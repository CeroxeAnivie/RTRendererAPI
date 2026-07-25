package top.ceroxe.rt.renderer.rt.runtime;

import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.*;
import top.ceroxe.rt.renderer.rt.RtSceneReadiness;
import top.ceroxe.rt.renderer.rt.acceleration.NativeTerrainOwnership;
import top.ceroxe.rt.renderer.rt.device.interop.Win32HandleSupport;
import top.ceroxe.rt.renderer.rt.pipeline.RtFrameSnapshot;
import top.ceroxe.rt.renderer.rt.pipeline.RtGBufferSnapshot;
import top.ceroxe.rt.renderer.scene.PackedSectionMembership;
import top.ceroxe.rt.renderer.scene.SectionKey;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Runtime boundary between renderer inputs and a ray-tracing backend.
 *
 * <p>Implementations own native resource lifetime and publish immutable diagnostic snapshots.
 * Callers must close the core when the renderer shuts down.</p>
 */
public interface RtCore extends AutoCloseable {
    /**
     * Supplies the probed Vulkan ray-tracing capability used to initialize the backend.
     *
     * @param capability immutable capability probe result
     */
    void acceptCapability(VulkanRtCapabilityProbe.Result capability);

    /**
     * Updates the visible view without retaining presentation-only sections.
     *
     * @param viewState immutable view state
     */
    default void acceptViewState(RendererViewState viewState) {
        acceptViewState(viewState, Set.of());
    }

    /**
     * Updates the visible view and sections that must remain available for presentation.
     *
     * @param viewState                    immutable view state
     * @param retainedPresentationSections sections retained by an outstanding presentation
     */
    void acceptViewState(RendererViewState viewState, Set<SectionKey> retainedPresentationSections);

    /**
     * Typed authority/successor handoff consumed by BLAS foreground recovery.
     *
     * @param work immutable foreground authority handoff
     */
    default void acceptForegroundWork(RendererForegroundWork work) {
        Objects.requireNonNull(work, "work");
        acceptViewState(work.viewState(), work.retainedPresentationSectionKeys());
    }

    /**
     * Accepts the immutable scene changes for one renderer frame.
     *
     * @param update frame update to consume
     */
    void acceptFrameUpdate(RendererFrameUpdate update);

    /**
     * Production transaction boundary; implementations must preserve the envelope identity.
     *
     * @param submission immutable frame submission envelope
     */
    default void acceptFrameSubmission(RendererFrameSubmission submission) {
        acceptFrameUpdate(Objects.requireNonNull(submission, "submission").update());
    }

    /**
     * Returns the latest non-blocking frame snapshot.
     *
     * @return latest snapshot, or an implementation-specific unavailable value
     */
    RtFrameSnapshot latestFrameSnapshot();

    /**
     * Requests capture of the next supported G-buffer frame.
     *
     * @return {@code true} when the request was accepted
     */
    default boolean requestGBufferCapture() {
        return false;
    }

    /**
     * Returns the latest captured G-buffer without blocking the render thread.
     *
     * @return latest G-buffer snapshot, or {@code null} when unavailable
     */
    default RtGBufferSnapshot latestGBufferSnapshot() {
        return null;
    }

    /**
     * Returns the sequence of the latest shareable completed frame.
     *
     * @return frame-state sequence, or a negative value when unavailable
     */
    long latestSharedFrameSequence();

    /**
     * Returns the terrain coverage of the latest shareable completed frame.
     *
     * @return immutable section coverage
     */
    Set<SectionKey> latestSharedFrameSectionKeys();

    /**
     * Returns an atomic view of the latest shareable frame state.
     *
     * @return immutable shared-frame state
     */
    default SharedFrameState latestSharedFrameState() {
        long sequence = latestSharedFrameSequence();
        return sequence < 0L ? SharedFrameState.unavailable() : new SharedFrameState(
                sequence,
                latestSharedFrameSectionKeys()
        );
    }

    /**
     * Exports the latest completed frame through the backend's shared-image mechanism.
     *
     * @return owned shared-image export, or {@code null} when unavailable
     */
    SharedFrameImage exportLatestSharedFrameImage();

    /**
     * Exports a completed frame only when its sequence matches the requested state.
     *
     * @param requiredFrameStateSequence exact frame-state sequence required by the caller
     * @return owned matching image, or {@code null} when no matching image is available
     */
    default SharedFrameImage exportSharedFrameImage(long requiredFrameStateSequence) {
        if (requiredFrameStateSequence < 0L) {
            throw new IllegalArgumentException("requiredFrameStateSequence must not be negative");
        }
        SharedFrameImage image = exportLatestSharedFrameImage();
        if (image == null || image.frameStateSequence() == requiredFrameStateSequence) {
            return image;
        }
        image.close();
        return null;
    }

    /**
     * Reports that a shared frame reached presentation and may advance lifecycle ownership.
     *
     * @param frameStateSequence sequence that was presented
     * @param vulkanImage        nonzero native Vulkan image handle
     * @return {@code true} when the acknowledgement matched a pending shared frame
     */
    boolean acknowledgeSharedFramePresented(long frameStateSequence, long vulkanImage);

    /**
     * Returns the latest runtime activity counters.
     *
     * @return immutable activity snapshot
     */
    RuntimeActivity runtimeActivity();

    /**
     * Latest native accept-frame breakdown; values are non-blocking observations.
     *
     * @return latest timing snapshot or an unavailable sentinel
     */
    default NativeFrameTiming nativeFrameTiming() {
        return NativeFrameTiming.unavailable();
    }

    /**
     * Latest native world-frame production decision used by smoke flight recording.
     *
     * @return latest dispatch decision or an unavailable sentinel
     */
    default NativeDispatchDecision nativeDispatchDecision() {
        return NativeDispatchDecision.unavailable();
    }

    /**
     * Smoke-only snapshot proving which dynamic-scene generation can reach dispatch.
     *
     * @return latest dynamic generation state or an unavailable sentinel
     */
    default DynamicGenerationState dynamicGenerationState() {
        return DynamicGenerationState.unavailable();
    }

    /**
     * Returns an on-demand section chain projection without exposing mutable cache owners.
     *
     * @param key section to inspect
     * @return immutable generation state for the section
     */
    default SectionGenerationState sectionGenerationState(SectionKey key) {
        return SectionGenerationState.unavailable(Objects.requireNonNull(key, "key"));
    }

    /**
     * Returns an on-demand aggregate for all model primitives owned by one dynamic entity.
     *
     * @param entityId stable dynamic entity identifier
     * @return immutable generation state for the entity
     */
    default DynamicEntityGenerationState dynamicEntityGenerationState(long entityId) {
        return DynamicEntityGenerationState.unavailable(entityId);
    }

    /**
     * Returns the atomic descriptor-visible scene generation.
     *
     * @return immutable publication state; diagnostics must not reconstruct it from separate caches
     */
    default ScenePublicationState scenePublicationState() {
        return ScenePublicationState.unavailable();
    }

    /**
     * Returns the current native terrain lifecycle ownership projection.
     *
     * @return immutable ownership snapshot
     */
    default NativeTerrainOwnership nativeTerrainOwnership() {
        return NativeTerrainOwnership.unavailable();
    }

    /**
     * Returns the scalar ownership anchor used for frame admission.
     *
     * @return ownership generation, or a negative value when unavailable
     */
    default long nativeTerrainOwnershipGeneration() {
        return -1L;
    }

    /**
     * Returns the latest scene readiness decision.
     *
     * @return immutable scene readiness snapshot
     */
    RtSceneReadiness sceneReadiness();

    /**
     * Probes external-memory interoperability without changing renderer ownership.
     *
     * @return immutable probe result
     */
    ExternalMemoryInteropProbe probeExternalMemoryInterop();

    /**
     * Probes external-semaphore interoperability without changing renderer ownership.
     *
     * @return immutable probe result
     */
    ExternalSemaphoreInteropProbe probeExternalSemaphoreInterop();

    /**
     * Returns the current runtime state.
     *
     * @return lifecycle state
     */
    State state();

    /**
     * Refreshes expensive human-readable backend state at a diagnostics
     * boundary. Structured frame/readiness state remains current without this
     * call; implementations must keep normal frame submission allocation-free.
     */
    default void refreshDiagnosticSummary() {
    }

    /**
     * Returns the latest structured runtime summary.
     *
     * @return immutable summary snapshot
     */
    Summary summary();

    @Override
    void close();

    /**
     * Runtime lifecycle state.
     */
    enum State {
        /**
         * Waiting for a capability probe result.
         */
        WAITING_FOR_CAPABILITY,
        /**
         * Creating native backend resources.
         */
        INITIALIZING_BACKEND,
        /**
         * Ready to accept view and frame updates.
         */
        READY_FOR_SCENE_UPDATES,
        /**
         * Disabled because required ray-tracing capability is unavailable.
         */
        DISABLED_UNSUPPORTED,
        /**
         * Disabled after backend initialization or runtime failure.
         */
        DISABLED_BACKEND_FAILURE,
        /**
         * Runtime and owned resources are closed.
         */
        CLOSED
    }

