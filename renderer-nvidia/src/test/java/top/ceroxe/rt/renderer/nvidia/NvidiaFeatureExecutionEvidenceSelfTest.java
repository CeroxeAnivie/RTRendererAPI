package top.ceroxe.rt.renderer.nvidia;

import top.ceroxe.rt.renderer.api.DenoisingOptions;
import top.ceroxe.rt.renderer.api.FrameGenerationEvidence;
import top.ceroxe.rt.renderer.api.FrameGenerationOptions;
import top.ceroxe.rt.renderer.api.FrameReconstructionOptions;
import top.ceroxe.rt.renderer.api.LowLatencyOptions;
import top.ceroxe.rt.renderer.api.RayTracingOptimizationOptions;
import top.ceroxe.rt.renderer.api.RendererFeaturePreference;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Status;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Technology;
import top.ceroxe.rt.renderer.api.TechnologyExecutionEvidence;

import java.util.OptionalInt;

/** Pure submission/presentation evidence tests; no Vulkan device or native SDK is required. */
public final class NvidiaFeatureExecutionEvidenceSelfTest {
    private NvidiaFeatureExecutionEvidenceSelfTest() {
    }

    public static void main(String[] args) {
        recordedWorkRequiresGpuCompletion();
        committedFeatureIdentityIsImmutable();
        inFlightFeatureWorkCompletesBySequence();
        lowLatencyRequiresSuccessfulPresent();
        skippedAndFailedPresentsDoNotLeakOrActivate();
        sequenceConflictsAreRejected();
        primaryReconstructionRequiresGpuCompletion();
        generationRequiresObservedOutput();
        multiFrameGenerationRequiresMultiFrameOutput();
        rtxmuUsesProviderWorkSequenceDomain();
        System.out.println("NvidiaFeatureExecutionEvidenceSelfTest passed");
    }

    private static void recordedWorkRequiresGpuCompletion() {
        NvidiaFeatureExecutionEvidence evidence = new NvidiaFeatureExecutionEvidence();
        evidence.recordDenoising(1L);
        evidence.recordReconstruction(1L, NvidiaStreamlineRuntime.Feature.DLSS);
        evidence.discardSubmission(1L);
        evidence.commitSubmission(1L);
        NvidiaFeatureExecutionEvidence.SubmissionWork empty = evidence.completeSubmission(1L);
        require(!empty.denoisingRecorded() && empty.reconstructionFeature() == null,
                "discarded recording must not become execution evidence");

        evidence.recordDenoising(2L);
        evidence.recordReconstruction(2L, NvidiaStreamlineRuntime.Feature.DLSS);
        evidence.commitSubmission(2L);
        NvidiaFeatureExecutionEvidence.SubmissionWork completed = evidence.completeSubmission(2L);
        require(completed.denoisingRecorded()
                        && completed.reconstructionFeature() == NvidiaStreamlineRuntime.Feature.DLSS,
                "completed NRD and DLSS work must be published together");
        require(!evidence.completeSubmission(2L).denoisingRecorded(),
                "GPU completion evidence must be consumed exactly once");
    }

    private static void committedFeatureIdentityIsImmutable() {
        NvidiaFeatureExecutionEvidence evidence = new NvidiaFeatureExecutionEvidence();
        evidence.recordReconstruction(3L, NvidiaStreamlineRuntime.Feature.NIS);
        evidence.commitSubmission(3L);
        require(evidence.completeSubmission(3L).reconstructionFeature()
                        == NvidiaStreamlineRuntime.Feature.NIS,
                "completion must return the implementation recorded by that frame");
    }

    private static void inFlightFeatureWorkCompletesBySequence() {
        NvidiaFeatureExecutionEvidence evidence = new NvidiaFeatureExecutionEvidence();
        evidence.recordDenoising(10L);
        evidence.commitSubmission(10L);
        evidence.recordReconstruction(11L, NvidiaStreamlineRuntime.Feature.NIS);
        evidence.commitSubmission(11L);

        NvidiaFeatureExecutionEvidence.SubmissionWork later = evidence.completeSubmission(11L);
        NvidiaFeatureExecutionEvidence.SubmissionWork earlier = evidence.completeSubmission(10L);
        require(later.reconstructionFeature() == NvidiaStreamlineRuntime.Feature.NIS
                        && !later.denoisingRecorded(),
                "out-of-order slot polling mixed later reconstruction evidence");
        require(earlier.denoisingRecorded() && earlier.reconstructionFeature() == null,
                "out-of-order slot polling lost earlier NRD evidence");
    }

