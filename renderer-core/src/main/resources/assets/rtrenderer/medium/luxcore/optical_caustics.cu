// Light/importance paths share the camera integrator's strict medium stack and pinned samplers.
// Vulkan writes the complete 208-byte geometric hit for each active path between step launches.
namespace rtr_optical_caustic {
using namespace rtr_optical;
struct __align__(16) PhotonState {State<false> path;uint4 metadata;};
struct __align__(16) Photon {float4 position,flux,incoming,normal;};
static_assert(sizeof(PhotonState)==272,"optical photon state ABI");
static_assert(sizeof(Photon)==64,"optical photon record ABI");
__device__ unsigned int hash(int3 cell){
    unsigned int v=(unsigned int)cell.x*0x8da6b343u^(unsigned int)cell.y*0xd8163841u^(unsigned int)cell.z*0xcb1ab31fu;
    v^=v>>16;v*=0x7feb352du;v^=v>>15;v*=0x846ca68bu;return v^(v>>16);
}
__device__ int3 cell(unsigned char*m,float3 p){const float*h=(const float*)m;return make_int3(
    (int)floorf((p.x-h[16])/h[12]),(int)floorf((p.y-h[17])/h[12]),(int)floorf((p.z-h[18])/h[12]));}
__device__ bool inRegion(unsigned char*m,float3 p){const float*h=(const float*)m;float3 d=p-make_float3(h[16],h[17],h[18]);return dot(d,d)<h[13]*h[13];}
__device__ bool deposit(unsigned char*m,PhotonState*s,unsigned int path,float3 p,float3 incoming,float3 flux,
        float3 normal,unsigned int slot,unsigned int kind){
    unsigned int*h=(unsigned int*)m;if(s->metadata.x>=h[3]||!finite3(flux)||!finite3(p))return false;
    if(!inRegion(m,p)||peak(flux)<=0.f)return true;
    unsigned int index=path*h[3]+s->metadata.x++,bucket=hash(cell(m,p))&h[8];
    Photon*record=(Photon*)(m+h[6])+index;
    record->position=make_float4(p.x,p.y,p.z,0.f);record->flux=make_float4(flux.x,flux.y,flux.z,__uint_as_float(slot));
    record->incoming=make_float4(incoming.x,incoming.y,incoming.z,0.f);
    record->normal=make_float4(normal.x,normal.y,normal.z,__uint_as_float(kind));
    // All publishers finish before gather. The atomic is only a collision-safe linked-list insertion.
    unsigned int next=atomicExch(((unsigned int*)(m+h[7]))+bucket,index);record->position.w=__uint_as_float(next);return true;
}
__device__ void reject(unsigned char*m,PhotonState*s,unsigned int code){s->path.control.x=0;s->path.control.y=code;atomicAdd(((unsigned int*)m)+20,1u);}
__device__ bool initialize(unsigned char*b,PhotonState*s){
    auto*p=&s->path;unsigned int count=p->volumes.volumeIndexListSize;
    if(count>32u)return false;unsigned int media[32];for(unsigned int j=0;j<count;j++)media[j]=p->volumes.volumeIndexList[j];
    rtr_medium::PathVolumeInfo_Init(&p->volumes);
    for(unsigned int j=0;j<count;j++){for(unsigned int k=0;k<j;k++)if(media[k]==media[j])return false;
        if(rtr_medium::add(&p->volumes,media[j],priorities(b),((unsigned int*)b)[7])!=rtr_medium::OK)return false;}
    p->counters.z=1u;return true;
}
__device__ void step(unsigned char*b,unsigned char*m,unsigned int i){
    unsigned int*h=(unsigned int*)m;PhotonState*s=(PhotonState*)(m+h[4])+i;auto*p=&s->path;
    if(!p->control.x)return;
    if(h[0]!=0x524f4331u||h[1]!=1u){reject(m,s,1u);return;}
    if(!p->counters.z&&!initialize(b,s)){reject(m,s,2u);return;}
    if(p->counters.x++>=h[10]){p->control.x=0;return;}
    const unsigned int*raw=(const unsigned int*)(m+h[5])+i*52u;
    if(raw[48]!=i||raw[51]!=p->control.w){reject(m,s,3u);return;}
    HitView<false,false> hit={raw,1u,0u};bool found=raw[28]!=0u,collision=false;
    float distance=found?hit.f(3):p->throughput.w;
    if(!isfinite(distance)||distance<0.f){reject(m,s,4u);return;}
    const unsigned int selected=selectedVolume(p);const float remaining=p->throughput.w;
    // segment performs spectral distance sampling, density integration and exact throughput/pdf weighting.
    float3 incident=make_float3(0);
    if(!segment(b,p,rgb(p->origin),distance,&collision,p->counters.w<h[11]&&p->counters.w<((unsigned int*)b)[44]+1u,&incident)){reject(m,s,5u);return;}
    s->metadata.z=__float_as_uint(__uint_as_float(s->metadata.z)+remaining-p->throughput.w);
    const float traveled=__uint_as_float(s->metadata.z),range=__uint_as_float(s->metadata.w);
    const float policy=range>0.f?powf(fmaxf(1.f-traveled/range,0.f),2.f)*fminf(traveled*traveled,1.f):1.f;
    if(collision){
        const unsigned int slot=selected==0xffffffffu?0xffffffffu:table(b)[selected].slot;
        if(s->metadata.y&& !deposit(m,s,i,rgb(p->origin),-rgb(p->direction),incident*policy,make_float3(0),slot,1u)){reject(m,s,6u);return;}
        float pdf;const unsigned int seed=h[9]^p->control.w*0x9e3779b9u;
        const float3 out=rtr_medium::hgSample(rgb(p->direction),anisotropy(b,selected),random(i,seed,2u),random(i,seed,3u),&pdf);
        if(!finite3(out)||!isfinite(pdf)||pdf<=0.f){reject(m,s,7u);return;}
        p->direction=make_float4(out.x,out.y,out.z,0);p->control.x=1u;++p->control.w;
        return;
    }
    if(!found||distance>remaining||peak(rgb(p->throughput))<=0.f){p->control.x=0;return;}
    unsigned int flags=raw[47];bool volumeOnly=(flags&2u)!=0u;auto candidate=p->volumes;
    if(flags){int index=boundaryIndex(b,raw[44]);if(index<0){reject(m,s,8u);return;}
        Boundary boundary=table(b)[index];if(raw[45]!=(unsigned int)boundary.priority||raw[46]!=boundary.orientation||flags!=boundary.flags){reject(m,s,9u);return;}
        bool entering=(raw[29]==2u)==(boundary.orientation!=0u);
        if(entering)for(unsigned int j=0;j<candidate.volumeIndexListSize;j++)if(candidate.volumeIndexList[j]==(unsigned int)index){reject(m,s,10u);return;}
        auto status=entering?rtr_medium::add(&candidate,index,priorities(b),((unsigned int*)b)[7]):rtr_medium::remove(&candidate,index,priorities(b),((unsigned int*)b)[7]);
        if(status!=rtr_medium::OK){reject(m,s,11u);return;}}
    float3 incoming=rgb(p->direction),out=incoming,normal=normalize(make_float3(hit.f(12),hit.f(13),hit.f(14)));
    if(volumeOnly)p->volumes=candidate;
    else {
        float transmission=fminf(fmaxf(hit.f(24),0.f),1.f),opacity=fminf(fmaxf(hit.f(19),0.f),1.f);
        if(transmission<=0.f){
            const float metallic=fminf(fmaxf(hit.f(23),0.f),1.f);
            // Public shading-model ABI: PHYSICALLY_BASED is zero at bits 7..8.
            if(((raw[30]>>7u)&3u)==0u){
                const unsigned int seed=h[9]^p->control.w*0x967a889bu;
                const float ci=fmaxf(dot(normal,-incoming),0.f);
                const float dielectric=.04f+.96f*powf(1.f-ci,5.f);
                const float diffuseProbability=(1.f-metallic)*(1.f-dielectric);
                if(random(i,seed,0u)<diffuseProbability){
                    if(s->metadata.y&&!deposit(m,s,i,make_float3(hit.f(0),hit.f(1),hit.f(2)),-incoming,
                            rgb(p->throughput)*(policy/diffuseProbability),normal,raw[44],0u)){reject(m,s,12u);return;}
                    p->control.x=0;return;
                }
                // Photons sample the complete opaque specular lobe, including the
                // dielectric F0 of a nonmetal. The camera's extra reflection is
                // metallic-only; use unit sampling weight with the actual F0 here.
                Connection surface={};surface.surfacePoint.w=1.f;
                surface.surfaceNormal=make_float4(normal.x,normal.y,normal.z,random(i,seed,1u));
                surface.surfaceView=make_float4(-incoming.x,-incoming.y,-incoming.z,random(i,seed,2u));
                const float3 f0=make_float3(.04f)*(1.f-metallic)+make_float3(hit.f(16),hit.f(17),hit.f(18))*metallic;
                surface.surfaceMaterial=make_float4(f0.x,f0.y,f0.z,hit.f(15));
                const float3 factor=metalReflection(&surface,&out)*(1.f/(1.f-diffuseProbability));
                const float3 flux=rgb(p->throughput)*factor;
                if(!finite3(flux)){reject(m,s,18u);return;}
                if(peak(flux)<=0.f){p->control.x=0;return;}
                p->throughput.x=flux.x;p->throughput.y=flux.y;p->throughput.z=flux.z;s->metadata.y=1u;
            }else{
                // First non-specular receiver terminates a caustic chain; direct photons are excluded.
                if(s->metadata.y&&!deposit(m,s,i,make_float3(hit.f(0),hit.f(1),hit.f(2)),-incoming,rgb(p->throughput)*policy,normal,raw[44],0u)){reject(m,s,12u);return;}
                p->control.x=0;return;
            }
        }else{
        if(opacity<1.f){reject(m,s,13u);return;}
        float n1=raw[29]==2u?hit.f(25):ior(b,selected),n2=raw[29]==2u?ior(b,selected):hit.f(25);
        if(flags){n1=ior(b,p->volumes.currentVolumeIndex);n2=ior(b,candidate.currentVolumeIndex);}
        float3 tint=make_float3(hit.f(16),hit.f(17),hit.f(18)),weight;
        const unsigned int seed=h[9]^p->control.w*0x967a889bu;
        bool reflected;
        if(hit.f(15)>0.f&&n1!=n2){
            reflected=random(i,seed,0u)<.5f;
            if(!roughGlass(incoming,normal,n1,n2,tint,hit.f(15),random(i,seed,1u),random(i,seed,2u),reflected,&out,&weight)){reject(m,s,14u);return;}
            // The shared sampler is radiance transport; photon importance cancels eta squared.
            if(!reflected)weight*=((n2/n1)*(n2/n1));
        }else{float3 rd,td,rw,tw;float f;
            if(!glass(incoming,normal,n1,n2,tint,&rd,&td,&rw,&tw,&f)){reject(m,s,15u);return;}
            reflected=random(i,seed,0u)<f;float probability=reflected?f:1.f-f;
            if(probability<=0.f){p->control.x=0u;return;}out=reflected?rd:td;weight=(reflected?rw:tw)*(1.f/probability);
            if(!reflected)weight*=((n2/n1)*(n2/n1));
        }
        weight*=transmission;if(peak(weight)<=0.f){p->control.x=0;return;}
        float3 flux=rgb(p->throughput)*weight;if(!finite3(flux)){reject(m,s,16u);return;}
        p->throughput.x=flux.x;p->throughput.y=flux.y;p->throughput.z=flux.z;
        if(!reflected&&flags)p->volumes=candidate;s->metadata.y|=n1!=n2?1u:0u;
        }
    }
    float3 point=make_float3(hit.f(0),hit.f(1),hit.f(2));
    const float3 next=spawnOrigin(point,normal,out,positionError(point,rgb(p->origin),distance,incoming));
        p->direction=make_float4(out.x,out.y,out.z,0.f);
    bool ignored=false;const float advance=fmaxf(dot(next-point,out),0.f);
    if(!segment(b,p,point,advance,&ignored,false)){reject(m,s,17u);return;}
    p->origin=make_float4(next.x,next.y,next.z,advance);
    ++p->control.w;p->control.x=p->throughput.w>0.f&&p->counters.x<h[10]?1u:0u;
}
// Normalized Epanechnikov sphere density kernel; records contain incident fluence.
__device__ float volumeKernel(float squaredDistance,float radius){
    float q=squaredDistance/(radius*radius);return q<1.f?15.f*(1.f-q)/(8.f*3.14159265358979323846f*radius*radius*radius):0.f;
}
}
extern "C" __global__ void native_optical_photon_clear(unsigned char*m,int count){
    unsigned int*h=(unsigned int*)m;unsigned int i=blockIdx.x*blockDim.x+threadIdx.x;
    if(i<=h[8])((unsigned int*)(m+h[7]))[i]=0xffffffffu;if(i==0u)h[20]=0u;
}
extern "C" __global__ void native_optical_photon_step(unsigned char*m,int count){
    unsigned int i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=(unsigned int)count)return;
    rtr_optical_caustic::step((unsigned char*)((unsigned long long*)m)[12],m,i);
    auto*s=(rtr_optical_caustic::PhotonState*)(m+((unsigned int*)m)[4])+i;
    if(s->path.counters.x>=((unsigned int*)m)[10])s->path.control.x=0;
    if(s->path.control.x)atomicAdd(((unsigned int*)m)+21,1u);
}
extern "C" __global__ void native_optical_photon_reset(unsigned char*m,int count){if(blockIdx.x==0&&threadIdx.x==0)((unsigned int*)m)[21]=0u;}
// Query ABI: position.xyz/type, view-direction.xyz/medium-slot, normal.xyz/surface-slot,
// diffuse-color.xyz/anisotropy, result.xyz/query metadata. Results are irradiance/pi for
// Lambertian surface receivers and phase-weighted in-scattered radiance for volume receivers.
namespace rtr_optical_caustic {
struct __align__(16) Gather {float4 position,view,normal,material,result;};
__device__ void gather(unsigned char*m,Gather*q){
    const unsigned int*h=(const unsigned int*)m;const float radius=((float*)m)[12],threshold=((float*)m)[14];
    const float3 point=rgb(q->position),view=rgb(q->view),normal=rgb(q->normal);
    float3 result=make_float3(0);int3 center=cell(m,point);const unsigned int capacity=h[2]*h[3];
    const unsigned int kind=__float_as_uint(q->position.w),slot=__float_as_uint(q->view.w);
    for(int z=-1;z<=1;z++)for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++){
        int3 wanted=make_int3(center.x+x,center.y+y,center.z+z);unsigned int index=((unsigned int*)(m+h[7]))[hash(wanted)&h[8]];
        for(unsigned int visited=0;index<capacity&&visited<capacity;visited++){
            const Photon r=((Photon*)(m+h[6]))[index];const unsigned int next=__float_as_uint(r.position.w);
            const float3 p=rgb(r.position),delta=p-point;const int3 actual=cell(m,p);
            if(actual.x==wanted.x&&actual.y==wanted.y&&actual.z==wanted.z
                    &&__float_as_uint(r.normal.w)==kind&&__float_as_uint(r.flux.w)==slot){
                float squared=dot(delta,delta),weight=0.f;
                if(kind==1u)weight=volumeKernel(squared,radius)*rtr_medium::hgEvaluate(dot(-rgb(r.incoming),view),q->material.w);
                else if(dot(rgb(r.normal),normal)>=threshold&&fabsf(dot(delta,normal))<=radius*.1f
                        &&dot(normal,rgb(r.incoming))>0.f&&squared<radius*radius)
                    weight=2.f*(1.f-squared/(radius*radius))/(3.14159265358979323846f*radius*radius);
                result=result+rgb(r.flux)*weight;
            }
            index=next;
        }
    }
    if(kind==0u)result=result*rgb(q->material)*(1.f/3.14159265358979323846f);
    q->result=make_float4(result.x,result.y,result.z,0.f);
}
}
extern "C" __global__ void native_optical_photon_gather(unsigned char*m,int count){
    unsigned int i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=(unsigned int)count)return;
    rtr_optical_caustic::gather(m,((rtr_optical_caustic::Gather*)((unsigned long long*)m)[13])+i);
}
