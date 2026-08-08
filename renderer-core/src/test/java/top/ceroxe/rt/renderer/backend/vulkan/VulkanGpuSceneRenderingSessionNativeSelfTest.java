package top.ceroxe.rt.renderer.backend.vulkan;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.api.AffineTransform;
import top.ceroxe.rt.renderer.api.AntiAliasingState;
import top.ceroxe.rt.renderer.api.CameraState;
import top.ceroxe.rt.renderer.api.CpuFrame;
import top.ceroxe.rt.renderer.api.DenoisingOptions;
import top.ceroxe.rt.renderer.api.EnvironmentState;
import top.ceroxe.rt.renderer.api.FrameGenerationOptions;
import top.ceroxe.rt.renderer.api.FrameOutputFormat;
import top.ceroxe.rt.renderer.api.FrameReconstructionOptions;
import top.ceroxe.rt.renderer.api.MaterialAsset;
import top.ceroxe.rt.renderer.api.MeshAsset;
import top.ceroxe.rt.renderer.api.RayTracingRendererConfig;
import top.ceroxe.rt.renderer.api.RayTracingOptimizationOptions;
import top.ceroxe.rt.renderer.api.RendererFeaturePreference;
import top.ceroxe.rt.renderer.api.RendererPreset;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Feature;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Status;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.SceneInstance;
import top.ceroxe.rt.renderer.api.SceneLight;
import top.ceroxe.rt.renderer.api.SceneTransaction;
import top.ceroxe.rt.renderer.api.SubmissionRejectedException;
import top.ceroxe.rt.renderer.api.TextureAsset;
import top.ceroxe.rt.renderer.api.EnvironmentState.Medium;
import top.ceroxe.rt.renderer.api.MaterialAsset.BlendMode;
import top.ceroxe.rt.renderer.api.MaterialAsset.ShadingModel;
import top.ceroxe.rt.renderer.api.TextureAsset.AddressMode;
import top.ceroxe.rt.renderer.api.TextureAsset.ColorSpace;
import top.ceroxe.rt.renderer.api.TextureAsset.Filter;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFrameInterop;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease.ImportDisposition;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease.LeaseState;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease.SemaphoreKind;
import top.ceroxe.rt.renderer.rt.device.RtCommandContext;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.device.interop.VulkanWin32ExternalSemaphoreProbe;
import top.ceroxe.rt.renderer.rt.device.interop.Win32HandleSupport;

public final class VulkanGpuSceneRenderingSessionNativeSelfTest {
   private static final long TIMEOUT_NANOS = 15000000000L;
   private static final int WIDTH = 960;
   private static final int HEIGHT = 540;
   private static final boolean REQUIRE_SER = Boolean.getBoolean("top.ceroxe.rt.ser.requiredGate");

   private VulkanGpuSceneRenderingSessionNativeSelfTest() {
   }

