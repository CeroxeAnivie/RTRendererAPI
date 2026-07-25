package top.ceroxe.rt.renderer.backend.vulkan;

import java.util.List;
import java.util.Set;
import top.ceroxe.rt.renderer.api.CameraState;
import top.ceroxe.rt.renderer.api.HistoryInvalidationReason;
import top.ceroxe.rt.renderer.api.HistoryResetReason;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.SceneInstance;
import top.ceroxe.rt.renderer.api.TemporalRenderingOptions;
import top.ceroxe.rt.renderer.api.SceneInstance.Mobility;

public final class TemporalHistoryTrackerSelfTest {
   private TemporalHistoryTrackerSelfTest() {
   }

   public static void main(String[] args) {
      disabledModePublishesNoTemporalReasons();
      rejectedPreparationDoesNotAdvanceSource();
      frameAndSceneInvalidationsAreExact();
      dynamicInstanceWritesPreserveStaticHistory();
      System.out.println("TemporalHistoryTrackerSelfTest passed");
   }

   private static void disabledModePublishesNoTemporalReasons() {
      TemporalHistoryTracker tracker = new TemporalHistoryTracker(TemporalRenderingOptions.disabled());
      TemporalHistoryTracker.PreparedFrame first = tracker.prepare(frame(0L, 64, 36, camera(60.0)), 0L);
      require(!first.historyValid() && first.invalidations().isEmpty(), "disabled temporal mode fabricated history or invalidation reasons");
      tracker.commit(first);
      TemporalHistoryTracker.PreparedFrame second = tracker.prepare(frame(1L, 64, 36, camera(60.0)), 0L);
      require(!second.historyValid() && second.invalidations().isEmpty(), "disabled temporal mode retained a hidden history source");
   }

   private static void rejectedPreparationDoesNotAdvanceSource() {
      TemporalHistoryTracker tracker = new TemporalHistoryTracker(TemporalRenderingOptions.balanced());
      TemporalHistoryTracker.PreparedFrame first = tracker.prepare(frame(0L, 64, 36, camera(60.0)), 0L);
      require(first.invalidations().equals(Set.of(HistoryInvalidationReason.FIRST_FRAME)), "first temporal frame did not start a fresh generation");
      tracker.commit(first);
      TemporalHistoryTracker.PreparedFrame rejected = tracker.prepare(frame(1L, 64, 36, camera(60.0)).toBuilder().resetTemporalHistory(HistoryResetReason.CAMERA_CUT).build(), 0L);
      require(rejected.invalidations().contains(HistoryInvalidationReason.CAMERA_CUT), "caller camera cut was not mapped to an effective invalidation");
      TemporalHistoryTracker.PreparedFrame retry = tracker.prepare(frame(1L, 64, 36, camera(60.0)), 0L);
      require(retry.historyValid() && retry.invalidations().isEmpty(), "uncommitted preparation advanced or poisoned the temporal source");
      tracker.commit(retry);
      expect(IllegalStateException.class, () -> tracker.commit(rejected));
   }

   private static void frameAndSceneInvalidationsAreExact() {
      TemporalHistoryTracker tracker = new TemporalHistoryTracker(TemporalRenderingOptions.balanced());
      TemporalHistoryTracker.PreparedFrame first = tracker.prepare(frame(4L, 80, 45, camera(60.0)), 0L);
      tracker.commit(first);
      TemporalHistoryTracker.PreparedFrame discontinuous = tracker.prepare(frame(7L, 96, 54, camera(75.0)).toBuilder().resetTemporalHistory(HistoryResetReason.SCENE_DISCONTINUITY).build(), 0L);
      require(discontinuous.invalidations().equals(Set.of(HistoryInvalidationReason.FRAME_SEQUENCE_DISCONTINUITY, HistoryInvalidationReason.OUTPUT_EXTENT_CHANGED, HistoryInvalidationReason.CAMERA_PROJECTION_CHANGED, HistoryInvalidationReason.SCENE_DISCONTINUITY)), "frame invalidation union was incomplete or non-deterministic: " + String.valueOf(discontinuous.invalidations()));
      TemporalHistoryTracker.PreparedFrame continuous = tracker.prepare(frame(5L, 80, 45, camera(60.0)), 0L);
      tracker.commit(continuous);
      tracker.sceneApplied(sceneReset(0L));
      TemporalHistoryTracker.PreparedFrame afterScene = tracker.prepare(frame(6L, 80, 45, camera(60.0)), 0L);
      require(afterScene.invalidations().equals(Set.of(HistoryInvalidationReason.SCENE_TOPOLOGY_CHANGED)), "scene reset did not invalidate topology exactly once");
      expect(UnsupportedOperationException.class, () -> afterScene.invalidations().clear());
   }

