package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease;
import top.ceroxe.rt.renderer.api.CpuFrame;
import top.ceroxe.rt.renderer.api.FrameValidationException;
import top.ceroxe.rt.renderer.api.RayTracingRendererConfig;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.RendererFeaturePreference;
import top.ceroxe.rt.renderer.api.RendererFeaturePlan;
import top.ceroxe.rt.renderer.api.RendererFeatureProfile;
import top.ceroxe.rt.renderer.api.SubmissionDeferralReason;
import top.ceroxe.rt.renderer.api.RendererDeviceException;
import top.ceroxe.rt.renderer.api.HistoryInvalidationReason;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities;
import top.ceroxe.rt.renderer.feature.VulkanFeatureSession;
import top.ceroxe.rt.renderer.feature.VulkanFeatureFallbackException;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.pipeline.GpuSceneRayTracingPipeline;
import top.ceroxe.rt.renderer.rt.pipeline.VulkanFrameExtents;

import java.util.List;
import java.util.ArrayList;
import java.util.Objects;

/** Production session joining persistent GPUScene convergence to bounded hardware RT frames. */
final class VulkanGpuSceneRenderingSession implements VulkanRenderingSession {
    private static final String FRAME_TIMING_LABEL = "gpuSceneFrame";

    private final RayTracingRendererConfig configuration;
    private final String gpuStableId;
    private final VulkanSceneRuntime scene;
    private final GpuSceneRayTracingPipeline pipeline;
    private final VulkanGpuSceneFrameRing frameRing;
    private final VulkanFrameAdmissionLimits frameAdmissionLimits;
    private final VulkanGpuSceneTemporalCoordinator temporalCoordinator;
    private final VulkanGpuSceneFeatureComposition featureComposition;
    private final VulkanGpuSceneDescriptorAssembler descriptorAssembler;
    private final VulkanGpuSceneFrameDiagnostics frameDiagnostics;
    private final VulkanGpuSceneFrameSubmitter frameSubmitter;

    private State state = State.READY;
    private boolean pipelineClosed;
    private boolean sceneClosed;
    private boolean resourcesClosed;
    private long acceptedSceneRevision = -1L;
    private int acceptedLightSlotUpperBound;
    private long latestCompletedDescriptorEpoch;
    private long latestCompletedFrameSequence = -1L;
    private long latestAcquiredFrameSequence = -1L;
    private RuntimeException terminalFailure;
    private RendererFeatureProfile currentFeatureProfile;