   public static void main(String[] arguments) throws Exception {
      VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
      require(capability.hardwareRayTracingReady(), "complex GPUScene gate requires hardware RT: " + capability.summary());
      RayTracingRendererConfig configuration = RayTracingRendererConfig.expertBuilder()
              .maxFramesInFlight(3)
              .validationEnabled(true)
              .gpuTimingsEnabled(true)
              // SER gates must not acquire Streamline or NRD process state. Their evidence is
              // meaningful only when the requested optimization is the sole optional feature.
              .frameReconstruction(FrameReconstructionOptions.disabled())
              .frameGeneration(FrameGenerationOptions.disabled())
              .denoising(DenoisingOptions.disabled())
              .rayTracingOptimizations(RayTracingOptimizationOptions.builder()
                      .shaderExecutionReordering(REQUIRE_SER
                              ? RendererFeaturePreference.REQUIRED
                              : RendererFeaturePreference.PREFERRED)
                      .build())
              .build();
      VulkanGpuSceneRenderingSession session = VulkanGpuSceneRenderingSession.open(capability, configuration, RendererRtDiagnostics.noop());
      VulkanRendererHost renderer = new VulkanRendererHost(configuration, session);

      try {
         Status serBeforeDispatch = session.featureCapabilities()
                 .feature(Feature.SHADER_EXECUTION_REORDERING).status();
         if (REQUIRE_SER) require(serBeforeDispatch == Status.AVAILABLE,
                 "required SER must remain AVAILABLE until real queue submission: " + serBeforeDispatch);
         renderer.apply(complexScene());
         RenderFrameRequest frame = frameRequest(0L, 0L, AntiAliasingState.disabled());
         awaitFrameAdmission(renderer, frame);
         VulkanGpuSceneRenderingSession.DiagnosticFrame diagnostic = awaitDiagnostic(session);
         Status serAfterCompletion = session.featureCapabilities()
                 .feature(Feature.SHADER_EXECUTION_REORDERING).status();
         if (REQUIRE_SER || serBeforeDispatch == Status.AVAILABLE) {
            require(serAfterCompletion == Status.ACTIVE,
                    "SER did not publish completed GPU-submission evidence: before="
                            + serBeforeDispatch + ", after=" + serAfterCompletion);
         }
         ImageStatistics statistics = statistics(diagnostic.rgba8());
         require(statistics.nonBlackPixels() > 86400L, "complex scene did not produce enough visible coverage: " + statistics);
         require(statistics.uniqueSampledColors() >= 24, "complex scene lost material/light variation: " + String.valueOf(statistics));
         CpuFrame cpuFrame = awaitCpuFrame(renderer);
         require(cpuFrame.frameSequence() == 0L && cpuFrame.renderedSceneRevision() == 0L && cpuFrame.width() == 960 && cpuFrame.height() == 540, "managed CPU frame lost rendered generation metadata");
         byte[] cpuPixels = new byte[cpuFrame.byteCount()];
         cpuFrame.pixelsRgba8().get(cpuPixels);
         require(statistics(cpuPixels).equals(statistics), "managed CPU frame pixels diverged from the trusted readback path");
         require(renderer.pollLatestCpuFrame().isEmpty(), "managed CPU frame path returned the same completed sequence twice");
         require(renderer.pollLatestFrame() instanceof VulkanFrameInterop.FrameNotReady,
                 "CPU snapshot retained an already-consumed expert GPU lease");
         Path png = Path.of("build", "reports", "gpuscene-complex-scene.png").toAbsolutePath().normalize();
         writePng(diagnostic, png);

         renderer.apply(refractiveIndexSceneUpdate());
         RenderFrameRequest refractiveIndexFrame = frameRequest(1L, 1L, AntiAliasingState.disabled());
         awaitFrameAdmission(renderer, refractiveIndexFrame);
         VulkanGpuSceneRenderingSession.DiagnosticFrame refractiveIndexDiagnostic = awaitDiagnostic(session);
         ImageStatistics refractiveIndexStatistics = statistics(refractiveIndexDiagnostic.rgba8());
         require(refractiveIndexStatistics.checksum() != statistics.checksum(), "changing only glass IOR did not change the rendered image");
         try (GpuFrameLease lease = awaitLease(renderer)) {
            require(lease.descriptor().frameSequence() == 1L && lease.descriptor().renderedSceneRevision() == 1L, "IOR-only public frame lease lost scene causality");
         }

         renderer.apply(animatedSceneUpdate());
         RenderFrameRequest updatedFrame = frameRequest(2L, 2L, AntiAliasingState.disabled());
         awaitFrameAdmission(renderer, updatedFrame);
         VulkanGpuSceneRenderingSession.DiagnosticFrame updatedDiagnostic = awaitDiagnostic(session);
         ImageStatistics updatedStatistics = statistics(updatedDiagnostic.rgba8());
         require(updatedStatistics.nonBlackPixels() > 86400L, "dynamic scene update lost visible coverage: " + updatedStatistics);
         require(updatedStatistics.checksum() != statistics.checksum(), "material/instance/light update did not change the rendered image");
         Path updatedPng = Path.of("build", "reports", "gpuscene-complex-scene-updated.png").toAbsolutePath().normalize();
         writePng(updatedDiagnostic, updatedPng);
         try (GpuFrameLease lease = awaitLease(renderer)) {
            require(lease.descriptor().frameSequence() == 2L && lease.descriptor().renderedSceneRevision() == 2L, "updated public frame lease lost scene causality");
            System.out.println("VulkanGpuSceneRenderingSessionNativeSelfTest frame2Handle=0x" + Long.toHexString(lease.memoryHandle().value()));
            releaseThroughExternalBinarySemaphore(session, lease);
         }

         System.out.println("VulkanGpuSceneRenderingSessionNativeSelfTest passed: device=" + capability.preferredDevice().name() + ", ser=" + serAfterCompletion + ", statistics=" + statistics + ", refractiveIndexStatistics=" + refractiveIndexStatistics + ", updatedStatistics=" + updatedStatistics + ", diagnosticPng=" + png + ", updatedDiagnosticPng=" + updatedPng);
      } catch (Throwable value28) {
         try {
            renderer.close();
         } catch (Throwable value21) {
            value28.addSuppressed(value21);
         }

         throw value28;
      }

      renderer.close();
      verifyLinearHdrOutput(capability);
   }

