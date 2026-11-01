package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.*;

public final class VulkanSurfaceSceneIndexSelfTest {
    private static long revision;

    public static void main(String[] args) {
        var residency = new VulkanSceneResidency();
        var index = new VulkanSurfaceSceneIndex();
        var material = MaterialAsset.opaque(1, -1);
        var mesh = MeshAsset.triangles(1, new float[]{0,0,0,1,0,0,0,1,0}, new int[]{0,1,2}, 1);
        apply(residency, index, SceneTransaction.builder(++revision).upsert(material).upsert(mesh).build());
        require(index.opaque(), "ordinary opaque scene");
        apply(residency, index, SceneTransaction.builder(++revision).upsert(material.toBuilder()
                .blendMode(MaterialAsset.BlendMode.TRANSLUCENT).transmission(.5f).build()).build());
        require(!index.opaque(), "transmission must use full transport");
        apply(residency, index, SceneTransaction.builder(++revision).upsert(material.toBuilder()
                .baseColorRgba8(0x80ffffff).build()).build());
        require(!index.opaque(), "material alpha must use full transport");
        apply(residency, index, SceneTransaction.builder(++revision).upsert(material).upsert(mesh.toBuilder()
                .vertexColorsRgba8(new int[]{-1,0x80ffffff,-1}).build()).build());
        require(!index.opaque(), "vertex alpha must use full transport");
        apply(residency, index, SceneTransaction.builder(++revision).upsert(mesh).upsert(material.toBuilder()
                .baseColorTextureId(1).build()).upsert(TextureAsset.color(1, 1, 1, new byte[]{-1,-1,-1,-1})).build());
        require(index.opaque(), "opaque texture must retain the fast path");
        // Five texels: a complete 2x2 + 1x1 mip chain. Only the last mip has fractional alpha.
        byte[] pixels = new byte[20];
        java.util.Arrays.fill(pixels, (byte) 255);
        pixels[19] = 100;
        apply(residency, index, SceneTransaction.builder(++revision)
                .upsert(TextureAsset.colorMipChain(1,2,2,2,pixels)).build());
        require(!index.opaque(), "every mip alpha must participate in eligibility");
        pixels[19] = (byte) 255;
        apply(residency, index, SceneTransaction.builder(++revision)
                .upsert(TextureAsset.colorMipChain(1,2,2,2,pixels)).build());
        require(index.opaque(), "texture update restores eligibility");
        apply(residency, index, SceneTransaction.builder(++revision).upsert(material.toBuilder()
                .shadingModel(MaterialAsset.ShadingModel.LIGHTMAP_MODULATED).build()).build());
        require(!index.opaque(), "frame lightmap alpha must retain the general path");
        apply(residency, index, SceneTransaction.builder(++revision).resetScene().build());
        require(index.opaque(), "reset must retire all obsolete opacity facts");
        System.out.println("VulkanSurfaceSceneIndexSelfTest passed");
    }

    private static void apply(VulkanSceneResidency residency, VulkanSurfaceSceneIndex index, SceneTransaction change) {
        var prepared = residency.prepare(change);
        residency.commit(prepared);
        index.sceneApplied(prepared.changeSet());
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
