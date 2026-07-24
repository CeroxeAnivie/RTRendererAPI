package top.ceroxe.mcvulkanrt.renderer.rt.runtime;

import top.ceroxe.mcvulkanrt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameCommitPlan;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameUpdate;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameSubmission;
import top.ceroxe.mcvulkanrt.renderer.RendererForegroundWork;
import top.ceroxe.mcvulkanrt.renderer.RendererViewState;
import top.ceroxe.mcvulkanrt.renderer.RendererRtDiagnostics;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;
import top.ceroxe.mcvulkanrt.renderer.rt.RtSceneReadiness;
import top.ceroxe.mcvulkanrt.renderer.rt.device.RtResourceScope;
import top.ceroxe.mcvulkanrt.renderer.rt.acceleration.NativeTerrainOwnership;
import top.ceroxe.mcvulkanrt.renderer.rt.device.VulkanRtDeviceContext;
import top.ceroxe.mcvulkanrt.renderer.rt.pipeline.RtFrameSnapshot;
import top.ceroxe.mcvulkanrt.renderer.rt.pipeline.RtGBufferSnapshot;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * RTCore 的第一层安全门。
 *
 * <p>它是真正 backend 的调用边界。只有 capability gate 确认硬件 RT extension 和
 * feature bit 都满足后，生产路径才会打开 native Vulkan backend，并开始接收帧末
 * mesh/removal update。这样可以把 unsupported hardware 和 backend failure 明确隔离。</p>
 */
public final class GuardedRtCore implements RtCore {
    private static final long BACKEND_SUMMARY_SAFETY_REFRESH_INTERVAL = 4_096L;

    private final AtomicReference<State> state = new AtomicReference<>(State.WAITING_FOR_CAPABILITY);
    private final RtResourceScope resourceScope = new RtResourceScope();
    private final NativeBackendFactory nativeBackendFactory;
    private final AtomicReference<NativeBackend> nativeBackend = new AtomicReference<>(NoopNativeBackend.INSTANCE);
    private final AtomicReference<String> backendSummary = new AtomicReference<>(NoopNativeBackend.INSTANCE.summary());
    /*
     * A backend summary is allowed to refresh while the renderer is healthy.  It
     * must never erase the only evidence of a failed guarded call, however: the
     * overlay and post-mortem logs use Summary as their stable diagnostic API.
     */
    private final AtomicReference<FailureSnapshot> backendFailure = new AtomicReference<>();
    private final AtomicLong lastBackendSummaryRefreshPump = new AtomicLong(-1L);
    private final AtomicReference<RtFrameSnapshot> latestFrameSnapshot = new AtomicReference<>();
    private final AtomicReference<RuntimeActivity> runtimeActivity =
            new AtomicReference<>(RuntimeActivity.unavailable());
    private final AtomicReference<RtSceneReadiness> sceneReadiness =
            new AtomicReference<>(RtSceneReadiness.unavailable());
    private final AtomicReference<NativeViewAdmission> latestViewAdmission =
            new AtomicReference<>(NativeViewAdmission.empty());
    private final AtomicLong acceptedCapabilities = new AtomicLong();
    private final AtomicLong acceptedFrameUpdates = new AtomicLong();
    private final AtomicLong observedMeshSections = new AtomicLong();
    private final AtomicLong observedRemovedSections = new AtomicLong();
    private final AtomicLong observedDynamicFrames = new AtomicLong();
    private final AtomicLong observedDynamicElements = new AtomicLong();
    private final AtomicLong lastMeshBatchBytes = new AtomicLong();
    private final AtomicLong totalMeshBatchBytes = new AtomicLong();
    private final AtomicLong backendPumps = new AtomicLong();

    public GuardedRtCore() {
        this((capability, scope) -> NoopNativeBackend.INSTANCE);
    }

    public static GuardedRtCore production() {
        return production(RendererRtDiagnostics.noop());
    }

    public static GuardedRtCore production(RendererRtDiagnostics diagnostics) {
        RendererRtDiagnostics immutableDiagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        return new GuardedRtCore((capability, scope) ->
                VulkanRtDeviceContext.open(capability, scope, immutableDiagnostics));
    }

