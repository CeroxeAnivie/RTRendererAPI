package top.ceroxe.rt.renderer.rtxpt;

import java.util.Objects;
import java.nio.file.Path;
import top.ceroxe.rt.renderer.api.*;

/** Explicit original RTXPT rendering and separately named adapter lighting extensions. */
public final class RtxptRenderers {
    private RtxptRenderers() { }
    /** Process-local native session initialization state, including compute PSO cache activity.
     * @param stage current initialization stage
     * @param activePso compute pipeline being loaded or created, if any
     * @param elapsedMillis time since native session startup
     * @param cacheHits number of compute pipelines loaded from the D3D12 library
     * @param cacheMisses number of compute pipelines created by the driver
     * @param terminalState native startup state
     */
    public record StartupProgress(String stage, String activePso, long elapsedMillis,
            long cacheHits, long cacheMisses, String terminalState) { }

    /** Reads the current native startup snapshot without filesystem polling.
     * @return a point-in-time startup snapshot
     */
    public static StartupProgress startupProgress() {
        if (!RtxptNative.loaded()) return new StartupProgress("waiting", "", 0, 0, 0, "not-started");
        String[] values = RtxptNative.startupProgress();
        if (values == null || values.length < 6) {
            return new StartupProgress("unknown", "", 0, 0, 0, "unknown");
        }
        return new StartupProgress(values[0], values[1], parse(values[2]), parse(values[3]),
                parse(values[4]), values[5]);
    }

    private static long parse(String value) {
        try { return Long.parseLong(value); }
        catch (NumberFormatException ignored) { return 0; }
    }
    /**
     * Opens the configured beauty pipeline with its native window hidden; rendering and presentation continue.
     * @param config renderer configuration
     * @return configured renderer
     */
    public static Renderer openHiddenWindow(RendererConfig config){
        return new RtxptRenderer(Objects.requireNonNull(config),true);
    }
    /**
     * Opens the full, unmodified AdvancedSample transport without adapter lighting extensions.
     * @param config renderer configuration
     * @return renderer using the original RTXPT transport
     */
    public static Renderer openOfficial(RendererConfig config){
        return new RtxptOfficialRenderer(Objects.requireNonNull(config),false);
    }
    /**
     * Opens the official RTXPT transport with its native presentation window hidden.
     * @param config renderer configuration
     * @return renderer using the original RTXPT transport
     */
    public static Renderer openOfficialHiddenWindow(RendererConfig config){
        return new RtxptOfficialRenderer(Objects.requireNonNull(config),true);
    }
    /**
     * Opens the adapter's analytic beauty transport; rejects other or incompatible configurations.
     * @param config configuration selecting analytic direct beauty
     * @return renderer using analytic beauty transport
     */
    public static Renderer openAnalytic(RendererConfig config){
        RtxptOptions.requireAnalytic(Objects.requireNonNull(config));
        return new RtxptRenderer(config);
    }
    /**
     * Opens analytic beauty transport with its native presentation window hidden.
     * @param config configuration selecting analytic direct beauty
     * @return renderer using analytic beauty transport
     */
    public static Renderer openAnalyticHiddenWindow(RendererConfig config){
        RtxptOptions.requireAnalytic(Objects.requireNonNull(config));
        return new RtxptRenderer(config,true);
    }
    /**
     * Creates a configuration for analytic beauty with 1 SPP, full sampling,
     * no stochastic denoiser or cache, and capability-gated presentation.
     * @return analytic beauty renderer configuration
     */
    public static RendererConfig analyticConfiguration(){
        var base=RendererConfig.presentation();var path=base.pathTracing();
        return base.copyBuilder().pathTracing(path.withQuality(path.quality().withBeautyTransport(
            PathTracingQualityOptions.BeautyTransport.ANALYTIC_DIRECT)))
            .denoising(DenoisingOptions.disabled()).build();
    }
    /**
     * Opens native lighting with the default native-lighting configuration.
     * @param plan initial native lighting plan
     * @return renderer configured with the supplied plan
     */
    public static Renderer openNativeLighting(NativeLightingPlan plan){return openNativeLighting(nativeLightingConfiguration(),plan);}
    /**
     * Opens native lighting using the supplied renderer configuration.
     * @param config renderer configuration
     * @param plan initial native lighting plan
     * @return renderer configured with the supplied plan
     */
    public static Renderer openNativeLighting(RendererConfig config,NativeLightingPlan plan){
        Objects.requireNonNull(config);Objects.requireNonNull(plan);
        var renderer=new RtxptRenderer(config,true);
        try{renderer.nativeLightingPlan(plan);return renderer;}
        catch(RuntimeException|Error failure){try{renderer.close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    /**
     * Opens a native lighting renderer with a hidden presentation window and
     * caches the packaged runtime beneath the consumer-owned path.
     *
     * @param config renderer configuration
     * @param plan initial native lighting plan
     * @param runtimeCacheRoot non-null cache root for the packaged runtime
     * @return renderer configured with the supplied native lighting plan
     * @see RtxptRuntime#binDirectory(Path)
     */
    public static Renderer openNativeLighting(RendererConfig config,NativeLightingPlan plan,Path runtimeCacheRoot){
        Objects.requireNonNull(config);Objects.requireNonNull(plan);Objects.requireNonNull(runtimeCacheRoot);
        var renderer=new RtxptRenderer(config,true,false,runtimeCacheRoot);
        try{renderer.nativeLightingPlan(plan);return renderer;}
        catch(RuntimeException|Error failure){try{renderer.close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    /**
     * Opens native lighting with DLSS Ray Reconstruction enabled.
     * @param plan initial native lighting plan
     * @return renderer configured for reconstructed native lighting
     */
    public static Renderer openReconstructedNativeLighting(NativeLightingPlan plan){
        var renderer=(RtxptRenderer)openNativeLighting(plan);
        try{renderer.lightingReconstruction(NativeLightingReconstruction.DLSS_RR);return renderer;}
        catch(RuntimeException|Error failure){renderer.close();throw failure;}
    }
    /**
     * Creates the default native-lighting configuration without beauty
     * reconstruction, radiance cache, resampling, bloom, or CPU readback.
     * @return native-lighting renderer configuration
     */
    public static RendererConfig nativeLightingConfiguration(){
        var base=RendererConfig.recommended();var path=base.pathTracing();
        var quality=path.quality().withPostProcessing(path.quality().postProcessing().withBloom(false));
        return base.copyBuilder().frameReconstruction(FrameReconstructionOptions.disabled())
            .denoising(DenoisingOptions.disabled()).pathTracing(path.withQuality(quality)).build();
    }
}
