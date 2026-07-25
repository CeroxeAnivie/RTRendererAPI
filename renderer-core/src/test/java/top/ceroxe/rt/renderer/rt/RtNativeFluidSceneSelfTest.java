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
import java.util.Objects;
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
import top.ceroxe.rt.renderer.scene.SectionEncodedSnapshot;
import top.ceroxe.rt.renderer.scene.SectionFace;
import top.ceroxe.rt.renderer.scene.SectionGeometryCache;
import top.ceroxe.rt.renderer.scene.SectionGeometrySnapshot;
import top.ceroxe.rt.renderer.scene.SectionKey;
import top.ceroxe.rt.renderer.scene.SectionMaterialCache;
import top.ceroxe.rt.renderer.scene.SectionMeshBuilder;
import top.ceroxe.rt.renderer.scene.SectionMeshCache;
import top.ceroxe.rt.renderer.scene.SectionMesher;
import top.ceroxe.rt.renderer.scene.SectionNeighborhood;
import top.ceroxe.rt.renderer.scene.SectionTriangleMesh;
import top.ceroxe.rt.renderer.scene.SectionVoxelSnapshot;
import top.ceroxe.rt.renderer.scene.SectionMaterialCache.MaterialFacts;

public final class RtNativeFluidSceneSelfTest {
   private static final int OUTPUT_WIDTH = intProperty("top.ceroxe.rt.rt.fluidStress.width", 960);
   private static final int OUTPUT_HEIGHT = intProperty("top.ceroxe.rt.rt.fluidStress.height", 540);
   private static final int SECTION_COLUMNS = intProperty("top.ceroxe.rt.rt.fluidStress.sectionColumns", 37);
   private static final int SECTION_ROWS = intProperty("top.ceroxe.rt.rt.fluidStress.sectionRows", 19);
   private static final int TOTAL_SECTIONS;
   private static final int WATERLOGGED_STRESS_SECTIONS;
   private static final int FLUID_FAMILY_STRESS_SECTIONS;
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
   private static final int SOLID_BLOCK_STATE_ID = 1;
   private static final int WATER_BLOCK_STATE_ID = 91;
   private static final int WATER_FLOWING_BLOCK_STATE_ID = 92;
   private static final int WATER_FLUID_TYPE_ID = 7;
   private static final int FULL_FLUID_AMOUNT = 8;
   private static final float FRONT_Z = 15.92F;
   private static final float BACK_Z = 15.58F;
   private static final String WATER_A_TEXTURE = "rtrenderer:selftest/fluid_water_a";
   private static final String WATER_B_TEXTURE = "rtrenderer:selftest/fluid_water_b";
   private static final String BACKPLATE_TEXTURE = "rtrenderer:selftest/fluid_backplate";
   private static final String FOAM_CUTOUT_TEXTURE = "rtrenderer:selftest/fluid_foam_cutout";
   private static final String SEAGRASS_CUTOUT_TEXTURE = "rtrenderer:selftest/fluid_seagrass_cutout";
   private static final Path SNAPSHOT_PATH;

   private RtNativeFluidSceneSelfTest() {
   }

   public static void main(String[] args) throws Exception {
      Map<String, String> previousProperties = installStressProperties();

      try {
         RtTextureCatalog.TestTextureScope textures = RtTextureCatalog.installTestTexturesForSelfTest(testTextures());

         try {
            WaterloggedStressStats waterloggedStats = assertWaterloggedMeshingStressContract();
            FluidFamilyStressStats fluidFamilyStats = assertFluidFamilyMeshingStressContract();
            FluidNeighborhoodStressStats fluidNeighborhoodStats = assertFluidNeighborhoodMeshingStressContract();
            VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
            require(capability.hardwareRayTracingReady(), "native fluid scene requires production RT hardware: " + capability.summary());
            StressResult result = runFluidScene(capability, textures);
            writeSnapshotPng(result.lastSnapshot(), SNAPSHOT_PATH);
            int sectionCount10001 = TOTAL_SECTIONS;
            System.out.println("RtNativeFluidSceneSelfTest passed: sections=" + sectionCount10001 + ", sustainedFrames=" + SUSTAINED_FRAMES + ", dynamicBursts=" + result.dynamicBursts() + ", distinctChecksums=" + result.distinctChecksums() + ", completedFrames=" + result.completedFrames() + ", averageCompletedFps=" + result.averageCompletedFps() + ", maxReadyPendingFrameAgeMillis=" + result.maxReadyPendingFrameAgeMillis() + ", maxReadyCompletionStallMillis=" + result.maxReadyCompletionStallMillis() + ", maxReadySnapshotLag=" + result.maxReadySnapshotLag() + ", initialProbe=" + result.initialProbe().asLogFragment() + ", finalProbe=" + result.finalProbe().asLogFragment() + ", lastSnapshot=" + result.lastSnapshot().asLogFragment() + ", png=" + String.valueOf(SNAPSHOT_PATH) + ", waterloggedPreflight=" + waterloggedStats.asLogFragment() + ", fluidFamilyPreflight=" + fluidFamilyStats.asLogFragment() + ", fluidNeighborhoodPreflight=" + fluidNeighborhoodStats.asLogFragment() + ", readiness=" + result.readiness().asLogFragment() + ", activity=" + result.activity().asLogFragment());
            System.out.println(RtNativeBenchmarkReport.pacedScene("fluidMedium", OUTPUT_WIDTH, OUTPUT_HEIGHT, result.completedFrames(), result.averageCompletedFps(), result.activity(), result.readiness()));
         } catch (Throwable value13) {
            if (textures != null) {
               try {
                  textures.close();
               } catch (Throwable value12) {
                  value13.addSuppressed(value12);
               }
            }

            throw value13;
         }

         if (textures != null) {
            textures.close();
         }
      } finally {
         restoreProperties(previousProperties);
      }

   }

