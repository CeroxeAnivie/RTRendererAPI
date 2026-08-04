package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities;
import top.ceroxe.rt.renderer.feature.VulkanFeatureSession;

/** Deterministic contract checks for queue-acceptance evidence commit and exceptional discard. */
public final class VulkanFeatureSubmissionTransactionSelfTest {
    private VulkanFeatureSubmissionTransactionSelfTest() {
    }

    public static void main(String[] args) {
        closeWithoutCommitDiscardsExactlyOnce();
        commitPreventsDiscard();
        failedCommitStillDiscards();
        System.out.println("VulkanFeatureSubmissionTransactionSelfTest passed");
    }

    private static void closeWithoutCommitDiscardsExactlyOnce() {
        RecordingSession session = new RecordingSession();
        try (var ignored = new VulkanFeatureSubmissionTransaction(session, 7L)) {
            // Simulates any recorder, command-buffer finalization, or queue-submit exception.
        }
        require(session.commits == 0 && session.discards == 1,
                "uncommitted evidence must be discarded exactly once");
    }

    private static void commitPreventsDiscard() {
        RecordingSession session = new RecordingSession();
        try (var transaction = new VulkanFeatureSubmissionTransaction(session, 11L)) {
            transaction.commit();
        }
        require(session.commits == 1 && session.discards == 0,
                "accepted submission evidence must not be discarded on close");
    }

    private static void failedCommitStillDiscards() {
        RecordingSession session = new RecordingSession();
        session.failCommit = true;
        expectFailure(() -> {
            try (var transaction = new VulkanFeatureSubmissionTransaction(session, 13L)) {
                transaction.commit();
            }
        });
        require(session.commits == 1 && session.discards == 1,
                "failed evidence commit must retain close-time discard protection");
    }

    private static void expectFailure(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected failure");
        } catch (IllegalStateException expected) {
            // Expected contract failure.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class RecordingSession implements VulkanFeatureSession {
        private int commits;
        private int discards;
        private boolean failCommit;

        @Override
        public RenderingFeatureCapabilities capabilities() {
            return RenderingFeatureCapabilities.builder().build();
        }

        @Override
        public void commitFrameSubmission(long frameSequence) {
            commits++;
            if (failCommit) throw new IllegalStateException("injected commit failure");
        }

        @Override
        public void discardFrameSubmission(long frameSequence) {
            discards++;
        }
    }
}
