package top.ceroxe.rt.renderer.rt.acceleration;

public final class RtSectionBlasConfigurationSelfTest {
   private static final String MAX_BUILDS_PROPERTY = "top.ceroxe.rt.rt.sectionBlas.maxBuildsPerFrame";
   private static final String MAX_TRIANGLES_PROPERTY = "top.ceroxe.rt.rt.sectionBlas.maxTrianglesPerFrame";
   private static final String MAX_CACHED_SECTIONS_PROPERTY = "top.ceroxe.rt.rt.sectionBlas.maxCachedSections";
   private static final String MAX_VIEW_INSTANCES_PROPERTY = "top.ceroxe.rt.rt.view.maxSectionInstances";
   private static final String FAR_FIELD_PROXY_ENABLED_PROPERTY = "top.ceroxe.rt.rt.farFieldProxy.enabled";

   private RtSectionBlasConfigurationSelfTest() {
   }

   public static void main(String[] arguments) {
      explicitConfigurationPreservesEveryLimit();
      systemPropertiesUsePositiveFallbacks();
      defaultsSeparateExactResidencyFromSourceCacheAndDisableProxyFidelity();
      invalidExplicitLimitsFailBeforeResourceAllocation();
      normalizesAsyncBatchFeedbackPerSection();
      System.out.println("RtSectionBlasConfigurationSelfTest passed");
   }

   private static void explicitConfigurationPreservesEveryLimit() {
      RtSectionBlasConfiguration configuration = RtSectionBlasConfiguration.explicit(9, 90000L, 5, 17, 19000000L, 31, 37000000L, 43, 47000000L);
      require(configuration.maxBuildsPerFrame() == 9, "explicit build count drifted");
      require(configuration.maxTrianglesPerFrame() == 90000L, "explicit triangle limit drifted");
      require(configuration.configuredMaxAsyncBuildsInFlight() == 5, "explicit async batch limit drifted");
      require(configuration.maxAsyncBuildSectionsInFlight() == 17, "explicit async section limit drifted");
      require(configuration.maxAsyncBuildBytesInFlight() == 19000000L, "explicit async byte limit drifted");
      require(configuration.maxPendingSections() == 31 && configuration.maxPendingBytes() == 37000000L, "explicit pending limits drifted");
      require(configuration.configuredMaxCachedSections() == 43 && configuration.maxCachedBytes() == 47000000L, "explicit resident limits drifted");
      require(configuration.effectiveMaxAsyncBuildsInFlight(3) == RtSectionBlasAdmissionPlanner.effectiveSubmissionWindow(5, 3), "effective queue width bypassed the shared admission planner");
      require(configuration.gpuSubmissionWindow(1) == 1, "CPU recording capacity leaked into the one-queue native submission window");
      require(configuration.gpuSubmissionWindow(3) == 3, "native submission window did not preserve one page per ordered queue");
      require(configuration.adaptiveBuildBudget().currentLimits().maxBuilds() > 0, "validated configuration produced an unusable adaptive budget");
   }

   private static void systemPropertiesUsePositiveFallbacks() {
      String previousBuilds = System.getProperty("top.ceroxe.rt.rt.sectionBlas.maxBuildsPerFrame");
      String previousTriangles = System.getProperty("top.ceroxe.rt.rt.sectionBlas.maxTrianglesPerFrame");

      try {
         System.setProperty("top.ceroxe.rt.rt.sectionBlas.maxBuildsPerFrame", "7");
         System.setProperty("top.ceroxe.rt.rt.sectionBlas.maxTrianglesPerFrame", "-19");
         RtSectionBlasConfiguration configuration = RtSectionBlasConfiguration.fromSystemProperties();
         require(configuration.maxBuildsPerFrame() == 7, "positive system property was not applied");
         require(configuration.maxTrianglesPerFrame() == 3072000L, "non-positive system property did not use the documented default");
      } finally {
         restoreProperty("top.ceroxe.rt.rt.sectionBlas.maxBuildsPerFrame", previousBuilds);
         restoreProperty("top.ceroxe.rt.rt.sectionBlas.maxTrianglesPerFrame", previousTriangles);
      }

   }

   private static void invalidExplicitLimitsFailBeforeResourceAllocation() {
      expectFailure(() -> RtSectionBlasConfiguration.explicit(0, 1L, 1, 1L, 1, 1L));
      expectFailure(() -> RtSectionBlasConfiguration.explicit(1, 1L, 1, 1, 0L, 1, 1L, 1, 1L));
   }

   private static void defaultsSeparateExactResidencyFromSourceCacheAndDisableProxyFidelity() {
      String previousCachedSections = System.getProperty("top.ceroxe.rt.rt.sectionBlas.maxCachedSections");
      String previousViewInstances = System.getProperty("top.ceroxe.rt.rt.view.maxSectionInstances");
      String previousFarFieldProxy = System.getProperty("top.ceroxe.rt.rt.farFieldProxy.enabled");

      try {
         System.clearProperty("top.ceroxe.rt.rt.sectionBlas.maxCachedSections");
         System.clearProperty("top.ceroxe.rt.rt.view.maxSectionInstances");
         System.clearProperty("top.ceroxe.rt.rt.farFieldProxy.enabled");
         RtSectionBlasConfiguration configuration = RtSectionBlasConfiguration.fromSystemProperties();
         require(configuration.configuredMaxCachedSections() == 65536, "exact BLAS residency must not inherit the 4K CPU source-cache bound");
         require(configuration.maxViewInstances() == 65536, "active exact TLAS capacity must not inherit the 4K CPU source-cache bound");
         require(!configuration.farFieldProxyEnabled(), "host application parity must default to exact geometry rather than proxy fidelity");
      } finally {
         restoreProperty("top.ceroxe.rt.rt.sectionBlas.maxCachedSections", previousCachedSections);
         restoreProperty("top.ceroxe.rt.rt.view.maxSectionInstances", previousViewInstances);
         restoreProperty("top.ceroxe.rt.rt.farFieldProxy.enabled", previousFarFieldProxy);
      }

   }

   private static void normalizesAsyncBatchFeedbackPerSection() {
      RtAdaptiveBuildBudget budget = new RtAdaptiveBuildBudget(8, 96000L, 1, 12000L, 3000000L, 7000000L);
      RtAdaptiveBuildBudget.Limits initial = budget.currentLimits();
      budget.recordBatch(64000000L, 16, true);
      require(budget.currentLimits().equals(initial), "aggregate duration of a healthy large batch must not halve per-section capacity");
      expectFailure(() -> budget.recordBatch(1L, 0, true));
   }

   private static void restoreProperty(String name, String value) {
      if (value == null) {
         System.clearProperty(name);
      } else {
         System.setProperty(name, value);
      }

   }

   private static void expectFailure(Runnable action) {
      try {
         action.run();
      } catch (IllegalArgumentException value2) {
         return;
      }

      throw new AssertionError("expected invalid configuration to fail");
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}
