package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import top.ceroxe.rt.renderer.api.*;

/** Independent compositing/Beer references and actual weighted PBR light connections. */
public final class CudaCompositeMediumProductionNativeSelfTest {
    private static final int W=23,H=17,COLOR=0xffc04080;
    private static final double A=128.0/255;
    private static final double[] RGB={128.0/255,64.0/255,192.0/255};
    private static final Medium MEDIUM=new Medium(new Rgb(.08f,.13f,.2f),Rgb.ZERO,new Rgb(.01f,.02f,.03f),1,1,0);
    private static final MediumTransport BUDGET=MediumTransport.balanced().withMaxEvents(24).withMaxDistance(30)
            .withScatteringSteps(64).withLightSamples(0).withTransmittanceCutoff(0);
    private static final CameraState CAMERA=CameraState.lookAt(0,0,3,0,0,0).verticalFieldOfViewDegrees(2).aspectRatio((double)W/H).build();
    private static final List<String> RESULTS=new ArrayList<>();
    private static Renderer renderer;
    private static long revision,sequence;
    public static void main(String[] args)throws Exception {
        long before=VulkanGpuSceneFrameSubmitter.diagnosticNativeOpticalSubmissions.sum();
        try(Renderer owned=Renderer.open("vulkan-rt",RendererConfig.builder().validationEnabled(true)
                .maxFramesInFlight(3).frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F)
                .temporalRendering(TemporalRenderingOptions.disabled()).build())){
            renderer=owned;
            for(String source:new String[]{"material","vertex","texture","product"})for(int domain=0;domain<3;domain++){
                int materialAlpha=source.equals("material")||source.equals("product")?128:255;
                int vertexAlpha=source.equals("vertex")||source.equals("product")?128:255;
                int textureAlpha=source.equals("texture")||source.equals("product")?128:255;
                var material=unlit().baseColorRgba8((COLOR&0xffffff)|(materialAlpha<<24)).baseColorTextureId(10).build();
                var mesh=plane(1,2,1).toBuilder().vertexColorsRgba8(new int[]{(vertexAlpha<<24)|0xffffff,
                        (vertexAlpha<<24)|0xffffff,(vertexAlpha<<24)|0xffffff,(vertexAlpha<<24)|0xffffff}).build();
                setup(material,mesh,SceneInstance.builder(1,1).build(),textureAlpha,domain,false);
                double alpha=(materialAlpha/255.0)*(vertexAlpha/255.0)*(textureAlpha/255.0);
                var medium=domain==0?MEDIUM:domain==1?Medium.vacuum():MEDIUM.withDensityField(MediumDensityField.grid(50,2,
                        new AffineTransform(new float[]{.005f,0,0,.5f,0,.005f,0,.5f,0,0,.125f,.25f})));
                compare(source+"-domain"+domain,capture(medium,false,LightmapState.fullIntensity(),FramePrimitiveBatch.empty()),
                        2,alpha,RGB,domain==0?1:A,0);
            }
            var material=unlit().baseColorRgba8((COLOR&0xffffff)|0x80000000).build();
            setup(material,plane(1,2,1),SceneInstance.builder(1,1).build(),255,0,true);
            compare("frame-local-alpha",capture(MEDIUM,false,LightmapState.fullIntensity(),FramePrimitiveBatch.builder()
                    .add(PrimitiveInstance.builder(1).build()).build()),2,A,RGB,1,0);
            for(float visibility:new float[]{1,.5f}){
                var lightmap=unlit().baseColorRgba8(COLOR).shadingModel(MaterialAsset.ShadingModel.LIGHTMAP_MODULATED).build();
                setup(lightmap,plane(1,2,1),SceneInstance.builder(1,1).surfaceVisibility(visibility).build(),255,0,false);
                int[] table=new int[256];Arrays.fill(table,0xff808080);
                compare("lightmap-visibility"+visibility,capture(MEDIUM,false,new LightmapState(1,table),FramePrimitiveBatch.empty()),
                        2,visibility,new double[]{RGB[0]*A*visibility,RGB[1]*A*visibility,RGB[2]*A*visibility},1,0);
            }
            for(var mode:SurfaceOverlayState.CompositionMode.values())for(boolean matched:new boolean[]{false,true}){
                var overlay=unlit().baseColorRgba8((COLOR&0xffffff)|0x80000000)
                        .blendMode(MaterialAsset.BlendMode.TRANSLUCENT)
                        .surfaceOverlay(SurfaceOverlayState.depthBias(.05f,mode)).build();
                setup(overlay,plane(1,.02f,1),SceneInstance.builder(1,1).renderState(InstanceRenderState.builder()
                        .overlayReceiverMask(matched?1:2).build()).build(),255,0,false);
                compare("overlay-"+mode+"-matched"+matched,capture(MEDIUM,false,LightmapState.fullIntensity(),FramePrimitiveBatch.empty()),
                        .02,matched?A:0,RGB,1,matched&&mode==SurfaceOverlayState.CompositionMode.MULTIPLY?1:0);
            }
            var overlay=unlit().baseColorRgba8((COLOR&0xffffff)|0x80000000).blendMode(MaterialAsset.BlendMode.TRANSLUCENT)
                    .surfaceOverlay(SurfaceOverlayState.depthEqual(.001f)).build();
            setup(overlay,plane(1,2,1),SceneInstance.builder(1,1).renderState(InstanceRenderState.builder().overlayReceiverMask(1).build()).build(),255,0,false);
            compare("overlay-depth-rejection",capture(MEDIUM,false,LightmapState.fullIntensity(),FramePrimitiveBatch.empty()),2,0,RGB,1,0);
            verifyPbrWeights();
            verifyPartialRefraction();
            require(renderer.health().activeFailure().isEmpty(),"compositing left an active failure");
        }finally{renderer=null;}
        long nativeFrames=VulkanGpuSceneFrameSubmitter.diagnosticNativeOpticalSubmissions.sum()-before;
        require(nativeFrames>=25,"composite medium frames bypassed native optical transport: "+nativeFrames);
        Path path=Path.of("build/composite-medium/result.json");Files.createDirectories(path.getParent());
        Files.writeString(path,"{\n  \"passed\":true,\n  \"nativeFrames\":"+nativeFrames+",\n  \"checks\":[\n"
                +String.join(",\n",RESULTS)+"\n  ]\n}\n");
        System.out.println("CudaCompositeMediumProductionNativeSelfTest passed: checks="+RESULTS.size()+", nativeFrames="+nativeFrames);
    }
    private static MaterialAsset.Builder unlit(){return MaterialAsset.builder(1).doubleSided(true).shadingModel(MaterialAsset.ShadingModel.UNLIT);}
    private static MeshAsset plane(long id,float z,long material){return MeshAsset.triangles(id,
            new float[]{-100,-100,z,100,-100,z,100,100,z,-100,100,z},new int[]{0,1,2,0,2,3},material);}
    private static void setup(MaterialAsset front,MeshAsset mesh,SceneInstance instance,int textureAlpha,int domain,boolean local)throws Exception{
        var field=MediumDensityField.grid(50,2,new AffineTransform(new float[]{.5f,0,0,.5f,0,.5f,0,.5f,0,0,.2f,.5f}));
        var transaction=SceneTransaction.builder(revision++).resetScene().upsert(front).upsert(mesh)
                .upsert(MaterialAsset.builder(2).doubleSided(true).shadingModel(MaterialAsset.ShadingModel.UNLIT).build())
                .upsert(plane(2,0,2)).upsert(SceneInstance.builder(2,2).renderState(InstanceRenderState.builder().surfaceMask(1).build()).build())
                .upsert(TextureAsset.builder(10,1,1).colorSpace(TextureAsset.ColorSpace.LINEAR)
                        .pixelsRgba8(new byte[]{-1,-1,-1,(byte)textureAlpha}).build());
        byte[] density=new byte[8];Arrays.fill(density,(byte)255);density[0]=density[4]=(byte)128;
        transaction.upsert(TextureAsset.builder(50,1,2).colorSpace(TextureAsset.ColorSpace.LINEAR).pixelsRgba8(density).build());
        if(!local)transaction.upsert(instance);
        if(domain==1)transaction.upsert(MediumTransportSelfTest.cube(3,2,1)).upsert(SceneInstance.builder(3,3)
                .transform(new AffineTransform(new float[]{4,0,0,0,0,4,0,0,0,0,2.5f,1.5f}))
                .mediumBoundary(MediumBoundary.volume(MEDIUM.withDensityField(field))).build());
        // A disjoint dielectric keeps every weight-control frame on the same executor.
        transaction.upsert(MaterialAsset.builder(4).doubleSided(true).transmission(1).roughness(0).build())
                .upsert(MediumTransportSelfTest.cube(4,4,1)).upsert(SceneInstance.builder(4,4)
                        .transform(new AffineTransform(new float[]{1,0,0,20,0,1,0,0,0,0,1,0})).build());
        apply(transaction.build());
    }
    private static void apply(SceneTransaction transaction)throws Exception{
        long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();
        for(;;)try{renderer.apply(transaction);return;}catch(SubmissionRejectedException busy){require(System.nanoTime()<deadline,"scene backpressure timed out");Thread.sleep(1);}
    }
    private static FrameSnapshot capture(Medium medium,boolean lit,LightmapState lightmap,FramePrimitiveBatch batch)throws Exception{
        var request=RenderFrameRequest.builder(sequence++,W,H,CAMERA).minimumSceneRevision(revision-1)
                .environment(EnvironmentState.builder().cameraMedium(medium).sunDirection(0,0,-1).sunIntensity(lit?1:0).build())
                .mediumTransport(BUDGET).lightmap(lightmap).primitiveBatch(batch).build();
        long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();
        while(!(renderer.trySubmit(request) instanceof Renderer.FrameSubmitted)){require(System.nanoTime()<deadline,"frame backpressure timed out");Thread.sleep(1);}
        var frame=renderer.awaitFrame(Duration.ofSeconds(60)).orElseThrow();require(frame.frameSequence()==request.sequence(),"wrong frame sequence");return frame;
    }
    private static void compare(String name,FrameSnapshot frame,double frontZ,double alpha,double[] color,double density,int mode){
        var pixels=frame.pixels().order(ByteOrder.LITTLE_ENDIAN);double maximum=0;
        for(int y=0;y<H;y++)for(int x=0;x<W;x++){
            double dx=((x+.5)/W*2-1)*CAMERA.tanHalfFovX(),dy=((y+.5)/H*2-1)*CAMERA.tanHalfFovY();
            double inverseCos=Math.sqrt(1+dx*dx+dy*dy),first=(3-frontZ)*inverseCos*density,last=frontZ*inverseCos*density;
            for(int c=0;c<3;c++){
                double a=switch(c){case 0->MEDIUM.absorption().red();case 1->MEDIUM.absorption().green();default->MEDIUM.absorption().blue();};
                double e=switch(c){case 0->MEDIUM.emission().red();case 1->MEDIUM.emission().green();default->MEDIUM.emission().blue();};
                double behind=integral(a,e,last)+Math.exp(-a*last);
                double atSurface=mode==1?((1-alpha)+color[c]*alpha)*behind:color[c]*alpha+(1-alpha)*behind;
                double expected=integral(a,e,first)+Math.exp(-a*first)*atSurface;
                double actual=Float.float16ToFloat(pixels.getShort((y*W+x)*8+c*2));
                require(Double.isFinite(actual)&&actual>=0,name+" invalid HDR");maximum=Math.max(maximum,Math.abs(actual-expected));
                require(Math.abs(actual-expected)<=.0015,name+" actual="+actual+" expected="+expected+" pixel="+x+","+y+" channel="+c);
            }
        }
        RESULTS.add(String.format(Locale.ROOT,"    {\"name\":\"%s\",\"maximumAbsoluteError\":%.9g}",name,maximum));
    }
    private static double integral(double a,double e,double mass){return e*(-Math.expm1(-a*mass))/a;}
    private static void verifyPbrWeights()throws Exception{
        for(int domain=0;domain<2;domain++){
            FrameSnapshot[] alpha=new FrameSnapshot[3],transmission=new FrameSnapshot[3];
            for(int i=0;i<3;i++){
                int coverage=new int[]{0,128,255}[i];
                var material=MaterialAsset.builder(1).doubleSided(true).roughness(1).baseColorRgba8((coverage<<24)|0xffffff).build();
                setup(material,plane(1,2,1),SceneInstance.builder(1,1).build(),255,domain,false);
                alpha[i]=capture(domain==0?MEDIUM:Medium.vacuum(),true,LightmapState.fullIntensity(),FramePrimitiveBatch.empty());
                material=material.toBuilder().blendMode(MaterialAsset.BlendMode.TRANSLUCENT)
                        .baseColorRgba8(-1).transmission(i*.5f).indexOfRefraction(1).build();
                apply(SceneTransaction.builder(revision++).upsert(material).build());
                transmission[i]=capture(domain==0?MEDIUM:Medium.vacuum(),true,LightmapState.fullIntensity(),FramePrimitiveBatch.empty());
            }
            mix("PBR-alpha-NEE-domain"+domain,alpha[0],alpha[1],alpha[2],A);
            mix("PBR-partial-transmission-NEE-domain"+domain,transmission[0],transmission[1],transmission[2],.5);
            apply(SceneTransaction.builder(revision++).upsert(MaterialAsset.builder(1).doubleSided(false).roughness(1)
                    .transmission(.5f).indexOfRefraction(1).build()).build());
            var single=capture(domain==0?MEDIUM:Medium.vacuum(),true,LightmapState.fullIntensity(),FramePrimitiveBatch.empty());
            mix("PBR-partial-transmission-single-sided-domain"+domain,transmission[0],single,transmission[2],.5);
        }
    }
    private static void mix(String name,FrameSnapshot zero,FrameSnapshot middle,FrameSnapshot one,double weight){
        var a=zero.pixels().order(ByteOrder.LITTLE_ENDIAN);var b=middle.pixels().order(ByteOrder.LITTLE_ENDIAN);var c=one.pixels().order(ByteOrder.LITTLE_ENDIAN);double maximum=0,difference=0;
        for(int p=0;p<W*H;p++)for(int k=0;k<3;k++){
            double low=Float.float16ToFloat(a.getShort(p*8+k*2)),high=Float.float16ToFloat(c.getShort(p*8+k*2));
            double actual=Float.float16ToFloat(b.getShort(p*8+k*2)),expected=low*(1-weight)+high*weight;
            maximum=Math.max(maximum,Math.abs(actual-expected));difference+=Math.abs(low-high);
            require(Double.isFinite(actual)&&Math.abs(actual-expected)<=.0015,name+" failed weighted final HDR: "+actual+" expected "+expected);
        }
        require(difference>.1,name+" controls have no observable contribution");
        RESULTS.add(String.format(Locale.ROOT,"    {\"name\":\"%s\",\"maximumAbsoluteError\":%.9g}",name,maximum));
    }
    private static void verifyPartialRefraction()throws Exception{
        var medium=new Medium(MEDIUM.absorption(),Rgb.ZERO,Rgb.ZERO,1,1,0);
        for(float transmission:new float[]{.2f,.5f,.8f}){
            setup(MaterialAsset.builder(1).doubleSided(true).roughness(0).transmission(transmission).indexOfRefraction(1.5f)
                    .emissive(COLOR,1).build(),plane(1,2,1),SceneInstance.builder(1,1).build(),255,0,false);
            var pixels=capture(medium,false,LightmapState.fullIntensity(),FramePrimitiveBatch.empty()).pixels().order(ByteOrder.LITTLE_ENDIAN);
            double maximum=0;
            for(int y=0;y<H;y++)for(int x=0;x<W;x++){
                double dx=((x+.5)/W*2-1)*CAMERA.tanHalfFovX(),dy=((y+.5)/H*2-1)*CAMERA.tanHalfFovY();
                double ci=1/Math.sqrt(1+dx*dx+dy*dy),ct=Math.sqrt(1-(1-ci*ci)/2.25);
                double rs=(ci-1.5*ct)/(ci+1.5*ct),rp=(1.5*ci-ct)/(1.5*ci+ct),f=(rs*rs+rp*rp)*.5;
                for(int c=0;c<3;c++){
                    double a=switch(c){case 0->medium.absorption().red();case 1->medium.absorption().green();default->medium.absorption().blue();};
                    double expected=Math.exp(-a/ci)*(RGB[c]*(1-transmission)+transmission*(1-f)/2.25*Math.exp(-a*2/ct));
                    double actual=Float.float16ToFloat(pixels.getShort((y*W+x)*8+c*2));maximum=Math.max(maximum,Math.abs(actual-expected));
                    require(Double.isFinite(actual)&&Math.abs(actual-expected)<=.0015,"partial PBR Snell/Fresnel/Beer failed: "+actual+" expected "+expected);
                }
            }
            RESULTS.add(String.format(Locale.ROOT,"    {\"name\":\"partial-refraction-%.1f\",\"maximumAbsoluteError\":%.9g}",transmission,maximum));
        }
    }
    private static void require(boolean value,String why){if(!value)throw new AssertionError(why);}
}
