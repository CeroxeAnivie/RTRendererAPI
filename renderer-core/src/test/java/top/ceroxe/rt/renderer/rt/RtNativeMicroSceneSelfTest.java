package top.ceroxe.rt.renderer.rt;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import javax.imageio.ImageIO;
import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.DynamicRenderScene;
import top.ceroxe.rt.renderer.LightmapPayload;
import top.ceroxe.rt.renderer.RendererFrameState;
import top.ceroxe.rt.renderer.RendererFrameUpdate;
import top.ceroxe.rt.renderer.DynamicRenderScene.LightKind;
import top.ceroxe.rt.renderer.RendererUpdateLoop.BacklogSnapshot;
import top.ceroxe.rt.renderer.rt.material.RtSceneMaterialTable;
import top.ceroxe.rt.renderer.rt.material.RtTextureCatalog;
import top.ceroxe.rt.renderer.rt.pipeline.RtFrameSnapshot;
import top.ceroxe.rt.renderer.rt.pipeline.RtGBufferSnapshot;
import top.ceroxe.rt.renderer.rt.runtime.GuardedRtCore;
import top.ceroxe.rt.renderer.rt.runtime.RtCore.State;
import top.ceroxe.rt.renderer.scene.ChunkKey;
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

public final class RtNativeMicroSceneSelfTest {
   private static final int OUTPUT_SIZE = 64;
   private static final int HIGH_RES_OUTPUT_WIDTH = 960;
   private static final int HIGH_RES_OUTPUT_HEIGHT = 540;
   private static final int BLOCK_STATE_ID = 1;
   private static final int MIN_EXPECTED_FOREGROUND_PIXELS = 512;
   private static final int MAX_PUMP_FRAMES = 600;
   private static final long PUMP_SLEEP_MILLIS = 5L;
   private static final Path SNAPSHOT_PATH = Path.of(System.getProperty("java.io.tmpdir"), "rtrenderer-native-micro-scene.png");
   private static final Path CAMERA_PROBE_SNAPSHOT_PATH = Path.of(System.getProperty("java.io.tmpdir"), "rtrenderer-native-camera-probe-scene.png");

   private RtNativeMicroSceneSelfTest() {
   }

   public static void main(String[] args) throws Exception {
      Map<String, String> previousProperties = installDiagnosticProperties();

      try {
         VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
         boolean condition10000 = capability.hardwareRayTracingReady();
         String details10001 = capability.summary();
         require(condition10000, "native micro-scene requires production RT hardware: " + details10001);
         RtFrameSnapshot snapshot = runMicroScene(capability);
         writeSnapshotPng(snapshot, SNAPSHOT_PATH);
         condition10000 = snapshot.foregroundPixels() >= 512;
         details10001 = snapshot.asLogFragment();
         require(condition10000, "native micro-scene foreground coverage is implausibly sparse: " + details10001 + ", foregroundSample=" + foregroundSample(snapshot, 64) + ", png=" + String.valueOf(SNAPSHOT_PATH) + ", expectedAtLeast=512");
         RtGBufferSnapshot gBuffer = runGBufferScene(capability);
         assertCenterGBuffer(gBuffer);
         RtFrameSnapshot cameraProbeSnapshot = runCameraProbeScene(capability);
         writeSnapshotPng(cameraProbeSnapshot, CAMERA_PROBE_SNAPSHOT_PATH);
         condition10000 = cameraProbeSnapshot.foregroundPixels() >= 64;
         details10001 = cameraProbeSnapshot.asLogFragment();
         require(condition10000, "native smoke-camera probe scene missed nearby geometry: " + details10001 + ", foregroundSample=" + foregroundSample(cameraProbeSnapshot, 64) + ", png=" + String.valueOf(CAMERA_PROBE_SNAPSHOT_PATH));
         RtFrameSnapshot dynamicReplacementSnapshot = runDynamicReplacementScene(capability);
         DirectionalShadowResult directionalShadow = runDirectionalShadowScene(capability);
         DynamicDeletionResult dynamicDeletion = runDynamicSectionDeletionScene(capability);
         DynamicDeletionResult dynamicBlockDeletion = runDynamicBlockDeletionWithinSectionScene(capability);
         RtFrameSnapshot alphaHighResolutionSnapshot = runAlphaMixedHighResolutionScene(capability);
         PrintStream output16 = System.out;
         details10001 = snapshot.asLogFragment();
         output16.println("RtNativeMicroSceneSelfTest passed: " + details10001 + ", png=" + String.valueOf(SNAPSHOT_PATH) + ", cameraProbe=" + cameraProbeSnapshot.asLogFragment() + ", cameraProbePng=" + String.valueOf(CAMERA_PROBE_SNAPSHOT_PATH) + ", dynamicReplacement=" + dynamicReplacementSnapshot.asLogFragment() + ", directionalShadow=" + directionalShadow.asLogFragment() + ", dynamicDeletion=" + dynamicDeletion.asLogFragment() + ", dynamicBlockDeletion=" + dynamicBlockDeletion.asLogFragment() + ", alphaHighResolution=" + alphaHighResolutionSnapshot.asLogFragment());
      } finally {
         restoreProperties(previousProperties);
      }

   }

   private static RtFrameSnapshot runMicroScene(VulkanRtCapabilityProbe.Result capability) throws InterruptedException {
      return runSceneUntilFreshSnapshot(capability, buildPreparedMeshFrameUpdate(fullSectionPositiveZQuad(new SectionKey(0, 0, 0)), frameState(1L)));
   }

