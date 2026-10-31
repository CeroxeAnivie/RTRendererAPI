#ifdef RTR_OPTICAL_TRACE_PATH
extern "C" __device__ int printf(const char *, ...);
#endif
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
// A light connection retains its own medium stack. It never mutates the camera path.
// origin.xyz and direction.xyz define one fixed straight ray; direction.w is its
// last processed hit distance. Advancing tMin must not move this ray across a
// neighbouring boundary without processing that boundary's entry/exit event.
struct __align__(16) Connection {
    float4 origin,direction,source,transport,lighting;uint4 control;
    rtr_medium::PathVolumeInfo volumes;
    // Point.w carries metallic; normal.w/view.w initially retain the GGX sample.
    // After preparing continuation, normal.w retains its projected spawn bound.
    float4 surfacePoint,surfaceNormal,surfaceView,surfaceMaterial;
    // Continuation is prepared before NEE overwrites the shared geometric hit.
    // Weight.w is the deterministic local opacity*(1-transmission) contribution.
    float4 continuationDirection,continuationWeight;uint4 continuationBoundary;
};
static_assert(sizeof(Connection)==352,"native optical connection ABI");
static_assert(sizeof(State<false>)==256,"native optical state ABI");
static_assert(sizeof(State<true>)==112,"native optical boundary-free state ABI");
static_assert(sizeof(Boundary)==64,"native optical boundary ABI");
__device__ float3 rgb(float4 v){return make_float3(v.x,v.y,v.z);}
__device__ float peak(float3 v){return fmaxf(v.x,fmaxf(v.y,v.z));}
__device__ bool finite3(float3 v){return isfinite(v.x)&&isfinite(v.y)&&isfinite(v.z);}
// PBRT's ray-spawn construction: select the outgoing side using the geometric normal,
// then round each moved coordinate away from the surface. A ray-direction epsilon
// has arbitrarily small normal displacement at grazing incidence and can remain outside.
__device__ float3 spawnOriginBound(float3 point,float3 normal,float3 direction,float bound){
    const float epsilon=fmaxf(1.e-5f,bound);
    const float3 offset=normal*(dot(normal,direction)<0.f?-epsilon:epsilon);
    float3 result=point+offset;
    if(offset.x!=0.f)result.x=nextafterf(result.x,offset.x>0.f?INFINITY:-INFINITY);
    if(offset.y!=0.f)result.y=nextafterf(result.y,offset.y>0.f?INFINITY:-INFINITY);
    if(offset.z!=0.f)result.z=nextafterf(result.z,offset.z>0.f?INFINITY:-INFINITY);
    return result;
}
__device__ float3 spawnOrigin(float3 point,float3 normal,float3 direction,float3 error){
    return spawnOriginBound(point,normal,direction,dot(make_float3(fabsf(normal.x),fabsf(normal.y),fabsf(normal.z)),error));
}
__device__ float3 positionError(float3 point,float3 incoming,float distance,float3 direction){
    // Propagate intersection reconstruction and instance-transform rounding before
    // projecting the uncertainty onto the geometric normal, as in robust BSDF spawn.
    const float gamma7=4.172327e-7f;
    return make_float3(fabsf(point.x)+fabsf(incoming.x)+fabsf(distance*direction.x),
            fabsf(point.y)+fabsf(incoming.y)+fabsf(distance*direction.y),
            fabsf(point.z)+fabsf(incoming.z)+fabsf(distance*direction.z))*gamma7;
}
__device__ float3 spawnOrigin(float3 point,float3 normal,float3 direction){
    return spawnOrigin(point,normal,direction,positionError(point,point,0.f,direction));
}
__device__ float3 scatterOrigin(float3 origin,float3 direction,float local,float distance,
        const float3*endPoint,const float3*facingNormal){
    float3 point=make_float3(fmaf(direction.x,local,origin.x),fmaf(direction.y,local,origin.y),fmaf(direction.z,local,origin.z));
    if(!endPoint||!facingNormal)return point;
    // PBRT's error-bound/away-rounding principle, adapted to a volume sample
    // just before a boundary. Its nearest float may lie on the other side.
    // Retreat on the incident segment, so a normal offset cannot cross an
    // adjacent face at a grazing edge. Keep the sampled optical mass/PDF.
    const float3 normal=*facingNormal;
    const float3 error=positionError(point,origin,local,direction)+positionError(*endPoint,origin,distance,direction);
    const float bound=dot(make_float3(fabsf(normal.x),fabsf(normal.y),fabsf(normal.z)),error);
    const float side=dot(point-*endPoint,normal),approach=-dot(direction,normal);
    if(side>bound||!(approach>0.f))return point;
    const float retreat=fminf(local,fmaxf((bound-side)/approach,0.f));
    const float safeLocal=nextafterf(fmaxf(local-retreat,0.f),0.f);
    return make_float3(fmaf(direction.x,safeLocal,origin.x),fmaf(direction.y,safeLocal,origin.y),fmaf(direction.z,safeLocal,origin.z));
}
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
__device__ Connection *connections(unsigned char*b){return (Connection*)(b+((unsigned int*)b)[18]);}
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
template<class S> __device__ __forceinline__ void finish(unsigned char *b,int i,S *s,unsigned int*statistics,unsigned int error=0){
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
template<class S> __device__ __forceinline__ bool initialize(unsigned char *b,S *s){
    unsigned int*h=(unsigned int*)b;if(h[0]!=0x524f5031u||h[1]!=7u||h[8]>32||h[6]<1||h[6]>32)return false;
    if constexpr(S::SMALL){if(h[7]!=0u||h[8]!=0u)return false;}
    else{
        rtr_medium::PathVolumeInfo_Init(&s->volumes);
        for(unsigned int j=0;j<h[8];j++)if(rtr_medium::add(&s->volumes,h[64+j],priorities(b),h[7])!=rtr_medium::OK)return false;
    }
    s->counters.z=1;return true;
}
__device__ float ior(unsigned char *b,unsigned int selected){return selected==0xffffffffu?((float*)b)[23]:table(b)[selected].absorption.w;}
template<class S> __device__ __forceinline__ bool segment(unsigned char*b,S*s,float3 origin,float distance,bool*collision,bool sample=true,float3*incident=nullptr,
        const float3*endPoint=nullptr,const float3*facingNormal=nullptr){
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
    const bool sampling=sample&&peak(scatter)>0.f&&s->counters.w<((unsigned int*)b)[44]+1u;
    float3 weight=rgb(s->throughput)*tr;
    if(sampling){
        const unsigned int seed=((unsigned int*)b)[36]^((unsigned int*)b)[34]*0x967a889bu^s->control.w*0x9e3779b9u;
        const unsigned int channel=min((unsigned int)(random(s->control.z,seed,0u)*3.f),2u);
        const float rate=channel==0u?sigma.x:channel==1u?sigma.y:sigma.z;
        float3 one=make_float3(1.f),sampleTr,sampleEmission;
        const float optical=HomogeneousVolume_SegmentScatter(random(s->control.z,seed,1u),true,1e30f,&zero,&one,&zero,&sampleTr,&sampleEmission);
        float local=1e30f,collisionMass=0.f,collisionDensity=1.f;
        if(rate>0.f&&descriptor<0){local=optical/rate;collisionMass=local;}
        else if(rate>0.f){
            const unsigned int steps=((const unsigned int*)b)[39];
            const rtr_density::Descriptor &grid=*(const rtr_density::Descriptor*)(b+(unsigned int)descriptor);
            const float *pixels=(const float*)(b+grid.atlasOffset);
            const float3 p=rtr_density::map(grid,origin,1.f),v=rtr_density::map(grid,rgb(s->direction),0.f);
            float first=0.f,last=distance;
            if(!finite3(p)||!finite3(v)||steps<1u||steps>64u)return false;
            if(rtr_density::clipAxis(p.x,v.x,&first,&last)&&rtr_density::clipAxis(p.y,v.y,&first,&last)
                    &&rtr_density::clipAxis(p.z,v.z,&first,&last)){
                const float interval=(last-first)/(float)steps;float target=optical/rate,prefixMass=0.f;
                for(unsigned int j=0;j<steps;++j){
                    const float density=rtr_density::DensityGridTexture_ConstEvaluate(pixels,grid,p+v*(first+((float)j+.5f)*interval));
                    if(!isfinite(density)||density<0.f)return false;
                    const float cellMass=density*interval;
                    if(density>0.f&&target<cellMass){
                        local=first+(float)j*interval+target/density;
                        collisionMass=prefixMass+target;collisionDensity=density;break;
                    }
                    target-=cellMass;prefixMass+=cellMass;
                }
            }
        }
        if(local<distance){
            float3 atTr;HomogeneousVolume_SegmentScatter(.5f,false,collisionMass,&sigma,&zero,&zero,&atTr,&unused);
            const float pdf=collisionDensity*(sigma.x*atTr.x+sigma.y*atTr.y+sigma.z*atTr.z)/3.f;
            if(!(pdf>0.f)||!isfinite(pdf))return false;
            if(incident)*incident=rgb(s->throughput)*atTr*(1.f/pdf);
            weight=rgb(s->throughput)*atTr*scatter*(collisionDensity/pdf);
            const float3 point=scatterOrigin(origin,rgb(s->direction),local,distance,endPoint,facingNormal);
#ifdef RTR_OPTICAL_TRACE_PATH
            if(s->control.z-((unsigned int*)b)[4]*((unsigned int*)b)[3]==RTR_OPTICAL_TRACE_PATH)
                printf("OPTICAL_SCATTER seed=%u volume=%u distance=%.9g local=%.9g origin=[%.9g,%.9g,%.9g] direction=[%.9g,%.9g,%.9g] point=[%.9g,%.9g,%.9g]\n",
                        ((unsigned int*)b)[36],selected,distance,local,origin.x,origin.y,origin.z,s->direction.x,s->direction.y,s->direction.z,point.x,point.y,point.z);
#endif
            if(!finite3(value)||!finite3(weight)||!finite3(point))return false;
            s->radiance=make_float4(value.x,value.y,value.z,0.f);
            s->throughput=make_float4(weight.x,weight.y,weight.z,fmaxf(s->throughput.w-local,0.f));
            s->origin=make_float4(point.x,point.y,point.z,0.f);s->control.x=2u;++s->counters.w;++s->control.w;
#ifndef RTR_OPTICAL_CAUSTIC_ONLY
            if(((unsigned int*)b)[19]!=0u){
                Connection*c=connections(b)+(s->control.z-((unsigned int*)b)[4]*((unsigned int*)b)[3]);
                c->lighting=make_float4(0.f);c->control=make_uint4(0u,0u,0u,0u);
                if constexpr(S::SMALL)rtr_medium::PathVolumeInfo_Init(&c->volumes);else c->volumes=s->volumes;
            }
#endif
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
// The public opaque PBR path samples one GGX metallic reflection. Keep its
// f*cos/pdf weight and sample point, then integrate every reflected medium
// segment through the same selected-volume state as camera and glass paths.
__device__ float3 metalReflection(const Connection*c,float3*outgoing){
    *outgoing=make_float3(0.f);
    const float metallic=fminf(fmaxf(c->surfacePoint.w,0.f),1.f);
    if(metallic<=1.e-4f)return make_float3(0.f);
    const float3 normal=rgb(c->surfaceNormal),view=rgb(c->surfaceView);
    const float normalView=fmaxf(dot(normal,view),0.f);
    if(normalView<=1.e-5f)return make_float3(0.f);
    const float roughness=fminf(fmaxf(c->surfaceMaterial.w,.025f),1.f);
    const float alpha=fmaxf(roughness*roughness,.0025f),a2=alpha*alpha;
    const float phi=6.283185307179586f*c->surfaceNormal.w,u=c->surfaceView.w;
    const float denominator=(1.f-u)+a2*u;
    const float cosine=sqrtf(fmaxf((1.f-u)/denominator,0.f));
    const float sine=sqrtf(fmaxf(a2*u/denominator,0.f));
    const float3 tangent=normalize(fabsf(normal.z)<.999f?cross(make_float3(0,0,1),normal):cross(make_float3(0,1,0),normal));
    const float3 halfVector=normalize(tangent*(cosf(phi)*sine)+cross(normal,tangent)*(sinf(phi)*sine)+normal*cosine);
    const float viewHalf=dot(view,halfVector);
    if(viewHalf<=1.e-5f)return make_float3(0.f);
    *outgoing=normalize(halfVector*(2.f*viewHalf)-view);
    const float normalLight=dot(normal,*outgoing);
    if(normalLight<=1.e-5f)return make_float3(0.f);
    const float k=(roughness+1.f)*(roughness+1.f)*.125f;
    const float geometry=normalView/fmaxf(normalView*(1.f-k)+k,1.e-6f)
            *normalLight/fmaxf(normalLight*(1.f-k)+k,1.e-6f);
    const float3 reflectance=make_float3(.04f)*(1.f-metallic)+rgb(c->surfaceMaterial)*metallic;
    const float3 fresnel=reflectance+(make_float3(1.f)-reflectance)*powf(1.f-viewHalf,5.f);
    return fresnel*(metallic*geometry*viewHalf/fmaxf(normalView*fmaxf(dot(normal,halfVector),1.e-5f),1.e-5f));
}
template<bool Compact,class S,class V> __device__ int prepareSurfaceContinuation(unsigned char*b,S*s,const V&view,Connection*c,float surfaceKind){
    const unsigned int*h=(const unsigned int*)b,flags=view.u(Compact?19:47);
    const float opacity=flags?1.f:fminf(fmaxf(view.f(Compact?11:19),0.f),1.f);
    const float transmission=fminf(fmaxf(view.f(Compact?12:24),0.f),1.f);
    const float3 normal=rgb(c->surfaceNormal);
    const float bound=dot(make_float3(fabsf(normal.x),fabsf(normal.y),fabsf(normal.z)),
        positionError(rgb(c->surfacePoint),rgb(s->origin),view.f(3),rgb(s->direction)));
    const float3 overlaySky=rgb(c->continuationWeight);
    c->continuationDirection=make_float4(0.f);c->continuationWeight=make_float4(0.f,0.f,0.f,opacity*(1.f-transmission));
    c->continuationBoundary=make_uint4(0u,0u,0u,0u);
    if(surfaceKind>=3.f){
        const bool multiply=surfaceKind==4.f||surfaceKind==7.f,unmatched=surfaceKind==5.f;
        c->continuationWeight=make_float4(overlaySky.x,overlaySky.y,overlaySky.z,unmatched||multiply?0.f:opacity);
        c->continuationDirection=make_float4(s->direction.x,s->direction.y,s->direction.z,__uint_as_float(unmatched?1u:(multiply?5u:6u)));
        c->continuationBoundary.y=unmatched?2u:surfaceKind==3.f||surfaceKind==4.f?1u:0u;
        c->continuationBoundary.z=__float_as_uint(opacity*(1.f-transmission));
        c->continuationBoundary.w=__float_as_uint(opacity);
        c->surfaceNormal.w=bound;
        if(s->counters.x>=h[6])c->continuationDirection.w=0.f;
        return 0;
    }
    if(s->counters.x>=h[6]){c->surfaceNormal.w=bound;return 0;}
    float3 direction=rgb(s->direction),factor=make_float3(0.f);unsigned int mode=1u;
    const bool metal=transmission<=0.f&&c->surfacePoint.w>1.e-4f&&(s->counters.y&2u)==0u;
    const unsigned int seed=h[36]^h[34]*0x967a889bu;
    if(transmission<=0.f&&!metal){
        // With no secondary material lobe, evaluate coverage exactly: its local
        // contribution was retained above and the straight background has weight 1-alpha.
        factor=make_float3(1.f-opacity);
    }else if(opacity<1.f&&random(s->control.z,seed^0x51633e2du,s->counters.x)>=opacity){
        // Coverage roulette is only needed when another continuing material lobe
        // competes with the hole. Its probability cancels the coverage weight.
        factor=make_float3(1.f);
    }else if(transmission<=0.f){factor=metalReflection(c,&direction);mode=4u;
    }else{
        const float3 normal=rgb(c->surfaceNormal),tint=make_float3(fminf(fmaxf(view.f(Compact?8:16),0.f),1.f),
            fminf(fmaxf(view.f(Compact?9:17),0.f),1.f),fminf(fmaxf(view.f(Compact?10:18),0.f),1.f));
        if(dot(normal,-direction)<=0.f)return -1;
        const float exterior=ior(b,selectedVolume(s));
        float n1=view.u(Compact?15:29)==2u?view.f(Compact?13:25):exterior;
        float n2=view.u(Compact?15:29)==2u?exterior:view.f(Compact?13:25);
        int index=-1;bool entering=false;
        if(flags){
            if constexpr(S::SMALL)return -2;else{
                index=boundaryIndex(b,view.u(Compact?16:44));if(index<0)return -2;
                const Boundary entry=table(b)[index];
                if(view.u(Compact?17:45)!=(unsigned int)entry.priority||view.u(Compact?18:46)!=entry.orientation||flags!=entry.flags)return -2;
                auto candidate=s->volumes;entering=(view.u(Compact?15:29)==2u)==(entry.orientation!=0u);
                if(entering)for(unsigned int j=0;j<candidate.volumeIndexListSize;++j)if(candidate.volumeIndexList[j]==(unsigned int)index)return -2;
                const auto status=entering?rtr_medium::add(&candidate,index,priorities(b),h[7]):rtr_medium::remove(&candidate,index,priorities(b),h[7]);
                if(status!=rtr_medium::OK)return -2;
                n1=ior(b,s->volumes.currentVolumeIndex);n2=ior(b,candidate.currentVolumeIndex);
            }
        }
        const bool first=(s->counters.y&1u)==0u;bool reflection;float multiplier=transmission;
        // Record the decision even if this lobe has zero weight or terminates.
        // A branch may be replayed only when no branch-dependent choice occurred.
        s->counters.y|=4u;
        if(view.f(Compact?7:15)>0.f&&n1!=n2){
            reflection=first&&h[37]==0u?h[35]==0u:random(s->control.z,seed^h[35]*0x02e5be93u,s->counters.x)<.5f;
            if(!roughGlass(direction,normal,n1,n2,tint,view.f(Compact?7:15),
                random(s->control.z,seed^0xa511e9b3u,s->counters.x),random(s->control.z,seed^0x63d83595u,s->counters.x),reflection,&direction,&factor))return -3;
            factor*=multiplier;
        }else{
            float3 rd,td,rw,tw;float fresnel;
            if(!glass(direction,normal,n1,n2,tint,&rd,&td,&rw,&tw,&fresnel))return -3;
            if(first&&h[37]==0u){reflection=h[35]==0u;multiplier*=2.f;}
            else{reflection=random(s->control.z,seed^h[35]*0x02e5be93u,s->counters.x)<fresnel;
                const float probability=reflection?fresnel:1.f-fresnel;if(probability<=0.f){c->surfaceNormal.w=bound;return 0;}multiplier/=probability;}
            direction=reflection?rd:td;factor=(reflection?rw:tw)*multiplier;
        }
        mode=first?2u:3u;
        if(!reflection&&flags)c->continuationBoundary=make_uint4((unsigned int)index+1u,entering?1u:0u,0u,0u);
    }
    if(!finite3(factor)||!finite3(direction))return -4;
    if(peak(factor)<=0.f){c->surfaceNormal.w=bound;return 0;}
    c->surfaceNormal.w=bound;
    c->continuationDirection=make_float4(direction.x,direction.y,direction.z,__uint_as_float(mode));
    c->continuationWeight.x=factor.x;c->continuationWeight.y=factor.y;c->continuationWeight.z=factor.z;
    return 0;
}
__device__ float3 surfaceShading(const Connection*c,float3 shading){
    const unsigned int mode=__float_as_uint(c->continuationDirection.w);
    if((mode==5u||mode==6u)&&c->continuationBoundary.y){
        const float local=__uint_as_float(c->continuationBoundary.z);
        return shading*local+rgb(c->continuationWeight)*(1.f-local);
    }
    return shading;
}
template<class S> __device__ int continueSurface(unsigned char*b,S*s,const Connection*c,float3 shading){
    const unsigned int mode=__float_as_uint(c->continuationDirection.w);if(mode==0u)return 0;
    const float3 direction=rgb(c->continuationDirection);
    const float opacity=__uint_as_float(c->continuationBoundary.w);
    const float3 factor=mode==5u?make_float3(1.f-opacity)+shading*opacity
        :mode==6u?make_float3(1.f-opacity):mode==1u&&c->continuationBoundary.y==2u?make_float3(1.f):rgb(c->continuationWeight);
    if(mode==2u){const float3 outer=rgb(s->terminal)*factor;s->terminal.x=outer.x;s->terminal.y=outer.y;s->terminal.z=outer.z;}
    else{const float3 weight=rgb(s->throughput)*factor;s->throughput.x=weight.x;s->throughput.y=weight.y;s->throughput.z=weight.z;}
    if(mode==2u||mode==3u)s->counters.y|=1u;else if(mode==4u)s->counters.y|=2u;
    if(!finite3(rgb(s->throughput))||!finite3(rgb(s->terminal)))return -1;
    if(peak(rgb(s->throughput))*peak(rgb(s->terminal))<=((float*)b)[32])return 0;
    if constexpr(!S::SMALL)if(c->continuationBoundary.x!=0u){
        const auto status=c->continuationBoundary.y?rtr_medium::add(&s->volumes,c->continuationBoundary.x-1u,priorities(b),((unsigned int*)b)[7])
            :rtr_medium::remove(&s->volumes,c->continuationBoundary.x-1u,priorities(b),((unsigned int*)b)[7]);
        if(status!=rtr_medium::OK)return -2;
    }
    const float3 point=rgb(c->surfacePoint),next=spawnOriginBound(point,rgb(c->surfaceNormal),direction,c->surfaceNormal.w);
    const float epsilon=fmaxf(dot(next-point,direction),0.f);
    s->direction=make_float4(direction.x,direction.y,direction.z,0.f);
    bool collision=false;if(!segment(b,s,point,epsilon,&collision,false))return -1;
    s->origin=make_float4(next.x,next.y,next.z,epsilon);s->control.x=1u;++s->control.w;
    return peak(rgb(s->throughput))>((float*)b)[32]?1:0;
}
#ifndef RTR_OPTICAL_CAUSTIC_ONLY
__device__ bool connectionSegment(unsigned char*b,Connection*c,float distance){
    distance=fminf(fmaxf(distance,0.f),c->transport.w);
    const unsigned int selected=c->volumes.currentVolumeIndex;const float*h=(const float*)b;
    const float3 absorption=selected==0xffffffffu?make_float3(h[20],h[21],h[22]):rgb(table(b)[selected].absorption);
    const int descriptor=selected==0xffffffffu?((const int*)b)[38]:((const int*)&table(b)[selected].sigmaSAndDescriptor)[3];
    const float3 start=rgb(c->origin)+rgb(c->direction)*c->direction.w;
    const float mass=rtr_density::mass(b,descriptor,start,rgb(c->direction),distance,((unsigned int*)b)[39]);
    if(!isfinite(mass)||mass<0.f)return false;
    float3 tr,unused,zero=make_float3(0.f),extinction=absorption+sigmaS(b,selected);
    HomogeneousVolume_SegmentScatter(.5f,false,mass,&extinction,&zero,&zero,&tr,&unused);
    const float3 weight=rgb(c->transport)*tr;c->transport=make_float4(weight.x,weight.y,weight.z,fmaxf(c->transport.w-distance,0.f));
    return finite3(weight);
}
// Returns 0 for finished/blocked, 1 for another geometric segment, -1 for invalid state.
template<bool Compact,bool Transposed> __device__ int connectionStep(unsigned char*b,Connection*c,HitView<Compact,Transposed> view){
    const bool found=view.u(Compact?14:28)!=0u;
    const float distance=found?view.f(3):c->origin.w;
#ifdef RTR_OPTICAL_TRACE_PATH
    if((view.u(Compact?20:48)-((unsigned int*)b)[4])*((unsigned int*)b)[3]+view.u(Compact?21:49)==RTR_OPTICAL_TRACE_PATH)printf("OPTICAL_CONNECTION seed=%u sample=%u branch=%u ordinal=%u events=%u previousT=%.9g hitT=%.9g boundary=%u side=%u flags=%u volume=%u stackSize=%u origin=[%.9g,%.9g,%.9g] direction=[%.9g,%.9g,%.9g] point=[%.9g,%.9g,%.9g]\n",
            ((unsigned int*)b)[36],((unsigned int*)b)[34],((unsigned int*)b)[35],c->control.x,c->control.z,
            c->direction.w,distance,view.u(Compact?16:44),view.u(Compact?15:29),view.u(Compact?19:47),
            c->volumes.currentVolumeIndex,c->volumes.volumeIndexListSize,c->origin.x,c->origin.y,c->origin.z,
            c->direction.x,c->direction.y,c->direction.z,view.f(0),view.f(1),view.f(2));
#endif
    if(!isfinite(distance)||distance<c->direction.w)return -1;
    const float travel=fminf(distance,c->origin.w)-c->direction.w;
    if(!connectionSegment(b,c,travel))return -2;
    if(!found||distance>=c->origin.w)return 0;
    if(++c->control.z>=((unsigned int*)b)[6]){c->transport.x=c->transport.y=c->transport.z=0.f;return 0;}
    const unsigned int flags=view.u(Compact?19:47);
    const bool volumeOnly=(flags&2u)!=0u;
    rtr_medium::PathVolumeInfo candidate=c->volumes;
    if(flags){
        const int index=boundaryIndex(b,view.u(Compact?16:44));if(index<0)return -3;
        const Boundary entry=table(b)[index];
        if(view.u(Compact?17:45)!=(unsigned int)entry.priority||view.u(Compact?18:46)!=entry.orientation||flags!=entry.flags)return -4;
        const bool entering=(view.u(Compact?15:29)==2u)==(entry.orientation!=0u);
        if(entering)for(unsigned int j=0;j<candidate.volumeIndexListSize;++j)if(candidate.volumeIndexList[j]==(unsigned int)index)return -5;
        const auto status=entering?rtr_medium::add(&candidate,index,priorities(b),((unsigned int*)b)[7])
                :rtr_medium::remove(&candidate,index,priorities(b),((unsigned int*)b)[7]);
        if(status!=rtr_medium::OK)return -6;
    }
    if(!volumeOnly&&__float_as_uint(c->source.w)!=2u){
        const float opacity=flags?1.f:fminf(fmaxf(view.f(Compact?11:19),0.f),1.f);
        const float transmission=fminf(fmaxf(view.f(Compact?12:24),0.f),1.f);
        const float outside=ior(b,c->volumes.currentVolumeIndex);
        float n1=view.u(Compact?15:29)==2u?view.f(Compact?13:25):outside;
        float n2=view.u(Compact?15:29)==2u?outside:view.f(Compact?13:25);
        if(flags){n1=outside;n2=ior(b,candidate.currentVolumeIndex);}
        const float3 normal=normalize(make_float3(view.f(Compact?4:12),view.f(Compact?5:13),view.f(Compact?6:14)));
        float3 rd,td,rw,tw;float fresnel;
        if(!glass(rgb(c->direction),normal,n1,n2,WHITE,&rd,&td,&rw,&tw,&fresnel))return -7;
        const float3 tint=make_float3(fminf(fmaxf(view.f(Compact?8:16),0.f),1.f),fminf(fmaxf(view.f(Compact?9:17),0.f),1.f),fminf(fmaxf(view.f(Compact?10:18),0.f),1.f));
        // Existing direct-light contract: straight connection transmittance, not a bent light path.
        const float3 factor=make_float3(1.f-opacity)+tint*(opacity*transmission*(1.f-fresnel));
        const float3 weight=rgb(c->transport)*factor;c->transport.x=weight.x;c->transport.y=weight.y;c->transport.z=weight.z;
    }
    c->volumes=candidate;
    if(peak(rgb(c->transport))<=((float*)b)[32]){c->transport.x=c->transport.y=c->transport.z=0.f;return 0;}
    // The geometric hit point may include barycentric edge repair, and a
    // normal offset can cross another volume at a grazing/shared edge. Neither
    // may replace the light ray's origin. Vulkan resumes after this exact t;
    // the next segment integrates from this t without dropping an epsilon gap.
    c->direction.w=distance;
    return 1;
}
}
extern "C" __global__ void native_optical_reset(unsigned char*b,int count){if(blockIdx.x==0&&threadIdx.x==0){unsigned int*h=(unsigned int*)b;h[28]=h[29]=h[30]=h[31]=0;
    // The preceding Vulkan pass consumed the previous output queue. Retain
    // original path IDs in two disjoint queues; no state/radiance is reordered.
    if((h[47]&256u)!=0u){
        if(h[17]!=0u){h[52]=h[2];h[54]=0u;h[55]=h[50];}
        else{h[52]=h[53];h[54]=h[55];h[55]=h[55]==h[50]?h[51]:h[50];}
        h[53]=0u;
    }
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
template<bool Compact,bool Small,bool Transposed=false,bool TransposedState=false> __device__ __forceinline__ void rtr_optical_step_impl(unsigned char*b,int count,unsigned int*statistics){
    int i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=count)return;using namespace rtr_optical;
    unsigned int*h=(unsigned int*)b;
    if((h[47]&256u)!=0u){if((unsigned int)i>=h[52])return;if(h[54]!=0u)i=((unsigned int*)(b+h[54]))[i];}
    RTR_PHASE_SCOPE(b);
    RTR_ENTRY_SCOPE(b);
    StateAccess<Small,TransposedState> state(b,i,h[2]);auto*s=state.operator->();
    if((h[47]&4096u)!=0u&&h[17]!=0u&&h[35]==1u&&h[37]==0u&&s->control.x==4u){
        // The Vulkan producer retained the completed branch-zero state. Keep the
        // original second weighted addition, rather than merging its two weights.
        if(s->control.y!=0u||(s->counters.y&5u)!=0u||s->counters.z!=1u
                ||s->control.z!=global(b,i)){finish(b,i,s,statistics,18u);return;}
        finish(b,i,s,statistics);return;
    }
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
    if(h[19]!=0u&&(s->control.x==2u||s->control.x==3u)){
        Connection*c=connections(b)+i;
        HitView<Compact,Transposed> connectionView={(const unsigned int*)(b+h[10]),h[2],(unsigned int)i};
        if(connectionView.u(Compact?23:51)!=s->control.w){finish(b,i,s,statistics,17);return;}
        const unsigned int connectionMode=__float_as_uint(c->source.w);
        if((h[47]&16384u)!=0u&&connectionMode>=5u&&connectionMode<=11u){finish(b,i,s,statistics,32u+connectionMode-4u);return;}
        if(connectionMode==3u&&(h[47]&16384u)==0u){finish(b,i,s,statistics,42u);return;}
        int continuation=0;
        // Mode 2 is an unshadowed light: preserve unit visibility and skip the
        // complete surface/medium connection, matching direct-volume transport.
        if(__float_as_uint(c->source.w)==1u){
            const bool batched=(h[47]&8192u)!=0u;
            const unsigned int count=batched?connectionView.u(Compact?22:50):1u;
            if(batched&&(h[58]!=2u||h[57]==0u||count<1u||count>2u)){finish(b,i,s,statistics,40u);return;}
            for(unsigned int segmentIndex=0;segmentIndex<count;++segmentIndex){
                HitView<Compact,Transposed> segmentView=segmentIndex==0u?connectionView
                        :HitView<Compact,Transposed>{(const unsigned int*)(b+h[57]),h[2],(unsigned int)i};
                if(segmentView.u(Compact?23:51)!=s->control.w){finish(b,i,s,statistics,41u);return;}
                continuation=connectionStep(b,c,segmentView);
                if(continuation<=0)break;
                // Retain the old per-hit random/identity epoch even though this
                // segment no longer needs a kernel entry, queue or API handoff.
                ++s->control.w;
            }
        }
        if(continuation<0){finish(b,i,s,statistics,32u+(unsigned int)(-continuation));return;}
        if(continuation>0){s->control.x=3u;increment(b,statistics,0u);return;}
        float3 incoming=rgb(c->lighting);
        if(peak(rgb(c->source))>0.f){
            const float phase=c->control.w!=0u?1.f:rtr_medium::hgEvaluate(dot(rgb(s->direction),rgb(c->direction)),anisotropy(b,selectedVolume(s)));
            incoming=incoming+rgb(c->source)*rgb(c->transport)*phase;
        }
        if(!finite3(incoming)){finish(b,i,s,statistics,19);return;}
        c->lighting=make_float4(incoming.x,incoming.y,incoming.z,0.f);
        ++c->control.x;
        if(c->control.x<c->control.y){
            if constexpr(Small)rtr_medium::PathVolumeInfo_Init(&c->volumes);else c->volumes=s->volumes;
            s->control.x=2u;++s->control.w;increment(b,statistics,0u);return;
        }
        if(c->control.w!=0u)incoming=surfaceShading(c,incoming);
        const float3 value=rgb(s->radiance)+rgb(s->terminal)*rgb(s->throughput)*incoming*(c->control.w!=0u?c->continuationWeight.w:1.f);
        if(!finite3(value)){finish(b,i,s,statistics,20);return;}
        s->radiance=make_float4(value.x,value.y,value.z,0.f);
        if(c->control.w!=0u){
            const int reflected=continueSurface(b,s,c,incoming);
            if(reflected<0){finish(b,i,s,statistics,21);return;}
            if(reflected>0){increment(b,statistics,0u);return;}
            finish(b,i,s,statistics);return;
        }
        const unsigned int seed=h[36]^h[34]*0x967a889bu^s->control.w*0x9e3779b9u;
        float phasePdf;const float3 direction=rtr_medium::hgSample(rgb(s->direction),anisotropy(b,selectedVolume(s)),
                random(s->control.z,seed,2u),random(s->control.z,seed,3u),&phasePdf);
        if(!finite3(direction)||!isfinite(phasePdf)||phasePdf<=0.f){finish(b,i,s,statistics,16);return;}
        s->direction=make_float4(direction.x,direction.y,direction.z,0.f);s->control.x=1u;++s->control.w;
        if(peak(rgb(s->throughput))<=((float*)b)[32]||s->throughput.w<=0.f){finish(b,i,s,statistics);return;}
        increment(b,statistics,0u);return;
    }
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
#ifdef RTR_OPTICAL_TRACE_PATH
    if(s->control.z-h[4]*h[3]==RTR_OPTICAL_TRACE_PATH)
        printf("OPTICAL_MAIN seed=%u sample=%u branch=%u volume=%u origin=[%.9g,%.9g,%.9g] direction=[%.9g,%.9g,%.9g] hitT=%.9g boundary=%u side=%u point=[%.9g,%.9g,%.9g]\n",
                h[36],h[34],h[35],selectedVolume(s),s->origin.x,s->origin.y,s->origin.z,s->direction.x,s->direction.y,s->direction.z,
                distance,view.u(Compact?16:44),view.u(Compact?15:29),view.f(0),view.f(1),view.f(2));
#endif
    RTR_ENTRY_END();
    RTR_PHASE_NEXT(1u);
    const bool boundaryEnd=found&&view.u(Compact?19:47)!=0u;
    const float3 endPoint=make_float3(view.f(0),view.f(1),view.f(2));
    const float3 endNormal=make_float3(view.f(Compact?4:12),view.f(Compact?5:13),view.f(Compact?6:14));
    bool collision=false;if(!isfinite(distance)||distance<0||!segment(b,s,rgb(s->origin),distance,&collision,true,nullptr,
            boundaryEnd?&endPoint:nullptr,boundaryEnd?&endNormal:nullptr)){finish(b,i,s,statistics,3);return;}
    RTR_PHASE_NEXT(2u);
    if(collision){increment(b,statistics,0u);return;}
    float3 evaluation=rgb(((float4*)(b+h[11]))[i]);
    if(!finite3(evaluation)){finish(b,i,s,statistics,4);return;}
    if(!found){float3 value=rgb(s->radiance)+rgb(s->terminal)*rgb(s->throughput)*evaluation;s->radiance=make_float4(value.x,value.y,value.z,0);finish(b,i,s,statistics);return;}
    ++s->counters.x;
    unsigned int flags=view.u(Compact?19:47);bool volumeOnly=(flags&2u)!=0u;
    float opacity=flags?1.f:fminf(fmaxf(view.f(Compact?11:19),0.f),1.f),transmission=volumeOnly?1.f:fminf(fmaxf(view.f(Compact?12:24),0.f),1.f);
    if(h[19]!=0u&&((float4*)(b+h[11]))[i].w!=0.f&&!volumeOnly) {
        Connection*c=connections(b)+i;
        const float surfaceKind=((float4*)(b+h[11]))[i].w;
        const int prepared=prepareSurfaceContinuation<Compact>(b,s,view,c,surfaceKind);
        if(prepared<0){finish(b,i,s,statistics,40u+(unsigned int)(-prepared));return;}
        if(surfaceKind!=1.f&&surfaceKind!=3.f&&surfaceKind!=4.f){
            evaluation=surfaceShading(c,evaluation);
            const float3 value=rgb(s->radiance)+rgb(s->terminal)*rgb(s->throughput)*evaluation*c->continuationWeight.w;
            if(!finite3(value)){finish(b,i,s,statistics,20);return;}s->radiance=make_float4(value.x,value.y,value.z,0.f);
            const int continued=continueSurface(b,s,c,evaluation);
            if(continued<0){finish(b,i,s,statistics,21);return;}
            if(continued>0){increment(b,statistics,0u);return;}
            finish(b,i,s,statistics);return;
        }
        c->lighting=make_float4(evaluation.x,evaluation.y,evaluation.z,0.f);c->control=make_uint4(0u,0u,0u,1u);
        if constexpr(Small)rtr_medium::PathVolumeInfo_Init(&c->volumes);else c->volumes=s->volumes;
        s->origin=c->surfacePoint;s->control.x=2u;++s->control.w;increment(b,statistics,0u);return;
    }
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
        bool firstInterface=(s->counters.y&1u)==0u;
        s->counters.y|=4u;
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
        if(peak(factor)<=0.f){finish(b,i,s,statistics);return;}
        s->counters.y|=1u;
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
    if(!finite3(point)||!finite3(direction)){finish(b,i,s,statistics,12);return;}
    const float3 spawnNormal=normalize(make_float3(view.f(Compact?4:12),view.f(Compact?5:13),view.f(Compact?6:14)));
    const float3 next=spawnOrigin(point,spawnNormal,direction,
            positionError(point,rgb(s->origin),distance,rgb(s->direction)));
    const float epsilon=fmaxf(dot(next-point,direction),0.f);
    s->direction=make_float4(direction.x,direction.y,direction.z,0);
    RTR_PHASE_NEXT(1u);
    bool epsilonCollision=false;if(!segment(b,s,point,epsilon,&epsilonCollision,false)){finish(b,i,s,statistics,13);return;}
    RTR_PHASE_NEXT(6u);
    if(epsilonCollision){increment(b,statistics,0u);return;}
    s->origin=make_float4(next.x,next.y,next.z,epsilon);++s->control.w;
    if(peak(rgb(s->throughput))<=((float*)b)[32]){finish(b,i,s,statistics);return;}increment(b,statistics,0u);
}

namespace rtr_optical {
// PBRT wavefront queue principle: launch capacity, then process queued work
// only. One atomic reservation per participating warp keeps original path IDs.
// All lanes (including the final partial block) must enter this helper.
__device__ __forceinline__ void queueContinued(unsigned char*b,int count,unsigned int continued){
    unsigned int*h=(unsigned int*)b;if((h[47]&256u)==0u)return;
    const unsigned int slot=blockIdx.x*blockDim.x+threadIdx.x,lane=threadIdx.x&31u;
    const bool active=slot<(unsigned int)count&&slot<h[52]&&continued!=0u;
    const unsigned int mask=__ballot_sync(0xffffffffu,active);
    if(mask==0u)return;
    const int leader=__ffs(mask)-1;unsigned int base=0u;
    if(lane==(unsigned int)leader)base=atomicAdd(h+53,__popc(mask));
    base=__shfl_sync(0xffffffffu,base,leader);
    if(active){
        const unsigned int item=h[54]!=0u?((unsigned int*)(b+h[54]))[slot]:slot;
        const unsigned int rank=__popc(mask&((1u<<lane)-1u));
        ((unsigned int*)(b+h[55]))[base+rank]=item;
    }
}
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
    if((((unsigned int*)b)[47]&256u)!=0u&&blockIdx.x*blockDim.x>=((unsigned int*)b)[52])return;
    if(((unsigned int*)b)[46]==0u){if(i<count)rtr_optical_dispatch(b,count,nullptr);return;}
    unsigned int statistics[4]={0u,0u,0u,0u};
    if(i<count)rtr_optical_dispatch(b,count,statistics);
    rtr_optical::queueContinued(b,count,statistics[0]);
    rtr_optical::reduceStatistics(b,statistics);
}
// Independent entry points keep each layout's compiled resources separate.
template<bool Compact,bool Small,bool Transposed=false,bool TransposedState=false> __device__ __forceinline__ void rtr_optical_fixed(unsigned char*b,int count){
    const int i=blockIdx.x*blockDim.x+threadIdx.x;
    if((((unsigned int*)b)[47]&256u)!=0u&&blockIdx.x*blockDim.x>=((unsigned int*)b)[52])return;
    if(((unsigned int*)b)[46]==0u){if(i<count)rtr_optical_step_impl<Compact,Small,Transposed,TransposedState>(b,count,nullptr);return;}
    unsigned int statistics[4]={0u,0u,0u,0u};
    if(i<count)rtr_optical_step_impl<Compact,Small,Transposed,TransposedState>(b,count,statistics);
    rtr_optical::queueContinued(b,count,statistics[0]);
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
#else
} // namespace rtr_optical
#endif
