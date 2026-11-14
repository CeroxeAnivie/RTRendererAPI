package top.ceroxe.rt.renderer;

/**
 * Receives command-host stalls after the RT owner has classified the wait.
 */
public interface RtStallTelemetrySink {
    /**
     * Disabled sink used when stall telemetry is not requested.
     */
    RtStallTelemetrySink NOOP = new RtStallTelemetrySink() {
    };

    /**
     * Records a classified command-host synchronization stall.
     *
     * @param thread      thread that observed the stall
     * @param commandPool native command-pool handle
     * @param stage       synchronization stage label
     * @param waitNanos   host wait duration in nanoseconds
     * @param workNanos   associated command-host work duration in nanoseconds
     */
    default void commandHostStall(
            String thread,
            long commandPool,
            String stage,
            long waitNanos,
            long workNanos
    ) {
    }

    /**
     * Records a host-visible GPU-memory synchronization stall.
     *
     * @param stage        synchronization stage label
     * @param bytes        synchronized byte count
     * @param elapsedNanos synchronization duration in nanoseconds
     */
    default void gpuMemoryHostStall(String stage, long bytes, long elapsedNanos) {
    }
}
