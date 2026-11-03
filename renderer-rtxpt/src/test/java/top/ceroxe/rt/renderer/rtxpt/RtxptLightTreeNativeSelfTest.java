package top.ceroxe.rt.renderer.rtxpt;

import java.nio.*;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** GPU traversal counters, large-set edits, and linear HDR estimator reference. */
public final class RtxptLightTreeNativeSelfTest {
    static final Path OUTPUT=Path.of("build/reports/rtxpt-light-tree").toAbsolutePath();
    static RendererConfig config(int limit){
        var base=RtxptScalabilityBenchmark.config();var p=base.pathTracing();
        var q=p.quality().withLighting(p.quality().lighting().withSamples(63,5).withExactPointLightLimit(limit));
        return base.copyBuilder().pathTracing(new PathTracingOptions(1,1,0,RendererFeaturePreference.DISABLED,
            RendererFeaturePreference.DISABLED,RendererFeaturePreference.DISABLED,4,q)).build();
    }
    static SceneTransaction scene(int count){
        var tx=SceneTransaction.builder(1).resetScene().upsert(MaterialAsset.builder(1).roughness(.8f).build())
            .upsert(RtxptScalabilityBenchmark.cube(1)).upsert(SceneInstance.builder(1,1)
                .transform(new AffineTransform(new float[]{4,0,0,0,0,.1f,0,0,0,0,4,0})).build());
        for(int i=0;i<count;i++)tx.upsert(SceneLight.point(i,(i%8-3.5)*.5,2+(i%3)*.3,(i/8%8-3.5)*.5)
            .color(.25f+(i%3)*.25f,.8f,.3f).intensity(12f/count).range(9).castsShadow(i%2==0).build());
        return tx.upsert(SceneLight.spot(count,0,3,0,0,-1,0).intensity(.6f).range(8).coneCosines(.95f,.5f).build())
            .upsert(SceneLight.directional(count+1,.1f,-1,.1f).intensity(.15f).build()).build();
    }
    static RenderFrameRequest frame(long sequence,long revision){return RtxptScalabilityBenchmark.frame(sequence,revision,EnvironmentState.neutral(),96,54);}
    static double[] linear(Path path)throws Exception {
        byte[] bytes=Files.readAllBytes(path);int offset=0,lines=0;
        while(lines<3)if(bytes[offset++]=='\n')lines++;
        var data=ByteBuffer.wrap(bytes,offset,bytes.length-offset).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        double[] values=new double[data.remaining()];for(int i=0;i<values.length;i++){values[i]=data.get();require(Double.isFinite(values[i]),"Finite HDR output");}
        return values;
    }
    static double[] average(int limit)throws Exception {return average(limit,false,false);}
    static double[] average(int limit,boolean edited,boolean fresh)throws Exception {
        try(var renderer=Renderer.open("rtxpt",config(limit))){
            var r=(RtxptRenderer)renderer;var host=r.extension(RtxptAccess.class).orElseThrow();
            r.apply(scene(32));r.submit(frame(0,1));
            if(edited){
                r.apply(SceneTransaction.builder(2).upsert(SceneLight.point(31,4.5,3,1).color(.9f,.2f,.7f).intensity(4).range(15).castsShadow(false).build()).build());r.submit(frame(1,2));
                require(host.retainedUpdateTimings().lightIndexBuilds()==1&&host.submitStages().lightDeltaRecords()==1,"Tree light edit refits without sorting");
                if(fresh){var snap=r.sceneSnapshot();r.apply(SceneTransaction.builder(3).resetScene().upsertMaterials(snap.upserts().materials()).upsertMeshes(snap.upserts().meshes()).upsertInstances(snap.upserts().instances()).upsertLights(snap.upserts().lights()).build());r.submit(frame(2,3));}
            }
            for(int i=0;i<8;i++)host.renderFrame();
            double[] average=null;long start=System.nanoTime();
            for(int i=0;i<48;i++){
                host.renderFrame();var path=OUTPUT.resolve("linear-"+limit+".pfm");r.captureLinear(path);var data=linear(path);
                if(average==null)average=new double[data.length];for(int j=0;j<data.length;j++)average[j]+=data[j]/48;
            }
            System.out.println("RTXPT_TREE_REFERENCE limit="+limit+" elapsedMs="+(System.nanoTime()-start)/1e6+" "+host.lightSamplingStatus());
            return average;
        }
    }
    static void reference()throws Exception {
        var exact=average(64);var sampled=average(4);double a=0,b=0,error=0,norm=0;
        for(int i=0;i<exact.length;i++){a+=exact[i];b+=sampled[i];error+=Math.pow(exact[i]-sampled[i],2);norm+=exact[i]*exact[i];}
        double energy=Math.abs(b-a)/a,relativeRmse=Math.sqrt(error/norm);
        require(energy<.03&&relativeRmse<.10,"HDR energy/reference: energy="+energy+" relativeRMSE="+relativeRmse);
        System.out.println("RTXPT_TREE_REFERENCE PASS relativeEnergyError="+energy+" relativeRmse="+relativeRmse);
    }
    static void refit()throws Exception {
        var exact=average(64,true,true);var sampled=average(4,true,false);double a=0,b=0,error=0,norm=0;
        for(int i=0;i<exact.length;i++){a+=exact[i];b+=sampled[i];error+=Math.pow(exact[i]-sampled[i],2);norm+=exact[i]*exact[i];}
        double energy=Math.abs(b-a)/a,rmse=Math.sqrt(error/norm);require(energy<.03&&rmse<.10,"Refit vs fresh exhaustive HDR reference: "+energy+" "+rmse);
        System.out.println("RTXPT_TREE_REFIT PASS relativeEnergyError="+energy+" relativeRmse="+rmse);
    }
    static void large()throws Exception {
        try(var renderer=Renderer.open("rtxpt",config(4))){
            var r=(RtxptRenderer)renderer;var host=r.extension(RtxptAccess.class).orElseThrow();long revision=1,sequence=0;
            r.apply(scene(100000));r.submit(frame(sequence++,revision));for(int i=0;i<8;i++)host.renderFrame();
            var stats=host.lightSamplingStatus();require(stats.mode()==RtxptAccess.LightSamplingMode.SPATIAL_TREE,"Large set selects tree");
            require(stats.representedLights()==100003&&stats.indexNodes()==200005,"Every accepted light has a tree leaf");
            require(stats.measured()&&stats.shadedReceivers()>0&&stats.selectedLights()==stats.shadedReceivers()*63,"Actual GPU light sample count");
            require(stats.visitedTreeNodes()<=stats.selectedLights()*19,"Tree work grows logarithmically");
            System.out.println("RTXPT_TREE_LARGE "+stats);
            var edits=List.of(SceneLight.point(0,2,3,1).intensity(.4f).range(5).build(),
                SceneLight.spot(1,0,3,0,0,-1,0).intensity(.2f).range(6).coneCosines(.9f,.4f).build(),
                SceneLight.directional(2,0,-1,0).intensity(.03f).build(),
                SceneLight.point(3,0,2,0).color(1,0,0).intensity(.1f).range(2).castsShadow(false).build());
            for(var light:edits){r.apply(SceneTransaction.builder(++revision).upsert(light).build());r.submit(frame(sequence++,revision));host.awaitIdle();}
            var clear=SceneTransaction.builder(++revision);for(long i=0;i<100002;i++)clear.removeLight(i);
            r.apply(clear.build());r.submit(frame(sequence++,revision));
            require(host.lightSamplingStatus().representedLights()==1,"Remove-all retains only environment sun slot");
            require(host.bridgeTimings().sceneExports()==1,"Light edits never export geometry");
            System.out.println("RTXPT_TREE_LARGE PASS "+host.retainedUpdateTimings());
        }
    }
    public static void main(String[] args)throws Exception {
        Files.createDirectories(OUTPUT);
        if(args.length==0||args[0].equals("large"))large();
        if(args.length==0||args[0].equals("reference"))reference();
        if(args.length>0&&args[0].equals("refit"))refit();
    }
}
