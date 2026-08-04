package top.ceroxe.rt.renderer.rt.pipeline;

/**
 * Explicit ownership boundary between the ray-traced input extent and the published output extent.
 *
 * <p>Temporal history, ray dispatch, motion vectors, and denoising signals use the render extent.
 * External-frame export, CPU readback, and presentation use the output extent. Keeping both
 * values together prevents an upscaler integration from accidentally treating a low-resolution
 * input as an already reconstructed frame.</p>
 *
 * @param renderWidth positive internal ray-dispatch width
 * @param renderHeight positive internal ray-dispatch height
 * @param outputWidth positive published output width, not smaller than the render width
 * @param outputHeight positive published output height, not smaller than the render height
 */
public record VulkanFrameExtents(
        int renderWidth,
        int renderHeight,
        int outputWidth,
        int outputHeight
) {
    /** Validates positive dimensions and forbids downscaling at the publication boundary. */
    public VulkanFrameExtents {
        requirePositive(renderWidth, "renderWidth");
        requirePositive(renderHeight, "renderHeight");
        requirePositive(outputWidth, "outputWidth");
        requirePositive(outputHeight, "outputHeight");
        if (renderWidth > outputWidth || renderHeight > outputHeight) {
            throw new IllegalArgumentException(
                    "render extent must not exceed the requested output extent: "
                            + renderWidth + 'x' + renderHeight + " -> " + outputWidth + 'x' + outputHeight
            );
        }
    }

    /**
     * Creates the no-reconstruction extent used until a negotiated upscaler supplies an internal
     * render scale.
     *
     * @param width requested output width
     * @param height requested output height
     * @return matching render and output extents
     */
    public static VulkanFrameExtents identity(int width, int height) {
        return new VulkanFrameExtents(width, height, width, height);
    }

    /**
     * Reports whether ray dispatch and publication use the same dimensions.
     *
     * @return {@code true} when render and output extents are identical
     */
    public boolean isIdentity() {
        return renderWidth == outputWidth && renderHeight == outputHeight;
    }

    /**
     * Returns the exact ray-dispatch invocation count.
     *
     * @return render width multiplied by render height
     */
    public long renderPixelCount() {
        return Math.multiplyExact((long) renderWidth, renderHeight);
    }

    /**
     * Returns the exact number of published output pixels.
     *
     * @return output width multiplied by output height
     */
    public long outputPixelCount() {
        return Math.multiplyExact((long) outputWidth, outputHeight);
    }

    private static void requirePositive(int value, String label) {
        if (value <= 0) throw new IllegalArgumentException(label + " must be positive");
    }
}
