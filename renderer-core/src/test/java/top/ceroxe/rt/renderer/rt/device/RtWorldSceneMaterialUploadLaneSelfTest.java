package top.ceroxe.rt.renderer.rt.device;

import top.ceroxe.rt.renderer.rt.device.RtWorldSceneMaterialUploadLane.WorldSubmission;
import top.ceroxe.rt.renderer.rt.device.RtWorldSceneMaterialUploadLane.WorldSubmissionStatus;
import top.ceroxe.rt.renderer.rt.material.RtSceneMaterialTable;

public final class RtWorldSceneMaterialUploadLaneSelfTest {
   private RtWorldSceneMaterialUploadLaneSelfTest() {
   }

   public static void main(String[] args) {
      detectsEveryMaterialGenerationSource();
      keepsNonImmediateResultsMetadataFree();
      rejectsInvalidRevisionInputs();
      System.out.println("RtWorldSceneMaterialUploadLaneSelfTest passed");
   }

   private static void detectsEveryMaterialGenerationSource() {
      require(!dirty(4L, 7L, 9L, 4L, 7L, 9L), "equal generations must not schedule an upload");
      require(dirty(5L, 7L, 9L, 4L, 7L, 9L), "section material changes must be visible");
      require(dirty(4L, 8L, 9L, 4L, 7L, 9L), "dynamic material changes must be visible");
      require(dirty(4L, 7L, 10L, 4L, 7L, 9L), "texture catalog changes must be visible");
   }

   private static void keepsNonImmediateResultsMetadataFree() {
      RtWorldSceneMaterialUploadLane.WorldSubmission deferred = WorldSubmission.deferred();
      RtWorldSceneMaterialUploadLane.WorldSubmission pending = WorldSubmission.pending();
      require(deferred.status() == WorldSubmissionStatus.DEFERRED, "descriptor conflicts must remain explicitly deferred");
      require(pending.status() == WorldSubmissionStatus.PENDING_BIND, "transferred async uploads must remain explicitly pending");
      require(deferred.materialSnapshot() == null && pending.materialSnapshot() == null, "only an immediate bind may carry unowned material metadata");
   }

   private static void rejectsInvalidRevisionInputs() {
      requireFailure(() -> dirty(-1L, 0L, 0L, 0L, 0L, 0L));
      requireFailure(() -> new RtWorldSceneMaterialUploadLane.WorldSubmission(WorldSubmissionStatus.PENDING_BIND, (RtSceneMaterialTable.Snapshot)null, 0L));
   }

   private static boolean dirty(long section, long dynamic, long texture, long publishedSection, long publishedDynamic, long boundTexture) {
      return RtWorldSceneMaterialUploadLane.materialGenerationIsDirty(section, dynamic, texture, publishedSection, publishedDynamic, boundTexture);
   }

   private static void requireFailure(Runnable action) {
      try {
         action.run();
      } catch (IllegalArgumentException value2) {
         return;
      }

      throw new AssertionError("invalid upload-lane state was accepted");
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}