   private static RtGBufferSnapshot runGBufferScene(VulkanRtCapabilityProbe.Result capability) throws InterruptedException {
      GuardedRtCore rtCore = GuardedRtCore.isolatedHardwareTest();

      RtGBufferSnapshot rtGBufferSnapshot5;
      try {
         rtCore.acceptCapability(capability);
         require(rtCore.state() == State.READY_FOR_SCENE_UPDATES, "RT core did not open diagnostic G-buffer backend");
         RendererFrameUpdate initial = buildPreparedMeshFrameUpdate(fullSectionPositiveZQuad(new SectionKey(0, 0, 0)), frameState(1L));
         rtCore.acceptFrameUpdate(initial);
         require(rtCore.requestGBufferCapture(), "diagnostic G-buffer request was rejected");
         int frame = 2;

         while(true) {
            if (frame > 601) {
               throw new AssertionError("diagnostic G-buffer did not complete: " + rtCore.summary().asLogFragment());
            }

            rtCore.acceptFrameUpdate(RendererFrameUpdate.empty(emptyBatch(), copyFrameStateSequence(initial.frameState(), (long)frame)));
            RtGBufferSnapshot snapshot = rtCore.latestGBufferSnapshot();
            if (snapshot != null) {
               rtGBufferSnapshot5 = snapshot;
               break;
            }

            require(rtCore.state() == State.READY_FOR_SCENE_UPDATES, "RT core failed during diagnostic G-buffer capture: " + rtCore.summary().asLogFragment());
            Thread.sleep(5L);
            ++frame;
         }
      } catch (Throwable value7) {
         if (rtCore != null) {
            try {
               rtCore.close();
            } catch (Throwable value6) {
               value7.addSuppressed(value6);
            }
         }

         throw value7;
      }

      if (rtCore != null) {
         rtCore.close();
      }

      return rtGBufferSnapshot5;
   }

   private static void assertCenterGBuffer(RtGBufferSnapshot snapshot) {
      int center = snapshot.height() / 2 * snapshot.width() + snapshot.width() / 2;
      require(Float.isFinite(snapshot.depth()[center]) && snapshot.depth()[center] > 0.0F, "center G-buffer depth must describe a real hit");
      require(snapshot.materialIds()[center] != -1, "center G-buffer material must not be the miss sentinel");
      require(snapshot.normalOct16()[center] == 0, "center G-buffer normal must encode the fixture's +Z surface exactly");
      require((snapshot.albedoRgba8()[center] & 16777215) != 0, "center G-buffer albedo must be populated");
   }

   private static RtFrameSnapshot runCameraProbeScene(VulkanRtCapabilityProbe.Result capability) throws InterruptedException {
      return runSceneUntilFreshSnapshot(capability, buildFrameUpdate(smokeCameraProbeSection(), smokeCameraFrameState(1L)));
   }

