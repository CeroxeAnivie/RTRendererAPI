package top.ceroxe.rt.renderer.nvidia;

import top.ceroxe.rt.renderer.api.DenoisingOptions;
import top.ceroxe.rt.renderer.api.FrameGenerationOptions;
import top.ceroxe.rt.renderer.api.FrameReconstructionOptions;
import top.ceroxe.rt.renderer.api.RendererConfig;
import top.ceroxe.rt.renderer.api.RayTracingOptimizationOptions;
import top.ceroxe.rt.renderer.api.RendererFeaturePreference;
import top.ceroxe.rt.renderer.api.RendererPreset;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Status;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Technology;
import top.ceroxe.rt.renderer.feature.VulkanFeatureRuntimeState;
import top.ceroxe.rt.renderer.feature.VulkanFeatureRequirements;
import top.ceroxe.rt.renderer.feature.VulkanQueueRequirements;
import top.ceroxe.rt.renderer.feature.Vulkan12Feature;
import top.ceroxe.rt.renderer.feature.Vulkan13Feature;
import org.lwjgl.vulkan.KHRComputeShaderDerivatives;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/** Verifies capability-driven production defaults without loading a native SDK or Vulkan device. */
public final class NvidiaTechnologyCapabilitiesSelfTest {
    private static final int ALL_NATIVE_CAPABILITIES = NvidiaNativeBridge.DLSS
            | NvidiaNativeBridge.DLAA
            | NvidiaNativeBridge.NIS
            | NvidiaNativeBridge.NRD
            | NvidiaNativeBridge.RTX_MEMORY_UTILITY;

    private NvidiaTechnologyCapabilitiesSelfTest() {
    }

    public static void main(String[] arguments) {
        productionDefaultsDoNotRequestPresentationOwnership();
        nrdDeclaresBorrowedDeviceRequirements();
        streamlineDeclaresPrivateDataRequirementAtRequestedStrength();
        nativeLoadAndCapabilityFailuresRemainDistinct();
        streamlinePreflightSelectsSupportedFallbacks();
        deviceHandoffPrunesUnsupportedGenerationFamilies();
        explicitTwoTimesGenerationDoesNotClaimMultiFrameGeneration();
        runtimeExecutionEvidenceActivatesOnlyTheExecutedTechnology();
        generatedPresentationDistinguishesFrameGenerationFromMultiFrameGeneration();
        System.out.println("NvidiaTechnologyCapabilitiesSelfTest passed");
    }

    private static void productionDefaultsDoNotRequestPresentationOwnership() {
        Map<Technology, RenderingFeatureCapabilities.Entry> technologies = requirements(
                RendererPreset.CPU_READBACK.configuration(),
                new NvidiaNativeBridge.Probe(true, ALL_NATIVE_CAPABILITIES, "native SDKs loaded"),
                readyPreflight(
                        NvidiaStreamlineRuntime.Feature.DLSS,
                        NvidiaStreamlineRuntime.Feature.NIS,
                        NvidiaStreamlineRuntime.Feature.DLSS_FRAME_GENERATION,
                        NvidiaStreamlineRuntime.Feature.REFLEX,
                        NvidiaStreamlineRuntime.Feature.PCL
                )
        );

        requireStatus(technologies, Technology.TEMPORAL_SUPER_RESOLUTION, Status.AVAILABLE);
        requireStatus(technologies, Technology.SPATIAL_UPSCALING, Status.AVAILABLE);
        require(!technologies.containsKey(Technology.RAY_TRACING_DENOISING),
                "CPU-readable defaults must not request NRD: " + technologies);
        requireStatus(
                technologies,
                Technology.ACCELERATION_STRUCTURE_MEMORY_OPTIMIZATION,
                Status.AVAILABLE
        );
        require(!technologies.containsKey(Technology.FRAME_GENERATION)
                        && !technologies.containsKey(Technology.MULTI_FRAME_GENERATION)
                        && !technologies.containsKey(Technology.LOW_LATENCY_MARKERS),
                "CPU-readable defaults must not request presentation ownership: " + technologies);
    }

