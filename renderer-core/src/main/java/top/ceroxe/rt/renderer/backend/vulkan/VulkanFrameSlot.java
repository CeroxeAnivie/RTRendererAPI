package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.system.MemoryStack;
import top.ceroxe.rt.renderer.RtStallTelemetrySink;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease;
import top.ceroxe.rt.renderer.rt.device.RtCommandContext;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.rt.renderer.rt.device.RtGpuImage;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.device.interop.VulkanImportedSemaphore;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded ownership unit for one output image, frame constants, descriptor set, and producer fence.
 *
 * <p>The slot becomes writable only after both the Vulkan producer and the external consumer have
 * completed. Extent replacement is therefore local to a free slot and never invalidates a handle
 * or descriptor still visible outside the renderer.</p>
 */
final class VulkanFrameSlot implements AutoCloseable {
    private static final AtomicLong EXTERNAL_RESOURCE_IDS = new AtomicLong(1L);
    private final int index;
    private final VulkanDeviceRuntime device;
    private final VulkanFrameOutput frameOutput;
    private final boolean dedicatedAllocationRequired;
    private final boolean externalSemaphoreCompletionEnabled;
    private final boolean cpuFrameReadbackEnabled;
    private final boolean temporalEnabled;
    private final RtStallTelemetrySink stalls;
    private final RtGpuBuffer frameUniforms;

    private RtGpuImage outputImage;
    private long outputResourceId = -1L;
    private RtGpuImage motionImage;
    private RtGpuBuffer cpuReadback;
    private RtCommandContext.AsyncSubmission producerSubmission;
    private RtCommandContext.AsyncSubmission consumerSubmission;
    private VulkanImportedSemaphore consumerSemaphore;
    private boolean frameUniformsClosed;
    private State state = State.FREE;
    private int imageLayout = VK10.VK_IMAGE_LAYOUT_UNDEFINED;
    private boolean motionLayoutInitialized;
    private boolean externallyOwned;
    private long frameSequence = -1L;
    private long sceneRevision = -1L;
    private long descriptorEpoch = -1L;
    private long readyTimelineSemaphore;
    private long readyTimelineValue;
    private long observedProducerFrameSequence = -1L;
    private long observedProducerDescriptorEpoch = -1L;
    private boolean closed;

    VulkanFrameSlot(
            int index,
            VulkanDeviceRuntime device,
            VulkanFrameOutput frameOutput,
            boolean dedicatedAllocationRequired,
            boolean externalSemaphoreCompletionEnabled,
            boolean cpuFrameReadbackEnabled,
            RtStallTelemetrySink stalls,
            boolean temporalEnabled
    ) {
        if (index < 0) throw new IllegalArgumentException("frame slot index must not be negative");
        this.index = index;
        this.device = Objects.requireNonNull(device, "device");
        this.frameOutput = Objects.requireNonNull(frameOutput, "frameOutput");
        this.dedicatedAllocationRequired = dedicatedAllocationRequired;
        this.externalSemaphoreCompletionEnabled = externalSemaphoreCompletionEnabled;
        this.cpuFrameReadbackEnabled = cpuFrameReadbackEnabled;
        this.temporalEnabled = temporalEnabled;
        this.stalls = Objects.requireNonNull(stalls, "stalls");
        this.frameUniforms = RtGpuBuffer.createHostVisibleUploadBuffer(
                device.device(),
                device.allocator(),
                VulkanFrameUniformPacker.BYTE_COUNT,
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                this.stalls
        );
    }

    private static RuntimeException accumulate(RuntimeException failure, RuntimeException next) {
        if (failure == null) {
            return next;
        }
        failure.addSuppressed(next);
        return failure;
    }

    private static long nextExternalResourceId() {
        long identity = EXTERNAL_RESOURCE_IDS.getAndIncrement();
        if (identity <= 0L) {
            throw new IllegalStateException("external frame resource identity space exhausted");
        }
        return identity;
    }

