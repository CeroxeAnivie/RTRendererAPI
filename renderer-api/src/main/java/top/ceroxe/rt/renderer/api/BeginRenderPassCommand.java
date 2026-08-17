package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Begins one render pass with an exact attachment declaration. */
public record BeginRenderPassCommand(RenderPassDescriptor descriptor) implements RenderCommand {
    /** Rejects an absent pass descriptor. */
    public BeginRenderPassCommand {
        Objects.requireNonNull(descriptor, "descriptor");
    }
}
