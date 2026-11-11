#pragma once
#include "rtxpt_frame_batch.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <charconv>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <functional>
#include <limits>
#include <memory>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>
#include "rtxpt_affine_adapter.h"

#include <donut/core/vfs/VFS.h>
#include <donut/engine/Scene.h>
#include <donut/engine/TextureCache.h>
#include <donut/engine/DescriptorTableManager.h>
#include "SampleCommon/ExtendedScene.h"
#include "OpacityMicroMap/OmmBaker.h"
#include "Materials/MaterialsBaker.h"
#include <nvrhi/common/misc.h>
#include <donut/shaders/bindless.h>
#include "rtxpt_memory_resources.h"
#include "rtxpt_diagnostics.h"
#include "rtxpt_scene_delta.h"
#include "rtxpt_scene_table_upload.h"
#include "rtxpt_mesh_input_lease.h"

namespace rtr::upstream {
// Surface transport for the independent finite-signal pass. The embedded
// constants are filled exclusively by the original Donut Material method.
struct SurfaceMaterialRow {
    MaterialConstants material{};
    std::array<uint32_t,4> policies{};
    float maskedOpacity=1.f;
    uint32_t active=0;
};
// Immutable canonical channels survive until their final mesh generation.
// They never retain unrelated transaction, texture, material or node bytes.
struct AuthoredMeshInputs {
    using FloatView=RtrResourceReader::FloatView;
    struct Indices {const uint8_t* data=nullptr;uint32_t count=0;};
    std::shared_ptr<const MeshInputLease> lease;
    FloatView positions{nullptr,0},normals{nullptr,0},tangents{nullptr,0},uv{nullptr,0};
    std::vector<Indices> indices;
    Indices canonicalIndices;
    std::vector<uint32_t> groupedIndices;
    uint32_t indexCount=0;
    void OwnGeometry(const std::shared_ptr<MeshInputRetention>& accounting){
        const std::array<MeshInputLease::Range,5> ranges{{
            {positions.data(),positions.size()*sizeof(float)},
            {normals.data(),normals.size()*sizeof(float)},
            {tangents.data(),tangents.size()*sizeof(float)},
            {uv.data(),uv.size()*sizeof(float)},
            {canonicalIndices.data,size_t(canonicalIndices.count)*sizeof(uint32_t)}}};
        auto owner=std::make_shared<const MeshInputLease>(ranges,accounting);
        const auto* data=owner->bytes.data();size_t offset=0;
        auto bind=[&](FloatView& view){
            const auto count=view.size();view=FloatView(data+offset,count);offset+=count*sizeof(float);
        };
        bind(positions);bind(normals);bind(tangents);bind(uv);
        canonicalIndices.data=data+offset;lease=std::move(owner);
    }
    uint32_t Layout()const{return 1u|(!tangents.empty()?2u:0u)|(!uv.empty()?4u:0u);}
    std::array<size_t,4> AttributeSizes()const{
        const size_t vertices=positions.size()/3;
        return {vertices*sizeof(donut::math::float3),vertices*sizeof(uint32_t),
            tangents.empty()?0:vertices*sizeof(uint32_t),uv.empty()?0:vertices*sizeof(donut::math::float2)};
    }
};
// The documented mesh factory boundary keeps the official MeshInfoEx behavior;
// the only additional state is this adapter-owned pending input lease.
struct AuthoredMeshInfo final : MeshInfoEx {
    std::shared_ptr<const AuthoredMeshInputs> pendingInputs;
    std::vector<std::string> surfaceMaterials;
    std::vector<uint32_t> triangleMaterials,surfaceSlots;
};
class AuthoredSceneTypeFactory final : public ExtendedSceneTypeFactory {
public:
    std::shared_ptr<donut::engine::MeshInfo> CreateMesh()override{return std::make_shared<AuthoredMeshInfo>();}
};
inline const std::shared_ptr<const AuthoredMeshInputs>& PendingMeshInputs(const donut::engine::MeshInfo& mesh){
    static const std::shared_ptr<const AuthoredMeshInputs> empty;
    const auto* authored=dynamic_cast<const AuthoredMeshInfo*>(&mesh);
    return authored?authored->pendingInputs:empty;
}
// Extend the documented Scene subclass boundary; expose no SDK type to Java.
// The base uploader still creates buffers/descriptors and packs geometry rows.
class SceneBufferUpload : public donut::engine::Scene {
public:
    static void Run(donut::engine::Scene& scene,nvrhi::ICommandList* commands){
        // Form the pointer through the derived extension, then retain virtual
        // dispatch to ExtendedScene's original (private) override. No downcast.
        const auto upload=&SceneBufferUpload::CreateMeshBuffers;
        (scene.*upload)(commands);
    }
};
class ManagedScene final : public ExtendedScene {
public:
    using ExtendedScene::ExtendedScene;
    void PackSurfaceMaterial(const donut::engine::Material& material,MaterialConstants& row)const{
        material.FillConstantBuffer(row,m_UseResourceDescriptorHeapBindless);
    }
    // Managed scenes have authored materials, not imported native animation.
    // Every mutation enters through Resources::Apply; camera/pose-only frames
    // cannot dirty material rows. Complete native refreshes reset this journal.
    void MarkMaterialRowsDirty(){m_materialRowsPending=true;}
    void MarkInstanceInput(const std::shared_ptr<donut::engine::SceneGraphNode>& node){
        if(node)if(auto instance=std::dynamic_pointer_cast<donut::engine::MeshInstance>(node->GetLeaf()))
            m_dirtyInstanceInputs.push_back(instance);
    }
    // The original complete renderer can refresh Scene directly. Its host hook
    // calls this after that refresh, so a later A->B->A edit cannot compare
    // against a journal older than the native CPU/GPU table publication.
    void SyncNativeRowsAfterCompleteRefresh(){
        m_preparedFrameValid=false;
        if(FitsNativeTables())RememberNativeRows();
        else{m_tableJournalReady=false;ResetRowChanges();}
    }
    const RtrSceneRowChanges& RowChanges(){
        // A later packet can retire an earlier changed owner. Inspect only the
        // changed entries against the native rows already reconciled above.
        std::erase_if(m_rowChanges.meshes,[&](const auto& mesh){
            if(!mesh)return true;
            for(const auto& geometry:mesh->geometries){
                const size_t index=geometry->globalGeometryIndex;
                if(index>=m_geometryJournal.size()||!SameOwner(m_geometryJournal[index].owner,geometry))return true;
            }
            return false;
        });
        std::erase_if(m_rowChanges.instances,[&](const auto& instance){
            if(!instance)return true;
            const int index=instance->GetInstanceIndex();
            return index<0||size_t(index)>=m_instanceJournal.size()||!SameOwner(m_instanceJournal[index].owner,instance);
        });
        if(m_rowChangesNeedDedup){
            const auto uniqueOwners=[](auto& owners){
                std::sort(owners.begin(),owners.end(),[](const auto& a,const auto& b){
                    if(a.owner_before(b))return true;
                    if(b.owner_before(a))return false;
                    return std::less<>{}(a.get(),b.get());
                });
                owners.erase(std::unique(owners.begin(),owners.end(),[](const auto& a,const auto& b){
                    return a.get()==b.get()&&!a.owner_before(b)&&!b.owner_before(a);
                }),owners.end());
            };
            uniqueOwners(m_rowChanges.meshes);uniqueOwners(m_rowChanges.instances);
            m_rowChangesNeedDedup=false;
        }
        return m_rowChanges;
    }
    void AcknowledgeRowChanges(){
        m_rowChanges.Clear();m_rowChangesNeedDedup=false;
    }
    void ResetRowChanges(){AcknowledgeRowChanges();}
    // Container rebinding changes an authored identity without changing its
    // native owner/slot. Record that exact changed input, not all resident names.
    void RecordInstanceIdentityChange(const std::shared_ptr<donut::engine::MeshInstance>& instance){
        RecordChangedInstance(instance);m_rowChangesNeedDedup=true;
    }
    void RecordMeshIdentityChange(const std::shared_ptr<donut::engine::MeshInfo>& mesh){
        RecordChangedMesh(mesh);m_rowChangesNeedDedup=true;
    }
    void SetFrameWorkers(donut::engine::ThreadPool* workers){m_frameWorkers=workers;}
    void PrepareMeshInputs(const std::vector<std::shared_ptr<donut::engine::MeshInfo>>& meshes){BatchMeshInputs(meshes);}
    void RefreshAttributes(nvrhi::ICommandList* commands,
        const std::vector<std::shared_ptr<donut::engine::MeshInfo>>& meshes){
        UploadMeshes(commands,meshes);
        {RtrDiagnostics::Scope rows(RtrDiagnostics::SceneGeometryRows,meshes.size());
            for(const auto& mesh:meshes){UpdateGeometry(mesh);RememberGeometry(mesh);}}
        WriteChangedGeometryRows(commands,meshes);
        CompleteRowPublication();
    }
    void RefreshTopology(nvrhi::ICommandList* commands,uint32_t frameIndex,
        const std::vector<std::shared_ptr<donut::engine::MeshInfo>>& pendingMeshes,bool finalFramePreparation=false){
        const bool structurePending=GetSceneGraph()->HasPendingStructureChanges();
        const auto root=GetSceneGraph()->GetRootNode();
        const bool rootTransformPending=root&&(root->GetDirtyFlags()&donut::engine::SceneGraphNode::DirtyFlags::LocalTransform)!=0;
        {RtrDiagnostics::Scope graphScope(RtrDiagnostics::SceneGraphRefresh);RefreshSceneGraph(frameIndex);}
        // A light-only initial graph has no native table rows. Donut does not
        // allocate zero-sized tables, so its full structure upload would pass
        // null buffers to writeBuffer. Publish the empty journals directly;
        // the first real mesh still takes the ordinary native allocation path.
        const auto graph=GetSceneGraph();
        if(graph->GetGeometryCount()==0&&graph->GetMaterials().empty()&&graph->GetMeshInstances().empty()) {
            RememberNativeRows();m_preparedFrameIndex=frameIndex;m_preparedFrameValid=finalFramePreparation;return;
        }
        // Native RefreshBuffers remains the CPU-array allocation authority. Its
        // factories allocate matching native CPU/GPU capacities in 1024 rows.
        // Reuse only proven existing capacity; never mirror/resize Resources.
        if(!m_tableJournalReady||!FitsNativeTables()||!GetSceneGraph()->GetSkinnedMeshInstances().empty()) {
            RtrDiagnostics::Scope fallback(RtrDiagnostics::SceneTopologyFallback,pendingMeshes.size(),GetSceneGraph()->GetMeshes().size());
            fallback.event[11]|=RtrDiagnostics::CacheMiss;
            fallback.event[20]=!m_tableJournalReady?RtrDiagnostics::Inflow:
                (!GetSceneGraph()->GetSkinnedMeshInstances().empty()?RtrDiagnostics::BuildFlags:RtrDiagnostics::CapacityGrowth);
            BatchMeshInputs(pendingMeshes);
            {RtrDiagnostics::Scope full(RtrDiagnostics::SceneFullRefresh);RefreshBuffers(commands,frameIndex);}
            RememberNativeRows();m_preparedFrameIndex=frameIndex;m_preparedFrameValid=finalFramePreparation;return;
        }
        UploadMeshes(commands,pendingMeshes);
        RefreshMaterialRows(commands,structurePending);
        std::vector<std::shared_ptr<donut::engine::MeshInfo>> changedMeshes;
        uint64_t written=0,examined=0;
        {RtrDiagnostics::Scope rows(RtrDiagnostics::SceneGeometryRows);
            m_geometryJournal.resize(GetSceneGraph()->GetGeometryCount());
            // Native reindexing requires reconciliation of all live rows. A
            // stable graph publication touches only the authored dirty meshes.
            const auto reconcile=[&](const auto& mesh){
                mesh->buffers->instanceBuffer=m_InstanceBuffer;
                bool changed=false;
                for(const auto& geometry:mesh->geometries) {
                    ++examined;const auto& old=m_geometryJournal.at(geometry->globalGeometryIndex);
                    changed|=!SameOwner(old.owner,geometry)||!SameOwner(old.buffers,mesh->buffers)
                        ||old.material!=(geometry->material?geometry->material->materialID:-1)
                        ||old.authoredRanges!=GeometryRanges(*mesh,*geometry);
                }
                if(changed){UpdateGeometry(mesh);RememberGeometry(mesh);written+=mesh->geometries.size();changedMeshes.push_back(mesh);}
            };
            if(structurePending)for(const auto& mesh:GetSceneGraph()->GetMeshes())reconcile(mesh);
            else for(const auto& mesh:pendingMeshes)reconcile(mesh);
            rows.event[8]=written;rows.event[9]=examined;rows.event[11]|=RtrDiagnostics::CacheHit;
        }
        WriteChangedGeometryRows(commands,changedMeshes);
        RefreshInstanceRows(commands,true,structurePending||rootTransformPending);CompleteRowPublication();
        m_preparedFrameIndex=frameIndex;m_preparedFrameValid=finalFramePreparation;
    }
    bool RefreshPreparedTransforms(nvrhi::ICommandList* commands,uint32_t frameIndex){
        const auto graph=GetSceneGraph();
        if(!m_tableJournalReady||graph->HasPendingStructureChanges()||!graph->GetSkinnedMeshInstances().empty()||!FitsNativeTables()
            ||m_instanceJournal.size()!=graph->GetMeshInstances().size()
            ||m_materialJournal.size()!=graph->GetMaterials().size()
            ||m_geometryJournal.size()!=graph->GetGeometryCount())return false;
        // A topology publication is finalized after Animate, at this frame's
        // render boundary. Do not consume its native PrevTransform flags twice.
        // Real late edits remain visible through the public root dirty flags;
        // the next frame still performs the ordinary previous-pose convergence.
        using Dirty=donut::engine::SceneGraphNode::DirtyFlags;
        const auto root=graph->GetRootNode();
        const auto currentDirty=Dirty::LocalTransform|Dirty::Leaf|Dirty::SubgraphStructure
            |Dirty::SubgraphTransforms|Dirty::SubgraphContentUpdate;
        if(m_preparedFrameValid&&m_preparedFrameIndex==frameIndex&&root
            &&(root->GetDirtyFlags()&currentDirty)==0){RefreshMaterialRows(commands);return true;}
        // Capture the native dirty state before Refresh consumes it. Its public
        // contract includes previous-pose convergence on the following frame.
        const bool transformsPending=graph->HasPendingTransformChanges();
        const bool rootTransformPending=root&&(root->GetDirtyFlags()&Dirty::LocalTransform)!=0;
        {RtrDiagnostics::Scope graphScope(RtrDiagnostics::SceneGraphRefresh);RefreshSceneGraph(frameIndex);}
        // Native animation can dirty materials independently of resource input.
        RefreshMaterialRows(commands);RefreshInstanceRows(commands,transformsPending,rootTransformPending);
        CompleteRowPublication();
        m_preparedFrameIndex=frameIndex;m_preparedFrameValid=true;return true;
    }
private:
    // Scene owns dense indices, CPU capacities and GPU packing. Its public
    // GetGeometryData accessor exposes those packed rows without duplicating
    // the upstream representation or reaching into its private Resources.
    void WriteChangedGeometryRows(nvrhi::ICommandList* commands,
        const std::vector<std::shared_ptr<donut::engine::MeshInfo>>& meshes){
        std::vector<const donut::engine::MeshGeometry*> rows;
        for(const auto& mesh:meshes)
            for(const auto& geometry:mesh->geometries)rows.push_back(geometry.get());
        if(rows.empty())return;
        std::sort(rows.begin(),rows.end(),[](const auto* a,const auto* b){return a->globalGeometryIndex<b->globalGeometryIndex;});
        rows.erase(std::unique(rows.begin(),rows.end(),[](const auto* a,const auto* b){return a->globalGeometryIndex==b->globalGeometryIndex;}),rows.end());
        RtrDiagnostics::Scope upload(RtrDiagnostics::SceneTableUpload,rows.size());
        for(size_t first=0;first<rows.size();){
            size_t end=first+1;
            while(end<rows.size()&&rows[end]->globalGeometryIndex==rows[end-1]->globalGeometryIndex+1)++end;
            commands->writeBuffer(m_GeometryBuffer,GetGeometryData(*rows[first]),
                (end-first)*sizeof(GeometryData),size_t(rows[first]->globalGeometryIndex)*sizeof(GeometryData));
            ++upload.event[9];upload.event[21]+=(end-first)*sizeof(GeometryData);first=end;
        }
    }
    template<class T>static bool SameOwner(const std::weak_ptr<T>& previous,const std::shared_ptr<T>& current){
        return !previous.owner_before(current)&&!current.owner_before(previous);
    }
    struct GeometryJournal {
        std::weak_ptr<donut::engine::MeshGeometry> owner;
        std::weak_ptr<donut::engine::BufferGroup> buffers;
        int material=-1;
        std::array<uint32_t,6> authoredRanges{};
    };
    struct InstanceJournal {
        std::weak_ptr<donut::engine::MeshInstance> owner;
        std::array<float,24> pose{};
        std::array<uint32_t,4> geometry{};
    };
    std::vector<GeometryJournal> m_geometryJournal;
    std::vector<std::weak_ptr<donut::engine::Material>> m_materialJournal;
    std::vector<InstanceJournal> m_instanceJournal;
    std::vector<uint32_t> m_dirtyInstanceRows;
    nvrhi::RefCountPtr<scene_upload::SceneTableUploadCommands> m_instanceUploadCommands;
    std::vector<std::weak_ptr<donut::engine::MeshInstance>> m_dirtyInstanceInputs,m_convergingInstances;
    donut::engine::ThreadPool* m_frameWorkers=nullptr;
    bool m_tableJournalReady=false;
    bool m_materialRowsPending=true;
    uint32_t m_preparedFrameIndex=0;
    bool m_preparedFrameValid=false;
    RtrSceneRowChanges m_rowChanges;
    bool m_rowChangesNeedDedup=false;
    void CompleteRowPublication(){
        // A single reconciliation already visits each owner once. Only several
        // pending publications can repeat owners; never allocate hash nodes per row.
        m_rowChangesNeedDedup|=m_rowChanges.prepared;
        ++m_rowChanges.revision;m_rowChanges.prepared=true;
    }
    void RecordChangedMesh(const std::shared_ptr<donut::engine::MeshInfo>& mesh){
        m_rowChanges.meshes.push_back(mesh);
    }
    void RecordChangedInstance(const std::shared_ptr<donut::engine::MeshInstance>& instance){
        if(instance)m_rowChanges.instances.push_back(instance);
    }
    static std::array<uint32_t,6> GeometryRanges(const donut::engine::MeshInfo& mesh,
        const donut::engine::MeshGeometry& geometry){
        // Input metadata only; native UpdateGeometry still owns all GPU packing.
        return {mesh.indexOffset,mesh.vertexOffset,geometry.indexOffsetInMesh,
            geometry.vertexOffsetInMesh,geometry.numIndices,geometry.numVertices};
    }
    bool FitsNativeTables()const {
        const auto fits=[](const nvrhi::BufferHandle& buffer,size_t count,size_t stride){return !count||(buffer&&buffer->getDesc().byteSize/stride>=count);};
        return m_EnableBindlessResources&&fits(m_GeometryBuffer,GetSceneGraph()->GetGeometryCount(),sizeof(GeometryData))
            &&fits(m_MaterialBuffer,GetSceneGraph()->GetMaterials().size(),sizeof(MaterialConstants))
            &&fits(m_InstanceBuffer,GetSceneGraph()->GetMeshInstances().size(),sizeof(InstanceData));
    }
    void RememberGeometry(const std::shared_ptr<donut::engine::MeshInfo>& mesh){
        for(const auto& geometry:mesh->geometries) {
            const size_t index=geometry->globalGeometryIndex;
            if(m_geometryJournal.size()<=index)m_geometryJournal.resize(index+1);
            m_geometryJournal[index]={geometry,mesh->buffers,geometry->material?geometry->material->materialID:-1,GeometryRanges(*mesh,*geometry)};
        }
        RecordChangedMesh(mesh);
    }
    static InstanceJournal InstanceRow(const std::shared_ptr<donut::engine::MeshInstance>& instance) {
        InstanceJournal result;result.owner=instance;
        const auto* node=instance->GetNode();
        const auto& current=node->GetLocalToWorldTransformFloat();const auto& previous=node->GetPrevLocalToWorldTransformFloat();
        for(unsigned r=0;r<3;++r){
            for(unsigned c=0;c<3;++c){result.pose[r*4+c]=current.m_linear[c][r];result.pose[12+r*4+c]=previous.m_linear[c][r];}
            result.pose[r*4+3]=current.m_translation[r];result.pose[12+r*4+3]=previous.m_translation[r];
        }
        const auto& mesh=instance->GetMesh();
        result.geometry={uint32_t(instance->GetGeometryInstanceIndex()),uint32_t(mesh->geometries.size()),
            mesh->geometries.empty()?~0u:uint32_t(mesh->geometries.front()->globalGeometryIndex),uint32_t(mesh->type)};
        return result;
    }
    static bool NeedsPoseConvergence(const InstanceJournal& row){
        return !std::equal(row.pose.begin(),row.pose.begin()+12,row.pose.begin()+12);
    }
    void RememberNativeRows(){
        // A complete native refresh supersedes every earlier pending change.
        // Reuse these unavoidable loops; lighting must not rescan the graph.
        AcknowledgeRowChanges();m_rowChanges.snapshot=true;
        m_geometryJournal.resize(GetSceneGraph()->GetGeometryCount());
        for(const auto& mesh:GetSceneGraph()->GetMeshes())RememberGeometry(mesh);
        m_materialJournal.resize(GetSceneGraph()->GetMaterials().size());
        for(const auto& material:GetSceneGraph()->GetMaterials())m_materialJournal.at(material->materialID)=material;
        m_dirtyInstanceInputs.clear();m_convergingInstances.clear();
        m_instanceJournal.resize(GetSceneGraph()->GetMeshInstances().size());
        for(const auto& instance:GetSceneGraph()->GetMeshInstances()){
            m_instanceJournal.at(instance->GetInstanceIndex())=InstanceRow(instance);RecordChangedInstance(instance);
            if(NeedsPoseConvergence(m_instanceJournal.at(instance->GetInstanceIndex())))m_convergingInstances.push_back(instance);
        }
        m_tableJournalReady=true;m_materialRowsPending=false;CompleteRowPublication();
    }
    void RefreshMaterialRows(nvrhi::ICommandList* commands,bool registryChanged=false){
        uint64_t written=0;
        std::vector<std::pair<uint32_t,MaterialConstants>> dirty;
        RtrDiagnostics::Scope rows(RtrDiagnostics::SceneMaterialRows);
        if(!registryChanged&&!m_materialRowsPending){rows.event[11]|=RtrDiagnostics::CacheHit;return;}
        m_materialJournal.resize(GetSceneGraph()->GetMaterials().size());
        for(const auto& material:GetSceneGraph()->GetMaterials()) {
            auto& old=m_materialJournal.at(material->materialID);
            if(material->dirty||!SameOwner(old,material)){
                UpdateMaterial(material);old=material;++written;
                MaterialConstants data{};material->FillConstantBuffer(data,m_UseResourceDescriptorHeapBindless);
                dirty.emplace_back(uint32_t(material->materialID),data);
            }
            if(!material->materialConstants){material->materialConstants=CreateMaterialConstantBuffer(material->name);material->dirty=true;}
            if(material->dirty) {
                // The supported native method is the sole packing authority.
                MaterialConstants data{};material->FillConstantBuffer(data,m_UseResourceDescriptorHeapBindless);
                commands->writeBuffer(material->materialConstants,&data,sizeof(data));material->dirty=false;
            }
        }
        rows.event[8]=written;rows.event[9]=GetSceneGraph()->GetMaterials().size();rows.event[11]|=RtrDiagnostics::CacheHit;
        if(!dirty.empty()){
            RtrDiagnostics::Scope upload(RtrDiagnostics::SceneTableUpload,dirty.size());
            std::sort(dirty.begin(),dirty.end(),[](const auto& a,const auto& b){return a.first<b.first;});
            std::vector<MaterialConstants> run;
            for(size_t first=0;first<dirty.size();){
                size_t end=first+1;
                while(end<dirty.size()&&dirty[end].first==dirty[end-1].first+1)++end;
                run.clear();run.reserve(end-first);
                for(size_t i=first;i<end;++i)run.push_back(dirty[i].second);
                commands->writeBuffer(m_MaterialBuffer,run.data(),run.size()*sizeof(MaterialConstants),size_t(dirty[first].first)*sizeof(MaterialConstants));
                ++upload.event[9];upload.event[21]+=run.size()*sizeof(MaterialConstants);first=end;
            }
        }
        m_materialRowsPending=false;
    }
    void RefreshInstanceRows(nvrhi::ICommandList* commands,bool transformsPending=true,bool structureChanged=false){
        uint64_t written=0;m_dirtyInstanceRows.clear();
        RtrDiagnostics::Scope rows(RtrDiagnostics::SceneInstanceRows);
        rows.event[11]|=RtrDiagnostics::CacheHit;
        // Keep an explicit zero-work event: no rows examined is distinguishable
        // from a missing diagnostic sample while every frame remains fresh.
        if(!transformsPending){rows.event[8]=0;rows.event[9]=0;return;}
        const auto& live=GetSceneGraph()->GetMeshInstances();
        bool all=structureChanged||m_instanceJournal.size()!=live.size()
            ||(m_dirtyInstanceInputs.empty()&&m_convergingInstances.empty());
        m_instanceJournal.resize(live.size());
        std::vector<std::shared_ptr<donut::engine::MeshInstance>> selected;
        std::unordered_set<const donut::engine::MeshInstance*> seen;
        if(!all){
            const auto collect=[&](const auto& inputs){for(const auto& input:inputs)if(auto instance=input.lock()){
                const int index=instance->GetInstanceIndex();
                if(index<0||size_t(index)>=live.size()||live[index]!=instance){all=true;return;}
                if(seen.insert(instance.get()).second)selected.push_back(std::move(instance));
            }};
            collect(m_dirtyInstanceInputs);if(!all)collect(m_convergingInstances);
            if(selected.empty())all=true;
        }
        m_dirtyInstanceInputs.clear();m_convergingInstances.clear();
        const auto& candidates=all?live:selected;
        for(const auto& instance:candidates) {
            auto row=InstanceRow(instance);auto& old=m_instanceJournal.at(instance->GetInstanceIndex());
            if(NeedsPoseConvergence(row))m_convergingInstances.push_back(instance);
            const bool ownerChanged=!SameOwner(old.owner,instance);
            if(ownerChanged)RecordChangedInstance(instance);
            if(ownerChanged||old.pose!=row.pose||old.geometry!=row.geometry) {
                UpdateInstance(instance);old=std::move(row);++written;
                m_dirtyInstanceRows.push_back(uint32_t(instance->GetInstanceIndex()));
            }
        }
        rows.event[8]=written;rows.event[9]=candidates.size();
        if(!m_dirtyInstanceRows.empty()){
            if(!m_instanceUploadCommands)m_instanceUploadCommands.Attach(new scene_upload::SceneTableUploadCommands());
            RtrDiagnostics::Scope upload(RtrDiagnostics::SceneTableUpload);
            const auto transferred=m_instanceUploadCommands->Record(commands,m_InstanceBuffer,sizeof(InstanceData),
                m_dirtyInstanceRows,m_tableJournalReady,[this](nvrhi::ICommandList* target){WriteInstanceBuffer(target);});
            upload.event[8]=transferred.rows;upload.event[9]=transferred.writes;upload.event[21]=transferred.bytes;
            upload.event[11]|=transferred.full?RtrDiagnostics::CacheMiss:RtrDiagnostics::CacheHit;
        }
    }
    void UploadMeshes(nvrhi::ICommandList* commands,
        const std::vector<std::shared_ptr<donut::engine::MeshInfo>>& meshes){
        if(meshes.empty())return;
        // Match GltfImporter's supported shared BufferGroup input, rather than
        // asking the original uploader for a committed buffer pair per mesh.
        // Batches are immutable generations, not a second GPU allocator/pool.
        RtrDiagnostics::Scope preparation(RtrDiagnostics::SceneMeshPreparation,meshes.size());
        BatchMeshInputs(meshes);
        // The original virtual uploader accepts a graph, not a mesh range.
        // Give it new temporary leaves referencing only this transaction's
        // changed MeshInfo objects. Never refresh this view: native dense IDs,
        // real leaf ownership and current/previous poses belong to the live graph.
        auto view=m_SceneTypeFactory->CreateGraph();
        auto root=std::make_shared<donut::engine::SceneGraphNode>();view->SetRootNode(root);
        for(const auto& mesh:meshes)view->AttachLeafNode(root,m_SceneTypeFactory->CreateMeshInstance(mesh));
        struct RestoreGraph {
            std::shared_ptr<donut::engine::SceneGraph>& target;
            std::shared_ptr<donut::engine::SceneGraph> original;
            ~RestoreGraph(){target=std::move(original);}
        } restore{m_SceneGraph,m_SceneGraph};
        m_SceneGraph=std::move(view);
        {RtrDiagnostics::Scope upload(RtrDiagnostics::MeshBufferUpload);
            if(upload.enabled) {
                std::unordered_map<donut::engine::BufferGroup*,std::array<bool,2>> pending;
                for(const auto& mesh:meshes)if(mesh->buffers)
                    pending.try_emplace(mesh->buffers.get(),std::array<bool,2>{!mesh->buffers->indexBuffer,!mesh->buffers->vertexBuffer});
                SceneBufferUpload::Run(*this,commands);
                for(const auto& [buffers,missing]:pending) {
                    if(missing[0]&&buffers->indexBuffer){++upload.event[8];upload.event[21]+=buffers->indexBuffer->getDesc().byteSize;}
                    if(missing[1]&&buffers->vertexBuffer){++upload.event[8];upload.event[21]+=buffers->vertexBuffer->getDesc().byteSize;}
                }
            }else SceneBufferUpload::Run(*this,commands);
        }
        m_SceneGraph=restore.original;
    }
    void BatchMeshInputs(const std::vector<std::shared_ptr<donut::engine::MeshInfo>>& meshes){
        using Mesh=donut::engine::MeshInfo;
        using Buffers=donut::engine::BufferGroup;
        constexpr size_t byteBudget=1024*1024,maxMeshes=32;
        struct Segment {Mesh* mesh;std::shared_ptr<const AuthoredMeshInputs> inputs;};
        // Group only this call's pending input. An interleaved optional channel
        // must not flush an otherwise compatible immutable upload generation.
        // Layout has exactly the normal/tangent/UV bits; graph order is untouched.
        std::array<std::vector<Segment>,8> layouts;
        std::unordered_set<Mesh*> selected;
        for(const auto& mesh:meshes){
            const auto input=PendingMeshInputs(*mesh);if(!input||!selected.insert(mesh.get()).second)continue;
            const auto& b=mesh->buffers;
            if(!b||b->indexBuffer||b->vertexBuffer||mesh->type!=donut::engine::MeshType::Triangles
                ||mesh->isSkinPrototype||mesh->skinPrototype.use_count()
                ||!b->positionData.empty()||!b->indexData.empty())
                throw std::logic_error("Pending authored mesh must have empty native upload channels");
            const auto layout=input->Layout();
            if(layout>=layouts.size())throw std::logic_error("Unknown authored mesh input layout");
            layouts[layout].push_back({mesh.get(),input});
        }
        std::vector<Segment> batch;batch.reserve(maxMeshes);
        size_t bytes=0,vertices=0,indices=0;uint32_t layout=0;
        auto flush=[&](){
            if(batch.empty())return;
            // Old ChunkAccumulator/TransferPlan strategy: collect ownership and
            // layout first, resize final channels once, then materialize directly.
            // Reuse one empty native BufferGroup, matching GltfImporter inputs.
            const auto shared=batch.front().mesh->buffers;
            shared->indexData.resize(indices);shared->positionData.resize(vertices);
            if(layout&1)shared->normalData.resize(vertices);
            if(layout&2)shared->tangentData.resize(vertices);
            if(layout&4)shared->texcoord1Data.resize(vertices);
            std::vector<std::pair<size_t,size_t>> offsets;offsets.reserve(batch.size());
            size_t vertexOffset=0,indexOffset=0;
            for(const auto& segment:batch){
                offsets.emplace_back(vertexOffset,indexOffset);
                vertexOffset+=segment.mesh->totalVertices;indexOffset+=segment.mesh->totalIndices;
            }
            // Final channels have one allocation. Workers fill disjoint ranges;
            // shared ownership and native graph metadata publish only after join.
            RunFrameBatch(m_frameWorkers,batch.size(),[&](size_t at){
                const auto& segment=batch[at];const auto& mesh=*segment.mesh;const auto& input=*segment.inputs;
                const auto [vertexOffset,indexOffset]=offsets[at];
                for(size_t v=0;v<mesh.totalVertices;++v){
                    shared->positionData[vertexOffset+v]=donut::math::float3(input.positions[v*3],input.positions[v*3+1],input.positions[v*3+2]);
                    if(layout&1)shared->normalData[vertexOffset+v]=donut::math::vectorToSnorm8(donut::math::float3(input.normals[v*3],input.normals[v*3+1],input.normals[v*3+2]));
                    if(layout&2)shared->tangentData[vertexOffset+v]=donut::math::vectorToSnorm8(donut::math::float4(input.tangents[v*4],input.tangents[v*4+1],input.tangents[v*4+2],input.tangents[v*4+3]));
                    if(layout&4)shared->texcoord1Data[vertexOffset+v]=donut::math::float2(input.uv[v*2],input.uv[v*2+1]);
                }
                size_t localIndex=0;
                for(const auto& part:input.indices){
                    std::memcpy(shared->indexData.data()+indexOffset+localIndex,part.data,size_t(part.count)*sizeof(uint32_t));localIndex+=part.count;
                }
            });
            for(size_t at=0;at<batch.size();++at){
                auto& mesh=*batch[at].mesh;mesh.vertexOffset=uint32_t(offsets[at].first);mesh.indexOffset=uint32_t(offsets[at].second);
                mesh.buffers=shared;static_cast<AuthoredMeshInfo&>(mesh).pendingInputs.reset();
            }
            batch.clear();bytes=vertices=indices=0;
        };
        for(uint32_t mask=0;mask<layouts.size();++mask){
            layout=mask;
            for(const auto& segment:layouts[mask]){
                const auto* mesh=segment.mesh;const auto sizes=segment.inputs->AttributeSizes();
                const size_t size=size_t(mesh->totalIndices)*sizeof(uint32_t)+sizes[0]+sizes[1]+sizes[2]+sizes[3];
                if(!batch.empty()&&(batch.size()>=maxMeshes||bytes+size>byteBudget))flush();
                batch.push_back(segment);bytes+=size;vertices+=mesh->totalVertices;indices+=mesh->totalIndices;
                // A single oversized authored mesh stays intact, as in the original
                // importer; it is never coalesced with a neighbour or quality-reduced.
                if(size>byteBudget)flush();
            }
            flush();
        }
    }
    // Original RefreshBuffers owns CPU capacities, dense indices and uploads.
    // Its factories run only after the corresponding public graph count has
    // grown past capacity (Scene.cpp:617-635). Match its 1024-row rounding and
    // ExtendedScene's final descriptors without allocating a throwaway base
    // buffer just to obtain that descriptor (ExtendedScene.cpp:353-371).
    nvrhi::BufferHandle CreateTableBuffer(size_t count,uint32_t stride,
        const char* name,bool instances=false){
        nvrhi::BufferDesc desc;
        desc.byteSize=nvrhi::align<size_t>(count,1024)*stride;
        desc.debugName=name;
        desc.structStride=instances&&m_Device->getGraphicsAPI()==nvrhi::GraphicsAPI::D3D11?0:stride;
        desc.canHaveRawViews=true;
        desc.canHaveUAVs=true;
        desc.isVertexBuffer=instances;
        desc.initialState=nvrhi::ResourceStates::NonPixelShaderResource;
        desc.keepInitialState=true;
        return m_Device->createBuffer(desc);
    }
    nvrhi::BufferHandle CreateMaterialBuffer() override {
        return CreateTableBuffer(m_SceneGraph->GetMaterials().size(),sizeof(MaterialConstants),"BindlessMaterials");
    }
    nvrhi::BufferHandle CreateGeometryBuffer() override {
        return CreateTableBuffer(m_SceneGraph->GetGeometryCount(),sizeof(GeometryData),"BindlessGeometry");
    }
    nvrhi::BufferHandle CreateInstanceBuffer() override {
        return CreateTableBuffer(m_SceneGraph->GetMeshInstances().size(),sizeof(InstanceData),"Instances",true);
    }
};

// Converts the private, synchronous resource packet into original scene objects.
// GPU allocation, upload pages, descriptors and AS execution use original
// Scene/NVRHI/DescriptorTable services. This class only maps authored identities.
// Immutable texture generations use NVRHI directly: file TextureCache marks its
// images permanently ShaderResource and cannot represent copy-on-write updates.
class Resources final {
public:
    struct Changes {
        bool topology = false;
        bool materials = false;
        bool transforms = false;
        bool textures = false;
        bool attributes = false;
        bool pendingTopologyRefresh = false;
        std::vector<std::shared_ptr<donut::engine::MeshInfo>> pendingUploadMeshes;
        std::vector<std::shared_ptr<donut::engine::MeshInfo>> retiredMeshes;
        std::vector<std::shared_ptr<donut::engine::BufferGroup>> retiredBuffers;
        std::vector<nvrhi::rt::AccelStructHandle> retiredStructures;
        std::vector<std::shared_ptr<donut::engine::MeshInfo>> deformedMeshes;
        std::vector<std::shared_ptr<donut::engine::MeshInfo>> reshapedMeshes;
        std::vector<std::shared_ptr<donut::engine::MeshInfo>> buildMeshes;
        std::vector<std::shared_ptr<donut::engine::LoadedTexture>> retiredTextures;
        bool existingMaterialsChanged = false;
        bool materialDomainsChanged = false;
        bool coverageChanged = false;
        bool requiresCoverageRebuild = false;
        std::unordered_set<std::string> materialPolicyChanges;
        std::unordered_set<std::string> materialOpacityChanges;
        std::vector<std::string> retiredInstances;
        std::vector<std::string> retiredGeometries;
        std::vector<std::pair<std::string,std::string>> renamedGeometries;
        std::vector<std::string> surfaceGeometries;
    };

