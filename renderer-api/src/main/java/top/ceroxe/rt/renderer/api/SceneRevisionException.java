package top.ceroxe.rt.renderer.api;

/**
 * A scene or frame revision violates the renderer's monotonic ordering contract.
 */
public final class SceneRevisionException extends RendererException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a revision-order failure.
     *
     * @param message human-readable ordering violation
     */
    public SceneRevisionException(String message) {
        super(message);
    }
}
