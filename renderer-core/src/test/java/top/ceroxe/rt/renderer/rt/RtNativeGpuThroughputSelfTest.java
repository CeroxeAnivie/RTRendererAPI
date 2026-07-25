package top.ceroxe.rt.renderer.rt;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import jdk.jfr.Recording;
import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.DynamicRenderScene;
import top.ceroxe.rt.renderer.RendererFrameState;
import top.ceroxe.rt.renderer.RendererFrameUpdate;
import top.ceroxe.rt.renderer.RendererUpdateLoop;
import top.ceroxe.rt.renderer.DynamicRenderScene.LightKind;
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

public final class RtNativeGpuThroughputSelfTest {
   private static final int OUTPUT_WIDTH = intProperty("top.ceroxe.rt.rt.gpuThroughput.width", 1920);
   private static final int OUTPUT_HEIGHT = intProperty("top.ceroxe.rt.rt.gpuThroughput.height", 1080);
   private static final int SECTION_COLUMNS = intProperty("top.ceroxe.rt.rt.gpuThroughput.sectionColumns", 16);
   private static final int SECTION_ROWS = intProperty("top.ceroxe.rt.rt.gpuThroughput.sectionRows", 16);
   private static final int TOTAL_SECTIONS;
   private static final int WARMUP_VALID_FRAMES;
   private static final int MEASURED_VALID_FRAMES;
   private static final int MAX_INITIAL_PUMP_FRAMES;
   private static final int MIN_GPU_ONLY_COMPLETED_FRAMES;
   private static final int MAX_DRAIN_PUMP_FRAMES;
   private static final double MIN_COMPLETED_FPS = 100.0;
   private static final double MIN_LOW_COMPLETED_FPS = 50.0;
   private static final int LOW_FPS_COMPLETION_WINDOW_FRAMES = 8;
   private static final int SCENE_PRESSURE_MUTATION_PERIOD_FRAMES;
   private static final int SCENE_PRESSURE_MUTATION_SECTIONS;
   private static final int BLOCK_STATE_ID = 1;
   private static final int CUTOUT_TEXTURE_SIZE = 8;
   private static final String CUTOUT_TEXTURE = "rtrenderer:selftest/throughput_cutout";
   private static final String FIRE_TEXTURE_A = "rtrenderer:selftest/throughput_fire_a";
   private static final String FIRE_TEXTURE_B = "rtrenderer:selftest/throughput_fire_b";
   private static final Path SNAPSHOT_PATH;
   private static final String JFR_PATH_PROPERTY = "top.ceroxe.rt.rt.gpuThroughput.jfrPath";

   private RtNativeGpuThroughputSelfTest() {
   }

   public static void main(String[] args) throws Exception {
      Path recordingPath = optionalJfrPath();
      if (recordingPath == null) {
         run();
      } else {
         Path parent = recordingPath.getParent();
         if (parent != null) {
            Files.createDirectories(parent);
         }

         Recording recording = new Recording();

         try {
            recording.enable("jdk.ObjectAllocationSample").withStackTrace();
            recording.enable("jdk.GarbageCollection").withStackTrace();
            recording.enable("jdk.JavaMonitorEnter").withStackTrace();
            recording.enable("jdk.ThreadPark").withStackTrace();
            recording.start();

            try {
               run();
            } finally {
               recording.stop();
               recording.dump(recordingPath);
               System.out.println("RtNativeGpuThroughputSelfTest JFR=" + String.valueOf(recordingPath));
            }
         } catch (Throwable value11) {
            try {
               recording.close();
            } catch (Throwable value9) {
               value11.addSuppressed(value9);
            }

            throw value11;
         }

         recording.close();
      }
   }

   private static void run() throws Exception {
      RtTextureCatalog.TestTextureScope textures = RtTextureCatalog.installTestTexturesForSelfTest(testTextures());

      try {
         VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
         boolean condition10000 = capability.hardwareRayTracingReady();
         String details10001 = capability.summary();
         require(condition10000, "native GPU throughput gate requires production RT hardware: " + details10001);
         RtFrameSnapshot diagnostic = runDiagnosticReadback(capability, textures);
         writeSnapshotPng(diagnostic, SNAPSHOT_PATH);
         RtNativeStressGuards.assertFrameNotPathological(diagnostic, "GPU throughput diagnostic scene");
         ThroughputResult result = runGpuOnlyThroughput(capability, textures);
         condition10000 = result.averageCompletedFps() >= 100.0;
         double value8 = result.averageCompletedFps();
         require(condition10000, "GPU-only RT average throughput is below the RTX 3050-compatible floor, averageCompletedFps=" + value8 + ", minCompletedFps=100.0, minGpuOnlyCompletedFrames=" + MIN_GPU_ONLY_COMPLETED_FRAMES + ", completedFrames=" + result.completedFrames() + ", submittedFrames=" + result.submittedFrames() + ", residentPumpFrames=" + result.residentPumpFrames() + ", terrainMutationSections=" + result.terrainMutationSections() + ", pressureFrameUpdates=" + result.pressureFrameUpdates() + ", pressureElapsedMillis=" + result.pressureElapsedNanos() / 1000000L + ", elapsedMillis=" + result.elapsedNanos() / 1000000L + ", activity=" + result.activity().asLogFragment() + ", readiness=" + result.readiness().asLogFragment() + ", summary=" + result.summary().asLogFragment());
         condition10000 = result.lowCompletedFps() >= 50.0;
         value8 = result.lowCompletedFps();
         require(condition10000, "GPU-only RT throughput has a completed-frame low-FPS stall, lowCompletedFps=" + value8 + ", minLowCompletedFps=50.0, lowFpsSampleWindows=" + result.lowFpsSampleWindows() + ", maxCompletionGapMillis=" + (double)result.maxCompletionGapNanos() / 1000000.0 + ", completedFrames=" + result.completedFrames() + ", activity=" + result.activity().asLogFragment() + ", readiness=" + result.readiness().asLogFragment() + ", summary=" + result.summary().asLogFragment());
         int sectionCount10 = TOTAL_SECTIONS;
         System.out.println("RtNativeGpuThroughputSelfTest passed: sections=" + sectionCount10 + ", output=" + OUTPUT_WIDTH + "x" + OUTPUT_HEIGHT + ", submittedFrames=" + result.submittedFrames() + ", completedFrames=" + result.completedFrames() + ", residentPumpFrames=" + result.residentPumpFrames() + ", averageCompletedFps=" + result.averageCompletedFps() + ", lowCompletedFps=" + result.lowCompletedFps() + ", lowFpsSampleWindows=" + result.lowFpsSampleWindows() + ", maxCompletionGapMillis=" + (double)result.maxCompletionGapNanos() / 1000000.0 + ", terrainMutationSections=" + result.terrainMutationSections() + ", pressureFrameUpdates=" + result.pressureFrameUpdates() + ", pressureElapsedMillis=" + result.pressureElapsedNanos() / 1000000L + ", diagnostic=" + diagnostic.asLogFragment() + ", diagnosticPng=" + String.valueOf(SNAPSHOT_PATH) + ", activity=" + result.activity().asLogFragment() + ", readiness=" + result.readiness().asLogFragment());
         System.out.println(RtNativeBenchmarkReport.throughputScene("staticDense", OUTPUT_WIDTH, OUTPUT_HEIGHT, result.completedFrames(), result.averageCompletedFps(), result.lowCompletedFps(), result.activity(), result.readiness()));
      } catch (Throwable value5) {
         if (textures != null) {
            try {
               textures.close();
            } catch (Throwable value4) {
               value5.addSuppressed(value4);
            }
         }

         throw value5;
      }

      if (textures != null) {
         textures.close();
      }

   }