    private static void lowLatencyRequiresSuccessfulPresent() {
        NvidiaFeatureExecutionEvidence evidence = new NvidiaFeatureExecutionEvidence();
        evidence.recordLowLatencyRenderEnd(4L);
        evidence.commitSubmission(4L);
        require(!evidence.lowLatencyPresentCommitted(),
                "RenderSubmitEnd without PresentEnd must remain non-active");
        evidence.observePresent(4L, true);
        require(evidence.lowLatencyPresentCommitted(),
                "successful present must close committed render-marker evidence");
    }

    private static void skippedAndFailedPresentsDoNotLeakOrActivate() {
        NvidiaFeatureExecutionEvidence failed = new NvidiaFeatureExecutionEvidence();
        failed.recordLowLatencyRenderEnd(5L);
        failed.commitSubmission(5L);
        failed.observePresent(5L, false);
        require(!failed.lowLatencyPresentCommitted(), "failed present must not activate low latency");

        NvidiaFeatureExecutionEvidence skipped = new NvidiaFeatureExecutionEvidence();
        skipped.recordLowLatencyRenderEnd(6L);
        skipped.commitSubmission(6L);
        skipped.recordLowLatencyRenderEnd(7L);
        skipped.commitSubmission(7L);
        skipped.observePresent(7L, true);
        require(skipped.lowLatencyPresentCommitted(),
                "later successful present must retire skipped older marker evidence");
    }

    private static void sequenceConflictsAreRejected() {
        NvidiaFeatureExecutionEvidence evidence = new NvidiaFeatureExecutionEvidence();
        evidence.recordDenoising(8L);
        expectFailure(() -> evidence.recordReconstruction(9L, NvidiaStreamlineRuntime.Feature.DLSS));
        evidence.discardSubmission(8L);
        evidence.discardSubmission(8L);
    }

    private static void primaryReconstructionRequiresGpuCompletion() {
        requirePrimaryReconstructionActivation(
                FrameReconstructionOptions.Mode.SUPER_RESOLUTION,
                NvidiaStreamlineRuntime.Feature.DLSS,
                Technology.TEMPORAL_SUPER_RESOLUTION,
                "nvidia.streamline.dlss-sr"
        );
        requirePrimaryReconstructionActivation(
                FrameReconstructionOptions.Mode.SPATIAL_UPSCALING,
                NvidiaStreamlineRuntime.Feature.NIS,
                Technology.SPATIAL_UPSCALING,
                "nvidia.streamline.nis"
        );
    }

    private static void requirePrimaryReconstructionActivation(
            FrameReconstructionOptions.Mode mode,
            NvidiaStreamlineRuntime.Feature feature,
            Technology technology,
            String implementation
    ) {
        FrameReconstructionOptions options = FrameReconstructionOptions.builder()
                .preference(RendererFeaturePreference.PREFERRED)
                .mode(mode)
                .build();
        RenderingFeatureCapabilities.Entry available = RenderingFeatureCapabilities.Entry.of(
                Status.AVAILABLE, implementation, "provider is ready"
        );
        RenderingFeatureCapabilities capabilities = RenderingFeatureCapabilities.builder()
                .feature(RenderingFeatureCapabilities.Feature.FRAME_RECONSTRUCTION, available)
                .technology(technology, available)
                .build();
        NvidiaFeatureExecutionEvidence execution = new NvidiaFeatureExecutionEvidence();

        TechnologyExecutionEvidence beforeCompletion = projectReconstruction(
                capabilities, options, execution
        );
        require(beforeCompletion.technology(technology).health()
                        == TechnologyExecutionEvidence.Health.READY,
                "selected reconstruction must remain READY before submission evidence");

        execution.recordReconstruction(9L, feature);
        execution.commitSubmission(9L);
        TechnologyExecutionEvidence submitted = projectReconstruction(
                capabilities, options, execution
        );
        require(submitted.technology(technology).health()
                        == TechnologyExecutionEvidence.Health.SUBMITTED,
                "selected reconstruction must remain SUBMITTED before GPU completion");

        execution.completeSubmission(9L);
        TechnologyExecutionEvidence active = projectReconstruction(
                capabilities, options, execution
        );
        require(active.technology(technology).health()
                        == TechnologyExecutionEvidence.Health.ACTIVE,
                implementation + " GPU completion did not activate its selected technology");
    }

