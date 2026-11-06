package top.ceroxe.rt.renderer.rtxpt;

import java.nio.LongBuffer;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** One combined scene-scale owner request; no MC FPS or isolated AS attribution. */
public final class RtxptManagedScaleNativeSelfTest {
    static SparseLightSource source(long id,int step){
        var s=RtxptSparseRegionalNativeSelfTest.source(id,0);
        return new SparseLightSource(s.id(),s.shape(),s.x()+step*.02,s.y(),s.z(),s.halfX(),s.halfY(),s.halfZ(),
            s.directionX(),s.directionY(),s.directionZ(),s.red(),s.green(),s.blue(),s.intensity(),s.range(),s.falloffExponent(),
            s.innerConeCosine(),s.outerConeCosine(),s.composition(),s.group(),s.castsShadow(),s.layer(),s.attenuation());
    }
    public static void main(String[] args)throws Exception {
        int count=3300,sources=45000,warmup=4,samples=16;long revision=1;
        RtxptBlasReuseNativeSelfTest.revision=revision;RtxptBlasReuseNativeSelfTest.sequence=0;
        try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            r.nativeLightingPlan(new NativeLightingPlan(64,48,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.NORMAL_DISTANCE),
                0,6,.35f,NativeLightingStyle.defaults(),new NativeLightingPlan.Directional(0,0,1,1,1,1,1),null));
            var tx=SceneTransaction.builder(revision).resetScene().upsert(MaterialAsset.builder(1).build());
            for(int i=1;i<=count;i++)tx.upsert(RtxptBlasReuseNativeSelfTest.mesh(i,0))
                .upsert(i==1?SceneInstance.builder(i,i).build():RtxptTopologyPreparationNativeSelfTest.offscreen(i,i));
            var initial=new ArrayList<SparseLightSource>(sources);for(int i=0;i<sources;i++)initial.add(source(i,0));
            r.apply(tx.build(),initial,LongBuffer.allocate(0));RtxptBlasReuseNativeSelfTest.submit(r);RtxptBlasReuseNativeSelfTest.drain(r);
            r.diagnosticLevel(RtxptDiagnostics.Level.DEEP);var monitor=r.diagnosticMonitor();monitor.poll();
            var owner=new ArrayList<Long>();var stages=new EnumMap<RtxptDiagnostics.Stage,ArrayList<Long>>(RtxptDiagnostics.Stage.class);
            var selected=EnumSet.of(RtxptDiagnostics.Stage.TRANSLATION,RtxptDiagnostics.Stage.RESOURCES,RtxptDiagnostics.Stage.LIGHTING,
                RtxptDiagnostics.Stage.LIGHT_TREE,RtxptDiagnostics.Stage.SPARSE_REGIONS,RtxptDiagnostics.Stage.SPARSE_QUERY_PREPARATION,
                RtxptDiagnostics.Stage.FRAME);
            long clones=0,refits=0;
            for(int step=0;step<warmup+samples;step++){
                var edit=SceneTransaction.builder(++revision);
                for(int i=1;i<=128;i++)edit.upsert(RtxptBlasReuseNativeSelfTest.mesh(i,.01f*(step+1)));
                var changed=new ArrayList<SparseLightSource>(150);for(int i=0;i<150;i++)changed.add(source((step*150+i)%sources,step+1));
                RtxptBlasReuseNativeSelfTest.revision=revision;long start=System.nanoTime();
                r.apply(edit.build(),changed,LongBuffer.allocate(0));RtxptBlasReuseNativeSelfTest.submit(r);long elapsed=System.nanoTime()-start;
                var sums=new EnumMap<RtxptDiagnostics.Stage,Long>(RtxptDiagnostics.Stage.class);
                for(var e:monitor.poll().events()){
                    if(e.stage()==RtxptDiagnostics.Stage.BLAS_CLONE)clones++;if(e.stage()==RtxptDiagnostics.Stage.BLAS_REFIT)refits++;
                    if(selected.contains(e.stage()))sums.merge(e.stage(),e.inclusiveNanos(),Long::sum);
                }
                if(step>=warmup){owner.add(elapsed);for(var stage:selected)stages.computeIfAbsent(stage,k->new ArrayList<>()).add(sums.getOrDefault(stage,0L));}
            }
            RtxptBlasReuseNativeSelfTest.distribution("scale-owner-apply-to-fresh-lease",owner);
            for(var entry:stages.entrySet())RtxptBlasReuseNativeSelfTest.distribution("scale-"+entry.getKey(),entry.getValue());
            RtxptBlasReuseNativeSelfTest.capture(r,"scale-final");
            require(r.sparseLightingStatus().representedSources()==sources,"Every source remains resident");
            require(r.diagnostics().residentMeshes()==count,"Every mesh remains resident");
            System.out.printf(Locale.ROOT,"RTXPT_MANAGED_SCALE PASS meshes=%d sources=%d meshDelta=128 sourceDelta=150 samples=%d warmup=%d frame=128x96 lighting=64x48 clones=%d refits=%d freshFrames=%d%n",
                count,sources,samples,warmup,clones,refits,RtxptBlasReuseNativeSelfTest.sequence);
        }
    }
}