    void SetTextureResources(nvrhi::IDevice* device,
        std::shared_ptr<donut::engine::DescriptorTableManager> descriptors) {
        m_textureDevice=device;m_textureDescriptors=std::move(descriptors);
    }
    bool HasInstance(const std::string& name) const { return m_nodes.contains(name); }
    bool IsLiveMesh(const std::shared_ptr<donut::engine::MeshInfo>& mesh)const{
        const auto current=m_meshes.find(mesh->name);const auto users=m_meshNodes.find(mesh->name);
        return current!=m_meshes.end()&&current->second==mesh&&users!=m_meshNodes.end()&&!users->second.empty();
    }
    // Full-upload count/bytes, rectangle count/bytes, GPU-copy bytes, identical
    // generation count, sampler-only count. Actual commands, not predicted work.
    const std::array<uint64_t,7>& TextureWork() const {return m_textureWork;}
    // Old-mesh tangent revalidation visits, and redundant dependency iterations
    // skipped because the normal-mapping requirement was already satisfied.
    const std::array<uint64_t,2>& ReferenceWork() const {return m_referenceWork;}
    // Lifetime work counts: existing-node pose packets, their nodes, empty packets.
    const std::array<uint64_t,3>& PoseWork() const {return m_poseWork;}
    // An immutable mesh segment shared by inherited inputs is counted once.
    // These are canonical retained bytes, not JNI traffic or GPU residency.
    std::array<uint64_t,3> MeshInputRetentionWork()const{return {
        m_meshInputRetention->bytes.load(std::memory_order_relaxed),
        m_meshInputRetention->peakBytes.load(std::memory_order_relaxed),
        m_meshInputRetention->packets.load(std::memory_order_relaxed)};}
    std::array<double,24> InstanceTransformState(const std::string& name) const {
        auto node=m_nodes.find(name);
        if(node==m_nodes.end())throw std::invalid_argument("Unknown adapter instance: "+name);
        const auto& current=node->second.node->GetLocalToWorldTransformFloat();
        const auto& previous=node->second.node->GetPrevLocalToWorldTransformFloat();
        std::array<double,24> result{};
        for(unsigned row=0;row<3;++row){
            for(unsigned col=0;col<3;++col){
                result[row*4+col]=current.m_linear[col][row];
                result[12+row*4+col]=previous.m_linear[col][row];
            }
            result[row*4+3]=current.m_translation[row];
            result[12+row*4+3]=previous.m_translation[row];
        }
        return result;
    }
    std::array<double,2> MeshResourceState(const std::string& name)const{
        const auto& mesh=*m_meshes.at(m_nodes.contains(name)?m_nodes.at(name).mesh:name);
        return {double(mesh.accelStruct?mesh.accelStruct->getDeviceAddress():0),
            double(reinterpret_cast<uintptr_t>(mesh.buffers->vertexBuffer.Get()))};
    }
    size_t GeometryCorners(const std::string& name) const {
        const auto separator=name.rfind(':');
        if(separator!=std::string::npos){
            const auto mesh=m_meshes.find(name.substr(0,separator));size_t index=0;
            const auto first=name.data()+separator+1,last=name.data()+name.size();
            const auto parsed=std::from_chars(first,last,index);
            if(mesh!=m_meshes.end()&&parsed.ec==std::errc()&&parsed.ptr==last&&index<mesh->second->geometries.size())
                return mesh->second->geometries[index]->numIndices;
        }
        throw std::invalid_argument("Unknown adapter geometry: "+name);
    }

