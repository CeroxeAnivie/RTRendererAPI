package top.ceroxe.rt.renderer.rtxpt;

import java.util.Objects;
import top.ceroxe.rt.renderer.api.*;

/** Validates frame inputs and monotonic scene revisions before native state changes. */
final class RtxptInputContract {
    private RtxptInputContract() { }

    static void validateFrame(RenderFrameRequest frame) {
        validateFrame(frame,false);
    }
    static void validateFrame(RenderFrameRequest frame,boolean nativeLighting) {
        Objects.requireNonNull(frame, "frame");
        if ((!nativeLighting&&frame.camera().hasExactProjection()) || !frame.shaderData().isEmpty()
                || frame.fog().opacity() > 0 || frame.caustics().enabled())
            throw new IllegalArgumentException("Exact Vulkan projections, custom shaders, screen fog and photon-map budgets are not native RTXPT controls");
        if (frame.antiAliasing().samplesPerPixel() != 1)
            throw new IllegalArgumentException("RTXPT samples are configured with PathTracingOptions, not per-frame deterministic AntiAliasingState");
        if (!frame.lightmap().texelsRgba8().equals(LightmapState.fullIntensity().texelsRgba8()))
            throw new IllegalArgumentException("Frame-local LightmapState lookups belong to Vulkan; RTXPT uses native scene lighting");
        if (!frame.textureSampling().equals(TextureSamplingState.pixelStable()))
            throw new IllegalArgumentException("Frame-local texture-footprint overrides belong to Vulkan; RTXPT owns texture sampling");

    }

    static void requireAdvancingRevision(long requested, long current, boolean published) {
        if (published && requested <= current)
            throw new SceneRevisionException("Scene revision must strictly increase: " + requested + " <= " + current);
    }
}
