package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.rt.device.RtCommandContext;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.device.VulkanMemoryBudgetPolicy;
import top.ceroxe.rt.renderer.rt.pipeline.GpuSceneRayTracingPipeline;
import top.ceroxe.rt.renderer.rt.pipeline.VulkanFrameExtents;
import top.ceroxe.rt.renderer.feature.VulkanFeatureSession;

import java.util.Objects;

/** Records and publishes one GPUScene frame as an atomic submission transaction. */
final class VulkanGpuSceneFrameSubmitter {
    private final VulkanSceneRuntime scene;
    private final GpuSceneRayTracingPipeline pipeline;
    private final VulkanGpuSceneFrameRing frameRing;
    private final VulkanGpuSceneTemporalCoordinator temporal;
    private final VulkanGpuSceneFeatureComposition features;
    private final VulkanGpuSceneDescriptorAssembler descriptors;
    private final String timingLabel;
    private long lastSubmittedDescriptorEpoch;

    VulkanGpuSceneFrameSubmitter(
            VulkanSceneRuntime scene,
            GpuSceneRayTracingPipeline pipeline,
            VulkanGpuSceneFrameRing frameRing,
            VulkanGpuSceneTemporalCoordinator temporal,
            VulkanGpuSceneFeatureComposition features,
            VulkanGpuSceneDescriptorAssembler descriptors,
            String timingLabel
    ) {
        this.scene = Objects.requireNonNull(scene, "scene");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
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
        VulkanSceneRuntime.Snapshot sceneState = scene.snapshot();
        if (sceneState.activeRevision() != acceptedSceneRevision) {
            throw new VulkanRenderingSession.SubmissionRejectedException(
                    "scene revision " + acceptedSceneRevision + " is still converging on the GPU"
            );
        }
        VulkanFrameSlot slot = frameRing.writableSlot();
        if (slot == null) {
            throw new VulkanRenderingSession.SubmissionRejectedException(
                    "all bounded frame slots are retained or in flight"
            );
        }
        // Presentation-time feature work may outlive proxy present. Establish resource ownership
        // before reconfiguration, extent replacement, or any writes to this free slot.
        VulkanFeatureSession.InputCompletion inputCompletion =
                scene.device().featureSession().awaitFrameInputReuse(frameSequence);
        VulkanGpuSceneFeatureComposition.Selection frameFeatures = features.frameSelection(
                checked.request().depthProjection().known()
        );
        // Only a free slot may observe a runtime capability transition. In-flight descriptors
        // retain their original resource family until their producer has completed.
        features.reconfigure(slot, frameFeatures);

        int width = checkedExtents.renderWidth();
        int height = checkedExtents.renderHeight();
        VulkanFramePrimitiveResources primitiveResources = frameRing.primitives(slot);
        long requestedGrowth = Math.addExact(
                Math.addExact(
                        slot.requiredImageGrowthBytes(checkedExtents),
                        temporal.requiredGrowthBytes(width, height)
                ),
                primitiveResources.requiredBufferGrowthBytes(checked.request().primitiveBatch())
        );
        requireMemoryHeadroom("resize frame resources", requestedGrowth);
        if (!temporal.extentMatches(width, height) && frameRing.hasProducerPending()) {
            throw new VulkanRenderingSession.SubmissionRejectedException(
                    "temporal history resize is waiting for submitted GPU frames"
            );
        }
        temporal.ensureExtent(width, height);
        // Temporal preparation borrows the slot-owned motion image. Allocate the slot contract
        // before deriving the temporal transaction, then upload the resulting uniforms below.
        slot.ensureResources(checkedExtents);

        VulkanFramePrimitiveResources.Prepared framePrimitives = primitiveResources.prepare(
                checked.request().primitiveBatch(), acceptedSceneRevision, scene
        );

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
            slot.writeUniforms(temporalFrame.uniforms());
            pipeline.updateDescriptorSet(
                    slot.index(), descriptors.assemble(
                            slot, acceptedSceneRevision, temporalFrame.gpu(), framePrimitives, frameFeatures
                    )
            );

            long descriptorEpoch = Math.incrementExact(lastSubmittedDescriptorEpoch);
            int previousLayout = slot.imageLayout();
            boolean acquireExternalOwnership = slot.externallyOwned();
            boolean releaseExternalOwnership = !scene.device().managedPresentationActive();
            int producerQueueFamilyIndex = scene.device().queueFamilyIndex();
            VulkanDeviceRuntime.ManagedPresentationSignal readySignal = releaseExternalOwnership
                    ? VulkanDeviceRuntime.ManagedPresentationSignal.disabled()
                    : scene.device().reserveManagedPresentationSignal();
            RtCommandContext.CommandRecorder recorder = (commandBuffer, stack) -> {
                if (framePrimitives.buildPlan() != null) {
                    framePrimitives.buildPlan().record(commandBuffer, stack);
                }
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
                        () -> features.recordPublication(commandBuffer, stack, slot, frameFeatures)
                );
            };
            nativeSubmission = submitNative(
                    frameSequence, inputCompletion, readySignal, recorder
            );
            featureSubmission.commit();
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
    }

    private RtCommandContext.AsyncSubmission submitNative(
            long frameSequence,
            VulkanFeatureSession.InputCompletion inputCompletion,
            VulkanDeviceRuntime.ManagedPresentationSignal readySignal,
            RtCommandContext.CommandRecorder recorder
    ) {
        scene.device().featureSession().beginFrameSubmission(frameSequence);
        RtCommandContext.AsyncSubmission nativeSubmission;
        try {
            nativeSubmission = inputCompletion.enabled()
                    ? scene.device().frameCommands().submitTimedTimelineSynchronizedOneTimeAsync(
                            timingLabel,
                            inputCompletion.semaphore(), inputCompletion.value(),
                            readySignal.semaphore(), readySignal.value(),
                            recorder
                    )
                    : readySignal.enabled()
                    ? scene.device().frameCommands().submitTimedOneTimeAsync(
                    timingLabel, readySignal.semaphore(), readySignal.value(), recorder
            )
                    : scene.device().frameCommands().submitTimedOneTimeAsync(timingLabel, recorder);
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
