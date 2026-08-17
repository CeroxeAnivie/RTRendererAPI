package top.ceroxe.rt.renderer.api;

/**
 * Direct indexed draw parameters.
 *
 * @param indexCount non-negative indices per instance; zero is an explicit no-op
 * @param instanceCount non-negative instance count; zero is an explicit no-op
 * @param firstIndex non-negative first bound-slice index
 * @param vertexOffset signed value added to each fetched index
 * @param firstInstance non-negative first logical instance
 */
public record DrawIndexedCommand(
        int indexCount,
        int instanceCount,
        int firstIndex,
        int vertexOffset,
        int firstInstance
) implements RenderCommand {
    /** Validates counts and unsigned logical offsets while preserving signed base-vertex semantics. */
    public DrawIndexedCommand {
        if (indexCount < 0 || instanceCount < 0 || firstIndex < 0 || firstInstance < 0) {
            throw new IllegalArgumentException("indexed draw counts and first indices must be non-negative");
        }
    }
}
