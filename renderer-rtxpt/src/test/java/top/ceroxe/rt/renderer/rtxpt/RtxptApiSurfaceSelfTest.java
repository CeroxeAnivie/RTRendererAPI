package top.ceroxe.rt.renderer.rtxpt;
import top.ceroxe.rt.renderer.api.*;
import java.util.*;
public final class RtxptApiSurfaceSelfTest {
    public static void main(String[] args) {
        for(var type:List.of(Renderer.class,RendererConfig.Builder.class,RenderFrameRequest.class,RenderFrameRequest.Builder.class,
                MaterialAsset.class,MaterialAsset.Builder.class,SceneInstance.class,SceneInstance.Builder.class,
                PrimitiveInstance.class,PrimitiveInstance.Builder.class,RayTracingOptimizationOptions.class,
                RayTracingOptimizationOptions.Builder.class,FrameReconstructionOptions.class,FrameReconstructionOptions.Builder.class,
                FrameGenerationOptions.class,FrameGenerationOptions.Builder.class,DenoisingOptions.class,DenoisingOptions.Builder.class))
            for(var method:type.getMethods())
                if(Set.of("submitCommands","submitResources","pollFrame","awaitFrame","devices",
                        "sceneShaders","gpuDevice","cpuFrameReadbackEnabled","shaderData","caustics",
                        "lightmap","textureSampling","antiAliasing","depthProjection","renderState","surfaceOverlay","shadingModel","memoryOptimization","fallback","builtInTemporalFallback").contains(method.getName()))
                    throw new AssertionError("Unsupported public operation remains: "+method);
        for(var name:List.of("AntiAliasingState","LightmapState","TextureSamplingState","DepthProjectionState","InstanceRenderState","SurfaceOverlayState","UvTransform","OutlineStyle","CardinalLightingState","DirectionalDiffuseState","MaterialAsset$ShadingModel","FrameReconstructionOptions$Fallback","FrameGenerationOptions$Fallback","Caustics","RenderCommandTransaction","RenderResourceTransaction","SceneShaderProgram")) {
            try {Class.forName("top.ceroxe.rt.renderer.api."+name);throw new AssertionError("Unsupported type remains: "+name);}
            catch(ClassNotFoundException expected) { }
        }
        if(Arrays.stream(FrameReconstructionOptions.Mode.values()).anyMatch(v->v.name().equals("SPATIAL_UPSCALING"))
                ||Arrays.stream(FrameGenerationOptions.Mode.values()).anyMatch(v->v.name().equals("ADAPTIVE")))
            throw new AssertionError("Unimplemented configuration mode remains");
        var state=new RtxptAccess.RenderingState(true,RtxptAccess.Reconstruction.NONE,0,1,false,
                RtxptAccess.Denoiser.REBLUR,0,0,false,false,false,false,false,false,0,false,false);
        var presentation=new RtxptAccess.PresentationState(12,2,0,0,0,0,0,1,false,false);
        RtxptOptions.validateRequired(RendererFeatureProfile.disabled(),state,presentation);
        var ser=RendererFeatureProfile.builder().rayTracingOptimizations(RayTracingOptimizationOptions.builder()
                .shaderExecutionReordering(RendererFeaturePreference.REQUIRED).build()).build();
        if(!RtxptOptions.hasRequiredFeatures(ser))throw new AssertionError("Required native feature was lost");
        try { RtxptOptions.validateRequired(ser,state,presentation);throw new AssertionError("Inactive required SER accepted"); }
        catch(IllegalStateException expected) { }
        System.out.println("API_SURFACE PASS unsupportedFunctionsRemoved=true noCompatibilityStubs=true");
    }
}
