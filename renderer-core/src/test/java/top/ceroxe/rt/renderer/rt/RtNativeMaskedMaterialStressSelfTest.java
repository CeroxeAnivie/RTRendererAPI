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
import java.util.function.IntPredicate;
import javax.imageio.ImageIO;
import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.CameraRayMath;
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
import top.ceroxe.rt.renderer.scene.SectionMaterialCache.MaterialFacts;

public final class RtNativeMaskedMaterialStressSelfTest {
   private static final int OUTPUT_WIDTH = intProperty("top.ceroxe.rt.rt.maskedStress.width", 960);
   private static final int OUTPUT_HEIGHT = intProperty("top.ceroxe.rt.rt.maskedStress.height", 540);
   private static final int SECTION_COLUMNS = intProperty("top.ceroxe.rt.rt.maskedStress.sectionColumns", 41);
   private static final int SECTION_ROWS = intProperty("top.ceroxe.rt.rt.maskedStress.sectionRows", 21);
   private static final int TOTAL_SECTIONS;
   private static final int MAX_INITIAL_READY_PUMP_FRAMES;
   private static final int SUSTAINED_FRAMES;
   private static final int MAX_FINAL_DRAIN_FRAMES;
   private static final int MUTATION_PERIOD_FRAMES;
   private static final int MUTATIONS_PER_BURST;
   private static final int MAX_READY_SNAPSHOT_LAG;
   private static final int READBACK_SAMPLE_INTERVAL;
   private static final long MAX_READY_PENDING_FRAME_AGE_MILLIS;
   private static final long MAX_READY_COMPLETION_STALL_MILLIS;
   private static final long PUMP_SLEEP_MILLIS;
   private static final double MIN_COMPLETED_FPS;
   private static final boolean EXPORT_SHARED_FRAME_ENABLED;
   private static final int SHARED_FRAME_EXPORT_SAMPLE_DELTA;
   private static final int BLOCK_STATE_ID = 1;
   private static final float FRONT_Z = 15.95F;
   private static final float BACK_Z = 15.7F;
   private static final float CANOPY_Z = 15.99F;
   private static final String CUTOUT_A_TEXTURE = "rtrenderer:selftest/cutout_a";
   private static final String CUTOUT_B_TEXTURE = "rtrenderer:selftest/cutout_b";
   private static final String BACKPLATE_TEXTURE = "rtrenderer:selftest/backplate";
   private static final String COCOA_TEXTURE = "rtrenderer:selftest/cocoa_flat";
   private static final int BACKPLATE_RED = 68;
   private static final int BACKPLATE_GREEN = 74;
   private static final int BACKPLATE_BLUE = 84;
   private static final Path SNAPSHOT_PATH;

   private RtNativeMaskedMaterialStressSelfTest() {
   }

   public static void main(String[] args) throws Exception {
      Map<String, String> previousProperties = installStressProperties();

      try {
         RtTextureCatalog.TestTextureScope textures = RtTextureCatalog.installTestTexturesForSelfTest(testTextures());

         try {
            VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
            require(capability.hardwareRayTracingReady(), "native masked-material stress requires production RT hardware: " + capability.summary());
            StressResult result = runStressScene(capability, textures);
            writeSnapshotPng(result.lastSnapshot(), SNAPSHOT_PATH);
            int sectionCount10001 = TOTAL_SECTIONS;
            System.out.println("RtNativeMaskedMaterialStressSelfTest passed: sections=" + sectionCount10001 + ", sustainedFrames=" + SUSTAINED_FRAMES + ", dynamicBursts=" + result.dynamicBursts() + ", distinctChecksums=" + result.distinctChecksums() + ", completedFrames=" + result.completedFrames() + ", averageCompletedFps=" + result.averageCompletedFps() + ", maxReadyPendingFrameAgeMillis=" + result.maxReadyPendingFrameAgeMillis() + ", maxReadyCompletionStallMillis=" + result.maxReadyCompletionStallMillis() + ", maxReadySnapshotLag=" + result.maxReadySnapshotLag() + ", initialProbe=" + result.initialProbe().asLogFragment() + ", finalProbe=" + result.finalProbe().asLogFragment() + ", lastSnapshot=" + result.lastSnapshot().asLogFragment() + ", png=" + String.valueOf(SNAPSHOT_PATH) + ", readiness=" + result.readiness().asLogFragment() + ", activity=" + result.activity().asLogFragment());
            System.out.println(RtNativeBenchmarkReport.pacedScene("maskedMaterial", OUTPUT_WIDTH, OUTPUT_HEIGHT, result.completedFrames(), result.averageCompletedFps(), result.activity(), result.readiness()));
         } catch (Throwable value10) {
            if (textures != null) {
               try {
                  textures.close();
               } catch (Throwable value9) {
                  value10.addSuppressed(value9);
               }
            }

            throw value10;
         }

         if (textures != null) {
            textures.close();
         }
      } finally {
         restoreProperties(previousProperties);
      }

   }

