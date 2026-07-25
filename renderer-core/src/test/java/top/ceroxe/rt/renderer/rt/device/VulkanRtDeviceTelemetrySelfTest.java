package top.ceroxe.rt.renderer.rt.device;

public final class VulkanRtDeviceTelemetrySelfTest {
   private VulkanRtDeviceTelemetrySelfTest() {
   }

   public static void main(String[] args) {
      testIndependentSlowPathThrottles();
      testStreamingBindWindow();
      testWorldTlasSampling();
      System.out.println("VulkanRtDeviceTelemetrySelfTest passed");
   }

   private static void testIndependentSlowPathThrottles() {
      VulkanRtDeviceTelemetry telemetry = new VulkanRtDeviceTelemetry(8L);
      require(!telemetry.shouldLogSlowPreBuild(1999999L, 2000000000L), "sub-threshold pre-build duration was logged");
      require(telemetry.shouldLogSlowPreBuild(2000000L, 2000000000L), "first slow pre-build sample was suppressed");
      require(!telemetry.shouldLogSlowPreBuild(2000000L, 2999999999L), "pre-build throttle admitted a sample before its interval");
      require(telemetry.shouldLogSlowPreBuild(2000000L, 3000000000L), "pre-build throttle rejected its interval boundary");
      require(telemetry.shouldLogSlowPreBuildPolicy(2000000L, 2000000000L), "policy diagnostics incorrectly shared the pre-build throttle");
   }

   private static void testStreamingBindWindow() {
      VulkanRtDeviceTelemetry telemetry = new VulkanRtDeviceTelemetry(8L);
      telemetry.rememberStreamingSceneBind(false, 25L, 100L);
      require(telemetry.nextStreamingSceneBindNanos() == 0L, "non-streaming update changed the bind window");
      telemetry.rememberStreamingSceneBind(true, 25L, 100L);
      require(telemetry.nextStreamingSceneBindNanos() == 125L, "streaming bind window did not retain its deadline");
      expectIllegalArgument(() -> telemetry.rememberStreamingSceneBind(true, -1L, 100L));
   }

   private static void testWorldTlasSampling() {
      VulkanRtDeviceTelemetry telemetry = new VulkanRtDeviceTelemetry(8L);

      for(int sample = 1; sample <= 4; ++sample) {
         require(telemetry.shouldLogWorldTlasBound(), "initial bind sample was suppressed");
      }

      for(int sample = 5; sample < 8; ++sample) {
         require(!telemetry.shouldLogWorldTlasBound(), "intermediate bind sample was logged");
      }

      require(telemetry.shouldLogWorldTlasBound(), "periodic bind sample was suppressed");
      require(!telemetry.shouldLogWorldTlasDispatch(1L, 1L, true, true), "unchanged dispatch count was logged");
      require(!telemetry.shouldLogWorldTlasDispatch(1L, 0L, false, true), "unready world TLAS dispatch was logged");
      require(telemetry.shouldLogWorldTlasDispatch(1L, 0L, true, true), "ready dispatch was not logged after an earlier readiness rejection");
      require(!telemetry.shouldLogWorldTlasDispatch(1L, 0L, true, true), "same dispatch was logged twice");
      require(!telemetry.shouldLogWorldTlasDispatch(5L, 4L, true, true), "non-periodic dispatch was logged after the initial window");
      require(telemetry.shouldLogWorldTlasDispatch(8L, 7L, true, true), "periodic dispatch was suppressed");
   }

   private static void expectIllegalArgument(Runnable action) {
      try {
         action.run();
         throw new AssertionError("expected IllegalArgumentException");
      } catch (IllegalArgumentException value2) {
      }
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}
