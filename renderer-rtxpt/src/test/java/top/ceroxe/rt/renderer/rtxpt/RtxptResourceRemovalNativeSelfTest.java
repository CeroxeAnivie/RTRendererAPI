package top.ceroxe.rt.renderer.rtxpt;

import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Shared-instance detach, last-reader retirement and bounded resource reuse. */
public final class RtxptResourceRemovalNativeSelfTest {
    public static void main(String[] args)throws Exception{
        var output=Path.of(System.getProperty("resourceMatrix.output","build/reports/resource-latency"));
        Files.createDirectories(output);long sequence=0,revision=1,mesh=1,first=1,second=2;
        try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            r.nativeLightingPlan(NativeLightingPlan.defaults(64,48));
            r.lightingReconstruction(NativeLightingReconstruction.DLSS_RR);
            r.diagnosticLevel(RtxptDiagnostics.Level.SUMMARY);
            r.apply(SceneTransaction.builder(revision).resetScene()
                .upsert(MaterialAsset.builder(2).roughness(.8f).build()).upsert(MaterialAsset.builder(3).roughness(.8f).build())
                .upsert(RtxptHighFramerateBenchmark.streamMesh(mesh,1))
                .upsert(SceneInstance.builder(first,mesh).build()).upsert(SceneInstance.builder(second,mesh).build()).build());
            RtxptResourceLatencyMatrixNativeSelfTest.render(r,sequence++,revision);
            for(int iteration=0;iteration<48;iteration++){
                var before=r.resourceMaintenance();
                r.apply(SceneTransaction.builder(++revision).removeInstance(second).build());
                RtxptResourceLatencyMatrixNativeSelfTest.render(r,sequence++,revision);
                var shared=r.resourceMaintenance();
                require(shared.blasBuilds()==before.blasBuilds()&&shared.blasRefits()==before.blasRefits(),
                    "Removing one shared instance retains its live BLAS");
                long next=mesh+1;
                r.apply(SceneTransaction.builder(++revision).removeInstance(first).removeMesh(mesh)
                    .upsert(RtxptHighFramerateBenchmark.streamMesh(next,1))
                    .upsert(SceneInstance.builder(first+2,next).build()).upsert(SceneInstance.builder(second+2,next).build()).build());
                RtxptResourceLatencyMatrixNativeSelfTest.render(r,sequence++,revision);mesh=next;first+=2;second+=2;
                require(r.resourcePressure().pendingReleases()<=32,"Release slots remain bounded");
            }
            r.awaitIdle();
            for(int i=0;i<8;i++)RtxptResourceLatencyMatrixNativeSelfTest.render(r,sequence++,revision);
            var rr=r.lightingReconstruction();require(rr.actual()==NativeLightingReconstruction.DLSS_RR&&rr.guidesValid(),"RR remains valid after detach/reuse");
            var pressure=r.resourcePressure();require(pressure.retiredGenerations()==0,"Completed removal generations drain");
            require(pressure.pooledTlasBytes()>0&&pressure.pooledTlasBytes()<=64L*1024*1024,"Completed TLAS/sub-instance storage is reused within its byte bound");
            Files.writeString(output.resolve("resource-removal.json"),RtxptJson.encode(RtxptJson.object(
                "passed",true,"iterations",48,"frames",sequence,"rr",rr.toString(),"pressure",pressure.toString(),
                "boundary","Shared and last-instance removal with fresh dual-plane identities; no system fault injection")));
            System.out.println("RESOURCE_REMOVAL PASS sharedMesh=true lastInstance=true iterations=48 freshOutputs="+sequence);
        }
    }
}
