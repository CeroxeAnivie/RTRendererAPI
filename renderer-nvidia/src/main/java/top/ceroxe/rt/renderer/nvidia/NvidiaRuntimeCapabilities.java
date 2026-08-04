package top.ceroxe.rt.renderer.nvidia;

import top.ceroxe.rt.renderer.api.DenoisingOptions;
import top.ceroxe.rt.renderer.api.FrameGenerationOptions;
import top.ceroxe.rt.renderer.api.FrameReconstructionOptions;
import top.ceroxe.rt.renderer.api.LowLatencyOptions;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Entry;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Feature;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Status;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Technology;
import top.ceroxe.rt.renderer.feature.VulkanFeatureRuntimeState;

import java.util.Objects;

/** Projects one atomic NVIDIA session observation into the public capability snapshot. */
final class NvidiaRuntimeCapabilities {
    private NvidiaRuntimeCapabilities() {
    }

    static RenderingFeatureCapabilities project(
            RenderingFeatureCapabilities baseline,
            Denoising denoising,
            Reconstruction reconstruction,
            FrameGeneration frameGeneration,
            LowLatency lowLatency,
            MemoryOptimization memoryOptimization
    ) {
        RenderingFeatureCapabilities.Builder target = RenderingFeatureCapabilities.builder();
        RenderingFeatureCapabilities checked = Objects.requireNonNull(baseline, "baseline");
        checked.features().forEach(target::feature);
        NvidiaTechnologyCapabilities.copyTechnologies(checked, target);
        applyDenoising(target, Objects.requireNonNull(denoising, "denoising"));
        applyReconstruction(target, Objects.requireNonNull(reconstruction, "reconstruction"));
        applyFrameGeneration(target, Objects.requireNonNull(frameGeneration, "frameGeneration"));
        applyLowLatency(target, Objects.requireNonNull(lowLatency, "lowLatency"));
        applyMemoryOptimization(
                target,
                Objects.requireNonNull(memoryOptimization, "memoryOptimization")
        );
        return target.build();
    }

    static RenderingFeatureCapabilities project(
            RenderingFeatureCapabilities baseline,
            Denoising denoising,
            Reconstruction reconstruction,
            FrameGeneration frameGeneration,
            boolean memoryOptimizationExecuted
    ) {
        return project(
                baseline,
                denoising,
                reconstruction,
                frameGeneration,
                new LowLatency(LowLatencyOptions.disabled(), false, false, false, null),
                MemoryOptimization.compatibility(memoryOptimizationExecuted)
        );
    }

    private static void applyMemoryOptimization(
            RenderingFeatureCapabilities.Builder target,
            MemoryOptimization observation
    ) {
        if (!observation.requested()) return;
        if (!observation.featureBound()) {
            Entry baseline = target.build().feature(Feature.MEMORY_OPTIMIZATION);
            String reason = failureReason(
                    observation.openFailureReason(),
                    baseline.reason().isBlank() ? "RTXMU device session is unavailable" : baseline.reason()
            );
            target.feature(Feature.MEMORY_OPTIMIZATION, Entry.of(
                    baseline.status() == Status.FALLBACK
                            || baseline.status() == Status.FALLBACK_PENDING
                            ? baseline.status() : Status.BLOCKED,
                    baseline.status() == Status.FALLBACK
                            || baseline.status() == Status.FALLBACK_PENDING
                            ? baseline.implementation() : "nvidia.rtx-memory-utility",
                    reason
            ));
            target.technology(Technology.ACCELERATION_STRUCTURE_MEMORY_OPTIMIZATION, Entry.of(
                    Status.BLOCKED, "nvidia.rtx-memory-utility", reason
            ));
            return;
        }

        Entry runtime = runtimeEntry(observation.runtimeState());
        target.feature(Feature.MEMORY_OPTIMIZATION, runtime);
        if (runtime.status() == Status.FALLBACK) {
            target.technology(Technology.ACCELERATION_STRUCTURE_MEMORY_OPTIMIZATION, Entry.of(
                    Status.BLOCKED,
                    "nvidia.rtx-memory-utility",
                    runtime.reason()
            ));
        } else {
            target.technology(Technology.ACCELERATION_STRUCTURE_MEMORY_OPTIMIZATION, runtime);
        }
    }

