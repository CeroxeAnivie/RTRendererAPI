package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;

/** Exercises the exact production spawn helper with runtime CUDA inputs. */
public final class CudaOpticalScatterOriginNativeSelfTest {
    public static void main(String[] args) {
        String optical=CudaOpticalMediumSource.source();
        int first=optical.indexOf("__device__ float3 spawnOriginBound(");
        int end=optical.indexOf("template<bool Small> __device__ State",first);
        if(first<0||end<0)throw new AssertionError("production spawn helper missing");
        String source="\nnamespace rtr_spawn_probe {\n"+optical.substring(first,end)+"\n}\n"+"""
                extern "C" __global__ void scatter_origin_probe(unsigned char*b,int count){
                    int i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=count)return;
                    float4*v=(float4*)b+i*5;
                    float3 origin=make_float3(v[0].x,v[0].y,v[0].z),direction=make_float3(v[1].x,v[1].y,v[1].z);
                    float3 endpoint=make_float3(v[2].x,v[2].y,v[2].z),normal=make_float3(v[3].x,v[3].y,v[3].z);
                    float3 result=rtr_spawn_probe::scatterOrigin(origin,direction,v[2].w,v[3].w,
                            v[3].w>0?&endpoint:nullptr,v[3].w>0?&normal:nullptr);
                    v[4]=make_float4(result.x,result.y,result.z,0);
                }
                """;
        try(var cuda=CudaMediumRuntime.open(0);var arena=Arena.ofConfined();
                var kernel=cuda.compileBatchKernel(source,"scatter_origin_probe");var memory=cuda.allocate(3*80)){
            var data=arena.allocate(3*80,16);
            for(int i=0;i<3;i++){
                float sign=i==1?-1:1;
                float[] values={sign*-8.28460884f,-2.41656017f,.799984932f,0,
                        sign*-.430513382f,-.125577614f,-.893805623f,0,
                        sign*-8.30000019f,-2.42104959f,.768030763f,i==2?.01f:.0357505418f,
                        sign,0,0,.0357507206f,0,0,0,0};
                for(int j=0;j<values.length;j++)data.set(ValueLayout.JAVA_FLOAT,i*80L+j*4L,values[j]);
            }
            cuda.upload(memory.slice(0,data.byteSize()),data);
            cuda.launchBlocking(kernel,memory.slice(0,data.byteSize()),3,1);
            cuda.download(memory.slice(0,data.byteSize()),data);
            for(int i=0;i<3;i++){
                float x=data.get(ValueLayout.JAVA_FLOAT,i*80L+64);
                float sign=i==1?-1:1;
                double plane=-7.5-(double).8f;
                double exact=(double)-8.28460884f+(double)-.430513382f*(i==2?.01f:.0357505418f);
                if(!Float.isFinite(x)||sign*x<plane||Math.abs(sign*x-exact)>1e-4)
                    throw new AssertionError("scatter origin left the incident medium: case="+i+" x="+x+" exact="+exact+" plane="+plane);
                if(i==2&&x!=Math.fma(-.430513382f,.01f,-8.28460884f))
                    throw new AssertionError("interior scatter point changed");
                System.out.println("Scatter origin case="+i+" x="+x+" exact="+(sign*exact));
            }
        }
        System.out.println("CudaOpticalScatterOriginNativeSelfTest passed: grazing boundary, mirror and unchanged interior");
    }
}
