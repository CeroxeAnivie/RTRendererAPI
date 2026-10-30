// Explicit volume-boundary finite-order scattering adapter.
// The pinned LuxCore stack/priority and HG implementations precede this source.
namespace rtr_boundary_scatter {
struct __align__(16) State {
    float4 origin,direction,throughput,radiance,weight,hit,lighting;
    uint4 control,random;
};
struct __align__(16) Boundary {
    unsigned int slot; int priority; unsigned int orientation,flags;
    float4 sigmaTAndG,sigmaSAndDensity,emission;
};
struct __align__(16) RayRecord {
    float4 originAndDistance,directionAndFlags,sourceAndVisibility;
    uint4 identity;
};
struct __align__(16) Connection {float4 source;float4 direction;};
static_assert(sizeof(State)==144&&sizeof(Boundary)==64&&sizeof(RayRecord)==64&&sizeof(Connection)==32,
        "boundary scattering ABI");
__device__ unsigned int *words(unsigned char*b){return (unsigned int*)b;}
__device__ float *floats(unsigned char*b){return (float*)b;}
__device__ float3 rgb(float4 v){return make_float3(v.x,v.y,v.z);}
__device__ bool finite3(float3 v){return isfinite(v.x)&&isfinite(v.y)&&isfinite(v.z);}
__device__ float peak(float3 v){return fmaxf(v.x,fmaxf(v.y,v.z));}
__device__ State *states(unsigned char*b){return (State*)(b+words(b)[26]);}
__device__ rtr_medium::PathVolumeInfo *volume(unsigned char*b,int i){return (rtr_medium::PathVolumeInfo*)(b+words(b)[47])+(unsigned int)i;}
__device__ const Boundary *table(unsigned char*b){return (const Boundary*)(b+words(b)[13]);}
__device__ const rtr_medium::Material *priorities(unsigned char*b){return (const rtr_medium::Material*)(b+words(b)[14]);}
__device__ int descriptor(unsigned char*b,const rtr_medium::PathVolumeInfo*v){
    const unsigned int current=v->currentVolumeIndex;return current==0xffffffffu?(int)words(b)[24]:(int)((unsigned int*)&table(b)[current].sigmaSAndDensity)[3];}
__device__ float3 sigmaT(unsigned char*b,const rtr_medium::PathVolumeInfo*v){const unsigned int current=v->currentVolumeIndex;
    return current==0xffffffffu?make_float3(floats(b)[16],floats(b)[17],floats(b)[18]):make_float3(table(b)[current].sigmaTAndG.x,table(b)[current].sigmaTAndG.y,table(b)[current].sigmaTAndG.z);}
__device__ float3 sigmaS(unsigned char*b,const rtr_medium::PathVolumeInfo*v){const unsigned int current=v->currentVolumeIndex;
    return current==0xffffffffu?make_float3(floats(b)[32],floats(b)[33],floats(b)[34]):make_float3(table(b)[current].sigmaSAndDensity.x,table(b)[current].sigmaSAndDensity.y,table(b)[current].sigmaSAndDensity.z);}
__device__ float3 emittedRgb(unsigned char*b,const rtr_medium::PathVolumeInfo*v){const unsigned int current=v->currentVolumeIndex;
    return current==0xffffffffu?make_float3(floats(b)[20],floats(b)[21],floats(b)[22]):make_float3(table(b)[current].emission.x,table(b)[current].emission.y,table(b)[current].emission.z);}
__device__ float anisotropy(unsigned char*b,const rtr_medium::PathVolumeInfo*v){const unsigned int current=v->currentVolumeIndex;
    return current==0xffffffffu?floats(b)[35]:table(b)[current].sigmaTAndG.w;}
__device__ unsigned int hash(unsigned int v){v^=v>>16;v*=0x7feb352du;v^=v>>15;v*=0x846ca68bu;return v^(v>>16);}
__device__ float random(unsigned int v){return (float)(hash(v)>>8)*(1.f/16777216.f);}
__device__ float3 expT(float3 tau){return make_float3(expf(-tau.x),expf(-tau.y),expf(-tau.z));}
__device__ float3 add(float3 a,float3 b){return make_float3(a.x+b.x,a.y+b.y,a.z+b.z);}
__device__ float3 mul(float3 a,float b){return make_float3(a.x*b,a.y*b,a.z*b);}
__device__ float3 componentMul(float3 a,float3 b){return make_float3(a.x*b.x,a.y*b.y,a.z*b.z);}
__device__ float3 emissionIntegral(float3 sigma,float3 e,float mass){return make_float3(
    rtrApiIntegratedEmission(sigma.x,e.x,mass),rtrApiIntegratedEmission(sigma.y,e.y,mass),rtrApiIntegratedEmission(sigma.z,e.z,mass));}
__device__ float epsilon(float3 p){return fmaxf(1e-5f,fmaxf(fabsf(p.x),fmaxf(fabsf(p.y),fabsf(p.z)))*2e-7f);}
__device__ bool initializeStack(unsigned char*b,int path,rtr_medium::PathVolumeInfo*out){
    rtr_medium::PathVolumeInfo_Init(out);const unsigned int initial=words(b)[8],offset=words(b)[15];
    for(unsigned int i=0;i<initial;++i)if(rtr_medium::add(out,((unsigned int*)(b+offset))[i],priorities(b),words(b)[7])!=rtr_medium::OK)return false;
    return true;
}
__device__ bool transition(unsigned char*b,rtr_medium::PathVolumeInfo*v,unsigned int code){
    const unsigned int index=code&0x7fffffffu;if(index>=words(b)[7])return false;
    const bool entering=(code&0x80000000u)!=0u;
    if(entering)for(unsigned int i=0;i<v->volumeIndexListSize;++i)if(v->volumeIndexList[i]==index)return false;
    return (entering?rtr_medium::add(v,index,priorities(b),words(b)[7])
            :rtr_medium::remove(v,index,priorities(b),words(b)[7]))==rtr_medium::OK;
}
__device__ unsigned int packetCount(unsigned char*b,int base){return words(b)[(unsigned int)base/4u+3u];}
__device__ void packetEvent(unsigned char*b,int base,unsigned int i,float*distance,unsigned int*code){
    const unsigned int*entry=words(b)+(unsigned int)base/4u+4u+(i/2u)*4u;
    const unsigned int lane=(i&1u)*2u;
    *distance=__uint_as_float(entry[lane]);*code=entry[lane+1u];}
__device__ float terminalDistance(unsigned char*b,int base){const unsigned int stride=2u+(words(b)[27]+1u)/2u;
    return __uint_as_float(words(b)[(unsigned int)base/4u+(stride-1u)*4u]);}
__device__ bool integrate(unsigned char*b,rtr_medium::PathVolumeInfo*v,float3 origin,float3 direction,float distance,
        float3*optical,float3*emitted){
    if(distance<=0.f)return true;const float3 st=sigmaT(b,v),e=emittedRgb(b,v);
    const int desc=descriptor(b,v);const float mass=desc<0?distance:rtr_density::mass(b,desc,origin,direction,distance,words(b)[25]);
    if(!isfinite(mass)||mass<0.f)return false;const float3 before=expT(*optical);
    *emitted=add(*emitted,componentMul(before,emissionIntegral(st,e,mass)));
    *optical=add(*optical,mul(st,mass));return true;
}
__device__ bool walk(unsigned char*b,int packetBase,float3 origin,float3 direction,float maximum,
        rtr_medium::PathVolumeInfo*v,float3*optical,float3*emitted,unsigned int*events){
    const unsigned int count=packetCount(b,packetBase),budget=words(b)[6];
    maximum=fminf(maximum,floats(b)[19]);
    const float hit=terminalDistance(b,packetBase),end=hit<0.f?maximum:fminf(hit,maximum);
    if(!isfinite(end)||end<0.f||count>words(b)[27])return false;
    float previous=0.f;
    for(unsigned int j=0;j<count;++j){float d;unsigned int code;packetEvent(b,packetBase,j,&d,&code);
        if(!isfinite(d)||d<previous)return false;if(d>=end)break;
        if(++*events>budget)return false;
        if(!integrate(b,v,origin+direction*previous,direction,d-previous,optical,emitted))return false;
        const float3 point=origin+direction*d;if(!transition(b,v,code))return false;
        const float advance=fminf(epsilon(point),fmaxf(end-d,0.f));
        if(!integrate(b,v,point,direction,advance,optical,emitted))return false;previous=d+advance;
    }
    return integrate(b,v,origin+direction*previous,direction,fmaxf(end-previous,0.f),optical,emitted);
}
__device__ float mixSurvival(float3 optical){return (expf(-optical.x)+expf(-optical.y)+expf(-optical.z))/3.f;}
__device__ void stateFail(unsigned char*b,State*s){s->control.x=3u;s->random.w=1u;atomicAdd(words(b)+29,1u);}
__device__ bool validHeader(unsigned char*b){const unsigned int*u=words(b);return u[0]==0x52534231u&&u[1]==1u&&u[3]>=1u&&u[3]<=8u&&u[6]>=1u&&u[6]<=32u
    &&u[7]>=1u&&u[7]<=32u&&u[25]>=1u&&u[25]<=64u&&u[27]>=1u&&u[27]<=32u
    &&u[54]==32u+16u*((u[27]+1u)/2u)&&u[39]<=8u&&u[46]==144u;}
}