    private static void nrdDeclaresBorrowedDeviceRequirements() {
        VulkanFeatureRequirements.Builder preferredTarget = VulkanFeatureRequirements.builder();
        NvidiaVulkanFeatureProvider.addNrdDeviceRequirements(preferredTarget, false);
        VulkanFeatureRequirements preferred = preferredTarget.build();
        require(preferred.preferredVulkan12Features().contains(
                        Vulkan12Feature.DESCRIPTOR_BINDING_PARTIALLY_BOUND),
                "preferred NRD must preserve fallback semantics for descriptor requirements");
        require(preferred.preferredVulkan13Features().contains(Vulkan13Feature.PRIVATE_DATA),
                "preferred NRD must preserve fallback semantics for privateData");
        require(preferred.preferredDeviceExtensions().contains(
                        KHRComputeShaderDerivatives.VK_KHR_COMPUTE_SHADER_DERIVATIVES_EXTENSION_NAME),
                "preferred NRD must preserve fallback semantics for compute shader derivatives");

        VulkanFeatureRequirements.Builder target = VulkanFeatureRequirements.builder();
        NvidiaVulkanFeatureProvider.addNrdDeviceRequirements(target, true);
        VulkanFeatureRequirements requirements = target.build();
        require(requirements.requiredVulkan12Features().contains(
                        Vulkan12Feature.DESCRIPTOR_BINDING_PARTIALLY_BOUND),
                "NRD must require descriptorBindingPartiallyBound before NRI creates layouts");
        require(requirements.requiredVulkan13Features().contains(Vulkan13Feature.PRIVATE_DATA),
                "NRD must require privateData before NRI creates its private-data slot");
        require(requirements.requiredDeviceExtensions().contains(
                        KHRComputeShaderDerivatives.VK_KHR_COMPUTE_SHADER_DERIVATIVES_EXTENSION_NAME),
                "NRD must require the extension used by its embedded compute shaders");
    }

    private static void streamlineDeclaresPrivateDataRequirementAtRequestedStrength() {
        VulkanFeatureRequirements.Builder preferredTarget = VulkanFeatureRequirements.builder();
        NvidiaVulkanFeatureProvider.addStreamlineDeviceRequirements(preferredTarget, false);
        VulkanFeatureRequirements preferred = preferredTarget.build();
        require(preferred.preferredVulkan13Features().contains(Vulkan13Feature.PRIVATE_DATA),
                "preferred Streamline must enable privateData without making the renderer mandatory");
        require(!preferred.requiredVulkan13Features().contains(Vulkan13Feature.PRIVATE_DATA),
                "preferred Streamline must preserve fallback semantics for privateData");

        VulkanFeatureRequirements.Builder requiredTarget = VulkanFeatureRequirements.builder();
        NvidiaVulkanFeatureProvider.addStreamlineDeviceRequirements(requiredTarget, true);
        VulkanFeatureRequirements required = requiredTarget.build();
        require(required.requiredVulkan13Features().contains(Vulkan13Feature.PRIVATE_DATA),
                "required Streamline must require privateData before device handoff");
        require(!required.preferredVulkan13Features().contains(Vulkan13Feature.PRIVATE_DATA),
                "required Streamline must not duplicate privateData at preferred strength");
    }

