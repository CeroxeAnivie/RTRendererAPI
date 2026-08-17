package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.CpuFrame;
import top.ceroxe.rt.renderer.api.FrameValidationException;
import top.ceroxe.rt.renderer.api.HistoryInvalidationReason;
import top.ceroxe.rt.renderer.api.RayTracingRenderer;
import top.ceroxe.rt.renderer.api.RayTracingRendererConfig;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.RendererDeviceException;
import top.ceroxe.rt.renderer.api.RendererDiagnostics;
import top.ceroxe.rt.renderer.api.FrameGenerationEvidence;
import top.ceroxe.rt.renderer.api.TechnologyExecutionEvidence;
import top.ceroxe.rt.renderer.api.RendererException;
import top.ceroxe.rt.renderer.api.RendererHealth;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities;
import top.ceroxe.rt.renderer.api.Renderer;
import top.ceroxe.rt.renderer.api.RenderingSemanticCapabilities;
import top.ceroxe.rt.renderer.api.RenderCommandTransaction;
import top.ceroxe.rt.renderer.api.CommandExecutionEvidence;
import top.ceroxe.rt.renderer.api.RenderResourceTransaction;
import top.ceroxe.rt.renderer.api.RenderWorkload;
import top.ceroxe.rt.renderer.api.WorkloadExecutionEvidence;
import top.ceroxe.rt.renderer.api.ResourceGenerationKey;
import top.ceroxe.rt.renderer.api.ResourceResidencyEvidence;
import top.ceroxe.rt.renderer.api.ResourceTransactionEvidence;
import top.ceroxe.rt.renderer.api.RendererFeatureController;
import top.ceroxe.rt.renderer.api.RendererFeatureProfile;
import top.ceroxe.rt.renderer.api.RendererStateException;
import top.ceroxe.rt.renderer.api.SceneRevisionException;
import top.ceroxe.rt.renderer.api.SceneTransaction;
import top.ceroxe.rt.renderer.api.SubmissionOrderException;
import top.ceroxe.rt.renderer.api.SubmissionDeferralReason;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFrameInterop;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFramePresenter;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFramePresenterConfig;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFramePresenterFactory;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

import java.util.Objects;
import java.util.Optional;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/**
 * Provider-side lifecycle and ordering authority for one Vulkan renderer instance.
 *
 * <p>This class deliberately has package visibility: hosts can only obtain the public
 * {@link RayTracingRenderer} contract from a fully wired provider. Until the generic mesh and
 * material adapter supplies a real {@link VulkanRenderingSession}, no service registration can
 * accidentally expose a renderer that acknowledges work without dispatching it.</p>
 */
final class VulkanRendererHost implements Renderer, VulkanFrameInterop, VulkanFramePresenterFactory {
    private static final int MAX_AUTOMATIC_DEVICE_RECOVERIES = 1;

    private final RayTracingRendererConfig configuration;
    private final VulkanRenderingSessionFactory sessionFactory;
    private final ManagedPresenterOpener managedPresenterOpener;
    private final RenderingFeatureCapabilities negotiatedFeatureCapabilities;
    private final VulkanRendererFeatureController featureController;
    private RenderingFeatureCapabilities publishedFeatureCapabilities;
    private final PersistentSceneRegistry scene = new PersistentSceneRegistry();
    /*
     * The lock protects lifecycle publication and the public single-writer contract. It is
     * deliberately non-fair: fair hand-off turned the hot submit/poll pair into a context-switch
     * pipeline and measurably reduced presentation throughput. GPU work is bounded by frame-slot
     * ownership instead of scheduler fairness.
     */
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private VulkanSceneResidency residency = new VulkanSceneResidency();
    private VulkanRenderingSession session;
    /* Generic command resources are isolated from retained GPUScene residency and frame slots. */
    private VulkanGenericCommandSession genericCommands;
    /* Stable adapter identity for the project-independent external-frame contract. */
    private final VulkanExternalFrameConsumerAdapter externalFrameConsumer;

    private Lifecycle lifecycle = Lifecycle.READY;
    private long latestSubmittedFrameSequence = -1L;
    private long latestCompletedFrameSequence = -1L;
    private long latestSessionCompletedFrameSequence = -1L;
    private long latestAcquiredFrameSequence = -1L;
    private long latestCpuFrameSequence = -1L;
    private boolean deviceRecoveredSinceLastFrame;
    private RendererDiagnostics.FrameGpuTiming latestGpuTiming = RendererDiagnostics.FrameGpuTiming.unavailable();
    private FrameGenerationEvidence latestFrameGenerationEvidence = FrameGenerationEvidence.unavailable();
    private TechnologyExecutionEvidence latestTechnologyExecutionEvidence =
            TechnologyExecutionEvidence.disabled();
    private Throwable terminalFailure;
    private RendererHealth.Failure activeFailure;
    private boolean sessionClosed;
    private int openFrameLeases;
    private int automaticDeviceRecoveries;
    private int successfulDeviceRecoveries;
    private int failedDeviceRecoveries;
    private boolean recoveryPending;
    private boolean managedPresenterOpen;
    private boolean managedPresenterRetainsSession;
    private int managedPresenterBacklogLimit;
    private FrameSubmissionDeferred managedPresenterBackpressure;
    private final ArrayDeque<Long> managedPresenterOutstandingFrames = new ArrayDeque<>();
    private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();

