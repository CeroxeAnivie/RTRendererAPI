package top.ceroxe.rt.renderer.rt.acceleration;

import java.util.concurrent.TimeUnit;

/**
 * Owns monotonic section-BLAS scheduler/resource counters and summary formatting.
 */
final class RtSectionBlasStatistics {
    private long appliedBatches;
    private long buildPasses;
    private long budgetLimitedPasses;
    private long asyncBuildSubmissions;
    private long asyncBuildWorkerStarts;
    private long asyncBuildWorkerCompletions;
    private long asyncBuildWorkerFailures;
    private long asyncBuildCompletions;
    private long asyncBuildPollsNotReady;
    private long asyncBuildResultDiscards;
    private long asyncBuildCloseWaits;
    private long asyncBuildBlockedPasses;
    private long foregroundPriorityBuilds;
    private long backgroundBuildReservationDeferrals;
    private long backgroundQueueHeadDeferrals;
    private long foregroundProgressDependencyReleases;
    private long builtSections;
    private long removedSections;
    private long emptyMeshes;
    private long fullResyncClears;
    private long peakCachedBlasBytes;
    private long evictedCachedSections;
    private long totalTrianglesBuilt;
    private long lastBuildBatchMillis;
    private long maxBuildBatchMillis;
    private long totalBuildBatchMillis;
    private long lastAsyncBuildLatencyMillis;
    private long maxAsyncBuildLatencyMillis;
    private long totalAsyncBuildLatencyMillis;
    private long lastAsyncWorkerSubmitMillis;
    private long maxAsyncWorkerSubmitMillis;
    private long totalAsyncWorkerSubmitMillis;
    private long lastAsyncWorkerSequence = -1L;
    private int lastAsyncWorkerSections;
    private long lastAsyncWorkerTriangles;
    private int lastBuildBatchSections;
    private long lastBuildBatchTriangles;

    private static void validateWorker(long sequence, int sections, long triangles) {
        if (sequence < 0L || sections < 0 || triangles < 0L) {
            throw new IllegalArgumentException("async worker statistics must not be negative");
        }
    }

    private static void validateElapsed(long elapsedNanos) {
        if (elapsedNanos < 0L) {
            throw new IllegalArgumentException("BLAS telemetry duration must not be negative");
        }
    }

    void appliedBatch() {
        appliedBatches++;
    }

    void asyncBuildSubmitted() {
        asyncBuildSubmissions++;
    }

    void asyncBuildCompleted() {
        asyncBuildCompletions++;
    }

    void asyncBuildPollNotReady() {
        asyncBuildPollsNotReady++;
    }

    void asyncBuildResultDiscarded() {
        asyncBuildResultDiscards++;
    }

    void asyncBuildCloseWaited() {
        asyncBuildCloseWaits++;
    }

    void foregroundPriorityBuild() {
        foregroundPriorityBuilds++;
    }

    void backgroundReservationDeferred() {
        backgroundBuildReservationDeferrals++;
    }

    void backgroundQueueHeadDeferred() {
        backgroundQueueHeadDeferrals++;
    }

    void foregroundProgressDependencyReleased() {
        foregroundProgressDependencyReleases++;
    }

    void emptyMesh() {
        emptyMeshes++;
    }

    void fullResyncClear() {
        fullResyncClears++;
    }

    void frameBudgetPass(boolean submitted, boolean asyncBlocked, boolean pendingAfterPass) {
        if (submitted) {
            buildPasses++;
        }
        if (asyncBlocked) {
            asyncBuildBlockedPasses++;
        }
        if (pendingAfterPass) {
            budgetLimitedPasses++;
        }
    }

    void workerStarted(long sequence, int sections, long triangles) {
        validateWorker(sequence, sections, triangles);
        asyncBuildWorkerStarts++;
        rememberWorker(sequence, sections, triangles);
    }

    void workerCompleted(long sequence, int sections, long triangles, long elapsedNanos) {
        validateWorker(sequence, sections, triangles);
        validateElapsed(elapsedNanos);
        asyncBuildWorkerCompletions++;
        rememberWorker(sequence, sections, triangles);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        lastAsyncWorkerSubmitMillis = elapsedMillis;
        maxAsyncWorkerSubmitMillis = Math.max(maxAsyncWorkerSubmitMillis, elapsedMillis);
        totalAsyncWorkerSubmitMillis = Math.addExact(totalAsyncWorkerSubmitMillis, elapsedMillis);
    }

    void workerFailed(long sequence, int sections, long triangles) {
        validateWorker(sequence, sections, triangles);
        asyncBuildWorkerFailures++;
        rememberWorker(sequence, sections, triangles);
    }