    private static void nativeLoadAndCapabilityFailuresRemainDistinct() {
        RendererConfig requested = RendererPreset.CPU_READBACK.configuration().copyBuilder()
                .denoising(DenoisingOptions.recommended())
                .rayTracingOptimizations(RayTracingOptimizationOptions.builder()
                        .memoryOptimization(RendererFeaturePreference.PREFERRED)
                        .build())
                .build();
        NvidiaStreamlineRuntime.Preflight preflight = readyPreflight(
                NvidiaStreamlineRuntime.Feature.DLSS,
                NvidiaStreamlineRuntime.Feature.NIS
        );
        Map<Technology, RenderingFeatureCapabilities.Entry> loaderFailure = requirements(
                requested,
                new NvidiaNativeBridge.Probe(false, 0, "native library missing"),
                preflight
        );
        requireStatus(loaderFailure, Technology.RAY_TRACING_DENOISING, Status.BLOCKED);
        requireStatus(
                loaderFailure,
                Technology.ACCELERATION_STRUCTURE_MEMORY_OPTIMIZATION,
                Status.BLOCKED
        );

        Map<Technology, RenderingFeatureCapabilities.Entry> unsupported = requirements(
                requested,
                new NvidiaNativeBridge.Probe(true, 0, "native bridge loaded without optional SDKs"),
                preflight
        );
        requireStatus(unsupported, Technology.RAY_TRACING_DENOISING, Status.NOT_SUPPORTED);
        requireStatus(
                unsupported,
                Technology.ACCELERATION_STRUCTURE_MEMORY_OPTIMIZATION,
                Status.NOT_SUPPORTED
        );
    }

    private static void streamlinePreflightSelectsSupportedFallbacks() {
        NvidiaNativeBridge.Probe probe = new NvidiaNativeBridge.Probe(
                true, ALL_NATIVE_CAPABILITIES, "native SDKs loaded"
        );
        RendererConfig generationConfiguration = generatedMultiFramePresentationConfiguration();
        Map<Technology, RenderingFeatureCapabilities.Entry> blocked = requirements(
                generationConfiguration,
                probe,
                new NvidiaStreamlineRuntime.Preflight(false, "Streamline plugin missing", Map.of())
        );
        requireStatus(blocked, Technology.TEMPORAL_SUPER_RESOLUTION, Status.BLOCKED);
        requireStatus(blocked, Technology.SPATIAL_UPSCALING, Status.BLOCKED);
        requireStatus(blocked, Technology.FRAME_GENERATION, Status.BLOCKED);
        requireStatus(blocked, Technology.MULTI_FRAME_GENERATION, Status.BLOCKED);

        Map<Technology, RenderingFeatureCapabilities.Entry> spatialOnly = requirements(
                generationConfiguration,
                probe,
                readyPreflight(NvidiaStreamlineRuntime.Feature.NIS)
        );
        requireStatus(spatialOnly, Technology.TEMPORAL_SUPER_RESOLUTION, Status.NOT_SUPPORTED);
        requireStatus(spatialOnly, Technology.SPATIAL_UPSCALING, Status.AVAILABLE);
        requireStatus(spatialOnly, Technology.FRAME_GENERATION, Status.NOT_SUPPORTED);
        requireStatus(spatialOnly, Technology.LOW_LATENCY_MARKERS, Status.NOT_SUPPORTED);
        requireStatus(spatialOnly, Technology.MULTI_FRAME_GENERATION, Status.NOT_SUPPORTED);
    }