   private static Path optionalJfrPath() {
      String configured = System.getProperty("top.ceroxe.rt.rt.gpuThroughput.jfrPath");
      return configured != null && !configured.isBlank() ? Path.of(configured).toAbsolutePath() : null;
   }

   private static RtFrameSnapshot runDiagnosticReadback(VulkanRtCapabilityProbe.Result capability, RtTextureCatalog.TestTextureScope textures) throws InterruptedException {
      Map<String, String> previous = installProperties(true);

      RtFrameSnapshot snapshot5;
      try {
         GuardedRtCore rtCore = GuardedRtCore.isolatedHardwareTest();

         try {
            HostPressureScene scene = new HostPressureScene(textures);
            rtCore.acceptCapability(capability);
            requireReady(rtCore, "diagnostic readback");
            rtCore.acceptFrameUpdate(scene.initialUpdate(frameState(1L)));
            snapshot5 = pumpUntilStrictVisualSnapshot(rtCore, 2L, "GPU throughput diagnostic readback");
         } catch (Throwable value11) {
            if (rtCore != null) {
               try {
                  rtCore.close();
               } catch (Throwable value10) {
                  value11.addSuppressed(value10);
               }
            }

            throw value11;
         }

         if (rtCore != null) {
            rtCore.close();
         }
      } finally {
         restoreProperties(previous);
      }

      return snapshot5;
   }

