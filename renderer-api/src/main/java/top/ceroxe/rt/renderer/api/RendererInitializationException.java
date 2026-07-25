package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * A selected backend failed while probing or acquiring renderer-owned resources.
 */
public final class RendererInitializationException extends RendererException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /**
     * Provider that failed during probe or initialization.
     */
    private final String providerId;

    /**
     * Creates a backend initialization failure.
     *
     * @param message    human-readable failure summary
     * @param providerId non-null provider identifier
     * @param cause      originating probe or initialization failure
     */
    public RendererInitializationException(String message, String providerId, Throwable cause) {
        super(message, cause);
        this.providerId = Objects.requireNonNull(providerId, "providerId");
    }

    /**
     * Returns the provider that failed.
     *
     * @return non-null provider identifier
     */
    public String providerId() {
        return providerId;
    }
}
