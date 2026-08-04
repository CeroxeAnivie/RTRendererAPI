package top.ceroxe.rt.renderer.nvidia;

import top.ceroxe.rt.renderer.api.CameraState;
import top.ceroxe.rt.renderer.api.DepthProjectionState;
import top.ceroxe.rt.renderer.feature.VulkanTemporalFrameInput;

import java.util.Objects;

/**
 * Exact renderer-to-Streamline constants, independent of JNI and GPU uniform byte packing.
 *
 * <p>Streamline expects row-major matrices without temporal jitter. The renderer instead keeps
 * camera bases and Vulkan forward-Z near/far planes as semantic values, so the conversion lives
 * here rather than relying on private shader-layout offsets or a second native camera model.</p>
 */
final class StreamlineFrameConstants {
    private final float[] cameraViewToClip;
    private final float[] clipToCameraView;
    private final float[] clipToPrevClip;
    private final float[] prevClipToClip;
    private final float[] cameraPosition;
    private final float[] cameraUp;
    private final float[] cameraRight;
    private final float[] cameraForward;
    private final float currentJitterX;
    private final float currentJitterY;
    private final float motionScaleX;
    private final float motionScaleY;
    private final float nearPlane;
    private final float farPlane;
    private final float fovRadians;
    private final float aspectRatio;
    private final boolean reset;

    private StreamlineFrameConstants(
            float[] cameraViewToClip,
            float[] clipToCameraView,
            float[] clipToPrevClip,
            float[] prevClipToClip,
            float[] cameraPosition,
            float[] cameraUp,
            float[] cameraRight,
            float[] cameraForward,
            float currentJitterX,
            float currentJitterY,
            float motionScaleX,
            float motionScaleY,
            float nearPlane,
            float farPlane,
            float fovRadians,
            float aspectRatio,
            boolean reset
    ) {
        this.cameraViewToClip = cameraViewToClip;
        this.clipToCameraView = clipToCameraView;
        this.clipToPrevClip = clipToPrevClip;
        this.prevClipToClip = prevClipToClip;
        this.cameraPosition = cameraPosition;
        this.cameraUp = cameraUp;
        this.cameraRight = cameraRight;
        this.cameraForward = cameraForward;
        this.currentJitterX = currentJitterX;
        this.currentJitterY = currentJitterY;
        this.motionScaleX = motionScaleX;
        this.motionScaleY = motionScaleY;
        this.nearPlane = nearPlane;
        this.farPlane = farPlane;
        this.fovRadians = fovRadians;
        this.aspectRatio = aspectRatio;
        this.reset = reset;
    }

    static StreamlineFrameConstants from(VulkanTemporalFrameInput input, int renderWidth, int renderHeight) {
        VulkanTemporalFrameInput checked = Objects.requireNonNull(input, "input");
        if (renderWidth <= 0 || renderHeight <= 0) {
            throw new IllegalArgumentException("Streamline render extent must be positive");
        }
        TemporalCameraMatrices matrices = TemporalCameraMatrices.from(checked);
        CameraState camera = checked.request().camera();
        DepthProjectionState projection = checked.request().depthProjection();
        float motionScaleX = 1.0F / renderWidth;
        float motionScaleY = 1.0F / renderHeight;
        float fovRadians = 2.0F * (float) Math.atan(camera.tanHalfFovY());
        float aspectRatio = camera.tanHalfFovX() / camera.tanHalfFovY();
        requireFinitePositive(motionScaleX, "motionScaleX");
        requireFinitePositive(motionScaleY, "motionScaleY");
        requireFinitePositive(fovRadians, "fovRadians");
        requireFinitePositive(aspectRatio, "aspectRatio");
        return new StreamlineFrameConstants(
                matrices.currentViewToClip(),
                matrices.currentClipToView(),
                matrices.currentClipToPreviousClip(),
                matrices.previousClipToCurrentClip(),
                position(camera),
                vector(camera.upX(), camera.upY(), camera.upZ()),
                vector(camera.rightX(), camera.rightY(), camera.rightZ()),
                vector(camera.forwardX(), camera.forwardY(), camera.forwardZ()),
                checked.currentJitterX(), checked.currentJitterY(),
                motionScaleX, motionScaleY,
                projection.nearPlane(), projection.farPlane(),
                fovRadians,
                aspectRatio,
                !checked.historyValid()
        );
    }

    float[] cameraViewToClip() { return cameraViewToClip.clone(); }
    float[] clipToCameraView() { return clipToCameraView.clone(); }
    float[] clipToPrevClip() { return clipToPrevClip.clone(); }
    float[] prevClipToClip() { return prevClipToClip.clone(); }
    float[] cameraPosition() { return cameraPosition.clone(); }
    float[] cameraUp() { return cameraUp.clone(); }
    float[] cameraRight() { return cameraRight.clone(); }
    float[] cameraForward() { return cameraForward.clone(); }
    float currentJitterX() { return currentJitterX; }
    float currentJitterY() { return currentJitterY; }
    float motionScaleX() { return motionScaleX; }
    float motionScaleY() { return motionScaleY; }
    float nearPlane() { return nearPlane; }
    float farPlane() { return farPlane; }
    float fovRadians() { return fovRadians; }
    float aspectRatio() { return aspectRatio; }
    boolean reset() { return reset; }

    private static float[] position(CameraState camera) {
        return vector((float) camera.x(), (float) camera.y(), (float) camera.z());
    }

    private static float[] vector(float x, float y, float z) { return new float[]{x, y, z}; }

    private static void requireFinitePositive(float value, String name) {
        if (!Float.isFinite(value) || value <= 0.0F) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
    }

}
