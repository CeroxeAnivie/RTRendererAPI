// RTRendererAPI production glue. The segment and medium-priority functions remain pinned LuxCore code.
namespace rtr_boundary {
enum { FINISHED=0, NEW_RAY=1, CONTINUE_RAY=2 };
enum { OK=0, EPOCH=100, BAD_HIT=101, UNKNOWN_BOUNDARY=102, STACK_ERROR=103,
       DUPLICATE_ENTRY=104, UNSUPPORTED_SURFACE=105, BAD_HEADER=106 };
struct __align__(16) State {
    float4 origin, direction;
    uint4 control;
    float4 transport, emission;
    uint4 counters;
    float4 cameraOrigin, reserved;
    rtr_medium::PathVolumeInfo volumes;
};
struct __align__(16) Boundary {
    unsigned int slot; int priority; unsigned int orientation, flags;
    float4 absorption, emission, reserved;
};
static_assert(sizeof(State)==272,"native boundary state ABI");
static_assert(sizeof(Boundary)==64,"native boundary table ABI");

__device__ float3 rgb(float4 v) { return make_float3(v.x,v.y,v.z); }
__device__ bool finite3(float3 v) { return isfinite(v.x)&&isfinite(v.y)&&isfinite(v.z); }
__device__ float peak(float3 v) { return fmaxf(v.x,fmaxf(v.y,v.z)); }
__device__ State *states(unsigned char *buffer) { return (State *)(buffer+((unsigned int *)buffer)[9]); }
__device__ float *payload(unsigned char *buffer,int i) { return (float *)(buffer+((unsigned int *)buffer)[10])+i*52; }
__device__ const Boundary *table(unsigned char *buffer) { return (const Boundary *)(buffer+((unsigned int *)buffer)[13]); }
__device__ const rtr_medium::Material *priorities(unsigned char *buffer) {
    return (const rtr_medium::Material *)(buffer+((unsigned int *)buffer)[14]);
}
__device__ void storeOutput(unsigned char *buffer,int i,const State &state) {
    const unsigned int *h=(const unsigned int *)buffer;
    ((float4 *)(buffer+h[11]))[i]=make_float4(state.transport.x,state.transport.y,state.transport.z,1.f);
    ((float4 *)(buffer+h[12]))[i]=make_float4(state.emission.x,state.emission.y,state.emission.z,0.f);
}
__device__ void blackPayload(float *p) {
    for(int j=0;j<52;++j) p[j]=0.f;
    p[3]=-1.f; p[19]=1.f;
}
__device__ void fail(unsigned char *buffer,int i,State *state,unsigned int status) {
    state->control.x=FINISHED; state->control.y=status;
    state->transport.x=state->transport.y=state->transport.z=0.f;
    state->emission.x=state->emission.y=state->emission.z=0.f;
    blackPayload(payload(buffer,i)); storeOutput(buffer,i,*state);
    atomicAdd((unsigned int *)buffer+29,1u);
}
__device__ bool initializeVolumes(unsigned char *buffer,State *state) {
    unsigned int *h=(unsigned int *)buffer;
    if(h[0]!=0x52564d31u || h[1]!=1u || h[8]>32u || h[6]<1u || h[6]>32u) return false;
    rtr_medium::PathVolumeInfo_Init(&state->volumes);
    const unsigned int *initial=(const unsigned int *)(buffer+h[15]);
    for(unsigned int j=0;j<h[8];++j)
        if(rtr_medium::add(&state->volumes,initial[j],priorities(buffer),h[7])!=rtr_medium::OK) return false;
    state->counters.z=1u;
    return true;
}
template<class StateType>
__device__ int coefficients(unsigned char *buffer,const StateType &state,float3 *a,float3 *e) {
    unsigned int selected=state.volumes.currentVolumeIndex;
    if(selected==0xffffffffu) {
        const float *h=(const float *)buffer;
        *a=make_float3(h[16],h[17],h[18]); *e=make_float3(h[20],h[21],h[22]);
        return ((const int *)buffer)[24];
    } else {
        *a=rgb(table(buffer)[selected].absorption); *e=rgb(table(buffer)[selected].emission);
        return ((const int *)&table(buffer)[selected].reserved)[0];
    }
}
template<class StateType>
__device__ bool segment(unsigned char *buffer,StateType *state,float3 origin,float distance) {
    distance=fminf(fmaxf(distance,0.f),state->transport.w);
    float3 a,e,tr,upstreamEmission,zero=make_float3(0.f);
    const int descriptor=coefficients(buffer,*state,&a,&e);
    const float mass=rtr_density::mass(buffer,descriptor,origin,rgb(state->direction),distance,((const unsigned int *)buffer)[25]);
    if(!isfinite(mass)||mass<0.f) return false;
    HomogeneousVolume_SegmentScatter(.5f,false,mass,&a,&zero,&zero,&tr,&upstreamEmission);
    float3 integrated=make_float3(rtrApiIntegratedEmission(a.x,e.x,mass),
        rtrApiIntegratedEmission(a.y,e.y,mass),rtrApiIntegratedEmission(a.z,e.z,mass));
    float3 accumulated=rgb(state->emission)+rgb(state->transport)*integrated;
    float3 throughput=rgb(state->transport)*tr;
    state->emission.x=accumulated.x; state->emission.y=accumulated.y; state->emission.z=accumulated.z;
    state->transport.x=throughput.x; state->transport.y=throughput.y; state->transport.z=throughput.z;
    state->transport.w=fmaxf(state->transport.w-distance,0.f);
    return finite3(accumulated)&&finite3(throughput);
}
__device__ int boundaryIndex(unsigned char *buffer,unsigned int slot) {
    const Boundary *entries=table(buffer); unsigned int low=0,high=((unsigned int *)buffer)[7];
    while(low<high) { unsigned int middle=low+(high-low)/2;
        if(entries[middle].slot<slot) low=middle+1; else high=middle; }
    return low<((unsigned int *)buffer)[7] && entries[low].slot==slot?(int)low:-1;
}
__device__ void finish(unsigned char *buffer,int i,State *state,unsigned int kind,bool black) {
    if(black) { blackPayload(payload(buffer,i)); state->transport.x=state->transport.y=state->transport.z=0.f; }
    state->control.x=FINISHED; state->counters.w=kind;
    storeOutput(buffer,i,*state); atomicAdd((unsigned int *)buffer+31,1u);
}
} // namespace rtr_boundary

