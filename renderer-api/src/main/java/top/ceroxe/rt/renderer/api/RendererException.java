package top.ceroxe.rt.renderer.api;

/**
 * Base class for renderer lifecycle, admission, and native backend failures.
 */
public class RendererException extends RuntimeException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a renderer exception without a cause.
     *
     * @param message human-readable failure summary
     */
    public RendererException(String message) {
        super(message);
    }

    /**
     * Creates a renderer exception with its originating cause.
     *
     * @param message human-readable failure summary
     * @param cause   originating failure
     */
    public RendererException(String message, Throwable cause) {
        super(message, cause);
    }
}
