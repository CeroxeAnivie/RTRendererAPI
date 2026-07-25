package top.ceroxe.rt.renderer.rt.pipeline;

public final class RtDescriptorGenerationStateSelfTest {
   private RtDescriptorGenerationStateSelfTest() {
   }

   public static void main(String[] args) {
      testUnchangedAndForcedTransitions();
      testWorldAndDynamicCommit();
      testStaleTransitionRejectedWithoutMutation();
      System.out.println("RtDescriptorGenerationStateSelfTest passed");
   }

   private static void testUnchangedAndForcedTransitions() {
      RtDescriptorGenerationState state = new RtDescriptorGenerationState(11L, 12L, 3, 7L);
      RtDescriptorGenerationState.Transition unchanged = state.prepareWorld(11L, 3, false);
      require(!unchanged.changed() && unchanged.nextGeneration() == 7L, "unchanged world binding advanced its generation");
      state.commit(unchanged);
      RtDescriptorGenerationState.Transition forced = state.prepareWorld(11L, 3, true);
      require(forced.changed() && forced.nextGeneration() == 8L, "forced material change did not advance generation");
      state.commit(forced);
      require(state.generation() == 8L, "forced transition was not committed");
   }

   private static void testWorldAndDynamicCommit() {
      RtDescriptorGenerationState state = new RtDescriptorGenerationState(1L, 2L, 0, 1L);
      RtDescriptorGenerationState.Transition transition = state.prepareWorldAndDynamic(3L, 4L, 5, false);
      state.commit(transition);
      require(state.worldTlas() == 3L, "world TLAS was not committed");
      require(state.dynamicTlas() == 4L, "dynamic TLAS was not committed");
      require(state.terrainMaterialCount() == 5, "terrain material count was not committed");
      require(state.generation() == 2L, "descriptor generation was not advanced");
   }

   private static void testStaleTransitionRejectedWithoutMutation() {
      RtDescriptorGenerationState state = new RtDescriptorGenerationState(1L, 2L, 0, 1L);
      RtDescriptorGenerationState.Transition stale = state.prepareWorld(3L, 0, false);
      state.commit(state.prepareWorld(4L, 0, false));
      expectIllegalState(() -> state.commit(stale));
      require(state.worldTlas() == 4L && state.generation() == 2L, "stale transition partially mutated active descriptor state");
      expectIllegalArgument(() -> new RtDescriptorGenerationState(0L, 0L, -1, 1L));
      expectIllegalArgument(() -> new RtDescriptorGenerationState(0L, 0L, 0, 0L));
   }

   private static void expectIllegalArgument(Runnable action) {
      try {
         action.run();
         throw new AssertionError("expected IllegalArgumentException");
      } catch (IllegalArgumentException value2) {
      }
   }

   private static void expectIllegalState(Runnable action) {
      try {
         action.run();
         throw new AssertionError("expected IllegalStateException");
      } catch (IllegalStateException value2) {
      }
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}