// Optional initial CUDA setup. Production can instead initialize state in the first real Vulkan query.
extern "C" __global__ void native_boundary_init(unsigned char *buffer,int count) {
    int i=blockIdx.x*blockDim.x+threadIdx.x; if(i>=count) return;
    rtr_boundary::State *s=rtr_boundary::states(buffer)+i;
    unsigned int *words=(unsigned int *)s; for(int j=0;j<68;++j) words[j]=0u;
    const unsigned int *h=(const unsigned int *)buffer;
    s->control=make_uint4(rtr_boundary::NEW_RAY,0u,h[4]*h[3]+(unsigned int)i,1u);
    s->transport=make_float4(1.f,1.f,1.f,((const float *)buffer)[19]);
    if(!rtr_boundary::initializeVolumes(buffer,s)) rtr_boundary::fail(buffer,i,s,rtr_boundary::BAD_HEADER);
}

// Clear only per-round aggregate counters; finished rays are skipped and never resubmitted.
extern "C" __global__ void native_boundary_reset(unsigned char *buffer,int count) {
    if(blockIdx.x==0 && threadIdx.x==0) { unsigned int *h=(unsigned int *)buffer;
        h[28]=h[29]=h[30]=h[31]=0u; }
}

extern "C" __global__ void native_boundary_step(unsigned char *buffer,int count) {
    int i=blockIdx.x*blockDim.x+threadIdx.x; if(i>=count) return;
    using namespace rtr_boundary;
    unsigned int *h=(unsigned int *)buffer;
    State *s=states(buffer)+i; if(s->control.x==FINISHED) return;
    if(!s->counters.z && !initializeVolumes(buffer,s)) {fail(buffer,i,s,BAD_HEADER);return;}
    float *p=payload(buffer,i); unsigned int *u=(unsigned int *)p;
    if(u[51]!=s->control.w) {fail(buffer,i,s,EPOCH);return;}
    const bool hit=u[28]!=0u;
    const float distance=hit?p[3]:s->transport.w;
    if(!isfinite(distance)||distance<0.f || !finite3(rgb(s->direction))) {fail(buffer,i,s,BAD_HIT);return;}
    if(!segment(buffer,s,rgb(s->origin),distance)) {fail(buffer,i,s,BAD_HIT);return;}
    if(hit) s->emission.w+=distance;
    ++s->counters.y;
    if(!hit) {p[3]=-1.f;finish(buffer,i,s,1u,false);return;}
    ++s->counters.x;
    if((u[47]&2u)==0u) {
        if(u[47]!=0u) {fail(buffer,i,s,UNSUPPORTED_SURFACE);return;}
        p[3]=s->emission.w; finish(buffer,i,s,0u,false);return;
    }
    const int compact=boundaryIndex(buffer,u[44]);
    if(compact<0) {fail(buffer,i,s,UNKNOWN_BOUNDARY);return;}
    const Boundary entry=table(buffer)[compact];
    if(u[45]!=(unsigned int)entry.priority || u[46]!=entry.orientation || u[47]!=entry.flags) {
        fail(buffer,i,s,UNKNOWN_BOUNDARY);return;
    }
    const bool entering=(u[29]==2u)==(entry.orientation!=0u);
    if(entering) {
        for(unsigned int j=0;j<s->volumes.volumeIndexListSize;++j)
            if(s->volumes.volumeIndexList[j]==(unsigned int)compact) {fail(buffer,i,s,DUPLICATE_ENTRY);return;}
    }
    const rtr_medium::Status status=entering
        ?rtr_medium::add(&s->volumes,(unsigned int)compact,priorities(buffer),h[7])
        :rtr_medium::remove(&s->volumes,(unsigned int)compact,priorities(buffer),h[7]);
    if(status!=rtr_medium::OK) {fail(buffer,i,s,STACK_ERROR);return;}
    atomicAdd(h+30,1u);
    if(s->counters.x>=h[6]) {finish(buffer,i,s,2u,true);return;}
    if(peak(rgb(s->transport))<=((float *)buffer)[23]) {finish(buffer,i,s,3u,true);return;}
    float3 point=make_float3(p[0],p[1],p[2]),direction=rgb(s->direction);
    if(!finite3(point)) {fail(buffer,i,s,BAD_HIT);return;}
    const float epsilon=fmaxf(1e-5f,peak(make_float3(fabsf(point.x),fabsf(point.y),fabsf(point.z)))*2e-7f);
    // The origin advances through the newly selected medium; integrate that displacement too.
    if(!segment(buffer,s,point,epsilon)) {fail(buffer,i,s,BAD_HIT);return;}
    point+=direction*epsilon; s->origin=make_float4(point.x,point.y,point.z,0.f);
    s->emission.w+=epsilon; s->control.x=CONTINUE_RAY; ++s->control.w;
    atomicAdd(h+28,1u);
}
