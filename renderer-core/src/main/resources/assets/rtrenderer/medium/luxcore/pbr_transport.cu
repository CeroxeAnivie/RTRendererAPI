// Vulkan supplies geometry/BRDF and ordered camera/light boundary events. All medium selection,
// density integration and attenuation here go through the fixed LuxCore native implementation.
namespace rtr_pbr {
// A uniform environment without interfaces has no per-ray priority stack.
// Keep the same segment implementation, but let the compiler eliminate both
// the 32-entry volume array and density-grid code from this separate kernel.
struct UniformState {
    float4 direction,transport,emission;
    struct Volumes {static constexpr unsigned int currentVolumeIndex=0xffffffffu;} volumes;
};
__device__ void clear(UniformState *s) {
    s->direction=s->transport=s->emission=make_float4(0.f);
}
__device__ bool initializeVolumes(unsigned char *buffer,UniformState *) {
    const unsigned int *h=(const unsigned int *)buffer;
    return h[0]==0x52564d31u&&h[1]==1u&&h[6]>=1u&&h[6]<=32u
        &&h[7]==0u&&h[8]==0u&&((const int *)buffer)[24]<0;
}
// Found by ADL from the shared segment template; the pinned LuxCore Beer and
// API emission functions remain identical to the general boundary path.
__device__ int coefficients(unsigned char *buffer,const UniformState &,float3 *a,float3 *e) {
    const float *h=(const float *)buffer;
    *a=make_float3(h[16],h[17],h[18]);*e=make_float3(h[20],h[21],h[22]);return -1;
}
__device__ bool walk(unsigned char *buffer,const unsigned int *packet,UniformState *s,
        float geometryEnd,bool receiver,unsigned int &crossed) {
    using namespace rtr_boundary;
    const unsigned int *h=(const unsigned int *)buffer;
    const bool endpoint=(h[27]&1024u)!=0u;
    const unsigned int capacity=h[27]&255u,terminal=endpoint?0u:4u+4u*((capacity+1u)/2u);
    if(packet==nullptr) {
        // The producer validates origins before publishing color-carried endpoints.
        // With no interfaces or density grid, the shared segment only uses distance.
        if(!endpoint||capacity!=0u||(h[67]&2u)==0u||!isfinite(geometryEnd)||geometryEnd<0.f)return false;
        return segment(buffer,s,make_float3(0.f),fmaxf(fminf(geometryEnd,((float *)buffer)[19]),0.f));
    }
    if(capacity>32u||(endpoint?capacity!=0u:packet[3]!=0u))return false;
    float3 origin=make_float3(__uint_as_float(packet[terminal+1]),__uint_as_float(packet[terminal+2]),__uint_as_float(packet[terminal+3]));
    // Uniform coefficients do not depend on ray direction; no density grid or interface can inspect it.
    if(!endpoint)s->direction=make_float4(__uint_as_float(packet[0]),__uint_as_float(packet[1]),__uint_as_float(packet[2]),0.f);
    if(!finite3(origin)||!finite3(rgb(s->direction))||!isfinite(geometryEnd)||geometryEnd<0.f)return false;
    return segment(buffer,s,origin,fmaxf(fminf(geometryEnd,((float *)buffer)[19]),0.f));
}
__device__ void clear(rtr_boundary::State *s) {
    unsigned int *words=(unsigned int *)s;for(int j=0;j<68;++j)words[j]=0u;
}
__device__ bool walk(unsigned char *buffer,const unsigned int *packet,rtr_boundary::State *s,
        float geometryEnd,bool receiver,unsigned int &crossed) {
    using namespace rtr_boundary;
    unsigned int *h=(unsigned int *)buffer;const unsigned int capacity=h[27]&255u,terminal=4u+4u*((capacity+1u)/2u);
    if(packet==nullptr||capacity>32u||(capacity==0u&&h[7]!=0u)||packet[3]>capacity||packet[3]>h[6])return false;
    float3 origin=make_float3(__uint_as_float(packet[terminal+1]),__uint_as_float(packet[terminal+2]),__uint_as_float(packet[terminal+3]));
    s->direction=make_float4(__uint_as_float(packet[0]),__uint_as_float(packet[1]),__uint_as_float(packet[2]),0.f);
    if(!finite3(origin)||!finite3(rgb(s->direction))||!isfinite(geometryEnd)||geometryEnd<0.f)return false;
    const float integrationEnd=fminf(geometryEnd,((float *)buffer)[19]);float previous=0.f;
    for(unsigned int j=0u;j<packet[3];++j) {
        float distance=__uint_as_float(packet[4+j*2]);unsigned int code=packet[5+j*2],index=code&0x7fffffffu;
        if(!isfinite(distance)||distance<0.f||index>=h[7])return false;
        if(distance<previous)continue;
        if(distance>=geometryEnd||(!receiver&&distance>=integrationEnd))break;
        if(!segment(buffer,s,origin+rgb(s->direction)*previous,distance-previous))return false;
        bool entering=(code&0x80000000u)!=0u;
        if(entering)for(unsigned int k=0u;k<s->volumes.volumeIndexListSize;++k)
            if(s->volumes.volumeIndexList[k]==index)return false;
        rtr_medium::Status result=entering?rtr_medium::add(&s->volumes,index,priorities(buffer),h[7])
                :rtr_medium::remove(&s->volumes,index,priorities(buffer),h[7]);
        if(result!=rtr_medium::OK)return false;
        ++crossed;
        if(j+1u>=h[6]||peak(rgb(s->transport))<=((float *)buffer)[23]) {
            s->transport.x=s->transport.y=s->transport.z=0.f;return true;
        }
        // Integrate the epsilon displacement in the newly selected medium, matching continuation.
        float3 point=origin+rgb(s->direction)*distance;
        float epsilon=fmaxf(1e-5f,peak(make_float3(fabsf(point.x),fabsf(point.y),fabsf(point.z)))*2e-7f);
        float advance=fminf(epsilon,fmaxf(geometryEnd-distance,0.f));
        if(!segment(buffer,s,point,advance))return false;
        previous=distance+advance;
    }
    return segment(buffer,s,origin+rgb(s->direction)*previous,fmaxf(integrationEnd-previous,0.f));
}
template<class StateType>
__device__ bool resolve(unsigned char *buffer,int i,unsigned int &crossed) {
    using namespace rtr_boundary;
    unsigned int *h=(unsigned int *)buffer;const unsigned int capacity=h[27]&255u,stride=(h[27]&1024u)!=0u?4u:8u+4u*((capacity+1u)/2u),terminal=stride-4u;
    const bool colorEndpoints=(h[67]&2u)!=0u;
    if(colorEndpoints&&((h[67]&1u)==0u||(h[27]&1024u)==0u||capacity!=0u
            ||h[7]!=0u||h[8]!=0u||((const int *)buffer)[24]>=0))return false;
    float4 *colors=(float4 *)(buffer+h[65]);
    const unsigned int *packets=(const unsigned int *)(buffer+h[9]);
    const unsigned int *camera=colorEndpoints?nullptr:packets+(unsigned int)i*stride;
    StateType receiver;clear(&receiver);receiver.transport=make_float4(1.f,1.f,1.f,((float *)buffer)[19]);
    if(!initializeVolumes(buffer,&receiver))return false;
    float hit=colorEndpoints?colors[(unsigned int)i*2u].w:__uint_as_float(camera[terminal]);
    float end=hit>=0.f?hit:((float *)buffer)[19];
    if(!walk(buffer,camera,&receiver,end,true,crossed))return false;
    float3 diffuse=rgb(colors[(unsigned int)i*2u]),specular=rgb(colors[(unsigned int)i*2u+1u]);
    float3 additionalRadiance=make_float3(0.f);
    const unsigned long long additionalOffset=((const unsigned long long *)(buffer+272))[0];
    if(additionalOffset!=0ull) {
        const float4 *additional=(const float4 *)(buffer+additionalOffset);
        additionalRadiance=rgb(additional[(unsigned int)i]);
    }
    float diffuseDistance=0.f,specularDistance=0.f,diffuseWeight=0.f,specularWeight=0.f;
    const unsigned int count=h[64],paths=h[66];
    if(peak(rgb(receiver.transport))>0.f)for(unsigned int light=0u;light<count;++light) {
        const unsigned int index=paths+(unsigned int)i*count+light;
        float4 d=colors[index*2u],s=colors[index*2u+1u];
        if(peak(rgb(d))+peak(rgb(s))<=0.f)continue;
        const unsigned int *packet=colorEndpoints?nullptr:packets+index*stride;
        float distance=colorEndpoints?s.w:__uint_as_float(packet[terminal]);float3 transmission=make_float3(1.f);
        // A negative connection distance is the existing castsShadow=false surface contract.
        if(distance>=0.f) {
            StateType connection;clear(&connection);connection.volumes=receiver.volumes;
            connection.transport=make_float4(1.f,1.f,1.f,((float *)buffer)[19]);
            if(!walk(buffer,packet,&connection,distance,false,crossed))return false;
            transmission=rgb(connection.transport);
        }
        float3 dc=rgb(d)*transmission,sc=rgb(s)*transmission;diffuse+=dc;specular+=sc;
        float dw=.2126f*dc.x+.7152f*dc.y+.0722f*dc.z,sw=.2126f*sc.x+.7152f*sc.y+.0722f*sc.z;
        diffuseDistance+=dw*d.w;diffuseWeight+=dw;specularDistance+=sw*(colorEndpoints?d.w:s.w);specularWeight+=sw;
    }
    // The existing density/multiple integrator owns all volume emission, including
    // secondary-path emission. Do not integrate camera emission twice on composition.
    const float3 cameraEmission=h[70]!=0u?make_float3(0.f):rgb(receiver.emission);
    diffuse=diffuse*rgb(receiver.transport)+cameraEmission+additionalRadiance;
    specular=specular*rgb(receiver.transport);
    if(!finite3(diffuse)||!finite3(specular))return false;
    ((float4 *)(buffer+h[11]))[i]=make_float4(diffuse.x,diffuse.y,diffuse.z,diffuseWeight>0.f?diffuseDistance/diffuseWeight:0.f);
    ((float4 *)(buffer+h[12]))[i]=make_float4(specular.x,specular.y,specular.z,specularWeight>0.f?specularDistance/specularWeight:0.f);
    return true;
}
template<class StateType>
__device__ void run(unsigned char *buffer,int count) {
    int i=blockIdx.x*blockDim.x+threadIdx.x;unsigned int failed=0u,crossed=0u,completed=i<count?1u:0u;
    if(i<count&&!resolve<StateType>(buffer,i,crossed)) {
        unsigned int *h=(unsigned int *)buffer;
        ((float4 *)(buffer+h[11]))[i]=make_float4(0.f);((float4 *)(buffer+h[12]))[i]=make_float4(0.f);failed=1u;
    }
    for(unsigned int shift=16u;shift>0u;shift>>=1u) {
        failed+=__shfl_down_sync(0xffffffffu,failed,shift);crossed+=__shfl_down_sync(0xffffffffu,crossed,shift);
        completed+=__shfl_down_sync(0xffffffffu,completed,shift);
    }
    if((threadIdx.x&31u)==0u) {
        unsigned int *h=(unsigned int *)buffer;if(failed)atomicAdd(h+29,failed);if(crossed)atomicAdd(h+30,crossed);
        if(completed){atomicSub(h+28,completed);atomicAdd(h+31,completed);}
    }
}
}
extern "C" __global__ void native_pbr_medium_resolve(unsigned char *buffer,int count) {
    rtr_pbr::run<rtr_boundary::State>(buffer,count);
}
extern "C" __global__ void native_pbr_medium_uniform_resolve(unsigned char *buffer,int count) {
    rtr_pbr::run<rtr_pbr::UniformState>(buffer,count);
}
