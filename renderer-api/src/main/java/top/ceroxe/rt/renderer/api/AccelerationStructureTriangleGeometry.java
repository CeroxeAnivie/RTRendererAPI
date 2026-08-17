package top.ceroxe.rt.renderer.api;

import java.util.Objects;
import java.util.Optional;

/** Exact triangle input ranges for a bottom-level acceleration-structure build. */
public final class AccelerationStructureTriangleGeometry {
    private final ResourceSlice.BufferSlice vertices;
    private final int vertexStrideBytes;
    private final int vertexCount;
    private final ResourceSlice.BufferSlice indices;
    private final AccelerationStructureIndexFormat indexFormat;
    private final int indexCount;

    /**
     * Creates a triangle geometry with optional indexed topology.
     *
     * <p>Only tightly specified float3 positions are portable at this layer. Applications with
     * richer vertex layouts retain those bindings in their raster commands while publishing this
     * explicit position stream for AS construction.</p>
     */
    public AccelerationStructureTriangleGeometry(
            ResourceSlice.BufferSlice vertices,
            int vertexStrideBytes,
            int vertexCount,
            ResourceSlice.BufferSlice indices,
            AccelerationStructureIndexFormat indexFormat,
            int indexCount
    ) {
        this.vertices = Objects.requireNonNull(vertices, "vertices");
        if (!vertices.resource().usage().contains(BufferUsage.ACCELERATION_STRUCTURE_BUILD_INPUT)) {
            throw new IllegalArgumentException("AS vertex buffer requires ACCELERATION_STRUCTURE_BUILD_INPUT usage");
        }
        if (vertexStrideBytes < 12 || (vertexStrideBytes & 3) != 0 || vertexCount < 3) {
            throw new IllegalArgumentException("AS geometry requires at least three float3 vertices with four-byte-aligned stride");
        }
        long vertexBytes;
        try {
            vertexBytes = Math.multiplyExact((long) vertexStrideBytes, vertexCount);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("AS vertex range overflows long", overflow);
        }
        if (vertexBytes > vertices.range().lengthBytes()) {
            throw new IllegalArgumentException("AS vertex range exceeds its buffer slice");
        }
        this.vertexStrideBytes = vertexStrideBytes;
        this.vertexCount = vertexCount;
        if ((indices == null) != (indexFormat == null)) {
            throw new IllegalArgumentException("AS index buffer and index format must be present together");
        }
        if (indices == null) {
            if (indexCount != 0) throw new IllegalArgumentException("non-indexed AS geometry must use indexCount=0");
            if (vertexCount % 3 != 0) throw new IllegalArgumentException("non-indexed AS geometry vertex count must be divisible by three");
            this.indices = null;
            this.indexFormat = null;
            this.indexCount = 0;
            return;
        }
        if (!indices.resource().usage().contains(BufferUsage.ACCELERATION_STRUCTURE_BUILD_INPUT)
                || indexCount < 3 || indexCount % 3 != 0) {
            throw new IllegalArgumentException("indexed AS geometry requires an AS input index buffer and triangle-aligned index count");
        }
        long indexBytes;
        try {
            indexBytes = Math.multiplyExact((long) indexCount, indexFormat.byteSize());
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("AS index range overflows long", overflow);
        }
        if (indexBytes > indices.range().lengthBytes()) throw new IllegalArgumentException("AS index range exceeds its buffer slice");
        this.indices = indices;
        this.indexFormat = indexFormat;
        this.indexCount = indexCount;
    }

    /** @return exact position-buffer slice */
    public ResourceSlice.BufferSlice vertices() { return vertices; }
    /** @return four-byte-aligned byte stride with float3 position at offset zero */
    public int vertexStrideBytes() { return vertexStrideBytes; }
    /** @return number of addressable vertices */
    public int vertexCount() { return vertexCount; }
    /** @return indexed topology source when supplied */
    public Optional<ResourceSlice.BufferSlice> indices() { return Optional.ofNullable(indices); }
    /** @return index encoding when indexed topology is supplied */
    public Optional<AccelerationStructureIndexFormat> indexFormat() { return Optional.ofNullable(indexFormat); }
    /** @return zero for non-indexed geometry, otherwise a positive multiple of three */
    public int indexCount() { return indexCount; }
}
