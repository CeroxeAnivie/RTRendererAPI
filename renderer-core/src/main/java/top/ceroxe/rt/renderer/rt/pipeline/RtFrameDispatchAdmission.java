package top.ceroxe.rt.renderer.rt.pipeline;

/**
 * Pure, ordered admission decision for a frame dispatch.
 *
 * <p>The ordering is observable API: presentation gating must win over cadence, cadence must win
 * over queue pressure, and queue pressure must win over slot acquisition. A caller applies the
 * returned counter effects and records the reason before it begins resource preparation.
 */
final class RtFrameDispatchAdmission {
    private RtFrameDispatchAdmission() {
    }

    static Decision decide(State state) {
        if (state.presentationEligibilityRequired()
                && !state.presentationEligible()
                && !state.presentationProbeDue()) {
            return Decision.PRESENTATION_ELIGIBILITY_GATE;
        }
        if (!state.frameStateValid()) {
            return Decision.INVALID_FRAME_STATE;
        }
        if (state.dispatchIntervalBlocked()) {
            return Decision.DISPATCH_INTERVAL;
        }
        if (state.pendingSubmissionCount() >= state.maxPendingSubmissions()) {
            return Decision.MAX_PENDING_SUBMISSIONS;
        }
        if (state.pendingSubmissionCount() >= state.frameSlotCount()) {
            return Decision.FRAME_SLOT_RING_BUSY;
        }
        return Decision.ACCEPTED;
    }

    enum Decision {
        ACCEPTED("accepted", false, false, false, false, false),
        PRESENTATION_ELIGIBILITY_GATE("presentationEligibilityGate", true, false, false, false, false),
        INVALID_FRAME_STATE("invalidFrameState", false, true, false, false, false),
        DISPATCH_INTERVAL("dispatchInterval", false, false, true, false, false),
        MAX_PENDING_SUBMISSIONS("maxPendingSubmissions", false, false, false, true, true),
        FRAME_SLOT_RING_BUSY("frameSlotRingBusy", false, false, false, true, false);

        private final String rejectionReason;
        private final boolean incrementsPresentationGateSkips;
        private final boolean incrementsUnavailableStateSkips;
        private final boolean incrementsIntervalSkips;
        private final boolean incrementsPendingAsyncSkips;
        private final boolean incrementsPendingBacklogSkips;

        Decision(
                String rejectionReason,
                boolean incrementsPresentationGateSkips,
                boolean incrementsUnavailableStateSkips,
                boolean incrementsIntervalSkips,
                boolean incrementsPendingAsyncSkips,
                boolean incrementsPendingBacklogSkips
        ) {
            this.rejectionReason = rejectionReason;
            this.incrementsPresentationGateSkips = incrementsPresentationGateSkips;
            this.incrementsUnavailableStateSkips = incrementsUnavailableStateSkips;
            this.incrementsIntervalSkips = incrementsIntervalSkips;
            this.incrementsPendingAsyncSkips = incrementsPendingAsyncSkips;
            this.incrementsPendingBacklogSkips = incrementsPendingBacklogSkips;
        }

        boolean accepted() {
            return this == ACCEPTED;
        }

        String rejectionReason() {
            return rejectionReason;
        }

        boolean incrementsPresentationGateSkips() {
            return incrementsPresentationGateSkips;
        }

        boolean incrementsUnavailableStateSkips() {
            return incrementsUnavailableStateSkips;
        }

        boolean incrementsIntervalSkips() {
            return incrementsIntervalSkips;
        }

        boolean incrementsPendingAsyncSkips() {
            return incrementsPendingAsyncSkips;
        }

        boolean incrementsPendingBacklogSkips() {
            return incrementsPendingBacklogSkips;
        }
    }

    record State(
            boolean presentationEligibilityRequired,
            boolean presentationEligible,
            boolean presentationProbeDue,
            boolean frameStateValid,
            boolean dispatchIntervalBlocked,
            int pendingSubmissionCount,
            int maxPendingSubmissions,
            int frameSlotCount
    ) {
        State {
            if (pendingSubmissionCount < 0) {
                throw new IllegalArgumentException("pendingSubmissionCount must not be negative");
            }
            if (maxPendingSubmissions <= 0) {
                throw new IllegalArgumentException("maxPendingSubmissions must be positive");
            }
            if (frameSlotCount <= 0) {
                throw new IllegalArgumentException("frameSlotCount must be positive");
            }
        }
    }
}
