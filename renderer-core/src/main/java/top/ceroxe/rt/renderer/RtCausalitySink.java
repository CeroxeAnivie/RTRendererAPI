package top.ceroxe.rt.renderer;

import top.ceroxe.rt.renderer.scene.SectionKey;

import java.util.Map;

/**
 * Records already-established RT identities; it never infers causality from thread or time.
 */
public interface RtCausalitySink {
    /**
     * Sink used when typed causality recording is disabled.
     */
    RtCausalitySink NOOP = new RtCausalitySink() {
    };

    /**
     * Converts a legacy dispatch-reason string to its typed representation.
     *
     * @param reason legacy dispatch-reason token; may be {@code null}
     * @return the matching reason, or {@link Reason#NONE} for an unknown token
     */
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

    /**
     * Allows producers to avoid any per-object traversal when typed event recording is disabled.
     *
     * @return {@code true} when generation events will be consumed
     */
    default boolean generationEventsEnabled() {
        return false;
    }

    /**
     * Records publication of one coherent renderer state generation.
     *
     * @param causality               admitted frame causality carried by the publication
     * @param publicationGeneration   publication generation identifier
     * @param descriptorGeneration    descriptor-set generation identifier
     * @param worldTlasRevision       world top-level acceleration-structure revision
     * @param dynamicTlasRevision     dynamic top-level acceleration-structure revision
     * @param dynamicSceneRevision    dynamic-scene revision
     * @param publicationReason       encoded publication reason
     * @param sectionContentRevisions content revision keyed by published section
     */
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

    /**
     * Records one section-level transition.
     *
     * @param stage           renderer stage represented by the transition
     * @param key             stable section key
     * @param contentRevision section content revision
     * @param generation      acceleration-structure generation
     * @param value           stage-specific numeric value
     * @param flags           stage-specific bit flags
     */
    default void section(
            Stage stage,
            SectionKey key,
            long contentRevision,
            long generation,
            long value,
            int flags
    ) {
    }

    /**
     * Records one already-established section identity without inferring missing stages.
     *
     * @param causality             admitted frame causality associated with the section
     * @param key                   stable section key
     * @param contentRevision       section content revision
     * @param geometryRevision      section geometry revision
     * @param materialRevision      section material revision
     * @param buildSequence         build submission sequence
     * @param blasGeneration        bottom-level acceleration-structure generation
     * @param tlasRevision          top-level acceleration-structure revision
     * @param publicationGeneration publication generation identifier
     * @param discardReason         encoded discard reason, or zero when not discarded
     */
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

    /**
     * Records one already-established entity identity without reconstructing it from text.
     *
     * @param causality             admitted frame causality associated with the entity
     * @param entityId              stable entity identifier
     * @param assetRevision         entity asset revision
     * @param dynamicSceneRevision  dynamic-scene revision
     * @param dynamicBlasRevision   dynamic bottom-level acceleration-structure revision
     * @param dynamicTlasRevision   dynamic top-level acceleration-structure revision
     * @param publicationGeneration publication generation identifier
     * @param discardReason         encoded discard reason, or zero when not discarded
     */
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

    /**
     * Records a world-level revision transition.
     *
     * @param worldRevision world revision
     * @param sectionCount  number of sections represented by the revision
     * @param viewRevision  renderer-view revision
     * @param flags         transition-specific bit flags
     */
    default void world(long worldRevision, long sectionCount, long viewRevision, int flags) {
    }

    /**
     * Records a frame-level transition with stage-specific flags.
     *
     * @param stage           renderer stage represented by the transition
     * @param frameSequence   frame sequence
     * @param relatedRevision revision associated with the transition
     * @param value           stage-specific numeric value
     * @param flags           stage-specific bit flags
     */
    default void frame(Stage stage, long frameSequence, long relatedRevision, long value, int flags) {
    }

