package top.ceroxe.rt.renderer.api;

/**
 * Declared access roles of a generic texture resource.
 */
public enum TextureUsage {
    COPY_SOURCE,
    COPY_DESTINATION,
    SAMPLED,
    STORAGE_READ,
    STORAGE_READ_WRITE,
    COLOR_ATTACHMENT,
    DEPTH_STENCIL_ATTACHMENT
}
