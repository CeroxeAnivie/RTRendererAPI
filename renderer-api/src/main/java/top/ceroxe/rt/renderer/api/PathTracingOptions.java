package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Vendor-neutral path budgets and optional reuse techniques, shared by ordinary and expert policy.
 * @param samplesPerPixel camera samples per rendered pixel
 * @param maxBounces maximum path depth
 * @param diffuseBounces maximum diffuse path depth
 * @param radianceCache reusable indirect-radiance cache preference
 * @param directLightResampling temporal/spatial direct-light sample reuse preference
 * @param indirectLightResampling temporal/spatial indirect-light sample reuse preference
 * @param exactOpticalInterfaces exact dielectric branch budget, 1..6; used by supporting scene-lighting paths
 * @param quality managed lighting, cache and reconstruction-guide workload
 */
public record PathTracingOptions(int samplesPerPixel,int maxBounces,int diffuseBounces,
        RendererFeaturePreference radianceCache,RendererFeaturePreference directLightResampling,
        RendererFeaturePreference indirectLightResampling,int exactOpticalInterfaces,PathTracingQualityOptions quality) {
    public PathTracingOptions {
        Objects.requireNonNull(radianceCache);Objects.requireNonNull(directLightResampling);Objects.requireNonNull(indirectLightResampling);
        Objects.requireNonNull(quality);
        if(samplesPerPixel<1 || samplesPerPixel>64 || maxBounces<1 || maxBounces>64 || diffuseBounces<0 || diffuseBounces>maxBounces)
            throw new IllegalArgumentException("Invalid path tracing sample/depth budget");
        if(exactOpticalInterfaces<1 || exactOpticalInterfaces>6)
            throw new IllegalArgumentException("exactOpticalInterfaces must be in [1,6]");
        if(samplesPerPixel!=1 && (directLightResampling.requested() || indirectLightResampling.requested()))
            throw new IllegalArgumentException("Light resampling requires one camera sample per pixel");
    }
    /** Uses the moderate-GPU quality budget. */
    public PathTracingOptions(int samplesPerPixel,int maxBounces,int diffuseBounces,
            RendererFeaturePreference radianceCache,RendererFeaturePreference directLightResampling,
            RendererFeaturePreference indirectLightResampling) {
        this(samplesPerPixel,maxBounces,diffuseBounces,radianceCache,directLightResampling,indirectLightResampling,4,PathTracingQualityOptions.defaults());
    }
    public PathTracingOptions(int samplesPerPixel,int maxBounces,int diffuseBounces,
            RendererFeaturePreference radianceCache,RendererFeaturePreference directLightResampling,
            RendererFeaturePreference indirectLightResampling,int exactOpticalInterfaces) {
        this(samplesPerPixel,maxBounces,diffuseBounces,radianceCache,directLightResampling,indirectLightResampling,exactOpticalInterfaces,PathTracingQualityOptions.defaults());
    }
    /** Select a lower exact-branch budget for a weaker GPU; later transport remains stochastic. */
    public PathTracingOptions withExactOpticalInterfaces(int count) {
        return new PathTracingOptions(samplesPerPixel,maxBounces,diffuseBounces,radianceCache,
            directLightResampling,indirectLightResampling,count,quality);
    }
    public PathTracingOptions withQuality(PathTracingQualityOptions value){return new PathTracingOptions(samplesPerPixel,maxBounces,diffuseBounces,radianceCache,directLightResampling,indirectLightResampling,exactOpticalInterfaces,value);}
    /** One native sample, default path depth, no optional reuse. */
    public static PathTracingOptions defaults(){return RendererPerformanceTier.MEDIUM.pathTracing(false);}
    /** Game-oriented default: one sample and a preferred radiance cache. */
    public static PathTracingOptions recommended(){return RendererPerformanceTier.MEDIUM.pathTracing(true);}
}
