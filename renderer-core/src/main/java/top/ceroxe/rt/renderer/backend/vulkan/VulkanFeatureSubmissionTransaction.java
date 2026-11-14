package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.feature.VulkanFeatureSession;

import java.util.Objects;

/**
 * Owns pending optional-feature execution evidence for one renderer queue submission.
 *
 * <p>Recording vendor work is not proof that Vulkan accepted it. This transaction commits only
 * after queue submission succeeds and otherwise discards the frame-local evidence from
 * {@link #close()}, including exceptional command-buffer finalization and queue-submit paths.</p>
 */
final class VulkanFeatureSubmissionTransaction implements AutoCloseable {
    private final VulkanFeatureSession session;
    private final long frameSequence;
    private boolean committed;

    VulkanFeatureSubmissionTransaction(VulkanFeatureSession session, long frameSequence) {
        this.session = Objects.requireNonNull(session, "session");
        if (frameSequence < 0L) throw new IllegalArgumentException("frameSequence must not be negative");
        this.frameSequence = frameSequence;
    }

    void commit() {
        if (committed) throw new IllegalStateException("feature submission evidence is already committed");
        session.commitFrameSubmission(frameSequence);
        committed = true;
    }

    @Override
    public void close() {
        if (!committed) session.discardFrameSubmission(frameSequence);
    }
}
