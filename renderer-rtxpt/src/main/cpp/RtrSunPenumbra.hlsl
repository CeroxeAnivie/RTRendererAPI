#define RTR_NATIVE_LIGHTING_LIBRARY
#define RTR_WORK_STATISTICS 0
#include "RtrNativeLighting.hlsl"

Texture2D<float4> t_SunEvidence : register(t14);
RWTexture2D<float4> u_SunPenumbra : register(u14);
groupshared uint selected;
groupshared float4 evidence[256];
groupshared float4 selectedEdge; // screen-space boundary, unit direction toward sunlight
groupshared float selectedRadius;

float3 ReceiverDirection(float2 pixel)
{
    float2 ndc=(pixel+.5)/float2(g.grid.xy)*float2(2,-2)+float2(-1,1)+g.farJitter.yz;
    float4 nearView=mul(g.inverseClip,float4(ndc,g.cameraNear.w,1));
    float4 farView=mul(g.inverseClip,float4(ndc,g.farJitter.x,1));
    return RtrNormalize(mul((float3x3)g.cameraToWorld,farView.xyz/farView.w-nearView.xyz/nearView.w));
}
float3 ReceiverRelative(uint2 pixel,float distance){return ReceiverDirection(float2(pixel))*distance;}

float NearestOpaqueBlocker(float3 position,float3 normal,float3 lightDirection)
{
    RayDesc ray;
    ray.Origin=position+normal*g.gi.z;ray.Direction=lightDirection;ray.TMin=g.gi.z;ray.TMax=1e20;
    RayQuery<RAY_FLAG_NONE> query;query.TraceRayInline(t_Scene,RtrQueryFlags(),RtrQueryMask(2),ray);
    while(query.Proceed())if(query.CandidateType()==CANDIDATE_NON_OPAQUE_TRIANGLE){
        uint instance=query.CandidateInstanceIndex(),primitive=query.CandidatePrimitiveIndex();
        RtrInstance data=t_InstanceAttributes[instance];uint roles=uint(data.rolesVisibility.x);
        if((roles&2)==0||((roles&16)==0&&!query.CandidateTriangleFrontFace()))continue;
        uint index=t_Instances[instance].firstGeometryIndex+query.CandidateGeometryIndex();
        GeometryData geometry=t_Geometry[index];RtrGeometryAttributes mapping=t_GeometryBaselines[index];
        MaterialConstants material=RtrMaterial(geometry,primitive,mapping);
        float3 transmission;bool opaque;
        if(RtrAcceptCandidateSurface(data,primitive,query.CandidateTriangleBarycentrics(),2,geometry,mapping,material,1,0,0,transmission,opaque)&&opaque)
            query.CommitNonOpaqueTriangleHit();
    }
    return query.CommittedStatus()==COMMITTED_TRIANGLE_HIT?max(0,query.CommittedRayT()-g.gi.z):0;
}

static const int2 offsets[4]={int2(1,0),int2(-1,0),int2(0,1),int2(0,-1)};

uint CellIndex(int2 cell){return uint(cell.y*16+cell.x);}
bool InTile(int2 cell){return all(cell>=0)&&all(cell<16);}
float4 Evidence(int2 cell){return evidence[CellIndex(cell)];}

bool ReceiverNeighbor(float4 a,float3 position,int2 other,int2 tileOrigin)
{
    if(!InTile(other))return false;
    float4 b=Evidence(other);
    if(b.x==0||dot(a.yzw,b.yzw)<=.995)return false;
    float3 delta=ReceiverRelative(uint2(tileOrigin+other),abs(b.x))-position;
    return abs(dot(delta,a.yzw))<.045*length(delta)+1e-5;
}

bool FitEdge(int2 cell,int2 tileOrigin,out float2 axis,out float offset,out float3 worldStep)
{
    axis=0;offset=0;worldStep=0;
    float4 center=Evidence(cell);
    if(center.x>=0)return false;
    uint candidates=0;
    [unroll]for(uint i=0;i<4;i++){
        int2 q=cell+offsets[i];
        if(InTile(q)&&Evidence(q).x>0&&dot(center.yzw,Evidence(q).yzw)>.995)candidates|=1u<<i;
    }
    if(candidates==0)return false;
    float3 position=ReceiverRelative(uint2(tileOrigin+cell),-center.x),deltas[4];
    uint coplanar=0;
    [unroll]for(uint j=0;j<4;j++)if(candidates&(1u<<j)){
        int2 q=cell+offsets[j];
        float3 delta=ReceiverRelative(uint2(tileOrigin+q),Evidence(q).x)-position;
        if(abs(dot(delta,center.yzw))<.045*length(delta)+1e-5){
            axis+=float2(offsets[j]);deltas[j]=delta;coplanar|=1u<<j;
        }
    }
    // Opposite lit neighbors indicate a thin occluder, not a single edge.
    if(coplanar==3||coplanar==12||countbits(coplanar)>2||dot(axis,axis)<.5)return false;
    axis=normalize(axis);
    float weights=0;
    [unroll]for(uint k=0;k<4;k++)if(coplanar&(1u<<k)){
        float projection=dot(axis,float2(offsets[k]));
        worldStep+=deltas[k]*projection;
        offset+=.5*projection*projection;
        weights+=projection;
    }
    offset/=weights;
    return true;
}

