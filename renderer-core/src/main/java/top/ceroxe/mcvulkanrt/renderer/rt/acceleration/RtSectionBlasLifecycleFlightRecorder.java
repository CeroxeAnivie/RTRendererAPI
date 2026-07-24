package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import jdk.jfr.EventType;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameCausality;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionTriangleMesh;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * Optional, bounded evidence sink for section-BLAS ownership.
 *
 * <p>This owner mirrors only diagnostic identity. The cache and retirement queue
 * remain the correctness owners, so a disabled recorder has no clock reads,
 * identity map, or lifecycle callback side effects. A recorder failure closes
 * the sink permanently and never changes native resource lifetime.</p>
 */
final class RtSectionBlasLifecycleFlightRecorder {
    static final String EVENT_NAME = "top.ceroxe.mcvulkanrt.SectionBlasLifecycle";
    static final String CAPTURE_LOSS_EVENT_NAME = "top.ceroxe.mcvulkanrt.SectionBlasCaptureLoss";
    private static final boolean ENABLED = Boolean.getBoolean("mcvulkanrt.takeoverFlightRecorder.enabled");
    private static final long DEFAULT_MAX_EVENTS = positiveLongProperty(
            "mcvulkanrt.takeoverFlightRecorder.sectionBlasMaxEvents", 65_536L
    );
    private static final long OVERFLOW_SAMPLE_MASK = 63L;

    private final boolean enabled;
    private final long maxEvents;
    private final EventType lifecycleEventType;
    private final EventType captureLossEventType;
    private final IdentityHashMap<Object, Resource> retired = new IdentityHashMap<>();
    private long attempts;
    private long committed;
    private long dropped;
    private long nextCaptureSequence;
    private boolean failedClosed;
    private final long[] stageAttempts = new long[Stage.values().length];
    private final long[] stageCommitted = new long[Stage.values().length];

    RtSectionBlasLifecycleFlightRecorder() {
        this(ENABLED, DEFAULT_MAX_EVENTS);
    }

    RtSectionBlasLifecycleFlightRecorder(boolean enabled, long maxEvents) {
        if (maxEvents <= 0L) {
            throw new IllegalArgumentException("section BLAS lifecycle maxEvents must be positive");
        }
        this.enabled = enabled;
        this.maxEvents = maxEvents;
        EventType lifecycleType = null;
        EventType lossType = null;
        boolean initializationFailed = false;
        if (enabled) {
            try {
                lifecycleType = EventType.getEventType(RtSectionBlasLifecycleEvent.class);
                lossType = EventType.getEventType(RtSectionBlasCaptureLossEvent.class);
            } catch (RuntimeException | LinkageError ignored) {
                /*
                 * JFR is an optional diagnostic sink.  A missing JFR runtime,
                 * a malformed event declaration, or an agent-side failure must
                 * never prevent the renderer cache from being constructed.
                 */
                initializationFailed = true;
            }
        }
        this.lifecycleEventType = lifecycleType;
        this.captureLossEventType = lossType;
        this.failedClosed = initializationFailed;
    }

    void record(
            String stage,
            SectionKey key,
            long contentRevision,
            long buildSequence,
            long resourceRevision,
            long safeAfterRevision,
            String outcome,
            RendererFrameCausality causality
    ) {
        record(stage, key, contentRevision, buildSequence, resourceRevision, safeAfterRevision,
                outcome, causality, BatchEvidence.NONE);
    }

    private void record(
            String stage,
            SectionKey key,
            long contentRevision,
            long buildSequence,
            long resourceRevision,
            long safeAfterRevision,
            String outcome,
            RendererFrameCausality causality,
            BatchEvidence batchEvidence
    ) {
        record(stage, key, contentRevision, buildSequence, resourceRevision, safeAfterRevision,
                outcome, causality, batchEvidence, "none");
    }

