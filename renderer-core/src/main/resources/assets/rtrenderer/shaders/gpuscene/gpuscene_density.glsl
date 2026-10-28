#ifndef GS_DENSITY_GLSL
#define GS_DENSITY_GLSL

// Extended transport is compiled separately: homogeneous programs never load a density atlas.
uint gsDensityTexture(uint mediumSlot) {
#ifdef RTRENDERER_VOLUME_TRANSPORT
    return mediumSlot==GS_INVALID_SLOT?gsFrame.words[GPU_SCENE_FRAME_MEDIUM_DENSITY_TEXTURE_WORD]
        :gsInstanceWord(mediumSlot,GPU_SCENE_INSTANCE_MEDIUM_DENSITY_TEXTURE_WORD);
#else
    return GS_INVALID_SLOT;
#endif
}
#ifdef RTRENDERER_VOLUME_TRANSPORT
uint gsDensityWord(uint mediumSlot,uint fieldOffset) {
    return mediumSlot==GS_INVALID_SLOT?gsFrame.words[GPU_SCENE_FRAME_MEDIUM_DENSITY_TEXTURE_WORD+fieldOffset]
        :gsInstanceWord(mediumSlot,GPU_SCENE_INSTANCE_MEDIUM_DENSITY_TEXTURE_WORD+fieldOffset);
}
vec3 gsDensityCoordinate(uint mediumSlot,vec3 position,float homogeneous) {
    vec4 p=vec4(position,homogeneous);
    return vec3(dot(p,vec4(uintBitsToFloat(gsDensityWord(mediumSlot,2u)),uintBitsToFloat(gsDensityWord(mediumSlot,3u)),
                            uintBitsToFloat(gsDensityWord(mediumSlot,4u)),uintBitsToFloat(gsDensityWord(mediumSlot,5u)))),
                dot(p,vec4(uintBitsToFloat(gsDensityWord(mediumSlot,6u)),uintBitsToFloat(gsDensityWord(mediumSlot,7u)),
                            uintBitsToFloat(gsDensityWord(mediumSlot,8u)),uintBitsToFloat(gsDensityWord(mediumSlot,9u)))),
                dot(p,vec4(uintBitsToFloat(gsDensityWord(mediumSlot,10u)),uintBitsToFloat(gsDensityWord(mediumSlot,11u)),
                            uintBitsToFloat(gsDensityWord(mediumSlot,12u)),uintBitsToFloat(gsDensityWord(mediumSlot,13u)))));
}
float gsDensityVoxel(uint base,ivec3 coordinate,ivec3 extent) {
    ivec3 voxel=clamp(coordinate,ivec3(0),extent-ivec3(1));
    return gsTextureTexel(base,voxel.x,voxel.y+voxel.z*extent.y).r;
}
#endif
float gsDensityAt(uint mediumSlot,vec3 position) {
#ifdef RTRENDERER_VOLUME_TRANSPORT
    uint texture=gsDensityTexture(mediumSlot);
    if(texture==GS_INVALID_SLOT) return 1.0;
    vec3 coordinate=gsDensityCoordinate(mediumSlot,position,1.0);
    if(any(isnan(coordinate)) || any(isinf(coordinate))
            || any(lessThan(coordinate,vec3(0))) || any(greaterThan(coordinate,vec3(1)))) return 0.0;
    uint base=texture*GPU_SCENE_TEXTURE_RECORD_WORDS;
    int slices=int(gsDensityWord(mediumSlot,1u));
    ivec3 extent=ivec3(gsTextures.words[base+GPU_SCENE_TEXTURE_WIDTH_WORD],
        gsTextures.words[base+GPU_SCENE_TEXTURE_HEIGHT_WORD]/uint(slices),slices);
    vec3 point=coordinate*vec3(extent)-vec3(.5),blend=fract(point);
    ivec3 lower=ivec3(floor(point));
    float first=mix(mix(gsDensityVoxel(base,lower,extent),gsDensityVoxel(base,lower+ivec3(1,0,0),extent),blend.x),
        mix(gsDensityVoxel(base,lower+ivec3(0,1,0),extent),gsDensityVoxel(base,lower+ivec3(1,1,0),extent),blend.x),blend.y);
    float second=mix(mix(gsDensityVoxel(base,lower+ivec3(0,0,1),extent),gsDensityVoxel(base,lower+ivec3(1,0,1),extent),blend.x),
        mix(gsDensityVoxel(base,lower+ivec3(0,1,1),extent),gsDensityVoxel(base,lower+ivec3(1,1,1),extent),blend.x),blend.y);
    return mix(first,second,blend.z);
#else
    return 1.0;
#endif
}
bool gsDensityInterval(uint mediumSlot,vec3 origin,vec3 direction,float distance,out vec2 interval) {
    interval=vec2(0.0,distance);
#ifdef RTRENDERER_VOLUME_TRANSPORT
    if(gsDensityTexture(mediumSlot)==GS_INVALID_SLOT) return distance>0.0;
    vec3 point=gsDensityCoordinate(mediumSlot,origin,1.0),delta=gsDensityCoordinate(mediumSlot,direction,0.0);
    if(any(isnan(point)) || any(isinf(point)) || any(isnan(delta)) || any(isinf(delta))) return false;
    for(uint axis=0u;axis<3u;++axis) {
        if(delta[axis]==0.0) {if(point[axis]<0.0 || point[axis]>1.0) return false;}
        else {
            float a=-point[axis]/delta[axis],b=(1.0-point[axis])/delta[axis];
            interval.x=max(interval.x,min(a,b));interval.y=min(interval.y,max(a,b));
        }
    }
#endif
    return interval.y>interval.x;
}
// x: integrated scalar density, y: sampled physical distance (-1 means survival), z: collision mass.
// Both integration and free-flight inversion use the same bounded piecewise-constant density.
vec3 gsDensityFlight(uint mediumSlot,vec3 origin,vec3 direction,float distance,float targetMass) {
    if(gsDensityTexture(mediumSlot)==GS_INVALID_SLOT)
        return vec3(distance,targetMass<distance?targetMass:-1.0,min(targetMass,distance));
    vec2 span;
    if(!gsDensityInterval(mediumSlot,origin,direction,distance,span)) return vec3(0,-1,0);
    uint steps=gsFrame.words[GPU_SCENE_FRAME_SCATTERING_STEPS_WORD];
    float width=(span.y-span.x)/float(steps),mass=0.0,collision=-1.0;
    [[dont_unroll]] for(uint stepIndex=0u;stepIndex<steps;++stepIndex) {
        float start=span.x+float(stepIndex)*width;
        float density=gsDensityAt(mediumSlot,origin+direction*(start+.5*width));
        float segmentMass=density*width;
        if(collision<0.0 && density>0.0 && targetMass<mass+segmentMass)
            collision=start+max(targetMass-mass,0.0)/density;
        mass+=segmentMass;
    }
    return vec3(mass,collision,min(targetMass,mass));
}
float gsDensityMass(uint mediumSlot,vec3 origin,vec3 direction,float distance) {
    return gsDensityFlight(mediumSlot,origin,direction,distance,1e30).x;
}
vec3 gsSegmentTransmittance(uint mediumSlot,vec3 origin,vec3 direction,float distance) {
    return exp(-gsCurrentMedium(mediumSlot).extinction*gsDensityMass(mediumSlot,origin,direction,distance));
}
#endif
