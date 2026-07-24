package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import top.ceroxe.mcvulkanrt.renderer.RendererFrameCausality;
import top.ceroxe.mcvulkanrt.renderer.scene.PackedSectionMembership;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;

import java.util.List;

/** Verifies paired active revision/causality ownership and immutable publication reuse. */
public final class RtActiveSectionContentStateSelfTest {
    private RtActiveSectionContentStateSelfTest() {
    }

    public static void main(String[] arguments) {
        SectionKey key = new SectionKey(3, -2, 7);
        RendererFrameCausality firstCausality = RendererFrameCausality.untraced(11L);
        RendererFrameCausality secondCausality = RendererFrameCausality.untraced(12L);
        PackedSectionMembership membership = PackedSectionMembership.canonicalDistinct(List.of(key));
        RtActiveSectionContentState state = new RtActiveSectionContentState();

        state.install(key, 41L, firstCausality);
        RtActiveSectionContentState.Publication first = state.publication(membership);
        require(first == state.publication(membership), "unchanged active content must reuse one publication");
        require(first.revisions().valueOrDefault(key, -1L) == 41L, "active revision was not published");
        require(first.causalities().causality(key).equals(firstCausality),
                "active causality was not aligned with its revision");

        long stableGeneration = state.generation();
        state.install(key, 41L, firstCausality);
        require(state.generation() == stableGeneration,
                "an identical install must not create a false content generation");
        require(first == state.publication(membership),
                "an identical install must preserve publication identity");

        state.install(key, 42L, secondCausality);
        RtActiveSectionContentState.Publication successor = state.publication(membership);
        require(successor != first, "changed active content must publish a successor");
        require(successor.revisions().valueOrDefault(key, -1L) == 42L,
                "successor revision was not published");
        require(successor.causalities().causality(key).equals(secondCausality),
                "successor causality was not published atomically");

        state.remove(key);
        require(state.publication(PackedSectionMembership.empty()).revisions().isEmpty(),
                "removing the last live BLAS must release the derived publication");
        require(state.firstMissingRevision(membership).equals(key),
                "failure-only diagnosis must identify the exact missing publication key");
        RtSectionOwnershipProof.Presence divergentPresence = new RtSectionOwnershipProof.Presence(
                true, true, true, false, true, true, true, false,
                false, false, false, true
        );
        require(
                RtSectionOwnershipProof.classify(divergentPresence)
                        == RtSectionOwnershipProof.Classification.ACTIVE_MEMBERSHIP_WITHOUT_CONTENT,
                "proof classifier must distinguish active membership/content divergence"
        );
        expectFailure(() -> state.remove(key));
        expectFailure(() -> state.install(key, -1L, firstCausality));
        System.out.println("RtActiveSectionContentStateSelfTest passed");
    }

    private static void expectFailure(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError("expected operation to fail");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
