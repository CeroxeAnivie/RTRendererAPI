package top.ceroxe.rt.renderer.api;

/**
 * A frame or transaction sequence did not advance beyond the last accepted submission.
 */
public final class SubmissionOrderException extends RendererException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a submission-order failure.
     *
     * @param message human-readable ordering violation
     */
    public SubmissionOrderException(String message) {
        super(message);
    }
}
