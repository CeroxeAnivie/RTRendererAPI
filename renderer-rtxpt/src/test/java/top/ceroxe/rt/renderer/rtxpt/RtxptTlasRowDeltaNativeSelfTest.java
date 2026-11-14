package top.ceroxe.rt.renderer.rtxpt;

import java.nio.*;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;

/** Pose and stable-address geometry edits stay local; actual reindexing stays complete. */
public final class RtxptTlasRowDeltaNativeSelfTest {
    private static AffineTransform pose(float x){return new AffineTransform(new float[]{1,0,0,x,0,1,0,0,0,0,1,0});}
    private static MeshAsset mesh(float z){return MeshAsset.builder(1,new float[]{-1,-1,z,1,-1,z,1,1,z,-1,1,z},new int[]{0,1,2,0,2,3},new long[]{1,1})
            .normals(new float[]{0,0,1,0,0,1,0,0,1,0,0,1}).build();}
    public static void main(String[] args)throws Exception{
        Path output=Files.createDirectories(Path.of(args.length==0?"build/reports/tlas-row-delta":args[0]));
        var plan=new NativeLightingPlan(64,64,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.NORMAL_DISTANCE),0,6,0,NativeLightingStyle.defaults());
        var rows=new ArrayList<String>();rows.add("sequence,case,translated_rows,dirty_rows,pixel_normal_z");
        try(var renderer=RtxptRenderers.openNativeLighting(plan)){
            var access=renderer.extension(RtxptAccess.class).orElseThrow();var lighting=renderer.extension(NativeLightingControl.class).orElseThrow();
            access.diagnosticLevel(RtxptDiagnostics.Level.DEEP);lighting.lightingReconstruction(NativeLightingReconstruction.NONE);
            RtxptDiagnostics.NativeMonitor monitor=null;var field=RtxptRenderer.class.getDeclaredField("handle");field.setAccessible(true);
            for(int sequence=1;sequence<=6;sequence++){
                var tx=SceneTransaction.builder(sequence);String name;
                if(sequence==1){tx.resetScene().upsert(MaterialAsset.builder(1).doubleSided(true).build()).upsert(mesh(0));
                    for(long id=1;id<=64;id++)tx.upsert(SceneInstance.builder(id,1).transform(pose(id==1?0:10)).build());name="bootstrap";
                }else if(sequence==2||sequence==3){tx.upsert(SceneInstance.builder(1,1).transform(pose(sequence==2?4:0)).build());name="one-pose";}
                else if(sequence==4){tx.removeInstance(2).upsert(SceneInstance.builder(65,1).transform(pose(10)).build());name="dense-reindex";}
                else{tx.upsert(mesh(sequence==5?.1f:.2f));name=sequence==5?"live-blas-rebuild":"in-place-blas-rebuild";}
                renderer.apply(tx.build());renderer.submit(RenderFrameRequest.builder(sequence,64,64,CameraState.lookAt(0,0,3,0,0,0).build()).minimumSceneRevision(sequence).build());access.awaitIdle();
                try(var lease=lighting.acquireLightingFrame().orElseThrow()){require(lease.frame().sequence()==sequence,"Stale TLAS output");}
                if(monitor==null)monitor=access.nativeDiagnosticMonitor();var snapshot=monitor.poll();require(snapshot.lostEvents()==0,"Missing TLAS diagnostics");
                long translated=0,dirty=0;
                if(sequence>1){var tlas=snapshot.events().stream().filter(e->e.stage().equals("TlasBuild")&&e.items()==1).toList();
                require(tlas.size()==1,"Required current TLAS was not built");
                var packs=snapshot.events().stream().filter(e->e.stage().equals("PayloadPack")&&e.parentScopeId()==tlas.getFirst().scopeId()).toList();
                require(packs.size()==1,"Direct TLAS translation was not recorded");var pack=packs.getFirst();
                require(pack.tasks()==(sequence==2||sequence==3?1:sequence==6?0:64),"Unexpected TLAS rows examined at "+sequence+": "+pack.tasks());
                translated=pack.tasks();dirty=pack.items();}
                long handle=field.getLong(renderer);Path file=output.resolve(name+"-"+sequence+".pfm");RtxptNative.nativeLightingCapture(handle,file.toAbsolutePath().toString(),2);
                byte[] bytes=Files.readAllBytes(file);int start=0;for(int line=0;line<3;line++)while(bytes[start++]!='\n'){}
                float z=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getFloat(start+((64-1-32)*64+32)*12+8);
                require(sequence==2?z==0:Math.abs(z-1)<.001,"Current pose/geometry was not visible at "+sequence);
                rows.add(sequence+","+name+","+translated+","+dirty+","+z);
            }
        }finally{Files.write(output.resolve("cases.csv"),rows);}
        System.out.println("TLAS_ROW_DELTA_NATIVE PASS poseRows=1/64 denseReindex=true inPlaceBlasRows=0/64 liveBlasRebuild=true freshPixels=true");
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