    /**
     * Historical test entry retained for source compatibility. The extracted renderer now owns
     * its Vulkan device in every mode, so this has the same ownership model as {@link #production()}.
     */
    public static GuardedRtCore isolatedHardwareTest() {
        return new GuardedRtCore((capability, scope) -> VulkanRtDeviceContext.openIndependentUnsafe(
                capability,
                scope,
                RendererRtDiagnostics.noop()
        ));
    }

    public GuardedRtCore(NativeBackendFactory nativeBackendFactory) {
        this.nativeBackendFactory = Objects.requireNonNull(nativeBackendFactory, "nativeBackendFactory");
    }

    @Override
    public void acceptCapability(VulkanRtCapabilityProbe.Result capability) {
        Objects.requireNonNull(capability, "capability");
        acceptedCapabilities.incrementAndGet();
        if (state.get() != State.WAITING_FOR_CAPABILITY) {
            return;
        }
        if (!capability.hardwareRayTracingReady()) {
            state.compareAndSet(State.WAITING_FOR_CAPABILITY, State.DISABLED_UNSUPPORTED);
            backendSummary.set("nativeBackend=unsupported");
            sceneReadiness.set(RtSceneReadiness.unavailable());
            return;
        }
        if (!state.compareAndSet(State.WAITING_FOR_CAPABILITY, State.INITIALIZING_BACKEND)) {
            return;
        }

        long initializationStartNanos = System.nanoTime();
        long factoryOpenNanos = 0L;
        long foregroundAdmissionNanos = 0L;
        long summaryNanos = 0L;
        boolean successful = false;
        try {
            long stageStartNanos = System.nanoTime();
            NativeBackend openedBackend = nativeBackendFactory.open(capability, resourceScope);
            factoryOpenNanos = System.nanoTime() - stageStartNanos;
            NativeViewAdmission viewAdmission = latestViewAdmission.get();
            stageStartNanos = System.nanoTime();
            openedBackend.acceptForegroundWork(viewAdmission.work());
            foregroundAdmissionNanos = System.nanoTime() - stageStartNanos;
            nativeBackend.set(Objects.requireNonNull(openedBackend, "openedBackend"));
            stageStartNanos = System.nanoTime();
            backendSummary.set(openedBackend.summary());
            summaryNanos = System.nanoTime() - stageStartNanos;
            sceneReadiness.set(openedBackend.sceneReadiness());
            if (!state.compareAndSet(State.INITIALIZING_BACKEND, State.READY_FOR_SCENE_UPDATES)) {
                closeResourceScopeAfterBackendFailure(new IllegalStateException("RTCore state changed during backend initialization"));
            }
            successful = state.get() == State.READY_FOR_SCENE_UPDATES;
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            recordFailure(FailureKind.BACKEND_INITIALIZATION, "acceptCapability", ex);
            sceneReadiness.set(RtSceneReadiness.unavailable());
            if (state.compareAndSet(State.INITIALIZING_BACKEND, State.DISABLED_BACKEND_FAILURE)) {
                top.ceroxe.mcvulkanrt.renderer.RendererLog.error(
                        "native Vulkan RT backend failed during initialization after acceptedCapabilities={}",
                        acceptedCapabilities.get(),
                        ex
                );
                closeResourceScopeAfterBackendFailure(ex);
            }
        } finally {
            RtBackendInitializationFlightRecorder.record(
                    System.nanoTime() - initializationStartNanos,
                    factoryOpenNanos,
                    foregroundAdmissionNanos,
                    summaryNanos,
                    successful
            );
        }
    }

    @Override
    public void acceptViewState(RendererViewState viewState) {
        acceptViewState(viewState, Set.of());
    }

    @Override
    public void acceptViewState(
            RendererViewState viewState,
            Set<SectionKey> retainedPresentationSections
    ) {
        acceptForegroundWork(RendererForegroundWork.untraced(viewState, retainedPresentationSections));
    }

