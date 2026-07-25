package top.ceroxe.rt.renderer.orchestration.takeover;

import top.ceroxe.rt.renderer.RendererLog;
import top.ceroxe.rt.renderer.diagnostics.RtTakeoverTimeline;
import top.ceroxe.rt.renderer.scene.ChunkKey;
import top.ceroxe.rt.renderer.scene.ChunkSnapshot;
import top.ceroxe.rt.renderer.scene.SectionKey;

import java.util.*;

/**
 * Bounded, smoke-only causality recorder for the gap between host visibility and a CPU build ticket.
 *
 * <p>The hot path performs no logging. Events are retained in a fixed ring and emitted once, after the
 * authoritative first front has reached the committed CPU generation. This provides per-section evidence
 * without turning extraction or render scheduling into an I/O benchmark.</p>
 */
public final class RtForegroundAdmissionFlightRecorder {
    private static final int DEFAULT_CAPACITY = 8_192;
    /*
     * Preserve the admission origin and terminal state while bounding the
     * diagnostic itself. A tail alone hides the first causal transition.
     */
    private static final int DUMP_HEAD_EVENT_LIMIT = 16;
    private static final int DUMP_TAIL_EVENT_LIMIT = 48;
    private static final Recorder RECORDER = new Recorder(
            Boolean.getBoolean("top.ceroxe.rt.takeoverFlightRecorder.enabled"),
            DEFAULT_CAPACITY
    );

    private RtForegroundAdmissionFlightRecorder() {
    }

    /**
     * Clears all retained smoke evidence before a new takeover run.
     */
    public static void reset() {
        RECORDER.reset();
    }

    /**
     * Replaces the authoritative visible section set tracked by this bounded recorder.
     *
     * @param sectionKeys authoritative visible section set to track
     */
    public static void observeVisibleAuthority(Set<SectionKey> sectionKeys) {
        RECORDER.observeVisibleAuthority(sectionKeys);
    }

    /**
     * Records one tracked section admission edge.
     *
     * @param key     tracked section identity
     * @param stage   stable admission stage
     * @param details bounded diagnostic details
     */
    public static void recordSection(SectionKey key, String stage, String details) {
        RECORDER.recordSection(key, stage, details);
    }

    /**
     * Lets hot producers avoid formatting text after this fixed diagnostic window is full.
     *
     * @param key candidate tracked section
     * @return whether another section event can be retained
     */
    public static boolean acceptsSection(SectionKey key) {
        return RECORDER.acceptsSection(key);
    }

    /**
     * Records one chunk edge for every tracked section it owns.
     *
     * @param key     chunk whose tracked sections receive the edge
     * @param stage   stable admission stage
     * @param details bounded diagnostic details
     */
    public static void recordChunk(ChunkKey key, String stage, String details) {
        RECORDER.recordChunk(key, stage, details);
    }

    /**
     * Records extraction of a chunk snapshot for its tracked sections.
     *
     * @param snapshot extracted chunk snapshot
     * @param stage    stable admission stage
     * @param details  bounded diagnostic details
     */
    public static void recordChunkSnapshot(ChunkSnapshot snapshot, String stage, String details) {
        RECORDER.recordChunkSnapshot(snapshot, stage, details);
    }

    /**
     * Emits the bounded recorder contents at most once.
     *
     * @param foregroundKeys keys used to filter the one-shot bounded dump
     */
    public static void dumpOnce(Set<SectionKey> foregroundKeys) {
        RECORDER.dumpOnce(foregroundKeys);
    }

    static final class Recorder {
        private final boolean enabled;
        private final Event[] ring;
        private final Set<SectionKey> trackedSections = new HashSet<>();
        private final Map<ChunkKey, Set<SectionKey>> trackedSectionsByChunk = new HashMap<>();
        private final Set<SectionKey> visibleRecorded = new HashSet<>();
        private long nextSequence = 1L;
        private boolean dumped;

        Recorder(boolean enabled, int capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("capacity must be positive");
            }
            this.enabled = enabled;
            this.ring = new Event[capacity];
        }

        synchronized void reset() {
            java.util.Arrays.fill(ring, null);
            trackedSections.clear();
            trackedSectionsByChunk.clear();
            visibleRecorded.clear();
            nextSequence = 1L;
            dumped = false;
        }

        synchronized void observeVisibleAuthority(Set<SectionKey> sectionKeys) {
            if (!enabled || dumped || sectionKeys == null || sectionKeys.isEmpty()) {
                return;
            }
            for (SectionKey key : sectionKeys) {
                if (trackedSections.add(key)) {
                    trackedSectionsByChunk.computeIfAbsent(key.chunkKey(), ignored -> new HashSet<>()).add(key);
                }
                if (visibleRecorded.add(key)) {
                    append(key, "visibleAuthority", "visibleCount=" + sectionKeys.size());
                }
            }
        }

