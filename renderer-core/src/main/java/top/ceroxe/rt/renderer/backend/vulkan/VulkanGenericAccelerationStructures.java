package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildRangeInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureCreateInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureDeviceAddressInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkAccelerationStructureInstanceKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import top.ceroxe.rt.renderer.api.AccelerationStructureBuildMode;
import top.ceroxe.rt.renderer.api.AccelerationStructureIndexFormat;
import top.ceroxe.rt.renderer.api.AccelerationStructureInstance;
import top.ceroxe.rt.renderer.api.AccelerationStructureKind;
import top.ceroxe.rt.renderer.api.AccelerationStructureResource;
import top.ceroxe.rt.renderer.api.AccelerationStructureTriangleGeometry;
import top.ceroxe.rt.renderer.api.BuildBottomLevelAccelerationStructureCommand;
import top.ceroxe.rt.renderer.api.BuildTopLevelAccelerationStructureCommand;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.device.VulkanFailures;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Native AS ownership for the generic command lane.
 *
 * <p>This class deliberately owns only Vulkan AS storage, temporary build inputs, and the
 * submission-local readiness state.  It has no scene, material, camera, or descriptor policy;
 * those belong to the generic command plan and ray-tracing pipeline collaborators.</p>
 */
final class VulkanGenericAccelerationStructures implements AutoCloseable {
    private final VulkanDeviceRuntime device;
    private final Map<AccelerationStructureResource, Record> resident = new LinkedHashMap<>();
    private boolean closed;

    VulkanGenericAccelerationStructures(VulkanDeviceRuntime device) {
        this.device = Objects.requireNonNull(device, "device");
    }

