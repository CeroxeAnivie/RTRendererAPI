package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Deterministic frame-input rejection that cannot succeed by retrying the same request.
 *
 * <p>Unlike {@link SubmissionRejectedException}, this exception never represents transient
 * capacity or renderer progress. Callers must correct the request or renderer configuration
 * before submitting again.</p>
 */
public final class FrameValidationException extends RendererException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /** Stable category retained independently from the human-readable exception message. */
    private final Reason reason;

    /** Stable machine-readable frame validation category. */
    public enum Reason {
        /** A REQUIRED temporal feature cannot consume an unknown depth projection. */
        MISSING_DEPTH_PROJECTION,
        /** The requested output exceeds the selected device's two-dimensional image limit. */
        OUTPUT_EXTENT_EXCEEDS_DEVICE_LIMIT,
        /** The negotiated internal extent exceeds the selected device's ray-dispatch limit. */
        RAY_DISPATCH_EXCEEDS_DEVICE_LIMIT
    }

    /**
     * Creates a deterministic frame validation failure.
     *
     * @param reason stable machine-readable category
     * @param message non-blank human-readable detail
     */
    public FrameValidationException(Reason reason, String message) {
        super(requireMessage(message));
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Returns the stable machine-readable validation category.
     *
     * @return the validation category supplied at construction
     */
    public Reason reason() {
        return reason;
    }

    private static String requireMessage(String message) {
        String checked = Objects.requireNonNull(message, "message").trim();
        if (checked.isEmpty()) throw new IllegalArgumentException("message must not be blank");
        return checked;
    }
}
