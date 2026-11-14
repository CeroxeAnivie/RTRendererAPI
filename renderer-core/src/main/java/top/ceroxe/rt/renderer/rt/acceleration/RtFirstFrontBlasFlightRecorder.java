package top.ceroxe.rt.renderer.rt.acceleration;

import top.ceroxe.rt.renderer.scene.SectionKey;

import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Owns the fixed-memory trace of section-BLAS convergence for the first authoritative front.
 *
 * <p>The renderer scheduler only publishes lifecycle edges into this owner. Ring retention,
 * foreground filtering, overwrite accounting, and the one-shot terminal snapshot remain one
 * cohesive state machine, so diagnostics cannot accidentally mutate BLAS scheduling state.</p>
 */
final class RtFirstFrontBlasFlightRecorder {
    static final int DEFAULT_CAPACITY = 4096;

    private final boolean enabled;
    private final LongSupplier elapsedMillis;
    private final Entry[] entries;
    private long nextSequence;
    private int nextSlot;
    private boolean dumped;

    RtFirstFrontBlasFlightRecorder(LongSupplier elapsedMillis) {
        this(
                Boolean.getBoolean("top.ceroxe.rt.takeoverFlightRecorder.enabled"),
                DEFAULT_CAPACITY,
                elapsedMillis
        );
    }