    int index() {
        return index;
    }

    synchronized boolean writable() {
        requireOpen();
        return state == State.FREE;
    }

    synchronized boolean producerPending() {
        requireOpen();
        return state == State.SUBMITTED;
    }

    synchronized void prepare(int width, int height, byte[] uniformBytes) {
        requireState(State.FREE, "prepare frame slot");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("frame extent must be positive");
        }
        byte[] checkedUniforms = Objects.requireNonNull(uniformBytes, "uniformBytes");
        if (checkedUniforms.length != VulkanFrameUniformPacker.BYTE_COUNT) {
            throw new IllegalArgumentException("frame uniform payload has the wrong ABI size");
        }
        boolean resourcesMatch = outputImage != null
                && outputImage.width() == width
                && outputImage.height() == height
                && (!temporalEnabled || motionImage != null)
                && (!cpuFrameReadbackEnabled || cpuReadback != null);
        if (resourcesMatch) {
            frameUniforms.writeBytes(checkedUniforms);
            return;
        }

        RtGpuImage replacementOutput = null;
        RtGpuImage replacementMotion = null;
        RtGpuBuffer replacementReadback = null;
        try {
            replacementOutput = RtGpuImage.createExportableStorageImage(
                    device.physicalDevice(),
                    device.device(),
                    width,
                    height,
                    frameOutput.vkFormat(),
                    dedicatedAllocationRequired
            );
            replacementMotion = temporalEnabled
                    ? RtGpuImage.createStorageImage(
                    device.device(), device.allocator(), width, height,
                    VulkanTemporalImageSupport.MOTION_FORMAT
            )
                    : null;
            replacementReadback = cpuFrameReadbackEnabled
                    ? RtGpuBuffer.createHostVisibleBuffer(
                    device.device(),
                    device.allocator(),
                    frameOutput.byteCount(width, height),
                    VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    stalls
            )
                    : null;
            frameUniforms.writeBytes(checkedUniforms);
        } catch (RuntimeException | Error failure) {
            closeAfterFailedReplacement(replacementReadback, failure);
            closeAfterFailedReplacement(replacementMotion, failure);
            closeAfterFailedReplacement(replacementOutput, failure);
            throw failure;
        }

        RtGpuImage previousOutput = outputImage;
        RtGpuImage previousMotion = motionImage;
        RtGpuBuffer previousReadback = cpuReadback;
        outputImage = replacementOutput;
        outputResourceId = nextExternalResourceId();
        motionImage = replacementMotion;
        cpuReadback = replacementReadback;
        imageLayout = VK10.VK_IMAGE_LAYOUT_UNDEFINED;
        externallyOwned = false;
        motionLayoutInitialized = false;

