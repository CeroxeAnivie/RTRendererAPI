package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameCausality;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded JFR evidence for the CPU-to-GPU lifetime of one RT frame dispatch.
 *
 * <p>The recorder owns only immutable scalar evidence. It never retains a frame slot,
 * submission, scene, or Vulkan resource, so enabling diagnostics cannot extend native
 * lifetimes. Disabled production runs return before touching counters or thread-local state.</p>
 */
final class RtFrameDispatchFlightRecorder {
    static final int OUTCOME_SUBMITTED = 1;
    static final int OUTCOME_COMPLETED = 2;
    static final int OUTCOME_REJECTED = 3;
    static final int OUTCOME_FAILED = 4;

    private static final boolean ENABLED =
            Boolean.getBoolean("mcvulkanrt.takeoverFlightRecorder.enabled");
    private static final long MAX_EVENTS = positiveLongProperty(
            "mcvulkanrt.takeoverFlightRecorder.frameDispatchMaxEvents",
            262_144L
    );
    private static final long OVERFLOW_SAMPLE_MASK = 63L;
    private static final AtomicLong ATTEMPTS = new AtomicLong();
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final AtomicBoolean FAILED_CLOSED = new AtomicBoolean();
    private static final EventType DISPATCH_EVENT_TYPE = EventType.getEventType(FrameDispatchEvent.class);
    private static final EventType CAPTURE_LOSS_EVENT_TYPE =
            EventType.getEventType(FrameDispatchCaptureLossEvent.class);
    private static final ThreadLocal<FrameDispatchEvent> THREAD_EVENT =
            ThreadLocal.withInitial(FrameDispatchEvent::new);

    private RtFrameDispatchFlightRecorder() {
    }

    static boolean enabled() {
        return ENABLED && !FAILED_CLOSED.get() && DISPATCH_EVENT_TYPE.isEnabled();
    }

    static void recordSubmitted(
            RendererFrameCausality causality,
            long frameStateSequence,
            long dispatchOrdinal,
            long dynamicSceneRevision,
            long descriptorGeneration,
            long boundTlasDynamicSceneRevision,
            long elapsedNanos,
            CpuTimings cpuTimings
    ) {
        record(
                causality,
                frameStateSequence,
                dispatchOrdinal,
                dynamicSceneRevision,
                descriptorGeneration,
                boundTlasDynamicSceneRevision,
                OUTCOME_SUBMITTED,
                "submitted",
                0L,
                elapsedNanos,
                0L,
                0L,
                0L,
                cpuTimings
        );
    }

    static void recordCompleted(
            RendererFrameCausality causality,
            long frameStateSequence,
            long dispatchOrdinal,
            long dynamicSceneRevision,
            long descriptorGeneration,
            long boundTlasDynamicSceneRevision,
            long pollCount,
            long elapsedNanos,
            long traceGpuNanos,
            long postTraceGpuNanos,
            long totalGpuNanos
    ) {
        record(
                causality,
                frameStateSequence,
                dispatchOrdinal,
                dynamicSceneRevision,
                descriptorGeneration,
                boundTlasDynamicSceneRevision,
                OUTCOME_COMPLETED,
                "completed",
                pollCount,
                elapsedNanos,
                traceGpuNanos,
                postTraceGpuNanos,
                totalGpuNanos,
                CpuTimings.NONE
        );
    }

    static void recordRejected(
            RendererFrameCausality causality,
            long frameStateSequence,
            long dynamicSceneRevision,
            long descriptorGeneration,
            long boundTlasDynamicSceneRevision,
            String reason
    ) {
        record(
                causality,
                frameStateSequence,
                -1L,
                dynamicSceneRevision,
                descriptorGeneration,
                boundTlasDynamicSceneRevision,
                OUTCOME_REJECTED,
                reason,
                0L,
                0L,
                0L,
                0L,
                0L,
                CpuTimings.NONE
        );
    }

    static void recordFailed(
            RendererFrameCausality causality,
            long frameStateSequence,
            long dynamicSceneRevision,
            long descriptorGeneration,
            long boundTlasDynamicSceneRevision,
            long elapsedNanos,
            String reason
    ) {
        record(
                causality,
                frameStateSequence,
                -1L,
                dynamicSceneRevision,
                descriptorGeneration,
                boundTlasDynamicSceneRevision,
                OUTCOME_FAILED,
                reason,
                0L,
                elapsedNanos,
                0L,
                0L,
                0L,
                CpuTimings.NONE
        );
    }

