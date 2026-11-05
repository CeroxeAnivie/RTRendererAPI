package top.ceroxe.rt.renderer.rtxpt;

import java.nio.LongBuffer;
import java.nio.file.Files;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingMatrixNativeSelfTest.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Published-runtime regression for authored tint, bounded updates and unified OFF. */
public final class RtxptRetainedLightingRepairNativeSelfTest {
    static MeshAsset bounce(int... colors){return plane(2,2,4,1,0,0,false).toBuilder().vertexColorsRgba8(colors).build();}
    static double[] sample(RtxptRenderer r,long sequence,long revision,String label)throws Exception{
        r.submit(frame(sequence,revision));var pixels=capture(r,""+label,1);packet(r,sequence,revision,FrameClock.of(0,0));return pixels;
    }
    static void channels(double[] pixels,double red,double green,double blue,String label){
        near(pixels[CENTER],red,.003,label+" red");near(pixels[CENTER+1],green,.003,label+" green");near(pixels[CENTER+2],blue,.003,label+" blue");
    }
    public static void main(String[] args)throws Exception{
        Files.createDirectories(OUT);double expected=giOracle(128/255.0);
        try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            r.nativeLightingPlan(plan(Set.of(NativeLightingPlan.Signal.INDIRECT_DIFFUSE),2,SUN_OFF,true));
            var initial=SceneTransaction.builder(1).resetScene().upsert(material(1,128,128,128)).upsert(material(2,255,255,255))
                .upsert(plane(1,1,2,0,0,0,false)).upsert(bounce(rgba(255,0,0),rgba(255,0,0),rgba(255,0,0),rgba(255,0,0)))
                .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build());
            for(long id=3;id<=256;id++)initial.upsert(plane(id,1,.1f,-10,0,0,false)).upsert(SceneInstance.builder(id,id)
                .transform(new AffineTransform(new float[]{1,0,0,100+id,0,1,0,0,0,0,1,0})).build());
            r.apply(initial.build(),List.of(source(1,0,0,.5,8,false)),LongBuffer.allocate(0));
            publication(r,1,new NativeLightingPublication.Instance(2,4,1,0,1,1,0,0));
            channels(sample(r,0,1,"vertex-red"),expected,0,0,"Red authored bounce tint");
            var resources=r.retainedUpdateTimings();var first=r.lightingAttributeUpdateStatistics();
            r.apply(SceneTransaction.builder(2).upsert(bounce(rgba(0,255,0),rgba(0,255,0),rgba(0,255,0),rgba(0,255,0))).build());
            channels(sample(r,1,2,"vertex-green"),0,expected,0,"Green authored bounce tint");
            var changed=r.lightingAttributeUpdateStatistics();
            require(changed.colorCorners()-first.colorCorners()==6&&changed.baselineCorners()==first.baselineCorners(),"RGB edit converts only six changed color corners");
            require(changed.layoutBuilds()==first.layoutBuilds()&&changed.localGeometryWrites()==first.localGeometryWrites()+1,"RGB edit patches one resident geometry range");
            require(r.retainedUpdateTimings().builtBlases()==resources.builtBlases()&&r.retainedUpdateTimings().exportedMeshVariants()==resources.exportedMeshVariants(),"Pure tint preserves all 256 resident BLAS and geometry exports");
            r.apply(SceneTransaction.builder(3).upsert(plane(1,1,2,0,0,0,false).toBuilder()
                .vertexColorsRgba8(new int[]{rgba(255,0,255),rgba(255,0,255),rgba(255,0,255),rgba(255,0,255)}).build()).build());
            channels(sample(r,2,3,"receiver-tint"),0,0,0,"Receiver tint participates exactly once");
            r.apply(SceneTransaction.builder(4).upsert(plane(1,1,2,0,0,0,false))
                .upsert(bounce(rgba(0,0,255),rgba(255,0,255),rgba(255,255,255),rgba(0,255,255))).build());
            var interpolated=sample(r,3,4,"barycentric");double[] oracle=new double[3];
            for(int i=0;i<2;i++){
                double radius=Math.sqrt((i+.5)/2),angle=(i+.5)*2.39996323,z=Math.sqrt(1-radius*radius),t=(1-EPSILON)/z;
                double x=radius*Math.cos(angle)*t,y=radius*Math.sin(angle)*t,d=Math.sqrt(x*x+y*y+.25);
                double incoming=8*Math.pow(1-d/8,2)/Math.max(d*d,1)*(128/255.0)*(.35/2);
                oracle[0]+=incoming*(.5+x/8);oracle[1]+=incoming*(.5+y/8);oracle[2]+=incoming;
            }
            channels(interpolated,oracle[0],oracle[1],oracle[2],"Raw numeric barycentric RGB");
            r.apply(SceneTransaction.builder(5).upsert(bounce()).build());
            channels(sample(r,4,5,"neutral-absent"),expected,expected,expected,"Absent vertex color is neutral");
            var beforeMaterial=r.lightingAttributeUpdateStatistics();
            r.apply(SceneTransaction.builder(6).upsert(material(2,0,255,0)).build());
            channels(sample(r,5,6,"material-local"),0,expected,0,"Material-only update");
            var afterMaterial=r.lightingAttributeUpdateStatistics();
            require(afterMaterial.baselineCorners()==beforeMaterial.baselineCorners()&&afterMaterial.colorCorners()==beforeMaterial.colorCorners(),"Material edit does not retranslate unrelated attributes");
            require(afterMaterial.layoutBuilds()==beforeMaterial.layoutBuilds(),"Material edit does not rebuild attribute mapping");
            r.diagnosticLevel(RtxptDiagnostics.Level.DEEP);r.lightingWorkStatisticsEnabled(true);sample(r,6,6,"statistics-on");
            long counted=r.lightingWorkStatistics().sequence();r.diagnosticLevel(RtxptDiagnostics.Level.OFF);
            r.diagnosticLevel(RtxptDiagnostics.Level.DEEP);r.lightingWorkStatisticsEnabled(true); // must not bypass unified OFF
            sample(r,7,6,"off-drain");r.lightingWorkStatistics();var drained=r.lightingStatisticsCollection();
            for(long sequence=8;sequence<=13;sequence++){sample(r,sequence,6,"off-"+sequence);r.lightingWorkStatistics();}
            var stopped=r.lightingStatisticsCollection();
            require(stopped.requested()&&!stopped.effective()&&stopped.pending()==0,"OFF requested/effective and bounded pending state");
            require(stopped.copies()==drained.copies()&&stopped.queries()==drained.queries()&&stopped.polls()==drained.polls(),"OFF creates no new statistics copies, queries or polling after drain");
            require(r.lightingWorkStatistics().sequence()==counted,"OFF freezes newly collected frame sequence");
            r.diagnosticLevel(RtxptDiagnostics.Level.SUMMARY);sample(r,14,6,"summary-uninstrumented");
            require(!r.lightingStatisticsCollection().effective(),"SUMMARY keeps explicit intent without ray atomics");
            r.diagnosticLevel(RtxptDiagnostics.Level.DEEP);sample(r,15,6,"statistics-resumed");
            require(r.lightingWorkStatistics().sequence()==15&&r.lightingStatisticsCollection().effective(),"DEEP restores explicit counter intent");
            System.out.println("RTXPT_REPAIRS PASS meshes=256 tint=receiver,bounce,barycentric,neutral baselineLocal=true unifiedOff=true statistics="+stopped+" attributes="+afterMaterial);
        }
    }
}
