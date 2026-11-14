package top.ceroxe.rt.renderer.diagnostics;

import jdk.jfr.*;
import top.ceroxe.rt.renderer.RendererCausalityEvidence;
import top.ceroxe.rt.renderer.RendererCausalityTrace;
import top.ceroxe.rt.renderer.RendererFrameCausality;
import top.ceroxe.rt.renderer.RtCausalitySink;
import top.ceroxe.rt.renderer.scene.SectionKey;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Smoke-only, allocation-free recent-event recorder for the RT terrain path.
 *
 * <p>host owns frustum relevance and completed-section state, whereas the
 * renderer owns the subsequent immutable snapshot, CPU build, BLAS, TLAS and
 * presentation lifetimes. A missing terrain section can only be diagnosed when
 * those independently-owned transitions share a stable section coordinate and
 * revision. Exact per-stage totals are kept separately from a preallocated ring
 * containing only the 64 most recent primitive facts. The producer path has no
 * logger, collection, string construction, lock, or file I/O. Human-readable
 * text is materialized only when smoke asks for a terminal evidence dump.</p>
 */
public final class RtSceneCausalityRecorder {
    /**
     * Host-visible mutation stage.
     */
    public static final int HOST_VISIBLE = 1;
    /**
     * Bridge extraction start stage.
     */
    public static final int BRIDGE_EXTRACT_BEGIN = 2;
    /**
     * Bridge extraction completion stage.
     */
    public static final int BRIDGE_EXTRACT_COMPLETE = 3;
    /**
     * Scene publication stage.
     */
    public static final int SCENE_PUBLISHED = 4;
    /**
     * CPU ticket stage.
     */
    public static final int CPU_TICKET = 5;
    /**
     * CPU queue stage.
     */
    public static final int CPU_QUEUE = 6;
    /**
     * CPU build start stage.
     */
    public static final int CPU_BUILD_BEGIN = 7;
    /**
     * CPU build completion stage.
     */
    public static final int CPU_BUILD_COMPLETE = 8;
    /**
     * CPU mesh commit stage.
     */
    public static final int CPU_MESH_COMMITTED = 9;
    /**
     * BLAS generation stage.
     */
    public static final int BLAS_GENERATION = 10;
    /**
     * BLAS activation stage.
     */
    public static final int BLAS_ACTIVE = 11;
    /**
     * World TLAS binding stage.
     */
    public static final int WORLD_TLAS_BOUND = 12;
    /**
     * GPU dispatch stage.
     */
    public static final int GPU_DISPATCH = 13;
    /**
     * GPU completion stage.
     */
    public static final int GPU_COMPLETED = 14;
    /**
     * Shared-frame export stage.
     */
    public static final int SHARED_EXPORTED = 15;
    /**
     * Shared-frame presentation stage.
     */
    public static final int SHARED_PRESENTED = 16;
    /**
     * Host-hidden transition stage.
     */
    public static final int HOST_HIDDEN = 17;
    /**
     * Pending work selected stage.
     */
    public static final int PENDING_SELECTED = 18;
    /**
     * Pending work deferred stage.
     */
    public static final int PENDING_DEFERRED = 19;
    /**
     * Pending work rejected stage.
     */
    public static final int PENDING_REJECTED = 20;
    /**
     * CPU work deferred stage.
     */
    public static final int CPU_DEFERRED = 21;
    /**
     * CPU work failed stage.
     */
    public static final int CPU_FAILED = 22;
    /**
     * BLAS removal stage.
     */
    public static final int BLAS_REMOVED = 23;
    /**
     * Frame rejection stage.
     */
    public static final int FRAME_REJECTED = 24;
    /**
     * Section TLAS binding stage.
     */
    public static final int SECTION_TLAS_BOUND = 25;
    /**
     * CPU work supersession stage.
     */
    public static final int CPU_SUPERSEDED = 26;
    /**
     * Host takeover acceptance stage.
     */
    public static final int HOST_TAKEOVER_ACCEPTED = 27;
    /**
     * Host takeover rejection stage.
     */
    public static final int HOST_TAKEOVER_REJECTED = 28;
    /**
     * No blocking or discard reason.
     */
    public static final int REASON_NONE = 0;
    /**
     * Presentation eligibility rejection.
     */
    public static final int REASON_PRESENTATION_ELIGIBILITY = 1;
    /**
     * Invalid frame-state rejection.
     */
    public static final int REASON_INVALID_FRAME_STATE = 2;
    /**
     * Dispatch interval throttle.
     */
    public static final int REASON_DISPATCH_INTERVAL = 3;
    /**
     * Pending submission limit.
     */
    public static final int REASON_MAX_PENDING_SUBMISSIONS = 4;
    /**
     * Busy frame-slot ring.
     */
    public static final int REASON_FRAME_SLOT_RING_BUSY = 5;
    /**
     * No writable frame slot.
     */
    public static final int REASON_NO_WRITABLE_FRAME_SLOT = 6;
    /**
     * Successful submission reason.
     */
    public static final int REASON_SUBMITTED = 7;
    /**
     * CPU runtime exception.
     */
    public static final int REASON_CPU_RUNTIME_EXCEPTION = 8;
    /**
     * CPU linkage error.
     */
    public static final int REASON_CPU_LINKAGE_ERROR = 9;
    /**
     * CPU out-of-memory failure.
     */
    public static final int REASON_CPU_OUT_OF_MEMORY = 10;
    /**
     * Missing source evidence.
     */
    public static final int REASON_MISSING_SOURCE = 11;
    /**
     * Presentation failure.
     */
    public static final int REASON_PRESENTATION_FAILED = 12;
    /**
     * Visual acceptance gate.
     */
    public static final int REASON_VISUAL_GATE = 13;
    private static final int RECENT_EVENT_CAPACITY = 64;
    private static final int TRACE_EVENT_CAPACITY = 16;
    private static final int STABLE_FRAME_SAMPLE_MASK = 63;
    private static final long MAX_JFR_EVENTS = positiveLongProperty(
            "top.ceroxe.rt.takeoverFlightRecorder.causalityMaxJfrEvents",
            8_192L
    );
    private static final long JFR_OVERFLOW_SAMPLE_MASK = 63L;
    private static final boolean ENABLED = Boolean.getBoolean("top.ceroxe.rt.takeoverFlightRecorder.enabled");
    private static final Entry[] ENTRIES = ENABLED ? entries(RECENT_EVENT_CAPACITY) : new Entry[0];
    private static final VarHandle ENTRY_STATE = entryStateHandle();
    private static final AtomicLong NEXT_SEQUENCE = new AtomicLong();
    private static final AtomicLong NEXT_JOIN_SEQUENCE = new AtomicLong();
    private static final AtomicLong NEXT_SESSION_ID = new AtomicLong();
    private static final AtomicLong SESSION_ID = new AtomicLong();
    private static final AtomicLong SESSION_FIRST_SEQUENCE = new AtomicLong(1L);
    private static final AtomicLong SESSION_START_NANOS = new AtomicLong();
    private static final AtomicLong RECORDED_EVENTS = new AtomicLong();
    private static final AtomicLong RING_BUSY_DROPS = new AtomicLong();
    private static final AtomicLong SESSION_FIRST_RECORDED_EVENTS = new AtomicLong();
    private static final AtomicLong SESSION_FIRST_RING_BUSY_DROPS = new AtomicLong();
    private static final AtomicLong JFR_ATTEMPTS = new AtomicLong();
    private static final AtomicLong JFR_COMMITTED = new AtomicLong();
    private static final AtomicLong JFR_DROPPED = new AtomicLong();
    private static final EventType CAUSALITY_EVENT_TYPE = EventType.getEventType(RtCausalityEvent.class);
    private static final EventType JFR_CAPTURE_LOSS_EVENT_TYPE = EventType.getEventType(RtCausalityCaptureLossEvent.class);
    private static final AtomicLong SESSION_FIRST_JFR_ATTEMPTS = new AtomicLong();
    private static final AtomicLong SESSION_FIRST_JFR_COMMITTED = new AtomicLong();
    private static final AtomicLong SESSION_FIRST_JFR_DROPPED = new AtomicLong();
    private static final AtomicBoolean DUMPED = new AtomicBoolean();
    private static final TraceSlot[] TRACE_SLOTS = ENABLED ? traceSlots(128) : new TraceSlot[0];
    private static final AtomicLongArray STAGE_EVENTS = new AtomicLongArray(HOST_TAKEOVER_REJECTED + 1);
    private static final AtomicLongArray SESSION_FIRST_STAGE_EVENTS = new AtomicLongArray(HOST_TAKEOVER_REJECTED + 1);
    private static int nextTraceSlot;

