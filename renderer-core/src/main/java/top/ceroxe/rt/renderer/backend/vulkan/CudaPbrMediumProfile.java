package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.FrameValidationException;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaBoundaryMediumAbi;

/** Opaque direct-light surface shading with native non-scattering medium connections. */
final class CudaPbrMediumProfile {
    private CudaPbrMediumProfile() { }
    static void requireScatteringContributionSupported(RenderFrameRequest frame,int boundaryCount) {
        var medium=frame.environment().cameraMedium();
        if(boundaryCount!=0||medium.scattering().isZero())
            unsupported("PBR scattering requires a scattering camera medium without explicit boundaries");
        if(frame.caustics().enabled() && (!medium.densityField().isUniform() || medium.indexOfRefraction()!=1))
            unsupported("PBR scattering caustics require a homogeneous global IOR-one medium");
        if(!frame.shaderData().isEmpty())
            unsupported("expert shader data requires separate PBR scattering integration");
    }
    static void requireSupported(RenderFrameRequest frame,MediumSceneIndex.NativeScene scene,boolean pbrSurfaces) {
        requireSupported(frame,scene,pbrSurfaces,false);
    }
    static void requireSupported(RenderFrameRequest frame,MediumSceneIndex.NativeScene scene,boolean pbrSurfaces,
            boolean scatteringCamera) {
        if(!pbrSurfaces)unsupported("surfaces must be opaque PBR with metallic zero or UNLIT");
        if (!frame.shaderData().isEmpty())
            unsupported("expert shader overrides require a separate native integration");
        if (frame.caustics().enabled()) {
            var camera = frame.environment().cameraMedium();
            if (!scene.boundaries().isEmpty())
                unsupported("medium caustics currently require a global camera medium; explicit boundaries need photon event integration");
            if (!camera.densityField().isUniform() || camera.indexOfRefraction() != 1)
                unsupported("native medium caustics require a homogeneous global IOR-one medium");
        }
        try {
            var camera=frame.environment().cameraMedium();
            if(scatteringCamera) {
                if(camera.scattering().isZero())
                    unsupported("PBR scattering requires a scattering camera medium");
            } else CudaBoundaryMediumAbi.requireMedium(camera);
            for(var entry:scene.boundaries()) {
                if(entry.boundary().surfaceInteraction())unsupported("native PBR currently requires volume-only explicit boundaries");
                CudaBoundaryMediumAbi.requireMedium(entry.boundary().medium());
            }
        }catch(IllegalArgumentException invalid){unsupported(invalid.getMessage());}
    }
    private static void unsupported(String message){throw new FrameValidationException(FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,
            "native PBR medium candidate does not support this frame: "+message);}
}
