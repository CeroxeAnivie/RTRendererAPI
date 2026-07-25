package top.ceroxe.rt.renderer.rt;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.CameraRayMath;
import top.ceroxe.rt.renderer.DynamicRenderScene;
import top.ceroxe.rt.renderer.RendererFrameState;
import top.ceroxe.rt.renderer.RendererFrameUpdate;
import top.ceroxe.rt.renderer.DynamicRenderScene.LightKind;
import top.ceroxe.rt.renderer.DynamicRenderScene.PrimitiveGeometryKind;
import top.ceroxe.rt.renderer.DynamicRenderScene.PrimitiveKind;
import top.ceroxe.rt.renderer.RendererUpdateLoop.BacklogSnapshot;
import top.ceroxe.rt.renderer.rt.pipeline.RtFrameSnapshot;
import top.ceroxe.rt.renderer.rt.runtime.GuardedRtCore;
import top.ceroxe.rt.renderer.rt.runtime.RtCore;
import top.ceroxe.rt.renderer.rt.runtime.RtCore.State;
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

public final class RtNativeDynamicBlasTlasStressSelfTest {
   private static final int OUTPUT_WIDTH = intProperty("top.ceroxe.rt.rt.dynamicBlasStress.width", 960);
   private static final int OUTPUT_HEIGHT = intProperty("top.ceroxe.rt.rt.dynamicBlasStress.height", 540);
   private static final int PRIMITIVE_COUNT = intProperty("top.ceroxe.rt.rt.dynamicBlasStress.primitiveCount", 160);
   private static final int REPLACEMENT_CYCLES = intProperty("top.ceroxe.rt.rt.dynamicBlasStress.replacementCycles", 32);
   private static final int MAX_FRESH_PUMP_FRAMES = intProperty("top.ceroxe.rt.rt.dynamicBlasStress.maxFreshPumpFrames", 1200);
   private static final int READBACK_SAMPLE_INTERVAL = intProperty("top.ceroxe.rt.rt.dynamicBlasStress.readbackSampleInterval", 1);
   private static final int MIN_DISTINCT_CHECKSUMS = intProperty("top.ceroxe.rt.rt.dynamicBlasStress.minDistinctChecksums", 12);
   private static final long PUMP_SLEEP_MILLIS = longProperty("top.ceroxe.rt.rt.dynamicBlasStress.pumpSleepMillis", 4L);
   private static final int BLOCK_STATE_ID = 1;
   private static final int SENTINEL_COUNT = 3;
   private static final int REFERENCE_LAYOUT_CHURN_INSTANCES = 58;
   private static final int SAMPLE_RADIUS = 5;
   private static final Path SNAPSHOT_PATH = Path.of(System.getProperty("java.io.tmpdir"), "rtrenderer-native-dynamic-blas-tlas-stress.png");

   private RtNativeDynamicBlasTlasStressSelfTest() {
   }

   public static void main(String[] args) throws Exception {
      Map<String, String> previousProperties = installStressProperties();

      try {
         VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
         require(capability.hardwareRayTracingReady(), "native dynamic BLAS/TLAS stress requires production RT hardware: " + capability.summary());
         DynamicBlasStressResult result = runDynamicBlasTlasStress(capability);
         writeSnapshotPng(result.lastSnapshot(), SNAPSHOT_PATH);
         int value10001 = PRIMITIVE_COUNT;
         System.out.println("RtNativeDynamicBlasTlasStressSelfTest passed: primitiveCount=" + value10001 + ", replacementCycles=" + REPLACEMENT_CYCLES + ", dynamicUpdates=" + result.dynamicUpdates() + ", completedFrames=" + result.completedFrames() + ", freshCycleSnapshots=" + result.freshCycleSnapshots() + ", averageCompletedFps=" + result.averageCompletedFps() + ", distinctChecksums=" + result.distinctChecksums() + ", lastSnapshot=" + result.lastSnapshot().asLogFragment() + ", png=" + String.valueOf(SNAPSHOT_PATH) + ", activity=" + result.activity().asLogFragment() + ", readiness=" + result.readiness().asLogFragment());
         System.out.println(RtNativeBenchmarkReport.pacedScene("dynamicBlasTlas", OUTPUT_WIDTH, OUTPUT_HEIGHT, result.completedFrames(), result.averageCompletedFps(), result.activity(), result.readiness()));
      } finally {
         restoreProperties(previousProperties);
      }

   }

