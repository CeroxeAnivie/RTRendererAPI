package top.ceroxe.rt.renderer.rt.acceleration;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import top.ceroxe.rt.renderer.RendererForegroundWork;
import top.ceroxe.rt.renderer.RendererViewState;
import top.ceroxe.rt.renderer.scene.PackedSectionMembership;
import top.ceroxe.rt.renderer.scene.SectionKey;

public final class RtSectionForegroundStateSelfTest {
   private RtSectionForegroundStateSelfTest() {
   }

   public static void main(String[] arguments) {
      RtSectionForegroundState state = new RtSectionForegroundState();
      SectionKey first = new SectionKey(1, 2, 3);
      SectionKey second = new SectionKey(4, 5, 6);
      PackedSectionMembership firstAuthority = PackedSectionMembership.canonicalDistinct(List.of(first));
      RendererForegroundWork initial = work(1L, 11L, firstAuthority, Set.of());
      RtSectionForegroundState.Transition initialTransition = state.accept(initial);
      require(initialTransition.changed() && initialTransition.reconciliationRequired() && initialTransition.authorityChanged(), "first authoritative publication did not request full reconciliation");
      require(state.work() == initial && state.view() == initial.viewState(), "foreground work and view were not committed as one publication");
      require(state.authority() == firstAuthority && state.authorityRevision() == 1L, "foreground authority identity and revision were not committed together");
      PackedSectionMembership equalButNew = PackedSectionMembership.canonicalDistinct(new ArrayList<>(List.of(first)));
      require(equalButNew != firstAuthority && equalButNew.equals(firstAuthority), "test requires equal membership with a different immutable identity");
      RendererForegroundWork successorOnly = work(2L, 12L, equalButNew, Set.of());
      RtSectionForegroundState.Transition successorTransition = state.accept(successorOnly);
      require(successorTransition.changed() && !successorTransition.reconciliationRequired(), "successor-only publication unnecessarily rebuilt unchanged admission");
      require(state.authority() == firstAuthority && state.work().sectionKeys() == firstAuthority, "equal successor membership was not rebased to the owner identity");
      require(state.authorityRevision() == 1L, "equal successor membership advanced authority revision");
      RendererForegroundWork retentionOnly = work(3L, 13L, equalButNew, Set.of(first));
      RtSectionForegroundState.Transition retentionTransition = state.accept(retentionOnly);
      require(retentionTransition.reconciliationRequired() && !retentionTransition.authorityChanged(), "retention-only publication did not reconcile without changing authority");
      require(state.retainedPresentationKeys().equals(Set.of(first)), "retained presentation keys were not committed with foreground work");
      PackedSectionMembership expanded = PackedSectionMembership.canonicalDistinct(List.of(first, second));
      RtSectionForegroundState.Transition expandedTransition = state.accept(work(4L, 14L, expanded, Set.of()));
      require(expandedTransition.authorityChanged() && state.authority() == expanded, "changed authoritative membership was not published");
      require(state.authorityRevision() == 2L, "changed authoritative membership did not advance exactly one revision");
      require(!state.accept(state.work()).changed(), "identical foreground publication was not an identity-stable no-op");
      System.out.println("RtSectionForegroundStateSelfTest passed");
   }

   private static RendererForegroundWork work(long viewRevision, long successorGeneration, PackedSectionMembership membership, Set<SectionKey> retained) {
      return new RendererForegroundWork(RendererViewState.host(viewRevision, membership), successorGeneration, retained);
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}
