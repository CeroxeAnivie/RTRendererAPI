package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Procedural primitives bounded by six float32 values per AABB: minX, minY, minZ,
 * maxX, maxY, maxZ. Applications must supply finite bounds with minimum no greater
 * than maximum and keep input contents valid through build completion.
 */
public record AccelerationStructureAabbGeometry(
        ResourceSlice.BufferSlice bounds, long strideBytes, int primitiveCount, boolean opaque
) {
    /** Validates usage, eight-byte alignment, count, and the complete addressed range. */
    public AccelerationStructureAabbGeometry {
        bounds = Objects.requireNonNull(bounds, "bounds");
        if (!bounds.resource().usage().contains(BufferUsage.ACCELERATION_STRUCTURE_BUILD_INPUT)) {
            throw new IllegalArgumentException("AABB buffer requires ACCELERATION_STRUCTURE_BUILD_INPUT usage");
        }
        if (strideBytes < 24 || strideBytes > 0xffff_ffffL || (strideBytes & 7) != 0
                || (bounds.range().offsetBytes() & 7) != 0 || primitiveCount <= 0) {
            throw new IllegalArgumentException("AABB input requires aligned offset/stride and positive primitive count");
        }
        long required;
        try {
            required = Math.addExact(Math.multiplyExact(strideBytes, primitiveCount - 1L), 24L);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("AABB input range overflows", overflow);
        }
        if (required > bounds.range().lengthBytes()) {
            throw new IllegalArgumentException("AABB input exceeds its buffer slice");
        }
    }
}
