package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

import top.ceroxe.mcvulkanrt.renderer.RendererFrameCausality;
import top.ceroxe.mcvulkanrt.renderer.RendererViewState;
import top.ceroxe.mcvulkanrt.renderer.SectionRevisionSnapshot;
import top.ceroxe.mcvulkanrt.renderer.rt.device.RtCommandContext;
import top.ceroxe.mcvulkanrt.renderer.rt.device.RtGpuImage;
import top.ceroxe.mcvulkanrt.renderer.rt.device.RtGpuTimestampPool;
import top.ceroxe.mcvulkanrt.renderer.rt.device.interop.VulkanWin32ExternalSemaphoreProbe;
import top.ceroxe.mcvulkanrt.renderer.rt.runtime.RtCore;
import top.ceroxe.mcvulkanrt.renderer.scene.PackedSectionMembership;

import java.util.Objects;

/**
 * Frozen ownership proof for an RT submission that has not reached GPU completion.
 *
 * <p>Every scene/publication field is captured before the submission enters the FIFO. Completion
 * must consume this immutable proof rather than re-read mutable pipeline state, otherwise a newer
 * descriptor generation could be incorrectly attributed to an older completed image.</p>
 */
final class RtPendingFrameSubmission {
    private final RtCommandContext.AsyncSubmission submission;
    private final RtGpuTimestampPool.Capture gpuTimestamps;
    private final RtPipelineFrameSlot frameSlot;
    private final long frameStateSequence;
    private final RendererFrameCausality causality;
    private final PackedSectionMembership sectionKeys;
    private final SectionRevisionSnapshot sectionContentRevisions;
    private final RendererViewState viewState;
    private final long dispatchOrdinal;
    private final RtGpuImage outputImage;
    private final boolean captureReadback;
    private final boolean captureGBuffer;
    private final String gBufferCaptureReason;
    private final VulkanWin32ExternalSemaphoreProbe.ExportedSemaphore signalSemaphore;
    private final long submittedNanos;
    private final long observedFrameStatesAtSubmit;
    private final RtCore.ScenePublicationState scenePublicationState;
    private final long descriptorGeneration;
    private final long dynamicSceneRevision;
    private final long boundTlasDynamicSceneRevision;
    private long polls;

