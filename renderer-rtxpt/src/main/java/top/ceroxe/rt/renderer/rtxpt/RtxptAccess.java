package top.ceroxe.rt.renderer.rtxpt;
import java.nio.file.Path;

/** Expert host boundary. Call on the renderer's creating thread. The host owns window lifetime. */
public interface RtxptAccess {
    /** Load an upstream scene directly, bypassing retained-scene translation and its raw-asset
     * admission limits. Native external asset sizes are owned by the caller/upstream loader.
     */
    void loadNativeScene(Path scene);
    /** Native HWND; only valid after the first submission and until close. */
    long windowHandle();
    /** Sets the native viewport title; persists across frames and scene reloads. Requires the first submission. */
    void setWindowTitle(String title);
    /** GPU render/present of the resident scene; false means the user closed the window. */
    boolean renderFrame();
    /** Explicit GPU drain for completed-frame measurement, outside the normal frame loop. */
    void awaitIdle();
    /** Actual native settings and internal resolution after rendering, including upstream fallbacks. */
    RuntimeStatus runtimeStatus();
    /** Native sampling/quality settings and actual cache/pool allocation; no GPU synchronization.
     * A setting is used only by its active technique. Guide depth is additionally capped by path depth.
     */
    PathQualityStatus pathQualityStatus();
    /** {@code allocatedCacheEntries} is zero while SHaRC storage is not allocated; the configured
     * capacity remains available through {@code quality.cache().entries()}.
     */
    record PathQualityStatus(top.ceroxe.rt.renderer.api.PathTracingQualityOptions quality,
            int actualLightSampler,int allocatedCacheEntries,int maxFramesInFlight,int workerThreads,int shaderCompilerThreads,
            boolean exactPointLighting,boolean directionalStrata) { }
    /** Text drawn by the upstream GPU UI pass; empty text hides the HUD. GPU timing follows RendererConfig.gpuTimingsEnabled(). */
    void setOverlayText(String text);
    /** Optional selector drawn inside the same native HUD; an empty list hides it. */
    void setOverlayChoices(java.util.List<String> labels, int selectedIndex);
    /** Current native selector index, or -1 when no choices are configured. */
    int overlaySelection();
    /** Actual client output size and upstream exposure/post-process settings. */
    PresentationStatus presentationStatus();
    /** Actual upstream camera after native mouse/keyboard input; requires the first submission. */
    top.ceroxe.rt.renderer.api.CameraState cameraState();
    /**
     * Sets the upstream tone mapper's exposure offset in EV; -1 halves displayed exposure.
     * May be called before the first submission and survives scene reloads. This changes
     * presentation only, without adding a render pass or changing light intensities.
     * @param stops finite exposure compensation in stops
     */
    void setExposureCompensation(float stops);
    record PresentationStatus(int outputWidth,int outputHeight,float exposureCompensation,float exposureValue,boolean bloom,
            boolean toneMapping,float bloomRadius,float bloomIntensity) { }
    enum Reconstruction { NONE, TAA, DLSS, DLSS_RR }
    record RuntimeStatus(int renderWidth,int renderHeight,Reconstruction reconstruction,
        boolean standaloneDenoiser,boolean denoising,boolean restirDi,boolean restirGi,boolean sharc,
        boolean frameGeneration,boolean shaderExecutionReordering,boolean lowLatency,
        int dlssMode,int generatedFramesPerNativeFrame,int maxGeneratedFrames,
        boolean dlssSupported,boolean dlssRrSupported,boolean frameGenerationSupported,
        boolean reflexSupported,boolean shaderExecutionReorderingSupported,boolean relaxDenoiser,
        int exactOpticalInterfaces,boolean sceneLighting) {
        /** Zero when the scene-lighting transport is not active; otherwise the selected 1..6 budget. */
        public int activeExactOpticalInterfaces(){return sceneLighting?exactOpticalInterfaces:0;}
    }
    /** Explicit one-shot readback. Never called in the presentation or benchmark frame loop. */
    void capture(Path png);
    BridgeTimings bridgeTimings();
    /** Native execution counters. Upload time measures CPU command recording, not GPU completion. */
    default LightingTimings lightingTimings(){return new LightingTimings(0,0,0,0,0);}
    record LightingTimings(long nativeSceneLoads,long committedBatches,long recordedUploads,
                          long uploadRecordingNanos,long historyInvalidations) { }
    /** Cumulative native resource work; CPU queue-drain waits are distinct from command recording.
     * GPU intervals use queue timestamps and may include CPU gaps during BLAS compaction;
     * they do not measure GPU busy time. Completed queries are collected without blocking. */
    default RetainedUpdateTimings retainedUpdateTimings(){return new RetainedUpdateTimings(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0);}
    record RetainedUpdateTimings(long resourceBatches,long exportedMeshVariants,long affectedMaterialVariants,long changedTextures,
        long builtBlases,long retainedBlases,long tlasGenerations,long synchronizationWaitNanos,long recordingNanos,
        long lightIndexBuilds,long lightIndexBuildNanos,long representedLights,
        long reusedTextureAllocations,long gpuIntervalSamples,long gpuIntervalNanos) { }
    enum LightSamplingMode { BACKEND_DEFAULT, EXHAUSTIVE, SPATIAL_TREE }
    /** Counts cover the latest native frame. GPU counters require RTR_RTXPT_LIGHT_STATS=1;
     * querying measured counters performs an explicit readback outside the render loop. */
    default LightSamplingStatus lightSamplingStatus(){return new LightSamplingStatus(LightSamplingMode.BACKEND_DEFAULT,0,0,false,0,0,0,0);}
    record LightSamplingStatus(LightSamplingMode mode,long representedLights,long indexNodes,boolean measured,
        long shadedReceivers,long selectedLights,long visitedTreeNodes,long shadowTests) { }
    enum RetainedUpdateMode { INITIAL_PUBLICATION, EXPLICIT_RESET, RESOURCE_DELTA, TRANSFORMS, LIGHTING, UNCHANGED, NATIVE_SCENE }
    default RetainedUpdateMode retainedUpdateMode(){return RetainedUpdateMode.UNCHANGED;}
    record BridgeTimings(long frames, long frameNanos, long sceneExports, long sceneExportNanos,
            long transformBatches, long transformNanos) { }
}
