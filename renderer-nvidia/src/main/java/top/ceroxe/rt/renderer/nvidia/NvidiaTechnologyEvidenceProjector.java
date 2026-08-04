package top.ceroxe.rt.renderer.nvidia;

import top.ceroxe.rt.renderer.api.DenoisingOptions;
import top.ceroxe.rt.renderer.api.FrameReconstructionOptions;
import top.ceroxe.rt.renderer.api.FrameGenerationEvidence;
import top.ceroxe.rt.renderer.api.FrameGenerationOptions;
import top.ceroxe.rt.renderer.api.LowLatencyOptions;
import top.ceroxe.rt.renderer.api.RayTracingOptimizationOptions;
import top.ceroxe.rt.renderer.api.RendererFeaturePreference;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Entry;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Feature;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Status;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Technology;
import top.ceroxe.rt.renderer.api.TechnologyExecutionEvidence;

import java.util.Map;
import java.util.Objects;

/** Projects NVIDIA session state into immutable, evidence-backed technology snapshots. */
final class NvidiaTechnologyEvidenceProjector {
    private NvidiaTechnologyEvidenceProjector() {
    }

    static TechnologyExecutionEvidence project(
            RenderingFeatureCapabilities capabilities,
            FrameReconstructionOptions reconstruction,
            FrameGenerationOptions frameGeneration,
            DenoisingOptions denoising,
            LowLatencyOptions lowLatency,
            RayTracingOptimizationOptions optimizations,
            NvidiaFeatureExecutionEvidence execution,
            FrameGenerationEvidence frameGenerationEvidence,
            long rtxmuCompletedBuilds
    ) {
        RenderingFeatureCapabilities checkedCapabilities = Objects.requireNonNull(capabilities, "capabilities");
        NvidiaFeatureExecutionEvidence checkedExecution = Objects.requireNonNull(execution, "execution");
        TechnologyExecutionEvidence.Builder result = TechnologyExecutionEvidence.builder();
        projectReconstruction(result, checkedCapabilities, reconstruction, checkedExecution);
        projectFrameGeneration(
                result, checkedCapabilities, frameGeneration, checkedExecution,
                Objects.requireNonNull(frameGenerationEvidence, "frameGenerationEvidence")
        );
        projectDenoising(result, checkedCapabilities, denoising, checkedExecution);
        projectLowLatency(
                result, checkedCapabilities, lowLatency, frameGeneration, checkedExecution
        );
        projectMemoryOptimization(result, checkedCapabilities, optimizations, rtxmuCompletedBuilds);
        return result.build();
    }

    private static void projectFrameGeneration(
            TechnologyExecutionEvidence.Builder result,
            RenderingFeatureCapabilities capabilities,
            FrameGenerationOptions options,
            NvidiaFeatureExecutionEvidence execution,
            FrameGenerationEvidence evidence
    ) {
        if (!options.preference().requested()) return;
        boolean multiFrame = NvidiaTechnologyCapabilities.multiFrameRequested(options);
        Technology selected = multiFrame
                ? Technology.MULTI_FRAME_GENERATION : Technology.FRAME_GENERATION;
        Entry feature = capabilities.feature(Feature.FRAME_GENERATION);
        boolean fallback = feature.status() == Status.FALLBACK_PENDING
                || feature.status() == Status.FALLBACK;
        NvidiaFeatureExecutionEvidence.Activity activity = fallback
                ? execution.activity(NvidiaFeatureExecutionEvidence.Lane.FRAME_GENERATION_FALLBACK)
                : generationActivity(evidence, multiFrame);
        Map<String, String> parameters = Map.of(
                "requested-multiplier", Integer.toString(evidence.requestedPresentationMultiplier()),
                "configured-multiplier", Integer.toString(evidence.configuredPresentationMultiplier())
        );
        result.technology(selected, projectEntry(
                options.preference(),
                multiFrame ? "nvidia.streamline.dlss-g.mfg" : "nvidia.streamline.dlss-g",
                fallback ? feature : capabilities.technology(selected),
                activity,
                true,
                true,
                parameters,
                feature.status() == Status.FALLBACK_PENDING,
                feature.status() == Status.FALLBACK,
                "FRAME_GENERATION_FALLBACK"
        ));
        if (multiFrame) {
            result.technology(Technology.FRAME_GENERATION, projectEntry(
                    options.preference(),
                    "nvidia.streamline.dlss-g",
                    capabilities.technology(Technology.FRAME_GENERATION),
                    NvidiaFeatureExecutionEvidence.Activity.empty(),
                    false,
                    false,
                    Map.of(),
                    false,
                    false,
                    "FRAME_GENERATION_FALLBACK"
            ));
        }
    }

