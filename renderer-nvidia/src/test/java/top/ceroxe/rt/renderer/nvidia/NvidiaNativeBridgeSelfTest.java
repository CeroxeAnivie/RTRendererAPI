package top.ceroxe.rt.renderer.nvidia;

import top.ceroxe.rt.renderer.api.DenoisingOptions;
import top.ceroxe.rt.renderer.api.FrameGenerationOptions;
import top.ceroxe.rt.renderer.api.FrameReconstructionOptions;
import top.ceroxe.rt.renderer.api.RendererConfig;
import top.ceroxe.rt.renderer.api.RendererFeaturePreference;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Feature;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Status;

import java.util.Set;

/** Verifies that the compiled JNI bridge advertises only the capability it can open. */
public final class NvidiaNativeBridgeSelfTest {
    private NvidiaNativeBridgeSelfTest() {
    }

    public static void main(String[] args) {
        WindowsChildProcessIsolation.preventGradlePipeInheritance();
        NvidiaNativeBridge.Probe probe = NvidiaNativeBridge.probe();
        require(probe.loaded(), "NVIDIA JNI bridge did not load: " + probe.reason());
        require(probe.capabilityMask() == (NvidiaNativeBridge.NRD | NvidiaNativeBridge.RTX_MEMORY_UTILITY),
                "bridge capability mask must match the NRD and RTXMU native open implementations");
        require(probe.supports(NvidiaNativeBridge.NRD), "compiled bridge must support NRD");
        require(probe.supports(NvidiaNativeBridge.RTX_MEMORY_UTILITY),
                "compiled bridge must support pinned RTXMU v1.4");
        require(!probe.supports(NvidiaNativeBridge.DLSS)
                        && !probe.supports(NvidiaNativeBridge.DLAA)
                        && !probe.supports(NvidiaNativeBridge.NIS),
                "bridge must not advertise Streamline paths through the native capability mask");
        require(probe.reason().contains("REBLUR_DIFFUSE_SPECULAR")
                        && probe.reason().contains("RTXMU v1.4"),
                "native diagnostic must identify both advertised implementations");
        verifiesOfficialStreamlineProviderRequirements();
        System.out.println("NvidiaNativeBridgeSelfTest passed: " + probe.reason());
    }

    private static void verifiesOfficialStreamlineProviderRequirements() {
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
        NvidiaVulkanFeatureProvider provider = new NvidiaVulkanFeatureProvider();
        try {
            NvidiaStreamlineRuntime.Preflight preflight = NvidiaStreamlineRuntime.preflight(
                    Set.of(NvidiaStreamlineRuntime.Feature.DLSS)
            );
            require(preflight.ready(), "official Streamline preflight failed: " + preflight.reason());
            NvidiaStreamlineRuntime.Requirements requirements = preflight.requirements()
                    .get(NvidiaStreamlineRuntime.Feature.DLSS);
            require(requirements != null,
                    "successful Streamline preflight must return DLSS requirements");
            var entry = provider.requirements(configuration).support()
                    .get(Feature.FRAME_RECONSTRUCTION);
            require(entry != null && entry.status() == Status.AVAILABLE,
                    "official Streamline provider did not expose DLSS reconstruction: " + entry);
            require(NvidiaNativeBridge.probe().capabilityMask()
                            == (NvidiaNativeBridge.NRD | NvidiaNativeBridge.RTX_MEMORY_UTILITY),
                    "Streamline preflight must not advertise DLSS before its execution path exists");
        } finally {
            provider.discardPlan();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
