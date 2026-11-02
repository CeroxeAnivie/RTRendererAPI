// Reuse the common prefixes of a depth-first Whitted tree. The native payload
// carries the complete ray, medium stack, counters and guide state unchanged.
// This is shader-local storage, not another per-pixel GPU resource or queue.
#ifndef RTR_LEGACY_REPLAY_CACHE_HEADER
#define RTR_LEGACY_REPLAY_CACHE_HEADER
struct RtrLegacyReplayCache
{
    PathPayload prefix[(RTR_LEGACY_EXACT_INTERFACE_COUNT>1u)?(RTR_LEGACY_EXACT_INTERFACE_COUNT-1u):1u];
    uint valid;
    uint previous;
    uint firstOpticalVertex;
    uint resumeVertex;
#if USE_NVAPI_HIT_OBJECT_EXTENSION
    // Same-frame committed intersections, reconstructed with the exact NVAPI
    // MakeHitWithRecordIndex operation already used by upstream SERUtils.
    uint4 hitIds[(RTR_LEGACY_EXACT_INTERFACE_COUNT>1u)?(RTR_LEGACY_EXACT_INTERFACE_COUNT-1u):1u];
    float3 hitBaryT[(RTR_LEGACY_EXACT_INTERFACE_COUNT>1u)?(RTR_LEGACY_EXACT_INTERFACE_COUNT-1u):1u];
    uint hitValid;

    void SaveHit(const PathState path,
        inout RayQuery<RAY_FLAG_SKIP_PROCEDURAL_PRIMITIVES, RAYQUERY_FLAG_ALLOW_OPACITY_MICROMAPS> query)
    {
        uint vertex=path.getVertexIndex()+1u;
        if(vertex<2u || vertex>RTR_LEGACY_EXACT_INTERFACE_COUNT)return;
        uint index=vertex-2u;
        hitIds[index]=uint4(query.CommittedInstanceIndex(),query.CommittedGeometryIndex(),
            query.CommittedPrimitiveIndex(),query.CommittedInstanceContributionToHitGroupIndex()+query.CommittedGeometryIndex());
        hitBaryT[index]=float3(query.CommittedTriangleBarycentrics(),query.CommittedRayT());
        hitValid|=1u<<index;
    }

    bool RestoreHit(const PathState path,out SER_HIT_OBJECT hit)
    {
        hit=(SER_HIT_OBJECT)0;
        uint vertex=path.getVertexIndex()+1u;
        if(resumeVertex!=vertex || vertex<2u || vertex>RTR_LEGACY_EXACT_INTERFACE_COUNT)return false;
        resumeVertex=0;
        uint index=vertex-2u;
        if((hitValid&(1u<<index))==0)return false;
        uint4 ids=hitIds[index];
        float3 baryT=hitBaryT[index];
        RayDesc ray=path.getScatterRay().toRayDesc();
        ray.TMin=0;ray.TMax=baryT.z;
        BuiltInTriangleIntersectionAttributes attributes;attributes.barycentrics=baryT.xy;
        NvMakeHitWithRecordIndex(ids.w,SceneBVH,ids.x,ids.y,ids.z,0,ray,attributes,hit);
        #if RTR_LEGACY_WORK_TRACE
        if(RtrLegacyWorkPixel(path.GetPixelPos(),g_Const.ptConsts.frameIndex))
            RtrLegacyWorkPrint(211u,uint4(path.GetPixelPos(),g_Const.ptConsts.frameIndex,vertex),ids,baryT);
        #endif
        return true;
    }
#endif

    void Save(const PathState path)
    {
        uint vertex=path.getVertexIndex()+1u;
        if(vertex<2u || vertex>RTR_LEGACY_EXACT_INTERFACE_COUNT)return;
        // Restore has just loaded this exact snapshot; do not repack it.
        if(vertex==resumeVertex)return;
#if USE_NVAPI_HIT_OBJECT_EXTENSION
        hitValid&=~(1u<<(vertex-2u));
#endif
        #if RTR_LEGACY_WORK_TRACE
        if(RtrLegacyWorkPixel(path.GetPixelPos(),g_Const.ptConsts.frameIndex))
            RtrLegacyWorkPrint(220u,uint4(path.GetPixelPos(),g_Const.ptConsts.frameIndex,vertex),uint3(path.getCounter((PackedCounters)3),valid,previous));
        #endif
        prefix[vertex-2u]=PathPayload::pack(path);
        valid|=1u<<(vertex-2u);
    }

    bool Restore(uint replay,uint control,inout PathState path)
    {
        resumeVertex=0;
        if(replay==0)return false;
        uint changed=replay^previous;
        uint vertex=RTR_LEGACY_EXACT_INTERFACE_COUNT-firstbithigh(changed);
        if(vertex<2u || (valid&(1u<<(vertex-2u)))==0)
        {
            valid=0;firstOpticalVertex=0;
#if USE_NVAPI_HIT_OBJECT_EXTENSION
            hitValid=0;
#endif
            return false;
        }
        path=PathPayload::unpack(prefix[vertex-2u]);
        #if RTR_LEGACY_WORK_TRACE
        if(RtrLegacyWorkPixel(path.GetPixelPos(),g_Const.ptConsts.frameIndex))
            RtrLegacyWorkPrint(221u,uint4(path.GetPixelPos(),g_Const.ptConsts.frameIndex,vertex),uint3(replay,previous,valid));
        #endif
        path.setCounter((PackedCounters)3,control|(path.getCounter((PackedCounters)3)&4u));
        // Earlier prefixes have already been committed. Only add this suffix;
        // direct/emission at the fork is still deduplicated by the shared helper.
        path.SetL(0.xxxx);
        valid&=(1u<<(vertex-1u))-1u;
        resumeVertex=vertex;
#if USE_NVAPI_HIT_OBJECT_EXTENSION
        hitValid&=valid;
#endif
        return true;
    }
};
#endif
