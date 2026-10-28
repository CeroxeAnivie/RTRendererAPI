package top.ceroxe.rt.renderer.rt;

import java.util.List;
import java.util.Locale;
import top.ceroxe.rt.renderer.rt.RtNativeBenchmarkReport.CompletionMeasurement;
import top.ceroxe.rt.renderer.rt.RtNativeBenchmarkReport.CompletionRateTracker;
import top.ceroxe.rt.renderer.rt.runtime.RtCore;

/** Isolated synthetic observations exercise statistics and report labels without a GPU. */
public final class RtNativeBenchmarkReportSelfTest {
   private RtNativeBenchmarkReportSelfTest() { }

   public static void main(String[] arguments) {
      excludesBaselineAndLabelsBatchedWindows();
      retainsCompletionsAtEqualObservationTimes();
      aggregatesCountsOverTotalElapsedTime();
      rejectsCounterAndTimeReversals();
      reportsMeasurementScopeWithoutInventingDisplayMetrics();
      System.out.println("RtNativeBenchmarkReportSelfTest passed");
   }

   private static CompletionMeasurement fixture() {
      var tracker = new CompletionRateTracker(1000, 0, 8);
      tracker.observe(1004, 40_000_000);
      tracker.observe(1008, 80_000_000);
      tracker.observe(1011, 140_000_000);
      return tracker.finish(200_000_000);
   }

   private static void excludesBaselineAndLabelsBatchedWindows() {
      CompletionMeasurement measurement = fixture();
      require(measurement.completedFrames() == 11 && measurement.elapsedNanos() == 200_000_000,
              "warmup counts or time outside the measurement interval leaked into throughput");
      require(measurement.hostCompletedFps() == 55, "throughput must include the entire measured elapsed interval");
      require(measurement.minimumWindowFps() == 100 && measurement.windowSamples() == 1
                      && measurement.trailingWindowFrames() == 3,
              "minimum full-window throughput must exclude and disclose the partial trailing window");
      require(measurement.hostProgressBatches() == 3 && measurement.p99HostProgressGapMillis() == 60,
              "batched host observations must not be counted as individual frame-time samples");
      var shortRun = new CompletionRateTracker(50, 0, 8);
      shortRun.observe(52, 20_000_000);
      CompletionMeasurement shortMeasurement = shortRun.finish(30_000_000);
      require(Double.isNaN(shortMeasurement.minimumWindowFps()) && shortMeasurement.windowSamples() == 0
                      && shortMeasurement.windowDetails().contains("minFps=unmeasured"),
              "an incomplete window must not fabricate a zero or average low-FPS statistic");
   }

   private static void retainsCompletionsAtEqualObservationTimes() {
      var tracker = new CompletionRateTracker(7, 10, 2);
      tracker.observe(8, 10);
      tracker.observe(9, 20);
      CompletionMeasurement measurement = tracker.finish(20);
      require(measurement.completedFrames() == 2 && measurement.hostCompletedFps() == 200_000_000,
              "clock-resolution collisions must not discard valid completion counts");
   }

   private static void aggregatesCountsOverTotalElapsedTime() {
      var fast = new CompletionRateTracker(0, 0, 8);
      fast.observe(10, 1_000_000_000L);
      var slow = new CompletionRateTracker(1000, 0, 8);
      slow.observe(1010, 9_000_000_000L);
      double aggregate = RtNativeBenchmarkReport.aggregateHostCompletedFps(
              List.of(fast.finish(1_000_000_000L), slow.finish(9_000_000_000L)));
      require(aggregate == 2, "aggregate throughput must be total completions / total elapsed, not mean run FPS");
      expectFailure(() -> RtNativeBenchmarkReport.aggregateHostCompletedFps(List.of()));
   }

   private static void rejectsCounterAndTimeReversals() {
      var tracker = new CompletionRateTracker(10, 100, 8);
      expectFailure(() -> tracker.observe(9, 101));
      expectFailure(() -> tracker.observe(10, 99));
      tracker.observe(11, 110);
      expectFailure(() -> tracker.finish(109));
      tracker.finish(120);
      try { tracker.observe(12, 130); throw new AssertionError("ended interval accepted more observations"); }
      catch (IllegalStateException expected) { }
      expectFailure(() -> new CompletionRateTracker(-2, 0, 8));
      expectFailure(() -> new CompletionRateTracker(0, 0, 0));
      expectFailure(() -> new CompletionRateTracker(0, 0, 8).finish(0));
   }

   private static void reportsMeasurementScopeWithoutInventingDisplayMetrics() {
      RtCore.RuntimeActivity activity = RtCore.RuntimeActivity.unavailable();
      RtSceneReadiness readiness = RtSceneReadiness.unavailable();
      Locale previous = Locale.getDefault();
      try {
         Locale.setDefault(Locale.FRANCE);
         String throughput = RtNativeBenchmarkReport.throughputScene("residentAfterPressure", 1920, 1080,
                 fixture(), activity, readiness);
         require(throughput.startsWith("RT_BENCHMARK scenario=residentAfterPressure extent=1920x1080"),
                 "benchmark identity changed");
         require(throughput.contains("measurementSource=hostObservedCompletion completionMode=unpaced hostCompletedFps=55.000"),
                 "host completion throughput must be explicit and locale-stable");
         require(throughput.contains("kind=nonOverlappingHostCompletionBatches,minFrames=8,samples=1,minFps=100.000")
                         && throughput.contains("excludedTrailingFrames=3"), "completion window definition is absent");
         require(throughput.contains("displayFps=unmeasured onePercentLowFps=unmeasured gpuTimingScope=sessionCumulative"),
                 "host polling statistics must not claim display cadence, 1% lows or isolated GPU times");
         String paced = RtNativeBenchmarkReport.pacedScene("dynamicLighting", 960, 540, 96, 80.125, activity, readiness);
         require(paced.contains("completionMode=hostPaced hostCompletedFps=80.125 completionWindows=unmeasured"),
                 "paced scene must retain its distinct host pacing policy");
      } finally { Locale.setDefault(previous); }
      expectFailure(() -> RtNativeBenchmarkReport.pacedScene(" ", 1920, 1080, 1, 1, activity, readiness));
      expectFailure(() -> RtNativeBenchmarkReport.pacedScene("scene", 0, 1080, 1, 1, activity, readiness));
      expectFailure(() -> RtNativeBenchmarkReport.pacedScene("scene", 1920, 1080, -1, 1, activity, readiness));
      expectFailure(() -> RtNativeBenchmarkReport.pacedScene("scene", 1920, 1080, 1, Double.NaN, activity, readiness));
   }

   private static void expectFailure(Runnable action) {
      try { action.run(); } catch (IllegalArgumentException expected) { return; }
      throw new AssertionError("expected IllegalArgumentException");
   }

   private static void require(boolean condition, String message) {
      if (!condition) throw new AssertionError(message);
   }
}
