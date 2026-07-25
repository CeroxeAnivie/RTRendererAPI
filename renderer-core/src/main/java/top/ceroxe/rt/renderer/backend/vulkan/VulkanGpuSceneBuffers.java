package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import top.ceroxe.rt.renderer.rt.device.RtCommandContext;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.rt.renderer.rt.device.VulkanMemoryBudgetPolicy;
import top.ceroxe.rt.renderer.rt.device.VulkanMemoryBudgetSnapshot;

import java.util.*;
import java.util.function.Supplier;

/**
 * Persistent Vulkan buffer generations for the generic GPUScene ABI.
 *
 * <p>One pending transfer may exist at a time. Sparse writes update a capacity-stable target in
 * place on the ordered renderer queue. Growth allocates a successor, copies the previous contents,
 * applies sparse writes, and activates only after the submission fence completes. Superseded
 * buffers remain alive until the descriptor completion epoch supplied by the session.</p>
 */
final class VulkanGpuSceneBuffers implements VulkanGpuSceneTransferQueue {
    private static final int MAX_COPY_REGIONS_PER_COMMAND = 256;
    private static final int BASE_USAGE = VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT
            | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT
            | VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    private static final int GEOMETRY_USAGE =
            KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;

    private final VkDevice device;
    private final long allocator;
    private final RtCommandContext commands;
    private final Supplier<VulkanMemoryBudgetSnapshot> memoryBudget;
    private final RtGpuBuffer descriptorFallback;
    private final EnumMap<VulkanGpuSceneUploadPlanner.Target, RtGpuBuffer> active =
            new EnumMap<>(VulkanGpuSceneUploadPlanner.Target.class);
    private final ArrayList<RetiredBuffer> retired = new ArrayList<>();

    private Pending pending;
    private long activeRevision = -1L;
    private boolean closed;

    VulkanGpuSceneBuffers(VkDevice device, long allocator, RtCommandContext commands) {
        this(device, allocator, commands, null);
    }

    VulkanGpuSceneBuffers(
            VkDevice device,
            long allocator,
            RtCommandContext commands,
            Supplier<VulkanMemoryBudgetSnapshot> memoryBudget
    ) {
        this.device = Objects.requireNonNull(device, "device");
        if (allocator == 0L) throw new IllegalArgumentException("allocator must not be null");
        this.allocator = allocator;
        this.commands = Objects.requireNonNull(commands, "commands");
        this.memoryBudget = memoryBudget;
        if (commands.orderedQueueCount() != 1) {
            throw new IllegalArgumentException("GPUScene uploads require one ordered Vulkan queue lane");
        }
        /*
         * Vulkan descriptors must remain valid even when an optional stream or an empty scene has
         * no allocation yet. One inert word is shared by every absent lane; shaders can reach it
         * only if the corresponding active record violates the CPU ABI, which is separately gated.
         */
        this.descriptorFallback = RtGpuBuffer.createDeviceAddressBuffer(
                device,
                allocator,
                Integer.BYTES,
                BASE_USAGE | GEOMETRY_USAGE,
                commands.stallTelemetry()
        );
    }

    private static boolean hasInPlaceWrites(VulkanGpuSceneTransferPlan.Plan transfer) {
        return transfer.targets().stream().anyMatch(capacity -> !capacity.grows());
    }

