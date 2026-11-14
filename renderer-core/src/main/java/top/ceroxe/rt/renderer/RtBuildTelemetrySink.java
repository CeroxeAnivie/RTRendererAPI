package top.ceroxe.rt.renderer;

/** Receives bounded aggregate build telemetry; implementations must not retain build products. */
public interface RtBuildTelemetrySink {
    /** Sink used when build telemetry is disabled. */
    RtBuildTelemetrySink NOOP = new RtBuildTelemetrySink() {
    };

    /**
     * Tests whether aggregate events will be consumed.
     * @return {@code true} when telemetry producers should format aggregate details
     */
    default boolean enabled() {
        return false;
    }

    /**
     * Records one bounded aggregate build event.
     * @param subsystem stable renderer subsystem name
     * @param details bounded diagnostic details
     */
    default void aggregate(String subsystem, String details) {
    }
}
