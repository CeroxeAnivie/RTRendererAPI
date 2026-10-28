package top.ceroxe.rt.renderer.rt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import top.ceroxe.rt.renderer.rt.runtime.RtCore;

/** Reports host-observed completion separately from cumulative GPU timestamp telemetry. */
public final class RtNativeBenchmarkReport {
   private RtNativeBenchmarkReport() { }

   static String pacedScene(String scenario, int width, int height, long completedFrames,
           double hostPacedCompletedFps, RtCore.RuntimeActivity activity, RtSceneReadiness readiness) {
      return format(scenario, width, height, completedFrames, "hostPaced", hostPacedCompletedFps,
              "unmeasured", activity, readiness);
   }

   static String throughputScene(String scenario, int width, int height, CompletionMeasurement measurement,
           RtCore.RuntimeActivity activity, RtSceneReadiness readiness) {
      Objects.requireNonNull(measurement, "measurement");
      return format(scenario, width, height, measurement.completedFrames(), "unpaced",
              measurement.hostCompletedFps(), measurement.windowDetails(), activity, readiness)
              + " measurementElapsedNanos=" + measurement.elapsedNanos()
              + " p95HostProgressGapMillis=" + formatDecimal(measurement.p95HostProgressGapMillis())
              + " p99HostProgressGapMillis=" + formatDecimal(measurement.p99HostProgressGapMillis())
              + " maxHostProgressGapMillis=" + formatDecimal(measurement.maxHostProgressGapMillis());
   }

   private static String format(String scenario, int width, int height, long completedFrames,
           String completionMode, double completedFps, String windowDetails,
           RtCore.RuntimeActivity activity, RtSceneReadiness readiness) {
      if (scenario == null || scenario.isBlank()) throw new IllegalArgumentException("scenario must not be blank");
      if (width <= 0 || height <= 0) throw new IllegalArgumentException("benchmark extent must be positive");
      if (completedFrames < 0 || !Double.isFinite(completedFps) || completedFps < 0)
         throw new IllegalArgumentException("completion measurements must be finite and non-negative");
      Objects.requireNonNull(activity, "activity");
      Objects.requireNonNull(readiness, "readiness");
      return "RT_BENCHMARK scenario=" + scenario + " extent=" + width + "x" + height
              + " visualAssertions=passed completedFrames=" + completedFrames
              + " measurementSource=hostObservedCompletion completionMode=" + completionMode
              + " hostCompletedFps=" + formatDecimal(completedFps)
              + " completionWindows=" + windowDetails
              + " displayFps=unmeasured onePercentLowFps=unmeasured gpuTimingScope=sessionCumulative"
              + " frameReadbacks=" + activity.frameReadbacks() + " " + activity.gpuFrameTiming().asLogFragment()
              + " " + activity.gpuWorkTiming().asLogFragment() + " " + readiness.asLogFragment();
   }

   /** Aggregate counts and elapsed time; arithmetic means of run FPS are not throughput. */
   public static double aggregateHostCompletedFps(List<CompletionMeasurement> measurements) {
      if (measurements.isEmpty()) throw new IllegalArgumentException("measurements must not be empty");
      long frames = 0, elapsed = 0;
      for (CompletionMeasurement measurement : measurements) {
         frames = Math.addExact(frames, measurement.completedFrames());
         elapsed = Math.addExact(elapsed, measurement.elapsedNanos());
      }
      return frames * 1e9 / elapsed;
   }

   /**
    * Host polling observations can batch several completions. Progress gaps are observation gaps,
    * and windows are non-overlapping batches containing at least the requested frame count.
    * They cannot recover individual frame times, display cadence, or a 1% low statistic.
    */
   public static final class CompletionRateTracker {
      private final long baselineCompleted;
      private final long startNanos;
      private final int windowFrames;
      private final ArrayList<Long> progressGaps = new ArrayList<>();
      private long lastCompleted, lastObservationNanos, lastProgressNanos, windowStartNanos;
      private long pendingWindowFrames, windowSamples;
      private double minimumWindowFps = Double.NaN;
      private boolean finished;

      public CompletionRateTracker(long baselineCompleted, long startNanos, int windowFrames) {
         if (baselineCompleted < -1 || windowFrames < 1) throw new IllegalArgumentException("invalid completion baseline or window");
         this.baselineCompleted = this.lastCompleted = baselineCompleted;
         this.startNanos = this.lastObservationNanos = this.lastProgressNanos = this.windowStartNanos = startNanos;
         this.windowFrames = windowFrames;
      }

