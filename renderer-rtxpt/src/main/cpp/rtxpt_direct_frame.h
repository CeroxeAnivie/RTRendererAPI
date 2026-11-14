#pragma once
#include <algorithm>
#include <cstring>
#include <stdexcept>
#include <vector>
#include <donut/engine/SceneGraph.h>
#include <nvrhi/nvrhi.h>
#include <nvrhi/common/misc.h>
#include "rtxpt_scene_delta.h"
#include "rtxpt_diagnostics.h"

namespace rtr::upstream {
// Direct requests have their own retained frame inputs, as GPUScene did in 5.0.
// Sample's CPU/GPU TLAS switch owns exact-count temporary buffers and frame-wide
// worker caches. Neither is needed to translate authored instances to DXR.
// Native scene IDs and transforms remain authoritative; NVRHI owns all builds,
// barriers, upload storage and fence-aware command-list resource references.
class DirectFrameInputs final {
    nvrhi::BufferHandle m_instances;
    std::vector<nvrhi::rt::InstanceDesc> m_rows;
    std::vector<uint32_t> m_dirty;
    std::vector<uint64_t> m_marks;
    uint64_t m_epoch=0,m_blasRevision=0;
    bool m_policy=false,m_valid=false;
public:
    void Reset(){m_instances=nullptr;m_rows.clear();m_dirty.clear();m_marks.clear();m_valid=false;}
    void Build(nvrhi::IDevice* device,nvrhi::ICommandList* commands,
        nvrhi::rt::IAccelStruct* tlas,const donut::engine::SceneGraph& graph,bool forceOpaque,
        const RtrSceneRowChanges& changes,uint64_t blasRevision){
        const auto& instances=graph.GetMeshInstances();
        if(instances.empty()||!tlas||!graph.GetSkinnedMeshInstances().empty())
            throw std::logic_error("Direct frame requires live authored non-skinned instances");
        const size_t previousCount=m_rows.size();m_rows.resize(instances.size());m_dirty.clear();
        const bool allocate=!m_instances||m_instances->getDesc().byteSize<instances.size()*sizeof(m_rows[0]);
        if(allocate){
            nvrhi::BufferDesc desc;
            desc.byteSize=nvrhi::align<size_t>(instances.size(),1024)*sizeof(m_rows[0]);
            desc.structStride=sizeof(m_rows[0]);desc.debugName="API retained direct TLAS inputs";
            desc.isAccelStructBuildInput=true;desc.initialState=nvrhi::ResourceStates::AccelStructBuildInput;
            desc.keepInitialState=true;m_instances=device->createBuffer(desc);
            if(!m_instances)throw std::runtime_error("Direct TLAS input allocation failed");
        }
        // Reindexing, policy and BLAS-address changes retain the complete native
        // reconciliation. Pose-only frames consume the already prepared rows;
        // they must not reconstruct every unchanged instance just to compare it.
        const bool full=allocate||!m_valid||previousCount!=instances.size()||m_policy!=forceOpaque
            ||m_blasRevision!=blasRevision||changes.snapshot||!changes.meshes.empty()||!changes.instances.empty();
        m_marks.resize(instances.size());if(++m_epoch==0){std::fill(m_marks.begin(),m_marks.end(),0);++m_epoch;}
        {RtrDiagnostics::Scope pack(RtrDiagnostics::PayloadPack,full?instances.size():changes.tlasInstances.size(),0,1,0,
            RtrDiagnostics::Identity("Direct TLAS input rows"));
        size_t examined=0;
        const auto reconcile=[&](size_t i,const auto& instance){
            if(i>=instances.size()||instances[i]!=instance)throw std::logic_error("Stale direct TLAS row journal");
            if(m_marks[i]==m_epoch)return;m_marks[i]=m_epoch;++examined;
            const auto& mesh=instance->GetMesh();
            const auto node=instance->GetNode();
            if(!node||!mesh->accelStruct)throw std::logic_error("Direct TLAS input is not prepared");
            nvrhi::rt::InstanceDesc row;
            row.blasDeviceAddress=mesh->accelStruct->getDeviceAddress();row.instanceMask=1;
            row.instanceID=instance->GetGeometryInstanceIndex();
            row.instanceContributionToHitGroupIndex=instance->GetGeometryInstanceIndex();
            row.flags=forceOpaque?nvrhi::rt::InstanceFlags::ForceOpaque:nvrhi::rt::InstanceFlags::None;
            donut::math::affineToColumnMajor(node->GetLocalToWorldTransformFloat(),row.transform);
            if(allocate||i>=previousCount||std::memcmp(&m_rows[i],&row,sizeof(row))!=0){
                m_rows[i]=row;m_dirty.push_back(uint32_t(i));
            }
        };
        if(full)for(size_t i=0;i<instances.size();++i)reconcile(i,instances[i]);
        else for(const auto& instance:changes.tlasInstances)reconcile(size_t(instance->GetInstanceIndex()),instance);
        std::sort(m_dirty.begin(),m_dirty.end());pack.event[8]=m_dirty.size();pack.event[9]=examined;}
        m_policy=forceOpaque;m_blasRevision=blasRevision;m_valid=true;
        // Like the 5.0 upload planner, combine adjacent dirty rows. A fragmented
        // change uses one full upload rather than hundreds of tiny writes.
        size_t runs=0;
        for(size_t i=0;i<m_dirty.size();++i)if(i==0||m_dirty[i]!=m_dirty[i-1]+1)++runs;
        if(runs>64||m_dirty.size()==m_rows.size())
            commands->writeBuffer(m_instances,m_rows.data(),m_rows.size()*sizeof(m_rows[0]));
        else for(size_t first=0;first<m_dirty.size();){
            size_t end=first+1;while(end<m_dirty.size()&&m_dirty[end]==m_dirty[end-1]+1)++end;
            commands->writeBuffer(m_instances,m_rows.data()+m_dirty[first],(end-first)*sizeof(m_rows[0]),size_t(m_dirty[first])*sizeof(m_rows[0]));
            first=end;
        }
        commands->setBufferState(m_instances,nvrhi::ResourceStates::AccelStructBuildInput);
        commands->setAccelStructState(tlas,nvrhi::ResourceStates::AccelStructWrite);commands->commitBarriers();
        commands->buildTopLevelAccelStructFromBuffer(tlas,m_instances,0,instances.size(),nvrhi::rt::AccelStructBuildFlags::AllowEmptyInstances);
    }
};
}
