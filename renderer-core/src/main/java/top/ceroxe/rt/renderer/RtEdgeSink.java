package top.ceroxe.rt.renderer;

/**
 * Receives low-frequency RT lifecycle edges without owning renderer state.
 */
public interface RtEdgeSink {
    /**
     * Disabled sink used when lifecycle-edge telemetry is not requested.
     */
    RtEdgeSink NOOP = new RtEdgeSink() {
    };

    /**
     * Reports whether lifecycle-edge collection is enabled.
     *
     * @return whether lifecycle-edge collection is enabled
     */
    default boolean enabled() {
        return false;
    }

    /**
     * Reports whether high-volume I/O edges may be emitted.
     *
     * @return whether high-volume I/O edges may be emitted
     */
    default boolean verboseIoEnabled() {
        return false;
    }

    /**
     * Reads elapsed diagnostic time.
     *
     * @return milliseconds elapsed since this sink's diagnostic epoch
     */
    default long elapsedMillis() {
        return 0L;
    }

    /**
     * Records one lifecycle edge.
     *
     * @param edge    stable edge name
     * @param details bounded diagnostic details
     */
    default void edge(String edge, String details) {
    }

    /**
     * Records the first lifecycle edge associated with a deduplication key.
     *
     * @param key     stable deduplication key
     * @param edge    stable edge name
     * @param details bounded diagnostic details
     */
    default void edgeOnce(String key, String edge, String details) {
    }
}
