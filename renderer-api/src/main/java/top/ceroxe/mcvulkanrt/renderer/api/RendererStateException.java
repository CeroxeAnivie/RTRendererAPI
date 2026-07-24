package top.ceroxe.mcvulkanrt.renderer.api;

import java.util.Objects;

/** An operation is invalid for the renderer's current lifecycle state. */
public final class RendererStateException extends RendererException {
    private final RayTracingRenderer.Status status;

    public RendererStateException(String message, RayTracingRenderer.Status status, Throwable cause) {
        super(message, cause);
        this.status = Objects.requireNonNull(status, "status");
    }

    public RayTracingRenderer.Status status() {
        return status;
    }
}
