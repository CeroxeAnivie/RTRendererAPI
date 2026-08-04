package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.FrameValidationException;

import static top.ceroxe.rt.renderer.api.FrameValidationException.Reason.OUTPUT_EXTENT_EXCEEDS_DEVICE_LIMIT;
import static top.ceroxe.rt.renderer.api.FrameValidationException.Reason.RAY_DISPATCH_EXCEEDS_DEVICE_LIMIT;

public final class VulkanFrameAdmissionLimitsSelfTest {
   private VulkanFrameAdmissionLimitsSelfTest() {
   }

   public static void main(String[] args) throws Exception {
      VulkanFrameAdmissionLimits limits = new VulkanFrameAdmissionLimits(16384, 1000000L);
      limits.validate(1000, 1000);
      expectValidation(OUTPUT_EXTENT_EXCEEDS_DEVICE_LIMIT, () -> limits.validate(16385, 1));
      expectValidation(OUTPUT_EXTENT_EXCEEDS_DEVICE_LIMIT, () -> limits.validate(1, 16385));
      expectValidation(RAY_DISPATCH_EXCEEDS_DEVICE_LIMIT, () -> limits.validate(1001, 1000));
      expectIllegalArgument(() -> new VulkanFrameAdmissionLimits(0, 1L));
      expectIllegalArgument(() -> new VulkanFrameAdmissionLimits(1, 0L));
      System.out.println("VulkanFrameAdmissionLimitsSelfTest passed");
   }

   private static void expectValidation(
           FrameValidationException.Reason reason,
           CheckedAction action
   ) throws Exception {
      try {
         action.run();
         throw new AssertionError("expected FrameValidationException");
      } catch (FrameValidationException failure) {
         if (failure.reason() != reason) throw new AssertionError("unexpected validation reason", failure);
      }
   }

   private static void expectIllegalArgument(Runnable action) {
      try {
         action.run();
         throw new AssertionError("expected IllegalArgumentException");
      } catch (IllegalArgumentException value2) {
      }
   }

   @FunctionalInterface
   private interface CheckedAction {
      void run() throws Exception;
   }
}
