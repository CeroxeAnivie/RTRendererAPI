package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static java.lang.foreign.ValueLayout.*;

/** Real CUDA numerical fixtures for photon importance, strict stack failure and density gather. */
public final class CudaOpticalCausticNativeSelfTest {
    private static final String PROBE="""
        extern "C" __global__ void photon_importance_probe(float*r,int count){
            using namespace rtr_optical;float3 rd,td,rw,tw;float f;
            bool ok=glass(make_float3(0,0,-1),make_float3(0,0,1),1,1.5f,make_float3(.8f,.6f,.4f),&rd,&td,&rw,&tw,&f);
            r[0]=f;r[1]=tw.x*2.25f/(1.f-f);r[2]=tw.y*2.25f/(1.f-f);r[3]=ok?1:0;
            r[4]=rtr_optical_caustic::volumeKernel(0.f,2.f);
        }
        extern "C" __global__ void photon_deposit_probe(unsigned char*m,int count){
            unsigned int i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=(unsigned int)count)return;
            using namespace rtr_optical_caustic;unsigned int*h=(unsigned int*)m;PhotonState*s=(PhotonState*)(m+h[4]);
            s->metadata=make_uint4(0,1,0,0);
            deposit(m,s,0,make_float3(0),make_float3(0,0,1),make_float3(2,3,4),make_float3(0,0,1),7,0);
            deposit(m,s,0,make_float3(0),make_float3(0,0,-1),make_float3(5,6,7),make_float3(0),9,1);
        }
        extern "C" __global__ void photon_segment_probe(unsigned char*b,int count){
            using namespace rtr_optical;State<false> s={};rtr_medium::PathVolumeInfo_Init(&s.volumes);
            s.direction=make_float4(0,0,1,0);s.throughput=make_float4(1,1,1,30);s.terminal=make_float4(1,1,1,1);
            float3 incident;bool collision=false;bool ok=segment(b,&s,make_float3(0),30,&collision,true,&incident);
            float*out=(float*)(b+464);out[0]=ok&&collision?1:0;out[1]=incident.x;out[2]=incident.y;out[3]=incident.z;
            out[4]=s.throughput.x;out[5]=s.throughput.y;out[6]=s.throughput.z;out[7]=s.origin.z+s.throughput.w;
        }
        """;
    public static void main(String[] args){
        try(var cuda=CudaMediumRuntime.open(0);var a=Arena.ofConfined()){
            String source=CudaOpticalCausticSource.source();
            try(var compiled=cuda.compileBatchKernel(source,"native_optical_photon_step")){
                if(java.util.Arrays.asList(args).contains("--compile-only")){System.out.println("CudaOpticalCausticNativeSelfTest compile passed");return;}
            }
            try(var k=cuda.compileBatchKernel(source+"\n"+PROBE,"photon_importance_probe");var r=cuda.allocate(32)){
                var h=a.allocate(32,16);cuda.launchBlocking(k,r.slice(0,32),1,1);cuda.download(r.slice(0,32),h);
                near(h.get(JAVA_FLOAT,0),.04,1e-6,"normal Fresnel");
                near(h.get(JAVA_FLOAT,4),.8,2e-6,"importance red eta cancellation");
                near(h.get(JAVA_FLOAT,8),.6,2e-6,"importance green eta cancellation");
                near(h.get(JAVA_FLOAT,12),1,0,"valid glass");
                near(h.get(JAVA_FLOAT,16),15/(64*Math.PI),1e-7,"normalized sphere kernel");
            }
            try(var k=cuda.compileBatchKernel(source+"\n"+PROBE,"photon_segment_probe");var r=cuda.allocate(512)) {
                var h=a.allocate(512,16);
                for(boolean density:new boolean[]{false,true}) {
                    h.fill((byte)0);
                    for(int c=0;c<3;c++){h.set(JAVA_FLOAT,80+c*4,1.5f);h.set(JAVA_FLOAT,160+c*4,.5f);}
                    h.set(JAVA_INT,152,density?384:-1);h.set(JAVA_INT,156,64);
                    h.set(JAVA_INT,384,448);h.set(JAVA_INT,388,1);h.set(JAVA_INT,392,1);h.set(JAVA_INT,396,1);
                    h.set(JAVA_FLOAT,412,.5f);h.set(JAVA_FLOAT,428,.5f);h.set(JAVA_FLOAT,444,.5f);h.set(JAVA_FLOAT,448,2);
                    cuda.upload(r.slice(0,512),h);cuda.launchBlocking(k,r.slice(0,512),1,1);cuda.download(r.slice(0,512),h);
                    near(h.get(JAVA_FLOAT,464),1,0,"photon segment collision "+density);
                    for(int c=0;c<3;c++) {
                        near(h.get(JAVA_FLOAT,468+c*4),density?.25:.5,1e-6,"incident fluence excludes sigmaS "+density);
                        near(h.get(JAVA_FLOAT,480+c*4),.25,1e-6,"continued scattering flux "+density);
                    }
                    near(h.get(JAVA_FLOAT,492),30,2e-6,"photon distance deducted once "+density);
                }
            }
            var c=top.ceroxe.rt.renderer.api.Caustics.photonMap(0,0,0,4,1,1).withMaxEvents(2);
            var p=CudaOpticalCausticAbi.plan(1,2,2,1024*1024);
            try(var m=cuda.allocate(p.bytes());var q=cuda.allocate(2*80);
                var clear=cuda.compileBatchKernel(source,"native_optical_photon_clear");
                var dep=cuda.compileBatchKernel(source+"\n"+PROBE,"photon_deposit_probe");
                var gather=cuda.compileBatchKernel(source,"native_optical_photon_gather")){
                var host=a.allocate(p.bytes(),16);host.fill((byte)0);
                host.asByteBuffer().put(CudaOpticalCausticAbi.header(p,c,2,1));
                CudaOpticalCausticAbi.bindCudaGather(host.asByteBuffer(),q.slice(0,160).deviceAddress());
                cuda.upload(m.slice(0,p.bytes()),host);cuda.launchBlocking(clear,m.slice(0,p.bytes()),p.hashCapacity(),1);
                var diagnostic=a.allocate(128,16);
                cuda.download(m.slice(p.headsOffset(),p.hashCapacity()*4L),diagnostic.asSlice(0,p.hashCapacity()*4L));
                for(int i=0;i<p.hashCapacity();i++)if(diagnostic.get(JAVA_INT,i*4L)!=-1)
                    throw new AssertionError("photon hash clear failed at bucket "+i);
                cuda.launchBlocking(dep,m.slice(0,p.bytes()),1,1);
                cuda.download(m.slice(p.photonOffset(),CudaOpticalCausticAbi.PHOTON_BYTES*2L),diagnostic);
                int firstNext=diagnostic.get(JAVA_INT,12),secondNext=diagnostic.get(JAVA_INT,64+12);
                if(firstNext!=-1||secondNext!=0)
                    throw new AssertionError("photon deposit linked-list sentinel mismatch: first="+firstNext+", second="
                            +secondNext+", photonOffset="+p.photonOffset()+", headsOffset="+p.headsOffset()+", hashCapacity="
                            +p.hashCapacity());
                var queries=a.allocate(160,16);queries.fill((byte)0);
                queries.set(JAVA_INT,12,0);queries.set(JAVA_FLOAT,24,1);queries.set(JAVA_INT,28,7);
                queries.set(JAVA_FLOAT,40,1);queries.set(JAVA_FLOAT,48,1);queries.set(JAVA_FLOAT,52,1);queries.set(JAVA_FLOAT,56,1);
                queries.set(JAVA_INT,92,1);queries.set(JAVA_FLOAT,104,1);queries.set(JAVA_INT,108,9);queries.set(JAVA_FLOAT,140,0);
                cuda.upload(q.slice(0,160),queries);System.out.println("optical caustic gather launch");
                cuda.launchBlocking(gather,m.slice(0,p.bytes()),2,1);
                cuda.download(q.slice(0,160),queries);
                near(queries.get(JAVA_FLOAT,64),4/(Math.PI*Math.PI),1e-6,"surface normalized photon density");
                near(queries.get(JAVA_FLOAT,68),6/(Math.PI*Math.PI),1e-6,"surface green density");
                near(queries.get(JAVA_FLOAT,144),5*15/(32*Math.PI*Math.PI),1e-6,"isotropic volume density");
            }
        }
        System.out.println("CudaOpticalCausticNativeSelfTest passed: importance eta, normalized surface/volume kernel, strict photon source compilation");
    }
    private static void near(double actual,double expected,double tolerance,String label){
        if(!Double.isFinite(actual)||Math.abs(actual-expected)>tolerance)throw new AssertionError(label+" actual="+actual+" expected="+expected);
    }
}
