package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.FrameValidationException;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;

/** Admission for direct single scattering; unsupported continuation is rejected before GPU work. */
final class CudaCameraScatteringProfile {
    private CudaCameraScatteringProfile() { }
    static void requireSupported(RenderFrameRequest frame,int boundaryCount,boolean terminalSurfaces) {
        var medium=frame.environment().cameraMedium();
        if(boundaryCount!=0||!terminalSurfaces)unsupported("opaque double-sided UNLIT surfaces without explicit boundaries are required");
        if(!medium.densityField().isUniform()||medium.indexOfRefraction()!=1||medium.scattering().isZero())
            unsupported("uniform IOR-one scattering camera medium is required");
        if(frame.mediumTransport().scatteringBounces()!=0)unsupported("additional volume bounces require the next continuation integration");
        if(frame.caustics().enabled()||!frame.shaderData().isEmpty())unsupported("caustics and expert shader data require separate integration");
    }
    private static void unsupported(String reason) {
        throw new FrameValidationException(FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,
                "native direct-scattering candidate does not support this frame: "+reason);
    }
}
