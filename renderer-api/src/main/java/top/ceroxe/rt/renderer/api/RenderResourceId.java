package top.ceroxe.rt.renderer.api;

/**
 * Stable caller-assigned identity of a generic rendering resource.
 *
 * <p>An identifier has no implied resource kind or backend handle. Resource kind, generation,
 * and lifetime remain explicit in the containing resource descriptor and transaction.</p>
 *
 * @param value non-negative stable identity
 */
public record RenderResourceId(long value) {
    /**
     * Validates a stable resource identity.
     */
    public RenderResourceId {
        if (value < 0L) {
            throw new IllegalArgumentException("resource id must not be negative");
        }
    }
}
