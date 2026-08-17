package top.ceroxe.rt.renderer.api;

/**
 * Direct non-indexed draw parameters.
 *
 * @param vertexCount non-negative vertices per instance; zero is an explicit no-op
 * @param instanceCount non-negative instance count; zero is an explicit no-op
 * @param firstVertex non-negative first logical vertex
 * @param firstInstance non-negative first logical instance
 */
public record DrawCommand(
        int vertexCount,
        int instanceCount,
        int firstVertex,
        int firstInstance
) implements RenderCommand {
    /** Validates all draw counts and logical offsets. */
    public DrawCommand {
        if (vertexCount < 0 || instanceCount < 0 || firstVertex < 0 || firstInstance < 0) {
            throw new IllegalArgumentException("draw counts and first indices must be non-negative");
        }
    }
}
