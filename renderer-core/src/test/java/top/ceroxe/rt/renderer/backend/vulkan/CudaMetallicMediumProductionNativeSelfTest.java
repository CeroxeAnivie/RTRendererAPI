package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import top.ceroxe.rt.renderer.api.*;

/** Actual reflected HDR and both Beer segments; a direct-BRDF-only implementation is black here. */
public final class CudaMetallicMediumProductionNativeSelfTest {
    private static final Duration TIMEOUT=Duration.ofSeconds(60);
    private static final int WIDTH=23,HEIGHT=17;
    private static final Medium ABSORBER=new Medium(new Rgb(.08f,.13f,.2f),Rgb.ZERO,Rgb.ZERO,1,1,0);
    private static final MediumTransport BUDGET=MediumTransport.balanced().withMaxEvents(24).withMaxDistance(30)
            .withScatteringSteps(64).withLightSamples(0).withTransmittanceCutoff(0);
    private static final CameraState CAMERA=CameraState.lookAt(0,0,3,0,0,0)
            .verticalFieldOfViewDegrees(2).aspectRatio((double)WIDTH/HEIGHT).build();
    private static final List<String> RESULTS=new ArrayList<>();
    private static long sequence,revision;
    private static Renderer renderer;

    public static void main(String[] arguments) throws Exception {
        var configuration=RendererConfig.builder().validationEnabled(true).maxFramesInFlight(3)
                .frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F)
                .temporalRendering(TemporalRenderingOptions.disabled())
                .sceneShaders(SceneShaderProgram.builder().dataBindingCount(1).build()).build();
        long before=VulkanGpuSceneFrameSubmitter.diagnosticNativeOpticalSubmissions.sum();
        try(Renderer owned=Renderer.open("vulkan-rt",configuration)) {
            renderer=owned;
            for(boolean doubleSided:new boolean[]{false,true})for(float metal:new float[]{.2f,1})for(int spp:new int[]{1,2,8}) {
                setup(metal,doubleSided,null,false);
                compare("vacuum-metal"+metal+"-sides"+doubleSided+"-SPP"+spp,
                        capture(Medium.vacuum(),spp,BUDGET,FramePrimitiveBatch.empty()),metal,spp,Medium.vacuum(),0,false);
                compare("uniform-metal"+metal+"-sides"+doubleSided+"-SPP"+spp,
                        capture(ABSORBER,spp,BUDGET,FramePrimitiveBatch.empty()),metal,spp,ABSORBER,1,false);
            }
            var field=MediumDensityField.grid(50,2,new AffineTransform(new float[]{.005f,0,0,.5f,0,.005f,0,.5f,0,0,.125f,.25f}));
            setup(1,true,null,false);
            compare("density-both-reflection-segments",capture(ABSORBER.withDensityField(field),1,BUDGET,FramePrimitiveBatch.empty()),
                    1,1,ABSORBER,128.0/255,false);
            compare("zero-density-restores-reflection",capture(ABSORBER.withDensity(0),1,BUDGET,FramePrimitiveBatch.empty()),
                    1,1,Medium.vacuum(),0,false);
            var emitting=new Medium(ABSORBER.absorption(),Rgb.ZERO,new Rgb(.01f,.02f,.03f),1,1,0);
            compare("emission-before-and-after-reflection",capture(emitting,1,BUDGET,FramePrimitiveBatch.empty()),1,1,emitting,1,false);
            setup(1,true,ABSORBER,false);
            compare("explicit-boundary-two-crossings",capture(Medium.vacuum(),1,BUDGET,FramePrimitiveBatch.empty()),1,1,ABSORBER,1,true);
            setup(1,true,null,false);
            var local=FramePrimitiveBatch.builder().add(PrimitiveInstance.from(boundary(ABSORBER,false))).build();
            compare("frame-local-boundary-two-crossings",capture(Medium.vacuum(),1,BUDGET,local),1,1,ABSORBER,1,true);
            var objectField=MediumDensityField.grid(50,2,new AffineTransform(new float[]{.5f,0,0,.5f,0,.5f,0,.5f,0,0,.5f,.5f}));
            setup(1,true,ABSORBER.withDensityField(objectField),false);
            compare("boundary-density-two-crossings",capture(Medium.vacuum(),1,BUDGET,FramePrimitiveBatch.empty()),1,1,ABSORBER,128.0/255,true);
            setup(1,true,null,false);
            allBlack(capture(ABSORBER,1,BUDGET.withMaxEvents(1),FramePrimitiveBatch.empty()),"event budget must stop metal reflection");
            var scattering=new Medium(new Rgb(.01f,.02f,.03f),new Rgb(.04f,.03f,.02f),Rgb.ZERO,1,1,.2f);
            positive(capture(scattering,8,BUDGET.withScatteringBounces(2),FramePrimitiveBatch.empty()),"metal plus multiple scattering");
            positive(capture(scattering.withDensityField(field),8,BUDGET.withScatteringBounces(2),FramePrimitiveBatch.empty()),"metal plus density/multiple scattering");
            setup(.2f,false,new Medium(new Rgb(.01f,.02f,.03f),Rgb.ZERO,Rgb.ZERO,1,1.4f,0),true);
            positive(capture(ABSORBER,8,BUDGET,FramePrimitiveBatch.empty()),"metal receiver behind dielectric/medium interfaces");
            verifyCaustics(1,false);
            verifyCaustics(0,false);
            verifyCaustics(0,true);
            verifyCompositeCaustics();
            require(renderer.health().activeFailure().isEmpty(),"metal reflection left an active renderer failure");
        } finally {renderer=null;}
        long nativeFrames=VulkanGpuSceneFrameSubmitter.diagnosticNativeOpticalSubmissions.sum()-before;
        require(nativeFrames>=21,"metal medium frames bypassed optical continuation: "+nativeFrames);
        Path output=Path.of("build/metallic-medium/result.json");Files.createDirectories(output.getParent());
        Files.writeString(output,"{\n  \"passed\":true,\n  \"nativeFrames\":"+nativeFrames+",\n  \"comparisons\":[\n"
                +String.join(",\n",RESULTS)+"\n  ]\n}\n");
        System.out.println("CudaMetallicMediumProductionNativeSelfTest passed: comparisons="+RESULTS.size()+", nativeFrames="+nativeFrames);
    }

    private static void setup(float metal,boolean doubleSided,Medium boundary,boolean glass) throws Exception {
        var scene=SceneTransaction.builder(revision++).resetScene()
                .upsert(MaterialAsset.builder(1).doubleSided(doubleSided).metallic(metal).roughness(.025f).build())
                .upsert(MaterialAsset.builder(2).doubleSided(true).shadingModel(MaterialAsset.ShadingModel.UNLIT).build())
                .upsert(MaterialAsset.builder(3).doubleSided(true).transmission(glass?1:0).roughness(0).build())
                .upsert(plane(1,0,1)).upsert(plane(2,5,2)).upsert(MediumTransportSelfTest.cube(3,3,1))
                .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build());
        byte[] pixels=new byte[8];Arrays.fill(pixels,(byte)255);pixels[0]=(byte)128;pixels[4]=(byte)128;
        scene.upsert(TextureAsset.builder(50,1,2).colorSpace(TextureAsset.ColorSpace.LINEAR).pixelsRgba8(pixels).build());
        if(boundary!=null)scene.upsert(boundary(boundary,glass));
        var transaction=scene.build();long deadline=System.nanoTime()+TIMEOUT.toNanos();
        for(;;)try{renderer.apply(transaction);return;}catch(SubmissionRejectedException pending){require(System.nanoTime()<deadline,"metal scene apply timed out");Thread.sleep(1);}
    }
    private static SceneInstance boundary(Medium medium,boolean glass) {
        return SceneInstance.builder(3,3).transform(new AffineTransform(new float[]{100,0,0,0,0,100,0,0,0,0,1,1.5f}))
                .mediumBoundary(glass?MediumBoundary.surface(medium):MediumBoundary.volume(medium)).build();
    }
    private static MeshAsset plane(long id,float z,long material) {
        return MeshAsset.triangles(id,new float[]{-100,-100,z,100,-100,z,100,100,z,-100,100,z},new int[]{0,1,2,0,2,3},material);
    }
    private static FrameSnapshot capture(Medium medium,int spp,MediumTransport transport,FramePrimitiveBatch batch) throws Exception {
        return capture(medium,spp,transport,batch,CAMERA,false);
    }
    private static FrameSnapshot capture(Medium medium,int spp,MediumTransport transport,FramePrimitiveBatch batch,
            CameraState camera,boolean caustics) throws Exception {
        var data=new ResourceData(ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).putLong(sequence).putLong(~sequence).flip());
        var request=RenderFrameRequest.builder(sequence++,WIDTH,HEIGHT,camera)
                .minimumSceneRevision(revision-1).antiAliasing(spp==1?AntiAliasingState.disabled():AntiAliasingState.multisampled(spp))
                .environment(EnvironmentState.builder().cameraMedium(medium).sunDirection(0,0,-1)
                        .sunIntensity(camera==CAMERA?0:1).build()).mediumTransport(transport)
                .caustics(caustics?Caustics.photonMap(0,0,1,6,65536,.6f).withMaxEvents(8):Caustics.disabled())
                .shaderData(List.of(data)).primitiveBatch(batch).build();
        long deadline=System.nanoTime()+TIMEOUT.toNanos();
        while(!(renderer.trySubmit(request) instanceof Renderer.FrameSubmitted)){require(System.nanoTime()<deadline,"metal frame admission timed out");Thread.sleep(1);}
        var frame=renderer.awaitFrame(TIMEOUT).orElseThrow(()->new AssertionError("metal HDR timed out"));
        require(frame.frameSequence()==request.sequence(),"wrong metallic frame sequence");
        return frame;
    }
    private static void verifyCaustics(float floorMetal,boolean density) throws Exception {
        setup(floorMetal,true,ABSORBER,false);
        var transaction=SceneTransaction.builder(revision++)
                .upsert(MaterialAsset.builder(1).doubleSided(true).metallic(floorMetal).roughness(.025f).build())
                .upsert(MaterialAsset.builder(2).doubleSided(false).metallic(0).roughness(1).build())
                .upsert(MeshAsset.triangles(2,new float[]{-100,-100,2,100,-100,2,100,100,2,-100,100,2},
                        new int[]{0,2,1,0,3,2},2));
        if(density)transaction.removeInstance(3);
        else transaction.upsert(boundary(ABSORBER,false).toBuilder().transform(new AffineTransform(
                new float[]{4,0,0,0,0,4,0,0,0,0,1,1.5f})).build());
        long deadline=System.nanoTime()+TIMEOUT.toNanos();
        for(;;)try{renderer.apply(transaction.build());break;}catch(SubmissionRejectedException pending){require(System.nanoTime()<deadline,"caustic scene apply timed out");Thread.sleep(1);}
        var camera=CameraState.lookAt(0,0,1,0,0,2).verticalFieldOfViewDegrees(2).aspectRatio((double)WIDTH/HEIGHT).build();
        var medium=density?ABSORBER.withDensityField(MediumDensityField.grid(50,2,new AffineTransform(
                new float[]{.005f,0,0,.5f,0,.005f,0,.5f,0,0,.125f,.25f}))):Medium.vacuum();
        FrameSnapshot off=capture(medium,1,BUDGET,FramePrimitiveBatch.empty(),camera,false);
        allBlack(off,"ceiling control must receive no direct light");
        long photonsBefore=CausticPhotonPlan.diagnosticPhotonDispatches.sum();
        var on=capture(medium,1,BUDGET,FramePrimitiveBatch.empty(),camera,true);
        positive(on,"caustics-metal"+floorMetal+"-"+(density?"density":"boundary"));
        require(CausticPhotonPlan.diagnosticPhotonDispatches.sum()==photonsBefore+1,"caustics did not build a photon map");
    }
    private static void verifyCompositeCaustics() throws Exception {
        for(String kind:new String[]{"alpha","partial-transmission","overlay-alpha","overlay-multiply"})for(int domain=0;domain<3;domain++) {
            long nativeBefore=VulkanGpuSceneFrameSubmitter.diagnosticNativeOpticalSubmissions.sum();
            setup(1,true,domain==0?ABSORBER:null,false);
            var receiver=MaterialAsset.builder(2).doubleSided(false).roughness(1);
            if(kind.equals("alpha"))receiver.baseColorRgba8(0x80ffffff).blendMode(MaterialAsset.BlendMode.TRANSLUCENT);
            // Equal IOR removes reflection of the directly lit floor from the black control.
            if(kind.equals("partial-transmission"))receiver.transmission(.5f).indexOfRefraction(1);
            var transaction=SceneTransaction.builder(revision++).upsert(receiver.build())
                    .upsert(MeshAsset.triangles(2,new float[]{-100,-100,2,100,-100,2,100,100,2,-100,100,2},
                            new int[]{0,2,1,0,3,2},2))
                    .upsert(SceneInstance.builder(2,2).renderState(InstanceRenderState.builder().surfaceMask(1).build()).build());
            if(domain==0)transaction.upsert(boundary(ABSORBER,false).toBuilder().transform(new AffineTransform(
                    new float[]{4,0,0,0,0,4,0,0,0,0,1,1.5f})).build());
            if(kind.startsWith("overlay")) {
                var mode=kind.equals("overlay-alpha")?SurfaceOverlayState.CompositionMode.ALPHA_OVER:SurfaceOverlayState.CompositionMode.MULTIPLY;
                transaction.upsert(MaterialAsset.builder(4).doubleSided(true).shadingModel(MaterialAsset.ShadingModel.UNLIT)
                                .baseColorRgba8(0x80000000).blendMode(MaterialAsset.BlendMode.TRANSLUCENT)
                                .surfaceOverlay(SurfaceOverlayState.depthBias(.05f,mode)).build())
                        .upsert(MeshAsset.triangles(4,new float[]{-100,-100,1.99f,100,-100,1.99f,100,100,1.99f,-100,100,1.99f},
                                new int[]{0,2,1,0,3,2},4))
                        .upsert(SceneInstance.builder(4,4).renderState(InstanceRenderState.builder().overlayReceiverMask(1).build()).build());
            }
            var change=transaction.build();long deadline=System.nanoTime()+TIMEOUT.toNanos();
            for(;;)try{renderer.apply(change);break;}catch(SubmissionRejectedException pending){require(System.nanoTime()<deadline,"composite caustic apply timed out");Thread.sleep(1);}
            var medium=domain==0?Medium.vacuum():domain==1?ABSORBER.withDensityField(MediumDensityField.grid(50,2,new AffineTransform(
                    new float[]{.005f,0,0,.5f,0,.005f,0,.5f,0,0,.125f,.25f}))):ABSORBER;
            var camera=CameraState.lookAt(0,0,1,0,0,2).verticalFieldOfViewDegrees(2).aspectRatio((double)WIDTH/HEIGHT).build();
            String name="caustics-"+kind+"-"+new String[]{"boundary","density","uniform"}[domain];
            FrameSnapshot off=capture(medium,1,BUDGET,FramePrimitiveBatch.empty(),camera,false);
            long photonsBefore=CausticPhotonPlan.diagnosticPhotonDispatches.sum();
            positiveDelta(capture(medium,1,BUDGET,FramePrimitiveBatch.empty(),camera,true),off,name);
            require(CausticPhotonPlan.diagnosticPhotonDispatches.sum()==photonsBefore+1,name+" did not build a photon map");
            require(VulkanGpuSceneFrameSubmitter.diagnosticNativeOpticalSubmissions.sum()==nativeBefore+2,
                    name+" bypassed native optical transport");
        }
    }
    private static void positiveDelta(FrameSnapshot frame,FrameSnapshot control,String why) {
        var actual=frame.pixels().order(ByteOrder.LITTLE_ENDIAN);var baseline=control.pixels().order(ByteOrder.LITTLE_ENDIAN);
        double increase=0,maximum=0;
        for(int p=0;p<WIDTH*HEIGHT;p++)for(int c=0;c<3;c++) {
            double value=Float.float16ToFloat(actual.getShort(p*8+c*2));
            double before=Float.float16ToFloat(baseline.getShort(p*8+c*2));
            require(Double.isFinite(value)&&value>=0&&Double.isFinite(before)&&before>=0,why+" invalid final HDR");
            increase+=Math.max(value-before,0);maximum=Math.max(maximum,value-before);
        }
        require(increase>.0001&&maximum>0,why+" caustics added no final HDR radiance: "+increase);
        RESULTS.add(String.format(Locale.ROOT,"    {\"name\":\"%s\",\"positiveCausticHdrIncrease\":%.9g,\"maximumPixelIncrease\":%.9g}",why,increase,maximum));
    }
    private static void compare(String name,FrameSnapshot frame,float metal,int spp,Medium medium,double density,boolean boundary) {
        var pixels=frame.pixels().order(ByteOrder.LITTLE_ENDIAN);double maximum=0;
        for(int y=0;y<HEIGHT;y++)for(int x=0;x<WIDTH;x++) {
            double[] expected=new double[3];
            for(int sample=0;sample<spp;sample++) {
                double[] reflection=reflection(x,y,sample,spp,metal);
                double cameraMass=(boundary?2:3)/reflection[0]*density,reflectedMass=(boundary?2:5)/reflection[1]*density;
                for(int c=0;c<3;c++) {
                    double a=switch(c){case 0->medium.absorption().red();case 1->medium.absorption().green();default->medium.absorption().blue();};
                    double e=switch(c){case 0->medium.emission().red();case 1->medium.emission().green();default->medium.emission().blue();};
                    double t=Math.exp(-a*cameraMass),r=Math.exp(-a*reflectedMass);
                    expected[c]+=(integral(a,e,cameraMass)+t*reflection[2]*(integral(a,e,reflectedMass)+r))/spp;
                }
            }
            for(int c=0;c<3;c++) {
                double actual=Float.float16ToFloat(pixels.getShort((y*WIDTH+x)*8+c*2));
                require(Double.isFinite(actual)&&actual>=0,"nonfinite metal HDR");maximum=Math.max(maximum,Math.abs(actual-expected[c]));
                require(Math.abs(actual-expected[c])<=.0025,name+" numerical reflection/Beer mismatch: "+actual+" expected "+expected[c]);
            }
        }
        RESULTS.add(String.format(Locale.ROOT,"    {\"name\":\"%s\",\"maximumAbsoluteError\":%.9g}",name,maximum));
    }
    private static double integral(double absorption,double emission,double mass) {
        return absorption==0?emission*mass:emission*(-Math.expm1(-absorption*mass))/absorption;
    }
    private static double[] reflection(int x,int y,int sample,int spp,float metal) {
        double[][] offsets=spp==1?new double[][]{{.5,.5}}:spp==2?new double[][]{{.25,.25},{.75,.75}}:
                new double[][]{{.5,1.0/3},{.25,2.0/3},{.75,1.0/9},{.125,4.0/9},{.625,7.0/9},{.375,2.0/9},{.875,5.0/9},{.0625,8.0/9}};
        double dx=((x+offsets[sample][0])/WIDTH*2-1)*CAMERA.tanHalfFovX(),dy=-((y+offsets[sample][1])/HEIGHT*2-1)*CAMERA.tanHalfFovY();
        double length=Math.sqrt(1+dx*dx+dy*dy),vx=-dx/length,vy=-dy/length,vz=1/length;
        int hash=x*0x9e3779b9^y*0x85ebca6b^0xc2b2ae35;
        double rotation=Integer.toUnsignedLong(Integer.reverse(hash))*Math.scalb(1.0,-32);
        double u=(Integer.toUnsignedLong(Integer.reverse(sample))*Math.scalb(1.0,-32)+rotation)%1;
        double phi=2*Math.PI*(sample+.5)/spp,a2=.0025*.0025,hz=Math.sqrt((1-u)/(1+(a2-1)*u)),sine=Math.sqrt(1-hz*hz);
        double hx=Math.cos(phi)*sine,hy=Math.sin(phi)*sine,vh=vx*hx+vy*hy+vz*hz,outz=2*vh*hz-vz;
        require(outz>0,"metal reference sampled below surface");
        double k=1.025*1.025/8,geometry=vz/(vz*(1-k)+k)*outz/(outz*(1-k)+k),f0=.04*(1-metal)+metal;
        double weight=metal*(f0+(1-f0)*Math.pow(1-vh,5))*geometry*vh/(vz*hz);
        return new double[]{vz,outz,weight};
    }
    private static void positive(FrameSnapshot frame,String why) {
        var pixels=frame.pixels().order(ByteOrder.LITTLE_ENDIAN);double sum=0;
        for(int p=0;p<WIDTH*HEIGHT;p++)for(int c=0;c<3;c++){double v=Float.float16ToFloat(pixels.getShort(p*8+c*2));require(Double.isFinite(v)&&v>=0,why+" invalid HDR");sum+=v;}
        require(sum>0,why+" lost all reflected radiance");RESULTS.add(String.format(Locale.ROOT,
                "    {\"name\":\"%s\",\"positiveFinalHDR\":true,\"finalHdrSum\":%.9g}",why,sum));
    }
    private static void allBlack(FrameSnapshot frame,String why) {
        var pixels=frame.pixels();for(int p=0;p<WIDTH*HEIGHT;p++)for(int c=0;c<3;c++)require(pixels.getShort(p*8+c*2)==0,why);
        RESULTS.add("    {\"name\":\""+why+"\",\"blackFinalHDR\":true}");
    }
    private static void require(boolean value,String why) { if(!value)throw new AssertionError(why); }
}
