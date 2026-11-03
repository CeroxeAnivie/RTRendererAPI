package top.ceroxe.rt.renderer.rtxpt;

import top.ceroxe.rt.renderer.api.*;

/** Policy and admission checks only: no device creation or GPU rendering. */
public final class RtxptModeUsabilitySelfTest {
    private static void require(boolean value,String detail){if(!value)throw new AssertionError(detail);}
    public static void main(String[] args){
        var finite=RtxptRenderers.nativeLightingConfiguration();
        var finiteOptions=RtxptOptions.resolve(RtxptOptions.profile(finite));
        require(finiteOptions.reconstruction()==RtxptAccess.Reconstruction.NONE&&!finiteOptions.standaloneDenoiser(),"Finite ordinary policy must not request beauty reconstruction");
        require(!finiteOptions.restirDi()&&!finiteOptions.restirGi()&&!finiteOptions.sharc()&&!finiteOptions.quality().postProcessing().bloom(),"Finite ordinary policy must avoid conflicting transport work");
        require(!finite.cpuFrameReadbackEnabled()&&!finite.frameGeneration().preference().requested(),"External finite output must not arm CPU/presentation output");
        require(finiteOptions.quality().lighting().input()==PathTracingQualityOptions.LightingInput.SCENE_ANALYTIC,"Finite sources must use their executable lighting contract");
        try(var renderer=RtxptRenderers.openNativeLighting(NativeLightingPlan.defaults(33,25))){
            require(renderer.extension(NativeLightingControl.class).orElseThrow().nativeLightingPlan().width()==33,"Ordinary native opening retains the requested grid");
            require(renderer.diagnostics().latestCompletedFrameSequence()<0,"Opening a policy must not render or fabricate completion");
        }
        var split=RtxptRenderers.hostSurfaceConfiguration(HostSurfacePlan.Composition.SPLIT_SURFACE_LIGHTING);
        var splitOptions=RtxptOptions.resolve(RtxptOptions.profile(split));
        require(splitOptions.reconstruction()==RtxptAccess.Reconstruction.DLSS_RR,"Ordinary split transport must prefer real RR");
        try(var renderer=RtxptRenderers.openHostSurface(new HostSurfacePlan(33,25))){
            require(renderer.extension(HostSurfaceControl.class).orElseThrow().hostSurfacePlan().width()==33,"Ordinary split opening retains surface extent");
        }
        var augmentation=HostSurfacePlan.augmentLitBase(33,25,HostSurfacePlan.HOST_EMISSION);
        try(var renderer=RtxptRenderers.openHostSurface(augmentation)){
            require(renderer.extension(HostSurfaceAugmentationControl.class).isPresent(),"Augmentation control remains explicitly discoverable");
        }
        var advanced=finite.copyBuilder().pathTracing(new PathTracingOptions(3,20,4,
                RendererFeaturePreference.DISABLED,RendererFeaturePreference.DISABLED,RendererFeaturePreference.DISABLED,
                6,finite.pathTracing().quality())).build();
        require(advanced.pathTracing().samplesPerPixel()==3&&advanced.pathTracing().exactOpticalInterfaces()==6,"Expert policies must retain independent explicit budgets");
        boolean rejected=false;
        try(var ignored=RtxptRenderers.openNativeLighting(RendererConfig.pathTracedPresentation(),NativeLightingPlan.defaults(33,25))){
            throw new AssertionError("Expert request was silently rewritten");
        }catch(IllegalArgumentException expected){rejected=true;}
        require(rejected,"Conflicting expert requests require an explicit rejection");
        System.out.println("RTXPT_MODE_USABILITY PASS noGpu=true ordinaryModes=3 expertPolicyPreserved=true");
    }
}
