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
import top.ceroxe.rt.renderer.LightmapPayload;
import top.ceroxe.rt.renderer.RendererFrameState;
import top.ceroxe.rt.renderer.RendererFrameUpdate;
import top.ceroxe.rt.renderer.DynamicRenderScene.CelestialKind;
import top.ceroxe.rt.renderer.DynamicRenderScene.LightKind;
import top.ceroxe.rt.renderer.RendererUpdateLoop.BacklogSnapshot;
import top.ceroxe.rt.renderer.rt.material.RtTextureCatalog;
import top.ceroxe.rt.renderer.rt.pipeline.RtFrameSnapshot;
import top.ceroxe.rt.renderer.rt.runtime.GuardedRtCore;
import top.ceroxe.rt.renderer.rt.runtime.RtCore;
import top.ceroxe.rt.renderer.rt.runtime.RtCore.State;
import top.ceroxe.rt.renderer.scene.ChunkSnapshot;
import top.ceroxe.rt.renderer.scene.FaceDirection;
import top.ceroxe.rt.renderer.scene.PackedVoxelLighting;
import top.ceroxe.rt.renderer.scene.SceneDatabase;
import top.ceroxe.rt.renderer.scene.SceneUpdateBatch;
import top.ceroxe.rt.renderer.scene.SectionGeometryCache;
import top.ceroxe.rt.renderer.scene.SectionKey;
import top.ceroxe.rt.renderer.scene.SectionMaterialCache;
import top.ceroxe.rt.renderer.scene.SectionMeshCache;
import top.ceroxe.rt.renderer.scene.SectionTriangleMesh;
import top.ceroxe.rt.renderer.scene.SectionVoxelSnapshot;

public final class RtNativeReferenceDynamicLightingSceneSelfTest {
   private static final int OUTPUT_WIDTH = intProperty("top.ceroxe.rt.rt.referenceLighting.width", 960);
   private static final int OUTPUT_HEIGHT = intProperty("top.ceroxe.rt.rt.referenceLighting.height", 540);
   private static final int SUSTAINED_FRAMES = intProperty("top.ceroxe.rt.rt.referenceLighting.sustainedFrames", 96);
   private static final int DYNAMIC_UPDATE_PERIOD_FRAMES = intProperty("top.ceroxe.rt.rt.referenceLighting.updatePeriodFrames", 2);
   private static final int MAX_INITIAL_READY_PUMP_FRAMES = intProperty("top.ceroxe.rt.rt.referenceLighting.maxInitialReadyPumpFrames", 900);
   private static final int MAX_FRESH_PUMP_FRAMES = intProperty("top.ceroxe.rt.rt.referenceLighting.maxFreshPumpFrames", 900);
   private static final int READBACK_SAMPLE_INTERVAL = intProperty("top.ceroxe.rt.rt.referenceLighting.readbackSampleInterval", 1);
   private static final int MIN_DISTINCT_CHECKSUMS = intProperty("top.ceroxe.rt.rt.referenceLighting.minDistinctChecksums", 8);
   private static final long PUMP_SLEEP_MILLIS = longProperty("top.ceroxe.rt.rt.referenceLighting.pumpSleepMillis", 5L);
   private static final int BLOCK_STATE_ID = 1;
   private static final int RED_SAMPLE_X;
   private static final int GREEN_SAMPLE_X;
   private static final int BLUE_SAMPLE_X;
   private static final int GRADIENT_LEFT_SAMPLE_X;
   private static final int GRADIENT_RIGHT_SAMPLE_X;
   private static final int MIN_GRADIENT_SPAN_LUMINANCE = 8;
   private static final int RGBA8_GRADIENT_QUANTIZATION_TOLERANCE = 1;
   private static final int SAMPLE_Y;
   private static final double TERRAIN_Z = 16.0;
   private static final Path SNAPSHOT_PATH;

   private RtNativeReferenceDynamicLightingSceneSelfTest() {
   }

   public static void main(String[] args) throws Exception {
      Map<String, String> previousProperties = installReferenceLightingProperties();

      try {
         VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
         require(capability.hardwareRayTracingReady(), "native reference dynamic lighting scene requires production RT hardware: " + capability.summary());
         ReferenceLightingResult result = runReferenceDynamicLightingScene(capability);
         writeSnapshotPng(result.lastSnapshot(), SNAPSHOT_PATH);
         int sustainedFrameCount10001 = SUSTAINED_FRAMES;
         System.out.println("RtNativeReferenceDynamicLightingSceneSelfTest passed: sustainedFrames=" + sustainedFrameCount10001 + ", dynamicUpdates=" + result.dynamicUpdates() + ", completedFrames=" + result.completedFrames() + ", averageCompletedFps=" + result.averageCompletedFps() + ", distinctChecksums=" + result.distinctChecksums() + ", lastSnapshot=" + result.lastSnapshot().asLogFragment() + ", png=" + String.valueOf(SNAPSHOT_PATH) + ", activity=" + result.activity().asLogFragment() + ", readiness=" + result.readiness().asLogFragment());
         System.out.println(RtNativeBenchmarkReport.pacedScene("dynamicLighting", OUTPUT_WIDTH, OUTPUT_HEIGHT, result.completedFrames(), result.averageCompletedFps(), result.activity(), result.readiness()));
      } finally {
         restoreProperties(previousProperties);
      }

   }