// Implemented below after helper definitions: one thread owns a camera sample and its first event.
extern "C" __global__ void boundary_scatter_reset(unsigned char*b,int count){
    if(blockIdx.x==0&&threadIdx.x==0){unsigned int*u=(unsigned int*)b;u[28]=u[30]=u[31]=0;}}
extern "C" __global__ void boundary_scatter_prepare(unsigned char*b,int count){
    int path=blockIdx.x*blockDim.x+threadIdx.x;if(path>=count)return;using namespace rtr_boundary_scatter;
    unsigned int*u=words(b);float*h=floats(b);if(!validHeader(b)){atomicAdd(u+29,1u);return;}
    const int packet=(int)u[9]+path*(int)u[54];const unsigned int stride=2u+(u[27]+1u)/2u;
    const unsigned int*term=words(b)+(unsigned int)packet/4u+(stride-1u)*4u;
    const float3 origin=make_float3(__uint_as_float(term[1]),__uint_as_float(term[2]),__uint_as_float(term[3]));
    const float3 direction=make_float3(__uint_as_float(words(b)[packet/4u]),__uint_as_float(words(b)[packet/4u+1]),__uint_as_float(words(b)[packet/4u+2]));
    const float hit=__uint_as_float(term[0]),distance=hit<0.f?h[19]:fminf(hit,h[19]);
    if(!isfinite(distance)||distance<0.f||packetCount(b,packet)>u[27]){atomicAdd(u+29,1u);return;}
    rtr_medium::PathVolumeInfo stack;if(!initializeStack(b,path,&stack)){atomicAdd(u+29,1u);return;}float3 optical=make_float3(0.f),emitted=make_float3(0.f);
    float target=-logf(fmaxf(1.f-random(u[40]^path*0x9e3779b9u),1e-30f));
    unsigned int channel=min((unsigned int)(random(u[40]^path*0x85ebca6bu)*3.f),2u),events=0u,collisionEvents=0u;bool collided=false;
    const unsigned int eventCount=packetCount(b,packet);float previous=0.f;
    const int stateIndex=path;State*s=states(b)+stateIndex;rtr_medium::PathVolumeInfo*saved=volume(b,stateIndex);
    s->origin=make_float4(0);s->direction=make_float4(direction.x,direction.y,direction.z,0);s->throughput=make_float4(1);s->radiance=make_float4(0);
    s->weight=make_float4(0);s->hit=make_float4(0);s->lighting=make_float4(0);s->control=make_uint4(0u,u[4]*u[3]+path,1u,0u);
    s->random=make_uint4(hash(u[40]^path),0u,0u,0u);
    for(unsigned int segment=0;segment<=eventCount;++segment){
        float next=distance;unsigned int code=0;
        if(segment<eventCount){float eventDistance;packetEvent(b,packet,segment,&eventDistance,&code);
            if(!isfinite(eventDistance)||eventDistance<previous){stateFail(b,s);return;}
            if(eventDistance>=distance)continue;next=eventDistance;}
        const float length=fmaxf(next-previous,0.f);const int desc=descriptor(b,&stack);const float3 st=sigmaT(b,&stack),ss=sigmaS(b,&stack),e=emittedRgb(b,&stack);
        for(unsigned int j=0;j<u[25];++j){const float dt=length/(float)u[25];if(dt<=0.f)break;const float t=previous+(j+.5f)*dt;
            float rho=1.f;if(desc>=0){const rtr_density::Descriptor&grid=*(const rtr_density::Descriptor*)(b+(unsigned int)desc);
                rho=rtr_density::DensityGridTexture_ConstEvaluate((const float*)(b+grid.atlasOffset),grid,rtr_density::map(grid,origin+direction*t,1.f));}
            if(!isfinite(rho)||rho<0.f){stateFail(b,s);return;}
            const float mass=rho*dt;float3 before=expT(optical);emitted=add(emitted,componentMul(before,emissionIntegral(st,e,mass)));
            const float rate=(channel==0?st.x:channel==1?st.y:st.z)*rho;
            if(!collided&&rate>0.f&&target<rate*dt){const float local=target/rate;float3 atOptical=add(optical,mul(st,rho*local));
                float pdf=(st.x*expf(-atOptical.x)+st.y*expf(-atOptical.y)+st.z*expf(-atOptical.z))*(rho/3.f);
                if(!(pdf>0.f)||!isfinite(pdf)){stateFail(b,s);return;}
                const float3 scatter=ss;const float3 weight=componentMul(expT(atOptical),mul(scatter,rho/pdf));
                const float at=previous+j*dt+local;float3 point=origin+direction*at;
                s->origin=make_float4(point.x,point.y,point.z,fmaxf(h[19]-at,0.f));s->weight=make_float4(weight.x,weight.y,weight.z,anisotropy(b,&stack));
                s->throughput=make_float4(1.f,1.f,1.f,0.f);*saved=stack;s->control.x=2u;s->control.w=0u;
                collisionEvents=events;
                collided=true;atomicAdd(u+28,1u);
            }else if(!collided)target-=rate*dt;
            optical=add(optical,mul(st,mass));
        }
        if(segment<eventCount){if(++events>u[6]||!transition(b,&stack,code)){stateFail(b,s);return;}
            float3 point=origin+direction*next;float advance=fminf(epsilon(point),fmaxf(distance-next,0.f));
            if(advance>0.f){const int nextDesc=descriptor(b,&stack);const float3 nextSigma=sigmaT(b,&stack),nextEmission=emittedRgb(b,&stack);
                float mass=nextDesc<0?advance:rtr_density::mass(b,nextDesc,point,direction,advance,u[25]);
                emitted=add(emitted,componentMul(expT(optical),emissionIntegral(nextSigma,nextEmission,mass)));optical=add(optical,mul(nextSigma,mass));}
            previous=next+advance;
        }
    }
    const unsigned int global=u[4]*u[3]+path;
    ((float4*)(b+u[11]))[global]=make_float4(expT(optical).x,expT(optical).y,expT(optical).z,1.f);
    ((float4*)(b+u[12]))[global]=make_float4(emitted.x,emitted.y,emitted.z,0.f);
    s->random.w=collided?collisionEvents:events;if(!collided)s->control.x=0u;
}

