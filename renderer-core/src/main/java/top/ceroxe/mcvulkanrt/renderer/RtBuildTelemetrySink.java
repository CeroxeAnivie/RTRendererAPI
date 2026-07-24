package top.ceroxe.mcvulkanrt.renderer;

/** Receives bounded aggregate build telemetry; implementations must not retain build products. */
public interface RtBuildTelemetrySink {
    RtBuildTelemetrySink NOOP = new RtBuildTelemetrySink() {
    };

    default boolean enabled() {
        return false;
    }

    default void aggregate(String subsystem, String details) {
    }
}