   private static ReferenceLightingResult runReferenceDynamicLightingScene(VulkanRtCapabilityProbe.Result capability) throws InterruptedException {
      GuardedRtCore rtCore = GuardedRtCore.isolatedHardwareTest();

      ReferenceLightingResult referenceLightingResult26;
      try {
         rtCore.acceptCapability(capability);
         boolean condition10000 = rtCore.state() == State.READY_FOR_SCENE_UPDATES;
         String stateDetails10001 = String.valueOf(rtCore.state());
         require(condition10000, "RT core did not open native backend for reference dynamic lighting: state=" + stateDetails10001 + ", summary=" + rtCore.summary().asLogFragment());
         RendererFrameState baseFrameState = frameState(1L);
         LightAnchors anchors = lightAnchors(baseFrameState);
         rtCore.acceptFrameUpdate(initialUpdate(litTerrainPlane(new SectionKey(0, 0, 0)), baseFrameState, referenceLightingScene(1L, anchors, 0.0F, RtNativeReferenceDynamicLightingSceneSelfTest.Palette.PRIMARY)));
         RtFrameSnapshot readySnapshot = pumpUntilFreshSnapshot(rtCore, 2L, "reference dynamic lighting initial ready");
         RtNativeStressGuards.assertFrameNotPathological(readySnapshot, "initial native reference dynamic lighting frame");
         assertNoDominantColor(readySnapshot, RED_SAMPLE_X, SAMPLE_Y, "initial low-light terrain");
         assertNoDominantColor(readySnapshot, GREEN_SAMPLE_X, SAMPLE_Y, "initial sky-light terrain");
         assertNoDominantColor(readySnapshot, BLUE_SAMPLE_X, SAMPLE_Y, "initial block-light terrain");
         assertLowLightTerrainVisible(readySnapshot, RED_SAMPLE_X, SAMPLE_Y, "initial reference low-light terrain");
         assertSkyLightTerrainVisible(readySnapshot, GREEN_SAMPLE_X, SAMPLE_Y, "initial reference sky-light terrain");
         assertBlockLightTerrainWarm(readySnapshot, BLUE_SAMPLE_X, SAMPLE_Y, "initial reference block-light terrain");
         assertSmoothVertexLightingGradient(readySnapshot, "initial reference per-vertex sky-light gradient");
         long phaseStartNanos = System.nanoTime();
         long lastCompletedSequence = Math.max(0L, rtCore.runtimeActivity().latestCompletedFrameStateSequence());
         long completedFrames = 0L;
         int dynamicUpdates = 1;
         Set<Long> checksums = new HashSet<>();
         checksums.add(readySnapshot.checksum());
         int observedLightingStateChanges = 0;
         long lastLightingSignature = lightingSignature(readySnapshot);

         for(int frame = 0; frame < SUSTAINED_FRAMES; ++frame) {
            long sequence = 10000L + (long)frame * 10L;
            float phase = (float)frame / (float)Math.max(1, SUSTAINED_FRAMES - 1);
            Palette palette = (frame & 1) == 0 ? RtNativeReferenceDynamicLightingSceneSelfTest.Palette.SECONDARY : RtNativeReferenceDynamicLightingSceneSelfTest.Palette.PRIMARY;
            RendererFrameUpdate update = RendererFrameUpdate.dynamicOnly(emptyBatch(), frameState(sequence), BacklogSnapshot.empty(), referenceLightingScene(2L + (long)dynamicUpdates, anchors, phase, palette));
            ++dynamicUpdates;
            rtCore.acceptFrameUpdate(update);
            RtFrameSnapshot snapshot = pollOnlyUntilFreshSnapshot(rtCore, sequence, "reference dynamic lighting revision " + frame);
            if (snapshot.frameStateSequence() > lastCompletedSequence) {
               ++completedFrames;
               lastCompletedSequence = snapshot.frameStateSequence();
            }

            checksums.add(snapshot.checksum());
            long lightingSignature = lightingSignature(snapshot);
            if (lightingSignature != lastLightingSignature) {
               ++observedLightingStateChanges;
               lastLightingSignature = lightingSignature;
            }

            assertNoDominantColor(snapshot, RED_SAMPLE_X, SAMPLE_Y, "dynamic low-light terrain");
            assertNoDominantColor(snapshot, GREEN_SAMPLE_X, SAMPLE_Y, "dynamic sky-light terrain");
            assertNoDominantColor(snapshot, BLUE_SAMPLE_X, SAMPLE_Y, "dynamic block-light terrain");
            condition10000 = rtCore.state() == State.READY_FOR_SCENE_UPDATES;
            stateDetails10001 = String.valueOf(rtCore.state());
            require(condition10000, "RT core failed during reference dynamic lighting scene: state=" + stateDetails10001 + ", activity=" + rtCore.runtimeActivity().asLogFragment() + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
         }

         DynamicRenderScene clearScene = new DynamicRenderScene(50000L + (long)dynamicUpdates, List.of(), List.of(), List.of(), List.of(), List.of(), dynamicLightmapPayload(50000L + (long)dynamicUpdates));
         rtCore.acceptFrameUpdate(RendererFrameUpdate.dynamicOnly(emptyBatch(), frameState(50000L), BacklogSnapshot.empty(), clearScene));
         RtFrameSnapshot clearSnapshot = pollOnlyUntilFreshSnapshot(rtCore, 50000L, "reference dynamic lighting clear");
         checksums.add(clearSnapshot.checksum());
         long clearLightingSignature = lightingSignature(clearSnapshot);
         if (clearLightingSignature != lastLightingSignature) {
            ++observedLightingStateChanges;
         }

         assertNoDominantColor(clearSnapshot, RED_SAMPLE_X, SAMPLE_Y, "cleared red local light");
         assertNoDominantColor(clearSnapshot, GREEN_SAMPLE_X, SAMPLE_Y, "cleared green local light");
         assertNoDominantColor(clearSnapshot, BLUE_SAMPLE_X, SAMPLE_Y, "cleared blue local light");
         assertLowLightTerrainVisible(clearSnapshot, RED_SAMPLE_X, SAMPLE_Y, "cleared reference low-light terrain");
         assertSkyLightTerrainVisible(clearSnapshot, GREEN_SAMPLE_X, SAMPLE_Y, "cleared reference sky-light terrain");
         assertBlockLightTerrainWarm(clearSnapshot, BLUE_SAMPLE_X, SAMPLE_Y, "cleared reference block-light terrain");
         assertSmoothVertexLightingGradient(clearSnapshot, "cleared reference per-vertex sky-light gradient");
         long elapsedNanos = Math.max(1L, System.nanoTime() - phaseStartNanos);
         double averageCompletedFps = (double)completedFrames * 1.0E9 / (double)elapsedNanos;
         require(averageCompletedFps >= 1.5, "native reference dynamic lighting completed frames below RTX 3050-compatible 1.5 fps floor, averageCompletedFps=" + averageCompletedFps + ", completedFrames=" + completedFrames + ", activity=" + rtCore.runtimeActivity().asLogFragment() + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
         require(observedLightingStateChanges >= 2, "reference dynamic lighting scene did not update sampled reference lightmap colors across revisions, observedLightingStateChanges=" + observedLightingStateChanges + ", lastLightingSignature=" + Long.toHexString(lastLightingSignature) + ", clearLightingSignature=" + Long.toHexString(clearLightingSignature) + ", lastSnapshot=" + clearSnapshot.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
         rtCore.refreshDiagnosticSummary();
         String summary = rtCore.summary().asLogFragment();
         require(sumSummaryLong(summary, "dynamicSceneUploads") > 0L, "RT pipeline did not report dynamic lighting scene GPU uploads; summary=" + summary);
         require(sumSummaryLong(summary, "latestDynamicSceneElements") == 0L, "dynamic lighting clear did not reach the RT pipeline; summary=" + summary);
         RtNativeStressGuards.assertCommandAndFencePoolReused(rtCore, "native reference dynamic lighting scene");
         referenceLightingResult26 = new ReferenceLightingResult(clearSnapshot, rtCore.sceneReadiness(), rtCore.runtimeActivity(), dynamicUpdates, completedFrames, averageCompletedFps, checksums.size());
      } catch (Throwable value28) {
         if (rtCore != null) {
            try {
               rtCore.close();
            } catch (Throwable value27) {
               value28.addSuppressed(value27);
            }
         }

         throw value28;
      }

      if (rtCore != null) {
         rtCore.close();
      }

      return referenceLightingResult26;
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
      require(meshResult.trianglesInBatch() > 0, "reference dynamic lighting terrain anchor must be visible");
      return new RendererFrameUpdate(batch, material, geometry, meshResult, frameState, BacklogSnapshot.empty(), dynamicScene);
   }

   private static RtFrameSnapshot pumpUntilFreshSnapshot(GuardedRtCore rtCore, long minimumSequence, String label) throws InterruptedException {
      RtFrameSnapshot lastSnapshot = rtCore.latestFrameSnapshot();
      long firstReadySequence = -1L;

      for(int frame = 0; frame < MAX_FRESH_PUMP_FRAMES; ++frame) {
         long sequence = minimumSequence + (long)frame;
         rtCore.acceptFrameUpdate(RendererFrameUpdate.empty(emptyBatch(), frameState(sequence)));
         RtSceneReadiness readiness = rtCore.sceneReadiness();
         if (firstReadySequence < 0L && readiness.builtRevisionIsCurrent() && !readiness.hasPendingRtBuilds()) {
            firstReadySequence = sequence;
         }

         lastSnapshot = rtCore.latestFrameSnapshot();
         if (lastSnapshot != null && firstReadySequence >= 0L && lastSnapshot.frameStateSequence() >= Math.max(firstReadySequence, minimumSequence)) {
            return lastSnapshot;
         }

         require(frame < MAX_INITIAL_READY_PUMP_FRAMES || firstReadySequence >= 0L, label + " did not build the initial RT world scene, readiness=" + readiness.asLogFragment() + ", activity=" + rtCore.runtimeActivity().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
         require(rtCore.state() == State.READY_FOR_SCENE_UPDATES, "RT core failed while pumping " + label + ": state=" + String.valueOf(rtCore.state()) + ", readiness=" + readiness.asLogFragment() + ", activity=" + rtCore.runtimeActivity().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
         Thread.sleep(PUMP_SLEEP_MILLIS);
      }

      throw new AssertionError(label + " did not produce a fresh native RT snapshot, minimumSequence=" + minimumSequence + ", firstReadySequence=" + firstReadySequence + ", lastSnapshot=" + (lastSnapshot == null ? "none" : lastSnapshot.asLogFragment()) + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", activity=" + rtCore.runtimeActivity().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
   }

   private static RtFrameSnapshot pollOnlyUntilFreshSnapshot(GuardedRtCore rtCore, long minimumSequence, String label) throws InterruptedException {
      RtFrameSnapshot lastSnapshot = rtCore.latestFrameSnapshot();

      for(int frame = 0; frame < MAX_FRESH_PUMP_FRAMES; ++frame) {
         rtCore.acceptFrameUpdate(RendererFrameUpdate.empty(emptyBatch(), RendererFrameState.unavailable(minimumSequence + (long)frame)));
         lastSnapshot = rtCore.latestFrameSnapshot();
         if (lastSnapshot != null && lastSnapshot.frameStateSequence() >= minimumSequence) {
            return lastSnapshot;
         }

         require(rtCore.state() == State.READY_FOR_SCENE_UPDATES, "RT core failed while polling " + label + ": state=" + String.valueOf(rtCore.state()) + ", lastSnapshot=" + (lastSnapshot == null ? "none" : lastSnapshot.asLogFragment()) + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", activity=" + rtCore.runtimeActivity().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
         Thread.sleep(PUMP_SLEEP_MILLIS);
      }

      throw new AssertionError(label + " did not complete the submitted native RT frame, minimumSequence=" + minimumSequence + ", lastSnapshot=" + (lastSnapshot == null ? "none" : lastSnapshot.asLogFragment()) + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", activity=" + rtCore.runtimeActivity().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
   }

   private static DynamicRenderScene referenceLightingScene(long revision, LightAnchors anchors, float phase, Palette palette) {
      ArrayList<DynamicRenderScene.SceneLight> lights = new ArrayList<>(66);
      lights.add(new DynamicRenderScene.SceneLight(1L, LightKind.SKY, 0.0, 0.0, 0.0, 0.0F, 0.0F, -1.0F, 1.0F, 0.22F, 4214896, false));
      lights.add(localLight(10L, LightKind.BLOCK_EMISSION, anchors.red(), palette.redRgb()));
      lights.add(localLight(11L, LightKind.ENTITY_EMISSION, anchors.green(), palette.greenRgb()));
      lights.add(localLight(12L, LightKind.BEAM_EMISSION, anchors.blue(), palette.blueRgb()));

      for(int index = 0; lights.size() < 65; ++index) {
         double t = (double)phase * 3.141592653589793 * 2.0 + (double)index * 0.47;
         double x = 8.0 + Math.cos(t) * (1.5 + (double)(index % 5));
         double y = 8.0 + Math.sin(t * 0.83) * (1.0 + (double)(index % 4));
         double z = 17.4 + (double)(index % 3) * 0.35;
         int value10000;
         switch (index % 3) {
            case 0 -> value10000 = 16746564;
            case 1 -> value10000 = 6750122;
            default -> value10000 = 6720767;
         }

         int rgb = value10000;
         lights.add(new DynamicRenderScene.SceneLight(100L + (long)index, index % 3 == 0 ? LightKind.BLOCK_EMISSION : (index % 3 == 1 ? LightKind.ENTITY_EMISSION : LightKind.BEAM_EMISSION), x, y, z, 0.0F, 0.0F, 0.0F, 0.75F, 0.05F, rgb, false));
      }

      return new DynamicRenderScene(revision, List.of(), List.of(), List.of(), List.of(new DynamicRenderScene.CelestialBody(CelestialKind.SUN, 0.0F, 0.0F, -1.0F, 0.06F, rgba8(255, 244, 184, 255), 0, 0.25F)), lights, dynamicLightmapPayload(revision));
   }

   private static LightmapPayload dynamicLightmapPayload(long revision) {
      float phase = (float)(revision & 31L) / 31.0F;
      int[] entries = new int[256];

      for(int firstCoordinate = 0; firstCoordinate < 16; ++firstCoordinate) {
         for(int secondCoordinate = 0; secondCoordinate < 16; ++secondCoordinate) {
            float first = (float)firstCoordinate / 15.0F;
            float second = (float)secondCoordinate / 15.0F;
            float coordinatedIntensity = Math.max(first, second);
            int red = Math.round(255.0F * Math.min(1.0F, 0.35F + second * (0.6F + 0.05F * phase)));
            int green = Math.round(255.0F * Math.min(1.0F, 0.35F + coordinatedIntensity * 0.5F));
            int blue = Math.round(255.0F * Math.min(1.0F, 0.35F + first * (0.6F + 0.05F * (1.0F - phase))));
            entries[LightmapPayload.index(firstCoordinate, secondCoordinate)] = red | green << 8 | blue << 16 | -16777216;
         }
      }

      return new LightmapPayload(revision, entries);
   }

   private static DynamicRenderScene.SceneLight localLight(long id, DynamicRenderScene.LightKind kind, LightAnchor anchor, int rgb) {
      return new DynamicRenderScene.SceneLight(id, kind, anchor.x(), anchor.y(), 17.35, 0.0F, 0.0F, 0.0F, 2.1F, 14.0F, rgb, false);
   }

   private static LightAnchors lightAnchors(RendererFrameState frameState) {
      return new LightAnchors(hitPointOnTerrain(frameState, RED_SAMPLE_X, SAMPLE_Y), hitPointOnTerrain(frameState, GREEN_SAMPLE_X, SAMPLE_Y), hitPointOnTerrain(frameState, BLUE_SAMPLE_X, SAMPLE_Y));
   }

   private static LightAnchor hitPointOnTerrain(RendererFrameState frameState, int sampleX, int sampleY) {
      float[] direction = rayDirection(frameState, sampleX, sampleY);
      double t = (16.0 - frameState.cameraZ()) / (double)direction[2];
      double x = frameState.cameraX() + (double)direction[0] * t;
      double y = frameState.cameraY() + (double)direction[1] * t;
      require(x > 0.5 && x < 15.5 && y > 0.5 && y < 15.5, "lighting sample did not land inside the terrain plane: sample=(" + sampleX + "," + sampleY + "), hit=(" + x + "," + y + ",16.0)");
      return new LightAnchor(x, y);
   }

   private static SectionTriangleMesh litTerrainPlane(SectionKey key) {
      int lowLight = SectionVoxelSnapshot.packMapColorAndLight(6316128, 0, 0);
      int skyLight = SectionVoxelSnapshot.packMapColorAndLight(6316128, 15, 0);
      int blockLight = SectionVoxelSnapshot.packMapColorAndLight(6316128, 0, 11);
      int lowLightVertex = PackedVoxelLighting.packFlatVertex(lowLight, FaceDirection.POSITIVE_Z);
      int skyLeftVertex = PackedVoxelLighting.packVertex(0, 160, PackedVoxelLighting.cardinalShade(FaceDirection.POSITIVE_Z));
      int skyRightVertex = PackedVoxelLighting.packVertex(0, 240, PackedVoxelLighting.cardinalShade(FaceDirection.POSITIVE_Z));
      int blockLightVertex = PackedVoxelLighting.packFlatVertex(blockLight, FaceDirection.POSITIVE_Z);
      byte knownLightMaterial = 33;
      return new SectionTriangleMesh(key, new short[]{fixed(0.0F), fixed(0.0F), fixed(16.0F), fixed(5.3333335F), fixed(0.0F), fixed(16.0F), fixed(5.3333335F), fixed(16.0F), fixed(16.0F), fixed(0.0F), fixed(16.0F), fixed(16.0F), fixed(5.3333335F), fixed(0.0F), fixed(16.0F), fixed(10.666667F), fixed(0.0F), fixed(16.0F), fixed(10.666667F), fixed(16.0F), fixed(16.0F), fixed(5.3333335F), fixed(16.0F), fixed(16.0F), fixed(10.666667F), fixed(0.0F), fixed(16.0F), fixed(16.0F), fixed(0.0F), fixed(16.0F), fixed(16.0F), fixed(16.0F), fixed(16.0F), fixed(10.666667F), fixed(16.0F), fixed(16.0F)}, new int[]{0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7, 8, 9, 10, 8, 10, 11}, new int[]{1, 1, 1}, new byte[]{0, 0, 0}, new byte[]{(byte)FaceDirection.POSITIVE_Z.ordinal(), (byte)FaceDirection.POSITIVE_Z.ordinal(), (byte)FaceDirection.POSITIVE_Z.ordinal()}, new int[]{lowLight, skyLight, blockLight}, new int[]{lowLightVertex, skyLeftVertex, blockLightVertex}, new int[]{lowLightVertex, skyRightVertex, blockLightVertex}, new int[]{lowLightVertex, skyRightVertex, blockLightVertex}, new int[]{lowLightVertex, skyLeftVertex, blockLightVertex}, new byte[]{0, 0, 0}, new byte[]{knownLightMaterial, knownLightMaterial, knownLightMaterial}, new int[]{0, 0, 0}, new int[]{RtTextureCatalog.packUv16(0.0F, 0.0F), RtTextureCatalog.packUv16(0.0F, 0.0F), RtTextureCatalog.packUv16(0.0F, 0.0F)}, new int[]{RtTextureCatalog.packUv16(1.0F, 0.0F), RtTextureCatalog.packUv16(1.0F, 0.0F), RtTextureCatalog.packUv16(1.0F, 0.0F)}, new int[]{RtTextureCatalog.packUv16(1.0F, 1.0F), RtTextureCatalog.packUv16(1.0F, 1.0F), RtTextureCatalog.packUv16(1.0F, 1.0F)}, new int[]{RtTextureCatalog.packUv16(0.0F, 1.0F), RtTextureCatalog.packUv16(0.0F, 1.0F), RtTextureCatalog.packUv16(0.0F, 1.0F)}, new byte[]{1, 1, 1}, new byte[]{0, 0, 0});
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

   private static void assertDominantRed(RtFrameSnapshot snapshot, int x, int y, String label) {
      require(countMatching(snapshot, x, y, 5, RtNativeReferenceDynamicLightingSceneSelfTest::isLocallyDominantRed) >= 10, label + " did not produce red-dominant RT lighting near sample, sample=(" + x + "," + y + "), snapshot=" + snapshot.asLogFragment() + ", colors=" + sampleWindow(snapshot, x, y, 2));
   }

   private static void assertDominantGreen(RtFrameSnapshot snapshot, int x, int y, String label) {
      require(countMatching(snapshot, x, y, 5, RtNativeReferenceDynamicLightingSceneSelfTest::isLocallyDominantGreen) >= 10, label + " did not produce green-dominant RT lighting near sample, sample=(" + x + "," + y + "), snapshot=" + snapshot.asLogFragment() + ", colors=" + sampleWindow(snapshot, x, y, 2));
   }

   private static void assertDominantBlue(RtFrameSnapshot snapshot, int x, int y, String label) {
      require(countMatching(snapshot, x, y, 5, RtNativeReferenceDynamicLightingSceneSelfTest::isLocallyDominantBlue) >= 10, label + " did not produce blue-dominant RT lighting near sample, sample=(" + x + "," + y + "), snapshot=" + snapshot.asLogFragment() + ", colors=" + sampleWindow(snapshot, x, y, 2));
   }

   private static void assertNoDominantColor(RtFrameSnapshot snapshot, int x, int y, String label) {
      require(countMatching(snapshot, x, y, 5, (pixel) -> isStrongDominantRed(pixel) || isStrongDominantGreen(pixel) || isStrongDominantBlue(pixel)) == 0, label + " left stale colored local-light pixels, sample=(" + x + "," + y + "), snapshot=" + snapshot.asLogFragment() + ", colors=" + sampleWindow(snapshot, x, y, 2));
   }

   private static void assertSkyLightTerrainVisible(RtFrameSnapshot snapshot, int x, int y, String label) {
      int average = averagePixel(snapshot.copyRgba8(), snapshot.width(), snapshot.height(), x, y, 5);
      int red = average & 255;
      int green = average >>> 8 & 255;
      int blue = average >>> 16 & 255;
      int luminance = luminance8(average);
      require(luminance >= 42 && blue >= red && green >= red - 4, label + " collapsed to the black/gray terrain failure seen in integration smoke, sample=(" + x + "," + y + "), luminance=" + luminance + ", average=" + RtFrameSnapshot.hex(average) + ", snapshot=" + snapshot.asLogFragment() + ", colors=" + sampleWindow(snapshot, x, y, 2));
   }

   private static void assertLowLightTerrainVisible(RtFrameSnapshot snapshot, int x, int y, String label) {
      int average = averagePixel(snapshot.copyRgba8(), snapshot.width(), snapshot.height(), x, y, 5);
      int luminance = luminance8(average);
      require(luminance >= 8, label + " collapsed to pure black under canopy-like low packed light, sample=(" + x + "," + y + "), luminance=" + luminance + ", average=" + RtFrameSnapshot.hex(average) + ", snapshot=" + snapshot.asLogFragment() + ", colors=" + sampleWindow(snapshot, x, y, 2));
   }

   private static void assertBlockLightTerrainWarm(RtFrameSnapshot snapshot, int x, int y, String label) {
      int average = averagePixel(snapshot.copyRgba8(), snapshot.width(), snapshot.height(), x, y, 5);
      int red = average & 255;
      int green = average >>> 8 & 255;
      int blue = average >>> 16 & 255;
      int luminance = luminance8(average);
      require(luminance >= 42 && red >= blue + 8 && green >= blue, label + " did not keep reference block-light warmth after dynamic lights were cleared, sample=(" + x + "," + y + "), luminance=" + luminance + ", average=" + RtFrameSnapshot.hex(average) + ", snapshot=" + snapshot.asLogFragment() + ", colors=" + sampleWindow(snapshot, x, y, 2));
   }

   private static void assertSmoothVertexLightingGradient(RtFrameSnapshot snapshot, String label) {
      byte[] rgba = snapshot.copyRgba8();
      int left = averagePixel(rgba, snapshot.width(), snapshot.height(), GRADIENT_LEFT_SAMPLE_X, SAMPLE_Y, 5);
      int center = averagePixel(rgba, snapshot.width(), snapshot.height(), GREEN_SAMPLE_X, SAMPLE_Y, 5);
      int right = averagePixel(rgba, snapshot.width(), snapshot.height(), GRADIENT_RIGHT_SAMPLE_X, SAMPLE_Y, 5);
      int leftLuminance = luminance8(left);
      int centerLuminance = luminance8(center);
      int rightLuminance = luminance8(right);
      int minimumQuantizedSpan = 7;
      require(leftLuminance + 2 <= centerLuminance && centerLuminance + 2 <= rightLuminance && rightLuminance >= leftLuminance + minimumQuantizedSpan, label + " collapsed to flat/stepped face lighting instead of RT-interpolated reference vertex lighting, luminance=(left=" + leftLuminance + ", center=" + centerLuminance + ", right=" + rightLuminance + "), colors=(left=" + RtFrameSnapshot.hex(left) + ", center=" + RtFrameSnapshot.hex(center) + ", right=" + RtFrameSnapshot.hex(right) + "), leftWindow=" + sampleWindow(snapshot, GRADIENT_LEFT_SAMPLE_X, SAMPLE_Y, 2) + ", centerWindow=" + sampleWindow(snapshot, GREEN_SAMPLE_X, SAMPLE_Y, 2) + ", rightWindow=" + sampleWindow(snapshot, GRADIENT_RIGHT_SAMPLE_X, SAMPLE_Y, 2) + ", snapshot=" + snapshot.asLogFragment());
   }

   private static long lightingSignature(RtFrameSnapshot snapshot) {
      byte[] rgba = snapshot.copyRgba8();
      long signature = -3750763034362895579L;
      signature = mixSignature(signature, averagePixel(rgba, snapshot.width(), snapshot.height(), RED_SAMPLE_X, SAMPLE_Y, 4));
      signature = mixSignature(signature, averagePixel(rgba, snapshot.width(), snapshot.height(), GREEN_SAMPLE_X, SAMPLE_Y, 4));
      signature = mixSignature(signature, averagePixel(rgba, snapshot.width(), snapshot.height(), BLUE_SAMPLE_X, SAMPLE_Y, 4));
      return signature;
   }

   private static long mixSignature(long signature, int rgba8) {
      signature ^= (long)rgba8;
      return signature * 1099511628211L;
   }

   private static int luminance8(int rgba8) {
      int red = rgba8 & 255;
      int green = rgba8 >>> 8 & 255;
      int blue = rgba8 >>> 16 & 255;
      return red * 54 + green * 183 + blue * 19 >> 8;
   }

   private static int averagePixel(byte[] rgba, int width, int height, int centerX, int centerY, int radius) {
      int count = 0;
      int red = 0;
      int green = 0;
      int blue = 0;
      int alpha = 0;

      for(int y = Math.max(0, centerY - radius); y <= Math.min(height - 1, centerY + radius); ++y) {
         for(int x = Math.max(0, centerX - radius); x <= Math.min(width - 1, centerX + radius); ++x) {
            int pixel = RtFrameSnapshot.pixel(rgba, width, x, y);
            red += pixel & 255;
            green += pixel >>> 8 & 255;
            blue += pixel >>> 16 & 255;
            alpha += pixel >>> 24 & 255;
            ++count;
         }
      }

      return red / count | green / count << 8 | blue / count << 16 | alpha / count << 24;
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

   private static boolean isLocallyDominantRed(int rgba8) {
      int red = rgba8 & 255;
      int green = rgba8 >>> 8 & 255;
      int blue = rgba8 >>> 16 & 255;
      return red >= 70 && red >= green + 15 && red >= blue + 15;
   }

   private static boolean isLocallyDominantGreen(int rgba8) {
      int red = rgba8 & 255;
      int green = rgba8 >>> 8 & 255;
      int blue = rgba8 >>> 16 & 255;
      return green >= 70 && green >= red + 15 && green >= blue + 15;
   }

   private static boolean isLocallyDominantBlue(int rgba8) {
      int red = rgba8 & 255;
      int green = rgba8 >>> 8 & 255;
      int blue = rgba8 >>> 16 & 255;
      return blue >= 70 && blue >= red + 15 && blue >= green + 15;
   }

   private static boolean isStrongDominantRed(int rgba8) {
      int red = rgba8 & 255;
      int green = rgba8 >>> 8 & 255;
      int blue = rgba8 >>> 16 & 255;
      return red >= 118 && red >= green + 30 && red >= blue + 30;
   }

   private static boolean isStrongDominantGreen(int rgba8) {
      int red = rgba8 & 255;
      int green = rgba8 >>> 8 & 255;
      int blue = rgba8 >>> 16 & 255;
      return green >= 118 && green >= red + 30 && green >= blue + 30;
   }

   private static boolean isStrongDominantBlue(int rgba8) {
      int red = rgba8 & 255;
      int green = rgba8 >>> 8 & 255;
      int blue = rgba8 >>> 16 & 255;
      return blue >= 118 && blue >= red + 30 && blue >= green + 30;
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

            result.append("(").append(x).append(",").append(y).append("=").append(RtFrameSnapshot.hex(RtFrameSnapshot.pixel(rgba, snapshot.width(), x, y))).append(")");
            ++emitted;
         }
      }

      return result.append("]").toString();
   }

   private static SceneUpdateBatch emptyBatch() {
      return new SceneUpdateBatch(Set.of(), Set.of(), Set.of(), Set.of(), Map.of(), false, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
   }

   private static Map<String, String> installReferenceLightingProperties() {
      Map<String, String> previous = new LinkedHashMap<>();
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

   static {
      RED_SAMPLE_X = OUTPUT_WIDTH * 5 / 16;
      GREEN_SAMPLE_X = OUTPUT_WIDTH / 2;
      BLUE_SAMPLE_X = OUTPUT_WIDTH * 11 / 16;
      GRADIENT_LEFT_SAMPLE_X = OUTPUT_WIDTH * 7 / 16;
      GRADIENT_RIGHT_SAMPLE_X = OUTPUT_WIDTH * 9 / 16;
      SAMPLE_Y = OUTPUT_HEIGHT / 2;
      SNAPSHOT_PATH = Path.of(System.getProperty("java.io.tmpdir"), "rtrenderer-native-reference-dynamic-lighting.png");
   }

   private static enum Palette {
      PRIMARY(16724008, 3538808, 6063359),
      SECONDARY(16742966, 3336408, 9202943);

      private final int redRgb;
      private final int greenRgb;
      private final int blueRgb;

      private Palette(int redRgb, int greenRgb, int blueRgb) {
         this.redRgb = redRgb;
         this.greenRgb = greenRgb;
         this.blueRgb = blueRgb;
      }

      int redRgb() {
         return this.redRgb;
      }

      int greenRgb() {
         return this.greenRgb;
      }

      int blueRgb() {
         return this.blueRgb;
      }
   }

   private static record LightAnchor(double x, double y) {
   }

   private static record LightAnchors(LightAnchor red, LightAnchor green, LightAnchor blue) {
   }

   private static record ReferenceLightingResult(RtFrameSnapshot lastSnapshot, RtSceneReadiness readiness, RtCore.RuntimeActivity activity, int dynamicUpdates, long completedFrames, double averageCompletedFps, int distinctChecksums) {
   }

   private interface PixelPredicate {
      boolean test(int value1);
   }
}
