package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.ByteOrder;
import java.nio.ByteBuffer;
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
import top.ceroxe.rt.renderer.rt.diagnostics.OpticalDetailedProfile;

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
        if(java.util.Arrays.asList(args).contains("--connection-ranges")){verifyConnectionRanges(CudaOpticalMediumAbi.BOUND_CONNECTION_RAYS_PROPERTY);return;}
        if(java.util.Arrays.asList(args).contains("--connection-batches")){verifyConnectionRanges(CudaOpticalMediumAbi.BATCH_CONNECTION_HITS_PROPERTY);return;}
        if(java.util.Arrays.asList(args).contains("--duplicate-branches")){verifyDuplicateBranches(CudaOpticalMediumAbi.REUSE_DUPLICATE_BRANCHES_PROPERTY);return;}
        if(java.util.Arrays.asList(args).contains("--gpu-paths")){verifyDuplicateBranches(CudaOpticalMediumAbi.VULKAN_CONNECTIONS_PROPERTY);return;}
        if(java.util.Arrays.asList(args).contains("--gpu-connections")){verifyConnectionRanges(CudaOpticalMediumAbi.VULKAN_CONNECTIONS_PROPERTY);return;}
        Files.createDirectories(OUTPUT);String previous=System.getProperty(CudaCameraMediumProfile.ENABLE_PROPERTY);
        System.setProperty(CudaCameraMediumProfile.ENABLE_PROPERTY,"true");
        long nativeBefore=VulkanGpuSceneFrameSubmitter.diagnosticNativeOpticalSubmissions.sum();
        String onlyThinCase=System.getProperty("top.ceroxe.rt.medium.opticalOnlyThinCase");
        boolean thinCaseMatched=false;
        try(Renderer owned=Renderer.open("vulkan-rt",RendererConfig.builder().validationEnabled(true).maxFramesInFlight(3)
                .frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F).temporalRendering(TemporalRenderingOptions.disabled()).build())){
            renderer=owned;var camera=CameraState.lookAt(0,0,6,0,0,-1).verticalFieldOfViewDegrees(5).aspectRatio((double)width/height).build();
            var budget=MediumTransport.balanced().withMaxEvents(16).withMaxDistance(30).withTransmittanceCutoff(0);
            if(java.util.Arrays.asList(args).contains("--volume-caustics")) {
                verifySpatialPbrOptics(camera,budget);
                verifyDensityBoundaryCaustics(camera,budget);
                long routes=VulkanGpuSceneFrameSubmitter.diagnosticNativeOpticalSubmissions.sum()-nativeBefore;
                require(accepted==16&&routes==accepted,"density/boundary caustics bypassed native optical transport");
                require(renderer.health().activeFailure().isEmpty(),"density/boundary caustic renderer unhealthy");
                System.out.println("CudaOpticalMediumProductionNativeSelfTest density/boundary caustics passed");return;
            }
            if(java.util.Arrays.asList(args).contains("--caustics")) {
                verifyScatteringGlassCaustics(camera,budget);
                long routes=VulkanGpuSceneFrameSubmitter.diagnosticNativeOpticalSubmissions.sum()-nativeBefore;
                require(accepted==4&&routes==accepted,"glass/scattering caustic frames bypassed native optical transport");
                require(renderer.health().activeFailure().isEmpty(),"glass/scattering caustic renderer unhealthy");
                System.out.println("CudaOpticalMediumProductionNativeSelfTest glass/scattering caustics passed");return;
            }
            if(Boolean.getBoolean("top.ceroxe.rt.medium.opticalOnlyExplicitScattering")) {
                verifyExplicitBoundaryScattering(camera,budget);
                long opticalRoutes=VulkanGpuSceneFrameSubmitter.diagnosticNativeOpticalSubmissions.sum()-nativeBefore;
                require(accepted==5&&opticalRoutes==accepted,
                        "explicit-IOR scattering cross cases bypassed native optical transport: accepted="+accepted+", optical="+opticalRoutes);
                require(renderer.health().activeFailure().isEmpty(),"explicit-IOR scattering renderer unhealthy");
                System.out.println("CudaOpticalMediumProductionNativeSelfTest isolated explicit-boundary scattering passed");return;
            }
            for(int optical:new int[]{1,2,8})for(int spp:new int[]{1,2,8}){
                if(onlyThinCase!=null&&!matchesThinCase(onlyThinCase,optical,spp))continue;
                thinCaseMatched=true;
                setupThin(1.5f);var frame=capture("thin-"+optical+"-"+spp,request(camera,spp,budget.withOpticalSamples(optical),blueSky()));
                compareThin("thin-optical"+optical+"-SPP"+spp,frame,camera,spp,1.5);
            }
            if(onlyThinCase!=null){
                if(!thinCaseMatched)throw new IllegalArgumentException("unknown isolated optical case: "+onlyThinCase);
                long routes=VulkanGpuSceneFrameSubmitter.diagnosticNativeOpticalSubmissions.sum()-nativeBefore;
                require(routes==accepted,"isolated optical cases bypassed native route: "+routes+" / "+accepted);
                if(OpticalDetailedProfile.ENABLED)saveDetailedProfile();
                System.out.println("CudaOpticalMediumProductionNativeSelfTest isolated case passed: "+onlyThinCase+" nativeFrames="+routes);
                return;
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
            verifyHeterogeneousScatteringRoughGlass(camera,budget);
            verifyExplicitBoundaryScattering(camera,budget);
            verifyUnlitTerminalProof(camera,budget);
            verifyResolvedPublication(camera,budget);
            verifyPartialPbr(camera,budget);
            setupThin(1.5f);
            apply(SceneTransaction.builder(++revision).upsert(MediumTransportSelfTest.cube(3,2,1))
                    .upsert(SceneInstance.builder(3,3).mediumBoundary(MediumBoundary.volume(
                            new Medium(Rgb.ZERO,Rgb.ZERO,Rgb.ZERO,0,1.5f,0))).build()).build());
            expectRejected(request(camera,1,budget,blueSky()));
            setupThin(1.5f);compareThin("invalid-boundary-rejection-recovery",capture("recovery",request(camera,1,budget,blueSky())),camera,1,1.5);
            width=137;height=101;var resized=CameraState.lookAt(0,0,6,0,0,-1).verticalFieldOfViewDegrees(5).aspectRatio((double)width/height).build();
            compareThin("resize-eight-SPP",capture("resized",request(resized,8,budget.withOpticalSamples(2),blueSky())),resized,8,1.5);
            width=1920;height=1080;var fullHd=CameraState.lookAt(0,0,6,0,0,-1).verticalFieldOfViewDegrees(5).aspectRatio((double)width/height).build();
            compareThin("full-HD-multiple-optical-tiles",capture("full-HD",request(fullHd,1,budget.withOpticalSamples(1),blueSky())),fullHd,1,1.5);
            width=37;height=29;
            compareThin("shrink-after-full-HD",capture("shrink",request(camera,1,budget,blueSky())),camera,1,1.5);
            verifySpatialPbrOptics(camera,budget);
            verifyScatteringGlassCaustics(camera,budget);
            verifyDensityBoundaryCaustics(camera,budget);
            require(renderer.health().activeFailure().isEmpty(),"optical session unhealthy");
        }catch(Exception|Error failure){
            if(OpticalDetailedProfile.ENABLED)try{saveDetailedProfile();}catch(java.io.IOException saveFailure){failure.addSuppressed(saveFailure);}
            throw failure;
        }finally{if(previous==null)System.clearProperty(CudaCameraMediumProfile.ENABLE_PROPERTY);else System.setProperty(CudaCameraMediumProfile.ENABLE_PROPERTY,previous);}
        if(OpticalDetailedProfile.ENABLED)saveDetailedProfile();
        verifyResolvedTemporal();
        long routes=VulkanGpuSceneFrameSubmitter.diagnosticNativeOpticalSubmissions.sum()-nativeBefore;
        require(routes==accepted,"public optical frame bypassed native route: "+routes+" / "+accepted);
        Files.writeString(OUTPUT.resolve("result.json"),"{\n\"passed\":true,\n\"nativeFrames\":"+routes+",\n\"recoverableRejections\":"+rejected+",\n\"comparisons\":["+String.join(",\n",RESULTS)+"]\n}\n");
        System.out.println("CudaOpticalMediumProductionNativeSelfTest passed: comparisons="+RESULTS.size()+" actualNativeFrames="+routes+" recoverableRejections="+rejected);
    }
    /** Same sequence identities, both initialization layouts, and branch-zero dead lobes. */
    private static void verifyDuplicateBranches(String property)throws Exception {
        Files.createDirectories(OUTPUT);
        boolean gpu=property.equals(CudaOpticalMediumAbi.VULKAN_CONNECTIONS_PROPERTY);
        String previous=System.getProperty(property),previousFused=System.getProperty(CudaOpticalMediumAbi.FUSED_INITIALIZATION_PROPERTY);
        var references=new ArrayList<byte[]>();var queryReferences=new ArrayList<Long>();int comparisons=0;
        String[] kinds={"fog","volume","nested","alpha","unit","smooth","rough","metal"};
        long nativeBefore=VulkanGpuSceneFrameSubmitter.diagnosticNativeOpticalSubmissions.sum();
        long totalBefore=0,totalAfter=0;
        try {
            for(boolean reuse:new boolean[]{false,true}) {
                System.setProperty(property,Boolean.toString(reuse));sequence=0;revision=0;int index=0;
                try(Renderer owned=Renderer.open("vulkan-rt",RendererConfig.builder().validationEnabled(true).maxFramesInFlight(3)
                        .frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F).temporalRendering(TemporalRenderingOptions.disabled()).build())) {
                    renderer=owned;
                    var camera=CameraState.lookAt(0,0,6,0,0,-1).verticalFieldOfViewDegrees(5).aspectRatio((double)width/height).build();
                    var budget=MediumTransport.balanced().withMaxEvents(24).withOpticalSamples(2)
                            .withMaxDistance(30).withTransmittanceCutoff(0).withScatteringBounces(2);
                    var fog=new Medium(new Rgb(.02f,.03f,.04f),new Rgb(.12f,.11f,.10f),new Rgb(.01f,.02f,.03f),1,1,.2f);
                    for(boolean fused:new boolean[]{false,true})for(int spp:new int[]{1,2})for(String kind:kinds) {
                        System.setProperty(CudaOpticalMediumAbi.FUSED_INITIALIZATION_PROPERTY,Boolean.toString(fused));
                        var surface=MaterialAsset.builder(1).baseColorRgba8(0xff808080).roughness(.5f)
                                .metallic(kind.equals("metal")?.8f:0).doubleSided(true).build();
                        // An off-camera explicit optical boundary keeps every case on the production optical route.
                        var scene=SceneTransaction.builder(++revision).resetScene().upsert(surface).upsert(glass(1.5f))
                                .upsert(plane(1,1,-4,false)).upsert(SceneInstance.builder(1,1).build())
                                .upsert(MediumTransportSelfTest.cube(2,2,1)).upsert(SceneInstance.builder(2,2)
                                        .transform(new AffineTransform(new float[]{1,0,0,1000,0,1,0,0,0,0,1,0}))
                                        .mediumBoundary(MediumBoundary.surface(new Medium(Rgb.ZERO,Rgb.ZERO,Rgb.ZERO,1,1.5f,0))).build())
                                .upsert(SceneLight.point(30,2,1,1).intensity(20).range(30).castsShadow(true).build())
                                .upsert(SceneLight.directional(31,-1,0,-1).intensity(2).castsShadow(true).build());
                        if(kind.equals("unit")||kind.equals("smooth")||kind.equals("rough")||kind.equals("alpha")) {
                            var obstacle=MaterialAsset.builder(3).transmission(1).indexOfRefraction(kind.equals("unit")?1:1.5f)
                                    .doubleSided(true).roughness(kind.equals("rough")?.35f:0).build();
                            if(kind.equals("alpha"))obstacle=obstacle.toBuilder().transmission(0).baseColorRgba8(0x80808080)
                                    .blendMode(MaterialAsset.BlendMode.TRANSLUCENT).build();
                            scene.upsert(obstacle).upsert(plane(3,3,0,false)).upsert(SceneInstance.builder(3,3).build());
                        }
                        if(kind.equals("volume")||kind.equals("nested")) {
                            scene.upsert(MediumTransportSelfTest.cube(3,1,1)).upsert(SceneInstance.builder(3,3)
                                    .transform(new AffineTransform(new float[]{3,0,0,0,0,3,0,0,0,0,3,0}))
                                    .mediumBoundary(MediumBoundary.volume(fog).withPriority(1)).build());
                            if(kind.equals("nested"))scene.upsert(SceneInstance.builder(4,3)
                                    .mediumBoundary(MediumBoundary.volume(new Medium(new Rgb(.2f,.1f,.3f),
                                            new Rgb(.2f,.2f,.2f),Rgb.ZERO,1,1,0)).withPriority(8)).build());
                        }
                        apply(scene.build());
                        long queries=CudaOpticalMediumManager.diagnosticQueries.sum();
                        var frame=capture("branch-"+reuse+"-"+fused+"-"+spp+"-"+kind,
                                request(camera,spp,budget,blackSky().toBuilder().cameraMedium(fog).build()));
                        queries=CudaOpticalMediumManager.diagnosticQueries.sum()-queries;
                        byte[] bytes=new byte[frame.byteCount()];frame.pixels().get(bytes);
                        if(!reuse){references.add(bytes);queryReferences.add(queries);totalBefore+=queries;}
                        else {
                            if(gpu)requireBoundedHdr(references.get(index),bytes,"GPU path: fused="+fused+" spp="+spp+" case="+kind);
                            else require(java.util.Arrays.equals(references.get(index),bytes),"duplicate-branch reuse changed HDR: fused="+fused+" spp="+spp+" case="+kind);
                            if(!gpu&&kind.equals("fog"))require(queries<queryReferences.get(index),"duplicate-branch reuse deleted no continued path work");
                            totalAfter+=queries;comparisons++;
                        }
                        index++;
                    }
                    require(renderer.health().activeFailure().isEmpty(),"duplicate-branch reuse session unhealthy");
                }
            }
            long routes=VulkanGpuSceneFrameSubmitter.diagnosticNativeOpticalSubmissions.sum()-nativeBefore;
            require(routes==64,"duplicate-branch checks bypassed optical route: "+routes);
            Files.writeString(OUTPUT.resolve(gpu?"gpu-path-checks.json":"duplicate-branches.json"),"{\"passed\":true,\""+(gpu?"boundedComparisons":"byteIdenticalComparisons")+"\":"+comparisons
                    +",\"nativeFrames\":"+routes+",\"continuedQueriesBefore\":"+totalBefore+",\"continuedQueriesAfter\":"+totalAfter+"}\n");
            System.out.println("CudaOpticalMediumProductionNativeSelfTest duplicate branches passed: comparisons="+comparisons+" nativeFrames="+routes);
        } finally {
            if(previous==null)System.clearProperty(property);else System.setProperty(property,previous);
            if(previousFused==null)System.clearProperty(CudaOpticalMediumAbi.FUSED_INITIALIZATION_PROPERTY);
            else System.setProperty(CudaOpticalMediumAbi.FUSED_INITIALIZATION_PROPERTY,previousFused);
        }
    }
    private static void saveDetailedProfile()throws java.io.IOException {
        Files.writeString(OUTPUT.resolve("detailed-profile.json"),OpticalDetailedProfile.deltaJson(java.util.Map.of()));
    }
    private static boolean matchesThinCase(String filter,int optical,int spp){
        return filter.equals(optical+"-"+spp)||filter.equals(optical+"-*");
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
            var detailedBefore=OpticalDetailedProfile.snapshot();
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
            if(OpticalDetailedProfile.ENABLED)Files.writeString(OUTPUT.resolve("detailed-profile.json"),OpticalDetailedProfile.deltaJson(detailedBefore));
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
    private static void verifyResolvedPublication(CameraState camera,MediumTransport budget)throws Exception {
        String previous=System.getProperty(CudaOpticalMediumAbi.RESOLVED_PUBLICATION_PROPERTY);
        try {
            setupThin(1.5f);
            // A small outlined dielectric and a large unlit receiver exercise mixed
            // fast/fallback pixels, sample edges, background and actual outline rays.
            apply(SceneTransaction.builder(++revision).upsert(MeshAsset.triangles(2,
                    new float[]{-.12f,-.12f,0,.12f,-.12f,0,.12f,.12f,0,-.12f,.12f,0},
                    new int[]{0,1,2,2,3,0},2)).upsert(SceneInstance.builder(2,2)
                    .renderState(InstanceRenderState.builder().objectMask(9).outline(OutlineStyle.of(0xff00ff00,2)).build()).build()).build());
            for(int samples:new int[]{1,2,8}) {
                var request=request(camera,samples,budget,blueSky());
                System.setProperty(CudaOpticalMediumAbi.RESOLVED_PUBLICATION_PROPERTY,"false");
                var legacy=capture("resolved-outline-off-"+samples,request);
                System.setProperty(CudaOpticalMediumAbi.RESOLVED_PUBLICATION_PROPERTY,"true");
                var resolved=capture("resolved-outline-on-"+samples,request(camera,samples,budget,blueSky()));
                require(legacy.pixels().equals(resolved.pixels()),"resolved publication changed outlined multi-sample HDR: "+samples);
                boolean green=false;var pixels=resolved.pixels().order(ByteOrder.LITTLE_ENDIAN);
                for(int pixel=0;pixel<width*height;pixel++)green|=Float.float16ToFloat(pixels.getShort(pixel*8+2))>.9f;
                require(green,"outlined publication test did not exercise an actual green outline");
                RESULTS.add("{\"name\":\"resolved-publication-outline-SPP"+samples+"\"}");
            }
        } finally {
            if(previous==null)System.clearProperty(CudaOpticalMediumAbi.RESOLVED_PUBLICATION_PROPERTY);
            else System.setProperty(CudaOpticalMediumAbi.RESOLVED_PUBLICATION_PROPERTY,previous);
        }
    }
    private static void verifyResolvedTemporal()throws Exception {
        String previous=System.getProperty(CudaOpticalMediumAbi.RESOLVED_PUBLICATION_PROPERTY);
        long firstSequence=sequence;var reference=new ArrayList<FrameSnapshot>();
        try {
            for(boolean resolved:new boolean[]{false,true}) {
                sequence=firstSequence;
                System.setProperty(CudaOpticalMediumAbi.RESOLVED_PUBLICATION_PROPERTY,Boolean.toString(resolved));
                try(Renderer owned=Renderer.open("vulkan-rt",RendererConfig.builder().validationEnabled(true).maxFramesInFlight(3)
                        .frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F).temporalRendering(TemporalRenderingOptions.balanced()).build())) {
                    renderer=owned;setupThin(1.5f);
                    var camera=CameraState.lookAt(0,0,6,0,0,-1).verticalFieldOfViewDegrees(5).aspectRatio((double)width/height).build();
                    for(int frame=0;frame<3;frame++) {
                        var snapshot=capture("resolved-temporal-"+resolved+"-"+frame,request(camera,2,MediumTransport.balanced().withOpticalSamples(2),blueSky()));
                        if(!resolved)reference.add(snapshot);
                        else require(reference.get(frame).pixels().equals(snapshot.pixels()),"resolved publication changed temporal geometry/history at frame "+frame);
                    }
                    require(renderer.health().activeFailure().isEmpty(),"resolved temporal session unhealthy");
                }
            }
            RESULTS.add("{\"name\":\"resolved-publication-temporal-history-three-frames\"}");
        } finally {
            if(previous==null)System.clearProperty(CudaOpticalMediumAbi.RESOLVED_PUBLICATION_PROPERTY);
            else System.setProperty(CudaOpticalMediumAbi.RESOLVED_PUBLICATION_PROPERTY,previous);
        }
    }
    private static void verifyUnlitTerminalProof(CameraState camera,MediumTransport budget)throws Exception {
        setupThin(1);
        apply(SceneTransaction.builder(++revision).upsert(glass(1).toBuilder().transmission(.5f)
                .shadingModel(MaterialAsset.ShadingModel.UNLIT).emissive(0xffff0000,.25f).build())
                .upsert(SceneLight.directional(31,0,0,-1).color(1,1,1).intensity(100).castsShadow(true).build()).build());
        var lit=capture("partial-unlit-terminal-proof",request(camera,8,budget,blackSky()));
        checkConstant("partial-unlit-terminal-color-and-emission",lit,1,.5,.625);
        apply(SceneTransaction.builder(++revision).removeLight(31).build());
        var dark=capture("partial-unlit-terminal-light-independence",request(camera,8,budget,blackSky()));
        require(lit.pixels().equals(dark.pixels()),"UNLIT terminal proof must preserve independence from scene lights");
        RESULTS.add("{\"name\":\"partial-unlit-emission-light-independence\"}");
    }
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
    private static void verifyScatteringGlassCaustics(CameraState camera,MediumTransport budget)throws Exception {
        long photonsBefore=CausticPhotonPlan.diagnosticPhotonDispatches.sum();
        var medium=new Medium(Rgb.ZERO,new Rgb(.2f,0,0),Rgb.ZERO,1,1,0);
        var environment=EnvironmentState.builder().sunDirection(0,0,-1).sunIntensity(1).cameraMedium(medium).build();
        var receiver=MaterialAsset.builder(1).shadingModel(MaterialAsset.ShadingModel.PHYSICALLY_BASED)
                .baseColorRgba8(0xff808080).roughness(1).metallic(0).doubleSided(true).build();
        var reflector=MaterialAsset.builder(2).roughness(0).metallic(0).doubleSided(true).build();
        var pane=MaterialAsset.builder(3).transmission(1).roughness(0).indexOfRefraction(1.5f).doubleSided(true).build();
        apply(SceneTransaction.builder(++revision).resetScene().upsert(receiver).upsert(reflector).upsert(pane)
                .upsert(plane(1,1,-4,false)).upsert(causticMirror()).upsert(plane(3,3,4.5f,false)).upsert(plane(4,3,4.4f,true))
                .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build())
                .upsert(SceneInstance.builder(3,3).build()).upsert(SceneInstance.builder(4,4).build()).build());
        var transport=budget.withOpticalSamples(8).withScatteringSteps(64).withScatteringBounces(0);
        var policy=Caustics.photonMap(0,0,-1,6,524288,.18f).withMaxEvents(8);
        var clear=environment.toBuilder().cameraMedium(Medium.vacuum()).build();
        var clearOff=capture("glass-caustics-clear-off",request(camera,8,transport,clear,Caustics.disabled()));
        var clearOn=capture("glass-caustics-clear-on",request(camera,8,transport,clear,policy));
        double clearDelta=energy(clearOn)-energy(clearOff);int clearPixels=positiveRedPixels(clearOff,clearOn);
        require(clearDelta>.01&&clearPixels>width*height/16,
                "glass path did not gather the native PBR photon map: delta="+clearDelta+", pixels="+clearPixels);
        long scatterPhotons=CausticPhotonPlan.diagnosticPhotonDispatches.sum();
        // Keep the receiver outside the volume-emission sphere while leaving the glass and mirror inside it.
        apply(SceneTransaction.builder(++revision).upsert(plane(1,1,-10,false)).build());
        var off=capture("glass-scattering-caustics-off",request(camera,8,transport,environment,Caustics.disabled()));
        var volumeOnly=Caustics.photonMap(0,0,0,6,524288,.18f).withMaxEvents(8);
        var on=capture("glass-scattering-caustics-on",request(camera,8,transport,environment,volumeOnly));
        double delta=energy(on)-energy(off);int changed=positiveRedPixels(off,on);
        require(delta>1e-4&&changed>width*height/16
                        &&CausticPhotonPlan.diagnosticPhotonDispatches.sum()-scatterPhotons==1,
                "scattering glass path produced no caustic gather: delta="+delta+", pixels="+changed);
        require(CausticPhotonPlan.diagnosticPhotonDispatches.sum()-photonsBefore==2,
                "each enabled native optical caustic frame must build the photon map exactly once");
        Files.writeString(OUTPUT.resolve("glass-scattering-caustics.json"),String.format(Locale.ROOT,
                "{\"passed\":true,\"nativeFrames\":4,\"photonDispatches\":2,\"clearPositivePixels\":%d,\"clearEnergyDelta\":%.9g,\"scatteringPositivePixels\":%d,\"scatteringEnergyDelta\":%.9g}%n",
                clearPixels,clearDelta,changed,delta));
    }
    /** Real production RT queries; mirrored geometry and identical sequence identities in both modes. */
    private static void verifyConnectionRanges(String property)throws Exception {
        Files.createDirectories(OUTPUT);
        String previous=System.getProperty(property),previousCompact=System.getProperty(CudaOpticalMediumAbi.COMPACT_HITS_PROPERTY),
                previousTransposed=System.getProperty(CudaOpticalMediumAbi.TRANSPOSED_HITS_PROPERTY),
                previousGpu=System.getProperty(CudaOpticalMediumAbi.VULKAN_CONNECTIONS_PROPERTY);
        boolean batches=property.equals(CudaOpticalMediumAbi.BATCH_CONNECTION_HITS_PROPERTY);
        boolean gpu=property.equals(CudaOpticalMediumAbi.VULKAN_CONNECTIONS_PROPERTY),extended=batches||gpu;
        var references=new ArrayList<byte[]>();int comparisons=0;
        String[] cases=extended?new String[]{"clear","behind-opaque","before-opaque","before-alpha","before-volume",
                "light-inside-volume","endpoint-overlay","endpoint-nonshadow","endpoint-opaque","sun",
                "before-alpha-chain","before-volume-nested","before-volume-budget2","before-volume-budget3"}
                :new String[]{"clear","behind-opaque","before-opaque","before-alpha","before-volume",
                "light-inside-volume","endpoint-overlay","endpoint-nonshadow","endpoint-opaque","sun"};
        String[] formats=extended?new String[]{"full","compact","transposed"}:new String[]{"transposed"};
        long nativeBefore=VulkanGpuSceneFrameSubmitter.diagnosticNativeOpticalSubmissions.sum();
        try {
            // Batching has its own strict fallback test even when full GPU connections are the default.
            if(batches)System.setProperty(CudaOpticalMediumAbi.VULKAN_CONNECTIONS_PROPERTY,"false");
            for(boolean bound:new boolean[]{false,true}) {
                System.setProperty(property,Boolean.toString(bound));
                sequence=0;revision=0;accepted=0;int referenceIndex=0;
                try(Renderer owned=Renderer.open("vulkan-rt",RendererConfig.builder().validationEnabled(true).maxFramesInFlight(3)
                        .frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F).temporalRendering(TemporalRenderingOptions.disabled()).build())) {
                    renderer=owned;
                    var camera=CameraState.lookAt(0,0,6,0,0,-1).verticalFieldOfViewDegrees(.01).aspectRatio((double)width/height).build();
                    var budget=MediumTransport.balanced().withMaxEvents(16).withOpticalSamples(2)
                            .withMaxDistance(30).withTransmittanceCutoff(0);
                    for(String format:formats) {
                    System.setProperty(CudaOpticalMediumAbi.COMPACT_HITS_PROPERTY,Boolean.toString(!format.equals("full")));
                    System.setProperty(CudaOpticalMediumAbi.TRANSPOSED_HITS_PROPERTY,Boolean.toString(format.equals("transposed")));
                    for(int mirror:new int[]{1,-1}) {
                        double clearEnergy=0;
                        for(String kind:cases) {
                            var receiver=MaterialAsset.builder(1).baseColorRgba8(0xff808080).roughness(1).doubleSided(true).build();
                            var obstacle=MaterialAsset.builder(3).doubleSided(true).baseColorRgba8(0xff000000).build();
                            if(kind.startsWith("before-alpha"))obstacle=obstacle.toBuilder().baseColorRgba8(0x80000000)
                                    .blendMode(MaterialAsset.BlendMode.TRANSLUCENT).build();
                            if(kind.equals("endpoint-overlay"))obstacle=obstacle.toBuilder().baseColorRgba8(0x80000000)
                                    .blendMode(MaterialAsset.BlendMode.TRANSLUCENT).surfaceOverlay(SurfaceOverlayState.depthBias(
                                            .05f,SurfaceOverlayState.CompositionMode.ALPHA_OVER)).build();
                            var scene=SceneTransaction.builder(++revision).resetScene().upsert(receiver).upsert(glass(1.5f)).upsert(obstacle)
                                    .upsert(plane(1,1,-4,false)).upsert(plane(2,2,0,false))
                                    .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build());
                            if(kind.contains("volume")) {
                                boolean inside=kind.equals("light-inside-volume");
                                var medium=new Medium(new Rgb(.2f,.3f,.4f),Rgb.ZERO,Rgb.ZERO,1,1,0);
                                scene.upsert(MediumTransportSelfTest.cube(4,3,1)).upsert(SceneInstance.builder(4,4)
                                        .transform(new AffineTransform(new float[]{.3f,0,0,mirror*(inside?2:1),0,.3f,0,0,0,0,.3f,inside?-2:-3}))
                                        .mediumBoundary(MediumBoundary.volume(medium)).build());
                                if(kind.equals("before-volume-nested"))scene.upsert(SceneInstance.builder(5,4)
                                        .transform(new AffineTransform(new float[]{.15f,0,0,mirror,0,.15f,0,0,0,0,.15f,-3}))
                                        .mediumBoundary(MediumBoundary.volume(new Medium(new Rgb(.4f,.1f,.2f),Rgb.ZERO,Rgb.ZERO,1,1,0)).withPriority(8)).build());
                            } else if(!kind.equals("clear")&&!kind.equals("sun")) {
                                float z=kind.startsWith("before")?-3:kind.startsWith("behind")?-1:-2;
                                float x=mirror*(z+4);
                                int[] indices=mirror==1?new int[]{0,1,2,2,3,0}:new int[]{0,2,1,0,3,2};
                                scene.upsert(MeshAsset.triangles(3,new float[]{x-.35f,-.35f,z,x+.35f,-.35f,z,
                                        x+.35f,.35f,z,x-.35f,.35f,z},indices,3))
                                        .upsert(SceneInstance.builder(3,3).castsShadow(!kind.equals("endpoint-nonshadow")).build());
                                if(kind.equals("before-alpha-chain"))for(int layer=1;layer<=2;layer++) {
                                    float dz=layer*.2f,dx=mirror*dz;
                                    scene.upsert(SceneInstance.builder(3+layer,3)
                                            .transform(new AffineTransform(new float[]{1,0,0,dx,0,1,0,0,0,0,1,dz})).build());
                                }
                            }
                            scene.upsert(kind.equals("sun")?SceneLight.directional(30,-mirror,0,-1).intensity(2).castsShadow(true).build()
                                    :SceneLight.point(30,mirror*2,0,-2).intensity(16).range(30).castsShadow(true).build());
                            apply(scene.build());
                            var caseBudget=kind.endsWith("budget2")?budget.withMaxEvents(2):kind.endsWith("budget3")?budget.withMaxEvents(3):budget;
                            var snapshot=capture("range-"+format+"-"+bound+"-"+mirror+"-"+kind,request(camera,1,caseBudget,blackSky()));
                            byte[] bytes=new byte[snapshot.byteCount()];snapshot.pixels().get(bytes);
                            if(!bound)references.add(bytes);
                            else {
                                String context="connection property="+property+" format="+format+" mirror="+mirror+" case="+kind;
                                if(gpu)requireBoundedHdr(references.get(referenceIndex),bytes,context);
                                else require(java.util.Arrays.equals(references.get(referenceIndex),bytes),context);
                                comparisons++;
                            }
                            referenceIndex++;
                            double e=energy(snapshot);
                            if(kind.equals("clear")){clearEnergy=e;require(e>.001,"finite connection test has no direct lighting");}
                            if(kind.equals("behind-opaque"))near(e,clearEnergy,0,"geometry behind the light cannot occlude it");
                            if(kind.equals("before-opaque"))near(e,0,1e-6,"geometry before the light must occlude it");
                            if(kind.endsWith("budget2"))near(e,0,1e-6,"second hit preserves the connection event budget");
                            else if(kind.startsWith("before-alpha")||kind.contains("volume"))require(e>0&&e<clearEnergy,
                                    "finite connection lost transparent/medium attenuation: "+kind);
                        }
                    }
                    }
                    require(renderer.health().activeFailure().isEmpty(),"finite connection renderer unhealthy");
                }
            }
            long routes=VulkanGpuSceneFrameSubmitter.diagnosticNativeOpticalSubmissions.sum()-nativeBefore;
            int expected=cases.length*formats.length*2;
            require(comparisons==expected&&routes==expected*2L,"finite connection checks bypassed native optical transport: "+routes);
            Files.writeString(OUTPUT.resolve(gpu?"gpu-connection-checks.json":batches?"connection-batch-checks.json":"connection-range-checks.json"),
                    "{\"passed\":true,\""+(gpu?"boundedComparisons":"byteIdenticalComparisons")+"\":"+comparisons+",\"nativeFrames\":"+routes+",\"mirroredVertexOrders\":2,\"hitFormats\":"+formats.length+"}\n");
            System.out.println("CudaOpticalMediumProductionNativeSelfTest connection modes passed: "+comparisons+(gpu?" bounded":" byte-identical")+" comparisons / "+routes+" native frames");
        } finally {
            if(previous==null)System.clearProperty(property);else System.setProperty(property,previous);
            if(previousCompact==null)System.clearProperty(CudaOpticalMediumAbi.COMPACT_HITS_PROPERTY);else System.setProperty(CudaOpticalMediumAbi.COMPACT_HITS_PROPERTY,previousCompact);
            if(previousTransposed==null)System.clearProperty(CudaOpticalMediumAbi.TRANSPOSED_HITS_PROPERTY);else System.setProperty(CudaOpticalMediumAbi.TRANSPOSED_HITS_PROPERTY,previousTransposed);
            if(previousGpu==null)System.clearProperty(CudaOpticalMediumAbi.VULKAN_CONNECTIONS_PROPERTY);else System.setProperty(CudaOpticalMediumAbi.VULKAN_CONNECTIONS_PROPERTY,previousGpu);
        }
    }
    /** Predeclared cross-backend policy, identical to CompareMediumHdr.py --bounded. */
    private static void requireBoundedHdr(byte[] reference,byte[] candidate,String context) {
        require(reference.length==candidate.length&&reference.length%8==0,"HDR extent: "+context);
        var a=ByteBuffer.wrap(reference).order(ByteOrder.LITTLE_ENDIAN);var b=ByteBuffer.wrap(candidate).order(ByteOrder.LITTLE_ENDIAN);
        int pixels=reference.length/8;double[] magnitudes=new double[pixels*3];double square=0,error=0,lightA=0,lightB=0,maximum=0;
        double[] luma={.2126,.7152,.0722};
        for(int i=0;i<pixels;i++){
            require(a.getShort(i*8+6)==b.getShort(i*8+6),"HDR alpha: "+context);
            require(Float.isFinite(Float.float16ToFloat(a.getShort(i*8+6))),"nonfinite HDR alpha: "+context);
            for(int c=0;c<3;c++){
                double x=Float.float16ToFloat(a.getShort(i*8+c*2)),y=Float.float16ToFloat(b.getShort(i*8+c*2));
                require(Double.isFinite(x)&&Double.isFinite(y),"nonfinite HDR: "+context);
                double delta=y-x;square+=x*x;error+=delta*delta;lightA+=x*luma[c];lightB+=y*luma[c];
                maximum=Math.max(maximum,Math.abs(delta));magnitudes[i*3+c]=Math.abs(x);
            }
        }
        java.util.Arrays.sort(magnitudes);double at=.99*(magnitudes.length-1),fraction=at-Math.floor(at);
        double p99=magnitudes[(int)Math.floor(at)]*(1-fraction)+magnitudes[(int)Math.ceil(at)]*fraction;
        require(Math.sqrt(error/Math.max(square,1e-30))<=.001&&Math.abs(lightB-lightA)/Math.max(Math.abs(lightA),1e-30)<=.001
                &&maximum<=p99*.005+1e-5,"cross-backend HDR error limit exceeded: "+context+" maximum="+maximum);
    }
    private static void verifySpatialPbrOptics(CameraState camera,MediumTransport budget)throws Exception {
        var transform=new AffineTransform(new float[]{2,0,0,0,0,2,0,0,0,0,1,0});
        var receiver=MaterialAsset.builder(1).baseColorRgba8(0xff808080).roughness(1).doubleSided(true).build();
        var glass=glass(1.5f);
        apply(SceneTransaction.builder(++revision).resetScene().upsert(receiver).upsert(glass)
                .upsert(plane(1,1,-4,false)).upsert(plane(2,2,4.5f,false)).upsert(MediumTransportSelfTest.cube(5,2,1))
                .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build())
                .upsert(SceneInstance.builder(5,5).transform(transform).mediumBoundary(MediumBoundary.volume(Medium.vacuum())).build())
                .upsert(SceneLight.directional(30,0,0,-1).intensity(2).castsShadow(true).build()).build());
        var narrow=CameraState.lookAt(0,0,6,0,0,-1).verticalFieldOfViewDegrees(.01).aspectRatio((double)width/height).build();
        var reference=meanRgb(capture("PBR-boundary-vacuum",request(narrow,1,budget,blackSky())));
        require(reference[0]>.001,"PBR boundary receiver has no direct lighting");
        var medium=new Medium(new Rgb(.1f,.2f,.3f),Rgb.ZERO,Rgb.ZERO,1,1,0);
        apply(SceneTransaction.builder(++revision).upsert(SceneInstance.builder(5,5).transform(transform)
                .mediumBoundary(MediumBoundary.volume(medium)).build()).build());
        var absorbed=meanRgb(capture("PBR-boundary-absorbed",request(narrow,1,budget,blackSky())));
        var field=MediumDensityField.grid(50,1,new AffineTransform(new float[]{.5f,0,0,.5f,0,.5f,0,.5f,0,0,.5f,.5f}));
        apply(SceneTransaction.builder(++revision).upsert(TextureAsset.builder(50,1,1).colorSpace(TextureAsset.ColorSpace.LINEAR)
                        .pixelsRgba8(new byte[]{(byte)128,0,0,(byte)255}).build())
                .upsert(SceneInstance.builder(5,5).transform(transform).mediumBoundary(MediumBoundary.volume(medium.withDensityField(field))).build()).build());
        var density=meanRgb(capture("PBR-boundary-density",request(narrow,1,budget,blackSky())));
        double maximum=0;
        for(int c=0;c<3;c++) {
            double sigma=(c+1)*.1;
            double expected=reference[c]*Math.exp(-4*sigma),densityExpected=reference[c]*Math.exp(-4*sigma*128/255.0);
            maximum=Math.max(maximum,Math.max(Math.abs(absorbed[c]-expected),Math.abs(density[c]-densityExpected)));
            near(absorbed[c],expected,.0002,"PBR camera + light boundary Beer channel="+c);
            near(density[c],densityExpected,.0002,"PBR camera + light density Beer channel="+c);
        }
        apply(SceneTransaction.builder(++revision).upsert(TextureAsset.builder(50,1,1).colorSpace(TextureAsset.ColorSpace.LINEAR)
                .pixelsRgba8(new byte[]{0,0,0,(byte)255}).build()).build());
        var zero=meanRgb(capture("PBR-boundary-zero-density",request(narrow,1,budget,blackSky())));
        for(int c=0;c<3;c++)near(zero[c],reference[c],1e-6,"PBR zero-density recovers vacuum channel="+c);
        RESULTS.add(String.format(Locale.ROOT,"{\"name\":\"PBR-boundary-density-camera-light-Beer\",\"maximumAbsoluteError\":%.9g}",maximum));
    }
    private static void verifyDensityBoundaryCaustics(CameraState camera,MediumTransport budget)throws Exception {
        var medium=new Medium(new Rgb(.01f,.02f,.03f),new Rgb(.2f,.15f,.1f),Rgb.ZERO,1,1,.2f);
        var field=MediumDensityField.grid(50,1,new AffineTransform(new float[]{.08f,0,0,.5f,0,.08f,0,.5f,0,0,.08f,.5f}));
        var texture=TextureAsset.builder(50,1,1).colorSpace(TextureAsset.ColorSpace.LINEAR)
                .pixelsRgba8(new byte[]{(byte)160,0,0,(byte)255}).build();
        var glass=MaterialAsset.builder(3).transmission(1).roughness(0).indexOfRefraction(1.5f).doubleSided(true).build();
        apply(SceneTransaction.builder(++revision).resetScene().upsert(receiver().toBuilder().baseColorRgba8(0xff000000).build())
                .upsert(glass).upsert(texture).upsert(plane(1,1,-10,false))
                .upsert(plane(3,3,4.5f,false)).upsert(plane(4,3,4.4f,true))
                .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(3,3).build())
                .upsert(SceneInstance.builder(4,4).build())
                .upsert(SceneLight.directional(30,0,0,-1).intensity(1).castsShadow(true).build()).build());
        var environment=blackSky().toBuilder()
                .cameraMedium(medium.withDensityField(field)).build();
        var transport=budget.withOpticalSamples(4).withScatteringSteps(64).withScatteringBounces(2);
        var policy=Caustics.photonMap(0,0,0,6,131072,.35f).withMaxEvents(12);
        long before=CausticPhotonPlan.diagnosticPhotonDispatches.sum();
        var off=capture("density-caustics-off",request(camera,8,transport,environment));
        var on=capture("density-caustics-on",request(camera,8,transport,environment,policy));
        double densityDelta=energy(on)-energy(off);
        require(densityDelta>1e-5,"density photon gather missing: "+densityDelta);
        apply(SceneTransaction.builder(++revision).upsert(TextureAsset.builder(50,1,1).colorSpace(TextureAsset.ColorSpace.LINEAR)
                .pixelsRgba8(new byte[]{0,0,0,(byte)255}).build()).build());
        var zeroOff=capture("zero-density-caustics-off",request(camera,8,transport,environment));
        var zeroOn=capture("zero-density-caustics-on",request(camera,8,transport,environment,policy));
        near(energy(zeroOn),0,1e-7,"zero-density caustics cannot invent volume light");
        near(energy(zeroOn),energy(zeroOff),1e-7,"zero-density on/off limit");
        apply(SceneTransaction.builder(++revision).upsert(texture)
                .upsert(MediumTransportSelfTest.cube(5,3,1))
                .upsert(SceneInstance.builder(5,5).transform(new AffineTransform(new float[]{2,0,0,0,0,2,0,0,0,0,2,0}))
                        .mediumBoundary(MediumBoundary.volume(medium.withDensityField(field)).withPriority(7)).build()).build());
        var bounded=environment.toBuilder().cameraMedium(Medium.vacuum()).build();
        var boundaryOff=capture("boundary-density-caustics-off",request(camera,8,transport,bounded));
        var boundaryOn=capture("boundary-density-caustics-on",request(camera,8,transport,bounded,policy));
        double boundaryDelta=energy(boundaryOn)-energy(boundaryOff);
        require(boundaryDelta>1e-5,"boundary-density photon gather missing: "+boundaryDelta);
        apply(SceneTransaction.builder(++revision).upsert(glass.toBuilder().roughness(.2f).build()).build());
        var roughOff=capture("rough-boundary-density-caustics-off",request(camera,8,transport,bounded));
        var roughOn=capture("rough-boundary-density-caustics-on",request(camera,8,transport,bounded,policy));
        double roughDelta=energy(roughOn)-energy(roughOff);
        require(roughDelta>1e-5,"rough glass/boundary-density photon gather missing: "+roughDelta);
        var outerMedium=new Medium(medium.absorption(),medium.scattering(),Rgb.ZERO,1,1.33f,.2f).withDensityField(field);
        var innerMedium=new Medium(medium.absorption(),medium.scattering(),Rgb.ZERO,1,1.1f,.2f);
        apply(SceneTransaction.builder(++revision)
                .upsert(SceneInstance.builder(5,5).transform(new AffineTransform(new float[]{2,0,0,0,0,2,0,0,0,0,2,0}))
                        .mediumBoundary(MediumBoundary.surface(outerMedium).withPriority(7)).build())
                .upsert(SceneInstance.builder(6,5).mediumBoundary(MediumBoundary.surface(innerMedium).withPriority(9)).build()).build());
        var nestedOff=capture("nested-IOR-density-caustics-off",request(camera,8,transport,bounded));
        var nestedOn=capture("nested-IOR-density-caustics-on",request(camera,8,transport,bounded,policy));
        double nestedDelta=energy(nestedOn)-energy(nestedOff);
        require(nestedDelta>1e-5,"nested priority/IOR photon gather missing: "+nestedDelta);
        apply(SceneTransaction.builder(++revision).removeLight(30)
                .upsert(SceneLight.point(31,0,0,0).color(1,1,1).intensity(2).range(20).castsShadow(true).build()).build());
        var inside=CameraState.lookAt(0,0,.5,0,0,-1).verticalFieldOfViewDegrees(5).aspectRatio((double)width/height).build();
        var insideOff=capture("inside-nested-caustics-off",request(inside,8,transport,bounded));
        var insideOn=capture("inside-nested-caustics-on",request(inside,8,transport,bounded,policy));
        double insideDelta=energy(insideOn)-energy(insideOff);
        require(insideDelta>1e-5,"interior emitter/camera lost initial medium stack: "+insideDelta);
        require(CausticPhotonPlan.diagnosticPhotonDispatches.sum()-before==6,"each enabled volume frame builds one photon map");
        Files.writeString(OUTPUT.resolve("density-boundary-caustics.json"),String.format(Locale.ROOT,
                "{\"passed\":true,\"nativeFrames\":12,\"photonDispatches\":6,\"densityEnergyDelta\":%.9g,\"boundaryEnergyDelta\":%.9g,\"roughEnergyDelta\":%.9g,\"nestedEnergyDelta\":%.9g,\"insideEnergyDelta\":%.9g,\"zeroDensityEnergy\":%.9g}%n",
                densityDelta,boundaryDelta,roughDelta,nestedDelta,insideDelta,energy(zeroOn)));
    }
    private static int positiveRedPixels(FrameSnapshot before,FrameSnapshot after){
        var a=before.pixels().order(ByteOrder.LITTLE_ENDIAN);var b=after.pixels().order(ByteOrder.LITTLE_ENDIAN);int changed=0;
        for(int pixel=0;pixel<width*height;pixel++)
            if(Float.float16ToFloat(b.getShort(pixel*8))-Float.float16ToFloat(a.getShort(pixel*8))>.001)changed++;
        return changed;
    }
    private static void verifyHeterogeneousScatteringRoughGlass(CameraState camera,MediumTransport budget)throws Exception {
        var field=MediumDensityField.grid(50,4,new AffineTransform(new float[]{.005f,0,0,.5f,0,.005f,0,.5f,0,0,.1f,.5f}));
        var medium=new Medium(new Rgb(.12f,.16f,.20f),new Rgb(.1f,.1f,.1f),Rgb.ZERO,1.2f,1,0).withDensityField(field);
        byte[] rgba=new byte[16];for(int z=0;z<4;z++){rgba[z*4]=(byte)(32+64*z);rgba[z*4+3]=(byte)255;}
        var texture=TextureAsset.builder(50,1,4).colorSpace(TextureAsset.ColorSpace.LINEAR).pixelsRgba8(rgba).build();
        setupThin(1.5f);
        apply(SceneTransaction.builder(++revision).upsert(texture).upsert(receiver().toBuilder().baseColorRgba8(0xff000000).build())
                .upsert(glass(1.5f).toBuilder().roughness(.45f).build())
                .upsert(SceneLight.directional(30,1,0,0).color(1,1,1).intensity(3).castsShadow(true).build()).build());
        var transport=budget.withOpticalSamples(8).withScatteringSteps(64).withScatteringBounces(2);
        var environment=blackSky().toBuilder().cameraMedium(medium).build();
        var lateral=capture("heterogeneous-scattering-rough-glass-lateral",request(camera,8,transport,environment));
        double lateralEnergy=energy(lateral);
        require(lateralEnergy>1e-6,"long lateral medium connection must retain measurable lighting");
        // The cloud is 200 world units wide but only 10 deep. Enabled shadow
        // connections retain medium extinction along their complete light segment.
        apply(SceneTransaction.builder(++revision)
                .upsert(SceneLight.directional(30,0,0,-1).color(1,1,1).intensity(3).castsShadow(true).build()).build());
        var lit=capture("heterogeneous-scattering-rough-glass-lit",request(camera,8,transport,environment));
        double litEnergy=energy(lit);require(litEnergy>.01,"heterogeneous scattering through rough glass must deliver real light energy");
        require(lateralEnergy<litEnergy,"longer lateral cloud connection must attenuate more than the short axial connection");
        apply(SceneTransaction.builder(++revision).removeLight(30).build());
        var dark=capture("heterogeneous-scattering-rough-glass-dark",request(camera,8,transport,environment));
        double darkEnergy=energy(dark);near(darkEnergy,0,1e-6,"heterogeneous scattering through rough glass respects disabled lighting");
        RESULTS.add(String.format(Locale.ROOT,"{\"name\":\"heterogeneous-scattering-rough-glass\",\"lateralEnergy\":%.9g,\"litEnergy\":%.9g,\"darkEnergy\":%.9g}",lateralEnergy,litEnergy,darkEnergy));
    }
    private static void verifyExplicitBoundaryScattering(CameraState camera,MediumTransport budget)throws Exception {
        var transport=budget.withOpticalSamples(8).withScatteringSteps(64).withScatteringBounces(2);
        var medium=new Medium(new Rgb(.12f,.16f,.20f),new Rgb(.1f,.1f,.1f),Rgb.ZERO,1,1.5f,.35f);
        var transform=new AffineTransform(new float[]{100,0,0,0,0,100,0,0,0,0,2,0});
        var boundary=MediumBoundary.surface(medium).withPriority(-7);
        apply(SceneTransaction.builder(++revision).resetScene().upsert(receiver().toBuilder().baseColorRgba8(0xff000000).build())
                .upsert(glass(1.5f)).upsert(plane(1,1,-4,false)).upsert(MediumTransportSelfTest.cube(2,2,1))
                .upsert(SceneInstance.builder(1,1).build())
                .upsert(SceneInstance.builder(2,2).transform(transform).mediumBoundary(boundary).build())
                .upsert(SceneLight.directional(30,0,0,-1).color(1,1,1).intensity(3).castsShadow(true).build()).build());
        double lit=energy(capture("explicit-IOR-scattering-shadow-lit",request(camera,8,transport,blackSky())));
        require(lit>.001,"explicit IOR scattering must receive light through its own boundary");
        apply(SceneTransaction.builder(++revision).removeLight(30).build());
        double dark=energy(capture("explicit-IOR-scattering-shadow-dark",request(camera,8,transport,blackSky())));
        near(dark,0,1e-6,"explicit IOR scattering must not invent light");
        var inside=CameraState.lookAt(0,0,0,0,0,-1).verticalFieldOfViewDegrees(5).aspectRatio((double)width/height).build();
        apply(SceneTransaction.builder(++revision).upsert(SceneLight.point(30,0,0,1).color(1,1,1).intensity(5).range(20).castsShadow(true).build()).build());
        double internal=energy(capture("explicit-IOR-scattering-inside-point",request(inside,8,transport,blackSky())));
        require(internal>.001,"interior camera/light must retain the selected scattering medium");
        var field=MediumDensityField.grid(50,1,new AffineTransform(new float[]{.5f,0,0,.5f,0,.5f,0,.5f,0,0,.5f,.5f}));
        var texture=TextureAsset.builder(50,1,1).colorSpace(TextureAsset.ColorSpace.LINEAR)
                .pixelsRgba8(new byte[]{(byte)160,0,0,(byte)255}).build();
        apply(SceneTransaction.builder(++revision).upsert(texture)
                .upsert(SceneInstance.builder(2,2).transform(transform).mediumBoundary(MediumBoundary.surface(medium.withDensityField(field)).withPriority(-7)).build())
                .upsert(glass(1.5f).toBuilder().roughness(.35f).build()).build());
        double density=energy(capture("explicit-IOR-density-scattering-rough",request(camera,8,transport,blackSky())));
        require(density>.0001,"density scattering and rough IOR boundary must deliver finite real light");
        apply(SceneTransaction.builder(++revision).removeLight(30).build());
        double densityDark=energy(capture("explicit-IOR-density-scattering-rough-dark",request(camera,8,transport,blackSky())));
        near(densityDark,0,1e-6,"density boundary scattering dark control");
        RESULTS.add(String.format(Locale.ROOT,"{\"name\":\"explicit-IOR-boundary-scattering\",\"litEnergy\":%.9g,\"insidePointEnergy\":%.9g,\"densityRoughEnergy\":%.9g,\"darkEnergy\":%.9g}",lit,internal,density,dark+densityDark));
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
    private static MeshAsset causticMirror() {
        double ox=-1/Math.sqrt(5),oz=-2/Math.sqrt(5),nx=ox,nz=1+oz,l=Math.hypot(nx,nz);nx/=l;nz/=l;
        float[] p=new float[12];int at=0;
        for(double[] corner:new double[][]{{-1,-1.2},{1,-1.2},{1,1.2},{-1,1.2}}) {
            p[at++]=(float)(2+nz*corner[0]);p[at++]=(float)corner[1];p[at++]=(float)(-nx*corner[0]);
        }
        return MeshAsset.triangles(2,p,new int[]{0,1,2,0,2,3},2);
    }
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
    private static void verifyPartialPbr(CameraState camera,MediumTransport budget)throws Exception {
        setupThin(1.5f);
        apply(SceneTransaction.builder(++revision).upsert(glass(1.5f).toBuilder().transmission(.5f)
                .emissive(0xff996633,1).build()).build());
        var frame=capture("partial-PBR",request(camera,1,budget,blackSky()));
        var pixels=frame.pixels().order(ByteOrder.LITTLE_ENDIAN);double maximum=0;
        for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
            double ci=-direction(camera,x,y,0,1)[2],ct=Math.sqrt(1-(1-ci*ci)/2.25);
            double rs=(ci-1.5*ct)/(ci+1.5*ct),rp=(1.5*ci-ct)/(1.5*ci+ct),f=(rs*rs+rp*rp)*.5;
            // The local emitter receives 1-transmission. The red UNLIT receiver
            // receives transmission*(1-Fresnel)*eta^2; reflection sees black.
            double[] expected={.1+.5*(1-f)/2.25,.2,.3};
            for(int c=0;c<3;c++) {
                double actual=Float.float16ToFloat(pixels.getShort((y*width+x)*8+c*2));
                maximum=Math.max(maximum,Math.abs(actual-expected[c]));
                near(actual,expected[c],.0015,"partial PBR local/continuation channel="+c+" pixel="+x+","+y);
            }
        }
        RESULTS.add(String.format(Locale.ROOT,"{\"name\":\"partial-PBR-Snell-Fresnel-emission\",\"maximumAbsoluteError\":%.9g}",maximum));
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
    private static RenderFrameRequest request(CameraState camera,int spp,MediumTransport budget,EnvironmentState environment){
        return request(camera,spp,budget,environment,Caustics.disabled());}
    private static RenderFrameRequest request(CameraState camera,int spp,MediumTransport budget,EnvironmentState environment,Caustics caustics){
        return RenderFrameRequest.builder(++sequence,width,height,camera).minimumSceneRevision(revision).environment(environment)
                .mediumTransport(budget).caustics(caustics)
                .antiAliasing(spp==1?AntiAliasingState.disabled():AntiAliasingState.multisampled(spp)).build();}
    private static void apply(SceneTransaction transaction)throws Exception{long deadline=System.nanoTime()+TIMEOUT.toNanos();for(;;)try{renderer.apply(transaction);return;}
        catch(SubmissionRejectedException pending){require(System.nanoTime()<deadline,"optical scene apply timeout");Thread.sleep(1);}}
    private static FrameSnapshot capture(String name,RenderFrameRequest request)throws Exception{long deadline=System.nanoTime()+TIMEOUT.toNanos();
        long captureStarted=benchmarkPolling?System.nanoTime():0L;
        while(!(renderer.trySubmit(request) instanceof Renderer.FrameSubmitted)){require(System.nanoTime()<deadline,"optical admission timeout");pollPause();}accepted++;
        long submitted=benchmarkPolling?System.nanoTime():0L;
        if(benchmarkPolling)submitNanos+=submitted-captureStarted;
        // First admission can compile native pipelines. GPU completion has its own deadline.
        deadline=System.nanoTime()+TIMEOUT.toNanos();
        while(System.nanoTime()<deadline){long pollStarted=benchmarkPolling?System.nanoTime():0L;var ready=renderer.pollFrame();
            if(benchmarkPolling)pollWorkNanos+=System.nanoTime()-pollStarted;
            if(ready.isPresent()&&ready.orElseThrow().frameSequence()==request.sequence()){var frame=ready.orElseThrow();
            require(frame.renderedSceneRevision()==revision&&frame.format()==TextureFormat.RGBA16_FLOAT,"optical final HDR provenance");
            if(name!=null){byte[] bytes=new byte[frame.byteCount()];frame.pixels().get(bytes);Files.write(OUTPUT.resolve(name+".rgba16f"),bytes);}
            if(benchmarkPolling)pollNanos+=System.nanoTime()-submitted;return frame;}
            var health=renderer.health();
            if(health.status()==Renderer.Status.FAILED||health.status()==Renderer.Status.RECOVERING)
                throw new AssertionError("optical frame entered "+health.status()+" before HDR completion: sequence="+request.sequence()+" health="+health+" diagnostics="+renderer.diagnostics());
            long gapStarted=benchmarkPolling?System.nanoTime():0L;pollPause();if(benchmarkPolling)pollGapNanos+=System.nanoTime()-gapStarted;}
        throw new AssertionError("optical final HDR timeout: sequence="+request.sequence()+" health="+renderer.health()+" diagnostics="+renderer.diagnostics());}
    private static void pollPause()throws InterruptedException{if(benchmarkPolling)Thread.onSpinWait();else Thread.sleep(1);}
    private static void expectRejected(RenderFrameRequest frame)throws Exception{long deadline=System.nanoTime()+TIMEOUT.toNanos();for(;;){try{
        if(renderer.trySubmit(frame) instanceof Renderer.FrameSubmitted)throw new AssertionError("IOR-changing volume without surface interaction was silently accepted");
        }catch(FrameValidationException expected){require(expected.reason()==FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,"recoverable optical category");rejected++;return;}
        require(System.nanoTime()<deadline,"optical rejection timeout");Thread.sleep(1);}}
    private static void checkConstant(String name,FrameSnapshot frame,double r,double g,double b){var pixels=frame.pixels().order(ByteOrder.LITTLE_ENDIAN);
        double[] value={r,g,b};for(int pixel=0;pixel<width*height;pixel++)for(int c=0;c<3;c++)near(Float.float16ToFloat(pixels.getShort(pixel*8+c*2)),value[c],.0015,name);
        RESULTS.add("{\"name\":\""+name+"\"}");}
    private static void near(double actual,double expected,double tolerance,String why){require(Double.isFinite(actual)&&Math.abs(actual-expected)<=tolerance,why+" actual="+actual+" expected="+expected);}
    private static void require(boolean condition,String why){if(!condition)throw new AssertionError(why);}
}