      public void observe(long completed, long nowNanos) {
         if (finished) throw new IllegalStateException("completion measurement has ended");
         if (completed < lastCompleted || nowNanos < lastObservationNanos)
            throw new IllegalArgumentException("completion counts and observation time must be monotonic");
         lastObservationNanos = nowNanos;
         long delta = completed - lastCompleted;
         if (delta == 0) return;
         progressGaps.add(nowNanos - lastProgressNanos);
         lastProgressNanos = nowNanos;
         lastCompleted = completed;
         pendingWindowFrames = Math.addExact(pendingWindowFrames, delta);
         if (pendingWindowFrames >= windowFrames && nowNanos > windowStartNanos) {
            double rate = pendingWindowFrames * 1e9 / (nowNanos - windowStartNanos);
            minimumWindowFps = Double.isNaN(minimumWindowFps) ? rate : Math.min(minimumWindowFps, rate);
            windowSamples++;
            pendingWindowFrames = 0;
            windowStartNanos = nowNanos;
         }
      }

      public long completedFrames() { return lastCompleted - baselineCompleted; }

      public CompletionMeasurement finish(long endNanos) {
         if (finished) throw new IllegalStateException("completion measurement has ended");
         if (endNanos < lastObservationNanos || endNanos <= startNanos)
            throw new IllegalArgumentException("measurement end must follow its start and observations");
         finished = true;
         long[] sorted = progressGaps.stream().mapToLong(Long::longValue).toArray();
         Arrays.sort(sorted);
         return new CompletionMeasurement(completedFrames(), endNanos - startNanos, windowFrames,
                 windowSamples, pendingWindowFrames, minimumWindowFps, sorted.length,
                 percentileMillis(sorted, .95), percentileMillis(sorted, .99),
                 sorted.length == 0 ? Double.NaN : sorted[sorted.length - 1] / 1e6);
      }
   }

   public record CompletionMeasurement(long completedFrames, long elapsedNanos, int minimumWindowFrames,
           long windowSamples, long trailingWindowFrames, double minimumWindowFps, long hostProgressBatches,
           double p95HostProgressGapMillis, double p99HostProgressGapMillis, double maxHostProgressGapMillis) {
      public CompletionMeasurement {
         if (completedFrames < 0 || elapsedNanos <= 0 || minimumWindowFrames < 1 || windowSamples < 0
                 || trailingWindowFrames < 0 || trailingWindowFrames > completedFrames || hostProgressBatches < 0)
            throw new IllegalArgumentException("invalid completion measurement counters");
         if (windowSamples == 0 ? !Double.isNaN(minimumWindowFps)
                 : !Double.isFinite(minimumWindowFps) || minimumWindowFps < 0)
            throw new IllegalArgumentException("minimum window rate requires an observed full window");
      }
      public double hostCompletedFps() { return completedFrames * 1e9 / elapsedNanos; }
      public String windowDetails() {
         return "{kind=nonOverlappingHostCompletionBatches,minFrames=" + minimumWindowFrames
                 + ",samples=" + windowSamples + ",minFps=" + formatDecimal(minimumWindowFps)
                 + ",excludedTrailingFrames=" + trailingWindowFrames + ",hostProgressBatches=" + hostProgressBatches + "}";
      }
      @Override public String toString() {
         return "{completedFrames=" + completedFrames + ",elapsedNanos=" + elapsedNanos
                 + ",hostCompletedFps=" + formatDecimal(hostCompletedFps()) + ",completionWindows=" + windowDetails()
                 + ",p95HostProgressGapMillis=" + formatDecimal(p95HostProgressGapMillis)
                 + ",p99HostProgressGapMillis=" + formatDecimal(p99HostProgressGapMillis)
                 + ",maxHostProgressGapMillis=" + formatDecimal(maxHostProgressGapMillis) + "}";
      }
   }

   private static double percentileMillis(long[] sorted, double quantile) {
      return sorted.length == 0 ? Double.NaN : sorted[(int)Math.ceil(quantile * sorted.length) - 1] / 1e6;
   }

   private static String formatDecimal(double value) {
      return Double.isNaN(value) ? "unmeasured" : String.format(Locale.ROOT, "%.3f", value);
   }
}
