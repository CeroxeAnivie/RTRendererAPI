package top.ceroxe.rt.renderer.api;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable atomic outcome of one {@link RenderResourceTransaction} admission.
 *
 * <p>The per-generation entries retain their own lifecycle milestones. This envelope only states
 * whether the transaction was admitted as one whole unit; it never upgrades an accepted resource
 * to GPU-ready before its submission fence has completed.</p>
 */
public final class ResourceTransactionEvidence {
    /** Transaction-level admission result. */
    public enum Outcome {
        REJECTED,
        ACCEPTED
    }

    private final long transactionRevision;
    private final Outcome outcome;
    private final List<ResourceResidencyEvidence> resources;
    private final String detail;

    /** Creates one fully validated transaction evidence snapshot. */
    public ResourceTransactionEvidence(
            long transactionRevision,
            Outcome outcome,
            List<? extends ResourceResidencyEvidence> resources,
            String detail
    ) {
        if (transactionRevision < 0L) {
            throw new IllegalArgumentException("transactionRevision must not be negative");
        }
        this.transactionRevision = transactionRevision;
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(resources, "resources");
        ArrayList<ResourceResidencyEvidence> copied = new ArrayList<>(resources.size());
        Set<ResourceGenerationKey> generations = new HashSet<>();
        for (ResourceResidencyEvidence resource : resources) {
            ResourceResidencyEvidence checked = Objects.requireNonNull(resource, "resources element");
            if (!generations.add(checked.generation())) {
                throw new IllegalArgumentException("resource transaction evidence contains a duplicate generation: "
                        + checked.generation());
            }
            if (outcome == Outcome.ACCEPTED
                    && checked.outcome() == ResourceResidencyEvidence.Outcome.REJECTED) {
                throw new IllegalArgumentException(
                        "accepted resource transaction cannot contain rejected generation evidence");
            }
            if (outcome == Outcome.REJECTED
                    && checked.outcome() != ResourceResidencyEvidence.Outcome.REJECTED) {
                throw new IllegalArgumentException(
                        "rejected resource transaction must contain only rejected generation evidence");
            }
            copied.add(checked);
        }
        this.resources = List.copyOf(copied);
        this.detail = Objects.requireNonNull(detail, "detail");
        if (detail.isBlank()) {
            throw new IllegalArgumentException("detail must not be blank");
        }
        if (outcome == Outcome.ACCEPTED && resources.isEmpty()) {
            throw new IllegalArgumentException("accepted resource transaction must report every affected generation");
        }
    }

    /** @return immutable caller-owned resource transaction revision */
    public long transactionRevision() { return transactionRevision; }

    /** @return atomic transaction admission outcome */
    public Outcome outcome() { return outcome; }

    /** @return immutable exact lifecycle evidence for each affected generation */
    public List<ResourceResidencyEvidence> resources() { return resources; }

    /** @return non-blank diagnostic context */
    public String detail() { return detail; }
}
