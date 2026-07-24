package top.ceroxe.mcvulkanrt.renderer;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionVoxelSnapshot;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Smoke-only structured evidence for one section payload crossing producer,
 * CPU-build and native-admission ownership boundaries.
 *
 * <p>Aggregate counters cannot distinguish a genuine block revision from two
 * producers observing the same host section. Every event therefore keeps
 * the section coordinate, JVM identity of the immutable snapshot, renderer
 * content revision and worker generation as independent correlation axes. The
 * recorder is completely dormant outside causality smoke runs and deliberately
 * omits stack traces and strings from the hot path.</p>
 */
public final class SectionLifecycleFlightRecorder {
    public static final int SOURCE_UNKNOWN = 0;
    public static final int SOURCE_HOST_COMPILE = 1;
    public static final int SOURCE_FOREGROUND_COMPILED = 2;
    public static final int SOURCE_ASYNC_FALLBACK = 3;
    public static final int SOURCE_RENDER_DIRTY = 4;
    public static final int SOURCE_BLOCK_MUTATION = 5;

    public static final int STAGE_SOURCE_CAPTURED = 1;
    public static final int STAGE_SOURCE_PUBLISHED = 2;
    public static final int STAGE_SOURCE_REJECTED = 3;
    public static final int STAGE_INBOX_DRAINED = 4;
    public static final int STAGE_SCENE_PUBLISHED = 5;
    public static final int STAGE_CPU_TICKET = 6;
    public static final int STAGE_CPU_QUEUE_SUBMIT = 7;
    public static final int STAGE_CPU_QUEUE_SUPERSEDE = 8;
    public static final int STAGE_CPU_WORK_START = 9;
    public static final int STAGE_CPU_WORK_COMPLETE = 10;
    public static final int STAGE_CPU_WORK_SUPERSEDED = 11;
    public static final int STAGE_CPU_COMMIT = 12;
    public static final int STAGE_CPU_COMMIT_STALE = 13;
    public static final int STAGE_BLAS_ENQUEUE = 14;
    public static final int STAGE_CPU_INPUT_COALESCED = 15;
    public static final int STAGE_SOURCE_ADMITTED = 16;
    public static final int STAGE_TLAS_BOUND = 17;
    public static final int STAGE_INTERACTIVE_TARGET_CAPTURED = 18;
    public static final int STAGE_INTERACTIVE_ACTIVE_READY = 19;
    public static final int STAGE_INTERACTIVE_PRESENTED = 20;
    public static final int STAGE_SOURCE_RECOVERY_DECISION = 21;
    /**
     * A section material fact has acquired its stable terrain custom index.
     *
     * <p>For this stage, {@code generation} is the material revision,
     * {@code relatedGeneration} is the unsigned face-record hash,
     * {@code queueDepth} is the section face count, and {@code value} is the
     * material slot. Keeping those facts on the section lifecycle event makes
     * a live block mutation joinable with MaterialSlot and ScenePublication
     * JFR events without retaining section keys in the material allocator.</p>
     */
    public static final int STAGE_MATERIAL_SLOT_INSTALLED = 22;
    /** The authoritative ClientLevel mutation has received its bridge generation. */
    public static final int STAGE_INTERACTIVE_MUTATION_OBSERVED = 23;
    /** An authoritative terrain-window publication retired this section from the drawable world. */
    public static final int STAGE_RESIDENCY_RETIRE_REQUESTED = 24;
    /** The CPU mesh owner processed a terrain-retirement request for this section. */
    public static final int STAGE_CPU_MESH_RETIRED = 25;
    /** The native compact-source owner processed a terrain-retirement request for this section. */
    public static final int STAGE_NATIVE_SOURCE_RETIRED = 26;
    /** The native exact-BLAS owner processed a terrain-retirement request for this section. */
    public static final int STAGE_NATIVE_BLAS_RETIRED = 27;
    /** The native section-material owner processed a terrain-retirement request for this section. */
    public static final int STAGE_NATIVE_MATERIAL_RETIRED = 28;
    /** A player mutation section refresh was accepted or rejected before immutable-source work began. */
    public static final int STAGE_INTERACTIVE_REFRESH_DECISION = 29;

    public static final int OUTCOME_ACCEPTED = 1;
    public static final int OUTCOME_REPLACED = 2;
    public static final int OUTCOME_STALE = 3;
    public static final int OUTCOME_FAILED = 4;
    public static final int OUTCOME_RETRY_REQUIRED = 5;

