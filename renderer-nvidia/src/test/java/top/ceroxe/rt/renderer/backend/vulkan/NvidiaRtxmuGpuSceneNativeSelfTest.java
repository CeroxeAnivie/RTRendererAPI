package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.api.DenoisingOptions;
import top.ceroxe.rt.renderer.api.FrameGenerationOptions;
import top.ceroxe.rt.renderer.api.FrameReconstructionOptions;
import top.ceroxe.rt.renderer.api.RayTracingOptimizationOptions;
import top.ceroxe.rt.renderer.api.RayTracingRendererConfig;
import top.ceroxe.rt.renderer.api.RendererFeaturePreference;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Feature;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Status;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

/** Real-device gate for RTXMU-owned GPUScene BLAS build, compaction, and release. */
public final class NvidiaRtxmuGpuSceneNativeSelfTest {
    private NvidiaRtxmuGpuSceneNativeSelfTest() {
    }

    public static void main(String[] arguments) throws Exception {
        VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
        NvidiaGpuSceneNativeTestSupport.require(
                capability.hardwareRayTracingReady(),
                "RTXMU GPUScene gate requires Vulkan RT: " + capability.summary()
        );
        RayTracingRendererConfig configuration = RayTracingRendererConfig.builder()
                .validationEnabled(true)
                .frameReconstruction(FrameReconstructionOptions.disabled())
                .frameGeneration(FrameGenerationOptions.disabled())
                .denoising(DenoisingOptions.disabled())
                .rayTracingOptimizations(RayTracingOptimizationOptions.builder()
                        .memoryOptimization(RendererFeaturePreference.REQUIRED)
                        .build())
                .build();

        try (VulkanGpuSceneRenderingSession session = VulkanGpuSceneRenderingSession.open(
                capability, configuration, RendererRtDiagnostics.noop()
        ); VulkanRendererHost renderer = new VulkanRendererHost(configuration, session)) {
            Status beforeBuild = session.featureCapabilities().feature(Feature.MEMORY_OPTIMIZATION).status();
            NvidiaGpuSceneNativeTestSupport.require(
                    beforeBuild == Status.AVAILABLE,
                    "RTXMU must remain AVAILABLE before a managed BLAS completes: " + beforeBuild
            );
            verifyConcurrentDeviceSessionRejected(capability, configuration);
            renderer.apply(NvidiaGpuSceneNativeTestSupport.scene());
            NvidiaGpuSceneNativeTestSupport.awaitFrameAdmission(
                    renderer, NvidiaGpuSceneNativeTestSupport.frame(), "RTXMU"
            );
            VulkanGpuSceneRenderingSession.DiagnosticFrame completed =
                    NvidiaGpuSceneNativeTestSupport.awaitCompletedFrame(session, "RTXMU");
            long nonBlackPixels = NvidiaGpuSceneNativeTestSupport.nonBlackPixels(completed.rgba8());
            NvidiaGpuSceneNativeTestSupport.require(
                    nonBlackPixels > completed.width() * completed.height() / 8L,
                    "RTXMU-managed scene did not preserve visible ray-traced output"
            );
            Status afterBuild = session.featureCapabilities().feature(Feature.MEMORY_OPTIMIZATION).status();
            NvidiaGpuSceneNativeTestSupport.require(
                    afterBuild == Status.ACTIVE,
                    "RTXMU did not publish completed build/compaction evidence: before="
                            + beforeBuild + ", after=" + afterBuild
            );
            System.out.println(
                    "NvidiaRtxmuGpuSceneNativeSelfTest passed: device="
                            + capability.preferredDevice().name() + ", before=" + beforeBuild
                            + ", after=" + afterBuild + ", nonBlackPixels=" + nonBlackPixels
            );
        }
    }

    private static void verifyConcurrentDeviceSessionRejected(
            VulkanRtCapabilityProbe.Result capability,
            RayTracingRendererConfig configuration
    ) {
        try (VulkanDeviceRuntime ignored = VulkanDeviceRuntime.open(
                capability, RendererRtDiagnostics.noop(), false, false, configuration
        )) {
            throw new AssertionError("RTXMU accepted concurrent process-static allocator ownership");
        } catch (RuntimeException expected) {
            NvidiaGpuSceneNativeTestSupport.require(
                    messageChain(expected).contains("only one RTXMU device session may be active"),
                    "concurrent RTXMU rejection lost its root-cause diagnostic: " + expected
            );
        }
    }

    private static String messageChain(Throwable failure) {
        StringBuilder result = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null) result.append(current.getMessage()).append('\n');
        }
        return result.toString();
    }
}
