package top.ceroxe.rt.renderer;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.joml.Matrix4fc;

import java.util.*;

/**
 * Frame-lifetime collector for non-terrain render facts.
 *
 * <p>host 26.2 extracts entities, dropped-item clusters, block entities,
 * particles, sky, and beams through renderer paths that are separate from
 * terrain sections. This collector is the renderer-owned boundary for those
 * facts: host adapters should convert host state into the immutable value
 * records from {@link DynamicRenderScene}, then submit those records here. The
 * collector deliberately owns revisioning, de-duplication, clear semantics, and
 * GPU-capacity clipping so individual hook sites cannot silently invent
 * incompatible lifetime rules.</p>
 */
public final class DynamicRenderSceneCollector {
    private static final CapturedModelObservationRegistry CAPTURED_MODELS =
            new CapturedModelObservationRegistry();

    private final Limits limits;
    private final LinkedHashMap<Long, DynamicRenderScene.DynamicPrimitive> primitives = new LinkedHashMap<>();
    /* Model instances use persistent RT slot identities, not the bounded analytic primitive lane. */
    private final Long2ObjectLinkedOpenHashMap<ModelInstanceSlot> modelInstances =
            new Long2ObjectLinkedOpenHashMap<>();
    private final ArrayList<ModelInstanceSlot> modelPhysicalSlots = new ArrayList<>();
    private final IntArrayList reusableModelPhysicalSlots = new IntArrayList();
    private final BitSet dirtyModelPhysicalSlots = new BitSet();
    private final Long2ObjectLinkedOpenHashMap<DynamicRenderScene.BillboardParticle> particles =
            new Long2ObjectLinkedOpenHashMap<>();
    private final Long2ObjectLinkedOpenHashMap<DynamicRenderScene.Beam> beams =
            new Long2ObjectLinkedOpenHashMap<>();
    private final Long2ObjectLinkedOpenHashMap<DynamicRenderScene.BlockDecal> blockDecals =
            new Long2ObjectLinkedOpenHashMap<>();
    private final ArrayList<DynamicRenderScene.WeatherColumn> weatherColumns = new ArrayList<>();
    private final EnumMap<DynamicRenderScene.CelestialKind, DynamicRenderScene.CelestialBody> celestialBodies =
            new EnumMap<>(DynamicRenderScene.CelestialKind.class);
    private final Long2ObjectLinkedOpenHashMap<DynamicRenderScene.SceneLight> lights =
            new Long2ObjectLinkedOpenHashMap<>();
    private int[] modelDirtyMasks = new int[16];
    private long modelMembershipRevision;
    private long modelTopologyRevision;
    private long modelTransformRevision;
    private long modelMaterialRevision;
    private long modelLightRevision;
    private List<DynamicRenderScene.DynamicModelInstance> lastModelMembershipPublication = List.of();
    private DynamicRenderScene.DynamicModelSlotSnapshot lastModelSlotSnapshot =
            DynamicRenderScene.DynamicModelSlotSnapshot.empty();
    private DynamicModelTransformSnapshot lastModelTransformSnapshot =
            DynamicModelTransformSnapshot.empty();
    private DynamicRenderScene.EnvironmentState environmentState = DynamicRenderScene.EnvironmentState.unknown();
    private long nextRevision = 1L;
    private boolean dirty;
    private boolean clearRequested;
    private boolean frameCollectionActive;
    private boolean persistentModelOwnership;
    private long frameCollectionEpoch;
    private ModelLaneSummary modelLaneSummary = ModelLaneSummary.empty();
    private int frameModelAdds;
    private int frameModelReuses;
    private int frameModelTransformChanges;
    private int frameModelMaterialChanges;
    private int frameModelLightChanges;
    private int frameModelTopologyChanges;
    private int frameModelRetires;
    private int frameModelDrops;
    private boolean lastDrainedHadRenderContent;
    private DynamicRenderScene lastDrainedScene = DynamicRenderScene.empty();
    private Summary lastDrainSummary = Summary.empty();

    /**
     * 使用 GPU 默认容量创建收集器。
     */
    public DynamicRenderSceneCollector() {
        this(Limits.gpuDefault());
    }

