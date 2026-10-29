package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.FrameValidationException;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;

/** Heterogeneous direct scattering with retained geometry/light queries. */
final class CudaDensityScatteringProfile {
    private CudaDensityScatteringProfile() { }
    static void requireSupported(RenderFrameRequest frame,int boundaries,boolean terminal) {
        var medium=frame.environment().cameraMedium();
        if(boundaries!=0||!terminal)unsupported("opaque double-sided UNLIT terminal surfaces without explicit boundaries are required");
        if(medium.densityField().isUniform()||medium.scattering().isZero()||medium.indexOfRefraction()!=1)
            unsupported("a heterogeneous IOR-one scattering camera medium is required");
        if(frame.mediumTransport().scatteringBounces()!=0)unsupported("additional density scattering bounces require separate continuation integration");
        if(frame.caustics().enabled()||!frame.shaderData().isEmpty())unsupported("caustics and expert shader data require separate integration");
    }
    private static void unsupported(String message) {throw new FrameValidationException(
            FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,"native density-scattering candidate: "+message);}
}
