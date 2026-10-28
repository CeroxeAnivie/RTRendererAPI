package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.*;

public final class VulkanSurfaceSceneIndexSelfTest {
    private static long revision;

    public static void main(String[] args) {
        var residency = new VulkanSceneResidency();
        var index = new VulkanSurfaceSceneIndex();
        var material = MaterialAsset.opaque(1, -1);
        var mesh = MeshAsset.triangles(1, new float[]{0,0,0,1,0,0,0,1,0}, new int[]{0,1,2}, 1);
        var instance = SceneInstance.builder(1,1).build();
        apply(residency, index, SceneTransaction.builder(++revision).upsert(material).upsert(mesh).upsert(instance).build());
        require(index.opaque(), "ordinary opaque scene");
        var glass = MaterialAsset.builder(2).transmission(.5f).build();
        var glassMesh = MeshAsset.triangles(2,new float[]{0,0,0,1,0,0,0,1,0},new int[]{0,1,2},2);
        var fractionalMesh = MeshAsset.triangles(3,new float[]{0,0,0,1,0,0,0,1,0},new int[]{0,1,2},1)
                .toBuilder().vertexColorsRgba8(new int[]{-1,0x80ffffff,-1}).build();
        apply(residency,index,SceneTransaction.builder(++revision).upsert(glass).upsert(glassMesh).upsert(fractionalMesh).build());
        require(index.opaque() && index.transmissiveCount()==0,"unused glass and fractional meshes preserve opaque path");
        var glassBatch=FramePrimitiveBatch.builder().add(PrimitiveInstance.builder(2).build()).build();
        require(!index.opaque(glassBatch) && index.transmissiveCount(glassBatch)==1,"frame-local mesh references require transmission");
        require(index.opaque(FramePrimitiveBatch.empty()) && index.transmissiveCount(FramePrimitiveBatch.empty())==0,
                "retired frame-local glass restores opaque path");
        var maskedState=InstanceRenderState.builder().surfaceMask(0).overlayReceiverMask(0x81).objectMask(7).build();
        var fractionalBatch=FramePrimitiveBatch.builder().add(PrimitiveInstance.builder(1)
                .surfaceVisibility(.5f).visibilityMask(0x80).renderState(maskedState).build()).build();
        require(!index.opaque(fractionalBatch),"frame-local fractional visibility cannot be forced opaque by masks");
        var maskedBatch=FramePrimitiveBatch.builder().add(PrimitiveInstance.builder(1)
                .visibilityMask(0x80).renderState(maskedState).build()).build();
        require(index.opaque(maskedBatch),"ordinary receiver/object/visibility masks do not invent translucency");
        apply(residency,index,SceneTransaction.builder(++revision).upsert(SceneInstance.builder(2,2).build()).build());
        require(!index.opaque() && index.transmissiveCount()==1,"instantiated glass opts into transmission");
        apply(residency,index,SceneTransaction.builder(++revision).upsert(SceneInstance.builder(2,1).build()).build());
        require(index.opaque() && index.transmissiveCount()==0,"instance mesh replacement retires previous glass reference");
        apply(residency,index,SceneTransaction.builder(++revision).upsert(SceneInstance.builder(2,3).build()).build());
        require(!index.opaque(),"referenced vertex alpha selects general surface path");
        apply(residency,index,SceneTransaction.builder(++revision).removeInstance(2).build());
        require(index.opaque(),"last fractional instance removal restores opaque path");
        apply(residency,index,SceneTransaction.builder(++revision).upsert(instance.toBuilder().surfaceVisibility(.25f).build()).build());
        require(!index.opaque(),"persistent fractional visibility cannot be forced opaque");
        apply(residency,index,SceneTransaction.builder(++revision).upsert(instance).build());
        apply(residency, index, SceneTransaction.builder(++revision).upsert(material.toBuilder()
                .blendMode(MaterialAsset.BlendMode.TRANSLUCENT).transmission(.5f).build()).build());
        require(!index.opaque() && index.transmissiveCount()==1,"material replacement updates live references");
        require(!index.opaque(maskedBatch) && index.transmissiveCount(maskedBatch)==2,
                "cached frame batch observes material generation changes");
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
        apply(residency,index,SceneTransaction.builder(++revision).upsert(material.toBuilder()
                .surfaceOverlay(SurfaceOverlayState.depthEqual(.001f)).build()).build());
        require(!index.opaque(maskedBatch),"receiver masks do not permit opaque rewrite of overlays");
        apply(residency, index, SceneTransaction.builder(++revision).upsert(material.toBuilder()
                .shadingModel(MaterialAsset.ShadingModel.LIGHTMAP_MODULATED).build()).build());
        require(!index.opaque(), "frame lightmap alpha must retain the general path");
        var closed = MediumTransportSelfTest.cube(4,2,1);
        var volume = SceneInstance.builder(4,4).surfaceVisibility(.5f).mediumBoundary(MediumBoundary.volume(Medium.vacuum())).build();
        apply(residency,index,SceneTransaction.builder(++revision).upsert(material).upsert(closed).upsert(volume).build());
        require(index.opaque() && index.transmissiveCount()==0,"volume-only boundaries do not shade their glass material");
        var volumeBatch=FramePrimitiveBatch.builder().add(PrimitiveInstance.from(volume)).build();
        require(index.opaque(volumeBatch) && index.transmissiveCount(volumeBatch)==0,"frame-local volume-only boundary skips surface facts");
        apply(residency,index,SceneTransaction.builder(++revision)
                .upsert(volume.toBuilder().mediumBoundary(MediumBoundary.surface(Medium.vacuum())).build()).build());
        require(!index.opaque() && index.transmissiveCount()==1,"surface boundary restores material and visibility classification");
        apply(residency, index, SceneTransaction.builder(++revision).resetScene().build());
        require(index.opaque() && index.transmissiveCount()==0,"reset must retire all obsolete opacity facts");
        require(!index.opaque(glassBatch),"stale frame mesh reference cannot prove opacity after reset");
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