        synchronized void recordSection(SectionKey key, String stage, String details) {
            if (!acceptsSection(key)) {
                return;
            }
            append(key, stage, details);
        }

        synchronized boolean acceptsSection(SectionKey key) {
            return enabled
                    && !dumped
                    && key != null
                    && nextSequence <= ring.length
                    && trackedSections.contains(key);
        }

        synchronized void recordChunk(ChunkKey key, String stage, String details) {
            if (!enabled || dumped || key == null) {
                return;
            }
            for (SectionKey sectionKey : trackedSectionsByChunk.getOrDefault(key, Set.of())) {
                append(sectionKey, stage, details);
            }
        }

        synchronized void recordChunkSnapshot(ChunkSnapshot snapshot, String stage, String details) {
            if (!enabled || dumped || snapshot == null) {
                return;
            }
            Set<SectionKey> extracted = new HashSet<>();
            snapshot.sections().forEach(section -> extracted.add(section.key()));
            for (SectionKey key : trackedSectionsByChunk.getOrDefault(snapshot.chunkKey(), Set.of())) {
                append(
                        key,
                        extracted.contains(key) ? stage : stage + "Omitted",
                        details + ", extractedSections=" + snapshot.sectionCount()
                );
            }
        }

        synchronized void dumpOnce(Set<SectionKey> foregroundKeys) {
            if (!enabled || dumped || foregroundKeys == null || foregroundKeys.isEmpty()) {
                return;
            }
            dumped = true;
            List<Event> retained = snapshotEvents();
            long totalEvents = nextSequence - 1L;
            long overwritten = Math.max(0L, totalEvents - ring.length);
            Map<String, Integer> foregroundStageCounts = new TreeMap<>();
            List<Event> head = new ArrayList<>(DUMP_HEAD_EVENT_LIMIT);
            List<Event> tail = new ArrayList<>(DUMP_TAIL_EVENT_LIMIT);
            int foregroundEventCount = 0;
            for (Event event : retained) {
                if (!foregroundKeys.contains(event.key())) {
                    continue;
                }
                foregroundEventCount++;
                foregroundStageCounts.merge(event.stage(), 1, Integer::sum);
                if (head.size() < DUMP_HEAD_EVENT_LIMIT) {
                    head.add(event);
                }
                if (tail.size() == DUMP_TAIL_EVENT_LIMIT) {
                    tail.removeFirst();
                }
                tail.add(event);
            }
            StringJoiner headTrace = new StringJoiner(" | ");
            for (Event event : head) {
                headTrace.add(event.asLogFragment());
            }
            int headTailOverlap = Math.max(0, head.size() + tail.size() - foregroundEventCount);
            List<Event> distinctTail = tail.subList(headTailOverlap, tail.size());
            StringJoiner tailTrace = new StringJoiner(" | ");
            for (Event event : distinctTail) {
                tailTrace.add(event.asLogFragment());
            }
            int emittedEvents = head.size() + distinctTail.size();
            RendererLog.warn(
                    "rt first-front admission flight recorder: foreground={}, events={}, retained={}, overwritten={}, foregroundEvents={}, stageCounts={}, headEvents={}, tailEvents={}, omitted={}, head={}, tail={}",
                    foregroundKeys.size(), totalEvents, retained.size(), overwritten,
                    foregroundEventCount, foregroundStageCounts, head.size(), distinctTail.size(),
                    foregroundEventCount - emittedEvents, headTrace, tailTrace
            );
        }

        synchronized List<Event> snapshotEvents() {
            List<Event> events = new ArrayList<>(ring.length);
            for (Event event : ring) {
                if (event != null) {
                    events.add(event);
                }
            }
            events.sort(Comparator.comparingLong(Event::sequence));
            return List.copyOf(events);
        }

        private void append(SectionKey key, String stage, String details) {
            Objects.requireNonNull(key, "key");
            String checkedStage = Objects.requireNonNull(stage, "stage");
            long sequence = nextSequence++;
            int slot = (int) ((sequence - 1L) % ring.length);
            ring[slot] = new Event(
                    sequence,
                    RtTakeoverTimeline.elapsedMillis(),
                    key,
                    checkedStage,
                    details == null || details.isBlank() ? "none" : details
            );
        }
    }

    record Event(long sequence, long elapsedMillis, SectionKey key, String stage, String details) {
        String asLogFragment() {
            return "#" + sequence + "@" + elapsedMillis + "ms"
                    + "[key=" + key + ", stage=" + stage + ", " + details + ']';
        }
    }
}
