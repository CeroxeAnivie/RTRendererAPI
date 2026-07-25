package top.ceroxe.rt.renderer.rt.device;

/**
 * Owns mutable time-window and sampling state for device-level diagnostics.
 *
 * <p>These counters deliberately live outside {@link VulkanRtDeviceContext}: log sampling and
 * diagnostic throttling must not become part of the Vulkan resource graph or scene transaction
 * state. Every decision accepts its clock value explicitly so boundary behavior remains
 * deterministic under tests.</p>
 */
final class VulkanRtDeviceTelemetry {
    private static final long DEFAULT_WORLD_TLAS_STATUS_LOG_INTERVAL = 10_000L;
    private static final long INITIAL_WORLD_TLAS_BIND_LOGS = 4L;
    private static final long INITIAL_WORLD_TLAS_DISPATCH_LOGS = 4L;
    private static final long SLOW_PRE_BUILD_DIAGNOSTIC_NANOS = 2_000_000L;
    private static final long SLOW_PRE_BUILD_DIAGNOSTIC_INTERVAL_NANOS = 1_000_000_000L;
    private static final String WORLD_TLAS_STATUS_LOG_INTERVAL_PROPERTY =
            "top.ceroxe.rt.telemetry.worldTlasStatusLogInterval";

    private final long worldTlasStatusLogInterval;
    private long nextStreamingSceneBindNanos;
    private long loggedWorldTlasBinds;
    private long lastLoggedWorldTlasDispatches;
    private long lastSlowPreBuildDiagnosticNanos;
    private long lastSlowPreBuildPolicyDiagnosticNanos;

    VulkanRtDeviceTelemetry() {
        this(positiveLongProperty(
                WORLD_TLAS_STATUS_LOG_INTERVAL_PROPERTY,
                DEFAULT_WORLD_TLAS_STATUS_LOG_INTERVAL
        ));
    }

    VulkanRtDeviceTelemetry(long worldTlasStatusLogInterval) {
        if (worldTlasStatusLogInterval <= 0L) {
            throw new IllegalArgumentException("world TLAS status log interval must be positive");
        }
        this.worldTlasStatusLogInterval = worldTlasStatusLogInterval;
    }

    private static long positiveLongProperty(String name, long defaultValue) {
        String raw = System.getProperty(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed > 0L ? parsed : defaultValue;
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    long nextStreamingSceneBindNanos() {
        return nextStreamingSceneBindNanos;
    }

    void rememberStreamingSceneBind(boolean streamingUpdate, long minimumIntervalNanos, long nowNanos) {
        if (minimumIntervalNanos < 0L) {
            throw new IllegalArgumentException("streaming scene bind interval must not be negative");
        }
        if (streamingUpdate && minimumIntervalNanos > 0L) {
            nextStreamingSceneBindNanos = nowNanos + minimumIntervalNanos;
        }
    }

    boolean shouldLogSlowPreBuild(long durationNanos, long nowNanos) {
        if (durationNanos < SLOW_PRE_BUILD_DIAGNOSTIC_NANOS
                || nowNanos - lastSlowPreBuildDiagnosticNanos
                < SLOW_PRE_BUILD_DIAGNOSTIC_INTERVAL_NANOS) {
            return false;
        }
        lastSlowPreBuildDiagnosticNanos = nowNanos;
        return true;
    }

    boolean shouldLogSlowPreBuildPolicy(long durationNanos, long nowNanos) {
        if (durationNanos < SLOW_PRE_BUILD_DIAGNOSTIC_NANOS
                || nowNanos - lastSlowPreBuildPolicyDiagnosticNanos
                < SLOW_PRE_BUILD_DIAGNOSTIC_INTERVAL_NANOS) {
            return false;
        }
        lastSlowPreBuildPolicyDiagnosticNanos = nowNanos;
        return true;
    }

    boolean shouldLogWorldTlasBound() {
        loggedWorldTlasBinds++;
        return loggedWorldTlasBinds <= INITIAL_WORLD_TLAS_BIND_LOGS
                || loggedWorldTlasBinds % worldTlasStatusLogInterval == 0L;
    }

    boolean shouldLogWorldTlasDispatch(
            long currentDispatches,
            long previousDispatches,
            boolean worldTlasReady,
            boolean frameDispatchUseful
    ) {
        if (currentDispatches <= previousDispatches
                || currentDispatches == lastLoggedWorldTlasDispatches
                || !worldTlasReady
                || !frameDispatchUseful) {
            return false;
        }
        lastLoggedWorldTlasDispatches = currentDispatches;
        return currentDispatches <= INITIAL_WORLD_TLAS_DISPATCH_LOGS
                || currentDispatches % worldTlasStatusLogInterval == 0L;
    }
}
