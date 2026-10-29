package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32C;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaBoundaryMediumAbi;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaMediumDensityAbi;

/** Public Renderer final HDR against independent ordered analytic slab segments. */
public final class CudaBoundaryMediumProductionNativeSelfTest {
    private static int WIDTH=37,HEIGHT=29;
    private static final double PLANE_Z=-10;
    private static final Duration TIMEOUT=Duration.ofSeconds(45);
    private static final Path OUTPUT=Path.of(System.getProperty("top.ceroxe.rt.medium.boundaryProductionDirectory",
            "build/luxcore-native-boundary-20261028/production"));
    private static final double[][] EIGHT={{.5,.3333},{.25,.6667},{.75,.1111},{.125,.4444},
            {.625,.7778},{.375,.2222},{.875,.5556},{.0625,.8889}};
    private static final List<String> RESULTS=new ArrayList<>();
    private static Renderer renderer;
    private static long sequence,revision;
    private static int nativeFrames,rejected;
    private static boolean benchmarkPolling;
    private static boolean benchmarkProfileCopy;
    private static boolean benchmarkLiveView;
    private static long benchmarkSnapshotCopyNanos,benchmarkReadFrameNanos;

    public static void main(String[] args) throws Exception {
        if(java.util.Arrays.asList(args).contains("--verify-terminal-mixed")) {
            verifyTerminalMixed();return;
        }
        if(java.util.Arrays.asList(args).contains("--probe-scoped-readback-lifetime")) {
            probeScopedReadbackLifetime();return;
        }
        if(java.util.Arrays.asList(args).contains("--probe-snapshot-after-renderer-close")) {
            probeSnapshotAfterRendererClose();return;
        }
        if(java.util.Arrays.asList(args).contains("--benchmark")) {
            benchmark(java.util.Arrays.asList(args).contains("--control"),
                    java.util.Arrays.asList(args).contains("--pipeline"),
                    java.util.Arrays.asList(args).contains("--live-view"));return;
        }
        Files.createDirectories(OUTPUT);
        String previous=System.getProperty(CudaCameraMediumProfile.ENABLE_PROPERTY);
        System.setProperty(CudaCameraMediumProfile.ENABLE_PROPERTY,"true");
        long before=VulkanGpuSceneFrameSubmitter.diagnosticNativeBoundarySubmissions.sum();
        long fusedBefore=VulkanGpuSceneFrameSubmitter.diagnosticNativeBoundaryFusedSubmissions.sum();
        long crossings=CudaBoundaryMediumManager.diagnosticBoundaryCrossings.sum();
        var a=medium(.11f,.03f,.07f,.02f,.01f,.03f);
        var b=medium(.04f,.16f,.02f,.01f,.04f,0);
        try {
            try(Renderer owned=Renderer.open("vulkan-rt",RendererConfig.builder().maxFramesInFlight(3)
                    .validationEnabled(true).frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F)
                    .temporalRendering(TemporalRenderingOptions.disabled()).build())) {
                renderer=owned;
                var budgets=MediumTransport.balanced().withMaxEvents(32).withMaxDistance(30).withTransmittanceCutoff(0);
                var single=List.of(new Volume(10,-1,1,a,0));
                compare("outside-single",single,6,1,false,false,budgets,Medium.vacuum());
                compare("inside-single",single,0,1,false,false,budgets,Medium.vacuum());
                compare("nested-priority",List.of(new Volume(10,-2,2,a,1),new Volume(11,-1,1,b,9)),
                        6,2,false,false,budgets,Medium.vacuum());
                compare("overlap-tie-latest-entry",List.of(new Volume(10,-1,2,a,4),new Volume(11,-2,1,b,4)),
                        6,1,false,false,budgets,Medium.vacuum());
                compare("overlap-priority-restoration",List.of(new Volume(10,-1,2,a,8),new Volume(11,-2,1,b,-2)),
                        6,1,false,false,budgets,Medium.vacuum());
                compare("frame-local-eight-SPP",single,6,8,true,false,budgets,Medium.vacuum());
                compare("exact-projection-jitter",single,6,2,false,true,budgets,Medium.vacuum());
                compare("distance-clamped-before-exit",single,6,1,false,false,budgets.withMaxDistance(5.5f),a);
                compare("event-budget-preserves-emission",single,6,1,false,false,budgets.withMaxEvents(2),Medium.vacuum());
                compare("cutoff-preserves-emission",List.of(new Volume(10,-1,1,medium(1,1,1,.2f,.1f,.05f),0)),
                        6,1,false,false,budgets.withTransmittanceCutoff(.5f),Medium.vacuum());
                var thirtyTwo=new ArrayList<Volume>();
                for(int i=0;i<32;i++) thirtyTwo.add(new Volume(100+i,-20-i,20+i,
                        medium(.005f*(i+1),.002f,.003f,.001f,0,0),i));
                compare("32-initial-volumes-terminal-before-exit",thirtyTwo,0,1,false,false,budgets,Medium.vacuum());
                var thirtyThree=new ArrayList<>(thirtyTwo);
                thirtyThree.add(new Volume(132,-60,60,a,32));
                setup(thirtyThree,false);
                expectRejected(request(camera(0,false),1,budgets,Medium.vacuum(),FramePrimitiveBatch.empty()),
                        FrameValidationException.Reason.MEDIUM_NESTING_EXCEEDS_LIMIT,"33-initial-volume-overflow");
                compare("overflow-recovery",single,6,1,false,false,budgets,Medium.vacuum());
                setup(single,false);
                var volumeOnlyReference=capture(null,request(camera(6,false),1,budgets,Medium.vacuum(),FramePrimitiveBatch.empty()));
                nativeFrames++;
                var roughGlass=MaterialAsset.builder(2).transmission(1).roughness(.2f)
                        .indexOfRefraction(1.5f).doubleSided(true).build();
                apply(SceneTransaction.builder(++revision).upsert(roughGlass)
                        .upsert(MediumTransportSelfTest.cube(3,2,1))
                        .upsert(SceneInstance.builder(10,3).transform(single.get(0).transform())
                                .mediumBoundary(MediumBoundary.surface(a)).build()).build());
                long opticalBefore=VulkanGpuSceneFrameSubmitter.diagnosticNativeOpticalSubmissions.sum();
                var roughGlassBoundary=capture("material-shaded-boundary-optical",
                        request(camera(6,false),1,budgets,Medium.vacuum(),FramePrimitiveBatch.empty()));
                require(VulkanGpuSceneFrameSubmitter.diagnosticNativeOpticalSubmissions.sum()==opticalBefore+1,
                        "material-shaded dielectric boundary bypassed the native optical executor");
                compareEquivalentFrames("unit-IOR rough-glass boundary",volumeOnlyReference,roughGlassBoundary);
                require(renderer.health().activeFailure().isEmpty(),"material-shaded optical boundary poisoned the renderer session");
                compare("surface-rejection-recovery",single,6,1,false,false,budgets,Medium.vacuum());
                var shortWorldDomain=MediumDensityField.grid(50,2,new AffineTransform(new float[]{
                        .005f,0,0,.5f,0,.005f,0,.5f,0,0,5,.5f}));
                compare("environment-density-short-domain-one-step",List.of(),6,1,false,false,
                        budgets.withScatteringSteps(1),a.withDensityField(shortWorldDomain));
                var localDomain=MediumDensityField.grid(50,2,new AffineTransform(new float[]{
                        .5f,0,0,.5f,0,.5f,0,.5f,0,0,2,.5f}));
                compare("scaled-translated-boundary-density",List.of(new Volume(10,-4,2,a.withDensityField(localDomain),3)),
                        6,2,false,false,budgets.withScatteringSteps(1),Medium.vacuum());
                long uploadsBefore=CudaBoundaryMediumManager.diagnosticAssetUploads.sum();
                long bytesBefore=CudaBoundaryMediumManager.diagnosticUploadedBytes.sum();
                var cachedA=capture("density-cached-first",request(camera(6,false),2,budgets.withScatteringSteps(1),
                        Medium.vacuum(),FramePrimitiveBatch.empty()));
                var cachedB=capture("density-cached-second",request(camera(6,false),2,budgets.withScatteringSteps(1),
                        Medium.vacuum(),FramePrimitiveBatch.empty()));
                nativeFrames+=2;
                require(cachedA.pixels().equals(cachedB.pixels()),"cached immutable density scene changed completed HDR");
                require(CudaBoundaryMediumManager.diagnosticAssetUploads.sum()==uploadsBefore,
                        "unchanged density assets were uploaded again across frames");
                require(CudaBoundaryMediumManager.diagnosticUploadedBytes.sum()-bytesBefore==2L*256,
                        "small cached frames must upload only the per-frame header");
                // Replacing managers must drain borrowed final consumers before releasing their imports.
                compare("mode-switch-camera-before-boundary",List.of(),6,1,false,false,budgets,a);
                compare("mode-switch-back-to-boundary",single,6,1,false,false,budgets,Medium.vacuum());
                compare("mode-switch-camera-after-boundary",List.of(),6,1,false,false,budgets,b);
                WIDTH=137;HEIGHT=101;
                compare("resize-eight-SPP-complete-frame",single,6,8,true,false,budgets,Medium.vacuum());
                WIDTH=1920;HEIGHT=1080;
                compare("full-HD-bounded-continuation-tiles",single,6,1,false,false,budgets,Medium.vacuum());
                WIDTH=41;HEIGHT=31;
                compare("resize-down-after-full-HD",single,6,2,false,false,budgets,Medium.vacuum());
                require(renderer.health().activeFailure().isEmpty(),"boundary renderer unhealthy");
            }
            long routes=VulkanGpuSceneFrameSubmitter.diagnosticNativeBoundarySubmissions.sum()-before;
            require(routes==nativeFrames,"a public boundary frame bypassed native controller: "+routes+" / "+nativeFrames);
            long fused=VulkanGpuSceneFrameSubmitter.diagnosticNativeBoundaryFusedSubmissions.sum()-fusedBefore;
            require(fused>0,"no full-frame boundary consumer used the fused final submission");
            require(CudaBoundaryMediumManager.diagnosticBoundaryCrossings.sum()>crossings,"no real boundary crossings");
            Files.writeString(OUTPUT.resolve("result.json"),"{\n  \"passed\":true,\n  \"scope\":\"public-vulkan-final-HDR-closed-boundaries\",\n"
                    +"  \"nativeFrames\":"+nativeFrames+",\n  \"fusedFrames\":"+fused+",\n  \"rejections\":"+rejected+",\n  \"comparisons\":[\n"
                    +String.join(",\n",RESULTS)+"\n  ]\n}\n");
            System.out.println("CudaBoundaryMediumProductionNativeSelfTest passed: analytic public HDR comparisons="+RESULTS.size()
                    +" nativeFrames="+nativeFrames+" fusedFrames="+fused+" recoverableRejections="+rejected);
        } finally {
            if(previous==null)System.clearProperty(CudaCameraMediumProfile.ENABLE_PROPERTY);
            else System.setProperty(CudaCameraMediumProfile.ENABLE_PROPERTY,previous);
        }
    }

    private static void probeScopedReadbackLifetime() throws Exception {
        try(Renderer owned=Renderer.open("vulkan-rt",RendererConfig.builder().maxFramesInFlight(2)
                .validationEnabled(false).frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F)
                .temporalRendering(TemporalRenderingOptions.disabled()).build())) {
            renderer=owned;
            setup(List.of(),false);
            RenderFrameRequest frame=request(camera(6,false),1,MediumTransport.balanced(),
                    Medium.vacuum(),FramePrimitiveBatch.empty());
            long deadline=System.nanoTime()+TIMEOUT.toNanos();
            while(!(renderer.trySubmit(frame) instanceof Renderer.FrameSubmitted)) {
                require(System.nanoTime()<deadline,"scoped readback probe admission timeout");
                Thread.onSpinWait();
            }
            ByteBuffer[] retained={null};
            boolean consumed=false;
            while(!consumed) {
                require(System.nanoTime()<deadline,"scoped readback probe completion timeout");
                consumed=renderer.readFrame(view->retained[0]=view.pixels());
                if(!consumed)Thread.onSpinWait();
            }
            require(retained[0]!=null,"readback callback did not expose pixel storage");
            try {
                retained[0].get(0);
                throw new AssertionError("mapped CPU readback remained accessible beyond its callback");
            } catch(IllegalStateException expected) {
                System.out.println("ScopedReadbackLifetimeProbe passed: mapped pixels expire at callback return");
            }
        } finally {
            renderer=null;
        }
    }

    /** Mixed pixels enter both terminal and retained-payload branches of the same SPP8 consumer. */
    private static void verifyTerminalMixed() throws Exception {
        Files.createDirectories(OUTPUT);
        String enable=System.getProperty(CudaCameraMediumProfile.ENABLE_PROPERTY);
        String terminal=System.getProperty("top.ceroxe.rt.medium.cuda.boundaryTerminalRadiance");
        System.setProperty(CudaCameraMediumProfile.ENABLE_PROPERTY,"true");
        try(Renderer owned=Renderer.open("vulkan-rt",RendererConfig.builder().maxFramesInFlight(3)
                .validationEnabled(true).frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F)
                .temporalRendering(TemporalRenderingOptions.disabled()).build())) {
            renderer=owned;
            var volumes=List.of(new Volume(10,-1,2,medium(.11f,.03f,.07f,.02f,.01f,.03f),8));
            setup(volumes,false);
            float halfX=.43f,halfY=.31f;
            apply(SceneTransaction.builder(++revision)
                    .upsert(MeshAsset.triangles(1,new float[]{-halfX,-halfY,(float)PLANE_Z,halfX,-halfY,(float)PLANE_Z,
                            halfX,halfY,(float)PLANE_Z,-halfX,halfY,(float)PLANE_Z},new int[]{0,1,2,2,3,0},1))
                    .upsert(SceneInstance.builder(1,1).renderState(InstanceRenderState.builder()
                            .objectMask(1).outline(OutlineStyle.of(0xff20_40ff,2)).build()).build()).build());
            var camera=camera(6,false);
            int mixed=0;
            for(int y=0;y<HEIGHT;y++)for(int x=0;x<WIDTH;x++) {
                int hits=0;
                for(int sample=0;sample<8;sample++) {
                    double[] ray=direction(camera,x,y,sample,8);double distance=(PLANE_Z-6)/ray[2];
                    if(Math.abs(ray[0]*distance)<halfX&&Math.abs(ray[1]*distance)<halfY)hits++;
                }
                if(hits>0&&hits<8)mixed++;
            }
            require(mixed>0,"mixed terminal/fallback sample coverage was not exercised");
            var budget=MediumTransport.balanced().withMaxEvents(32).withMaxDistance(30).withTransmittanceCutoff(0);
            System.setProperty("top.ceroxe.rt.medium.cuda.boundaryTerminalRadiance","false");
            var baseline=capture("mixed-full",request(camera,8,budget,Medium.vacuum(),FramePrimitiveBatch.empty()));
            System.setProperty("top.ceroxe.rt.medium.cuda.boundaryTerminalRadiance","true");
            var candidate=capture("mixed-terminal",request(camera,8,budget,Medium.vacuum(),FramePrimitiveBatch.empty()));
            var left=baseline.pixels().order(ByteOrder.nativeOrder());var right=candidate.pixels().order(ByteOrder.nativeOrder());
            int differences=0,maximumUlp=0;
            for(int index=0;index<left.remaining()/2;index++) {
                short a=left.getShort(index*2),b=right.getShort(index*2);
                require(Float.isFinite(Float.float16ToFloat(a))&&Float.isFinite(Float.float16ToFloat(b)),
                        "nonfinite mixed pixel");
                if(index%4==3)require(a==b&&b==(short)0x3c00,"mixed alpha changed");
                int ulp=Math.abs(Short.toUnsignedInt(a)-Short.toUnsignedInt(b));
                maximumUlp=Math.max(maximumUlp,ulp);if(a!=b)differences++;
                require(ulp<=1,"mixed terminal/fallback output exceeds one half ULP");
            }
            require(renderer.health().activeFailure().isEmpty(),"mixed terminal renderer unhealthy");
            Files.writeString(OUTPUT.resolve("mixed-result.json"),"{\"mixedPixels\":"+mixed
                    +",\"differingChannels\":"+differences+",\"maxHalfULP\":"+maximumUlp+",\"alphaDifferences\":0}\n");
            System.out.println("Mixed terminal/fallback SPP8 passed: mixedPixels="+mixed
                    +" maxHalfULP="+maximumUlp+" differingChannels="+differences);
        } finally {
            renderer=null;
            if(enable==null)System.clearProperty(CudaCameraMediumProfile.ENABLE_PROPERTY);
            else System.setProperty(CudaCameraMediumProfile.ENABLE_PROPERTY,enable);
            if(terminal==null)System.clearProperty("top.ceroxe.rt.medium.cuda.boundaryTerminalRadiance");
            else System.setProperty("top.ceroxe.rt.medium.cuda.boundaryTerminalRadiance",terminal);
        }
    }

    private static void probeSnapshotAfterRendererClose() throws Exception {
        FrameSnapshot[] retained={null};
        Renderer owned=Renderer.open("vulkan-rt",RendererConfig.builder().maxFramesInFlight(2)
                .validationEnabled(false).frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F)
                .temporalRendering(TemporalRenderingOptions.disabled()).build());
        try {
            renderer=owned;
            setup(List.of(),false);
            RenderFrameRequest frame=request(camera(6,false),1,MediumTransport.balanced(),
                    Medium.vacuum(),FramePrimitiveBatch.empty());
            long deadline=System.nanoTime()+TIMEOUT.toNanos();
            while(!(renderer.trySubmit(frame) instanceof Renderer.FrameSubmitted)) {
                require(System.nanoTime()<deadline,"snapshot lifetime probe admission timeout");
                Thread.onSpinWait();
            }
            boolean consumed=false;
            while(!consumed) {
                require(System.nanoTime()<deadline,"snapshot lifetime probe completion timeout");
                consumed=renderer.readFrame(view->retained[0]=FrameSnapshot.copyOf(view));
                if(!consumed)Thread.onSpinWait();
            }
            require(retained[0]!=null&&retained[0].byteCount()==WIDTH*HEIGHT*8,
                    "immutable snapshot did not retain the complete HDR frame");
        } finally {
            renderer=null;
            owned.close();
        }
        for(int i=0;i<3;i++) {System.gc();Thread.sleep(20);}
        ByteBuffer pixels=retained[0].pixels();
        require(pixels.isReadOnly()&&pixels.remaining()==WIDTH*HEIGHT*8,
                "snapshot view changed after renderer close or GC");
        byte first=pixels.get(0);pixels.position(0);
        System.out.println("SnapshotAfterRendererCloseProbe passed: immutable HDR bytes remain readable after close and GC, firstByte="+Byte.toUnsignedInt(first));
    }

    private static void compare(String name,List<Volume> volumes,double cameraZ,int samples,boolean local,
                                boolean exact,MediumTransport budget,Medium environment) throws Exception {
        setup(List.of(),false);
        CameraState camera=camera(cameraZ,exact);
        var vacuum=capture(name+"-vacuum",request(camera,samples,budget,Medium.vacuum(),FramePrimitiveBatch.empty()));
        setup(volumes,local);
        FramePrimitiveBatch batch=FramePrimitiveBatch.empty();
        if(local) {
            var builder=FramePrimitiveBatch.builder();
            for(var volume:volumes)builder.add(PrimitiveInstance.builder(2).transform(volume.transform())
                    .mediumBoundary(volume.boundary()).build());
            batch=builder.build();
        }
        var actual=capture(name+"-native",request(camera,samples,budget,environment,batch));
        if(!volumes.isEmpty() || !environment.densityField().isUniform())nativeFrames++;
        compareFrames(name,volumes,cameraZ,samples,budget,environment,camera,vacuum,actual);
    }

    private static void compareFrames(String name,List<Volume> volumes,double cameraZ,int samples,
            MediumTransport budget,Medium environment,CameraState camera,FrameSnapshot vacuum,FrameSnapshot actual) {
        var a=vacuum.pixels().order(ByteOrder.LITTLE_ENDIAN);
        var b=actual.pixels().order(ByteOrder.LITTLE_ENDIAN);
        double maximum=0;
        for(int y=0;y<HEIGHT;y++)for(int x=0;x<WIDTH;x++) {
            double[][] reference=new double[samples][];
            for(int sample=0;sample<samples;sample++)reference[sample]=reference(volumes,cameraZ,
                    direction(camera,x,y,sample,samples),budget,environment);
            for(int channel=0;channel<3;channel++) {
                int at=(y*WIDTH+x)*8+channel*2;
                double base=Float.float16ToFloat(a.getShort(at)),expected=0;
                for(var value:reference)expected+=(base*value[channel]+value[channel+3])/samples;
                double value=Float.float16ToFloat(b.getShort(at)),error=Math.abs(value-expected);
                maximum=Math.max(maximum,error);
                require(Double.isFinite(value)&&error<=.0015*Math.max(1,Math.abs(expected)),name+" pixel="+x+","+y
                        +" channel="+channel+" actual="+value+" expected="+expected+" error="+error);
            }
            require(a.getShort((y*WIDTH+x)*8+6)==b.getShort((y*WIDTH+x)*8+6),"boundary alpha changed");
        }
        RESULTS.add(String.format(Locale.ROOT,"    {\"name\":\"%s\",\"samples\":%d,\"volumes\":%d,\"maximumAbsoluteError\":%.12g}",
                name,samples,volumes.size(),maximum));
        System.out.printf(Locale.ROOT,"Native boundary public HDR comparison=%s SPP=%d volumes=%d maxAbsolute=%.9g%n",
                name,samples,volumes.size(),maximum);
    }

    private static void compareEquivalentFrames(String name,FrameSnapshot reference,FrameSnapshot actual) {
        var expected=reference.pixels().order(ByteOrder.LITTLE_ENDIAN);
        var pixels=actual.pixels().order(ByteOrder.LITTLE_ENDIAN);double maximum=0,signal=0;
        for(int pixel=0;pixel<WIDTH*HEIGHT;pixel++) {
            for(int channel=0;channel<3;channel++) {
                int offset=pixel*8+channel*2;
                double a=Float.float16ToFloat(expected.getShort(offset));
                double b=Float.float16ToFloat(pixels.getShort(offset));
                require(Double.isFinite(b)&&b>=0,name+" finite nonnegative HDR");
                maximum=Math.max(maximum,Math.abs(a-b));signal=Math.max(signal,b);
                require(Math.abs(a-b)<=.0015*Math.max(1,Math.abs(a)),
                        name+" differs from the independent volume-only HDR reference at pixel="+pixel+" channel="+channel);
            }
            require(expected.getShort(pixel*8+6)==pixels.getShort(pixel*8+6),name+" alpha changed");
        }
        require(signal>0,name+" produced black HDR");
        RESULTS.add(String.format(Locale.ROOT,"    {\"name\":\"%s\",\"maximumAbsoluteError\":%.12g,\"signal\":%.9g}",
                name,maximum,signal));
    }

    /** Same compiled public workload can run against a frozen legacy renderer using --control. */
    private static void benchmark(boolean control,boolean pipeline,boolean liveView) throws Exception {
        WIDTH=Integer.getInteger("top.ceroxe.rt.medium.boundaryBenchmarkWidth",640);
        HEIGHT=Integer.getInteger("top.ceroxe.rt.medium.boundaryBenchmarkHeight",360);
        int warmup=Integer.getInteger("top.ceroxe.rt.medium.boundaryBenchmarkWarmup",64);
        int frames=Integer.getInteger("top.ceroxe.rt.medium.boundaryBenchmarkFrames",480);
        benchmarkProfileCopy=Boolean.getBoolean("top.ceroxe.rt.medium.boundaryBenchmarkProfileCopy");
        benchmarkLiveView=liveView;
        require(WIDTH>0&&HEIGHT>0&&warmup>=1&&frames>=8,"invalid boundary benchmark configuration");
        long residentLimit=Long.getLong("top.ceroxe.rt.medium.cuda.maxResidentBytes",512L*1024*1024);
        var memoryPlan=CudaBoundaryMediumAbi.planPacket(WIDTH,HEIGHT,1,2,
                CudaMediumDensityAbi.Scene.empty(),residentLimit-65_536L,
                Boolean.parseBoolean(System.getProperty("top.ceroxe.rt.medium.cuda.boundaryCompactPacket","true"))?24:32);
        long tileCount=((long)WIDTH*HEIGHT+memoryPlan.tilePixels()-1)/memoryPlan.tilePixels();
        String previous=System.getProperty(CudaCameraMediumProfile.ENABLE_PROPERTY);
        System.setProperty(CudaCameraMediumProfile.ENABLE_PROPERTY,Boolean.toString(!control));
        benchmarkPolling=true;
        Files.createDirectories(OUTPUT);
        var budget=MediumTransport.balanced().withMaxEvents(32).withMaxDistance(30).withTransmittanceCutoff(0);
        var volumes=List.of(new Volume(10,-1,2,medium(.11f,.03f,.07f,.02f,.01f,.03f),8),
                new Volume(11,-2,1,medium(.04f,.16f,.02f,.01f,.04f,0),-2));
        var camera=camera(6,false);
        try(Renderer owned=Renderer.open("vulkan-rt",RendererConfig.builder().maxFramesInFlight(3)
                .validationEnabled(false).frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F)
                .temporalRendering(TemporalRenderingOptions.disabled()).build())) {
            renderer=owned;
            setup(List.of(),false);
            var vacuum=capture("benchmark-vacuum",request(camera,1,budget,Medium.vacuum(),FramePrimitiveBatch.empty()));
            setup(volumes,false);
            long started=System.nanoTime();
            var cold=capture(null,request(camera,1,budget,Medium.vacuum(),FramePrimitiveBatch.empty()));
            long coldNanos=System.nanoTime()-started;
            byte[] bytes=new byte[cold.byteCount()];cold.pixels().get(bytes);
            Files.write(OUTPUT.resolve("benchmark-medium.rgba16f"),bytes);
            compareFrames("benchmark-analytic-reference",volumes,6,1,budget,Medium.vacuum(),camera,vacuum,cold);
            if(pipeline) {
                runPipelinedFrames(camera,budget,warmup,null);
            } else if(liveView) {
                for(int index=0;index<warmup;index++)consumeLiveFrame(request(camera,1,budget,Medium.vacuum(),FramePrimitiveBatch.empty()));
            } else {
                for(int index=0;index<warmup;index++)
                    capture(null,request(camera,1,budget,Medium.vacuum(),FramePrimitiveBatch.empty()));
            }
            long[] nanos=new long[frames];
            long batchStarted=System.nanoTime();
            if(pipeline) {
                runPipelinedFrames(camera,budget,frames,nanos);
            } else if(liveView) {
                for(int index=0;index<frames;index++) {
                    long frameStarted=System.nanoTime();
                    consumeLiveFrame(request(camera,1,budget,Medium.vacuum(),FramePrimitiveBatch.empty()));
                    nanos[index]=System.nanoTime()-frameStarted;
                }
            } else {
                for(int index=0;index<frames;index++) {
                    long frameStarted=System.nanoTime();
                    capture(null,request(camera,1,budget,Medium.vacuum(),FramePrimitiveBatch.empty()));
                    nanos[index]=System.nanoTime()-frameStarted;
                }
            }
            long batchNanos=System.nanoTime()-batchStarted;
            long[] sorted=nanos.clone();java.util.Arrays.sort(sorted);
            require(renderer.health().activeFailure().isEmpty(),"boundary benchmark renderer unhealthy");
            var gpuTiming=renderer.diagnostics().frameGpuTiming();
            StringBuilder json=new StringBuilder(String.format(Locale.ROOT,
                    "{\n  \"mode\":\"%s\",\n  \"pipeline\":%s,\n  \"liveView\":%s,\n  \"width\":%d,\n  \"height\":%d,\n  \"samplesPerPixel\":1,\n  \"volumes\":2,\n  \"warmupFrames\":%d,\n  \"completedFrames\":%d,\n  \"output\":\"%s\",\n  \"coldFirstMediumMs\":%.6f,\n  \"batchWallMs\":%.6f,\n  \"completedFramesPerSecond\":%.6f,\n  \"medianEndToEndMs\":%.6f,\n  \"p95EndToEndMs\":%.6f,\n  \"frameGpuTimingEnabled\":%s,\n  \"frameGpuTimingSamples\":%d,\n  \"frameGpuTimingAverageMs\":%.6f,\n  \"frameGpuTimingMaxMs\":%.6f,\n  \"profiledSnapshotCopyTotalMs\":%.6f,\n  \"profiledReadFrameHostTotalMs\":%.6f,\n  \"frameNanos\":[",
                    control?"control":"native",pipeline,liveView,WIDTH,HEIGHT,warmup,frames,
                    liveView?"borrowed RGBA16F readFrame callback; CRC32C scans complete frame; valid only inside callback"
                            :"complete immutable CPU RGBA16F snapshot",coldNanos/1e6,batchNanos/1e6,
                    frames*1e9/batchNanos,sorted[frames/2]/1e6,sorted[Math.min(frames-1,(int)Math.ceil(frames*.95)-1)]/1e6,
                    gpuTiming.enabled(),gpuTiming.completedSamples(),gpuTiming.averageTotalNanos()/1e6,
                    gpuTiming.maxTotalNanos()/1e6,benchmarkSnapshotCopyNanos/1e6,benchmarkReadFrameNanos/1e6));
            for(int index=0;index<frames;index++) {if(index>0)json.append(',');json.append(nanos[index]);}
            json.append("]\n}\n");Files.writeString(OUTPUT.resolve("benchmark.json"),json);
            System.out.printf(Locale.ROOT,"Boundary benchmark mode=%s pipeline=%s extent=%dx%d completed=%d framesPerSecond=%.6f%n",
                    control?"control":"native",pipeline,WIDTH,HEIGHT,frames,frames*1e9/batchNanos);
            System.out.printf(Locale.ROOT,"Boundary benchmark plan residentLimitBytes=%d storageBytes=%d tilePixels=%d tiles=%d%n",
                    residentLimit,memoryPlan.bytes(),memoryPlan.tilePixels(),tileCount);
        } finally {
            benchmarkPolling=false;
            if(previous==null)System.clearProperty(CudaCameraMediumProfile.ENABLE_PROPERTY);
            else System.setProperty(CudaCameraMediumProfile.ENABLE_PROPERTY,previous);
        }
    }

    private static long consumeLiveFrame(RenderFrameRequest request) throws Exception {
        long deadline=System.nanoTime()+TIMEOUT.toNanos();
        while(!(renderer.trySubmit(request) instanceof Renderer.FrameSubmitted)) {
            require(System.nanoTime()<deadline,"live-view frame admission timeout");
            Thread.onSpinWait();
        }
        CRC32C checksum=new CRC32C();
        long[] hash={-1L};
        while(System.nanoTime()<deadline) {
            if(renderer.readFrame(view->{
                require(view.frameSequence()==request.sequence(),"live-view frame sequence changed");
                require(view.renderedSceneRevision()==revision&&view.format()==TextureFormat.RGBA16_FLOAT,
                        "live-view HDR provenance");
                ByteBuffer pixels=view.pixels();
                require(pixels.remaining()==Math.multiplyExact(Math.multiplyExact(WIDTH,HEIGHT),8),
                        "live-view did not expose the complete HDR frame");
                checksum.reset();
                checksum.update(pixels);
                hash[0]=checksum.getValue();
            })) {
                require(hash[0]>=0L,"live-view callback did not consume pixel data");
                return hash[0];
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("live-view frame timeout");
    }

    private static void runPipelinedFrames(CameraState camera,MediumTransport budget,int count,long[] nanos)
            throws Exception {
        Deque<Long> pending=new ArrayDeque<>();
        long deadline=System.nanoTime()+TIMEOUT.toNanos();
        long firstSequence=sequence+1;
        for(int index=0;index<count;index++) {
            RenderFrameRequest next=request(camera,1,budget,Medium.vacuum(),FramePrimitiveBatch.empty());
            long frameStarted=System.nanoTime();
            while(true) {
                require(System.nanoTime()<deadline,"pipelined boundary admission timeout");
                if(renderer.trySubmit(next) instanceof Renderer.FrameSubmitted) break;
                if(!pending.isEmpty()) {
                    long previous=pending.removeFirst();
                    FrameSnapshot completed=readExpectedFrame(previous,deadline);
                    require(completed.frameSequence()==previous,"pipelined frame sequence changed");
                } else Thread.onSpinWait();
            }
            pending.addLast(next.sequence());
            if(pending.size()>1) {
                long previous=pending.removeFirst();
                FrameSnapshot completed=readExpectedFrame(previous,deadline);
                if(nanos!=null)nanos[index-1]=System.nanoTime()-frameStarted;
                require(completed.frameSequence()==previous,"pipelined frame sequence changed");
            }
        }
        while(!pending.isEmpty()) {
            long expected=pending.removeFirst();
            long started=System.nanoTime();
            FrameSnapshot completed=readExpectedFrame(expected,deadline);
            if(nanos!=null)nanos[(int)(expected-firstSequence)]=System.nanoTime()-started;
            require(completed.frameSequence()==expected,"pipelined drain changed frame sequence");
        }
    }

    private static FrameSnapshot readExpectedFrame(long expected,long deadline) throws Exception {
        while(System.nanoTime()<deadline) {
            FrameSnapshot[] result={null};
            long readStarted=System.nanoTime();
            boolean consumed=renderer.readFrame(view->result[0]=copySnapshot(view));
            benchmarkReadFrameNanos+=System.nanoTime()-readStarted;
            if(consumed) {
                FrameSnapshot frame=result[0];
                require(frame!=null&&frame.frameSequence()==expected,
                        "pipelined public API skipped frame: expected="+expected+", actual="+
                                (frame==null?"none":frame.frameSequence()));
                require(frame.renderedSceneRevision()==revision&&frame.format()==TextureFormat.RGBA16_FLOAT,
                        "pipelined final HDR provenance");
                return frame;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("pipelined frame timeout: "+expected);
    }

    private static FrameSnapshot copySnapshot(FrameView view) {
        long started=System.nanoTime();
        FrameSnapshot snapshot=FrameSnapshot.copyOf(view);
        benchmarkSnapshotCopyNanos+=System.nanoTime()-started;
        return snapshot;
    }

    /** Ordered ray/slab crossings; no renderer/controller implementation is called by the reference. */
    private static double[] reference(List<Volume> volumes,double cameraZ,double[] direction,MediumTransport budget,Medium environment) {
        double end=(PLANE_Z-cameraZ)/direction[2],previous=0;
        var active=new ArrayList<Volume>();
        var events=new ArrayList<Event>();
        for(var volume:volumes) {
            if(cameraZ>=volume.minZ&&cameraZ<=volume.maxZ)active.add(volume);
            double enter=(volume.maxZ-cameraZ)/direction[2],exit=(volume.minZ-cameraZ)/direction[2];
            if(enter>0&&enter<end)events.add(new Event(enter,volume,true));
            if(exit>0&&exit<end)events.add(new Event(exit,volume,false));
        }
        active.sort(Comparator.comparingInt(Volume::priority).thenComparingLong(Volume::identity));
        events.sort(Comparator.comparingDouble(Event::distance));
        double[] result={1,1,1,0,0,0};
        int count=0;
        for(var event:events) {
            integrateInterval(result,active,environment,Math.min(previous,budget.maxDistance()),
                    Math.min(event.distance,budget.maxDistance()),cameraZ,direction);
            previous=event.distance;
            if(event.entering)active.add(event.volume);else active.remove(event.volume);
            count++;
            if(count>=budget.maxEvents()||Math.max(result[0],Math.max(result[1],result[2]))<=budget.transmittanceCutoff()) {
                result[0]=result[1]=result[2]=0;
                return result;
            }
        }
        integrateInterval(result,active,environment,Math.min(previous,budget.maxDistance()),
                Math.min(end,budget.maxDistance()),cameraZ,direction);
        return result;
    }
    private static void integrateInterval(double[] result,List<Volume> active,Medium environment,
                                          double first,double last,double cameraZ,double[] direction) {
        Volume selected=null;
        for(var volume:active)if(selected==null||volume.priority>=selected.priority)selected=volume;
        Medium medium=selected==null?environment:selected.medium;
        if(!medium.densityField().isUniform()) {
            // Independent public affine slab clipping. The fixture grid has constant red=128/255.
            double[] origin={0,0,cameraZ},ray=direction.clone();
            if(selected!=null) {
                double half=(selected.maxZ-selected.minZ)/2,center=(selected.maxZ+selected.minZ)/2;
                origin[2]=(cameraZ-center)/half;
                ray[0]/=100;ray[1]/=100;ray[2]/=half;
            }
            var transform=medium.densityField().transform();
            for(int axis=0;axis<3;axis++) {
                double o=transform.element(axis*4+3),d=0;
                for(int column=0;column<3;column++) {
                    o+=transform.element(axis*4+column)*origin[column];
                    d+=transform.element(axis*4+column)*ray[column];
                }
                if(Math.abs(d)<1e-20) {if(o<0||o>1)return;}
                else {
                    double a=-o/d,b=(1-o)/d;
                    first=Math.max(first,Math.min(a,b));last=Math.min(last,Math.max(a,b));
                }
            }
            integrate(result,medium,Math.max(0,last-first)*(128/255.0));
        } else integrate(result,medium,Math.max(0,last-first));
    }
    private static void integrate(double[] result,Medium medium,double distance) {
        float[] a={medium.absorption().red()*medium.density(),medium.absorption().green()*medium.density(),medium.absorption().blue()*medium.density()};
        float[] e={medium.emission().red()*medium.density(),medium.emission().green()*medium.density(),medium.emission().blue()*medium.density()};
        for(int c=0;c<3;c++) {
            result[c+3]+=result[c]*e[c]*(a[c]==0?distance:-Math.expm1(-a[c]*distance)/a[c]);
            result[c]*=Math.exp(-a[c]*distance);
        }
    }
    private static double[] direction(CameraState camera,int x,int y,int sample,int samples) {
        double[] offset=samples==1?new double[]{.5,.5}:samples==2?(sample==0?new double[]{.25,.25}:new double[]{.75,.75}):EIGHT[sample];
        if(camera.hasExactProjection()) {
            var ray=camera.exactProjection().rayForPixel(x+offset[0]-.5,y+offset[1]-.5);
            return new double[]{ray.directionX(),ray.directionY(),ray.directionZ()};
        }
        double fy=Math.tan(Math.toRadians(5)*.5),fx=fy*WIDTH/HEIGHT;
        double dx=((x+offset[0])/WIDTH*2-1)*fx,dy=-((y+offset[1])/HEIGHT*2-1)*fy;
        double length=Math.sqrt(dx*dx+dy*dy+1);
        return new double[]{dx/length,dy/length,-1/length};
    }
    private static CameraState camera(double z,boolean exact) {
        if(!exact)return CameraState.lookAt(0,0,z,0,0,z-1).verticalFieldOfViewDegrees(5).aspectRatio((double)WIDTH/HEIGHT).build();
        double f=1/Math.tan(Math.toRadians(5)*.5),near=.1,far=100;
        return CameraState.exactProjection(ExactProjectionState.builder(WIDTH,HEIGHT)
                .matrixLayout(ExactProjectionState.MatrixLayout.ROW_MAJOR)
                .coordinateSystem(ExactProjectionState.CoordinateSystem.RIGHT_HANDED_NEGATIVE_Z_FORWARD)
                .cameraToWorld(new double[]{1,0,0,0,0,1,0,0,0,0,1,z,0,0,0,1})
                .clipFromView(new double[]{f*HEIGHT/WIDTH,0,.01,0,0,f,-.02,0,0,0,far/(near-far),far*near/(near-far),0,0,-1,0})
                .depthConvention(ExactProjectionState.DepthConvention.ZERO_TO_ONE)
                .jitter(ExactProjectionState.JitterConvention.PIXEL_CENTER_OFFSET,.25,-.125).build());
    }
    private static RenderFrameRequest request(CameraState camera,int samples,MediumTransport budget,Medium medium,FramePrimitiveBatch batch) {
        return RenderFrameRequest.builder(++sequence,WIDTH,HEIGHT,camera).minimumSceneRevision(revision)
                .clock(FrameClock.of(0,0)).environment(EnvironmentState.builder().skyRadiance(.2f,.4f,.6f).cameraMedium(medium).build())
                .antiAliasing(samples==1?AntiAliasingState.disabled():AntiAliasingState.multisampled(samples))
                .mediumTransport(budget).primitiveBatch(batch).build();
    }
    private static void setup(List<Volume> volumes,boolean local) throws Exception {
        var transaction=SceneTransaction.builder(++revision).resetScene()
                .upsert(densityTexture())
                .upsert(MaterialAsset.builder(1).shadingModel(MaterialAsset.ShadingModel.UNLIT).doubleSided(true)
                        .emissive(0xff4080ff,.25f).build())
                .upsert(MeshAsset.triangles(1,new float[]{-100,-100,(float)PLANE_Z,100,-100,(float)PLANE_Z,
                        100,100,(float)PLANE_Z,-100,100,(float)PLANE_Z},new int[]{0,1,2,2,3,0},1))
                .upsert(MediumTransportSelfTest.cube(2,1,1)).upsert(SceneInstance.builder(1,1).build());
        if(!local)for(var volume:volumes)transaction.upsert(SceneInstance.builder(volume.identity,2)
                .transform(volume.transform()).mediumBoundary(volume.boundary()).build());
        apply(transaction.build());
    }
    private static void apply(SceneTransaction transaction) throws Exception {
        long deadline=System.nanoTime()+TIMEOUT.toNanos();
        while(true)try {renderer.apply(transaction);return;}
        catch(SubmissionRejectedException pending) {require(System.nanoTime()<deadline,"boundary scene apply timeout");Thread.sleep(1);}
    }
    private static FrameSnapshot capture(String name,RenderFrameRequest request) throws Exception {
        long deadline=System.nanoTime()+TIMEOUT.toNanos();
        while(!(renderer.trySubmit(request) instanceof Renderer.FrameSubmitted)) {
            require(System.nanoTime()<deadline,"boundary admission timeout");
            if(benchmarkPolling)Thread.onSpinWait();else Thread.sleep(1);
        }
        if(benchmarkPolling&&benchmarkProfileCopy) {
            while(System.nanoTime()<deadline) {
                FrameSnapshot[] result={null};
                long readStarted=System.nanoTime();
                boolean consumed=renderer.readFrame(view->result[0]=copySnapshot(view));
                benchmarkReadFrameNanos+=System.nanoTime()-readStarted;
                if(consumed) {
                    FrameSnapshot frame=result[0];
                    require(frame!=null&&frame.frameSequence()==request.sequence(),"wrong profiled boundary frame");
                    require(frame.renderedSceneRevision()==revision&&frame.format()==TextureFormat.RGBA16_FLOAT,
                            "profiled final HDR provenance");
                    return frame;
                }
                Thread.onSpinWait();
            }
            throw new AssertionError("profiled boundary frame timeout");
        }
        while(System.nanoTime()<deadline) {
            var ready=renderer.pollFrame();
            if(ready.isPresent()&&ready.orElseThrow().frameSequence()==request.sequence()) {
                var frame=ready.orElseThrow();
                require(frame.renderedSceneRevision()==revision&&frame.format()==TextureFormat.RGBA16_FLOAT,"final HDR provenance");
                if(name!=null) {
                    byte[] bytes=new byte[frame.byteCount()];frame.pixels().get(bytes);Files.write(OUTPUT.resolve(name+".rgba16f"),bytes);
                }
                return frame;
            }
            if(benchmarkPolling)Thread.onSpinWait();else Thread.sleep(1);
        }
        throw new AssertionError("boundary public frame timeout");
    }
    private static void expectRejected(RenderFrameRequest frame,FrameValidationException.Reason reason,String name) throws Exception {
        long deadline=System.nanoTime()+TIMEOUT.toNanos();
        while(true) {
            try {
                if(renderer.trySubmit(frame) instanceof Renderer.FrameSubmitted)
                    throw new AssertionError("expected recoverable "+name);
            } catch(FrameValidationException expected) {
                require(expected.reason()==reason,"wrong rejection category for "+name+": "+expected.reason());
                require(renderer.status()==Renderer.Status.READY,"rejection poisoned session");rejected++;return;
            }
            require(System.nanoTime()<deadline,"recoverable rejection admission timeout: "+name);
            Thread.sleep(1);
        }
    }
    private static Medium medium(float r,float g,float b,float er,float eg,float eb) {
        return new Medium(new Rgb(r,g,b),Rgb.ZERO,new Rgb(er,eg,eb),1,1,0);
    }
    private static TextureAsset densityTexture() {
        byte[] pixels=new byte[2*4*4];
        for(int i=0;i<pixels.length;i+=4) {pixels[i]=(byte)128;pixels[i+1]=(byte)255;pixels[i+3]=(byte)255;}
        return TextureAsset.builder(50,2,4).colorSpace(TextureAsset.ColorSpace.LINEAR).pixelsRgba8(pixels).build();
    }
    private static void require(boolean value,String reason) {if(!value)throw new AssertionError(reason);}
    private record Volume(long identity,double minZ,double maxZ,Medium medium,int priority) {
        AffineTransform transform() {return new AffineTransform(new float[]{100,0,0,0,0,100,0,0,0,0,(float)((maxZ-minZ)/2),(float)((maxZ+minZ)/2)});}
        MediumBoundary boundary() {return MediumBoundary.volume(medium).withPriority(priority);}
    }
    private record Event(double distance,Volume volume,boolean entering) { }
}
