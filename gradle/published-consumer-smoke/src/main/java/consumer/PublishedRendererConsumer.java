package consumer;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import top.ceroxe.rt.renderer.api.AntiAliasingState;
import top.ceroxe.rt.renderer.api.CameraState;
import top.ceroxe.rt.renderer.api.CpuFrame;
import top.ceroxe.rt.renderer.api.FrameOutputFormat;
import top.ceroxe.rt.renderer.api.HardwareCapabilities;
import top.ceroxe.rt.renderer.api.HistoryResetReason;
import top.ceroxe.rt.renderer.api.MaterialAsset;
import top.ceroxe.rt.renderer.api.MeshAsset;
import top.ceroxe.rt.renderer.api.RayTracingGpuDevice;
import top.ceroxe.rt.renderer.api.RayTracingRenderer;
import top.ceroxe.rt.renderer.api.RayTracingRendererConfig;
import top.ceroxe.rt.renderer.api.RendererBootstrap;
import top.ceroxe.rt.renderer.api.RendererFeatureApplyResult;
import top.ceroxe.rt.renderer.api.RendererFeatureController;
import top.ceroxe.rt.renderer.api.RendererFeaturePlan;
import top.ceroxe.rt.renderer.api.RendererFeatureProfile;
import top.ceroxe.rt.renderer.api.RendererHealth;
import top.ceroxe.rt.renderer.api.RendererPreset;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.SceneInstance;
import top.ceroxe.rt.renderer.api.SceneLight;
import top.ceroxe.rt.renderer.api.SceneTransaction;
import top.ceroxe.rt.renderer.api.TextureAsset;
import top.ceroxe.rt.renderer.api.TemporalRenderingOptions;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFrameInterop;

/**
 * Compile-only proof that a clean host can enumerate devices, select one stable identity, and open
 * the published backend without relying on this repository's project classpath.
 */
public final class PublishedRendererConsumer {
    private PublishedRendererConsumer() {
    }

    public static List<RayTracingGpuDevice> enumerateRayTracingDevices() {
        return RendererBootstrap.availableGpuDevices();
    }

    public static RayTracingRendererConfig selectDevice(String backendId, String stableId) {
        Objects.requireNonNull(backendId, "backendId");
        Objects.requireNonNull(stableId, "stableId");
        RayTracingGpuDevice selected = enumerateRayTracingDevices().stream()
                .filter(device -> backendId.equals(device.backendId()) && stableId.equals(device.stableId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown RT GPU: " + backendId + "/" + stableId
                ));
        return RendererPreset.CPU_READBACK.configuration().copyBuilder().gpuDevice(selected).build();
    }

    public static RayTracingRendererConfig selectHighestMemoryDiscreteDevice() {
        RayTracingGpuDevice selected = enumerateRayTracingDevices().stream()
                .filter(device -> device.type() == RayTracingGpuDevice.Type.DISCRETE)
                .max(Comparator.comparingLong(
                        device -> device.hardwareCapabilities().deviceLocalMemoryBytes()
                ))
                .orElseThrow(() -> new IllegalStateException("No discrete hardware RT GPU available"));
        return RendererPreset.CPU_READBACK.configuration().copyBuilder().gpuDevice(selected).build();
    }

    public static HardwareCapabilities hardwareCapabilities(RayTracingGpuDevice device) {
        return Objects.requireNonNull(device, "device").hardwareCapabilities();
    }

