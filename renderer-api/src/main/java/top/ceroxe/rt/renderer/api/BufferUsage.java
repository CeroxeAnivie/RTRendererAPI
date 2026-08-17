package top.ceroxe.rt.renderer.api;

/**
 * Declared access roles of a generic buffer resource.
 */
public enum BufferUsage {
    COPY_SOURCE,
    COPY_DESTINATION,
    VERTEX,
    INDEX,
    UNIFORM,
    STORAGE_READ,
    STORAGE_READ_WRITE,
    INDIRECT,
    /** Permits this buffer to supply geometry or instance input to an acceleration-structure build. */
    ACCELERATION_STRUCTURE_BUILD_INPUT
}
