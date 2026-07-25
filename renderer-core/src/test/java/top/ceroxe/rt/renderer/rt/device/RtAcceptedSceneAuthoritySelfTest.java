package top.ceroxe.rt.renderer.rt.device;

import top.ceroxe.rt.renderer.RendererFrameCausality;

public final class RtAcceptedSceneAuthoritySelfTest {
   private RtAcceptedSceneAuthoritySelfTest() {
   }

   public static void main(String[] args) {
      RtAcceptedSceneAuthority authority = new RtAcceptedSceneAuthority();
      require(authority.viewRevision() == -1L, "bootstrap view revision was not unbound");
      RendererFrameCausality terrain = RendererFrameCausality.untraced(11L);
      RendererFrameCausality dynamic = RendererFrameCausality.untraced(12L);
      RendererFrameCausality current = RendererFrameCausality.untraced(13L);
      authority.acceptViewRevision(7L);
      authority.acceptTerrain(terrain);
      authority.acceptDynamic(dynamic);
      require(authority.viewRevision() == 7L, "accepted view revision was not retained");
      require(authority.worldBuildCausality(true, true, current) == terrain, "terrain work did not take causality precedence");
      require(authority.worldBuildCausality(false, true, current) == dynamic, "dynamic work did not retain dynamic causality");
      require(authority.worldBuildCausality(false, false, current) == current, "poll-only frame did not retain current causality");
      expectNullPointer(() -> authority.acceptTerrain((RendererFrameCausality)null));
      expectNullPointer(() -> authority.acceptDynamic((RendererFrameCausality)null));
      expectNullPointer(() -> authority.worldBuildCausality(false, false, (RendererFrameCausality)null));
      System.out.println("RtAcceptedSceneAuthoritySelfTest passed");
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
