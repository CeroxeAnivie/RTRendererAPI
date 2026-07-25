package top.ceroxe.rt.renderer.rt.device;

public final class RtWorldSceneCoverageAuthoritySelfTest {
   private RtWorldSceneCoverageAuthoritySelfTest() {
   }

   public static void main(String[] args) {
      testContractionRequiresNewerAcceptedAuthority();
      testBoundStateIsMonotonic();
      testCommittedFrontInvalidationConvergesOnlyAfterBind();
      System.out.println("RtWorldSceneCoverageAuthoritySelfTest passed");
   }

   private static void testContractionRequiresNewerAcceptedAuthority() {
      RtWorldSceneCoverageAuthority authority = new RtWorldSceneCoverageAuthority();
      require(!authority.contractionAuthorizedFor(0L), "bootstrap generation authorized contraction");
      long accepted = authority.authorizeContraction();
      require(accepted == 1L, "first contraction authority was not generation one");
      require(authority.contractionAuthorizedFor(accepted), "new explicit removal authority was rejected");
      authority.recordBoundGeneration(accepted, 0L);
      require(!authority.contractionAuthorizedFor(accepted), "already-bound removal authority was reusable");
      expectIllegalArgument(() -> authority.contractionAuthorizedFor(-1L));
   }

   private static void testBoundStateIsMonotonic() {
      RtWorldSceneCoverageAuthority authority = new RtWorldSceneCoverageAuthority();
      authority.authorizeContraction();
      long newest = authority.authorizeContraction();
      authority.recordBoundGeneration(newest, 8L);
      authority.recordBoundGeneration(1L, 3L);
      require(!authority.contractionAuthorizedFor(newest), "stale bound completion regressed contraction authority");
      expectIllegalArgument(() -> authority.recordBoundGeneration(-1L, 9L));
   }

   private static void testCommittedFrontInvalidationConvergesOnlyAfterBind() {
      RtWorldSceneCoverageAuthority authority = new RtWorldSceneCoverageAuthority();
      require(authority.committedFrontGenerationIsCurrent(), "bootstrap committed front was unexpectedly stale");
      authority.recordCommittedFrontInvalidation(7L);
      require(!authority.committedFrontGenerationIsCurrent(), "invalidated committed front remained current");
      authority.recordBoundGeneration(0L, 6L);
      require(!authority.committedFrontGenerationIsCurrent(), "older bound revision cleared committed-front invalidation");
      authority.recordBoundGeneration(0L, 7L);
      require(authority.committedFrontGenerationIsCurrent(), "matching bound revision did not converge committed front");
      authority.recordCommittedFrontInvalidation(5L);
      require(authority.committedFrontGenerationIsCurrent(), "stale invalidation regressed committed-front state");
   }

   private static void expectIllegalArgument(Runnable action) {
      try {
         action.run();
         throw new AssertionError("expected IllegalArgumentException");
      } catch (IllegalArgumentException value2) {
      }
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}