    private static TechnologyExecutionEvidence projectReconstruction(
            RenderingFeatureCapabilities capabilities,
            FrameReconstructionOptions options,
            NvidiaFeatureExecutionEvidence execution
    ) {
        return NvidiaTechnologyEvidenceProjector.project(
                capabilities,
                options,
                FrameGenerationOptions.disabled(),
                DenoisingOptions.disabled(),
                LowLatencyOptions.disabled(),
                RayTracingOptimizationOptions.disabled(),
                execution,
                FrameGenerationEvidence.unavailable(),
                0L
        );
    }

    private static void generationRequiresObservedOutput() {
        FrameGenerationOptions options = generationOptions(
                FrameGenerationOptions.Mode.FRAME_GENERATION,
                FrameGenerationOptions.Multiplier.TWO_X
        );
        TechnologyExecutionEvidence withoutOutput = projectGeneration(
                options, generationEvidence(1, 0L, 0), 0L
        );
        require(withoutOutput.technology(Technology.FRAME_GENERATION).health()
                        == TechnologyExecutionEvidence.Health.SUBMITTED,
                "configured FG without generated output must not become active");

        TechnologyExecutionEvidence withOutput = projectGeneration(
                options, generationEvidence(1, 1L, 1), 0L
        );
        TechnologyExecutionEvidence.Entry active = withOutput.technology(Technology.FRAME_GENERATION);
        require(active.health() == TechnologyExecutionEvidence.Health.ACTIVE
                        && active.outputCount() == 1L
                        && active.resetEpoch() == 7L,
                "FG output or reset epoch was not projected from native evidence");
    }

    private static void multiFrameGenerationRequiresMultiFrameOutput() {
        FrameGenerationOptions options = generationOptions(
                FrameGenerationOptions.Mode.MULTI_FRAME_GENERATION,
                FrameGenerationOptions.Multiplier.THREE_X
        );
        TechnologyExecutionEvidence oneGeneratedFrame = projectGeneration(
                options, generationEvidence(2, 1L, 1), 0L
        );
        require(oneGeneratedFrame.technology(Technology.MULTI_FRAME_GENERATION).health()
                        == TechnologyExecutionEvidence.Health.SUBMITTED,
                "a 2x-sized sample must not activate MFG");
        require(oneGeneratedFrame.technology(Technology.FRAME_GENERATION).health()
                        == TechnologyExecutionEvidence.Health.NEGOTIATED,
                "MFG selection must not activate the mutually exclusive FG technology");

        TechnologyExecutionEvidence multiFrameOutput = projectGeneration(
                options, generationEvidence(2, 2L, 2), 0L
        );
        require(multiFrameOutput.technology(Technology.MULTI_FRAME_GENERATION).health()
                        == TechnologyExecutionEvidence.Health.ACTIVE,
                "observed multi-frame output did not activate MFG");
    }