extern "C" __global__ void boundary_scatter_connections(unsigned char*b,int count){
    int i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=count)return;using namespace rtr_boundary_scatter;
    unsigned int*u=words(b);State*s=states(b)+i;if(s->control.x!=2u)return;
    const unsigned int connections=u[42],epoch=s->control.z;
    const int rayBase=(int)u[64],packetBase=(int)u[49],outputBase=(int)u[55];
    const float3 incident=rgb(s->direction);rtr_medium::PathVolumeInfo*current=volume(b,i);float3 incoming=rgb(s->lighting);
    for(unsigned int k=0;k<connections;++k){const unsigned int index=(unsigned int)i*connections+k;
        const RayRecord&r=((const RayRecord*)(b+rayBase))[index];const float3 raw=rgb(r.sourceAndVisibility);
        Connection&out=((Connection*)(b+outputBase))[index];out.source=make_float4(0.f);out.direction=make_float4(0.f);
        if(r.identity.x!=epoch||r.identity.y!=(unsigned int)i||r.identity.z!=u[43]+k||r.identity.w!=32u){stateFail(b,s);return;}
        if(r.sourceAndVisibility.w<=0.f||(raw.x==0.f&&raw.y==0.f&&raw.z==0.f))continue;
        float3 optical=make_float3(0.f),unused=make_float3(0.f);unsigned int shadowEvents=0u;
        if((__float_as_uint(r.directionAndFlags.w)&2u)!=0u){rtr_medium::PathVolumeInfo shadow=*current;
            const int packet=packetBase+(int)index*(int)u[54];
            if(!walk(b,packet,rgb(r.originAndDistance),rgb(r.directionAndFlags),r.originAndDistance.w,
                    &shadow,&optical,&unused,&shadowEvents)){stateFail(b,s);return;}}
        const float phase=rtr_medium::hgEvaluate(dot(incident,rgb(r.directionAndFlags)),anisotropy(b,current));
        const float3 value=mul(componentMul(raw,expT(optical)),phase);incoming=add(incoming,value);
        out.source=make_float4(value.x,value.y,value.z,1.f);out.direction=r.directionAndFlags;
    }
    s->lighting=make_float4(incoming.x,incoming.y,incoming.z,0.f);
}

