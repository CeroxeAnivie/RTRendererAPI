package top.ceroxe.rt.renderer.rtxpt;

import java.nio.*;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;
import static top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingMatrixNativeSelfTest.SUN_ON;

/** Host-independent production fused + real GI RR, with continuously accepted resource updates. */
public final class RtxptHighFramerateBenchmark {
    static final Path OUT=Path.of(System.getProperty("highFramerate.output","build/reports/high-framerate"));
    static final int SECONDS=Integer.getInteger("highFramerate.seconds",30);
    static final int COUNT=Integer.getInteger("highFramerate.meshes",719);
    static final RtxptDiagnostics.Level LEVEL=RtxptDiagnostics.Level.valueOf(System.getProperty("highFramerate.level","SUMMARY"));
    static TextureAsset texture(long id,int frame){byte[] data=new byte[(8*8+4*4+2*2+1)*4];Arrays.fill(data,(byte)255);
        for(int i=0;i<data.length;i+=4){data[i]=(byte)(frame*13+id);if((frame&1)==0&&i/4%7==0)data[i+3]=0;}
        return TextureAsset.colorMipChain(id,8,8,4,data);}
    static void release(RtxptRenderer r,long sequence,long revision){try(var lease=r.acquireLightingFrame().orElseThrow()){
        var f=lease.frame();require(f.sequence()==sequence&&f.sceneRevision()==revision&&f.planes().size()==2,"Current dual HDR packet");}}
    static void warmup(RtxptRenderer r,RenderFrameRequest request)throws Exception{
        long deadline=System.nanoTime()+30_000_000_000L;
        while(r.trySubmit(request) instanceof Renderer.FrameSubmissionDeferred){require(System.nanoTime()<deadline,"Warmup output backpressure recovers");Thread.sleep(1);}
    }
    static Object maintenance(RtxptRenderer renderer)throws Exception{
        try{return renderer.getClass().getMethod("resourceMaintenance").invoke(renderer);}
        catch(NoSuchMethodException ignored){return "unavailable in baseline";}
    }
    public static void main(String[] args)throws Exception{
        require(SECONDS>=1&&SECONDS<=300,"Bounded measurement window");Files.createDirectories(OUT);
        String stem="production-"+COUNT+"-"+LEVEL+"-"+System.getProperty("highFramerate.identity","current");long sequence=0,revision=1;
        var rows=new ArrayList<String>();var gpu=new LinkedHashMap<String,RtxptDiagnostics.GpuScope>();
        int sourceCount=COUNT>=4096?26000:COUNT>=977?22000:17408;
        var sources=new ArrayList<SparseLightSource>(sourceCount);
        for(int i=0;i<sourceCount;i++)sources.add(new SparseLightSource(i,SparseLightSource.Shape.BOX,(i%163)*.4-32,(i/163)*.4-32,2,
            .12f,.12f,.12f,0,-1,0,1,.7f,.4f,.02f,3,2,1,-1,SparseLightSource.Composition.VISIBLE_GROUP_MAX,i%2,true,SparseLightSource.Layer.DYNAMIC));
        var plan=new NativeLightingPlan(1280,800,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.INDIRECT_DIFFUSE),2,6,.35f,NativeLightingStyle.defaults(),SUN_ON,null,true);
        try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4).copyBuilder().resources(RendererResourceOptions.high()).gpuTimingsEnabled(true).build(),true)){
            r.nativeLightingPlan(plan);r.lightingReconstruction(NativeLightingReconstruction.DLSS_RR);r.diagnosticLevel(LEVEL);
            var tx=SceneTransaction.builder(1).resetScene();for(long id=1;id<=63;id++)tx.upsert(texture(id,1)).upsert(MaterialAsset.builder(id).baseColorTextureId(id).roughness(.8f).build());
            for(long id=1;id<=COUNT;id++)tx.upsert(RtxptDynamicResourcesNativeSelfTest.mesh(id,0)).upsert(SceneInstance.builder(id,id).build());
            r.apply(tx.build(),sources,LongBuffer.allocate(0));
            for(int i=0;i<12;i++){warmup(r,RtxptDynamicResourcesNativeSelfTest.request(sequence,revision,0));release(r,sequence++,revision);}r.awaitIdle();
            var before=maintenance(r);long start=System.nanoTime(),nextUpdate=start,end=start+SECONDS*1_000_000_000L;int animation=0;long deferrals=0;
            rows.add("sequence,revision,callNanos,attemptNanos,applyNanos,releaseNanos,deferred,meshes,textures,acceptedNanos");
            while(System.nanoTime()<end){long applyNanos=0;int meshes=0,textures=0;
                if(System.nanoTime()>=nextUpdate){long a=System.nanoTime();var update=SceneTransaction.builder(++revision);animation++;
                    textures=1+animation%9;for(int k=0;k<textures;k++)update.upsert(texture(1+(animation+k)%63,animation));
                    meshes=animation%4==0?1+(animation/4)%4:0;for(int k=0;k<meshes;k++)update.upsert(RtxptDynamicResourcesNativeSelfTest.mesh(1+k,.001f*animation));
                    r.apply(update.build());applyNanos=System.nanoTime()-a;nextUpdate+=50_000_000L;
                }
                var request=RtxptDynamicResourcesNativeSelfTest.request(sequence,revision,animation);long call=System.nanoTime(),attemptNanos=0,acceptedNanos=0;int deferred=0;
                for(;;){long a=System.nanoTime();var result=r.trySubmit(request);acceptedNanos=System.nanoTime()-a;attemptNanos+=acceptedNanos;
                    if(result instanceof Renderer.FrameSubmitted)break;deferred++;deferrals++;java.util.concurrent.locks.LockSupport.parkNanos(100_000);
                    require(System.nanoTime()-call<30_000_000_000L,"Admission recovers without an unbounded wait");}
                long elapsed=System.nanoTime()-call,a=System.nanoTime();release(r,sequence,revision);long release=System.nanoTime()-a;
                rows.add(sequence+","+revision+","+elapsed+","+attemptNanos+","+applyNanos+","+release+","+deferred+","+meshes+","+textures+","+acceptedNanos);sequence++;
                if((sequence&31)==0&&LEVEL!=RtxptDiagnostics.Level.OFF)for(var scope:r.diagnosticGpuSnapshot().scopes())gpu.put(scope.nativeFrame()+":"+scope.queue()+":"+scope.event(),scope);
            }
            long acceptedDuration=System.nanoTime()-start;r.awaitIdle();long duration=System.nanoTime()-start;if(LEVEL!=RtxptDiagnostics.Level.OFF)for(var scope:r.diagnosticGpuSnapshot().scopes())gpu.put(scope.nativeFrame()+":"+scope.queue()+":"+scope.event(),scope);
            var after=maintenance(r);var rr=r.lightingReconstruction();require(rr.actual()==NativeLightingReconstruction.DLSS_RR&&rr.guidesValid(),"Real GI RR remains active");
            require(r.dynamicResourceStatistics().deviceDrains()==0,"Normal updates never idle the device");
            Files.write(OUT.resolve(stem+".csv"),rows);
            Files.writeString(OUT.resolve(stem+"-gpu.json"),RtxptJson.encode(gpu.values().stream().map(s->RtxptJson.object("frame",s.nativeFrame(),"sequence",s.sequence(),"queue",s.queue(),"name",s.name(),"start",s.beginTick(),"end",s.endTick(),"frequency",s.frequency(),"ms",s.inclusiveMillis())).toList()));
            Files.writeString(OUT.resolve(stem+".json"),RtxptJson.encode(RtxptJson.object("meshes",COUNT,"triangles",COUNT*768,"sources",sourceCount,"textures",63,"width",1280,"height",800,"seconds",duration/1e9,"acceptedWindowSeconds",acceptedDuration/1e9,"frames",rows.size()-1,"fps",(rows.size()-1)*1e9/duration,"animationBatches",animation,"deferrals",deferrals,"diagnostics",LEVEL.name(),"rr",rr.toString(),"before",before.toString(),"after",after.toString(),"resources",r.dynamicResourceStatistics().toString(),"beautyFrames",r.nativeLightingExecution().fullBeautyFrames(),"formalWindow",SECONDS>=30)));
            System.out.println("HIGH_FRAMERATE_PRODUCTION meshes="+COUNT+" level="+LEVEL+" fps="+(rows.size()-1)*1e9/duration+" updates="+animation+" maintenance="+after);
        }catch(Throwable failure){Files.write(OUT.resolve(stem+"-incomplete.csv"),rows);throw failure;}
    }
}
