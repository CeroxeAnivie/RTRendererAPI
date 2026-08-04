package top.ceroxe.rt.renderer.nvidia;

import top.ceroxe.rt.renderer.api.FrameGenerationOptions;
import top.ceroxe.rt.renderer.api.FrameReconstructionOptions;
import top.ceroxe.rt.renderer.api.DenoisingOptions;
import top.ceroxe.rt.renderer.api.LowLatencyOptions;
import top.ceroxe.rt.renderer.api.RayTracingRendererConfig;
import top.ceroxe.rt.renderer.api.RendererFeaturePreference;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Entry;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Feature;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Status;
import top.ceroxe.rt.renderer.feature.VulkanFeatureFrameContext;
import top.ceroxe.rt.renderer.feature.VulkanFeatureOpenContext;
import top.ceroxe.rt.renderer.feature.VulkanFeatureProvider;
import top.ceroxe.rt.renderer.feature.VulkanFeatureRequirements;
import top.ceroxe.rt.renderer.feature.VulkanFeatureSession;
import top.ceroxe.rt.renderer.feature.VulkanFeatureRuntimeState;
import top.ceroxe.rt.renderer.feature.VulkanAccelerationStructureMemoryOptimizer;
import top.ceroxe.rt.renderer.feature.VulkanSwapchainInterceptor;
import top.ceroxe.rt.renderer.feature.Vulkan12Feature;
import top.ceroxe.rt.renderer.feature.Vulkan13Feature;
import top.ceroxe.rt.renderer.feature.VulkanFeatureQueueAllocation;
import top.ceroxe.rt.renderer.feature.VulkanQueueRequirements;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.rt.pipeline.VulkanFrameExtents;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.KHRComputeShaderDerivatives;

/** NVIDIA Streamline/NRD/RTXMU provider behind one optional JNI boundary. */
public final class NvidiaVulkanFeatureProvider implements VulkanFeatureProvider {
    private static final String PROVIDER_ID = "nvidia";
    private NvidiaStreamlineRuntime.Preflight streamlinePreflight;

