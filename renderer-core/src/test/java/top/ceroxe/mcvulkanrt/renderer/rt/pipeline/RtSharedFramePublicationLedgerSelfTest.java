package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

import top.ceroxe.mcvulkanrt.renderer.rt.runtime.RtCore;

import java.util.Set;

/** Pure contract coverage for frame-slot retention and scene-publication identity. */
public final class RtSharedFramePublicationLedgerSelfTest {
    private RtSharedFramePublicationLedgerSelfTest() {
    }

    public static void main(String[] args) {
        completionRetainsPublishedSlotsButExposesSupersededSlot();
        exportReservationReleasesOnlyUnretainedPreviousExport();
        presentedFrontCanBeReservedAfterNewCompletion();
        acknowledgementPromotesOnlyTheCurrentCompletedSceneProof();
        System.out.println("RtSharedFramePublicationLedgerSelfTest passed");
    }

    private static void completionRetainsPublishedSlotsButExposesSupersededSlot() {
        RtSharedFramePublicationLedger<Object> ledger = new RtSharedFramePublicationLedger<>();
        Object first = new Object();
        Object second = new Object();
        ledger.complete(first, state(1L));
        ledger.reserveExport(first);

        RtSharedFramePublicationLedger.Completion<Object> completion = ledger.complete(second, state(2L));
        require(completion.previousLatest() == first, "completion must expose the superseded latest slot");
        require(ledger.retainsForPublication(first), "an exported slot must remain retained across a newer completion");
        require(!ledger.retainsForPublication(second), "the latest slot alone is not a presentation hold");
    }

    private static void exportReservationReleasesOnlyUnretainedPreviousExport() {
        RtSharedFramePublicationLedger<Object> ledger = new RtSharedFramePublicationLedger<>();
        Object first = new Object();
        Object second = new Object();
        ledger.complete(first, state(1L));
        ledger.reserveExport(first);
        ledger.complete(second, state(2L));

        RtSharedFramePublicationLedger.ExportReservation<Object> reservation = ledger.reserveExport(second);
        require(reservation.previousExport() == first, "second export must identify the prior export hold");
        require(!ledger.retainsForPublication(first), "superseded export must no longer retain its slot");
        require(ledger.retainsForPublication(second), "current export must retain its slot");
    }

    private static void acknowledgementPromotesOnlyTheCurrentCompletedSceneProof() {
        RtSharedFramePublicationLedger<Object> ledger = new RtSharedFramePublicationLedger<>();
        Object first = new Object();
        Object retired = new Object();
        ledger.complete(first, state(7L));

        RtSharedFramePublicationLedger.Acknowledgement<Object> firstAcknowledgement = ledger.acknowledge(first, 7L);
        require(!firstAcknowledgement.alreadyPresented(), "first acknowledgement must create presentation ownership");
        require(ledger.presentedState().available(), "current completion must promote its frozen scene proof");
        require(ledger.presentedState().frameStateSequence() == 7L, "presented proof sequence must match acknowledgement");

        RtSharedFramePublicationLedger.Acknowledgement<Object> repeated = ledger.acknowledge(first, 7L);
        require(repeated.alreadyPresented(), "same slot acknowledgement must be idempotent");

        ledger.acknowledge(retired, 6L);
        require(!ledger.presentedState().available(),
                "a retired slot without the current completion proof must not publish stale scene coverage");
    }

    private static void presentedFrontCanBeReservedAfterNewCompletion() {
        RtSharedFramePublicationLedger<Object> ledger = new RtSharedFramePublicationLedger<>();
        Object presented = new Object();
        Object successor = new Object();
        ledger.complete(presented, state(11L));
        ledger.acknowledge(presented, 11L);
        ledger.complete(successor, state(12L));

        RtSharedFramePublicationLedger.ExportReservation<Object> reservation = ledger.reserveExport(presented);
        require(reservation.exported() == presented,
                "the acknowledged front must remain exportable while a successor is incomplete");
        require(ledger.retainsForPublication(presented),
                "reserving the acknowledged front must retain its image lifetime");
    }

    private static RtCore.SharedFrameState state(long sequence) {
        return new RtCore.SharedFrameState(sequence, Set.of());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
