package top.ceroxe.rt.renderer;

import top.ceroxe.rt.renderer.scene.*;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 帧末 renderer update loop 给 RTCore 的窄输入。
 *
 * <p>RTCore 只能消费这个不可变结果，不能回头读取 hook pending set，也不能持有
 * host chunk/block 对象。后续 BLAS/TLAS builder 会从这里拿本帧新增 mesh 和
 * removed section key。</p>
 *
 * @param batch           immutable scene update batch
 * @param materialResult  material-cache application result, or {@code null} for an empty batch
 * @param geometryResult  geometry-cache application result, or {@code null} for an empty batch
 * @param meshResult      mesh-cache application result, or {@code null} for an empty batch
 * @param frameState      immutable render-thread frame state
 * @param backlogSnapshot immutable orchestration backlog snapshot
 * @param dynamicScene    immutable dynamic-scene state
 * @param commitPlan      immutable native commit plan matching this payload
 */
public record RendererFrameUpdate(
        SceneUpdateBatch batch,
        SectionMaterialCache.ApplyResult materialResult,
        SectionGeometryCache.ApplyResult geometryResult,
        SectionMeshCache.ApplyResult meshResult,
        RendererFrameState frameState,
        RendererUpdateLoop.BacklogSnapshot backlogSnapshot,
        DynamicRenderScene dynamicScene,
        RendererFrameCommitPlan commitPlan
) {
    /**
     * 使用默认的不可用帧状态、空积压快照和空动态场景创建帧更新。
     *
     * @param batch          不可变场景更新批次
     * @param materialResult 材质缓存应用结果；空批次必须为 {@code null}
     * @param geometryResult 几何缓存应用结果；空批次必须为 {@code null}
     * @param meshResult     mesh 缓存应用结果；空批次必须为 {@code null}
     * @throws NullPointerException     {@code batch} 为 {@code null}，或非空批次缺少缓存结果时
     * @throws IllegalArgumentException 空批次携带缓存结果，或生成的提交计划与负载不匹配时
     */
    public RendererFrameUpdate(
            SceneUpdateBatch batch,
            SectionMaterialCache.ApplyResult materialResult,
            SectionGeometryCache.ApplyResult geometryResult,
            SectionMeshCache.ApplyResult meshResult
    ) {
        this(
                batch,
                materialResult,
                geometryResult,
                meshResult,
                RendererFrameState.unavailable(),
                RendererUpdateLoop.BacklogSnapshot.empty(),
                DynamicRenderScene.empty()
        );
    }

    /**
     * 使用空积压快照和空动态场景创建帧更新。
     *
     * @param batch          不可变场景更新批次
     * @param materialResult 材质缓存应用结果；空批次必须为 {@code null}
     * @param geometryResult 几何缓存应用结果；空批次必须为 {@code null}
     * @param meshResult     mesh 缓存应用结果；空批次必须为 {@code null}
     * @param frameState     不可变渲染线程帧状态；{@code null} 按不可用状态处理
     * @throws NullPointerException     {@code batch} 为 {@code null}，或非空批次缺少缓存结果时
     * @throws IllegalArgumentException 空批次携带缓存结果，或生成的提交计划与负载不匹配时
     */
    public RendererFrameUpdate(
            SceneUpdateBatch batch,
            SectionMaterialCache.ApplyResult materialResult,
            SectionGeometryCache.ApplyResult geometryResult,
            SectionMeshCache.ApplyResult meshResult,
            RendererFrameState frameState
    ) {
        this(
                batch,
                materialResult,
                geometryResult,
                meshResult,
                frameState,
                RendererUpdateLoop.BacklogSnapshot.empty(),
                DynamicRenderScene.empty()
        );
    }

    /**
     * 使用空动态场景创建包含显式积压快照的帧更新。
     *
     * @param batch           不可变场景更新批次
     * @param materialResult  材质缓存应用结果；空批次必须为 {@code null}
     * @param geometryResult  几何缓存应用结果；空批次必须为 {@code null}
     * @param meshResult      mesh 缓存应用结果；空批次必须为 {@code null}
     * @param frameState      不可变渲染线程帧状态；{@code null} 按不可用状态处理
     * @param backlogSnapshot 不可变编排积压快照；{@code null} 按空快照处理
     * @throws NullPointerException     {@code batch} 为 {@code null}，或非空批次缺少缓存结果时
     * @throws IllegalArgumentException 空批次携带缓存结果，或生成的提交计划与负载不匹配时
     */
    public RendererFrameUpdate(
            SceneUpdateBatch batch,
            SectionMaterialCache.ApplyResult materialResult,
            SectionGeometryCache.ApplyResult geometryResult,
            SectionMeshCache.ApplyResult meshResult,
            RendererFrameState frameState,
            RendererUpdateLoop.BacklogSnapshot backlogSnapshot
    ) {
        this(
                batch,
                materialResult,
                geometryResult,
                meshResult,
                frameState,
                backlogSnapshot,
                DynamicRenderScene.empty()
        );
    }

    /**
     * 创建帧更新并从其负载推导原生提交计划。
     *
     * @param batch           不可变场景更新批次
     * @param materialResult  材质缓存应用结果；空批次必须为 {@code null}
     * @param geometryResult  几何缓存应用结果；空批次必须为 {@code null}
     * @param meshResult      mesh 缓存应用结果；空批次必须为 {@code null}
     * @param frameState      不可变渲染线程帧状态；{@code null} 按不可用状态处理
     * @param backlogSnapshot 不可变编排积压快照；{@code null} 按空快照处理
     * @param dynamicScene    不可变动态场景状态；{@code null} 按空场景处理
     * @throws NullPointerException     {@code batch} 为 {@code null}，或非空批次缺少缓存结果时
     * @throws IllegalArgumentException 空批次携带缓存结果，或生成的提交计划与负载不匹配时
     */
    public RendererFrameUpdate(
            SceneUpdateBatch batch,
            SectionMaterialCache.ApplyResult materialResult,
            SectionGeometryCache.ApplyResult geometryResult,
            SectionMeshCache.ApplyResult meshResult,
            RendererFrameState frameState,
            RendererUpdateLoop.BacklogSnapshot backlogSnapshot,
            DynamicRenderScene dynamicScene
    ) {
        this(
                batch,
                materialResult,
                geometryResult,
                meshResult,
                frameState,
                backlogSnapshot,
                dynamicScene,
                RendererFrameCommitPlan.from(batch, materialResult, meshResult, dynamicScene)
        );
    }

    /**
     * 校验并规范化完整的不可变帧更新负载。
     *
     * @param batch           不可变场景更新批次
     * @param materialResult  材质缓存应用结果；空批次必须为 {@code null}
     * @param geometryResult  几何缓存应用结果；空批次必须为 {@code null}
     * @param meshResult      mesh 缓存应用结果；空批次必须为 {@code null}
     * @param frameState      不可变渲染线程帧状态；{@code null} 按不可用状态处理
     * @param backlogSnapshot 不可变编排积压快照；{@code null} 按空快照处理
     * @param dynamicScene    不可变动态场景状态；{@code null} 按空场景处理
     * @param commitPlan      与当前负载匹配的提交计划；{@code null} 时自动推导
     * @throws NullPointerException     {@code batch} 为 {@code null}，或非空批次缺少缓存结果时
     * @throws IllegalArgumentException 空批次携带缓存结果，或提交计划与负载不匹配时
     */
    public RendererFrameUpdate {
        batch = Objects.requireNonNull(batch, "batch");
        frameState = frameState == null ? RendererFrameState.unavailable() : frameState;
        backlogSnapshot = backlogSnapshot == null ? RendererUpdateLoop.BacklogSnapshot.empty() : backlogSnapshot;
        dynamicScene = dynamicScene == null ? DynamicRenderScene.empty() : dynamicScene;
        commitPlan = commitPlan == null
                ? RendererFrameCommitPlan.from(batch, materialResult, meshResult, dynamicScene)
                : commitPlan;
        if (batch.hasChanges()) {
            materialResult = Objects.requireNonNull(materialResult, "materialResult");
            geometryResult = Objects.requireNonNull(geometryResult, "geometryResult");
            meshResult = Objects.requireNonNull(meshResult, "meshResult");
        } else if (materialResult != null || geometryResult != null || meshResult != null) {
            throw new IllegalArgumentException("empty frame update must not carry cache apply results");
        }
        if (!commitPlan.matchesPayload(batch, materialResult, meshResult, dynamicScene)) {
            throw new IllegalArgumentException("frame commit plan must match frame update payload");
        }
    }

    /**
     * 使用默认帧状态创建不含任何场景变化的更新。
     *
     * @param batch 不含场景变化的更新批次
     * @return 规范化后的空帧更新
     * @throws NullPointerException {@code batch} 为 {@code null} 或包含地形变化时
     */
    public static RendererFrameUpdate empty(SceneUpdateBatch batch) {
        return empty(batch, RendererFrameState.unavailable());
    }

    /**
     * 使用显式帧状态创建不含任何场景变化的更新。
     *
     * @param batch      不含场景变化的更新批次
     * @param frameState 不可变渲染线程帧状态；{@code null} 按不可用状态处理
     * @return 规范化后的空帧更新
     * @throws NullPointerException {@code batch} 为 {@code null} 或包含地形变化时
     */
    public static RendererFrameUpdate empty(SceneUpdateBatch batch, RendererFrameState frameState) {
        return empty(batch, frameState, RendererUpdateLoop.BacklogSnapshot.empty());
    }

    /**
     * 使用显式帧状态和积压快照创建不含场景变化的更新。
     *
     * @param batch           不含场景变化的更新批次
     * @param frameState      不可变渲染线程帧状态；{@code null} 按不可用状态处理
     * @param backlogSnapshot 不可变编排积压快照；{@code null} 按空快照处理
     * @return 规范化后的空帧更新
     * @throws NullPointerException {@code batch} 为 {@code null} 或包含地形变化时
     */
    public static RendererFrameUpdate empty(
            SceneUpdateBatch batch,
            RendererFrameState frameState,
            RendererUpdateLoop.BacklogSnapshot backlogSnapshot
    ) {
        return new RendererFrameUpdate(batch, null, null, null, frameState, backlogSnapshot, DynamicRenderScene.empty());
    }

    /**
     * 创建只包含动态场景变化、不包含地形缓存结果的帧更新。
     *
     * @param batch           不含地形变化的更新批次
     * @param frameState      不可变渲染线程帧状态；{@code null} 按不可用状态处理
     * @param backlogSnapshot 不可变编排积压快照；{@code null} 按空快照处理
     * @param dynamicScene    至少包含一次场景更新的动态场景状态
     * @return 规范化后的动态场景帧更新
     * @throws NullPointerException     {@code batch} 或 {@code dynamicScene} 为 {@code null} 时
     * @throws IllegalArgumentException 批次包含地形变化，或动态场景不含更新时
     */
    public static RendererFrameUpdate dynamicOnly(
            SceneUpdateBatch batch,
            RendererFrameState frameState,
            RendererUpdateLoop.BacklogSnapshot backlogSnapshot,
            DynamicRenderScene dynamicScene
    ) {
        Objects.requireNonNull(batch, "batch");
        if (batch.hasChanges()) {
            throw new IllegalArgumentException("dynamic-only frame update must not carry terrain changes");
        }
        DynamicRenderScene scene = Objects.requireNonNull(dynamicScene, "dynamicScene");
        if (!scene.hasSceneUpdate()) {
            throw new IllegalArgumentException("dynamic-only frame update requires a dynamic scene update");
        }
        return new RendererFrameUpdate(batch, null, null, null, frameState, backlogSnapshot, scene);
    }

    /**
     * 将已缓存的 RT mesh 回填批次转换为一帧可提交更新。
     *
     * @param backfill        至少包含一个 mesh 的回填批次
     * @param frameState      不可变渲染线程帧状态；{@code null} 按不可用状态处理
     * @param backlogSnapshot 不可变编排积压快照；{@code null} 按空快照处理
     * @return 与回填 mesh 及其缓存摘要一致的帧更新
     * @throws NullPointerException     {@code backfill} 为 {@code null} 时
     * @throws IllegalArgumentException 回填批次为空或内部摘要与 mesh 不一致时
     */
    public static RendererFrameUpdate rtMeshBackfill(
            SectionMeshCache.BackfillBatch backfill,
            RendererFrameState frameState,
            RendererUpdateLoop.BacklogSnapshot backlogSnapshot
    ) {
        Objects.requireNonNull(backfill, "backfill");
        if (backfill.isEmpty()) {
            throw new IllegalArgumentException("RT mesh backfill update must contain meshes");
        }

        Map<SectionKey, SectionTriangleMesh> meshes = backfill.meshes();
        Set<ChunkKey> dirtyChunks = new HashSet<>();
        for (SectionKey key : meshes.keySet()) {
            dirtyChunks.add(key.chunkKey());
        }

        SceneUpdateBatch batch = new SceneUpdateBatch(
                meshes.keySet(),
                dirtyChunks,
                Set.of(),
                Set.of(),
                Map.of(),
                false,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L
        );
        SectionMeshCache.Summary summary = backfill.cacheSummary();
        return new RendererFrameUpdate(
                batch,
                new SectionMaterialCache.ApplyResult(
                        0L,
                        0,
                        0,
                        0,
                        0L,
                        0L,
                        0,
                        0L,
                        0L,
                        0L,
                        0L,
                        false,
                        0L,
                        SectionMaterialCache.MaterialFacts.empty(),
                        Map.of()
                ),
                new SectionGeometryCache.ApplyResult(
                        0L,
                        0,
                        0,
                        0,
                        0,
                        0L,
                        0,
                        0L,
                        0L,
                        0L,
                        0L,
                        false,
                        0L,
                        0L,
                        Map.of()
                ),
                new SectionMeshCache.ApplyResult(
                        summary.appliedBatches(),
                        meshes.size(),
                        0,
                        backfill.trianglesInBatch(),
                        backfill.estimatedBytesInBatch(),
                        summary.cachedSections(),
                        summary.knownRenderableSections(),
                        summary.totalRemovedSections(),
                        0,
                        summary.totalEvictedSections(),
                        summary.cachedEstimatedBytes(),
                        summary.peakCachedEstimatedBytes(),
                        summary.budgetBytes(),
                        summary.overBudget(),
                        summary.totalTrianglesBuilt(),
                        summary.totalEstimatedBytes(),
                        summary.fullResyncClears(),
                        meshes
                ),
                frameState,
                backlogSnapshot,
                DynamicRenderScene.empty()
        );
    }

    /**
     * 判断该帧是否包含地形或动态场景变化。
     *
     * @return 存在任一类场景变化时返回 {@code true}
     */
    public boolean hasChanges() {
        return hasTerrainChanges() || hasDynamicSceneUpdate();
    }

    /**
     * 判断该帧是否包含地形变化。
     *
     * @return 场景更新批次包含地形变化时返回 {@code true}
     */
    public boolean hasTerrainChanges() {
        return batch.hasChanges();
    }

    /**
     * 判断该帧是否包含动态场景更新事件。
     *
     * @return 动态场景声明了更新时返回 {@code true}
     */
    public boolean hasDynamicSceneUpdate() {
        return dynamicScene.hasSceneUpdate();
    }

    /**
     * 判断该帧是否包含着色器可见的动态内容。
     *
     * @return 动态场景包含渲染内容时返回 {@code true}
     */
    public boolean hasDynamicContent() {
        return dynamicScene.hasRenderContent();
    }
}
