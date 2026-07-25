package top.ceroxe.rt.renderer.diagnostics;

import top.ceroxe.rt.renderer.scene.SectionKey;

import java.util.*;
import java.util.function.Predicate;

/**
 * Fixed-memory, cross-layer first-front causality recorder used only by smoke.
 *
 * <p>The existing CPU, BLAS and presentation traces are intentionally local to
 * their owners. This recorder adds the shared correlation keys needed to prove
 * where a first-front generation waits as it crosses the host bridge,
 * CPU workers, Vulkan AS scheduler, frame pipeline and external graphics API presentation.
 * It never writes from a hot path; the complete trace is emitted once after the
 * first successfully presented RT frame.</p>
 */
public final class RtFirstFrontCausalityRecorder {
    /*
     * Exact stage totals live in RtSceneCausalityRecorder. This recorder keeps
     * only the earliest cross-layer text evidence needed to diagnose first
     * presentation, so it must never scale with view distance or refresh rate.
     */
    private static final int CAPACITY = 64;
    private static final boolean ENABLED = Boolean.getBoolean("top.ceroxe.rt.takeoverFlightRecorder.enabled");
    private static final Entry[] ENTRIES = ENABLED ? entries() : new Entry[0];

    private static long nextSequence;
    private static int nextSlot;
    private static long omittedTextEvents;
    private static boolean dumped;

    private RtFirstFrontCausalityRecorder() {
    }

    /**
     * Clears the fixed evidence buffer before a new smoke run.
     */
    public static void reset() {
        if (!ENABLED) {
            return;
        }
        synchronized (ENTRIES) {
            nextSequence = 0L;
            nextSlot = 0;
            omittedTextEvents = 0L;
            dumped = false;
            for (Entry entry : ENTRIES) {
                entry.clear();
            }
        }
    }

    /**
     * Records a revision-bearing section edge under all joinable subjects.
     *
     * @param stage           stable cross-layer stage
     * @param key             section identity
     * @param contentRevision renderer-owned content revision
     * @param details         bounded diagnostic details
     */
    public static void recordSection(String stage, SectionKey key, long contentRevision, String details) {
        Objects.requireNonNull(key, "key");
        recordPrimitiveSection(stage, key, contentRevision);
        recordText(stage, key, contentRevision, details, Subject.SECTION);
        recordPrimitiveSection(stage, key, -1L);
        recordText(stage, key, contentRevision, details, Subject.SECTION_KEY_WITH_REVISION);
    }

    /**
     * Records a bridge-side section edge before a renderer content revision exists.
     *
     * <p>Every revision-bearing section event also writes this key-only subject. That is
     * what makes the host frustum publication and extraction path causally joinable
     * to the later renderer-owned revision without treating either timestamp as a proxy
     * for the other.</p>
     *
     * @param stage   stable bridge stage
     * @param key     section identity
     * @param details bounded diagnostic details
     */
    public static void recordSectionKey(String stage, SectionKey key, String details) {
        Objects.requireNonNull(key, "key");
        recordPrimitiveSection(stage, key, -1L);
        recordText(stage, key, 0L, details, Subject.SECTION_KEY);
    }

    /**
     * Lets hot producers skip text construction after the bounded evidence window closes.
     *
     * @return whether another text event can be retained
     */
    public static boolean acceptsTextEvent() {
        if (!ENABLED) {
            return false;
        }
        synchronized (ENTRIES) {
            return !dumped && nextSequence < CAPACITY;
        }
    }

    /**
     * Records a world-generation edge.
     *
     * @param stage         stable world stage
     * @param worldRevision world generation
     * @param details       diagnostic details
     */
    public static void recordWorld(String stage, long worldRevision, String details) {
        if (stageCode(stage) == RtSceneCausalityRecorder.WORLD_TLAS_BOUND) {
            RtSceneCausalityRecorder.recordWorld(worldRevision, 0L, -1L, 0);
        }
        recordText(stage, null, worldRevision, details, Subject.WORLD);
    }

    /**
     * Records a frame edge.
     *
     * @param stage         stable frame stage
     * @param frameSequence frame sequence
     * @param details       diagnostic details
     */
    public static void recordFrame(String stage, long frameSequence, String details) {
        int stageCode = stageCode(stage);
        if (stageCode != 0) {
            RtSceneCausalityRecorder.recordFrame(stageCode, frameSequence, -1L, 0L, 0);
        }
        recordText(stage, null, frameSequence, details, Subject.FRAME);
    }

    /**
     * Records a renderer lifecycle edge.
     *
     * @param stage   stable lifecycle stage
     * @param details bounded diagnostic details
     */
    public static void recordLifecycle(String stage, String details) {
        recordText(stage, null, 0L, details, Subject.LIFECYCLE);
    }

