package top.ceroxe.rt.renderer.nvidia;

import top.ceroxe.rt.renderer.api.FrameGenerationOptions;
import top.ceroxe.rt.renderer.api.FrameGenerationEvidence;
import top.ceroxe.rt.renderer.api.FrameReconstructionOptions;
import top.ceroxe.rt.renderer.api.DenoisingOptions;
import top.ceroxe.rt.renderer.api.LowLatencyOptions;
import top.ceroxe.rt.renderer.api.RayTracingOptimizationOptions;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.RendererFeaturePreference;
import top.ceroxe.rt.renderer.api.RendererFeatureProfile;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Entry;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Feature;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Status;
import top.ceroxe.rt.renderer.feature.VulkanFeatureFrameContext;
import top.ceroxe.rt.renderer.feature.VulkanFeatureFallbackException;
import top.ceroxe.rt.renderer.feature.VulkanFeatureOpenContext;
import top.ceroxe.rt.renderer.feature.VulkanFeatureSession;
import top.ceroxe.rt.renderer.feature.VulkanFeatureRuntimeState;
import top.ceroxe.rt.renderer.feature.VulkanAccelerationStructureMemoryOptimizer;
import top.ceroxe.rt.renderer.feature.VulkanSwapchainInterceptor;
import top.ceroxe.rt.renderer.rt.pipeline.VulkanFrameExtents;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;

/** Owns the runtime state and synchronized frame lifecycle of one NVIDIA feature session. */
final class NvidiaFeatureSession implements VulkanFeatureSession {

    private static boolean lowLatencyBound(Set<NvidiaStreamlineRuntime.Feature> features) {
        return features.contains(NvidiaStreamlineRuntime.Feature.REFLEX)
                && features.contains(NvidiaStreamlineRuntime.Feature.PCL);
    }

    static boolean streamlinePresentProxyRequired(Set<NvidiaStreamlineRuntime.Feature> features) {
        Set<NvidiaStreamlineRuntime.Feature> checked = Set.copyOf(features);
        return checked.contains(NvidiaStreamlineRuntime.Feature.DLSS_FRAME_GENERATION)
                || lowLatencyBound(checked);
    }

    private static String implementation(NvidiaStreamlineRuntime.Feature feature) {
        return feature == NvidiaStreamlineRuntime.Feature.NIS
                ? "nvidia.streamline.nis" : "nvidia.streamline.dlss";
    }

    private final RenderingFeatureCapabilities capabilities;
    private final DenoisingOptions reservedDenoising;
    private final FrameReconstructionOptions reservedReconstruction;
    private final FrameGenerationOptions reservedFrameGeneration;
    private final LowLatencyOptions reservedLowLatency;
    private DenoisingOptions denoising;
    private FrameReconstructionOptions reconstruction;
    private FrameGenerationOptions frameGeneration;
    private LowLatencyOptions lowLatency;
    private final RayTracingOptimizationOptions optimizations;
    private final Set<NvidiaStreamlineRuntime.Feature> streamlineFeatures;
    private NvidiaStreamlineRuntime.Feature streamlineFeature;
    private final NvidiaStreamlineSwapchainInterceptor swapchainInterceptor;
    private final NvidiaNativeFeatureSessions nativeSessions;
    private final NvidiaStreamlineDeviceSession streamlineSession;
    private final NvidiaRtxmuMemoryOptimizer rtxmu;
    private final VulkanFeatureRuntimeState memoryOptimizationState;
    private final VkDevice device;
    private boolean closed;
    private boolean streamlineEvaluated;
    private boolean denoisingFailed;
    private boolean reconstructionFailed;
    private boolean frameGenerationFailed;
    private boolean lowLatencyFailed;
    private long denoisingFallbackCompletionAfter = -1L;
    private long reconstructionFallbackCompletionAfter = -1L;
    private long frameGenerationFallbackPresentAfter = -1L;
    private final NvidiaFeatureExecutionEvidence executionEvidence =
            new NvidiaFeatureExecutionEvidence();
    private VulkanFeatureSession.InputCompletion pendingInputCompletion =
            VulkanFeatureSession.InputCompletion.none();
    private String denoisingFailureReason;
    private String reconstructionFailureReason;
    private String frameGenerationFailureReason;
    private String lowLatencyFailureReason;
    private final VulkanFeatureRuntimeState denoisingState;
    private final VulkanFeatureRuntimeState reconstructionState;
    private final VulkanFeatureRuntimeState frameGenerationState;
    private final VulkanFeatureRuntimeState lowLatencyState;
    private NvidiaStreamlineFrameGenerationRuntime.Stats latestFrameGenerationStats =
            NvidiaStreamlineFrameGenerationRuntime.emptyStats();
    private FrameGenerationEvidence latestFrameGenerationEvidence =
            FrameGenerationEvidence.unavailable();
    private boolean frameGenerationStatisticsUnavailable;

