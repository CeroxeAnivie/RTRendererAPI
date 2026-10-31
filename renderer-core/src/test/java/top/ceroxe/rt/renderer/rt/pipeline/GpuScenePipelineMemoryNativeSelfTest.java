package top.ceroxe.rt.renderer.rt.pipeline;

import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.diagnostics.NativeMemoryDiagnostics;

/** One pipeline per process: distinguish creation/background compilation from frame storage. */
public final class GpuScenePipelineMemoryNativeSelfTest {
    public static void main(String[] arguments) throws InterruptedException {
        if (arguments.length != 2) throw new IllegalArgumentException("PROGRAM idleMillis required");
        var program = GpuSceneRayTracingPipeline.Program.valueOf(arguments[0]);
        long idleMillis = Long.parseLong(arguments[1]);
        if (idleMillis < 0 || idleMillis > 120_000) throw new IllegalArgumentException("idleMillis outside 0..120000");
        var capability = VulkanRtCapabilityProbe.capture();
        if (!capability.hardwareRayTracingReady()) throw new IllegalStateException(capability.summary());
        try (var device = VulkanDeviceRuntime.open(capability)) {
            NativeMemoryDiagnostics.checkpoint("isolation.device", 0, 0);
            try (var pipeline = GpuSceneRayTracingPipeline.openBuiltIn(device, 1, true, program)) {
                NativeMemoryDiagnostics.checkpoint("isolation.created." + program, pipeline.snapshot().pipeline(), 0);
                System.out.println("PipelineMemory created " + program);
                Thread.sleep(idleMillis);
                NativeMemoryDiagnostics.checkpoint("isolation.idled." + program, pipeline.snapshot().pipeline(), 0);
            }
            NativeMemoryDiagnostics.checkpoint("isolation.released." + program, 0, 0);
        }
        System.out.println("PipelineMemory released " + program);
    }
}