    // Enable only when the consumer's own pass explicitly implements every
    // returned policy. The original beauty material sampler is never replaced.
    void SetExternalTextureSampling(bool enabled) {
        m_externalTextureSampling=enabled;
        if(enabled&&!m_materials.contains(SurfaceCarrierName)){
            MaterialEntry entry;entry.material=m_factory.CreateMaterial();
            entry.material->name=SurfaceCarrierName;entry.textures.fill(-1);
            m_materials.emplace(SurfaceCarrierName,std::move(entry));
            m_pendingIndependentMaterials.insert(SurfaceCarrierName);
        }
    }
    const std::vector<uint32_t>* SurfaceSlots(const std::string& geometry)const{
        const auto separator=geometry.rfind(':');if(separator==std::string::npos)return nullptr;
        auto found=m_meshes.find(geometry.substr(0,separator));if(found==m_meshes.end())return nullptr;
        const auto& mesh=static_cast<const AuthoredMeshInfo&>(*found->second);
        return mesh.surfaceMaterials.empty()?nullptr:&mesh.surfaceSlots;
    }
    void SurfaceUpdates(const ManagedScene& scene,std::vector<SurfaceMaterialRow>& rows,std::vector<uint32_t>& dirty){
        rows.resize(m_surfaceNames.size());dirty.clear();
        for(uint32_t slot:m_surfaceDirty){
            auto& row=rows.at(slot);SurfaceMaterialRow next{};const auto& name=m_surfaceNames.at(slot);
            if(!name.empty()){
                const auto& entry=m_materials.at(name);
                scene.PackSurfaceMaterial(*entry.material,next.material);
                next.policies=TextureSamplingPolicies(name);next.maskedOpacity=entry.material->opacity;
                const auto users=m_materialMeshes.find(name);next.active=users!=m_materialMeshes.end()&&!users->second.empty();
            }
            if(std::memcmp(&row,&next,sizeof(row))!=0){row=next;dirty.push_back(slot);}
        }
        m_surfaceDirty.clear();
    }

    // base, normal, metal/roughness, emissive. Bit 0/1: clamp U/V; bit 2: nearest.
    // This is transport metadata for a consumer-owned pass, not Material.flags.
    std::array<uint32_t, 4> TextureSamplingPolicies(const std::string& materialName) const {
        auto material = m_materials.find(materialName);
        if (material == m_materials.end())
            throw std::invalid_argument("Unknown sampling material: " + materialName);
        std::array<uint32_t, 4> result{};
        for (size_t slot = 0; slot < result.size(); ++slot) {
            const auto id = material->second.textures[slot];
            if (id < 0) continue;
            const auto& texture = m_textures.at(id);
            result[slot] = texture.u | (texture.v << 1) | ((texture.filter == 0 ? 1u : 0u) << 2);
        }
        return result;
    }

    // Call only after discarding the corresponding original scene, on its owner
    // thread. Never retain an ID mapping into a newly loaded scene graph.
    void Reset() {
        m_nodes.clear();
        m_meshes.clear();
        m_meshSources.clear();
        m_materials.clear();
        m_pendingIndependentMaterials.clear();
        m_surfaceNames.clear();m_surfaceFree.clear();m_surfaceDirty.clear();
        SetExternalTextureSampling(m_externalTextureSampling);
        m_textures.clear();
        m_meshNodes.clear();
        m_materialMeshes.clear();
        m_textureMaterials.clear();
        m_pendingMaterials.clear();
        m_pendingTextureMaterials.clear();
        m_pendingTransforms.clear();
        m_graph.reset();
    }

    // Apply changed poses once, immediately before the final original frame
    // refresh. Direct topology packets defer their graph/table preparation to
    // that boundary; complete renderer lifecycle fallbacks still stage poses.
    void ApplyPendingTransforms(donut::engine::Scene& scene) {
        for(const auto& [name,input]:m_pendingTransforms)
            if(auto node=m_nodes.find(name);node!=m_nodes.end()){
                static_cast<ManagedScene&>(scene).MarkInstanceInput(node->second.node);
                SetPose(node->second,input);
            }
        m_pendingTransforms.clear();
    }

    // Donut Material has no IOR/volume fields. After the original SceneLoaded /
    // MaterialsBaker lifecycle has created PTMaterial, map those transport fields
    // through the original, public PTMaterial data and its dirty contract.
    void ConfigureMaterials(const std::unordered_set<std::shared_ptr<donut::engine::Material>>& registered,
        bool sceneReloaded = false,bool independentLighting = false) {
        auto configure = [&](const std::string& name, MaterialEntry& entry) {
            RtrDiagnostics::Scope materialRow(RtrDiagnostics::SceneMaterialRows,1,0,1,0,
                RtrDiagnostics::Identity(name),0,RtrDiagnostics::Material);
            auto material = std::dynamic_pointer_cast<MaterialEx>(entry.material);
            if (!material || !material->PTMaterial)
                throw std::runtime_error("Original material bake has not completed: " + name);
            auto& target = *material->PTMaterial;
            // These are authored API parameters, mapped to the public native
            // data model. The baker keeps its GPUDataIndex/permutation and
            // performs FillData/upload on GPUDataDirty in its ordinary Update.
            // ImportFromDonut is private and is deliberately not copied here.
            target.BaseOrDiffuseColor=material->baseOrDiffuseColor;
            target.EmissiveColor=material->emissiveColor;
            target.Opacity=material->opacity;
            target.Roughness=material->roughness;
            target.Metalness=material->metalness;
            target.TransmissionFactor=material->transmissionFactor;
            target.AlphaCutoff=material->alphaCutoff;
            target.EnableAlphaTesting=material->domain==donut::engine::MaterialDomain::AlphaTested
                ||material->domain==donut::engine::MaterialDomain::TransmissiveAlphaTested;
            target.EnableTransmission=material->domain==donut::engine::MaterialDomain::Transmissive
                ||material->domain==donut::engine::MaterialDomain::TransmissiveAlphaBlended
                ||material->domain==donut::engine::MaterialDomain::TransmissiveAlphaTested;
            target.IoR = entry.ior;
            // Public upstream material controls (Docs/Design.md, Path Decomposition).
            // Preserve its opt-out default; consumers explicitly select optical layers.
            target.PSDExclude = entry.psdExclude;
            target.PSDDominantDeltaLobe = entry.psdDominant;
            target.PSDBlockMotionVectorsAtSurfaceType = entry.psdBlockMotionVectors;
            if (entry.boundary) {
                target.VolumeAttenuationColor = donut::math::float3(
                    std::exp(-entry.absorption.x), std::exp(-entry.absorption.y),
                    std::exp(-entry.absorption.z));
                target.VolumeAttenuationDistance = 1.f;
                target.NestedPriority = entry.priority;
            } else {
                target.VolumeAttenuationColor=donut::math::float3(1.f);
                target.VolumeAttenuationDistance=std::numeric_limits<float>::max();
                target.NestedPriority=PTMaterial::kMaterialMaxNestedPriority;
            }
            target.GPUDataDirty = true;
        };
        if(independentLighting) {
            // CPU PTMaterial controls are needed for original AS flags. Prepare
            // each authored definition once, including definitions that acquire
            // their first graph user in a later packet. No GPU row is registered.
            // Pose/empty packets must never walk the complete graph registry.
            RtrDiagnostics::Scope candidates(RtrDiagnostics::SceneMaterialRows);
            candidates.event[9]=m_pendingIndependentMaterials.size();
            candidates.event[11]|=RtrDiagnostics::CacheHit;
            for(const auto& name:m_pendingIndependentMaterials) {
                auto found=m_materials.find(name);
                if(found==m_materials.end())continue;
                auto material=std::dynamic_pointer_cast<MaterialEx>(found->second.material);
                if(!material)throw std::logic_error("Managed graph requires the original material type");
                if(!material->PTMaterial) {
                    // Public CPU data for the original AS flags. No GPU material
                    // row, shader permutation or invented GPUDataIndex is created.
                    material->PTMaterial=std::make_shared<PTMaterial>();
                    material->PTMaterial->Name=name;
                    material->PTMaterial->DonutCounterpart=material.get();
                }
                configure(name,found->second);
            }
            m_pendingIndependentMaterials.clear();
        } else if (sceneReloaded) {
            // A complete original bake replaces every PTMaterial; reapply only
            // the transport fields unavailable on Donut Material in that case.
            for (auto& [name, entry] : m_materials) {
                if(registered.contains(entry.material))configure(name,entry);
                else if(auto material=std::dynamic_pointer_cast<MaterialEx>(entry.material)){
                    material->PTMaterial.reset();
                    // A later direct transition must restore these CPU-only
                    // definitions before their first native graph attachment.
                    m_pendingIndependentMaterials.insert(name);
                }
            }
        } else {
            for (const auto& name : m_pendingMaterials)
                if (auto i = m_materials.find(name); i != m_materials.end()&&registered.contains(i->second.material))
                    configure(name, i->second);
        }
        m_pendingMaterials.clear();
    }

