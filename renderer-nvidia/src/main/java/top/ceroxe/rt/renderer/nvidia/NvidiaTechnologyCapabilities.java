package top.ceroxe.rt.renderer.nvidia;

import top.ceroxe.rt.renderer.api.FrameGenerationOptions;
import top.ceroxe.rt.renderer.api.FrameReconstructionOptions;
import top.ceroxe.rt.renderer.api.RayTracingRendererConfig;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Entry;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Status;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Technology;
import top.ceroxe.rt.renderer.feature.VulkanFeatureRequirements;

import java.util.Objects;
import java.util.Set;

/** Projects NVIDIA SDK state into the vendor-neutral public technology capability model. */
final class NvidiaTechnologyCapabilities {
    private NvidiaTechnologyCapabilities() {
    }

    static void declareRequirements(
            VulkanFeatureRequirements.Builder target,
            RayTracingRendererConfig configuration,
            NvidiaNativeBridge.Probe probe,
            NvidiaStreamlineRuntime.Preflight preflight
    ) {
        Objects.requireNonNull(target, "target");
        RayTracingRendererConfig checked = Objects.requireNonNull(configuration, "configuration");
        declareReconstruction(target, checked.frameReconstruction(), preflight);
        declareFrameGeneration(target, checked.frameGeneration(), preflight);
        declareLowLatency(target, checked, preflight);
        declareNative(
                target,
                Technology.RAY_TRACING_DENOISING,
                checked.denoising().preference().requested(),
                probe,
                NvidiaNativeBridge.NRD,
                "nvidia.nrd"
        );
        declareNative(
                target,
                Technology.ACCELERATION_STRUCTURE_MEMORY_OPTIMIZATION,
                checked.rayTracingOptimizations().memoryOptimization().requested(),
                probe,
                NvidiaNativeBridge.RTX_MEMORY_UTILITY,
                "nvidia.rtx-memory-utility"
        );
    }

    static void refineDeviceSupport(
            RenderingFeatureCapabilities.Builder target,
            RayTracingRendererConfig configuration,
            Set<NvidiaStreamlineRuntime.Feature> executable
    ) {
        Objects.requireNonNull(target, "target");
        RayTracingRendererConfig checked = Objects.requireNonNull(configuration, "configuration");
        Set<NvidiaStreamlineRuntime.Feature> features = Set.copyOf(executable);
        FrameReconstructionOptions reconstruction = checked.frameReconstruction();
        if (reconstruction.preference().requested()) {
            Technology primary = reconstructionTechnology(reconstruction);
            NvidiaStreamlineRuntime.Feature implementation =
                    reconstruction.mode() == FrameReconstructionOptions.Mode.SPATIAL_UPSCALING
                            ? NvidiaStreamlineRuntime.Feature.NIS : NvidiaStreamlineRuntime.Feature.DLSS;
            target.technology(primary, deviceEntry(features.contains(implementation), implementation(primary)));
            if (reconstruction.fallback() == FrameReconstructionOptions.Fallback.SPATIAL) {
                target.technology(
                        Technology.SPATIAL_UPSCALING,
                        deviceEntry(features.contains(NvidiaStreamlineRuntime.Feature.NIS), "nvidia.streamline.nis")
                );
            }
        }
        if (checked.frameGeneration().preference().requested()) {
            boolean supported = features.contains(NvidiaStreamlineRuntime.Feature.DLSS_FRAME_GENERATION)
                    && features.contains(NvidiaStreamlineRuntime.Feature.REFLEX)
                    && features.contains(NvidiaStreamlineRuntime.Feature.PCL);
            Entry entry = deviceEntry(supported, "nvidia.streamline.dlss-g");
            target.technology(Technology.FRAME_GENERATION, entry);
            if (multiFrameRequested(checked.frameGeneration())) {
                target.technology(Technology.MULTI_FRAME_GENERATION, entry);
            }
        }
        if (lowLatencyRequested(checked)) {
            target.technology(
                    Technology.LOW_LATENCY_MARKERS,
                    deviceEntry(lowLatencyBound(features), "nvidia.streamline.reflex-pcl")
            );
        }
    }

