package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import java.util.List;
import java.util.Set;
import top.ceroxe.mcvulkanrt.renderer.scene.PackedSectionMembership;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;

/** Verifies provisional-to-authoritative priority promotion and interactive overlay ownership. */
public final class RtSectionBuildPriorityStateSelfTest {
    private RtSectionBuildPriorityStateSelfTest() {
    }

    public static void main(String[] arguments) {
        RtSectionBuildPriorityState state = new RtSectionBuildPriorityState();
        SectionKey first = new SectionKey(1, 0, 0);
        SectionKey second = new SectionKey(2, 0, 0);
        SectionKey later = new SectionKey(3, 0, 0);

        state.admitProvisional(false, List.of(first, second));
        Set<SectionKey> provisional = state.preferredKeys();
        require(provisional.equals(Set.of(first, second)),
                "first source batch did not establish provisional priority");
        expectFailure(() -> provisional.add(later));
        state.admitProvisional(false, List.of(later));
        require(state.preferredKeys() == provisional,
                "later source batch replaced the stable provisional publication");

        state.markInteractive(second);
        state.markInteractive(second);
        require(state.isInteractive(second) && state.interactiveCount() == 1,
                "interactive urgency was not idempotent");
        expectFailure(() -> state.interactiveKeys().clear());

        PackedSectionMembership authority = PackedSectionMembership.copyOf(List.of(later));
        state.publishAuthority(authority);
        require(state.preferredKeys() == authority,
                "authoritative immutable membership identity was not preserved");
        require(!state.preferredKeys().contains(first),
                "provisional keys survived authoritative priority promotion");
        state.admitProvisional(true, List.of(first));
        require(state.preferredKeys() == authority,
                "authoritative priority was replaced by a later provisional batch");

        state.resolveInteractive(second);
        require(!state.isInteractive(second), "resolved interactive urgency remained published");
        state.markInteractive(first);
        state.clear();
        require(state.preferredKeys().isEmpty() && state.interactiveKeys().isEmpty(),
                "full priority reset did not clear both lanes");
        System.out.println("RtSectionBuildPriorityStateSelfTest passed");
    }

    private static void expectFailure(Runnable action) {
        try {
            action.run();
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError("expected immutable priority view");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
