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
}
