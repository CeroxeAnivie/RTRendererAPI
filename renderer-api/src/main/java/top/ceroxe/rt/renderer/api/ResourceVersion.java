package top.ceroxe.rt.renderer.api;

/**
 * Immutable monotonically assigned content version of one rendering resource.
 *
 * <p>The API does not infer versions from object identity or byte equality. A caller publishes a
 * new version whenever content or descriptor semantics change, allowing transactions to reject
 * stale bindings before work is recorded.</p>
 *
 * @param value non-negative resource version
 */
public record ResourceVersion(long value) {
    /**
     * Validates a resource generation.
     */
    public ResourceVersion {
        if (value < 0L) {
            throw new IllegalArgumentException("resource version must not be negative");
        }
    }

    /**
     * Returns the initial published resource version.
     *
     * @return version zero
     */
    public static ResourceVersion initial() {
        return new ResourceVersion(0L);
    }
}
