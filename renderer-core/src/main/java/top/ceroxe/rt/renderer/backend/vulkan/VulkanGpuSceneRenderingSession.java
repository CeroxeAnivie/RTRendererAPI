package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease;
import top.ceroxe.rt.renderer.api.CpuFrame;
import top.ceroxe.rt.renderer.api.RayTracingRendererConfig;
import top.ceroxe.rt.renderer.api.RendererDiagnostics;
import top.ceroxe.rt.renderer.api.RendererDeviceException;
import top.ceroxe.rt.renderer.rt.acceleration.RtAccelerationStructure;
import top.ceroxe.rt.renderer.rt.device.RtCommandContext;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.rt.renderer.rt.device.RtGpuTimestampPool;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.device.VulkanMemoryBudgetPolicy;
import top.ceroxe.rt.renderer.rt.pipeline.GpuSceneDescriptorResources;
import top.ceroxe.rt.renderer.rt.pipeline.GpuSceneRayTracingPipeline;
import top.ceroxe.rt.renderer.rt.pipeline.GpuSceneTemporalFrameResources;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Production session joining persistent GPUScene convergence to bounded hardware RT frames. */
final class VulkanGpuSceneRenderingSession implements VulkanRenderingSession {
    private static final String FRAME_TIMING_LABEL = "gpuSceneFrame";

    private final RayTracingRendererConfig configuration;
    private final String gpuStableId;
    private final VulkanSceneRuntime scene;
    private final GpuSceneRayTracingPipeline pipeline;
    private final VulkanFrameSlot[] frameSlots;
    private final VulkanFrameAdmissionLimits frameAdmissionLimits;
    private final TemporalHistoryTracker temporalHistory;
    private final VulkanTemporalHistory temporalGpuHistory;

    private State state = State.READY;
    private boolean pipelineClosed;
    private boolean sceneClosed;
    private boolean resourcesClosed;
    private long acceptedSceneRevision = -1L;
    private int acceptedLightSlotUpperBound;
    private long lastSubmittedDescriptorEpoch;
    private long latestCompletedDescriptorEpoch;
    private long latestSubmittedFrameSequence = -1L;
    private long latestCompletedFrameSequence = -1L;
    private long latestAcquiredFrameSequence = -1L;
    private RuntimeException terminalFailure;

