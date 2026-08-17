package top.ceroxe.rt.renderer.api;

/** Portable resource access categories used by explicit barriers. */
public enum RenderResourceAccess {
    HOST_READ,
    HOST_WRITE,
    COPY_READ,
    COPY_WRITE,
    INDIRECT_READ,
    VERTEX_READ,
    INDEX_READ,
    UNIFORM_READ,
    SHADER_READ,
    SHADER_WRITE,
    COLOR_ATTACHMENT_READ,
    COLOR_ATTACHMENT_WRITE,
    DEPTH_STENCIL_READ,
    DEPTH_STENCIL_WRITE,
    PRESENT_READ
}