    private RtSceneCausalityRecorder() {
    }

    /**
     * Reports whether causality capture is enabled.
     *
     * @return capture enablement state
     */
    public static boolean enabled() {
        return ENABLED;
    }

    /**
     * Returns the current evidence-session identifier.
     *
     * @return session identifier
     */
    public static long sessionId() {
        return SESSION_ID.get();
    }

    /**
     * Returns bounded retained evidence for a trace.
     *
     * @param traceId trace identifier, or {@code -1} for unavailable
     * @return retained or explicitly unavailable trace
     */
    public static RendererCausalityTrace trace(long traceId) {
        if (traceId < -1L) {
            throw new IllegalArgumentException("trace id must be -1 or greater");
        }
        if (traceId <= 0L || !ENABLED) {
            return RendererCausalityTrace.unavailable(traceId, "causalityRecorderDisabledOrUntraced");
        }
        synchronized (TRACE_SLOTS) {
            for (TraceSlot slot : TRACE_SLOTS) {
                if (slot.traceId == traceId) {
                    return slot.snapshot();
                }
            }
        }
        return RendererCausalityTrace.unavailable(traceId, "traceNotRetainedInBoundedIndex");
    }

    /**
     * Records scene publication identity without per-section revisions.
     *
     * @param frameTransactionTraceId frame transaction trace
     * @param publicationGeneration   publication generation
     * @param descriptorGeneration    descriptor generation
     * @param worldTlasRevision       world TLAS revision
     * @param dynamicTlasRevision     dynamic TLAS revision
     * @param dynamicSceneRevision    dynamic scene revision
     * @param reasonCode              primitive reason code
     */
    public static void recordPublication(
            long frameTransactionTraceId,
            long publicationGeneration,
            long descriptorGeneration,
            long worldTlasRevision,
            long dynamicTlasRevision,
            long dynamicSceneRevision,
            int reasonCode
    ) {
        recordPublication(
                frameTransactionTraceId,
                publicationGeneration,
                descriptorGeneration,
                worldTlasRevision,
                dynamicTlasRevision,
                dynamicSceneRevision,
                reasonCode,
                Map.of()
        );
    }

    /**
     * Records scene publication identity and exact section revisions.
     *
     * @param frameTransactionTraceId frame transaction trace
     * @param publicationGeneration   publication generation
     * @param descriptorGeneration    descriptor generation
     * @param worldTlasRevision       world TLAS revision
     * @param dynamicTlasRevision     dynamic TLAS revision
     * @param dynamicSceneRevision    dynamic scene revision
     * @param reasonCode              primitive reason code
     * @param sectionContentRevisions exact section revisions
     */
    public static void recordPublication(
            long frameTransactionTraceId,
            long publicationGeneration,
            long descriptorGeneration,
            long worldTlasRevision,
            long dynamicTlasRevision,
            long dynamicSceneRevision,
            int reasonCode,
            Map<SectionKey, Long> sectionContentRevisions
    ) {
        recordJoinEvent(
                SCENE_PUBLISHED,
                -1L,
                -1L,
                -1L,
                frameTransactionTraceId,
                publicationGeneration,
                descriptorGeneration,
                -1L,
                -1L,
                -1L,
                worldTlasRevision,
                dynamicTlasRevision,
                dynamicSceneRevision,
                reasonCode
        );
    }

    /**
     * Records GPU dispatch join identity.
     *
     * @param causality             frame causality, or {@code null}
     * @param frameSequence         frame sequence
     * @param publicationGeneration publication generation
     * @param descriptorGeneration  descriptor generation
     * @param dispatchSequence      dispatch sequence
     * @param reasonCode            primitive reason code
     */
    public static void recordDispatch(
            RendererFrameCausality causality,
            long frameSequence,
            long publicationGeneration,
            long descriptorGeneration,
            long dispatchSequence,
            int reasonCode
    ) {
        recordJoinEvent(
                GPU_DISPATCH,
                frameSequence,
                -1L,
                -1L,
                causality == null ? -1L : causality.traceId(),
                publicationGeneration,
                descriptorGeneration,
                dispatchSequence,
                -1L,
                -1L,
                0L,
                0L,
                0L,
                reasonCode
        );
    }

