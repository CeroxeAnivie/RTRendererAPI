package top.ceroxe.rt.renderer.rt.acceleration;

import top.ceroxe.rt.renderer.rt.device.RtCommandContext;

import java.util.Objects;

/**
 * Publishes one completed CPU BLAS recording into ordered GPU-submission ownership.
 */
final class RtSectionBlasSubmissionTransaction {
    private RtSectionBlasSubmissionTransaction() {
    }

    static RtPendingSectionBlasBuild promote(
            RtSectionAsyncBuildInventory inventory,
            RtPendingBlasBuildOwnership ownership,
            RtCommandContext commandContext,
            int recordingIndex,
            RtPendingSectionBlasRecording recording,
            RtPendingSectionBlasRecording.SubmittedBuild submitted
    ) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(ownership, "ownership");
        Objects.requireNonNull(commandContext, "commandContext");
        Objects.requireNonNull(recording, "recording");
        Objects.requireNonNull(submitted, "submitted");

        RtAccelerationStructure.SectionBlasBuildSubmission nativeSubmission = null;
        RtPendingSectionBlasBuild pending = null;
        try {
            nativeSubmission = submitted.recorded().submit(commandContext);
            pending = new RtPendingSectionBlasBuild(
                    nativeSubmission,
                    recording.batch(),
                    recording.backgroundAdmission(),
                    recording.foregroundSubmission(),
                    recording.sequence()
            );
            recording.applyInvalidations(pending);
            inventory.promoteRecording(recordingIndex, recording, pending);
            return pending;
        } catch (RuntimeException | Error failure) {
            closeAfterFailedPublication(pending, nativeSubmission, failure);
            inventory.removeRecordingAt(recordingIndex);
            ownership.releaseAsync(recording.meshes());
            throw failure;
        }
    }

    private static void closeAfterFailedPublication(
            RtPendingSectionBlasBuild pending,
            RtAccelerationStructure.SectionBlasBuildSubmission nativeSubmission,
            Throwable failure
    ) {
        try {
            if (pending != null) {
                pending.close();
            } else if (nativeSubmission != null) {
                nativeSubmission.close();
            }
        } catch (RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}
