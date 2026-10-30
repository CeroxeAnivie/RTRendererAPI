package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.FrameValidationException;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.rt.pipeline.GpuSceneRayTracingPipeline.Program;

/** One admission decision shared by shader selection and native frame submission. */
final class CudaMediumFrameProfile {
    enum Route {
        NONE, CAMERA, BOUNDARY, CAMERA_SCATTERING, MULTIPLE_SCATTERING,
        DENSITY_SCATTERING, BOUNDARY_SCATTERING, OPTICAL, PBR, PBR_SCATTERING
    }

    record Selection(Program program, Route route, MediumSceneIndex.NativeScene boundaries) { }

    private CudaMediumFrameProfile() { }

    static Selection select(RenderFrameRequest request, MediumSceneIndex media,
            VulkanSurfaceSceneIndex surfaces, boolean builtIn, boolean nativeEnabled) {
        media.validateDensityFields(request);
        if (!builtIn) return new Selection(Program.SURFACE, Route.NONE, null);

        var batch = request.primitiveBatch();
        var medium = request.environment().cameraMedium();
        int boundaryCount = media.frameBoundaryCount(batch);
        boolean hasMedium = !medium.isVacuum() || boundaryCount != 0;
        boolean optical = nativeEnabled && (surfaces.transmissiveCount(batch) != 0
                || media.hasSurfaceInteraction(batch)
                || boundaryCount!=0&&surfaces.opaquePbrMediumOnly(batch)&&surfaces.hasCausticReceiver(batch)
                    &&(!medium.scattering().isZero()||media.hasScattering(batch)));
        if (!hasMedium && !optical)
            return new Selection(VulkanGpuSceneTemporalCoordinator.program(request, media, surfaces), Route.NONE, null);
        if (!nativeEnabled) throw new FrameValidationException(
                FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,
                "Participating-medium frames require the native CUDA production path");

        // Construct boundary/containment metadata once; the executor consumes this same snapshot.
        var scene = optical || boundaryCount != 0 || !medium.densityField().isUniform()
                ? media.nativeScene(request) : null;
        Route route;
        if (optical) {
            CudaOpticalMediumProfile.requireSupported(request, scene,
                    surfaces.opticalTerminalOnly(batch), surfaces.hasCausticReceiver(batch));
            route = Route.OPTICAL;
        } else {
            boolean scattering = !medium.scattering().isZero();
            if (scene != null) for (var boundary : scene.boundaries())
                scattering |= !boundary.boundary().medium().scattering().isZero();
            if (scattering && boundaryCount == 0
                    && surfaces.opaquePbrMediumOnly(batch)) {
                if (scene == null) scene = media.nativeScene(request);
                CudaPbrMediumProfile.requireSupported(request, scene, true, true);
                CudaPbrMediumProfile.requireScatteringContributionSupported(request, boundaryCount);
                route = Route.PBR_SCATTERING;
            } else if (boundaryCount != 0 && scattering) {
                CudaBoundaryScatteringProfile.requireSupported(request, scene, surfaces.cameraAbsorptionOnly(batch));
                route = Route.BOUNDARY_SCATTERING;
            } else if (scattering && request.mediumTransport().scatteringBounces() != 0) {
                CudaMultipleScatteringProfile.requireSupported(request, boundaryCount, surfaces.cameraAbsorptionOnly(batch));
                route = Route.MULTIPLE_SCATTERING;
            } else if (scattering && !medium.densityField().isUniform()) {
                CudaDensityScatteringProfile.requireSupported(request, boundaryCount, surfaces.cameraAbsorptionOnly(batch));
                route = Route.DENSITY_SCATTERING;
            } else if (scattering) {
                CudaCameraScatteringProfile.requireSupported(request, boundaryCount, surfaces.cameraAbsorptionOnly(batch));
                route = Route.CAMERA_SCATTERING;
            } else if (!surfaces.cameraAbsorptionOnly(batch) && surfaces.opaquePbrMediumOnly(batch)) {
                if (scene == null) scene = media.nativeScene(request);
                CudaPbrMediumProfile.requireSupported(request, scene, true);
                route = Route.PBR;
            } else if (boundaryCount != 0 || !medium.densityField().isUniform()) {
                if (scene == null) scene = media.nativeScene(request);
                CudaBoundaryMediumProfile.requireSupported(request, scene, surfaces.cameraAbsorptionOnly(batch));
                route = Route.BOUNDARY;
            } else {
                CudaCameraMediumProfile.requireSupported(request, boundaryCount, surfaces.cameraAbsorptionOnly(batch));
                route = Route.CAMERA;
            }
        }
        return new Selection(Program.SURFACE, route, scene);
    }

    static void validateShaderData(RenderFrameRequest request, Selection selection, boolean builtIn) {
        if (!builtIn || request.shaderData().isEmpty()) return;
        if (selection.route() != Route.NONE) {
            throw new FrameValidationException(FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,
                    "built-in medium transport is not integrated with application shader data");
        }
        throw new FrameValidationException(FrameValidationException.Reason.INVALID_SHADER_DATA,
                "built-in scene shaders declare no application data bindings");
    }
}
