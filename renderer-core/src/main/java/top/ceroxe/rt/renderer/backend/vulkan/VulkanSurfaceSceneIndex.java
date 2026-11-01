package top.ceroxe.rt.renderer.backend.vulkan;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import top.ceroxe.rt.renderer.api.MaterialAsset;
import top.ceroxe.rt.renderer.api.MeshAsset;
import top.ceroxe.rt.renderer.api.TextureAsset;

/** Proves opaque shading inputs once per accepted resource update, including every texture mip. */
final class VulkanSurfaceSceneIndex {
    private final Map<Long, MaterialAsset> materials = new HashMap<>();
    private final Map<Long, Boolean> opaqueTextures = new HashMap<>();
    private final Set<Long> fractionalMeshes = new HashSet<>();
    private boolean opaque = true;

    void sceneApplied(VulkanSceneResidency.SceneChangeSet changes) {
        if (changes.reset()) {
            materials.clear();
            opaqueTextures.clear();
            fractionalMeshes.clear();
        }
        boolean changed = changes.reset()
                || changes.materials().statistics().writes() != 0 || changes.materials().statistics().removals() != 0
                || changes.textures().statistics().writes() != 0 || changes.textures().statistics().removals() != 0
                || changes.meshes().statistics().writes() != 0 || changes.meshes().statistics().removals() != 0;
        if (!changed) return;
        for (long id : changes.materials().removedIdentities()) materials.remove(id);
        for (var write : changes.materials().writes()) materials.put(write.id(), write.value());
        for (long id : changes.textures().removedIdentities()) opaqueTextures.remove(id);
        for (var write : changes.textures().writes()) opaqueTextures.put(write.id(), opaque(write.value()));
        for (long id : changes.meshes().removedIdentities()) fractionalMeshes.remove(id);
        for (var write : changes.meshes().writes()) {
            if (opaque(write.value())) fractionalMeshes.remove(write.id()); else fractionalMeshes.add(write.id());
        }
        opaque = fractionalMeshes.isEmpty() && materials.values().stream().allMatch(material ->
                material.transmission() == 0.0f && (material.baseColorRgba8() >>> 24) == 255
                        && !material.surfaceOverlay().enabled()
                        && material.shadingModel() != MaterialAsset.ShadingModel.LIGHTMAP_MODULATED
                        && (material.baseColorTextureId() < 0
                            || Boolean.TRUE.equals(opaqueTextures.get(material.baseColorTextureId()))));
    }

    boolean opaque() { return opaque; }

    private static boolean opaque(MeshAsset mesh) {
        var colors = mesh.vertexColorsRgba8();
        while (colors.hasRemaining()) if ((colors.get() >>> 24) != 255) return false;
        return true;
    }

    private static boolean opaque(TextureAsset texture) {
        var pixels = texture.rgba8();
        for (int offset = pixels.position() + 3; offset < pixels.limit(); offset += 4)
            if (pixels.get(offset) != (byte) 255) return false;
        return true;
    }
}
