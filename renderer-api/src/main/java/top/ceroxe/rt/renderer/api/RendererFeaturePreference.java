package top.ceroxe.rt.renderer.api;

/**
 * Native feature intent. PREFERRED permits the original RTXPT fallback and the
 * actual selection remains observable. REQUIRED rejects the first submission
 * if initialization or rendering cannot activate the requested native feature.
 */
public enum RendererFeaturePreference {
    /** Do not probe, allocate, or execute the feature. */
    DISABLED,
    /** Use the feature when available; otherwise retain the native fallback. */
    PREFERRED,
    /** Require the feature and reject its first submission when it cannot be activated. */
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