   private static StressResult runStressScene(VulkanRtCapabilityProbe.Result capability, RtTextureCatalog.TestTextureScope textures) throws Exception {
      GuardedRtCore rtCore = GuardedRtCore.isolatedHardwareTest();

      StressResult stressResult65;
      try {
         rtCore.acceptCapability(capability);
         boolean condition10000 = rtCore.state() == State.READY_FOR_SCENE_UPDATES;
         String stateDetails10001 = String.valueOf(rtCore.state());
         require(condition10000, "RT core did not open native backend for masked-material stress: state=" + stateDetails10001 + ", summary=" + rtCore.summary().asLogFragment());
         MaskedSceneState scene = new MaskedSceneState();
         List<SectionKey> keys = buildSectionKeys();
         SectionKey probeKey = probeKey();
         rtCore.acceptFrameUpdate(scene.initialUpdate(buildMeshes(keys, textures, 0), frameState(1L)));
         RtFrameSnapshot initialSnapshot = pumpUntilProbeReady(rtCore, 2L, 0, MAX_INITIAL_READY_PUMP_FRAMES, "initial masked-material scene");
         ProbeSamples initialProbe = assertProbePixels(initialSnapshot, 0, "initial");
         RtNativeStressGuards.assertFrameNotPathological(initialSnapshot, "initial masked-material stress frame");
         long phaseStartNanos = System.nanoTime();
         long lastCompletedSequence = Math.max(0L, rtCore.runtimeActivity().latestCompletedFrameStateSequence());
         long lastCompletedDispatch = Math.max(0L, rtCore.runtimeActivity().latestCompletedFrameDispatch());
         long lastCompletionNanos = System.nanoTime();
         long lastExportedSharedFrameSequence = -1L;
         long completedFrameCount = 0L;
         long maxPendingAge = 0L;
         long maxCompletionStallMillis = 0L;
         long maxSnapshotLag = 0L;
         int dynamicBursts = 0;
         int expectedProbeVariant = 0;
         boolean observedProbeVariant0 = true;
         boolean observedProbeVariant1 = false;
         Set<Long> checksums = new HashSet<>();
         checksums.add(initialSnapshot.checksum());

         for(int frame = 0; frame < SUSTAINED_FRAMES; ++frame) {
            long sequence = 10000L + (long)frame;
            RendererFrameUpdate update;
            if (frame % MUTATION_PERIOD_FRAMES == 0) {
               ++dynamicBursts;
               expectedProbeVariant = dynamicBursts & 1;
               update = scene.replacePreparedMeshes(mutationMeshes(keys, probeKey, textures, dynamicBursts, expectedProbeVariant), frameState(sequence));
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
               maxPendingAge = Math.max(maxPendingAge, pendingAge);
               maxSnapshotLag = Math.max(maxSnapshotLag, snapshotLag);
               maxCompletionStallMillis = Math.max(maxCompletionStallMillis, completionStallMillis);
               require(pendingAge <= MAX_READY_PENDING_FRAME_AGE_MILLIS, "masked-material stress has a stale pending RT frame after scene became current, sequence=" + sequence + ", pendingAgeMillis=" + pendingAge + ", maxAllowedMillis=" + MAX_READY_PENDING_FRAME_AGE_MILLIS + ", readiness=" + readiness.asLogFragment() + ", activity=" + activity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
               require(completionStallMillis <= MAX_READY_COMPLETION_STALL_MILLIS, "masked-material stress completed-frame stream stalled after scene became current, sequence=" + sequence + ", snapshot=" + (snapshot == null ? "none" : snapshot.asLogFragment()) + ", latestCompletedFrameStateSequence=" + activity.latestCompletedFrameStateSequence() + ", completionStallMillis=" + completionStallMillis + ", maxAllowedMillis=" + MAX_READY_COMPLETION_STALL_MILLIS + ", readiness=" + readiness.asLogFragment() + ", activity=" + activity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
               long completedLag = completedSequenceLag(sequence, activity.latestCompletedFrameStateSequence());
               if (activity.latestCompletedFrameStateSequence() >= sequence) {
                  require(completedLag <= (long)MAX_READY_SNAPSHOT_LAG, "masked-material completed RT output is too far behind a ready scene, sequence=" + sequence + ", latestCompletedFrameStateSequence=" + activity.latestCompletedFrameStateSequence() + ", completedLag=" + completedLag + ", maxAllowedLag=" + MAX_READY_SNAPSHOT_LAG + ", snapshot=" + (snapshot == null ? "none" : snapshot.asLogFragment()) + ", readiness=" + readiness.asLogFragment() + ", activity=" + activity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
               }

               if (snapshot != null && snapshot.frameStateSequence() >= sequence) {
                  require(snapshotLag <= (long)MAX_READY_SNAPSHOT_LAG, "masked-material diagnostic snapshot is too far behind a ready scene, sequence=" + sequence + ", snapshotLag=" + snapshotLag + ", maxAllowedLag=" + MAX_READY_SNAPSHOT_LAG + ", snapshot=" + snapshot.asLogFragment() + ", latestCompletedFrameStateSequence=" + activity.latestCompletedFrameStateSequence() + ", readiness=" + readiness.asLogFragment() + ", activity=" + activity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
               }

               if (snapshot != null && snapshot.frameStateSequence() >= sequence && snapshotLag <= (long)MAX_READY_SNAPSHOT_LAG) {
                  ProbeSamples samples = assertSustainedProbePixels(snapshot, "sustained frame " + frame);
                  observedProbeVariant0 |= matchesProbeVariant(snapshot, samples, 0);
                  observedProbeVariant1 |= matchesProbeVariant(snapshot, samples, 1);
                  RtNativeStressGuards.assertFrameNotPathological(snapshot, "masked-material ready frame " + frame);
               }
            }

            condition10000 = rtCore.state() == State.READY_FOR_SCENE_UPDATES;
            stateDetails10001 = String.valueOf(rtCore.state());
            require(condition10000, "RT core failed during masked-material stress: state=" + stateDetails10001 + ", readiness=" + readiness.asLogFragment() + ", activity=" + activity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
            if (EXPORT_SHARED_FRAME_ENABLED && activity.latestCompletedFrameStateSequence() > lastExportedSharedFrameSequence) {
               lastExportedSharedFrameSequence = RtNativeStressGuards.sampleCompletedSharedFrame(rtCore, true, activity.latestCompletedFrameStateSequence(), lastExportedSharedFrameSequence, SHARED_FRAME_EXPORT_SAMPLE_DELTA, false, "masked-material stress frame " + frame);
            }

            Thread.sleep(PUMP_SLEEP_MILLIS);
         }

         int finalProbeVariant = 1;
         rtCore.acceptFrameUpdate(scene.replacePreparedMeshes(Map.of(probeKey, probeSectionMesh(probeKey, textures, finalProbeVariant)), frameState(20000L)));
         DrainResult drain = pumpUntilProbeDrained(rtCore, 20001L, finalProbeVariant, MAX_FINAL_DRAIN_FRAMES, lastCompletedSequence, lastCompletedDispatch, lastCompletionNanos, lastExportedSharedFrameSequence);
         RtFrameSnapshot lastSnapshot = drain.snapshot();
         lastExportedSharedFrameSequence = drain.lastExportedSharedFrameSequence();
         checksums.add(lastSnapshot.checksum());
         completedFrameCount += drain.completedFrames();
         maxPendingAge = Math.max(maxPendingAge, drain.maxPendingFrameAgeMillis());
         maxCompletionStallMillis = Math.max(maxCompletionStallMillis, drain.maxCompletionStallMillis());
         maxSnapshotLag = Math.max(maxSnapshotLag, drain.maxSnapshotLag());
         ProbeSamples finalProbe = assertProbePixels(lastSnapshot, finalProbeVariant, "final");
         observedProbeVariant1 |= matchesProbeVariant(lastSnapshot, finalProbe, finalProbeVariant);
         require(observedProbeVariant0 && observedProbeVariant1, "masked-material stress did not observe both dynamic cutout texture variants after the final explicit replacement, observedProbeVariant0=" + observedProbeVariant0 + ", observedProbeVariant1=" + observedProbeVariant1 + ", finalProbe=" + finalProbe.asLogFragment() + ", lastSnapshot=" + lastSnapshot.asLogFragment() + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", activity=" + rtCore.runtimeActivity().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
         RtNativeStressGuards.assertFrameNotPathological(lastSnapshot, "final masked-material stress frame");
         long elapsedNanos = Math.max(1L, System.nanoTime() - phaseStartNanos);
         RtCore.RuntimeActivity finalActivity = rtCore.runtimeActivity();
         lastExportedSharedFrameSequence = RtNativeStressGuards.sampleCompletedSharedFrame(rtCore, EXPORT_SHARED_FRAME_ENABLED, finalActivity.latestCompletedFrameStateSequence(), lastExportedSharedFrameSequence, SHARED_FRAME_EXPORT_SAMPLE_DELTA, true, "masked-material final frame");
         double averageCompletedFps = (double)completedFrameCount * 1.0E9 / (double)elapsedNanos;
         require(averageCompletedFps >= MIN_COMPLETED_FPS, "masked-material stress completed frames below fps floor, averageCompletedFps=" + averageCompletedFps + ", minCompletedFps=" + MIN_COMPLETED_FPS + ", completedFrames=" + completedFrameCount + ", elapsedMillis=" + elapsedNanos / 1000000L + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", activity=" + finalActivity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
         RtNativeStressGuards.assertSharedFrameReachedCompletedFrame(EXPORT_SHARED_FRAME_ENABLED, finalActivity.latestCompletedFrameStateSequence(), lastExportedSharedFrameSequence, "masked-material stress scene");
         RtNativeStressGuards.assertCommandAndFencePoolReused(rtCore, "masked-material stress scene");
         condition10000 = checksums.size() >= 4;
         int size70 = checksums.size();
         require(condition10000, "masked-material dynamic scene did not visibly change across replacements, distinctChecksums=" + size70 + ", lastSnapshot=" + lastSnapshot.asLogFragment() + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", activity=" + finalActivity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
         condition10000 = initialProbe.primaryColor() != finalProbe.primaryColor();
         String logDetails71 = initialProbe.asLogFragment();
         require(condition10000, "probe texture replacement did not reach visible RT output, initialProbe=" + logDetails71 + ", finalProbe=" + finalProbe.asLogFragment() + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", activity=" + finalActivity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
         stressResult65 = new StressResult(lastSnapshot, rtCore.sceneReadiness(), finalActivity, initialProbe, finalProbe, dynamicBursts, checksums.size(), completedFrameCount, averageCompletedFps, maxPendingAge, maxCompletionStallMillis, maxSnapshotLag);
      } catch (Throwable value51) {
         if (rtCore != null) {
            try {
               rtCore.close();
            } catch (Throwable value50) {
               value51.addSuppressed(value50);
            }
         }

         throw value51;
      }

      if (rtCore != null) {
         rtCore.close();
      }

      return stressResult65;
   }

   private static RtFrameSnapshot pumpUntilProbeReady(GuardedRtCore rtCore, long firstSequence, int expectedProbeVariant, int maxPumpFrames, String label) throws InterruptedException {
      RtFrameSnapshot lastSnapshot = rtCore.latestFrameSnapshot();
      long firstReadySequence = -1L;

      for(int frame = 0; frame < maxPumpFrames; ++frame) {
         long sequence = firstSequence + (long)frame;
         rtCore.acceptFrameUpdate(RendererFrameUpdate.empty(emptyBatch(), frameState(sequence)));
         RtSceneReadiness readiness = rtCore.sceneReadiness();
         if (firstReadySequence < 0L && readiness.builtRevisionIsCurrent() && !readiness.hasPendingRtBuilds()) {
            firstReadySequence = sequence;
         }

         RtFrameSnapshot snapshot = rtCore.latestFrameSnapshot();
         if (snapshot != null) {
            lastSnapshot = snapshot;
            if (firstReadySequence >= 0L && snapshot.frameStateSequence() >= firstReadySequence) {
               assertProbePixels(snapshot, expectedProbeVariant, label);
               return snapshot;
            }
         }

         require(rtCore.state() == State.READY_FOR_SCENE_UPDATES, "RT core failed while waiting for " + label + ": state=" + String.valueOf(rtCore.state()) + ", readiness=" + readiness.asLogFragment() + ", activity=" + rtCore.runtimeActivity().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
         Thread.sleep(PUMP_SLEEP_MILLIS);
      }

      throw new AssertionError(label + " never produced a probe-valid RT output, firstReadySequence=" + firstReadySequence + ", lastSnapshot=" + (lastSnapshot == null ? "none" : lastSnapshot.asLogFragment()) + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", activity=" + rtCore.runtimeActivity().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
   }

   private static DrainResult pumpUntilProbeDrained(GuardedRtCore rtCore, long firstSequence, int expectedProbeVariant, int maxPumpFrames, long initialCompletedSequence, long initialCompletedDispatch, long initialCompletionNanos, long initialExportedSharedFrameSequence) throws InterruptedException {
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
            lastExportedSharedFrameSequence = RtNativeStressGuards.sampleCompletedSharedFrame(rtCore, true, activity.latestCompletedFrameStateSequence(), lastExportedSharedFrameSequence, SHARED_FRAME_EXPORT_SAMPLE_DELTA, false, "masked-material drain frame " + frame);
         }

         RtFrameSnapshot snapshot = rtCore.latestFrameSnapshot();
         if (snapshot != null) {
            lastSnapshot = snapshot;
         }

         long pendingAge = activity.pendingFrameAgeMillis();
         long snapshotLag = snapshot == null ? 9223372036854775807L : Math.max(0L, sequence - snapshot.frameStateSequence());
         long completionStallMillis = Math.max(0L, nowNanos - lastCompletionNanos) / 1000000L;
         maxPendingAge = Math.max(maxPendingAge, pendingAge);
         maxSnapshotLag = Math.max(maxSnapshotLag, snapshotLag);
         maxCompletionStallMillis = Math.max(maxCompletionStallMillis, completionStallMillis);
         require(pendingAge <= MAX_READY_PENDING_FRAME_AGE_MILLIS, "masked-material final drain has a stale pending RT frame, sequence=" + sequence + ", pendingAgeMillis=" + pendingAge + ", maxAllowedMillis=" + MAX_READY_PENDING_FRAME_AGE_MILLIS + ", readiness=" + readiness.asLogFragment() + ", activity=" + activity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
         require(completionStallMillis <= MAX_READY_COMPLETION_STALL_MILLIS, "masked-material final drain completed-frame stream stalled, sequence=" + sequence + ", latestCompletedFrameStateSequence=" + activity.latestCompletedFrameStateSequence() + ", completionStallMillis=" + completionStallMillis + ", maxAllowedMillis=" + MAX_READY_COMPLETION_STALL_MILLIS + ", readiness=" + readiness.asLogFragment() + ", activity=" + activity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
         if (readiness.builtRevisionIsCurrent() && !readiness.hasPendingRtBuilds()) {
            long completedLag = completedSequenceLag(sequence, activity.latestCompletedFrameStateSequence());
            if (activity.latestCompletedFrameStateSequence() >= firstSequence) {
               require(completedLag <= (long)MAX_READY_SNAPSHOT_LAG, "masked-material final drain completed RT output is too far behind a ready scene, sequence=" + sequence + ", latestCompletedFrameStateSequence=" + activity.latestCompletedFrameStateSequence() + ", completedLag=" + completedLag + ", maxAllowedLag=" + MAX_READY_SNAPSHOT_LAG + ", snapshot=" + (snapshot == null ? "none" : snapshot.asLogFragment()) + ", readiness=" + readiness.asLogFragment() + ", activity=" + activity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
            }

            if (snapshot != null && snapshot.frameStateSequence() >= firstSequence) {
               require(snapshotLag <= (long)MAX_READY_SNAPSHOT_LAG, "masked-material final drain diagnostic snapshot is too far behind a ready scene, sequence=" + sequence + ", snapshotLag=" + snapshotLag + ", maxAllowedLag=" + MAX_READY_SNAPSHOT_LAG + ", snapshot=" + snapshot.asLogFragment() + ", latestCompletedFrameStateSequence=" + activity.latestCompletedFrameStateSequence() + ", readiness=" + readiness.asLogFragment() + ", activity=" + activity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
            }
         }

         if (lastSnapshot != null && readiness.builtRevisionIsCurrent() && !readiness.hasPendingRtBuilds() && firstReadySequence < 0L) {
            firstReadySequence = sequence;
         }

         if (lastSnapshot != null && firstReadySequence >= 0L && lastSnapshot.frameStateSequence() >= firstReadySequence && lastSnapshot.frameStateSequence() >= sequence - (long)MAX_READY_SNAPSHOT_LAG) {
            RtNativeStressGuards.assertFrameNotPathological(lastSnapshot, "masked-material drained frame");
            ProbeSamples samples = probeSamples(lastSnapshot);
            if (matchesProbeVariant(lastSnapshot, samples, expectedProbeVariant)) {
               try {
                  assertProbePixels(lastSnapshot, expectedProbeVariant, "final drain");
               } catch (AssertionError failure) {
                  String failureMessage10002 = failure.getMessage();
                  throw new AssertionError(failureMessage10002 + ", readiness=" + readiness.asLogFragment() + ", activity=" + activity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment(), failure);
               }

               return new DrainResult(lastSnapshot, completedFrames, maxPendingAge, maxCompletionStallMillis, maxSnapshotLag, lastExportedSharedFrameSequence);
            }

            Thread.sleep(PUMP_SLEEP_MILLIS);
         } else {
            boolean condition10000 = rtCore.state() == State.READY_FOR_SCENE_UPDATES;
            String stateDetails10001 = String.valueOf(rtCore.state());
            require(condition10000, "RT core failed during masked-material final drain: state=" + stateDetails10001 + ", readiness=" + readiness.asLogFragment() + ", activity=" + activity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
            Thread.sleep(PUMP_SLEEP_MILLIS);
         }
      }

      if (lastSnapshot != null) {
         try {
            assertProbePixels(lastSnapshot, expectedProbeVariant, "final drain timeout");
         } catch (AssertionError failure) {
            String failureMessage51 = failure.getMessage();
            throw new AssertionError(failureMessage51 + ", firstReadySequence=" + firstReadySequence + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", activity=" + rtCore.runtimeActivity().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment(), failure);
         }
      }

      String logDetails52 = lastSnapshot == null ? "none" : lastSnapshot.asLogFragment();
      throw new AssertionError("masked-material final drain never reached current RT output, lastSnapshot=" + logDetails52 + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", activity=" + rtCore.runtimeActivity().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
   }

   private static ProbeSamples assertProbePixels(RtFrameSnapshot snapshot, int expectedVariant, String label) {
      ProbeSamples samples = probeSamples(snapshot);
      IntPredicate primaryPredicate = expectedVariant == 0 ? RtNativeMaskedMaterialStressSelfTest::isStrongRed : RtNativeMaskedMaterialStressSelfTest::isStrongBlue;
      IntPredicate secondaryPredicate = expectedVariant == 0 ? RtNativeMaskedMaterialStressSelfTest::isStrongGreen : RtNativeMaskedMaterialStressSelfTest::isStrongYellow;
      require(countMatching(snapshot, samples.primaryX(), samples.primaryY(), 2, primaryPredicate) >= 3, label + " primary solid cutout texel was not shaded from the front masked texture, samples=" + samples.asLogFragment() + ", window=" + sampleWindow(snapshot, samples.primaryX(), samples.primaryY(), 2) + ", snapshot=" + snapshot.asLogFragment());
      require(countMatching(snapshot, samples.secondaryX(), samples.secondaryY(), 2, secondaryPredicate) >= 3, label + " secondary solid cutout texel has wrong UV orientation or wrong texture, samples=" + samples.asLogFragment() + ", window=" + sampleWindow(snapshot, samples.secondaryX(), samples.secondaryY(), 2) + ", snapshot=" + snapshot.asLogFragment());
      require(countMatching(snapshot, samples.holeX(), samples.holeY(), 2, RtNativeMaskedMaterialStressSelfTest::isBackplate) >= 3, label + " transparent cutout hole did not reveal the opaque backplate, samples=" + samples.asLogFragment() + ", window=" + sampleWindow(snapshot, samples.holeX(), samples.holeY(), 2) + ", snapshot=" + snapshot.asLogFragment());
      require(countMatching(snapshot, samples.secondHoleX(), samples.secondHoleY(), 2, RtNativeMaskedMaterialStressSelfTest::isBackplate) >= 3, label + " second transparent cutout hole did not reveal the opaque backplate, samples=" + samples.asLogFragment() + ", window=" + sampleWindow(snapshot, samples.secondHoleX(), samples.secondHoleY(), 2) + ", snapshot=" + snapshot.asLogFragment());
      require(samples.primaryColor() != RtSceneMaterialTable.missRgba8() && samples.secondaryColor() != RtSceneMaterialTable.missRgba8(), label + " solid masked texel collapsed into miss/sky blue, samples=" + samples.asLogFragment() + ", snapshot=" + snapshot.asLogFragment());
      require(samples.holeColor() != RtSceneMaterialTable.missRgba8() && samples.secondHoleColor() != RtSceneMaterialTable.missRgba8(), label + " transparent masked hole skipped the opaque backplate and fell through to sky, samples=" + samples.asLogFragment() + ", snapshot=" + snapshot.asLogFragment());
      assertCanopyProbePixel(snapshot, label);
      return samples;
   }

   private static ProbeSamples assertSustainedProbePixels(RtFrameSnapshot snapshot, String label) {
      ProbeSamples samples = probeSamples(snapshot);
      require(countMatching(snapshot, samples.primaryX(), samples.primaryY(), 2, (pixel) -> isStrongRed(pixel) || isStrongBlue(pixel)) >= 3, label + " primary solid cutout texel was neither valid dynamic texture variant, samples=" + samples.asLogFragment() + ", window=" + sampleWindow(snapshot, samples.primaryX(), samples.primaryY(), 2) + ", snapshot=" + snapshot.asLogFragment());
      require(countMatching(snapshot, samples.secondaryX(), samples.secondaryY(), 2, (pixel) -> isStrongGreen(pixel) || isStrongYellow(pixel)) >= 3, label + " secondary solid cutout texel has wrong UV orientation or an unknown dynamic texture, samples=" + samples.asLogFragment() + ", window=" + sampleWindow(snapshot, samples.secondaryX(), samples.secondaryY(), 2) + ", snapshot=" + snapshot.asLogFragment());
      require(countMatching(snapshot, samples.holeX(), samples.holeY(), 2, RtNativeMaskedMaterialStressSelfTest::isBackplate) >= 3, label + " transparent cutout hole did not reveal the opaque backplate, samples=" + samples.asLogFragment() + ", window=" + sampleWindow(snapshot, samples.holeX(), samples.holeY(), 2) + ", snapshot=" + snapshot.asLogFragment());
      require(countMatching(snapshot, samples.secondHoleX(), samples.secondHoleY(), 2, RtNativeMaskedMaterialStressSelfTest::isBackplate) >= 3, label + " second transparent cutout hole did not reveal the opaque backplate, samples=" + samples.asLogFragment() + ", window=" + sampleWindow(snapshot, samples.secondHoleX(), samples.secondHoleY(), 2) + ", snapshot=" + snapshot.asLogFragment());
      require(samples.primaryColor() != RtSceneMaterialTable.missRgba8() && samples.secondaryColor() != RtSceneMaterialTable.missRgba8() && samples.holeColor() != RtSceneMaterialTable.missRgba8() && samples.secondHoleColor() != RtSceneMaterialTable.missRgba8(), label + " masked probe collapsed into miss/sky blue, samples=" + samples.asLogFragment() + ", snapshot=" + snapshot.asLogFragment());
      assertCanopyProbePixel(snapshot, label);
      return samples;
   }

   private static void assertCanopyProbePixel(RtFrameSnapshot snapshot, String label) {
      SectionKey key = probeKey();
      int canopyX = pixelXForWorld(snapshot.width(), snapshot.height(), (float)key.x() * 16.0F + 8.0F, 15.99F);
      int canopyY = pixelYForWorld(snapshot.width(), snapshot.height(), (float)key.y() * 16.0F + 8.0F, 15.99F);
      require(countMatching(snapshot, canopyX, canopyY, 2, RtNativeMaskedMaterialStressSelfTest::isCocoaBrown) >= 3, label + " dense canopy/cocoa cutout face used the wrong material record or disappeared, canopy=(" + canopyX + "," + canopyY + "), window=" + sampleWindow(snapshot, canopyX, canopyY, 2) + ", snapshot=" + snapshot.asLogFragment());
   }

   private static boolean matchesProbeVariant(RtFrameSnapshot snapshot, ProbeSamples samples, int variant) {
      if (variant == 0) {
         return countMatching(snapshot, samples.primaryX(), samples.primaryY(), 2, RtNativeMaskedMaterialStressSelfTest::isStrongRed) >= 3 && countMatching(snapshot, samples.secondaryX(), samples.secondaryY(), 2, RtNativeMaskedMaterialStressSelfTest::isStrongGreen) >= 3;
      } else {
         return countMatching(snapshot, samples.primaryX(), samples.primaryY(), 2, RtNativeMaskedMaterialStressSelfTest::isStrongBlue) >= 3 && countMatching(snapshot, samples.secondaryX(), samples.secondaryY(), 2, RtNativeMaskedMaterialStressSelfTest::isStrongYellow) >= 3;
      }
   }

   private static long completedSequenceLag(long sequence, long latestCompletedSequence) {
      return latestCompletedSequence < 0L ? 9223372036854775807L : Math.max(0L, sequence - latestCompletedSequence);
   }

   private static ProbeSamples probeSamples(RtFrameSnapshot snapshot) {
      SectionKey key = probeKey();
      int primaryX = pixelXForWorld(snapshot.width(), snapshot.height(), (float)key.x() * 16.0F + 12.0F, 15.95F);
      int primaryY = pixelYForWorld(snapshot.width(), snapshot.height(), (float)key.y() * 16.0F + 4.0F, 15.95F);
      int secondaryX = pixelXForWorld(snapshot.width(), snapshot.height(), (float)key.x() * 16.0F + 4.0F, 15.95F);
      int secondaryY = pixelYForWorld(snapshot.width(), snapshot.height(), (float)key.y() * 16.0F + 12.0F, 15.95F);
      int holeX = pixelXForWorld(snapshot.width(), snapshot.height(), (float)key.x() * 16.0F + 4.0F, 15.95F);
      int holeY = pixelYForWorld(snapshot.width(), snapshot.height(), (float)key.y() * 16.0F + 4.0F, 15.95F);
      int secondHoleX = pixelXForWorld(snapshot.width(), snapshot.height(), (float)key.x() * 16.0F + 12.0F, 15.95F);
      int secondHoleY = pixelYForWorld(snapshot.width(), snapshot.height(), (float)key.y() * 16.0F + 12.0F, 15.95F);
      byte[] pixels = snapshot.copyRgba8();
      return new ProbeSamples(primaryX, primaryY, RtFrameSnapshot.pixel(pixels, snapshot.width(), primaryX, primaryY), secondaryX, secondaryY, RtFrameSnapshot.pixel(pixels, snapshot.width(), secondaryX, secondaryY), holeX, holeY, RtFrameSnapshot.pixel(pixels, snapshot.width(), holeX, holeY), secondHoleX, secondHoleY, RtFrameSnapshot.pixel(pixels, snapshot.width(), secondHoleX, secondHoleY));
   }

   private static int pixelXForWorld(int width, int height, float worldX, float worldZ) {
      RendererFrameState frameState = frameState(0L);
      CameraRayMath.RayScale scale = CameraRayMath.rayScale(frameState, width, height);
      float distance = (float)frameState.cameraZ() - worldZ;
      float ndcX = (worldX - (float)frameState.cameraX()) / (distance * scale.horizontalTan());
      return clampPixel(Math.round((ndcX + 1.0F) * 0.5F * (float)width - 0.5F), width);
   }

   private static int pixelYForWorld(int width, int height, float worldY, float worldZ) {
      RendererFrameState frameState = frameState(0L);
      CameraRayMath.RayScale scale = CameraRayMath.rayScale(frameState, width, height);
      float distance = (float)frameState.cameraZ() - worldZ;
      float ndcY = (worldY - (float)frameState.cameraY()) / (distance * scale.verticalTan());
      return clampPixel(Math.round((1.0F - ndcY) * 0.5F * (float)height - 0.5F), height);
   }

   private static int clampPixel(int value, int extent) {
      return Math.max(0, Math.min(extent - 1, value));
   }

   private static boolean isStrongRed(int pixel) {
      return red(pixel) >= 96 && green(pixel) <= 120 && blue(pixel) <= 90 && red(pixel) > green(pixel) + 48 && red(pixel) > blue(pixel) + 48;
   }

   private static boolean isStrongGreen(int pixel) {
      return green(pixel) >= 96 && red(pixel) <= 120 && blue(pixel) <= 90 && green(pixel) > red(pixel) + 48 && green(pixel) > blue(pixel) + 48;
   }

   private static boolean isStrongBlue(int pixel) {
      return blue(pixel) >= 96 && red(pixel) <= 90 && green(pixel) <= 120 && blue(pixel) > red(pixel) + 48 && blue(pixel) > green(pixel) + 48;
   }

   private static boolean isStrongYellow(int pixel) {
      return red(pixel) >= 96 && green(pixel) >= 88 && blue(pixel) <= 90 && Math.abs(red(pixel) - green(pixel)) <= 50;
   }

   private static boolean isBackplate(int pixel) {
      return red(pixel) >= 20 && red(pixel) <= 76 && green(pixel) >= 20 && green(pixel) <= 82 && blue(pixel) >= 25 && blue(pixel) <= 92 && Math.abs(red(pixel) - green(pixel)) <= 18 && Math.abs(green(pixel) - blue(pixel)) <= 24;
   }

   private static boolean isCocoaBrown(int pixel) {
      return red(pixel) >= 55 && red(pixel) <= 190 && green(pixel) >= 32 && green(pixel) <= 140 && blue(pixel) >= 12 && blue(pixel) <= 85 && red(pixel) > green(pixel) + 18 && green(pixel) > blue(pixel) + 8;
   }

   private static int countMatching(RtFrameSnapshot snapshot, int centerX, int centerY, int radius, IntPredicate predicate) {
      byte[] pixels = snapshot.copyRgba8();
      int count = 0;

      for(int y = Math.max(0, centerY - radius); y <= Math.min(snapshot.height() - 1, centerY + radius); ++y) {
         for(int x = Math.max(0, centerX - radius); x <= Math.min(snapshot.width() - 1, centerX + radius); ++x) {
            if (predicate.test(RtFrameSnapshot.pixel(pixels, snapshot.width(), x, y))) {
               ++count;
            }
         }
      }

      return count;
   }

   private static String sampleWindow(RtFrameSnapshot snapshot, int centerX, int centerY, int radius) {
      byte[] pixels = snapshot.copyRgba8();
      StringBuilder builder = new StringBuilder("[");
      int emitted = 0;

      for(int y = Math.max(0, centerY - radius); y <= Math.min(snapshot.height() - 1, centerY + radius); ++y) {
         for(int x = Math.max(0, centerX - radius); x <= Math.min(snapshot.width() - 1, centerX + radius); ++x) {
            if (emitted > 0) {
               builder.append(", ");
            }

            int pixel = RtFrameSnapshot.pixel(pixels, snapshot.width(), x, y);
            builder.append("(").append(x).append(",").append(y).append("=").append(RtFrameSnapshot.hex(pixel)).append("/rgba=").append(red(pixel)).append(',').append(green(pixel)).append(',').append(blue(pixel)).append(',').append(pixel >>> 24 & 255).append(")");
            ++emitted;
         }
      }

      return builder.append("]").toString();
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

   private static SectionKey probeKey() {
      return new SectionKey(SECTION_COLUMNS / 2, SECTION_ROWS / 2, 0);
   }

   private static Map<SectionKey, SectionTriangleMesh> buildMeshes(List<SectionKey> keys, RtTextureCatalog.TestTextureScope textures, int probeVariant) {
      Map<SectionKey, SectionTriangleMesh> meshes = new LinkedHashMap<>();
      SectionKey probe = probeKey();

      for(int index = 0; index < keys.size(); ++index) {
         SectionKey key = (SectionKey)keys.get(index);
         meshes.put(key, key.equals(probe) ? probeSectionMesh(key, textures, probeVariant) : pressureSectionMesh(key, textures, index));
      }

      return meshes;
   }

   private static Map<SectionKey, SectionTriangleMesh> mutationMeshes(List<SectionKey> keys, SectionKey probeKey, RtTextureCatalog.TestTextureScope textures, int burst, int probeVariant) {
      Map<SectionKey, SectionTriangleMesh> meshes = new LinkedHashMap<>();
      meshes.put(probeKey, probeSectionMesh(probeKey, textures, probeVariant));
      int offset = Math.floorMod(burst * 53, keys.size());

      for(int index = 0; index < Math.min(MUTATIONS_PER_BURST, keys.size()); ++index) {
         SectionKey key = (SectionKey)keys.get((offset + index * 17) % keys.size());
         if (!key.equals(probeKey)) {
            meshes.put(key, pressureSectionMesh(key, textures, burst * 4099 + index));
         }
      }

      return meshes;
   }

   private static SectionTriangleMesh probeSectionMesh(SectionKey key, RtTextureCatalog.TestTextureScope textures, int variant) {
      MeshBuilder builder = new MeshBuilder(key);
      builder.addPositiveZQuad(0.0F, 0.0F, 16.0F, 16.0F, 15.7F, textures.textureId("rtrenderer:selftest/backplate"), 0, false, false);
      builder.addPositiveZQuad(0.0F, 0.0F, 16.0F, 16.0F, 15.95F, textures.textureId(variant == 0 ? "rtrenderer:selftest/cutout_a" : "rtrenderer:selftest/cutout_b"), 0, false, true);
      builder.addPositiveZQuad(1.0F, 1.0F, 3.0F, 3.0F, 15.99F, textures.textureId(variant == 0 ? "rtrenderer:selftest/cutout_a" : "rtrenderer:selftest/cutout_b"), 0, false, true);
      builder.addPositiveZQuad(13.0F, 13.0F, 15.0F, 15.0F, 15.99F, textures.textureId(variant == 0 ? "rtrenderer:selftest/cutout_b" : "rtrenderer:selftest/cutout_a"), 0, false, true);
      builder.addPositiveZQuad(6.0F, 6.0F, 10.0F, 10.0F, 15.99F, textures.textureId("rtrenderer:selftest/cocoa_flat"), 0, false, true);
      return builder.build();
   }

   private static SectionTriangleMesh pressureSectionMesh(SectionKey key, RtTextureCatalog.TestTextureScope textures, int variant) {
      MeshBuilder builder = new MeshBuilder(key);
      int cutoutTexture = textures.textureId((variant & 1) == 0 ? "rtrenderer:selftest/cutout_a" : "rtrenderer:selftest/cutout_b");
      builder.addPositiveZQuad(0.0F, 0.0F, 16.0F, 16.0F, 15.7F, textures.textureId("rtrenderer:selftest/backplate"), 0, false, false);
      builder.addPositiveZQuad(0.0F, 0.0F, 16.0F, 16.0F, 15.95F, cutoutTexture, 0, false, true);
      builder.addQuad(new float[]{2.0F, 2.0F, 15.98F, 6.0F, 2.0F, 15.98F, 6.0F, 6.0F, 15.98F, 2.0F, 6.0F, 15.98F}, FaceDirection.POSITIVE_Z, textures.textureId("rtrenderer:selftest/cocoa_flat"), 0, false, true, standardUvs());
      builder.addQuad(new float[]{3.0F, 1.0F, 15.96F, 13.0F, 15.0F, 15.42F, 13.0F, 15.0F, 14.62F, 3.0F, 1.0F, 15.16F}, FaceDirection.POSITIVE_Z, cutoutTexture, 0, false, true, rotatedUvs(variant));
      builder.addQuad(new float[]{13.0F, 1.0F, 15.96F, 3.0F, 15.0F, 15.42F, 3.0F, 15.0F, 14.62F, 13.0F, 1.0F, 15.16F}, FaceDirection.POSITIVE_Z, cutoutTexture, 0, false, true, rotatedUvs(variant + 1));
      return builder.build();
   }

   private static int[] standardUvs() {
      return new int[]{RtTextureCatalog.packUv16(0.0F, 0.0F), RtTextureCatalog.packUv16(1.0F, 0.0F), RtTextureCatalog.packUv16(1.0F, 1.0F), RtTextureCatalog.packUv16(0.0F, 1.0F)};
   }

   private static int[] rotatedUvs(int variant) {
      int[] uv = standardUvs();
      return (variant & 1) == 0 ? uv : new int[]{uv[1], uv[2], uv[3], uv[0]};
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

   private static List<RtTextureCatalog.TestTexture> testTextures() {
      return List.of(new RtTextureCatalog.TestTexture("rtrenderer:selftest/cutout_a", 8, 8, cutoutTextureA()), new RtTextureCatalog.TestTexture("rtrenderer:selftest/cutout_b", 8, 8, cutoutTextureB()), new RtTextureCatalog.TestTexture("rtrenderer:selftest/backplate", 4, 4, solidTexture(68, 74, 84, 255, 4, 4)), new RtTextureCatalog.TestTexture("rtrenderer:selftest/cocoa_flat", 8, 8, cocoaTexture()));
   }

   private static int[] cutoutTextureA() {
      int[] pixels = new int[64];

      for(int y = 0; y < 8; ++y) {
         for(int x = 0; x < 8; ++x) {
            int offset = y * 8 + x;
            if (x >= 4 && y < 4) {
               pixels[offset] = rgba8(240, 48, 32, 255);
            } else if (x < 4 && y >= 4) {
               pixels[offset] = rgba8(32, 224, 80, 255);
            } else {
               pixels[offset] = rgba8(0, 0, 0, 0);
            }
         }
      }

      return pixels;
   }

   private static int[] cutoutTextureB() {
      int[] pixels = new int[64];

      for(int y = 0; y < 8; ++y) {
         for(int x = 0; x < 8; ++x) {
            int offset = y * 8 + x;
            if (x >= 4 && y < 4) {
               pixels[offset] = rgba8(32, 96, 240, 255);
            } else if (x < 4 && y >= 4) {
               pixels[offset] = rgba8(232, 208, 40, 255);
            } else {
               pixels[offset] = rgba8(0, 0, 0, 0);
            }
         }
      }

      return pixels;
   }

   private static int[] cocoaTexture() {
      int[] pixels = new int[64];

      for(int y = 0; y < 8; ++y) {
         for(int x = 0; x < 8; ++x) {
            boolean border = x == 0 || x == 7 || y == 0 || y == 7;
            boolean bean = x >= 2 && x <= 5 && y >= 2 && y <= 5;
            pixels[y * 8 + x] = !border && !bean ? rgba8(0, 0, 0, 0) : rgba8(148, 86, 36, 255);
         }
      }

      return pixels;
   }

   private static int[] solidTexture(int red, int green, int blue, int alpha, int width, int height) {
      int[] pixels = new int[width * height];
      Arrays.fill(pixels, rgba8(red, green, blue, alpha));
      return pixels;
   }

   private static int rgba8(int red, int green, int blue, int alpha) {
      return red & 255 | (green & 255) << 8 | (blue & 255) << 16 | (alpha & 255) << 24;
   }

   private static int red(int pixel) {
      return pixel & 255;
   }

   private static int green(int pixel) {
      return pixel >>> 8 & 255;
   }

   private static int blue(int pixel) {
      return pixel >>> 16 & 255;
   }

   private static short fixed(float blockUnits) {
      return (short)Math.round(blockUnits * 1024.0F);
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
      set(previous, "top.ceroxe.rt.rt.output.maxPixels", Integer.toString(OUTPUT_WIDTH * OUTPUT_HEIGHT));
      set(previous, "top.ceroxe.rt.rt.worldTlas.minInitialInstances", "1");
      set(previous, "top.ceroxe.rt.rt.worldTlas.minRebuildIntervalMillis", "0");
      set(previous, "top.ceroxe.rt.rt.worldTlas.minStreamingRebuildIntervalMillis", "0");
      set(previous, "top.ceroxe.rt.rt.worldTlas.minStreamingRevisionDelta", "1");
      set(previous, "top.ceroxe.rt.rt.worldTlas.minStreamingInstanceDelta", "1");
      set(previous, "top.ceroxe.rt.rt.worldTlas.allowBackloggedStreamingRebuilds", "true");
      set(previous, "top.ceroxe.rt.rt.scheduler.maxStreamingSceneBindDeferrals", "2");
      set(previous, "top.ceroxe.rt.rt.sectionBlas.maxBuildsPerFrame", "192");
      set(previous, "top.ceroxe.rt.rt.sectionBlas.maxTrianglesPerFrame", "6000000");
      set(previous, "top.ceroxe.rt.rt.sectionBlas.maxAsyncBuildsInFlight", "16");
      set(previous, "top.ceroxe.rt.rt.sectionBlas.maxAsyncBuildSectionsInFlight", "1024");
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
            return parsed > 0 ? parsed : defaultValue;
         } catch (NumberFormatException value4) {
            return defaultValue;
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
            return parsed >= 0L ? parsed : defaultValue;
         } catch (NumberFormatException value6) {
            return defaultValue;
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
            return parsed >= 0.0 ? parsed : defaultValue;
         } catch (NumberFormatException value6) {
            return defaultValue;
         }
      } else {
         return defaultValue;
      }
   }

   private static boolean booleanProperty(String name, boolean defaultValue) {
      String raw = System.getProperty(name);
      return raw != null && !raw.isBlank() ? Boolean.parseBoolean(raw.trim()) : defaultValue;
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

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }

   static {
      TOTAL_SECTIONS = SECTION_COLUMNS * SECTION_ROWS;
      MAX_INITIAL_READY_PUMP_FRAMES = intProperty("top.ceroxe.rt.rt.maskedStress.maxInitialReadyPumpFrames", 3600);
      SUSTAINED_FRAMES = intProperty("top.ceroxe.rt.rt.maskedStress.sustainedFrames", 240);
      MAX_FINAL_DRAIN_FRAMES = intProperty("top.ceroxe.rt.rt.maskedStress.maxFinalDrainFrames", 2400);
      MUTATION_PERIOD_FRAMES = intProperty("top.ceroxe.rt.rt.maskedStress.mutationPeriodFrames", 8);
      MUTATIONS_PER_BURST = intProperty("top.ceroxe.rt.rt.maskedStress.mutationsPerBurst", 48);
      MAX_READY_SNAPSHOT_LAG = intProperty("top.ceroxe.rt.rt.maskedStress.maxReadySnapshotLag", 180);
      READBACK_SAMPLE_INTERVAL = intProperty("top.ceroxe.rt.rt.maskedStress.readbackSampleInterval", 5);
      MAX_READY_PENDING_FRAME_AGE_MILLIS = longProperty("top.ceroxe.rt.rt.maskedStress.maxReadyPendingFrameAgeMillis", 1500L);
      MAX_READY_COMPLETION_STALL_MILLIS = longProperty("top.ceroxe.rt.rt.maskedStress.maxReadyCompletionStallMillis", 1500L);
      PUMP_SLEEP_MILLIS = longProperty("top.ceroxe.rt.rt.maskedStress.pumpSleepMillis", 6L);
      MIN_COMPLETED_FPS = doubleProperty("top.ceroxe.rt.rt.maskedStress.minCompletedFps", 1.5);
      EXPORT_SHARED_FRAME_ENABLED = booleanProperty("top.ceroxe.rt.rt.maskedStress.exportSharedFrame.enabled", true);
      SHARED_FRAME_EXPORT_SAMPLE_DELTA = intProperty("top.ceroxe.rt.rt.maskedStress.sharedFrameExportSampleDelta", 30);
      SNAPSHOT_PATH = Path.of(System.getProperty("java.io.tmpdir"), "rtrenderer-native-masked-material-stress.png");
   }

   private static record StressResult(RtFrameSnapshot lastSnapshot, RtSceneReadiness readiness, RtCore.RuntimeActivity activity, ProbeSamples initialProbe, ProbeSamples finalProbe, int dynamicBursts, int distinctChecksums, long completedFrames, double averageCompletedFps, long maxReadyPendingFrameAgeMillis, long maxReadyCompletionStallMillis, long maxReadySnapshotLag) {
   }

   private static record DrainResult(RtFrameSnapshot snapshot, long completedFrames, long maxPendingFrameAgeMillis, long maxCompletionStallMillis, long maxSnapshotLag, long lastExportedSharedFrameSequence) {
   }

   private static record ProbeSamples(int primaryX, int primaryY, int primaryColor, int secondaryX, int secondaryY, int secondaryColor, int holeX, int holeY, int holeColor, int secondHoleX, int secondHoleY, int secondHoleColor) {
      private String asLogFragment() {
         int primaryX10000 = this.primaryX;
         return "probeSamples{primary=(" + primaryX10000 + "," + this.primaryY + "=" + RtFrameSnapshot.hex(this.primaryColor) + "), secondary=(" + this.secondaryX + "," + this.secondaryY + "=" + RtFrameSnapshot.hex(this.secondaryColor) + "), hole=(" + this.holeX + "," + this.holeY + "=" + RtFrameSnapshot.hex(this.holeColor) + "), secondHole=(" + this.secondHoleX + "," + this.secondHoleY + "=" + RtFrameSnapshot.hex(this.secondHoleColor) + ")}";
      }
   }

   private static final class MaskedSceneState {
      private final SceneDatabase database = new SceneDatabase();
      private final SectionMaterialCache materialCache = new SectionMaterialCache();
      private final SectionGeometryCache geometryCache = SectionGeometryCache.transientProductionStaging();
      private final SectionMeshCache meshCache = new SectionMeshCache();

      private static SceneUpdateBatch preparedMeshBatch(Map<SectionKey, SectionTriangleMesh> meshes) {
         Set<SectionKey> dirtySections = Set.copyOf(meshes.keySet());
         Set<ChunkKey> dirtyChunks = new LinkedHashSet<>();
         Map<SectionKey, SectionVoxelSnapshot> snapshots = new LinkedHashMap<>();

         for(SectionKey key : dirtySections) {
            dirtyChunks.add(key.chunkKey());
            snapshots.put(key, RtNativeMaskedMaterialStressSelfTest.filledSection(key, 1));
         }

         return new SceneUpdateBatch(dirtySections, dirtyChunks, Set.of(), Set.of(), snapshots, false, (long)dirtySections.size(), (long)dirtySections.size(), 0L, 0L, 0L, 0L, 0L, SceneUpdateBatch.sourceFlagsForBlockMutation());
      }

      private RendererFrameUpdate initialUpdate(Map<SectionKey, SectionTriangleMesh> meshes, RendererFrameState frameState) {
         Map<ChunkKey, List<SectionVoxelSnapshot>> sectionsByChunk = new LinkedHashMap<>();

         for(SectionKey key : meshes.keySet()) {
            sectionsByChunk.computeIfAbsent(key.chunkKey(), ignored -> new ArrayList<>()).add(RtNativeMaskedMaterialStressSelfTest.filledSection(key, 1));
         }

         for(Map.Entry<ChunkKey, List<SectionVoxelSnapshot>> entry : sectionsByChunk.entrySet()) {
            int minY = entry.getValue().stream().mapToInt(section -> section.key().y()).min().orElse(0);
            this.database.replaceChunkSnapshot(new ChunkSnapshot(entry.getKey(), minY, entry.getValue()));
         }

         return this.applyPreparedMeshes(meshes, frameState);
      }

      private RendererFrameUpdate replacePreparedMeshes(Map<SectionKey, SectionTriangleMesh> meshes, RendererFrameState frameState) {
         for(SectionKey key : meshes.keySet()) {
            this.database.replaceBlockMutationSectionSnapshot(RtNativeMaskedMaterialStressSelfTest.filledSection(key, 1));
         }

         return this.applyPreparedMeshes(meshes, frameState);
      }

      private RendererFrameUpdate applyPreparedMeshes(Map<SectionKey, SectionTriangleMesh> meshes, RendererFrameState frameState) {
         SceneUpdateBatch batch = this.database.drainPendingUpdates();
         if (!batch.hasChanges() && !meshes.isEmpty()) {
            batch = preparedMeshBatch(meshes);
         }

         SectionMaterialCache.MaterialFacts materialFacts = MaterialFacts.empty();

         for(SectionVoxelSnapshot snapshot : batch.sectionSnapshots().values()) {
            materialFacts = materialFacts.plus(MaterialFacts.fromSnapshot(snapshot));
         }

         SectionMaterialCache.ApplyResult material = this.materialCache.applyMaterialUpdates(batch, batch.sectionSnapshots().keySet(), materialFacts);
         SectionGeometryCache.ApplyResult geometry = this.geometryCache.applyPrepared(Map.of(), batch.removedSections(), batch.fullResyncRequested());
         SectionMeshCache.ApplyResult meshResult = this.meshCache.applyPrepared(meshes, batch.removedSections(), batch.fullResyncRequested());
         RtNativeMaskedMaterialStressSelfTest.require(meshResult.trianglesInBatch() > 0, "masked-material stress update must submit visible triangles");
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

      private void addPositiveZQuad(float x0, float y0, float x1, float y1, float z, int textureId, int mapColor, boolean tinted, boolean alphaCutout) {
         this.addQuad(new float[]{x0, y0, z, x1, y0, z, x1, y1, z, x0, y1, z}, FaceDirection.POSITIVE_Z, textureId, mapColor, tinted, alphaCutout, RtNativeMaskedMaterialStressSelfTest.standardUvs());
      }

      private void addQuad(float[] quadPositions, FaceDirection direction, int textureId, int mapColor, boolean tinted, boolean alphaCutout, int[] packedUvs) {
         if (quadPositions.length != 12) {
            throw new IllegalArgumentException("quadPositions must contain four xyz vertices");
         } else if (packedUvs.length != 4) {
            throw new IllegalArgumentException("packedUvs must contain four UVs");
         } else {
            int firstVertex = this.positions.size() / 3;

            for(int vertex = 0; vertex < 4; ++vertex) {
               int offset = vertex * 3;
               this.addVertex(quadPositions[offset], quadPositions[offset + 1], quadPositions[offset + 2]);
            }

            this.indices.add(firstVertex);
            this.indices.add(firstVertex + 1);
            this.indices.add(firstVertex + 2);
            this.indices.add(firstVertex);
            this.indices.add(firstVertex + 2);
            this.indices.add(firstVertex + 3);
            this.voxelTypeIds.add(1);
            this.mediumAmounts.add((byte)0);
            this.directions.add((byte)direction.ordinal());
            this.mapColors.add(mapColor);
            this.lightEmissions.add((byte)0);
            this.materialFlags.add((byte)1);
            this.textureIds.add(textureId);
            this.uv0.add(packedUvs[0]);
            this.uv1.add(packedUvs[1]);
            this.uv2.add(packedUvs[2]);
            this.uv3.add(packedUvs[3]);
            this.tintFlags.add((byte)(tinted ? 1 : 0));
            this.alphaCutoutFlags.add((byte)(alphaCutout ? 1 : 0));
         }
      }

      private void addVertex(float x, float y, float z) {
         this.positions.add(RtNativeMaskedMaterialStressSelfTest.fixed(x));
         this.positions.add(RtNativeMaskedMaterialStressSelfTest.fixed(y));
         this.positions.add(RtNativeMaskedMaterialStressSelfTest.fixed(z));
      }

      private SectionTriangleMesh build() {
         int faceCount = this.voxelTypeIds.size();
         RtNativeMaskedMaterialStressSelfTest.require(this.positions.size() == faceCount * 4 * 3, "masked mesh vertex count mismatch");
         RtNativeMaskedMaterialStressSelfTest.require(this.indices.size() == faceCount * 6, "masked mesh index count mismatch");
         return new SectionTriangleMesh(this.key, shorts(this.positions), ints(this.indices), ints(this.voxelTypeIds), bytes(this.mediumAmounts), bytes(this.directions), ints(this.mapColors), bytes(this.lightEmissions), bytes(this.materialFlags), ints(this.textureIds), ints(this.uv0), ints(this.uv1), ints(this.uv2), ints(this.uv3), bytes(this.tintFlags), bytes(this.alphaCutoutFlags));
      }
   }
}
