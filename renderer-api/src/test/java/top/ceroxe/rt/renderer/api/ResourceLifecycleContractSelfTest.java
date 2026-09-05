package top.ceroxe.rt.renderer.api;

import java.util.List;
import java.util.OptionalLong;
import java.util.Set;

/** Executable contract checks for explicit generic-resource publication and retirement. */
public final class ResourceLifecycleContractSelfTest {
    private ResourceLifecycleContractSelfTest() { }

    /** Runs every resource lifecycle assertion. */
    public static void main(String[] args) {
        retentionContract();
        BufferResource buffer = new BufferResource(
                new RenderResourceId(1L), ResourceVersion.initial(), 64L, Set.of(BufferUsage.VERTEX)
        );
        RenderResourceTransaction transaction = RenderResourceTransaction.builder(1L)
                .upsert(buffer)
                .build();
        require(transaction.upsertGenerationKeys().equals(List.of(ResourceGenerationKey.of(buffer))),
                "resource transaction lost its exact generation");
        expect(IllegalArgumentException.class, () -> RenderResourceTransaction.builder(1L)
                .upsert(buffer).upsert(buffer).build());
        expect(IllegalArgumentException.class, () -> RenderResourceTransaction.builder(1L)
                .upsert(buffer).retire(ResourceGenerationKey.of(buffer)).build());
        expect(IllegalArgumentException.class, () -> RenderResourceTransaction.builder(1L)
                .retire(ResourceGenerationKey.of(buffer)).retire(ResourceGenerationKey.of(buffer)).build());
        BufferResource replacement = new BufferResource(
                buffer.id(), new ResourceVersion(1L), 128L, Set.of(BufferUsage.VERTEX)
        );
        RenderResourceTransaction replacementTransaction = RenderResourceTransaction.builder(2L)
                .upsert(replacement).retire(ResourceGenerationKey.of(buffer)).build();
        require(replacementTransaction.retiredGenerations().equals(Set.of(ResourceGenerationKey.of(buffer))),
                "exact old-generation retirement was not retained beside its replacement");
        expect(UnsupportedOperationException.class, () -> transaction.buffers().clear());
        RenderResourceTransaction.requireStrictlyAfter(1L, 2L);
        expect(IllegalArgumentException.class, () -> RenderResourceTransaction.requireStrictlyAfter(2L, 2L));

        ResourceGenerationKey key = ResourceGenerationKey.of(buffer);
        ResourceResidencyEvidence accepted = ResourceResidencyEvidence.accepted(key, 1L, "accepted");
        ResourceResidencyEvidence recorded = evidence(
                key, ResourceResidencyEvidence.Outcome.UPLOAD_RECORDED, OptionalLong.of(7L), OptionalLong.empty()
        );
        ResourceResidencyEvidence ready = evidence(
                key, ResourceResidencyEvidence.Outcome.GPU_READY, OptionalLong.of(7L), OptionalLong.empty()
        );
        require(ready.mutationKey().orElseThrow().equals(new ResourceMutationKey(key, 7L)),
                "GPU-ready residency did not retain its exact content mutation token");
        require(accepted.mutationKey().isEmpty(), "unrecorded storage fabricated a content mutation token");
        ResourceResidencyEvidence pending = evidence(
                key, ResourceResidencyEvidence.Outcome.RETIRE_PENDING, OptionalLong.of(7L), OptionalLong.of(9L)
        );
        ResourceResidencyEvidence retired = evidence(
                key, ResourceResidencyEvidence.Outcome.RETIRED, OptionalLong.of(7L), OptionalLong.of(9L)
        );
        accepted.requireNext(recorded);
        ResourceResidencyEvidence unusedRetirement = new ResourceResidencyEvidence(
                key, ResourceResidencyEvidence.Outcome.RETIRED_UNUSED, 1L,
                OptionalLong.empty(), OptionalLong.empty(), "unused allocation released"
        );
        accepted.requireNext(unusedRetirement);
        require(unusedRetirement.outcome().retired() && !unusedRetirement.outcome().gpuReady(),
                "unused retirement fabricated GPU readiness or lost terminal retirement");
        recorded.requireNext(ready);
        ready.requireNext(pending);
        pending.requireNext(retired);
        require(!pending.outcome().retired() && retired.outcome().retired(),
                "pending retirement was confused with completed retirement");
        expect(IllegalArgumentException.class, () -> accepted.requireNext(ready));

        ResourceTransactionEvidence publication = new ResourceTransactionEvidence(
                1L, ResourceTransactionEvidence.Outcome.ACCEPTED, List.of(accepted), "publication accepted"
        );
        require(publication.resources().equals(List.of(accepted)), "resource transaction lost publication evidence");
        ResourceTransactionEvidence laterRetirement = new ResourceTransactionEvidence(
                2L, ResourceTransactionEvidence.Outcome.ACCEPTED, List.of(retired), "retirement accepted"
        );
        require(laterRetirement.resources().getFirst().transactionRevision() == 1L,
                "later transaction rewrote the generation publication provenance");
        expect(IllegalArgumentException.class, () -> new ResourceTransactionEvidence(
                2L, ResourceTransactionEvidence.Outcome.ACCEPTED, List.of(), "empty acceptance"
        ));
        ResourceResidencyEvidence rejected = ResourceResidencyEvidence.rejected(key, 1L, "rejected");
        expect(IllegalArgumentException.class, () -> new ResourceTransactionEvidence(
                2L, ResourceTransactionEvidence.Outcome.ACCEPTED, List.of(rejected), "mixed acceptance"
        ));
        expect(IllegalArgumentException.class, () -> new ResourceTransactionEvidence(
                2L, ResourceTransactionEvidence.Outcome.REJECTED, List.of(accepted), "mixed rejection"
        ));
        expect(IllegalArgumentException.class, () -> new ResourceTransactionEvidence(
                2L, ResourceTransactionEvidence.Outcome.ACCEPTED, List.of(accepted, accepted), "duplicate evidence"
        ));

        ResourceRetirementPolicy atBound = new ResourceRetirementPolicy(2, 10L, OptionalLong.of(8L));
        require(atBound.mayRetire(8L) && !atBound.mayRetire(9L),
                "retirement policy changed consumer-completion semantics");
        expect(IllegalArgumentException.class,
                () -> new ResourceRetirementPolicy(2, 10L, OptionalLong.of(7L)));
        ResourceRetirementPolicy unknown = new ResourceRetirementPolicy(2, 10L, OptionalLong.empty());
        require(!unknown.mayRetire(0L), "unknown consumer progress permitted retirement");
        expect(IllegalStateException.class, () -> unknown.requireRetirable(0L));
    }

