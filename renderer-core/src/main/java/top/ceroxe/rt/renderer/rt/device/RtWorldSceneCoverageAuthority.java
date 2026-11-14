package top.ceroxe.rt.renderer.rt.device;

/**
 * Monotonic authority state for world-coverage contraction and committed-front invalidation.
 *
 * <p>A successor TLAS may cover fewer sections only when an accepted host transaction carries
 * explicit removal provenance newer than the bound front. Keeping that provenance together with
 * the committed-front revision prevents two independently updated counters from authorizing a
 * stale contraction.</p>
 */
final class RtWorldSceneCoverageAuthority {
    private long acceptedContractionGeneration;
    private long boundContractionGeneration;
    private long committedFrontInvalidationRevision;
    private long boundCommittedFrontRevision;

    long authorizeContraction() {
        acceptedContractionGeneration = Math.incrementExact(acceptedContractionGeneration);
        return acceptedContractionGeneration;
    }

    long acceptedContractionGeneration() {
        return acceptedContractionGeneration;
    }

    void recordCommittedFrontInvalidation(long sectionRevision) {
        committedFrontInvalidationRevision = Math.max(
                committedFrontInvalidationRevision,
                sectionRevision
        );
    }

    boolean committedFrontGenerationIsCurrent() {
        return RtCommittedFrontPolicy.generationIsCurrent(
                committedFrontInvalidationRevision,
                boundCommittedFrontRevision
        );
    }

    boolean contractionAuthorizedFor(long candidateGeneration) {
        if (candidateGeneration < 0L) {
            throw new IllegalArgumentException("coverage contraction generation must not be negative");
        }
        return candidateGeneration > boundContractionGeneration;
    }

    void recordBoundGeneration(long contractionGeneration, long sectionRevision) {
        if (contractionGeneration < 0L) {
            throw new IllegalArgumentException("bound coverage contraction generation must not be negative");
        }
        boundContractionGeneration = Math.max(boundContractionGeneration, contractionGeneration);
        boundCommittedFrontRevision = Math.max(boundCommittedFrontRevision, sectionRevision);
    }
}
