package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.FrameValidationException;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;

/** Admission for scattering through explicit, volume-only IOR-one boundaries. */
final class CudaBoundaryScatteringProfile {
    private CudaBoundaryScatteringProfile() { }

    static void requireSupported(RenderFrameRequest request,MediumSceneIndex.NativeScene scene,
                                 boolean terminalSurfaces) {
        if(!terminalSurfaces)unsupported("all material surfaces must be opaque double-sided UNLIT terminals");
        if(request.caustics().enabled()||!request.shaderData().isEmpty())
            unsupported("caustics and expert shader data require a later native transport integration");
        if(request.environment().cameraMedium().indexOfRefraction()!=1)
            unsupported("the camera medium must have IOR one");
        if(scene.boundaries().isEmpty()||scene.boundaries().size()>32)
            unsupported("one to 32 explicit boundaries are required");
        boolean scattering=!request.environment().cameraMedium().scattering().isZero();
        for(var entry:scene.boundaries()) {
            if(entry.boundary().surfaceInteraction())unsupported("only volume-only explicit boundaries are supported");
            if(entry.boundary().medium().indexOfRefraction()!=1)
                unsupported("all explicit boundaries must have IOR one");
            scattering|=!entry.boundary().medium().scattering().isZero();
        }
        if(!scattering)unsupported("at least one active medium must scatter");
    }

    private static void unsupported(String message) {
        throw new FrameValidationException(FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,
                "native boundary-scattering candidate does not support this frame: "+message);
    }
}
