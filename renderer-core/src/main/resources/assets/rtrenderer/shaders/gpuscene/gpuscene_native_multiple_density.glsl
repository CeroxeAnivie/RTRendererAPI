// Same descriptor, clipped midpoint rule and texel-center trilinear interpolation
// as api_density.cu. The pinned upstream attribution is retained in that adapter.
float gsMultipleDensityTexel(uint atlas,uvec3 dimensions,ivec3 point) {
    uvec3 p=uvec3(clamp(point,ivec3(0),ivec3(dimensions)-1));
    uint word=atlas/4u+(p.z*dimensions.y+p.y)*dimensions.x+p.x;
    return uintBitsToFloat(dispatch.storage.lanes[word/4u][word%4u]);
}
float gsMultipleDensityValue(uint descriptor,vec3 p) {
    if(any(lessThan(p,vec3(0)))||any(greaterThan(p,vec3(1))))return 0.0;
    uvec4 grid=dispatch.storage.lanes[descriptor];
    vec3 point=p*vec3(grid.yzw)-.5,weight=fract(point);ivec3 base=ivec3(floor(point));
    float value=0.0;
    for(int z=0;z<2;++z)for(int y=0;y<2;++y)for(int x=0;x<2;++x) {
        vec3 w=mix(vec3(1)-weight,weight,bvec3(x!=0,y!=0,z!=0));
        value+=w.x*w.y*w.z*gsMultipleDensityTexel(grid.x,grid.yzw,base+ivec3(x,y,z));
    }
    return value;
}
float gsMultipleSegmentMass(vec3 origin,vec3 direction,float distance,float maximum) {
    float last=min(max(distance,0.0),maximum),first=0.0;
    if(dispatch.storage.lanes[0u].x!=0x52484d31u)return last;
    uint descriptor=dispatch.storage.lanes[10u].x/16u;
    vec4 r0=uintBitsToFloat(dispatch.storage.lanes[descriptor+1u]);
    vec4 r1=uintBitsToFloat(dispatch.storage.lanes[descriptor+2u]);
    vec4 r2=uintBitsToFloat(dispatch.storage.lanes[descriptor+3u]);
    vec3 p=vec3(dot(r0,vec4(origin,1)),dot(r1,vec4(origin,1)),dot(r2,vec4(origin,1)));
    vec3 v=vec3(dot(r0.xyz,direction),dot(r1.xyz,direction),dot(r2.xyz,direction));
    for(int axis=0;axis<3;++axis) {
        if(v[axis]==0.0){if(p[axis]<0.0||p[axis]>1.0)return 0.0;}
        else {vec2 t=(vec2(0,1)-p[axis])/v[axis];first=max(first,min(t.x,t.y));last=min(last,max(t.x,t.y));}
    }
    if(last<=first)return 0.0;
    uint steps=dispatch.storage.lanes[0u].w;float interval=(last-first)/float(steps),sum=0.0;
    for(uint j=0u;j<steps;++j)sum+=gsMultipleDensityValue(descriptor,p+v*(first+(float(j)+.5)*interval));
    return sum*interval;
}
vec3 gsMultipleLightDirection(uint slot,vec3 origin) {
    uint base=slot*GPU_SCENE_LIGHT_RECORD_WORDS;
    if(((gsLights.words[base+GPU_SCENE_LIGHT_FLAGS_WORD]>>1u)&7u)==GPU_SCENE_LIGHT_DIRECTIONAL)
        return -normalize(vec3(uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD]),
            uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD+1u]),
            uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD+2u])));
    vec3 position=vec3(gsFiniteDoubleAsFloat(gsLights.words[base+GPU_SCENE_LIGHT_POSITION_X_WORD],gsLights.words[base+GPU_SCENE_LIGHT_POSITION_X_WORD+1u]),
        gsFiniteDoubleAsFloat(gsLights.words[base+GPU_SCENE_LIGHT_POSITION_Y_WORD],gsLights.words[base+GPU_SCENE_LIGHT_POSITION_Y_WORD+1u]),
        gsFiniteDoubleAsFloat(gsLights.words[base+GPU_SCENE_LIGHT_POSITION_Z_WORD],gsLights.words[base+GPU_SCENE_LIGHT_POSITION_Z_WORD+1u]));
    vec3 delta=position-origin;return dot(delta,delta)>0.0?normalize(delta):vec3(0,1,0);
}
