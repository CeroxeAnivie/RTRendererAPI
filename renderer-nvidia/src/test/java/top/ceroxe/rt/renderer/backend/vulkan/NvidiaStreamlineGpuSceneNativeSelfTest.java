package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.api.CameraState;
import top.ceroxe.rt.renderer.api.DenoisingOptions;
import top.ceroxe.rt.renderer.api.DepthProjectionState;
import top.ceroxe.rt.renderer.api.EnvironmentState;
import top.ceroxe.rt.renderer.api.FrameOutputFormat;
import top.ceroxe.rt.renderer.api.FrameGenerationOptions;
import top.ceroxe.rt.renderer.api.FrameReconstructionOptions;
import top.ceroxe.rt.renderer.api.MaterialAsset;
import top.ceroxe.rt.renderer.api.MeshAsset;
import top.ceroxe.rt.renderer.api.RendererConfig;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.RendererFeaturePreference;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Feature;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Status;
import top.ceroxe.rt.renderer.api.SceneInstance;
import top.ceroxe.rt.renderer.api.SceneTransaction;
import top.ceroxe.rt.renderer.api.SubmissionRejectedException;
import top.ceroxe.rt.renderer.nvidia.WindowsChildProcessIsolation;
import top.ceroxe.rt.renderer.rt.pipeline.VulkanFrameExtents;

/** Runs real GPUScene frames through Streamline DLSS, DLAA, or NIS on the selected Vulkan RT device. */
public final class NvidiaStreamlineGpuSceneNativeSelfTest {
    private static final int OUTPUT_WIDTH = NvidiaGpuSceneNativeTestSupport.OUTPUT_WIDTH;
    private static final int OUTPUT_HEIGHT = NvidiaGpuSceneNativeTestSupport.OUTPUT_HEIGHT;

    private NvidiaStreamlineGpuSceneNativeSelfTest() {
    }

