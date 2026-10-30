package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.FrameValidationException;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaBoundaryMediumAbi;

/** Admission for homogeneous volume boundaries and terminal unlit surfaces. */
final class CudaBoundaryMediumProfile {
    private CudaBoundaryMediumProfile() { }
    static void requireSupported(RenderFrameRequest request, MediumSceneIndex.NativeScene scene,
                                 boolean terminalSurfaces) {
        if(!terminalSurfaces) unsupported("all material-shaded surfaces must be opaque, double-sided and UNLIT");
        if(request.caustics().enabled() || !request.shaderData().isEmpty())
            unsupported("caustics and expert shader data require a later native transport integration");
        try {
            CudaBoundaryMediumAbi.requireMedium(request.environment().cameraMedium());
            for(var entry:scene.boundaries()) {
                if(entry.boundary().surfaceInteraction()) unsupported("only volume-only explicit boundaries are supported");
                CudaBoundaryMediumAbi.requireMedium(entry.boundary().medium());
            }
        } catch(IllegalArgumentException unsupported) { unsupported(unsupported.getMessage()); }
    }
    private static void unsupported(String message) {
        throw new FrameValidationException(FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,
                "native boundary-medium candidate does not support this frame: "+message);
    }
}
