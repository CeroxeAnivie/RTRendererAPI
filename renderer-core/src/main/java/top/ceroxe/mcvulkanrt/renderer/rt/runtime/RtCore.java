package top.ceroxe.mcvulkanrt.renderer.rt.runtime;

import top.ceroxe.mcvulkanrt.renderer.rt.RtSceneReadiness;
import top.ceroxe.mcvulkanrt.renderer.rt.acceleration.NativeTerrainOwnership;
import top.ceroxe.mcvulkanrt.renderer.rt.device.interop.Win32HandleSupport;
import top.ceroxe.mcvulkanrt.renderer.rt.pipeline.RtFrameSnapshot;
import top.ceroxe.mcvulkanrt.renderer.rt.pipeline.RtGBufferSnapshot;
import top.ceroxe.mcvulkanrt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameUpdate;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameSubmission;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameCausality;
import top.ceroxe.mcvulkanrt.renderer.RendererForegroundWork;
import top.ceroxe.mcvulkanrt.renderer.RendererViewState;
import top.ceroxe.mcvulkanrt.renderer.SectionRevisionSnapshot;
import top.ceroxe.mcvulkanrt.renderer.scene.PackedSectionMembership;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public interface RtCore extends AutoCloseable {
    void acceptCapability(VulkanRtCapabilityProbe.Result capability);

    default void acceptViewState(RendererViewState viewState) {
        acceptViewState(viewState, Set.of());
    }

    void acceptViewState(RendererViewState viewState, Set<SectionKey> retainedPresentationSections);

    /** Typed authority/successor handoff consumed by BLAS foreground recovery. */
    default void acceptForegroundWork(RendererForegroundWork work) {
        Objects.requireNonNull(work, "work");
        acceptViewState(work.viewState(), work.retainedPresentationSectionKeys());
    }

    void acceptFrameUpdate(RendererFrameUpdate update);

    /** Production transaction boundary; implementations must preserve the envelope identity. */
    default void acceptFrameSubmission(RendererFrameSubmission submission) {
        acceptFrameUpdate(Objects.requireNonNull(submission, "submission").update());
    }

    RtFrameSnapshot latestFrameSnapshot();

    default boolean requestGBufferCapture() {
        return false;
    }

    default RtGBufferSnapshot latestGBufferSnapshot() {
        return null;
    }

    long latestSharedFrameSequence();

    Set<SectionKey> latestSharedFrameSectionKeys();

    default SharedFrameState latestSharedFrameState() {
        long sequence = latestSharedFrameSequence();
        return sequence < 0L ? SharedFrameState.unavailable() : new SharedFrameState(
                sequence,
                latestSharedFrameSectionKeys()
        );
    }

    SharedFrameImage exportLatestSharedFrameImage();

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

    boolean acknowledgeSharedFramePresented(long frameStateSequence, long vulkanImage);

    RuntimeActivity runtimeActivity();

    /** Latest native accept-frame breakdown; values are non-blocking observations. */
    default NativeFrameTiming nativeFrameTiming() {
        return NativeFrameTiming.unavailable();
    }

    /** Latest native world-frame production decision used by smoke flight recording. */
    default NativeDispatchDecision nativeDispatchDecision() {
        return NativeDispatchDecision.unavailable();
    }

    /** Smoke-only snapshot proving which dynamic-scene generation can reach dispatch. */
    default DynamicGenerationState dynamicGenerationState() {
        return DynamicGenerationState.unavailable();
    }

    /** On-demand section chain projection; implementations must not expose mutable cache owners. */
    default SectionGenerationState sectionGenerationState(SectionKey key) {
        return SectionGenerationState.unavailable(Objects.requireNonNull(key, "key"));
    }

    /** On-demand aggregate for every model primitive currently owned by one sourceEngine entity. */
    default DynamicEntityGenerationState dynamicEntityGenerationState(long entityId) {
        return DynamicEntityGenerationState.unavailable(entityId);
    }

    /** Atomic descriptor-visible scene generation; diagnostics must not reconstruct this from separate caches. */
    default ScenePublicationState scenePublicationState() {
        return ScenePublicationState.unavailable();
    }

    default NativeTerrainOwnership nativeTerrainOwnership() {
        return NativeTerrainOwnership.unavailable();
    }

    /** Scalar ownership anchor for frame admission; must not materialize section collections. */
    default long nativeTerrainOwnershipGeneration() {
        return -1L;
    }

    RtSceneReadiness sceneReadiness();

    ExternalMemoryInteropProbe probeExternalMemoryInterop();

    ExternalSemaphoreInteropProbe probeExternalSemaphoreInterop();

    State state();

    /**
     * Refreshes expensive human-readable backend state at a diagnostics
     * boundary. Structured frame/readiness state remains current without this
     * call; implementations must keep normal frame submission allocation-free.
     */
    default void refreshDiagnosticSummary() {
    }

    Summary summary();

    @Override
    void close();

    enum State {
        WAITING_FOR_CAPABILITY,
        INITIALIZING_BACKEND,
        READY_FOR_SCENE_UPDATES,
        DISABLED_UNSUPPORTED,
        DISABLED_BACKEND_FAILURE,
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

        public SharedFrameState(long frameStateSequence, Set<SectionKey> sectionKeys) {
            this(frameStateSequence, sectionKeys, RendererViewState.allResident(), zeroRevisions(sectionKeys),
                    RendererFrameCausality.untraced(Math.max(0L, frameStateSequence)));
        }

        public SharedFrameState(long frameStateSequence, Set<SectionKey> sectionKeys, RendererViewState viewState) {
            this(frameStateSequence, sectionKeys, viewState, zeroRevisions(sectionKeys),
                    RendererFrameCausality.untraced(Math.max(0L, frameStateSequence)));
        }

        /**
         * Internal fast path for a PendingFrameSubmission which already froze
         * its section coverage and revision map before GPU submission.
         *
         * <p>The public constructors remain defensive. Recopying this same
         * immutable payload at every completed RT frame was visible in JFR as
         * render-thread allocation and map-equality work, so only the pipeline
         * completion boundary may use this trusted factory.</p>
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

        /** Compatibility boundary for untraced tests and diagnostic fixtures. */
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

        /** Compatibility boundary for untraced tests and diagnostic fixtures. */
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

        public long frameStateSequence() {
            return frameStateSequence;
        }

        public PackedSectionMembership sectionKeys() {
            return sectionKeys;
        }

        public RendererViewState viewState() {
            return viewState;
        }

        public SectionRevisionSnapshot sectionContentRevisions() {
            return sectionContentRevisions;
        }

        public RendererFrameCausality causality() {
            return causality;
        }

        public ScenePublicationState publicationState() {
            return publicationState;
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

        public static SharedFrameState unavailable() {
            return UNAVAILABLE;
        }

        public boolean available() {
            return frameStateSequence >= 0L;
        }

        /** Production frames carry the exact descriptor-visible generation used by GPU dispatch. */
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
    }

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

        public static RuntimeActivity unavailable() {
            return UNAVAILABLE;
        }

        public boolean hasFrameDispatch() {
            return frameDispatches > 0L;
        }

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

        public GpuFrameTiming {
            if (acquiredSamples < 0L || completedSamples < 0L || droppedSamples < 0L
                    || failedSamples < 0L || lastTraceNanos < 0L || lastPostTraceNanos < 0L
                    || lastTotalNanos < 0L || averageTraceNanos < 0L || averagePostTraceNanos < 0L
                    || averageTotalNanos < 0L || maxTotalNanos < 0L) {
                throw new IllegalArgumentException("GPU frame timing values must not be negative");
            }
        }

        public static GpuFrameTiming unavailable() {
            return UNAVAILABLE;
        }

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

        public GpuWorkTiming {
            Objects.requireNonNull(sectionBlas, "sectionBlas");
            Objects.requireNonNull(dynamicBlas, "dynamicBlas");
            Objects.requireNonNull(dynamicTlas, "dynamicTlas");
            Objects.requireNonNull(worldTlas, "worldTlas");
            Objects.requireNonNull(materialUpload, "materialUpload");
        }

        public static GpuWorkTiming unavailable() {
            return UNAVAILABLE;
        }

        public String asLogFragment() {
            return "gpuWork{" + sectionBlas.asLogFragment("sectionBlas")
                    + ", " + dynamicBlas.asLogFragment("dynamicBlas")
                    + ", " + dynamicTlas.asLogFragment("dynamicTlas")
                    + ", " + worldTlas.asLogFragment("worldTlas")
                    + ", " + materialUpload.asLogFragment("materialUpload")
                    + '}';
        }
    }

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

        public GpuStageTiming {
            if (acquiredSamples < 0L || completedSamples < 0L || droppedSamples < 0L
                    || failedSamples < 0L || lastNanos < 0L || averageNanos < 0L || maxNanos < 0L) {
                throw new IllegalArgumentException("GPU work timing values must not be negative");
            }
        }

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

        public NativeFrameTiming {
            if (totalMicros < 0L || ingestMicros < 0L || preBuildMicros < 0L || buildBudgetMicros < 0L
                    || postBuildMicros < 0L || worldTlasMicros < 0L || dispatchMicros < 0L) {
                throw new IllegalArgumentException("native frame timing values must not be negative");
            }
        }

        public static NativeFrameTiming unavailable() {
            return UNAVAILABLE;
        }

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

        public static NativeDispatchDecision unavailable() {
            return UNAVAILABLE;
        }

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

        public boolean assetBuildBacklog() {
            return pendingAssetBuilds > 0 || queuedAssetBuilds > 0
                    || inactiveAssetSlots > 0 || replacementAssetSlots > 0;
        }

        public static DynamicGenerationState unavailable() {
            return UNAVAILABLE;
        }

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

        public static ScenePublicationState unavailable() {
            return UNAVAILABLE;
        }

        public boolean available() {
            return publicationGeneration >= 0L;
        }

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

    record FrameGenerationProgress(
            long dispatchedFrameSequence,
            long completedFrameSequence,
            long presentedFrameSequence,
            long acknowledgedFrameSequence
    ) {
        private static final FrameGenerationProgress UNAVAILABLE =
                new FrameGenerationProgress(-1L, -1L, -1L, -1L);

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

        public static FrameGenerationProgress unavailable() {
            return UNAVAILABLE;
        }
    }

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

        public boolean observed() {
            return desiredContentRevision >= 0L || activeContentRevision >= 0L || publishedContentRevision >= 0L;
        }

        public static SectionGenerationState unavailable(SectionKey key) {
            return new SectionGenerationState(
                    key, -1L, -1L, -1L, -1L, -1L, -1L,
                    false, false, false, false, false, false,
                    -1L, -1L, FrameGenerationProgress.unavailable(),
                    RendererFrameCausality.untraced(0L), RendererFrameCausality.untraced(0L)
            );
        }
    }

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

        public boolean observed() {
            return primitiveCount > 0;
        }

        public boolean assetBuildBacklog() {
            return queuedAssetCount > 0 || pendingAssetCount > 0 || residentAssetCount < assetCount;
        }

        public static DynamicEntityGenerationState unavailable(long entityId) {
            return new DynamicEntityGenerationState(
                    entityId, -1L, 0, 0, 0, 0, 0, -1L,
                    -1L, -1L, false, 0, -1L, -1L, -1L, -1L,
                    FrameGenerationProgress.unavailable(), RendererFrameCausality.untraced(0L),
                    RendererFrameCausality.untraced(0L)
            );
        }
    }

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
        public Summary {
            backendSummary = backendSummary == null ? "" : backendSummary;
        }

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
        public ExternalMemoryInteropProbe {
            reason = reason == null ? "" : reason;
        }

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

    record ExternalSemaphoreInteropProbe(
            boolean attempted,
            boolean successful,
            int handleType,
            boolean semaphoreCreated,
            boolean win32HandleClosed,
            String reason
    ) {
        public ExternalSemaphoreInteropProbe {
            reason = reason == null ? "" : reason;
        }

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
