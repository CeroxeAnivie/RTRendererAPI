package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

import top.ceroxe.mcvulkanrt.renderer.RendererFrameState;

import java.util.Objects;

/**
 * Immutable output-resource sizing policy for one RT frame ring.
 *
 * <p>This is intentionally independent of Vulkan allocation. It determines the stable output
 * image extent from a frozen frame state; the pipeline owns the resulting resource replacement
 * and waits for in-flight work before applying a changed extent.</p>
 */
record RtFrameOutputConfig(
        int baseWidth,
        int baseHeight,
        int maxPixels,
        boolean followsRenderTarget,
        int primaryRayUpscaleFactor
) {
    RtFrameOutputConfig {
        if (baseWidth <= 0 || baseHeight <= 0) {
            throw new IllegalArgumentException("frame output base dimensions must be positive");
        }
        if (maxPixels <= 0) {
            throw new IllegalArgumentException("frame output maxPixels must be positive");
        }
        if (primaryRayUpscaleFactor <= 0
                || primaryRayUpscaleFactor > RtFrameDispatchPolicy.MAX_PRIMARY_RAY_UPSCALE_FACTOR) {
            throw new IllegalArgumentException(
                    "frame output primaryRayUpscaleFactor must be within 1.."
                            + RtFrameDispatchPolicy.MAX_PRIMARY_RAY_UPSCALE_FACTOR
            );
        }
    }

    Extent initialExtent() {
        return new Extent(baseWidth, baseHeight);
    }

    Extent resolve(RendererFrameState frameState) {
        Objects.requireNonNull(frameState, "frameState");
        if (!followsRenderTarget || !frameState.valid()) {
            return initialExtent();
        }
        return Extent.fitWithin(frameState.targetWidth(), frameState.targetHeight(), maxPixels);
    }

    String summary(String name) {
        return name
                + "{base=" + baseWidth + "x" + baseHeight
                + ", maxPixels=" + maxPixels
                + ", followsRenderTarget=" + followsRenderTarget
                + ", primaryRayUpscaleFactor=" + primaryRayUpscaleFactor
                + "}";
    }

    record Extent(int width, int height) {
        Extent {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("frame output extent must be positive");
            }
        }

        static Extent fitWithin(int width, int height, int maxPixels) {
            if (maxPixels <= 0) {
                throw new IllegalArgumentException("maxPixels must be positive");
            }
            long pixels = (long) width * height;
            if (pixels <= 0L) {
                throw new IllegalArgumentException("frame output extent must be positive");
            }
            if (pixels <= maxPixels) {
                return new Extent(width, height);
            }
            double scale = Math.sqrt(maxPixels / (double) pixels);
            int scaledWidth = Math.max(1, (int) Math.floor(width * scale));
            int scaledHeight = Math.max(1, (int) Math.floor(height * scale));
            while ((long) scaledWidth * scaledHeight > maxPixels) {
                if (scaledWidth >= scaledHeight && scaledWidth > 1) {
                    scaledWidth--;
                } else if (scaledHeight > 1) {
                    scaledHeight--;
                } else {
                    break;
                }
            }
            return new Extent(scaledWidth, scaledHeight);
        }

        Extent divideAndRoundUp(int factor) {
            if (factor <= 0) {
                throw new IllegalArgumentException("frame output scale factor must be positive");
            }
            return new Extent(Math.max(1, Math.ceilDiv(width, factor)), Math.max(1, Math.ceilDiv(height, factor)));
        }
    }
}
