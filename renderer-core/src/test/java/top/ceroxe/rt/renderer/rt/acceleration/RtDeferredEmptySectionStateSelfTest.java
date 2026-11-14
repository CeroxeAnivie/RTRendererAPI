package top.ceroxe.rt.renderer.rt.acceleration;

import java.util.List;
import java.util.Set;
import top.ceroxe.rt.renderer.scene.SectionKey;

public final class RtDeferredEmptySectionStateSelfTest {
   private RtDeferredEmptySectionStateSelfTest() {
   }

   public static void main(String[] arguments) {
      require(RtDeferredEmptySectionState.shouldDefer(true, true, 0), "streaming empty successor was not deferred behind committed retention");
      require(!RtDeferredEmptySectionState.shouldDefer(false, true, 0), "source-only empty section was incorrectly deferred");
      require(!RtDeferredEmptySectionState.shouldDefer(true, false, 0), "unretained empty successor was incorrectly deferred");
      require(!RtDeferredEmptySectionState.shouldDefer(true, true, 2), "interactive block mutation did not force authoritative removal");
      RtDeferredEmptySectionState state = new RtDeferredEmptySectionState();
      SectionKey retained = new SectionKey(1, 0, 0);
      SectionKey releasable = new SectionKey(2, 0, 0);
      state.defer(retained);
      state.defer(releasable);
      state.defer(releasable);
      List<SectionKey> released = state.releaseUnretained(Set.of(retained));
      require(released.equals(List.of(releasable)) && state.size() == 1, "deferred membership did not release only the unretained section once");
      require(state.releaseUnretained(Set.of(retained)).isEmpty(), "stable retained publication produced duplicate releases");
      state.resolve(retained);
      require(state.size() == 0, "explicit empty resolution remained deferred");
      state.defer(retained);
      state.clear();
      require(state.size() == 0, "full reset retained deferred empty membership");
      System.out.println("RtDeferredEmptySectionStateSelfTest passed");
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}