    /**
     * Records presentation join identity.
     *
     * @param causality     frame causality, or {@code null}
     * @param frameSequence frame sequence
     * @param presentToken  presentation token
     * @param reasonCode    primitive reason code
     */
    public static void recordPresent(
            RendererFrameCausality causality,
            long frameSequence,
            long presentToken,
            int reasonCode
    ) {
        recordJoinEvent(
                SHARED_PRESENTED,
                frameSequence,
                -1L,
                -1L,
                causality == null ? -1L : causality.traceId(),
                -1L,
                -1L,
                -1L,
                presentToken,
                -1L,
                0L,
                0L,
                0L,
                reasonCode
        );
    }

    /**
     * Starts a new smoke evidence window without clearing or allocating the ring.
     */
    public static void reset() {
        if (!ENABLED) {
            return;
        }
        SESSION_FIRST_SEQUENCE.set(NEXT_SEQUENCE.get() + 1L);
        SESSION_ID.set(NEXT_SESSION_ID.incrementAndGet());
        SESSION_START_NANOS.set(System.nanoTime());
        SESSION_FIRST_RECORDED_EVENTS.set(RECORDED_EVENTS.get());
        SESSION_FIRST_RING_BUSY_DROPS.set(RING_BUSY_DROPS.get());
        SESSION_FIRST_JFR_ATTEMPTS.set(JFR_ATTEMPTS.get());
        SESSION_FIRST_JFR_COMMITTED.set(JFR_COMMITTED.get());
        SESSION_FIRST_JFR_DROPPED.set(JFR_DROPPED.get());
        for (int stage = HOST_VISIBLE; stage <= HOST_TAKEOVER_REJECTED; stage++) {
            SESSION_FIRST_STAGE_EVENTS.set(stage, STAGE_EVENTS.get(stage));
        }
        DUMPED.set(false);
    }

    /**
     * Records one section lifecycle scalar event.
     *
     * @param stage           lifecycle stage
     * @param key             section key, or {@code null} to omit
     * @param contentRevision content revision
     * @param generation      stage generation
     * @param value           stage-specific value
     * @param flags           stage flags
     */
    public static void recordSection(
            int stage,
            SectionKey key,
            long contentRevision,
            long generation,
            long value,
            int flags
    ) {
        if (!ENABLED || key == null) {
            return;
        }
        record(stage, key.x(), key.y(), key.z(), contentRevision, generation, value, flags);
    }

    /**
     * JFR-only typed section identity; no chain is reconstructed from text or timestamps.
     *
     * @param causality             immutable renderer causality identity
     * @param key                   section identity
     * @param contentRevision       content revision
     * @param geometryRevision      geometry revision
     * @param materialRevision      material revision
     * @param buildSequence         build sequence
     * @param blasGeneration        BLAS generation
     * @param tlasRevision          TLAS revision
     * @param publicationGeneration publication generation
     * @param discardReason         stable discard reason code
     */
    public static void recordSectionGeneration(
            RendererFrameCausality causality,
            SectionKey key,
            long contentRevision,
            long geometryRevision,
            long materialRevision,
            long buildSequence,
            long blasGeneration,
            long tlasRevision,
            long publicationGeneration,
            int discardReason
    ) {
        if (!ENABLED || key == null || causality == null || !CAUSALITY_EVENT_TYPE.isEnabled()
                || !reserveJfrEvent(BLAS_ACTIVE)) {
            if (ENABLED && causality != null) {
                rememberTrace(causality.traceId(), BLAS_ACTIVE, publicationGeneration);
            }
            return;
        }
        rememberTrace(causality.traceId(), BLAS_ACTIVE, publicationGeneration);
        RtCausalityEvent event = new RtCausalityEvent();
        event.sessionId = SESSION_ID.get();
        event.eventSequence = NEXT_JOIN_SEQUENCE.incrementAndGet();
        event.traceId = causality.traceId();
        event.frameTransactionTraceId = causality.traceId();
        event.frameSequence = causality.frameSequence();
        event.submissionSource = causality.source().ordinal();
        event.stage = BLAS_ACTIVE;
        event.sectionX = key.x();
        event.sectionY = key.y();
        event.sectionZ = key.z();
        event.sectionRevision = contentRevision;
        event.geometryRevision = geometryRevision;
        event.materialRevision = materialRevision;
        event.buildSequence = buildSequence;
        event.blasGeneration = blasGeneration;
        event.tlasRevision = tlasRevision;
        event.publicationGeneration = publicationGeneration;
        event.discardReason = discardReason;
        event.commit();
        JFR_COMMITTED.incrementAndGet();
    }

    /**
     * JFR-only typed entity identity; the renderer supplies the stable id directly.
     *
     * @param causality             immutable renderer causality identity
     * @param entityId              stable entity identifier
     * @param assetRevision         asset revision
     * @param dynamicSceneRevision  dynamic scene revision
     * @param dynamicBlasRevision   dynamic BLAS revision
     * @param dynamicTlasRevision   dynamic TLAS revision
     * @param publicationGeneration publication generation
     * @param discardReason         stable discard reason code
     */
    public static void recordEntityGeneration(
            RendererFrameCausality causality,
            long entityId,
            long assetRevision,
            long dynamicSceneRevision,
            long dynamicBlasRevision,
            long dynamicTlasRevision,
            long publicationGeneration,
            int discardReason
    ) {
        if (!ENABLED || causality == null || entityId < 0L || !CAUSALITY_EVENT_TYPE.isEnabled()
                || !reserveJfrEvent(GPU_DISPATCH)) {
            if (ENABLED && causality != null) {
                rememberTrace(causality.traceId(), GPU_DISPATCH, publicationGeneration);
            }
            return;
        }
        rememberTrace(causality.traceId(), GPU_DISPATCH, publicationGeneration);
        RtCausalityEvent event = new RtCausalityEvent();
        event.sessionId = SESSION_ID.get();
        event.eventSequence = NEXT_JOIN_SEQUENCE.incrementAndGet();
        event.traceId = causality.traceId();
        event.frameTransactionTraceId = causality.traceId();
        event.frameSequence = causality.frameSequence();
        event.submissionSource = causality.source().ordinal();
        event.stage = GPU_DISPATCH;
        event.entityId = entityId;
        event.assetRevision = assetRevision;
        event.dynamicSceneRevision = dynamicSceneRevision;
        event.dynamicBlasRevision = dynamicBlasRevision;
        event.dynamicTlasRevision = dynamicTlasRevision;
        event.publicationGeneration = publicationGeneration;
        event.discardReason = discardReason;
        event.commit();
        JFR_COMMITTED.incrementAndGet();
    }

