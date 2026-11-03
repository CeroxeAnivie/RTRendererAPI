package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Deterministic correction of a host-owned, already lit diffuse layer. This is
 * the native finite style evaluator applied to supplied primary surfaces, not
 * a replacement physical path tracer. No camera-primary rays are generated.
 *
 * <p>Augmentation inputs append two RGBA16F planes to the eight surface planes:
 * resolved local/sky baseline B,S and visibility/fog V,F, all in [0,1]; and an
 * optional-to-read straight-alpha host-lit color. The latter is required when
 * COMBINED is requested. BASE_COLOR remains the actual material albedo.
 *
 * <p>Color inputs, source RGB and indirect contribution use the declared common
 * working domain. There is no implicit exposure, tone mapping or conversion
 * between scene-linear and display-linear values. The host-lit color is the
 * selected diffuse lighting layer before host fog, HUD and unrelated overlays.
 * The host applies its final fog once after composition; F only attenuates the
 * correction and new GI. Declaring display-linear does not create HDR radiance.
 */
public record HostSurfaceAugmentation(NativeLightingPlan lighting,ColorDomain colorDomain) {
    public static final int ABI_VERSION=1;
    public static final int SUPPORTED_OUTPUTS=HostSurfacePlan.COMBINED|HostSurfacePlan.DIRECT_MODULATION
        |HostSurfacePlan.DIFFUSE_INDIRECT|HostSurfacePlan.EMISSION|HostSurfacePlan.COVERAGE;
    public enum ColorDomain { SCENE_LINEAR, DISPLAY_LINEAR }
    public HostSurfaceAugmentation {Objects.requireNonNull(lighting);Objects.requireNonNull(colorDomain);}
    public static HostSurfaceAugmentation defaults(int width,int height,ColorDomain domain){
        return new HostSurfaceAugmentation(NativeLightingPlan.defaults(width,height),domain);
    }
    public void validateFor(HostSurfacePlan plan){
        Objects.requireNonNull(plan);
        if(plan.composition()!=HostSurfacePlan.Composition.HOST_LIT_AUGMENTATION)
            throw new IllegalArgumentException("Augmentation parameters require HOST_LIT_AUGMENTATION");
        if((plan.outputSignals()&~SUPPORTED_OUTPUTS)!=0)
            throw new IllegalArgumentException("Finite augmentation produces gain, indirect, emission, coverage and optional host-color composition; select split transport for physical diffuse/specular/transmission AOVs");
    }
    /** Native evaluator signals required by this host output set; zero means only metadata/emission. */
    public int nativeSignalMask(int outputs){
        if(outputs<1||(outputs&~SUPPORTED_OUTPUTS)!=0)throw new IllegalArgumentException("Unknown augmentation output");
        return ((outputs&(HostSurfacePlan.COMBINED|HostSurfacePlan.DIRECT_MODULATION))!=0?1:0)
            |((outputs&(HostSurfacePlan.COMBINED|HostSurfacePlan.DIFFUSE_INDIRECT))!=0?2:0);
    }
    /** Exact composition reference for one working-domain RGB channel; emission is added once. */
    public static float compose(float hostLit,float gain,float indirect,float emission,boolean hostContainsEmission){
        for(float value:new float[]{hostLit,gain,indirect,emission})
            if(!Float.isFinite(value)||value<0)throw new IllegalArgumentException("Composition inputs must be finite and nonnegative");
        double base=hostContainsEmission?Math.max(0,(double)hostLit-emission):hostLit;
        return (float)Math.min(65504,base*gain+indirect+emission);
    }
}
