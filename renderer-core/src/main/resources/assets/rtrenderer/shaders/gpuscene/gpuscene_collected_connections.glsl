// Intel Embree 82c67a5318c535be94f017b9ec745498b5eb5606, Apache-2.0:
// nearest-N collection, ordered consumption and complete refill without truncation.
#include "gpuscene_collected_material_data.glsl"
#include "gpuscene_collected_batch_data.glsl"
layout(location=0) callableDataEXT GsCollectedMaterialData gsCollectedMaterial;
layout(location=1) callableDataEXT GsCollectedBatchData gsCollectedBatch;
#define gsCollectedEvents gsCollectedBatch.events
#define gsCollectedCount gsCollectedBatch.count
#define gsCollectedComplete gsCollectedBatch.complete
#define gsCollectedFallback gsCollectedBatch.fallback
uint gsCollectedCursor;
void gsCollectedReset() {
    gsCollectedCount=gsCollectedCursor=0u;
    gsCollectedComplete=gsCollectedFallback=false;
}
void gsCollectedCountEvent(uint stage) {
#ifdef RTRENDERER_OPTICAL_PROFILE
    if(payload.diagnosticAddress!=0ul&&gsDetailActive(stage)) {
        uint word=(payload.diagnosticRecord*184u+stage)*2u;
        GsOpticalDiagnostic target=GsOpticalDiagnostic(payload.diagnosticAddress);
        target.values[word+1u]+=1ul;
    }
#endif
}
bool gsCollectedFill(vec3 origin,vec3 direction,float minimum,float maximum) {
    gsCollectedCursor=0u;
    gsCollectedBatch.originAndMinimum=vec4(origin,minimum);
    gsCollectedBatch.directionAndMaximum=vec4(direction,maximum);
#ifdef RTRENDERER_OPTICAL_PROFILE
    gsCollectedBatch.diagnosticAddress=payload.diagnosticAddress;
    gsCollectedBatch.diagnosticRecord=payload.diagnosticRecord;
#endif
    executeCallableEXT(1u,1);
    return !gsCollectedFallback;
}

bool gsCollectedConnectionHit(vec3 origin,vec3 direction,uvec4 launch,float minimum,float maximum,
        out GpuScenePayload hit) {
    if(gsCollectedFallback)return false;
    for(;;) {
        if(gsCollectedCursor==gsCollectedCount) {
            if(gsCollectedComplete) {
                gsCollectedCountEvent(180u);
                hit.state=uvec4(0);hit.worldPositionAndDistance=vec4(0,0,0,-1);
                hit.mediumBoundary=uvec4(0);hit.previousWorldPosition=vec4(0);hit.motionRevision=uvec2(0);
                hit.worldNormalAndRoughness=vec4(0,1,0,0);hit.baseColorAndOpacity=vec4(0);
                hit.emissiveAndMetallic=hit.transmissionIor=hit.compositeState=vec4(0);
                hit.surfaceState=uvec4(0);hit.launchInfo=launch;return true;
            }
            if(gsCollectedCount!=0u)gsCollectedCountEvent(181u);
            if(!gsCollectedFill(origin,direction,minimum,maximum))return false;
            if(gsCollectedCount==0u)continue;
        }
        GsCollectedEvent event=gsCollectedEvents[gsCollectedCursor++];
        if(event.distance.y<minimum)continue;
        GS_DETAIL_BEGIN(177u);
        uint instance=event.identity.x,primitive=event.identity.y;
        uint boundary=gsInstanceWord(instance,GPU_SCENE_INSTANCE_MEDIUM_BOUNDARY_FLAGS_WORD);
        gsCollectedMaterial.hit.state=uvec4(0u,GS_PAYLOAD_OPTICAL_CONNECTION_QUERY,0u,0u);
        gsCollectedMaterial.hit.launchInfo=launch;
#ifdef RTRENDERER_OPTICAL_PROFILE
        gsCollectedMaterial.hit.diagnosticAddress=payload.diagnosticAddress;
        gsCollectedMaterial.hit.diagnosticRecord=payload.diagnosticRecord;
#endif
        gsCollectedMaterial.identity=event.identity;
        gsCollectedMaterial.originAndDistance=vec4(origin,event.distance.y);
        gsCollectedMaterial.direction=direction;
        executeCallableEXT(0u,0);
        hit=gsCollectedMaterial.hit;
        GS_DETAIL_END();
        uint blend=(hit.state.z>>1u)&0x7u;
        if(boundary==0u&&blend==GPU_SCENE_BLEND_MASKED) {
            uint material=gsTriangleMaterialSlot(gsMeshBase(gsInstanceMeshSlot(instance)),primitive)*GPU_SCENE_MATERIAL_RECORD_WORDS;
            if(hit.baseColorAndOpacity.a<uintBitsToFloat(gsMaterials.words[material+GPU_SCENE_MATERIAL_ALPHA_CUTOFF_WORD])) {
                // Even a batch containing only rejected coverage must make progress.
                // Coincident candidates were excluded before any event was consumed.
                minimum=uintBitsToFloat(floatBitsToUint(event.distance.y)+1u);continue;
            }
        }
        if(boundary==0u&&((hit.state.w&GPU_SCENE_FLAG_CASTS_SHADOW)==0u||gsSurfaceOverlay(hit))) {
            minimum=uintBitsToFloat(floatBitsToUint(hit.worldPositionAndDistance.w)+1u);
            if(minimum<=maximum)continue;
        }
        return true;
    }
}
