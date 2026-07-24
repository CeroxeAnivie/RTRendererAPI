package top.ceroxe.mcvulkanrt.renderer;

/** Receives low-frequency RT lifecycle edges without owning renderer state. */
public interface RtEdgeSink {
    RtEdgeSink NOOP = new RtEdgeSink() {
    };

    default boolean enabled() {
        return false;
    }

    default boolean verboseIoEnabled() {
        return false;
    }

    default long elapsedMillis() {
        return 0L;
    }

    default void edge(String edge, String details) {
    }

    default void edgeOnce(String key, String edge, String details) {
    }
}