    private static String describeFailure(String operation, Throwable failure) {
        String message = failure.getMessage();
        return operation + " failed: " + failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static RuntimeException fallbackBoundary(String reason, Throwable cause) {
        return new VulkanFeatureFallbackException(
                reason + "; current frame aborted before submission, fallback applies next frame", cause
        );
    }

    NvidiaFeatureSession(
            NvidiaNativeFeatureSessions nativeSessions,
            NvidiaStreamlineDeviceSession streamlineSession,
            RenderingFeatureCapabilities capabilities,
            DenoisingOptions denoising,
            FrameReconstructionOptions reconstruction,
            FrameGenerationOptions frameGeneration,
            LowLatencyOptions lowLatency,
            Set<NvidiaStreamlineRuntime.Feature> streamlineFeatures,
            VulkanFeatureOpenContext context
    ) {
        this.nativeSessions = Objects.requireNonNull(nativeSessions, "nativeSessions");
        this.streamlineSession = streamlineSession;
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.denoising = Objects.requireNonNull(denoising, "denoising");
        this.reconstruction = Objects.requireNonNull(reconstruction, "reconstruction");
        this.frameGeneration = Objects.requireNonNull(frameGeneration, "frameGeneration");
        this.lowLatency = Objects.requireNonNull(lowLatency, "lowLatency");
        this.reservedDenoising = this.denoising;
        this.reservedReconstruction = this.reconstruction;
        this.reservedFrameGeneration = this.frameGeneration;
        this.reservedLowLatency = this.lowLatency;
        this.optimizations = Objects.requireNonNull(
                context.configuration().rayTracingOptimizations(), "optimizations"
        );
        this.streamlineFeatures = Set.copyOf(streamlineFeatures);
        this.streamlineFeature = this.streamlineFeatures.contains(NvidiaStreamlineRuntime.Feature.DLSS)
                ? NvidiaStreamlineRuntime.Feature.DLSS
                : this.streamlineFeatures.contains(NvidiaStreamlineRuntime.Feature.NIS)
                ? NvidiaStreamlineRuntime.Feature.NIS : null;
        this.swapchainInterceptor = streamlinePresentProxyRequired(this.streamlineFeatures)
                ? new NvidiaStreamlineSwapchainInterceptor(frameGeneration) : null;
        this.device = Objects.requireNonNull(context, "context").device().device();
        this.memoryOptimizationState = runtimeState(
                capabilities,
                Feature.MEMORY_OPTIMIZATION,
                "nvidia.rtx-memory-utility"
        );
        this.rtxmu = !nativeSessions.rtxmuAvailable()
                ? null
                : new NvidiaRtxmuMemoryOptimizer(
                        nativeSessions.rtxmuHandle(),
                        device,
                        context.device().accelerationStructureScratchAlignment(),
                        context.configuration().rayTracingOptimizations().memoryOptimization(),
                        memoryOptimizationState
                        );
        if (nativeSessions.nrdOpenFailure() != null) {
            denoisingFailed = true;
            denoisingFailureReason = describeFailure(
                    "NRD device open", nativeSessions.nrdOpenFailure()
            );
        }
        this.denoisingState = runtimeState(capabilities, Feature.DENOISING, "nvidia.nrd");
        this.reconstructionState = runtimeState(capabilities, Feature.FRAME_RECONSTRUCTION,
                "nvidia.streamline");
        this.frameGenerationState = runtimeState(capabilities, Feature.FRAME_GENERATION,
                "nvidia.streamline.dlss-g");
        this.lowLatencyState = runtimeState(capabilities, Feature.LOW_LATENCY,
                "nvidia.streamline.reflex-pcl");
        Entry reconstructionEntry = capabilities.feature(Feature.FRAME_RECONSTRUCTION);
        if (reconstructionEntry.status() == Status.FALLBACK_PENDING
                && !"nvidia.streamline.nis".equals(reconstructionEntry.implementation())) {
            reconstructionFailed = true;
            reconstructionFailureReason = reconstructionEntry.reason();
        }
        Entry generationEntry = capabilities.feature(Feature.FRAME_GENERATION);
        if (generationEntry.status() == Status.FALLBACK_PENDING) {
            frameGenerationFailed = true;
            frameGenerationFailureReason = generationEntry.reason();
        }
        Entry lowLatencyEntry = capabilities.feature(Feature.LOW_LATENCY);
        if (lowLatencyEntry.status() == Status.FALLBACK_PENDING) {
            lowLatencyFailed = true;
            lowLatencyFailureReason = lowLatencyEntry.reason();
        }
    }

    private static VulkanFeatureRuntimeState runtimeState(RenderingFeatureCapabilities values,
                                                          Feature feature, String implementation) {
        Entry entry = values.feature(feature);
        VulkanFeatureRuntimeState.Status initial = switch (entry.status()) {
            case ACTIVE -> VulkanFeatureRuntimeState.Status.ACTIVE;
            case FALLBACK_PENDING -> VulkanFeatureRuntimeState.Status.RECOVERING;
            case FALLBACK -> VulkanFeatureRuntimeState.Status.FALLBACK;
            case NOT_SUPPORTED, BLOCKED -> VulkanFeatureRuntimeState.Status.UNAVAILABLE;
            default -> VulkanFeatureRuntimeState.Status.AVAILABLE;
        };
        return new VulkanFeatureRuntimeState(initial,
                "none".equals(entry.implementation()) ? implementation : entry.implementation(),
                entry.reason());
    }

    @Override
    public synchronized RenderingFeatureCapabilities capabilities() {
        absorbPresentationFailure();
        boolean generationBound = streamlineFeatures.contains(
                NvidiaStreamlineRuntime.Feature.DLSS_FRAME_GENERATION
        ) && swapchainInterceptor != null;
        NvidiaStreamlineFrameGenerationRuntime.Stats generationStats =
                readFrameGenerationStats(generationBound);
        if (frameGenerationFailed
                && executionEvidence.latestSuccessfulPresent()
                > frameGenerationFallbackPresentAfter) {
            frameGenerationState.fallback(
                    "native.presentation",
                    frameGenerationFailureReason
                            + "; a later native frame completed presentation"
            );
        }
        return NvidiaRuntimeCapabilities.project(
                capabilities,
                new NvidiaRuntimeCapabilities.Denoising(
                        denoising,
                        nativeSessions.nrdAvailable(),
                        denoisingFailed,
                        denoisingFailureReason,
                        denoisingState.snapshot()
                ),
                new NvidiaRuntimeCapabilities.Reconstruction(
                        reconstruction,
                        streamlineFeature,
                        streamlineEvaluated,
                        reconstructionFailed,
                        reconstructionFailureReason,
                        reconstructionState.snapshot()
                ),
                new NvidiaRuntimeCapabilities.FrameGeneration(
                        frameGeneration,
                        generationBound,
                        generationBound && swapchainInterceptor.frameGenerationEnabled(),
                        frameGenerationFailed,
                        frameGenerationFailureReason,
                        generationStats,
                        frameGenerationState.snapshot().status()
                                == VulkanFeatureRuntimeState.Status.FALLBACK
                ),
                new NvidiaRuntimeCapabilities.LowLatency(
                        lowLatency,
                        lowLatencyBound(streamlineFeatures),
                        executionEvidence.lowLatencyPresentCommitted()
                                && lowLatencyState.active(),
                        lowLatencyFailed,
                        lowLatencyFailureReason,
                        lowLatencyState.snapshot().status()
                                == VulkanFeatureRuntimeState.Status.FALLBACK
                ),
                new NvidiaRuntimeCapabilities.MemoryOptimization(
                        contextMemoryOptimizationRequested(),
                        rtxmu != null,
                        memoryOptimizationState.snapshot(),
                        nativeSessions.rtxmuOpenFailure() == null
                                ? null
                                : describeFailure(
                                        "RTXMU device open",
                                        nativeSessions.rtxmuOpenFailure()
                                )
                )
        );
    }

    @Override
    public synchronized VulkanFeatureSession.ReconfigurationAssessment assessReconfiguration(
            RendererFeatureProfile source,
            RendererFeatureProfile target
    ) {
        if (closed) throw new IllegalStateException("NVIDIA feature session is closed");
        RendererFeatureProfile checkedSource = Objects.requireNonNull(source, "source");
        RendererFeatureProfile checkedTarget = Objects.requireNonNull(target, "target");
        if (!profile().equals(checkedSource)) {
            return VulkanFeatureSession.ReconfigurationAssessment.rendererRebuild(
                    "NVIDIA session source profile does not match the controller generation"
            );
        }
        if (checkedTarget.rayTracingOptimizations().shaderExecutionReordering()
                != checkedSource.rayTracingOptimizations().shaderExecutionReordering()
                || checkedTarget.rayTracingOptimizations().memoryOptimization()
                != checkedSource.rayTracingOptimizations().memoryOptimization()) {
            return VulkanFeatureSession.ReconfigurationAssessment.rendererRebuild(
                    "NVIDIA provider does not own SER or RTXMU transition boundaries"
            );
        }
        if (!reservedReconstructionTarget(checkedTarget.frameReconstruction())) {
            return VulkanFeatureSession.ReconfigurationAssessment.rendererRebuild(
                    "reconstruction mode, quality, or fallback was not reserved for hot switching"
            );
        }
        if (!reservedDenoisingTarget(checkedTarget.denoising())) {
            return VulkanFeatureSession.ReconfigurationAssessment.rendererRebuild(
                    "denoising strategy or fallback was not reserved for hot switching"
            );
        }
        if (!reservedFrameGenerationTarget(checkedTarget.frameGeneration())) {
            return VulkanFeatureSession.ReconfigurationAssessment.rendererRebuild(
                    "frame-generation family or fallback was not reserved for hot switching"
            );
        }
        if (!reservedLowLatencyTarget(checkedTarget.lowLatency())) {
            return VulkanFeatureSession.ReconfigurationAssessment.rendererRebuild(
                    "low-latency provider was not reserved for hot switching"
            );
        }
        if (checkedTarget.frameReconstruction().preference().requested()) {
            NvidiaStreamlineRuntime.Feature requested = reconstructionFeature(
                    checkedTarget.frameReconstruction()
            );
            if (!streamlineFeatures.contains(requested) || reconstructionFailed) {
                return VulkanFeatureSession.ReconfigurationAssessment.rendererRebuild(
                        "requested reconstruction implementation was not reserved or has failed"
                );
            }
        }
        if (checkedTarget.frameGeneration().preference().requested()) {
            if (!streamlineFeatures.contains(NvidiaStreamlineRuntime.Feature.DLSS_FRAME_GENERATION)
                    || frameGenerationFailed || swapchainInterceptor == null
                    || !swapchainInterceptor.proxyActive()) {
                return VulkanFeatureSession.ReconfigurationAssessment.rendererRebuild(
                        "frame generation requires a healthy reserved Streamline proxy swapchain"
                );
            }
        }
        if (checkedTarget.lowLatency().preference().requested()
                && (!lowLatencyBound(streamlineFeatures) || lowLatencyFailed)) {
            return VulkanFeatureSession.ReconfigurationAssessment.rendererRebuild(
                    "Reflex/PCL was not reserved as a healthy provider pair"
            );
        }
        if (checkedTarget.denoising().preference().requested()
                && (!nativeSessions.nrdAvailable() || denoisingFailed)) {
            return VulkanFeatureSession.ReconfigurationAssessment.rendererRebuild(
                    "NRD resources were not reserved or the NRD session has failed"
            );
        }
        return VulkanFeatureSession.ReconfigurationAssessment.frameDrain(
                "reserved NVIDIA feature session can apply the target at a drained frame boundary"
        );
    }

    @Override
    public synchronized void applyReconfiguration(
            RendererFeatureProfile source,
            RendererFeatureProfile target
    ) {
        if (closed) throw new IllegalStateException("NVIDIA feature session is closed");
        RendererFeatureProfile checkedSource = Objects.requireNonNull(source, "source");
        RendererFeatureProfile checkedTarget = Objects.requireNonNull(target, "target");
        if (!profile().equals(checkedSource)) {
            throw new IllegalStateException("NVIDIA feature transition source is stale");
        }
        if (swapchainInterceptor != null) {
            // This may call slDLSSGSetOptions(eOff) and release valid-until-present tags. The core
            // caller has already proved that every frame slot and external lease is retired.
            swapchainInterceptor.reconfigure(checkedTarget.frameGeneration());
        } else if (checkedTarget.frameGeneration().preference().requested()) {
            throw new IllegalStateException("frame-generation proxy was not reserved");
        }
        denoising = checkedTarget.denoising();
        reconstruction = checkedTarget.frameReconstruction();
        frameGeneration = checkedTarget.frameGeneration();
        lowLatency = checkedTarget.lowLatency();
        streamlineFeature = reconstruction.preference().requested()
                ? reconstructionFeature(reconstruction) : null;
        streamlineEvaluated = false;
        pendingInputCompletion = VulkanFeatureSession.InputCompletion.none();
        publishReconfigurationStates();
    }

    private RendererFeatureProfile profile() {
        return new RendererFeatureProfile(
                reconstruction, frameGeneration, lowLatency, denoising, optimizations
        );
    }

    /**
     * Runtime transitions may only alter preference once the resource-producing shape was
     * reserved. Disabled is a valid target because it releases provider work without inventing
     * resources; changing reconstruction mode/quality would require a new extent contract.
     */
    private boolean reservedReconstructionTarget(FrameReconstructionOptions target) {
        if (!target.preference().requested()) return true;
        return reservedReconstruction.preference().requested()
                && target.mode() == reservedReconstruction.mode()
                && target.quality() == reservedReconstruction.quality();
    }

    private boolean reservedDenoisingTarget(DenoisingOptions target) {
        if (!target.preference().requested()) return true;
        return reservedDenoising.preference().requested()
                && target.strategy() == reservedDenoising.strategy();
    }

    private boolean reservedFrameGenerationTarget(FrameGenerationOptions target) {
        if (!target.preference().requested()) return true;
        return reservedFrameGeneration.preference().requested()
                && target.mode() != FrameGenerationOptions.Mode.DISABLED;
    }

    private boolean reservedLowLatencyTarget(LowLatencyOptions target) {
        return !target.preference().requested() || reservedLowLatency.preference().requested();
    }

    private static NvidiaStreamlineRuntime.Feature reconstructionFeature(
            FrameReconstructionOptions options
    ) {
        return options.mode() == FrameReconstructionOptions.Mode.SPATIAL_UPSCALING
                ? NvidiaStreamlineRuntime.Feature.NIS : NvidiaStreamlineRuntime.Feature.DLSS;
    }

    private void publishReconfigurationStates() {
        if (denoising.preference().requested()) {
            denoisingState.available("nvidia.nrd", "NRD reserved session re-enabled; awaiting GPU completion");
        } else {
            denoisingState.unavailable("denoising disabled by the effective feature profile");
        }
        if (reconstruction.preference().requested()) {
            reconstructionState.available(
                    implementation(streamlineFeature),
                    "Streamline reconstruction reserved session re-enabled; awaiting evaluate"
            );
        } else {
            reconstructionState.unavailable("frame reconstruction disabled by the effective feature profile");
        }
        if (frameGeneration.preference().requested()) {
            frameGenerationState.available(
                    "nvidia.streamline.dlss-g",
                    "frame-generation cadence committed; awaiting tagged present"
            );
        } else {
            frameGenerationState.unavailable("frame generation disabled by the effective feature profile");
        }
        if (lowLatency.preference().requested()) {
            lowLatencyState.available(
                    "nvidia.streamline.reflex-pcl",
                    "Reflex/PCL markers reserved session re-enabled; awaiting present"
            );
        } else {
            lowLatencyState.unavailable("low-latency markers disabled by the effective feature profile");
        }
    }

    @Override
    public synchronized FrameGenerationEvidence frameGenerationEvidence() {
        if (closed) throw new IllegalStateException("NVIDIA feature session is closed");
        if (!frameGeneration.preference().requested()) {
            return FrameGenerationEvidence.unavailable();
        }
        absorbPresentationFailure();
        boolean generationBound = streamlineFeatures.contains(
                NvidiaStreamlineRuntime.Feature.DLSS_FRAME_GENERATION
        ) && swapchainInterceptor != null;
        NvidiaStreamlineFrameGenerationRuntime.Stats stats =
                readFrameGenerationStats(generationBound);
        OptionalInt nativeStatus = !frameGenerationFailed
                && !frameGenerationStatisticsUnavailable
                && stats.latestQuerySucceeded()
                ? OptionalInt.of(stats.status()) : OptionalInt.empty();
        FrameGenerationEvidence.Builder evidence = FrameGenerationEvidence.builder()
                .reported(true)
                .requestedGeneratedFramesPerNativeFrame(
                        frameGeneration.multiplier().presentedFramesPerNativeFrame() - 1
                )
                .lastSubmittedGeneratedFramesPerNativeFrame(stats.lastRequestedGeneratedFrames())
                .configuredGeneratedFramesPerNativeFrame(stats.configuredGeneratedFrames())
                .proxyPresentCalls(stats.proxyPresentCalls())
                .stateSamples(stats.stateSamples())
                .stateQueryCalls(stats.stateQueryCalls())
                .totalFramesActuallyPresented(stats.framesActuallyPresented())
                .generatedFramesActuallyPresented(stats.generatedFramesActuallyPresented())
                .lastFramesActuallyPresented(stats.lastFramesActuallyPresented())
                .maximumSupportedGeneratedFramesPerNativeFrame(stats.maxFramesToGenerate())
                .stateQueryFailures(stats.stateQueryFailures())
                .generationRequestMisses(stats.generationRequestMisses())
                .maximumGeneratedFramesObservedPerSample(stats.maxGeneratedFramesInSample())
                .latestNativeStatus(nativeStatus)
                .latestQuerySucceeded(nativeStatus.isPresent())
                .resetEpoch(stats.resetEpoch());
        if (stats.proxyPresentCalls() > 0L) {
            evidence.proxyPresentSequenceRange(
                    stats.firstProxyPresentSequence(), stats.lastProxyPresentSequence()
            );
        }
        if (stats.generatedFramesActuallyPresented() > 0L) {
            evidence.lastGeneratedObservationSequence(stats.lastGeneratedObservationSequence());
        }
        latestFrameGenerationEvidence = evidence.build();
        return latestFrameGenerationEvidence;
    }

    @Override
    public synchronized top.ceroxe.rt.renderer.api.TechnologyExecutionEvidence
    technologyExecutionEvidence() {
        if (closed) throw new IllegalStateException("NVIDIA feature session is closed");
        RenderingFeatureCapabilities resolved = capabilities();
        FrameGenerationEvidence generationEvidence = frameGenerationEvidence();
        return NvidiaTechnologyEvidenceProjector.project(
                resolved,
                reconstruction,
                frameGeneration,
                denoising,
                lowLatency,
                optimizations,
                executionEvidence,
                generationEvidence,
                rtxmu == null ? 0L : rtxmu.completedBuilds()
        );
    }

    private void absorbPresentationFailure() {
        if (swapchainInterceptor == null) return;
        swapchainInterceptor.presentationFailure().ifPresent(failure -> {
            String reason = "Streamline presentation failed: " + failure.description();
            if (lowLatencyBound(streamlineFeatures) && !lowLatencyFailed) {
                lowLatencyFailed = true;
                lowLatencyFailureReason = reason + "; Reflex/PCL present markers are unavailable";
                lowLatencyState.recovering("native.scheduling", lowLatencyFailureReason);
            }
            if (frameGeneration.preference().requested() && !frameGenerationFailed) {
                markFrameGenerationFallbackPending(
                        reason + "; DLSS-G/MFG generation is unavailable",
                        failure.frameSequence()
                );
            }
        });
    }

    private NvidiaStreamlineFrameGenerationRuntime.Stats readFrameGenerationStats(
            boolean generationBound
    ) {
        if (!generationBound || frameGenerationStatisticsUnavailable) {
            return latestFrameGenerationStats;
        }
        try {
            latestFrameGenerationStats = swapchainInterceptor.frameGenerationStats();
        } catch (RuntimeException | LinkageError failure) {
            // Diagnostics are observational. Preserve the last coherent native snapshot while
            // the feature state records the query failure; never fail the renderer merely because
            // an optional telemetry read could not complete.
            frameGenerationStatisticsUnavailable = true;
            markFrameGenerationFallbackPending(describeFailure(
                    "DLSS-G/MFG statistics query", failure
            ));
        }
        return latestFrameGenerationStats;
    }

    private boolean contextMemoryOptimizationRequested() {
        return memoryOptimizationState.snapshot().status()
                != VulkanFeatureRuntimeState.Status.UNAVAILABLE
                || nativeSessions.rtxmuOpenFailure() != null;
    }

    @Override
    public synchronized VulkanFrameExtents negotiateFrameExtents(
            RenderFrameRequest request, VulkanFrameExtents requested
    ) {
        if (closed) throw new IllegalStateException("NVIDIA feature session is closed");
        if (streamlineFeature == null || reconstructionFailed) return requested;
        try {
            return NvidiaStreamlineReconstructionRuntime.extents(
                    reconstruction, streamlineFeature, request.width(), request.height()
            );
        } catch (RuntimeException | LinkageError failure) {
            NvidiaFeatureFailurePolicy.Action action = NvidiaFeatureFailurePolicy.reconstruction(
                    reconstruction, streamlineFeature,
                    streamlineFeatures.contains(NvidiaStreamlineRuntime.Feature.NIS)
            );
            if (action == NvidiaFeatureFailurePolicy.Action.FAIL_REQUIRED) throw failure;
            String reason = describeFailure("Streamline " + streamlineFeature + " extent negotiation", failure);
            if (action == NvidiaFeatureFailurePolicy.Action.SWITCH_TO_NIS) {
                streamlineFeature = NvidiaStreamlineRuntime.Feature.NIS;
                streamlineEvaluated = false;
                try {
                    return NvidiaStreamlineReconstructionRuntime.extents(
                            reconstruction, streamlineFeature, request.width(), request.height()
                    );
                } catch (RuntimeException | LinkageError fallbackFailure) {
                    reconstructionFailed = true;
                    reconstructionFallbackCompletionAfter =
                            executionEvidence.latestCommittedSubmission();
                    reconstructionFailureReason = reason + "; NIS fallback failed: "
                            + describeFailure("NIS extent negotiation", fallbackFailure);
                    reconstructionState.recovering("native.resolution", reconstructionFailureReason);
                    return requested;
                }
            }
            reconstructionFailed = true;
            reconstructionFallbackCompletionAfter = executionEvidence.latestCommittedSubmission();
            reconstructionFailureReason = reason + "; next frame uses native-resolution rendering";
            reconstructionState.recovering("native.resolution", reconstructionFailureReason);
            return requested;
        }
    }

    @Override
    public synchronized boolean extentNegotiationMayChangeCapabilities() {
        return streamlineFeature != null && !reconstructionFailed;
    }

    @Override
    public synchronized void recordDenoising(VulkanFeatureFrameContext context) {
        if (closed) throw new IllegalStateException("NVIDIA feature session is closed");
        if (!nativeSessions.nrdAvailable() || denoisingFailed) return;
        try {
            NvidiaNrdRuntime.recordPostTrace(nativeSessions.nrdHandle(), context);
            executionEvidence.recordDenoising(context.frameSequence());
        } catch (RuntimeException | LinkageError failure) {
            NvidiaFeatureFailurePolicy.Action action = NvidiaFeatureFailurePolicy.denoising(denoising);
            if (action == NvidiaFeatureFailurePolicy.Action.FAIL_REQUIRED) throw failure;
            denoisingFailed = true;
            denoisingFallbackCompletionAfter = executionEvidence.latestCommittedSubmission();
            denoisingFailureReason = describeFailure("NRD record", failure);
            denoisingState.recovering(
                    action == NvidiaFeatureFailurePolicy.Action.FALLBACK_TEMPORAL
                            ? "builtin.temporal" : "unfiltered.trace",
                    denoisingFailureReason
            );
            throw fallbackBoundary(denoisingFailureReason, failure);
        }
    }

    @Override
    public synchronized void recordReconstruction(VulkanFeatureFrameContext context) {
        if (closed) throw new IllegalStateException("NVIDIA feature session is closed");
        if (streamlineFeature != null && !reconstructionFailed) {
            try {
                NvidiaStreamlineReconstructionRuntime.record(context, reconstruction, streamlineFeature);
                executionEvidence.recordReconstruction(context.frameSequence(), streamlineFeature);
            } catch (RuntimeException | LinkageError failure) {
                NvidiaFeatureFailurePolicy.Action action = NvidiaFeatureFailurePolicy.reconstruction(
                        reconstruction, streamlineFeature,
                        streamlineFeatures.contains(NvidiaStreamlineRuntime.Feature.NIS)
                );
                if (action == NvidiaFeatureFailurePolicy.Action.FAIL_REQUIRED) throw failure;
                String reason = describeFailure("Streamline " + streamlineFeature + " evaluate", failure);
                if (action == NvidiaFeatureFailurePolicy.Action.SWITCH_TO_NIS) {
                    streamlineFeature = NvidiaStreamlineRuntime.Feature.NIS;
                    streamlineEvaluated = false;
                    reconstructionFallbackCompletionAfter =
                            executionEvidence.latestCommittedSubmission();
                    reconstructionFailureReason = reason + "; next frame switches to Streamline NIS";
                    reconstructionState.recovering("nvidia.streamline.nis", reconstructionFailureReason);
                } else {
                    reconstructionFailed = true;
                    reconstructionFallbackCompletionAfter =
                            executionEvidence.latestCommittedSubmission();
                    reconstructionFailureReason = reason;
                    reconstructionState.recovering(
                            action == NvidiaFeatureFailurePolicy.Action.FALLBACK_TEMPORAL
                                    ? "builtin.temporal" : "native.resolution",
                            reconstructionFailureReason
                    );
                }
                throw fallbackBoundary(reconstructionFailureReason, failure);
            }
        }
    }

    @Override
    public synchronized void recordFrameGeneration(VulkanFeatureFrameContext context) {
        if (closed) throw new IllegalStateException("NVIDIA feature session is closed");
        if (streamlineFeatures.contains(NvidiaStreamlineRuntime.Feature.DLSS_FRAME_GENERATION)
                && !frameGenerationFailed) {
            try {
                NvidiaStreamlineFrameGenerationRuntime.record(context);
            } catch (RuntimeException | LinkageError failure) {
                if (NvidiaFeatureFailurePolicy.frameGeneration(frameGeneration)
                        == NvidiaFeatureFailurePolicy.Action.FAIL_REQUIRED) throw failure;
                markFrameGenerationFallbackPending(
                        describeFailure("DLSS-G/MFG record", failure)
                );
                throw fallbackBoundary(frameGenerationFailureReason, failure);
            }
        }
    }

    @Override
    public synchronized void beginFramePreparation(long frameSequence) {
        if (closed) throw new IllegalStateException("NVIDIA feature session is closed");
        if (latencyExecutionRequested() && lowLatencyBound(streamlineFeatures) && !lowLatencyFailed) {
            try {
                NvidiaStreamlineFrameGenerationRuntime.beginPreparation(frameSequence);
            } catch (RuntimeException | LinkageError failure) {
                throw handleLowLatencyFailure("Reflex/PCL frame preparation", failure);
            }
        }
    }

    @Override
    public synchronized void cancelFramePreparation(long frameSequence) {
        if (closed) throw new IllegalStateException("NVIDIA feature session is closed");
        if (latencyExecutionRequested() && lowLatencyBound(streamlineFeatures) && !lowLatencyFailed) {
            try {
                NvidiaStreamlineFrameGenerationRuntime.cancelPreparation(frameSequence);
            } catch (RuntimeException | LinkageError failure) {
                throw handleLowLatencyFailure("Reflex/PCL preparation cancel", failure);
            }
        }
    }

    @Override
    public synchronized void beginFrameSubmission(long frameSequence) {
        if (closed) throw new IllegalStateException("NVIDIA feature session is closed");
        if (latencyExecutionRequested() && lowLatencyBound(streamlineFeatures) && !lowLatencyFailed) {
            try {
                NvidiaStreamlineFrameGenerationRuntime.beginSubmission(frameSequence);
            } catch (RuntimeException | LinkageError failure) {
                throw handleLowLatencyFailure("Reflex/PCL submission begin", failure);
            }
        }
    }

    @Override
    public synchronized VulkanFeatureSession.InputCompletion awaitFrameInputReuse(long frameSequence) {
        if (closed) throw new IllegalStateException("NVIDIA feature session is closed");
        if (streamlineFeatures.contains(NvidiaStreamlineRuntime.Feature.DLSS_FRAME_GENERATION)
                && !frameGenerationFailed) {
            try {
                if (!pendingInputCompletion.enabled()) {
                    pendingInputCompletion = NvidiaStreamlineFrameGenerationRuntime.awaitInputReuse(frameSequence);
                }
                return pendingInputCompletion;
            } catch (RuntimeException | LinkageError failure) {
                if (NvidiaFeatureFailurePolicy.frameGeneration(frameGeneration)
                        == NvidiaFeatureFailurePolicy.Action.FAIL_REQUIRED) throw failure;
                markFrameGenerationFallbackPending(
                        describeFailure("DLSS-G/MFG input reuse", failure)
                );
                throw new IllegalStateException(
                        frameGenerationFailureReason
                                + "; frame aborted before tagged resources were modified",
                        failure
                );
            }
        }
        return VulkanFeatureSession.InputCompletion.none();
    }

    @Override
    public synchronized void commitFrameInputReuse(long frameSequence) {
        if (closed) throw new IllegalStateException("NVIDIA feature session is closed");
        pendingInputCompletion = VulkanFeatureSession.InputCompletion.none();
    }

    @Override
    public synchronized void endFrameSubmission(long frameSequence) {
        if (closed) throw new IllegalStateException("NVIDIA feature session is closed");
        if (latencyExecutionRequested() && lowLatencyBound(streamlineFeatures) && !lowLatencyFailed) {
            try {
                NvidiaStreamlineFrameGenerationRuntime.endSubmission(frameSequence);
                executionEvidence.recordLowLatencyRenderEnd(frameSequence);
            } catch (RuntimeException | LinkageError failure) {
                throw handleLowLatencyFailure("Reflex/PCL submission end", failure);
            }
        }
    }

    @Override
    public synchronized void commitFrameSubmission(long frameSequence) {
        if (closed) throw new IllegalStateException("NVIDIA feature session is closed");
        executionEvidence.commitSubmission(frameSequence);
    }

    @Override
    public synchronized void observeFrameCompletion(long frameSequence) {
        if (closed) throw new IllegalStateException("NVIDIA feature session is closed");
        NvidiaFeatureExecutionEvidence.SubmissionWork completed =
                executionEvidence.completeSubmission(frameSequence);
        if (completed.denoisingRecorded()
                && frameSequence > denoisingFallbackCompletionAfter) {
            denoisingState.active(
                    "nvidia.nrd", "NRD output completed on the Vulkan queue"
            );
        }
        if (completed.reconstructionFeature() != null
                && frameSequence > reconstructionFallbackCompletionAfter) {
            streamlineEvaluated = true;
            String executionReason = "Streamline " + completed.reconstructionFeature()
                    + " output completed on the Vulkan queue";
            if (completed.reconstructionFeature() == NvidiaStreamlineRuntime.Feature.NIS) {
                reconstructionState.fallback(
                        implementation(completed.reconstructionFeature()), executionReason
                );
            } else {
                reconstructionState.active(
                        implementation(completed.reconstructionFeature()), executionReason
                );
            }
        }
        if (denoisingFailed
                && denoisingState.snapshot().status()
                == VulkanFeatureRuntimeState.Status.RECOVERING
                && frameSequence > denoisingFallbackCompletionAfter) {
            denoisingState.fallback(
                    denoising.builtInTemporalFallback()
                            ? "builtin.temporal" : "unfiltered.trace",
                    denoisingFailureReason + "; replacement frame completed on the Vulkan queue"
            );
            executionEvidence.completeFallback(
                    NvidiaFeatureExecutionEvidence.Lane.DENOISING_FALLBACK,
                    frameSequence
            );
        }
        if (reconstructionFailed
                && reconstructionState.snapshot().status()
                == VulkanFeatureRuntimeState.Status.RECOVERING
                && frameSequence > reconstructionFallbackCompletionAfter) {
            reconstructionState.fallback(
                    reconstruction.fallback()
                            == FrameReconstructionOptions.Fallback.BUILT_IN_TEMPORAL
                            ? "builtin.temporal" : "native.resolution",
                    reconstructionFailureReason
                            + "; replacement frame completed on the Vulkan queue"
            );
            executionEvidence.completeFallback(
                    NvidiaFeatureExecutionEvidence.Lane.RECONSTRUCTION_FALLBACK,
                    frameSequence
            );
        }
    }

    @Override
    public synchronized void discardFrameSubmission(long frameSequence) {
        if (closed) throw new IllegalStateException("NVIDIA feature session is closed");
        executionEvidence.discardSubmission(frameSequence);
    }

    @Override
    public synchronized void observePresentation(long frameSequence, boolean succeeded) {
        if (closed) throw new IllegalStateException("NVIDIA feature session is closed");
        absorbPresentationFailure();
        executionEvidence.observePresent(frameSequence, succeeded);
        if (!succeeded) return;
        if (latencyExecutionRequested() && !lowLatencyFailed
                && executionEvidence.lowLatencyPresentCommitted()) {
            lowLatencyState.active(
                    "nvidia.streamline.reflex-pcl",
                    "Reflex/PCL markers completed on a successfully presented frame"
            );
        }
        if (frameGenerationFailed
                && frameSequence > frameGenerationFallbackPresentAfter
                && frameGenerationState.snapshot().status()
                == VulkanFeatureRuntimeState.Status.RECOVERING) {
            frameGenerationState.fallback(
                    "native.presentation",
                    frameGenerationFailureReason + "; a later native frame completed presentation"
            );
            executionEvidence.completeFallback(
                    NvidiaFeatureExecutionEvidence.Lane.FRAME_GENERATION_FALLBACK,
                    frameSequence
            );
        }
        if (lowLatencyFailed
                && lowLatencyState.snapshot().status()
                == VulkanFeatureRuntimeState.Status.RECOVERING) {
            lowLatencyState.fallback(
                    "native.scheduling",
                    lowLatencyFailureReason + "; replacement frame completed presentation"
            );
            executionEvidence.completeFallback(
                    NvidiaFeatureExecutionEvidence.Lane.LOW_LATENCY_FALLBACK,
                    frameSequence
            );
        }
    }

    private RuntimeException handleLowLatencyFailure(String operation, Throwable failure) {
        lowLatencyFailed = true;
        lowLatencyFailureReason = describeFailure(operation, failure);
        lowLatencyState.recovering("native.scheduling", lowLatencyFailureReason);
        if (frameGeneration.preference().requested()) {
            markFrameGenerationFallbackPending(
                    lowLatencyFailureReason + "; frame generation requires Reflex/PCL"
            );
        }
        if (lowLatency.preference() == RendererFeaturePreference.REQUIRED
                || frameGeneration.preference() == RendererFeaturePreference.REQUIRED) {
            if (failure instanceof RuntimeException runtimeFailure) return runtimeFailure;
            throw (LinkageError) failure;
        }
        return fallbackBoundary(lowLatencyFailureReason, failure);
    }

    private boolean latencyExecutionRequested() {
        return lowLatency.preference().requested() || frameGeneration.preference().requested();
    }

    private void markFrameGenerationFallbackPending(String reason) {
        markFrameGenerationFallbackPending(reason, executionEvidence.latestSuccessfulPresent());
    }

    private void markFrameGenerationFallbackPending(String reason, long fallbackPresentAfter) {
        if (!frameGenerationFailed) {
            frameGenerationFallbackPresentAfter = fallbackPresentAfter;
        }
        frameGenerationFailed = true;
        frameGenerationFailureReason = Objects.requireNonNull(reason, "reason");
        frameGenerationState.recovering("native.presentation", frameGenerationFailureReason);
        if (swapchainInterceptor != null) swapchainInterceptor.disableGeneration();
    }

    @Override
    public synchronized Optional<VulkanSwapchainInterceptor> swapchainInterceptor() {
        if (closed) throw new IllegalStateException("NVIDIA feature session is closed");
        return Optional.ofNullable(swapchainInterceptor);
    }

    @Override
    public synchronized Optional<VulkanAccelerationStructureMemoryOptimizer>
    accelerationStructureMemoryOptimizer() {
        if (closed) throw new IllegalStateException("NVIDIA feature session is closed");
        return rtxmu == null || rtxmu.disabled() ? Optional.empty() : Optional.of(rtxmu);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        denoisingState.close();
        reconstructionState.close();
        frameGenerationState.close();
        lowLatencyState.close();
        memoryOptimizationState.close();
        Throwable failure = null;
        // Drain the borrowed Vulkan device before NRD/NRI or Streamline releases descriptors,
        // allocators, and plugin state referenced by queued command buffers.
        try {
            top.ceroxe.rt.renderer.rt.device.VulkanFailures.check(
                    VK10.vkDeviceWaitIdle(device),
                    "wait for NVIDIA feature device idle"
            );
        } catch (RuntimeException | LinkageError | OutOfMemoryError waitFailure) {
            failure = waitFailure;
        }
        // Streamline's manual-hooking contract requires slShutdown while the Vulkan instance
        // and device-related integrations are still alive. The queue is idle above, so SL can
        // release its viewport/plugin state before NRD tears down its NRI device wrapper.
        try {
            if (streamlineSession != null) streamlineSession.close();
        } catch (RuntimeException | LinkageError | OutOfMemoryError closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        try {
            nativeSessions.close();
        } catch (RuntimeException | LinkageError | OutOfMemoryError closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
        if (failure instanceof Error error) throw error;
    }
}
