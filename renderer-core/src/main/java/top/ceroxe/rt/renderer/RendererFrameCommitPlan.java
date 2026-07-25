package top.ceroxe.rt.renderer;

import top.ceroxe.rt.renderer.scene.*;

import java.util.*;

/**
 * Explicit RT commit contract for one renderer frame.
 *
 * <p>Persistent GPU-scene paths turn dirty objects into a bounded upload plan before touching GPU
 * resources. This value gives the RT backend an explicit boundary: it states which stable section
 * slots reset, disappear, upload material data, and enqueue BLAS input. The backend must not
 * rediscover that intent by cross-reading unrelated cache results.</p>
 *
 * @param fullResyncRequested        whether all persistent section state must be reconciled
 * @param removedSections            immutable sections explicitly removed from the scene
 * @param unloadedChunks             immutable chunk groups removed from the scene
 * @param sectionMeshes              immutable section meshes admitted for upload
 * @param materialSections           immutable sections whose material state changed
 * @param sectionSourceFlags         immutable per-section source flags for admitted meshes
 * @param sourceFlags                aggregate sanitized source flags
 * @param sectionTriangles           exact triangle total across {@code sectionMeshes}
 * @param sectionMeshBytes           exact estimated byte total across {@code sectionMeshes}
 * @param dynamicSceneUpdate         whether a dynamic-scene update is present
 * @param dynamicTlasGeometryContent whether the update changes dynamic TLAS geometry
 * @param dynamicRenderContent       whether the update changes dynamic shader-visible content
 * @param sectionContentRevisions    immutable content revision for each admitted section mesh
 */
