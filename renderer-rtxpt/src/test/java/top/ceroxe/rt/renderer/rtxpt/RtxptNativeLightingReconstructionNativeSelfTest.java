package top.ceroxe.rt.renderer.rtxpt;

import java.nio.LongBuffer;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingMatrixNativeSelfTest.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Actual SDK evaluation, guide numerics, moving camera/object, reset and raw fallback. */
public final class RtxptNativeLightingReconstructionNativeSelfTest {
    static final Path OUTPUT=Path.of(System.getProperty("nativeGiReconstruction.output","build/reports/native-gi-reconstruction")).toAbsolutePath();
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
    static void sameInputs(long[] expected,long[] actual,String label){
        require(expected.length==116&&actual.length==116,"Native constants and RR parameter snapshot");
        // The built-in evaluator ignores frame-clock words. Only counter enable
        // and winner-search history differ between the diagnostic modes.
        for(int i=0;i<116;i++){
            if(i>=76&&i<80)continue;
            long a=i==75?expected[i]&22:expected[i],b=i==75?actual[i]&22:actual[i];
            require(a==b,label+" has identical semantic input word "+i+": "+a+" versus "+b);
        }
    }
    static void sameSignal(double[] expected,double[] actual,String label)throws Exception{
        if(Arrays.equals(expected,actual))return;
        long different=0;double maximum=0;int first=-1;
        for(int i=0;i<expected.length;i++)if(expected[i]!=actual[i]){
            different++;maximum=Math.max(maximum,Math.abs(expected[i]-actual[i]));if(first<0)first=i;
        }
        Files.writeString(OUTPUT.resolve(label+"-failure.json"),RtxptJson.encode(RtxptJson.object(
            "passed",false,"differentValues",different,"totalValues",expected.length,"maximumDifference",maximum,"firstDifference",first)));
        require(false,label+" is bit exact: different="+different+" maximum="+maximum);
    }
    public static void main(String[] args)throws Exception{
        Files.createDirectories(OUTPUT);
        if(args.length>0&&args[0].equals("gain")){gain();return;}
        if(args.length>0&&args[0].equals("temporal")){temporal();return;}
        if(args.length>0&&args[0].equals("instrumentation")){instrumentation();return;}
        if(args.length>0&&args[0].equals("lifecycle")){lifecycle();return;}
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
    static void lifecycle()throws Exception{
        var signals=Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.INDIRECT_DIFFUSE);
        var plan=new NativeLightingPlan(WIDTH,HEIGHT,signals,2,6,.35f,NativeLightingStyle.defaults(),SUN_OFF,null,true);
        try(var renderer=RtxptRenderers.openReconstructedNativeLighting(plan)){
            var r=(RtxptRenderer)renderer;
            r.apply(SceneTransaction.builder(1).resetScene().upsert(material(1,128,128,128)).upsert(material(2,255,0,0))
                .upsert(plane(1,1,2,0,0,0,false)).upsert(plane(2,2,4,1,0,0,false))
                .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build()).build(),
                List.of(source(1,0,0,.5,8,false)),LongBuffer.allocate(0));
            publication(r,1,new NativeLightingPublication.Instance(2,4,1,0,1,1,0,0));
            int[] widths={192,256,320,384},heights={128,160,192,224};
            long evaluations=0,deferrals=0;
            LightingFrameLease previous=null;
            try{
                for(int i=0;i<24;i++){
                    int w=widths[i%4],h=heights[i%4];boolean enabled=i%5!=3;
                    r.nativeLightingPlan(new NativeLightingPlan(w,h,signals,2,6,.35f,NativeLightingStyle.defaults(),SUN_OFF,null,true));
                    r.lightingReconstruction(enabled?NativeLightingReconstruction.DLSS_RR:NativeLightingReconstruction.NONE);
                    deferrals+=submitLifecycle(r,request(i,1,0));
                    var next=r.acquireLightingFrame().orElseThrow();
                    if(previous!=null)previous.close();previous=next;
                    var frame=next.frame();
                    require(frame.sequence()==i&&frame.sceneRevision()==1&&frame.width()==w&&frame.height()==h,
                        "Resize publishes current identity and extent while the previous generation is leased");
                    require(frame.planes().size()==2&&frame.planes().stream().allMatch(p->p.memoryHandle()!=0),"Both HDR planes remain available");
                    if(enabled)evaluations++;
                    var state=r.lightingReconstruction();
                    require(state.evaluatedFrames()==evaluations,"Each enabled frame performs one real RR evaluation");
                    require(state.actual()==(enabled?NativeLightingReconstruction.DLSS_RR:NativeLightingReconstruction.NONE),"No stale reconstruction after disabling RR");
                }
            }finally{if(previous!=null)previous.close();}
            r.nativeLightingPlan(plan);r.lightingReconstruction(NativeLightingReconstruction.DLSS_RR);
            deferrals+=submitLifecycle(r,request(24,1,0));release(r,24,1);evaluations++;r.awaitIdle();
            var raw=image(r,"lifecycle-raw",9);var rr=image(r,"lifecycle-rr",1);
            require(raw[MIDDLE]>.1,"Recreated RR receives current observable GI");
            near(rr[MIDDLE],raw[MIDDLE],raw[MIDDLE]*.35,"Recreated RR preserves GI energy");
            neutral(image(r,"lifecycle-motion",6),0,"No motion survives a resized history reset");
            require(r.lightingReconstruction().evaluatedFrames()==evaluations&&r.lightingReconstruction().guidesValid(),"Recreated guides are valid");
            require(r.nativeLightingExecution().fullBeautyFrames()==0,"Lifecycle changes never invoke beauty");
            Files.writeString(OUTPUT.resolve("lifecycle-result.json"),RtxptJson.encode(RtxptJson.object(
                "passed",true,"frames",25,"evaluations",evaluations,"sizes",4,"previousGenerationLeased",true,"disableEnable",true,"deferrals",deferrals)));
            System.out.println("NATIVE_GI_RR_LIFECYCLE PASS frames=25 evaluations="+evaluations+" sizes=4 previousGenerationLeased=true disableEnable=true deferrals="+deferrals);
        }
    }
    static long submitLifecycle(RtxptRenderer r,RenderFrameRequest frame)throws Exception{
        long deadline=System.nanoTime()+30_000_000_000L,deferrals=0;
        // Returning a lease does not imply that its producer GPU copy has
        // completed. Keep the previous generation leased while admission retries.
        while(r.trySubmit(frame) instanceof Renderer.FrameSubmissionDeferred){
            deferrals++;require(System.nanoTime()<deadline,"Lifecycle admission recovers within 30 seconds");
            Thread.sleep(1);
        }
        return deferrals;
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
            for(int variant=0;variant<6;variant++){
                var tx=SceneTransaction.builder(++revision);
                if((variant&2)!=0)tx.upsert(SceneLight.point(9000,.2,0,.5).intensity(.2f).range(2).build());
                var extra=(variant&1)==0?List.<SparseLightSource>of():List.of(source(100,0,0,.5,0,false)
                    .withComposition(SparseLightSource.Composition.VISIBLE_GROUP_MAX,30));
                if(variant==4)extra=List.of(source(1,0,0,.5,8,false).withComposition(SparseLightSource.Composition.VISIBLE_GROUP_MAX,0),
                    source(100,0,0,.5,0,false).withComposition(SparseLightSource.Composition.VISIBLE_GROUP_MAX,1));
                if(variant==5)extra=List.of(source(100,0,0,.5,0,false).withComposition(SparseLightSource.Composition.VISIBLE_GROUP_MAX,1),
                    source(101,0,0,.5,0,false).withComposition(SparseLightSource.Composition.VISIBLE_GROUP_MAX,2),
                    source(102,0,0,.5,0,false).withComposition(SparseLightSource.Composition.VISIBLE_GROUP_MAX,3));
                r.apply(tx.build(),extra,LongBuffer.allocate(0));
                // Settle both the changed transform and its following previous-
                // transform refresh. Verify the actual inputs below; a warmup
                // count alone is not evidence that the input is now stable.
                r.diagnosticLevel(RtxptDiagnostics.Level.OFF);r.lightingWorkStatisticsEnabled(false);
                for(int warmup=0;warmup<3;warmup++){
                    long warm=sequence++;r.submit(request(warm,revision,0));release(r,warm,revision);
                }
                double[][] reference=new double[8][];
                long[] referenceInputs=null;
                int[] signals={0,9,3,4,5,6,7,8};
                for(int mode=0;mode<3;mode++){
                    r.diagnosticLevel(RtxptDiagnostics.Level.values()[mode]);
                    r.lightingWorkStatisticsEnabled(mode==2);r.resetLightingReconstruction();
                    long current=sequence++;r.submit(request(current,revision,0));release(r,current,revision);
                    var inputs=r.nativeLightingInputSnapshot();
                    Files.writeString(OUTPUT.resolve("instrumentation-"+variant+"-"+mode+"-inputs.json"),
                        RtxptJson.encode(RtxptJson.object("sequence",current,"revision",revision,"words",inputs)));
                    System.out.println("RR_INPUT variant="+variant+" mode="+mode+" flags="+inputs[75]);
                    require((inputs[75]&16)==0,"Static comparison does not consume a geometry transition");
                    if(mode==0)referenceInputs=inputs;else sameInputs(referenceInputs,inputs,"Static variant "+variant);
                    for(int i=0;i<signals.length;i++){
                        var values=image(r,"instrumentation-"+variant+"-"+mode+"-"+signals[i],signals[i]);
                        if(mode==0)reference[i]=values;
                        else sameSignal(reference[i],values,"instrumentation-"+variant+"-"+mode+"-"+signals[i]);
                    }
                    var rr=image(r,"instrumentation-"+variant+"-"+mode+"-rr",1);
                    require(rr[MIDDLE]>.01,"Each counter variant performs actual reconstructed GI");
                    if(mode==2){var counts=r.lightingWorkStatistics();
                        require(counts.available()&&counts.sequence()==current&&counts.primaryRays()==WIDTH*HEIGHT
                            &&counts.bounceRays()==counts.receivers()*2,"RR uses actual current-frame shader counters");}
                }
                if((variant&1)!=0)r.apply(SceneTransaction.builder(++revision).build(),List.of(),LongBuffer.wrap(new long[]{100}));
            }
            int[] signals={0,9,3,4,5,6,7,8};
            double[][][] motionReference=new double[2][signals.length][];
            long[][] motionInputs=new long[2][];
            for(int mode=0;mode<3;mode++){
                r.diagnosticLevel(RtxptDiagnostics.Level.values()[mode]);r.lightingWorkStatisticsEnabled(mode==2);
                r.apply(SceneTransaction.builder(++revision).upsert(SceneInstance.builder(1,1).build()).build());
                r.resetLightingReconstruction();
                for(int warmup=0;warmup<3;warmup++){
                    long warm=sequence++;r.submit(request(warm,revision,0));release(r,warm,revision);
                }
                for(int motion=0;motion<2;motion++){
                    if(motion==1)r.apply(SceneTransaction.builder(++revision).upsert(SceneInstance.builder(1,1)
                        .transform(new AffineTransform(new float[]{1,0,0,.1f,0,1,0,0,0,0,1,0})).build()).build());
                    long current=sequence++;r.submit(request(current,revision,.05));release(r,current,revision);
                    String label="motion-"+motion+"-"+mode;var inputs=r.nativeLightingInputSnapshot();
                    Files.writeString(OUTPUT.resolve(label+"-inputs.json"),RtxptJson.encode(RtxptJson.object("sequence",current,"revision",revision,"words",inputs)));
                    require(((inputs[75]&16)!=0)==(motion==1),"Only the object-motion capture consumes previous geometry");
                    if(mode==0)motionInputs[motion]=inputs;else sameInputs(motionInputs[motion],inputs,label);
                    for(int i=0;i<signals.length;i++){
                        var values=image(r,label+"-"+signals[i],signals[i]);
                        if(mode==0)motionReference[motion][i]=values;else sameSignal(motionReference[motion][i],values,label+"-"+signals[i]);
                        if(signals[i]==6)require(Arrays.stream(values).anyMatch(v->Math.abs(v)>.001),"Moving camera/object has observable motion");
                    }
                    if(mode==2){var counts=r.lightingWorkStatistics();require(counts.available()&&counts.sequence()==current
                        &&counts.primaryRays()==WIDTH*HEIGHT&&counts.bounceRays()==counts.receivers()*2,"Moving RR current-frame counters");}
                }
            }
            require(sequence==51&&r.lightingReconstruction().evaluatedFrames()==51&&r.lightingReconstruction().guidesValid(),"51 actual SDK evaluations including input warmups and moving trajectories");
            require(r.nativeLightingExecution().fullBeautyFrames()==0,"No beauty execution in RR variants");
            Files.writeString(OUTPUT.resolve("instrumentation-result.json"),"{\"passed\":true,\"variants\":24,\"sdkEvaluations\":51,\"signalsAndGuidesBitExact\":true,\"semanticInputsBitExact\":true,\"cameraAndObjectMotion\":true,\"actualCounters\":true,\"inputWarmups\":27,\"fullBeautyFrames\":0}\n");
            System.out.println("NATIVE_GI_RR_INSTRUMENTATION PASS variants=24 SDK=51 inputWarmups=27 signalsAndGuidesBitExact=true semanticInputsBitExact=true moving=true actualCounters=true beauty=0");
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