    /**
     * Records one world TLAS binding event.
     *
     * @param worldRevision world revision
     * @param sectionCount  section count
     * @param viewRevision  view revision
     * @param flags         stage flags
     */
    public static void recordWorld(long worldRevision, long sectionCount, long viewRevision, int flags) {
        if (!ENABLED) {
            return;
        }
        record(WORLD_TLAS_BOUND, Integer.MIN_VALUE, 0, 0, worldRevision, viewRevision, sectionCount, flags);
    }

    /**
     * Records a sampled frame lifecycle event.
     *
     * @param stage           frame stage
     * @param frameSequence   frame sequence
     * @param relatedRevision related revision
     * @param value           stage-specific value
     * @param flags           stage flags
     */
    public static void recordFrame(int stage, long frameSequence, long relatedRevision, long value, int flags) {
        if (!ENABLED) {
            return;
        }
        /*
         * A 500+ FPS smoke emits four successful frame edges per frame. Those
         * edges are already counted exactly by RtCore and presentation telemetry;
         * retaining every identical success would overwrite the section chain
         * before a moving-camera capture completes. Keep every rejection and
         * sample stable success frames at a fixed cadence. Section lifecycle
         * transitions are never sampled.
         */
        if (stage != FRAME_REJECTED
                && stage != HOST_TAKEOVER_REJECTED
                && (frameSequence & STABLE_FRAME_SAMPLE_MASK) != 0L) {
            return;
        }
        record(stage, Integer.MAX_VALUE, 0, 0, relatedRevision, frameSequence, value, flags);
    }

    /**
     * Records typed frame causality evidence.
     *
     * @param stage     frame stage
     * @param causality immutable frame causality
     * @param evidence  immutable reason and value evidence
     */
    public static void recordFrameCausality(
            int stage,
            RendererFrameCausality causality,
            RendererCausalityEvidence evidence
    ) {
        Objects.requireNonNull(causality, "causality");
        Objects.requireNonNull(evidence, "evidence");
        recordJoinEvent(
                stage,
                causality.frameSequence(),
                evidence.relatedRevision(),
                -1L,
                causality.traceId(),
                evidence.eventValue(),
                -1L,
                -1L,
                -1L,
                -1L,
                -1L,
                -1L,
                -1L,
                reasonCode(evidence.reason())
        );
    }

    private static int reasonCode(RtCausalitySink.Reason reason) {
        return switch (reason) {
            case NONE -> REASON_NONE;
            case SUBMITTED -> REASON_SUBMITTED;
            case PRESENTATION_ELIGIBILITY -> REASON_PRESENTATION_ELIGIBILITY;
            case INVALID_FRAME_STATE -> REASON_INVALID_FRAME_STATE;
            case DISPATCH_INTERVAL -> REASON_DISPATCH_INTERVAL;
            case MAX_PENDING_SUBMISSIONS -> REASON_MAX_PENDING_SUBMISSIONS;
            case FRAME_SLOT_RING_BUSY -> REASON_FRAME_SLOT_RING_BUSY;
            case NO_WRITABLE_FRAME_SLOT -> REASON_NO_WRITABLE_FRAME_SLOT;
            case VISUAL_GATE -> REASON_VISUAL_GATE;
        };
    }

    /**
     * Known dispatch reason token; producer-side comparison does not allocate.
     *
     * @param reason stable dispatch reason token
     * @return corresponding primitive reason code
     */
    public static int reasonCode(String reason) {
        if ("presentationEligibilityGate".equals(reason)) return REASON_PRESENTATION_ELIGIBILITY;
        if ("invalidFrameState".equals(reason)) return REASON_INVALID_FRAME_STATE;
        if ("dispatchInterval".equals(reason)) return REASON_DISPATCH_INTERVAL;
        if ("maxPendingSubmissions".equals(reason)) return REASON_MAX_PENDING_SUBMISSIONS;
        if ("frameSlotRingBusy".equals(reason)) return REASON_FRAME_SLOT_RING_BUSY;
        if ("noWritableFrameSlot".equals(reason)) return REASON_NO_WRITABLE_FRAME_SLOT;
        if ("submitted".equals(reason)) return REASON_SUBMITTED;
        return REASON_NONE;
    }

    /**
     * Classifies worker failures without constructing a throwable description on
     * the worker hot path. The regular failure logger remains the source for the
     * full stack trace; this code makes the corresponding primitive event usable
     * when that logger is sampled or interleaved with another thread.
     *
     * @param failure worker failure
     * @return corresponding primitive failure code
     */
    public static int failureCode(Throwable failure) {
        if (failure instanceof OutOfMemoryError) return REASON_CPU_OUT_OF_MEMORY;
        if (failure instanceof LinkageError) return REASON_CPU_LINKAGE_ERROR;
        if (failure instanceof RuntimeException) return REASON_CPU_RUNTIME_EXCEPTION;
        return REASON_NONE;
    }

    /**
     * Maps final host ownership outcomes without retaining arbitrary reason strings.
     *
     * @param reason stable takeover reason token
     * @return corresponding primitive reason code
     */
    public static int takeoverReasonCode(String reason) {
        if ("missingSharedFrame".equals(reason) || "missingSnapshot".equals(reason)) return REASON_MISSING_SOURCE;
        if ("presentationFailed".equals(reason)) return REASON_PRESENTATION_FAILED;
        return REASON_VISUAL_GATE;
    }

