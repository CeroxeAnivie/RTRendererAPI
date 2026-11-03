package top.ceroxe.rt.renderer.rtxpt;

import java.nio.LongBuffer;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingMatrixNativeSelfTest.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Target-grid pressure with rolling numeric images, many retained resources and moving sources. */
public final class RtxptLightingPressureNativeSelfTest {
    static final int WIDTH=1281,HEIGHT=801,CENTRE=((HEIGHT/2)*WIDTH+WIDTH/2)*3;
    static final Path OUT=Path.of("build/reports/lighting-pressure").toAbsolutePath();
    static List<SparseLightSource> sources(double camera,double phase,boolean includeParticles){
        var lights=new ArrayList<SparseLightSource>();
        for(int i=0;i<64;i++)lights.add(source(i+1,camera+(i%8-3.5)*.08+.1*Math.sin(phase),
            (i/8-3.5)*.08,.5f,8f/64,false));
        if(includeParticles)for(int i=0;i<960;i++)lights.add(source(i+65,100+i,20,1,.1f,false));
        return lights;
    }
    static double gi(double camera,double phase,double albedo){
        double sum=0;
        for(int sample=0;sample<2;sample++){
            double radius=Math.sqrt((sample+.5)/2),angle=(sample+.5)*2.39996323,z=Math.sqrt(1-radius*radius);
            double t=(1-EPSILON)/z,x=camera+radius*Math.cos(angle)*t,y=radius*Math.sin(angle)*t;
            for(var light:sources(camera,phase,false)){
                double dx=x-light.x(),dy=y-light.y(),d=Math.sqrt(dx*dx+dy*dy+.25);
                sum+=light.intensity()*Math.pow(Math.max(0,1-d/8),2)/Math.max(d*d,1);
            }
        }
        return albedo*sum*.35/2;
    }
    static double[] captureImage(RtxptRenderer r,int signal)throws Exception{
        var file=OUT.resolve("rolling-"+signal+".pfm");r.captureNativeLighting(file,signal);
        var image=RtxptLightTreeNativeSelfTest.linear(file);
        require(image.length==WIDTH*HEIGHT*3,"Target lighting grid");return image;
    }
    static void frame(RtxptRenderer r,long sequence,long revision,double camera){
        var request=RenderFrameRequest.builder(sequence,2560,1600,
            CameraState.lookAt(camera,0,3,camera,0,0).verticalFieldOfViewDegrees(52).aspectRatio(1.6).build())
            .minimumSceneRevision(revision).clock(FrameClock.of(sequence*16_666_667,16_666_667)).environment(EnvironmentState.neutral()).build();
        long deadline=System.nanoTime()+30_000_000_000L;
        while(r.trySubmit(request) instanceof Renderer.FrameSubmissionDeferred){
            require(System.nanoTime()<deadline,"Bounded admission");try{Thread.sleep(1);}catch(InterruptedException e){throw new RuntimeException(e);}
        }
        try(var lease=r.acquireLightingFrame().orElseThrow()){
            require(lease.frame().sequence()==sequence&&lease.frame().sceneRevision()==revision,"Current pressure output");
        }
    }
    public static void main(String[] args)throws Exception{
        Files.createDirectories(OUT);var rows=new ArrayList<Object>();long sequence=0,revision=1;
        try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            r.nativeLightingPlan(new NativeLightingPlan(WIDTH,HEIGHT,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,
                NativeLightingPlan.Signal.INDIRECT_DIFFUSE),2,6,.35f,NativeLightingStyle.defaults(),SUN_OFF,null,true));
            var tx=SceneTransaction.builder(1).resetScene().upsert(material(1,128,128,128)).upsert(material(2,255,255,255))
                .upsert(plane(1,1,8,0,0,0,false)).upsert(plane(2,2,8,1,0,0,false))
                .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build());
            for(long id=3;id<=256;id++)tx.upsert(plane(id,1,.1f,-10,0,0,false)).upsert(SceneInstance.builder(id,id)
                .transform(new AffineTransform(new float[]{1,0,0,100+id,0,1,0,0,0,0,1,0})).build());
            r.apply(tx.build(),sources(0,0,true),LongBuffer.allocate(0));publication(r,1,new NativeLightingPublication.Instance(2,4,1,0,1,1,0,0));
            frame(r,sequence++,revision,0);r.awaitIdle();long initialBlas=r.retainedUpdateTimings().builtBlases();
            boolean particlesPresent=true;
            for(boolean mixed:new boolean[]{false,true}){
                long start=System.nanoTime(),duration=(mixed?120L:60L)*1_000_000_000L,nextSample=0;int sampled=0;long first=sequence;
                while(System.nanoTime()-start<duration){
                    double phase=sequence*.021,camera=.2*Math.sin(phase*.3);int tint=mixed&&sequence%80>=40?64:128;
                    var edit=SceneTransaction.builder(++revision);
                    if(mixed){edit.upsert(material(1,tint,tint,tint));
                        long id=3+sequence%254;edit.upsert(SceneInstance.builder(id,id).transform(new AffineTransform(new float[]{
                            1,0,0,100+id,0,1,0,(float)Math.sin(phase),0,0,1,0})).build());}
                    boolean particles=sequence%40<20;
                    var removals=new long[!particles&&particlesPresent?960:0];for(int i=0;i<removals.length;i++)removals[i]=i+65;
                    r.apply(edit.build(),sources(camera,phase,particles),LongBuffer.wrap(removals));particlesPresent=particles;frame(r,sequence++,revision,camera);
                    long elapsed=System.nanoTime()-start;
                    if(elapsed>=nextSample){
                        var indirect=captureImage(r,1);double actual=indirect[CENTRE],expected=gi(camera,phase,tint/255.0);
                        near(actual,expected,.004,"Rolling current-frame GI CPU reference");
                        near(indirect[CENTRE+1],expected,.004,"Rolling green");near(indirect[CENTRE+2],expected,.004,"Rolling blue");
                        var gain=captureImage(r,0);require(gain[CENTRE]>=1&&gain[CENTRE]<=15.65,"Current finite gain");
                        rows.add(RtxptJson.object("mixed",mixed,"seconds",elapsed/1e9,"sequence",sequence-1,"revision",revision,
                            "actualGi",actual,"expectedGi",expected,"sources",particles?1024:64,"attributes",r.lightingAttributeUpdateStatistics().toString(),
                            "resources",r.retainedUpdateTimings().toString()));sampled++;nextSample=elapsed+5_000_000_000L;
                        Files.writeString(OUT.resolve("progress.json"),RtxptJson.encode(rows));
                    }
                    Thread.sleep(8); // Bounded pressure, not a frame-rate benchmark.
                }
                require(sampled>=10,"Rolling samples cover the whole duration");
                System.out.println("LIGHTING_PRESSURE PASS mixed="+mixed+" frames="+(sequence-first)+" seconds="+(System.nanoTime()-start)/1e9+" rollingImages="+sampled);
            }
            r.awaitIdle();require(r.retainedUpdateTimings().builtBlases()==initialBlas,"Material and pose pressure preserve all resident BLAS");
            require(r.nativeLightingExecution().fullBeautyFrames()==0,"Pressure never runs full beauty");
        }
        Files.writeString(OUT.resolve("result.json"),RtxptJson.encode(RtxptJson.object("passed",true,"grid",new int[]{WIDTH,HEIGHT},
            "viewport",new int[]{2560,1600},"meshes",256,"maximumSources",1024,"giSamples",2,"dynamicSeconds",60,"mixedSeconds",120,
            "throttleMilliseconds",8,"rollingSamples",rows,"artifact",RtxptRenderer.class.getProtectionDomain().getCodeSource().getLocation().toString())));
    }
}
