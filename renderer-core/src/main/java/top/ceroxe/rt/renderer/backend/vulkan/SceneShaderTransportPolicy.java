package top.ceroxe.rt.renderer.backend.vulkan;

import java.util.List;
import top.ceroxe.rt.renderer.api.FrameValidationException;
import top.ceroxe.rt.renderer.api.ResourceData;
import top.ceroxe.rt.renderer.api.SceneShaderProgram;
import top.ceroxe.rt.renderer.api.ShaderStage;

/** Executable-stage ownership is independent from the application descriptor layout. */
final class SceneShaderTransportPolicy {
    private SceneShaderTransportPolicy() { }

    static boolean usesBuiltInStages(SceneShaderProgram program) {
        return program.sources().isEmpty()
                && !program.hasSpirv(ShaderStage.RAY_GENERATION)
                && !program.hasSpirv(ShaderStage.RAY_MISS)
                && !program.hasSpirv(ShaderStage.RAY_CLOSEST_HIT)
                && !program.hasSpirv(ShaderStage.RAY_ANY_HIT);
    }

    static void validateDataCount(SceneShaderProgram program, List<ResourceData> data) {
        if (data.size() != program.dataBindingCount()) {
            throw new FrameValidationException(FrameValidationException.Reason.INVALID_SHADER_DATA,
                    "frame shader data count must equal declared dataBindingCount=" + program.dataBindingCount());
        }
    }
}
