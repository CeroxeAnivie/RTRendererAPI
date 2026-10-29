package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import top.ceroxe.rt.renderer.api.*;

/** Public renderer final-HDR check for analytic camera emission; no private kernel replaces this route. */
public final class CudaCameraEmissionProductionNativeSelfTest {
    private static final int WIDTH=67, HEIGHT=49;
    private static final Duration TIMEOUT=Duration.ofSeconds(45);
    private static final double[][] EIGHT_SAMPLES={{.5,.3333},{.25,.6667},{.75,.1111},{.125,.4444},
            {.625,.7778},{.375,.2222},{.875,.5556},{.0625,.8889}};
    private static final Path OUTPUT=Path.of(System.getProperty("top.ceroxe.rt.medium.emissionProductionDirectory",
            "build/luxcore-native-emission-20261028/production"));
    private static final List<String> RESULTS=new ArrayList<>();
    private static Renderer renderer;
    private static long sequence, revision;
    private static int nativeFrames;

    public static void main(String[] arguments) throws Exception {
        String previous=System.getProperty(CudaCameraMediumProfile.ENABLE_PROPERTY);
        Files.createDirectories(OUTPUT);
        System.setProperty(CudaCameraMediumProfile.ENABLE_PROPERTY,"true");
        long routes=VulkanGpuSceneFrameSubmitter.diagnosticNativeCameraSubmissions.sum();
        try {
            try (Renderer owned=Renderer.open("vulkan-rt",RendererConfig.builder().maxFramesInFlight(3)
                    .validationEnabled(true).frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F)
                    .temporalRendering(TemporalRenderingOptions.disabled()).build())) {
                renderer=owned;
                renderer.apply(SceneTransaction.builder(++revision).resetScene()
                        .upsert(MaterialAsset.builder(1).doubleSided(true)
                                .shadingModel(MaterialAsset.ShadingModel.UNLIT).baseColorRgba8(0xffe0b080).build())
                        .upsert(MeshAsset.triangles(1,new float[]{-20,-20,-4,20,-20,-4,20,20,-4,-20,20,-4},
                                new int[]{0,1,2,2,3,0},1))
                        .upsert(SceneInstance.builder(1,1).build()).build());
                var pure=new Medium(Rgb.ZERO,Rgb.ZERO,new Rgb(.05f,.12f,.2f),1.7f,1,0);
                var mixed=new Medium(new Rgb(0,.13f,.04f),Rgb.ZERO,new Rgb(.3f,.17f,0),1.6f,1,.9f);
                var tiny=new Medium(new Rgb(1e-12f,0,1e-8f),Rgb.ZERO,new Rgb(.3f,1e-6f,.11f),.7f,1,0);
                comparePair("pure-emission-hit",pure,12,1,true,FramePrimitiveBatch.empty());
                comparePair("mixed-emission-hit",mixed,12,1,true,FramePrimitiveBatch.empty());
                comparePair("tiny-extinction-clamped-two-SPP",tiny,4,2,true,FramePrimitiveBatch.empty());
                comparePair("mixed-eight-SPP",mixed,12,8,true,FramePrimitiveBatch.empty());
                renderer.apply(SceneTransaction.builder(++revision).removeInstance(1).build());
                comparePair("pure-emission-miss",pure,7,1,false,FramePrimitiveBatch.empty());
                var local=FramePrimitiveBatch.builder().add(PrimitiveInstance.builder(1).build()).build();
                comparePair("frame-local-mixed-two-SPP",mixed,12,2,true,local);
                require(renderer.health().activeFailure().isEmpty(),"emission renderer has an active failure");
            }
            require(VulkanGpuSceneFrameSubmitter.diagnosticNativeCameraSubmissions.sum()-routes==nativeFrames,
                    "an emitted medium frame bypassed native production");
            Files.writeString(OUTPUT.resolve("result.json"),"{\n  \"passed\":true,\n  \"scope\":\"public-vulkan-final-HDR-homogeneous-analytic-emission\",\n"
                    +"  \"nativeFrames\":"+nativeFrames+",\n  \"comparisons\":[\n"+String.join(",\n",RESULTS)+"\n  ]\n}\n");
            System.out.println("CudaCameraEmissionProductionNativeSelfTest passed: public HDR analytic emission comparisons="+RESULTS.size());
        } finally {
            if(previous==null)System.clearProperty(CudaCameraMediumProfile.ENABLE_PROPERTY);
            else System.setProperty(CudaCameraMediumProfile.ENABLE_PROPERTY,previous);
        }
    }

    private static void comparePair(String name,Medium medium,float maxDistance,int samples,boolean hit,
                                    FramePrimitiveBatch primitives) throws Exception {
        var vacuum=capture(name+"-vacuum",request(Medium.vacuum(),maxDistance,samples,primitives));
        var candidate=capture(name+"-native",request(medium,maxDistance,samples,primitives));
        nativeFrames++;
        ByteBuffer a=vacuum.pixels().order(ByteOrder.LITTLE_ENDIAN),b=candidate.pixels().order(ByteOrder.LITTLE_ENDIAN);
        var sigma=medium.absorption().multiply(medium.density());
        var emitted=medium.emission().multiply(medium.density());
        double[] absorption={sigma.red(),sigma.green(),sigma.blue()},emission={emitted.red(),emitted.green(),emitted.blue()};
        double fy=Math.tan(Math.toRadians(35)*.5),fx=fy*WIDTH/HEIGHT,maximum=0;
        for(int y=0;y<HEIGHT;y++)for(int x=0;x<WIDTH;x++)for(int channel=0;channel<3;channel++) {
            int at=(y*WIDTH+x)*8+channel*2;
            double base=Float.float16ToFloat(a.getShort(at)),expected=0;
            for(int sample=0;sample<samples;sample++) {
                double sx=samples==1?.5:samples==2?(sample==0?.25:.75):EIGHT_SAMPLES[sample][0];
                double sy=samples==1?.5:samples==2?(sample==0?.25:.75):EIGHT_SAMPLES[sample][1];
                double dx=((x+sx)/WIDTH*2-1)*fx,dy=-((y+sy)/HEIGHT*2-1)*fy;
                double distance=hit?Math.min(10*Math.sqrt(1+dx*dx+dy*dy),maxDistance):maxDistance;
                double tr=Math.exp(-absorption[channel]*distance);
                double e=emission[channel]*(absorption[channel]==0?distance:-Math.expm1(-absorption[channel]*distance)/absorption[channel]);
                expected+=(base*tr+e)/samples;
            }
            float actual=Float.float16ToFloat(b.getShort(at));
            double error=Math.abs(actual-expected);maximum=Math.max(maximum,error);
            require(Float.isFinite(actual)&&error<=.0015*Math.max(1,Math.abs(expected)),name+" pixel="+x+","+y
                    +" channel="+channel+" actual="+actual+" expected="+expected+" error="+error);
        }
        RESULTS.add(String.format(Locale.ROOT,"    {\"name\":\"%s\",\"samples\":%d,\"hit\":%s,\"maximumAbsoluteError\":%.12g}",
                name,samples,hit,maximum));
        System.out.printf(Locale.ROOT,"Native emission public HDR comparison=%s SPP=%d hit=%s maxAbsolute=%.9g%n",name,samples,hit,maximum);
    }

    private static RenderFrameRequest request(Medium medium,float distance,int samples,FramePrimitiveBatch primitives) {
        var camera=CameraState.lookAt(0,0,6,0,0,-4).verticalFieldOfViewDegrees(35).aspectRatio((double)WIDTH/HEIGHT).build();
        return RenderFrameRequest.builder(++sequence,WIDTH,HEIGHT,camera).minimumSceneRevision(revision)
                .clock(FrameClock.of(0,0)).environment(EnvironmentState.builder().skyRadiance(.2f,.4f,.6f)
                        .cameraMedium(medium).build()).antiAliasing(new AntiAliasingState(samples))
                .mediumTransport(MediumTransport.balanced().withMaxDistance(distance)).primitiveBatch(primitives).build();
    }

    private static FrameSnapshot capture(String name,RenderFrameRequest request) throws Exception {
        long deadline=System.nanoTime()+TIMEOUT.toNanos();
        while(!(renderer.trySubmit(request) instanceof Renderer.FrameSubmitted)) {
            require(System.nanoTime()<deadline,"emission frame admission timeout");Thread.sleep(1);
        }
        deadline=System.nanoTime()+TIMEOUT.toNanos();
        while(System.nanoTime()<deadline) {
            var ready=renderer.pollFrame();
            if(ready.isPresent()&&ready.orElseThrow().frameSequence()==request.sequence()) {
                var frame=ready.orElseThrow();
                require(frame.renderedSceneRevision()==revision&&frame.format()==TextureFormat.RGBA16_FLOAT,"emission final HDR provenance");
                byte[] pixels=new byte[frame.byteCount()];frame.pixels().get(pixels);Files.write(OUTPUT.resolve(name+".rgba16f"),pixels);
                return frame;
            }
            Thread.sleep(1);
        }
        throw new AssertionError("emission final HDR completion timeout");
    }

    private static void require(boolean condition,String reason) {if(!condition)throw new AssertionError(reason);}
}