    private <T> T withLifecycleLock(Supplier<T> action) {
        lifecycleLock.lock();
        try {
            return action.get();
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void withLifecycleLock(Runnable action) {
        lifecycleLock.lock();
        try {
            action.run();
        } finally {
            lifecycleLock.unlock();
        }
    }

    VulkanRendererHost(RayTracingRendererConfig configuration, VulkanRenderingSession session) {
        this(configuration, null, session, VulkanGlfwFramePresenter::open);
    }

    VulkanRendererHost(RayTracingRendererConfig configuration, VulkanRenderingSessionFactory sessionFactory) {
        this(
                configuration,
                Objects.requireNonNull(sessionFactory, "sessionFactory"),
                sessionFactory.open(),
                VulkanGlfwFramePresenter::open
        );
    }

    VulkanRendererHost(
            RayTracingRendererConfig configuration,
            VulkanRenderingSession session,
            ManagedPresenterOpener managedPresenterOpener
    ) {
        this(configuration, null, session, managedPresenterOpener);
    }

    VulkanRendererHost(
            RayTracingRendererConfig configuration,
            VulkanRenderingSessionFactory sessionFactory,
            ManagedPresenterOpener managedPresenterOpener
    ) {
        this(
                configuration,
                Objects.requireNonNull(sessionFactory, "sessionFactory"),
                sessionFactory.open(),
                managedPresenterOpener
        );
    }

    private VulkanRendererHost(
            RayTracingRendererConfig configuration,
            VulkanRenderingSessionFactory sessionFactory,
            VulkanRenderingSession session,
            ManagedPresenterOpener managedPresenterOpener
    ) {
        VulkanRenderingSession ownedSession = Objects.requireNonNull(session, "session");
        RayTracingRendererConfig checkedConfiguration;
        try {
            checkedConfiguration = Objects.requireNonNull(configuration, "configuration");
            VulkanRenderingSession.State initialState = Objects.requireNonNull(
                    ownedSession.state(),
                    "session state"
            );
            if (initialState != VulkanRenderingSession.State.READY) {
                throw new IllegalArgumentException("Vulkan rendering session must be READY: state=" + initialState);
            }
        } catch (RuntimeException initializationFailure) {
            try {
                ownedSession.close();
            } catch (RuntimeException closeFailure) {
                initializationFailure.addSuppressed(closeFailure);
            }
            throw initializationFailure;
        }
        this.configuration = checkedConfiguration;
        this.externalFrameConsumer = new VulkanExternalFrameConsumerAdapter(
                this, checkedConfiguration.frameOutputFormat()
        );
        this.sessionFactory = sessionFactory;
        this.negotiatedFeatureCapabilities = Objects.requireNonNull(
                ownedSession.featureCapabilities(), "session feature capabilities"
        );
        this.publishedFeatureCapabilities = negotiatedFeatureCapabilities;
        this.session = ownedSession;
        this.genericCommands = openGenericCommands(ownedSession, checkedConfiguration);
        RendererFeatureProfile initialFeatureProfile = featureProfile(checkedConfiguration);
        this.featureController = new VulkanRendererFeatureController(
                initialFeatureProfile,
                new VulkanRendererFeatureController.Backend() {
                    @Override
                    public VulkanRenderingSession.FeatureReconfigurationAssessment assess(
                            RendererFeatureProfile source,
                            RendererFeatureProfile target
                    ) {
                        return withLifecycleLock(() -> {
                            requireReady("plan feature transition");
                            return VulkanRendererHost.this.session.assessFeatureReconfiguration(
                                    source, target
                            );
                        });
                    }

                    @Override
                    public VulkanRenderingSession.FeatureReconfigurationResult apply(
                            RendererFeatureProfile target
                    ) {
                        return withLifecycleLock(() -> {
                            requireReady("apply feature transition");
                            VulkanRenderingSession.FeatureReconfigurationResult result;
                            try {
                                result = VulkanRendererHost.this.session.applyFeatureReconfiguration(target);
                            } catch (RuntimeException failure) {
                                transitionToFailed("apply feature transition", failure);
                                throw failure;
                            } catch (LinkageError failure) {
                                RuntimeException wrapped = new IllegalStateException(
                                        "native feature reconfiguration failed during apply", failure
                                );
                                transitionToFailed("apply feature transition", wrapped);
                                throw failure;
                            }
                            if (result.applied()) {
                                publishedFeatureCapabilities = Objects.requireNonNull(
                                        VulkanRendererHost.this.session.featureCapabilities(),
                                        "reconfigured session feature capabilities"
                                );
                            }
                            return result;
                        });
                    }
                }
        );
        this.managedPresenterOpener = Objects.requireNonNull(
                managedPresenterOpener, "managedPresenterOpener"
        );
    }

    private static VulkanGenericCommandSession openGenericCommands(
            VulkanRenderingSession session,
            RayTracingRendererConfig configuration
    ) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(configuration, "configuration");
        if (!(session instanceof VulkanGenericCommandRuntimeProvider provider)) {
            return null;
        }
        return new VulkanGenericCommandSession(provider.genericCommandRuntime(), configuration.maxFramesInFlight());
    }

    @Override
    public <T> Optional<T> extension(Class<T> extensionType) {
        Class<T> checkedType = Objects.requireNonNull(extensionType, "extensionType");
        if (checkedType.isInstance(featureController)) {
            return Optional.of(checkedType.cast(featureController));
        }
        if (checkedType.isInstance(publishedFeatureCapabilities)) {
            return withLifecycleLock(() -> {
                if (lifecycle == Lifecycle.READY && !sessionClosed) {
                    publishedFeatureCapabilities = Objects.requireNonNull(
                            session.featureCapabilities(), "session feature capabilities"
                    );
                } else {
                    publishedFeatureCapabilities = invalidateFeatureCapabilities(
                            publishedFeatureCapabilities,
                            publicStatus()
                    );
                }
                return Optional.of(checkedType.cast(publishedFeatureCapabilities));
            });
        }
        if (checkedType.isInstance(externalFrameConsumer)) {
            return Optional.of(checkedType.cast(externalFrameConsumer));
        }
        return checkedType.isInstance(this)
                ? Optional.of(checkedType.cast(this))
                : Optional.empty();
    }

    private static RendererFeatureProfile featureProfile(RayTracingRendererConfig configuration) {
        RayTracingRendererConfig checked = Objects.requireNonNull(configuration, "configuration");
        return new RendererFeatureProfile(
                checked.frameReconstruction(),
                checked.frameGeneration(),
                checked.lowLatency(),
                checked.denoising(),
                checked.rayTracingOptimizations()
        );
    }

    private static RenderingFeatureCapabilities invalidateFeatureCapabilities(
            RenderingFeatureCapabilities source,
            Status rendererStatus
    ) {
        RenderingFeatureCapabilities checked = Objects.requireNonNull(source, "source");
        String reason = "renderer lifecycle is " + Objects.requireNonNull(rendererStatus, "rendererStatus")
                + "; prior execution evidence is no longer current";
        RenderingFeatureCapabilities.Builder invalidated = RenderingFeatureCapabilities.builder();
        checked.features().forEach((feature, entry) -> invalidated.feature(
                feature,
                entry.status() == RenderingFeatureCapabilities.Status.DISABLED
                        ? entry
                        : RenderingFeatureCapabilities.Entry.of(
                                RenderingFeatureCapabilities.Status.BLOCKED,
                                entry.implementation(),
                                reason
                        )
        ));
        checked.technologies().forEach((technology, entry) -> invalidated.technology(
                technology,
                entry.status() == RenderingFeatureCapabilities.Status.DISABLED
                        ? entry
                        : RenderingFeatureCapabilities.Entry.of(
                                RenderingFeatureCapabilities.Status.BLOCKED,
                                entry.implementation(),
                                reason
                        )
        ));
        return invalidated.build();
    }

    private static void requireEquivalentPreparedStates(
            PersistentSceneRegistry.SceneState authority,
            VulkanSceneResidency.SceneChangeSet residentChanges
    ) {
        if (authority.revision() != residentChanges.revision()
                || authority.textures() != residentChanges.textures().statistics().liveSlots()
                || authority.materials() != residentChanges.materials().statistics().liveSlots()
                || authority.meshes() != residentChanges.meshes().statistics().liveSlots()
                || authority.instances() != residentChanges.instances().statistics().liveSlots()
                || authority.lights() != residentChanges.lights().statistics().liveSlots()) {
            throw new IllegalStateException("CPU scene authority and GPUScene prepared residency diverged");
        }
    }

    private static void requireEquivalentCommittedStates(
            PersistentSceneRegistry.SceneState authority,
            VulkanSceneResidency.SceneResidencyState resident
    ) {
        if (authority.revision() != resident.revision()
                || authority.textures() != resident.textures().liveSlots()
                || authority.materials() != resident.materials().liveSlots()
                || authority.meshes() != resident.meshes().liveSlots()
                || authority.instances() != resident.instances().liveSlots()
                || authority.lights() != resident.lights().liveSlots()) {
            throw new IllegalStateException("CPU scene authority and committed GPUScene residency diverged");
        }
    }

    private static RendererHealth.Failure classifyFailure(String operation, RuntimeException failure) {
        if (failure instanceof RendererDeviceException deviceFailure) {
            RendererHealth.Kind kind = switch (deviceFailure.reason()) {
                case DEVICE_LOST -> RendererHealth.Kind.DEVICE_LOST;
                case DEVICE_OUT_OF_MEMORY -> RendererHealth.Kind.DEVICE_OUT_OF_MEMORY;
                case HOST_OUT_OF_MEMORY -> RendererHealth.Kind.HOST_OUT_OF_MEMORY;
                case DRIVER_FAILURE -> RendererHealth.Kind.DRIVER_FAILURE;
            };
            return new RendererHealth.Failure(
                    kind,
                    deviceFailure.recoveryAction(),
                    deviceFailure.operation(),
                    java.util.OptionalInt.of(deviceFailure.nativeResult())
            );
        }
        return failure(
                RendererHealth.Kind.BACKEND_FAILURE,
                RendererDeviceException.RecoveryAction.ABORT,
                operation
        );
    }

    private static RendererHealth.Failure failure(
            RendererHealth.Kind kind,
            RendererDeviceException.RecoveryAction recoveryAction,
            String operation
    ) {
        return new RendererHealth.Failure(kind, recoveryAction, operation, java.util.OptionalInt.empty());
    }

    @Override
    public Status status() {
        return withLifecycleLock(this::statusLocked);
    }

    private Status statusLocked() {
        observeSessionState();
        return publicStatus();
    }

    @Override
    public RendererHealth health() {
        return withLifecycleLock(this::healthLocked);
    }

    private RendererHealth healthLocked() {
        observeSessionState();
        return new RendererHealth(
                publicStatus(),
                Optional.ofNullable(activeFailure),
                new RendererHealth.ResourceObligations(
                        openFrameLeases,
                        lifecycle != Lifecycle.READY && !sessionClosed,
                        recoveryPending
                )
        );
    }

    @Override
    public VulkanFramePresenter openPresenter(
            VulkanFramePresenterConfig presenterConfiguration
    ) {
        return withLifecycleLock(() -> openPresenterLocked(presenterConfiguration));
    }

    private VulkanFramePresenter openPresenterLocked(
            VulkanFramePresenterConfig presenterConfiguration
    ) {
        requireReady("open Vulkan frame presenter");
        if (managedPresenterOpen) {
            throw new RendererStateException(
                    "this renderer already owns an open Vulkan frame presenter",
                    publicStatus(),
                    null
            );
        }
        if (openFrameLeases != 0) {
            throw new RendererStateException(
                    "cannot open the managed presenter while an expert Vulkan frame lease is active",
                    publicStatus(),
                    null
            );
        }
        VulkanFramePresenterConfig checked = Objects.requireNonNull(
                presenterConfiguration, "presenterConfiguration"
        );
        VulkanDeviceRuntime presentationRuntime = managedPresentationRuntime();
        VulkanFramePresenter presenter = Objects.requireNonNull(
                managedPresenterOpener.open(
                        presentationRuntime,
                        session.gpuStableId(),
                        checked,
                        this::acquireLatestManagedFrame,
                        this::onManagedFrameRetired,
                        this::onManagedPresenterClosed
                ),
                "managed presenter opener result"
        );
        boolean published = false;
        try {
            int backendProducerLeadLimit = session.managedPresentationProducerLeadLimit();
            if (backendProducerLeadLimit <= 0) {
                throw new IllegalStateException("backend managed-presentation producer lead must be positive");
            }
            managedPresenterBacklogLimit = Math.min(
                    checked.maximumFramesQueuedAhead(), backendProducerLeadLimit
            );
            managedPresenterBackpressure = new FrameSubmissionDeferred(
                    SubmissionDeferralReason.PRESENTATION_BACKLOG,
                    "Vulkan presenter queue reached its configured producer lead of "
                            + managedPresenterBacklogLimit + " frames"
            );
            managedPresenterOutstandingFrames.clear();
            managedPresenterOpen = true;
            managedPresenterRetainsSession = presentationRuntime != null;
            published = true;
            return presenter;
        } finally {
            if (!published) presenter.close();
        }
    }

    private VulkanDeviceRuntime managedPresentationRuntime() {
        return session instanceof VulkanGpuSceneRenderingSession gpuSession
                ? gpuSession.deviceForAcceptance()
                : null;
    }

    private void onManagedFrameRetired(long frameSequence) {
        withLifecycleLock(() -> {
            while (!managedPresenterOutstandingFrames.isEmpty()
                    && managedPresenterOutstandingFrames.peekFirst() <= frameSequence) {
                managedPresenterOutstandingFrames.removeFirst();
            }
        });
    }

    private void onManagedPresenterClosed() {
        withLifecycleLock(() -> {
            if (!managedPresenterOpen) return;
            managedPresenterOpen = false;
            managedPresenterRetainsSession = false;
            managedPresenterBacklogLimit = 0;
            managedPresenterBackpressure = null;
            managedPresenterOutstandingFrames.clear();
            if (lifecycle != Lifecycle.READY) {
                RuntimeException closeFailure = closeSessionIfUnleased();
                if (closeFailure != null) {
                    recoveryPending = false;
                    if (terminalFailure == null) terminalFailure = closeFailure;
                    else terminalFailure.addSuppressed(closeFailure);
                    throw closeFailure;
                }
                clearResolvedCleanupFailure();
                recoverIfPossible();
            }
        });
    }

    @Override
    public SceneUpdateResult apply(SceneTransaction transaction) {
        return withLifecycleLock(() -> applyLocked(transaction));
    }

    private SceneUpdateResult applyLocked(SceneTransaction transaction) {
        requireReady("apply scene transaction");
        PersistentSceneRegistry.PreparedMutation prepared = scene.prepare(transaction);
        VulkanSceneResidency.PreparedUpdate preparedResidency;
        try {
            preparedResidency = residency.prepare(prepared.transaction());
            requireEquivalentPreparedStates(prepared.prospectiveState(), preparedResidency.changeSet());
        } catch (RuntimeException failure) {
            throw fail("prepare Vulkan scene residency", failure);
        }
        VulkanRenderingSession.SceneAdmission admission;
        try {
            admission = Objects.requireNonNull(
                    session.apply(new VulkanRenderingSession.SceneSubmission(
                            preparedResidency.changeSet(),
                            prepared.prospectiveState()
                    )),
                    "session scene admission"
            );
        } catch (VulkanRenderingSession.SubmissionRejectedException rejection) {
            throw rejected("scene transaction " + prepared.transaction().revision(), rejection);
        } catch (RuntimeException failure) {
            throw fail("apply scene transaction", failure);
        }

        try {
            if (admission.acceptedSceneRevision() != preparedResidency.revision()) {
                throw new IllegalStateException(
                        "session admitted a different scene revision: submitted="
                                + preparedResidency.revision()
                                + ", admitted=" + admission.acceptedSceneRevision()
                );
            }
            // Validate every authority before publication. Neither commit below can reject after
            // this point, which keeps host-visible semantic state and GPU slot ownership aligned.
            scene.validate(prepared);
            residency.validate(preparedResidency);
            PersistentSceneRegistry.SceneState committed = scene.commitValidated(prepared);
            VulkanSceneResidency.SceneResidencyState resident = residency.commitValidated(preparedResidency);
            requireEquivalentCommittedStates(committed, resident);
            return new SceneUpdateResult(committed.revision());
        } catch (RuntimeException invariantFailure) {
            throw fail("publish admitted scene transaction", invariantFailure);
        }
    }

    @Override
    public FrameSubmissionResult submit(RenderFrameRequest request) {
        FrameSubmissionAttempt attempt = withLifecycleLock(() -> trySubmitLocked(request));
        if (attempt instanceof FrameSubmitted submitted) return submitted.submission();
        FrameSubmissionDeferred deferred = (FrameSubmissionDeferred) attempt;
        throw new top.ceroxe.rt.renderer.api.SubmissionRejectedException(
                deferred.deferralReason(), deferred.detail()
        );
    }

    @Override
    public FrameSubmissionAttempt trySubmit(RenderFrameRequest request) {
        return withLifecycleLock(() -> trySubmitLocked(request));
    }

    @Override
    public RenderingSemanticCapabilities renderingSemanticCapabilities() {
        return withLifecycleLock(() -> {
            requireReady("query general rendering capabilities");
            RenderingSemanticCapabilities capabilities = genericCommands == null
                    ? RenderingSemanticCapabilities.unsupported()
                    : genericCommands.capabilities();
            if (genericCommands != null) {
                RenderingSemanticCapabilities.Builder builder = RenderingSemanticCapabilities.builder();
                for (RenderingSemanticCapabilities.Feature feature : RenderingSemanticCapabilities.Feature.values()) {
                    builder.feature(feature, capabilities.feature(feature));
                }
                builder.feature(
                        RenderingSemanticCapabilities.Feature.COMBINED_WORKLOADS,
                        new RenderingSemanticCapabilities.Entry(
                                RenderingSemanticCapabilities.Status.EXECUTABLE,
                                "retained RT submission is ordered before generic raster submission on the shared Vulkan frame queue"
                        )
                );
                return builder.build();
            }
            return capabilities;
        });
    }

    @Override
    public WorkloadExecutionEvidence submitWorkload(RenderWorkload workload) {
        return withLifecycleLock(() -> {
            requireReady("submit workload");
            RenderWorkload checked = Objects.requireNonNull(workload, "workload");
            if (checked.mode() != RenderWorkload.Mode.COMBINED) {
                return Renderer.super.submitWorkload(checked);
            }
            if (genericCommands == null) {
                return WorkloadExecutionEvidence.combinedUnsupported(
                        checked.sceneFrame().orElseThrow().sequence(),
                        "generic Vulkan command lane is unavailable for ordered RT/raster composition"
                );
            }
            FrameSubmissionAttempt sceneAttempt;
            try {
                sceneAttempt = trySubmitLocked(checked.sceneFrame().orElseThrow());
            } catch (RuntimeException failure) {
                throw fail("submit combined retained scene workload", failure);
            }
            if (sceneAttempt instanceof FrameSubmissionDeferred deferred) {
                return WorkloadExecutionEvidence.combinedDeferred(
                        checked.sceneFrame().orElseThrow().sequence(), deferred.detail()
                );
            }
            FrameSubmissionResult sceneSubmission = ((FrameSubmitted) sceneAttempt).submission();
            CommandExecutionEvidence graphics;
            try {
                graphics = genericCommands.submit(checked.graphicsCommands().orElseThrow());
            } catch (RuntimeException failure) {
                throw fail("submit combined generic commands", failure);
            }
            return WorkloadExecutionEvidence.combined(sceneSubmission, graphics);
        });
    }

    @Override
    public ResourceTransactionEvidence submitResources(RenderResourceTransaction transaction) {
        return withLifecycleLock(() -> {
            requireReady("submit general render resources");
            RenderResourceTransaction checked = Objects.requireNonNull(transaction, "transaction");
            if (genericCommands == null) return Renderer.super.submitResources(checked);
            try {
                return genericCommands.submitResources(checked);
            } catch (RuntimeException failure) {
                throw fail("submit general render resources", failure);
            }
        });
    }

    @Override
    public Optional<ResourceResidencyEvidence> resourceResidencyEvidence(ResourceGenerationKey generation) {
        Objects.requireNonNull(generation, "generation");
        return withLifecycleLock(() -> {
            requireReady("query general resource evidence");
            return genericCommands == null ? Optional.empty() : genericCommands.resourceEvidence(generation);
        });
    }

    @Override
    public CommandExecutionEvidence submitCommands(RenderCommandTransaction transaction) {
        return withLifecycleLock(() -> {
            requireReady("submit general render commands");
            RenderCommandTransaction checked = Objects.requireNonNull(transaction, "transaction");
            if (genericCommands == null) {
                return new CommandExecutionEvidence(
                        checked.sequence(), CommandExecutionEvidence.Outcome.REJECTED,
                        CommandExecutionEvidence.Reason.UNSUPPORTED_FEATURE,
                        java.util.OptionalLong.empty(), Optional.empty(), 0L,
                        "general render-command execution is unavailable for this Vulkan session"
                );
            }
            try {
                return genericCommands.submit(checked);
            } catch (RuntimeException failure) {
                throw fail("submit general render commands", failure);
            }
        });
    }

    @Override
    public Optional<CommandExecutionEvidence> commandExecutionEvidence(long transactionSequence) {
        if (transactionSequence < 0L) {
            throw new IllegalArgumentException("transaction sequence must not be negative");
        }
        return withLifecycleLock(() -> {
            requireReady("query general render-command evidence");
            return genericCommands == null ? Optional.empty() : genericCommands.commandEvidence(transactionSequence);
        });
    }

    private FrameSubmissionAttempt trySubmitLocked(RenderFrameRequest request) {
        requireReady("submit frame");
        RenderFrameRequest checked = Objects.requireNonNull(request, "request");
        if (checked.sequence() <= latestSubmittedFrameSequence) {
            throw new SubmissionOrderException(
                    "frame sequence must advance: current=" + latestSubmittedFrameSequence
                            + ", submitted=" + checked.sequence()
            );
        }

        long acceptedSceneRevision = scene.state().revision();
        if (checked.minimumSceneRevision() > acceptedSceneRevision) {
            throw new SceneRevisionException(
                    "frame requires scene revision " + checked.minimumSceneRevision()
                            + " but renderer has accepted only " + acceptedSceneRevision
            );
        }

        if (managedPresenterOpen
                && managedPresenterOutstandingFrames.size() >= managedPresenterBacklogLimit) {
            return Objects.requireNonNull(
                    managedPresenterBackpressure,
                    "managed presenter backpressure"
            );
        }

        VulkanRenderingSession.FrameAdmission admission;
        try {
            VulkanRenderingSession.FrameAdmissionAttempt attempt = Objects.requireNonNull(
                    session.trySubmit(new VulkanRenderingSession.FrameSubmission(checked, acceptedSceneRevision)),
                    "session frame admission attempt"
            );
            if (attempt instanceof VulkanRenderingSession.FrameDeferred deferred) {
                return new FrameSubmissionDeferred(
                        deferred.deferralReason(),
                        "Vulkan renderer deferred frame " + checked.sequence()
                                + " without publishing partial state: " + deferred.detail()
                );
            }
            admission = ((VulkanRenderingSession.FrameAdmitted) attempt).admission();
        } catch (FrameValidationException validation) {
            throw validation;
        } catch (RuntimeException failure) {
            throw fail("submit frame", failure);
        }

        if (admission.frameSequence() != checked.sequence()
                || admission.sceneRevision() != acceptedSceneRevision) {
            throw fail(
                    "verify frame admission",
                    new IllegalStateException(
                            "session admitted a different frame: requested=" + checked.sequence()
                                    + "/" + acceptedSceneRevision
                                    + ", admitted=" + admission.frameSequence()
                                    + "/" + admission.sceneRevision()
                    )
            );
        }
        // The previous-instance transform must follow the last successfully submitted frame. A
        // scene may be updated several times before a frame is accepted, so recording it at scene
        // transaction commit would produce a stale or future motion vector.
        residency.markFrameRendered(acceptedSceneRevision);
        latestSubmittedFrameSequence = checked.sequence();
        if (managedPresenterOpen) managedPresenterOutstandingFrames.addLast(checked.sequence());
        java.util.Set<HistoryInvalidationReason> historyInvalidations = admission.historyInvalidations();
        if (deviceRecoveredSinceLastFrame && configuration.temporalRendering().enabled()) {
            java.util.EnumSet<HistoryInvalidationReason> augmented = historyInvalidations.isEmpty()
                    ? java.util.EnumSet.noneOf(HistoryInvalidationReason.class)
                    : java.util.EnumSet.copyOf(historyInvalidations);
            augmented.add(HistoryInvalidationReason.DEVICE_RECOVERY);
            historyInvalidations = java.util.Set.copyOf(augmented);
        }
        deviceRecoveredSinceLastFrame = false;
        return new FrameSubmitted(FrameSubmissionResult.accepted(
                admission.frameSequence(), admission.sceneRevision(), historyInvalidations
        ));
    }

    @Override
    public VulkanFrameInterop.FramePollResult pollLatestFrame() {
        return withLifecycleLock(this::pollLatestFrameLocked);
    }

    private VulkanFrameInterop.FramePollResult pollLatestFrameLocked() {
        requireReady("poll latest GPU frame");
        if (managedPresenterOpen) {
            throw new RendererStateException(
                    "expert Vulkan frame polling is unavailable while the managed presenter is open",
                    publicStatus(),
                    null
            );
        }
        GpuFrameLease lease;
        try {
            lease = session.acquireLatestFrame();
        } catch (RuntimeException failure) {
            throw fail("acquire latest GPU frame", failure);
        }
        if (lease == null) {
            return VulkanFrameInterop.FrameNotReady.INSTANCE;
        }

        return new VulkanFrameInterop.FrameAvailable(trackAcquiredFrame(lease, true));
    }

    private GpuFrameLease acquireLatestManagedFrame() {
        return withLifecycleLock(() -> {
            requireReady("acquire latest managed GPU frame");
            if (!managedPresenterOpen) {
                throw new IllegalStateException("managed frame source requires an open presenter");
            }
            GpuFrameLease lease;
            try {
                lease = session.acquireLatestManagedFrame();
            } catch (RuntimeException failure) {
                throw fail("acquire latest managed GPU frame", failure);
            }
            return lease == null ? null : trackAcquiredFrame(lease, false);
        });
    }

    private GpuFrameLease trackAcquiredFrame(GpuFrameLease lease, boolean producerCompletedOnCpu) {
        try {
            GpuFrameLease.FrameDescriptor descriptor = Objects.requireNonNull(
                    lease.descriptor(), "lease descriptor"
            );
            if (lease.state() != GpuFrameLease.LeaseState.ACTIVE) {
                throw new IllegalStateException("session returned a consumed GPU frame lease");
            }
            if (lease.memoryHandle().state() != GpuFrameLease.HandleState.EXPORTED) {
                throw new IllegalStateException("session returned a non-exported memory handle");
            }
            if (lease.acquireSignal().isPresent()
                    && lease.acquireSignal().orElseThrow().handle().state()
                    != GpuFrameLease.HandleState.EXPORTED) {
                throw new IllegalStateException("session returned a non-exported acquire semaphore handle");
            }
            if (descriptor.frameSequence() <= latestAcquiredFrameSequence) {
                throw new IllegalStateException(
                        "session returned a non-new frame: latest=" + latestAcquiredFrameSequence
                                + ", returned=" + descriptor.frameSequence()
                );
            }
            if (descriptor.frameSequence() > latestSubmittedFrameSequence) {
                throw new IllegalStateException(
                        "session returned an unsubmitted frame " + descriptor.frameSequence()
                );
            }
            long acceptedSceneRevision = scene.state().revision();
            if (descriptor.renderedSceneRevision() > acceptedSceneRevision) {
                throw new IllegalStateException(
                        "session returned frame for unaccepted scene revision "
                                + descriptor.renderedSceneRevision()
                );
            }
            latestAcquiredFrameSequence = descriptor.frameSequence();
            if (producerCompletedOnCpu) {
                latestCompletedFrameSequence = Math.max(
                        latestCompletedFrameSequence, descriptor.frameSequence()
                );
            }
            openFrameLeases++;
            return new TrackedGpuFrameLease(lease, new FrameLeaseRetirement());
        } catch (RuntimeException contractFailure) {
            try {
                lease.close();
            } catch (RuntimeException closeFailure) {
                contractFailure.addSuppressed(closeFailure);
            }
            throw fail("validate acquired GPU frame", contractFailure);
        }
    }

    @Override
    public Optional<CpuFrame> pollLatestCpuFrame() {
        return withLifecycleLock(this::pollLatestCpuFrameLocked);
    }

    private Optional<CpuFrame> pollLatestCpuFrameLocked() {
        requireReady("poll latest CPU frame");
        if (!configuration.cpuFrameReadbackEnabled()) {
            throw new UnsupportedOperationException(
                    "managed CPU frame readback is disabled; consume VulkanFrameInterop leases instead"
            );
        }
        CpuFrame frame;
        try {
            frame = session.captureLatestCpuFrame(latestCpuFrameSequence);
        } catch (RuntimeException failure) {
            throw fail("capture latest CPU frame", failure);
        }
        if (frame == null) return Optional.empty();
        if (frame.frameSequence() <= latestCpuFrameSequence) {
            throw fail(
                    "validate captured CPU frame",
                    new IllegalStateException(
                            "session returned a non-new CPU frame: latest=" + latestCpuFrameSequence
                                    + ", returned=" + frame.frameSequence()
                    )
            );
        }
        if (frame.frameSequence() > latestSubmittedFrameSequence
                || frame.renderedSceneRevision() > scene.state().revision()) {
            throw fail(
                    "validate captured CPU frame",
                    new IllegalStateException("session returned CPU pixels for unaccepted work")
            );
        }
        latestCpuFrameSequence = frame.frameSequence();
        latestCompletedFrameSequence = Math.max(latestCompletedFrameSequence, frame.frameSequence());
        return Optional.of(frame);
    }

    @Override
    public RendererDiagnostics diagnostics() {
        return withLifecycleLock(this::diagnosticsLocked);
    }

    private RendererDiagnostics diagnosticsLocked() {
        observeSessionState();
        if (lifecycle == Lifecycle.READY) {
            refreshTelemetry();
        }
        PersistentSceneRegistry.SceneState sceneState = scene.state();
        return RendererDiagnostics.builder()
                .status(publicStatus())
                .latestAcceptedSceneRevision(sceneState.revision())
                .latestSubmittedFrameSequence(latestSubmittedFrameSequence)
                .latestCompletedFrameSequence(latestCompletedFrameSequence)
                .residentMeshes(sceneState.meshes())
                .residentInstances(sceneState.instances())
                .deviceRecovery(new RendererDiagnostics.DeviceRecovery(
                        successfulDeviceRecoveries,
                        automaticDeviceRecoveries,
                        failedDeviceRecoveries
                ))
                .frameGpuTiming(latestGpuTiming)
                .frameGenerationEvidence(latestFrameGenerationEvidence)
                .technologyExecutionEvidence(latestTechnologyExecutionEvidence)
                .build();
    }

    @Override
    public void close() {
        withLifecycleLock(this::closeLocked);
    }

    @Override
    public CompletionStage<Void> closeAsync() {
        try {
            close();
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
        return closeCompletion.minimalCompletionStage();
    }

    private void closeLocked() {
        if (lifecycle == Lifecycle.CLOSED && sessionClosed) {
            return;
        }
        lifecycle = Lifecycle.CLOSED;
        RuntimeException closeFailure = closeSessionIfUnleased();
        if (closeFailure != null) {
            if (terminalFailure != null) {
                terminalFailure.addSuppressed(closeFailure);
            } else {
                terminalFailure = closeFailure;
            }
            activeFailure = failure(
                    RendererHealth.Kind.RESOURCE_CLEANUP_FAILURE,
                    RendererDeviceException.RecoveryAction.ABORT,
                    "close Vulkan renderer session"
            );
            throw closeFailure;
        }
        clearResolvedCleanupFailure();
        if (sessionClosed) closeCompletion.complete(null);
    }

    Throwable terminalFailure() {
        return withLifecycleLock(() -> terminalFailure);
    }

    private Throwable terminalFailureLocked() {
        return terminalFailure;
    }

    private void refreshTelemetry() {
        VulkanRenderingSession.Telemetry telemetry;
        try {
            telemetry = Objects.requireNonNull(session.telemetry(), "session telemetry");
            if (telemetry.latestCompletedFrameSequence() < latestSessionCompletedFrameSequence) {
                throw new IllegalStateException(
                        "completed frame telemetry regressed within the active device generation from "
                                + latestSessionCompletedFrameSequence
                                + " to " + telemetry.latestCompletedFrameSequence()
                );
            }
            if (telemetry.latestCompletedFrameSequence() > latestSubmittedFrameSequence) {
                throw new IllegalStateException(
                        "completed frame telemetry exceeds submitted sequence: completed="
                                + telemetry.latestCompletedFrameSequence()
                                + ", submitted=" + latestSubmittedFrameSequence
                );
            }
            if (!configuration.gpuTimingsEnabled() && telemetry.frameGpuTiming().enabled()) {
                throw new IllegalStateException("session published GPU timings while configuration disabled them");
            }
            latestSessionCompletedFrameSequence = telemetry.latestCompletedFrameSequence();
            latestCompletedFrameSequence = Math.max(
                    latestCompletedFrameSequence,
                    latestSessionCompletedFrameSequence
            );
            latestGpuTiming = telemetry.frameGpuTiming();
            latestFrameGenerationEvidence = telemetry.frameGenerationEvidence();
            latestTechnologyExecutionEvidence = telemetry.technologyExecutionEvidence();
        } catch (RuntimeException failure) {
            transitionToFailed("read renderer telemetry", failure);
        }
    }

    private void requireReady(String operation) {
        observeSessionState();
        if (lifecycle != Lifecycle.READY) {
            throw new RendererStateException(
                    "renderer is not ready for " + operation + ": status=" + publicStatus(),
                    publicStatus(),
                    terminalFailure
            );
        }
    }

    private void observeSessionState() {
        if (lifecycle != Lifecycle.READY) {
            return;
        }
        VulkanRenderingSession.State state;
        try {
            state = Objects.requireNonNull(session.state(), "session state");
        } catch (RuntimeException failure) {
            transitionToFailed("observe Vulkan session state", failure);
            return;
        }
        if (state != VulkanRenderingSession.State.READY) {
            transitionToFailed(
                    "observe Vulkan session state",
                    new IllegalStateException("Vulkan session left READY state: " + state)
            );
        }
    }

    private top.ceroxe.rt.renderer.api.SubmissionRejectedException rejected(
            String submission,
            VulkanRenderingSession.SubmissionRejectedException rejection
    ) {
        return new top.ceroxe.rt.renderer.api.SubmissionRejectedException(
                rejection.deferralReason(),
                "Vulkan renderer rejected " + submission + " without publishing partial state: "
                        + rejection.detail(),
                rejection
        );
    }

    private RendererException fail(String operation, RuntimeException failure) {
        transitionToFailed(operation, failure);
        if (failure instanceof RendererDeviceException deviceFailure) {
            if (lifecycle == Lifecycle.READY
                    && deviceFailure.recoveryAction()
                    == RendererDeviceException.RecoveryAction.RECREATE_RENDERER) {
                return new RendererDeviceException(
                        deviceFailure.getMessage() + "; Vulkan device recreation completed, retry the operation",
                        deviceFailure.reason(),
                        RendererDeviceException.RecoveryAction.RETRY_OPERATION,
                        deviceFailure.operation(),
                        deviceFailure.nativeResult()
                );
            }
            return deviceFailure;
        }
        return new RendererStateException(
                "Vulkan renderer failed during " + operation,
                Status.FAILED,
                failure
        );
    }

    private void transitionToFailed(String operation, RuntimeException failure) {
        if (lifecycle != Lifecycle.READY) {
            return;
        }
        lifecycle = Lifecycle.FAILED;
        terminalFailure = new IllegalStateException("Vulkan renderer failed during " + operation, failure);
        activeFailure = classifyFailure(operation, failure);
        recoveryPending = canAutomaticallyRecover(failure);
        RuntimeException closeFailure = closeSessionIfUnleased();
        if (closeFailure != null) {
            recoveryPending = false;
            terminalFailure.addSuppressed(closeFailure);
        }
        recoverIfPossible();
    }

    private void onFrameLeaseClosed(FrameLeaseRetirement retirement) {
        withLifecycleLock(() -> onFrameLeaseClosedLocked(retirement));
    }

    private void onFrameLeaseClosedLocked(FrameLeaseRetirement retirement) {
        if (!retirement.countRetired) {
            if (openFrameLeases <= 0) {
                throw new IllegalStateException("GPU frame lease tracking underflow");
            }
            openFrameLeases--;
            retirement.countRetired = true;
        }
        if (lifecycle == Lifecycle.READY) {
            return;
        }
        RuntimeException closeFailure = closeSessionIfUnleased();
        if (closeFailure != null) {
            recoveryPending = false;
            if (terminalFailure != null) {
                terminalFailure.addSuppressed(closeFailure);
            } else {
                terminalFailure = closeFailure;
                activeFailure = failure(
                        RendererHealth.Kind.RESOURCE_CLEANUP_FAILURE,
                        RendererDeviceException.RecoveryAction.ABORT,
                        "close deferred Vulkan renderer session"
                );
                throw closeFailure;
            }
        }
        clearResolvedCleanupFailure();
        recoverIfPossible();
    }

    private RuntimeException closeSessionIfUnleased() {
        if (sessionClosed || openFrameLeases != 0 || managedPresenterRetainsSession) {
            return null;
        }
        RuntimeException failure = null;
        if (lifecycle == Lifecycle.CLOSED) externalFrameConsumer.close();
        if (genericCommands != null) {
            try {
                genericCommands.close();
            } catch (RuntimeException closeFailure) {
                failure = closeFailure;
            } finally {
                genericCommands = null;
            }
        }
        try {
            session.close();
            sessionClosed = true;
            if (lifecycle == Lifecycle.CLOSED) closeCompletion.complete(null);
        } catch (RuntimeException closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        return failure;
    }

    private void clearResolvedCleanupFailure() {
        if (sessionClosed && activeFailure != null
                && activeFailure.kind() == RendererHealth.Kind.RESOURCE_CLEANUP_FAILURE) {
            activeFailure = null;
            terminalFailure = null;
        }
    }

    private boolean canAutomaticallyRecover(RuntimeException failure) {
        return sessionFactory != null
                && automaticDeviceRecoveries < MAX_AUTOMATIC_DEVICE_RECOVERIES
                && failure instanceof RendererDeviceException deviceFailure
                && deviceFailure.recoveryAction() == RendererDeviceException.RecoveryAction.RECREATE_RENDERER;
    }

    private void recoverIfPossible() {
        if (!recoveryPending || lifecycle != Lifecycle.FAILED || openFrameLeases != 0 || !sessionClosed) {
            return;
        }
        recoveryPending = false;
        automaticDeviceRecoveries++;
            VulkanRenderingSession candidate = null;
        try {
            candidate = Objects.requireNonNull(sessionFactory.open(), "recovered Vulkan session");
            if (candidate.state() != VulkanRenderingSession.State.READY) {
                throw new IllegalStateException("recovered Vulkan session is not READY: " + candidate.state());
            }
            if (!negotiatedFeatureCapabilities.equals(candidate.featureCapabilities())) {
                throw new IllegalStateException("recovered Vulkan session negotiated different feature capabilities");
            }
            VulkanSceneResidency recoveredResidency = replayCommittedScene(candidate);
            VulkanGenericCommandSession recoveredGenericCommands = openGenericCommands(candidate, configuration);
            session = candidate;
            genericCommands = recoveredGenericCommands;
            publishedFeatureCapabilities = candidate.featureCapabilities();
            residency = recoveredResidency;
            // Accepted frames belonged to the discarded session and can no longer produce a
            // lease. Retaining their producer permits would deadlock a still-open presenter
            // immediately after otherwise successful device recreation.
            managedPresenterOutstandingFrames.clear();
            sessionClosed = false;
            latestSessionCompletedFrameSequence = -1L;
            latestCpuFrameSequence = -1L;
            latestGpuTiming = RendererDiagnostics.FrameGpuTiming.unavailable();
            latestFrameGenerationEvidence = FrameGenerationEvidence.unavailable();
            latestTechnologyExecutionEvidence = TechnologyExecutionEvidence.disabled();
            terminalFailure = null;
            activeFailure = null;
            lifecycle = Lifecycle.READY;
            successfulDeviceRecoveries++;
            deviceRecoveredSinceLastFrame = true;
            candidate = null;
        } catch (RuntimeException recoveryFailure) {
            failedDeviceRecoveries++;
            activeFailure = failure(
                    RendererHealth.Kind.RECOVERY_FAILURE,
                    RendererDeviceException.RecoveryAction.RECREATE_RENDERER,
                    "recreate Vulkan renderer device"
            );
            if (terminalFailure == null) {
                terminalFailure = new IllegalStateException("Vulkan renderer recovery failed", recoveryFailure);
            } else {
                terminalFailure.addSuppressed(recoveryFailure);
            }
        } finally {
            if (candidate != null) {
                try {
                    candidate.close();
                } catch (RuntimeException closeFailure) {
                    if (terminalFailure != null) {
                        terminalFailure.addSuppressed(closeFailure);
                    }
                }
            }
        }
    }

    private VulkanSceneResidency replayCommittedScene(VulkanRenderingSession candidate) {
        PersistentSceneRegistry.Snapshot snapshot = scene.snapshot();
        VulkanSceneResidency recoveredResidency = new VulkanSceneResidency();
        if (!snapshot.initialized()) {
            return recoveredResidency;
        }
        SceneTransaction reset = SceneTransaction.builder(snapshot.state().revision())
                .resetScene()
                .upsertTextures(snapshot.textures())
                .upsertMaterials(snapshot.materials())
                .upsertMeshes(snapshot.meshes())
                .upsertInstances(snapshot.instances())
                .upsertLights(snapshot.lights())
                .build();
        VulkanSceneResidency.PreparedUpdate prepared = recoveredResidency.prepare(reset);
        VulkanRenderingSession.SceneAdmission admission;
        try {
            admission = Objects.requireNonNull(
                    candidate.apply(new VulkanRenderingSession.SceneSubmission(
                            prepared.changeSet(), snapshot.state())),
                    "recovered scene admission"
            );
        } catch (VulkanRenderingSession.SubmissionRejectedException rejection) {
            throw new IllegalStateException("recovered Vulkan session rejected authoritative scene replay", rejection);
        }
        if (admission.acceptedSceneRevision() != snapshot.state().revision()) {
            throw new IllegalStateException(
                    "recovered Vulkan session admitted scene revision " + admission.acceptedSceneRevision()
                            + " instead of " + snapshot.state().revision()
            );
        }
        recoveredResidency.validate(prepared);
        VulkanSceneResidency.SceneResidencyState committed = recoveredResidency.commitValidated(prepared);
        requireEquivalentCommittedStates(snapshot.state(), committed);
        return recoveredResidency;
    }

    private Status publicStatus() {
        return switch (lifecycle) {
            case READY -> Status.READY;
            case FAILED -> recoveryPending ? Status.RECOVERING : Status.FAILED;
            case CLOSED -> Status.CLOSED;
        };
    }

    private enum Lifecycle {
        READY,
        FAILED,
        CLOSED
    }

    /**
     * Package-private construction seam for deterministic lifecycle and flow-control tests.
     * Production always binds this seam to the native GLFW/Vulkan presenter.
     */
    @FunctionalInterface
    interface ManagedPresenterOpener {
        VulkanFramePresenter open(
                VulkanDeviceRuntime runtime,
                String gpuStableId,
                VulkanFramePresenterConfig configuration,
                Supplier<GpuFrameLease> managedFrameSupplier,
                LongConsumer frameRetiredCallback,
                Runnable closeCallback
        );
    }

    /**
     * Per-lease idempotency token separating logical retirement from fallible session teardown.
     * A retry after native close failure must retry teardown without decrementing the host's
     * outstanding-lease counter twice.
     */
    private final class FrameLeaseRetirement implements Runnable {
        private boolean countRetired;

        @Override
        public void run() {
            onFrameLeaseClosed(this);
        }
    }
}