   private static StressResult runFluidScene(VulkanRtCapabilityProbe.Result capability, RtTextureCatalog.TestTextureScope textures) throws Exception {
      GuardedRtCore rtCore = GuardedRtCore.isolatedHardwareTest();

      StressResult stressResult77;
      try {
         rtCore.acceptCapability(capability);
         boolean condition10000 = rtCore.state() == State.READY_FOR_SCENE_UPDATES;
         String stateDetails10001 = String.valueOf(rtCore.state());
         require(condition10000, "RT core did not open native backend for fluid scene: state=" + stateDetails10001 + ", summary=" + rtCore.summary().asLogFragment());
         FluidSceneState scene = new FluidSceneState();
         List<SectionKey> keys = buildSectionKeys();
         SectionKey probeKey = probeKey();
         rtCore.acceptFrameUpdate(scene.initialUpdate(buildPreparedSections(keys, textures, RtNativeFluidSceneSelfTest.FluidVariant.FULL_A), frameState(1L)));
         RtFrameSnapshot initialSnapshot = pumpUntilProbeReady(rtCore, 2L, RtNativeFluidSceneSelfTest.FluidVariant.FULL_A, MAX_INITIAL_READY_PUMP_FRAMES, "initial fluid scene");
         ProbeSamples initialProbe = assertProbePixels(initialSnapshot, RtNativeFluidSceneSelfTest.FluidVariant.FULL_A, "initial");
         RtNativeStressGuards.assertFrameNotPathological(initialSnapshot, "initial fluid scene frame");
         scene.resetMutationTimings();
         long phaseStartNanos = System.nanoTime();
         long lastCompletedSequence = Math.max(0L, rtCore.runtimeActivity().latestCompletedFrameStateSequence());
         long lastCompletedDispatch = Math.max(0L, rtCore.runtimeActivity().latestCompletedFrameDispatch());
         long lastCompletionNanos = System.nanoTime();
         long lastExportedSharedFrameSequence = -1L;
         long completedFrameCount = 0L;
         long maxPendingAge = 0L;
         long maxCompletionStallMillis = 0L;
         long maxSnapshotLag = 0L;
         long mutationMeshPreparationNanos = 0L;
         long mutationPublicationNanos = 0L;
         long acceptFrameNanos = 0L;
         int dynamicBursts = 0;
         FluidVariant expectedProbeVariant = RtNativeFluidSceneSelfTest.FluidVariant.FULL_A;
         boolean observedFluidA = true;
         boolean observedFluidB = false;
         Set<Long> checksums = new HashSet<>();
         checksums.add(initialSnapshot.checksum());
         RtFrameSnapshot lastSnapshot = initialSnapshot;

         for(int frame = 0; frame < SUSTAINED_FRAMES; ++frame) {
            long sequence = 10000L + (long)frame;
            RendererFrameUpdate update;
            if (frame % MUTATION_PERIOD_FRAMES == 0) {
               ++dynamicBursts;
               expectedProbeVariant = (dynamicBursts & 1) == 0 ? RtNativeFluidSceneSelfTest.FluidVariant.FULL_A : RtNativeFluidSceneSelfTest.FluidVariant.FULL_B;
               long mutationStageStartNanos = System.nanoTime();
               Map<SectionKey, PreparedSection> preparedSections = mutationPreparedSections(keys, probeKey, textures, dynamicBursts, expectedProbeVariant);
               mutationMeshPreparationNanos += System.nanoTime() - mutationStageStartNanos;
               mutationStageStartNanos = System.nanoTime();
               update = scene.replacePreparedSections(preparedSections, frameState(sequence));
               mutationPublicationNanos += System.nanoTime() - mutationStageStartNanos;
            } else {
               update = RendererFrameUpdate.empty(emptyBatch(), frameState(sequence));
            }

            long acceptFrameStartNanos = System.nanoTime();
            rtCore.acceptFrameUpdate(update);
            acceptFrameNanos += System.nanoTime() - acceptFrameStartNanos;
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
               lastSnapshot = snapshot;
               checksums.add(snapshot.checksum());
               ProbeSamples visibleSamples = probeSamples(snapshot);
               observedFluidA |= isFluidA(visibleSamples.centerColor());
               observedFluidB |= isFluidB(visibleSamples.centerColor());
            }

            if (readiness.builtRevisionIsCurrent() && !readiness.hasPendingRtBuilds()) {
               long pendingAge = activity.pendingFrameAgeMillis();
               long snapshotLag = snapshot == null ? 9223372036854775807L : Math.max(0L, sequence - snapshot.frameStateSequence());
               long completionStallMillis = Math.max(0L, nowNanos - lastCompletionNanos) / 1000000L;
               maxPendingAge = Math.max(maxPendingAge, pendingAge);
               maxSnapshotLag = Math.max(maxSnapshotLag, snapshotLag);
               maxCompletionStallMillis = Math.max(maxCompletionStallMillis, completionStallMillis);
               require(pendingAge <= MAX_READY_PENDING_FRAME_AGE_MILLIS, "fluid scene has a stale pending RT frame after scene became current, sequence=" + sequence + ", pendingAgeMillis=" + pendingAge + ", maxAllowedMillis=" + MAX_READY_PENDING_FRAME_AGE_MILLIS + ", readiness=" + readiness.asLogFragment() + ", activity=" + activity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
               require(completionStallMillis <= MAX_READY_COMPLETION_STALL_MILLIS, "fluid scene completed-frame stream stalled after scene became current, sequence=" + sequence + ", snapshot=" + (snapshot == null ? "none" : snapshot.asLogFragment()) + ", latestCompletedFrameStateSequence=" + activity.latestCompletedFrameStateSequence() + ", completionStallMillis=" + completionStallMillis + ", maxAllowedMillis=" + MAX_READY_COMPLETION_STALL_MILLIS + ", readiness=" + readiness.asLogFragment() + ", activity=" + activity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
               long completedLag = completedSequenceLag(sequence, activity.latestCompletedFrameStateSequence());
               if (activity.latestCompletedFrameStateSequence() >= sequence) {
                  require(completedLag <= (long)MAX_READY_SNAPSHOT_LAG, "fluid scene completed RT output is too far behind a ready scene, sequence=" + sequence + ", latestCompletedFrameStateSequence=" + activity.latestCompletedFrameStateSequence() + ", completedLag=" + completedLag + ", maxAllowedLag=" + MAX_READY_SNAPSHOT_LAG + ", snapshot=" + (snapshot == null ? "none" : snapshot.asLogFragment()) + ", readiness=" + readiness.asLogFragment() + ", activity=" + activity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
               }

               if (snapshot != null && snapshot.frameStateSequence() >= sequence) {
                  require(snapshotLag <= (long)MAX_READY_SNAPSHOT_LAG, "fluid scene diagnostic snapshot is too far behind a ready scene, sequence=" + sequence + ", snapshotLag=" + snapshotLag + ", maxAllowedLag=" + MAX_READY_SNAPSHOT_LAG + ", snapshot=" + snapshot.asLogFragment() + ", latestCompletedFrameStateSequence=" + activity.latestCompletedFrameStateSequence() + ", readiness=" + readiness.asLogFragment() + ", activity=" + activity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
               }

               if (snapshot != null && snapshot.frameStateSequence() >= sequence && snapshotLag <= (long)MAX_READY_SNAPSHOT_LAG) {
                  assertSustainedProbePixels(snapshot, "sustained fluid frame " + frame);
                  RtNativeStressGuards.assertFrameNotPathological(snapshot, "fluid ready frame " + frame);
               }
            }

            condition10000 = rtCore.state() == State.READY_FOR_SCENE_UPDATES;
            stateDetails10001 = String.valueOf(rtCore.state());
            require(condition10000, "RT core failed during fluid scene: state=" + stateDetails10001 + ", readiness=" + readiness.asLogFragment() + ", activity=" + activity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
            if (EXPORT_SHARED_FRAME_ENABLED && activity.latestCompletedFrameStateSequence() > lastExportedSharedFrameSequence) {
               lastExportedSharedFrameSequence = RtNativeStressGuards.sampleCompletedSharedFrame(rtCore, true, activity.latestCompletedFrameStateSequence(), lastExportedSharedFrameSequence, SHARED_FRAME_EXPORT_SAMPLE_DELTA, false, "fluid scene frame " + frame);
            }

            Thread.sleep(PUMP_SLEEP_MILLIS);
         }

         if (!observedFluidB) {
            long verificationSequence = 19000L;
            rtCore.acceptFrameUpdate(scene.replacePreparedSections(Map.of(probeKey, probePreparedSection(probeKey, textures, RtNativeFluidSceneSelfTest.FluidVariant.FULL_B)), frameState(verificationSequence)));
            lastSnapshot = pumpUntilProbeReady(rtCore, verificationSequence + 1L, RtNativeFluidSceneSelfTest.FluidVariant.FULL_B, MAX_INITIAL_READY_PUMP_FRAMES, "deterministic fluid B verification");
            checksums.add(lastSnapshot.checksum());
            observedFluidB = isFluidB(probeSamples(lastSnapshot).centerColor());
            RtCore.RuntimeActivity verifiedActivity = rtCore.runtimeActivity();
            long verifiedCompletedDispatch = verifiedActivity.latestCompletedFrameDispatch();
            if (verifiedCompletedDispatch > lastCompletedDispatch) {
               completedFrameCount += verifiedCompletedDispatch - lastCompletedDispatch;
            }

            lastCompletedSequence = Math.max(lastCompletedSequence, verifiedActivity.latestCompletedFrameStateSequence());
            lastCompletedDispatch = Math.max(lastCompletedDispatch, verifiedCompletedDispatch);
            lastCompletionNanos = System.nanoTime();
         }

         rtCore.acceptFrameUpdate(scene.replacePreparedSections(Map.of(probeKey, probePreparedSection(probeKey, textures, RtNativeFluidSceneSelfTest.FluidVariant.DRAINED)), frameState(20000L)));
         require(observedFluidA && observedFluidB, "fluid scene did not observe both dynamic water material variants before drain, observedFluidA=" + observedFluidA + ", observedFluidB=" + observedFluidB + ", lastSnapshot=" + lastSnapshot.asLogFragment() + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", activity=" + rtCore.runtimeActivity().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
         DrainResult drain = pumpUntilProbeDrained(rtCore, 20001L, RtNativeFluidSceneSelfTest.FluidVariant.DRAINED, MAX_FINAL_DRAIN_FRAMES, lastCompletedSequence, lastCompletedDispatch, lastCompletionNanos, lastExportedSharedFrameSequence);
         lastSnapshot = drain.snapshot();
         lastExportedSharedFrameSequence = drain.lastExportedSharedFrameSequence();
         checksums.add(lastSnapshot.checksum());
         completedFrameCount += drain.completedFrames();
         maxPendingAge = Math.max(maxPendingAge, drain.maxPendingFrameAgeMillis());
         maxCompletionStallMillis = Math.max(maxCompletionStallMillis, drain.maxCompletionStallMillis());
         maxSnapshotLag = Math.max(maxSnapshotLag, drain.maxSnapshotLag());
         ProbeSamples finalProbe = assertProbePixels(lastSnapshot, RtNativeFluidSceneSelfTest.FluidVariant.DRAINED, "final drained fluid scene");
         RtNativeStressGuards.assertFrameNotPathological(lastSnapshot, "final drained fluid scene frame");
         long elapsedNanos = Math.max(1L, System.nanoTime() - phaseStartNanos);
         RtCore.RuntimeActivity finalActivity = rtCore.runtimeActivity();
         lastExportedSharedFrameSequence = RtNativeStressGuards.sampleCompletedSharedFrame(rtCore, EXPORT_SHARED_FRAME_ENABLED, finalActivity.latestCompletedFrameStateSequence(), lastExportedSharedFrameSequence, SHARED_FRAME_EXPORT_SAMPLE_DELTA, true, "fluid scene final frame");
         double averageCompletedFps = (double)completedFrameCount * 1.0E9 / (double)elapsedNanos;
         require(averageCompletedFps >= MIN_COMPLETED_FPS, "fluid scene completed frames below fps floor, averageCompletedFps=" + averageCompletedFps + ", minCompletedFps=" + MIN_COMPLETED_FPS + ", completedFrames=" + completedFrameCount + ", elapsedMillis=" + elapsedNanos / 1000000L + ", mutationMeshPreparationMillis=" + mutationMeshPreparationNanos / 1000000L + ", mutationPublicationMillis=" + mutationPublicationNanos / 1000000L + ", mutationPublicationStages=" + scene.mutationTimingSummary() + ", acceptFrameMillis=" + acceptFrameNanos / 1000000L + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", activity=" + finalActivity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
         RtNativeStressGuards.assertSharedFrameReachedCompletedFrame(EXPORT_SHARED_FRAME_ENABLED, finalActivity.latestCompletedFrameStateSequence(), lastExportedSharedFrameSequence, "fluid scene");
         RtNativeStressGuards.assertCommandAndFencePoolReused(rtCore, "fluid scene");
         condition10000 = checksums.size() >= 3;
         int size83 = checksums.size();
         require(condition10000, "fluid scene did not visibly change across fill/drain replacements, distinctChecksums=" + size83 + ", lastSnapshot=" + lastSnapshot.asLogFragment() + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", activity=" + finalActivity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
         condition10000 = initialProbe.centerColor() != finalProbe.centerColor();
         String logDetails84 = initialProbe.asLogFragment();
         require(condition10000, "fluid drain replacement did not reach visible RT output, initialProbe=" + logDetails84 + ", finalProbe=" + finalProbe.asLogFragment() + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", activity=" + finalActivity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
         stressResult77 = new StressResult(lastSnapshot, rtCore.sceneReadiness(), finalActivity, initialProbe, finalProbe, dynamicBursts, checksums.size(), completedFrameCount, averageCompletedFps, maxPendingAge, maxCompletionStallMillis, maxSnapshotLag);
      } catch (Throwable value59) {
         if (rtCore != null) {
            try {
               rtCore.close();
            } catch (Throwable value58) {
               value59.addSuppressed(value58);
            }
         }

         throw value59;
      }

      if (rtCore != null) {
         rtCore.close();
      }

      return stressResult77;
   }

   private static RtFrameSnapshot pumpUntilProbeReady(GuardedRtCore rtCore, long firstSequence, FluidVariant expectedVariant, int maxPumpFrames, String label) throws InterruptedException {
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
            if (firstReadySequence >= 0L && snapshot.frameStateSequence() >= firstReadySequence && hasExpectedProbeCoverage(snapshot, expectedVariant)) {
               assertProbePixels(snapshot, expectedVariant, label);
               return snapshot;
            }
         }

         require(rtCore.state() == State.READY_FOR_SCENE_UPDATES, "RT core failed while waiting for " + label + ": state=" + String.valueOf(rtCore.state()) + ", readiness=" + readiness.asLogFragment() + ", activity=" + rtCore.runtimeActivity().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
         Thread.sleep(PUMP_SLEEP_MILLIS);
      }

      throw new AssertionError(label + " never produced a probe-valid RT output, firstReadySequence=" + firstReadySequence + ", lastSnapshot=" + (lastSnapshot == null ? "none" : lastSnapshot.asLogFragment()) + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", activity=" + rtCore.runtimeActivity().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
   }

   private static DrainResult pumpUntilProbeDrained(GuardedRtCore rtCore, long firstSequence, FluidVariant expectedVariant, int maxPumpFrames, long lastCompletedSequence, long lastCompletedDispatch, long lastCompletionNanos, long lastExportedSharedFrameSequence) throws InterruptedException {
      RtFrameSnapshot lastSnapshot = rtCore.latestFrameSnapshot();
      long firstReadySequence = -1L;
      long completedFrames = 0L;
      long maxPendingAge = 0L;
      long maxCompletionStallMillis = 0L;
      long maxSnapshotLag = 0L;

      for(int frame = 0; frame < maxPumpFrames; ++frame) {
         long sequence = firstSequence + (long)frame;
         rtCore.acceptFrameUpdate(RendererFrameUpdate.empty(emptyBatch(), frameState(sequence)));
         RtCore.RuntimeActivity activity = rtCore.runtimeActivity();
         RtSceneReadiness readiness = rtCore.sceneReadiness();
         long nowNanos = System.nanoTime();
         if (activity.latestCompletedFrameDispatch() > lastCompletedDispatch) {
            completedFrames += activity.latestCompletedFrameDispatch() - lastCompletedDispatch;
            lastCompletedDispatch = activity.latestCompletedFrameDispatch();
            lastCompletedSequence = activity.latestCompletedFrameStateSequence();
            lastCompletionNanos = nowNanos;
         }

         if (firstReadySequence < 0L && readiness.builtRevisionIsCurrent() && !readiness.hasPendingRtBuilds()) {
            firstReadySequence = sequence;
         }

         RtFrameSnapshot snapshot = rtCore.latestFrameSnapshot();
         if (snapshot != null) {
            lastSnapshot = snapshot;
         }

         long snapshotLag = snapshot == null ? 9223372036854775807L : Math.max(0L, sequence - snapshot.frameStateSequence());
         long completionStallMillis = Math.max(0L, nowNanos - lastCompletionNanos) / 1000000L;
         maxSnapshotLag = Math.max(maxSnapshotLag, snapshotLag);
         maxCompletionStallMillis = Math.max(maxCompletionStallMillis, completionStallMillis);
         if (readiness.builtRevisionIsCurrent() && !readiness.hasPendingRtBuilds()) {
            long pendingAge = activity.pendingFrameAgeMillis();
            maxPendingAge = Math.max(maxPendingAge, pendingAge);
            require(pendingAge <= MAX_READY_PENDING_FRAME_AGE_MILLIS, "fluid final drain has a stale pending RT frame, pendingAgeMillis=" + pendingAge + ", maxAllowedMillis=" + MAX_READY_PENDING_FRAME_AGE_MILLIS + ", readiness=" + readiness.asLogFragment() + ", activity=" + activity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
            require(completionStallMillis <= MAX_READY_COMPLETION_STALL_MILLIS, "fluid final drain completed-frame stream stalled, completionStallMillis=" + completionStallMillis + ", maxAllowedMillis=" + MAX_READY_COMPLETION_STALL_MILLIS + ", readiness=" + readiness.asLogFragment() + ", activity=" + activity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
            long completedLag = completedSequenceLag(sequence, activity.latestCompletedFrameStateSequence());
            require(completedLag <= (long)MAX_READY_SNAPSHOT_LAG, "fluid final drain completed RT output is too far behind a ready scene, sequence=" + sequence + ", latestCompletedFrameStateSequence=" + activity.latestCompletedFrameStateSequence() + ", completedLag=" + completedLag + ", maxAllowedLag=" + MAX_READY_SNAPSHOT_LAG + ", snapshot=" + (snapshot == null ? "none" : snapshot.asLogFragment()) + ", readiness=" + readiness.asLogFragment() + ", activity=" + activity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
            require(snapshot != null && snapshotLag <= (long)MAX_READY_SNAPSHOT_LAG, "fluid final drain diagnostic snapshot is too far behind a ready scene, sequence=" + sequence + ", snapshotLag=" + snapshotLag + ", maxAllowedLag=" + MAX_READY_SNAPSHOT_LAG + ", snapshot=" + (snapshot == null ? "none" : snapshot.asLogFragment()) + ", latestCompletedFrameStateSequence=" + activity.latestCompletedFrameStateSequence() + ", readiness=" + readiness.asLogFragment() + ", activity=" + activity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
         }

         if (EXPORT_SHARED_FRAME_ENABLED && activity.latestCompletedFrameStateSequence() > lastExportedSharedFrameSequence) {
            lastExportedSharedFrameSequence = RtNativeStressGuards.sampleCompletedSharedFrame(rtCore, true, activity.latestCompletedFrameStateSequence(), lastExportedSharedFrameSequence, SHARED_FRAME_EXPORT_SAMPLE_DELTA, false, "fluid final drain frame " + frame);
         }

         if (firstReadySequence >= 0L && lastSnapshot != null && lastSnapshot.frameStateSequence() >= firstReadySequence && lastSnapshot.frameStateSequence() >= sequence - (long)MAX_READY_SNAPSHOT_LAG) {
            assertProbePixels(lastSnapshot, expectedVariant, "final fluid drain");
            return new DrainResult(lastSnapshot, completedFrames, maxPendingAge, maxCompletionStallMillis, maxSnapshotLag, lastExportedSharedFrameSequence);
         }

         boolean condition10000 = rtCore.state() == State.READY_FOR_SCENE_UPDATES;
         String stateDetails10001 = String.valueOf(rtCore.state());
         require(condition10000, "RT core failed during fluid final drain: state=" + stateDetails10001 + ", readiness=" + readiness.asLogFragment() + ", activity=" + activity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
         Thread.sleep(PUMP_SLEEP_MILLIS);
      }

      String logDetails10002 = lastSnapshot == null ? "none" : lastSnapshot.asLogFragment();
      throw new AssertionError("fluid final drain never reached current RT output, lastSnapshot=" + logDetails10002 + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", activity=" + rtCore.runtimeActivity().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
   }

   private static ProbeSamples assertProbePixels(RtFrameSnapshot snapshot, FluidVariant expectedVariant, String label) {
      ProbeSamples samples = probeSamples(snapshot);
      if (expectedVariant == RtNativeFluidSceneSelfTest.FluidVariant.DRAINED) {
         require(countMatching(snapshot, samples.centerX(), samples.centerY(), 2, RtNativeFluidSceneSelfTest::isBackplate) >= 3, label + " drained fluid center did not reveal the backplate, samples=" + samples.asLogFragment() + ", window=" + sampleWindow(snapshot, samples.centerX(), samples.centerY(), 2) + ", snapshot=" + snapshot.asLogFragment());
         require(countMatching(snapshot, samples.plantX(), samples.plantY(), 2, RtNativeFluidSceneSelfTest::isBackplate) >= 3, label + " drained fluid plant probe did not reveal the backplate after the water-surface cutout was removed, samples=" + samples.asLogFragment() + ", window=" + sampleWindow(snapshot, samples.plantX(), samples.plantY(), 2) + ", snapshot=" + snapshot.asLogFragment());
      } else {
         IntPredicate predicate = expectedVariant == RtNativeFluidSceneSelfTest.FluidVariant.FULL_A ? RtNativeFluidSceneSelfTest::isFluidA : RtNativeFluidSceneSelfTest::isFluidB;
         require(countMatching(snapshot, samples.centerX(), samples.centerY(), 2, predicate) >= 3, label + " fluid center was not shaded from the expected water material, expectedVariant=" + String.valueOf(expectedVariant) + ", samples=" + samples.asLogFragment() + ", window=" + sampleWindow(snapshot, samples.centerX(), samples.centerY(), 2) + ", snapshot=" + snapshot.asLogFragment());
         requireFluidProbeNotNearBlack(snapshot, samples.centerX(), samples.centerY(), samples, label, "center");
         require(countMatching(snapshot, samples.centerX(), samples.centerY(), 2, RtNativeFluidSceneSelfTest::isSeagrassCutout) == 0, label + " translucent water center was routed as masked cutout and revealed submerged seagrass, samples=" + samples.asLogFragment() + ", window=" + sampleWindow(snapshot, samples.centerX(), samples.centerY(), 2) + ", snapshot=" + snapshot.asLogFragment());
         require(countMatching(snapshot, samples.plantX(), samples.plantY(), 2, RtNativeFluidSceneSelfTest::isSeagrassCutout) >= 3, label + " water-surface cutout plant did not render as an opaque masked texel, samples=" + samples.asLogFragment() + ", window=" + sampleWindow(snapshot, samples.plantX(), samples.plantY(), 2) + ", snapshot=" + snapshot.asLogFragment());
      }

      require(countMatching(snapshot, samples.shoreX(), samples.shoreY(), 2, RtNativeFluidSceneSelfTest::isBackplate) >= 3, label + " fluid boundary did not expose the solid backplate, samples=" + samples.asLogFragment() + ", window=" + sampleWindow(snapshot, samples.shoreX(), samples.shoreY(), 2) + ", snapshot=" + snapshot.asLogFragment());
      require(samples.centerColor() != RtSceneMaterialTable.missRgba8() && samples.shoreColor() != RtSceneMaterialTable.missRgba8() && samples.plantColor() != RtSceneMaterialTable.missRgba8(), label + " fluid probe collapsed into miss/sky blue, samples=" + samples.asLogFragment() + ", snapshot=" + snapshot.asLogFragment());
      return samples;
   }

   private static boolean hasExpectedProbeCoverage(RtFrameSnapshot snapshot, FluidVariant expectedVariant) {
      ProbeSamples samples = probeSamples(snapshot);
      if (expectedVariant != RtNativeFluidSceneSelfTest.FluidVariant.DRAINED) {
         IntPredicate predicate = expectedVariant == RtNativeFluidSceneSelfTest.FluidVariant.FULL_A ? RtNativeFluidSceneSelfTest::isFluidA : RtNativeFluidSceneSelfTest::isFluidB;
         return countMatching(snapshot, samples.centerX(), samples.centerY(), 2, predicate) >= 3;
      } else {
         return countMatching(snapshot, samples.centerX(), samples.centerY(), 2, RtNativeFluidSceneSelfTest::isBackplate) >= 3 && countMatching(snapshot, samples.plantX(), samples.plantY(), 2, RtNativeFluidSceneSelfTest::isBackplate) >= 3;
      }
   }

   private static ProbeSamples assertSustainedProbePixels(RtFrameSnapshot snapshot, String label) {
      ProbeSamples samples = probeSamples(snapshot);
      require(countMatching(snapshot, samples.centerX(), samples.centerY(), 2, (pixel) -> isFluidA(pixel) || isFluidB(pixel)) >= 3, label + " fluid center was neither valid water material variant; a water-surface plant may be occluding the fluid hole, samples=" + samples.asLogFragment() + ", window=" + sampleWindow(snapshot, samples.centerX(), samples.centerY(), 2) + ", snapshot=" + snapshot.asLogFragment());
      requireFluidProbeNotNearBlack(snapshot, samples.centerX(), samples.centerY(), samples, label, "center");
      require(countMatching(snapshot, samples.centerX(), samples.centerY(), 2, RtNativeFluidSceneSelfTest::isSeagrassCutout) == 0, label + " translucent water center was routed as masked cutout and revealed submerged seagrass, samples=" + samples.asLogFragment() + ", window=" + sampleWindow(snapshot, samples.centerX(), samples.centerY(), 2) + ", snapshot=" + snapshot.asLogFragment());
      require(countMatching(snapshot, samples.shoreX(), samples.shoreY(), 2, RtNativeFluidSceneSelfTest::isBackplate) >= 3, label + " fluid boundary did not expose the solid backplate, samples=" + samples.asLogFragment() + ", window=" + sampleWindow(snapshot, samples.shoreX(), samples.shoreY(), 2) + ", snapshot=" + snapshot.asLogFragment());
      require(countMatching(snapshot, samples.plantX(), samples.plantY(), 2, RtNativeFluidSceneSelfTest::isSeagrassCutout) >= 3, label + " water-surface cutout plant did not stay opaque over fluid, samples=" + samples.asLogFragment() + ", window=" + sampleWindow(snapshot, samples.plantX(), samples.plantY(), 2) + ", snapshot=" + snapshot.asLogFragment());
      require(samples.centerColor() != RtSceneMaterialTable.missRgba8() && samples.shoreColor() != RtSceneMaterialTable.missRgba8() && samples.plantColor() != RtSceneMaterialTable.missRgba8(), label + " fluid probe collapsed into miss/sky blue, samples=" + samples.asLogFragment() + ", snapshot=" + snapshot.asLogFragment());
      return samples;
   }

   private static void requireFluidProbeNotNearBlack(RtFrameSnapshot snapshot, int centerX, int centerY, ProbeSamples samples, String label, String probeName) {
      require(countMatching(snapshot, centerX, centerY, 2, RtNativeFluidSceneSelfTest::isNearBlackWaterFailure) == 0, label + " fluid " + probeName + " contains near-black water pixels, samples=" + samples.asLogFragment() + ", window=" + sampleWindow(snapshot, centerX, centerY, 2) + ", snapshot=" + snapshot.asLogFragment());
   }

   private static ProbeSamples probeSamples(RtFrameSnapshot snapshot) {
      SectionKey key = probeKey();
      int centerX = pixelXForWorld(snapshot.width(), snapshot.height(), (float)key.x() * 16.0F + 8.0F, 15.92F);
      int centerY = pixelYForWorld(snapshot.width(), snapshot.height(), (float)key.y() * 16.0F + 8.0F, 15.92F);
      int shoreX = pixelXForWorld(snapshot.width(), snapshot.height(), (float)key.x() * 16.0F + 1.25F, 15.92F);
      int shoreY = pixelYForWorld(snapshot.width(), snapshot.height(), (float)key.y() * 16.0F + 1.25F, 15.92F);
      int plantX = pixelXForWorld(snapshot.width(), snapshot.height(), (float)key.x() * 16.0F + 3.0F, 15.92F);
      int plantY = pixelYForWorld(snapshot.width(), snapshot.height(), (float)key.y() * 16.0F + 8.0F, 15.92F);
      byte[] pixels = snapshot.copyRgba8();
      return new ProbeSamples(centerX, centerY, RtFrameSnapshot.pixel(pixels, snapshot.width(), centerX, centerY), shoreX, shoreY, RtFrameSnapshot.pixel(pixels, snapshot.width(), shoreX, shoreY), plantX, plantY, RtFrameSnapshot.pixel(pixels, snapshot.width(), plantX, plantY));
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

   private static boolean isFluidA(int pixel) {
      return red(pixel) <= 80 && green(pixel) >= 95 && blue(pixel) >= 100 && green(pixel) > red(pixel) + 25 && blue(pixel) > red(pixel) + 60 && green(pixel) > blue(pixel) + 35;
   }

   private static boolean isFluidB(int pixel) {
      return red(pixel) >= 20 && red(pixel) <= 100 && green(pixel) >= 100 && green(pixel) <= 200 && blue(pixel) >= 120 && blue(pixel) <= 220 && blue(pixel) > red(pixel) + 70 && green(pixel) > red(pixel) + 70 && Math.abs(green(pixel) - blue(pixel)) <= 45;
   }

   private static boolean isBackplate(int pixel) {
      return red(pixel) >= 65 && red(pixel) <= 190 && green(pixel) >= 30 && green(pixel) <= 120 && blue(pixel) <= 90 && red(pixel) > green(pixel) && green(pixel) > blue(pixel);
   }

   private static boolean isSeagrassCutout(int pixel) {
      return green(pixel) >= 120 && red(pixel) <= 90 && blue(pixel) <= 120 && green(pixel) > red(pixel) + 55 && green(pixel) > blue(pixel) + 45;
   }

   private static boolean isNearBlackWaterFailure(int pixel) {
      return red(pixel) <= 24 && green(pixel) <= 36 && blue(pixel) <= 52;
   }

   private static long completedSequenceLag(long sequence, long latestCompletedSequence) {
      return latestCompletedSequence < 0L ? 9223372036854775807L : Math.max(0L, sequence - latestCompletedSequence);
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

   private static Map<SectionKey, PreparedSection> buildPreparedSections(List<SectionKey> keys, RtTextureCatalog.TestTextureScope textures, FluidVariant probeVariant) {
      Map<SectionKey, PreparedSection> sections = new LinkedHashMap<>();
      SectionKey probe = probeKey();

      for(int index = 0; index < keys.size(); ++index) {
         SectionKey key = (SectionKey)keys.get(index);
         sections.put(key, key.equals(probe) ? probePreparedSection(key, textures, probeVariant) : pressurePreparedSection(key, textures, index));
      }

      return sections;
   }

   private static Map<SectionKey, PreparedSection> mutationPreparedSections(List<SectionKey> keys, SectionKey probeKey, RtTextureCatalog.TestTextureScope textures, int burst, FluidVariant probeVariant) {
      Map<SectionKey, PreparedSection> sections = new LinkedHashMap<>();
      sections.put(probeKey, probePreparedSection(probeKey, textures, probeVariant));
      int offset = Math.floorMod(burst * 67, keys.size());

      for(int index = 0; index < Math.min(MUTATIONS_PER_BURST, keys.size()); ++index) {
         SectionKey key = (SectionKey)keys.get((offset + index * 19) % keys.size());
         if (!key.equals(probeKey)) {
            sections.put(key, pressurePreparedSection(key, textures, burst * 8191 + index));
         }
      }

      return sections;
   }

   private static PreparedSection probePreparedSection(SectionKey key, RtTextureCatalog.TestTextureScope textures, FluidVariant variant) {
      MeshBuilder builder = new MeshBuilder(key);
      builder.addPositiveZSolidQuad(0.0F, 0.0F, 16.0F, 16.0F, 15.58F, textures.textureId("rtrenderer:selftest/fluid_backplate"));
      if (variant.fluidPresent()) {
         builder.addPositiveZFluidQuad(2.0F, 2.0F, 14.0F, 14.0F, 15.92F, textures.textureId(variant.textureName()), 8);
         builder.addCutoutQuad(new float[]{6.0F, 6.0F, 15.84F, 10.0F, 6.0F, 15.84F, 10.0F, 10.0F, 15.84F, 6.0F, 10.0F, 15.84F}, textures.textureId("rtrenderer:selftest/fluid_seagrass_cutout"), standardUvs());
         builder.addCutoutQuad(new float[]{1.0F, 4.5F, 15.99F, 5.5F, 4.5F, 15.99F, 5.5F, 11.5F, 15.99F, 1.0F, 11.5F, 15.99F}, textures.textureId("rtrenderer:selftest/fluid_seagrass_cutout"), standardUvs());
      }

      return new PreparedSection(builder.build(), variant.fluidPresent());
   }

   private static PreparedSection pressurePreparedSection(SectionKey key, RtTextureCatalog.TestTextureScope textures, int variant) {
      MeshBuilder builder = new MeshBuilder(key);
      int waterTexture = textures.textureId((variant & 1) == 0 ? "rtrenderer:selftest/fluid_water_a" : "rtrenderer:selftest/fluid_water_b");
      int alternateWaterTexture = textures.textureId((variant & 1) == 0 ? "rtrenderer:selftest/fluid_water_b" : "rtrenderer:selftest/fluid_water_a");
      builder.addPositiveZSolidQuad(0.0F, 0.0F, 16.0F, 16.0F, 15.58F, textures.textureId("rtrenderer:selftest/fluid_backplate"));
      builder.addPositiveZFluidQuad(1.5F, 1.5F, 7.6F, 7.6F, 15.92F, waterTexture, 8);
      builder.addPositiveZFluidQuad(8.4F, 1.5F, 14.5F, 7.6F, 15.92F, alternateWaterTexture, 6);
      builder.addPositiveZFluidQuad(1.5F, 8.4F, 7.6F, 14.5F, 15.92F, alternateWaterTexture, 5);
      builder.addPositiveZFluidQuad(8.4F, 8.4F, 14.5F, 14.5F, 15.92F, waterTexture, 8);
      builder.addCutoutQuad(new float[]{2.0F, 3.0F, 15.98F, 14.0F, 3.0F, 15.98F, 14.0F, 4.8F, 15.98F, 2.0F, 4.8F, 15.98F}, textures.textureId("rtrenderer:selftest/fluid_foam_cutout"), rotatedUvs(variant));
      builder.addCutoutQuad(new float[]{4.0F, 12.5F, 15.97F, 12.0F, 2.0F, 15.52F, 12.0F, 2.0F, 14.92F, 4.0F, 12.5F, 15.37F}, textures.textureId("rtrenderer:selftest/fluid_foam_cutout"), rotatedUvs(variant + 1));
      return new PreparedSection(builder.build(), true);
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

   private static SectionVoxelSnapshot sectionSnapshot(SectionKey key, boolean fluidPresent) {
      int[] ids = new int[4096];
      byte[] fluids = new byte[4096];
      int[] mapColors = new int[4096];
      byte[] lightEmissions = new byte[4096];
      byte[] flags = new byte[4096];
      Arrays.fill(ids, fluidPresent ? 91 : 1);
      Arrays.fill(mapColors, fluidPresent ? 2781147 : 11822128);
      byte materialFlags = (byte)(17 | (fluidPresent ? 4 : 8));
      Arrays.fill(flags, materialFlags);
      if (fluidPresent) {
         Arrays.fill(fluids, (byte)8);
      }

      return new SectionVoxelSnapshot(key, ids, fluids, mapColors, lightEmissions, flags, false, fluidPresent);
   }

   private static SceneUpdateBatch emptyBatch() {
      return new SceneUpdateBatch(Set.of(), Set.of(), Set.of(), Set.of(), Map.of(), false, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
   }

   private static WaterloggedStressStats assertWaterloggedMeshingStressContract() {
      SectionMesher mesher = new SectionMesher();
      SectionMeshBuilder meshBuilder = new SectionMeshBuilder();
      long waterloggedVoxels = 0L;
      long plantFaces = 0L;
      long fluidTopFaces = 0L;
      long meshFaces = 0L;
      int maxSectionFaces = 0;
      int maxMeshFaces = 0;

      for(int section = 0; section < WATERLOGGED_STRESS_SECTIONS; ++section) {
         SectionKey key = new SectionKey(section % 64, section / 64, 900 + section % 17);
         SectionVoxelSnapshot snapshot = waterloggedStressSection(key, section);
         SectionGeometrySnapshot geometry = mesher.build(snapshot, 64, 64);
         maxSectionFaces = Math.max(maxSectionFaces, geometry.faceCount());

         for(SectionFace face : geometry.faces()) {
            if (isWaterloggedPlantState(face.voxelTypeId())) {
               ++plantFaces;
               boolean condition10000 = face.mediumAmount() == 0 && (face.materialFlags() & 4) == 0;
               String details10001 = String.valueOf(key);
               require(condition10000, "waterlogged stress leaked fluid material into plant face, section=" + details10001 + ", face=" + String.valueOf(face));
            }

            if (face.voxelTypeId() == 91 && face.mediumAmount() == 8 && face.direction() == FaceDirection.POSITIVE_Y && (face.materialFlags() & 4) != 0) {
               ++fluidTopFaces;
            }
         }

         SectionTriangleMesh mesh = meshBuilder.build(geometry);
         int[] blockIds = mesh.faceVoxelStateIds();
         byte[] fluids = mesh.faceFluidAmounts();
         byte[] flags = mesh.faceMaterialFlags();

         for(int face = 0; face < mesh.faceCount(); ++face) {
            if (isWaterloggedPlantState(blockIds[face])) {
               require(Byte.toUnsignedInt(fluids[face]) == 0, "waterlogged stress mesh leaked fluid amount into plant face");
               require((Byte.toUnsignedInt(flags[face]) & 4) == 0, "waterlogged stress mesh leaked liquid flag into plant face");
            }
         }

         waterloggedVoxels += waterloggedVoxelCount(section);
         meshFaces += (long)mesh.faceCount();
         maxMeshFaces = Math.max(maxMeshFaces, mesh.faceCount());
      }

      require(plantFaces >= waterloggedVoxels, "waterlogged stress did not keep plant block faces alive, plantFaces=" + plantFaces + ", waterloggedVoxels=" + waterloggedVoxels);
      require(fluidTopFaces >= waterloggedVoxels, "waterlogged stress did not emit independent fluid top faces, fluidTopFaces=" + fluidTopFaces + ", waterloggedVoxels=" + waterloggedVoxels);
      require(maxSectionFaces < 16384, "waterlogged stress generated pathological section face count, maxSectionFaces=" + maxSectionFaces);
      return new WaterloggedStressStats(WATERLOGGED_STRESS_SECTIONS, waterloggedVoxels, plantFaces, fluidTopFaces, meshFaces, maxSectionFaces, maxMeshFaces);
   }

   private static FluidFamilyStressStats assertFluidFamilyMeshingStressContract() {
      SectionMesher mesher = new SectionMesher();
      SectionMeshBuilder meshBuilder = new SectionMeshBuilder();
      long fluidVoxels = 0L;
      long geometryFaces = 0L;
      long meshFaces = 0L;
      int maxSectionFaces = 0;
      int maxMeshFaces = 0;

      for(int section = 0; section < FLUID_FAMILY_STRESS_SECTIONS; ++section) {
         SectionKey key = new SectionKey(section % 64, section / 64, 1200 + section % 23);
         SectionVoxelSnapshot snapshot = fluidFamilyStressSection(key, section);
         SectionEncodedSnapshot encoded = SectionEncodedSnapshot.encode(snapshot);
         SectionGeometrySnapshot geometry = mesher.build(snapshot, encoded.paletteSize(), encoded.runCount());
         SectionTriangleMesh mesh = meshBuilder.build(geometry);
         long sectionFluidVoxels = fluidFamilyVoxelCount(section);
         fluidVoxels += sectionFluidVoxels;
         geometryFaces += (long)geometry.faceCount();
         meshFaces += (long)mesh.faceCount();
         maxSectionFaces = Math.max(maxSectionFaces, geometry.faceCount());
         maxMeshFaces = Math.max(maxMeshFaces, mesh.faceCount());
         require(encoded.mediumTypeIdAt(0, 0, 0) == 7, "fluid family stress lost fluid type id through palette/RLE");
         boolean condition10000 = geometry.faceCount() < 1600;
         String details10001 = String.valueOf(key);
         require(condition10000, "fluid family stress generated pathological internal same-fluid faces, section=" + details10001 + ", faceCount=" + geometry.faceCount() + ", fluidVoxels=" + sectionFluidVoxels);
         require(mesh.faceCount() == geometry.faceCount(), "fluid family stress mesh must preserve geometry face count");
      }

      return new FluidFamilyStressStats(FLUID_FAMILY_STRESS_SECTIONS, fluidVoxels, geometryFaces, meshFaces, maxSectionFaces, maxMeshFaces);
   }

   private static FluidNeighborhoodStressStats assertFluidNeighborhoodMeshingStressContract() {
      SectionMesher mesher = new SectionMesher();
      int checkedCorners = 0;
      int diagonalInfluencedCorners = 0;
      int rebuiltDependents = 0;

      for(int section = 0; section < 128; ++section) {
         SectionKey centerKey = new SectionKey(1600 + section * 3, section & 7, 1700 + section * 5);
         SectionVoxelSnapshot center = singleFluidVoxel(centerKey, 15, 7, 15, 4);
         SectionVoxelSnapshot east = singleFluidVoxel(new SectionKey(centerKey.x() + 1, centerKey.y(), centerKey.z()), 0, 7, 15, 2);
         SectionVoxelSnapshot south = singleFluidVoxel(new SectionKey(centerKey.x(), centerKey.y(), centerKey.z() + 1), 15, 7, 0, 2);
         SectionVoxelSnapshot diagonal = singleFluidVoxel(new SectionKey(centerKey.x() + 1, centerKey.y(), centerKey.z() + 1), 0, 7, 0, 8);
         SectionGeometrySnapshot complete = mesher.build(center, 4, 4, SectionNeighborhood.fromSnapshots(centerKey, Map.of(east.key(), east, south.key(), south, diagonal.key(), diagonal)));
         SectionGeometrySnapshot missingDiagonal = mesher.build(center, 3, 3, SectionNeighborhood.fromSnapshots(centerKey, Map.of(east.key(), east, south.key(), south)));
         SectionFace completeTop = fluidTopFace(complete);
         SectionFace missingDiagonalTop = fluidTopFace(missingDiagonal);
         ++checkedCorners;
         if (completeTop.fluidHeight1() > missingDiagonalTop.fluidHeight1()) {
            ++diagonalInfluencedCorners;
         }

         SceneDatabase database = new SceneDatabase();
         database.replaceChunkSnapshot(new ChunkSnapshot(centerKey.chunkKey(), centerKey.y(), List.of(center)));
         database.drainPendingUpdates();
         database.replaceChunkSnapshot(new ChunkSnapshot(diagonal.key().chunkKey(), diagonal.key().y(), List.of(diagonal)));
         SceneUpdateBatch batch = database.drainPendingUpdates();
         if (batch.sectionSnapshots().containsKey(centerKey) && database.snapshotSectionNeighborhood(centerKey).snapshots().containsKey(diagonal.key())) {
            ++rebuiltDependents;
         }
      }

      require(diagonalInfluencedCorners == checkedCorners, "fluid neighborhood stress lost cross-section diagonal corner heights, checkedCorners=" + checkedCorners + ", diagonalInfluencedCorners=" + diagonalInfluencedCorners);
      require(rebuiltDependents == checkedCorners, "fluid neighborhood stress did not dirty all diagonal corner dependents, checkedCorners=" + checkedCorners + ", rebuiltDependents=" + rebuiltDependents);
      return new FluidNeighborhoodStressStats(checkedCorners, diagonalInfluencedCorners, rebuiltDependents);
   }

   private static SectionFace fluidTopFace(SectionGeometrySnapshot geometry) {
      return (SectionFace)geometry.faces().stream().filter((face) -> face.direction() == FaceDirection.POSITIVE_Y).filter((face) -> face.mediumAmount() > 0).findFirst().orElseThrow(() -> new AssertionError("fluid neighborhood stress top face missing: " + String.valueOf(geometry.key())));
   }

   private static SectionVoxelSnapshot singleFluidVoxel(SectionKey key, int x, int y, int z, int amount) {
      int[] voxelTypeIds = new int[4096];
      int[] mediumStateIds = new int[4096];
      int[] mediumTypeIds = new int[4096];
      byte[] mediumAmounts = new byte[4096];
      int[] mapColors = new int[4096];
      int[] fluidMapColors = new int[4096];
      byte[] lightEmissions = new byte[4096];
      byte[] materialFlags = new byte[4096];
      byte[] shadeBrightnesses = new byte[4096];
      Arrays.fill(shadeBrightnesses, (byte)-1);
      int index = SectionVoxelSnapshot.localBlockIndex(x, y, z);
      voxelTypeIds[index] = amount == 8 ? 91 : 92;
      mediumStateIds[index] = voxelTypeIds[index];
      mediumTypeIds[index] = 7;
      mediumAmounts[index] = (byte)amount;
      mapColors[index] = SectionVoxelSnapshot.packMapColorAndLight(2781147, 15, 0);
      fluidMapColors[index] = mapColors[index];
      materialFlags[index] = 52;
      return new SectionVoxelSnapshot(key, voxelTypeIds, mediumStateIds, mediumTypeIds, mediumAmounts, mapColors, fluidMapColors, lightEmissions, materialFlags, shadeBrightnesses, false, true);
   }

   private static SectionVoxelSnapshot waterloggedStressSection(SectionKey key, int sectionOrdinal) {
      int[] voxelTypeIds = new int[4096];
      int[] mediumStateIds = new int[4096];
      byte[] mediumAmounts = new byte[4096];
      int[] mapColors = new int[4096];
      byte[] lightEmissions = new byte[4096];
      byte[] materialFlags = new byte[4096];

      for(int y = 0; y < 16; ++y) {
         for(int z = 0; z < 16; ++z) {
            for(int x = 0; x < 16; ++x) {
               int index = SectionVoxelSnapshot.localBlockIndex(x, y, z);
               if (y < 8) {
                  voxelTypeIds[index] = 91;
                  mediumStateIds[index] = 91;
                  mediumAmounts[index] = 8;
                  materialFlags[index] = 20;
               } else if (y == 8 && isWaterloggedStressPlant(x, z, sectionOrdinal)) {
                  voxelTypeIds[index] = waterloggedPlantStateId(x, z, sectionOrdinal);
                  mediumStateIds[index] = 91;
                  mediumAmounts[index] = 8;
                  materialFlags[index] = 17;
               } else if (y == 8 && (x * 31 + z * 17 + sectionOrdinal & 7) == 0) {
                  voxelTypeIds[index] = 1;
                  materialFlags[index] = 25;
               } else {
                  materialFlags[index] = 2;
               }
            }
         }
      }

      return new SectionVoxelSnapshot(key, voxelTypeIds, mediumStateIds, mediumAmounts, mapColors, lightEmissions, materialFlags, false, true);
   }

   private static SectionVoxelSnapshot fluidFamilyStressSection(SectionKey key, int sectionOrdinal) {
      int[] voxelTypeIds = new int[4096];
      int[] mediumStateIds = new int[4096];
      int[] mediumTypeIds = new int[4096];
      byte[] mediumAmounts = new byte[4096];
      int[] mapColors = new int[4096];
      int[] fluidMapColors = new int[4096];
      byte[] lightEmissions = new byte[4096];
      byte[] materialFlags = new byte[4096];
      byte[] shadeBrightnesses = new byte[4096];
      Arrays.fill(shadeBrightnesses, (byte)-1);

      for(int y = 0; y < 8; ++y) {
         for(int z = 0; z < 16; ++z) {
            for(int x = 0; x < 16; ++x) {
               int index = SectionVoxelSnapshot.localBlockIndex(x, y, z);
               int legacyState = (x + z + y + sectionOrdinal & 1) == 0 ? 91 : 92;
               int amount = legacyState == 91 ? 8 : 2 + (x + z + sectionOrdinal & 3);
               voxelTypeIds[index] = legacyState;
               mediumStateIds[index] = legacyState;
               mediumTypeIds[index] = 7;
               mediumAmounts[index] = (byte)amount;
               mapColors[index] = SectionVoxelSnapshot.packMapColorAndLight(2781147, 15, 0);
               fluidMapColors[index] = mapColors[index];
               materialFlags[index] = 52;
            }
         }
      }

      for(int y = 8; y < 16; ++y) {
         for(int z = 0; z < 16; ++z) {
            for(int x = 0; x < 16; ++x) {
               int index = SectionVoxelSnapshot.localBlockIndex(x, y, z);
               materialFlags[index] = 2;
            }
         }
      }

      return new SectionVoxelSnapshot(key, voxelTypeIds, mediumStateIds, mediumTypeIds, mediumAmounts, mapColors, fluidMapColors, lightEmissions, materialFlags, shadeBrightnesses, false, true);
   }

   private static long fluidFamilyVoxelCount(int sectionOrdinal) {
      return 2048L;
   }

   private static long waterloggedVoxelCount(int sectionOrdinal) {
      long count = 0L;

      for(int z = 0; z < 16; ++z) {
         for(int x = 0; x < 16; ++x) {
            if (isWaterloggedStressPlant(x, z, sectionOrdinal)) {
               ++count;
            }
         }
      }

      return count;
   }

   private static boolean isWaterloggedStressPlant(int x, int z, int sectionOrdinal) {
      return (x * 13 + z * 7 + sectionOrdinal & 3) == 0;
   }

   private static int waterloggedPlantStateId(int x, int z, int sectionOrdinal) {
      return 2001 + Math.floorMod(x + z + sectionOrdinal, 3);
   }

   private static boolean isWaterloggedPlantState(int voxelTypeId) {
      return voxelTypeId >= 2001 && voxelTypeId <= 2003;
   }

   private static List<RtTextureCatalog.TestTexture> testTextures() {
      return List.of(new RtTextureCatalog.TestTexture("rtrenderer:selftest/fluid_water_a", 16, 16, solidTexture(0, 190, 220, 96, 16, 16)), new RtTextureCatalog.TestTexture("rtrenderer:selftest/fluid_water_b", 16, 16, solidTexture(60, 80, 255, 96, 16, 16)), new RtTextureCatalog.TestTexture("rtrenderer:selftest/fluid_backplate", 8, 8, solidTexture(180, 100, 48, 255, 8, 8)), new RtTextureCatalog.TestTexture("rtrenderer:selftest/fluid_foam_cutout", 8, 8, foamCutoutTexture()), new RtTextureCatalog.TestTexture("rtrenderer:selftest/fluid_seagrass_cutout", 8, 8, seagrassCutoutTexture()));
   }

   private static int[] foamCutoutTexture() {
      int[] pixels = new int[64];

      for(int y = 0; y < 8; ++y) {
         for(int x = 0; x < 8; ++x) {
            boolean visible = x == y || x + y == 7 || y == 3 && x >= 2 && x <= 5;
            pixels[y * 8 + x] = visible ? rgba8(232, 248, 255, 255) : rgba8(0, 0, 0, 0);
         }
      }

      return pixels;
   }

   private static int[] seagrassCutoutTexture() {
      int[] pixels = new int[64];

      for(int y = 0; y < 8; ++y) {
         for(int x = 0; x < 8; ++x) {
            boolean blade = x >= 2 && x <= 5 || y >= 4 && (x == 1 || x == 6);
            pixels[y * 8 + x] = blade ? rgba8(36, 214, 78, 255) : rgba8(0, 0, 0, 0);
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
      WATERLOGGED_STRESS_SECTIONS = intProperty("top.ceroxe.rt.rt.fluidStress.waterloggedSections", 1024);
      FLUID_FAMILY_STRESS_SECTIONS = intProperty("top.ceroxe.rt.rt.fluidStress.fluidFamilySections", 512);
      MAX_INITIAL_READY_PUMP_FRAMES = intProperty("top.ceroxe.rt.rt.fluidStress.maxInitialReadyPumpFrames", 3600);
      SUSTAINED_FRAMES = intProperty("top.ceroxe.rt.rt.fluidStress.sustainedFrames", 180);
      MAX_FINAL_DRAIN_FRAMES = intProperty("top.ceroxe.rt.rt.fluidStress.maxFinalDrainFrames", 2400);
      MUTATION_PERIOD_FRAMES = intProperty("top.ceroxe.rt.rt.fluidStress.mutationPeriodFrames", 6);
      MUTATIONS_PER_BURST = intProperty("top.ceroxe.rt.rt.fluidStress.mutationsPerBurst", 64);
      MAX_READY_SNAPSHOT_LAG = intProperty("top.ceroxe.rt.rt.fluidStress.maxReadySnapshotLag", 180);
      READBACK_SAMPLE_INTERVAL = intProperty("top.ceroxe.rt.rt.fluidStress.readbackSampleInterval", 8);
      MAX_READY_PENDING_FRAME_AGE_MILLIS = longProperty("top.ceroxe.rt.rt.fluidStress.maxReadyPendingFrameAgeMillis", 1500L);
      MAX_READY_COMPLETION_STALL_MILLIS = longProperty("top.ceroxe.rt.rt.fluidStress.maxReadyCompletionStallMillis", 1500L);
      PUMP_SLEEP_MILLIS = longProperty("top.ceroxe.rt.rt.fluidStress.pumpSleepMillis", 6L);
      MIN_COMPLETED_FPS = doubleProperty("top.ceroxe.rt.rt.fluidStress.minCompletedFps", 1.5);
      EXPORT_SHARED_FRAME_ENABLED = booleanProperty("top.ceroxe.rt.rt.fluidStress.exportSharedFrame.enabled", true);
      SHARED_FRAME_EXPORT_SAMPLE_DELTA = intProperty("top.ceroxe.rt.rt.fluidStress.sharedFrameExportSampleDelta", 30);
      SNAPSHOT_PATH = Path.of(System.getProperty("java.io.tmpdir"), "rtrenderer-native-fluid-scene.png");
   }

   private static enum FluidVariant {
      FULL_A("rtrenderer:selftest/fluid_water_a", true),
      FULL_B("rtrenderer:selftest/fluid_water_b", true),
      DRAINED("", false);

      private final String textureName;
      private final boolean fluidPresent;

      private FluidVariant(String textureName, boolean fluidPresent) {
         this.textureName = textureName;
         this.fluidPresent = fluidPresent;
      }

      private String textureName() {
         if (!this.fluidPresent) {
            throw new IllegalStateException("drained fluid variant has no water texture");
         } else {
            return this.textureName;
         }
      }

      private boolean fluidPresent() {
         return this.fluidPresent;
      }
   }

   private static record PreparedSection(SectionTriangleMesh mesh, boolean fluidPresent) {
      private PreparedSection(SectionTriangleMesh mesh, boolean fluidPresent) {
         mesh = (SectionTriangleMesh)Objects.requireNonNull(mesh, "mesh");
         this.mesh = mesh;
         this.fluidPresent = fluidPresent;
      }
   }

   private static record StressResult(RtFrameSnapshot lastSnapshot, RtSceneReadiness readiness, RtCore.RuntimeActivity activity, ProbeSamples initialProbe, ProbeSamples finalProbe, int dynamicBursts, int distinctChecksums, long completedFrames, double averageCompletedFps, long maxReadyPendingFrameAgeMillis, long maxReadyCompletionStallMillis, long maxReadySnapshotLag) {
   }

   private static record DrainResult(RtFrameSnapshot snapshot, long completedFrames, long maxPendingFrameAgeMillis, long maxCompletionStallMillis, long maxSnapshotLag, long lastExportedSharedFrameSequence) {
   }

   private static record ProbeSamples(int centerX, int centerY, int centerColor, int shoreX, int shoreY, int shoreColor, int plantX, int plantY, int plantColor) {
      private String asLogFragment() {
         int value10000 = this.centerX;
         return "probeSamples{center=(" + value10000 + "," + this.centerY + "=" + RtFrameSnapshot.hex(this.centerColor) + "), shore=(" + this.shoreX + "," + this.shoreY + "=" + RtFrameSnapshot.hex(this.shoreColor) + "), plant=(" + this.plantX + "," + this.plantY + "=" + RtFrameSnapshot.hex(this.plantColor) + ")}";
      }
   }

   private static record WaterloggedStressStats(int sections, long waterloggedVoxels, long plantFaces, long fluidTopFaces, long meshFaces, int maxSectionFaces, int maxMeshFaces) {
      private String asLogFragment() {
         return "waterloggedStress{sections=" + this.sections + ", waterloggedVoxels=" + this.waterloggedVoxels + ", plantFaces=" + this.plantFaces + ", fluidTopFaces=" + this.fluidTopFaces + ", meshFaces=" + this.meshFaces + ", maxSectionFaces=" + this.maxSectionFaces + ", maxMeshFaces=" + this.maxMeshFaces + "}";
      }
   }

   private static record FluidFamilyStressStats(int sections, long fluidVoxels, long geometryFaces, long meshFaces, int maxSectionFaces, int maxMeshFaces) {
      private String asLogFragment() {
         return "fluidFamilyStress{sections=" + this.sections + ", fluidVoxels=" + this.fluidVoxels + ", geometryFaces=" + this.geometryFaces + ", meshFaces=" + this.meshFaces + ", maxSectionFaces=" + this.maxSectionFaces + ", maxMeshFaces=" + this.maxMeshFaces + "}";
      }
   }

   private static record FluidNeighborhoodStressStats(int checkedCorners, int diagonalInfluencedCorners, int rebuiltDependents) {
      private String asLogFragment() {
         return "fluidNeighborhoodStress{checkedCorners=" + this.checkedCorners + ", diagonalInfluencedCorners=" + this.diagonalInfluencedCorners + ", rebuiltDependents=" + this.rebuiltDependents + "}";
      }
   }

   private static final class FluidSceneState {
      private final SceneDatabase database = new SceneDatabase();
      private final SectionMaterialCache materialCache = new SectionMaterialCache();
      private final SectionGeometryCache geometryCache = SectionGeometryCache.transientProductionStaging();
      private final SectionMeshCache meshCache = new SectionMeshCache();
      private long snapshotNanos;
      private long databaseReplaceNanos;
      private long databaseDrainNanos;
      private long materialNanos;
      private long geometryNanos;
      private long meshNanos;

      private static SceneUpdateBatch preparedSectionBatch(Map<SectionKey, PreparedSection> sections) {
         Set<SectionKey> dirtySections = Set.copyOf(sections.keySet());
         Set<ChunkKey> dirtyChunks = new LinkedHashSet<>();
         Map<SectionKey, SectionVoxelSnapshot> snapshots = new LinkedHashMap<>();

         for(Map.Entry<SectionKey, PreparedSection> entry : sections.entrySet()) {
            dirtyChunks.add(((SectionKey)entry.getKey()).chunkKey());
            snapshots.put((SectionKey)entry.getKey(), RtNativeFluidSceneSelfTest.sectionSnapshot((SectionKey)entry.getKey(), ((PreparedSection)entry.getValue()).fluidPresent()));
         }

         return new SceneUpdateBatch(dirtySections, dirtyChunks, Set.of(), Set.of(), snapshots, false, (long)dirtySections.size(), (long)dirtySections.size(), 0L, 0L, 0L, 0L, 0L, SceneUpdateBatch.sourceFlagsForBlockMutation());
      }

      private RendererFrameUpdate initialUpdate(Map<SectionKey, PreparedSection> sections, RendererFrameState frameState) {
         Map<ChunkKey, List<SectionVoxelSnapshot>> sectionsByChunk = new LinkedHashMap<>();

         for(Map.Entry<SectionKey, PreparedSection> entry : sections.entrySet()) {
            sectionsByChunk.computeIfAbsent(entry.getKey().chunkKey(), ignored -> new ArrayList<>()).add(RtNativeFluidSceneSelfTest.sectionSnapshot(entry.getKey(), entry.getValue().fluidPresent()));
         }

         for(Map.Entry<ChunkKey, List<SectionVoxelSnapshot>> entry : sectionsByChunk.entrySet()) {
            int minY = entry.getValue().stream().mapToInt(section -> section.key().y()).min().orElse(0);
            this.database.replaceChunkSnapshot(new ChunkSnapshot(entry.getKey(), minY, entry.getValue()));
         }

         return this.applyPreparedSections(sections, frameState);
      }

      private RendererFrameUpdate replacePreparedSections(Map<SectionKey, PreparedSection> sections, RendererFrameState frameState) {
         long stageStartNanos = System.nanoTime();
         List<SectionVoxelSnapshot> snapshots = sections.entrySet().stream().map((entry) -> RtNativeFluidSceneSelfTest.sectionSnapshot((SectionKey)entry.getKey(), ((PreparedSection)entry.getValue()).fluidPresent())).toList();
         this.snapshotNanos += System.nanoTime() - stageStartNanos;
         stageStartNanos = System.nanoTime();

         for(SectionVoxelSnapshot snapshot : snapshots) {
            this.database.replaceBlockMutationSectionSnapshot(snapshot);
         }

         this.databaseReplaceNanos += System.nanoTime() - stageStartNanos;
         return this.applyPreparedSections(sections, frameState);
      }

      private RendererFrameUpdate applyPreparedSections(Map<SectionKey, PreparedSection> sections, RendererFrameState frameState) {
         long stageStartNanos = System.nanoTime();
         SceneUpdateBatch batch = this.database.drainPendingUpdates();
         this.databaseDrainNanos += System.nanoTime() - stageStartNanos;
         if (!batch.hasChanges() && !sections.isEmpty()) {
            batch = preparedSectionBatch(sections);
         }

         stageStartNanos = System.nanoTime();
         SectionMaterialCache.MaterialFacts materialFacts = MaterialFacts.empty();

         for(SectionVoxelSnapshot snapshot : batch.sectionSnapshots().values()) {
            materialFacts = materialFacts.plus(MaterialFacts.fromSnapshot(snapshot));
         }

         SectionMaterialCache.ApplyResult material = this.materialCache.applyMaterialUpdates(batch, batch.sectionSnapshots().keySet(), materialFacts);
         this.materialNanos += System.nanoTime() - stageStartNanos;
         stageStartNanos = System.nanoTime();
         SectionGeometryCache.ApplyResult geometry = this.geometryCache.applyPrepared(Map.of(), batch.removedSections(), batch.fullResyncRequested());
         this.geometryNanos += System.nanoTime() - stageStartNanos;
         Map<SectionKey, SectionTriangleMesh> meshes = new LinkedHashMap<>();

         for(Map.Entry<SectionKey, PreparedSection> entry : sections.entrySet()) {
            meshes.put((SectionKey)entry.getKey(), ((PreparedSection)entry.getValue()).mesh());
         }

         stageStartNanos = System.nanoTime();
         SectionMeshCache.ApplyResult meshResult = this.meshCache.applyPrepared(meshes, batch.removedSections(), batch.fullResyncRequested());
         this.meshNanos += System.nanoTime() - stageStartNanos;
         RtNativeFluidSceneSelfTest.require(meshResult.trianglesInBatch() > 0, "fluid scene update must submit visible triangles");
         return new RendererFrameUpdate(batch, material, geometry, meshResult, frameState);
      }

      private void resetMutationTimings() {
         this.snapshotNanos = 0L;
         this.databaseReplaceNanos = 0L;
         this.databaseDrainNanos = 0L;
         this.materialNanos = 0L;
         this.geometryNanos = 0L;
         this.meshNanos = 0L;
      }

      private String mutationTimingSummary() {
         long value10000 = this.snapshotNanos / 1000000L;
         return "snapshot=" + value10000 + "ms,dbReplace=" + this.databaseReplaceNanos / 1000000L + "ms,dbDrain=" + this.databaseDrainNanos / 1000000L + "ms,material=" + this.materialNanos / 1000000L + "ms,geometry=" + this.geometryNanos / 1000000L + "ms,mesh=" + this.meshNanos / 1000000L + "ms";
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

      private void addPositiveZSolidQuad(float x0, float y0, float x1, float y1, float z, int textureId) {
         this.addQuad(new float[]{x0, y0, z, x1, y0, z, x1, y1, z, x0, y1, z}, FaceDirection.POSITIVE_Z, 1, 0, 9, textureId, false, false, RtNativeFluidSceneSelfTest.standardUvs());
      }

      private void addPositiveZFluidQuad(float x0, float y0, float x1, float y1, float z, int textureId, int mediumAmount) {
         this.addQuad(new float[]{x0, y0, z, x1, y0, z, x1, y1, z, x0, y1, z}, FaceDirection.POSITIVE_Z, 91, mediumAmount, 5, textureId, false, false, RtNativeFluidSceneSelfTest.standardUvs());
      }

      private void addCutoutQuad(float[] quadPositions, int textureId, int[] packedUvs) {
         this.addQuad(quadPositions, FaceDirection.POSITIVE_Z, 1, 0, 1, textureId, false, true, packedUvs);
      }

      private void addQuad(float[] quadPositions, FaceDirection direction, int voxelTypeId, int mediumAmount, int packedMaterialFlags, int textureId, boolean tinted, boolean alphaCutout, int[] packedUvs) {
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
            this.voxelTypeIds.add(voxelTypeId);
            this.mediumAmounts.add((byte)mediumAmount);
            this.directions.add((byte)direction.ordinal());
            this.mapColors.add(0);
            this.lightEmissions.add((byte)0);
            this.materialFlags.add((byte)packedMaterialFlags);
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
         this.positions.add(RtNativeFluidSceneSelfTest.fixed(x));
         this.positions.add(RtNativeFluidSceneSelfTest.fixed(y));
         this.positions.add(RtNativeFluidSceneSelfTest.fixed(z));
      }

      private SectionTriangleMesh build() {
         int faceCount = this.voxelTypeIds.size();
         RtNativeFluidSceneSelfTest.require(this.positions.size() == faceCount * 4 * 3, "fluid mesh vertex count mismatch");
         RtNativeFluidSceneSelfTest.require(this.indices.size() == faceCount * 6, "fluid mesh index count mismatch");
         return new SectionTriangleMesh(this.key, shorts(this.positions), ints(this.indices), ints(this.voxelTypeIds), bytes(this.mediumAmounts), bytes(this.directions), ints(this.mapColors), bytes(this.lightEmissions), bytes(this.materialFlags), ints(this.textureIds), ints(this.uv0), ints(this.uv1), ints(this.uv2), ints(this.uv3), bytes(this.tintFlags), bytes(this.alphaCutoutFlags));
      }
   }
}