    // Existing texture identities do not change a PT material's shader features.
    // Rebind through the public PTTexture adapter and GPUDataDirty contract;
    // the original baker remains the owner of material GPU rows and uploads.
    void RebindMaterialTextures(const std::filesystem::path& mediaPath) {
        for(const auto& name:m_pendingTextureMaterials) {
            auto resident=m_materials.find(name);
            if(resident==m_materials.end())continue;
            auto material=std::dynamic_pointer_cast<MaterialEx>(resident->second.material);
            if(!material)throw std::logic_error("Managed material requires the original material type");
            // A declared definition may currently have no graph users and no
            // baker row. Its authored textures are imported when it is attached.
            if(!material->PTMaterial)continue;
            auto& target=*material->PTMaterial;
            target.BaseTexture.InitFromLoadedTexture(material->baseOrDiffuseTexture,true,false,mediaPath);
            target.NormalTexture.InitFromLoadedTexture(material->normalTexture,false,true,mediaPath);
            target.OcclusionRoughnessMetallicTexture.InitFromLoadedTexture(
                material->metalRoughOrSpecularTexture,material->useSpecularGlossModel,false,mediaPath);
            target.EmissiveTexture.InitFromLoadedTexture(material->emissiveTexture,true,false,mediaPath);
            target.GPUDataDirty=true;
        }
        m_pendingTextureMaterials.clear();
    }