    private static void rtxmuUsesProviderWorkSequenceDomain() {
        RayTracingOptimizationOptions optimizations = RayTracingOptimizationOptions.builder()
                .memoryOptimization(RendererFeaturePreference.PREFERRED)
                .build();
        TechnologyExecutionEvidence evidence = NvidiaTechnologyEvidenceProjector.project(
                activeCapabilities(),
                FrameReconstructionOptions.disabled(),
                FrameGenerationOptions.disabled(),
                DenoisingOptions.disabled(),
                LowLatencyOptions.disabled(),
                optimizations,
                new NvidiaFeatureExecutionEvidence(),
                FrameGenerationEvidence.unavailable(),
                3L
        );
        TechnologyExecutionEvidence.Entry rtxmu = evidence.technology(
                Technology.ACCELERATION_STRUCTURE_MEMORY_OPTIMIZATION
        );
        require(rtxmu.health() == TechnologyExecutionEvidence.Health.ACTIVE
                        && rtxmu.sequenceDomain()
                        == TechnologyExecutionEvidence.SequenceDomain.PROVIDER_WORK
                        && rtxmu.firstSequence().orElseThrow() == 1L
                        && rtxmu.lastSequence().orElseThrow() == 3L,
                "RTXMU build ordinals were exposed as renderer-frame sequences");
    }

    private static TechnologyExecutionEvidence projectGeneration(
            FrameGenerationOptions options,
            FrameGenerationEvidence generation,
            long rtxmuBuilds
    ) {
        return NvidiaTechnologyEvidenceProjector.project(
                activeCapabilities(),
                FrameReconstructionOptions.disabled(),
                options,
                DenoisingOptions.disabled(),
                LowLatencyOptions.disabled(),
                RayTracingOptimizationOptions.disabled(),
                new NvidiaFeatureExecutionEvidence(),
                generation,
                rtxmuBuilds
        );
    }

    private static RenderingFeatureCapabilities activeCapabilities() {
        RenderingFeatureCapabilities.Entry active = RenderingFeatureCapabilities.Entry.of(
                Status.ACTIVE, "nvidia.runtime", "runtime owns executable resources"
        );
        return RenderingFeatureCapabilities.builder()
                .feature(RenderingFeatureCapabilities.Feature.FRAME_GENERATION, active)
                .feature(RenderingFeatureCapabilities.Feature.MEMORY_OPTIMIZATION, active)
                .feature(RenderingFeatureCapabilities.Feature.LOW_LATENCY, active)
                .technology(Technology.FRAME_GENERATION, active)
                .technology(Technology.MULTI_FRAME_GENERATION, active)
                .technology(Technology.LOW_LATENCY_MARKERS, active)
                .technology(Technology.ACCELERATION_STRUCTURE_MEMORY_OPTIMIZATION, active)
                .build();
    }

    private static FrameGenerationOptions generationOptions(
            FrameGenerationOptions.Mode mode,
            FrameGenerationOptions.Multiplier multiplier
    ) {
        return FrameGenerationOptions.builder()
                .preference(RendererFeaturePreference.PREFERRED)
                .mode(mode)
                .multiplier(multiplier)
                .fallback(FrameGenerationOptions.Fallback.PRESENT_NATIVE_FRAMES)
                .build();
    }

    private static FrameGenerationEvidence generationEvidence(
            int requestedGeneratedFrames,
            long generatedFrames,
            int maximumGeneratedFrames
    ) {
        FrameGenerationEvidence.Builder builder = FrameGenerationEvidence.builder()
                .reported(true)
                .requestedGeneratedFramesPerNativeFrame(requestedGeneratedFrames)
                .lastSubmittedGeneratedFramesPerNativeFrame(requestedGeneratedFrames)
                .configuredGeneratedFramesPerNativeFrame(requestedGeneratedFrames)
                .proxyPresentCalls(1L)
                .stateSamples(1L)
                .stateQueryCalls(1L)
                .totalFramesActuallyPresented(1L + generatedFrames)
                .generatedFramesActuallyPresented(generatedFrames)
                .lastFramesActuallyPresented(1 + maximumGeneratedFrames)
                .maximumSupportedGeneratedFramesPerNativeFrame(3)
                .maximumGeneratedFramesObservedPerSample(maximumGeneratedFrames)
                .latestNativeStatus(OptionalInt.of(0))
                .proxyPresentSequenceRange(10L, 10L)
                .resetEpoch(7L);
        if (generatedFrames > 0L) builder.lastGeneratedObservationSequence(10L);
        return builder.build();
    }

    private static void expectFailure(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected sequence conflict");
        } catch (IllegalStateException expected) {
            // Expected contract failure.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
