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
    COMPARISON_SAMPLER
}