    static void declareSession(
            RenderingFeatureCapabilities.Builder target,
            RayTracingRendererConfig configuration,
            NvidiaNativeBridge.Probe probe,
            NvidiaStreamlineRuntime.Preflight preflight,
            Set<NvidiaStreamlineRuntime.Feature> executable
    ) {
        RayTracingRendererConfig checked = Objects.requireNonNull(configuration, "configuration");
        if (preflight != null && preflight.ready()) {
            refineDeviceSupport(target, checked, executable);
        } else {
            declareBlockedStreamlineSession(target, checked, preflight);
        }
        declareNative(
                target,
                Technology.RAY_TRACING_DENOISING,
                checked.denoising().preference().requested(),
                probe,
                NvidiaNativeBridge.NRD,
                "nvidia.nrd"
        );
        declareNative(
                target,
                Technology.ACCELERATION_STRUCTURE_MEMORY_OPTIMIZATION,
                checked.rayTracingOptimizations().memoryOptimization().requested(),
                probe,
                NvidiaNativeBridge.RTX_MEMORY_UTILITY,
                "nvidia.rtx-memory-utility"
        );
    }

    private static void declareBlockedStreamlineSession(
            RenderingFeatureCapabilities.Builder target,
            RayTracingRendererConfig configuration,
            NvidiaStreamlineRuntime.Preflight preflight
    ) {
        String reason = "Streamline preflight failed: "
                + (preflight == null ? "not initialized" : preflight.reason());
        FrameReconstructionOptions reconstruction = configuration.frameReconstruction();
        if (reconstruction.preference().requested()) {
            Technology primary = reconstructionTechnology(reconstruction);
            target.technology(primary, Entry.of(
                    Status.BLOCKED,
                    NvidiaTechnologyCapabilities.implementation(primary),
                    reason
            ));
            if (reconstruction.fallback() == FrameReconstructionOptions.Fallback.SPATIAL) {
                target.technology(Technology.SPATIAL_UPSCALING, Entry.of(
                        Status.BLOCKED, "nvidia.streamline.nis", reason
                ));
            }
        }
        if (configuration.frameGeneration().preference().requested()) {
            target.technology(Technology.FRAME_GENERATION, Entry.of(
                    Status.BLOCKED, "nvidia.streamline.dlss-g", reason
            ));
            if (multiFrameRequested(configuration.frameGeneration())) {
                target.technology(Technology.MULTI_FRAME_GENERATION, Entry.of(
                        Status.BLOCKED, "nvidia.streamline.dlss-g.mfg", reason
                ));
            }
        }
        if (lowLatencyRequested(configuration)) {
            target.technology(Technology.LOW_LATENCY_MARKERS, Entry.of(
                    Status.BLOCKED, "nvidia.streamline.reflex-pcl", reason
            ));
        }
    }

    static void copyTechnologies(
            RenderingFeatureCapabilities source,
            RenderingFeatureCapabilities.Builder target
    ) {
        source.technologies().forEach(target::technology);
    }

    static Technology reconstructionTechnology(FrameReconstructionOptions options) {
        return switch (Objects.requireNonNull(options, "options").mode()) {
            case SUPER_RESOLUTION -> Technology.TEMPORAL_SUPER_RESOLUTION;
            case NATIVE_ANTI_ALIASING -> Technology.NATIVE_TEMPORAL_ANTI_ALIASING;
            case SPATIAL_UPSCALING -> Technology.SPATIAL_UPSCALING;
        };
    }

    static String implementation(Technology technology) {
        return switch (Objects.requireNonNull(technology, "technology")) {
            case TEMPORAL_SUPER_RESOLUTION -> "nvidia.streamline.dlss-sr";
            case NATIVE_TEMPORAL_ANTI_ALIASING -> "nvidia.streamline.dlaa";
            case SPATIAL_UPSCALING -> "nvidia.streamline.nis";
            default -> throw new IllegalArgumentException("technology is not a reconstruction implementation: " + technology);
        };
    }

    static boolean multiFrameRequested(FrameGenerationOptions options) {
        FrameGenerationOptions checked = Objects.requireNonNull(options, "options");
        return checked.preference().requested()
                && (checked.mode() == FrameGenerationOptions.Mode.MULTI_FRAME_GENERATION
                || checked.mode() == FrameGenerationOptions.Mode.ADAPTIVE
                && checked.multiplier().presentedFramesPerNativeFrame() > 2);
    }

    private static void declareReconstruction(
            VulkanFeatureRequirements.Builder target,
            FrameReconstructionOptions options,
            NvidiaStreamlineRuntime.Preflight preflight
    ) {
        if (!options.preference().requested()) return;
        Technology primary = reconstructionTechnology(options);
        NvidiaStreamlineRuntime.Feature feature = options.mode() == FrameReconstructionOptions.Mode.SPATIAL_UPSCALING
                ? NvidiaStreamlineRuntime.Feature.NIS : NvidiaStreamlineRuntime.Feature.DLSS;
        target.technology(primary, preflightEntry(preflight, feature, implementation(primary)));
        if (options.fallback() == FrameReconstructionOptions.Fallback.SPATIAL) {
            target.technology(
                    Technology.SPATIAL_UPSCALING,
                    preflightEntry(preflight, NvidiaStreamlineRuntime.Feature.NIS, "nvidia.streamline.nis")
            );
        }
    }

