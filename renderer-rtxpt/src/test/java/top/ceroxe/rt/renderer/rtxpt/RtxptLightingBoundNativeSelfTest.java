package top.ceroxe.rt.renderer.rtxpt;

import java.nio.LongBuffer;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingMatrixNativeSelfTest.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Cross-runtime image corpus for conservative source bounds, including raw RR inputs. */
public final class RtxptLightingBoundNativeSelfTest {
    private static final int WIDTH=256,HEIGHT=160;
    private static final int[] RAW_SIGNALS={0,1,2},RR_SIGNALS={0,9,2,3,4,5,6,7,8};

    private static List<SparseLightSource> sources(SparseLightSource.Composition composition,
            SparseLightSource.Attenuation attenuation,SparseLightSource.Shape shape,boolean groups){
        var sources=new ArrayList<SparseLightSource>();
        for(int i=0;i<24;i++){
            // Equal-position pairs exercise ties with IDs on both sides of 32 bits.
            int pair=i/2;
            sources.add(new SparseLightSource((i&1)==0?0x100000001L+i:i+1,shape,
                (pair%4-1.5)*.2,(pair/4-1)*.2,.5,
                shape==SparseLightSource.Shape.BOX?.15f:0,shape==SparseLightSource.Shape.BOX?.1f:0,
                shape==SparseLightSource.Shape.BOX?.6f:0,0,0,-1,
                pair%3==0?1:.25f,pair%3==1?1:.25f,pair%3==2?1:.25f,pair==0?0:1+pair%4,
                pair%2==0?2:8,2,.95f,.4f,composition,31,(i&2)==0,
                (i&1)==0?SparseLightSource.Layer.STATIC:SparseLightSource.Layer.DYNAMIC,attenuation));
        }
        // The dynamic-only result must remain independent of a stronger static maximum.
        sources.add(source(200,0,0,.5,30,false).withLayer(SparseLightSource.Layer.STATIC)
            .withComposition(composition,31).withAttenuation(attenuation));
        // Exactly zero distance and the finite-support boundary are distinct cases.
        sources.add(source(201,0,0,0,1,false).withComposition(composition,31).withAttenuation(attenuation));
        sources.add(source(202,8,0,0,1,false).withComposition(composition,31).withAttenuation(attenuation));
        if(groups)sources.add(source(300,0,0,.5,.2f,false)
            .withComposition(SparseLightSource.Composition.VISIBLE_GROUP_CHANNEL_MAX,30).withAttenuation(attenuation));
        return sources;
    }

    public static void main(String[] args)throws Exception{
        Path out=Path.of(args[0]).toAbsolutePath();Files.createDirectories(out);
        long sequence=0,revision=0;int cases=0,captures=0;
        var plan=new NativeLightingPlan(WIDTH,HEIGHT,EnumSet.allOf(NativeLightingPlan.Signal.class),
            2,6,.35f,NativeLightingStyle.defaults(),SUN_OFF,null,true);
        try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            r.nativeLightingPlan(plan);
            for(boolean rr:new boolean[]{false,true}){
                r.lightingReconstruction(rr?NativeLightingReconstruction.DLSS_RR:NativeLightingReconstruction.NONE);
                for(var composition:SparseLightSource.Composition.values())
                    for(var attenuation:SparseLightSource.Attenuation.values())
                        for(var shape:SparseLightSource.Shape.values()){
                            int selector=cases%4;boolean groups=(selector&1)!=0,analytic=(selector&2)!=0;
                            var tx=SceneTransaction.builder(++revision).resetScene()
                                .upsert(material(1,128,128,128)).upsert(material(2,255,64,16))
                                .upsert(plane(1,1,2,0,.25f,.75f,false)).upsert(plane(2,2,4,1,0,0,false))
                                .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build());
                            if(analytic)tx.upsert(SceneLight.point(9000,.2,0,.5).intensity(.2f).range(2).build());
                            r.apply(tx.build(),sources(composition,attenuation,shape,groups),LongBuffer.allocate(0));
                            publication(r,revision,new NativeLightingPublication.Instance(2,4,1,0,1,1,0,0));
                            int[] signals=rr?RR_SIGNALS:RAW_SIGNALS;
                            var reference=new ArrayList<double[]>();
                            for(int mode=0;mode<2;mode++){
                                r.lightingWorkStatisticsEnabled(mode==1);
                                if(rr)r.resetLightingReconstruction();
                                long s=sequence++;
                                r.submit(RtxptNativeLightingReconstructionNativeSelfTest.request(s,revision,0));
                                for(int signalIndex=0;signalIndex<signals.length;signalIndex++){
                                    int signal=signals[signalIndex];
                                    Path file=out.resolve("case-"+cases+"-counter-"+mode+"-signal-"+signal+".pfm");
                                    r.captureNativeLighting(file,signal);captures++;
                                    double[] values=RtxptLightTreeNativeSelfTest.linear(file);
                                    require(values.length==WIDTH*HEIGHT*3,"Complete output extent");
                                    for(double value:values)require(Double.isFinite(value),"Finite signal "+signal);
                                    if(mode==0)reference.add(values);
                                    else require(Arrays.equals(reference.get(signalIndex),values),"Counter mode equivalence");
                                }
                                if(rr)require(r.lightingReconstruction().actual()==NativeLightingReconstruction.DLSS_RR
                                    &&r.lightingReconstruction().guidesValid(),"Actual RR with valid guides");
                                try(var lease=r.acquireLightingFrame().orElseThrow()){
                                    require(lease.frame().sequence()==s&&lease.frame().sceneRevision()==revision,"Fresh frame");
                                }
                                r.awaitIdle();
                                if(mode==1)require(r.lightingWorkStatistics().primaryRays()==WIDTH*HEIGHT,"Actual counter path");
                            }
                            cases++;
                        }
            }
            require(r.nativeLightingExecution().fullBeautyFrames()==0,"Native lighting only");
            require(r.lightingReconstruction().evaluatedFrames()==36,"All RR variants evaluated");
        }
        Files.writeString(out.resolve("result.json"),RtxptJson.encode(RtxptJson.object("passed",true,"cases",cases,
            "newFrames",sequence,"captures",captures,"rrEvaluations",36,"counterModesBitExact",true)));
        System.out.println("LIGHTING_BOUND PASS cases="+cases+" frames="+sequence+" captures="+captures+" RR=36");
    }
}
