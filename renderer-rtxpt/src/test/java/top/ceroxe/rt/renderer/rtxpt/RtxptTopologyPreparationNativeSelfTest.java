package top.ceroxe.rt.renderer.rtxpt;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.EnumSet;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingMatrixNativeSelfTest.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Visible multi-material geometry survives material/geometry slot migration and table growth. */
public final class RtxptTopologyPreparationNativeSelfTest {
    static SceneInstance offscreen(long id,long mesh) {
        return SceneInstance.builder(id,mesh).transform(new AffineTransform(
            new float[]{1,0,0,100+id,0,1,0,0,0,0,1,0})).build();
    }
    static MeshAsset multi(long id,long... materials) {
        int triangles=materials.length;
        float[] positions=new float[triangles*9];int[] indices=new int[triangles*3];
        for(int t=0;t<triangles;t++) {
            float[] triangle=t%2==0?new float[]{-2,-2,0,2,-2,0,2,2,0}:new float[]{-2,-2,0,2,2,0,-2,2,0};
            System.arraycopy(triangle,0,positions,t*9,9);
            for(int v=0;v<3;v++)indices[t*3+v]=t*3+v;
        }
        return MeshAsset.builder(id,positions,indices,materials).build();
    }
    static void sameFrame(RtxptRenderer r,long sequence,long revision,double[][] reference,String label)throws Exception {
        r.submit(frame(sequence,revision));
        for(int signal=0;signal<3;signal++)require(Arrays.equals(reference[signal],capture(r,"topology-preparation-"+label+"-"+signal,signal)),
            "Unchanged visible multi-material surface remains exact after "+label+", signal="+signal);
        packet(r,sequence,revision,FrameClock.of(0,0));
    }
    public static void main(String[] args)throws Exception {
        Files.createDirectories(OUT);
        try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)) {
            r.nativeLightingPlan(plan(EnumSet.allOf(NativeLightingPlan.Signal.class),2,SUN_ON,true));
            r.apply(SceneTransaction.builder(1).resetScene()
                .upsert(MaterialAsset.builder(1).baseColorRgba8(0x00000000).blendMode(MaterialAsset.BlendMode.MASKED).build())
                .upsert(MaterialAsset.builder(2).baseColorRgba8(0x00000000).blendMode(MaterialAsset.BlendMode.MASKED).build())
                .upsert(material(3,255,255,255))
                .upsert(plane(1,1,.1f,0,0,0,false)).upsert(multi(2,2,3)).upsert(plane(3,3,.1f,0,0,0,false))
                .upsert(offscreen(1,1)).upsert(SceneInstance.builder(2,2).build()).upsert(offscreen(3,3)).build());
            r.submit(frame(0,1));double[][] reference=new double[3][];
            for(int signal=0;signal<3;signal++)reference[signal]=capture(r,"topology-preparation-reference-"+signal,signal);
            require(Arrays.stream(reference[2]).anyMatch(v->Math.abs(v)>.5),"Reference contains visible geometry");
            long hits=java.util.stream.IntStream.range(0,W*H).filter(p->lengthSquared(reference[2],p*3)>.5).count();
            require(hits>100&&hits<W*H-100,"Reference contains both masked holes and an opaque region");
            packet(r,0,1,FrameClock.of(0,0));
            r.apply(SceneTransaction.builder(2).removeInstance(1).removeMesh(1).build());
            sameFrame(r,1,2,reference,"material-swap");
            r.apply(SceneTransaction.builder(3).upsert(material(4,0,0,0)).upsert(material(5,32,192,64))
                .upsert(multi(4,1,4,5)).upsert(offscreen(4,4)).build());
            sameFrame(r,2,3,reference,"retired-material-slot-reuse");
            r.apply(SceneTransaction.builder(4).removeInstance(3).removeMesh(3).build());
            sameFrame(r,3,4,reference,"mixed-geometry-prefix-migration");
            var grow=SceneTransaction.builder(5);
            for(int k=0;k<1025;k++)grow.upsert(plane(100+k,2,.1f,0,0,0,false)).upsert(offscreen(100+k,100+k));
            r.apply(grow.build());sameFrame(r,4,5,reference,"table-growth");
            var shrink=SceneTransaction.builder(6);
            for(int k=0;k<1025;k++)shrink.removeInstance(100+k).removeMesh(100+k);
            r.apply(shrink.build());sameFrame(r,5,6,reference,"table-shrink");
            System.out.println("TOPOLOGY_PREPARATION PASS frames=6 signals=3 materialMigration=true geometryMigration=true capacityGrowth=true bitExact=true");
        }
    }
}
