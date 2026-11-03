package top.ceroxe.rt.renderer.rtxpt;
import java.util.*;
import java.nio.LongBuffer;
import java.nio.file.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingMatrixNativeSelfTest.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Add an inert second group to force the full pipeline, without changing radiance. */
public final class RtxptLightingGroupSpecializationNativeSelfTest {
    public static void main(String[] args)throws Exception{
        Files.createDirectories(OUT);long sequence=0,revision=1;
        try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            r.nativeLightingPlan(plan(EnumSet.allOf(NativeLightingPlan.Signal.class),2,SUN_ON,true));
            r.apply(SceneTransaction.builder(revision).resetScene().upsert(material(1,128,128,128)).upsert(material(2,255,64,16))
                .upsert(plane(1,1,2,0,.25f,.75f,false)).upsert(plane(2,2,4,1,0,0,false))
                .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build()).build());
            publication(r,1,new NativeLightingPublication.Instance(2,4,1,0,1,1,0,0));
            for(var composition:SparseLightSource.Composition.values()){
                var a=source(0x100000001L,-.2,0,.5,8,true).withComposition(composition,31);
                var b=source(2,.2,.1,.5,4,false).withComposition(composition,31).withLayer(SparseLightSource.Layer.STATIC);
                // No grouped sources for PHYSICAL_SUM; two inactive groups still select the generic shader.
                var inactiveA=source(100,0,0,.5,0,false).withComposition(SparseLightSource.Composition.VISIBLE_GROUP_CHANNEL_MAX,30);
                var inactiveB=source(101,0,0,.5,0,false).withComposition(SparseLightSource.Composition.VISIBLE_GROUP_CHANNEL_MAX,29);
                r.apply(SceneTransaction.builder(++revision).build(),List.of(a,b),LongBuffer.allocate(0));
                long first=sequence++;r.submit(frame(first,revision));double[] direct=capture(r,"single-"+composition+"-direct",0),indirect=capture(r,"single-"+composition+"-gi",1);packet(r,first,revision,FrameClock.of(0,0));
                r.apply(SceneTransaction.builder(++revision).build(),List.of(inactiveA,inactiveB),LongBuffer.allocate(0));
                long next=sequence++;r.submit(frame(next,revision));
                require(Arrays.equals(direct,capture(r,"general-"+composition+"-direct",0)),"Single/general gain exact: "+composition);
                require(Arrays.equals(indirect,capture(r,"general-"+composition+"-gi",1)),"Single/general GI exact: "+composition);packet(r,next,revision,FrameClock.of(0,0));
                r.apply(SceneTransaction.builder(++revision).upsert(SceneLight.point(9000,100,100,100).intensity(1).range(1).build()).build());
                next=sequence++;r.submit(frame(next,revision));
                require(Arrays.equals(direct,capture(r,"analytic-general-"+composition+"-direct",0)),"Analytic-capable full-group gain exact");
                require(Arrays.equals(indirect,capture(r,"analytic-general-"+composition+"-gi",1)),"Analytic-capable full-group GI exact");packet(r,next,revision,FrameClock.of(0,0));
                r.apply(SceneTransaction.builder(++revision).build(),List.of(),LongBuffer.wrap(new long[]{100,101}));
                next=sequence++;r.submit(frame(next,revision));require(Arrays.equals(direct,capture(r,"restored-"+composition,0)),"Removing group restores specialization");packet(r,next,revision,FrameClock.of(0,0));
                require(Arrays.equals(indirect,capture(r,"analytic-single-"+composition+"-gi",1)),"Analytic-capable single-group GI exact");
                r.apply(SceneTransaction.builder(++revision).removeLight(9000).build());next=sequence++;r.submit(frame(next,revision));
                require(Arrays.equals(direct,capture(r,"sparse-only-"+composition+"-direct",0)),"Removing analytic source restores sparse-only shader");
                require(Arrays.equals(indirect,capture(r,"sparse-only-"+composition+"-gi",1)),"Sparse-only/general GI exact");packet(r,next,revision,FrameClock.of(0,0));
            }
            System.out.println("LIGHT_GROUP_SPECIALIZATION PASS compositions=3 group=31 staticAndDynamic=true stable64BitIds=true gainAndGiBitExact=true variants=4 frames="+sequence);
        }
    }
}
