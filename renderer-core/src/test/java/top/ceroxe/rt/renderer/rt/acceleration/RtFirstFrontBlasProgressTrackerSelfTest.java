package top.ceroxe.rt.renderer.rt.acceleration;

import java.util.List;
import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.rt.runtime.RtCore;
import top.ceroxe.rt.renderer.scene.PackedSectionMembership;
import top.ceroxe.rt.renderer.scene.SectionKey;

public final class RtFirstFrontBlasProgressTrackerSelfTest {
   private RtFirstFrontBlasProgressTrackerSelfTest() {
   }

   public static void main(String[] arguments) {
      RtFirstFrontBlasProgressTracker tracker = new RtFirstFrontBlasProgressTracker(RendererRtDiagnostics.noop());
      SectionKey first = new SectionKey(1, 2, 3);
      SectionKey second = new SectionKey(4, 5, 6);
      PackedSectionMembership authoritative = PackedSectionMembership.copyOf(List.of(first, second));
      PackedSectionMembership oneSection = PackedSectionMembership.copyOf(List.of(first));
      PackedSectionMembership complete = PackedSectionMembership.copyOf(List.of(first, second));
      PackedSectionMembership empty = PackedSectionMembership.empty();
      RtCore.RuntimeActivity runtime = new RtCore.RuntimeActivity(0L, 0L, -1L);
      tracker.recordProgress(7L, authoritative, 1L, complete, 1L, oneSection, 1L, empty, 1L, empty, 1L, oneSection, 1L, empty, -9223372036854775808L, runtime);
      RtFirstFrontBlasProgressTracker.Progress partial = tracker.latestProgress();
      require(partial.required() == 2 && partial.source() == 2 && partial.queued() == 1, "first-front stage coverage counts were not preserved");
      require(!partial.complete(), "partial active/bound coverage must not report completion");
      require(tracker.activeCoverageIncomplete(authoritative, 1L, oneSection, 1L), "partial active membership must remain incomplete");
      require(!tracker.boundCovers(authoritative, 1L, oneSection, 1L, complete), "bound coverage cannot hide incomplete active BLAS ownership");
      tracker.recordProgress(8L, authoritative, 1L, complete, 1L, empty, 2L, empty, 2L, empty, 2L, complete, 2L, complete, -9223372036854775808L, runtime);
      require(tracker.latestProgress().complete(), "matching active and bound publications must complete the first front");
      require(tracker.boundCovers(authoritative, 1L, complete, 2L, complete), "complete bound coverage proof was rejected");
      tracker.reset();
      require(tracker.latestProgress().required() == 0, "reset must discard the previous front's diagnostic publication");
      System.out.println("RtFirstFrontBlasProgressTrackerSelfTest passed");
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}
