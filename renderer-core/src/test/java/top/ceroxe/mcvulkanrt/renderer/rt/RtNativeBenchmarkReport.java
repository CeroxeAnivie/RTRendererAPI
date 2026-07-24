package top.ceroxe.mcvulkanrt.renderer.rt;

import top.ceroxe.mcvulkanrt.renderer.rt.runtime.RtCore;

import java.util.Locale;
import java.util.Objects;

/**
 * Emits one machine-searchable summary line for every native 1080p scene lane.
 *
 * <p>Dynamic correctness scenes deliberately sleep while waiting for fresh GPU
 * results, so their host-observed completion rate is paced rather than a pure
 * throughput measurement.  Keeping that distinction in the field name prevents
 * a visually rich but host-paced scene from being compared to the unpaced dense
 * throughput lane as though both numbers measured the same thing.</p>
 */
final class RtNativeBenchmarkReport {
    private static final String PREFIX = "RT_BENCHMARK";

    private RtNativeBenchmarkReport() {
    }

    static String pacedScene(
            String scenario,
            int width,
            int height,
            long completedFrames,
            double hostPacedCompletedFps,
            RtCore.RuntimeActivity activity,
            RtSceneReadiness readiness
    ) {
        return format(
                scenario,
                width,
                height,
                completedFrames,
                "hostPaced",
                hostPacedCompletedFps,
                "unmeasured",
                activity,
                readiness
        );
    }

    static String throughputScene(
            String scenario,
            int width,
            int height,
            long completedFrames,
            double completedFps,
            double lowWindowFps,
            RtCore.RuntimeActivity activity,
            RtSceneReadiness readiness
    ) {
        return format(
                scenario,
                width,
                height,
                completedFrames,
                "unpaced",
                completedFps,
                formatDecimal(lowWindowFps),
                activity,
                readiness
        );
    }

    private static String format(
            String scenario,
            int width,
            int height,
            long completedFrames,
            String completionMode,
            double completedFps,
            String lowWindowFps,
            RtCore.RuntimeActivity activity,
            RtSceneReadiness readiness
    ) {
        if (scenario == null || scenario.isBlank()) {
            throw new IllegalArgumentException("scenario must not be blank");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("benchmark extent must be positive");
        }
        if (completedFrames < 0L || !Double.isFinite(completedFps) || completedFps < 0.0D) {
            throw new IllegalArgumentException("completion measurements must be finite and non-negative");
        }
        Objects.requireNonNull(activity, "activity");
        Objects.requireNonNull(readiness, "readiness");

        return PREFIX
                + " scenario=" + scenario
                + " extent=" + width + 'x' + height
                + " visualAssertions=passed"
                + " completedFrames=" + completedFrames
                + " completionMode=" + completionMode
                + " completedFps=" + formatDecimal(completedFps)
                + " lowWindowFps=" + lowWindowFps
                + " frameReadbacks=" + activity.frameReadbacks()
                + ' ' + activity.gpuFrameTiming().asLogFragment()
                + ' ' + activity.gpuWorkTiming().asLogFragment()
                + ' ' + readiness.asLogFragment();
    }

    private static String formatDecimal(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
