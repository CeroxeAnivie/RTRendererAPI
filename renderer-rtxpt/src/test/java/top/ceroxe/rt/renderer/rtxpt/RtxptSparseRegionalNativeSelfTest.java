package top.ceroxe.rt.renderer.rtxpt;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingMatrixNativeSelfTest.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Current executor versus independently disabled regional/bound/channel paths. */
public final class RtxptSparseRegionalNativeSelfTest {
    static final boolean RR=Boolean.getBoolean("regional.rr");
    // RR needs a supported evaluation extent; the 33x25 raw numeric oracle
    // is below the network's minimum size. Both sides use identical inputs.
    static final int WIDTH=RR?192:W,HEIGHT=RR?128:H;
    static RenderFrameRequest request(long sequence,long revision){return RR?
        RenderFrameRequest.builder(sequence,WIDTH*2,HEIGHT*2,frame(sequence,revision).camera())
            .minimumSceneRevision(revision).environment(EnvironmentState.neutral()).build():frame(sequence,revision);}
    static double[] image(RtxptRenderer renderer,String label,int signal)throws Exception{
        var file=OUT.resolve(label+".pfm");renderer.captureNativeLighting(file,signal);
        var values=RtxptLightTreeNativeSelfTest.linear(file);
        require(values.length==WIDTH*HEIGHT*3,"Exact regional oracle lighting extent");
        for(double value:values)require(Double.isFinite(value),"Finite regional output");
        return values;
    }
    static SparseLightSource source(long id,int phase){
        int i=(int)id;float x=(i%50-24.5f)*2,y=(i/2500-4.5f)*2,z=(i/50%50-24.5f)*2;
        if(phase==1&&i<16)x+=.001f;
        if(phase==2&&i<128){x=-8.0001f+(i%8)*.2f;y=(i%7-3)*.3f;z=.4f;}
        int group=phase==3?new int[]{0,1,3,31}[i%4]:phase==4?31:0;
        return new SparseLightSource(id,SparseLightSource.Shape.BOX,x,y,z,.45f,.45f,.45f,0,-1,0,
            .15f+(i%3)*.4f,.2f+(i%5)*.15f,.1f+(i%7)*.12f,.2f+(i%15)/15.f,1+i%15,
            new float[]{.5f,2,5}[i%3],1,-1,
            phase==8&&i%3==0?SparseLightSource.Composition.PHYSICAL_SUM:SparseLightSource.Composition.VISIBLE_GROUP_CHANNEL_MAX,
            group,true,(i&1)==0?SparseLightSource.Layer.STATIC:SparseLightSource.Layer.DYNAMIC,
            phase==3&&i%3==0?SparseLightSource.Attenuation.INVERSE_SQUARE_FINITE:SparseLightSource.Attenuation.MANHATTAN_LINEAR);
    }
    public static void main(String[] args)throws Exception{
        Files.createDirectories(OUT);var records=new ArrayList<Object>();long sequence=0,revision=0;
        boolean reference="1".equals(System.getenv("RTR_RTXPT_DISABLE_SPARSE_REGIONS"));
        boolean rr=RR;
        try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            r.nativeLightingPlan(new NativeLightingPlan(WIDTH,HEIGHT,EnumSet.allOf(NativeLightingPlan.Signal.class),2,6,.35f,NativeLightingStyle.defaults(),SUN_OFF,null,true));
            if(rr)r.lightingReconstruction(NativeLightingReconstruction.DLSS_RR);
            for(int phase=0;phase<9;phase++){
                var tx=SceneTransaction.builder(++revision);
                if(phase!=1&&phase!=2)tx.resetScene().upsert(material(1,128,128,128))
                    .upsert(MaterialAsset.builder(2).baseColorRgba8((rgba(80,220,160)&0x00ffffff)|0x4d000000).blendMode(MaterialAsset.BlendMode.TRANSLUCENT).transmission(1).build())
                    .upsert(plane(1,1,2,0,.15f,.7f,false)).upsert(plane(2,2,.6f,.8f,0,0,false))
                    .upsert(plane(3,1,4,1.5f,0,0,true))
                    .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build()).upsert(SceneInstance.builder(3,3).build());
                var sources=new ArrayList<SparseLightSource>();int count=phase==6?0:phase==8?256:phase==1?16:phase==2?128:25000;
                for(int i=0;i<count;i++)sources.add(source(i,phase));
                long[] removed=new long[phase==2?128:0];
                for(int i=0;i<removed.length;i++){removed[i]=128+i;sources.add(source(25000+i,phase));}
                if(phase==5)sources.add(SparseLightSource.point(900000,-20,0,.5,.2f,.5f,1,.3f,1_000_000)
                    .withComposition(SparseLightSource.Composition.VISIBLE_GROUP_CHANNEL_MAX,0));
                r.apply(tx.build(),sources,LongBuffer.wrap(removed));
                publication(r,revision,new NativeLightingPublication.Instance(2,18,1,0,1,1,0,0),
                    new NativeLightingPublication.Instance(3,4,1,0,1,1,0,0));
                r.diagnosticLevel(RtxptDiagnostics.Level.OFF);r.lightingWorkStatisticsEnabled(false);
                var off=new ArrayList<double[]>();
                for(int warm=0;warm<3;warm++){long s=sequence++;r.submit(request(s,revision));
                    if(warm==2)for(int signal=0;signal<3;signal++)off.add(image(r,"regional-off-"+phase+"-"+signal,signal));packet(r,s,revision,FrameClock.of(0,0));}
                r.diagnosticLevel(RtxptDiagnostics.Level.DEEP);r.lightingWorkStatisticsEnabled(true);long s=sequence++;r.submit(request(s,revision));
                for(int signal=0;signal<3;signal++){var actual=image(r,"regional-deep-"+phase+"-"+signal,signal);
                    if(!rr)require(Arrays.equals(off.get(signal),actual),"OFF/DEEP same RGB result phase="+phase+" plane="+signal);}
                packet(r,s,revision,FrameClock.of(0,0));var q=r.lightingSpatialQueryStatistics();var index=r.sparseSpatialIndexStatistics();
                require(q.available()&&q.sequence()==s,"Spatial counts match captured frame");
                require(q.indexedQueries()+q.fullTreeFallbacks()==q.queries(),"Indexed/fallback queries partition all source lookups");
                if(!reference&&phase<6)require(q.indexedQueries()>0&&index.cells()>0,"Real 25k CHANNEL_MAX uses regional index");
                if(phase==6)require(q.queries()==0&&index.cells()==0,"Empty reset clears all regional state");
                if(phase==8)require(q.indexedQueries()==0,"Mixed additive preserves global traversal");
                if(phase==0){require(q.queries()>r.lightingWorkStatistics().receivers(),"Regional queries include actual GI hits");
                    require(Arrays.stream(off.get(1)).anyMatch(v->v>0),"Oracle exercises nonzero GI radiance");}
                var work=r.lightingWorkStatistics();require(work.sequence()==q.sequence(),"Routing and ray events use same frame");
                records.add(RtxptJson.object("phase",phase,"sources",r.sparseLightingStatus().representedSources(),"queries",q.queries(),"indexed",q.indexedQueries(),
                    "empty",q.emptyCells(),"fallback",q.fullTreeFallbacks(),"treeletsVisited",q.treelets(),"globalVisited",q.globalTreelets(),
                    "primary",work.primaryRays(),"receivers",work.receivers(),"shadow",work.shadowRays(),"bounce",work.bounceRays(),"nodes",work.sourceNodes(),"candidates",work.sourceCandidates(),
                    "indexRoots",index.treelets(),"cells",index.cells(),"links",index.links(),"bytes",index.packedBytes(),"uploaded",index.uploadedBytes()));
            }
        }
        Files.writeString(OUT.resolve("regional-statistics.json"),RtxptJson.encode(records));
        System.out.println("SPARSE_REGIONAL PASS phases=9 sources=25000 channelMax=true groups=1,2,4,32 mixedAttenuation=true coloredTransmission=true reference="+reference+" rr="+rr);
    }
}
