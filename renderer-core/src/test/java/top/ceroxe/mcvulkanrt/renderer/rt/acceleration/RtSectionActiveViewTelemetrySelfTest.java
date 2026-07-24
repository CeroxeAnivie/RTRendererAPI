package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import top.ceroxe.mcvulkanrt.renderer.RtBuildTelemetrySink;

import java.util.concurrent.atomic.AtomicLong;

/** Verifies deterministic active-view telemetry windows, deltas, and current-state diagnostics. */
public final class RtSectionActiveViewTelemetrySelfTest {
    private RtSectionActiveViewTelemetrySelfTest() {
    }

    public static void main(String[] arguments) {
        AtomicLong now = new AtomicLong(1L);
        CapturingSink sink = new CapturingSink();
        RtSectionActiveViewTelemetry telemetry = new RtSectionActiveViewTelemetry(sink, now::get);

        telemetry.cacheHit();
        require(telemetry.sampleDue(), "first enabled telemetry sample must establish a baseline");
        telemetry.publish(sample(1L, 1, false));
        require(sink.details == null, "baseline sampling must not emit a partial window");
        require(!telemetry.sampleDue(), "telemetry must not resample inside the one-second window");

        telemetry.cacheHit();
        telemetry.rebuild(true, false, true);
        telemetry.addAdmissionNanos(12_345L);
        telemetry.assembly(4_567L, 8_901L, true, 2, 1);
        telemetry.warmupPlanInvoked();
        telemetry.tlasBuildStatsRequested();
        now.set(1_000_000_002L);
        require(telemetry.sampleDue(), "elapsed telemetry window was not detected");
        telemetry.publish(sample(3L, 4, true));

        require("activeViewSnapshot".equals(sink.subsystem), "active-view telemetry subsystem changed");
        require(sink.details.contains("hits=1") && sink.details.contains("rebuilds=1"),
                "active-view cache deltas were not published");
        require(sink.details.contains("identityDelta={changes=1, added=2, removed=1}"),
                "active-view identity deltas were not published");
        require(sink.details.contains("sourceSections=4") && sink.details.contains("requiresView=true"),
                "telemetry must report the window-ending admission state, not the previous baseline");
        System.out.println("RtSectionActiveViewTelemetrySelfTest passed");
    }

    private static RtSectionActiveViewTelemetry.Sample sample(
            long planTotals,
            int sourceSections,
            boolean requiresView
    ) {
        return new RtSectionActiveViewTelemetry.Sample(
                planTotals,
                planTotals,
                planTotals,
                planTotals,
                planTotals,
                planTotals,
                planTotals,
                RtSectionTlasBuildInputCache.Stats.empty(),
                sourceSections,
                8,
                requiresView,
                requiresView,
                false,
                false,
                false
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class CapturingSink implements RtBuildTelemetrySink {
        private String subsystem;
        private String details;

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public void aggregate(String subsystem, String details) {
            this.subsystem = subsystem;
            this.details = details;
        }
    }
}
