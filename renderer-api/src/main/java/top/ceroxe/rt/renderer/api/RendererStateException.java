package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * An operation is invalid for the renderer's current lifecycle state.
 */
public final class RendererStateException extends RendererException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /**
     * Lifecycle state that rejected the operation.
     */
    private final Renderer.Status status;

    /**
     * Creates a lifecycle-state failure.
     *
     * @param message human-readable failure summary
     * @param status  lifecycle state that rejected the operation
     * @param cause   originating failure, or {@code null}
     */
    public RendererStateException(String message, Renderer.Status status, Throwable cause) {
        super(message, cause);
        this.status = Objects.requireNonNull(status, "status");
    }

    /**
     * Returns the lifecycle state that rejected the operation.
     *
     * @return non-null renderer status
     */
    public Renderer.Status status() {
        return status;
    }
}
