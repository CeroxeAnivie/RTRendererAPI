package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaMediumDensityAbi;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaOpticalMediumAbi;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.OpticalFrameTimings;

/** Public optical HDR with independent smooth/rough dielectric references and closed-boundary controls. */
public final class CudaOpticalMediumProductionNativeSelfTest {
    private static final Path OUTPUT=Path.of(System.getProperty("top.ceroxe.rt.medium.opticalProductionDirectory","build/luxcore-native-optical-20261029/production"));
    private static final Duration TIMEOUT=Duration.ofSeconds(45);
    private static final List<String> RESULTS=new ArrayList<>();
    private static Renderer renderer;private static long sequence,revision;private static int width=37,height=29;
    private static int accepted,rejected;
    private static boolean benchmarkPolling;
    private static long submitNanos,pollNanos,pollWorkNanos,pollGapNanos;
    public static void main(String[] args)throws Exception{
        if(java.util.Arrays.asList(args).contains("--benchmark")){benchmark();return;}
        Files.createDirectories(OUTPUT);String previous=System.getProperty(CudaCameraMediumProfile.ENABLE_PROPERTY);
        System.setProperty(CudaCameraMediumProfile.ENABLE_PROPERTY,"true");
        long nativeBefore=VulkanGpuSceneFrameSubmitter.diagnosticNativeOpticalSubmissions.sum();
        try(Renderer owned=Renderer.open("vulkan-rt",RendererConfig.builder().validationEnabled(true).maxFramesInFlight(3)
                .frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F).temporalRendering(TemporalRenderingOptions.disabled()).build())){
            renderer=owned;var camera=CameraState.lookAt(0,0,6,0,0,-1).verticalFieldOfViewDegrees(5).aspectRatio((double)width/height).build();
            var budget=MediumTransport.balanced().withMaxEvents(16).withMaxDistance(30).withTransmittanceCutoff(0);
            for(int optical:new int[]{1,2,8})for(int spp:new int[]{1,2,8}){
                setupThin(1.5f);var frame=capture("thin-"+optical+"-"+spp,request(camera,spp,budget.withOpticalSamples(optical),blueSky()));
                compareThin("thin-optical"+optical+"-SPP"+spp,frame,camera,spp,1.5);
            }
            setupThin(1.5f);var exhausted=capture("event-exhaustion",request(camera,1,budget.withMaxEvents(1),blueSky()));
            checkConstant("one-event-no-unbudgeted-continuation",exhausted,0,0,0);
            setupThin(1);var equal=capture("equal-IOR",request(camera,1,budget,blueSky()));checkConstant("equal-IOR",equal,1,0,0);
            setupThin(1.5f);long points=0;double red=0,blue=0;
            var stochastic=budget.withFirstInterfaceSampling(MediumTransport.FirstInterfaceSampling.STOCHASTIC_SINGLE_BRANCH);
            for(int sample=0;sample<64;sample++){
                var frame=capture(null,request(camera,1,stochastic,blueSky()));var pixels=frame.pixels().order(ByteOrder.LITTLE_ENDIAN);
                for(int pixel=0;pixel<width*height;pixel++){red+=Float.float16ToFloat(pixels.getShort(pixel*8));blue+=Float.float16ToFloat(pixels.getShort(pixel*8+4));points++;}
            }
            near(red/points,.96/2.25,.008,"probability-compensated stochastic red mean");
            near(blue/points,.04*.675,.008,"probability-compensated stochastic reflection mean");
            RESULTS.add(String.format(Locale.ROOT,"{\"name\":\"stochastic-first-interface\",\"frames\":64,\"redMean\":%.9g,\"blueMean\":%.9g}",red/points,blue/points));
            var tilted=CameraState.lookAt(0,0,0,Math.sqrt(3),0,1).verticalFieldOfViewDegrees(1).aspectRatio((double)width/height).build();
            setupInside(1.5f);var tir=capture("inside-TIR",request(tilted,1,budget,blackSky()));checkConstant("closed-boundary-inside-TIR",tir,0,0,0);
            setupInside(1);var notTir=capture("inside-unit-IOR",request(tilted,1,budget,blackSky()));checkConstant("closed-boundary-inside-unit-IOR",notTir,1,0,0);
            setupInsideAbsorption();var straight=CameraState.lookAt(0,0,0,0,0,-1).verticalFieldOfViewDegrees(1).aspectRatio((double)width/height).build();
            var nested=capture("nested-priority-inside",request(straight,1,budget,blackSky()));
            compareNested(nested,straight);
            verifyDensityOptics(camera,budget.withScatteringSteps(64));
            verifyRoughOptics(budget);
            verifyScatteringOptics(camera,budget);
            setupThin(1.5f);apply(SceneTransaction.builder(++revision).upsert(glass(1.5f).toBuilder().transmission(.5f).build()).build());
            expectRejected(request(camera,1,budget,blueSky()));
            setupThin(1.5f);compareThin("partial-PBR-rejection-recovery",capture("recovery",request(camera,1,budget,blueSky())),camera,1,1.5);
            width=137;height=101;var resized=CameraState.lookAt(0,0,6,0,0,-1).verticalFieldOfViewDegrees(5).aspectRatio((double)width/height).build();
            compareThin("resize-eight-SPP",capture("resized",request(resized,8,budget.withOpticalSamples(2),blueSky())),resized,8,1.5);
            width=1920;height=1080;var fullHd=CameraState.lookAt(0,0,6,0,0,-1).verticalFieldOfViewDegrees(5).aspectRatio((double)width/height).build();
            compareThin("full-HD-multiple-optical-tiles",capture("full-HD",request(fullHd,1,budget.withOpticalSamples(1),blueSky())),fullHd,1,1.5);
            width=37;height=29;
            compareThin("shrink-after-full-HD",capture("shrink",request(camera,1,budget,blueSky())),camera,1,1.5);
            require(renderer.health().activeFailure().isEmpty(),"optical session unhealthy");
        }finally{if(previous==null)System.clearProperty(CudaCameraMediumProfile.ENABLE_PROPERTY);else System.setProperty(CudaCameraMediumProfile.ENABLE_PROPERTY,previous);}
        long routes=VulkanGpuSceneFrameSubmitter.diagnosticNativeOpticalSubmissions.sum()-nativeBefore;
        require(routes==accepted,"public optical frame bypassed native route: "+routes+" / "+accepted);
        Files.writeString(OUTPUT.resolve("result.json"),"{\n\"passed\":true,\n\"nativeFrames\":"+routes+",\n\"recoverableRejections\":"+rejected+",\n\"comparisons\":["+String.join(",\n",RESULTS)+"]\n}\n");
        System.out.println("CudaOpticalMediumProductionNativeSelfTest passed: comparisons="+RESULTS.size()+" actualNativeFrames="+routes+" recoverableRejections="+rejected);
    }
    /** Same immutable public HDR completion workload and budgets for both primary storage layouts. */
    private static void benchmark()throws Exception {
        width=Integer.getInteger("top.ceroxe.rt.medium.opticalBenchmarkWidth",1920);
        height=Integer.getInteger("top.ceroxe.rt.medium.opticalBenchmarkHeight",1080);
        int warmup=Integer.getInteger("top.ceroxe.rt.medium.opticalBenchmarkWarmup",32);
        int frames=Integer.getInteger("top.ceroxe.rt.medium.opticalBenchmarkFrames",120);
        int opticalSamples=Integer.getInteger("top.ceroxe.rt.medium.opticalBenchmarkOpticalSamples",1);
        require(width>0&&height>0&&warmup>=1&&frames>=8&&(opticalSamples==1||opticalSamples==8),"invalid optical benchmark configuration");
        long cap=Long.getLong("top.ceroxe.rt.medium.cuda.maxResidentBytes",CudaOpticalMediumAbi.DEFAULT_RESIDENT_BYTES);
        var plan=CudaOpticalMediumAbi.plan(width,height,1,0,CudaMediumDensityAbi.Scene.empty(),cap);
        long tiles=((long)plan.pixels()+plan.tilePixels()-1)/plan.tilePixels();
        var camera=CameraState.lookAt(0,0,6,0,0,-1).verticalFieldOfViewDegrees(5).aspectRatio((double)width/height).build();
        var budget=MediumTransport.balanced().withMaxEvents(16).withMaxDistance(30).withTransmittanceCutoff(0).withOpticalSamples(opticalSamples);
        String previous=System.getProperty(CudaCameraMediumProfile.ENABLE_PROPERTY);
        System.setProperty(CudaCameraMediumProfile.ENABLE_PROPERTY,"true");benchmarkPolling=true;Files.createDirectories(OUTPUT);
        try(Renderer owned=Renderer.open("vulkan-rt",RendererConfig.builder().validationEnabled(true).maxFramesInFlight(3)
                .frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F).temporalRendering(TemporalRenderingOptions.disabled()).build())) {
            renderer=owned;setupThin(1.5f);long coldStarted=System.nanoTime();
            var cold=capture(null,request(camera,1,budget,blueSky()));long coldNanos=System.nanoTime()-coldStarted;
            compareThin("benchmark-cold-analytic",cold,camera,1,1.5);
            for(int frame=0;frame<warmup;frame++)capture(null,request(camera,1,budget,blueSky()));
            long nativeBefore=VulkanGpuSceneFrameSubmitter.diagnosticNativeOpticalSubmissions.sum();
            long queriesBefore=CudaOpticalMediumManager.diagnosticQueries.sum(),interfacesBefore=CudaOpticalMediumManager.diagnosticInterfaces.sum();
            var roundBefore=OpticalFrameTimings.KERNEL_PHASES?OpticalFrameTimings.roundSnapshot():null;
            long[] profileBefore=OpticalFrameTimings.snapshot();long submitBefore=submitNanos,pollBefore=pollNanos;
            long pollWorkBefore=pollWorkNanos,pollGapBefore=pollGapNanos;
            var gpuBefore=renderer.diagnostics().frameGpuTiming();
            long[] nanos=new long[frames];FrameSnapshot last=null;long started=System.nanoTime();
            for(int frame=0;frame<frames;frame++) {
                long frameStarted=System.nanoTime();last=capture(null,request(camera,1,budget,blueSky()));nanos[frame]=System.nanoTime()-frameStarted;
            }
            long elapsed=System.nanoTime()-started;
            var gpuAfter=renderer.diagnostics().frameGpuTiming();
            if(OpticalFrameTimings.ENABLED) {
                long[] profileAfter=OpticalFrameTimings.snapshot();
                String[] names={"nativePrepareWallNanos","exchangeWallNanos","vulkanReleaseRecordSubmitWallNanos","cudaStageWallNanos",
                        "cudaResolveWallNanos","vulkanContinuationRecordSubmitWallNanos","vulkanContinuationFenceWaitWallNanos",
                        "vulkanPrimaryGpuNanos","vulkanContinuationGpuNanos","snapshotCpuCopyWallNanos","exchangeRounds",
                        "vulkanReleaseCommandsWallNanos","vulkanReleaseQueueSubmitWallNanos",
                        "vulkanContinuationCommandsWallNanos","vulkanContinuationQueueSubmitWallNanos",
                        "cudaPhaseDispatchCycles","cudaPhaseSegmentCycles","cudaPhaseTerminalCycles","cudaPhaseBoundaryCycles",
                        "cudaPhaseSmoothGlassCycles","cudaPhaseRoughGlassCycles","cudaPhaseContinuationCycles","cudaPhaseSamples"};
                var attribution=new StringBuilder("{\n\"scope\":\"same timed full completion window; GPU metrics overlap wall metrics\",\n\"frames\":")
                        .append(frames).append(",\n\"fullCompletionWallNanos\":").append(elapsed)
                        .append(",\n\"publicSubmitWallNanos\":").append(submitNanos-submitBefore)
                        .append(",\n\"postSubmitPollTotalWallNanos\":").append(pollNanos-pollBefore)
                        .append(",\n\"postSubmitPollWorkWallNanos\":").append(pollWorkNanos-pollWorkBefore)
                        .append(",\n\"postSubmitSpinWaitWallNanos\":").append(pollGapNanos-pollGapBefore);
                for(int i=0;i<names.length;i++)attribution.append(",\n\"").append(names[i]).append("\":").append(profileAfter[i]-profileBefore[i]);
                long gpuTotal=gpuAfter.averageTotalNanos()*gpuAfter.completedSamples()-gpuBefore.averageTotalNanos()*gpuBefore.completedSamples();
                attribution.append(",\n\"finalShadeAndGpuReadbackNanos\":").append(gpuTotal)
                        .append(",\n\"finalGpuSamples\":").append(gpuAfter.completedSamples()-gpuBefore.completedSamples())
                        .append(",\n\"kernelPhaseSamplingEnabled\":").append(OpticalFrameTimings.KERNEL_PHASES).append("\n}\n");
                Files.writeString(OUTPUT.resolve("frame-components.json"),attribution);
                if(roundBefore!=null)Files.writeString(OUTPUT.resolve("round-breakdown.json"),OpticalFrameTimings.roundDeltaJson(roundBefore));
            }
            long nativeFrames=VulkanGpuSceneFrameSubmitter.diagnosticNativeOpticalSubmissions.sum()-nativeBefore;
            long queries=CudaOpticalMediumManager.diagnosticQueries.sum()-queriesBefore,interfaces=CudaOpticalMediumManager.diagnosticInterfaces.sum()-interfacesBefore;
            long pathBudget=Math.multiplyExact((long)width*height,Math.multiplyExact((long)frames,opticalSamples));
            require(nativeFrames==frames,"optical benchmark bypassed the native route");
            require(queries==Math.multiplyExact(pathBudget,4)&&interfaces==Math.multiplyExact(pathBudget,2),
                    "optical benchmark did not execute every declared sample and branch");
            require(renderer.health().activeFailure().isEmpty(),"optical benchmark session unhealthy");
            compareThin("benchmark-final-analytic",last,camera,1,1.5);
            byte[] image=new byte[last.byteCount()];last.pixels().get(image);Files.write(OUTPUT.resolve("benchmark-final.rgba16f"),image);
            long[] sorted=nanos.clone();java.util.Arrays.sort(sorted);
            double median=(sorted[(frames-1)/2]/2.0+sorted[frames/2]/2.0)/1e6,p95=sorted[(int)Math.ceil(frames*.95)-1]/1e6;
            var json=new StringBuilder(String.format(Locale.ROOT,
                    "{\n  \"passed\":true,\n  \"externalPrimary\":%s,\n  \"width\":%d,\n  \"height\":%d,\n  \"samplesPerPixel\":1,\n  \"opticalSamples\":%d,\n  \"maxEvents\":16,\n  \"maxDistance\":30,\n  \"transmittanceCutoff\":0,\n  \"firstInterfaceSampling\":\"DETERMINISTIC_SPLIT\",\n  \"validationEnabled\":true,\n  \"temporalEnabled\":false,\n  \"output\":\"immutable CPU FrameSnapshot RGBA16F\",\n  \"cudaResidentLimitBytes\":%d,\n  \"sharedBytes\":%d,\n  \"externalPrimaryBytes\":%d,\n  \"tilePixels\":%d,\n  \"tiles\":%d,\n  \"warmupFrames\":%d,\n  \"completedFrames\":%d,\n  \"nativeFrames\":%d,\n  \"queryPaths\":%d,\n  \"opticalInterfaces\":%d,\n  \"coldFirstFrameMs\":%.6f,\n  \"batchWallMs\":%.6f,\n  \"completedFramesPerSecond\":%.6f,\n  \"medianEndToEndMs\":%.6f,\n  \"p95EndToEndMs\":%.6f,\n  \"frameNanos\":[",
                    plan.externalPrimary(),width,height,opticalSamples,Math.min(cap,CudaOpticalMediumAbi.DEFAULT_RESIDENT_BYTES),plan.bytes(),
                    plan.externalPrimary()?plan.primaryBytes():0,plan.tilePixels(),tiles,warmup,frames,nativeFrames,queries,interfaces,
                    coldNanos/1e6,elapsed/1e6,frames*1e9/elapsed,median,p95));
            json.insert(2,String.format(Locale.ROOT,"  \"directPrimary\":%s,\n  \"compactHits\":%s,\n  \"compactStates\":%s,\n  \"mutableHitBytes\":%d,\n  \"mutableStateBytes\":%d,\n  \"reducedCounters\":%s,\n",plan.directPrimary(),plan.compactHits(),plan.compactStates(),plan.hitBytes(),plan.stateBytes(),CudaOpticalMediumAbi.reducedCounters()));
            json.insert(2,"  \"minimalStateWrites\":"+CudaOpticalMediumAbi.minimalStateWrites()+",\n");
            json.insert(2,"  \"transposedHits\":"+plan.transposedHits()+",\n");
            json.insert(2,"  \"transposedStates\":"+plan.transposedStates()+",\n");
            for(int frame=0;frame<frames;frame++){if(frame>0)json.append(',');json.append(nanos[frame]);}
            json.append("],\n  \"comparisons\":[").append(String.join(",",RESULTS)).append("]\n}\n");
            Files.writeString(OUTPUT.resolve("benchmark.json"),json);
            System.out.printf(Locale.ROOT,"Optical benchmark externalPrimary=%s extent=%dx%d SPP=1 opticalSamples=%d completed=%d tiles=%d FPS=%.6f medianMs=%.6f p95Ms=%.6f%n",
                    plan.externalPrimary(),width,height,opticalSamples,frames,tiles,frames*1e9/elapsed,median,p95);
        }finally {
            benchmarkPolling=false;if(previous==null)System.clearProperty(CudaCameraMediumProfile.ENABLE_PROPERTY);else System.setProperty(CudaCameraMediumProfile.ENABLE_PROPERTY,previous);
        }
    }
    private static MaterialAsset glass(float ior){return MaterialAsset.builder(2).transmission(1).roughness(0).indexOfRefraction(ior).doubleSided(true).build();}
    private static void verifyScatteringOptics(CameraState camera,MediumTransport budget)throws Exception {
        var direct=budget.withOpticalSamples(8).withScatteringBounces(0);
        var absorber=new Rgb(.12f,.16f,.20f);var scatter=new Rgb(.1f,.1f,.1f);
        var medium=new Medium(absorber,scatter,Rgb.ZERO,1,1,0);
        var dark=blackSky().toBuilder().cameraMedium(medium).build();
        setupThin(1);
        apply(SceneTransaction.builder(++revision).upsert(receiver().toBuilder().baseColorRgba8(0xff000000).build())
                .upsert(SceneLight.directional(30,1,0,0).color(1,1,1).intensity(3).castsShadow(false).build()).build());
        var analytic=capture("scattering-equal-IOR-analytic",request(camera,8,direct,dark));
        double[] mean=meanRgb(analytic);
        double[] extinction={.22,.26,.30};
        for(int c=0;c<3;c++){double expected=.1/extinction[c]*(-Math.expm1(-extinction[c]*10))*3/(4*Math.PI);
            near(mean[c],expected,.003,"single-scattering equal-IOR optical limit channel="+c);}
        apply(SceneTransaction.builder(++revision).upsert(glass(1.5f).toBuilder().roughness(.45f).build()).build());
        var combined=capture("scattering-rough-glass-lit",request(camera,8,direct.withScatteringBounces(2),dark));
        double lit=energy(combined);require(lit>.01,"rough glass plus scattering must deliver real light energy");
        apply(SceneTransaction.builder(++revision).removeLight(30).build());
        var unlit=capture("scattering-rough-glass-dark",request(camera,8,direct.withScatteringBounces(2),dark));
        near(energy(unlit),0,1e-6,"rough glass scattering respects disabled lighting");
        setupThin(1.5f);
        apply(SceneTransaction.builder(++revision).upsert(glass(1.5f).toBuilder().roughness(.45f).build()).build());
        var absorption=blueSky().toBuilder().cameraMedium(new Medium(absorber,Rgb.ZERO,Rgb.ZERO,1,1,0)).build();
        // Compare independently sampled means in the sigmaS -> 0 limit.
        var reference=capture("rough-glass-absorption-limit",request(camera,8,direct,absorption));
        var tiny=absorption.toBuilder().cameraMedium(new Medium(absorber,new Rgb(1e-8f,1e-8f,1e-8f),Rgb.ZERO,1,1,0)).build();
        var limit=capture("rough-glass-tiny-scattering",request(camera,8,direct,tiny));
        // Different frame seeds change rough samples; compare their independently sampled means.
        var a=meanRgb(reference);var b=meanRgb(limit);
        for(int c=0;c<3;c++)near(b[c],a[c],.003,"rough-glass sigmaS limit channel="+c);
        RESULTS.add(String.format(Locale.ROOT,"{\"name\":\"scattering-rough-glass\",\"analyticRedMean\":%.9g,\"litEnergy\":%.9g,\"darkEnergy\":%.9g}",mean[0],lit,energy(unlit)));
    }
    private static double[] meanRgb(FrameSnapshot frame){
        var data=frame.pixels().order(ByteOrder.LITTLE_ENDIAN);double[] mean=new double[3];
        for(int pixel=0;pixel<width*height;pixel++){
            for(int c=0;c<3;c++){double value=Float.float16ToFloat(data.getShort(pixel*8+c*2));
                require(Double.isFinite(value)&&value>=0,"combined transport finite nonnegative HDR");mean[c]+=value/(width*height);}
            near(Float.float16ToFloat(data.getShort(pixel*8+6)),1,0,"combined transport alpha");
        }return mean;
    }
    private static double energy(FrameSnapshot frame){var mean=meanRgb(frame);return mean[0]+mean[1]+mean[2];}
    private static void verifyRoughOptics(MediumTransport budget)throws Exception {
        var normal=CameraState.lookAt(0,0,6,0,0,-1).verticalFieldOfViewDegrees(.005).aspectRatio((double)width/height).build();
        var roughBudget=budget.withOpticalSamples(8);
        for(float roughness:new float[]{.12f,.45f,.85f}) {
            setupThin(1.5f);apply(SceneTransaction.builder(++revision).upsert(glass(1.5f).toBuilder().roughness(roughness).build()).build());
            compareRoughMean("rough-"+roughness,capture("rough-"+roughness,request(normal,8,roughBudget,blueSky())),roughness,false);
        }
        setupThin(1.5f);apply(SceneTransaction.builder(++revision).upsert(glass(1.5f).toBuilder().roughness(.45f).build()).build());
        compareRoughMean("rough-stochastic",capture("rough-stochastic",request(normal,8,
                roughBudget.withFirstInterfaceSampling(MediumTransport.FirstInterfaceSampling.STOCHASTIC_SINGLE_BRANCH),blueSky())),.45,false);
        setupThin(1.5f);apply(SceneTransaction.builder(++revision).upsert(glass(1.5f).toBuilder().roughness(.45f).baseColorRgba8(0xff000000).build()).build());
        compareRoughMean("rough-black-transmission",capture("rough-black",request(normal,8,roughBudget,blueSky())),.45,true);
        setupThin(1.5f);apply(SceneTransaction.builder(++revision).upsert(glass(1.5f).toBuilder().roughness(.0001f).build()).build());
        compareThin("rough-smooth-limit",capture("rough-smooth-limit",request(normal,8,roughBudget,blueSky())),normal,8,1.5);
        setupThin(1);apply(SceneTransaction.builder(++revision).upsert(glass(1).toBuilder().roughness(.85f).build()).build());
        checkConstant("rough-equal-IOR",capture("rough-equal-IOR",request(normal,2,budget,blueSky())),1,0,0);
    }
    private static void compareRoughMean(String name,FrameSnapshot frame,double roughness,boolean blackTint) {
        double[] mean=new double[3];var pixels=frame.pixels().order(ByteOrder.LITTLE_ENDIAN);
        for(int pixel=0;pixel<width*height;pixel++) {
            for(int c=0;c<3;c++) {double value=Float.float16ToFloat(pixels.getShort(pixel*8+c*2));
                require(Double.isFinite(value)&&value>=0,name+" finite nonnegative HDR");mean[c]+=value/(width*height);}
            near(Float.float16ToFloat(pixels.getShort(pixel*8+6)),1,0,name+" alpha");
        }
        // Integrate the microfacet normal measure in double precision. At normal incidence the
        // azimuthal sky term integrates to .675; no renderer RNG, CUDA packing or hash is reused.
        int bins=131072;double expectedRed=0,expectedBlue=0,r=roughness*roughness,eta=1/1.5;
        for(int bin=0;bin<bins;bin++) {
            double u=(bin+.5)/bins,c=Math.sqrt(u/(r+(1-r)*u)),ct=Math.sqrt(1-eta*eta*(1-c*c));
            double f=.5*(Math.pow((c-1.5*ct)/(c+1.5*ct),2)+Math.pow((1.5*c-ct)/(1.5*c+ct),2));
            double tz=(eta*c-ct)*c-eta,gt=Math.abs(tz)/(Math.abs(tz)*(1-r)+r);
            expectedRed+=gt*c*(1-f)*eta*eta/bins;
            double rz=2*c*c-1;if(rz>0)expectedBlue+=.675*(rz/(rz*(1-r)+r))*c*f/bins;
        }
        if(blackTint)expectedRed=0;
        near(mean[0],expectedRed,.004,name+" independently integrated transmission");
        near(mean[1],0,.0001,name+" green");near(mean[2],expectedBlue,.003,name+" independently integrated reflection");
        RESULTS.add(String.format(Locale.ROOT,"{\"name\":\"%s\",\"redMean\":%.9g,\"expectedRed\":%.9g,\"blueMean\":%.9g,\"expectedBlue\":%.9g}",name,mean[0],expectedRed,mean[2],expectedBlue));
    }
    private static void verifyDensityOptics(CameraState camera,MediumTransport budget)throws Exception {
        var medium=new Medium(new Rgb(.13f,.2f,.31f),Rgb.ZERO,new Rgb(.011f,.017f,.023f),1.2f,1,0)
                .withDensityField(MediumDensityField.grid(50,4,new AffineTransform(new float[]{.005f,0,0,.5f,0,.005f,0,.5f,0,0,.1f,.5f})));
        for(int generation=0;generation<2;generation++) {
            setupThin(1.5f);byte[] rgba=new byte[16];
            for(int z=0;z<4;z++){rgba[z*4]=(byte)(generation==0?32+64*z:224-64*z);rgba[z*4+3]=(byte)255;}
            var texture=TextureAsset.builder(50,1,4).colorSpace(TextureAsset.ColorSpace.LINEAR).pixelsRgba8(rgba).build();
            apply(SceneTransaction.builder(++revision).upsert(texture).build());
            long uploadsBefore=CudaOpticalMediumManager.diagnosticAssetUploads.sum();
            long bytesBefore=CudaOpticalMediumManager.diagnosticUploadedBytes.sum();
            for(int spp:new int[]{1,2,8}) {
                var frame=capture("density-glass-"+generation+"-"+spp,request(camera,spp,budget,blueSky().toBuilder().cameraMedium(medium).build()));
                if(spp==1) {
                    var density=CudaMediumDensityAbi.prepare(medium,List.of(),id->texture);
                    require(CudaOpticalMediumManager.diagnosticAssetUploads.sum()==uploadsBefore+1,
                            "new optical density generation was not uploaded");
                    require(CudaOpticalMediumManager.diagnosticUploadedBytes.sum()-bytesBefore
                                    ==2L*CudaOpticalMediumAbi.HEADER_BYTES+density.bytes(),
                            "new optical density generation did not upload its complete immutable atlas");
                }
                var pixels=frame.pixels().order(ByteOrder.LITTLE_ENDIAN);double maximum=0;
                for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
                    double[] expected=new double[3];
                    for(int sample=0;sample<spp;sample++) {
                        double[] d=direction(camera,x,y,sample,spp);double ci=-d[2],ct=Math.sqrt(1-(1-ci*ci)/2.25);
                        double f=.5*(Math.pow((ci-1.5*ct)/(ci+1.5*ct),2)+Math.pow((1.5*ci-ct)/(1.5*ci+ct),2));
                        double prefix=densityMass(0,6,generation)/ci,reflected=densityMass(0,5,generation)/ci;
                        double transmitted=densityMass(-4,0,generation)/ct;
                        double[] a={.13*1.2,.2*1.2,.31*1.2},e={.011*1.2,.017*1.2,.023*1.2};
                        for(int c=0;c<3;c++) {
                            double t=Math.exp(-a[c]*prefix),r=Math.exp(-a[c]*reflected),v=Math.exp(-a[c]*transmitted);
                            double sky=c==2?.675+.325*d[1]:0,receiver=c==0?1:0;
                            expected[c]+=(e[c]*(1-t)/a[c]+t*(f*(e[c]*(1-r)/a[c]+r*sky)
                                    +(1-f)/2.25*(e[c]*(1-v)/a[c]+v*receiver)))/spp;
                        }
                    }
                    for(int c=0;c<3;c++) {double actual=Float.float16ToFloat(pixels.getShort((y*width+x)*8+c*2));
                        maximum=Math.max(maximum,Math.abs(actual-expected[c]));near(actual,expected[c],.0015,"density glass analytic refraction/emission");}
                }
                RESULTS.add(String.format(Locale.ROOT,"{\"name\":\"density-glass-generation%d-SPP%d\",\"maximumAbsoluteError\":%.9g}",generation,spp,maximum));
            }
            long cachedUploads=CudaOpticalMediumManager.diagnosticAssetUploads.sum();
            long cachedBytes=CudaOpticalMediumManager.diagnosticUploadedBytes.sum();
            var cachedA=capture(null,request(camera,8,budget,blueSky().toBuilder().cameraMedium(medium).build()));
            var cachedB=capture(null,request(camera,8,budget,blueSky().toBuilder().cameraMedium(medium).build()));
            require(cachedA.pixels().equals(cachedB.pixels()),"cached optical density changed the completed HDR output");
            require(CudaOpticalMediumManager.diagnosticAssetUploads.sum()==cachedUploads,
                    "unchanged optical density generation was uploaded again");
            require(CudaOpticalMediumManager.diagnosticUploadedBytes.sum()-cachedBytes==4L*CudaOpticalMediumAbi.HEADER_BYTES,
                    "unchanged optical density frames must upload only their per-frame headers");
            RESULTS.add(String.format(Locale.ROOT,"{\"name\":\"density-cache-generation%d\",\"assetUploads\":0,\"metadataBytes\":%d}",
                    generation,4L*CudaOpticalMediumAbi.HEADER_BYTES));
        }
        setupInsideAbsorption();byte[] rgba=new byte[16];for(int z=0;z<4;z++){rgba[z*4]=(byte)(32+64*z);rgba[z*4+3]=(byte)255;}
        var field=MediumDensityField.grid(50,4,new AffineTransform(new float[]{.5f,0,0,.5f,0,.5f,0,.5f,0,0,.5f,.5f}));
        var a=new Medium(new Rgb(.1f,.2f,.3f),Rgb.ZERO,new Rgb(.01f,.02f,.03f),1,1,0).withDensityField(field);
        var b=new Medium(new Rgb(.3f,.1f,.2f),Rgb.ZERO,new Rgb(.03f,.01f,.02f),1,1,0).withDensityField(field);
        var boundaryTexture=TextureAsset.builder(50,1,4).colorSpace(TextureAsset.ColorSpace.LINEAR).pixelsRgba8(rgba).build();
        var innerTransform=new AffineTransform(new float[]{100,0,0,0,0,100,0,0,0,0,2,0});
        var outerTransform=new AffineTransform(new float[]{100,0,0,0,0,100,0,0,0,0,1,0});
        var innerBoundary=MediumBoundary.surface(a).withPriority(1);var outerBoundary=MediumBoundary.surface(b).withPriority(8);
        apply(SceneTransaction.builder(++revision).upsert(boundaryTexture)
                .upsert(SceneInstance.builder(2,2).transform(innerTransform).mediumBoundary(innerBoundary).build())
                .upsert(SceneInstance.builder(3,2).transform(outerTransform).mediumBoundary(outerBoundary).build()).build());
        var straight=CameraState.lookAt(0,0,0,0,0,-1).verticalFieldOfViewDegrees(1).aspectRatio((double)width/height).build();
        var localDensity=CudaMediumDensityAbi.prepare(Medium.vacuum(),List.of(
                new top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaBoundaryMediumAbi.Boundary(2,innerBoundary,innerTransform),
                new top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaBoundaryMediumAbi.Boundary(3,outerBoundary,outerTransform)),id->boundaryTexture);
        int completeSceneBytes=CudaOpticalMediumAbi.plan(width,height,2,2,localDensity,
                Long.getLong("top.ceroxe.rt.medium.cuda.maxResidentBytes",CudaOpticalMediumAbi.DEFAULT_RESIDENT_BYTES)).sceneBytes();
        long uploadBefore=CudaOpticalMediumManager.diagnosticAssetUploads.sum();
        long bytesBefore=CudaOpticalMediumManager.diagnosticUploadedBytes.sum();
        var nested=capture("nested-local-density",request(straight,2,budget,blackSky()));var pixels=nested.pixels().order(ByteOrder.LITTLE_ENDIAN);
        require(CudaOpticalMediumManager.diagnosticAssetUploads.sum()==uploadBefore+1,
                "new optical boundary identity set was not uploaded");
        require(CudaOpticalMediumManager.diagnosticUploadedBytes.sum()-bytesBefore
                        ==(long)completeSceneBytes+CudaOpticalMediumAbi.HEADER_BYTES,
                "optical boundary table and density atlas were not uploaded as a complete generation");
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)for(int c=0;c<3;c++) {
            double expected=0;for(int sample=0;sample<2;sample++) {
                double cosine=-direction(straight,x,y,sample,2)[2],mb=densityMass(-5,0,0)*.2/cosine,ma=densityMass(-5,-2.5,0)*.4/cosine;
                double[] ac={.1,.2,.3},bc={.3,.1,.2},ea={.01,.02,.03},eb={.03,.01,.02};double tb=Math.exp(-bc[c]*mb),ta=Math.exp(-ac[c]*ma);
                expected+=((c==0?1:0)*tb*ta+eb[c]*(1-tb)/bc[c]+tb*ea[c]*(1-ta)/ac[c])/2;
            }
            near(Float.float16ToFloat(pixels.getShort((y*width+x)*8+c*2)),expected,.0015,"local optical density priority and affine transform");
        }
        RESULTS.add("{\"name\":\"nested-local-density-priority-two-SPP\"}");
        var movedTransform=new AffineTransform(new float[]{100,0,0,1000,0,100,0,0,0,0,2,0});
        long transformUploads=CudaOpticalMediumManager.diagnosticAssetUploads.sum();long transformBytes=CudaOpticalMediumManager.diagnosticUploadedBytes.sum();
        apply(SceneTransaction.builder(++revision).upsert(SceneInstance.builder(2,2).transform(movedTransform)
                .mediumBoundary(innerBoundary).build()).build());
        var moved=capture(null,request(straight,2,budget,blackSky()));
        require(!nested.pixels().equals(moved.pixels()),"moving an optical boundary did not change completed radiance");
        require(CudaOpticalMediumManager.diagnosticAssetUploads.sum()==transformUploads+1
                        &&CudaOpticalMediumManager.diagnosticUploadedBytes.sum()-transformBytes
                                ==(long)completeSceneBytes+CudaOpticalMediumAbi.HEADER_BYTES,
                "optical boundary transform change did not upload a complete asset generation");
        long metadataUploads=CudaOpticalMediumManager.diagnosticAssetUploads.sum();long metadataBytes=CudaOpticalMediumManager.diagnosticUploadedBytes.sum();
        var reprioritized=MediumBoundary.surface(b).withPriority(7);
        apply(SceneTransaction.builder(++revision).upsert(SceneInstance.builder(3,2).transform(outerTransform)
                .mediumBoundary(reprioritized).build()).build());
        var reprioritizedFrame=capture(null,request(straight,2,budget,blackSky()));
        require(CudaOpticalMediumManager.diagnosticAssetUploads.sum()==metadataUploads+1
                        &&CudaOpticalMediumManager.diagnosticUploadedBytes.sum()-metadataBytes
                                ==(long)completeSceneBytes+CudaOpticalMediumAbi.HEADER_BYTES,
                "optical boundary priority metadata change did not upload a complete asset generation");
        long identityUploads=CudaOpticalMediumManager.diagnosticAssetUploads.sum();long identityBytes=CudaOpticalMediumManager.diagnosticUploadedBytes.sum();
        apply(SceneTransaction.builder(++revision).removeInstance(3).upsert(SceneInstance.builder(4,2).transform(outerTransform)
                .mediumBoundary(reprioritized).build()).build());
        var replacedFrame=capture(null,request(straight,2,budget,blackSky()));
        require(reprioritizedFrame.pixels().equals(replacedFrame.pixels()),
                "same native boundary slot and coefficients must preserve completed radiance after identity replacement");
        require(CudaOpticalMediumManager.diagnosticAssetUploads.sum()==identityUploads
                        &&CudaOpticalMediumManager.diagnosticUploadedBytes.sum()-identityBytes
                                ==2L*CudaOpticalMediumAbi.HEADER_BYTES,
                "a reused native slot with identical immutable assets must upload only frame headers");

        width=137;height=101;var resizedCamera=CameraState.lookAt(0,0,0,0,0,-1).verticalFieldOfViewDegrees(1)
                .aspectRatio((double)width/height).build();
        long recreations=CudaOpticalMediumManager.diagnosticStorageRecreations.sum();
        long resizeUploads=CudaOpticalMediumManager.diagnosticAssetUploads.sum();long resizeBytes=CudaOpticalMediumManager.diagnosticUploadedBytes.sum();
        capture(null,request(resizedCamera,2,budget,blackSky()));
        require(CudaOpticalMediumManager.diagnosticStorageRecreations.sum()>recreations,
                "optical resize did not exercise storage recreation");
        require(CudaOpticalMediumManager.diagnosticAssetUploads.sum()==resizeUploads+1
                        &&CudaOpticalMediumManager.diagnosticUploadedBytes.sum()-resizeBytes
                                ==(long)completeSceneBytes+CudaOpticalMediumAbi.HEADER_BYTES,
                "recreated optical storage did not receive the complete boundary and density assets");
        width=37;height=29;
    }
    // Exact integral of a one-dimensional, texel-centered piecewise-linear field.
    // This reference does not use the CUDA midpoint integrator or its descriptor packing.
    private static double densityMass(double first,double last,int generation) {
        double total=0;double[] knots={-5,-3.75,-1.25,1.25,3.75,5};
        for(int i=0;i<knots.length-1;i++) {double lo=Math.max(first,knots[i]),hi=Math.min(last,knots[i+1]);
            if(hi<=lo)continue;double value=Math.clamp(((lo+hi)/2+5)/2.5-.5,0,3);
            total+=(hi-lo)*(generation==0?32+64*value:224-64*value)/255;}
        return total;
    }
    private static MaterialAsset receiver(){return MaterialAsset.builder(1).shadingModel(MaterialAsset.ShadingModel.UNLIT).baseColorRgba8(0xff0000ff).doubleSided(true).build();}
    private static MeshAsset plane(long id,long material,float z,boolean reverse){return MeshAsset.triangles(id,
            new float[]{-100,-100,z,100,-100,z,100,100,z,-100,100,z},reverse?new int[]{0,2,1,0,3,2}:new int[]{0,1,2,2,3,0},material);}
    private static void setupThin(float ior)throws Exception{apply(SceneTransaction.builder(++revision).resetScene().upsert(receiver()).upsert(glass(ior))
            .upsert(plane(1,1,-4,false)).upsert(plane(2,2,0,false)).upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build()).build());}
    private static void setupInside(float ior)throws Exception{var medium=new Medium(Rgb.ZERO,Rgb.ZERO,Rgb.ZERO,0,ior,0);
        apply(SceneTransaction.builder(++revision).resetScene().upsert(receiver()).upsert(glass(1.5f)).upsert(plane(1,1,3,true))
                .upsert(MediumTransportSelfTest.cube(2,2,1)).upsert(SceneInstance.builder(1,1).build())
                .upsert(SceneInstance.builder(2,2).transform(new AffineTransform(new float[]{100,0,0,0,0,100,0,0,0,0,1,0})).mediumBoundary(MediumBoundary.surface(medium)).build()).build());}
    private static void setupInsideAbsorption()throws Exception{
        var a=new Medium(new Rgb(.1f,.2f,.3f),Rgb.ZERO,new Rgb(.01f,.02f,.03f),1,1,0);
        var b=new Medium(new Rgb(.3f,.1f,.2f),Rgb.ZERO,new Rgb(.03f,.01f,.02f),1,1,0);
        apply(SceneTransaction.builder(++revision).resetScene().upsert(receiver()).upsert(glass(1.5f)).upsert(plane(1,1,-4,false))
                .upsert(MediumTransportSelfTest.cube(2,2,1)).upsert(SceneInstance.builder(1,1).build())
                .upsert(SceneInstance.builder(2,2).transform(new AffineTransform(new float[]{100,0,0,0,0,100,0,0,0,0,2,0})).mediumBoundary(MediumBoundary.surface(a).withPriority(1)).build())
                .upsert(SceneInstance.builder(3,2).transform(new AffineTransform(new float[]{100,0,0,0,0,100,0,0,0,0,1,0})).mediumBoundary(MediumBoundary.surface(b).withPriority(8)).build()).build());
    }
    private static void compareThin(String name,FrameSnapshot frame,CameraState camera,int samples,double ior){
        var pixels=frame.pixels().order(ByteOrder.LITTLE_ENDIAN);double max=0;
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){double expectedR=0,expectedB=0;
            for(int sample=0;sample<samples;sample++){double[] d=direction(camera,x,y,sample,samples),eta= new double[]{1/ior};
                double ci=-d[2],sine=eta[0]*Math.sqrt(Math.max(0,1-ci*ci)),ct=Math.sqrt(Math.max(0,1-sine*sine));
                double f=ior==1?0:.5*(Math.pow((ci-ior*ct)/(ci+ior*ct),2)+Math.pow((ior*ci-ct)/(ior*ci+ct),2));
                expectedR+=(1-f)*eta[0]*eta[0]/samples;expectedB+=f*(.675+.325*d[1])/samples;
            }
            double r=Float.float16ToFloat(pixels.getShort((y*width+x)*8)),b=Float.float16ToFloat(pixels.getShort((y*width+x)*8+4));
            max=Math.max(max,Math.max(Math.abs(r-expectedR),Math.abs(b-expectedB)));
            near(r,expectedR,.0015,name+" red "+x+","+y);near(b,expectedB,.0015,name+" reflection blue "+x+","+y);
        }
        RESULTS.add(String.format(Locale.ROOT,"{\"name\":\"%s\",\"maximumAbsoluteError\":%.9g}",name,max));
    }
    private static void compareNested(FrameSnapshot frame,CameraState camera){var pixels=frame.pixels().order(ByteOrder.LITTLE_ENDIAN);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){double d=1/-direction(camera,x,y,0,1)[2];
            double[] a={.1,.2,.3},b={.3,.1,.2},eA={.01,.02,.03},eB={.03,.01,.02};
            for(int c=0;c<3;c++){double tB=Math.exp(-b[c]*d),tA=Math.exp(-a[c]*d);
                double expected=(c==0?1:0)*tB*tA+eB[c]*(1-tB)/b[c]+tB*eA[c]*(1-tA)/a[c];
                near(Float.float16ToFloat(pixels.getShort((y*width+x)*8+c*2)),expected,.0015,"nested priority absorption/emission");}
        }RESULTS.add("{\"name\":\"nested-priority-inside-HDR\"}");}
    private static double[] direction(CameraState camera,int x,int y,int sample,int samples){
        double[][] offsets=samples==1?new double[][]{{.5,.5}}:samples==2?new double[][]{{.25,.25},{.75,.75}}:
                new double[][]{{.5,.3333},{.25,.6667},{.75,.1111},{.125,.4444},{.625,.7778},{.375,.2222},{.875,.5556},{.0625,.8889}};
        double dx=((x+offsets[sample][0])/width*2-1)*camera.tanHalfFovX(),dy=-((y+offsets[sample][1])/height*2-1)*camera.tanHalfFovY();
        double norm=Math.sqrt(dx*dx+dy*dy+1);return new double[]{dx/norm,dy/norm,-1/norm};
    }
    private static EnvironmentState blueSky(){return EnvironmentState.builder().skyRadiance(0,0,1).ambientIntensity(1).sunIntensity(0).build();}
    private static EnvironmentState blackSky(){return EnvironmentState.builder().skyRadiance(0,0,0).ambientIntensity(0).sunIntensity(0).build();}
    private static RenderFrameRequest request(CameraState camera,int spp,MediumTransport budget,EnvironmentState environment){return RenderFrameRequest.builder(++sequence,width,height,camera)
            .minimumSceneRevision(revision).environment(environment).mediumTransport(budget).antiAliasing(spp==1?AntiAliasingState.disabled():AntiAliasingState.multisampled(spp)).build();}
    private static void apply(SceneTransaction transaction)throws Exception{long deadline=System.nanoTime()+TIMEOUT.toNanos();for(;;)try{renderer.apply(transaction);return;}
        catch(SubmissionRejectedException pending){require(System.nanoTime()<deadline,"optical scene apply timeout");Thread.sleep(1);}}
    private static FrameSnapshot capture(String name,RenderFrameRequest request)throws Exception{long deadline=System.nanoTime()+TIMEOUT.toNanos();
        long captureStarted=benchmarkPolling?System.nanoTime():0L;
        while(!(renderer.trySubmit(request) instanceof Renderer.FrameSubmitted)){require(System.nanoTime()<deadline,"optical admission timeout");pollPause();}accepted++;
        long submitted=benchmarkPolling?System.nanoTime():0L;
        if(benchmarkPolling)submitNanos+=submitted-captureStarted;
        while(System.nanoTime()<deadline){long pollStarted=benchmarkPolling?System.nanoTime():0L;var ready=renderer.pollFrame();
            if(benchmarkPolling)pollWorkNanos+=System.nanoTime()-pollStarted;
            if(ready.isPresent()&&ready.orElseThrow().frameSequence()==request.sequence()){var frame=ready.orElseThrow();
            require(frame.renderedSceneRevision()==revision&&frame.format()==TextureFormat.RGBA16_FLOAT,"optical final HDR provenance");
            if(name!=null){byte[] bytes=new byte[frame.byteCount()];frame.pixels().get(bytes);Files.write(OUTPUT.resolve(name+".rgba16f"),bytes);}
            if(benchmarkPolling)pollNanos+=System.nanoTime()-submitted;return frame;}
            long gapStarted=benchmarkPolling?System.nanoTime():0L;pollPause();if(benchmarkPolling)pollGapNanos+=System.nanoTime()-gapStarted;}
        throw new AssertionError("optical final HDR timeout");}
    private static void pollPause()throws InterruptedException{if(benchmarkPolling)Thread.onSpinWait();else Thread.sleep(1);}
    private static void expectRejected(RenderFrameRequest frame)throws Exception{long deadline=System.nanoTime()+TIMEOUT.toNanos();for(;;){try{
        if(renderer.trySubmit(frame) instanceof Renderer.FrameSubmitted)throw new AssertionError("unsupported partial-PBR surface was silently accepted");
        }catch(FrameValidationException expected){require(expected.reason()==FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,"recoverable optical category");rejected++;return;}
        require(System.nanoTime()<deadline,"optical rejection timeout");Thread.sleep(1);}}
    private static void checkConstant(String name,FrameSnapshot frame,double r,double g,double b){var pixels=frame.pixels().order(ByteOrder.LITTLE_ENDIAN);
        double[] value={r,g,b};for(int pixel=0;pixel<width*height;pixel++)for(int c=0;c<3;c++)near(Float.float16ToFloat(pixels.getShort(pixel*8+c*2)),value[c],.0015,name);
        RESULTS.add("{\"name\":\""+name+"\"}");}
    private static void near(double actual,double expected,double tolerance,String why){require(Double.isFinite(actual)&&Math.abs(actual-expected)<=tolerance,why+" actual="+actual+" expected="+expected);}
    private static void require(boolean condition,String why){if(!condition)throw new AssertionError(why);}
}
