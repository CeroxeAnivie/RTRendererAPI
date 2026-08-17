package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Binds one immutable graphics pipeline generation for subsequent draws. */
public record BindGraphicsPipelineCommand(GraphicsPipelineState pipeline) implements RenderCommand {
    /** Rejects an absent pipeline descriptor. */
    public BindGraphicsPipelineCommand {
        Objects.requireNonNull(pipeline, "pipeline");
    }
}