    private static final boolean ENABLED = Boolean.getBoolean("mcvulkanrt.takeoverFlightRecorder.enabled");
    private static final long DEFAULT_MAX_EVENTS = 262_144L;
    private static final long MAX_EVENTS = positiveLongProperty(
            "mcvulkanrt.takeoverFlightRecorder.sectionLifecycleMaxEvents",
            DEFAULT_MAX_EVENTS
    );
    private static final AtomicLong ATTEMPTS = new AtomicLong();
    private static final AtomicLong COMMITTED = new AtomicLong();
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final AtomicLong SESSION = new AtomicLong(1L);
    private static final long OVERFLOW_SAMPLE_MASK = 63L;
    /*
     * JFR snapshots every field synchronously during commit, and Event objects
     * are explicitly reusable. One instance per producer thread preserves the
     * exact event stream without turning diagnostics into a per-transition
     * allocation source. Every mutable field is overwritten below before the
     * event can be committed again.
     */
    private static final ThreadLocal<SectionLifecycleEvent> THREAD_EVENT =
            ThreadLocal.withInitial(SectionLifecycleEvent::new);

    private SectionLifecycleFlightRecorder() {
    }

    /** Starts a new world-scoped evidence window without disabling later long-running samples. */
    public static void reset() {
        SESSION.incrementAndGet();
        ATTEMPTS.set(0L);
        COMMITTED.set(0L);
        DROPPED.set(0L);
    }

    public static void record(
            int stage,
            int source,
            int outcome,
            SectionVoxelSnapshot snapshot,
            long generation,
            long contentRevision,
            long relatedGeneration,
            int sourceFlags,
            int queueDepth,
            long value
    ) {
        if (!ENABLED || snapshot == null) {
            return;
        }
        record(
                stage,
                source,
                outcome,
                snapshot.key(),
                System.identityHashCode(snapshot),
                generation,
                contentRevision,
                relatedGeneration,
                sourceFlags,
                queueDepth,
                value
        );
    }

    public static void record(
            int stage,
            int source,
            int outcome,
            SectionKey key,
            int snapshotIdentity,
            long generation,
            long contentRevision,
            long relatedGeneration,
            int sourceFlags,
            int queueDepth,
            long value
    ) {
        if (!ENABLED || key == null) {
            return;
        }
        long session = SESSION.get();
        long sequence = ATTEMPTS.incrementAndGet();
        if (sequence > MAX_EVENTS && ((sequence - MAX_EVENTS) & OVERFLOW_SAMPLE_MASK) != 0L) {
            DROPPED.incrementAndGet();
            return;
        }
        SectionLifecycleEvent event = THREAD_EVENT.get();
        if (!event.isEnabled()) {
            return;
        }
        event.session = session;
        event.sequence = sequence;
        event.stage = stage;
        event.source = source;
        event.outcome = outcome;
        event.sectionX = key.x();
        event.sectionY = key.y();
        event.sectionZ = key.z();
        event.snapshotIdentity = snapshotIdentity;
        event.generation = generation;
        event.contentRevision = contentRevision;
        event.relatedGeneration = relatedGeneration;
        event.sourceFlags = sourceFlags;
        event.queueDepth = queueDepth;
        event.value = value;
        event.droppedBefore = DROPPED.get();
        event.commit();
        COMMITTED.incrementAndGet();
    }

    public static String summary() {
        return "sectionLifecycleJfr{attempts=" + ATTEMPTS.get()
                + ", committed=" + COMMITTED.get()
                + ", dropped=" + DROPPED.get()
                + ", fullFidelityEvents=" + MAX_EVENTS
                + ", overflowSampleStride=" + (OVERFLOW_SAMPLE_MASK + 1L) + '}';
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

    @Name("top.ceroxe.mcvulkanrt.SectionLifecycle")
    @Label("RT Section Lifecycle")
    @Category({"MCVulkanRT", "Causality"})
    @StackTrace(false)
    static final class SectionLifecycleEvent extends Event {
        long session;
        long sequence;
        int stage;
        int source;
        int outcome;
        int sectionX;
        int sectionY;
        int sectionZ;
        int snapshotIdentity;
        long generation;
        long contentRevision;
        long relatedGeneration;
        int sourceFlags;
        int queueDepth;
        long value;
        long droppedBefore;
    }
}