    /** Creates the stateless ServiceLoader provider. */
    public NvidiaVulkanFeatureProvider() {
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public int priority() {
        return 1_000;
    }

    @Override
    public VulkanFeatureRequirements requirements(RayTracingRendererConfig configuration) {
        RayTracingRendererConfig checked = Objects.requireNonNull(configuration, "configuration");
        VulkanFeatureRequirements.Builder result = VulkanFeatureRequirements.builder();
        if (!nvidiaFeatureRequested(checked)) return result.build();

        NvidiaNativeBridge.Probe probe = NvidiaNativeBridge.probe();
        NvidiaStreamlineRuntime.Preflight preflight = streamlineRequested(checked)
                ? prepareStreamline(checked) : null;
        if (preflight != null && preflight.ready()) {
            addStreamlineDeviceRequirements(result, streamlineRequired(checked));
        }
        addReconstructionSupport(result, checked, probe, preflight);
        addLowLatencySupport(result, checked, preflight);
        addFrameGenerationSupport(result, checked, preflight);
        addSupport(
                result,
                Feature.DENOISING,
                checked.denoising().preference(),
                NvidiaNativeBridge.NRD,
                "nvidia.nrd",
                probe
        );
        if (checked.denoising().preference().requested() && probe.supports(NvidiaNativeBridge.NRD)) {
            // NRI's Vulkan wrapper records NRD barriers with vkCmdPipelineBarrier2. Enabling the
            // device feature is part of making NRD executable, not an optional optimization.
            // Streamline may already have requested synchronization2 at preferred strength.
            // NRD makes the same device bit mandatory, so promote the exact shared requirement
            // instead of emitting contradictory required+preferred declarations.
            boolean required = checked.denoising().preference()
                    == RendererFeaturePreference.REQUIRED;
            result.mergeVulkan13Feature(Vulkan13Feature.SYNCHRONIZATION_2, required);
            addNrdDeviceRequirements(result, required);
        }
        addSupport(
                result,
                Feature.MEMORY_OPTIMIZATION,
                checked.rayTracingOptimizations().memoryOptimization(),
                NvidiaNativeBridge.RTX_MEMORY_UTILITY,
                "nvidia.rtx-memory-utility",
                probe
        );
        NvidiaTechnologyCapabilities.declareRequirements(
                result, checked, probe, preflight
        );
        return result.build();
    }

    /** Declares feature bits consumed by the borrowed-device NRI/NRD integration. */
    static void addNrdDeviceRequirements(
            VulkanFeatureRequirements.Builder target,
            boolean required
    ) {
        Objects.requireNonNull(target, "target")
                .mergeVulkan12Feature(Vulkan12Feature.DESCRIPTOR_BINDING_PARTIALLY_BOUND, required)
                .mergeVulkan13Feature(Vulkan13Feature.PRIVATE_DATA, required)
                .mergeDeviceExtension(
                        KHRComputeShaderDerivatives.VK_KHR_COMPUTE_SHADER_DERIVATIVES_EXTENSION_NAME,
                        required
                );
    }

    /** Declares host features used by Streamline but omitted from its Vulkan requirement report. */
    static void addStreamlineDeviceRequirements(
            VulkanFeatureRequirements.Builder target,
            boolean required
    ) {
        // sl.chi creates a VkPrivateDataSlot unconditionally during Vulkan device handoff when
        // the Vulkan 1.3 entry point is present. Calling it without enabling privateData violates
        // VUID-vkCreatePrivateDataSlot-privateData-04564 before feature support is queried.
        Objects.requireNonNull(target, "target")
                .mergeVulkan13Feature(Vulkan13Feature.PRIVATE_DATA, required);
    }

    @Override
    public void discardPlan() {
        closePreparedStreamline();
    }

    @Override
    public VulkanFeatureSession open(VulkanFeatureOpenContext context) {
        VulkanFeatureOpenContext checked = Objects.requireNonNull(context, "context");
        RayTracingRendererConfig configuration = checked.configuration();
        NvidiaNativeBridge.Probe probe = NvidiaNativeBridge.probe();
        EnumMap<Feature, Entry> active = activeFeatures(configuration, probe);
        NvidiaStreamlineRuntime.Preflight plannedStreamline = claimPreparedStreamline();
        Set<NvidiaStreamlineRuntime.Feature> streamlineFeatures =
                selectedStreamlineFeatures(plannedStreamline);
        boolean streamlinePrepared = !streamlineFeatures.isEmpty();
        NvidiaStreamlineDeviceSession streamlineSession = null;
        NvidiaNativeFeatureSessions nativeSessions = null;
        try {
            if (streamlinePrepared) {
                try {
                    streamlineSession = NvidiaStreamlineDeviceSession.bind(
                            checked,
                            streamlineFeatures,
                            requiredStreamlineFeatures(configuration, streamlineFeatures)
                    );
                    streamlineFeatures = streamlineSession.features();
                } catch (RuntimeException | LinkageError failure) {
                    handleStreamlineOpenFailure(active, configuration, failure);
                    streamlineFeatures = Set.of();
                }
            } else if (plannedStreamline != null) {
                // A failed/empty preflight can still own process-level Streamline initialization.
                // The provider has claimed it, so release it here instead of leaving ownership in
                // a provider that will never receive discardPlan after a successful open.
                NvidiaStreamlineRuntime.closePreflight();
            }

            if (streamlineSession != null) {
                NvidiaStreamlineRuntime.Feature streamlineFeature =
                        selectedReconstructionFeature(streamlineFeatures);
                if (streamlineFeature != null) {
                    active.put(Feature.FRAME_RECONSTRUCTION, Entry.of(
                            streamlineStatus(configuration.frameReconstruction(), streamlineFeature),
                            implementation(streamlineFeature),
                            "Streamline " + streamlineFeature + " device handoff succeeded; awaiting first evaluate"
                    ));
                } else if (configuration.frameReconstruction().preference().requested()) {
                    boolean temporalFallback = configuration.frameReconstruction().fallback()
                            == FrameReconstructionOptions.Fallback.BUILT_IN_TEMPORAL;
                    active.put(Feature.FRAME_RECONSTRUCTION, Entry.of(
                            Status.FALLBACK_PENDING,
                            temporalFallback ? "builtin.temporal" : "native.resolution",
                            "Streamline reconstruction is unsupported on the active adapter; using "
                                    + (temporalFallback ? "built-in temporal output" : "native-resolution output")
                    ));
                }
                if (streamlineFeatures.contains(NvidiaStreamlineRuntime.Feature.DLSS_FRAME_GENERATION)) {
                    active.put(Feature.FRAME_GENERATION, Entry.of(
                            Status.AVAILABLE,
                            "nvidia.streamline.dlss-g",
                            "Streamline DLSS-G device handoff succeeded; awaiting tagged present"
                    ));
                } else if (configuration.frameGeneration().preference().requested()) {
                    active.put(Feature.FRAME_GENERATION, Entry.of(
                            Status.FALLBACK_PENDING,
                            "native.presentation",
                            "Streamline DLSS-G/Reflex/PCL is unsupported on the active adapter"
                    ));
                }
                if (configuration.lowLatency().preference().requested()) {
                    boolean lowLatencyBound = lowLatencyBound(streamlineFeatures);
                    active.put(Feature.LOW_LATENCY, Entry.of(
                            lowLatencyBound ? Status.AVAILABLE : Status.FALLBACK_PENDING,
                            lowLatencyBound ? "nvidia.streamline.reflex-pcl" : "native.scheduling",
                            lowLatencyBound
                                    ? "Streamline Reflex/PCL device handoff succeeded; awaiting first frame interval"
                                    : "Reflex/PCL is unsupported on the active adapter; using native scheduling"
                    ));
                }
            }

            nativeSessions = NvidiaNativeFeatureSessions.open(
                    checked,
                    probe,
                    configuration.denoising().preference(),
                    configuration.rayTracingOptimizations().memoryOptimization()
            );
            applyNativeOpenOutcomes(active, configuration, nativeSessions);
            NvidiaFeatureSession session = new NvidiaFeatureSession(
                    nativeSessions,
                    streamlineSession,
                    capabilities(
                            active, configuration, probe, plannedStreamline,
                            streamlineFeatures
                    ),
                    configuration.denoising(),
                    configuration.frameReconstruction(),
                    configuration.frameGeneration(),
                    configuration.lowLatency(),
                    streamlineFeatures,
                    checked
            );
            nativeSessions = null;
            streamlineSession = null;
            return session;
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            closeSuppressing(nativeSessions, failure);
            closeSuppressing(streamlineSession, failure);
            throw failure;
        }
    }

    private synchronized NvidiaStreamlineRuntime.Preflight claimPreparedStreamline() {
        NvidiaStreamlineRuntime.Preflight claimed = streamlinePreflight;
        streamlinePreflight = null;
        return claimed;
    }

    static void closeSuppressing(AutoCloseable owner, Throwable failure) {
        if (owner == null) return;
        try {
            owner.close();
        } catch (Exception | LinkageError | OutOfMemoryError closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    /** Applies only the documented PREFERRED Streamline fallback; strict failures escape intact. */
    static void handleStreamlineOpenFailure(
            EnumMap<Feature, Entry> active,
            RayTracingRendererConfig configuration,
            Throwable failure
    ) {
        Objects.requireNonNull(active, "active");
        RayTracingRendererConfig checked = Objects.requireNonNull(configuration, "configuration");
        Throwable checkedFailure = Objects.requireNonNull(failure, "failure");
        if (streamlineRequired(checked)
                || checkedFailure instanceof top.ceroxe.rt.renderer.api.RendererDeviceException) {
            if (checkedFailure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
            if (checkedFailure instanceof Error error) throw error;
            throw new IllegalArgumentException(
                    "unsupported Streamline failure type", checkedFailure
            );
        }
        String reason = describeOpenFailure("Streamline device handoff", failure);
        if (checked.frameReconstruction().preference().requested()) {
            FrameReconstructionOptions options = checked.frameReconstruction();
            boolean temporal = options.fallback() == FrameReconstructionOptions.Fallback.BUILT_IN_TEMPORAL;
            active.put(Feature.FRAME_RECONSTRUCTION, Entry.of(
                    Status.FALLBACK_PENDING,
                    temporal ? "builtin.temporal" : "native.resolution",
                    reason
            ));
        }
        if (checked.frameGeneration().preference().requested()) {
            active.put(Feature.FRAME_GENERATION, Entry.of(
                    Status.FALLBACK_PENDING, "native.presentation", reason
            ));
        }
        if (checked.lowLatency().preference().requested()) {
            active.put(Feature.LOW_LATENCY, Entry.of(
                    Status.FALLBACK_PENDING, "native.scheduling", reason
            ));
        }
    }

    static void applyNativeOpenOutcomes(
            EnumMap<Feature, Entry> active,
            RayTracingRendererConfig configuration,
            NvidiaNativeFeatureSessions sessions
    ) {
        EnumMap<Feature, Entry> checkedActive = Objects.requireNonNull(active, "active");
        RayTracingRendererConfig checkedConfiguration =
                Objects.requireNonNull(configuration, "configuration");
        NvidiaNativeFeatureSessions checkedSessions = Objects.requireNonNull(sessions, "sessions");
        if (checkedSessions.nrdAvailable()) {
            checkedActive.put(Feature.DENOISING, Entry.of(
                    Status.AVAILABLE,
                    "nvidia.nrd",
                    "NRD device session initialized; awaiting an accepted post-trace dispatch"
            ));
        } else if (checkedSessions.nrdOpenFailure() != null) {
            boolean temporal = checkedConfiguration.denoising().builtInTemporalFallback();
            checkedActive.put(Feature.DENOISING, Entry.of(
                    temporal ? Status.FALLBACK_PENDING : Status.BLOCKED,
                    temporal ? "builtin.temporal" : "none",
                    describeOpenFailure("NRD device open", checkedSessions.nrdOpenFailure())
            ));
        }

        if (checkedSessions.rtxmuAvailable()) {
            checkedActive.put(Feature.MEMORY_OPTIMIZATION, Entry.of(
                    Status.AVAILABLE,
                    "nvidia.rtx-memory-utility",
                    "RTXMU device session initialized; awaiting a managed BLAS completion"
            ));
        } else if (checkedSessions.rtxmuOpenFailure() != null) {
            checkedActive.put(Feature.MEMORY_OPTIMIZATION, Entry.of(
                    Status.BLOCKED,
                    "nvidia.rtx-memory-utility",
                    describeOpenFailure("RTXMU device open", checkedSessions.rtxmuOpenFailure())
            ));
        }
    }

    private static String describeOpenFailure(String operation, Throwable failure) {
        String message = failure.getMessage();
        return operation + " failed: " + failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private void addReconstructionSupport(
            VulkanFeatureRequirements.Builder result,
            RayTracingRendererConfig configuration,
            NvidiaNativeBridge.Probe probe,
            NvidiaStreamlineRuntime.Preflight preflight
    ) {
        FrameReconstructionOptions options = configuration.frameReconstruction();
        if (!options.preference().requested()) return;
        Set<NvidiaStreamlineRuntime.Feature> available = selectedStreamlineFeatures(preflight);
        NvidiaStreamlineRuntime.Feature feature = selectedReconstructionFeature(available);
        if (feature != null) {
            for (NvidiaStreamlineRuntime.Feature candidate : available) {
                if (candidate != NvidiaStreamlineRuntime.Feature.DLSS
                        && candidate != NvidiaStreamlineRuntime.Feature.NIS) continue;
                applyStreamlineRequirements(
                        result,
                        preflight.requirements().get(candidate),
                        options.preference() == RendererFeaturePreference.REQUIRED && candidate == feature
                );
            }
            result.support(Feature.FRAME_RECONSTRUCTION,
                    Entry.of(
                            feature == NvidiaStreamlineRuntime.Feature.NIS
                                    && options.mode() != FrameReconstructionOptions.Mode.SPATIAL_UPSCALING
                                    ? Status.FALLBACK_PENDING : Status.AVAILABLE,
                            implementation(feature),
                            "Streamline " + feature + " preflight succeeded; device handoff pending"
                    ));
            return;
        }
        int temporalMask = options.mode() == FrameReconstructionOptions.Mode.NATIVE_ANTI_ALIASING
                ? NvidiaNativeBridge.DLAA
                : NvidiaNativeBridge.DLSS;
        if (probe.supports(temporalMask)) {
            result.support(
                    Feature.FRAME_RECONSTRUCTION,
                    Entry.of(Status.AVAILABLE, "nvidia.dlss", probe.reason())
            );
        } else if (options.preference() == RendererFeaturePreference.PREFERRED
                && options.fallback() == FrameReconstructionOptions.Fallback.SPATIAL
                && probe.supports(NvidiaNativeBridge.NIS)) {
            result.support(
                    Feature.FRAME_RECONSTRUCTION,
                    Entry.of(Status.FALLBACK_PENDING, "nvidia.nis", "DLSS/DLAA unavailable; NIS is available")
            );
        } else {
            result.support(
                    Feature.FRAME_RECONSTRUCTION,
                    Entry.of(
                            preflight.ready() ? Status.NOT_SUPPORTED : Status.BLOCKED,
                            "none",
                            "Streamline preflight failed: " + preflight.reason()
                                    + "; native capability probe: " + probe.reason()
                    )
            );
        }
    }

    private void addFrameGenerationSupport(
            VulkanFeatureRequirements.Builder result,
            RayTracingRendererConfig configuration,
            NvidiaStreamlineRuntime.Preflight preflight
    ) {
        FrameGenerationOptions options = configuration.frameGeneration();
        if (!options.preference().requested()) return;
        if (preflight != null && preflight.ready()
                && preflight.requirements().containsKey(NvidiaStreamlineRuntime.Feature.DLSS_FRAME_GENERATION)) {
            for (NvidiaStreamlineRuntime.Feature dependency : Set.of(
                    NvidiaStreamlineRuntime.Feature.DLSS_FRAME_GENERATION,
                    NvidiaStreamlineRuntime.Feature.REFLEX,
                    NvidiaStreamlineRuntime.Feature.PCL
            )) {
                NvidiaStreamlineRuntime.Requirements dependencyRequirements =
                        preflight.requirements().get(dependency);
                if (dependencyRequirements == null) {
                    result.support(Feature.FRAME_GENERATION, Entry.of(
                            Status.BLOCKED,
                            "none",
                            "Streamline DLSS-G preflight omitted required dependency " + dependency
                    ));
                    return;
                }
                applyStreamlineRequirements(
                        result,
                        dependencyRequirements,
                        options.preference() == RendererFeaturePreference.REQUIRED
                );
            }
            result.support(Feature.FRAME_GENERATION, Entry.of(
                    Status.AVAILABLE,
                    "nvidia.streamline.dlss-g",
                    "Streamline DLSS-G preflight succeeded; swapchain proxy activation pending"
            ));
            return;
        }
        result.support(Feature.FRAME_GENERATION, Entry.of(
                Status.BLOCKED,
                "none",
                "Streamline DLSS-G preflight failed: " + (preflight == null ? "not initialized" : preflight.reason())
        ));
    }

    private static void addLowLatencySupport(
            VulkanFeatureRequirements.Builder result,
            RayTracingRendererConfig configuration,
            NvidiaStreamlineRuntime.Preflight preflight
    ) {
        LowLatencyOptions options = configuration.lowLatency();
        if (!options.preference().requested()) return;
        if (preflight != null && preflight.ready()
                && preflight.requirements().containsKey(NvidiaStreamlineRuntime.Feature.REFLEX)
                && preflight.requirements().containsKey(NvidiaStreamlineRuntime.Feature.PCL)) {
            boolean required = options.preference() == RendererFeaturePreference.REQUIRED;
            applyStreamlineRequirements(
                    result,
                    preflight.requirements().get(NvidiaStreamlineRuntime.Feature.REFLEX),
                    required
            );
            applyStreamlineRequirements(
                    result,
                    preflight.requirements().get(NvidiaStreamlineRuntime.Feature.PCL),
                    required
            );
            result.support(Feature.LOW_LATENCY, Entry.of(
                    Status.AVAILABLE,
                    "nvidia.streamline.reflex-pcl",
                    "Streamline Reflex/PCL preflight succeeded; device handoff pending"
            ));
            return;
        }
        result.support(Feature.LOW_LATENCY, Entry.of(
                preflight != null && preflight.ready() ? Status.NOT_SUPPORTED : Status.BLOCKED,
                "none",
                "Streamline Reflex/PCL preflight failed: "
                        + (preflight == null ? "not initialized" : preflight.reason())
        ));
    }

    private static void addSupport(
            VulkanFeatureRequirements.Builder result,
            Feature feature,
            RendererFeaturePreference preference,
            int capability,
            String implementation,
            NvidiaNativeBridge.Probe probe
    ) {
        if (!preference.requested()) return;
        result.support(
                feature,
                probe.supports(capability)
                        ? Entry.of(Status.AVAILABLE, implementation, probe.reason())
                        : Entry.of(
                                probe.loaded() ? Status.NOT_SUPPORTED : Status.BLOCKED,
                                "none",
                                probe.reason()
                        )
        );
    }

    private static boolean nvidiaFeatureRequested(RayTracingRendererConfig configuration) {
        return configuration.frameReconstruction().preference().requested()
                || configuration.frameGeneration().preference().requested()
                || configuration.lowLatency().preference().requested()
                || configuration.denoising().preference().requested()
                || configuration.rayTracingOptimizations().memoryOptimization().requested();
    }

    private static EnumMap<Feature, Entry> activeFeatures(
            RayTracingRendererConfig configuration,
            NvidiaNativeBridge.Probe probe
    ) {
        EnumMap<Feature, Entry> result = new EnumMap<>(Feature.class);
        if (configuration.denoising().preference().requested() && probe.supports(NvidiaNativeBridge.NRD)) {
            result.put(Feature.DENOISING, Entry.of(
                    Status.AVAILABLE,
                    "nvidia.nrd",
                    "NRD device session initialized; awaiting first post-trace dispatch"
            ));
        }
        if (configuration.rayTracingOptimizations().memoryOptimization().requested()
                && probe.supports(NvidiaNativeBridge.RTX_MEMORY_UTILITY)) {
            result.put(Feature.MEMORY_OPTIMIZATION,
                    Entry.of(
                            Status.AVAILABLE,
                            "nvidia.rtx-memory-utility",
                            "RTXMU v1.4 device manager initialized; awaiting a managed BLAS completion"
                    ));
        }
        return result;
    }

    private static boolean streamlineRequested(RayTracingRendererConfig configuration) {
        return configuration.frameReconstruction().preference().requested()
                || configuration.frameGeneration().preference().requested()
                || configuration.lowLatency().preference().requested();
    }

    private static boolean streamlineRequired(RayTracingRendererConfig configuration) {
        return configuration.frameReconstruction().preference() == RendererFeaturePreference.REQUIRED
                || configuration.frameGeneration().preference() == RendererFeaturePreference.REQUIRED
                || configuration.lowLatency().preference() == RendererFeaturePreference.REQUIRED;
    }

    private synchronized NvidiaStreamlineRuntime.Preflight prepareStreamline(
            RayTracingRendererConfig configuration
    ) {
        if (streamlinePreflight != null) return streamlinePreflight;
        FrameReconstructionOptions reconstruction = configuration.frameReconstruction();
        EnumSet<NvidiaStreamlineRuntime.Feature> requested =
                EnumSet.noneOf(NvidiaStreamlineRuntime.Feature.class);
        if (reconstruction.preference().requested()) {
            requested.add(reconstruction.mode() == FrameReconstructionOptions.Mode.SPATIAL_UPSCALING
                    ? NvidiaStreamlineRuntime.Feature.NIS
                    : NvidiaStreamlineRuntime.Feature.DLSS);
            if (reconstruction.preference() == RendererFeaturePreference.PREFERRED
                    && reconstruction.mode() != FrameReconstructionOptions.Mode.SPATIAL_UPSCALING
                    && reconstruction.fallback() == FrameReconstructionOptions.Fallback.SPATIAL) {
                // Load the spatial fallback in the same process/device transaction. If the
                // adapter rejects DLSS during slIsFeatureSupported, NIS can remain executable
                // without an unsafe slShutdown/slInit cycle after VkDevice creation.
                requested.add(NvidiaStreamlineRuntime.Feature.NIS);
            }
        }
        boolean generationRequested = configuration.frameGeneration().preference().requested();
        boolean independentLowLatencyRequested = configuration.lowLatency().preference().requested();
        if (generationRequested) {
            requested.add(NvidiaStreamlineRuntime.Feature.DLSS_FRAME_GENERATION);
        }
        if (generationRequested || independentLowLatencyRequested) {
            // Frame generation depends on this pair, while an independent request keeps the same
            // pacing and marker integration active for native presentation.
            requested.add(NvidiaStreamlineRuntime.Feature.REFLEX);
            requested.add(NvidiaStreamlineRuntime.Feature.PCL);
        }
        streamlinePreflight = NvidiaStreamlineRuntime.preflight(requested);
        return streamlinePreflight;
    }

    private static NvidiaStreamlineRuntime.Feature selectedReconstructionFeature(
            NvidiaStreamlineRuntime.Preflight preflight
    ) {
        return selectedReconstructionFeature(selectedStreamlineFeatures(preflight));
    }

    private static NvidiaStreamlineRuntime.Feature selectedReconstructionFeature(
            Set<NvidiaStreamlineRuntime.Feature> features
    ) {
        if (features.contains(NvidiaStreamlineRuntime.Feature.DLSS)) {
            return NvidiaStreamlineRuntime.Feature.DLSS;
        }
        if (features.contains(NvidiaStreamlineRuntime.Feature.NIS)) {
            return NvidiaStreamlineRuntime.Feature.NIS;
        }
        return null;
    }

    private static Set<NvidiaStreamlineRuntime.Feature> requiredStreamlineFeatures(
            RayTracingRendererConfig configuration,
            Set<NvidiaStreamlineRuntime.Feature> prepared
    ) {
        EnumSet<NvidiaStreamlineRuntime.Feature> required =
                EnumSet.noneOf(NvidiaStreamlineRuntime.Feature.class);
        if (configuration.frameReconstruction().preference() == RendererFeaturePreference.REQUIRED) {
            NvidiaStreamlineRuntime.Feature reconstruction = selectedReconstructionFeature(prepared);
            if (reconstruction != null) required.add(reconstruction);
        }
        if (configuration.frameGeneration().preference() == RendererFeaturePreference.REQUIRED) {
            required.add(NvidiaStreamlineRuntime.Feature.DLSS_FRAME_GENERATION);
            required.add(NvidiaStreamlineRuntime.Feature.REFLEX);
            required.add(NvidiaStreamlineRuntime.Feature.PCL);
        }
        if (configuration.lowLatency().preference() == RendererFeaturePreference.REQUIRED) {
            required.add(NvidiaStreamlineRuntime.Feature.REFLEX);
            required.add(NvidiaStreamlineRuntime.Feature.PCL);
        }
        return required;
    }

    private static boolean lowLatencyBound(Set<NvidiaStreamlineRuntime.Feature> features) {
        return features.contains(NvidiaStreamlineRuntime.Feature.REFLEX)
                && features.contains(NvidiaStreamlineRuntime.Feature.PCL);
    }

    private static Set<NvidiaStreamlineRuntime.Feature> selectedStreamlineFeatures(
            NvidiaStreamlineRuntime.Preflight preflight
    ) {
        if (preflight == null || !preflight.ready()) return Set.of();
        return Set.copyOf(preflight.requirements().keySet());
    }

    private static String implementation(NvidiaStreamlineRuntime.Feature feature) {
        return feature == NvidiaStreamlineRuntime.Feature.NIS
                ? "nvidia.streamline.nis" : "nvidia.streamline.dlss";
    }

    private static Status streamlineStatus(
            FrameReconstructionOptions options,
            NvidiaStreamlineRuntime.Feature feature
    ) {
        return feature == NvidiaStreamlineRuntime.Feature.NIS
                && options.mode() != FrameReconstructionOptions.Mode.SPATIAL_UPSCALING
                ? Status.FALLBACK_PENDING : Status.AVAILABLE;
    }

    private synchronized void closePreparedStreamline() {
        if (streamlinePreflight == null) return;
        streamlinePreflight = null;
        NvidiaStreamlineRuntime.closePreflight();
    }

    private static void applyStreamlineRequirements(
            VulkanFeatureRequirements.Builder target,
            NvidiaStreamlineRuntime.Requirements requirements,
            boolean required
    ) {
        for (String extension : requirements.instanceExtensions()) {
            target.mergeInstanceExtension(extension, required);
        }
        for (String extension : requirements.deviceExtensions()) {
            target.mergeDeviceExtension(extension, required);
        }
        for (String feature : requirements.vulkan12Features()) {
            Vulkan12Feature value = Vulkan12Feature.fromStreamlineName(feature);
            target.mergeVulkan12Feature(value, required);
        }
        for (String feature : requirements.vulkan13Features()) {
            Vulkan13Feature value = Vulkan13Feature.fromStreamlineName(feature);
            target.mergeVulkan13Feature(value, required);
        }
        VulkanQueueRequirements queues = requirements.queues();
        if (required) {
            target.requireQueues(new VulkanQueueRequirements(
                    queues.additionalGraphicsQueues(), queues.additionalComputeQueues(), 0
            ));
            /*
             * Streamline reports the native optical-flow queue role, but its Vulkan contract
             * explicitly supports optical-flow interop when no exclusive NV queue family exists.
             * Keeping this role preferred prevents REQUIRED DLSS-G from rejecting otherwise
             * supported devices while still allowing bootstrap to reserve a native queue when
             * the physical device exposes one.
             */
            target.preferQueues(new VulkanQueueRequirements(
                    0, 0, queues.additionalOpticalFlowQueues()
            ));
        } else {
            target.preferQueues(queues);
        }
    }

    private static int requestedMask(
            RayTracingRendererConfig configuration,
            NvidiaNativeBridge.Probe probe
    ) {
        int mask = 0;
        FrameReconstructionOptions reconstruction = configuration.frameReconstruction();
        if (reconstruction.preference().requested()) {
            int temporal = reconstruction.mode() == FrameReconstructionOptions.Mode.NATIVE_ANTI_ALIASING
                    ? NvidiaNativeBridge.DLAA
                    : NvidiaNativeBridge.DLSS;
            if (probe.supports(temporal)) mask |= temporal;
            else if (reconstruction.fallback() == FrameReconstructionOptions.Fallback.SPATIAL
                    && probe.supports(NvidiaNativeBridge.NIS)) mask |= NvidiaNativeBridge.NIS;
        }
        if (configuration.denoising().preference().requested() && probe.supports(NvidiaNativeBridge.NRD)) {
            mask |= NvidiaNativeBridge.NRD;
        }
        if (configuration.rayTracingOptimizations().memoryOptimization().requested()
                && probe.supports(NvidiaNativeBridge.RTX_MEMORY_UTILITY)) {
            mask |= NvidiaNativeBridge.RTX_MEMORY_UTILITY;
        }
        return mask;
    }

    private static RenderingFeatureCapabilities capabilities(
            Map<Feature, Entry> active,
            RayTracingRendererConfig configuration,
            NvidiaNativeBridge.Probe probe,
            NvidiaStreamlineRuntime.Preflight preflight,
            Set<NvidiaStreamlineRuntime.Feature> streamlineFeatures
    ) {
        RenderingFeatureCapabilities.Builder result = RenderingFeatureCapabilities.builder();
        active.forEach(result::feature);
        NvidiaTechnologyCapabilities.declareSession(
                result, configuration, probe, preflight, streamlineFeatures
        );
        return result.build();
    }

}
