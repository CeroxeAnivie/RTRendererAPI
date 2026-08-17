package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Binds one non-empty exact buffer slice to a vertex-input binding. */
public record BindVertexBufferCommand(
        int binding,
        ResourceSlice.BufferSlice slice
) implements RenderCommand {
    /** Validates binding number, range, and declared resource usage. */
    public BindVertexBufferCommand {
        if (binding < 0) throw new IllegalArgumentException("vertex-buffer binding must be non-negative");
        Objects.requireNonNull(slice, "slice");
        if (slice.range().lengthBytes() == 0L) {
            throw new IllegalArgumentException("vertex-buffer slice must not be empty");
        }
        if (!slice.resource().usage().contains(BufferUsage.VERTEX)) {
            throw new IllegalArgumentException("vertex buffer does not declare VERTEX usage");
        }
    }
}
