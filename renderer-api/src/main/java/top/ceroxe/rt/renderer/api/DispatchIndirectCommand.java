package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Indirect compute dispatch reading one aligned three-word argument record. */
public record DispatchIndirectCommand(ResourceSlice.BufferSlice arguments) implements RenderCommand {
    public DispatchIndirectCommand {
        Objects.requireNonNull(arguments, "arguments");
        if (!arguments.resource().usage().contains(BufferUsage.INDIRECT)) {
            throw new IllegalArgumentException("dispatch argument buffer does not declare INDIRECT usage");
        }
        if ((arguments.range().offsetBytes() & 3L) != 0L || arguments.range().lengthBytes() < 12L) {
            throw new IllegalArgumentException("dispatch argument slice must expose one aligned 12-byte record");
        }
    }
}
