package top.ceroxe.rt.renderer.rtxpt;

import java.nio.*;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;

/** Page rollover and compatible writes while prior graphics work is queued. */
public final class RtxptDirectGeometryQueueNativeSelfTest {
    private static final int SIZE=128;
    private static final CameraState CAMERA=CameraState.lookAt(0,0,4,0,0,0).aspectRatio(1).build();
    private static MeshAsset mesh(long id,int count,float x,float tilt,boolean large){
        float[] positions=new float[count*3],normals=new float[count*3];
        float z=(float)Math.sqrt(1-tilt*tilt);
        for(int i=0;i<count;i++){positions[i*3]=100;normals[i*3+1]=tilt;normals[i*3+2]=z;}
        float[] quad={x-.4f,-.6f,0,x+.4f,-.6f,0,x+.4f,.6f,0,x-.4f,.6f,0};
        System.arraycopy(quad,0,positions,0,quad.length);
        int[] indices=new int[large?32766:6];long[] materials=new long[indices.length/3];Arrays.fill(materials,1);
        Arrays.fill(indices,4);int[] first={0,1,2,0,2,3};System.arraycopy(first,0,indices,0,6);
        return MeshAsset.builder(id,positions,indices,materials).normals(normals).build();
    }
    public static void main(String[] args)throws Exception{
        Path output=Path.of(args[0]);Files.createDirectories(output);
        var plan=new NativeLightingPlan(SIZE,SIZE,Set.of(NativeLightingPlan.Signal.NORMAL_DISTANCE,
                NativeLightingPlan.Signal.DIRECT_GAIN),0,6,0,NativeLightingStyle.defaults());
        try(var renderer=RtxptRenderers.openNativeLighting(RtxptRenderers.nativeLightingConfiguration(),plan)){
            var access=renderer.extension(RtxptAccess.class).orElseThrow();
            var lighting=renderer.extension(NativeLightingControl.class).orElseThrow();
            access.diagnosticLevel(RtxptDiagnostics.Level.DEEP);lighting.lightingReconstruction(NativeLightingReconstruction.NONE);
            var field=RtxptRenderer.class.getDeclaredField("handle");field.setAccessible(true);
            RtxptDiagnostics.NativeMonitor memory=null;
            long preparedPageHits=0;
            Set<Double> pages=new HashSet<>();Set<Long> indexStarts=new HashSet<>();
            double[] neighbour=null;long[] neighbourRanges=null;long priorStart=-1;int previousCount=0;
            for(int sequence=0;sequence<28;sequence++){
                int count=12000+(sequence/2%2)*4;float tilt=sequence%2==0?-.45f:.45f;
                var tx=SceneTransaction.builder(sequence).upsert(mesh(2,count,.65f,tilt,true));
                if(sequence==0)tx.resetScene().upsert(MaterialAsset.builder(1).doubleSided(true).build())
                        .upsert(mesh(1,5,-.65f,0,false)).upsert(SceneInstance.builder(11,1).build())
                        .upsert(SceneInstance.builder(22,2).build());
                renderer.apply(tx.build());
                lighting.publishLightingAttributes(new NativeLightingPublication(sequence+1,List.of(
                        NativeLightingPublication.Instance.receiver(11),NativeLightingPublication.Instance.receiver(22)),List.of(),null,null));
                renderer.submit(RenderFrameRequest.builder(sequence,SIZE,SIZE,CAMERA).minimumSceneRevision(sequence).build());
                long handle=field.getLong(renderer);
                // Owner observation waits for CPU recording, never device idle.
                double[] current=RtxptNative.meshResourceState(handle,"i22");
                long[] ranges=RtxptNative.meshStorageRanges(handle,"i22");pages.add(current[1]);indexStarts.add(ranges[2]);
                require(ranges[1]-ranges[0]==count*12L,"Wrong vertex slice size");
                if(count==previousCount)require(ranges[0]==priorStart,"Compatible edit moved its vertex range");
                if(neighbour==null){neighbour=RtxptNative.meshResourceState(handle,"i11");neighbourRanges=RtxptNative.meshStorageRanges(handle,"i11");}
                else require(Arrays.equals(neighbour,RtxptNative.meshResourceState(handle,"i11"))
                        &&Arrays.equals(neighbourRanges,RtxptNative.meshStorageRanges(handle,"i11")),"Page rollover moved a live neighbour");
                try(var lease=lighting.acquireLightingFrame().orElseThrow()){
                    require(lease.frame().sequence()==sequence,"Queued frame identity was stale");
                }
                if(memory==null)memory=RtxptDiagnostics.nativeMonitor(handle);
                var observed=memory.poll();
                require(observed.lostEvents()==0,"Lost native allocation evidence");
                require(observed.events().stream().noneMatch(event->event.stage().equals("BufferAllocate")),
                        "Queued geometry allocated a committed page on the frame path");
                preparedPageHits+=observed.events().stream().filter(event->event.stage().equals("PoolAcquire")
                        &&event.bytes()>0&&(event.flags()&4)!=0).count();
                previousCount=count;priorStart=ranges[0];
            }
            require(preparedPageHits>=3,"Fixture did not exercise the prebuilt page path");
            require(pages.size()>=3&&indexStarts.size()>=2,"Fixture did not cross native page boundaries");
            access.awaitIdle();long handle=field.getLong(renderer);
            Path capture=output.resolve("final-normal.pfm");RtxptNative.nativeLightingCapture(handle,capture.toString(),2);
            byte[] bytes=Files.readAllBytes(capture);int offset=0;
            for(int line=0;line<3;line++)while(bytes[offset++]!='\n'){}
            ByteBuffer data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            for(int side=0;side<2;side++){
                double worldX=side==0?-.65:.65;int x=(int)Math.round((.5+worldX/(8*CAMERA.tanHalfFovY()))*SIZE-.5);
                float tilt=side==0?0:.45f;float[] expected={0,tilt,(float)Math.sqrt(1-tilt*tilt)};
                for(int y=SIZE/2-1;y<=SIZE/2+1;y++)for(int dx=-1;dx<=1;dx++)for(int c=0;c<3;c++){
                    float value=data.getFloat(offset+((SIZE-1-y)*SIZE+x+dx)*12+c*4);
                    require(Float.isFinite(value)&&Math.abs(value-expected[c])<.025,"Queued edit or page rollover corrupted final pixels");
                }
            }
            System.out.println("DIRECT_GEOMETRY_QUEUE PASS frames=28 perFrameAwaitIdle=false vertexPages="+pages.size()
                    +" preparedPageHits="+preparedPageHits+" indexOffsets="+indexStarts.size()+" neighbourStable=true finalPixels=true forcedIncompleteReader=false");
        }
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
