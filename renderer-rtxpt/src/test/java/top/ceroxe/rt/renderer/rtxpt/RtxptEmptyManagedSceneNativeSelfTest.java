package top.ceroxe.rt.renderer.rtxpt;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;

/** A fresh empty managed session must work before any mesh allocates native scene tables. */
public final class RtxptEmptyManagedSceneNativeSelfTest {
    private static final int SIZE=32;
    private static final CameraState CAMERA=CameraState.lookAt(0,0,4,0,0,0).aspectRatio(1).build();
    private static Path lifecycle;
    private static void mark(String phase) {
        try {Files.writeString(lifecycle,phase+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
        catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
    }
    public static void main(String[] args)throws Exception {
        Path output=Path.of(args.length==0?"build/reports/empty-managed-scene-contract":args[0]).toAbsolutePath();
        Files.createDirectories(output);
        lifecycle=output.resolve("lifecycle.txt");mark("start");
        var plan=new NativeLightingPlan(SIZE,SIZE,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,
                NativeLightingPlan.Signal.NORMAL_DISTANCE),0,6,0,NativeLightingStyle.defaults());
        try(var renderer=RtxptRenderers.openNativeLighting(RtxptRenderers.nativeLightingConfiguration(),plan)) {
            mark("renderer-opened");
            var access=renderer.extension(RtxptAccess.class).orElseThrow();
            var lighting=renderer.extension(NativeLightingControl.class).orElseThrow();
            lighting.lightingReconstruction(NativeLightingReconstruction.NONE);
            renderer.apply(SceneTransaction.builder(1).resetScene().build());
            render(renderer,access,lighting,1,false);
            var field=RtxptRenderer.class.getDeclaredField("handle");field.setAccessible(true);
            long handle=field.getLong(renderer);
            empty(handle,output,"first-empty");
            var mesh=MeshAsset.builder(2,new float[]{-1,-1,0,1,-1,0,1,1,0,-1,1,0},
                    new int[]{0,1,2,0,2,3},new long[]{1,1})
                    .normals(new float[]{0,0,1,0,0,1,0,0,1,0,0,1}).build();
            renderer.apply(SceneTransaction.builder(2).upsert(MaterialAsset.builder(1).doubleSided(true).build())
                    .upsert(mesh).upsert(SceneInstance.builder(3,2).build()).build());
            render(renderer,access,lighting,2,true);
            var geometry=capture(handle,output,"real-mesh",2);
            int center=((SIZE/2)*SIZE+SIZE/2)*12;
            require(Math.abs(geometry.getFloat(center))<.001f&&Math.abs(geometry.getFloat(center+4))<.001f
                    &&geometry.getFloat(center+8)>.99f,"The real mesh did not produce fresh receiver normals");
            renderer.apply(SceneTransaction.builder(3).removeInstance(3).removeMesh(2).removeMaterial(1).build());
            render(renderer,access,lighting,3,false);empty(handle,output,"last-mesh-removed");
        }
        Files.write(output.resolve("cases.csv"),List.of("sequence,case,result","1,fresh-reset-empty,PASS",
                "2,first-real-mesh,PASS","3,remove-last-mesh-empty,PASS"),StandardCharsets.UTF_8);
        System.out.println("EMPTY_MANAGED_SCENE_NATIVE PASS firstResetEmpty=true freshPackets=true firstRealMesh=true finalMeshRemoved=true noBootstrapGeometry=true");
    }
    private static void render(Renderer renderer,RtxptAccess access,NativeLightingControl lighting,long sequence,boolean mesh) {
        lighting.publishLightingAttributes(new NativeLightingPublication(sequence,mesh
                ?List.of(NativeLightingPublication.Instance.receiver(3)):List.of(),List.of(),null,null));
        mark("submit-enter-"+sequence);
        renderer.submit(RenderFrameRequest.builder(sequence,SIZE,SIZE,CAMERA).minimumSceneRevision(sequence).build());
        mark("submit-exit-"+sequence);access.awaitIdle();mark("idle-exit-"+sequence);
        try(var lease=lighting.acquireLightingFrame().orElseThrow()) {
            var frame=lease.frame();
            mark("adapter-luid-"+Long.toUnsignedString(frame.adapterLuid()));
            require(frame.sequence()==sequence&&frame.sceneRevision()==sequence&&frame.attributeGeneration()==sequence
                    &&frame.width()==SIZE&&frame.height()==SIZE,"Empty scene returned a stale or mismatched packet");
        }
    }
    private static ByteBuffer capture(long handle,Path output,String name,int signal)throws Exception {
        Path path=output.resolve(name+"-signal-"+signal+".pfm");
        RtxptNative.nativeLightingCapture(handle,path.toString(),signal);byte[] data=Files.readAllBytes(path);int offset=0;
        for(int line=0;line<3;line++)while(offset<data.length&&data[offset++]!='\n'){}
        require(new String(data,0,offset,StandardCharsets.US_ASCII).equals("PF\n32 32\n-1.0\n")
                &&data.length-offset==SIZE*SIZE*12,"Missing native empty-scene output");
        return ByteBuffer.wrap(data,offset,data.length-offset).slice().order(ByteOrder.LITTLE_ENDIAN);
    }
    private static void empty(long handle,Path output,String name)throws Exception {
        var geometry=capture(handle,output,name,2);
        while(geometry.hasRemaining())require(geometry.getFloat()==0,"An empty scene retained receiver geometry");
        var direct=capture(handle,output,name,0);
        while(direct.hasRemaining())require(direct.getFloat()==1,"An empty scene retained occlusion or invalid direct gain");
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
