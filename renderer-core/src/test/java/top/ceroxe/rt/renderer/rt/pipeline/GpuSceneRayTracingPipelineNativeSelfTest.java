package top.ceroxe.rt.renderer.rt.pipeline;

import java.io.PrintStream;
import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

public final class GpuSceneRayTracingPipelineNativeSelfTest {
   private GpuSceneRayTracingPipelineNativeSelfTest() {
   }

   public static void main(String[] arguments) {
      VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
      require(capability.hardwareRayTracingReady(), "GPUScene pipeline gate requires hardware RT: " + capability.summary());
      VulkanDeviceRuntime device = VulkanDeviceRuntime.open(capability);

      try {
         GpuSceneRayTracingPipeline pipeline = GpuSceneRayTracingPipeline.open(device, 6, false);

         try {
            GpuSceneRayTracingPipeline.Snapshot snapshot = pipeline.snapshot();
            require(snapshot.descriptorSetCount() == 6 && snapshot.pipeline() != 0L && snapshot.pipelineLayout() != 0L && snapshot.shaderBindingTableBytes() > 0, "native GPUScene pipeline snapshot is incomplete: " + String.valueOf(snapshot));
            PrintStream output10000 = System.out;
            String details10001 = capability.preferredDevice().name();
            output10000.println("GpuSceneRayTracingPipelineNativeSelfTest passed: device=" + details10001 + ", snapshot=" + String.valueOf(snapshot));
         } catch (Throwable value8) {
            if (pipeline != null) {
               try {
                  pipeline.close();
               } catch (Throwable value7) {
                  value8.addSuppressed(value7);
               }
            }

            throw value8;
         }

         if (pipeline != null) {
            pipeline.close();
         }
      } catch (Throwable value9) {
         if (device != null) {
            try {
               device.close();
            } catch (Throwable value6) {
               value9.addSuppressed(value6);
            }
         }

         throw value9;
      }

      if (device != null) {
         device.close();
      }

   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}
