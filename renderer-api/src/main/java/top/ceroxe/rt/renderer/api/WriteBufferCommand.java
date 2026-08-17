package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Writes a complete immutable byte payload into one exact destination buffer slice. */
public record WriteBufferCommand(ResourceSlice.BufferSlice destination, ResourceData data) implements RenderCommand {
    /** Validates destination usage and exact payload extent. */
    public WriteBufferCommand {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(data, "data");
        if (!destination.resource().usage().contains(BufferUsage.COPY_DESTINATION)) {
            throw new IllegalArgumentException("write destination does not declare COPY_DESTINATION usage");
        }
        if (data.byteSize() != destination.range().lengthBytes()) {
            throw new IllegalArgumentException("write payload size must equal destination slice length");
        }
    }
}
