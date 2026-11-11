package top.ceroxe.rt.renderer.rtxpt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import top.ceroxe.rt.renderer.api.*;

/** Completed native BLAS storage accepts smaller full builds, never oversized inputs. */
public final class RtxptBlasCapacityNativeSelfTest {
    private record Result(double address, long allocations, long builds, byte[] pixels) {}

    public static void main(String[] args) throws Exception {
        Path output=Path.of(args.length==0?"build/reports/blas-capacity-contract":args[0]).toAbsolutePath();
        Files.createDirectories(output);
        var rows=new ArrayList<String>();
        rows.add("case,blas_allocations,blas_builds,reused_address,equals_fresh_pixels");
        byte[] smallReference,largeReference,oversizeReference;
        try(var reference=new Fixture(output)) {
            smallReference=reference.step(initial(1,16),1,100,"fresh-small").pixels();
        }
        try(var reference=new Fixture(output)) {
            largeReference=reference.step(initial(1,32),1,100,"fresh-large").pixels();
        }
        try(var reference=new Fixture(output)) {
            oversizeReference=reference.step(initial(1,64),1,100,"fresh-oversize").pixels();
        }
        try(var fixture=new Fixture(output)) {
            // Startup uses the original compacting pool. The candidate applies
            // to ordinary streaming allocations created after that bootstrap.
            fixture.step(initial(1,0),1,-1,"bootstrap");
            Result first=fixture.step(add(2,32),2,100,"initial-large");
            require(first.address()!=0,"Initial BLAS has no device address");
            require(Arrays.equals(first.pixels(),largeReference),"Initial native construction differs from fresh reference");
            for(int cycle=0;cycle<4;cycle++) {
                int remove=3+cycle*4;
                fixture.step(SceneTransaction.builder(remove).removeInstance(100).removeMesh(100).build(),remove,-1,"removed-"+remove);
                Result small=fixture.step(add(remove+1,16),remove+1,100,"small-"+cycle);
                require(small.allocations()==0&&small.builds()==1,"Smaller full build did not reuse retired native capacity: "+small);
                require(small.address()==first.address(),"Smaller full build replaced the retired allocation");
                require(Arrays.equals(small.pixels(),smallReference),"Reused smaller BLAS differs from fresh native pixels");
                rows.add("small-"+cycle+",0,1,true,true");
                fixture.step(SceneTransaction.builder(remove+2).removeInstance(100).removeMesh(100).build(),remove+2,-1,"removed-"+(remove+2));
                Result large=fixture.step(add(remove+3,32),remove+3,100,"large-"+cycle);
                require(large.allocations()==0&&large.builds()==1&&large.address()==first.address(),
                        "A smaller rebuild lost the allocation's original capacity");
                require(Arrays.equals(large.pixels(),largeReference),"Regrown BLAS differs from fresh native pixels");
                rows.add("large-"+cycle+",0,1,true,true");
            }
            fixture.step(SceneTransaction.builder(19).removeInstance(100).removeMesh(100).build(),19,-1,"removed-before-growth");
            Result grown=fixture.step(add(20,64),20,100,"oversize");
            require(grown.allocations()==1&&grown.builds()==1&&grown.address()!=first.address(),
                    "Oversized input reused insufficient native capacity");
            require(Arrays.equals(grown.pixels(),oversizeReference),"Oversized fresh BLAS differs from reference pixels");
            rows.add("oversize,1,1,false,true");
            Result liveSmall=fixture.step(add(21,16),21,100,"live-shrink");
            require(liveSmall.allocations()==0&&liveSmall.builds()==1&&liveSmall.address()==grown.address(),
                    "Live shrink replaced compatible native capacity");
            require(Arrays.equals(liveSmall.pixels(),smallReference),"Live smaller rebuild differs from fresh pixels");
            rows.add("live-shrink,0,1,true,true");
            Result liveRegrown=fixture.step(add(22,64),22,100,"live-regrowth");
            require(liveRegrown.allocations()==0&&liveRegrown.builds()==1&&liveRegrown.address()==grown.address(),
                    "Live regrowth lost the original allocation capacity");
            require(Arrays.equals(liveRegrown.pixels(),oversizeReference),"Live regrowth differs from fresh pixels");
            rows.add("live-regrowth,0,1,true,true");
        }
        try(var fixture=new Fixture(output)) {
            fixture.step(initial(1,0),1,-1,"pressure-bootstrap");
            fixture.step(pressure(2,false),2,-1,"pressure-small-live");
            fixture.step(pressure(3,true),3,-1,"pressure-small-retired");
            Result large=fixture.step(add(4,64),4,100,"pressure-large");
            require(large.allocations()==1&&large.builds()==1,"Small cached allocations incorrectly fit larger geometry");
            fixture.step(SceneTransaction.builder(5).removeInstance(100).removeMesh(100).build(),5,-1,"pressure-large-retired");
            Result reused=fixture.step(add(6,64),6,100,"pressure-large-returned");
            require(reused.allocations()==0&&reused.builds()==1&&reused.address()==large.address(),
                    "Full cache rejected the recently retired allocation: "+reused);
            require(Arrays.equals(reused.pixels(),oversizeReference),"Cache eviction changed fresh native pixels");
            rows.add("full-cache-recent-reuse,0,1,true,true");
        } finally { Files.write(output.resolve("cases.csv"),rows); }
        System.out.println("BLAS_CAPACITY PASS smallerRebuilds=4 regrowths=4 zeroReuseAllocations=true oversizeRejected=true liveShrinkAndRegrowth=true freshPixels=true fullCacheRecentReuse=true");
    }

