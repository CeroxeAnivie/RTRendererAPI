package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Vendor-neutral path budgets and optional reuse techniques, shared by ordinary and expert policy.
 * @param samplesPerPixel camera samples per rendered pixel
 * @param maxBounces maximum path depth
 * @param diffuseBounces maximum diffuse path depth
 * @param radianceCache reusable indirect-radiance cache preference
 * @param directLightResampling temporal/spatial direct-light sample reuse preference
 * @param indirectLightResampling temporal/spatial indirect-light sample reuse preference
 */
public record PathTracingOptions(int samplesPerPixel,int maxBounces,int diffuseBounces,
        RendererFeaturePreference radianceCache,RendererFeaturePreference directLightResampling,
        RendererFeaturePreference indirectLightResampling) {
    public PathTracingOptions {
        Objects.requireNonNull(radianceCache);Objects.requireNonNull(directLightResampling);Objects.requireNonNull(indirectLightResampling);
        if(samplesPerPixel<1 || samplesPerPixel>64 || maxBounces<1 || maxBounces>64 || diffuseBounces<0 || diffuseBounces>maxBounces)
            throw new IllegalArgumentException("Invalid path tracing sample/depth budget");
        if(samplesPerPixel!=1 && (directLightResampling.requested() || indirectLightResampling.requested()))
            throw new IllegalArgumentException("Light resampling requires one camera sample per pixel");
    }
    /** One native sample, default path depth, no optional reuse. */
    public static PathTracingOptions defaults(){return new PathTracingOptions(1,20,2,RendererFeaturePreference.DISABLED,RendererFeaturePreference.DISABLED,RendererFeaturePreference.DISABLED);}
    /** Game-oriented default: one sample and a preferred radiance cache. */
    public static PathTracingOptions recommended(){return new PathTracingOptions(1,20,2,RendererFeaturePreference.PREFERRED,RendererFeaturePreference.DISABLED,RendererFeaturePreference.DISABLED);}
}
