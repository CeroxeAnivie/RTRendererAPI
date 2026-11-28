#ifndef RTR_NATIVE_TRANSMITTED_LIGHT_HLSLI
#define RTR_NATIVE_TRANSMITTED_LIGHT_HLSLI

#include "RtrNativeOpticalQueries.hlsli"

// Trace the transmitted branch of an emitted flux sample. Unlike camera
// radiance, flux already represents the sample's projected area, so a crossing
// multiplies it by the Fresnel power fraction, not by a radiance eta squared.
// A reflected branch is excluded here except at total internal reflection.
bool RtrTraceTransmittedLight(RayDesc ray,RtrDielectricStack stack,uint terminalRoles,out RtrOpticalHit receiver,out float3 position,
    out float3 incidentDirection,out float3 powerFraction)
{
    static const uint MaxInterfaces=24u;
    receiver=(RtrOpticalHit)0;position=0;incidentDirection=0;powerFraction=0;
    float3 power=1;
    bool transited=false;
    [loop]for(uint step=0;step<=MaxInterfaces;++step){
        RtrOpticalHit hit;
        if(!RtrFindOpticalHit(ray,terminalRoles|RtrMediumRole,hit)||hit.blocked)return false;
        float3 outward=0;
        RtrOpticalEvent event;
        if(hit.surface!=0){
            if(t_SurfaceMaterials[hit.surface-1].mediumPriorityPlusOne==0)return false;
            outward=RtrOpticalOutward(hit.instance,hit.geometry,hit.primitive);
            hit.entering=dot(outward,ray.Direction)<0;
            RtrOpticalHit contact;
            if(!RtrOpticalGather(ray,hit,terminalRoles,event,contact)||contact.blocked)return false;
            if(contact.distance>0)hit=contact;
        }
        power*=RtrDielectricSegmentTransmittance(stack,hit.distance);
        if(!any(power>0))return false;
        const float3 hitPosition=ray.Origin+hit.distance*ray.Direction;
        if(hit.surface==0){
            if(!transited)return false;
            receiver=hit;position=hitPosition;incidentDirection=ray.Direction;powerFraction=power;
            return true;
        }
        if(step==MaxInterfaces)return false;
        RtrDielectricStack crossed;
        if(!RtrOpticalCross(stack,event,crossed))return false;
        const RtrDielectricMedium before=RtrDielectricActiveMedium(stack),after=RtrDielectricActiveMedium(crossed);
        const float3 normal=hit.entering?outward:-outward;
        const RtrDielectricInterface scatter=RtrDielectricEvaluateInterface(ray.Direction,normal,before.ior,after.ior);
        // Ray t and every interface epsilon must use world distance. Snell's
        // direction is analytical unit length, but FP32 roundoff plus its
        // accumulated origin can drift measurably after long crossings.
        ray.Direction=RtrNormalize(ray.Direction);
        ray.Origin=hitPosition;
        ray.TMax-=hit.distance;
        if(ray.TMax<=ray.TMin)return false;
        if(scatter.totalInternalReflection){
            ray.Direction=RtrNormalize(scatter.reflectedDirection);
        }else{
            power*=1-scatter.reflectance;
            ray.Direction=RtrNormalize(scatter.transmittedDirection);
            stack=crossed;
            transited|=before.ior!=after.ior||any(before.absorption!=after.absorption);
        }
    }
    return false;
}

#endif
