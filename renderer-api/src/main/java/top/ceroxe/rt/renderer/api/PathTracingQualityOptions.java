package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Native path-tracing quality controls supported by the public backend settings. */
public record PathTracingQualityOptions(LightSampling lighting,
        int denoisingLayers,int denoisingLayerMaxDepth,int nestedDielectricsQuality,
        SubSampling subSampling,float textureLodBias,boolean halfPrecision,PostProcessing postProcessing,
        BeautyTransport beautyTransport) {
    public PathTracingQualityOptions {
        Objects.requireNonNull(lighting);Objects.requireNonNull(subSampling);Objects.requireNonNull(postProcessing);Objects.requireNonNull(beautyTransport);
        range(denoisingLayers,1,3,"denoisingLayers");range(denoisingLayerMaxDepth,1,15,"denoisingLayerMaxDepth");
        range(nestedDielectricsQuality,0,2,"nestedDielectricsQuality");finite(textureLodBias,-8,8,"textureLodBias");
    }
    public PathTracingQualityOptions(LightSampling lighting,int denoisingLayers,int denoisingLayerMaxDepth,
            int nestedDielectricsQuality,SubSampling subSampling,float textureLodBias,boolean halfPrecision,PostProcessing postProcessing){
        this(lighting,denoisingLayers,denoisingLayerMaxDepth,nestedDielectricsQuality,subSampling,textureLodBias,halfPrecision,postProcessing,BeautyTransport.NATIVE);
    }
    /** NATIVE retains the complete upstream integrator. ANALYTIC_DIRECT is an
     * explicit API extension: finite-range analytic lights, RGB transmission
     * visibility, four exactly split interfaces and stable native BSDF continuation.
     * It does not evaluate diffuse indirect illumination or stochastic denoising. */
    public enum BeautyTransport { NATIVE, ANALYTIC_DIRECT }
    public PathTracingQualityOptions withBeautyTransport(BeautyTransport value){return new PathTracingQualityOptions(lighting,denoisingLayers,denoisingLayerMaxDepth,nestedDielectricsQuality,subSampling,textureLodBias,halfPrecision,postProcessing,value);}
    public static PathTracingQualityOptions defaults(){return new PathTracingQualityOptions(
        LightSampling.defaults(),3,9,1,SubSampling.FULL,-1,false,PostProcessing.defaults());}
    public PathTracingQualityOptions withLighting(LightSampling value){return new PathTracingQualityOptions(value,denoisingLayers,denoisingLayerMaxDepth,nestedDielectricsQuality,subSampling,textureLodBias,halfPrecision,postProcessing,beautyTransport);}
    public PathTracingQualityOptions withDenoisingLayers(int count,int depth){return new PathTracingQualityOptions(lighting,count,depth,nestedDielectricsQuality,subSampling,textureLodBias,halfPrecision,postProcessing,beautyTransport);}
    public PathTracingQualityOptions withNestedDielectricsQuality(int value){return new PathTracingQualityOptions(lighting,denoisingLayers,denoisingLayerMaxDepth,value,subSampling,textureLodBias,halfPrecision,postProcessing,beautyTransport);}
    public PathTracingQualityOptions withSubSampling(SubSampling value){return new PathTracingQualityOptions(lighting,denoisingLayers,denoisingLayerMaxDepth,nestedDielectricsQuality,value,textureLodBias,halfPrecision,postProcessing,beautyTransport);}
    public PathTracingQualityOptions withTextureLodBias(float value){return new PathTracingQualityOptions(lighting,denoisingLayers,denoisingLayerMaxDepth,nestedDielectricsQuality,subSampling,value,halfPrecision,postProcessing,beautyTransport);}
    public PathTracingQualityOptions withHalfPrecision(boolean value){return new PathTracingQualityOptions(lighting,denoisingLayers,denoisingLayerMaxDepth,nestedDielectricsQuality,subSampling,textureLodBias,value,postProcessing,beautyTransport);}
    public PathTracingQualityOptions withPostProcessing(PostProcessing value){return new PathTracingQualityOptions(lighting,denoisingLayers,denoisingLayerMaxDepth,nestedDielectricsQuality,subSampling,textureLodBias,halfPrecision,value,beautyTransport);}

    /** Native post-processing passes and manual exposure compensation in stops. */
    public record PostProcessing(boolean bloom,float bloomRadius,float bloomIntensity,boolean toneMapping,float exposureOffset) {
        public PostProcessing {finite(bloomRadius,0,128,"bloomRadius");finite(bloomIntensity,0,100,"bloomIntensity");finite(exposureOffset,-12,12,"exposureOffset");}
        public PostProcessing(boolean bloom,float bloomRadius,float bloomIntensity,boolean toneMapping){this(bloom,bloomRadius,bloomIntensity,toneMapping,0);}
        public static PostProcessing defaults(){return new PostProcessing(true,8,.004f,true,0);}
        public PostProcessing withBloom(boolean enabled){return new PostProcessing(enabled,bloomRadius,bloomIntensity,toneMapping,exposureOffset);}
        public PostProcessing withExposureOffset(float value){return new PostProcessing(bloom,bloomRadius,bloomIntensity,toneMapping,value);}
    }
    /** AUTO preserves the upstream sampler; explicit modes select native NEE implementations. */
    public enum LightSampler { AUTO, UNIFORM, POWER, ADAPTIVE }
    /** Fraction of the internal render grid sampled before reconstruction. */
    public enum SubSampling { FULL, THREE_QUARTERS, HALF, QUARTER }
    /** Native direct-light estimator workload. */
    public record LightSampling(LightSampler sampler,int samples,int candidates,float localCandidateRatio,int adaptiveQuality) {
        public LightSampling {
            Objects.requireNonNull(sampler);range(samples,1,63,"light samples");range(candidates,1,63,"light candidates");
            finite(localCandidateRatio,0,1,"localCandidateRatio");range(adaptiveQuality,0,2,"adaptiveQuality");
        }
        public static LightSampling defaults(){return new LightSampling(LightSampler.AUTO,1,5,.65f,0);}
        public LightSampling withSampler(LightSampler value){return new LightSampling(value,samples,candidates,localCandidateRatio,adaptiveQuality);}
        public LightSampling withSamples(int full,int candidate){return new LightSampling(sampler,full,candidate,localCandidateRatio,adaptiveQuality);}
        public LightSampling withLocalCandidateRatio(float value){return new LightSampling(sampler,samples,candidates,value,adaptiveQuality);}
        public LightSampling withAdaptiveQuality(int value){return new LightSampling(sampler,samples,candidates,localCandidateRatio,value);}
    }
    private static void range(int value,int min,int max,String name){if(value<min||value>max)throw new IllegalArgumentException(name+" must be in ["+min+","+max+"]");}
    private static void finite(float value,float min,float max,String name){if(!Float.isFinite(value)||value<min||value>max)throw new IllegalArgumentException(name+" must be finite and in ["+min+","+max+"]");}
}
