package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import top.ceroxe.rt.renderer.api.*;

/** Public final HDR against independent clipped-density direct-transport calculations. */
public final class CudaDensityScatteringProductionNativeSelfTest {
    private static final Path OUTPUT=Path.of(System.getProperty(
            "top.ceroxe.rt.medium.densityScatteringProductionDirectory","build/luxcore-native-density-scattering/production"));
    private static final Duration TIMEOUT=Duration.ofSeconds(45);
    private static final List<String> RESULTS=new ArrayList<>();
    private static final int NX=3,NY=2,NZ=4;
    private static final double[][] EIGHT={{.5,.3333},{.25,.6667},{.75,.1111},{.125,.4444},
            {.625,.7778},{.375,.2222},{.875,.5556},{.0625,.8889}};
    private static Renderer renderer;
    private static int width=23,height=17,nativeFrames;
    private static long sequence,revision;

    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUTPUT);
        String previous=System.getProperty(CudaCameraMediumProfile.ENABLE_PROPERTY);
        System.setProperty(CudaCameraMediumProfile.ENABLE_PROPERTY,"true");
        long before=VulkanGpuSceneFrameSubmitter.diagnosticNativeDensityScatteringSubmissions.sum();
        var map=new AffineTransform(new float[]{.125f,0,.025f,.5f, 0,.16666667f,0,.5f, 0,0,.2f,.5f});
        Medium medium=new Medium(new Rgb(.12f,.08f,.04f),new Rgb(.08f,.12f,.16f),new Rgb(.01f,.005f,0),1.3f,1,.3f)
                .withDensityField(MediumDensityField.grid(50,NZ,map));
        try {
            try(Renderer owned=Renderer.open("vulkan-rt",RendererConfig.builder().maxFramesInFlight(3)
                    .validationEnabled(true).frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F)
                    .temporalRendering(TemporalRenderingOptions.disabled()).build())) {
                renderer=owned;
                compare("density-ambient-emission-one-step",medium,List.of(),false,false,1,1,0,false);
                var unshadowed=SceneLight.directional(1,0,0,1).color(.5f,.3f,.8f).intensity(.7f).castsShadow(false).build();
                compare("density-unshadowed-HG-eight-SPP",medium,List.of(unshadowed),false,false,13,8,0,true);
                var directional=SceneLight.directional(1,-1,0,0).color(.8f,.5f,.2f).intensity(3).castsShadow(true).build();
                compare("density-directional-shadow-two-SPP",medium,List.of(directional),false,false,17,2,0,true);
                compare("density-frame-local-opaque-shadow",medium,List.of(directional),true,true,19,2,0,true);
                var point=SceneLight.point(2,7,5,1).color(.2f,.8f,.6f).intensity(8).range(40).castsShadow(true).build();
                var spot=SceneLight.spot(3,-7,5,1,7,-5,-2).color(.9f,.2f,.4f).intensity(7).range(40)
                        .coneDegrees(20,50).castsShadow(true).build();
                compare("density-point-spot-directional-sun-enumerated",medium,List.of(directional,point,spot),false,false,23,2,0,false);
                compare("density-small-light-set-sampled-enumerates",medium,List.of(directional,point,spot),false,false,23,2,4,false);
                var many=new ArrayList<SceneLight>();
                for(int i=0;i<29;i++)many.add(SceneLight.directional(10+i,0,0,1).color(.5f,.3f,.8f)
                        .intensity((i+1)*.003f).castsShadow(false).build());
                compare("density-all-29-lights-through-bounded-chunks",medium,many,false,false,7,1,0,true);
                // Identical spectra/directions make the importance estimator equal the full sum
                // for every reservoir result, without reproducing production selection code.
                compare("density-29-light-one-sample-reservoir",medium,many,false,false,7,2,1,true);
                compare("density-29-light-eight-sample-reservoir",medium,many,false,false,7,8,8,true);
                var narrow=medium.withDensityField(MediumDensityField.grid(50,NZ,
                        new AffineTransform(new float[]{.2f,0,0,.5f,0,.2f,0,.5f,0,0,20,.5f})));
                compare("density-narrow-domain-one-step-eight-SPP",narrow,List.of(directional),false,false,1,8,0,true);
                var outside=medium.withDensityField(MediumDensityField.grid(50,NZ,
                        new AffineTransform(new float[]{.125f,0,0,-20,0,.16666667f,0,.5f,0,0,.2f,.5f})));
                compare("density-domain-miss-is-vacuum",outside,List.of(directional),false,false,13,2,0,true);
                width=31;height=19;
                compare("density-resize-and-cache-reuse",medium,List.of(directional),false,false,11,8,0,true);
                require(renderer.health().activeFailure().isEmpty(),"density scattering renderer unhealthy");
            }
            require(VulkanGpuSceneFrameSubmitter.diagnosticNativeDensityScatteringSubmissions.sum()-before==nativeFrames,
                    "a public density-scattering frame bypassed its actual native route");
            Files.writeString(OUTPUT.resolve("result.json"),"{\n  \"passed\":true,\n  \"scope\":\"public-final-HDR-heterogeneous-direct-scattering\",\n"
                    +"  \"reference\":\"independent double trilinear, clipped midpoint, seeded interval sampling and per-light density shadows\",\n"
                    +"  \"nativeFrames\":"+nativeFrames+",\n  \"comparisons\":[\n"
                    +String.join(",\n",RESULTS)+"\n  ]\n}\n");
            System.out.println("CudaDensityScatteringProductionNativeSelfTest passed: comparisons="+RESULTS.size());
        } finally {if(previous==null)System.clearProperty(CudaCameraMediumProfile.ENABLE_PROPERTY);
            else System.setProperty(CudaCameraMediumProfile.ENABLE_PROPERTY,previous);}
    }
    private static EnvironmentState environment(boolean dark){return EnvironmentState.builder().skyRadiance(.12f,.2f,.31f)
            .ambientIntensity(dark?0:.3f).sunDirection(-1,0,0).sunRadiance(.7f,.4f,.2f).sunIntensity(dark?0:.4f).build();}
    private static void compare(String name,Medium medium,List<SceneLight> lights,boolean wall,boolean localWall,
            int steps,int samples,int lightSamples,boolean dark) throws Exception {
        setup(lights,wall&&!localWall);EnvironmentState env=environment(dark);
        FrameSnapshot vacuum=capture(name+"-vacuum",request(Medium.vacuum(),env,steps,samples,lightSamples,localWall));
        RenderFrameRequest request=request(medium,env,steps,samples,lightSamples,localWall);
        FrameSnapshot actual=capture(name+"-native",request);nativeFrames++;
        var a=vacuum.pixels().order(ByteOrder.LITTLE_ENDIAN);var b=actual.pixels().order(ByteOrder.LITTLE_ENDIAN);
        double maximum=0,ratio=0;
        for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
            double[][] transport=new double[samples][];
            for(int sample=0;sample<samples;sample++)transport[sample]=reference(medium,env,lights,wall,
                    direction(x,y,sample,samples),steps,x+y*width,(int)request.sequence());
            for(int c=0;c<3;c++) {int at=(y*width+x)*8+c*2;double base=Float.float16ToFloat(a.getShort(at)),expected=0;
                for(double[] value:transport)expected+=(base*value[c]+value[c+3])/samples;
                double value=Float.float16ToFloat(b.getShort(at)),error=Math.abs(value-expected);
                double tolerance=2*halfUlp(expected)+halfUlp(base)+8e-5;maximum=Math.max(maximum,error);ratio=Math.max(ratio,error/tolerance);
                require(Double.isFinite(value)&&error<=tolerance,name+" pixel="+x+","+y+" channel="+c+" actual="+value
                        +" expected="+expected+" error="+error+" tolerance="+tolerance);
            }
            require(a.getShort((y*width+x)*8+6)==b.getShort((y*width+x)*8+6),"density scattering changed alpha");
        }
        RESULTS.add(String.format(Locale.ROOT,"    {\"name\":\"%s\",\"steps\":%d,\"samples\":%d,\"lightSamples\":%d,\"maximumAbsoluteError\":%.12g,\"maximumToleranceRatio\":%.12g}",
                name,steps,samples,lightSamples,maximum,ratio));
        System.out.printf(Locale.ROOT,"Native density scattering HDR comparison=%s SPP=%d steps=%d maxAbsolute=%.9g toleranceRatio=%.9g%n",
                name,samples,steps,maximum,ratio);
    }
    /** Independent camera integral, using the API's clipped piecewise-constant density estimator. */
    private static double[] reference(Medium medium,EnvironmentState env,List<SceneLight> lights,boolean wall,
            double[] direction,int steps,int pixel,int sequence) {
        double[] origin={0,0,6},result={1,1,1,0,0,0};double distance=Math.min(10/-direction[2],12);
        var transform=medium.densityField().transform();double[] p=map(transform,origin,1),v=map(transform,direction,0),span=clip(p,v,distance);
        if(span==null)return result;double width=(span[1]-span[0])/steps,mass=0,prefix=0;double[] density=new double[steps];
        for(int j=0;j<steps;j++){double t=span[0]+(j+.5)*width;density[j]=sample(p[0]+v[0]*t,p[1]+v[1]*t,p[2]+v[2]*t);mass+=density[j]*width;}
        double[] sigma=new double[3],scatter=new double[3];
        for(int c=0;c<3;c++){sigma[c]=(float)(component(medium.extinction(),c)*medium.density());scatter[c]=(float)(component(medium.scattering(),c)*medium.density());
            double emission=(float)(component(medium.emission(),c)*medium.density()),ambient=(c==0?env.skyRed():c==1?env.skyGreen():env.skyBlue())*env.ambientIntensity();
            result[c]=Math.exp(-sigma[c]*mass);result[c+3]=(emission+scatter[c]*ambient)*integral(sigma[c],mass);}
        double minimum=Math.min(sigma[0],Math.min(sigma[1],sigma[2]));int seed=hash(pixel^sequence*0x9e3779b9);
        for(int j=0;j<steps;j++){if(density[j]<=0)continue;double u=(hash(seed^(j+1)*0x27d4eb2d)>>>8)*0x1.0p-24;
            double effective=minimum*density[j],tau=effective*width,local=tau<1e-3?u*width:-Math.log(Math.max(1-u*(1-Math.exp(-tau)),1e-30))/effective;
            double pdf=tau<1e-3?1/width:Math.exp(-effective*local)/integral(effective,width),t=span[0]+j*width+local;
            double[] position={direction[0]*t,direction[1]*t,6+direction[2]*t};
            for(int c=0;c<3;c++)result[c+3]+=Math.exp(-sigma[c]*(prefix+density[j]*local))*scatter[c]*density[j]
                    *incoming(medium,env,lights,wall,position,direction,steps,c,sigma[c])/pdf;
            prefix+=density[j]*width;
        }return result;
    }
    private static double incoming(Medium medium,EnvironmentState env,List<SceneLight> lights,boolean wall,
            double[] position,double[] ray,int steps,int channel,double sigma) {
        double result=0;double[] sun={-env.sunDirectionX(),-env.sunDirectionY(),-env.sunDirectionZ()};
        if(env.sunIntensity()>0)result+=(channel==0?env.sunRed():channel==1?env.sunGreen():env.sunBlue())*env.sunIntensity()
                *phase(dot(ray,sun),medium.anisotropy())*visibility(medium,wall,position,sun,1e20,steps,sigma);
        for(SceneLight light:lights){double[] direction;double distance=1e20,attenuation=1;
            if(light.type()==SceneLight.Type.DIRECTIONAL)direction=new double[]{-light.directionX(),-light.directionY(),-light.directionZ()};
            else {direction=new double[]{light.x()-position[0],light.y()-position[1],light.z()-position[2]};distance=Math.sqrt(dot(direction,direction));
                for(int axis=0;axis<3;axis++)direction[axis]/=distance;double fade=Math.max(1-distance/light.range(),0);attenuation=fade*fade/Math.max(distance*distance,1);
                if(light.type()==SceneLight.Type.SPOT){double cone=-(light.directionX()*direction[0]+light.directionY()*direction[1]+light.directionZ()*direction[2]);
                    double u=Math.clamp((cone-light.outerConeCosine())/(light.innerConeCosine()-light.outerConeCosine()),0,1);attenuation*=u*u*(3-2*u);}}
            double value=channel==0?light.red():channel==1?light.green():light.blue();
            result+=value*light.intensity()*attenuation*phase(dot(ray,direction),medium.anisotropy())
                    *(light.castsShadow()?visibility(medium,wall,position,direction,distance,steps,sigma):1);
        }return result;
    }
    private static double visibility(Medium medium,boolean wall,double[] position,double[] direction,double distance,int steps,double sigma){
        double epsilon=Math.max(1e-5,Math.max(Math.abs(position[0]),Math.max(Math.abs(position[1]),Math.abs(position[2])))*2e-7);
        double range=Math.max(distance-2*epsilon,epsilon);double[] start={position[0]+direction[0]*epsilon,position[1]+direction[1]*epsilon,position[2]+direction[2]*epsilon};
        if(wall&&direction[0]>1e-12){double hit=(2-start[0])/direction[0];if(hit>epsilon&&hit<range)return 0;}
        if(direction[2]<-1e-12){double hit=(-4-start[2])/direction[2];if(hit>epsilon&&hit<range)return 0;}
        return Math.exp(-sigma*mass(medium.densityField().transform(),start,direction,Math.min(range,12),steps));
    }
    private static double mass(AffineTransform transform,double[] origin,double[] direction,double distance,int steps){double[] p=map(transform,origin,1),v=map(transform,direction,0),span=clip(p,v,distance);if(span==null)return 0;double width=(span[1]-span[0])/steps,total=0;for(int j=0;j<steps;j++){double t=span[0]+(j+.5)*width;total+=sample(p[0]+v[0]*t,p[1]+v[1]*t,p[2]+v[2]*t)*width;}return total;}
    private static double[] map(AffineTransform transform,double[] vector,int w){double[] result=new double[3];for(int row=0;row<3;row++){result[row]=transform.element(row*4+3)*w;for(int column=0;column<3;column++)result[row]+=transform.element(row*4+column)*vector[column];}return result;}
    private static double[] clip(double[] origin,double[] direction,double distance){double first=0,last=distance;for(int axis=0;axis<3;axis++){if(Math.abs(direction[axis])<1e-30){if(origin[axis]<0||origin[axis]>1)return null;}else{double a=-origin[axis]/direction[axis],b=(1-origin[axis])/direction[axis];first=Math.max(first,Math.min(a,b));last=Math.min(last,Math.max(a,b));if(last<=first)return null;}}return new double[]{first,last};}
    private static double sample(double x,double y,double z){if(x<0||x>1||y<0||y>1||z<0||z>1)return 0;double[] p={x*NX-.5,y*NY-.5,z*NZ-.5},f=new double[3];int[] cell=new int[3],dims={NX,NY,NZ};for(int a=0;a<3;a++){cell[a]=(int)Math.floor(p[a]);f[a]=p[a]-cell[a];}double value=0;for(int k=0;k<2;k++)for(int j=0;j<2;j++)for(int i=0;i<2;i++){int ix=Math.clamp(cell[0]+i,0,dims[0]-1),iy=Math.clamp(cell[1]+j,0,dims[1]-1),iz=Math.clamp(cell[2]+k,0,dims[2]-1);value+=red(ix+NX*iy+NX*NY*iz)/255.0*(i==0?1-f[0]:f[0])*(j==0?1-f[1]:f[1])*(k==0?1-f[2]:f[2]);}return value;}
    private static int red(int index){return (index*73+(index/3)*31+17)&255;}
    private static double integral(double sigma,double mass){return sigma==0?mass:-Math.expm1(-sigma*mass)/sigma;}
    private static double phase(double cosine,double g){double d=1+g*g-2*g*cosine;return (1-g*g)/(4*Math.PI*d*Math.sqrt(d));}
    private static double component(Rgb value,int c){return c==0?value.red():c==1?value.green():value.blue();}
    private static double dot(double[] a,double[] b){return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];}
    private static int hash(int value){value^=value>>>16;value*=0x7feb352d;value^=value>>>15;value*=0x846ca68b;return value^(value>>>16);}
    private static double halfUlp(double value){return Math.max(Math.scalb(1.0,-24),Math.scalb(1.0,Math.getExponent(Math.abs(value))-10));}
    private static double[] direction(int x,int y,int sample,int samples){double[] offset=samples==1?new double[]{.5,.5}:samples==2?(sample==0?new double[]{.25,.25}:new double[]{.75,.75}):EIGHT[sample];double fy=(float)Math.tan(Math.toRadians(8)*.5),fx=(float)(Math.tan(Math.toRadians(8)*.5)*width/height),dx=((x+offset[0])/width*2-1)*fx,dy=-((y+offset[1])/height*2-1)*fy,scale=1/Math.sqrt(1+dx*dx+dy*dy);return new double[]{dx*scale,dy*scale,-scale};}
    private static RenderFrameRequest request(Medium medium,EnvironmentState env,int steps,int samples,int lightSamples,boolean localWall){
        var camera=CameraState.lookAt(0,0,6,0,0,-4).verticalFieldOfViewDegrees(8).aspectRatio((double)width/height).build();
        var batch=localWall?FramePrimitiveBatch.builder().add(PrimitiveInstance.builder(2).build()).build():FramePrimitiveBatch.empty();
        return RenderFrameRequest.builder(++sequence,width,height,camera).minimumSceneRevision(revision).clock(FrameClock.of(0,0))
                .environment(env.toBuilder().cameraMedium(medium).build()).antiAliasing(new AntiAliasingState(samples)).primitiveBatch(batch)
                .mediumTransport(MediumTransport.balanced().withScatteringSteps(steps).withLightSamples(lightSamples).withMaxDistance(12).withTransmittanceCutoff(0)).build();
    }
    private static void setup(List<SceneLight> lights,boolean wall){
        byte[] rgba=new byte[NX*NY*NZ*4];for(int i=0;i<NX*NY*NZ;i++){rgba[i*4]=(byte)red(i);rgba[i*4+1]=(byte)255;rgba[i*4+3]=(byte)255;}
        var tx=SceneTransaction.builder(++revision).resetScene().upsert(TextureAsset.builder(50,NX,NY*NZ).colorSpace(TextureAsset.ColorSpace.LINEAR).pixelsRgba8(rgba).build())
                .upsert(MaterialAsset.builder(1).doubleSided(true).shadingModel(MaterialAsset.ShadingModel.UNLIT).baseColorRgba8(0xff806040).build())
                .upsert(MeshAsset.triangles(1,new float[]{-20,-20,-4,20,-20,-4,20,20,-4,-20,20,-4},new int[]{0,1,2,2,3,0},1))
                .upsert(MeshAsset.triangles(2,new float[]{2,-20,-20,2,20,-20,2,20,20,2,-20,20},new int[]{0,1,2,2,3,0},1))
                .upsert(SceneInstance.builder(1,1).build());
        if(wall)tx.upsert(SceneInstance.builder(2,2).build());for(SceneLight light:lights)tx.upsert(light);renderer.apply(tx.build());
    }
    private static FrameSnapshot capture(String name,RenderFrameRequest request) throws Exception {
        long deadline=System.nanoTime()+TIMEOUT.toNanos();while(!(renderer.trySubmit(request) instanceof Renderer.FrameSubmitted)){require(System.nanoTime()<deadline,"density scatter admission timeout");Thread.sleep(1);}
        deadline=System.nanoTime()+TIMEOUT.toNanos();while(System.nanoTime()<deadline){var ready=renderer.pollFrame();if(ready.isPresent()&&ready.orElseThrow().frameSequence()==request.sequence()){var frame=ready.orElseThrow();require(frame.renderedSceneRevision()==revision&&frame.format()==TextureFormat.RGBA16_FLOAT,"density scattering final HDR provenance");byte[] pixels=new byte[frame.byteCount()];frame.pixels().get(pixels);Files.write(OUTPUT.resolve(name+".rgba16f"),pixels);return frame;}Thread.sleep(1);}throw new AssertionError("density scattering completion timeout");
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