[numthreads(8,8,1)]
void main(uint2 pixel:SV_DispatchThreadID,uint2 local:SV_GroupThreadID,uint lane:SV_GroupIndex,uint2 group:SV_GroupID)
{
    bool inside=all(pixel<g.grid.xy);
    if(g.fogControl.y<=0||(g.sun.w<=0&&g.moon.w<=0)){if(inside)u_SunPenumbra[pixel]=0;return;}
    // Halo lets an edge just outside the output tile influence both sides.
    // Four coalesced evidence loads/lane, no intermediate texture or extra pass.
    int2 tileOrigin=int2(group)*8-4;
    [unroll]for(uint row=0;row<4;row++){
        uint index=lane+row*64;
        int2 q=tileOrigin+int2(index&15,index>>4);
        float4 value=0;
        if(all(q>=0)&&all(q<int2(g.grid.xy)))value=t_SunEvidence.Load(int3(q,0));
        evidence[index]=value;
    }
    if(lane==0)selected=0xffffffff;
    GroupMemoryBarrierWithGroupSync();
    [unroll]for(uint row=0;row<4;row++){
        uint index=lane+row*64;
        int2 cell=int2(index&15,index>>4);
        if(all(cell>0)&&all(cell<15)){
            float2 axis;float offset;float3 worldStep;
            if(FitEdge(cell,tileOrigin,axis,offset,worldStep)){
                int2 fromCenter=abs(cell*2-15);
                InterlockedMin(selected,(uint(fromCenter.x+fromCenter.y)<<8)|index);
            }
        }
    }
    GroupMemoryBarrierWithGroupSync();
    int2 cell=int2(local)+4;
    float4 center=Evidence(cell);
    float state=sign(center.x);
    if(selected==0xffffffff){if(inside)u_SunPenumbra[pixel]=float4(state,0,0,0);return;}
    if(lane==0){
        int2 owner=int2(selected&15,(selected>>4)&15);
        float2 axis;float offset;float3 worldStep;
        FitEdge(owner,tileOrigin,axis,offset,worldStep);
        float4 edge=Evidence(owner);
        float2 ownerPixel=float2(tileOrigin+owner);
        float3 position=ReceiverDirection(ownerPixel)*-edge.x;
        float3 neighbor=ReceiverDirection(ownerPixel+float2(-axis.y,axis.x));
        float planeDenominator=dot(neighbor,edge.yzw);
        selectedRadius=0;
        selectedEdge=float4(float2(owner)+axis*offset,axis);
        if(abs(planeDenominator)>1e-5){
            float planeDistance=dot(position,edge.yzw)/planeDenominator;
            float3 tangent=neighbor*planeDistance-position;
            // Measure displacement perpendicular to the projected contour in
            // the light's cross section, not raw world or camera distance.
            float3 lightDirection=RtrSunIsPrimary()?g.sun.xyz:g.moon.xyz;
            float3 contourNormal=cross(tangent,lightDirection);
            float contourLength=length(contourNormal);
            if(planeDistance>0&&contourLength>1e-6){
                float scale=abs(dot(worldStep,contourNormal))/contourLength;
                if(scale>1e-6){
                    float blocker=NearestOpaqueBlocker(g.cameraNear.xyz+position,edge.yzw,lightDirection);
                    // Bounded by the evidence halo, not the display resolution.
                    selectedRadius=min(3.0,blocker*g.fogControl.y/scale);
                }
            }
        }
    }
    GroupMemoryBarrierWithGroupSync();
    if(!inside)return;
    float4 output=float4(state,0,0,0);
    float2 axis=selectedEdge.zw;
    float distance=dot(float2(cell)-selectedEdge.xy,axis);
    if(state!=0&&selectedRadius>0&&distance*state>=0&&abs(distance)<=selectedRadius+.75){
        float2 boundary=float2(cell)-axis*distance;
        int2 dark=int2(floor(boundary+.5));
        int2 step=abs(axis.x)>=abs(axis.y)?int2(int(sign(axis.x)),0):int2(0,int(sign(axis.y)));
        if(dot(float2(dark)-boundary,axis)>=0)dark-=step;
        int2 lit=dark+step;
        // Validate that the local contour and its receivers agree with the
        // representative. Do not smear across a hole, depth break or other edge.
        if(InTile(dark)&&InTile(lit)&&Evidence(dark).x<0&&Evidence(lit).x>0){
            float3 position=ReceiverRelative(pixel,abs(center.x));
            int2 owner=int2(selected&15,(selected>>4)&15);
            if(ReceiverNeighbor(center,position,owner,tileOrigin)&&ReceiverNeighbor(center,position,dark,tileOrigin)
                &&ReceiverNeighbor(center,position,lit,tileOrigin)){
                output.y=selectedRadius;
                // BA carry the edge normal plus signed distance. Bias by the
                // current side so the vector is nonzero even at the boundary.
                output.zw=axis*(distance+4*state);
            }
        }
    }
    u_SunPenumbra[pixel]=output;
}