    @Override
    public void acceptForegroundWork(RendererForegroundWork work) {
        RendererForegroundWork immutableWork = Objects.requireNonNull(work, "work");
        latestViewAdmission.set(new NativeViewAdmission(immutableWork));
        if (state.get() != State.READY_FOR_SCENE_UPDATES) {
            return;
        }
        try {
            nativeBackend.get().acceptForegroundWork(immutableWork);
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            recordFailure(FailureKind.CONTROL_PATH, "acceptForegroundWork", ex);
            throw ex;
        }
    }

    @Override
    public void acceptFrameUpdate(RendererFrameUpdate update) {
        acceptFrameSubmission(RendererFrameSubmission.untraced(update));
    }

    @Override
    public void acceptFrameSubmission(RendererFrameSubmission submission) {
        Objects.requireNonNull(submission, "submission");
        RendererFrameUpdate update = submission.update();
        if (state.get() != State.READY_FOR_SCENE_UPDATES) {
            return;
        }

        try {
            NativeBackend backend = nativeBackend.get();
            backend.acceptFrameSubmission(submission);
            latestFrameSnapshot.set(backend.latestFrameSnapshot());
            runtimeActivity.set(backend.runtimeActivity());
            sceneReadiness.set(backend.sceneReadiness());
            long pumps = backendPumps.incrementAndGet();
            if (shouldRefreshBackendSummary(pumps)) {
                refreshBackendSummary(backend, pumps);
            }
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            recordFailure(FailureKind.BACKEND_PROCESSING, "acceptFrameUpdate", ex);
            sceneReadiness.set(RtSceneReadiness.unavailable());
            if (state.compareAndSet(State.READY_FOR_SCENE_UPDATES, State.DISABLED_BACKEND_FAILURE)) {
                top.ceroxe.mcvulkanrt.renderer.RendererLog.error(
                        "native Vulkan RT backend failed while accepting frame update: acceptedFrameUpdates={}, observedMeshSections={}, observedRemovedSections={}, observedDynamicFrames={}, observedDynamicElements={}, updateHasChanges={}, updateHasTerrainChanges={}, updateHasDynamicContent={}, commitPlan={}, dynamicScene={}, frameState={}, backlog={}",
                        acceptedFrameUpdates.get(),
                        observedMeshSections.get(),
                        observedRemovedSections.get(),
                        observedDynamicFrames.get(),
                        observedDynamicElements.get(),
                        update.hasChanges(),
                        update.commitPlan().hasTerrainWork(),
                        update.hasDynamicContent(),
                        update.commitPlan().asLogFragment(),
                        update.dynamicScene().asLogFragment(),
                        update.frameState().asLogFragment(),
                        update.backlogSnapshot().asLogFragment(),
                        ex
                );
                closeResourceScopeAfterBackendFailure(ex);
            }
            return;
        }

        if (!update.hasChanges()) {
            return;
        }
        acceptedFrameUpdates.incrementAndGet();
        RendererFrameCommitPlan commitPlan = update.commitPlan();
        if (commitPlan.hasTerrainWork()) {
            observedMeshSections.addAndGet(commitPlan.sectionMeshCount());
            observedRemovedSections.addAndGet(commitPlan.removedSectionCount());
            lastMeshBatchBytes.set(commitPlan.sectionMeshBytes());
            totalMeshBatchBytes.addAndGet(commitPlan.sectionMeshBytes());
        }
        if (update.hasDynamicSceneUpdate()) {
            observedDynamicFrames.incrementAndGet();
            observedDynamicElements.addAndGet(update.dynamicScene().totalElements());
        }
    }

    private static boolean shouldRefreshBackendSummary(long backendPumps) {
        return backendPumps <= 4L || backendPumps % BACKEND_SUMMARY_SAFETY_REFRESH_INTERVAL == 0L;
    }

    private void refreshBackendSummary(NativeBackend backend, long backendPumps) {
        if (lastBackendSummaryRefreshPump.get() == backendPumps) {
            return;
        }
        String refreshed = Objects.requireNonNull(backend.summary(), "backend summary");
        backendSummary.set(refreshed);
        lastBackendSummaryRefreshPump.set(backendPumps);
    }