    /**
     * 使用显式容量限制创建收集器。
     *
     * @param limits 各动态通道的硬容量限制
     */
    public DynamicRenderSceneCollector(Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /**
     * Advances the producer-facing GPUScene slot epoch. Capture sessions do not own model
     * lifetime; they ask this renderer boundary for the current immutable publication value.
     *
     * @param nextFrameEpoch 严格递增的生产者帧纪元
     */
    public static void beginCapturedModelFrame(long nextFrameEpoch) {
        CAPTURED_MODELS.beginFrame(nextFrameEpoch);
    }

    /**
     * 返回生产者身份缓存当前保留的模型数量。
     *
     * @return 保留模型数量
     */
    public static int retainedCapturedModelInstanceCount() {
        return CAPTURED_MODELS.retainedCount();
    }

    /**
     * Retires the producer-side model identity cache at a renderer-world boundary.
     *
     * <p>The cache is not scene data, but it can carry immutable asset and material
     * values across frames. Retaining it through a world transition would permit a
     * late capture with a reused entity ID to revive data owned by the retired world.</p>
     */
    static void resetCapturedModelObservationsForWorld() {
        CAPTURED_MODELS.resetForWorld();
    }

    /**
     * Stages a model observation without manufacturing an immutable model object for animation.
     * The returned view is consumed synchronously at capture commit and must then be committed or
     * discarded through the matching lifecycle method below.
     *
     * @param id            稳定模型标识
     * @param kind          所有者语义
     * @param asset         共享网格资产
     * @param transform     对象到世界变换
     * @param faceMaterials 面材料列表
     * @param packedLight   打包光照
     * @param debugName     中性诊断名称
     * @param renderLane    实例深度通道
     * @param frameEpoch    当前生产者帧纪元
     * @return 仅在本次捕获提交期间有效的模型观察
     */
    public static DynamicRenderScene.DynamicModelObservation retainCapturedModelObservation(
            long id,
            DynamicRenderScene.PrimitiveKind kind,
            DynamicMeshAsset asset,
            DynamicMeshInstance.AffineTransform transform,
            List<DynamicMeshInstance.FaceMaterial> faceMaterials,
            int packedLight,
            String debugName,
            DynamicRenderLane renderLane,
            long frameEpoch
    ) {
        return CAPTURED_MODELS.stage(
                id, kind, asset, transform, faceMaterials, packedLight, debugName, renderLane, frameEpoch
        );
    }

    /**
     * 使用矩阵生产者输入暂存模型观察。
     *
     * @param id            稳定模型标识
     * @param kind          所有者语义
     * @param asset         共享网格资产
     * @param objectToWorld 对象到世界矩阵
     * @param faceMaterials 面材料列表
     * @param packedLight   打包光照
     * @param debugName     中性诊断名称
     * @param frameEpoch    当前生产者帧纪元
     * @return 仅在本次捕获提交期间有效的模型观察
     */
    public static DynamicRenderScene.DynamicModelObservation retainCapturedModelObservation(
            long id,
            DynamicRenderScene.PrimitiveKind kind,
            DynamicMeshAsset asset,
            Matrix4fc objectToWorld,
            List<DynamicMeshInstance.FaceMaterial> faceMaterials,
            int packedLight,
            String debugName,
            long frameEpoch
    ) {
        return CAPTURED_MODELS.stage(
                id, kind, asset, objectToWorld, faceMaterials, packedLight, debugName, frameEpoch
        );
    }

    /**
     * 提交暂存模型观察，使生产者缓存接受其最新通道状态。
     *
     * @param observation 由本收集器身份注册表暂存的观察
     */
    public static void commitCapturedModelObservation(DynamicRenderScene.DynamicModelObservation observation) {
        CAPTURED_MODELS.commit(observation);
    }

    /**
     * 丢弃暂存模型观察且不推进缓存状态。
     *
     * @param observation 由本收集器身份注册表暂存的观察
     */
    public static void discardCapturedModelObservation(DynamicRenderScene.DynamicModelObservation observation) {
        CAPTURED_MODELS.discard(observation);
    }

    /**
     * 复用或创建捕获模型实例，并由材料推导深度通道。
     *
     * @param id            稳定模型标识
     * @param kind          所有者语义
     * @param asset         共享网格资产
     * @param transform     对象到世界变换
     * @param faceMaterials 面材料列表
     * @param packedLight   打包光照
     * @param debugName     中性诊断名称
     * @param frameEpoch    当前生产者帧纪元
     * @return 可安全保留的不可变模型实例
     */
    public static DynamicRenderScene.DynamicModelInstance retainCapturedModelInstance(
            long id,
            DynamicRenderScene.PrimitiveKind kind,
            DynamicMeshAsset asset,
            DynamicMeshInstance.AffineTransform transform,
            List<DynamicMeshInstance.FaceMaterial> faceMaterials,
            int packedLight,
            String debugName,
            long frameEpoch
    ) {
        return retainCapturedModelInstance(
                id, kind, asset, transform, faceMaterials, packedLight, debugName, null, frameEpoch
        );
    }

    /**
     * 复用或创建具有显式深度通道的捕获模型实例。
     *
     * @param id            稳定模型标识
     * @param kind          所有者语义
     * @param asset         共享网格资产
     * @param transform     对象到世界变换
     * @param faceMaterials 面材料列表
     * @param packedLight   打包光照
     * @param debugName     中性诊断名称
     * @param renderLane    实例深度通道
     * @param frameEpoch    当前生产者帧纪元
     * @return 可安全保留的不可变模型实例
     */
    public static DynamicRenderScene.DynamicModelInstance retainCapturedModelInstance(
            long id,
            DynamicRenderScene.PrimitiveKind kind,
            DynamicMeshAsset asset,
            DynamicMeshInstance.AffineTransform transform,
            List<DynamicMeshInstance.FaceMaterial> faceMaterials,
            int packedLight,
            String debugName,
            DynamicRenderLane renderLane,
            long frameEpoch
    ) {
        return CAPTURED_MODELS.retain(
                id, kind, asset, transform, faceMaterials, packedLight, debugName, renderLane, frameEpoch
        );
    }

    /**
     * 使用矩阵生产者输入复用或创建捕获模型实例。
     *
     * @param id            稳定模型标识
     * @param kind          所有者语义
     * @param asset         共享网格资产
     * @param objectToWorld 对象到世界矩阵
     * @param faceMaterials 面材料列表
     * @param packedLight   打包光照
     * @param debugName     中性诊断名称
     * @param frameEpoch    当前生产者帧纪元
     * @return 可安全保留的不可变模型实例
     */
    public static DynamicRenderScene.DynamicModelInstance retainCapturedModelInstance(
            long id,
            DynamicRenderScene.PrimitiveKind kind,
            DynamicMeshAsset asset,
            Matrix4fc objectToWorld,
            List<DynamicMeshInstance.FaceMaterial> faceMaterials,
            int packedLight,
            String debugName,
            long frameEpoch
    ) {
        return CAPTURED_MODELS.retain(
                id, kind, asset, objectToWorld, faceMaterials, packedLight, debugName, frameEpoch
        );
    }

    private static long nextEpoch(long epoch) {
        return epoch == Long.MAX_VALUE ? 1L : epoch + 1L;
    }

    private static <T> List<T> selectNearest(
            Iterable<T> values,
            int limit,
            RendererFrameState frameState,
            PriorityPointFactory<T> pointFactory
    ) {
        if (limit <= 0) {
            return List.of();
        }
        ArrayList<T> candidates = new ArrayList<>();
        for (T value : values) {
            candidates.add(value);
        }
        if (candidates.size() <= limit) {
            return List.copyOf(candidates);
        }
        if (frameState.valid()) {
            candidates.sort(Comparator.comparingDouble(value ->
                    squaredDistance(pointFactory.point(value), frameState)));
        }
        return List.copyOf(candidates.subList(0, limit));
    }

    private static <T> List<T> firstValues(Iterable<T> values, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        /*
         * `limit` is a GPU capacity (16K for model slots), not evidence that
         * the current frame contains that many values. Reserving it for the
         * usual handful of entities allocated a 16K reference array every
         * frame. Preserve the bounded handoff while reserving only known input
         * cardinality, like bounded dirty upload batches.
         */
        int initialCapacity = values instanceof Collection<?> collection
                ? Math.min(limit, collection.size())
                : Math.min(limit, 16);
        ArrayList<T> selected = new ArrayList<>(initialCapacity);
        for (T value : values) {
            if (selected.size() >= limit) {
                break;
            }
            selected.add(value);
        }
        return List.copyOf(selected);
    }

    private static PriorityPoint primitivePriorityPoint(DynamicRenderScene.DynamicPrimitive primitive) {
        return new PriorityPoint(primitive.x(), primitive.y(), primitive.z());
    }

    private static PriorityPoint particlePriorityPoint(DynamicRenderScene.BillboardParticle particle) {
        return new PriorityPoint(particle.x(), particle.y(), particle.z());
    }

    private static PriorityPoint beamPriorityPoint(DynamicRenderScene.Beam beam) {
        return new PriorityPoint(
                (beam.startX() + beam.endX()) * 0.5D,
                (beam.startY() + beam.endY()) * 0.5D,
                (beam.startZ() + beam.endZ()) * 0.5D
        );
    }

    private static PriorityPoint blockDecalPriorityPoint(DynamicRenderScene.BlockDecal decal) {
        return new PriorityPoint(
                decal.blockX() + 0.5D,
                decal.blockY() + 0.5D,
                decal.blockZ() + 0.5D
        );
    }

    private static PriorityPoint weatherPriorityPoint(DynamicRenderScene.WeatherColumn column) {
        return new PriorityPoint(
                column.x() + 0.5D,
                (column.bottomY() + column.topY()) * 0.5D,
                column.z() + 0.5D
        );
    }

    private static PriorityPoint lightPriorityPoint(DynamicRenderScene.SceneLight light) {
        return new PriorityPoint(light.x(), light.y(), light.z());
    }

    private static double squaredDistance(PriorityPoint point, RendererFrameState frameState) {
        double dx = point.x() - frameState.cameraX();
        double dy = point.y() - frameState.cameraY();
        double dz = point.z() - frameState.cameraZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * 提交一个动态图元事实。
     *
     * @param primitive 本帧观察到的动态图元
     */
    public synchronized void submitPrimitive(DynamicRenderScene.DynamicPrimitive primitive) {
        DynamicRenderScene.DynamicPrimitive fact = Objects.requireNonNull(primitive, "primitive");
        beginContentUpdate();
        primitives.put(fact.id(), fact);
    }

    /**
     * 提交一个完整不可变模型实例。
     *
     * @param instance 本帧观察到的不可变模型实例
     */
    public synchronized void submitModelInstance(DynamicRenderScene.DynamicModelInstance instance) {
        submitModelObservation(instance);
    }

    /**
     * Consumes a split-lane model observation synchronously into the renderer-owned persistent slot.
     * Transform-only observations never materialize a second complete model object.
     *
     * @param observation 本帧观察到的拆分通道模型状态
     */
    public synchronized void submitModelObservation(DynamicRenderScene.DynamicModelObservation observation) {
        DynamicRenderScene.DynamicModelObservation fact = Objects.requireNonNull(observation, "observation");
        if (!CAPTURED_MODELS.accepts(fact)) {
            return;
        }
        ModelInstanceSlot slot = modelInstances.get(fact.id());
        if (slot == null) {
            if (modelInstances.size() >= limits.maxTlasPrimitives()) {
                ModelInstanceSlot retired = persistentModelOwnership ? removeFirstUnseenModelSlot() : null;
                if (retired != null) {
                    retireModelSlot(retired);
                    frameModelRetires++;
                    beginContentUpdate();
                } else {
                    frameModelDrops++;
                    return;
                }
            }
            int physicalSlot = allocateModelPhysicalSlot();
            slot = new ModelInstanceSlot(fact, observedModelEpoch(), physicalSlot);
            modelInstances.put(fact.id(), slot);
            modelPhysicalSlots.set(physicalSlot, slot);
            markModelSlotDirty(
                    physicalSlot,
                    DynamicRenderScene.DynamicModelFrameDelta.ADDED
                            | DynamicRenderScene.DynamicModelFrameDelta.TOPOLOGY
                            | DynamicRenderScene.DynamicModelFrameDelta.TRANSFORM
                            | DynamicRenderScene.DynamicModelFrameDelta.MATERIAL
                            | DynamicRenderScene.DynamicModelFrameDelta.LIGHT
            );
            frameModelAdds++;
            beginContentUpdate();
            return;
        }
        ModelLaneChanges changes = slot.observe(fact, observedModelEpoch());
        frameModelReuses++;
        frameModelTransformChanges += changes.transformChanged() ? 1 : 0;
        frameModelMaterialChanges += changes.materialChanged() ? 1 : 0;
        frameModelLightChanges += changes.lightChanged() ? 1 : 0;
        frameModelTopologyChanges += changes.topologyChanged() ? 1 : 0;
        int dirtyMask = (changes.topologyChanged()
                ? DynamicRenderScene.DynamicModelFrameDelta.TOPOLOGY : 0)
                | (changes.transformChanged()
                ? DynamicRenderScene.DynamicModelFrameDelta.TRANSFORM : 0)
                | (changes.materialChanged()
                ? DynamicRenderScene.DynamicModelFrameDelta.MATERIAL : 0)
                | (changes.lightChanged()
                ? DynamicRenderScene.DynamicModelFrameDelta.LIGHT : 0);
        if (dirtyMask != 0) {
            markModelSlotDirty(slot.physicalSlot(), dirtyMask);
        }
        if (changes.anyChanged()) {
            beginContentUpdate();
        }
    }

    /**
     * 提交一个公告板粒子事实。
     *
     * @param particle 本帧观察到的公告板粒子
     */
    public synchronized void submitParticle(DynamicRenderScene.BillboardParticle particle) {
        DynamicRenderScene.BillboardParticle fact = Objects.requireNonNull(particle, "particle");
        beginContentUpdate();
        particles.put(fact.id(), fact);
    }

    /**
     * 提交一个光束事实。
     *
     * @param beam 本帧观察到的光束
     */
    public synchronized void submitBeam(DynamicRenderScene.Beam beam) {
        DynamicRenderScene.Beam fact = Objects.requireNonNull(beam, "beam");
        beginContentUpdate();
        beams.put(fact.id(), fact);
    }

    /**
     * 提交一个稀疏区块贴花事实。
     *
     * @param decal 本帧观察到的稀疏区块贴花
     */
    public synchronized void submitBlockDecal(DynamicRenderScene.BlockDecal decal) {
        DynamicRenderScene.BlockDecal fact = Objects.requireNonNull(decal, "decal");
        beginContentUpdate();
        blockDecals.put(fact.stableId(), fact);
    }

    /**
     * 提交一个天气柱事实。
     *
     * @param column 本帧观察到的天气柱
     */
    public synchronized void submitWeatherColumn(DynamicRenderScene.WeatherColumn column) {
        DynamicRenderScene.WeatherColumn fact = Objects.requireNonNull(column, "column");
        beginContentUpdate();
        weatherColumns.add(fact);
    }

    /**
     * 提交一个天体事实。
     *
     * @param body 本帧观察到的天体
     */
    public synchronized void submitCelestialBody(DynamicRenderScene.CelestialBody body) {
        DynamicRenderScene.CelestialBody fact = Objects.requireNonNull(body, "body");
        beginContentUpdate();
        celestialBodies.put(fact.kind(), fact);
    }

    /**
     * 提交一个解析光源事实。
     *
     * @param light 本帧观察到的解析光源
     */
    public synchronized void submitLight(DynamicRenderScene.SceneLight light) {
        DynamicRenderScene.SceneLight fact = Objects.requireNonNull(light, "light");
        beginContentUpdate();
        lights.put(fact.id(), fact);
    }

    /**
     * 提交一份环境状态事实。
     *
     * @param state 本帧观察到的环境状态
     */
    public synchronized void submitEnvironmentState(DynamicRenderScene.EnvironmentState state) {
        DynamicRenderScene.EnvironmentState fact = Objects.requireNonNull(state, "state");
        if (!fact.hasRenderContent()) {
            return;
        }
        beginContentUpdate();
        environmentState = fact;
    }

    /**
     * 开始一次幂等的帧级动态事实收集。
     */
    public synchronized void beginFrameCollection() {
        /*
         * host 26.2 extracts and submits non-terrain render state as a
         * per-host level renderer frame collection. A production GPU-driven instance collector
         * has the same lifetime: a frame owns the temporary dynamic RT instances
         * gathered for that view. Once a hook site opts into this mode, stale
         * facts from an older frame must not survive merely because the new frame
         * contains no entities, particles, beams, or sky facts.
         *
         * LevelExtractor owns the normal begin hook, while host level renderer owns the
         * final frame facts and replacement decision. The renderer hook must be a
         * safe fallback when extraction injection is unavailable, so a repeated
         * begin in the same frame is intentionally idempotent instead of clearing
         * entity and particle facts already extracted for that frame.
         */
        if (frameCollectionActive) {
            return;
        }
        frameCollectionEpoch = nextEpoch(frameCollectionEpoch);
        persistentModelOwnership = true;
        resetFrameModelCounters();
        primitives.clear();
        particles.clear();
        beams.clear();
        blockDecals.clear();
        weatherColumns.clear();
        celestialBodies.clear();
        lights.clear();
        environmentState = DynamicRenderScene.EnvironmentState.unknown();
        clearRequested = false;
        dirty = false;
        frameCollectionActive = true;
    }

    /**
     * 结束当前帧收集并退休未再次观察到的持久模型。
     */
    public synchronized void endFrameCollection() {
        if (!frameCollectionActive) {
            return;
        }
        frameCollectionActive = false;
        retireUnseenModelInstances();
        modelLaneSummary = currentModelLaneSummary();
        if (!dirty && lastDrainedSceneHasTransientContent()) {
            /*
             * Persistent model slots must not mask retirement in frame-local
             * lanes. A frame with the same model owners but no particle/beam/
             * weather/etc. observations is an authoritative removal, not an
             * unchanged scene.
             */
            beginContentUpdate();
        }
        if (totalPendingElements() > 0) {
            return;
        }
        if (lastDrainedHadRenderContent) {
            clearRequested = true;
            dirty = true;
        }
    }

    /**
     * 请求下一次排出发布权威空场景。
     */
    public synchronized void requestClear() {
        primitives.clear();
        for (ModelInstanceSlot slot : modelInstances.values()) {
            retireModelSlot(slot);
        }
        modelInstances.clear();
        particles.clear();
        beams.clear();
        blockDecals.clear();
        weatherColumns.clear();
        celestialBodies.clear();
        lights.clear();
        environmentState = DynamicRenderScene.EnvironmentState.unknown();
        modelLaneSummary = currentModelLaneSummary();
        clearRequested = true;
        dirty = true;
    }

    /**
     * 判断是否必须在当前帧立即排出 TLAS 可见变化。
     *
     * @return 清空或几何成员变化待发布时返回 {@code true}
     */
    public synchronized boolean requiresImmediateDrain() {
        if (frameCollectionActive) {
            return false;
        }
        if (!dirty) {
            return false;
        }
        if (clearRequested) {
            return true;
        }
        if (!modelInstances.isEmpty()) {
            return true;
        }
        for (DynamicRenderScene.DynamicPrimitive primitive : primitives.values()) {
            if (primitive.usesTlasGeometry()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按容量与相机优先级排出一份不可变动态场景。
     *
     * @param frameState 用于距离裁剪的相机帧状态；{@code null} 表示不可用
     * @return 有变化时返回权威发布，否则返回共享空场景
     */
    public synchronized DynamicRenderScene drain(RendererFrameState frameState) {
        if (frameCollectionActive) {
            lastDrainSummary = Summary.empty();
            return DynamicRenderScene.empty();
        }
        if (!dirty) {
            lastDrainSummary = Summary.empty();
            return DynamicRenderScene.empty();
        }

        if (clearRequested || totalPendingElements() == 0) {
            long revision = nextRevision();
            DynamicRenderScene.DynamicModelFrameDelta modelDelta = buildModelFrameDelta();
            DynamicRenderScene clearScene = new DynamicRenderScene(
                    revision,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    DynamicRenderScene.EnvironmentState.unknown(),
                    LightmapPayload.unknown(),
                    lastModelMembershipPublication,
                    List.of(),
                    modelDelta
            );
            clearRequested = false;
            dirty = false;
            lastDrainedHadRenderContent = false;
            lastDrainedScene = clearScene;
            lastDrainSummary = new Summary(
                    revision,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    true
            );
            clearPublishedModelDelta();
            return clearScene;
        }

        RendererFrameState effectiveFrameState =
                frameState == null ? RendererFrameState.unavailable() : frameState;
        ArrayList<DynamicRenderScene.DynamicPrimitive> analyticCandidates = new ArrayList<>();
        ArrayList<DynamicRenderScene.DynamicPrimitive> tlasCandidates = new ArrayList<>();
        for (DynamicRenderScene.DynamicPrimitive primitive : primitives.values()) {
            (primitive.usesTlasGeometry() ? tlasCandidates : analyticCandidates).add(primitive);
        }
        List<DynamicRenderScene.DynamicPrimitive> selectedAnalyticPrimitives = selectNearest(
                analyticCandidates,
                limits.maxAnalyticPrimitives(),
                effectiveFrameState,
                DynamicRenderSceneCollector::primitivePriorityPoint
        );
        List<DynamicRenderScene.DynamicPrimitive> selectedTlasPrimitives = selectNearest(
                tlasCandidates,
                limits.maxTlasPrimitives(),
                effectiveFrameState,
                DynamicRenderSceneCollector::primitivePriorityPoint
        );
        LongOpenHashSet selectedPrimitiveIds = new LongOpenHashSet(
                selectedAnalyticPrimitives.size() + selectedTlasPrimitives.size()
        );
        for (DynamicRenderScene.DynamicPrimitive primitive : selectedAnalyticPrimitives) {
            selectedPrimitiveIds.add(primitive.id());
        }
        for (DynamicRenderScene.DynamicPrimitive primitive : selectedTlasPrimitives) {
            selectedPrimitiveIds.add(primitive.id());
        }
        ArrayList<DynamicRenderScene.DynamicPrimitive> selectedPrimitives =
                new ArrayList<>(selectedPrimitiveIds.size());
        for (DynamicRenderScene.DynamicPrimitive primitive : primitives.values()) {
            if (selectedPrimitiveIds.contains(primitive.id())) {
                selectedPrimitives.add(primitive);
            }
        }
        /*
         * sourceEngine extracts entities in raster-frustum traversal order. That
         * order changes when the camera turns even if the stable RT candidate
         * set, BLAS asset, and transform payload are unchanged. TLAS UPDATE
         * eligibility is positional in Vulkan instance input, so canonicalize
         * the handoff by persistent primitive ID before comparing generations.
         * This is the CPU counterpart of stable persistent GPU-scene instance slots:
         * only real membership changes alter topology; view traversal can
         * change transforms/material payload but never instance layout.
         */
        selectedPrimitives.sort(Comparator.comparingLong(DynamicRenderScene.DynamicPrimitive::id));
        /*
         * The legacy MODEL compatibility lane and the persistent model stream
         * share one Vulkan TLAS instance budget.  Reserve legacy selections
         * first so old hook sites retain their established behavior, then admit
         * canonical persistent slots without exceeding the native limit.
         */
        DynamicRenderScene.DynamicModelFrameDelta modelDelta = buildModelFrameDelta();
        List<DynamicRenderScene.DynamicModelInstance> selectedModelInstances = lastModelMembershipPublication;
        List<DynamicRenderScene.BillboardParticle> selectedParticles = selectNearest(
                particles.values(),
                limits.maxParticles(),
                effectiveFrameState,
                DynamicRenderSceneCollector::particlePriorityPoint
        );
        List<DynamicRenderScene.Beam> selectedBeams = selectNearest(
                beams.values(),
                limits.maxBeams(),
                effectiveFrameState,
                DynamicRenderSceneCollector::beamPriorityPoint
        );
        ArrayList<DynamicRenderScene.BlockDecal> selectedBlockDecals = new ArrayList<>(selectNearest(
                blockDecals.values(),
                DynamicRenderScene.MAX_GPU_BLOCK_DECALS,
                effectiveFrameState,
                DynamicRenderSceneCollector::blockDecalPriorityPoint
        ));
        /*
         * Open-addressed GPU slots depend on insertion order when coordinates
         * collide. Canonical BlockPos order keeps identical visible sets byte-
         * identical across sourceEngine traversal changes, so dirty-range upload
         * tracks real decal changes instead of rebuilding collision chains.
         */
        selectedBlockDecals.sort(Comparator.comparingLong(DynamicRenderScene.BlockDecal::stableId));
        List<DynamicRenderScene.WeatherColumn> selectedWeatherColumns = selectNearest(
                weatherColumns,
                limits.maxWeatherColumns(),
                effectiveFrameState,
                DynamicRenderSceneCollector::weatherPriorityPoint
        );
        List<DynamicRenderScene.CelestialBody> selectedCelestialBodies =
                firstValues(celestialBodies.values(), limits.maxCelestialBodies());
        List<DynamicRenderScene.SceneLight> selectedLights = selectNearest(
                lights.values(),
                limits.maxLights(),
                effectiveFrameState,
                DynamicRenderSceneCollector::lightPriorityPoint
        );
        DynamicRenderScene.EnvironmentState selectedEnvironmentState = environmentState;

        DynamicRenderScene scene = new DynamicRenderScene(
                peekNextRevision(),
                selectedPrimitives,
                selectedParticles,
                selectedBeams,
                selectedCelestialBodies,
                selectedLights,
                selectedWeatherColumns,
                selectedEnvironmentState,
                LightmapPayload.unknown(),
                selectedModelInstances,
                selectedBlockDecals,
                modelDelta
        );
        Summary summary = new Summary(
                scene.hasSameRenderPayload(lastDrainedScene) ? 0L : scene.revision(),
                selectedPrimitives.size(),
                selectedAnalyticPrimitives.size(),
                selectedTlasPrimitives.size(),
                selectedParticles.size(),
                selectedBeams.size(),
                selectedCelestialBodies.size(),
                selectedLights.size(),
                selectedWeatherColumns.size(),
                environmentState.hasRenderContent() ? 1 : 0,
                Math.max(0, primitives.size() - selectedPrimitives.size()),
                Math.max(0, analyticCandidates.size() - selectedAnalyticPrimitives.size()),
                Math.max(0, tlasCandidates.size() - selectedTlasPrimitives.size()),
                Math.max(0, particles.size() - selectedParticles.size()),
                Math.max(0, beams.size() - selectedBeams.size()),
                Math.max(0, celestialBodies.size() - selectedCelestialBodies.size()),
                Math.max(0, lights.size() - selectedLights.size()),
                Math.max(0, weatherColumns.size() - selectedWeatherColumns.size()),
                false
        );
        if (scene.hasSameRenderPayload(lastDrainedScene)) {
            clearPending();
            lastDrainSummary = summary;
            return DynamicRenderScene.empty();
        }
        long committedRevision = nextRevision();
        if (committedRevision != scene.revision()) {
            scene = new DynamicRenderScene(
                    committedRevision,
                    selectedPrimitives,
                    selectedParticles,
                    selectedBeams,
                    selectedCelestialBodies,
                    selectedLights,
                    selectedWeatherColumns,
                    selectedEnvironmentState,
                    LightmapPayload.unknown(),
                    selectedModelInstances,
                    selectedBlockDecals,
                    modelDelta
            );
            summary = new Summary(
                    committedRevision,
                    selectedPrimitives.size(),
                    selectedAnalyticPrimitives.size(),
                    selectedTlasPrimitives.size(),
                    selectedParticles.size(),
                    selectedBeams.size(),
                    selectedCelestialBodies.size(),
                    selectedLights.size(),
                    selectedWeatherColumns.size(),
                    environmentState.hasRenderContent() ? 1 : 0,
                    Math.max(0, primitives.size() - selectedPrimitives.size()),
                    Math.max(0, analyticCandidates.size() - selectedAnalyticPrimitives.size()),
                    Math.max(0, tlasCandidates.size() - selectedTlasPrimitives.size()),
                    Math.max(0, particles.size() - selectedParticles.size()),
                    Math.max(0, beams.size() - selectedBeams.size()),
                    Math.max(0, celestialBodies.size() - selectedCelestialBodies.size()),
                    Math.max(0, lights.size() - selectedLights.size()),
                    Math.max(0, weatherColumns.size() - selectedWeatherColumns.size()),
                    false
            );
        }
        clearPending();
        lastDrainSummary = summary;
        lastDrainedHadRenderContent = scene.hasRenderContent();
        lastDrainedScene = scene;
        return scene;
    }

    /**
     * 返回当前积压的动态元素数量。
     *
     * @return 待处理元素数
     */
    public synchronized int pendingElements() {
        return totalPendingElements();
    }

    /**
     * 返回最近一次排出的容量摘要。
     *
     * @return 最近排出摘要
     */
    public synchronized Summary lastDrainSummary() {
        return lastDrainSummary;
    }

    /**
     * 返回最近帧的模型拆分通道摘要。
     *
     * @return 模型通道摘要
     */
    public synchronized ModelLaneSummary modelLaneSummary() {
        return modelLaneSummary;
    }

    /**
     * 将收集器恢复到初始空状态。
     */
    public synchronized void reset() {
        clearAllPending();
        nextRevision = 1L;
        lastDrainedHadRenderContent = false;
        lastDrainedScene = DynamicRenderScene.empty();
        lastDrainSummary = Summary.empty();
    }

    synchronized void discardPending() {
        clearAllPending();
        lastDrainSummary = Summary.empty();
    }

    private void beginContentUpdate() {
        if (clearRequested) {
            clearRequested = false;
        }
        dirty = true;
    }

    private void clearPending() {
        primitives.clear();
        if (!persistentModelOwnership) {
            modelInstances.clear();
        }
        particles.clear();
        beams.clear();
        blockDecals.clear();
        weatherColumns.clear();
        celestialBodies.clear();
        lights.clear();
        environmentState = DynamicRenderScene.EnvironmentState.unknown();
        clearRequested = false;
        dirty = false;
        frameCollectionActive = false;
        clearPublishedModelDelta();
    }

    private void clearAllPending() {
        persistentModelOwnership = false;
        frameCollectionEpoch = 0L;
        modelInstances.clear();
        modelPhysicalSlots.clear();
        reusableModelPhysicalSlots.clear();
        dirtyModelPhysicalSlots.clear();
        Arrays.fill(modelDirtyMasks, 0);
        modelMembershipRevision = 0L;
        modelTopologyRevision = 0L;
        modelTransformRevision = 0L;
        modelMaterialRevision = 0L;
        modelLightRevision = 0L;
        lastModelMembershipPublication = List.of();
        lastModelSlotSnapshot = DynamicRenderScene.DynamicModelSlotSnapshot.empty();
        lastModelTransformSnapshot = DynamicModelTransformSnapshot.empty();
        modelLaneSummary = ModelLaneSummary.empty();
        resetFrameModelCounters();
        clearPending();
    }

    synchronized void rememberRetainedRenderContent(boolean retainedRenderContent) {
        lastDrainedHadRenderContent = retainedRenderContent;
    }

    private int totalPendingElements() {
        return primitives.size()
                + modelInstances.size()
                + particles.size()
                + beams.size()
                + blockDecals.size()
                + celestialBodies.size()
                + lights.size()
                + weatherColumns.size()
                + (environmentState.hasRenderContent() ? 1 : 0);
    }

    private long observedModelEpoch() {
        return persistentModelOwnership ? frameCollectionEpoch : 0L;
    }

    private boolean lastDrainedSceneHasTransientContent() {
        return !lastDrainedScene.primitives().isEmpty()
                || !lastDrainedScene.particles().isEmpty()
                || !lastDrainedScene.beams().isEmpty()
                || !lastDrainedScene.blockDecals().isEmpty()
                || !lastDrainedScene.weatherColumns().isEmpty()
                || !lastDrainedScene.celestialBodies().isEmpty()
                || !lastDrainedScene.lights().isEmpty()
                || lastDrainedScene.environmentState().hasRenderContent();
    }

    private void retireUnseenModelInstances() {
        if (!persistentModelOwnership || modelInstances.isEmpty()) {
            return;
        }
        var iterator = modelInstances.long2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            ModelInstanceSlot removed = iterator.next().getValue();
            if (removed.seenEpoch() == frameCollectionEpoch) {
                continue;
            }
            iterator.remove();
            retireModelSlot(removed);
            frameModelRetires++;
            beginContentUpdate();
        }
    }

    private ModelInstanceSlot removeFirstUnseenModelSlot() {
        var iterator = modelInstances.long2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            ModelInstanceSlot candidate = iterator.next().getValue();
            if (candidate.seenEpoch() == frameCollectionEpoch) {
                continue;
            }
            iterator.remove();
            return candidate;
        }
        return null;
    }

    private List<DynamicRenderScene.DynamicModelInstance> selectedModelInstances(int limit) {
        if (limit <= 0 || modelInstances.isEmpty()) {
            return List.of();
        }
        ArrayList<DynamicRenderScene.DynamicModelInstance> selected =
                new ArrayList<>(Math.min(limit, modelInstances.size()));
        for (ModelInstanceSlot slot : modelInstances.values()) {
            if (selected.size() >= limit) {
                break;
            }
            selected.add(slot.instance());
        }
        return List.copyOf(selected);
    }

    private DynamicRenderScene.DynamicModelFrameDelta buildModelFrameDelta() {
        int updateCount = dirtyModelPhysicalSlots.cardinality();
        if (modelPhysicalSlots.isEmpty() && updateCount == 0 && modelInstances.isEmpty()) {
            lastModelMembershipPublication = List.of();
            lastModelSlotSnapshot = DynamicRenderScene.DynamicModelSlotSnapshot.empty();
            lastModelTransformSnapshot = DynamicModelTransformSnapshot.empty();
            return DynamicRenderScene.DynamicModelFrameDelta.none();
        }

        boolean membershipDirty = false;
        boolean topologyDirty = false;
        boolean transformDirty = false;
        boolean materialDirty = false;
        boolean lightDirty = false;
        boolean refreshColdPublication = lastModelMembershipPublication.isEmpty() && !modelInstances.isEmpty();
        for (int slot = dirtyModelPhysicalSlots.nextSetBit(0);
             slot >= 0;
             slot = dirtyModelPhysicalSlots.nextSetBit(slot + 1)) {
            int mask = modelDirtyMasks[slot];
            membershipDirty |= (mask & (DynamicRenderScene.DynamicModelFrameDelta.ADDED
                    | DynamicRenderScene.DynamicModelFrameDelta.REMOVED)) != 0;
            topologyDirty |= (mask & DynamicRenderScene.DynamicModelFrameDelta.TOPOLOGY) != 0;
            transformDirty |= (mask & DynamicRenderScene.DynamicModelFrameDelta.TRANSFORM) != 0;
            materialDirty |= (mask & DynamicRenderScene.DynamicModelFrameDelta.MATERIAL) != 0;
            lightDirty |= (mask & DynamicRenderScene.DynamicModelFrameDelta.LIGHT) != 0;
            refreshColdPublication |= (mask & ~DynamicRenderScene.DynamicModelFrameDelta.TRANSFORM) != 0;
        }
        if (membershipDirty) {
            modelMembershipRevision = nextEpoch(modelMembershipRevision);
        }
        if (membershipDirty || topologyDirty) {
            modelTopologyRevision = nextEpoch(modelTopologyRevision);
        }
        if (transformDirty) {
            modelTransformRevision = nextEpoch(modelTransformRevision);
        }
        if (membershipDirty || materialDirty) {
            modelMaterialRevision = nextEpoch(modelMaterialRevision);
        }
        if (lightDirty) {
            modelLightRevision = nextEpoch(modelLightRevision);
        }
        if (membershipDirty
                || lastModelSlotSnapshot.membershipRevision() != modelMembershipRevision
                || lastModelSlotSnapshot.physicalSlotCount() != modelPhysicalSlots.size()
                || lastModelSlotSnapshot.activeSlotCount() != modelInstances.size()) {
            lastModelSlotSnapshot = buildModelSlotSnapshot();
        }

        int[] slots = new int[updateCount];
        byte[] masks = new byte[updateCount];
        DynamicRenderScene.DynamicModelInstance[] publications =
                new DynamicRenderScene.DynamicModelInstance[updateCount];
        float[] packedTransforms = new float[updateCount * 12];
        int update = 0;
        for (int slot = dirtyModelPhysicalSlots.nextSetBit(0);
             slot >= 0;
             slot = dirtyModelPhysicalSlots.nextSetBit(slot + 1)) {
            int mask = modelDirtyMasks[slot];
            ModelInstanceSlot modelSlot = modelPhysicalSlots.get(slot);
            DynamicRenderScene.DynamicModelInstance instance = modelSlot == null ? null : modelSlot.instance();
            slots[update] = slot;
            masks[update] = (byte) mask;
            if (instance != null
                    && (mask & ~DynamicRenderScene.DynamicModelFrameDelta.TRANSFORM) != 0) {
                publications[update] = instance;
            }
            if (modelSlot != null && (mask & DynamicRenderScene.DynamicModelFrameDelta.TRANSFORM) != 0) {
                for (int component = 0; component < 12; component++) {
                    packedTransforms[update * 12 + component] = modelSlot.transformValue(component);
                }
            }
            update++;
        }

        if (refreshColdPublication) {
            ArrayList<DynamicRenderScene.DynamicModelInstance> active =
                    new ArrayList<>(modelInstances.size());
            for (ModelInstanceSlot slot : modelInstances.values()) {
                active.add(slot.instance());
            }
            lastModelMembershipPublication = List.copyOf(active);
        }
        lastModelTransformSnapshot = lastModelTransformSnapshot.withUpdates(
                modelTransformRevision,
                modelPhysicalSlots.size(),
                slots,
                masks,
                packedTransforms
        );
        return DynamicRenderScene.DynamicModelFrameDelta.takeCollectorOwnership(
                modelMembershipRevision,
                modelTopologyRevision,
                modelTransformRevision,
                modelMaterialRevision,
                modelLightRevision,
                modelPhysicalSlots.size(),
                modelInstances.size(),
                lastModelSlotSnapshot,
                lastModelTransformSnapshot,
                slots,
                masks,
                publications,
                packedTransforms
        );
    }

    private DynamicRenderScene.DynamicModelSlotSnapshot buildModelSlotSnapshot() {
        int[] activeSlots = new int[modelInstances.size()];
        DynamicRenderScene.DynamicModelInstance[] instances =
                new DynamicRenderScene.DynamicModelInstance[modelInstances.size()];
        int active = 0;
        for (int slot = 0; slot < modelPhysicalSlots.size(); slot++) {
            ModelInstanceSlot modelSlot = modelPhysicalSlots.get(slot);
            if (modelSlot == null) {
                continue;
            }
            activeSlots[active] = slot;
            instances[active] = modelSlot.instance();
            active++;
        }
        if (active != modelInstances.size()) {
            throw new IllegalStateException("collector model membership table is internally inconsistent");
        }
        return new DynamicRenderScene.DynamicModelSlotSnapshot(
                modelMembershipRevision,
                modelPhysicalSlots.size(),
                activeSlots,
                instances
        );
    }

    private void clearPublishedModelDelta() {
        for (int slot = dirtyModelPhysicalSlots.nextSetBit(0);
             slot >= 0;
             slot = dirtyModelPhysicalSlots.nextSetBit(slot + 1)) {
            modelDirtyMasks[slot] = 0;
        }
        dirtyModelPhysicalSlots.clear();
    }

    private int allocateModelPhysicalSlot() {
        int slot;
        if (!reusableModelPhysicalSlots.isEmpty()) {
            slot = reusableModelPhysicalSlots.removeInt(reusableModelPhysicalSlots.size() - 1);
            if (slot < 0 || slot >= modelPhysicalSlots.size() || modelPhysicalSlots.get(slot) != null) {
                throw new IllegalStateException("collector model free-slot table is corrupt: " + slot);
            }
        } else {
            slot = modelPhysicalSlots.size();
            modelPhysicalSlots.add(null);
        }
        if (slot >= modelDirtyMasks.length) {
            modelDirtyMasks = Arrays.copyOf(modelDirtyMasks, Math.max(slot + 1, modelDirtyMasks.length * 2));
        }
        return slot;
    }

    private void retireModelSlot(ModelInstanceSlot slot) {
        if (slot == null) {
            return;
        }
        int physicalSlot = slot.physicalSlot();
        if (modelPhysicalSlots.get(physicalSlot) != slot) {
            throw new IllegalStateException("collector model physical slot ownership is corrupt: " + physicalSlot);
        }
        modelPhysicalSlots.set(physicalSlot, null);
        reusableModelPhysicalSlots.add(physicalSlot);
        markModelSlotDirty(
                physicalSlot,
                DynamicRenderScene.DynamicModelFrameDelta.REMOVED
                        | DynamicRenderScene.DynamicModelFrameDelta.TOPOLOGY
        );
    }

    private void markModelSlotDirty(int physicalSlot, int dirtyMask) {
        if (physicalSlot < 0 || physicalSlot >= modelPhysicalSlots.size() || dirtyMask == 0) {
            throw new IllegalArgumentException("invalid collector model dirty slot");
        }
        modelDirtyMasks[physicalSlot] |= dirtyMask;
        dirtyModelPhysicalSlots.set(physicalSlot);
    }

    private void resetFrameModelCounters() {
        frameModelAdds = 0;
        frameModelReuses = 0;
        frameModelTransformChanges = 0;
        frameModelMaterialChanges = 0;
        frameModelLightChanges = 0;
        frameModelTopologyChanges = 0;
        frameModelRetires = 0;
        frameModelDrops = 0;
    }

    private ModelLaneSummary currentModelLaneSummary() {
        return new ModelLaneSummary(
                modelInstances.size(),
                frameModelAdds,
                frameModelReuses,
                frameModelTransformChanges,
                frameModelMaterialChanges,
                frameModelLightChanges,
                frameModelTopologyChanges,
                frameModelRetires,
                frameModelDrops
        );
    }

    private long nextRevision() {
        if (nextRevision == Long.MAX_VALUE) {
            nextRevision = 1L;
        }
        return nextRevision++;
    }

    private long peekNextRevision() {
        return nextRevision == Long.MAX_VALUE ? 1L : nextRevision;
    }

    private interface PriorityPointFactory<T> {
        PriorityPoint point(T value);
    }

    private record PriorityPoint(double x, double y, double z) {
    }

    /**
     * Collector-owned persistent model slot. The slot identity and seen epoch
     * are lifecycle state; its immutable payload changes only when one RT lane
     * actually differs. This mirrors GPUScene primitive slots without exposing
     * mutable capture objects to asynchronous frame publication.
     */
    private static final class ModelInstanceSlot {
        private final float[] transform = new float[12];
        private final int physicalSlot;
        private DynamicRenderScene.DynamicModelInstance instance;
        private long seenEpoch;

        private ModelInstanceSlot(
                DynamicRenderScene.DynamicModelObservation observation,
                long seenEpoch,
                int physicalSlot
        ) {
            DynamicRenderScene.DynamicModelObservation initial = Objects.requireNonNull(observation, "observation");
            this.instance = initial.materialize();
            copyTransform(initial);
            this.seenEpoch = seenEpoch;
            if (physicalSlot < 0) {
                throw new IllegalArgumentException("model physical slot must not be negative");
            }
            this.physicalSlot = physicalSlot;
        }

        private DynamicRenderScene.DynamicModelInstance instance() {
            return instance;
        }

        private long seenEpoch() {
            return seenEpoch;
        }

        private int physicalSlot() {
            return physicalSlot;
        }

        private float transformValue(int component) {
            return transform[component];
        }

        private ModelLaneChanges observe(DynamicRenderScene.DynamicModelObservation next, long epoch) {
            Objects.requireNonNull(next, "next");
            DynamicRenderScene.DynamicModelInstance previous = instance;
            boolean topologyChanged = previous.kind() != next.kind()
                    || (previous.asset() != next.asset() && !previous.asset().equals(next.asset()))
                    || (previous.debugName() != next.debugName()
                    && !previous.debugName().equals(next.debugName()));
            boolean transformChanged = !transformMatches(next);
            boolean materialChanged = previous.renderLane() != next.renderLane()
                    || (previous.faceMaterials() != next.faceMaterials()
                    && !previous.faceMaterials().equals(next.faceMaterials()));
            boolean lightChanged = previous.packedLight() != next.packedLight();
            seenEpoch = epoch;
            ModelLaneChanges changes = new ModelLaneChanges(
                    topologyChanged,
                    transformChanged,
                    materialChanged,
                    lightChanged
            );
            if (topologyChanged || materialChanged || lightChanged) {
                instance = next.materialize();
            }
            if (transformChanged) {
                copyTransform(next);
            }
            return changes;
        }

        private boolean transformMatches(DynamicRenderScene.DynamicModelObservation observation) {
            for (int component = 0; component < transform.length; component++) {
                if (Float.floatToIntBits(transform[component])
                        != Float.floatToIntBits(observation.transformValue(component))) {
                    return false;
                }
            }
            return true;
        }

        private void copyTransform(DynamicRenderScene.DynamicModelObservation observation) {
            for (int component = 0; component < transform.length; component++) {
                transform[component] = observation.transformValue(component);
            }
        }
    }

    private record ModelLaneChanges(
            boolean topologyChanged,
            boolean transformChanged,
            boolean materialChanged,
            boolean lightChanged
    ) {
        private boolean anyChanged() {
            return topologyChanged || transformChanged || materialChanged || lightChanged;
        }
    }

    /**
     * 一帧持久模型槽拆分通道的有界计数摘要。
     *
     * @param activeSlots         活动槽数
     * @param addedSlots          新增槽数
     * @param reusedSlots         完全复用槽数
     * @param transformDirtySlots 变换变化槽数
     * @param materialDirtySlots  材料变化槽数
     * @param lightDirtySlots     光照变化槽数
     * @param topologyDirtySlots  拓扑变化槽数
     * @param retiredSlots        退休槽数
     * @param droppedSlots        因容量丢弃的槽数
     */
    public record ModelLaneSummary(
            int activeSlots,
            int addedSlots,
            int reusedSlots,
            int transformDirtySlots,
            int materialDirtySlots,
            int lightDirtySlots,
            int topologyDirtySlots,
            int retiredSlots,
            int droppedSlots
    ) {
        /**
         * 校验所有通道计数均为非负数。
         */
        public ModelLaneSummary {
            if (activeSlots < 0 || addedSlots < 0 || reusedSlots < 0 || transformDirtySlots < 0
                    || materialDirtySlots < 0 || lightDirtySlots < 0 || topologyDirtySlots < 0
                    || retiredSlots < 0 || droppedSlots < 0) {
                throw new IllegalArgumentException("model lane counters must not be negative");
            }
        }

        /**
         * 返回全部计数为零的摘要。
         *
         * @return 空模型通道摘要
         */
        public static ModelLaneSummary empty() {
            return new ModelLaneSummary(0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        /**
         * Exposes only bounded ownership counters. This is deliberately separate from the
         * scene publication so pressure telemetry cannot retain dynamic model instances.
         *
         * @return 单行模型通道诊断摘要
         */
        public String asLogFragment() {
            return "dynamicModelLane{activeSlots=" + activeSlots
                    + ", addedSlots=" + addedSlots
                    + ", reusedSlots=" + reusedSlots
                    + ", transformDirtySlots=" + transformDirtySlots
                    + ", materialDirtySlots=" + materialDirtySlots
                    + ", lightDirtySlots=" + lightDirtySlots
                    + ", topologyDirtySlots=" + topologyDirtySlots
                    + ", retiredSlots=" + retiredSlots
                    + ", droppedSlots=" + droppedSlots
                    + "}";
        }
    }

    /**
     * 动态场景各 GPU 通道的硬容量限制。
     *
     * @param maxAnalyticPrimitives 最大解析图元数
     * @param maxTlasPrimitives     最大 TLAS 图元数
     * @param maxParticles          最大粒子数
     * @param maxBeams              最大光束数
     * @param maxCelestialBodies    最大天体数
     * @param maxLights             最大解析光源数
     * @param maxWeatherColumns     最大天气柱数
     */
    public record Limits(
            int maxAnalyticPrimitives,
            int maxTlasPrimitives,
            int maxParticles,
            int maxBeams,
            int maxCelestialBodies,
            int maxLights,
            int maxWeatherColumns
    ) {
        /**
         * 校验所有容量限制均为正数。
         */
        public Limits {
            requirePositive(maxAnalyticPrimitives, "maxAnalyticPrimitives");
            requirePositive(maxTlasPrimitives, "maxTlasPrimitives");
            requirePositive(maxParticles, "maxParticles");
            requirePositive(maxBeams, "maxBeams");
            requirePositive(maxCelestialBodies, "maxCelestialBodies");
            requirePositive(maxLights, "maxLights");
            requirePositive(maxWeatherColumns, "maxWeatherColumns");
        }

        /**
         * 返回与 {@link DynamicRenderScene} GPU ABI 一致的默认限制。
         *
         * @return GPU 默认容量限制
         */
        public static Limits gpuDefault() {
            return new Limits(
                    DynamicRenderScene.MAX_GPU_PRIMITIVES,
                    DynamicRenderScene.MAX_TLAS_MODEL_PRIMITIVES,
                    DynamicRenderScene.MAX_GPU_PARTICLES,
                    DynamicRenderScene.MAX_GPU_BEAMS,
                    DynamicRenderScene.MAX_GPU_CELESTIAL_BODIES,
                    DynamicRenderScene.MAX_GPU_LIGHTS,
                    DynamicRenderScene.MAX_GPU_WEATHER_COLUMNS
            );
        }

        private static void requirePositive(int value, String name) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
        }
    }

    /**
     * 最近一次动态场景排出的选择与容量裁剪摘要。
     *
     * @param revision                  实际发布修订号；无发布时为零
     * @param primitives                已选图元总数
     * @param analyticPrimitives        已选解析图元数
     * @param tlasPrimitives            已选 TLAS 图元数
     * @param particles                 已选粒子数
     * @param beams                     已选光束数
     * @param celestialBodies           已选天体数
     * @param lights                    已选光源数
     * @param weatherColumns            已选天气柱数
     * @param environmentStates         已选环境状态数
     * @param droppedPrimitives         丢弃图元总数
     * @param droppedAnalyticPrimitives 丢弃解析图元数
     * @param droppedTlasPrimitives     丢弃 TLAS 图元数
     * @param droppedParticles          丢弃粒子数
     * @param droppedBeams              丢弃光束数
     * @param droppedCelestialBodies    丢弃天体数
     * @param droppedLights             丢弃光源数
     * @param droppedWeatherColumns     丢弃天气柱数
     * @param clear                     是否发布了权威清空
     */
    public record Summary(
            long revision,
            int primitives,
            int analyticPrimitives,
            int tlasPrimitives,
            int particles,
            int beams,
            int celestialBodies,
            int lights,
            int weatherColumns,
            int environmentStates,
            int droppedPrimitives,
            int droppedAnalyticPrimitives,
            int droppedTlasPrimitives,
            int droppedParticles,
            int droppedBeams,
            int droppedCelestialBodies,
            int droppedLights,
            int droppedWeatherColumns,
            boolean clear
    ) {
        /**
         * 校验摘要计数及图元分项恒等式。
         */
        public Summary {
            if (revision < 0L) {
                throw new IllegalArgumentException("revision must not be negative");
            }
            requireNonNegative(primitives, "primitives");
            requireNonNegative(analyticPrimitives, "analyticPrimitives");
            requireNonNegative(tlasPrimitives, "tlasPrimitives");
            requireNonNegative(particles, "particles");
            requireNonNegative(beams, "beams");
            requireNonNegative(celestialBodies, "celestialBodies");
            requireNonNegative(lights, "lights");
            requireNonNegative(weatherColumns, "weatherColumns");
            requireNonNegative(environmentStates, "environmentStates");
            requireNonNegative(droppedPrimitives, "droppedPrimitives");
            requireNonNegative(droppedAnalyticPrimitives, "droppedAnalyticPrimitives");
            requireNonNegative(droppedTlasPrimitives, "droppedTlasPrimitives");
            requireNonNegative(droppedParticles, "droppedParticles");
            requireNonNegative(droppedBeams, "droppedBeams");
            requireNonNegative(droppedCelestialBodies, "droppedCelestialBodies");
            requireNonNegative(droppedLights, "droppedLights");
            requireNonNegative(droppedWeatherColumns, "droppedWeatherColumns");
            if ((long) analyticPrimitives + tlasPrimitives != primitives) {
                throw new IllegalArgumentException("primitive split must equal total primitives");
            }
            if ((long) droppedAnalyticPrimitives + droppedTlasPrimitives != droppedPrimitives) {
                throw new IllegalArgumentException("dropped primitive split must equal total dropped primitives");
            }
        }

        /**
         * 返回全部计数为零且不表示清空的摘要。
         *
         * @return 空排出摘要
         */
        public static Summary empty() {
            return new Summary(0L, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false);
        }

        private static void requireNonNegative(int value, String name) {
            if (value < 0) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
        }

        /**
         * 汇总所有因容量裁剪而丢弃的元素。
         *
         * @return 丢弃元素总数
         */
        public int droppedElements() {
            return droppedPrimitives
                    + droppedParticles
                    + droppedBeams
                    + droppedCelestialBodies
                    + droppedLights
                    + droppedWeatherColumns;
        }

        /**
         * 将选择与丢弃计数格式化为诊断字段。
         *
         * @return 单行收集器摘要
         */
        public String asLogFragment() {
            return "dynamicCollector{revision=" + revision
                    + ", primitives=" + primitives
                    + ", analyticPrimitives=" + analyticPrimitives
                    + ", tlasPrimitives=" + tlasPrimitives
                    + ", particles=" + particles
                    + ", beams=" + beams
                    + ", celestialBodies=" + celestialBodies
                    + ", lights=" + lights
                    + ", weatherColumns=" + weatherColumns
                    + ", environmentStates=" + environmentStates
                    + ", droppedPrimitives=" + droppedPrimitives
                    + ", droppedAnalyticPrimitives=" + droppedAnalyticPrimitives
                    + ", droppedTlasPrimitives=" + droppedTlasPrimitives
                    + ", droppedParticles=" + droppedParticles
                    + ", droppedBeams=" + droppedBeams
                    + ", droppedCelestialBodies=" + droppedCelestialBodies
                    + ", droppedLights=" + droppedLights
                    + ", droppedWeatherColumns=" + droppedWeatherColumns
                    + ", droppedElements=" + droppedElements()
                    + ", clear=" + clear
                    + "}";
        }
    }
}
