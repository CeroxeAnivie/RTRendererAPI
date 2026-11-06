package top.ceroxe.rt.renderer.rtxpt;

import java.nio.LongBuffer;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;
import static top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingMatrixNativeSelfTest.*;

/** Real placed resources, resumable retirement, bounded caches and material dependencies. */
public final class RtxptResourceArenaNativeSelfTest {
    static final Path OUTPUT=Path.of(System.getProperty("resourceMatrix.output","build/reports/resource-latency"));
    static long sequence,revision=1;
    static MeshAsset deformedMulti(long id,long... materials){
        var original=RtxptTopologyPreparationNativeSelfTest.multi(id,materials);
        var positions=new float[original.positions().remaining()];original.positions().get(positions);
        for(int i=0;i<positions.length;i+=3)positions[i]+=.01f;
        var indices=new int[original.triangleIndices().remaining()];original.triangleIndices().get(indices);
        return MeshAsset.builder(id,positions,indices,materials).build();
    }
    static long submit(RtxptRenderer r)throws Exception {
        var request=frame(sequence,revision);long start=System.nanoTime(),deferrals=0;
        var before=r.retainedUpdateTimings();
        while(r.trySubmit(request) instanceof Renderer.FrameSubmissionDeferred){
            deferrals++;require(System.nanoTime()-start<30_000_000_000L,"Identical immutable request eventually admitted");Thread.sleep(1);
        }
        require(r.retainedUpdateTimings().resourceBatches()-before.resourceBatches()<=1,"Deferred retries commit the transaction at most once");
        try(var lease=r.acquireLightingFrame().orElseThrow()){
            require(lease.frame().sequence()==sequence&&lease.frame().sceneRevision()==revision,"Only accepted request identity is published");
        }
        sequence++;return deferrals;
    }
    static void drain(RtxptRenderer r)throws Exception {
        // Completion alone does not run all cursor slices. Continue ordinary
        // owner observations, with no scene edits, until all retirement work drains.
        long deadline=System.nanoTime()+30_000_000_000L;
        do{r.awaitIdle();submit(r);require(System.nanoTime()<deadline,"Retirement cursor and release queue recover");}
        while(r.resourcePressure().retiredGenerations()!=0||r.resourcePressure().pendingReleases()!=0);
        var arena=r.resourceArenaStatistics();
        require(arena.pooledBuffers()<=64&&arena.pooledBlases()<=1024,"Both object caches have count limits");
        require(r.resourcePressure().pooledMeshBytes()<=64L*1024*1024&&r.resourcePressure().pooledBlasBytes()<=64L*1024*1024,"Both caches have byte limits");
        require(arena.heapBytes()<=arena.budgetBytes()&&arena.leasedBytes()<=arena.heapBytes(),"Heap and lease accounting obey budget");
        require(arena.freeBytes()+arena.leasedBytes()==arena.heapBytes(),"Every arena byte has one owner or is free");
    }
    public static void main(String[] args)throws Exception {
        Files.createDirectories(OUTPUT);Files.createDirectories(OUT);var rows=new ArrayList<Object>();
        try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            r.nativeLightingPlan(plan(EnumSet.allOf(NativeLightingPlan.Signal.class),2,SUN_ON,true));
            r.apply(SceneTransaction.builder(revision).resetScene().upsert(material(1,128,128,128)).upsert(material(2,255,255,255))
                .upsert(plane(1,1,2,0,.2f,.7f,false)).upsert(SceneInstance.builder(1,1).build()).build());
            submit(r);var reference=capture(r,"arena-reference",0);var referenceNormals=capture(r,"arena-reference-normal",2);var live=new ArrayList<Long>();
            // Place the visible mesh after another member of one transaction.
            // Retire only that other member, then churn allocations while the
            // survivor keeps the shared page and its non-zero ranges alive.
            r.apply(SceneTransaction.builder(++revision)
                .upsert(RtxptTopologyPreparationNativeSelfTest.multi(0,new long[]{2,2,2}))
                .upsert(RtxptTopologyPreparationNativeSelfTest.offscreen(0,0))
                .upsert(plane(1,1,2,0,.2f,.7f,false)).build());
            submit(r);drain(r);
            require(Arrays.equals(reference,capture(r,"arena-shared-visible",0)),"Shared buffer offsets preserve visible output");
            r.apply(SceneTransaction.builder(++revision).removeInstance(0).removeMesh(0).build());submit(r);drain(r);
            require(Arrays.equals(reference,capture(r,"arena-shared-survivor",0)),"Partial page retirement preserves the surviving mesh");
            r.apply(SceneTransaction.builder(++revision)
                .upsert(RtxptTopologyPreparationNativeSelfTest.multi(2,new long[]{2,2}))
                .upsert(RtxptTopologyPreparationNativeSelfTest.multi(3,new long[]{2,2,2}))
                .upsert(RtxptTopologyPreparationNativeSelfTest.multi(4,new long[]{2}))
                .upsert(RtxptTopologyPreparationNativeSelfTest.offscreen(2,2))
                .upsert(RtxptTopologyPreparationNativeSelfTest.offscreen(3,3))
                .upsert(RtxptTopologyPreparationNativeSelfTest.offscreen(4,4)).build());submit(r);
            var mixedBefore=r.resourceMaintenance();
            r.apply(SceneTransaction.builder(++revision)
                .upsert(deformedMulti(2,2,2)).upsert(deformedMulti(3,2,2,2)).upsert(deformedMulti(4,2))
                .upsert(RtxptTopologyPreparationNativeSelfTest.multi(5,new long[]{2}))
                .upsert(RtxptTopologyPreparationNativeSelfTest.offscreen(5,5)).build());submit(r);
            var mixedAfter=r.resourceMaintenance();
            require(mixedAfter.blasRefits()-mixedBefore.blasRefits()>=3&&mixedAfter.blasBuilds()>mixedBefore.blasBuilds(),"One transaction contains three independent clone/refits and a new BLAS build");
            require(Arrays.equals(reference,capture(r,"arena-mixed-build-refit",0)),"Batched clone/build/refit dependencies preserve visible output");
            r.apply(SceneTransaction.builder(++revision).removeInstance(2).removeMesh(2).removeInstance(3).removeMesh(3).removeInstance(4).removeMesh(4).removeInstance(5).removeMesh(5).build());submit(r);drain(r);
            long deferrals=0;
            for(int count:new int[]{8,9,18,62,70,129,300,11,18,1,1}){
                var tx=SceneTransaction.builder(++revision);for(long id:live)tx.removeInstance(id).removeMesh(id);
                int retired=live.size();live.clear();long attempts=r.resourceArenaStatistics().recycleAttempts();
                for(int i=0;i<count;i++){
                    long id=10000+revision*1000+i;live.add(id);
                    // Different geometry counts exercise storage reuse without
                    // relaxing clone/refit compatibility.
                    long[] groups=new long[1+i%5];Arrays.fill(groups,2);
                    tx.upsert(RtxptTopologyPreparationNativeSelfTest.multi(id,groups)).upsert(RtxptTopologyPreparationNativeSelfTest.offscreen(id,id));
                }
                long start=System.nanoTime();r.apply(tx.build());deferrals+=submit(r);
                require(Arrays.equals(reference,capture(r,"arena-"+count,0)),"Unrelated inflow preserves visible attributes");
                drain(r);
                require(r.resourceArenaStatistics().recycleAttempts()-attempts>=retired,"Every retired mesh gets a recycle opportunity, including after item 8");
                rows.add(RtxptJson.object("batch",count,"retired",retired,"wallNanos",System.nanoTime()-start,
                    "arena",r.resourceArenaStatistics().toString(),"pressure",r.resourcePressure().toString()));
            }
            require(r.resourceArenaStatistics().evictions()>0,"Cache saturation exercises background eviction");
            var uploadMemory=r.uploadMemory();
            require(uploadMemory.transactionLeasedBytes()==0&&uploadMemory.transactionAllocatedBytes()<=4L*1024*1024,"Completed upload pages exceeded the idle warm budget");
            System.out.println("TRANSACTION_UPLOAD_MEMORY "+uploadMemory);
            require(r.resourceArenaStatistics().bufferPoolHits()>0,"Capacity-compatible buffers are actually reused");
            var before=r.resourceMaintenance();
            // The first material had no tangent payload. Enabling a normal map
            // must prepare its mesh and preserve the current frame's mapping.
            var normal=TextureAsset.color(91,1,1,new byte[]{(byte)255,(byte)128,(byte)255,(byte)255});
            r.apply(SceneTransaction.builder(++revision).upsert(normal).upsert(MaterialAsset.builder(1).baseColorRgba8(rgba(128,128,128)).normalTextureId(91).build()).build());
            submit(r);var enabled=capture(r,"arena-normal-enabled",2);
            require(!Arrays.equals(enabled,referenceNormals),"Normal-map enabling changes the actual normal guide");
            require(r.resourceMaintenance().blasBuilds()+r.resourceMaintenance().blasRefits()>before.blasBuilds()+before.blasRefits(),"New tangent layout is prepared before material publication");
            r.apply(SceneTransaction.builder(++revision).upsert(material(1,128,128,128)).build());submit(r);
            var disabled=capture(r,"arena-normal-disabled",2);
            require(Arrays.equals(disabled,referenceNormals),"Normal-map disabling restores geometric normals");
            r.diagnosticLevel(RtxptDiagnostics.Level.OFF);var off=r.dynamicResourceStatistics();submit(r);
            require(r.dynamicResourceStatistics().equals(off),"OFF does not collect resource work");
            Files.writeString(OUTPUT.resolve("arena.json"),RtxptJson.encode(RtxptJson.object("passed",true,"rows",rows,"deferrals",deferrals,
                "arena",r.resourceArenaStatistics().toString(),"normalMapToggle",true)));
            System.out.println("RESOURCE_ARENA PASS batches=11 cursorBeyond8=true cachesBounded=true partialPageRetirement=true normalMapToggle=true deferrals="+deferrals);
        }
    }
}