    /**
     * Immutable RT presentation coverage published after a submitted frame completes.
     *
     * <p>This is deliberately a class rather than a record. Public construction stays
     * defensive because callers may hand us mutable scene collections, while the
     * render pipeline can publish the already-frozen submission payload without a
     * second whole-world collection copy at the GPU-completion boundary. Production completion
     * also carries the descriptor-visible scene proof captured at dispatch, so presentation never
     * infers one frame's resource generation from a newer global readiness snapshot.</p>
     */
    final class SharedFrameState {
        private static final SharedFrameState UNAVAILABLE =
                new SharedFrameState(
                        -1L,
                        PackedSectionMembership.empty(),
                        RendererViewState.allResident(),
                        SectionRevisionSnapshot.empty(),
                        RendererFrameCausality.untraced(0L),
                        ScenePublicationState.unavailable(),
                        true
                );
        private final long frameStateSequence;
        private final PackedSectionMembership sectionKeys;
        private final RendererViewState viewState;
        private final SectionRevisionSnapshot sectionContentRevisions;
        private final RendererFrameCausality causality;
        private final ScenePublicationState publicationState;

        /**
         * Creates an untraced shared-frame state without an authoritative view.
         *
         * @param frameStateSequence frame sequence, or {@code -1} for unavailable
         * @param sectionKeys        immutable-compatible terrain coverage
         */
        public SharedFrameState(long frameStateSequence, Set<SectionKey> sectionKeys) {
            this(frameStateSequence, sectionKeys, RendererViewState.allResident(), zeroRevisions(sectionKeys),
                    RendererFrameCausality.untraced(Math.max(0L, frameStateSequence)));
        }

        /**
         * Creates an untraced shared-frame state with an explicit view.
         *
         * @param frameStateSequence frame sequence, or {@code -1} for unavailable
         * @param sectionKeys        immutable-compatible terrain coverage
         * @param viewState          immutable renderer view
         */
        public SharedFrameState(long frameStateSequence, Set<SectionKey> sectionKeys, RendererViewState viewState) {
            this(frameStateSequence, sectionKeys, viewState, zeroRevisions(sectionKeys),
                    RendererFrameCausality.untraced(Math.max(0L, frameStateSequence)));
        }

        private SharedFrameState(
                long frameStateSequence,
                Set<SectionKey> sectionKeys,
                RendererViewState viewState,
                SectionRevisionSnapshot sectionContentRevisions,
                RendererFrameCausality causality
        ) {
            validateHeader(frameStateSequence, sectionKeys, viewState);
            Objects.requireNonNull(sectionContentRevisions, "sectionContentRevisions");
            if (!sectionContentRevisions.membership().equals(sectionKeys)) {
                throw new IllegalArgumentException("shared-frame revisions must exactly cover section keys");
            }
            this.frameStateSequence = frameStateSequence;
            this.sectionKeys = sectionContentRevisions.membership();
            this.viewState = viewState;
            this.sectionContentRevisions = sectionContentRevisions;
            this.causality = Objects.requireNonNull(causality, "causality");
            this.publicationState = ScenePublicationState.unavailable();
        }

        private SharedFrameState(
                long frameStateSequence,
                PackedSectionMembership sectionKeys,
                RendererViewState viewState,
                SectionRevisionSnapshot sectionContentRevisions,
                RendererFrameCausality causality,
                ScenePublicationState publicationState,
                boolean trustedFrozen
        ) {
            this.frameStateSequence = frameStateSequence;
            this.sectionKeys = sectionKeys;
            this.viewState = viewState;
            this.sectionContentRevisions = Objects.requireNonNull(
                    sectionContentRevisions,
                    "sectionContentRevisions"
            );
            this.causality = Objects.requireNonNull(causality, "causality");
            this.publicationState = Objects.requireNonNull(publicationState, "publicationState");
        }

        /**
         * Creates a defensive shared-frame state from public collection inputs.
         *
         * @param frameStateSequence      frame sequence, or {@code -1} for unavailable
         * @param sectionKeys             terrain coverage
         * @param viewState               immutable renderer view
         * @param sectionContentRevisions content revision for every covered section
         */
        public SharedFrameState(
                long frameStateSequence,
                Set<SectionKey> sectionKeys,
                RendererViewState viewState,
                Map<SectionKey, Long> sectionContentRevisions
        ) {
            validate(frameStateSequence, sectionKeys, viewState, sectionContentRevisions);
            SectionRevisionSnapshot frozenRevisions = SectionRevisionSnapshot.copyOf(sectionContentRevisions);
            this.frameStateSequence = frameStateSequence;
            this.sectionKeys = frozenRevisions.membership();
            this.viewState = viewState;
            this.sectionContentRevisions = frozenRevisions;
            this.causality = RendererFrameCausality.untraced(Math.max(0L, frameStateSequence));
            this.publicationState = ScenePublicationState.unavailable();
        }

        /**
         * Creates a trusted state from publications frozen before GPU submission.
         *
         * @param frameStateSequence      completed frame sequence
         * @param sectionKeys             already-frozen packed terrain coverage
         * @param viewState               immutable submitted view
         * @param sectionContentRevisions already-frozen section revisions sharing the same membership
         * @param causality               admitted frame causality
         * @return immutable shared-frame state retaining the frozen publications
         */
        public static SharedFrameState trustedFrozen(
                long frameStateSequence,
                PackedSectionMembership sectionKeys,
                RendererViewState viewState,
                SectionRevisionSnapshot sectionContentRevisions,
                RendererFrameCausality causality
        ) {
            return trustedFrozen(
                    frameStateSequence,
                    sectionKeys,
                    viewState,
                    sectionContentRevisions,
                    causality,
                    ScenePublicationState.unavailable()
            );
        }

        /**
         * Creates a trusted state with descriptor-visible publication proof.
         *
         * @param frameStateSequence      completed frame sequence
         * @param sectionKeys             already-frozen packed terrain coverage
         * @param viewState               immutable submitted view
         * @param sectionContentRevisions already-frozen section revisions sharing the same membership
         * @param causality               admitted frame causality
         * @param publicationState        descriptor-visible publication used by dispatch
         * @return immutable shared-frame state retaining the frozen publications
         */
        public static SharedFrameState trustedFrozen(
                long frameStateSequence,
                PackedSectionMembership sectionKeys,
                RendererViewState viewState,
                SectionRevisionSnapshot sectionContentRevisions,
                RendererFrameCausality causality,
                ScenePublicationState publicationState
        ) {
            Objects.requireNonNull(sectionContentRevisions, "sectionContentRevisions");
            if (sectionContentRevisions.membership() != sectionKeys) {
                throw new IllegalArgumentException(
                        "shared-frame revisions must retain the exact section membership publication"
                );
            }
            validateHeader(frameStateSequence, sectionKeys, viewState);
            validatePublicationProof(sectionKeys, viewState, publicationState);
            return new SharedFrameState(
                    frameStateSequence,
                    sectionKeys,
                    viewState,
                    sectionContentRevisions,
                    causality,
                    publicationState,
                    true
            );
        }

        /**
         * Compatibility boundary for callers that still expose set semantics.
         * Runtime publication uses the packed overload above; external callers are fully
         * validated once and then converge on the revision snapshot's membership identity.
         *
         * @param frameStateSequence      completed frame sequence
         * @param sectionKeys             immutable-compatible terrain coverage
         * @param viewState               immutable submitted view
         * @param sectionContentRevisions frozen section revisions with identical membership
         * @param causality               admitted frame causality
         * @return immutable validated shared-frame state
         */
        public static SharedFrameState trustedFrozen(
                long frameStateSequence,
                Set<SectionKey> sectionKeys,
                RendererViewState viewState,
                SectionRevisionSnapshot sectionContentRevisions,
                RendererFrameCausality causality
        ) {
            validate(frameStateSequence, sectionKeys, viewState, sectionContentRevisions);
            return trustedFrozen(
                    frameStateSequence,
                    sectionContentRevisions.membership(),
                    viewState,
                    sectionContentRevisions,
                    causality
            );
        }

        /**
         * Creates an untraced trusted state from packed coverage.
         *
         * @param frameStateSequence      completed frame sequence
         * @param sectionKeys             already-frozen packed terrain coverage
         * @param viewState               immutable submitted view
         * @param sectionContentRevisions already-frozen section revisions
         * @return immutable untraced shared-frame state
         */
        public static SharedFrameState trustedFrozen(
                long frameStateSequence,
                PackedSectionMembership sectionKeys,
                RendererViewState viewState,
                SectionRevisionSnapshot sectionContentRevisions
        ) {
            return trustedFrozen(
                    frameStateSequence,
                    sectionKeys,
                    viewState,
                    sectionContentRevisions,
                    RendererFrameCausality.untraced(Math.max(0L, frameStateSequence))
            );
        }