   private static RtFrameSnapshot runDynamicReplacementScene(VulkanRtCapabilityProbe.Result capability) throws InterruptedException {
      GuardedRtCore rtCore = GuardedRtCore.isolatedHardwareTest();

      RtFrameSnapshot snapshot6;
      try {
         rtCore.acceptCapability(capability);
         boolean condition10000 = rtCore.state() == State.READY_FOR_SCENE_UPDATES;
         String stateDetails10001 = String.valueOf(rtCore.state());
         require(condition10000, "RT core did not open native backend for dynamic replacement: state=" + stateDetails10001 + ", summary=" + rtCore.summary().asLogFragment());
         MicroSceneState scene = new MicroSceneState();
         SectionKey key = new SectionKey(0, 0, 0);
         rtCore.acceptFrameUpdate(scene.replacePreparedMesh(tintedPositiveZQuad(key, 13643824), frameState(1L)));
         RtFrameSnapshot first = pumpUntilFreshSnapshot(rtCore, frameState(2L), 2L, "dynamic replacement first");
         rtCore.acceptFrameUpdate(scene.replacePreparedMesh(tintedPositiveZQuad(key, 3199072), frameState(100L)));
         RtFrameSnapshot second = pumpUntilFreshSnapshot(rtCore, frameState(101L), 101L, "dynamic replacement second", (snapshot) -> snapshot.checksum() != first.checksum() || snapshot.center() != first.center());
         condition10000 = first.checksum() != second.checksum() || first.center() != second.center();
         stateDetails10001 = first.asLogFragment();
         require(condition10000, "dynamic replacement did not change RT output: first=" + stateDetails10001 + ", second=" + second.asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
         snapshot6 = second;
      } catch (Throwable value8) {
         if (rtCore != null) {
            try {
               rtCore.close();
            } catch (Throwable value7) {
               value8.addSuppressed(value7);
            }
         }

         throw value8;
      }

      if (rtCore != null) {
         rtCore.close();
      }

      return snapshot6;
   }

   private static DirectionalShadowResult runDirectionalShadowScene(VulkanRtCapabilityProbe.Result capability) throws InterruptedException {
      GuardedRtCore rtCore = GuardedRtCore.isolatedHardwareTest();

      DirectionalShadowResult directionalShadowResult12;
      try {
         rtCore.acceptCapability(capability);
         require(rtCore.state() == State.READY_FOR_SCENE_UPDATES, "RT core did not open native backend for directional shadow scene");
         RendererFrameState initialFrame = frameState(1L);
         rtCore.acceptFrameUpdate(buildPreparedMeshFrameUpdate(directionalShadowFixture(new SectionKey(0, 0, 0)), initialFrame, directionalShadowScene(1L, true)));
         RtFrameSnapshot shadowed = pumpUntilFreshSnapshot(rtCore, frameState(2L), 2L, "directional shadow enabled");
         rtCore.acceptFrameUpdate(RendererFrameUpdate.dynamicOnly(emptyBatch(), frameState(100L), BacklogSnapshot.empty(), directionalShadowScene(2L, false)));
         RtFrameSnapshot unshadowed = pumpUntilFreshSnapshot(rtCore, frameState(101L), 101L, "directional shadow disabled", (snapshot) -> snapshot.checksum() != shadowed.checksum());
         int sampleY = 32;
         int shadowSampleX = 28;
         int litSampleX = 40;
         int shadowedOccluded = pixelLuminance(shadowed, shadowSampleX, sampleY);
         int unshadowedOccluded = pixelLuminance(unshadowed, shadowSampleX, sampleY);
         int shadowedLit = pixelLuminance(shadowed, litSampleX, sampleY);
         int unshadowedLit = pixelLuminance(unshadowed, litSampleX, sampleY);
         require(unshadowedOccluded >= shadowedOccluded + 90, "directional visibility ray did not darken the occluded terrain sample, shadowed=" + shadowedOccluded + ", unshadowed=" + unshadowedOccluded + ", shadowedFrame=" + shadowed.asLogFragment() + ", unshadowedFrame=" + unshadowed.asLogFragment());
         require(Math.abs(unshadowedLit - shadowedLit) <= 24, "directional visibility ray changed the non-occluded control sample, shadowed=" + shadowedLit + ", unshadowed=" + unshadowedLit);
         directionalShadowResult12 = new DirectionalShadowResult(shadowed, unshadowed, shadowedOccluded, unshadowedOccluded, shadowedLit, unshadowedLit);
      } catch (Throwable value14) {
         if (rtCore != null) {
            try {
               rtCore.close();
            } catch (Throwable value13) {
               value14.addSuppressed(value13);
            }
         }

         throw value14;
      }

      if (rtCore != null) {
         rtCore.close();
      }

      return directionalShadowResult12;
   }

   private static DynamicDeletionResult runDynamicSectionDeletionScene(VulkanRtCapabilityProbe.Result capability) throws InterruptedException {
      RtTextureCatalog.TestTextureScope textures = RtTextureCatalog.installTestTexturesForSelfTest(List.of(new RtTextureCatalog.TestTexture("selftest:dynamic_deletion_front", 2, 2, solidTexture(216, 40, 40, 255, 2, 2)), new RtTextureCatalog.TestTexture("selftest:dynamic_deletion_back", 2, 2, solidTexture(32, 192, 96, 255, 2, 2))));

      DynamicDeletionResult dynamicDeletionResult10;
      try {
         GuardedRtCore rtCore = GuardedRtCore.isolatedHardwareTest();

         try {
            rtCore.acceptCapability(capability);
            boolean condition10000 = rtCore.state() == State.READY_FOR_SCENE_UPDATES;
            String stateDetails10001 = String.valueOf(rtCore.state());
            require(condition10000, "RT core did not open native backend for dynamic deletion: state=" + stateDetails10001 + ", summary=" + rtCore.summary().asLogFragment());
            MicroSceneState scene = new MicroSceneState();
            SectionKey backKey = new SectionKey(0, 0, 0);
            SectionKey frontKey = new SectionKey(0, 0, 1);
            int frontTextureId = textures.textureId("selftest:dynamic_deletion_front");
            int backTextureId = textures.textureId("selftest:dynamic_deletion_back");
            rtCore.acceptFrameUpdate(scene.replacePreparedMeshes(Map.of(backKey, texturedPositiveZQuad(backKey, backTextureId), frontKey, texturedPositiveZQuad(frontKey, frontTextureId)), frameState(1L)));
            RtFrameSnapshot front = pumpUntilFreshSnapshot(rtCore, frameState(2L), 2L, "dynamic deletion front");
            condition10000 = colorNear(front.center(), shadedRgba8(216, 40, 40, FaceDirection.POSITIVE_Z), 3);
            stateDetails10001 = front.asLogFragment();
            require(condition10000, "dynamic deletion setup did not hit the front red section with reference face shading: " + stateDetails10001 + ", center=" + RtFrameSnapshot.hex(front.center()) + ", foregroundSample=" + foregroundSample(front, 32) + ", summary=" + rtCore.summary().asLogFragment());
            rtCore.acceptFrameUpdate(scene.removePreparedMesh(frontKey, frameState(100L)));
            RtFrameSnapshot revealed = pumpUntilFreshSnapshot(rtCore, frameState(101L), 101L, "dynamic deletion reveal");
            condition10000 = front.center() != revealed.center() || front.checksum() != revealed.checksum();
            stateDetails10001 = front.asLogFragment();
            require(condition10000, "section deletion did not change RT output: front=" + stateDetails10001 + ", revealed=" + revealed.asLogFragment() + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", activity=" + rtCore.runtimeActivity().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
            condition10000 = colorNear(revealed.center(), shadedRgba8(32, 192, 96, FaceDirection.POSITIVE_Z), 3);
            stateDetails10001 = revealed.asLogFragment();
            require(condition10000, "section deletion removed the front section but did not reveal the back green section with reference face shading: " + stateDetails10001 + ", center=" + RtFrameSnapshot.hex(revealed.center()) + ", foregroundSample=" + foregroundSample(revealed, 32) + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
            dynamicDeletionResult10 = new DynamicDeletionResult(front, revealed);
         } catch (Throwable value13) {
            if (rtCore != null) {
               try {
                  rtCore.close();
               } catch (Throwable value12) {
                  value13.addSuppressed(value12);
               }
            }

            throw value13;
         }

         if (rtCore != null) {
            rtCore.close();
         }
      } catch (Throwable value14) {
         if (textures != null) {
            try {
               textures.close();
            } catch (Throwable value11) {
               value14.addSuppressed(value11);
            }
         }

         throw value14;
      }

      if (textures != null) {
         textures.close();
      }

      return dynamicDeletionResult10;
   }

   private static DynamicDeletionResult runDynamicBlockDeletionWithinSectionScene(VulkanRtCapabilityProbe.Result capability) throws InterruptedException {
      RtTextureCatalog.TestTextureScope textures = RtTextureCatalog.installTestTexturesForSelfTest(List.of(new RtTextureCatalog.TestTexture("selftest:dynamic_block_deletion_front", 2, 2, solidTexture(224, 48, 40, 255, 2, 2)), new RtTextureCatalog.TestTexture("selftest:dynamic_block_deletion_back", 2, 2, solidTexture(48, 176, 224, 255, 2, 2))));

      DynamicDeletionResult dynamicDeletionResult9;
      try {
         GuardedRtCore rtCore = GuardedRtCore.isolatedHardwareTest();

         try {
            rtCore.acceptCapability(capability);
            boolean condition10000 = rtCore.state() == State.READY_FOR_SCENE_UPDATES;
            String stateDetails10001 = String.valueOf(rtCore.state());
            require(condition10000, "RT core did not open native backend for dynamic block deletion: state=" + stateDetails10001 + ", summary=" + rtCore.summary().asLogFragment());
            MicroSceneState scene = new MicroSceneState();
            SectionKey key = new SectionKey(0, 0, 0);
            int frontTextureId = textures.textureId("selftest:dynamic_block_deletion_front");
            int backTextureId = textures.textureId("selftest:dynamic_block_deletion_back");
            rtCore.acceptFrameUpdate(scene.replacePreparedMesh(stackedTexturedPositiveZQuads(key, frontTextureId, backTextureId), frameState(1L)));
            RtFrameSnapshot front = pumpUntilFreshSnapshot(rtCore, frameState(2L), 2L, "dynamic block deletion front");
            condition10000 = colorNear(front.center(), shadedRgba8(224, 48, 40, FaceDirection.POSITIVE_Z), 3);
            stateDetails10001 = front.asLogFragment();
            require(condition10000, "dynamic block deletion setup did not hit the nearer red face inside the same section: " + stateDetails10001 + ", center=" + RtFrameSnapshot.hex(front.center()) + ", foregroundSample=" + foregroundSample(front, 32) + ", summary=" + rtCore.summary().asLogFragment());
            rtCore.acceptFrameUpdate(scene.replacePreparedMesh(texturedPositiveZQuadAtLocalZ(key, backTextureId, 8), frameState(100L)));
            RtFrameSnapshot revealed = pumpUntilFreshSnapshot(rtCore, frameState(101L), 101L, "dynamic block deletion reveal");
            condition10000 = front.center() != revealed.center() || front.checksum() != revealed.checksum();
            stateDetails10001 = front.asLogFragment();
            require(condition10000, "same-section block deletion did not change RT output: front=" + stateDetails10001 + ", revealed=" + revealed.asLogFragment() + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", activity=" + rtCore.runtimeActivity().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
            condition10000 = colorNear(revealed.center(), shadedRgba8(48, 176, 224, FaceDirection.POSITIVE_Z), 3);
            stateDetails10001 = revealed.asLogFragment();
            require(condition10000, "same-section block deletion did not replace the stale front BLAS with the back cyan face: " + stateDetails10001 + ", center=" + RtFrameSnapshot.hex(revealed.center()) + ", foregroundSample=" + foregroundSample(revealed, 32) + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
            dynamicDeletionResult9 = new DynamicDeletionResult(front, revealed);
         } catch (Throwable value12) {
            if (rtCore != null) {
               try {
                  rtCore.close();
               } catch (Throwable value11) {
                  value12.addSuppressed(value11);
               }
            }

            throw value12;
         }

         if (rtCore != null) {
            rtCore.close();
         }
      } catch (Throwable value13) {
         if (textures != null) {
            try {
               textures.close();
            } catch (Throwable value10) {
               value13.addSuppressed(value10);
            }
         }

         throw value13;
      }

      if (textures != null) {
         textures.close();
      }

      return dynamicDeletionResult9;
   }

   private static RtFrameSnapshot runAlphaMixedHighResolutionScene(VulkanRtCapabilityProbe.Result capability) throws InterruptedException {
      Map<String, String> previous = new LinkedHashMap<>();
      set(previous, "top.ceroxe.rt.rt.output.width", Integer.toString(960));
      set(previous, "top.ceroxe.rt.rt.output.height", Integer.toString(540));
      set(previous, "top.ceroxe.rt.rt.output.maxPixels", Integer.toString(518400));

      RtFrameSnapshot snapshot10;
      try {
         GuardedRtCore rtCore = GuardedRtCore.isolatedHardwareTest();

         try {
            rtCore.acceptCapability(capability);
            boolean condition10000 = rtCore.state() == State.READY_FOR_SCENE_UPDATES;
            String stateDetails10001 = String.valueOf(rtCore.state());
            require(condition10000, "RT core did not open native backend for high-resolution alpha scene: state=" + stateDetails10001 + ", summary=" + rtCore.summary().asLogFragment());
            RendererFrameState initialFrame = frameState(1L, 960, 540);
            rtCore.acceptFrameUpdate(buildPreparedMeshFrameUpdate(alphaMixedPositiveZQuads(new SectionKey(0, 0, 0)), initialFrame));
            RtFrameSnapshot lastSnapshot = null;
            long firstSnapshotSequence = -1L;
            int frame = 2;

            while(true) {
               if (frame > 181) {
                  throw new AssertionError("high-resolution alpha-mixed RT scene did not keep producing fresh frames, firstSnapshotSequence=" + firstSnapshotSequence + ", lastSnapshot=" + (lastSnapshot == null ? "none" : lastSnapshot.asLogFragment()) + ", summary=" + rtCore.summary().asLogFragment());
               }

               RendererFrameState frameState = frameState((long)frame, 960, 540);
               rtCore.acceptFrameUpdate(RendererFrameUpdate.empty(emptyBatch(), frameState));
               RtFrameSnapshot snapshot = rtCore.latestFrameSnapshot();
               if (snapshot != null) {
                  if (firstSnapshotSequence < 0L) {
                     firstSnapshotSequence = snapshot.frameStateSequence();
                  }

                  lastSnapshot = snapshot;
                  if (frame >= 120 && snapshot.frameStateSequence() >= (long)frame - 8L) {
                     condition10000 = snapshot.width() == 960 && snapshot.height() == 540;
                     stateDetails10001 = snapshot.asLogFragment();
                     require(condition10000, "high-resolution RT frame extent mismatch: " + stateDetails10001);
                     condition10000 = snapshot.foregroundPixels() >= 32400;
                     stateDetails10001 = snapshot.asLogFragment();
                     require(condition10000, "high-resolution alpha-mixed scene rendered too little geometry: " + stateDetails10001 + ", foregroundSample=" + foregroundSample(snapshot, 64) + ", summary=" + rtCore.summary().asLogFragment());
                     snapshot10 = snapshot;
                     break;
                  }
               }

               condition10000 = rtCore.state() == State.READY_FOR_SCENE_UPDATES;
               stateDetails10001 = String.valueOf(rtCore.state());
               require(condition10000, "RT core failed during high-resolution alpha scene: state=" + stateDetails10001 + ", summary=" + rtCore.summary().asLogFragment());
               Thread.sleep(5L);
               ++frame;
            }
         } catch (Throwable value16) {
            if (rtCore != null) {
               try {
                  rtCore.close();
               } catch (Throwable value15) {
                  value16.addSuppressed(value15);
               }
            }

            throw value16;
         }

         if (rtCore != null) {
            rtCore.close();
         }
      } finally {
         restoreProperties(previous);
      }

      return snapshot10;
   }

   private static RtFrameSnapshot runSceneUntilFreshSnapshot(VulkanRtCapabilityProbe.Result capability, RendererFrameUpdate initialUpdate) throws InterruptedException {
      GuardedRtCore rtCore = GuardedRtCore.isolatedHardwareTest();

      RtFrameSnapshot snapshot8;
      try {
         rtCore.acceptCapability(capability);
         boolean condition10000 = rtCore.state() == State.READY_FOR_SCENE_UPDATES;
         String stateDetails10001 = String.valueOf(rtCore.state());
         require(condition10000, "RT core did not open native backend: state=" + stateDetails10001 + ", summary=" + rtCore.summary().asLogFragment());
         rtCore.acceptFrameUpdate(initialUpdate);
         RtFrameSnapshot lastSnapshot = null;
         long firstReadyPumpFrame = -1L;
         int frame = 2;

         while(true) {
            if (frame > 601) {
               String logDetails10002 = lastSnapshot == null ? "none" : lastSnapshot.asLogFragment();
               throw new AssertionError("native micro-scene rendered only miss/background pixels, lastSnapshot=" + logDetails10002 + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", activity=" + rtCore.runtimeActivity().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
            }

            rtCore.acceptFrameUpdate(RendererFrameUpdate.empty(emptyBatch(), copyFrameStateSequence(initialUpdate.frameState(), (long)frame)));
            lastSnapshot = rtCore.latestFrameSnapshot();
            RtSceneReadiness readiness = rtCore.sceneReadiness();
            if (firstReadyPumpFrame < 0L && readiness.builtRevisionIsCurrent() && !readiness.hasPendingRtBuilds()) {
               firstReadyPumpFrame = (long)frame;
            }

            if (lastSnapshot != null && firstReadyPumpFrame >= 0L && lastSnapshot.frameStateSequence() >= firstReadyPumpFrame) {
               snapshot8 = lastSnapshot;
               break;
            }

            condition10000 = rtCore.state() == State.READY_FOR_SCENE_UPDATES;
            stateDetails10001 = String.valueOf(rtCore.state());
            require(condition10000, "RT core failed during native micro-scene pump: state=" + stateDetails10001 + ", summary=" + rtCore.summary().asLogFragment());
            Thread.sleep(5L);
            ++frame;
         }
      } catch (Throwable value10) {
         if (rtCore != null) {
            try {
               rtCore.close();
            } catch (Throwable value9) {
               value10.addSuppressed(value9);
            }
         }

         throw value10;
      }

      if (rtCore != null) {
         rtCore.close();
      }

      return snapshot8;
   }

   private static RtFrameSnapshot pumpUntilFreshSnapshot(GuardedRtCore rtCore, RendererFrameState firstPumpFrameState, long minimumSequence, String label) throws InterruptedException {
      return pumpUntilFreshSnapshot(rtCore, firstPumpFrameState, minimumSequence, label, (snapshot) -> true);
   }

   private static RtFrameSnapshot pumpUntilFreshSnapshot(GuardedRtCore rtCore, RendererFrameState firstPumpFrameState, long minimumSequence, String label, Predicate<RtFrameSnapshot> snapshotPredicate) throws InterruptedException {
      Objects.requireNonNull(snapshotPredicate, "snapshotPredicate");
      RtFrameSnapshot lastSnapshot = rtCore.latestFrameSnapshot();
      long firstReadyPumpFrame = -1L;

      for(int frame = 0; frame < 600; ++frame) {
         RendererFrameState frameState = copyFrameStateSequence(firstPumpFrameState, firstPumpFrameState.sequence() + (long)frame);
         rtCore.acceptFrameUpdate(RendererFrameUpdate.empty(emptyBatch(), frameState));
         lastSnapshot = rtCore.latestFrameSnapshot();
         RtSceneReadiness readiness = rtCore.sceneReadiness();
         if (firstReadyPumpFrame < 0L && readiness.builtRevisionIsCurrent() && !readiness.hasPendingRtBuilds()) {
            firstReadyPumpFrame = frameState.sequence();
         }

         if (lastSnapshot != null && firstReadyPumpFrame >= 0L && lastSnapshot.frameStateSequence() >= Math.max(minimumSequence, firstReadyPumpFrame) && snapshotPredicate.test(lastSnapshot)) {
            return lastSnapshot;
         }

         require(rtCore.state() == State.READY_FOR_SCENE_UPDATES, "RT core failed during " + label + ": state=" + String.valueOf(rtCore.state()) + ", summary=" + rtCore.summary().asLogFragment());
         Thread.sleep(5L);
      }

      throw new AssertionError(label + " did not produce a fresh native RT snapshot, minimumSequence=" + minimumSequence + ", firstReadyPumpFrame=" + firstReadyPumpFrame + ", lastSnapshot=" + (lastSnapshot == null ? "none" : lastSnapshot.asLogFragment()) + ", summary=" + rtCore.summary().asLogFragment());
   }

   private static RendererFrameUpdate buildPreparedMeshFrameUpdate(SectionTriangleMesh mesh, RendererFrameState frameState) {
      return buildPreparedMeshFrameUpdate(mesh, frameState, DynamicRenderScene.empty());
   }

   private static RendererFrameUpdate buildPreparedMeshFrameUpdate(SectionTriangleMesh mesh, RendererFrameState frameState, DynamicRenderScene dynamicScene) {
      Objects.requireNonNull(dynamicScene, "dynamicScene");
      SectionKey key = mesh.key();
      SceneDatabase database = new SceneDatabase();
      SectionMaterialCache materialCache = new SectionMaterialCache();
      SectionGeometryCache geometryCache = new SectionGeometryCache();
      SectionMeshCache meshCache = new SectionMeshCache();
      database.replaceChunkSnapshot(new ChunkSnapshot(key.chunkKey(), key.y(), List.of(filledSection(key, 1))));
      SceneUpdateBatch batch = database.drainPendingUpdates();
      SectionMaterialCache.ApplyResult material = materialCache.apply(batch);
      SectionGeometryCache.ApplyResult geometry = geometryCache.apply(material.encodedSections(), batch.removedSections(), batch.fullResyncRequested());
      SectionMeshCache.ApplyResult meshResult = meshCache.applyPrepared(Map.of(key, mesh), batch.removedSections(), batch.fullResyncRequested());
      require(meshResult.trianglesInBatch() > 0, "micro-scene must submit visible section triangles");
      return new RendererFrameUpdate(batch, material, geometry, meshResult, frameState, BacklogSnapshot.empty(), dynamicScene);
   }

   private static RendererFrameUpdate buildFrameUpdate(SectionKey key, int voxelTypeId, RendererFrameState frameState) {
      return buildFrameUpdate(filledSection(key, voxelTypeId), frameState);
   }

   private static RendererFrameUpdate buildFrameUpdate(SectionVoxelSnapshot section, RendererFrameState frameState) {
      SceneDatabase database = new SceneDatabase();
      SectionMaterialCache materialCache = new SectionMaterialCache();
      SectionGeometryCache geometryCache = new SectionGeometryCache();
      SectionMeshCache meshCache = new SectionMeshCache();
      database.replaceChunkSnapshot(new ChunkSnapshot(section.key().chunkKey(), section.key().y(), List.of(section)));
      SceneUpdateBatch batch = database.drainPendingUpdates();
      SectionMaterialCache.ApplyResult material = materialCache.apply(batch);
      SectionGeometryCache.ApplyResult geometry = geometryCache.apply(material.encodedSections(), batch.removedSections(), batch.fullResyncRequested());
      SectionMeshCache.ApplyResult mesh = meshCache.apply(geometry.geometrySections(), batch.removedSections(), batch.fullResyncRequested());
      require(mesh.trianglesInBatch() > 0, "micro-scene must build visible section triangles");
      return new RendererFrameUpdate(batch, material, geometry, mesh, frameState);
   }

   private static RendererFrameState copyFrameStateSequence(RendererFrameState source, long sequence) {
      return new RendererFrameState(sequence, source.valid(), source.targetWidth(), source.targetHeight(), source.cameraX(), source.cameraY(), source.cameraZ(), source.cameraPitch(), source.cameraYaw(), source.cameraForwardX(), source.cameraForwardY(), source.cameraForwardZ(), source.cameraRightX(), source.cameraRightY(), source.cameraRightZ(), source.cameraUpX(), source.cameraUpY(), source.cameraUpZ(), source.projection00(), source.projection11(), source.projection22(), source.projection23(), source.projection32(), source.projection33(), source.renderBlockOutline(), source.renderBlockEntities());
   }

   private static SectionTriangleMesh fullSectionPositiveZQuad(SectionKey key) {
      return new SectionTriangleMesh(key, new short[]{fixed(0), fixed(0), fixed(16), fixed(16), fixed(0), fixed(16), fixed(16), fixed(16), fixed(16), fixed(0), fixed(16), fixed(16)}, new int[]{0, 1, 2, 0, 2, 3}, new int[]{1}, new byte[]{0}, new byte[]{(byte)FaceDirection.POSITIVE_Z.ordinal()});
   }

   private static SectionTriangleMesh tintedPositiveZQuad(SectionKey key, int mapColor) {
      return new SectionTriangleMesh(key, new short[]{fixed(0), fixed(0), fixed(16), fixed(16), fixed(0), fixed(16), fixed(16), fixed(16), fixed(16), fixed(0), fixed(16), fixed(16)}, new int[]{0, 1, 2, 0, 2, 3}, new int[]{1}, new byte[]{0}, new byte[]{(byte)FaceDirection.POSITIVE_Z.ordinal()}, new int[]{mapColor}, new byte[]{0}, new byte[]{1}, new int[]{0}, new int[]{RtTextureCatalog.packUv16(0.0F, 0.0F)}, new int[]{RtTextureCatalog.packUv16(1.0F, 0.0F)}, new int[]{RtTextureCatalog.packUv16(1.0F, 1.0F)}, new int[]{RtTextureCatalog.packUv16(0.0F, 1.0F)}, new byte[]{1}, new byte[]{0});
   }

   private static SectionTriangleMesh directionalShadowFixture(SectionKey key) {
      return new SectionTriangleMesh(key, new short[]{fixed(0), fixed(0), fixed(8), fixed(16), fixed(0), fixed(8), fixed(16), fixed(16), fixed(8), fixed(0), fixed(16), fixed(8), fixed(12), fixed(0), fixed(8), fixed(12), fixed(16), fixed(8), fixed(12), fixed(16), fixed(16), fixed(12), fixed(0), fixed(16)}, new int[]{0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7}, new int[]{1, 1}, new byte[]{0, 0}, new byte[]{(byte)FaceDirection.POSITIVE_Z.ordinal(), (byte)FaceDirection.POSITIVE_X.ordinal()}, new int[]{14737632, 14737632}, new byte[]{0, 0}, new byte[]{1, 1});
   }

   private static DynamicRenderScene directionalShadowScene(long revision, boolean castsShadow) {
      float diagonal = 0.70710677F;
      return new DynamicRenderScene(revision, List.of(), List.of(), List.of(), List.of(), List.of(new DynamicRenderScene.SceneLight(1L, LightKind.SUN, 0.0, 0.0, 0.0, diagonal, 0.0F, diagonal, 1.0F, 1.0F, 16777215, castsShadow)), LightmapPayload.unknown());
   }

   private static SectionTriangleMesh texturedPositiveZQuad(SectionKey key, int textureId) {
      return texturedPositiveZQuadAtLocalZ(key, textureId, 16);
   }

   private static SectionTriangleMesh texturedPositiveZQuadAtLocalZ(SectionKey key, int textureId, int localZ) {
      int vertexLighting = fullBrightPositiveZVertexLighting();
      byte knownLightMaterial = 33;
      return new SectionTriangleMesh(key, new short[]{fixed(0), fixed(0), fixed(localZ), fixed(16), fixed(0), fixed(localZ), fixed(16), fixed(16), fixed(localZ), fixed(0), fixed(16), fixed(localZ)}, new int[]{0, 1, 2, 0, 2, 3}, new int[]{1}, new byte[]{0}, new byte[]{(byte)FaceDirection.POSITIVE_Z.ordinal()}, new int[]{16777215}, new int[]{vertexLighting}, new int[]{vertexLighting}, new int[]{vertexLighting}, new int[]{vertexLighting}, new byte[]{0}, new byte[]{knownLightMaterial}, new int[]{textureId}, new int[]{RtTextureCatalog.packUv16(0.0F, 0.0F)}, new int[]{RtTextureCatalog.packUv16(1.0F, 0.0F)}, new int[]{RtTextureCatalog.packUv16(1.0F, 1.0F)}, new int[]{RtTextureCatalog.packUv16(0.0F, 1.0F)}, new byte[]{0}, new byte[]{0});
   }

   private static SectionTriangleMesh stackedTexturedPositiveZQuads(SectionKey key, int frontTextureId, int backTextureId) {
      int vertexLighting = fullBrightPositiveZVertexLighting();
      byte knownLightMaterial = 33;
      return new SectionTriangleMesh(key, new short[]{fixed(0), fixed(0), fixed(16), fixed(16), fixed(0), fixed(16), fixed(16), fixed(16), fixed(16), fixed(0), fixed(16), fixed(16), fixed(0), fixed(0), fixed(8), fixed(16), fixed(0), fixed(8), fixed(16), fixed(16), fixed(8), fixed(0), fixed(16), fixed(8)}, new int[]{0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7}, new int[]{1, 1}, new byte[]{0, 0}, new byte[]{(byte)FaceDirection.POSITIVE_Z.ordinal(), (byte)FaceDirection.POSITIVE_Z.ordinal()}, new int[]{16777215, 16777215}, new int[]{vertexLighting, vertexLighting}, new int[]{vertexLighting, vertexLighting}, new int[]{vertexLighting, vertexLighting}, new int[]{vertexLighting, vertexLighting}, new byte[]{0, 0}, new byte[]{knownLightMaterial, knownLightMaterial}, new int[]{frontTextureId, backTextureId}, new int[]{RtTextureCatalog.packUv16(0.0F, 0.0F), RtTextureCatalog.packUv16(0.0F, 0.0F)}, new int[]{RtTextureCatalog.packUv16(1.0F, 0.0F), RtTextureCatalog.packUv16(1.0F, 0.0F)}, new int[]{RtTextureCatalog.packUv16(1.0F, 1.0F), RtTextureCatalog.packUv16(1.0F, 1.0F)}, new int[]{RtTextureCatalog.packUv16(0.0F, 1.0F), RtTextureCatalog.packUv16(0.0F, 1.0F)}, new byte[]{0, 0}, new byte[]{0, 0});
   }

   private static SectionTriangleMesh alphaMixedPositiveZQuads(SectionKey key) {
      return new SectionTriangleMesh(key, new short[]{fixed(0), fixed(0), fixed(16), fixed(8), fixed(0), fixed(16), fixed(8), fixed(16), fixed(16), fixed(0), fixed(16), fixed(16), fixed(8), fixed(0), fixed(16), fixed(16), fixed(0), fixed(16), fixed(16), fixed(16), fixed(16), fixed(8), fixed(16), fixed(16)}, new int[]{0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7}, new int[]{1, 1}, new byte[]{0, 0}, new byte[]{(byte)FaceDirection.POSITIVE_Z.ordinal(), (byte)FaceDirection.POSITIVE_Z.ordinal()}, new int[]{7919704, 14211160}, new byte[]{0, 0}, new byte[]{1, 1}, new int[]{0, 0}, new int[]{RtTextureCatalog.packUv16(0.0F, 0.0F), RtTextureCatalog.packUv16(0.0F, 0.0F)}, new int[]{RtTextureCatalog.packUv16(1.0F, 0.0F), RtTextureCatalog.packUv16(1.0F, 0.0F)}, new int[]{RtTextureCatalog.packUv16(1.0F, 1.0F), RtTextureCatalog.packUv16(1.0F, 1.0F)}, new int[]{RtTextureCatalog.packUv16(0.0F, 1.0F), RtTextureCatalog.packUv16(0.0F, 1.0F)}, new byte[]{1, 1}, new byte[]{0, 1});
   }

   private static short fixed(int blockUnits) {
      return (short)(blockUnits * 1024);
   }

   private static int fullBrightPositiveZVertexLighting() {
      return PackedVoxelLighting.packVertex(240, 240, PackedVoxelLighting.cardinalShade(FaceDirection.POSITIVE_Z));
   }

   private static RendererFrameState frameState(long sequence) {
      return frameState(sequence, 64, 64);
   }

   private static RendererFrameState frameState(long sequence, int width, int height) {
      return new RendererFrameState(sequence, true, width, height, 8.0, 8.0, 40.0, 0.0F, 0.0F, 0.0F, 0.0F, -1.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 1.7320508F, 1.7320508F, 1.0F, 0.0F, -1.0F, 0.0F, false, true);
   }

   private static RendererFrameState smokeCameraFrameState(long sequence) {
      return new RendererFrameState(sequence, true, 64, 64, 162.5, 76.61999988555908, 170.5, 60.0F, -0.15000002F, 0.0012918696F, -0.8660254F, 0.49999833F, -0.99999666F, -0.0F, 0.0025837393F, 0.0022375837F, 0.5F, 0.8660225F, 0.8027061F, 1.428148F, 2.441466E-5F, -1.0F, 0.050001223F, 0.0F, true, true);
   }

   private static SectionVoxelSnapshot filledSection(SectionKey key, int voxelTypeId) {
      int[] ids = new int[4096];
      byte[] fluids = new byte[4096];
      Arrays.fill(ids, voxelTypeId);
      return new SectionVoxelSnapshot(key, ids, fluids, false, false);
   }

   private static SectionVoxelSnapshot smokeCameraProbeSection() {
      SectionKey key = new SectionKey(10, 4, 10);
      int[] ids = new int[4096];
      byte[] fluids = new byte[4096];
      int[] mapColors = new int[4096];
      byte[] lightEmissions = new byte[4096];
      byte[] materialFlags = new byte[4096];
      Arrays.fill(materialFlags, (byte)2);

      for(int z = 10; z <= 12; ++z) {
         for(int x = 1; x <= 3; ++x) {
            int index = SectionVoxelSnapshot.localBlockIndex(x, 10, z);
            ids[index] = 1;
            mapColors[index] = 3116863;
            materialFlags[index] = 1;
         }
      }

      return new SectionVoxelSnapshot(key, ids, fluids, mapColors, lightEmissions, materialFlags, false, false);
   }

   private static SceneUpdateBatch emptyBatch() {
      return new SceneUpdateBatch(Set.of(), Set.of(), Set.of(), Set.of(), Map.of(), false, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
   }

   private static Map<String, String> installDiagnosticProperties() {
      Map<String, String> previous = new LinkedHashMap<>();
      set(previous, "top.ceroxe.rt.rt.output.readback.enabled", "true");
      set(previous, "top.ceroxe.rt.oracleGBuffer.enabled", "true");
      set(previous, "top.ceroxe.rt.rt.output.readback.interval", "1");
      set(previous, "top.ceroxe.rt.rt.output.dispatchInterval", "1");
      set(previous, "top.ceroxe.rt.rt.output.width", Integer.toString(64));
      set(previous, "top.ceroxe.rt.rt.output.height", Integer.toString(64));
      set(previous, "top.ceroxe.rt.rt.output.maxPixels", Integer.toString(4096));
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

   private static int[] solidTexture(int red, int green, int blue, int alpha, int width, int height) {
      int[] pixels = new int[width * height];
      Arrays.fill(pixels, rgba8(red, green, blue, alpha));
      return pixels;
   }

   private static int rgba8(int red, int green, int blue, int alpha) {
      return red & 255 | (green & 255) << 8 | (blue & 255) << 16 | (alpha & 255) << 24;
   }

   private static int pixelLuminance(RtFrameSnapshot snapshot, int x, int y) {
      if (x >= 0 && x < snapshot.width() && y >= 0 && y < snapshot.height()) {
         int rgba8 = RtFrameSnapshot.pixel(snapshot.copyRgba8(), snapshot.width(), x, y);
         int red = rgba8 & 255;
         int green = rgba8 >>> 8 & 255;
         int blue = rgba8 >>> 16 & 255;
         return red * 54 + green * 183 + blue * 19 + 128 >>> 8;
      } else {
         throw new IllegalArgumentException("pixel coordinate outside snapshot: (" + x + "," + y + ")");
      }
   }

   private static int shadedRgba8(int red, int green, int blue, FaceDirection direction) {
      double value10000;
      switch (direction) {
         case NEGATIVE_Y:
            value10000 = 0.5;
            break;
         case POSITIVE_Y:
            value10000 = 1.0;
            break;
         case NEGATIVE_X:
         case POSITIVE_X:
            value10000 = 0.6;
            break;
         case NEGATIVE_Z:
         case POSITIVE_Z:
            value10000 = 0.8;
            break;
         default:
            throw new MatchException((String)null, (Throwable)null);
      }

      double referenceDirectional = value10000;
      return rgba8((int)Math.round((double)red * referenceDirectional), (int)Math.round((double)green * referenceDirectional), (int)Math.round((double)blue * referenceDirectional), 255);
   }

   private static boolean colorNear(int actual, int expected, int tolerance) {
      return Math.abs((actual & 255) - (expected & 255)) <= tolerance && Math.abs((actual >>> 8 & 255) - (expected >>> 8 & 255)) <= tolerance && Math.abs((actual >>> 16 & 255) - (expected >>> 16 & 255)) <= tolerance && Math.abs((actual >>> 24 & 255) - (expected >>> 24 & 255)) <= tolerance;
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }

   private static final class MicroSceneState {
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
            snapshots.put(key, RtNativeMicroSceneSelfTest.filledSection(key, 1));
         }

         return new SceneUpdateBatch(dirtySections, dirtyChunks, Set.of(), Set.of(), snapshots, false, (long)dirtySections.size(), (long)dirtySections.size(), 0L, 0L, 0L, 0L, 0L, SceneUpdateBatch.sourceFlagsForBlockMutation());
      }

      private RendererFrameUpdate replacePreparedMesh(SectionTriangleMesh mesh, RendererFrameState frameState) {
         return this.replacePreparedMeshes(Map.of(mesh.key(), mesh), frameState);
      }

      private RendererFrameUpdate replacePreparedMeshes(Map<SectionKey, SectionTriangleMesh> meshes, RendererFrameState frameState) {
         RtNativeMicroSceneSelfTest.require(!meshes.isEmpty(), "dynamic micro-scene replacement must not be empty");

         for(SectionTriangleMesh mesh : meshes.values()) {
            this.database.replaceBlockMutationSectionSnapshot(RtNativeMicroSceneSelfTest.filledSection(mesh.key(), 1));
         }

         SceneUpdateBatch batch = this.database.drainPendingUpdates();
         if (!batch.hasChanges()) {
            batch = preparedMeshBatch(meshes);
         }

         SectionMaterialCache.ApplyResult material = this.materialCache.apply(batch);
         SectionGeometryCache.ApplyResult geometry = this.geometryCache.apply(material.encodedSections(), batch.removedSections(), batch.fullResyncRequested());
         SectionMeshCache.ApplyResult meshResult = this.meshCache.applyPrepared(meshes, batch.removedSections(), batch.fullResyncRequested());
         RtNativeMicroSceneSelfTest.require(meshResult.trianglesInBatch() > 0, "dynamic micro-scene must submit visible section triangles");
         return new RendererFrameUpdate(batch, material, geometry, meshResult, frameState);
      }

      private RendererFrameUpdate removePreparedMesh(SectionKey key, RendererFrameState frameState) {
         this.database.removeBlockMutationSectionSnapshot(key);
         SceneUpdateBatch batch = this.database.drainPendingUpdates();
         SectionMaterialCache.ApplyResult material = this.materialCache.apply(batch);
         SectionGeometryCache.ApplyResult geometry = this.geometryCache.apply(material.encodedSections(), batch.removedSections(), batch.fullResyncRequested());
         SectionMeshCache.ApplyResult meshResult = this.meshCache.applyPrepared(Map.of(), batch.removedSections(), batch.fullResyncRequested());
         RtNativeMicroSceneSelfTest.require(meshResult.removedInBatch() > 0, "dynamic micro-scene removal must remove a prepared section");
         return new RendererFrameUpdate(batch, material, geometry, meshResult, frameState);
      }
   }

   private static record DynamicDeletionResult(RtFrameSnapshot front, RtFrameSnapshot revealed) {
      private String asLogFragment() {
         String logDetails10000 = this.front.asLogFragment();
         return "dynamicDeletion{front=" + logDetails10000 + ", revealed=" + this.revealed.asLogFragment() + ", frontCenter=" + RtFrameSnapshot.hex(this.front.center()) + ", revealedCenter=" + RtFrameSnapshot.hex(this.revealed.center()) + "}";
      }
   }

   private static record DirectionalShadowResult(RtFrameSnapshot shadowed, RtFrameSnapshot unshadowed, int shadowedOccludedLuminance, int unshadowedOccludedLuminance, int shadowedLitLuminance, int unshadowedLitLuminance) {
      private String asLogFragment() {
         String logDetails10000 = this.shadowed.asLogFragment();
         return "directionalShadow{shadowed=" + logDetails10000 + ", unshadowed=" + this.unshadowed.asLogFragment() + ", occluded=" + this.shadowedOccludedLuminance + "->" + this.unshadowedOccludedLuminance + ", control=" + this.shadowedLitLuminance + "->" + this.unshadowedLitLuminance + "}";
      }
   }
}
