package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.lang.foreign.Arena;
import static java.lang.foreign.ValueLayout.*;

/** Real CUDA checks against independent double Fresnel/Snell references; also compiles the controller. */
public final class CudaOpticalMediumNativeSelfTest {
    private static final String PROBE="""
        extern "C" __global__ void optical_metal_probe(float *records,int count){
            int i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=count)return;float*r=records+i*16;
            rtr_optical::Connection c={};
            c.surfacePoint=make_float4(0,0,0,r[0]);c.surfaceNormal=make_float4(0,0,1,r[3]);
            c.surfaceView=make_float4(sqrtf(fmaxf(0.f,1.f-r[2]*r[2])),0,r[2],r[4]);
            c.surfaceMaterial=make_float4(r[5],r[6],r[7],r[1]);
            float3 out;const float3 weight=rtr_optical::metalReflection(&c,&out);
            r[8]=out.x;r[9]=out.y;r[10]=out.z;r[11]=weight.x;r[12]=weight.y;r[13]=weight.z;
        }
        extern "C" __global__ void optical_spawn_probe(float *records,int count){
            int i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=count)return;float*r=records+i*16;
            const float3 point=make_float3(r[0],r[1],r[2]),normal=make_float3(r[3],r[4],r[5]);
            const float3 direction=make_float3(r[6],r[7],r[8]);
            const float3 result=rtr_optical::spawnOrigin(point,normal,direction);
            r[9]=result.x;r[10]=result.y;r[11]=result.z;
        }
        extern "C" __global__ void optical_spawn_bound_probe(float *r,int count){
            const float3 point=make_float3(-100.f,-48.83976f,.8987462f),normal=make_float3(1,0,0);
            const float3 direction=make_float3(-.32876092f,-.944412f,.0015058815f);
            const float3 error=rtr_optical::positionError(point,make_float3(1000,2000,3000),4000,direction);
            const float3 result=rtr_optical::spawnOrigin(point,normal,direction,error);
            r[0]=result.x;r[1]=result.y;r[2]=result.z;r[3]=error.x;
        }
        extern "C" __global__ void optical_terminal_spawn_bound_probe(float *r,int count){
            int i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=count)return;
            unsigned int header[96]={};header[6]=16u;
            float hit[52]={};hit[3]=6.f;hit[19]=1.f;
            rtr_optical::State<true> state={};state.origin=make_float4(0,0,6,0);
            state.direction=make_float4(0,0,-1,0);state.counters.x=i==0?0u:16u;
            rtr_optical::Connection c={};c.surfaceNormal=make_float4(0,0,1,.5f);
            rtr_optical::HitView<false,false> view={(const unsigned int*)hit,1u,0u};
            const int result=rtr_optical::prepareSurfaceContinuation<false>((unsigned char*)header,&state,view,&c,1.f);
            r[i*2]=c.surfaceNormal.w;r[i*2+1]=float(result);
        }
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
        extern "C" __global__ void optical_connection_probe(unsigned char*b,int count){
            if(blockIdx.x!=0||threadIdx.x!=0)return;
            using namespace rtr_optical;Connection*c=connections(b);
            HitView<false,false> hit={(const unsigned int*)(b+((unsigned int*)b)[10]),1u,0u};
            const int result=connectionStep(b,c,hit);
            ((int*)b)[17]=result;
        }
        """;
    public static void main(String[] args){
        if(java.util.Arrays.asList(args).contains("--active-volume-copies")){
            try(var cuda=CudaMediumRuntime.open(0);var arena=Arena.ofConfined()){
                verifyActiveVolumeCopies(cuda,arena);
            }
            System.out.println("CudaOpticalMediumNativeSelfTest active-volume copies passed: 136 cases, both copy modes, 5 mutation checkpoints");
            return;
        }
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
            verifyMetalReflection(cuda,arena);
            verifyCounterReduction(cuda,arena);
            verifyHitLayouts(cuda,arena);
            verifyStateLayouts(cuda,arena);
            verifyConnectionTransport(cuda,arena);
            verifyConnectionRayContinuity(cuda,arena);
            verifyGrazingSpawn(cuda,arena);
            try(var kernel=cuda.compileBatchKernel(CudaOpticalMediumSource.source()+"\n"+PROBE,"optical_terminal_spawn_bound_probe");
                var records=cuda.allocate(16)) {
                var host=arena.allocate(16,16);cuda.launchBlocking(kernel,records.slice(0,16),2,1);
                cuda.download(records.slice(0,16),host);
                for(int i=0;i<2;i++) {
                    near(host.get(JAVA_FLOAT,i*8L),12*4.172327e-7,1e-10,"terminal NEE spawn bound case "+i);
                    near(host.get(JAVA_FLOAT,i*8L+4),0,0,"terminal NEE continuation case "+i);
                }
            }
            try(var kernel=cuda.compileBatchKernel(CudaOpticalMediumSource.source()+"\n"+PROBE,"optical_spawn_bound_probe");var records=cuda.allocate(16)) {
                var host=arena.allocate(16,16);cuda.launchBlocking(kernel,records.slice(0,16),1,1);cuda.download(records.slice(0,16),host);
                double gamma=7*Math.scalb(1.0,-24)/(1-7*Math.scalb(1.0,-24));
                double bound=gamma*(100+1000+Math.abs(4000*-.32876092f));
                if(!(host.get(JAVA_FLOAT,0)<=-100-bound))throw new AssertionError("long-ray spawn did not leave the propagated position interval");
                near(host.get(JAVA_FLOAT,4),-48.83976f,0,"bounded spawn tangent y");near(host.get(JAVA_FLOAT,8),.8987462f,0,"bounded spawn tangent z");
            }
            verifyRoughTransmissionHemisphere(cuda,arena);
        }
        System.out.println("CudaOpticalMediumNativeSelfTest passed: smooth/rough glass, selected-medium light connections, independent attenuation, priority transitions and TIR");
    }
    private static void verifyActiveVolumeCopies(CudaMediumRuntime cuda,Arena arena) {
        final String probe="""
            extern "C" __global__ void optical_volume_copy_probe(unsigned int*out,int count){
                const int i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=count)return;
                const unsigned int n=i%34u;const bool reverse=(i/34u)&1u;
                rtr_medium::Material materials[33];
                for(int j=0;j<33;j++)materials[j].volume.priority=j%5-2;
                rtr_medium::PathVolumeInfo source;
                source.currentVolumeIndex=n?(reverse?31u:0u):NULL_INDEX;
                source.volumeIndexListSize=n;source.scatteredStart=i/68u;
                for(unsigned int j=0;j<32u;j++)source.volumeIndexList[j]=j<n?(reverse?31u-j:j):0xdeadbeefu;
                struct Guarded { unsigned int before[4];rtr_medium::PathVolumeInfo value;unsigned int after[4]; } destination;
                for(int j=0;j<4;j++)destination.before[j]=destination.after[j]=0x12345678u;
                for(int j=0;j<32;j++)destination.value.volumeIndexList[j]=0x5a5a5a5au;
                rtr_optical::copyVolumes(&destination.value,&source);
                unsigned int tailOk=1u;
            #ifdef RTR_OPTICAL_ACTIVE_VOLUME_COPIES
                for(unsigned int j=n;j<32u;j++)if(destination.value.volumeIndexList[j]!=0x5a5a5a5au)tailOk=0u;
            #endif
                for(unsigned int stage=0;stage<5u;stage++){
                    auto*p=&destination.value;unsigned int status=rtr_medium::validate(p,33u);
                    if(n<=32u){
                        if(stage==1u)status=rtr_medium::add(p,32u,materials,33u);
                        if(stage==2u)status=rtr_medium::remove(p,n?(reverse?31u:0u):31u,materials,33u);
                        if(stage==3u)while(p->volumeIndexListSize)status=rtr_medium::remove(p,p->volumeIndexList[0],materials,33u);
                        if(stage==4u)for(unsigned int j=0;j<3u;j++)status=rtr_medium::add(p,j==0u?31u:j==1u?2u:29u,materials,33u);
                    }
                    unsigned int*r=out+(i*5u+stage)*40u;
                    r[0]=p->currentVolumeIndex;r[1]=p->volumeIndexListSize;r[2]=p->scatteredStart;r[3]=status;
                    r[4]=tailOk;
                    for(int j=0;j<4;j++)if(destination.before[j]!=0x12345678u||destination.after[j]!=0x12345678u)r[4]=0u;
                    for(unsigned int j=0;j<32u;j++)r[5+j]=j<p->volumeIndexListSize?p->volumeIndexList[j]:NULL_INDEX;
                }
            }
            """;
        String property=CudaOpticalMediumSource.ACTIVE_VOLUME_COPIES_PROPERTY,previous=System.getProperty(property);
        try {
            for(boolean active:new boolean[]{false,true}) {
                System.setProperty(property,Boolean.toString(active));
                try(var kernel=cuda.compileBatchKernel(CudaOpticalMediumSource.source()+"\n"+probe,"optical_volume_copy_probe");
                        var records=cuda.allocate(136L*5*160)) {
                    var host=arena.allocate(records.byteSize(),16);
                    cuda.launchBlocking(kernel,records.slice(0,records.byteSize()),136,64);
                    cuda.download(records.slice(0,records.byteSize()),host);
                    for(int i=0;i<136;i++) {
                        int n=i%34;boolean reverse=((i/34)&1)!=0;
                        var live=new java.util.ArrayList<Integer>();
                        for(int j=0;j<Math.min(n,32);j++)live.add(reverse?31-j:j);
                        int current=live.isEmpty()?-1:live.getFirst();
                        for(int stage=0;stage<5;stage++) {
                            int status=n==33?4:0;
                            if(n<=32) {
                                if(stage==1){if(n==32)status=1;else{live.add(32);if(current==-1||32%5>=current%5)current=32;}}
                                if(stage==2){if(n==0)status=2;else{live.remove(Integer.valueOf(reverse?31:0));current=selectedVolume(live);}}
                                if(stage==3){live.clear();current=-1;}
                                if(stage==4){live.addAll(java.util.List.of(31,2,29));current=selectedVolume(live);}
                            }
                            long offset=(i*5L+stage)*160;
                            int[] expected={current,n==33?33:live.size(),i/68,status,1};
                            for(int j=0;j<expected.length;j++)if(host.get(JAVA_INT,offset+j*4)!=expected[j])
                                throw new AssertionError("volume copy mode="+active+" case="+i+" checkpoint="+stage+" field="+j);
                            for(int j=0;j<32;j++)if(host.get(JAVA_INT,offset+20+j*4)!=(j<live.size()?live.get(j):-1))
                                throw new AssertionError("volume copy changed live order: mode="+active+" case="+i+" checkpoint="+stage+" slot="+j);
                        }
                    }
                }
            }
        } finally {
            if(previous==null)System.clearProperty(property);else System.setProperty(property,previous);
        }
    }
    private static int selectedVolume(java.util.List<Integer> live) {
        int current=-1;for(int entry:live)if(current==-1||entry%5>=current%5)current=entry;return current;
    }
    private static void verifyMetalReflection(CudaMediumRuntime cuda,Arena arena) {
        var cases=new java.util.ArrayList<float[]>();
        for(float metal:new float[]{0,.2f,1})for(float roughness:new float[]{.025f,.35f,.9f})
            for(float cosine:new float[]{1,.7f,.2f})for(float[] sample:new float[][]{{.25f,.125f},{.7f,.8f},{.5f,0}})
                cases.add(new float[]{metal,roughness,cosine,sample[0],sample[1],.7f,.3f,.1f});
        try(var kernel=cuda.compileBatchKernel(CudaOpticalMediumSource.source()+"\n"+PROBE,"optical_metal_probe");
                var records=cuda.allocate(cases.size()*64L)) {
            var host=arena.allocate(cases.size()*64L,16);
            for(int i=0;i<cases.size();i++)for(int j=0;j<8;j++)host.set(JAVA_FLOAT,i*64L+j*4,cases.get(i)[j]);
            cuda.upload(records.slice(0,records.byteSize()),host);
            cuda.launchBlocking(kernel,records.slice(0,records.byteSize()),cases.size(),64);
            cuda.download(records.slice(0,records.byteSize()),host);
            for(int i=0;i<cases.size();i++) {
                double[] expected=metalReference(cases.get(i));
                for(int j=0;j<6;j++)near(host.get(JAVA_FLOAT,i*64L+(8+j)*4),expected[j],3e-5,
                        "metallic reflection f*cos/pdf case="+i+", component="+j);
            }
        }
    }
    private static double[] metalReference(float[] r) {
        if(r[0]<=1e-4)return new double[6];
        double alpha=Math.max(r[1]*r[1],.0025),a2=alpha*alpha,phi=2*Math.PI*r[3];
        double hz=Math.sqrt((1-r[4])/(1+(a2-1)*r[4])),sine=Math.sqrt(Math.max(0,1-hz*hz));
        double hx=Math.cos(phi)*sine,hy=Math.sin(phi)*sine,vx=Math.sqrt(1-r[2]*r[2]),vz=r[2];
        double vh=vx*hx+vz*hz;
        if(vh<=1e-5)return new double[6];
        double x=2*vh*hx-vx,y=2*vh*hy,z=2*vh*hz-vz;
        double[] result={x,y,z,0,0,0};
        if(z<=1e-5)return result;
        double k=(r[1]+1)*(r[1]+1)/8.0,geometry=vz/(vz*(1-k)+k)*z/(z*(1-k)+k);
        for(int c=0;c<3;c++) {
            double f0=.04*(1-r[0])+r[5+c]*r[0],f=f0+(1-f0)*Math.pow(1-vh,5);
            result[3+c]=r[0]*f*geometry*vh/Math.max(vz*Math.max(hz,1e-5),1e-5);
        }
        return result;
    }

    private static void verifyRoughTransmissionHemisphere(CudaMediumRuntime cuda,Arena arena) {
        final int count=16384;
        try(var kernel=cuda.compileBatchKernel(CudaOpticalMediumSource.source()+"\n"+PROBE,"optical_rough_glass_probe");
            var records=cuda.allocate(count*64L)) {
            var host=arena.allocate(count*64L,16);host.fill((byte)0);
            for(int i=0;i<count;i++) {long at=i*64L;
                host.set(JAVA_FLOAT,at,1.5f);host.set(JAVA_FLOAT,at+4,1);
                host.set(JAVA_FLOAT,at+8,.05f+(i%8)*.1f);host.set(JAVA_FLOAT,at+12,.35f);
                host.set(JAVA_FLOAT,at+16,((i/8)%128+.5f)/128);host.set(JAVA_FLOAT,at+20,((i/1024)+.5f)/16);
                host.set(JAVA_FLOAT,at+24,0);host.set(JAVA_FLOAT,at+28,1);
            }
            cuda.upload(records.slice(0,records.byteSize()),host);cuda.launchBlocking(kernel,records.slice(0,records.byteSize()),count,1);
            cuda.download(records.slice(0,records.byteSize()),host);int valid=0;
            for(int i=0;i<count;i++) {long at=i*64L;float weight=host.get(JAVA_FLOAT,at+44),z=host.get(JAVA_FLOAT,at+40);
                if(host.get(JAVA_FLOAT,at+48)!=1)throw new AssertionError("rough transmission sampler invalid case "+i);
                if(weight>0){valid++;if(!(z<0))throw new AssertionError("rough transmission retained reflected-side sample case "+i+" z="+z);}
            }
            if(valid==0)throw new AssertionError("rough transmission hemisphere fixture accepted no real samples");
        }
    }
    private static void verifyGrazingSpawn(CudaMediumRuntime cuda,Arena arena) {
        // The production failure kept an entering path at z=-2.0000002 with the
        // volume already in its stack; a grazing direction epsilon rounds back outside.
        float[][] cases={
                {80.06746f,-66.16144f,-2.0000002f,0,0,-1,.99999f,0,.0001f},
                {80.06746f,-66.16144f,-2.0000002f,0,0,-1,.99999f,0,-.0001f},
                {80.06746f,-66.16144f,2.0000002f,0,0,1,.99999f,0,-.0001f},
                {80.06746f,-66.16144f,2.0000002f,0,0,1,.99999f,0,.0001f},
                {-2.0000002f,80.06746f,-66.16144f,-1,0,0,.0001f,.99999f,0},
                {2.0000002f,80.06746f,-66.16144f,1,0,0,-.0001f,.99999f,0},
                {0,0,0,0,1,0,1,.0001f,0},
                {1000000,1,1,1,0,0,.0001f,1,0}
        };
        try(var kernel=cuda.compileBatchKernel(CudaOpticalMediumSource.source()+"\n"+PROBE,"optical_spawn_probe");
            var records=cuda.allocate(cases.length*64L)) {
            var host=arena.allocate(cases.length*64L,16);host.fill((byte)0);
            for(int i=0;i<cases.length;i++)for(int j=0;j<9;j++)host.set(JAVA_FLOAT,i*64L+j*4,cases[i][j]);
            cuda.upload(records.slice(0,records.byteSize()),host);cuda.launchBlocking(kernel,records.slice(0,records.byteSize()),cases.length,1);
            cuda.download(records.slice(0,records.byteSize()),host);
            for(int i=0;i<cases.length;i++) {
                double outgoing=0,moved=0;
                for(int c=0;c<3;c++) {
                    float value=host.get(JAVA_FLOAT,i*64L+(9+c)*4);
                    if(!Float.isFinite(value))throw new AssertionError("nonfinite spawn case "+i);
                    outgoing+=cases[i][3+c]*cases[i][6+c];moved+=cases[i][3+c]*(value-cases[i][c]);
                    if(cases[i][3+c]==0&&value!=cases[i][c])throw new AssertionError("tangent coordinate changed case "+i);
                }
                if(moved*(outgoing<0?-1:1)<=0)throw new AssertionError("spawn did not leave on outgoing side case "+i);
            }
            if(!(host.get(JAVA_FLOAT,44)>-2))throw new AssertionError("grazing transmitted production ray stayed outside its entered boundary");
        }
    }
    private static void verifyConnectionTransport(CudaMediumRuntime cuda,Arena arena) {
        final int connection=512,hit=connection+CudaOpticalMediumAbi.CONNECTION_BYTES,bytes=hit+CudaOpticalMediumAbi.HIT_BYTES;
        try(var kernel=cuda.compileBatchKernel(CudaOpticalMediumSource.source()+"\n"+PROBE,"optical_connection_probe");
            var records=cuda.allocate(bytes)) {
            var host=arena.allocate(bytes,16);
            // A light starts in a scattering IOR 1.5 volume and leaves into vacuum.
            // Normal-incidence connection transmittance is exp(-sigmaT*2)*(1-F);
            // the following vacuum segment must not inherit camera/inside extinction.
            host.fill((byte)0);host.set(JAVA_INT,24,16);host.set(JAVA_INT,28,1);
            host.set(JAVA_INT,40,hit);host.set(JAVA_INT,64,448);host.set(JAVA_INT,72,connection);
            host.set(JAVA_FLOAT,92,1);host.set(JAVA_INT,152,-1);host.set(JAVA_INT,156,64);
            host.set(JAVA_INT,384,7);host.set(JAVA_INT,388,-11);host.set(JAVA_INT,396,1);
            host.set(JAVA_FLOAT,400,.1f);host.set(JAVA_FLOAT,404,.2f);host.set(JAVA_FLOAT,408,.3f);host.set(JAVA_FLOAT,412,1.5f);
            host.set(JAVA_FLOAT,432,.05f);host.set(JAVA_FLOAT,436,.04f);host.set(JAVA_FLOAT,440,.03f);host.set(JAVA_INT,444,-1);
            host.set(JAVA_INT,448,-11);
            host.set(JAVA_FLOAT,connection+12,5);host.set(JAVA_FLOAT,connection+24,1);
            for(int c=0;c<3;c++)host.set(JAVA_FLOAT,connection+48+c*4,1);
            host.set(JAVA_FLOAT,connection+60,10);
            host.set(JAVA_INT,connection+96,0);host.set(JAVA_INT,connection+100,0);host.set(JAVA_INT,connection+228,1);
            host.set(JAVA_FLOAT,hit+8,2);host.set(JAVA_FLOAT,hit+12,2);host.set(JAVA_FLOAT,hit+56,-1);
            for(int c=0;c<3;c++)host.set(JAVA_FLOAT,hit+64+c*4,1);
            host.set(JAVA_FLOAT,hit+76,1);host.set(JAVA_FLOAT,hit+96,1);host.set(JAVA_FLOAT,hit+100,1.5f);
            host.set(JAVA_INT,hit+112,1);host.set(JAVA_INT,hit+116,2);
            host.set(JAVA_INT,hit+176,7);host.set(JAVA_INT,hit+180,-11);host.set(JAVA_INT,hit+188,1);
            cuda.upload(records.slice(0,bytes),host);cuda.launchBlocking(kernel,records.slice(0,bytes),1,1);cuda.download(records.slice(0,bytes),host);
            if(host.get(JAVA_INT,68)!=1||host.get(JAVA_INT,connection+96)!=-1||host.get(JAVA_INT,connection+228)!=0)
                throw new AssertionError("optical connection did not leave its exact boundary stack");
            double[] sigma={.15,.24,.33};
            for(int c=0;c<3;c++)near(host.get(JAVA_FLOAT,connection+48+c*4),Math.exp(-sigma[c]*2)*.96,3e-6,"boundary connection Beer/Fresnel channel "+c);
            // A miss integrates the rest using the restored exterior medium (vacuum).
            host.set(JAVA_INT,hit+112,0);
            cuda.upload(records.slice(0,bytes),host);cuda.launchBlocking(kernel,records.slice(0,bytes),1,1);cuda.download(records.slice(0,bytes),host);
            if(host.get(JAVA_INT,68)!=0)throw new AssertionError("optical connection miss did not complete");
            for(int c=0;c<3;c++)near(host.get(JAVA_FLOAT,connection+48+c*4),Math.exp(-sigma[c]*2)*.96,3e-6,"restored exterior channel "+c);
            // An ordinary opaque terminal blocks the connection; it is not a volume crossing.
            host.set(JAVA_FLOAT,connection+12,3);host.set(JAVA_FLOAT,connection+28,0);host.set(JAVA_FLOAT,connection+60,8);
            host.set(JAVA_FLOAT,hit+8,3);host.set(JAVA_FLOAT,hit+12,1);host.set(JAVA_INT,hit+112,1);host.set(JAVA_INT,hit+188,0);
            host.set(JAVA_FLOAT,hit+96,0);
            cuda.upload(records.slice(0,bytes),host);cuda.launchBlocking(kernel,records.slice(0,bytes),1,1);cuda.download(records.slice(0,bytes),host);
            if(host.get(JAVA_INT,68)!=0)throw new AssertionError("opaque connection did not finish");
            for(int c=0;c<3;c++)near(host.get(JAVA_FLOAT,connection+48+c*4),0,0,"opaque light blocker channel "+c);
            // A constant half-density unit grid has integrated mass exactly 0.5 over one world unit.
            // Its memory and arithmetic reference is independent from the public scene packer.
            host.fill((byte)0);host.set(JAVA_INT,24,16);host.set(JAVA_INT,40,hit);host.set(JAVA_INT,72,connection);
            host.set(JAVA_FLOAT,80,.1f);host.set(JAVA_FLOAT,84,.2f);host.set(JAVA_FLOAT,88,.3f);host.set(JAVA_FLOAT,92,1);
            host.set(JAVA_FLOAT,160,.05f);host.set(JAVA_FLOAT,164,.04f);host.set(JAVA_FLOAT,168,.03f);
            host.set(JAVA_INT,152,384);host.set(JAVA_INT,156,64);host.set(JAVA_INT,384,448);
            host.set(JAVA_INT,388,1);host.set(JAVA_INT,392,1);host.set(JAVA_INT,396,1);
            host.set(JAVA_FLOAT,400,1);host.set(JAVA_FLOAT,420,1);host.set(JAVA_FLOAT,440,1);host.set(JAVA_FLOAT,448,.5f);
            host.set(JAVA_FLOAT,connection,.5f);host.set(JAVA_FLOAT,connection+4,.5f);host.set(JAVA_FLOAT,connection+12,1);
            host.set(JAVA_FLOAT,connection+24,1);host.set(JAVA_FLOAT,connection+60,1);host.set(JAVA_INT,connection+96,-1);
            for(int c=0;c<3;c++)host.set(JAVA_FLOAT,connection+48+c*4,1);
            cuda.upload(records.slice(0,bytes),host);cuda.launchBlocking(kernel,records.slice(0,bytes),1,1);cuda.download(records.slice(0,bytes),host);
            if(host.get(JAVA_INT,68)!=0)throw new AssertionError("density connection did not complete");
            for(int c=0;c<3;c++)near(host.get(JAVA_FLOAT,connection+48+c*4),Math.exp(-sigma[c]*.5),3e-6,"grid light attenuation channel "+c);
            // Non-shadow-casting lights ignore opaque surfaces but still integrate
            // extinction. Two geometric segments must equal the independent Beer result.
            host.set(JAVA_FLOAT,connection+8,0);host.set(JAVA_FLOAT,connection+12,1);
            host.set(JAVA_FLOAT,connection+60,1);host.set(JAVA_INT,connection+44,2);host.set(JAVA_INT,connection+88,0);
            for(int c=0;c<3;c++)host.set(JAVA_FLOAT,connection+48+c*4,1);
            host.set(JAVA_FLOAT,hit+8,.5f);host.set(JAVA_FLOAT,hit+12,.5f);host.set(JAVA_FLOAT,hit+56,-1);
            host.set(JAVA_FLOAT,hit+76,1);host.set(JAVA_INT,hit+112,1);
            cuda.upload(records.slice(0,bytes),host);cuda.launchBlocking(kernel,records.slice(0,bytes),1,1);cuda.download(records.slice(0,bytes),host);
            if(host.get(JAVA_INT,68)!=1)throw new AssertionError("non-shadow light was blocked by an opaque surface");
            host.set(JAVA_INT,hit+112,0);
            cuda.upload(records.slice(0,bytes),host);cuda.launchBlocking(kernel,records.slice(0,bytes),1,1);cuda.download(records.slice(0,bytes),host);
            if(host.get(JAVA_INT,68)!=0)throw new AssertionError("non-shadow light connection did not complete");
            for(int c=0;c<3;c++)near(host.get(JAVA_FLOAT,connection+48+c*4),Math.exp(-sigma[c]*.5),3e-6,
                    "non-shadow light retains grid extinction channel "+c);
        }
    }
    private static void verifyConnectionRayContinuity(CudaMediumRuntime cuda,Arena arena) {
        final int connection=512,hit=connection+CudaOpticalMediumAbi.CONNECTION_BYTES,bytes=hit+CudaOpticalMediumAbi.HIT_BYTES;
        try(var kernel=cuda.compileBatchKernel(CudaOpticalMediumSource.source()+"\n"+PROBE,"optical_connection_probe");
            var records=cuda.allocate(bytes)) {
            var host=arena.allocate(bytes,16);host.fill((byte)0);
            host.set(JAVA_INT,24,16);host.set(JAVA_INT,28,1);host.set(JAVA_INT,40,hit);
            host.set(JAVA_INT,64,448);host.set(JAVA_INT,72,connection);
            host.set(JAVA_FLOAT,92,1);host.set(JAVA_INT,152,-1);host.set(JAVA_INT,156,64);
            host.set(JAVA_INT,384,7);host.set(JAVA_INT,396,2);host.set(JAVA_FLOAT,400,.25f);
            host.set(JAVA_FLOAT,412,1);host.set(JAVA_INT,444,-1);
            // Production edge: a non-shadow-casting light passes a slanted mirror
            // just outside x=2. A normal offset moved it inside the adjacent box
            // without an entry, then its exit rejected the entire renderer frame.
            host.set(JAVA_FLOAT,connection,2.000003f);host.set(JAVA_FLOAT,connection+4,.64209f);
            host.set(JAVA_FLOAT,connection+8,-1);host.set(JAVA_FLOAT,connection+12,4);
            host.set(JAVA_FLOAT,connection+24,1);host.set(JAVA_INT,connection+44,2);
            for(int c=0;c<3;c++)host.set(JAVA_FLOAT,connection+48+c*4,1);
            host.set(JAVA_FLOAT,connection+60,4);host.set(JAVA_INT,connection+96,-1);
            host.set(JAVA_FLOAT,hit,2.000003f);host.set(JAVA_FLOAT,hit+4,.64209f);
            host.set(JAVA_FLOAT,hit+12,1);host.set(JAVA_FLOAT,hit+48,.8506508f);host.set(JAVA_FLOAT,hit+56,-.5257311f);
            host.set(JAVA_INT,hit+112,1);
            cuda.upload(records.slice(0,bytes),host);cuda.launchBlocking(kernel,records.slice(0,bytes),1,1);cuda.download(records.slice(0,bytes),host);
            if(host.get(JAVA_INT,68)!=1)throw new AssertionError("non-shadow connection did not continue");
            near(host.get(JAVA_FLOAT,connection),2.000003f,0,"straight connection x must not cross adjacent boundary");
            near(host.get(JAVA_FLOAT,connection+8),-1,0,"connection retains its original ray origin");
            near(host.get(JAVA_FLOAT,connection+28),1,0,"absolute hit distance advances the connection cursor");
            // A thin IOR-one volume immediately after that surface must still
            // enter and leave, with Beer attenuation over precisely its width.
            float enter=Math.nextUp(1f),exit=1.00001f;
            host.set(JAVA_FLOAT,hit+12,enter);host.set(JAVA_INT,hit+176,7);host.set(JAVA_INT,hit+188,2);
            cuda.upload(records.slice(0,bytes),host);cuda.launchBlocking(kernel,records.slice(0,bytes),1,1);cuda.download(records.slice(0,bytes),host);
            if(host.get(JAVA_INT,68)!=1||host.get(JAVA_INT,connection+228)!=1)throw new AssertionError("nearby volume entry was skipped");
            host.set(JAVA_FLOAT,hit+12,exit);host.set(JAVA_INT,hit+116,2);
            cuda.upload(records.slice(0,bytes),host);cuda.launchBlocking(kernel,records.slice(0,bytes),1,1);cuda.download(records.slice(0,bytes),host);
            if(host.get(JAVA_INT,68)!=1||host.get(JAVA_INT,connection+228)!=0)throw new AssertionError("thin volume exit lost its entry");
            near(host.get(JAVA_FLOAT,connection+48),Math.exp(-.25*(exit-enter)),1e-7,"thin boundary segment attenuation");
            near(host.get(JAVA_FLOAT,connection+28),exit,0,"connection cursor is cumulative");
        }
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
