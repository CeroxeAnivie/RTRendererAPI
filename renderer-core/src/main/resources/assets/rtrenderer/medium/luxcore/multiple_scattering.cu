// RTRendererAPI bounded transport adapter. Pinned segment, HG and priority helpers precede this file.
namespace rtr_multiple {
struct __align__(16) State {
    float4 origin,direction,throughput,radiance,weight,hit,lighting;
    uint4 control,random;
};
static_assert(sizeof(State)==144,"multiple-scattering state ABI");
__device__ float3 rgb(float4 v){return make_float3(v.x,v.y,v.z);}
__device__ bool finite(float3 v){return isfinite(v.x)&&isfinite(v.y)&&isfinite(v.z);}
__device__ float peak(float3 v){return fmaxf(v.x,fmaxf(v.y,v.z));}
__device__ unsigned int hash(unsigned int v){v^=v>>16;v*=0x7feb352du;v^=v>>15;v*=0x846ca68bu;return v^(v>>16);}
__device__ float random(unsigned int seed){return (float)(hash(seed)>>8)*(1.f/16777216.f);}
__device__ State *states(unsigned char *b){return (State *)(b+((unsigned int *)b)[27]);}
__device__ void finish(unsigned char *b,State *s,unsigned int kind){s->control.x=0;s->random.w=kind;atomicAdd((unsigned int *)b+30,1u);}
__device__ void fail(unsigned char *b,State *s,unsigned int kind){s->control.x=0;s->random.w=kind;atomicAdd((unsigned int *)b+29,1u);}
__device__ float3 transmittance(float3 sigma,float distance){
    float3 tr,em,zero=make_float3(0.f);
    HomogeneousVolume_SegmentScatter(.5f,false,distance,&sigma,&zero,&zero,&tr,&em);return tr;
}
__device__ void pending(unsigned char *b,State *s){
    const float *h=(const float *)b;const unsigned int *u=(const unsigned int *)b;
    const unsigned int seed=hash(s->random.x^(s->random.y+1u)*0x9e3779b9u);s->random.z=seed;
    const unsigned int channel=min((unsigned int)(random(seed)*3.f),2u);
    const float sigma=h[12+channel];float target=1e30f;
    if(s->control.w<u[7]&&sigma>0.f&&s->origin.w>0.f){
        // The public estimator samples a uniformly selected extinction channel. Use the pinned
        // exponential sampler at unit rate in optical-distance coordinates, then map back. This
        // preserves that estimator without replacing HG by LuxCore's different Schlick phase.
        float3 one=make_float3(1.f),zero=make_float3(0.f),tr,em;
        const float optical=HomogeneousVolume_SegmentScatter(random(seed^0x68bc21ebu),true,sigma*s->origin.w,
                &zero,&one,&zero,&tr,&em);
        if(optical>=0.f)target=optical/sigma;
    }
    s->direction.w=target;
}
}
extern "C" __global__ void camera_multiple_reset(unsigned char *buffer,int count){
    if(blockIdx.x==0&&threadIdx.x==0){unsigned int *u=(unsigned int *)buffer;u[28]=u[29]=u[30]=u[31]=0;}
}
extern "C" __global__ void camera_multiple_init(unsigned char *buffer,int count){
    int i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=count)return;
    using namespace rtr_multiple;const unsigned int *u=(const unsigned int *)buffer;const float *h=(const float *)buffer;
    const unsigned int global=u[4]*u[6]+i,steps=u[3];
    const unsigned int primaryItem=u[8]>u[10]?(unsigned int)i:global;
    const float *p=(const float *)(buffer+u[8])+primaryItem*52u;
    const bool hit=((const unsigned int *)p)[28]!=0u;
    const float distance=hit?fminf(fmaxf(p[3],0.f),h[15]):h[15];
    const float interval=distance/(float)steps,reference=fminf(h[12],fminf(h[13],h[14]));
    const float3 sigma=make_float3(h[12],h[13],h[14]),scatter=make_float3(h[16],h[17],h[18]);
    const float4 *source=(const float4 *)(buffer+u[11])+(unsigned int)i*steps;
    for(unsigned int j=0;j<steps;++j){
        State *s=states(buffer)+(unsigned int)i*steps+j;const float start=j*interval,local=source[j].w;
        s->radiance=make_float4(0.f);s->throughput=make_float4(1.f,1.f,1.f,0.f);s->weight=make_float4(0.f);
        if(u[0]!=0x524d5331u||u[1]!=1u||steps<1u||steps>64u||u[7]<1u||u[7]>8u||u[32]!=144u
                ||s->control.y!=global||s->control.z!=1u||!finite(rgb(s->origin))||!finite(rgb(s->direction))){fail(buffer,s,100u);continue;}
        const float3 prefix=transmittance(sigma,start);
        if(!(interval>0.f)||peak(prefix)<h[23]){finish(buffer,s,1u);continue;}
        if(!isfinite(local)||local<0.f||local>interval*(1.f+1e-5f)){fail(buffer,s,101u);continue;}
        const float pdf=reference*interval<1e-3f?1.f/interval:
                expf(-reference*local)/rtrApiIntegratedEmission(reference,1.f,interval);
        const float3 weight=transmittance(sigma,start+local)*scatter/pdf;
        if(!finite(weight)){fail(buffer,s,102u);continue;}
        s->weight=make_float4(weight.x,weight.y,weight.z,0.f);
        float phasePdf;const float3 outgoing=rtr_medium::hgSample(rgb(s->direction),h[19],
                random(s->random.x),random(s->random.x^0xd3a2646cu),&phasePdf);
        s->direction=make_float4(outgoing.x,outgoing.y,outgoing.z,0.f);s->control.x=1u;
        pending(buffer,s);atomicAdd((unsigned int *)buffer+28,1u);
    }
}
extern "C" __global__ void camera_multiple_step(unsigned char *buffer,int count){
    int i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=count)return;
    using namespace rtr_multiple;State *s=states(buffer)+i;if(s->control.x==0u)return;
    const unsigned int *u=(const unsigned int *)buffer;const float *h=(const float *)buffer;
    if(((unsigned int *)&s->lighting)[3]!=s->control.z){fail(buffer,s,103u);return;}
    const bool hit=s->hit.w>=0.f;const float distance=hit?fminf(fmaxf(s->hit.w,0.f),s->origin.w):s->origin.w;
    const float3 sigma=make_float3(h[12],h[13],h[14]),scatter=make_float3(h[16],h[17],h[18]);
    float3 throughput=rgb(s->throughput),radiance=rgb(s->radiance);
    if(!isfinite(distance)||distance<0.f||!finite(rgb(s->hit))||!finite(throughput)){fail(buffer,s,104u);return;}
    // As in the public transport equation, source integration is the separate deterministic
    // full-segment term, while the collision estimator continues the scattering term.
    radiance+=throughput*make_float3(rtrApiIntegratedEmission(sigma.x,h[20],distance),
            rtrApiIntegratedEmission(sigma.y,h[21],distance),rtrApiIntegratedEmission(sigma.z,h[22],distance));
    const bool sampleCollision=s->control.w<u[7]&&peak(scatter)>0.f;
    if(sampleCollision&&s->direction.w<distance){
        const float target=s->direction.w;const float3 tr=transmittance(sigma,target);
        const float pdf=sigma.x*tr.x*(1.f/3.f)+sigma.y*tr.y*(1.f/3.f)+sigma.z*tr.z*(1.f/3.f);
        if(!(pdf>0.f)){s->radiance=make_float4(radiance.x,radiance.y,radiance.z,0.f);finish(buffer,s,2u);return;}
        throughput*=tr*scatter/pdf;
        if(!finite(rgb(s->lighting))){fail(buffer,s,105u);return;}
        radiance+=throughput*rgb(s->lighting);
        const float3 point=rgb(s->origin)+rgb(s->direction)*target;
        s->origin=make_float4(point.x,point.y,point.z,fmaxf(s->origin.w-target,0.f));
        float phasePdf;const float3 outgoing=rtr_medium::hgSample(rgb(s->direction),h[19],
                random(s->random.z^0x27d4eb2du),random(s->random.z^0x165667b1u),&phasePdf);
        s->direction=make_float4(outgoing.x,outgoing.y,outgoing.z,0.f);++s->control.w;
        atomicAdd((unsigned int *)buffer+31,1u);
        s->throughput=make_float4(throughput.x,throughput.y,throughput.z,0.f);
        s->radiance=make_float4(radiance.x,radiance.y,radiance.z,0.f);
        if(!finite(throughput)||!finite(radiance)){fail(buffer,s,106u);return;}
        if(peak(throughput)<=h[23]){finish(buffer,s,3u);return;}
        ++s->control.z;++s->random.y;
        if(s->random.y>=u[34]+u[7]+1u){finish(buffer,s,4u);return;}
        pending(buffer,s);atomicAdd((unsigned int *)buffer+28,1u);
    }else{
        const float3 tr=transmittance(sigma,distance);
        if(sampleCollision){
            const float survival=(tr.x+tr.y+tr.z)*(1.f/3.f);
            if(!(survival>0.f)){s->radiance=make_float4(radiance.x,radiance.y,radiance.z,0.f);finish(buffer,s,5u);return;}
            throughput*=tr/survival;
        }else throughput*=tr;
        radiance+=throughput*rgb(s->hit);
        s->radiance=make_float4(radiance.x,radiance.y,radiance.z,0.f);
        if(!finite(radiance)){fail(buffer,s,107u);return;}finish(buffer,s,hit?6u:7u);
    }
}
extern "C" __global__ void camera_multiple_reduce(unsigned char *buffer,int count){
    int i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=count)return;
    using namespace rtr_multiple;const unsigned int *u=(const unsigned int *)buffer;
    const unsigned int global=u[4]*u[6]+i;
    const unsigned int primaryItem=u[8]>u[10]?(unsigned int)i:global;
    const float *primary=(const float *)(buffer+u[8])+primaryItem*52u;
    const float4 *source=(const float4 *)(buffer+u[11])+(unsigned int)i*u[3];
    float4 tr,em;
    if(!rtr_camera_scattering::resolve(primary,source,u[3],(const float *)buffer,&tr,&em)){atomicAdd((unsigned int *)buffer+29,1u);return;}
    float3 total=rgb(em);
    for(unsigned int j=0;j<u[3];++j){const State *s=states(buffer)+(unsigned int)i*u[3]+j;
        if(s->control.x!=0u){atomicAdd((unsigned int *)buffer+29,1u);return;}total+=rgb(s->weight)*rgb(s->radiance);}
    if(!finite(total)){atomicAdd((unsigned int *)buffer+29,1u);return;}
    ((float4 *)(buffer+u[9]))[global]=tr;
    ((float4 *)(buffer+u[10]))[global]=make_float4(total.x,total.y,total.z,0.f);
}