    public static RayTracingRendererConfig selectLinearHdrOutput(RayTracingRendererConfig configuration) {
        return Objects.requireNonNull(configuration, "configuration").copyBuilder()
                .frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F)
                .build();
    }

    public static RayTracingRenderer open(String backendId, String stableId) {
        return RendererBootstrap.openExpert(selectDevice(backendId, stableId));
    }

    /**
     * Compile-time proof of the Vulkan-free beginner scene path using only safe factories and
     * semantic builders.
     *
     * @param renderer open renderer instance
     * @param revision strictly increasing scene revision
     * @param width    positive texture and camera viewport width
     * @param height   positive texture and camera viewport height
     * @param rgba8    exactly {@code width * height * 4} color bytes
     * @return the accepted scene revision
     */
    public static long publishBeginnerScene(
            RayTracingRenderer renderer,
            long revision,
            int width,
            int height,
            byte[] rgba8
    ) {
        Objects.requireNonNull(renderer, "renderer");
        TextureAsset texture = TextureAsset.color(1L, width, height, rgba8);
        MaterialAsset material = MaterialAsset.builder(2L)
                .baseColorTexture(texture)
                .roughness(0.7F)
                .build();
        MeshAsset mesh = MeshAsset.triangles(
                3L,
                new float[]{0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F},
                new int[]{0, 1, 2},
                material.id()
        );
        SceneInstance instance = SceneInstance.builder(4L, mesh.id()).build();
        SceneLight light = SceneLight.directional(5L, -0.4F, -1.0F, -0.2F)
                .intensity(3.0F)
                .build();
        return renderer.apply(SceneTransaction.builder(revision)
                .upsert(texture)
                .upsert(material)
                .upsert(mesh)
                .upsert(instance)
                .upsert(light)
                .build()).acceptedSceneRevision();
    }

    public static CameraState beginnerCamera(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("viewport must be positive");
        return CameraState.lookAt(0.0D, 2.0D, 5.0D, 0.0D, 1.0D, 0.0D)
                .aspectRatio((double) width / height)
                .build();
    }

    public static Optional<CpuFrame> pollManagedFrame(RayTracingRenderer renderer) {
        return Objects.requireNonNull(renderer, "renderer").pollLatestCpuFrame();
    }

    public static Optional<VulkanFrameInterop> vulkanInterop(RayTracingRenderer renderer) {
        return Objects.requireNonNull(renderer, "renderer").extension(VulkanFrameInterop.class);
    }

    public static RendererHealth health(RayTracingRenderer renderer) {
        return Objects.requireNonNull(renderer, "renderer").health();
    }

    public static RendererFeaturePlan planFeatures(
            RayTracingRenderer renderer,
            RendererFeatureProfile target
    ) {
        RendererFeatureController controller = Objects.requireNonNull(renderer, "renderer")
                .extension(RendererFeatureController.class)
                .orElseThrow(() -> new IllegalStateException("runtime feature control unavailable"));
        return controller.plan(Objects.requireNonNull(target, "target"));
    }

    public static RendererFeatureApplyResult applyInSessionPlan(
            RayTracingRenderer renderer,
            RendererFeaturePlan plan
    ) {
        Objects.requireNonNull(plan, "plan");
        if (plan.disposition() != RendererFeaturePlan.Disposition.APPLICABLE
                && plan.disposition() != RendererFeaturePlan.Disposition.UNCHANGED) {
            throw new IllegalArgumentException(
                    "plan requires an application-owned rebuild: " + plan.boundary()
            );
        }
        RendererFeatureController controller = Objects.requireNonNull(renderer, "renderer")
                .extension(RendererFeatureController.class)
                .orElseThrow(() -> new IllegalStateException("runtime feature control unavailable"));
        return controller.apply(plan);
    }

    public static RenderFrameRequest withDeterministicAntiAliasing(
            RenderFrameRequest request,
            int samplesPerPixel
    ) {
        return Objects.requireNonNull(request, "request").toBuilder()
                .antiAliasing(AntiAliasingState.multisampled(samplesPerPixel))
                .build();
    }

    public static RayTracingRendererConfig accumulatingTemporalHistory(int maxHistoryFrames) {
        return RayTracingRendererConfig.expertBuilder()
                .temporalRendering(TemporalRenderingOptions.accumulating(maxHistoryFrames))
                .build();
    }

    public static RenderFrameRequest cameraCut(RenderFrameRequest request) {
        return Objects.requireNonNull(request, "request").toBuilder()
                .resetTemporalHistory(HistoryResetReason.CAMERA_CUT)
                .build();
    }
}