    private void record(
            String stage,
            SectionKey key,
            long contentRevision,
            long buildSequence,
            long resourceRevision,
            long safeAfterRevision,
            String outcome,
            RendererFrameCausality causality,
            BatchEvidence batchEvidence,
            String retirementReason
    ) {
        if (!enabled || failedClosed) {
            return;
        }
        try {
            Stage lifecycleStage = Stage.valueOf(Objects.requireNonNull(stage, "stage"));
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(causality, "causality");
            Objects.requireNonNull(batchEvidence, "batchEvidence");
            Objects.requireNonNull(retirementReason, "retirementReason");
            stageAttempts[lifecycleStage.ordinal()]++;
            if (!admit()) {
                return;
            }
            RtSectionBlasLifecycleEvent event = new RtSectionBlasLifecycleEvent();
            event.stage = lifecycleStage.name();
            event.sectionX = key.x();
            event.sectionY = key.y();
            event.sectionZ = key.z();
            event.contentRevision = contentRevision;
            event.buildSequence = buildSequence;
            event.resourceRevision = resourceRevision;
            event.safeAfterRevision = safeAfterRevision;
            event.outcome = outcome;
            event.retirementReason = retirementReason;
            event.traceId = causality.traceId();
            event.causalitySource = causality.source().name();
            event.frameSequence = causality.frameSequence();
            event.captureSequence = nextCaptureSequence;
            event.droppedBefore = dropped;
            event.batchSectionCount = batchEvidence.sectionCount();
            event.batchTriangleCount = batchEvidence.triangleCount();
            event.interactiveSectionCount = batchEvidence.interactiveSectionCount();
            event.preferredSectionCount = batchEvidence.preferredSectionCount();
            event.gpuBatchesAhead = batchEvidence.gpuBatchesAhead();
            event.gpuSubmissionWindow = batchEvidence.gpuSubmissionWindow();
            event.elapsedNanos = batchEvidence.elapsedNanos();
            event.gpuExecutionNanos = batchEvidence.gpuExecutionNanos();
            event.lastNotReadyToObservationNanos = batchEvidence.lastNotReadyToObservationNanos();
            event.fenceNotReadyPolls = batchEvidence.fenceNotReadyPolls();
            event.commit();
            committed++;
            stageCommitted[lifecycleStage.ordinal()]++;
        } catch (RuntimeException | LinkageError failure) {
            failedClosed = true;
        }
    }

    /**
     * Captures the immutable metadata of every section still owned by one native submission.
     * Mapping the batch here keeps JFR failure policy and event-shape ownership out of the Vulkan
     * scheduler; an observer failure closes only this sink and cannot interrupt GPU polling.
     */
    void recordPending(RtPendingSectionBlasBuild pending, Stage stage, String outcome) {
        recordPending(pending, stage, outcome, BatchEvidence.NONE);
    }

