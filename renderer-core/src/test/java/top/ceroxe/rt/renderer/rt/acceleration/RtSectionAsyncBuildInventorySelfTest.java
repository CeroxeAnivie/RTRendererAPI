package top.ceroxe.rt.renderer.rt.acceleration;

import java.util.List;
import java.util.Set;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import top.ceroxe.rt.renderer.RendererFrameCausality;
import top.ceroxe.rt.renderer.RtEdgeSink;
import top.ceroxe.rt.renderer.orchestration.work.SectionWorkLane;
import top.ceroxe.rt.renderer.rt.material.RtSceneMaterialTable.SectionMaterial;
import top.ceroxe.rt.renderer.scene.FaceDirection;
import top.ceroxe.rt.renderer.scene.SectionKey;
import top.ceroxe.rt.renderer.scene.SectionTriangleMesh;

public final class RtSectionAsyncBuildInventorySelfTest {
   private RtSectionAsyncBuildInventorySelfTest() {
   }

   public static void main(String[] arguments) {
      boundsProductionRecordingParallelism();
      protectsForegroundSubmissionBoundary();
      releasesCompletedRecordingForQueuedForegroundProgress();
      queuedRecordingLifecycleIsAtomic();
      rejectedRecordingNeverPublishesOwnership();
      System.out.println("RtSectionAsyncBuildInventorySelfTest passed");
   }

   private static void protectsForegroundSubmissionBoundary() {
      require(!RtSectionAsyncSubmissionSelector.shouldSubmit(false, true, true, 0), "background recording bypassed an incomplete first-front gate");
      require(RtSectionAsyncSubmissionSelector.shouldSubmit(false, true, true, 1), "foreground recording was blocked while the first front was incomplete");
      require(RtSectionAsyncSubmissionSelector.shouldSubmit(true, false, false, 0), "stable authoritative view did not admit background recording work");

      try {
         RtSectionAsyncSubmissionSelector.shouldSubmit(true, false, false, -1);
         throw new AssertionError("negative foreground membership was accepted by submission policy");
      } catch (IllegalArgumentException value1) {
      }
   }

   private static void releasesCompletedRecordingForQueuedForegroundProgress() {
      require(RtSectionAsyncSubmissionSelector.shouldReleaseCompletedRecordingForForegroundProgress(true, true), "incomplete foreground could not release one completed dependency page");
      require(!RtSectionAsyncSubmissionSelector.shouldReleaseCompletedRecordingForForegroundProgress(true, false), "background-only work incorrectly relaxed the foreground submission gate");
      require(!RtSectionAsyncSubmissionSelector.shouldReleaseCompletedRecordingForForegroundProgress(false, true), "stable foreground incorrectly used the incomplete-coverage dependency breaker");
   }

   private static void boundsProductionRecordingParallelism() {
      require(RtPendingSectionBlasRecording.recordingWorkerCount(1) == 1, "single-CPU environments must retain one recording worker");
      require(RtPendingSectionBlasRecording.recordingWorkerCount(8) == 2, "recording worker policy did not scale conservatively on eight CPUs");
      require(RtPendingSectionBlasRecording.recordingWorkerCount(16) == 2 && RtPendingSectionBlasRecording.recordingWorkerCount(128) == 2, "recording worker policy must remain capped at two native preparation workers");
   }

