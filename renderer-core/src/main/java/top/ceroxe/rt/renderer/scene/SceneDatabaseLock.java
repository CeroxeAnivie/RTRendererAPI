package top.ceroxe.rt.renderer.scene;

import jdk.jfr.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Observable single-lock owner for {@link SceneDatabase}.
 *
 * <p>The logarithmic histograms are cumulative and allocation-free on the hot
 * path. They deliberately answer whether contention exists before any lock
 * sharding is attempted; a split without this evidence would exchange a simple
 * correctness boundary for unproven concurrency complexity.</p>
 */
final class SceneDatabaseLock {
    private static final int HISTOGRAM_BUCKETS = 64;

    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicLongArray acquisitions = new AtomicLongArray(Stage.values().length);
    private final AtomicLongArray waitTotals = new AtomicLongArray(Stage.values().length);
    private final AtomicLongArray waitMaxima = new AtomicLongArray(Stage.values().length);
    private final AtomicLongArray holdTotals = new AtomicLongArray(Stage.values().length);
    private final AtomicLongArray holdMaxima = new AtomicLongArray(Stage.values().length);
    private final AtomicLongArray waitHistogram = new AtomicLongArray(Stage.values().length * HISTOGRAM_BUCKETS);
    private final AtomicLongArray holdHistogram = new AtomicLongArray(Stage.values().length * HISTOGRAM_BUCKETS);
    private final AtomicLongArray lastJfrAcquisitions = new AtomicLongArray(Stage.values().length);

    private static long percentile(AtomicLongArray histogram, int stage, long samples, double percentile) {
        if (samples <= 0L) {
            return 0L;
        }
        long target = Math.max(1L, (long) Math.ceil(samples * percentile));
        long cumulative = 0L;
        int offset = stage * HISTOGRAM_BUCKETS;
        for (int bucket = 0; bucket < HISTOGRAM_BUCKETS; bucket++) {
            cumulative += histogram.get(offset + bucket);
            if (cumulative >= target) {
                return bucketUpperBound(bucket);
            }
        }
        return Long.MAX_VALUE;
    }

    private static int histogramIndex(int stage, long nanos) {
        int bucket = nanos <= 0L ? 0 : 64 - Long.numberOfLeadingZeros(nanos);
        return stage * HISTOGRAM_BUCKETS + Math.min(HISTOGRAM_BUCKETS - 1, bucket);
    }

    private static long bucketUpperBound(int bucket) {
        if (bucket <= 0) {
            return 0L;
        }
        if (bucket >= 63) {
            return Long.MAX_VALUE;
        }
        return 1L << bucket;
    }

    private static void updateMaximum(AtomicLongArray maxima, int index, long candidate) {
        long previous = maxima.get(index);
        while (candidate > previous && !maxima.compareAndSet(index, previous, candidate)) {
            previous = maxima.get(index);
        }
    }

    long acquire(Stage stage) {
        int index = stage.ordinal();
        long waitStart = System.nanoTime();
        lock.lock();
        long acquired = System.nanoTime();
        long waitNanos = Math.max(0L, acquired - waitStart);
        waitTotals.addAndGet(index, waitNanos);
        updateMaximum(waitMaxima, index, waitNanos);
        waitHistogram.incrementAndGet(histogramIndex(index, waitNanos));
        return acquired;
    }

    void release(Stage stage, long acquiredNanos) {
        long holdNanos = Math.max(0L, System.nanoTime() - acquiredNanos);
        lock.unlock();
        int index = stage.ordinal();
        holdTotals.addAndGet(index, holdNanos);
        updateMaximum(holdMaxima, index, holdNanos);
        holdHistogram.incrementAndGet(histogramIndex(index, holdNanos));
        /* Publish completion last so percentile readers never divide by an unfinished sample. */
        acquisitions.incrementAndGet(index);
    }

    Snapshot snapshot() {
        List<StageSnapshot> stages = new ArrayList<>(Stage.values().length);
        for (Stage stage : Stage.values()) {
            int index = stage.ordinal();
            long count = acquisitions.get(index);
            StageSnapshot snapshot = new StageSnapshot(
                    stage,
                    count,
                    waitTotals.get(index),
                    waitMaxima.get(index),
                    percentile(waitHistogram, index, count, 0.95D),
                    percentile(waitHistogram, index, count, 0.99D),
                    holdTotals.get(index),
                    holdMaxima.get(index),
                    percentile(holdHistogram, index, count, 0.95D),
                    percentile(holdHistogram, index, count, 0.99D)
            );
            stages.add(snapshot);
            emitJfrIfAdvanced(snapshot);
        }
        return new Snapshot(stages);
    }

    private void emitJfrIfAdvanced(StageSnapshot snapshot) {
        int index = snapshot.stage().ordinal();
        long previous = lastJfrAcquisitions.getAndSet(index, snapshot.acquisitions());
        if (previous == snapshot.acquisitions()) {
            return;
        }
        LockAggregateEvent event = new LockAggregateEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.stage = snapshot.stage().name();
        event.acquisitions = snapshot.acquisitions();
        event.waitTotalNanos = snapshot.waitTotalNanos();
        event.waitMaxNanos = snapshot.waitMaxNanos();
        event.waitP95Nanos = snapshot.waitP95Nanos();
        event.waitP99Nanos = snapshot.waitP99Nanos();
        event.holdTotalNanos = snapshot.holdTotalNanos();
        event.holdMaxNanos = snapshot.holdMaxNanos();
        event.holdP95Nanos = snapshot.holdP95Nanos();
        event.holdP99Nanos = snapshot.holdP99Nanos();
        event.commit();
    }

    enum Stage {
        QUERY,
        MUTATION,
        SNAPSHOT,
        DRAIN,
        RESET
    }

    record Snapshot(List<StageSnapshot> stages) {
        Snapshot {
            stages = List.copyOf(stages);
        }

        String asLogFragment() {
            StringBuilder result = new StringBuilder("sceneDbLock{");
            for (int index = 0; index < stages.size(); index++) {
                if (index > 0) {
                    result.append(", ");
                }
                result.append(stages.get(index).asLogFragment());
            }
            return result.append('}').toString();
        }
    }

    record StageSnapshot(
            Stage stage,
            long acquisitions,
            long waitTotalNanos,
            long waitMaxNanos,
            long waitP95Nanos,
            long waitP99Nanos,
            long holdTotalNanos,
            long holdMaxNanos,
            long holdP95Nanos,
            long holdP99Nanos
    ) {
        String asLogFragment() {
            return stage.name().toLowerCase()
                    + "{count=" + acquisitions
                    + ", waitP95Us=" + waitP95Nanos / 1_000L
                    + ", waitP99Us=" + waitP99Nanos / 1_000L
                    + ", waitMaxUs=" + waitMaxNanos / 1_000L
                    + ", holdP95Us=" + holdP95Nanos / 1_000L
                    + ", holdP99Us=" + holdP99Nanos / 1_000L
                    + ", holdMaxUs=" + holdMaxNanos / 1_000L
                    + '}';
        }
    }

    @Name("top.ceroxe.rt.SceneDatabaseLockAggregate")
    @Label("Scene Database Lock Aggregate")
    @Category({"RTRenderer", "Scene"})
    @StackTrace(false)
    static final class LockAggregateEvent extends Event {
        String stage;
        long acquisitions;
        long waitTotalNanos;
        long waitMaxNanos;
        long waitP95Nanos;
        long waitP99Nanos;
        long holdTotalNanos;
        long holdMaxNanos;
        long holdP95Nanos;
        long holdP99Nanos;
    }
}
