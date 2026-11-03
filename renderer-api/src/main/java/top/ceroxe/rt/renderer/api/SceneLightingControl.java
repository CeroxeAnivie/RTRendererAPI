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
    /** Select primary-surface coverage output. Primary misses have zero alpha and RGB;
     * valid masked/opaque/transmissive surfaces retain coverage. Secondary paths keep the
     * physical environment. RGBA8 shared output encodes linear premultiplied display RGB
     * with the sRGB transfer function; decode sRGB before ONE/ONE_MINUS_SRC_ALPHA blending.
     * Unsupported configurations reject this call without changing the selected mode.
     * The native window does not become an OS-transparent window. */
    default void transparentBackground() { throw new UnsupportedOperationException("Transparent background is unavailable"); }
    /** True only if the current configuration implements the coverage/output contract. */
    default boolean supportsTransparentBackground() { return false; }
}
