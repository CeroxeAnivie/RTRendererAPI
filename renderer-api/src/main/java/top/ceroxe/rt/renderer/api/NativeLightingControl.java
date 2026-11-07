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
    /** Reconstruct the diffuse GI radiance at the lighting-grid extent. Gain and
     * host base color never enter RR. NONE selects the deterministic raw path. */
    void lightingReconstruction(NativeLightingReconstruction value);
    /** GI-only RR evaluation divisor, 1 or 2 per axis. Default 1 preserves the
     * lighting-grid evaluation. Direct gain and both published HDR extents stay
     * at the plan extent. Divisor 2 trades GI reconstruction detail for cost. */
    void lightingReconstructionScale(int divisor);
    /** Explicitly reset layer history after a discontinuity not described by scene or camera updates. */
    void resetLightingReconstruction();
    record ReconstructionState(NativeLightingReconstruction requested,NativeLightingReconstruction actual,int width,int height,
            long evaluatedFrames,long historyResets,boolean guidesValid) { }
    ReconstructionState lightingReconstruction();
    record Execution(boolean active,int gridWidth,int gridHeight,int actualSignalMask,
            long lightingFrames,long fullBeautyFrames,long attributeUploads,long attributeGeneration) { }
    Execution nativeLightingExecution();
    /** Acquire the latest unleased packet, empty before output or after it was acquired. */
    java.util.Optional<LightingFrameLease> acquireLightingFrame();
}
