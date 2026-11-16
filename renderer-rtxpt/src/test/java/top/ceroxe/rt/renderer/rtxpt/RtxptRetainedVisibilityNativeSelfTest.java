package top.ceroxe.rt.renderer.rtxpt;

import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;

/** GPU oracle for hiding retained instances without structural publications. */
public final class RtxptRetainedVisibilityNativeSelfTest {
    private static final int COUNT=129;
    public static void main(String[] args)throws Exception {
        Path output=Path.of(args[0]);Files.createDirectories(output);
        var plan=new NativeLightingPlan(64,64,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.NORMAL_DISTANCE),0,6,0,NativeLightingStyle.defaults());
        try(var renderer=RtxptRenderers.openNativeLighting(RtxptRenderers.nativeLightingConfiguration(),plan)){
            var access=renderer.extension(RtxptAccess.class).orElseThrow();
            var lighting=renderer.extension(NativeLightingControl.class).orElseThrow();
            access.diagnosticLevel(RtxptDiagnostics.Level.DEEP);lighting.lightingReconstruction(NativeLightingReconstruction.NONE);
            var handleField=RtxptRenderer.class.getDeclaredField("handle");handleField.setAccessible(true);
            var camera=CameraState.lookAt(0,0,5,0,0,0).build();
            var tx=SceneTransaction.builder(1).resetScene().upsert(MaterialAsset.builder(1).doubleSided(true).build())
                .upsert(quad(1,0,0,1)).upsert(quad(2,.5f,.6f,.8f));
            for(int i=1;i<=COUNT;i++)tx.upsert(instance(i));
            renderer.apply(tx.build());
            byte[][] visible=null,hidden=null,empty=null;
            long revision=1;
            for(int sequence=1;sequence<=7;sequence++){
                if(sequence==3||sequence==6){var edit=SceneTransaction.builder(++revision);
                    for(int i=1;i<=COUNT;i++)if(sequence==6||i%2==0)edit.removeInstance(i);
                    renderer.apply(edit.build());}
                if(sequence==4||sequence==7){var edit=SceneTransaction.builder(++revision);
                    for(int i=1;i<=COUNT;i++)if(sequence==7||i%2==0)edit.upsert(instance(i));
                    renderer.apply(edit.build());}
                var roles=new ArrayList<NativeLightingPublication.Instance>();
                for(int i=1;i<=COUNT;i++){
                    if(sequence==6||(sequence==3&&i%2==0))continue;
                    boolean hide=sequence==5||sequence==2&&i%2==0;
                    roles.add(new NativeLightingPublication.Instance(i,hide?0:23,hide?0:1,0,1,1,0,0));
                }
                var monitor=sequence==1?null:access.nativeDiagnosticMonitor();if(monitor!=null)monitor.poll();
                lighting.publishLightingAttributes(new NativeLightingPublication(sequence,roles,List.of(),null,null));
                renderer.submit(RenderFrameRequest.builder(sequence,64,64,camera).minimumSceneRevision(revision).build());access.awaitIdle();
                try(var lease=lighting.acquireLightingFrame().orElseThrow()){
                    require(lease.frame().sequence()==sequence&&lease.frame().attributeGeneration()==sequence,"Stale role output");
                }
                var events=(monitor==null?access.nativeDiagnosticMonitor():monitor).poll();require(events.lostEvents()==0,"Lost role diagnostics");
                Files.write(output.resolve(sequence+"-events.csv"),events.events().stream().map(e->e.stage()+","+e.items()+","+e.flags()).toList());
                if(sequence==2||sequence==5){
                    require(events.events().stream().noneMatch(e->Set.of("Resources","SceneFullRefresh","BlasBuild").contains(e.stage())||e.stage().equals("TlasBuild")&&e.items()!=0),"Role change rebuilt native structure");
                }
                byte[][] images=new byte[2][];int[] planes={0,2};
                for(int p=0;p<2;p++){
                    Path image=output.resolve(sequence+"-"+planes[p]+".pfm");RtxptNative.nativeLightingCapture(handleField.getLong(renderer),image.toString(),planes[p]);images[p]=Files.readAllBytes(image);
                }
                if(sequence==1)visible=images;
                if(sequence==2)hidden=images;
                if(sequence==3)equal(hidden,images,"Hidden retained instance differs from removal");
                if(sequence==4||sequence==7)equal(visible,images,"Restored retained identity changed pixels");
                if(sequence==5)empty=images;
                if(sequence==6)equal(empty,images,"All-hidden scene differs from empty native scene");
                System.out.println("RETAINED_VISIBILITY sequence="+sequence+" PASS");
            }
        }
        System.out.println("RETAINED_VISIBILITY_NATIVE PASS frames=7 instances=129 exactPixels=true zeroTopologyRoleChanges=true allHidden=true restored=true");
    }
    private static SceneInstance instance(int i){return SceneInstance.builder(i,i==2?2:1).transform(new AffineTransform(new float[]{1,0,0,i<=2?0:1000+i*5,0,1,0,0,0,0,1,0})).build();}
    private static MeshAsset quad(long id,float z,float ny,float nz){return MeshAsset.builder(id,new float[]{-2,-2,z,2,-2,z,2,2,z,-2,2,z},new int[]{0,1,2,2,3,0},new long[]{1,1}).normals(new float[]{0,ny,nz,0,ny,nz,0,ny,nz,0,ny,nz}).build();}
    private static void equal(byte[][] a,byte[][] b,String message){for(int i=0;i<a.length;i++)require(Arrays.equals(a[i],b[i]),message+" plane="+i);}
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}