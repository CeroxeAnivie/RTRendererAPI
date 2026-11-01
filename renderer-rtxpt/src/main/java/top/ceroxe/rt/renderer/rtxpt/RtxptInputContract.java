package top.ceroxe.rt.renderer.rtxpt;

import java.util.Objects;
import java.util.Optional;
import top.ceroxe.rt.renderer.api.*;

/** Rejects Vulkan-only inputs before native state or scene files can change. */
final class RtxptInputContract {
    private RtxptInputContract() { }

    static Optional<String> configurationIssue(RendererConfig configuration) {
        Objects.requireNonNull(configuration, "configuration");
        if (configuration.cpuFrameReadbackEnabled())
            return Optional.of("RTXPT presents GPU frames directly; CPU frame readback is not exposed");
        if (!configuration.sceneShaders().equals(SceneShaderProgram.builtIn()))
            return Optional.of("RTXPT uses upstream shaders; SceneShaderProgram replacements belong to Vulkan");
        if (configuration.frameOutputFormat() != FrameOutputFormat.SDR_RGBA8)
            return Optional.of("RTXPT presents tone-mapped SDR; linear HDR frame export belongs to Vulkan");
        if (configuration.gpuDevice().isPresent())
            return Optional.of("RTXPT does not expose explicit GPU selection; Vulkan GPU identities cannot select its D3D12 device");
        if (configuration.validationEnabled())
            return Optional.of("RTXPT does not expose the API validation-layer control; use the native upstream diagnostics");
        return Optional.empty();
    }

    static void validateConfiguration(RendererConfig configuration) {
        configurationIssue(configuration).ifPresent(reason -> { throw new IllegalArgumentException(reason); });
    }

    static void validateFrame(RenderFrameRequest frame) {
        Objects.requireNonNull(frame, "frame");
        if (frame.camera().hasExactProjection() || !frame.shaderData().isEmpty()
                || frame.fog().opacity() > 0 || frame.caustics().enabled())
            throw new IllegalArgumentException("Exact Vulkan projections, custom shaders, screen fog and photon-map budgets are not native RTXPT controls");
        if (frame.antiAliasing().samplesPerPixel() != 1)
            throw new IllegalArgumentException("RTXPT samples are configured with PathTracingOptions, not per-frame deterministic AntiAliasingState");
        if (!frame.lightmap().texelsRgba8().equals(LightmapState.fullIntensity().texelsRgba8()))
            throw new IllegalArgumentException("Frame-local LightmapState lookups belong to Vulkan; RTXPT uses native scene lighting");
        if (!frame.textureSampling().equals(TextureSamplingState.pixelStable()))
            throw new IllegalArgumentException("Frame-local texture-footprint overrides belong to Vulkan; RTXPT owns texture sampling");
        if (!frame.temporalHistoryResets().isEmpty())
            throw new IllegalArgumentException("Explicit per-frame temporal-history resets are not exposed by the RTXPT host");
    }

    static void requireAdvancingRevision(long requested, long current, boolean published) {
        if (published && requested <= current)
            throw new SceneRevisionException("Scene revision must strictly increase: " + requested + " <= " + current);
    }
}
