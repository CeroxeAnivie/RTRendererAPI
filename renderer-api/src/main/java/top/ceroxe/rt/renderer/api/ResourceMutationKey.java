package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Exact content snapshot of one storage generation recorded by one command transaction.
 *
 * <p>{@link ResourceVersion} identifies allocation shape and declared usage. This type identifies
 * the later ordered mutation of that allocation, without using a host buffer wrapper or object
 * identity as a lifetime key. A transaction records at most one final snapshot per generation, so
 * the transaction sequence is a stable bounded in-flight mutation token.</p>
 *
 * @param generation exact storage generation
 * @param commandSequence non-negative transaction sequence that recorded the content mutation
 */
public record ResourceMutationKey(ResourceGenerationKey generation, long commandSequence) {
    /** Rejects absent storage identity and negative command ordering. */
    public ResourceMutationKey {
        generation = Objects.requireNonNull(generation, "generation");
        if (commandSequence < 0L) {
            throw new IllegalArgumentException("resource mutation command sequence must not be negative");
        }
    }
}
