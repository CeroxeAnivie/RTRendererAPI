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
        cameraAbsorptionAdmission();
        pbrMediumAdmission();
        System.out.println("VulkanSurfaceSceneIndexSelfTest passed");
    }

    private static void cameraAbsorptionAdmission() {
        var residency = new VulkanSceneResidency();
        var index = new VulkanSurfaceSceneIndex();
        var empty = FramePrimitiveBatch.empty();
        require(index.cameraAbsorptionOnly(empty), "empty scene needs no secondary medium segments");
        var unlit = MaterialAsset.builder(51).doubleSided(true)
                .shadingModel(MaterialAsset.ShadingModel.UNLIT).build();
        var mesh = MeshAsset.triangles(51, new float[]{0,0,0,1,0,0,0,1,0}, new int[]{0,1,2}, 51);
        var instance = SceneInstance.builder(51,51).build();
        var batch = FramePrimitiveBatch.builder().add(PrimitiveInstance.builder(51).build()).build();
        apply(residency,index,SceneTransaction.builder(++revision).upsert(unlit).upsert(mesh).upsert(instance).build());
        require(index.cameraAbsorptionOnly(empty) && index.cameraAbsorptionOnly(batch),
                "referenced double-sided unlit surfaces terminate camera paths");
        apply(residency,index,SceneTransaction.builder(++revision)
                .upsert(MaterialAsset.builder(52).build()).build());
        require(index.cameraAbsorptionOnly(empty), "unused PBR asset must not reject an admitted scene");
        apply(residency,index,SceneTransaction.builder(++revision).upsert(unlit.toBuilder()
                .shadingModel(MaterialAsset.ShadingModel.PHYSICALLY_BASED).emissive(0xffffffff,3).build()).build());
        require(!index.cameraAbsorptionOnly(empty) && !index.cameraAbsorptionOnly(batch),
                "emissive PBR still needs lighting and reflection segments; cached batches must update");
        apply(residency,index,SceneTransaction.builder(++revision).upsert(unlit.toBuilder().doubleSided(false).build()).build());
        require(!index.cameraAbsorptionOnly(empty), "single-sided surfaces do not satisfy admission");
        apply(residency,index,SceneTransaction.builder(++revision).upsert(unlit.toBuilder()
                .baseColorTextureId(51).build()).upsert(TextureAsset.color(51,1,1,new byte[]{-1,-1,-1,-1})).build());
        require(index.cameraAbsorptionOnly(batch), "opaque unlit texture is admitted");
        apply(residency,index,SceneTransaction.builder(++revision)
                .upsert(TextureAsset.color(51,1,1,new byte[]{-1,-1,-1,100})).build());
        require(!index.cameraAbsorptionOnly(batch), "texture alpha update invalidates terminal proof");
        apply(residency,index,SceneTransaction.builder(++revision).upsert(unlit).build());
        apply(residency,index,SceneTransaction.builder(++revision)
                .upsert(instance.toBuilder().surfaceVisibility(.5f).build()).build());
        require(!index.cameraAbsorptionOnly(empty), "fractional instance visibility needs continuation");
        apply(residency,index,SceneTransaction.builder(++revision).removeInstance(51).build());
        require(index.cameraAbsorptionOnly(batch), "retired instance does not invalidate admitted frame-local geometry");
        apply(residency,index,SceneTransaction.builder(++revision).resetScene().build());
        require(index.cameraAbsorptionOnly(empty) && !index.cameraAbsorptionOnly(batch),
                "reset clears counters while stale mesh references cannot prove admission");
    }

    private static void pbrMediumAdmission() {
        var residency = new VulkanSceneResidency();
        var index = new VulkanSurfaceSceneIndex();
        var empty = FramePrimitiveBatch.empty();
        var material = MaterialAsset.builder(61).doubleSided(true).metallic(0).baseColorTextureId(61).build();
        var mesh = MeshAsset.triangles(61, new float[]{0,0,0,1,0,0,0,1,0}, new int[]{0,1,2}, 61);
        var instance = SceneInstance.builder(61,61).build();
        var batch = FramePrimitiveBatch.builder().add(PrimitiveInstance.builder(61).build()).build();
        apply(residency,index,SceneTransaction.builder(++revision).upsert(material).upsert(mesh).upsert(instance)
                .upsert(TextureAsset.color(61,1,1,new byte[]{-1,-1,-1,-1})).build());
        require(index.opaquePbrMediumOnly(empty) && index.opaquePbrMediumOnly(batch),
                "opaque double-sided dielectric PBR admits native light connections");
        require(!index.cameraAbsorptionOnly(empty), "PBR must retain its lighting path");
        apply(residency,index,SceneTransaction.builder(++revision).upsert(MaterialAsset.builder(62).metallic(1).build()).build());
        require(index.opaquePbrMediumOnly(batch), "unused metallic resources do not invalidate admission");
        apply(residency,index,SceneTransaction.builder(++revision).upsert(material.toBuilder().metallic(.1f).build()).build());
        require(!index.opaquePbrMediumOnly(empty) && !index.opaquePbrMediumOnly(batch),
                "referenced metal requires secondary reflection segments and invalidates the cached batch");
        apply(residency,index,SceneTransaction.builder(++revision).upsert(material).build());
        require(index.opaquePbrMediumOnly(batch), "material replacement restores the proof");
        apply(residency,index,SceneTransaction.builder(++revision)
                .upsert(TextureAsset.color(61,1,1,new byte[]{-1,-1,-1,100})).build());
        require(!index.opaquePbrMediumOnly(empty) && !index.opaquePbrMediumOnly(batch),
                "texture updates invalidate persistent and frame-local coverage proofs");
        apply(residency,index,SceneTransaction.builder(++revision)
                .upsert(TextureAsset.color(61,1,1,new byte[]{-1,-1,-1,-1})).build());
        require(index.opaquePbrMediumOnly(batch), "restored texture alpha restores admission");
        var singleSided = material.toBuilder().doubleSided(false).build();
        apply(residency,index,SceneTransaction.builder(++revision).upsert(singleSided).build());
        require(index.opaquePbrMediumOnly(empty) && index.opaquePbrMediumOnly(batch),
                "single-sided PBR preserves public camera and light-query backface rejection");
        apply(residency,index,SceneTransaction.builder(++revision).upsert(singleSided.toBuilder()
                .shadingModel(MaterialAsset.ShadingModel.UNLIT).build()).build());
        require(index.opaquePbrMediumOnly(empty) && index.opaquePbrMediumOnly(batch)
                && !index.cameraAbsorptionOnly(empty), "single-sided unlit uses complete native camera packets");
        for (var unsupported : new MaterialAsset[]{
                singleSided.toBuilder().metallic(.1f).build(),
                singleSided.toBuilder().blendMode(MaterialAsset.BlendMode.TRANSLUCENT).transmission(.5f).build(),
                material.toBuilder().blendMode(MaterialAsset.BlendMode.TRANSLUCENT).transmission(.5f).build(),
                material.toBuilder().surfaceOverlay(SurfaceOverlayState.depthEqual(.001f)).build(),
                material.toBuilder().shadingModel(MaterialAsset.ShadingModel.LIGHTMAP_MODULATED).build()}) {
            apply(residency,index,SceneTransaction.builder(++revision).upsert(unsupported).build());
            require(!index.opaquePbrMediumOnly(batch), "surface continuation cannot omit medium segments");
        }
        apply(residency,index,SceneTransaction.builder(++revision).upsert(material).upsert(instance.toBuilder()
                .surfaceVisibility(.5f).build()).build());
        require(!index.opaquePbrMediumOnly(empty), "fractional instance visibility requires continuation");
        apply(residency,index,SceneTransaction.builder(++revision).removeInstance(61).build());
        require(index.opaquePbrMediumOnly(batch), "retired persistent instances do not reject frame-local geometry");
        apply(residency,index,SceneTransaction.builder(++revision).upsert(mesh.toBuilder()
                .vertexColorsRgba8(new int[]{-1,0x80ffffff,-1}).build()).build());
        require(!index.opaquePbrMediumOnly(batch), "vertex alpha invalidates the frame-local proof");
        var volume = FramePrimitiveBatch.builder().add(PrimitiveInstance.builder(61).surfaceVisibility(.5f)
                .mediumBoundary(MediumBoundary.volume(Medium.vacuum())).build()).build();
        require(index.opaquePbrMediumOnly(volume), "volume-only geometry has no surface lighting obligation");
        apply(residency,index,SceneTransaction.builder(++revision).resetScene().build());
        require(index.opaquePbrMediumOnly(empty) && !index.opaquePbrMediumOnly(batch),
                "reset clears counts and invalidates cached references");
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
