package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.ByteOrder;
import java.time.Duration;
import java.util.Locale;
import java.util.SplittableRandom;
import top.ceroxe.rt.renderer.api.*;

/** Public HDR: analytic constant grids and an independent continuous-density null-collision estimator. */
public final class CudaHeterogeneousMultipleScatteringProductionNativeSelfTest {
    private static final Duration TIMEOUT=Duration.ofSeconds(60);
    private static final int NX=4,NY=2,NZ=4;
    private static final double[][] EIGHT={{.5,.3333},{.25,.6667},{.75,.1111},{.125,.4444},
            {.625,.7778},{.375,.2222},{.875,.5556},{.0625,.8889}};
    private static final AffineTransform MAP=new AffineTransform(new float[]{1f/6,0,0,.5f,0,1f/6,0,.5f,0,0,1f/6,.5f});
    private static long sequence,revision;
    private static int nativeFrames;
    private static int width=96,height=64;
    private static int steps=16;
    private static Renderer renderer;
    private CudaHeterogeneousMultipleScatteringProductionNativeSelfTest() { }

    public static void main(String[] args) throws Exception {
        String previous=System.getProperty(CudaCameraMediumProfile.ENABLE_PROPERTY);
        System.setProperty(CudaCameraMediumProfile.ENABLE_PROPERTY,"true");
        long routes=VulkanGpuSceneFrameSubmitter.diagnosticNativeMultipleScatteringSubmissions.sum();
        long collisions=CudaMultipleScatteringManager.diagnosticCollisions.sum();
        try(Renderer owned=Renderer.open("vulkan-rt",RendererConfig.builder().maxFramesInFlight(2).validationEnabled(true)
                .frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F).temporalRendering(TemporalRenderingOptions.disabled()).build())) {
            renderer=owned;setup(255,false,0,false);
            EnvironmentState env=EnvironmentState.builder().skyRadiance(.8f,.5f,.25f).ambientIntensity(.7f).sunIntensity(0).build();
            Medium uniform=new Medium(new Rgb(.2f,.35f,.5f),new Rgb(.5f,.35f,.2f),Rgb.ZERO,1,1,0);
            for(float g:new float[]{0,.6f})for(int bounce:new int[]{1,2,8}) {
                int samples=bounce==8?8:bounce;
                Medium medium=new Medium(uniform.absorption(),uniform.scattering(),Rgb.ZERO,1,1,g);
                analytic("constant-grid",grid(medium),env,bounce,samples,1);
            }
            // The immutable texture id remains the same, while its generation changes.
            setup(127,false,0,false);analytic("texture-generation",grid(uniform),env,2,2,127.0/255);
            analytic("uniform-after-grid",uniform,env,2,2,1);
            analytic("grid-after-uniform",grid(uniform),env,2,2,127.0/255);

            steps=64;setup(0,true,0,false);
            Medium varying=grid(new Medium(uniform.absorption(),uniform.scattering(),Rgb.ZERO,1,1,.6f));
            compareNullCollision("nonuniform-grid",varying,env,2,8,false,0);
            compareNullCollision("nonuniform-RGB-emission",grid(new Medium(uniform.absorption(),uniform.scattering(),
                    new Rgb(.015f,.005f,.02f),1,1,.6f)),env,2,2,false,0);
            // The exact analog estimator below performs delta tracking against the continuous
            // trilinear field. It shares neither interval partitioning nor production random seeds.
            EnvironmentState dark=env.toBuilder().skyRadiance(0,0,0).ambientIntensity(0).build();
            setup(0,true,29,false);
            double[] all=mean(capture(request(varying,dark,2,2,0)));
            double[] sampled=mean(capture(request(varying,dark,2,2,8)));
            double[] reference=nullCollision(varying,dark,2,2,29,false,400_000);
            close(all,reference,.0025,"29-light bounded exhaustive connections");
            close(sampled,reference,.0025,"eight-light reservoir connections");
            require(all[0]>0,"real direct-light connections produced black");
            setup(0,true,29,true);
            double[] blocked=mean(capture(request(varying,dark,2,2,0)));
            double[] blockedReference=nullCollision(varying,dark,2,2,29,true,400_000);
            close(blocked,blockedReference,.0025,"real opaque geometry and density shadows");
            require(blocked[0]<all[0]*.75,"opaque geometry failed to reduce light transport");
            setup(255,false,0,false);width=31;height=19;
            for(double value:mean(capture(request(grid(uniform),dark,2,2,0))))require(value==0,"resize/black transport acquired radiance");
            require(renderer.health().activeFailure().isEmpty(),"heterogeneous continuation session unhealthy");
        } finally {if(previous==null)System.clearProperty(CudaCameraMediumProfile.ENABLE_PROPERTY);
            else System.setProperty(CudaCameraMediumProfile.ENABLE_PROPERTY,previous);}
        require(VulkanGpuSceneFrameSubmitter.diagnosticNativeMultipleScatteringSubmissions.sum()-routes==nativeFrames,"public HDR bypassed native continuation");
        require(CudaMultipleScatteringManager.diagnosticCollisions.sum()>collisions,"additional real scattering collisions were not evaluated");
        System.out.println("CudaHeterogeneousMultipleScatteringProductionNativeSelfTest passed: analytic orders, continuous-density independent reference, texture generations, light chunks/reservoirs, geometry, SPP 1/2/8, bounce 1/2/8 and resize");
    }
    private static void analytic(String name,Medium medium,EnvironmentState env,int bounces,int samples,double density) throws Exception {
        double[] vacuum=mean(capture(request(Medium.vacuum(),env,bounces,samples,0)));
        double[] actual=mean(capture(request(medium,env,bounces,samples,0))),expected=new double[3];
        for(int c=0;c<3;c++){double average=component(new Rgb(env.skyRed(),env.skyGreen(),env.skyBlue()),c)*env.ambientIntensity()*.675;
            double term=1,orders=0;for(int k=1;k<=bounces+1;k++){term*=component(medium.scattering(),c)*density*2/k;
                orders+=term*(average+(vacuum[c]-average)*Math.pow(medium.anisotropy(),k));}
            expected[c]=Math.exp(-component(medium.extinction(),c)*density*2)*(vacuum[c]+orders);}
        close(actual,expected,.004,name+" order="+(bounces+1)+" SPP="+samples+" g="+medium.anisotropy());
    }
    private static void compareNullCollision(String name,Medium medium,EnvironmentState env,int bounce,int samples,boolean wall,int lights) throws Exception {
        double[] actual=mean(capture(request(medium,env,bounce,samples,0)));
        double[] expected=nullCollision(medium,env,bounce,samples,lights,wall,400_000);
        close(actual,expected,.0035,name);
    }
    /** Scalar-extinction analog path tracing with Woodcock tracking, independent of CUDA's optical CDF. */
    private static double[] nullCollision(Medium medium,EnvironmentState env,int bounce,int samples,int lights,boolean wall,int count) {
        SplittableRandom rng=new SplittableRandom(0x2385cc20d0b3a61L);double[] sum=new double[3];
        double sigma=medium.extinction().red(),majorant=sigma;double lightIntensity=.003*lights*(lights+1)/2;
        for(int path=0;path<count;path++) {
            int pixel=rng.nextInt(width*height),sample=rng.nextInt(samples);double[] d=direction(pixel%width,pixel/width,sample,samples);
            double[] p={0,0,0},throughput={1,1,1};double remaining=2;int order=0;
            while(remaining>0) {
                double t=-Math.log1p(-rng.nextDouble())/majorant;
                double hit=wall&&d[0]>1e-12?(.5-p[0])/d[0]:Double.POSITIVE_INFINITY;
                if(hit>1e-5&&hit<Math.min(t,remaining))break; // Opaque black terminal surface.
                if(t>=remaining){double sky=.675+.325*d[1];for(int c=0;c<3;c++)sum[c]+=throughput[c]
                        *(c==0?env.skyRed():c==1?env.skyGreen():env.skyBlue())*Math.max(env.ambientIntensity(),.05)*sky;break;}
                for(int a=0;a<3;a++)p[a]+=d[a]*t;remaining-=t;
                if(rng.nextDouble()>=density(p))continue;
                // The analog extinction event estimates the continuous emission integral
                // with emission/sigma, including the segment at the final scattering order.
                for(int c=0;c<3;c++)sum[c]+=throughput[c]*component(medium.emission(),c)/sigma;
                if(++order>bounce+1)break;
                for(int c=0;c<3;c++)throughput[c]*=component(medium.scattering(),c)/sigma;
                if(lights>0&&(!wall||p[0]>.5)){
                    double direct=lightIntensity*phase(d[0],medium.anisotropy())*Math.exp(-sigma*lightMass(p));
                    for(int c=0;c<3;c++)sum[c]+=throughput[c]*direct*(c==0?.5:c==1?.3:.8);
                }
                d=sampleHg(d,medium.anisotropy(),rng.nextDouble(),rng.nextDouble());
            }
        }
        for(int c=0;c<3;c++)sum[c]/=count;return sum;
    }
    private static double[] sampleHg(double[] axis,double g,double u,double v){double cosine=Math.abs(g)<1e-6?1-2*u:
            (1+g*g-Math.pow((1-g*g)/(1-g+2*g*u),2))/(2*g);cosine=Math.clamp(cosine,-1,1);
        double sine=Math.sqrt(Math.max(0,1-cosine*cosine)),phi=2*Math.PI*v;
        double[] helper=Math.abs(axis[2])<.99?new double[]{0,0,1}:new double[]{0,1,0};
        double[] tangent={helper[1]*axis[2]-helper[2]*axis[1],helper[2]*axis[0]-helper[0]*axis[2],helper[0]*axis[1]-helper[1]*axis[0]};
        double length=Math.sqrt(dot(tangent,tangent));for(int a=0;a<3;a++)tangent[a]/=length;
        double[] bitangent={axis[1]*tangent[2]-axis[2]*tangent[1],axis[2]*tangent[0]-axis[0]*tangent[2],axis[0]*tangent[1]-axis[1]*tangent[0]},out=new double[3];
        for(int a=0;a<3;a++)out[a]=cosine*axis[a]+sine*(Math.cos(phi)*tangent[a]+Math.sin(phi)*bitangent[a]);return out;
    }
    private static double lightMass(double[] p){double distance=Math.min(3-p[0],2),mass=0;int n=256;for(int i=0;i<n;i++)mass+=density(new double[]{p[0]+distance*(i+.5)/n,p[1],p[2]})*distance/n;return mass;}
    private static double density(double[] world){double[] p={world[0]/6+.5,world[1]/6+.5,world[2]/6+.5};for(double v:p)if(v<0||v>1)return 0;
        int[] dims={NX,NY,NZ},cell=new int[3];double[] f=new double[3];for(int a=0;a<3;a++){double q=p[a]*dims[a]-.5;cell[a]=(int)Math.floor(q);f[a]=q-cell[a];}
        double result=0;for(int z=0;z<2;z++)for(int y=0;y<2;y++)for(int x=0;x<2;x++){int ix=Math.clamp(cell[0]+x,0,NX-1),iz=Math.clamp(cell[2]+z,0,NZ-1);
            result+=red(ix,iz)/255.0*(x==0?1-f[0]:f[0])*(y==0?1-f[1]:f[1])*(z==0?1-f[2]:f[2]);}return result;}
    private static int red(int x,int z){return 48+x*41+z*17;}
    private static double phase(double cosine,double g){double d=1+g*g-2*g*cosine;return (1-g*g)/(4*Math.PI*d*Math.sqrt(d));}
    private static double dot(double[] a,double[] b){return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];}
    private static double component(Rgb rgb,int c){return c==0?rgb.red():c==1?rgb.green():rgb.blue();}
    private static Medium grid(Medium medium){return medium.withDensityField(MediumDensityField.grid(50,NZ,MAP));}
    private static RenderFrameRequest request(Medium medium,EnvironmentState env,int bounces,int samples,int lights){return RenderFrameRequest.builder(++sequence,width,height,
            CameraState.lookAt(0,0,0,0,0,-1).verticalFieldOfViewDegrees(40).aspectRatio((double)width/height).build()).minimumSceneRevision(revision)
            .environment(env.toBuilder().cameraMedium(medium).build()).antiAliasing(new AntiAliasingState(samples))
            .mediumTransport(MediumTransport.balanced().withMaxDistance(2).withScatteringSteps(steps).withScatteringBounces(bounces).withLightSamples(lights).withTransmittanceCutoff(0)).build();}
    private static double[] direction(int x,int y,int sample,int samples){double[] o=samples==1?new double[]{.5,.5}:samples==2?(sample==0?new double[]{.25,.25}:new double[]{.75,.75}):EIGHT[sample];
        double fy=Math.tan(Math.toRadians(40)*.5),fx=fy*width/height,dx=((x+o[0])/width*2-1)*fx,dy=-((y+o[1])/height*2-1)*fy,n=1/Math.sqrt(1+dx*dx+dy*dy);return new double[]{dx*n,dy*n,-n};}
    private static void setup(int constant,boolean varying,int lightCount,boolean wall){byte[] rgba=new byte[NX*NY*NZ*4];for(int z=0;z<NZ;z++)for(int y=0;y<NY;y++)for(int x=0;x<NX;x++){
            int i=(x+NX*y+NX*NY*z)*4;rgba[i]=(byte)(varying?red(x,z):constant);rgba[i+3]=(byte)255;}
        var tx=SceneTransaction.builder(++revision).resetScene().upsert(TextureAsset.builder(50,NX,NY*NZ).colorSpace(TextureAsset.ColorSpace.LINEAR).pixelsRgba8(rgba).build());
        for(int i=0;i<lightCount;i++)tx.upsert(SceneLight.directional(10+i,-1,0,0).color(.5f,.3f,.8f).intensity((i+1)*.003f).castsShadow(true).build());
        if(wall)tx.upsert(MaterialAsset.builder(1).doubleSided(true).shadingModel(MaterialAsset.ShadingModel.UNLIT).baseColorRgba8(0xff000000).build())
                .upsert(MeshAsset.triangles(1,new float[]{.5f,-20,-20,.5f,20,-20,.5f,20,20,.5f,-20,20},new int[]{0,1,2,2,3,0},1))
                .upsert(SceneInstance.builder(1,1).build());renderer.apply(tx.build());}
    private static FrameSnapshot capture(RenderFrameRequest frame) throws Exception {long deadline=System.nanoTime()+TIMEOUT.toNanos();while(!(renderer.trySubmit(frame) instanceof Renderer.FrameSubmitted)){
            require(System.nanoTime()<deadline,"heterogeneous continuation admission timeout");Thread.sleep(1);}deadline=System.nanoTime()+TIMEOUT.toNanos();
        while(System.nanoTime()<deadline){var ready=renderer.pollFrame();if(ready.isPresent()&&ready.orElseThrow().frameSequence()==frame.sequence()){
                if(!frame.environment().cameraMedium().scattering().isZero())nativeFrames++;return ready.orElseThrow();}Thread.sleep(1);}throw new AssertionError("heterogeneous continuation final HDR timeout");}
    private static double[] mean(FrameSnapshot frame){var pixels=frame.pixels().order(ByteOrder.LITTLE_ENDIAN);double[] sum=new double[3];int n=frame.width()*frame.height();for(int i=0;i<n;i++){
            for(int c=0;c<3;c++){float value=Float.float16ToFloat(pixels.getShort(i*8+c*2));require(Float.isFinite(value)&&value>=0,"invalid continuation HDR");sum[c]+=value/n;}
            require(Float.float16ToFloat(pixels.getShort(i*8+6))==1,"continuation alpha");}return sum;}
    private static void close(double[] actual,double[] expected,double tolerance,String name){for(int c=0;c<3;c++)require(Math.abs(actual[c]-expected[c])<=tolerance,
            name+" channel="+c+" actual="+actual[c]+" expected="+expected[c]+" tolerance="+tolerance);
        System.out.printf(Locale.ROOT,"Heterogeneous continuation %s meanRGB=%.8f,%.8f,%.8f reference=%.8f,%.8f,%.8f%n",name,actual[0],actual[1],actual[2],expected[0],expected[1],expected[2]);}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
