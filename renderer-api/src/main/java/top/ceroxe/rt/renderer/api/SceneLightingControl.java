package top.ceroxe.rt.renderer.api;

/** Optional scene-lighting policy. Query with {@link Renderer#extension(Class)}.
 * Calls are confined to the renderer's owner thread and take effect on its next frame.
 * Backends that do not expose this extension do not support these controls.
 */
public interface SceneLightingControl {
    /** Default preserves compatibility: lighting edits invalidate temporal reconstruction. */
    enum Continuity { DISCONTINUOUS, CONTINUOUS }
    /** A continuity hint does not suppress input changes. A backend may reset on an abrupt
     * energy, direction or topology change even with CONTINUOUS selected. */
    void lightingContinuity(Continuity continuity);
    /** Whether the current backend configuration supports CONTINUOUS without changing its
     * transport, reconstruction or cache techniques. */
    default boolean supportsContinuousLighting() { return false; }
    /** Linear scene-referred primary-ray color, before the renderer's exposure and tone mapping.
     * Reflected, refracted and indirect illumination continues to use EnvironmentState.
     * This is opaque output, not host-background compositing. */
    void visibleBackground(float red, float green, float blue);
    /** Restore the default primary background derived from EnvironmentState. */
    void environmentBackground();
    /** True only if an additional negotiated coverage/alpha composition contract is implemented. */
    default boolean supportsTransparentBackground() { return false; }
}
