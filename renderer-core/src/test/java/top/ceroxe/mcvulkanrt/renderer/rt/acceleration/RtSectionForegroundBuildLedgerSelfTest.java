package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import top.ceroxe.mcvulkanrt.renderer.scene.PackedSectionMembership;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;

import java.util.List;
import java.util.Set;

/** Verifies authoritative foreground membership and bounded round-robin recovery order. */
public final class RtSectionForegroundBuildLedgerSelfTest {
    private RtSectionForegroundBuildLedgerSelfTest() {
    }

    public static void main(String[] arguments) {
        RtSectionForegroundBuildLedger ledger = new RtSectionForegroundBuildLedger();
        SectionKey first = new SectionKey(1, 0, 0);
        SectionKey second = new SectionKey(2, 0, 0);
        SectionKey third = new SectionKey(3, 0, 0);
        PackedSectionMembership authority = PackedSectionMembership.copyOf(List.of(first, second, third));

        ledger.reconcile(authority, key -> !key.equals(third));
        require(ledger.size() == 2 && ledger.inspectionBudget(128) == 2,
                "foreground reconcile did not retain exactly the incomplete sections");
        ledger.reconcile(authority, key -> !key.equals(third));
        require(ledger.size() == 2 && ledger.inspectionBudget(128) == 2,
                "an unchanged successor reconciliation must not discharge still-required work");
        SectionKey firstCandidate = ledger.pollRecoveryCandidate();
        ledger.defer(firstCandidate);
        SectionKey secondCandidate = ledger.pollRecoveryCandidate();
        require(!firstCandidate.equals(secondCandidate),
                "deferred foreground work must rotate behind the next required section");
        ledger.complete(secondCandidate);
        require(ledger.size() == 1, "completed foreground work remained authoritative");

        ledger.reconcile(
                PackedSectionMembership.copyOf(List.of(third)),
                Set.of(third)::contains
        );
        require(ledger.size() == 1 && ledger.pollRecoveryCandidate().equals(third),
                "authority replacement retained a stale recovery candidate");
        ledger.clear();
        require(!ledger.hasRecoveryWork() && ledger.size() == 0,
                "foreground ledger clear did not release both owners");
        expectFailure(() -> ledger.defer(first));
        expectFailure(() -> ledger.inspectionBudget(-1));
        System.out.println("RtSectionForegroundBuildLedgerSelfTest passed");
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
