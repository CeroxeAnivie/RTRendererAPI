package top.ceroxe.rt.renderer;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Immutable non-terrain render state captured at the renderer integration boundary.
 *
 * <p>Persistent sections are only one input stream. Dynamic primitives, particles, beams,
 * celestial quads, and scene lighting follow separate lifetimes. This value is the
 * renderer-owned handoff point for those streams: no host object, mutable collection, or native
 * graphics handle may cross this boundary.</p>
 *
 * @param revision         monotonically increasing dynamic-scene revision
 * @param primitives       immutable dynamic primitives
 * @param particles        immutable billboard particles
 * @param beams            immutable beam primitives
 * @param celestialBodies  immutable celestial bodies
 * @param lights           immutable analytic lights
 * @param weatherColumns   immutable weather columns
 * @param environmentState immutable environment state
 * @param lightmapPayload  immutable lightmap payload
 * @param modelInstances   immutable dynamic model instances
 * @param blockDecals      immutable sparse surface decals
 * @param modelFrameDelta  immutable model lifecycle delta
 */
public record DynamicRenderScene(
        long revision,
        List<DynamicPrimitive> primitives,
        List<BillboardParticle> particles,
        List<Beam> beams,
        List<CelestialBody> celestialBodies,
        List<SceneLight> lights,
        List<WeatherColumn> weatherColumns,
        EnvironmentState environmentState,
        LightmapPayload lightmapPayload,
        List<DynamicModelInstance> modelInstances,
        List<BlockDecal> blockDecals,
        DynamicModelFrameDelta modelFrameDelta
) {
    /**
     * GPU 场景允许的最大天体数量。
     */
    public static final int MAX_GPU_CELESTIAL_BODIES = 8;
    /**
     * GPU 场景允许的最大通用动态图元数量。
     */
    public static final int MAX_GPU_PRIMITIVES = 64;
    /**
     * 动态 TLAS 允许的最大模型图元数量。
     */
    public static final int MAX_TLAS_MODEL_PRIMITIVES = 16_384;
    /**
     * GPU 场景允许的最大粒子数量。
     */
    public static final int MAX_GPU_PARTICLES = 256;
    /**
     * GPU 场景允许的最大光束数量。
     */
    public static final int MAX_GPU_BEAMS = 32;
    /**
     * GPU 场景允许的最大解析光源数量。
     */
    public static final int MAX_GPU_LIGHTS = 64;
    /**
     * GPU 场景允许的最大天气柱数量。
     */
    public static final int MAX_GPU_WEATHER_COLUMNS = 256;
    /**
     * GPU 场景允许的最大稀疏区块贴花数量。
     */
    public static final int MAX_GPU_BLOCK_DECALS = 64;

    private static final DynamicRenderScene EMPTY = new DynamicRenderScene(
            0L,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            EnvironmentState.unknown(),
            LightmapPayload.unknown(),
            List.of(),
            List.of()
    );

    /**
     * 校验、规范化并冻结完整动态场景发布。
     */
    public DynamicRenderScene {
        if (revision < 0L) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        primitives = List.copyOf(primitives == null ? List.of() : primitives);
        particles = List.copyOf(particles == null ? List.of() : particles);
        beams = List.copyOf(beams == null ? List.of() : beams);
        celestialBodies = List.copyOf(celestialBodies == null ? List.of() : celestialBodies);
        lights = List.copyOf(lights == null ? List.of() : lights);
        weatherColumns = List.copyOf(weatherColumns == null ? List.of() : weatherColumns);
        environmentState = environmentState == null ? EnvironmentState.unknown() : environmentState;
        lightmapPayload = lightmapPayload == null ? LightmapPayload.unknown() : lightmapPayload;
        modelInstances = List.copyOf(modelInstances == null ? List.of() : modelInstances);
        blockDecals = List.copyOf(blockDecals == null ? List.of() : blockDecals);
        modelFrameDelta = modelFrameDelta == null ? DynamicModelFrameDelta.none() : modelFrameDelta;
        if (revision == 0L && (totalElements(
                primitives,
                particles,
                beams,
                celestialBodies,
                lights,
                weatherColumns,
                environmentState,
                modelInstances,
                blockDecals
        ) > 0 || modelFrameDelta.activeSlotCount() > 0 || modelFrameDelta.hasUpdates())) {
            throw new IllegalArgumentException("non-empty dynamic scene requires a positive revision");
        }
    }

    /**
     * 创建不包含模型帧增量的完整动态场景。
     *
     * @param revision         动态场景修订号
     * @param primitives       动态图元
     * @param particles        公告板粒子
     * @param beams            光束图元
     * @param celestialBodies  天体
     * @param lights           解析光源
     * @param weatherColumns   天气柱
     * @param environmentState 环境状态
     * @param lightmapPayload  光照贴图负载
     * @param modelInstances   动态模型实例
     * @param blockDecals      稀疏区块贴花
     */
    public DynamicRenderScene(
            long revision,
            List<DynamicPrimitive> primitives,
            List<BillboardParticle> particles,
            List<Beam> beams,
            List<CelestialBody> celestialBodies,
            List<SceneLight> lights,
            List<WeatherColumn> weatherColumns,
            EnvironmentState environmentState,
            LightmapPayload lightmapPayload,
            List<DynamicModelInstance> modelInstances,
            List<BlockDecal> blockDecals
    ) {
        this(
                revision, primitives, particles, beams, celestialBodies, lights, weatherColumns,
                environmentState, lightmapPayload, modelInstances, blockDecals, DynamicModelFrameDelta.none()
        );
    }

    /**
     * 创建不包含贴花和模型帧增量的动态场景。
     *
     * @param revision         动态场景修订号
     * @param primitives       动态图元
     * @param particles        公告板粒子
     * @param beams            光束图元
     * @param celestialBodies  天体
     * @param lights           解析光源
     * @param weatherColumns   天气柱
     * @param environmentState 环境状态
     * @param lightmapPayload  光照贴图负载
     * @param modelInstances   动态模型实例
     */
    public DynamicRenderScene(
            long revision,
            List<DynamicPrimitive> primitives,
            List<BillboardParticle> particles,
            List<Beam> beams,
            List<CelestialBody> celestialBodies,
            List<SceneLight> lights,
            List<WeatherColumn> weatherColumns,
            EnvironmentState environmentState,
            LightmapPayload lightmapPayload,
            List<DynamicModelInstance> modelInstances
    ) {
        this(
                revision,
                primitives,
                particles,
                beams,
                celestialBodies,
                lights,
                weatherColumns,
                environmentState,
                lightmapPayload,
                modelInstances,
                List.of()
        );
    }

    /**
     * Host-compatible constructor for non-model dynamic scene producers.
     * Model capture uses the dedicated stream below so it never has to build a
     * generic DynamicPrimitive plus a nested DynamicMeshInstance per cube.
     *
     * @param revision         动态场景修订号
     * @param primitives       动态图元
     * @param particles        公告板粒子
     * @param beams            光束图元
     * @param celestialBodies  天体
     * @param lights           解析光源
     * @param weatherColumns   天气柱
     * @param environmentState 环境状态
     * @param lightmapPayload  光照贴图负载
     */
    public DynamicRenderScene(
            long revision,
            List<DynamicPrimitive> primitives,
            List<BillboardParticle> particles,
            List<Beam> beams,
            List<CelestialBody> celestialBodies,
            List<SceneLight> lights,
            List<WeatherColumn> weatherColumns,
            EnvironmentState environmentState,
            LightmapPayload lightmapPayload
    ) {
        this(
                revision,
                primitives,
                particles,
                beams,
                celestialBodies,
                lights,
                weatherColumns,
                environmentState,
                lightmapPayload,
                List.of(),
                List.of()
        );
    }

    /**
     * 创建只包含基础动态流的场景。
     *
     * @param revision        动态场景修订号
     * @param primitives      动态图元
     * @param particles       公告板粒子
     * @param beams           光束图元
     * @param celestialBodies 天体
     * @param lights          解析光源
     */
    public DynamicRenderScene(
            long revision,
            List<DynamicPrimitive> primitives,
            List<BillboardParticle> particles,
            List<Beam> beams,
            List<CelestialBody> celestialBodies,
            List<SceneLight> lights
    ) {
        this(
                revision,
                primitives,
                particles,
                beams,
                celestialBodies,
                lights,
                List.of(),
                EnvironmentState.unknown(),
                LightmapPayload.unknown(),
                List.of(),
                List.of()
        );
    }

    /**
     * 创建基础动态流及显式光照贴图负载。
     *
     * @param revision        动态场景修订号
     * @param primitives      动态图元
     * @param particles       公告板粒子
     * @param beams           光束图元
     * @param celestialBodies 天体
     * @param lights          解析光源
     * @param lightmapPayload 光照贴图负载
     */
    public DynamicRenderScene(
            long revision,
            List<DynamicPrimitive> primitives,
            List<BillboardParticle> particles,
            List<Beam> beams,
            List<CelestialBody> celestialBodies,
            List<SceneLight> lights,
            LightmapPayload lightmapPayload
    ) {
        this(
                revision,
                primitives,
                particles,
                beams,
                celestialBodies,
                lights,
                List.of(),
                EnvironmentState.unknown(),
                lightmapPayload,
                List.of(),
                List.of()
        );
    }

    /**
     * 创建基础动态流及显式天气与环境状态。
     *
     * @param revision         动态场景修订号
     * @param primitives       动态图元
     * @param particles        公告板粒子
     * @param beams            光束图元
     * @param celestialBodies  天体
     * @param lights           解析光源
     * @param weatherColumns   天气柱
     * @param environmentState 环境状态
     */
    public DynamicRenderScene(
            long revision,
            List<DynamicPrimitive> primitives,
            List<BillboardParticle> particles,
            List<Beam> beams,
            List<CelestialBody> celestialBodies,
            List<SceneLight> lights,
            List<WeatherColumn> weatherColumns,
            EnvironmentState environmentState
    ) {
        this(
                revision,
                primitives,
                particles,
                beams,
                celestialBodies,
                lights,
                weatherColumns,
                environmentState,
                LightmapPayload.unknown(),
                List.of(),
                List.of()
        );
    }

    /**
     * 返回修订号为零的共享空动态场景。
     *
     * @return 空动态场景
     */
    public static DynamicRenderScene empty() {
        return EMPTY;
    }

    private static int totalElements(
            List<?> primitives,
            List<?> particles,
            List<?> beams,
            List<?> celestialBodies,
            List<?> lights,
            List<?> weatherColumns,
            EnvironmentState environmentState,
            List<?> modelInstances,
            List<?> blockDecals
    ) {
        return primitives.size()
                + particles.size()
                + beams.size()
                + celestialBodies.size()
                + lights.size()
                + weatherColumns.size()
                + modelInstances.size()
                + blockDecals.size()
                + (environmentState != null && environmentState.hasRenderContent() ? 1 : 0);
    }

    private static boolean environmentRenderPayloadEquals(
            EnvironmentState left,
            EnvironmentState right
    ) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        /*
         * This predicate is the CPU-side contract for persistent dynamic-scene
         * uploads. Environment clock and fog alpha are frame constants, not
         * scene ownership: placing them in the dynamic SSBO would advance the
         * descriptor/TLAS generation on every rendered frame even when no
         * primitive, material, or transform changed.
         */
        return left.fogKnown() == right.fogKnown()
                && Float.compare(left.fogRed(), right.fogRed()) == 0
                && Float.compare(left.fogGreen(), right.fogGreen()) == 0
                && Float.compare(left.fogBlue(), right.fogBlue()) == 0
                && Float.compare(left.environmentalStart(), right.environmentalStart()) == 0
                && Float.compare(left.environmentalEnd(), right.environmentalEnd()) == 0
                && Float.compare(left.renderDistanceStart(), right.renderDistanceStart()) == 0
                && Float.compare(left.renderDistanceEnd(), right.renderDistanceEnd()) == 0
                && Float.compare(left.skyEnd(), right.skyEnd()) == 0
                && Float.compare(left.cloudEnd(), right.cloudEnd()) == 0
                && left.cloudKnown() == right.cloudKnown()
                && left.cloudRgba8() == right.cloudRgba8()
                && Float.compare(left.cloudHeight(), right.cloudHeight()) == 0
                && left.cloudRange() == right.cloudRange()
                && left.cloudStatus() == right.cloudStatus()
                && left.skyVisible() == right.skyVisible();
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    private static void requirePositive(float value, String name) {
        requireFinite(value, name);
        if (value <= 0.0F) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static float finiteDistance(float value, String name) {
        requireFinite(value, name);
        return Math.max(0.0F, Math.min(value, 1_048_576.0F));
    }

    private static float clampColor(float value, String name) {
        requireFinite(value, name);
        return Math.max(0.0F, Math.min(value, 4.0F));
    }

    private static float clamp01Finite(float value, String name) {
        requireFinite(value, name);
        return clamp01(value);
    }

    private static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    private static void requireUnitVector(float x, float y, float z, String name) {
        requireFinite(x, name + ".x");
        requireFinite(y, name + ".y");
        requireFinite(z, name + ".z");
        float lengthSquared = x * x + y * y + z * z;
        if (Math.abs(lengthSquared - 1.0F) > 1.0e-3F) {
            throw new IllegalArgumentException(name + " must be normalized");
        }
    }

    private static String sanitizeDebugName(String debugName) {
        if (debugName == null || debugName.isBlank()) {
            return "";
        }
        return debugName.length() <= 96 ? debugName : debugName.substring(0, 96);
    }

    /**
     * 判断场景是否包含任何可渲染动态内容。
     *
     * @return 至少包含一个动态元素时返回 {@code true}
     */
    public boolean hasRenderContent() {
        return totalElements() > 0;
    }

    /**
     * 判断场景是否包含需要进入 TLAS 的几何内容。
     *
     * @return 存在模型或几何图元时返回 {@code true}
     */
    public boolean hasTlasGeometryContent() {
        for (DynamicPrimitive primitive : primitives) {
            if (primitive.usesTlasGeometry()) {
                return true;
            }
        }
        return !modelInstances.isEmpty() || modelFrameDelta.activeSlotCount() > 0;
    }

    /**
     * 判断发布是否携带可观察的动态场景更新。
     *
     * @return 修订、内容或光照贴图已知时返回 {@code true}
     */
    public boolean hasSceneUpdate() {
        return revision > 0L || hasRenderContent() || lightmapPayload.known();
    }

    /**
     * 比较忽略发布修订号后的完整渲染负载。
     *
     * @param other 另一份动态场景
     * @return 渲染负载完全相同时返回 {@code true}
     */
    public boolean hasSameRenderPayload(DynamicRenderScene other) {
        if (other == null) {
            return false;
        }
        return primitives.equals(other.primitives)
                && particles.equals(other.particles)
                && beams.equals(other.beams)
                && celestialBodies.equals(other.celestialBodies)
                && lights.equals(other.lights)
                && weatherColumns.equals(other.weatherColumns)
                && environmentRenderPayloadEquals(environmentState, other.environmentState)
                && lightmapPayload.equals(other.lightmapPayload)
                && modelInstances.equals(other.modelInstances)
                && modelFrameDelta.equals(other.modelFrameDelta)
                && blockDecals.equals(other.blockDecals);
    }

    /**
     * 统计场景中全部动态元素。
     *
     * @return 动态元素总数
     */
    public int totalElements() {
        int listElements = totalElements(
                primitives,
                particles,
                beams,
                celestialBodies,
                lights,
                weatherColumns,
                environmentState,
                modelInstances,
                blockDecals
        );
        return listElements + Math.max(0, modelFrameDelta.activeSlotCount() - modelInstances.size());
    }

    /**
     * 将动态场景计数与环境状态格式化为诊断字段。
     *
     * @return 单行诊断摘要
     */
    public String asLogFragment() {
        return "dynamicScene{revision=" + revision
                + ", primitives=" + primitives.size()
                + ", tlasGeometryPrimitives=" + tlasGeometryPrimitiveCount()
                + ", modelInstances=" + Math.max(modelInstances.size(), modelFrameDelta.activeSlotCount())
                + ", modelDeltaUpdates=" + modelFrameDelta.updateCount()
                + ", particles=" + particles.size()
                + ", beams=" + beams.size()
                + ", celestialBodies=" + celestialBodies.size()
                + ", lights=" + lights.size()
                + ", weatherColumns=" + weatherColumns.size()
                + ", blockDecals=" + blockDecals.size()
                + ", environment=" + environmentState.asLogFragment()
                + ", totalElements=" + totalElements()
                + ", " + lightmapPayload.asLogFragment()
                + "}";
    }

    private int tlasGeometryPrimitiveCount() {
        int count = 0;
        for (DynamicPrimitive primitive : primitives) {
            if (primitive.usesTlasGeometry()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 返回仅替换光照贴图负载的场景副本。
     *
     * @param nextLightmapPayload 新负载；{@code null} 表示未知
     * @return 负载未变化时返回当前对象，否则返回新场景
     */
    public DynamicRenderScene withLightmapPayload(LightmapPayload nextLightmapPayload) {
        LightmapPayload effectiveLightmapPayload =
                nextLightmapPayload == null ? LightmapPayload.unknown() : nextLightmapPayload;
        if (lightmapPayload.equals(effectiveLightmapPayload)) {
            return this;
        }
        return new DynamicRenderScene(
                revision,
                primitives,
                particles,
                beams,
                celestialBodies,
                lights,
                weatherColumns,
                environmentState,
                effectiveLightmapPayload,
                modelInstances,
                blockDecals,
                modelFrameDelta
        );
    }

    DynamicRenderScene withRevision(long nextRevision) {
        if (revision == nextRevision) {
            return this;
        }
        return new DynamicRenderScene(
                nextRevision,
                primitives,
                particles,
                beams,
                celestialBodies,
                lights,
                weatherColumns,
                environmentState,
                lightmapPayload,
                modelInstances,
                blockDecals,
                modelFrameDelta
        );
    }

    /**
     * 动态图元的所有者语义。
     */
    public enum PrimitiveKind {
        /**
         * 场景实体。
         */
        ENTITY,
        /**
         * 掉落或展示物品。
         */
        DROPPED_ITEM,
        /**
         * 具有独立生命周期的场景对象。
         */
        BLOCK_ENTITY,
        /**
         * 第一人称手部或工具。
         */
        HAND_OR_TOOL
    }

    /**
     * 动态图元使用的几何表示。
     */
    public enum PrimitiveGeometryKind {
        /**
         * 显式三角模型。
         */
        MODEL,
        /**
         * 面向相机的单平面。
         */
        BILLBOARD,
        /**
         * 两个相交平面。
         */
        CROSS_PLANE,
        /**
         * 远距离替代几何。
         */
        IMPOSTOR
    }

    /**
     * 粒子的透明度与混合语义。
     */
    public enum ParticleKind {
        /**
         * 完全不透明公告板。
         */
        OPAQUE_BILLBOARD,
        /**
         * 使用透明度裁剪的公告板。
         */
        CUTOUT_BILLBOARD,
        /**
         * 使用常规透明混合的公告板。
         */
        TRANSLUCENT_BILLBOARD,
        /**
         * 使用加法混合的公告板。
         */
        ADDITIVE_BILLBOARD
    }

    /**
     * 天气柱的视觉类型。
     */
    public enum WeatherKind {
        /**
         * 雨柱。
         */
        RAIN,
        /**
         * 雪柱。
         */
        SNOW
    }

    /**
     * 光束图元的视觉语义。
     */
    public enum BeamKind {
        /**
         * 信标类垂直光束。
         */
        BEACON,
        /**
         * 水晶连接光束。
         */
        END_CRYSTAL,
        /**
         * 大型生物连接光束。
         */
        DRAGON,
        /**
         * 通用发光线段。
         */
        GENERIC
    }

    /**
     * 天体发光盘的类型。
     */
    public enum CelestialKind {
        /**
         * 太阳。
         */
        SUN,
        /**
         * 月亮。
         */
        MOON,
        /**
         * 星空层。
         */
        STARS,
        /**
         * 通用天空盘。
         */
        SKY_DISC
    }

    /**
     * 解析光源的物理或视觉来源。
     */
    public enum LightKind {
        /**
         * 太阳方向光。
         */
        SUN,
        /**
         * 月亮方向光。
         */
        MOON,
        /**
         * 天空环境光。
         */
        SKY,
        /**
         * 静态场景自发光。
         */
        BLOCK_EMISSION,
        /**
         * 动态实体自发光。
         */
        ENTITY_EMISSION,
        /**
         * 光束自发光。
         */
        BEAM_EMISSION
    }

    /**
     * Split-lane producer view of one persistent model slot.
     *
     * <p>Capture owns topology/material/light facts separately from the twelve transform
     * components. Implementations may be persistent mutable staging slots consumed synchronously
     * at the renderer boundary; consumers must copy only the lanes they own and must not retain a
     * non-{@link DynamicModelInstance} implementation.</p>
     */
    public interface DynamicModelObservation {
        /**
         * 返回稳定模型标识。
         *
         * @return 稳定模型标识
         */
        long id();

        /**
         * 返回模型所有者语义。
         *
         * @return 图元类型
         */
        PrimitiveKind kind();

        /**
         * 返回共享网格资产。
         *
         * @return 不可变网格资产
         */
        DynamicMeshAsset asset();

        /**
         * 返回不可变面材料。
         *
         * @return 面材料列表
         */
        List<DynamicMeshInstance.FaceMaterial> faceMaterials();

        /**
         * 返回实例深度通道。
         *
         * @return 渲染通道
         */
        DynamicRenderLane renderLane();

        /**
         * 返回打包光照。
         *
         * @return 打包光照值
         */
        int packedLight();

        /**
         * 返回中性诊断名称。
         *
         * @return 诊断名称
         */
        String debugName();

        /**
         * 返回对象到世界变换的一个分量。
         *
         * @param component 0..11 分量索引
         * @return 对应变换分量
         */
        float transformValue(int component);

        /**
         * 将临时生产者视图冻结为持久模型实例。
         *
         * @return 可由消费者安全保留的不可变实例
         */
        default DynamicModelInstance materialize() {
            if (this instanceof DynamicModelInstance instance) {
                return instance;
            }
            return new DynamicModelInstance(
                    id(),
                    kind(),
                    asset(),
                    new DynamicMeshInstance.AffineTransform(
                            transformValue(0), transformValue(1), transformValue(2), transformValue(3),
                            transformValue(4), transformValue(5), transformValue(6), transformValue(7),
                            transformValue(8), transformValue(9), transformValue(10), transformValue(11)
                    ),
                    faceMaterials(),
                    packedLight(),
                    debugName(),
                    renderLane()
            );
        }
    }

    /**
     * 一个具有稳定身份、空间包围盒和可选三角网格的动态图元。
     *
     * @param id           稳定图元标识
     * @param kind         所有者语义
     * @param geometryKind 几何表示
     * @param x            世界空间 X
     * @param y            世界空间 Y
     * @param z            世界空间 Z
     * @param yaw          偏航角
     * @param pitch        俯仰角
     * @param roll         翻滚角
     * @param radiusX      X 轴正半径
     * @param radiusY      Y 轴正半径
     * @param radiusZ      Z 轴正半径
     * @param materialKey  材料键
     * @param textureKey   纹理键
     * @param packedLight  打包光照
     * @param castsShadow  是否投射阴影
     * @param debugName    中性诊断名称
     * @param meshInstance 可选的显式三角网格实例
     */
    public record DynamicPrimitive(
            long id,
            PrimitiveKind kind,
            PrimitiveGeometryKind geometryKind,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            float roll,
            float radiusX,
            float radiusY,
            float radiusZ,
            int materialKey,
            int textureKey,
            int packedLight,
            boolean castsShadow,
            String debugName,
            DynamicMeshInstance meshInstance
    ) {
        /**
         * 校验、规范化并冻结动态图元。
         */
        public DynamicPrimitive {
            requireNonNegative(id, "id");
            requireFinite(x, "x");
            requireFinite(y, "y");
            requireFinite(z, "z");
            requireFinite(yaw, "yaw");
            requireFinite(pitch, "pitch");
            requireFinite(roll, "roll");
            requirePositive(radiusX, "radiusX");
            requirePositive(radiusY, "radiusY");
            requirePositive(radiusZ, "radiusZ");
            kind = kind == null ? PrimitiveKind.ENTITY : kind;
            geometryKind = geometryKind == null ? PrimitiveGeometryKind.MODEL : geometryKind;
            debugName = sanitizeDebugName(debugName);
            if (meshInstance != null && geometryKind != PrimitiveGeometryKind.MODEL) {
                throw new IllegalArgumentException("dynamic mesh instances must use MODEL geometry");
            }
        }

        /**
         * 创建不携带显式网格实例的轴向包围动态图元。
         *
         * @param id           稳定图元标识
         * @param kind         所有者语义
         * @param geometryKind 几何表示
         * @param x            世界空间 X
         * @param y            世界空间 Y
         * @param z            世界空间 Z
         * @param yaw          偏航角
         * @param pitch        俯仰角
         * @param roll         翻滚角
         * @param radiusX      X 轴正半径
         * @param radiusY      Y 轴正半径
         * @param radiusZ      Z 轴正半径
         * @param materialKey  材料键
         * @param textureKey   纹理键
         * @param packedLight  打包光照
         * @param castsShadow  是否投射阴影
         * @param debugName    中性诊断名称
         */
        public DynamicPrimitive(
                long id,
                PrimitiveKind kind,
                PrimitiveGeometryKind geometryKind,
                double x,
                double y,
                double z,
                float yaw,
                float pitch,
                float roll,
                float radiusX,
                float radiusY,
                float radiusZ,
                int materialKey,
                int textureKey,
                int packedLight,
                boolean castsShadow,
                String debugName
        ) {
            this(
                    id,
                    kind,
                    geometryKind,
                    x,
                    y,
                    z,
                    yaw,
                    pitch,
                    roll,
                    radiusX,
                    radiusY,
                    radiusZ,
                    materialKey,
                    textureKey,
                    packedLight,
                    castsShadow,
                    debugName,
                    null
            );
        }

        /**
         * 创建使用统一半径且不携带显式网格实例的动态图元。
         *
         * @param id           稳定图元标识
         * @param kind         所有者语义
         * @param geometryKind 几何表示
         * @param x            世界空间 X
         * @param y            世界空间 Y
         * @param z            世界空间 Z
         * @param yaw          偏航角
         * @param pitch        俯仰角
         * @param roll         翻滚角
         * @param radius       三个轴共用的正半径
         * @param materialKey  材料键
         * @param textureKey   纹理键
         * @param packedLight  打包光照
         * @param castsShadow  是否投射阴影
         * @param debugName    中性诊断名称
         */
        public DynamicPrimitive(
                long id,
                PrimitiveKind kind,
                PrimitiveGeometryKind geometryKind,
                double x,
                double y,
                double z,
                float yaw,
                float pitch,
                float roll,
                float radius,
                int materialKey,
                int textureKey,
                int packedLight,
                boolean castsShadow,
                String debugName
        ) {
            this(
                    id,
                    kind,
                    geometryKind,
                    x,
                    y,
                    z,
                    yaw,
                    pitch,
                    roll,
                    radius,
                    radius,
                    radius,
                    materialKey,
                    textureKey,
                    packedLight,
                    castsShadow,
                    debugName,
                    null
            );
        }

        /**
         * 返回包围椭球的最大轴半径。
         *
         * @return 三个轴半径的最大值
         */
        public float radius() {
            return Math.max(radiusX, Math.max(radiusY, radiusZ));
        }

        /**
         * Full model geometry participates in the world TLAS because it needs
         * hardware triangle traversal, material-table indexing, and frame-lifetime
         * protection. Lightweight impostor/cross-plane/billboard primitives stay
         * in the per-frame analytic stream; sending particle-like motion through
         * BLAS/TLAS would force scene graph rebuilds and stall presentation behind
         * stale-TLAS gates.
         *
         * @return 需要硬件三角形遍历时返回 {@code true}
         */
        public boolean usesTlasGeometry() {
            return geometryKind == PrimitiveGeometryKind.MODEL;
        }

        /**
         * 判断图元是否使用每帧解析几何快路径。
         *
         * @return 不进入 TLAS 时返回 {@code true}
         */
        public boolean usesAnalyticFastPath() {
            return !usesTlasGeometry();
        }
    }

    /**
     * Immutable scatter publication for collector-owned model slots.
     *
     * <p>Transforms are packed as twelve floats per update so GPU consumers can scatter the lane
     * without scanning the complete model list. Producer-owned deltas also retain the already
     * immutable slot publication: CPU lifecycle consumers install that identity directly instead
     * of rebuilding a second model object from the same transform fact.</p>
     */
    public static final class DynamicModelFrameDelta {
        /**
         * 新增槽标志。
         */
        public static final int ADDED = 1;
        /**
         * 移除槽标志。
         */
        public static final int REMOVED = 1 << 1;
        /**
         * 拓扑负载变化标志。
         */
        public static final int TOPOLOGY = 1 << 2;
        /**
         * 变换负载变化标志。
         */
        public static final int TRANSFORM = 1 << 3;
        /**
         * 材料负载变化标志。
         */
        public static final int MATERIAL = 1 << 4;
        /**
         * 光照负载变化标志。
         */
        public static final int LIGHT = 1 << 5;
        private static final int VALID_MASK = ADDED | REMOVED | TOPOLOGY | TRANSFORM | MATERIAL | LIGHT;
        private static final int TRANSFORM_COMPONENTS = 12;
        private static final DynamicModelFrameDelta NONE = new DynamicModelFrameDelta(
                0L, 0L, 0L, 0L, 0L, 0, 0,
                DynamicModelSlotSnapshot.empty(),
                DynamicModelTransformSnapshot.empty(),
                new int[0], new byte[0], new DynamicModelInstance[0], new float[0]
        );

        private final long membershipRevision;
        private final long topologyRevision;
        private final long transformRevision;
        private final long materialRevision;
        private final long lightRevision;
        private final int physicalSlotCount;
        private final int activeSlotCount;
        private final DynamicModelSlotSnapshot membershipSnapshot;
        private final DynamicModelTransformSnapshot transformSnapshot;
        private final int[] slots;
        private final byte[] dirtyMasks;
        private final DynamicModelInstance[] publications;
        private final float[] packedTransforms;

        /**
         * 从成员快照与散射数组创建动态模型帧增量，并派生变换快照。
         *
         * @param membershipRevision 成员修订号
         * @param topologyRevision   拓扑修订号
         * @param transformRevision  变换修订号
         * @param materialRevision   材料修订号
         * @param lightRevision      光照修订号
         * @param physicalSlotCount  物理槽容量
         * @param activeSlotCount    活动槽数量
         * @param membershipSnapshot 权威成员快照
         * @param slots              严格递增的更新槽
         * @param dirtyMasks         每个更新槽的变化标志
         * @param publications       非纯移除更新对应的发布对象
         * @param packedTransforms   每个更新槽连续十二个分量的变换负载
         */
        public DynamicModelFrameDelta(
                long membershipRevision,
                long topologyRevision,
                long transformRevision,
                long materialRevision,
                long lightRevision,
                int physicalSlotCount,
                int activeSlotCount,
                DynamicModelSlotSnapshot membershipSnapshot,
                int[] slots,
                byte[] dirtyMasks,
                DynamicModelInstance[] publications,
                float[] packedTransforms
        ) {
            this(
                    membershipRevision,
                    topologyRevision,
                    transformRevision,
                    materialRevision,
                    lightRevision,
                    physicalSlotCount,
                    activeSlotCount,
                    membershipSnapshot,
                    DynamicModelTransformSnapshot.fromFrame(
                            transformRevision, membershipSnapshot, slots, dirtyMasks, packedTransforms
                    ),
                    slots,
                    dirtyMasks,
                    publications,
                    packedTransforms
            );
        }

        /**
         * 从完整成员、变换权威和散射数组创建动态模型帧增量。
         *
         * @param membershipRevision 成员修订号
         * @param topologyRevision   拓扑修订号
         * @param transformRevision  变换修订号
         * @param materialRevision   材料修订号
         * @param lightRevision      光照修订号
         * @param physicalSlotCount  物理槽容量
         * @param activeSlotCount    活动槽数量
         * @param membershipSnapshot 权威成员快照
         * @param transformSnapshot  权威变换快照
         * @param slots              严格递增的更新槽
         * @param dirtyMasks         每个更新槽的变化标志
         * @param publications       非纯移除更新对应的发布对象
         * @param packedTransforms   每个更新槽连续十二个分量的变换负载
         */
        public DynamicModelFrameDelta(
                long membershipRevision,
                long topologyRevision,
                long transformRevision,
                long materialRevision,
                long lightRevision,
                int physicalSlotCount,
                int activeSlotCount,
                DynamicModelSlotSnapshot membershipSnapshot,
                DynamicModelTransformSnapshot transformSnapshot,
                int[] slots,
                byte[] dirtyMasks,
                DynamicModelInstance[] publications,
                float[] packedTransforms
        ) {
            this(
                    membershipRevision,
                    topologyRevision,
                    transformRevision,
                    materialRevision,
                    lightRevision,
                    physicalSlotCount,
                    activeSlotCount,
                    membershipSnapshot,
                    transformSnapshot,
                    slots,
                    dirtyMasks,
                    publications,
                    packedTransforms,
                    false
            );
        }

        private DynamicModelFrameDelta(
                long membershipRevision,
                long topologyRevision,
                long transformRevision,
                long materialRevision,
                long lightRevision,
                int physicalSlotCount,
                int activeSlotCount,
                DynamicModelSlotSnapshot membershipSnapshot,
                DynamicModelTransformSnapshot transformSnapshot,
                int[] slots,
                byte[] dirtyMasks,
                DynamicModelInstance[] publications,
                float[] packedTransforms,
                boolean takeOwnership
        ) {
            if (membershipRevision < 0L || topologyRevision < 0L || transformRevision < 0L
                    || materialRevision < 0L || lightRevision < 0L) {
                throw new IllegalArgumentException("dynamic model lane revisions must not be negative");
            }
            if (physicalSlotCount < 0 || activeSlotCount < 0 || activeSlotCount > physicalSlotCount) {
                throw new IllegalArgumentException("dynamic model slot counts are invalid");
            }
            this.membershipSnapshot = Objects.requireNonNull(membershipSnapshot, "membershipSnapshot");
            this.transformSnapshot = Objects.requireNonNull(transformSnapshot, "transformSnapshot");
            if (this.membershipSnapshot.membershipRevision() != membershipRevision
                    || this.membershipSnapshot.physicalSlotCount() != physicalSlotCount
                    || this.membershipSnapshot.activeSlotCount() != activeSlotCount) {
                throw new IllegalArgumentException("dynamic model membership snapshot does not match lane identity");
            }
            if (this.transformSnapshot.revision() != transformRevision
                    || this.transformSnapshot.physicalSlotCount() != physicalSlotCount) {
                throw new IllegalArgumentException("dynamic model transform snapshot does not match lane identity");
            }
            Objects.requireNonNull(slots, "slots");
            Objects.requireNonNull(dirtyMasks, "dirtyMasks");
            Objects.requireNonNull(publications, "publications");
            Objects.requireNonNull(packedTransforms, "packedTransforms");
            this.slots = takeOwnership ? slots : Arrays.copyOf(slots, slots.length);
            this.dirtyMasks = takeOwnership ? dirtyMasks : Arrays.copyOf(dirtyMasks, dirtyMasks.length);
            this.publications = takeOwnership
                    ? publications
                    : Arrays.copyOf(publications, publications.length);
            this.packedTransforms = takeOwnership
                    ? packedTransforms
                    : Arrays.copyOf(packedTransforms, packedTransforms.length);
            if (this.slots.length != this.dirtyMasks.length
                    || this.slots.length != this.publications.length
                    || this.packedTransforms.length != this.slots.length * TRANSFORM_COMPONENTS) {
                throw new IllegalArgumentException("dynamic model delta lanes must have matching update counts");
            }
            int previousSlot = -1;
            for (int update = 0; update < this.slots.length; update++) {
                int slot = this.slots[update];
                int mask = Byte.toUnsignedInt(this.dirtyMasks[update]);
                if (slot <= previousSlot || slot >= physicalSlotCount || mask == 0 || (mask & ~VALID_MASK) != 0) {
                    throw new IllegalArgumentException("dynamic model dirty slots must be sorted, unique, and valid");
                }
                boolean removedOnly = (mask & REMOVED) != 0 && (mask & ADDED) == 0;
                boolean publicationRequired = !removedOnly
                        && (mask & (ADDED | TOPOLOGY | MATERIAL | LIGHT)) != 0;
                if (publicationRequired && this.publications[update] == null) {
                    throw new IllegalArgumentException("non-transform model lane update requires a publication");
                }
                if ((mask & TRANSFORM) != 0) {
                    for (int component = 0; component < TRANSFORM_COMPONENTS; component++) {
                        if (!Float.isFinite(this.packedTransforms[update * TRANSFORM_COMPONENTS + component])) {
                            throw new IllegalArgumentException("packed model transform must be finite");
                        }
                        if (this.publications[update] != null
                                && Float.floatToIntBits(this.publications[update].transform().value(component))
                                != Float.floatToIntBits(
                                this.packedTransforms[update * TRANSFORM_COMPONENTS + component]
                        )) {
                            throw new IllegalArgumentException(
                                    "model publication transform does not match packed scatter lane"
                            );
                        }
                    }
                }
                previousSlot = slot;
            }
            this.membershipRevision = membershipRevision;
            this.topologyRevision = topologyRevision;
            this.transformRevision = transformRevision;
            this.materialRevision = materialRevision;
            this.lightRevision = lightRevision;
            this.physicalSlotCount = physicalSlotCount;
            this.activeSlotCount = activeSlotCount;
        }

        /**
         * Transfers fresh collector arrays into one immutable frame publication.
         *
         * <p>Public constructors stay defensive for external/test callers. The collector creates
         * these arrays solely for this publication and never mutates them afterward, so copying
         * the transform scatter a second time only doubles per-animation-frame allocation.</p>
         */
        static DynamicModelFrameDelta takeCollectorOwnership(
                long membershipRevision,
                long topologyRevision,
                long transformRevision,
                long materialRevision,
                long lightRevision,
                int physicalSlotCount,
                int activeSlotCount,
                DynamicModelSlotSnapshot membershipSnapshot,
                DynamicModelTransformSnapshot transformSnapshot,
                int[] slots,
                byte[] dirtyMasks,
                DynamicModelInstance[] publications,
                float[] packedTransforms
        ) {
            return new DynamicModelFrameDelta(
                    membershipRevision,
                    topologyRevision,
                    transformRevision,
                    materialRevision,
                    lightRevision,
                    physicalSlotCount,
                    activeSlotCount,
                    membershipSnapshot,
                    transformSnapshot,
                    slots,
                    dirtyMasks,
                    publications,
                    packedTransforms,
                    true
            );
        }

        /**
         * 返回不包含任何权威状态或更新的共享空增量。
         *
         * @return 空帧增量
         */
        public static DynamicModelFrameDelta none() {
            return NONE;
        }

        /**
         * 返回成员修订号。
         *
         * @return 成员修订号
         */
        public long membershipRevision() {
            return membershipRevision;
        }

        /**
         * 返回拓扑修订号。
         *
         * @return 拓扑修订号
         */
        public long topologyRevision() {
            return topologyRevision;
        }

        /**
         * 返回变换修订号。
         *
         * @return 变换修订号
         */
        public long transformRevision() {
            return transformRevision;
        }

        /**
         * 返回材料修订号。
         *
         * @return 材料修订号
         */
        public long materialRevision() {
            return materialRevision;
        }

        /**
         * 返回光照修订号。
         *
         * @return 光照修订号
         */
        public long lightRevision() {
            return lightRevision;
        }

        /**
         * 返回物理槽容量。
         *
         * @return 物理槽容量
         */
        public int physicalSlotCount() {
            return physicalSlotCount;
        }

        /**
         * 返回活动槽数量。
         *
         * @return 活动槽数量
         */
        public int activeSlotCount() {
            return activeSlotCount;
        }

        /**
         * 返回权威成员快照。
         *
         * @return 权威成员快照
         */
        public DynamicModelSlotSnapshot membershipSnapshot() {
            return membershipSnapshot;
        }

        /**
         * 返回权威变换快照。
         *
         * @return 权威变换快照
         */
        public DynamicModelTransformSnapshot transformSnapshot() {
            return transformSnapshot;
        }

        /**
         * 返回散射更新数量。
         *
         * @return 散射更新数量
         */
        public int updateCount() {
            return slots.length;
        }

        /**
         * 判断是否至少包含一个散射更新。
         *
         * @return 至少包含一个散射更新时返回 {@code true}
         */
        public boolean hasUpdates() {
            return slots.length > 0;
        }

        /**
         * 判断增量是否携带可发布的权威状态。
         *
         * @return 任一权威身份或更新存在时返回 {@code true}
         */
        public boolean isAuthoritative() {
            return membershipRevision > 0L || physicalSlotCount > 0 || activeSlotCount > 0 || hasUpdates();
        }

        /**
         * 返回指定更新的物理槽。
         *
         * @param update 更新索引
         * @return 对应物理槽
         */
        public int slotAt(int update) {
            return slots[update];
        }

        /**
         * 返回指定更新的变化标志。
         *
         * @param update 更新索引
         * @return 对应变化标志
         */
        public int dirtyMaskAt(int update) {
            return Byte.toUnsignedInt(dirtyMasks[update]);
        }

        /**
         * 返回指定更新携带的发布对象。
         *
         * @param update 更新索引
         * @return 对应发布对象；纯移除或变换更新可能为 {@code null}
         */
        public DynamicModelInstance publicationAt(int update) {
            return publications[update];
        }

        /**
         * 返回指定更新的一个变换分量。
         *
         * @param update    更新索引
         * @param component 0..11 分量索引
         * @return 对应变换分量
         */
        public float transformAt(int update, int component) {
            if (component < 0 || component >= TRANSFORM_COMPONENTS) {
                throw new IndexOutOfBoundsException(component);
            }
            return packedTransforms[update * TRANSFORM_COMPONENTS + component];
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof DynamicModelFrameDelta that
                    && membershipRevision == that.membershipRevision
                    && topologyRevision == that.topologyRevision
                    && transformRevision == that.transformRevision
                    && materialRevision == that.materialRevision
                    && lightRevision == that.lightRevision
                    && physicalSlotCount == that.physicalSlotCount
                    && activeSlotCount == that.activeSlotCount
                    && membershipSnapshot.equals(that.membershipSnapshot)
                    && transformSnapshot.equals(that.transformSnapshot)
                    && Arrays.equals(slots, that.slots)
                    && Arrays.equals(dirtyMasks, that.dirtyMasks)
                    && Arrays.equals(publications, that.publications)
                    && Arrays.equals(packedTransforms, that.packedTransforms);
        }

        @Override
        public int hashCode() {
            int hash = Objects.hash(
                    membershipRevision, topologyRevision, transformRevision, materialRevision,
                    lightRevision, physicalSlotCount, activeSlotCount, membershipSnapshot, transformSnapshot
            );
            hash = 31 * hash + Arrays.hashCode(slots);
            hash = 31 * hash + Arrays.hashCode(dirtyMasks);
            hash = 31 * hash + Arrays.hashCode(publications);
            return 31 * hash + Arrays.hashCode(packedTransforms);
        }
    }

    /**
     * Immutable owner-generation map from collector slots to model identities.
     *
     * <p>The frame delta is intentionally lossy after publication: a stable owner may emit only a
     * transform lane. Native consumers can begin observing that stream after an earlier compatibility
     * frame, so slot ownership cannot be reconstructed from the current dirty set. This snapshot is
     * rebuilt only when membership changes and is retained by identity across transform/material frames,
     * matching GPUScene's persistent primitive-index publication.</p>
     */
    public static final class DynamicModelSlotSnapshot {
        private static final DynamicModelSlotSnapshot EMPTY = new DynamicModelSlotSnapshot(
                0L, 0, new int[0], new DynamicModelInstance[0]
        );

        private final long membershipRevision;
        private final int physicalSlotCount;
        private final int[] activeSlots;
        private final DynamicModelInstance[] instances;

        /**
         * 创建并校验一个权威模型槽成员快照。
         *
         * @param membershipRevision 非负成员修订号
         * @param physicalSlotCount  物理槽容量
         * @param activeSlots        严格递增的活动槽
         * @param instances          与活动槽一一对应的模型实例
         */
        public DynamicModelSlotSnapshot(
                long membershipRevision,
                int physicalSlotCount,
                int[] activeSlots,
                DynamicModelInstance[] instances
        ) {
            if (membershipRevision < 0L || physicalSlotCount < 0) {
                throw new IllegalArgumentException("dynamic model membership identity must not be negative");
            }
            this.activeSlots = Arrays.copyOf(Objects.requireNonNull(activeSlots, "activeSlots"), activeSlots.length);
            this.instances = Arrays.copyOf(Objects.requireNonNull(instances, "instances"), instances.length);
            if (this.activeSlots.length != this.instances.length) {
                throw new IllegalArgumentException("dynamic model membership lanes must have matching lengths");
            }
            LongOpenHashSet identities = new LongOpenHashSet(this.instances.length);
            int previousSlot = -1;
            for (int index = 0; index < this.activeSlots.length; index++) {
                int slot = this.activeSlots[index];
                DynamicModelInstance instance = Objects.requireNonNull(
                        this.instances[index], "dynamic model membership instance"
                );
                if (slot <= previousSlot || slot >= physicalSlotCount || !identities.add(instance.id())) {
                    throw new IllegalArgumentException(
                            "dynamic model membership slots and identities must be sorted and unique"
                    );
                }
                previousSlot = slot;
            }
            this.membershipRevision = membershipRevision;
            this.physicalSlotCount = physicalSlotCount;
        }

        /**
         * 返回修订号和容量均为零的共享空成员快照。
         *
         * @return 空成员快照
         */
        public static DynamicModelSlotSnapshot empty() {
            return EMPTY;
        }

        /**
         * 返回成员修订号。
         *
         * @return 非负成员修订号
         */
        public long membershipRevision() {
            return membershipRevision;
        }

        /**
         * 返回物理槽容量。
         *
         * @return 非负物理槽容量
         */
        public int physicalSlotCount() {
            return physicalSlotCount;
        }

        /**
         * 返回活动槽数量。
         *
         * @return 活动槽数量
         */
        public int activeSlotCount() {
            return activeSlots.length;
        }

        /**
         * 返回指定活动索引对应的物理槽。
         *
         * @param index 活动索引
         * @return 对应物理槽
         */
        public int slotAt(int index) {
            return activeSlots[index];
        }

        /**
         * 返回指定活动索引对应的模型实例。
         *
         * @param index 活动索引
         * @return 对应模型实例
         */
        public DynamicModelInstance instanceAt(int index) {
            return instances[index];
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof DynamicModelSlotSnapshot that
                    && membershipRevision == that.membershipRevision
                    && physicalSlotCount == that.physicalSlotCount
                    && Arrays.equals(activeSlots, that.activeSlots)
                    && Arrays.equals(instances, that.instances);
        }

        @Override
        public int hashCode() {
            int hash = Objects.hash(membershipRevision, physicalSlotCount);
            hash = 31 * hash + Arrays.hashCode(activeSlots);
            return 31 * hash + Arrays.hashCode(instances);
        }
    }

    /**
     * Persistent-slot payload for captured host model geometry.
     *
     * <p>This is intentionally separate from {@link DynamicPrimitive}: model
     * topology is identified by {@code id + asset.id}, transforms are TLAS
     * payload, and light/face materials are material-table payload. Keeping
     * those fields in one compact renderer-owned value lets the RT cache use
     * persistent dirty lanes without rebuilding generic analytic primitive data
     * for every host submit traversal.</p>
     */
    public static final class DynamicModelInstance implements DynamicModelObservation {
        private final long id;
        private final PrimitiveKind kind;
        private final DynamicMeshAsset asset;
        private final DynamicMeshInstance.AffineTransform transform;
        private final List<DynamicMeshInstance.FaceMaterial> faceMaterials;
        private final DynamicRenderLane renderLane;
        private final int packedLight;
        private final String debugName;
        private final long topologyRevision;
        private final long transformRevision;
        private final long materialRevision;
        private final long lightRevision;
        private final int faceMaterialsHash;
        private final int hashCode;

        /**
         * 创建使用材料推导渲染通道的模型实例。
         *
         * @param id            稳定模型标识
         * @param kind          所有者语义
         * @param asset         共享网格资产
         * @param transform     对象到世界变换
         * @param faceMaterials 面材料列表
         * @param packedLight   打包光照
         * @param debugName     中性诊断名称
         */
        public DynamicModelInstance(
                long id,
                PrimitiveKind kind,
                DynamicMeshAsset asset,
                DynamicMeshInstance.AffineTransform transform,
                List<DynamicMeshInstance.FaceMaterial> faceMaterials,
                int packedLight,
                String debugName
        ) {
            this(id, kind, asset, transform, faceMaterials, packedLight, debugName, null);
        }

        /**
         * 创建使用显式渲染通道的模型实例。
         *
         * @param id            稳定模型标识
         * @param kind          所有者语义
         * @param asset         共享网格资产
         * @param transform     对象到世界变换
         * @param faceMaterials 面材料列表
         * @param packedLight   打包光照
         * @param debugName     中性诊断名称
         * @param renderLane    显式实例深度通道；{@code null} 表示从材料推导
         */
        public DynamicModelInstance(
                long id,
                PrimitiveKind kind,
                DynamicMeshAsset asset,
                DynamicMeshInstance.AffineTransform transform,
                List<DynamicMeshInstance.FaceMaterial> faceMaterials,
                int packedLight,
                String debugName,
                DynamicRenderLane renderLane
        ) {
            this(
                    id,
                    kind,
                    asset,
                    transform,
                    List.copyOf(Objects.requireNonNull(faceMaterials, "faceMaterials")),
                    packedLight,
                    sanitizeDebugName(debugName),
                    1L,
                    1L,
                    1L,
                    1L,
                    renderLane,
                    publishedFaceMaterialsHash(faceMaterials)
            );
        }

        private DynamicModelInstance(
                long id,
                PrimitiveKind kind,
                DynamicMeshAsset asset,
                DynamicMeshInstance.AffineTransform transform,
                List<DynamicMeshInstance.FaceMaterial> faceMaterials,
                int packedLight,
                String debugName,
                long topologyRevision,
                long transformRevision,
                long materialRevision,
                long lightRevision,
                DynamicRenderLane retainedRenderLane,
                int retainedFaceMaterialsHash
        ) {
            requireNonNegative(id, "id");
            this.id = id;
            this.kind = kind == null ? PrimitiveKind.ENTITY : kind;
            this.asset = Objects.requireNonNull(asset, "asset");
            this.transform = Objects.requireNonNull(transform, "transform");
            this.faceMaterials = Objects.requireNonNull(faceMaterials, "faceMaterials");
            if (this.faceMaterials.size() != this.asset.faceCount()) {
                throw new IllegalArgumentException("model face materials must exactly match the mesh asset face count");
            }
            this.renderLane = retainedRenderLane == null
                    ? DynamicRenderLane.fromFaceMaterials(this.faceMaterials)
                    : retainedRenderLane;
            this.packedLight = packedLight;
            this.debugName = Objects.requireNonNull(debugName, "debugName");
            this.topologyRevision = requirePositiveRevision(topologyRevision, "topologyRevision");
            this.transformRevision = requirePositiveRevision(transformRevision, "transformRevision");
            this.materialRevision = requirePositiveRevision(materialRevision, "materialRevision");
            this.lightRevision = requirePositiveRevision(lightRevision, "lightRevision");
            this.faceMaterialsHash = retainedFaceMaterialsHash;
            int hash = Long.hashCode(this.id);
            hash = 31 * hash + this.kind.hashCode();
            hash = 31 * hash + this.asset.hashCode();
            hash = 31 * hash + this.transform.hashCode();
            hash = 31 * hash + this.faceMaterialsHash;
            hash = 31 * hash + this.renderLane.hashCode();
            hash = 31 * hash + Integer.hashCode(this.packedLight);
            this.hashCode = 31 * hash + this.debugName.hashCode();
        }

        private static long requirePositiveRevision(long revision, String name) {
            if (revision <= 0L) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return revision;
        }

        private static long nextLaneRevision(long revision) {
            return revision == Long.MAX_VALUE ? 1L : revision + 1L;
        }

        private static int publishedFaceMaterialsHash(
                List<DynamicMeshInstance.FaceMaterial> faceMaterials
        ) {
            return Objects.requireNonNull(faceMaterials, "faceMaterials").hashCode();
        }

        /**
         * 返回稳定模型标识。
         *
         * @return 稳定模型标识
         */
        public long id() {
            return id;
        }

        /**
         * 返回模型所有者语义。
         *
         * @return 图元类型
         */
        public PrimitiveKind kind() {
            return kind;
        }

        /**
         * 返回共享网格资产。
         *
         * @return 不可变网格资产
         */
        public DynamicMeshAsset asset() {
            return asset;
        }

        /**
         * 返回对象到世界变换。
         *
         * @return 不可变仿射变换
         */
        public DynamicMeshInstance.AffineTransform transform() {
            return transform;
        }

        @Override
        public float transformValue(int component) {
            return transform.value(component);
        }

        /**
         * 返回不可变面材料。
         *
         * @return 面材料列表
         */
        public List<DynamicMeshInstance.FaceMaterial> faceMaterials() {
            return faceMaterials;
        }

        /**
         * 返回实例深度通道。
         *
         * @return 渲染通道
         */
        public DynamicRenderLane renderLane() {
            return renderLane;
        }

        /**
         * 返回打包光照。
         *
         * @return 打包光照值
         */
        public int packedLight() {
            return packedLight;
        }

        /**
         * 返回中性诊断名称。
         *
         * @return 诊断名称
         */
        public String debugName() {
            return debugName;
        }

        /**
         * 返回拓扑通道修订号。
         *
         * @return 正拓扑修订号
         */
        public long topologyRevision() {
            return topologyRevision;
        }

        /**
         * 返回变换通道修订号。
         *
         * @return 正变换修订号
         */
        public long transformRevision() {
            return transformRevision;
        }

        /**
         * 返回材料通道修订号。
         *
         * @return 正材料修订号
         */
        public long materialRevision() {
            return materialRevision;
        }

        /**
         * 返回光照通道修订号。
         *
         * @return 正光照修订号
         */
        public long lightRevision() {
            return lightRevision;
        }

        /**
         * 返回由当前拆分通道组合的完整网格实例。
         *
         * @return 完整网格实例
         */
        public DynamicMeshInstance meshInstance() {
            return new DynamicMeshInstance(asset, transform, faceMaterials);
        }

        /**
         * Produces the next immutable publication while preserving independent GPUScene lanes.
         * Stable observations return this exact object. A transform-only animation therefore
         * reuses topology/material/light state and never recopies the face-material list.
         *
         * @param nextKind          下一所有者语义
         * @param nextAsset         下一网格资产
         * @param nextTransform     下一对象到世界变换
         * @param nextFaceMaterials 下一面材料列表
         * @param nextPackedLight   下一打包光照
         * @param nextDebugName     下一中性诊断名称
         * @return 所有通道未变化时返回当前对象，否则返回下一发布
         */
        public DynamicModelInstance withObservation(
                PrimitiveKind nextKind,
                DynamicMeshAsset nextAsset,
                DynamicMeshInstance.AffineTransform nextTransform,
                List<DynamicMeshInstance.FaceMaterial> nextFaceMaterials,
                int nextPackedLight,
                String nextDebugName
        ) {
            return withObservation(
                    nextKind, nextAsset, nextTransform, nextFaceMaterials,
                    nextPackedLight, nextDebugName, null
            );
        }

        /**
         * 使用显式深度通道生成下一份拆分通道发布。
         *
         * @param nextKind          下一所有者语义
         * @param nextAsset         下一网格资产
         * @param nextTransform     下一对象到世界变换
         * @param nextFaceMaterials 下一面材料列表
         * @param nextPackedLight   下一打包光照
         * @param nextDebugName     下一中性诊断名称
         * @param nextRenderLane    下一显式深度通道；{@code null} 表示复用或从材料推导
         * @return 所有通道未变化时返回当前对象，否则返回下一发布
         */
        public DynamicModelInstance withObservation(
                PrimitiveKind nextKind,
                DynamicMeshAsset nextAsset,
                DynamicMeshInstance.AffineTransform nextTransform,
                List<DynamicMeshInstance.FaceMaterial> nextFaceMaterials,
                int nextPackedLight,
                String nextDebugName,
                DynamicRenderLane nextRenderLane
        ) {
            PrimitiveKind normalizedKind = nextKind == null ? PrimitiveKind.ENTITY : nextKind;
            DynamicMeshAsset normalizedAsset = Objects.requireNonNull(nextAsset, "asset");
            DynamicMeshInstance.AffineTransform normalizedTransform =
                    Objects.requireNonNull(nextTransform, "transform");
            List<DynamicMeshInstance.FaceMaterial> observedMaterials =
                    Objects.requireNonNull(nextFaceMaterials, "faceMaterials");
            String normalizedDebugName = sanitizeDebugName(nextDebugName);

            boolean topologyChanged = kind != normalizedKind
                    || (asset != normalizedAsset && !asset.equals(normalizedAsset))
                    || (debugName != normalizedDebugName && !debugName.equals(normalizedDebugName));
            boolean transformChanged = transform != normalizedTransform
                    && !transform.equals(normalizedTransform);
            boolean materialChanged = faceMaterials != observedMaterials
                    && !faceMaterials.equals(observedMaterials);
            /*
             * Render lane is a material fact. Transform-only animation must
             * not rescan every cube face merely because the model publication
             * advances. Reuse the retained lane whenever the material payload
             * is equal; an explicit producer lane still wins when supplied.
             */
            DynamicRenderLane observedRenderLane = nextRenderLane != null
                    ? nextRenderLane
                    : materialChanged
                      ? DynamicRenderLane.fromFaceMaterials(observedMaterials)
                      : renderLane;
            boolean renderLaneChanged = renderLane != observedRenderLane;
            boolean lightChanged = packedLight != nextPackedLight;
            if (!topologyChanged && !transformChanged && !materialChanged && !renderLaneChanged && !lightChanged) {
                return this;
            }

            List<DynamicMeshInstance.FaceMaterial> publishedMaterials = materialChanged
                    ? List.copyOf(observedMaterials)
                    : faceMaterials;
            return new DynamicModelInstance(
                    id,
                    normalizedKind,
                    normalizedAsset,
                    transformChanged ? normalizedTransform : transform,
                    publishedMaterials,
                    nextPackedLight,
                    normalizedDebugName,
                    topologyChanged ? nextLaneRevision(topologyRevision) : topologyRevision,
                    transformChanged || renderLaneChanged
                            ? nextLaneRevision(transformRevision)
                            : transformRevision,
                    materialChanged ? nextLaneRevision(materialRevision) : materialRevision,
                    lightChanged ? nextLaneRevision(lightRevision) : lightRevision,
                    observedRenderLane,
                    materialChanged ? publishedMaterials.hashCode() : faceMaterialsHash
            );
        }

        /**
         * Producer-side reuse predicate. Capture can preserve this immutable value across frames
         * only when every visible RT field is identical; changing an animation transform, material,
         * light, render lane, or debug identity always produces a new publication value.
         *
         * @param id            待比较稳定标识
         * @param kind          待比较所有者语义
         * @param asset         待比较网格资产
         * @param transform     待比较对象到世界变换
         * @param faceMaterials 待比较面材料
         * @param packedLight   待比较打包光照
         * @param debugName     待比较中性诊断名称
         * @return 所有可见 RT 字段均相同时返回 {@code true}
         */
        public boolean matches(
                long id,
                PrimitiveKind kind,
                DynamicMeshAsset asset,
                DynamicMeshInstance.AffineTransform transform,
                List<DynamicMeshInstance.FaceMaterial> faceMaterials,
                int packedLight,
                String debugName
        ) {
            return this.id == id
                    && this.kind == (kind == null ? PrimitiveKind.ENTITY : kind)
                    && this.asset.equals(Objects.requireNonNull(asset, "asset"))
                    && this.transform.equals(Objects.requireNonNull(transform, "transform"))
                    && this.faceMaterials.equals(Objects.requireNonNull(faceMaterials, "faceMaterials"))
                    && this.packedLight == packedLight
                    && this.debugName.equals(sanitizeDebugName(debugName));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof DynamicModelInstance that
                    && id == that.id
                    && packedLight == that.packedLight
                    && kind == that.kind
                    && asset.equals(that.asset)
                    && transform.equals(that.transform)
                    && faceMaterials.equals(that.faceMaterials)
                    && renderLane == that.renderLane
                    && debugName.equals(that.debugName);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }

    /**
     * 一个完整冻结的公告板粒子。
     *
     * @param id             稳定粒子标识
     * @param kind           混合语义
     * @param x              世界空间 X
     * @param y              世界空间 Y
     * @param z              世界空间 Z
     * @param size           正粒子尺寸
     * @param rgba8          打包颜色
     * @param textureId      纹理标识
     * @param packedLight    打包光照
     * @param ageFraction    0..1 生命周期进度
     * @param rotationX      单位四元数 X
     * @param rotationY      单位四元数 Y
     * @param rotationZ      单位四元数 Z
     * @param rotationW      单位四元数 W
     * @param u0             第一 U 边界
     * @param u1             第二 U 边界
     * @param v0             第一 V 边界
     * @param v1             第二 V 边界
     * @param lifecycleAlpha 生命周期透明度
     */
    public record BillboardParticle(
            long id,
            ParticleKind kind,
            double x,
            double y,
            double z,
            float size,
            int rgba8,
            int textureId,
            int packedLight,
            float ageFraction,
            float rotationX,
            float rotationY,
            float rotationZ,
            float rotationW,
            float u0,
            float u1,
            float v0,
            float v1,
            float lifecycleAlpha
    ) {
        /**
         * 校验并规范化粒子空间、旋转、UV 与生命周期状态。
         */
        public BillboardParticle {
            requireNonNegative(id, "id");
            requireFinite(x, "x");
            requireFinite(y, "y");
            requireFinite(z, "z");
            requirePositive(size, "size");
            requireFinite(ageFraction, "ageFraction");
            if (ageFraction < 0.0F || ageFraction > 1.0F) {
                throw new IllegalArgumentException("ageFraction must be in [0, 1]");
            }
            requireFinite(rotationX, "rotationX");
            requireFinite(rotationY, "rotationY");
            requireFinite(rotationZ, "rotationZ");
            requireFinite(rotationW, "rotationW");
            float rotationLengthSquared = rotationX * rotationX + rotationY * rotationY
                    + rotationZ * rotationZ + rotationW * rotationW;
            if (Math.abs(rotationLengthSquared - 1.0F) > 1.0e-3F) {
                throw new IllegalArgumentException("particle rotation must be normalized");
            }
            requireFinite(u0, "u0");
            requireFinite(u1, "u1");
            requireFinite(v0, "v0");
            requireFinite(v1, "v1");
            /* Sprite animation and mirroring may intentionally reverse either UV axis. */
            lifecycleAlpha = clamp01Finite(lifecycleAlpha, "lifecycleAlpha");
            kind = kind == null ? ParticleKind.TRANSLUCENT_BILLBOARD : kind;
        }

        /**
         * 使用单位旋转、完整 UV 和颜色透明度创建粒子。
         *
         * @param id          稳定粒子标识
         * @param kind        混合语义
         * @param x           世界空间 X
         * @param y           世界空间 Y
         * @param z           世界空间 Z
         * @param size        正粒子尺寸
         * @param rgba8       打包颜色
         * @param textureId   纹理标识
         * @param packedLight 打包光照
         * @param ageFraction 0..1 生命周期进度
         */
        public BillboardParticle(
                long id,
                ParticleKind kind,
                double x,
                double y,
                double z,
                float size,
                int rgba8,
                int textureId,
                int packedLight,
                float ageFraction
        ) {
            this(
                    id, kind, x, y, z, size, rgba8, textureId, packedLight, ageFraction,
                    0.0F, 0.0F, 0.0F, 1.0F,
                    0.0F, 1.0F, 0.0F, 1.0F,
                    ((rgba8 >>> 24) & 0xFF) / 255.0F
            );
        }
    }

    /**
     * 一根相机邻域天气渲染柱。
     *
     * @param kind        天气类型
     * @param x           世界空间 X
     * @param z           世界空间 Z
     * @param bottomY     柱体底部高度
     * @param topY        柱体顶部高度
     * @param uOffset     U 动画偏移
     * @param vOffset     V 动画偏移
     * @param lightCoords 打包光照坐标
     * @param alpha       0..1 透明度
     */
    public record WeatherColumn(
            WeatherKind kind,
            double x,
            double z,
            float bottomY,
            float topY,
            float uOffset,
            float vOffset,
            int lightCoords,
            float alpha
    ) {
        /**
         * 校验并规范化天气柱状态。
         */
        public WeatherColumn {
            kind = kind == null ? WeatherKind.RAIN : kind;
            requireFinite(x, "x");
            requireFinite(z, "z");
            requireFinite(bottomY, "bottomY");
            requireFinite(topY, "topY");
            requireFinite(uOffset, "uOffset");
            requireFinite(vOffset, "vOffset");
            requireFinite(alpha, "alpha");
            if (topY <= bottomY) {
                throw new IllegalArgumentException("weather column topY must be greater than bottomY");
            }
            alpha = clamp01(alpha);
        }
    }

    /**
     * 两个不同世界空间端点之间的发光光束。
     *
     * @param id          稳定光束标识
     * @param kind        光束视觉类型
     * @param startX      起点 X
     * @param startY      起点 Y
     * @param startZ      起点 Z
     * @param endX        终点 X
     * @param endY        终点 Y
     * @param endZ        终点 Z
     * @param radius      正半径
     * @param rgba8       打包颜色
     * @param textureKey  纹理键
     * @param packedLight 打包光照
     * @param additive    是否使用加法混合
     */
    public record Beam(
            long id,
            BeamKind kind,
            double startX,
            double startY,
            double startZ,
            double endX,
            double endY,
            double endZ,
            float radius,
            int rgba8,
            int textureKey,
            int packedLight,
            boolean additive
    ) {
        /**
         * 校验并规范化光束端点、半径和类型。
         */
        public Beam {
            requireNonNegative(id, "id");
            requireFinite(startX, "startX");
            requireFinite(startY, "startY");
            requireFinite(startZ, "startZ");
            requireFinite(endX, "endX");
            requireFinite(endY, "endY");
            requireFinite(endZ, "endZ");
            requirePositive(radius, "radius");
            if (startX == endX && startY == endY && startZ == endZ) {
                throw new IllegalArgumentException("beam endpoints must not be identical");
            }
            kind = kind == null ? BeamKind.GENERIC : kind;
        }
    }

    /**
     * Sparse same-surface decal keyed by exact integer world coordinates.
     *
     * @param blockX    integer world x coordinate
     * @param blockY    integer world y coordinate
     * @param blockZ    integer world z coordinate
     * @param offsetX   finite local x offset
     * @param offsetY   finite local y offset
     * @param offsetZ   finite local z offset
     * @param textureId positive registered texture identifier
     * @param progress  discrete progression level in {@code [0, 9]}
     */
    public record BlockDecal(
            int blockX,
            int blockY,
            int blockZ,
            float offsetX,
            float offsetY,
            float offsetZ,
            int textureId,
            int progress
    ) {
        /**
         * 校验并规范化稀疏区块贴花。
         */
        public BlockDecal {
            if (blockX < -33_554_432 || blockX > 33_554_431
                    || blockZ < -33_554_432 || blockZ > 33_554_431
                    || blockY < -2_048 || blockY > 2_047) {
                throw new IllegalArgumentException("block decal position must fit BlockPos.asLong encoding");
            }
            requireFinite(offsetX, "blockDecal.offsetX");
            requireFinite(offsetY, "blockDecal.offsetY");
            requireFinite(offsetZ, "blockDecal.offsetZ");
            if (textureId <= 0) {
                throw new IllegalArgumentException("block decal requires a registered texture");
            }
            if (progress < 0 || progress > 9) {
                throw new IllegalArgumentException("block decal progress must be in [0, 9]");
            }
        }

        /**
         * 将整数世界坐标编码为稳定贴花标识。
         *
         * @return 无冲突的坐标编码
         */
        public long stableId() {
            return ((long) blockX & 0x3FF_FFFFL) << 38
                    | ((long) blockZ & 0x3FF_FFFFL) << 12
                    | ((long) blockY & 0xFFFL);
        }
    }

    /**
     * 一个远场天体发光盘。
     *
     * @param kind          天体类型
     * @param directionX    单位方向 X
     * @param directionY    单位方向 Y
     * @param directionZ    单位方向 Z
     * @param angularRadius 正角半径
     * @param rgba8         打包颜色
     * @param textureKey    纹理键
     * @param brightness    非负亮度
     */
    public record CelestialBody(
            CelestialKind kind,
            float directionX,
            float directionY,
            float directionZ,
            float angularRadius,
            int rgba8,
            int textureKey,
            float brightness
    ) {
        /**
         * 校验并规范化天体方向、半径与亮度。
         */
        public CelestialBody {
            kind = kind == null ? CelestialKind.SKY_DISC : kind;
            requireUnitVector(directionX, directionY, directionZ, "direction");
            requirePositive(angularRadius, "angularRadius");
            requireFinite(brightness, "brightness");
            if (brightness < 0.0F) {
                throw new IllegalArgumentException("brightness must not be negative");
            }
        }
    }

    /**
     * 一个解析方向光或局部光源。
     *
     * @param id          稳定光源标识
     * @param kind        光源类型
     * @param x           世界空间 X
     * @param y           世界空间 Y
     * @param z           世界空间 Z
     * @param directionX  方向 X
     * @param directionY  方向 Y
     * @param directionZ  方向 Z
     * @param radius      正影响半径
     * @param intensity   非负强度
     * @param rgb8        打包 RGB 颜色
     * @param castsShadow 是否投射阴影
     */
    public record SceneLight(
            long id,
            LightKind kind,
            double x,
            double y,
            double z,
            float directionX,
            float directionY,
            float directionZ,
            float radius,
            float intensity,
            int rgb8,
            boolean castsShadow
    ) {
        /**
         * 校验并规范化光源空间与光度状态。
         */
        public SceneLight {
            requireNonNegative(id, "id");
            kind = kind == null ? LightKind.BLOCK_EMISSION : kind;
            requireFinite(x, "x");
            requireFinite(y, "y");
            requireFinite(z, "z");
            if (kind == LightKind.SUN || kind == LightKind.MOON || kind == LightKind.SKY) {
                requireUnitVector(directionX, directionY, directionZ, "direction");
            } else {
                requireFinite(directionX, "directionX");
                requireFinite(directionY, "directionY");
                requireFinite(directionZ, "directionZ");
            }
            requirePositive(radius, "radius");
            requireFinite(intensity, "intensity");
            if (intensity < 0.0F) {
                throw new IllegalArgumentException("intensity must not be negative");
            }
        }
    }

    /**
     * 与动态场景同时冻结的环境、雾、云和时间状态。
     *
     * @param fogKnown            雾参数是否权威
     * @param fogRed              雾红色分量
     * @param fogGreen            雾绿色分量
     * @param fogBlue             雾蓝色分量
     * @param fogAlpha            雾透明度
     * @param environmentalStart  环境雾起点
     * @param environmentalEnd    环境雾终点
     * @param renderDistanceStart 渲染距离雾起点
     * @param renderDistanceEnd   渲染距离雾终点
     * @param skyEnd              天空可见终点
     * @param cloudEnd            云可见终点
     * @param cloudKnown          云参数是否权威
     * @param cloudRgba8          打包云颜色
     * @param cloudHeight         云层高度
     * @param cloudRange          云渲染范围
     * @param cloudStatus         云状态编码
     * @param gameTime            非负场景时间
     * @param partialTicks        {@code [0, 1]} 内的帧插值
     * @param skyVisible          天空是否可见
     */
    public record EnvironmentState(
            boolean fogKnown,
            float fogRed,
            float fogGreen,
            float fogBlue,
            float fogAlpha,
            float environmentalStart,
            float environmentalEnd,
            float renderDistanceStart,
            float renderDistanceEnd,
            float skyEnd,
            float cloudEnd,
            boolean cloudKnown,
            int cloudRgba8,
            float cloudHeight,
            int cloudRange,
            int cloudStatus,
            long gameTime,
            float partialTicks,
            boolean skyVisible
    ) {
        private static final EnvironmentState UNKNOWN = new EnvironmentState(
                false,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                false,
                0,
                0.0F,
                0,
                0,
                0L,
                0.0F,
                false
        );

        /**
         * 校验并规范化环境颜色、距离和时间状态。
         */
        public EnvironmentState {
            fogRed = clampColor(fogRed, "fogRed");
            fogGreen = clampColor(fogGreen, "fogGreen");
            fogBlue = clampColor(fogBlue, "fogBlue");
            fogAlpha = clamp01Finite(fogAlpha, "fogAlpha");
            environmentalStart = finiteDistance(environmentalStart, "environmentalStart");
            environmentalEnd = finiteDistance(environmentalEnd, "environmentalEnd");
            renderDistanceStart = finiteDistance(renderDistanceStart, "renderDistanceStart");
            renderDistanceEnd = finiteDistance(renderDistanceEnd, "renderDistanceEnd");
            skyEnd = finiteDistance(skyEnd, "skyEnd");
            cloudEnd = finiteDistance(cloudEnd, "cloudEnd");
            requireFinite(cloudHeight, "cloudHeight");
            cloudRange = Math.max(0, cloudRange);
            cloudStatus = Math.max(0, cloudStatus);
            if (gameTime < 0L) gameTime = 0L;
            partialTicks = clamp01Finite(partialTicks, "partialTicks");
        }

        /**
         * 返回所有环境权威标志均关闭的共享状态。
         *
         * @return 未知环境状态
         */
        public static EnvironmentState unknown() {
            return UNKNOWN;
        }

        /**
         * 判断环境状态是否包含任何可渲染事实。
         *
         * @return 雾、云或天空任一已知时返回 {@code true}
         */
        public boolean hasRenderContent() {
            return fogKnown || cloudKnown || skyVisible;
        }

        /**
         * 将有界环境状态格式化为诊断字段。
         *
         * @return 单行诊断摘要
         */
        public String asLogFragment() {
            return "{fogKnown=" + fogKnown
                    + ", cloudKnown=" + cloudKnown
                    + ", cloudStatus=" + cloudStatus
                    + ", cloudRange=" + cloudRange
                    + ", skyVisible=" + skyVisible
                    + "}";
        }
    }
}
