package top.ceroxe.rt.renderer.api;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ReadOnlyBufferException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import top.ceroxe.rt.renderer.api.EnvironmentState.Medium;
import top.ceroxe.rt.renderer.api.MaterialAsset.BlendMode;
import top.ceroxe.rt.renderer.api.MaterialAsset.ShadingModel;
import top.ceroxe.rt.renderer.api.HardwareCapabilities.RayTracingLimits;
import top.ceroxe.rt.renderer.api.RayTracingGpuDevice.Type;
import top.ceroxe.rt.renderer.api.RayTracingRenderer.FrameSubmissionResult;
import top.ceroxe.rt.renderer.api.RayTracingRenderer.Status;
import top.ceroxe.rt.renderer.api.RendererDiagnostics.DeviceRecovery;
import top.ceroxe.rt.renderer.api.RendererDiagnostics.FrameGpuTiming;
import top.ceroxe.rt.renderer.api.RendererHealth.ResourceObligations;
import top.ceroxe.rt.renderer.api.RendererUnavailableException.BackendAttempt;
import top.ceroxe.rt.renderer.api.TextureAsset.AddressMode;
import top.ceroxe.rt.renderer.api.TextureAsset.ColorSpace;
import top.ceroxe.rt.renderer.api.TextureAsset.Filter;
import top.ceroxe.rt.renderer.api.TextureSamplingState.MinificationMode;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFrameInterop;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFramePresenter;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFramePresenterConfig;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease.ConsumerCompletionCapabilities;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease.FrameDescriptor;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease.HandleState;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease.ImportDisposition;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease.SemaphoreKind;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFrameInterop.FrameNotReady;
import top.ceroxe.rt.renderer.spi.RayTracingBackendProvider;
import top.ceroxe.rt.renderer.spi.RayTracingBackendProvider.Compatibility;
import top.ceroxe.rt.renderer.spi.RayTracingBackendProvider.Descriptor;
import top.ceroxe.rt.renderer.spi.RayTracingBackendProvider.ProbeResult;

public final class RendererApiContractSelfTest {
   private RendererApiContractSelfTest() {
   }

   public static void main(String[] args) {
      assertConfigurationBounds();
      assertFeatureOptionContracts();
      assertTechnologyCapabilityContract();
      assertTechnologyExecutionEvidenceContract();
      assertFrameGenerationContract();
      assertLowLatencyContract();
      assertGpuDeviceSelectionContract();
      assertHardwareCapabilitiesContract();
      assertTransformAndLightingValidation();
      assertCameraAndFrameValidation();
      assertExactProjectionContract();
      assertFrameValidationFailureContract();
      assertFramePrimitiveContract();
      assertAntiAliasingContract();
      assertTemporalRenderingContract();
      assertAssetOwnership();
      assertDirectAssetOwnership();
      assertMipChainContract();
      assertTransactionOwnershipAndConflicts();
      assertDiagnosticsAndResultValidation();
      assertGpuFrameDescriptorValidation();
      assertManagedPresenterContract();
      assertFramePollingContract();
      assertCpuFrameContract();
      assertSubmissionDeferralAndCloseContract();
      assertExportedHandleLifecycle();
      assertProviderSelection();
      System.out.println("RendererApiContractSelfTest passed");
   }

   private static void assertFramePrimitiveContract() {
      UvTransform uv = UvTransform.scaleAndOffset(2.0F, 3.0F, 0.25F, -0.5F);
      require(uv.transformU(0.5F, 0.25F) == 1.25F && uv.transformV(0.5F, 0.25F) == 0.25F,
              "UV affine transform changed its row-major mapping");
      require(UvTransform.identity() == UvTransform.of(1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F),
              "identity UV transform lost canonicalization");
      expect(IllegalArgumentException.class,
              () -> UvTransform.of(Float.NaN, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F));
      expect(IllegalArgumentException.class,
              () -> UvTransform.rotation(Float.POSITIVE_INFINITY, 0.0F, 0.0F));

      OutlineStyle outline = OutlineStyle.of(0xff40_20ff, 2.0F);
      CardinalLightingState cardinalLighting = CardinalLightingState.worldSpace(
              0.45F, 0.55F, 0.65F, 0.75F, 0.85F, 0.95F
      );
      InstanceRenderState renderState = InstanceRenderState.builder()
              .uvTransform(uv)
              .surfaceMask(0x04)
              .overlayReceiverMask(0x04)
              .objectMask(73)
              .outline(outline)
              .cardinalLighting(cardinalLighting)
              .build();
      require(renderState.cardinalLighting().equals(cardinalLighting)
                      && renderState.equals(renderState.toBuilder().build()),
              "instance copy lost cardinal-lighting state");
      require(CardinalLightingState.objectSpace(1, 1, 1, 1, 1, 1)
                      == CardinalLightingState.disabled(),
              "no-op cardinal lighting lost canonicalization");
      expect(IllegalArgumentException.class,
              () -> CardinalLightingState.objectSpace(-0.01F, 1, 1, 1, 1, 1));
      expect(IllegalArgumentException.class,
              () -> CardinalLightingState.worldSpace(1, 1, Float.NaN, 1, 1, 1));
      expect(IllegalArgumentException.class,
              () -> CardinalLightingState.objectSpace(1, 1, 1, 1.01F, 1, 1));

      DirectionalDiffuseState directionalDiffuse = DirectionalDiffuseState.builder()
              .coordinateSpace(DirectionalDiffuseState.CoordinateSpace.WORLD)
              .firstDirection(0.0F, 3.0F, 4.0F)
              .firstIntensity(0.6F)
              .secondDirection(-4.0F, 0.0F, 3.0F)
              .secondIntensity(0.6F)
              .ambient(0.4F)
              .backFacePolicy(DirectionalDiffuseState.BackFacePolicy.FLIP_ON_BACK_FACE)
              .build();
      InstanceRenderState directionalState = InstanceRenderState.builder()
              .directionalDiffuse(directionalDiffuse)
              .build();
      require(directionalDiffuse.enabled()
                      && directionalDiffuse.firstDirectionY() == 0.6F
                      && directionalDiffuse.firstDirectionZ() == 0.8F
                      && directionalDiffuse.secondDirectionX() == -0.8F
                      && directionalDiffuse.secondDirectionZ() == 0.6F
                      && directionalState.equals(directionalState.toBuilder().build()),
              "directional diffuse state lost normalization or instance-copy semantics");
      require(DirectionalDiffuseState.builder().ambient(1.0F).build()
                      == DirectionalDiffuseState.disabled(),
              "no-op directional diffuse state lost canonicalization");
      expect(IllegalArgumentException.class,
              () -> DirectionalDiffuseState.builder().firstDirection(0.0F, 0.0F, 0.0F));
      expect(IllegalArgumentException.class,
              () -> DirectionalDiffuseState.builder().secondDirection(Float.NaN, 1.0F, 0.0F));
      expect(IllegalArgumentException.class,
              () -> DirectionalDiffuseState.builder().ambient(-0.01F));
      expect(IllegalArgumentException.class,
              () -> DirectionalDiffuseState.builder().firstIntensity(1.01F));
      expect(IllegalStateException.class,
              () -> DirectionalDiffuseState.builder().ambient(0.4F)
                      .firstIntensity(0.6F).build());
      expect(NullPointerException.class,
              () -> DirectionalDiffuseState.builder().coordinateSpace(null));
      expect(NullPointerException.class,
              () -> DirectionalDiffuseState.builder().backFacePolicy(null));
      expect(IllegalArgumentException.class,
              () -> InstanceRenderState.builder()
                      .cardinalLighting(cardinalLighting)
                      .directionalDiffuse(directionalDiffuse)
                      .build());
      expect(IllegalArgumentException.class,
              () -> InstanceRenderState.builder().outline(outline).build());
      expect(IllegalArgumentException.class, () -> OutlineStyle.of(0x0040_20ff, 1.0F));
      expect(IllegalArgumentException.class,
              () -> OutlineStyle.of(0xff40_20ff, OutlineStyle.MAX_WIDTH_PIXELS + 0.01F));

      SurfaceOverlayState overlay = SurfaceOverlayState.depthEqual(0.002F);
      SurfaceOverlayState multiplyOverlay = SurfaceOverlayState.depthBias(
              0.004F, SurfaceOverlayState.CompositionMode.MULTIPLY
      );
      require(overlay.compositionMode() == SurfaceOverlayState.CompositionMode.ALPHA_OVER
                      && multiplyOverlay.compositionMode()
                      == SurfaceOverlayState.CompositionMode.MULTIPLY,
              "overlay composition mode lost explicit/default semantics");
      MaterialAsset unlitOverlay = MaterialAsset.builder(71L)
              .blendMode(BlendMode.TRANSLUCENT)
              .baseColorRgba8(0x8040_20ff)
              .shadingModel(ShadingModel.UNLIT)
              .surfaceOverlay(overlay)
              .build();
      require(unlitOverlay.shadingModel() == ShadingModel.UNLIT
                      && unlitOverlay.surfaceOverlay().equals(overlay)
                      && unlitOverlay.equals(unlitOverlay.toBuilder().build()),
              "material copy lost unlit or overlay policy");
      expect(IllegalArgumentException.class, () -> SurfaceOverlayState.depthBias(Float.NaN));
      expect(IllegalArgumentException.class, () -> SurfaceOverlayState.depthEqual(-0.001F));
      expect(NullPointerException.class,
              () -> SurfaceOverlayState.depthEqual(0.0F, null));
      expect(IllegalArgumentException.class, () -> MaterialAsset.builder(72L)
              .baseColorRgba8(0x8040_20ff)
              .surfaceOverlay(overlay)
              .build());

      SceneInstance persistent = SceneInstance.builder(81L, 91L).renderState(renderState).build();
      PrimitiveInstance primitive = PrimitiveInstance.from(persistent);
      require(primitive.renderState().equals(persistent.renderState())
                      && primitive.transform().equals(persistent.transform())
                      && primitive.previousTransform().equals(persistent.transform())
                      && primitive.meshAssetId() == persistent.meshAssetId(),
              "persistent/frame primitive conversion lost shared instance state");
      AffineTransform primitivePrevious = new AffineTransform(new float[]{
              1, 0, 0, -1, 0, 1, 0, -2, 0, 0, 1, -3
      });
      PrimitiveInstance movingPrimitive = primitive.toBuilder()
              .previousTransform(primitivePrevious)
              .build();
      require(movingPrimitive.previousTransform().equals(primitivePrevious),
              "frame primitive copy lost explicit previous transform");
      FramePrimitiveBatch batch = FramePrimitiveBatch.of(new ArrayList<>(List.of(primitive)));
      require(batch.size() == 1 && batch.primitives().get(0).equals(primitive),
              "frame primitive batch lost its value");
      expect(UnsupportedOperationException.class, () -> batch.primitives().clear());
      expect(NullPointerException.class, () -> FramePrimitiveBatch.of(java.util.Arrays.asList(primitive, null)));
      expect(IllegalArgumentException.class, () -> FramePrimitiveBatch.of(
              new java.util.AbstractList<PrimitiveInstance>() {
                 @Override public PrimitiveInstance get(int index) { throw new AssertionError("oversized list was read"); }
                 @Override public int size() { return FramePrimitiveBatch.MAX_PRIMITIVES + 1; }
              }
      ));

      RenderFrameRequest request = RenderFrameRequest.builder(82L, 1, 1, camera())
              .primitiveBatch(batch)
              .build();
      require(request.primitiveBatch().equals(batch)
                      && request.toBuilder().build().primitiveBatch().equals(batch),
              "frame request copy lost the frame primitive batch");
      expect(NullPointerException.class, () -> request.toBuilder().primitiveBatch(null));
   }

   private static void assertFrameValidationFailureContract() {
      FrameValidationException failure = new FrameValidationException(
              FrameValidationException.Reason.MISSING_DEPTH_PROJECTION,
              "projection metadata is required"
      );
      require(failure.reason() == FrameValidationException.Reason.MISSING_DEPTH_PROJECTION,
              "frame validation lost its structured reason");
      expect(NullPointerException.class, () -> new FrameValidationException(null, "reason"));
      expect(IllegalArgumentException.class, () -> new FrameValidationException(
              FrameValidationException.Reason.MISSING_DEPTH_PROJECTION, " "
      ));
   }

