package top.ceroxe.rt.renderer.api;

/**
 * Pins one command's evidence for delayed or multiple readers, independently of GPU resources.
 * Close is idempotent and remains safe after renderer close. A lease does not keep a closed
 * renderer queryable and must not be transferred to a replacement renderer.
 */
public interface EvidenceLease extends AutoCloseable {
    @Override
    void close();
}