    @Override
    public State state() {
        return state.get();
    }

    @Override
    public RtFrameSnapshot latestFrameSnapshot() {
        return latestFrameSnapshot.get();
    }

    @Override
    public boolean requestGBufferCapture() {
        if (state.get() != State.READY_FOR_SCENE_UPDATES) {
            return false;
        }
        try {
            return nativeBackend.get().requestGBufferCapture();
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            recordFailure(FailureKind.OBSERVATION, "requestGBufferCapture", ex);
            return false;
        }
    }

    @Override
    public RtGBufferSnapshot latestGBufferSnapshot() {
        if (state.get() != State.READY_FOR_SCENE_UPDATES) {
            return null;
        }
        try {
            return nativeBackend.get().latestGBufferSnapshot();
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            recordFailure(FailureKind.OBSERVATION, "latestGBufferSnapshot", ex);
            return null;
        }
    }

    @Override
    public long latestSharedFrameSequence() {
        if (state.get() != State.READY_FOR_SCENE_UPDATES) {
            return -1L;
        }
        try {
            return nativeBackend.get().latestSharedFrameSequence();
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            recordFailure(FailureKind.OBSERVATION, "latestSharedFrameSequence", ex);
            return -1L;
        }
    }

    @Override
    public Set<SectionKey> latestSharedFrameSectionKeys() {
        if (state.get() != State.READY_FOR_SCENE_UPDATES) {
            return Set.of();
        }
        try {
            return nativeBackend.get().latestSharedFrameSectionKeys();
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            recordFailure(FailureKind.OBSERVATION, "latestSharedFrameSectionKeys", ex);
            return Set.of();
        }
    }

    @Override
    public SharedFrameState latestSharedFrameState() {
        if (state.get() != State.READY_FOR_SCENE_UPDATES) {
            return SharedFrameState.unavailable();
        }
        try {
            return nativeBackend.get().latestSharedFrameState();
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            recordFailure(FailureKind.OBSERVATION, "latestSharedFrameState", ex);
            return SharedFrameState.unavailable();
        }
    }

    @Override
    public SharedFrameImage exportLatestSharedFrameImage() {
        if (state.get() != State.READY_FOR_SCENE_UPDATES) {
            return null;
        }
        try {
            return nativeBackend.get().exportLatestSharedFrameImage();
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            recordFailure(FailureKind.PRESENTATION, "exportLatestSharedFrameImage", ex);
            return null;
        }
    }

    @Override
    public SharedFrameImage exportSharedFrameImage(long requiredFrameStateSequence) {
        if (requiredFrameStateSequence < 0L) {
            throw new IllegalArgumentException("requiredFrameStateSequence must not be negative");
        }
        if (state.get() != State.READY_FOR_SCENE_UPDATES) {
            return null;
        }
        try {
            return nativeBackend.get().exportSharedFrameImage(requiredFrameStateSequence);
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            recordFailure(FailureKind.PRESENTATION, "exportSharedFrameImage", ex);
            return null;
        }
    }

    @Override
    public boolean acknowledgeSharedFramePresented(long frameStateSequence, long vulkanImage) {
        if (frameStateSequence < 0L) {
            throw new IllegalArgumentException("frameStateSequence must not be negative");
        }
        if (vulkanImage == 0L) {
            throw new IllegalArgumentException("vulkanImage must not be null");
        }
        if (state.get() != State.READY_FOR_SCENE_UPDATES) {
            return false;
        }
        try {
            return nativeBackend.get().acknowledgeSharedFramePresented(frameStateSequence, vulkanImage);
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            recordFailure(FailureKind.PRESENTATION, "acknowledgeSharedFramePresented", ex);
            return false;
        }
    }

    @Override
    public RuntimeActivity runtimeActivity() {
        return runtimeActivity.get();
    }

