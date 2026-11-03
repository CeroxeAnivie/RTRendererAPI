package top.ceroxe.rt.renderer.rtxpt;

import java.nio.LongBuffer;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Real native HDR equivalence and retained source edits, independent of analytic light counts. */
public final class RtxptSparseLightingNativeSelfTest {
    static final Path OUT=Path.of("build/reports/rtxpt-sparse").toAbsolutePath();
    static SceneTransaction scene(long revision){return SceneTransaction.builder(revision).resetScene()
        .upsert(MaterialAsset.builder(1).baseColorRgba8(0x808080ff).roughness(.8f).build())
        .upsert(RtxptFramePreparationNativeSelfTest.card(1)).upsert(SceneInstance.builder(1,1).build()).build();}
    static RenderFrameRequest frame(long sequence,long revision){return RtxptFramePreparationNativeSelfTest.frame(sequence,revision,96,64);}
    static SparseLightSource source(long id,float x,float r,float g,float b){return SparseLightSource.point(id,x,1,2,r,g,b,4,8);}
    static double[] image(boolean sparse)throws Exception {
        try(var renderer=Renderer.open("rtxpt",RtxptLightTreeNativeSelfTest.config(16))){
            var r=(RtxptRenderer)renderer;
            if(sparse)r.apply(scene(1),List.of(source(1,-1,1,0,0),source(2,1,0,1,0)),LongBuffer.allocate(0));
            else {var tx=SceneTransaction.builder(1).resetScene().upsertMaterials(scene(1).upserts().materials()).upsertMeshes(scene(1).upserts().meshes()).upsertInstances(scene(1).upserts().instances());
                tx.upsert(SceneLight.point(1,-1,1,2).color(1,0,0).intensity(4).range(8).build())
                    .upsert(SceneLight.point(2,1,1,2).color(0,1,0).intensity(4).range(8).build());r.apply(tx.build());}
            r.submit(frame(0,1));for(int i=0;i<6;i++)r.renderFrame();
            var file=OUT.resolve(sparse?"sparse.pfm":"analytic.pfm");r.captureLinear(file);
            return RtxptLightTreeNativeSelfTest.linear(file);
        }
    }
    static void equivalence()throws Exception {
        var a=image(false);var b=image(true);double squared=0,norm=0;
        for(int i=0;i<a.length;i++){squared+=(a[i]-b[i])*(a[i]-b[i]);norm+=a[i]*a[i];}
        double relative=Math.sqrt(squared/Math.max(norm,1e-20));
        require(relative<.003,"Exact sparse point sum must match analytic reference: "+relative);
        System.out.println("RTXPT_SPARSE_HDR PASS relativeRmse="+relative+" tolerance=0.003");
    }
    static void retained()throws Exception {
        try(var renderer=Renderer.open("rtxpt",RtxptLightTreeNativeSelfTest.config(4))){
            var r=(RtxptRenderer)renderer;var sources=new ArrayList<SparseLightSource>();
            for(int i=0;i<160000;i++)sources.add(SparseLightSource.point(i,10000+i%400,10000+i/400,0,.2f,.3f,.4f,1,2));
            r.apply(scene(1),sources,LongBuffer.allocate(0));r.submit(frame(0,1));for(int i=0;i<4;i++)r.renderFrame();r.awaitIdle();
            var before=r.sparseLightingStatus();require(before.representedSources()==160000,"Sparse represented count");
            long pipelines=r.submitStages().pipelineBuilds();
            for(int round=0;round<12;round++){
                var changed=new ArrayList<SparseLightSource>();long[] removed=new long[6];
                for(int i=0;i<6;i++){removed[i]=159999-round*6-i;changed.add(source(200000L+round*6+i,1,1,0,0));changed.add(source(i,round%2,0,1,0));}
                long begin=System.nanoTime();r.apply(SceneTransaction.builder(round+2).build(),changed,LongBuffer.wrap(removed));r.submit(frame(round+1,round+2));
                require(r.sparseLightingStatus().representedSources()==160000,"Sparse dense count after edits");
                System.out.printf(Locale.ROOT,"RTXPT_SPARSE_EDIT round=%d submitMs=%.6f%n",round,(System.nanoTime()-begin)/1e6);
            }
            var after=r.sparseLightingStatus();
            require(after.uploadedRecordBytes()-before.uploadedRecordBytes()<300*96,"Unchanged sparse source records retained");
            require(after.uploadedIndexBytes()-before.uploadedIndexBytes()<30000*64,"Spatial index updates remain local");
            require(r.submitStages().pipelineBuilds()==pipelines,"Sparse changes do not create PSOs");
            var snapshot=r.diagnostics();
            try {r.apply(SceneTransaction.builder(20).build(),List.of(source(1,0,1,0,0),source(1,0,0,1,0)),LongBuffer.allocate(0));throw new AssertionError("Duplicate source accepted");}
            catch(IllegalArgumentException expected){require(r.diagnostics().latestAcceptedSceneRevision()==snapshot.latestAcceptedSceneRevision(),"Invalid sparse batch does not publish a revision");}
            r.apply(scene(20));r.submit(frame(20,20));require(r.sparseLightingStatus().representedSources()==0,"Ordinary scene reset clears sparse sources");
            System.out.println("RTXPT_SPARSE_RETAINED PASS before="+before+" after="+after);
        }
    }
    public static void main(String[] args)throws Exception {Files.createDirectories(OUT);equivalence();retained();System.out.println("RTXPT_SPARSE_ALL PASS");}
}