    private static void deviceHandoffPrunesUnsupportedGenerationFamilies() {
        RendererConfig generationConfiguration = generatedMultiFramePresentationConfiguration();
        RenderingFeatureCapabilities.Builder supported = RenderingFeatureCapabilities.builder();
        NvidiaTechnologyCapabilities.refineDeviceSupport(
                supported,
                generationConfiguration,
                Set.of(
                        NvidiaStreamlineRuntime.Feature.DLSS,
                        NvidiaStreamlineRuntime.Feature.NIS,
                        NvidiaStreamlineRuntime.Feature.DLSS_FRAME_GENERATION,
                        NvidiaStreamlineRuntime.Feature.REFLEX,
                        NvidiaStreamlineRuntime.Feature.PCL
                )
        );
        RenderingFeatureCapabilities supportedSnapshot = supported.build();
        requireStatus(supportedSnapshot, Technology.FRAME_GENERATION, Status.AVAILABLE);
        requireStatus(supportedSnapshot, Technology.MULTI_FRAME_GENERATION, Status.AVAILABLE);
        requireStatus(supportedSnapshot, Technology.LOW_LATENCY_MARKERS, Status.AVAILABLE);

        RenderingFeatureCapabilities.Builder rejected = RenderingFeatureCapabilities.builder();
        NvidiaTechnologyCapabilities.refineDeviceSupport(
                rejected,
                generationConfiguration,
                Set.of(NvidiaStreamlineRuntime.Feature.DLSS, NvidiaStreamlineRuntime.Feature.NIS)
        );
        RenderingFeatureCapabilities rejectedSnapshot = rejected.build();
        requireStatus(rejectedSnapshot, Technology.TEMPORAL_SUPER_RESOLUTION, Status.AVAILABLE);
        requireStatus(rejectedSnapshot, Technology.SPATIAL_UPSCALING, Status.AVAILABLE);
        requireStatus(rejectedSnapshot, Technology.FRAME_GENERATION, Status.NOT_SUPPORTED);
        requireStatus(rejectedSnapshot, Technology.MULTI_FRAME_GENERATION, Status.NOT_SUPPORTED);
        requireStatus(rejectedSnapshot, Technology.LOW_LATENCY_MARKERS, Status.NOT_SUPPORTED);
    }

    private static void explicitTwoTimesGenerationDoesNotClaimMultiFrameGeneration() {
        FrameGenerationOptions twoTimes = FrameGenerationOptions.builder()
                .preference(RendererFeaturePreference.PREFERRED)
                .mode(FrameGenerationOptions.Mode.FRAME_GENERATION)
                .multiplier(FrameGenerationOptions.Multiplier.TWO_X)
                .fallback(FrameGenerationOptions.Fallback.PRESENT_NATIVE_FRAMES)
                .build();
        RendererConfig configuration = RendererPreset.CPU_READBACK.configuration().copyBuilder()
                .frameGeneration(twoTimes)
                .build();
        RenderingFeatureCapabilities.Builder target = RenderingFeatureCapabilities.builder();
        NvidiaTechnologyCapabilities.refineDeviceSupport(
                target,
                configuration,
                Set.of(
                        NvidiaStreamlineRuntime.Feature.DLSS,
                        NvidiaStreamlineRuntime.Feature.NIS,
                        NvidiaStreamlineRuntime.Feature.DLSS_FRAME_GENERATION,
                        NvidiaStreamlineRuntime.Feature.REFLEX,
                        NvidiaStreamlineRuntime.Feature.PCL
                )
        );
        RenderingFeatureCapabilities capabilities = target.build();
        requireStatus(capabilities, Technology.FRAME_GENERATION, Status.AVAILABLE);
        requireStatus(capabilities, Technology.MULTI_FRAME_GENERATION, Status.DISABLED);
    }