    private static NvidiaFeatureExecutionEvidence.Activity generationActivity(
            FrameGenerationEvidence evidence,
            boolean multiFrame
    ) {
        if (!evidence.reported() || evidence.proxyPresentCalls() == 0L) {
            return NvidiaFeatureExecutionEvidence.Activity.empty();
        }
        boolean outputProven = evidence.active()
                && (!multiFrame || evidence.maximumGeneratedFramesObservedPerSample() > 1);
        long completed = outputProven
                ? Math.min(evidence.proxyPresentCalls(), evidence.stateSamples()) : 0L;
        long generated = outputProven ? evidence.generatedFramesActuallyPresented() : 0L;
        return new NvidiaFeatureExecutionEvidence.Activity(
                evidence.proxyPresentCalls(),
                evidence.proxyPresentCalls(),
                completed,
                generated,
                evidence.firstProxyPresentSequence().orElseThrow(),
                evidence.lastProxyPresentSequence().orElseThrow(),
                outputProven ? evidence.lastGeneratedObservationSequence().orElseThrow() : -1L,
                TechnologyExecutionEvidence.SequenceDomain.RENDERER_FRAME,
                evidence.resetEpoch()
        );
    }

    private static void projectReconstruction(
            TechnologyExecutionEvidence.Builder result,
            RenderingFeatureCapabilities capabilities,
            FrameReconstructionOptions options,
            NvidiaFeatureExecutionEvidence execution
    ) {
        if (!options.preference().requested()) return;
        Technology primary = NvidiaTechnologyCapabilities.reconstructionTechnology(options);
        Entry feature = capabilities.feature(Feature.FRAME_RECONSTRUCTION);
        Entry primaryTechnology = capabilities.technology(primary);
        boolean fallback = feature.status() == Status.FALLBACK_PENDING
                || feature.status() == Status.FALLBACK;
        boolean spatialSelected = feature.implementation().equals("nvidia.streamline.nis");
        NvidiaFeatureExecutionEvidence.Lane lane = spatialSelected
                ? NvidiaFeatureExecutionEvidence.Lane.NIS
                : NvidiaFeatureExecutionEvidence.Lane.DLSS;
        result.technology(primary, projectEntry(
                options.preference(),
                NvidiaTechnologyCapabilities.implementation(primary),
                fallback ? feature : primaryTechnology,
                execution.activity(lane),
                !fallback,
                false,
                Map.of("mode", options.mode().name(), "quality", options.quality().name()),
                feature.status() == Status.FALLBACK_PENDING,
                feature.status() == Status.FALLBACK,
                "RECONSTRUCTION_FALLBACK"
        ));
        if (options.fallback() == FrameReconstructionOptions.Fallback.SPATIAL
                && primary != Technology.SPATIAL_UPSCALING) {
            Entry spatial = capabilities.technology(Technology.SPATIAL_UPSCALING);
            result.technology(Technology.SPATIAL_UPSCALING, projectEntry(
                    options.preference(),
                    "nvidia.streamline.nis",
                    spatialSelected && (feature.status() == Status.FALLBACK_PENDING
                            || feature.status() == Status.FALLBACK) ? feature : spatial,
                    execution.activity(NvidiaFeatureExecutionEvidence.Lane.NIS),
                    spatialSelected,
                    false,
                    Map.of("fallback", "spatial"),
                    spatialSelected && feature.status() == Status.FALLBACK_PENDING,
                    spatialSelected && feature.status() == Status.FALLBACK,
                    "RECONSTRUCTION_FALLBACK"
            ));
        }
    }