    @Override
    public NativeFrameTiming nativeFrameTiming() {
        if (state.get() != State.READY_FOR_SCENE_UPDATES) {
            return NativeFrameTiming.unavailable();
        }
        try {
            return nativeBackend.get().nativeFrameTiming();
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            recordFailure(FailureKind.OBSERVATION, "nativeFrameTiming", ex);
            return NativeFrameTiming.unavailable();
        }
    }

    @Override
    public NativeDispatchDecision nativeDispatchDecision() {
        if (state.get() != State.READY_FOR_SCENE_UPDATES) {
            return NativeDispatchDecision.unavailable();
        }
        try {
            return nativeBackend.get().nativeDispatchDecision();
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            recordFailure(FailureKind.OBSERVATION, "nativeDispatchDecision", ex);
            return NativeDispatchDecision.unavailable();
        }
    }

    @Override
    public DynamicGenerationState dynamicGenerationState() {
        if (state.get() != State.READY_FOR_SCENE_UPDATES) {
            return DynamicGenerationState.unavailable();
        }
        try {
            return nativeBackend.get().dynamicGenerationState();
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            recordFailure(FailureKind.OBSERVATION, "dynamicGenerationState", ex);
            return DynamicGenerationState.unavailable();
        }
    }

    @Override
    public SectionGenerationState sectionGenerationState(SectionKey key) {
        Objects.requireNonNull(key, "key");
        if (state.get() != State.READY_FOR_SCENE_UPDATES) {
            return SectionGenerationState.unavailable(key);
        }
        try {
            return nativeBackend.get().sectionGenerationState(key);
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            recordFailure(FailureKind.OBSERVATION, "sectionGenerationState", ex);
            return SectionGenerationState.unavailable(key);
        }
    }

    @Override
    public DynamicEntityGenerationState dynamicEntityGenerationState(long entityId) {
        if (state.get() != State.READY_FOR_SCENE_UPDATES) {
            return DynamicEntityGenerationState.unavailable(entityId);
        }
        try {
            return nativeBackend.get().dynamicEntityGenerationState(entityId);
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            recordFailure(FailureKind.OBSERVATION, "dynamicEntityGenerationState", ex);
            return DynamicEntityGenerationState.unavailable(entityId);
        }
    }

    @Override
    public ScenePublicationState scenePublicationState() {
        if (state.get() != State.READY_FOR_SCENE_UPDATES) {
            return ScenePublicationState.unavailable();
        }
        try {
            return nativeBackend.get().scenePublicationState();
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            recordFailure(FailureKind.OBSERVATION, "scenePublicationState", ex);
            return ScenePublicationState.unavailable();
        }
    }

    @Override
    public NativeTerrainOwnership nativeTerrainOwnership() {
        if (state.get() != State.READY_FOR_SCENE_UPDATES) {
            return NativeTerrainOwnership.unavailable();
        }
        try {
            return nativeBackend.get().nativeTerrainOwnership();
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            recordFailure(FailureKind.OBSERVATION, "nativeTerrainOwnership", ex);
            return NativeTerrainOwnership.unavailable();
        }
    }

    @Override
    public long nativeTerrainOwnershipGeneration() {
        if (state.get() != State.READY_FOR_SCENE_UPDATES) {
            return -1L;
        }
        try {
            return nativeBackend.get().nativeTerrainOwnershipGeneration();
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            recordFailure(FailureKind.OBSERVATION, "nativeTerrainOwnershipGeneration", ex);
            return -1L;
        }
    }

    @Override
    public RtSceneReadiness sceneReadiness() {
        return sceneReadiness.get();
    }

    @Override
    public ExternalMemoryInteropProbe probeExternalMemoryInterop() {
        if (state.get() != State.READY_FOR_SCENE_UPDATES) {
            return ExternalMemoryInteropProbe.skipped("rtCoreNotReady:" + state.get());
        }
        try {
            return nativeBackend.get().probeExternalMemoryInterop();
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            recordFailure(FailureKind.INTEROP_PROBE, "probeExternalMemoryInterop", ex);
            return ExternalMemoryInteropProbe.failed(
                    ex.getClass().getSimpleName() + ": " + Objects.toString(ex.getMessage(), ""),
                    0,
                    0,
                    0,
                    0L,
                    -1,
                    false,
                    false
            );
        }
    }

