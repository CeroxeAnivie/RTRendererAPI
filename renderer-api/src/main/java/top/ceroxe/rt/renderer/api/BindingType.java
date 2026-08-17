package top.ceroxe.rt.renderer.api;

/** Exact resource category expected by one shader binding. */
public enum BindingType {
    UNIFORM_BUFFER,
    READ_ONLY_STORAGE_BUFFER,
    READ_WRITE_STORAGE_BUFFER,
    SAMPLED_TEXTURE,
    READ_ONLY_STORAGE_TEXTURE,
    READ_WRITE_STORAGE_TEXTURE,
    SAMPLER,
    COMPARISON_SAMPLER,
    /**
     * One texture view and sampler bound atomically at one shader location.
     *
     * <p>This preserves the native descriptor shape emitted for conventional sampled-image
     * declarations. It is deliberately distinct from adjacent {@link #SAMPLED_TEXTURE} and
     * {@link #SAMPLER} bindings: consumers must never infer, split, or re-number this binding.</p>
     */
    COMBINED_IMAGE_SAMPLER,
    /** One top-level acceleration structure consumed by a ray-tracing shader. */
    ACCELERATION_STRUCTURE
}
