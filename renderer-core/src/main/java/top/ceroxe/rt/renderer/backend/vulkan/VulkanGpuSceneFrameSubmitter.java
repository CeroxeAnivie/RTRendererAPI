package top.ceroxe.rt.renderer.backend.vulkan;

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
    static final java.util.concurrent.atomic.LongAdder diagnosticNativeCameraSubmissions = new java.util.concurrent.atomic.LongAdder();
    static final java.util.concurrent.atomic.LongAdder diagnosticNativeBoundarySubmissions = new java.util.concurrent.atomic.LongAdder();
    static final java.util.concurrent.atomic.LongAdder diagnosticNativeScatteringSubmissions = new java.util.concurrent.atomic.LongAdder();
    static final java.util.concurrent.atomic.LongAdder diagnosticNativeMultipleScatteringSubmissions = new java.util.concurrent.atomic.LongAdder();
    static final java.util.concurrent.atomic.LongAdder diagnosticNativeDensityScatteringSubmissions = new java.util.concurrent.atomic.LongAdder();
    static final java.util.concurrent.atomic.LongAdder diagnosticNativeOpticalSubmissions = new java.util.concurrent.atomic.LongAdder();
    static final java.util.concurrent.atomic.LongAdder diagnosticNativePbrSubmissions = new java.util.concurrent.atomic.LongAdder();
    static final java.util.concurrent.atomic.LongAdder diagnosticNativeBoundaryFusedSubmissions = new java.util.concurrent.atomic.LongAdder();
    static final java.util.concurrent.atomic.LongAdder diagnosticNativeBoundaryScatteringSubmissions = new java.util.concurrent.atomic.LongAdder();
    private final top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaMediumDensityAbi.Cache nativeDensityCache =
            new top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaMediumDensityAbi.Cache();
    private final VulkanSceneRuntime scene;
    private final VulkanGpuScenePipelines pipelines;
    private final VulkanGpuSceneFrameRing frameRing;
    private final VulkanGpuSceneTemporalCoordinator temporal;
    private final VulkanGpuSceneFeatureComposition features;
    private final VulkanGpuSceneDescriptorAssembler descriptors;
    private final String timingLabel;
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
        GpuSceneRayTracingPipeline photonPipeline = photonPlan.photons() > 0 ? pipelines.photons() : null;
        if(photonPipeline!=null&&photonPlan.bytes()>photonPipeline.maxStorageBufferRangeBytes())
            throw new top.ceroxe.rt.renderer.api.FrameValidationException(
                    top.ceroxe.rt.renderer.api.FrameValidationException.Reason.INVALID_CAUSTICS,
                    "complete surface and volume photon map exceeds device maxStorageBufferRange="
                            +photonPipeline.maxStorageBufferRangeBytes()+" bytes; reduce photon or event budget");
        var transport = temporal.selectTransport(checked.request());
        CudaMediumFrameProfile.validateShaderData(
                checked.request(), transport, pipelines.usesBuiltInSceneShaders());
        pipelines.validateShaderData(checked.request().shaderData());
        var serialProgram = transport.program();
        var route = transport.route();
        boolean nativeOpticalFrame = route == CudaMediumFrameProfile.Route.OPTICAL;
        boolean nativeBoundaryScatteringFrame = route == CudaMediumFrameProfile.Route.BOUNDARY_SCATTERING;
        boolean nativeMultipleFrame = route == CudaMediumFrameProfile.Route.MULTIPLE_SCATTERING;
        boolean nativeDensityScatteringFrame = route == CudaMediumFrameProfile.Route.DENSITY_SCATTERING;
        boolean nativeScatteringFrame = route == CudaMediumFrameProfile.Route.CAMERA_SCATTERING;
        boolean nativePbrScatteringFrame = route == CudaMediumFrameProfile.Route.PBR_SCATTERING;
        boolean nativePbrFrame = route == CudaMediumFrameProfile.Route.PBR || nativePbrScatteringFrame;
        boolean nativeBoundaryFrame = route == CudaMediumFrameProfile.Route.BOUNDARY;
        boolean nativeCameraFrame = route == CudaMediumFrameProfile.Route.CAMERA;
        boolean nativeMediumFrame = route != CudaMediumFrameProfile.Route.NONE;
        boolean nativeHeterogeneousMultipleFrame = nativeMultipleFrame && !checked.request().environment().cameraMedium().densityField().isUniform();
        var nativeBoundaryScene = transport.boundaries();
        if (nativeMediumFrame && !pipelines.supportsPrimaryQueue())
            throw new top.ceroxe.rt.renderer.api.FrameValidationException(
                    top.ceroxe.rt.renderer.api.FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,
                    "native medium transport requires built-in scene shaders");
        var selectedProgram = nativeMediumFrame ? GpuSceneRayTracingPipeline.Program.SURFACE_PRIMARY_SHADE
                : VulkanGpuSceneTemporalCoordinator.primaryQueueProgram(serialProgram,
                pipelines.supportsPrimaryQueue() && Boolean.getBoolean("top.ceroxe.rt.medium.primaryQueue"));
        int width = checkedExtents.renderWidth();
        int height = checkedExtents.renderHeight();
        var nativeBoundaryData = nativeBoundaryFrame ? CudaBoundaryMediumManager.scene(checked.request(),
                width, height, nativeBoundaryScene, temporal::nativeDensityTexture, nativeDensityCache) : null;
        var nativeBoundaryScatteringData = nativeBoundaryScatteringFrame ? CudaMultipleScatteringManager.boundaryScatteringScene(
                checked.request(),width,height,acceptedLightSlotUpperBound,nativeBoundaryScene,temporal::nativeDensityTexture,nativeDensityCache) : null;
        var nativeBoundaryManager = nativeBoundaryFrame ? pipelines.nativeBoundaryMedium() : null;
        boolean fusedBoundaryFrame = nativeBoundaryFrame && nativeBoundaryManager.canFuseConsumer(nativeBoundaryData);
        var nativeDensityScatteringData = nativeDensityScatteringFrame ? CudaDensityScatteringManager.scene(checked.request(),
                width,height,acceptedLightSlotUpperBound,temporal::nativeDensityTexture,nativeDensityCache) : null;
        var nativeOpticalData = nativeOpticalFrame ? CudaOpticalMediumManager.scene(checked.request(),width,height,
                nativeBoundaryScene,temporal::nativeDensityTexture,nativeDensityCache,
                temporal.nativeOpticalOpaqueRay(checked.request()),acceptedLightSlotUpperBound) : null;
        var nativePbrData = nativePbrFrame ? CudaPbrMediumManager.scene(checked.request(),width,height,
                acceptedLightSlotUpperBound,nativeBoundaryScene,temporal::nativeDensityTexture,nativeDensityCache,nativePbrScatteringFrame) : null;
        var nativeHeterogeneousMultipleData = nativeHeterogeneousMultipleFrame ? CudaMultipleScatteringManager.scene(
                checked.request(),width,height,acceptedLightSlotUpperBound,temporal::nativeDensityTexture,nativeDensityCache) : null;
        GpuScenePrimaryQueue.Plan selectedQueue = !nativeMediumFrame && selectedProgram.primaryQueue()
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
        GpuSceneRayTracingPipeline primaryProducer = nativePbrFrame
                ? pipelines.forFrame(GpuSceneRayTracingPipeline.Program.NATIVE_PBR_MEDIUM_TRACE)
                : nativeBoundaryFrame
                ? pipelines.forFrame(Boolean.parseBoolean(System.getProperty("top.ceroxe.rt.medium.cuda.boundaryPacket","true"))
                    ? GpuSceneRayTracingPipeline.Program.NATIVE_BOUNDARY_PACKET : GpuSceneRayTracingPipeline.Program.NATIVE_MEDIUM_TRACE)
                : primaryQueue != null || nativeCameraFrame || nativeScatteringFrame || nativeMultipleFrame || nativeDensityScatteringFrame || nativeOpticalFrame
                    ? pipelines.forFrame(GpuSceneRayTracingPipeline.Program.PRIMARY_TRACE) : null;
        GpuSceneRayTracingPipeline scatteringLights = nativePbrFrame
                ? pipelines.forFrame(GpuSceneRayTracingPipeline.Program.NATIVE_PBR_MEDIUM_LIGHTS)
                : nativeDensityScatteringFrame
                ? pipelines.forFrame(GpuSceneRayTracingPipeline.Program.NATIVE_DENSITY_SCATTERING_LIGHTS)
                : nativeScatteringFrame || nativeMultipleFrame && !nativeHeterogeneousMultipleFrame
                    ? pipelines.forFrame(GpuSceneRayTracingPipeline.Program.NATIVE_SCATTERING_LIGHTS) : null;
        var pbrScatteringKind = nativePbrScatteringFrame ? nativePbrData.transportPlan().kind() : null;
        GpuSceneRayTracingPipeline pbrScatteringPrimary = nativePbrScatteringFrame
                && pbrScatteringKind != top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaPbrMediumAbi.ScatteringKind.DIRECT_UNIFORM
                ? pipelines.forFrame(GpuSceneRayTracingPipeline.Program.PRIMARY_TRACE) : null;
        GpuSceneRayTracingPipeline cameraScatteringLights = nativePbrScatteringFrame
                ? pipelines.forFrame(pbrScatteringKind == top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaPbrMediumAbi.ScatteringKind.DIRECT_DENSITY
                    ? GpuSceneRayTracingPipeline.Program.NATIVE_DENSITY_SCATTERING_LIGHTS
                    : GpuSceneRayTracingPipeline.Program.NATIVE_SCATTERING_LIGHTS) : null;
        GpuSceneRayTracingPipeline multipleTracer = nativeMultipleFrame || nativePbrScatteringFrame
                && (pbrScatteringKind == top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaPbrMediumAbi.ScatteringKind.MULTIPLE_UNIFORM
                    || pbrScatteringKind == top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaPbrMediumAbi.ScatteringKind.MULTIPLE_DENSITY)
                ? pipelines.forFrame(GpuSceneRayTracingPipeline.Program.NATIVE_MULTIPLE_SCATTERING) : null;
        GpuSceneRayTracingPipeline boundaryScatteringQuery = nativeBoundaryScatteringFrame
                ? pipelines.nativeBoundaryScattering() : null;
        GpuSceneRayTracingPipeline opticalTracer = nativeOpticalFrame
                ? pipelines.forFrame(GpuSceneRayTracingPipeline.Program.NATIVE_OPTICAL_TRACE) : null;
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
                Math.addExact(primaryQueue == null ? 0 : slot.requiredPrimaryQueueGrowth(primaryQueue.bytes()),
                        nativeOpticalFrame ? pipelines.requiredNativeOpticalGrowth(nativeOpticalData)
                                : nativePbrScatteringFrame ? pipelines.requiredNativePbrScatteringGrowth(nativePbrData,width,height,
                                    checked.request().antiAliasing().samplesPerPixel(),checked.request().mediumTransport().scatteringSteps())
                                : nativePbrFrame ? pipelines.requiredNativePbrGrowth(nativePbrData)
                                : nativeHeterogeneousMultipleFrame ? pipelines.requiredNativeMultipleScatteringGrowth(nativeHeterogeneousMultipleData)
                                : nativeMultipleFrame ? pipelines.requiredNativeMultipleScatteringGrowth(width,height,
                                    checked.request().antiAliasing().samplesPerPixel(),checked.request().mediumTransport().scatteringSteps())
                                : nativeBoundaryScatteringFrame ? pipelines.requiredNativeBoundaryScatteringGrowth(nativeBoundaryScatteringData)
                                : nativeDensityScatteringFrame ? pipelines.requiredNativeDensityScatteringGrowth(nativeDensityScatteringData)
                                : nativeScatteringFrame ? pipelines.requiredNativeScatteringGrowth(width, height,
                                    checked.request().antiAliasing().samplesPerPixel(), checked.request().mediumTransport().scatteringSteps())
                                : nativeBoundaryFrame ? pipelines.requiredNativeBoundaryGrowth(nativeBoundaryData)
                                : nativeCameraFrame ? pipelines.requiredNativeCameraGrowth(width, height,
                                    checked.request().antiAliasing().samplesPerPixel()) : 0)));
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
            java.nio.ByteBuffer.wrap(temporalFrame.uniforms()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .putInt(VulkanGpuSceneAbi.FRAME_CAUSTIC_PHOTONS_WORD * 4, photonPlan.photons());
            slot.writeUniforms(temporalFrame.uniforms());
            pipeline.updateShaderData(slot.index(), checked.request().shaderData());
            var frameDescriptors = descriptors.assemble(
                    slot, acceptedSceneRevision, temporalFrame.gpu(), framePrimitives, frameFeatures);
            pipeline.updateDescriptorSet(slot.index(), frameDescriptors);
            if (photonPipeline != null) photonPipeline.updateDescriptorSet(slot.index(), frameDescriptors);
            if (primaryProducer != null) primaryProducer.updateDescriptorSet(slot.index(), frameDescriptors);
            if (scatteringLights != null) scatteringLights.updateDescriptorSet(slot.index(), frameDescriptors);
            if (cameraScatteringLights != null) cameraScatteringLights.updateDescriptorSet(slot.index(), frameDescriptors);
            if (pbrScatteringPrimary != null) pbrScatteringPrimary.updateDescriptorSet(slot.index(),frameDescriptors);
            if (multipleTracer != null) multipleTracer.updateDescriptorSet(slot.index(),frameDescriptors);
            if (boundaryScatteringQuery != null) boundaryScatteringQuery.updateDescriptorSet(slot.index(),frameDescriptors);
            if (opticalTracer != null) opticalTracer.updateDescriptorSet(slot.index(),frameDescriptors);

            // Boundary continuations still finish their internal blocking rounds here. The
            // camera route instead acquires CUDA ownership in the final frame submission.
            CudaPbrMediumManager.RadianceContribution scatteringContribution = null;
            if(nativePbrScatteringFrame) {
                var scatteringPlan = nativePbrData.transportPlan();
                scatteringContribution = switch(scatteringPlan.kind()) {
                    case DIRECT_UNIFORM -> {
                        var contribution = pipelines.nativeCameraScatteringForPbr().prepareRadianceContribution(checked.request(),
                                scatteringPlan.direct(),cameraScatteringLights,slot.index(),framePrimitives.buildPlan());
                        yield new CudaPbrMediumManager.RadianceContribution(contribution.buffer(),contribution.offset(),contribution.bytes(),false);
                    }
                    case DIRECT_DENSITY -> pipelines.nativeDensityScatteringForPbr().prepareRadianceContribution(
                            scatteringPlan.densityDirect(),pbrScatteringPrimary,cameraScatteringLights,slot.index(),framePrimitives.buildPlan());
                    case MULTIPLE_UNIFORM -> pipelines.nativeMultipleScatteringForPbr().prepareRadianceContribution(checked.request(),
                            scatteringPlan.multiple(),pbrScatteringPrimary,cameraScatteringLights,multipleTracer,slot.index(),framePrimitives.buildPlan());
                    case MULTIPLE_DENSITY -> pipelines.nativeMultipleScatteringForPbr().prepareRadianceContribution(
                            scatteringPlan.densityMultiple(),pbrScatteringPrimary,multipleTracer,slot.index(),framePrimitives.buildPlan());
                };
            }
            CudaCameraMediumManager.Prepared nativeCamera = nativeOpticalFrame
                    ? pipelines.nativeOpticalMedium().prepare(nativeOpticalData,primaryProducer,opticalTracer,slot.index(),
                            framePrimitives.buildPlan(),photonPlan,photonPipeline,slot.causticPhotons())
                    : nativePbrFrame
                    ? (nativePbrScatteringFrame?pipelines.nativePbrMediumWithScattering(nativePbrData):pipelines.nativePbrMedium())
                            .prepare(nativePbrData,primaryProducer,scatteringLights,slot.index(),
                            nativePbrScatteringFrame?null:framePrimitives.buildPlan(),photonPlan,photonPipeline,slot.causticPhotons(),
                            scatteringContribution)
                    : nativeHeterogeneousMultipleFrame
                    ? pipelines.nativeMultipleScattering().prepare(nativeHeterogeneousMultipleData,primaryProducer,multipleTracer,slot.index(),framePrimitives.buildPlan())
                    : nativeMultipleFrame
                    ? pipelines.nativeMultipleScattering().prepare(checked.request(),width,height,primaryProducer,scatteringLights,multipleTracer,slot.index(),framePrimitives.buildPlan())
                    : nativeBoundaryScatteringFrame
                    ? pipelines.nativeMultipleScattering().prepareBoundaryScattering(nativeBoundaryScatteringData,boundaryScatteringQuery,slot.index(),framePrimitives.buildPlan())
                    : nativeDensityScatteringFrame
                    ? pipelines.nativeDensityScattering().prepare(nativeDensityScatteringData,primaryProducer,scatteringLights,slot.index(),framePrimitives.buildPlan())
                    : nativeScatteringFrame
                    ? pipelines.nativeCameraScattering().prepare(checked.request(), width, height,
                            primaryProducer, scatteringLights, slot.index(), framePrimitives.buildPlan())
                    : nativeBoundaryFrame && !fusedBoundaryFrame
                    ? pipelines.nativeBoundaryMedium().prepare(nativeBoundaryData,
                            primaryProducer, slot.index(), framePrimitives.buildPlan())
                    : null;

            long descriptorEpoch = Math.incrementExact(lastSubmittedDescriptorEpoch);
            int previousLayout = slot.imageLayout();
            boolean acquireExternalOwnership = slot.externallyOwned();
            boolean releaseExternalOwnership = !scene.device().managedPresentationActive();
            int producerQueueFamilyIndex = scene.device().queueFamilyIndex();
            VulkanDeviceRuntime.ManagedPresentationSignal readySignal = releaseExternalOwnership
                    ? VulkanDeviceRuntime.ManagedPresentationSignal.disabled()
                    : scene.device().reserveManagedPresentationSignal();
            java.util.function.BiFunction<CudaCameraMediumManager.Prepared,
                    java.util.function.Consumer<org.lwjgl.vulkan.VkCommandBuffer>,
                    RtCommandContext.CommandRecorder> recorder = (preparedCamera, acquireNative) -> (commandBuffer, stack) -> {
                if (acquireNative != null) acquireNative.accept(commandBuffer);
                if (framePrimitives.buildPlan() != null && preparedCamera == null) {
                    framePrimitives.buildPlan().record(commandBuffer, stack);
                }
                // Native PBR resolves in CUDA after its producer handoff. Its photon map is
                // recorded inside that handoff so CUDA observes completed photon writes; all
                // other paths retain the ordinary final-command photon stage.
                if (!nativePbrFrame && !nativeOpticalFrame)
                    photonPlan.record(commandBuffer, stack, slot.causticPhotons(), photonPipeline, slot.index());
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
                        preparedCamera != null ? () -> preparedCamera.recordConsumer(commandBuffer, stack,
                                pipeline, slot.index())
                        : primaryQueue == null ? null : () -> GpuScenePrimaryQueue.record(commandBuffer, stack,
                                primaryProducer, pipeline, slot.index(), slot.primaryQueue(), primaryQueue)
                );
            };
            nativeSubmission = nativeCameraFrame
                    ? pipelines.nativeCameraMedium().submitBlocking(checked.request(), width, height,
                            primaryProducer, slot.index(), framePrimitives.buildPlan(),
                            (prepared, semaphore, acquire) -> submitNative(frameSequence, inputCompletion,
                                    readySignal, semaphore, slot, recorder.apply(prepared, acquire)))
                    : fusedBoundaryFrame
                    ? nativeBoundaryManager.submitBlocking(nativeBoundaryData, primaryProducer, slot.index(),
                            framePrimitives.buildPlan(), (prepared, semaphore, acquire) -> submitNative(
                                    frameSequence, inputCompletion, readySignal, semaphore, slot,
                                    recorder.apply(prepared, acquire)))
                    : submitNative(frameSequence, inputCompletion, readySignal, 0L, slot, recorder.apply(nativeCamera, null));
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
            if (nativeCameraFrame) diagnosticNativeCameraSubmissions.increment();
            if (nativeBoundaryFrame) diagnosticNativeBoundarySubmissions.increment();
            if (nativeBoundaryScatteringFrame) diagnosticNativeBoundaryScatteringSubmissions.increment();
            if (fusedBoundaryFrame) diagnosticNativeBoundaryFusedSubmissions.increment();
            if (nativeScatteringFrame) diagnosticNativeScatteringSubmissions.increment();
            if (nativeMultipleFrame) diagnosticNativeMultipleScatteringSubmissions.increment();
            if (nativeDensityScatteringFrame) diagnosticNativeDensityScatteringSubmissions.increment();
            if (nativePbrScatteringFrame) {
                switch (nativePbrData.transportPlan().kind()) {
                    case DIRECT_UNIFORM -> diagnosticNativeScatteringSubmissions.increment();
                    case DIRECT_DENSITY -> diagnosticNativeDensityScatteringSubmissions.increment();
                    case MULTIPLE_UNIFORM, MULTIPLE_DENSITY -> diagnosticNativeMultipleScatteringSubmissions.increment();
                }
            }
            if (nativeOpticalFrame) diagnosticNativeOpticalSubmissions.increment();
            if (nativePbrFrame) diagnosticNativePbrSubmissions.increment();
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
    }

    private RtCommandContext.AsyncSubmission submitNative(
            long frameSequence,
            VulkanFeatureSession.InputCompletion inputCompletion,
            VulkanDeviceRuntime.ManagedPresentationSignal readySignal,
            long nativeWaitSemaphore,
            VulkanFrameSlot deliverySlot,
            RtCommandContext.CommandRecorder recorder
    ) {
        scene.device().featureSession().beginFrameSubmission(frameSequence);
        RtCommandContext.AsyncSubmission nativeSubmission;
        try {
            nativeSubmission = scene.device().frameCommands().submitTimedExternalSynchronizedOneTimeAsync(
                    timingLabel, nativeWaitSemaphore, deliverySlot.readyBinarySemaphore(),
                    inputCompletion.semaphore(), inputCompletion.value(),
                    readySignal.semaphore(), readySignal.value(), recorder);
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
