package top.ceroxe.rt.renderer.api;

/** Owner-thread controls for the independent deterministic lighting execution plan. */
public interface NativeLightingControl {
    NativeLightingPlan nativeLightingPlan();
    /** Reconfigure at a frame boundary, after validating the complete requested plan. */
    void nativeLightingPlan(NativeLightingPlan plan);
    /** Validate and accept one immutable attribute delta; it becomes GPU-visible at the next submit. */
    void publishLightingAttributes(NativeLightingPublication publication);
    /** Patch an existing application byte range; capacity stays fixed and generations share the publication timeline. */
    void patchLightingApplicationData(NativeLightingApplicationPatch patch);
    /** Load a validated offline DXIL bundle; failures preserve the active plan and pipeline. */
    void activateLightingExtension(java.nio.file.Path manifest);
    void resetLightingExtension();
    /** Opt-in asynchronous ray counters. Turning them off stops new GPU copies and only drains pending work. */
    void lightingWorkStatisticsEnabled(boolean enabled);
    record WorkStatistics(boolean available,long sequence,long sceneRevision,long primaryRays,long receivers,long shadowRays,long bounceRays,long sourceNodes,long sourceCandidates) { }
    WorkStatistics lightingWorkStatistics();
    record Execution(boolean active,int gridWidth,int gridHeight,int actualSignalMask,
            long lightingFrames,long fullBeautyFrames,long attributeUploads,long attributeGeneration) { }
    Execution nativeLightingExecution();
    /** Acquire the latest unleased packet, empty before output or after it was acquired. */
    java.util.Optional<LightingFrameLease> acquireLightingFrame();
}
