package top.ceroxe.rt.renderer.rt.acceleration;

import top.ceroxe.rt.renderer.RendererFrameCausality;
import top.ceroxe.rt.renderer.scene.SectionKey;

public final class RtSectionBuildIntentStateSelfTest {
   private RtSectionBuildIntentStateSelfTest() {
   }

   public static void main(String[] arguments) {
      RtSectionBuildIntentState state = new RtSectionBuildIntentState();
      SectionKey key = new SectionKey(1, -2, 3);
      RendererFrameCausality first = RendererFrameCausality.untraced(7L);
      RendererFrameCausality second = RendererFrameCausality.untraced(8L);
      RtSectionBuildIntentState.Intent initial = state.publish(key, (Long)null, first, 16);
      require(initial.contentRevision() == 0L && initial.causality().equals(first), "first implicit build revision must start at zero with matching causality");
      RtSectionBuildIntentState.Intent successor = state.publish(key, 12L, second, 32);
      require(successor.contentRevision() == 12L && successor.causality().equals(second) && successor.sourceFlags() == 32, "build intent components were not replaced atomically");
      RtSectionBuildIntentState.Intent materialOnly = state.publish(key, (Long)null, first, 64);
      require(materialOnly.contentRevision() == 12L && materialOnly.causality().equals(first) && materialOnly.sourceFlags() == 64, "missing revision must preserve the current desired generation only");
      require(state.require(key) == materialOnly, "required intent lookup must return the exact immutable publication");
      state.remove(key);
      require(state.revisionOrDefault(key, -1L) == -1L && state.causality(key) == null, "removal must clear the complete build intent");
      expectFailure(() -> state.require(key));
      expectFailure(() -> state.publish(key, -1L, first, 0));
      System.out.println("RtSectionBuildIntentStateSelfTest passed");
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
