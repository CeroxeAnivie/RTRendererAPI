package top.ceroxe.rt.renderer.api;

/** Pure public-contract reference checks. No JNI, renderer or GPU is created. */
public final class HostSurfaceAugmentationSelfTest {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        var style=HostSurfaceAugmentation.defaults(1280,800,HostSurfaceAugmentation.ColorDomain.DISPLAY_LINEAR);
        var plan=HostSurfacePlan.augmentLitBase(2560,1600,HostSurfacePlan.HOST_DIRECT|HostSurfacePlan.HOST_AMBIENT);
        style.validateFor(plan.withOutputSignals(HostSurfacePlan.DIRECT_MODULATION|HostSurfacePlan.DIFFUSE_INDIRECT));
        require(style.nativeSignalMask(HostSurfacePlan.COMBINED)==3,"Combined needs actual gain and indirect evaluation");
        require(style.nativeSignalMask(HostSurfacePlan.DIRECT_MODULATION)==1,"Gain-only does not execute GI");
        require(style.nativeSignalMask(HostSurfacePlan.DIFFUSE_INDIRECT)==2,"GI-only does not execute direct correction");
        require(style.nativeSignalMask(HostSurfacePlan.COVERAGE|HostSurfacePlan.EMISSION)==0,"Metadata/emission requires no illumination rays");
        require(HostSurfaceAugmentation.compose(0,15.65f,0,0,false)==0,"A black host base is not invented from albedo or gain");
        require(HostSurfaceAugmentation.compose(2,.5f,.25f,.5f,true)==1.5f,"Host-included emission is preserved once");
        require(HostSurfaceAugmentation.compose(1.5f,.5f,.25f,.5f,false)==1.5f,"API-owned emission is added once");
        require(HostSurfaceAugmentation.compose(Float.MAX_VALUE,Float.MAX_VALUE,0,0,false)==65504,"Finite half-float signal clamp");
        boolean rejected=false;try{style.validateFor(plan.withOutputSignals(HostSurfacePlan.SPECULAR));}catch(IllegalArgumentException expected){rejected=true;}
        require(rejected,"Finite style augmentation cannot pretend to provide physical specular transport");
        rejected=false;try{HostSurfaceAugmentation.compose(Float.NaN,1,0,0,false);}catch(IllegalArgumentException expected){rejected=true;}
        require(rejected,"Nonfinite composition input rejected");
        System.out.println("RTXPT_710_HOST_AUGMENTATION_CONTRACT PASS blackBase=true emissionOnce=true demandMask=true domainsExplicit=true noGpu=true");
    }
}