    Changes Apply(const void* bytes, size_t size, donut::engine::Scene& scene,
        const std::function<nvrhi::ICommandList*()>& acquireCommands, uint32_t frameIndex,
        const std::function<void(bool)>& beforeStructural = {},bool deferTopologyRefresh=false,
        const std::shared_ptr<const std::vector<uint8_t>>& owner={},
        const std::function<void(const MeshInfoEx&)>& beforeDormantMesh={}) {
        if (!bytes || !size || !acquireCommands)
            throw std::invalid_argument("A resource packet and command-list provider are required");
        if(!owner||owner->data()!=bytes||owner->size()!=size)
            throw std::invalid_argument("Resource packet requires its exact native owned transport");
        auto graph = scene.GetSceneGraph();
        if (!graph || !graph->GetRootNode())
            throw std::logic_error("Create the original empty scene graph before applying resources");
        if (auto bound = m_graph.lock(); bound && bound != graph)
            throw std::logic_error("Reset resource identities before replacing the original scene");

        // Decode and validate the complete packet before changing any scene object.
        RtrDiagnostics::Scope applyScope(RtrDiagnostics::Resources,0,0,1,0,0,0,0,size);
        Packet packet;{RtrDiagnostics::Scope decodeScope(RtrDiagnostics::Import);packet=Decode(bytes,size);}
        ValidateReferences(packet);
        // A resident pose publication has no GPU resource work. Keep its complete
        // packet/reference validation, then stage the exact authored transforms
        // for the original frame refresh, without opening an upload command list.
        const bool posesOnly=packet.textures.empty()&&packet.materials.empty()&&packet.meshes.empty()
            &&packet.removeNodes.empty()&&packet.coverage.empty()&&packet.removeTextures.empty()&&packet.removeMaterials.empty()&&packet.removeMeshes.empty()
            &&std::all_of(packet.nodes.begin(),packet.nodes.end(),[&](const auto& input){
                const auto old=m_nodes.find(input.name);
                return old!=m_nodes.end()&&old->second.mesh==input.mesh;
            });
        if(posesOnly){
            bool transformsChanged=false;
            for(auto& input:packet.nodes){
                transformsChanged|=StagePose(m_nodes.at(input.name),input);
            }
            m_graph=graph;
            if(packet.nodes.empty())++m_poseWork[2];
            else {++m_poseWork[0];m_poseWork[1]+=packet.nodes.size();}
            Changes changes;changes.transforms=transformsChanged;return changes;
        }
        // Exact input equality avoids turning an unchanged material into a
        // scene reload. No epsilon or hash collision can suppress a real edit.
        for(auto i=packet.materials.begin();i!=packet.materials.end();)
            if(auto old=m_materials.find(i->first);old!=m_materials.end()&&SameMaterial(old->second,i->second))i=packet.materials.erase(i);
            else ++i;
        Changes changes;
        std::unordered_set<std::string> referencedNewMaterials,domainChangedMaterials;
        const bool hasNewMaterial=std::any_of(packet.materials.begin(),packet.materials.end(),[&](const auto& entry){
            return !m_materials.contains(entry.first);
        });
        if(hasNewMaterial) {
            // A new definition can gain graph users only through this packet's
            // meshes. Resolve their final users after removals and node rebinding;
            // keep the existing resource ownership/cleanup contract unchanged.
            std::unordered_set<std::string> publishedMeshes,replacedNodes(packet.removeNodes.begin(),packet.removeNodes.end());
            for(const auto& node:packet.nodes){publishedMeshes.insert(node.mesh);replacedNodes.insert(node.name);}
            for(const auto& [name,mesh]:packet.meshes) {
                bool referenced=publishedMeshes.contains(name);
                if(!referenced)if(auto users=m_meshNodes.find(name);users!=m_meshNodes.end())
                    referenced=std::any_of(users->second.begin(),users->second.end(),[&](const auto& node){return !replacedNodes.contains(node);});
                if(referenced)for(const auto& geometry:mesh->geometries)
                    if(!m_materials.contains(geometry->material->name))referencedNewMaterials.insert(geometry->material->name);
            }
        }
        bool requiresMaterialLifecycle=false;
        for(const auto& [name,entry]:packet.materials) {
            const auto previous=m_materials.find(name);
            const bool isNew=previous==m_materials.end();
            const bool domainChanged=!isNew&&previous->second.material->domain!=entry.material->domain;
            requiresMaterialLifecycle|=!m_externalTextureSampling&&(isNew?referencedNewMaterials.contains(name):domainChanged);
            if(domainChanged)domainChangedMaterials.insert(name);
            if(isNew||previous->second.textures!=entry.textures)changes.materialPolicyChanges.insert(name);
            if(isNew||previous->second.material->opacity!=entry.material->opacity)changes.materialOpacityChanges.insert(name);
        }
        // Atomic replacement can reuse an actually vacated native container.
        // The live graph never contains a dead placeholder, and its dense IDs
        // remain authoritative. Unmatched topology keeps the original lifecycle.
        const auto containerReuses=PlanContainerReuses(packet,domainChangedMaterials);
        std::unordered_map<std::string,std::string> reusedMeshes;
        std::unordered_set<std::string> reusedNodes;
        for(const auto& reuse:containerReuses){
            reusedMeshes.emplace(reuse.newMesh,reuse.oldMesh);
            if(reuse.retainNode)reusedNodes.insert(reuse.newNode);
        }
        std::unordered_set<std::string> attributeMeshes,deformedMeshes,reshapedMeshes,unchangedMeshes;
        std::unordered_set<std::string> finalPublishedMeshes,replacedNodeNames(packet.removeNodes.begin(),packet.removeNodes.end());
        for(const auto& node:packet.nodes){finalPublishedMeshes.insert(node.mesh);replacedNodeNames.insert(node.name);}
        const auto hasFinalUsers=[&](const std::string& name){
            if(finalPublishedMeshes.contains(name))return true;
            if(auto users=m_meshNodes.find(name);users!=m_meshNodes.end())
                return std::any_of(users->second.begin(),users->second.end(),[&](const auto& node){return !replacedNodeNames.contains(node);});
            return false;
        };
        // Position changes are not graph topology changes when indices, groups,
        // materials and vertex layouts stay identical. The external direct path
        // can rebuild this one native BLAS without replacing its MeshInfo/leaf.
        // This pass evaluates coverage itself; a simultaneous sampler/pixel
        // delta does not turn an otherwise stable mesh into graph topology.
        for(const auto& [name,mesh]:packet.meshes) {
            const auto reuse=reusedMeshes.find(name);
            const auto& residentName=reuse==reusedMeshes.end()?name:reuse->second;
            // An edited dormant definition has no native graph rows. Replace
            // its pending generation now; upload/build only if it becomes live.
            if(!hasFinalUsers(name))continue;
            const bool allowDeformation=m_externalTextureSampling
                &&std::none_of(mesh->geometries.begin(),mesh->geometries.end(),[&](const auto& geometry){
                    return domainChangedMaterials.contains(geometry->material->name);
                });
            if(CanUpdateAttributes(residentName,*mesh)){
                attributeMeshes.insert(name);
                if(SameShadingInputs(residentName,*mesh))unchangedMeshes.insert(name);
            }else if(allowDeformation&&CanUpdateAttributes(residentName,*mesh,true)) {
                attributeMeshes.insert(name);deformedMeshes.insert(name);
            } else if(allowDeformation&&CanRetainGeometryLayout(residentName,*mesh)) {
                // Geometry contents/counts are BLAS inputs, not scene-graph
                // identities. Keep the registered mesh/geometry slots when
                // their material layout has not changed; rebuild this BLAS.
                attributeMeshes.insert(name);reshapedMeshes.insert(name);
            }
        }
        changes.attributes=attributeMeshes.size()!=unchangedMeshes.size();
        // A generation/rectangle publication can contain identical pixels. It
        // still advances the parent-generation contract below, but must not
        // invalidate materials, history or acceleration structures.
        changes.textures = !packet.removeTextures.empty();
        for(const auto& [id,texture]:packet.textures) {
            const auto old=m_textures.find(id);
            changes.textures |= old==m_textures.end()||!old->second.SameLayout(texture)
                ||!texture.unchangedPixels;
            if(old!=m_textures.end()&&(old->second.u!=texture.u||old->second.v!=texture.v||old->second.filter!=texture.filter))
                if(auto users=m_textureMaterials.find(id);users!=m_textureMaterials.end())
                    changes.materialPolicyChanges.insert(users->second.begin(),users->second.end());
        }
        changes.materials = !packet.materials.empty()||!packet.removeMaterials.empty();
        changes.coverageChanged = !packet.coverage.empty();
        // The independent pass evaluates opacity itself without OMMs, so pixel/
        // sampler coverage changes do not change its BLAS geometry flags. Native
        // beauty retains the original conservative coverage rebuild lifecycle.
        changes.requiresCoverageRebuild=changes.coverageChanged&&!m_externalTextureSampling;
        changes.topology = packet.meshes.size()!=attributeMeshes.size() || packet.removeNodes.size()!=reusedNodes.size()
            || changes.requiresCoverageRebuild;
        changes.transforms = false;
        bool structural=changes.topology||changes.materials||changes.textures||changes.attributes||!packet.removeMeshes.empty();
        for(const auto& node:packet.nodes){
            const auto old=m_nodes.find(node.name);
            const bool changedStructure=!reusedNodes.contains(node.name)
                &&(old==m_nodes.end()||old->second.mesh!=node.mesh);
            changes.topology|=changedStructure;structural|=changedStructure;
        }
        // The sample's TLAS holds device addresses, not leases on removed pooled
        // BLASes. Retire previous GPU readers before graph edits can free them.
        if(structural&&beforeStructural)beforeStructural(!requiresMaterialLifecycle&&!changes.requiresCoverageRebuild);
        // A lifecycle boundary may finalize an earlier packet using the same
        // retained native list. Acquire this packet's version only afterward.
        nvrhi::ICommandList* commands=nullptr;
        const auto gpuCommands=[&](){
            if(!commands)commands=acquireCommands();
            if(!commands)throw std::runtime_error("Resource command-list acquisition failed");
            return commands;
        };
        if(changes.materials||changes.textures)dynamic_cast<ManagedScene&>(scene).MarkMaterialRowsDirty();
        RebindContainers(packet,containerReuses,changes,dynamic_cast<ManagedScene&>(scene));

        if (!packet.textures.empty() && (!m_textureDevice||!m_textureDescriptors))
            throw std::logic_error("Inject native texture resources before importing textures");
        for (auto& [id, texture] : packet.textures) {
            const auto old=m_textures.find(id);
            const Texture* previous=old==m_textures.end()?nullptr:&old->second;
            if(previous&&texture.unchangedPixels) {
                texture.loaded=previous->loaded;
                ++m_textureWork[previous->SameLayout(texture)?5:6];
            } else
                texture.loaded=UploadTexture(id,texture,previous,gpuCommands());
        }

        auto findTexture = [&](int64_t id) -> std::shared_ptr<donut::engine::LoadedTexture> {
            if (id < 0) return {};
            if (auto i = packet.textures.find(id); i != packet.textures.end()) return i->second.loaded;
            return m_textures.at(id).loaded;
        };
        auto assignTextures = [&](MaterialEntry& entry) {
            entry.material->baseOrDiffuseTexture = findTexture(entry.textures[0]);
            entry.material->normalTexture = findTexture(entry.textures[1]);
            entry.material->metalRoughOrSpecularTexture = findTexture(entry.textures[2]);
            entry.material->emissiveTexture = findTexture(entry.textures[3]);
            entry.material->dirty = true;
        };
        for (auto& [name, entry] : packet.materials) assignTextures(entry);

        // Preserve registered material identity. The public dirty flag is the
        // original Scene's supported way to publish an edited material.
        for (auto& [name, entry] : packet.materials) {
            auto old = m_materials.find(name);
            if (old != m_materials.end()) {
                changes.existingMaterialsChanged = true;
                const bool domainChanged=old->second.material->domain!=entry.material->domain;
                changes.materialDomainsChanged|=domainChanged&&!m_externalTextureSampling;
                changes.topology|=domainChanged&&!m_externalTextureSampling;
                entry.surfaceSlot=old->second.surfaceSlot;
                const auto constants = old->second.material->materialConstants;
                const auto materialID = old->second.material->materialID;
                UnlinkMaterialTextures(name, old->second);
                *old->second.material = *entry.material;
                old->second.material->materialConstants = constants;
                old->second.material->materialID = materialID;
                entry.material = old->second.material;
            }
        }
        for (const auto& [id, texture] : packet.textures) {
            if(m_externalTextureSampling)if(auto users=m_textureMaterials.find(id);users!=m_textureMaterials.end())
                for(const auto& name:users->second)if(!packet.materials.contains(name))m_surfaceDirty.insert(m_materials.at(name).surfaceSlot);
            if(auto old=m_textures.find(id);old!=m_textures.end()&&old->second.loaded==texture.loaded)continue;
            auto users = m_textureMaterials.find(id);
            if (users == m_textureMaterials.end()) continue;
            for (const auto& name : users->second) {
                if (packet.materials.count(name)) continue;
                assignTextures(m_materials.at(name));
                if(m_externalTextureSampling)m_surfaceDirty.insert(m_materials.at(name).surfaceSlot);
                m_pendingTextureMaterials.insert(name);
            }
        }
        for (auto& [name, entry] : packet.materials) {
            if(m_externalTextureSampling){
                if(entry.surfaceSlot==~0u){
                    if(m_surfaceFree.empty()){entry.surfaceSlot=uint32_t(m_surfaceNames.size());m_surfaceNames.push_back(name);}
                    else{entry.surfaceSlot=m_surfaceFree.back();m_surfaceFree.pop_back();m_surfaceNames[entry.surfaceSlot]=name;}
                }
                m_surfaceDirty.insert(entry.surfaceSlot);
            }
            LinkMaterialTextures(name, entry);
            m_pendingMaterials.insert(name);
            m_pendingIndependentMaterials.insert(name);
            m_pendingTextureMaterials.insert(name);
            m_materials.insert_or_assign(name, std::move(entry));
        }
        for (auto& [id, texture] : packet.textures) {
            if(auto old=m_textures.find(id);old!=m_textures.end()) {
                if(old->second.loaded!=texture.loaded)changes.retiredTextures.push_back(old->second.loaded);
                // Decode proved exact equality without copying the full image.
                // Retain its shadow only after all packet validation has passed.
                if(texture.unchangedPixels)texture.pixels=std::move(old->second.pixels);
            }
            texture.unchangedPixels=false;
            m_textures.insert_or_assign(id, std::move(texture));
        }
        for (auto id : packet.removeTextures) if(auto old=m_textures.find(id);old!=m_textures.end()){
            changes.retiredTextures.push_back(old->second.loaded);
            m_textures.erase(old);
        }

        std::unordered_set<std::string> touchedMeshes, touchedMaterials;
        std::vector<std::shared_ptr<Mesh>> attributeUpdates;
        for (auto& [name, mesh] : packet.meshes) {
            auto& authored=static_cast<AuthoredMeshInfo&>(*mesh);
            if(!authored.surfaceMaterials.empty()){
                for(auto& group:authored.triangleMaterials)group=m_materials.at(authored.surfaceMaterials.at(group)).surfaceSlot;
                authored.surfaceSlots=std::move(authored.triangleMaterials);
                if(attributeMeshes.contains(name)){
                    auto& resident=static_cast<AuthoredMeshInfo&>(*m_meshes.at(name));
                    if(resident.surfaceSlots!=authored.surfaceSlots)changes.surfaceGeometries.push_back(name+":0");
                    UnlinkMeshMaterials(name,resident,touchedMaterials);
                    resident.surfaceMaterials=std::move(authored.surfaceMaterials);
                    resident.triangleMaterials=std::move(authored.triangleMaterials);
                    resident.surfaceSlots=std::move(authored.surfaceSlots);
                    LinkMeshMaterials(name,resident);
                }
            }
            // A public identity replacement is not a geometric edit. The
            // container and metadata names were already published above; keep
            // the immutable native buffers/AS if all consumed channels match.
            if(unchangedMeshes.contains(name))continue;
            if(attributeMeshes.contains(name)){
                RtrDiagnostics::Scope retainedMesh(RtrDiagnostics::SceneMeshPreparation,1,0,1,0,
                    RtrDiagnostics::Identity(name),0,reshapedMeshes.contains(name)?RtrDiagnostics::Topology
                        :(deformedMeshes.contains(name)?RtrDiagnostics::Deformation:RtrDiagnostics::Attributes));
                // Original Scene buffers have permanent read states and cannot
                // be rewritten as CopyDest. Ask its uploader for the new buffer;
                // unchanged positions retain the BLAS, while stable-topology
                // deformation requests a native rebuild of only this mesh.
                auto& resident=*m_meshes.at(name);
                changes.retiredBuffers.push_back(resident.buffers);
                // Authored offsets are local to this generation; a prior
                // shared native batch may have placed this mesh at any offset.
                resident.vertexOffset=mesh->vertexOffset;
                resident.indexOffset=mesh->indexOffset;
                m_meshSources.at(name)=MeshSource::Capture(*mesh);
                if(deformedMeshes.contains(name)||reshapedMeshes.contains(name)) {
                    resident.objectSpaceBounds=mesh->objectSpaceBounds;
                    resident.totalVertices=mesh->totalVertices;
                    resident.totalIndices=mesh->totalIndices;
                    resident.vertexOffset=mesh->vertexOffset;
                    resident.indexOffset=mesh->indexOffset;
                    for(size_t i=0;i<resident.geometries.size();++i) {
                        resident.geometries[i]->objectSpaceBounds=mesh->geometries[i]->objectSpaceBounds;
                        if(reshapedMeshes.contains(name)) {
                            // globalGeometryIndex and the native material owner
                            // remain registered; only authored ranges change.
                            auto& target=*resident.geometries[i];const auto& input=*mesh->geometries[i];
                            target.indexOffsetInMesh=input.indexOffsetInMesh;
                            target.vertexOffsetInMesh=input.vertexOffsetInMesh;
                            target.numIndices=input.numIndices;target.numVertices=input.numVertices;
                        }
                    }
                    if(reshapedMeshes.contains(name))changes.reshapedMeshes.push_back(m_meshes.at(name));
                    else changes.deformedMeshes.push_back(m_meshes.at(name));
                    // A public no-value transform edit marks the new leaf bounds
                    // dirty without unregistering/reordering any scene resource.
                    // Consume it only in the normal Render refresh, preserving
                    // the current/previous pose publication boundary.
                    if(auto users=m_meshNodes.find(name);users!=m_meshNodes.end())
                        for(const auto& instance:users->second){
                            static_cast<ManagedScene&>(scene).MarkInstanceInput(m_nodes.at(instance).node);
                            m_nodes.at(instance).node->SetTransform(nullptr,nullptr,nullptr);
                        }
                }
                mesh->buffers->instanceBuffer=resident.buffers->instanceBuffer;
                resident.buffers=std::move(mesh->buffers);
                static_cast<AuthoredMeshInfo&>(resident).pendingInputs=
                    std::move(static_cast<AuthoredMeshInfo&>(*mesh).pendingInputs);
                attributeUpdates.push_back(m_meshes.at(name));
                continue;
            }
            // The original uploader frees its CPU arrays. Retain only the
            // geometry inputs needed to prove a future update does not need AS.
            m_meshSources.insert_or_assign(name,MeshSource::Capture(*mesh));
            if (auto old = m_meshes.find(name); old != m_meshes.end()) {
                changes.retiredMeshes.push_back(old->second);
                for(size_t i=0;i<old->second->geometries.size();++i)
                    changes.retiredGeometries.push_back(name+":"+std::to_string(i));
                UnlinkMeshMaterials(name, *old->second, touchedMaterials);
            }
            for (auto& geometry : mesh->geometries)
                geometry->material = m_materials.at(geometry->material->name).material;
            LinkMeshMaterials(name, *mesh);
            m_meshes.insert_or_assign(name, std::move(mesh));
            touchedMeshes.insert(name);
        }
        {RtrDiagnostics::Scope removals(RtrDiagnostics::ResourceIndex,packet.removeNodes.size(),graph->GetMeshInstances().size(),1,0,
            RtrDiagnostics::level.load()?RtrDiagnostics::Identity("graph-node-removals"):0,0,RtrDiagnostics::Removal);
        for (const auto& name : packet.removeNodes) {
            if (auto i = m_nodes.find(name); i != m_nodes.end()) {
                changes.retiredInstances.push_back(name);
                touchedMeshes.insert(i->second.mesh);
                m_meshNodes.at(i->second.mesh).erase(name);
                graph->Detach(i->second.poseNode);
                m_nodes.erase(i);
                m_pendingTransforms.erase(name);
            }
        }}
        // Replacing a mesh must also replace all existing leaves referencing it.
        // SetLeaf handles official resource registration and graph dirty flags.
        for (const auto& [meshName, mesh] : packet.meshes) {
            if(attributeMeshes.contains(meshName))continue;
            auto users = m_meshNodes.find(meshName);
            if (users == m_meshNodes.end()) continue;
            for (const auto& name : users->second)
                m_nodes.at(name).node->SetLeaf(m_factory.CreateMeshInstance(m_meshes.at(meshName)));
        }
        if (requiresMaterialLifecycle || changes.requiresCoverageRebuild) graph->GetRootNode()->InvalidateContent();
        {RtrDiagnostics::Scope publications(RtrDiagnostics::ResourceIndex,packet.nodes.size(),graph->GetMeshInstances().size(),1,0,
            RtrDiagnostics::level.load()?RtrDiagnostics::Identity("graph-node-publications"):0,0,RtrDiagnostics::Topology|RtrDiagnostics::Pose);
        for (const auto& input : packet.nodes) {
            auto old = m_nodes.find(input.name);
            const bool created=old==m_nodes.end();
            if (created) {
                auto poseNode=std::make_shared<Node>();poseNode->SetName(input.name+"/affine");
                graph->Attach(graph->GetRootNode(),poseNode);
                auto node = graph->AttachLeafNode(poseNode,m_factory.CreateMeshInstance(m_meshes.at(input.mesh)));
                node->SetName(input.name);
                old = m_nodes.emplace(input.name, Instance{input.mesh,node,poseNode}).first;
                m_meshNodes[input.mesh].insert(input.name);
                changes.topology = true;
            } else if (old->second.mesh != input.mesh) {
                touchedMeshes.insert(old->second.mesh);
                m_meshNodes.at(old->second.mesh).erase(input.name);
                old->second.node->SetLeaf(m_factory.CreateMeshInstance(m_meshes.at(input.mesh)));
                old->second.mesh = input.mesh;
                m_meshNodes[input.mesh].insert(input.name);
                changes.topology = true;
            }
            if(created)
                SetPose(old->second,input);
            else
                changes.transforms|=StagePose(old->second,input);
        }}
        // Definitions outlive their graph users. Versioned packets retire them
        // explicitly; legacy packets preserve their former implicit lifetime.
        // Full beauty rebuilds release their whole native BLAS pool. Dormant
        // pooled handles cannot outlive that pool, nor keep obsolete domain/OMM
        // inputs. The original lifecycle boundary above already waited idle.
        // Direct lighting uses ordinary non-pooled BLASes and retains them.
        if(!m_externalTextureSampling)for(const auto& name:touchedMeshes)
            if(auto mesh=m_meshes.find(name);mesh!=m_meshes.end()&&!IsLiveMesh(mesh->second)){
                auto& native=static_cast<MeshInfoEx&>(*mesh->second);
                // A pending bake must not publish another pooled handle after
                // this definition leaves the graph visited by ReleaseBlases.
                if(beforeDormantMesh)beforeDormantMesh(native);
                native.accelStruct=nullptr;
                native.AccelStructOMM=nullptr;
                native.OpacityMicroMaps.clear();
                native.DebugData=nullptr;
                native.MarkDebugDataDirty();
                for(const auto& geometry:native.geometries)
                    static_cast<MeshGeometryEx&>(*geometry).DebugData={};
            }
        const auto retireMesh=[&](const std::string& name) {
            auto users = m_meshNodes.find(name);
            if (users != m_meshNodes.end() && !users->second.empty()) return;
            m_meshNodes.erase(name);
            if (auto mesh = m_meshes.find(name); mesh != m_meshes.end()) {
                for(size_t i=0;i<mesh->second->geometries.size();++i)
                    changes.retiredGeometries.push_back(name+":"+std::to_string(i));
                UnlinkMeshMaterials(name, *mesh->second, touchedMaterials);
                changes.retiredMeshes.push_back(mesh->second);
                m_meshSources.erase(name);
                m_meshes.erase(mesh);
            }
        };
        if(packet.retainedDefinitions)for(const auto& name:packet.removeMeshes)retireMesh(name);
        else for(const auto& name:touchedMeshes)retireMesh(name);
        for (const auto& [name, entry] : packet.materials) touchedMaterials.insert(name);
        for (const auto& name : touchedMaterials) {
            auto users = m_materialMeshes.find(name);
            if (users != m_materialMeshes.end() && !users->second.empty()) continue;
            m_materialMeshes.erase(name);
        }
        // A material is a declared resource, not a mesh-owned temporary. The
        // public scene keeps it across periods with no instances; only an
        // explicit definition removal retires its identity and texture links.
        for(const auto& name:packet.removeMaterials) {
            if (auto material = m_materials.find(name); material != m_materials.end()) {
                if(material->second.surfaceSlot!=~0u){
                    const auto slot=material->second.surfaceSlot;m_surfaceNames.at(slot).clear();
                    m_surfaceFree.push_back(slot);m_surfaceDirty.insert(slot);
                }
                UnlinkMaterialTextures(name, material->second);
                m_materials.erase(material);
            }
            m_pendingMaterials.erase(name);
            m_pendingIndependentMaterials.erase(name);
            m_pendingTextureMaterials.erase(name);
        }
        m_graph = graph;
        // The native uploader/builders still own GPU resources. Hand them only
        // actual incoming/replaced meshes, not a repeated resident-scene scan.
        for(const auto& [name,mesh]:packet.meshes)
            if(!attributeMeshes.contains(name)) {
                const auto resident=m_meshes.find(name);
                if(resident!=m_meshes.end()&&IsLiveMesh(resident->second))changes.buildMeshes.push_back(resident->second);
            }
        // A definition can become live in a later packet. Resolve just those
        // newly referenced identities, retaining the changed-only input path.
        std::unordered_set<std::string> newlyLiveInputs;
        for(const auto& input:packet.nodes)if(!packet.meshes.contains(input.mesh)){
            const auto resident=m_meshes.find(input.mesh);
            if(resident!=m_meshes.end()&&(PendingMeshInputs(*resident->second)||!resident->second->accelStruct)
                &&IsLiveMesh(resident->second)&&newlyLiveInputs.insert(input.mesh).second)
                changes.buildMeshes.push_back(resident->second);
        }
        std::erase_if(attributeUpdates,[&](const auto& mesh){return !IsLiveMesh(mesh);});
        // Structural/material initialization still needs original buffer allocation
        // before its bakers run. Existing-node poses remain staged until Render.
        // A transform-only packet needs neither this refresh nor a GPU submission.
        const bool originalUploadsAttributes=graph->HasPendingStructureChanges();
        // A texture replacement leaves the original Material dirty. Sample::Render
        // refreshes that same current frame before MaterialsBaker::Update and our
        // lighting pass; an eager texture-only refresh would scan the scene twice.
        // New structure and material definitions still need immediate preparation.
        if(changes.topology||requiresMaterialLifecycle
            ||(deferTopologyRefresh&&graph->HasPendingStructureChanges()))
            {
                auto pending=changes.buildMeshes;pending.insert(pending.end(),attributeUpdates.begin(),attributeUpdates.end());
                if(m_externalTextureSampling) {
                    if(deferTopologyRefresh&&!requiresMaterialLifecycle&&!changes.requiresCoverageRebuild
                        &&graph->GetSkinnedMeshInstances().empty()){
                        changes.pendingTopologyRefresh=true;changes.pendingUploadMeshes=std::move(pending);
                    }else dynamic_cast<ManagedScene&>(scene).RefreshTopology(gpuCommands(),frameIndex,pending);
                } else {
                    dynamic_cast<ManagedScene&>(scene).PrepareMeshInputs(pending);
                    RtrDiagnostics::Scope refreshScope(RtrDiagnostics::SceneFullRefresh);scene.Refresh(gpuCommands(),frameIndex);
                }
                }
        // A texture/material refresh need not have a structural dirty flag, so the original
        // Scene does not upload replacement attribute buffers or geometry rows.
        // Do not lose those updates merely because a texture shares the packet.
        // Actual structural refreshes already run the original complete uploader.
        if(!attributeUpdates.empty()&&!originalUploadsAttributes&&!changes.pendingTopologyRefresh){
            if(m_externalTextureSampling&&deferTopologyRefresh&&!requiresMaterialLifecycle
                &&!changes.requiresCoverageRebuild&&graph->GetSkinnedMeshInstances().empty()){
                // Stable attributes need the same final-live boundary as new
                // topology: later packets can replace or remove these inputs.
                changes.pendingTopologyRefresh=true;changes.pendingUploadMeshes=std::move(attributeUpdates);
            }else {RtrDiagnostics::Scope attributeScope(RtrDiagnostics::AttributePreparation,attributeUpdates.size());
                dynamic_cast<ManagedScene&>(scene).RefreshAttributes(gpuCommands(),attributeUpdates);}
        }
        return changes;
    }

private:
    using Material = donut::engine::Material;
    using Mesh = donut::engine::MeshInfo;
    using Node = donut::engine::SceneGraphNode;

