package top.ceroxe.mcvulkanrt.renderer;

import top.ceroxe.mcvulkanrt.renderer.scene.ChunkKey;
import top.ceroxe.mcvulkanrt.renderer.scene.SceneUpdateBatch;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionMaterialCache;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionMeshCache;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionTriangleMesh;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit RT commit contract for one renderer frame.
 *
 * <p>host's sourceEngine renderer publishes compiled section meshes through a
 * clear RenderSection lifecycle; UE-style GPUScene/RDG paths similarly turn
 * dirty objects into a bounded upload plan before touching GPU resources. This
 * value object gives the RT backend the same boundary: it says which stable
 * section slots reset, disappear, upload material data, and enqueue BLAS input.
 * The backend should not rediscover that intent by cross-reading unrelated
 * cache results.</p>
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

    /** Compatibility constructor for callers that do not model content generations. */
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

    public boolean hasTerrainWork() {
        return fullResyncRequested
                || !removedSections.isEmpty()
                || !unloadedChunks.isEmpty()
                || !sectionMeshes.isEmpty()
                || !materialSections.isEmpty();
    }

    public boolean hasSectionAccelerationWork() {
        return fullResyncRequested || !removedSections.isEmpty() || !sectionMeshes.isEmpty();
    }

    public boolean hasMaterialWork() {
        return fullResyncRequested || !removedSections.isEmpty() || !sectionMeshes.isEmpty() || !materialSections.isEmpty();
    }

    public boolean hasWorldTlasWork() {
        return hasSectionAccelerationWork() || dynamicTlasGeometryContent;
    }

    public int sectionMeshCount() {
        return sectionMeshes.size();
    }

    public int materialSectionCount() {
        return materialSections.size();
    }

    public int removedSectionCount() {
        return removedSections.size();
    }

    public int renderableSectionMeshCount() {
        int count = 0;
        for (SectionTriangleMesh mesh : sectionMeshes.values()) {
            if (mesh.triangleCount() > 0) {
                count++;
            }
        }
        return count;
    }

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
}
