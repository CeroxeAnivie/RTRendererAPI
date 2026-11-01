package top.ceroxe.rt.renderer.backend.vulkan;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.OpticalWallTimeline;
import static top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.OpticalWallTimeline.Stage.*;


import top.ceroxe.rt.renderer.api.SubmissionDeferralReason;
import top.ceroxe.rt.renderer.rt.device.RtCommandContext;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.device.VulkanMemoryBudgetPolicy;
import top.ceroxe.rt.renderer.rt.pipeline.GpuSceneRayTracingPipeline;
import top.ceroxe.rt.renderer.rt.pipeline.GpuScenePrimaryQueue;
import top.ceroxe.rt.renderer.rt.pipeline.VulkanFrameExtents;
import top.ceroxe.rt.renderer.feature.VulkanFeatureSession;

import java.util.Objects;

/** Records and publishes one GPUScene frame as an atomic submission transaction. */
final class VulkanGpuSceneFrameSubmitter {
    // Test-only scheduling evidence, deliberately not presented as GPU ray/work counters.
    static final java.util.concurrent.atomic.LongAdder diagnosticQueuedSubmissions = new java.util.concurrent.atomic.LongAdder();
    private final VulkanSceneRuntime scene;
    private final VulkanGpuScenePipelines pipelines;
    private final VulkanGpuSceneFrameRing frameRing;
    private final VulkanGpuSceneTemporalCoordinator temporal;
    private final VulkanGpuSceneFeatureComposition features;
    private final VulkanGpuSceneDescriptorAssembler descriptors;
    private final String timingLabel;
    private static final boolean STAGE_PROFILE = Boolean.getBoolean("top.ceroxe.rt.rt.gpuTimestamps.frameStages");
    private static final String[] FRAME_CHECKPOINTS = {
            "Begin", "AccelerationComplete", "TraceBegin", "TraceComplete",
            "ReconstructionComplete", "PublicationComplete", "End"
    };
    private static final String[] WHOLE_FRAME_CHECKPOINTS = {"start", "end"};
    private long lastSubmittedDescriptorEpoch;

    VulkanGpuSceneFrameSubmitter(
            VulkanSceneRuntime scene,
            VulkanGpuScenePipelines pipelines,
            VulkanGpuSceneFrameRing frameRing,
            VulkanGpuSceneTemporalCoordinator temporal,
            VulkanGpuSceneFeatureComposition features,
            VulkanGpuSceneDescriptorAssembler descriptors,
            String timingLabel
    ) {
        this.scene = Objects.requireNonNull(scene, "scene");
        this.pipelines = Objects.requireNonNull(pipelines, "pipelines");
        this.frameRing = Objects.requireNonNull(frameRing, "frameRing");
        this.temporal = Objects.requireNonNull(temporal, "temporal");
        this.features = Objects.requireNonNull(features, "features");
        this.descriptors = Objects.requireNonNull(descriptors, "descriptors");
        this.timingLabel = requireText(timingLabel, "timingLabel");
    }

    long lastSubmittedDescriptorEpoch() {
        return lastSubmittedDescriptorEpoch;
    }

    VulkanRenderingSession.FrameAdmission submit(
            VulkanRenderingSession.FrameSubmission submission,
            VulkanFrameExtents extents,
            long acceptedSceneRevision,
            int acceptedLightSlotUpperBound
    ) throws VulkanRenderingSession.SubmissionRejectedException {
        VulkanRenderingSession.FrameSubmission checked = Objects.requireNonNull(submission, "submission");
        VulkanFrameExtents checkedExtents = Objects.requireNonNull(extents, "extents");
        if (checked.acceptedSceneRevision() != acceptedSceneRevision) {
            throw new IllegalStateException(
                    "host/session accepted scene revisions diverged: host="
                            + checked.acceptedSceneRevision() + ", session=" + acceptedSceneRevision
            );
        }
        long frameSequence = checked.request().sequence();
        VulkanFeatureSession featureSession = scene.device().featureSession();
        featureSession.beginFramePreparation(frameSequence);
        try {
            return prepareAndSubmit(
                    checked, checkedExtents, acceptedSceneRevision, acceptedLightSlotUpperBound, frameSequence
            );
        } catch (VulkanRenderingSession.SubmissionRejectedException | RuntimeException | Error failure) {
            try {
                // This is intentionally idempotent: beginFrameSubmission may already have
                // consumed the preparation interval before a later operation failed.
                featureSession.cancelFramePreparation(frameSequence);
            } catch (RuntimeException | Error cancellationFailure) {
                failure.addSuppressed(cancellationFailure);
            }
            throw failure;
        }
    }

