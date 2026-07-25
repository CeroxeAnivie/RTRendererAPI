package top.ceroxe.rt.renderer.rt.device;

import top.ceroxe.rt.renderer.api.RendererDeviceException;
import top.ceroxe.rt.renderer.api.RendererDeviceException.Reason;
import top.ceroxe.rt.renderer.api.RendererDeviceException.RecoveryAction;

public final class VulkanFailuresSelfTest {
   private VulkanFailuresSelfTest() {
   }

   public static void main(String[] arguments) {
      VulkanFailures.check(0, "success");
      requireClassification(-4, Reason.DEVICE_LOST, RecoveryAction.RECREATE_RENDERER);
      requireClassification(-2, Reason.DEVICE_OUT_OF_MEMORY, RecoveryAction.REDUCE_MEMORY_AND_RECREATE);
      requireClassification(-1, Reason.HOST_OUT_OF_MEMORY, RecoveryAction.ABORT);
      requireClassification(-3, Reason.DRIVER_FAILURE, RecoveryAction.ABORT);
      System.out.println("VulkanFailuresSelfTest passed");
   }

   private static void requireClassification(int result, RendererDeviceException.Reason reason, RendererDeviceException.RecoveryAction action) {
      RendererDeviceException failure = VulkanFailures.exception(result, "syntheticOperation");
      if (failure.reason() != reason || failure.recoveryAction() != action || failure.nativeResult() != result || !failure.operation().equals("syntheticOperation")) {
         throw new AssertionError("unexpected Vulkan failure classification: " + String.valueOf(failure));
      }
   }
}
