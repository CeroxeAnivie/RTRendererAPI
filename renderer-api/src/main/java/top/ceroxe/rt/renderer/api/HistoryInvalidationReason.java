package top.ceroxe.rt.renderer.api;

/**
 * Effective renderer-observed reasons why a submitted frame cannot consume prior history.
 */
public enum HistoryInvalidationReason {
    /**
     * No earlier frame exists in the active renderer generation.
     */
    FIRST_FRAME,
    /**
     * The application identified a discontinuous camera change.
     */
    CAMERA_CUT,
    /**
     * The application identified a discontinuous scene change.
     */
    SCENE_DISCONTINUITY,
    /**
     * The application explicitly requested a fresh history generation.
     */
    EXPLICIT_RESET,
    /**
     * Projection parameters changed and invalidate prior screen-space samples.
     */
    CAMERA_PROJECTION_CHANGED,
    /**
     * Stable scene topology or temporal instance identity changed.
     */
    SCENE_TOPOLOGY_CHANGED,
    /**
     * Scene materials, textures, lights, or other radiance-producing content changed.
     */
    SCENE_CONTENT_CHANGED,
    /**
     * Per-frame environment, fog, lightmap, sampling, or anti-aliasing policy changed.
     */
    FRAME_SHADING_CHANGED,
    /**
     * The output extent changed.
     */
    OUTPUT_EXTENT_CHANGED,
    /**
     * The native output encoding changed.
     */
    OUTPUT_FORMAT_CHANGED,
    /**
     * Accepted frame sequence values were not consecutive.
     */
    FRAME_SEQUENCE_DISCONTINUITY,
    /**
     * A new Vulkan device generation replaced the prior history owner.
     */
    DEVICE_RECOVERY,
    /**
     * Memory pressure evicted otherwise reusable history.
     */
    HISTORY_EVICTED,
    /**
     * Required previous-frame geometry or identity evidence was unavailable.
     */
    MOTION_DATA_UNAVAILABLE
}