    /**
     * Emits the bounded session dump at most once.
     *
     * @param trigger               stable terminal trigger
     * @param terminalFrameSequence terminal frame sequence
     */
    public static void dumpOnce(String trigger, long terminalFrameSequence) {
        if (!ENABLED || !DUMPED.compareAndSet(false, true)) {
            return;
        }
        long first = SESSION_FIRST_SEQUENCE.get();
        long last = NEXT_SEQUENCE.get();
        long retainedFirst = Math.max(first, Math.max(1L, last - ENTRIES.length + 1L));
        long sessionStartNanos = SESSION_START_NANOS.get();
        CoverageAccumulator coverage = new CoverageAccumulator(sessionStageEvents());
        int retained = 0;
        for (long sequence = retainedFirst; sequence <= last; sequence++) {
            Entry entry = ENTRIES[(int) ((sequence - 1L) % ENTRIES.length)];
            if (entry.observeIfStable(sequence, coverage)) {
                retained++;
            }
        }
        long traceFirst = retainedFirst;
        StringBuilder trace = new StringBuilder(RECENT_EVENT_CAPACITY * 160);
        int emittedTraceEvents = 0;
        for (long sequence = traceFirst; sequence <= last; sequence++) {
            Entry entry = ENTRIES[(int) ((sequence - 1L) % ENTRIES.length)];
            if (entry.appendIfStable(sequence, sessionStartNanos, trace, emittedTraceEvents > 0)) {
                emittedTraceEvents++;
            }
        }
        long recorded = Math.max(0L, RECORDED_EVENTS.get() - SESSION_FIRST_RECORDED_EVENTS.get());
        long ringBusyDrops = Math.max(0L, RING_BUSY_DROPS.get() - SESSION_FIRST_RING_BUSY_DROPS.get());
        long attempts = Math.max(0L, last - first + 1L);
        long overwritten = Math.max(0L, recorded - retained);
        boolean recentWindowStable = retained == Math.min(recorded, (long) ENTRIES.length)
                && ringBusyDrops == 0L;
        long jfrAttempts = Math.max(0L, JFR_ATTEMPTS.get() - SESSION_FIRST_JFR_ATTEMPTS.get());
        long jfrCommitted = Math.max(0L, JFR_COMMITTED.get() - SESSION_FIRST_JFR_COMMITTED.get());
        long jfrDropped = Math.max(0L, JFR_DROPPED.get() - SESSION_FIRST_JFR_DROPPED.get());
        /* Exact totals and bounded recent detail are emitted only on demand. */
        top.ceroxe.rt.renderer.RendererLog.info(
                "rt scene causality evidence: trigger={}, terminalFrame={}, recentCapacity={}, stableFrameSampleStride={}, attempts={}, recorded={}, recentRetained={}, detailsOutsideWindow={}, ringBusyDrops={}, inFlight={}, recentWindowStable={}, jfrAttempts={}, jfrCommitted={}, jfrDropped={}, jfrFullFidelityLimit={}, jfrOverflowSampleStride={}, recentEvents={}",
                trigger,
                terminalFrameSequence,
                ENTRIES.length,
                STABLE_FRAME_SAMPLE_MASK + 1,
                attempts,
                recorded,
                retained,
                overwritten,
                ringBusyDrops,
                Math.max(0L, attempts - recorded - ringBusyDrops),
                recentWindowStable,
                jfrAttempts,
                jfrCommitted,
                jfrDropped,
                MAX_JFR_EVENTS,
                JFR_OVERFLOW_SAMPLE_MASK + 1L,
                trace
        );
        top.ceroxe.rt.renderer.RendererLog.info(
                "rt scene causality coverage: trigger={}, terminalFrame={}, exactStageTotals={}, recentCoverage={}, recentMissingSamples={}",
                trigger,
                terminalFrameSequence,
                coverage.stageEventsFragment(),
                coverage.recentCoverageFragment(),
                coverage.missingSamples()
        );
    }

    static long eventCountForTesting() {
        return ENABLED ? Math.max(0L, NEXT_SEQUENCE.get() - SESSION_FIRST_SEQUENCE.get() + 1L) : 0L;
    }

    static int recentEventCapacityForTesting() {
        return ENTRIES.length;
    }

    static long stageEventCountForTesting(int stage) {
        if (!ENABLED || stage < HOST_VISIBLE || stage > HOST_TAKEOVER_REJECTED) {
            return 0L;
        }
        return Math.max(0L, STAGE_EVENTS.get(stage) - SESSION_FIRST_STAGE_EVENTS.get(stage));
    }

    private static void record(
            int stage,
            int sectionX,
            int sectionY,
            int sectionZ,
            long revision,
            long generationOrFrame,
            long value,
            int flags
    ) {
        long sequence = NEXT_SEQUENCE.incrementAndGet();
        if (stage >= HOST_VISIBLE && stage <= HOST_TAKEOVER_REJECTED) {
            STAGE_EVENTS.incrementAndGet(stage);
        }
        Entry entry = ENTRIES[(int) ((sequence - 1L) % ENTRIES.length)];
        long observedState = (long) ENTRY_STATE.getVolatile(entry);
        /*
         * This is a lossy diagnostic ring by design, but it must never be a
         * corrupt ring. A stalled producer from an older lap may not overwrite
         * a newer entry after the slot has been reused. In that rare case we
         * explicitly count the event as dropped instead of fabricating a chain.
         */
        if (isWriting(observedState)
                || observedState >= sequence
                || !ENTRY_STATE.compareAndSet(entry, observedState, writingState(sequence))) {
            RING_BUSY_DROPS.incrementAndGet();
            return;
        }
        entry.elapsedNanos = System.nanoTime();
        entry.threadId = Thread.currentThread().threadId();
        entry.stage = stage;
        entry.sectionX = sectionX;
        entry.sectionY = sectionY;
        entry.sectionZ = sectionZ;
        entry.revision = revision;
        entry.generationOrFrame = generationOrFrame;
        entry.value = value;
        entry.flags = flags;
        // Volatile publication is intentionally the final producer operation.
        entry.state = sequence;
        RECORDED_EVENTS.incrementAndGet();
        recordJfrEvent(sequence, stage, sectionX, sectionY, sectionZ, revision, generationOrFrame, value, flags);
    }

    /*
     * This is the machine-readable authority. Its fields deliberately retain
     * every join key without reconstructing ownership from a formatted message:
     * section coordinates/revision, frame sequence, publication-like generation,
     * and stage-specific value/reason live in one event stream.
     */
    private static void recordJfrEvent(
            long sequence,
            int stage,
            int sectionX,
            int sectionY,
            int sectionZ,
            long revision,
            long generationOrFrame,
            long value,
            int flags
    ) {
        if (!reserveJfrEvent(stage)) {
            return;
        }
        RtCausalityEvent event = new RtCausalityEvent();
        event.sessionId = SESSION_ID.get();
        event.eventSequence = sequence;
        event.stage = stage;
        event.sectionX = sectionX;
        event.sectionY = sectionY;
        event.sectionZ = sectionZ;
        event.sectionRevision = revision;
        event.generationOrFrame = generationOrFrame;
        event.value = value;
        event.flags = flags;
        event.commit();
        JFR_COMMITTED.incrementAndGet();
    }