        RuntimeException failure = null;
        failure = closeReplaced(previousReadback, failure);
        failure = closeReplaced(previousMotion, failure);
        failure = closeReplaced(previousOutput, failure);
        if (failure != null) throw failure;
    }

    private static void closeAfterFailedReplacement(AutoCloseable resource, Throwable failure) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static RuntimeException closeReplaced(AutoCloseable resource, RuntimeException failure) {
        if (resource == null) return failure;
        try {
            resource.close();
            return failure;
        } catch (RuntimeException closeFailure) {
            return accumulate(failure, closeFailure);
        } catch (Exception closeFailure) {
            return accumulate(failure, new IllegalStateException("native frame resource close failed", closeFailure));
        }
    }

    synchronized long requiredImageGrowthBytes(int width, int height) {
        requireState(State.FREE, "estimate frame slot growth");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("frame extent must be positive");
        if (outputImage != null && outputImage.width() == width && outputImage.height() == height
                && (!temporalEnabled || motionImage != null)) return 0L;
        long bytes = frameOutput.byteCount(width, height);
        if (temporalEnabled) {
            bytes = Math.addExact(bytes, Math.multiplyExact((long) width * height, 4L));
        }
        return bytes;
    }

    synchronized long trimIdleOutputImage() {
        requireState(State.FREE, "trim idle frame output");
        RtGpuImage idleOutput = outputImage;
        RtGpuImage idleMotion = motionImage;
        RtGpuBuffer idleReadback = cpuReadback;
        if (idleOutput == null && idleMotion == null && idleReadback == null) {
            return 0L;
        }
        long releasedBytes = idleOutput == null ? 0L : idleOutput.allocationSize();
        if (idleMotion != null) {
            releasedBytes = Math.addExact(releasedBytes, idleMotion.allocationSize());
        }
        /*
         * Detach the whole resource set before native teardown. This preserves the slot invariant
         * even when one close fails and lets every independent allocation attempt cleanup.
         */
        outputImage = null;
        outputResourceId = -1L;
        motionImage = null;
        cpuReadback = null;
        imageLayout = VK10.VK_IMAGE_LAYOUT_UNDEFINED;
        externallyOwned = false;
        motionLayoutInitialized = false;

        RuntimeException failure = null;
        failure = closeReplaced(idleReadback, failure);
        failure = closeReplaced(idleMotion, failure);
        failure = closeReplaced(idleOutput, failure);
        if (failure != null) throw failure;
        return releasedBytes;
    }

    synchronized RtGpuImage outputImage() {
        requireOpen();
        if (outputImage == null) throw new IllegalStateException("frame slot has no output image");
        return outputImage;
    }

    synchronized RtGpuBuffer frameUniforms() {
        requireOpen();
        return frameUniforms;
    }

    synchronized RtGpuBuffer cpuReadback() {
        requireOpen();
        if (!cpuFrameReadbackEnabled || cpuReadback == null) {
            throw new IllegalStateException("frame slot has no managed CPU readback buffer");
        }
        return cpuReadback;
    }

    synchronized RtGpuBuffer cpuReadbackOrNull() {
        requireOpen();
        return cpuReadback;
    }

    synchronized byte[] captureCpuRgba8() {
        requireState(State.COMPLETED, "capture managed CPU frame");
        byte[] nativePixels = cpuReadback().readBytes(frameOutput.byteCount(
                outputImage.width(), outputImage.height()
        ));
        return frameOutput.linearHdr()
                ? VulkanFramePixelCodec.convertLinearHdrRgba16fToSdrRgba8(nativePixels)
                : nativePixels;
    }

    synchronized RtGpuImage motionImage() {
        requireOpen();
        if (!temporalEnabled || motionImage == null) {
            throw new IllegalStateException("frame slot has no temporal motion image");
        }
        return motionImage;
    }

    synchronized boolean motionLayoutInitialized() {
        requireOpen();
        return motionLayoutInitialized;
    }

    synchronized int imageLayout() {
        requireOpen();
        return imageLayout;
    }

    synchronized boolean externallyOwned() {
        requireOpen();
        return externallyOwned;
    }

    synchronized void submitted(
            RtCommandContext.AsyncSubmission submission,
            long frameSequence,
            long sceneRevision,
            long descriptorEpoch,
            boolean externallyOwnedAfterSubmission,
            VulkanDeviceRuntime.ManagedPresentationSignal readySignal
    ) {
        requireState(State.FREE, "publish frame submission");
        if (frameSequence < 0L || sceneRevision < 0L || descriptorEpoch < 0L) {
            throw new IllegalArgumentException("frame submission counters must not be negative");
        }
        producerSubmission = Objects.requireNonNull(submission, "submission");
        this.frameSequence = frameSequence;
        this.sceneRevision = sceneRevision;
        this.descriptorEpoch = descriptorEpoch;
        VulkanDeviceRuntime.ManagedPresentationSignal checkedSignal =
                Objects.requireNonNull(readySignal, "readySignal");
        readyTimelineSemaphore = checkedSignal.semaphore();
        readyTimelineValue = checkedSignal.value();
        imageLayout = VK10.VK_IMAGE_LAYOUT_GENERAL;
        if (temporalEnabled) motionLayoutInitialized = true;
        externallyOwned = externallyOwnedAfterSubmission;
        state = State.SUBMITTED;
        VulkanFrameFlightRecorder.record(
                VulkanFrameFlightRecorder.PHASE_ADMITTED,
                index,
                frameSequence,
                sceneRevision,
                descriptorEpoch,
                outputImage.width(),
                outputImage.height(),
                0L,
                0L
        );
    }

    synchronized boolean pollProducer() {
        requireOpen();
        if (state == State.CONSUMER_PENDING) {
            if (!consumerSubmission.pollComplete()) return false;
            consumerSubmission = null;
            consumerSemaphore.close();
            consumerSemaphore = null;
            recordConsumerCompleted();
            resetIdentity();
            state = State.FREE;
            return false;
        }
        if (state != State.SUBMITTED) return state == State.COMPLETED || state == State.LEASED;
        if (!producerSubmission.pollComplete()) return false;
        RtCommandContext.Timing timing = finishProducerCompletion();
        state = State.COMPLETED;
        recordProducerCompleted(timing);
        return true;
    }

    private RtCommandContext.Timing finishProducerCompletion() {
        RtCommandContext.Timing timing = producerSubmission.timing();
        producerSubmission = null;
        observedProducerFrameSequence = Math.max(observedProducerFrameSequence, frameSequence);
        observedProducerDescriptorEpoch = Math.max(observedProducerDescriptorEpoch, descriptorEpoch);
        return timing;
    }

    private void recordProducerCompleted(RtCommandContext.Timing timing) {
        VulkanFrameFlightRecorder.record(
                VulkanFrameFlightRecorder.PHASE_PRODUCER_COMPLETED,
                index,
                frameSequence,
                sceneRevision,
                descriptorEpoch,
                outputImage.width(),
                outputImage.height(),
                timing.fenceResidencyUpperBoundNanos(),
                timing.notReadyPolls()
        );
    }

    synchronized boolean completed() {
        requireOpen();
        return state == State.COMPLETED;
    }

    synchronized long frameSequence() {
        return frameSequence;
    }

    /**
     * Returns the scene revision captured when the current frame identity was submitted.
     */
    synchronized long renderedSceneRevision() {
        return sceneRevision;
    }

    synchronized long descriptorEpoch() {
        return descriptorEpoch;
    }

    synchronized long observedProducerFrameSequence() {
        return observedProducerFrameSequence;
    }

    synchronized long observedProducerDescriptorEpoch() {
        return observedProducerDescriptorEpoch;
    }

    synchronized boolean managedPresentable() {
        requireOpen();
        return state == State.COMPLETED
                || state == State.SUBMITTED && readyTimelineSemaphore != 0L;
    }

    synchronized void discardCompleted() {
        requireState(State.COMPLETED, "discard completed frame");
        resetIdentity();
        state = State.FREE;
    }

    synchronized GpuFrameLease acquire() {
        requireState(State.COMPLETED, "acquire completed frame");
        return acquirePreparedFrame();
    }

    synchronized GpuFrameLease acquireManaged() {
        requireOpen();
        if (!managedPresentable()) {
            throw new IllegalStateException("cannot acquire managed frame while frame slot is " + state);
        }
        return acquirePreparedFrame();
    }

    private GpuFrameLease acquirePreparedFrame() {
        VulkanExportedMemoryHandle memoryHandle = new VulkanExportedMemoryHandle(
                this::exportSharedMemoryHandle
        );
        boolean transferred = false;
        try {
            GpuFrameLease.FrameDescriptor descriptor = GpuFrameLease.FrameDescriptor.builder()
                    .resourceId(outputResourceId)
                    .frameSequence(frameSequence)
                    .renderedSceneRevision(sceneRevision)
                    .extent(outputImage.width(), outputImage.height())
                    .format(new GpuFrameLease.VulkanFormat(outputImage.format()))
                    .imageType(new GpuFrameLease.VulkanImageType(VK10.VK_IMAGE_TYPE_2D))
                    .imageTiling(new GpuFrameLease.VulkanImageTiling(VK10.VK_IMAGE_TILING_OPTIMAL))
                    .imageUsage(new GpuFrameLease.VulkanImageUsage(outputImage.usageFlags()))
                    .imageCreateFlags(new GpuFrameLease.VulkanImageCreateFlags(0))
                    .imageLayout(new GpuFrameLease.VulkanImageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL))
                    .mipLevels(1)
                    .arrayLayers(1)
                    .sampleCount(new GpuFrameLease.VulkanSampleCount(VK10.VK_SAMPLE_COUNT_1_BIT))
                    .sharingMode(new GpuFrameLease.VulkanSharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE))
                    .producerQueueFamily(new GpuFrameLease.VulkanQueueFamily(device.queueFamilyIndex()))
                    .memoryTypeIndex(outputImage.memoryTypeIndex())
                    .allocationSize(outputImage.allocationSize())
                    .allocationOffset(0L)
                    .dedicatedAllocation(outputImage.dedicatedAllocation())
                    .build();
            long leasedSequence = frameSequence;
            VulkanGpuFrameLease lease = new VulkanGpuFrameLease(
                    descriptor,
                    memoryHandle,
                    externalSemaphoreCompletionEnabled
                            ? GpuFrameLease.ConsumerCompletionCapabilities.cpuAndBinarySemaphore()
                            : GpuFrameLease.ConsumerCompletionCapabilities.cpuOnly(),
                    completion -> consumerCompleted(leasedSequence, completion),
                    new VulkanManagedFrameLease.NativeFrame(
                            device.device(),
                            outputImage.image(),
                            device.queueFamilyIndex(),
                            externallyOwned,
                            readyTimelineSemaphore,
                            readyTimelineValue
                    )
            );
            state = State.LEASED;
            VulkanFrameFlightRecorder.record(
                    VulkanFrameFlightRecorder.PHASE_LEASE_ACQUIRED,
                    index,
                    frameSequence,
                    sceneRevision,
                    descriptorEpoch,
                    outputImage.width(),
                    outputImage.height(),
                    0L,
                    0L
            );
            transferred = true;
            return lease;
        } finally {
            if (!transferred) memoryHandle.close();
        }
    }

    /**
     * Converts a managed frame into the public expert ownership contract only when an expert
     * consumer actually asks for a native handle. The normal presenter path never calls this
     * method and therefore pays no external queue-family transition.
     */
    private synchronized long exportSharedMemoryHandle() {
        requireState(State.LEASED, "export shared frame memory");
        if (!externallyOwned) {
            RtCommandContext.AsyncSubmission release = device.frameCommands().submitOneTimeAsync(
                    this::recordExternalOwnershipRelease
            );
            release.close();
            externallyOwned = true;
        }
        return outputImage.exportSharedWin32MemoryHandle();
    }

    private void recordExternalOwnershipRelease(
            VkCommandBuffer commandBuffer,
            MemoryStack stack
    ) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                .sType$Default()
                .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                .dstAccessMask(0)
                .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .srcQueueFamilyIndex(device.queueFamilyIndex())
                .dstQueueFamilyIndex(VK11.VK_QUEUE_FAMILY_EXTERNAL)
                .image(outputImage.image());
        barrier.subresourceRange()
                .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1);
        VK10.vkCmdPipelineBarrier(
                commandBuffer,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                0,
                null,
                null,
                barrier
        );
    }

    private synchronized void consumerCompleted(
            long leasedSequence,
            GpuFrameLease.ConsumerCompletion completion
    ) {
        requireState(State.LEASED, "release frame consumer");
        if (frameSequence != leasedSequence) {
            throw new IllegalStateException("frame slot identity changed while externally leased");
        }
        if (completion instanceof GpuFrameLease.ExternalSemaphoreSignal signal) {
            if (!externalSemaphoreCompletionEnabled) {
                throw new UnsupportedOperationException("external semaphore completion is unavailable");
            }
            VulkanImportedSemaphore imported = VulkanImportedSemaphore.importBinary(device.device(), signal);
            boolean transferred = false;
            try {
                RtCommandContext.AsyncSubmission wait =
                        device.frameCommands().submitSemaphoreWaitAsync(imported.semaphore());
                consumerSemaphore = imported;
                consumerSubmission = wait;
                state = State.CONSUMER_PENDING;
                transferred = true;
                return;
            } finally {
                if (!transferred) imported.close();
            }
        }
        if (producerSubmission != null) {
            producerSubmission.close();
            recordProducerCompleted(finishProducerCompletion());
        }
        recordConsumerCompleted();
        resetIdentity();
        state = State.FREE;
    }

    private void recordConsumerCompleted() {
        VulkanFrameFlightRecorder.record(
                VulkanFrameFlightRecorder.PHASE_CONSUMER_COMPLETED,
                index,
                frameSequence,
                sceneRevision,
                descriptorEpoch,
                outputImage.width(),
                outputImage.height(),
                0L,
                0L
        );
    }

    private void resetIdentity() {
        frameSequence = -1L;
        sceneRevision = -1L;
        descriptorEpoch = -1L;
        readyTimelineSemaphore = 0L;
        readyTimelineValue = 0L;
    }

    private void requireState(State required, String operation) {
        requireOpen();
        if (state != required) {
            throw new IllegalStateException("cannot " + operation + " while frame slot is " + state);
        }
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("frame slot is closed");
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        if (state == State.LEASED) {
            throw new IllegalStateException("cannot close a frame slot retained by an external consumer");
        }
        RuntimeException failure = null;
        if (consumerSubmission != null) {
            try {
                consumerSubmission.close();
                consumerSubmission = null;
            } catch (RuntimeException ex) {
                failure = ex;
            }
        }
        /* The imported semaphore may still be referenced by a timed-out wait submission. */
        if (consumerSubmission == null && consumerSemaphore != null) {
            try {
                consumerSemaphore.close();
                consumerSemaphore = null;
            } catch (RuntimeException ex) {
                failure = accumulate(failure, ex);
            }
        }
        if (producerSubmission != null) {
            try {
                producerSubmission.close();
                producerSubmission = null;
            } catch (RuntimeException ex) {
                failure = accumulate(failure, ex);
            }
        }

        /*
         * Neither GPU submission may outlive the image or uniform buffers it
         * references. A timeout keeps these resources owned by the slot so a
         * later close can retry after the fence or external semaphore advances.
         */
        if (producerSubmission == null && consumerSubmission == null) {
            if (outputImage != null) {
                try {
                    outputImage.close();
                    outputImage = null;
                    outputResourceId = -1L;
                } catch (RuntimeException ex) {
                    failure = accumulate(failure, ex);
                }
            }
            if (motionImage != null) {
                try {
                    motionImage.close();
                    motionImage = null;
                } catch (RuntimeException ex) {
                    failure = accumulate(failure, ex);
                }
            }
            if (cpuReadback != null) {
                try {
                    cpuReadback.close();
                    cpuReadback = null;
                } catch (RuntimeException ex) {
                    failure = accumulate(failure, ex);
                }
            }
            if (!frameUniformsClosed) {
                try {
                    frameUniforms.close();
                    frameUniformsClosed = true;
                } catch (RuntimeException ex) {
                    failure = accumulate(failure, ex);
                }
            }
        }
        closed = consumerSubmission == null
                && consumerSemaphore == null
                && producerSubmission == null
                && outputImage == null
                && motionImage == null
                && cpuReadback == null
                && frameUniformsClosed;
        if (failure != null) throw failure;
    }

    private enum State {
        FREE,
        SUBMITTED,
        COMPLETED,
        LEASED,
        CONSUMER_PENDING
    }
}
