package top.ceroxe.rt.renderer.rt.acceleration;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import top.ceroxe.rt.renderer.rt.device.RtCommandContext;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.rt.renderer.rt.device.VulkanFailures;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
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
    private static final String COMPACTION_GPU_TIMING_LABEL = "gpuSceneTriangleBlasCompaction";

    private RtDeviceTriangleBlasBuilder() {
    }

    /**
     * Submits a compactable triangle BLAS build from persistent device-address streams.
     *
     * <p>The source vertex and index buffers remain caller-owned and must stay live until the
     * returned build completes or is closed. The returned object exclusively owns destination,
     * scratch, query-pool, and submission resources.</p>
     *
     * @param device                logical device that owns every native resource in this operation
     * @param allocator             non-null VMA allocator handle associated with {@code device}
     * @param commands              command context used for build and optional compaction submissions
     * @param scratchAlignmentBytes positive power-of-two scratch-address alignment
     * @param geometries            non-empty geometry list whose source buffers remain live through completion
     * @return an owning asynchronous BLAS build handle
     * @throws NullPointerException     if an object argument or geometry element is {@code null}
     * @throws IllegalArgumentException if a handle, alignment, address, or geometry count is invalid
     * @throws IllegalStateException    if Vulkan reports unusable build sizes
     */
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
        long queryPool = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureGeometryKHR.Buffer nativeGeometries = nativeGeometries(stack, checkedGeometries);
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer queryInfoBuffer =
                    VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            VkAccelerationStructureBuildGeometryInfoKHR queryInfo = queryInfoBuffer.get(0)
                    .sType$Default()
                    .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                    .flags(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR
                            | KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR)
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

            VkQueryPoolCreateInfo queryInfoCreate = VkQueryPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .queryType(KHRAccelerationStructure.VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR)
                    .queryCount(1);
            LongBuffer queryHandle = stack.longs(0L);
            VulkanFailures.check(
                    VK10.vkCreateQueryPool(checkedDevice, queryInfoCreate, null, queryHandle),
                    "vkCreateQueryPool.blasCompaction"
            );
            queryPool = queryHandle.get(0);

            RtAccelerationStructure ownedBlas = blas;
            RtGpuBuffer ownedScratch = scratch;
            long ownedQueryPool = queryPool;
            submission = checkedCommands.submitTimedOneTimeAsync(
                    GPU_TIMING_LABEL,
                    (commandBuffer, commandStack) -> recordBuild(
                            commandBuffer,
                            commandStack,
                            checkedGeometries,
                            ownedBlas.handle(),
                            scratchAddress,
                            ownedQueryPool
                    )
            );
            PendingBuild pending = new PendingBuild(
                    submission,
                    ownedScratch,
                    ownedBlas,
                    checkedDevice,
                    allocator,
                    checkedCommands,
                    scratchAlignmentBytes,
                    ownedQueryPool,
                    checkedGeometries.size(),
                    checkedGeometries.stream().mapToLong(Geometry::primitiveCount).sum()
            );
            submission = null;
            scratch = null;
            blas = null;
            queryPool = 0L;
            return pending;
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            closeSuppressing(failure, submission);
            closeSuppressing(failure, scratch);
            closeSuppressing(failure, blas);
            if (queryPool != 0L) VK10.vkDestroyQueryPool(checkedDevice, queryPool, null);
            throw failure;
        }
    }

    private static void recordBuild(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            List<Geometry> geometries,
            long destinationBlas,
            long scratchAddress,
            long queryPool
    ) {
        RtAccelerationStructureCommandRecorder.recordInputUploadBarrier(commandBuffer, stack);

        VkAccelerationStructureGeometryKHR.Buffer nativeGeometries = nativeGeometries(stack, geometries);
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer buildInfo =
                VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        buildInfo.get(0)
                .sType$Default()
                .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR
                        | KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR)
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
        VK10.vkCmdResetQueryPool(commandBuffer, queryPool, 0, 1);
        KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR(commandBuffer, buildInfo, rangePointers);

        RtAccelerationStructureCommandRecorder.recordBuildBarrier(commandBuffer, stack);
        KHRAccelerationStructure.vkCmdWriteAccelerationStructuresPropertiesKHR(
                commandBuffer,
                stack.longs(destinationBlas),
                KHRAccelerationStructure.VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR,
                queryPool,
                0
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

    private static void recordCompactionCopy(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            long source,
            long destination
    ) {
        VkCopyAccelerationStructureInfoKHR copy = VkCopyAccelerationStructureInfoKHR.calloc(stack)
                .sType$Default()
                .src(source)
                .dst(destination)
                .mode(KHRAccelerationStructure.VK_COPY_ACCELERATION_STRUCTURE_MODE_COMPACT_KHR);
        KHRAccelerationStructure.vkCmdCopyAccelerationStructureKHR(commandBuffer, copy);
        RtAccelerationStructureCommandRecorder.recordBuildBarrier(commandBuffer, stack);
    }

    /**
     * Device-resident indexed triangle stream used as one BLAS geometry.
     *
     * @param positionDeviceAddress aligned device address of tightly packed {@code vec3} positions
     * @param indexDeviceAddress    aligned device address of unsigned 32-bit triangle indices
     * @param vertexCount           positive number of addressable vertices
     * @param primitiveCount        positive number of indexed triangles
     * @param opaque                whether Vulkan may skip any-hit invocation for this geometry
     */
    public record Geometry(
            long positionDeviceAddress,
            long indexDeviceAddress,
            int vertexCount,
            int primitiveCount,
            boolean opaque
    ) {
        /**
         * Validates address alignment and non-empty geometry before native submission.
         */
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

    /**
     * Exclusive owner of a submitted BLAS build and its optional compaction pass.
     *
     * <p>A successful completion transfers exactly one acceleration structure to the caller. Until
     * that transfer, callers must retain this owner and all source geometry buffers.</p>
     */
    public static final class PendingBuild implements AutoCloseable {
        private final VkDevice device;
        private final long allocator;
        private final RtCommandContext commands;
        private final int scratchAlignmentBytes;
        private final int geometryCount;
        private final long primitiveCount;
        private final long sourceStorageBytes;
        private RtCommandContext.AsyncSubmission submission;
        private RtGpuBuffer scratch;
        private RtAccelerationStructure blas;
        private RtAccelerationStructure compactedBlas;
        private long queryPool;
        private long completedStorageBytes = -1L;
        private boolean compacted;
        private boolean closed;
        private boolean copyPending;

        private PendingBuild(
                RtCommandContext.AsyncSubmission submission,
                RtGpuBuffer scratch,
                RtAccelerationStructure blas,
                VkDevice device,
                long allocator,
                RtCommandContext commands,
                int scratchAlignmentBytes,
                long queryPool,
                int geometryCount,
                long primitiveCount
        ) {
            this.submission = Objects.requireNonNull(submission, "submission");
            this.scratch = Objects.requireNonNull(scratch, "scratch");
            this.blas = Objects.requireNonNull(blas, "blas");
            this.device = Objects.requireNonNull(device, "device");
            this.allocator = allocator;
            this.commands = Objects.requireNonNull(commands, "commands");
            this.scratchAlignmentBytes = scratchAlignmentBytes;
            if (queryPool == 0L) throw new IllegalArgumentException("queryPool must not be null");
            this.queryPool = queryPool;
            this.geometryCount = geometryCount;
            this.primitiveCount = primitiveCount;
            this.sourceStorageBytes = blas.storageBytes();
        }

        /**
         * Advances build or compaction state without blocking.
         *
         * @return the completed caller-owned BLAS, or {@code null} while either GPU pass is pending
         * @throws IllegalStateException if this build was already completed or closed
         */
        public synchronized RtAccelerationStructure completeIfReady() {
            requireOpen();
            if (!submission.pollComplete()) return null;
            return copyPending ? releaseCompacted() : beginCompaction();
        }

        /**
         * Waits for all required GPU work and transfers the completed BLAS to the caller.
         *
         * @return the completed caller-owned BLAS
         * @throws IllegalStateException if this build was already completed or closed
         */
        public synchronized RtAccelerationStructure waitAndComplete() {
            requireOpen();
            submission.close();
            if (!copyPending) {
                RtAccelerationStructure uncompressed = beginCompaction();
                if (uncompressed != null) return uncompressed;
            }
            submission.close();
            return releaseCompacted();
        }

        /**
         * Returns the immutable geometry-range count captured at submission.
         *
         * @return the submitted geometry count
         */
        public int geometryCount() {
            return geometryCount;
        }

        /**
         * Returns the immutable primitive total captured at submission.
         *
         * @return total submitted triangle count
         */
        public long primitiveCount() {
            return primitiveCount;
        }

        /**
         * Returns the original destination size for compaction telemetry.
         *
         * @return uncompacted BLAS storage bytes
         */
        public long sourceStorageBytes() {
            return sourceStorageBytes;
        }

        /**
         * Returns the selected result size after build-size evaluation.
         *
         * @return final BLAS storage bytes, or {@code -1} until a completion path selects the result
         */
        public long completedStorageBytes() {
            return completedStorageBytes;
        }

        /**
         * Reports whether compaction replaced the original allocation.
         *
         * @return whether a compacted allocation was selected
         */
        public boolean compacted() {
            return compacted;
        }

        private RtAccelerationStructure beginCompaction() {
            RuntimeException failure = null;
            failure = closeCollecting(failure, scratch);
            scratch = null;
            if (failure != null) {
                failure = closeCollecting(failure, blas);
                closed = true;
                throw failure;
            }
            long compactedBytes = compactedSize();
            if (compactedBytes <= 0L || compactedBytes >= blas.storageBytes()) {
                return releaseUncompacted();
            }
            RtAccelerationStructure destination = null;
            RtCommandContext.AsyncSubmission copySubmission = null;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                destination = RtAccelerationStructure.createBottomLevel(
                        stack,
                        device,
                        allocator,
                        compactedBytes,
                        blas.buildScratchBytes(),
                        scratchAlignmentBytes,
                        commands.stallTelemetry()
                );
                RtAccelerationStructure ownedDestination = destination;
                copySubmission = commands.submitTimedOneTimeAsync(
                        COMPACTION_GPU_TIMING_LABEL,
                        (commandBuffer, commandStack) -> recordCompactionCopy(
                                commandBuffer, commandStack, blas.handle(), ownedDestination.handle())
                );
                compactedBlas = destination;
                submission = copySubmission;
                copyPending = true;
                completedStorageBytes = compactedBytes;
                compacted = true;
                return null;
            } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
                closeSuppressing(ex, copySubmission);
                closeSuppressing(ex, destination);
                closeSuppressing(ex, blas);
                destroyQueryPool();
                closed = true;
                throw ex;
            }
        }

        private long compactedSize() {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer result = stack.mallocLong(1);
                VulkanFailures.check(
                        VK10.vkGetQueryPoolResults(
                                device, queryPool, 0, 1, result, Long.BYTES, VK10.VK_QUERY_RESULT_64_BIT),
                        "vkGetQueryPoolResults.blasCompaction"
                );
                return result.get(0);
            }
        }

        private RtAccelerationStructure releaseUncompacted() {
            RtAccelerationStructure result = blas;
            blas = null;
            completedStorageBytes = sourceStorageBytes;
            destroyQueryPool();
            closed = true;
            return result;
        }

        private RtAccelerationStructure releaseCompacted() {
            RuntimeException failure = closeCollecting(null, blas);
            blas = null;
            if (failure != null) {
                failure = closeCollecting(failure, compactedBlas);
                compactedBlas = null;
                destroyQueryPool();
                closed = true;
                throw failure;
            }
            RtAccelerationStructure result = compactedBlas;
            compactedBlas = null;
            destroyQueryPool();
            closed = true;
            return result;
        }

        private void destroyQueryPool() {
            long handle = queryPool;
            queryPool = 0L;
            if (handle != 0L) VK10.vkDestroyQueryPool(device, handle, null);
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
            failure = closeCollecting(failure, compactedBlas);
            destroyQueryPool();
            if (failure != null) throw failure;
        }
    }
}
