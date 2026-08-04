package top.ceroxe.rt.renderer.nvidia;

import top.ceroxe.rt.renderer.feature.VulkanTemporalFrameInput;

import java.util.Objects;

/** Exact non-jittered NRD {@code CommonSettings} camera contract for one frame. */
final class NrdFrameConstants {
    private final float[] viewToClipMatrix;
    private final float[] viewToClipMatrixPrev;
    private final float[] worldToViewMatrix;
    private final float[] worldToViewMatrixPrev;
    private final float currentJitterX;
    private final float currentJitterY;
    private final float previousJitterX;
    private final float previousJitterY;
    private final float motionVectorScaleX;
    private final float motionVectorScaleY;
    private final boolean reset;

    private NrdFrameConstants(
            float[] viewToClipMatrix,
            float[] viewToClipMatrixPrev,
            float[] worldToViewMatrix,
            float[] worldToViewMatrixPrev,
            float currentJitterX,
            float currentJitterY,
            float previousJitterX,
            float previousJitterY,
            float motionVectorScaleX,
            float motionVectorScaleY,
            boolean reset
    ) {
        this.viewToClipMatrix = viewToClipMatrix;
        this.viewToClipMatrixPrev = viewToClipMatrixPrev;
        this.worldToViewMatrix = worldToViewMatrix;
        this.worldToViewMatrixPrev = worldToViewMatrixPrev;
        this.currentJitterX = currentJitterX;
        this.currentJitterY = currentJitterY;
        this.previousJitterX = previousJitterX;
        this.previousJitterY = previousJitterY;
        this.motionVectorScaleX = motionVectorScaleX;
        this.motionVectorScaleY = motionVectorScaleY;
        this.reset = reset;
    }

    static NrdFrameConstants from(
            VulkanTemporalFrameInput input,
            int renderWidth,
            int renderHeight,
            boolean historyReset
    ) {
        VulkanTemporalFrameInput checked = Objects.requireNonNull(input, "input");
        if (renderWidth <= 0 || renderHeight <= 0 || renderWidth > 0xFFFF || renderHeight > 0xFFFF) {
            throw new IllegalArgumentException("NRD render extent must fit positive uint16 dimensions");
        }
        if (!checked.historyValid() && !historyReset) {
            throw new IllegalArgumentException("NRD must reset when prior temporal history is invalid");
        }
        TemporalCameraMatrices matrices = TemporalCameraMatrices.from(checked);
        float previousJitterX = checked.historyValid()
                ? checked.previousJitterX()
                : checked.currentJitterX();
        float previousJitterY = checked.historyValid()
                ? checked.previousJitterY()
                : checked.currentJitterY();
        return new NrdFrameConstants(
                matrices.currentViewToClip(),
                matrices.previousViewToClip(),
                matrices.currentWorldToView(),
                matrices.previousWorldToView(),
                checked.currentJitterX(),
                checked.currentJitterY(),
                previousJitterX,
                previousJitterY,
                1.0F / renderWidth,
                1.0F / renderHeight,
                historyReset
        );
    }

    float[] viewToClipMatrix() { return viewToClipMatrix.clone(); }
    float[] viewToClipMatrixPrev() { return viewToClipMatrixPrev.clone(); }
    float[] worldToViewMatrix() { return worldToViewMatrix.clone(); }
    float[] worldToViewMatrixPrev() { return worldToViewMatrixPrev.clone(); }
    float currentJitterX() { return currentJitterX; }
    float currentJitterY() { return currentJitterY; }
    float previousJitterX() { return previousJitterX; }
    float previousJitterY() { return previousJitterY; }
    float motionVectorScaleX() { return motionVectorScaleX; }
    float motionVectorScaleY() { return motionVectorScaleY; }
    boolean reset() { return reset; }
}
