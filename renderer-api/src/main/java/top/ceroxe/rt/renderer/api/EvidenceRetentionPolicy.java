package top.ceroxe.rt.renderer.api;

/**
 * Renderer-lifetime limits for generic execution evidence and resource identity tracking.
 *
 * <p>Command capacity includes pending work, unobserved terminal results and completed history.
 * A terminal result becomes evictable after a caller queries it, unless an evidence lease pins it.
 * Resident resource evidence never competes with retired history. Identity entries preserve the
 * highest generation and resource kind for the entire renderer lifetime: exhausting that budget
 * rejects new identities, while later generations of existing identities remain admissible.</p>
 */
public record EvidenceRetentionPolicy(
        int commandCapacity,
        int retiredResourceCapacity,
        int resourceIdentityCapacity,
        int residentGenerationCapacity
) {
    public EvidenceRetentionPolicy {
        if (commandCapacity <= 0 || retiredResourceCapacity <= 0
                || resourceIdentityCapacity <= 0 || residentGenerationCapacity <= 0) {
            throw new IllegalArgumentException("evidence retention capacities must be positive");
        }
    }

    /** Returns fixed budgets independent of cumulative frames or elapsed time. */
    public static EvidenceRetentionPolicy bounded() {
        return new EvidenceRetentionPolicy(256, 256, 65_536, 16_384);
    }
}
