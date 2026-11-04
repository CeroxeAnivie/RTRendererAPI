package top.ceroxe.rt.renderer.rtxpt;

import java.nio.*;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;
import static top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingMatrixNativeSelfTest.*;

/** Multi-group heightfields with animated shared textures and real reconstructed GI. */
public final class RtxptDynamicResourcesNativeSelfTest {
    static final Path OUTPUT=Path.of(System.getProperty("dynamicResources.output","build/reports/dynamic-resources"));
    static final boolean OBSERVE=Boolean.getBoolean("dynamicResources.observe");
    static final RtxptDiagnostics.Level LEVEL=RtxptDiagnostics.Level.valueOf(System.getProperty("dynamicResources.level","SUMMARY"));
    static final int X=16,Y=24,TRIANGLES=X*Y*2;
    static final List<Object> ROWS=new ArrayList<>();
    static final java.lang.management.ThreadMXBean THREAD_CPU=java.lang.management.ManagementFactory.getThreadMXBean();
    static long lastCpuNanos;
    static long deferredNanos,deferrals;
    static long sample(RtxptRenderer renderer,RenderFrameRequest request){
        long deadline=System.nanoTime()+60_000_000_000L;
        for(;;){long cpuStart=THREAD_CPU.getCurrentThreadCpuTime(),start=System.nanoTime();var attempt=renderer.trySubmit(request);long duration=System.nanoTime()-start;
            if(attempt instanceof Renderer.FrameSubmitted){lastCpuNanos=Math.max(0,THREAD_CPU.getCurrentThreadCpuTime()-cpuStart);return duration;}
            deferrals++;long pause=System.nanoTime();java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);deferredNanos+=System.nanoTime()-pause+duration;
            require(System.nanoTime()<deadline,"Bounded GPU output/resource backpressure recovers");
        }
    }
    static MeshAsset mesh(long id,float change){
        int vertices=(X+1)*(Y+1);float[] p=new float[vertices*3],n=new float[p.length],uv=new float[vertices*2],baseline=new float[uv.length];
        int[] color=new int[vertices],indices=new int[TRIANGLES*3];long[] materials=new long[TRIANGLES];
        float ox=(id-1)%32*2-32,oy=(id-1)/32*2-32;
        for(int y=0;y<=Y;y++)for(int x=0;x<=X;x++){int v=y*(X+1)+x;float wx=ox+x*2f/X,wy=oy+y*2f/Y;
            p[v*3]=wx;p[v*3+1]=wy;p[v*3+2]=(float)(.25*Math.sin(wx*.2)*Math.cos(wy*.2))+change;n[v*3+2]=1;
            uv[v*2]=x/(float)X;uv[v*2+1]=y/(float)Y;baseline[v*2]=.2f;baseline[v*2+1]=.8f;color[v]=0xffa0b0c0;}
        int t=0;for(int y=0;y<Y;y++)for(int x=0;x<X;x++){int a=y*(X+1)+x,b=a+1,c=a+X+1,d=c+1;
            for(int v:new int[]{a,b,d,a,d,c})indices[t++]=v;}
        for(int k=0;k<materials.length;k++)materials[k]=1+(id+k%2)%63;
        return MeshAsset.builder(id,p,indices,materials).normals(n).textureCoordinates(uv).lightmapCoordinates(baseline).vertexColorsRgba8(color).build();
    }
    static TextureAsset texture(long id,int step){
        byte[] bytes=new byte[(8*8+4*4+2*2+1)*4];Arrays.fill(bytes,(byte)255);
        int pixel=(step&63)*4;bytes[pixel]=(byte)(step*17);bytes[pixel+1]=(byte)(step*31);
        return TextureAsset.colorMipChain(id,8,8,4,bytes);
    }
    static SceneTransaction initial(int count){var tx=SceneTransaction.builder(1).resetScene();
        for(long id=1;id<=63;id++)tx.upsert(texture(id,0)).upsert(MaterialAsset.builder(id).baseColorTextureId(id).roughness(.8f).build());
        for(long id=1;id<=count;id++)tx.upsert(mesh(id,0)).upsert(SceneInstance.builder(id,id).build());return tx.build();}
    static RenderFrameRequest request(long sequence,long revision,int step){return RenderFrameRequest.builder(sequence,2560,1600,
        CameraState.lookAt(step*.002,0,36,0,0,0).verticalFieldOfViewDegrees(70).aspectRatio(1.6).build())
        .minimumSceneRevision(revision).clock(FrameClock.of(sequence*50_000_000,50_000_000)).environment(EnvironmentState.neutral()).build();}
    static void release(RtxptRenderer r,long sequence,long revision){try(var lease=r.acquireLightingFrame().orElseThrow()){
        require(lease.frame().sequence()==sequence&&lease.frame().sceneRevision()==revision,"Fresh generation, sequence and revision");}}
    static Map<String,Object> distribution(long[] values){Arrays.sort(values);return RtxptJson.object("samples",values.length,"p50Ms",values[values.length/2]/1e6,
        "p95Ms",values[Math.min(values.length-1,(int)Math.ceil(values.length*.95)-1)]/1e6,"p99Ms",values[Math.min(values.length-1,(int)Math.ceil(values.length*.99)-1)]/1e6,"maxMs",values[values.length-1]/1e6,
        "over16Ms",Arrays.stream(values).filter(v->v>16_700_000).count(),"over50Ms",Arrays.stream(values).filter(v->v>50_000_000).count(),"over100Ms",Arrays.stream(values).filter(v->v>100_000_000).count());}
    static void run(int count)throws Exception{
        long revision=1,sequence=0;var sources=new ArrayList<SparseLightSource>();
        for(int id=0;id<17408;id++)sources.add(new SparseLightSource(id,SparseLightSource.Shape.POINT,(id%132)*.5-33,(id/132)*.5-33,2,
            0,0,0,0,-1,0,1,.7f,.4f,.02f,3,2,1,-1,SparseLightSource.Composition.VISIBLE_GROUP_MAX,id%2,false,SparseLightSource.Layer.DYNAMIC));
        var plan=new NativeLightingPlan(1280,800,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.INDIRECT_DIFFUSE),2,6,.35f,NativeLightingStyle.defaults(),SUN_ON,null,true);
        try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4).copyBuilder().resources(RendererResourceOptions.high())
                .gpuTimingsEnabled(Boolean.getBoolean("dynamicResources.gpu")).build(),true)){
            r.nativeLightingPlan(plan);r.lightingReconstruction(NativeLightingReconstruction.DLSS_RR);r.diagnosticLevel(LEVEL);
            r.apply(initial(count),sources,LongBuffer.allocate(0));long begin=System.nanoTime();sample(r,request(sequence,revision,0));release(r,sequence++,revision);r.awaitIdle();
            System.out.println("DYNAMIC_INITIAL meshes="+count+" triangles="+(count*TRIANGLES)+" ms="+(System.nanoTime()-begin)/1e6);
            for(int i=0;i<6;i++){sample(r,request(sequence,revision,0));release(r,sequence++,revision);}r.awaitIdle();
            var stable=new long[12];for(int i=0;i<stable.length;i++){stable[i]=sample(r,request(sequence,revision,0));release(r,sequence++,revision);}
            int rounds=Integer.getInteger("dynamicResources.rounds",1);require(rounds>0&&rounds<=16,"Bounded measurement rounds");
            var local=new long[OBSERVE?4:16*rounds];var texture=new long[OBSERVE?4:20*rounds];var updates=new ArrayList<Object>();
            var localCpu=new long[local.length];var textureCpu=new long[texture.length];
            var before=r.lightingAttributeUpdateStatistics();var preparationBefore=r.preparationStatistics();long resets=r.lightingReconstruction().historyResets();
            for(int i=0;i<local.length;i++){
                int changed=1+i%4;var tx=SceneTransaction.builder(++revision);for(int k=0;k<changed;k++)tx.upsert(mesh(1+k,.001f*(i+1)));
                var workBefore=r.lightingAttributeUpdateStatistics();r.apply(tx.build());local[i]=sample(r,request(sequence,revision,i));
                localCpu[i]=lastCpuNanos;release(r,sequence++,revision);var after=r.lightingAttributeUpdateStatistics();
                if(!OBSERVE){require(after.baselineCorners()-workBefore.baselineCorners()==(long)changed*TRIANGLES*3,"Only changed baseline corners");
                    require(after.colorCorners()-workBefore.colorCorners()==(long)changed*TRIANGLES*3,"Only changed color corners");
                    require(after.packedCorners()-workBefore.packedCorners()==(long)changed*TRIANGLES*3,"Unchanged GPU attribute ranges are reused");}
                updates.add(RtxptJson.object("changedMeshes",changed,"convertedCorners",after.packedCorners()-workBefore.packedCorners(),"ms",local[i]/1e6));
            }
            for(int i=0;i<texture.length;i++){
                var tx=SceneTransaction.builder(++revision);for(int k=0;k<1+i%9;k++)tx.upsert(texture(1+k,i+1));r.apply(tx.build());
                texture[i]=sample(r,request(sequence,revision,i));textureCpu[i]=lastCpuNanos;release(r,sequence++,revision);
            }
            var result=RtxptJson.object("meshes",count,"triangles",count*TRIANGLES,"groupsPerMesh",2,"textures",63,"sparseSources",sources.size(),
                "hostWidth",2560,"hostHeight",1600,"lightingWidth",1280,"lightingHeight",800,"samples",2,"actualRR",r.lightingReconstruction().actual().toString(),
                "stable",distribution(stable),"meshUpdates",distribution(local),"textureUpdates",distribution(texture),"meshSubmitThreadCpu",distribution(localCpu),"textureSubmitThreadCpu",distribution(textureCpu),"meshRows",updates,
                "attributeDelta",r.lightingAttributeUpdateStatistics().packedCorners()-before.packedCorners(),"rrResetsDuringUpdates",r.lightingReconstruction().historyResets()-resets,
                "resources",r.retainedUpdateTimings().toString(),"nativeStatistics",OBSERVE?"baseline API":r.dynamicResourceStatistics().toString(),"stages",r.submitStages().toString(),
                "diagnosticLevel",LEVEL.toString(),"gpuProfiling",Boolean.getBoolean("dynamicResources.gpu"),
                "updateJavaAllocatedBytes",LEVEL==RtxptDiagnostics.Level.OFF?null:r.preparationStatistics().submitJavaAllocatedBytes()-preparationBefore.submitJavaAllocatedBytes(),
                "preparation",r.preparationStatistics().toString(),
                "deferrals",deferrals,"deferredMs",deferredNanos/1e6,"timingBoundary","accepted trySubmit only; apply, output release, initialization and explicit backpressure reported separately");
            if(!OBSERVE){require(r.dynamicResourceStatistics().deviceDrains()==0,"No device drain");if(LEVEL!=RtxptDiagnostics.Level.OFF)require(r.dynamicResourceStatistics().attributeClears()==1,"Only initial attribute clear");
                require(r.lightingReconstruction().historyResets()==resets,"Ordinary edits retain RR history");}
            require(r.nativeLightingExecution().fullBeautyFrames()==0,"No beauty execution");r.awaitIdle();ROWS.add(result);
            Files.writeString(OUTPUT.resolve("cpu-"+count+".json"),RtxptJson.encode(r.diagnosticSnapshot().events().stream().map(e->RtxptJson.object(
                "sequence",e.sequence(),"stage",e.stage().toString(),"inclusiveMs",e.inclusiveNanos()/1e6,"selfMs",e.selfNanos()/1e6,"threadCpuMs",e.threadCpuNanos()/1e6,"threadCpuSelfMs",e.threadCpuSelfNanos()/1e6)).toList()));
            Files.writeString(OUTPUT.resolve("gpu-"+count+".json"),RtxptJson.encode(r.diagnosticGpuSnapshot().scopes().stream().map(e->RtxptJson.object(
                "sequence",e.sequence(),"name",e.name(),"ms",e.inclusiveMillis())).toList()));
            Files.writeString(OUTPUT.resolve("result.json"),RtxptJson.encode(RtxptJson.object("observationOnly",OBSERVE,"rows",ROWS)));
            System.out.println("DYNAMIC_RESOURCES "+RtxptJson.encode(result));
        }
    }
    public static void main(String[] args)throws Exception{Files.createDirectories(OUTPUT);for(String count:args.length==0?new String[]{"719","977","4096"}:args)run(Integer.parseInt(count));
        System.out.println("DYNAMIC_RESOURCES_NATIVE PASS observationOnly="+OBSERVE);}
}
