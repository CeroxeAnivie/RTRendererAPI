package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Dispatches primary rays into one exact writable output texture view. */
public record TraceRaysCommand(TextureView output, int width, int height, int depth) implements RenderCommand {
    /** Validates explicit dispatch dimensions and shader-writable output usage. */
    public TraceRaysCommand {
        output = Objects.requireNonNull(output, "output");
        if (width <= 0 || height <= 0 || depth <= 0) {
            throw new IllegalArgumentException("trace dimensions must be positive");
        }
        if (!output.texture().usage().contains(TextureUsage.STORAGE_READ_WRITE)) {
            throw new IllegalArgumentException("ray-tracing output requires STORAGE_READ_WRITE texture usage");
        }
        if (width > output.texture().width() || height > output.texture().height() || depth > output.texture().depth()) {
            throw new IllegalArgumentException("trace dimensions exceed output texture base extent");
        }
    }
}
