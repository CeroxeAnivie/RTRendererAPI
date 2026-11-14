package top.ceroxe.rt.renderer.rt.device;

public final class VulkanQueueTimelineSelfTest {
   private VulkanQueueTimelineSelfTest() {
   }

   public static void main(String[] args) {
      selectsOnlySynchronizableDualQueueTopology();
      publishesOnlyFenceObservedValues();
      rejectsUnsubmittedCompletion();
      System.out.println("VulkanQueueTimelineSelfTest passed");
   }

   private static void selectsOnlySynchronizableDualQueueTopology() {
      require(RtDeviceQueueContexts.requestedQueueCount(1, true) == 1, "one available queue must retain the ordered fallback");
      require(RtDeviceQueueContexts.requestedQueueCount(2, false) == 1, "queue separation without timeline synchronization must be rejected");
      require(RtDeviceQueueContexts.requestedQueueCount(2, true) == 2, "two queues plus timeline support must enable the asynchronous build lane");
      require(RtDeviceQueueContexts.requestedQueueCount(3, true) == 3,
              "three queues plus timeline support must isolate blocking presentation");
      require(RtDeviceQueueContexts.requestedQueueCount(4, true, 0, 2) == 2,
              "preferred provider queues must take precedence over an optional presentation lane");
      require(RtDeviceQueueContexts.requestedQueueCount(4, true, 1, 2) == 1,
              "renderer lanes must collapse before provider-owned queues alias the frame queue");
      require(RtDeviceQueueContexts.requestedQueueCount(3, true, 1, 2) == 2,
              "an all-or-none preferred request must be omitted when it would consume the renderer queue");
      expectIllegalState(() -> RtDeviceQueueContexts.requestedQueueCount(2, true, 2, 0));
   }

   private static void publishesOnlyFenceObservedValues() {
      VulkanQueueTimeline.Watermark watermark = new VulkanQueueTimeline.Watermark();
      long first = watermark.reserveSignalValue();
      long second = watermark.reserveSignalValue();
      watermark.markSubmitted(first);
      watermark.markSubmitted(second);
      require(watermark.completedValue() == 0L, "submission alone must not unblock frame consumption");
      watermark.markCompleted(first);
      require(watermark.completedValue() == first, "fence observation must publish its exact watermark");
      watermark.markCompleted(second);
      require(watermark.completedValue() == second, "completed watermark must advance monotonically");
   }

   private static void rejectsUnsubmittedCompletion() {
      VulkanQueueTimeline.Watermark watermark = new VulkanQueueTimeline.Watermark();
      long value = watermark.reserveSignalValue();

      try {
         watermark.markCompleted(value);
         throw new AssertionError("unsubmitted timeline completion was accepted");
      } catch (IllegalArgumentException value4) {
      }
   }

   private static void expectIllegalState(Runnable action) {
      try {
         action.run();
         throw new AssertionError("expected IllegalStateException");
      } catch (IllegalStateException expected) {
         // Required provider queues may never consume the renderer's last queue.
      }
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}