        /**
         * Creates an untraced trusted state from set-semantics coverage.
         *
         * @param frameStateSequence      completed frame sequence
         * @param sectionKeys             immutable-compatible terrain coverage
         * @param viewState               immutable submitted view
         * @param sectionContentRevisions frozen section revisions
         * @return immutable untraced shared-frame state
         */
        public static SharedFrameState trustedFrozen(
                long frameStateSequence,
                Set<SectionKey> sectionKeys,
                RendererViewState viewState,
                SectionRevisionSnapshot sectionContentRevisions
        ) {
            return trustedFrozen(
                    frameStateSequence,
                    sectionKeys,
                    viewState,
                    sectionContentRevisions,
                    RendererFrameCausality.untraced(Math.max(0L, frameStateSequence))
            );
        }

        private static void validatePublicationProof(
                PackedSectionMembership sectionKeys,
                RendererViewState viewState,
                ScenePublicationState publicationState
        ) {
            Objects.requireNonNull(publicationState, "publicationState");
            if (!publicationState.available()) {
                return;
            }
            if (publicationState.descriptorGeneration() <= 0L
                    || publicationState.worldTlasRevision() < 0L
                    || publicationState.materialRevision() < 0L
                    || publicationState.sectionCount() != sectionKeys.size()
                    || publicationState.viewRevision() != viewState.revision()) {
                throw new IllegalArgumentException(
                        "shared frame publication proof does not match frozen scene coverage"
                );
            }
        }

        private static void validate(
                long frameStateSequence,
                Set<SectionKey> sectionKeys,
                RendererViewState viewState,
                Map<SectionKey, Long> sectionContentRevisions
        ) {
            validateHeader(frameStateSequence, sectionKeys, viewState);
            stableRevisions(sectionContentRevisions, sectionKeys);
        }

        private static void validateHeader(
                long frameStateSequence,
                Set<SectionKey> sectionKeys,
                RendererViewState viewState
        ) {
            if (frameStateSequence < -1L) {
                throw new IllegalArgumentException("frameStateSequence must be -1 or greater");
            }
            viewState = java.util.Objects.requireNonNull(viewState, "viewState");
            sectionKeys = java.util.Objects.requireNonNull(sectionKeys, "sectionKeys");
            if (frameStateSequence < 0L && !sectionKeys.isEmpty()) {
                throw new IllegalArgumentException("unavailable shared frame must not retain section coverage");
            }
            if (frameStateSequence < 0L && !viewState.equals(RendererViewState.allResident())) {
                throw new IllegalArgumentException("unavailable shared frame must not retain a view generation");
            }
        }

        /**
         * Returns the shared unavailable state.
         *
         * @return unavailable sentinel
         */
        public static SharedFrameState unavailable() {
            return UNAVAILABLE;
        }

        private static SectionRevisionSnapshot zeroRevisions(Set<SectionKey> keys) {
            return SectionRevisionSnapshot.constant(keys, 0L);
        }

        private static Map<SectionKey, Long> stableRevisions(Map<SectionKey, Long> revisions, Set<SectionKey> keys) {
            if (!java.util.Objects.requireNonNull(revisions, "sectionContentRevisions").keySet().equals(keys)) {
                throw new IllegalArgumentException("shared-frame section revisions must exactly cover section keys");
            }
            for (SectionKey key : keys) {
                Long revision = revisions.get(key);
                if (revision == null || revision < 0L) {
                    throw new IllegalArgumentException("shared-frame section revision must be non-negative");
                }
            }
            /*
             * PendingFrameSubmission has already frozen this exact publication before GPU
             * completion. The public constructor still validates mutable external maps before
             * converting them to the packed revision representation.
             */
            return revisions;
        }

        /**
         * Returns the frame-state sequence.
         *
         * @return sequence, or {@code -1} when unavailable
         */
        public long frameStateSequence() {
            return frameStateSequence;
        }

        /**
         * Returns packed terrain coverage.
         *
         * @return immutable section membership
         */
        public PackedSectionMembership sectionKeys() {
            return sectionKeys;
        }

        /**
         * Returns the submitted view.
         *
         * @return immutable renderer view
         */
        public RendererViewState viewState() {
            return viewState;
        }

        /**
         * Returns exact content revisions for covered sections.
         *
         * @return immutable revision snapshot
         */
        public SectionRevisionSnapshot sectionContentRevisions() {
            return sectionContentRevisions;
        }

        /**
         * Returns admitted frame causality.
         *
         * @return immutable causality identity
         */
        public RendererFrameCausality causality() {
            return causality;
        }

        /**
         * Returns descriptor-visible publication proof.
         *
         * @return immutable publication state
         */
        public ScenePublicationState publicationState() {
            return publicationState;
        }

        /**
         * Tests whether a completed frame is represented.
         *
         * @return whether the sequence is available
         */
        public boolean available() {
            return frameStateSequence >= 0L;
        }