    public static void main(String[] arguments) throws Exception {
        WindowsChildProcessIsolation.preventGradlePipeInheritance();
        boolean dlaa = arguments.length == 1 && "dlaa".equalsIgnoreCase(arguments[0]);
        boolean nis = arguments.length == 1 && "nis".equalsIgnoreCase(arguments[0]);
        if (arguments.length > 1 || (arguments.length == 1 && !dlaa && !nis)) {
            throw new IllegalArgumentException("usage: NvidiaStreamlineGpuSceneNativeSelfTest [dlaa|nis]");
        }
        String featureName = nis ? "NIS" : dlaa ? "DLAA" : "DLSS";
        VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
        NvidiaGpuSceneNativeTestSupport.require(capability.hardwareRayTracingReady(),
                featureName + " frame gate requires Vulkan RT: " + capability.summary());
        NvidiaGpuSceneNativeTestSupport.require(capability.preferredDevice().linearHdrRgba16fOutput(),
                featureName + " frame gate requires exportable RGBA16F output on the selected device");

        RendererConfig configuration = RendererConfig.expertBuilder()
                .maxFramesInFlight(2)
                .frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F)
                .frameGeneration(FrameGenerationOptions.disabled())
                .frameReconstruction(FrameReconstructionOptions.builder()
                        .preference(RendererFeaturePreference.PREFERRED)
                        .mode(dlaa
                                ? FrameReconstructionOptions.Mode.NATIVE_ANTI_ALIASING
                                : nis
                                ? FrameReconstructionOptions.Mode.SPATIAL_UPSCALING
                                : FrameReconstructionOptions.Mode.SUPER_RESOLUTION)
                        .quality(FrameReconstructionOptions.Quality.BALANCED)
                        .fallback(FrameReconstructionOptions.Fallback.NONE)
                        .build())
                .denoising(DenoisingOptions.disabled())
                .validationEnabled(false)
                .gpuTimingsEnabled(false)
                .build();
        VulkanGpuSceneRenderingSession session = VulkanGpuSceneRenderingSession.open(
                capability, configuration, RendererRtDiagnostics.noop()
        );
        VulkanRendererHost renderer = new VulkanRendererHost(configuration, session);
        try {
            var armedCapability = session.featureCapabilities().feature(Feature.FRAME_RECONSTRUCTION);
            NvidiaGpuSceneNativeTestSupport.require(
                    armedCapability.status() == Status.AVAILABLE,
                    featureName + " must remain armed before its first successful evaluate: " + armedCapability);
            RenderFrameRequest frame = NvidiaGpuSceneNativeTestSupport.frame();
            VulkanFrameExtents negotiated = session.deviceForAcceptance().featureSession().negotiateFrameExtents(
                    frame, VulkanFrameExtents.identity(OUTPUT_WIDTH, OUTPUT_HEIGHT)
            );
            NvidiaGpuSceneNativeTestSupport.require(
                    negotiated.outputWidth() == OUTPUT_WIDTH && negotiated.outputHeight() == OUTPUT_HEIGHT,
                    featureName + " extent negotiation changed the requested output extent: " + negotiated);
            if (dlaa) {
                NvidiaGpuSceneNativeTestSupport.require(
                        negotiated.renderWidth() == OUTPUT_WIDTH && negotiated.renderHeight() == OUTPUT_HEIGHT,
                        "DLAA must preserve native render resolution: " + negotiated);
            } else {
                NvidiaGpuSceneNativeTestSupport.require(
                        negotiated.renderWidth() < OUTPUT_WIDTH && negotiated.renderHeight() < OUTPUT_HEIGHT,
                        "balanced " + featureName + " must negotiate a real scaled render extent: " + negotiated);
            }
            if (nis) {
                NvidiaGpuSceneNativeTestSupport.require(
                        negotiated.renderWidth() == 378 && negotiated.renderHeight() == 212,
                        "balanced NIS must use the documented 59% spatial input ratio: " + negotiated
                );
            }

            renderer.apply(NvidiaGpuSceneNativeTestSupport.scene());
            byte[] previousOutput = null;
            double maxFrameDelta = 0.0;
            for (long sequence = 0L; sequence < 9L; sequence++) {
                RenderFrameRequest movingFrame = sequence == 0L
                        ? frame
                        : NvidiaGpuSceneNativeTestSupport.frame(
                                sequence,
                                OUTPUT_WIDTH,
                                OUTPUT_HEIGHT,
                                NvidiaGpuSceneNativeTestSupport.camera(
                                        sequence * 0.025,
                                        (sequence - 4L) * 0.0025,
                                        OUTPUT_WIDTH,
                                        OUTPUT_HEIGHT
                                )
                        );
                NvidiaGpuSceneNativeTestSupport.awaitFrameAdmission(renderer, movingFrame, featureName);
                VulkanGpuSceneRenderingSession.DiagnosticFrame completed =
                        NvidiaGpuSceneNativeTestSupport.awaitCompletedFrame(session, sequence, featureName);
                NvidiaGpuSceneNativeTestSupport.require(
                        session.featureCapabilities().feature(Feature.FRAME_RECONSTRUCTION).status() == Status.ACTIVE,
                        featureName + " capability did not activate after completed slEvaluateFeature");
                VulkanGpuSceneRenderingSession.DiagnosticFrame trace = session.captureLatestTraceForAcceptance();
                NvidiaGpuSceneNativeTestSupport.require(trace != null,
                        "completed " + featureName + " frame lost its internal trace image");
                long tracePixels = NvidiaGpuSceneNativeTestSupport.nonBlackPixels(trace.rgba8());
                long outputPixels = NvidiaGpuSceneNativeTestSupport.nonBlackPixels(completed.rgba8());
                NvidiaGpuSceneNativeTestSupport.require(tracePixels > 1_000,
                        featureName + " input trace is unexpectedly black: tracePixels=" + tracePixels);
                NvidiaGpuSceneNativeTestSupport.require(
                        completed.width() == OUTPUT_WIDTH && completed.height() == OUTPUT_HEIGHT,
                        featureName + " published an incorrect output extent");
                NvidiaGpuSceneNativeTestSupport.require(outputPixels > 1_000,
                        "completed " + featureName + " frame is unexpectedly black: tracePixels=" + tracePixels
                                + ", outputPixels=" + outputPixels);
                if (previousOutput != null) {
                    maxFrameDelta = Math.max(
                            maxFrameDelta,
                            NvidiaGpuSceneNativeTestSupport.meanAbsoluteRgbDelta(previousOutput, completed.rgba8())
                    );
                }
                previousOutput = completed.rgba8();
                session.discardCompletedForAcceptance();
            }
            NvidiaGpuSceneNativeTestSupport.require(maxFrameDelta > 0.05,
                    featureName + " continuous camera motion did not produce measurable frame change");
            System.out.println("NvidiaStreamlineGpuSceneNativeSelfTest passed: feature=" + featureName + ", device="
                    + capability.preferredDevice().name() + ", extents=" + negotiated
                    + ", movingFrames=9, maxFrameDelta=" + maxFrameDelta
                    + ", status=" + session.featureCapabilities().feature(Feature.FRAME_RECONSTRUCTION).status());
        } finally {
            renderer.close();
        }
    }

}
