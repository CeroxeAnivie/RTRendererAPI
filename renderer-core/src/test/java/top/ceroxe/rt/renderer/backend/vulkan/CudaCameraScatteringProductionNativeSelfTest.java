package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import top.ceroxe.rt.renderer.api.*;

/** Final public HDR against analytic directional transport and independent CPU quadrature. */
public final class CudaCameraScatteringProductionNativeSelfTest {
    private static int width=31,height=23;
    private static final Path OUTPUT=Path.of(System.getProperty("top.ceroxe.rt.medium.scatteringProductionDirectory",
            "build/luxcore-native-scattering-20261029/production"));
    private static final List<String> RESULTS=new ArrayList<>();
    private static final Duration TIMEOUT=Duration.ofSeconds(45);
    private static final double[][] EIGHT={{.5,.3333},{.25,.6667},{.75,.1111},{.125,.4444},
            {.625,.7778},{.375,.2222},{.875,.5556},{.0625,.8889}};
    private static Renderer renderer;
    private static long revision,sequence;
    private static int nativeFrames,rejections;

    public static void main(String[] arguments) throws Exception {
        Files.createDirectories(OUTPUT);
        String previous=System.getProperty(CudaCameraMediumProfile.ENABLE_PROPERTY);
        System.setProperty(CudaCameraMediumProfile.ENABLE_PROPERTY,"true");
        long routes=VulkanGpuSceneFrameSubmitter.diagnosticNativeScatteringSubmissions.sum();
        long opticalRoutes=VulkanGpuSceneFrameSubmitter.diagnosticNativeOpticalSubmissions.sum();
        long pbrRoutes=VulkanGpuSceneFrameSubmitter.diagnosticNativePbrSubmissions.sum();
        try {
            try(Renderer owned=Renderer.open("vulkan-rt",RendererConfig.builder().maxFramesInFlight(3)
                    .validationEnabled(true).frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F)
                    .temporalRendering(TemporalRenderingOptions.disabled()).build())) {
                renderer=owned;
                var ambient=environment(0,false);
                compare("analytic-ambient-mixed-RGB",medium(0),ambient,List.of(),false,1,1,12,false);
                var forward=SceneLight.directional(1,0,0,1).color(.5f,.3f,.8f).intensity(.7f).castsShadow(false).build();
                compare("analytic-HG-isotropic",medium(0),environment(0,true),List.of(forward),false,8,1,12,false);
                compare("analytic-HG-forward-eight-SPP",medium(.6f),environment(0,true),List.of(forward),false,64,8,12,false);
                compare("analytic-HG-backward-two-SPP",medium(-.6f),environment(0,true),List.of(forward),false,2,2,4,false);
                var directional=SceneLight.directional(1,-1,0,0).color(.8f,.5f,.2f).intensity(3).castsShadow(true).build();
                compare("directional-medium-shadow",medium(.3f),environment(0,true),List.of(directional),false,8,1,12,false);
                compare("opaque-wall-shadow",medium(.3f),environment(0,true),List.of(directional),true,8,2,12,false);
                var point=SceneLight.point(2,20,15,3).color(.2f,.8f,.6f).intensity(40).range(100).castsShadow(true).build();
                var spot=SceneLight.spot(3,-20,15,3,20,-15,-2).color(.9f,.2f,.4f).intensity(35).range(100)
                        .coneDegrees(20,50).castsShadow(true).build();
                compare("point-spot-directional-sun-enumerated",medium(.25f),environment(.8f,false),List.of(directional,point,spot),false,64,2,12,false);
                compare("point-spot-opaque-shadow",medium(-.25f),environment(.8f,false),List.of(directional,point,spot),true,64,1,12,false);
                compare("small-light-set-importance-enumerates",medium(.25f),environment(.8f,false),List.of(directional,point,spot),false,64,1,12,true);
                var fractionalPoint=SceneLight.point(2,20.123456789,15.987654321,3.0000000000000004)
                        .color(.2f,.8f,.6f).intensity(40).range(100).castsShadow(true).build();
                var fractionalSpot=SceneLight.spot(3,-20.123456789,15.321987654,3.567891234,20,-15,-2)
                        .color(.9f,.2f,.4f).intensity(35).range(100).coneDegrees(20,50).castsShadow(true).build();
                compare("fractional-double-point-spot-upload-replacement",medium(.25f),environment(.8f,false),
                        List.of(directional,fractionalPoint,fractionalSpot),true,64,1,12,true);
                // Nine collinear lights have equal spectral/geometry ratios. Their importance
                // estimates have an exact sum, independently of which reservoir entries win.
                var reservoirLights=new ArrayList<SceneLight>();
                for(int i=0;i<9;i++)reservoirLights.add(SceneLight.directional(i+10,0,0,1)
                        .color(.5f,.3f,.8f).intensity((i+1)*.04f).castsShadow(false).build());
                compareLightBudget("nine-light-one-sample-reservoir",medium(.3f),environment(0,true),reservoirLights,false,8,1,12,1);
                compareLightBudget("nine-light-eight-sample-reservoir",medium(-.3f),environment(0,true),reservoirLights,false,8,2,12,8);
                var dielectricScattering=new Medium(new Rgb(.12f,.08f,.04f),new Rgb(.08f,.12f,.16f),Rgb.ZERO,1,1.3f,0);
                compare("IOR-scattering-without-interface-uses-native-scattering-integrator",dielectricScattering,environment(0,true),
                        List.of(forward),false,8,1,12,false);
                width=17;height=13;
                compare("resize-and-rejection-recovery",medium(0),environment(0,true),List.of(forward),false,8,2,7,false);
                require(renderer.health().activeFailure().isEmpty(),"native scattering session unhealthy");
            }
            long scatteringRoutes=VulkanGpuSceneFrameSubmitter.diagnosticNativeScatteringSubmissions.sum()-routes;
            long dielectricRoutes=VulkanGpuSceneFrameSubmitter.diagnosticNativeOpticalSubmissions.sum()-opticalRoutes;
            long nativePbrRoutes=VulkanGpuSceneFrameSubmitter.diagnosticNativePbrSubmissions.sum()-pbrRoutes;
            require(scatteringRoutes==nativeFrames&&dielectricRoutes==0&&nativePbrRoutes==nativeFrames,
                    "a public scattering frame bypassed its native integrator: camera="+scatteringRoutes+", optical="
                            +dielectricRoutes+", PBR="+nativePbrRoutes+", expectedFrames="+nativeFrames);
            Files.writeString(OUTPUT.resolve("result.json"),"{\n  \"passed\":true,\n  \"scope\":\"public-final-HDR-uniform-direct-scattering\",\n"
                    +"  \"nativeFrames\":"+nativeFrames+",\n  \"nativeScatteringFrames\":"+scatteringRoutes
                    +",\n  \"nativeOpticalFrames\":"+dielectricRoutes+",\n  \"nativePbrFrames\":"+nativePbrRoutes
                    +",\n  \"recoverableRejections\":"+rejections+",\n  \"comparisons\":[\n"
                    +String.join(",\n",RESULTS)+"\n  ]\n}\n");
            System.out.println("CudaCameraScatteringProductionNativeSelfTest passed: independent public HDR comparisons="+RESULTS.size());
        }finally{if(previous==null)System.clearProperty(CudaCameraMediumProfile.ENABLE_PROPERTY);else System.setProperty(CudaCameraMediumProfile.ENABLE_PROPERTY,previous);}
    }

    private static Medium medium(float g){return new Medium(new Rgb(.12f,.08f,.04f),new Rgb(.08f,.12f,.16f),new Rgb(.01f,.005f,0),1.3f,1,g);}
    private static EnvironmentState environment(float sun,boolean darkAmbient){return EnvironmentState.builder()
            .skyRadiance(.12f,.2f,.31f).ambientIntensity(darkAmbient?0:.3f).sunDirection(-1,0,0)
            .sunRadiance(.7f,.4f,.2f).sunIntensity(sun).build();}
    private static void setup(List<SceneLight> lights,boolean wall){
        var transaction=SceneTransaction.builder(++revision).resetScene()
                .upsert(MaterialAsset.builder(1).doubleSided(true).shadingModel(MaterialAsset.ShadingModel.UNLIT).baseColorRgba8(0xff806040).build())
                .upsert(MeshAsset.triangles(1,new float[]{-20,-20,-4,20,-20,-4,20,20,-4,-20,20,-4},new int[]{0,1,2,2,3,0},1))
                .upsert(SceneInstance.builder(1,1).build());
        if(wall)transaction.upsert(MeshAsset.triangles(2,new float[]{2,-20,-20,2,20,-20,2,20,20,2,-20,20},new int[]{0,1,2,2,3,0},1))
                .upsert(SceneInstance.builder(2,2).build());
        for(var light:lights)transaction.upsert(light);
        renderer.apply(transaction.build());
    }
    private static void compare(String name,Medium medium,EnvironmentState environment,List<SceneLight> lights,
            boolean wall,int steps,int samples,float maximum,boolean sampled) throws Exception {
        compareLightBudget(name,medium,environment,lights,wall,steps,samples,maximum,sampled?4:0);
    }
    private static void compareLightBudget(String name,Medium medium,EnvironmentState environment,List<SceneLight> lights,
            boolean wall,int steps,int samples,float maximum,int lightSamples) throws Exception {
        setup(lights,wall);
        var vacuum=capture(name+"-vacuum",request(Medium.vacuum(),environment,steps,samples,maximum,lightSamples));
        var actual=capture(name+"-native",request(medium,environment,steps,samples,maximum,lightSamples));nativeFrames++;
        var a=vacuum.pixels().order(ByteOrder.LITTLE_ENDIAN);var b=actual.pixels().order(ByteOrder.LITTLE_ENDIAN);
        double maximumError=0,sourceEnergy=0;
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)for(int c=0;c<3;c++) {
            int at=(y*width+x)*8+c*2;double base=Float.float16ToFloat(a.getShort(at)),expected=0;
            for(int sample=0;sample<samples;sample++) {
                double[] d=direction(x,y,sample,samples);double distance=Math.min(10/-d[2],maximum);
                double sigma=component(medium.extinction(),c)*medium.density();
                double integrated=sigma==0?distance:-Math.expm1(-sigma*distance)/sigma;
                double source=component(medium.emission(),c)*medium.density()*integrated
                        +component(medium.scattering(),c)*medium.density()*component(new Rgb(environment.skyRed(),environment.skyGreen(),environment.skyBlue()),c)
                        *environment.ambientIntensity()*integrated;
                final int n=512;double sum=0;
                for(int i=0;i<=n;i++) {
                    double t=distance*i/n;double incoming=incoming(lights,environment,wall,d,t,c,medium.anisotropy(),sigma,maximum);
                    sum+=(i==0||i==n?1:i%2==0?2:4)*Math.exp(-sigma*t)*incoming;
                }
                source+=component(medium.scattering(),c)*medium.density()*sum*distance/(3*n);
                expected+=(base*Math.exp(-sigma*distance)+source)/samples;sourceEnergy+=source/samples;
            }
            double value=Float.float16ToFloat(b.getShort(at)),error=Math.abs(value-expected);maximumError=Math.max(maximumError,error);
            require(Double.isFinite(value)&&error<=.0015*Math.max(1,Math.abs(expected)),name+" pixel="+x+","+y+" channel="+c
                    +" actual="+value+" expected="+expected+" error="+error);
        }
        for(int i=0;i<width*height;i++)require(a.getShort(i*8+6)==b.getShort(i*8+6),"scattering alpha changed");
        RESULTS.add(String.format(Locale.ROOT,"    {\"name\":\"%s\",\"steps\":%d,\"samples\":%d,\"maximumAbsoluteError\":%.12g,\"referenceSourceEnergy\":%.12g}",name,steps,samples,maximumError,sourceEnergy));
        System.out.printf(Locale.ROOT,"Native scattering public HDR comparison=%s SPP=%d steps=%d maxAbsolute=%.9g%n",name,samples,steps,maximumError);
    }

    /** Independent double-precision radiative-transfer reference, using Simpson integration. */
    private static double incoming(List<SceneLight> lights,EnvironmentState env,boolean wall,double[] ray,double t,
            int channel,float g,double sigma,double maximum) {
        double[] position={ray[0]*t,ray[1]*t,6+ray[2]*t};double result=0;
        double[] sun={-env.sunDirectionX(),-env.sunDirectionY(),-env.sunDirectionZ()};
        if(env.sunIntensity()>0&&!blocked(wall,position,sun,Double.POSITIVE_INFINITY)) {
            double value=channel==0?env.sunRed():channel==1?env.sunGreen():env.sunBlue();
            result+=value*env.sunIntensity()*phase(dot(ray,sun),g)*Math.exp(-sigma*maximum);
        }
        for(var light:lights) {
            double[] direction;double distance=Double.POSITIVE_INFINITY,attenuation=1;
            if(light.type()==SceneLight.Type.DIRECTIONAL)direction=new double[]{-light.directionX(),-light.directionY(),-light.directionZ()};
            else {
                direction=new double[]{light.x()-position[0],light.y()-position[1],light.z()-position[2]};distance=Math.sqrt(dot(direction,direction));
                for(int k=0;k<3;k++)direction[k]/=distance;
                double fade=Math.max(1-distance/light.range(),0);attenuation=fade*fade/Math.max(distance*distance,1);
                if(light.type()==SceneLight.Type.SPOT) {
                    double cone=-(light.directionX()*direction[0]+light.directionY()*direction[1]+light.directionZ()*direction[2]);
                    double u=Math.max(0,Math.min(1,(cone-light.outerConeCosine())/(light.innerConeCosine()-light.outerConeCosine())));
                    attenuation*=u*u*(3-2*u);
                }
            }
            double visibility=1;
            if(light.castsShadow())visibility=blocked(wall,position,direction,distance)?0:Math.exp(-sigma*Math.min(distance,maximum));
            double value=channel==0?light.red():channel==1?light.green():light.blue();
            result+=value*light.intensity()*attenuation*visibility*phase(dot(ray,direction),g);
        }
        return result;
    }
    private static boolean blocked(boolean wall,double[] position,double[] direction,double distance) {
        if(wall&&direction[0]>1e-12){double at=(2-position[0])/direction[0];if(at>0&&at<distance)return true;}
        if(direction[2]<-1e-12){double at=(-4-position[2])/direction[2];if(at>1e-5&&at<distance-1e-5)return true;}
        return false;
    }
    private static double phase(double cosine,double g){double d=1+g*g-2*g*cosine;return (1-g*g)/(4*Math.PI*d*Math.sqrt(d));}
    private static double dot(double[] a,double[] b){return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];}
    private static double component(Rgb value,int c){return c==0?value.red():c==1?value.green():value.blue();}
    private static double[] direction(int x,int y,int sample,int samples){
        double sx=samples==1?.5:samples==2?(sample==0?.25:.75):EIGHT[sample][0];
        double sy=samples==1?.5:samples==2?(sample==0?.25:.75):EIGHT[sample][1];
        double fy=Math.tan(Math.toRadians(5)*.5),dx=((x+sx)/width*2-1)*fy*width/height,dy=-((y+sy)/height*2-1)*fy;
        double length=Math.sqrt(1+dx*dx+dy*dy);return new double[]{dx/length,dy/length,-1/length};
    }
    private static RenderFrameRequest request(Medium medium,EnvironmentState environment,int steps,int samples,float distance,int lightSamples){
        var camera=CameraState.lookAt(0,0,6,0,0,-4).verticalFieldOfViewDegrees(5).aspectRatio((double)width/height).build();
        return RenderFrameRequest.builder(++sequence,width,height,camera).minimumSceneRevision(revision).clock(FrameClock.of(0,0))
                .environment(environment.toBuilder().cameraMedium(medium).build()).antiAliasing(new AntiAliasingState(samples))
                .mediumTransport(MediumTransport.balanced().withScatteringSteps(steps).withLightSamples(lightSamples).withMaxDistance(distance)).build();
    }
    private static FrameSnapshot capture(String name,RenderFrameRequest request) throws Exception {
        long deadline=System.nanoTime()+TIMEOUT.toNanos();
        while(!(renderer.trySubmit(request) instanceof Renderer.FrameSubmitted)){require(System.nanoTime()<deadline,"scattering frame admission timeout");Thread.sleep(1);}
        deadline=System.nanoTime()+TIMEOUT.toNanos();
        while(System.nanoTime()<deadline){var ready=renderer.pollFrame();if(ready.isPresent()&&ready.orElseThrow().frameSequence()==request.sequence()) {
            var frame=ready.orElseThrow();require(frame.renderedSceneRevision()==revision&&frame.format()==TextureFormat.RGBA16_FLOAT,"public HDR provenance");
            byte[] pixels=new byte[frame.byteCount()];frame.pixels().get(pixels);Files.write(OUTPUT.resolve(name+".rgba16f"),pixels);return frame;
        }Thread.sleep(1);}throw new AssertionError("native scattering final frame timeout");
    }
    private static void expectRejected(RenderFrameRequest request) throws Exception {
        try{renderer.trySubmit(request);throw new AssertionError("expected unsupported combined dielectric scattering");}
        catch(FrameValidationException expected){require(expected.reason()==FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,"recoverable scattering rejection");rejections++;}
    }
    private static void require(boolean value,String reason){if(!value)throw new AssertionError(reason);}
}
