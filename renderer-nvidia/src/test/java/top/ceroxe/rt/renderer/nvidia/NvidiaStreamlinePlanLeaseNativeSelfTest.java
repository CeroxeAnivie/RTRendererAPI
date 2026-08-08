package top.ceroxe.rt.renderer.nvidia;

import top.ceroxe.rt.renderer.api.DenoisingOptions;
import top.ceroxe.rt.renderer.api.FrameGenerationOptions;
import top.ceroxe.rt.renderer.api.FrameReconstructionOptions;
import top.ceroxe.rt.renderer.api.RayTracingOptimizationOptions;
import top.ceroxe.rt.renderer.api.RayTracingRendererConfig;
import top.ceroxe.rt.renderer.api.RendererFeaturePreference;
import top.ceroxe.rt.renderer.feature.VulkanFeaturePlan;
import top.ceroxe.rt.renderer.feature.VulkanFeatureRegistry;

/** Verifies that abandoned Streamline preflight plans release their process lease exactly once. */
public final class NvidiaStreamlinePlanLeaseNativeSelfTest {
    private NvidiaStreamlinePlanLeaseNativeSelfTest() {
    }

    public static void main(String[] arguments) {
        WindowsChildProcessIsolation.preventGradlePipeInheritance();
        RayTracingRendererConfig configuration = RayTracingRendererConfig.expertBuilder()
                .frameReconstruction(FrameReconstructionOptions.builder()
                        .preference(RendererFeaturePreference.PREFERRED)
                        .build())
                .frameGeneration(FrameGenerationOptions.disabled())
                .denoising(DenoisingOptions.disabled())
                .rayTracingOptimizations(RayTracingOptimizationOptions.disabled())
                .build();
        for (int iteration = 0; iteration < 2; iteration++) {
            try (VulkanFeaturePlan plan = VulkanFeatureRegistry.plan(configuration)) {
                require(!plan.providers().isEmpty(), "Streamline plan did not select its provider");
            }
        }
        System.out.println("NvidiaStreamlinePlanLeaseNativeSelfTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
