package top.ceroxe.rt.renderer.rtxpt;

import java.util.Objects;
import top.ceroxe.rt.renderer.api.*;

/** Explicit original RTXPT rendering and separately named adapter lighting extensions. */
public final class RtxptRenderers {
    private RtxptRenderers() { }
    /** Configured beauty pipeline with its window hidden; rendering and native presentation still run. */
    public static Renderer openHiddenWindow(RendererConfig config){
        return new RtxptRenderer(Objects.requireNonNull(config),true);
    }
    /** Full, unmodified AdvancedSample transport; adapter lighting extensions are unavailable. */
    public static Renderer openOfficial(RendererConfig config){
        return new RtxptOfficialRenderer(Objects.requireNonNull(config),false);
    }
    /** Same official transport with a hidden native presentation window. */
    public static Renderer openOfficialHiddenWindow(RendererConfig config){
        return new RtxptOfficialRenderer(Objects.requireNonNull(config),true);
    }
    /** Explicit adapter analytic beauty transport; rejects a different or incompatible configuration. */
    public static Renderer openAnalytic(RendererConfig config){
        RtxptOptions.requireAnalytic(Objects.requireNonNull(config));
        return new RtxptRenderer(config);
    }
    /** Same analytic transport with a hidden native presentation window. */
    public static Renderer openAnalyticHiddenWindow(RendererConfig config){
        RtxptOptions.requireAnalytic(Objects.requireNonNull(config));
        return new RtxptRenderer(config,true);
    }
    /** Analytic beauty with 1 SPP, SR, no stochastic denoiser/cache, and capability-gated presentation. */
    public static RendererConfig analyticConfiguration(){
        var base=RendererConfig.presentation();var path=base.pathTracing();
        return base.copyBuilder().pathTracing(path.withQuality(path.quality().withBeautyTransport(
            PathTracingQualityOptions.BeautyTransport.ANALYTIC_DIRECT)))
            .denoising(DenoisingOptions.disabled()).build();
    }
    public static Renderer openNativeLighting(NativeLightingPlan plan){return openNativeLighting(nativeLightingConfiguration(),plan);}
    public static Renderer openNativeLighting(RendererConfig config,NativeLightingPlan plan){
        Objects.requireNonNull(config);Objects.requireNonNull(plan);
        var renderer=new RtxptRenderer(config,true);
        try{renderer.nativeLightingPlan(plan);return renderer;}
        catch(RuntimeException|Error failure){try{renderer.close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    public static Renderer openReconstructedNativeLighting(NativeLightingPlan plan){
        var renderer=(RtxptRenderer)openNativeLighting(plan);
        try{renderer.lightingReconstruction(NativeLightingReconstruction.DLSS_RR);return renderer;}
        catch(RuntimeException|Error failure){renderer.close();throw failure;}
    }
    /** No beauty reconstruction, radiance cache, resampling, bloom or CPU readback. */
    public static RendererConfig nativeLightingConfiguration(){
        var base=RendererConfig.recommended();var path=base.pathTracing();
        var quality=path.quality().withPostProcessing(path.quality().postProcessing().withBloom(false));
        return base.copyBuilder().frameReconstruction(FrameReconstructionOptions.disabled())
            .denoising(DenoisingOptions.disabled()).pathTracing(path.withQuality(quality)).build();
    }
}
