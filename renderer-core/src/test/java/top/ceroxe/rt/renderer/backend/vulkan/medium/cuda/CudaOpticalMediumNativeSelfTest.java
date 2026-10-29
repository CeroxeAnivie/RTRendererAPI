package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.lang.foreign.Arena;
import static java.lang.foreign.ValueLayout.*;

/** Real CUDA checks against independent double Fresnel/Snell references; also compiles the controller. */
public final class CudaOpticalMediumNativeSelfTest {
    private static final String PROBE="""
        extern "C" __global__ void optical_glass_probe(float *records,int count){
            int i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=count)return;float*r=records+i*16;
            float3 incident=make_float3(sqrtf(fmaxf(0.f,1.f-r[2]*r[2])),0,-r[2]),rd,td,rw,tw;float f;
            bool good=rtr_optical::glass(incident,make_float3(0,0,1),r[0],r[1],WHITE,&rd,&td,&rw,&tw,&f);
            r[4]=f;r[5]=rw.x;r[6]=tw.x;r[7]=td.x;r[8]=td.z;r[9]=rd.z;r[10]=good;
        }
        extern "C" __global__ void optical_rough_glass_probe(float *records,int count){
            int i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=count)return;float*r=records+i*16;
            float3 fixed=make_float3(sqrtf(fmaxf(0.f,1.f-r[2]*r[2])),0,r[2]),direction,weight;
            bool good=rtr_roughglass::sample(fixed,make_float3(r[7]),r[3],r[0],r[1],r[4],r[5],r[6]!=0,&direction,&weight);
            r[8]=direction.x;r[9]=direction.y;r[10]=direction.z;r[11]=weight.x;r[12]=good;
        }
        extern "C" __global__ void optical_hit_layout_probe(unsigned char*b,int count){
            int i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=count)return;
            unsigned int*h=(unsigned int*)b,*output=(unsigned int*)(b+h[11])+i*24;
            const unsigned int full[24]={0,1,2,3,12,13,14,15,16,17,18,19,24,25,28,29,44,45,46,47,48,49,50,51};
            for(unsigned int k=0;k<24;k++){
                if((h[47]&9u)==9u){rtr_optical::HitView<true,true> view={(const unsigned int*)(b+h[10]),h[2],(unsigned int)i};output[k]=view.u(k);}
                else if(h[47]&1u){rtr_optical::HitView<true,false> view={(const unsigned int*)(b+h[10]),h[2],(unsigned int)i};output[k]=view.u(k);}
                else{rtr_optical::HitView<false,false> view={(const unsigned int*)(b+h[10]),h[2],(unsigned int)i};output[k]=view.u(full[k]);}
            }
        }
        extern "C" __global__ void optical_counter_probe(unsigned char*b,int count){
            int i=blockIdx.x*blockDim.x+threadIdx.x;
            bool reduced=((unsigned int*)b)[46]!=0u;
            unsigned int statistics[4]={0u,0u,0u,0u};
            if(i<count&&(i&7)!=0){
                rtr_optical::increment(b,reduced?statistics:nullptr,i&3);
                if(i%3==0)rtr_optical::increment(b,reduced?statistics:nullptr,2u);
            }
            if(reduced)rtr_optical::reduceStatistics(b,statistics);
        }
        template<bool Transposed> __device__ void stateLayoutProbe(unsigned char*b,int i,int count){
            rtr_optical::StateAccess<true,Transposed> access(b,i,count);auto*s=access.operator->();
            float*output=(float*)(b+((unsigned int*)b)[11])+i*28;
            float4 vectors[5]={s->origin,s->direction,s->throughput,s->radiance,s->terminal};
            for(int k=0;k<5;k++){output[k*4]=vectors[k].x;output[k*4+1]=vectors[k].y;output[k*4+2]=vectors[k].z;output[k*4+3]=vectors[k].w;}
            unsigned int*u=(unsigned int*)output;u[20]=s->control.x;u[21]=s->control.y;u[22]=s->control.z;u[23]=s->control.w;
            u[24]=s->counters.x;u[25]=s->counters.y;u[26]=s->counters.z;u[27]=s->counters.w;
            s->origin=make_float4(i+.25f,i+.5f,i+.75f,i+1.f);s->control.w+=7u;s->counters.z=1u;
            s->throughput.y+=2.f;s->radiance.z+=3.f;s->terminal.x+=4.f;
        }
        extern "C" __global__ void optical_state_layout_probe(unsigned char*b,int count){
            int i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=count)return;
            if(((unsigned int*)b)[47]&16u)stateLayoutProbe<true>(b,i,count);else stateLayoutProbe<false>(b,i,count);
        }
        """;
    public static void main(String[] args){
        float[][] cases={{1,1.5f,1},{1.5f,1,1},{1.5f,1,.5f},{1,1,1},{1,1,.1f},{1,1.33f,.3f},{1.7f,1.2f,.9f},{1,1.5f,.000001f}};
        try(var cuda=CudaMediumRuntime.open(0);var arena=Arena.ofConfined()){
            try(var controller=cuda.compileBatchKernel(CudaOpticalMediumSource.source(),"native_optical_step")){
                if(java.util.Arrays.asList(args).contains("--compile-only")){System.out.println("CudaOpticalMediumNativeSelfTest compile-only passed");return;}
            }
            try(var kernel=cuda.compileBatchKernel(CudaOpticalMediumSource.source()+"\n"+PROBE,"optical_glass_probe");
                var records=cuda.allocate(cases.length*64L)){
                var host=arena.allocate(cases.length*64L,16);
                for(int i=0;i<cases.length;i++)for(int j=0;j<3;j++)host.set(JAVA_FLOAT,i*64L+j*4,cases[i][j]);
                cuda.upload(records.slice(0,records.byteSize()),host);cuda.launchBlocking(kernel,records.slice(0,records.byteSize()),cases.length,64);
                cuda.download(records.slice(0,records.byteSize()),host);
                for(int i=0;i<cases.length;i++){
                    double n1=cases[i][0],n2=cases[i][1],ci=cases[i][2],eta=n1/n2,st=eta*Math.sqrt(Math.max(0,1-ci*ci));
                    double ct=st>=1?0:Math.sqrt(Math.max(0,1-st*st));
                    double f=n1==n2?0:st>=1?1:.5*(Math.pow((n1*ci-n2*ct)/(n1*ci+n2*ct),2)+Math.pow((n2*ci-n1*ct)/(n2*ci+n1*ct),2));
                    double trans=st>=1?0:(1-f)*eta*eta;
                    near(host.get(JAVA_FLOAT,i*64L+16),f,3e-5,"Fresnel case "+i);
                    near(host.get(JAVA_FLOAT,i*64L+24),trans,3e-5,"radiance eta squared case "+i);
                    if(host.get(JAVA_FLOAT,i*64L+40)!=1)throw new AssertionError("nonfinite glass probe "+i);
                    if(st<1){near(host.get(JAVA_FLOAT,i*64L+28),st,3e-5,"Snell tangent case "+i);near(host.get(JAVA_FLOAT,i*64L+32),-ct,3e-5,"Snell normal case "+i);}
                }
            }
            verifyRoughGlass(cuda,arena);
            verifyCounterReduction(cuda,arena);
            verifyHitLayouts(cuda,arena);
            verifyStateLayouts(cuda,arena);
        }
        System.out.println("CudaOpticalMediumNativeSelfTest passed: pinned smooth/rough glass, independent double directions/energy, eta squared, black tint and TIR");
    }
    private static void verifyStateLayouts(CudaMediumRuntime cuda,Arena arena) {
        int[] fields={0,1,2,3,4,5,6,7,12,13,14,15,16,17,18,19,24,25,26,27,8,9,10,11,20,21,22,23};
        try(var kernel=cuda.compileBatchKernel(CudaOpticalMediumSource.source()+"\n"+PROBE,"optical_state_layout_probe")) {
            for(int count:new int[]{1,31,32,33,255,256,257,1069})for(boolean transposed:new boolean[]{false,true}) {
                int output=384+count*112,bytes=output+count*112+16;
                try(var records=cuda.allocate(bytes)) {
                    var host=arena.allocate(bytes,16);host.fill((byte)0x5a);
                    host.set(JAVA_INT,8,count);host.set(JAVA_INT,36,384);host.set(JAVA_INT,44,output);host.set(JAVA_INT,188,transposed?18:2);
                    for(int i=0;i<count;i++)for(int k=0;k<28;k++)
                        host.set(JAVA_FLOAT,384+(transposed?k*(long)count+i:i*28L+k)*4,i*32f+k+.125f);
                    cuda.upload(records.slice(0,bytes),host);cuda.launchBlocking(kernel,records.slice(0,bytes),count,1);
                    cuda.download(records.slice(0,bytes),host);
                    for(int i=0;i<count;i++)for(int k=0;k<28;k++) {
                        float expected=i*32f+fields[k]+.125f;
                        near(host.get(JAVA_FLOAT,output+(i*28L+k)*4),expected,0,"state read count="+count+" transposed="+transposed+" field="+k);
                    }
                    for(int i=0;i<count;i++)for(int k=0;k<28;k++) {
                        float expected=i*32f+k+.125f;
                        int bits=Float.floatToRawIntBits(expected);
                        if(k<4)bits=Float.floatToRawIntBits(i+(k+1)*.25f);
                        else if(k==11)bits+=7;else if(k==22)bits=1;
                        else if(k==13)bits=Float.floatToRawIntBits(expected+2);
                        else if(k==18)bits=Float.floatToRawIntBits(expected+3);
                        else if(k==24)bits=Float.floatToRawIntBits(expected+4);
                        long at=384+(transposed?k*(long)count+i:i*28L+k)*4;
                        if(host.get(JAVA_INT,at)!=bits)throw new AssertionError("state write mismatch count="+count+" field="+k);
                    }
                    for(int k=0;k<4;k++)if(host.get(JAVA_INT,bytes-16+k*4L)!=0x5a5a5a5a)throw new AssertionError("state tail guard overwritten");
                }
            }
        }
    }
    private static void verifyHitLayouts(CudaMediumRuntime cuda,Arena arena) {
        int[] full={0,1,2,3,12,13,14,15,16,17,18,19,24,25,28,29,44,45,46,47,48,49,50,51};
        try(var kernel=cuda.compileBatchKernel(CudaOpticalMediumSource.source()+"\n"+PROBE,"optical_hit_layout_probe")) {
            for(int count:new int[]{1,31,32,33,255,256,257,1069})for(int flags:new int[]{0,1,9}) {
                int hitBytes=flags==0?208:96,output=384+count*hitBytes,bytes=output+count*96+16;
                try(var records=cuda.allocate(bytes)) {
                    var host=arena.allocate(bytes,16);host.fill((byte)0x5a);
                    host.set(JAVA_INT,8,count);host.set(JAVA_INT,40,384);host.set(JAVA_INT,44,output);host.set(JAVA_INT,188,flags);
                    for(int i=0;i<count;i++)for(int k=0;k<24;k++){
                        int word=flags==9?(k/4)*count*4+i*4+k%4:i*(hitBytes/4)+(flags==0?full[k]:k);
                        host.set(JAVA_INT,384+word*4L,0x3f000000+i*64+k);
                    }
                    cuda.upload(records.slice(0,bytes),host);cuda.launchBlocking(kernel,records.slice(0,bytes),count,1);
                    cuda.download(records.slice(0,bytes),host);
                    for(int i=0;i<count;i++)for(int k=0;k<24;k++)
                        if(host.get(JAVA_INT,output+(i*24L+k)*4)!=0x3f000000+i*64+k)
                            throw new AssertionError("hit layout field mismatch count="+count+" flags="+flags+" path="+i+" field="+k);
                    for(int k=0;k<4;k++)if(host.get(JAVA_INT,bytes-16+k*4L)!=0x5a5a5a5a)
                        throw new AssertionError("hit layout tail guard overwritten");
                }
            }
        }
    }
    private static void verifyCounterReduction(CudaMediumRuntime cuda,Arena arena) {
        try(var kernel=cuda.compileBatchKernel(CudaOpticalMediumSource.source()+"\n"+PROBE,"optical_counter_probe")) {
            for(int count:new int[]{1,31,32,33,255,256,257,1069}) {
                int bytes=Math.max(384,count);int[] expected=new int[4];
                for(int i=0;i<count;i++)if((i&7)!=0){expected[i&3]++;if(i%3==0)expected[2]++;}
                try(var records=cuda.allocate(bytes)) {
                    var host=arena.allocate(bytes,16);
                    for(boolean reduced:new boolean[]{false,true}) {
                        host.fill((byte)0);host.set(JAVA_INT,184,reduced?1:0);
                        cuda.upload(records.slice(0,bytes),host);
                        cuda.launchBlocking(kernel,records.slice(0,bytes),count,1);
                        cuda.download(records.slice(0,bytes),host);
                        for(int k=0;k<4;k++)if(host.get(JAVA_INT,112+k*4L)!=expected[k])
                            throw new AssertionError("optical counters: count="+count+" reduced="+reduced+" counter="+k);
                    }
                }
            }
        }
    }
    private static void verifyRoughGlass(CudaMediumRuntime cuda,Arena arena) {
        var cases=new java.util.ArrayList<float[]>();
        for(float roughness:new float[]{.0001f,.15f,.5f,.9f})for(float[] indices:new float[][]{{1,1.5f},{1.5f,1}})
            for(float cosine:new float[]{1,.75f,.2f})for(float u:new float[]{.01f,.3f,.99f})
                for(int reflect=0;reflect<2;reflect++)for(float tint:new float[]{0,.25f})
                    cases.add(new float[]{indices[0],indices[1],cosine,roughness,u,.371f,reflect,tint});
        try(var kernel=cuda.compileBatchKernel(CudaOpticalMediumSource.source()+"\n"+PROBE,"optical_rough_glass_probe");
            var records=cuda.allocate(cases.size()*64L)) {
            var host=arena.allocate(cases.size()*64L,16);
            for(int i=0;i<cases.size();i++)for(int j=0;j<8;j++)host.set(JAVA_FLOAT,i*64L+j*4,cases.get(i)[j]);
            cuda.upload(records.slice(0,records.byteSize()),host);cuda.launchBlocking(kernel,records.slice(0,records.byteSize()),cases.size(),64);
            cuda.download(records.slice(0,records.byteSize()),host);
            for(int i=0;i<cases.size();i++) {
                var values=cases.get(i);double[] expected=roughReference(values);
                if(host.get(JAVA_FLOAT,i*64L+48)!=1)throw new AssertionError("nonfinite rough glass sample "+i);
                near(host.get(JAVA_FLOAT,i*64L+44),expected[3],Math.max(3e-5,Math.abs(expected[3])*5e-4),"rough glass radiance weight "+i);
                if(expected[3]>1e-8)for(int c=0;c<3;c++)near(host.get(JAVA_FLOAT,i*64L+32+c*4),expected[c],2e-4,"rough glass Snell/reflection direction "+i);
                if(values[2]==1&&values[6]!=0&&halfCosineSquared(values[3],values[4])==1) {
                    near(host.get(JAVA_FLOAT,i*64L+32),0,0,"quantized smooth reflection tangent x "+i);
                    near(host.get(JAVA_FLOAT,i*64L+36),0,0,"quantized smooth reflection tangent y "+i);
                    near(host.get(JAVA_FLOAT,i*64L+40),1,0,"quantized smooth reflection normal "+i);
                }
            }
        }
    }
    // Schlick's normal distribution stores cos^2(theta) in float before taking its square roots.
    // For roughness=.0001 and u=.3 this is exactly 1; a double-only normal instead invents a
    // tangent of about 1.5e-4 that the pinned sampler cannot represent. Preserve that documented
    // scalar quantization; ray geometry, dielectric energy and directions remain double references.
    private static float halfCosineSquared(float roughness,float sample) {
        float alpha=roughness*roughness;
        return sample/(alpha*(1-sample)+sample);
    }
    private static double[] roughReference(float[] r) {
        double n1=r[0],n2=r[1],ci=r[2],rough=r[3]*r[3],phi=2*Math.PI*r[5];
        double cosSquared=halfCosineSquared(r[3],r[4]);
        double hz=Math.sqrt(cosSquared),hs=Math.sqrt(Math.max(0,1-cosSquared));
        double hx=hs*Math.cos(phi),hy=hs*Math.sin(phi),fx=Math.sqrt(Math.max(0,1-ci*ci)),dot=fx*hx+ci*hz;
        boolean reflection=r[6]!=0;double x,y,z,factor;
        if(reflection) {
            x=2*dot*hx-fx;y=2*dot*hy;z=2*dot*hz-ci;
            if(Math.abs(z)<1e-6||z<0)return new double[4];
            factor=fresnel(n1,n2,dot);
        }else {
            double eta=n1/n2,sine2=eta*eta*Math.max(0,1-dot*dot);
            if(sine2>=1)return new double[4];
            double ct=Math.sqrt(1-sine2),length=eta*dot-ct;
            x=length*hx-eta*fx;y=length*hy;z=length*hz-eta*ci;
            factor=(1-fresnel(n2,n1,ct))*eta*eta*r[7];
        }
        double masking=ci/(ci*(1-rough)+rough)*Math.abs(z)/(Math.abs(z)*(1-rough)+rough);
        return new double[]{x,y,z,2*masking*Math.abs(dot)/ci*factor};
    }
    private static double fresnel(double n1,double n2,double cosine) {
        if(cosine<0)return fresnel(n2,n1,-cosine);
        double sine2=Math.pow(n1/n2,2)*Math.max(0,1-cosine*cosine);
        if(sine2>=1)return 1;
        double ct=Math.sqrt(1-sine2),rs=(n1*cosine-n2*ct)/(n1*cosine+n2*ct),rp=(n2*cosine-n1*ct)/(n2*cosine+n1*ct);
        return .5*(rs*rs+rp*rp);
    }
    private static void near(double value,double expected,double tolerance,String why){if(!Double.isFinite(value)||Math.abs(value-expected)>tolerance)throw new AssertionError(why+" actual="+value+" expected="+expected);}
}
