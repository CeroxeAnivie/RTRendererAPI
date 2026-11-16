package top.ceroxe.rt.renderer.rtxpt;

import java.nio.*;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;

/** Compare incremental indexed receivers against a fresh complete publication. */
public final class RtxptReceiverTransportNativeSelfTest {
    private static final int SIZE=64;
    private static final int[] INDEX={0,1,2,2,3,0};
    private static MeshAsset mesh(long id,float z,boolean caster,int variant){
        float[] p={-1,-1,z,1,-1,z,1,1,z,-1,1,z};
        float[] baseline={.05f,.9f,.2f,.7f,.4f,.5f,.6f,.3f};
        int[] colors=caster?new int[]{0x20ffffff,0x60ffffff,0xa0ffffff,0xe0ffffff}:new int[]{-1,-1,-1,-1};
        if(variant>=3){baseline=new float[]{.8f,.1f,.6f,.2f,.4f,.3f,.2f,.4f};if(caster)colors=new int[]{0xe0ffffff,0xa0ffffff,0x60ffffff,0x20ffffff};}
        return MeshAsset.builder(id,p,INDEX,new long[]{1,1}).lightmapCoordinates(baseline).vertexColorsRgba8(colors).build();
    }
    private static float[] override(int step){return step>=4?new float[]{.1f,.2f,.3f,.4f,.5f,.6f,.7f,.8f}:null;}
    public static void main(String[] args)throws Exception{
        Path output=Files.createDirectories(Path.of(args[0]));
        var style=new NativeLightingStyle(NativeLightingStyle.AdditionMode.DYNAMIC_ONLY_V1,.75f,0,1,0,1,1,1,0,1,1);
        var plan=new NativeLightingPlan(SIZE,SIZE,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.NORMAL_DISTANCE),0,6,0,style,
            new NativeLightingPlan.Directional(0,0,1,1,1,1,1),null,false);
        byte[][][] reference=new byte[6][2][];int images=0;
        for(boolean incremental:new boolean[]{false,true})try(var renderer=RtxptRenderers.openNativeLighting(plan)){
            var access=renderer.extension(RtxptAccess.class).orElseThrow();var lighting=renderer.extension(NativeLightingControl.class).orElseThrow();
            lighting.lightingReconstruction(NativeLightingReconstruction.NONE);
            var field=RtxptRenderer.class.getDeclaredField("handle");field.setAccessible(true);
            long lastReceiver=0,lastCaster=0;
            for(int step=1;step<=6;step++){
                long receiver=step==1?1:3,caster=step==1?2:4,receiverInstance=step==1?11:33,casterInstance=step==1?22:44;
                var tx=SceneTransaction.builder(step);
                if(!incremental||step==1)tx.resetScene().upsert(MaterialAsset.builder(1).doubleSided(true).build());
                else if(step==2)tx.removeInstance(11).removeInstance(22).removeMesh(lastReceiver).removeMesh(lastCaster);
                if(!incremental||step<=3||step==5){
                    float shift=step>=3?.015625f:0;
                    tx.upsert(mesh(receiver,shift,false,step)).upsert(mesh(caster,1+shift,true,step));
                    tx.upsert(SceneInstance.builder(receiverInstance,receiver).build()).upsert(SceneInstance.builder(casterInstance,caster).build());
                }
                renderer.apply(tx.build());
                var overrides=override(step);
                lighting.publishLightingAttributes(new NativeLightingPublication(step,List.of(NativeLightingPublication.Instance.receiver(receiverInstance),NativeLightingPublication.Instance.shadowOnly(casterInstance)),
                    overrides==null?List.of():List.of(new NativeLightingPublication.MeshBaseline(receiver,overrides)),null,null));
                renderer.submit(RenderFrameRequest.builder(step,SIZE,SIZE,CameraState.lookAt(0,0,3,0,0,0).build()).minimumSceneRevision(step).build());access.awaitIdle();
                try(var frame=lighting.acquireLightingFrame().orElseThrow()){require(frame.frame().sequence()==step&&frame.frame().attributeGeneration()==step,"Stale receiver publication");}
                for(int plane=0;plane<2;plane++){
                    Path file=output.resolve((incremental?"incremental":"complete")+"-"+step+"-"+plane+".pfm");
                    RtxptNative.nativeLightingCapture(field.getLong(renderer),file.toAbsolutePath().toString(),plane==0?0:2);
                    byte[] bytes=Files.readAllBytes(file);images++;
                    if(incremental)require(Arrays.equals(bytes,reference[step-1][plane]),"Indexed receiver differs from complete publication: step="+step+" plane="+plane);
                    else reference[step-1][plane]=bytes;
                }
                lastReceiver=receiver;lastCaster=caster;
            }
        }
        System.out.println("RECEIVER_TRANSPORT_NATIVE PASS images="+images+" indexedBaselines=true alpha=true inheritedGeometry=true publicOverride=true retainedOverride=true");
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
