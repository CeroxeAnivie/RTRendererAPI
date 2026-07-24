package top.ceroxe.mcvulkanrt.renderer;

import top.ceroxe.mcvulkanrt.renderer.scene.RendererWorldPublication;

/** Commands that mutate renderer-owned terrain or dynamic-scene state. */
public interface RendererSceneObservationPort {
    /**
     * The sole ordered terrain-lifetime write path for host bridge code.
     * Individual callbacks may collect raw facts, but must not apply them to
     * the renderer outside this versioned publication boundary.
     */
    void onWorldPublication(RendererWorldPublication publication);
    void onObservedDynamicScene(DynamicRenderScene dynamicScene);
    void onObservedDynamicFrameCollectionStart();
    void onObservedDynamicFrameCollectionEnd();
    void onObservedDynamicPrimitive(DynamicRenderScene.DynamicPrimitive primitive);
    void onObservedDynamicModelObservation(DynamicRenderScene.DynamicModelObservation observation);
    void onObservedDynamicParticle(DynamicRenderScene.BillboardParticle particle);
    void onObservedDynamicBeam(DynamicRenderScene.Beam beam);
    void onObservedDynamicBlockDecal(DynamicRenderScene.BlockDecal decal);
    void onObservedDynamicWeatherColumn(DynamicRenderScene.WeatherColumn column);
    void onObservedDynamicCelestialBody(DynamicRenderScene.CelestialBody body);
    void onObservedDynamicLight(DynamicRenderScene.SceneLight light);
    void onObservedDynamicEnvironmentState(DynamicRenderScene.EnvironmentState state);
    void onObservedDynamicSceneClear();
}
