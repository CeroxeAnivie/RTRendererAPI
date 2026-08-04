package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Entry;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Feature;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Status;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Technology;

/** Pure contract checks for feature activation without requiring a Vulkan device. */
public final class VulkanGpuSceneFeatureCompositionSelfTest {
    private VulkanGpuSceneFeatureCompositionSelfTest() {
    }

    public static void main(String[] args) {
        selectsOnlyExecutableGpuImplementations();
        disabledFeaturesCannotResurrectWithinOneSession();
        missingDepthProjectionDisablesOnlyTheCurrentFrame();
        selectsPublicationOnlyForSdrReconstruction();
        System.out.println("VulkanGpuSceneFeatureCompositionSelfTest passed");
    }

    private static void selectsPublicationOnlyForSdrReconstruction() {
        VulkanGpuSceneFeatureComposition.Selection reconstruction =
                new VulkanGpuSceneFeatureComposition.Selection(false, true, true);
        require(VulkanGpuSceneFeatureComposition.publicationRequired(false, reconstruction),
                "SDR reconstruction requires private HDR publication");
        require(!VulkanGpuSceneFeatureComposition.publicationRequired(true, reconstruction),
                "public HDR reconstruction must remain a direct path");
        require(!VulkanGpuSceneFeatureComposition.publicationRequired(
                        false, VulkanGpuSceneFeatureComposition.Selection.disabled()),
                "SDR without reconstruction must not allocate a publication pipeline");
    }

    private static void selectsOnlyExecutableGpuImplementations() {
        RenderingFeatureCapabilities capabilities = RenderingFeatureCapabilities.builder()
                .feature(Feature.DENOISING, Entry.of(Status.ACTIVE, "nvidia.nrd", "executed"))
                .feature(Feature.FRAME_RECONSTRUCTION,
                        Entry.of(Status.FALLBACK, "builtin.temporal", "vendor evaluate failed"))
                .feature(Feature.FRAME_GENERATION,
                        Entry.of(Status.FALLBACK, "native.presentation", "proxy present failed"))
                .build();
        VulkanGpuSceneFeatureComposition.Selection selected =
                VulkanGpuSceneFeatureComposition.select(capabilities);
        require(selected.denoising(), "active NRD must remain executable");
        require(!selected.reconstruction(), "built-in temporal fallback must release vendor resources");
        require(!selected.frameGeneration(), "native presentation fallback must release FG resources");

        RenderingFeatureCapabilities vendorFallback = RenderingFeatureCapabilities.builder()
                .feature(Feature.FRAME_RECONSTRUCTION,
                        Entry.of(Status.FALLBACK, "nvidia.streamline.nis", "DLSS unavailable"))
                .technology(Technology.SPATIAL_UPSCALING,
                        Entry.of(Status.FALLBACK, "nvidia.streamline.nis", "DLSS unavailable"))
                .build();
        VulkanGpuSceneFeatureComposition.Selection spatial =
                VulkanGpuSceneFeatureComposition.select(vendorFallback);
        require(spatial.reconstruction() && !spatial.temporalReconstruction(),
                "NIS fallback still consumes reconstruction resources");

        RenderingFeatureCapabilities dlss = RenderingFeatureCapabilities.builder()
                .feature(Feature.FRAME_RECONSTRUCTION,
                        Entry.of(Status.ACTIVE, "nvidia.streamline.dlss", "executed"))
                .technology(Technology.TEMPORAL_SUPER_RESOLUTION,
                        Entry.of(Status.ACTIVE, "nvidia.streamline.dlss", "executed"))
                .build();
        require(VulkanGpuSceneFeatureComposition.select(dlss).temporalReconstruction(),
                "active DLSS must request vendor temporal jitter and provenance");
    }

    private static void disabledFeaturesCannotResurrectWithinOneSession() {
        VulkanGpuSceneFeatureComposition.Selection all =
                new VulkanGpuSceneFeatureComposition.Selection(true, true, true);
        VulkanGpuSceneFeatureComposition.Selection failed =
                all.retain(new VulkanGpuSceneFeatureComposition.Selection(false, true, false));
        require(!failed.denoising() && failed.reconstruction() && !failed.frameGeneration(),
                "runtime failure must disable exactly the failed resource families");
        VulkanGpuSceneFeatureComposition.Selection staleActive =
                failed.retain(new VulkanGpuSceneFeatureComposition.Selection(true, true, true));
        require(staleActive.equals(failed),
                "a stale capability snapshot must not resurrect resources without a new session");
    }

    private static void missingDepthProjectionDisablesOnlyTheCurrentFrame() {
        VulkanGpuSceneFeatureComposition.Selection session =
                new VulkanGpuSceneFeatureComposition.Selection(true, true, true);
        require(session.forTemporalContract(false).equals(
                        VulkanGpuSceneFeatureComposition.Selection.disabled()),
                "missing exact projection must disable every temporal feature for that frame");
        VulkanGpuSceneFeatureComposition.Selection spatial =
                new VulkanGpuSceneFeatureComposition.Selection(true, true, true, false);
        require(spatial.forTemporalContract(false).equals(
                        new VulkanGpuSceneFeatureComposition.Selection(false, true, false, false)),
                "missing exact projection must preserve color-only spatial reconstruction");
        require(session.forTemporalContract(true).equals(session),
                "a later exact projection must restore the unchanged session selection");
        require(session.equals(new VulkanGpuSceneFeatureComposition.Selection(true, true, true)),
                "frame-local fallback must not mutate the session selection");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
