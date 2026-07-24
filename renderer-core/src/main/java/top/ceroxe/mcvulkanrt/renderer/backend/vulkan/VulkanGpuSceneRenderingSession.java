package top.ceroxe.mcvulkanrt.renderer.backend.vulkan;

import top.ceroxe.mcvulkanrt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.mcvulkanrt.renderer.RendererRtDiagnostics;
import top.ceroxe.mcvulkanrt.renderer.api.GpuFrameLease;
import top.ceroxe.mcvulkanrt.renderer.api.RayTracingRendererConfig;
import top.ceroxe.mcvulkanrt.renderer.api.RendererDiagnostics;
import top.ceroxe.mcvulkanrt.renderer.rt.acceleration.RtAccelerationStructure;
import top.ceroxe.mcvulkanrt.renderer.rt.device.RtCommandContext;
import top.ceroxe.mcvulkanrt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.mcvulkanrt.renderer.rt.device.RtGpuTimestampPool;
import top.ceroxe.mcvulkanrt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.mcvulkanrt.renderer.rt.pipeline.GpuSceneDescriptorResources;
import top.ceroxe.mcvulkanrt.renderer.rt.pipeline.GpuSceneRayTracingPipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Production session joining persistent GPUScene convergence to bounded hardware RT frames. */
final class VulkanGpuSceneRenderingSession implements VulkanRenderingSession {
    private static final String FRAME_TIMING_LABEL = "gpuSceneFrame";

    private final RayTracingRendererConfig configuration;
    private final VulkanSceneRuntime scene;
    private final GpuSceneRayTracingPipeline pipeline;
    private final VulkanFrameSlot[] frameSlots;

