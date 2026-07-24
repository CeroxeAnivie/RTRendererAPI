package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;

import java.util.Objects;
import java.util.Set;

/** Correlates CPU BLAS recording lifecycle evidence with scheduler statistics. */
final class RtSectionAsyncWorkerTelemetry {
    private final RtSectionBlasStatistics statistics;

    RtSectionAsyncWorkerTelemetry(RtSectionBlasStatistics statistics) {
        this.statistics = Objects.requireNonNull(statistics, "statistics");
    }

    void started(RtPendingSectionBlasRecording recording, long viewRevision, Set<SectionKey> preferredSections) {
        Objects.requireNonNull(recording, "recording");
        Objects.requireNonNull(preferredSections, "preferredSections");
        statistics.workerStarted(
                recording.sequence(), recording.activeSectionCount(), recording.activeTriangleCount()
        );
        recording.recordCreated(viewRevision, preferredSections);
    }

    void completed(
            RtPendingSectionBlasRecording recording,
            long elapsedNanos,
            Set<SectionKey> preferredSections
    ) {
        Objects.requireNonNull(recording, "recording");
        Objects.requireNonNull(preferredSections, "preferredSections");
        statistics.workerCompleted(
                recording.sequence(),
                recording.activeSectionCount(),
                recording.activeTriangleCount(),
                elapsedNanos
        );
        recording.recordCpuCompleted(elapsedNanos, preferredSections);
    }

    void failed(RtPendingSectionBlasRecording recording) {
        Objects.requireNonNull(recording, "recording");
        statistics.workerFailed(
                recording.sequence(), recording.activeSectionCount(), recording.activeTriangleCount()
        );
    }
}
