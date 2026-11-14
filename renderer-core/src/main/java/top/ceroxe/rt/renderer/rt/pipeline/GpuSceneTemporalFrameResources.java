package top.ceroxe.rt.renderer.rt.pipeline;

import top.ceroxe.rt.renderer.rt.device.RtGpuImage;

import java.util.Objects;

/**
 * Complete temporal image generation consumed by one GPUScene dispatch.
 *
 * @param colorInput              previous linear-color history
 * @param colorOutput             current linear-color history target
 * @param geometryInput           previous normal/log-depth history
 * @param geometryOutput          current normal/log-depth history target
 * @param motionOutput            current motion-vector target
 * @param inputLayoutInitialized  whether both input planes are already in general layout
 * @param outputLayoutInitialized whether both output planes are already in general layout
 * @param motionLayoutInitialized whether the motion target is already in general layout
 */
public record GpuSceneTemporalFrameResources(
        RtGpuImage colorInput,
        RtGpuImage colorOutput,
        RtGpuImage geometryInput,
        RtGpuImage geometryOutput,
        RtGpuImage motionOutput,
        boolean inputLayoutInitialized,
        boolean outputLayoutInitialized,
        boolean motionLayoutInitialized
) {
    /**
     * Validates one non-aliasing temporal ping-pong generation.
     *
     * <p>Keeping both history planes at the same extent prevents descriptor updates from pairing
     * a valid color history with incompatible geometry history after resize or reallocation.</p>
     *
     * @param colorInput              previous linear-color history
     * @param colorOutput             current linear-color history target
     * @param geometryInput           previous normal/log-depth history
     * @param geometryOutput          current normal/log-depth history target
     * @param motionOutput            current motion-vector target
     * @param inputLayoutInitialized  whether both input planes are already in general layout
     * @param outputLayoutInitialized whether both output planes are already in general layout
     * @param motionLayoutInitialized whether the motion target is already in general layout
     */
    public GpuSceneTemporalFrameResources {
        colorInput = Objects.requireNonNull(colorInput, "colorInput");
        colorOutput = Objects.requireNonNull(colorOutput, "colorOutput");
        geometryInput = Objects.requireNonNull(geometryInput, "geometryInput");
        geometryOutput = Objects.requireNonNull(geometryOutput, "geometryOutput");
        motionOutput = Objects.requireNonNull(motionOutput, "motionOutput");
        if (colorInput == colorOutput || geometryInput == geometryOutput) {
            throw new IllegalArgumentException("temporal input and output images must not alias");
        }
        requireSameExtent(colorInput, colorOutput, "color history");
        requireSameExtent(geometryInput, geometryOutput, "geometry history");
        requireSameExtent(colorInput, geometryInput, "history planes");
    }

    private static void requireSameExtent(RtGpuImage first, RtGpuImage second, String label) {
        if (first.width() != second.width() || first.height() != second.height()) {
            throw new IllegalArgumentException(label + " extents do not match");
        }
    }
}
