package top.ceroxe.rt.renderer.rtxpt;

import java.util.Objects;
import top.ceroxe.rt.renderer.api.*;

/** Independent lighting over the original RTXPT scene and acceleration structures. */
public final class RtxptRenderers {
    private RtxptRenderers() { }
    /** Original beauty pipeline with its window hidden; rendering and native presentation still run. */
    public static Renderer openHiddenWindow(RendererConfig config){
        return new RtxptRenderer(Objects.requireNonNull(config),true);
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
