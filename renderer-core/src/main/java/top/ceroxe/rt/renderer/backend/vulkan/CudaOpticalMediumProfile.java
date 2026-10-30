package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.FrameValidationException;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;

/** Admission for native dielectric transport with selected-medium scattering and direct connections. */
final class CudaOpticalMediumProfile {
    private CudaOpticalMediumProfile() { }
    static void requireSupported(RenderFrameRequest request,MediumSceneIndex.NativeScene scene,boolean opticalSurfaces) {
        requireSupported(request,scene,opticalSurfaces,false);
    }
    static void requireSupported(RenderFrameRequest request,MediumSceneIndex.NativeScene scene,
            boolean opticalSurfaces,boolean pbrCausticReceiver) {
        if(!opticalSurfaces)
            unsupported("scene surfaces must reference supported, resident material and mesh resources");
        for(var entry:scene.boundaries())
            if(!entry.boundary().surfaceInteraction()&&entry.boundary().medium().indexOfRefraction()!=1)
                unsupported("IOR-changing boundaries must enable surface interaction");
    }
    private static void unsupported(String reason) {throw new FrameValidationException(
            FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,"native optical candidate: "+reason);}
}