    Compilation beginCompilation() {
        requireOpen();
        return new Compilation();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        for (Record record : resident.values()) {
            try {
                record.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        resident.clear();
        if (failure != null) throw failure;
    }

    /** One mutable planning scope, owned by exactly one command-plan compilation. */
    final class Compilation implements VulkanGenericDescriptorSetBank.AccelerationStructureResolver, AutoCloseable {
        private final Map<AccelerationStructureResource, Record> staged = new LinkedHashMap<>();
        private final List<PreparedBuild> builds = new ArrayList<>();
        private final java.util.Set<Record> used = new LinkedHashSet<>();
        private final java.util.Set<Record> destroyed = new LinkedHashSet<>();
        private boolean committed;
        private boolean closed;

        PreparedBuild prepareBottom(
                BuildBottomLevelAccelerationStructureCommand command,
                List<TriangleInput> geometries
        ) {
            requireMutable();
            Objects.requireNonNull(command, "command");
            List<TriangleInput> checked = List.copyOf(geometries);
            if (checked.isEmpty()) throw new IllegalArgumentException("BLAS build requires resolved geometry");
            if (staged.containsKey(command.destination())) {
                throw new IllegalArgumentException("one command transaction cannot build the same AS destination twice: "
                        + command.destination().id());
            }
            Record destination = prepareDestination(command.destination(), command.mode(), checked, null, 0L);
            PreparedBuild build = new PreparedBuild(destination, command.mode(), checked, null, null);
            staged.put(command.destination(), destination);
            builds.add(build);
            return build;
        }

        PreparedBuild prepareTop(BuildTopLevelAccelerationStructureCommand command) {
            requireMutable();
            Objects.requireNonNull(command, "command");
            if (staged.containsKey(command.destination())) {
                throw new IllegalArgumentException("one command transaction cannot build the same AS destination twice: "
                        + command.destination().id());
            }
            for (AccelerationStructureInstance instance : command.instances()) {
                Record bottom = lookup(instance.bottomLevel());
                if (bottom.kind != AccelerationStructureKind.BOTTOM_LEVEL) {
                    throw new IllegalArgumentException("TLAS instance does not resolve to a bottom-level AS");
                }
                used.add(bottom);
            }
            RtGpuBuffer instanceBuffer = createInstanceBuffer(command.instances(), this);
            try {
                Record destination = prepareDestination(
                        command.destination(), command.mode(), null, command.instances(), instanceBuffer.deviceAddress()
                );
                PreparedBuild build = new PreparedBuild(destination, command.mode(), null, command.instances(), instanceBuffer);
                instanceBuffer = null;
                staged.put(command.destination(), destination);
                builds.add(build);
                return build;
            } finally {
                if (instanceBuffer != null) instanceBuffer.close();
            }
        }

        @Override
        public long requireTopLevel(AccelerationStructureResource resource) {
            Record record = lookup(Objects.requireNonNull(resource, "resource"));
            if (record.kind != AccelerationStructureKind.TOP_LEVEL) {
                throw new IllegalArgumentException("shader AS descriptor requires a top-level structure");
            }
            used.add(record);
            return record.handle;
        }

        void destroy(AccelerationStructureResource resource) {
            requireMutable();
            Record record = lookup(Objects.requireNonNull(resource, "resource"));
            if (staged.containsKey(resource)) {
                throw new IllegalArgumentException("an AS cannot be built and destroyed in one command transaction");
            }
            if (used.contains(record)) {
                throw new IllegalArgumentException("an AS cannot be used and destroyed in one command transaction");
            }
            record.requireReadyForMutation();
            destroyed.add(record);
        }

        void recordBuild(VkCommandBuffer commandBuffer, MemoryStack stack, PreparedBuild build) {
            requireOpen();
            Objects.requireNonNull(build, "build").record(commandBuffer, stack, this);
        }

        void commit(long submissionSequence) {
            requireMutable();
            if (submissionSequence < 0L) throw new IllegalArgumentException("submission sequence must not be negative");
            java.util.Set<Record> inFlight = new LinkedHashSet<>(used);
            for (PreparedBuild build : builds) inFlight.add(build.destination);
            for (Record record : inFlight) record.markPending(submissionSequence);
            resident.putAll(staged);
            for (Record record : destroyed) {
                resident.remove(record.descriptor);
                record.close();
            }
            committed = true;
        }

        void complete(long submissionSequence) {
            if (!committed) throw new IllegalStateException("cannot complete an uncommitted AS compilation");
            java.util.Set<Record> inFlight = new LinkedHashSet<>(used);
            for (PreparedBuild build : builds) inFlight.add(build.destination);
            for (Record record : inFlight) record.markComplete(submissionSequence);
            for (PreparedBuild build : builds) {
                build.closeTemporary();
            }
        }

        void discardAfterDeviceFailure() {
            for (PreparedBuild build : builds) build.closeTemporary();
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (!committed) {
                for (PreparedBuild build : builds) build.closeForAbort();
            }
        }

        private Record prepareDestination(
                AccelerationStructureResource descriptor,
                AccelerationStructureBuildMode mode,
                List<TriangleInput> triangles,
                List<AccelerationStructureInstance> instances,
                long buildInputAddress
        ) {
            Record existing = resident.get(descriptor);
            if (mode == AccelerationStructureBuildMode.UPDATE) {
                if (existing == null) throw new IllegalStateException("AS UPDATE requires a previously built exact destination");
                existing.requireReadyForMutation();
                return existing;
            }
            if (existing != null) {
                existing.requireReadyForMutation();
                return existing;
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkAccelerationStructureBuildGeometryInfoKHR.Buffer info = buildInfo(
                        stack, descriptor, mode, triangles, instances, buildInputAddress
                );
                IntBuffer counts = primitiveCounts(stack, triangles, instances);
                VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
                KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR(
                        device.device(), KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                        info.get(0), counts, sizes
                );
                if (sizes.accelerationStructureSize() <= 0L || sizes.buildScratchSize() <= 0L) {
                    throw new IllegalStateException("Vulkan returned invalid AS build sizes");
                }
                return Record.create(device, descriptor, sizes.accelerationStructureSize());
            }
        }

        private Record lookup(AccelerationStructureResource descriptor) {
            Record record = staged.get(descriptor);
            if (record == null) record = resident.get(descriptor);
            if (record == null) throw new IllegalArgumentException("acceleration structure is not resident: " + descriptor.id());
            if (destroyed.contains(record)) throw new IllegalStateException("acceleration structure is pending destruction");
            return record;
        }

        private void requireMutable() {
            requireOpen();
            if (committed) throw new IllegalStateException("AS compilation is already committed");
        }

        private void requireOpen() {
            if (closed) throw new IllegalStateException("AS compilation is closed");
        }
    }

    /** Exact resolved triangle input, including the generation that owns its device address. */
    record TriangleInput(
            AccelerationStructureTriangleGeometry geometry,
            VulkanGenericResourceRegistry.BufferRecord vertices,
            VulkanGenericResourceRegistry.BufferRecord indices
    ) {
        TriangleInput {
            geometry = Objects.requireNonNull(geometry, "geometry");
            vertices = Objects.requireNonNull(vertices, "vertices");
            if (geometry.indices().isPresent() != (indices != null)) {
                throw new IllegalArgumentException("resolved AS index input does not match the API geometry");
            }
            if (vertices.buffer().deviceAddress() == 0L || (indices != null && indices.buffer().deviceAddress() == 0L)) {
                throw new IllegalArgumentException("AS build input buffers require shader device addresses");
            }
        }
    }

    /** Prepared native command and fence-bounded temporary allocations for one build. */
    final class PreparedBuild {
        private final Record destination;
        private final AccelerationStructureBuildMode mode;
        private final List<TriangleInput> triangles;
        private final List<AccelerationStructureInstance> instances;
        private final RtGpuBuffer scratch;
        private final RtGpuBuffer instanceBuffer;
        private boolean temporaryClosed;

        private PreparedBuild(
                Record destination,
                AccelerationStructureBuildMode mode,
                List<TriangleInput> triangles,
                List<AccelerationStructureInstance> instances,
                RtGpuBuffer preparedInstanceBuffer
        ) {
            this.destination = Objects.requireNonNull(destination, "destination");
            this.mode = Objects.requireNonNull(mode, "mode");
            this.triangles = triangles == null ? null : List.copyOf(triangles);
            this.instances = instances == null ? null : List.copyOf(instances);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkAccelerationStructureBuildGeometryInfoKHR.Buffer info = buildInfo(
                        stack, destination.descriptor, mode, this.triangles, this.instances,
                        preparedInstanceBuffer == null ? 0L : preparedInstanceBuffer.deviceAddress()
                );
                IntBuffer counts = primitiveCounts(stack, this.triangles, this.instances);
                VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
                KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR(
                        device.device(), KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                        info.get(0), counts, sizes
                );
                long scratchSize = mode == AccelerationStructureBuildMode.UPDATE
                        ? sizes.updateScratchSize() : sizes.buildScratchSize();
                if (scratchSize <= 0L) throw new IllegalStateException("Vulkan returned invalid AS scratch size");
                this.scratch = RtGpuBuffer.createDeviceAddressBuffer(
                        device.device(), device.allocator(), alignedBytes(scratchSize, device.accelerationStructureScratchAlignment()),
                        VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                );
                if (this.instances == null) {
                    this.instanceBuffer = null;
                } else {
                    this.instanceBuffer = Objects.requireNonNull(preparedInstanceBuffer, "preparedInstanceBuffer");
                }
            } catch (RuntimeException failure) {
                throw failure;
            }
        }

        void record(VkCommandBuffer commandBuffer, MemoryStack commandStack, Compilation scope) {
            /*
             * A command session may contain millions of captured draw builds.  The descriptor
             * structs are needed only while vkCmdBuildAccelerationStructuresKHR records the
             * command; retaining them on the session's shared MemoryStack makes native stack
             * usage grow with the entire frame and eventually raises OutOfMemoryError: Out of
             * stack space.  Keep this allocation scoped to one build so complete capture frames
             * remain bounded without changing the public command contract.
             */
            try (MemoryStack buildStack = MemoryStack.stackPush()) {
                VkAccelerationStructureBuildGeometryInfoKHR.Buffer info = buildInfo(
                        buildStack, destination.descriptor, mode, triangles, instances,
                        instanceBuffer == null ? 0L : instanceBuffer.deviceAddress()
                );
                info.get(0).dstAccelerationStructure(destination.handle)
                        .scratchData(address -> address.deviceAddress(alignedAddress(scratch,
                                device.accelerationStructureScratchAlignment())));
                if (mode == AccelerationStructureBuildMode.UPDATE) info.get(0).srcAccelerationStructure(destination.handle);
                VkAccelerationStructureBuildRangeInfoKHR.Buffer ranges = ranges(buildStack, triangles, instances);
                PointerBuffer pointers = buildStack.mallocPointer(ranges.remaining());
                for (int index = 0; index < ranges.remaining(); index++) pointers.put(index, ranges.get(index).address());
                KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR(commandBuffer, info, pointers);
                recordBuildBarrier(commandBuffer, buildStack);
            }
        }

        void closeTemporary() {
            if (temporaryClosed) return;
            temporaryClosed = true;
            RuntimeException failure = null;
            try { scratch.close(); } catch (RuntimeException closeFailure) { failure = closeFailure; }
            if (instanceBuffer != null) {
                try { instanceBuffer.close(); } catch (RuntimeException closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                }
            }
            if (failure != null) throw failure;
        }

        void closeForAbort() {
            closeTemporary();
            if (!resident.containsValue(destination)) destination.close();
        }
    }

    private static VkAccelerationStructureBuildGeometryInfoKHR.Buffer buildInfo(
            MemoryStack stack,
            AccelerationStructureResource destination,
            AccelerationStructureBuildMode mode,
            List<TriangleInput> triangles,
            List<AccelerationStructureInstance> instances,
            long destinationHandle
    ) {
        boolean bottom = destination.kind() == AccelerationStructureKind.BOTTOM_LEVEL;
        VkAccelerationStructureGeometryKHR.Buffer geometries = bottom
                ? triangleGeometries(stack, Objects.requireNonNull(triangles, "triangles"))
                : instanceGeometry(stack, Objects.requireNonNull(instances, "instances"), destinationHandle);
        int flags = KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR;
        if (destination.allowUpdate()) flags |= KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR;
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer result =
                VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        result.get(0).sType$Default()
                .type(bottom ? KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR
                        : KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                .flags(flags)
                .mode(mode == AccelerationStructureBuildMode.UPDATE
                        ? KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR
                        : KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .geometryCount(geometries.remaining()).pGeometries(geometries);
        return result;
    }

    private static VkAccelerationStructureGeometryKHR.Buffer triangleGeometries(
            MemoryStack stack, List<TriangleInput> inputs
    ) {
        VkAccelerationStructureGeometryKHR.Buffer result = VkAccelerationStructureGeometryKHR.calloc(inputs.size(), stack);
        for (int index = 0; index < inputs.size(); index++) {
            TriangleInput input = inputs.get(index);
            AccelerationStructureTriangleGeometry geometry = input.geometry();
            long vertexAddress = Math.addExact(input.vertices().buffer().deviceAddress(), geometry.vertices().range().offsetBytes());
            long indexAddress = geometry.indices().isPresent()
                    ? Math.addExact(Objects.requireNonNull(input.indices()).buffer().deviceAddress(),
                    geometry.indices().orElseThrow().range().offsetBytes())
                    : 0L;
            result.get(index).sType$Default()
                    .geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                    .geometry(data -> data.triangles(value -> value.sType$Default()
                            .vertexFormat(VK10.VK_FORMAT_R32G32B32_SFLOAT)
                            .vertexData(address -> address.deviceAddress(vertexAddress))
                            .vertexStride(geometry.vertexStrideBytes())
                            .maxVertex(geometry.vertexCount() - 1)
                            .indexType(geometry.indexFormat().map(VulkanGenericAccelerationStructures::vulkanIndexType)
                                    .orElse(KHRAccelerationStructure.VK_INDEX_TYPE_NONE_KHR))
                            .indexData(address -> address.deviceAddress(indexAddress))));
        }
        return result;
    }

    private static VkAccelerationStructureGeometryKHR.Buffer instanceGeometry(
            MemoryStack stack, List<AccelerationStructureInstance> instances, long instanceBufferHandle
    ) {
        if (instanceBufferHandle == 0L) {
            /* The size query does not consume instance data; use a non-zero sentinel address only
             * after the caller has allocated the transient instance buffer in PreparedBuild. */
            instanceBufferHandle = 1L;
        }
        long address = instanceBufferHandle;
        VkAccelerationStructureGeometryKHR.Buffer result = VkAccelerationStructureGeometryKHR.calloc(1, stack);
        result.get(0).sType$Default()
                .geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_INSTANCES_KHR)
                .geometry(data -> data.instances(value -> value.sType$Default()
                        .arrayOfPointers(false).data(pointer -> pointer.deviceAddress(address))));
        return result;
    }

    private static IntBuffer primitiveCounts(
            MemoryStack stack, List<TriangleInput> triangles, List<AccelerationStructureInstance> instances
    ) {
        if (triangles != null) {
            IntBuffer result = stack.mallocInt(triangles.size());
            for (int index = 0; index < triangles.size(); index++) {
                AccelerationStructureTriangleGeometry geometry = triangles.get(index).geometry();
                result.put(index, geometry.indices().isPresent() ? geometry.indexCount() / 3 : geometry.vertexCount() / 3);
            }
            return result;
        }
        return stack.ints(Objects.requireNonNull(instances, "instances").size());
    }

    private static VkAccelerationStructureBuildRangeInfoKHR.Buffer ranges(
            MemoryStack stack, List<TriangleInput> triangles, List<AccelerationStructureInstance> instances
    ) {
        int count = triangles == null ? 1 : triangles.size();
        VkAccelerationStructureBuildRangeInfoKHR.Buffer ranges = VkAccelerationStructureBuildRangeInfoKHR.calloc(count, stack);
        for (int index = 0; index < count; index++) {
            int primitives = triangles == null ? Objects.requireNonNull(instances, "instances").size()
                    : (triangles.get(index).geometry().indices().isPresent()
                    ? triangles.get(index).geometry().indexCount() / 3 : triangles.get(index).geometry().vertexCount() / 3);
            ranges.get(index).primitiveCount(primitives).primitiveOffset(0).firstVertex(0).transformOffset(0);
        }
        return ranges;
    }

    private RtGpuBuffer createInstanceBuffer(List<AccelerationStructureInstance> instances, Compilation scope) {
        long bytes = Math.multiplyExact((long) instances.size(), VkAccelerationStructureInstanceKHR.SIZEOF);
        RtGpuBuffer result = RtGpuBuffer.createHostVisibleDeviceAddressBuffer(
                device.device(), device.allocator(), bytes,
                KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                top.ceroxe.rt.renderer.RtStallTelemetrySink.NOOP
        );
        try {
            ByteBuffer records = ByteBuffer.allocateDirect(Math.toIntExact(bytes)).order(ByteOrder.nativeOrder());
            for (AccelerationStructureInstance instance : instances) {
                Record bottom = scope.lookup(instance.bottomLevel());
                putInstance(records, instance, bottom.deviceAddress);
            }
            records.flip();
            byte[] copy = new byte[records.remaining()];
            records.get(copy);
            result.writeBytes(copy);
            return result;
        } catch (RuntimeException failure) {
            result.close();
            throw failure;
        }
    }

    private static void putInstance(ByteBuffer target, AccelerationStructureInstance instance, long bottomAddress) {
        var transform = instance.transform();
        target.putFloat(transform.m00()).putFloat(transform.m01()).putFloat(transform.m02()).putFloat(transform.m03());
        target.putFloat(transform.m10()).putFloat(transform.m11()).putFloat(transform.m12()).putFloat(transform.m13());
        target.putFloat(transform.m20()).putFloat(transform.m21()).putFloat(transform.m22()).putFloat(transform.m23());
        target.putInt(instance.customIndex() | (instance.visibilityMask() << 24));
        int flags = (instance.forceOpaque() ? KHRAccelerationStructure.VK_GEOMETRY_INSTANCE_FORCE_OPAQUE_BIT_KHR : 0)
                | (instance.forceNoOpaque() ? KHRAccelerationStructure.VK_GEOMETRY_INSTANCE_FORCE_NO_OPAQUE_BIT_KHR : 0);
        target.putInt(instance.shaderBindingTableRecordOffset() | (flags << 24));
        target.putLong(bottomAddress);
    }

    private static int vulkanIndexType(AccelerationStructureIndexFormat format) {
        return switch (format) {
            case UINT16 -> VK10.VK_INDEX_TYPE_UINT16;
            case UINT32 -> VK10.VK_INDEX_TYPE_UINT32;
        };
    }

    private static long alignedBytes(long value, int alignment) {
        if (value <= 0L || alignment <= 0) throw new IllegalArgumentException("invalid AS scratch size or alignment");
        long remainder = value % alignment;
        return remainder == 0L ? value : Math.addExact(value, alignment - remainder);
    }

    private static long alignedAddress(RtGpuBuffer buffer, int alignment) {
        long base = buffer.deviceAddress();
        long remainder = base % alignment;
        long result = remainder == 0L ? base : Math.addExact(base, alignment - remainder);
        if (result - base >= buffer.sizeBytes()) throw new IllegalStateException("AS scratch alignment exceeds allocation");
        return result;
    }

    private static void recordBuildBarrier(VkCommandBuffer commandBuffer, MemoryStack stack) {
        var barrier = org.lwjgl.vulkan.VkMemoryBarrier.calloc(1, stack);
        barrier.get(0).sType$Default()
                .srcAccessMask(KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR)
                .dstAccessMask(KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
        VK10.vkCmdPipelineBarrier(commandBuffer,
                KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR
                        | KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                0, barrier, null, null);
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("generic AS registry is closed");
    }

    private static final class Record implements AutoCloseable {
        private final VkDevice device;
        private final AccelerationStructureResource descriptor;
        private final AccelerationStructureKind kind;
        private final RtGpuBuffer storage;
        private final long handle;
        private final long deviceAddress;
        private long pendingSequence = -1L;
        private boolean closed;

        private Record(VkDevice device, AccelerationStructureResource descriptor, RtGpuBuffer storage, long handle, long deviceAddress) {
            this.device = Objects.requireNonNull(device, "device");
            this.descriptor = descriptor;
            this.kind = descriptor.kind();
            this.storage = storage;
            this.handle = handle;
            this.deviceAddress = deviceAddress;
        }

        static Record create(VulkanDeviceRuntime device, AccelerationStructureResource descriptor, long storageBytes) {
            RtGpuBuffer storage = RtGpuBuffer.createDeviceAddressBuffer(device.device(), device.allocator(), storageBytes,
                    KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR);
            long handle = VK10.VK_NULL_HANDLE;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkAccelerationStructureCreateInfoKHR info = VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default()
                        .buffer(storage.buffer()).offset(0L).size(storageBytes)
                        .type(descriptor.kind() == AccelerationStructureKind.BOTTOM_LEVEL
                                ? KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR
                                : KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);
                LongBuffer output = stack.longs(VK10.VK_NULL_HANDLE);
                VulkanFailures.check(KHRAccelerationStructure.vkCreateAccelerationStructureKHR(device.device(), info, null, output),
                        "vkCreateAccelerationStructureKHR.generic");
                handle = output.get(0);
                VkAccelerationStructureDeviceAddressInfoKHR addressInfo =
                        VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack).sType$Default().accelerationStructure(handle);
                long address = KHRAccelerationStructure.vkGetAccelerationStructureDeviceAddressKHR(device.device(), addressInfo);
                if (address == 0L) throw new IllegalStateException("generic acceleration structure has no device address");
                return new Record(device.device(), descriptor, storage, handle, address);
            } catch (RuntimeException failure) {
                if (handle != VK10.VK_NULL_HANDLE) KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(device.device(), handle, null);
                storage.close();
                throw failure;
            }
        }

        void requireReadyForMutation() {
            if (pendingSequence >= 0L) throw new IllegalStateException("AS has unresolved GPU build sequence " + pendingSequence);
            if (closed) throw new IllegalStateException("AS is closed");
        }

        void markPending(long sequence) {
            if (pendingSequence == sequence) return;
            requireReadyForMutation();
            pendingSequence = sequence;
        }

        void markComplete(long sequence) {
            if (pendingSequence != sequence) throw new IllegalStateException("AS completion sequence does not match pending build");
            pendingSequence = -1L;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(device, handle, null);
            storage.close();
        }
    }
}
