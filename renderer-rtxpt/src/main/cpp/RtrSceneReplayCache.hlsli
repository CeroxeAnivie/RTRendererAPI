// Reuse the common prefixes of a depth-first Whitted tree. The native payload
// carries the complete ray, medium stack, counters and guide state unchanged.
// This is shader-local storage, not another per-pixel GPU resource or queue.
#ifndef RTR_SCENE_REPLAY_CACHE_HEADER
#define RTR_SCENE_REPLAY_CACHE_HEADER
struct RtrSceneReplayCache
{
    PathPayload prefix[(RTR_SCENE_EXACT_INTERFACE_COUNT>1u)?(RTR_SCENE_EXACT_INTERFACE_COUNT-1u):1u];
    uint valid;
    uint previous;
    uint firstOpticalVertex;
    uint resumeVertex;
    void Save(const PathState path)
    {
        uint vertex=path.getVertexIndex()+1u;
        if(vertex<2u || vertex>RTR_SCENE_EXACT_INTERFACE_COUNT)return;
        // Restore has just loaded this exact snapshot; do not repack it.
        if(vertex==resumeVertex){resumeVertex=0;return;}
        #if RTR_SCENE_WORK_TRACE
        if(RtrSceneWorkPixel(path.GetPixelPos(),g_Const.ptConsts.frameIndex))
            RtrSceneWorkPrint(220u,uint4(path.GetPixelPos(),g_Const.ptConsts.frameIndex,vertex),uint3(path.getCounter((PackedCounters)3),valid,previous));
        #endif
        prefix[vertex-2u]=PathPayload::pack(path);
        valid|=1u<<(vertex-2u);
    }

    bool Restore(uint replay,uint control,inout PathState path)
    {
        resumeVertex=0;
        if(replay==0)return false;
        uint changed=replay^previous;
        uint vertex=RTR_SCENE_EXACT_INTERFACE_COUNT-firstbithigh(changed);
        if(vertex<2u || (valid&(1u<<(vertex-2u)))==0)
        {
            valid=0;firstOpticalVertex=0;
            return false;
        }
        path=PathPayload::unpack(prefix[vertex-2u]);
        #if RTR_SCENE_WORK_TRACE
        if(RtrSceneWorkPixel(path.GetPixelPos(),g_Const.ptConsts.frameIndex))
            RtrSceneWorkPrint(221u,uint4(path.GetPixelPos(),g_Const.ptConsts.frameIndex,vertex),uint3(replay,previous,valid));
        #endif
        path.setCounter((PackedCounters)3,control|(path.getCounter((PackedCounters)3)&4u));
        // Earlier prefixes have already been committed. Only add this suffix;
        // direct/emission at the fork is still deduplicated by the shared helper.
        path.SetL(0.xxxx);
        valid&=(1u<<(vertex-1u))-1u;
        resumeVertex=vertex;
        return true;
    }
};
#endif
