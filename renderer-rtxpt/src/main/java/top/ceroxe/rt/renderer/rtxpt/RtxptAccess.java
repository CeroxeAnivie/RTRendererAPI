package top.ceroxe.rt.renderer.rtxpt;

import java.nio.file.Path;

/** Original RTXPT execution and window access, plus external boundary observations. */
public interface RtxptAccess extends RtxptDiagnosticAccess {
    enum Reconstruction { NONE, TAA, DLSS, DLSS_RR }
    void loadNativeScene(Path scene);
    long windowHandle();
    void setWindowTitle(String title);
    /** Sets the native manual exposure compensation in stops, in [-12, 12]. */
    void setExposureOffset(float offset);
    /** Reads native requested and active settings; this is not a count of presented frames. */
    PresentationState presentationState();
    /** Reads the original tone-mapping exposure fields. */
    ExposureState exposureState();
    /** Actual native window, framebuffer, internal rendering and output dimensions. */
    WindowState windowState();
    /** Actual native standalone denoiser selection, independently of its enabled state. */
    Denoiser denoiserState();
    /** Actual native modes and shader-path flags, not GPU execution counters. */
    RenderingState renderingState();
    enum Denoiser { REBLUR, RELAX }
    /** Consumer HUD text rendered through the native UI pass. */
    void setOverlayText(String text);
    /** Replaces the consumer selector; an empty list requires selected=-1. */
    void setOverlayChoices(java.util.List<String> choices,int selected);
    /** Current consumer selector index, or -1 when no choices are installed. */
    int overlaySelection();
    /** Native observations; the presentation count covers the SDK's own query interval. */
    record PresentationState(int maxBounces,int diffuseBounces,int reflexMode,int frameGenerationMode,
            int generatedFrames,int activeReflexMode,int activeFrameGenerationMode,int framesPresentedSinceLastNativeQuery,
            boolean reflexSupported,boolean frameGenerationSupported) { }
    record ExposureState(boolean automatic,float value,float compensation) { }
    record WindowState(int requestedWidth,int requestedHeight,int windowWidth,int windowHeight,
            int framebufferWidth,int framebufferHeight,int renderWidth,int renderHeight,
            int displayWidth,int displayHeight,boolean maximized,boolean fullscreen) { }
    record RenderingState(boolean realtime,Reconstruction reconstruction,int dlssQuality,int samplesPerPixel,
            boolean standaloneDenoiser,Denoiser denoiser,int denoiserIntegrations,int radianceCacheMode,
            boolean restirDi,boolean restirGi,boolean dxHitObjectPath,boolean dxReorderThreads,
            boolean nvapiHitObjectPath,boolean nvapiReorderThreads,int subSamplingMode,boolean externalLighting,
            boolean analyticBeauty) { }
    void awaitIdle();
    void capture(Path png);
}
