package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.FrameValidationException;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;

/** Strict admission preserves the independent validated direct-scattering fast path. */
final class CudaMultipleScatteringProfile {
    private CudaMultipleScatteringProfile() { }
    static void requireSupported(RenderFrameRequest frame,int boundaries,boolean terminal) {
        var medium=frame.environment().cameraMedium();
        if(boundaries!=0||!terminal)unsupported("opaque double-sided UNLIT terminal surfaces without explicit boundaries are required");
        if(medium.scattering().isZero())
            unsupported("a scattering camera medium is required");
        if(frame.mediumTransport().scatteringBounces()<1)unsupported("additional scattering bounces must be requested");
        if(frame.caustics().enabled()||!frame.shaderData().isEmpty())unsupported("caustics and expert shader data require separate integration");
    }
    private static void unsupported(String reason){throw new FrameValidationException(
            FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,"native multiple-scattering candidate does not support this frame: "+reason);}
}
