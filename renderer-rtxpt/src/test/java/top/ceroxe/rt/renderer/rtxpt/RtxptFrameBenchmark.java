package top.ceroxe.rt.renderer.rtxpt;

import java.util.*;
import top.ceroxe.rt.renderer.api.*;

/** Uses shared public methods so exactly this workload can run against both artifacts. */
public final class RtxptFrameBenchmark {
    public static void main(String[] args){
        int instances=args.length==0?16:Integer.parseInt(args[0]);
        var base=RtxptLightingNativeSelfTest.configuration("rr");
        var path=base.pathTracing();var quality=path.quality().withLighting(path.quality().lighting().withInput(PathTracingQualityOptions.LightingInput.SCENE_ANALYTIC));
        var config=base.copyBuilder().pathTracing(new PathTracingOptions(1,8,2,RendererFeaturePreference.DISABLED,RendererFeaturePreference.DISABLED,RendererFeaturePreference.DISABLED,4,quality))
            .frameReconstruction(base.frameReconstruction().toBuilder().quality(FrameReconstructionOptions.Quality.QUALITY).build()).build();
        try(var r=Renderer.open("rtxpt",config)){
            var h=r.extension(RtxptAccess.class).orElseThrow();
            var tx=SceneTransaction.builder(1).resetScene().upsert(MaterialAsset.builder(1).baseColorRgba8(0x90a0b0ff).roughness(.6f).build()).upsert(RtxptScalabilityBenchmark.cube(1));
            for(int i=0;i<instances;i++)tx.upsert(SceneInstance.builder(i+1,1).transform(new AffineTransform(new float[]{.12f,0,0,(i%16-8)*.3f,0,.12f,0,(i/16)*.3f,0,0,.12f,0})).build());
            r.apply(tx.build());r.submit(RenderFrameRequest.builder(0,960,540,RtxptAcceptanceFixture.camera()).minimumSceneRevision(1).environment(RtxptLightingNativeSelfTest.environment()).build());
            for(int i=0;i<80;i++)h.renderFrame();h.awaitIdle();
            var times=new ArrayList<Double>();
            for(int i=0;i<240;i++){long start=System.nanoTime();if(!h.renderFrame())throw new AssertionError("Renderer closed");h.awaitIdle();times.add((System.nanoTime()-start)/1e6);}
            RtxptScalabilityBenchmark.result("rr-quality-1spp-"+instances,times,h.bridgeTimings().sceneExports());
            System.out.println("RTXPT_FRAME_BENCH_STATUS "+h.runtimeStatus());
        }
    }
}