    RtPendingFrameSubmission(
            RtCommandContext.AsyncSubmission submission,
            RtGpuTimestampPool.Capture gpuTimestamps,
            RtPipelineFrameSlot frameSlot,
            long frameStateSequence,
            RendererFrameCausality causality,
            RtCore.ScenePublicationState scenePublicationState,
            PackedSectionMembership sectionKeys,
            SectionRevisionSnapshot sectionContentRevisions,
            RendererViewState viewState,
            long dispatchOrdinal,
            RtGpuImage outputImage,
            boolean captureReadback,
            boolean captureGBuffer,
            String gBufferCaptureReason,
            VulkanWin32ExternalSemaphoreProbe.ExportedSemaphore signalSemaphore,
            long submittedNanos,
            long observedFrameStatesAtSubmit,
            long descriptorGeneration,
            long dynamicSceneRevision,
            long boundTlasDynamicSceneRevision
    ) {
        this.submission = Objects.requireNonNull(submission, "submission");
        this.gpuTimestamps = gpuTimestamps;
        this.frameSlot = Objects.requireNonNull(frameSlot, "frameSlot");
        if (frameStateSequence < 0L) {
            throw new IllegalArgumentException("pending frame sequence must not be negative");
        }
        this.frameStateSequence = frameStateSequence;
        this.causality = Objects.requireNonNull(causality, "causality");
        if (causality.frameSequence() != frameStateSequence) {
            throw new IllegalArgumentException("pending frame causality must match its frame sequence");
        }
        this.scenePublicationState = Objects.requireNonNull(scenePublicationState, "scenePublicationState");
        this.sectionKeys = Objects.requireNonNull(sectionKeys, "sectionKeys");
        this.sectionContentRevisions = Objects.requireNonNull(sectionContentRevisions, "sectionContentRevisions");
        if (this.sectionContentRevisions.membership() != this.sectionKeys) {
            throw new IllegalArgumentException("pending frame revisions must retain the exact section membership publication");
        }
        this.viewState = Objects.requireNonNull(viewState, "viewState");
        if (dispatchOrdinal <= 0L) {
            throw new IllegalArgumentException("pending frame dispatch ordinal must be positive");
        }
        this.dispatchOrdinal = dispatchOrdinal;
        this.outputImage = Objects.requireNonNull(outputImage, "outputImage");
        if (outputImage != frameSlot.outputImage()) {
            throw new IllegalArgumentException("pending frame output image must belong to its frame slot");
        }
        this.captureReadback = captureReadback;
        this.captureGBuffer = captureGBuffer;
        if (captureGBuffer != (gBufferCaptureReason != null)) {
            throw new IllegalArgumentException("G-buffer capture flag and reason must agree");
        }
        this.gBufferCaptureReason = gBufferCaptureReason;
        this.signalSemaphore = signalSemaphore;
        if (submittedNanos <= 0L || observedFrameStatesAtSubmit <= 0L || descriptorGeneration <= 0L) {
            throw new IllegalArgumentException("pending frame submission metadata must be positive");
        }
        this.submittedNanos = submittedNanos;
        this.observedFrameStatesAtSubmit = observedFrameStatesAtSubmit;
        this.descriptorGeneration = descriptorGeneration;
        if (!this.scenePublicationState.available()
                || this.scenePublicationState.descriptorGeneration() != descriptorGeneration
                || this.scenePublicationState.sectionCount() != this.sectionKeys.size()
                || this.scenePublicationState.viewRevision() != this.viewState.revision()
                || this.scenePublicationState.dynamicSceneRevision() != boundTlasDynamicSceneRevision) {
            throw new IllegalArgumentException("pending frame publication proof must match descriptor and frozen coverage");
        }
        if (dynamicSceneRevision < 0L || boundTlasDynamicSceneRevision < 0L) {
            throw new IllegalArgumentException("pending frame dynamic scene revisions must not be negative");
        }
        this.dynamicSceneRevision = dynamicSceneRevision;
        this.boundTlasDynamicSceneRevision = boundTlasDynamicSceneRevision;
    }

    RtCommandContext.AsyncSubmission submission() { return submission; }
    RtGpuTimestampPool.Capture gpuTimestamps() { return gpuTimestamps; }
    RtPipelineFrameSlot frameSlot() { return frameSlot; }
    long frameStateSequence() { return frameStateSequence; }
    RendererFrameCausality causality() { return causality; }
    RtCore.ScenePublicationState scenePublicationState() { return scenePublicationState; }
    PackedSectionMembership sectionKeys() { return sectionKeys; }
    SectionRevisionSnapshot sectionContentRevisions() { return sectionContentRevisions; }
    RendererViewState viewState() { return viewState; }
    long dispatchOrdinal() { return dispatchOrdinal; }
    RtGpuImage outputImage() { return outputImage; }
    boolean captureReadback() { return captureReadback; }
    boolean captureGBuffer() { return captureGBuffer; }

    String gBufferCaptureReason() {
        if (gBufferCaptureReason == null) {
            throw new IllegalStateException("pending frame has no G-buffer capture reason");
        }
        return gBufferCaptureReason;
    }

    VulkanWin32ExternalSemaphoreProbe.ExportedSemaphore signalSemaphore() { return signalSemaphore; }
    long submittedNanos() { return submittedNanos; }
    long observedFrameStatesAtSubmit() { return observedFrameStatesAtSubmit; }
    long descriptorGeneration() { return descriptorGeneration; }
    long dynamicSceneRevision() { return dynamicSceneRevision; }
    long boundTlasDynamicSceneRevision() { return boundTlasDynamicSceneRevision; }
    long polls() { return polls; }
    void incrementPolls() { polls++; }
    int width() { return outputImage.width(); }
    int height() { return outputImage.height(); }
    long readbackBytes() { return (long) outputImage.width() * outputImage.height() * Integer.BYTES; }
    int gBufferWidth() { return frameSlot.traceImage().width(); }
    int gBufferHeight() { return frameSlot.traceImage().height(); }
}