    static VulkanGpuSceneRenderingSession open(
            VulkanRtCapabilityProbe.Result capability,
            RayTracingRendererConfig configuration,
            RendererRtDiagnostics diagnostics
    ) {
        RayTracingRendererConfig checkedConfiguration = Objects.requireNonNull(configuration, "configuration");
        VulkanSceneRuntime scene = null;
        GpuSceneRayTracingPipeline pipeline = null;
        VulkanTemporalHistory temporalGpuHistory = null;
        VulkanFrameSlot[] slots = null;
        try {
            scene = VulkanSceneRuntime.open(
                    Objects.requireNonNull(capability, "capability"),
                    Objects.requireNonNull(diagnostics, "diagnostics"),
                    checkedConfiguration.validationEnabled(),
                    checkedConfiguration.gpuTimingsEnabled()
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
            temporalGpuHistory = new VulkanTemporalHistory(
                    device, checkedConfiguration.temporalRendering().enabled()
            );
            pipeline = GpuSceneRayTracingPipeline.open(
                    device, checkedConfiguration.maxFramesInFlight(), frameOutput.linearHdr()
            );
            slots = new VulkanFrameSlot[checkedConfiguration.maxFramesInFlight()];
            for (int index = 0; index < slots.length; index++) {
                slots[index] = new VulkanFrameSlot(
                        index,
                        device,
                        frameOutput,
                        interop.dedicatedAllocationRequired(),
                        interop.semaphoreImportReady(),
                        checkedConfiguration.cpuFrameReadbackEnabled(),
                        diagnostics.stalls(),
                        checkedConfiguration.temporalRendering().enabled()
                );
            }
            VulkanFrameAdmissionLimits frameAdmissionLimits = new VulkanFrameAdmissionLimits(
                    device.maxImageDimension2D(),
                    capability.preferredDevice().maxRayDispatchInvocationCount()
            );
            VulkanGpuSceneRenderingSession result = new VulkanGpuSceneRenderingSession(
                    checkedConfiguration,
                    capability.preferredDevice().stableId(),
                    scene,
                    pipeline,
                    temporalGpuHistory,
                    slots,
                    frameAdmissionLimits
            );
            scene = null;
            pipeline = null;
            temporalGpuHistory = null;
            slots = null;
            return result;
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            closeSlotsSuppressing(failure, slots);
            closeSuppressing(failure, pipeline);
            closeSuppressing(failure, temporalGpuHistory);
            closeSuppressing(failure, scene);
            throw failure;
        }
    }

    private VulkanGpuSceneRenderingSession(
            RayTracingRendererConfig configuration,
            String gpuStableId,
            VulkanSceneRuntime scene,
            GpuSceneRayTracingPipeline pipeline,
            VulkanTemporalHistory temporalGpuHistory,
            VulkanFrameSlot[] frameSlots,
            VulkanFrameAdmissionLimits frameAdmissionLimits
    ) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.gpuStableId = Objects.requireNonNull(gpuStableId, "gpuStableId");
        if (gpuStableId.isBlank()) throw new IllegalArgumentException("gpuStableId must not be blank");
        this.scene = Objects.requireNonNull(scene, "scene");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.temporalGpuHistory = Objects.requireNonNull(temporalGpuHistory, "temporalGpuHistory");
        this.frameSlots = Objects.requireNonNull(frameSlots, "frameSlots").clone();
        this.frameAdmissionLimits = Objects.requireNonNull(frameAdmissionLimits, "frameAdmissionLimits");
        temporalHistory = new TemporalHistoryTracker(configuration.temporalRendering());
        if (this.frameSlots.length != configuration.maxFramesInFlight()) {
            throw new IllegalArgumentException("frame slot count diverges from renderer configuration");
        }
        for (VulkanFrameSlot slot : this.frameSlots) Objects.requireNonNull(slot, "frame slot");
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
    public synchronized SceneAdmission apply(SceneSubmission submission) throws SubmissionRejectedException {
        requireReady("apply scene");
        SceneSubmission checked = Objects.requireNonNull(submission, "submission");
        try {
            pump();
            VulkanSceneRuntime.Admission admission = scene.apply(
                    checked.residentChanges(),
                    lastSubmittedDescriptorEpoch
            );
            if (admission.revision() != checked.residentChanges().revision()) {
                throw new IllegalStateException("scene runtime admitted a different revision");
            }
            acceptedSceneRevision = admission.revision();
            acceptedLightSlotUpperBound = checked.residentChanges()
                    .lights().statistics().slotUpperBound();
            temporalHistory.sceneApplied(checked.residentChanges());
            return new SceneAdmission(acceptedSceneRevision);
        } catch (VulkanSceneRuntime.BusyException busy) {
            if (busy.getCause() instanceof VulkanMemoryBudgetRejectedException) {
                long reclaimedBytes;
                try {
                    reclaimedBytes = reclaimIdleFrameOutputs();
                } catch (RuntimeException reclaimFailure) {
                    throw fail("reclaim idle frame outputs after GPU memory pressure", reclaimFailure);
                }
                if (reclaimedBytes > 0L) {
                    return apply(checked);
                }
            }
            throw new SubmissionRejectedException(busy.getMessage());
        } catch (RuntimeException failure) {
            throw fail("apply GPUScene generation", failure);
        }
    }

    @Override
    public synchronized FrameAdmission submit(FrameSubmission submission) throws SubmissionRejectedException {
        requireReady("submit frame");
        FrameSubmission checked = Objects.requireNonNull(submission, "submission");
        frameAdmissionLimits.validate(checked.request().width(), checked.request().height());
        try {
            pump();
            if (checked.acceptedSceneRevision() != acceptedSceneRevision) {
                throw new IllegalStateException(
                        "host/session accepted scene revisions diverged: host="
                                + checked.acceptedSceneRevision() + ", session=" + acceptedSceneRevision
                );
            }
            VulkanSceneRuntime.Snapshot sceneState = scene.snapshot();
            if (sceneState.activeRevision() != acceptedSceneRevision) {
                throw new SubmissionRejectedException(
                        "scene revision " + acceptedSceneRevision + " is still converging on the GPU"
                );
            }
            VulkanFrameSlot slot = writableSlot();
            if (slot == null) {
                throw new SubmissionRejectedException("all bounded frame slots are retained or in flight");
            }

            int width = checked.request().width();
            int height = checked.request().height();
            long frameGrowth = slot.requiredImageGrowthBytes(width, height);
            long temporalGrowth = temporalGpuHistory.requiredGrowthBytes(width, height);
            requireMemoryHeadroom(
                    "resize frame resources",
                    Math.addExact(frameGrowth, temporalGrowth)
            );
            if (!temporalGpuHistory.extentMatches(width, height) && hasProducerPending()) {
                throw new SubmissionRejectedException(
                        "temporal history resize is waiting for submitted GPU frames"
                );
            }
            temporalGpuHistory.ensureExtent(width, height);

            TemporalHistoryTracker.PreparedFrame temporalFrame =
                    temporalHistory.prepare(checked.request(), acceptedSceneRevision);
            byte[] frameUniforms = VulkanFrameUniformPacker.pack(
                    checked.request(),
                    acceptedLightSlotUpperBound,
                    acceptedSceneRevision,
                    temporalFrame,
                    configuration.temporalRendering()
            );
            slot.prepare(width, height, frameUniforms);
            VulkanTemporalHistory.PreparedFrame gpuTemporalFrame = temporalGpuHistory.prepareFrame(
                    configuration.temporalRendering().enabled() ? slot.motionImage() : null,
                    configuration.temporalRendering().enabled() && slot.motionLayoutInitialized()
            );
            GpuSceneTemporalFrameResources temporalResources = new GpuSceneTemporalFrameResources(
                    gpuTemporalFrame.colorInput(),
                    gpuTemporalFrame.colorOutput(),
                    gpuTemporalFrame.geometryInput(),
                    gpuTemporalFrame.geometryOutput(),
                    gpuTemporalFrame.motionOutput(),
                    gpuTemporalFrame.inputLayoutInitialized(),
                    gpuTemporalFrame.outputLayoutInitialized(),
                    gpuTemporalFrame.motionLayoutInitialized()
            );
            pipeline.updateDescriptorSet(
                    slot.index(), descriptorResources(slot, acceptedSceneRevision, gpuTemporalFrame)
            );

            long descriptorEpoch = Math.incrementExact(lastSubmittedDescriptorEpoch);
            int previousLayout = slot.imageLayout();
            boolean acquireExternalOwnership = slot.externallyOwned();
            boolean releaseExternalOwnership = !scene.device().managedPresentationActive();
            int producerQueueFamilyIndex = scene.device().queueFamilyIndex();
            VulkanDeviceRuntime.ManagedPresentationSignal readySignal =
                    releaseExternalOwnership
                            ? VulkanDeviceRuntime.ManagedPresentationSignal.disabled()
                            : scene.device().reserveManagedPresentationSignal();
            RtCommandContext.CommandRecorder frameRecorder =
                    (commandBuffer, stack) -> pipeline.recordFrame(
                                    commandBuffer,
                                    stack,
                                    slot.index(),
                                    slot.outputImage(),
                                    slot.cpuReadbackOrNull(),
                                    temporalResources,
                                    previousLayout,
                                    acquireExternalOwnership,
                                    releaseExternalOwnership,
                                    producerQueueFamilyIndex,
                                    width,
                                    height
                            );
            RtCommandContext.AsyncSubmission nativeSubmission = readySignal.enabled()
                    ? scene.device().frameCommands().submitTimedOneTimeAsync(
                            FRAME_TIMING_LABEL,
                            readySignal.semaphore(),
                            readySignal.value(),
                            frameRecorder
                    )
                    : scene.device().frameCommands().submitTimedOneTimeAsync(
                            FRAME_TIMING_LABEL, frameRecorder
                    );
            boolean published = false;
            try {
                slot.submitted(
                        nativeSubmission,
                        checked.request().sequence(),
                        acceptedSceneRevision,
                        descriptorEpoch,
                        releaseExternalOwnership,
                        readySignal
                );
                published = true;
            } finally {
                if (!published) nativeSubmission.close();
            }
            lastSubmittedDescriptorEpoch = descriptorEpoch;
            latestSubmittedFrameSequence = checked.request().sequence();
            temporalGpuHistory.commit(gpuTemporalFrame);
            temporalHistory.commit(temporalFrame);
            return new FrameAdmission(
                    checked.request().sequence(),
                    acceptedSceneRevision,
                    temporalFrame.invalidations()
            );
        } catch (SubmissionRejectedException rejection) {
            throw rejection;
        } catch (RuntimeException failure) {
            throw fail("submit GPUScene frame", failure);
        }
    }

    @Override
    public synchronized GpuFrameLease acquireLatestFrame() {
        requireReady("acquire latest frame");
        try {
            pump();
            VulkanFrameSlot latest = latestCompletedSlot();
            if (latest == null || latest.frameSequence() <= latestAcquiredFrameSequence) return null;
            /*
             * Completed images are retained until a consumer actually asks for one. Reclaiming
             * them from pump() let an uncapped producer render and discard thousands of frames
             * while starving a swapchain consumer on the same physical GPU. Once the caller asks
             * for the latest image, older completions are genuinely superseded and may be freed.
             */
            discardCompletedExcept(latest);
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
            VulkanFrameSlot earliest = earliestManagedPresentableSlot();
            if (earliest == null || earliest.frameSequence() <= latestAcquiredFrameSequence) return null;
            discardCompletedExcept(earliest);
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
            VulkanFrameSlot latest = latestCompletedSlot();
            if (latest == null || latest.frameSequence() <= afterFrameSequence) return null;
            byte[] rgba8 = latest.captureCpuRgba8();
            CpuFrame frame = CpuFrame.builder()
                    .frameSequence(latest.frameSequence())
                    .renderedSceneRevision(latest.renderedSceneRevision())
                    .extent(latest.outputImage().width(), latest.outputImage().height())
                    .pixelsRgba8(rgba8)
                    .build();
            // The immutable CPU copy owns its pixels, so no completed GPU output must remain
            // retained merely to keep the returned frame alive.
            discardAllCompleted();
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
            return new Telemetry(latestCompletedFrameSequence, frameTiming());
        } catch (RuntimeException failure) {
            throw fail("read GPUScene telemetry", failure);
        }
    }

    synchronized DiagnosticFrame captureLatestForAcceptance() {
        requireReady("capture diagnostic frame");
        try {
            pump();
            VulkanFrameSlot latest = latestCompletedSlot();
            if (latest == null) return null;
            byte[] rgba8 = VulkanFrameDiagnosticReadback.capture(scene.device(), latest.outputImage());
            return new DiagnosticFrame(
                    latest.frameSequence(),
                    latest.renderedSceneRevision(),
                    latest.outputImage().width(),
                    latest.outputImage().height(),
                    rgba8
            );
        } catch (RuntimeException failure) {
            throw fail("capture GPUScene diagnostic frame", failure);
        }
    }

    synchronized VulkanDeviceRuntime deviceForAcceptance() {
        requireReady("inspect acceptance device");
        return scene.device();
    }

    private GpuSceneDescriptorResources descriptorResources(
            VulkanFrameSlot slot,
            long sceneRevision,
            VulkanTemporalHistory.PreparedFrame temporal
    ) {
        RtAccelerationStructure tlas = scene.requireActiveTlas(sceneRevision);
        ArrayList<GpuSceneDescriptorResources.StorageBinding> sceneBuffers = new ArrayList<>(
                VulkanGpuSceneUploadPlanner.Target.values().length
        );
        for (VulkanGpuSceneUploadPlanner.Target target : VulkanGpuSceneUploadPlanner.Target.values()) {
            VulkanGpuSceneTransferQueue.BufferBinding binding = scene.requireBuffer(target, sceneRevision);
            sceneBuffers.add(new GpuSceneDescriptorResources.StorageBinding(
                    VulkanGpuSceneAbi.descriptorBinding(target),
                    GpuSceneDescriptorResources.BufferRange.whole(binding.buffer(), binding.capacityBytes())
            ));
        }
        RtGpuBuffer uniforms = slot.frameUniforms();
        return new GpuSceneDescriptorResources(
                tlas.handle(),
                slot.outputImage().imageView(),
                temporal.colorInput().imageView(),
                temporal.colorOutput().imageView(),
                temporal.geometryInput().imageView(),
                temporal.geometryOutput().imageView(),
                temporal.motionOutput().imageView(),
                GpuSceneDescriptorResources.BufferRange.whole(uniforms.buffer(), uniforms.sizeBytes()),
                sceneBuffers
        );
    }

    private boolean hasProducerPending() {
        for (VulkanFrameSlot slot : frameSlots) {
            if (slot.producerPending()) return true;
        }
        return false;
    }

    private void requireMemoryHeadroom(String operation, long requestedGrowthBytes)
            throws SubmissionRejectedException {
        VulkanMemoryBudgetPolicy.Admission admission = VulkanMemoryBudgetPolicy.evaluate(
                scene.device().memoryBudget(), requestedGrowthBytes
        );
        if (!admission.admitted()) {
            throw new SubmissionRejectedException(operation + " rejected by GPU memory budget: " + admission.reason());
        }
    }

    private long reclaimIdleFrameOutputs() {
        long reclaimedBytes = 0L;
        for (VulkanFrameSlot slot : frameSlots) {
            if (slot.writable()) {
                reclaimedBytes = Math.addExact(reclaimedBytes, slot.trimIdleOutputImage());
            }
        }
        return reclaimedBytes;
    }

    private void pump() {
        long completedEpoch = latestCompletedDescriptorEpoch;
        long completedSequence = latestCompletedFrameSequence;
        for (VulkanFrameSlot slot : frameSlots) {
            slot.pollProducer();
            completedEpoch = Math.max(completedEpoch, slot.observedProducerDescriptorEpoch());
            completedSequence = Math.max(completedSequence, slot.observedProducerFrameSequence());
        }
        latestCompletedDescriptorEpoch = completedEpoch;
        latestCompletedFrameSequence = completedSequence;
        scene.poll(latestCompletedDescriptorEpoch);
    }

    private void discardCompletedExcept(VulkanFrameSlot retained) {
        Objects.requireNonNull(retained, "retained");
        for (VulkanFrameSlot slot : frameSlots) {
            if (slot != retained && slot.completed()) slot.discardCompleted();
        }
    }

    private void discardAllCompleted() {
        for (VulkanFrameSlot slot : frameSlots) {
            if (slot.completed()) slot.discardCompleted();
        }
    }

    private VulkanFrameSlot writableSlot() {
        for (VulkanFrameSlot slot : frameSlots) {
            if (slot.writable()) return slot;
        }
        return null;
    }

    private VulkanFrameSlot latestCompletedSlot() {
        VulkanFrameSlot latest = null;
        for (VulkanFrameSlot slot : frameSlots) {
            if (slot.completed() && (latest == null || slot.frameSequence() > latest.frameSequence())) {
                latest = slot;
            }
        }
        return latest;
    }

    private VulkanFrameSlot earliestManagedPresentableSlot() {
        VulkanFrameSlot earliest = null;
        for (VulkanFrameSlot slot : frameSlots) {
            if (slot.managedPresentable()
                    && slot.frameSequence() > latestAcquiredFrameSequence
                    && (earliest == null || slot.frameSequence() < earliest.frameSequence())) {
                earliest = slot;
            }
        }
        return earliest;
    }

    private RendererDiagnostics.FrameGpuTiming frameTiming() {
        if (!configuration.gpuTimingsEnabled()) return RendererDiagnostics.FrameGpuTiming.unavailable();
        RtGpuTimestampPool.StageSnapshot timing = scene.device().frameCommands()
                .gpuStageTimestampSnapshot(FRAME_TIMING_LABEL);
        if (!timing.enabled()) return RendererDiagnostics.FrameGpuTiming.unavailable();
        return RendererDiagnostics.FrameGpuTiming.builder()
                .enabled(true)
                .completedSamples(timing.completedCaptures())
                .droppedSamples(timing.droppedCaptures())
                .failedSamples(timing.failedCaptures())
                .averageTraceNanos(timing.averageNanos())
                .averagePostTraceNanos(0L)
                .averageTotalNanos(timing.averageNanos())
                .maxTotalNanos(timing.maxNanos())
                .build();
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
        RuntimeException failure = null;
        for (int index = frameSlots.length - 1; index >= 0; index--) {
            try { frameSlots[index].close(); } catch (RuntimeException ex) { failure = collect(failure, ex); }
        }
        if (failure != null) {
            /* A frame submission may still reference every downstream pipeline resource. */
            throw failure;
        }
        if (!pipelineClosed) {
            pipeline.close();
            pipelineClosed = true;
        }
        temporalGpuHistory.close();
        if (!sceneClosed) {
            scene.close();
            sceneClosed = true;
        }
        resourcesClosed = true;
    }

    private static RuntimeException collect(RuntimeException current, RuntimeException next) {
        if (current == null) return next;
        current.addSuppressed(next);
        return current;
    }

    private static void closeSlotsSuppressing(Throwable failure, VulkanFrameSlot[] slots) {
        if (slots == null) return;
        for (int index = slots.length - 1; index >= 0; index--) {
            closeSuppressing(failure, slots[index]);
        }
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