   private static DynamicBlasStressResult runDynamicBlasTlasStress(VulkanRtCapabilityProbe.Result capability) throws InterruptedException {
      GuardedRtCore rtCore = GuardedRtCore.isolatedHardwareTest();

      DynamicBlasStressResult dynamicBlasStressResult27;
      try {
         rtCore.acceptCapability(capability);
         boolean condition10000 = rtCore.state() == State.READY_FOR_SCENE_UPDATES;
         String stateDetails10001 = String.valueOf(rtCore.state());
         require(condition10000, "RT core did not open native backend for dynamic BLAS/TLAS stress: state=" + stateDetails10001 + ", summary=" + rtCore.summary().asLogFragment());
         RendererFrameState baseFrameState = frameState(1L);
         List<PrimitiveAnchor> anchors = primitiveAnchors(baseFrameState, PRIMITIVE_COUNT);
         rtCore.acceptFrameUpdate(initialUpdate(terrainDepthAnchor(new SectionKey(0, 0, 0)), baseFrameState, dynamicPrimitiveScene(1L, anchors, RtNativeDynamicBlasTlasStressSelfTest.Variant.WARM, 0)));
         RtFrameSnapshot warmSnapshot = pumpUntilVariantFrame(rtCore, 2L, 1L, RtNativeDynamicBlasTlasStressSelfTest.Variant.WARM, "initial dynamic BLAS warm scene");
         RtNativeStressGuards.assertFrameNotPathological(warmSnapshot, "initial dynamic BLAS warm scene");
         long phaseStartNanos = System.nanoTime();
         long lastCompletedDispatch = Math.max(0L, rtCore.runtimeActivity().latestCompletedFrameDispatch());
         long completedFrames = 0L;
         long freshCycleSnapshots = 0L;
         int dynamicUpdates = 1;
         Set<Long> checksums = new HashSet<>();
         checksums.add(warmSnapshot.checksum());

         for(int cycle = 0; cycle < REPLACEMENT_CYCLES; ++cycle) {
            Variant variant = (cycle & 1) == 0 ? RtNativeDynamicBlasTlasStressSelfTest.Variant.COOL : RtNativeDynamicBlasTlasStressSelfTest.Variant.WARM;
            long sequence = 10000L + (long)cycle * 100L;
            List<PrimitiveAnchor> cycleAnchors = (cycle & 1) == 0 ? anchors.subList(0, Math.min(anchors.size(), 58)) : anchors;
            rtCore.acceptFrameUpdate(RendererFrameUpdate.dynamicOnly(emptyBatch(), frameState(sequence), BacklogSnapshot.empty(), dynamicPrimitiveScene(2L + (long)dynamicUpdates, cycleAnchors, variant, cycle + 1)));
            ++dynamicUpdates;
            RtFrameSnapshot snapshot = pumpUntilVariantFrame(rtCore, sequence, 2L + (long)dynamicUpdates - 1L, variant, "replacement cycle " + cycle);
            RtNativeStressGuards.assertFrameNotPathological(snapshot, "replacement cycle " + cycle);
            checksums.add(snapshot.checksum());
            ++freshCycleSnapshots;
            RtCore.RuntimeActivity activity = rtCore.runtimeActivity();
            long latestCompletedDispatch = activity.latestCompletedFrameDispatch();
            if (latestCompletedDispatch > lastCompletedDispatch) {
               completedFrames += latestCompletedDispatch - lastCompletedDispatch;
               lastCompletedDispatch = latestCompletedDispatch;
            }
         }

         DynamicRenderScene clearScene = new DynamicRenderScene(50000L + (long)dynamicUpdates, List.of(), List.of(), List.of(), List.of(), List.of());
         rtCore.acceptFrameUpdate(RendererFrameUpdate.dynamicOnly(emptyBatch(), frameState(50000L), BacklogSnapshot.empty(), clearScene));
         RtFrameSnapshot clearSnapshot = pumpUntilClearFrame(rtCore, 50000L, clearScene.revision(), "dynamic BLAS clear");
         RtNativeStressGuards.assertFrameNotPathological(clearSnapshot, "dynamic BLAS clear terrain-only frame");
         checksums.add(clearSnapshot.checksum());
         RtCore.RuntimeActivity clearActivity = rtCore.runtimeActivity();
         long latestCompletedDispatch = clearActivity.latestCompletedFrameDispatch();
         if (latestCompletedDispatch > lastCompletedDispatch) {
            completedFrames += latestCompletedDispatch - lastCompletedDispatch;
         }

         long elapsedNanos = Math.max(1L, System.nanoTime() - phaseStartNanos);
         double averageCompletedFps = (double)completedFrames * 1.0E9 / (double)elapsedNanos;
         require(averageCompletedFps >= 1.5, "native dynamic BLAS/TLAS stress completed frames below RTX 3050-compatible 1.5 fps floor, averageCompletedFps=" + averageCompletedFps + ", completedFrames=" + completedFrames + ", freshCycleSnapshots=" + freshCycleSnapshots + ", activity=" + rtCore.runtimeActivity().asLogFragment() + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
         condition10000 = checksums.size() >= MIN_DISTINCT_CHECKSUMS;
         int size36 = checksums.size();
         require(condition10000, "dynamic BLAS/TLAS stress did not produce enough distinct completed frames, distinctChecksums=" + size36 + ", expectedAtLeast=" + MIN_DISTINCT_CHECKSUMS + ", lastSnapshot=" + clearSnapshot.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
         rtCore.refreshDiagnosticSummary();
         String summary = rtCore.summary().asLogFragment();
         require(sumSummaryLong(summary, "submittedBuilds") == 1L, "legacy MODEL replacements must reuse one stable procedural BLAS, dynamicUpdates=" + dynamicUpdates + ", summary=" + summary);
         require(sumSummaryLong(summary, "completedBuilds") == 1L, "stable procedural BLAS must complete exactly once under replacement stress; summary=" + summary);
         require(sumSummaryLong(summary, "clears") > 0L, "dynamic BLAS clear was not observed by the cache; summary=" + summary);
         require(freshCycleSnapshots == (long)REPLACEMENT_CYCLES, "every dynamic replacement must produce a matching completed RT frame, freshCycleSnapshots=" + freshCycleSnapshots + ", replacementCycles=" + REPLACEMENT_CYCLES + ", summary=" + summary);
         RtCore.RuntimeActivity finalActivity = rtCore.runtimeActivity();
         requireGpuStage(finalActivity.gpuWorkTiming().dynamicBlas(), "dynamicBlas", finalActivity);
         requireGpuStage(finalActivity.gpuWorkTiming().dynamicTlas(), "dynamicTlas", finalActivity);
         RtNativeStressGuards.assertCommandAndFencePoolReused(rtCore, "native dynamic BLAS/TLAS stress");
         dynamicBlasStressResult27 = new DynamicBlasStressResult(clearSnapshot, rtCore.sceneReadiness(), finalActivity, dynamicUpdates, completedFrames, freshCycleSnapshots, averageCompletedFps, checksums.size());
      } catch (Throwable value29) {
         if (rtCore != null) {
            try {
               rtCore.close();
            } catch (Throwable value28) {
               value29.addSuppressed(value28);
            }
         }

         throw value29;
      }

      if (rtCore != null) {
         rtCore.close();
      }

      return dynamicBlasStressResult27;
   }

   private static void requireGpuStage(RtCore.GpuStageTiming timing, String label, RtCore.RuntimeActivity activity) {
      require(timing.enabled() && timing.completedSamples() > 0L && timing.averageNanos() > 0L, "dynamic BLAS/TLAS stress did not resolve " + label + " GPU timing, activity=" + activity.asLogFragment());
      require(timing.failedSamples() == 0L, "dynamic BLAS/TLAS stress observed invalid " + label + " GPU timing, activity=" + activity.asLogFragment());
   }

   private static RendererFrameUpdate initialUpdate(SectionTriangleMesh mesh, RendererFrameState frameState, DynamicRenderScene dynamicScene) {
      SectionKey key = mesh.key();
      SceneDatabase database = new SceneDatabase();
      SectionMaterialCache materialCache = new SectionMaterialCache();
      SectionGeometryCache geometryCache = new SectionGeometryCache();
      SectionMeshCache meshCache = new SectionMeshCache();
      database.replaceChunkSnapshot(new ChunkSnapshot(key.chunkKey(), key.y(), List.of(filledSection(key))));
      SceneUpdateBatch batch = database.drainPendingUpdates();
      SectionMaterialCache.ApplyResult material = materialCache.apply(batch);
      SectionGeometryCache.ApplyResult geometry = geometryCache.apply(material.encodedSections(), batch.removedSections(), batch.fullResyncRequested());
      SectionMeshCache.ApplyResult meshResult = meshCache.applyPrepared(Map.of(key, mesh), batch.removedSections(), batch.fullResyncRequested());
      require(meshResult.trianglesInBatch() > 0, "dynamic BLAS stress terrain anchor must submit visible triangles");
      return new RendererFrameUpdate(batch, material, geometry, meshResult, frameState, BacklogSnapshot.empty(), dynamicScene);
   }

   private static RtFrameSnapshot pumpUntilVariantFrame(GuardedRtCore rtCore, long minimumSnapshotSequence, long minimumDynamicSceneRevision, Variant expectedVariant, String label) throws InterruptedException {
      for(int frame = 0; frame < MAX_FRESH_PUMP_FRAMES; ++frame) {
         long sequence = minimumSnapshotSequence + 1L + (long)frame;
         rtCore.acceptFrameUpdate(RendererFrameUpdate.empty(emptyBatch(), frameState(sequence)));
         RtFrameSnapshot snapshot = rtCore.latestFrameSnapshot();
         if (snapshot != null && snapshot.frameStateSequence() >= minimumSnapshotSequence && snapshot.boundTlasDynamicSceneRevision() >= minimumDynamicSceneRevision) {
            try {
               assertVariantSentinels(snapshot, expectedVariant, label);
               return snapshot;
            } catch (AssertionError ex) {
               writeFailureSnapshot(snapshot, label, ex);
               throw ex;
            }
         }

         require(rtCore.state() == State.READY_FOR_SCENE_UPDATES, "RT core failed while pumping " + label + ": state=" + String.valueOf(rtCore.state()) + ", lastSnapshot=" + (snapshot == null ? "none" : snapshot.asLogFragment()) + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", activity=" + rtCore.runtimeActivity().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
         Thread.sleep(PUMP_SLEEP_MILLIS);
      }

      throw new AssertionError(label + " did not produce a fresh dynamic BLAS/TLAS snapshot, minimumSequence=" + minimumSnapshotSequence + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", activity=" + rtCore.runtimeActivity().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
   }

   private static RtFrameSnapshot pumpUntilClearFrame(GuardedRtCore rtCore, long minimumSnapshotSequence, long minimumDynamicSceneRevision, String label) throws InterruptedException {
      for(int frame = 0; frame < MAX_FRESH_PUMP_FRAMES; ++frame) {
         long sequence = minimumSnapshotSequence + 1L + (long)frame;
         rtCore.acceptFrameUpdate(RendererFrameUpdate.empty(emptyBatch(), frameState(sequence)));
         RtFrameSnapshot snapshot = rtCore.latestFrameSnapshot();
         if (snapshot != null && snapshot.frameStateSequence() >= minimumSnapshotSequence && snapshot.boundTlasDynamicSceneRevision() >= minimumDynamicSceneRevision) {
            try {
               assertNoSentinelColors(snapshot, label);
               return snapshot;
            } catch (AssertionError ex) {
               writeFailureSnapshot(snapshot, label, ex);
               throw ex;
            }
         }

         require(rtCore.state() == State.READY_FOR_SCENE_UPDATES, "RT core failed while pumping " + label + ": state=" + String.valueOf(rtCore.state()) + ", lastSnapshot=" + (snapshot == null ? "none" : snapshot.asLogFragment()) + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", activity=" + rtCore.runtimeActivity().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
         Thread.sleep(PUMP_SLEEP_MILLIS);
      }

      throw new AssertionError(label + " did not produce a fresh terrain-only snapshot, minimumSequence=" + minimumSnapshotSequence + ", minimumDynamicSceneRevision=" + minimumDynamicSceneRevision + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", activity=" + rtCore.runtimeActivity().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
   }

   private static DynamicRenderScene dynamicPrimitiveScene(long revision, List<PrimitiveAnchor> anchors, Variant variant, int phase) {
      List<DynamicRenderScene.DynamicPrimitive> primitives = new ArrayList<>(anchors.size());

      for(int index = 0; index < anchors.size(); ++index) {
         PrimitiveAnchor anchor = (PrimitiveAnchor)anchors.get(index);
         double wobble = anchor.sentinel() ? 0.0 : Math.sin((double)(phase + index) * 0.37) * 0.28;
         int color = anchor.sentinel() ? variant.sentinelColor(anchor.sentinelIndex()) : variant.fillerColor(index, phase);
         primitives.add(new DynamicRenderScene.DynamicPrimitive(10000L + (long)index, PrimitiveKind.ENTITY, PrimitiveGeometryKind.MODEL, anchor.x() + anchor.rayX() * wobble, anchor.y() + anchor.rayY() * wobble, anchor.z() + anchor.rayZ() * wobble, 0.0F, 0.0F, 0.0F, anchor.sentinel() ? 0.82F : 0.34F, color, 0, 15728880, true, anchor.sentinel() ? "sentinel-" + anchor.sentinelIndex() : "filler-" + index));
      }

      return new DynamicRenderScene(revision, primitives, List.of(), List.of(), List.of(), List.of(new DynamicRenderScene.SceneLight(1L, LightKind.SKY, 0.0, 0.0, 0.0, 0.0F, 0.0F, -1.0F, 1.0F, 1.0F, 7383295, false)));
   }

   private static List<PrimitiveAnchor> primitiveAnchors(RendererFrameState frameState, int primitiveCount) {
      require(primitiveCount >= 3, "primitiveCount must be at least 3");
      List<PrimitiveAnchor> anchors = new ArrayList<>(primitiveCount);
      int[][] sentinels = sentinelSamples();

      for(int index = 0; index < sentinels.length; ++index) {
         anchors.add(anchorForSample(frameState, sentinels[index][0], sentinels[index][1], 10.5 + (double)index, index));
      }

      int columns = Math.max(8, (int)Math.ceil(Math.sqrt((double)primitiveCount)));
      int rows = Math.max(4, (primitiveCount + columns - 1) / columns);
      int cursor = 0;

      while(anchors.size() < primitiveCount) {
         int column = cursor % columns;
         int row = cursor / columns % rows;
         int sampleX = OUTPUT_WIDTH * (column + 1) / (columns + 1);
         int sampleY = OUTPUT_HEIGHT * (row + 1) / (rows + 1);
         ++cursor;
         if (!nearAnySentinel(sampleX, sampleY, sentinels)) {
            double distance = 8.0 + (double)(cursor % 9) * 0.45;
            anchors.add(anchorForSample(frameState, sampleX, sampleY, distance, -1));
         }
      }

      return anchors;
   }

   private static PrimitiveAnchor anchorForSample(RendererFrameState frameState, int sampleX, int sampleY, double distance, int sentinelIndex) {
      float[] ray = rayDirection(frameState, sampleX, sampleY);
      return new PrimitiveAnchor(sampleX, sampleY, frameState.cameraX() + (double)ray[0] * distance, frameState.cameraY() + (double)ray[1] * distance, frameState.cameraZ() + (double)ray[2] * distance, (double)ray[0], (double)ray[1], (double)ray[2], sentinelIndex);
   }

   private static boolean nearAnySentinel(int x, int y, int[][] sentinels) {
      for(int[] sentinel : sentinels) {
         if (Math.abs(x - sentinel[0]) <= 48 && Math.abs(y - sentinel[1]) <= 48) {
            return true;
         }
      }

      return false;
   }

   private static int[][] sentinelSamples() {
      return new int[][]{{OUTPUT_WIDTH / 4, OUTPUT_HEIGHT / 2}, {OUTPUT_WIDTH / 2, OUTPUT_HEIGHT / 2}, {OUTPUT_WIDTH * 3 / 4, OUTPUT_HEIGHT / 2}};
   }

   private static SectionTriangleMesh terrainDepthAnchor(SectionKey key) {
      return new SectionTriangleMesh(key, new short[]{fixed(0.0F), fixed(0.0F), fixed(16.0F), fixed(16.0F), fixed(0.0F), fixed(16.0F), fixed(16.0F), fixed(16.0F), fixed(16.0F), fixed(0.0F), fixed(16.0F), fixed(16.0F)}, new int[]{0, 1, 2, 0, 2, 3}, new int[]{1}, new byte[]{0}, new byte[]{(byte)FaceDirection.POSITIVE_Z.ordinal()});
   }

   private static SectionVoxelSnapshot filledSection(SectionKey key) {
      int[] ids = new int[4096];
      byte[] fluids = new byte[4096];
      Arrays.fill(ids, 1);
      return new SectionVoxelSnapshot(key, ids, fluids, false, false);
   }

   private static RendererFrameState frameState(long sequence) {
      return new RendererFrameState(sequence, true, OUTPUT_WIDTH, OUTPUT_HEIGHT, 8.0, 8.0, 40.0, 0.0F, 0.0F, 0.0F, 0.0F, -1.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 1.7320508F, 1.7320508F, 1.0F, 0.0F, -1.0F, 0.0F, false, true);
   }

   private static float[] rayDirection(RendererFrameState frameState, int x, int y) {
      CameraRayMath.RayScale rayScale = CameraRayMath.rayScale(frameState, OUTPUT_WIDTH, OUTPUT_HEIGHT);
      float uvX = ((float)x + 0.5F) / (float)OUTPUT_WIDTH;
      float uvY = ((float)y + 0.5F) / (float)OUTPUT_HEIGHT;
      float ndcX = uvX * 2.0F - 1.0F;
      float ndcY = 1.0F - uvY * 2.0F;
      CameraRayMath.RayDirection direction = CameraRayMath.screenRay(frameState, ndcX, ndcY, rayScale);
      return new float[]{direction.x(), direction.y(), direction.z()};
   }

   private static void assertVariantSentinels(RtFrameSnapshot snapshot, Variant expectedVariant, String label) {
      int[][] samples = sentinelSamples();

      for(int index = 0; index < samples.length; ++index) {
         int x = samples[index][0];
         int y = samples[index][1];
         PixelPredicate expected = expectedVariant.sentinelPredicate(index);
         PixelPredicate forbidden = expectedVariant.opposite().sentinelPredicate(index);
         require(countMatching(snapshot, x, y, 5, expected) >= 8, label + " did not render expected dynamic BLAS sentinel, sentinel=" + index + ", expectedVariant=" + String.valueOf(expectedVariant) + ", sample=(" + x + "," + y + "), snapshot=" + snapshot.asLogFragment() + ", colors=" + sampleWindow(snapshot, x, y, 2));
         require(countMatching(snapshot, x, y, 5, forbidden) == 0, label + " rendered stale dynamic BLAS sentinel from the previous variant, sentinel=" + index + ", expectedVariant=" + String.valueOf(expectedVariant) + ", sample=(" + x + "," + y + "), snapshot=" + snapshot.asLogFragment() + ", colors=" + sampleWindow(snapshot, x, y, 2));
      }

   }

   private static void assertNoSentinelColors(RtFrameSnapshot snapshot, String label) {
      int[][] samples = sentinelSamples();

      for(int index = 0; index < samples.length; ++index) {
         int x = samples[index][0];
         int y = samples[index][1];
         require(countMatching(snapshot, x, y, 5, RtNativeDynamicBlasTlasStressSelfTest.Variant.WARM.sentinelPredicate(index)) == 0, label + " left stale warm dynamic BLAS pixels, sentinel=" + index + ", sample=(" + x + "," + y + "), snapshot=" + snapshot.asLogFragment() + ", colors=" + sampleWindow(snapshot, x, y, 2));
         require(countMatching(snapshot, x, y, 5, RtNativeDynamicBlasTlasStressSelfTest.Variant.COOL.sentinelPredicate(index)) == 0, label + " left stale cool dynamic BLAS pixels, sentinel=" + index + ", sample=(" + x + "," + y + "), snapshot=" + snapshot.asLogFragment() + ", colors=" + sampleWindow(snapshot, x, y, 2));
      }

   }

   private static int countMatching(RtFrameSnapshot snapshot, int centerX, int centerY, int radius, PixelPredicate predicate) {
      byte[] rgba = snapshot.copyRgba8();
      int count = 0;

      for(int y = Math.max(0, centerY - radius); y <= Math.min(snapshot.height() - 1, centerY + radius); ++y) {
         for(int x = Math.max(0, centerX - radius); x <= Math.min(snapshot.width() - 1, centerX + radius); ++x) {
            if (predicate.test(RtFrameSnapshot.pixel(rgba, snapshot.width(), x, y))) {
               ++count;
            }
         }
      }

      return count;
   }

   private static boolean isWarmRed(int rgba8) {
      int red = rgba8 & 255;
      int green = rgba8 >>> 8 & 255;
      int blue = rgba8 >>> 16 & 255;
      return red >= 130 && green <= 130 && blue <= 130;
   }

   private static boolean isWarmYellow(int rgba8) {
      int red = rgba8 & 255;
      int green = rgba8 >>> 8 & 255;
      int blue = rgba8 >>> 16 & 255;
      return red >= 150 && green >= 120 && blue <= 130;
   }

   private static boolean isWarmOrange(int rgba8) {
      int red = rgba8 & 255;
      int green = rgba8 >>> 8 & 255;
      int blue = rgba8 >>> 16 & 255;
      return red >= 140 && green >= 70 && green <= 180 && blue <= 120;
   }

   private static boolean isCoolCyan(int rgba8) {
      int red = rgba8 & 255;
      int green = rgba8 >>> 8 & 255;
      int blue = rgba8 >>> 16 & 255;
      return red <= 130 && green >= 130 && blue >= 130;
   }

   private static boolean isCoolBlue(int rgba8) {
      int red = rgba8 & 255;
      int green = rgba8 >>> 8 & 255;
      int blue = rgba8 >>> 16 & 255;
      return red <= 130 && green <= 190 && blue >= 150;
   }

   private static boolean isCoolLime(int rgba8) {
      int red = rgba8 & 255;
      int green = rgba8 >>> 8 & 255;
      int blue = rgba8 >>> 16 & 255;
      return red <= 130 && green >= 150 && blue <= 160;
   }

   private static String sampleWindow(RtFrameSnapshot snapshot, int centerX, int centerY, int radius) {
      byte[] rgba = snapshot.copyRgba8();
      StringBuilder result = new StringBuilder("[");
      int emitted = 0;

      for(int y = Math.max(0, centerY - radius); y <= Math.min(snapshot.height() - 1, centerY + radius); ++y) {
         for(int x = Math.max(0, centerX - radius); x <= Math.min(snapshot.width() - 1, centerX + radius); ++x) {
            if (emitted > 0) {
               result.append(", ");
            }

            int pixel = RtFrameSnapshot.pixel(rgba, snapshot.width(), x, y);
            result.append("(").append(x).append(",").append(y).append("=").append(RtFrameSnapshot.hex(pixel)).append("/rgba=").append(pixel & 255).append(',').append(pixel >>> 8 & 255).append(',').append(pixel >>> 16 & 255).append(',').append(pixel >>> 24 & 255).append(")");
            ++emitted;
         }
      }

      return result.append("]").toString();
   }

   private static Map<String, String> installStressProperties() {
      Map<String, String> previous = new LinkedHashMap<>();
      set(previous, "top.ceroxe.rt.takeoverFlightRecorder.enabled", "true");
      set(previous, "top.ceroxe.rt.takeoverFlightRecorder.verboseIo", "false");
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
      set(previous, "top.ceroxe.rt.rt.sectionBlas.maxBuildsPerFrame", "8");
      set(previous, "top.ceroxe.rt.rt.sectionBlas.maxTrianglesPerFrame", "1000000");
      set(previous, "top.ceroxe.rt.rt.sectionBlas.maxAsyncBuildsInFlight", "8");
      set(previous, "top.ceroxe.rt.rt.sectionBlas.maxAsyncBuildSectionsInFlight", "8");
      set(previous, "top.ceroxe.rt.rt.sectionBlas.maxAsyncBuildBytesInFlight", "268435456");
      set(previous, "top.ceroxe.rt.rt.sectionBlas.maxPendingSections", "64");
      set(previous, "top.ceroxe.rt.rt.sectionBlas.maxPendingBytes", "268435456");
      set(previous, "top.ceroxe.rt.rt.sectionBlas.maxCachedSections", "64");
      set(previous, "top.ceroxe.rt.rt.sectionBlas.maxCachedBytes", "268435456");
      return previous;
   }

   private static SceneUpdateBatch emptyBatch() {
      return new SceneUpdateBatch(Set.of(), Set.of(), Set.of(), Set.of(), Map.of(), false, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
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

   private static void writeFailureSnapshot(RtFrameSnapshot snapshot, String label, AssertionError failure) {
      String safeLabel = label.replaceAll("[^A-Za-z0-9._-]+", "-");
      Path path = SNAPSHOT_PATH.resolveSibling("rtrenderer-native-dynamic-blas-tlas-stress-failed-" + safeLabel + ".png");

      try {
         writeSnapshotPng(snapshot, path);
         failure.addSuppressed(new AssertionError("failure snapshot written to " + String.valueOf(path)));
      } catch (IOException ioFailure) {
         failure.addSuppressed(ioFailure);
      }

   }

   private static short fixed(float blockUnits) {
      return (short)Math.round(blockUnits * 1024.0F);
   }

   private static int rgba8(int red, int green, int blue, int alpha) {
      return red & 255 | (green & 255) << 8 | (blue & 255) << 16 | (alpha & 255) << 24;
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

   private static long sumSummaryLong(String summary, String key) {
      long sum = 0L;
      boolean present = false;
      String prefix = key + "=";

      int valueEnd;
      for(int searchFrom = 0; searchFrom < summary.length(); searchFrom = valueEnd) {
         int start = summary.indexOf(prefix, searchFrom);
         if (start < 0) {
            break;
         }

         present = true;
         int valueStart = start + prefix.length();

         for(valueEnd = valueStart; valueEnd < summary.length() && Character.isDigit(summary.charAt(valueEnd)); ++valueEnd) {
         }

         require(valueEnd > valueStart, "summary key has no numeric value: " + key + "; summary=" + summary);
         sum += Long.parseLong(summary.substring(valueStart, valueEnd));
      }

      require(present, "summary key was not present: " + key + "; summary=" + summary);
      return sum;
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }

   private static enum Variant {
      WARM {
         int sentinelColor(int index) {
            int value10000;
            switch (index) {
               case 0 -> value10000 = RtNativeDynamicBlasTlasStressSelfTest.rgba8(238, 54, 42, 255);
               case 1 -> value10000 = RtNativeDynamicBlasTlasStressSelfTest.rgba8(250, 214, 44, 255);
               case 2 -> value10000 = RtNativeDynamicBlasTlasStressSelfTest.rgba8(238, 104, 34, 255);
               default -> throw new IllegalArgumentException("invalid sentinel index: " + index);
            }

            return value10000;
         }

         PixelPredicate sentinelPredicate(int index) {
            PixelPredicate predicate10000;
            switch (index) {
               case 0 -> predicate10000 = RtNativeDynamicBlasTlasStressSelfTest::isWarmRed;
               case 1 -> predicate10000 = RtNativeDynamicBlasTlasStressSelfTest::isWarmYellow;
               case 2 -> predicate10000 = RtNativeDynamicBlasTlasStressSelfTest::isWarmOrange;
               default -> throw new IllegalArgumentException("invalid sentinel index: " + index);
            }

            return predicate10000;
         }

         Variant opposite() {
            return COOL;
         }
      },
      COOL {
         int sentinelColor(int index) {
            int value10000;
            switch (index) {
               case 0 -> value10000 = RtNativeDynamicBlasTlasStressSelfTest.rgba8(42, 214, 238, 255);
               case 1 -> value10000 = RtNativeDynamicBlasTlasStressSelfTest.rgba8(58, 118, 248, 255);
               case 2 -> value10000 = RtNativeDynamicBlasTlasStressSelfTest.rgba8(72, 236, 88, 255);
               default -> throw new IllegalArgumentException("invalid sentinel index: " + index);
            }

            return value10000;
         }

         PixelPredicate sentinelPredicate(int index) {
            PixelPredicate predicate10000;
            switch (index) {
               case 0 -> predicate10000 = RtNativeDynamicBlasTlasStressSelfTest::isCoolCyan;
               case 1 -> predicate10000 = RtNativeDynamicBlasTlasStressSelfTest::isCoolBlue;
               case 2 -> predicate10000 = RtNativeDynamicBlasTlasStressSelfTest::isCoolLime;
               default -> throw new IllegalArgumentException("invalid sentinel index: " + index);
            }

            return predicate10000;
         }

         Variant opposite() {
            return WARM;
         }
      };

      abstract int sentinelColor(int value1);

      abstract PixelPredicate sentinelPredicate(int value1);

      abstract Variant opposite();

      int fillerColor(int index, int phase) {
         int seed = index * 73244475 + phase * 295559667 + this.ordinal() * 668265261;
         int red = 64 + (seed & 127);
         int green = 64 + (seed >>> 8 & 127);
         int blue = 64 + (seed >>> 16 & 127);
         return RtNativeDynamicBlasTlasStressSelfTest.rgba8(red, green, blue, 255);
      }
   }

   private static record PrimitiveAnchor(int sampleX, int sampleY, double x, double y, double z, double rayX, double rayY, double rayZ, int sentinelIndex) {
      private PrimitiveAnchor(int sampleX, int sampleY, double x, double y, double z, double rayX, double rayY, double rayZ, int sentinelIndex) {
         if (sentinelIndex >= -1 && sentinelIndex < 3) {
            this.sampleX = sampleX;
            this.sampleY = sampleY;
            this.x = x;
            this.y = y;
            this.z = z;
            this.rayX = rayX;
            this.rayY = rayY;
            this.rayZ = rayZ;
            this.sentinelIndex = sentinelIndex;
         } else {
            throw new IllegalArgumentException("invalid sentinel index: " + sentinelIndex);
         }
      }

      boolean sentinel() {
         return this.sentinelIndex >= 0;
      }
   }

   private static record DynamicBlasStressResult(RtFrameSnapshot lastSnapshot, RtSceneReadiness readiness, RtCore.RuntimeActivity activity, int dynamicUpdates, long completedFrames, long freshCycleSnapshots, double averageCompletedFps, int distinctChecksums) {
   }

   private interface PixelPredicate {
      boolean test(int value1);
   }
}
