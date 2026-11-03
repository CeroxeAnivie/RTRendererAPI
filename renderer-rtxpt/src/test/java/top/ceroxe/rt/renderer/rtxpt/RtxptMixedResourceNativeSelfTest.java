package top.ceroxe.rt.renderer.rtxpt;

import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingMatrixNativeSelfTest.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Replacement geometry and shared alpha coverage must select current mesh generations. */
public final class RtxptMixedResourceNativeSelfTest {
    private static TextureAsset alpha(int value){return TextureAsset.color(7,1,1,new byte[]{-1,-1,-1,(byte)value});}
    public static void main(String[] args)throws Exception {
        Files.createDirectories(OUT);var rows=new ArrayList<Object>();
        try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            r.nativeLightingPlan(directPlan(SUN_ON));
            r.apply(SceneTransaction.builder(1).resetScene().upsert(material(1,128,128,128)).upsert(alpha(255))
                .upsert(MaterialAsset.builder(2).baseColorTextureId(7).blendMode(MaterialAsset.BlendMode.MASKED).alphaCutoff(.5f).build())
                .upsert(plane(1,1,2,0,0,1,false)).upsert(plane(2,2,4,1,0,0,false)).upsert(plane(3,2,.1f,-10,0,0,false))
                .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build())
                .upsert(SceneInstance.builder(3,3).transform(new AffineTransform(new float[]{1,0,0,100,0,1,0,0,0,0,1,0})).build()).build());
            publication(r,1,NativeLightingPublication.Instance.shadowOnly(2));
            r.submit(frame(0,1));near(capture(r,"mixed-initial",0)[CENTER],.45,.002,"Initial shadow");packet(r,0,1,FrameClock.of(0,0));
            for(int step=0;step<2;step++){
                long revision=step+2,sequence=step+1;long before=r.retainedUpdateTimings().builtBlases();
                r.apply(SceneTransaction.builder(revision).upsert(alpha(step==0?0:255))
                    .upsert(plane(2,2,step==0?3.5f:4,1,0,0,false)).build());
                r.submit(frame(sequence,revision));double gain=capture(r,"mixed-geometry-alpha-"+step,0)[CENTER];
                near(gain,step==0?1:.45,.002,"Mixed geometry and shared coverage");packet(r,sequence,revision,FrameClock.of(0,0));
                require(r.retainedUpdateTimings().builtBlases()-before==2,"Exactly two real coverage dependents, including the replacement generation");
                rows.add(RtxptJson.object("case",step==0?"open":"restore","gain",gain,"builtBlases",2,"native",r.nativePreparationStatistics().toString()));
            }
            r.apply(SceneTransaction.builder(4).upsert(MaterialAsset.builder(2).baseColorTextureId(7)
                .blendMode(MaterialAsset.BlendMode.MASKED).alphaCutoff(.5f).roughness(.7f).build()).build());
            var before=r.nativePreparationStatistics();r.submit(frame(3,4));near(capture(r,"mixed-next-local-material",0)[CENTER],.45,.002,"Next local commit uses rebuilt indexes");
            packet(r,3,4,FrameClock.of(0,0));var after=r.nativePreparationStatistics();
            require(after.materialVisits()-before.materialVisits()==1&&after.meshVisits()==before.meshVisits()&&after.indexBuilds()==before.indexBuilds(),"Next material edit stays local");
            require(r.nativeLightingExecution().fullBeautyFrames()==0,"No beauty work");
        }
        Files.writeString(OUT.resolve("mixed-resource-matrix.json"),RtxptJson.encode(RtxptJson.object("passed",true,"rows",rows)));
        System.out.println("MIXED_RESOURCE_NATIVE PASS sharedDependents=2 replacementGeneration=true alphaRestored=true nextCommitLocal=true");
    }
}