        /**
         * Tests whether the exact descriptor-visible dispatch generation is attached.
         *
         * @return whether publication proof is available
         */
        public boolean publicationProven() {
            return publicationState.available();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof SharedFrameState state)) {
                return false;
            }
            return frameStateSequence == state.frameStateSequence
                    && sectionKeys.equals(state.sectionKeys)
                    && viewState.equals(state.viewState)
                    && sectionContentRevisions.equals(state.sectionContentRevisions)
                    && causality.equals(state.causality)
                    && publicationState.equals(state.publicationState);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(
                    frameStateSequence,
                    sectionKeys,
                    viewState,
                    sectionContentRevisions,
                    causality,
                    publicationState
            );
        }

        @Override
        public String toString() {
            return "SharedFrameState[frameStateSequence=" + frameStateSequence
                    + ", sectionKeys=" + sectionKeys
                    + ", viewState=" + viewState
                    + ", sectionContentRevisions=" + sectionContentRevisions
                    + ", causality=" + causality + ']';
        }
    }

    /**
     * Owned external-memory export for one completed frame.
     *
     * @param frameStateSequence  completed frame sequence
     * @param causality           admitted frame causality
     * @param width               image width in pixels
     * @param height              image height in pixels
     * @param vulkanFormat        Vulkan image format
     * @param vulkanImageLayout   Vulkan image layout valid for the consumer
     * @param vulkanImage         native image handle
     * @param vulkanMemory        native memory handle
     * @param allocationSize      allocation size in bytes
     * @param memoryTypeIndex     Vulkan memory type index
     * @param dedicatedAllocation whether memory uses a dedicated allocation
     * @param win32Handle         exported memory handle
     * @param syncWin32Handle     optional per-frame synchronization handle
     * @param syncHandleType      Vulkan external synchronization handle type
     */
    record SharedFrameImage(
            long frameStateSequence,
            RendererFrameCausality causality,
            int width,
            int height,
            int vulkanFormat,
            int vulkanImageLayout,
            long vulkanImage,
            long vulkanMemory,
            long allocationSize,
            int memoryTypeIndex,
            boolean dedicatedAllocation,
            long win32Handle,
            long syncWin32Handle,
            int syncHandleType
    ) implements AutoCloseable {
        /**
         * Validates native handles, dimensions, layout and causality consistency.
         */
        public SharedFrameImage {
            if (frameStateSequence < 0L) {
                throw new IllegalArgumentException("frameStateSequence must not be negative");
            }
            causality = Objects.requireNonNull(causality, "causality");
            if (causality.frameSequence() != frameStateSequence) {
                throw new IllegalArgumentException("shared frame image causality must match its frame sequence");
            }
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("shared frame dimensions must be positive");
            }
            if (vulkanImageLayout != org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL
                    && vulkanImageLayout != org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL) {
                throw new IllegalArgumentException(
                        "shared frame image layout must be GENERAL or TRANSFER_SRC_OPTIMAL"
                );
            }
            if (vulkanImage == 0L) {
                throw new IllegalArgumentException("vulkanImage must not be null");
            }
            if (vulkanMemory == 0L) {
                throw new IllegalArgumentException("vulkanMemory must not be null");
            }
            if (allocationSize <= 0L) {
                throw new IllegalArgumentException("allocationSize must be positive");
            }
            if (memoryTypeIndex < 0) {
                throw new IllegalArgumentException("memoryTypeIndex must not be negative");
            }
            if (win32Handle == 0L) {
                throw new IllegalArgumentException("win32Handle must not be null");
            }
            if (syncWin32Handle != 0L && syncHandleType == 0) {
                throw new IllegalArgumentException("syncHandleType must be set when syncWin32Handle is present");
            }
        }

        @Override
        public void close() {
            /*
             * The shared image memory handle is owned by the Vulkan output image
             * and reused across frames. Per-frame callers only own the optional
             * exported synchronization handle.
             */
            if (syncWin32Handle != 0L) {
                Win32HandleSupport.close(syncWin32Handle);
            }
        }

        /**
         * Returns a diagnostic representation of the exported image.
         *
         * @return stable log fragment
         */
        public String asLogFragment() {
            return "sharedFrameImage{seq=" + frameStateSequence
                    + ", extent=" + width + "x" + height
                    + ", vulkanFormat=" + vulkanFormat
                    + ", vulkanImage=0x" + Long.toHexString(vulkanImage)
                    + ", vulkanMemory=0x" + Long.toHexString(vulkanMemory)
                    + ", allocationSize=" + allocationSize
                    + ", memoryTypeIndex=" + memoryTypeIndex
                    + ", dedicatedAllocation=" + dedicatedAllocation
                    + ", win32Handle=0x" + Long.toHexString(win32Handle)
                    + ", syncWin32Handle=0x" + Long.toHexString(syncWin32Handle)
                    + ", syncHandleType=0x" + Integer.toHexString(syncHandleType)
                    + "}";
        }
    }

    /**
     * Immutable frame-dispatch, readback and pending-work activity snapshot.
     *
     * @param frameDispatches                   cumulative dispatch count
     * @param frameReadbacks                    cumulative readback count
     * @param latestFrameReadbackSequence       latest readback sequence, or {@code -1}
     * @param pendingFrame                      whether a frame is pending
     * @param pendingFrameSequence              pending frame sequence, or {@code -1}
     * @param pendingFrameAgeMillis             pending frame age in milliseconds
     * @param pendingFramePolls                 pending frame poll count
     * @param pendingFrameObservedLag           observed sequence lag
     * @param latestCompletedFrameDispatch      completed dispatch count
     * @param latestCompletedFrameStateSequence latest completed frame sequence, or {@code -1}
     * @param gpuFrameTiming                    GPU frame timing aggregate
     * @param gpuWorkTiming                     GPU build/upload stage timing aggregate
     */
    record RuntimeActivity(
            long frameDispatches,
            long frameReadbacks,
            long latestFrameReadbackSequence,
            boolean pendingFrame,
            long pendingFrameSequence,
            long pendingFrameAgeMillis,
            long pendingFramePolls,
            long pendingFrameObservedLag,
            long latestCompletedFrameDispatch,
            long latestCompletedFrameStateSequence,
            GpuFrameTiming gpuFrameTiming,
            GpuWorkTiming gpuWorkTiming
    ) {
        private static final long UNAVAILABLE_SEQUENCE = -1L;
        private static final RuntimeActivity UNAVAILABLE = new RuntimeActivity(0L, 0L, UNAVAILABLE_SEQUENCE);

        /**
         * Creates a compatibility activity snapshot without pending or GPU timing state.
         *
         * @param frameDispatches             cumulative dispatch count
         * @param frameReadbacks              cumulative readback count
         * @param latestFrameReadbackSequence latest readback sequence, or {@code -1}
         */
        public RuntimeActivity(
                long frameDispatches,
                long frameReadbacks,
                long latestFrameReadbackSequence
        ) {
            this(
                    frameDispatches,
                    frameReadbacks,
                    latestFrameReadbackSequence,
                    false,
                    UNAVAILABLE_SEQUENCE,
                    0L,
                    0L,
                    0L,
                    0L,
                    UNAVAILABLE_SEQUENCE,
                    GpuFrameTiming.unavailable(),
                    GpuWorkTiming.unavailable()
            );
        }

        /**
         * Normalizes optional timing snapshots and validates all counters.
         */
        public RuntimeActivity {
            gpuFrameTiming = gpuFrameTiming == null ? GpuFrameTiming.unavailable() : gpuFrameTiming;
            gpuWorkTiming = gpuWorkTiming == null ? GpuWorkTiming.unavailable() : gpuWorkTiming;
            if (frameDispatches < 0L) {
                throw new IllegalArgumentException("frameDispatches must not be negative");
            }
            if (frameReadbacks < 0L) {
                throw new IllegalArgumentException("frameReadbacks must not be negative");
            }
            if (latestFrameReadbackSequence < UNAVAILABLE_SEQUENCE) {
                throw new IllegalArgumentException("latestFrameReadbackSequence must be -1 or greater");
            }
            if (frameReadbacks == 0L && latestFrameReadbackSequence != UNAVAILABLE_SEQUENCE) {
                throw new IllegalArgumentException("readback sequence requires at least one frame readback");
            }
            if (pendingFrameSequence < UNAVAILABLE_SEQUENCE) {
                throw new IllegalArgumentException("pendingFrameSequence must be -1 or greater");
            }
            if (pendingFrameAgeMillis < 0L) {
                throw new IllegalArgumentException("pendingFrameAgeMillis must not be negative");
            }
            if (pendingFramePolls < 0L) {
                throw new IllegalArgumentException("pendingFramePolls must not be negative");
            }
            if (pendingFrameObservedLag < 0L) {
                throw new IllegalArgumentException("pendingFrameObservedLag must not be negative");
            }
            if (latestCompletedFrameDispatch < 0L) {
                throw new IllegalArgumentException("latestCompletedFrameDispatch must not be negative");
            }
            if (latestCompletedFrameStateSequence < UNAVAILABLE_SEQUENCE) {
                throw new IllegalArgumentException("latestCompletedFrameStateSequence must be -1 or greater");
            }
            if (!pendingFrame && pendingFrameSequence != UNAVAILABLE_SEQUENCE) {
                throw new IllegalArgumentException("pendingFrameSequence requires a pending frame");
            }
            if (pendingFrame && pendingFrameSequence == UNAVAILABLE_SEQUENCE) {
                throw new IllegalArgumentException("pending frame requires a sequence");
            }
        }

        /**
         * Returns the shared unavailable activity snapshot.
         *
         * @return unavailable snapshot
         */
        public static RuntimeActivity unavailable() {
            return UNAVAILABLE;
        }

        /**
         * Tests whether any frame was dispatched.
         *
         * @return whether dispatch activity exists
         */
        public boolean hasFrameDispatch() {
            return frameDispatches > 0L;
        }

        /**
         * Returns a copy with updated GPU work timing.
         *
         * @param timing replacement GPU work timing
         * @return updated activity snapshot
         */
        public RuntimeActivity withGpuWorkTiming(GpuWorkTiming timing) {
            return new RuntimeActivity(
                    frameDispatches,
                    frameReadbacks,
                    latestFrameReadbackSequence,
                    pendingFrame,
                    pendingFrameSequence,
                    pendingFrameAgeMillis,
                    pendingFramePolls,
                    pendingFrameObservedLag,
                    latestCompletedFrameDispatch,
                    latestCompletedFrameStateSequence,
                    gpuFrameTiming,
                    Objects.requireNonNull(timing, "timing")
            );
        }

        /**
         * Formats runtime activity for diagnostics.
         *
         * @return stable log fragment
         */
        public String asLogFragment() {
            return "rtRuntimeActivity{frameDispatches=" + frameDispatches
                    + ", frameReadbacks=" + frameReadbacks
                    + ", latestFrameReadbackSequence=" + latestFrameReadbackSequence
                    + ", pendingFrame=" + pendingFrame
                    + ", pendingFrameSequence=" + pendingFrameSequence
                    + ", pendingFrameAgeMillis=" + pendingFrameAgeMillis
                    + ", pendingFramePolls=" + pendingFramePolls
                    + ", pendingFrameObservedLag=" + pendingFrameObservedLag
                    + ", latestCompletedFrameDispatch=" + latestCompletedFrameDispatch
                    + ", latestCompletedFrameStateSequence=" + latestCompletedFrameStateSequence
                    + ", gpuFrameTiming=" + gpuFrameTiming.asLogFragment()
                    + ", gpuWorkTiming=" + gpuWorkTiming.asLogFragment()
                    + "}";
        }
    }

    /**
     * Aggregate GPU timestamp telemetry for frame tracing.
     *
     * @param enabled               whether timestamp capture is enabled
     * @param acquiredSamples       acquired capture count
     * @param completedSamples      completed capture count
     * @param droppedSamples        dropped capture count
     * @param failedSamples         failed capture count
     * @param lastTraceNanos        latest trace duration
     * @param lastPostTraceNanos    latest post-trace duration
     * @param lastTotalNanos        latest total duration
     * @param averageTraceNanos     average trace duration
     * @param averagePostTraceNanos average post-trace duration
     * @param averageTotalNanos     average total duration
     * @param maxTotalNanos         maximum total duration
     */
    record GpuFrameTiming(
            boolean enabled,
            long acquiredSamples,
            long completedSamples,
            long droppedSamples,
            long failedSamples,
            long lastTraceNanos,
            long lastPostTraceNanos,
            long lastTotalNanos,
            long averageTraceNanos,
            long averagePostTraceNanos,
            long averageTotalNanos,
            long maxTotalNanos
    ) {
        private static final GpuFrameTiming UNAVAILABLE = new GpuFrameTiming(
                false, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L
        );

        /**
         * Validates that every timing counter and duration is nonnegative.
         */
        public GpuFrameTiming {
            if (acquiredSamples < 0L || completedSamples < 0L || droppedSamples < 0L
                    || failedSamples < 0L || lastTraceNanos < 0L || lastPostTraceNanos < 0L
                    || lastTotalNanos < 0L || averageTraceNanos < 0L || averagePostTraceNanos < 0L
                    || averageTotalNanos < 0L || maxTotalNanos < 0L) {
                throw new IllegalArgumentException("GPU frame timing values must not be negative");
            }
        }

        /**
         * Returns the disabled empty timing snapshot.
         *
         * @return unavailable timing
         */
        public static GpuFrameTiming unavailable() {
            return UNAVAILABLE;
        }

        /**
         * Formats GPU frame timing for diagnostics.
         *
         * @return stable log fragment
         */
        public String asLogFragment() {
            return "gpuFrame{enabled=" + enabled
                    + ", acquired=" + acquiredSamples
                    + ", completed=" + completedSamples
                    + ", dropped=" + droppedSamples
                    + ", failed=" + failedSamples
                    + ", lastTraceMicros=" + lastTraceNanos / 1_000L
                    + ", lastPostTraceMicros=" + lastPostTraceNanos / 1_000L
                    + ", lastTotalMicros=" + lastTotalNanos / 1_000L
                    + ", averageTraceMicros=" + averageTraceNanos / 1_000L
                    + ", averagePostTraceMicros=" + averagePostTraceNanos / 1_000L
                    + ", averageTotalMicros=" + averageTotalNanos / 1_000L
                    + ", maxTotalMicros=" + maxTotalNanos / 1_000L
                    + '}';
        }
    }

    /**
     * GPU timing grouped by renderer work stage.
     *
     * @param sectionBlas    section BLAS timing
     * @param dynamicBlas    dynamic BLAS timing
     * @param dynamicTlas    dynamic TLAS timing
     * @param worldTlas      world TLAS timing
     * @param materialUpload material upload timing
     */
    record GpuWorkTiming(
            GpuStageTiming sectionBlas,
            GpuStageTiming dynamicBlas,
            GpuStageTiming dynamicTlas,
            GpuStageTiming worldTlas,
            GpuStageTiming materialUpload
    ) {
        private static final GpuWorkTiming UNAVAILABLE = new GpuWorkTiming(
                GpuStageTiming.unavailable(),
                GpuStageTiming.unavailable(),
                GpuStageTiming.unavailable(),
                GpuStageTiming.unavailable(),
                GpuStageTiming.unavailable()
        );

        /**
         * Requires an explicit timing snapshot for every work stage.
         */
        public GpuWorkTiming {
            Objects.requireNonNull(sectionBlas, "sectionBlas");
            Objects.requireNonNull(dynamicBlas, "dynamicBlas");
            Objects.requireNonNull(dynamicTlas, "dynamicTlas");
            Objects.requireNonNull(worldTlas, "worldTlas");
            Objects.requireNonNull(materialUpload, "materialUpload");
        }

        /**
         * Returns a snapshot with every stage unavailable.
         *
         * @return unavailable timing
         */
        public static GpuWorkTiming unavailable() {
            return UNAVAILABLE;
        }

        /**
         * Formats all GPU work stages for diagnostics.
         *
         * @return stable log fragment
         */
        public String asLogFragment() {
            return "gpuWork{" + sectionBlas.asLogFragment("sectionBlas")
                    + ", " + dynamicBlas.asLogFragment("dynamicBlas")
                    + ", " + dynamicTlas.asLogFragment("dynamicTlas")
                    + ", " + worldTlas.asLogFragment("worldTlas")
                    + ", " + materialUpload.asLogFragment("materialUpload")
                    + '}';
        }
    }

    /**
     * Aggregate timestamp telemetry for one GPU work stage.
     *
     * @param enabled          whether timestamp capture is enabled
     * @param acquiredSamples  acquired capture count
     * @param completedSamples completed capture count
     * @param droppedSamples   dropped capture count
     * @param failedSamples    failed capture count
     * @param lastNanos        latest duration
     * @param averageNanos     average duration
     * @param maxNanos         maximum duration
     */
    record GpuStageTiming(
            boolean enabled,
            long acquiredSamples,
            long completedSamples,
            long droppedSamples,
            long failedSamples,
            long lastNanos,
            long averageNanos,
            long maxNanos
    ) {
        private static final GpuStageTiming UNAVAILABLE = new GpuStageTiming(
                false, 0L, 0L, 0L, 0L, 0L, 0L, 0L
        );

        /**
         * Validates that every stage counter and duration is nonnegative.
         */
        public GpuStageTiming {
            if (acquiredSamples < 0L || completedSamples < 0L || droppedSamples < 0L
                    || failedSamples < 0L || lastNanos < 0L || averageNanos < 0L || maxNanos < 0L) {
                throw new IllegalArgumentException("GPU work timing values must not be negative");
            }
        }

        /**
         * Returns the disabled empty stage timing.
         *
         * @return unavailable timing
         */
        public static GpuStageTiming unavailable() {
            return UNAVAILABLE;
        }

        private String asLogFragment(String label) {
            return label + "{enabled=" + enabled
                    + ", acquired=" + acquiredSamples
                    + ", completed=" + completedSamples
                    + ", dropped=" + droppedSamples
                    + ", failed=" + failedSamples
                    + ", lastMicros=" + lastNanos / 1_000L
                    + ", averageMicros=" + averageNanos / 1_000L
                    + ", maxMicros=" + maxNanos / 1_000L
                    + '}';
        }
    }

    /**
     * CPU-side native accept-frame timing breakdown in microseconds.
     *
     * @param totalMicros       total accept-frame duration
     * @param ingestMicros      input ingestion duration
     * @param preBuildMicros    pre-build preparation duration
     * @param buildBudgetMicros budgeted build duration
     * @param postBuildMicros   post-build processing duration
     * @param worldTlasMicros   world TLAS duration
     * @param dispatchMicros    dispatch preparation duration
     */
    record NativeFrameTiming(
            long totalMicros,
            long ingestMicros,
            long preBuildMicros,
            long buildBudgetMicros,
            long postBuildMicros,
            long worldTlasMicros,
            long dispatchMicros
    ) {
        private static final NativeFrameTiming UNAVAILABLE = new NativeFrameTiming(0L, 0L, 0L, 0L, 0L, 0L, 0L);

        /**
         * Validates that every duration is nonnegative.
         */
        public NativeFrameTiming {
            if (totalMicros < 0L || ingestMicros < 0L || preBuildMicros < 0L || buildBudgetMicros < 0L
                    || postBuildMicros < 0L || worldTlasMicros < 0L || dispatchMicros < 0L) {
                throw new IllegalArgumentException("native frame timing values must not be negative");
            }
        }

        /**
         * Returns the empty native timing snapshot.
         *
         * @return unavailable timing
         */
        public static NativeFrameTiming unavailable() {
            return UNAVAILABLE;
        }

        /**
         * Formats native frame timing for diagnostics.
         *
         * @return stable log fragment
         */
        public String asLogFragment() {
            return "native{totalUs=" + totalMicros
                    + ", ingestUs=" + ingestMicros
                    + ", preBuildUs=" + preBuildMicros
                    + ", buildBudgetUs=" + buildBudgetMicros
                    + ", postBuildUs=" + postBuildMicros
                    + ", tlasUs=" + worldTlasMicros
                    + ", dispatchUs=" + dispatchMicros
                    + "}";
        }
    }

    /**
     * Records why the latest world frame did or did not reach a Vulkan
     * submission. This is observability only and never controls scheduling.
     *
     * @param frameStateSequence                evaluated frame sequence
     * @param dispatched                        whether native work was submitted
     * @param stage                             last evaluated dispatch stage
     * @param reason                            diagnostic dispatch reason
     * @param pendingSubmissions                current pending submissions
     * @param frameSlots                        configured frame slots
     * @param maxPendingSubmissions             configured pending submission limit
     * @param frameDispatches                   cumulative successful dispatches
     * @param latestCompletedFrameStateSequence latest completed frame sequence
     * @param pendingFrameStateSequence         currently pending frame sequence
     * @param observedFrameStates               cumulative observed frame states
     * @param skippedPresentationGateFrames     presentation-gate skip count
     * @param skippedUnavailableFrameStates     unavailable-state skip count
     * @param skippedIntervalFrames             interval-throttle skip count
     * @param skippedPendingAsyncFrames         asynchronous-backlog skip count
     * @param skippedNoFrameSlots               no-slot skip count
     */
    record NativeDispatchDecision(
            long frameStateSequence,
            boolean dispatched,
            String stage,
            String reason,
            int pendingSubmissions,
            int frameSlots,
            int maxPendingSubmissions,
            long frameDispatches,
            long latestCompletedFrameStateSequence,
            long pendingFrameStateSequence,
            long observedFrameStates,
            long skippedPresentationGateFrames,
            long skippedUnavailableFrameStates,
            long skippedIntervalFrames,
            long skippedPendingAsyncFrames,
            long skippedNoFrameSlots
    ) {
        private static final NativeDispatchDecision UNAVAILABLE = new NativeDispatchDecision(
                -1L, false, "unavailable", "unavailable", 0, 0, 0,
                0L, -1L, -1L, 0L, 0L, 0L, 0L, 0L, 0L
        );

        /**
         * Validates counters, sequences and diagnostic labels.
         */
        public NativeDispatchDecision {
            if (frameStateSequence < -1L || pendingSubmissions < 0 || frameSlots < 0
                    || maxPendingSubmissions < 0 || frameDispatches < 0L
                    || latestCompletedFrameStateSequence < -1L || pendingFrameStateSequence < -1L
                    || observedFrameStates < 0L || skippedPresentationGateFrames < 0L
                    || skippedUnavailableFrameStates < 0L || skippedIntervalFrames < 0L
                    || skippedPendingAsyncFrames < 0L || skippedNoFrameSlots < 0L) {
                throw new IllegalArgumentException("native dispatch decision values must be valid");
            }
            stage = java.util.Objects.requireNonNull(stage, "stage");
            reason = java.util.Objects.requireNonNull(reason, "reason");
        }

        /**
         * Returns the shared unavailable dispatch decision.
         *
         * @return unavailable decision
         */
        public static NativeDispatchDecision unavailable() {
            return UNAVAILABLE;
        }

        /**
         * Formats the dispatch decision for diagnostics.
         *
         * @return stable log fragment
         */
        public String asLogFragment() {
            return "nativeDispatch{frameSeq=" + frameStateSequence
                    + ", dispatched=" + dispatched
                    + ", stage=" + stage
                    + ", reason=" + reason
                    + ", pending=" + pendingSubmissions + "/" + maxPendingSubmissions
                    + ", slots=" + frameSlots
                    + ", dispatches=" + frameDispatches
                    + ", completedSeq=" + latestCompletedFrameStateSequence
                    + ", pendingSeq=" + pendingFrameStateSequence
                    + ", observed=" + observedFrameStates
                    + ", skipped=gate:" + skippedPresentationGateFrames
                    + ",unavailable:" + skippedUnavailableFrameStates
                    + ",interval:" + skippedIntervalFrames
                    + ",pending:" + skippedPendingAsyncFrames
                    + ",noSlot:" + skippedNoFrameSlots
                    + "}";
        }
    }

    /**
     * Aggregate dynamic-scene build, binding and publication generations.
     *
     * @param observedSceneRevision     observed dynamic-scene revision
     * @param activeBlasRevision        active dynamic BLAS revision
     * @param activeTopologyRevision    active topology revision
     * @param activeGeometryRevision    active geometry revision
     * @param causality                 admitted frame causality
     * @param pendingAssetBuilds        pending asset builds
     * @param queuedAssetBuilds         queued asset builds
     * @param inactiveAssetSlots        inactive asset slots
     * @param replacementAssetSlots     replacement asset slots
     * @param boundTlasRevision         bound dynamic TLAS revision
     * @param boundTlasTopologyRevision bound TLAS topology revision
     * @param boundTlasGeometryRevision bound TLAS geometry revision
     * @param descriptorGeneration      descriptor generation
     * @param pipelineSceneRevision     pipeline scene revision
     * @param dispatchedSceneRevision   dispatched scene revision
     * @param completedSceneRevision    completed scene revision
     */
    record DynamicGenerationState(
            long observedSceneRevision,
            long activeBlasRevision,
            long activeTopologyRevision,
            long activeGeometryRevision,
            RendererFrameCausality causality,
            int pendingAssetBuilds,
            int queuedAssetBuilds,
            int inactiveAssetSlots,
            int replacementAssetSlots,
            long boundTlasRevision,
            long boundTlasTopologyRevision,
            long boundTlasGeometryRevision,
            long descriptorGeneration,
            long pipelineSceneRevision,
            long dispatchedSceneRevision,
            long completedSceneRevision
    ) {
        private static final DynamicGenerationState UNAVAILABLE = new DynamicGenerationState(
                -1L, -1L, -1L, -1L, RendererFrameCausality.untraced(0L),
                0, 0, 0, 0, -1L, -1L, -1L, -1L, -1L, -1L, -1L
        );

        /**
         * Validates generations, residency counters and causality.
         */
        public DynamicGenerationState {
            if (observedSceneRevision < -1L || activeBlasRevision < -1L
                    || activeTopologyRevision < -1L || activeGeometryRevision < -1L
                    || boundTlasRevision < -1L || boundTlasTopologyRevision < -1L
                    || boundTlasGeometryRevision < -1L || descriptorGeneration < -1L
                    || pipelineSceneRevision < -1L || dispatchedSceneRevision < -1L
                    || completedSceneRevision < -1L) {
                throw new IllegalArgumentException("dynamic generation revisions must be -1 or greater");
            }
            if (pendingAssetBuilds < 0 || queuedAssetBuilds < 0
                    || inactiveAssetSlots < 0 || replacementAssetSlots < 0) {
                throw new IllegalArgumentException("dynamic residency counts must not be negative");
            }
            causality = java.util.Objects.requireNonNull(causality, "causality");
        }

        /**
         * Returns the shared unavailable generation state.
         *
         * @return unavailable state
         */
        public static DynamicGenerationState unavailable() {
            return UNAVAILABLE;
        }

        /**
         * Tests whether asset build or residency work remains.
         *
         * @return whether a backlog exists
         */
        public boolean assetBuildBacklog() {
            return pendingAssetBuilds > 0 || queuedAssetBuilds > 0
                    || inactiveAssetSlots > 0 || replacementAssetSlots > 0;
        }

        /**
         * Formats dynamic generation state for diagnostics.
         *
         * @return stable log fragment
         */
        public String asLogFragment() {
            return "dynamicGeneration{observedScene=" + observedSceneRevision
                    + ", activeBlas=" + activeBlasRevision
                    + ", activeTopology=" + activeTopologyRevision
                    + ", activeGeometry=" + activeGeometryRevision
                    + ", causality={traceId=" + causality.traceId()
                    + ", source=" + causality.source()
                    + ", frame=" + causality.frameSequence() + "}"
                    + ", residency={pending=" + pendingAssetBuilds
                    + ", queued=" + queuedAssetBuilds
                    + ", inactiveSlots=" + inactiveAssetSlots
                    + ", replacementSlots=" + replacementAssetSlots + "}"
                    + ", boundTlas=" + boundTlasRevision
                    + ", boundTopology=" + boundTlasTopologyRevision
                    + ", boundGeometry=" + boundTlasGeometryRevision
                    + ", descriptor=" + descriptorGeneration
                    + ", pipelineScene=" + pipelineSceneRevision
                    + ", dispatchedScene=" + dispatchedSceneRevision
                    + ", completedScene=" + completedSceneRevision
                    + "}";
        }
    }

    /**
     * Atomic descriptor-visible scene publication identity.
     *
     * @param publicationGeneration   publication generation
     * @param descriptorGeneration    descriptor generation
     * @param worldTlasRevision       world TLAS revision
     * @param dynamicTlasRevision     dynamic TLAS revision
     * @param materialRevision        aggregate material revision
     * @param sectionMaterialRevision section material revision
     * @param dynamicMaterialRevision dynamic material revision
     * @param viewRevision            renderer view revision
     * @param sectionCount            published section count
     * @param dynamicSceneRevision    dynamic scene revision
     * @param reason                  publication reason
     */
    record ScenePublicationState(
            long publicationGeneration,
            long descriptorGeneration,
            long worldTlasRevision,
            long dynamicTlasRevision,
            long materialRevision,
            long sectionMaterialRevision,
            long dynamicMaterialRevision,
            long viewRevision,
            int sectionCount,
            long dynamicSceneRevision,
            String reason
    ) {
        private static final ScenePublicationState UNAVAILABLE = new ScenePublicationState(
                -1L, -1L, -1L, -1L, -1L, -1L, -1L, -1L, 0, -1L, "unavailable"
        );

        /**
         * Validates all publication generations and availability consistency.
         */
        public ScenePublicationState {
            if (publicationGeneration < -1L || descriptorGeneration < -1L
                    || worldTlasRevision < -1L || dynamicTlasRevision < -1L
                    || materialRevision < -1L || sectionMaterialRevision < -1L
                    || dynamicMaterialRevision < -1L || viewRevision < -1L
                    || sectionCount < 0 || dynamicSceneRevision < -1L) {
                throw new IllegalArgumentException("scene publication values must be -1 or greater");
            }
            reason = java.util.Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("scene publication reason must not be blank");
            }
            if (publicationGeneration < 0L && (descriptorGeneration >= 0L || worldTlasRevision >= 0L
                    || dynamicTlasRevision >= 0L || materialRevision >= 0L || sectionCount != 0)) {
                throw new IllegalArgumentException("unavailable scene publication must not claim native state");
            }
        }

        /**
         * Returns the shared unavailable publication.
         *
         * @return unavailable publication
         */
        public static ScenePublicationState unavailable() {
            return UNAVAILABLE;
        }

        /**
         * Tests whether a publication generation exists.
         *
         * @return whether publication is available
         */
        public boolean available() {
            return publicationGeneration >= 0L;
        }

        /**
         * Formats publication identity for diagnostics.
         *
         * @return stable log fragment
         */
        public String asLogFragment() {
            return "scenePublication{generation=" + publicationGeneration
                    + ", descriptor=" + descriptorGeneration
                    + ", worldTlas=" + worldTlasRevision
                    + ", dynamicTlas=" + dynamicTlasRevision
                    + ", material=" + materialRevision
                    + ", sectionMaterial=" + sectionMaterialRevision
                    + ", dynamicMaterial=" + dynamicMaterialRevision
                    + ", view=" + viewRevision
                    + ", sections=" + sectionCount
                    + ", dynamicScene=" + dynamicSceneRevision
                    + ", reason=" + reason
                    + "}";
        }
    }

    /**
     * Frame lifecycle progress associated with a resource generation.
     *
     * @param dispatchedFrameSequence   latest dispatched frame sequence
     * @param completedFrameSequence    latest completed frame sequence
     * @param presentedFrameSequence    latest presented frame sequence
     * @param acknowledgedFrameSequence latest acknowledged frame sequence
     */
    record FrameGenerationProgress(
            long dispatchedFrameSequence,
            long completedFrameSequence,
            long presentedFrameSequence,
            long acknowledgedFrameSequence
    ) {
        private static final FrameGenerationProgress UNAVAILABLE =
                new FrameGenerationProgress(-1L, -1L, -1L, -1L);

        /**
         * Validates sequence bounds and lifecycle ordering.
         */
        public FrameGenerationProgress {
            if (dispatchedFrameSequence < -1L || completedFrameSequence < -1L
                    || presentedFrameSequence < -1L || acknowledgedFrameSequence < -1L) {
                throw new IllegalArgumentException("frame generation progress must be -1 or greater");
            }
            if (completedFrameSequence >= 0L && dispatchedFrameSequence < 0L) {
                throw new IllegalArgumentException("completed generation requires a dispatch");
            }
            if (presentedFrameSequence >= 0L && completedFrameSequence < 0L) {
                throw new IllegalArgumentException("presented generation requires completion");
            }
            if (acknowledgedFrameSequence >= 0L && presentedFrameSequence < 0L) {
                throw new IllegalArgumentException("acknowledged generation requires presentation");
            }
        }

        /**
         * Returns the shared unavailable progress snapshot.
         *
         * @return unavailable progress
         */
        public static FrameGenerationProgress unavailable() {
            return UNAVAILABLE;
        }
    }

    /**
     * End-to-end ownership and publication state for one terrain section.
     *
     * @param sectionKey               stable section key
     * @param desiredContentRevision   desired source revision
     * @param activeContentRevision    active BLAS content revision
     * @param publishedContentRevision published content revision
     * @param geometryGeneration       geometry generation
     * @param materialGeneration       material generation
     * @param buildSequence            build submission sequence
     * @param queued                   whether queued for recording
     * @param recording                whether commands are being recorded
     * @param gpuInFlight              whether GPU build work is in flight
     * @param active                   whether an active BLAS exists
     * @param bound                    whether bound into the world TLAS
     * @param published                whether descriptor-visible
     * @param worldTlasRevision        bound world TLAS revision
     * @param publicationGeneration    publication generation
     * @param frameProgress            associated frame lifecycle progress
     * @param causality                current ownership causality
     * @param publishedCausality       published causality
     */
    record SectionGenerationState(
            SectionKey sectionKey,
            long desiredContentRevision,
            long activeContentRevision,
            long publishedContentRevision,
            long geometryGeneration,
            long materialGeneration,
            long buildSequence,
            boolean queued,
            boolean recording,
            boolean gpuInFlight,
            boolean active,
            boolean bound,
            boolean published,
            long worldTlasRevision,
            long publicationGeneration,
            FrameGenerationProgress frameProgress,
            RendererFrameCausality causality,
            RendererFrameCausality publishedCausality
    ) {
        /**
         * Validates generations, ownership flags and publication consistency.
         */
        public SectionGenerationState {
            sectionKey = Objects.requireNonNull(sectionKey, "sectionKey");
            if (desiredContentRevision < -1L || activeContentRevision < -1L
                    || publishedContentRevision < -1L
                    || geometryGeneration < -1L || materialGeneration < -1L || buildSequence < -1L
                    || worldTlasRevision < -1L || publicationGeneration < -1L) {
                throw new IllegalArgumentException("section generation values must be -1 or greater");
            }
            frameProgress = Objects.requireNonNull(frameProgress, "frameProgress");
            causality = Objects.requireNonNull(causality, "causality");
            publishedCausality = Objects.requireNonNull(publishedCausality, "publishedCausality");
            if (!observed() && (queued || recording || gpuInFlight || active || bound || published)) {
                throw new IllegalArgumentException("unobserved section must not claim downstream ownership");
            }
            if (published && (!bound || publicationGeneration < 0L)) {
                throw new IllegalArgumentException("published section requires a bound publication generation");
            }
        }

        /**
         * Creates an unavailable state for a requested section.
         *
         * @param key section key
         * @return unavailable section state
         */
        public static SectionGenerationState unavailable(SectionKey key) {
            return new SectionGenerationState(
                    key, -1L, -1L, -1L, -1L, -1L, -1L,
                    false, false, false, false, false, false,
                    -1L, -1L, FrameGenerationProgress.unavailable(),
                    RendererFrameCausality.untraced(0L), RendererFrameCausality.untraced(0L)
            );
        }

        /**
         * Tests whether any section revision was observed.
         *
         * @return whether the section is observed
         */
        public boolean observed() {
            return desiredContentRevision >= 0L || activeContentRevision >= 0L || publishedContentRevision >= 0L;
        }
    }

    /**
     * End-to-end build and publication state for one dynamic entity.
     *
     * @param entityId                     unsigned 32-bit entity identifier
     * @param observedSceneRevision        observed scene revision
     * @param primitiveCount               observed primitive count
     * @param assetCount                   referenced asset count
     * @param queuedAssetCount             queued asset count
     * @param pendingAssetCount            pending asset count
     * @param residentAssetCount           resident asset count
     * @param newestAssetRevision          newest asset revision
     * @param dynamicBlasRevision          dynamic BLAS revision
     * @param boundDynamicTlasRevision     bound dynamic TLAS revision
     * @param published                    whether the entity is published
     * @param publishedPrimitiveCount      published primitive count
     * @param publishedNewestAssetRevision newest published asset revision
     * @param publicationGeneration        publication generation
     * @param publishedDynamicTlasRevision published dynamic TLAS revision
     * @param publishedSceneRevision       published scene revision
     * @param frameProgress                associated frame lifecycle progress
     * @param causality                    current ownership causality
     * @param publishedCausality           published causality
     */
    record DynamicEntityGenerationState(
            long entityId,
            long observedSceneRevision,
            int primitiveCount,
            int assetCount,
            int queuedAssetCount,
            int pendingAssetCount,
            int residentAssetCount,
            long newestAssetRevision,
            long dynamicBlasRevision,
            long boundDynamicTlasRevision,
            boolean published,
            int publishedPrimitiveCount,
            long publishedNewestAssetRevision,
            long publicationGeneration,
            long publishedDynamicTlasRevision,
            long publishedSceneRevision,
            FrameGenerationProgress frameProgress,
            RendererFrameCausality causality,
            RendererFrameCausality publishedCausality
    ) {
        /**
         * Validates entity identity, counters, generations and publication consistency.
         */
        public DynamicEntityGenerationState {
            if (entityId < 0L || entityId > 0xFFFF_FFFFL) {
                throw new IllegalArgumentException("entity id must be an unsigned 32-bit value");
            }
            if (observedSceneRevision < -1L || newestAssetRevision < -1L
                    || dynamicBlasRevision < -1L || boundDynamicTlasRevision < -1L
                    || publishedNewestAssetRevision < -1L
                    || publicationGeneration < -1L || publishedDynamicTlasRevision < -1L
                    || publishedSceneRevision < -1L) {
                throw new IllegalArgumentException("dynamic entity generations must be -1 or greater");
            }
            if (primitiveCount < 0 || assetCount < 0 || queuedAssetCount < 0
                    || pendingAssetCount < 0 || residentAssetCount < 0
                    || publishedPrimitiveCount < 0
                    || queuedAssetCount > assetCount || pendingAssetCount > assetCount
                    || residentAssetCount > assetCount) {
                throw new IllegalArgumentException("dynamic entity counts are inconsistent");
            }
            frameProgress = Objects.requireNonNull(frameProgress, "frameProgress");
            causality = Objects.requireNonNull(causality, "causality");
            publishedCausality = Objects.requireNonNull(publishedCausality, "publishedCausality");
            if (published && publicationGeneration < 0L) {
                throw new IllegalArgumentException("published entity requires a publication generation");
            }
            if (published != (publishedPrimitiveCount > 0)) {
                throw new IllegalArgumentException("published entity flag and primitive count must agree");
            }
        }

        /**
         * Creates an unavailable state for a requested entity.
         *
         * @param entityId unsigned 32-bit entity identifier
         * @return unavailable entity state
         */
        public static DynamicEntityGenerationState unavailable(long entityId) {
            return new DynamicEntityGenerationState(
                    entityId, -1L, 0, 0, 0, 0, 0, -1L,
                    -1L, -1L, false, 0, -1L, -1L, -1L, -1L,
                    FrameGenerationProgress.unavailable(), RendererFrameCausality.untraced(0L),
                    RendererFrameCausality.untraced(0L)
            );
        }

        /**
         * Tests whether any entity primitive was observed.
         *
         * @return whether the entity is observed
         */
        public boolean observed() {
            return primitiveCount > 0;
        }

        /**
         * Tests whether referenced assets still require build or residency work.
         *
         * @return whether work remains
         */
        public boolean assetBuildBacklog() {
            return queuedAssetCount > 0 || pendingAssetCount > 0 || residentAssetCount < assetCount;
        }
    }

    /**
     * Aggregate runtime counters and backend summary.
     *
     * @param state                   runtime state
     * @param acceptedCapabilities    accepted capability count
     * @param acceptedFrameUpdates    accepted frame-update count
     * @param observedMeshSections    observed mesh section count
     * @param observedRemovedSections observed removed section count
     * @param observedDynamicFrames   observed dynamic frame count
     * @param observedDynamicElements observed dynamic element count
     * @param lastMeshBatchBytes      latest mesh batch bytes
     * @param totalMeshBatchBytes     cumulative mesh batch bytes
     * @param backendSummary          backend diagnostic summary
     */
    record Summary(
            State state,
            long acceptedCapabilities,
            long acceptedFrameUpdates,
            long observedMeshSections,
            long observedRemovedSections,
            long observedDynamicFrames,
            long observedDynamicElements,
            long lastMeshBatchBytes,
            long totalMeshBatchBytes,
            String backendSummary
    ) {
        /**
         * Normalizes a null backend summary to an empty string.
         */
        public Summary {
            backendSummary = backendSummary == null ? "" : backendSummary;
        }

        /**
         * Formats aggregate runtime state for diagnostics.
         *
         * @return stable log fragment
         */
        public String asLogFragment() {
            return "rtCoreState=" + state
                    + ", rtCapabilities=" + acceptedCapabilities
                    + ", rtFrameUpdates=" + acceptedFrameUpdates
                    + ", rtMeshSections=" + observedMeshSections
                    + ", rtRemovedSections=" + observedRemovedSections
                    + ", rtDynamicFrames=" + observedDynamicFrames
                    + ", rtDynamicElements=" + observedDynamicElements
                    + ", rtLastMeshBatchBytes=" + lastMeshBatchBytes
                    + ", rtTotalMeshBatchBytes=" + totalMeshBatchBytes
                    + (backendSummary.isBlank() ? "" : ", " + backendSummary);
        }
    }

    /**
     * Result of external Vulkan memory export validation.
     *
     * @param attempted           whether the probe ran
     * @param successful          whether every probe stage succeeded
     * @param width               test image width
     * @param height              test image height
     * @param vulkanFormat        test image format
     * @param allocationSize      exported allocation size
     * @param memoryTypeIndex     Vulkan memory type index
     * @param dedicatedAllocation whether a dedicated allocation was used
     * @param win32HandleClosed   whether the exported handle was closed
     * @param reason              outcome reason
     */
    record ExternalMemoryInteropProbe(
            boolean attempted,
            boolean successful,
            int width,
            int height,
            int vulkanFormat,
            long allocationSize,
            int memoryTypeIndex,
            boolean dedicatedAllocation,
            boolean win32HandleClosed,
            String reason
    ) {
        /**
         * Normalizes a null outcome reason.
         */
        public ExternalMemoryInteropProbe {
            reason = reason == null ? "" : reason;
        }

        /**
         * Creates a skipped probe result.
         *
         * @param reason skip reason
         * @return skipped result
         */
        public static ExternalMemoryInteropProbe skipped(String reason) {
            return new ExternalMemoryInteropProbe(
                    false,
                    false,
                    0,
                    0,
                    0,
                    0L,
                    -1,
                    false,
                    false,
                    reason
            );
        }

        /**
         * Creates a failed probe result with observed allocation facts.
         *
         * @param reason              failure reason
         * @param width               test image width
         * @param height              test image height
         * @param vulkanFormat        test image format
         * @param allocationSize      exported allocation size
         * @param memoryTypeIndex     Vulkan memory type index
         * @param dedicatedAllocation whether a dedicated allocation was used
         * @param win32HandleClosed   whether the exported handle was closed
         * @return failed result
         */
        public static ExternalMemoryInteropProbe failed(
                String reason,
                int width,
                int height,
                int vulkanFormat,
                long allocationSize,
                int memoryTypeIndex,
                boolean dedicatedAllocation,
                boolean win32HandleClosed
        ) {
            return new ExternalMemoryInteropProbe(
                    true,
                    false,
                    width,
                    height,
                    vulkanFormat,
                    allocationSize,
                    memoryTypeIndex,
                    dedicatedAllocation,
                    win32HandleClosed,
                    reason
            );
        }

        /**
         * Creates a successful probe result.
         *
         * @param width               test image width
         * @param height              test image height
         * @param vulkanFormat        test image format
         * @param allocationSize      exported allocation size
         * @param memoryTypeIndex     Vulkan memory type index
         * @param dedicatedAllocation whether a dedicated allocation was used
         * @return successful result
         */
        public static ExternalMemoryInteropProbe success(
                int width,
                int height,
                int vulkanFormat,
                long allocationSize,
                int memoryTypeIndex,
                boolean dedicatedAllocation
        ) {
            return new ExternalMemoryInteropProbe(
                    true,
                    true,
                    width,
                    height,
                    vulkanFormat,
                    allocationSize,
                    memoryTypeIndex,
                    dedicatedAllocation,
                    true,
                    "ready"
            );
        }

        /**
         * Formats memory interop facts for diagnostics.
         *
         * @return stable log fragment
         */
        public String asLogFragment() {
            return "externalMemoryInteropProbe{attempted=" + attempted
                    + ", successful=" + successful
                    + ", extent=" + width + "x" + height
                    + ", vulkanFormat=" + vulkanFormat
                    + ", allocationSize=" + allocationSize
                    + ", memoryTypeIndex=" + memoryTypeIndex
                    + ", dedicatedAllocation=" + dedicatedAllocation
                    + ", win32HandleClosed=" + win32HandleClosed
                    + ", reason=" + reason
                    + "}";
        }
    }

    /**
     * Result of external Vulkan semaphore export validation.
     *
     * @param attempted         whether the probe ran
     * @param successful        whether every probe stage succeeded
     * @param handleType        Vulkan external semaphore handle type
     * @param semaphoreCreated  whether the test semaphore was created
     * @param win32HandleClosed whether the exported handle was closed
     * @param reason            outcome reason
     */
    record ExternalSemaphoreInteropProbe(
            boolean attempted,
            boolean successful,
            int handleType,
            boolean semaphoreCreated,
            boolean win32HandleClosed,
            String reason
    ) {
        /**
         * Normalizes a null outcome reason.
         */
        public ExternalSemaphoreInteropProbe {
            reason = reason == null ? "" : reason;
        }

        /**
         * Creates a skipped semaphore probe.
         *
         * @param reason skip reason
         * @return skipped result
         */
        public static ExternalSemaphoreInteropProbe skipped(String reason) {
            return new ExternalSemaphoreInteropProbe(
                    false,
                    false,
                    0,
                    false,
                    false,
                    reason
            );
        }

        /**
         * Creates a failed semaphore probe.
         *
         * @param reason            failure reason
         * @param handleType        Vulkan external semaphore handle type
         * @param semaphoreCreated  whether the test semaphore was created
         * @param win32HandleClosed whether the exported handle was closed
         * @return failed result
         */
        public static ExternalSemaphoreInteropProbe failed(
                String reason,
                int handleType,
                boolean semaphoreCreated,
                boolean win32HandleClosed
        ) {
            return new ExternalSemaphoreInteropProbe(
                    true,
                    false,
                    handleType,
                    semaphoreCreated,
                    win32HandleClosed,
                    reason
            );
        }

        /**
         * Creates a successful semaphore probe.
         *
         * @param handleType Vulkan external semaphore handle type
         * @return successful result
         */
        public static ExternalSemaphoreInteropProbe success(int handleType) {
            return new ExternalSemaphoreInteropProbe(
                    true,
                    true,
                    handleType,
                    true,
                    true,
                    "ready"
            );
        }

        /**
         * Formats semaphore interop facts for diagnostics.
         *
         * @return stable log fragment
         */
        public String asLogFragment() {
            return "externalSemaphoreInteropProbe{attempted=" + attempted
                    + ", successful=" + successful
                    + ", handleType=0x" + Integer.toHexString(handleType)
                    + ", semaphoreCreated=" + semaphoreCreated
                    + ", win32HandleClosed=" + win32HandleClosed
                    + ", reason=" + reason
                    + "}";
        }
    }
}
