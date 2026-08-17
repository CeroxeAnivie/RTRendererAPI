package top.ceroxe.rt.renderer.api;

/**
 * Immutable monotonically assigned storage-descriptor generation of one rendering resource.
 *
 * <p>The API does not infer generations from object identity or byte equality. A new generation
 * is required only when storage shape or declared usage changes. Ordered writes mutate the
 * contents of the same generation and produce new fence-backed residency evidence; forcing a
 * new allocation for every uniform or vertex update would make high-frequency submission
 * intrinsically unbounded.</p>
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