    private static void applyDenoising(
            RenderingFeatureCapabilities.Builder target,
            Denoising observation
    ) {
        if (observation.failed()) {
            Entry fallback = runtimeEntry(observation.runtimeState());
            target.feature(Feature.DENOISING, fallback);
            target.technology(Technology.RAY_TRACING_DENOISING, Entry.of(
                    Status.BLOCKED,
                    "nvidia.nrd",
                    failureReason(observation.failureReason(), "NRD failed at runtime")
            ));
        } else if (observation.nativeEnabled()) {
            Entry runtime = runtimeEntry(observation.runtimeState());
            target.feature(Feature.DENOISING, runtime);
            target.technology(Technology.RAY_TRACING_DENOISING, runtime);
        }
    }

    private static void applyReconstruction(
            RenderingFeatureCapabilities.Builder target,
            Reconstruction observation
    ) {
        Technology requested = NvidiaTechnologyCapabilities.reconstructionTechnology(observation.options());
        if (observation.failed()) {
            target.feature(Feature.FRAME_RECONSTRUCTION, runtimeEntry(observation.runtimeState()));
            target.technology(requested, Entry.of(
                    Status.BLOCKED,
                    NvidiaTechnologyCapabilities.implementation(requested),
                    failureReason(observation.failureReason(), "reconstruction failed at runtime")
            ));
            return;
        }
        if (observation.executedFeature() == null || !observation.evaluated()) return;

        NvidiaStreamlineRuntime.Feature executedFeature = observation.executedFeature();
        String reason = "Streamline " + executedFeature
                + " evaluate succeeded on the active Vulkan device";
        Technology executed = executedFeature == NvidiaStreamlineRuntime.Feature.NIS
                ? Technology.SPATIAL_UPSCALING : requested;
        target.feature(Feature.FRAME_RECONSTRUCTION, Entry.of(
                executed == requested ? Status.ACTIVE : Status.FALLBACK,
                implementation(executedFeature),
                reason
        ));
        target.technology(executed, Entry.of(
                Status.ACTIVE,
                NvidiaTechnologyCapabilities.implementation(executed),
                reason
        ));
        if (executed != requested && observation.failureReason() != null) {
            target.technology(requested, Entry.of(
                    Status.BLOCKED,
                    NvidiaTechnologyCapabilities.implementation(requested),
                    observation.failureReason()
            ));
        }
    }

