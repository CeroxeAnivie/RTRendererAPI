package top.ceroxe.rt.renderer.scene;

/**
 * 场景 CPU 缓存、异步构建队列与常驻光追资源的统一容量预算。
 *
 * <p>所有限制都必须为正数。默认值可通过对应的 {@code top.ceroxe.rt.sceneCache.*}
 * 系统属性覆盖；非法覆盖值会安全回退到平台默认值。</p>
 *
 * @param materialBytes             CPU 材料缓存的最大字节数
 * @param geometryBytes             CPU 几何快照缓存的最大字节数
 * @param meshBytes                 CPU 三角网格缓存的最大字节数
 * @param maxCpuBuildsInFlight      同时执行的 CPU 网格构建上限
 * @param maxCompletedBuildsWaiting 已完成但尚未消费的构建结果上限
 * @param maxRtPendingMeshSections  等待上传或 BLAS 构建的区段上限
 * @param maxRtPendingMeshBytes     等待光追处理的网格字节数上限
 * @param maxRtCachedMeshSections   光追常驻网格区段上限
 * @param maxRtCachedBlasBytes      光追常驻 BLAS 字节数上限
 */
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
    private static final String MATERIAL_BYTES_PROPERTY = "top.ceroxe.rt.sceneCache.materialBytes";
    private static final String GEOMETRY_BYTES_PROPERTY = "top.ceroxe.rt.sceneCache.geometryBytes";
    private static final String MESH_BYTES_PROPERTY = "top.ceroxe.rt.sceneCache.meshBytes";
    private static final String MAX_CPU_BUILDS_IN_FLIGHT_PROPERTY = "top.ceroxe.rt.sceneCache.maxCpuBuildsInFlight";
    private static final String MAX_COMPLETED_BUILDS_WAITING_PROPERTY = "top.ceroxe.rt.sceneCache.maxCompletedBuildsWaiting";
    private static final String MAX_RT_PENDING_MESH_SECTIONS_PROPERTY = "top.ceroxe.rt.sceneCache.maxRtPendingMeshSections";
    private static final String MAX_RT_PENDING_MESH_BYTES_PROPERTY = "top.ceroxe.rt.sceneCache.maxRtPendingMeshBytes";
    private static final String MAX_RT_CACHED_MESH_SECTIONS_PROPERTY = "top.ceroxe.rt.sceneCache.maxRtCachedMeshSections";
    private static final String MAX_RT_CACHED_BLAS_BYTES_PROPERTY = "top.ceroxe.rt.sceneCache.maxRtCachedBlasBytes";

    /**
     * 根据可用堆内存和可选系统属性计算的默认预算。
     */
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

    /**
     * 使用兼容的队列与光追缓存默认值创建预算。
     *
     * @param materialBytes CPU 材料缓存的最大字节数
     * @param geometryBytes CPU 几何快照缓存的最大字节数
     * @param meshBytes     CPU 三角网格缓存的最大字节数
     */
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

    /**
     * 使用指定 CPU 与待处理光追限制创建预算，常驻光追缓存采用兼容默认值。
     *
     * @param materialBytes             CPU 材料缓存的最大字节数
     * @param geometryBytes             CPU 几何快照缓存的最大字节数
     * @param meshBytes                 CPU 三角网格缓存的最大字节数
     * @param maxCpuBuildsInFlight      同时执行的 CPU 网格构建上限
     * @param maxCompletedBuildsWaiting 已完成但尚未消费的构建结果上限
     * @param maxRtPendingMeshSections  等待光追处理的区段上限
     * @param maxRtPendingMeshBytes     等待光追处理的网格字节数上限
     */
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

    /**
     * Validates that every independently enforced budget is strictly positive.
     */
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
