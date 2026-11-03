package top.ceroxe.rt.renderer.rtxpt;

import java.util.Objects;
import top.ceroxe.rt.renderer.api.*;

/** Explicit RTXPT execution entry points. No Vulkan renderer is created. */
public final class RtxptRenderers {
    private RtxptRenderers() { }
    /**
     * Opens supplied-surface lighting with a compatible ordinary policy. Split
     * transport prefers RR; finite host-lit augmentation has no reconstruction.
     * GPU input/output leases remain an explicit host integration contract.
     */
    public static Renderer openHostSurface(HostSurfacePlan plan){
        Objects.requireNonNull(plan,"plan");
        return openHostSurface(hostSurfaceConfiguration(plan.composition()),plan);
    }
    /** Opens finite native lighting without requiring unrelated transport options. */
    public static Renderer openNativeLighting(NativeLightingPlan plan){
        return openNativeLighting(nativeLightingConfiguration(),Objects.requireNonNull(plan,"plan"));
    }
    /** Opens finite host-lit augmentation using its compatible ordinary policy. */
    public static Renderer openHostSurface(HostSurfacePlan plan,HostSurfaceAugmentation augmentation){
        Objects.requireNonNull(plan,"plan");
        return openHostSurface(hostSurfaceConfiguration(plan.composition()),plan,augmentation);
    }
    /**
     * Returns the ordinary finite-signal policy: scene-analytic sources, no beauty
     * reconstruction, cache, resampling, bloom, frame generation or CPU readback.
     * Use {@link RendererConfig#copyBuilder()} to tune independent expert controls.
     */
    public static RendererConfig nativeLightingConfiguration(){
        return lightingConfiguration(false);
    }
    /**
     * Returns a complete policy compatible with the selected surface contract.
     * The split policy prefers actual RR; augmentation keeps deterministic signals
     * unreconstructed. This factory allocates no renderer or native resources.
     */
    public static RendererConfig hostSurfaceConfiguration(HostSurfacePlan.Composition composition){
        Objects.requireNonNull(composition,"composition");
        return lightingConfiguration(composition==HostSurfacePlan.Composition.SPLIT_SURFACE_LIGHTING);
    }
    private static RendererConfig lightingConfiguration(boolean reconstruct){
        var base=RendererConfig.recommended();var path=base.pathTracing();
        var quality=path.quality().withLighting(path.quality().lighting()
                .withInput(PathTracingQualityOptions.LightingInput.SCENE_ANALYTIC))
                .withPostProcessing(path.quality().postProcessing().withBloom(false));
        return base.copyBuilder().cpuFrameReadbackEnabled(false)
                .frameReconstruction(reconstruct?base.frameReconstruction():FrameReconstructionOptions.disabled())
                .denoising(reconstruct?DenoisingOptions.recommended():DenoisingOptions.disabled())
                .pathTracing(path.withQuality(quality)).build();
    }
    /**
     * Expert entry point. Uses the complete supplied configuration unchanged;
     * unsupported combinations fail before native scene creation.
     */
    public static Renderer openHostSurface(RendererConfig configuration,HostSurfacePlan plan){
        Objects.requireNonNull(configuration,"configuration");Objects.requireNonNull(plan,"plan");
        var renderer=new RtxptRenderer(configuration,true);
        try{renderer.hostSurfacePlan(plan);return renderer;}
        catch(RuntimeException|Error failure){try{renderer.close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    /**
     * Expert finite-lighting entry point. Full-image RR/SR and stochastic caches
     * must be disabled explicitly; incompatible policies are never rewritten.
     */
    public static Renderer openNativeLighting(RendererConfig configuration,NativeLightingPlan plan) {
        Objects.requireNonNull(configuration,"configuration");Objects.requireNonNull(plan,"plan");
        var renderer=new RtxptRenderer(configuration,true);
        try {renderer.nativeLightingPlan(plan);return renderer;}
        catch(RuntimeException|Error failure){try{renderer.close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    /** Expert host-lit entry point with explicit renderer and finite-style policies. */
    public static Renderer openHostSurface(RendererConfig configuration,HostSurfacePlan plan,HostSurfaceAugmentation augmentation){
        Objects.requireNonNull(augmentation,"augmentation");
        var renderer=(RtxptRenderer)openHostSurface(configuration,plan);
        try{renderer.hostSurfaceAugmentation(augmentation);return renderer;}
        catch(RuntimeException|Error failure){try{renderer.close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
}
