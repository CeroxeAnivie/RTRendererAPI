package top.ceroxe.rt.renderer.rtxpt;

import java.nio.*;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;

/** Native allocation handoff, ordinary rendering, forged block rejection and exact release. */
public final class RtxptOwnedMeshInputsNativeSelfTest {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void rejected(Runnable call){boolean rejected=false;try{call.run();}catch(RuntimeException expected){rejected=true;}require(rejected,"Invalid mesh input transfer accepted");}
    private static final float[] POSITIONS={-1,-1,0,1,-1,0,1,1,0,-1,1,0};
    private static final int[] INDICES={0,1,2,2,3,0};
    private static MeshAsset mesh(long id,float z){var positions=POSITIONS.clone();for(int i=2;i<positions.length;i+=3)positions[i]=z;
        return MeshAsset.builder(id,positions,INDICES,new long[]{1,1}).lightmapCoordinates(new float[]{.1f,.9f,.3f,.7f,.5f,.5f,.7f,.3f}).build();}
    private static void submit(Renderer renderer,NativeLightingControl lighting,RtxptAccess access,long sequence,long... ids){
        lighting.publishLightingAttributes(new NativeLightingPublication(sequence,Arrays.stream(ids).mapToObj(NativeLightingPublication.Instance::receiver).toList(),List.of(),null,null));
        renderer.submit(RenderFrameRequest.builder(sequence,64,64,CameraState.lookAt(0,0,3,0,0,0).build()).minimumSceneRevision(sequence).build());access.awaitIdle();
        try(var frame=lighting.acquireLightingFrame().orElseThrow()){require(frame.frame().sequence()==sequence,"Transferred geometry returned stale output");}
    }
    public static void main(String[] args)throws Exception{
        var output=Path.of(args[0]);Files.createDirectories(output);RtxptNative.load();
        rejected(()->RtxptNative.allocateMeshInputs(new int[0]));rejected(()->RtxptNative.allocateMeshInputs(new int[]{0}));
        rejected(()->RtxptNative.allocateMeshInputs(new int[]{-1}));rejected(()->RtxptNative.allocateMeshInputs(new int[]{512*1024*1024,1}));
        long ticket=RtxptNative.allocateMeshInputs(new int[]{64,127});
        var a=RtxptNative.meshInputBuffer(ticket,0);var b=RtxptNative.meshInputBuffer(ticket,1);
        require(a.isDirect()&&a.capacity()==64&&b.capacity()==127,"Native blocks lost their exact extent");
        a.putLong(0,0x0123456789abcdefL);require(a.getLong(0)==0x0123456789abcdefL,"Native mesh input block lost bytes");
        final long mapped=ticket;rejected(()->RtxptNative.meshInputBuffer(mapped,-1));rejected(()->RtxptNative.meshInputBuffer(mapped,2));
        RtxptNative.releaseMeshInputs(ticket);rejected(()->RtxptNative.meshInputBuffer(mapped,0));RtxptNative.releaseMeshInputs(ticket);
        var plan=new NativeLightingPlan(64,64,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.NORMAL_DISTANCE),0,6,0,NativeLightingStyle.defaults());
        try(var renderer=RtxptRenderers.openNativeLighting(RtxptRenderers.nativeLightingConfiguration(),plan)){
            var lighting=renderer.extension(NativeLightingControl.class).orElseThrow();var access=renderer.extension(RtxptAccess.class).orElseThrow();
            lighting.lightingReconstruction(NativeLightingReconstruction.NONE);
            var handleField=RtxptRenderer.class.getDeclaredField("handle");handleField.setAccessible(true);
            renderer.apply(SceneTransaction.builder(1).resetScene().upsert(MaterialAsset.builder(1).doubleSided(true).build())
                    .upsert(mesh(1,0)).upsert(SceneInstance.builder(11,1).build()).build());submit(renderer,lighting,access,1,11);
            long handle=handleField.getLong(renderer);var before=RtxptNative.meshInputRetentionState(handle);
            // A steady publication enables native per-mesh block transport. Its
            // unchanged sibling must not retain the newly deleted block later.
            renderer.apply(SceneTransaction.builder(2).upsert(mesh(2,.5f)).upsert(SceneInstance.builder(22,2).build()).build());submit(renderer,lighting,access,2,11,22);
            var both=RtxptNative.meshInputRetentionState(handle);require(both[2]==2&&both[0]>before[0],"Transferred mesh allocation not retained");
            RtxptNative.nativeLightingCapture(handle,output.resolve("two-meshes-normal.pfm").toAbsolutePath().toString(),2);
            // Packing a private mesh packet then abandoning it must release its
            // registry ticket without touching the already retained mesh data.
            var scene=new RtxptScene();scene.retain(SceneTransaction.builder(1).upsert(MaterialAsset.builder(1).doubleSided(true).build())
                .upsert(mesh(99,.75f)).upsert(SceneInstance.builder(999,99).build()).build());
            try(var pool=new RtxptMemoryResources.PacketPool()){
                pool.meshTransfers(true);var items=scene.items(RenderFrameRequest.builder(3,64,64,CameraState.lookAt(0,0,3,0,0,0).build()).build());
                var packet=RtxptMemoryResources.pack(new RtxptScene(),scene,items,List.of(new RtxptScene.Variant(1,null,1)),items,List.of(),List.of(),Map.of(),pool,List.of(),List.of(),List.of(),Map.of());
                require(packet.getInt(0)==0x5244525f,"Owned transport protocol did not activate");long abandoned=pool.pendingMeshTransfer();
                require(abandoned!=0&&RtxptNative.meshInputBuffer(abandoned,0).capacity()>0,"Missing independently owned block");
                pool.clear();rejected(()->RtxptNative.meshInputBuffer(abandoned,0));
            }
            renderer.apply(SceneTransaction.builder(3).removeInstance(22).removeMesh(2).build());submit(renderer,lighting,access,3,11);
            var remaining=RtxptNative.meshInputRetentionState(handle);require(remaining[0]==before[0]&&remaining[2]==before[2],"Deleting a transferred mesh did not release exactly its owner");
            renderer.apply(SceneTransaction.builder(4).resetScene().build());submit(renderer,lighting,access,4);
            var reset=RtxptNative.meshInputRetentionState(handle);require(reset[0]==0&&reset[2]==0,"Reset retained transferred inputs");
        }
        System.out.println("OWNED_MESH_INPUTS_NATIVE PASS allocationBounds=true exactBlocks=true cancelledPacketRelease=true retiredBlockRelease=true sceneReset=true freshFrame=true");
    }
}
