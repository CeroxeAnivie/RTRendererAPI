package top.ceroxe.rt.renderer.rt.device;

public final class RtDeviceFrameAdmissionSelfTest {
   private RtDeviceFrameAdmissionSelfTest() {
   }

   public static void main(String[] args) {
      appliesStableFrameBackpressureOnlyAtSubmissionCapacity();
      requiresExternalMemoryAndSemaphoreForSharedPresentation();
      System.out.println("RtDeviceFrameAdmissionSelfTest passed");
   }

   private static void appliesStableFrameBackpressureOnlyAtSubmissionCapacity() {
      require(!VulkanRtDeviceContext.shouldApplyStableFrameSubmissionBackpressure(true, true, 0L, 2L), "pending GPU work must not serialize a frame ring that still has submission capacity");
      require(VulkanRtDeviceContext.shouldApplyStableFrameSubmissionBackpressure(false, true, 0L, 2L), "a saturated resident frame ring should apply bounded producer backpressure");
      require(!VulkanRtDeviceContext.shouldApplyStableFrameSubmissionBackpressure(false, true, 2L, 2L), "stale pending work must keep polling the full scheduler instead of being hidden");
      require(!VulkanRtDeviceContext.shouldApplyStableFrameSubmissionBackpressure(false, false, 0L, 2L), "an idle GPU queue must never trigger stable-frame backpressure");
      require(expectFailure(() -> VulkanRtDeviceContext.shouldApplyStableFrameSubmissionBackpressure(false, true, -1L, 2L)) instanceof IllegalArgumentException, "negative pending frame ages must be rejected");
   }

   private static void requiresExternalMemoryAndSemaphoreForSharedPresentation() {
      require(VulkanRtExternalFrameInterop.sharedPresentationReady(true, true, true), "GPU shared presentation should be ready only when interop, memory, and semaphore probes all pass");
      require(!VulkanRtExternalFrameInterop.sharedPresentationReady(false, true, true), "GPU shared presentation must stay disabled when external interop is not a candidate");
      require(!VulkanRtExternalFrameInterop.sharedPresentationReady(true, false, true), "GPU shared presentation must stay disabled when Vulkan external memory is unavailable");
      require(!VulkanRtExternalFrameInterop.sharedPresentationReady(true, true, false), "GPU shared presentation must stay disabled when Vulkan external semaphore sync is unavailable");
   }

   private static RuntimeException expectFailure(Runnable runnable) {
      try {
         runnable.run();
      } catch (RuntimeException exception) {
         return exception;
      }

      throw new AssertionError("expected failure did not occur");
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}