    struct Texture {
        uint32_t width = 0, height = 0, mips = 0, color = 0, u = 0, v = 0, filter = 0;
        uint64_t generation = 0;
        bool rectangle=false,unchangedPixels=false;
        uint32_t patchMip=0,patchX=0,patchY=0,patchWidth=0,patchHeight=0;
        // Required only for the packet's parent-generation rectangle semantics.
        // There is no upload cache, spare-resource list or GPU allocation policy.
        std::vector<uint8_t> pixels;
        std::shared_ptr<donut::engine::LoadedTexture> loaded;
        bool SameImageLayout(const Texture& other) const {
            return width == other.width && height == other.height && mips == other.mips
                && color == other.color;
        }
        bool SameLayout(const Texture& other) const {
            // Sampler policy belongs to the external pass metadata. It is not
            // part of the original TextureCache image or descriptor identity.
            return SameImageLayout(other) && u == other.u && v == other.v && filter == other.filter;
        }
    };
    struct MaterialEntry {
        std::shared_ptr<Material> material;
        uint32_t surfaceSlot=~0u;
        std::array<int64_t, 4> textures{};
        float ior = 1.5f;
        bool psdExclude = true;
        int psdDominant = -1;
        int psdBlockMotionVectors = 0;
        bool boundary = false;
        donut::math::float3 absorption = 0.f;
        int priority = 0;
    };
    struct Instance {
        std::string mesh;
        std::shared_ptr<Node> node,poseNode;
        AffinePose pose;
    };
    struct NodeInput {
        std::string name, mesh;
        AffinePose pose;
    };
    bool StagePose(const Instance& instance,const NodeInput& input){
        // Mesh/attribute publications also carry their existing users. Native
        // SetTransform always dirties the graph, even for identical values.
        // Keep actual pose edits (including a later packet reverting an earlier
        // staged edit), without making resource-only changes rebuild the TLAS.
        if(SamePose(instance.pose,input.pose)){m_pendingTransforms.erase(input.name);return false;}
        if(auto i=m_pendingTransforms.find(input.name);i!=m_pendingTransforms.end()&&SamePose(i->second.pose,input.pose))return false;
        m_pendingTransforms.insert_or_assign(input.name,input);return true;
    }
    static void SetPose(Instance& instance,const NodeInput& input){
        const auto& pose=input.pose;
        instance.poseNode->SetTransform(&pose.translation,&pose.rotation,&pose.scaling);
        if(instance.poseNode!=instance.node)instance.node->SetRotation(pose.inner);
        instance.pose=pose;
    }
    struct MeshSource {
        std::shared_ptr<const AuthoredMeshInputs> inputs;
        static MeshSource Capture(const Mesh& mesh){
            const auto& input=PendingMeshInputs(mesh);
            if(!input)throw std::logic_error("Authored mesh source is missing its owned input");
            return {input};
        }
    };
    std::unordered_map<std::string,MeshSource> m_meshSources;
    static bool SamePositions(const AuthoredMeshInputs& a,const AuthoredMeshInputs& b){
        return a.positions.size()==b.positions.size()
            &&std::memcmp(a.positions.data(),b.positions.data(),a.positions.size()*sizeof(float))==0;
    }
    static bool SameIndices(const AuthoredMeshInputs& a,const AuthoredMeshInputs& b){
        if(a.indexCount!=b.indexCount||a.indices.size()!=b.indices.size())return false;
        for(size_t i=0;i<a.indices.size();++i){
            const auto& x=a.indices[i];const auto& y=b.indices[i];
            if(x.count!=y.count||std::memcmp(x.data,y.data,size_t(x.count)*sizeof(uint32_t))!=0)return false;
        }
        return true;
    }
    bool SameShadingInputs(const std::string& name,const Mesh& mesh)const{
        const auto& source=*m_meshSources.at(name).inputs;const auto& input=*PendingMeshInputs(mesh);
        const auto same=[](const auto& a,const auto& b){
            return a.size()==b.size()&&(a.empty()||std::memcmp(a.data(),b.data(),a.size()*sizeof(float))==0);
        };
        const auto& resident=*m_meshes.at(name);
        if(!input.tangents.empty()&&resident.buffers->vertexBuffer
            &&!resident.buffers->hasAttribute(donut::engine::VertexAttribute::Tangent))return false;
        return same(source.normals,input.normals)&&same(source.tangents,input.tangents)&&same(source.uv,input.uv);
    }
    static bool HasNativeOrPendingInputs(const Mesh& mesh){
        // An earlier packet may already have replaced this generation's CPU
        // inputs while its native upload is intentionally deferred. Absence of
        // that new vertexBuffer does not retire the registered MeshInfo/BLAS.
        return mesh.buffers&&(mesh.buffers->vertexBuffer||PendingMeshInputs(mesh));
    }
    bool CanUpdateAttributes(const std::string& name,const Mesh& mesh,bool allowPositionChanges=false)const{
        const auto old=m_meshes.find(name),end=m_meshes.end();
        const auto shadow=m_meshSources.find(name);
        if(old==end||shadow==m_meshSources.end()||!HasNativeOrPendingInputs(*old->second)
            ||old->second->geometries.size()!=mesh.geometries.size())return false;
        const auto& input=PendingMeshInputs(mesh);
        if(!input||!shadow->second.inputs)return false;
        const auto& a=*old->second;const auto& source=*shadow->second.inputs;
        if(a.totalVertices!=mesh.totalVertices||a.totalIndices!=mesh.totalIndices
            ||a.type!=mesh.type
            ||a.isSkinPrototype||a.skinPrototype.use_count()!=0
            ||mesh.isSkinPrototype||mesh.skinPrototype.use_count()!=0
            ||source.positions.size()!=input->positions.size()
            ||(!allowPositionChanges&&!SamePositions(source,*input))
            ||!SameIndices(source,*input))return false;
        for(size_t i=0;i<a.geometries.size();++i){
            const auto& x=*a.geometries[i];const auto& y=*mesh.geometries[i];
            if(x.material->name!=y.material->name||x.indexOffsetInMesh!=y.indexOffsetInMesh||x.numIndices!=y.numIndices
                ||x.vertexOffsetInMesh!=y.vertexOffsetInMesh||x.numVertices!=y.numVertices)return false;
        }
        // Compare the authored row, not the aggregate range of a native batch.
        return source.AttributeSizes()==input->AttributeSizes();
    }
    bool CanRetainGeometryLayout(const std::string& name,const Mesh& mesh)const{
        const auto old=m_meshes.find(name);
        if(old==m_meshes.end()||!m_meshSources.contains(name)||!HasNativeOrPendingInputs(*old->second))return false;
        const auto& resident=*old->second;
        if(resident.type!=mesh.type||resident.geometries.size()!=mesh.geometries.size()
            ||resident.isSkinPrototype||resident.skinPrototype.use_count()!=0
            ||mesh.isSkinPrototype||mesh.skinPrototype.use_count()!=0)return false;
        for(size_t i=0;i<resident.geometries.size();++i) {
            const auto& previous=*resident.geometries[i];const auto& next=*mesh.geometries[i];
            if(previous.material->name!=next.material->name||previous.type!=next.type)return false;
        }
        return true;
    }
    static bool SameMaterial(const MaterialEntry& a,const MaterialEntry& b){
        const auto& x=*a.material;const auto& y=*b.material;
        return a.textures==b.textures&&a.ior==b.ior&&a.psdExclude==b.psdExclude&&a.psdDominant==b.psdDominant
            &&a.psdBlockMotionVectors==b.psdBlockMotionVectors&&a.boundary==b.boundary&&a.priority==b.priority
            &&std::memcmp(&a.absorption,&b.absorption,sizeof(a.absorption))==0
            &&std::memcmp(&x.baseOrDiffuseColor,&y.baseOrDiffuseColor,sizeof(x.baseOrDiffuseColor))==0
            &&std::memcmp(&x.emissiveColor,&y.emissiveColor,sizeof(x.emissiveColor))==0
            &&x.opacity==y.opacity&&x.roughness==y.roughness&&x.metalness==y.metalness
            &&x.transmissionFactor==y.transmissionFactor&&x.alphaCutoff==y.alphaCutoff
            &&x.domain==y.domain&&x.doubleSided==y.doubleSided;
    }
    struct Packet {
        bool retainedDefinitions=false;
        std::unordered_map<int64_t, Texture> textures;
        std::unordered_map<std::string, MaterialEntry> materials;
        std::unordered_map<std::string, std::shared_ptr<Mesh>> meshes;
        std::vector<std::string> removeNodes, coverage, removeMaterials,removeMeshes;
        std::vector<int64_t> removeTextures;
        std::vector<NodeInput> nodes;
    };

    struct ContainerReuse {std::string oldNode,oldMesh,newNode,newMesh;bool retainNode;};
    static bool SamePose(const AffinePose& a,const AffinePose& b) {
        for(unsigned i=0;i<3;++i)if(a.translation[i]!=b.translation[i]||a.scaling[i]!=b.scaling[i])return false;
        return a.rotation.w==b.rotation.w&&a.rotation.x==b.rotation.x&&a.rotation.y==b.rotation.y&&a.rotation.z==b.rotation.z
            &&a.inner.w==b.inner.w&&a.inner.x==b.inner.x&&a.inner.y==b.inner.y&&a.inner.z==b.inner.z&&a.split==b.split;
    }
    static bool StationaryPose(const Instance& instance) {
        const auto& current=instance.node->GetLocalToWorldTransformFloat();
        const auto& previous=instance.node->GetPrevLocalToWorldTransformFloat();
        for(unsigned r=0;r<3;++r) {
            if(current.m_translation[r]!=previous.m_translation[r])return false;
            for(unsigned c=0;c<3;++c)if(current.m_linear[c][r]!=previous.m_linear[c][r])return false;
        }
        return true;
    }
    static std::string ContainerLayout(const Mesh& mesh) {
        std::string key=std::to_string(uint32_t(mesh.type))+"/";
        for(const auto& geometry:mesh.geometries) {
            const auto& name=geometry->material->name;
            key+=std::to_string(uint32_t(geometry->type))+":"+std::to_string(name.size())+":"+name+"/";
        }
        return key;
    }
    std::vector<ContainerReuse> PlanContainerReuses(const Packet& packet,
        const std::unordered_set<std::string>& domainChanges) const {
        std::vector<ContainerReuse> result;
        if(!m_externalTextureSampling||packet.removeNodes.empty()||packet.meshes.empty())return result;
        std::unordered_map<std::string,size_t> incomingUsers;
        for(const auto& input:packet.nodes)++incomingUsers[input.mesh];
        std::unordered_map<std::string,std::vector<std::string>> available;
        uint64_t notVacant=0,layoutMismatch=0;
        const std::unordered_set<std::string> removedDefinitions(packet.removeMeshes.begin(),packet.removeMeshes.end());
        for(const auto& name:packet.removeNodes) {
            const auto old=m_nodes.find(name);
            if(old==m_nodes.end()){++notVacant;continue;}
            const auto& meshName=old->second.mesh;
            if(packet.retainedDefinitions&&!removedDefinitions.contains(meshName)){++notVacant;continue;}
            const auto users=m_meshNodes.find(meshName);
            if(users==m_meshNodes.end()||users->second.size()!=1||packet.meshes.contains(meshName)||incomingUsers.contains(meshName)){++notVacant;continue;}
            available[ContainerLayout(*m_meshes.at(meshName))].push_back(name);
        }
        for(const auto& input:packet.nodes) {
            if(m_nodes.contains(input.name)||m_meshes.contains(input.mesh)||incomingUsers.at(input.mesh)!=1)continue;
            const auto incoming=packet.meshes.find(input.mesh);if(incoming==packet.meshes.end())continue;
            const auto& mesh=*incoming->second;
            if(std::any_of(mesh.geometries.begin(),mesh.geometries.end(),[&](const auto& geometry){return domainChanges.contains(geometry->material->name);}))continue;
            auto bucket=available.find(ContainerLayout(mesh));if(bucket==available.end()){++layoutMismatch;continue;}
            auto& candidates=bucket->second;
            // Geometry lives in local space. A different instance pose does
            // not invalidate its native buffers or BLAS. Prefer retaining a
            // same-pose node; otherwise use the ordinary detach/attach lifecycle
            // below to give the new instance its own previous transform.
            for(unsigned pass=0;pass<2;++pass) {
              bool matched=false;
              for(size_t i=candidates.size();i>0;--i) {
                const auto& old=m_nodes.at(candidates[i-1]);
                const bool retainNode=SamePose(old.pose,input.pose)&&StationaryPose(old)
                    &&!m_pendingTransforms.contains(candidates[i-1]);
                if(retainNode!=(pass==0))continue;
                if(!CanRetainGeometryLayout(old.mesh,mesh)){++layoutMismatch;continue;}
                result.push_back({candidates[i-1],old.mesh,input.name,input.mesh,retainNode});
                candidates.erase(candidates.begin()+i-1);matched=true;break;
              }
              if(matched)break;
            }
        }
        // Counts reuse rejections, not original RefreshBuffers invocations.
        // The reason mask distinguishes these from capacity/skin fallback.
        const auto rejected=[&](uint64_t count,uint64_t reason){if(count){
            RtrDiagnostics::Scope miss(RtrDiagnostics::SceneTopologyFallback,count,packet.removeNodes.size(),1,0,0,0,reason);
            miss.event[11]|=RtrDiagnostics::CacheMiss;
        }};
        rejected(notVacant,RtrDiagnostics::Removal);
        rejected(layoutMismatch,RtrDiagnostics::Topology|RtrDiagnostics::Material);
        return result;
    }
    void RebindContainers(Packet& packet,const std::vector<ContainerReuse>& reuses,Changes& changes,ManagedScene& scene) {
        std::unordered_set<std::string> consumed;
        for(const auto& reuse:reuses) {
            RtrDiagnostics::Scope replacement(RtrDiagnostics::SceneMeshPreparation,1,0,1,0,
                RtrDiagnostics::Identity(reuse.newMesh),0,RtrDiagnostics::Topology);
            auto mesh=m_meshes.extract(reuse.oldMesh);
            std::unordered_set<std::string> touched;
            UnlinkMeshMaterials(reuse.oldMesh,*mesh.mapped(),touched);
            for(size_t i=0;i<mesh.mapped()->geometries.size();++i) {
                changes.renamedGeometries.emplace_back(reuse.oldMesh+":"+std::to_string(i),reuse.newMesh+":"+std::to_string(i));
            }
            LinkMeshMaterials(reuse.newMesh,*mesh.mapped());
            mesh.mapped()->name=reuse.newMesh;mesh.key()=reuse.newMesh;m_meshes.insert(std::move(mesh));
            scene.RecordMeshIdentityChange(m_meshes.at(reuse.newMesh));
            auto source=m_meshSources.extract(reuse.oldMesh);source.key()=reuse.newMesh;m_meshSources.insert(std::move(source));
            auto users=m_meshNodes.extract(reuse.oldMesh);users.key()=reuse.newMesh;
            if(!reuse.retainNode){
                // Keep only the mesh allocation. The old leaf is still retired
                // by the ordinary packet removal, and its replacement is a new
                // native node at the authored pose, with no inherited motion.
                m_nodes.at(reuse.oldNode).mesh=reuse.newMesh;
                m_meshNodes.insert(std::move(users));
                replacement.event[11]|=RtrDiagnostics::CacheHit;
                continue;
            }
            users.mapped().erase(reuse.oldNode);users.mapped().insert(reuse.newNode);m_meshNodes.insert(std::move(users));
            auto node=m_nodes.extract(reuse.oldNode);node.key()=reuse.newNode;node.mapped().mesh=reuse.newMesh;
            node.mapped().node->SetName(reuse.newNode);node.mapped().poseNode->SetName(reuse.newNode+"/affine");
            scene.RecordInstanceIdentityChange(std::dynamic_pointer_cast<donut::engine::MeshInstance>(node.mapped().node->GetLeaf()));
            m_nodes.insert(std::move(node));m_pendingTransforms.erase(reuse.oldNode);
            changes.retiredInstances.push_back(reuse.oldNode);consumed.insert(reuse.oldNode);
            replacement.event[11]|=RtrDiagnostics::CacheHit;
        }
        if(!consumed.empty())std::erase_if(packet.removeNodes,[&](const auto& name){return consumed.contains(name);});
    }