    void builtSection(int triangles, long cachedBlasBytes) {
        if (triangles < 0 || cachedBlasBytes < 0L) {
            throw new IllegalArgumentException("built section statistics must not be negative");
        }
        builtSections++;
        totalTrianglesBuilt = Math.addExact(totalTrianglesBuilt, triangles);
        peakCachedBlasBytes = Math.max(peakCachedBlasBytes, cachedBlasBytes);
    }

    void removedSection(boolean evicted) {
        removedSections++;
        if (evicted) {
            evictedCachedSections++;
        }
    }

    void recordBuildBatch(int sections, long triangles, long elapsedNanos) {
        if (sections < 0 || triangles < 0L) {
            throw new IllegalArgumentException("build batch statistics must not be negative");
        }
        validateElapsed(elapsedNanos);
        lastBuildBatchSections = sections;
        lastBuildBatchTriangles = triangles;
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        lastBuildBatchMillis = elapsedMillis;
        maxBuildBatchMillis = Math.max(maxBuildBatchMillis, elapsedMillis);
        totalBuildBatchMillis = Math.addExact(totalBuildBatchMillis, elapsedMillis);
    }

    void recordAsyncBuildLatency(long elapsedNanos) {
        validateElapsed(elapsedNanos);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        lastAsyncBuildLatencyMillis = elapsedMillis;
        maxAsyncBuildLatencyMillis = Math.max(maxAsyncBuildLatencyMillis, elapsedMillis);
        totalAsyncBuildLatencyMillis = Math.addExact(totalAsyncBuildLatencyMillis, elapsedMillis);
    }

    String summary() {
        return "appliedBatches=" + appliedBatches
                + ", buildPasses=" + buildPasses
                + ", budgetLimitedPasses=" + budgetLimitedPasses
                + ", asyncBuildSubmissions=" + asyncBuildSubmissions
                + ", asyncBuildWorkerStarts=" + asyncBuildWorkerStarts
                + ", asyncBuildWorkerCompletions=" + asyncBuildWorkerCompletions
                + ", asyncBuildWorkerFailures=" + asyncBuildWorkerFailures
                + ", asyncBuildCompletions=" + asyncBuildCompletions
                + ", asyncBuildPollsNotReady=" + asyncBuildPollsNotReady
                + ", asyncBuildResultDiscards=" + asyncBuildResultDiscards
                + ", asyncBuildCloseWaits=" + asyncBuildCloseWaits
                + ", asyncBuildBlockedPasses=" + asyncBuildBlockedPasses
                + ", foregroundPriorityBuilds=" + foregroundPriorityBuilds
                + ", backgroundBuildReservationDeferrals=" + backgroundBuildReservationDeferrals
                + ", backgroundQueueHeadDeferrals=" + backgroundQueueHeadDeferrals
                + ", foregroundProgressDependencyReleases=" + foregroundProgressDependencyReleases
                + ", builtSections=" + builtSections
                + ", removedSections=" + removedSections
                + ", emptyMeshes=" + emptyMeshes
                + ", peakCachedBlasBytes=" + peakCachedBlasBytes
                + ", evictedCachedSections=" + evictedCachedSections
                + ", totalTrianglesBuilt=" + totalTrianglesBuilt
                + ", lastBuildBatchSections=" + lastBuildBatchSections
                + ", lastBuildBatchTriangles=" + lastBuildBatchTriangles
                + ", lastBuildBatchMillis=" + lastBuildBatchMillis
                + ", maxBuildBatchMillis=" + maxBuildBatchMillis
                + ", totalBuildBatchMillis=" + totalBuildBatchMillis
                + ", lastAsyncBuildLatencyMillis=" + lastAsyncBuildLatencyMillis
                + ", maxAsyncBuildLatencyMillis=" + maxAsyncBuildLatencyMillis
                + ", totalAsyncBuildLatencyMillis=" + totalAsyncBuildLatencyMillis
                + ", lastAsyncWorkerSequence=" + lastAsyncWorkerSequence
                + ", lastAsyncWorkerSections=" + lastAsyncWorkerSections
                + ", lastAsyncWorkerTriangles=" + lastAsyncWorkerTriangles
                + ", lastAsyncWorkerSubmitMillis=" + lastAsyncWorkerSubmitMillis
                + ", maxAsyncWorkerSubmitMillis=" + maxAsyncWorkerSubmitMillis
                + ", totalAsyncWorkerSubmitMillis=" + totalAsyncWorkerSubmitMillis
                + ", fullResyncClears=" + fullResyncClears;
    }

    private void rememberWorker(long sequence, int sections, long triangles) {
        lastAsyncWorkerSequence = sequence;
        lastAsyncWorkerSections = sections;
        lastAsyncWorkerTriangles = triangles;
    }
}
