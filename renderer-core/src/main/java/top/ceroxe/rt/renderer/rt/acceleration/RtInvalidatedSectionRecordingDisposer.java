package top.ceroxe.rt.renderer.rt.acceleration;

import top.ceroxe.rt.renderer.scene.SectionKey;

import java.util.Objects;
import java.util.Set;

/**
 * Drains fully invalidated CPU recordings without leaking completed native build resources.
 */
final class RtInvalidatedSectionRecordingDisposer {
    private RtInvalidatedSectionRecordingDisposer() {
    }

    static void discard(
            RtSectionAsyncBuildInventory inventory,
            RtPendingBlasBuildOwnership ownership,
            RtSectionAsyncWorkerTelemetry telemetry,
            Set<SectionKey> preferredSections
    ) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(ownership, "ownership");
        Objects.requireNonNull(telemetry, "telemetry");
        Objects.requireNonNull(preferredSections, "preferredSections");

        int index = 0;
        while (index < inventory.recordingBatchCount()) {
            RtPendingSectionBlasRecording recording = inventory.recordingAt(index);
            if (recording.activeSectionCount() != 0) {
                index++;
                continue;
            }
            if (inventory.cancelRecordingIfQueued(index, recording)) {
                ownership.releaseAsync(recording.meshes());
                continue;
            }
            if (!recording.isDone()) {
                /* A running worker must publish its native recording before it can be closed. */
                index++;
                continue;
            }

            inventory.removeRecordingAt(index);
            try {
                RtPendingSectionBlasRecording.SubmittedBuild submitted = recording.complete();
                try {
                    telemetry.completed(recording, submitted.submitElapsedNanos(), preferredSections);
                } catch (RuntimeException | Error diagnosticsFailure) {
                    closeSuppressing(submitted, diagnosticsFailure);
                    throw diagnosticsFailure;
                }
                submitted.recorded().close();
            } catch (RuntimeException | Error failure) {
                telemetry.failed(recording);
                throw failure;
            } finally {
                ownership.releaseAsync(recording.meshes());
            }
        }
    }

    private static void closeSuppressing(
            RtPendingSectionBlasRecording.SubmittedBuild submitted,
            Throwable failure
    ) {
        try {
            submitted.recorded().close();
        } catch (RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}