    static VulkanGpuSceneRenderingSession open(
            VulkanRtCapabilityProbe.Result capability,
            RayTracingRendererConfig configuration,
            RendererRtDiagnostics diagnostics
    ) {
        RayTracingRendererConfig checkedConfiguration = Objects.requireNonNull(configuration, "configuration");
        VulkanSceneRuntime scene = null;
        GpuSceneRayTracingPipeline pipeline = null;
        VulkanGpuSceneTemporalCoordinator temporalCoordinator = null;
        VulkanGpuSceneFrameRing frameRing = null;
        VulkanGpuSceneFeatureComposition featureComposition = null;
        try {
            scene = VulkanSceneRuntime.open(
                    Objects.requireNonNull(capability, "capability"),
                    Objects.requireNonNull(diagnostics, "diagnostics"),
                    checkedConfiguration.validationEnabled(),
                    checkedConfiguration.gpuTimingsEnabled(),
                    checkedConfiguration
            );
            VulkanDeviceRuntime device = scene.device();
            VulkanFrameOutput frameOutput = VulkanFrameOutput.from(checkedConfiguration.frameOutputFormat());
            VulkanDeviceRuntime.ExternalFrameInterop interop = device.externalFrameInterop(frameOutput.vkFormat());
            if (!interop.memoryExportReady()) {
                throw new IllegalStateException(
                        "selected Vulkan device cannot export renderer frame memory: " + interop.reason()
                );
            }
            VulkanFrameOutputSupport.requireSupported(device.physicalDevice(), frameOutput);
            temporalCoordinator = new VulkanGpuSceneTemporalCoordinator(device, checkedConfiguration);
            VulkanGpuSceneFeatureComposition.Selection featureSelection =
                    VulkanGpuSceneFeatureComposition.select(device.featureCapabilities());
            pipeline = GpuSceneRayTracingPipeline.open(
                    device,
                    checkedConfiguration.maxFramesInFlight(),
                    frameOutput.linearHdr() || featureSelection.denoising() || featureSelection.reconstruction()
            );
            // The monolithic GPUScene descriptor layout always declares both NRD and
            // reconstruction bindings. These sentinels supply whichever optional feature is
            // inactive; both families may be active and are composed transactionally.
            featureComposition = VulkanGpuSceneFeatureComposition.open(
                    device,
                    checkedConfiguration.maxFramesInFlight(),
                    frameOutput.linearHdr(),
                    featureSelection
            );
            frameRing = VulkanGpuSceneFrameRing.open(
                    device, checkedConfiguration, frameOutput, interop, diagnostics, featureSelection
            );
            VulkanFrameAdmissionLimits frameAdmissionLimits = new VulkanFrameAdmissionLimits(
                    device.maxImageDimension2D(),
                    capability.preferredDevice().maxRayDispatchInvocationCount()
            );
            VulkanGpuSceneRenderingSession result = new VulkanGpuSceneRenderingSession(
                    checkedConfiguration,
                    capability.preferredDevice().stableId(),
                    scene,
                    pipeline,
                    temporalCoordinator,
                    frameRing,
                    featureComposition,
                    frameAdmissionLimits
            );
            scene = null;
            pipeline = null;
            temporalCoordinator = null;
            frameRing = null;
            featureComposition = null;
            return result;
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            closeSuppressing(failure, frameRing);
            closeSuppressing(failure, featureComposition);
            closeSuppressing(failure, pipeline);
            closeSuppressing(failure, temporalCoordinator);
            closeSuppressing(failure, scene);
            throw failure;
        }
    }