    // Authored raw RGBA8 needs no container decode. Use the native allocator,
    // descriptor table and uploader directly; no second pool or upload arena.
    // Each changed generation is immutable while older GPU work can read it.
    std::shared_ptr<donut::engine::LoadedTexture> UploadTexture(int64_t id,const Texture& input,
        const Texture* previous,nvrhi::ICommandList* commands) {
        std::array<uint64_t,7> work{};
        auto loaded=std::make_shared<donut::engine::LoadedTexture>();
        loaded->path="external/texture-"+std::to_string(id)+"-"+std::to_string(input.generation)+".rgba8";
        loaded->mimeType="application/x-rgba8";loaded->originalBitsPerPixel=32;
        loaded->loadOptions.sRGBMode=input.color?donut::engine::SRGBMode::ForceSRGB:donut::engine::SRGBMode::ForceLinear;
        nvrhi::TextureDesc desc;desc.width=input.width;desc.height=input.height;desc.mipLevels=input.mips;
        desc.format=input.color?nvrhi::Format::SRGBA8_UNORM:nvrhi::Format::RGBA8_UNORM;
        desc.debugName=loaded->path;desc.initialState=nvrhi::ResourceStates::ShaderResource;desc.keepInitialState=true;
        loaded->texture=m_textureDevice->createTexture(desc);
        if(!loaded->texture)throw std::runtime_error("Native authored texture allocation failed");
        loaded->bindlessDescriptor=m_textureDescriptors->CreateDescriptorHandle(nvrhi::BindingSetItem::Texture_SRV(0,loaded->texture));
        const bool compatible=previous&&previous->SameImageLayout(input);
        size_t offset=0;
        for(uint32_t mip=0;mip<input.mips;++mip) {
            const auto width=std::max(1u,input.width>>mip),height=std::max(1u,input.height>>mip);
            const size_t bytes=size_t(width)*height*4;
            // Rectangle decode already proved the authored mip changed and all
            // other mips stayed equal; do not rescan the complete CPU image.
            const bool unchanged=compatible&&(input.rectangle?mip!=input.patchMip
                :std::memcmp(input.pixels.data()+offset,previous->pixels.data()+offset,bytes)==0);
            if(unchanged) {
                commands->copyTexture(loaded->texture,nvrhi::TextureSlice().setMipLevel(mip),
                    previous->loaded->texture,nvrhi::TextureSlice().setMipLevel(mip));
                work[4]+=bytes;
            } else if(compatible&&input.rectangle&&mip==input.patchMip
                &&uint64_t(input.patchWidth)*input.patchHeight<uint64_t(width)*height) {
                commands->copyTexture(loaded->texture,nvrhi::TextureSlice().setMipLevel(mip),
                    previous->loaded->texture,nvrhi::TextureSlice().setMipLevel(mip));
                work[4]+=bytes;
                auto patchDesc=desc;patchDesc.width=input.patchWidth;patchDesc.height=input.patchHeight;patchDesc.mipLevels=1;
                patchDesc.debugName="Authored texture rectangle upload";
                auto patch=m_textureDevice->createTexture(patchDesc);
                if(!patch)throw std::runtime_error("Native texture rectangle allocation failed");
                commands->writeTexture(patch,0,0,input.pixels.data()+offset+(size_t(input.patchY)*width+input.patchX)*4,size_t(width)*4);
                auto target=nvrhi::TextureSlice().setMipLevel(mip);target.x=input.patchX;target.y=input.patchY;
                target.width=input.patchWidth;target.height=input.patchHeight;
                commands->copyTexture(loaded->texture,target,patch,nvrhi::TextureSlice());
                ++work[2];work[3]+=uint64_t(input.patchWidth)*input.patchHeight*4;
                // NVRHI command-list references keep this temporary image and its
                // native upload storage alive through GPU execution.
            } else {
                commands->writeTexture(loaded->texture,0,mip,input.pixels.data()+offset,size_t(width)*4);
                ++work[0];work[1]+=bytes;
            }
            offset+=bytes;
        }
        for(size_t i=0;i<work.size();++i)m_textureWork[i]+=work[i];
        return loaded;
    }

    static donut::math::float3 Color(uint32_t rgba) {
        return donut::math::float3(float(rgba & 255), float((rgba >> 8) & 255),
            float((rgba >> 16) & 255)) / 255.f;
    }
    static std::string Name(RtrResourceReader& reader) {
        auto result = reader.text();
        if (result.empty() || result.find('\0') != std::string::npos)
            throw std::invalid_argument("Resource identity must be nonempty UTF-8 without NUL");
        return result;
    }
    void LinkMaterialTextures(const std::string& name, const MaterialEntry& entry) {
        for (auto id : entry.textures) if (id >= 0) m_textureMaterials[id].insert(name);
    }
    void UnlinkMaterialTextures(const std::string& name, const MaterialEntry& entry) {
        for (auto id : entry.textures) if (id >= 0) {
            auto i = m_textureMaterials.find(id);
            if (i != m_textureMaterials.end() && (i->second.erase(name), i->second.empty()))
                m_textureMaterials.erase(i);
        }
    }
    void LinkMeshMaterials(const std::string& name, const Mesh& mesh) {
        if(const auto& authored=static_cast<const AuthoredMeshInfo&>(mesh);!authored.surfaceMaterials.empty()){
            for(const auto& material:authored.surfaceMaterials){m_materialMeshes[material].insert(name);
                if(auto found=m_materials.find(material);found!=m_materials.end()&&found->second.surfaceSlot!=~0u)m_surfaceDirty.insert(found->second.surfaceSlot);}
            return;
        }
        for (const auto& geometry : mesh.geometries)
            m_materialMeshes[geometry->material->name].insert(name);
    }
    void UnlinkMeshMaterials(const std::string& name, const Mesh& mesh,
        std::unordered_set<std::string>& touched) {
        if(const auto& authored=static_cast<const AuthoredMeshInfo&>(mesh);!authored.surfaceMaterials.empty()){
            for(const auto& material:authored.surfaceMaterials){
                touched.insert(material);if(auto found=m_materialMeshes.find(material);found!=m_materialMeshes.end())found->second.erase(name);
                if(auto found=m_materials.find(material);found!=m_materials.end()&&found->second.surfaceSlot!=~0u)m_surfaceDirty.insert(found->second.surfaceSlot);
            }
            return;
        }
        for (const auto& geometry : mesh.geometries) {
            const auto& material = geometry->material->name;
            touched.insert(material);
            auto i = m_materialMeshes.find(material);
            if (i != m_materialMeshes.end()) i->second.erase(name);
        }
    }
    const Texture& FindTexture(int64_t id, const Packet& packet) const {
        if (auto i = packet.textures.find(id); i != packet.textures.end()) return i->second;
        auto i = m_textures.find(id);
        if (i == m_textures.end()) throw std::invalid_argument("Unknown texture identity");
        return i->second;
    }
    const MaterialEntry& FindMaterial(const std::string& name, const Packet& packet) const {
        if (auto i = packet.materials.find(name); i != packet.materials.end()) return i->second;
        auto i = m_materials.find(name);
        if (i == m_materials.end()) throw std::invalid_argument("Unknown geometry material: " + name);
        return i->second;
    }

