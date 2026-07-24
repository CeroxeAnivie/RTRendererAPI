package top.ceroxe.mcvulkanrt.renderer.api;

import java.util.Objects;

/** A selected backend failed while probing or acquiring renderer-owned resources. */
public final class RendererInitializationException extends RendererException {
    private final String providerId;

    public RendererInitializationException(String message, String providerId, Throwable cause) {
        super(message, cause);
        this.providerId = Objects.requireNonNull(providerId, "providerId");
    }

    public String providerId() {
        return providerId;
    }
}
