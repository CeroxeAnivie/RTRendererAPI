package top.ceroxe.mcvulkanrt.renderer;

/** Receives command-host stalls after the RT owner has classified the wait. */
public interface RtStallTelemetrySink {
    RtStallTelemetrySink NOOP = new RtStallTelemetrySink() {
    };

    default void commandHostStall(
            String thread,
            long commandPool,
            String stage,
            long waitNanos,
            long workNanos
    ) {
    }

    default void gpuMemoryHostStall(String stage, long bytes, long elapsedNanos) {
    }
}