    private static void runtimeExecutionEvidenceActivatesOnlyTheExecutedTechnology() {
        NvidiaRuntimeCapabilities.Denoising denoising = new NvidiaRuntimeCapabilities.Denoising(
                DenoisingOptions.recommended(),
                true,
                false,
                null,
                new VulkanFeatureRuntimeState.Snapshot(
                        VulkanFeatureRuntimeState.Status.ACTIVE,
                        "nvidia.nrd",
                        "NRD post-trace dispatch recorded"
                )
        );
        NvidiaRuntimeCapabilities.Reconstruction reconstruction =
                new NvidiaRuntimeCapabilities.Reconstruction(
                        FrameReconstructionOptions.recommended(),
                        NvidiaStreamlineRuntime.Feature.NIS,
                        true,
                        false,
                        "DLSS evaluate failed; switched to NIS"
                );
        RenderingFeatureCapabilities capabilities = NvidiaRuntimeCapabilities.project(
                runtimeBaseline(),
                denoising,
                reconstruction,
                inactiveGeneration(),
                true
        );

        requireStatus(capabilities, Technology.RAY_TRACING_DENOISING, Status.ACTIVE);
        requireStatus(capabilities, Technology.SPATIAL_UPSCALING, Status.ACTIVE);
        requireStatus(capabilities, Technology.TEMPORAL_SUPER_RESOLUTION, Status.BLOCKED);
        requireStatus(
                capabilities,
                Technology.ACCELERATION_STRUCTURE_MEMORY_OPTIMIZATION,
                Status.ACTIVE
        );
        require(capabilities.feature(RenderingFeatureCapabilities.Feature.FRAME_RECONSTRUCTION).status()
                        == Status.FALLBACK,
                "NIS execution must remain a feature-family fallback while its technology is ACTIVE");

        FrameReconstructionOptions explicitNis = FrameReconstructionOptions.builder()
                .preference(RendererFeaturePreference.PREFERRED)
                .mode(FrameReconstructionOptions.Mode.SPATIAL_UPSCALING)
                .build();
        RenderingFeatureCapabilities nativeNis = NvidiaRuntimeCapabilities.project(
                runtimeBaseline(),
                inactiveDenoising(),
                new NvidiaRuntimeCapabilities.Reconstruction(
                        explicitNis,
                        NvidiaStreamlineRuntime.Feature.NIS,
                        true,
                        false,
                        null
                ),
                inactiveGeneration(),
                false
        );
        requireStatus(nativeNis, Technology.SPATIAL_UPSCALING, Status.ACTIVE);
        require(nativeNis.feature(RenderingFeatureCapabilities.Feature.FRAME_RECONSTRUCTION).status()
                        == Status.ACTIVE,
                "explicit NIS execution is the requested implementation, not a fallback");
    }

    private static void generatedPresentationDistinguishesFrameGenerationFromMultiFrameGeneration() {
        FrameGenerationOptions twoTimesOptions = FrameGenerationOptions.builder()
                .preference(RendererFeaturePreference.PREFERRED)
                .mode(FrameGenerationOptions.Mode.FRAME_GENERATION)
                .multiplier(FrameGenerationOptions.Multiplier.TWO_X)
                .fallback(FrameGenerationOptions.Fallback.PRESENT_NATIVE_FRAMES)
                .build();
        RenderingFeatureCapabilities twoTimes = NvidiaRuntimeCapabilities.project(
                runtimeBaseline(twoTimesOptions),
                inactiveDenoising(),
                inactiveReconstruction(),
                activeGeneration(twoTimesOptions, new NvidiaStreamlineFrameGenerationRuntime.Stats(
                        1L, 1L, 2L, 1L, 2, 1, 0, 0L, 1, 1, 1,
                        0L, 1L, 1L, 1L, 1L, 1L, true
                )),
                false
        );
        requireStatus(twoTimes, Technology.FRAME_GENERATION, Status.ACTIVE);
        requireStatus(twoTimes, Technology.MULTI_FRAME_GENERATION, Status.DISABLED);
        requireStatus(twoTimes, Technology.LOW_LATENCY_MARKERS, Status.ACTIVE);

        NvidiaStreamlineFrameGenerationRuntime.Stats staleAfterQueryFailure =
                new NvidiaStreamlineFrameGenerationRuntime.Stats(
                        2L, 1L, 2L, 1L, 2, 1, 0, 1L, 1, 1, 1,
                        0L, 2L, 1L, 2L, 1L, 1L, false
                );
        require(!staleAfterQueryFailure.active(),
                "a failed latest DLSS-G query must invalidate older generated-output activation");

        FrameGenerationOptions multiFrameOptions = multiFrameGenerationOptions();
        RenderingFeatureCapabilities armedMultiFrame = NvidiaRuntimeCapabilities.project(
                runtimeBaseline(multiFrameOptions),
                inactiveDenoising(),
                inactiveReconstruction(),
                activeGeneration(multiFrameOptions, new NvidiaStreamlineFrameGenerationRuntime.Stats(
                        1L, 2L, 2L, 1L, 1, 5, 0, 0L, 1, 3, 3,
                        0L, 2L, 1L, 1L, 1L, 1L, true
                )),
                false
        );
        requireStatus(armedMultiFrame, Technology.FRAME_GENERATION, Status.AVAILABLE);
        requireStatus(armedMultiFrame, Technology.MULTI_FRAME_GENERATION, Status.AVAILABLE);

        RenderingFeatureCapabilities fourTimes = NvidiaRuntimeCapabilities.project(
                runtimeBaseline(multiFrameOptions),
                inactiveDenoising(),
                inactiveReconstruction(),
                activeGeneration(multiFrameOptions, new NvidiaStreamlineFrameGenerationRuntime.Stats(
                        1L, 1L, 4L, 3L, 4, 5, 0, 0L, 3, 3, 3,
                        0L, 1L, 1L, 1L, 1L, 1L, true
                )),
                false
        );
        requireStatus(fourTimes, Technology.FRAME_GENERATION, Status.AVAILABLE);
        requireStatus(fourTimes, Technology.MULTI_FRAME_GENERATION, Status.ACTIVE);

        RenderingFeatureCapabilities blocked = NvidiaRuntimeCapabilities.project(
                runtimeBaseline(multiFrameOptions),
                inactiveDenoising(),
                inactiveReconstruction(),
                new NvidiaRuntimeCapabilities.FrameGeneration(
                        multiFrameOptions,
                        true,
                        false,
                        true,
                        "generated presentation failed",
                        emptyStats()
                ),
                false
        );
        requireStatus(blocked, Technology.FRAME_GENERATION, Status.BLOCKED);
        requireStatus(blocked, Technology.MULTI_FRAME_GENERATION, Status.BLOCKED);
        requireStatus(blocked, Technology.LOW_LATENCY_MARKERS, Status.BLOCKED);
    }

