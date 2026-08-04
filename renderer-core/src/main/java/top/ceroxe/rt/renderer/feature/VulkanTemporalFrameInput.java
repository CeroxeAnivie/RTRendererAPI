package top.ceroxe.rt.renderer.feature;

import top.ceroxe.rt.renderer.api.CameraState;
import top.ceroxe.rt.renderer.api.DepthProjectionState;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;

import java.util.Objects;

/**
 * Immutable camera facts shared by native temporal integrations for one Vulkan frame.
 *
 * <p>This contract intentionally carries semantic camera and projection values rather than GPU
 * uniform bytes. NRD and Streamline require different matrix storage conventions and motion-vector
 * semantics; both conversions must therefore start from the same exact facts without depending on
 * renderer-private shader packing.</p>
 *
 * @param request current immutable render request
 * @param previousCamera prior camera state used for temporal transforms
 * @param previousDepthProjection prior exact depth projection
 * @param previousSequence prior frame sequence, not greater than the current sequence
 * @param historyValid whether prior-frame temporal history may be consumed
 * @param currentJitterX current horizontal pixel-centered jitter in {@code [-0.5, 0.5)}
 * @param currentJitterY current vertical pixel-centered jitter in {@code [-0.5, 0.5)}
 * @param previousJitterX prior horizontal pixel-centered jitter in {@code [-0.5, 0.5)}
 * @param previousJitterY prior vertical pixel-centered jitter in {@code [-0.5, 0.5)}
 */
public record VulkanTemporalFrameInput(
        RenderFrameRequest request,
        CameraState previousCamera,
        DepthProjectionState previousDepthProjection,
        long previousSequence,
        boolean historyValid,
        float currentJitterX,
        float currentJitterY,
        float previousJitterX,
        float previousJitterY
) {
    /** Validates temporal identity, exact projections, and pixel-centered jitter. */
    public VulkanTemporalFrameInput {
        request = Objects.requireNonNull(request, "request");
        previousCamera = Objects.requireNonNull(previousCamera, "previousCamera");
        previousDepthProjection = Objects.requireNonNull(previousDepthProjection, "previousDepthProjection");
        if (previousSequence < 0L || previousSequence > request.sequence()) {
            throw new IllegalArgumentException("previous temporal frame sequence is out of range");
        }
        if (!request.depthProjection().known()) {
            throw new IllegalArgumentException("temporal frame input requires an exact depth projection");
        }
        if (historyValid && !previousDepthProjection.known()) {
            throw new IllegalArgumentException("temporal frame input requires the prior exact depth projection");
        }
        requirePixelCenteredJitter(currentJitterX, "currentJitterX");
        requirePixelCenteredJitter(currentJitterY, "currentJitterY");
        requirePixelCenteredJitter(previousJitterX, "previousJitterX");
        requirePixelCenteredJitter(previousJitterY, "previousJitterY");
    }

    private static void requirePixelCenteredJitter(float value, String name) {
        if (!Float.isFinite(value) || value < -0.5F || value >= 0.5F) {
            throw new IllegalArgumentException(name + " must be finite and pixel-centered in [-0.5, 0.5)");
        }
    }
}
