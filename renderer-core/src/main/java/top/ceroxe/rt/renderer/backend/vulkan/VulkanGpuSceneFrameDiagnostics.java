package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.CpuFrame;
import top.ceroxe.rt.renderer.api.RendererDiagnostics;
import top.ceroxe.rt.renderer.rt.device.RtGpuImage;
import top.ceroxe.rt.renderer.rt.device.RtGpuTimestampPool;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

import java.util.Objects;

/** Isolates diagnostic copies and timing translation from frame lifecycle coordination. */
final class VulkanGpuSceneFrameDiagnostics {
    private final VulkanDeviceRuntime device;
    private final VulkanGpuSceneFeatureComposition features;
    private final boolean gpuTimingsEnabled;
    private final String timingLabel;

    VulkanGpuSceneFrameDiagnostics(
            VulkanDeviceRuntime device,
            VulkanGpuSceneFeatureComposition features,
            boolean gpuTimingsEnabled,
            String timingLabel
    ) {
        this.device = Objects.requireNonNull(device, "device");
        this.features = Objects.requireNonNull(features, "features");
        this.gpuTimingsEnabled = gpuTimingsEnabled;
        this.timingLabel = requireText(timingLabel, "timingLabel");
    }

    CpuFrame captureCpu(VulkanFrameSlot slot) {
        VulkanFrameSlot checked = Objects.requireNonNull(slot, "slot");
        return CpuFrame.builder()
                .frameSequence(checked.frameSequence())
                .renderedSceneRevision(checked.renderedSceneRevision())
                .extent(checked.outputImage().width(), checked.outputImage().height())
                .pixelsRgba8(checked.captureCpuRgba8())
                .build();
    }

    VulkanGpuSceneRenderingSession.DiagnosticFrame captureOutput(VulkanFrameSlot slot) {
        VulkanFrameSlot checked = Objects.requireNonNull(slot, "slot");
        byte[] rgba8 = VulkanFrameDiagnosticReadback.capture(device, checked.outputImage());
        return new VulkanGpuSceneRenderingSession.DiagnosticFrame(
                checked.frameSequence(),
                checked.renderedSceneRevision(),
                checked.outputImage().width(),
                checked.outputImage().height(),
                rgba8
        );
    }

    VulkanGpuSceneRenderingSession.DiagnosticFrame captureTrace(VulkanFrameSlot slot) {
        VulkanFrameSlot checked = Objects.requireNonNull(slot, "slot");
        RtGpuImage trace = features.traceOutput(checked, features.frameSelection(checked));
        byte[] rgba8 = VulkanFrameDiagnosticReadback.captureInternal(device, trace);
        return new VulkanGpuSceneRenderingSession.DiagnosticFrame(
                checked.frameSequence(), checked.renderedSceneRevision(),
                trace.width(), trace.height(), rgba8
        );
    }

    RendererDiagnostics.FrameGpuTiming frameTiming() {
        if (!gpuTimingsEnabled) return RendererDiagnostics.FrameGpuTiming.unavailable();
        RtGpuTimestampPool.StageSnapshot timing = device.frameCommands()
                .gpuStageTimestampSnapshot(timingLabel);
        if (!timing.enabled()) return RendererDiagnostics.FrameGpuTiming.unavailable();
        return RendererDiagnostics.FrameGpuTiming.builder()
                .enabled(true)
                .completedSamples(timing.completedCaptures())
                .droppedSamples(timing.droppedCaptures())
                .failedSamples(timing.failedCaptures())
                .averageTraceNanos(timing.averageNanos())
                .averagePostTraceNanos(0L)
                .averageTotalNanos(timing.averageNanos())
                .maxTotalNanos(timing.maxNanos())
                .build();
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) throw new IllegalArgumentException(label + " must not be blank");
        return checked;
    }
}