    private static void projectDenoising(
            TechnologyExecutionEvidence.Builder result,
            RenderingFeatureCapabilities capabilities,
            DenoisingOptions options,
            NvidiaFeatureExecutionEvidence execution
    ) {
        if (!options.preference().requested()) return;
        Entry feature = capabilities.feature(Feature.DENOISING);
        Entry technology = capabilities.technology(Technology.RAY_TRACING_DENOISING);
        boolean fallback = feature.status() == Status.FALLBACK_PENDING
                || feature.status() == Status.FALLBACK;
        result.technology(Technology.RAY_TRACING_DENOISING, projectEntry(
                options.preference(),
                "nvidia.nrd",
                fallback ? feature : technology,
                fallback
                        ? execution.activity(NvidiaFeatureExecutionEvidence.Lane.DENOISING_FALLBACK)
                        : execution.activity(NvidiaFeatureExecutionEvidence.Lane.DENOISING),
                !fallback,
                false,
                Map.of("strategy", options.strategy().name()),
                feature.status() == Status.FALLBACK_PENDING,
                feature.status() == Status.FALLBACK,
                "DENOISING_FALLBACK"
        ));
    }

    private static void projectLowLatency(
            TechnologyExecutionEvidence.Builder result,
            RenderingFeatureCapabilities capabilities,
            LowLatencyOptions options,
            FrameGenerationOptions frameGeneration,
            NvidiaFeatureExecutionEvidence execution
    ) {
        RendererFeaturePreference preference = options.preference().requested()
                ? options.preference() : frameGeneration.preference();
        if (!preference.requested()) return;
        Entry feature = capabilities.feature(Feature.LOW_LATENCY);
        Entry technology = capabilities.technology(Technology.LOW_LATENCY_MARKERS);
        boolean fallback = feature.status() == Status.FALLBACK_PENDING
                || feature.status() == Status.FALLBACK;
        result.technology(Technology.LOW_LATENCY_MARKERS, projectEntry(
                preference,
                "nvidia.streamline.reflex-pcl",
                fallback ? feature : technology,
                fallback
                        ? execution.activity(NvidiaFeatureExecutionEvidence.Lane.LOW_LATENCY_FALLBACK)
                        : execution.activity(NvidiaFeatureExecutionEvidence.Lane.LOW_LATENCY),
                !fallback,
                false,
                Map.of("present-proxy", "required"),
                feature.status() == Status.FALLBACK_PENDING,
                feature.status() == Status.FALLBACK,
                "LOW_LATENCY_FALLBACK"
        ));
    }

    private static void projectMemoryOptimization(
            TechnologyExecutionEvidence.Builder result,
            RenderingFeatureCapabilities capabilities,
            RayTracingOptimizationOptions options,
            long completedBuilds
    ) {
        if (!options.memoryOptimization().requested()) return;
        Entry technology = capabilities.technology(
                Technology.ACCELERATION_STRUCTURE_MEMORY_OPTIMIZATION
        );
        NvidiaFeatureExecutionEvidence.Activity activity = completedBuilds == 0L
                ? NvidiaFeatureExecutionEvidence.Activity.empty()
                : new NvidiaFeatureExecutionEvidence.Activity(
                        completedBuilds, completedBuilds, completedBuilds, completedBuilds,
                        1L, completedBuilds, completedBuilds,
                        TechnologyExecutionEvidence.SequenceDomain.PROVIDER_WORK, 0L
                );
        result.technology(
                Technology.ACCELERATION_STRUCTURE_MEMORY_OPTIMIZATION,
                projectEntry(
                        options.memoryOptimization(),
                        "nvidia.rtx-memory-utility",
                        technology,
                        activity,
                        true,
                        false,
                        Map.of(
                                "lifetime", "acceleration-structure",
                                "sequence-domain", "provider-build-ordinal"
                        ),
                        false,
                        false,
                        "RTXMU_FALLBACK"
                )
        );
    }