    private static SceneTransaction pressure(long revision,boolean remove) {
        var tx=SceneTransaction.builder(revision);
        for(long id=1000;id<2024;id++) {
            if(remove)tx.removeInstance(id).removeMesh(id);
            else tx.upsert(grid(id,1,5)).upsert(SceneInstance.builder(id,id).build());
        }
        return tx.build();
    }

    private static SceneTransaction initial(long revision,int cells) {
        var tx=SceneTransaction.builder(revision).resetScene().upsert(MaterialAsset.builder(1).doubleSided(true).build())
                .upsert(grid(1,1,5)).upsert(SceneInstance.builder(1,1).build());
        if(cells>0)tx.upsert(grid(100,cells,0)).upsert(SceneInstance.builder(100,100).build());
        return tx.build();
    }
    private static SceneTransaction add(long revision,int cells) {
        return SceneTransaction.builder(revision).upsert(grid(100,cells,0))
                .upsert(SceneInstance.builder(100,100).build()).build();
    }
    private static MeshAsset grid(long id,int cells,float offset) {
        int side=cells+1;float[] positions=new float[side*side*3],normals=new float[positions.length];
        int[] indices=new int[cells*cells*6];long[] materials=new long[cells*cells*2];
        for(int y=0;y<side;y++)for(int x=0;x<side;x++) {
            int v=(y*side+x)*3;positions[v]=offset-1+2f*x/cells;positions[v+1]=-1+2f*y/cells;normals[v+2]=1;
        }
        int at=0;
        for(int y=0;y<cells;y++)for(int x=0;x<cells;x++) {
            int v=y*side+x;indices[at++]=v;indices[at++]=v+1;indices[at++]=v+side+1;
            indices[at++]=v;indices[at++]=v+side+1;indices[at++]=v+side;
        }
        Arrays.fill(materials,1);
        return MeshAsset.builder(id,positions,indices,materials).normals(normals).build();
    }
    private static final class Fixture implements AutoCloseable {
        final Renderer renderer;
        final RtxptAccess access;
        final NativeLightingControl lighting;
        final Path output;
        RtxptDiagnostics.NativeMonitor monitor;
        Fixture(Path output) {
            this.output=output;
            var plan=new NativeLightingPlan(64,64,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.NORMAL_DISTANCE),
                    0,6,0,NativeLightingStyle.defaults());
            renderer=RtxptRenderers.openNativeLighting(RtxptRenderers.nativeLightingConfiguration(),plan);
            access=renderer.extension(RtxptAccess.class).orElseThrow();
            lighting=renderer.extension(NativeLightingControl.class).orElseThrow();
            access.diagnosticLevel(RtxptDiagnostics.Level.DEEP);
            lighting.lightingReconstruction(NativeLightingReconstruction.NONE);
        }
        Result step(SceneTransaction tx,long sequence,long target,String name) throws Exception {
            renderer.apply(tx);
            var receivers=new ArrayList<NativeLightingPublication.Instance>();
            receivers.add(NativeLightingPublication.Instance.receiver(1));
            if(target>=0)receivers.add(NativeLightingPublication.Instance.receiver(target));
            lighting.publishLightingAttributes(new NativeLightingPublication(sequence,receivers,List.of(),null,null));
            renderer.submit(RenderFrameRequest.builder(sequence,64,64,CameraState.lookAt(0,0,3,0,0,0).build())
                    .minimumSceneRevision(sequence).build());
            access.awaitIdle();
            try(var lease=lighting.acquireLightingFrame().orElseThrow()) {
                require(lease.frame().sequence()==sequence&&lease.frame().sceneRevision()==sequence,"Stale capacity-test output");
            }
            if(monitor==null)monitor=access.nativeDiagnosticMonitor();
            var snapshot=monitor.poll();
            if(name.equals("pressure-small-live")) {
                long threads=snapshot.events().stream().filter(e->e.stage().equals("BlasAllocate"))
                        .map(e->e.thread()).distinct().count();
                require(threads>1,"Streaming batch still serializes all native BLAS allocations");
            }
            require(snapshot.lostEvents()==0&&snapshot.events().stream().noneMatch(e->(e.flags()&(2L|512L|1024L))!=0),
                    "Incomplete native diagnostics");
            var field=RtxptRenderer.class.getDeclaredField("handle");field.setAccessible(true);long handle=field.getLong(renderer);
            double address=target<0?0:RtxptNative.meshResourceState(handle,"i"+target)[0];
            Path image=output.resolve(name+".pfm");RtxptNative.nativeLightingCapture(handle,image.toString(),2);
            return new Result(address,snapshot.events().stream().filter(e->e.stage().equals("BlasAllocate")).count(),
                    snapshot.events().stream().filter(e->e.stage().equals("BlasBuild")).count(),Files.readAllBytes(image));
        }
        @Override public void close(){renderer.close();}
    }
    private static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
}