   private static void verifyLinearHdrOutput(VulkanRtCapabilityProbe.Result capability) throws Exception {
      require(capability.preferredDevice().linearHdrRgba16fOutput(), "selected target GPU does not expose exportable RGBA16F storage images");
      RayTracingRendererConfig configuration = RendererPreset.CPU_READBACK.configuration().copyBuilder().frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F).build();
      VulkanGpuSceneRenderingSession session = VulkanGpuSceneRenderingSession.open(capability, configuration, RendererRtDiagnostics.noop());
      VulkanRendererHost renderer = new VulkanRendererHost(configuration, session);

      try {
         renderer.apply(complexScene());
         awaitFrameAdmission(renderer, frameRequest(0L, 0L, AntiAliasingState.multisampled(4)));
         VulkanGpuSceneRenderingSession.DiagnosticFrame diagnostic = awaitDiagnostic(session);
         require(statistics(diagnostic.rgba8()).nonBlackPixels() > 86400L, "HDR managed readback lost visible scene coverage");
         CpuFrame cpuFrame = awaitCpuFrame(renderer);
         require(cpuFrame.byteCount() == Math.multiplyExact(Math.multiplyExact(960, 540), 4), "HDR renderer did not preserve the managed RGBA8 CpuFrame contract");
         awaitFrameAdmission(renderer, frameRequest(1L, 0L, AntiAliasingState.multisampled(4)));
         GpuFrameLease lease = awaitLease(renderer);

         try {
            require(lease.descriptor().format().value() == 97, "HDR lease descriptor does not match the RGBA16F allocation");
         } catch (Throwable value11) {
            if (lease != null) {
               try {
                  lease.close();
               } catch (Throwable value10) {
                  value11.addSuppressed(value10);
               }
            }

            throw value11;
         }

         if (lease != null) {
            lease.close();
         }
      } catch (Throwable value12) {
         try {
            renderer.close();
         } catch (Throwable value9) {
            value12.addSuppressed(value9);
         }

         throw value12;
      }

