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

    void Save(const PathState path)
    {
        uint vertex=path.getVertexIndex()+1u;
        if(vertex<2u || vertex>RTR_LEGACY_EXACT_INTERFACE_COUNT)return;
        #if RTR_LEGACY_WORK_TRACE
        if(RtrLegacyWorkPixel(path.GetPixelPos(),g_Const.ptConsts.frameIndex))
            RtrLegacyWorkPrint(220u,uint4(path.GetPixelPos(),g_Const.ptConsts.frameIndex,vertex),uint3(path.getCounter((PackedCounters)3),valid,previous));
        #endif
        prefix[vertex-2u]=PathPayload::pack(path);
        valid|=1u<<(vertex-2u);
    }

    bool Restore(uint replay,inout PathState path)
    {
        if(replay==0)return false;
        uint changed=replay^previous;
        uint vertex=RTR_LEGACY_EXACT_INTERFACE_COUNT-firstbithigh(changed);
        if(vertex<2u || (valid&(1u<<(vertex-2u)))==0)
        {
            valid=0;firstOpticalVertex=0;
            return false;
        }
        uint control=path.getCounter((PackedCounters)3);
        #if RTR_LEGACY_WORK_TRACE
        if(RtrLegacyWorkPixel(path.GetPixelPos(),g_Const.ptConsts.frameIndex))
            RtrLegacyWorkPrint(221u,uint4(path.GetPixelPos(),g_Const.ptConsts.frameIndex,vertex),uint3(replay,previous,valid));
        #endif
        path=PathPayload::unpack(prefix[vertex-2u]);
        path.setCounter((PackedCounters)3,control|(path.getCounter((PackedCounters)3)&4u));
        // Earlier prefixes have already been committed. Only add this suffix;
        // direct/emission at the fork is still deduplicated by the shared helper.
        path.SetL(0.xxxx);
        valid&=(1u<<(vertex-1u))-1u;
        return true;
    }
};
#endif
