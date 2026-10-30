package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.FrameValidationException;

/** Strict admission for the first native-camera segment integration; unsupported work stays explicit. */
final class CudaCameraMediumProfile {
    static final String ENABLE_PROPERTY = "top.ceroxe.rt.medium.cuda.cameraSegment";
    private CudaCameraMediumProfile() { }

    /** Built-in medium transport is the ordinary path; the property remains an explicit rollback switch. */
    static boolean requested() { return Boolean.parseBoolean(System.getProperty(ENABLE_PROPERTY, "true")); }

    static void requireSupported(RenderFrameRequest request, int boundaryCount, boolean terminalOpaqueDoubleSided) {
        var medium = request.environment().cameraMedium();
        if (boundaryCount != 0)
            unsupported("opaque geometry without explicit volume boundaries is required");
        if (!terminalOpaqueDoubleSided) unsupported("all referenced surfaces must be opaque, double-sided and UNLIT");
        if (!medium.densityField().isUniform() || !medium.scattering().isZero())
            unsupported("the camera medium must be homogeneous and non-scattering");
        if (!medium.participating()) unsupported("a participating camera absorption or emission medium is required");
        if (request.caustics().enabled() || !request.shaderData().isEmpty())
            unsupported("caustics and application shader data are not supported by this integration slice");
    }

    private static void unsupported(String reason) {
        throw new FrameValidationException(FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,
                "native camera-medium candidate does not support this frame: " + reason);
    }
}