    /**
     * Records one complete host visible-authority publication without
     * constructing per-section diagnostic strings after the fixed text window
     * is full.
     *
     * <p>The exact entered/retired stage totals are owned by
     * {@link RtSceneCausalityRecorder}. This recorder retains only the earliest
     * human-readable cross-layer evidence, so one batch lock and one omitted
     * count preserve observability without making diagnostics scale with view
     * distance.</p>
     *
     * @param rasterSectionCount host raster section count
     * @param visibleSections    authoritative ray-coverage set
     * @param drawableSections   subset considered raster-drawable
     */
    public static void recordVisibleAuthority(
            int rasterSectionCount,
            Collection<SectionKey> visibleSections,
            Set<SectionKey> drawableSections
    ) {
        Objects.requireNonNull(drawableSections, "drawableSections");
        recordVisibleAuthority(
                rasterSectionCount,
                visibleSections,
                drawableSections.size(),
                drawableSections::contains
        );
    }

    /**
     * Records visible authority with an allocation-free membership predicate.
     *
     * @param rasterSectionCount   host raster section count
     * @param visibleSections      authoritative ray-coverage set
     * @param drawableSectionCount drawable member count
     * @param drawableMembership   drawable membership predicate
     */
    public static void recordVisibleAuthority(
            int rasterSectionCount,
            Collection<SectionKey> visibleSections,
            int drawableSectionCount,
            Predicate<SectionKey> drawableMembership
    ) {
        Objects.requireNonNull(visibleSections, "visibleSections");
        Objects.requireNonNull(drawableMembership, "drawableMembership");
        if (rasterSectionCount < 0 || drawableSectionCount < 0) {
            throw new IllegalArgumentException("visible authority counts must not be negative");
        }
        if (!ENABLED) {
            return;
        }
        synchronized (ENTRIES) {
            if (dumped) {
                return;
            }
            int retainedBefore = (int) nextSequence;
            recordTextLocked(
                    "hostAuthoritativeFrustum",
                    "lifecycle",
                    "rasterSections=" + rasterSectionCount
                            + ", rtRayCoverageSections=" + visibleSections.size()
                            + ", rasterDrawableSections=" + drawableSectionCount
            );
            int remaining = CAPACITY - (int) nextSequence;
            if (remaining > 0) {
                for (SectionKey key : visibleSections) {
                    if (remaining-- == 0) {
                        break;
                    }
                    SectionKey sectionKey = Objects.requireNonNull(key, "visible section");
                    recordTextLocked(
                            "hostVisibleAuthority",
                            "sectionKey:" + sectionKey,
                            "visibleSections=" + visibleSections.size()
                                    + ", drawable=" + drawableMembership.test(sectionKey)
                    );
                }
            }
            long attempted = 1L + visibleSections.size();
            long retained = nextSequence - retainedBefore;
            omittedTextEvents += attempted - retained;
        }
    }

    /**
     * Emits and closes the bounded trace exactly once.
     *
     * @param trigger                event that made the trace publishable
     * @param presentedFrameSequence first successfully presented frame
     */
    public static void dumpOnce(String trigger, long presentedFrameSequence) {
        if (!ENABLED) {
            return;
        }
        synchronized (ENTRIES) {
            if (dumped) {
                return;
            }
            dumped = true;
            long first = 1L;
            StringBuilder trace = new StringBuilder(32_768);
            Map<String, Long> latestBySubject = new HashMap<>();
            long largestGapMillis = -1L;
            String largestGap = "none";
            int retained = 0;
            for (long sequence = first; sequence <= nextSequence; sequence++) {
                Entry entry = ENTRIES[(int) ((sequence - 1L) % ENTRIES.length)];
                if (entry.sequence != sequence) {
                    continue;
                }
                Long previous = latestBySubject.put(entry.subject, entry.elapsedMillis);
                if (previous != null && entry.elapsedMillis >= previous) {
                    long gap = entry.elapsedMillis - previous;
                    if (gap > largestGapMillis) {
                        largestGapMillis = gap;
                        largestGap = entry.subject + ':' + gap + "ms->" + entry.stage;
                    }
                }
                if (retained++ > 0) {
                    trace.append(';');
                }
                entry.appendTo(trace);
            }
            /* This is an explicitly requested flight-recorder dump, not an error. */
            top.ceroxe.rt.renderer.RendererLog.info(
                    "rt first-front cross-layer recorder: trigger={}, presentedFrame={}, events={}, retained={}, omitted={}, largestCorrelatedGap={}, trace={}",
                    trigger,
                    presentedFrameSequence,
                    nextSequence,
                    retained,
                    omittedTextEvents,
                    largestGap,
                    trace
            );
        }
    }

