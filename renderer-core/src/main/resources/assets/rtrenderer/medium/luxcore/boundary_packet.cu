// Straight volume-only rays permit one ordered boundary packet without host bounce scheduling.
// Priority, segment integration and public termination rules are the same as boundary_transport.cu.
__device__ void rtr_boundary_packet_fail(unsigned char *buffer,int index,unsigned int &failed,
        unsigned int status,unsigned int event=0u) {
    const unsigned int *h=(const unsigned int *)buffer;
    // Error-only metadata; successful output and the original event packet are untouched.
    ((float4 *)(buffer+h[11]))[index]=make_float4(0.f,0.f,0.f,-float(status*256u+event));
    // A deferred validation consumer must never dereference an unwritten primary on a failed path.
    ((float4 *)(buffer+h[12]))[index]=make_float4(0.f,0.f,0.f,(h[27]&512u)!=0u?1.f:0.f);
    failed=1u;
}
__device__ void rtr_boundary_packet_finish(unsigned char *buffer,int index,const rtr_boundary::State &state) {
    const unsigned int *h=(const unsigned int *)buffer;
    ((float4 *)(buffer+h[11]))[index]=make_float4(state.transport.x,state.transport.y,state.transport.z,1.f);
    float4 terminal=((float4 *)(buffer+h[12]))[index];
    if((h[27]&512u)!=0u&&terminal.w==1.f) {
        ((float4 *)(buffer+h[12]))[index]=make_float4(__fadd_rn(__fmul_rn(terminal.x,state.transport.x),state.emission.x),
                __fadd_rn(__fmul_rn(terminal.y,state.transport.y),state.emission.y),__fadd_rn(__fmul_rn(terminal.z,state.transport.z),state.emission.z),1.f);
    } else ((float4 *)(buffer+h[12]))[index]=make_float4(state.emission.x,state.emission.y,state.emission.z,0.f);

}
__device__ void rtr_boundary_packet_resolve_one(unsigned char *buffer,int i,unsigned int &failed,unsigned int &crossed) {
    using namespace rtr_boundary;
    unsigned int *h=(unsigned int *)buffer;float *f=(float *)buffer;
    const unsigned int capacity=h[27]&255u,terminal=4+4*((capacity+1)/2),stride=terminal+4;
    if(capacity<1u||capacity>32u){rtr_boundary_packet_fail(buffer,i,failed,BAD_HEADER);return;}
    const unsigned int *packet=(const unsigned int *)(buffer+h[9])+i*stride;
    State state;unsigned int *words=(unsigned int *)&state;for(int j=0;j<68;++j)words[j]=0;
    state.transport=make_float4(1.f,1.f,1.f,f[19]);
    state.direction=make_float4(__uint_as_float(packet[0]),__uint_as_float(packet[1]),__uint_as_float(packet[2]),0.f);
    if(!initializeVolumes(buffer,&state)||packet[3]>h[6]||packet[3]>capacity){rtr_boundary_packet_fail(buffer,i,failed,BAD_HEADER);return;}
    const float hitDistance=__uint_as_float(packet[terminal]);
    const bool hit=hitDistance>=0.f;const float end=hit?fminf(hitDistance,f[19]):f[19];
    if(!isfinite(end)||end<0.f||!finite3(rgb(state.direction))){rtr_boundary_packet_fail(buffer,i,failed,BAD_HIT);return;}
    float3 origin=make_float3(__uint_as_float(packet[terminal+1]),__uint_as_float(packet[terminal+2]),__uint_as_float(packet[terminal+3]));
    float3 direction=rgb(state.direction);
    float previous=0.f;
    for(unsigned int j=0;j<packet[3];++j) {
        const float distance=__uint_as_float(packet[4+j*2]);const unsigned int code=packet[5+j*2],index=code&0x7fffffffu;
        if(!isfinite(distance)||distance<0.f||index>=h[7]){rtr_boundary_packet_fail(buffer,i,failed,BAD_HIT,j+1u);return;}
        if(distance<previous)continue; // Same epsilon exclusion as the ordered closest-hit route.
        if(distance>=end)break;
        if(!segment(buffer,&state,origin+direction*previous,distance-previous)){rtr_boundary_packet_fail(buffer,i,failed,BAD_HIT,j+1u);return;}
        bool entering=(code&0x80000000u)!=0u;
        if(entering)for(unsigned int k=0;k<state.volumes.volumeIndexListSize;++k)
            if(state.volumes.volumeIndexList[k]==index){rtr_boundary_packet_fail(buffer,i,failed,DUPLICATE_ENTRY,j+1u);return;}
        rtr_medium::Status status=entering?rtr_medium::add(&state.volumes,index,priorities(buffer),h[7])
                :rtr_medium::remove(&state.volumes,index,priorities(buffer),h[7]);
        if(status!=rtr_medium::OK){rtr_boundary_packet_fail(buffer,i,failed,STACK_ERROR,j+1u);return;}
        ++crossed;
        if(j+1u>=h[6]||peak(rgb(state.transport))<=f[23]){
            state.control.x=FINISHED;state.counters.w=2u;state.transport.x=state.transport.y=state.transport.z=0.f;
            rtr_boundary_packet_finish(buffer,i,state);return;
        }
        float3 point=origin+direction*distance;
        const float epsilon=fmaxf(1e-5f,peak(make_float3(fabsf(point.x),fabsf(point.y),fabsf(point.z)))*2e-7f);
        const float advance=fminf(epsilon,fmaxf(end-distance,0.f));
        if(!segment(buffer,&state,point,advance)){rtr_boundary_packet_fail(buffer,i,failed,BAD_HIT,j+1u);return;}
        previous=distance+advance;
    }
    if(!segment(buffer,&state,origin+direction*previous,fmaxf(end-previous,0.f))){rtr_boundary_packet_fail(buffer,i,failed,BAD_HIT);return;}
    state.control.x=FINISHED;state.counters.w=hit?0u:1u;rtr_boundary_packet_finish(buffer,i,state);
}

// Every path has finished before the host reads statistics. Aggregate exact path counts
// per warp to avoid millions of contended global atomics without changing transport.
extern "C" __global__ void native_boundary_packet_resolve(unsigned char *buffer,int count) {
    const int i=blockIdx.x*blockDim.x+threadIdx.x;
    unsigned int failed=0u,crossed=0u,completed=i<count?1u:0u;
    if(i<count)rtr_boundary_packet_resolve_one(buffer,i,failed,crossed);
    for(unsigned int offset=16u;offset>0u;offset>>=1u) {
        failed+=__shfl_down_sync(0xffffffffu,failed,offset);
        crossed+=__shfl_down_sync(0xffffffffu,crossed,offset);
        completed+=__shfl_down_sync(0xffffffffu,completed,offset);
    }
    if((threadIdx.x&31u)==0u) {
        unsigned int *h=(unsigned int *)buffer;
        if(failed)atomicAdd(h+29,failed);
        if(crossed)atomicAdd(h+30,crossed);
        if(completed) {atomicSub(h+28,completed);atomicAdd(h+31,completed);}
    }
}