    @Override
    public ExternalSemaphoreInteropProbe probeExternalSemaphoreInterop() {
        if (state.get() != State.READY_FOR_SCENE_UPDATES) {
            return ExternalSemaphoreInteropProbe.skipped("rtCoreNotReady:" + state.get());
        }
        try {
            return nativeBackend.get().probeExternalSemaphoreInterop();
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            recordFailure(FailureKind.INTEROP_PROBE, "probeExternalSemaphoreInterop", ex);
            return ExternalSemaphoreInteropProbe.failed(
                    ex.getClass().getSimpleName() + ": " + Objects.toString(ex.getMessage(), ""),
                    0,
                    false,
                    false
            );
        }
    }

    @Override
    public void refreshDiagnosticSummary() {
        if (state.get() != State.READY_FOR_SCENE_UPDATES) {
            return;
        }
        try {
            refreshBackendSummary(nativeBackend.get(), backendPumps.get());
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            recordFailure(FailureKind.DIAGNOSTIC, "refreshDiagnosticSummary", ex);
        }
    }

    @Override
    public Summary summary() {
        return new Summary(
                state(),
                acceptedCapabilities.get(),
                acceptedFrameUpdates.get(),
                observedMeshSections.get(),
                observedRemovedSections.get(),
                observedDynamicFrames.get(),
                observedDynamicElements.get(),
                lastMeshBatchBytes.get(),
                totalMeshBatchBytes.get(),
                summaryWithFailure()
        );
    }

    @Override
    public void close() {
        try {
            resourceScope.close();
        } finally {
            state.set(State.CLOSED);
        }
    }

    private void closeResourceScopeAfterBackendFailure(Throwable failure) {
        try {
            resourceScope.close();
        } catch (RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
            recordFailure(FailureKind.RESOURCE_CLOSE, "closeResourceScopeAfterBackendFailure", closeFailure);
        }
    }

    private void recordFailure(FailureKind kind, String operation, Throwable failure) {
        FailureFact observed = new FailureFact(
                Objects.requireNonNull(kind, "kind"),
                Objects.requireNonNull(operation, "operation"),
                failure.getClass().getName(),
                Objects.toString(failure.getMessage(), "")
        );
        backendFailure.updateAndGet(previous -> FailureSnapshot.record(previous, observed));
    }

    private String summaryWithFailure() {
        FailureSnapshot failure = backendFailure.get();
        return failure == null ? backendSummary.get() : backendSummary.get() + ", " + failure.asLogFragment();
    }

    private enum FailureKind {
        BACKEND_INITIALIZATION,
        BACKEND_PROCESSING,
        CONTROL_PATH,
        OBSERVATION,
        PRESENTATION,
        INTEROP_PROBE,
        DIAGNOSTIC,
        RESOURCE_CLOSE
    }

    private record FailureFact(FailureKind kind, String operation, String throwableClass, String message) {
        private String asLogFragment() {
            return kind + "/" + operation + ":" + throwableClass + ":" + message;
        }
    }

    private record NativeViewAdmission(RendererForegroundWork work) {
        private NativeViewAdmission {
            work = Objects.requireNonNull(work, "work");
        }

        private static NativeViewAdmission empty() {
            return new NativeViewAdmission(RendererForegroundWork.untraced(
                    RendererViewState.allResident(),
                    Set.of()
            ));
        }
    }

    private record FailureSnapshot(FailureFact first, FailureFact latest, long occurrences) {
        private static FailureSnapshot record(FailureSnapshot previous, FailureFact observed) {
            return previous == null
                    ? new FailureSnapshot(observed, observed, 1L)
                    : new FailureSnapshot(
                            previous.first,
                            observed,
                            previous.occurrences == Long.MAX_VALUE ? Long.MAX_VALUE : previous.occurrences + 1L
                    );
        }

        private String asLogFragment() {
            return "backendFailure{count=" + occurrences
                    + ", first=" + first.asLogFragment()
                    + ", latest=" + latest.asLogFragment() + "}";
        }
    }

