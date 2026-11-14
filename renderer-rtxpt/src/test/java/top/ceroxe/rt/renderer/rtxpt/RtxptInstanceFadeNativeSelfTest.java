package top.ceroxe.rt.renderer.rtxpt;

import java.nio.*;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;

/** Physical surface fade stays separate from explicit receiver visibility. */
public final class RtxptInstanceFadeNativeSelfTest {
    public static void main(String[] args) throws Exception {
        Path output=Files.createDirectories(Path.of(args.length==0?"build/reports/instance-fade":args[0]));
        var plan=new NativeLightingPlan(64,64,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.NORMAL_DISTANCE),0,6,0,NativeLightingStyle.defaults());
        try(var renderer=RtxptRenderers.openHiddenWindow(RtxptRenderers.nativeLightingConfiguration())){
            var lighting=renderer.extension(NativeLightingControl.class).orElseThrow();
            var access=renderer.extension(RtxptAccess.class).orElseThrow();
            var material=MaterialAsset.builder(1).blendMode(MaterialAsset.BlendMode.MASKED).doubleSided(true).build();
            var mesh=MeshAsset.builder(1,new float[]{-2,-2,0,2,-2,0,2,2,0,-2,2,0},
                    new int[]{0,1,2,0,2,3},new long[]{1,1}).build();
            renderer.apply(SceneTransaction.builder(1).resetScene().upsert(material).upsert(mesh)
                    .upsert(SceneInstance.builder(1,1).surfaceVisibility(.75f).build()).build());
            lighting.nativeLightingPlan(plan);
            lighting.lightingReconstruction(NativeLightingReconstruction.NONE);
            lighting.publishLightingAttributes(new NativeLightingPublication(1,
                    List.of(new NativeLightingPublication.Instance(1,23,.7f,0,0,0,1,1)),List.of(),null,null));
            var field=RtxptRenderer.class.getDeclaredField("handle");field.setAccessible(true);long handle=field.getLong(renderer);
            var camera=CameraState.lookAt(0,0,3,0,0,0).build();
            double[] identity=null;long sequence=0;
            for(float fade:new float[]{.75f,1,.50001f,.49999f,0,.6f,1}){
                ++sequence;
                if(sequence>1)renderer.apply(SceneTransaction.builder(sequence)
                        .upsert(SceneInstance.builder(1,1).surfaceVisibility(fade).build()).build());
                renderer.submit(RenderFrameRequest.builder(sequence,64,64,camera).minimumSceneRevision(sequence).build());
                access.awaitIdle();handle=field.getLong(renderer);
                var state=RtxptNative.meshResourceState(handle,"i1");
                if(identity==null)identity=state;else for(int k=0;k<identity.length;k++)require(identity[k]==state[k],"Fade recreated geometry resource "+k);
                try(var lease=lighting.acquireLightingFrame().orElseThrow()){
                    require(lease.frame().sequence()==sequence,"Stale fade frame");
                }
                float z=capture(handle,output.resolve("retained-"+sequence+".pfm"),2);
                require(fade>=.5f?Math.abs(z)>0.9f:z==0,"Masked fade cutoff ignored: "+fade+" normal="+z);
            }
            renderer.apply(SceneTransaction.builder(++sequence).removeInstance(1).build());
            identity=null;
            for(float fade:new float[]{1,.6f,.49f,0,1}){
                var primitives=FramePrimitiveBatch.builder().add(PrimitiveInstance.builder(1).surfaceVisibility(fade).build()).build();
                renderer.submit(RenderFrameRequest.builder(sequence,64,64,camera).minimumSceneRevision(8)
                        .primitiveBatch(primitives).build());access.awaitIdle();handle=field.getLong(renderer);
                var state=RtxptNative.meshResourceState(handle,"p0");
                if(identity==null)identity=state;else for(int k=0;k<identity.length;k++)require(identity[k]==state[k],"Primitive fade recreated geometry");
                float z=capture(handle,output.resolve("primitive-"+sequence+".pfm"),2);
                require(fade>=.5f?Math.abs(z)>0.9f:z==0,"Primitive cutoff ignored");++sequence;
            }
        }
        System.out.println("INSTANCE_FADE_NATIVE PASS retained=true primitive=true cutoff=true stableBlas=true freshFrames=true");
    }
    private static float capture(long handle,Path file,int signal)throws Exception{
        RtxptNative.nativeLightingCapture(handle,file.toAbsolutePath().toString(),signal);
        byte[] bytes=Files.readAllBytes(file);int start=0;for(int line=0;line<3;line++)while(bytes[start++]!='\n'){}
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getFloat(start+((64-1-32)*64+32)*12+8);
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
