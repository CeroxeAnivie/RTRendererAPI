package top.ceroxe.mcvulkanrt.renderer;

import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;

import java.util.Map;

/** Records already-established RT identities; it never infers causality from thread or time. */
public interface RtCausalitySink {
    RtCausalitySink NOOP = new RtCausalitySink() {
    };

    enum Stage {
        BLAS_GENERATION,
        BLAS_ACTIVE,
        BLAS_REMOVED,
        SECTION_TLAS_BOUND,
        WORLD_TLAS_BOUND,
        GPU_DISPATCH,
        GPU_COMPLETED,
        FRAME_REJECTED
    }

    enum Reason {
        NONE,
        SUBMITTED,
        PRESENTATION_ELIGIBILITY,
        INVALID_FRAME_STATE,
        DISPATCH_INTERVAL,
        MAX_PENDING_SUBMISSIONS,
        FRAME_SLOT_RING_BUSY,
        NO_WRITABLE_FRAME_SLOT,
        VISUAL_GATE
    }

    /** Allows producers to avoid any per-object traversal when typed JFR is disabled. */
    default boolean generationEventsEnabled() {
        return false;
    }

    default void publication(
            RendererFrameCausality causality,
            long publicationGeneration,
            long descriptorGeneration,
            long worldTlasRevision,
            long dynamicTlasRevision,
            long dynamicSceneRevision,
            int publicationReason,
            Map<SectionKey, Long> sectionContentRevisions
    ) {
    }

    default void section(
            Stage stage,
            SectionKey key,
            long contentRevision,
            long generation,
            long value,
            int flags
    ) {
    }

    /** Records one already-established section identity without inferring missing stages. */
    default void sectionGeneration(
            RendererFrameCausality causality,
            SectionKey key,
            long contentRevision,
            long geometryRevision,
            long materialRevision,
            long buildSequence,
            long blasGeneration,
            long tlasRevision,
            long publicationGeneration,
            int discardReason
    ) {
    }

    /** Records one already-established entity identity without reconstructing it from text. */
    default void entityGeneration(
            RendererFrameCausality causality,
            long entityId,
            long assetRevision,
            long dynamicSceneRevision,
            long dynamicBlasRevision,
            long dynamicTlasRevision,
            long publicationGeneration,
            int discardReason
    ) {
    }

    default void world(long worldRevision, long sectionCount, long viewRevision, int flags) {
    }

    default void frame(Stage stage, long frameSequence, long relatedRevision, long value, int flags) {
    }

    default void frameReason(Stage stage, long frameSequence, long relatedRevision, long value, Reason reason) {
    }

    /** Records a frame edge that already carries its admitted transaction identity. */
    default void frameCausality(
            Stage stage,
            RendererFrameCausality causality,
            RendererCausalityEvidence evidence
    ) {
    }

    default void dispatch(
            RendererFrameCausality causality,
            long frameSequence,
            long publicationGeneration,
            long descriptorGeneration,
            long dispatchSequence,
            Reason reason
    ) {
    }

    default void firstFrontSection(String stage, SectionKey key, long contentRevision, String details) {
    }

    default void firstFrontWorld(String stage, long worldRevision, String details) {
    }

    default void firstFrontFrame(String stage, long frameSequence, String details) {
    }

    static Reason dispatchReason(String reason) {
        if ("presentationEligibilityGate".equals(reason)) return Reason.PRESENTATION_ELIGIBILITY;
        if ("invalidFrameState".equals(reason)) return Reason.INVALID_FRAME_STATE;
        if ("dispatchInterval".equals(reason)) return Reason.DISPATCH_INTERVAL;
        if ("maxPendingSubmissions".equals(reason)) return Reason.MAX_PENDING_SUBMISSIONS;
        if ("frameSlotRingBusy".equals(reason)) return Reason.FRAME_SLOT_RING_BUSY;
        if ("noWritableFrameSlot".equals(reason)) return Reason.NO_WRITABLE_FRAME_SLOT;
        if ("submitted".equals(reason)) return Reason.SUBMITTED;
        return Reason.NONE;
    }
}
