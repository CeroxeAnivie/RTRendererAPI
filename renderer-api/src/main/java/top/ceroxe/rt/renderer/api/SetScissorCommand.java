package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Sets the exact framebuffer scissor consumed by subsequent draws in the active pass. */
public record SetScissorCommand(ScissorRectangle scissor) implements RenderCommand {
    /** Rejects an absent scissor rectangle. */
    public SetScissorCommand {
        Objects.requireNonNull(scissor, "scissor");
    }
}