    private static void recordCopies(
            VkCommandBuffer commandBuffer,
            long source,
            long target,
            List<VulkanGpuSceneTransferPlan.StagedCopy> copies
    ) {
        for (int first = 0; first < copies.size(); first += MAX_COPY_REGIONS_PER_COMMAND) {
            int count = Math.min(MAX_COPY_REGIONS_PER_COMMAND, copies.size() - first);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferCopy.Buffer regions = VkBufferCopy.calloc(count, stack);
                for (int offset = 0; offset < count; offset++) {
                    VulkanGpuSceneTransferPlan.StagedCopy copy = copies.get(first + offset);
                    regions.get(offset).srcOffset(copy.sourceOffsetBytes())
                            .dstOffset(copy.targetOffsetBytes()).size(copy.byteCount());
                }
                VK10.vkCmdCopyBuffer(commandBuffer, source, target, regions);
            }
        }
    }

    private static void recordSingleCopy(
            VkCommandBuffer commandBuffer, long source, long target,
            long sourceOffset, long targetOffset, long bytes, MemoryStack stack
    ) {
        if (source == 0L || target == 0L || bytes <= 0L) {
            throw new IllegalArgumentException("GPUScene previous-generation copy is invalid");
        }
        VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
        region.get(0).srcOffset(sourceOffset).dstOffset(targetOffset).size(bytes);
        VK10.vkCmdCopyBuffer(commandBuffer, source, target, region);
    }

    private static boolean geometryTarget(VulkanGpuSceneUploadPlanner.Target target) {
        return switch (target) {
            case POSITIONS, NORMALS, TANGENTS, TEXTURE_COORDINATES, COLORS, INDICES,
                 TRIANGLE_MATERIAL_SLOTS -> true;
            default -> false;
        };
    }

    private static RuntimeException closeNewTargets(
            Map<VulkanGpuSceneUploadPlanner.Target, RtGpuBuffer> targets,
            EnumSet<VulkanGpuSceneUploadPlanner.Target> newlyAllocated,
            RuntimeException failure
    ) {
        RuntimeException result = failure;
        for (VulkanGpuSceneUploadPlanner.Target target : newlyAllocated) {
            try {
                targets.get(target).close();
            } catch (RuntimeException ex) {
                result = collect(result, ex);
            }
        }
        return result;
    }

    private static RuntimeException collect(RuntimeException current, RuntimeException next) {
        if (current == null) return next;
        current.addSuppressed(next);
        return current;
    }

    @Override
    public synchronized Pending submit(VulkanGpuSceneUploadPlanner.Plan uploadPlan) {
        requireOpen();
        if (pending != null) throw new IllegalStateException("a GPUScene transfer is already pending");
        VulkanGpuSceneUploadPlanner.Plan uploads = Objects.requireNonNull(uploadPlan, "uploadPlan");
        if (uploads.revision() <= activeRevision) {
            throw new IllegalArgumentException("GPUScene buffer revision must advance");
        }
        VulkanGpuSceneTransferPlan.Plan transfer = VulkanGpuSceneTransferPlan.build(
                uploads, target -> {
                    RtGpuBuffer buffer = active.get(target);
                    return buffer == null ? 0L : buffer.sizeBytes();
                }
        );
        if (transfer.isEmpty()) {
            pending = Pending.empty(this, transfer.revision());
            return pending;
        }
        requireMemoryHeadroom(transfer);

        EnumMap<VulkanGpuSceneUploadPlanner.Target, RtGpuBuffer> targets =
                new EnumMap<>(VulkanGpuSceneUploadPlanner.Target.class);
        EnumSet<VulkanGpuSceneUploadPlanner.Target> newlyAllocated =
                EnumSet.noneOf(VulkanGpuSceneUploadPlanner.Target.class);
        RtGpuBuffer staging = null;
        RtCommandContext.AsyncSubmission submission = null;
        boolean transferred = false;
        try {
            for (VulkanGpuSceneTransferPlan.TargetCapacity capacity : transfer.targets()) {
                RtGpuBuffer current = active.get(capacity.target());
                if (capacity.grows()) {
                    RtGpuBuffer candidate = createTarget(capacity.target(), capacity.capacityBytes());
                    targets.put(capacity.target(), candidate);
                    newlyAllocated.add(capacity.target());
                } else {
                    if (current == null || current.sizeBytes() != capacity.capacityBytes()) {
                        throw new IllegalStateException("stable GPUScene target capacity diverged from transfer plan");
                    }
                    targets.put(capacity.target(), current);
                }
            }
            byte[] stagingBytes = transfer.stagingBytes();
            if (stagingBytes.length > 0) {
                staging = RtGpuBuffer.createHostVisibleUploadBuffer(
                        device, allocator, stagingBytes.length,
                        VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT, commands.stallTelemetry()
                );
                staging.writeBytes(stagingBytes);
                RtGpuBuffer ownedStaging = staging;
                submission = commands.submitTimedOneTimeAsync(
                        "gpuSceneUpload",
                        (commandBuffer, stack) -> recordTransfer(
                                commandBuffer, stack, transfer, ownedStaging, targets
                        )
                );
            }
            pending = new Pending(
                    this, transfer.revision(), transfer, targets, newlyAllocated,
                    staging, submission
            );
            transferred = true;
            return pending;
        } finally {
            if (!transferred) {
                if (submission != null) submission.close();
                if (staging != null) staging.close();
                closeNewTargets(targets, newlyAllocated, null);
            }
        }
    }

    private void requireMemoryHeadroom(VulkanGpuSceneTransferPlan.Plan transfer) {
        if (memoryBudget == null) {
            return;
        }
        long allocationGrowthBytes = transfer.allocationGrowthBytes();
        VulkanMemoryBudgetPolicy.Admission admission = VulkanMemoryBudgetPolicy.evaluate(
                Objects.requireNonNull(memoryBudget.get(), "memory budget snapshot"),
                allocationGrowthBytes
        );
        if (!admission.admitted()) {
            throw new VulkanMemoryBudgetRejectedException(
                    "GPUScene allocation rejected before native allocation: growthBytes="
                            + allocationGrowthBytes + ", reason=" + admission.reason()
            );
        }
    }

    @Override
    public synchronized boolean pollAndActivate(TransferTicket transfer, long retireAfterEpoch) {
        requireOpen();
        Pending checked = requirePending(transfer);
        if (retireAfterEpoch < 0L) throw new IllegalArgumentException("retireAfterEpoch must not be negative");
        if (checked.submission != null && !checked.submission.pollComplete()) {
            return false;
        }
        activate(checked, retireAfterEpoch);
        return true;
    }

    @Override
    public synchronized void waitAndActivate(TransferTicket transfer, long retireAfterEpoch) {
        requireOpen();
        Pending checked = requirePending(transfer);
        if (retireAfterEpoch < 0L) throw new IllegalArgumentException("retireAfterEpoch must not be negative");
        if (checked.submission != null) checked.submission.close();
        activate(checked, retireAfterEpoch);
    }

    @Override
    public synchronized void releaseRetiredThrough(long completedDescriptorEpoch) {
        requireOpen();
        if (completedDescriptorEpoch < 0L) {
            throw new IllegalArgumentException("completedDescriptorEpoch must not be negative");
        }
        RuntimeException failure = null;
        for (int index = retired.size() - 1; index >= 0; index--) {
            RetiredBuffer entry = retired.get(index);
            if (entry.safeAfterEpoch() <= completedDescriptorEpoch) {
                try {
                    entry.buffer().close();
                } catch (RuntimeException closeFailure) {
                    failure = collect(failure, closeFailure);
                }
                retired.remove(index);
            }
        }
        if (failure != null) throw failure;
    }

    @Override
    public synchronized BufferBinding buffer(VulkanGpuSceneUploadPlanner.Target target) {
        requireOpen();
        RtGpuBuffer buffer = active.get(Objects.requireNonNull(target, "target"));
        if (buffer == null && activeRevision >= 0L) buffer = descriptorFallback;
        return buffer == null ? null : new BufferBinding(
                buffer.buffer(), buffer.deviceAddress(), buffer.sizeBytes()
        );
    }

    @Override
    public synchronized TransferState state() {
        long activeBytes = active.values().stream().mapToLong(RtGpuBuffer::sizeBytes).sum();
        long retiredBytes = retired.stream().mapToLong(entry -> entry.buffer().sizeBytes()).sum();
        return new TransferState(activeRevision, active.size(), activeBytes, pending != null,
                retired.size(), retiredBytes);
    }

    private void activate(Pending completed, long retireAfterEpoch) {
        if (completed.activated) throw new IllegalStateException("GPUScene transfer was already activated");
        if (completed.staging != null) completed.staging.close();
        for (VulkanGpuSceneUploadPlanner.Target target : completed.newlyAllocated) {
            RtGpuBuffer successor = completed.targets.get(target);
            RtGpuBuffer previous = active.put(target, successor);
            if (previous != null) retired.add(new RetiredBuffer(retireAfterEpoch, previous));
        }
        activeRevision = completed.revision;
        completed.activated = true;
        pending = null;
    }

    private void recordTransfer(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VulkanGpuSceneTransferPlan.Plan transfer,
            RtGpuBuffer staging,
            EnumMap<VulkanGpuSceneUploadPlanner.Target, RtGpuBuffer> targets
    ) {
        if (hasInPlaceWrites(transfer)) {
            /*
             * Capacity-stable targets are descriptor-stable and therefore may still have reads
             * queued by the previous scene generation. Queue submission order alone is not a
             * memory dependency: make those reads complete before transfer writes overwrite the
             * same storage. Grown targets are fresh allocations and need no write-after-read gate.
             */
            VkMemoryBarrier.Buffer readToTransfer = VkMemoryBarrier.calloc(1, stack);
            readToTransfer.get(0).sType$Default()
                    .srcAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT
                            | KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR)
                    .dstAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT);
            VK10.vkCmdPipelineBarrier(
                    commandBuffer,
                    KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR
                            | KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0, readToTransfer, null, null
            );
        }
        for (VulkanGpuSceneTransferPlan.TargetCapacity capacity : transfer.targets()) {
            if (!capacity.grows() || capacity.copyPreviousBytes() == 0L) continue;
            RtGpuBuffer previous = active.get(capacity.target());
            RtGpuBuffer successor = targets.get(capacity.target());
            recordSingleCopy(commandBuffer, previous.buffer(), successor.buffer(), 0L, 0L,
                    capacity.copyPreviousBytes(), stack);
        }
        for (VulkanGpuSceneUploadPlanner.Target target : VulkanGpuSceneUploadPlanner.Target.values()) {
            List<VulkanGpuSceneTransferPlan.StagedCopy> copies = transfer.copies().stream()
                    .filter(copy -> copy.target() == target).toList();
            if (!copies.isEmpty()) {
                recordCopies(commandBuffer, staging.buffer(), targets.get(target).buffer(), copies);
            }
        }
        VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
        barrier.get(0).sType$Default()
                .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT
                        | KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
        VK10.vkCmdPipelineBarrier(
                commandBuffer,
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR
                        | KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                0, barrier, null, null
        );
    }

    private RtGpuBuffer createTarget(VulkanGpuSceneUploadPlanner.Target target, long capacity) {
        int usage = BASE_USAGE | (geometryTarget(target) ? GEOMETRY_USAGE : 0);
        return RtGpuBuffer.createDeviceAddressBuffer(
                device, allocator, capacity, usage, commands.stallTelemetry()
        );
    }

    private Pending requirePending(TransferTicket candidate) {
        if (!(candidate instanceof Pending checked)
                || checked.owner != this || checked != pending || checked.activated) {
            throw new IllegalStateException("GPUScene pending transfer is stale or belongs to another owner");
        }
        return checked;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("GPUScene buffers are closed");
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        if (pending != null) {
            if (pending.submission != null) {
                try {
                    pending.submission.close();
                } catch (RuntimeException ex) {
                    failure = collect(failure, ex);
                }
            }
            if (pending.staging != null) {
                try {
                    pending.staging.close();
                } catch (RuntimeException ex) {
                    failure = collect(failure, ex);
                }
            }
            failure = closeNewTargets(pending.targets, pending.newlyAllocated, failure);
            pending = null;
        }
        for (RtGpuBuffer buffer : active.values()) {
            try {
                buffer.close();
            } catch (RuntimeException ex) {
                failure = collect(failure, ex);
            }
        }
        active.clear();
        try {
            descriptorFallback.close();
        } catch (RuntimeException ex) {
            failure = collect(failure, ex);
        }
        for (RetiredBuffer entry : retired) {
            try {
                entry.buffer().close();
            } catch (RuntimeException ex) {
                failure = collect(failure, ex);
            }
        }
        retired.clear();
        if (failure != null) throw failure;
    }

    static final class Pending implements TransferTicket {
        private final VulkanGpuSceneBuffers owner;
        private final long revision;
        private final VulkanGpuSceneTransferPlan.Plan transfer;
        private final EnumMap<VulkanGpuSceneUploadPlanner.Target, RtGpuBuffer> targets;
        private final EnumSet<VulkanGpuSceneUploadPlanner.Target> newlyAllocated;
        private final RtGpuBuffer staging;
        private final RtCommandContext.AsyncSubmission submission;
        private boolean activated;

        private Pending(
                VulkanGpuSceneBuffers owner, long revision,
                VulkanGpuSceneTransferPlan.Plan transfer,
                EnumMap<VulkanGpuSceneUploadPlanner.Target, RtGpuBuffer> targets,
                EnumSet<VulkanGpuSceneUploadPlanner.Target> newlyAllocated,
                RtGpuBuffer staging,
                RtCommandContext.AsyncSubmission submission
        ) {
            this.owner = owner;
            this.revision = revision;
            this.transfer = transfer;
            this.targets = targets;
            this.newlyAllocated = newlyAllocated;
            this.staging = staging;
            this.submission = submission;
        }

        private static Pending empty(VulkanGpuSceneBuffers owner, long revision) {
            return new Pending(owner, revision,
                    new VulkanGpuSceneTransferPlan.Plan(revision, List.of(), List.of(), new byte[0]),
                    new EnumMap<>(VulkanGpuSceneUploadPlanner.Target.class),
                    EnumSet.noneOf(VulkanGpuSceneUploadPlanner.Target.class), null, null);
        }

        @Override
        public long revision() {
            return revision;
        }

        @Override
        public boolean activated() {
            return activated;
        }
    }

    private record RetiredBuffer(long safeAfterEpoch, RtGpuBuffer buffer) {
        RetiredBuffer {
            if (safeAfterEpoch < 0L) throw new IllegalArgumentException("retired buffer epoch must not be negative");
            buffer = Objects.requireNonNull(buffer, "buffer");
        }
    }
}
