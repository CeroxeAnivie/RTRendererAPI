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
            if(all(equal(gsCausticCell(point),cell)) && gsCaustics.words[base+7u]==receiver.mediumBoundary.x
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
#endif
