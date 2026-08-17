package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Binds one non-empty exact index-buffer slice and its unsigned element format. */
public record BindIndexBufferCommand(
        ResourceSlice.BufferSlice slice,
        IndexFormat format
) implements RenderCommand {
    /** Validates usage and element alignment for deterministic indexed access. */
    public BindIndexBufferCommand {
        Objects.requireNonNull(slice, "slice");
        Objects.requireNonNull(format, "format");
        if (!slice.resource().usage().contains(BufferUsage.INDEX)) {
            throw new IllegalArgumentException("index buffer does not declare INDEX usage");
        }
        if (slice.range().lengthBytes() == 0L
                || slice.range().offsetBytes() % format.byteSize() != 0L
                || slice.range().lengthBytes() % format.byteSize() != 0L) {
            throw new IllegalArgumentException("index-buffer slice must be non-empty and element-aligned");
        }
    }
}