    private static void recordJoinEvent(
            int stage,
            long frameSequence,
            long sectionRevision,
            long ingressSequence,
            long frameTransactionTraceId,
            long publicationGeneration,
            long descriptorGeneration,
            long dispatchSequence,
            long presentToken,
            long sectionX,
            long worldTlasRevision,
            long dynamicTlasRevision,
            long dynamicSceneRevision,
            int reasonCode
    ) {
        if (!ENABLED) {
            return;
        }
        rememberTrace(frameTransactionTraceId, stage, publicationGeneration);
        if (!reserveJfrEvent(stage)) {
            return;
        }
        RtCausalityEvent event = new RtCausalityEvent();
        event.sessionId = SESSION_ID.get();
        event.eventSequence = NEXT_JOIN_SEQUENCE.incrementAndGet();
        event.stage = stage;
        event.frameSequence = frameSequence;
        event.sectionRevision = sectionRevision;
        event.ingressSequence = ingressSequence;
        event.frameTransactionTraceId = frameTransactionTraceId;
        event.publicationGeneration = publicationGeneration;
        event.descriptorGeneration = descriptorGeneration;
        event.dispatchSequence = dispatchSequence;
        event.presentToken = presentToken;
        event.worldTlasRevision = worldTlasRevision;
        event.dynamicTlasRevision = dynamicTlasRevision;
        event.dynamicSceneRevision = dynamicSceneRevision;
        event.reasonCode = reasonCode;
        event.commit();
        JFR_COMMITTED.incrementAndGet();
    }

    /**
     * JFR is an optional evidence sink, not part of the renderer's correctness
     * path. Check the recording state before constructing an Event, then keep a
     * bounded exact window and a sparse overflow sample for long smokes.
     * Rejections and failures remain full fidelity because they are the facts
     * needed to explain a missing frame.
     */
    private static boolean reserveJfrEvent(int stage) {
        if (!ENABLED || !CAUSALITY_EVENT_TYPE.isEnabled()) {
            return false;
        }
        long sequence = JFR_ATTEMPTS.incrementAndGet();
        boolean critical = stage == CPU_FAILED
                || stage == FRAME_REJECTED
                || stage == PENDING_REJECTED
                || stage == HOST_TAKEOVER_REJECTED;
        if (critical || sequence <= MAX_JFR_EVENTS
                || ((sequence - MAX_JFR_EVENTS) & JFR_OVERFLOW_SAMPLE_MASK) == 0L) {
            return true;
        }
        long dropped = JFR_DROPPED.incrementAndGet();
        if (dropped == 1L || (dropped & (dropped - 1L)) == 0L) {
            recordJfrCaptureLoss(sequence, dropped);
        }
        return false;
    }

    private static void recordJfrCaptureLoss(long attemptedEvents, long droppedEvents) {
        if (!JFR_CAPTURE_LOSS_EVENT_TYPE.isEnabled()) {
            return;
        }
        RtCausalityCaptureLossEvent event = new RtCausalityCaptureLossEvent();
        event.maxEvents = MAX_JFR_EVENTS;
        event.attemptedEvents = attemptedEvents;
        event.droppedEventsLowerBound = droppedEvents;
        event.overflowSampleStride = JFR_OVERFLOW_SAMPLE_MASK + 1L;
        event.commit();
    }

    private static long[] sessionStageEvents() {
        long[] totals = new long[HOST_TAKEOVER_REJECTED + 1];
        for (int stage = HOST_VISIBLE; stage <= HOST_TAKEOVER_REJECTED; stage++) {
            totals[stage] = Math.max(0L, STAGE_EVENTS.get(stage) - SESSION_FIRST_STAGE_EVENTS.get(stage));
        }
        return totals;
    }

    private static Entry[] entries(int capacity) {
        Entry[] entries = new Entry[capacity];
        for (int index = 0; index < entries.length; index++) {
            entries[index] = new Entry();
        }
        return entries;
    }

    private static TraceSlot[] traceSlots(int capacity) {
        TraceSlot[] slots = new TraceSlot[capacity];
        for (int index = 0; index < capacity; index++) {
            slots[index] = new TraceSlot();
        }
        return slots;
    }

    private static void rememberTrace(long traceId, int stage, long publicationGeneration) {
        if (!ENABLED || traceId <= 0L || TRACE_SLOTS.length == 0) {
            return;
        }
        synchronized (TRACE_SLOTS) {
            for (TraceSlot slot : TRACE_SLOTS) {
                if (slot.traceId == traceId) {
                    slot.append(stage, publicationGeneration);
                    return;
                }
            }
            TraceSlot slot = TRACE_SLOTS[nextTraceSlot++ % TRACE_SLOTS.length];
            slot.traceId = traceId;
            slot.reset(traceId, stage, publicationGeneration);
        }
    }

