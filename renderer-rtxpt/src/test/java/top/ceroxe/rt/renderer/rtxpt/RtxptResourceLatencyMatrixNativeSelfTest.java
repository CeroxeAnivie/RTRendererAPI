package top.ceroxe.rt.renderer.rtxpt;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;
import static top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingMatrixNativeSelfTest.SUN_ON;

/** Targeted changed-path regression at the actual configured scene scale. */
public final class RtxptResourceLatencyMatrixNativeSelfTest {
    static void admit(RtxptRenderer r,RenderFrameRequest request)throws Exception{
        long end=System.nanoTime()+30_000_000_000L;
        while(r.trySubmit(request) instanceof Renderer.FrameSubmissionDeferred){
            require(System.nanoTime()<end,"Bounded admission recovers");Thread.sleep(1);
        }
    }
    static void render(RtxptRenderer r,long sequence,long revision)throws Exception{
        admit(r,RtxptDynamicResourcesNativeSelfTest.request(sequence,revision,0));
        RtxptHighFramerateBenchmark.release(r,sequence,revision);
    }
    public static void main(String[] args)throws Exception{
        int count=Integer.getInteger("resourceMatrix.meshes",719);long sequence=0,revision=1;
        var output=Path.of(System.getProperty("resourceMatrix.output","build/reports/resource-latency"));Files.createDirectories(output);
        var rows=new ArrayList<Object>();long started=System.nanoTime();
        try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4).copyBuilder().resources(RendererResourceOptions.high()).build(),true)){
            r.nativeLightingPlan(new NativeLightingPlan(1280,800,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.INDIRECT_DIFFUSE),
                2,6,.35f,NativeLightingStyle.defaults(),SUN_ON,null,true));r.lightingReconstruction(NativeLightingReconstruction.DLSS_RR);
            r.diagnosticLevel(RtxptDiagnostics.Level.SUMMARY);var monitor=r.diagnosticMonitor();
            var observing=new java.util.concurrent.atomic.AtomicBoolean(true);
            var initializationSeen=new java.util.concurrent.atomic.AtomicBoolean();
            var observer=Thread.ofPlatform().daemon().start(()->{
                while(observing.get()){
                    var snapshot=monitor.snapshot();
                    if(snapshot.events().stream().anyMatch(e->e.stage()==RtxptDiagnostics.Stage.RUNTIME_INITIALIZE)
                        ||snapshot.activeScopes().stream().anyMatch(e->e.stage()==RtxptDiagnostics.Stage.RUNTIME_INITIALIZE))initializationSeen.set(true);
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
                }
            });
            var tx=SceneTransaction.builder(revision).resetScene();
            for(long id=1;id<=63;id++)tx.upsert(RtxptHighFramerateBenchmark.texture(id,1)).upsert(MaterialAsset.builder(id).baseColorTextureId(id).roughness(.8f).build());
            for(long id=1;id<=count;id++)tx.upsert(RtxptDynamicResourcesNativeSelfTest.mesh(id,0)).upsert(SceneInstance.builder(id,id).build());
            try{r.apply(tx.build(),RtxptHighFramerateBenchmark.sources(count>=4096?26000:count>=977?22000:17408),LongBuffer.allocate(0));render(r,sequence++,revision);r.awaitIdle();}
            finally{observing.set(false);observer.join();}
            rows.add(RtxptJson.object("case","initialize","wallNanos",System.nanoTime()-started,"initializationObserved",initializationSeen.get(),"overwritten",monitor.snapshot().overwrittenEvents()));
            require(initializationSeen.get(),"Pre-submit observer sees initialization before bounded history can overwrite it");
            r.lightingWorkStatisticsEnabled(true);render(r,sequence++,revision);
            require(!r.lightingStatisticsCollection().effective(),"SUMMARY does not execute ray atomics");
            for(int batch=0;batch<4;batch++){
                var before=r.resourceMaintenance();tx=SceneTransaction.builder(++revision);
                long visits=r.dynamicResourceStatistics().attributeTableVisits();
                int changed=batch+1;for(long id=1;id<=changed;id++)tx.upsert(RtxptDynamicResourcesNativeSelfTest.mesh(id,.001f*(batch+1)));
                r.apply(tx.build());render(r,sequence++,revision);r.awaitIdle();var after=r.resourceMaintenance();
                require(after.blasBuilds()-before.blasBuilds()+after.blasRefits()-before.blasRefits()==changed,"Only changed BLAS are built/refit");
                require(r.dynamicResourceStatistics().attributeTableVisits()-visits<=changed*8L+8,"Metadata visits follow changed meshes and dense migrations, independently of scene scale");
                rows.add(RtxptJson.object("case","local","changed",changed,"before",before.toString(),"after",after.toString()));
            }
            var before=r.resourceMaintenance();
            for(int batch=0;batch<6;batch++){r.apply(SceneTransaction.builder(++revision).upsert(RtxptHighFramerateBenchmark.texture(1,batch+2)).build());render(r,sequence++,revision);}
            var after=r.resourceMaintenance();require(after.blasBuilds()==before.blasBuilds()&&after.blasRefits()==before.blasRefits(),"Classified coverage does not build/refit BLAS");
            for(int batch=0;batch<3;batch++){
                tx=SceneTransaction.builder(++revision);for(long id=count+1;id<=count+5;id++){
                    if(batch>0)tx.removeInstance(id+5*(batch-1)).removeMesh(id+5*(batch-1));
                    long next=id+5*batch;tx.upsert(RtxptDynamicResourcesNativeSelfTest.mesh(next,0)).upsert(SceneInstance.builder(next,next).build());
                }r.apply(tx.build());render(r,sequence++,revision);
            }
            before=r.resourceMaintenance();var work=r.dynamicResourceStatistics();
            r.apply(SceneTransaction.builder(++revision).upsert(SceneInstance.builder(1,1).transform(new AffineTransform(new float[]{1,0,0,.05f,0,1,0,0,0,0,1,0})).build()).build());render(r,sequence++,revision);
            require(r.resourceMaintenance().blasBuilds()==before.blasBuilds()&&r.resourceMaintenance().blasRefits()==before.blasRefits(),"Pose retains every BLAS");
            r.apply(SceneTransaction.builder(++revision).upsert(MaterialAsset.builder(1).baseColorTextureId(1).roughness(.62f).build()).build());render(r,sequence++,revision);
            var attribute=RtxptDynamicResourcesNativeSelfTest.mesh(1,.004f);int[] colors=new int[attribute.vertexCount()];Arrays.fill(colors,0x80d0b0a0);
            r.apply(SceneTransaction.builder(++revision).upsert(attribute.toBuilder().vertexColorsRgba8(colors).build()).build());render(r,sequence++,revision);
            r.publishLightingAttributes(new NativeLightingPublication(1,List.of(new NativeLightingPublication.Instance(1,22,.8f,.1f,1,1,0,0)),
                List.of(new NativeLightingPublication.MeshBaseline(1,new float[attribute.vertexCount()*2])),null,null));render(r,sequence++,revision);
            require(r.resourceMaintenance().blasBuilds()==before.blasBuilds()&&r.resourceMaintenance().blasRefits()==before.blasRefits(),"Material, RGB, alpha, baseline and roles retain BLAS");
            require(r.dynamicResourceStatistics().geometryUploadBytes()==work.geometryUploadBytes(),"No geometry payload for pose and attributes");
            var texture=RtxptHighFramerateBenchmark.texture(1,19);r.apply(SceneTransaction.builder(++revision).upsert(texture).build());render(r,sequence++,revision);
            var patch=texture.patched(new TexturePatch(1,2,0,0,1,1,4,ByteBuffer.wrap(new byte[]{16,32,64,(byte)255})));
            r.apply(SceneTransaction.builder(++revision).upsert(patch).build());render(r,sequence++,revision);
            rows.add(RtxptJson.object("case","pose-material-attributes-shared-mip-patch","passed",true));
            var held=new ArrayList<LightingFrameLease>();
            try{
                for(int i=0;i<3;i++){var request=RtxptDynamicResourcesNativeSelfTest.request(sequence++,revision,0);admit(r,request);held.add(r.acquireLightingFrame().orElseThrow());}
                var retry=RtxptDynamicResourcesNativeSelfTest.request(sequence,revision,0);long deferStart=System.nanoTime();
                require(r.trySubmit(retry) instanceof Renderer.FrameSubmissionDeferred,"Unreturned consumers defer before accepting request");
                rows.add(RtxptJson.object("case","slow-consumer","deferNanos",System.nanoTime()-deferStart,"slots",held.size()));
                held.get(1).close();held.get(2).close();held.get(0).close();
            }finally{for(var lease:held)lease.close();}
            render(r,sequence++,revision);
            for(int[] extent:new int[][]{{960,540},{1280,800},{1279,799}}){long resizeStart=System.nanoTime();
                r.nativeLightingPlan(new NativeLightingPlan(extent[0],extent[1],Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.INDIRECT_DIFFUSE),2,6,.35f,NativeLightingStyle.defaults(),SUN_ON,null,true));
                var request=RtxptDynamicResourcesNativeSelfTest.request(sequence,revision,0).toBuilder().build();
                // Both logical host sizes and odd physical grids are exercised.
                request=RenderFrameRequest.builder(sequence,extent[0]==960?1920:2560,extent[0]==960?1080:1600,request.camera()).minimumSceneRevision(revision).environment(request.environment()).build();
                long deadline=System.nanoTime()+30_000_000_000L;while(r.trySubmit(request) instanceof Renderer.FrameSubmissionDeferred){require(System.nanoTime()<deadline,"Resize admission recovers");Thread.sleep(1);}
                RtxptHighFramerateBenchmark.release(r,sequence++,revision);rows.add(RtxptJson.object("case","resize","width",extent[0],"height",extent[1],"wallNanos",System.nanoTime()-resizeStart));
            }
            r.diagnosticLevel(RtxptDiagnostics.Level.DEEP);render(r,sequence++,revision);
            require(r.lightingStatisticsCollection().effective(),"DEEP enables requested ray atomics");
            var live=monitor.snapshot();require(live.events().stream().anyMatch(e->e.stage()==RtxptDiagnostics.Stage.BLAS_ALLOCATE||e.stage()==RtxptDiagnostics.Stage.BLAS_REFIT),"Resource subcalls carry evidence");
            r.diagnosticLevel(RtxptDiagnostics.Level.OFF);var off=monitor.snapshot();
            for(int i=0;i<4;i++)render(r,sequence++,revision);
            require(monitor.snapshot().nextEventOrdinal()==off.nextEventOrdinal(),"OFF stops CPU evidence growth");
            require(!r.lightingStatisticsCollection().effective(),"OFF disables ray atomics");
            r.diagnosticLevel(RtxptDiagnostics.Level.SUMMARY);render(r,sequence++,revision);r.awaitIdle();
            rows.add(RtxptJson.object("case","final","pressure",r.resourcePressure().toString(),"rr",r.lightingReconstruction().toString(),
                "observerMissed",monitor.snapshot().missedSinceRegistration()));
            Files.writeString(output.resolve("matrix-"+count+".json"),RtxptJson.encode(RtxptJson.object("meshCount",count,"passed",true,"cases",rows)));
            System.out.println("RESOURCE_LATENCY_MATRIX PASS meshes="+count+" cases=init,local,coverage,stream,modes observer=true");
        }catch(Throwable failure){Files.writeString(output.resolve("matrix-"+count+"-incomplete.json"),RtxptJson.encode(rows));throw failure;}
    }
}