   private static ThroughputResult runGpuOnlyThroughput(VulkanRtCapabilityProbe.Result capability, RtTextureCatalog.TestTextureScope textures) throws InterruptedException {
      Map<String, String> previous = installProperties(false);

      ThroughputResult throughputResult27;
      try {
         GuardedRtCore rtCore = GuardedRtCore.isolatedHardwareTest();

         try {
            HostPressureScene scene = new HostPressureScene(textures);
            rtCore.acceptCapability(capability);
            requireReady(rtCore, "GPU-only throughput");
            rtCore.acceptFrameUpdate(scene.initialUpdate(frameState(1L)));
            pumpUntilReady(rtCore, 2L, "GPU-only throughput initial scene");

            for(int frame = 0; frame < WARMUP_VALID_FRAMES; ++frame) {
               rtCore.acceptFrameUpdate(emptyUpdate(10000L + (long)frame));
            }

            drainPendingFrames(rtCore, 20000L, "GPU-only throughput warmup drain");
            long pressureStartNanos = System.nanoTime();
            int terrainMutationSections = 0;
            int pressureFrameUpdates = 0;

            for(int frame = 0; frame < MEASURED_VALID_FRAMES; ++frame) {
               RendererFrameUpdate update = scene.pressureUpdate(30000L + (long)frame, frame);
               if (update.hasTerrainChanges()) {
                  terrainMutationSections += update.batch().dirtySectionCount();
                  ++pressureFrameUpdates;
               }

               rtCore.acceptFrameUpdate(update);
            }

            drainSceneAndFrames(rtCore, 40000L, "GPU-only throughput measured drain");
            long pressureElapsedNanos = Math.max(1L, System.nanoTime() - pressureStartNanos);
            long startDispatches = rtCore.runtimeActivity().frameDispatches();
            long startNanos = System.nanoTime();
            CompletionRateTracker completionRates = new CompletionRateTracker(rtCore.runtimeActivity(), startNanos);
            int residentPumpFrames = 0;

            for(long submittedFrames = 0L; residentPumpFrames < MAX_DRAIN_PUMP_FRAMES; ++residentPumpFrames) {
               rtCore.acceptFrameUpdate(emptyUpdate(60000L + (long)residentPumpFrames));
               RtCore.RuntimeActivity activity = rtCore.runtimeActivity();
               completionRates.observe(activity, System.nanoTime());
               submittedFrames = activity.frameDispatches() - startDispatches;
               if (completionRates.completedFrames() >= (long)MIN_GPU_ONLY_COMPLETED_FRAMES) {
                  ++residentPumpFrames;
                  break;
               }

               requireReady(rtCore, "GPU-only throughput resident frame pump");
            }

            drainPendingFrames(rtCore, 70000L, "GPU-only throughput resident frame drain", completionRates);
            long elapsedNanos = Math.max(1L, System.nanoTime() - startNanos);
            RtCore.RuntimeActivity finalActivity = rtCore.runtimeActivity();
            long value38 = finalActivity.frameDispatches() - startDispatches;
            long completedFrames = completionRates.completedFrames();
            require(completedFrames > 0L && value38 > 0L, "GPU-only throughput gate did not submit and complete RT frames, completedFrames=" + completedFrames + ", submittedFrames=" + value38 + ", summary=" + rtCore.summary().asLogFragment());
            require(completedFrames >= (long)MIN_GPU_ONLY_COMPLETED_FRAMES, "GPU-only throughput gate completed too few resident frames for a stable measurement, completedFrames=" + completedFrames + ", minGpuOnlyCompletedFrames=" + MIN_GPU_ONLY_COMPLETED_FRAMES + ", submittedFrames=" + value38 + ", activity=" + rtCore.runtimeActivity().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
            boolean condition10000 = completionRates.completedFrames() >= (long)MIN_GPU_ONLY_COMPLETED_FRAMES;
            long value10001 = completionRates.completedFrames();
            require(condition10000, "GPU-only throughput low-FPS sampling missed completed frames, sampledCompletedFrames=" + value10001 + ", minGpuOnlyCompletedFrames=" + MIN_GPU_ONLY_COMPLETED_FRAMES + ", activity=" + rtCore.runtimeActivity().asLogFragment());
            condition10000 = finalActivity.frameReadbacks() == 0L;
            String logDetails46 = finalActivity.asLogFragment();
            require(condition10000, "GPU-only throughput gate must not use CPU frame readbacks, activity=" + logDetails46 + ", summary=" + rtCore.summary().asLogFragment());
            require(!finalActivity.pendingFrame(), "GPU-only throughput gate ended with uncompleted RT submissions, completedFrames=" + completedFrames + ", submittedFrames=" + value38 + ", activity=" + finalActivity.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
            RtCore.GpuFrameTiming gpuFrameTiming = finalActivity.gpuFrameTiming();
            condition10000 = gpuFrameTiming.enabled();
            logDetails46 = finalActivity.asLogFragment();
            require(condition10000, "GPU-only throughput gate did not enable Vulkan timestamp instrumentation, activity=" + logDetails46);
            condition10000 = gpuFrameTiming.completedSamples() > 0L;
            logDetails46 = finalActivity.asLogFragment();
            require(condition10000, "GPU-only throughput gate did not resolve any Vulkan timestamp samples, activity=" + logDetails46);
            condition10000 = gpuFrameTiming.failedSamples() == 0L;
            logDetails46 = finalActivity.asLogFragment();
            require(condition10000, "GPU-only throughput gate observed invalid Vulkan timestamp results, activity=" + logDetails46);
            condition10000 = gpuFrameTiming.averageTraceNanos() > 0L;
            logDetails46 = finalActivity.asLogFragment();
            require(condition10000, "GPU-only throughput gate reported no ray-trace GPU duration, activity=" + logDetails46);
            condition10000 = gpuFrameTiming.averageTotalNanos() >= gpuFrameTiming.averageTraceNanos();
            logDetails46 = finalActivity.asLogFragment();
            require(condition10000, "GPU-only throughput gate reported a total GPU duration shorter than ray tracing, activity=" + logDetails46);
            RtCore.GpuWorkTiming gpuWorkTiming = finalActivity.gpuWorkTiming();
            requireCompletedGpuStage(gpuWorkTiming.sectionBlas(), "sectionBlas", finalActivity);
            requireCompletedGpuStage(gpuWorkTiming.worldTlas(), "worldTlas", finalActivity);
            requireCompletedGpuStage(gpuWorkTiming.materialUpload(), "materialUpload", finalActivity);
            require(terrainMutationSections > 0, "host pressure throughput did not inject any terrain/material/fluid mutations");
            require(pressureFrameUpdates > 0, "host pressure throughput did not execute any dynamic terrain/material update frames");
            require("ready".equals(rtCore.sceneReadiness().frameDispatchBlockReason(throughputBacklog())), "host pressure throughput ended with stale TLAS/material state, terrainMutationSections=" + terrainMutationSections + ", activity=" + rtCore.runtimeActivity().asLogFragment() + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
            refreshBackendSummary(rtCore, 50000L);
            RtCore.Summary finalSummary = rtCore.summary();
            require(finalActivity.asLogFragment().contains("gpuFrame{enabled=true"), "runtime activity omitted structured GPU frame timing, activity=" + finalActivity.asLogFragment());
            require(finalSummary.asLogFragment().contains("gpuTimestamps{enabled=true"), "backend summary omitted Vulkan timestamp-pool diagnostics, summary=" + finalSummary.asLogFragment());
            throughputResult27 = new ThroughputResult(completedFrames, value38, elapsedNanos, (double)completedFrames * 1.0E9 / (double)elapsedNanos, completionRates.lowestCompletedFps(), completionRates.windowSamples(), completionRates.maxCompletionGapNanos(), residentPumpFrames, terrainMutationSections, pressureFrameUpdates, pressureElapsedNanos, finalActivity, rtCore.sceneReadiness(), finalSummary);
         } catch (Throwable value33) {
            if (rtCore != null) {
               try {
                  rtCore.close();
               } catch (Throwable value32) {
                  value33.addSuppressed(value32);
               }
            }

            throw value33;
         }

         if (rtCore != null) {
            rtCore.close();
         }
      } finally {
         restoreProperties(previous);
      }

      return throughputResult27;
   }

   private static void refreshBackendSummary(GuardedRtCore rtCore, long sequenceBase) {
      for(int frame = 0; frame < 128; ++frame) {
         rtCore.acceptFrameUpdate(unavailableEmptyUpdate(sequenceBase + (long)frame));
         requireReady(rtCore, "GPU throughput summary refresh");
      }

   }

   private static RendererFrameUpdate initialUpdate(RtTextureCatalog.TestTextureScope textures, RendererFrameState frameState) {
      Map<SectionKey, SectionTriangleMesh> meshes = buildMeshes(textures);
      SceneDatabase database = new SceneDatabase();
      Map<ChunkKey, List<SectionVoxelSnapshot>> sectionsByChunk = new LinkedHashMap<>();

      for(SectionKey key : meshes.keySet()) {
         sectionsByChunk.computeIfAbsent(key.chunkKey(), ignored -> new ArrayList<>()).add(filledSection(key));
      }

      for(Map.Entry<ChunkKey, List<SectionVoxelSnapshot>> entry : sectionsByChunk.entrySet()) {
         int minY = entry.getValue().stream().mapToInt(section -> section.key().y()).min().orElse(0);
         database.replaceChunkSnapshot(new ChunkSnapshot(entry.getKey(), minY, entry.getValue()));
      }

      SceneUpdateBatch batch = database.drainPendingUpdates();
      SectionMaterialCache.ApplyResult material = (new SectionMaterialCache()).apply(batch);
      SectionGeometryCache.ApplyResult geometry = SectionGeometryCache.transientProductionStaging().applyProducedFaceCounts(producedFaceCounts(meshes), batch.removedSections(), batch.fullResyncRequested());
      SectionMeshCache.ApplyResult meshResult = (new SectionMeshCache()).applyPrepared(meshes, batch.removedSections(), batch.fullResyncRequested());
      require(meshResult.trianglesInBatch() > 0, "GPU throughput scene must submit visible triangles");
      return new RendererFrameUpdate(batch, material, geometry, meshResult, frameState, throughputBacklog());
   }

   private static Map<SectionKey, SectionTriangleMesh> buildMeshes(RtTextureCatalog.TestTextureScope textures) {
      Map<SectionKey, SectionTriangleMesh> meshes = new LinkedHashMap<>();
      int cutoutTextureId = textures.textureId("rtrenderer:selftest/throughput_cutout");

      for(int y = 0; y < SECTION_ROWS; ++y) {
         for(int x = 0; x < SECTION_COLUMNS; ++x) {
            SectionKey key = new SectionKey(x, y, 0);
            int pattern = Math.floorMod(x * 31 + y * 17, 11);
            boolean liquid = pattern == 0 || pattern == 5;
            boolean cutout = pattern == 3 || pattern == 7;
            int value10000;
            switch (pattern % 5) {
               case 0 -> value10000 = 5016552;
               case 1 -> value10000 = 5810251;
               case 2 -> value10000 = 10526880;
               case 3 -> value10000 = 7323490;
               default -> value10000 = 13680752;
            }

            int rgb = value10000;
            meshes.put(key, sectionQuad(key, liquid ? 8 : 0, liquid, cutout, cutout ? cutoutTextureId : 0, SectionVoxelSnapshot.packMapColorAndLight(rgb, 15, liquid ? 0 : 2)));
         }
      }

      return Map.copyOf(meshes);
   }

   private static Map<SectionKey, Integer> producedFaceCounts(Map<SectionKey, SectionTriangleMesh> meshes) {
      Map<SectionKey, Integer> faceCounts = new LinkedHashMap<>(meshes.size());

      for(Map.Entry<SectionKey, SectionTriangleMesh> entry : meshes.entrySet()) {
         faceCounts.put((SectionKey)entry.getKey(), ((SectionTriangleMesh)entry.getValue()).faceCount());
      }

      return Map.copyOf(faceCounts);
   }

   private static SectionKey sectionKeyByLinearIndex(int index) {
      int wrapped = Math.floorMod(index, TOTAL_SECTIONS);
      return new SectionKey(wrapped % SECTION_COLUMNS, wrapped / SECTION_COLUMNS, 0);
   }

   private static int pressureMapColor(int pattern, int phase, boolean liquid, boolean fire) {
      if (fire) {
         return (phase & 1) == 0 ? 15895076 : 16766042;
      } else if (liquid) {
         int value4;
         switch (Math.floorMod(phase, 3)) {
            case 0 -> value4 = 14179106;
            case 1 -> value4 = 3898304;
            default -> value4 = 6858832;
         }

         return value4;
      } else {
         int value10000;
         switch (Math.floorMod(pattern, 5)) {
            case 0 -> value10000 = 5016552;
            case 1 -> value10000 = 5810251;
            case 2 -> value10000 = 10526880;
            case 3 -> value10000 = 7323490;
            default -> value10000 = 13680752;
         }

         return value10000;
      }
   }

   private static SectionTriangleMesh sectionQuad(SectionKey key, int mediumAmount, boolean liquid, boolean alphaCutout, int textureId, int packedMapColorAndLight) {
      return sectionQuad(key, mediumAmount, liquid, alphaCutout, textureId, 0, packedMapColorAndLight);
   }

   private static SectionTriangleMesh sectionQuad(SectionKey key, int mediumAmount, boolean liquid, boolean alphaCutout, int textureId, int lightEmission, int packedMapColorAndLight) {
      int flags = 33;
      if (liquid) {
         flags |= 4;
      }

      return new SectionTriangleMesh(key, new short[]{fixed(0.0F), fixed(0.0F), fixed(16.0F), fixed(16.0F), fixed(0.0F), fixed(16.0F), fixed(16.0F), fixed(16.0F), fixed(16.0F), fixed(0.0F), fixed(16.0F), fixed(16.0F)}, new int[]{0, 1, 2, 0, 2, 3}, new int[]{1}, new byte[]{(byte)mediumAmount}, new byte[]{(byte)FaceDirection.POSITIVE_Z.ordinal()}, new int[]{packedMapColorAndLight}, new byte[]{(byte)lightEmission}, new byte[]{(byte)flags}, new int[]{textureId}, new int[]{RtTextureCatalog.packUv16(0.0F, 0.0F)}, new int[]{RtTextureCatalog.packUv16(1.0F, 0.0F)}, new int[]{RtTextureCatalog.packUv16(1.0F, 1.0F)}, new int[]{RtTextureCatalog.packUv16(0.0F, 1.0F)}, new byte[]{(byte)(alphaCutout ? 1 : 0)}, new byte[]{(byte)(alphaCutout ? 1 : 0)});
   }

   private static SectionVoxelSnapshot filledSection(SectionKey key) {
      return filledSection(key, 0);
   }

   private static SectionVoxelSnapshot filledSection(SectionKey key, int phase) {
      int pattern = Math.floorMod(key.x() * 31 + key.y() * 17 + phase * 7, 13);
      boolean liquid = pattern == 0 || pattern == 5 || pattern == 9;
      boolean fire = pattern == 3 || pattern == 7;
      int[] ids = new int[4096];
      byte[] fluids = new byte[4096];
      int[] mapColors = new int[4096];
      byte[] emissions = new byte[4096];
      byte[] flags = new byte[4096];
      Arrays.fill(ids, 1);
      Arrays.fill(fluids, (byte)(liquid ? 4 + Math.floorMod(phase, 5) : 0));
      Arrays.fill(mapColors, SectionVoxelSnapshot.packMapColorAndLight(pressureMapColor(pattern, phase, liquid, fire), 15, fire ? 15 : 0));
      Arrays.fill(emissions, (byte)(fire ? 15 : 0));
      int blockFlags = 33 | (liquid ? 4 : 0);
      Arrays.fill(flags, (byte)blockFlags);
      return new SectionVoxelSnapshot(key, ids, fluids, mapColors, emissions, flags, false, false);
   }

   private static RtFrameSnapshot pumpUntilStrictVisualSnapshot(GuardedRtCore rtCore, long minimumSequence, String label) throws InterruptedException {
      RtFrameSnapshot snapshot = null;
      long strictReadyMinimumSequence = -1L;

      for(int frame = 0; frame < MAX_INITIAL_PUMP_FRAMES; ++frame) {
         long sequence = minimumSequence + (long)frame;
         rtCore.acceptFrameUpdate(emptyUpdate(sequence));
         snapshot = rtCore.latestFrameSnapshot();
         RtSceneReadiness readiness = rtCore.sceneReadiness();
         String strictReason = readiness.frameDispatchBlockReason(throughputBacklog());
         if ("ready".equals(strictReason) && strictReadyMinimumSequence < 0L) {
            strictReadyMinimumSequence = sequence;
         }

         if (strictReadyMinimumSequence >= 0L && snapshot != null && snapshot.frameStateSequence() >= strictReadyMinimumSequence) {
            return snapshot;
         }

         requireReady(rtCore, label);
      }

      throw new AssertionError(label + " did not produce a fresh RT readback, snapshot=" + (snapshot == null ? "none" : snapshot.asLogFragment()) + ", strictReadyMinimumSequence=" + strictReadyMinimumSequence + ", activity=" + rtCore.runtimeActivity().asLogFragment() + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", backlog=" + throughputBacklog().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
   }

   private static void pumpUntilReady(GuardedRtCore rtCore, long minimumSequence, String label) throws InterruptedException {
      long strictReadyMinimumSequence = -1L;

      for(int frame = 0; frame < MAX_INITIAL_PUMP_FRAMES; ++frame) {
         long sequence = minimumSequence + (long)frame;
         rtCore.acceptFrameUpdate(emptyUpdate(sequence));
         RtSceneReadiness readiness = rtCore.sceneReadiness();
         RtCore.RuntimeActivity activity = rtCore.runtimeActivity();
         if ("ready".equals(readiness.frameDispatchBlockReason(throughputBacklog())) && strictReadyMinimumSequence < 0L) {
            strictReadyMinimumSequence = sequence;
         }

         if (strictReadyMinimumSequence >= 0L && activity.latestCompletedFrameStateSequence() >= strictReadyMinimumSequence) {
            return;
         }

         requireReady(rtCore, label);
      }

      throw new AssertionError(label + " did not reach a ready GPU-only RT scene, activity=" + rtCore.runtimeActivity().asLogFragment() + ", strictReadyMinimumSequence=" + strictReadyMinimumSequence + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
   }

   private static void drainPendingFrames(GuardedRtCore rtCore, long sequenceBase, String label) {
      drainPendingFrames(rtCore, sequenceBase, label, (CompletionRateTracker)null);
   }

   private static void drainPendingFrames(GuardedRtCore rtCore, long sequenceBase, String label, CompletionRateTracker completionRates) {
      for(int frame = 0; frame < MAX_DRAIN_PUMP_FRAMES; ++frame) {
         RtCore.RuntimeActivity activity = rtCore.runtimeActivity();
         if (completionRates != null) {
            completionRates.observe(activity, System.nanoTime());
         }

         if (!activity.pendingFrame()) {
            return;
         }

         rtCore.acceptFrameUpdate(unavailableEmptyUpdate(sequenceBase + (long)frame));
         if (completionRates != null) {
            completionRates.observe(rtCore.runtimeActivity(), System.nanoTime());
         }

         requireReady(rtCore, label);
      }

      throw new AssertionError(label + " did not drain pending GPU frames, activity=" + rtCore.runtimeActivity().asLogFragment() + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
   }

   private static void drainSceneAndFrames(GuardedRtCore rtCore, long sequenceBase, String label) {
      long strictReadyMinimumDispatch = -1L;

      for(int frame = 0; frame < MAX_DRAIN_PUMP_FRAMES; ++frame) {
         long sequence = sequenceBase + (long)frame;
         RtSceneReadiness readinessBeforePump = rtCore.sceneReadiness();
         RtCore.RuntimeActivity activityBeforePump = rtCore.runtimeActivity();
         boolean sceneReady = "ready".equals(readinessBeforePump.frameDispatchBlockReason(throughputBacklog()));
         if (sceneReady && strictReadyMinimumDispatch < 0L) {
            strictReadyMinimumDispatch = activityBeforePump.frameDispatches() + 1L;
         }

         rtCore.acceptFrameUpdate(sceneReady && activityBeforePump.frameDispatches() < strictReadyMinimumDispatch ? emptyUpdate(sequence) : unavailableEmptyUpdate(sequence));
         RtSceneReadiness readiness = rtCore.sceneReadiness();
         RtCore.RuntimeActivity activity = rtCore.runtimeActivity();
         if (strictReadyMinimumDispatch >= 0L && !activity.pendingFrame() && activity.latestCompletedFrameDispatch() >= strictReadyMinimumDispatch) {
            return;
         }

         requireReady(rtCore, label);
      }

      throw new AssertionError(label + " did not drain GPU scene and frame work, strictReadyMinimumDispatch=" + strictReadyMinimumDispatch + ", activity=" + rtCore.runtimeActivity().asLogFragment() + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
   }

   private static RendererFrameState frameState(long sequence) {
      return new RendererFrameState(sequence, true, OUTPUT_WIDTH, OUTPUT_HEIGHT, (double)SECTION_COLUMNS * 8.0, (double)SECTION_ROWS * 8.0, 72.0, 0.0F, 0.0F, 0.0F, 0.0F, -1.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 1.7320508F, 1.7320508F, 1.0F, 0.0F, -1.0F, 0.0F, false, true);
   }

   private static SceneUpdateBatch emptyBatch() {
      return new SceneUpdateBatch(Set.of(), Set.of(), Set.of(), Set.of(), Map.of(), false, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
   }

   private static RendererFrameUpdate emptyUpdate(long sequence) {
      return RendererFrameUpdate.empty(emptyBatch(), frameState(sequence), throughputBacklog());
   }

   private static RendererFrameUpdate unavailableEmptyUpdate(long sequence) {
      return RendererFrameUpdate.empty(emptyBatch(), RendererFrameState.unavailable(sequence), throughputBacklog());
   }

   private static DynamicRenderScene throughputLightingScene() {
      float diagonal = 0.70710677F;
      return new DynamicRenderScene(1L, List.of(), List.of(), List.of(), List.of(), List.of(new DynamicRenderScene.SceneLight(1L, LightKind.SUN, 0.0, 0.0, 0.0, diagonal, 0.0F, diagonal, 1.0F, 1.0F, 16777215, true)));
   }

   private static RendererUpdateLoop.BacklogSnapshot throughputBacklog() {
      int sectionBudget = Math.max(1, Math.min(TOTAL_SECTIONS, 256));
      return new RendererUpdateLoop.BacklogSnapshot(0, 0, 0, 0, sectionBudget, sectionBudget, sectionBudget, 16, Math.max(16, sectionBudget), TOTAL_SECTIONS, TOTAL_SECTIONS, 0L);
   }

   private static List<RtTextureCatalog.TestTexture> testTextures() {
      return List.of(new RtTextureCatalog.TestTexture("rtrenderer:selftest/throughput_cutout", 8, 8, cutoutTexture()), new RtTextureCatalog.TestTexture("rtrenderer:selftest/throughput_fire_a", 8, 8, fireTexture(0)), new RtTextureCatalog.TestTexture("rtrenderer:selftest/throughput_fire_b", 8, 8, fireTexture(1)));
   }

   private static int[] cutoutTexture() {
      int[] pixels = new int[64];

      for(int y = 0; y < 8; ++y) {
         for(int x = 0; x < 8; ++x) {
            boolean leaf = (x + y) % 3 != 0;
            pixels[y * 8 + x] = leaf ? rgba8(70, 180, 72, 255) : rgba8(0, 0, 0, 0);
         }
      }

      return pixels;
   }

   private static int[] fireTexture(int phase) {
      int[] pixels = new int[64];

      for(int y = 0; y < 8; ++y) {
         for(int x = 0; x < 8; ++x) {
            boolean flame = y >= x / 2 && (x + y + phase) % 4 != 0;
            int warm = Math.min(255, 140 + y * 12 + phase * 20);
            pixels[y * 8 + x] = flame ? rgba8(255, warm, phase == 0 ? 32 : 72, 255) : rgba8(0, 0, 0, 0);
         }
      }

      return pixels;
   }

   private static Map<String, String> installProperties(boolean readbackEnabled) {
      Map<String, String> previous = new LinkedHashMap<>();
      int sectionCapacity = Math.max(1024, TOTAL_SECTIONS * 2);
      set(previous, "top.ceroxe.rt.rt.output.readback.enabled", Boolean.toString(readbackEnabled));
      set(previous, "top.ceroxe.rt.rt.output.readback.interval", readbackEnabled ? "1" : "1000000");
      set(previous, "top.ceroxe.rt.rt.output.dispatchInterval", "1");
      set(previous, "top.ceroxe.rt.rt.output.externalSemaphore.enabled", "false");
      set(previous, "top.ceroxe.rt.rt.output.frameResourceRingSize", "24");
      set(previous, "top.ceroxe.rt.rt.output.maxPendingFrames", "24");
      set(previous, "top.ceroxe.rt.rt.output.width", Integer.toString(OUTPUT_WIDTH));
      set(previous, "top.ceroxe.rt.rt.output.height", Integer.toString(OUTPUT_HEIGHT));
      set(previous, "top.ceroxe.rt.rt.output.maxPixels", Integer.toString(OUTPUT_WIDTH * OUTPUT_HEIGHT));
      set(previous, "top.ceroxe.rt.rt.worldTlas.minInitialInstances", "1");
      set(previous, "top.ceroxe.rt.rt.worldTlas.minRebuildIntervalMillis", "0");
      set(previous, "top.ceroxe.rt.rt.worldTlas.minStreamingRebuildIntervalMillis", "0");
      set(previous, "top.ceroxe.rt.rt.worldTlas.minStreamingRevisionDelta", "1");
      set(previous, "top.ceroxe.rt.rt.worldTlas.minStreamingInstanceDelta", "1");
      set(previous, "top.ceroxe.rt.rt.sectionBlas.maxBuildsPerFrame", "256");
      set(previous, "top.ceroxe.rt.rt.sectionBlas.maxTrianglesPerFrame", "8000000");
      set(previous, "top.ceroxe.rt.rt.sectionBlas.maxAsyncBuildsInFlight", "16");
      set(previous, "top.ceroxe.rt.rt.sectionBlas.maxAsyncBuildSectionsInFlight", Integer.toString(sectionCapacity));
      set(previous, "top.ceroxe.rt.rt.sectionBlas.maxAsyncBuildBytesInFlight", "1073741824");
      set(previous, "top.ceroxe.rt.rt.sectionBlas.maxPendingSections", Integer.toString(sectionCapacity));
      set(previous, "top.ceroxe.rt.rt.sectionBlas.maxPendingBytes", "1073741824");
      set(previous, "top.ceroxe.rt.rt.sectionBlas.maxCachedSections", Integer.toString(sectionCapacity));
      set(previous, "top.ceroxe.rt.rt.sectionBlas.maxCachedBytes", "1073741824");
      return previous;
   }

   private static void requireReady(GuardedRtCore rtCore, String label) {
      require(rtCore.state() == State.READY_FOR_SCENE_UPDATES, label + " RT core is not ready: state=" + String.valueOf(rtCore.state()) + ", activity=" + rtCore.runtimeActivity().asLogFragment() + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
   }

   private static void requireCompletedGpuStage(RtCore.GpuStageTiming timing, String label, RtCore.RuntimeActivity activity) {
      require(timing.enabled() && timing.completedSamples() > 0L && timing.averageNanos() > 0L, "GPU-only throughput gate did not resolve " + label + " timestamp evidence, activity=" + activity.asLogFragment());
      require(timing.failedSamples() == 0L, "GPU-only throughput gate observed invalid " + label + " timestamps, activity=" + activity.asLogFragment());
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

   private static double doubleProperty(String name, double defaultValue) {
      String raw = System.getProperty(name);
      if (raw != null && !raw.isBlank()) {
         try {
            double parsed = Double.parseDouble(raw.trim());
            return parsed > 0.0 ? parsed : defaultValue;
         } catch (NumberFormatException value6) {
            return defaultValue;
         }
      } else {
         return defaultValue;
      }
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

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }

   static {
      TOTAL_SECTIONS = SECTION_COLUMNS * SECTION_ROWS;
      WARMUP_VALID_FRAMES = intProperty("top.ceroxe.rt.rt.gpuThroughput.warmupFrames", 240);
      MEASURED_VALID_FRAMES = intProperty("top.ceroxe.rt.rt.gpuThroughput.measuredFrames", 1800);
      MAX_INITIAL_PUMP_FRAMES = intProperty("top.ceroxe.rt.rt.gpuThroughput.maxInitialPumpFrames", 2400);
      MIN_GPU_ONLY_COMPLETED_FRAMES = intProperty("top.ceroxe.rt.rt.gpuThroughput.minGpuOnlyCompletedFrames", 512);
      MAX_DRAIN_PUMP_FRAMES = intProperty("top.ceroxe.rt.rt.gpuThroughput.maxDrainPumpFrames", Math.max(12000, MIN_GPU_ONLY_COMPLETED_FRAMES * 128));
      SCENE_PRESSURE_MUTATION_PERIOD_FRAMES = intProperty("top.ceroxe.rt.rt.gpuThroughput.hostMutationPeriodFrames", 8);
      SCENE_PRESSURE_MUTATION_SECTIONS = intProperty("top.ceroxe.rt.rt.gpuThroughput.hostMutationSections", 2);
      SNAPSHOT_PATH = Path.of(System.getProperty("java.io.tmpdir"), "rtrenderer-native-gpu-throughput-diagnostic.png");
   }

   private static final class HostPressureScene {
      private final RtTextureCatalog.TestTextureScope textures;
      private final SectionMaterialCache materialCache = new SectionMaterialCache();
      private final SectionGeometryCache geometryCache = SectionGeometryCache.transientProductionStaging();
      private final SectionMeshCache meshCache = new SectionMeshCache();

      private HostPressureScene(RtTextureCatalog.TestTextureScope textures) {
         this.textures = textures;
      }

      RendererFrameUpdate initialUpdate(RendererFrameState frameState) {
         Map<SectionKey, SectionVoxelSnapshot> snapshots = new LinkedHashMap<>();
         Map<SectionKey, SectionTriangleMesh> meshes = new LinkedHashMap<>();
         Set<SectionKey> dirtySections = new LinkedHashSet<>();
         Set<ChunkKey> dirtyChunks = new LinkedHashSet<>();

         for(int index = 0; index < RtNativeGpuThroughputSelfTest.TOTAL_SECTIONS; ++index) {
            SectionKey key = RtNativeGpuThroughputSelfTest.sectionKeyByLinearIndex(index);
            dirtySections.add(key);
            dirtyChunks.add(key.chunkKey());
            snapshots.put(key, RtNativeGpuThroughputSelfTest.filledSection(key, index));
            meshes.put(key, this.pressureSectionMesh(key, index));
         }

         SceneUpdateBatch batch = new SceneUpdateBatch(dirtySections, dirtyChunks, Set.of(), Set.of(), snapshots, true, (long)dirtySections.size(), 0L, (long)dirtyChunks.size(), (long)dirtyChunks.size(), 0L, 0L, 1L, 21);
         return this.apply(batch, meshes, frameState, RtNativeGpuThroughputSelfTest.throughputLightingScene());
      }

      RendererFrameUpdate pressureUpdate(long sequence, int frame) {
         if (frame % RtNativeGpuThroughputSelfTest.SCENE_PRESSURE_MUTATION_PERIOD_FRAMES != 0) {
            return RtNativeGpuThroughputSelfTest.emptyUpdate(sequence);
         } else {
            int mutationOrdinal = frame / RtNativeGpuThroughputSelfTest.SCENE_PRESSURE_MUTATION_PERIOD_FRAMES;
            int mutationSections = Math.max(1, Math.min(RtNativeGpuThroughputSelfTest.SCENE_PRESSURE_MUTATION_SECTIONS, RtNativeGpuThroughputSelfTest.TOTAL_SECTIONS));
            Map<SectionKey, SectionVoxelSnapshot> snapshots = new LinkedHashMap<>();
            Map<SectionKey, SectionTriangleMesh> meshes = new LinkedHashMap<>();
            Set<SectionKey> dirtySections = new LinkedHashSet<>();
            Set<ChunkKey> dirtyChunks = new LinkedHashSet<>();
            Map<SectionKey, Integer> sectionSourceFlags = new LinkedHashMap<>();
            boolean streamingLikeBatch = mutationOrdinal % 4 == 0;
            int sourceFlags = 3;
            if (streamingLikeBatch) {
               sourceFlags |= 4;
            }

            for(int offset = 0; offset < mutationSections; ++offset) {
               int index = Math.floorMod(mutationOrdinal * mutationSections + offset, RtNativeGpuThroughputSelfTest.TOTAL_SECTIONS);
               SectionKey key = RtNativeGpuThroughputSelfTest.sectionKeyByLinearIndex(index);
               int phase = mutationOrdinal + offset + 1;
               dirtySections.add(key);
               dirtyChunks.add(key.chunkKey());
               snapshots.put(key, RtNativeGpuThroughputSelfTest.filledSection(key, phase));
               meshes.put(key, this.pressureSectionMesh(key, phase));
               sectionSourceFlags.put(key, sourceFlags);
            }

            SceneUpdateBatch batch = new SceneUpdateBatch(dirtySections, dirtyChunks, Set.of(), Set.of(), snapshots, false, (long)mutationSections, (long)mutationSections, streamingLikeBatch ? (long)dirtyChunks.size() : 0L, streamingLikeBatch ? (long)dirtyChunks.size() : 0L, 0L, 0L, 0L, sourceFlags, sectionSourceFlags);
            return this.apply(batch, meshes, RtNativeGpuThroughputSelfTest.frameState(sequence), DynamicRenderScene.empty());
         }
      }

      private RendererFrameUpdate apply(SceneUpdateBatch batch, Map<SectionKey, SectionTriangleMesh> meshes, RendererFrameState frameState, DynamicRenderScene dynamicScene) {
         SectionMaterialCache.ApplyResult material = this.materialCache.apply(batch);
         SectionGeometryCache.ApplyResult geometry = this.geometryCache.applyProducedFaceCounts(RtNativeGpuThroughputSelfTest.producedFaceCounts(meshes), batch.removedSections(), batch.fullResyncRequested());
         SectionMeshCache.ApplyResult mesh = this.meshCache.applyPrepared(meshes, batch.removedSections(), batch.fullResyncRequested());
         RtNativeGpuThroughputSelfTest.require(mesh.trianglesInBatch() > 0, "host pressure update must submit visible triangles");
         return new RendererFrameUpdate(batch, material, geometry, mesh, frameState, RtNativeGpuThroughputSelfTest.throughputBacklog(), dynamicScene);
      }

      private SectionTriangleMesh pressureSectionMesh(SectionKey key, int phase) {
         int pattern = Math.floorMod(key.x() * 31 + key.y() * 17 + phase * 7, 13);
         boolean liquid = pattern == 0 || pattern == 5 || pattern == 9;
         boolean fire = pattern == 3 || pattern == 7;
         boolean cutout = fire || pattern == 4 || pattern == 11;
         int textureId = fire ? this.textures.textureId((phase & 1) == 0 ? "rtrenderer:selftest/throughput_fire_a" : "rtrenderer:selftest/throughput_fire_b") : (cutout ? this.textures.textureId("rtrenderer:selftest/throughput_cutout") : 0);
         int mediumAmount = liquid ? 4 + Math.floorMod(phase, 5) : 0;
         int lightEmission = fire ? 15 : 0;
         int rgb = RtNativeGpuThroughputSelfTest.pressureMapColor(pattern, phase, liquid, fire);
         return RtNativeGpuThroughputSelfTest.sectionQuad(key, mediumAmount, liquid, cutout, textureId, lightEmission, SectionVoxelSnapshot.packMapColorAndLight(rgb, 15, Math.max(lightEmission, liquid ? 0 : 2)));
      }
   }

   private static record ThroughputResult(long completedFrames, long submittedFrames, long elapsedNanos, double averageCompletedFps, double lowCompletedFps, long lowFpsSampleWindows, long maxCompletionGapNanos, int residentPumpFrames, int terrainMutationSections, int pressureFrameUpdates, long pressureElapsedNanos, RtCore.RuntimeActivity activity, RtSceneReadiness readiness, RtCore.Summary summary) {
   }

   private static final class CompletionRateTracker {
      private long completedDispatches;
      private long sampleNanos;
      private long completedFrames;
      private long completionWindowStartNanos;
      private long completionWindowFrames;
      private long windowSamples;
      private long maxCompletionGapNanos;
      private double lowestCompletedFps = 1.0 / 0.0;

      private CompletionRateTracker(RtCore.RuntimeActivity activity, long sampleNanos) {
         this.completedDispatches = activity.latestCompletedFrameDispatch();
         this.sampleNanos = sampleNanos;
      }

      private void observe(RtCore.RuntimeActivity activity, long nowNanos) {
         long completed = activity.latestCompletedFrameDispatch();
         if (completed < this.completedDispatches) {
            throw new AssertionError("completed RT frame dispatch ordinal moved backwards");
         } else {
            long completedDelta = completed - this.completedDispatches;
            long elapsedNanos = nowNanos - this.sampleNanos;
            if (completedDelta > 0L && elapsedNanos > 0L) {
               this.maxCompletionGapNanos = Math.max(this.maxCompletionGapNanos, elapsedNanos);
               if (this.completionWindowFrames == 0L) {
                  this.completionWindowStartNanos = this.sampleNanos;
               }

               this.completionWindowFrames += completedDelta;
               this.completedFrames += completedDelta;
               this.completedDispatches = completed;
               this.sampleNanos = nowNanos;
               if (this.completionWindowFrames >= 8L) {
                  long windowElapsedNanos = Math.max(1L, nowNanos - this.completionWindowStartNanos);
                  double completedFps = (double)this.completionWindowFrames * 1.0E9 / (double)windowElapsedNanos;
                  this.lowestCompletedFps = Math.min(this.lowestCompletedFps, completedFps);
                  ++this.windowSamples;
                  this.completionWindowFrames = 0L;
                  this.completionWindowStartNanos = 0L;
               }
            }

         }
      }

      private long completedFrames() {
         return this.completedFrames;
      }

      private double lowestCompletedFps() {
         return Double.isFinite(this.lowestCompletedFps) ? this.lowestCompletedFps : 0.0;
      }

      private long windowSamples() {
         return this.windowSamples;
      }

      private long maxCompletionGapNanos() {
         return this.maxCompletionGapNanos;
      }
   }
}
