package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Locale;
import top.ceroxe.rt.renderer.api.*;

/** Real packaged CUDA sampler and clipped-budget quadrature against an independent double reference. */
public final class CudaMediumDensityNativeSelfTest {
    private static final int COUNT=513,BYTES=192;
    private static final int[] RED={0,32,64,96,128,160,224,255};
    private static final int[][] DIMENSIONS={{2,2,2},{1,4,2},{4,2,1},{8,1,1}};
    private static final String PROBE="""
        extern "C" __global__ void density_probe(float *records,int count) {
            int i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=count)return;
            float *r=records+i*48;const unsigned char *b=(const unsigned char *)r;
            const rtr_density::Descriptor &grid=*(const rtr_density::Descriptor *)(b+16);
            r[0]=rtr_density::DensityGridTexture_ConstEvaluate((const float *)(b+grid.atlasOffset),grid,
                make_float3(r[28],r[29],r[30]));
            r[1]=rtr_density::mass(b,((int *)r)[42],make_float3(r[32],r[33],r[34]),make_float3(r[36],r[37],r[38]),
                r[40],((unsigned int *)r)[41]);
        }
        """;
    public static void main(String[] arguments) throws Exception {
        byte[] rgba=new byte[32];for(int i=0;i<8;i++) {rgba[i*4]=(byte)RED[i];rgba[i*4+3]=(byte)255;}
        var densities=new CudaMediumDensityAbi.Scene[DIMENSIONS.length];
        var transforms=new AffineTransform[DIMENSIONS.length];
        for(int fixture=0;fixture<DIMENSIONS.length;fixture++) {
            int[] dims=DIMENSIONS[fixture];
            var texture=TextureAsset.builder(7,dims[0],dims[1]*dims[2]).colorSpace(TextureAsset.ColorSpace.LINEAR).pixelsRgba8(rgba).build();
            var transform=new AffineTransform(fixture==0?new float[]{.5f,0,0,-5, 0,.25f,0,1, 0,0,2,-6}
                    :new float[]{.5f,0,.25f,-5, .125f,.25f,0,1, 0,-.125f,2,-6});
            var medium=new Medium(new Rgb(.1f,.2f,.3f),Rgb.ZERO,Rgb.ZERO,1,1,0)
                    .withDensityField(MediumDensityField.grid(7,dims[2],transform));
            densities[fixture]=CudaMediumDensityAbi.prepare(medium,List.of(),id->texture);
            transforms[fixture]=transform;
        }
        ByteBuffer input=ByteBuffer.allocate(COUNT*BYTES).order(ByteOrder.nativeOrder());
        double[] expectedSample=new double[COUNT],expectedMass=new double[COUNT];
        for(int index=0;index<COUNT;index++) {
            int at=index*BYTES;ByteBuffer record=input.duplicate().order(ByteOrder.nativeOrder());record.position(at);record=record.slice().order(ByteOrder.nativeOrder());
            int fixture=index<2?0:index%DIMENSIONS.length;int[] dims=DIMENSIONS[fixture];
            densities[fixture].write(record,16);
            double x=((index*17)%137)/100.0-.18,y=((index*31)%131)/100.0-.16,z=((index*43)%139)/100.0-.19;
            if(index<8) {x=(index&1);y=(index>>1)&1;z=(index>>2)&1;}
            record.putFloat(112,(float)x).putFloat(116,(float)y).putFloat(120,(float)z);
            double ox=7+(index%7)*.7,oy=-6+(index%5)*1.1,oz=2.1+(index%11)*.12;
            double dx=((index%9)-4)*.16,dy=((index%7)-3)*.17,dz=((index%5)-2)*.13;
            if(index==0) {ox=0;oy=-2;oz=3.25;dx=1;dy=0;dz=0;} // Long ray, tiny clipped interval, one step.
            if(index==1) {ox=10;oy=-2;oz=3.25;dx=0;dy=0;dz=0;} // Parallel in-domain.
            int steps=index%64+1;float distance=index==0?100:3.5f;
            if(index==502)distance=0;
            if(index==503)distance=-1;
            if(index==504)record.putFloat(112,Float.NaN);
            if(index==505)steps=0;
            if(index==506)steps=65;
            if(index==507||index==509)distance=Float.NaN;
            boolean uniform=index==508||index==509;
            record.putFloat(128,(float)ox).putFloat(132,(float)oy).putFloat(136,(float)oz);
            record.putFloat(144,(float)dx).putFloat(148,(float)dy).putFloat(152,(float)dz).putFloat(160,distance).putInt(164,steps).putInt(168,uniform?-1:16);
            expectedSample[index]=sample(record.getFloat(112),record.getFloat(116),record.getFloat(120),dims);
            double[] origin=map(transforms[fixture],record,128,1);
            double[] direction=map(transforms[fixture],record,144,0);
            expectedMass[index]=uniform?distance:mass(origin,direction,distance,steps,dims);
        }
        double maximum=0;
        try(var runtime=CudaMediumRuntime.open(0);var kernel=runtime.compileBatchKernel(CudaMediumDensitySource.source()+PROBE,"density_probe");
            var storage=runtime.allocate(input.capacity()+32L);var arena=Arena.ofConfined()) {
            var host=arena.allocate(storage.byteSize(),16);host.fill((byte)0x5a);host.asByteBuffer().put(input.array());
            runtime.upload(storage.slice(0,storage.byteSize()),host);
            runtime.launchBlocking(kernel,storage.slice(0,input.capacity()),COUNT,BYTES);
            runtime.download(storage.slice(0,storage.byteSize()),host);
            ByteBuffer actual=host.asByteBuffer().order(ByteOrder.nativeOrder());
            for(int index=0;index<COUNT;index++) for(int lane=0;lane<2;lane++) {
                double reference=lane==0?expectedSample[index]:expectedMass[index];float value=actual.getFloat(index*BYTES+lane*4);
                if(Double.isNaN(reference)) {if(!Float.isNaN(value))throw new AssertionError("invalid density input was silently accepted: index="+index+" lane="+lane);continue;}
                double error=Math.abs(value-reference)/Math.max(1,Math.abs(reference));maximum=Math.max(maximum,error);
                if(!Float.isFinite(value)||error>2e-6)throw new AssertionError("density index="+index+" lane="+lane+" actual="+value+" expected="+reference);
            }
            for(int i=input.capacity();i<host.byteSize();i++) if(actual.get(i)!=(byte)0x5a)throw new AssertionError("density guard overwritten");
        }
        System.out.printf(Locale.ROOT,"CudaMediumDensityNativeSelfTest passed: cases=%d irregular/degenerate grids+affine clipped-midpoint-mass maxScaledError=%.9g%n",COUNT,maximum);
    }
    private static double[] map(AffineTransform transform,ByteBuffer record,int at,int w) {
        double[] result=new double[3];
        for(int row=0;row<3;row++) {
            result[row]=transform.element(row*4+3)*w;
            for(int column=0;column<3;column++)result[row]+=transform.element(row*4+column)*record.getFloat(at+column*4);
        }
        return result;
    }
    private static double sample(double x,double y,double z,int[] dims) {
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))return Double.NaN;
        if(x<0||x>1||y<0||y>1||z<0||z>1)return 0;
        double[] coordinate={x*dims[0]-.5,y*dims[1]-.5,z*dims[2]-.5};int[] cell=new int[3];double[] f=new double[3];
        for(int axis=0;axis<3;axis++) {cell[axis]=(int)Math.floor(coordinate[axis]);f[axis]=coordinate[axis]-cell[axis];}
        double sum=0;
        for(int k=0;k<2;k++)for(int j=0;j<2;j++)for(int i=0;i<2;i++) {
            int ix=Math.clamp(cell[0]+i,0,dims[0]-1),iy=Math.clamp(cell[1]+j,0,dims[1]-1),iz=Math.clamp(cell[2]+k,0,dims[2]-1);
            sum+=(float)(RED[ix+dims[0]*iy+dims[0]*dims[1]*iz]/255f)*(i==0?1-f[0]:f[0])*(j==0?1-f[1]:f[1])*(k==0?1-f[2]:f[2]);
        }
        return sum;
    }
    private static double mass(double[] origin,double[] direction,double length,int steps,int[] dims) {
        if(!Double.isFinite(length)||length<0)return Double.NaN;
        if(length==0)return 0;
        if(steps<1||steps>64)return Double.NaN;
        double first=0,last=length;
        for(int axis=0;axis<3;axis++) {
            if(direction[axis]==0) {if(origin[axis]<0||origin[axis]>1)return 0;continue;}
            double a=-origin[axis]/direction[axis],b=(1-origin[axis])/direction[axis];
            first=Math.max(first,Math.min(a,b));last=Math.min(last,Math.max(a,b));
            if(last<=first)return 0;
        }
        double dt=(last-first)/steps,total=0;
        for(int i=0;i<steps;i++) {double t=first+(i+.5)*dt;total+=sample(origin[0]+direction[0]*t,origin[1]+direction[1]*t,origin[2]+direction[2]*t,dims);}
        return total*dt;
    }
}
