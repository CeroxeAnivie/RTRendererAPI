package top.ceroxe.rt.renderer.backend.vulkan;

import java.util.EnumMap;
import java.util.List;
import top.ceroxe.rt.renderer.api.FrameValidationException;
import top.ceroxe.rt.renderer.api.ResourceData;
import top.ceroxe.rt.renderer.api.RendererConfig;
import top.ceroxe.rt.renderer.api.SceneShaderProgram;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.pipeline.GpuSceneRayTracingPipeline;
import top.ceroxe.rt.renderer.rt.pipeline.GpuSceneRayTracingPipeline.Program;

/** Owns built-in specializations or one explicit expert program until all frame slots retire. */
final class VulkanGpuScenePipelines implements AutoCloseable {
    private final VulkanDeviceRuntime device;
    private final RendererConfig configuration;
    private final boolean linearHdr;
    private final EnumMap<Program, GpuSceneRayTracingPipeline> builtIns = new EnumMap<>(Program.class);
    private final GpuSceneRayTracingPipeline expert;

    VulkanGpuScenePipelines(VulkanDeviceRuntime device, RendererConfig configuration, boolean linearHdr) {
        this.device = device;
        this.configuration = configuration;
        this.linearHdr = linearHdr;
        if (configuration.sceneShaders().equals(SceneShaderProgram.builtIn())) {
            expert = null;
        } else {
            expert = GpuSceneRayTracingPipeline.open(device, configuration.maxFramesInFlight(),
                    linearHdr, configuration.sceneShaders());
        }
    }

    private GpuSceneRayTracingPipeline create(Program program) {
        return GpuSceneRayTracingPipeline.openBuiltIn(device, configuration.maxFramesInFlight(), linearHdr, program);
    }

    void validateShaderData(List<ResourceData> values) {
        if (expert != null) {
            expert.validateShaderData(values);
        } else if (!values.isEmpty()) {
            throw new FrameValidationException(FrameValidationException.Reason.INVALID_SHADER_DATA,
                    "built-in scene shaders declare no application data bindings");
        }
    }

    GpuSceneRayTracingPipeline photons() {
        if (expert != null) throw new FrameValidationException(FrameValidationException.Reason.INVALID_CAUSTICS,
                "built-in photon maps require built-in scene shaders; expert programs own their light transport");
        return builtIns.computeIfAbsent(Program.PHOTONS, this::create);
    }

    GpuSceneRayTracingPipeline forFrame(Program program) {
        if (expert != null && (program == Program.COMPACT_CAUSTICS || program == Program.GENERAL_CAUSTICS))
            throw new FrameValidationException(FrameValidationException.Reason.INVALID_CAUSTICS,
                    "built-in caustics cannot replace an expert scene program");
        return expert != null ? expert : builtIns.computeIfAbsent(program, this::create);
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        var owned = expert != null ? List.of(expert) : builtIns.values();
        for (var pipeline : owned) {
            try {
                pipeline.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) throw failure;
    }
}
