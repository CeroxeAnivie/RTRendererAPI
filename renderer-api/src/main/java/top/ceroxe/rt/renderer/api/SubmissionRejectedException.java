package top.ceroxe.rt.renderer.api;

/**
 * Recoverable capacity refusal; no logical or native submission state was retained.
 */
public final class SubmissionRejectedException extends RendererException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a recoverable admission refusal without a cause.
     *
     * @param message human-readable rejection reason
     */
    public SubmissionRejectedException(String message) {
        super(message);
    }

    /**
     * Creates a recoverable admission refusal with its originating cause.
     *
     * @param message human-readable rejection reason
     * @param cause   originating capacity or admission failure
     */
    public SubmissionRejectedException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates a typed recoverable admission refusal without a cause.
     *
     * @param reason stable capacity classification
     * @param detail non-blank provider diagnostic detail
     */
    public SubmissionRejectedException(SubmissionDeferralReason reason, String detail) {
        super(SubmissionDeferralReason.encode(reason, detail));
    }

    /**
     * Creates a typed recoverable admission refusal with its originating cause.
     *
     * @param reason stable capacity classification
     * @param detail non-blank provider diagnostic detail
     * @param cause  originating capacity failure
     */
    public SubmissionRejectedException(
            SubmissionDeferralReason reason,
            String detail,
            Throwable cause
    ) {
        super(SubmissionDeferralReason.encode(reason, detail), cause);
    }

    /**
     * Returns the stable refusal category without requiring diagnostic-text parsing by callers.
     *
     * @return typed category, or {@link SubmissionDeferralReason#UNSPECIFIED} for legacy providers
     */
    public SubmissionDeferralReason deferralReason() {
        return SubmissionDeferralReason.decode(getMessage());
    }

    /**
     * Returns human-readable provider detail with the stable transport marker removed.
     *
     * @return non-blank diagnostic detail
     */
    public String detail() {
        return SubmissionDeferralReason.detail(getMessage());
    }
}
