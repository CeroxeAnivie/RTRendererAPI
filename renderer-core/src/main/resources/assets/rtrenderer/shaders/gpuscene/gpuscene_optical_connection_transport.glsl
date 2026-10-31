// LuxCore b872186d2fe995a762f73708c606847eed55843e, Apache-2.0.
// Adapt the pinned density interpolation, Beer segment, FresnelCauchy and
// PathVolumeInfo rules from the CUDA adapter to the shared Vulkan connection.
// PBRT-v4 b0bdf461 TraceTransmittance motivates the GPU-internal loop, not this ABI.
uint gsConnectionWord(uint word) {return gsOptical.storage.lanes[word/4u][word%4u];}
float gsConnectionFloat(uint word) {return uintBitsToFloat(gsConnectionWord(word));}
uint gsConnectionVolume(uint base,uint word) {return gsOptical.storage.lanes[base+6u+word/4u][word%4u];}
void gsConnectionVolumeWrite(uint base,uint word,uint value) {gsOptical.storage.lanes[base+6u+word/4u][word%4u]=value;}
bool gsConnectionFinite(vec3 v) {return !any(isnan(v))&&!any(isinf(v));}
float gsConnectionIor(uint selected) {return uintBitsToFloat(selected==GS_INVALID_SLOT?
    gsOptical.storage.lanes[5u].w:gsOptical.storage.lanes[25u+selected*4u].w);}