    private State state = State.READY;
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
        VulkanFrameSlot[] slots = null;
        try {
            scene = VulkanSceneRuntime.open(
                    Objects.requireNonNull(capability, "capability"),
                    Objects.requireNonNull(diagnostics, "diagnostics"),
                    checkedConfiguration.validationEnabled(),
                    checkedConfiguration.gpuTimingsEnabled()
            );
            VulkanDeviceRuntime device = scene.device();
            VulkanDeviceRuntime.ExternalFrameInterop interop = device.externalFrameInterop();
            if (!interop.memoryExportReady()) {
                throw new IllegalStateException(
                        "selected Vulkan device cannot export renderer frame memory: " + interop.reason()
                );
            }
            pipeline = GpuSceneRayTracingPipeline.open(device, checkedConfiguration.maxFramesInFlight());
            slots = new VulkanFrameSlot[checkedConfiguration.maxFramesInFlight()];
            for (int index = 0; index < slots.length; index++) {
                slots[index] = new VulkanFrameSlot(
                        index,
                        device,
                        interop.dedicatedAllocationRequired(),
                        diagnostics.stalls()
                );
            }
            VulkanGpuSceneRenderingSession result = new VulkanGpuSceneRenderingSession(
                    checkedConfiguration, scene, pipeline, slots
            );
            scene = null;
            pipeline = null;
            slots = null;
            return result;
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            closeSlotsSuppressing(failure, slots);
            closeSuppressing(failure, pipeline);
            closeSuppressing(failure, scene);
            throw failure;
        }
    }

    private VulkanGpuSceneRenderingSession(
            RayTracingRendererConfig configuration,
            VulkanSceneRuntime scene,
            GpuSceneRayTracingPipeline pipeline,
            VulkanFrameSlot[] frameSlots
    ) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.scene = Objects.requireNonNull(scene, "scene");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.frameSlots = Objects.requireNonNull(frameSlots, "frameSlots").clone();
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
            return new SceneAdmission(acceptedSceneRevision);
        } catch (VulkanSceneRuntime.BusyException busy) {
            throw new SubmissionRejectedException(busy.getMessage());
        } catch (RuntimeException failure) {
            throw fail("apply GPUScene generation", failure);
        }
    }

    @Override
    public synchronized FrameAdmission submit(FrameSubmission submission) throws SubmissionRejectedException {
        requireReady("submit frame");
        FrameSubmission checked = Objects.requireNonNull(submission, "submission");
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

            byte[] frameUniforms = VulkanFrameUniformPacker.pack(
                    checked.request(), acceptedLightSlotUpperBound, acceptedSceneRevision
            );
            slot.prepare(checked.request().width(), checked.request().height(), frameUniforms);
            pipeline.updateDescriptorSet(slot.index(), descriptorResources(slot, acceptedSceneRevision));

            long descriptorEpoch = Math.incrementExact(lastSubmittedDescriptorEpoch);
            int previousLayout = slot.imageLayout();
            RtCommandContext.AsyncSubmission nativeSubmission = scene.device().frameCommands()
                    .submitTimedOneTimeAsync(
                            FRAME_TIMING_LABEL,
                            (commandBuffer, stack) -> pipeline.recordFrame(
                                    commandBuffer,
                                    stack,
                                    slot.index(),
                                    slot.outputImage(),
                                    previousLayout,
                                    checked.request().width(),
                                    checked.request().height()
                            )
                    );
            boolean published = false;
            try {
                slot.submitted(
                        nativeSubmission,
                        checked.request().sequence(),
                        acceptedSceneRevision,
                        descriptorEpoch
                );
                published = true;
            } finally {
                if (!published) nativeSubmission.close();
            }
            lastSubmittedDescriptorEpoch = descriptorEpoch;
            latestSubmittedFrameSequence = checked.request().sequence();
            return new FrameAdmission(checked.request().sequence(), acceptedSceneRevision);
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
            GpuFrameLease lease = latest.acquire();
            latestAcquiredFrameSequence = lease.descriptor().frameSequence();
            return lease;
        } catch (RuntimeException failure) {
            throw fail("export completed GPUScene frame", failure);
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
                    acceptedSceneRevision,
                    latest.outputImage().width(),
                    latest.outputImage().height(),
                    rgba8
            );
        } catch (RuntimeException failure) {
            throw fail("capture GPUScene diagnostic frame", failure);
        }
    }

    private GpuSceneDescriptorResources descriptorResources(VulkanFrameSlot slot, long sceneRevision) {
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
                GpuSceneDescriptorResources.BufferRange.whole(uniforms.buffer(), uniforms.sizeBytes()),
                sceneBuffers
        );
    }

    private void pump() {
        long completedEpoch = latestCompletedDescriptorEpoch;
        long completedSequence = latestCompletedFrameSequence;
        for (VulkanFrameSlot slot : frameSlots) {
            if (slot.pollProducer()) {
                completedEpoch = Math.max(completedEpoch, slot.descriptorEpoch());
                completedSequence = Math.max(completedSequence, slot.frameSequence());
            }
        }
        latestCompletedDescriptorEpoch = completedEpoch;
        latestCompletedFrameSequence = completedSequence;
        scene.poll(latestCompletedDescriptorEpoch);
        discardSupersededCompletions();
    }

    private void discardSupersededCompletions() {
        for (VulkanFrameSlot slot : frameSlots) {
            if (slot.completed() && slot.frameSequence() <= latestAcquiredFrameSequence) {
                slot.discardCompleted();
            }
        }
        VulkanFrameSlot latest = latestCompletedSlot();
        if (latest == null) return;
        for (VulkanFrameSlot slot : frameSlots) {
            if (slot != latest && slot.completed()) slot.discardCompleted();
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

    private RendererDiagnostics.FrameGpuTiming frameTiming() {
        if (!configuration.gpuTimingsEnabled()) return RendererDiagnostics.FrameGpuTiming.unavailable();
        RtGpuTimestampPool.StageSnapshot timing = scene.device().frameCommands()
                .gpuStageTimestampSnapshot(FRAME_TIMING_LABEL);
        if (!timing.enabled()) return RendererDiagnostics.FrameGpuTiming.unavailable();
        return new RendererDiagnostics.FrameGpuTiming(
                true,
                timing.completedCaptures(),
                timing.droppedCaptures(),
                timing.failedCaptures(),
                timing.averageNanos(),
                0L,
                timing.averageNanos(),
                timing.maxNanos()
        );
    }

    private void requireReady(String operation) {
        if (state != State.READY) {
            throw new IllegalStateException("cannot " + operation + " while session is " + state, terminalFailure);
        }
    }

    private IllegalStateException fail(String operation, RuntimeException failure) {
        state = State.FAILED;
        terminalFailure = failure;
        return new IllegalStateException("failed to " + operation, failure);
    }

    @Override
    public synchronized void close() {
        if (state == State.CLOSED) return;
        state = State.CLOSED;
        RuntimeException failure = null;
        for (int index = frameSlots.length - 1; index >= 0; index--) {
            try { frameSlots[index].close(); } catch (RuntimeException ex) { failure = collect(failure, ex); }
        }
        try { pipeline.close(); } catch (RuntimeException ex) { failure = collect(failure, ex); }
        try { scene.close(); } catch (RuntimeException ex) { failure = collect(failure, ex); }
        if (failure != null) throw failure;
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