    void recordPending(
            RtPendingSectionBlasBuild pending,
            Stage stage,
            String outcome,
            BatchEvidence batchEvidence
    ) {
        if (!enabled || failedClosed) {
            return;
        }
        try {
            Objects.requireNonNull(pending, "pending");
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(outcome, "outcome");
            for (SectionTriangleMesh mesh : pending.meshes()) {
                SectionKey key = mesh.key();
                record(
                        stage.name(),
                        key,
                        pending.contentRevision(key),
                        pending.sequence(),
                        -1L,
                        -1L,
                        outcome,
                        pending.causality(key),
                        batchEvidence
                );
                if (failedClosed) {
                    return;
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            failedClosed = true;
        }
    }

    void applied(
            Object resource,
            SectionKey key,
            long contentRevision,
            long buildSequence,
            long resourceRevision,
            RendererFrameCausality causality,
            String outcome
    ) {
        try {
            if (!enabled || failedClosed) {
                return;
            }
            Objects.requireNonNull(resource, "resource");
            retired.remove(resource);
        } catch (RuntimeException | LinkageError failure) {
            failedClosed = true;
            return;
        }
        try {
            record("APPLIED", key, contentRevision, buildSequence, resourceRevision, -1L, outcome, causality);
            if (!failedClosed) {
                retired.put(resource, new Resource(key, contentRevision, buildSequence, resourceRevision, causality));
            }
        } catch (RuntimeException | LinkageError failure) {
            failedClosed = true;
        }
    }

    void retireQueued(
            Object resource,
            SectionKey key,
            long safeAfterRevision,
            long resourceRevision,
            long contentRevision,
            long buildSequence,
            RendererFrameCausality causality,
            RtSectionBlasRetirementLifecycle.Reason reason
    ) {
        try {
            if (!enabled || failedClosed) {
                return;
            }
            Resource identity = retired.remove(Objects.requireNonNull(resource, "resource"));
            Resource metadata = identity == null
                    ? new Resource(key, contentRevision, buildSequence, resourceRevision, causality)
                    : identity;
            Resource retiredResource = metadata.withSafeAfter(safeAfterRevision, reason);
            retired.put(resource, retiredResource);
            record("RETIRE_QUEUED", metadata.key(), metadata.contentRevision(), metadata.buildSequence(),
                    metadata.resourceRevision(), safeAfterRevision, "queued", metadata.causality(),
                    BatchEvidence.NONE, reason.name());
        } catch (RuntimeException | LinkageError failure) {
            failedClosed = true;
        }
    }

    void releaseThrough(long protectedRevision, boolean batchSucceeded) {
        try {
            if (!enabled || failedClosed) {
                return;
            }
            /*
             * IdentityHashMap's entry iterator allocates a wrapper per visited resource. This is a
             * frame-boundary diagnostic path, so walk stable identity keys and retire in place.
             */
            Iterator<Object> identities = retired.keySet().iterator();
            while (identities.hasNext()) {
                Object identity = identities.next();
                Resource resource = retired.get(identity);
                if (resource.safeAfterRevision() > protectedRevision) {
                    continue;
                }
                record("RELEASED", resource.key(), resource.contentRevision(), resource.buildSequence(),
                        resource.resourceRevision(), resource.safeAfterRevision(),
                        batchSucceeded ? "released" : "releaseBatchFailed", resource.causality(),
                        BatchEvidence.NONE, resource.retirementReason());
                identities.remove();
            }
        } catch (RuntimeException | LinkageError failure) {
            failedClosed = true;
        }
    }

    void releaseAll(boolean batchSucceeded) {
        try {
            if (!enabled || failedClosed) {
                return;
            }
            for (Resource resource : retired.values()) {
                record("RELEASED", resource.key(), resource.contentRevision(), resource.buildSequence(),
                        resource.resourceRevision(), resource.safeAfterRevision(),
                        batchSucceeded ? "releasedOnClose" : "releaseBatchFailedOnClose", resource.causality(),
                        BatchEvidence.NONE, resource.retirementReason());
            }
            retired.clear();
        } catch (RuntimeException | LinkageError failure) {
            failedClosed = true;
        }
    }

    Snapshot snapshot() {
        return new Snapshot(enabled, failedClosed, attempts, committed, dropped, retired.size(),
                stageAttempts.clone(), stageCommitted.clone());
    }

    private boolean admit() {
        if (lifecycleEventType == null || !lifecycleEventType.isEnabled()) {
            return false;
        }
        attempts++;
        long sequence = attempts;
        if (sequence <= maxEvents || ((sequence - maxEvents) & OVERFLOW_SAMPLE_MASK) == 0L) {
            nextCaptureSequence = sequence;
            return true;
        }
        dropped++;
        if (dropped == 1L || (dropped & (dropped - 1L)) == 0L) {
            recordCaptureLoss(sequence);
        }
        return false;
    }

    private void recordCaptureLoss(long attemptedEvents) {
        if (captureLossEventType == null || !captureLossEventType.isEnabled()) {
            return;
        }
        try {
            RtSectionBlasCaptureLossEvent event = new RtSectionBlasCaptureLossEvent();
            event.maxEvents = maxEvents;
            event.attemptedEvents = attemptedEvents;
            event.droppedEventsLowerBound = dropped;
            event.overflowSampleStride = OVERFLOW_SAMPLE_MASK + 1L;
            event.commit();
        } catch (RuntimeException | LinkageError failure) {
            failedClosed = true;
        }
    }

    private static long positiveLongProperty(String property, long defaultValue) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0L ? parsed : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    enum Stage {
        RECOVERY_MISSING_EXACT_SOURCE,
        RECOVERY_REQUEUED,
        BLAS_RECORD_SUBMIT,
        GPU_COMPLETE,
        APPLIED,
        RETIRE_QUEUED,
        RELEASED
    }

    record BatchEvidence(
            int sectionCount,
            long triangleCount,
            int interactiveSectionCount,
            int preferredSectionCount,
            int gpuBatchesAhead,
            int gpuSubmissionWindow,
            long elapsedNanos,
            long gpuExecutionNanos,
            long lastNotReadyToObservationNanos,
            long fenceNotReadyPolls
    ) {
        private static final BatchEvidence NONE = new BatchEvidence(-1, -1L, -1, -1, -1, -1, -1L, -1L, -1L, -1L);

        BatchEvidence {
            if (sectionCount < -1 || triangleCount < -1L || interactiveSectionCount < -1
                    || preferredSectionCount < -1 || gpuBatchesAhead < -1
                    || gpuSubmissionWindow < -1 || elapsedNanos < -1L || gpuExecutionNanos < -1L
                    || lastNotReadyToObservationNanos < -1L || fenceNotReadyPolls < -1L) {
                throw new IllegalArgumentException("section BLAS batch evidence must be -1 or non-negative");
            }
        }
    }

    record Snapshot(
            boolean enabled,
            boolean failedClosed,
            long attempts,
            long committed,
            long dropped,
            int trackedRetirements,
            long[] stageAttempts,
            long[] stageCommitted
    ) {
        Snapshot {
            stageAttempts = stageAttempts.clone();
            stageCommitted = stageCommitted.clone();
        }
    }

    private record Resource(
            SectionKey key,
            long contentRevision,
            long buildSequence,
            long resourceRevision,
            RendererFrameCausality causality,
            long safeAfterRevision,
            String retirementReason
    ) {
        private Resource(SectionKey key, long contentRevision, long buildSequence,
                         long resourceRevision, RendererFrameCausality causality) {
            this(key, contentRevision, buildSequence, resourceRevision, causality, Long.MAX_VALUE, "none");
        }

        private Resource withSafeAfter(
                long safeAfterRevision,
                RtSectionBlasRetirementLifecycle.Reason reason
        ) {
            return new Resource(
                    key,
                    contentRevision,
                    buildSequence,
                    resourceRevision,
                    causality,
                    safeAfterRevision,
                    Objects.requireNonNull(reason, "reason").name()
            );
        }
    }
}
