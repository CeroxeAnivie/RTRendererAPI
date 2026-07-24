package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

import top.ceroxe.mcvulkanrt.renderer.RendererCausalityEvidence;
import top.ceroxe.mcvulkanrt.renderer.RendererRtDiagnostics;
import top.ceroxe.mcvulkanrt.renderer.RtCausalitySink;
import top.ceroxe.mcvulkanrt.renderer.rt.material.RtSceneMaterialTable;
import top.ceroxe.mcvulkanrt.renderer.rt.device.RtGpuTimestampPool;
import top.ceroxe.mcvulkanrt.renderer.rt.runtime.RtCore;

import java.util.Objects;

/** Applies one completed GPU submission to the immutable shared-frame publication front. */
final class RtFrameCompletionPublisher {
    private final RtSharedFrameLifecycle sharedFrames;
    private final RendererRtDiagnostics diagnostics;
    private final int diagnosticGBufferBytesPerPixel;

    RtFrameCompletionPublisher(
            RtSharedFrameLifecycle sharedFrames,
            RendererRtDiagnostics diagnostics,
            int diagnosticGBufferBytesPerPixel
    ) {
        this.sharedFrames = Objects.requireNonNull(sharedFrames, "sharedFrames");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        if (diagnosticGBufferBytesPerPixel <= 0) {
            throw new IllegalArgumentException("diagnostic G-buffer bytes per pixel must be positive");
        }
        this.diagnosticGBufferBytesPerPixel = diagnosticGBufferBytesPerPixel;
    }

    Completion publish(RtPendingFrameSubmission pending) {
        Objects.requireNonNull(pending, "pending");
        long completionNanos = System.nanoTime();
        long gpuElapsedNanos = Math.max(0L, completionNanos - pending.submittedNanos());
        long traceGpuNanos = 0L;
        long postTraceGpuNanos = 0L;
        long totalGpuNanos = 0L;
        RtGpuTimestampPool.Capture gpuTimestamps = pending.gpuTimestamps();
        if (gpuTimestamps != null) {
            try {
                if (gpuTimestamps.resolve() && gpuTimestamps.segmentCount() == 2) {
                    traceGpuNanos = gpuTimestamps.segmentNanos(0);
                    postTraceGpuNanos = gpuTimestamps.segmentNanos(1);
                    totalGpuNanos = gpuTimestamps.totalNanos();
                }
            } finally {
                gpuTimestamps.close();
            }
        }
        RtFrameDispatchFlightRecorder.recordCompleted(
                pending.causality(),
                pending.frameStateSequence(),
                pending.dispatchOrdinal(),
                pending.dynamicSceneRevision(),
                pending.descriptorGeneration(),
                pending.boundTlasDynamicSceneRevision(),
                pending.polls(),
                gpuElapsedNanos,
                traceGpuNanos,
                postTraceGpuNanos,
                totalGpuNanos
        );
        diagnostics.edges().edge(
                "frameCompleted",
                "dispatchOrdinal=" + pending.dispatchOrdinal()
                        + ", frameSequence=" + pending.frameStateSequence()
                        + ", gpuLatencyMs=" + ageMillis(pending, completionNanos)
                        + ", traceGpuMicros=" + traceGpuNanos / 1_000L
                        + ", postTraceGpuMicros=" + postTraceGpuNanos / 1_000L
                        + ", totalGpuMicros=" + totalGpuNanos / 1_000L
                        + ", polls=" + pending.polls()
        );

        RtPipelineFrameSlot completedFrameSlot = pending.frameSlot();
        completedFrameSlot.completeWrite(pending.frameStateSequence());
        RtCore.SharedFrameState completedSharedState = RtCore.SharedFrameState.trustedFrozen(
                pending.frameStateSequence(),
                pending.sectionKeys(),
                pending.viewState(),
                pending.sectionContentRevisions(),
                pending.causality(),
                pending.scenePublicationState()
        );
        sharedFrames.publishCompletion(completedFrameSlot, completedSharedState);

        diagnostics.causality().firstFrontFrame(
                "gpuFrameCompleted",
                pending.frameStateSequence(),
                "dispatchOrdinal=" + pending.dispatchOrdinal()
                        + ", gpuLatencyMs=" + ageMillis(pending, System.nanoTime())
                        + ", sections=" + pending.sectionKeys().size()
        );
        diagnostics.causality().frameCausality(
                RtCausalitySink.Stage.GPU_COMPLETED,
                pending.causality(),
                new RendererCausalityEvidence(
                        pending.dynamicSceneRevision(),
                        pending.sectionKeys().size(),
                        RtCausalitySink.Reason.NONE
                )
        );

        RtFrameSnapshot frameReadback = null;
        if (pending.captureReadback()) {
            frameReadback = RtFrameSnapshot.capture(
                    pending.frameSlot().readbackBuffer().readBytes(pending.readbackBytes()),
                    pending.width(),
                    pending.height(),
                    pending.frameStateSequence(),
                    pending.dynamicSceneRevision(),
                    pending.boundTlasDynamicSceneRevision(),
                    pending.scenePublicationState(),
                    RtSceneMaterialTable.missRgba8()
            );
        }
        RtGBufferSnapshot gBufferReadback = null;
        if (pending.captureGBuffer()) {
            long gBufferBytes = RtFrameOutputResourceFactory.imageByteSize(
                    pending.gBufferWidth(), pending.gBufferHeight(), diagnosticGBufferBytesPerPixel
            );
            gBufferReadback = RtGBufferSnapshot.capture(
                    pending.frameSlot().diagnosticGBufferReadback().readBytes(gBufferBytes),
                    pending.gBufferWidth(),
                    pending.gBufferHeight(),
                    pending.frameStateSequence()
            );
        }
        return new Completion(frameReadback, gBufferReadback);
    }

    private static long ageMillis(RtPendingFrameSubmission pending, long nowNanos) {
        return Math.max(0L, nowNanos - pending.submittedNanos()) / 1_000_000L;
    }

    record Completion(RtFrameSnapshot frameReadback, RtGBufferSnapshot gBufferReadback) {
    }
}
