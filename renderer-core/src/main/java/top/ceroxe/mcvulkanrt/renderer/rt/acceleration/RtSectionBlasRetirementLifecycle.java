package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import top.ceroxe.mcvulkanrt.renderer.RendererFrameCausality;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;

import java.util.Objects;

/** Couples deferred section-BLAS destruction with its corresponding JFR retirement evidence. */
final class RtSectionBlasRetirementLifecycle {
    /** Stable diagnostic cause attached to both retirement and deferred release edges. */
    enum Reason {
        FULL_RESYNC,
        SUCCESSOR_RESERVATION,
        REPLACEMENT,
        EXPLICIT_REMOVAL,
        BUDGET_EVICTION
    }

    private final RtSectionBlasRetirementQueue queue = new RtSectionBlasRetirementQueue();
    private final RtSectionBlasLifecycleFlightRecorder flightRecorder;

    RtSectionBlasRetirementLifecycle(RtSectionBlasLifecycleFlightRecorder flightRecorder) {
        this.flightRecorder = Objects.requireNonNull(flightRecorder, "flightRecorder");
    }

    void retire(
            SectionKey key,
            long safeAfterRevision,
            RtAccelerationStructure blas,
            long resourceRevision,
            long contentRevision,
            long buildSequence,
            RendererFrameCausality causality,
            Reason reason
    ) {
        queue.retire(key, safeAfterRevision, blas);
        flightRecorder.retireQueued(
                blas,
                key,
                safeAfterRevision,
                resourceRevision,
                contentRevision,
                buildSequence,
                causality,
                Objects.requireNonNull(reason, "reason")
        );
    }

    void releaseThrough(long protectedRevision) {
        try {
            queue.releaseThrough(protectedRevision);
        } catch (RuntimeException failure) {
            flightRecorder.releaseThrough(protectedRevision, false);
            throw failure;
        }
        flightRecorder.releaseThrough(protectedRevision, true);
    }

    RuntimeException closeAllCollecting(RuntimeException failure, boolean residentCloseSucceeded) {
        RuntimeException before = failure;
        int suppressedBefore = suppressedCount(failure);
        failure = queue.closeAllCollecting(failure);
        boolean retirementCloseSucceeded = before == failure && suppressedBefore == suppressedCount(failure);
        flightRecorder.releaseAll(residentCloseSucceeded && retirementCloseSucceeded);
        return failure;
    }

    int size() { return queue.size(); }
    long closedCount() { return queue.closedCount(); }
    long retainedBytes() { return queue.retainedBytes(); }
    long peakRetainedBytes() { return queue.peakRetainedBytes(); }

    static boolean isReleasable(long safeAfterRevision, long protectedRevision) {
        return RtSectionBlasRetirementQueue.isReleasable(safeAfterRevision, protectedRevision);
    }

    private static int suppressedCount(RuntimeException failure) {
        return failure == null ? 0 : failure.getSuppressed().length;
    }
}
