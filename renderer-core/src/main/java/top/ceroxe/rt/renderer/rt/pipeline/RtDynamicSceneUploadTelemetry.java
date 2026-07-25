package top.ceroxe.rt.renderer.rt.pipeline;

import top.ceroxe.rt.renderer.RtBuildTelemetrySink;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Aggregates dirty dynamic-scene SSBO ranges without retaining frame buffers.
 *
 * <p>This owner deliberately receives the ABI ranges from the pipeline instead
 * of reaching into pipeline constants. That keeps the byte-layout definition in
 * one place while making telemetry independently testable and impossible to
 * mutate the upload decision.</p>
 */
final class RtDynamicSceneUploadTelemetry {
    private static final long WINDOW_NANOS = 1_000_000_000L;

    private final RtBuildTelemetrySink telemetry;
    private final List<Range> ranges;
    private final long[] changedFrames;
    private final long[] changedBytes;
    private long samples;
    private long windowStartNanos;

    RtDynamicSceneUploadTelemetry(RtBuildTelemetrySink telemetry, List<Range> ranges) {
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.ranges = List.copyOf(Objects.requireNonNull(ranges, "ranges"));
        if (this.ranges.isEmpty()) {
            throw new IllegalArgumentException("dynamic scene telemetry requires at least one range");
        }
        this.changedFrames = new long[this.ranges.size()];
        this.changedBytes = new long[this.ranges.size()];
    }

    private static int changedBytes(List<RtRayTracingPipeline.UploadRange> dirtyRanges, Range range) {
        int changed = 0;
        int rangeStart = range.offsetBytes();
        int rangeEnd = range.endOffsetBytes();
        for (RtRayTracingPipeline.UploadRange dirty : dirtyRanges) {
            int overlapStart = Math.max(rangeStart, dirty.offsetBytes());
            int overlapEnd = Math.min(rangeEnd, dirty.endOffsetBytes());
            changed += Math.max(0, overlapEnd - overlapStart);
        }
        return changed;
    }

    void record(List<RtRayTracingPipeline.UploadRange> dirtyRanges) {
        Objects.requireNonNull(dirtyRanges, "dirtyRanges");
        if (!telemetry.enabled()) {
            return;
        }
        for (int index = 0; index < ranges.size(); index++) {
            Range range = ranges.get(index);
            int changed = changedBytes(dirtyRanges, range);
            if (changed > 0) {
                changedFrames[index]++;
                changedBytes[index] += changed;
            }
        }
        recordWindow();
    }

    private void recordWindow() {
        long now = System.nanoTime();
        if (windowStartNanos == 0L) {
            windowStartNanos = now;
        }
        samples++;
        if (now - windowStartNanos < WINDOW_NANOS) {
            return;
        }
        StringBuilder details = new StringBuilder("samples=").append(samples).append(", ranges={");
        for (int index = 0; index < ranges.size(); index++) {
            if (index > 0) {
                details.append(',');
            }
            Range range = ranges.get(index);
            details.append(range.name())
                    .append(":frames=").append(changedFrames[index])
                    .append("/bytes=").append(changedBytes[index])
                    .append("/capacity=").append(range.byteCount());
        }
        details.append('}');
        telemetry.aggregate("dynamicSceneRangeDiff", details.toString());
        windowStartNanos = now;
        samples = 0L;
        Arrays.fill(changedFrames, 0L);
        Arrays.fill(changedBytes, 0L);
    }

    record Range(String name, int firstRecord, int recordCount) {
        Range {
            if (name == null || name.isBlank() || firstRecord < 0 || recordCount <= 0) {
                throw new IllegalArgumentException("dynamic scene range must be named and non-empty");
            }
        }

        private int offsetBytes() {
            return Math.multiplyExact(firstRecord, 16);
        }

        private int byteCount() {
            return Math.multiplyExact(recordCount, 16);
        }

        private int endOffsetBytes() {
            return Math.addExact(offsetBytes(), byteCount());
        }
    }
}