    private VulkanGpuSceneRenderingSession(
            RayTracingRendererConfig configuration,
            String gpuStableId,
            VulkanSceneRuntime scene,
            GpuSceneRayTracingPipeline pipeline,
            VulkanGpuSceneTemporalCoordinator temporalCoordinator,
            VulkanGpuSceneFrameRing frameRing,
            VulkanGpuSceneFeatureComposition featureComposition,
            VulkanFrameAdmissionLimits frameAdmissionLimits
    ) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.gpuStableId = Objects.requireNonNull(gpuStableId, "gpuStableId");
        if (gpuStableId.isBlank()) throw new IllegalArgumentException("gpuStableId must not be blank");
        this.scene = Objects.requireNonNull(scene, "scene");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.temporalCoordinator = Objects.requireNonNull(temporalCoordinator, "temporalCoordinator");
        this.frameRing = Objects.requireNonNull(frameRing, "frameRing");
        this.featureComposition = Objects.requireNonNull(featureComposition, "featureComposition");
        descriptorAssembler = new VulkanGpuSceneDescriptorAssembler(scene, featureComposition);
        frameDiagnostics = new VulkanGpuSceneFrameDiagnostics(
                scene.device(), featureComposition, configuration.gpuTimingsEnabled(), FRAME_TIMING_LABEL
        );
        frameSubmitter = new VulkanGpuSceneFrameSubmitter(
                scene, pipeline, frameRing, temporalCoordinator, featureComposition,
                descriptorAssembler, FRAME_TIMING_LABEL
        );
        this.frameAdmissionLimits = Objects.requireNonNull(frameAdmissionLimits, "frameAdmissionLimits");
        currentFeatureProfile = featureProfile(configuration);
        if (this.frameRing.size() != configuration.maxFramesInFlight()) {
            throw new IllegalArgumentException("frame slot count diverges from renderer configuration");
        }
    }

    @Override
    public synchronized State state() {
        return state;
    }

    @Override
    public String gpuStableId() {
        return gpuStableId;
    }

    @Override
    public RenderingFeatureCapabilities featureCapabilities() {
        return scene.device().featureCapabilities();
    }

    @Override
    public int managedPresentationProducerLeadLimit() {
        /*
         * Every accepted frame owns a distinct slot and Streamline frame token. Frame-generation
         * tags use eValidUntilPresent, while the slot remains immutable through its matching proxy
         * present and any published input-completion debt. The native executor retains those tags
         * by sequence, so multiple bounded slots do not replace one another's bindings. Returning
         * the ring capacity restores render/present overlap without weakening resource lifetime;
         * the presenter configuration may still select a smaller queue-ahead limit.
         */
        return frameRing.size();
    }

    @Override
    public synchronized FeatureReconfigurationAssessment assessFeatureReconfiguration(
            RendererFeatureProfile source,
            RendererFeatureProfile target
    ) {
        requireReady("plan feature transition");
        RendererFeatureProfile checkedSource = Objects.requireNonNull(source, "source");
        RendererFeatureProfile checkedTarget = Objects.requireNonNull(target, "target");
        if (!currentFeatureProfile.equals(checkedSource)) {
            return FeatureReconfigurationAssessment.rendererRebuild(
                    "controller source profile no longer matches the rendering session"
            );
        }
        boolean serChanged = checkedSource.rayTracingOptimizations().shaderExecutionReordering()
                != checkedTarget.rayTracingOptimizations().shaderExecutionReordering();
        boolean memoryChanged = checkedSource.rayTracingOptimizations().memoryOptimization()
                != checkedTarget.rayTracingOptimizations().memoryOptimization();
        if (serChanged && memoryChanged) {
            return FeatureReconfigurationAssessment.rendererRebuild(
                    "combined SER pipeline and acceleration-memory ownership change requires renderer rebuild"
            );
        }
        if (serChanged) {
            return new FeatureReconfigurationAssessment(
                    RendererFeaturePlan.Disposition.REQUIRES_PIPELINE_REBUILD,
                    RendererFeaturePlan.Boundary.PIPELINE_REBUILD,
                    "SER is compiled into the active ray-generation pipeline permutation"
            );
        }
        if (memoryChanged) {
            return new FeatureReconfigurationAssessment(
                    RendererFeaturePlan.Disposition.REQUIRES_SCENE_REBUILD,
                    RendererFeaturePlan.Boundary.SCENE_REBUILD,
                    "acceleration-structure memory ownership is retained by existing BLAS resources"
            );
        }
        VulkanFeatureSession.ReconfigurationAssessment provider =
                scene.device().featureSession().assessReconfiguration(
                        checkedSource, checkedTarget
                );
        return new FeatureReconfigurationAssessment(
                provider.disposition(), provider.boundary(), provider.reason()
        );
    }

    @Override
    public synchronized FeatureReconfigurationResult applyFeatureReconfiguration(
            RendererFeatureProfile target
    ) {
        requireReady("apply feature transition");
        RendererFeatureProfile checkedTarget = Objects.requireNonNull(target, "target");
        pump();
        if (!frameRing.allSlotsWritable()) {
            return FeatureReconfigurationResult.retry(
                    "feature transition is waiting for every producer and external frame lease to retire"
            );
        }
        VulkanFeatureSession featureSession = scene.device().featureSession();
        try {
            featureSession.applyReconfiguration(currentFeatureProfile, checkedTarget);
            RenderingFeatureCapabilities capabilities = Objects.requireNonNull(
                    featureSession.capabilities(), "reconfigured feature capabilities"
            );
            featureComposition.applyReconfiguration(capabilities);
            temporalCoordinator.invalidate(HistoryInvalidationReason.EXPLICIT_RESET);
            // Publish the Java profile only after every native/core owner has committed. If any
            // step above throws, fail-closed keeps the old profile from being reported as live.
            currentFeatureProfile = checkedTarget;
        } catch (RuntimeException failure) {
            // Provider application may have crossed a native boundary before reporting failure.
            // Poison this session immediately so the controller cannot publish the old profile
            // while queued work observes a partially changed provider state.
            throw fail("apply feature reconfiguration", failure);
        } catch (LinkageError failure) {
            state = State.FAILED;
            terminalFailure = new IllegalStateException(
                    "native feature reconfiguration crossed an unrecoverable boundary", failure
            );
            throw failure;
        }
        return FeatureReconfigurationResult.applied(
                "feature profile committed after a drained frame boundary; temporal history reset"
        );
    }

    @Override
    public synchronized SceneAdmission apply(SceneSubmission submission) throws SubmissionRejectedException {
        requireReady("apply scene");
        SceneSubmission checked = Objects.requireNonNull(submission, "submission");
        try {
            pump();
            /*
             * Scene uploads and acceleration updates overwrite storage used by ray dispatches and
             * same-submission frame-primitive TLAS builds.
             * Apply backpressure only while a renderer producer fence is outstanding. Waiting for
             * the whole frame queue here would also include presentation-completion submissions and
             * can deadlock against the presenter queue's wait on the renderer-ready semaphore.
             */
            if (frameRing.hasProducerPending()) {
                throw new SubmissionRejectedException(
                        SubmissionDeferralReason.SCENE_UPDATE_BACKLOG,
                        "scene mutation is waiting for submitted renderer frames to release scene references"
                );
            }
            VulkanSceneRuntime.Admission admission = scene.apply(
                    checked.residentChanges(),
                    frameSubmitter.lastSubmittedDescriptorEpoch()
            );
            if (admission.revision() != checked.residentChanges().revision()) {
                throw new IllegalStateException("scene runtime admitted a different revision");
            }
            acceptedSceneRevision = admission.revision();
            acceptedLightSlotUpperBound = checked.residentChanges()
                    .lights().statistics().slotUpperBound();
            temporalCoordinator.sceneApplied(checked.residentChanges());
            return new SceneAdmission(acceptedSceneRevision);
        } catch (VulkanSceneRuntime.BusyException busy) {
            if (busy.getCause() instanceof VulkanMemoryBudgetRejectedException) {
                long reclaimedBytes;
                try {
                    reclaimedBytes = frameRing.reclaimIdleOutputs();
                } catch (RuntimeException reclaimFailure) {
                    throw fail("reclaim idle frame outputs after GPU memory pressure", reclaimFailure);
                }
                if (reclaimedBytes > 0L) {
                    return apply(checked);
                }
            }
            SubmissionDeferralReason reason = busy.getCause() instanceof VulkanMemoryBudgetRejectedException
                    ? SubmissionDeferralReason.RESOURCE_PRESSURE
                    : SubmissionDeferralReason.PROVIDER_CAPACITY;
            throw new SubmissionRejectedException(reason, busy.getMessage());
        } catch (RuntimeException failure) {
            throw fail("apply GPUScene generation", failure);
        }
    }

    @Override
    public synchronized FrameAdmission submit(FrameSubmission submission) throws SubmissionRejectedException {
        FrameAdmissionAttempt attempt = trySubmit(submission);
        if (attempt instanceof FrameAdmitted admitted) return admitted.admission();
        FrameDeferred deferred = (FrameDeferred) attempt;
        throw new SubmissionRejectedException(deferred.deferralReason(), deferred.detail());
    }

    @Override
    public synchronized FrameAdmissionAttempt trySubmit(FrameSubmission submission) {
        requireReady("submit frame");
        FrameSubmission checked = Objects.requireNonNull(submission, "submission");
        try {
            pump();
            // Capability probing and extent negotiation can cross the JNI boundary. A saturated
            // ring cannot admit their result, so reject it before doing that work on every retry.
            if (frameRing.writableSlot() == null) {
                return new FrameDeferred(
                        SubmissionDeferralReason.FRAME_RING_FULL,
                        "all bounded frame slots are retained or in flight"
                );
            }
            VulkanFeatureSession featureSession = scene.device().featureSession();
            featureComposition.refresh(featureSession.capabilities());
            boolean refreshAfterExtentNegotiation =
                    featureSession.extentNegotiationMayChangeCapabilities();
            VulkanFrameExtents extents = featureSession.negotiateFrameExtents(
                    checked.request(),
                    VulkanFrameExtents.identity(checked.request().width(), checked.request().height())
            );
            // Extent negotiation may itself select fallback. Refresh before the free slot adopts
            // the feature contract so a failed implementation cannot leak into this frame.
            if (refreshAfterExtentNegotiation) {
                featureComposition.refresh(featureSession.capabilities());
            }
            frameAdmissionLimits.validate(extents);
            validateRequiredTemporalContract(checked.request());
            return new FrameAdmitted(
                    frameSubmitter.submit(
                            checked, extents, acceptedSceneRevision, acceptedLightSlotUpperBound
                    )
            );
        } catch (SubmissionRejectedException rejection) {
            return new FrameDeferred(rejection.deferralReason(), rejection.detail());
        } catch (VulkanFeatureFallbackException fallback) {
            // The provider changed only its next-frame feature plan. Recording failed before queue
            // publication, so keep the renderer alive and retry this sequence against that plan.
            temporalCoordinator.invalidate(HistoryInvalidationReason.EXPLICIT_RESET);
            return new FrameDeferred(
                    SubmissionDeferralReason.FEATURE_RECONFIGURATION,
                    fallback.getMessage()
            );
        } catch (FrameValidationException validation) {
            throw validation;
        } catch (RuntimeException failure) {
            // A command buffer that was not published cannot advance temporal provenance.
            temporalCoordinator.invalidate(HistoryInvalidationReason.EXPLICIT_RESET);
            throw fail("submit GPUScene frame", failure);
        }
    }

    private void validateRequiredTemporalContract(RenderFrameRequest request) {
        if (request.depthProjection().known()) return;
        List<String> required = new ArrayList<>(3);
        if (featureComposition.reconstructionActive()
                && currentFeatureProfile.frameReconstruction().preference()
                == RendererFeaturePreference.REQUIRED) {
            required.add("frame reconstruction");
        }
        if (featureComposition.denoisingActive()
                && currentFeatureProfile.denoising().preference()
                == RendererFeaturePreference.REQUIRED) {
            required.add("denoising");
        }
        if (featureComposition.frameGenerationActive()
                && currentFeatureProfile.frameGeneration().preference()
                == RendererFeaturePreference.REQUIRED) {
            required.add("frame generation");
        }
        if (!required.isEmpty()) {
            throw new FrameValidationException(
                    FrameValidationException.Reason.MISSING_DEPTH_PROJECTION,
                    String.join(", ", required)
                            + " requires the exact Vulkan depth projection; the request marked it unknown"
            );
        }
    }

    private static RendererFeatureProfile featureProfile(RayTracingRendererConfig configuration) {
        return new RendererFeatureProfile(
                configuration.frameReconstruction(),
                configuration.frameGeneration(),
                configuration.lowLatency(),
                configuration.denoising(),
                configuration.rayTracingOptimizations()
        );
    }

    @Override
    public synchronized GpuFrameLease acquireLatestFrame() {
        requireReady("acquire latest frame");
        try {
            pump();
            VulkanFrameSlot latest = frameRing.latestCompletedSlot();
            if (latest == null || latest.frameSequence() <= latestAcquiredFrameSequence) return null;
            /*
             * Completed images are retained until a consumer actually asks for one. Reclaiming
             * them from pump() let an uncapped producer render and discard thousands of frames
             * while starving a swapchain consumer on the same physical GPU. Once the caller asks
             * for the latest image, older completions are genuinely superseded and may be freed.
             */
            frameRing.discardCompletedExcept(latest);
            GpuFrameLease lease = latest.acquire();
            latestAcquiredFrameSequence = lease.descriptor().frameSequence();
            return lease;
        } catch (RuntimeException failure) {
            throw fail("export completed GPUScene frame", failure);
        }
    }

    @Override
    public synchronized GpuFrameLease acquireLatestManagedFrame() {
        requireReady("acquire managed frame");
        try {
            pump();
            VulkanFrameSlot earliest = frameRing.earliestManagedPresentableSlot(latestAcquiredFrameSequence);
            if (earliest == null || earliest.frameSequence() <= latestAcquiredFrameSequence) return null;
            frameRing.discardCompletedExcept(earliest);
            GpuFrameLease lease = earliest.acquireManaged();
            latestAcquiredFrameSequence = lease.descriptor().frameSequence();
            return lease;
        } catch (RuntimeException failure) {
            throw fail("export managed GPUScene frame", failure);
        }
    }

    @Override
    public synchronized CpuFrame captureLatestCpuFrame(long afterFrameSequence) {
        if (afterFrameSequence < -1L) {
            throw new IllegalArgumentException("afterFrameSequence must be at least -1");
        }
        requireReady("capture latest CPU frame");
        if (!configuration.cpuFrameReadbackEnabled()) {
            throw new UnsupportedOperationException(
                    "managed CPU frame readback is disabled by renderer configuration"
            );
        }
        try {
            pump();
            VulkanFrameSlot latest = frameRing.latestCompletedSlot();
            if (latest == null || latest.frameSequence() <= afterFrameSequence) return null;
            CpuFrame frame = frameDiagnostics.captureCpu(latest);
            // The immutable CPU copy owns its pixels, so no completed GPU output must remain
            // retained merely to keep the returned frame alive.
            frameRing.discardAllCompleted();
            return frame;
        } catch (RuntimeException failure) {
            throw fail("capture latest CPU frame", failure);
        }
    }

    @Override
    public synchronized Telemetry telemetry() {
        requireReady("read telemetry");
        try {
            pump();
            return new Telemetry(
                    latestCompletedFrameSequence,
                    frameDiagnostics.frameTiming(),
                    scene.device().featureSession().frameGenerationEvidence(),
                    scene.device().technologyExecutionEvidence()
            );
        } catch (RuntimeException failure) {
            throw fail("read GPUScene telemetry", failure);
        }
    }

    synchronized DiagnosticFrame captureLatestForAcceptance() {
        requireReady("capture diagnostic frame");
        try {
            pump();
            VulkanFrameSlot latest = frameRing.latestCompletedSlot();
            if (latest == null) return null;
            return frameDiagnostics.captureOutput(latest);
        } catch (RuntimeException failure) {
            throw fail("capture GPUScene diagnostic frame", failure);
        }
    }

    synchronized DiagnosticFrame captureLatestTraceForAcceptance() {
        requireReady("capture diagnostic trace frame");
        try {
            pump();
            VulkanFrameSlot latest = frameRing.latestCompletedSlot();
            if (latest == null) return null;
            return frameDiagnostics.captureTrace(latest);
        } catch (RuntimeException failure) {
            throw fail("capture GPUScene diagnostic trace frame", failure);
        }
    }

    synchronized void discardCompletedForAcceptance() {
        requireReady("discard completed acceptance frames");
        frameRing.discardAllCompleted();
    }

    synchronized VulkanDeviceRuntime deviceForAcceptance() {
        requireReady("inspect acceptance device");
        return scene.device();
    }

    private void pump() {
        VulkanGpuSceneFrameRing.Progress progress = frameRing.poll(
                latestCompletedDescriptorEpoch, latestCompletedFrameSequence
        );
        latestCompletedDescriptorEpoch = progress.descriptorEpoch();
        latestCompletedFrameSequence = progress.frameSequence();
        scene.poll(latestCompletedDescriptorEpoch);
    }

    private void requireReady(String operation) {
        if (state != State.READY) {
            throw new IllegalStateException("cannot " + operation + " while session is " + state, terminalFailure);
        }
    }

    private RuntimeException fail(String operation, RuntimeException failure) {
        state = State.FAILED;
        terminalFailure = failure;
        if (failure instanceof RendererDeviceException) return failure;
        return new IllegalStateException("failed to " + operation, failure);
    }

    @Override
    public synchronized void close() {
        if (resourcesClosed) return;
        state = State.CLOSED;
        frameRing.close();
        featureComposition.close();
        if (!pipelineClosed) {
            pipeline.close();
            pipelineClosed = true;
        }
        temporalCoordinator.close();
        if (!sceneClosed) {
            scene.close();
            sceneClosed = true;
        }
        resourcesClosed = true;
    }

    private static void closeSuppressing(Throwable failure, AutoCloseable resource) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    record DiagnosticFrame(long sequence, long sceneRevision, int width, int height, byte[] rgba8) {
        DiagnosticFrame {
            if (sequence < 0L || sceneRevision < 0L || width <= 0 || height <= 0) {
                throw new IllegalArgumentException("diagnostic frame metadata is invalid");
            }
            rgba8 = Objects.requireNonNull(rgba8, "rgba8").clone();
            if (rgba8.length != Math.multiplyExact(Math.multiplyExact(width, height), Integer.BYTES)) {
                throw new IllegalArgumentException("diagnostic frame payload does not match its extent");
            }
        }

        @Override
        public byte[] rgba8() {
            return rgba8.clone();
        }
    }

}
