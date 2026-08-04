package top.ceroxe.rt.renderer.api;

/**
 * Renderer-lifetime policy for an optional rendering feature.
 *
 * <p>The three states deliberately separate product intent from backend capability. A backend may
 * select a documented fallback only for {@link #PREFERRED}; when no fallback is configured, the
 * renderer keeps its base path and reports the optional feature as unsupported. {@link #REQUIRED}
 * turns an unavailable feature into renderer initialization failure instead of producing a frame
 * with different quality characteristics.</p>
 */
public enum RendererFeaturePreference {
    /** Do not probe, allocate, or execute the feature. */
    DISABLED,
    /** Use the feature when available; otherwise use its fallback or retain the base path. */
    PREFERRED,
    /** Require the feature and reject renderer initialization when it cannot be activated. */
    REQUIRED;

    /**
     * Reports whether a backend must perform feature negotiation.
     *
     * @return {@code true} for preferred or required features
     */
    public boolean requested() {
        return this != DISABLED;
    }
}