    /**
     * Records a frame-level transition with a typed reason.
     *
     * @param stage           renderer stage represented by the transition
     * @param frameSequence   frame sequence
     * @param relatedRevision revision associated with the transition
     * @param value           stage-specific numeric value
     * @param reason          reason for the transition
     */
    default void frameReason(Stage stage, long frameSequence, long relatedRevision, long value, Reason reason) {
    }

    /**
     * Records a frame edge that already carries its admitted transaction identity.
     *
     * @param stage     renderer stage represented by the edge
     * @param causality admitted frame causality
     * @param evidence  immutable evidence captured for the edge
     */
    default void frameCausality(
            Stage stage,
            RendererFrameCausality causality,
            RendererCausalityEvidence evidence
    ) {
    }

    /**
     * Records the outcome of a dispatch decision.
     *
     * @param causality             admitted frame causality
     * @param frameSequence         frame sequence
     * @param publicationGeneration publication generation identifier
     * @param descriptorGeneration  descriptor-set generation identifier
     * @param dispatchSequence      dispatch sequence
     * @param reason                dispatch outcome reason
     */
    default void dispatch(
            RendererFrameCausality causality,
            long frameSequence,
            long publicationGeneration,
            long descriptorGeneration,
            long dispatchSequence,
            Reason reason
    ) {
    }

    /**
     * Records the first section reaching a named front-edge stage.
     *
     * @param stage           stable stage name
     * @param key             stable section key
     * @param contentRevision section content revision
     * @param details         bounded diagnostic details
     */
    default void firstFrontSection(String stage, SectionKey key, long contentRevision, String details) {
    }

    /**
     * Records the first world revision reaching a named front-edge stage.
     *
     * @param stage         stable stage name
     * @param worldRevision world revision
     * @param details       bounded diagnostic details
     */
    default void firstFrontWorld(String stage, long worldRevision, String details) {
    }

    /**
     * Records the first frame reaching a named front-edge stage.
     *
     * @param stage         stable stage name
     * @param frameSequence frame sequence
     * @param details       bounded diagnostic details
     */
    default void firstFrontFrame(String stage, long frameSequence, String details) {
    }

    /**
     * Identifies the renderer stage represented by a causality event.
     */
    enum Stage {
        /**
         * A bottom-level acceleration-structure generation was produced.
         */
        BLAS_GENERATION,
        /**
         * A bottom-level acceleration-structure generation became active.
         */
        BLAS_ACTIVE,
        /**
         * A bottom-level acceleration-structure generation was removed.
         */
        BLAS_REMOVED,
        /**
         * A section was bound into a top-level acceleration structure.
         */
        SECTION_TLAS_BOUND,
        /**
         * The world top-level acceleration structure was bound.
         */
        WORLD_TLAS_BOUND,
        /**
         * GPU work was dispatched.
         */
        GPU_DISPATCH,
        /**
         * GPU work completed.
         */
        GPU_COMPLETED,
        /**
         * A frame was rejected before dispatch.
         */
        FRAME_REJECTED
    }

    /**
     * Identifies why a frame event was submitted, delayed, or rejected.
     */
    enum Reason {
        /**
         * No more specific reason is available.
         */
        NONE,
        /**
         * The frame was accepted for submission.
         */
        SUBMITTED,
        /**
         * Presentation eligibility prevented submission.
         */
        PRESENTATION_ELIGIBILITY,
        /**
         * The supplied frame state was invalid or unavailable.
         */
        INVALID_FRAME_STATE,
        /**
         * The configured dispatch interval deferred submission.
         */
        DISPATCH_INTERVAL,
        /**
         * The pending-submission limit deferred submission.
         */
        MAX_PENDING_SUBMISSIONS,
        /**
         * All frame slots were still in use.
         */
        FRAME_SLOT_RING_BUSY,
        /**
         * No frame slot was currently writable.
         */
        NO_WRITABLE_FRAME_SLOT,
        /**
         * A visual-readiness gate prevented presentation.
         */
        VISUAL_GATE
    }
}