    private static void applyFrameGeneration(
            RenderingFeatureCapabilities.Builder target,
            FrameGeneration observation
    ) {
        if (!observation.featureBound()) return;
        NvidiaStreamlineFrameGenerationRuntime.Stats stats = observation.stats();
        if (!observation.failed() && observation.enabled() && stats.active()) {
            String evidence = "Streamline configured " + (stats.configuredGeneratedFrames() + 1)
                    + "x; presented " + stats.generatedFramesActuallyPresented()
                    + " generated frames across " + stats.proxyPresentCalls() + " proxy presents"
                    + "; request misses " + stats.generationRequestMisses()
                    + "; last present delivered " + stats.lastFramesActuallyPresented() + " total frames";
            target.feature(Feature.FRAME_GENERATION, Entry.of(
                    Status.ACTIVE,
                    "nvidia.streamline.dlss-g",
                    evidence
            ));
            if (NvidiaTechnologyCapabilities.multiFrameRequested(observation.options())) {
                // MFG owns the mutually exclusive generated-frame mode at 3x/4x. Keep the
                // generic feature active for consumers, but never advertise standard 2x FG as
                // active in the technology projection at the same time.
                target.technology(Technology.FRAME_GENERATION, Entry.of(
                        Status.AVAILABLE,
                        "nvidia.streamline.dlss-g",
                        "MFG selected; standard DLSS FG is mutually exclusive"
                ));
            } else {
                target.technology(Technology.FRAME_GENERATION, Entry.of(
                        Status.ACTIVE, "nvidia.streamline.dlss-g", evidence
                ));
            }
            target.technology(Technology.LOW_LATENCY_MARKERS, Entry.of(
                    Status.ACTIVE,
                    "nvidia.streamline.reflex-pcl",
                    "Reflex/PCL frame markers participated in active generated presentation"
            ));
            if (NvidiaTechnologyCapabilities.multiFrameRequested(observation.options())) {
                boolean multiFrameActive = stats.maxGeneratedFramesInSample() > 1;
                boolean multiFrameArmed = stats.configuredGeneratedFrames() > 1;
                target.technology(Technology.MULTI_FRAME_GENERATION, Entry.of(
                        multiFrameActive ? Status.ACTIVE
                                : multiFrameArmed ? Status.AVAILABLE : Status.NOT_SUPPORTED,
                        multiFrameActive || multiFrameArmed ? "nvidia.streamline.dlss-g.mfg" : "none",
                        multiFrameActive
                                ? evidence
                                : multiFrameArmed
                                ? "Streamline accepted multi-frame generation; awaiting an observed multi-frame batch"
                                : "the active adapter limited generation to the 2x frame-generation path"
                ));
            }
        } else if (observation.failed()) {
            String reason = failureReason(
                    observation.failureReason(),
                    "Streamline frame generation failed at the presentation boundary"
            );
            target.feature(Feature.FRAME_GENERATION, Entry.of(
                    observation.nativeFallbackPresented() ? Status.FALLBACK : Status.FALLBACK_PENDING,
                    "native.presentation",
                    observation.nativeFallbackPresented()
                            ? reason + "; a later native frame completed presentation"
                            : reason + "; awaiting a successful native present"
            ));
            target.technology(Technology.FRAME_GENERATION, Entry.of(
                    Status.BLOCKED, "nvidia.streamline.dlss-g", reason
            ));
            target.technology(Technology.LOW_LATENCY_MARKERS, Entry.of(
                    Status.BLOCKED, "nvidia.streamline.reflex-pcl", reason
            ));
            if (NvidiaTechnologyCapabilities.multiFrameRequested(observation.options())) {
                target.technology(Technology.MULTI_FRAME_GENERATION, Entry.of(
                        Status.BLOCKED, "nvidia.streamline.dlss-g.mfg", reason
                ));
            }
        } else {
            String evidence = "Streamline requested " + (stats.lastRequestedGeneratedFrames() + 1)
                    + "x; configured " + (stats.configuredGeneratedFrames() + 1)
                    + "x; proxy presents " + stats.proxyPresentCalls()
                    + "; delivery samples " + stats.stateSamples()
                    + "; generated frames " + stats.generatedFramesActuallyPresented()
                    + "; request misses " + stats.generationRequestMisses()
                    + "; state query failures " + stats.stateQueryFailures()
                    + "; native status " + stats.status();
            target.feature(Feature.FRAME_GENERATION, Entry.of(
                    Status.AVAILABLE, "nvidia.streamline.dlss-g", evidence
            ));
            if (NvidiaTechnologyCapabilities.multiFrameRequested(observation.options())) {
                target.technology(Technology.MULTI_FRAME_GENERATION, Entry.of(
                        Status.AVAILABLE, "nvidia.streamline.dlss-g.mfg", evidence
                ));
            } else {
                target.technology(Technology.FRAME_GENERATION, Entry.of(
                        Status.AVAILABLE, "nvidia.streamline.dlss-g", evidence
                ));
            }
        }
    }

    private static void applyLowLatency(
            RenderingFeatureCapabilities.Builder target,
            LowLatency observation
    ) {
        if (!observation.options().preference().requested()) return;
        if (observation.failed()) {
            String reason = failureReason(
                    observation.failureReason(),
                    "low-latency provider failed at a frame boundary"
            );
            target.feature(Feature.LOW_LATENCY, Entry.of(
                    observation.nativeFallbackCommitted()
                            ? Status.FALLBACK : Status.FALLBACK_PENDING,
                    "native.scheduling",
                    observation.nativeFallbackCommitted()
                            ? reason + "; replacement frame accepted by the Vulkan queue"
                            : reason + "; awaiting replacement frame submission"
            ));
            target.technology(Technology.LOW_LATENCY_MARKERS, Entry.of(
                    Status.BLOCKED, "nvidia.streamline.reflex-pcl", reason
            ));
        } else if (observation.featureBound() && observation.executed()) {
             Entry active = Entry.of(
                    Status.ACTIVE,
                    "nvidia.streamline.reflex-pcl",
                    "Reflex sleep and Simulation/Render/Present PCL markers completed for a "
                            + "successfully presented frame"
            );
            target.feature(Feature.LOW_LATENCY, active);
            target.technology(Technology.LOW_LATENCY_MARKERS, active);
        }
    }

    private static Entry runtimeEntry(VulkanFeatureRuntimeState.Snapshot snapshot) {
        VulkanFeatureRuntimeState.Snapshot checked = Objects.requireNonNull(snapshot, "snapshot");
        Status status = switch (checked.status()) {
            case AVAILABLE -> Status.AVAILABLE;
            case RECOVERING -> Status.FALLBACK_PENDING;
            case ACTIVE -> Status.ACTIVE;
            case FALLBACK -> Status.FALLBACK;
            case UNAVAILABLE, CLOSED -> Status.BLOCKED;
        };
        return Entry.of(status, checked.implementation(), checked.reason());
    }

