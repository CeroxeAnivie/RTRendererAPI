package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.api.*;

/** Small same-source serial/queued HDR comparisons; not a performance benchmark. */
public final class PrimaryQueueNativeSelfTest {
    private static final String PROPERTY="top.ceroxe.rt.medium.primaryQueue";
    private static final List<String> CASES=List.of("opaque-clean","opaque-offscreen-boundary","absorption","emission",
            "absorption-spp8-tiles","absorption-resize-tiles","frame-local","nine-nested-boundaries",
            "masked-footprint-tiles","coverage-footprint-tiles","opaque-metallic");
    private static long sequence,revision;
    private static String selectedCase;
    private static final EnvironmentState ENV=EnvironmentState.builder().skyRadiance(.1f,.12f,.15f)
            .ambientIntensity(.2f).sunDirection(0,0,-1).sunIntensity(1).build();

    public static void main(String[] args) throws Exception {
        for(String argument:args) if(argument.startsWith("--case=")) {
            selectedCase=argument.substring("--case=".length());
            if(!CASES.contains(selectedCase)) throw new IllegalArgumentException("unknown case: "+selectedCase);
        }
        String previous=System.getProperty(PROPERTY);
        String previousDiagnostics=System.getProperty("top.ceroxe.rt.medium.queueDiagnostics");
        try {
            System.setProperty("top.ceroxe.rt.medium.queueDiagnostics","true");
            if(Arrays.asList(args).contains("--serial-only")) {
                var images=run(false);
                System.out.println("PrimaryQueue serialOnly=true selectedCase="+selectedCase+" images="+images.size());
                return;
            }
            long before=VulkanGpuSceneFrameSubmitter.diagnosticQueuedSubmissions.sum();
            // Recovery mode uses a previously completed same-build control after an interrupted
            // queue run. Its resource identities must be retained in the accompanying manifest.
            var serial=Arrays.asList(args).contains("--queued-only")?savedSerial():run(false);
            if(VulkanGpuSceneFrameSubmitter.diagnosticQueuedSubmissions.sum()!=before)
                throw new AssertionError("serial control unexpectedly submitted queued work");
            var queued=run(true);
            long expectedQueued=queued.keySet().stream().filter(PrimaryQueueNativeSelfTest::ordinarySurface).count();
            if(VulkanGpuSceneFrameSubmitter.diagnosticQueuedSubmissions.sum()-before!=expectedQueued)
                throw new AssertionError("ordinary surface cases did not all submit queued work: expected="+expectedQueued);
            for(var entry:serial.entrySet()) {
                byte[] candidate=queued.get(entry.getKey());
                if(!Arrays.equals(entry.getValue(),candidate)) {
                    int count=0;float maximum=0;
                    var a=java.nio.ByteBuffer.wrap(entry.getValue()).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    var b=java.nio.ByteBuffer.wrap(candidate).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    while(a.hasRemaining()) {float x=Float.float16ToFloat(a.getShort()),y=Float.float16ToFloat(b.getShort());
                        if(x!=y){count++;maximum=Math.max(maximum,Math.abs(x-y));}}
                    throw new AssertionError(entry.getKey()+" differs: channels="+count+", maxAbsolute="+maximum);
                }
                System.out.println("PrimaryQueue image="+entry.getKey()+" bytes="+candidate.length+" byteIdentical=true");
            }
            System.out.println("PrimaryQueueNativeSelfTest passed: "+serial.size()+" HDR pairs; ordinaryQueuedPairs="
                    +expectedQueued+" nativeRoutePairs="+(queued.size()-expectedQueued)
                    +"; exact per-case routes and serial/queue HDR parity; not timing evidence");
        } finally {
            if(previous==null)System.clearProperty(PROPERTY);else System.setProperty(PROPERTY,previous);
            if(previousDiagnostics==null)System.clearProperty("top.ceroxe.rt.medium.queueDiagnostics");
            else System.setProperty("top.ceroxe.rt.medium.queueDiagnostics",previousDiagnostics);
        }
    }

    private static Map<String,byte[]> savedSerial() throws Exception {
        String output=System.getProperty("top.ceroxe.rt.medium.queueImageDirectory");
        if(output==null)throw new IllegalArgumentException("--queued-only requires queueImageDirectory");
        var serial=new LinkedHashMap<String,byte[]>();
        for(String name:CASES)serial.put(name,Files.readAllBytes(Path.of(output,"serial",name+".rgba16f")));
        System.out.println("PrimaryQueue restoredSerialImages="+serial.size());
        return serial;
    }