   private static RenderFrameRequest frame(long sequence, int width, int height, CameraState camera) {
      return RenderFrameRequest.builder(sequence, width, height, camera).build();
   }

   private static void dynamicInstanceWritesPreserveStaticHistory() {
      TemporalHistoryTracker tracker = new TemporalHistoryTracker(TemporalRenderingOptions.balanced());
      TemporalHistoryTracker.PreparedFrame first = tracker.prepare(frame(0L, 64, 36, camera(60.0)), 0L);
      tracker.commit(first);
      tracker.sceneApplied(instanceWrite(1L, Mobility.DYNAMIC));
      TemporalHistoryTracker.PreparedFrame dynamic = tracker.prepare(frame(1L, 64, 36, camera(60.0)), 1L);
      require(dynamic.historyValid() && dynamic.invalidations().isEmpty(), "dynamic instance write discarded unrelated static history");
      tracker.commit(dynamic);
      tracker.sceneApplied(instanceWrite(2L, Mobility.STATIC));
      TemporalHistoryTracker.PreparedFrame staticWrite = tracker.prepare(frame(2L, 64, 36, camera(60.0)), 2L);
      require(staticWrite.invalidations().equals(Set.of(HistoryInvalidationReason.SCENE_TOPOLOGY_CHANGED)), "static instance write did not invalidate temporal geometry");
   }

   private static VulkanSceneResidency.SceneChangeSet instanceWrite(long revision, SceneInstance.Mobility mobility) {
      SceneInstance instance = SceneInstance.builder(7L, 11L).mobility(mobility).build();
      VulkanSceneResidency.DomainChange<SceneInstance> instances = new VulkanSceneResidency.DomainChange<>(List.of(new StableIdentitySlots.SlotWrite<>(0, instance.id(), instance)), new long[0], new int[0], new VulkanSceneResidency.DomainUpdateStatistics(1, 0, 0, 1, 1));
      return new VulkanSceneResidency.SceneChangeSet(revision - 1L, revision, false, emptyDomain(), emptyDomain(), emptyDomain(), instances, emptyDomain());
   }

   private static CameraState camera(double verticalFovDegrees) {
      return CameraState.lookAt(0.0, 1.0, 4.0, 0.0, 1.0, 0.0).verticalFieldOfViewDegrees(verticalFovDegrees).aspectRatio(1.7777777777777777).build();
   }

   private static VulkanSceneResidency.SceneChangeSet sceneReset(long revision) {
      return new VulkanSceneResidency.SceneChangeSet(-1L, revision, true, emptyDomain(), emptyDomain(), emptyDomain(), emptyDomain(), emptyDomain());
   }

   private static <T> VulkanSceneResidency.DomainChange<T> emptyDomain() {
      return new VulkanSceneResidency.DomainChange<>(List.of(), new long[0], new int[0], new VulkanSceneResidency.DomainUpdateStatistics(0, 0, 0, 0, 0));
   }

   private static <T extends Throwable> void expect(Class<T> type, ThrowingRunnable action) {
      try {
         action.run();
      } catch (Throwable failure) {
         if (type.isInstance(failure)) {
            return;
         }

         throw new AssertionError("expected " + type.getName() + " but caught " + String.valueOf(failure), failure);
      }

      throw new AssertionError("expected " + type.getName() + " but no exception was thrown");
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }

   @FunctionalInterface
   private interface ThrowingRunnable {
      void run() throws Throwable;
   }
}
