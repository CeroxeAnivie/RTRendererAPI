package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

import top.ceroxe.mcvulkanrt.renderer.RendererViewState;
import top.ceroxe.mcvulkanrt.renderer.SectionRevisionSnapshot;
import top.ceroxe.mcvulkanrt.renderer.scene.PackedSectionMembership;

/**
 * Single-writer host snapshot of the most recently completed GPU frame.
 *
 * <p>Completion metadata is a transaction: sequence, scene revisions, section
 * membership and readback evidence must advance together. This object prevents
 * resize and completion paths from publishing a partially updated front.</p>
 */
final class RtCompletedFrameFront {
    private long frameReadbacks;
    private RtFrameSnapshot frameReadback;
    private RtGBufferSnapshot gBufferReadback;
    private long completedGBufferCaptures;
    private long frameStateSequence = -1L;
    private long dynamicSceneRevision = -1L;
    private PackedSectionMembership sectionKeys = PackedSectionMembership.empty();
    private SectionRevisionSnapshot sectionContentRevisions = SectionRevisionSnapshot.empty();
    private RendererViewState viewState = RendererViewState.allResident();
    private long dispatchOrdinal;

    void accept(RtPendingFrameSubmission pending, RtFrameCompletionPublisher.Completion completion) {
        dispatchOrdinal = pending.dispatchOrdinal();
        frameStateSequence = pending.frameStateSequence();
        dynamicSceneRevision = pending.dynamicSceneRevision();
        sectionKeys = pending.sectionKeys();
        sectionContentRevisions = pending.sectionContentRevisions();
        viewState = pending.viewState();
        if (completion.frameReadback() != null) {
            frameReadbacks++;
            frameReadback = completion.frameReadback();
        }
        if (completion.gBufferReadback() != null) {
            gBufferReadback = completion.gBufferReadback();
            completedGBufferCaptures++;
        }
    }

    /** Invalidates image-bound proof while retaining the latest diagnostic G-buffer capture. */
    void resetForOutputReplacement() {
        frameReadback = null;
        frameStateSequence = -1L;
        sectionKeys = PackedSectionMembership.empty();
        sectionContentRevisions = SectionRevisionSnapshot.empty();
        viewState = RendererViewState.allResident();
        dispatchOrdinal = 0L;
    }

    long frameReadbacks() {
        return frameReadbacks;
    }

    RtFrameSnapshot frameReadback() {
        return frameReadback;
    }

    RtGBufferSnapshot gBufferReadback() {
        return gBufferReadback;
    }

    long completedGBufferCaptures() {
        return completedGBufferCaptures;
    }

    long frameStateSequence() {
        return frameStateSequence;
    }

    long dynamicSceneRevision() {
        return dynamicSceneRevision;
    }

    PackedSectionMembership sectionKeys() {
        return sectionKeys;
    }

    SectionRevisionSnapshot sectionContentRevisions() {
        return sectionContentRevisions;
    }

    RendererViewState viewState() {
        return viewState;
    }

    long dispatchOrdinal() {
        return dispatchOrdinal;
    }
}