    /* Package-private so the self-test can lock the one-shot I/O contract
       without needing to capture or parse logger output. */
    static boolean dumpedForTesting() {
        if (!ENABLED) {
            return false;
        }
        synchronized (ENTRIES) {
            return dumped;
        }
    }

    static int recordedTextEventCountForTesting() {
        if (!ENABLED) {
            return 0;
        }
        synchronized (ENTRIES) {
            return (int) nextSequence;
        }
    }

    static long omittedTextEventCountForTesting() {
        if (!ENABLED) {
            return 0L;
        }
        synchronized (ENTRIES) {
            return omittedTextEvents;
        }
    }

    private static int stageCode(String stage) {
        if ("hostLoadingHidden".equals(stage)) return RtSceneCausalityRecorder.HOST_HIDDEN;
        if ("cpuTicket".equals(stage)) return RtSceneCausalityRecorder.CPU_TICKET;
        if ("blasActiveInstalled".equals(stage)) return RtSceneCausalityRecorder.BLAS_ACTIVE;
        if ("worldTlasBound".equals(stage)) return RtSceneCausalityRecorder.WORLD_TLAS_BOUND;
        if ("gpuFrameCompleted".equals(stage)) return RtSceneCausalityRecorder.GPU_COMPLETED;
        if ("sharedFramePresented".equals(stage)) return RtSceneCausalityRecorder.SHARED_PRESENTED;
        return 0;
    }

    private static void recordPrimitiveSection(String stage, SectionKey key, long revision) {
        int stageCode = stageCode(stage);
        if (stageCode != 0) {
            RtSceneCausalityRecorder.recordSection(stageCode, key, revision, -1L, 0L, 0);
        }
    }

    private static void recordText(
            String stage,
            SectionKey key,
            long revision,
            String details,
            Subject subject
    ) {
        if (!ENABLED || stage == null || stage.isBlank()) {
            return;
        }
        synchronized (ENTRIES) {
            if (dumped) {
                return;
            }
            if (nextSequence == CAPACITY) {
                omittedTextEvents++;
                return;
            }
            recordTextLocked(
                    stage,
                    subject.format(key, revision),
                    subject.formatDetails(revision, details)
            );
        }
    }

    private static void recordTextLocked(String stage, String subject, String details) {
        if (nextSequence == CAPACITY) {
            return;
        }
        long sequence = ++nextSequence;
        Entry entry = ENTRIES[nextSlot++];
        entry.set(
                sequence,
                RtTakeoverTimeline.elapsedMillis(),
                stage,
                subject,
                details == null || details.isBlank() ? "none" : details
        );
    }

    private static Entry[] entries() {
        Entry[] entries = new Entry[CAPACITY];
        for (int index = 0; index < entries.length; index++) {
            entries[index] = new Entry();
        }
        return entries;
    }

    private enum Subject {
        SECTION {
            @Override
            String format(SectionKey key, long revision) {
                return "section:" + Objects.requireNonNull(key, "key") + '#' + revision;
            }
        },
        SECTION_KEY {
            @Override
            String format(SectionKey key, long revision) {
                return "sectionKey:" + Objects.requireNonNull(key, "key");
            }
        },
        SECTION_KEY_WITH_REVISION {
            @Override
            String format(SectionKey key, long revision) {
                return "sectionKey:" + Objects.requireNonNull(key, "key");
            }

            @Override
            String formatDetails(long revision, String details) {
                return "revision=" + revision + ", " + details;
            }
        },
        WORLD {
            @Override
            String format(SectionKey key, long revision) {
                return "world:" + revision;
            }
        },
        FRAME {
            @Override
            String format(SectionKey key, long revision) {
                return "frame:" + revision;
            }
        },
        LIFECYCLE {
            @Override
            String format(SectionKey key, long revision) {
                return "lifecycle";
            }
        };

        abstract String format(SectionKey key, long revision);

        String formatDetails(long revision, String details) {
            return details == null || details.isBlank() ? "none" : details;
        }
    }

    private static final class Entry {
        private long sequence;
        private long elapsedMillis;
        private String stage = "";
        private String subject = "";
        private String details = "";

        private void set(long sequence, long elapsedMillis, String stage, String subject, String details) {
            this.sequence = sequence;
            this.elapsedMillis = elapsedMillis;
            this.stage = stage;
            this.subject = subject;
            this.details = details;
        }

        private void clear() {
            sequence = 0L;
            elapsedMillis = 0L;
            stage = "";
            subject = "";
            details = "";
        }

        private void appendTo(StringBuilder result) {
            result.append('#').append(sequence)
                    .append('@').append(elapsedMillis).append("ms")
                    .append("[stage=").append(stage)
                    .append(", subject=").append(subject)
                    .append(", ").append(details).append(']');
        }
    }
}
