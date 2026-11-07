package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Camera path budgets and optional native light/cache reuse techniques. */
public record PathTracingOptions(int samplesPerPixel,int maxBounces,int diffuseBounces,
        RendererFeaturePreference radianceCache,RendererFeaturePreference directLightResampling,
        RendererFeaturePreference indirectLightResampling,PathTracingQualityOptions quality) {
    public PathTracingOptions {
        Objects.requireNonNull(radianceCache);Objects.requireNonNull(directLightResampling);Objects.requireNonNull(indirectLightResampling);Objects.requireNonNull(quality);
        if(samplesPerPixel<1||samplesPerPixel>64||maxBounces<1||maxBounces>64||diffuseBounces<0||diffuseBounces>maxBounces)
            throw new IllegalArgumentException("Invalid path tracing sample/depth budget");
        if(samplesPerPixel!=1&&(directLightResampling.requested()||indirectLightResampling.requested()))
            throw new IllegalArgumentException("Light resampling requires one camera sample per pixel");
    }
    public PathTracingOptions(int samplesPerPixel,int maxBounces,int diffuseBounces,
            RendererFeaturePreference radianceCache,RendererFeaturePreference directLightResampling,
            RendererFeaturePreference indirectLightResampling) {
        this(samplesPerPixel,maxBounces,diffuseBounces,radianceCache,directLightResampling,indirectLightResampling,PathTracingQualityOptions.defaults());
    }
    public PathTracingOptions withQuality(PathTracingQualityOptions value){return new PathTracingOptions(samplesPerPixel,maxBounces,diffuseBounces,radianceCache,directLightResampling,indirectLightResampling,value);}
    public static PathTracingOptions defaults(){return RendererPerformanceTier.MEDIUM.pathTracing(false);}
    public static PathTracingOptions recommended(){return RendererPerformanceTier.MEDIUM.pathTracing(true);}
}