    @FunctionalInterface
    public interface NativeBackendFactory {
        NativeBackend open(VulkanRtCapabilityProbe.Result capability, RtResourceScope scope);
    }

    public interface NativeBackend {
        default void acceptViewState(RendererViewState viewState) {
            Objects.requireNonNull(viewState, "viewState");
        }

        default void acceptViewState(
                RendererViewState viewState,
                Set<SectionKey> retainedPresentationSections
        ) {
            acceptViewState(viewState);
            Objects.requireNonNull(retainedPresentationSections, "retainedPresentationSections");
        }

        default void acceptForegroundWork(RendererForegroundWork work) {
            Objects.requireNonNull(work, "work");
            acceptViewState(work.viewState(), work.retainedPresentationSectionKeys());
        }

        void acceptFrameUpdate(RendererFrameUpdate update);

        default void acceptFrameSubmission(RendererFrameSubmission submission) {
            acceptFrameUpdate(Objects.requireNonNull(submission, "submission").update());
        }

        default RtFrameSnapshot latestFrameSnapshot() {
            return null;
        }

        default boolean requestGBufferCapture() {
            return false;
        }

        default RtGBufferSnapshot latestGBufferSnapshot() {
            return null;
        }

        default long latestSharedFrameSequence() {
            return -1L;
        }

        default Set<SectionKey> latestSharedFrameSectionKeys() {
            return Set.of();
        }

        default SharedFrameState latestSharedFrameState() {
            long sequence = latestSharedFrameSequence();
            return sequence < 0L ? SharedFrameState.unavailable() : new SharedFrameState(
                    sequence,
                    latestSharedFrameSectionKeys()
            );
        }

        default SharedFrameImage exportLatestSharedFrameImage() {
            return null;
        }

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

        default boolean acknowledgeSharedFramePresented(long frameStateSequence, long vulkanImage) {
            if (frameStateSequence < 0L) {
                throw new IllegalArgumentException("frameStateSequence must not be negative");
            }
            if (vulkanImage == 0L) {
                throw new IllegalArgumentException("vulkanImage must not be null");
            }
            return false;
        }

        default RuntimeActivity runtimeActivity() {
            return RuntimeActivity.unavailable();
        }

        default NativeFrameTiming nativeFrameTiming() {
            return NativeFrameTiming.unavailable();
        }

        default NativeDispatchDecision nativeDispatchDecision() {
            return NativeDispatchDecision.unavailable();
        }

        default DynamicGenerationState dynamicGenerationState() {
            return DynamicGenerationState.unavailable();
        }

        default SectionGenerationState sectionGenerationState(SectionKey key) {
            return SectionGenerationState.unavailable(Objects.requireNonNull(key, "key"));
        }

        default DynamicEntityGenerationState dynamicEntityGenerationState(long entityId) {
            return DynamicEntityGenerationState.unavailable(entityId);
        }

        default ScenePublicationState scenePublicationState() {
            return ScenePublicationState.unavailable();
        }

        default NativeTerrainOwnership nativeTerrainOwnership() {
            return NativeTerrainOwnership.unavailable();
        }

        default long nativeTerrainOwnershipGeneration() {
            return -1L;
        }

        default RtSceneReadiness sceneReadiness() {
            return RtSceneReadiness.unavailable();
        }

        default ExternalMemoryInteropProbe probeExternalMemoryInterop() {
            return ExternalMemoryInteropProbe.skipped("nativeBackendDoesNotSupportExternalInteropProbe");
        }

        default ExternalSemaphoreInteropProbe probeExternalSemaphoreInterop() {
            return ExternalSemaphoreInteropProbe.skipped("nativeBackendDoesNotSupportExternalInteropProbe");
        }

        String summary();
    }

    private enum NoopNativeBackend implements NativeBackend {
        INSTANCE;

        @Override
        public void acceptFrameUpdate(RendererFrameUpdate update) {
            Objects.requireNonNull(update, "update");
        }

        @Override
        public String summary() {
            return "nativeBackend=none";
        }
    }
}
