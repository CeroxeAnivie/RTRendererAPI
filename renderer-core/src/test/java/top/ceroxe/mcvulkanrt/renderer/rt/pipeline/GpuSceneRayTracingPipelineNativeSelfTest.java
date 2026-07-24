package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

import top.ceroxe.mcvulkanrt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.mcvulkanrt.renderer.rt.device.VulkanDeviceRuntime;

/** Creates the production GPUScene descriptor layout, native pipeline, and uploaded SBT. */
public final class GpuSceneRayTracingPipelineNativeSelfTest {
    private GpuSceneRayTracingPipelineNativeSelfTest() {
    }

    public static void main(String[] arguments) {
        VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
        require(capability.hardwareRayTracingReady(),
                "GPUScene pipeline gate requires hardware RT: " + capability.summary());
        try (VulkanDeviceRuntime device = VulkanDeviceRuntime.open(capability);
             GpuSceneRayTracingPipeline pipeline = GpuSceneRayTracingPipeline.open(device, 6)) {
            GpuSceneRayTracingPipeline.Snapshot snapshot = pipeline.snapshot();
            require(snapshot.descriptorSetCount() == 6
                            && snapshot.pipeline() != 0L
                            && snapshot.pipelineLayout() != 0L
                            && snapshot.shaderBindingTableBytes() > 0,
                    "native GPUScene pipeline snapshot is incomplete: " + snapshot);
            System.out.println("GpuSceneRayTracingPipelineNativeSelfTest passed: device="
                    + capability.preferredDevice().name() + ", snapshot=" + snapshot);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
