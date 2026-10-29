// RTRendererAPI heterogeneous direct-scattering compatibility adapter.
// Pinned homogeneous segment math and density-grid provenance remain unchanged.
namespace rtr_density_scattering {
struct __align__(16) Ray {float4 origin,direction;};
struct __align__(16) Sample {float4 position,weight;};
struct __align__(16) Connection {float4 radianceAndDistance,direction;};
static_assert(sizeof(Ray)==32 && sizeof(Sample)==32 && sizeof(Connection)==32,"density scattering ABI");
__device__ float3 rgb(float4 value) {return make_float3(value.x,value.y,value.z);}
__device__ unsigned int hash(unsigned int value) {value^=value>>16;value*=0x7feb352du;value^=value>>15;value*=0x846ca68bu;return value^(value>>16);}
__device__ float random(unsigned int seed) {return (float)(hash(seed)>>8)*(1.f/16777216.f);}
__device__ bool finite(float3 value) {return isfinite(value.x)&&isfinite(value.y)&&isfinite(value.z);}
__device__ float3 transmittance(float3 sigma,float mass) {
    float3 output,ignored,zero=make_float3(0.f);
    HomogeneousVolume_SegmentScatter(.5f,false,mass,&sigma,&zero,&zero,&output,&ignored);return output;
}
__device__ float epsilon(float3 position) {return fmaxf(1e-5f,fmaxf(fabsf(position.x),fmaxf(fabsf(position.y),fabsf(position.z)))*2e-7f);}
__device__ bool header(const unsigned int *u) {return u[0]==0x52445331u&&u[1]==1u&&u[3]>=1u&&u[3]<=64u&&u[6]>=1u&&u[6]<=8u&&u[33]>=1u&&u[33]<=9u;}
}
extern "C" __global__ void density_scattering_prepare(unsigned char *buffer,int count) {
    int index=blockIdx.x*blockDim.x+threadIdx.x;if(index>=count)return;
    using namespace rtr_density_scattering;
    const unsigned int *u=(const unsigned int *)buffer;const float *h=(const float *)buffer;
    if(!header(u)) {atomicAdd((unsigned int *)buffer+28,1u);return;}
    const unsigned int item=u[4]*u[6]+(unsigned int)index,steps=u[3];
    const Ray ray=((const Ray *)(buffer+u[11]))[index];
    const float3 origin=rgb(ray.origin),direction=rgb(ray.direction),sigma=make_float3(h[12],h[13],h[14]);
    const float *primary=(const float *)(buffer+u[8])+item*52u;
    const bool hit=((const unsigned int *)primary)[28]!=0u;
    const float distance=hit?fminf(fmaxf(primary[3],0.f),h[15]):h[15];
    Sample *samples=(Sample *)(buffer+u[30])+(unsigned int)index*steps;
    for(unsigned int j=0;j<steps;++j) {samples[j].position=make_float4(0.f,0.f,0.f,0.f);samples[j].weight=make_float4(0.f,0.f,0.f,0.f);}
    if(!finite(origin)||!finite(direction)||!isfinite(distance)||distance<0.f) {atomicAdd((unsigned int *)buffer+28,1u);return;}
    const rtr_density::Descriptor &grid=*(const rtr_density::Descriptor *)(buffer+u[29]);
    const float3 p=rtr_density::map(grid,origin,1.f),v=rtr_density::map(grid,direction,0.f);
    if(!finite(p)||!finite(v)) {atomicAdd((unsigned int *)buffer+28,1u);return;}
    float first=0.f,last=distance;
    const bool clipped=rtr_density::clipAxis(p.x,v.x,&first,&last)&&rtr_density::clipAxis(p.y,v.y,&first,&last)
        &&rtr_density::clipAxis(p.z,v.z,&first,&last)&&last>first;
    const float interval=clipped?(last-first)/(float)steps:0.f;
    const float *pixels=(const float *)(buffer+grid.atlasOffset);float density[64],totalMass=0.f;
    for(unsigned int j=0;j<steps;++j) {
        density[j]=interval>0.f?rtr_density::DensityGridTexture_ConstEvaluate(pixels,grid,p+v*(first+((float)j+.5f)*interval)):0.f;
        totalMass+=density[j]*interval;
    }
    if(!isfinite(totalMass)||totalMass<0.f) {atomicAdd((unsigned int *)buffer+28,1u);return;}
    const float3 tr=transmittance(sigma,totalMass),scatter=make_float3(h[16],h[17],h[18]);
    const float3 emitted=make_float3(h[20],h[21],h[22])+scatter*make_float3(h[24],h[25],h[26]);
    const float3 output=make_float3(rtrApiIntegratedEmission(sigma.x,emitted.x,totalMass),
        rtrApiIntegratedEmission(sigma.y,emitted.y,totalMass),rtrApiIntegratedEmission(sigma.z,emitted.z,totalMass));
    ((float4 *)(buffer+u[9]))[item]=make_float4(tr.x,tr.y,tr.z,1.f);
    ((float4 *)(buffer+u[10]))[item]=make_float4(output.x,output.y,output.z,0.f);
    const unsigned int seed=hash((item/u[6])^u[27]*0x9e3779b9u);
    const float reference=fminf(sigma.x,fminf(sigma.y,sigma.z));float prefixMass=0.f;
    for(unsigned int j=0;j<steps;++j) {
        if(density[j]<=0.f||interval<=0.f)continue;
        const float3 prefix=transmittance(sigma,prefixMass);
        if(fmaxf(prefix.x,fmaxf(prefix.y,prefix.z))<h[23])break;
        const float uniform=random(seed^(j+1u)*0x27d4eb2du),effective=reference*density[j],tau=effective*interval;
        const float local=tau<1e-3f?uniform*interval:-logf(fmaxf(1.f-uniform*(1.f-expf(-tau)),1e-30f))/effective;
        const float pdf=tau<1e-3f?1.f/interval:expf(-effective*local)/rtrApiIntegratedEmission(effective,1.f,interval);
        const float3 position=origin+direction*(first+(float)j*interval+local);
        const float3 weight=transmittance(sigma,prefixMass+density[j]*local)*scatter*(density[j]/pdf);
        samples[j].position=make_float4(position.x,position.y,position.z,1.f);
        samples[j].weight=make_float4(weight.x,weight.y,weight.z,0.f);
        prefixMass+=density[j]*interval;
        if(!finite(position)||!finite(weight))atomicAdd((unsigned int *)buffer+28,1u);
    }
}
extern "C" __global__ void density_scattering_resolve(unsigned char *buffer,int count) {
    int index=blockIdx.x*blockDim.x+threadIdx.x;if(index>=count)return;
    using namespace rtr_density_scattering;
    const unsigned int *u=(const unsigned int *)buffer;const float *h=(const float *)buffer;
    if(!header(u)) {atomicAdd((unsigned int *)buffer+28,1u);return;}
    const unsigned int item=u[4]*u[6]+(unsigned int)index;
    const Sample *samples=(const Sample *)(buffer+u[30])+(unsigned int)index*u[3];
    const Connection *connections=(const Connection *)(buffer+u[31])+(unsigned int)index*u[3]*u[33];
    const float3 sigma=make_float3(h[12],h[13],h[14]);float3 output=rgb(((float4 *)(buffer+u[10]))[item]);
    for(unsigned int j=0;j<u[3];++j)if(samples[j].position.w!=0.f) {
        const float3 position=rgb(samples[j].position),weight=rgb(samples[j].weight);
        for(unsigned int k=0;k<u[33];++k) {
            const Connection connection=connections[j*u[33]+k];const float3 source=rgb(connection.radianceAndDistance);
            if(!finite(source)||!isfinite(connection.radianceAndDistance.w)) {atomicAdd((unsigned int *)buffer+28,1u);return;}
            if(fmaxf(source.x,fmaxf(source.y,source.z))==0.f)continue;
            float mass=0.f;
            if(connection.radianceAndDistance.w>=0.f) {
                const float3 direction=rgb(connection.direction);
                mass=rtr_density::mass(buffer,(int)u[29],position+direction*epsilon(position),direction,
                    fminf(connection.radianceAndDistance.w,h[15]),u[3]);
            }
            if(!isfinite(mass)||mass<0.f) {atomicAdd((unsigned int *)buffer+28,1u);return;}
            output+=weight*source*transmittance(sigma,mass);
        }
    }
    if(!finite(output)) {atomicAdd((unsigned int *)buffer+28,1u);return;}
    ((float4 *)(buffer+u[10]))[item]=make_float4(output.x,output.y,output.z,0.f);
}
