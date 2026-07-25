package top.ceroxe.rt.renderer.rt.pipeline;

import top.ceroxe.rt.renderer.rt.pipeline.RtFrameDispatchAdmission.Decision;

public final class RtFrameDispatchStatisticsSelfTest {
   private RtFrameDispatchStatisticsSelfTest() {
   }

   public static void main(String[] args) {
      testAdmissionTransitions();
      testSubmissionLifecycle();
      testLatencyAggregation();
      System.out.println("RtFrameDispatchStatisticsSelfTest passed");
   }

   private static void testAdmissionTransitions() {
      RtFrameDispatchStatistics statistics = new RtFrameDispatchStatistics();
      require(statistics.observeFrameState() == 1L, "first observed frame state was not one");
      statistics.recordAdmissionRejection(Decision.PRESENTATION_ELIGIBILITY_GATE);
      statistics.recordAdmissionRejection(Decision.INVALID_FRAME_STATE);
      statistics.recordAdmissionRejection(Decision.DISPATCH_INTERVAL);
      statistics.recordAdmissionRejection(Decision.MAX_PENDING_SUBMISSIONS);
      statistics.recordNoFrameSlot();
      require(statistics.skippedPresentationGateFrames() == 1L, "presentation-gate rejection count drifted");
      require(statistics.skippedUnavailableFrameStates() == 1L, "invalid-state rejection count drifted");
      require(statistics.skippedIntervalFrames() == 1L, "interval rejection count drifted");
      require(statistics.skippedPendingBacklogFrames() == 1L, "pending backlog rejection count drifted");
      require(statistics.skippedPendingAsyncFrames() == 2L, "pending async count did not include backlog and no-slot transitions");
      require(statistics.skippedNoFrameSlots() == 1L, "no-slot rejection count drifted");
      expectIllegalArgument(() -> statistics.recordAdmissionRejection(Decision.ACCEPTED));
   }

   private static void testSubmissionLifecycle() {
      RtFrameDispatchStatistics statistics = new RtFrameDispatchStatistics();
      long firstOrdinal = statistics.nextDispatchOrdinal();
      require(firstOrdinal == 1L, "first dispatch ordinal was not one");
      statistics.recordSubmission(firstOrdinal);
      statistics.recordCompletion();
      require(statistics.frameDispatches() == 1L, "submitted ordinal was not published");
      require(statistics.asyncFrameSubmissions() == 1L, "submission count was not published");
      require(statistics.asyncFrameCompletions() == 1L, "completion count was not published");
      expectIllegalState(() -> statistics.recordSubmission(3L));
   }

   private static void testLatencyAggregation() {
      RtFrameDispatchStatistics statistics = new RtFrameDispatchStatistics();
      statistics.recordDispatchDuration(1999999L);
      statistics.recordDispatchDuration(4000000L);
      require(statistics.lastFrameDispatchMillis() == 4L, "last latency was not retained");
      require(statistics.maxFrameDispatchMillis() == 4L, "maximum latency was not retained");
      require(statistics.totalFrameDispatchMillis() == 5L, "total latency was not accumulated");
      expectIllegalArgument(() -> statistics.recordDispatchDuration(-1L));
   }

   private static void expectIllegalArgument(Runnable action) {
      try {
         action.run();
         throw new AssertionError("expected IllegalArgumentException");
      } catch (IllegalArgumentException value2) {
      }
   }

   private static void expectIllegalState(Runnable action) {
      try {
         action.run();
         throw new AssertionError("expected IllegalStateException");
      } catch (IllegalStateException value2) {
      }
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}
