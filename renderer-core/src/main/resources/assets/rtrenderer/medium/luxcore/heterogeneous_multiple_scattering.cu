// RTRendererAPI density-aware finite-order continuation adapter.
// The pinned LuxCore segment function and density-grid adapter precede this file.
// Spatial quadrature uses the public scatteringSteps; no host ray/state readback is used.
namespace rtr_heterogeneous_multiple {
struct __align__(16) State {
    float4 origin,direction,throughput,radiance,weight,hit,lighting;
    uint4 control,random;
};
struct __align__(16) Connection { float4 radianceAndDistance,direction; };
static_assert(sizeof(State)==144 && sizeof(Connection)==32,"heterogeneous continuation ABI");
__device__ float3 rgb(float4 v){return make_float3(v.x,v.y,v.z);}
__device__ bool finite(float3 v){return isfinite(v.x)&&isfinite(v.y)&&isfinite(v.z);}
__device__ float peak(float3 v){return fmaxf(v.x,fmaxf(v.y,v.z));}
__device__ unsigned int hash(unsigned int v){v^=v>>16;v*=0x7feb352du;v^=v>>15;v*=0x846ca68bu;return v^(v>>16);}
__device__ float random(unsigned int v){return (float)(hash(v)>>8)*(1.f/16777216.f);}
__device__ State *states(unsigned char*b){return (State*)(b+((unsigned int*)b)[41]);}
__device__ float3 sigma(unsigned char*b){float*h=(float*)b;return make_float3(h[12],h[13],h[14]);}
__device__ float3 scattering(unsigned char*b){float*h=(float*)b;return make_float3(h[16],h[17],h[18]);}
__device__ float3 tr(float3 sigma,float mass){float3 value,ignored,zero=make_float3(0.f);
    HomogeneousVolume_SegmentScatter(.5f,false,mass,&sigma,&zero,&zero,&value,&ignored);return value;}
__device__ float3 emission(unsigned char*b,float mass){float*h=(float*)b;return make_float3(
    rtrApiIntegratedEmission(h[12],h[20],mass),rtrApiIntegratedEmission(h[13],h[21],mass),rtrApiIntegratedEmission(h[14],h[22],mass));}
__device__ void finish(unsigned char*b,State*s,unsigned int reason){s->control.x=0;s->random.w=reason;atomicAdd((unsigned int*)b+30,1u);}
__device__ void fail(unsigned char*b,State*s,unsigned int reason){s->control.x=0;s->random.w=reason;atomicAdd((unsigned int*)b+29,1u);}
__device__ bool header(unsigned char*b){unsigned int*u=(unsigned int*)b;return u[0]==0x52484d31u&&u[1]==1u
    &&u[3]>=1u&&u[3]<=64u&&u[6]>=1u&&u[6]<=8u&&u[7]>=1u&&u[7]<=8u&&u[32]==144u&&u[43]>=1u&&u[43]<=9u;}
__device__ bool domain(unsigned char*b,float3 origin,float3 direction,float maximum,float3*p,float3*v,float*first,float*last){
    const rtr_density::Descriptor&grid=*(const rtr_density::Descriptor*)(b+((unsigned int*)b)[40]);
    *p=rtr_density::map(grid,origin,1.f);*v=rtr_density::map(grid,direction,0.f);*first=0.f;*last=maximum;
    if(!finite(*p)||!finite(*v)||!isfinite(maximum)||maximum<0.f)return false;
    return maximum>0.f&&rtr_density::clipAxis(p->x,v->x,first,last)&&rtr_density::clipAxis(p->y,v->y,first,last)
        &&rtr_density::clipAxis(p->z,v->z,first,last)&&*last>*first;
}
// Use one fixed clipped partition for both the sampled free path and any nearer geometry hit.
// Repartitioning after the hit would mismatch the survival PDF for a nonuniform density field.
__device__ float segmentMass(unsigned char*b,const State*s,float distance){
    float3 p,v;float first,last;if(!domain(b,rgb(s->origin),rgb(s->direction),s->origin.w,&p,&v,&first,&last))return 0.f;
    const unsigned int*u=(unsigned int*)b;const rtr_density::Descriptor&grid=*(const rtr_density::Descriptor*)(b+u[40]);
    const float*pixels=(const float*)(b+grid.atlasOffset);const float interval=(last-first)/(float)u[3];
    float mass=0.f;distance=fminf(fmaxf(distance,0.f),s->origin.w);
    for(unsigned int j=0;j<u[3];++j){float start=first+j*interval,dt=fminf(interval,distance-start);if(dt<=0.f)break;
        float density=rtr_density::DensityGridTexture_ConstEvaluate(pixels,grid,p+v*(start+.5f*interval));
        mass+=density*dt;}
    return mass;
}
__device__ void pending(unsigned char*b,State*s){
    const unsigned int*u=(unsigned int*)b;float*h=(float*)b;
    const unsigned int seed=hash(s->random.x^(s->random.y+1u)*0x9e3779b9u);s->random.z=seed;
    s->direction.w=1e30f;s->throughput.w=0.f;
    if(s->control.w>=u[7]||s->origin.w<=0.f)return;
    const unsigned int channel=min((unsigned int)(random(seed)*3.f),2u);const float rate=h[12+channel];if(rate<=0.f)return;
    float3 p,v;float first,last;if(!domain(b,rgb(s->origin),rgb(s->direction),s->origin.w,&p,&v,&first,&last))return;
    const rtr_density::Descriptor&grid=*(const rtr_density::Descriptor*)(b+u[40]);const float*pixels=(const float*)(b+grid.atlasOffset);
    // Sample the pinned LuxCore unit-rate segment in optical mass coordinates.
    // weight.w is scratch: retaining the sampled mass avoids a second density traversal
    // at the collision in step(), while weight.rgb retains the camera integral weight.
    float3 one=make_float3(1.f),zero=make_float3(0.f),attenuation,source;
    const float optical=HomogeneousVolume_SegmentScatter(random(seed^0x68bc21ebu),true,1e30f,
        &zero,&one,&zero,&attenuation,&source);
    if(!isfinite(optical)||optical<0.f){s->direction.w=nanf("");return;}
    const float interval=(last-first)/(float)u[3];float target=optical/rate;s->weight.w=target;
    for(unsigned int j=0;j<u[3];++j){const float start=first+j*interval;
        const float density=rtr_density::DensityGridTexture_ConstEvaluate(pixels,grid,p+v*(start+.5f*interval));
        if(!isfinite(density)||density<0.f){s->direction.w=nanf("");return;}
        const float mass=density*interval;
        if(density>0.f&&target<mass){s->direction.w=start+target/density;s->throughput.w=density;return;}target-=mass;
    }
}
__device__ void clearLighting(State*s){s->lighting=make_float4(0.f);((unsigned int*)&s->lighting)[3]=s->control.z;}
}
extern "C" __global__ void heterogeneous_multiple_reset(unsigned char*b,int count){
    if(blockIdx.x==0&&threadIdx.x==0){unsigned int*u=(unsigned int*)b;u[28]=u[30]=u[31]=0;}}