    private static void record(
            RendererFrameCausality causality,
            long frameStateSequence,
            long dispatchOrdinal,
            long dynamicSceneRevision,
            long descriptorGeneration,
            long boundTlasDynamicSceneRevision,
            int outcome,
            String reason,
            long pollCount,
            long elapsedNanos,
            long traceGpuNanos,
            long postTraceGpuNanos,
            long totalGpuNanos,
            CpuTimings cpu
    ) {
        if (!ENABLED || FAILED_CLOSED.get()) {
            return;
        }
        try {
            Objects.requireNonNull(causality, "causality");
            Objects.requireNonNull(cpu, "cpu");
            requireReason(reason);
            if (frameStateSequence < 0L || dynamicSceneRevision < 0L
                    || descriptorGeneration <= 0L || boundTlasDynamicSceneRevision < 0L
                    || pollCount < 0L || elapsedNanos < 0L || traceGpuNanos < 0L
                    || postTraceGpuNanos < 0L || totalGpuNanos < 0L
                    || totalGpuNanos < traceGpuNanos || totalGpuNanos < postTraceGpuNanos) {
                throw new IllegalArgumentException("frame dispatch evidence contains an invalid scalar");
            }
            if (!admit()) {
                return;
            }
            FrameDispatchEvent event = THREAD_EVENT.get();
            event.traceId = causality.traceId();
            event.frameStateSequence = frameStateSequence;
            event.dispatchOrdinal = dispatchOrdinal;
            event.dynamicSceneRevision = dynamicSceneRevision;
            event.descriptorGeneration = descriptorGeneration;
            event.worldTlasRevision = causality.chainIdentity().worldTlasRevision();
            event.boundTlasDynamicSceneRevision = boundTlasDynamicSceneRevision;
            event.outcome = outcome;
            event.reason = reason;
            event.pollCount = pollCount;
            event.elapsedNanos = elapsedNanos;
            event.traceGpuNanos = traceGpuNanos;
            event.postTraceGpuNanos = postTraceGpuNanos;
            event.totalGpuNanos = totalGpuNanos;
            event.resourcePrepNanos = cpu.resourcePrepNanos();
            event.frameUniformNanos = cpu.frameUniformNanos();
            event.dynamicScenePackNanos = cpu.dynamicScenePackNanos();
            event.dynamicSceneCommandsNanos = cpu.dynamicSceneCommandsNanos();
            event.preTraceBarriersNanos = cpu.preTraceBarriersNanos();
            event.traceCommandNanos = cpu.traceCommandNanos();
            event.traceToTransferNanos = cpu.traceToTransferNanos();
            event.outputToTransferNanos = cpu.outputToTransferNanos();
            event.imageBlitNanos = cpu.imageBlitNanos();
            event.outputToGeneralNanos = cpu.outputToGeneralNanos();
            event.traceToGeneralNanos = cpu.traceToGeneralNanos();
            event.readbackCommandNanos = cpu.readbackCommandNanos();
            event.commandPoolAcquireNanos = cpu.commandPoolAcquireNanos();
            event.commandRecordNanos = cpu.commandRecordNanos();
            event.queueLockWaitNanos = cpu.queueLockWaitNanos();
            event.vkQueueSubmitNanos = cpu.vkQueueSubmitNanos();
            event.commandWrapperAndSubmitNanos = cpu.commandWrapperAndSubmitNanos();
            event.bookkeepingNanos = cpu.bookkeepingNanos();
            event.droppedBefore = DROPPED.get();
            event.commit();
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            FAILED_CLOSED.set(true);
        }
    }

    private static boolean admit() {
        if (!DISPATCH_EVENT_TYPE.isEnabled()) {
            return false;
        }
        long sequence = ATTEMPTS.incrementAndGet();
        if (sequence <= MAX_EVENTS || ((sequence - MAX_EVENTS) & OVERFLOW_SAMPLE_MASK) == 0L) {
            return true;
        }
        long dropped = DROPPED.incrementAndGet();
        if (dropped == 1L || (dropped & (dropped - 1L)) == 0L) {
            recordCaptureLoss(sequence, dropped);
        }
        return false;
    }