    private static void retentionContract() {
        var defaults = EvidenceRetentionPolicy.bounded();
        require(defaults.commandCapacity() == 256 && defaults.retiredResourceCapacity() == 256,
                "default historical budgets changed");
        expect(IllegalArgumentException.class, () -> new EvidenceRetentionPolicy(0, 1, 1, 1));
        expect(IllegalArgumentException.class, () -> new EvidenceRetentionPolicy(1, -1, 1, 1));
        expect(IllegalArgumentException.class, () -> new EvidenceRetentionPolicy(1, 1, 0, 1));
        expect(IllegalArgumentException.class, () -> new EvidenceRetentionPolicy(1, 1, 1, 0));
        var tiny = new EvidenceRetentionPolicy(2, 3, 4, 5);
        var configuration = RendererConfig.expertBuilder().evidenceRetention(tiny).build();
        require(configuration.copyBuilder().build().equals(configuration)
                && configuration.evidenceRetention().equals(tiny), "configuration copy lost retention policy");
        require(!configuration.equals(RendererConfig.expertBuilder().build()), "config equality ignored evidence policy");
        expect(NullPointerException.class, () -> RendererConfig.expertBuilder().evidenceRetention(null));
        require(EvidenceQuery.available("snapshot").evidence().orElseThrow().equals("snapshot"), "query lost evidence");
        for (var status : EvidenceQuery.Status.values()) {
            if (status != EvidenceQuery.Status.AVAILABLE) {
                require(EvidenceQuery.absent(status).evidence().isEmpty(), "absence fabricated evidence");
                expect(IllegalArgumentException.class, () -> new EvidenceQuery<>(status, java.util.Optional.of("snapshot")));
            }
        }
        expect(IllegalArgumentException.class, () -> EvidenceQuery.absent(EvidenceQuery.Status.AVAILABLE));
        expect(NullPointerException.class, () -> EvidenceQuery.available(null));
        expect(IllegalArgumentException.class, () -> new EvidenceRetentionStatistics(
                tiny, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        expect(IllegalArgumentException.class, () -> new EvidenceRetentionStatistics(
                tiny, 2, 2, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
    }

    private static ResourceResidencyEvidence evidence(
            ResourceGenerationKey key,
            ResourceResidencyEvidence.Outcome outcome,
            OptionalLong submission,
            OptionalLong consumer
    ) {
        return new ResourceResidencyEvidence(key, outcome, 1L, submission, consumer, "contract");
    }

    private static <T extends Throwable> void expect(Class<T> type, Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("expected " + type.getName() + " but caught " + failure, failure);
        }
        throw new AssertionError("expected " + type.getName() + " but no exception was thrown");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