    private static void declareFrameGeneration(
            VulkanFeatureRequirements.Builder target,
            FrameGenerationOptions options,
            NvidiaStreamlineRuntime.Preflight preflight
    ) {
        if (!options.preference().requested()) return;
        Entry generation = preflightEntry(
                preflight,
                NvidiaStreamlineRuntime.Feature.DLSS_FRAME_GENERATION,
                "nvidia.streamline.dlss-g"
        );
        target.technology(Technology.FRAME_GENERATION, generation);
        if (multiFrameRequested(options)) {
            target.technology(Technology.MULTI_FRAME_GENERATION, generation);
        }
    }

    private static void declareLowLatency(
            VulkanFeatureRequirements.Builder target,
            RayTracingRendererConfig configuration,
            NvidiaStreamlineRuntime.Preflight preflight
    ) {
        if (!lowLatencyRequested(configuration)) return;
        Entry reflex = preflightEntry(
                preflight,
                NvidiaStreamlineRuntime.Feature.REFLEX,
                "nvidia.streamline.reflex-pcl"
        );
        Entry pcl = preflightEntry(
                preflight,
                NvidiaStreamlineRuntime.Feature.PCL,
                "nvidia.streamline.reflex-pcl"
        );
        target.technology(
                Technology.LOW_LATENCY_MARKERS,
                reflex.status() == Status.AVAILABLE && pcl.status() == Status.AVAILABLE
                        ? reflex
                        : Entry.of(
                                reflex.status() == Status.BLOCKED || pcl.status() == Status.BLOCKED
                                        ? Status.BLOCKED : Status.NOT_SUPPORTED,
                                "none",
                                "Streamline requires both Reflex pacing and PCL frame markers"
                        )
        );
    }


    private static boolean lowLatencyRequested(RayTracingRendererConfig configuration) {
        return configuration.lowLatency().preference().requested()
                || configuration.frameGeneration().preference().requested();
    }

    private static boolean lowLatencyBound(Set<NvidiaStreamlineRuntime.Feature> features) {
        return features.contains(NvidiaStreamlineRuntime.Feature.REFLEX)
                && features.contains(NvidiaStreamlineRuntime.Feature.PCL);
    }

    private static void declareNative(
            VulkanFeatureRequirements.Builder target,
            Technology technology,
            boolean requested,
            NvidiaNativeBridge.Probe probe,
            int capability,
            String implementation
    ) {
        if (!requested) return;
        NvidiaNativeBridge.Probe checked = Objects.requireNonNull(probe, "probe");
        Status status = !checked.loaded() ? Status.BLOCKED
                : checked.supports(capability) ? Status.AVAILABLE : Status.NOT_SUPPORTED;
        target.technology(
                technology,
                Entry.of(status, status == Status.AVAILABLE ? implementation : "none", checked.reason())
        );
    }

    private static void declareNative(
            RenderingFeatureCapabilities.Builder target,
            Technology technology,
            boolean requested,
            NvidiaNativeBridge.Probe probe,
            int capability,
            String implementation
    ) {
        if (!requested) return;
        NvidiaNativeBridge.Probe checked = Objects.requireNonNull(probe, "probe");
        Status status = !checked.loaded() ? Status.BLOCKED
                : checked.supports(capability) ? Status.AVAILABLE : Status.NOT_SUPPORTED;
        target.technology(
                technology,
                Entry.of(status, status == Status.AVAILABLE ? implementation : "none", checked.reason())
        );
    }

    private static Entry preflightEntry(
            NvidiaStreamlineRuntime.Preflight preflight,
            NvidiaStreamlineRuntime.Feature feature,
            String implementation
    ) {
        if (preflight == null || !preflight.ready()) {
            return Entry.of(
                    Status.BLOCKED,
                    "none",
                    "Streamline preflight failed: " + (preflight == null ? "not initialized" : preflight.reason())
            );
        }
        boolean declared = preflight.requirements().containsKey(feature);
        return Entry.of(
                declared ? Status.AVAILABLE : Status.NOT_SUPPORTED,
                declared ? implementation : "none",
                declared ? "Streamline preflight succeeded; physical-device handoff pending"
                        : "Streamline did not expose " + feature
        );
    }

    private static Entry deviceEntry(boolean supported, String implementation) {
        return Entry.of(
                supported ? Status.AVAILABLE : Status.NOT_SUPPORTED,
                supported ? implementation : "none",
                supported ? "physical-device handoff succeeded; awaiting first execution"
                        : "the active Vulkan adapter rejected this Streamline technology"
        );
    }
}
