package top.ceroxe.rt.renderer.api;

/** Executable shader stages recognized by the generic rendering contract. */
public enum ShaderStage {
    VERTEX,
    TESSELLATION_CONTROL,
    TESSELLATION_EVALUATION,
    GEOMETRY,
    FRAGMENT,
    COMPUTE,
    RAY_GENERATION,
    RAY_MISS,
    RAY_CLOSEST_HIT,
    RAY_ANY_HIT,
    RAY_INTERSECTION,
    CALLABLE
}
