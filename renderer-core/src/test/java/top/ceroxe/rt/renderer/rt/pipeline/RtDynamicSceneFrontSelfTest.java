package top.ceroxe.rt.renderer.rt.pipeline;

import java.util.List;
import top.ceroxe.rt.renderer.DynamicRenderScene;
import top.ceroxe.rt.renderer.LightmapPayload;

public final class RtDynamicSceneFrontSelfTest {
   private RtDynamicSceneFrontSelfTest() {
   }

   public static void main(String[] args) {
      RtDynamicSceneFront front = new RtDynamicSceneFront();
      require(front.revision() == 0L, "bootstrap dynamic revision was not zero");
      require(front.dispatchedRevision() == -1L, "bootstrap dispatched revision was available");
      DynamicRenderScene revisionOne = emptyRevision(1L);
      front.accept(revisionOne);
      require(front.scene() == revisionOne, "accepted dynamic scene was not retained");
      require(front.revision() == 1L, "accepted dynamic revision was not retained");
      require(front.revisionChanges() == 1L, "first positive revision change was not counted");
      front.accept(DynamicRenderScene.empty());
      require(front.scene() == revisionOne, "poll-only empty scene cleared the authoritative front");
      front.accept(emptyRevision(1L));
      require(front.revisionChanges() == 1L, "same revision was counted as a revision change");
      front.recordUpload();
      front.recordDispatched(1L);
      require(front.uploads() == 1L, "dynamic upload was not counted");
      require(front.dispatchedRevision() == 1L, "dispatched dynamic revision was not retained");
      expectIllegalArgument(() -> front.recordDispatched(-1L));
      expectNullPointer(() -> front.accept((DynamicRenderScene)null));
      System.out.println("RtDynamicSceneFrontSelfTest passed");
   }

   private static DynamicRenderScene emptyRevision(long revision) {
      return new DynamicRenderScene(revision, null, null, null, null, null, null, null, null, null, null, null);
   }

   private static void expectIllegalArgument(Runnable action) {
      try {
         action.run();
         throw new AssertionError("expected IllegalArgumentException");
      } catch (IllegalArgumentException value2) {
      }
   }

   private static void expectNullPointer(Runnable action) {
      try {
         action.run();
         throw new AssertionError("expected NullPointerException");
      } catch (NullPointerException value2) {
      }
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}
