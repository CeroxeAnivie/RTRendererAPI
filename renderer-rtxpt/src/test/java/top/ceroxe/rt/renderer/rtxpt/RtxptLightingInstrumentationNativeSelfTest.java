package top.ceroxe.rt.renderer.rtxpt;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingMatrixNativeSelfTest.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Both compile-time counter modes produce identical lighting, including all selector variants. */
public final class RtxptLightingInstrumentationNativeSelfTest {
    static final List<Object> counterRecords=new ArrayList<>();
    static void record(String label,NativeLightingControl.WorkStatistics counts){
        counterRecords.add(RtxptJson.object("case",label,"primary",counts.primaryRays(),"receivers",counts.receivers(),
            "shadow",counts.shadowRays(),"bounce",counts.bounceRays(),"nodes",counts.sourceNodes(),"candidates",counts.sourceCandidates()));
    }
    static void verifySourceTraversal()throws Exception{
        long sequence=0,revision=0;var current=new LinkedHashMap<Long,SparseLightSource>();
        try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            for(int phase=0;phase<8;phase++){
                int samples=new int[]{1,2,5}[phase%3];
                r.nativeLightingPlan(plan(EnumSet.allOf(NativeLightingPlan.Signal.class),samples,SUN_OFF,true));
                var updates=new ArrayList<SparseLightSource>();var removed=new ArrayList<Long>();
                if(phase<4)updates.addAll(RtxptSparseQueryOrderingNativeSelfTest.sources(phase));
                else if(phase==4)removed.addAll(List.of(0L,1L));
                else if(phase==5){
                    removed.addAll(current.keySet());
                    // Equal luminance with different RGB exercises stable-ID ties.
                    updates.add(SparseLightSource.point(900,0,0,.5,.7152f,0,0,8,8).withComposition(SparseLightSource.Composition.VISIBLE_GROUP_MAX,0));
                    updates.add(SparseLightSource.point(901,0,0,.5,0,.2126f,0,8,8).withComposition(SparseLightSource.Composition.VISIBLE_GROUP_MAX,0));
                }else if(phase==6)removed.add(900L);
                else updates.add(SparseLightSource.point(900,1.2,0,.5,.7152f,0,0,8,8).withComposition(SparseLightSource.Composition.VISIBLE_GROUP_MAX,0));
                for(long id:removed)current.remove(id);for(var source:updates)current.put(source.id(),source);
                var tx=SceneTransaction.builder(++revision);
                if(phase==0)tx.resetScene().upsert(material(1,128,128,128)).upsert(material(2,255,255,255))
                    .upsert(plane(1,1,2,0,0,0,false)).upsert(plane(2,2,4,1,0,0,false))
                    .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build());
                r.apply(tx.build(),updates,LongBuffer.wrap(removed.stream().mapToLong(Long::longValue).toArray()));
                if(phase==0)publication(r,1,new NativeLightingPublication.Instance(2,4,1,0,1,1,0,0));
                r.diagnosticLevel(RtxptDiagnostics.Level.OFF);r.lightingWorkStatisticsEnabled(false);
                var reference=new ArrayList<double[]>();
                for(int warm=0;warm<3;warm++){
                    long s=sequence++;r.submit(frame(s,revision));
                    if(warm==2)for(int signal=0;signal<3;signal++)reference.add(capture(r,"seeded-"+phase+"-"+signal,signal));
                    packet(r,s,revision,FrameClock.of(0,0));
                }
                require(Arrays.stream(reference.get(1)).anyMatch(v->v>0),"Traversal comparison exercises actual bounce lighting phase="+phase);
                r.diagnosticLevel(RtxptDiagnostics.Level.DEEP);r.lightingWorkStatisticsEnabled(true);
                long s=sequence++;r.submit(frame(s,revision));
                for(int signal=0;signal<3;signal++)require(Arrays.equals(reference.get(signal),capture(r,"unseeded-"+phase+"-"+signal,signal)),
                    "Current-source GI matches independent unseeded traversal phase="+phase+" samples="+samples+" signal="+signal);
                packet(r,s,revision,FrameClock.of(0,0));var counts=r.lightingWorkStatistics();
                record("source-phase-"+phase,counts);
                require(counts.primaryRays()==W*H&&counts.bounceRays()==counts.receivers()*samples,"Counters preserve divergent GI sample counts");
            }
            for(int mode=0;mode<2;mode++){
                var sources=new ArrayList<SparseLightSource>();int lights=mode==0?16:1;
                for(int i=0;i<lights;i++){
                    var source=source(i+1,0,0,.5,.01f,false);
                    sources.add(mode==0?source:source.withComposition(SparseLightSource.Composition.VISIBLE_GROUP_MAX,0));
                }
                r.nativeLightingPlan(plan(Set.of(NativeLightingPlan.Signal.DIRECT_GAIN),0,SUN_OFF,true));
                r.apply(SceneTransaction.builder(++revision).resetScene().upsert(material(1,128,128,128))
                    .upsert(plane(1,1,2,0,0,0,false)).upsert(SceneInstance.builder(1,1).build()).build(),sources,LongBuffer.allocate(0));
                r.diagnosticLevel(RtxptDiagnostics.Level.DEEP);r.lightingWorkStatisticsEnabled(true);
                long s=sequence++;r.submit(frame(s,revision));capture(r,"exact-traversal-counts-"+mode,0);packet(r,s,revision,FrameClock.of(0,0));
                var counts=r.lightingWorkStatistics();
                record("exhaustive-"+mode,counts);
                require(counts.receivers()>0&&counts.sourceNodes()==counts.receivers()*(2*lights-1)&&counts.sourceCandidates()==counts.receivers()*lights,
                    "Exact node and candidate counters for an exhaustive supported tree mode="+mode+" counts="+counts);
                require(counts.shadowRays()==0&&counts.bounceRays()==0,"No fabricated shadow or bounce counts");
            }
        }
        System.out.println("SOURCE_TRAVERSAL PASS cases=8 signals=3 bitExact=true samples=1,2,5 groups=1,2,4,32 sourceChanges=true stableTies=true exactCounterCases=2");
    }
    public static void main(String[] args)throws Exception{
        Files.createDirectories(OUT);long sequence=0,revision=1;
        try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            r.nativeLightingPlan(plan(EnumSet.allOf(NativeLightingPlan.Signal.class),2,SUN_ON,true));
            r.apply(SceneTransaction.builder(revision).resetScene().upsert(material(1,128,128,128)).upsert(material(2,255,64,16))
                .upsert(plane(1,1,2,0,.25f,.75f,false)).upsert(plane(2,2,4,1,0,0,false))
                .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build()).build(),
                List.of(source(1,0,0,.5,8,true).withComposition(SparseLightSource.Composition.VISIBLE_GROUP_MAX,31)),LongBuffer.allocate(0));
            publication(r,1,new NativeLightingPublication.Instance(2,4,1,0,1,1,0,0));
            for(int variant=0;variant<5;variant++){
                var tx=SceneTransaction.builder(++revision);
                if((variant&2)!=0)tx.upsert(SceneLight.point(9000,.2,0,.5).intensity(.2f).range(2).build());
                var more=(variant&1)==0?List.<SparseLightSource>of():List.of(source(100,0,0,.5,0,false)
                    .withComposition(SparseLightSource.Composition.VISIBLE_GROUP_MAX,30));
                if(variant==4)more=List.of(source(1,0,0,.5,8,true).withComposition(SparseLightSource.Composition.VISIBLE_GROUP_MAX,0),
                    source(100,0,0,.5,0,false).withComposition(SparseLightSource.Composition.VISIBLE_GROUP_MAX,1));
                r.apply(tx.build(),more,LongBuffer.allocate(0));r.lightingWorkStatisticsEnabled(false);
                long s=sequence++;r.submit(frame(s,revision));var reference=new ArrayList<double[]>();
                for(int signal=0;signal<3;signal++)reference.add(capture(r,"counter-off-"+variant+"-"+signal,signal));packet(r,s,revision,FrameClock.of(0,0));
                r.diagnosticLevel(RtxptDiagnostics.Level.DEEP);r.lightingWorkStatisticsEnabled(true);s=sequence++;r.submit(frame(s,revision));
                for(int signal=0;signal<3;signal++)require(Arrays.equals(reference.get(signal),capture(r,"counter-on-"+variant+"-"+signal,signal)),"Counter shader equivalence variant="+variant+" signal="+signal);
                packet(r,s,revision,FrameClock.of(0,0));var counts=r.lightingWorkStatistics();
                record("selector-"+variant,counts);
                require(counts.primaryRays()==W*H,"Wave counters preserve every primary lane");
                require(counts.receivers()>0&&counts.receivers()<=W*H,"Receiver counters include exactly the hit lanes");
                require(counts.bounceRays()==counts.receivers()*2,"Wave counters preserve every divergent bounce lane");
                require(counts.shadowRays()>0&&counts.sourceNodes()>=counts.sourceCandidates(),"Traversal and shadow counters remain populated");
                if((variant&1)!=0)r.apply(SceneTransaction.builder(++revision).build(),List.of(),LongBuffer.wrap(new long[]{100}));
            }
            r.diagnosticLevel(RtxptDiagnostics.Level.OFF);long s=sequence++;r.submit(frame(s,revision));packet(r,s,revision,FrameClock.of(0,0));
            require(!r.lightingStatisticsCollection().effective()&&r.lightingStatisticsCollection().pending()==0,"Unified OFF selects the uninstrumented path");
            System.out.println("LIGHTING_INSTRUMENTATION PASS variants=10 signals=3 bitExact=true actualCounters=true pairGroups=true unifiedOff=true");
        }
        verifySourceTraversal();
        Files.writeString(OUT.resolve("lighting-instrumentation-counts.json"),RtxptJson.encode(counterRecords));
    }
}
