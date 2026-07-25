package top.ceroxe.rt.renderer.rt;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.RendererFrameState;
import top.ceroxe.rt.renderer.RendererFrameUpdate;
import top.ceroxe.rt.renderer.rt.material.RtSceneMaterialTable;
import top.ceroxe.rt.renderer.rt.material.RtTextureCatalog;
import top.ceroxe.rt.renderer.rt.pipeline.RtFrameSnapshot;
import top.ceroxe.rt.renderer.rt.runtime.GuardedRtCore;
import top.ceroxe.rt.renderer.rt.runtime.RtCore;
import top.ceroxe.rt.renderer.rt.runtime.RtCore.State;
import top.ceroxe.rt.renderer.scene.ChunkKey;
import top.ceroxe.rt.renderer.scene.ChunkSnapshot;
import top.ceroxe.rt.renderer.scene.FaceDirection;
import top.ceroxe.rt.renderer.scene.SceneDatabase;
import top.ceroxe.rt.renderer.scene.SceneUpdateBatch;
import top.ceroxe.rt.renderer.scene.SectionGeometryCache;
import top.ceroxe.rt.renderer.scene.SectionKey;
import top.ceroxe.rt.renderer.scene.SectionMaterialCache;
import top.ceroxe.rt.renderer.scene.SectionMeshCache;
import top.ceroxe.rt.renderer.scene.SectionTriangleMesh;
import top.ceroxe.rt.renderer.scene.SectionVoxelSnapshot;

public final class RtNativeStressSceneSelfTest {
   private static final int OUTPUT_WIDTH = intProperty("top.ceroxe.rt.rt.stress.width", 960);
   private static final int OUTPUT_HEIGHT = intProperty("top.ceroxe.rt.rt.stress.height", 540);
   private static final int SECTION_COLUMNS = intProperty("top.ceroxe.rt.rt.stress.sectionColumns", 48);
   private static final int SECTION_ROWS = intProperty("top.ceroxe.rt.rt.stress.sectionRows", 16);
   private static final int TOTAL_SECTIONS;
   private static final int MAX_INITIAL_READY_PUMP_FRAMES;
   private static final int SUSTAINED_FRAMES;
   private static final int MAX_POST_MUTATION_DRAIN_FRAMES;
   private static final int MUTATION_PERIOD_FRAMES;
   private static final int MUTATIONS_PER_BURST;
   private static final int MIN_DISTINCT_CHECKSUMS;
   private static final int MAX_VISUAL_EVIDENCE_MUTATIONS;
   private static final int MAX_READY_SNAPSHOT_LAG;
   private static final int READBACK_SAMPLE_INTERVAL;
   private static final long MAX_READY_PENDING_FRAME_AGE_MILLIS;
   private static final long MAX_READY_COMPLETION_STALL_MILLIS;
   private static final long PUMP_SLEEP_MILLIS;
   private static final double MIN_COMPLETED_FPS;
   private static final boolean ALPHA_CUTOUT_ENABLED;
   private static final boolean EXPORT_SHARED_FRAME_ENABLED;
   private static final int SHARED_FRAME_EXPORT_SAMPLE_DELTA;
   private static final int BLOCK_STATE_ID = 1;
   private static final int OUTPUT_PIXELS;
   private static final int MIN_FOREGROUND_PIXELS;
   private static final Path SNAPSHOT_PATH;

   private RtNativeStressSceneSelfTest() {
   }

   public static void main(String[] args) throws Exception {
      Map<String, String> previousProperties = installStressProperties();

      try {
         VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
         require(capability.hardwareRayTracingReady(), "native stress scene requires production RT hardware: " + capability.summary());
         StressResult result = runStressScene(capability);
         writeSnapshotPng(result.lastSnapshot(), SNAPSHOT_PATH);
         int sectionCount10001 = TOTAL_SECTIONS;
         System.out.println("RtNativeStressSceneSelfTest passed: sections=" + sectionCount10001 + ", sustainedFrames=" + SUSTAINED_FRAMES + ", dynamicBursts=" + result.dynamicBursts() + ", distinctChecksums=" + result.distinctChecksums() + ", completedFrames=" + result.completedFrames() + ", averageCompletedFps=" + result.averageCompletedFps() + ", maxReadyPendingFrameAgeMillis=" + result.maxReadyPendingFrameAgeMillis() + ", maxReadyCompletionStallMillis=" + result.maxReadyCompletionStallMillis() + ", maxReadySnapshotLag=" + result.maxReadySnapshotLag() + ", lastSnapshot=" + result.lastSnapshot().asLogFragment() + ", png=" + String.valueOf(SNAPSHOT_PATH) + ", readiness=" + result.readiness().asLogFragment() + ", activity=" + result.activity().asLogFragment());
      } finally {
         restoreProperties(previousProperties);
      }

   }

