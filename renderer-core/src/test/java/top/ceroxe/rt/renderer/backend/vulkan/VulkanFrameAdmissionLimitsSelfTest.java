package top.ceroxe.rt.renderer.backend.vulkan;

public final class VulkanFrameAdmissionLimitsSelfTest {
   private VulkanFrameAdmissionLimitsSelfTest() {
   }

   public static void main(String[] args) throws Exception {
      VulkanFrameAdmissionLimits limits = new VulkanFrameAdmissionLimits(16384, 1000000L);
      limits.validate(1000, 1000);
      expectRejected(() -> limits.validate(16385, 1));
      expectRejected(() -> limits.validate(1, 16385));
      expectRejected(() -> limits.validate(1001, 1000));
      expectIllegalArgument(() -> new VulkanFrameAdmissionLimits(0, 1L));
      expectIllegalArgument(() -> new VulkanFrameAdmissionLimits(1, 0L));
      System.out.println("VulkanFrameAdmissionLimitsSelfTest passed");
   }

   private static void expectRejected(CheckedAction action) throws Exception {
      try {
         action.run();
         throw new AssertionError("expected SubmissionRejectedException");
      } catch (VulkanRenderingSession.SubmissionRejectedException value2) {
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