int gsConnectionPriority(uint selected) {return int(gsOptical.storage.lanes[24u+selected*4u].y);}
float gsConnectionDensityFetch(uvec4 grid,ivec3 p) {
    p=clamp(p,ivec3(0),ivec3(grid.yzw)-ivec3(1));
    uint index=(uint(p.z)*grid.z+uint(p.y))*grid.y+uint(p.x);
    return gsConnectionFloat(grid.x/4u+index);
}
float gsConnectionDensity(uvec4 grid,vec3 p) {
    if(!gsConnectionFinite(p))return uintBitsToFloat(0x7fc00000u);
    if(any(lessThan(p,vec3(0)))||any(greaterThan(p,vec3(1))))return 0.0;
    precise vec3 v=p*vec3(grid.yzw)-vec3(.5);
    ivec3 cell=ivec3(floor(v));v-=vec3(cell);
    return mix(mix(mix(gsConnectionDensityFetch(grid,cell),gsConnectionDensityFetch(grid,cell+ivec3(1,0,0)),v.x),
                   mix(gsConnectionDensityFetch(grid,cell+ivec3(0,1,0)),gsConnectionDensityFetch(grid,cell+ivec3(1,1,0)),v.x),v.y),
               mix(mix(gsConnectionDensityFetch(grid,cell+ivec3(0,0,1)),gsConnectionDensityFetch(grid,cell+ivec3(1,0,1)),v.x),
                   mix(gsConnectionDensityFetch(grid,cell+ivec3(0,1,1)),gsConnectionDensityFetch(grid,cell+ivec3(1,1,1)),v.x),v.y),v.z);
}
bool gsConnectionClip(float origin,float direction,inout float first,inout float last) {
    if(direction==0.0)return origin>=0.0&&origin<=1.0;
    precise float a=(0.0-origin)/direction,b=(1.0-origin)/direction;
    first=max(first,min(a,b));last=min(last,max(a,b));return last>first;
}
float gsConnectionMass(int descriptor,vec3 origin,vec3 direction,float distance) {
    if(isnan(distance)||isinf(distance)||distance<0.0)return uintBitsToFloat(0x7fc00000u);
    if(descriptor<0)return distance;
    if(distance<=0.0)return 0.0;
    uint base=uint(descriptor)/16u;uvec4 grid=gsOptical.storage.lanes[base];
    vec4 row0=uintBitsToFloat(gsOptical.storage.lanes[base+1u]);
    vec4 row1=uintBitsToFloat(gsOptical.storage.lanes[base+2u]);
    vec4 row2=uintBitsToFloat(gsOptical.storage.lanes[base+3u]);
    precise vec3 p=vec3(row0.x*origin.x+row0.y*origin.y+row0.z*origin.z+row0.w,
        row1.x*origin.x+row1.y*origin.y+row1.z*origin.z+row1.w,
        row2.x*origin.x+row2.y*origin.y+row2.z*origin.z+row2.w);
    precise vec3 v=vec3(row0.x*direction.x+row0.y*direction.y+row0.z*direction.z,
        row1.x*direction.x+row1.y*direction.y+row1.z*direction.z,
        row2.x*direction.x+row2.y*direction.y+row2.z*direction.z);
    uint steps=gsOptical.storage.lanes[9u].w;
    if(!gsConnectionFinite(p)||!gsConnectionFinite(v)||steps<1u||steps>64u||any(equal(grid.yzw,uvec3(0))))
        return uintBitsToFloat(0x7fc00000u);
    float first=0.0,last=distance;
    if(!gsConnectionClip(p.x,v.x,first,last)||!gsConnectionClip(p.y,v.y,first,last)||!gsConnectionClip(p.z,v.z,first,last))return 0.0;
    precise float dt=(last-first)/float(steps),integrated=0.0;
    for(uint i=0u;i<steps;++i){precise float t=first+(float(i)+.5)*dt;integrated+=gsConnectionDensity(grid,p+v*t);}
    return integrated*dt;
}
int gsConnectionBoundary(uint slot) {
    uint low=0u,high=gsOptical.storage.lanes[1u].w;
    while(low<high){uint mid=low+(high-low)/2u;if(gsOptical.storage.lanes[24u+mid*4u].x<slot)low=mid+1u;else high=mid;}
    return low<gsOptical.storage.lanes[1u].w&&gsOptical.storage.lanes[24u+low*4u].x==slot?int(low):-1;
}
float gsConnectionFresnel(float eta,float cosine) {
    precise float eta2=eta*eta,sine2=(1.0/eta2)*max(0.0,1.0-cosine*cosine);
    if(sine2>=1.0)return 1.0;
    precise float transmitted=sqrt(max(0.0,1.0-sine2));
    precise float parallel=eta*cosine;parallel=(transmitted-parallel)/(transmitted+parallel);
    precise float perpendicular=eta*transmitted;perpendicular=(cosine-perpendicular)/(cosine+perpendicular);
    return (parallel*parallel+perpendicular*perpendicular)*.5;
}
// 0 completed, 1 next segment, 2 unchanged-hit CUDA fallback, negative original rejection.
int gsConnectionTransportStep(uint base,GpuScenePayload hit) {
    bool found=hit.state.x!=0u;vec4 origin=uintBitsToFloat(gsOptical.storage.lanes[base]);
    vec4 direction=uintBitsToFloat(gsOptical.storage.lanes[base+1u]);
    vec4 transport=uintBitsToFloat(gsOptical.storage.lanes[base+3u]);
    float distance=found?hit.worldPositionAndDistance.w:origin.w;
    if(isnan(distance)||isinf(distance)||distance<direction.w)return -1;
    precise float travel=min(distance,origin.w)-direction.w;
    travel=min(max(travel,0.0),transport.w);
    uint selected=gsConnectionVolume(base,0u);
    vec3 absorption=uintBitsToFloat(selected==GS_INVALID_SLOT?gsOptical.storage.lanes[5u].xyz:gsOptical.storage.lanes[25u+selected*4u].xyz);
    vec3 scattering=uintBitsToFloat(selected==GS_INVALID_SLOT?gsOptical.storage.lanes[10u].xyz:gsOptical.storage.lanes[27u+selected*4u].xyz);
    int descriptor=int(selected==GS_INVALID_SLOT?gsOptical.storage.lanes[9u].z:gsOptical.storage.lanes[27u+selected*4u].w);
    precise vec3 start=origin.xyz+direction.xyz*direction.w;
    float mass=gsConnectionMass(descriptor,start,direction.xyz,travel);
    if(isnan(mass)||isinf(mass)||mass<0.0)return -2;
    precise vec3 extinction=absorption+scattering;
    precise vec3 weight=transport.xyz*exp(-(mass*extinction));
    if(!gsConnectionFinite(weight))return -2;
    vec4 nextTransport=vec4(weight,max(transport.w-travel,0.0));
    if(!found||distance>=origin.w){gsOptical.storage.lanes[base+3u]=floatBitsToUint(nextTransport);return 0;}
    uvec4 control=gsOptical.storage.lanes[base+5u];
    if(control.z+1u>=gsOptical.storage.lanes[1u].z){
        ++control.z;gsOptical.storage.lanes[base+5u]=control;
        nextTransport.xyz=vec3(0);gsOptical.storage.lanes[base+3u]=floatBitsToUint(nextTransport);return 0;
    }
    uint flags=hit.mediumBoundary.w,size=gsConnectionVolume(base,33u),nextSelected=selected,removeAt=GS_INVALID_SLOT;
    int index=-1;bool entering=false;
    if(flags!=0u){
        index=gsConnectionBoundary(hit.mediumBoundary.x);if(index<0)return -3;
        uvec4 entry=gsOptical.storage.lanes[24u+uint(index)*4u];
        if(hit.mediumBoundary.y!=entry.y||hit.mediumBoundary.z!=entry.z||flags!=entry.w)return -4;
        entering=(hit.state.y==2u)==(entry.z!=0u);
        if(size>32u)return -6;
        bool present=selected==GS_INVALID_SLOT&&size==0u;
        for(uint i=0u;i<size;++i){
            uint volume=gsConnectionVolume(base,1u+i);
            if(volume>=gsOptical.storage.lanes[1u].w)return -6;
            if(volume==selected)present=true;
            if(volume==uint(index))removeAt=i;
        }
        if(!present)return -6;
        if(entering){
            if(removeAt!=GS_INVALID_SLOT)return -5;
            if(size==32u)return -6;
            if(selected==GS_INVALID_SLOT||gsConnectionPriority(uint(index))>=gsConnectionPriority(selected))nextSelected=uint(index);
        }else{
            if(removeAt==GS_INVALID_SLOT)return -6;
            nextSelected=GS_INVALID_SLOT;
            for(uint i=0u;i<size;++i)if(i!=removeAt){uint volume=gsConnectionVolume(base,1u+i);
                if(nextSelected==GS_INVALID_SLOT||gsConnectionPriority(volume)>=gsConnectionPriority(nextSelected))nextSelected=volume;}
        }
    }
    if((flags&2u)==0u){
        float opacity=flags!=0u?1.0:clamp(hit.baseColorAndOpacity.a,0.0,1.0),transmission=clamp(hit.transmissionIor.x,0.0,1.0);
        float outside=gsConnectionIor(selected);
        float n1=hit.state.y==2u?hit.transmissionIor.y:outside,n2=hit.state.y==2u?outside:hit.transmissionIor.y;
        if(flags!=0u){n1=outside;n2=gsConnectionIor(nextSelected);}
        float fresnel=0.0;
        if(n1!=n2){
            vec3 normal=normalize(hit.worldNormalAndRoughness.xyz);precise float cosine=max(dot(-direction.xyz,normal),0.0),eta=n2/n1;
            // Retain the CUDA adapter's double-precision extreme/grazing path.
            // No global state has changed, so its original hit can be consumed once.
            if(!gsConnectionFinite(normal)||isnan(eta)||isinf(eta)||eta<1.0e-7||eta>1.0e7||cosine<=1.0e-5)return 2;
            fresnel=gsConnectionFresnel(eta,cosine);if(isnan(fresnel)||isinf(fresnel))return -7;
        }
        vec3 tint=clamp(hit.baseColorAndOpacity.rgb,vec3(0),vec3(1));
        precise vec3 factor=vec3(1.0-opacity)+tint*(opacity*transmission*(1.0-fresnel));nextTransport.xyz*=factor;
    }
    // Commit only after fallback/rejection decisions. Keep the original full
    // stack object and unused tail; ordered removal preserves equal-priority ties.
    if(flags!=0u){
        if(entering){gsConnectionVolumeWrite(base,1u+size,uint(index));gsConnectionVolumeWrite(base,33u,size+1u);}
        else {for(uint i=removeAt+1u;i<size;++i)gsConnectionVolumeWrite(base,i,gsConnectionVolume(base,1u+i));gsConnectionVolumeWrite(base,33u,size-1u);}
        gsConnectionVolumeWrite(base,0u,nextSelected);
    }
    ++control.z;gsOptical.storage.lanes[base+5u]=control;
    if(max(nextTransport.x,max(nextTransport.y,nextTransport.z))<=uintBitsToFloat(gsOptical.storage.lanes[8u].x))nextTransport.xyz=vec3(0);
    gsOptical.storage.lanes[base+3u]=floatBitsToUint(nextTransport);
    if(all(equal(nextTransport.xyz,vec3(0))))return 0;
    direction.w=distance;gsOptical.storage.lanes[base+1u]=floatBitsToUint(direction);return 1;
}
void gsOpticalCompleteConnection(uint base,uint item,uvec4 launch,float maximum,inout GpuScenePayload hit,inout uint epoch) {
#ifdef RTRENDERER_COLLECTED_CONNECTIONS
    bool firstQuery=true;
#endif
    for(;;){
#ifdef RTRENDERER_COLLECTED_CONNECTIONS
        // One query call site for the first and every subsequent event. Keeping
        // the material stage separate is insufficient if the driver clones the
        // robust gather/classification tree at two different loop levels.
        vec4 origin=uintBitsToFloat(gsOptical.storage.lanes[base]),direction=uintBitsToFloat(gsOptical.storage.lanes[base+1u]);
        float minimum=firstQuery&&gsOptical.storage.lanes[base+5u].z==0u
            ?0.0:uintBitsToFloat(floatBitsToUint(direction.w)+1u);
        if(minimum<=maximum){
            GS_DETAIL_BEGIN(firstQuery?173u:174u);
            hit=gsOpticalConnectionHit(origin.xyz,direction.xyz,launch,minimum,maximum);
            GS_DETAIL_END();
        }else hit.state.x=0u;
#endif
        int status;{GS_DETAIL_BEGIN(175u);status=gsConnectionTransportStep(base,hit);GS_DETAIL_END();}
        if(status==2)break;
        if(status<=0){gsOptical.storage.lanes[base+2u].w=status==0?3u:4u+uint(-status);break;}
        ++epoch;
#ifdef RTRENDERER_COLLECTED_CONNECTIONS
        firstQuery=false;
#else
        vec4 origin=uintBitsToFloat(gsOptical.storage.lanes[base]),direction=uintBitsToFloat(gsOptical.storage.lanes[base+1u]);
        float minimum=uintBitsToFloat(floatBitsToUint(direction.w)+1u);
        if(minimum>maximum){hit.state.x=0u;continue;}
        {GS_DETAIL_BEGIN(174u);hit=gsOpticalConnectionHit(origin.xyz,direction.xyz,launch,minimum,maximum);GS_DETAIL_END();}
#endif
    }
    uvec4 control=gsOpticalStateRead(item,2u);control.w=epoch;gsOpticalStateWrite(item,2u,control);
}

