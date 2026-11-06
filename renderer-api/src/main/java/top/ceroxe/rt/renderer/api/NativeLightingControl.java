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
    /** Same asynchronous frame as work statistics. Indexed and fallback queries
     * are disjoint; empty indexed cells may still visit large-support treelets.
     * Collection follows the existing opt-in DEEP work-statistics switch. */
    record SpatialQueryStatistics(boolean available,long sequence,long sceneRevision,long queries,long indexedQueries,
            long emptyCells,long fullTreeFallbacks,long treelets,long globalTreelets) { }
    default SpatialQueryStatistics lightingSpatialQueryStatistics(){return new SpatialQueryStatistics(false,0,0,0,0,0,0,0,0);}
    /** Requested RTXPT ray counters run only at the unified DEEP diagnostic level;
     * OFF and SUMMARY suppress new ray instrumentation.
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
    /** GI-only RR evaluation divisor, 1 or 2 per axis. Default 1 preserves the
     * lighting-grid evaluation. Direct gain and both published HDR extents stay
     * at the plan extent. Divisor 2 trades GI reconstruction detail for cost. */
    default void lightingReconstructionScale(int divisor){throw new UnsupportedOperationException("Independent GI reconstruction grid unavailable");}
    record ReconstructionGrid(int divisor,int inputWidth,int inputHeight,int outputWidth,int outputHeight,long resourceBytes) { }
    /** Actual allocated RR input/output grid; zero extents before allocation or
     * after disabling RR. RR runs DLAA at inputWidth x inputHeight, followed by
     * current-frame geometry-constrained resolve to the output extent. */
    default ReconstructionGrid lightingReconstructionGrid(){return new ReconstructionGrid(1,0,0,0,0,0);}
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
