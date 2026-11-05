package top.ceroxe.rt.renderer.rtxpt;

import java.nio.LongBuffer;
import java.nio.file.Files;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingMatrixNativeSelfTest.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Visible attributes survive unrelated topology changes, slot migration and reuse. */
public final class RtxptAttributeTopologyNativeSelfTest {
    public static void main(String[] args)throws Exception {
        Files.createDirectories(OUT);long sequence=0,revision=1;
        try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            r.nativeLightingPlan(plan(EnumSet.allOf(NativeLightingPlan.Signal.class),2,SUN_OFF,true));
            int tint=rgba(64,192,255);
            r.apply(SceneTransaction.builder(revision).resetScene().upsert(material(1,128,128,128)).upsert(material(2,255,255,255))
                .upsert(plane(1,1,2,0,.2f,.7f,false))
                .upsert(plane(2,2,4,1,0,0,false).toBuilder().vertexColorsRgba8(new int[]{tint,tint,tint,tint}).build())
                .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build()).build(),
                List.of(source(1,0,0,.5,8,false)),LongBuffer.allocate(0));
            publication(r,1,new NativeLightingPublication.Instance(2,4,1,0,1,1,0,0));
            r.submit(frame(sequence,revision));var reference=new ArrayList<double[]>();
            for(int signal=0;signal<3;signal++)reference.add(capture(r,"attribute-topology-reference-"+signal,signal));
            packet(r,sequence++,revision,FrameClock.of(0,0));
            var live=new ArrayList<Long>();
            for(int batch=0;batch<8;batch++){
                var tx=SceneTransaction.builder(++revision);
                for(long id:live)tx.removeInstance(id).removeMesh(id);live.clear();
                for(int k=0;k<19+batch;k++){
                    long id=100+batch*32L+k;live.add(id);
                    tx.upsert(plane(id,1,.1f,-10,0,0,false)).upsert(SceneInstance.builder(id,id)
                        .transform(new AffineTransform(new float[]{1,0,0,100+id,0,1,0,0,0,0,1,0})).build());
                }
                r.apply(tx.build());r.submit(frame(sequence,revision));
                for(int signal=0;signal<3;signal++)require(Arrays.equals(reference.get(signal),capture(r,"attribute-topology-"+batch+"-"+signal,signal)),
                    "Topology migration preserves every visible attribute, signal="+signal+" batch="+batch);
                packet(r,sequence++,revision,FrameClock.of(0,0));
            }
            r.awaitIdle();System.out.println("ATTRIBUTE_TOPOLOGY PASS batches=8 signals=3 bitExact=true roles=true colors=true baselines=true");
        }
    }
}
