package top.ceroxe.rt.renderer.rt.acceleration;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import top.ceroxe.rt.renderer.scene.PackedSectionMembership;
import top.ceroxe.rt.renderer.scene.SectionKey;

public final class RtSectionForegroundBuildLedgerSelfTest {
   private RtSectionForegroundBuildLedgerSelfTest() {
   }

   public static void main(String[] arguments) {
      RtSectionForegroundBuildLedger ledger = new RtSectionForegroundBuildLedger();
      SectionKey first = new SectionKey(1, 0, 0);
      SectionKey second = new SectionKey(2, 0, 0);
      SectionKey third = new SectionKey(3, 0, 0);
      PackedSectionMembership authority = PackedSectionMembership.copyOf(List.of(first, second, third));
      ledger.reconcile(authority, (key) -> !key.equals(third));
      require(ledger.size() == 2 && ledger.inspectionBudget(128) == 2, "foreground reconcile did not retain exactly the incomplete sections");
      ledger.reconcile(authority, (key) -> !key.equals(third));
      require(ledger.size() == 2 && ledger.inspectionBudget(128) == 2, "an unchanged successor reconciliation must not discharge still-required work");
      SectionKey firstCandidate = ledger.pollRecoveryCandidate();
      ledger.defer(firstCandidate);
      SectionKey secondCandidate = ledger.pollRecoveryCandidate();
      require(!firstCandidate.equals(secondCandidate), "deferred foreground work must rotate behind the next required section");
      ledger.complete(secondCandidate);
      require(ledger.size() == 1, "completed foreground work remained authoritative");
      PackedSectionMembership packedSectionMembership10001 = PackedSectionMembership.copyOf(List.of(third));
      Set values10002 = Set.of(third);
      Objects.requireNonNull(values10002);
      ledger.reconcile(packedSectionMembership10001, values10002::contains);
      require(ledger.size() == 1 && ledger.pollRecoveryCandidate().equals(third), "authority replacement retained a stale recovery candidate");
      ledger.clear();
      require(!ledger.hasRecoveryWork() && ledger.size() == 0, "foreground ledger clear did not release both owners");
      expectFailure(() -> ledger.defer(first));
      expectFailure(() -> ledger.inspectionBudget(-1));
      System.out.println("RtSectionForegroundBuildLedgerSelfTest passed");
   }

   private static void expectFailure(Runnable action) {
      try {
         action.run();
      } catch (RuntimeException value2) {
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
