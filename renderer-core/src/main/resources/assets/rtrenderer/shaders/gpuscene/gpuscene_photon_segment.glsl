#ifndef GS_PHOTON_SEGMENT_GLSL
#define GS_PHOTON_SEGMENT_GLSL
// RGB channel-mixture distance sampling, matching the native optical integrator.
// Incident fluence and scattered flux are distinct: the camera applies sigmaS.
struct GsPhotonSegment {
    float distance;
    bool collision;
    vec3 incidentWeight;
    vec3 transportWeight;
};
GsPhotonSegment gsPhotonSegment(vec3 extinction,vec3 scattering,float maximum,
        float channelSample,float distanceSample,bool sampleCollision) {
    GsPhotonSegment result;
    result.distance=maximum;result.collision=false;result.incidentWeight=vec3(0);
    vec3 tr=exp(-extinction*maximum);
    result.transportWeight=tr;
    if(!sampleCollision || maximum<=0.0 || max(scattering.x,max(scattering.y,scattering.z))<=0.0)
        return result;
    uint channel=min(uint(channelSample*3.0),2u);
    float rate=extinction[channel];
    float local=rate>0.0?-log(max(1.0-distanceSample,1.0e-30))/rate:maximum;
    if(local<maximum) {
        tr=exp(-extinction*local);
        float pdf=dot(extinction,tr)/3.0;
        result.distance=local;result.collision=true;
        result.incidentWeight=pdf>0.0?tr/pdf:vec3(0);
        result.transportWeight=result.incidentWeight*scattering;
    } else {
        float survival=(tr.x+tr.y+tr.z)/3.0;
        result.transportWeight=survival>0.0?tr/survival:vec3(0);
    }
    return result;
}
#endif
