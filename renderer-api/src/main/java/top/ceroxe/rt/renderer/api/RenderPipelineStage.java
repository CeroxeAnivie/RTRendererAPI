package top.ceroxe.rt.renderer.api;

/** Portable execution stages used by explicit resource barriers. */
public enum RenderPipelineStage {
    HOST,
    COPY,
    INDIRECT,
    VERTEX_INPUT,
    VERTEX_SHADER,
    FRAGMENT_SHADER,
    EARLY_DEPTH_STENCIL,
    LATE_DEPTH_STENCIL,
    COLOR_ATTACHMENT_OUTPUT,
    COMPUTE_SHADER,
    RAY_TRACING_SHADER,
    PRESENT
}