    private static TechnologyExecutionEvidence.Entry projectEntry(
            RendererFeaturePreference preference,
            String requestedImplementation,
            Entry capability,
            NvidiaFeatureExecutionEvidence.Activity activity,
            boolean selected,
            boolean outputRequired,
            Map<String, String> parameters,
            boolean fallbackPending,
            boolean fallbackActive,
            String fallbackCode
    ) {
        TechnologyExecutionEvidence.Entry.Builder builder = TechnologyExecutionEvidence.Entry.builder()
                .requestPreference(preference)
                .requestedImplementation(requestedImplementation);
        Status status = capability.status();
        String implementation = capability.implementation();
        if (status == Status.DISABLED) return TechnologyExecutionEvidence.Entry.disabled();
        if (notSupported(status)) {
            return builder.health(TechnologyExecutionEvidence.Health.UNAVAILABLE)
                    .errorCode("NOT_SUPPORTED")
                    .build();
        }
        if (status == Status.BLOCKED) {
            return builder.health(TechnologyExecutionEvidence.Health.FAILED)
                    .errorCode("RUNTIME_BLOCKED")
                    .build();
        }
        String negotiated = !"none".equals(implementation)
                ? implementation : requestedImplementation;
        builder.negotiatedImplementation(negotiated);
        if (selected || fallbackPending || fallbackActive) {
            builder.configuredImplementation(negotiated).configuredParameters(parameters);
        }
        applyActivity(builder, activity);
        if (fallbackPending) {
            return builder.fallbackCode(fallbackCode)
                    .health(TechnologyExecutionEvidence.Health.FALLBACK_PENDING)
                    .build();
        }
        if (fallbackActive && activity.gpuCompleted() > 0L) {
            return builder.fallbackCode(fallbackCode)
                    .health(TechnologyExecutionEvidence.Health.DEGRADED)
                    .build();
        }
        if (fallbackActive) {
            return builder.fallbackCode(fallbackCode)
                    .health(TechnologyExecutionEvidence.Health.FALLBACK_PENDING)
                    .build();
        }
        if (selected) {
            TechnologyExecutionEvidence.Health health;
            if (activity.gpuCompleted() > 0L && (!outputRequired || activity.output() > 0L)) {
                health = TechnologyExecutionEvidence.Health.ACTIVE;
            } else if (activity.recorded() > 0L) {
                health = TechnologyExecutionEvidence.Health.SUBMITTED;
            } else {
                health = TechnologyExecutionEvidence.Health.READY;
            }
            return builder.health(health)
                    .build();
        }
        return builder.health(TechnologyExecutionEvidence.Health.NEGOTIATED).build();
    }

    private static void applyActivity(
            TechnologyExecutionEvidence.Entry.Builder builder,
            NvidiaFeatureExecutionEvidence.Activity activity
    ) {
        if (activity.recorded() == 0L) return;
        builder.recordedCount(activity.recorded())
                .queueAcceptedCount(activity.queueAccepted())
                .gpuCompletedCount(activity.gpuCompleted())
                .outputCount(activity.output())
                .sequenceRange(activity.firstSequence(), activity.lastSequence())
                .sequenceDomain(activity.sequenceDomain())
                .resetEpoch(activity.resetEpoch());
        if (activity.output() > 0L) builder.lastOutputSequence(activity.lastOutputSequence());
    }

    private static boolean notSupported(Status status) {
        return status == Status.NOT_SUPPORTED || legacyUnavailable(status);
    }

    @SuppressWarnings("deprecation")
    private static boolean legacyUnavailable(Status status) {
        return status == Status.UNAVAILABLE;
    }
}
