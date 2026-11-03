package top.ceroxe.rt.renderer.rtxpt;

import java.nio.LongBuffer;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingMatrixNativeSelfTest.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Actual SDK evaluation, guide numerics, moving camera/object, reset and raw fallback. */
public final class RtxptNativeLightingReconstructionNativeSelfTest {
    static final Path OUTPUT=Path.of("build/reports/native-gi-reconstruction").toAbsolutePath();
    static final int WIDTH=256,HEIGHT=160,MIDDLE=((HEIGHT/2)*WIDTH+WIDTH/2)*3;
    static RenderFrameRequest request(long sequence,long revision,double cameraX){
        return RenderFrameRequest.builder(sequence,WIDTH*2,HEIGHT*2,
            CameraState.lookAt(cameraX,0,3,cameraX,0,0).verticalFieldOfViewDegrees(52).aspectRatio(1.6).build())
            .minimumSceneRevision(revision).environment(EnvironmentState.neutral())
            .clock(FrameClock.of(sequence*16_666_667,16_666_667)).build();
    }
    static double[] image(RtxptRenderer r,String name,int signal)throws Exception{
        var path=OUTPUT.resolve(name+".pfm");r.captureNativeLighting(path,signal);
        var rgb=RtxptLightTreeNativeSelfTest.linear(path);require(rgb.length==WIDTH*HEIGHT*3,"Lighting extent");
        for(double v:rgb)require(Double.isFinite(v),"Finite "+name);return rgb;
    }
    static void release(RtxptRenderer r,long sequence,long revision){
        try(var lease=r.acquireLightingFrame().orElseThrow()){
            var f=lease.frame();require(f.sequence()==sequence&&f.sceneRevision()==revision,"Fresh output identity");
            require(f.width()==WIDTH&&f.height()==HEIGHT,"All published planes retain lighting extent");
        }
    }
    public static void main(String[] args)throws Exception{
        Files.createDirectories(OUTPUT);
        if(args.length>0&&args[0].equals("gain")){gain();return;}
        if(args.length>0&&args[0].equals("temporal")){temporal();return;}
        if(args.length>0&&args[0].equals("instrumentation")){instrumentation();return;}
        var plan=new NativeLightingPlan(WIDTH,HEIGHT,EnumSet.allOf(NativeLightingPlan.Signal.class),2,6,.35f,NativeLightingStyle.defaults(),SUN_OFF,null,true);
        try(var renderer=RtxptRenderers.openReconstructedNativeLighting(plan)){
            var r=(RtxptRenderer)renderer;
            require(r.lightingReconstruction().requested()==NativeLightingReconstruction.DLSS_RR,"Reconstructed entry defaults to RR");
            require(r.lightingReconstruction().actual()==NativeLightingReconstruction.NONE,"No fabricated evaluation before GPU work");
            r.apply(SceneTransaction.builder(1).resetScene().upsert(material(1,128,128,128)).upsert(material(2,255,0,0))
                .upsert(plane(1,1,2,0,0,0,false)).upsert(plane(2,2,4,1,0,0,false))
                .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build()).build(),
                List.of(source(1,0,0,.5,8,false)),LongBuffer.allocate(0));
            publication(r,1,new NativeLightingPublication.Instance(2,4,1,0,1,1,0,0));
            for(int i=0;i<24;i++){r.submit(request(i,1,0));release(r,i,1);}
            r.awaitIdle();var state=r.lightingReconstruction();
            require(state.actual()==NativeLightingReconstruction.DLSS_RR&&state.evaluatedFrames()==24&&state.guidesValid(),"24 successful actual SDK evaluations");
            var gain=image(r,"static-gain",0);var rr=image(r,"static-rr",1);var raw=image(r,"static-raw",9);
            double reference=raw[MIDDLE];require(reference>.1,"Observable GI fixture");
            near(rr[MIDDLE],reference,reference*.35,"RR preserves diffuse GI energy");
            near(rr[MIDDLE+1],0,.02,"No invented green");near(rr[MIDDLE+2],0,.02,"No invented blue");
            var albedo=image(r,"diffuse-albedo",3);near(albedo[MIDDLE],128/255.0,.001,"Linear diffuse albedo guide");
            var spec=image(r,"specular-albedo",4);neutral(spec,0,"Diffuse layer has zero specular albedo");
            var normal=image(r,"normal-roughness",5);near(normal[MIDDLE+2],1,.001,"World normal guide");
            var mv=image(r,"static-motion",6);near(mv[MIDDLE],0,1e-6,"Static dense MV x");near(mv[MIDDLE+1],0,1e-6,"Static dense MV y");
            var depth=image(r,"hardware-depth",7);require(depth[MIDDLE]>0&&depth[MIDDLE]<1,"Finite hardware depth");
            neutral(image(r,"specular-distance",8),0,"No specular ray distance in diffuse layer");
            r.submit(request(24,1,.05));release(r,24,1);var moving=image(r,"camera-motion",6);
            require(Math.abs(moving[MIDDLE])>.001,"Motion guide includes camera translation");
            r.apply(SceneTransaction.builder(2).upsert(SceneInstance.builder(1,1).transform(new AffineTransform(new float[]{1,0,0,.1f,0,1,0,0,0,0,1,0})).build()).build());
            r.submit(request(25,2,.05));release(r,25,2);moving=image(r,"object-motion",6);
            require(Math.abs(moving[MIDDLE])>.001,"Motion guide includes object translation");
            var resetCount=r.lightingReconstruction().historyResets();r.resetLightingReconstruction();r.submit(request(26,2,.05));release(r,26,2);
            require(r.lightingReconstruction().historyResets()==resetCount+1,"Explicit reset executes once");
            r.nativeLightingPlan(plan.withEncoding(NativeLightingPlan.Encoding.COMPACT_UNORM8));r.submit(request(27,2,.05));release(r,27,2);
            require(r.lightingReconstruction().evaluatedFrames()==28,"Compact output uses reconstructed GI");
            var lastReconstructedGain=image(r,"last-reconstructed-gain",0);
            r.lightingReconstruction(NativeLightingReconstruction.NONE);r.nativeLightingPlan(plan);
            r.submit(request(28,2,.05));release(r,28,2);
            require(r.lightingReconstruction().actual()==NativeLightingReconstruction.NONE&&r.lightingReconstruction().evaluatedFrames()==28,"Raw path executes no RR");
            require(Arrays.equals(lastReconstructedGain,image(r,"raw-restored-gain",0)),"RR never modifies gain for the same scene and camera");
            require(r.nativeLightingExecution().fullBeautyFrames()==0,"No full beauty execution");
            Files.writeString(OUTPUT.resolve("result.json"),"{\"passed\":true,\"evaluations\":28,\"fullBeautyFrames\":0,\"lightingWidth\":256,\"lightingHeight\":160,\"hostWidth\":512,\"hostHeight\":320,\"rawCenter\":"+reference+",\"rrCenter\":"+rr[MIDDLE]+"}\n");
            System.out.println("NATIVE_GI_RR PASS actual=DLSS_RR evaluations=28 rawCenter="+reference+" rrCenter="+rr[MIDDLE]+" cameraAndObjectMotion=true compact=true beauty=0");
        }
    }
    static void gain()throws Exception{
        var plan=new NativeLightingPlan(WIDTH,HEIGHT,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.INDIRECT_DIFFUSE),2,6,.35f,NativeLightingStyle.defaults(),SUN_OFF,null,true);
        try(var renderer=RtxptRenderers.openReconstructedNativeLighting(plan)){
            var r=(RtxptRenderer)renderer;
            r.apply(SceneTransaction.builder(1).resetScene().upsert(material(1,128,128,128)).upsert(plane(1,1,2,0,0,0,false)).upsert(SceneInstance.builder(1,1).build()).build(),List.of(source(1,0,0,.5,8,false)),LongBuffer.allocate(0));
            r.submit(request(0,1,0));release(r,0,1);var reconstructedGain=image(r,"gain-rr-same-scene",0);
            r.lightingReconstruction(NativeLightingReconstruction.NONE);r.submit(request(1,1,0));release(r,1,1);
            require(Arrays.equals(reconstructedGain,image(r,"gain-raw-same-scene",0)),"RR leaves gain bit exact on identical scene and camera");
            require(r.lightingReconstruction().actual()==NativeLightingReconstruction.NONE&&r.lightingReconstruction().evaluatedFrames()==1,"Raw disables actual SDK evaluation");
            require(r.nativeLightingExecution().fullBeautyFrames()==0,"Layer RR never executes beauty");
            Files.writeString(OUTPUT.resolve("gain-result.json"),"{\"passed\":true,\"sameSceneAndCamera\":true,\"gainBitExact\":true,\"rawSuppressesRr\":true}\n");
            System.out.println("NATIVE_GI_RR_GAIN PASS bitExact=true sameScene=true rawActual=NONE beauty=0");
        }
    }
    static void instrumentation()throws Exception{
        var plan=new NativeLightingPlan(WIDTH,HEIGHT,EnumSet.allOf(NativeLightingPlan.Signal.class),2,6,.35f,NativeLightingStyle.defaults(),SUN_OFF,null,true);
        long sequence=0,revision=1;
        try(var renderer=RtxptRenderers.openReconstructedNativeLighting(plan)){
            var r=(RtxptRenderer)renderer;
            r.apply(SceneTransaction.builder(1).resetScene().upsert(material(1,128,128,128)).upsert(material(2,255,0,0))
                .upsert(plane(1,1,2,0,0,0,false)).upsert(plane(2,2,4,1,0,0,false))
                .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build()).build(),
                List.of(source(1,0,0,.5,8,false).withComposition(SparseLightSource.Composition.VISIBLE_GROUP_MAX,31)),LongBuffer.allocate(0));
            publication(r,1,new NativeLightingPublication.Instance(2,4,1,0,1,1,0,0));
            for(int variant=0;variant<4;variant++){
                var tx=SceneTransaction.builder(++revision);
                if((variant&2)!=0)tx.upsert(SceneLight.point(9000,.2,0,.5).intensity(.2f).range(2).build());
                var extra=(variant&1)==0?List.<SparseLightSource>of():List.of(source(100,0,0,.5,0,false)
                    .withComposition(SparseLightSource.Composition.VISIBLE_GROUP_MAX,30));
                r.apply(tx.build(),extra,LongBuffer.allocate(0));
                double[][] reference=new double[8][];
                int[] signals={0,9,3,4,5,6,7,8};
                for(int mode=0;mode<2;mode++){
                    r.lightingWorkStatisticsEnabled(mode==1);r.resetLightingReconstruction();
                    long current=sequence++;r.submit(request(current,revision,0));release(r,current,revision);
                    for(int i=0;i<signals.length;i++){
                        var values=image(r,"instrumentation-"+variant+"-"+mode+"-"+signals[i],signals[i]);
                        if(mode==0)reference[i]=values;
                        else require(Arrays.equals(reference[i],values),"RR counter mode preserves signal/guide "+signals[i]+" variant="+variant);
                    }
                    var rr=image(r,"instrumentation-"+variant+"-"+mode+"-rr",1);
                    require(rr[MIDDLE]>.01,"Each counter variant performs actual reconstructed GI");
                }
                if((variant&1)!=0)r.apply(SceneTransaction.builder(++revision).build(),List.of(),LongBuffer.wrap(new long[]{100}));
            }
            require(r.lightingReconstruction().evaluatedFrames()==8&&r.lightingReconstruction().guidesValid(),"Eight real RR variant evaluations");
            require(r.nativeLightingExecution().fullBeautyFrames()==0,"No beauty execution in RR variants");
            Files.writeString(OUTPUT.resolve("instrumentation-result.json"),"{\"passed\":true,\"variants\":8,\"sdkEvaluations\":8,\"signalsAndGuidesBitExact\":true,\"fullBeautyFrames\":0}\n");
            System.out.println("NATIVE_GI_RR_INSTRUMENTATION PASS variants=8 SDK=8 signalsAndGuidesBitExact=true beauty=0");
        }
    }
    static void temporal()throws Exception{
        var plan=new NativeLightingPlan(WIDTH,HEIGHT,EnumSet.allOf(NativeLightingPlan.Signal.class),2,6,.35f,NativeLightingStyle.defaults(),SUN_OFF,null,true);
        long newlyUncovered=0;double[] previousAlbedo=null;double maxEnergyError=0;
        try(var renderer=RtxptRenderers.openReconstructedNativeLighting(plan)){
            var r=(RtxptRenderer)renderer;
            r.apply(SceneTransaction.builder(1).resetScene().upsert(material(1,128,128,128)).upsert(material(2,255,0,0))
                .upsert(plane(1,1,.3f,0,0,0,false)).upsert(plane(2,2,4,1,0,0,false))
                .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build()).build(),
                List.of(source(1,0,0,.5,8,false)),LongBuffer.allocate(0));
            publication(r,1,new NativeLightingPublication.Instance(2,4,1,0,1,1,0,0));
            for(int i=0;i<32;i++){
                float x=(float)(.45*Math.sin(i*.22));long revision=i+2;
                r.apply(SceneTransaction.builder(revision).upsert(SceneInstance.builder(1,1).transform(new AffineTransform(new float[]{1,0,0,x,0,1,0,0,0,0,1,0})).build()).build());
                r.submit(request(i,revision,.05*Math.cos(i*.17)));release(r,i,revision);
                var rr=image(r,"temporal-current-rr",1);var raw=image(r,"temporal-current-raw",9);var albedo=image(r,"temporal-current-albedo",3);
                double sum=0,reference=0;
                for(int p=0;p<rr.length;p+=3){
                    if(albedo[p]==0){near(rr[p],0,0,"Current uncovered receiver has no historical GI");
                        if(previousAlbedo!=null&&previousAlbedo[p]>0)newlyUncovered++;}
                    sum+=rr[p];reference+=raw[p];near(rr[p+1],0,.02,"Temporal red GI invents no green");near(rr[p+2],0,.02,"Temporal red GI invents no blue");
                }
                require(reference>0,"Moving thin receiver retains actual reflected light");
                double error=Math.abs(sum-reference)/reference;maxEnergyError=Math.max(maxEnergyError,error);
                require(error<.5,"Moving-layer integrated energy remains bounded: "+error);previousAlbedo=albedo;
            }
            require(newlyUncovered>100,"Motion exercise includes real disocclusion");
            require(r.lightingReconstruction().evaluatedFrames()==32&&r.nativeLightingExecution().fullBeautyFrames()==0,"Only real native GI SDK evaluations");
            Files.writeString(OUTPUT.resolve("temporal-result.json"),RtxptJson.encode(RtxptJson.object("passed",true,"evaluations",32,
                "newlyUncoveredPixels",newlyUncovered,"maximumIntegratedEnergyError",maxEnergyError,"clearedCoverageExact",true,
                "artifact",RtxptRenderer.class.getProtectionDomain().getCodeSource().getLocation().toString())));
            System.out.println("NATIVE_GI_RR_TEMPORAL PASS frames=32 newlyUncovered="+newlyUncovered+" maxEnergyError="+maxEnergyError);
        }
    }
}
