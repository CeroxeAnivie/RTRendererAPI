package top.ceroxe.rt.renderer.rtxpt;

import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;

/** Differential GPU oracle for canonical missing-normal input and the old expanded representation. */
public final class RtxptFlatNormalsNativeSelfTest {
    private record Input(String name,float[] positions,int[] indices,float[] uv) { }
    private static final float[] QUAD={-1,-1,0,1,-1,0,1,1,0,-1,1,0};
    private static final int[] INDICES={0,1,2,0,2,3};
    public static void main(String[] args)throws Exception {
        Path output=Path.of(args[0]);Files.createDirectories(output);
        var fixtures=List.of(new Input("planar",QUAD,INDICES,new float[0]),
                new Input("sharp",new float[]{-1,-1,0,1,-1,0,1,1,0,-1,1,.8f},INDICES,new float[0]),
                new Input("uv-corner-identity",QUAD,INDICES,new float[]{0,0,1,0,1,1,0,1}),
                new Input("unused-vertex",new float[]{-1,-1,0,1,-1,0,1,1,0,-1,1,0,99,99,99},INDICES,new float[0]),
                new Input("degenerate",QUAD,new int[]{0,1,1,0,2,3},new float[0]),
                new Input("reverse",QUAD,new int[]{0,2,1,0,3,2},new float[0]));
        var plan=new NativeLightingPlan(64,64,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.NORMAL_DISTANCE),0,6,0,NativeLightingStyle.defaults());
        int images=0;
        try(var renderer=RtxptRenderers.openNativeLighting(RtxptRenderers.nativeLightingConfiguration(),plan)){
            var access=renderer.extension(RtxptAccess.class).orElseThrow();
            var lighting=renderer.extension(NativeLightingControl.class).orElseThrow();
            lighting.lightingReconstruction(NativeLightingReconstruction.NONE);
            var field=RtxptRenderer.class.getDeclaredField("handle");field.setAccessible(true);long handle=field.getLong(renderer);
            long sequence=0;
            for(var input:fixtures){
                byte[][] expected=null;
                for(boolean explicit:new boolean[]{true,false}){
                    sequence++;
                    renderer.apply(SceneTransaction.builder(sequence).resetScene().upsert(MaterialAsset.builder(1).doubleSided(true).build())
                            .upsert(mesh(input,explicit)).upsert(SceneInstance.builder(11,1).build()).build());
                    lighting.publishLightingAttributes(new NativeLightingPublication(sequence,List.of(NativeLightingPublication.Instance.receiver(11)),List.of(),null,null));
                    renderer.submit(RenderFrameRequest.builder(sequence,64,64,CameraState.lookAt(0,0,3,0,0,0).build()).minimumSceneRevision(sequence).build());access.awaitIdle();
                    try(var frame=lighting.acquireLightingFrame().orElseThrow()){
                        require(frame.frame().sequence()==sequence&&frame.frame().attributeGeneration()==sequence,"Stale flat normal output");
                    }
                    byte[][] actual=new byte[2][];
                    int[] planes={0,2};
                    for(int p=0;p<planes.length;p++){
                        handle=field.getLong(renderer);
                        Path capture=output.resolve(input.name()+"-"+(explicit?"expanded":"canonical")+"-"+planes[p]+".pfm");
                        RtxptNative.nativeLightingCapture(handle,capture.toString(),planes[p]);actual[p]=Files.readAllBytes(capture);
                        if(expected!=null)require(Arrays.equals(expected[p],actual[p]),"Flat normal output differs: "+input.name()+" plane="+planes[p]);
                        images++;
                    }
                    if(explicit)expected=actual;
                    else if(input.name().equals("planar")){
                        long[] retained=RtxptNative.meshInputRetentionState(handle);
                        require(retained[0]==4L*3*4+4L*4+6L*4,"Planar indexed input did not retain exact packed normal storage: "+retained[0]);
                    }
                }
                System.out.println("FLAT_NORMALS case="+input.name()+" exactGpuPlanes=2 PASS");
            }
            sequence++;
            renderer.apply(SceneTransaction.builder(sequence).resetScene().build());
            lighting.publishLightingAttributes(new NativeLightingPublication(sequence,List.of(),List.of(),null,null));
            renderer.submit(RenderFrameRequest.builder(sequence,64,64,CameraState.lookAt(0,0,3,0,0,0).build()).minimumSceneRevision(sequence).build());access.awaitIdle();
            require(RtxptNative.meshInputRetentionState(handle)[0]==0,"Reset retained generated normals or positions");
        }
        System.out.println("FLAT_NORMALS_NATIVE PASS images="+images+" exactPixels=true sharpFallback=true uvIdentity=true unusedBounds=true degenerate=true resetRelease=true");
    }
    private static MeshAsset mesh(Input input,boolean expanded){
        float[] p=input.positions(),uv=input.uv(),normals=new float[0];int[] indices=input.indices();
        if(expanded){
            p=new float[indices.length*3];normals=new float[p.length];uv=new float[input.uv().length==0?0:indices.length*2];
            for(int first=0;first<indices.length;first+=3){
                int a=indices[first]*3,b=indices[first+1]*3,c=indices[first+2]*3;var v=input.positions();
                double ux=(double)v[b]-v[a],uy=(double)v[b+1]-v[a+1],uz=(double)v[b+2]-v[a+2];
                double vx=(double)v[c]-v[a],vy=(double)v[c+1]-v[a+1],vz=(double)v[c+2]-v[a+2];
                double nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx,length=Math.sqrt(nx*nx+ny*ny+nz*nz);
                if(length==0){nx=0;ny=0;nz=1;length=1;}
                for(int corner=first;corner<first+3;corner++){
                    System.arraycopy(v,indices[corner]*3,p,corner*3,3);
                    normals[corner*3]=(float)(nx/length);normals[corner*3+1]=(float)(ny/length);normals[corner*3+2]=(float)(nz/length);
                    if(uv.length!=0)System.arraycopy(input.uv(),indices[corner]*2,uv,corner*2,2);
                }
            }
            indices=new int[indices.length];for(int i=0;i<indices.length;i++)indices[i]=i;
        }
        long[] materials=new long[indices.length/3];Arrays.fill(materials,1);
        return MeshAsset.builder(1,p,indices,materials).normals(normals).textureCoordinates(uv).build();
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
