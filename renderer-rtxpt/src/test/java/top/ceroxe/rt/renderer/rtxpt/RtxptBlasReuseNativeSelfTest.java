package top.ceroxe.rt.renderer.rtxpt;

import java.util.Locale;
import java.util.Set;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.nio.file.Files;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Retired same-layout BLAS batches larger than 64 are reused within the byte budget. */
public final class RtxptBlasReuseNativeSelfTest {
    static long sequence, revision=1;
    static MeshAsset mesh(long id,float offset) {
        return MeshAsset.builder(id,new float[]{-1+offset,-1,0,1+offset,-1,0,offset,1,0},
            new int[]{0,1,2},new long[]{1}).build();
    }
    static MeshAsset attributes(long id,float offset,boolean alternate) {
        float y=alternate?.6f:-.6f;
        return MeshAsset.builder(id,new float[]{-1+offset,-1,0,1+offset,-1,0,offset,1,0},
            new int[]{0,1,2},new long[]{1}).normals(new float[]{0,y,.8f,0,y,.8f,0,y,.8f})
            .textureCoordinates(new float[]{0,0,alternate?1:0,0,0,1}).build();
    }
    static long submit(RtxptRenderer renderer) throws Exception {
        var request=RtxptFramePreparationNativeSelfTest.frame(sequence,revision,128,96);
        long begin=System.nanoTime(),deadline=begin+30_000_000_000L;
        while(renderer.trySubmit(request) instanceof Renderer.FrameSubmissionDeferred) {
            require(System.nanoTime()<deadline,"Retirement makes forward progress");Thread.sleep(1);
        }
        try(var lease=renderer.acquireLightingFrame().orElseThrow()) {
            require(lease.frame().sequence()==sequence&&lease.frame().sceneRevision()==revision,
                "Fresh output preserves accepted request identity");
        }
        sequence++;return System.nanoTime()-begin;
    }
    static void drain(RtxptRenderer renderer) throws Exception {
        long deadline=System.nanoTime()+30_000_000_000L;
        do {
            renderer.awaitIdle();submit(renderer);
            require(System.nanoTime()<deadline,"Completed generations fully retire");
        } while(renderer.resourcePressure().retiredGenerations()!=0||renderer.resourcePressure().pendingReleases()!=0);
        var arena=renderer.resourceArenaStatistics();var pressure=renderer.resourcePressure();
        require(arena.pooledBlases()<=1024&&pressure.pooledBlasBytes()<=64L*1024*1024,"BLAS pool stays bounded");
        require(arena.heapBytes()<=arena.budgetBytes()&&arena.freeBytes()+arena.leasedBytes()==arena.heapBytes(),
            "Every heap byte is leased or free within budget");
    }
    public static void main(String[] args) throws Exception {
        final int count=128,samples=32;boolean baseline=Boolean.getBoolean("blasReuse.allowClones");
        Files.createDirectories(RtxptNativeLightingMatrixNativeSelfTest.OUT);
        try(var renderer=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)) {
            renderer.nativeLightingPlan(new NativeLightingPlan(64,48,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.NORMAL_DISTANCE),
                0,6,.35f,NativeLightingStyle.defaults(),new NativeLightingPlan.Directional(0,0,1,1,1,1,1),null));
            var initial=SceneTransaction.builder(revision).resetScene().upsert(MaterialAsset.builder(1).build());
            for(int i=1;i<=count;i++)initial.upsert(mesh(i,0)).upsert(i==1?SceneInstance.builder(i,i).build():RtxptTopologyPreparationNativeSelfTest.offscreen(i,i));
            renderer.apply(initial.build());submit(renderer);drain(renderer);
            var original=capture(renderer,"managed-mesh-original");
            renderer.diagnosticLevel(RtxptDiagnostics.Level.DEEP);var monitor=renderer.diagnosticMonitor();
            var wall=new ArrayList<Long>();var stages=new EnumMap<RtxptDiagnostics.Stage,ArrayList<Long>>(RtxptDiagnostics.Stage.class);
            long clones=0,allocations=0,refits=0;
            for(int step=0;step<samples+4;step++){
                var edit=SceneTransaction.builder(++revision);
                for(int i=1;i<=count;i++)edit.upsert(mesh(i,.01f*(step+1)));
                long start=System.nanoTime();renderer.apply(edit.build());submit(renderer);long elapsed=System.nanoTime()-start;
                var snapshot=monitor.poll();
                for(var event:snapshot.events()){
                    if(event.stage()==RtxptDiagnostics.Stage.BLAS_CLONE)clones++;
                    if(event.stage()==RtxptDiagnostics.Stage.RESOURCE_PREBUILD||event.stage()==RtxptDiagnostics.Stage.BLAS_ALLOCATE)allocations++;
                    if(event.stage()==RtxptDiagnostics.Stage.BLAS_REFIT)refits++;
                    if(step>=4)stages.computeIfAbsent(event.stage(),k->new ArrayList<>()).add(event.inclusiveNanos());
                }
                if(step>=4)wall.add(elapsed);
            }
            var changed=capture(renderer,"managed-mesh-final");
            require(!Arrays.equals(original,changed),"Fresh visible geometry follows the current deformation");
            drain(renderer);
            if(!baseline){
                require(clones==0&&allocations==0,"Stable layout updates allocate and clone no BLAS: clones="+clones+", allocations="+allocations);
                require(renderer.resourceArenaStatistics().pooledBlases()==0,"A live stable BLAS never enters an old generation's spare pool");
            }
            distribution("owner-update-and-frame",wall);
            for(var stage:new RtxptDiagnostics.Stage[]{RtxptDiagnostics.Stage.TRANSLATION,RtxptDiagnostics.Stage.RESOURCES,RtxptDiagnostics.Stage.FRAME})
                if(stages.containsKey(stage))distribution(stage.name(),stages.get(stage));
            System.out.printf(Locale.ROOT,"RTXPT_MANAGED_MESH PASS batch=%d updates=%d clones=%d allocations=%d refits=%d allowClones=%s freshFrames=%d%n",
                count,samples+4,clones,allocations,refits,baseline,sequence);
            wall.clear();stages.clear();clones=allocations=refits=0;long builds=0;monitor.poll();
            for(int step=0;step<samples+4;step++){
                var edit=SceneTransaction.builder(++revision);
                for(int i=1;i<=count;i++)edit.upsert(attributes(i,.01f*(samples+4),(step&1)==0));
                long start=System.nanoTime();renderer.apply(edit.build());submit(renderer);long elapsed=System.nanoTime()-start;
                var snapshot=monitor.poll();
                for(var event:snapshot.events()){
                    if(event.stage()==RtxptDiagnostics.Stage.BLAS_CLONE)clones++;
                    if(event.stage()==RtxptDiagnostics.Stage.RESOURCE_PREBUILD||event.stage()==RtxptDiagnostics.Stage.BLAS_ALLOCATE)allocations++;
                    if(event.stage()==RtxptDiagnostics.Stage.BLAS_REFIT)refits++;
                    if(event.stage()==RtxptDiagnostics.Stage.BLAS_BUILD)builds++;
                    if(step>=4)stages.computeIfAbsent(event.stage(),k->new ArrayList<>()).add(event.inclusiveNanos());
                }
                if(step>=4)wall.add(elapsed);
            }
            require(!Arrays.equals(changed,capture(renderer,"managed-mesh-attributes")),"Current normals reach the fresh output without rebuilding geometry");
            drain(renderer);
            if(!baseline)require(clones==0&&allocations==0&&refits==0&&builds==0,"Attribute-only changes do no AS work");
            distribution("attributes-owner-update-and-frame",wall);
            for(var stage:new RtxptDiagnostics.Stage[]{RtxptDiagnostics.Stage.TRANSLATION,RtxptDiagnostics.Stage.RESOURCES,RtxptDiagnostics.Stage.FRAME})
                if(stages.containsKey(stage))distribution("attributes-"+stage.name(),stages.get(stage));
            System.out.printf(Locale.ROOT,"RTXPT_MANAGED_ATTRIBUTES PASS batch=%d updates=%d clones=%d allocations=%d refits=%d builds=%d allowClones=%s%n",
                count,samples+4,clones,allocations,refits,builds,baseline);
            var leases=new ArrayList<LightingFrameLease>();boolean full=false;
            for(int slot=0;slot<16;slot++){
                var request=RtxptFramePreparationNativeSelfTest.frame(sequence,revision,128,96);
                if(renderer.trySubmit(request) instanceof Renderer.FrameSubmissionDeferred){full=true;break;}
                leases.add(renderer.acquireLightingFrame().orElseThrow());sequence++;
            }
            require(full,"Held output leases exercise actual admission deferral");
            var pending=SceneTransaction.builder(++revision);for(int i=1;i<=count;i++)pending.upsert(mesh(i,.5f));
            renderer.apply(pending.build());
            var before=renderer.retainedUpdateTimings();var maintenance=renderer.resourceMaintenance();
            var deferred=RtxptFramePreparationNativeSelfTest.frame(sequence,revision,128,96);
            require(renderer.trySubmit(deferred) instanceof Renderer.FrameSubmissionDeferred,"Full publication ring defers the geometry update");
            require(renderer.retainedUpdateTimings().resourceBatches()==before.resourceBatches()
                &&renderer.resourceMaintenance().blasRefits()==maintenance.blasRefits(),"Rejected admission writes no resource or AS state");
            for(var lease:leases)lease.close();
            long deadline=System.nanoTime()+30_000_000_000L;
            while(renderer.trySubmit(deferred) instanceof Renderer.FrameSubmissionDeferred){require(System.nanoTime()<deadline,"The identical deferred request eventually publishes");Thread.sleep(1);}
            try(var lease=renderer.acquireLightingFrame().orElseThrow()){
                require(lease.frame().sequence()==sequence&&lease.frame().sceneRevision()==revision,"Retried request publishes the current exact identity");
            }sequence++;drain(renderer);
            if(!baseline)require(renderer.resourceArenaStatistics().pooledBlases()==0,"Pending/admitted updates never retire an active AS");
            renderer.apply(SceneTransaction.builder(++revision).upsert(MeshAsset.builder(1,
                new float[]{-1,-1,0,1,-1,0,1,1,0,-1,1,0},new int[]{0,1,2,0,2,3},new long[]{1,1}).build()).build());
            submit(renderer);drain(renderer);
            if(!baseline)require(renderer.resourceArenaStatistics().pooledBlases()==1,"Only the incompatible replacement retires its previous AS");
            System.out.println("RTXPT_MANAGED_BOUNDARIES PASS outputDeferral=true retryIdentity=true activePoolExcluded=true incompatibleReplacement=true");
            var remove=SceneTransaction.builder(++revision);for(int i=1;i<=count;i++)remove.removeInstance(i).removeMesh(i);
            renderer.apply(remove.build());submit(renderer);drain(renderer);
            long available=renderer.resourceArenaStatistics().pooledBlases();
            if(!baseline)require(available>=count,"Only actual removal releases all 128 resident BLASes: "+available);
            long hits=renderer.resourceArenaStatistics().blasPoolHits();
            var second=SceneTransaction.builder(++revision);
            for(int i=1;i<=count;i++)second.upsert(mesh(i+1000,.02f)).upsert(RtxptTopologyPreparationNativeSelfTest.offscreen(i+1000,i+1000));
            renderer.apply(second.build());submit(renderer);drain(renderer);
            var arena=renderer.resourceArenaStatistics();long reused=arena.blasPoolHits()-hits;
            if(!baseline)require(reused>=count,"All 128 truly retired BLAS targets are reused: "+reused);
            System.out.printf(Locale.ROOT,"RTXPT_BLAS_REUSE PASS batch=%d available=%d poolHits=%d pooledBlases=%d pooledBytes=%d heapBytes=%d leasedBytes=%d freeBytes=%d freshFrames=%d%n",
                count,available,reused,arena.pooledBlases(),renderer.resourcePressure().pooledBlasBytes(),arena.heapBytes(),arena.leasedBytes(),arena.freeBytes(),sequence);
        }
    }
    static double[] capture(RtxptRenderer renderer,String label)throws Exception{
        var file=RtxptNativeLightingMatrixNativeSelfTest.OUT.resolve(label+".pfm");renderer.captureNativeLighting(file,2);
        return RtxptLightTreeNativeSelfTest.linear(file);
    }
    static void distribution(String name,ArrayList<Long> values){
        var sorted=values.stream().mapToLong(Long::longValue).sorted().toArray();
        System.out.printf(Locale.ROOT,"RTXPT_MANAGED_COST stage=%s samples=%d meanMs=%.6f p95Ms=%.6f%n",name,sorted.length,
            Arrays.stream(sorted).average().orElseThrow()/1e6,sorted[(int)Math.ceil(sorted.length*.95)-1]/1e6);
    }
}
