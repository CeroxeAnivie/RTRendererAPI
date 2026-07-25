package top.ceroxe.rt.renderer.rt.device;

/**
 * Immutable, unit-normalized policy used by the Vulkan RT frame scheduler.
 */
record VulkanRtSchedulingConfig(
        long maxPendingFrameAgeBeforeBuildMillis,
        long maxConvergenceVisualStalenessNanos,
        long minStreamingSceneBindIntervalNanos,
        long maxStreamingSceneBindDeferrals,
        long immediateStreamingBindMaxFaces,
        long interactiveMutationRadiusSections,
        long maxInteractiveMutationSections,
        long foregroundFrameBudgetNanos
) {
    private static final long NANOS_PER_MILLISECOND = 1_000_000L;
    private static final long NANOS_PER_MICROSECOND = 1_000L;

    private static final String PREFIX = "top.ceroxe.rt.rt.scheduler.";
    private static final String MAX_PENDING_FRAME_AGE_MILLIS =
            PREFIX + "maxPendingFrameAgeBeforeBuildMillis";
    private static final String MAX_CONVERGENCE_STALENESS_MILLIS =
            PREFIX + "maxConvergenceVisualStalenessMillis";
    private static final String MIN_STREAMING_BIND_INTERVAL_MILLIS =
            PREFIX + "minStreamingSceneBindIntervalMillis";
    private static final String MAX_STREAMING_BIND_DEFERRALS =
            PREFIX + "maxStreamingSceneBindDeferrals";
    private static final String IMMEDIATE_STREAMING_BIND_MAX_FACES =
            PREFIX + "immediateStreamingBindMaxFaces";
    private static final String INTERACTIVE_MUTATION_RADIUS_SECTIONS =
            PREFIX + "interactiveMutationRadiusSections";
    private static final String MAX_INTERACTIVE_MUTATION_SECTIONS =
            PREFIX + "maxInteractiveMutationSections";
    private static final String FOREGROUND_FRAME_BUDGET_MICROS =
            PREFIX + "foregroundFrameBudgetMicros";

    private static final long DEFAULT_MAX_PENDING_FRAME_AGE_MILLIS = 32L;
    private static final long DEFAULT_MAX_CONVERGENCE_STALENESS_MILLIS = 50L;
    private static final long DEFAULT_MIN_STREAMING_BIND_INTERVAL_MILLIS = 16L;
    private static final long DEFAULT_MAX_STREAMING_BIND_DEFERRALS = 4L;
    private static final long DEFAULT_IMMEDIATE_STREAMING_BIND_MAX_FACES = 100_000L;
    private static final long DEFAULT_INTERACTIVE_MUTATION_RADIUS_SECTIONS = 2L;
    private static final long DEFAULT_MAX_INTERACTIVE_MUTATION_SECTIONS = 12L;
    private static final long DEFAULT_FOREGROUND_FRAME_BUDGET_MICROS = 500L;

    VulkanRtSchedulingConfig {
        requirePositive(maxPendingFrameAgeBeforeBuildMillis, "maxPendingFrameAgeBeforeBuildMillis");
        requirePositive(maxConvergenceVisualStalenessNanos, "maxConvergenceVisualStalenessNanos");
        requirePositive(minStreamingSceneBindIntervalNanos, "minStreamingSceneBindIntervalNanos");
        requirePositive(maxStreamingSceneBindDeferrals, "maxStreamingSceneBindDeferrals");
        requirePositive(immediateStreamingBindMaxFaces, "immediateStreamingBindMaxFaces");
        requirePositive(interactiveMutationRadiusSections, "interactiveMutationRadiusSections");
        requirePositive(maxInteractiveMutationSections, "maxInteractiveMutationSections");
        requirePositive(foregroundFrameBudgetNanos, "foregroundFrameBudgetNanos");
        if (interactiveMutationRadiusSections > maxInteractiveMutationSections) {
            throw new IllegalArgumentException(
                    "interactive mutation radius must not exceed the section limit");
        }
    }

    static VulkanRtSchedulingConfig fromSystemProperties() {
        return new VulkanRtSchedulingConfig(
                positiveProperty(MAX_PENDING_FRAME_AGE_MILLIS, DEFAULT_MAX_PENDING_FRAME_AGE_MILLIS),
                multiplyUnit(
                        positiveProperty(
                                MAX_CONVERGENCE_STALENESS_MILLIS,
                                DEFAULT_MAX_CONVERGENCE_STALENESS_MILLIS),
                        NANOS_PER_MILLISECOND,
                        MAX_CONVERGENCE_STALENESS_MILLIS),
                multiplyUnit(
                        positiveProperty(
                                MIN_STREAMING_BIND_INTERVAL_MILLIS,
                                DEFAULT_MIN_STREAMING_BIND_INTERVAL_MILLIS),
                        NANOS_PER_MILLISECOND,
                        MIN_STREAMING_BIND_INTERVAL_MILLIS),
                positiveProperty(MAX_STREAMING_BIND_DEFERRALS, DEFAULT_MAX_STREAMING_BIND_DEFERRALS),
                positiveProperty(IMMEDIATE_STREAMING_BIND_MAX_FACES, DEFAULT_IMMEDIATE_STREAMING_BIND_MAX_FACES),
                positiveProperty(
                        INTERACTIVE_MUTATION_RADIUS_SECTIONS,
                        DEFAULT_INTERACTIVE_MUTATION_RADIUS_SECTIONS),
                positiveProperty(MAX_INTERACTIVE_MUTATION_SECTIONS, DEFAULT_MAX_INTERACTIVE_MUTATION_SECTIONS),
                multiplyUnit(
                        positiveProperty(
                                FOREGROUND_FRAME_BUDGET_MICROS,
                                DEFAULT_FOREGROUND_FRAME_BUDGET_MICROS),
                        NANOS_PER_MICROSECOND,
                        FOREGROUND_FRAME_BUDGET_MICROS)
        );
    }

    private static long positiveProperty(String name, long defaultValue) {
        long value = Long.getLong(name, defaultValue);
        if (value <= 0L) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static long multiplyUnit(long value, long multiplier, String propertyName) {
        try {
            return Math.multiplyExact(value, multiplier);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(propertyName + " exceeds the supported duration", overflow);
        }
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0L) throw new IllegalArgumentException(name + " must be positive");
    }
}
