package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

/** Verifies monotonic BLAS statistics, duration conversion, and stable summary field names. */
public final class RtSectionBlasStatisticsSelfTest {
    private RtSectionBlasStatisticsSelfTest() {
    }

    public static void main(String[] arguments) {
        RtSectionBlasStatistics statistics = new RtSectionBlasStatistics();
        statistics.appliedBatch();
        statistics.frameBudgetPass(true, true, true);
        statistics.asyncBuildSubmitted();
        statistics.workerStarted(5L, 2, 30L);
        statistics.workerCompleted(5L, 2, 30L, 3_400_000L);
        statistics.asyncBuildCompleted();
        statistics.builtSection(30, 4_096L);
        statistics.recordBuildBatch(2, 30L, 4_900_000L);
        statistics.recordAsyncBuildLatency(5_900_000L);
        statistics.removedSection(true);
        statistics.fullResyncClear();

        String summary = statistics.summary();
        require(summary.contains("appliedBatches=1")
                        && summary.contains("buildPasses=1")
                        && summary.contains("asyncBuildBlockedPasses=1"),
                "scheduler counters were not published");
        require(summary.contains("lastAsyncWorkerSequence=5")
                        && summary.contains("lastAsyncWorkerSubmitMillis=3"),
                "worker identity/timing counters were not published");
        require(summary.contains("peakCachedBlasBytes=4096")
                        && summary.contains("totalTrianglesBuilt=30")
                        && summary.contains("evictedCachedSections=1"),
                "resource counters were not published");
        expectFailure(() -> statistics.workerStarted(-1L, 0, 0L));
        expectFailure(() -> statistics.recordBuildBatch(0, 0L, -1L));
        System.out.println("RtSectionBlasStatisticsSelfTest passed");
    }

    private static void expectFailure(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError("expected operation to fail");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
