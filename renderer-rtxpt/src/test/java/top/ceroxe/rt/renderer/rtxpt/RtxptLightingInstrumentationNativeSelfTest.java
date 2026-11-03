package top.ceroxe.rt.renderer.rtxpt;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingMatrixNativeSelfTest.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Both compile-time counter modes produce identical lighting, including all selector variants. */
public final class RtxptLightingInstrumentationNativeSelfTest {
    public static void main(String[] args)throws Exception{
        Files.createDirectories(OUT);long sequence=0,revision=1;
        try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            r.nativeLightingPlan(plan(EnumSet.allOf(NativeLightingPlan.Signal.class),2,SUN_ON,true));
            r.apply(SceneTransaction.builder(revision).resetScene().upsert(material(1,128,128,128)).upsert(material(2,255,64,16))
                .upsert(plane(1,1,2,0,.25f,.75f,false)).upsert(plane(2,2,4,1,0,0,false))
                .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build()).build(),
                List.of(source(1,0,0,.5,8,true).withComposition(SparseLightSource.Composition.VISIBLE_GROUP_MAX,31)),LongBuffer.allocate(0));
            publication(r,1,new NativeLightingPublication.Instance(2,4,1,0,1,1,0,0));
            for(int variant=0;variant<4;variant++){
                var tx=SceneTransaction.builder(++revision);
                if((variant&2)!=0)tx.upsert(SceneLight.point(9000,.2,0,.5).intensity(.2f).range(2).build());
                var more=(variant&1)==0?List.<SparseLightSource>of():List.of(source(100,0,0,.5,0,false)
                    .withComposition(SparseLightSource.Composition.VISIBLE_GROUP_MAX,30));
                r.apply(tx.build(),more,LongBuffer.allocate(0));r.lightingWorkStatisticsEnabled(false);
                long s=sequence++;r.submit(frame(s,revision));var reference=new ArrayList<double[]>();
                for(int signal=0;signal<3;signal++)reference.add(capture(r,"counter-off-"+variant+"-"+signal,signal));packet(r,s,revision,FrameClock.of(0,0));
                r.lightingWorkStatisticsEnabled(true);s=sequence++;r.submit(frame(s,revision));
                for(int signal=0;signal<3;signal++)require(Arrays.equals(reference.get(signal),capture(r,"counter-on-"+variant+"-"+signal,signal)),"Counter shader equivalence variant="+variant+" signal="+signal);
                packet(r,s,revision,FrameClock.of(0,0));require(r.lightingWorkStatistics().primaryRays()==W*H,"Instrumented shader executes real counters");
                if((variant&1)!=0)r.apply(SceneTransaction.builder(++revision).build(),List.of(),LongBuffer.wrap(new long[]{100}));
            }
            r.diagnosticLevel(RtxptDiagnostics.Level.OFF);long s=sequence++;r.submit(frame(s,revision));packet(r,s,revision,FrameClock.of(0,0));
            require(!r.lightingStatisticsCollection().effective()&&r.lightingStatisticsCollection().pending()==0,"Unified OFF selects the uninstrumented path");
            System.out.println("LIGHTING_INSTRUMENTATION PASS variants=8 signals=3 bitExact=true actualCounters=true unifiedOff=true");
        }
    }
}