extern "C" __global__ void heterogeneous_multiple_prepare(unsigned char*b,int count){
    int i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=count)return;using namespace rtr_heterogeneous_multiple;
    unsigned int*u=(unsigned int*)b;float*h=(float*)b;const unsigned int steps=u[3],global=u[4]*u[6]+i;
    State*firstState=states(b)+(unsigned int)i*steps;const float3 origin=rgb(firstState->origin),direction=rgb(firstState->direction);
    const unsigned int primaryItem=u[8]>u[10]?(unsigned int)i:global;
    const float*primary=(const float*)(b+u[8])+primaryItem*52u;const bool hit=((const unsigned int*)primary)[28]!=0u;
    const float distance=hit?fminf(fmaxf(primary[3],0.f),h[15]):h[15];
    if(!header(b)||!finite(origin)||!finite(direction)||!isfinite(distance)||distance<0.f){atomicAdd(u+29,1u);return;}
    float3 p,v;float first,last;const bool inside=domain(b,origin,direction,distance,&p,&v,&first,&last);
    const rtr_density::Descriptor&grid=*(const rtr_density::Descriptor*)(b+u[40]);const float*pixels=(const float*)(b+grid.atlasOffset);
    const float interval=inside?(last-first)/(float)steps:0.f;float densities[64],mass=0.f;
    for(unsigned int j=0;j<steps;++j){densities[j]=inside?rtr_density::DensityGridTexture_ConstEvaluate(pixels,grid,p+v*(first+(j+.5f)*interval)):0.f;mass+=densities[j]*interval;}
    if(!isfinite(mass)||mass<0.f){atomicAdd(u+29,1u);return;}
    const float3 attenuation=tr(sigma(b),mass),source=emission(b,mass);
    ((float4*)(b+u[9]))[global]=make_float4(attenuation.x,attenuation.y,attenuation.z,1.f);
    ((float4*)(b+u[10]))[global]=make_float4(source.x,source.y,source.z,0.f);
    const float reference=fminf(h[12],fminf(h[13],h[14]));const unsigned int seed=hash((global/u[6])^u[33]*0x9e3779b9u);
    float prefixMass=0.f;
    for(unsigned int j=0;j<steps;++j){State*s=firstState+j;s->radiance=make_float4(0.f);s->weight=make_float4(0.f);
        s->throughput=make_float4(1.f,1.f,1.f,0.f);s->control=make_uint4(0u,global,1u,0u);
        s->random=make_uint4(seed^(j+1u)*0x165667b1u,0u,0u,0u);clearLighting(s);
        const float density=densities[j],effective=reference*density,tau=effective*interval;
        if(density<=0.f||interval<=0.f)continue;
        const float3 prefix=tr(sigma(b),prefixMass);if(peak(prefix)<h[23])continue;
        const float uniform=random(seed^(j+1u)*0x27d4eb2du);
        const float local=tau<1e-3f?uniform*interval:-logf(fmaxf(1.f-uniform*(1.f-expf(-tau)),1e-30f))/effective;
        const float pdf=tau<1e-3f?1.f/interval:expf(-effective*local)/rtrApiIntegratedEmission(effective,1.f,interval);
        const float at=first+j*interval+local;const float3 position=origin+direction*at;
        const float3 weight=tr(sigma(b),prefixMass+density*local)*scattering(b)*(density/pdf);
        prefixMass+=density*interval;
        s->origin=make_float4(position.x,position.y,position.z,fmaxf(h[15]-at,0.f));
        s->direction=make_float4(direction.x,direction.y,direction.z,0.f);s->weight=make_float4(weight.x,weight.y,weight.z,0.f);
        if(!finite(position)||!finite(weight)){fail(b,s,201u);continue;}s->control.x=1u;
    }
}
extern "C" __global__ void heterogeneous_multiple_connections(unsigned char*b,int count){
    int i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=count)return;using namespace rtr_heterogeneous_multiple;
    unsigned int*u=(unsigned int*)b;State*s=states(b)+i;if(!s->control.x)return;
    const Connection*connections=(const Connection*)(b+u[42])+(unsigned int)i*u[43];float3 incoming=rgb(s->lighting);
    for(unsigned int k=0;k<u[43];++k){const Connection&c=connections[k];const float3 source=rgb(c.radianceAndDistance);
        if(((const unsigned int*)&c.direction)[3]!=s->control.z||!finite(source)||!isfinite(c.radianceAndDistance.w)){fail(b,s,202u);return;}
        if(peak(source)<=0.f)continue;float mass=0.f;
        if(c.radianceAndDistance.w>=0.f){const float3 direction=rgb(c.direction);
            const float3 position=rgb(s->origin)+((u[47]==0u)?make_float3(0.f):rgb(s->direction)*s->direction.w);
            const float epsilon=fmaxf(1e-5f,fmaxf(fabsf(position.x),fmaxf(fabsf(position.y),fabsf(position.z)))*2e-7f);
            mass=rtr_density::mass(b,(int)u[40],position+direction*epsilon,direction,
                fminf(c.radianceAndDistance.w,((float*)b)[15]),u[3]);}
        if(!isfinite(mass)||mass<0.f){fail(b,s,203u);return;}incoming+=source*tr(sigma(b),mass);
    }
    if(!finite(incoming)){fail(b,s,204u);return;}s->lighting.x=incoming.x;s->lighting.y=incoming.y;s->lighting.z=incoming.z;
}
extern "C" __global__ void heterogeneous_multiple_start(unsigned char*b,int count){
    int i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=count)return;using namespace rtr_heterogeneous_multiple;
    State*s=states(b)+i;if(!s->control.x)return;
    s->radiance=s->lighting;float phasePdf;
    const float3 outgoing=rtr_medium::hgSample(rgb(s->direction),((float*)b)[19],random(s->random.x),random(s->random.x^0xd3a2646cu),&phasePdf);
    s->direction=make_float4(outgoing.x,outgoing.y,outgoing.z,0.f);pending(b,s);clearLighting(s);
    if(!finite(outgoing)||!isfinite(s->direction.w)){fail(b,s,205u);return;}atomicAdd((unsigned int*)b+28,1u);
}
extern "C" __global__ void heterogeneous_multiple_step(unsigned char*b,int count){
    int i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=count)return;using namespace rtr_heterogeneous_multiple;
    State*s=states(b)+i;if(!s->control.x)return;unsigned int*u=(unsigned int*)b;float*h=(float*)b;
    if(((unsigned int*)&s->lighting)[3]!=s->control.z){fail(b,s,206u);return;}
    const bool hit=s->hit.w>=0.f;const float distance=hit?fminf(fmaxf(s->hit.w,0.f),s->origin.w):s->origin.w;
    const bool sampling=s->control.w<u[7]&&peak(scattering(b))>0.f;
    const bool collided=sampling&&s->direction.w<distance;
    // Collision-only, non-emitting media need the already sampled optical mass only.
    // This removes the full-segment density walk from the common scattering path.
    const bool emitting=h[20]>0.f||h[21]>0.f||h[22]>0.f;
    const float mass=(!collided||emitting)?segmentMass(b,s,distance):0.f;
    float3 throughput=rgb(s->throughput),radiance=rgb(s->radiance);
    if(!isfinite(mass)||mass<0.f||!finite(rgb(s->hit))||!finite(throughput)){fail(b,s,207u);return;}
    if(emitting)radiance+=throughput*emission(b,mass);
    if(collided){const float target=s->direction.w,collisionMass=s->weight.w,density=s->throughput.w;
        const float3 attenuation=tr(sigma(b),collisionMass);const float3 ext=sigma(b);
        const float pdf=density*(ext.x*attenuation.x+ext.y*attenuation.y+ext.z*attenuation.z)*(1.f/3.f);
        if(!(pdf>0.f)){s->radiance=make_float4(radiance.x,radiance.y,radiance.z,0.f);finish(b,s,208u);return;}
        throughput*=attenuation*scattering(b)*(density/pdf);radiance+=throughput*rgb(s->lighting);
        const float3 point=rgb(s->origin)+rgb(s->direction)*target;s->origin=make_float4(point.x,point.y,point.z,fmaxf(s->origin.w-target,0.f));
        float phasePdf;const float3 outgoing=rtr_medium::hgSample(rgb(s->direction),h[19],
            random(s->random.z^0x27d4eb2du),random(s->random.z^0x165667b1u),&phasePdf);
        s->direction=make_float4(outgoing.x,outgoing.y,outgoing.z,0.f);++s->control.w;++s->control.z;++s->random.y;
        atomicAdd(u+31,1u);s->throughput=make_float4(throughput.x,throughput.y,throughput.z,0.f);
        s->radiance=make_float4(radiance.x,radiance.y,radiance.z,0.f);
        if(!finite(throughput)||!finite(radiance)){fail(b,s,209u);return;}
        if(peak(throughput)<=h[23]||s->random.y>=u[34]+u[7]+1u){finish(b,s,210u);return;}
        pending(b,s);clearLighting(s);if(!isfinite(s->direction.w)){fail(b,s,211u);return;}atomicAdd(u+28,1u);
    }else{const float3 attenuation=tr(sigma(b),mass);
        if(sampling){const float survival=(attenuation.x+attenuation.y+attenuation.z)*(1.f/3.f);
            if(!(survival>0.f)){s->radiance=make_float4(radiance.x,radiance.y,radiance.z,0.f);finish(b,s,212u);return;}throughput*=attenuation/survival;
        }else throughput*=attenuation;
        radiance+=throughput*rgb(s->hit);s->radiance=make_float4(radiance.x,radiance.y,radiance.z,0.f);
        if(!finite(radiance)){fail(b,s,213u);return;}finish(b,s,hit?214u:215u);
    }
}
extern "C" __global__ void heterogeneous_multiple_reduce(unsigned char*b,int count){
    int i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=count)return;using namespace rtr_heterogeneous_multiple;
    unsigned int*u=(unsigned int*)b;const unsigned int global=u[4]*u[6]+i;float4*output=(float4*)(b+u[10]);float3 total=rgb(output[global]);
    const State*sample=states(b)+(unsigned int)i*u[3];for(unsigned int j=0;j<u[3];++j){if(sample[j].control.x){atomicAdd(u+29,1u);return;}
        total+=rgb(sample[j].weight)*rgb(sample[j].radiance);}
    if(!finite(total)){atomicAdd(u+29,1u);return;}output[global]=make_float4(total.x,total.y,total.z,0.f);
}