    private VulkanRenderingSession.FrameAdmission prepareAndSubmit(
            VulkanRenderingSession.FrameSubmission checked,
            VulkanFrameExtents checkedExtents,
            long acceptedSceneRevision,
            int acceptedLightSlotUpperBound,
            long frameSequence
    ) throws VulkanRenderingSession.SubmissionRejectedException {
        int wallScope = OpticalWallTimeline.enter(FRAME_PREPARATION);
        int wallStep = OpticalWallTimeline.enter(ROUTE_AND_PIPELINES);
        try {
        VulkanSceneRuntime.Snapshot sceneState = scene.snapshot();
        if (sceneState.activeRevision() != acceptedSceneRevision) {
            throw new VulkanRenderingSession.SubmissionRejectedException(
                    SubmissionDeferralReason.SCENE_UPDATE_BACKLOG,
                    "scene revision " + acceptedSceneRevision + " is still converging on the GPU"
            );
        }
        VulkanFrameSlot slot = frameRing.writableSlot();
        if (slot == null) {
            throw new VulkanRenderingSession.SubmissionRejectedException(
                    SubmissionDeferralReason.FRAME_RING_FULL,
                    "all bounded frame slots are retained or in flight"
            );
        }
        var photonPlan = temporal.caustics(checked.request());
        var serialProgram = temporal.selectProgram(checked.request());
        pipelines.validateShaderData(checked.request().shaderData());
        var selectedProgram = VulkanGpuSceneTemporalCoordinator.primaryQueueProgram(serialProgram,
                pipelines.supportsPrimaryQueue() && Boolean.getBoolean("top.ceroxe.rt.medium.primaryQueue"));
        int width = checkedExtents.renderWidth();
        int height = checkedExtents.renderHeight();
        GpuSceneRayTracingPipeline photonPipeline = photonPlan.photons() > 0 ? pipelines.photons() : null;
        GpuScenePrimaryQueue.Plan selectedQueue = selectedProgram.primaryQueue()
                ? GpuScenePrimaryQueue.plan(width, height, checked.request().antiAliasing().samplesPerPixel()) : null;
        if (selectedQueue != null) {
            try { requireMemoryHeadroom("prepare primary hit queue", slot.requiredPrimaryQueueGrowth(selectedQueue.bytes())); }
            catch (VulkanRenderingSession.SubmissionRejectedException unavailable) {
                selectedProgram = serialProgram;
                selectedQueue = null; // Memory-pressure fallback never truncates a tile or drops a path.
            }
        }
        GpuScenePrimaryQueue.Plan primaryQueue = selectedQueue;
        GpuSceneRayTracingPipeline pipeline = pipelines.forFrame(selectedProgram);
        if(photonPlan.photons()>0&&photonPlan.bytes()>pipeline.maxStorageBufferRangeBytes())
            throw new top.ceroxe.rt.renderer.api.FrameValidationException(
                    top.ceroxe.rt.renderer.api.FrameValidationException.Reason.INVALID_CAUSTICS,
                    "complete surface and volume photon map exceeds device maxStorageBufferRange="
                            +pipeline.maxStorageBufferRangeBytes()+" bytes; reduce photon or event budget");
        GpuSceneRayTracingPipeline primaryProducer = primaryQueue != null
                ? pipelines.forFrame(GpuSceneRayTracingPipeline.Program.PRIMARY_TRACE) : null;
        // Presentation-time feature work may outlive proxy present. Establish resource ownership
        // before reconfiguration, extent replacement, or any writes to this free slot.
        wallStep = OpticalWallTimeline.next(wallStep, FEATURE_INPUT_REUSE);
        VulkanFeatureSession.InputCompletion inputCompletion =
                scene.device().featureSession().awaitFrameInputReuse(frameSequence);
        VulkanGpuSceneFeatureComposition.Selection frameFeatures = features.frameSelection(
                checked.request().depthProjection().known()
        );
        // Only a free slot may observe a runtime capability transition. In-flight descriptors
        // retain their original resource family until their producer has completed.
        features.reconfigure(slot, frameFeatures);

        wallStep = OpticalWallTimeline.next(wallStep, RESOURCE_BUDGET_AND_ENSURE);
        VulkanFramePrimitiveResources primitiveResources = frameRing.primitives(slot);
        long requestedGrowth = Math.addExact(
                Math.addExact(
                        Math.addExact(slot.requiredImageGrowthBytes(checkedExtents),
                                pipeline.requiredShaderDataGrowth(slot.index(), checked.request().shaderData())),
                        temporal.requiredGrowthBytes(width, height)
                ),
                Math.addExact(primitiveResources.requiredBufferGrowthBytes(checked.request().primitiveBatch()),
                        slot.requiredCausticGrowth(photonPlan.bytes()))
        );
        requireMemoryHeadroom("resize frame resources", Math.addExact(requestedGrowth,
                primaryQueue == null ? 0 : slot.requiredPrimaryQueueGrowth(primaryQueue.bytes())));
        if (!temporal.extentMatches(width, height) && frameRing.hasProducerPending()) {
            throw new VulkanRenderingSession.SubmissionRejectedException(
                    SubmissionDeferralReason.FRAME_RESOURCES_BUSY,
                    "temporal history resize is waiting for submitted GPU frames"
            );
        }
        temporal.ensureExtent(width, height);
        // Temporal preparation borrows the slot-owned motion image. Allocate the slot contract
        // before deriving the temporal transaction, then upload the resulting uniforms below.
        slot.ensureResources(checkedExtents);
        slot.ensureCausticResources(photonPlan.bytes());
        if (primaryQueue != null) slot.ensurePrimaryQueue(primaryQueue.bytes());

        wallStep = OpticalWallTimeline.next(wallStep, FRAME_PRIMITIVES);
        VulkanFramePrimitiveResources.Prepared framePrimitives = primitiveResources.prepare(
                checked.request().primitiveBatch(), acceptedSceneRevision, scene
        );

        wallStep = OpticalWallTimeline.next(wallStep, TEMPORAL_UNIFORMS_DESCRIPTORS);
        RtCommandContext.AsyncSubmission nativeSubmission = null;
        boolean slotPublished = false;
        try (VulkanFeatureSubmissionTransaction featureSubmission =
                     new VulkanFeatureSubmissionTransaction(scene.device().featureSession(), frameSequence)) {
            VulkanGpuSceneTemporalCoordinator.Prepared temporalFrame = temporal.prepare(
                    checked.request(),
                    checkedExtents,
                    acceptedLightSlotUpperBound,
                    acceptedSceneRevision,
                    slot,
                    frameFeatures.denoising(),
                    frameFeatures.reconstruction() || frameFeatures.frameGeneration(),
                    frameFeatures.temporalReconstruction()
            );
            java.nio.ByteBuffer.wrap(temporalFrame.uniforms()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .putInt(VulkanGpuSceneAbi.FRAME_CAUSTIC_PHOTONS_WORD * 4, photonPlan.photons());
            slot.writeUniforms(temporalFrame.uniforms());
            pipeline.updateShaderData(slot.index(), checked.request().shaderData());
            var frameDescriptors = descriptors.assemble(
                    slot, acceptedSceneRevision, temporalFrame.gpu(), framePrimitives, frameFeatures);
            pipeline.updateDescriptorSet(slot.index(), frameDescriptors);
            if (photonPipeline != null) photonPipeline.updateDescriptorSet(slot.index(), frameDescriptors);
            if (primaryProducer != null) primaryProducer.updateDescriptorSet(slot.index(), frameDescriptors);
            wallStep = OpticalWallTimeline.next(wallStep, FINAL_FRAME_RECORD_AND_SUBMIT);
            long descriptorEpoch = Math.incrementExact(lastSubmittedDescriptorEpoch);
            int previousLayout = slot.imageLayout();
            boolean acquireExternalOwnership = slot.externallyOwned();
            boolean releaseExternalOwnership = !scene.device().managedPresentationActive();
            int producerQueueFamilyIndex = scene.device().queueFamilyIndex();
            VulkanDeviceRuntime.ManagedPresentationSignal readySignal = releaseExternalOwnership
                    ? VulkanDeviceRuntime.ManagedPresentationSignal.disabled()
                    : scene.device().reserveManagedPresentationSignal();
            RtCommandContext.ProfiledCommandRecorder recorder = (commandBuffer, stack, capture) -> {
                if (framePrimitives.buildPlan() != null) framePrimitives.buildPlan().record(commandBuffer, stack);
                photonPlan.record(commandBuffer, stack, slot.causticPhotons(), photonPipeline, slot.index());
                if (STAGE_PROFILE && capture != null) capture.write(commandBuffer, org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
                pipeline.recordFrame(
                        commandBuffer,
                        stack,
                        slot.index(),
                        features.traceOutput(slot, frameFeatures),
                        features.reconstructionOutput(slot, frameFeatures),
                        slot.outputImage(),
                        slot.cpuReadbackOrNull(),
                        temporalFrame.resources(),
                        previousLayout,
                        features.traceLayoutInitialized(slot, frameFeatures),
                        acquireExternalOwnership,
                        releaseExternalOwnership,
                        producerQueueFamilyIndex,
                        checkedExtents,
                        scene.device().featureSession(),
                        features.denoisingResources(slot, frameFeatures),
                        features.denoisingLayoutsInitialized(slot, frameFeatures),
                        features.reconstructionResources(slot, frameFeatures),
                        features.reconstructionLayoutsInitialized(slot, frameFeatures),
                        slot.reconstructionOutputLayoutInitialized(),
                        features.frameGenerationResources(slot, frameFeatures),
                        features.frameGenerationLayoutsInitialized(slot, frameFeatures),
                        temporalFrame.featureInput(),
                        checked.request().sequence(),
                        acceptedSceneRevision,
                        !temporalFrame.invalidations().isEmpty(),
                        () -> features.recordNrdComposition(
                                commandBuffer, stack, slot, width, height, frameFeatures
                        ),
                        () -> features.recordPublication(commandBuffer, stack, slot, frameFeatures),
                        primaryQueue == null ? null : () -> GpuScenePrimaryQueue.record(commandBuffer, stack,
                                primaryProducer, pipeline, slot.index(), slot.primaryQueue(), primaryQueue),
                        STAGE_PROFILE ? capture : null
                );
            };
            nativeSubmission = submitNative(frameSequence, inputCompletion, readySignal, 0L, slot, recorder);
            featureSubmission.commit();
            wallStep = OpticalWallTimeline.next(wallStep, SLOT_PUBLICATION);
            slot.submitted(
                    nativeSubmission,
                    frameSequence,
                    acceptedSceneRevision,
                    descriptorEpoch,
                    releaseExternalOwnership,
                    readySignal,
                    pipeline.shaderExecutionReorderingEnabled()
            );
            slotPublished = true;
            if (primaryQueue != null && Boolean.getBoolean("top.ceroxe.rt.medium.queueDiagnostics")) {
                diagnosticQueuedSubmissions.increment();
                System.out.println("PrimaryQueue submitted frame="+frameSequence+" program="+selectedProgram
                        +" scheduledPrimaryPaths="+primaryQueue.primaryPaths()+" dispatchPairs="+primaryQueue.dispatchPairs()
                        +" scratchBytes="+primaryQueue.bytes());
            }
            primitiveResources.commit(framePrimitives);
            features.markLayoutsInitialized(slot, frameFeatures);
            lastSubmittedDescriptorEpoch = descriptorEpoch;
            temporal.commit(temporalFrame);
            return new VulkanRenderingSession.FrameAdmission(
                    frameSequence, acceptedSceneRevision, temporalFrame.invalidations()
            );
        } catch (RuntimeException | Error failure) {
            if (!slotPublished) {
                if (nativeSubmission != null) {
                    try {
                        // Submission close observes its fence before the build destination is freed.
                        nativeSubmission.close();
                        slot.drainAbortedBinarySubmission();
                    } catch (RuntimeException closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                }
                try {
                    primitiveResources.abort(framePrimitives);
                } catch (RuntimeException abortFailure) {
                    failure.addSuppressed(abortFailure);
                }
            }
            throw failure;
        }

        } finally { OpticalWallTimeline.leave(wallStep); OpticalWallTimeline.leave(wallScope); }
    }

    private RtCommandContext.AsyncSubmission submitNative(
            long frameSequence,
            VulkanFeatureSession.InputCompletion inputCompletion,
            VulkanDeviceRuntime.ManagedPresentationSignal readySignal,
            long nativeWaitSemaphore,
            VulkanFrameSlot deliverySlot,
            RtCommandContext.ProfiledCommandRecorder recorder
    ) {
        scene.device().featureSession().beginFrameSubmission(frameSequence);
        RtCommandContext.AsyncSubmission nativeSubmission;
        try {
            nativeSubmission = scene.device().frameCommands().submitProfiledExternalSynchronizedOneTimeAsync(
                    timingLabel, nativeWaitSemaphore, deliverySlot.readyBinarySemaphore(),
                    inputCompletion.semaphore(), inputCompletion.value(),
                    readySignal.semaphore(), readySignal.value(),
                    STAGE_PROFILE ? FRAME_CHECKPOINTS : WHOLE_FRAME_CHECKPOINTS,
                    !STAGE_PROFILE || frameSequence >= 240L && (frameSequence & 15L) == 0L, recorder);
            scene.device().featureSession().commitFrameInputReuse(frameSequence);
        } catch (RuntimeException | Error submissionFailure) {
            try {
                scene.device().featureSession().endFrameSubmission(frameSequence);
            } catch (RuntimeException | Error markerFailure) {
                submissionFailure.addSuppressed(markerFailure);
            }
            throw submissionFailure;
        }
        try {
            scene.device().featureSession().endFrameSubmission(frameSequence);
            return nativeSubmission;
        } catch (RuntimeException | Error markerFailure) {
            nativeSubmission.close();
            deliverySlot.drainAbortedBinarySubmission();
            throw markerFailure;
        }
    }

    private void requireMemoryHeadroom(String operation, long requestedGrowthBytes)
            throws VulkanRenderingSession.SubmissionRejectedException {
        VulkanMemoryBudgetPolicy.Admission admission = VulkanMemoryBudgetPolicy.evaluate(
                scene.device().memoryBudget(), requestedGrowthBytes
        );
        if (!admission.admitted()) {
            throw new VulkanRenderingSession.SubmissionRejectedException(
                    SubmissionDeferralReason.RESOURCE_PRESSURE,
                    operation + " rejected by GPU memory budget: " + admission.reason()
            );
        }
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) throw new IllegalArgumentException(label + " must not be blank");
        return checked;
    }
}
