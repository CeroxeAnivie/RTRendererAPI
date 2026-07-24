package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

/** Verifies one validated source of truth for section-BLAS defaults and explicit limits. */
public final class RtSectionBlasConfigurationSelfTest {
    private static final String MAX_BUILDS_PROPERTY = "mcvulkanrt.rt.sectionBlas.maxBuildsPerFrame";
    private static final String MAX_TRIANGLES_PROPERTY = "mcvulkanrt.rt.sectionBlas.maxTrianglesPerFrame";
    private static final String MAX_CACHED_SECTIONS_PROPERTY = "mcvulkanrt.rt.sectionBlas.maxCachedSections";
    private static final String MAX_VIEW_INSTANCES_PROPERTY = "mcvulkanrt.rt.view.maxSectionInstances";
    private static final String FAR_FIELD_PROXY_ENABLED_PROPERTY = "mcvulkanrt.rt.farFieldProxy.enabled";

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
        RtSectionBlasConfiguration configuration = RtSectionBlasConfiguration.explicit(
                9,
                90_000L,
                5,
                17,
                19_000_000L,
                31,
                37_000_000L,
                43,
                47_000_000L
        );
        require(configuration.maxBuildsPerFrame() == 9, "explicit build count drifted");
        require(configuration.maxTrianglesPerFrame() == 90_000L, "explicit triangle limit drifted");
        require(configuration.configuredMaxAsyncBuildsInFlight() == 5,
                "explicit async batch limit drifted");
        require(configuration.maxAsyncBuildSectionsInFlight() == 17,
                "explicit async section limit drifted");
        require(configuration.maxAsyncBuildBytesInFlight() == 19_000_000L,
                "explicit async byte limit drifted");
        require(configuration.maxPendingSections() == 31 && configuration.maxPendingBytes() == 37_000_000L,
                "explicit pending limits drifted");
        require(configuration.configuredMaxCachedSections() == 43
                        && configuration.maxCachedBytes() == 47_000_000L,
                "explicit resident limits drifted");
        require(configuration.effectiveMaxAsyncBuildsInFlight(3)
                        == RtSectionBlasAdmissionPlanner.effectiveSubmissionWindow(5, 3),
                "effective queue width bypassed the shared admission planner");
        require(configuration.gpuSubmissionWindow(1) == 1,
                "CPU recording capacity leaked into the one-queue native submission window");
        require(configuration.gpuSubmissionWindow(3) == 3,
                "native submission window did not preserve one page per ordered queue");
        require(configuration.adaptiveBuildBudget().currentLimits().maxBuilds() > 0,
                "validated configuration produced an unusable adaptive budget");
    }

    private static void systemPropertiesUsePositiveFallbacks() {
        String previousBuilds = System.getProperty(MAX_BUILDS_PROPERTY);
        String previousTriangles = System.getProperty(MAX_TRIANGLES_PROPERTY);
        try {
            System.setProperty(MAX_BUILDS_PROPERTY, "7");
            System.setProperty(MAX_TRIANGLES_PROPERTY, "-19");
            RtSectionBlasConfiguration configuration = RtSectionBlasConfiguration.fromSystemProperties();
            require(configuration.maxBuildsPerFrame() == 7,
                    "positive system property was not applied");
            require(configuration.maxTrianglesPerFrame() == 3_072_000L,
                    "non-positive system property did not use the documented default");
        } finally {
            restoreProperty(MAX_BUILDS_PROPERTY, previousBuilds);
            restoreProperty(MAX_TRIANGLES_PROPERTY, previousTriangles);
        }
    }

    private static void invalidExplicitLimitsFailBeforeResourceAllocation() {
        expectFailure(() -> RtSectionBlasConfiguration.explicit(
                0, 1L, 1, 1L, 1, 1L
        ));
        expectFailure(() -> RtSectionBlasConfiguration.explicit(
                1, 1L, 1, 1, 0L, 1, 1L, 1, 1L
        ));
    }

    private static void defaultsSeparateExactResidencyFromSourceCacheAndDisableProxyFidelity() {
        String previousCachedSections = System.getProperty(MAX_CACHED_SECTIONS_PROPERTY);
        String previousViewInstances = System.getProperty(MAX_VIEW_INSTANCES_PROPERTY);
        String previousFarFieldProxy = System.getProperty(FAR_FIELD_PROXY_ENABLED_PROPERTY);
        try {
            System.clearProperty(MAX_CACHED_SECTIONS_PROPERTY);
            System.clearProperty(MAX_VIEW_INSTANCES_PROPERTY);
            System.clearProperty(FAR_FIELD_PROXY_ENABLED_PROPERTY);
            RtSectionBlasConfiguration configuration = RtSectionBlasConfiguration.fromSystemProperties();
            require(configuration.configuredMaxCachedSections() == 65_536,
                    "exact BLAS residency must not inherit the 4K CPU source-cache bound");
            require(configuration.maxViewInstances() == 65_536,
                    "active exact TLAS capacity must not inherit the 4K CPU source-cache bound");
            require(!configuration.farFieldProxyEnabled(),
                    "Minecraft parity must default to exact geometry rather than proxy fidelity");
        } finally {
            restoreProperty(MAX_CACHED_SECTIONS_PROPERTY, previousCachedSections);
            restoreProperty(MAX_VIEW_INSTANCES_PROPERTY, previousViewInstances);
            restoreProperty(FAR_FIELD_PROXY_ENABLED_PROPERTY, previousFarFieldProxy);
        }
    }

    private static void normalizesAsyncBatchFeedbackPerSection() {
        RtAdaptiveBuildBudget budget = new RtAdaptiveBuildBudget(
                8, 96_000L, 1, 12_000L, 3_000_000L, 7_000_000L
        );
        RtAdaptiveBuildBudget.Limits initial = budget.currentLimits();
        budget.recordBatch(64_000_000L, 16, true);
        require(budget.currentLimits().equals(initial),
                "aggregate duration of a healthy large batch must not halve per-section capacity");
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
        } catch (IllegalArgumentException expected) {
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
