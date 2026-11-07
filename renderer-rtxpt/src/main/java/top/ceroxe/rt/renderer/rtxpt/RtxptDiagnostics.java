package top.ceroxe.rt.renderer.rtxpt;

import java.util.ArrayList;
import java.util.List;

/** Measurements of this adapter's own calls. RTXPT internals are not expanded. */
public final class RtxptDiagnostics {
    private RtxptDiagnostics() { }
    public enum Level { OFF, SUMMARY, DEEP }
    public enum Stage { VALIDATION, TRANSLATION, RESOURCES, LIGHTING, ATTRIBUTES, RTXPT_FRAME }
    /** RTXPT_FRAME measures wall time including native waits, not GPU busy time. */
    public record Event(long ordinal, long sequence, long sceneRevision, Stage stage,
            long beginNanos, long durationNanos, long items, long bytes, boolean failed) { }
    /** nextOrdinal is the next event identity; use nextOrdinal - 1 for the next poll. */
    public record Snapshot(Level level, long nextOrdinal, long lostEvents,
            List<Event> events, boolean upstreamInternalsExpanded) {
        public Snapshot { events = List.copyOf(events); }
    }

    /** Owner-thread bounded primitive storage; allocation occurs only at explicit snapshot. */
    static final class Recorder {
        private static final int CAPACITY = 1024, STRIDE = 9;
        private final long[] data = new long[CAPACITY * STRIDE];
        private Level level = Level.SUMMARY;
        private long next;
        void level(Level value) { level = java.util.Objects.requireNonNull(value); }
        Level level() { return level; }
        long clock() { return level == Level.OFF ? 0 : System.nanoTime(); }
        void add(long sequence, long revision, Stage stage, long begin, long items, long bytes, boolean failed) {
            if (begin == 0 || level == Level.OFF) return;
            int offset = (int)(next % CAPACITY) * STRIDE;
            data[offset] = next;
            data[offset + 1] = sequence;
            data[offset + 2] = revision;
            data[offset + 3] = stage.ordinal();
            data[offset + 4] = begin;
            data[offset + 5] = Math.max(0, System.nanoTime() - begin);
            data[offset + 6] = items;
            data[offset + 7] = bytes;
            data[offset + 8] = failed ? 1 : 0;
            next++;
        }
        Snapshot snapshot(long afterOrdinal) {
            if (afterOrdinal < -1 || afterOrdinal >= next && afterOrdinal != next - 1)
                throw new IllegalArgumentException("Invalid diagnostic cursor");
            long wanted = afterOrdinal + 1, oldest = Math.max(0, next - CAPACITY);
            long first = Math.max(wanted, oldest);
            var events = new ArrayList<Event>((int)(next - first));
            for (long ordinal = first; ordinal < next; ordinal++) {
                int offset = (int)(ordinal % CAPACITY) * STRIDE;
                events.add(new Event(data[offset], data[offset + 1], data[offset + 2],
                        Stage.values()[(int)data[offset + 3]], data[offset + 4], data[offset + 5],
                        data[offset + 6], data[offset + 7], data[offset + 8] != 0));
            }
            return new Snapshot(level, next, Math.max(0, oldest - wanted), events, false);
        }
    }
}
