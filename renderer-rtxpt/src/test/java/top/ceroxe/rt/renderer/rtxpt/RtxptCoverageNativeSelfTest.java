package top.ceroxe.rt.renderer.rtxpt;

import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Distinguishes RGB animation from opacity coverage, including SBT/pipeline execution counts. */
public final class RtxptCoverageNativeSelfTest {
    static TextureAsset texture(int red,int alpha){return TextureAsset.color(7,1,1,new byte[]{(byte)red,80,40,(byte)alpha});}
    public static void main(String[] args){
        try(var r=Renderer.open("rtxpt",RtxptScalabilityBenchmark.config())){
            var h=r.extension(RtxptAccess.class).orElseThrow();var e=RtxptLightingNativeSelfTest.environment();
            r.apply(SceneTransaction.builder(1).resetScene().upsert(texture(255,255)).upsert(MaterialAsset.builder(1).blendMode(MaterialAsset.BlendMode.MASKED).baseColorTextureId(7).roughness(.8f).build()).upsert(RtxptScalabilityBenchmark.cube(1)).upsert(SceneInstance.builder(1,1).build()).build());
            r.submit(RtxptScalabilityBenchmark.frame(0,1,e,640,360));RtxptLightingNativeSelfTest.settle(h,12);
            var before=h.retainedUpdateTimings();var pipeline=h.submitStages();
            r.apply(SceneTransaction.builder(2).upsert(texture(60,255)).build());r.submit(RtxptScalabilityBenchmark.frame(1,2,e,640,360));
            var rgb=h.retainedUpdateTimings();var after=h.submitStages();
            require(rgb.builtBlases()==before.builtBlases()&&rgb.tlasGenerations()==before.tlasGenerations(),"Masked RGB-only update retains AS");
            require(after.pipelineBuilds()==pipeline.pipelineBuilds()&&after.shaderTableBuilds()==pipeline.shaderTableBuilds(),"RGB-only update retains pipeline and SBT");
            r.apply(SceneTransaction.builder(3).upsert(texture(60,0)).build());r.submit(RtxptScalabilityBenchmark.frame(2,3,e,640,360));
            require(h.retainedUpdateTimings().builtBlases()==rgb.builtBlases()+1,"Alpha coverage rebuilds affected BLAS");
            // A shader key change that happens to retain the same permutation name/index must
            // still rebuild the native pipeline; topology-only reuse must not mask it.
            r.apply(SceneTransaction.builder(4).upsert(MaterialAsset.builder(1).baseColorTextureId(7).roughness(.8f).transmission(1).blendMode(MaterialAsset.BlendMode.TRANSLUCENT).build()).build());
            r.submit(RtxptScalabilityBenchmark.frame(3,4,e,640,360));
            require(h.submitStages().pipelineBuilds()>after.pipelineBuilds(),"Changed shader/any-hit exports rebuild pipelines");
            System.out.println("COVERAGE_PASS "+h.retainedUpdateTimings()+" "+h.submitStages());
        }
    }
}
