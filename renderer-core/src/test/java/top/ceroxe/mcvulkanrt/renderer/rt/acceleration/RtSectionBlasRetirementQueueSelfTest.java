package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

/** State-machine checks for bootstrap-safe deferred BLAS release. */
public final class RtSectionBlasRetirementQueueSelfTest {
    private RtSectionBlasRetirementQueueSelfTest() {
    }

    public static void main(String[] args) {
        RtSectionBlasRetirementQueue queue = new RtSectionBlasRetirementQueue();
        queue.releaseThrough(-1L);

        require(!RtSectionBlasRetirementQueue.isReleasable(41L, 40L),
                "a protected predecessor must retain the BLAS");
        require(RtSectionBlasRetirementQueue.isReleasable(41L, 41L),
                "the matching protected revision must release the BLAS");
        require(expectFailure(() -> RtSectionBlasRetirementQueue.isReleasable(0L, -1L))
                        instanceof IllegalArgumentException,
                "a non-empty release decision must reject the bootstrap sentinel");
        System.out.println("RtSectionBlasRetirementQueueSelfTest passed");
    }

    private static RuntimeException expectFailure(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            return failure;
        }
        throw new AssertionError("expected failure");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
