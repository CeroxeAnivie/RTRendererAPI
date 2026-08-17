package top.ceroxe.rt.renderer.nvidia;

import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.api.DenoisingOptions;
import top.ceroxe.rt.renderer.api.FrameReconstructionOptions;
import top.ceroxe.rt.renderer.api.FrameGenerationOptions;
import top.ceroxe.rt.renderer.api.RendererConfig;
import top.ceroxe.rt.renderer.api.RendererFeaturePreference;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Feature;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Status;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

/** Verifies that Streamline preflight survives through creation of the exact Vulkan RT device. */
public final class NvidiaStreamlineDeviceHandoffNativeSelfTest {
    private NvidiaStreamlineDeviceHandoffNativeSelfTest() {
    }

    public static void main(String[] arguments) {
        WindowsChildProcessIsolation.preventGradlePipeInheritance();
        VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
        require(capability.hardwareRayTracingReady(), "Streamline device gate requires RT hardware: " + capability.summary());
        RendererConfig configuration = RendererConfig.expertBuilder()
                .frameReconstruction(FrameReconstructionOptions.builder()
                        .preference(RendererFeaturePreference.PREFERRED)
                        .mode(FrameReconstructionOptions.Mode.SUPER_RESOLUTION)
                        .quality(FrameReconstructionOptions.Quality.BALANCED)
                        .fallback(FrameReconstructionOptions.Fallback.NONE)
                        .build())
                .frameGeneration(FrameGenerationOptions.disabled())
                .denoising(DenoisingOptions.disabled())
                .build();
        try (VulkanDeviceRuntime runtime = VulkanDeviceRuntime.open(
                capability, RendererRtDiagnostics.noop(), false, false, configuration
        )) {
            var reconstruction = runtime.featureCapabilities().feature(Feature.FRAME_RECONSTRUCTION);
            Status status = reconstruction.status();
            require(status == Status.AVAILABLE,
                    "Streamline device handoff must arm, not prematurely activate, reconstruction: "
                            + status + ", implementation=" + reconstruction.implementation()
                            + ", reason=" + reconstruction.reason());
            System.out.println("NvidiaStreamlineDeviceHandoffNativeSelfTest passed: device="
                    + capability.preferredDevice().name() + ", status=" + status);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