    private static RenderingFeatureCapabilities runtimeBaseline() {
        return runtimeBaseline(FrameGenerationOptions.recommended());
    }

    private static RenderingFeatureCapabilities runtimeBaseline(FrameGenerationOptions generation) {
        RenderingFeatureCapabilities.Entry available = RenderingFeatureCapabilities.Entry.of(
                Status.AVAILABLE, "pending", "device negotiation succeeded; awaiting execution"
        );
        RenderingFeatureCapabilities.Builder target = RenderingFeatureCapabilities.builder()
                .feature(RenderingFeatureCapabilities.Feature.FRAME_RECONSTRUCTION, available)
                .feature(RenderingFeatureCapabilities.Feature.FRAME_GENERATION, available)
                .feature(RenderingFeatureCapabilities.Feature.DENOISING, available)
                .feature(RenderingFeatureCapabilities.Feature.MEMORY_OPTIMIZATION, available)
                .technology(Technology.TEMPORAL_SUPER_RESOLUTION, available)
                .technology(Technology.SPATIAL_UPSCALING, available)
                .technology(Technology.RAY_TRACING_DENOISING, available)
                .technology(Technology.ACCELERATION_STRUCTURE_MEMORY_OPTIMIZATION, available);
        if (generation.preference().requested()) {
            target.technology(Technology.FRAME_GENERATION, available)
                    .technology(Technology.LOW_LATENCY_MARKERS, available);
            if (NvidiaTechnologyCapabilities.multiFrameRequested(generation)) {
                target.technology(Technology.MULTI_FRAME_GENERATION, available);
            }
        }
        return target.build();
    }

    private static NvidiaRuntimeCapabilities.Denoising inactiveDenoising() {
        return new NvidiaRuntimeCapabilities.Denoising(
                DenoisingOptions.recommended(),
                false,
                false,
                null,
                new VulkanFeatureRuntimeState.Snapshot(
                        VulkanFeatureRuntimeState.Status.AVAILABLE,
                        "nvidia.nrd",
                        "awaiting first dispatch"
                )
        );
    }

