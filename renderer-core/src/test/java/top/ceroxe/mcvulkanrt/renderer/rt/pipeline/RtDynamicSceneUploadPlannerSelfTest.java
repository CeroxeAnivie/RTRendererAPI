package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

import java.util.List;

/** Contract coverage for the renderer-owned dynamic-scene dirty upload planner. */
public final class RtDynamicSceneUploadPlannerSelfTest {
    private static final int TOTAL_BYTES = 64;

    private RtDynamicSceneUploadPlannerSelfTest() {
    }

    public static void main(String[] args) {
        firstUseInitializesTheWholeAbi();
        changedRecordsStayWithinCandidateRangesAndMerge();
        invalidCandidateRangeIsRejected();
        System.out.println("RtDynamicSceneUploadPlannerSelfTest passed");
    }

    private static void firstUseInitializesTheWholeAbi() {
        List<RtRayTracingPipeline.UploadRange> ranges = RtDynamicSceneUploadPlanner.dirtyRanges(
                new byte[TOTAL_BYTES],
                new byte[TOTAL_BYTES],
                false,
                List.of(),
                TOTAL_BYTES
        );
        require(ranges.equals(List.of(new RtRayTracingPipeline.UploadRange(0, TOTAL_BYTES))),
                "first use must initialize the complete dynamic-scene ABI");
    }

    private static void changedRecordsStayWithinCandidateRangesAndMerge() {
        byte[] committed = new byte[TOTAL_BYTES];
        byte[] current = new byte[TOTAL_BYTES];
        current[16] = 1;
        current[32] = 2;
        current[48] = 3;

        List<RtRayTracingPipeline.UploadRange> ranges = RtDynamicSceneUploadPlanner.dirtyRanges(
                committed,
                current,
                true,
                List.of(
                        new RtRayTracingPipeline.UploadRange(16, 32),
                        new RtRayTracingPipeline.UploadRange(48, 16)
                ),
                TOTAL_BYTES
        );
        require(ranges.equals(List.of(new RtRayTracingPipeline.UploadRange(16, 48))),
                "adjacent dirty candidate records must merge into one transfer range");
    }

    private static void invalidCandidateRangeIsRejected() {
        requireFailure(() -> RtDynamicSceneUploadPlanner.dirtyRanges(
                new byte[TOTAL_BYTES],
                new byte[TOTAL_BYTES],
                true,
                List.of(new RtRayTracingPipeline.UploadRange(4, 16)),
                TOTAL_BYTES
        ));
    }

    private static void requireFailure(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("invalid upload candidate range was accepted");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
