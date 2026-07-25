package top.ceroxe.rt.renderer.rt.device;

public final class VulkanRtSchedulingConfigSelfTest {
   private static final String STALENESS_PROPERTY = "top.ceroxe.rt.rt.scheduler.maxConvergenceVisualStalenessMillis";

   private VulkanRtSchedulingConfigSelfTest() {
   }

   public static void main(String[] arguments) {
      assertValidatedValuesRemainStable();
      assertInteractiveRadiusCannotExceedLimit();
      assertDurationConversionRejectsOverflow();
      System.out.println("VulkanRtSchedulingConfigSelfTest passed");
   }

   private static void assertValidatedValuesRemainStable() {
      VulkanRtSchedulingConfig config = new VulkanRtSchedulingConfig(32L, 50000000L, 16000000L, 4L, 100000L, 2L, 12L, 500000L);
      require(config.maxPendingFrameAgeBeforeBuildMillis() == 32L, "pending-frame age changed during validation");
      require(config.maxConvergenceVisualStalenessNanos() == 50000000L, "visual staleness changed units during validation");
      require(config.foregroundFrameBudgetNanos() == 500000L, "foreground budget changed units during validation");
   }

   private static void assertInteractiveRadiusCannotExceedLimit() {
      expect(IllegalArgumentException.class, () -> new VulkanRtSchedulingConfig(32L, 50000000L, 16000000L, 4L, 100000L, 13L, 12L, 500000L));
   }

   private static void assertDurationConversionRejectsOverflow() {
      String previous = System.getProperty("top.ceroxe.rt.rt.scheduler.maxConvergenceVisualStalenessMillis");
      System.setProperty("top.ceroxe.rt.rt.scheduler.maxConvergenceVisualStalenessMillis", Long.toString(9223372036854775807L));

      try {
         expect(IllegalArgumentException.class, VulkanRtSchedulingConfig::fromSystemProperties);
      } finally {
         if (previous == null) {
            System.clearProperty("top.ceroxe.rt.rt.scheduler.maxConvergenceVisualStalenessMillis");
         } else {
            System.setProperty("top.ceroxe.rt.rt.scheduler.maxConvergenceVisualStalenessMillis", previous);
         }

      }

   }

   private static <T extends Throwable> void expect(Class<T> type, ThrowingRunnable action) {
      try {
         action.run();
      } catch (Throwable failure) {
         if (type.isInstance(failure)) {
            return;
         }

         throw new AssertionError("expected " + type.getName() + " but caught " + String.valueOf(failure), failure);
      }

      throw new AssertionError("expected " + type.getName() + " but no exception was thrown");
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }

   @FunctionalInterface
   private interface ThrowingRunnable {
      void run();
   }
}