    private static Map<String,byte[]> run(boolean queued) throws Exception {
        System.setProperty(PROPERTY,Boolean.toString(queued));sequence=revision=0;
        var capability=VulkanRtCapabilityProbe.capture();
        MediumTransportSelfTest.require(capability.hardwareRayTracingReady(),"hardware RT required");
        var config=RendererConfig.builder().maxFramesInFlight(4).validationEnabled(true)
                .frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F)
                .temporalRendering(TemporalRenderingOptions.disabled()).build();
        var result=new LinkedHashMap<String,byte[]>();
        try(var renderer=new VulkanRendererHost(config,VulkanGpuSceneRenderingSession.open(capability,config,RendererRtDiagnostics.noop()))) {
            for(String name:List.of("opaque-clean","opaque-offscreen-boundary","absorption","emission")) {
                apply(renderer,VulkanMediumTransportBenchmark.transaction(scene(name),++revision));
                capture(renderer,result,name,160,90,1,FramePrimitiveBatch.empty());
            }
            apply(renderer,VulkanMediumTransportBenchmark.transaction(scene("absorption"),++revision));
            capture(renderer,result,"absorption-spp8-tiles",160,90,8,FramePrimitiveBatch.empty());
            capture(renderer,result,"absorption-resize-tiles",320,240,1,FramePrimitiveBatch.empty());
            apply(renderer,SceneTransaction.builder(++revision).removeInstance(2).build());
            var medium=new Medium(new Rgb(.2f,.1f,.03f),Rgb.ZERO,Rgb.ZERO,1,1,0);
            var local=PrimitiveInstance.builder(2).transform(scale(2)).mediumBoundary(MediumBoundary.volume(medium)).build();
            capture(renderer,result,"frame-local",160,90,1,FramePrimitiveBatch.builder().add(local).build());
            var many=SceneTransaction.builder(++revision);
            for(int i=0;i<9;i++)many.upsert(SceneInstance.builder(2+i,2).transform(scale(2-i*.15f))
                    .mediumBoundary(new MediumBoundary(medium,i,MediumBoundary.Orientation.OUTWARD,false)).build());
            apply(renderer,many.build());
            capture(renderer,result,"nine-nested-boundaries",160,90,1,FramePrimitiveBatch.empty());
            if("nine-nested-boundaries".equals(selectedCase)) return result;
            apply(renderer,VulkanMediumTransportBenchmark.transaction(scene("opaque-offscreen-boundary"),++revision));
            byte[] pixels=new byte[16*16*4];
            for(int y=0;y<16;y++)for(int x=0;x<16;x++){int p=(y*16+x)*4;
                pixels[p]=(byte)255;pixels[p+1]=(byte)180;pixels[p+2]=(byte)100;pixels[p+3]=(byte)(((x+y)%2==0)?255:0);}
            var mask=MaterialAsset.builder(3).blendMode(MaterialAsset.BlendMode.MASKED).doubleSided(true)
                    .baseColorTextureId(3).roughness(.4f).build();
            apply(renderer,SceneTransaction.builder(++revision).upsert(TextureAsset.builder(3,16,16)
                    .colorSpace(TextureAsset.ColorSpace.LINEAR).pixelsRgba8(pixels).build()).upsert(mask)
                    .upsert(MeshAsset.builder(3,new float[]{-3,-3,0,3,-3,0,3,3,0,-3,3,0},
                            new int[]{0,1,2,2,3,0},new long[]{3,3})
                            .textureCoordinates(new float[]{0,0,16,0,16,16,0,16}).build())
                    .upsert(SceneInstance.builder(20,3).build()).build());
            capture(renderer,result,"masked-footprint-tiles",320,240,8,FramePrimitiveBatch.empty());
            apply(renderer,SceneTransaction.builder(++revision).upsert(mask.toBuilder()
                    .blendMode(MaterialAsset.BlendMode.TRANSLUCENT).baseColorRgba8(0x80ffffff).build()).build());
            capture(renderer,result,"coverage-footprint-tiles",320,240,8,FramePrimitiveBatch.empty());
            apply(renderer,VulkanMediumTransportBenchmark.transaction(scene("opaque-clean"),++revision));
            apply(renderer,SceneTransaction.builder(++revision).upsert(MaterialAsset.builder(1).metallic(1).roughness(.25f)
                    .doubleSided(true).build()).build());
            capture(renderer,result,"opaque-metallic",160,90,4,FramePrimitiveBatch.empty());
            if(renderer.health().activeFailure().isPresent())throw new AssertionError(renderer.health());
        }
        return result;
    }
    private static VulkanMediumTransportBenchmark.Scene scene(String name) {
        return VulkanMediumTransportBenchmark.SCENES.stream().filter(s->s.name().equals(name)).findFirst().orElseThrow();
    }
    private static void apply(VulkanRendererHost renderer,SceneTransaction transaction) throws InterruptedException {
        long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
        while(true) {
            try {renderer.apply(transaction);return;}
            catch(SubmissionRejectedException pending) {
                // Consecutive asynchronous scene generations may not have converged yet.
                // A capacity refusal publishes no state, so retry this exact revision.
                if((pending.deferralReason()!=SubmissionDeferralReason.PROVIDER_CAPACITY
                        && pending.deferralReason()!=SubmissionDeferralReason.SCENE_UPDATE_BACKLOG)
                        || System.nanoTime()>=deadline) throw pending;
                Thread.sleep(1);
            }
        }
    }
    private static AffineTransform scale(float radius) {
        return new AffineTransform(new float[]{radius,0,0,0,0,radius,0,0,0,0,radius,0});
    }
    private static void capture(VulkanRendererHost renderer,Map<String,byte[]> images,String name,int width,int height,
            int samples,FramePrimitiveBatch primitives) throws Exception {
        // Keep original frame/sample identity while omitting unrelated rendering in a narrow control.
        if(selectedCase!=null && !selectedCase.equals(name)) {sequence++;return;}
        var camera=CameraState.lookAt(0,0,6,0,0,-2).verticalFieldOfViewDegrees(35).aspectRatio((double)width/height).build();
        var request=RenderFrameRequest.builder(++sequence,width,height,camera).minimumSceneRevision(revision)
                .clock(FrameClock.of(0,0)).environment(ENV).antiAliasing(new AntiAliasingState(samples))
                .mediumTransport(MediumTransport.balanced().withMaxEvents(32)).primitiveBatch(primitives).build();
        var routesBefore=routes();
        long deadline=System.nanoTime()+Duration.ofMinutes(5).toNanos();
        while(!(renderer.trySubmit(request) instanceof Renderer.FrameSubmitted)) {
            if(System.nanoTime()>deadline)throw new AssertionError("admission timeout: "+name);Thread.sleep(1);
        }
        while(System.nanoTime()<deadline) {
            var ready=renderer.pollFrame();
            if(ready.isPresent()) {var buffer=ready.orElseThrow().pixels();byte[] pixels=new byte[buffer.remaining()];buffer.get(pixels);
                var actual=routes().subtract(routesBefore);
                var expected=ordinarySurface(name)?new Routes(Boolean.getBoolean(PROPERTY)?1:0,0,0):
                        new Routes(0,name.endsWith("footprint-tiles")?0:1,name.endsWith("footprint-tiles")?1:0);
                if(!actual.equals(expected))throw new AssertionError("wrong exact submission route for "+name
                        +" queued="+Boolean.getBoolean(PROPERTY)+": actual="+actual+" expected="+expected);
                images.put(name,pixels);
                String output=System.getProperty("top.ceroxe.rt.medium.queueImageDirectory");
                if(output!=null) {
                    Path directory=Path.of(output,Boolean.getBoolean(PROPERTY)?"queued":"serial");
                    Files.createDirectories(directory);
                    Files.write(directory.resolve(name+".rgba16f"),pixels);
                }
                System.out.println("PrimaryQueue captured="+name+" queued="+System.getProperty(PROPERTY));return;}
            Thread.sleep(1);
        }
        throw new AssertionError("readback timeout: "+name);
    }
    private static boolean ordinarySurface(String name) {
        return name.equals("opaque-clean")||name.equals("opaque-metallic");
    }
    private static Routes routes() {
        return new Routes(VulkanGpuSceneFrameSubmitter.diagnosticQueuedSubmissions.sum(),
                VulkanGpuSceneFrameSubmitter.diagnosticNativePbrSubmissions.sum(),
                VulkanGpuSceneFrameSubmitter.diagnosticNativeOpticalSubmissions.sum());
    }
    private record Routes(long queued,long pbr,long optical) {
        Routes subtract(Routes previous) {return new Routes(queued-previous.queued,pbr-previous.pbr,optical-previous.optical);}
    }
}