   private static StressResult runStressScene(VulkanRtCapabilityProbe.Result capability) throws Exception {
      GuardedRtCore rtCore = GuardedRtCore.isolatedHardwareTest();

      StressResult stressResult60;
      try {
         rtCore.acceptCapability(capability);
         boolean condition10000 = rtCore.state() == State.READY_FOR_SCENE_UPDATES;
         String stateDetails10001 = String.valueOf(rtCore.state());
         require(condition10000, "RT core did not open native backend for stress scene: state=" + stateDetails10001 + ", summary=" + rtCore.summary().asLogFragment());
         StressSceneState scene = new StressSceneState();
         List<SectionKey> keys = buildSectionKeys();
         rtCore.acceptFrameUpdate(scene.initialUpdate(buildMeshes(keys, 0), frameState(1L)));
         RtFrameSnapshot readySnapshot = pumpUntilSceneReady(rtCore, 2L, MAX_INITIAL_READY_PUMP_FRAMES);
         condition10000 = readySnapshot.foregroundPixels() >= MIN_FOREGROUND_PIXELS;
         stateDetails10001 = readySnapshot.asLogFragment();
         require(condition10000, "stress scene initial ready frame has too little foreground: " + stateDetails10001 + ", foregroundSample=" + foregroundSample(readySnapshot, 32) + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", activity=" + rtCore.runtimeActivity().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
         RtNativeStressGuards.assertFrameNotPathological(readySnapshot, "initial native stress frame");
         long phaseStartNanos = System.nanoTime();
         long firstCompleted = Math.max(0L, rtCore.runtimeActivity().latestCompletedFrameStateSequence());
         long lastCompletedSequence = firstCompleted;
         long lastCompletedDispatch = Math.max(0L, rtCore.runtimeActivity().latestCompletedFrameDispatch());
         long lastCompletionNanos = System.nanoTime();
         long lastExportedSharedFrameSequence = -1L;
         long completedFrameCount = 0L;
         long maxPendingAge = 0L;
         long maxCompletionStallMillis = 0L;
         long maxSnapshotLag = 0L;
         int dynamicBursts = 0;
         Set<Long> checksums = new HashSet<>();
         checksums.add(readySnapshot.checksum());

         for(int frame = 0; frame < SUSTAINED_FRAMES; ++frame) {
            long sequence = 10000L + (long)frame;
            RendererFrameUpdate update;
            if (frame % MUTATION_PERIOD_FRAMES == 0) {
               update = scene.replacePreparedMeshes(mutationMeshes(keys, dynamicBursts + 1), frameState(sequence));
               ++dynamicBursts;
            } else {
               update = RendererFrameUpdate.empty(emptyBatch(), frameState(sequence));
            }

            rtCore.acceptFrameUpdate(update);
            RtCore.RuntimeActivity activity = rtCore.runtimeActivity();
            RtSceneReadiness readiness = rtCore.sceneReadiness();
            long nowNanos = System.nanoTime();
            if (activity.latestCompletedFrameDispatch() > lastCompletedDispatch) {
               completedFrameCount += activity.latestCompletedFrameDispatch() - lastCompletedDispatch;
               lastCompletedDispatch = activity.latestCompletedFrameDispatch();
               lastCompletedSequence = activity.latestCompletedFrameStateSequence();
               lastCompletionNanos = nowNanos;
            }

            RtFrameSnapshot snapshot = rtCore.latestFrameSnapshot();
            if (snapshot != null) {
               checksums.add(snapshot.checksum());
            }

            if (readiness.builtRevisionIsCurrent() && !readiness.hasPendingRtBuilds()) {
               long pendingAge = activity.pendingFrameAgeMillis();
               long snapshotLag = snapshot == null ? 9223372036854775807L : Math.max(0L, sequence - snapshot.frameStateSequence());
               long completionStallMillis = Math.max(0L, nowNanos - lastCompletionNanos) / 1000000L;
               boolean currentOutputInFlight = currentFrameStillInFlight(activity, sequence);
               maxPendingAge = Math.max(maxPendingAge, pendingAge);
               if (!currentOutputInFlight) {
                  maxSnapshotLag = Math.max(maxSnapshotLag, snapshotLag);
               }

               maxCompletionStallMillis = Math.max(maxCompletionStallMillis, completionStallMillis);
               require(pendingAge <= MAX_READY_PENDING_FRAME_AGE_MILLIS, "stress scene has a stale pending RT frame after scene became current, sequence=" + sequence + ", pendingAgeMillis=" + pendingAge + ", maxAllowedMillis=" + MAX_READY_PENDING_FRAME_AGE_MILLIS + ", readiness=" + readiness.asLogFragment() + ", activity=" + activity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
               if (!currentOutputInFlight) {
                  require(completionStallMillis <= MAX_READY_COMPLETION_STALL_MILLIS, "stress scene completed-frame stream stalled after scene became current, sequence=" + sequence + ", snapshot=" + (snapshot == null ? "none" : snapshot.asLogFragment()) + ", latestCompletedFrameStateSequence=" + activity.latestCompletedFrameStateSequence() + ", completionStallMillis=" + completionStallMillis + ", maxAllowedMillis=" + MAX_READY_COMPLETION_STALL_MILLIS + ", readiness=" + readiness.asLogFragment() + ", activity=" + activity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
                  long completedLag = completedSequenceLag(sequence, activity.latestCompletedFrameStateSequence());
                  if (activity.latestCompletedFrameStateSequence() >= sequence) {
                     require(completedLag <= (long)MAX_READY_SNAPSHOT_LAG, "stress scene completed RT output is too far behind a ready scene, sequence=" + sequence + ", latestCompletedFrameStateSequence=" + activity.latestCompletedFrameStateSequence() + ", completedLag=" + completedLag + ", maxAllowedLag=" + MAX_READY_SNAPSHOT_LAG + ", snapshot=" + (snapshot == null ? "none" : snapshot.asLogFragment()) + ", readiness=" + readiness.asLogFragment() + ", activity=" + activity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
                  }

                  if (snapshot != null && snapshot.frameStateSequence() >= sequence) {
                     require(snapshotLag <= (long)MAX_READY_SNAPSHOT_LAG, "stress scene diagnostic snapshot is too far behind a ready scene, sequence=" + sequence + ", snapshotLag=" + snapshotLag + ", maxAllowedLag=" + MAX_READY_SNAPSHOT_LAG + ", snapshot=" + snapshot.asLogFragment() + ", latestCompletedFrameStateSequence=" + activity.latestCompletedFrameStateSequence() + ", readiness=" + readiness.asLogFragment() + ", activity=" + activity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
                  }
               }

               if (snapshot != null && snapshot.frameStateSequence() >= sequence && snapshotLag <= (long)MAX_READY_SNAPSHOT_LAG) {
                  RtNativeStressGuards.assertFrameNotPathological(snapshot, "native stress ready frame " + frame);
               }
            }

            condition10000 = rtCore.state() == State.READY_FOR_SCENE_UPDATES;
            stateDetails10001 = String.valueOf(rtCore.state());
            require(condition10000, "RT core failed during native stress scene: state=" + stateDetails10001 + ", readiness=" + readiness.asLogFragment() + ", activity=" + activity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
            if (EXPORT_SHARED_FRAME_ENABLED && activity.latestCompletedFrameStateSequence() > lastExportedSharedFrameSequence) {
               lastExportedSharedFrameSequence = RtNativeStressGuards.sampleCompletedSharedFrame(rtCore, true, activity.latestCompletedFrameStateSequence(), lastExportedSharedFrameSequence, SHARED_FRAME_EXPORT_SAMPLE_DELTA, false, "native stress frame " + frame);
            }

            Thread.sleep(PUMP_SLEEP_MILLIS);
         }

         DrainResult drain = pumpUntilSceneDrained(rtCore, 20000L, MAX_POST_MUTATION_DRAIN_FRAMES, rtCore.sceneReadiness().latestRevision(), lastCompletedSequence, lastCompletedDispatch, lastCompletionNanos, lastExportedSharedFrameSequence);
         RtFrameSnapshot lastSnapshot = drain.snapshot();
         lastExportedSharedFrameSequence = drain.lastExportedSharedFrameSequence();
         checksums.add(lastSnapshot.checksum());
         completedFrameCount += drain.completedFrames();
         maxPendingAge = Math.max(maxPendingAge, drain.maxPendingFrameAgeMillis());
         maxCompletionStallMillis = Math.max(maxCompletionStallMillis, drain.maxCompletionStallMillis());
         maxSnapshotLag = Math.max(maxSnapshotLag, drain.maxSnapshotLag());
         RtNativeStressGuards.assertFrameNotPathological(lastSnapshot, "final native stress frame");
         long elapsedNanos = Math.max(1L, System.nanoTime() - phaseStartNanos);
         double averageCompletedFps = (double)completedFrameCount * 1.0E9 / (double)elapsedNanos;
         require(averageCompletedFps >= MIN_COMPLETED_FPS, "native stress scene completed frames below configured fps floor, averageCompletedFps=" + averageCompletedFps + ", minCompletedFps=" + MIN_COMPLETED_FPS + ", completedFrames=" + completedFrameCount + ", elapsedMillis=" + elapsedNanos / 1000000L + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", activity=" + rtCore.runtimeActivity().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());

         for(int evidenceCycle = 0; checksums.size() < MIN_DISTINCT_CHECKSUMS && evidenceCycle < MAX_VISUAL_EVIDENCE_MUTATIONS; ++evidenceCycle) {
            long evidenceSequence = 100000L + (long)evidenceCycle * ((long)MAX_POST_MUTATION_DRAIN_FRAMES + 1L);
            rtCore.acceptFrameUpdate(scene.replacePreparedMeshes(mutationMeshes(keys, dynamicBursts + 1), frameState(evidenceSequence)));
            ++dynamicBursts;
            long evidenceRevision = rtCore.sceneReadiness().latestRevision();
            require(evidenceRevision >= 0L, "visual evidence mutation did not publish a scene revision, evidenceCycle=" + evidenceCycle + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
            DrainResult evidence = pumpUntilSceneDrained(rtCore, evidenceSequence + 1L, MAX_POST_MUTATION_DRAIN_FRAMES, evidenceRevision, rtCore.runtimeActivity().latestCompletedFrameStateSequence(), rtCore.runtimeActivity().latestCompletedFrameDispatch(), System.nanoTime(), lastExportedSharedFrameSequence);
            lastSnapshot = evidence.snapshot();
            lastExportedSharedFrameSequence = evidence.lastExportedSharedFrameSequence();
            checksums.add(lastSnapshot.checksum());
            maxPendingAge = Math.max(maxPendingAge, evidence.maxPendingFrameAgeMillis());
            maxCompletionStallMillis = Math.max(maxCompletionStallMillis, evidence.maxCompletionStallMillis());
            maxSnapshotLag = Math.max(maxSnapshotLag, evidence.maxSnapshotLag());
         }

         RtCore.RuntimeActivity finalActivity = rtCore.runtimeActivity();
         lastExportedSharedFrameSequence = RtNativeStressGuards.sampleCompletedSharedFrame(rtCore, EXPORT_SHARED_FRAME_ENABLED, finalActivity.latestCompletedFrameStateSequence(), lastExportedSharedFrameSequence, SHARED_FRAME_EXPORT_SAMPLE_DELTA, true, "native stress final frame");
         condition10000 = checksums.size() >= MIN_DISTINCT_CHECKSUMS;
         int size67 = checksums.size();
         require(condition10000, "dynamic stress scene did not visibly change across replacements, distinctChecksums=" + size67 + ", required=" + MIN_DISTINCT_CHECKSUMS + ", lastSnapshot=" + (lastSnapshot == null ? "none" : lastSnapshot.asLogFragment()) + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", activity=" + finalActivity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
         RtNativeStressGuards.assertSharedFrameReachedCompletedFrame(EXPORT_SHARED_FRAME_ENABLED, finalActivity.latestCompletedFrameStateSequence(), lastExportedSharedFrameSequence, "native stress scene");
         RtNativeStressGuards.assertCommandAndFencePoolReused(rtCore, "native stress scene");
         require(lastSnapshot != null, "stress scene did not produce any snapshot");
         stressResult60 = new StressResult(lastSnapshot, rtCore.sceneReadiness(), finalActivity, dynamicBursts, checksums.size(), completedFrameCount, averageCompletedFps, maxPendingAge, maxCompletionStallMillis, maxSnapshotLag);
      } catch (Throwable value47) {
         if (rtCore != null) {
            try {
               rtCore.close();
            } catch (Throwable value46) {
               value47.addSuppressed(value46);
            }
         }

         throw value47;
      }

      if (rtCore != null) {
         rtCore.close();
      }

      return stressResult60;
   }

   private static DrainResult pumpUntilSceneDrained(GuardedRtCore rtCore, long firstSequence, int maxPumpFrames, long requiredRevision, long initialCompletedSequence, long initialCompletedDispatch, long initialCompletionNanos, long initialExportedSharedFrameSequence) throws InterruptedException {
      long lastCompletedDispatch = initialCompletedDispatch;
      long lastCompletionNanos = initialCompletionNanos;
      long lastExportedSharedFrameSequence = initialExportedSharedFrameSequence;
      long completedFrames = 0L;
      long maxPendingAge = 0L;
      long maxCompletionStallMillis = 0L;
      long maxSnapshotLag = 0L;
      long firstReadySequence = -1L;
      RtFrameSnapshot lastSnapshot = rtCore.latestFrameSnapshot();

      for(int frame = 0; frame < maxPumpFrames; ++frame) {
         long sequence = firstSequence + (long)frame;
         rtCore.acceptFrameUpdate(RendererFrameUpdate.empty(emptyBatch(), frameState(sequence)));
         RtCore.RuntimeActivity activity = rtCore.runtimeActivity();
         RtSceneReadiness readiness = rtCore.sceneReadiness();
         long nowNanos = System.nanoTime();
         if (activity.latestCompletedFrameDispatch() > lastCompletedDispatch) {
            completedFrames += activity.latestCompletedFrameDispatch() - lastCompletedDispatch;
            lastCompletedDispatch = activity.latestCompletedFrameDispatch();
            long lastCompletedSequence = activity.latestCompletedFrameStateSequence();
            lastCompletionNanos = nowNanos;
         }

         if (EXPORT_SHARED_FRAME_ENABLED && activity.latestCompletedFrameStateSequence() > lastExportedSharedFrameSequence) {
            lastExportedSharedFrameSequence = RtNativeStressGuards.sampleCompletedSharedFrame(rtCore, true, activity.latestCompletedFrameStateSequence(), lastExportedSharedFrameSequence, SHARED_FRAME_EXPORT_SAMPLE_DELTA, false, "native stress drain frame " + frame);
         }

         RtFrameSnapshot snapshot = rtCore.latestFrameSnapshot();
         if (snapshot != null) {
            lastSnapshot = snapshot;
         }

         if (firstReadySequence < 0L && readiness.builtRevisionIsCurrent() && readiness.builtRevision() >= requiredRevision && !readiness.hasPendingRtBuilds()) {
            firstReadySequence = sequence;
         }

         long pendingAge = activity.pendingFrameAgeMillis();
         long snapshotLag = snapshot == null ? 9223372036854775807L : Math.max(0L, sequence - snapshot.frameStateSequence());
         long completionStallMillis = Math.max(0L, nowNanos - lastCompletionNanos) / 1000000L;
         boolean currentOutputInFlight = currentFrameStillInFlight(activity, sequence);
         maxPendingAge = Math.max(maxPendingAge, pendingAge);
         if (!currentOutputInFlight) {
            maxSnapshotLag = Math.max(maxSnapshotLag, snapshotLag);
         }

         maxCompletionStallMillis = Math.max(maxCompletionStallMillis, completionStallMillis);
         require(pendingAge <= MAX_READY_PENDING_FRAME_AGE_MILLIS, "post-mutation drain has a stale pending RT frame, sequence=" + sequence + ", pendingAgeMillis=" + pendingAge + ", maxAllowedMillis=" + MAX_READY_PENDING_FRAME_AGE_MILLIS + ", readiness=" + readiness.asLogFragment() + ", activity=" + activity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
         if (!currentOutputInFlight) {
            require(completionStallMillis <= MAX_READY_COMPLETION_STALL_MILLIS, "post-mutation drain completed-frame stream stalled, sequence=" + sequence + ", latestCompletedFrameStateSequence=" + activity.latestCompletedFrameStateSequence() + ", completionStallMillis=" + completionStallMillis + ", maxAllowedMillis=" + MAX_READY_COMPLETION_STALL_MILLIS + ", readiness=" + readiness.asLogFragment() + ", activity=" + activity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
         }

         if (readiness.builtRevisionIsCurrent() && !readiness.hasPendingRtBuilds() && !currentOutputInFlight) {
            long completedLag = completedSequenceLag(sequence, activity.latestCompletedFrameStateSequence());
            if (activity.latestCompletedFrameStateSequence() >= firstSequence) {
               require(completedLag <= (long)MAX_READY_SNAPSHOT_LAG, "post-mutation drain completed RT output is too far behind a ready scene, sequence=" + sequence + ", latestCompletedFrameStateSequence=" + activity.latestCompletedFrameStateSequence() + ", completedLag=" + completedLag + ", maxAllowedLag=" + MAX_READY_SNAPSHOT_LAG + ", snapshot=" + (snapshot == null ? "none" : snapshot.asLogFragment()) + ", readiness=" + readiness.asLogFragment() + ", activity=" + activity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
            }

            if (snapshot != null && snapshot.frameStateSequence() >= firstSequence) {
               require(snapshotLag <= (long)MAX_READY_SNAPSHOT_LAG, "post-mutation drain diagnostic snapshot is too far behind a ready scene, sequence=" + sequence + ", snapshotLag=" + snapshotLag + ", maxAllowedLag=" + MAX_READY_SNAPSHOT_LAG + ", snapshot=" + snapshot.asLogFragment() + ", latestCompletedFrameStateSequence=" + activity.latestCompletedFrameStateSequence() + ", readiness=" + readiness.asLogFragment() + ", activity=" + activity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
            }
         }

         if (lastSnapshot != null && firstReadySequence >= 0L && lastSnapshot.frameStateSequence() >= firstReadySequence && lastSnapshot.frameStateSequence() >= sequence - (long)MAX_READY_SNAPSHOT_LAG) {
            RtNativeStressGuards.assertFrameNotPathological(lastSnapshot, "native stress drained frame");
            return new DrainResult(lastSnapshot, completedFrames, maxPendingAge, maxCompletionStallMillis, maxSnapshotLag, lastExportedSharedFrameSequence);
         }

         boolean condition10000 = rtCore.state() == State.READY_FOR_SCENE_UPDATES;
         String stateDetails10001 = String.valueOf(rtCore.state());
         require(condition10000, "RT core failed during post-mutation drain: state=" + stateDetails10001 + ", readiness=" + readiness.asLogFragment() + ", activity=" + activity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
         Thread.sleep(PUMP_SLEEP_MILLIS);
      }

      throw new AssertionError("post-mutation stress scene never drained to current RT output, firstReadySequence=" + firstReadySequence + ", requiredRevision=" + requiredRevision + ", lastSnapshot=" + (lastSnapshot == null ? "none" : lastSnapshot.asLogFragment()) + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", activity=" + rtCore.runtimeActivity().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
   }

   private static RtFrameSnapshot pumpUntilSceneReady(GuardedRtCore rtCore, long firstSequence, int maxPumpFrames) throws InterruptedException {
      RtFrameSnapshot lastSnapshot = rtCore.latestFrameSnapshot();
      long firstReadySequence = -1L;

      for(int frame = 0; frame < maxPumpFrames; ++frame) {
         long sequence = firstSequence + (long)frame;
         rtCore.acceptFrameUpdate(RendererFrameUpdate.empty(emptyBatch(), frameState(sequence)));
         RtSceneReadiness readiness = rtCore.sceneReadiness();
         RtCore.RuntimeActivity activity = rtCore.runtimeActivity();
         if (firstReadySequence < 0L && readiness.builtRevisionIsCurrent() && !readiness.hasPendingRtBuilds()) {
            firstReadySequence = sequence;
         }

         lastSnapshot = rtCore.latestFrameSnapshot();
         if (lastSnapshot != null && firstReadySequence >= 0L && lastSnapshot.frameStateSequence() >= firstReadySequence) {
            return lastSnapshot;
         }

         boolean condition10000 = rtCore.state() == State.READY_FOR_SCENE_UPDATES;
         String stateDetails10001 = String.valueOf(rtCore.state());
         require(condition10000, "RT core failed while waiting for stress scene readiness: state=" + stateDetails10001 + ", readiness=" + readiness.asLogFragment() + ", activity=" + activity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
         Thread.sleep(PUMP_SLEEP_MILLIS);
      }

      throw new AssertionError("native stress scene never became current, firstReadySequence=" + firstReadySequence + ", lastSnapshot=" + (lastSnapshot == null ? "none" : lastSnapshot.asLogFragment()) + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", activity=" + rtCore.runtimeActivity().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
   }

   private static List<SectionKey> buildSectionKeys() {
      List<SectionKey> keys = new ArrayList<>(TOTAL_SECTIONS);

      for(int y = 0; y < SECTION_ROWS; ++y) {
         for(int x = 0; x < SECTION_COLUMNS; ++x) {
            keys.add(new SectionKey(x, y, 0));
         }
      }

      return List.copyOf(keys);
   }

   private static Map<SectionKey, SectionTriangleMesh> buildMeshes(List<SectionKey> keys, int variant) {
      Map<SectionKey, SectionTriangleMesh> meshes = new LinkedHashMap<>();

      for(int index = 0; index < keys.size(); ++index) {
         SectionKey key = (SectionKey)keys.get(index);
         meshes.put(key, stressSectionMesh(key, variant + index));
      }

      return meshes;
   }

   private static Map<SectionKey, SectionTriangleMesh> mutationMeshes(List<SectionKey> keys, int burst) {
      Map<SectionKey, SectionTriangleMesh> meshes = new LinkedHashMap<>();
      int offset = Math.floorMod(burst * 37, keys.size());

      for(int index = 0; index < Math.min(MUTATIONS_PER_BURST, keys.size()); ++index) {
         SectionKey key = (SectionKey)keys.get((offset + index * 11) % keys.size());
         meshes.put(key, stressSectionMesh(key, burst * 4099 + index));
      }

      return meshes;
   }

   private static SectionTriangleMesh stressSectionMesh(SectionKey key, int variant) {
      MeshBuilder builder = new MeshBuilder(key);
      int baseColor = colorVariant(variant, 3235624, 9420626);
      int accentColor = colorVariant(variant * 17 + 3, 2845631, 14143835);

      for(int row = 0; row < 4; ++row) {
         float y0 = (float)row * 4.0F;
         float y1 = y0 + 4.0F;
         builder.addPositiveZQuad(0.0F, y0, 16.0F, y1, 15.92F, baseColor, false);
         builder.addPositiveZQuad(4.0F, y0 + 0.35F, 12.0F, y1 - 0.35F, 15.98F, accentColor, ALPHA_CUTOUT_ENABLED);
         builder.addPositiveZQuad(12.0F, y0 + 0.65F, 16.0F, y1 - 0.65F, 15.99F, accentColor, ALPHA_CUTOUT_ENABLED);
      }

      return builder.build();
   }

   private static int colorVariant(int variant, int firstRgb, int secondRgb) {
      int mixed = variant * 1103515245 + 12345;
      int weight = mixed >>> 24 & 255;
      int red = blend(firstRgb >>> 16 & 255, secondRgb >>> 16 & 255, weight);
      int green = blend(firstRgb >>> 8 & 255, secondRgb >>> 8 & 255, weight);
      int blue = blend(firstRgb & 255, secondRgb & 255, weight);
      return red << 16 | green << 8 | blue;
   }

   private static int blend(int first, int second, int weight) {
      return (first * (255 - weight) + second * weight) / 255;
   }

   private static RendererFrameState frameState(long sequence) {
      double centerX = (double)SECTION_COLUMNS * 8.0;
      double centerY = (double)SECTION_ROWS * 8.0;
      double cameraZ = 640.0;
      return new RendererFrameState(sequence, true, OUTPUT_WIDTH, OUTPUT_HEIGHT, centerX, centerY, cameraZ, 0.0F, 0.0F, 0.0F, 0.0F, -1.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 1.7320508F, 1.7320508F, 1.0F, 0.0F, -1.0F, 0.0F, false, true);
   }

   private static SectionVoxelSnapshot filledSection(SectionKey key, int voxelTypeId) {
      int[] ids = new int[4096];
      byte[] fluids = new byte[4096];
      Arrays.fill(ids, voxelTypeId);
      return new SectionVoxelSnapshot(key, ids, fluids, false, false);
   }

   private static SceneUpdateBatch emptyBatch() {
      return new SceneUpdateBatch(Set.of(), Set.of(), Set.of(), Set.of(), Map.of(), false, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
   }

   private static Map<String, String> installStressProperties() {
      Map<String, String> previous = new LinkedHashMap<>();
      int sectionCapacity = Math.max(4096, TOTAL_SECTIONS * 2);
      long byteCapacity = Math.max(1610612736L, (long)TOTAL_SECTIONS * 512L * 1024L);
      set(previous, "top.ceroxe.rt.rt.output.readback.enabled", "true");
      set(previous, "top.ceroxe.rt.rt.output.readback.interval", Integer.toString(READBACK_SAMPLE_INTERVAL));
      set(previous, "top.ceroxe.rt.rt.output.dispatchInterval", "1");
      set(previous, "top.ceroxe.rt.rt.output.externalSemaphore.enabled", "false");
      set(previous, "top.ceroxe.rt.rt.output.width", Integer.toString(OUTPUT_WIDTH));
      set(previous, "top.ceroxe.rt.rt.output.height", Integer.toString(OUTPUT_HEIGHT));
      set(previous, "top.ceroxe.rt.rt.output.maxPixels", Integer.toString(OUTPUT_PIXELS));
      set(previous, "top.ceroxe.rt.rt.worldTlas.minInitialInstances", "1");
      set(previous, "top.ceroxe.rt.rt.worldTlas.minRebuildIntervalMillis", "0");
      set(previous, "top.ceroxe.rt.rt.worldTlas.minStreamingRebuildIntervalMillis", "0");
      set(previous, "top.ceroxe.rt.rt.worldTlas.minStreamingRevisionDelta", "1");
      set(previous, "top.ceroxe.rt.rt.worldTlas.minStreamingInstanceDelta", "1");
      set(previous, "top.ceroxe.rt.rt.worldTlas.allowBackloggedStreamingRebuilds", "true");
      set(previous, "top.ceroxe.rt.rt.sectionBlas.maxBuildsPerFrame", "128");
      set(previous, "top.ceroxe.rt.rt.sectionBlas.maxTrianglesPerFrame", "4000000");
      set(previous, "top.ceroxe.rt.rt.sectionBlas.maxAsyncBuildsInFlight", "16");
      set(previous, "top.ceroxe.rt.rt.sectionBlas.maxAsyncBuildSectionsInFlight", "768");
      set(previous, "top.ceroxe.rt.rt.sectionBlas.maxAsyncBuildBytesInFlight", Long.toString(byteCapacity));
      set(previous, "top.ceroxe.rt.rt.sectionBlas.maxPendingSections", Integer.toString(sectionCapacity));
      set(previous, "top.ceroxe.rt.rt.sectionBlas.maxPendingBytes", Long.toString(byteCapacity));
      set(previous, "top.ceroxe.rt.rt.sectionBlas.maxCachedSections", Integer.toString(sectionCapacity));
      set(previous, "top.ceroxe.rt.rt.sectionBlas.maxCachedBytes", Long.toString(byteCapacity));
      return previous;
   }

   private static void set(Map<String, String> previous, String name, String value) {
      previous.put(name, System.getProperty(name));
      System.setProperty(name, value);
   }

   private static void restoreProperties(Map<String, String> previousProperties) {
      for(Map.Entry<String, String> entry : previousProperties.entrySet()) {
         if (entry.getValue() == null) {
            System.clearProperty((String)entry.getKey());
         } else {
            System.setProperty((String)entry.getKey(), (String)entry.getValue());
         }
      }

   }

   private static int intProperty(String name, int defaultValue) {
      String raw = System.getProperty(name);
      if (raw != null && !raw.isBlank()) {
         try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed <= 0) {
               throw invalidProperty(name, raw, "a positive integer", (Throwable)null);
            } else {
               return parsed;
            }
         } catch (NumberFormatException ex) {
            throw invalidProperty(name, raw, "a positive integer", ex);
         }
      } else {
         return defaultValue;
      }
   }

   private static long longProperty(String name, long defaultValue) {
      String raw = System.getProperty(name);
      if (raw != null && !raw.isBlank()) {
         try {
            long parsed = Long.parseLong(raw.trim());
            if (parsed < 0L) {
               throw invalidProperty(name, raw, "a non-negative integer", (Throwable)null);
            } else {
               return parsed;
            }
         } catch (NumberFormatException ex) {
            throw invalidProperty(name, raw, "a non-negative integer", ex);
         }
      } else {
         return defaultValue;
      }
   }

   private static double doubleProperty(String name, double defaultValue) {
      String raw = System.getProperty(name);
      if (raw != null && !raw.isBlank()) {
         try {
            double parsed = Double.parseDouble(raw.trim());
            if (Double.isFinite(parsed) && !(parsed <= 0.0)) {
               return parsed;
            } else {
               throw invalidProperty(name, raw, "a positive finite number", (Throwable)null);
            }
         } catch (NumberFormatException ex) {
            throw invalidProperty(name, raw, "a positive finite number", ex);
         }
      } else {
         return defaultValue;
      }
   }

   private static boolean booleanProperty(String name, boolean defaultValue) {
      String raw = System.getProperty(name);
      if (raw != null && !raw.isBlank()) {
         String normalized = raw.trim();
         if ("true".equalsIgnoreCase(normalized)) {
            return true;
         } else if ("false".equalsIgnoreCase(normalized)) {
            return false;
         } else {
            throw invalidProperty(name, raw, "true or false", (Throwable)null);
         }
      } else {
         return defaultValue;
      }
   }

   private static IllegalArgumentException invalidProperty(String name, String value, String expected, Throwable cause) {
      String message = "system property " + name + " must be " + expected + "; value=" + value;
      return cause == null ? new IllegalArgumentException(message) : new IllegalArgumentException(message, cause);
   }

   private static void writeSnapshotPng(RtFrameSnapshot snapshot, Path path) throws IOException {
      byte[] rgba = snapshot.copyRgba8();
      BufferedImage image = new BufferedImage(snapshot.width(), snapshot.height(), 2);

      for(int y = 0; y < snapshot.height(); ++y) {
         for(int x = 0; x < snapshot.width(); ++x) {
            int rgba8 = RtFrameSnapshot.pixel(rgba, snapshot.width(), x, y);
            int argb = (rgba8 >>> 24 & 255) << 24 | (rgba8 & 255) << 16 | (rgba8 >>> 8 & 255) << 8 | rgba8 >>> 16 & 255;
            image.setRGB(x, y, argb);
         }
      }

      ImageIO.write(image, "png", path.toFile());
   }

   private static String foregroundSample(RtFrameSnapshot snapshot, int maxPixels) {
      byte[] rgba = snapshot.copyRgba8();
      int background = RtSceneMaterialTable.missRgba8();
      StringBuilder sample = new StringBuilder("[");
      int emitted = 0;

      for(int y = 0; y < snapshot.height(); ++y) {
         for(int x = 0; x < snapshot.width(); ++x) {
            int pixel = RtFrameSnapshot.pixel(rgba, snapshot.width(), x, y);
            if (pixel != background) {
               if (emitted > 0) {
                  sample.append(", ");
               }

               sample.append("(").append(x).append(",").append(y).append("=").append(RtFrameSnapshot.hex(pixel)).append(")");
               ++emitted;
               if (emitted >= maxPixels) {
                  sample.append(", ...");
                  return sample.append("]").toString();
               }
            }
         }
      }

      return sample.append("]").toString();
   }

   private static short fixed(float blockUnits) {
      return (short)Math.round(blockUnits * 1024.0F);
   }

   private static long completedSequenceLag(long sequence, long latestCompletedSequence) {
      return latestCompletedSequence < 0L ? 9223372036854775807L : Math.max(0L, sequence - latestCompletedSequence);
   }

   private static boolean currentFrameStillInFlight(RtCore.RuntimeActivity activity, long sequence) {
      return activity.pendingFrame() && activity.pendingFrameSequence() >= sequence && activity.pendingFrameAgeMillis() <= MAX_READY_PENDING_FRAME_AGE_MILLIS;
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }

   static {
      TOTAL_SECTIONS = Math.multiplyExact(SECTION_COLUMNS, SECTION_ROWS);
      MAX_INITIAL_READY_PUMP_FRAMES = intProperty("top.ceroxe.rt.rt.stress.maxInitialReadyPumpFrames", 3000);
      SUSTAINED_FRAMES = intProperty("top.ceroxe.rt.rt.stress.sustainedFrames", 420);
      MAX_POST_MUTATION_DRAIN_FRAMES = intProperty("top.ceroxe.rt.rt.stress.maxPostMutationDrainFrames", 2000);
      MUTATION_PERIOD_FRAMES = intProperty("top.ceroxe.rt.rt.stress.mutationPeriodFrames", 12);
      MUTATIONS_PER_BURST = intProperty("top.ceroxe.rt.rt.stress.mutationsPerBurst", 24);
      MIN_DISTINCT_CHECKSUMS = intProperty("top.ceroxe.rt.rt.stress.minDistinctChecksums", 4);
      MAX_VISUAL_EVIDENCE_MUTATIONS = intProperty("top.ceroxe.rt.rt.stress.maxVisualEvidenceMutations", 8);
      MAX_READY_SNAPSHOT_LAG = intProperty("top.ceroxe.rt.rt.stress.maxReadySnapshotLag", 180);
      READBACK_SAMPLE_INTERVAL = intProperty("top.ceroxe.rt.rt.stress.readbackSampleInterval", 8);
      MAX_READY_PENDING_FRAME_AGE_MILLIS = longProperty("top.ceroxe.rt.rt.stress.maxReadyPendingFrameAgeMillis", 1500L);
      MAX_READY_COMPLETION_STALL_MILLIS = longProperty("top.ceroxe.rt.rt.stress.maxReadyCompletionStallMillis", 1500L);
      PUMP_SLEEP_MILLIS = longProperty("top.ceroxe.rt.rt.stress.pumpSleepMillis", 8L);
      MIN_COMPLETED_FPS = doubleProperty("top.ceroxe.rt.rt.stress.minCompletedFps", 1.5);
      ALPHA_CUTOUT_ENABLED = booleanProperty("top.ceroxe.rt.rt.stress.alphaCutout.enabled", true);
      EXPORT_SHARED_FRAME_ENABLED = booleanProperty("top.ceroxe.rt.rt.stress.exportSharedFrame.enabled", true);
      SHARED_FRAME_EXPORT_SAMPLE_DELTA = intProperty("top.ceroxe.rt.rt.stress.sharedFrameExportSampleDelta", 30);
      OUTPUT_PIXELS = Math.multiplyExact(OUTPUT_WIDTH, OUTPUT_HEIGHT);
      MIN_FOREGROUND_PIXELS = OUTPUT_PIXELS / 32;
      SNAPSHOT_PATH = Path.of(System.getProperty("java.io.tmpdir"), "rtrenderer-native-stress-scene.png");
   }

   private static record StressResult(RtFrameSnapshot lastSnapshot, RtSceneReadiness readiness, RtCore.RuntimeActivity activity, int dynamicBursts, int distinctChecksums, long completedFrames, double averageCompletedFps, long maxReadyPendingFrameAgeMillis, long maxReadyCompletionStallMillis, long maxReadySnapshotLag) {
   }

   private static record DrainResult(RtFrameSnapshot snapshot, long completedFrames, long maxPendingFrameAgeMillis, long maxCompletionStallMillis, long maxSnapshotLag, long lastExportedSharedFrameSequence) {
   }

   private static final class StressSceneState {
      private final SceneDatabase database = new SceneDatabase();
      private final SectionMaterialCache materialCache = new SectionMaterialCache();
      private final SectionGeometryCache geometryCache = new SectionGeometryCache();
      private final SectionMeshCache meshCache = new SectionMeshCache();

      private static SceneUpdateBatch preparedMeshBatch(Map<SectionKey, SectionTriangleMesh> meshes) {
         Set<SectionKey> dirtySections = Set.copyOf(meshes.keySet());
         Set<ChunkKey> dirtyChunks = new LinkedHashSet<>();
         Map<SectionKey, SectionVoxelSnapshot> snapshots = new LinkedHashMap<>();

         for(SectionKey key : dirtySections) {
            dirtyChunks.add(key.chunkKey());
            snapshots.put(key, RtNativeStressSceneSelfTest.filledSection(key, 1));
         }

         return new SceneUpdateBatch(dirtySections, dirtyChunks, Set.of(), Set.of(), snapshots, false, (long)dirtySections.size(), (long)dirtySections.size(), 0L, 0L, 0L, 0L, 0L, SceneUpdateBatch.sourceFlagsForBlockMutation());
      }

      private RendererFrameUpdate initialUpdate(Map<SectionKey, SectionTriangleMesh> meshes, RendererFrameState frameState) {
         Map<ChunkKey, List<SectionVoxelSnapshot>> sectionsByChunk = new LinkedHashMap<>();

         for(SectionKey key : meshes.keySet()) {
            sectionsByChunk.computeIfAbsent(key.chunkKey(), ignored -> new ArrayList<>()).add(RtNativeStressSceneSelfTest.filledSection(key, 1));
         }

         for(Map.Entry<ChunkKey, List<SectionVoxelSnapshot>> entry : sectionsByChunk.entrySet()) {
            int minY = entry.getValue().stream().mapToInt(section -> section.key().y()).min().orElse(0);
            this.database.replaceChunkSnapshot(new ChunkSnapshot(entry.getKey(), minY, entry.getValue()));
         }

         return this.applyPreparedMeshes(meshes, frameState);
      }

      private RendererFrameUpdate replacePreparedMeshes(Map<SectionKey, SectionTriangleMesh> meshes, RendererFrameState frameState) {
         for(SectionKey key : meshes.keySet()) {
            this.database.replaceBlockMutationSectionSnapshot(RtNativeStressSceneSelfTest.filledSection(key, 1));
         }

         return this.applyPreparedMeshes(meshes, frameState);
      }

      private RendererFrameUpdate applyPreparedMeshes(Map<SectionKey, SectionTriangleMesh> meshes, RendererFrameState frameState) {
         SceneUpdateBatch batch = this.database.drainPendingUpdates();
         if (!batch.hasChanges() && !meshes.isEmpty()) {
            batch = preparedMeshBatch(meshes);
         }

         SectionMaterialCache.ApplyResult material = this.materialCache.apply(batch);
         SectionGeometryCache.ApplyResult geometry = this.geometryCache.apply(material.encodedSections(), batch.removedSections(), batch.fullResyncRequested());
         SectionMeshCache.ApplyResult meshResult = this.meshCache.applyPrepared(meshes, batch.removedSections(), batch.fullResyncRequested());
         RtNativeStressSceneSelfTest.require(meshResult.trianglesInBatch() > 0, "stress scene update must submit visible section triangles");
         return new RendererFrameUpdate(batch, material, geometry, meshResult, frameState);
      }
   }

   private static final class MeshBuilder {
      private static final int VERTICES_PER_FACE = 4;
      private static final int INDICES_PER_FACE = 6;
      private final SectionKey key;
      private final List<Short> positions = new ArrayList<>();
      private final List<Integer> indices = new ArrayList<>();
      private final List<Integer> voxelTypeIds = new ArrayList<>();
      private final List<Byte> mediumAmounts = new ArrayList<>();
      private final List<Byte> directions = new ArrayList<>();
      private final List<Integer> mapColors = new ArrayList<>();
      private final List<Byte> lightEmissions = new ArrayList<>();
      private final List<Byte> materialFlags = new ArrayList<>();
      private final List<Integer> textureIds = new ArrayList<>();
      private final List<Integer> uv0 = new ArrayList<>();
      private final List<Integer> uv1 = new ArrayList<>();
      private final List<Integer> uv2 = new ArrayList<>();
      private final List<Integer> uv3 = new ArrayList<>();
      private final List<Byte> tintFlags = new ArrayList<>();
      private final List<Byte> alphaCutoutFlags = new ArrayList<>();

      private MeshBuilder(SectionKey key) {
         this.key = key;
      }

      private static short[] shorts(List<Short> values) {
         short[] array = new short[values.size()];

         for(int index = 0; index < values.size(); ++index) {
            array[index] = (Short)values.get(index);
         }

         return array;
      }

      private static int[] ints(List<Integer> values) {
         int[] array = new int[values.size()];

         for(int index = 0; index < values.size(); ++index) {
            array[index] = (Integer)values.get(index);
         }

         return array;
      }

      private static byte[] bytes(List<Byte> values) {
         byte[] array = new byte[values.size()];

         for(int index = 0; index < values.size(); ++index) {
            array[index] = (Byte)values.get(index);
         }

         return array;
      }

      private void addPositiveZQuad(float x0, float y0, float x1, float y1, float z, int mapColor, boolean alphaCutout) {
         int firstVertex = this.positions.size() / 3;
         this.addVertex(x0, y0, z);
         this.addVertex(x1, y0, z);
         this.addVertex(x1, y1, z);
         this.addVertex(x0, y1, z);
         this.indices.add(firstVertex);
         this.indices.add(firstVertex + 1);
         this.indices.add(firstVertex + 2);
         this.indices.add(firstVertex);
         this.indices.add(firstVertex + 2);
         this.indices.add(firstVertex + 3);
         this.voxelTypeIds.add(1);
         this.mediumAmounts.add((byte)0);
         this.directions.add((byte)FaceDirection.POSITIVE_Z.ordinal());
         this.mapColors.add(mapColor);
         this.lightEmissions.add((byte)0);
         this.materialFlags.add((byte)1);
         this.textureIds.add(0);
         this.uv0.add(RtTextureCatalog.packUv16(0.0F, 0.0F));
         this.uv1.add(RtTextureCatalog.packUv16(1.0F, 0.0F));
         this.uv2.add(RtTextureCatalog.packUv16(1.0F, 1.0F));
         this.uv3.add(RtTextureCatalog.packUv16(0.0F, 1.0F));
         this.tintFlags.add((byte)1);
         this.alphaCutoutFlags.add((byte)(alphaCutout ? 1 : 0));
      }

      private void addVertex(float x, float y, float z) {
         this.positions.add(RtNativeStressSceneSelfTest.fixed(x));
         this.positions.add(RtNativeStressSceneSelfTest.fixed(y));
         this.positions.add(RtNativeStressSceneSelfTest.fixed(z));
      }

      private SectionTriangleMesh build() {
         int faceCount = this.voxelTypeIds.size();
         RtNativeStressSceneSelfTest.require(this.positions.size() == faceCount * 4 * 3, "stress mesh vertex count mismatch");
         RtNativeStressSceneSelfTest.require(this.indices.size() == faceCount * 6, "stress mesh index count mismatch");
         return new SectionTriangleMesh(this.key, shorts(this.positions), ints(this.indices), ints(this.voxelTypeIds), bytes(this.mediumAmounts), bytes(this.directions), ints(this.mapColors), bytes(this.lightEmissions), bytes(this.materialFlags), ints(this.textureIds), ints(this.uv0), ints(this.uv1), ints(this.uv2), ints(this.uv3), bytes(this.tintFlags), bytes(this.alphaCutoutFlags));
      }
   }
}