    Packet Decode(const void* bytes, size_t size) {
        RtrResourceReader reader(bytes, size);
        const auto protocol=reader.value<uint32_t>();
        if (protocol != 0x52445259&&protocol != 0x5244525a&&protocol != 0x5244525b)
            throw std::invalid_argument("Unknown resource transport");
        Packet packet;
        packet.retainedDefinitions=protocol!=0x52445259;
        const bool packetInputSources=protocol==0x5244525b;
        for (uint32_t n = reader.count(); n; --n) {
            const auto id = reader.value<int64_t>();
            Texture texture;
            texture.width = reader.value<uint32_t>(); texture.height = reader.value<uint32_t>();
            texture.mips = reader.value<uint32_t>(); texture.color = reader.value<uint32_t>();
            texture.u = reader.value<uint32_t>(); texture.v = reader.value<uint32_t>();
            texture.filter = reader.value<uint32_t>(); texture.generation = reader.value<uint64_t>();
            const auto transport = reader.value<uint32_t>();
            if (id < 0 || !texture.width || !texture.height || texture.width > 16384
                || texture.height > 16384 || !texture.mips || texture.mips > 15
                || texture.color > 1 || texture.u > 1 || texture.v > 1 || texture.filter > 1
                || !texture.generation || transport > 2)
                throw std::invalid_argument("Invalid texture metadata");
            // Original RTXPT's public material path has one shared material
            // sampler, not per-texture address/filter policies. Never ignore it.
            if (!m_externalTextureSampling && (texture.u || texture.v || texture.filter != 1))
                throw std::invalid_argument("Original RTXPT material path cannot express per-texture clamp or nearest sampling");
            size_t expected = 0;
            uint32_t width = texture.width, height = texture.height;
            for (uint32_t mip = 0; mip < texture.mips; ++mip) {
                expected += size_t(width) * height * 4;
                if (mip + 1 < texture.mips && width == 1 && height == 1)
                    throw std::invalid_argument("Texture mip chain exceeds its extent");
                width = std::max(1u, width / 2); height = std::max(1u, height / 2);
            }
            if (!transport) {
                const auto count=reader.count();const auto* pixels=reader.take(count);
                if(count!=expected)throw std::invalid_argument("Invalid RGBA8 texture byte count");
                const auto old=m_textures.find(id);
                texture.unchangedPixels=old!=m_textures.end()&&old->second.SameImageLayout(texture)
                    &&old->second.pixels.size()==expected
                    &&std::memcmp(old->second.pixels.data(),pixels,expected)==0;
                if(!texture.unchangedPixels)texture.pixels.assign(pixels,pixels+count);
            }
            else if(transport==2) {
                // The bridge compared the complete immutable pixels before
                // choosing this wire form. Bind that proof to the exact native
                // base generation; later queued packets cannot inherit stale data.
                const auto parent=reader.value<uint64_t>();
                const auto old=m_textures.find(id);
                if(old==m_textures.end()||old->second.generation!=parent
                    ||!old->second.SameImageLayout(texture)||old->second.pixels.size()!=expected)
                    throw std::invalid_argument("Stale or invalid texture image inheritance");
                texture.unchangedPixels=true;
            }
            else {
                const auto parent = reader.value<uint64_t>();
                const auto mip = reader.value<uint32_t>(), x = reader.value<uint32_t>();
                const auto y = reader.value<uint32_t>(), patchWidth = reader.value<uint32_t>();
                const auto patchHeight = reader.value<uint32_t>(), pitch = reader.value<uint32_t>();
                const auto count=reader.count();const auto* pixels=reader.take(count);
                auto old = m_textures.find(id);
                if (old == m_textures.end() || old->second.generation != parent
                    || !old->second.SameLayout(texture) || mip >= texture.mips
                    || !patchWidth || !patchHeight || old->second.pixels.size()!=expected)
                    throw std::invalid_argument("Stale or invalid texture patch");
                width = std::max(1u, texture.width >> mip); height = std::max(1u, texture.height >> mip);
                if (uint64_t(x) + patchWidth > width || uint64_t(y) + patchHeight > height
                    || uint64_t(pitch) < uint64_t(patchWidth) * 4
                    || uint64_t(pitch) * (patchHeight - 1) + uint64_t(patchWidth) * 4 != count)
                    throw std::invalid_argument("Invalid texture patch range");
                texture.rectangle=true;texture.patchMip=mip;texture.patchX=x;texture.patchY=y;
                texture.patchWidth=patchWidth;texture.patchHeight=patchHeight;
                size_t offset = 0;
                for (uint32_t i = 0; i < mip; ++i)
                    offset += size_t(std::max(1u, texture.width >> i)) * std::max(1u, texture.height >> i) * 4;
                texture.unchangedPixels=true;
                for(uint32_t row=0;row<patchHeight;++row)
                    if(std::memcmp(old->second.pixels.data()+offset+(size_t(y+row)*width+x)*4,
                        pixels+size_t(row)*pitch,size_t(patchWidth)*4)!=0) {
                        texture.unchangedPixels=false;break;
                    }
                if(!texture.unchangedPixels) {
                    texture.pixels=old->second.pixels;
                    for (uint32_t row = 0; row < patchHeight; ++row)
                        std::memcpy(texture.pixels.data() + offset + (size_t(y + row) * width + x) * 4,
                            pixels + size_t(row) * pitch, size_t(patchWidth) * 4);
                }
            }
            if (!texture.unchangedPixels && texture.pixels.size() != expected)
                throw std::invalid_argument("Invalid RGBA8 texture byte count");
            if (!packet.textures.emplace(id, std::move(texture)).second)
                throw std::invalid_argument("Duplicate texture identity");
        }
        for (uint32_t n = reader.count(); n; --n) {
            MaterialEntry entry;
            entry.material = m_factory.CreateMaterial();
            auto& material = *entry.material;
            material.name = Name(reader);
            const auto rgba = reader.value<uint32_t>();
            material.baseOrDiffuseColor = Color(rgba);
            material.opacity = float(rgba >> 24) / 255.f * reader.value<float>();
            material.roughness = reader.value<float>(); material.metalness = reader.value<float>();
            material.transmissionFactor = reader.value<float>(); material.alphaCutoff = reader.value<float>();
            const auto blend = reader.value<uint32_t>(), doubleSided = reader.value<uint32_t>();
            material.doubleSided = doubleSided != 0;
            // The private wire carries canonical linear radiance. Donut already
            // accepts float3 emission; do not quantize it back to a packed color.
            material.emissiveColor.x = reader.value<float>();
            material.emissiveColor.y = reader.value<float>();
            material.emissiveColor.z = reader.value<float>();
            material.emissiveIntensity = 1.f; entry.ior = reader.value<float>();
            const auto psdExclude = reader.value<int32_t>();
            entry.psdDominant = reader.value<int32_t>();
            if (psdExclude < 0 || psdExclude > 1 || entry.psdDominant < -1 || entry.psdDominant > 1
                || (psdExclude != 0 && entry.psdDominant != -1))
                throw std::invalid_argument("Invalid native material path decomposition");
            entry.psdExclude = psdExclude != 0;
            entry.psdBlockMotionVectors = reader.value<int32_t>();
            if (entry.psdBlockMotionVectors < 0 || entry.psdBlockMotionVectors > 3)
                throw std::invalid_argument("Invalid native material motion-vector blocking");
            for (unsigned component=0;component<3;++component)
                if (!std::isfinite(material.emissiveColor[component]) || material.emissiveColor[component]<0)
                    throw std::invalid_argument("Invalid linear emissive radiance");
            if (blend > 2 || doubleSided > 1 || entry.ior < 1.f)
                throw std::invalid_argument("Invalid material input");
            material.domain = donut::engine::MaterialDomain(blend + (material.transmissionFactor > 0 ? 3 : 0));
            for (auto& id : entry.textures) id = reader.value<int64_t>();
            const auto boundary = reader.value<uint32_t>();
            if (boundary > 1) throw std::invalid_argument("Invalid medium boundary flag");
            entry.boundary = boundary != 0;
            if (entry.boundary) {
                entry.absorption.x = reader.value<float>(); entry.absorption.y = reader.value<float>();
                entry.absorption.z = reader.value<float>(); entry.priority = reader.value<int32_t>();
                if (entry.absorption.x < 0 || entry.absorption.y < 0 || entry.absorption.z < 0
                    || entry.priority < 0 || entry.priority > PTMaterial::kMaterialMaxNestedPriority)
                    throw std::invalid_argument("Medium boundary exceeds original material limits");
            }
            const auto name = material.name;
            if (!packet.materials.emplace(name, std::move(entry)).second)
                throw std::invalid_argument("Duplicate material identity");
        }
        const auto meshCount=reader.count();
        for (uint32_t n = meshCount; n; --n) {
            auto mesh = m_factory.CreateMesh();
            mesh->name = Name(reader);
            auto inputs=std::make_shared<AuthoredMeshInputs>();
            auto& positions=inputs->positions;auto& normals=inputs->normals;
            auto& tangents=inputs->tangents;auto& uv=inputs->uv;
            const auto inherited=reader.value<uint32_t>();
            if(inherited>1)throw std::invalid_argument("Invalid geometry input transport");
            if(inherited){
                const auto source=Name(reader);
                std::shared_ptr<const AuthoredMeshInputs> baseInputs;
                std::shared_ptr<Mesh> sourceMesh;
                // Only preceding entries have decoded and validated owned
                // input. Never resolve a future name or trust packet metadata.
                if(packetInputSources)if(auto prior=packet.meshes.find(source);prior!=packet.meshes.end()){
                    sourceMesh=prior->second;baseInputs=PendingMeshInputs(*sourceMesh);
                }
                if(!sourceMesh)if(auto found=m_meshSources.find(source);found!=m_meshSources.end()){
                    baseInputs=found->second.inputs;sourceMesh=m_meshes.at(source);
                }
                if(!baseInputs||!sourceMesh)
                    throw std::invalid_argument(packetInputSources
                        ?"Inherited geometry input is neither preceding nor resident"
                        :"Inherited geometry input is not resident");
                const auto& base=*baseInputs;
                // Retain the original byte owner directly, never a chain of
                // replacement MeshInfo objects or borrowed JVM addresses.
                inputs->lease=base.lease;positions=base.positions;normals=base.normals;
                tangents=base.tangents;uv=base.uv;inputs->canonicalIndices=base.canonicalIndices;
                mesh->objectSpaceBounds=sourceMesh->objectSpaceBounds;
            }else{
                positions=reader.floatView();normals=reader.floatView();
                tangents=reader.floatView();uv=reader.floatView();
            }
            if (positions.empty() || positions.size() % 3 || normals.size() != positions.size()
                || (!tangents.empty() && tangents.size() != positions.size() / 3 * 4)
                || (!uv.empty() && uv.size() != positions.size() / 3 * 2))
                throw std::invalid_argument("Invalid mesh attributes");
            const auto vertices = positions.size() / 3;
            mesh->buffers = std::make_shared<donut::engine::BufferGroup>();
            if(!inherited){
                mesh->objectSpaceBounds = donut::math::box3::empty();
                for(size_t v=0;v<vertices;++v)
                    mesh->objectSpaceBounds|=donut::math::float3(positions[v*3],positions[v*3+1],positions[v*3+2]);
                const auto count=reader.count();
                inputs->canonicalIndices={reader.take(size_t(count)*sizeof(uint32_t)),count};
            }
            const auto indexCount=inputs->canonicalIndices.count;
            if(!indexCount||indexCount%3)throw std::invalid_argument("Invalid canonical triangle indices");
            const auto* canonical=inputs->canonicalIndices.data;
            auto indexAt=[&](uint32_t corner){uint32_t value;std::memcpy(&value,canonical+size_t(corner)*4,4);return value;};
            if(!inherited)for(uint32_t corner=0;corner<indexCount;++corner)
                if(indexAt(corner)>=vertices)throw std::invalid_argument("Triangle index exceeds vertices");
            if(!inherited){
                inputs->OwnGeometry(m_meshInputRetention);
                canonical=inputs->canonicalIndices.data;
            }
            const auto groups=reader.count();if(!groups)throw std::invalid_argument("Mesh requires geometry groups");
            auto& authored=static_cast<AuthoredMeshInfo&>(*mesh);
            std::vector<uint32_t> triangleGroups(indexCount/3,~0u);
            if(m_externalTextureSampling)authored.surfaceMaterials.reserve(groups);
            else inputs->groupedIndices.reserve(indexCount);
            inputs->indices.reserve(groups);mesh->geometries.reserve(groups);
            for (uint32_t group = 0; group < groups; ++group) {
                const auto materialName=Name(reader);const auto material=FindMaterial(materialName,packet).material;
                const auto triangles=reader.count();
                if(!triangles||triangles>indexCount/3)throw std::invalid_argument("Invalid surface triangle count");
                const size_t begin=inputs->groupedIndices.size();
                for(uint32_t triangle=0;triangle<triangles;++triangle){
                    const auto identity=reader.value<uint32_t>();
                    if(identity>=triangleGroups.size()||triangleGroups[identity]!=~0u)
                        throw std::invalid_argument("Invalid or repeated surface triangle identity");
                    triangleGroups[identity]=group;
                    if(!m_externalTextureSampling)for(uint32_t corner=0;corner<3;++corner)
                        inputs->groupedIndices.push_back(indexAt(identity*3+corner));
                }
                if(m_externalTextureSampling)authored.surfaceMaterials.push_back(materialName);
                else {
                    auto geometry=m_factory.CreateMeshGeometry();geometry->material=material;
                    geometry->indexOffsetInMesh=uint32_t(begin);geometry->numIndices=triangles*3;
                    geometry->numVertices=uint32_t(vertices);geometry->objectSpaceBounds=mesh->objectSpaceBounds;
                    inputs->indices.push_back({reinterpret_cast<const uint8_t*>(inputs->groupedIndices.data()+begin),geometry->numIndices});
                    mesh->geometries.push_back(std::move(geometry));
                }
            }
            if(std::find(triangleGroups.begin(),triangleGroups.end(),~0u)!=triangleGroups.end())
                throw std::invalid_argument("Surface mapping omits a triangle");
            if(m_externalTextureSampling){
                authored.triangleMaterials=std::move(triangleGroups);
                auto geometry=m_factory.CreateMeshGeometry();geometry->material=m_materials.at(SurfaceCarrierName).material;
                geometry->numIndices=indexCount;geometry->numVertices=uint32_t(vertices);geometry->objectSpaceBounds=mesh->objectSpaceBounds;
                inputs->indices.push_back({canonical,indexCount});mesh->geometries.push_back(std::move(geometry));
            }
            inputs->indexCount=indexCount;
            mesh->totalVertices = uint32_t(vertices); mesh->totalIndices = inputs->indexCount;
            static_cast<AuthoredMeshInfo&>(*mesh).pendingInputs=std::move(inputs);
            const auto name = mesh->name;
            if (!packet.meshes.emplace(name, std::move(mesh)).second)
                throw std::invalid_argument("Duplicate mesh identity");
        }
        std::unordered_set<std::string> removals, nodes;
        for (uint32_t n = reader.count(); n; --n) {
            auto name = Name(reader);
            if (!removals.insert(name).second) throw std::invalid_argument("Duplicate node removal");
            packet.removeNodes.push_back(std::move(name));
        }
        for (uint32_t n = reader.count(); n; --n) {
            NodeInput input;
            input.name = Name(reader); input.mesh = Name(reader);
            if (!nodes.insert(input.name).second || removals.count(input.name))
                throw std::invalid_argument("Duplicate or conflicting instance identity");
            const auto values = reader.floats();
            if (values.size() != 12) throw std::invalid_argument("Transform requires twelve floats");
            const double determinant = double(values[0]) * (double(values[5]) * values[10] - double(values[6]) * values[9])
                - double(values[1]) * (double(values[4]) * values[10] - double(values[6]) * values[8])
                + double(values[2]) * (double(values[4]) * values[9] - double(values[5]) * values[8]);
            if (std::abs(determinant) < 1.e-8)
                throw std::invalid_argument("Instance transform must be invertible");
            donut::math::daffine3 transform;
            for (int row = 0; row < 3; ++row)
                for (int col = 0; col < 3; ++col) transform.m_linear[col][row] = double(values[row * 4 + col]);
            transform.m_translation = donut::math::double3(values[3], values[7], values[11]);
            input.pose=AffinePose::Decode(transform);
            packet.nodes.push_back(std::move(input));
        }
        for (uint32_t n = reader.count(); n; --n) packet.coverage.push_back(Name(reader));
        std::unordered_set<int64_t> removedTextures;
        for (uint32_t n = reader.count(); n; --n) {
            const auto id = reader.value<int64_t>();
            if (id < 0 || packet.textures.count(id) || !removedTextures.insert(id).second)
                throw std::invalid_argument("Duplicate or conflicting texture removal");
            packet.removeTextures.push_back(id);
        }
        std::unordered_set<std::string> removedMaterials;
        for(uint32_t n=reader.count();n;--n) {
            auto name=Name(reader);
            if(packet.materials.contains(name)||!removedMaterials.insert(name).second)
                throw std::invalid_argument("Duplicate or conflicting material removal");
            packet.removeMaterials.push_back(std::move(name));
        }
        if(packet.retainedDefinitions){
            std::unordered_set<std::string> removedMeshes;
            for(uint32_t n=reader.count();n;--n){
                auto name=Name(reader);
                if(packet.meshes.contains(name)||!removedMeshes.insert(name).second)
                    throw std::invalid_argument("Duplicate or conflicting mesh removal");
                packet.removeMeshes.push_back(std::move(name));
            }
        }
        reader.finish();
        return packet;
    }

    void ValidateReferences(const Packet& packet) {
        const std::unordered_set<int64_t> removed(packet.removeTextures.begin(), packet.removeTextures.end());
        const std::unordered_set<std::string> removedMaterials(packet.removeMaterials.begin(),packet.removeMaterials.end());
        const std::unordered_set<std::string> removedMeshes(packet.removeMeshes.begin(),packet.removeMeshes.end());
        std::unordered_set<std::string> replacedNodes(packet.removeNodes.begin(),packet.removeNodes.end()),publishedMeshes;
        if(!removedMaterials.empty()||!removedMeshes.empty())for(const auto& node:packet.nodes){replacedNodes.insert(node.name);publishedMeshes.insert(node.mesh);}
        for(const auto& name:packet.removeMeshes)if(auto users=m_meshNodes.find(name);users!=m_meshNodes.end())
            for(const auto& node:users->second)if(!replacedNodes.contains(node))
                throw std::invalid_argument("Cannot remove a mesh referenced by a live instance");
        auto validateMaterial = [&](const MaterialEntry& entry) {
            for (auto id : entry.textures) {
                if (id < -1) throw std::invalid_argument("Invalid texture reference sentinel");
                if (id >= 0) {
                    if (removed.count(id)) throw std::invalid_argument("Cannot remove a referenced texture");
                    (void)FindTexture(id, packet);
                }
            }
        };
        for (const auto& [name, entry] : packet.materials) validateMaterial(entry);
        for(auto id:packet.removeTextures)if(auto users=m_textureMaterials.find(id);users!=m_textureMaterials.end())
            for(const auto& name:users->second)
                if(!removedMaterials.contains(name)&&!packet.materials.contains(name))
                    throw std::invalid_argument("Cannot remove a texture referenced by a declared material");
        auto validateMesh = [&](const std::shared_ptr<Mesh>& mesh) {
            const auto& authored=static_cast<const AuthoredMeshInfo&>(*mesh);
            std::vector<std::string> names=authored.surfaceMaterials;
            if(names.empty())for(const auto& geometry:mesh->geometries)names.push_back(geometry->material->name);
            for (const auto& name:names) {
                if(removedMaterials.contains(name))
                    throw std::invalid_argument("Cannot remove a referenced material");
                const auto& entry = FindMaterial(name, packet);
                const auto& pending=PendingMeshInputs(*mesh);
                if (entry.textures[1] >= 0 && (!pending||pending->tangents.empty())&&mesh->buffers->tangentData.empty()
                    && !mesh->buffers->hasAttribute(donut::engine::VertexAttribute::Tangent))
                    throw std::invalid_argument("Normal-mapped meshes require explicit tangents on the original scene path");
            }
        };
        for (const auto& [name, mesh] : packet.meshes) validateMesh(mesh);
        for(const auto& name:packet.removeMaterials)if(auto users=m_materialMeshes.find(name);users!=m_materialMeshes.end())
            for(const auto& mesh:users->second) {
                if(removedMeshes.contains(mesh))continue;
                if(packet.meshes.contains(mesh))continue;
                if(packet.retainedDefinitions)throw std::invalid_argument("Cannot remove a material referenced by a declared mesh");
                if(publishedMeshes.contains(mesh))throw std::invalid_argument("Cannot remove a referenced material");
                if(auto instances=m_meshNodes.find(mesh);instances!=m_meshNodes.end())
                    for(const auto& node:instances->second)if(!replacedNodes.contains(node))
                        throw std::invalid_argument("Cannot remove a referenced material");
            }
        for (const auto& [name, entry] : packet.materials) {
            auto users = m_materialMeshes.find(name);
            if (users == m_materialMeshes.end()) continue;
            // Existing meshes were checked when their normal map was enabled.
            // Pixel, sampler, scalar or normal-texture identity edits cannot add
            // a missing vertex requirement; changed meshes are checked above.
            // Keep all material texture-ID validation before this fast path.
            const auto previous=m_materials.find(name);
            if(entry.textures[1]<0 || (previous!=m_materials.end()&&previous->second.textures[1]>=0)) {
                m_referenceWork[1]+=users->second.size();
                continue;
            }
            for (const auto& mesh : users->second)
                if (!packet.meshes.count(mesh)) {
                    ++m_referenceWork[0];
                    validateMesh(m_meshes.at(mesh));
                }
        }
        for (const auto& input : packet.nodes)
            if (removedMeshes.contains(input.mesh)||(!packet.meshes.count(input.mesh) && !m_meshes.count(input.mesh)))
                throw std::invalid_argument("Unknown instance mesh: " + input.mesh);
        for (const auto& name : packet.coverage) {
            if(removedMaterials.contains(name))throw std::invalid_argument("Cannot update coverage of a removed material");
            (void)FindMaterial(name, packet);
        }
    }

    AuthoredSceneTypeFactory m_factory;
    std::shared_ptr<MeshInputRetention> m_meshInputRetention=std::make_shared<MeshInputRetention>();
    bool m_externalTextureSampling = false;
    static constexpr const char* SurfaceCarrierName="~finite-signal-traversal";
    std::vector<std::string> m_surfaceNames;
    std::vector<uint32_t> m_surfaceFree;
    std::unordered_set<uint32_t> m_surfaceDirty;
    std::array<uint64_t,7> m_textureWork{};
    std::array<uint64_t,2> m_referenceWork{};
    std::array<uint64_t,3> m_poseWork{};
    nvrhi::IDevice* m_textureDevice=nullptr;
    std::shared_ptr<donut::engine::DescriptorTableManager> m_textureDescriptors;
    std::weak_ptr<donut::engine::SceneGraph> m_graph;
    std::unordered_map<int64_t, Texture> m_textures;
    std::unordered_map<std::string, MaterialEntry> m_materials;
    std::unordered_map<std::string, std::shared_ptr<Mesh>> m_meshes;
    std::unordered_map<std::string, Instance> m_nodes;
    // External IDs have no upstream lookup/replace API. These indexes route only
    // changed identities; they do not mirror geometry, AS or GPU resource state.
    std::unordered_map<std::string, std::unordered_set<std::string>> m_meshNodes;
    std::unordered_map<std::string, std::unordered_set<std::string>> m_materialMeshes;
    std::unordered_map<int64_t, std::unordered_set<std::string>> m_textureMaterials;
    std::unordered_set<std::string> m_pendingMaterials;
    std::unordered_set<std::string> m_pendingIndependentMaterials;
    std::unordered_set<std::string> m_pendingTextureMaterials;
    std::unordered_map<std::string,NodeInput> m_pendingTransforms;
};

} // namespace rtr::upstream
