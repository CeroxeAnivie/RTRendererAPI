package top.ceroxe.rt.renderer.nvidia;

import top.ceroxe.rt.renderer.feature.VulkanDenoisingResourceContract;
import top.ceroxe.rt.renderer.feature.VulkanFeatureFrameContext;

import java.util.Objects;

/** Translates renderer-owned frame resources into the narrow NRD JNI contract. */
final class NvidiaNrdRuntime {
    private NvidiaNrdRuntime() {
    }

    static void recordPostTrace(long session, VulkanFeatureFrameContext context) {
        if (session == 0L) throw new IllegalArgumentException("native NVIDIA session handle must not be zero");
        VulkanFeatureFrameContext checked = Objects.requireNonNull(context, "context");
        VulkanDenoisingResourceContract denoising = checked.denoisingResources().orElse(null);
        NrdFrameConstants constants = denoising == null
                ? null
                : NrdFrameConstants.from(
                        checked.temporalInput().orElseThrow(() -> new IllegalArgumentException(
                                "NRD requires temporal frame input"
                        )),
                        checked.extents().renderWidth(),
                        checked.extents().renderHeight(),
                        checked.historyReset()
                );
        NvidiaNativeBridge.nativeRecordPostTrace(
                session,
                checked.commandBuffer().address(),
                checked.traceOutput().image(),
                checked.publishedOutput().image(),
                checked.temporalResources().geometryOutput().image(),
                checked.temporalResources().motionOutput().image(),
                denoising == null ? 0L : denoising.normalRoughness().handle(),
                denoising == null ? 0L : denoising.viewZ().handle(),
                denoising == null ? 0L : denoising.motionVectors().handle(),
                denoising == null ? 0L : denoising.diffuseRadianceHitDistance().handle(),
                denoising == null ? 0L : denoising.specularRadianceHitDistance().handle(),
                denoising == null ? 0L : denoising.denoisedDiffuseRadianceHitDistance().handle(),
                denoising == null ? 0L : denoising.denoisedSpecularRadianceHitDistance().handle(),
                denoising != null,
                checked.extents().renderWidth(),
                checked.extents().renderHeight(),
                constants
        );
    }
}
