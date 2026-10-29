package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import top.ceroxe.rt.renderer.api.*;

/** Real CUDA sampling and connection shadows against independent double reference calculations. */
public final class CudaDensityScatteringNativeSelfTest {
    private static final int COUNT=65;
    private static final int[] RED={16,91,188,242,214,133,67,251};
    public static void main(String[] args) throws Exception {
        byte[] rgba=new byte[32];for(int i=0;i<8;i++){rgba[i*4]=(byte)RED[i];rgba[i*4+3]=(byte)255;}
        var texture=TextureAsset.builder(7,2,4).colorSpace(TextureAsset.ColorSpace.LINEAR).pixelsRgba8(rgba).build();
        var transform=new AffineTransform(new float[]{.2f,0,.025f,.5f, 0,.25f,0,.5f, 0,0,.5f,.5f});
        var medium=new Medium(new Rgb(.12f,.08f,.04f),new Rgb(.08f,.12f,.16f),new Rgb(.01f,.005f,0),1.3f,1,.3f)
                .withDensityField(MediumDensityField.grid(7,2,transform));
        double maximum=0;
        try(var runtime=CudaMediumRuntime.open(0);
            var prepare=runtime.compileBatchKernel(CudaDensityScatteringSource.source(),"density_scattering_prepare");
            var resolve=runtime.compileBatchKernel(CudaDensityScatteringSource.source(),"density_scattering_resolve")) {
            for(int steps:new int[]{1,7,64}) {
                var frame=RenderFrameRequest.builder(47,COUNT,1,CameraState.lookAt(0,0,3,0,0,0).build())
                        .environment(EnvironmentState.builder().skyRadiance(.2f,.3f,.4f).ambientIntensity(.2f).cameraMedium(medium).build())
                        .mediumTransport(MediumTransport.balanced().withScatteringSteps(steps).withLightSamples(1)
                                .withMaxDistance(12).withTransmittanceCutoff(0)).build();
                var scene=CudaDensityScatteringAbi.scene(frame,COUNT,1,1,id->texture,null,512L*1024*1024);var plan=scene.plan();
                double[][][] reference=new double[COUNT][][];
                try(var storage=runtime.allocate(plan.bytes()+32);var arena=Arena.ofConfined()) {
                    var host=arena.allocate(storage.byteSize(),16);host.fill((byte)0);ByteBuffer bytes=host.asByteBuffer().order(ByteOrder.nativeOrder());
                    bytes.put(scene.metadata(0,COUNT,0,0));
                    for(int at=(int)plan.bytes();at<host.byteSize();at++)bytes.put(at,(byte)0x5a);
                    for(int index=0;index<COUNT;index++) {
                        int at=plan.rayOffset()+index*32;float ox=index==COUNT-1?100:(index%9-4)*.18f,oy=(index%7-3)*.12f,oz=3;
                        float dx=(index%5-2)*.04f,dy=(index%3-1)*.03f,dz=-1;float scale=(float)(1/Math.sqrt(dx*dx+dy*dy+1));dx*=scale;dy*=scale;dz*=scale;
                        bytes.putFloat(at,ox).putFloat(at+4,oy).putFloat(at+8,oz).putFloat(at+16,dx).putFloat(at+20,dy).putFloat(at+24,dz);
                        reference[index]=reference(frame,medium,transform,new double[]{ox,oy,oz},new double[]{dx,dy,dz},index,steps);
                    }
                    runtime.upload(storage.slice(0,storage.byteSize()),host);runtime.launchBlocking(prepare,storage.slice(0,plan.bytes()),COUNT,1);
                    runtime.download(storage.slice(0,storage.byteSize()),host);
                    require(bytes.getInt(112)==0,"density prepare reported invalid state");
                    for(int index=0;index<COUNT;index++) {
                        double[][] expected=reference[index];
                        for(int c=0;c<3;c++){maximum=Math.max(maximum,near(bytes.getFloat(plan.transmittanceOffset()+index*16+c*4),expected[0][c]));
                            maximum=Math.max(maximum,near(bytes.getFloat(plan.emissionOffset()+index*16+c*4),expected[0][c+3]));}
                        for(int j=0;j<steps;j++) {
                            int sample=plan.sampleOffset()+(index*steps+j)*32;double[] value=expected[j+1];
                            require((bytes.getFloat(sample+12)!=0)==(value[6]!=0),"clipped density sample activity");
                            if(value[6]==0)continue;
                            for(int c=0;c<3;c++){maximum=Math.max(maximum,near(bytes.getFloat(sample+c*4),value[c]));
                                maximum=Math.max(maximum,near(bytes.getFloat(sample+16+c*4),value[c+3]));}
                            int connection=plan.connectionOffset()+(index*steps+j)*plan.connections()*32;
                            bytes.putFloat(connection,.4f).putFloat(connection+4,.3f).putFloat(connection+8,.2f).putFloat(connection+12,3.25f)
                                    .putFloat(connection+16,1).putFloat(connection+20,0).putFloat(connection+24,0);
                            bytes.putFloat(connection+32,.07f).putFloat(connection+36,.11f).putFloat(connection+40,.17f).putFloat(connection+44,-1)
                                    .putFloat(connection+48,0).putFloat(connection+52,1).putFloat(connection+56,0);
                        }
                    }
                    runtime.upload(storage.slice(0,storage.byteSize()),host);runtime.launchBlocking(resolve,storage.slice(0,plan.bytes()),COUNT,1);
                    runtime.download(storage.slice(0,storage.byteSize()),host);require(bytes.getInt(112)==0,"density resolve reported invalid state");
                    for(int index=0;index<COUNT;index++)for(int c=0;c<3;c++)maximum=Math.max(maximum,near(
                            bytes.getFloat(plan.emissionOffset()+index*16+c*4),reference[index][0][c+6]));
                    for(int at=(int)plan.bytes();at<host.byteSize();at++)require(bytes.get(at)==(byte)0x5a,"density scattering output guard");
                }
            }
        }
        System.out.printf(Locale.ROOT,"CudaDensityScatteringNativeSelfTest passed: paths=%d steps=1/7/64 true-sample+per-connection-shadow maxScaledError=%.9g%n",COUNT,maximum);
    }
    private static double[][] reference(RenderFrameRequest frame,Medium medium,AffineTransform transform,double[] origin,double[] direction,int pixel,int steps) {
        double[][] result=new double[steps+1][9];double[] p=map(transform,origin,1),v=map(transform,direction,0);
        double[] clip=clip(p,v,12);double width=clip==null?0:(clip[1]-clip[0])/steps,mass=0;double[] densities=new double[steps];
        for(int j=0;j<steps;j++){double t=clip==null?0:clip[0]+(j+.5)*width;densities[j]=width==0?0:sample(p[0]+v[0]*t,p[1]+v[1]*t,p[2]+v[2]*t);mass+=densities[j]*width;}
        float[] sigma={medium.extinction().red()*medium.density(),medium.extinction().green()*medium.density(),medium.extinction().blue()*medium.density()};
        float[] scatter={medium.scattering().red()*medium.density(),medium.scattering().green()*medium.density(),medium.scattering().blue()*medium.density()};
        float[] emitted={medium.emission().red()*medium.density(),medium.emission().green()*medium.density(),medium.emission().blue()*medium.density()};
        float[] ambient={frame.environment().skyRed()*frame.environment().ambientIntensity(),frame.environment().skyGreen()*frame.environment().ambientIntensity(),frame.environment().skyBlue()*frame.environment().ambientIntensity()};
        for(int c=0;c<3;c++){result[0][c]=Math.exp(-sigma[c]*mass);result[0][c+3]=(emitted[c]+scatter[c]*ambient[c])*integral(sigma[c],mass);result[0][c+6]=result[0][c+3];}
        int seed=hash(pixel^(int)frame.sequence()*0x9e3779b9);double prefix=0,ref=Math.min(sigma[0],Math.min(sigma[1],sigma[2]));
        for(int j=0;j<steps;j++){double density=densities[j];if(density<=0||width==0)continue;
            double u=(hash(seed^(j+1)*0x27d4eb2d)>>>8)*0x1.0p-24,eff=ref*density,tau=eff*width;
            double local=tau<1e-3?u*width:-Math.log(Math.max(1-u*(1-Math.exp(-tau)),1e-30))/eff;
            double pdf=tau<1e-3?1/width:Math.exp(-eff*local)/integral(eff,width),t=clip[0]+j*width+local;double[] value=result[j+1];
            for(int c=0;c<3;c++){value[c]=origin[c]+direction[c]*t;value[c+3]=Math.exp(-sigma[c]*(prefix+density*local))*scatter[c]*density/pdf;}value[6]=1;
            double epsilon=Math.max(1e-5,Math.max(Math.abs(value[0]),Math.max(Math.abs(value[1]),Math.abs(value[2])))*2e-7);
            double shadow=mass(transform,new double[]{value[0]+epsilon,value[1],value[2]},new double[]{1,0,0},3.25,steps);
            float[] source={.4f,.3f,.2f},unshadowed={.07f,.11f,.17f};
            for(int c=0;c<3;c++)result[0][c+6]+=value[c+3]*(source[c]*Math.exp(-sigma[c]*shadow)+unshadowed[c]);prefix+=density*width;
        }return result;
    }
    private static double integral(double sigma,double distance){return sigma==0?distance:-Math.expm1(-sigma*distance)/sigma;}
    private static int hash(int value){value^=value>>>16;value*=0x7feb352d;value^=value>>>15;value*=0x846ca68b;return value^(value>>>16);}
    private static double[] map(AffineTransform transform,double[] point,int w){double[] result=new double[3];for(int row=0;row<3;row++){result[row]=transform.element(row*4+3)*w;for(int col=0;col<3;col++)result[row]+=transform.element(row*4+col)*point[col];}return result;}
    private static double[] clip(double[] origin,double[] direction,double distance){double first=0,last=distance;for(int axis=0;axis<3;axis++){if(direction[axis]==0){if(origin[axis]<0||origin[axis]>1)return null;}else{double a=-origin[axis]/direction[axis],b=(1-origin[axis])/direction[axis];first=Math.max(first,Math.min(a,b));last=Math.min(last,Math.max(a,b));if(last<=first)return null;}}return new double[]{first,last};}
    private static double mass(AffineTransform transform,double[] origin,double[] direction,double distance,int steps){double[] p=map(transform,origin,1),v=map(transform,direction,0),span=clip(p,v,distance);if(span==null)return 0;double width=(span[1]-span[0])/steps,total=0;for(int j=0;j<steps;j++){double t=span[0]+(j+.5)*width;total+=sample(p[0]+v[0]*t,p[1]+v[1]*t,p[2]+v[2]*t)*width;}return total;}
    private static double sample(double x,double y,double z){if(x<0||x>1||y<0||y>1||z<0||z>1)return 0;double[] p={x*2-.5,y*2-.5,z*2-.5},f=new double[3];int[] cell=new int[3];for(int a=0;a<3;a++){cell[a]=(int)Math.floor(p[a]);f[a]=p[a]-cell[a];}double value=0;for(int k=0;k<2;k++)for(int j=0;j<2;j++)for(int i=0;i<2;i++){int ix=Math.clamp(cell[0]+i,0,1),iy=Math.clamp(cell[1]+j,0,1),iz=Math.clamp(cell[2]+k,0,1);value+=(float)(RED[ix+2*iy+4*iz]/255f)*(i==0?1-f[0]:f[0])*(j==0?1-f[1]:f[1])*(k==0?1-f[2]:f[2]);}return value;}
    private static double near(double actual,double expected){double error=Math.abs(actual-expected)/Math.max(1,Math.abs(expected));require(Double.isFinite(actual)&&error<8e-6,"density scatter actual="+actual+" expected="+expected+" scaledError="+error);return error;}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
