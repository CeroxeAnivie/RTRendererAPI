package top.ceroxe.rt.renderer.api;

import java.util.Set;

/** Shared fail-closed validation for portable barrier stage/access masks. */
final class RenderBarrierValidator {
    private RenderBarrierValidator() { }

    static void validate(
            Set<RenderPipelineStage> sourceStages,
            Set<RenderResourceAccess> sourceAccess,
            Set<RenderPipelineStage> destinationStages,
            Set<RenderResourceAccess> destinationAccess
    ) {
        validateSide(sourceStages, sourceAccess, "source");
        validateSide(destinationStages, destinationAccess, "destination");
    }

    static void validateBufferUsage(BufferResource buffer, Set<RenderResourceAccess> accesses) {
        for (RenderResourceAccess access : accesses) {
            boolean supported = switch (access) {
                case COPY_READ -> buffer.usage().contains(BufferUsage.COPY_SOURCE);
                case COPY_WRITE -> buffer.usage().contains(BufferUsage.COPY_DESTINATION);
                case INDIRECT_READ -> buffer.usage().contains(BufferUsage.INDIRECT);
                case VERTEX_READ -> buffer.usage().contains(BufferUsage.VERTEX);
                case INDEX_READ -> buffer.usage().contains(BufferUsage.INDEX);
                case UNIFORM_READ -> buffer.usage().contains(BufferUsage.UNIFORM);
                case SHADER_READ -> buffer.usage().contains(BufferUsage.STORAGE_READ)
                        || buffer.usage().contains(BufferUsage.STORAGE_READ_WRITE);
                case SHADER_WRITE -> buffer.usage().contains(BufferUsage.STORAGE_READ_WRITE);
                case HOST_READ, HOST_WRITE -> true;
                default -> false;
            };
            if (!supported) throw new IllegalArgumentException("buffer barrier access is not declared by its resource: " + access);
        }
    }

    static void validateTextureUsage(TextureResource texture, Set<RenderResourceAccess> accesses) {
        for (RenderResourceAccess access : accesses) {
            boolean supported = switch (access) {
                case COPY_READ -> texture.usage().contains(TextureUsage.COPY_SOURCE);
                case COPY_WRITE -> texture.usage().contains(TextureUsage.COPY_DESTINATION);
                case SHADER_READ -> texture.usage().contains(TextureUsage.SAMPLED)
                        || texture.usage().contains(TextureUsage.STORAGE_READ)
                        || texture.usage().contains(TextureUsage.STORAGE_READ_WRITE);
                case SHADER_WRITE -> texture.usage().contains(TextureUsage.STORAGE_READ_WRITE);
                case COLOR_ATTACHMENT_READ, COLOR_ATTACHMENT_WRITE ->
                        texture.usage().contains(TextureUsage.COLOR_ATTACHMENT);
                case DEPTH_STENCIL_READ, DEPTH_STENCIL_WRITE ->
                        texture.usage().contains(TextureUsage.DEPTH_STENCIL_ATTACHMENT);
                case PRESENT_READ, HOST_READ, HOST_WRITE -> true;
                default -> false;
            };
            if (!supported) throw new IllegalArgumentException("texture barrier access is not declared by its resource: " + access);
        }
    }

    private static void validateSide(
            Set<RenderPipelineStage> stages,
            Set<RenderResourceAccess> accesses,
            String side
    ) {
        for (RenderResourceAccess access : accesses) {
            boolean compatible = stages.stream().anyMatch(stage -> supports(stage, access));
            if (!compatible) {
                throw new IllegalArgumentException(side + " barrier access has no compatible execution stage: " + access);
            }
        }
    }

    private static boolean supports(RenderPipelineStage stage, RenderResourceAccess access) {
        return switch (access) {
            case HOST_READ, HOST_WRITE -> stage == RenderPipelineStage.HOST;
            case COPY_READ, COPY_WRITE -> stage == RenderPipelineStage.COPY;
            case INDIRECT_READ -> stage == RenderPipelineStage.INDIRECT;
            case VERTEX_READ, INDEX_READ -> stage == RenderPipelineStage.VERTEX_INPUT;
            case UNIFORM_READ, SHADER_READ, SHADER_WRITE -> isShader(stage);
            case COLOR_ATTACHMENT_READ, COLOR_ATTACHMENT_WRITE ->
                    stage == RenderPipelineStage.COLOR_ATTACHMENT_OUTPUT;
            case DEPTH_STENCIL_READ, DEPTH_STENCIL_WRITE ->
                    stage == RenderPipelineStage.EARLY_DEPTH_STENCIL
                            || stage == RenderPipelineStage.LATE_DEPTH_STENCIL;
            case PRESENT_READ -> stage == RenderPipelineStage.PRESENT;
        };
    }

    private static boolean isShader(RenderPipelineStage stage) {
        return stage == RenderPipelineStage.VERTEX_SHADER
                || stage == RenderPipelineStage.FRAGMENT_SHADER
                || stage == RenderPipelineStage.COMPUTE_SHADER
                || stage == RenderPipelineStage.RAY_TRACING_SHADER;
    }
}
