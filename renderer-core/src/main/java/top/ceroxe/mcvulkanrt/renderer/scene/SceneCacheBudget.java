package top.ceroxe.mcvulkanrt.renderer.scene;

public record SceneCacheBudget(
        long materialBytes,
        long geometryBytes,
        long meshBytes,
        int maxCpuBuildsInFlight,
        int maxCompletedBuildsWaiting,
        int maxRtPendingMeshSections,
        long maxRtPendingMeshBytes,
        int maxRtCachedMeshSections,
        long maxRtCachedBlasBytes
) {
    private static final long MEBIBYTE = 1024L * 1024L;
    private static final String MATERIAL_BYTES_PROPERTY = "mcvulkanrt.sceneCache.materialBytes";
    private static final String GEOMETRY_BYTES_PROPERTY = "mcvulkanrt.sceneCache.geometryBytes";
    private static final String MESH_BYTES_PROPERTY = "mcvulkanrt.sceneCache.meshBytes";
    private static final String MAX_CPU_BUILDS_IN_FLIGHT_PROPERTY = "mcvulkanrt.sceneCache.maxCpuBuildsInFlight";
    private static final String MAX_COMPLETED_BUILDS_WAITING_PROPERTY = "mcvulkanrt.sceneCache.maxCompletedBuildsWaiting";
    private static final String MAX_RT_PENDING_MESH_SECTIONS_PROPERTY = "mcvulkanrt.sceneCache.maxRtPendingMeshSections";
    private static final String MAX_RT_PENDING_MESH_BYTES_PROPERTY = "mcvulkanrt.sceneCache.maxRtPendingMeshBytes";
    private static final String MAX_RT_CACHED_MESH_SECTIONS_PROPERTY = "mcvulkanrt.sceneCache.maxRtCachedMeshSections";
    private static final String MAX_RT_CACHED_BLAS_BYTES_PROPERTY = "mcvulkanrt.sceneCache.maxRtCachedBlasBytes";

    public static final SceneCacheBudget DEFAULT = new SceneCacheBudget(
            positiveLongProperty(
                    MATERIAL_BYTES_PROPERTY,
                    RendererHeapBudget.defaultBytes(64L * MEBIBYTE, 16L * MEBIBYTE)
            ),
            positiveLongProperty(
                    GEOMETRY_BYTES_PROPERTY,
                    RendererHeapBudget.defaultBytes(128L * MEBIBYTE, 32L * MEBIBYTE)
            ),
            positiveLongProperty(
                    MESH_BYTES_PROPERTY,
                    RendererHeapBudget.defaultBytes(256L * MEBIBYTE, 64L * MEBIBYTE)
            ),
            positiveIntProperty(
                    MAX_CPU_BUILDS_IN_FLIGHT_PROPERTY,
                    RendererHeapBudget.defaultCount(96, 24)
            ),
            positiveIntProperty(
                    MAX_COMPLETED_BUILDS_WAITING_PROPERTY,
                    RendererHeapBudget.defaultCount(64, 16)
            ),
            positiveIntProperty(
                    MAX_RT_PENDING_MESH_SECTIONS_PROPERTY,
                    RendererHeapBudget.defaultCount(512, 128)
            ),
            positiveLongProperty(
                    MAX_RT_PENDING_MESH_BYTES_PROPERTY,
                    RendererHeapBudget.defaultBytes(256L * MEBIBYTE, 64L * MEBIBYTE)
            ),
            /* A 32-chunk frustum has several thousand vertical sections. */
            positiveIntProperty(MAX_RT_CACHED_MESH_SECTIONS_PROPERTY, 4096),
            positiveLongProperty(MAX_RT_CACHED_BLAS_BYTES_PROPERTY, 1536L * MEBIBYTE)
    );

    public SceneCacheBudget(long materialBytes, long geometryBytes, long meshBytes) {
        this(
                materialBytes,
                geometryBytes,
                meshBytes,
                64,
                32,
                96,
                meshBytes
        );
    }

    public SceneCacheBudget(
            long materialBytes,
            long geometryBytes,
            long meshBytes,
            int maxCpuBuildsInFlight,
            int maxCompletedBuildsWaiting,
            int maxRtPendingMeshSections,
            long maxRtPendingMeshBytes
    ) {
        this(
                materialBytes,
                geometryBytes,
                meshBytes,
                maxCpuBuildsInFlight,
                maxCompletedBuildsWaiting,
                maxRtPendingMeshSections,
                maxRtPendingMeshBytes,
                8192,
                Math.max(meshBytes, 1024L * MEBIBYTE)
        );
    }

    public SceneCacheBudget {
        requirePositive(materialBytes, "materialBytes");
        requirePositive(geometryBytes, "geometryBytes");
        requirePositive(meshBytes, "meshBytes");
        requirePositive(maxCpuBuildsInFlight, "maxCpuBuildsInFlight");
        requirePositive(maxCompletedBuildsWaiting, "maxCompletedBuildsWaiting");
        requirePositive(maxRtPendingMeshSections, "maxRtPendingMeshSections");
        requirePositive(maxRtPendingMeshBytes, "maxRtPendingMeshBytes");
        requirePositive(maxRtCachedMeshSections, "maxRtCachedMeshSections");
        requirePositive(maxRtCachedBlasBytes, "maxRtCachedBlasBytes");
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0L) {
            throw new IllegalArgumentException(name + " must be positive: " + value);
        }
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
}