    private static VarHandle entryStateHandle() {
        try {
            return MethodHandles.lookup().findVarHandle(Entry.class, "state", long.class);
        } catch (ReflectiveOperationException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static long writingState(long sequence) {
        return -(sequence + 1L);
    }

    private static boolean isWriting(long state) {
        return state < 0L;
    }

    private static String stageName(int stage) {
        return switch (stage) {
            case HOST_VISIBLE -> "hostVisible";
            case BRIDGE_EXTRACT_BEGIN -> "bridgeExtractBegin";
            case BRIDGE_EXTRACT_COMPLETE -> "bridgeExtractComplete";
            case SCENE_PUBLISHED -> "scenePublished";
            case CPU_TICKET -> "cpuTicket";
            case CPU_QUEUE -> "cpuQueue";
            case CPU_BUILD_BEGIN -> "cpuBuildBegin";
            case CPU_BUILD_COMPLETE -> "cpuBuildComplete";
            case CPU_MESH_COMMITTED -> "cpuMeshCommitted";
            case BLAS_GENERATION -> "blasGeneration";
            case BLAS_ACTIVE -> "blasActive";
            case WORLD_TLAS_BOUND -> "worldTlasBound";
            case GPU_DISPATCH -> "gpuDispatch";
            case GPU_COMPLETED -> "gpuCompleted";
            case SHARED_EXPORTED -> "sharedExported";
            case SHARED_PRESENTED -> "sharedPresented";
            case HOST_HIDDEN -> "hostHidden";
            case PENDING_SELECTED -> "pendingSelected";
            case PENDING_DEFERRED -> "pendingDeferred";
            case PENDING_REJECTED -> "pendingRejected";
            case CPU_DEFERRED -> "cpuDeferred";
            case CPU_FAILED -> "cpuFailed";
            case BLAS_REMOVED -> "blasRemoved";
            case FRAME_REJECTED -> "frameRejected";
            case SECTION_TLAS_BOUND -> "sectionTlasBound";
            case CPU_SUPERSEDED -> "cpuSuperseded";
            case HOST_TAKEOVER_ACCEPTED -> "hostTakeoverAccepted";
            case HOST_TAKEOVER_REJECTED -> "hostTakeoverRejected";
            default -> "unknown(" + stage + ')';
        };
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

    @Name("top.ceroxe.rt.RtCausality")
    @Label("RTRenderer RT Causality")
    @Category({"RTRenderer", "Renderer", "Causality"})
    @StackTrace(false)
    static final class RtCausalityEvent extends Event {
        long sessionId;
        long eventSequence;
        long traceId;
        int submissionSource;
        int stage;
        int sectionX;
        int sectionY;
        int sectionZ;
        long sectionRevision;
        long geometryRevision;
        long materialRevision;
        long buildSequence;
        long blasGeneration;
        long tlasRevision;
        long entityId;
        long assetRevision;
        long dynamicBlasRevision;
        int discardReason;
        long generationOrFrame;
        long value;
        int flags;
        long frameSequence;
        long ingressSequence;
        long frameTransactionTraceId;
        long publicationGeneration;
        long descriptorGeneration;
        long dispatchSequence;
        long presentToken;
        long worldTlasRevision;
        long dynamicTlasRevision;
        long dynamicSceneRevision;
        int reasonCode;
    }

    @Name("top.ceroxe.rt.RtCausalityCaptureLoss")
    @Label("RTRenderer RT Causality Capture Loss")
    @Category({"RTRenderer", "Renderer", "Causality"})
    @StackTrace(false)
    static final class RtCausalityCaptureLossEvent extends Event {
        long maxEvents;
        long attemptedEvents;
        long droppedEventsLowerBound;
        long overflowSampleStride;
    }

    /**
     * Builds the terminal report only after smoke has stopped recording.  No
     * collection is allocated by host, worker, or Vulkan producer paths.
     * The report deliberately distinguishes empty CPU meshes from missing RT
     * work: an air section has no BLAS by design and must never be reported as
     * an invisible-geometry failure.
     */
    private static final class CoverageAccumulator {
        private static final int MAX_MISSING_SAMPLES = 16;
        private static final int CPU_CHAIN = stageBit(HOST_VISIBLE)
                | stageBit(BRIDGE_EXTRACT_BEGIN)
                | stageBit(BRIDGE_EXTRACT_COMPLETE)
                | stageBit(SCENE_PUBLISHED)
                | stageBit(CPU_TICKET)
                | stageBit(CPU_QUEUE)
                | stageBit(CPU_BUILD_BEGIN)
                | stageBit(CPU_BUILD_COMPLETE)
                | stageBit(CPU_MESH_COMMITTED);
        private static final int RT_CHAIN = CPU_CHAIN
                | stageBit(BLAS_GENERATION)
                | stageBit(BLAS_ACTIVE)
                | stageBit(SECTION_TLAS_BOUND);

        private final Map<SectionCoordinate, SectionLifecycle> sections = new HashMap<>();
        private final long[] stageEvents;

        private CoverageAccumulator(long[] stageEvents) {
            this.stageEvents = stageEvents;
        }

        private static String stagesFragment(int stageBits) {
            StringBuilder result = new StringBuilder();
            for (int stage = HOST_VISIBLE; stage <= HOST_TAKEOVER_REJECTED; stage++) {
                if ((stageBits & stageBit(stage)) == 0) {
                    continue;
                }
                if (!result.isEmpty()) {
                    result.append('+');
                }
                result.append(stageName(stage));
            }
            return result.toString();
        }

        private static int stageBit(int stage) {
            return 1 << (stage - 1);
        }

        private void observe(int stage, int x, int y, int z, long value) {
            if (x == Integer.MIN_VALUE || x == Integer.MAX_VALUE) {
                return;
            }
            SectionCoordinate key = new SectionCoordinate(x, y, z);
            SectionLifecycle lifecycle = sections.computeIfAbsent(key, ignored -> new SectionLifecycle());
            if (stage == HOST_VISIBLE) {
                lifecycle.visible = true;
                lifecycle.stages = stageBit(HOST_VISIBLE);
                lifecycle.emptyCpuMesh = false;
                return;
            }
            if (stage == HOST_HIDDEN) {
                lifecycle.visible = false;
                lifecycle.stages |= stageBit(HOST_HIDDEN);
                return;
            }
            lifecycle.stages |= stageBit(stage);
            if (stage == CPU_BUILD_COMPLETE && value == 0L) {
                lifecycle.emptyCpuMesh = true;
            }
        }

        private String recentCoverageFragment() {
            int activeVisible = 0;
            int completeCpuChains = 0;
            int completeRtChains = 0;
            int emptyCpuMeshes = 0;
            int incompleteCpuChains = 0;
            int incompleteRtChains = 0;
            for (SectionLifecycle lifecycle : sections.values()) {
                if (!lifecycle.visible) {
                    continue;
                }
                activeVisible++;
                boolean cpuComplete = (lifecycle.stages & CPU_CHAIN) == CPU_CHAIN;
                if (cpuComplete) {
                    completeCpuChains++;
                } else {
                    incompleteCpuChains++;
                }
                if (lifecycle.emptyCpuMesh) {
                    emptyCpuMeshes++;
                    continue;
                }
                if ((lifecycle.stages & RT_CHAIN) == RT_CHAIN) {
                    completeRtChains++;
                } else {
                    incompleteRtChains++;
                }
            }
            return "sectionCoverage{tracked=" + sections.size()
                    + ", activeVisible=" + activeVisible
                    + ", completeCpuChains=" + completeCpuChains
                    + ", completeRtChains=" + completeRtChains
                    + ", emptyCpuMeshes=" + emptyCpuMeshes
                    + ", incompleteCpuChains=" + incompleteCpuChains
                    + ", incompleteRtChains=" + incompleteRtChains
                    + '}';
        }

        private String missingSamples() {
            List<String> samples = new ArrayList<>(MAX_MISSING_SAMPLES);
            for (Map.Entry<SectionCoordinate, SectionLifecycle> entry : sections.entrySet()) {
                if (samples.size() >= MAX_MISSING_SAMPLES) {
                    break;
                }
                SectionLifecycle lifecycle = entry.getValue();
                if (!lifecycle.visible || lifecycle.emptyCpuMesh) {
                    continue;
                }
                int missing = RT_CHAIN & ~lifecycle.stages;
                if (missing != 0) {
                    samples.add(entry.getKey() + ":" + stagesFragment(missing));
                }
            }
            return samples.toString();
        }

        private String stageEventsFragment() {
            StringBuilder result = new StringBuilder();
            for (int stage = HOST_VISIBLE; stage < stageEvents.length; stage++) {
                if (stageEvents[stage] == 0L) {
                    continue;
                }
                if (!result.isEmpty()) {
                    result.append('|');
                }
                result.append(stageName(stage)).append('=').append(stageEvents[stage]);
            }
            return result.toString();
        }
    }

    private record SectionCoordinate(int x, int y, int z) {
        @Override
        public String toString() {
            return x + "," + y + "," + z;
        }
    }

    private static final class SectionLifecycle {
        private boolean visible;
        private boolean emptyCpuMesh;
        private int stages;
    }

    private static final class Entry {
        /* Negative values are producer-owned states; zero is an unused slot. */
        private volatile long state;
        private long elapsedNanos;
        private long threadId;
        private int stage;
        private int sectionX;
        private int sectionY;
        private int sectionZ;
        private long revision;
        private long generationOrFrame;
        private long value;
        private int flags;

        private Entry() {
        }

        private boolean observeIfStable(long expectedSequence, CoverageAccumulator coverage) {
            if (state != expectedSequence) {
                return false;
            }
            int capturedStage = stage;
            int capturedSectionX = sectionX;
            int capturedSectionY = sectionY;
            int capturedSectionZ = sectionZ;
            long capturedValue = value;
            if (state != expectedSequence) {
                return false;
            }
            coverage.observe(capturedStage, capturedSectionX, capturedSectionY, capturedSectionZ, capturedValue);
            return true;
        }

        private boolean appendIfStable(long expectedSequence, long sessionStartNanos, StringBuilder result, boolean prependSeparator) {
            if (state != expectedSequence) {
                return false;
            }
            long capturedNanos = elapsedNanos;
            long capturedThreadId = threadId;
            int capturedStage = stage;
            int capturedSectionX = sectionX;
            int capturedSectionY = sectionY;
            int capturedSectionZ = sectionZ;
            long capturedRevision = revision;
            long capturedGenerationOrFrame = generationOrFrame;
            long capturedValue = value;
            int capturedFlags = flags;
            if (state != expectedSequence) {
                return false;
            }
            if (prependSeparator) {
                result.append(';');
            }
            result.append('#').append(expectedSequence)
                    .append("@+").append(Math.max(0L, capturedNanos - sessionStartNanos) / 1_000L)
                    .append("us[thread=").append(capturedThreadId)
                    .append(",stage=").append(stageName(capturedStage));
            if (capturedSectionX == Integer.MIN_VALUE) {
                result.append(",worldRevision=").append(capturedRevision)
                        .append(",viewRevision=").append(capturedGenerationOrFrame)
                        .append(",sections=").append(capturedValue);
            } else if (capturedSectionX == Integer.MAX_VALUE) {
                result.append(",frame=").append(capturedGenerationOrFrame)
                        .append(",relatedRevision=").append(capturedRevision)
                        .append(",value=").append(capturedValue);
            } else {
                result.append(",section=").append(capturedSectionX).append(',').append(capturedSectionY).append(',').append(capturedSectionZ)
                        .append(",contentRevision=").append(capturedRevision)
                        .append(",generation=").append(capturedGenerationOrFrame)
                        .append(",value=").append(capturedValue);
            }
            result.append(",flags=0x").append(Integer.toHexString(capturedFlags)).append(']');
            return true;
        }
    }

    private static final class TraceSlot {
        private final int[] stages = new int[TRACE_EVENT_CAPACITY];
        private final long[] publicationGenerations = new long[TRACE_EVENT_CAPACITY];
        private long traceId;
        private long eventCount;
        private int lastStage = -1;
        private long lastPublicationGeneration = -1L;

        private void reset(long traceId, int stage, long publicationGeneration) {
            this.traceId = traceId;
            this.eventCount = 0L;
            this.lastStage = -1;
            this.lastPublicationGeneration = -1L;
            append(stage, publicationGeneration);
        }

        private void append(int stage, long publicationGeneration) {
            long ordinal = ++eventCount;
            int slot = (int) ((ordinal - 1L) % TRACE_EVENT_CAPACITY);
            stages[slot] = stage;
            publicationGenerations[slot] = publicationGeneration;
            lastStage = stage;
            lastPublicationGeneration = Math.max(lastPublicationGeneration, publicationGeneration);
        }

        private RendererCausalityTrace snapshot() {
            long retainedCount = Math.min(eventCount, (long) TRACE_EVENT_CAPACITY);
            long firstOrdinal = eventCount - retainedCount + 1L;
            List<RendererCausalityTrace.StageEvidence> retained = new ArrayList<>((int) retainedCount);
            for (long ordinal = firstOrdinal; ordinal <= eventCount; ordinal++) {
                int slot = (int) ((ordinal - 1L) % TRACE_EVENT_CAPACITY);
                int stage = stages[slot];
                retained.add(new RendererCausalityTrace.StageEvidence(
                        ordinal,
                        stage,
                        stageName(stage),
                        publicationGenerations[slot]
                ));
            }
            return new RendererCausalityTrace(
                    traceId, eventCount, lastStage, lastPublicationGeneration,
                    retained, eventCount - retainedCount, true, "boundedTraceIndex"
            );
        }
    }
}