      renderer.close();
   }

   private static RenderFrameRequest frameRequest(long sequence, long minimumSceneRevision, AntiAliasingState antiAliasing) {
      return RenderFrameRequest.builder(sequence, 960, 540, camera()).minimumSceneRevision(minimumSceneRevision).environment(environment()).antiAliasing(antiAliasing).build();
   }

   private static void releaseThroughExternalBinarySemaphore(VulkanGpuSceneRenderingSession session, GpuFrameLease lease) {
      require(lease.consumerCompletionCapabilities().supports(SemaphoreKind.BINARY), "validated Vulkan device did not advertise binary consumer completion");
      VulkanDeviceRuntime device = session.deviceForAcceptance();
      long consumerHandle = 0L;

      try {
         VulkanWin32ExternalSemaphoreProbe.ExportedSemaphore signal = VulkanWin32ExternalSemaphoreProbe.exportSemaphore(device.device());

         try {
            RtCommandContext.AsyncSubmission producer = device.frameCommands().submitOneTimeAsync((commandBuffer, stack) -> {
            }, signal);
            consumerHandle = signal.detachWin32Handle();
            lease.release(new GpuFrameLease.ExternalSemaphoreSignal(consumerHandle, new GpuFrameLease.VulkanSemaphoreHandleType(signal.handleType()), SemaphoreKind.BINARY, 0L, ImportDisposition.CALLER_RETAINS_HANDLE));
            require(lease.state() == LeaseState.RELEASED, "external semaphore completion was not accepted");
            producer.close();
         } catch (Throwable value13) {
            if (signal != null) {
               try {
                  signal.close();
               } catch (Throwable value12) {
                  value13.addSuppressed(value12);
               }
            }

            throw value13;
         }

         if (signal != null) {
            signal.close();
         }
      } finally {
         if (consumerHandle != 0L && !Win32HandleSupport.close(consumerHandle)) {
            throw new IllegalStateException("failed to close consumer semaphore handle");
         }

      }

   }

   private static void awaitFrameAdmission(VulkanRendererHost renderer, RenderFrameRequest frame) throws InterruptedException {
      long deadline = System.nanoTime() + 15000000000L;

      while(true) {
         try {
            renderer.submit(frame);
            return;
         } catch (SubmissionRejectedException converging) {
            if (System.nanoTime() >= deadline) {
               throw new AssertionError("GPUScene did not converge before frame admission", converging);
            }

            renderer.diagnostics();
            Thread.sleep(1L);
         }
      }
   }

   private static VulkanGpuSceneRenderingSession.DiagnosticFrame awaitDiagnostic(VulkanGpuSceneRenderingSession session) throws InterruptedException {
      long deadline = System.nanoTime() + 15000000000L;

      do {
         VulkanGpuSceneRenderingSession.DiagnosticFrame diagnostic = session.captureLatestForAcceptance();
         if (diagnostic != null) {
            return diagnostic;
         }

         Thread.sleep(1L);
      } while(System.nanoTime() < deadline);

      throw new AssertionError("GPUScene frame did not complete before timeout");
   }

   private static GpuFrameLease awaitLease(VulkanRendererHost renderer) throws InterruptedException {
      long deadline = System.nanoTime() + 15000000000L;

      do {
         VulkanFrameInterop.FramePollResult result = renderer.pollLatestFrame();
         if (result instanceof VulkanFrameInterop.FrameAvailable available) {
            return available.lease();
         }

         Thread.sleep(1L);
      } while(System.nanoTime() < deadline);

      throw new AssertionError("completed public GPU frame lease was unavailable");
   }

   private static CpuFrame awaitCpuFrame(VulkanRendererHost renderer) throws InterruptedException {
      long deadline = System.nanoTime() + 15000000000L;

      do {
         Optional<CpuFrame> frame = renderer.pollLatestCpuFrame();
         if (frame.isPresent()) {
            return (CpuFrame)frame.orElseThrow();
         }

         Thread.sleep(1L);
      } while(System.nanoTime() < deadline);

      throw new AssertionError("completed managed CPU frame was unavailable");
   }

   static SceneTransaction complexScene() {
      TextureAsset checker = checkerTexture(100L, 64, false);
      TextureAsset cutout = checkerTexture(101L, 32, true);
      MaterialAsset floor = material(200L, BlendMode.OPAQUE, -1, checker.id(), 0.72F, 0.05F, 0.0F, 1.5F, true);
      MaterialAsset red = material(201L, BlendMode.OPAQUE, -13618984, -1L, 0.38F, 0.05F, 0.0F, 1.5F, true);
      MaterialAsset metal = material(202L, BlendMode.OPAQUE, -3104704, -1L, 0.18F, 0.92F, 0.0F, 1.5F, true);
      MaterialAsset emissive = MaterialAsset.builder(203L).blendMode(BlendMode.OPAQUE).baseColorRgba8(-14671840).emissive(-12541697, 8.0F).alphaCutoff(0.0F).roughness(0.45F).metallic(0.0F).transmission(0.0F).indexOfRefraction(1.5F).doubleSided(true).shadingModel(ShadingModel.PHYSICALLY_BASED).build();
      MaterialAsset masked = MaterialAsset.builder(204L).blendMode(BlendMode.MASKED).baseColorRgba8(-1).baseColorTexture(cutout).emissive(0, 0.0F).alphaCutoff(0.5F).roughness(0.65F).metallic(0.0F).transmission(0.0F).indexOfRefraction(1.5F).doubleSided(true).shadingModel(ShadingModel.PHYSICALLY_BASED).build();
      MaterialAsset glass = material(205L, BlendMode.TRANSLUCENT, -2141136656, -1L, 0.08F, 0.0F, 0.82F, 1.45F, true);
      MeshAsset ground = quad(300L, floor.id(), 8.0F, 8.0F, VulkanGpuSceneRenderingSessionNativeSelfTest.Plane.XZ);
      MeshAsset cube = cube(301L, red.id(), metal.id(), emissive.id());
      MeshAsset cutoutPanel = quad(302L, masked.id(), 2.6F, 2.6F, VulkanGpuSceneRenderingSessionNativeSelfTest.Plane.XY);
      MeshAsset glassPanel = quad(303L, glass.id(), 2.8F, 2.2F, VulkanGpuSceneRenderingSessionNativeSelfTest.Plane.XY);
      List<SceneInstance> instances = List.of(instance(400L, ground.id(), transform(0.0F, -1.2F, 0.0F, 1.0F, 1.0F, 1.0F)), instance(401L, cube.id(), transform(-2.4F, 0.0F, 0.0F, 1.15F, 1.15F, 1.15F)), instance(402L, cube.id(), transform(0.0F, -0.25F, -1.4F, 0.85F, 0.85F, 0.85F)), instance(403L, cube.id(), transform(2.3F, 0.35F, 0.3F, 1.5F, 1.5F, 1.5F)), instance(404L, cutoutPanel.id(), transform(-0.9F, 0.4F, 1.6F, 1.0F, 1.0F, 1.0F)), instance(405L, glassPanel.id(), transform(1.25F, 0.25F, 2.0F, 1.0F, 1.0F, 1.0F)));
      List<SceneLight> lights = List.of(SceneLight.directional(500L, -0.350508F, -0.851234F, -0.390566F).normalizedDirection(-0.350508F, -0.851234F, -0.390566F).color(1.0F, 0.92F, 0.78F).intensity(3.0F).build(), SceneLight.point(501L, -3.0, 3.5, 3.0).color(1.0F, 0.2F, 0.08F).intensity(180.0F).range(12.0F).build(), SceneLight.point(502L, 3.5, 2.5, 1.0).color(0.08F, 0.25F, 1.0F).intensity(220.0F).range(13.0F).build());
      return SceneTransaction.builder(0L).resetScene().upsertTextures(List.of(checker, cutout)).upsertMaterials(List.of(floor, red, metal, emissive, masked, glass)).upsertMeshes(List.of(ground, cube, cutoutPanel, glassPanel)).upsertInstances(instances).upsertLights(lights).build();
   }

   private static SceneTransaction animatedSceneUpdate() {
      MaterialAsset changedMaterial = material(201L, BlendMode.OPAQUE, -2602968, -1L, 0.24F, 0.18F, 0.0F, 1.5F, true);
      SceneInstance movedInstance = instance(402L, 301L, transform(0.25F, 0.15F, -0.75F, 1.05F, 1.05F, 1.05F));
      SceneLight movedLight = SceneLight.point(501L, -1.5, 4.25, 2.0).color(0.25F, 1.0F, 0.18F).intensity(240.0F).range(14.0F).build();
      return SceneTransaction.builder(2L).upsert(changedMaterial).upsert(movedInstance).upsert(movedLight).build();
   }

   private static SceneTransaction refractiveIndexSceneUpdate() {
      MaterialAsset opticallyNeutralGlass = material(205L, BlendMode.TRANSLUCENT, -2141136656, -1L, 0.08F, 0.0F, 0.82F, 1.0F, true);
      return SceneTransaction.builder(1L).upsert(opticallyNeutralGlass).build();
   }

   private static SceneTransaction maskedTextureSceneUpdate() {
      int extent = 32;
      byte[] pixels = new byte[extent * extent * 4];
      for (int offset = 0; offset < pixels.length; offset += 4) {
         pixels[offset] = (byte) 255;
         pixels[offset + 1] = (byte) 255;
         pixels[offset + 2] = (byte) 255;
      }
      TextureAsset transparent = TextureAsset.builder(101L, extent, extent)
              .colorSpace(ColorSpace.SRGB)
              .addressModes(AddressMode.REPEAT, AddressMode.REPEAT)
              .filter(Filter.LINEAR)
              .pixelsRgba8(pixels)
              .build();
      return SceneTransaction.builder(3L).upsert(transparent).build();
   }

   private static TextureAsset checkerTexture(long id, int extent, boolean cutout) {
      byte[] pixels = new byte[extent * extent * 4];

      for(int y = 0; y < extent; ++y) {
         for(int x = 0; x < extent; ++x) {
            boolean alternate = (x / 8 ^ y / 8) % 2 != 0;
            int offset = (y * extent + x) * 4;
            pixels[offset] = (byte)(alternate ? 235 : 45);
            pixels[offset + 1] = (byte)(alternate ? 235 : 70);
            pixels[offset + 2] = (byte)(alternate ? 235 : 95);
            pixels[offset + 3] = (byte)(cutout && alternate ? 0 : 255);
         }
      }

      return TextureAsset.builder(id, extent, extent).colorSpace(ColorSpace.SRGB).addressModes(AddressMode.REPEAT, AddressMode.REPEAT).filter(Filter.LINEAR).pixelsRgba8(pixels).build();
   }

   private static MaterialAsset material(long id, MaterialAsset.BlendMode blendMode, int baseColor, long texture, float roughness, float metallic, float transmission, float ior, boolean doubleSided) {
      return MaterialAsset.builder(id).blendMode(blendMode).baseColorRgba8(baseColor).baseColorTextureId(texture).emissive(0, 0.0F).alphaCutoff(blendMode == BlendMode.MASKED ? 0.5F : 0.0F).roughness(roughness).metallic(metallic).transmission(transmission).indexOfRefraction(ior).doubleSided(doubleSided).shadingModel(ShadingModel.PHYSICALLY_BASED).build();
   }

   private static MeshAsset quad(long id, long material, float width, float height, Plane plane) {
      float halfWidth = width * 0.5F;
      float halfHeight = height * 0.5F;
      float[] positions;
      float[] normals;
      if (plane == VulkanGpuSceneRenderingSessionNativeSelfTest.Plane.XZ) {
         positions = new float[]{-halfWidth, 0.0F, -halfHeight, halfWidth, 0.0F, -halfHeight, halfWidth, 0.0F, halfHeight, -halfWidth, 0.0F, halfHeight};
         normals = repeat3(4, 0.0F, 1.0F, 0.0F);
      } else {
         positions = new float[]{-halfWidth, -halfHeight, 0.0F, halfWidth, -halfHeight, 0.0F, halfWidth, halfHeight, 0.0F, -halfWidth, halfHeight, 0.0F};
         normals = repeat3(4, 0.0F, 0.0F, 1.0F);
      }

      return MeshAsset.builder(id, positions, new int[]{0, 1, 2, 0, 2, 3}, new long[]{material, material}).normals(normals).textureCoordinates(new float[]{0.0F, 0.0F, 4.0F, 0.0F, 4.0F, 4.0F, 0.0F, 4.0F}).vertexColorsRgba8(new int[]{-1, -1, -1, -1}).build();
   }

   private static MeshAsset cube(long id, long sideMaterial, long topMaterial, long frontMaterial) {
      ArrayList<Float> positions = new ArrayList<>();
      ArrayList<Float> normals = new ArrayList<>();
      ArrayList<Float> uvs = new ArrayList<>();
      ArrayList<Integer> indices = new ArrayList<>();
      ArrayList<Long> materials = new ArrayList<>();
      addFace(positions, normals, uvs, indices, materials, new float[]{-1.0F, -1.0F, 1.0F, 1.0F, -1.0F, 1.0F, 1.0F, 1.0F, 1.0F, -1.0F, 1.0F, 1.0F}, 0.0F, 0.0F, 1.0F, frontMaterial);
      addFace(positions, normals, uvs, indices, materials, new float[]{1.0F, -1.0F, -1.0F, -1.0F, -1.0F, -1.0F, -1.0F, 1.0F, -1.0F, 1.0F, 1.0F, -1.0F}, 0.0F, 0.0F, -1.0F, sideMaterial);
      addFace(positions, normals, uvs, indices, materials, new float[]{-1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F, -1.0F, -1.0F, 1.0F, -1.0F}, 0.0F, 1.0F, 0.0F, topMaterial);
      addFace(positions, normals, uvs, indices, materials, new float[]{-1.0F, -1.0F, -1.0F, 1.0F, -1.0F, -1.0F, 1.0F, -1.0F, 1.0F, -1.0F, -1.0F, 1.0F}, 0.0F, -1.0F, 0.0F, sideMaterial);
      addFace(positions, normals, uvs, indices, materials, new float[]{1.0F, -1.0F, 1.0F, 1.0F, -1.0F, -1.0F, 1.0F, 1.0F, -1.0F, 1.0F, 1.0F, 1.0F}, 1.0F, 0.0F, 0.0F, topMaterial);
      addFace(positions, normals, uvs, indices, materials, new float[]{-1.0F, -1.0F, -1.0F, -1.0F, -1.0F, 1.0F, -1.0F, 1.0F, 1.0F, -1.0F, 1.0F, -1.0F}, -1.0F, 0.0F, 0.0F, sideMaterial);
      return MeshAsset.builder(id, floats(positions), integers(indices), longs(materials)).normals(floats(normals)).textureCoordinates(floats(uvs)).build();
   }

   private static void addFace(List<Float> positions, List<Float> normals, List<Float> uvs, List<Integer> indices, List<Long> materials, float[] face, float nx, float ny, float nz, long material) {
      int base = positions.size() / 3;

      for(float value : face) {
         positions.add(value);
      }

      for(float value : repeat3(4, nx, ny, nz)) {
         normals.add(value);
      }

      for(float value : new float[]{0.0F, 0.0F, 1.0F, 0.0F, 1.0F, 1.0F, 0.0F, 1.0F}) {
         uvs.add(value);
      }

      for(int value : new int[]{0, 1, 2, 0, 2, 3}) {
         indices.add(base + value);
      }

      materials.add(material);
      materials.add(material);
   }

   private static SceneInstance instance(long id, long mesh, AffineTransform transform) {
      return SceneInstance.builder(id, mesh).transform(transform).build();
   }

   private static AffineTransform transform(float x, float y, float z, float sx, float sy, float sz) {
      return new AffineTransform(new float[]{sx, 0.0F, 0.0F, x, 0.0F, sy, 0.0F, y, 0.0F, 0.0F, sz, z});
   }

   static CameraState camera() {
      float forwardY = -0.24253562F;
      float forwardZ = -0.9701425F;
      return CameraState.explicitBasis(0.0, 3.0, 9.0).forward(0.0F, forwardY, forwardZ).right(1.0F, 0.0F, 0.0F).up(0.0F, -forwardZ, forwardY).projectionTangents(1.0F, 0.5625F).build();
   }

   static EnvironmentState environment() {
      EnvironmentState.Medium medium = Medium.builder().extinction(0.03F, 0.018F, 0.012F).scattering(0.006F, 0.009F, 0.014F).density(0.025F).indexOfRefraction(1.0F).build();
      return EnvironmentState.builder().skyRadiance(0.08F, 0.14F, 0.24F).ambientIntensity(0.65F).sunDirection(-0.350508F, -0.851234F, -0.390566F).sunRadiance(1.0F, 0.94F, 0.82F).sunIntensity(1.8F).cameraMedium(medium).build();
   }

   private static ImageStatistics statistics(byte[] rgba8) {
      long nonBlack = 0L;
      long checksum = -3750763034362895579L;
      HashSet<Integer> sampled = new HashSet<>();

      for(int offset = 0; offset < rgba8.length; offset += 4) {
         int color = Byte.toUnsignedInt(rgba8[offset]) << 16 | Byte.toUnsignedInt(rgba8[offset + 1]) << 8 | Byte.toUnsignedInt(rgba8[offset + 2]);
         if (color != 0) {
            ++nonBlack;
         }

         if ((offset & 255) == 0) {
            sampled.add(color);
         }

         for(int component = 0; component < 4; ++component) {
            checksum ^= (long)Byte.toUnsignedInt(rgba8[offset + component]);
            checksum *= 1099511628211L;
         }
      }

      return new ImageStatistics(nonBlack, sampled.size(), checksum);
   }

   private static void writePng(VulkanGpuSceneRenderingSession.DiagnosticFrame frame, Path path) throws IOException {
      Files.createDirectories(path.getParent());
      BufferedImage image = new BufferedImage(frame.width(), frame.height(), 2);
      byte[] rgba8 = frame.rgba8();
      int offset = 0;

      for(int y = 0; y < frame.height(); ++y) {
         for(int x = 0; x < frame.width(); ++x) {
            int red = Byte.toUnsignedInt(rgba8[offset++]);
            int green = Byte.toUnsignedInt(rgba8[offset++]);
            int blue = Byte.toUnsignedInt(rgba8[offset++]);
            int alpha = Byte.toUnsignedInt(rgba8[offset++]);
            image.setRGB(x, y, alpha << 24 | red << 16 | green << 8 | blue);
         }
      }

      require(ImageIO.write(image, "png", path.toFile()), "PNG writer is unavailable");
   }

   private static float[] repeat3(int count, float x, float y, float z) {
      float[] result = new float[count * 3];

      for(int index = 0; index < count; ++index) {
         result[index * 3] = x;
         result[index * 3 + 1] = y;
         result[index * 3 + 2] = z;
      }

      return result;
   }

   private static float[] floats(List<Float> values) {
      float[] result = new float[values.size()];

      for(int index = 0; index < result.length; ++index) {
         result[index] = (Float)values.get(index);
      }

      return result;
   }

   private static int[] integers(List<Integer> values) {
      int[] result = new int[values.size()];

      for(int index = 0; index < result.length; ++index) {
         result[index] = (Integer)values.get(index);
      }

      return result;
   }

   private static long[] longs(List<Long> values) {
      long[] result = new long[values.size()];

      for(int index = 0; index < result.length; ++index) {
         result[index] = (Long)values.get(index);
      }

      return result;
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }

   private static enum Plane {
      XY,
      XZ;
   }

   private static record ImageStatistics(long nonBlackPixels, int uniqueSampledColors, long checksum) {
      public String toString() {
         long nonBlackPixels10000 = this.nonBlackPixels;
         return "image{nonBlackPixels=" + nonBlackPixels10000 + ", uniqueSampledColors=" + this.uniqueSampledColors + ", checksum=0x" + Long.toHexString(this.checksum) + "}";
      }
   }
}