    private static String implementation(NvidiaStreamlineRuntime.Feature feature) {
        return switch (feature) {
            case DLSS -> "nvidia.streamline.dlss";
            case NIS -> "nvidia.streamline.nis";
            default -> throw new IllegalArgumentException("not a reconstruction feature: " + feature);
        };
    }

    private static String failureReason(String reason, String fallback) {
        return reason == null || reason.isBlank() ? fallback : reason;
    }

    record Denoising(
            DenoisingOptions options,
            boolean nativeEnabled,
            boolean failed,
            String failureReason,
            VulkanFeatureRuntimeState.Snapshot runtimeState
    ) {
        Denoising {
            Objects.requireNonNull(options, "options");
            Objects.requireNonNull(runtimeState, "runtimeState");
        }
    }

    record Reconstruction(
            FrameReconstructionOptions options,
            NvidiaStreamlineRuntime.Feature executedFeature,
            boolean evaluated,
            boolean failed,
            String failureReason,
            VulkanFeatureRuntimeState.Snapshot runtimeState
    ) {
        Reconstruction {
            Objects.requireNonNull(options, "options");
            Objects.requireNonNull(runtimeState, "runtimeState");
            if (executedFeature != null
                    && executedFeature != NvidiaStreamlineRuntime.Feature.DLSS
                    && executedFeature != NvidiaStreamlineRuntime.Feature.NIS) {
                throw new IllegalArgumentException("invalid reconstruction feature: " + executedFeature);
            }
        }

        Reconstruction(
                FrameReconstructionOptions options,
                NvidiaStreamlineRuntime.Feature executedFeature,
                boolean evaluated,
                boolean failed,
                String failureReason
        ) {
            this(
                    options,
                    executedFeature,
                    evaluated,
                    failed,
                    failureReason,
                    new VulkanFeatureRuntimeState.Snapshot(
                            failed
                                    ? VulkanFeatureRuntimeState.Status.RECOVERING
                                    : evaluated
                                    ? VulkanFeatureRuntimeState.Status.ACTIVE
                                    : VulkanFeatureRuntimeState.Status.AVAILABLE,
                            executedFeature == null ? "none" : implementation(executedFeature),
                            failureReason
                    )
            );
        }
    }

    record FrameGeneration(
            FrameGenerationOptions options,
            boolean featureBound,
            boolean enabled,
            boolean failed,
            String failureReason,
            NvidiaStreamlineFrameGenerationRuntime.Stats stats,
            boolean nativeFallbackPresented
    ) {
        FrameGeneration {
            Objects.requireNonNull(options, "options");
            Objects.requireNonNull(stats, "stats");
        }

        FrameGeneration(
                FrameGenerationOptions options,
                boolean featureBound,
                boolean enabled,
                boolean failed,
                String failureReason,
                NvidiaStreamlineFrameGenerationRuntime.Stats stats
        ) {
            this(options, featureBound, enabled, failed, failureReason, stats, false);
        }
    }

    record LowLatency(
            LowLatencyOptions options,
            boolean featureBound,
            boolean executed,
            boolean failed,
            String failureReason,
            boolean nativeFallbackCommitted
    ) {
        LowLatency {
            Objects.requireNonNull(options, "options");
            if (executed && (!featureBound || failed)) {
                throw new IllegalArgumentException(
                        "executed low-latency markers require a healthy bound feature"
                );
            }
        }

        LowLatency(
                LowLatencyOptions options,
                boolean featureBound,
                boolean executed,
                boolean failed,
                String failureReason
        ) {
            this(options, featureBound, executed, failed, failureReason, false);
        }
    }

    record MemoryOptimization(
            boolean requested,
            boolean featureBound,
            VulkanFeatureRuntimeState.Snapshot runtimeState,
            String openFailureReason
    ) {
        MemoryOptimization {
            Objects.requireNonNull(runtimeState, "runtimeState");
            if (featureBound && !requested) {
                throw new IllegalArgumentException("bound memory optimizer requires a request");
            }
        }

        private static MemoryOptimization compatibility(boolean executed) {
            return new MemoryOptimization(
                    executed,
                    executed,
                    new VulkanFeatureRuntimeState.Snapshot(
                            executed
                                    ? VulkanFeatureRuntimeState.Status.ACTIVE
                                    : VulkanFeatureRuntimeState.Status.UNAVAILABLE,
                            executed ? "nvidia.rtx-memory-utility" : "none",
                            executed
                                    ? "RTXMU execution evidence supplied by compatibility caller"
                                    : "memory optimization disabled"
                    ),
                    null
            );
        }
    }
}
