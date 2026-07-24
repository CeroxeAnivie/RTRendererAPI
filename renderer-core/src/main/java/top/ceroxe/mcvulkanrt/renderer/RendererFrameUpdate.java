package top.ceroxe.mcvulkanrt.renderer;

import top.ceroxe.mcvulkanrt.renderer.scene.SceneUpdateBatch;
import top.ceroxe.mcvulkanrt.renderer.scene.ChunkKey;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionGeometryCache;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionMaterialCache;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionMeshCache;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionTriangleMesh;

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

    public static RendererFrameUpdate empty(SceneUpdateBatch batch) {
        return empty(batch, RendererFrameState.unavailable());
    }

    public static RendererFrameUpdate empty(SceneUpdateBatch batch, RendererFrameState frameState) {
        return empty(batch, frameState, RendererUpdateLoop.BacklogSnapshot.empty());
    }

    public static RendererFrameUpdate empty(
            SceneUpdateBatch batch,
            RendererFrameState frameState,
            RendererUpdateLoop.BacklogSnapshot backlogSnapshot
    ) {
        return new RendererFrameUpdate(batch, null, null, null, frameState, backlogSnapshot, DynamicRenderScene.empty());
    }

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

    public boolean hasChanges() {
        return hasTerrainChanges() || hasDynamicSceneUpdate();
    }

    public boolean hasTerrainChanges() {
        return batch.hasChanges();
    }

    public boolean hasDynamicSceneUpdate() {
        return dynamicScene.hasSceneUpdate();
    }

    public boolean hasDynamicContent() {
        return dynamicScene.hasRenderContent();
    }
}
