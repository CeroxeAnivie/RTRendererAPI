namespace rtr_optical {
#ifdef RTR_OPTICAL_PHASE_PROFILE
// Sparse per-path latency samples; overlapping GPU cycles are not wall time.
struct PhaseSample {
    unsigned char *buffer;unsigned int phase;unsigned long long started;bool selected;
    __device__ PhaseSample(unsigned char*b):buffer(b),phase(0u),started(0u),
            selected(threadIdx.x==0u&&(blockIdx.x&31u)==0u){
        if(selected){atomicAdd(((unsigned long long*)(buffer+192))+7,1ull);started=clock64();}
    }
    __device__ void next(unsigned int value){if(selected){
        unsigned long long ended=clock64();
        atomicAdd(((unsigned long long*)(buffer+192))+phase,ended-started);
        phase=value;started=clock64();
    }}
    __device__ void end(){if(selected){atomicAdd(((unsigned long long*)(buffer+192))+phase,clock64()-started);selected=false;}}
    __device__ ~PhaseSample(){end();}
};
#if !defined(RTR_OPTICAL_FINISH_PROFILE) && !defined(RTR_OPTICAL_ENTRY_PROFILE)
#define RTR_PHASE_SCOPE(b) rtr_optical::PhaseSample phaseSample(b)
#define RTR_PHASE_NEXT(p) phaseSample.next(p)
#else
#define RTR_PHASE_SCOPE(b)
#define RTR_PHASE_NEXT(p)
#endif
#else
#define RTR_PHASE_SCOPE(b)
#define RTR_PHASE_NEXT(p)
#endif
template<bool Small> struct State;
template<> struct __align__(16) State<true> {
    static constexpr bool SMALL=true;
    float4 origin,direction;uint4 control;float4 throughput,radiance;uint4 counters;float4 terminal;
};
template<> struct __align__(16) State<false> {
    static constexpr bool SMALL=false;
    float4 origin,direction;uint4 control;float4 throughput,radiance;uint4 counters;float4 terminal;
    rtr_medium::PathVolumeInfo volumes;
};
struct __align__(16) Boundary {unsigned int slot;int priority;unsigned int orientation,flags;float4 absorption,emission,sigmaSAndDescriptor;};
static_assert(sizeof(State<false>)==256,"native optical state ABI");
static_assert(sizeof(State<true>)==112,"native optical boundary-free state ABI");
static_assert(sizeof(Boundary)==64,"native optical boundary ABI");
__device__ float3 rgb(float4 v){return make_float3(v.x,v.y,v.z);}
__device__ float peak(float3 v){return fmaxf(v.x,fmaxf(v.y,v.z));}
__device__ bool finite3(float3 v){return isfinite(v.x)&&isfinite(v.y)&&isfinite(v.z);}
template<bool Small> __device__ State<Small> *states(unsigned char *b){return (State<Small> *)(b+((unsigned int*)b)[9]);}
// Scalar planes make the same state component contiguous across a warp. References
// preserve individual writes and early returns without eagerly loading or flushing state.
template<class T> struct VectorRef {
    T &x,&y,&z,&w;
    __device__ __forceinline__ VectorRef(T*base,unsigned int paths):x(base[0]),y(base[paths]),z(base[2u*paths]),w(base[3u*paths]){}
};
struct Float4Ref:VectorRef<float> {
    __device__ __forceinline__ Float4Ref(float*base,unsigned int paths):VectorRef<float>(base,paths){}
    __device__ __forceinline__ operator float4()const{return make_float4(x,y,z,w);}
    __device__ __forceinline__ void operator=(float4 v){x=v.x;y=v.y;z=v.z;w=v.w;}
};
struct Uint4Ref:VectorRef<unsigned int> {
    __device__ __forceinline__ Uint4Ref(unsigned int*base,unsigned int paths):VectorRef<unsigned int>(base,paths){}
};
template<bool Small,bool Transposed> struct StateAccess {
    State<Small>*value;
    __device__ __forceinline__ StateAccess(unsigned char*b,unsigned int item,unsigned int paths):value(states<Small>(b)+item){}
    __device__ __forceinline__ State<Small>*operator->(){return value;}
};
template<> struct StateAccess<true,true> {
    static constexpr bool SMALL=true;
    Float4Ref origin,direction;Uint4Ref control;Float4Ref throughput,radiance;Uint4Ref counters;Float4Ref terminal;
    __device__ __forceinline__ StateAccess(float*base,unsigned int paths):origin(base,paths),direction(base+4u*paths,paths),
        control((unsigned int*)base+8u*paths,paths),throughput(base+12u*paths,paths),radiance(base+16u*paths,paths),
        counters((unsigned int*)base+20u*paths,paths),terminal(base+24u*paths,paths){}
    __device__ __forceinline__ StateAccess(unsigned char*b,unsigned int item,unsigned int paths):
        StateAccess((float*)(b+((unsigned int*)b)[9])+item,paths){}
    __device__ __forceinline__ StateAccess*operator->(){return this;}
};

template<class S> __device__ unsigned int selectedVolume(const S*s){
    if constexpr(S::SMALL)return 0xffffffffu;else return s->volumes.currentVolumeIndex;
}
template<bool Compact,bool Transposed> struct HitView {
    const unsigned int *data;unsigned int paths,item;
    __device__ unsigned int word(unsigned int n)const{return Transposed?(n/4u)*paths*4u+item*4u+(n&3u):item*(Compact?24u:52u)+n;}
    __device__ unsigned int u(unsigned int n)const{return data[word(n)];}
    __device__ float f(unsigned int n)const{return __uint_as_float(u(n));}
};
__device__ const Boundary *table(unsigned char *b){return (const Boundary *)(b+384);}
__device__ float3 sigmaS(unsigned char*b,unsigned int selected){return selected==0xffffffffu
        ?make_float3(((float*)b)[40],((float*)b)[41],((float*)b)[42])
        :rgb(table(b)[selected].sigmaSAndDescriptor);
}
__device__ float anisotropy(unsigned char*b,unsigned int selected){return selected==0xffffffffu?((float*)b)[43]:table(b)[selected].emission.w;
}
__device__ const rtr_medium::Material *priorities(unsigned char *b){return (const rtr_medium::Material *)(b+((unsigned int*)b)[16]);}
__device__ unsigned int global(unsigned char *b,int i){return ((unsigned int*)b)[4]*((unsigned int*)b)[3]+i;}
__device__ float random(unsigned int path,unsigned int seed,unsigned int event){
    unsigned int v=path*0x9e3779b9u^seed*0xc2b2ae35u^event*0x27d4eb2du;
    v^=v>>16;v*=0x7feb352du;v^=v>>15;return (v>>8)*(1.f/16777216.f);
}
__device__ void increment(unsigned char*b,unsigned int*statistics,unsigned int index){if(statistics)++statistics[index];else atomicAdd(((unsigned int*)b)+28+index,1u);}
template<class S> __device__ void finish(unsigned char *b,int i,S *s,unsigned int*statistics,unsigned int error=0){
#ifdef RTR_OPTICAL_FINISH_PROFILE
    PhaseSample finishSample(b);
#define RTR_FINISH_NEXT(p) finishSample.next(p)
#else
#define RTR_FINISH_NEXT(p)
#endif
    s->control.x=0;s->control.y=error;unsigned int *h=(unsigned int*)b;
    RTR_FINISH_NEXT(1u);
    if(error){increment(b,statistics,1u);return;}
    const unsigned int at=global(b,i);float4 *output=(float4 *)(b+h[14]);
    RTR_FINISH_NEXT(2u);
    float3 value=rgb(output[at])+rgb(s->radiance)*s->terminal.w;
    RTR_FINISH_NEXT(3u);
    if(!finite3(value)||value.x<0.f||value.y<0.f||value.z<0.f){increment(b,statistics,1u);return;}
    RTR_FINISH_NEXT(4u);
    output[at]=make_float4(value.x,value.y,value.z,0.f);
    RTR_FINISH_NEXT(5u);
    increment(b,statistics,3u);
    RTR_FINISH_NEXT(6u);
#undef RTR_FINISH_NEXT
}
template<class S> __device__ bool initialize(unsigned char *b,S *s){
    unsigned int*h=(unsigned int*)b;if(h[0]!=0x524f5031u||h[1]!=4u||h[8]>32||h[6]<1||h[6]>32)return false;
    if constexpr(S::SMALL){if(h[7]!=0u||h[8]!=0u)return false;}
    else{
        rtr_medium::PathVolumeInfo_Init(&s->volumes);
        for(unsigned int j=0;j<h[8];j++)if(rtr_medium::add(&s->volumes,h[64+j],priorities(b),h[7])!=rtr_medium::OK)return false;
    }
    s->counters.z=1;return true;
}
__device__ float ior(unsigned char *b,unsigned int selected){return selected==0xffffffffu?((float*)b)[23]:table(b)[selected].absorption.w;}
template<class S> __device__ bool segment(unsigned char*b,S*s,float3 origin,float distance,bool*collision,bool sample=true){
    *collision=false;
    distance=fminf(fmaxf(distance,0.f),s->throughput.w);if(distance==0.f)return true;
    const unsigned int selected=selectedVolume(s);const float*h=(const float*)b;
    float3 absorption=selected==0xffffffffu?make_float3(h[20],h[21],h[22]):rgb(table(b)[selected].absorption);
    float3 e=selected==0xffffffffu?make_float3(h[24],h[25],h[26]):rgb(table(b)[selected].emission);
    float3 scatter=sigmaS(b,selected),sigma=absorption+scatter;
    const int descriptor=selected==0xffffffffu?((const int*)b)[38]:((const int*)&table(b)[selected].sigmaSAndDescriptor)[3];
    const float mass=rtr_density::mass(b,descriptor,origin,rgb(s->direction),distance,((const unsigned int*)b)[39]);
    if(!isfinite(mass)||mass<0.f)return false;
    float3 tr,unused,zero=make_float3(0.f);
    HomogeneousVolume_SegmentScatter(.5f,false,mass,&sigma,&zero,&zero,&tr,&unused);
    float3 integrated=make_float3(rtrApiIntegratedEmission(sigma.x,e.x,mass),rtrApiIntegratedEmission(sigma.y,e.y,mass),rtrApiIntegratedEmission(sigma.z,e.z,mass));
    float3 value=rgb(s->radiance)+rgb(s->terminal)*rgb(s->throughput)*integrated;
    const bool sampling=sample&&peak(scatter)>0.f&&descriptor<0&&s->counters.w<((unsigned int*)b)[44]+1u;
    float3 weight=rgb(s->throughput)*tr;
    if(sampling){
        const unsigned int seed=((unsigned int*)b)[36]^((unsigned int*)b)[34]*0x967a889bu^s->control.w*0x9e3779b9u;
        const unsigned int channel=min((unsigned int)(random(s->control.z,seed,0u)*3.f),2u);
        const float rate=channel==0u?sigma.x:channel==1u?sigma.y:sigma.z;
        float3 one=make_float3(1.f),sampleTr,sampleEmission;
        const float optical=HomogeneousVolume_SegmentScatter(random(s->control.z,seed,1u),true,1e30f,&zero,&one,&zero,&sampleTr,&sampleEmission);
        const float local=rate>0.f?optical/rate:1e30f;
        if(local<distance){
            float3 atTr;HomogeneousVolume_SegmentScatter(.5f,false,local,&sigma,&zero,&zero,&atTr,&unused);
            const float pdf=(sigma.x*atTr.x+sigma.y*atTr.y+sigma.z*atTr.z)/3.f;
            if(!(pdf>0.f)||!isfinite(pdf))return false;
            weight=rgb(s->throughput)*atTr*scatter/pdf;
            const float3 point=origin+rgb(s->direction)*local;
            if(!finite3(value)||!finite3(weight)||!finite3(point))return false;
            s->radiance=make_float4(value.x,value.y,value.z,0.f);
            s->throughput=make_float4(weight.x,weight.y,weight.z,fmaxf(s->throughput.w-local,0.f));
            s->origin=make_float4(point.x,point.y,point.z,0.f);s->control.x=2u;++s->counters.w;++s->control.w;
            *collision=true;return true;
        }
        const float survival=(tr.x+tr.y+tr.z)/3.f;
        if(!(survival>0.f)||!isfinite(survival))weight=make_float3(0.f);else weight*=1.f/survival;
    }
    s->radiance=make_float4(value.x,value.y,value.z,0.f);
    s->throughput=make_float4(weight.x,weight.y,weight.z,fmaxf(s->throughput.w-distance,0.f));
    return finite3(value)&&finite3(weight);
}
__device__ int boundaryIndex(unsigned char*b,unsigned int slot){
    unsigned int low=0,high=((unsigned int*)b)[7];const Boundary*t=table(b);
    while(low<high){unsigned int mid=low+(high-low)/2;if(t[mid].slot<slot)low=mid+1;else high=mid;}
    return low<((unsigned int*)b)[7]&&t[low].slot==slot?(int)low:-1;
}
// Pinned glass evaluation is used with a facing local normal and the actual selected pair of media.
__device__ bool glass(float3 incident,float3 normal,float n1,float n2,float3 tint,
        float3 *reflected,float3 *transmitted,float3 *reflectionWeight,float3 *transmissionWeight,float *fresnel){
    float3 tangent=normalize(fabsf(normal.z)<.999f?cross(make_float3(0,0,1),normal):cross(make_float3(0,1,0),normal));
    float3 bitangent=cross(normal,tangent),fixed=make_float3(dot(-incident,tangent),dot(-incident,bitangent),fmaxf(dot(-incident,normal),0.f));
    if(n1==n2){*reflected=incident;*transmitted=incident;*reflectionWeight=make_float3(0);*transmissionWeight=tint;*fresnel=0;return true;}
    const double ratio=(double)n2/n1;float3 rd,td;rtr_glass::HitPoint point;
    if(ratio>=1.e-8&&ratio<=1.e8&&fixed.z>1.e-6f){
        *reflectionWeight=rtr_glass::GlassMaterial_EvalSpecularReflection(&point,fixed,WHITE,n1,n2,&rd,0.f,1.f);
        *transmissionWeight=rtr_glass::GlassMaterial_EvalSpecularTransmission(&point,fixed,.5f,tint,n1,n2,0.f,&td);
        *fresnel=reflectionWeight->x;
        *reflected=normalize(tangent*rd.x+bitangent*rd.y+normal*rd.z);
        *transmitted=Spectrum_IsBlack(*transmissionWeight)?make_float3(0):normalize(tangent*td.x+bitangent*td.y+normal*td.z);
    }else{
        // Public finite IORs may exceed the pinned float eta^2 range; double Snell math preserves their limit.
        const double eta=(double)n1/n2,ci=fmin(fmax((double)fixed.z,0.),1.);
        double sine=eta*sqrt(fmax(0.,(1.-ci)*(1.+ci))),f=1.,ct=0.;
        if(sine<1.){ct=sqrt(fmax(0.,(1.-sine)*(1.+sine)));double scale=fmax((double)n1,(double)n2),a=n1/scale,c=n2/scale;
            double rs=(a*ci-c*ct)/(a*ci+c*ct),rp=(c*ci-a*ct)/(c*ci+a*ct);f=.5*(rs*rs+rp*rp);}
        *fresnel=(float)f;*reflectionWeight=make_float3((float)f);
        *reflected=normalize(incident+normal*(2.f*fixed.z));
        *transmitted=sine>=1.?make_float3(0):normalize(tangent*(float)(-eta*fixed.x)+bitangent*(float)(-eta*fixed.y)-normal*(float)ct);
        const double w=sine>=1.?0.:(1.-f)*eta*eta;
        *transmissionWeight=make_float3((float)(tint.x*w),(float)(tint.y*w),(float)(tint.z*w));
    }
    return finite3(*reflectionWeight)&&finite3(*transmissionWeight)&&finite3(*reflected)&&finite3(*transmitted)&&isfinite(*fresnel);
}
// Only the coordinate and public radiance-convention adapters surround the unchanged sampler.
// The caller preserves its 1/2 lobe PDF for both deterministic first splitting and later roulette.
__device__ bool roughGlass(float3 incident,float3 normal,float n1,float n2,float3 tint,float roughness,
        float u0,float u1,bool reflection,float3 *direction,float3 *weight){
    float3 tangent=normalize(fabsf(normal.z)<.999f?cross(make_float3(0,0,1),normal):cross(make_float3(0,1,0),normal));
    float3 bitangent=cross(normal,tangent);
    float3 fixed=make_float3(dot(-incident,tangent),dot(-incident,bitangent),fmaxf(dot(-incident,normal),0.f)),local;
    if(!rtr_roughglass::sample(fixed,tint,roughness,n1,n2,u0,u1,reflection,&local,weight))return false;
    // A rejected microfacet direction contributes zero; it is a valid sample, not a broken path.
    *direction=Spectrum_IsBlack(*weight)?incident:normalize(tangent*local.x+bitangent*local.y+normal*local.z);
    return finite3(*direction)&&finite3(*weight);
}
}
extern "C" __global__ void native_optical_reset(unsigned char*b,int count){if(blockIdx.x==0&&threadIdx.x==0){unsigned int*h=(unsigned int*)b;h[28]=h[29]=h[30]=h[31]=0;
#ifdef RTR_OPTICAL_PHASE_PROFILE
    for(int j=0;j<8;j++)((unsigned long long*)(b+192))[j]=0ull;
#endif
}}
#ifdef RTR_OPTICAL_ENTRY_PROFILE
#define RTR_ENTRY_SCOPE(b) rtr_optical::PhaseSample entrySample(b)
#define RTR_ENTRY_NEXT(p) entrySample.next(p)
#define RTR_ENTRY_END() entrySample.end()
#else
#define RTR_ENTRY_SCOPE(b)
#define RTR_ENTRY_NEXT(p)
#define RTR_ENTRY_END()
#endif
template<bool Compact,bool Small,bool Transposed=false,bool TransposedState=false> __device__ void rtr_optical_step_impl(unsigned char*b,int count,unsigned int*statistics){
    int i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=count)return;using namespace rtr_optical;
    RTR_PHASE_SCOPE(b);
    RTR_ENTRY_SCOPE(b);
    unsigned int*h=(unsigned int*)b;StateAccess<Small,TransposedState> state(b,i,h[2]);auto*s=state.operator->();
    if((h[47]&128u)!=0u&&h[17]!=0u){
        // Camera origin/direction retain the exact Vulkan projection. Construct
        // all deterministic fields here instead of writing and reloading them
        // across the producer/consumer boundary.
        s->control.x=1u;s->control.y=0u;s->control.z=global(b,i);s->control.w=1u;
        s->throughput=make_float4(1.f,1.f,1.f,((float*)b)[27]);
        s->radiance=make_float4(0.f);
        s->counters.x=0u;s->counters.y=0u;s->counters.z=0u;s->counters.w=0u;
        const unsigned int branches=h[37]==0u?2u:1u;
        s->terminal=make_float4(1.f,1.f,1.f,1.f/(float)(h[33]*branches));
    }
    if(s->control.x==0)return;
    RTR_ENTRY_NEXT(1u);
    if(!s->counters.z&&!initialize(b,s)){finish(b,i,s,statistics,1);return;}
    RTR_ENTRY_NEXT(2u);
    if(s->control.x==2u){
        RTR_PHASE_NEXT(6u);
        const float4 lighting=((float4*)(b+h[11]))[i];
        if(__float_as_uint(lighting.w)!=s->control.w||!finite3(rgb(lighting))){finish(b,i,s,statistics,14);return;}
        const float3 value=rgb(s->radiance)+rgb(s->terminal)*rgb(s->throughput)*rgb(lighting);
        if(!finite3(value)){finish(b,i,s,statistics,15);return;}
        s->radiance=make_float4(value.x,value.y,value.z,0.f);
        const unsigned int seed=h[36]^h[34]*0x967a889bu^s->control.w*0x9e3779b9u;
        float phasePdf;const float3 direction=rtr_medium::hgSample(rgb(s->direction),anisotropy(b,selectedVolume(s)),
            random(s->control.z,seed,2u),random(s->control.z,seed,3u),&phasePdf);
        if(!finite3(direction)||!isfinite(phasePdf)||phasePdf<=0.f){finish(b,i,s,statistics,16);return;}
        s->direction=make_float4(direction.x,direction.y,direction.z,0.f);s->control.x=1u;++s->control.w;
        if(peak(rgb(s->throughput))<=((float*)b)[32]||s->throughput.w<=0.f){finish(b,i,s,statistics);return;}
        increment(b,statistics,0u);return;
    }
    RTR_ENTRY_NEXT(3u);
    HitView<Compact,Transposed> view={(const unsigned int*)(b+h[10]),h[2],(unsigned int)i};
    RTR_ENTRY_NEXT(4u);
    if(view.u(Compact?20:48)!=s->control.z/h[3]||view.u(Compact?21:49)!=s->control.z%h[3]||view.u(Compact?23:51)!=s->control.w){finish(b,i,s,statistics,2);return;}
    RTR_ENTRY_NEXT(5u);
    bool found=view.u(Compact?14:28)!=0;float distance=found?view.f(3):s->throughput.w;
    RTR_ENTRY_END();
    RTR_PHASE_NEXT(1u);
    bool collision=false;if(!isfinite(distance)||distance<0||!segment(b,s,rgb(s->origin),distance,&collision)){finish(b,i,s,statistics,3);return;}
    RTR_PHASE_NEXT(2u);
    if(collision){increment(b,statistics,0u);return;}
    float3 evaluation=rgb(((float4*)(b+h[11]))[i]);
    if(!finite3(evaluation)){finish(b,i,s,statistics,4);return;}
    if(!found){float3 value=rgb(s->radiance)+rgb(s->terminal)*rgb(s->throughput)*evaluation;s->radiance=make_float4(value.x,value.y,value.z,0);finish(b,i,s,statistics);return;}
    ++s->counters.x;
    unsigned int flags=view.u(Compact?19:47);bool volumeOnly=(flags&2u)!=0u;
    float opacity=flags?1.f:fminf(fmaxf(view.f(Compact?11:19),0.f),1.f),transmission=volumeOnly?1.f:fminf(fmaxf(view.f(Compact?12:24),0.f),1.f);
    float3 local=rgb(s->terminal)*rgb(s->throughput)*evaluation*(opacity*(1.f-transmission));
    s->radiance.x+=local.x;s->radiance.y+=local.y;s->radiance.z+=local.z;
    if(transmission<=0.f&&opacity>=1.f){finish(b,i,s,statistics);return;}
    if(opacity<1.f){finish(b,i,s,statistics,5);return;}
    if(s->counters.x>=h[6]){finish(b,i,s,statistics);return;}
    RTR_PHASE_NEXT(3u);
    int compact=-1;bool entering=false;rtr_medium::PathVolumeInfo candidate;
    if constexpr(!Small)candidate=s->volumes;
    if(flags){
        if constexpr(Small){finish(b,i,s,statistics,6);return;}else{
        compact=boundaryIndex(b,view.u(Compact?16:44));if(compact<0){finish(b,i,s,statistics,6);return;}
        const Boundary entry=table(b)[compact];
        if(view.u(Compact?17:45)!=(unsigned int)entry.priority||view.u(Compact?18:46)!=entry.orientation||flags!=entry.flags){finish(b,i,s,statistics,7);return;}
        entering=(view.u(Compact?15:29)==2u)==(entry.orientation!=0u);
        if(entering)for(unsigned int j=0;j<candidate.volumeIndexListSize;j++)if(candidate.volumeIndexList[j]==(unsigned int)compact){finish(b,i,s,statistics,8);return;}
        const auto status=entering?rtr_medium::add(&candidate,compact,priorities(b),h[7]):rtr_medium::remove(&candidate,compact,priorities(b),h[7]);
        if(status!=rtr_medium::OK){finish(b,i,s,statistics,9);return;}
        }
    }
    float3 direction=rgb(s->direction);
    if(volumeOnly){if constexpr(!Small)s->volumes=candidate;}else{
        float exterior=ior(b,selectedVolume(s));
        float n1=view.u(Compact?15:29)==2u?view.f(Compact?13:25):exterior,n2=view.u(Compact?15:29)==2u?exterior:view.f(Compact?13:25);
        if constexpr(!Small)if(flags){n1=ior(b,s->volumes.currentVolumeIndex);n2=ior(b,candidate.currentVolumeIndex);}
        float3 normal=normalize(make_float3(view.f(Compact?4:12),view.f(Compact?5:13),view.f(Compact?6:14)));
        float3 tint=make_float3(fminf(fmaxf(view.f(Compact?8:16),0),1),fminf(fmaxf(view.f(Compact?9:17),0),1),fminf(fmaxf(view.f(Compact?10:18),0),1));
        if(dot(normal,-direction)<=0.f){finish(b,i,s,statistics,10);return;}
        unsigned int seed=h[36]^h[34]*0x967a889bu^h[35]*0x02e5be93u;bool reflection;float multiplier=transmission;
        bool firstInterface=!s->counters.y;
        float3 factor,nextDirection;
        if(view.f(Compact?7:15)>0.f&&n1!=n2){
            RTR_PHASE_NEXT(5u);
            reflection=firstInterface&&h[37]==0u?h[35]==0u:random(s->control.z,seed,s->counters.x)<.5f;
            unsigned int microfacetSeed=h[36]^h[34]*0x967a889bu;
            float u0=random(s->control.z,microfacetSeed^0xa511e9b3u,s->counters.x);
            float u1=random(s->control.z,microfacetSeed^0x63d83595u,s->counters.x);
            if(!roughGlass(direction,normal,n1,n2,tint,view.f(Compact?7:15),u0,u1,reflection,&nextDirection,&factor)){finish(b,i,s,statistics,10);return;}
            // The pinned weight already divides by its 1/2 lobe PDF. Deterministic splitting
            // averages two explicit lobes; roulette chooses one with that same probability.
            factor*=multiplier;
        }else{
            RTR_PHASE_NEXT(4u);
            float3 rd,td,rw,tw;float fresnel;
            if(!glass(direction,normal,n1,n2,tint,&rd,&td,&rw,&tw,&fresnel)){finish(b,i,s,statistics,10);return;}
            if(firstInterface&&h[37]==0u){reflection=h[35]==0u;multiplier*=2.f;}
            else{reflection=random(s->control.z,seed,s->counters.x)<fresnel;float probability=reflection?fresnel:1.f-fresnel;
                if(probability<=0.f){finish(b,i,s,statistics);return;}multiplier/=probability;}
            factor=(reflection?rw:tw)*multiplier;nextDirection=reflection?rd:td;
        }
        RTR_PHASE_NEXT(6u);
        s->counters.y=1;
        float3 weight=rgb(s->throughput)*(firstInterface?make_float3(1.f):factor);
        if(firstInterface){float3 outer=rgb(s->terminal)*factor;s->terminal.x=outer.x;s->terminal.y=outer.y;s->terminal.z=outer.z;
            if(peak(outer)<=0.f){finish(b,i,s,statistics);return;}}
        if(!finite3(weight)){finish(b,i,s,statistics,11);return;}
        if(peak(weight)<=((float*)b)[32]){finish(b,i,s,statistics);return;}
        direction=nextDirection;if constexpr(!Small)if(!reflection&&flags)s->volumes=candidate;
        s->throughput.x=weight.x;s->throughput.y=weight.y;s->throughput.z=weight.z;
        increment(b,statistics,2u);
    }
    RTR_PHASE_NEXT(6u);
    float3 point=make_float3(view.f(0),view.f(1),view.f(2));
    float epsilon=fmaxf(1.e-5f,fmaxf(fabsf(point.x),fmaxf(fabsf(point.y),fabsf(point.z)))*2.e-7f);
    if(!finite3(point)||!finite3(direction)){finish(b,i,s,statistics,12);return;}
    s->direction=make_float4(direction.x,direction.y,direction.z,0);
    RTR_PHASE_NEXT(1u);
    bool epsilonCollision=false;if(!segment(b,s,point,epsilon,&epsilonCollision,false)){finish(b,i,s,statistics,13);return;}
    RTR_PHASE_NEXT(6u);
    if(epsilonCollision){increment(b,statistics,0u);return;}
    point+=direction*epsilon;s->origin=make_float4(point.x,point.y,point.z,epsilon);++s->control.w;
    if(peak(rgb(s->throughput))<=((float*)b)[32]){finish(b,i,s,statistics);return;}increment(b,statistics,0u);
}

