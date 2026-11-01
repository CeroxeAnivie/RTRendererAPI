package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.RendererConfig;
import top.ceroxe.rt.renderer.api.SceneShaderProgram;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.pipeline.GpuSceneRayTracingPipeline;

/** Session-owned pipelines. Submitted frames retain either variant until the frame ring drains. */
final class VulkanGpuScenePipelines implements AutoCloseable {
    private final VulkanDeviceRuntime device;
    private final RendererConfig configuration;
    private final boolean linearHdr;
    private final boolean builtIn;
    private final GpuSceneRayTracingPipeline primary;
    private GpuSceneRayTracingPipeline transport;

    VulkanGpuScenePipelines(VulkanDeviceRuntime device, RendererConfig configuration, boolean linearHdr) {
        this.device = device;
        this.configuration = configuration;
        this.linearHdr = linearHdr;
        builtIn = configuration.sceneShaders().equals(SceneShaderProgram.builtIn());
        primary = builtIn
                ? GpuSceneRayTracingPipeline.openSurface(device, configuration.maxFramesInFlight(), linearHdr)
                : GpuSceneRayTracingPipeline.open(device, configuration.maxFramesInFlight(), linearHdr,
                        configuration.sceneShaders());
    }

    GpuSceneRayTracingPipeline primary() {
        return primary;
    }

    GpuSceneRayTracingPipeline forFrame(boolean surfaceOnly) {
        if (!builtIn || surfaceOnly) return primary;
        // Creating the full variant never replaces or destroys the pipeline of an in-flight frame.
        // Custom stages always retain their original program, ABI, and recursion requirements.
        if (transport == null) {
            transport = GpuSceneRayTracingPipeline.open(device, configuration.maxFramesInFlight(), linearHdr,
                    configuration.sceneShaders());
        }
        return transport;
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        try {
            if (transport != null) transport.close();
        } catch (RuntimeException closeFailure) {
            failure = closeFailure;
        }
        try {
            primary.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) failure = closeFailure; else failure.addSuppressed(closeFailure);
        }
        if (failure != null) throw failure;
    }
}
