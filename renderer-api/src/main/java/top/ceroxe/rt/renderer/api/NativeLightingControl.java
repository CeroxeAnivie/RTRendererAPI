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
    /** Requested counters are suppressed while the unified diagnostic level is OFF.
     * Pending samples may drain; cumulative copy/query/poll counts then stop. */
    record StatisticsCollection(boolean requested,boolean effective,long pending,long copies,long queries,long polls) { }
    default StatisticsCollection lightingStatisticsCollection(){return new StatisticsCollection(false,false,0,0,0,0);}
    /** Cumulative operational update work, without per-ray GPU instrumentation. */
    record AttributeUpdateStatistics(long baselineCorners,long colorCorners,long convertedMeshVariants,long layoutBuilds,
            long packedCorners,long instanceWrites,long uploadedBytes,long localGeometryWrites) { }
    default AttributeUpdateStatistics lightingAttributeUpdateStatistics(){return new AttributeUpdateStatistics(0,0,0,0,0,0,0,0);}
    /** Opt-in DEEP receiver/direct/GI dispatch decomposition. Adds receiver storage and
     * changes scheduling, so its timings describe diagnostic execution, not fused production.
     * Direct includes source traversal and shadows; GI includes its nested source/shadow work. */
    default void lightingDiagnosticDecompositionEnabled(boolean enabled){throw new UnsupportedOperationException("Diagnostic decomposition unavailable");}
    /** Reconstruct the diffuse GI radiance at the lighting-grid extent. Gain and
     * host base color never enter RR. NONE selects the deterministic raw path. */
    default void lightingReconstruction(NativeLightingReconstruction value){throw new UnsupportedOperationException("Native lighting reconstruction unavailable");}
    /** Explicitly reset layer history after a discontinuity not described by scene or camera updates. */
    default void resetLightingReconstruction(){throw new UnsupportedOperationException("Native lighting reconstruction unavailable");}
    record ReconstructionState(NativeLightingReconstruction requested,NativeLightingReconstruction actual,int width,int height,
            long evaluatedFrames,long historyResets,boolean guidesValid) { }
    default ReconstructionState lightingReconstruction(){return new ReconstructionState(NativeLightingReconstruction.NONE,NativeLightingReconstruction.NONE,0,0,0,0,false);}
    record Execution(boolean active,int gridWidth,int gridHeight,int actualSignalMask,
            long lightingFrames,long fullBeautyFrames,long attributeUploads,long attributeGeneration) { }
    Execution nativeLightingExecution();
    /** Acquire the latest unleased packet, empty before output or after it was acquired. */
    java.util.Optional<LightingFrameLease> acquireLightingFrame();
}
