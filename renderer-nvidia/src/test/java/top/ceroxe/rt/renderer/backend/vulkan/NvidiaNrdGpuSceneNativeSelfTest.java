package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.api.DenoisingOptions;
import top.ceroxe.rt.renderer.api.FrameOutputFormat;
import top.ceroxe.rt.renderer.api.FrameGenerationOptions;
import top.ceroxe.rt.renderer.api.HistoryResetReason;
import top.ceroxe.rt.renderer.api.RayTracingRendererConfig;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.RendererFeaturePreference;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Feature;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Status;
import top.ceroxe.rt.renderer.nvidia.WindowsChildProcessIsolation;
import top.ceroxe.rt.renderer.rt.device.VulkanMemoryBudgetSnapshot;
import top.ceroxe.rt.renderer.rt.device.interop.Win32HandleSupport;

/** Exercises NRD REBLUR across continuous camera motion and explicit history discontinuities. */
public final class NvidiaNrdGpuSceneNativeSelfTest {
    private static final long MAX_RECOVERY_VRAM_DRIFT_BYTES = 128L * 1024L * 1024L;

    private NvidiaNrdGpuSceneNativeSelfTest() {
    }

    public static void main(String[] arguments) throws Exception {
        WindowsChildProcessIsolation.preventGradlePipeInheritance();
        int handlesBefore = Win32HandleSupport.processHandleCount();
        boolean dlaaCombo = arguments.length == 1 && ("combo".equalsIgnoreCase(arguments[0])
                || "combo-dlaa".equalsIgnoreCase(arguments[0]));
        boolean dlssCombo = arguments.length == 1 && "combo-dlss".equalsIgnoreCase(arguments[0]);
        boolean combo = dlaaCombo || dlssCombo;
        if (arguments.length > 1 || (arguments.length == 1 && !combo)) {
            throw new IllegalArgumentException("usage: NvidiaNrdGpuSceneNativeSelfTest [combo-dlaa|combo-dlss]");
        }
        VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
        NvidiaGpuSceneNativeTestSupport.require(capability.hardwareRayTracingReady(),
                "NRD frame gate requires Vulkan RT: " + capability.summary());
        NvidiaGpuSceneNativeTestSupport.require(capability.preferredDevice().linearHdrRgba16fOutput(),
                "NRD frame gate requires exportable RGBA16F output on the selected device");

        RayTracingRendererConfig configuration = RayTracingRendererConfig.builder()
                .maxFramesInFlight(2)
                .frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F)
                .frameGeneration(FrameGenerationOptions.disabled())
                .frameReconstruction(combo
                        ? top.ceroxe.rt.renderer.api.FrameReconstructionOptions.builder()
                        .preference(RendererFeaturePreference.PREFERRED)
                        .mode(dlssCombo
                                ? top.ceroxe.rt.renderer.api.FrameReconstructionOptions.Mode.SUPER_RESOLUTION
                                : top.ceroxe.rt.renderer.api.FrameReconstructionOptions.Mode.NATIVE_ANTI_ALIASING)
                        .quality(top.ceroxe.rt.renderer.api.FrameReconstructionOptions.Quality.BALANCED)
                        .fallback(top.ceroxe.rt.renderer.api.FrameReconstructionOptions.Fallback.NONE)
                        .build()
                        : top.ceroxe.rt.renderer.api.FrameReconstructionOptions.disabled())
                .denoising(DenoisingOptions.builder()
                        .preference(RendererFeaturePreference.REQUIRED)
                        .strategy(DenoisingOptions.Strategy.BALANCED)
                        .builtInTemporalFallback(false)
                        .build())
                .validationEnabled(false)
                .gpuTimingsEnabled(false)
                .build();
        RenderedSequence temporal = renderTemporalSequence(capability, configuration, combo, dlssCombo);
        int handlesAfterWarmSequence = Win32HandleSupport.processHandleCount();
        VulkanMemoryBudgetSnapshot recoveredBudget = combo
                ? unavailableBudget()
                : verifyRecoveredDeviceSession(capability, configuration);
        NvidiaGpuSceneNativeTestSupport.require(temporal.cameraCutResetDelta() <= 1.0,
                "camera-cut reset retained stale NRD history: meanRgbDelta="
                        + temporal.cameraCutResetDelta());
        NvidiaGpuSceneNativeTestSupport.require(temporal.resizeRestoreDelta() <= 1.0,
                "resize recreation retained stale NRD history: meanRgbDelta="
                        + temporal.resizeRestoreDelta());
        NvidiaGpuSceneNativeTestSupport.require(temporal.sceneResetDelta() <= 1.0,
                "scene-discontinuity reset retained stale NRD history: meanRgbDelta="
                        + temporal.sceneResetDelta());
        int handlesAfter = Win32HandleSupport.processHandleCount();
        if (!combo && handlesAfterWarmSequence >= 0 && handlesAfter >= 0) {
            NvidiaGpuSceneNativeTestSupport.require(
                    handlesAfter <= handlesAfterWarmSequence + 24,
                    "bounded NRD recovery session leaked process handles: warm="
                            + handlesAfterWarmSequence + ", afterRecovery=" + handlesAfter
            );
        }
        if (!combo && temporal.memoryBudget().driverBudgetAvailable()
                && recoveredBudget.driverBudgetAvailable()) {
            long warmUsage = temporal.memoryBudget().deviceLocalUsageBytes();
            long recoveredUsage = recoveredBudget.deviceLocalUsageBytes();
            NvidiaGpuSceneNativeTestSupport.require(
                    recoveredUsage <= warmUsage + MAX_RECOVERY_VRAM_DRIFT_BYTES,
                    "bounded NRD recovery exceeded device-local memory drift: warm="
                            + warmUsage + ", recovered=" + recoveredUsage
                            + ", limit=" + MAX_RECOVERY_VRAM_DRIFT_BYTES
            );
        }
        System.out.println("NvidiaNrdGpuSceneNativeSelfTest passed: device="
                + capability.preferredDevice().name()
                + ", orderedNrdReconstruction=" + (dlssCombo ? "DLSS_SR" : dlaaCombo ? "DLAA" : "none")
                + ", movingFrames=" + temporal.movingFrames()
                + ", maxComposeTraceDelta=" + temporal.maxComposeTraceDelta()
                + ", maxFrameDelta=" + temporal.maxFrameDelta()
                + ", dynamicMotionDelta=" + temporal.dynamicMotionDelta()
                + ", disocclusionRestoreDelta=" + temporal.disocclusionRestoreDelta()
                + ", stableHistoryFrames=" + temporal.stableHistoryFrames()
                + ", maxStableHistoryDelta=" + temporal.maxStableHistoryDelta()
                + ", cutResetDelta=" + temporal.cameraCutResetDelta()
                + ", resizeRestoreDelta=" + temporal.resizeRestoreDelta()
                + ", sceneResetDelta=" + temporal.sceneResetDelta()
                + ", processHandlesBefore=" + handlesBefore
                + ", processHandlesAfterWarmSequence=" + handlesAfterWarmSequence
                + ", processHandlesAfter=" + handlesAfter
                + ", deviceLocalUsageAfterWarmSequence="
                + temporal.memoryBudget().deviceLocalUsageBytes()
                + ", deviceLocalUsageAfterRecovery=" + recoveredBudget.deviceLocalUsageBytes()
                + ", status=ACTIVE");
    }

    private static VulkanMemoryBudgetSnapshot verifyRecoveredDeviceSession(
            VulkanRtCapabilityProbe.Result capability,
            RayTracingRendererConfig configuration
    ) throws Exception {
        VulkanGpuSceneRenderingSession session = VulkanGpuSceneRenderingSession.open(
                capability, configuration, RendererRtDiagnostics.noop()
        );
        VulkanRendererHost renderer = new VulkanRendererHost(configuration, session);
        try {
            NvidiaGpuSceneNativeTestSupport.require(
                    session.featureCapabilities().feature(Feature.DENOISING).status() == Status.AVAILABLE,
                    "recovered Vulkan device must await its first NRD dispatch before activation"
            );
            renderer.apply(NvidiaGpuSceneNativeTestSupport.scene());
            RenderFrameRequest recovered = NvidiaGpuSceneNativeTestSupport.frame(
                    8_192L,
                    NvidiaGpuSceneNativeTestSupport.OUTPUT_WIDTH,
                    NvidiaGpuSceneNativeTestSupport.OUTPUT_HEIGHT,
                    NvidiaGpuSceneNativeTestSupport.camera(
                            0.0,
                            NvidiaGpuSceneNativeTestSupport.OUTPUT_WIDTH,
                            NvidiaGpuSceneNativeTestSupport.OUTPUT_HEIGHT
                    ),
                    HistoryResetReason.EXPLICIT_RESET
            );
            NvidiaGpuSceneNativeTestSupport.DiagnosticFramePair completed = render(
                    renderer, session, recovered, "NRD recovered-device first frame"
            );
            NvidiaGpuSceneNativeTestSupport.require(
                    session.featureCapabilities().feature(Feature.DENOISING).status() == Status.ACTIVE,
                    "recovered Vulkan device did not activate NRD after its first dispatch"
            );
            requireVisible(completed.output(), completed.trace(), "recovered-device first frame");
            return session.deviceForAcceptance().memoryBudget();
        } finally {
            renderer.close();
        }
    }

    private static RenderedSequence renderTemporalSequence(
            VulkanRtCapabilityProbe.Result capability,
            RayTracingRendererConfig configuration,
            boolean combo,
            boolean dlssCombo
    ) throws Exception {
        VulkanGpuSceneRenderingSession session = VulkanGpuSceneRenderingSession.open(
                capability, configuration, RendererRtDiagnostics.noop()
        );
        VulkanRendererHost renderer = new VulkanRendererHost(configuration, session);
        try {
            NvidiaGpuSceneNativeTestSupport.require(
                    session.featureCapabilities().feature(Feature.DENOISING).status() == Status.AVAILABLE,
                    "NRD must remain available until its first dispatch succeeds"
            );
            if (combo) {
                NvidiaGpuSceneNativeTestSupport.require(
                        session.featureCapabilities().feature(Feature.FRAME_RECONSTRUCTION).status()
                                == Status.AVAILABLE,
                        "ordered NRD+DLAA gate must arm reconstruction before its first evaluate"
                );
            }
            renderer.apply(NvidiaGpuSceneNativeTestSupport.scene());
            byte[] previous = null;
            byte[] resetReference = null;
            double maxFrameDelta = 0.0;
            double maxComposeTraceDelta = 0.0;
            for (long sequence = 0L; sequence <= 8L; sequence++) {
                RenderFrameRequest frame = NvidiaGpuSceneNativeTestSupport.frame(
                        sequence,
                        NvidiaGpuSceneNativeTestSupport.OUTPUT_WIDTH,
                        NvidiaGpuSceneNativeTestSupport.OUTPUT_HEIGHT,
                        NvidiaGpuSceneNativeTestSupport.camera(
                                sequence * 0.025,
                                (sequence - 4L) * 0.0025,
                                NvidiaGpuSceneNativeTestSupport.OUTPUT_WIDTH,
                                NvidiaGpuSceneNativeTestSupport.OUTPUT_HEIGHT
                        )
                );
                NvidiaGpuSceneNativeTestSupport.DiagnosticFramePair completed = render(
                        renderer, session, frame, "NRD moving frame"
                );
                if (sequence == 0L) {
                    NvidiaGpuSceneNativeTestSupport.require(
                            session.featureCapabilities().feature(Feature.DENOISING).status()
                                    == Status.ACTIVE,
                            "NRD did not activate after its first successful dispatch"
                    );
                }
                VulkanGpuSceneRenderingSession.DiagnosticFrame trace = completed.trace();
                NvidiaGpuSceneNativeTestSupport.require(trace.sequence() == sequence,
                        "completed NRD frame lost its matching noisy trace image");
                requireVisible(completed.output(), trace, "moving sequence " + sequence);
                if (completed.output().width() == trace.width()
                        && completed.output().height() == trace.height()) {
                    maxComposeTraceDelta = Math.max(
                            maxComposeTraceDelta,
                            NvidiaGpuSceneNativeTestSupport.meanAbsoluteRgbDelta(
                                    completed.output().rgba8(), trace.rgba8()
                            )
                    );
                } else {
                    NvidiaGpuSceneNativeTestSupport.require(dlssCombo
                                    && trace.width() < completed.output().width()
                                    && trace.height() < completed.output().height(),
                            "only DLSS SR may publish an extent larger than its NRD trace input");
                }
                if (previous != null) {
                    maxFrameDelta = Math.max(
                            maxFrameDelta,
                            NvidiaGpuSceneNativeTestSupport.meanAbsoluteRgbDelta(
                                    previous, completed.output().rgba8()
                            )
                    );
                }
                if (sequence == 0L) resetReference = completed.output().rgba8();
                previous = completed.output().rgba8();
            }
            NvidiaGpuSceneNativeTestSupport.require(maxFrameDelta > 0.05,
                    "camera translation did not produce measurable frame motion");
            NvidiaGpuSceneNativeTestSupport.require(maxComposeTraceDelta < 32.0,
                    "NRD diverged catastrophically from its trace composition input");

            renderer.apply(NvidiaGpuSceneNativeTestSupport.moveInstance(1L, 1.25F));
            VulkanGpuSceneRenderingSession.DiagnosticFrame movedInstance = render(
                    renderer,
                    session,
                    NvidiaGpuSceneNativeTestSupport.frame(9L).toBuilder().minimumSceneRevision(1L).build(),
                    "NRD dynamic-instance motion"
            ).output();
            double dynamicMotionDelta = NvidiaGpuSceneNativeTestSupport.meanAbsoluteRgbDelta(
                    previous, movedInstance.rgba8()
            );
            NvidiaGpuSceneNativeTestSupport.require(dynamicMotionDelta > 0.05,
                    "dynamic instance motion did not produce a measurable temporal change");

            renderer.apply(NvidiaGpuSceneNativeTestSupport.moveInstance(2L, 0.0F));
            VulkanGpuSceneRenderingSession.DiagnosticFrame disoccluded = render(
                    renderer,
                    session,
                    NvidiaGpuSceneNativeTestSupport.frame(10L).toBuilder().minimumSceneRevision(2L).build(),
                    "NRD dynamic-instance disocclusion"
            ).output();
            double disocclusionRestoreDelta = NvidiaGpuSceneNativeTestSupport.meanAbsoluteRgbDelta(
                    resetReference, disoccluded.rgba8()
            );
            NvidiaGpuSceneNativeTestSupport.require(disocclusionRestoreDelta < 32.0,
                    "dynamic disocclusion retained catastrophic stale history");

            byte[] stablePrevious = disoccluded.rgba8();
            double maxStableHistoryDelta = 0.0;
            final int stableHistoryFrames = 32;
            for (long sequence = 11L; sequence < 11L + stableHistoryFrames; sequence++) {
                VulkanGpuSceneRenderingSession.DiagnosticFrame stable = render(
                        renderer,
                        session,
                        NvidiaGpuSceneNativeTestSupport.frame(sequence).toBuilder()
                                .minimumSceneRevision(2L)
                                .build(),
                        "NRD stable history"
                ).output();
                maxStableHistoryDelta = Math.max(
                        maxStableHistoryDelta,
                        NvidiaGpuSceneNativeTestSupport.meanAbsoluteRgbDelta(stablePrevious, stable.rgba8())
                );
                stablePrevious = stable.rgba8();
            }
            NvidiaGpuSceneNativeTestSupport.require(maxStableHistoryDelta < 32.0,
                    "long NRD history became temporally unstable");

            RenderFrameRequest cutFrame = NvidiaGpuSceneNativeTestSupport.frame(
                    1_024L,
                    NvidiaGpuSceneNativeTestSupport.OUTPUT_WIDTH,
                    NvidiaGpuSceneNativeTestSupport.OUTPUT_HEIGHT,
                    NvidiaGpuSceneNativeTestSupport.camera(
                            0.0,
                            NvidiaGpuSceneNativeTestSupport.OUTPUT_WIDTH,
                            NvidiaGpuSceneNativeTestSupport.OUTPUT_HEIGHT
                    ),
                    HistoryResetReason.CAMERA_CUT
            );
            VulkanGpuSceneRenderingSession.DiagnosticFrame cameraCut = render(
                    renderer, session, cutFrame, "NRD camera cut"
            ).output();
            RenderFrameRequest resizedFrame = NvidiaGpuSceneNativeTestSupport.frame(
                    1_025L, 672, 378, NvidiaGpuSceneNativeTestSupport.camera(0.0, 672, 378)
            );
            NvidiaGpuSceneNativeTestSupport.DiagnosticFramePair resized = render(
                    renderer, session, resizedFrame, "NRD resize"
            );
            requireVisible(resized.output(), resized.trace(), "resized frame");
            RenderFrameRequest restoredFrame = NvidiaGpuSceneNativeTestSupport.frame(
                    2_048L,
                    NvidiaGpuSceneNativeTestSupport.OUTPUT_WIDTH,
                    NvidiaGpuSceneNativeTestSupport.OUTPUT_HEIGHT,
                    NvidiaGpuSceneNativeTestSupport.camera(
                            0.0,
                            NvidiaGpuSceneNativeTestSupport.OUTPUT_WIDTH,
                            NvidiaGpuSceneNativeTestSupport.OUTPUT_HEIGHT
                    )
            );
            VulkanGpuSceneRenderingSession.DiagnosticFrame restored = render(
                    renderer, session, restoredFrame, "NRD restored extent"
            ).output();
            RenderFrameRequest sceneResetFrame = NvidiaGpuSceneNativeTestSupport.frame(
                    3_072L,
                    NvidiaGpuSceneNativeTestSupport.OUTPUT_WIDTH,
                    NvidiaGpuSceneNativeTestSupport.OUTPUT_HEIGHT,
                    NvidiaGpuSceneNativeTestSupport.camera(
                            0.0,
                            NvidiaGpuSceneNativeTestSupport.OUTPUT_WIDTH,
                            NvidiaGpuSceneNativeTestSupport.OUTPUT_HEIGHT
                    ),
                    HistoryResetReason.SCENE_DISCONTINUITY
            );
            VulkanGpuSceneRenderingSession.DiagnosticFrame sceneReset = render(
                    renderer, session, sceneResetFrame, "NRD scene discontinuity"
            ).output();
            VulkanMemoryBudgetSnapshot memoryBudget = session.deviceForAcceptance().memoryBudget();
            return new RenderedSequence(
                    11,
                    maxComposeTraceDelta,
                    maxFrameDelta,
                    dynamicMotionDelta,
                    disocclusionRestoreDelta,
                    stableHistoryFrames,
                    maxStableHistoryDelta,
                    NvidiaGpuSceneNativeTestSupport.meanAbsoluteRgbDelta(resetReference, cameraCut.rgba8()),
                    NvidiaGpuSceneNativeTestSupport.meanAbsoluteRgbDelta(resetReference, restored.rgba8()),
                    NvidiaGpuSceneNativeTestSupport.meanAbsoluteRgbDelta(resetReference, sceneReset.rgba8()),
                    memoryBudget
            );
        } finally {
            renderer.close();
        }
    }

    private static NvidiaGpuSceneNativeTestSupport.DiagnosticFramePair render(
            VulkanRendererHost renderer,
            VulkanGpuSceneRenderingSession session,
            RenderFrameRequest frame,
            String label
    ) throws InterruptedException {
        NvidiaGpuSceneNativeTestSupport.awaitFrameAdmission(renderer, frame, label);
        return NvidiaGpuSceneNativeTestSupport.awaitCompletedFramePair(session, frame.sequence(), label);
    }

    private static void requireVisible(
            VulkanGpuSceneRenderingSession.DiagnosticFrame completed,
            VulkanGpuSceneRenderingSession.DiagnosticFrame trace,
            String label
    ) {
        long tracePixels = NvidiaGpuSceneNativeTestSupport.nonBlackPixels(trace.rgba8());
        long outputPixels = NvidiaGpuSceneNativeTestSupport.nonBlackPixels(completed.rgba8());
        NvidiaGpuSceneNativeTestSupport.require(tracePixels > 1_000,
                label + " NRD input trace is unexpectedly black: tracePixels=" + tracePixels);
        NvidiaGpuSceneNativeTestSupport.require(outputPixels > 1_000,
                label + " NRD output is unexpectedly black: outputPixels=" + outputPixels);
    }

    private static VulkanMemoryBudgetSnapshot unavailableBudget() {
        return new VulkanMemoryBudgetSnapshot(false, 0L, 0L, 0L, java.util.List.of());
    }

    private record RenderedSequence(
            int movingFrames,
            double maxComposeTraceDelta,
            double maxFrameDelta,
            double dynamicMotionDelta,
            double disocclusionRestoreDelta,
            int stableHistoryFrames,
            double maxStableHistoryDelta,
            double cameraCutResetDelta,
            double resizeRestoreDelta,
            double sceneResetDelta,
            VulkanMemoryBudgetSnapshot memoryBudget
    ) {
        RenderedSequence {
            memoryBudget = java.util.Objects.requireNonNull(memoryBudget, "memoryBudget");
        }
    }
}
