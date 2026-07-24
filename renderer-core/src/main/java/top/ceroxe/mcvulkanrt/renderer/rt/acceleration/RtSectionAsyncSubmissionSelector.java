package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;

import java.util.Objects;
import java.util.Set;

/** Selects the next CPU-recorded BLAS page that may enter the ordered Vulkan queue. */
final class RtSectionAsyncSubmissionSelector {
    private RtSectionAsyncSubmissionSelector() {
    }

    static int select(
            RtSectionAsyncBuildInventory inventory,
            long viewRevision,
            Set<SectionKey> preferredSections,
            Set<SectionKey> interactiveSections,
            boolean authoritativeViewEstablished,
            boolean foregroundCoverageIncomplete,
            boolean firstWorldFrontPending,
            boolean releaseCompletedRecordingForForegroundProgress
    ) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(preferredSections, "preferredSections");
        Objects.requireNonNull(interactiveSections, "interactiveSections");
        if (viewRevision < 0L) {
            throw new IllegalArgumentException("viewRevision must not be negative");
        }

        /*
         * A completed mutation page is the first preemption boundary after the
         * current native FIFO page. It must not wait behind camera streaming
         * work merely because both pages intersect the broad foreground set.
         */
        for (int index = 0; index < inventory.recordingBatchCount(); index++) {
            RtPendingSectionBlasRecording recording = inventory.recordingAt(index);
            if (!recording.isDone() || recording.activePreferredSectionCount(interactiveSections) == 0) {
                continue;
            }
            int activeForegroundSections = recording.activePreferredSectionCount(preferredSections);
            if (shouldSubmit(
                    authoritativeViewEstablished,
                    foregroundCoverageIncomplete,
                    firstWorldFrontPending,
                    activeForegroundSections
            )) {
                return index;
            }
        }

        for (int index = 0; index < inventory.recordingBatchCount(); index++) {
            RtPendingSectionBlasRecording recording = inventory.recordingAt(index);
            if (!recording.isDone()) {
                continue;
            }
            int activeForegroundSections = recording.activePreferredSectionCount(preferredSections);
            boolean submit = shouldSubmit(
                    authoritativeViewEstablished,
                    foregroundCoverageIncomplete,
                    firstWorldFrontPending,
                    activeForegroundSections
            );
            recording.recordSubmissionDecision(
                    viewRevision,
                    preferredSections,
                    authoritativeViewEstablished,
                    foregroundCoverageIncomplete,
                    firstWorldFrontPending,
                    activeForegroundSections,
                    submit
            );
            if (submit) {
                return index;
            }
        }

        if (releaseCompletedRecordingForForegroundProgress) {
            /*
             * A recording admitted by an older camera-local authority can finish after that
             * authority has moved. The strict foreground gate correctly gives a completed
             * current-front page first choice, but it must not turn every completed older page
             * into a permanent queue head while that current page is still recording.
             *
             * Submit exactly the oldest completed page as a liveness dependency. The caller has
             * already proved that the ordered GPU window is empty, and the one-page GPU window
             * stops this loop immediately after promotion. Consequently older work can delay the
             * current foreground by at most one bounded page; it cannot occupy the queue
             * recursively or relax the presentation coverage contract.
             */
            for (int index = 0; index < inventory.recordingBatchCount(); index++) {
                if (inventory.recordingAt(index).isDone()) {
                    return index;
                }
            }
        }
        return -1;
    }

    static boolean shouldReleaseCompletedRecordingForForegroundProgress(
            boolean foregroundGateActive,
            boolean foregroundBuildQueued
    ) {
        return foregroundGateActive && foregroundBuildQueued;
    }

    static boolean shouldSubmit(
            boolean authoritativeViewEstablished,
            boolean foregroundCoverageIncomplete,
            boolean firstWorldFrontPending,
            int activeForegroundSections
    ) {
        if (activeForegroundSections < 0) {
            throw new IllegalArgumentException("activeForegroundSections must not be negative");
        }
        return !foregroundGateActive(
                authoritativeViewEstablished,
                foregroundCoverageIncomplete,
                firstWorldFrontPending
        ) || activeForegroundSections > 0;
    }

    static boolean foregroundGateActive(
            boolean authoritativeViewEstablished,
            boolean foregroundCoverageIncomplete,
            boolean firstWorldFrontPending
    ) {
        return !authoritativeViewEstablished || foregroundCoverageIncomplete || firstWorldFrontPending;
    }
}
