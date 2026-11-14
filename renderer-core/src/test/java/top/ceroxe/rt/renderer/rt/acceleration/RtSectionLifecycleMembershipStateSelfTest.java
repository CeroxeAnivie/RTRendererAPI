package top.ceroxe.rt.renderer.rt.acceleration;

import java.util.List;
import top.ceroxe.rt.renderer.scene.PackedSectionMembership;
import top.ceroxe.rt.renderer.scene.SectionKey;

public final class RtSectionLifecycleMembershipStateSelfTest {
   private RtSectionLifecycleMembershipStateSelfTest() {
   }

   public static void main(String[] arguments) {
      RtSectionLifecycleMembershipState state = new RtSectionLifecycleMembershipState();
      SectionKey first = new SectionKey(1, 2, 3);
      SectionKey second = new SectionKey(4, 5, 6);
      state.addResident(first);
      long residentRevision = state.residentRevision();
      PackedSectionMembership resident = state.resident();
      require(resident.contains(first), "resident publication lost an inserted section");
      require(state.resident() == resident, "stable resident revision must reuse publication identity");
      state.addResident(first);
      require(state.residentRevision() == residentRevision, "duplicate resident insertion must not advance membership revision");
      expectFailure(() -> state.residentKeys().add(second));
      state.addActive(first);
      state.addActive(second);
      PackedSectionMembership active = state.active();
      require(active.containsAll(List.of(first, second)) && state.activeRevision() == 2L, "batched active changes were not committed in one exact publication");
      require(state.active() == active, "stable active revision must reuse its immutable publication identity");
      expectFailure(() -> state.addActive(first));
      state.removeActive(first);
      state.removeActive(second);
      require(state.active().isEmpty() && state.activeRevision() == 4L, "batched active removals did not publish an empty successor");
      expectFailure(() -> state.removeActive(first));
      PackedSectionMembership boundInput = PackedSectionMembership.copyOf(List.of(first, second));
      PackedSectionMembership bound = state.bound(boundInput, 9L);
      require(bound.size() == 2 && state.bound(boundInput, 9L) == bound, "stable bound-world revision must reuse its immutable publication");
      state.clearResidents();
      require(!state.hasResidents() && state.resident().isEmpty(), "resident clear did not invalidate and rebuild the publication");
      System.out.println("RtSectionLifecycleMembershipStateSelfTest passed");
   }

   private static void expectFailure(Runnable action) {
      try {
         action.run();
      } catch (RuntimeException value2) {
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
