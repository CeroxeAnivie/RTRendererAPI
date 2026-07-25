package top.ceroxe.rt.renderer.rt.acceleration;

import org.lwjgl.vulkan.VkCommandBuffer;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.rt.renderer.scene.SectionTriangleMesh;

import java.util.Objects;

/**
 * Owns one prepared BLAS geometry's upload payload and device input buffers.
 */
final class PreparedTriangleGeometry implements AutoCloseable {
    private final RtGpuBuffer vertexBuffer;
    private final RtGpuBuffer indexBuffer;
    private final float[] vertexPositions;
    private final int[] indices;
    private final SectionTriangleMesh sectionMesh;
    private final boolean filterByAlphaCutout;
    private final boolean alphaCutout;
    private final int vertexCount;
    private final int primitiveCount;
    private final boolean opaque;
    private boolean closed;

    PreparedTriangleGeometry(
            RtGpuBuffer vertexBuffer,
            RtGpuBuffer indexBuffer,
            float[] vertexPositions,
            int[] indices,
            int vertexCount,
            int primitiveCount,
            boolean opaque
    ) {
        this.vertexBuffer = Objects.requireNonNull(vertexBuffer, "vertexBuffer");
        this.indexBuffer = indexBuffer;
        this.vertexPositions = Objects.requireNonNull(vertexPositions, "vertexPositions");
        this.indices = indices;
        this.sectionMesh = null;
        this.filterByAlphaCutout = false;
        this.alphaCutout = false;
        if (vertexCount <= 0) {
            throw new IllegalArgumentException("vertexCount must be positive");
        }
        if (primitiveCount <= 0) {
            throw new IllegalArgumentException("primitiveCount must be positive");
        }
        this.vertexCount = vertexCount;
        this.primitiveCount = primitiveCount;
        this.opaque = opaque;
    }

    PreparedTriangleGeometry(
            RtGpuBuffer vertexBuffer,
            RtGpuBuffer indexBuffer,
            SectionTriangleMesh sectionMesh,
            boolean filterByAlphaCutout,
            boolean alphaCutout,
            int vertexCount,
            int primitiveCount,
            boolean opaque
    ) {
        this.vertexBuffer = Objects.requireNonNull(vertexBuffer, "vertexBuffer");
        this.indexBuffer = Objects.requireNonNull(indexBuffer, "indexBuffer");
        this.vertexPositions = null;
        this.indices = null;
        this.sectionMesh = Objects.requireNonNull(sectionMesh, "sectionMesh");
        this.filterByAlphaCutout = filterByAlphaCutout;
        this.alphaCutout = alphaCutout;
        if (vertexCount <= 0 || primitiveCount <= 0) {
            throw new IllegalArgumentException("section geometry counts must be positive");
        }
        this.vertexCount = vertexCount;
        this.primitiveCount = primitiveCount;
        this.opaque = opaque;
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
                    : new IllegalStateException("failed to close prepared BLAS geometry resource", ex);
            if (failure == null) {
                return wrapped;
            }
            failure.addSuppressed(wrapped);
            return failure;
        }
    }

    RtGpuBuffer vertexBuffer() {
        return vertexBuffer;
    }

    RtGpuBuffer indexBuffer() {
        return indexBuffer;
    }

    int vertexCount() {
        return vertexCount;
    }

    int primitiveCount() {
        return primitiveCount;
    }

    boolean opaque() {
        return opaque;
    }

    void recordUpload(VkCommandBuffer commandBuffer) {
        requireOpen();
        if (sectionMesh != null) {
            RtAccelerationStructureCommandRecorder.recordSectionMeshUpload(
                    commandBuffer,
                    vertexBuffer.buffer(),
                    indexBuffer.buffer(),
                    sectionMesh,
                    filterByAlphaCutout,
                    alphaCutout,
                    vertexCount / 4
            );
            return;
        }
        RtAccelerationStructureCommandRecorder.recordFloatUpload(
                commandBuffer, vertexBuffer.buffer(), vertexPositions
        );
        if (indexBuffer != null) {
            RtAccelerationStructureCommandRecorder.recordIntUpload(
                    commandBuffer, indexBuffer.buffer(), indices
            );
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        failure = closeCollecting(failure, indexBuffer);
        failure = closeCollecting(failure, vertexBuffer);
        if (failure != null) {
            throw failure;
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("prepared BLAS geometry is already closed");
        }
    }
}
