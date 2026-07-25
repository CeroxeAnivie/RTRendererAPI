package top.ceroxe.rt.renderer.rt.acceleration;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.rt.renderer.scene.SectionTriangleMesh;

import java.util.List;
import java.util.Objects;

/**
 * Owns all temporary resources and state transitions for one prepared triangle BLAS build.
 */
final class PreparedTriangleBlasBuild implements AutoCloseable {
    private static final long FLOAT_POSITION_STRIDE_BYTES = 3L * Float.BYTES;

    private final List<PreparedTriangleGeometry> geometries;
    private final RtGpuBuffer scratchBuffer;
    private final RtAccelerationStructure accelerationStructure;
    private final long scratchAddress;
    private final SectionTriangleMesh mesh;
    private boolean accelerationStructureReleased;
    private boolean closed;

    PreparedTriangleBlasBuild(
            List<PreparedTriangleGeometry> geometries,
            RtGpuBuffer scratchBuffer,
            RtAccelerationStructure accelerationStructure,
            long scratchAddress,
            SectionTriangleMesh mesh
    ) {
        this.geometries = List.copyOf(Objects.requireNonNull(geometries, "geometries"));
        if (this.geometries.isEmpty()) {
            throw new IllegalArgumentException("prepared BLAS build must contain at least one geometry");
        }
        this.scratchBuffer = Objects.requireNonNull(scratchBuffer, "scratchBuffer");
        this.accelerationStructure = Objects.requireNonNull(accelerationStructure, "accelerationStructure");
        if (scratchAddress == 0L) {
            throw new IllegalArgumentException("scratchAddress must not be null");
        }
        this.scratchAddress = scratchAddress;
        this.mesh = mesh;
    }

    static VkAccelerationStructureGeometryKHR.Buffer nativeGeometries(
            MemoryStack stack,
            List<PreparedTriangleGeometry> geometries
    ) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(geometries, "geometries");
        if (geometries.isEmpty()) {
            throw new IllegalArgumentException("triangle BLAS must contain at least one geometry");
        }
        VkAccelerationStructureGeometryKHR.Buffer nativeGeometries =
                VkAccelerationStructureGeometryKHR.calloc(geometries.size(), stack);
        for (int index = 0; index < geometries.size(); index++) {
            PreparedTriangleGeometry geometry = Objects.requireNonNull(
                    geometries.get(index), "geometries[" + index + "]"
            );
            nativeGeometries.get(index)
                    .sType$Default()
                    .geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                    .geometry(data -> data.triangles(triangles -> triangles
                            .sType$Default()
                            .vertexFormat(VK10.VK_FORMAT_R32G32B32_SFLOAT)
                            .vertexData(address -> address.deviceAddress(geometry.vertexBuffer().deviceAddress()))
                            .vertexStride(FLOAT_POSITION_STRIDE_BYTES)
                            .maxVertex(geometry.vertexCount() - 1)
                            .indexType(geometry.indexBuffer() == null
                                    ? KHRAccelerationStructure.VK_INDEX_TYPE_NONE_KHR
                                    : VK10.VK_INDEX_TYPE_UINT32)
                            .indexData(address -> address.deviceAddress(geometry.indexBuffer() == null
                                    ? 0L
                                    : geometry.indexBuffer().deviceAddress()))
                            .transformData(address -> address.deviceAddress(0L))))
                    .flags(geometry.opaque()
                            ? KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR
                            : 0);
        }
        return nativeGeometries;
    }

    private static RuntimeException closeCollecting(RuntimeException failure, AutoCloseable resource) {
        if (resource == null) {
            return failure;
        }
        try {
            resource.close();
            return failure;
        } catch (Exception ex) {
            RuntimeException wrapped = ex instanceof RuntimeException runtimeException
                    ? runtimeException
                    : new IllegalStateException("failed to close prepared BLAS build resource", ex);
            if (failure == null) {
                return wrapped;
            }
            failure.addSuppressed(wrapped);
            return failure;
        }
    }

    void record(VkCommandBuffer commandBuffer, MemoryStack stack) {
        recordUpload(commandBuffer);
        RtAccelerationStructureCommandRecorder.recordInputUploadBarrier(commandBuffer, stack);
        recordBuild(commandBuffer);
        RtAccelerationStructureCommandRecorder.recordBuildBarrier(commandBuffer, stack);
    }

    void recordUpload(VkCommandBuffer commandBuffer) {
        requireOpen();
        for (PreparedTriangleGeometry geometry : geometries) {
            geometry.recordUpload(commandBuffer);
        }
    }

    void recordBuild(VkCommandBuffer commandBuffer) {
        requireOpen();
        Objects.requireNonNull(commandBuffer, "commandBuffer");
        /*
         * A batch can contain hundreds of builds. Vulkan consumes these
         * descriptors during command recording, so keeping each descriptor in
         * the caller's outer MemoryStack would create unbounded native-stack growth.
         */
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureGeometryKHR.Buffer geometry = nativeGeometries(stack, geometries);
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer buildInfoBuffer =
                    VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            buildInfoBuffer.get(0)
                    .sType$Default()
                    .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                    .flags(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                    .mode(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .geometryCount(geometry.remaining())
                    .pGeometries(geometry)
                    .dstAccelerationStructure(accelerationStructure.handle())
                    .scratchData(scratch -> scratch.deviceAddress(scratchAddress));

            VkAccelerationStructureBuildRangeInfoKHR.Buffer rangeInfo =
                    VkAccelerationStructureBuildRangeInfoKHR.calloc(geometries.size(), stack);
            PointerBuffer rangeInfoPointers = stack.mallocPointer(geometries.size());
            for (int geometryIndex = 0; geometryIndex < geometries.size(); geometryIndex++) {
                rangeInfo.get(geometryIndex)
                        .primitiveCount(geometries.get(geometryIndex).primitiveCount())
                        .primitiveOffset(0)
                        .firstVertex(0)
                        .transformOffset(0);
                rangeInfoPointers.put(geometryIndex, rangeInfo.get(geometryIndex).address());
            }
            KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR(
                    commandBuffer, buildInfoBuffer, rangeInfoPointers
            );
        }
    }

    SectionTriangleMesh mesh() {
        return Objects.requireNonNull(mesh, "mesh");
    }

    RtAccelerationStructure releaseAccelerationStructure() {
        requireOpen();
        if (accelerationStructureReleased) {
            throw new IllegalStateException("acceleration structure was already released");
        }
        accelerationStructureReleased = true;
        return accelerationStructure;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        failure = closeCollecting(failure, scratchBuffer);
        for (int index = geometries.size() - 1; index >= 0; index--) {
            failure = closeCollecting(failure, geometries.get(index));
        }
        if (!accelerationStructureReleased) {
            failure = closeCollecting(failure, accelerationStructure);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("prepared BLAS build is already closed");
        }
    }
}