    private static NvidiaRuntimeCapabilities.Reconstruction inactiveReconstruction() {
        return new NvidiaRuntimeCapabilities.Reconstruction(
                FrameReconstructionOptions.recommended(), null, false, false, null
        );
    }

    private static NvidiaRuntimeCapabilities.FrameGeneration inactiveGeneration() {
        return new NvidiaRuntimeCapabilities.FrameGeneration(
                FrameGenerationOptions.recommended(), false, false, false, null, emptyStats()
        );
    }

    private static NvidiaRuntimeCapabilities.FrameGeneration activeGeneration(
            NvidiaStreamlineFrameGenerationRuntime.Stats stats
    ) {
        return activeGeneration(FrameGenerationOptions.recommended(), stats);
    }

    private static NvidiaRuntimeCapabilities.FrameGeneration activeGeneration(
            FrameGenerationOptions options,
            NvidiaStreamlineFrameGenerationRuntime.Stats stats
    ) {
        return new NvidiaRuntimeCapabilities.FrameGeneration(
                options, true, true, false, null, stats
        );
    }

    private static NvidiaStreamlineFrameGenerationRuntime.Stats emptyStats() {
        return NvidiaStreamlineFrameGenerationRuntime.emptyStats();
    }

    private static Map<Technology, RenderingFeatureCapabilities.Entry> requirements(
            RendererConfig configuration,
            NvidiaNativeBridge.Probe probe,
            NvidiaStreamlineRuntime.Preflight preflight
    ) {
        VulkanFeatureRequirements.Builder target = VulkanFeatureRequirements.builder();
        NvidiaTechnologyCapabilities.declareRequirements(target, configuration, probe, preflight);
        return target.build().technologies();
    }

    private static NvidiaStreamlineRuntime.Preflight readyPreflight(
            NvidiaStreamlineRuntime.Feature... features
    ) {
        EnumMap<NvidiaStreamlineRuntime.Feature, NvidiaStreamlineRuntime.Requirements> requirements =
                new EnumMap<>(NvidiaStreamlineRuntime.Feature.class);
        NvidiaStreamlineRuntime.Requirements empty = new NvidiaStreamlineRuntime.Requirements(
                Set.of(), Set.of(), Set.of(), Set.of(), VulkanQueueRequirements.NONE,
                new NvidiaStreamlineRuntime.Version(2, 13, 0)
        );
        for (NvidiaStreamlineRuntime.Feature feature : features) requirements.put(feature, empty);
        return new NvidiaStreamlineRuntime.Preflight(true, "Streamline preflight succeeded", requirements);
    }

    private static RendererConfig generatedMultiFramePresentationConfiguration() {
        return RendererPreset.CPU_READBACK.configuration().copyBuilder()
                .frameReconstruction(FrameReconstructionOptions.recommended())
                .frameGeneration(multiFrameGenerationOptions())
                .build();
    }

    private static FrameGenerationOptions multiFrameGenerationOptions() {
        return FrameGenerationOptions.builder()
                .preference(RendererFeaturePreference.PREFERRED)
                .mode(FrameGenerationOptions.Mode.MULTI_FRAME_GENERATION)
                .multiplier(FrameGenerationOptions.Multiplier.FOUR_X)
                .fallback(FrameGenerationOptions.Fallback.PRESENT_NATIVE_FRAMES)
                .build();
    }

    private static void requireStatus(
            Map<Technology, RenderingFeatureCapabilities.Entry> technologies,
            Technology technology,
            Status expected
    ) {
        RenderingFeatureCapabilities.Entry entry = technologies.get(technology);
        require(entry != null, "missing technology declaration: " + technology);
        require(entry.status() == expected,
                technology + " expected " + expected + " but was " + entry);
    }

    private static void requireStatus(
            RenderingFeatureCapabilities capabilities,
            Technology technology,
            Status expected
    ) {
        RenderingFeatureCapabilities.Entry entry = capabilities.technology(technology);
        require(entry.status() == expected,
                technology + " expected " + expected + " but was " + entry);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
