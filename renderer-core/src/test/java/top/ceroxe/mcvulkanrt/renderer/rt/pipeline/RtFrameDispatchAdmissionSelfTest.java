package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

/**
 * Locks the ordered, allocation-free frame admission contract independently from Vulkan.
 *
 * <p>Keeping this decision test outside the native gates makes a future scheduler change fail at
 * the smallest useful boundary instead of being diagnosed as a missing GPU frame much later.</p>
 */
public final class RtFrameDispatchAdmissionSelfTest {
    private RtFrameDispatchAdmissionSelfTest() {
    }

    public static void main(String[] args) {
        presentationGateWinsBeforeAllOtherReasons();
        invalidFrameWinsBeforeCadenceAndBacklog();
        cadenceWinsBeforeBacklog();
        backlogWinsBeforeRingCapacity();
        ringCapacityIsTheLastAdmissionRejector();
        acceptsOnlyWhenEveryGateIsClear();
        rejectsInvalidStateShape();
        System.out.println("RtFrameDispatchAdmissionSelfTest passed");
    }

    private static void presentationGateWinsBeforeAllOtherReasons() {
        RtFrameDispatchAdmission.Decision decision = decide(
                true, false, false, false, true, 4, 4, 4
        );
        require(decision == RtFrameDispatchAdmission.Decision.PRESENTATION_ELIGIBILITY_GATE,
                "presentation eligibility must be the first rejection reason");
        require(decision.incrementsPresentationGateSkips(), "presentation gate counter must be marked");
        require(!decision.incrementsPendingAsyncSkips(), "presentation gate must not claim queue pressure");
    }

    private static void invalidFrameWinsBeforeCadenceAndBacklog() {
        RtFrameDispatchAdmission.Decision decision = decide(
                false, false, true, false, true, 4, 4, 4
        );
        require(decision == RtFrameDispatchAdmission.Decision.INVALID_FRAME_STATE,
                "invalid frame state must be rejected before cadence and backlog");
        require(decision.incrementsUnavailableStateSkips(), "invalid frame counter must be marked");
    }

    private static void cadenceWinsBeforeBacklog() {
        RtFrameDispatchAdmission.Decision decision = decide(
                false, true, true, true, true, 4, 4, 4
        );
        require(decision == RtFrameDispatchAdmission.Decision.DISPATCH_INTERVAL,
                "dispatch cadence must be evaluated before pending submissions");
        require(decision.incrementsIntervalSkips(), "cadence counter must be marked");
    }

    private static void backlogWinsBeforeRingCapacity() {
        RtFrameDispatchAdmission.Decision decision = decide(
                false, true, true, true, false, 4, 4, 8
        );
        require(decision == RtFrameDispatchAdmission.Decision.MAX_PENDING_SUBMISSIONS,
                "configured pending limit must be reported before physical ring capacity");
        require(decision.incrementsPendingAsyncSkips(), "pending async counter must be marked");
        require(decision.incrementsPendingBacklogSkips(), "pending backlog counter must be marked");
    }

    private static void ringCapacityIsTheLastAdmissionRejector() {
        RtFrameDispatchAdmission.Decision decision = decide(
                false, true, true, true, false, 4, 8, 4
        );
        require(decision == RtFrameDispatchAdmission.Decision.FRAME_SLOT_RING_BUSY,
                "physical frame ring capacity must be the final admission rejection");
        require(decision.incrementsPendingAsyncSkips(), "ring pressure must mark async skips");
        require(!decision.incrementsPendingBacklogSkips(), "ring pressure is not configured backlog pressure");
    }

    private static void acceptsOnlyWhenEveryGateIsClear() {
        RtFrameDispatchAdmission.Decision decision = decide(
                true, true, false, true, false, 3, 4, 4
        );
        require(decision == RtFrameDispatchAdmission.Decision.ACCEPTED,
                "an eligible valid frame with capacity must be accepted");
        require(decision.accepted(), "accepted decision must expose its state explicitly");
    }

    private static void rejectsInvalidStateShape() {
        requireFailure(() -> new RtFrameDispatchAdmission.State(false, true, true, true, false, -1, 1, 1));
        requireFailure(() -> new RtFrameDispatchAdmission.State(false, true, true, true, false, 0, 0, 1));
        requireFailure(() -> new RtFrameDispatchAdmission.State(false, true, true, true, false, 0, 1, 0));
    }

    private static RtFrameDispatchAdmission.Decision decide(
            boolean requiresPresentationEligibility,
            boolean presentationEligible,
            boolean presentationProbeDue,
            boolean frameStateValid,
            boolean intervalBlocked,
            int pendingSubmissionCount,
            int maxPendingSubmissions,
            int frameSlotCount
    ) {
        return RtFrameDispatchAdmission.decide(new RtFrameDispatchAdmission.State(
                requiresPresentationEligibility,
                presentationEligible,
                presentationProbeDue,
                frameStateValid,
                intervalBlocked,
                pendingSubmissionCount,
                maxPendingSubmissions,
                frameSlotCount
        ));
    }

    private static void requireFailure(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("invalid admission state was accepted");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