   private static void queuedRecordingLifecycleIsAtomic() {
      QueueOnlyExecutor executor = new QueueOnlyExecutor(false);
      RtSectionAsyncBuildInventory inventory = new RtSectionAsyncBuildInventory(executor);
      SectionTriangleMesh mesh = mesh(7);
      RtPendingSectionBlasRecording recording = recording(mesh, false, 41L);
      RtPendingSectionBlasRecording.PrioritizedTask task = RtPendingSectionBlasRecording.task(false, recording.sequence(), () -> {
         throw new AssertionError("queue-only executor must never run its recording task");
      });
      require(inventory.recordingMembership().isEmpty() && inventory.recordingRevision() == 0L, "new async inventory did not start with an empty recording publication");
      require(inventory.allocateSequence() == 0L && inventory.allocateSequence() == 1L, "async inventory did not allocate monotonic lifecycle join identities");
      inventory.startRecording(recording, task);
      require(inventory.recordingBatchCount() == 1 && inventory.recordingRevision() == 1L, "recording list and membership revision were not published together");
      require(inventory.recordingMembership().contains(mesh.key()), "recording membership omitted its active section");
      require(inventory.hasPendingPreferredRecording(Set.of(mesh.key())), "queued preferred recording was invisible to foreground dependency admission");
      require(!inventory.hasPendingPreferredRecording(Set.of(new SectionKey(99, 0, 0))), "unrelated preferred key matched a queued recording");
      RtSectionAsyncBuildInventory.Metrics metrics = inventory.metrics(Set.of(mesh.key()));
      require(metrics.recordingBatches() == 1 && metrics.gpuBatches() == 0, "recording/GPU batch metrics were not stage-specific");
      require(metrics.activeSections() == 1 && metrics.activeTriangles() == (long)mesh.triangleCount(), "active async metrics diverged from the recording owner");
      require(metrics.retainedSections() == 1 && metrics.retainedBytes() == mesh.estimatedBytes(), "retained async metrics did not include invalidation-safe ownership");
      require(metrics.preferredSections() == 1 && metrics.backgroundBatches() == 1, "priority/background metrics lost the recording classification");
      RtSectionAsyncBuildInventory.DebugState debug = inventory.debugState(mesh.key());
      require(debug.recording() && !debug.gpu() && debug.latestSequence() == 41L, "debug state did not report the exact async owner and sequence");
      RtSectionAsyncBuildInventory.Invalidation invalidation = inventory.invalidate(mesh.key());
      require(invalidation.recordingSequences().equals(List.of(41L)) && invalidation.gpuSequences().isEmpty(), "invalidation trace did not retain the recording sequence");
      require(inventory.recordingRevision() == 2L && inventory.recordingMembership().isEmpty(), "recording invalidation did not atomically publish empty active membership");
      require(!inventory.hasPendingPreferredRecording(Set.of(mesh.key())), "invalidated recording remained a foreground dependency");
      metrics = inventory.metrics(Set.of(mesh.key()));
      require(metrics.activeSections() == 0 && metrics.retainedSections() == 1, "invalidation incorrectly released native recording retention");
      inventory.invalidate(mesh.key());
      require(inventory.recordingRevision() == 2L, "idempotent invalidation unexpectedly advanced membership revision");
      require(inventory.cancelRecordingIfQueued(0, recording), "queued invalidated recording was not cancellable through its owner");
      require(inventory.recordingBatchCount() == 0 && inventory.recordingRevision() == 2L, "removing an already-empty recording changed membership generation");
      require(inventory.closeCollecting((RuntimeException)null) == null && executor.isShutdown(), "inventory close did not shut down its owned executor cleanly");
   }

   private static void rejectedRecordingNeverPublishesOwnership() {
      QueueOnlyExecutor executor = new QueueOnlyExecutor(true);
      RtSectionAsyncBuildInventory inventory = new RtSectionAsyncBuildInventory(executor);
      RtPendingSectionBlasRecording recording = recording(mesh(9), true, 73L);
      RtPendingSectionBlasRecording.PrioritizedTask task = RtPendingSectionBlasRecording.task(true, recording.sequence(), () -> {
         throw new AssertionError("rejected recording task must never execute");
      });
      expectFailure(() -> inventory.startRecording(recording, task));
      require(inventory.recordingBatchCount() == 0 && inventory.recordingRevision() == 0L, "executor rejection partially published recording ownership");
      require(inventory.recordingMembership().isEmpty(), "executor rejection leaked a recording membership publication");
      require(inventory.closeCollecting((RuntimeException)null) == null, "empty inventory did not close cleanly after executor rejection");
   }

   private static RtPendingSectionBlasRecording recording(SectionTriangleMesh mesh, boolean foreground, long sequence) {
      RtPendingBlasBuildQueue.Work<RtSectionBlasBuildMetadata> work = new RtPendingBlasBuildQueue.Work<>(mesh, new RtSectionBlasBuildMetadata(3L, RendererFrameCausality.untraced(sequence), 0, SectionMaterial.fromMesh(mesh)), SectionWorkLane.BACKGROUND);
      return new RtPendingSectionBlasRecording(RtSectionBlasBuildBatch.capture(List.of(work), List.of(mesh)), foreground, sequence, RtEdgeSink.NOOP);
   }

   private static SectionTriangleMesh mesh(int sectionX) {
      return new SectionTriangleMesh(new SectionKey(sectionX, 0, 0), new short[]{0, 0, 0, 16, 0, 0, 16, 16, 0, 0, 16, 0}, new int[]{0, 1, 2, 0, 2, 3}, new int[]{42}, new byte[]{0}, new byte[]{(byte)FaceDirection.POSITIVE_Z.ordinal()});
   }

   private static void expectFailure(Runnable action) {
      try {
         action.run();
      } catch (RejectedExecutionException value2) {
         return;
      }

      throw new AssertionError("expected executor rejection");
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }

   private static final class QueueOnlyExecutor extends ThreadPoolExecutor {
      private final boolean reject;

      private QueueOnlyExecutor(boolean reject) {
         super(0, 1, 0L, TimeUnit.MILLISECONDS, new PriorityBlockingQueue<>());
         this.reject = reject;
      }

      public void execute(Runnable command) {
         if (this.reject) {
            throw new RejectedExecutionException("intentional self-test rejection");
         } else {
            this.getQueue().add(command);
         }
      }
   }
}