extern "C" __global__ void boundary_scatter_continue(unsigned char*b,int count){
    int i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=count)return;using namespace rtr_boundary_scatter;
    unsigned int*u=words(b);State*s=states(b)+i;if(s->control.x!=2u)return;
    float3 radiance=add(rgb(s->radiance),componentMul(rgb(s->throughput),rgb(s->lighting)));
    if(s->control.w>=u[39]) {
        if(!finite3(radiance)){stateFail(b,s);return;}
        s->radiance=make_float4(radiance.x,radiance.y,radiance.z,0.f);s->control.x=0u;return;
    }
    const float g=anisotropy(b,volume(b,i));float phasePdf=0.f;const unsigned int seed=s->random.x^(s->control.z*0x9e3779b9u);
    const float3 outgoing=rtr_medium::hgSample(rgb(s->direction),g,random(seed),random(seed^0xd3a2646cu),&phasePdf);
    if(!isfinite(phasePdf)||phasePdf<=0.f||!finite3(outgoing)||!isfinite(radiance.x)||!isfinite(radiance.y)||!isfinite(radiance.z)){
        stateFail(b,s);return;}
    s->radiance=make_float4(radiance.x,radiance.y,radiance.z,0.f);s->direction=make_float4(outgoing.x,outgoing.y,outgoing.z,0.f);
    s->lighting=make_float4(0.f);++s->control.w;++s->control.z;s->control.x=1u;
}

