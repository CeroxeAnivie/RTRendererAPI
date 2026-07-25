package top.ceroxe.rt.renderer.api;

/**
 * Caller-known semantic reasons that forbid reusing temporal history for one frame.
 */
public enum HistoryResetReason {
    /**
     * The camera changed discontinuously, for example after a teleport or viewpoint switch.
     */
    CAMERA_CUT,
    /**
     * Scene meaning changed discontinuously even if stable asset identities were retained.
     */
    SCENE_DISCONTINUITY,
    /**
     * The application explicitly requires a fresh temporal generation.
     */
    EXPLICIT_RESET
}