   private static void assertAntiAliasingContract() {
      require(AntiAliasingState.disabled().samplesPerPixel() == 1, "disabled anti-aliasing changed its center-sample contract");
      require(AntiAliasingState.multisampled(8).samplesPerPixel() == 8, "anti-aliasing sample count was not retained");
      expect(IllegalArgumentException.class, () -> new AntiAliasingState(0));
      expect(IllegalArgumentException.class, () -> new AntiAliasingState(3));
      expect(IllegalArgumentException.class, () -> AntiAliasingState.multisampled(1));
      RenderFrameRequest request = RenderFrameRequest.builder(0L, 1, 1, CameraState.lookAt(0.0, 0.0, 1.0, 0.0, 0.0, 0.0).build()).antiAliasing(AntiAliasingState.multisampled(4)).build();
      require(request.antiAliasing().samplesPerPixel() == 4, "frame builder lost the requested anti-aliasing policy");
      expect(NullPointerException.class, () -> request.toBuilder().antiAliasing((AntiAliasingState)null));
   }

   private static void assertConfigurationBounds() {
      require(RendererPreset.CPU_READBACK.configuration().maxFramesInFlight() == 3, "default frame ring changed");
      require(RendererPreset.CPU_READBACK.configuration().cpuFrameReadbackEnabled(), "managed CPU readback must remain enabled by default");
      require(RendererPreset.CPU_READBACK.configuration().frameOutputFormat() == FrameOutputFormat.SDR_RGBA8, "default native output must be SDR RGBA8");
      require(RendererPreset.CPU_READBACK.configuration().temporalRendering().equals(TemporalRenderingOptions.balanced()), "production defaults must enable balanced temporal reconstruction");
      require(RendererPreset.CPU_READBACK.configuration().denoising().equals(DenoisingOptions.recommended()), "ordinary defaults must capability-gate denoising");
      require(RendererPreset.CPU_READBACK.configuration().frameReconstruction().equals(FrameReconstructionOptions.recommended()), "ordinary defaults must capability-gate reconstruction");
      require(RendererPreset.CPU_READBACK.configuration().frameGeneration().equals(FrameGenerationOptions.disabled()), "CPU-readable defaults must not arm presentation-time generation");
      require(RendererPreset.CPU_READBACK.configuration().lowLatency().equals(LowLatencyOptions.disabled()), "CPU-readable defaults must not arm display pacing");
      require(RendererPreset.CPU_READBACK.configuration().rayTracingOptimizations().equals(RayTracingOptimizationOptions.recommended()), "ordinary defaults must capability-gate SER and RTXMU");
      RayTracingRendererConfig explicitProduction = RayTracingRendererConfig.expertBuilder()
              .maxFramesInFlight(RayTracingRendererConfig.DEFAULT_MAX_FRAMES_IN_FLIGHT)
              .validationEnabled(false)
              .gpuTimingsEnabled(true)
              .cpuFrameReadbackEnabled(true)
              .automaticGpuSelection()
              .frameOutputFormat(FrameOutputFormat.SDR_RGBA8)
              .temporalRendering(TemporalRenderingOptions.balanced())
              .frameReconstruction(FrameReconstructionOptions.recommended())
              .frameGeneration(FrameGenerationOptions.disabled())
              .lowLatency(LowLatencyOptions.disabled())
              .denoising(DenoisingOptions.recommended())
              .rayTracingOptimizations(RayTracingOptimizationOptions.recommended())
                .build();
      require(RendererPreset.CPU_READBACK.configuration().equals(explicitProduction),
              "simple production defaults cannot be expressed exactly through expert policies");
      require(RendererPreset.CPU_READBACK.configuration().equals(
                      RendererPreset.CPU_READBACK.configuration().copyBuilder().build()),
              "ordinary defaults lost policy while crossing the expert builder boundary");
      RayTracingRendererConfig gpuPresentation = RendererPreset.MANAGED_GPU_PRESENTATION.configuration();
      require(!gpuPresentation.cpuFrameReadbackEnabled()
                      && gpuPresentation.frameGeneration().equals(FrameGenerationOptions.recommended())
                      && gpuPresentation.lowLatency().equals(LowLatencyOptions.recommended())
                      && gpuPresentation.frameReconstruction().equals(
                              RendererPreset.CPU_READBACK.configuration().frameReconstruction())
                      && gpuPresentation.denoising().equals(
                              RendererPreset.CPU_READBACK.configuration().denoising())
                      && gpuPresentation.rayTracingOptimizations().equals(
                              RendererPreset.CPU_READBACK.configuration().rayTracingOptimizations()),
              "GPU presentation defaults must add only presentation-safe automatic policies");
      require(gpuPresentation.equals(gpuPresentation.copyBuilder().build()),
              "managed-presentation defaults lost policy while crossing the expert builder boundary");
      RayTracingRendererConfig rawInterop = RendererPreset.CPU_READBACK.configuration().copyBuilder()
              .cpuFrameReadbackEnabled(false)
              .build();
      require(!rawInterop.cpuFrameReadbackEnabled()
                      && rawInterop.frameGeneration().equals(FrameGenerationOptions.disabled())
                      && rawInterop.lowLatency().equals(LowLatencyOptions.disabled()),
              "raw Vulkan interop must not inherit managed-presenter cadence policies");
      require(gpuPresentation.frameGeneration().mode()
                      == FrameGenerationOptions.Mode.FRAME_GENERATION
                      && gpuPresentation.frameGeneration().multiplier()
                      == FrameGenerationOptions.Multiplier.TWO_X,
              "ordinary GPU defaults must never auto-select MFG");
      RayTracingRendererConfig generationOnly = RayTracingRendererConfig.expertBuilder()
              .frameGeneration(FrameGenerationOptions.recommended())
              .build();
      require(generationOnly.frameReconstruction().equals(FrameReconstructionOptions.disabled())
                      && generationOnly.denoising().equals(DenoisingOptions.disabled())
                      && generationOnly.lowLatency().equals(LowLatencyOptions.disabled())
                      && generationOnly.rayTracingOptimizations().equals(
                              RayTracingOptimizationOptions.disabled()),
              "one explicit feature option must not enable unrelated optional policies");
      require(RayTracingRendererConfig.expertBuilder().build().equals(
                      RayTracingRendererConfig.expertBuilder()
                              .frameReconstruction(FrameReconstructionOptions.disabled())
                              .frameGeneration(FrameGenerationOptions.disabled())
                              .lowLatency(LowLatencyOptions.disabled())
                              .denoising(DenoisingOptions.disabled())
                              .rayTracingOptimizations(RayTracingOptimizationOptions.disabled())
                              .build()),
              "expert builder must keep every unrelated optional feature explicit");
      RayTracingRendererConfig tuned = RendererPreset.CPU_READBACK.configuration().copyBuilder().maxFramesInFlight(4).validationEnabled(true).gpuTimingsEnabled(false).cpuFrameReadbackEnabled(false).frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F).temporalRendering(TemporalRenderingOptions.accumulating(16)).build();
      require(tuned.maxFramesInFlight() == 4 && tuned.validationEnabled() && !tuned.gpuTimingsEnabled() && !tuned.cpuFrameReadbackEnabled() && tuned.frameOutputFormat() == FrameOutputFormat.LINEAR_HDR_RGBA16F && tuned.temporalRendering().maxHistoryFrames() == 16, "configuration builder lost an independent policy value");
      expect(IllegalArgumentException.class, () -> RayTracingRendererConfig.expertBuilder().maxFramesInFlight(1).build());
      expect(IllegalArgumentException.class, () -> RayTracingRendererConfig.expertBuilder().maxFramesInFlight(17).build());
      expect(NullPointerException.class, () -> RayTracingRendererConfig.expertBuilder().frameOutputFormat((FrameOutputFormat)null));
      expect(NullPointerException.class, () -> RayTracingRendererConfig.expertBuilder().temporalRendering((TemporalRenderingOptions)null));
      expect(NullPointerException.class, () -> RayTracingRendererConfig.expertBuilder().frameGeneration((FrameGenerationOptions)null));
      expect(NullPointerException.class, () -> RayTracingRendererConfig.expertBuilder().lowLatency((LowLatencyOptions)null));
      require(RayTracingBackendProvider.Descriptor.class.getConstructors().length == 0, "provider descriptor exposed an ordered public constructor");
      expect(IllegalArgumentException.class, () -> Descriptor.builder(" ").build());
      expect(IllegalArgumentException.class, () -> Descriptor.builder("vulkan").apiMajor(0).build());
      expect(IllegalArgumentException.class, () -> Descriptor.builder("vulkan").apiMinor(-1).build());
      expect(IllegalArgumentException.class, () -> ProbeResult.compatible(" "));
   }

   private static void assertFeatureOptionContracts() {
      expect(IllegalArgumentException.class, () -> FrameReconstructionOptions.builder()
              .preference(RendererFeaturePreference.REQUIRED)
              .fallback(FrameReconstructionOptions.Fallback.SPATIAL)
              .build());
      expect(IllegalArgumentException.class, () -> DenoisingOptions.builder()
              .preference(RendererFeaturePreference.REQUIRED)
              .builtInTemporalFallback(true)
              .build());
      expect(IllegalArgumentException.class, () -> FrameGenerationOptions.builder()
              .preference(RendererFeaturePreference.REQUIRED)
              .mode(FrameGenerationOptions.Mode.FRAME_GENERATION)
              .fallback(FrameGenerationOptions.Fallback.PRESENT_NATIVE_FRAMES)
              .build());
      expect(IllegalArgumentException.class, () -> RayTracingRendererConfig.expertBuilder()
              .temporalRendering(TemporalRenderingOptions.disabled())
              .frameReconstruction(FrameReconstructionOptions.builder()
                      .preference(RendererFeaturePreference.PREFERRED)
                      .fallback(FrameReconstructionOptions.Fallback.BUILT_IN_TEMPORAL)
                      .build())
              .build());
      expect(IllegalArgumentException.class, () -> RayTracingRendererConfig.expertBuilder()
              .temporalRendering(TemporalRenderingOptions.disabled())
              .denoising(DenoisingOptions.builder()
                      .preference(RendererFeaturePreference.PREFERRED)
                      .builtInTemporalFallback(true)
                      .build())
              .build());
   }

   private static void assertTechnologyCapabilityContract() {
      RenderingFeatureCapabilities empty = RenderingFeatureCapabilities.builder().build();
      require(empty.features().size() == RenderingFeatureCapabilities.Feature.values().length,
              "capability snapshot omitted a feature entry");
      require(empty.technologies().size() == RenderingFeatureCapabilities.Technology.values().length,
              "capability snapshot omitted a technology entry");
      for (RenderingFeatureCapabilities.Technology technology
              : RenderingFeatureCapabilities.Technology.values()) {
         require(empty.technology(technology).status() == RenderingFeatureCapabilities.Status.DISABLED,
                 "omitted technology must be explicitly disabled: " + technology);
      }

      RenderingFeatureCapabilities.Entry unsupported = RenderingFeatureCapabilities.Entry.of(
              RenderingFeatureCapabilities.Status.NOT_SUPPORTED,
              "none",
              "the active adapter does not expose this technology"
      );
      RenderingFeatureCapabilities.Entry blocked = RenderingFeatureCapabilities.Entry.of(
              RenderingFeatureCapabilities.Status.BLOCKED,
              "nvidia.streamline.dlss-g",
              "plugin initialization failed"
      );
      RenderingFeatureCapabilities capabilities = RenderingFeatureCapabilities.builder()
              .technology(RenderingFeatureCapabilities.Technology.MULTI_FRAME_GENERATION, unsupported)
              .technology(RenderingFeatureCapabilities.Technology.FRAME_GENERATION, blocked)
              .build();
      require(capabilities.technology(RenderingFeatureCapabilities.Technology.MULTI_FRAME_GENERATION)
                      .status() == RenderingFeatureCapabilities.Status.NOT_SUPPORTED,
              "hardware rejection was not retained as NOT_SUPPORTED");
      require(capabilities.technology(RenderingFeatureCapabilities.Technology.FRAME_GENERATION)
                      .status() == RenderingFeatureCapabilities.Status.BLOCKED,
              "runtime failure was not retained as BLOCKED");
      require(capabilities.equals(RenderingFeatureCapabilities.builder()
                      .technology(RenderingFeatureCapabilities.Technology.MULTI_FRAME_GENERATION, unsupported)
                      .technology(RenderingFeatureCapabilities.Technology.FRAME_GENERATION, blocked)
                      .build()),
              "technology capabilities lost immutable value semantics");
      expect(NullPointerException.class, () -> capabilities.technology(null));
      expect(NullPointerException.class, () -> RenderingFeatureCapabilities.builder()
              .technology(null, blocked));
      expect(NullPointerException.class, () -> RenderingFeatureCapabilities.builder()
              .technology(RenderingFeatureCapabilities.Technology.FRAME_GENERATION, null));
   }

   private static void assertTechnologyExecutionEvidenceContract() {
      TechnologyExecutionEvidence.Entry active = TechnologyExecutionEvidence.Entry.builder()
              .requestPreference(RendererFeaturePreference.PREFERRED)
              .requestedImplementation("nvidia.dlss-g")
              .negotiatedImplementation("nvidia.dlss-g")
              .configuredImplementation("nvidia.dlss-g")
              .configuredParameter("generated-frames", "2")
              .recordedCount(12L)
              .queueAcceptedCount(10L)
              .gpuCompletedCount(8L)
              .outputCount(16L)
              .sequenceRange(3L, 14L)
              .sequenceDomain(TechnologyExecutionEvidence.SequenceDomain.RENDERER_FRAME)
              .lastOutputSequence(12L)
              .resetEpoch(2L)
              .health(TechnologyExecutionEvidence.Health.ACTIVE)
              .build();
      TechnologyExecutionEvidence.Entry unavailable = TechnologyExecutionEvidence.Entry.unavailable(
              RendererFeaturePreference.PREFERRED,
              "nvidia.nrd"
      ).toBuilder().errorCode("SDK_UNAVAILABLE").build();
      TechnologyExecutionEvidence.Entry fallback = TechnologyExecutionEvidence.Entry.builder()
              .requestPreference(RendererFeaturePreference.PREFERRED)
              .requestedImplementation("nvidia.dlss-sr")
              .negotiatedImplementation("renderer.temporal")
              .configuredImplementation("renderer.temporal")
              .fallbackCode("PROVIDER_UNAVAILABLE")
              .health(TechnologyExecutionEvidence.Health.FALLBACK_PENDING)
              .build();
      TechnologyExecutionEvidence evidence = TechnologyExecutionEvidence.builder()
              .technology(RenderingFeatureCapabilities.Technology.FRAME_GENERATION, active)
              .technology(RenderingFeatureCapabilities.Technology.RAY_TRACING_DENOISING, unavailable)
              .technology(RenderingFeatureCapabilities.Technology.TEMPORAL_SUPER_RESOLUTION, fallback)
              .build();

      require(TechnologyExecutionEvidence.class.getConstructors().length == 0
                      && TechnologyExecutionEvidence.Entry.class.getConstructors().length == 0,
              "technology execution evidence exposed ordered public constructors");
      require(evidence.technologies().size() == RenderingFeatureCapabilities.Technology.values().length,
              "technology execution evidence must contain every technology");
      require(evidence.technology(RenderingFeatureCapabilities.Technology.FRAME_GENERATION).equals(active)
                      && active.outputCount() == 16L
                      && active.firstSequence().orElseThrow() == 3L
                      && active.lastSequence().orElseThrow() == 14L
                      && active.lastOutputSequence().orElseThrow() == 12L
                      && active.sequenceDomain()
                      == TechnologyExecutionEvidence.SequenceDomain.RENDERER_FRAME,
              "technology execution evidence lost execution counters or sequence identity");
      require(evidence.technology(RenderingFeatureCapabilities.Technology.RAY_TRACING_DENOISING).health()
                      == TechnologyExecutionEvidence.Health.UNAVAILABLE
                      && unavailable.errorCode().orElseThrow().equals("SDK_UNAVAILABLE"),
              "requested but unavailable technology lost its structured failure evidence");
      require(evidence.technology(RenderingFeatureCapabilities.Technology.MULTI_FRAME_GENERATION)
                      .equals(TechnologyExecutionEvidence.Entry.disabled()),
              "omitted technology must remain explicitly disabled");
      require(evidence.equals(evidence.toBuilder().build())
                      && evidence.hashCode() == evidence.toBuilder().build().hashCode(),
              "technology execution evidence copy lost immutable value semantics");
      expect(UnsupportedOperationException.class, () -> evidence.technologies().clear());
      expect(NullPointerException.class, () -> evidence.technology(null));
      expect(NullPointerException.class, () -> TechnologyExecutionEvidence.builder()
              .technology(null, active));
      expect(NullPointerException.class, () -> TechnologyExecutionEvidence.builder()
              .technology(RenderingFeatureCapabilities.Technology.FRAME_GENERATION, null));

      expect(IllegalArgumentException.class, () -> active.toBuilder()
              .recordedCount(9L)
              .build());
      expect(IllegalArgumentException.class, () -> active.toBuilder()
              .queueAcceptedCount(7L)
              .build());
      expect(IllegalArgumentException.class, () -> active.toBuilder()
              .gpuCompletedCount(0L)
              .build());
      expect(IllegalArgumentException.class, () -> active.toBuilder()
              .configuredParameters(java.util.Map.of("bad key", "2"))
              .build());
      expect(IllegalArgumentException.class, () -> active.toBuilder()
              .clearSequenceRange()
              .build());
      expect(IllegalArgumentException.class, () -> active.toBuilder()
              .sequenceDomain(TechnologyExecutionEvidence.SequenceDomain.NONE)
              .build());
      expect(IllegalArgumentException.class, () -> active.toBuilder()
              .lastOutputSequence(15L)
              .build());
      expect(IllegalArgumentException.class, () -> fallback.toBuilder()
              .clearFallbackCode()
              .build());
      expect(IllegalArgumentException.class, () -> active.toBuilder()
              .fallbackCode("REPLACEMENT_PENDING")
              .health(TechnologyExecutionEvidence.Health.FALLBACK_PENDING)
              .build());
      expect(IllegalArgumentException.class, () -> unavailable.toBuilder()
              .clearErrorCode()
              .health(TechnologyExecutionEvidence.Health.FAILED)
              .build());
      expect(IllegalArgumentException.class, () -> TechnologyExecutionEvidence.Entry.disabled()
              .toBuilder()
              .resetEpoch(1L)
              .build());
      expect(IllegalArgumentException.class, () -> unavailable.toBuilder()
              .requestedImplementation("human readable reason")
              .build());
   }

   private static void assertFrameGenerationContract() {
      FrameGenerationOptions production = FrameGenerationOptions.recommended();
      require(production.preference() == RendererFeaturePreference.PREFERRED
                      && production.mode() == FrameGenerationOptions.Mode.FRAME_GENERATION
                      && production.multiplier() == FrameGenerationOptions.Multiplier.TWO_X
                      && production.fallback() == FrameGenerationOptions.Fallback.PRESENT_NATIVE_FRAMES,
              "frame generation production policy must remain ordinary FG 2x");
      require(FrameGenerationOptions.disabled().mode() == FrameGenerationOptions.Mode.DISABLED,
              "disabled frame generation lost its explicit mode");
      FrameGenerationOptions multi = FrameGenerationOptions.builder()
              .preference(RendererFeaturePreference.PREFERRED)
              .mode(FrameGenerationOptions.Mode.MULTI_FRAME_GENERATION)
              .multiplier(FrameGenerationOptions.Multiplier.FOUR_X)
              .fallback(FrameGenerationOptions.Fallback.PRESENT_NATIVE_FRAMES)
              .build();
      require(multi.equals(multi.toBuilder().build())
                      && multi.multiplier().presentedFramesPerNativeFrame() == 4,
              "frame generation copy lost the multi-frame cadence");
      expect(IllegalArgumentException.class, () -> FrameGenerationOptions.builder()
              .preference(RendererFeaturePreference.PREFERRED)
              .mode(FrameGenerationOptions.Mode.FRAME_GENERATION)
              .multiplier(FrameGenerationOptions.Multiplier.THREE_X)
              .build());
      expect(IllegalArgumentException.class, () -> FrameGenerationOptions.builder()
              .preference(RendererFeaturePreference.PREFERRED)
              .mode(FrameGenerationOptions.Mode.MULTI_FRAME_GENERATION)
              .build());
      expect(IllegalArgumentException.class, () -> FrameGenerationOptions.builder()
              .mode(FrameGenerationOptions.Mode.FRAME_GENERATION)
              .build());
   }

   private static void assertLowLatencyContract() {
      LowLatencyOptions production = LowLatencyOptions.recommended();
      require(production.preference() == RendererFeaturePreference.PREFERRED,
              "production low-latency policy must be non-terminal");
      require(LowLatencyOptions.disabled().preference() == RendererFeaturePreference.DISABLED,
              "disabled low-latency policy changed");
      LowLatencyOptions required = LowLatencyOptions.builder()
              .preference(RendererFeaturePreference.REQUIRED)
              .build();
      require(required.equals(required.toBuilder().build()),
              "low-latency policy copy lost value semantics");
      expect(NullPointerException.class, () -> LowLatencyOptions.builder().preference(null));
   }

   private static void assertFramePollingContract() {
      for(Method method : GpuFrameLease.class.getDeclaredMethods()) {
         require(!method.getName().equals("released") && !method.getName().equals("closed"), "GPU frame lease exposed a compatibility boolean lifecycle alias: " + method.getName());
      }

      TrackingVulkanInterop interop = new TrackingVulkanInterop();
      require(interop.pollLatestFrame() == FrameNotReady.INSTANCE, "empty frame poll must return the canonical not-ready result");

      try {
         require(interop.awaitLatestFrame(Duration.ZERO) == FrameNotReady.INSTANCE, "zero-timeout frame wait must perform one non-blocking poll");
      } catch (InterruptedException impossible) {
         throw new AssertionError("zero-timeout wait was unexpectedly interrupted", impossible);
      }

      expect(IllegalArgumentException.class, () -> interop.awaitLatestFrame(Duration.ofNanos(-1L)));
      expect(NullPointerException.class, () -> interop.awaitLatestFrame((Duration)null));
      expect(NullPointerException.class, () -> interop.awaitLatestFrameAsync(Duration.ZERO, (Executor)null));
      assertQueuedVulkanFrameWaitCanBeCancelled();
      assertRunningVulkanFrameWaitRetainsResultOwnership();
      CompletableFuture<VulkanFrameInterop.FramePollResult> rejected = interop.awaitLatestFrameAsync(
              Duration.ZERO,
              action -> { throw new java.util.concurrent.RejectedExecutionException("injected rejection"); }
      );
      try {
         rejected.join();
         throw new AssertionError("rejected Vulkan frame wait did not fail its future");
      } catch (CompletionException expected) {
         require(expected.getCause() instanceof java.util.concurrent.RejectedExecutionException,
                 "executor rejection lost its original cause");
      }
      TrackingRenderer renderer = new TrackingRenderer();
      require(renderer.extension(VulkanFrameInterop.class).isEmpty(), "ordinary renderer fabricated Vulkan interoperability support");
   }

   private static void assertQueuedVulkanFrameWaitCanBeCancelled() {
      AtomicInteger polls = new AtomicInteger();
      AtomicReference<Runnable> queued = new AtomicReference<>();
      VulkanFrameInterop interop = () -> {
         polls.incrementAndGet();
         return FrameNotReady.INSTANCE;
      };
      CompletableFuture<VulkanFrameInterop.FramePollResult> future =
              interop.awaitLatestFrameAsync(Duration.ZERO, queued::set);
      require(future.cancel(true), "queued Vulkan frame wait must remain cancellable");
      queued.get().run();
      require(polls.get() == 0, "cancelled queued wait must not poll or acquire a frame lease");
   }

   private static void assertRunningVulkanFrameWaitRetainsResultOwnership() {
      CountDownLatch polling = new CountDownLatch(1);
      CountDownLatch releasePoll = new CountDownLatch(1);
      VulkanFrameInterop interop = () -> {
         polling.countDown();
         try {
            require(releasePoll.await(5L, TimeUnit.SECONDS), "timed out releasing Vulkan frame poll");
         } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Vulkan frame poll was interrupted", interrupted);
         }
         return FrameNotReady.INSTANCE;
      };
      ExecutorService executor = Executors.newSingleThreadExecutor();
      try {
         CompletableFuture<VulkanFrameInterop.FramePollResult> future =
                 interop.awaitLatestFrameAsync(Duration.ZERO, executor);
         require(polling.await(5L, TimeUnit.SECONDS), "Vulkan frame wait did not begin polling");
         require(!future.cancel(true),
                 "running Vulkan frame wait must reject cancellation before it can acquire a lease");
         releasePoll.countDown();
         require(future.get(5L, TimeUnit.SECONDS) == FrameNotReady.INSTANCE,
                 "running Vulkan frame wait lost its terminal result");
      } catch (Exception failure) {
         throw new AssertionError("running Vulkan frame wait contract failed", failure);
      } finally {
         releasePoll.countDown();
         executor.shutdownNow();
      }
   }

   private static void assertGpuDeviceSelectionContract() {
      RayTracingGpuDevice device = gpuDevice("vulkan", "00112233445566778899aabbccddeeff");
      RayTracingRendererConfig selected = RendererPreset.CPU_READBACK.configuration().copyBuilder().gpuDevice(device).build();
      require(((RayTracingGpuDevice)selected.gpuDevice().orElseThrow()).equals(device), "configuration lost selected GPU identity");
      require(RayTracingGpuDevice.class.getConstructors().length == 0, "GPU device exposed an ordered public constructor");
      require(HardwareCapabilities.RayTracingLimits.class.getConstructors().length == 0, "ray-tracing limits exposed an ordered public constructor");
      require(device.equals(device.toBuilder().build()), "GPU device toBuilder changed structural value semantics");
      expect(NullPointerException.class, () -> RayTracingRendererConfig.expertBuilder().gpuDevice((RayTracingGpuDevice)null));
      expect(NullPointerException.class, () -> RayTracingGpuDevice.builder().backendId((String)null));
      expect(IllegalArgumentException.class, () -> device.toBuilder().stableId(" " + device.stableId()));
      expect(IllegalArgumentException.class, () -> device.toBuilder().name("bad\nname"));
      expect(IllegalStateException.class, () -> RayTracingGpuDevice.builder()
              .backendId("vulkan")
              .stableId("explicit-id-contract")
              .name("Missing identifiers")
              .type(Type.DISCRETE)
              .apiVersion(new RayTracingGpuDevice.ApiVersion(1, 3, 0))
              .hardwareCapabilities(device.hardwareCapabilities())
              .build());
      expect(IllegalStateException.class, () -> RayTracingLimits.builder().maxRayRecursionDepth(1).build());
      expect(IllegalArgumentException.class, () -> device.toBuilder()
              .hardwareCapabilities(HardwareCapabilities.builder()
                      .probeState(HardwareCapabilities.ProbeState.FAILED)
                      .reason("contract probe failed")
                      .build())
              .build());
      TrackingProvider wrongBackend = new TrackingProvider("other", 1000, ProbeResult.compatible("ready"), false);
      TrackingProvider selectedBackend = new TrackingProvider("vulkan", 1, ProbeResult.compatible("ready"), false);
      RayTracingRenderer renderer = RendererBootstrap.openProviders((String)null, selected, List.of(wrongBackend, selectedBackend));
      require(renderer == selectedBackend.renderer, "selected GPU did not route to its owning backend");
      require(wrongBackend.opens.get() == 0, "bootstrap opened a backend that does not own the selected GPU");
      expect(IllegalArgumentException.class, () -> RendererBootstrap.openProviders("other", selected, List.of(wrongBackend, selectedBackend)));
   }

   private static void assertHardwareCapabilitiesContract() {
      HardwareCapabilities.RayTracingLimits limits = RayTracingLimits.builder()
              .maxRayRecursionDepth(2)
              .shaderGroupHandleSize(32)
              .shaderGroupHandleAlignment(32)
              .shaderGroupBaseAlignment(64)
              .maxShaderGroupStride(4096)
              .maxRayDispatchInvocationCount(1_073_741_824L)
              .minAccelerationStructureScratchAlignment(256)
              .build();
      HardwareCapabilities capabilities = hardwareCapabilities(limits);
      require(capabilities.probeState() == HardwareCapabilities.ProbeState.COMPLETE
                      && capabilities.features().size() == HardwareCapabilities.Feature.values().length
                      && capabilities.supports(HardwareCapabilities.Feature.HARDWARE_RAY_TRACING),
              "complete hardware snapshot lost total, positive RT evidence");
      require(capabilities.feature(HardwareCapabilities.Feature.MEMORY_BUDGET).state()
                      == HardwareCapabilities.SupportState.UNSUPPORTED,
              "complete hardware snapshot did not preserve queried unsupported evidence");
      require(capabilities.frameInterop(
                      FrameOutputFormat.SDR_RGBA8,
                      HardwareCapabilities.ExternalHandleType.OPAQUE_WIN32
              ).semaphoreImport().state() == HardwareCapabilities.SupportState.SUPPORTED,
              "format/handle-specific semaphore import evidence was lost");
      expect(UnsupportedOperationException.class, () -> capabilities.features().clear());
      expect(UnsupportedOperationException.class, () -> capabilities.frameInterop().clear());
      expect(IllegalArgumentException.class, () -> HardwareCapabilities.builder()
              .probeState(HardwareCapabilities.ProbeState.FAILED)
              .feature(
                      HardwareCapabilities.Feature.HARDWARE_RAY_TRACING,
                      HardwareCapabilities.Support.supported("fabricated support")
              )
              .reason("failed probe")
              .build());
      expect(IllegalArgumentException.class, () -> HardwareCapabilities.builder()
              .probeState(HardwareCapabilities.ProbeState.COMPLETE)
              .feature(
                      HardwareCapabilities.Feature.HARDWARE_RAY_TRACING,
                      HardwareCapabilities.Support.supported("incomplete RT evidence")
              )
              .maxImageDimension2D(16_384)
              .rayTracingLimits(limits)
              .reason("missing prerequisite")
              .build());
   }

   private static void assertCpuFrameContract() {
      byte[] pixels = new byte[]{1, 2, 3, 4};
      CpuFrame frame = CpuFrame.builder().frameSequence(4L).renderedSceneRevision(3L).extent(1, 1).pixelsRgba8(pixels).build();
      require(CpuFrame.class.getConstructors().length == 0, "CPU frame exposed an ordered public constructor");
      pixels[0] = 99;
      require(frame.frameSequence() == 4L && frame.renderedSceneRevision() == 3L && frame.width() == 1 && frame.height() == 1 && frame.byteCount() == 4, "CPU frame metadata changed");
      require(frame.pixelsRgba8().get(0) == 1, "CPU frame retained caller-owned pixels");
      expect(ReadOnlyBufferException.class, () -> frame.pixelsRgba8().put(0, (byte)9));
      ByteBuffer destination = ByteBuffer.allocate(6);
      destination.position(1);
      frame.copyPixelsRgba8To(destination);
      require(destination.position() == 5 && destination.get(1) == 1, "CPU frame copy did not respect destination position");
      expect(IllegalArgumentException.class, () -> frame.copyPixelsRgba8To(ByteBuffer.allocate(3)));
      expect(IllegalArgumentException.class, () -> frame.toBuilder().extent(1, 2).build());
      TrackingRenderer renderer = new TrackingRenderer();

      try {
         require(renderer.awaitLatestCpuFrame(Duration.ZERO).isEmpty(), "zero-timeout CPU frame poll fabricated a frame");
      } catch (InterruptedException impossible) {
         throw new AssertionError("zero-timeout CPU frame wait was unexpectedly interrupted", impossible);
      }

      expect(IllegalArgumentException.class, () -> renderer.awaitLatestCpuFrame(Duration.ofNanos(-1L)));
      expect(NullPointerException.class, () -> renderer.awaitLatestCpuFrameAsync(Duration.ZERO, (Executor)null));
      ExecutorService executor = Executors.newSingleThreadExecutor();

      try {
         CompletableFuture<Optional<CpuFrame>> cancelled = renderer.awaitLatestCpuFrameAsync(Duration.ofSeconds(1L), executor);
         require(cancelled.cancel(false), "async frame wait did not accept cancellation");
         Objects.requireNonNull(cancelled);
         expect(CancellationException.class, cancelled::join);
         require(!executor.awaitTermination(1L, TimeUnit.MILLISECONDS), "caller-owned executor was shut down by frame-wait cancellation");
      } catch (InterruptedException interrupted) {
         Thread.currentThread().interrupt();
         throw new AssertionError("contract executor wait was interrupted", interrupted);
      } finally {
         executor.shutdownNow();
      }

   }

   private static void assertSubmissionDeferralAndCloseContract() {
      RayTracingRenderer.FrameSubmissionDeferred typed = new RayTracingRenderer.FrameSubmissionDeferred(
            SubmissionDeferralReason.FRAME_RING_FULL,
            "all contract-test frame slots are retained"
      );
      require(typed.deferralReason() == SubmissionDeferralReason.FRAME_RING_FULL
                  && typed.detail().equals("all contract-test frame slots are retained"),
            "typed frame deferral lost its stable reason or diagnostic detail");

      expect(NullPointerException.class, () -> new RayTracingRenderer.FrameSubmissionDeferred(
              null, "missing typed category"));
      expect(IllegalArgumentException.class, () -> new RayTracingRenderer.FrameSubmissionDeferred(
              SubmissionDeferralReason.PROVIDER_CAPACITY, " "));

      SubmissionRejectedException rejection = new SubmissionRejectedException(
            SubmissionDeferralReason.RESOURCE_PRESSURE,
            "contract memory budget is saturated"
      );
      require(rejection.deferralReason() == SubmissionDeferralReason.RESOURCE_PRESSURE
                  && rejection.detail().equals("contract memory budget is saturated"),
            "typed rejection lost its stable reason or diagnostic detail");

      TrackingRenderer renderer = new TrackingRenderer();
      require(renderer.closeAsync().toCompletableFuture().isDone(),
            "synchronous provider default did not complete closeAsync");
      try {
         require(renderer.awaitClosed(Duration.ZERO),
               "synchronous provider default did not report completed cleanup");
      } catch (InterruptedException interrupted) {
         Thread.currentThread().interrupt();
         throw new AssertionError("synchronous close wait was unexpectedly interrupted", interrupted);
      }
      expect(IllegalArgumentException.class, () -> renderer.awaitClosed(Duration.ofNanos(-1L)));
      expect(NullPointerException.class, () -> renderer.awaitClosed(null));
   }

   private static RayTracingGpuDevice gpuDevice(String backendId, String stableId) {
      HardwareCapabilities.RayTracingLimits limits = RayTracingLimits.builder().maxRayRecursionDepth(31).shaderGroupHandleSize(32).shaderGroupHandleAlignment(32).shaderGroupBaseAlignment(64).maxShaderGroupStride(4096).maxRayDispatchInvocationCount(1073741824L).minAccelerationStructureScratchAlignment(256).build();
      return RayTracingGpuDevice.builder().backendId(backendId).stableId(stableId).name("Contract GPU").vendorId(4318).deviceId(1).type(Type.DISCRETE).apiVersion(new RayTracingGpuDevice.ApiVersion(1, 3, 0)).hardwareCapabilities(hardwareCapabilities(limits)).build();
   }

   private static HardwareCapabilities hardwareCapabilities(HardwareCapabilities.RayTracingLimits limits) {
      HardwareCapabilities.Support supported = HardwareCapabilities.Support.supported("contract evidence");
      HardwareCapabilities.Support unsupported = HardwareCapabilities.Support.unsupported("contract absence");
      return HardwareCapabilities.builder()
              .probeState(HardwareCapabilities.ProbeState.COMPLETE)
              .feature(HardwareCapabilities.Feature.HARDWARE_RAY_TRACING, supported)
              .feature(HardwareCapabilities.Feature.ACCELERATION_STRUCTURE, supported)
              .feature(HardwareCapabilities.Feature.RAY_TRACING_PIPELINE, supported)
              .feature(HardwareCapabilities.Feature.BUFFER_DEVICE_ADDRESS, supported)
              .feature(HardwareCapabilities.Feature.SHADER_INT64, supported)
              .feature(HardwareCapabilities.Feature.EXTERNAL_MEMORY, supported)
              .feature(HardwareCapabilities.Feature.EXTERNAL_SEMAPHORE, supported)
              .feature(HardwareCapabilities.Feature.MEMORY_BUDGET, unsupported)
              .feature(HardwareCapabilities.Feature.GPU_TIMESTAMPS, supported)
              .deviceLocalMemoryBytes(8_589_934_592L)
              .maxImageDimension2D(16_384)
              .rayTracingLimits(limits)
              .frameInterop(
                      FrameOutputFormat.SDR_RGBA8,
                      HardwareCapabilities.ExternalHandleType.OPAQUE_WIN32,
                      new HardwareCapabilities.FrameInteropSupport(
                              supported,
                              supported,
                              supported,
                              supported,
                              HardwareCapabilities.DedicatedAllocation.NOT_REQUIRED
                      )
              )
              .frameInterop(
                      FrameOutputFormat.LINEAR_HDR_RGBA16F,
                      HardwareCapabilities.ExternalHandleType.OPAQUE_WIN32,
                      new HardwareCapabilities.FrameInteropSupport(
                              unsupported,
                              unsupported,
                              supported,
                              supported,
                              HardwareCapabilities.DedicatedAllocation.UNKNOWN
                      )
              )
              .reason("complete contract probe")
              .build();
   }

   private static void assertTransformAndLightingValidation() {
      float[] elements = new float[]{1.0F, 0.0F, 0.0F, 2.0F, 0.0F, 1.0F, 0.0F, 3.0F, 0.0F, 0.0F, 1.0F, 4.0F};
      AffineTransform transform = new AffineTransform(elements);
      elements[0] = 9.0F;
      require(transform.element(0) == 1.0F, "affine transform retained caller-owned storage");
      expect(ReadOnlyBufferException.class, () -> transform.elements().put(0, 9.0F));
      expect(IllegalArgumentException.class, () -> new AffineTransform(new float[11]));
      expect(IllegalArgumentException.class, () -> new AffineTransform(new float[12]));
      require(EnvironmentState.class.getConstructors().length == 0 && EnvironmentState.Medium.class.getConstructors().length == 0 && DistanceFogState.class.getConstructors().length == 0, "environment or fog state exposed an ordered public constructor");
      EnvironmentState neutral = EnvironmentState.neutral();
      require(neutral.cameraMedium().equals(Medium.vacuum()), "neutral environment changed its camera medium");
      EnvironmentState.Medium medium = Medium.builder().extinction(0.01F, 0.02F, 0.03F).scattering(0.04F, 0.05F, 0.06F).density(0.4F).indexOfRefraction(1.333F).build();
      EnvironmentState environment = EnvironmentState.builder().skyRadiance(0.1F, 0.2F, 0.3F).ambientIntensity(0.4F).sunDirection(0.0F, 1.0F, 0.0F).sunRadiance(0.9F, 0.8F, 0.7F).sunIntensity(12.0F).cameraMedium(medium).build();
      require(environment.skyGreen() == 0.2F && environment.sunBlue() == 0.7F && environment.cameraMedium().scatteringGreen() == 0.05F && environment.equals(environment.toBuilder().build()) && medium.equals(medium.toBuilder().build()), "semantic environment builders changed field mapping or value semantics");
      expect(IllegalArgumentException.class, () -> Medium.builder().indexOfRefraction(0.99F).build());
      expect(IllegalArgumentException.class, () -> EnvironmentState.builder().sunDirection(0.0F, 2.0F, 0.0F).build());
      SceneLight builtPoint = SceneLight.point(10L, 1.0, 2.0, 3.0).color(0.25F, 0.5F, 1.0F).intensity(4.0F).range(12.0F).castsShadow(false).build();
      require(builtPoint.type() == top.ceroxe.rt.renderer.api.SceneLight.Type.POINT && builtPoint.directionX() == 0.0F && builtPoint.range() == 12.0F && !builtPoint.castsShadow(), "point-light builder changed semantic defaults");
      SceneLight builtSpot = SceneLight.spot(11L, 0.0, 1.0, 0.0, 0.0F, -2.0F, 0.0F).coneDegrees(15.0F, 30.0F).build();
      require(Math.abs(builtSpot.directionY() + 1.0F) < 1.0E-6F && builtSpot.innerConeCosine() >= builtSpot.outerConeCosine(), "spot-light builder failed to normalize direction or cone ordering");
      expect(IllegalStateException.class, () -> SceneLight.directional(12L, 0.0F, -1.0F, 0.0F).range(2.0F));
      expect(IllegalArgumentException.class, () -> SceneLight.directional(13L, 0.0F, 0.0F, 0.0F));
      require(SceneLight.class.getConstructors().length == 0, "scene light exposed an ordered public constructor");
      SceneLight point = SceneLight.point(1L, 0.0, 1.0, 2.0).color(1.0F, 1.0F, 1.0F).intensity(10.0F).range(8.0F).castsShadow(true).build();
      require(point.range() == 8.0F, "point-light range changed");
      SceneLight rebuiltPoint = point.toBuilder().build();
      require(point.equals(rebuiltPoint) && point.hashCode() == rebuiltPoint.hashCode() && point.toString().startsWith("SceneLight["), "scene-light builder round-trip lost immutable value semantics");
      expect(IllegalArgumentException.class, () -> SceneLight.point(2L, 0.0, 0.0, 0.0).range(0.0F));
      expect(IllegalArgumentException.class, () -> SceneLight.spot(3L, 0.0, 0.0, 0.0, 0.0F, -1.0F, 0.0F).coneCosines(0.2F, 0.3F));
      expect(IllegalStateException.class, () -> SceneLight.directional(4L, 0.0F, -1.0F, 0.0F).coneCosines(0.8F, 0.7F));
   }

   private static void assertCameraAndFrameValidation() {
      require(CameraState.class.getConstructors().length == 0, "camera state exposed an ordered public constructor");
      CameraState builtCamera = CameraState.lookAt(0.0, 1.0, 5.0, 0.0, 1.0, 0.0).aspectRatio(2.0).verticalFieldOfViewDegrees(90.0).build();
      require(Math.abs(builtCamera.forwardZ() + 1.0F) < 1.0E-6F && Math.abs(builtCamera.rightX() - 1.0F) < 1.0E-6F && Math.abs(builtCamera.upY() - 1.0F) < 1.0E-6F && Math.abs(builtCamera.tanHalfFovX() - 2.0F) < 1.0E-5F && Math.abs(builtCamera.tanHalfFovY() - 1.0F) < 1.0E-5F, "look-at camera builder derived the wrong basis or projection");
      expect(IllegalArgumentException.class, () -> CameraState.lookAt(0.0, 0.0, 0.0, 0.0, 0.0, 0.0).build());
      expect(IllegalArgumentException.class, () -> CameraState.lookAt(0.0, 0.0, 0.0, 0.0, 1.0, 0.0).build());
      expect(IllegalArgumentException.class, () -> CameraState.lookAt(0.0, 0.0, 0.0, 0.0, 0.0, -1.0).verticalFieldOfViewDegrees(180.0));
      expect(IllegalArgumentException.class, () -> CameraState.explicitBasis(0.0, 0.0, 0.0).forward(0.0F, 0.0F, -1.0F).right(1.0F, 0.0F, 0.0F).up(0.0F, -1.0F, 0.0F).projectionTangents(1.0F, 1.0F).build());
      CameraState camera = camera();
      CameraState equivalentCamera = camera();
      require(camera.equals(equivalentCamera) && camera.hashCode() == equivalentCamera.hashCode() && camera.toString().startsWith("CameraState["), "camera state lost immutable value semantics");
      RenderFrameRequest request = RenderFrameRequest.builder(7L, 1920, 1080, camera).minimumSceneRevision(3L).build();
      require(request.width() == 1920 && request.height() == 1080, "frame extent changed");
      require(request.lightmap() == LightmapState.fullIntensity(), "frame builder must select the neutral lightmap by default");
      require(request.fog() == DistanceFogState.disabled(), "frame builder must select disabled distance fog by default");
      require(request.textureSampling().equals(TextureSamplingState.pixelStable()), "frame builder must select pixel-stable texture minification by default");
      require(TextureSamplingState.anisotropic(16).maxAnisotropy() == 16, "supported anisotropic sampling level changed");
      expect(IllegalArgumentException.class, () -> TextureSamplingState.anisotropic(17));
      expect(IllegalArgumentException.class, () -> new TextureSamplingState(MinificationMode.PIXEL_STABLE, 2));
      int[] lightmapTexels = new int[256];
      lightmapTexels[0] = -13426159;
      LightmapState lightmap = new LightmapState(4L, lightmapTexels);
      lightmapTexels[0] = 0;
      require(lightmap.texelsRgba8().get(0) == -13426159, "lightmap retained caller-owned texels");
      expect(ReadOnlyBufferException.class, () -> lightmap.texelsRgba8().put(0, 0));
      expect(IllegalArgumentException.class, () -> new LightmapState(-1L, new int[256]));
      expect(IllegalArgumentException.class, () -> new LightmapState(0L, new int[255]));
      DistanceFogState fog = DistanceFogState.builder().color(0.2F, 0.3F, 0.4F).opacity(0.75F).sphericalRange(-8.0F, 16.0F).cylindricalRange(64.0F, 96.0F).build();
      require(fog.green() == 0.3F && fog.sphericalStart() == -8.0F && fog.cylindricalEnd() == 96.0F && fog.equals(fog.toBuilder().build()), "semantic fog builder changed field mapping or value semantics");
      expect(IllegalArgumentException.class, () -> DistanceFogState.builder().color(1.1F, 0.0F, 0.0F).build());
      expect(IllegalArgumentException.class, () -> CameraState.explicitBasis(0.0, 0.0, 0.0).forward(0.0F, 0.0F, -2.0F).right(1.0F, 0.0F, 0.0F).up(0.0F, 1.0F, 0.0F).projectionTangents(1.0F, 1.0F).build());
      expect(IllegalArgumentException.class, () -> RenderFrameRequest.builder(1L, 2147483647, 2147483647, camera).build());
   }

   private static void assertExactProjectionContract() {
      double[] identityView = {
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, -100.0 / 99.0, -100.0 / 99.0,
            0, 0, -1, 0
      };
      double[] rigid = {
            0, 0, -1, 4,
            0, 1, 0, 5,
            1, 0, 0, 6,
            0, 0, 0, 1
      };
      ExactProjectionState exact = ExactProjectionState.builder(1280, 800)
            .matrixLayout(ExactProjectionState.MatrixLayout.ROW_MAJOR)
            .coordinateSystem(ExactProjectionState.CoordinateSystem.RIGHT_HANDED_NEGATIVE_Z_FORWARD)
            .depthConvention(ExactProjectionState.DepthConvention.ZERO_TO_ONE)
            .cameraToWorld(rigid)
            .clipFromView(identityView)
            .build();
      ExactProjectionState.Ray center = exact.rayForPixel(639.5, 399.5);
      require(Math.abs(center.originX() - 4.0) < 1.0E-9 && Math.abs(center.originY() - 5.0) < 1.0E-9
                  && Math.abs(center.originZ() - 6.0) < 1.0E-9,
            "exact camera-to-world translation was not retained");
      require(Math.abs(center.directionX() - 1.0) < 1.0E-6
                  && Math.abs(center.directionY()) < 1.0E-6
                  && Math.abs(center.directionZ()) < 1.0E-6,
            "rigid rotation did not rotate the exact primary ray");
      ExactProjectionState columnMajor = ExactProjectionState.builder(1280, 800)
            .matrixLayout(ExactProjectionState.MatrixLayout.COLUMN_MAJOR)
            .coordinateSystem(ExactProjectionState.CoordinateSystem.RIGHT_HANDED_NEGATIVE_Z_FORWARD)
            .depthConvention(ExactProjectionState.DepthConvention.ZERO_TO_ONE)
            .cameraToWorld(transpose(rigid))
            .clipFromView(transpose(identityView))
            .build();
      ExactProjectionState.Ray columnRay = columnMajor.rayForPixel(639.5, 399.5);
      require(Math.abs(columnRay.directionX() - center.directionX()) < 1.0E-9
                  && Math.abs(columnRay.directionY() - center.directionY()) < 1.0E-9
                  && Math.abs(columnRay.directionZ() - center.directionZ()) < 1.0E-9,
            "explicit column-major layout was not canonicalized exactly");
      CameraState exactCamera = CameraState.exactProjection(exact);
      require(exactCamera.projectionPath() == CameraState.ProjectionPath.EXACT_CLIP
                  && exactCamera.hasExactProjection()
                  && exactCamera.exactProjection().equals(exact),
            "exact camera path lost its explicit discriminator");
      expect(IllegalStateException.class, () -> camera().exactProjection());

      double[] warp = identityView.clone();
      warp[1] = 0.25;
      warp[4] = -0.15;
      ExactProjectionState warped = ExactProjectionState.builder(1280, 800)
            .matrixLayout(ExactProjectionState.MatrixLayout.ROW_MAJOR)
            .coordinateSystem(ExactProjectionState.CoordinateSystem.RIGHT_HANDED_NEGATIVE_Z_FORWARD)
            .depthConvention(ExactProjectionState.DepthConvention.ZERO_TO_ONE)
            .cameraToWorld(new double[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1})
            .clipFromView(warp)
            .build();
      ExactProjectionState.Ray warpedRay = warped.rayForPixel(800.0, 400.0);
      require(Math.abs(warpedRay.directionY()) > 1.0E-4,
            "rotated non-uniform projection warp was ignored");
      ExactProjectionState jittered = ExactProjectionState.builder(1280, 800)
            .matrixLayout(ExactProjectionState.MatrixLayout.ROW_MAJOR)
            .coordinateSystem(ExactProjectionState.CoordinateSystem.RIGHT_HANDED_NEGATIVE_Z_FORWARD)
            .depthConvention(ExactProjectionState.DepthConvention.ZERO_TO_ONE)
            .jitter(ExactProjectionState.JitterConvention.PIXEL_CENTER_OFFSET, 0.5, -0.25)
            .cameraToWorld(new double[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1})
            .clipFromView(identityView)
            .build();
      require(Math.abs(jittered.rayForPixel(639.5, 359.5).directionX()
                  - exact.rayForPixel(639.5, 359.5).directionX()) > 1.0E-5,
            "pixel jitter did not affect exact ray mapping");

      expect(IllegalArgumentException.class, () -> ExactProjectionState.builder(1280, 800)
            .matrixLayout(ExactProjectionState.MatrixLayout.ROW_MAJOR)
            .coordinateSystem(ExactProjectionState.CoordinateSystem.RIGHT_HANDED_NEGATIVE_Z_FORWARD)
            .depthConvention(ExactProjectionState.DepthConvention.ZERO_TO_ONE)
            .cameraToWorld(new double[16]).clipFromView(identityView).build());
      expect(IllegalArgumentException.class, () -> ExactProjectionState.builder(1280, 800)
            .matrixLayout(ExactProjectionState.MatrixLayout.ROW_MAJOR)
            .coordinateSystem(ExactProjectionState.CoordinateSystem.RIGHT_HANDED_NEGATIVE_Z_FORWARD)
            .depthConvention(ExactProjectionState.DepthConvention.ZERO_TO_ONE)
            .cameraToWorld(rigid).clipFromView(new double[]{Double.NaN, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1}).build());
      expect(IllegalArgumentException.class, () -> ExactProjectionState.builder(1280, 800)
            .matrixLayout(ExactProjectionState.MatrixLayout.ROW_MAJOR)
            .coordinateSystem(ExactProjectionState.CoordinateSystem.RIGHT_HANDED_NEGATIVE_Z_FORWARD)
            .depthConvention(ExactProjectionState.DepthConvention.ZERO_TO_ONE)
            .jitter(ExactProjectionState.JitterConvention.NONE, 1, 0)
            .cameraToWorld(rigid).clipFromView(identityView).build());
      expect(IllegalArgumentException.class, () -> ExactProjectionState.builder(1280, 800)
            .coordinateSystem(ExactProjectionState.CoordinateSystem.RIGHT_HANDED_NEGATIVE_Z_FORWARD)
            .depthConvention(ExactProjectionState.DepthConvention.ZERO_TO_ONE)
            .cameraToWorld(rigid).clipFromView(identityView).build());
      require(camera().projectionPath() == CameraState.ProjectionPath.BASIS_FOV
                  && !camera().hasExactProjection()
                  && camera().tanHalfFovX() == 1.0F,
            "legacy CameraState basis/FOV regression detected");
   }

   private static void assertTemporalRenderingContract() {
      require(!TemporalRenderingOptions.disabled().enabled() && TemporalRenderingOptions.disabled().maxHistoryFrames() == 0, "disabled temporal policy must allocate no history");
      require(TemporalRenderingOptions.balanced().enabled() && TemporalRenderingOptions.balanced().maxHistoryFrames() == 8, "balanced temporal policy changed its bounded history contract");
      require(TemporalRenderingOptions.accumulating(2).maxHistoryFrames() == 2, "minimum explicit history length was not retained");
      require(TemporalRenderingOptions.accumulating(64).maxHistoryFrames() == 64, "maximum explicit history length was not retained");
      expect(IllegalArgumentException.class, () -> TemporalRenderingOptions.accumulating(1));
      expect(IllegalArgumentException.class, () -> TemporalRenderingOptions.accumulating(65));
      RenderFrameRequest request = RenderFrameRequest.builder(9L, 32, 18, camera()).resetTemporalHistory(HistoryResetReason.CAMERA_CUT).resetTemporalHistory(HistoryResetReason.CAMERA_CUT).resetTemporalHistory(HistoryResetReason.EXPLICIT_RESET).build();
      require(request.temporalHistoryResets().equals(Set.of(HistoryResetReason.CAMERA_CUT, HistoryResetReason.EXPLICIT_RESET)), "temporal reset reasons must be immutable and deduplicated");
      expect(UnsupportedOperationException.class, () -> request.temporalHistoryResets().clear());
      require(request.toBuilder().continueTemporalHistory().build().temporalHistoryResets().isEmpty(), "request builder could not resume continuous temporal history");
      expect(NullPointerException.class, () -> request.toBuilder().resetTemporalHistory((HistoryResetReason)null));
      require(Modifier.isPrivate(RayTracingRendererConfig.class.getDeclaredConstructors()[0].getModifiers()), "renderer configuration exposed an ordered compatibility constructor");
      require(Modifier.isPrivate(RenderFrameRequest.class.getDeclaredConstructors()[0].getModifiers()), "frame request exposed an ordered compatibility constructor");
   }

   private static void assertAssetOwnership() {
      require(SceneInstance.class.getConstructors().length == 0, "scene instance exposed an ordered public constructor");
      byte[] pixels = new byte[]{1, 2, 3, 4};
      TextureAsset texture = TextureAsset.builder(1L, 1, 1).colorSpace(ColorSpace.SRGB).addressModes(AddressMode.REPEAT, AddressMode.CLAMP_TO_EDGE).filter(Filter.LINEAR).pixelsRgba8(pixels).build();
      pixels[0] = 99;
      require(texture.rgba8().get(0) == 1, "texture retained caller-owned pixel array");
      require(texture.mipLevelCount() == 1 && texture.mipWidth(0) == 1 && texture.mipByteOffset(0) == 0, "single-mip texture construction must retain one mip level");
      expect(ReadOnlyBufferException.class, () -> texture.rgba8().put(0, (byte)8));
      require(MeshAsset.class.getConstructors().length == 0, "mesh asset exposed an ordered public constructor");
      float[] positions = new float[]{0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F};
      float[] normals = new float[]{0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F};
      int[] indices = new int[]{0, 1, 2};
      long[] materialIds = new long[]{2L};
      MeshAsset mesh = MeshAsset.builder(3L, positions, indices, materialIds).normals(normals).build();
      positions[0] = 42.0F;
      normals[2] = -1.0F;
      indices[0] = 2;
      materialIds[0] = 99L;
      require(mesh.positions().get(0) == 0.0F, "mesh retained caller-owned position array");
      require(mesh.normals().get(2) == 1.0F, "mesh builder retained caller-owned optional array");
      require(mesh.triangleIndices().get(0) == 0, "mesh retained caller-owned index array");
      require(mesh.triangleMaterialIds().get(0) == 2L, "mesh retained caller-owned material-id array");
      expect(ReadOnlyBufferException.class, () -> mesh.positions().put(0, 2.0F));
      MeshAsset rebuiltMesh = mesh.toBuilder().build();
      require(mesh.equals(rebuiltMesh) && mesh.hashCode() == rebuiltMesh.hashCode(), "mesh value semantics changed across toBuilder");
      expect(IllegalArgumentException.class, () -> MeshAsset.triangles(4L, new float[]{0.0F, 0.0F, 0.0F}, new int[]{0, 1, 0}, 2L));
      SceneInstance fullyVisible = SceneInstance.builder(5L, 3L).build();
      require(fullyVisible.surfaceVisibility() == 1.0F, "instance builder must default to full surface visibility");
      require(fullyVisible.packedLight() == SceneInstance.FULL_BRIGHT_PACKED_LIGHT,
              "instance builder must default to full-bright lightmap coordinates");
      SceneInstance partiallyLit = fullyVisible.toBuilder().lightmapCoordinates(16, 224).build();
      require(partiallyLit.packedLight() == 0x00e0_0010,
              "instance builder changed generic lightmap coordinate packing");
      SceneInstance rebuilt = fullyVisible.toBuilder().build();
      require(fullyVisible.equals(rebuilt) && fullyVisible.hashCode() == rebuilt.hashCode() && fullyVisible.toString().startsWith("SceneInstance["), "scene instance builder round-trip lost immutable value semantics");
      expect(IllegalArgumentException.class, () -> SceneInstance.builder(6L, 3L).surfaceVisibility(0.0F / 0.0F).build());
      expect(IllegalArgumentException.class, () -> SceneInstance.builder(6L, 3L).surfaceVisibility(1.01F).build());
      expect(IllegalArgumentException.class, () -> SceneInstance.builder(6L, 3L).lightmapCoordinates(-1, 0));
      expect(IllegalArgumentException.class, () -> SceneInstance.builder(6L, 3L).lightmapCoordinates(0, SceneInstance.MAX_LIGHT_COORDINATE + 1));
      expect(IllegalArgumentException.class, () -> SceneInstance.builder(6L, 3L).packedLight(0x00f1_0000).build());
   }

   private static void assertMipChainContract() {
      byte[] mipBytes = new byte[Math.toIntExact(TextureAsset.requiredByteCount(4, 2, 3))];
      TextureAsset texture = TextureAsset.builder(8L, 4, 2).colorSpace(ColorSpace.SRGB).addressModes(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE).filter(Filter.LINEAR).mipChainRgba8(3, mipBytes).build();
      require(texture.mipLevelCount() == 3 && texture.mipWidth(0) == 4 && texture.mipHeight(0) == 2 && texture.mipWidth(1) == 2 && texture.mipHeight(1) == 1 && texture.mipWidth(2) == 1 && texture.mipHeight(2) == 1 && texture.mipByteOffset(0) == 0 && texture.mipByteOffset(1) == 32 && texture.mipByteOffset(2) == 40, "mip chain dimensions and tightly packed offsets changed");
      expect(IllegalArgumentException.class, () -> TextureAsset.builder(9L, 4, 2).colorSpace(ColorSpace.SRGB).addressModes(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE).filter(Filter.LINEAR).mipChainRgba8(4, mipBytes).build());
   }

   private static void assertDirectAssetOwnership() {
      ByteBuffer meshStorage = ByteBuffer.allocateDirect(48).order(ByteOrder.nativeOrder());
      FloatBuffer writablePositions = meshStorage.slice(0, 36).order(ByteOrder.nativeOrder()).asFloatBuffer();
      writablePositions.put(new float[]{0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F}).flip();
      IntBuffer writableIndices = meshStorage.slice(36, 12).order(ByteOrder.nativeOrder()).asIntBuffer();
      writableIndices.put(new int[]{0, 1, 2}).flip();
      LongBuffer writableMaterials = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).asLongBuffer();
      writableMaterials.put(2L).flip();
      FloatBuffer emptyFloats = ByteBuffer.allocateDirect(0).asFloatBuffer().asReadOnlyBuffer();
      IntBuffer emptyInts = ByteBuffer.allocateDirect(0).asIntBuffer().asReadOnlyBuffer();
      MeshAsset mesh = MeshAsset.wrapImmutableDirect(7L, writablePositions.asReadOnlyBuffer(), emptyFloats, emptyFloats, emptyFloats, emptyFloats, emptyInts, writableIndices.asReadOnlyBuffer(), writableMaterials.asReadOnlyBuffer());
      writablePositions.position(3);
      writableIndices.position(1);
      require(mesh.positions().position() == 0 && mesh.positions().remaining() == 9, "mesh direct view inherited caller position mutation");
      require(mesh.triangleIndices().position() == 0 && mesh.triangleIndices().remaining() == 3, "mesh index view inherited caller position mutation");
      require(mesh.positions().isDirect() && mesh.positions().isReadOnly(), "mesh did not retain immutable direct storage");
      expect(IllegalArgumentException.class, () -> MeshAsset.wrapImmutableDirect(8L, writablePositions, emptyFloats, emptyFloats, emptyFloats, emptyFloats, emptyInts, writableIndices.asReadOnlyBuffer(), writableMaterials.asReadOnlyBuffer()));
      ByteBuffer writablePixels = ByteBuffer.allocateDirect(8);
      writablePixels.put(new byte[]{9, 8, 7, 6, 5, 4, 3, 2}).flip();
      writablePixels.position(4);
      TextureAsset texture = TextureAsset.wrapImmutableDirect(9L, 1, 1, ColorSpace.LINEAR, AddressMode.REPEAT, AddressMode.REPEAT, Filter.NEAREST, writablePixels.asReadOnlyBuffer());
      writablePixels.position(8);
      require(texture.rgba8().position() == 0 && texture.rgba8().remaining() == 4, "texture direct view inherited caller position mutation");
      require(texture.rgba8().get(0) == 5 && texture.rgba8().isDirect() && texture.rgba8().isReadOnly(), "texture did not retain the captured immutable direct range");
      expect(IllegalArgumentException.class, () -> TextureAsset.wrapImmutableDirect(10L, 1, 1, ColorSpace.LINEAR, AddressMode.REPEAT, AddressMode.REPEAT, Filter.NEAREST, ByteBuffer.allocateDirect(4)));
      MeshAsset basicMesh = MeshAsset.triangles(11L, new float[]{0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F}, new int[]{0, 1, 2}, 12L);
      require(basicMesh.triangleMaterialIds().get(0) == 12L, "beginner mesh factory did not broadcast its material");
      TextureAsset color = TextureAsset.color(13L, 1, 1, new byte[]{1, 2, 3, 4});
      require(color.colorSpace() == ColorSpace.SRGB && color.addressU() == AddressMode.REPEAT && color.filter() == Filter.LINEAR, "beginner texture factory defaults changed");
   }

   private static void assertTransactionOwnershipAndConflicts() {
      MaterialAsset material = material(2L);
      TextureAsset materialTexture = TextureAsset.color(21L, 1, 1, new byte[]{1, 2, 3, 4});
      MaterialAsset builtMaterial = MaterialAsset.builder(22L).baseColorTexture(materialTexture).roughness(0.35F).metallic(0.8F).transmission(0.2F).indexOfRefraction(1.45F).doubleSided(true).build();
      require(builtMaterial.baseColorTextureId() == materialTexture.id() && builtMaterial.blendMode() == BlendMode.TRANSLUCENT && builtMaterial.transmission() == 0.2F && builtMaterial.doubleSided(), "material builder lost texture identity or physical defaults");
      MaterialAsset opaque = MaterialAsset.opaque(23L, -15654349);
      require(opaque.blendMode() == BlendMode.OPAQUE && opaque.roughness() == 1.0F && opaque.transmission() == 0.0F, "simple opaque material factory changed safe defaults");
      expect(IllegalArgumentException.class, () -> MaterialAsset.builder(24L).roughness(0.0F / 0.0F));
      MaterialAsset transmissionFirst = MaterialAsset.builder(25L).transmission(0.4F).blendMode(BlendMode.MASKED).build();
      MaterialAsset blendFirst = MaterialAsset.builder(26L).blendMode(BlendMode.MASKED).transmission(0.4F).build();
      require(transmissionFirst.blendMode() == blendFirst.blendMode(), "material builder result depends on blend/transmission call order");
      expect(IllegalArgumentException.class, () -> MaterialAsset.builder(27L).blendMode(BlendMode.OPAQUE).transmission(0.4F).build());
      expect(IllegalArgumentException.class, () -> MaterialAsset.builder(28L).transmission(0.4F).blendMode(BlendMode.OPAQUE).build());
      require(material.shadingModel() == ShadingModel.PHYSICALLY_BASED, "material builder must retain PBR shading by default");
      require(SceneTransaction.class.getConstructors().length == 0 && SceneTransaction.Upserts.class.getConstructors().length == 0 && SceneTransaction.Removals.class.getConstructors().length == 0, "scene transaction types exposed ordered public constructors");
      TextureAsset builtTexture = TextureAsset.color(20L, 1, 1, new byte[]{1, 2, 3, 4});
      SceneTransaction.Builder builder = SceneTransaction.builder(4L).upsert(builtTexture).upsert(material).removeMesh(30L);
      SceneTransaction built = builder.build();
      builder.removeLight(40L);
      require(built.revision() == 4L && !built.reset() && built.upserts().textures().equals(List.of(builtTexture)) && built.upserts().materials().equals(List.of(material)) && built.removals().meshIds().get(0) == 30L && !built.removals().lightIds().hasRemaining(), "transaction builder did not create an isolated dependency-ordered snapshot");
      SceneTransaction resetBuilt = SceneTransaction.builder(5L).resetScene().upsert(builtTexture).build();
      require(resetBuilt.reset() && !resetBuilt.removals().hasChanges(), "reset transaction did not retain its dependency-safe replacement semantics");
      expect(IllegalArgumentException.class, () -> SceneTransaction.builder(6L).resetScene().removeTexture(20L).build());
      expect(IllegalArgumentException.class, () -> SceneTransaction.builder(6L).upsert(builtTexture).removeTexture(20L).build());
      expect(IllegalArgumentException.class, () -> SceneTransaction.builder(6L).removeTexture(20L).removeTexture(20L).build());
      List<MaterialAsset> callerList = new ArrayList<>();
      callerList.add(material);
      long[] removals = new long[]{9L};
      SceneTransaction transaction = SceneTransaction.builder(6L).upsertMaterials(callerList).removeMaterials(removals).build();
      callerList.clear();
      removals[0] = 10L;
      require(transaction.upserts().materials().size() == 1, "transaction retained caller-owned list");
      require(transaction.removals().materialIds().get(0) == 9L, "transaction retained caller-owned removal array");
      require(transaction.hasChanges(), "non-empty transaction reported no changes");
      SceneTransaction equivalent = SceneTransaction.builder(6L).upsert(material).removeMaterial(9L).build();
      require(transaction.equals(equivalent) && transaction.hashCode() == equivalent.hashCode() && transaction.toString().startsWith("SceneTransaction["), "scene transaction lost immutable value semantics");
      expect(IllegalArgumentException.class, () -> SceneTransaction.builder(6L).upsert(material).removeMaterial(material.id()).build());
      expect(IllegalArgumentException.class, () -> SceneTransaction.builder(6L).removeTextures(new long[]{1L, 1L}).build());
   }

   private static void assertDiagnosticsAndResultValidation() {
      RendererDiagnostics.FrameGpuTiming timing = FrameGpuTiming.builder().enabled(true).completedSamples(10L).averageTraceNanos(400L).averagePostTraceNanos(25L).averageTotalNanos(425L).maxTotalNanos(500L).build();
      FrameGenerationEvidence generation = FrameGenerationEvidence.builder().reported(true).requestedGeneratedFramesPerNativeFrame(2).lastSubmittedGeneratedFramesPerNativeFrame(2).configuredGeneratedFramesPerNativeFrame(2).proxyPresentCalls(10L).stateSamples(8L).stateQueryCalls(12L).totalFramesActuallyPresented(24L).generatedFramesActuallyPresented(16L).lastFramesActuallyPresented(3).maximumSupportedGeneratedFramesPerNativeFrame(3).maximumGeneratedFramesObservedPerSample(2).latestNativeStatus(OptionalInt.of(0)).proxyPresentSequenceRange(3L, 12L).lastGeneratedObservationSequence(12L).resetEpoch(1L).build();
      TechnologyExecutionEvidence.Entry execution = TechnologyExecutionEvidence.Entry.builder()
              .requestPreference(RendererFeaturePreference.PREFERRED)
              .requestedImplementation("nvidia.dlss-g")
              .negotiatedImplementation("nvidia.dlss-g")
              .configuredImplementation("nvidia.dlss-g")
              .health(TechnologyExecutionEvidence.Health.READY)
              .build();
      TechnologyExecutionEvidence technologies = TechnologyExecutionEvidence.builder()
              .technology(RenderingFeatureCapabilities.Technology.FRAME_GENERATION, execution)
              .build();
      require(RendererDiagnostics.class.getConstructors().length == 0 && RendererDiagnostics.FrameGpuTiming.class.getConstructors().length == 0 && FrameGenerationEvidence.class.getConstructors().length == 0 && TechnologyExecutionEvidence.class.getConstructors().length == 0, "diagnostics types exposed ordered public constructors");
      require(timing.averageTotalNanos() == 425L, "GPU total timing changed");
      require(generation.active() && generation.requestedPresentationMultiplier() == 3 && generation.configuredPresentationMultiplier() == 3, "typed frame-generation evidence lost cadence or activity semantics");
      require(generation.equals(generation.toBuilder().build()) && generation.hashCode() == generation.toBuilder().build().hashCode(), "frame-generation evidence copy lost immutable value semantics");
      expect(IllegalArgumentException.class, () -> timing.toBuilder().averageTotalNanos(420L).build());
      expect(IllegalArgumentException.class, () -> timing.toBuilder().enabled(false).build());
      expect(IllegalArgumentException.class, () -> timing.toBuilder().completedSamples(1L).averageTraceNanos(9223372036854775807L).averagePostTraceNanos(1L).averageTotalNanos(9223372036854775807L).maxTotalNanos(9223372036854775807L).build());
      expect(IllegalArgumentException.class, () -> generation.toBuilder().configuredGeneratedFramesPerNativeFrame(3).build());
      expect(IllegalArgumentException.class, () -> generation.toBuilder().generatedFramesActuallyPresented(25L).build());
      expect(IllegalArgumentException.class, () -> generation.toBuilder().generationRequestMisses(11L).build());
      expect(IllegalArgumentException.class, () -> generation.toBuilder().stateQueryCalls(7L).build());
      expect(IllegalArgumentException.class, () -> generation.toBuilder().stateSamples(0L).build());
      expect(IllegalArgumentException.class, () -> generation.toBuilder().reported(false).build());
      RendererDiagnostics diagnostics = RendererDiagnostics.builder()
              .status(Status.READY)
              .latestAcceptedSceneRevision(3L)
              .latestSubmittedFrameSequence(8L)
              .latestCompletedFrameSequence(7L)
              .residentMeshes(2L)
              .residentInstances(4L)
              .deviceRecovery(DeviceRecovery.initial())
              .frameGpuTiming(timing)
              .frameGenerationEvidence(generation)
              .technologyExecutionEvidence(technologies)
              .build();
      require(diagnostics.latestCompletedFrameSequence() == 7L, "renderer diagnostics completion sequence changed");
      require(diagnostics.frameGenerationEvidence().equals(generation)
                      && diagnostics.technologyExecutionEvidence().equals(technologies)
                      && diagnostics.equals(diagnostics.toBuilder().build()),
              "renderer diagnostics lost structured execution evidence");
      require(!diagnostics.equals(diagnostics.toBuilder()
                      .technologyExecutionEvidence(TechnologyExecutionEvidence.disabled())
                      .build()),
              "renderer diagnostics value semantics ignored technology execution evidence");
      require(RendererDiagnostics.builder().status(Status.READY).build()
                      .technologyExecutionEvidence().equals(TechnologyExecutionEvidence.disabled()),
              "renderer diagnostics must default to complete disabled technology evidence");
      expect(NullPointerException.class, () -> diagnostics.toBuilder().technologyExecutionEvidence(null));
      expect(IllegalArgumentException.class, () -> diagnostics.toBuilder().latestSubmittedFrameSequence(7L).latestCompletedFrameSequence(8L).build());
      expect(IllegalArgumentException.class, () -> new RayTracingRenderer.SceneUpdateResult(-1L));
      expect(IllegalArgumentException.class, () -> FrameSubmissionResult.accepted(-1L, 0L, Set.of()));
      expect(IllegalArgumentException.class, () -> FrameSubmissionResult.accepted(0L, -1L, Set.of()));
      RayTracingRenderer.FrameSubmissionResult temporalAdmission = FrameSubmissionResult.accepted(9L, 3L, Set.of(HistoryInvalidationReason.CAMERA_CUT));
      require(temporalAdmission.historyInvalidations().equals(Set.of(HistoryInvalidationReason.CAMERA_CUT)), "frame admission lost effective temporal invalidation evidence");
      expect(UnsupportedOperationException.class, () -> temporalAdmission.historyInvalidations().clear());
      RendererHealth health = new RendererHealth(Status.READY, Optional.empty(), ResourceObligations.none());
      require(health.activeFailure().isEmpty(), "ready health unexpectedly reported a failure");
      expect(IllegalArgumentException.class, () -> new RendererHealth.ResourceObligations(-1, false, false));
      expect(IllegalArgumentException.class, () -> new RendererHealth.ResourceObligations(0, false, true));
      expect(IllegalArgumentException.class, () -> new RendererHealth(Status.FAILED, Optional.empty(), ResourceObligations.none()));
   }

   private static void assertGpuFrameDescriptorValidation() {
      require(ConsumerCompletionCapabilities.cpuOnly().cpuCompleted(), "CPU completion must remain available by default");
      require(!ConsumerCompletionCapabilities.cpuOnly().externalSemaphoreSignal(), "default leases must not claim unsupported semaphore completion");
      expect(IllegalArgumentException.class, () -> new GpuFrameLease.ConsumerCompletionCapabilities(false, Set.of()));
      GpuFrameLease.FrameDescriptor descriptor = FrameDescriptor.builder().resourceId(3L).frameSequence(8L).renderedSceneRevision(5L).extent(1920, 1080).format(new GpuFrameLease.VulkanFormat(37)).imageType(new GpuFrameLease.VulkanImageType(1)).imageTiling(new GpuFrameLease.VulkanImageTiling(1)).imageUsage(new GpuFrameLease.VulkanImageUsage(16)).imageCreateFlags(new GpuFrameLease.VulkanImageCreateFlags(0)).imageLayout(new GpuFrameLease.VulkanImageLayout(1)).mipLevels(1).arrayLayers(1).sampleCount(new GpuFrameLease.VulkanSampleCount(1)).sharingMode(new GpuFrameLease.VulkanSharingMode(0)).producerQueueFamily(new GpuFrameLease.VulkanQueueFamily(0)).memoryTypeIndex(0).allocationSize(8294400L).allocationOffset(0L).dedicatedAllocation(false).build();
      require(GpuFrameLease.FrameDescriptor.class.getConstructors().length == 0, "GPU frame descriptor exposed an ordered public constructor");
      require(descriptor.resourceId() == 3L && descriptor.frameSequence() == 8L, "GPU frame descriptor changed");
      require(descriptor.equals(descriptor.toBuilder().build()), "GPU frame descriptor copy lost resource identity");
      expect(IllegalArgumentException.class, () -> descriptor.toBuilder().resourceId(0L).build());
      expect(IllegalArgumentException.class, () -> descriptor.toBuilder().extent(0, 1080).build());
      expect(IllegalArgumentException.class, () -> new GpuFrameLease.ExternalSemaphoreSignal(1L, new GpuFrameLease.VulkanSemaphoreHandleType(2), SemaphoreKind.BINARY, 1L, ImportDisposition.IMPORT_CONSUMES_HANDLE));
   }

   private static void assertManagedPresenterContract() {
      VulkanFramePresenterConfig defaults = VulkanFramePresenterConfig.builder().build();
      require(defaults.initialWidth() == 1280 && defaults.initialHeight() == 720,
              "managed presenter default extent changed");
      require(defaults.presentMode() == VulkanFramePresenterConfig.PresentMode.VSYNC,
              "managed presenter safe default is no longer FIFO-oriented");
      require(defaults.windowMode() == VulkanFramePresenterConfig.WindowMode.WINDOWED,
              "presenter default window mode changed");
      require(defaults.maximumFramesQueuedAhead() == 2,
              "managed presenter default producer lead changed");
      VulkanFramePresenterConfig uncapped = VulkanFramePresenterConfig.builder()
              .title("contract")
              .initialExtent(2560, 1600)
              .resizable(false)
              .presentMode(VulkanFramePresenterConfig.PresentMode.UNCAPPED)
              .maximumFramesQueuedAhead(3)
              .build();
      require(uncapped.title().equals("contract") && !uncapped.resizable()
                      && uncapped.maximumFramesQueuedAhead() == 3,
              "managed presenter builder lost an independent policy");
      expect(IllegalArgumentException.class,
              () -> VulkanFramePresenterConfig.builder().maximumFramesQueuedAhead(0));
      expect(IllegalArgumentException.class,
              () -> VulkanFramePresenterConfig.builder().maximumFramesQueuedAhead(17));
      expect(IllegalArgumentException.class,
              () -> VulkanFramePresenterConfig.builder().initialExtent(0, 720));
      expect(IllegalArgumentException.class,
              () -> new VulkanFramePresenter.WindowState(false, 0, 720));
      expect(IllegalArgumentException.class,
              () -> new VulkanFramePresenter.PresentationResult(
                      -1L, 1, 1, VulkanFramePresenter.Outcome.PRESENTED));
      expect(UnsupportedOperationException.class,
              () -> VulkanFramePresenter.open(new TrackingRenderer(), defaults));
   }

   private static void assertExportedHandleLifecycle() {
      TrackingHandle imported = new TrackingHandle(ImportDisposition.IMPORT_CONSUMES_HANDLE);
      require(imported.markImported(), "first successful import did not transition handle state");
      require(!imported.markImported(), "native handle imported more than once");
      imported.close();
      require(imported.nativeCloses.get() == 0, "import-consumed handle was closed by exporter");
      TrackingHandle abandoned = new TrackingHandle(ImportDisposition.CALLER_RETAINS_HANDLE);
      abandoned.close();
      abandoned.close();
      require(abandoned.nativeCloses.get() == 1, "abandoned exported handle did not close exactly once");
      new GpuFrameLease.AcquireSignal(imported, SemaphoreKind.BINARY, 0L);
      expect(IllegalArgumentException.class, () -> new GpuFrameLease.AcquireSignal(abandoned, SemaphoreKind.TIMELINE, 0L));
   }

   private static void assertProviderSelection() {
      TrackingProvider unsupported = new TrackingProvider("unsupported", 100, ProbeResult.unsupported("missing feature"), false);
      TrackingProvider compatible = new TrackingProvider("vulkan", 10, ProbeResult.compatible("ready"), false);
      RayTracingRenderer selected = RendererBootstrap.openProviders((String)null, RendererPreset.CPU_READBACK.configuration(), List.of(compatible, unsupported));
      require(selected == compatible.renderer, "bootstrap did not select the compatible provider");
      require(unsupported.opens.get() == 0 && compatible.opens.get() == 1, "bootstrap opened an incompatible provider or opened twice");
      expect(RendererInitializationException.class, () -> RendererBootstrap.openProviders((String)null, RendererPreset.CPU_READBACK.configuration(), List.of(compatible, new TrackingProvider("vulkan", 1, ProbeResult.compatible("duplicate"), false))));
      TrackingProvider broken = new TrackingProvider("broken", 200, ProbeResult.compatible("probe ready"), true);
      RendererInitializationException initialization = (RendererInitializationException)expect(RendererInitializationException.class, () -> RendererBootstrap.openProviders((String)null, RendererPreset.CPU_READBACK.configuration(), List.of(compatible, broken)));
      require(initialization.providerId().equals("broken"), "initialization failure lost provider identity");
      require(compatible.opens.get() == 1, "bootstrap silently fell back after initialization failure");
      RendererUnavailableException unavailable = (RendererUnavailableException)expect(RendererUnavailableException.class, () -> RendererBootstrap.openProviders("missing", RendererPreset.CPU_READBACK.configuration(), List.of(compatible)));
      require(unavailable.attempts().isEmpty(), "explicit missing provider fabricated probe attempts");
      RendererUnavailableException.BackendAttempt attempt = BackendAttempt.of("vulkan-rt", Compatibility.UNSUPPORTED, "missing ray tracing support");
      require(RendererUnavailableException.BackendAttempt.class.getConstructors().length == 0 && attempt.compatibility() == Compatibility.UNSUPPORTED, "provider failure evidence exposed a positional or stringly compatibility surface");
   }

   private static CameraState camera() {
      return CameraState.explicitBasis(0.0, 0.0, 0.0).forward(0.0F, 0.0F, -1.0F).right(1.0F, 0.0F, 0.0F).up(0.0F, 1.0F, 0.0F).projectionTangents(1.0F, 0.5625F).build();
   }

   private static double[] transpose(double[] matrix) {
      double[] result = new double[16];
      for (int row = 0; row < 4; row++) for (int column = 0; column < 4; column++) {
         result[column * 4 + row] = matrix[row * 4 + column];
      }
      return result;
   }

   private static MaterialAsset material(long id) {
      return MaterialAsset.builder(id).blendMode(BlendMode.MASKED).baseColorRgba8(-1).alphaCutoff(0.5F).roughness(1.0F).doubleSided(true).build();
   }

   private static <T extends Throwable> T expect(Class<T> type, ThrowingRunnable action) {
      try {
         action.run();
      } catch (Throwable failure) {
         if (type.isInstance(failure)) {
            return (T)(type.cast(failure));
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

   private static final class TrackingHandle implements GpuFrameLease.ExportedNativeHandle<GpuFrameLease.VulkanSemaphoreHandleType> {
      private final GpuFrameLease.ImportDisposition disposition;
      private final AtomicInteger nativeCloses = new AtomicInteger();
      private GpuFrameLease.HandleState state;

      private TrackingHandle(GpuFrameLease.ImportDisposition disposition) {
         this.state = HandleState.EXPORTED;
         this.disposition = disposition;
      }

      public long value() {
         return 1L;
      }

      public GpuFrameLease.VulkanSemaphoreHandleType handleType() {
         return new GpuFrameLease.VulkanSemaphoreHandleType(2);
      }

      public GpuFrameLease.ImportDisposition importDisposition() {
         return this.disposition;
      }

      public GpuFrameLease.HandleState state() {
         return this.state;
      }

      public boolean markImported() {
         if (this.state != HandleState.EXPORTED) {
            return false;
         } else {
            this.state = HandleState.IMPORTED;
            if (this.disposition == ImportDisposition.CALLER_RETAINS_HANDLE) {
               this.nativeCloses.incrementAndGet();
            }

            return true;
         }
      }

      public void close() {
         if (this.state != HandleState.CLOSED) {
            if (this.state == HandleState.EXPORTED) {
               this.nativeCloses.incrementAndGet();
            }

            this.state = HandleState.CLOSED;
         }
      }
   }

   private static final class TrackingProvider implements RayTracingBackendProvider {
      private final RayTracingBackendProvider.Descriptor descriptor;
      private final RayTracingBackendProvider.ProbeResult probe;
      private final boolean failOpen;
      private final AtomicInteger opens = new AtomicInteger();
      private final RayTracingRenderer renderer = new TrackingRenderer();

      private TrackingProvider(String id, int priority, RayTracingBackendProvider.ProbeResult probe, boolean failOpen) {
         this.descriptor = Descriptor.builder(id).priority(priority).apiMajor(1).apiMinor(0).build();
         this.probe = probe;
         this.failOpen = failOpen;
      }

      public RayTracingBackendProvider.Descriptor descriptor() {
         return this.descriptor;
      }

      public List<RayTracingGpuDevice> availableGpuDevices() {
         return List.of();
      }

      public RayTracingBackendProvider.ProbeResult probe(RayTracingRendererConfig configuration) {
         return this.probe;
      }

      public RayTracingRenderer open(RayTracingRendererConfig configuration) {
         this.opens.incrementAndGet();
         if (this.failOpen) {
            throw new IllegalStateException("synthetic initialization failure");
         } else {
            return this.renderer;
         }
      }
   }

   private static final class TrackingRenderer implements RayTracingRenderer {
      public RayTracingRenderer.Status status() {
         return Status.READY;
      }

      public RendererHealth health() {
         return new RendererHealth(Status.READY, Optional.empty(), ResourceObligations.none());
      }

      public RayTracingRenderer.SceneUpdateResult apply(SceneTransaction transaction) {
         throw new UnsupportedOperationException();
      }

      public RayTracingRenderer.FrameSubmissionResult submit(RenderFrameRequest request) {
         throw new UnsupportedOperationException();
      }

      public RayTracingRenderer.FrameSubmissionAttempt trySubmit(RenderFrameRequest request) {
         throw new UnsupportedOperationException();
      }

      public Optional<CpuFrame> pollLatestCpuFrame() {
         return Optional.empty();
      }

      public RendererDiagnostics diagnostics() {
         throw new UnsupportedOperationException();
      }

      public <T> Optional<T> extension(Class<T> extensionType) {
         return Optional.empty();
      }

      public java.util.concurrent.CompletionStage<Void> closeAsync() {
         close();
         return java.util.concurrent.CompletableFuture.completedFuture(null);
      }

      public void close() {
      }
   }

   private static final class TrackingVulkanInterop implements VulkanFrameInterop {
      public VulkanFrameInterop.FramePollResult pollLatestFrame() {
         return FrameNotReady.INSTANCE;
      }
   }

   @FunctionalInterface
   private interface ThrowingRunnable {
      void run() throws Throwable;
   }
}
