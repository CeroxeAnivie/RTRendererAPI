#ifdef RTRENDERER_CAUSTICS
vec3 gsGatherCaustics(GpuScenePayload receiver,vec3 view) {
    vec3 position=receiver.worldPositionAndDistance.xyz;
    if(!gsCausticRegion(position) || (receiver.mediumBoundary.w&2u)!=0u) return vec3(0.0);
    float metallic=clamp(receiver.emissiveAndMetallic.a,0.0,1.0);
    if(metallic>=1.0 || receiver.transmissionIor.x>=1.0) return vec3(0.0);
    vec3 normal=normalize(receiver.worldNormalAndRoughness.xyz);
    vec3 geometric=gsDecodeGeometricNormal(receiver.compositeState.w);
    float radius=uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_CAUSTIC_GATHER_RADIUS_WORD]);
    float threshold=uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_CAUSTIC_NORMAL_THRESHOLD_WORD]);
    ivec3 center=gsCausticCell(position);
    uint heads=gsCaustics.words[1],records=gsCaustics.words[2],mask=gsCaustics.words[3];
    uint capacity=gsCaustics.words[4];
    vec3 radiance=vec3(0.0);
    [[dont_unroll]] for(int z=-1;z<=1;++z) [[dont_unroll]] for(int y=-1;y<=1;++y)
    [[dont_unroll]] for(int x=-1;x<=1;++x) {
        ivec3 cell=center+ivec3(x,y,z);
        uint index=gsCaustics.words[heads+(gsCausticHash(cell)&mask)];
        // No arbitrary bucket truncation: every published record is visited once per cell.
        [[dont_unroll]] for(uint visited=0u;index<capacity && visited<capacity;++visited) {
            uint base=records+index*16u;
            vec3 point=gsCausticRecordVec3(base),delta=point-position;
            uint next=gsCaustics.words[base+3u];
            if(all(equal(gsCausticCell(point),cell)) && gsCaustics.words[base+15u]==0u
                    && gsCaustics.words[base+7u]==receiver.mediumBoundary.x
                    && dot(gsCausticRecordVec3(base+12u),geometric)>=threshold
                    && abs(dot(delta,geometric))<=radius*.1+2.0*gsRayEpsilon(position)) {
                float r2=dot(delta,delta)/(radius*radius);
                vec3 incoming=gsCausticRecordVec3(base+8u);
                float cosine=dot(normal,incoming);
                if(r2<1.0 && cosine>0.0) {
                    GsRadianceSplit brdf=gsEvaluateBrdf(normal,view,incoming,receiver.baseColorAndOpacity.rgb,
                        clamp(receiver.worldNormalAndRoughness.w,.025,1.0),metallic);
                    // Photon flux already carries incidence geometry. Remove N.L from the shared BRDF.
                    float kernel=2.0*(1.0-r2)/(GS_PI*radius*radius);
                    radiance+=gsCausticRecordVec3(base+4u)*(brdf.diffuse/cosine)*kernel;
                }
            }
            index=next;
        }
    }
    return radiance;
}

float gsCausticHenyeyGreenstein(float cosine,float anisotropy) {
    float g=clamp(anisotropy,-.95,.95),denominator=max(1.0+g*g-2.0*g*cosine,1.0e-8);
    return (1.0-g*g)/(4.0*GS_PI*denominator*sqrt(denominator));
}

vec3 gsGatherVolumeCaustics(vec3 position,vec3 view,float anisotropy,uint mediumSlot) {
    if(!gsCausticRegion(position))return vec3(0.0);
    float radius=uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_CAUSTIC_GATHER_RADIUS_WORD]);
    ivec3 center=gsCausticCell(position);uint capacity=gsCaustics.words[4];
    vec3 radiance=vec3(0.0);
    [[dont_unroll]] for(int z=-1;z<=1;++z) [[dont_unroll]] for(int y=-1;y<=1;++y)
    [[dont_unroll]] for(int x=-1;x<=1;++x) {
        ivec3 cell=center+ivec3(x,y,z);
        uint index=gsCaustics.words[gsCaustics.words[1]+(gsCausticHash(cell)&gsCaustics.words[3])];
        [[dont_unroll]] for(uint visited=0u;index<capacity&&visited<capacity;++visited) {
            uint base=gsCaustics.words[2]+index*16u;
            vec3 delta=gsCausticRecordVec3(base)-position;
            uint next=gsCaustics.words[base+3u];
            if(gsCaustics.words[base+15u]==1u&&gsCaustics.words[base+7u]==mediumSlot
                    &&all(equal(gsCausticCell(gsCausticRecordVec3(base)),cell))) {
                float squared=dot(delta,delta),q=squared/(radius*radius);
                if(q<1.0) {
                    vec3 incoming=gsCausticRecordVec3(base+8u);
                    float phase=gsCausticHenyeyGreenstein(dot(-incoming,view),anisotropy);
                    float kernel=15.0*(1.0-q)/(8.0*GS_PI*radius*radius*radius);
                    radiance+=gsCausticRecordVec3(base+4u)*(phase*kernel);
                }
            }
            index=next;
        }
    }
    return radiance;
}
vec3 gsGatherVolumeCaustics(vec3 position,vec3 view,float anisotropy) {
    return gsGatherVolumeCaustics(position,view,anisotropy,GS_INVALID_SLOT);
}
#endif
