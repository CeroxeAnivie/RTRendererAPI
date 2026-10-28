#ifndef GS_RADIANCE_GLSL
#define GS_RADIANCE_GLSL
struct GsRadianceSplit {
    vec3 diffuse; vec3 specular;
    float diffuseHitDistance; float specularHitDistance;
    float diffuseHitWeight; float specularHitWeight;
};
GsRadianceSplit gsRadianceSplit(vec3 diffuse, vec3 specular) {
    return GsRadianceSplit(diffuse,specular,0.0,0.0,0.0,0.0);
}
float gsLuminance(vec3 value) { return dot(max(value,vec3(0.0)),vec3(.2126,.7152,.0722)); }
void gsAccumulateRadianceSplit(inout GsRadianceSplit total, GsRadianceSplit contribution) {
    float dw=gsLuminance(contribution.diffuse),sw=gsLuminance(contribution.specular);
    total.diffuse+=contribution.diffuse;total.specular+=contribution.specular;
    total.diffuseHitDistance+=contribution.diffuseHitDistance*dw;
    total.specularHitDistance+=contribution.specularHitDistance*sw;
    total.diffuseHitWeight+=dw;total.specularHitWeight+=sw;
}
void gsFinalizeRadianceHitDistances(inout GsRadianceSplit value) {
    value.diffuseHitDistance=value.diffuseHitWeight>1e-6?value.diffuseHitDistance/value.diffuseHitWeight:0.0;
    value.specularHitDistance=value.specularHitWeight>1e-6?value.specularHitDistance/value.specularHitWeight:0.0;
}
vec3 gsRadiance(GsRadianceSplit value) { return value.diffuse+value.specular; }
#endif