public record RendererFrameCommitPlan(
        boolean fullResyncRequested,
        Set<SectionKey> removedSections,
        Set<ChunkKey> unloadedChunks,
        Map<SectionKey, SectionTriangleMesh> sectionMeshes,
        Set<SectionKey> materialSections,
        Map<SectionKey, Integer> sectionSourceFlags,
        int sourceFlags,
        long sectionTriangles,
        long sectionMeshBytes,
        boolean dynamicSceneUpdate,
        boolean dynamicTlasGeometryContent,
        boolean dynamicRenderContent,
        Map<SectionKey, Long> sectionContentRevisions
) {
    /**
     * 校验并固化一帧的原生提交计划。
     *
     * @param fullResyncRequested        是否必须协调全部持久化 section 状态
     * @param removedSections            从场景中显式移除的不可变 section 集合
     * @param unloadedChunks             从场景中移除的不可变 chunk 集合
     * @param sectionMeshes              获准上传的不可变 section mesh 映射
     * @param materialSections           材质状态发生变化的不可变 section 集合
     * @param sectionSourceFlags         已接纳 mesh 的逐 section 来源标志
     * @param sourceFlags                清理后的聚合来源标志
     * @param sectionTriangles           {@code sectionMeshes} 的精确三角形总数
     * @param sectionMeshBytes           {@code sectionMeshes} 的精确预估字节总数
     * @param dynamicSceneUpdate         是否存在动态场景更新
     * @param dynamicTlasGeometryContent 更新是否改变动态 TLAS 几何内容
     * @param dynamicRenderContent       更新是否改变着色器可见的动态内容
     * @param sectionContentRevisions    每个已接纳 section mesh 的内容修订号
     * @throws NullPointerException     任一必需集合、映射、键或值为 {@code null} 时
     * @throws IllegalArgumentException 计数、映射覆盖范围或动态场景标志彼此不一致时
     */
    public RendererFrameCommitPlan {
        removedSections = stableSectionSet(removedSections, "removedSections");
        unloadedChunks = stableChunkSet(unloadedChunks, "unloadedChunks");
        sectionMeshes = stableMeshMap(sectionMeshes);
        materialSections = stableSectionSet(materialSections, "materialSections");
        sectionSourceFlags = stableSectionSourceFlags(sectionSourceFlags, sectionMeshes.keySet());
        sectionContentRevisions = stableRevisionMap(sectionContentRevisions, sectionMeshes.keySet());
        sourceFlags = sanitizeSourceFlags(sourceFlags);
        if (sectionTriangles < 0L) {
            throw new IllegalArgumentException("sectionTriangles must not be negative");
        }
        if (sectionMeshBytes < 0L) {
            throw new IllegalArgumentException("sectionMeshBytes must not be negative");
        }
        long computedTriangles = 0L;
        long computedBytes = 0L;
        for (SectionTriangleMesh mesh : sectionMeshes.values()) {
            computedTriangles += mesh.triangleCount();
            computedBytes += mesh.estimatedBytes();
        }
        if (sectionTriangles != computedTriangles) {
            throw new IllegalArgumentException("sectionTriangles must match sectionMeshes");
        }
        if (sectionMeshBytes != computedBytes) {
            throw new IllegalArgumentException("sectionMeshBytes must match sectionMeshes");
        }
        if (dynamicRenderContent && !dynamicSceneUpdate) {
            throw new IllegalArgumentException("dynamic render content requires a dynamic scene update");
        }
        if (dynamicTlasGeometryContent && !dynamicSceneUpdate) {
            throw new IllegalArgumentException("dynamic TLAS geometry requires a dynamic scene update");
        }
    }

    /**
     * 为尚未提供逐 section 内容修订号和来源标志的调用方创建提交计划。
     *
     * <p>该兼容入口会为每个 section mesh 生成修订号 {@code 0}，并使用空的逐
     * section 来源标志映射。</p>
     *
     * @param fullResyncRequested        是否必须协调全部持久化 section 状态
     * @param removedSections            从场景中显式移除的 section 集合
     * @param unloadedChunks             从场景中移除的 chunk 集合
     * @param sectionMeshes              获准上传的 section mesh 映射
     * @param materialSections           材质状态发生变化的 section 集合
     * @param sourceFlags                聚合来源标志
     * @param sectionTriangles           {@code sectionMeshes} 的精确三角形总数
     * @param sectionMeshBytes           {@code sectionMeshes} 的精确预估字节总数
     * @param dynamicSceneUpdate         是否存在动态场景更新
     * @param dynamicTlasGeometryContent 更新是否改变动态 TLAS 几何内容
     * @param dynamicRenderContent       更新是否改变着色器可见的动态内容
     * @throws NullPointerException     任一必需集合、映射、键或值为 {@code null} 时
     * @throws IllegalArgumentException 计数、mesh 键或动态场景标志彼此不一致时
     */
    public RendererFrameCommitPlan(
            boolean fullResyncRequested,
            Set<SectionKey> removedSections,
            Set<ChunkKey> unloadedChunks,
            Map<SectionKey, SectionTriangleMesh> sectionMeshes,
            Set<SectionKey> materialSections,
            int sourceFlags,
            long sectionTriangles,
            long sectionMeshBytes,
            boolean dynamicSceneUpdate,
            boolean dynamicTlasGeometryContent,
            boolean dynamicRenderContent
    ) {
        this(
                fullResyncRequested,
                removedSections,
                unloadedChunks,
                sectionMeshes,
                materialSections,
                Map.of(),
                sourceFlags,
                sectionTriangles,
                sectionMeshBytes,
                dynamicSceneUpdate,
                dynamicTlasGeometryContent,
                dynamicRenderContent,
                zeroRevisions(sectionMeshes.keySet())
        );
    }

    /**
     * 根据已应用的场景缓存结果创建提交计划，并为每个 mesh 使用修订号 {@code 0}。
     *
     * @param batch          不可变场景更新批次
     * @param materialResult 材质缓存应用结果；空地形批次必须为 {@code null}
     * @param meshResult     mesh 缓存应用结果；空地形批次必须为 {@code null}
     * @param dynamicScene   动态场景状态；{@code null} 按空场景处理
     * @return 与输入负载严格匹配的不可变提交计划
     * @throws NullPointerException     {@code batch} 为 {@code null}，或非空地形批次缺少缓存结果时
     * @throws IllegalArgumentException 空地形批次携带缓存结果，或缓存结果内部不一致时
     */
    public static RendererFrameCommitPlan from(
            SceneUpdateBatch batch,
            SectionMaterialCache.ApplyResult materialResult,
            SectionMeshCache.ApplyResult meshResult,
            DynamicRenderScene dynamicScene
    ) {
        return from(batch, materialResult, meshResult, dynamicScene, zeroRevisions(
                meshResult == null ? Set.of() : meshResult.builtMeshes().keySet()
        ));
    }

    /**
     * 根据已应用的场景缓存结果和显式内容修订号创建提交计划。
     *
     * @param batch                   不可变场景更新批次
     * @param materialResult          材质缓存应用结果；空地形批次必须为 {@code null}
     * @param meshResult              mesh 缓存应用结果；空地形批次必须为 {@code null}
     * @param dynamicScene            动态场景状态；{@code null} 按空场景处理
     * @param sectionContentRevisions 精确覆盖所有已构建 mesh 的非负内容修订号
     * @return 与输入负载严格匹配的不可变提交计划
     * @throws NullPointerException     必需输入为 {@code null} 时
     * @throws IllegalArgumentException 缓存结果与批次不一致，或修订号未精确覆盖 mesh 时
     */
    public static RendererFrameCommitPlan from(
            SceneUpdateBatch batch,
            SectionMaterialCache.ApplyResult materialResult,
            SectionMeshCache.ApplyResult meshResult,
            DynamicRenderScene dynamicScene,
            Map<SectionKey, Long> sectionContentRevisions
    ) {
        Objects.requireNonNull(batch, "batch");
        DynamicRenderScene scene = dynamicScene == null ? DynamicRenderScene.empty() : dynamicScene;
        if (!batch.hasChanges()) {
            if (materialResult != null || meshResult != null) {
                throw new IllegalArgumentException("empty terrain batch must not carry terrain cache results");
            }
            return new RendererFrameCommitPlan(
                    false,
                    Set.of(),
                    Set.of(),
                    Map.of(),
                    Set.of(),
                    Map.of(),
                    0,
                    0L,
                    0L,
                    scene.hasSceneUpdate(),
                    scene.hasTlasGeometryContent(),
                    scene.hasRenderContent(),
                    Map.of()
            );
        }

        Objects.requireNonNull(materialResult, "materialResult");
        Objects.requireNonNull(meshResult, "meshResult");
        Map<SectionKey, SectionTriangleMesh> meshes = meshResult.builtMeshes();
        return new RendererFrameCommitPlan(
                batch.fullResyncRequested(),
                batch.removedSections(),
                batch.unloadedChunks(),
                meshes,
                materialResult.updatedSections(),
                sourceFlagsForMeshes(batch, meshes.keySet()),
                batch.batchSourceFlags(),
                triangleCount(meshes),
                meshByteCount(meshes),
                scene.hasSceneUpdate(),
                scene.hasTlasGeometryContent(),
                scene.hasRenderContent(),
                sectionContentRevisions
        );
    }

    private static long triangleCount(Map<SectionKey, SectionTriangleMesh> meshes) {
        long triangles = 0L;
        for (SectionTriangleMesh mesh : meshes.values()) {
            triangles += mesh.triangleCount();
        }
        return triangles;
    }

    private static long meshByteCount(Map<SectionKey, SectionTriangleMesh> meshes) {
        long bytes = 0L;
        for (SectionTriangleMesh mesh : meshes.values()) {
            bytes += mesh.estimatedBytes();
        }
        return bytes;
    }

    private static Set<SectionKey> stableSectionSet(Set<SectionKey> keys, String name) {
        Objects.requireNonNull(keys, name);
        if (keys.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(keys));
    }

    private static Set<ChunkKey> stableChunkSet(Set<ChunkKey> keys, String name) {
        Objects.requireNonNull(keys, name);
        if (keys.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(keys));
    }

    private static Map<SectionKey, SectionTriangleMesh> stableMeshMap(Map<SectionKey, SectionTriangleMesh> meshes) {
        Objects.requireNonNull(meshes, "sectionMeshes");
        if (meshes.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<SectionKey, SectionTriangleMesh> stable = new LinkedHashMap<>();
        for (Map.Entry<SectionKey, SectionTriangleMesh> entry : meshes.entrySet()) {
            SectionKey key = Objects.requireNonNull(entry.getKey(), "section mesh key");
            SectionTriangleMesh mesh = Objects.requireNonNull(entry.getValue(), "section mesh");
            if (!key.equals(mesh.key())) {
                throw new IllegalArgumentException("section mesh key must match mesh payload key");
            }
            stable.put(key, mesh);
        }
        return Collections.unmodifiableMap(stable);
    }

    private static Map<SectionKey, Integer> sourceFlagsForMeshes(
            SceneUpdateBatch batch,
            Set<SectionKey> meshKeys
    ) {
        LinkedHashMap<SectionKey, Integer> flags = new LinkedHashMap<>();
        for (SectionKey key : meshKeys) {
            int sectionFlags = batch.sourceFlagsForSection(key);
            if (sectionFlags != 0) {
                flags.put(key, sectionFlags);
            }
        }
        // The record constructor is the sole immutable owner and freezes this once.
        return flags;
    }

    private static Map<SectionKey, Integer> stableSectionSourceFlags(
            Map<SectionKey, Integer> sourceFlags,
            Set<SectionKey> meshKeys
    ) {
        Objects.requireNonNull(sourceFlags, "sectionSourceFlags");
        LinkedHashMap<SectionKey, Integer> stable = new LinkedHashMap<>();
        for (Map.Entry<SectionKey, Integer> entry : sourceFlags.entrySet()) {
            SectionKey key = Objects.requireNonNull(entry.getKey(), "section source key");
            if (!meshKeys.contains(key)) {
                throw new IllegalArgumentException("section source flags require a matching section mesh");
            }
            int flags = sanitizeSourceFlags(Objects.requireNonNull(entry.getValue(), "section source flags"));
            if (flags != 0) {
                stable.put(key, flags);
            }
        }
        return Collections.unmodifiableMap(stable);
    }

    private static Map<SectionKey, Long> stableRevisionMap(
            Map<SectionKey, Long> revisions,
            Set<SectionKey> meshKeys
    ) {
        Objects.requireNonNull(revisions, "sectionContentRevisions");
        if (!revisions.keySet().equals(meshKeys)) {
            throw new IllegalArgumentException("section content revisions must exactly cover section meshes");
        }
        if (revisions.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<SectionKey, Long> stable = new LinkedHashMap<>();
        for (SectionKey key : meshKeys) {
            Long revision = revisions.get(key);
            if (revision == null || revision < 0L) {
                throw new IllegalArgumentException("section content revision must be non-negative for " + key);
            }
            stable.put(key, revision);
        }
        return Collections.unmodifiableMap(stable);
    }

    private static Map<SectionKey, Long> zeroRevisions(Set<SectionKey> keys) {
        if (keys.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<SectionKey, Long> revisions = new LinkedHashMap<>();
        for (SectionKey key : keys) {
            revisions.put(key, 0L);
        }
        return revisions;
    }

    private static int sanitizeSourceFlags(int flags) {
        return flags
                & (SceneUpdateBatch.SOURCE_RENDER_DIRTY
                | SceneUpdateBatch.SOURCE_BLOCK_MUTATION
                | SceneUpdateBatch.SOURCE_CHUNK_STREAMING
                | SceneUpdateBatch.SOURCE_SECTION_REMOVAL
                | SceneUpdateBatch.SOURCE_FULL_RESYNC
                | SceneUpdateBatch.SOURCE_NEIGHBOR_DEPENDENCY
                | SceneUpdateBatch.SOURCE_DIRECT_CONTENT);
    }

    /**
     * 判断该计划是否包含地形或动态场景工作。
     *
     * @return 存在任一类待提交工作时返回 {@code true}
     */
    public boolean hasAnyWork() {
        return hasTerrainWork() || dynamicSceneUpdate;
    }

    boolean matchesPayload(
            SceneUpdateBatch batch,
            SectionMaterialCache.ApplyResult materialResult,
            SectionMeshCache.ApplyResult meshResult,
            DynamicRenderScene dynamicScene
    ) {
        Objects.requireNonNull(batch, "batch");
        DynamicRenderScene scene = dynamicScene == null ? DynamicRenderScene.empty() : dynamicScene;
        if (!batch.hasChanges()) {
            return materialResult == null
                    && meshResult == null
                    && !fullResyncRequested
                    && removedSections.isEmpty()
                    && unloadedChunks.isEmpty()
                    && sectionMeshes.isEmpty()
                    && materialSections.isEmpty()
                    && sectionSourceFlags.isEmpty()
                    && sectionContentRevisions.isEmpty()
                    && sourceFlags == 0
                    && sectionTriangles == 0L
                    && sectionMeshBytes == 0L
                    && dynamicSceneUpdate == scene.hasSceneUpdate()
                    && dynamicTlasGeometryContent == scene.hasTlasGeometryContent()
                    && dynamicRenderContent == scene.hasRenderContent();
        }
        if (materialResult == null || meshResult == null) {
            return false;
        }
        return fullResyncRequested == batch.fullResyncRequested()
                && removedSections.equals(batch.removedSections())
                && unloadedChunks.equals(batch.unloadedChunks())
                && sectionMeshes.equals(meshResult.builtMeshes())
                && sectionContentRevisions.keySet().equals(sectionMeshes.keySet())
                && materialSections.equals(materialResult.updatedSections())
                && sectionSourceFlags.equals(sourceFlagsForMeshes(batch, sectionMeshes.keySet()))
                && sourceFlags == sanitizeSourceFlags(batch.batchSourceFlags())
                && dynamicSceneUpdate == scene.hasSceneUpdate()
                && dynamicTlasGeometryContent == scene.hasTlasGeometryContent()
                && dynamicRenderContent == scene.hasRenderContent();
    }

    /**
     * 判断该计划是否会改变持久化地形状态。
     *
     * @return 需要重同步、移除、上传 mesh 或更新材质时返回 {@code true}
     */
    public boolean hasTerrainWork() {
        return fullResyncRequested
                || !removedSections.isEmpty()
                || !unloadedChunks.isEmpty()
                || !sectionMeshes.isEmpty()
                || !materialSections.isEmpty();
    }

    /**
     * 判断是否需要更新 section 级加速结构。
     *
     * @return 需要重同步、移除 section 或提交 section mesh 时返回 {@code true}
     */
    public boolean hasSectionAccelerationWork() {
        return fullResyncRequested || !removedSections.isEmpty() || !sectionMeshes.isEmpty();
    }

    /**
     * 判断是否需要更新 GPU 材质状态。
     *
     * @return 任一地形变更可能影响材质状态时返回 {@code true}
     */
    public boolean hasMaterialWork() {
        return fullResyncRequested || !removedSections.isEmpty() || !sectionMeshes.isEmpty() || !materialSections.isEmpty();
    }

    /**
     * 判断是否需要更新世界级顶层加速结构。
     *
     * @return section 加速结构或动态 TLAS 几何发生变化时返回 {@code true}
     */
    public boolean hasWorldTlasWork() {
        return hasSectionAccelerationWork() || dynamicTlasGeometryContent;
    }

    /**
     * 返回本计划接纳的 section mesh 数量。
     *
     * @return section mesh 数量
     */
    public int sectionMeshCount() {
        return sectionMeshes.size();
    }

    /**
     * 返回材质状态发生变化的 section 数量。
     *
     * @return 材质 section 数量
     */
    public int materialSectionCount() {
        return materialSections.size();
    }

    /**
     * 返回显式移除的 section 数量。
     *
     * @return 已移除 section 数量
     */
    public int removedSectionCount() {
        return removedSections.size();
    }

    /**
     * 统计至少包含一个三角形的可渲染 section mesh。
     *
     * @return 可渲染 section mesh 数量
     */
    public int renderableSectionMeshCount() {
        int count = 0;
        for (SectionTriangleMesh mesh : sectionMeshes.values()) {
            if (mesh.triangleCount() > 0) {
                count++;
            }
        }
        return count;
    }

    /**
     * 生成适合嵌入帧诊断日志的紧凑摘要。
     *
     * @return 包含提交规模和工作类型的稳定日志片段
     */
    public String asLogFragment() {
        return "rtCommit{terrainWork=" + hasTerrainWork()
                + ", fullResync=" + fullResyncRequested
                + ", removedSections=" + removedSections.size()
                + ", unloadedChunks=" + unloadedChunks.size()
                + ", sectionMeshes=" + sectionMeshes.size()
                + ", sectionContentRevisions=" + sectionContentRevisions.size()
                + ", renderableSectionMeshes=" + renderableSectionMeshCount()
                + ", materialSections=" + materialSections.size()
                + ", sectionSourceFlags=" + sectionSourceFlags.size()
                + ", sectionTriangles=" + sectionTriangles
                + ", sectionMeshBytes=" + sectionMeshBytes
                + ", dynamicSceneUpdate=" + dynamicSceneUpdate
                + ", dynamicTlasGeometryContent=" + dynamicTlasGeometryContent
                + ", dynamicRenderContent=" + dynamicRenderContent
                + ", sourceFlags=" + sourceFlags
                + "}";
    }
}
