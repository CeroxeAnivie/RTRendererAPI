package top.ceroxe.rt.renderer.rtxpt;

import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Measures complete submissions, including real structural edits before native FRAME. */
public final class RtxptStructuralLightingNativeSelfTest {
    static SceneLight light(long id,int kind,int generation) {
        var b=kind==0?SceneLight.point(id,(id%100-50)*.12,2,(id/100%100-50)*.12)
            :SceneLight.spot(id,(id%100-50)*.12,2,(id/100%100-50)*.12,0,-1,0).coneCosines(.95f,.5f);
        return b.color(.2f+(generation%3)*.1f,.3f,.4f).intensity(.00005f).range(12).castsShadow(false).build();
    }
    static void run(int count) {
        try(var renderer=Renderer.open("rtxpt",RtxptLightTreeNativeSelfTest.config(4))) {
            var r=(RtxptRenderer)renderer;var h=r.extension(RtxptAccess.class).orElseThrow();
            r.diagnosticLevel(RtxptDiagnostics.Level.DEEP);
            long rev=1,seq=0;
            var tx=SceneTransaction.builder(rev).resetScene().upsert(MaterialAsset.builder(1).roughness(.8f).build())
                .upsert(RtxptScalabilityBenchmark.cube(1)).upsert(SceneInstance.builder(1,1).build());
            for(int i=0;i<count;i++)tx.upsert(light(i,0,0));
            r.apply(tx.build());r.submit(RtxptLightTreeNativeSelfTest.frame(seq++,rev));
            for(int i=0;i<4;i++)h.renderFrame();h.awaitIdle();
            var before=r.lightUpdates();long pipelines=h.submitStages().pipelineBuilds();
            var active=new ArrayList<Long>();for(long i=0;i<count;i++)active.add(i);
            long next=count;var samples=new ArrayList<Double>();
            for(int round=0;round<12;round++) {
                var edit=SceneTransaction.builder(++rev);
                // Same-ID type changes, birth, deletion and ordinary value updates in one batch.
                int deaths=Math.min(6,active.size());
                for(int i=0;i<deaths;i++)edit.removeLight(active.removeLast());
                int changes=Math.min(6,active.size());
                for(int i=0;i<changes;i++)edit.upsert(light(active.get(i),round%2,round));
                for(int i=0;i<18-deaths-changes;i++){active.add(next);edit.upsert(light(next++,round%2,round));}
                long begin=System.nanoTime();r.apply(edit.build());
                r.submit(RtxptLightTreeNativeSelfTest.frame(seq++,rev));samples.add((System.nanoTime()-begin)/1e6);
                require(r.lightUpdates().residentLights()==active.size()+1,"Dense light count after mixed edit");
            }
            h.awaitIdle();var after=r.lightUpdates();
            require(after.fullCommits()==before.fullCommits(),"Small structural edits do not commit the whole light group");
            require(after.treeRebuilds()==before.treeRebuilds(),"Small structural edits refit the retained index");
            require(after.createdObjects()-before.createdObjects()<=216,"Only changed light objects created");
            require(h.submitStages().pipelineBuilds()==pipelines,"Light type/count changes do not create a PSO");
            require(r.diagnosticSnapshot().events().stream().anyMatch(e->e.stage()==RtxptDiagnostics.Stage.SUBMIT),"Complete submit events recorded");
            if(count>=1000) {
                require(after.uploadedLightRecords()-before.uploadedLightRecords()<=300,"Unchanged lights are not reuploaded");
                require(after.uploadedTreeNodes()-before.uploadedTreeNodes()<=30000,"Only local tree spans uploaded");
            }
            RtxptScalabilityBenchmark.result("structural-"+count,samples,h.bridgeTimings().sceneExports());
            System.out.println("RTXPT_706_STRUCTURAL PASS resident="+count+" before="+before+" after="+after);
        }
    }
    public static void main(String[] args) {
        for(String size:args.length==0?new String[]{"0","1","64","1000","10000","100000","160000"}:args)run(Integer.parseInt(size));
        System.out.println("RTXPT_706_STRUCTURAL_ALL PASS");
    }
}
