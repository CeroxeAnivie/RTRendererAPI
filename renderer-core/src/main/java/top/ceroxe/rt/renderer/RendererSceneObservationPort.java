package top.ceroxe.rt.renderer;

import top.ceroxe.rt.renderer.scene.RendererWorldPublication;

/**
 * Commands that mutate renderer-owned terrain or dynamic-scene state.
 */
public interface RendererSceneObservationPort {
    /**
     * The sole ordered terrain-lifetime write path for host bridge code.
     * Individual callbacks may collect raw facts, but must not apply them to
     * the renderer outside this versioned publication boundary.
     *
     * @param publication immutable ordered terrain publication
     */
    void onWorldPublication(RendererWorldPublication publication);

    /**
     * Accepts a complete dynamic-scene observation.
     *
     * @param dynamicScene observed scene
     */
    void onObservedDynamicScene(DynamicRenderScene dynamicScene);

    /**
     * Marks the start of an incremental dynamic-scene collection.
     */
    void onObservedDynamicFrameCollectionStart();

    /**
     * Marks the end of an incremental dynamic-scene collection.
     */
    void onObservedDynamicFrameCollectionEnd();

    /**
     * Accepts an observed dynamic primitive.
     *
     * @param primitive observed primitive
     */
    void onObservedDynamicPrimitive(DynamicRenderScene.DynamicPrimitive primitive);

    /**
     * Accepts an observed dynamic model.
     *
     * @param observation observed model
     */
    void onObservedDynamicModelObservation(DynamicRenderScene.DynamicModelObservation observation);

    /**
     * Accepts an observed particle.
     *
     * @param particle observed particle
     */
    void onObservedDynamicParticle(DynamicRenderScene.BillboardParticle particle);

    /**
     * Accepts an observed beam.
     *
     * @param beam observed beam
     */
    void onObservedDynamicBeam(DynamicRenderScene.Beam beam);

    /**
     * Accepts an observed decal.
     *
     * @param decal observed decal
     */
    void onObservedDynamicBlockDecal(DynamicRenderScene.BlockDecal decal);

    /**
     * Accepts an observed weather column.
     *
     * @param column observed column
     */
    void onObservedDynamicWeatherColumn(DynamicRenderScene.WeatherColumn column);

    /**
     * Accepts an observed celestial body.
     *
     * @param body observed body
     */
    void onObservedDynamicCelestialBody(DynamicRenderScene.CelestialBody body);

    /**
     * Accepts an observed dynamic light.
     *
     * @param light observed light
     */
    void onObservedDynamicLight(DynamicRenderScene.SceneLight light);

    /**
     * Accepts observed dynamic environment state.
     *
     * @param state observed environment
     */
    void onObservedDynamicEnvironmentState(DynamicRenderScene.EnvironmentState state);

    /**
     * Clears all previously observed dynamic-scene state.
     */
    void onObservedDynamicSceneClear();
}