extern "C" __global__ void boundary_scatter_step(unsigned char*b,int count){
    int i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=count)return;using namespace rtr_boundary_scatter;
    unsigned int*u=words(b);float*h=floats(b);State*s=states(b)+i;if(s->control.x!=1u)return;
    const RayRecord&r=((const RayRecord*)(b+u[64]))[i];
    if(r.identity.x!=s->control.z||r.identity.y!=(unsigned int)i||r.identity.w!=16u){stateFail(b,s);return;}
    const int packet=(int)u[9]+i*(int)u[54];const unsigned int eventsInPacket=packetCount(b,packet);
    const float hit=terminalDistance(b,packet),remaining=s->origin.w,end=hit<0.f?remaining:fminf(hit,remaining);
    if(!isfinite(end)||end<0.f||eventsInPacket>u[27]){stateFail(b,s);return;}
    const float3 origin=rgb(s->origin),direction=rgb(s->direction);rtr_medium::PathVolumeInfo*saved=volume(b,i);
    rtr_medium::PathVolumeInfo current=*saved;float3 optical=make_float3(0.f),radiance=rgb(s->radiance);
    float target=-logf(fmaxf(1.f-random(s->random.x^s->control.z*0x85ebca6bu),1e-30f));
    const unsigned int channel=min((unsigned int)(random(s->random.x^s->control.z*0xc2b2ae35u)*3.f),2u);
    bool collided=false;unsigned int events=s->random.w;float previous=0.f;float3 throughput=rgb(s->throughput);
    for(unsigned int segment=0;segment<=eventsInPacket;++segment){float next=end;unsigned int code=0u;
        if(segment<eventsInPacket){float d;packetEvent(b,packet,segment,&d,&code);if(!isfinite(d)||d<previous){stateFail(b,s);return;}if(d>=end)continue;next=d;}
        const float length=fmaxf(next-previous,0.f),dt=length/(float)u[25];const int desc=descriptor(b,&current);
        const float3 st=sigmaT(b,&current),ss=sigmaS(b,&current),e=emittedRgb(b,&current);
        for(unsigned int j=0;j<u[25];++j){if(dt<=0.f)break;const float t=previous+(j+.5f)*dt;float rho=1.f;
            if(desc>=0){const rtr_density::Descriptor&grid=*(const rtr_density::Descriptor*)(b+(unsigned int)desc);
                rho=rtr_density::DensityGridTexture_ConstEvaluate((const float*)(b+grid.atlasOffset),grid,rtr_density::map(grid,origin+direction*t,1.f));}
            if(!isfinite(rho)||rho<0.f){stateFail(b,s);return;}const float mass=rho*dt;float3 before=expT(optical);
            const float rate=(channel==0?st.x:channel==1?st.y:st.z)*rho;
            if(!collided&&rate>0.f&&target<rate*dt){const float local=target/rate;const float3 atOptical=add(optical,mul(st,rho*local));
                const float pdf=(st.x*expf(-atOptical.x)+st.y*expf(-atOptical.y)+st.z*expf(-atOptical.z))*(rho/3.f);
                if(!(pdf>0.f)||!isfinite(pdf)){stateFail(b,s);return;}
                radiance=add(radiance,componentMul(throughput,componentMul(before,emissionIntegral(st,e,rho*local))));
                const float3 point=origin+direction*(previous+j*dt+local);throughput=componentMul(throughput,componentMul(expT(atOptical),mul(ss,rho/pdf)));
                s->origin=make_float4(point.x,point.y,point.z,fmaxf(remaining-(previous+j*dt+local),0.f));
                *saved=current;s->throughput=make_float4(throughput.x,throughput.y,throughput.z,0.f);
                s->radiance=make_float4(radiance.x,radiance.y,radiance.z,0.f);s->control.x=2u;
                s->random.w=events;collided=true;atomicAdd(u+30,1u);optical=atOptical;
                break;
            }
            radiance=add(radiance,componentMul(throughput,componentMul(before,emissionIntegral(st,e,mass))));
            if(rate>0.f)target-=rate*dt;
            optical=add(optical,mul(st,mass));
        }
        if(collided)break;
        if(segment<eventsInPacket){if(++events>u[6]||!transition(b,&current,code)){stateFail(b,s);return;}
            const float3 point=origin+direction*next;const float advance=fminf(epsilon(point),fmaxf(end-next,0.f));
            if(advance>0.f){const int nextDesc=descriptor(b,&current);const float3 nextSigma=sigmaT(b,&current),nextEmission=emittedRgb(b,&current);
                const float mass=nextDesc<0?advance:rtr_density::mass(b,nextDesc,point,direction,advance,u[25]);
                radiance=add(radiance,componentMul(throughput,componentMul(expT(optical),emissionIntegral(nextSigma,nextEmission,mass))));
                optical=add(optical,mul(nextSigma,mass));}
            previous=next+advance;
        }
    }
    if(collided){s->random.w=events;return;}
    const float survival=mixSurvival(optical);const float3 terminal=rgb(r.sourceAndVisibility),trans=expT(optical);
    if(survival>0.f)radiance=add(radiance,mul(componentMul(throughput,componentMul(trans,terminal)),1.f/survival));
    if(!isfinite(radiance.x)||!isfinite(radiance.y)||!isfinite(radiance.z)){stateFail(b,s);return;}
    s->radiance=make_float4(radiance.x,radiance.y,radiance.z,0.f);s->control.x=0u;s->random.w=events;s->hit.w=r.sourceAndVisibility.w;
    atomicAdd(u+31,1u);
}

extern "C" __global__ void boundary_scatter_reduce(unsigned char*b,int count){
    int i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=count)return;using namespace rtr_boundary_scatter;
    unsigned int*u=words(b);State*s=states(b)+i;if(s->control.x!=0u){atomicAdd(u+29,1u);return;}
    const unsigned int global=u[4]*u[3]+(unsigned int)i;float4*out=(float4*)(b+u[12]);
    const float3 total=add(rgb(out[global]),componentMul(rgb(s->weight),rgb(s->radiance)));
    if(!isfinite(total.x)||!isfinite(total.y)||!isfinite(total.z)){atomicAdd(u+29,1u);return;}
    out[global]=make_float4(total.x,total.y,total.z,0.f);
}
