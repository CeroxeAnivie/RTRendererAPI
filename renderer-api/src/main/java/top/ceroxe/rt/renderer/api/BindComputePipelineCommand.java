package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Binds one immutable compute pipeline outside a render pass. */
public record BindComputePipelineCommand(ComputePipelineState pipeline) implements RenderCommand {
    public BindComputePipelineCommand { Objects.requireNonNull(pipeline, "pipeline"); }
}
