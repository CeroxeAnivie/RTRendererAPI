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
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import top.ceroxe.rt.renderer.api.EnvironmentState.Medium;
import top.ceroxe.rt.renderer.api.MaterialAsset.BlendMode;
import top.ceroxe.rt.renderer.api.MaterialAsset.ShadingModel;
import top.ceroxe.rt.renderer.api.RayTracingGpuDevice.Capability;
import top.ceroxe.rt.renderer.api.RayTracingGpuDevice.RayTracingLimits;
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
      assertGpuDeviceSelectionContract();
      assertTransformAndLightingValidation();
      assertCameraAndFrameValidation();
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
      assertExportedHandleLifecycle();
      assertProviderSelection();
      System.out.println("RendererApiContractSelfTest passed");
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
      require(RayTracingRendererConfig.defaults().maxFramesInFlight() == 3, "default frame ring changed");
      require(RayTracingRendererConfig.defaults().cpuFrameReadbackEnabled(), "managed CPU readback must remain enabled by default");
      require(RayTracingRendererConfig.defaults().frameOutputFormat() == FrameOutputFormat.SDR_RGBA8, "default native output must be SDR RGBA8");
      require(RayTracingRendererConfig.defaults().temporalRendering().equals(TemporalRenderingOptions.balanced()), "production defaults must enable balanced temporal reconstruction");
      RayTracingRendererConfig tuned = RayTracingRendererConfig.defaults().toBuilder().maxFramesInFlight(4).validationEnabled(true).gpuTimingsEnabled(false).cpuFrameReadbackEnabled(false).frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F).temporalRendering(TemporalRenderingOptions.accumulating(16)).build();
      require(tuned.maxFramesInFlight() == 4 && tuned.validationEnabled() && !tuned.gpuTimingsEnabled() && !tuned.cpuFrameReadbackEnabled() && tuned.frameOutputFormat() == FrameOutputFormat.LINEAR_HDR_RGBA16F && tuned.temporalRendering().maxHistoryFrames() == 16, "configuration builder lost an independent policy value");
      expect(IllegalArgumentException.class, () -> RayTracingRendererConfig.builder().maxFramesInFlight(1).build());
      expect(IllegalArgumentException.class, () -> RayTracingRendererConfig.builder().maxFramesInFlight(17).build());
      expect(NullPointerException.class, () -> RayTracingRendererConfig.builder().frameOutputFormat((FrameOutputFormat)null));
      expect(NullPointerException.class, () -> RayTracingRendererConfig.builder().temporalRendering((TemporalRenderingOptions)null));
      require(RayTracingBackendProvider.Descriptor.class.getConstructors().length == 0, "provider descriptor exposed an ordered public constructor");
      expect(IllegalArgumentException.class, () -> Descriptor.builder(" ").build());
      expect(IllegalArgumentException.class, () -> Descriptor.builder("vulkan").apiMajor(0).build());
      expect(IllegalArgumentException.class, () -> Descriptor.builder("vulkan").apiMinor(-1).build());
      expect(IllegalArgumentException.class, () -> ProbeResult.compatible(" "));
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
      TrackingRenderer renderer = new TrackingRenderer();
      require(renderer.extension(VulkanFrameInterop.class).isEmpty(), "ordinary renderer fabricated Vulkan interoperability support");
   }

   private static void assertGpuDeviceSelectionContract() {
      RayTracingGpuDevice device = gpuDevice("vulkan", "00112233445566778899aabbccddeeff");
      RayTracingRendererConfig selected = RayTracingRendererConfig.defaults().toBuilder().gpuDevice(device).build();
      require(((RayTracingGpuDevice)selected.gpuDevice().orElseThrow()).equals(device), "configuration lost selected GPU identity");
      require(RayTracingGpuDevice.class.getConstructors().length == 0, "GPU device exposed an ordered public constructor");
      require(RayTracingGpuDevice.RayTracingLimits.class.getConstructors().length == 0, "ray-tracing limits exposed an ordered public constructor");
      require(device.equals(device.toBuilder().build()), "GPU device toBuilder changed structural value semantics");
      expect(NullPointerException.class, () -> RayTracingRendererConfig.builder().gpuDevice((RayTracingGpuDevice)null));
      expect(NullPointerException.class, () -> RayTracingGpuDevice.builder().backendId((String)null));
      expect(IllegalStateException.class, () -> RayTracingLimits.builder().maxRayRecursionDepth(1).build());
      expect(IllegalArgumentException.class, () -> device.toBuilder().capabilities(Set.of()).build());
      TrackingProvider wrongBackend = new TrackingProvider("other", 1000, ProbeResult.compatible("ready"), false);
      TrackingProvider selectedBackend = new TrackingProvider("vulkan", 1, ProbeResult.compatible("ready"), false);
      RayTracingRenderer renderer = RendererBootstrap.openProviders((String)null, selected, List.of(wrongBackend, selectedBackend));
      require(renderer == selectedBackend.renderer, "selected GPU did not route to its owning backend");
      require(wrongBackend.opens.get() == 0, "bootstrap opened a backend that does not own the selected GPU");
      expect(IllegalArgumentException.class, () -> RendererBootstrap.openProviders("other", selected, List.of(wrongBackend, selectedBackend)));
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

   private static RayTracingGpuDevice gpuDevice(String backendId, String stableId) {
      RayTracingGpuDevice.RayTracingLimits limits = RayTracingLimits.builder().maxRayRecursionDepth(31).shaderGroupHandleSize(32).shaderGroupHandleAlignment(32).shaderGroupBaseAlignment(64).maxShaderGroupStride(4096).maxRayDispatchInvocationCount(1073741824L).minAccelerationStructureScratchAlignment(256).build();
      return RayTracingGpuDevice.builder().backendId(backendId).stableId(stableId).name("Contract GPU").vendorId(4318).deviceId(1).type(Type.DISCRETE).apiVersion(new RayTracingGpuDevice.ApiVersion(1, 3, 0)).deviceLocalMemoryBytes(8589934592L).capabilities(Set.of(Capability.HARDWARE_RAY_TRACING, Capability.ACCELERATION_STRUCTURE, Capability.RAY_TRACING_PIPELINE)).rayTracingLimits(limits).build();
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
      require(RendererDiagnostics.class.getConstructors().length == 0 && RendererDiagnostics.FrameGpuTiming.class.getConstructors().length == 0, "diagnostics types exposed ordered public constructors");
      require(timing.averageTotalNanos() == 425L, "GPU total timing changed");
      expect(IllegalArgumentException.class, () -> timing.toBuilder().averageTotalNanos(420L).build());
      expect(IllegalArgumentException.class, () -> timing.toBuilder().enabled(false).build());
      expect(IllegalArgumentException.class, () -> timing.toBuilder().completedSamples(1L).averageTraceNanos(9223372036854775807L).averagePostTraceNanos(1L).averageTotalNanos(9223372036854775807L).maxTotalNanos(9223372036854775807L).build());
      RendererDiagnostics diagnostics = RendererDiagnostics.builder().status(Status.READY).latestAcceptedSceneRevision(3L).latestSubmittedFrameSequence(8L).latestCompletedFrameSequence(7L).residentMeshes(2L).residentInstances(4L).deviceRecovery(DeviceRecovery.initial()).frameGpuTiming(timing).build();
      require(diagnostics.latestCompletedFrameSequence() == 7L, "renderer diagnostics completion sequence changed");
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
      RayTracingRenderer selected = RendererBootstrap.openProviders((String)null, RayTracingRendererConfig.defaults(), List.of(compatible, unsupported));
      require(selected == compatible.renderer, "bootstrap did not select the compatible provider");
      require(unsupported.opens.get() == 0 && compatible.opens.get() == 1, "bootstrap opened an incompatible provider or opened twice");
      expect(RendererInitializationException.class, () -> RendererBootstrap.openProviders((String)null, RayTracingRendererConfig.defaults(), List.of(compatible, new TrackingProvider("vulkan", 1, ProbeResult.compatible("duplicate"), false))));
      TrackingProvider broken = new TrackingProvider("broken", 200, ProbeResult.compatible("probe ready"), true);
      RendererInitializationException initialization = (RendererInitializationException)expect(RendererInitializationException.class, () -> RendererBootstrap.openProviders((String)null, RayTracingRendererConfig.defaults(), List.of(compatible, broken)));
      require(initialization.providerId().equals("broken"), "initialization failure lost provider identity");
      require(compatible.opens.get() == 1, "bootstrap silently fell back after initialization failure");
      RendererUnavailableException unavailable = (RendererUnavailableException)expect(RendererUnavailableException.class, () -> RendererBootstrap.openProviders("missing", RayTracingRendererConfig.defaults(), List.of(compatible)));
      require(unavailable.attempts().isEmpty(), "explicit missing provider fabricated probe attempts");
      RendererUnavailableException.BackendAttempt attempt = BackendAttempt.of("vulkan-rt", Compatibility.UNSUPPORTED, "missing ray tracing support");
      require(RendererUnavailableException.BackendAttempt.class.getConstructors().length == 0 && attempt.compatibility() == Compatibility.UNSUPPORTED, "provider failure evidence exposed a positional or stringly compatibility surface");
   }

   private static CameraState camera() {
      return CameraState.explicitBasis(0.0, 0.0, 0.0).forward(0.0F, 0.0F, -1.0F).right(1.0F, 0.0F, 0.0F).up(0.0F, 1.0F, 0.0F).projectionTangents(1.0F, 0.5625F).build();
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

      public Optional<CpuFrame> pollLatestCpuFrame() {
         return Optional.empty();
      }

      public RendererDiagnostics diagnostics() {
         throw new UnsupportedOperationException();
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