    RtFirstFrontBlasFlightRecorder(boolean enabled, int capacity, LongSupplier elapsedMillis) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("first-front BLAS flight recorder capacity must be positive");
        }
        this.enabled = enabled;
        this.elapsedMillis = Objects.requireNonNull(elapsedMillis, "elapsedMillis");
        this.entries = enabled ? entries(capacity) : new Entry[0];
    }

    private static Entry[] entries(int capacity) {
        Entry[] result = new Entry[capacity];
        for (int index = 0; index < result.length; index++) {
            result[index] = new Entry();
        }
        return result;
    }

    void reset() {
        if (!enabled) {
            return;
        }
        nextSequence = 0L;
        nextSlot = 0;
        dumped = false;
        for (Entry entry : entries) {
            entry.clear();
        }
    }

    void record(
            String edge,
            SectionKey key,
            long buildSequence,
            long desiredRevision,
            long activeRevision,
            int sourceFlags,
            boolean activeExists,
            boolean baseMatches,
            boolean invalidated
    ) {
        if (!enabled || dumped) {
            return;
        }
        nextEntry().setSection(
                nextSequence,
                elapsedMillis.getAsLong(),
                edge,
                key,
                buildSequence,
                desiredRevision,
                activeRevision,
                sourceFlags,
                activeExists,
                baseMatches,
                invalidated
        );
    }

    void recordProgress(
            long viewRevision,
            int required,
            int source,
            int queued,
            int recording,
            int gpu,
            int active,
            int bound
    ) {
        if (!enabled || dumped) {
            return;
        }
        nextEntry().setProgress(
                nextSequence,
                elapsedMillis.getAsLong(),
                viewRevision,
                required,
                source,
                queued,
                recording,
                gpu,
                active,
                bound
        );
    }

    /**
     * Returns the immutable terminal trace exactly once; disabled recorders never publish.
     */
    Dump dumpOnce(Set<SectionKey> foregroundKeys) {
        if (!enabled || dumped) {
            return null;
        }
        Objects.requireNonNull(foregroundKeys, "foregroundKeys");
        dumped = true;
        long firstSequence = Math.max(1L, nextSequence - entries.length + 1L);
        StringBuilder trace = new StringBuilder(16_384);
        int retained = 0;
        for (long sequence = firstSequence; sequence <= nextSequence; sequence++) {
            Entry entry = entries[(int) ((sequence - 1L) % entries.length)];
            if (entry.sequence != sequence
                    || (entry.key != null && !foregroundKeys.contains(entry.key))) {
                continue;
            }
            if (retained++ > 0) {
                trace.append(';');
            }
            entry.appendTo(trace);
        }
        return new Dump(
                nextSequence,
                retained,
                Math.max(0L, nextSequence - entries.length),
                trace.toString()
        );
    }

    private Entry nextEntry() {
        nextSequence++;
        Entry entry = entries[nextSlot];
        nextSlot = (nextSlot + 1) % entries.length;
        return entry;
    }

    record Dump(long events, int retained, long overwritten, String trace) {
        Dump {
            trace = Objects.requireNonNull(trace, "trace");
            if (events < 0L || retained < 0 || overwritten < 0L || retained > events) {
                throw new IllegalArgumentException("invalid first-front BLAS flight recorder dump counts");
            }
        }
    }

    private static final class Entry {
        private long sequence;
        private long elapsedMillis;
        private String edge;
        private SectionKey key;
        private long buildSequence;
        private long desiredRevision;
        private long activeRevision;
        private int sourceFlags;
        private boolean activeExists;
        private boolean baseMatches;
        private boolean invalidated;
        private long viewRevision;
        private int required;
        private int source;
        private int queued;
        private int recording;
        private int gpu;
        private int active;
        private int bound;

        private void setSection(
                long sequence,
                long elapsedMillis,
                String edge,
                SectionKey key,
                long buildSequence,
                long desiredRevision,
                long activeRevision,
                int sourceFlags,
                boolean activeExists,
                boolean baseMatches,
                boolean invalidated
        ) {
            clear();
            this.sequence = sequence;
            this.elapsedMillis = elapsedMillis;
            this.edge = Objects.requireNonNull(edge, "edge");
            this.key = Objects.requireNonNull(key, "key");
            this.buildSequence = buildSequence;
            this.desiredRevision = desiredRevision;
            this.activeRevision = activeRevision;
            this.sourceFlags = sourceFlags;
            this.activeExists = activeExists;
            this.baseMatches = baseMatches;
            this.invalidated = invalidated;
        }

        private void setProgress(
                long sequence,
                long elapsedMillis,
                long viewRevision,
                int required,
                int source,
                int queued,
                int recording,
                int gpu,
                int active,
                int bound
        ) {
            clear();
            this.sequence = sequence;
            this.elapsedMillis = elapsedMillis;
            this.edge = "progress";
            this.viewRevision = viewRevision;
            this.required = required;
            this.source = source;
            this.queued = queued;
            this.recording = recording;
            this.gpu = gpu;
            this.active = active;
            this.bound = bound;
        }

        private void appendTo(StringBuilder output) {
            output.append('{').append(sequence).append('@').append(elapsedMillis).append("ms:").append(edge);
            if (key == null) {
                output.append(",view=").append(viewRevision)
                        .append(",required=").append(required)
                        .append(",source=").append(source)
                        .append(",queued=").append(queued)
                        .append(",recording=").append(recording)
                        .append(",gpu=").append(gpu)
                        .append(",active=").append(active)
                        .append(",bound=").append(bound);
            } else {
                output.append(",key=").append(key)
                        .append(",build=").append(buildSequence)
                        .append(",desired=").append(desiredRevision)
                        .append(",activeRevision=").append(activeRevision)
                        .append(",flags=0x").append(Integer.toHexString(sourceFlags))
                        .append(",activeExists=").append(activeExists)
                        .append(",baseMatches=").append(baseMatches)
                        .append(",invalidated=").append(invalidated);
            }
            output.append('}');
        }

        private void clear() {
            sequence = 0L;
            elapsedMillis = 0L;
            edge = null;
            key = null;
            buildSequence = -1L;
            desiredRevision = -1L;
            activeRevision = -1L;
            sourceFlags = 0;
            activeExists = false;
            baseMatches = false;
            invalidated = false;
            viewRevision = -1L;
            required = 0;
            source = 0;
            queued = 0;
            recording = 0;
            gpu = 0;
            active = 0;
            bound = 0;
        }
    }
}
