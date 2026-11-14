package top.ceroxe.rt.renderer.feature;

import java.util.Objects;

/**
 * Signals that an optional feature selected its configured fallback before queue submission.
 *
 * <p>The current command buffer must be discarded and the same renderer frame may be retried
 * against the provider's new capability state. This is not a renderer or device failure: no GPU
 * work from the aborted recording has been published.</p>
 */
public final class VulkanFeatureFallbackException extends RuntimeException {
    /**
     * Creates a fallback boundary with its originating provider failure.
     *
     * @param message non-blank description of the selected next-frame fallback
     * @param cause originating optional-feature failure
     */
    public VulkanFeatureFallbackException(String message, Throwable cause) {
        super(requireMessage(message), Objects.requireNonNull(cause, "cause"));
    }

    private static String requireMessage(String message) {
        String checked = Objects.requireNonNull(message, "message").trim();
        if (checked.isEmpty()) throw new IllegalArgumentException("message must not be blank");
        return checked;
    }
}
