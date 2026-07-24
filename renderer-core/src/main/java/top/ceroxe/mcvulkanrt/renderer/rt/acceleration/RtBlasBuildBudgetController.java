package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

/**
 * Adaptive CPU/GPU submission budget for section BLAS work.
 *
 * <p>The controller owns only feedback state. Queue ordering, Vulkan recording,
 * and resident BLAS lifetime remain separate concerns, which makes budget changes
 * deterministic and testable without a device.</p>
 */
final class RtBlasBuildBudgetController {
    private static final int WARMUP_BUILD_DIVISOR = 4;
    private static final int MIN_WARMUP_BUILDS = 8;
    private static final int MAX_WARMUP_BUILDS = 32;
    private static final long WARMUP_TRIANGLE_DIVISOR = 4L;
    private static final long MAX_WARMUP_TRIANGLES = 768_000L;

    private final int maxBuilds;
    private final long maxTriangles;
    private final int minBuilds;
    private final long minTriangles;
    private final long targetNanos;
    private final long highNanos;
    private final int rampBuilds;
    private final long rampTriangles;
    private int currentBuilds;
    private long currentTriangles;

    RtBlasBuildBudgetController(
            int maxBuilds,
            long maxTriangles,
            int minBuilds,
            long minTriangles,
            long targetNanos,
            long highNanos
    ) {
        if (maxBuilds <= 0) {
            throw new IllegalArgumentException("maxBuilds must be positive");
        }
        if (maxTriangles <= 0L) {
            throw new IllegalArgumentException("maxTriangles must be positive");
        }
        if (minBuilds <= 0 || minBuilds > maxBuilds) {
            throw new IllegalArgumentException("minBuilds must be in range 1..maxBuilds");
        }
        if (minTriangles <= 0L || minTriangles > maxTriangles) {
            throw new IllegalArgumentException("minTriangles must be in range 1..maxTriangles");
        }
        if (targetNanos <= 0L) {
            throw new IllegalArgumentException("targetNanos must be positive");
        }
        if (highNanos < targetNanos) {
            throw new IllegalArgumentException("highNanos must be greater than or equal to targetNanos");
        }
        this.maxBuilds = maxBuilds;
        this.maxTriangles = maxTriangles;
        this.minBuilds = minBuilds;
        this.minTriangles = minTriangles;
        this.targetNanos = targetNanos;
        this.highNanos = highNanos;
        this.rampBuilds = Math.max(
                minBuilds,
                Math.min(MAX_WARMUP_BUILDS, Math.max(MIN_WARMUP_BUILDS, maxBuilds / WARMUP_BUILD_DIVISOR))
        );
        this.rampTriangles = Math.max(
                minTriangles,
                Math.min(MAX_WARMUP_TRIANGLES, Math.max(1L, maxTriangles / WARMUP_TRIANGLE_DIVISOR))
        );
        this.currentBuilds = rampBuilds;
        this.currentTriangles = rampTriangles;
    }

    Limits currentLimits() {
        return new Limits(currentBuilds, currentTriangles);
    }

    void recordBuild(long elapsedNanos, boolean hasBacklog) {
        if (elapsedNanos < 0L) {
            throw new IllegalArgumentException("elapsedNanos must not be negative");
        }
        if (elapsedNanos > highNanos) {
            currentBuilds = Math.max(minBuilds, Math.max(1, currentBuilds / 2));
            currentTriangles = Math.max(minTriangles, Math.max(1L, currentTriangles / 2L));
        } else if (hasBacklog && elapsedNanos < targetNanos) {
            currentBuilds = Math.min(maxBuilds, currentBuilds + rampBuilds);
            currentTriangles = Math.min(maxTriangles, currentTriangles + rampTriangles);
        }
    }

    void recordIdle(boolean hasBacklog) {
        if (!hasBacklog) {
            currentBuilds = rampBuilds;
            currentTriangles = rampTriangles;
        }
    }

    record Limits(int maxBuilds, long maxTriangles) {
        Limits {
            if (maxBuilds <= 0) {
                throw new IllegalArgumentException("maxBuilds must be positive");
            }
            if (maxTriangles <= 0L) {
                throw new IllegalArgumentException("maxTriangles must be positive");
            }
        }
    }
}
