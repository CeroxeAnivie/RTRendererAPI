package top.ceroxe.rt.renderer.rtxpt;

import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;

/** A thousand live BLAS ranges, partial retirement and fresh native pixels. */
public final class RtxptPagedBlasNativeSelfTest {
    private static final int COUNT=1024,SIZE=128;
    private static final CameraState CAMERA=CameraState.lookAt(0,0,4,0,0,0).aspectRatio(1).build();
    private static MeshAsset mesh(long id,int first,int count,boolean alternating){
        var positions=new float[count*12];var normals=new float[count*12];
        var indices=new int[count*6];var materials=new long[count*2];Arrays.fill(materials,1);
        for(int n=0;n<count;n++){
            int tile=first+(alternating?2*n:n),x=tile%32,y=tile/32;
            float left=-2+x*.125f,bottom=-2+y*.125f;
            float tilt=(tile%7-3)*.08f,z=(float)Math.sqrt(1-tilt*tilt);
            float[] p={left,bottom,0,left+.125f,bottom,0,left+.125f,bottom+.125f,0,left,bottom+.125f,0};
            System.arraycopy(p,0,positions,n*12,12);
            for(int v=0;v<4;v++){normals[n*12+v*3+1]=tilt;normals[n*12+v*3+2]=z;}
            int at=n*6,base=n*4;int[] local={0,1,2,0,2,3};for(int k=0;k<6;k++)indices[at+k]=base+local[k];
        }
        return MeshAsset.builder(id,positions,indices,materials).normals(normals).build();
    }
    private static SceneTransaction.Builder initial(long revision){
        return SceneTransaction.builder(revision).resetScene().upsert(MaterialAsset.builder(1).doubleSided(true).build());
    }
    private static byte[] reference(Path output,boolean half)throws Exception{
        try(var f=new Fixture(output)){
            f.renderer.apply(initial(1).upsert(mesh(2,half?1:0,half?COUNT/2:COUNT,half)).upsert(SceneInstance.builder(2,2).build()).build());
            return f.frame(1,half?"reference-half":"reference-full");
        }
    }
    public static void main(String[] args)throws Exception{
        Path output=Files.createDirectories(Path.of(args.length==0?"build/reports/paged-blas":args[0]));
        byte[] full=reference(output,false),half=reference(output,true);
        var rows=new ArrayList<String>();rows.add("case,logical_ranges,committed_buffers,blas_builds,fresh_pixel_match");
        try(var f=new Fixture(output)){
            f.renderer.apply(initial(1).upsert(MeshAsset.builder(1,new float[]{10,0,0,11,0,0,10,1,0},new int[]{0,1,2},new long[]{1}).build())
                    .upsert(SceneInstance.builder(1,1).build()).build());f.frame(1,"bootstrap");
            for(int batch=0;batch<4;batch++){
                long revision=batch+2;var tx=SceneTransaction.builder(revision);
                for(int tile=batch*256;tile<(batch+1)*256;tile++){long id=1000L+tile;tx.upsert(mesh(id,tile,1,false)).upsert(SceneInstance.builder(id,id).build());}
                f.renderer.apply(tx.build());byte[] pixels=f.frame(revision,"batch-"+batch);
                require(f.builds==256,"Streaming batch did not build all current BLASes");
                require(f.committed<16,"Streaming retained one committed allocation per BLAS");
                if(batch==3)require(Arrays.equals(full,pixels),"Paged geometry differs from fresh merged native pixels");
                rows.add("batch-"+batch+","+f.ranges+","+f.committed+","+f.builds+","+(batch==3));
            }
            var addresses=new HashSet<Double>();for(int tile=0;tile<COUNT;tile++)
                require(addresses.add(RtxptNative.meshResourceState(f.handle,"i"+(1000+tile))[0]),"Live BLAS ranges overlap");
            double survivor=RtxptNative.meshResourceState(f.handle,"i1001")[0];
            var remove=SceneTransaction.builder(6);for(int tile=0;tile<COUNT;tile+=2)remove.removeInstance(1000+tile).removeMesh(1000+tile);
            f.renderer.apply(remove.build());require(Arrays.equals(half,f.frame(6,"half-retired")),"Retirement changed surviving geometry pixels");
            require(RtxptNative.meshResourceState(f.handle,"i1001")[0]==survivor,"Partial retirement replaced a surviving BLAS");
            var restore=SceneTransaction.builder(7);for(int tile=COUNT-2;tile>=0;tile-=2){long id=1000L+tile;restore.upsert(mesh(id,tile,1,false)).upsert(SceneInstance.builder(id,id).build());}
            f.renderer.apply(restore.build());require(Arrays.equals(full,f.frame(7,"restored-reverse")),"Reused ranges changed fresh pixels");
            require(f.committed==0,"Retired page capacity was not reused");
            rows.add("restored-reverse,"+f.ranges+","+f.committed+","+f.builds+",true");
            require(RtxptNative.meshResourceState(f.handle,"i1001")[0]==survivor,"Range reuse overwrote a live neighbor");
        }finally{Files.write(output.resolve("cases.csv"),rows);}
        System.out.println("PAGED_BLAS_NATIVE PASS live=1024 uniqueAddresses=true partialRetirement=true reverseReuse=true freshPixels=true");
    }
    private static final class Fixture implements AutoCloseable{
        final Renderer renderer;final RtxptAccess access;final NativeLightingControl lighting;final Path output;
        RtxptDiagnostics.NativeMonitor monitor;long handle,ranges,committed,builds;
        Fixture(Path output){
            this.output=output;var plan=new NativeLightingPlan(SIZE,SIZE,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.NORMAL_DISTANCE),0,6,0,NativeLightingStyle.defaults());
            renderer=RtxptRenderers.openNativeLighting(plan);access=renderer.extension(RtxptAccess.class).orElseThrow();
            lighting=renderer.extension(NativeLightingControl.class).orElseThrow();lighting.lightingReconstruction(NativeLightingReconstruction.NONE);
            access.diagnosticLevel(RtxptDiagnostics.Level.DEEP);
        }
        byte[] frame(long revision,String name)throws Exception{
            renderer.submit(RenderFrameRequest.builder(revision,SIZE,SIZE,CAMERA).minimumSceneRevision(revision).build());access.awaitIdle();
            try(var lease=lighting.acquireLightingFrame().orElseThrow()){require(lease.frame().sequence()==revision,"Stale pressure frame");}
            if(monitor==null)monitor=access.nativeDiagnosticMonitor();var snapshot=monitor.poll();
            require(snapshot.lostEvents()==0&&snapshot.events().stream().noneMatch(e->(e.flags()&(2L|512L|1024L))!=0),"Incomplete pressure diagnostics");
            ranges=snapshot.events().stream().filter(e->e.stage().equals("BlasAllocate")).count();
            committed=snapshot.events().stream().filter(e->e.stage().equals("DefaultBufferCommitted")).count();
            builds=snapshot.events().stream().filter(e->e.stage().equals("BlasBuild")).count();
            var field=RtxptRenderer.class.getDeclaredField("handle");field.setAccessible(true);handle=field.getLong(renderer);
            Path file=output.resolve(name+".pfm");RtxptNative.nativeLightingCapture(handle,file.toAbsolutePath().toString(),2);return Files.readAllBytes(file);
        }
        @Override public void close(){renderer.close();}
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