namespace rtr_optical {
// CudaMediumRuntime launches 256 threads per block. Every lane, including tail lanes,
// must call this helper; counters are exact integers and do not affect radiance ordering.
__device__ __forceinline__ void reduceStatistics(unsigned char*b,unsigned int*statistics){
    const unsigned int lane=threadIdx.x&31u,warp=threadIdx.x>>5u;
    for(unsigned int offset=16u;offset>0u;offset>>=1u)
        for(unsigned int k=0u;k<4u;++k)statistics[k]+=__shfl_down_sync(0xffffffffu,statistics[k],offset);
    __shared__ unsigned int totals[32];
    if(lane==0u)for(unsigned int k=0u;k<4u;++k)totals[warp*4u+k]=statistics[k];
    __syncthreads();
    if(threadIdx.x<4u){unsigned int sum=0u;for(unsigned int w=0u;w<(blockDim.x+31u)/32u;++w)sum+=totals[w*4u+threadIdx.x];
        if(sum)atomicAdd(((unsigned int*)b)+28+threadIdx.x,sum);}
}
}
__device__ void rtr_optical_dispatch(unsigned char*b,int count,unsigned int*statistics){
    const unsigned int layout=((unsigned int*)b)[47];
    if((layout&18u)==18u){
        if((layout&9u)==9u)rtr_optical_step_impl<true,true,true,true>(b,count,statistics);
        else if(layout&1u)rtr_optical_step_impl<true,true,false,true>(b,count,statistics);
        else rtr_optical_step_impl<false,true,false,true>(b,count,statistics);return;
    }
    if((layout&9u)==9u){if(layout&2u)rtr_optical_step_impl<true,true,true>(b,count,statistics);else rtr_optical_step_impl<true,false,true>(b,count,statistics);return;}
    if(layout&2u){if(layout&1u)rtr_optical_step_impl<true,true>(b,count,statistics);else rtr_optical_step_impl<false,true>(b,count,statistics);}
    else{if(layout&1u)rtr_optical_step_impl<true,false>(b,count,statistics);else rtr_optical_step_impl<false,false>(b,count,statistics);}
}
extern "C" __global__ void native_optical_step(unsigned char*b,int count){
    const int i=blockIdx.x*blockDim.x+threadIdx.x;
    if(((unsigned int*)b)[46]==0u){if(i<count)rtr_optical_dispatch(b,count,nullptr);return;}
    unsigned int statistics[4]={0u,0u,0u,0u};
    if(i<count)rtr_optical_dispatch(b,count,statistics);
    rtr_optical::reduceStatistics(b,statistics);
}
// Independent entry points keep each layout's compiled resources separate.
template<bool Compact,bool Small,bool Transposed=false,bool TransposedState=false> __device__ __forceinline__ void rtr_optical_fixed(unsigned char*b,int count){
    const int i=blockIdx.x*blockDim.x+threadIdx.x;
    if(((unsigned int*)b)[46]==0u){if(i<count)rtr_optical_step_impl<Compact,Small,Transposed,TransposedState>(b,count,nullptr);return;}
    unsigned int statistics[4]={0u,0u,0u,0u};
    if(i<count)rtr_optical_step_impl<Compact,Small,Transposed,TransposedState>(b,count,statistics);
    rtr_optical::reduceStatistics(b,statistics);
}
extern "C" __global__ void native_optical_step_0(unsigned char*b,int count){rtr_optical_fixed<false,false>(b,count);}
extern "C" __global__ void native_optical_step_1(unsigned char*b,int count){rtr_optical_fixed<true,false>(b,count);}
extern "C" __global__ void native_optical_step_2(unsigned char*b,int count){rtr_optical_fixed<false,true>(b,count);}
extern "C" __global__ void native_optical_step_3(unsigned char*b,int count){rtr_optical_fixed<true,true>(b,count);}
extern "C" __global__ void native_optical_step_4(unsigned char*b,int count){rtr_optical_fixed<true,false,true>(b,count);}
extern "C" __global__ void native_optical_step_5(unsigned char*b,int count){rtr_optical_fixed<true,true,true>(b,count);}

extern "C" __global__ void native_optical_step_6(unsigned char*b,int count){rtr_optical_fixed<false,true,false,true>(b,count);}
extern "C" __global__ void native_optical_step_7(unsigned char*b,int count){rtr_optical_fixed<true,true,false,true>(b,count);}
extern "C" __global__ void native_optical_step_8(unsigned char*b,int count){rtr_optical_fixed<true,true,true,true>(b,count);}
