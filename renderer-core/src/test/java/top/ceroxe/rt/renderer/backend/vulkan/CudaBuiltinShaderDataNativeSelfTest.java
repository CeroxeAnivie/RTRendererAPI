package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import top.ceroxe.rt.renderer.api.*;

/** Application descriptor layouts must preserve built-in transport and final native HDR. */
public final class CudaBuiltinShaderDataNativeSelfTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final Medium ABSORBER = new Medium(new Rgb(.08f,.04f,.02f), Rgb.ZERO,
            new Rgb(.01f,.02f,.005f), 1, 1, 0);
    private static final Medium SCATTERER = new Medium(new Rgb(.02f,.01f,.03f),
            new Rgb(.06f,.03f,.04f), new Rgb(.005f,.003f,.002f), 1, 1, .2f);
    private static final MediumDensityField FIELD = MediumDensityField.grid(50, 2,
            new AffineTransform(new float[]{1f/6,0,0,.5f, 0,1f/6,0,.5f, 0,0,1f/6,.5f}));
    private record Example(String name, Medium camera, Medium boundary, boolean pbr, boolean glass, int bounces) { }
    private static final List<Example> EXAMPLES = List.of(
            new Example("vacuum", Medium.vacuum(), null, false, false, 0),
            new Example("camera-absorption-emission", ABSORBER, null, false, false, 0),
            new Example("density-absorption-emission", ABSORBER.withDensityField(FIELD), null, false, false, 0),
            new Example("PBR-camera", ABSORBER, null, true, false, 0),
            new Example("PBR-density", ABSORBER.withDensityField(FIELD), null, true, false, 0),
            new Example("PBR-boundary", Medium.vacuum(), ABSORBER, true, false, 0),
            new Example("scattering", SCATTERER, null, true, false, 0),
            new Example("density-scattering", SCATTERER.withDensityField(FIELD), null, true, false, 0),
            new Example("multiple-scattering", SCATTERER, null, true, false, 2),
            new Example("boundary-absorption", Medium.vacuum(), ABSORBER, false, false, 0),
            new Example("boundary-scattering", Medium.vacuum(), SCATTERER, false, false, 1),
            new Example("glass-density", ABSORBER.withDensityField(FIELD),
                    new Medium(new Rgb(.03f,.01f,.02f),Rgb.ZERO,Rgb.ZERO,1,1.4f,0), true, true, 0));

    public static void main(String[] arguments) throws Exception {
        long cameraBefore = VulkanGpuSceneFrameSubmitter.diagnosticNativeCameraSubmissions.sum();
        long pbrBefore = VulkanGpuSceneFrameSubmitter.diagnosticNativePbrSubmissions.sum();
        long opticalBefore = VulkanGpuSceneFrameSubmitter.diagnosticNativeOpticalSubmissions.sum();
        List<byte[]> control = render(false);
        List<byte[]> withData = render(true);
        for (int i=0;i<EXAMPLES.size();i++) {
            require(Arrays.equals(control.get(i),withData.get(i)),
                    "application data declaration changed final HDR: " + EXAMPLES.get(i).name());
        }
        require(VulkanGpuSceneFrameSubmitter.diagnosticNativeCameraSubmissions.sum()>cameraBefore,
                "application data matrix never executed the native camera route");
        require(VulkanGpuSceneFrameSubmitter.diagnosticNativePbrSubmissions.sum()>pbrBefore,
                "application data matrix never executed native PBR connections");
        require(VulkanGpuSceneFrameSubmitter.diagnosticNativeOpticalSubmissions.sum()>opticalBefore,
                "application data matrix never executed native optical continuation");
        Path output = Path.of("build/builtin-shader-data/result.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output,"{\n  \"passed\":true,\n  \"comparisons\":"+EXAMPLES.size()
                +",\n  \"nativeFrames\":"+(2*(EXAMPLES.size()-1))
                +",\n  \"scope\":\"declared readonly data layout; unchanged built-in final HDR; invalid binding recovery\"\n}\n");
        System.out.println("CudaBuiltinShaderDataNativeSelfTest passed: " + EXAMPLES.size()
                + " complete HDR comparisons, data generations, binding rejection recovery and native routes");
    }

    private static List<byte[]> render(boolean withData) throws Exception {
        var layout = SceneShaderProgram.builder().dataBindingCount(withData?1:0).build();
        var configuration = RendererConfig.builder().maxFramesInFlight(3).validationEnabled(true)
                .frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F)
                .temporalRendering(TemporalRenderingOptions.disabled()).sceneShaders(layout).build();
        var results = new ArrayList<byte[]>();
        FrameSnapshot retained = null;
        byte[] retainedBytes = null;
        try (Renderer renderer = Renderer.open("vulkan-rt",configuration)) {
            long revision=0,sequence=0;
            for (Example example : EXAMPLES) {
                var scene = SceneTransaction.builder(revision++).resetScene()
                        .upsert(MaterialAsset.builder(1).doubleSided(true).roughness(.6f)
                                .baseColorRgba8(0xff7090c0).shadingModel(example.pbr()
                                        ?MaterialAsset.ShadingModel.PHYSICALLY_BASED:MaterialAsset.ShadingModel.UNLIT).build())
                        .upsert(MeshAsset.triangles(1,new float[]{-12,-12,-2,12,-12,-2,0,12,-2},new int[]{0,1,2},1))
                        .upsert(SceneInstance.builder(1,1).build())
                        .upsert(densityTexture());
                if (example.boundary()!=null) {
                    scene.upsert(MaterialAsset.builder(2).doubleSided(true).transmission(example.glass()?1:0).roughness(0).build())
                            .upsert(MediumTransportSelfTest.cube(2,2,1))
                            .upsert(SceneInstance.builder(2,2).mediumBoundary(example.glass()
                                    ?MediumBoundary.surface(example.boundary()):MediumBoundary.volume(example.boundary())).build());
                }
                var transaction=scene.build();
                long applyDeadline=System.nanoTime()+TIMEOUT.toNanos(),acceptedRevision;
                for (;;) {
                    try { acceptedRevision=renderer.apply(transaction).acceptedSceneRevision();break; }
                    catch (SubmissionRejectedException pending) {
                        require(System.nanoTime()<applyDeadline,"scene apply timed out");Thread.sleep(1);
                    }
                }
                int width=sequence%2==0?19:23,height=sequence%2==0?13:11;
                var request = RenderFrameRequest.builder(sequence++,width,height,
                                CameraState.lookAt(0,0,3,0,0,0).aspectRatio((double)width/height).build())
                        .minimumSceneRevision(acceptedRevision)
                        .environment(EnvironmentState.builder().skyRadiance(.3f,.4f,.5f).ambientIntensity(.2f)
                                .sunDirection(0,0,-1).sunIntensity(1).cameraMedium(example.camera()).build())
                        .mediumTransport(MediumTransport.balanced().withMaxEvents(24).withMaxDistance(15)
                                .withScatteringBounces(example.bounces()).withTransmittanceCutoff(0))
                        .shaderData(withData?List.of(data((int)sequence)):List.of()).build();
                if (withData) {
                    reject(renderer,request.toBuilder().shaderData(List.of()).build());
                    reject(renderer,request.toBuilder().shaderData(List.of(data(1),data(2))).build());
                    reject(renderer,request.toBuilder().shaderData(List.of(new ResourceData(ByteBuffer.allocateDirect(3)))).build());
                }
                long deadline=System.nanoTime()+TIMEOUT.toNanos();
                while (!(renderer.trySubmit(request) instanceof Renderer.FrameSubmitted)) {
                    require(System.nanoTime()<deadline,"native frame admission timed out");Thread.sleep(1);
                }
                FrameSnapshot frame=renderer.awaitFrame(TIMEOUT).orElseThrow(()->new AssertionError("native HDR timed out"));
                require(frame.frameSequence()==request.sequence(),"wrong frame after invalid binding recovery");
                byte[] pixels=bytes(frame);
                require(pixels.length==width*height*8,"incomplete native HDR");
                require(nonzero(pixels),"empty final HDR: "+example.name());
                results.add(pixels);
                if (retained==null) { retained=frame;retainedBytes=pixels.clone(); }
            }
            require(renderer.health().activeFailure().isEmpty(),"binding rejections poisoned the renderer");
        }
        require(Arrays.equals(retainedBytes,bytes(retained)),"retained snapshot changed after slot reuse and close");
        return results;
    }

    private static ResourceData data(int generation) {
        return new ResourceData(ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder())
                .putInt(generation).putInt(~generation).putLong(0x76543210abcdefL).flip());
    }
    private static TextureAsset densityTexture() {
        byte[] pixels=new byte[32];Arrays.fill(pixels,(byte)255);
        return TextureAsset.builder(50,2,4).colorSpace(TextureAsset.ColorSpace.LINEAR).pixelsRgba8(pixels).build();
    }
    private static void reject(Renderer renderer,RenderFrameRequest request) {
        try { renderer.submit(request);throw new AssertionError("invalid application data was accepted"); }
        catch (FrameValidationException expected) {
            require(expected.reason()==FrameValidationException.Reason.INVALID_SHADER_DATA,"wrong binding rejection reason");
        }
    }
    private static byte[] bytes(FrameSnapshot frame) { var pixels=frame.pixels();byte[] result=new byte[pixels.remaining()];pixels.get(result);return result; }
    private static boolean nonzero(byte[] pixels) { for(int i=0;i<pixels.length;i+=8)for(int c=0;c<6;c++)if(pixels[i+c]!=0)return true;return false; }
    private static void require(boolean value,String message) { if(!value)throw new AssertionError(message); }
}
