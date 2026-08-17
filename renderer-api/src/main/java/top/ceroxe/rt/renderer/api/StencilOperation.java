package top.ceroxe.rt.renderer.api;

/** Update applied to an eight-bit stencil value. */
public enum StencilOperation {
    KEEP,
    ZERO,
    REPLACE,
    INCREMENT_AND_CLAMP,
    DECREMENT_AND_CLAMP,
    INVERT,
    INCREMENT_AND_WRAP,
    DECREMENT_AND_WRAP
}
