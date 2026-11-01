package demo;

import top.ceroxe.rt.renderer.api.*;

/** Deliberate demo migration to upstream physical semantics; original fixtures remain intact. */
final class NativeMediumScenes {
    static final String JSON_CHANGES="[\"native progressive film\",\"native stochastic dielectric sampling\",\"native BSDF and physical lighting without finite light cutoff\",\"native densitygrid interpolation\",\"game unlit/lightmap materials become native physical materials\",\"screen overlays become ordinary native surfaces without screen depth composition\",\"custom fog/shader data/lightmap are excluded; physical volumes remain\",\"native path and volume budgets replace custom quadrature/photon budgets\"]";
    static SceneTransaction scene(SceneTransaction source) {
        var b=SceneTransaction.builder(source.revision());if(source.reset())b.resetScene();var u=source.upserts();
        b.upsertTextures(u.textures()).upsertMeshes(u.meshes()).upsertLights(u.lights());
        for(var i:u.instances())b.upsert(i.toBuilder().renderState(InstanceRenderState.defaults()).build());
        for(var m:u.materials())b.upsert(m.toBuilder().shadingModel(MaterialAsset.ShadingModel.PHYSICALLY_BASED).surfaceOverlay(SurfaceOverlayState.disabled()).build());
        var r=source.removals();var ids=r.textureIds();while(ids.hasRemaining())b.removeTexture(ids.get());
        ids=r.materialIds();while(ids.hasRemaining())b.removeMaterial(ids.get());ids=r.meshIds();while(ids.hasRemaining())b.removeMesh(ids.get());
        ids=r.instanceIds();while(ids.hasRemaining())b.removeInstance(ids.get());ids=r.lightIds();while(ids.hasRemaining())b.removeLight(ids.get());
        return b.build();
    }
    static RenderFrameRequest frame(RenderFrameRequest source) {
        var batch=FramePrimitiveBatch.builder();for(var i:source.primitiveBatch().primitives())batch.add(i.toBuilder().renderState(InstanceRenderState.defaults()).build());
        return source.toBuilder().shaderData(java.util.List.of()).fog(DistanceFogState.disabled()).primitiveBatch(batch.build()).antiAliasing(new AntiAliasingState(1)).build();
    }
    private NativeMediumScenes() { }
}
