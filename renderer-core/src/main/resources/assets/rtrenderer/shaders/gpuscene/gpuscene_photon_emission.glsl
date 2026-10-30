// Shared light emission for Vulkan-only and native medium light paths.
vec3 gsPhotonCone(vec3 axis,float cosine,vec2 samplePoint) {
    vec3 tangent=normalize(abs(axis.z)<.999?cross(vec3(0,0,1),axis):cross(vec3(0,1,0),axis));
    vec3 bitangent=cross(axis,tangent);
    float c=1.0-samplePoint.x*(1.0-cosine),s=sqrt(max(0.0,1.0-c*c)),angle=2.0*GS_PI*samplePoint.y;
    return normalize(axis*c+s*(tangent*cos(angle)+bitangent*sin(angle)));
}
void gsEmitPhoton(uint index,uint count,out vec3 origin,out vec3 direction,out vec3 flux,out float range,out uint row) {
    origin=vec3(0);direction=vec3(0,0,1);flux=vec3(0);range=0.0;row=0u;
    if(gsCaustics.words[0]==0u)return;
    uint seed=gsTransportHash(index^gsFrame.words[GPU_SCENE_FRAME_SEQUENCE_WORD]*0x9e3779b9u);
    uint emitters=gsCaustics.words[0];
    uint emitter=min(uint(gsTransportRandom(seed)*float(emitters)),emitters-1u);
    row=16u+emitter*36u;uint slot=gsCaustics.words[row];
    vec3 center=gsFrameVec3(GPU_SCENE_FRAME_CAUSTIC_CENTER_WORD);
    float radius=uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_CAUSTIC_REGION_RADIUS_WORD]);
    float scale=float(emitters)/float(count);
    vec2 samplePoint=vec2(gsTransportRandom(seed^0x68bc21ebu),gsTransportRandom(seed^0x02e5be93u));
    uint type=GPU_SCENE_LIGHT_DIRECTIONAL;
    vec3 lightDirection=normalize(gsFrameVec3(GPU_SCENE_FRAME_SUN_DIRECTION_WORD));
    flux=gsFrameVec3(GPU_SCENE_FRAME_SUN_COLOR_WORD)*uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_SUN_INTENSITY_WORD]);
    if(slot!=GS_INVALID_SLOT) {
        uint base=slot*GPU_SCENE_LIGHT_RECORD_WORDS;
        type=(gsLights.words[base+GPU_SCENE_LIGHT_FLAGS_WORD]>>1u)&7u;
        flux=vec3(uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_COLOR_WORD]),
            uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_COLOR_WORD+1u]),
            uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_COLOR_WORD+2u]))
            *uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_INTENSITY_WORD]);
        lightDirection=vec3(uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD]),
            uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD+1u]),
            uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD+2u]));
        if(type!=GPU_SCENE_LIGHT_DIRECTIONAL) {
            origin=vec3(gsFiniteDoubleAsFloat(gsLights.words[base+GPU_SCENE_LIGHT_POSITION_X_WORD],gsLights.words[base+GPU_SCENE_LIGHT_POSITION_X_WORD+1u]),
                gsFiniteDoubleAsFloat(gsLights.words[base+GPU_SCENE_LIGHT_POSITION_Y_WORD],gsLights.words[base+GPU_SCENE_LIGHT_POSITION_Y_WORD+1u]),
                gsFiniteDoubleAsFloat(gsLights.words[base+GPU_SCENE_LIGHT_POSITION_Z_WORD],gsLights.words[base+GPU_SCENE_LIGHT_POSITION_Z_WORD+1u]));
            vec3 toCenter=center-origin;float distance=length(toCenter);
            float cosine=distance>radius?sqrt(max(0.0,1.0-radius*radius/(distance*distance))):-1.0;
            direction=gsPhotonCone(distance>0.0?toCenter/distance:vec3(0,0,1),cosine,samplePoint);
            flux*=scale*2.0*GS_PI*(1.0-cosine);
            range=uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_RANGE_WORD]);
            if(type==GPU_SCENE_LIGHT_SPOT) {
                float inner=uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_INNER_CONE_WORD]);
                float outer=uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_OUTER_CONE_WORD]);
                float cone=dot(normalize(lightDirection),direction);
                flux*=inner==outer?step(outer,cone):smoothstep(outer,inner,cone);
            }
        }
    }
    if(type==GPU_SCENE_LIGHT_DIRECTIONAL) {
        direction=normalize(lightDirection);
        vec3 tangent=normalize(abs(direction.z)<.999?cross(vec3(0,0,1),direction):cross(vec3(0,1,0),direction));
        float r=radius*sqrt(samplePoint.x),angle=2.0*GS_PI*samplePoint.y;
        origin=center-direction*(radius+2.0*gsRayEpsilon(center))
            +r*(tangent*cos(angle)+cross(direction,tangent)*sin(angle));
        flux*=scale*GS_PI*radius*radius;
    }
}
