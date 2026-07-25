package top.ceroxe.rt.renderer.api;

/**
 * A prospective scene generation is internally inconsistent and was not published.
 */
public final class SceneValidationException extends RendererException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a scene validation failure without a cause.
     *
     * @param message human-readable validation failure
     */
    public SceneValidationException(String message) {
        super(message);
    }

    /**
     * Creates a scene validation failure with its originating cause.
     *
     * @param message human-readable validation failure
     * @param cause   originating validation failure
     */
    public SceneValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
