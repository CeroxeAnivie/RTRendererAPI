package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

import top.ceroxe.mcvulkanrt.renderer.rt.device.RtCommandContext;

/** Mutable, single-dispatch CPU timing evidence for native RT command recording and submission. */
final class RtFrameDispatchTiming {
    enum Stage {
        RESOURCE_PREP("resourcePrep"), FRAME_UNIFORM("frameUniform"), DYNAMIC_SCENE_PACK("dynamicScenePack"),
        DYNAMIC_SCENE_COMMANDS("dynamicSceneCommands"), PRE_TRACE_BARRIERS("preTraceBarriers"),
        TRACE_COMMAND("traceCommand"), TRACE_TO_TRANSFER("traceToTransfer"),
        OUTPUT_TO_TRANSFER("outputToTransfer"), IMAGE_BLIT("imageBlit"), OUTPUT_TO_GENERAL("outputToGeneral"),
        TRACE_TO_GENERAL("traceToGeneral"), READBACK_COMMAND("readbackCommand"),
        COMMAND_POOL_ACQUIRE("commandPoolAcquire"), COMMAND_RECORD("commandRecord"),
        QUEUE_LOCK_WAIT("queueLockWait"), VK_QUEUE_SUBMIT("vkQueueSubmit"),
        COMMAND_WRAPPER_AND_SUBMIT("commandWrapperAndSubmit"), BOOKKEEPING("bookkeeping");

        private final String logName;

        Stage(String logName) {
            this.logName = logName;
        }

        String logName() {
            return logName;
        }
    }

    private static final Stage[] COMMAND_RECORDING_STAGES = {
            Stage.FRAME_UNIFORM, Stage.DYNAMIC_SCENE_PACK, Stage.DYNAMIC_SCENE_COMMANDS,
            Stage.PRE_TRACE_BARRIERS, Stage.TRACE_COMMAND, Stage.TRACE_TO_TRANSFER,
            Stage.OUTPUT_TO_TRANSFER, Stage.IMAGE_BLIT, Stage.OUTPUT_TO_GENERAL,
            Stage.TRACE_TO_GENERAL, Stage.READBACK_COMMAND
    };

    private final long[] nanos;

    private RtFrameDispatchTiming(boolean enabled) {
        this.nanos = enabled ? new long[Stage.values().length] : null;
    }

    static RtFrameDispatchTiming createEnabled() {
        return new RtFrameDispatchTiming(true);
    }

    static RtFrameDispatchTiming createDisabled() {
        return new RtFrameDispatchTiming(false);
    }

    boolean enabled() {
        return nanos != null;
    }

    void record(Stage stage, long startNanos) {
        if (nanos != null) {
            nanos[stage.ordinal()] += Math.max(0L, System.nanoTime() - startNanos);
        }
    }

    void recordValue(Stage stage, long durationNanos) {
        if (nanos != null) {
            nanos[stage.ordinal()] = Math.max(0L, durationNanos);
        }
    }

    void recordSubmission(RtCommandContext.AsyncSubmission submission, long dispatchStartNanos) {
        if (nanos == null) {
            return;
        }
        recordValue(Stage.COMMAND_POOL_ACQUIRE, submission.commandPoolAcquireWaitNanos());
        recordValue(Stage.COMMAND_RECORD, submission.commandRecordNanos());
        recordValue(Stage.QUEUE_LOCK_WAIT, submission.queueLockWaitNanos());
        recordValue(Stage.VK_QUEUE_SUBMIT, submission.vkQueueSubmitNanos());
        long knownRecordNanos = 0L;
        for (Stage stage : COMMAND_RECORDING_STAGES) {
            knownRecordNanos += nanos[stage.ordinal()];
        }
        recordValue(Stage.COMMAND_WRAPPER_AND_SUBMIT, System.nanoTime() - dispatchStartNanos - knownRecordNanos);
    }

    void finishBookkeeping(long dispatchStartNanos) {
        if (nanos == null) {
            return;
        }
        long accounted = 0L;
        for (Stage stage : Stage.values()) {
            if (stage != Stage.BOOKKEEPING) {
                accounted += nanos[stage.ordinal()];
            }
        }
        recordValue(Stage.BOOKKEEPING,
                System.nanoTime() - dispatchStartNanos - (accounted - nanos[Stage.RESOURCE_PREP.ordinal()]));
    }

    long nanos(Stage stage) {
        if (nanos == null) {
            throw new IllegalStateException("dispatch timing is disabled");
        }
        return nanos[stage.ordinal()];
    }

    long[] snapshot() {
        if (nanos == null) {
            throw new IllegalStateException("dispatch timing is disabled");
        }
        return nanos.clone();
    }

    static Stage[] commandRecordingStages() {
        return COMMAND_RECORDING_STAGES.clone();
    }

    static RtFrameDispatchFlightRecorder.CpuTimings cpuTimings(long[] nanos) {
        if (nanos == null || nanos.length != Stage.values().length) {
            throw new IllegalArgumentException("dispatch timing snapshot does not match the stage ABI");
        }
        return new RtFrameDispatchFlightRecorder.CpuTimings(
                nanos[Stage.RESOURCE_PREP.ordinal()], nanos[Stage.FRAME_UNIFORM.ordinal()],
                nanos[Stage.DYNAMIC_SCENE_PACK.ordinal()], nanos[Stage.DYNAMIC_SCENE_COMMANDS.ordinal()],
                nanos[Stage.PRE_TRACE_BARRIERS.ordinal()], nanos[Stage.TRACE_COMMAND.ordinal()],
                nanos[Stage.TRACE_TO_TRANSFER.ordinal()], nanos[Stage.OUTPUT_TO_TRANSFER.ordinal()],
                nanos[Stage.IMAGE_BLIT.ordinal()], nanos[Stage.OUTPUT_TO_GENERAL.ordinal()],
                nanos[Stage.TRACE_TO_GENERAL.ordinal()], nanos[Stage.READBACK_COMMAND.ordinal()],
                nanos[Stage.COMMAND_POOL_ACQUIRE.ordinal()], nanos[Stage.COMMAND_RECORD.ordinal()],
                nanos[Stage.QUEUE_LOCK_WAIT.ordinal()], nanos[Stage.VK_QUEUE_SUBMIT.ordinal()],
                nanos[Stage.COMMAND_WRAPPER_AND_SUBMIT.ordinal()], nanos[Stage.BOOKKEEPING.ordinal()]
        );
    }

    RtFrameDispatchFlightRecorder.CpuTimings cpuTimings() {
        if (nanos == null) {
            throw new IllegalStateException("dispatch timing is disabled");
        }
        return cpuTimings(nanos);
    }
}
