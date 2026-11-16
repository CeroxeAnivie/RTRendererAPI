package top.ceroxe.rt.renderer.rtxpt;

import java.nio.*;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;

/** A reset reuses logical geometry names, never the previous material namespace. */
public final class RtxptLightingResetNativeSelfTest {
    private static final int SIZE=32;
    private static final CameraState CAMERA=CameraState.lookAt(0,0,3,0,0,0).build();
    private static MeshAsset mesh(long id,long material,float x){
        return MeshAsset.builder(id,new float[]{x-1,-1,0,x+1,-1,0,x+1,1,0,x-1,1,0},
            new int[]{0,1,2,2,3,0},new long[]{material,material})
            .lightmapCoordinates(new float[8]).build();
    }
    private static MaterialAsset material(long id,boolean visible){
        return MaterialAsset.builder(id).doubleSided(true).blendMode(MaterialAsset.BlendMode.MASKED)
            .baseColorRgba8(visible?0xffffffff:0x00ffffff).alphaCutoff(.1f).build();
    }
    public static void main(String[] args)throws Exception{
        Path output=Path.of(args.length==0?"build/reports/lighting-reset-contract":args[0]).toAbsolutePath();
        Files.createDirectories(output);Set<Integer> surfaceSlots=new HashSet<>();
        var plan=new NativeLightingPlan(SIZE,SIZE,Set.of(NativeLightingPlan.Signal.NORMAL_DISTANCE),0,6,0,NativeLightingStyle.defaults());
        try(var renderer=RtxptRenderers.openNativeLighting(RtxptRenderers.nativeLightingConfiguration(),plan)){
            var access=renderer.extension(RtxptAccess.class).orElseThrow();
            var lighting=renderer.extension(NativeLightingControl.class).orElseThrow();
            var field=RtxptRenderer.class.getDeclaredField("handle");field.setAccessible(true);
            for(int epoch=0;epoch<8;epoch++){
                long material=10+epoch*7;long revision=epoch+1;
                var tx=SceneTransaction.builder(revision).resetScene().upsert(material(material,true))
                    .upsert(mesh(1,material,0)).upsert(SceneInstance.builder(1,1).build());
                for(int i=0;i<4+epoch;i++)tx.upsert(material(100+epoch*17+i,false))
                    .upsert(mesh(100+i,100+epoch*17+i,10+i*3)).upsert(SceneInstance.builder(100+i,100+i).build());
                renderer.apply(tx.build());
                renderer.submit(RenderFrameRequest.builder(revision,SIZE,SIZE,CAMERA).minimumSceneRevision(revision).build());
                access.awaitIdle();
                try(var lease=lighting.acquireLightingFrame().orElseThrow()){
                    if(lease.frame().sequence()!=revision||lease.frame().sceneRevision()!=revision)
                        throw new AssertionError("First reset frame used stale resources");
                }
                long handle=field.getLong(renderer);
                // The diagnostic checks the published selector against this generation's material table.
                var surface=RtxptNative.lightingSurfaceState(handle,"mesh1_v3f800000:0",0);
                surfaceSlots.add((int)surface[0]);
                Path image=output.resolve("reset-"+epoch+".pfm");RtxptNative.nativeLightingCapture(handle,image.toString(),2);
                var bytes=Files.readAllBytes(image);int begin=0,lines=0;
                while(lines<3)if(bytes[begin++]==10)lines++;
                var pixels=ByteBuffer.wrap(bytes,begin,bytes.length-begin).slice().order(ByteOrder.LITTLE_ENDIAN);
                int center=(SIZE/2*SIZE+SIZE/2)*12;
                if(Math.abs(pixels.getFloat(center))>.001||Math.abs(pixels.getFloat(center+4))>.001||Math.abs(pixels.getFloat(center+8)-1)>.001)
                    throw new AssertionError("Opaque current-generation receiver disappeared at reset "+epoch);
            }
        }
        if(surfaceSlots.size()<2)throw new AssertionError("Reset fixture did not change native material slots");
        System.out.println("LIGHTING_RESET_NATIVE PASS resets=8 reusedGeometryName=true sameTriangleCount=true freshFirstFrame=true materialSlots="+surfaceSlots);
    }
}
