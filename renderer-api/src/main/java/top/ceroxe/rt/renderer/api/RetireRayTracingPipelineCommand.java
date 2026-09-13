package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Requests retirement of one exact session-owned ray-tracing pipeline generation.
 *
 * <p>The backend keeps the native pipeline, layout, descriptor sets, and SBT alive until every
 * submission that can reference the generation has completed. Obtain the handle from
 * {@link CommandExecutionEvidence#rayTracingPipelines()}. Bound, in-flight, foreign, stale, and
 * already retired handles are rejected with a typed command reason. Rebinding an equivalent
 * declaration after retirement creates a new generation; an old handle cannot retire it.</p>
 */
public record RetireRayTracingPipelineCommand(RayTracingPipelineHandle handle) implements RenderCommand {
    public RetireRayTracingPipelineCommand {
        handle = Objects.requireNonNull(handle, "handle");
    }
}
