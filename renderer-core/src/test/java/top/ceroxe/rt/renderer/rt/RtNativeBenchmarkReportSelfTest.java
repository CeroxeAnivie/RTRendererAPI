package top.ceroxe.rt.renderer.rt;

import top.ceroxe.rt.renderer.rt.runtime.RtCore;
import top.ceroxe.rt.renderer.rt.runtime.RtCore.RuntimeActivity;

public final class RtNativeBenchmarkReportSelfTest {
   private RtNativeBenchmarkReportSelfTest() {
   }

   public static void main(String[] arguments) {
      RtCore.RuntimeActivity activity = RuntimeActivity.unavailable();
      RtSceneReadiness readiness = RtSceneReadiness.unavailable();
      String throughput = RtNativeBenchmarkReport.throughputScene("staticDense", 1920, 1080, 600L, 1250.25, 875.5, activity, readiness);
      require(throughput.startsWith("RT_BENCHMARK scenario=staticDense extent=1920x1080"), "benchmark prefix or scenario identity changed");
      require(throughput.contains(" visualAssertions=passed "), "visual correctness result is absent from the performance record");
      require(throughput.contains(" completionMode=unpaced completedFps=1250.250 lowWindowFps=875.500 "), "throughput or low-window metrics are not locale-stable");
      String paced = RtNativeBenchmarkReport.pacedScene("dynamicLighting", 960, 540, 96L, 80.125, activity, readiness);
      require(paced.contains(" completionMode=hostPaced completedFps=80.125 lowWindowFps=unmeasured "), "paced scene was misrepresented as an unpaced throughput result");
      expectFailure(() -> RtNativeBenchmarkReport.throughputScene("staticDense", 1920, 1080, 1L, 1.0, 0.0 / 0.0, activity, readiness));
      expectFailure(() -> RtNativeBenchmarkReport.throughputScene("staticDense", 1920, 1080, 1L, 1.0, -1.0, activity, readiness));
      expectFailure(() -> RtNativeBenchmarkReport.pacedScene(" ", 1920, 1080, 1L, 1.0, activity, readiness));
      expectFailure(() -> RtNativeBenchmarkReport.pacedScene("dynamicLighting", 0, 1080, 1L, 1.0, activity, readiness));
      expectFailure(() -> RtNativeBenchmarkReport.pacedScene("dynamicLighting", 1920, 1080, -1L, 1.0, activity, readiness));
      System.out.println("RtNativeBenchmarkReportSelfTest passed");
   }

   private static void expectFailure(ThrowingRunnable action) {
      try {
         action.run();
      } catch (IllegalArgumentException value2) {
         return;
      }

      throw new AssertionError("expected IllegalArgumentException");
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
