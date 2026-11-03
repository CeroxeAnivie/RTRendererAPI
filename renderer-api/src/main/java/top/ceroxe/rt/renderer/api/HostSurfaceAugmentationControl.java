package top.ceroxe.rt.renderer.api;

/** Owner-thread style controls for host-supplied primary receivers. */
public interface HostSurfaceAugmentationControl {
    HostSurfaceAugmentation hostSurfaceAugmentation();
    /** Validate and prepare at a frame boundary; preserve the prior configuration on failure. */
    void hostSurfaceAugmentation(HostSurfaceAugmentation configuration);
    /** Publish immutable receiver roles, baseline data and application bytes for the next host submit. */
    void publishLightingAttributes(NativeLightingPublication publication);
    /** Patch existing application storage without resizing it or starting independent primary tracing. */
    void patchLightingApplicationData(NativeLightingApplicationPatch patch);
    /** Opt-in asynchronous evaluator counters; supplied receivers generate no camera-primary rays. */
    void lightingWorkStatisticsEnabled(boolean enabled);
    /** Most recent completed counter sample, with the producing frame's sequence and scene revision. */
    NativeLightingControl.WorkStatistics lightingWorkStatistics();
}
