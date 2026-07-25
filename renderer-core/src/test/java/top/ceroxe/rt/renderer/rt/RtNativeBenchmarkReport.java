package top.ceroxe.rt.renderer.rt;

import java.util.Locale;
import java.util.Objects;
import top.ceroxe.rt.renderer.rt.runtime.RtCore;

final class RtNativeBenchmarkReport {
   private static final String PREFIX = "RT_BENCHMARK";

   private RtNativeBenchmarkReport() {
   }

   static String pacedScene(String scenario, int width, int height, long completedFrames, double hostPacedCompletedFps, RtCore.RuntimeActivity activity, RtSceneReadiness readiness) {
      return format(scenario, width, height, completedFrames, "hostPaced", hostPacedCompletedFps, "unmeasured", activity, readiness);
   }

   static String throughputScene(String scenario, int width, int height, long completedFrames, double completedFps, double lowWindowFps, RtCore.RuntimeActivity activity, RtSceneReadiness readiness) {
      if (Double.isFinite(lowWindowFps) && !(lowWindowFps < 0.0)) {
         return format(scenario, width, height, completedFrames, "unpaced", completedFps, formatDecimal(lowWindowFps), activity, readiness);
      } else {
         throw new IllegalArgumentException("low-window FPS must be finite and non-negative");
      }
   }

   private static String format(String scenario, int width, int height, long completedFrames, String completionMode, double completedFps, String lowWindowFps, RtCore.RuntimeActivity activity, RtSceneReadiness readiness) {
      if (scenario != null && !scenario.isBlank()) {
         if (width > 0 && height > 0) {
            if (completedFrames >= 0L && Double.isFinite(completedFps) && !(completedFps < 0.0)) {
               Objects.requireNonNull(activity, "activity");
               Objects.requireNonNull(readiness, "readiness");
               return "RT_BENCHMARK scenario=" + scenario + " extent=" + width + "x" + height + " visualAssertions=passed completedFrames=" + completedFrames + " completionMode=" + completionMode + " completedFps=" + formatDecimal(completedFps) + " lowWindowFps=" + lowWindowFps + " frameReadbacks=" + activity.frameReadbacks() + " " + activity.gpuFrameTiming().asLogFragment() + " " + activity.gpuWorkTiming().asLogFragment() + " " + readiness.asLogFragment();
            } else {
               throw new IllegalArgumentException("completion measurements must be finite and non-negative");
            }
         } else {
            throw new IllegalArgumentException("benchmark extent must be positive");
         }
      } else {
         throw new IllegalArgumentException("scenario must not be blank");
      }
   }

   private static String formatDecimal(double value) {
      return String.format(Locale.ROOT, "%.3f", value);
   }
}
