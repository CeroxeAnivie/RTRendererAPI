// RTRendererAPI direct-light adapter. HomogeneousVolume_SegmentScatter above remains pinned.
// Vulkan supplies radiance at each real light connection; no host ray/light readback is used.
namespace rtr_camera_scattering {
__device__ float3 rgb(float4 v) {return make_float3(v.x,v.y,v.z);}
__device__ float integral(float sigma,float distance) {
    return rtrApiIntegratedEmission(sigma,1.f,distance);
}
__device__ bool resolve(bool hit,float hitDistance,const float4 *source,int steps,const float *h,
        float4 *attenuation,float4 *radiance,bool preweighted=false) {
    const float distance=hit?fminf(fmaxf(hitDistance,0.f),h[15]):h[15];
    const float3 sigma=make_float3(h[12],h[13],h[14]),zero=make_float3(0.f);
    const float3 scatter=make_float3(h[16],h[17],h[18]);
    float3 tr,ignored;
    // sigmaT is passed as the purely absorbing coefficient: no random collision is substituted
    // for the public direct-single-scattering estimator or its full interval/light budget.
    HomogeneousVolume_SegmentScatter(.5f,false,distance,&sigma,&zero,&zero,&tr,&ignored);
    float3 output=make_float3(rtrApiIntegratedEmission(sigma.x,h[20],distance),
        rtrApiIntegratedEmission(sigma.y,h[21],distance),rtrApiIntegratedEmission(sigma.z,h[22],distance));
    output+=scatter*make_float3(h[24]*integral(sigma.x,distance),
        h[25]*integral(sigma.y,distance),h[26]*integral(sigma.z,distance));
    const float interval=distance/(float)steps,reference=fminf(sigma.x,fminf(sigma.y,sigma.z));
    for(int j=0;j<steps && interval>0.f;++j) {
        const float start=j*interval;
        const float3 prefix=make_float3(expf(-sigma.x*start),expf(-sigma.y*start),expf(-sigma.z*start));
        if(!preweighted&&fmaxf(prefix.x,fmaxf(prefix.y,prefix.z))<h[23])break;
        const float4 incoming=source[j];
        if(!isfinite(incoming.x)||!isfinite(incoming.y)||!isfinite(incoming.z)||!isfinite(incoming.w)
                ||incoming.w<0.f||incoming.w>(preweighted?distance:interval)*(1.f+1e-5f))return false;
        const float pdf=preweighted?1.f:
                (reference*interval<1e-3f?1.f/interval:expf(-reference*incoming.w)/integral(reference,interval));
        float3 segmentTr,segmentE;
        HomogeneousVolume_SegmentScatter(.5f,false,(preweighted?0.f:start)+incoming.w,&sigma,&zero,&zero,&segmentTr,&segmentE);
        output+=segmentTr*scatter*rgb(incoming)/pdf;
    }
    *attenuation=make_float4(tr.x,tr.y,tr.z,1.f);
    *radiance=make_float4(output.x,output.y,output.z,0.f);
    return isfinite(output.x)&&isfinite(output.y)&&isfinite(output.z);
}
__device__ bool resolve(const float *primary,const float4 *source,int steps,const float *h,
        float4 *attenuation,float4 *radiance) {
    return resolve(((const unsigned int*)primary)[28]!=0u,primary[3],source,steps,h,attenuation,radiance);
}
}
extern "C" __global__ void camera_scattering_resolve(unsigned char *buffer,int count) {
    int i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=count)return;
    const unsigned int *u=(const unsigned int *)buffer;const float *h=(const float *)buffer;
    if(u[0]!=0x52534331u||u[1]!=2u||u[3]<1u||u[3]>64u||u[6]<1u||u[6]>8u) {
        atomicAdd((unsigned int *)buffer+28,1u);return;
    }
    const unsigned int item=u[4]*u[6]+(unsigned int)i;
    bool hit;float distance;
    if(u[7]!=0u) {
        const unsigned int *compact=(const unsigned int *)(buffer+u[8])+(unsigned int)i*4u;
        distance=__uint_as_float(compact[0]);hit=compact[1]!=0u;
    } else {const float *primary=(const float *)(buffer+u[8])+item*52u;distance=primary[3];hit=((const unsigned int*)primary)[28]!=0u;}
    const float4 *source=(const float4 *)(buffer+u[11])+(unsigned int)i*u[3];
    if(!rtr_camera_scattering::resolve(hit,distance,source,u[3],h,(float4 *)(buffer+u[9])+item,(float4 *)(buffer+u[10])+item,true))
        atomicAdd((unsigned int *)buffer+28,1u);
}
