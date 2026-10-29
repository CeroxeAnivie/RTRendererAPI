package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.FrameValidationException;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaBoundaryMediumAbi;

/** Opaque direct-light surface shading with native non-scattering medium connections. */
final class CudaPbrMediumProfile {
    private CudaPbrMediumProfile() { }
    static void requireSupported(RenderFrameRequest frame,MediumSceneIndex.NativeScene scene,boolean pbrSurfaces) {
        if(!pbrSurfaces)unsupported("surfaces must be opaque PBR with metallic zero or UNLIT");
        if (!frame.shaderData().isEmpty())
            unsupported("expert shader overrides require a separate native integration");
        if (frame.caustics().enabled()) {
            var camera = frame.environment().cameraMedium();
            if (!scene.boundaries().isEmpty())
                unsupported("medium caustics currently require a global camera medium; explicit boundaries need photon event integration");
            if (!camera.densityField().isUniform() || !camera.scattering().isZero()
                    || camera.indexOfRefraction() != 1)
                unsupported("native medium caustics require homogeneous non-scattering IOR-one absorption");
        }
        try {
            CudaBoundaryMediumAbi.requireMedium(frame.environment().cameraMedium());
            for(var entry:scene.boundaries()) {
                if(entry.boundary().surfaceInteraction())unsupported("native PBR currently requires volume-only explicit boundaries");
                CudaBoundaryMediumAbi.requireMedium(entry.boundary().medium());
            }
        }catch(IllegalArgumentException invalid){unsupported(invalid.getMessage());}
    }
    private static void unsupported(String message){throw new FrameValidationException(FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,
            "native PBR medium candidate does not support this frame: "+message);}
}
