package top.ceroxe.rt.renderer.rt.device;

import top.ceroxe.rt.renderer.RtBuildTelemetrySink;

public final class VulkanAcceptFrameTimingSelfTest {
   private VulkanAcceptFrameTimingSelfTest() {
   }

   public static void main(String[] arguments) {
      VulkanAcceptFrameTiming timing = new VulkanAcceptFrameTiming(RtBuildTelemetrySink.NOOP);
      timing.recordPreBuildDispatchOutcome(true, true);
      timing.recordPreBuildDispatchOutcome(false, false);
      timing.recordBuildBudgetDeferral();
      timing.recordForegroundBudgetDeferral();
      timing.recordStableFrameFastPathDispatch();
      timing.recordStableFramePendingGpuSkip();
      timing.recordDynamicWorldTlasDispatchBlock("sceneNotReady");
      String summary = timing.admissionSummary();
      requireContains(summary, "framePendingBuildBudgetDeferrals=1");
      requireContains(summary, "foregroundFrameBudgetDeferrals=1");
      requireContains(summary, "foregroundFrameFirstDispatches=1");
      requireContains(summary, "foregroundFrameCoverageDeferrals=1");
      requireContains(summary, "stableFrameFastPathDispatches=1");
      requireContains(summary, "stableFramePendingGpuSkips=1");
      requireContains(summary, "dynamicWorldTlasDispatchBlocks=1");
      requireContains(summary, "lastDynamicWorldTlasDispatchBlockReason=sceneNotReady");
      expect(NullPointerException.class, () -> timing.recordDynamicWorldTlasDispatchBlock((String)null));
      expect(IllegalArgumentException.class, () -> timing.recordDynamicWorldTlasDispatchBlock(" "));
      System.out.println("VulkanAcceptFrameTimingSelfTest passed");
   }

   private static void requireContains(String value, String expected) {
      if (!value.contains(expected)) {
         throw new AssertionError("expected telemetry fragment '" + expected + "' in: " + value);
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

   @FunctionalInterface
   private interface ThrowingRunnable {
      void run();
   }
}