// PBRT uses Float for HG; retain CUDA for the near-singular parameter range.
bool gsVertexDirectPhase(uint item,uint base,out float phase) {
    phase=1.0;if(gsOptical.storage.lanes[base+5u].w!=0u)return true;
    uint selected=(gsOptical.storage.lanes[11u].w&2u)!=0u?GS_INVALID_SLOT:gsOpticalStateRead(item,7u).x;
    float g=uintBitsToFloat(selected==GS_INVALID_SLOT?gsOptical.storage.lanes[10u].w:gsOptical.storage.lanes[26u+selected*4u].w);
    if(abs(g)>.99)return false;
    precise float cosine=clamp(dot(uintBitsToFloat(gsOpticalStateRead(item,1u)).xyz,
        uintBitsToFloat(gsOptical.storage.lanes[base+1u]).xyz),-1.0,1.0);
    precise float a=abs(g),forwardCosine=g<0.0?-cosine:cosine,gap=1.0-a;
    precise float denominator=gap*gap+2.0*a*(1.0-forwardCosine);
    if(denominator<1.0e-4)return false;
    phase=((1.0-g)*(1.0+g))/(12.56637061435917295385*denominator*sqrt(denominator));
    return !isnan(phase)&&!isinf(phase);
}
void gsVertexRestoreConnectionStack(uint item,uint base) {
    if((gsOptical.storage.lanes[11u].w&2u)==0u) {
        // Keep the original full stack and tail, including priority tie order.
        for(uint lane=0u;lane<9u;++lane)gsOptical.storage.lanes[base+6u+lane]=gsOpticalStateRead(item,7u+lane);
    } else {
        gsConnectionVolumeWrite(base,0u,GS_INVALID_SLOT);
        gsConnectionVolumeWrite(base,33u,0u);gsConnectionVolumeWrite(base,34u,0u);
    }
}
