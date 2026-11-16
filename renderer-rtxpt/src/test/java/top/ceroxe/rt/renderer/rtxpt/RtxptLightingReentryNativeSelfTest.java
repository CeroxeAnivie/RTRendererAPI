package top.ceroxe.rt.renderer.rtxpt;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import top.ceroxe.rt.renderer.api.*;

/** Dormant geometry must republish metadata when its former dense slot was overwritten. */
public final class RtxptLightingReentryNativeSelfTest {
    private static final int SIZE=32;
    private static final CameraState CAMERA=CameraState.lookAt(0,0,3,0,0,0).build();
    private static MeshAsset quad(long id,long material,float x){
        return MeshAsset.builder(id,new float[]{x-1,-1,0,x+1,-1,0,x+1,1,0,x-1,1,0},
            new int[]{0,1,2,2,3,0},new long[]{material,material}).build();
    }
    public static void main(String[] args)throws Exception{
        Path output=Path.of(args.length==0?"build/reports/lighting-reentry-contract":args[0]).toAbsolutePath();
        Files.createDirectories(output);
        var plan=new NativeLightingPlan(SIZE,SIZE,Set.of(NativeLightingPlan.Signal.NORMAL_DISTANCE),
            0,6,0,NativeLightingStyle.defaults());
        int overwrittenReentries=0;
        try(var renderer=RtxptRenderers.openNativeLighting(RtxptRenderers.nativeLightingConfiguration(),plan)){
            var access=renderer.extension(RtxptAccess.class).orElseThrow();
            var lighting=renderer.extension(NativeLightingControl.class).orElseThrow();
            var field=RtxptRenderer.class.getDeclaredField("handle");field.setAccessible(true);
            var tx=SceneTransaction.builder(1).resetScene()
                .upsert(MaterialAsset.builder(1).doubleSided(true).build())
                .upsert(quad(1,1,10)).upsert(SceneInstance.builder(1,1).visibilityMask(1).build());
            for(int id=2;id<=6;id++)tx.upsert(MaterialAsset.builder(id).doubleSided(true)
                .blendMode(MaterialAsset.BlendMode.MASKED).baseColorRgba8((id&1)==0?0xffffffff:0x00ffffff)
                .alphaCutoff(.1f).build()).upsert(quad(id,id,0));
            renderer.apply(tx.build());
            Map<Integer,Long> slotOwners=new HashMap<>();Map<Long,Integer> priorSlots=new HashMap<>();
            long[] ids={2,3,2,4,5,4,2,6,5,6,3,2};
            for(int frame=0;frame<ids.length;frame++){
                long mesh=ids[frame],sequence=frame+1;
                var batch=FramePrimitiveBatch.builder().add(PrimitiveInstance.builder(mesh).visibilityMask(1).build()).build();
                renderer.submit(RenderFrameRequest.builder(sequence,SIZE,SIZE,CAMERA).minimumSceneRevision(1)
                    .primitiveBatch(batch).build());access.awaitIdle();
                try(var lease=lighting.acquireLightingFrame().orElseThrow()){
                    if(lease.frame().sequence()!=sequence||lease.frame().sceneRevision()!=1)
                        throw new AssertionError("Reentry output used a different resource generation");
                }
                long handle=field.getLong(renderer);
                var metadata=RtxptNative.lightingMetadataState(handle,"p0","mesh"+mesh+"_v3f800000:0");
                int slot=(int)metadata[1];Integer prior=priorSlots.put(mesh,slot);
                if(prior!=null&&prior==slot&&slotOwners.getOrDefault(slot,mesh).longValue()!=mesh)overwrittenReentries++;
                slotOwners.put(slot,mesh);
                Path image=output.resolve("reentry-"+sequence+".pfm");
                RtxptNative.nativeLightingCapture(handle,image.toString(),2);
                byte[] bytes=Files.readAllBytes(image);int begin=0;
                for(int line=0;line<3;line++)while(bytes[begin++]!=10){}
                var pixels=ByteBuffer.wrap(bytes,begin,bytes.length-begin).slice().order(ByteOrder.LITTLE_ENDIAN);
                float expected=(mesh&1)==0?1:0;
                for(int y=14;y<=17;y++)for(int x=14;x<=17;x++){
                    int center=(y*SIZE+x)*12;
                    if(Math.abs(pixels.getFloat(center))>.001||Math.abs(pixels.getFloat(center+4))>.001
                        ||Math.abs(pixels.getFloat(center+8)-expected)>.001)
                        throw new AssertionError("Dense slot contains another geometry's metadata: frame="+sequence+" mesh="+mesh+" slot="+slot);
                }
            }
        }
        if(overwrittenReentries==0)throw new AssertionError("Fixture never reused an overwritten dense slot");
        System.out.println("LIGHTING_REENTRY_NATIVE PASS frames=12 overwrittenReentries="+overwrittenReentries+" freshFirstFrame=true");
    }
}
