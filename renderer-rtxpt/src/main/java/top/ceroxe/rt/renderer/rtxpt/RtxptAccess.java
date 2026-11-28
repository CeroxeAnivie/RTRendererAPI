package top.ceroxe.rt.renderer.rtxpt;

import java.nio.file.Path;

/** Original RTXPT execution and window access, plus external boundary observations. */
public interface RtxptAccess extends RtxptDiagnosticAccess {
    /** Available image reconstruction modes reported by the native renderer. */
    enum Reconstruction {
        /** No reconstruction. */ NONE,
        /** Temporal anti-aliasing. */ TAA,
        /** DLSS Super Resolution. */ DLSS,
        /** DLSS Ray Reconstruction. */ DLSS_RR
    }
    /**
     * Loads a native RTXPT scene description.
     * @param scene path to the scene file
     */
    void loadNativeScene(Path scene);
    /**
     * Returns the native presentation window handle.
     * @return native window handle, or zero when no window is available
     */
    long windowHandle();
    /**
     * Sets the title of the native presentation window.
     * @param title new window title
     */
    void setWindowTitle(String title);
    /**
     * Sets manual exposure compensation in stops, in {@code [-12, 12]}.
     * @param offset exposure compensation in stops
     */
    void setExposureOffset(float offset);
    /**
     * Reads native requested and active settings; this is not a count of presented frames.
     * @return current presentation settings and capability state
     */
    PresentationState presentationState();
    /**
     * Reads the original tone-mapping exposure fields.
     * @return current automatic and manual exposure values
     */
    ExposureState exposureState();
    /**
     * Reads actual native window, framebuffer, internal rendering and output dimensions.
     * @return current native window and render dimensions
     */
    WindowState windowState();
    /**
     * Reads the native standalone denoiser selection independently of its enabled state.
     * @return selected native denoiser
     */
    Denoiser denoiserState();
    /**
     * Reads actual native modes and shader-path flags, not GPU execution counters.
     * @return current native rendering modes and path flags
     */
    RenderingState renderingState();
    /**
     * Reads completed calls to original rendering hooks, not GPU timings or generated-frame counts.
     * @return counts of completed native rendering hooks
     */
    ExecutionState executionState();
    /**
     * Counts completed calls to the original and adapter renderer hooks.
     * @param officialPipelineFrames completed original pipeline frame calls
     * @param dlssRrGuideFrames completed DLSS Ray Reconstruction guide calls
     * @param analyticBeautyFrames completed analytic beauty calls
     * @param independentLightingFrames completed independent lighting calls
     * @param officialOnly whether adapter beauty extensions are disabled
     */
    record ExecutionState(long officialPipelineFrames,long dlssRrGuideFrames,long analyticBeautyFrames,
            long independentLightingFrames,boolean officialOnly) { }
    /**
     * Returns original RTXPT frame statistics, also used in its window title.
     * DLSS-G values marked {@code *} are multiplier estimates.
     * @return formatted native frame statistics
     */
    String frameStatistics();
    /** Standalone native denoiser choices. */
    enum Denoiser {
        /** NVIDIA Real-Time Denoiser Reblur. */ REBLUR,
        /** NVIDIA Real-Time Denoiser Relax. */ RELAX
    }
    /**
     * Sets consumer HUD text rendered through the native UI pass.
     * @param text overlay text; {@code null} or empty text clears it
     */
    void setOverlayText(String text);
    /**
     * Replaces the consumer selector; an empty list requires {@code selected == -1}.
     * @param choices selector labels, in display order
     * @param selected selected index, or {@code -1} for no selection
     */
    void setOverlayChoices(java.util.List<String> choices,int selected);
    /**
     * Returns the current consumer selector index.
     * @return selected index, or {@code -1} when no selection is installed
     */
    int overlaySelection();
    /**
     * Native requested and active presentation settings.
     * @param maxBounces requested maximum path depth
     * @param diffuseBounces requested diffuse path depth
     * @param reflexMode requested Reflex mode
     * @param frameGenerationMode requested frame-generation mode
     * @param generatedFrames generated frames reported for the current interval
     * @param activeReflexMode active native Reflex mode
     * @param activeFrameGenerationMode active native frame-generation mode
     * @param framesPresentedSinceLastNativeQuery frames counted during the native query interval
     * @param reflexSupported whether the native runtime reports Reflex support
     * @param frameGenerationSupported whether the native runtime reports frame-generation support
     */
    record PresentationState(int maxBounces,int diffuseBounces,int reflexMode,int frameGenerationMode,
            int generatedFrames,int activeReflexMode,int activeFrameGenerationMode,int framesPresentedSinceLastNativeQuery,
            boolean reflexSupported,boolean frameGenerationSupported) { }
    /**
     * Original tone-mapping exposure state.
     * @param automatic whether automatic exposure is enabled
     * @param value native exposure value
     * @param compensation manual compensation in stops
     */
    record ExposureState(boolean automatic,float value,float compensation) { }
    /**
     * Native window, framebuffer, render and display dimensions.
     * @param requestedWidth requested window width
     * @param requestedHeight requested window height
     * @param windowWidth actual window width
     * @param windowHeight actual window height
     * @param framebufferWidth actual framebuffer width
     * @param framebufferHeight actual framebuffer height
     * @param renderWidth internal rendering width
     * @param renderHeight internal rendering height
     * @param displayWidth output display width
     * @param displayHeight output display height
     * @param maximized whether the native window is maximized
     * @param fullscreen whether the native window is fullscreen
     */
    record WindowState(int requestedWidth,int requestedHeight,int windowWidth,int windowHeight,
            int framebufferWidth,int framebufferHeight,int renderWidth,int renderHeight,
            int displayWidth,int displayHeight,boolean maximized,boolean fullscreen) { }
    /**
     * Native rendering modes and selected shader paths.
     * @param realtime whether realtime rendering is active
     * @param reconstruction active reconstruction mode
     * @param dlssQuality native DLSS quality value
     * @param samplesPerPixel configured samples per pixel
     * @param standaloneDenoiser whether the standalone denoiser is enabled
     * @param denoiser selected standalone denoiser
     * @param denoiserIntegrations number of active denoiser integrations
     * @param radianceCacheMode active radiance-cache mode
     * @param restirDi whether ReSTIR direct illumination is active
     * @param restirGi whether ReSTIR global illumination is active
     * @param dxHitObjectPath whether the DirectX hit-object path is selected
     * @param dxReorderThreads whether DirectX thread reordering is active
     * @param nvapiHitObjectPath whether the NVAPI hit-object path is selected
     * @param nvapiReorderThreads whether NVAPI thread reordering is active
     * @param subSamplingMode native sub-sampling mode
     * @param externalLighting whether lighting is supplied externally
     * @param analyticBeauty whether analytic beauty transport is active
     */
    record RenderingState(boolean realtime,Reconstruction reconstruction,int dlssQuality,int samplesPerPixel,
            boolean standaloneDenoiser,Denoiser denoiser,int denoiserIntegrations,int radianceCacheMode,
            boolean restirDi,boolean restirGi,boolean dxHitObjectPath,boolean dxReorderThreads,
            boolean nvapiHitObjectPath,boolean nvapiReorderThreads,int subSamplingMode,boolean externalLighting,
            boolean analyticBeauty) { }
    /** Waits until previously submitted native rendering work is idle. */
    void awaitIdle();
    /**
     * Captures the current native presentation image as a PNG.
     * @param png destination path
     */
    void capture(Path png);
}
