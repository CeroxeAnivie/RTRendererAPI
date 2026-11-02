package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Explicit quality/cost budgets for managed path tracing. Changes require a renderer rebuild.
 * These are workload choices, not a claim that every backend supports every choice.
 */
public record PathTracingQualityOptions(LightSampling lighting,RadianceCache cache,
        int denoisingLayers,int denoisingLayerMaxDepth,int nestedDielectricsQuality,
        SubSampling subSampling,float textureLodBias,boolean halfPrecision) {
    public PathTracingQualityOptions {
        Objects.requireNonNull(lighting);Objects.requireNonNull(cache);Objects.requireNonNull(subSampling);
        range(denoisingLayers,1,3,"denoisingLayers");range(denoisingLayerMaxDepth,1,15,"denoisingLayerMaxDepth");
        range(nestedDielectricsQuality,0,2,"nestedDielectricsQuality");finite(textureLodBias,-8,8,"textureLodBias");
    }
    /** Moderate-GPU starting point; applications can raise any budget explicitly. */
    public static PathTracingQualityOptions defaults(){return new PathTracingQualityOptions(
        LightSampling.defaults(),RadianceCache.defaults(),3,9,1,SubSampling.FULL,-1,false);}
    public PathTracingQualityOptions withLighting(LightSampling value){return new PathTracingQualityOptions(value,cache,denoisingLayers,denoisingLayerMaxDepth,nestedDielectricsQuality,subSampling,textureLodBias,halfPrecision);}
    public PathTracingQualityOptions withCache(RadianceCache value){return new PathTracingQualityOptions(lighting,value,denoisingLayers,denoisingLayerMaxDepth,nestedDielectricsQuality,subSampling,textureLodBias,halfPrecision);}
    public PathTracingQualityOptions withDenoisingLayers(int count,int depth){return new PathTracingQualityOptions(lighting,cache,count,depth,nestedDielectricsQuality,subSampling,textureLodBias,halfPrecision);}
    public PathTracingQualityOptions withNestedDielectricsQuality(int value){return new PathTracingQualityOptions(lighting,cache,denoisingLayers,denoisingLayerMaxDepth,value,subSampling,textureLodBias,halfPrecision);}
    public PathTracingQualityOptions withSubSampling(SubSampling value){return new PathTracingQualityOptions(lighting,cache,denoisingLayers,denoisingLayerMaxDepth,nestedDielectricsQuality,value,textureLodBias,halfPrecision);}
    public PathTracingQualityOptions withTextureLodBias(float value){return new PathTracingQualityOptions(lighting,cache,denoisingLayers,denoisingLayerMaxDepth,nestedDielectricsQuality,subSampling,value,halfPrecision);}
    public PathTracingQualityOptions withHalfPrecision(boolean value){return new PathTracingQualityOptions(lighting,cache,denoisingLayers,denoisingLayerMaxDepth,nestedDielectricsQuality,subSampling,textureLodBias,value);}

    /** Backend lighting keeps its own transport; scene analytic inputs use the adapter's stable light inputs. */
    public enum LightingInput { BACKEND_DEFAULT, SCENE_ANALYTIC }
    /** AUTO lets the backend select its sampler; the other values are explicit requests. */
    public enum LightSampler { AUTO, UNIFORM, POWER, ADAPTIVE }
    /** Fraction of the internal render grid sampled each frame, before reconstruction. */
    public enum SubSampling { FULL, THREE_QUARTERS, HALF, QUARTER }

    /** Direct-light estimator workload. Candidate samples are cheaper than visibility-tested samples. */
    public record LightSampling(LightingInput input,LightSampler sampler,int samples,int candidates,
            float localCandidateRatio,int adaptiveQuality,int exactPointLightLimit,boolean directionalStrata) {
        public LightSampling {
            Objects.requireNonNull(input);Objects.requireNonNull(sampler);
            range(samples,1,63,"light samples");range(candidates,1,63,"light candidates");
            finite(localCandidateRatio,0,1,"localCandidateRatio");range(adaptiveQuality,0,2,"adaptiveQuality");
            range(exactPointLightLimit,0,4096,"exactPointLightLimit");
            if(input==LightingInput.SCENE_ANALYTIC&&directionalStrata)
                throw new IllegalArgumentException("Scene analytic inputs do not use baked-environment directional strata");
        }
        public static LightSampling defaults(){return new LightSampling(LightingInput.BACKEND_DEFAULT,LightSampler.AUTO,1,5,.65f,0,4,false);}
        public LightSampling withInput(LightingInput value){return new LightSampling(value,sampler,samples,candidates,localCandidateRatio,adaptiveQuality,exactPointLightLimit,directionalStrata);}
        public LightSampling withSampler(LightSampler value){return new LightSampling(input,value,samples,candidates,localCandidateRatio,adaptiveQuality,exactPointLightLimit,directionalStrata);}
        public LightSampling withSamples(int full,int candidate){return new LightSampling(input,sampler,full,candidate,localCandidateRatio,adaptiveQuality,exactPointLightLimit,directionalStrata);}
        public LightSampling withLocalCandidateRatio(float value){return new LightSampling(input,sampler,samples,candidates,value,adaptiveQuality,exactPointLightLimit,directionalStrata);}
        public LightSampling withAdaptiveQuality(int value){return new LightSampling(input,sampler,samples,candidates,localCandidateRatio,value,exactPointLightLimit,directionalStrata);}
        public LightSampling withExactPointLightLimit(int value){return new LightSampling(input,sampler,samples,candidates,localCandidateRatio,adaptiveQuality,value,directionalStrata);}
        public LightSampling withDirectionalStrata(boolean value){return new LightSampling(input,sampler,samples,candidates,localCandidateRatio,adaptiveQuality,exactPointLightLimit,value);}
    }
    /** Spatial radiance cache capacity and training cost; only used when radianceCache is requested. */
    public record RadianceCache(int entries,int trainingDownscale,int maxBounces,int diffuseBounces,
            int directLightSamples,float terminationThreshold,float textureLodBias,float sceneScale,
            int accumulationFrames,int staleFrames,float minRoughness) {
        public RadianceCache {
            range(entries,65536,16777216,"cache entries");
            if(Integer.bitCount(entries)!=1)throw new IllegalArgumentException("cache entries must be a power of two");
            range(trainingDownscale,2,5,"cache trainingDownscale");range(maxBounces,1,64,"cache maxBounces");
            range(diffuseBounces,0,maxBounces,"cache diffuseBounces");range(directLightSamples,0,63,"cache directLightSamples");
            finite(terminationThreshold,.01f,10,"cache terminationThreshold");finite(textureLodBias,-8,8,"cache textureLodBias");
            finite(sceneScale,.0001f,1e6f,"cache sceneScale");range(accumulationFrames,1,255,"cache accumulationFrames");
            // SHaRC clamps values below its eight-frame minimum; reject no-op requests.
            range(staleFrames,8,128,"cache staleFrames");finite(minRoughness,0,1,"cache minRoughness");
        }
        public static RadianceCache defaults(){return new RadianceCache(2*1024*1024,5,8,4,1,3.5f,1,35,10,64,.4f);}
        public RadianceCache withCapacity(int value){return new RadianceCache(value,trainingDownscale,maxBounces,diffuseBounces,directLightSamples,terminationThreshold,textureLodBias,sceneScale,accumulationFrames,staleFrames,minRoughness);}
        public RadianceCache withTrainingDownscale(int value){return new RadianceCache(entries,value,maxBounces,diffuseBounces,directLightSamples,terminationThreshold,textureLodBias,sceneScale,accumulationFrames,staleFrames,minRoughness);}
        public RadianceCache withPathBudget(int depth,int diffuse,int lights){return new RadianceCache(entries,trainingDownscale,depth,diffuse,lights,terminationThreshold,textureLodBias,sceneScale,accumulationFrames,staleFrames,minRoughness);}
        public RadianceCache withTerminationThreshold(float value){return new RadianceCache(entries,trainingDownscale,maxBounces,diffuseBounces,directLightSamples,value,textureLodBias,sceneScale,accumulationFrames,staleFrames,minRoughness);}
    }
    private static void range(int value,int min,int max,String name){if(value<min||value>max)throw new IllegalArgumentException(name+" must be in ["+min+","+max+"]");}
    private static void finite(float value,float min,float max,String name){if(!Float.isFinite(value)||value<min||value>max)throw new IllegalArgumentException(name+" must be finite and in ["+min+","+max+"]");}
}
