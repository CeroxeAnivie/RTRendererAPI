package top.ceroxe.rt.renderer.api;

/**
 * Recoverable capacity refusal; no logical or native submission state was retained.
 */
public final class SubmissionRejectedException extends RendererException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /** Stable typed refusal category. */
    private final SubmissionDeferralReason reason;
    /** Bounded provider detail retained for diagnostics. */
    private final String detail;

    /**
     * Creates a typed recoverable admission refusal without a cause.
     *
     * @param reason stable capacity classification
     * @param detail non-blank provider diagnostic detail
     */
    public SubmissionRejectedException(SubmissionDeferralReason reason, String detail) {
        this(reason, detail, null);
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
        super(requireDetail(detail), cause);
        this.reason = java.util.Objects.requireNonNull(reason, "reason");
        this.detail = detail;
    }

    /**
     * Returns the stable refusal category without requiring diagnostic-text parsing by callers.
     *
     * @return typed category
     */
    public SubmissionDeferralReason deferralReason() {
        return reason;
    }

    /**
     * Returns human-readable provider detail with the stable transport marker removed.
     *
     * @return non-blank diagnostic detail
     */
    public String detail() {
        return detail;
    }

    private static String requireDetail(String detail) {
        String checked = java.util.Objects.requireNonNull(detail, "detail");
        if (checked.isBlank()) throw new IllegalArgumentException("detail must not be blank");
        return checked;
    }
}