    private static void recordCaptureLoss(long attemptedEvents, long droppedEvents) {
        if (!CAPTURE_LOSS_EVENT_TYPE.isEnabled()) {
            return;
        }
        FrameDispatchCaptureLossEvent event = new FrameDispatchCaptureLossEvent();
        event.maxEvents = MAX_EVENTS;
        event.attemptedEvents = attemptedEvents;
        event.droppedEventsLowerBound = droppedEvents;
        event.overflowSampleStride = OVERFLOW_SAMPLE_MASK + 1L;
        event.commit();
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("frame dispatch reason must not be blank");
        }
        return reason;
    }

    private static long positiveLongProperty(String property, long defaultValue) {
        String configured = System.getProperty(property);
        if (configured == null || configured.isBlank()) {
            return defaultValue;
        }
        try {
            long value = Long.parseLong(configured);
            return value > 0L ? value : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    record CpuTimings(
            long resourcePrepNanos,
            long frameUniformNanos,
            long dynamicScenePackNanos,
            long dynamicSceneCommandsNanos,
            long preTraceBarriersNanos,
            long traceCommandNanos,
            long traceToTransferNanos,
            long outputToTransferNanos,
            long imageBlitNanos,
            long outputToGeneralNanos,
            long traceToGeneralNanos,
            long readbackCommandNanos,
            long commandPoolAcquireNanos,
            long commandRecordNanos,
            long queueLockWaitNanos,
            long vkQueueSubmitNanos,
            long commandWrapperAndSubmitNanos,
            long bookkeepingNanos
    ) {
        private static final CpuTimings NONE = new CpuTimings(
                0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L
        );

        CpuTimings {
            if (resourcePrepNanos < 0L || frameUniformNanos < 0L || dynamicScenePackNanos < 0L
                    || dynamicSceneCommandsNanos < 0L || preTraceBarriersNanos < 0L
                    || traceCommandNanos < 0L || traceToTransferNanos < 0L
                    || outputToTransferNanos < 0L || imageBlitNanos < 0L
                    || outputToGeneralNanos < 0L || traceToGeneralNanos < 0L
                    || readbackCommandNanos < 0L || commandPoolAcquireNanos < 0L
                    || commandRecordNanos < 0L || queueLockWaitNanos < 0L
                    || vkQueueSubmitNanos < 0L || commandWrapperAndSubmitNanos < 0L
                    || bookkeepingNanos < 0L) {
                throw new IllegalArgumentException("frame dispatch CPU timings must not be negative");
            }
        }
    }

    @Name("top.ceroxe.mcvulkanrt.FrameDispatch")
    @Label("MCVulkanRT RT Frame Dispatch")
    @Category({"MCVulkanRT", "Renderer", "Dispatch"})
    @StackTrace(false)
    static final class FrameDispatchEvent extends Event {
        long traceId;
        long frameStateSequence;
        long dispatchOrdinal;
        long dynamicSceneRevision;
        long descriptorGeneration;
        long worldTlasRevision;
        long boundTlasDynamicSceneRevision;
        int outcome;
        String reason;
        long pollCount;
        long elapsedNanos;
        long traceGpuNanos;
        long postTraceGpuNanos;
        long totalGpuNanos;
        long resourcePrepNanos;
        long frameUniformNanos;
        long dynamicScenePackNanos;
        long dynamicSceneCommandsNanos;
        long preTraceBarriersNanos;
        long traceCommandNanos;
        long traceToTransferNanos;
        long outputToTransferNanos;
        long imageBlitNanos;
        long outputToGeneralNanos;
        long traceToGeneralNanos;
        long readbackCommandNanos;
        long commandPoolAcquireNanos;
        long commandRecordNanos;
        long queueLockWaitNanos;
        long vkQueueSubmitNanos;
        long commandWrapperAndSubmitNanos;
        long bookkeepingNanos;
        long droppedBefore;
    }

    @Name("top.ceroxe.mcvulkanrt.FrameDispatchCaptureLoss")
    @Label("MCVulkanRT RT Frame Dispatch Capture Loss")
    @Category({"MCVulkanRT", "Renderer", "Dispatch"})
    @StackTrace(false)
    static final class FrameDispatchCaptureLossEvent extends Event {
        long maxEvents;
        long attemptedEvents;
        long droppedEventsLowerBound;
        long overflowSampleStride;
    }
}
