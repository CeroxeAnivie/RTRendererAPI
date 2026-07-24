package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildRangeInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryBarrier;
import top.ceroxe.mcvulkanrt.renderer.rt.device.RtCommandContext;
import top.ceroxe.mcvulkanrt.renderer.rt.device.RtGpuBuffer;

import java.nio.IntBuffer;
import java.util.List;
import java.util.Objects;

/**
 * Builds a BLAS directly from persistent device-address triangle streams.
 *
 * <p>The caller retains the source buffers through completion. This builder owns only the
 * destination AS, scratch allocation, command submission, and fence. It deliberately performs no
 * CPU geometry upload: GPUScene remains the single geometry-storage authority.</p>
 */
public final class RtDeviceTriangleBlasBuilder {
    private static final int POSITION_STRIDE_BYTES = 3 * Float.BYTES;
    private static final String GPU_TIMING_LABEL = "gpuSceneTriangleBlas";

    private RtDeviceTriangleBlasBuilder() {
    }

    public static PendingBuild submit(
            VkDevice device,
            long allocator,
            RtCommandContext commands,
            int scratchAlignmentBytes,
            List<Geometry> geometries
    ) {
        VkDevice checkedDevice = Objects.requireNonNull(device, "device");
        RtCommandContext checkedCommands = Objects.requireNonNull(commands, "commands");
        List<Geometry> checkedGeometries = List.copyOf(Objects.requireNonNull(geometries, "geometries"));
        if (allocator == 0L) throw new IllegalArgumentException("allocator must not be null");
        if (scratchAlignmentBytes <= 0 || (scratchAlignmentBytes & (scratchAlignmentBytes - 1)) != 0) {
            throw new IllegalArgumentException("scratch alignment must be a positive power of two");
        }
        if (checkedGeometries.isEmpty()) {
            throw new IllegalArgumentException("triangle BLAS requires at least one geometry");
        }

        RtAccelerationStructure blas = null;
        RtGpuBuffer scratch = null;
        RtCommandContext.AsyncSubmission submission = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureGeometryKHR.Buffer nativeGeometries = nativeGeometries(stack, checkedGeometries);
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer queryInfoBuffer =
                    VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            VkAccelerationStructureBuildGeometryInfoKHR queryInfo = queryInfoBuffer.get(0)
                    .sType$Default()
                    .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                    .flags(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                    .mode(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .geometryCount(nativeGeometries.remaining())
                    .pGeometries(nativeGeometries);
            IntBuffer maxPrimitiveCounts = stack.mallocInt(checkedGeometries.size());
            for (int index = 0; index < checkedGeometries.size(); index++) {
                maxPrimitiveCounts.put(index, checkedGeometries.get(index).primitiveCount());
            }
            VkAccelerationStructureBuildSizesInfoKHR sizes =
                    VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR(
                    checkedDevice,
                    KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    queryInfo,
                    maxPrimitiveCounts,
                    sizes
            );
            if (sizes.accelerationStructureSize() <= 0L || sizes.buildScratchSize() <= 0L) {
                throw new IllegalStateException("Vulkan returned invalid triangle BLAS build sizes");
            }

            blas = RtAccelerationStructure.createBottomLevel(
                    stack,
                    checkedDevice,
                    allocator,
                    sizes.accelerationStructureSize(),
                    sizes.buildScratchSize(),
                    scratchAlignmentBytes,
                    checkedCommands.stallTelemetry()
            );
            long scratchCapacity = Math.addExact(sizes.buildScratchSize(), scratchAlignmentBytes - 1L);
            scratch = RtGpuBuffer.createDeviceAddressBuffer(
                    checkedDevice,
                    allocator,
                    scratchCapacity,
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    checkedCommands.stallTelemetry()
            );
            long scratchAddress = alignUp(scratch.deviceAddress(), scratchAlignmentBytes);
            if (scratchAddress > scratch.deviceAddress() + scratch.sizeBytes() - sizes.buildScratchSize()) {
                throw new IllegalStateException("aligned BLAS scratch range exceeds its allocation");
            }

            RtAccelerationStructure ownedBlas = blas;
            RtGpuBuffer ownedScratch = scratch;
            submission = checkedCommands.submitTimedOneTimeAsync(
                    GPU_TIMING_LABEL,
                    (commandBuffer, commandStack) -> recordBuild(
                            commandBuffer,
                            commandStack,
                            checkedGeometries,
                            ownedBlas.handle(),
                            scratchAddress
                    )
            );
            PendingBuild pending = new PendingBuild(
                    submission,
                    ownedScratch,
                    ownedBlas,
                    checkedGeometries.size(),
                    checkedGeometries.stream().mapToLong(Geometry::primitiveCount).sum()
            );
            submission = null;
            scratch = null;
            blas = null;
            return pending;
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            closeSuppressing(failure, submission);
            closeSuppressing(failure, scratch);
            closeSuppressing(failure, blas);
            throw failure;
        }
    }

    private static void recordBuild(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            List<Geometry> geometries,
            long destinationBlas,
            long scratchAddress
    ) {
        VkMemoryBarrier.Buffer inputReady = VkMemoryBarrier.calloc(1, stack);
        inputReady.get(0).sType$Default()
                .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
        VK10.vkCmdPipelineBarrier(
                commandBuffer,
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                0,
                inputReady,
                null,
                null
        );

        VkAccelerationStructureGeometryKHR.Buffer nativeGeometries = nativeGeometries(stack, geometries);
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer buildInfo =
                VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        buildInfo.get(0)
                .sType$Default()
                .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                .mode(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .geometryCount(nativeGeometries.remaining())
                .pGeometries(nativeGeometries)
                .dstAccelerationStructure(destinationBlas)
                .scratchData(address -> address.deviceAddress(scratchAddress));
        VkAccelerationStructureBuildRangeInfoKHR.Buffer ranges =
                VkAccelerationStructureBuildRangeInfoKHR.calloc(geometries.size(), stack);
        PointerBuffer rangePointers = stack.mallocPointer(geometries.size());
        for (int index = 0; index < geometries.size(); index++) {
            ranges.get(index)
                    .primitiveCount(geometries.get(index).primitiveCount())
                    .primitiveOffset(0)
                    .firstVertex(0)
                    .transformOffset(0);
            rangePointers.put(index, ranges.get(index).address());
        }
        KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR(commandBuffer, buildInfo, rangePointers);

        VkMemoryBarrier.Buffer buildReady = VkMemoryBarrier.calloc(1, stack);
        buildReady.get(0).sType$Default()
                .srcAccessMask(KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR)
                .dstAccessMask(KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
        VK10.vkCmdPipelineBarrier(
                commandBuffer,
                KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR
                        | KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                0,
                buildReady,
                null,
                null
        );
    }

    private static VkAccelerationStructureGeometryKHR.Buffer nativeGeometries(
            MemoryStack stack,
            List<Geometry> geometries
    ) {
        VkAccelerationStructureGeometryKHR.Buffer nativeGeometries =
                VkAccelerationStructureGeometryKHR.calloc(geometries.size(), stack);
        for (int index = 0; index < geometries.size(); index++) {
            Geometry geometry = geometries.get(index);
            nativeGeometries.get(index)
                    .sType$Default()
                    .geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                    .geometry(data -> data.triangles(triangles -> triangles
                            .sType$Default()
                            .vertexFormat(VK10.VK_FORMAT_R32G32B32_SFLOAT)
                            .vertexData(address -> address.deviceAddress(geometry.positionDeviceAddress()))
                            .vertexStride(POSITION_STRIDE_BYTES)
                            .maxVertex(geometry.vertexCount() - 1)
                            .indexType(VK10.VK_INDEX_TYPE_UINT32)
                            .indexData(address -> address.deviceAddress(geometry.indexDeviceAddress()))
                            .transformData(address -> address.deviceAddress(0L))))
                    .flags(geometry.opaque() ? KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR : 0);
        }
        return nativeGeometries;
    }

    private static long alignUp(long value, int alignment) {
        return Math.addExact(value, alignment - 1L) & -((long) alignment);
    }

    private static RuntimeException closeCollecting(RuntimeException failure, AutoCloseable resource) {
        if (resource == null) return failure;
        try {
            resource.close();
        } catch (Exception closeFailure) {
            RuntimeException wrapped = closeFailure instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException("failed to release triangle BLAS resource", closeFailure);
            if (failure == null) return wrapped;
            failure.addSuppressed(wrapped);
        }
        return failure;
    }

    private static void closeSuppressing(Throwable failure, AutoCloseable resource) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    public record Geometry(
            long positionDeviceAddress,
            long indexDeviceAddress,
            int vertexCount,
            int primitiveCount,
            boolean opaque
    ) {
        public Geometry {
            if (positionDeviceAddress == 0L || indexDeviceAddress == 0L
                    || (positionDeviceAddress & 3L) != 0L || (indexDeviceAddress & 3L) != 0L) {
                throw new IllegalArgumentException("triangle geometry device addresses must be non-null and aligned");
            }
            if (vertexCount <= 0 || primitiveCount <= 0) {
                throw new IllegalArgumentException("triangle geometry counts must be positive");
            }
        }
    }

    public static final class PendingBuild implements AutoCloseable {
        private final RtCommandContext.AsyncSubmission submission;
        private final RtGpuBuffer scratch;
        private final RtAccelerationStructure blas;
        private final int geometryCount;
        private final long primitiveCount;
        private boolean closed;

        private PendingBuild(
                RtCommandContext.AsyncSubmission submission,
                RtGpuBuffer scratch,
                RtAccelerationStructure blas,
                int geometryCount,
                long primitiveCount
        ) {
            this.submission = Objects.requireNonNull(submission, "submission");
            this.scratch = Objects.requireNonNull(scratch, "scratch");
            this.blas = Objects.requireNonNull(blas, "blas");
            this.geometryCount = geometryCount;
            this.primitiveCount = primitiveCount;
        }

        public synchronized RtAccelerationStructure completeIfReady() {
            requireOpen();
            if (!submission.pollComplete()) return null;
            return releaseCompleted();
        }

        public synchronized RtAccelerationStructure waitAndComplete() {
            requireOpen();
            submission.close();
            return releaseCompleted();
        }

        public int geometryCount() {
            return geometryCount;
        }

        public long primitiveCount() {
            return primitiveCount;
        }

        private RtAccelerationStructure releaseCompleted() {
            RuntimeException failure = null;
            failure = closeCollecting(failure, scratch);
            if (failure != null) {
                failure = closeCollecting(failure, blas);
                closed = true;
                throw failure;
            }
            closed = true;
            return blas;
        }

        private void requireOpen() {
            if (closed) throw new IllegalStateException("triangle BLAS build is already completed or closed");
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            RuntimeException failure = null;
            failure = closeCollecting(failure, submission);
            failure = closeCollecting(failure, scratch);
            failure = closeCollecting(failure, blas);
            if (failure != null) throw failure;
        }
    }
}
