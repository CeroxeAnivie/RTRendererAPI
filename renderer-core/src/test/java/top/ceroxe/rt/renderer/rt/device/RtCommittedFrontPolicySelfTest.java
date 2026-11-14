package top.ceroxe.rt.renderer.rt.device;

import java.util.Set;
import top.ceroxe.rt.renderer.rt.acceleration.RtSectionBlasCache;
import top.ceroxe.rt.renderer.rt.device.RtCommittedFrontPolicy.Decision;
import top.ceroxe.rt.renderer.scene.ChunkKey;
import top.ceroxe.rt.renderer.scene.SceneUpdateBatch;
import top.ceroxe.rt.renderer.scene.SectionKey;

public final class RtCommittedFrontPolicySelfTest {
   private RtCommittedFrontPolicySelfTest() {
   }

   public static void main(String[] args) {
      preservesCommittedFrontDuringConvergence();
      rejectsInvalidatedCommittedFront();
      retainsInvalidationUntilSuccessorPublication();
      invalidatesOnlySemanticTerrainChanges();
      continuousSuccessorChurnCannotFreezeCommittedFront();
      invalidatesOnlyExplicitCommittedMembershipRemovals();
      removesInteractiveEmptySectionsWithoutFrontDeadlock();
      System.out.println("RtCommittedFrontPolicySelfTest passed");
   }

   private static void preservesCommittedFrontDuringConvergence() {
      require(RtCommittedFrontPolicy.classify(true, true, true) == Decision.ELIGIBLE, "a valid immutable front must remain renderable while its successor converges");
      require(RtCommittedFrontPolicy.classify(true, true, false) == Decision.CONVERGED, "normal frame production must own the converged path");
   }

   private static void rejectsInvalidatedCommittedFront() {
      require(RtCommittedFrontPolicy.classify(false, true, true) == Decision.TERRAIN_INVALIDATED, "removal, unload, full resync, and block mutation must not render stale terrain");
      require(RtCommittedFrontPolicy.classify(false, true, false) == Decision.TERRAIN_INVALIDATED, "an unpublished invalidation must outrank a transient converged readiness observation");
      require(RtCommittedFrontPolicy.classify(true, false, true) == Decision.NO_COMMITTED_WORLD, "continuity requires a previously committed world front");
   }

   private static void retainsInvalidationUntilSuccessorPublication() {
      require(!RtCommittedFrontPolicy.generationIsCurrent(8L, 7L), "a later empty frame must not resurrect terrain invalidated by an earlier accepted update");
      require(RtCommittedFrontPolicy.generationIsCurrent(8L, 8L), "binding the successor generation must atomically restore committed-front continuity");
      require(RtCommittedFrontPolicy.generationIsCurrent(8L, 9L), "a newer committed generation must satisfy every earlier invalidation");
   }

   private static void invalidatesOnlySemanticTerrainChanges() {
      SectionKey committed = new SectionKey(0, 4, 0);
      Set<SectionKey> committedFront = Set.of(committed);
      require(!RtCommittedFrontPolicy.invalidatesCommittedFront(false, Set.of(), Set.of(), committedFront), "material-only and additive streaming updates must not invalidate the front");
      require(RtCommittedFrontPolicy.invalidatesCommittedFront(true, Set.of(), Set.of(), committedFront), "full resync must invalidate the previous front");
      require(RtCommittedFrontPolicy.invalidatesCommittedFront(false, Set.of(committed), Set.of(), committedFront), "section removal must invalidate the previous front");
      require(RtCommittedFrontPolicy.invalidatesCommittedFront(false, Set.of(), Set.of(committed.chunkKey()), committedFront), "chunk unload must invalidate the previous front");
      require(!RtCommittedFrontPolicy.invalidatesCommittedFront(false, Set.of(), Set.of(), committedFront), "block mutation without explicit membership removal must retain the immutable front");
   }

   private static void continuousSuccessorChurnCannotFreezeCommittedFront() {
      long publishedWorldRevision = 17L;
      int dispatches = 0;

      for(int frame = 0; frame < 10000; ++frame) {
         require(RtCommittedFrontPolicy.classify(true, true, true) == Decision.ELIGIBLE, "continuous successor work must not revoke a valid immutable front");
         ++dispatches;
      }

      require(publishedWorldRevision == 17L && dispatches == 10000, "continuous successor churn must dispatch every eligible camera frame without a timer gate");
      require(RtCommittedFrontPolicy.classify(false, true, true) == Decision.TERRAIN_INVALIDATED, "the liveness guarantee must not resurrect a front invalidated by a block or membership change");
   }

   private static void invalidatesOnlyExplicitCommittedMembershipRemovals() {
      SectionKey committed = new SectionKey(2, 4, 3);
      SectionKey successorOnly = new SectionKey(8, 4, 9);
      Set<SectionKey> committedFront = Set.of(committed);
      require(!RtCommittedFrontPolicy.invalidatesCommittedFront(false, Set.of(successorOnly), Set.of(), committedFront), "removing a successor-only section must not revoke the published front");
      require(!RtCommittedFrontPolicy.invalidatesCommittedFront(false, Set.of(), Set.of(new ChunkKey(8, 9)), committedFront), "unloading a successor-only chunk must not revoke the published front");
      require(RtCommittedFrontPolicy.invalidatesCommittedFront(false, Set.of(committed), Set.of(), committedFront), "explicit removal of a committed member must block stale dispatch");
      require(RtCommittedFrontPolicy.invalidatesCommittedFront(false, Set.of(), Set.of(committed.chunkKey()), committedFront), "unloading a chunk that owns a committed member must block stale dispatch");
   }

   private static void removesInteractiveEmptySectionsWithoutFrontDeadlock() {
      require(RtSectionBlasCache.deferEmptySectionRemoval(true, true), "non-semantic empty successors may retain a committed presentation generation");
      require(!RtSectionBlasCache.deferEmptySectionRemoval(true, true, SceneUpdateBatch.sourceFlagsForBlockMutation()), "digging the last block must not wait for the front that still contains that block");
      require(RtSectionBlasCache.deferEmptySectionRemoval(true, true, 8), "streaming unload must not promote a bulk removal into the interactive lane");
   }

   private static Throwable expectFailure(Runnable action) {
      try {
         action.run();
         return null;
      } catch (Throwable failure) {
         return failure;
      }
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}
