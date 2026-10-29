package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.FrameValidationException;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaOpticalMediumAbi;

/** Explicit admission for dielectric materials and terminal opaque UNLIT surfaces. */
final class CudaOpticalMediumProfile {
    private CudaOpticalMediumProfile() { }
    static void requireSupported(RenderFrameRequest request,MediumSceneIndex.NativeScene scene,boolean opticalSurfaces) {
        if(!opticalSurfaces)unsupported("surfaces must be double-sided opaque UNLIT or fully covered dielectric materials");
        if(request.caustics().enabled()||!request.shaderData().isEmpty())unsupported("caustics and expert shader data are not integrated");
        boolean scattering=request.environment().cameraMedium().participating()&&!request.environment().cameraMedium().scattering().isZero();
        for(var entry:scene.boundaries())scattering|=entry.boundary().medium().participating()&&!entry.boundary().medium().scattering().isZero();
        if(scattering&&!scene.boundaries().isEmpty())unsupported("optical scattering shadow connections require a single homogeneous camera medium");
        try {
            CudaOpticalMediumAbi.requireMedium(request.environment().cameraMedium());
            for(var entry:scene.boundaries()) {
                CudaOpticalMediumAbi.requireMedium(entry.boundary().medium());
                if(!entry.boundary().surfaceInteraction()&&entry.boundary().medium().indexOfRefraction()!=1)
                    unsupported("IOR-changing boundaries must enable surface interaction");
            }
        }catch(IllegalArgumentException invalid){unsupported(invalid.getMessage());}
    }
    private static void unsupported(String reason) {throw new FrameValidationException(
            FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,"native optical candidate: "+reason);}
}
