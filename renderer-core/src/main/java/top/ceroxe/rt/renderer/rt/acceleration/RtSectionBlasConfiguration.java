package top.ceroxe.rt.renderer.rt.acceleration;

import top.ceroxe.rt.renderer.scene.RendererHeapBudget;
import top.ceroxe.rt.renderer.scene.SceneCacheBudget;

/**
 * Immutable, fully validated capacity contract for {@link RtSectionBlasCache}.
 *
 * <p>Every constructor path and system property converges here before scheduler resources are
 * allocated. Defaults, validation, adaptive limits, and effective queue width therefore cannot
 * drift independently from the values reported by diagnostics.</p>
 */
record RtSectionBlasConfiguration(
        int maxBuildsPerFrame,
        long maxTrianglesPerFrame,
        int configuredMaxAsyncBuildsInFlight,
        int maxAsyncBuildSectionsInFlight,
        long maxAsyncBuildBytesInFlight,
        int maxPendingSections,
        long maxPendingBytes,
        int configuredMaxCachedSections,
        long maxCachedBytes,
        long maxCachedSourceMeshBytes,
        int maxViewInstances,
        int maxFarFieldInstances,
        int viewInstanceRetentionMargin,
        boolean farFieldProxyEnabled
) {
    private static final int DEFAULT_MAX_BUILDS_PER_FRAME = 128;
    private static final long DEFAULT_MAX_TRIANGLES_PER_FRAME = 3_072_000L;
    private static final int DEFAULT_MAX_ASYNC_BUILDS_IN_FLIGHT = 12;
    /*
     * Async work retains the same section payload independently from the pending queue. Keep its
     * default section/byte window inside the renderer's pending-mesh budget; a larger implicit
     * window duplicates world staging until GPU completion and causes avoidable G1 pressure.
     */
    private static final int DEFAULT_MAX_ASYNC_BUILD_SECTIONS_IN_FLIGHT = 128;
    private static final long DEFAULT_MAX_ASYNC_BUILD_BYTES_IN_FLIGHT = 256L * 1024L * 1024L;
    /*
     * Exact GPU residency is deliberately independent from SceneDatabase's bounded dense-source
     * cache. A 32-chunk host application view can expose far more drawable sections than the 4K CPU
     * source window; sharing that value made completed BLAS resources churn and substituted
     * lossy far-field quads for real terrain.
     */
    private static final int DEFAULT_MAX_CACHED_EXACT_SECTIONS = 65_536;
    private static final int DEFAULT_MAX_EXACT_VIEW_INSTANCES = 65_536;
    private static final int DEFAULT_MAX_FAR_FIELD_INSTANCES = 256;
    private static final int DEFAULT_VIEW_INSTANCE_RETENTION_MARGIN = 32;
    private static final int MIN_ADAPTIVE_BUILDS_PER_FRAME = 1;
    private static final long MIN_ADAPTIVE_TRIANGLES_PER_FRAME = 12_000L;
    private static final long TARGET_BUILD_NANOS = 4_000_000L;
    private static final long HIGH_BUILD_NANOS = 8_000_000L;

    private static final String MAX_BUILDS_PER_FRAME_PROPERTY =
            "top.ceroxe.rt.rt.sectionBlas.maxBuildsPerFrame";
    private static final String MAX_TRIANGLES_PER_FRAME_PROPERTY =
            "top.ceroxe.rt.rt.sectionBlas.maxTrianglesPerFrame";
    private static final String MAX_ASYNC_BUILDS_IN_FLIGHT_PROPERTY =
            "top.ceroxe.rt.rt.sectionBlas.maxAsyncBuildsInFlight";
    private static final String MAX_ASYNC_BUILD_SECTIONS_IN_FLIGHT_PROPERTY =
            "top.ceroxe.rt.rt.sectionBlas.maxAsyncBuildSectionsInFlight";
    private static final String MAX_ASYNC_BUILD_BYTES_IN_FLIGHT_PROPERTY =
            "top.ceroxe.rt.rt.sectionBlas.maxAsyncBuildBytesInFlight";
    private static final String MAX_PENDING_SECTIONS_PROPERTY =
            "top.ceroxe.rt.rt.sectionBlas.maxPendingSections";
    private static final String MAX_PENDING_BYTES_PROPERTY =
            "top.ceroxe.rt.rt.sectionBlas.maxPendingBytes";
    private static final String MAX_CACHED_SECTIONS_PROPERTY =
            "top.ceroxe.rt.rt.sectionBlas.maxCachedSections";
    private static final String MAX_CACHED_BYTES_PROPERTY =
            "top.ceroxe.rt.rt.sectionBlas.maxCachedBytes";
    private static final String MAX_CACHED_SOURCE_MESH_BYTES_PROPERTY =
            "top.ceroxe.rt.rt.sectionBlas.maxCachedSourceMeshBytes";
    private static final String MAX_VIEW_INSTANCES_PROPERTY =
            "top.ceroxe.rt.rt.view.maxSectionInstances";
    private static final String MAX_FAR_FIELD_INSTANCES_PROPERTY =
            "top.ceroxe.rt.rt.view.maxFarFieldInstances";
    private static final String VIEW_INSTANCE_RETENTION_MARGIN_PROPERTY =
            "top.ceroxe.rt.rt.view.instanceRetentionMargin";
    private static final String FAR_FIELD_PROXY_ENABLED_PROPERTY =
            "top.ceroxe.rt.rt.farFieldProxy.enabled";

    RtSectionBlasConfiguration {
        requirePositive(maxBuildsPerFrame, "maxBuildsPerFrame");
        requirePositive(maxTrianglesPerFrame, "maxTrianglesPerFrame");
        requirePositive(configuredMaxAsyncBuildsInFlight, "configuredMaxAsyncBuildsInFlight");
        requirePositive(maxAsyncBuildSectionsInFlight, "maxAsyncBuildSectionsInFlight");
        requirePositive(maxAsyncBuildBytesInFlight, "maxAsyncBuildBytesInFlight");
        requirePositive(maxPendingSections, "maxPendingSections");
        requirePositive(maxPendingBytes, "maxPendingBytes");
        requirePositive(configuredMaxCachedSections, "configuredMaxCachedSections");
        requirePositive(maxCachedBytes, "maxCachedBytes");
        requirePositive(maxCachedSourceMeshBytes, "maxCachedSourceMeshBytes");
        requirePositive(maxViewInstances, "maxViewInstances");
        requirePositive(maxFarFieldInstances, "maxFarFieldInstances");
        requirePositive(viewInstanceRetentionMargin, "viewInstanceRetentionMargin");
    }

    static RtSectionBlasConfiguration fromSystemProperties() {
        return new RtSectionBlasConfiguration(
                positiveIntProperty(MAX_BUILDS_PER_FRAME_PROPERTY, DEFAULT_MAX_BUILDS_PER_FRAME),
                positiveLongProperty(MAX_TRIANGLES_PER_FRAME_PROPERTY, DEFAULT_MAX_TRIANGLES_PER_FRAME),
                positiveIntProperty(
                        MAX_ASYNC_BUILDS_IN_FLIGHT_PROPERTY,
                        RendererHeapBudget.defaultCount(DEFAULT_MAX_ASYNC_BUILDS_IN_FLIGHT, 4)
                ),
                positiveIntProperty(
                        MAX_ASYNC_BUILD_SECTIONS_IN_FLIGHT_PROPERTY,
                        RendererHeapBudget.defaultCount(DEFAULT_MAX_ASYNC_BUILD_SECTIONS_IN_FLIGHT, 32)
                ),
                positiveLongProperty(
                        MAX_ASYNC_BUILD_BYTES_IN_FLIGHT_PROPERTY,
                        RendererHeapBudget.defaultBytes(
                                DEFAULT_MAX_ASYNC_BUILD_BYTES_IN_FLIGHT,
                                64L * 1024L * 1024L
                        )
                ),
                positiveIntProperty(
                        MAX_PENDING_SECTIONS_PROPERTY,
                        SceneCacheBudget.DEFAULT.maxRtPendingMeshSections()
                ),
                positiveLongProperty(MAX_PENDING_BYTES_PROPERTY, SceneCacheBudget.DEFAULT.maxRtPendingMeshBytes()),
                positiveIntProperty(
                        MAX_CACHED_SECTIONS_PROPERTY,
                        DEFAULT_MAX_CACHED_EXACT_SECTIONS
                ),
                positiveLongProperty(MAX_CACHED_BYTES_PROPERTY, SceneCacheBudget.DEFAULT.maxRtCachedBlasBytes()),
                positiveLongProperty(MAX_CACHED_SOURCE_MESH_BYTES_PROPERTY, SceneCacheBudget.DEFAULT.meshBytes()),
                positiveIntProperty(MAX_VIEW_INSTANCES_PROPERTY, DEFAULT_MAX_EXACT_VIEW_INSTANCES),
                positiveIntProperty(MAX_FAR_FIELD_INSTANCES_PROPERTY, DEFAULT_MAX_FAR_FIELD_INSTANCES),
                positiveIntProperty(
                        VIEW_INSTANCE_RETENTION_MARGIN_PROPERTY,
                        DEFAULT_VIEW_INSTANCE_RETENTION_MARGIN
                ),
                Boolean.parseBoolean(System.getProperty(FAR_FIELD_PROXY_ENABLED_PROPERTY, "false"))
        );
    }

    static RtSectionBlasConfiguration explicit(
            int maxBuildsPerFrame,
            long maxTrianglesPerFrame,
            int maxPendingSections,
            long maxPendingBytes,
            int maxCachedSections,
            long maxCachedBytes
    ) {
        return explicit(
                maxBuildsPerFrame,
                maxTrianglesPerFrame,
                DEFAULT_MAX_ASYNC_BUILDS_IN_FLIGHT,
                DEFAULT_MAX_ASYNC_BUILD_SECTIONS_IN_FLIGHT,
                DEFAULT_MAX_ASYNC_BUILD_BYTES_IN_FLIGHT,
                maxPendingSections,
                maxPendingBytes,
                maxCachedSections,
                maxCachedBytes
        );
    }

    static RtSectionBlasConfiguration explicit(
            int maxBuildsPerFrame,
            long maxTrianglesPerFrame,
            int maxAsyncBuildsInFlight,
            int maxAsyncBuildSectionsInFlight,
            long maxAsyncBuildBytesInFlight,
            int maxPendingSections,
            long maxPendingBytes,
            int maxCachedSections,
            long maxCachedBytes
    ) {
        return new RtSectionBlasConfiguration(
                maxBuildsPerFrame,
                maxTrianglesPerFrame,
                maxAsyncBuildsInFlight,
                maxAsyncBuildSectionsInFlight,
                maxAsyncBuildBytesInFlight,
                maxPendingSections,
                maxPendingBytes,
                maxCachedSections,
                maxCachedBytes,
                positiveLongProperty(MAX_CACHED_SOURCE_MESH_BYTES_PROPERTY, SceneCacheBudget.DEFAULT.meshBytes()),
                positiveIntProperty(MAX_VIEW_INSTANCES_PROPERTY, DEFAULT_MAX_EXACT_VIEW_INSTANCES),
                positiveIntProperty(MAX_FAR_FIELD_INSTANCES_PROPERTY, DEFAULT_MAX_FAR_FIELD_INSTANCES),
                positiveIntProperty(
                        VIEW_INSTANCE_RETENTION_MARGIN_PROPERTY,
                        DEFAULT_VIEW_INSTANCE_RETENTION_MARGIN
                ),
                Boolean.parseBoolean(System.getProperty(FAR_FIELD_PROXY_ENABLED_PROPERTY, "false"))
        );
    }

    private static int positiveIntProperty(String name, int defaultValue) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static long positiveLongProperty(String name, long defaultValue) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0L ? parsed : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0L) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    int effectiveMaxAsyncBuildsInFlight(int orderedQueueCount) {
        return RtSectionBlasAdmissionPlanner.effectiveSubmissionWindow(
                configuredMaxAsyncBuildsInFlight,
                orderedQueueCount
        );
    }

    int gpuSubmissionWindow(int orderedQueueCount) {
        return Math.min(
                configuredMaxAsyncBuildsInFlight,
                RtSectionBlasAdmissionPlanner.gpuSubmissionWindow(orderedQueueCount)
        );
    }

    RtAdaptiveBuildBudget adaptiveBuildBudget() {
        return new RtAdaptiveBuildBudget(
                maxBuildsPerFrame,
                maxTrianglesPerFrame,
                MIN_ADAPTIVE_BUILDS_PER_FRAME,
                Math.min(maxTrianglesPerFrame, MIN_ADAPTIVE_TRIANGLES_PER_FRAME),
                TARGET_BUILD_NANOS,
                HIGH_BUILD_NANOS
        );
    }
}
