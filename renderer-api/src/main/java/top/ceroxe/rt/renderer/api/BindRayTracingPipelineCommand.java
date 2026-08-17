package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Binds one validated ray-tracing pipeline outside a raster pass. */
public record BindRayTracingPipelineCommand(RayTracingPipelineState pipeline) implements RenderCommand {
    /** Rejects null pipeline state before transaction validation. */
    public BindRayTracingPipelineCommand {
        pipeline = Objects.requireNonNull(pipeline, "pipeline");
    }
}
