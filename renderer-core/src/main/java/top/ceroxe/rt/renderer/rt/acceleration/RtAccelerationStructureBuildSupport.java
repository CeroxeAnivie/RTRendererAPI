package top.ceroxe.rt.renderer.rt.acceleration;

import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;

import java.util.Objects;

/**
 * Overflow-safe size arithmetic and input validation shared by BLAS and TLAS builders.
 */
final class RtAccelerationStructureBuildSupport {
    private static final int TRIANGLE_VERTEX_COUNT = 3;
    private static final int TRIANGLE_INDEX_COUNT = 3;

    private RtAccelerationStructureBuildSupport() {
    }

    static long alignUp(long value, int alignment) {
        if (value < 0L) {
            throw new IllegalArgumentException("aligned value must not be negative");
        }
        if (alignment <= 0) {
            throw new IllegalArgumentException("alignment must be positive");
        }
        long remainder = value % alignment;
        if (remainder == 0L) {
            return value;
        }
        return checkedAdd(value, alignment - remainder);
    }

    static long checkedAdd(long left, long right) {
        requireNonNegativeOperands(left, right);
        long result = left + right;
        if (((left ^ result) & (right ^ result)) < 0L) {
            throw new IllegalArgumentException("size overflow");
        }
        return result;
    }

    static long checkedMultiply(long left, long right) {
        requireNonNegativeOperands(left, right);
        long result = left * right;
        if (left != 0L && result / left != right) {
            throw new IllegalArgumentException("size overflow");
        }
        return result;
    }

    static int checkedByteBufferSize(long sizeBytes, String label) {
        String bufferLabel = Objects.requireNonNull(label, "label");
        if (sizeBytes <= 0L || sizeBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    bufferLabel + " byte size outside Java direct buffer range: " + sizeBytes
            );
        }
        return (int) sizeBytes;
    }

    static int validateAndCountTriangles(int[] indices, int vertexCount, String label) {
        String geometryLabel = Objects.requireNonNull(label, "label");
        if (vertexCount <= 0) {
            throw new IllegalArgumentException(geometryLabel + " vertex count must be positive");
        }
        if (indices == null) {
            if (vertexCount != TRIANGLE_VERTEX_COUNT) {
                throw new IllegalArgumentException(
                        geometryLabel + " non-indexed geometry must contain exactly one triangle"
                );
            }
            return 1;
        }
        if (indices.length == 0 || indices.length % TRIANGLE_INDEX_COUNT != 0) {
            throw new IllegalArgumentException(geometryLabel + " indices must contain triangle triples");
        }
        for (int index : indices) {
            if (index < 0 || index >= vertexCount) {
                throw new IllegalArgumentException(
                        geometryLabel + " index outside vertex buffer: " + index
                );
            }
        }
        return indices.length / TRIANGLE_INDEX_COUNT;
    }

    static void validateBuildSizes(VkAccelerationStructureBuildSizesInfoKHR buildSizes) {
        Objects.requireNonNull(buildSizes, "buildSizes");
        if (buildSizes.accelerationStructureSize() <= 0L) {
            throw new IllegalStateException("acceleration structure build size must be positive");
        }
        if (buildSizes.buildScratchSize() <= 0L) {
            throw new IllegalStateException("acceleration structure scratch size must be positive");
        }
    }

    private static void requireNonNegativeOperands(long left, long right) {
        if (left < 0L || right < 0L) {
            throw new IllegalArgumentException("size operands must not be negative");
        }
    }
}
