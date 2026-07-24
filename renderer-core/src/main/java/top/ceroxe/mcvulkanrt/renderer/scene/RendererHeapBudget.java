package top.ceroxe.mcvulkanrt.renderer.scene;

/**
 * Central policy for Java-heap-backed renderer staging budgets.
 *
 * <p>Native BLAS/TLAS storage is deliberately outside this policy. CPU meshes, materials, source
 * payloads and asynchronous inputs compete directly with host chunk generation and lighting.
 * Scaling every owner from the same heap baseline prevents individually reasonable limits from
 * composing into a renderer retention budget larger than the client heap.</p>
 */
public final class RendererHeapBudget {
    private static final long GIBIBYTE = 1024L * 1024L * 1024L;
    private static final long BASELINE_HEAP_BYTES = 4L * GIBIBYTE;

    private RendererHeapBudget() {
    }

    public static long defaultBytes(long baselineBytes, long minimumBytes) {
        return scaledBytes(Runtime.getRuntime().maxMemory(), baselineBytes, minimumBytes);
    }

    public static int defaultCount(int baselineCount, int minimumCount) {
        return scaledCount(Runtime.getRuntime().maxMemory(), baselineCount, minimumCount);
    }

    static long scaledBytes(long maxHeapBytes, long baselineBytes, long minimumBytes) {
        requirePositive(maxHeapBytes, "maxHeapBytes");
        requirePositive(baselineBytes, "baselineBytes");
        requirePositive(minimumBytes, "minimumBytes");
        if (minimumBytes > baselineBytes) {
            throw new IllegalArgumentException("minimumBytes must not exceed baselineBytes");
        }
        if (maxHeapBytes >= BASELINE_HEAP_BYTES) {
            return baselineBytes;
        }
        long scaled = (long) Math.floor(
                baselineBytes * ((double) maxHeapBytes / BASELINE_HEAP_BYTES)
        );
        return Math.max(minimumBytes, scaled);
    }

    static int scaledCount(long maxHeapBytes, int baselineCount, int minimumCount) {
        requirePositive(maxHeapBytes, "maxHeapBytes");
        requirePositive(baselineCount, "baselineCount");
        requirePositive(minimumCount, "minimumCount");
        if (minimumCount > baselineCount) {
            throw new IllegalArgumentException("minimumCount must not exceed baselineCount");
        }
        if (maxHeapBytes >= BASELINE_HEAP_BYTES) {
            return baselineCount;
        }
        int scaled = (int) Math.floor(
                baselineCount * ((double) maxHeapBytes / BASELINE_HEAP_BYTES)
        );
        return Math.max(minimumCount, scaled);
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0L) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
