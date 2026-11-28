#pragma once
#include "rtxpt_scene_delta.h"
#include <array>
#include <chrono>
#include <cstdio>
#include <memory>
#include <unordered_map>
#include <unordered_set>
#include <cstdlib>
#include "rtxpt_native_application_ranges.h"
#include "rtxpt_attribute_ranges.h"
#include "rtxpt_attribute_capacity.h"
#include "rtxpt_receiver_arena.h"
#include "rtxpt_receiver_upload.h"
#include "rtxpt_allocation_failure.h"
#include "rtxpt_host_response_lookup.h"
#include "rtxpt_gpu_profile.h"
#include "rtxpt_native_rr.h"
#include "rtxpt_resource_reclaimer.h"
#include "rtxpt_directional_flux.h"
#include "rtxpt_dielectric_probe.h"

// Independent execution resources. The caller supplies the existing scene/AS,
// descriptor table and command list; this object never owns a second RT scene.
class RtrNativeLightingPass {
public:
    static constexpr uint32_t AbiVersion=1;
    static constexpr unsigned DielectricSignal=6;
    static constexpr uint32_t DielectricMask=1u<<DielectricSignal,LegacySignalMask=DielectricMask-1;
    static constexpr uint32_t RefinementMask=DielectricMask<<1;
    static constexpr uint32_t DielectricUav=15,DielectricSkySrv=15,DielectricSkyFlag=1u<<7;
    static constexpr uint32_t RefinementUav=17,CoarseDielectricSrv=17,RefinementJobsUav=18,RefinementJobsSrv=16;
    struct alignas(16) Constants {std::array<float,96> words{};std::array<uint32_t,4> cameraSlots{},opticalViewport{};};
    std::optional<std::vector<std::string>> cameraVolumes;
    struct InstanceAttributes {float4 rolesVisibility={23,1,0,0};float4 baseline={1,1,0,0};std::array<float4,3> explicitPrevious{};float4 surfaceVisibility={1,0,0,0};};
    struct GeometryAttributes {uint32_t offset=0,triangles=0;std::array<uint32_t,4> texturePolicies{};float maskedOpacity=1.f;uint32_t surfaceSelector=0;};
    using VertexAttributes=rtr::upstream::ReceiverVertex;
    static_assert(sizeof(VertexAttributes)==16);
    static_assert(sizeof(Constants)==416&&sizeof(InstanceAttributes)==96&&sizeof(GeometryAttributes)==32);
    std::function<std::array<uint32_t,4>(const std::string&)> texturePolicies;
    std::function<rtr::upstream::MeshSurfaceSlots(const std::string&)> surfaceSlots;
    std::function<void(std::vector<rtr::upstream::SurfaceMaterialRow>&,std::vector<uint32_t>&)> surfaceUpdates;
    std::vector<rtr::upstream::SurfaceMaterialRow> surfaceRows;
    std::vector<uint32_t> surfaceDirty;
    std::vector<uint8_t> surfaceCoverage;
    size_t surfaceCandidateCount=0;
    nvrhi::BufferHandle surfaceMaterials;
    nvrhi::BufferHandle surfaceIndices;
    std::unordered_set<std::string> dirtySurfaces;
    std::vector<uint32_t> surfaceUploadScratch;
    struct SurfaceUploadRun {uint32_t offset;const std::vector<uint32_t>* slots;};
    std::vector<SurfaceUploadRun> surfaceUploadRuns;
    static constexpr uint32_t MixedSurfaceBit=0x80000000u;
    rtr::AttributeRanges surfaceRanges;
    std::array<nvrhi::SamplerHandle,8> textureSamplers;
    Constants constants;
    RtrSceneDelta sceneDelta;
    bool active=false,metadataDirty=true;
    bool metadataInitialized=false;
    std::unordered_set<std::string> specialInstances;
    bool instanceCandidates=false,maskedScalarCandidates=false;
    bool diagnosticDecomposition=false;
    RtrNativeRR reconstruction;
    nvrhi::BindingLayoutHandle deepLayout;
    nvrhi::ComputePipelineHandle deepPipeline;
    nvrhi::BufferHandle deepReceivers;
    std::array<nvrhi::BufferHandle,3> deepConstants;
    RtrCachedBindings dispatchBindings,encodeBindings,penumbraDispatchBindings,dielectricDispatchBindings;
    RtrCachedBindings dielectricClassifyBindings,dielectricRefineBindings;
    std::array<RtrCachedBindings,2> sourceHistoryBindings;
    nvrhi::BindingLayoutHandle sourceHistoryLayout;
    std::array<nvrhi::TextureHandle,2> sourceHistory;
    unsigned sourceHistoryRead=0;
    bool sourceHistoryActive=false,sourceHistoryClear=false;
    std::array<RtrCachedBindings,3> phaseBindings;
    uint32_t width=0,height=0,mask=0;
    uint64_t executedFrames=0,beautyFrames=0,publication=0,metadataUploads=0;
    // The built-in evaluator is deterministic and independent of frame time.
    // Only managed, non-skinned scenes opt in with an authoritative generation.
    bool retainSignalsAllowed=false,retainedSignals=false,retainedReconstruction=false;
    uint64_t inputGeneration=0,retainedInputGeneration=0,retainedSourceGeneration=0,retainedMetadata=0;
    uint64_t geometryGeneration=0,lastGeometryGeneration=0;
    uint64_t analyticGeneration=0,retainedAnalyticGeneration=0;
    Constants retainedConstants;
    int64_t timeNanos=0,deltaNanos=0;
    bool workStatisticsEnabled=false,workStatisticsRequested=false,diagnosticsEnabled=true;
    size_t specialRoleCount=0;
    uint64_t sequence=0,sceneRevision=0;
    struct WorkReadback {nvrhi::BufferHandle staging;nvrhi::EventQueryHandle done;uint64_t sequence=0,revision=0;bool pending=false;};
    std::array<WorkReadback,3> workReadbacks;
    std::array<uint64_t,15> latestWork{};
    uint64_t workCopies=0,workQueries=0,workPolls=0;
    void PollWork(nvrhi::IDevice* device){
        for(auto& slot:workReadbacks)if(slot.pending&&((++workPolls),device->pollEventQuery(slot.done))){
            auto* words=static_cast<const uint32_t*>(device->mapBuffer(slot.staging,nvrhi::CpuAccessMode::Read));
            if(words){if(!latestWork[0]||slot.sequence>=latestWork[1]){latestWork={1,slot.sequence,slot.revision,words[0],words[1],words[2],words[3],words[4],words[5],words[6],words[7],words[8],words[9],words[10],words[11]};}device->unmapBuffer(slot.staging);}
            slot.pending=false;
        }
    }
    void RecordWork(nvrhi::IDevice* device){
        PollWork(device);if(!workStatisticsEnabled)return;
        for(auto& slot:workReadbacks)if(!slot.pending){
            if(!slot.staging){nvrhi::BufferDesc desc;desc.byteSize=48;desc.cpuAccess=nvrhi::CpuAccessMode::Read;desc.initialState=nvrhi::ResourceStates::CopyDest;desc.debugName="Optional native lighting statistics";
                slot.staging=device->createBuffer(desc);slot.done=device->createEventQuery();}
            auto commands=device->createCommandList();commands->open();commands->copyBuffer(slot.staging,0,work,0,48);commands->close();device->executeCommandList(commands);
            device->setEventQuery(slot.done,nvrhi::CommandQueue::Graphics);++workCopies;++workQueries;slot.sequence=sequence;slot.revision=sceneRevision;slot.pending=true;break;
        }
    }
    nvrhi::TextureHandle direct,indirect,geometry,identity,directionalSpecular,dummyColor,dummyIdentity;
    nvrhi::TextureHandle sunPenumbra,sunEvidence;
    nvrhi::BindingLayoutHandle penumbraLayout;
    nvrhi::ComputePipelineHandle penumbraPipeline;
    nvrhi::TextureHandle dielectricRadiance;
    nvrhi::TextureHandle dielectricRefinement;
    nvrhi::BufferHandle dielectricRefinementJobs;
    uint32_t refinementCapacity=0;
    nvrhi::TextureHandle dielectricSky;
    std::vector<float> pendingDielectricSky;
    nvrhi::BindingLayoutHandle dielectricLayout;
    nvrhi::ComputePipelineHandle dielectricPipeline;
    nvrhi::BindingLayoutHandle dielectricClassifyLayout,dielectricRefineLayout;
    nvrhi::ComputePipelineHandle dielectricClassifyPipeline,dielectricRefinePipeline;
    RtrDielectricProbe dielectricProbe;
    RtrDirectionalFlux directionalFlux;
    bool compactEncoding=false;
    bool compactResponseEncoding=false;
    nvrhi::TextureHandle compactDirect,compactIndirect,compactDummy;
    nvrhi::BufferHandle compactConstants;
    nvrhi::BindingLayoutHandle compactLayout;
    nvrhi::ComputePipelineHandle compactPipeline;
    nvrhi::BufferHandle constantBuffer,instances,baselineCoordinates,geometryBaselines,lookup,application,work;
    nvrhi::BindingLayoutHandle layout;
    nvrhi::ComputePipelineHandle pipeline;
    nvrhi::ComputePipelineHandle receiverSignalsPipeline;
    nvrhi::ComputePipelineHandle singleGroupPipeline;
    nvrhi::ComputePipelineHandle sparseOnlyPipeline,sparseOnlySingleGroupPipeline,smallGroupPipeline,sparseOnlySmallGroupPipeline;
    nvrhi::ComputePipelineHandle pairGroupPipeline,sparseOnlyPairGroupPipeline;
    std::array<nvrhi::ComputePipelineHandle,8> uninstrumentedPipelines,directPipelines,directChannelPipelines;
    bool UseDirectPipeline(bool maximum) const{
        return !reconstruction.requested&&!maximum&&(!(mask&2u)||constants.words[43]==0);
    }
    bool UseReceiverSignalsPipeline() const{
        return !(mask&1u)&&UseDirectPipeline(false)&&!workStatisticsEnabled&&!diagnosticDecomposition;
    }
    bool RunMainLighting() const{return (mask&LegacySignalMask)||reconstruction.requested||workStatisticsEnabled||diagnosticDecomposition;}
    std::array<nvrhi::ComputePipelineHandle,16> maxPipelines;
    std::array<nvrhi::ComputePipelineHandle,8> countedMaxPipelines;
    std::array<nvrhi::ComputePipelineHandle,16> channelMaxPipelines;
    std::unordered_map<std::string,InstanceAttributes> instanceAttributes;
    // The lean managed TLAS exposes traversal roles as hardware masks. Keep
    // only exceptions; ordinary instances are not dielectric boundaries.
    static constexpr uint32_t DefaultTraversalRoles=7u, MediumTraversalRole=32u, AllTraversalRoles=DefaultTraversalRoles|MediumTraversalRole;
    std::unordered_map<std::string,uint32_t> traversalRoles;
    std::unordered_set<std::string> traversalMaskChanges;
    size_t mediumTraversalCount=0;
    bool roleMaskTraversal=false;
    std::unordered_map<std::string,std::vector<float>> meshBaselines; // two floats per corner
    std::unordered_map<std::string,std::vector<uint32_t>> meshColors;
    std::unordered_map<std::string,rtr::upstream::ReceiverGeometry> meshReceivers;
    bool SameBaseline(const std::string& name,const std::vector<float>& values)const{
        if(const auto old=meshBaselines.find(name);old!=meshBaselines.end())
            return old->second.size()==values.size()&&(values.empty()||std::memcmp(old->second.data(),values.data(),values.size()*sizeof(float))==0);
        const auto found=meshReceivers.find(name);
        if(found==meshReceivers.end()||values.size()!=found->second.indices.size()*2)return false;
        const auto& receiver=found->second;
        for(size_t i=0;i<receiver.indices.size();++i)for(unsigned axis=0;axis<2;++axis){
            const float previous=receiver.baseline.empty()?0:receiver.baseline[size_t(receiver.indices[i])*2+axis];
            if(std::memcmp(&previous,&values[i*2+axis],sizeof(float))!=0)return false;
        }
        return true;
    }
    bool SameColors(const std::string& name,const std::vector<uint32_t>& values)const{
        if(const auto old=meshColors.find(name);old!=meshColors.end())return old->second==values;
        const auto found=meshReceivers.find(name);
        if(found==meshReceivers.end()||values.size()!=found->second.indices.size())return false;
        const auto& receiver=found->second;
        for(size_t i=0;i<values.size();++i)
            if(values[i]!=(receiver.colors.empty()?0xffffffffu:receiver.colors[receiver.indices[i]]))return false;
        return true;
    }
    void PublishReceivers(std::vector<rtr::upstream::ReceiverGeometry>& values){
        for(auto& value:values){
            // A geometry/identity packet republishes canonical receiver input,
            // but it need not rewrite unchanged corner attributes. Compare the
            // actual effective channels, including preceding public overrides.
            auto previous=meshReceivers.find(value.name);
            auto baseline=meshBaselines.find(value.name);auto colors=meshColors.find(value.name);
            const auto exact=[](const auto& a,const auto& b){
                return a.size()==b.size()&&(a.empty()||std::memcmp(a.data(),b.data(),a.size()*sizeof(a[0]))==0);
            };
            bool baselineChanged=true,colorsChanged=true;
            if(previous!=meshReceivers.end()&&previous->second.indices.size()==value.indices.size()){
                const auto& old=previous->second;
                baselineChanged=baseline!=meshBaselines.end()||!exact(old.baseline,value.baseline);
                colorsChanged=colors!=meshColors.end()||old.colors!=value.colors;
                const bool sameMap=old.indices==value.indices;
                baselineChanged|=!sameMap&&(!old.baseline.empty()||!value.baseline.empty());
                colorsChanged|=!sameMap&&(!old.colors.empty()||!value.colors.empty());
                if(baselineChanged){
                    baselineChanged=false;
                    for(size_t i=0;i<value.indices.size()&&!baselineChanged;++i)for(unsigned axis=0;axis<2;++axis){
                        const float before=baseline!=meshBaselines.end()?baseline->second.at(i*2+axis)
                            :old.baseline.empty()?0:old.baseline[size_t(old.indices[i])*2+axis];
                        const float after=value.baseline.empty()?0:value.baseline[size_t(value.indices[i])*2+axis];
                        if(std::memcmp(&before,&after,sizeof(float))!=0){baselineChanged=true;break;}
                    }
                }
                if(colorsChanged){
                    colorsChanged=false;
                    for(size_t i=0;i<value.indices.size();++i){
                        const uint32_t before=colors!=meshColors.end()?colors->second.at(i)
                            :old.colors.empty()?0xffffffffu:old.colors[old.indices[i]];
                        const uint32_t after=value.colors.empty()?0xffffffffu:value.colors[value.indices[i]];
                        if(before!=after){colorsChanged=true;break;}
                    }
                }
            }else{
                // A public clear/reset may leave only expanded overrides. They
                // remain the actual previous GPU inputs until this publication.
                if(baseline!=meshBaselines.end()&&baseline->second.size()==value.indices.size()*2){
                    baselineChanged=false;
                    for(size_t i=0;i<value.indices.size()&&!baselineChanged;++i)for(unsigned axis=0;axis<2;++axis){
                        const float after=value.baseline.empty()?0:value.baseline[size_t(value.indices[i])*2+axis];
                        if(std::memcmp(&baseline->second[i*2+axis],&after,sizeof(float))!=0){baselineChanged=true;break;}
                    }
                }
                if(colors!=meshColors.end()&&colors->second.size()==value.indices.size()){
                    colorsChanged=false;
                    for(size_t i=0;i<value.indices.size();++i)
                        if(colors->second[i]!=(value.colors.empty()?0xffffffffu:value.colors[value.indices[i]])){colorsChanged=true;break;}
                }
            }
            meshBaselines.erase(value.name);meshColors.erase(value.name);
            if(value.alpha)alphaMeshes.insert(value.name);else alphaMeshes.erase(value.name);
            if(baselineChanged)dirtyBaselines.insert(value.name);
            if(colorsChanged)dirtyColors.insert(value.name);
            auto name=std::move(value.name);meshReceivers.insert_or_assign(std::move(name),std::move(value));
        }
    }
    std::unordered_set<std::string> alphaMeshes;
    std::unordered_map<std::string,uint32_t> instanceSlots;
    std::unordered_map<std::string,GeometryAttributes> geometrySlots;
    struct GeometryIndex {uint32_t index;uint64_t epoch;GeometryAttributes* attributes=nullptr;};
    std::unordered_map<std::string,GeometryIndex> geometryIndices;
    struct GeometryMetadata {
        std::string name;
        std::string materialName;
        std::weak_ptr<donut::engine::Material> material;
        std::array<uint32_t,4> texturePolicies{};
        bool policiesInitialized=false;
        // unordered_map rehash preserves references to its nodes. Retirement
        // removes the corresponding layout before it can be visited again.
        GeometryIndex* mapping=nullptr;
        uint64_t collected=0;
    };
    struct MeshMetadata {
        std::weak_ptr<donut::engine::MeshInfo> owner;
        std::vector<GeometryMetadata> groups;
        bool seenInSnapshot=false;
    };
    // Reindexing preserves the native mesh owner even when its dense slot moves.
    // The weak owner guards address reuse without keeping the mesh/BLAS alive.
    std::unordered_map<const donut::engine::MeshInfo*,MeshMetadata> metadataLayouts;
    struct MaterialGeometryUser {std::weak_ptr<donut::engine::MeshInfo> owner;uint32_t group=0;};
    struct MaterialMetadata {
        std::weak_ptr<donut::engine::Material> owner;
        std::array<uint32_t,4> texturePolicies{};
        std::unordered_map<std::string,MaterialGeometryUser> users;
        bool policiesInitialized=false;
    };
    // This is an adapter-metadata dependency index, not a second scene/resource
    // owner. Retirement removes keys and every resource reference stays weak.
    std::unordered_map<std::string,MaterialMetadata> materialGeometryUsers;
    std::unordered_map<std::string,uint8_t> materialMetadataChanges; // sampler/reference=1, opacity=2
    std::unordered_set<std::string> maskedScalarGeometries;
    // Keep only changed geometry owners for this frame; retaining vector
    // capacity avoids a temporary mesh hash table rebuilt after every remap.
    struct GeometryMetadataChange {
        std::shared_ptr<donut::engine::MeshInfo> mesh;
        uint32_t group;
        // Layouts are collected once before upload and remain stable until
        // this owner-thread journal is consumed. No second name lookup needed.
        GeometryMetadata* metadata;
        bool indexOnly=false;
    };
    std::vector<GeometryMetadataChange> metadataGeometryChanges;
    uint64_t metadataScan=0;
    std::array<uint64_t,4> metadataWork{}; // names built, policy reads, geometry checks, policies reused
    void InvalidateMaterialMetadata(const std::unordered_set<std::string>& policies,const std::unordered_set<std::string>& opacity){
        for(const auto& name:policies){
            materialMetadataChanges[name]|=1;
            if(auto found=materialGeometryUsers.find(name);found!=materialGeometryUsers.end())found->second.policiesInitialized=false;
        }
        for(const auto& name:opacity)materialMetadataChanges[name]|=2;
    }
    void ForgetMetadataGroup(const GeometryMetadata& group){
        if(auto found=materialGeometryUsers.find(group.materialName);found!=materialGeometryUsers.end()){
            found->second.users.erase(group.name);if(found->second.users.empty())materialGeometryUsers.erase(found);
        }
        maskedScalarGeometries.erase(group.name);maskedScalarCandidates=!maskedScalarGeometries.empty();
    }
    void ForgetMetadataLayout(const MeshMetadata& layout){for(const auto& group:layout.groups)ForgetMetadataGroup(group);}
    void ForgetMeshMetadata(const std::shared_ptr<donut::engine::MeshInfo>& mesh){
        const auto found=metadataLayouts.find(mesh.get());
        if(found==metadataLayouts.end()||found->second.owner.owner_before(mesh)||mesh.owner_before(found->second.owner))return;
        ForgetMetadataLayout(found->second);metadataLayouts.erase(found);
    }
    MeshMetadata& MetadataLayout(const std::shared_ptr<donut::engine::MeshInfo>& mesh){
        auto& result=metadataLayouts[mesh.get()];
        if(result.owner.owner_before(mesh)||mesh.owner_before(result.owner)){
            ForgetMetadataLayout(result);
            result.owner=mesh;result.groups.clear();
        }
        const size_t before=result.groups.size();
        for(size_t group=mesh->geometries.size();group<before;++group)ForgetMetadataGroup(result.groups[group]);
        result.groups.resize(mesh->geometries.size());
        for(size_t group=before;group<result.groups.size();++group){
            result.groups[group].name=mesh->name+":"+std::to_string(group);++metadataWork[0];
        }
        return result;
    }
    void RefreshMetadataPolicy(GeometryMetadata& cached,const std::shared_ptr<donut::engine::Material>& material,
            const std::shared_ptr<donut::engine::MeshInfo>& mesh,uint32_t group){
        static const std::string emptyName;
        const auto& name=material?material->name:emptyName;
        const bool ownerChanged=cached.material.owner_before(material)||material.owner_before(cached.material);
        const bool nameChanged=cached.materialName!=name;
        if(cached.policiesInitialized&&!nameChanged&&!ownerChanged
            &&(materialMetadataChanges.empty()||!materialMetadataChanges.contains(name))){
            ++metadataWork[3];return;
        }
        if(nameChanged){
            ForgetMetadataGroup(cached);cached.materialName=name;
        }
        if(material){
            auto& dependency=materialGeometryUsers[name];
            if(!cached.policiesInitialized||nameChanged||ownerChanged)
                dependency.users.insert_or_assign(cached.name,MaterialGeometryUser{mesh,group});
            if(!dependency.policiesInitialized||dependency.owner.owner_before(material)||material.owner_before(dependency.owner)){
                dependency.texturePolicies=texturePolicies?texturePolicies(name):std::array<uint32_t,4>{};
                dependency.owner=material;dependency.policiesInitialized=true;++metadataWork[1];
            }else ++metadataWork[3];
            cached.texturePolicies=dependency.texturePolicies;
        }else{cached.texturePolicies={};++metadataWork[3];}
        cached.material=material;cached.policiesInitialized=true;
    }
    static float MetadataOpacity(const std::shared_ptr<donut::engine::Material>& material){
        return material&&(material->domain==donut::engine::MaterialDomain::AlphaTested
            ||material->domain==donut::engine::MaterialDomain::TransmissiveAlphaTested)?material->opacity:1.f;
    }
    void TrackMaskedScalar(const std::string& name,float opacity){
        if(opacity!=1.f)maskedScalarGeometries.insert(name);else maskedScalarGeometries.erase(name);
        maskedScalarCandidates=!maskedScalarGeometries.empty();
    }
    uint64_t geometryIndexEpoch=0;
    struct GeometryEntry {std::string name;uint32_t index=0,corners=0;bool indexChanged=false;GeometryIndex* slot=nullptr;std::array<uint32_t,4> texturePolicies{};float maskedOpacity=1.f;rtr::upstream::MeshSurfaceSlots surfaces;};
    std::vector<GeometryEntry> geometryEntriesScratch;
    std::unordered_set<std::string> dirtyInstances,dirtyBaselines,dirtyColors;
    std::vector<InstanceAttributes> instanceUploadScratch;
    std::vector<GeometryAttributes> geometryUploadScratch;
    rtr::ReceiverUploadStorage<VertexAttributes> vertexUploadScratch;
    struct VertexUploadRun {const std::string* name;GeometryAttributes attributes;};
    std::vector<VertexUploadRun> vertexUploadRuns;
    std::vector<uint32_t> instanceUploadIndices,geometryUploadIndices;
    std::vector<uint64_t> instanceUploadMarks;
    uint64_t instanceUploadEpoch=0;
    std::vector<const std::string*> instanceNamesScratch;
    template<class T> static void UploadAttributeRuns(nvrhi::ICommandList* command,nvrhi::IBuffer* buffer,
            const std::vector<T>& values,std::vector<uint32_t>& indices){
        std::sort(indices.begin(),indices.end());
        for(size_t begin=0;begin<indices.size();){
            size_t end=begin+1;while(end<indices.size()&&indices[end]==indices[end-1]+1)++end;
            const size_t bytes=(end-begin)*sizeof(T);
            RtrDiagnostics::Scope upload(RtrDiagnostics::BufferUpload,end-begin,bytes,1,0,0,0,RtrDiagnostics::Topology,bytes);
            command->writeBuffer(buffer,values.data()+indices[begin],bytes,size_t(indices[begin])*sizeof(T));
            begin=end;
        }
    }
    rtr::AttributeRanges attributeRanges;
    RtrReceiverArena receiverArena;
    uint64_t attributeMigrationBytes=0,attributeTableVisits=0;
    void FreeAttributeRangeAt(uint32_t offset,uint32_t count){attributeRanges.Release(offset,count);}
    uint32_t AllocateAttributeRange(uint32_t count){return attributeRanges.Allocate(count);}
    void EnsureAttributeBuffer(nvrhi::IDevice* device,nvrhi::ICommandList* command,nvrhi::BufferHandle& buffer,size_t bytes,size_t stride,const char* name){
        bytes=std::max(bytes,stride);
        if(std::addressof(buffer)==std::addressof(baselineCoordinates)&&receiverArena.Ensure(device,buffer,bytes,stride,name))return;
        if(buffer&&buffer->getDesc().byteSize>=bytes)return;
        const size_t oldCapacity=buffer?buffer->getDesc().byteSize:0;
        const size_t capacity=rtr::AttributeBufferCapacity(bytes,oldCapacity,stride);
        nvrhi::BufferHandle next;
        try{next=Buffer(device,capacity,stride,name);}
        catch(const std::runtime_error& failure){
            uint64_t retiredBytes=0;for(const auto& old:retired)retiredBytes+=old.bytes;
            throw std::runtime_error(std::string(failure.what())+" liveBytes="+std::to_string(bytes)+" oldCapacityBytes="+std::to_string(oldCapacity)
                +" retiredBytes="+std::to_string(retiredBytes)+" reclaimerPendingBytes="+std::to_string(reclaimer->pendingBytes.load()));
        }
        if(buffer){auto copy=buffer->getDesc().byteSize;command->copyBuffer(next,0,buffer,0,copy);attributeMigrationBytes+=copy;Retire(device,{buffer});}
        buffer=next;
    }
    // Cumulative operational counts, independent of optional diagnostic recording.
    std::array<uint64_t,5> attributeWork{}; // layout builds, corners packed, instance writes, attribute bytes, local geometry writes
    void PackVertices(const std::string& name,size_t count,VertexAttributes* target){
        const auto baseline=meshBaselines.find(name);const auto colors=meshColors.find(name);
        if(baseline!=meshBaselines.end()&&baseline->second.size()!=count*2)throw std::invalid_argument("Baseline update changes geometry layout");
        if(colors!=meshColors.end()&&colors->second.size()!=count)throw std::invalid_argument("Vertex color update changes geometry layout");
        RtrInputSpan<uint32_t> indices,receiverColors;RtrInputSpan<float> receiverBaseline;
        if(auto receiver=meshReceivers.find(name);receiver!=meshReceivers.end()){
            const auto& value=receiver->second;
            if(value.indices.size()!=count)throw std::invalid_argument("Receiver update changes geometry layout");
            indices=value.indices;receiverBaseline=value.baseline;receiverColors=value.colors;
        }
        rtr::PackReceiverUpload(target,count,indices,receiverBaseline,receiverColors,
            baseline==meshBaselines.end()?nullptr:baseline->second.data(),
            colors==meshColors.end()?nullptr:colors->second.data());
        attributeWork[1]+=count;
    }
    void UploadVertexRuns(nvrhi::ICommandList* command,const std::unordered_set<std::string>& names){
        vertexUploadRuns.clear();
        for(const auto& name:names)if(auto found=geometrySlots.find(name);found!=geometrySlots.end())
            vertexUploadRuns.push_back({&found->first,found->second});
        std::sort(vertexUploadRuns.begin(),vertexUploadRuns.end(),[](const auto& a,const auto& b){return a.attributes.offset<b.attributes.offset;});
        // Coalesce authored adjacent slices only. NVRHI still owns staging,
        // fences and GPU resources; no second upload allocator is introduced.
        constexpr size_t batchCorners=1024*1024/sizeof(VertexAttributes);
        for(size_t begin=0;begin<vertexUploadRuns.size();){
            const size_t offset=vertexUploadRuns[begin].attributes.offset;
            size_t end=begin+1,corners=size_t(vertexUploadRuns[begin].attributes.triangles)*3;
            while(end<vertexUploadRuns.size()
                &&vertexUploadRuns[end].attributes.offset==offset+corners
                &&size_t(vertexUploadRuns[end].attributes.triangles)*3<=batchCorners-std::min(corners,batchCorners)){
                corners+=size_t(vertexUploadRuns[end].attributes.triangles)*3;++end;
            }
            vertexUploadScratch.reserve(corners);size_t written=0;
            for(size_t at=begin;at<end;++at){
                const auto& run=vertexUploadRuns[at];const size_t count=size_t(run.attributes.triangles)*3;
                PackVertices(*run.name,count,vertexUploadScratch.data()+written);written+=count;++attributeWork[4];
            }
            const size_t bytes=corners*sizeof(VertexAttributes);
            if(bytes){
                RtrDiagnostics::Scope upload(RtrDiagnostics::BufferUpload,corners,bytes,1,0,0,0,RtrDiagnostics::Attributes,bytes);
                command->writeBuffer(baselineCoordinates,vertexUploadScratch.data(),bytes,offset*sizeof(VertexAttributes));
                attributeWork[3]+=bytes;
            }
            begin=end;
        }
    }
    void ReleaseGeometryRanges(const GeometryAttributes& attributes){
        FreeAttributeRangeAt(attributes.offset,attributes.triangles*3);
        if(attributes.surfaceSelector&MixedSurfaceBit)
            surfaceRanges.Release(attributes.surfaceSelector&~MixedSurfaceBit,attributes.triangles);
    }
    bool SetSurfaceMapping(GeometryAttributes& attributes,rtr::upstream::MeshSurfaceSlots slots){
        const auto before=attributes.surfaceSelector;
        if(slots.mixed){
            if(slots.mixed->size()!=attributes.triangles)throw std::invalid_argument("Surface update changes triangle layout");
            // Mixed ranges are a subset of the live corner ranges, but churn can
            // leave holes. The tag bit is reserved even if the arena fragments.
            if(!(before&MixedSurfaceBit)){
                const auto offset=surfaceRanges.Allocate(attributes.triangles);
                if(uint64_t(offset)+attributes.triangles>MixedSurfaceBit)throw std::length_error("Triangle surfaces exceed selector capacity");
                attributes.surfaceSelector=MixedSurfaceBit|offset;
            }
            surfaceUploadRuns.push_back({attributes.surfaceSelector&~MixedSurfaceBit,slots.mixed});
        }else{
            if(before&MixedSurfaceBit)surfaceRanges.Release(before&~MixedSurfaceBit,attributes.triangles);
            attributes.surfaceSelector=slots.uniform;
        }
        return before!=attributes.surfaceSelector;
    }
    bool StageChangedSurfaces(){
        bool staged=false;
        for(const auto& name:dirtySurfaces){
            auto found=geometryIndices.find(name);
            if(found==geometryIndices.end()||!found->second.attributes)continue;
            auto& attributes=*found->second.attributes;
            if(!SetSurfaceMapping(attributes,surfaceSlots(name)))continue;
            geometryUploadScratch.at(found->second.index)=attributes;
            geometryUploadIndices.push_back(found->second.index);attributeWork[3]+=sizeof(attributes);staged=true;
        }
        dirtySurfaces.clear();return staged;
    }
    void UploadGeometryRows(nvrhi::ICommandList* command){
        const bool overlap=!geometryUploadIndices.empty();
        if(StageChangedSurfaces()&&overlap){
            std::sort(geometryUploadIndices.begin(),geometryUploadIndices.end());
            geometryUploadIndices.erase(std::unique(geometryUploadIndices.begin(),geometryUploadIndices.end()),geometryUploadIndices.end());
        }
        UploadAttributeRuns(command,geometryBaselines,geometryUploadScratch,geometryUploadIndices);
        geometryUploadIndices.clear();
    }
    void UploadSurfaceRuns(nvrhi::IDevice* device,nvrhi::ICommandList* command){
        EnsureAttributeBuffer(device,command,surfaceIndices,surfaceRanges.end*sizeof(uint32_t),sizeof(uint32_t),"Finite-signal triangle surfaces");
        std::sort(surfaceUploadRuns.begin(),surfaceUploadRuns.end(),[](const auto& a,const auto& b){return a.offset<b.offset;});
        for(size_t begin=0;begin<surfaceUploadRuns.size();){
            const auto& first=surfaceUploadRuns[begin];size_t count=first.slots->size(),end=begin+1;
            for(;end<surfaceUploadRuns.size();++end){
                const auto& next=surfaceUploadRuns[end];
                if(next.offset!=first.offset+count||count+next.slots->size()>262144)break;
                count+=next.slots->size();
            }
            const uint32_t* data=first.slots->data();
            if(end>begin+1){
                surfaceUploadScratch.clear();surfaceUploadScratch.reserve(count);
                for(size_t i=begin;i<end;++i){const auto& slots=*surfaceUploadRuns[i].slots;
                    surfaceUploadScratch.insert(surfaceUploadScratch.end(),slots.begin(),slots.end());}
                data=surfaceUploadScratch.data();
            }
            const size_t bytes=count*sizeof(uint32_t);
            RtrDiagnostics::Scope upload(RtrDiagnostics::BufferUpload,count,bytes,1,0,0,0,RtrDiagnostics::Material|RtrDiagnostics::Attributes,bytes);
            command->writeBuffer(surfaceIndices,data,bytes,size_t(first.offset)*sizeof(uint32_t));attributeWork[3]+=bytes;begin=end;
        }
        if(!surfaceUploadRuns.empty())++metadataUploads;
        surfaceUploadRuns.clear();
    }
    std::vector<uint8_t> applicationData;
    std::vector<RtrNativeApplicationRange> applicationDirtyRanges;
    uint64_t applicationUploadedBytes=0,applicationUploadCalls=0,applicationPatches=0;
    bool lookupDirty=true,applicationDirty=true;
    void PatchApplication(uint64_t generation,size_t offset,const std::vector<uint8_t>& bytes){
        // Public Java publications require strictly increasing generations. Their
        // bounded uploader may emit several disjoint slices of that same generation.
        if(generation==0||generation<publication)throw std::invalid_argument("Application patch generation cannot precede the resident publication");
        auto next=RtrMergeApplicationRange(applicationDirtyRanges,offset,bytes.size(),applicationData.size());
        // All validation/allocation precedes mutation, including the dirty-range metadata.
        std::copy(bytes.begin(),bytes.end(),applicationData.begin()+offset);
        applicationDirtyRanges=std::move(next);publication=generation;++applicationPatches;
    }
    // Baseline float2[256], followed by packed host-response RGB[256]. The
    // Java contract requires an explicit table before selecting response mode.
    std::array<float2,640> baselineLookup=rtr::DefaultHostResponseLookup<float2>();
    nvrhi::IBuffer* previousSceneInstances=nullptr;
    nvrhi::IBuffer* previousSceneGeometry=nullptr;
    size_t previousInstanceCount=0,previousGeometryCount=0;
    struct Retired {std::vector<nvrhi::BufferHandle> buffers;std::vector<nvrhi::TextureHandle> textures;std::vector<nvrhi::ComputePipelineHandle> pipelines;std::vector<nvrhi::BindingSetHandle> bindings;nvrhi::EventQueryHandle done,computeDone;uint64_t bytes=0;};
    std::vector<Retired> retired;
    RtrNativeLightingPass(){retired.reserve(64);}
    std::shared_ptr<RtrResourceReclaimer> reclaimer=std::make_shared<RtrResourceReclaimer>();
    void PollRetired(nvrhi::IDevice* device){
        size_t budget=2;
        RtrDiagnostics::Scope maintenance(RtrDiagnostics::ResourceRetirement);
        for(auto i=retired.begin();i!=retired.end()&&budget&&reclaimer->Available();){
            bool done;{RtrDiagnostics::Scope poll(RtrDiagnostics::CompletionPoll,2);done=device->pollEventQuery(i->done)&&device->pollEventQuery(i->computeDone);}
            if(!done)break;
            RtrDiagnostics::Scope transfer(RtrDiagnostics::ReclaimEnqueue,1,0,1,0,0,0,0,i->bytes);
            if(!reclaimer->Enqueue(*i,i->bytes))break;i=retired.erase(i);--budget;
        }
    }
    void Configure(const std::vector<float>& parameters){
        if(parameters.size()!=98)throw std::invalid_argument("Native-lighting parameters require 98 words");
        for(auto v:parameters)if(!std::isfinite(v))throw std::invalid_argument("Non-finite native-lighting parameter");
        auto integer=[&](size_t i,uint32_t minimum,uint32_t maximum){float value=parameters[i];
            if(value<float(minimum)||value>float(maximum)||float(uint32_t(value))!=value)throw std::invalid_argument("Invalid native-lighting integer parameter");return uint32_t(value);};
        uint32_t w=integer(40,1,16384),h=integer(41,1,16384),outputs=integer(42,1,RefinementMask|DielectricMask|LegacySignalMask),samples=integer(43,0,8);
        if((outputs&RefinementMask)&&!(outputs&DielectricMask))throw std::invalid_argument("Dielectric refinement requires its coarse plane");
        if((outputs&48)&&!(outputs&1))throw std::invalid_argument("Directional lighting evidence requires direct gain");
        if(parameters[85]<0)throw std::invalid_argument("Invalid directional source angular radius");
        if(parameters[88]<0||parameters[88]>1||parameters[89]<=0)
            throw std::invalid_argument("Invalid diffuse sky occlusion parameters");
        if(parameters[92]<0||parameters[93]>1||parameters[92]>=parameters[93]
            ||parameters[94]<0||parameters[94]>65504)throw std::invalid_argument("Invalid native wet-film roughness response");
        if(parameters[56]<=0||parameters[57]<0||parameters[58]<=0||parameters[58]>.1f)
            throw std::invalid_argument("Invalid native-lighting GI budget or offset");
        if(parameters[47]<0||parameters[47]>1||parameters[55]<0||parameters[55]>1)
            throw std::invalid_argument("Style directional weights must be in [0,1]");
        Constants prepared;std::copy_n(parameters.begin(),prepared.words.size(),prepared.words.begin());
        if(outputs&RefinementMask){
            const uint32_t vw=integer(96,0,16384),vh=integer(97,0,16384);
            if(vw>=w&&vh>=h&&vw<=4*w&&vh<=4*h&&(vw>w||vh>h)){
                const uint32_t rays=((vw+w-1)/w)*((vh+h-1)/h);
                const uint32_t atlasTexels=w*h-(w*h+3)/4;
                // Bound work independently of the number of discontinuities and the display size.
                const uint32_t jobs=std::min({atlasTexels/rays,vw*vh/(32*rays),16384u});
                if(jobs)prepared.opticalViewport={vw,vh,jobs,0};
            }
            if(!prepared.opticalViewport[2])outputs&=~RefinementMask;
        }
        auto* integers=reinterpret_cast<uint32_t*>(prepared.words.data());integers[40]=w;integers[41]=h;integers[42]=outputs;integers[43]=samples;
        // Counter enable is not a float identity payload. The caller updates it
        // from the actual diagnostic setting, separately from required state.
        integers[72]=0;integers[73]=uint32_t(publication);integers[74]=uint32_t(publication>>32);integers[75]=0;
        for(size_t i=76;i<80;i++)integers[i]=0;
        constants=prepared;cameraVolumes.reset();active=true;
    }
    static nvrhi::BufferHandle Buffer(nvrhi::IDevice* device,size_t bytes,size_t stride,const char* name,bool raw=false){
        nvrhi::BufferDesc desc;desc.byteSize=std::max<size_t>(bytes,stride?stride:4);desc.structStride=uint32_t(stride);
        desc.debugName=name;desc.initialState=nvrhi::ResourceStates::ShaderResource;desc.keepInitialState=true;desc.canHaveRawViews=raw;
        auto buffer=device->createBuffer(desc);if(!buffer)throw std::runtime_error(RtrAllocationFailure(device,name,desc.byteSize));return buffer;
    }
    void Retire(nvrhi::IDevice* device,std::vector<nvrhi::BufferHandle> buffers,std::vector<nvrhi::TextureHandle> textures={}){
        PollRetired(device);if(retired.size()>=64)throw std::runtime_error("Lighting retirement capacity exhausted");
        Retired old;old.buffers=std::move(buffers);old.textures=std::move(textures);
        // NVRHI binding sets own their resources. An inactive variant (for
        // example DEEP after it is disabled) must not pin a replaced buffer.
        // Preserve unrelated caches and let NVRHI retain submitted bindings
        // until GPU completion; release our cache references on the reclaimer.
        auto retireBindings=[&](RtrCachedBindings& cache){
            if(!cache.value)return;const auto* desc=cache.value->getDesc();if(!desc)return;
            for(const auto& item:desc->bindings){
                bool matches=false;
                for(const auto& buffer:old.buffers)if(buffer&&item.resourceHandle==buffer.operator->()){matches=true;break;}
                if(!matches)for(const auto& texture:old.textures)if(texture&&item.resourceHandle==texture.operator->()){matches=true;break;}
                if(matches){old.bindings.push_back(std::move(cache.value));return;}
            }
        };
        retireBindings(dispatchBindings);retireBindings(encodeBindings);retireBindings(penumbraDispatchBindings);
        retireBindings(dielectricDispatchBindings);
        retireBindings(dielectricClassifyBindings);retireBindings(dielectricRefineBindings);
        for(const auto& buffer:old.buffers)if(buffer)directionalFlux.RetireBindingFor(buffer,nullptr,old.bindings);
        for(const auto& texture:old.textures)if(texture)directionalFlux.RetireBindingFor(nullptr,texture,old.bindings);
        for(auto& cache:sourceHistoryBindings)retireBindings(cache);
        for(auto& cache:phaseBindings)retireBindings(cache);
        retireBindings(reconstruction.resolveBindings);retireBindings(reconstruction.reduceBindings);retireBindings(reconstruction.upsampleBindings);
        for(auto& b:old.buffers)if(b)old.bytes+=b->getDesc().byteSize;
        old.done=device->createEventQuery();old.computeDone=device->createEventQuery();
        if(!old.done||!old.computeDone)throw std::runtime_error("Lighting completion query allocation failed");
        device->setEventQuery(old.done,nvrhi::CommandQueue::Graphics);device->setEventQuery(old.computeDone,nvrhi::CommandQueue::Compute);retired.push_back(std::move(old));
    }
    static nvrhi::ComputePipelineHandle CreateLightingPipeline(nvrhi::IDevice* device,const nvrhi::ComputePipelineDesc& desc,
        const char* name,unsigned variant=0){
        if(!desc.CS)throw std::runtime_error(std::string("Missing precompiled lighting shader: ")+name);
        using Clock=std::chrono::steady_clock;
        std::fprintf(stderr,"RTXPT lighting PSO begin: %s variant=%u\n",name,variant);
        std::fflush(stderr);
        const auto start=Clock::now();
        auto result=device->createComputePipeline(desc);
        const auto elapsed=std::chrono::duration<double,std::milli>(Clock::now()-start).count();
        std::fprintf(stderr,"RTXPT lighting PSO end: %s variant=%u ms=%.3f ready=%d\n",name,variant,elapsed,result?1:0);
        std::fflush(stderr);
        return result;
    }
    void Prepare(nvrhi::IDevice* device,donut::engine::ShaderFactory& factory,nvrhi::IBindingLayout* bindlessLayout,const RtrSparseLighting* sources=nullptr){
        dielectricProbe.Poll();
        // Retirement must advance on stable and empty frames too. Polling only
        // from Retire keeps the final replaced buffers alive indefinitely once
        // growth stops; the same two-queue completion checks remain mandatory.
        PollRetired(device);
        if(!layout){
            RtrDiagnostics::Scope initialize(RtrDiagnostics::LightingInitialize);
            nvrhi::BindingLayoutDesc desc;desc.visibility=nvrhi::ShaderType::Compute;
            desc.bindings={nvrhi::BindingLayoutItem::ConstantBuffer(0),nvrhi::BindingLayoutItem::RayTracingAccelStruct(0),
                nvrhi::BindingLayoutItem::StructuredBuffer_SRV(1),nvrhi::BindingLayoutItem::StructuredBuffer_SRV(2),
                nvrhi::BindingLayoutItem::StructuredBuffer_SRV(3),nvrhi::BindingLayoutItem::StructuredBuffer_SRV(4),
                nvrhi::BindingLayoutItem::StructuredBuffer_SRV(5),nvrhi::BindingLayoutItem::StructuredBuffer_SRV(6),
                nvrhi::BindingLayoutItem::StructuredBuffer_SRV(7),nvrhi::BindingLayoutItem::StructuredBuffer_SRV(8),
                nvrhi::BindingLayoutItem::RawBuffer_SRV(9),nvrhi::BindingLayoutItem::StructuredBuffer_SRV(10),nvrhi::BindingLayoutItem::StructuredBuffer_SRV(11),
                nvrhi::BindingLayoutItem::StructuredBuffer_SRV(12),
                nvrhi::BindingLayoutItem::StructuredBuffer_SRV(13),
                nvrhi::BindingLayoutItem::Sampler(0),nvrhi::BindingLayoutItem::Texture_UAV(0),nvrhi::BindingLayoutItem::Texture_UAV(1),
                nvrhi::BindingLayoutItem::Texture_UAV(2),nvrhi::BindingLayoutItem::Texture_UAV(3),nvrhi::BindingLayoutItem::RawBuffer_UAV(4),
                nvrhi::BindingLayoutItem::Texture_UAV(12)};
            for(uint32_t i=0;i<8;++i)desc.bindings.push_back(nvrhi::BindingLayoutItem::Sampler(i+1));
            desc.bindings.push_back(nvrhi::BindingLayoutItem::Texture_UAV(13));
            auto preparedLayout=device->createBindingLayout(desc);
            if(!preparedLayout)throw std::runtime_error("Could not prepare native-lighting bindings");
            nvrhi::BufferDesc cb;cb.byteSize=sizeof(Constants);cb.isConstantBuffer=true;cb.debugName="Native lighting parameters";
            cb.initialState=nvrhi::ResourceStates::ConstantBuffer;cb.keepInitialState=true;constantBuffer=device->createBuffer(cb);
            application=Buffer(device,4,0,"Native lighting application data",true);
            lookup=Buffer(device,sizeof(baselineLookup),sizeof(float2),"Native lighting baseline lookup");
            nvrhi::BufferDesc counter;counter.byteSize=48;counter.canHaveRawViews=counter.canHaveUAVs=true;
            counter.debugName="Native lighting work counters";counter.initialState=nvrhi::ResourceStates::UnorderedAccess;counter.keepInitialState=true;work=device->createBuffer(counter);
            auto td=nvrhi::TextureDesc().setWidth(1).setHeight(1).setFormat(nvrhi::Format::RGBA16_FLOAT).setIsUAV(true)
                .setInitialState(nvrhi::ResourceStates::UnorderedAccess).setKeepInitialState(true);
            dummyColor=device->createTexture(td);td.format=nvrhi::Format::RGBA32_UINT;dummyIdentity=device->createTexture(td);
            if(!constantBuffer||!application||!lookup||!work||!dummyColor||!dummyIdentity)
                throw std::runtime_error("Could not allocate native-lighting common resources");
            layout=preparedLayout;
        }
        if(diagnosticDecomposition&&RtrDiagnostics::level.load()==2&&!deepPipeline){
            auto desc=*layout->getDesc();desc.bindings.push_back(nvrhi::BindingLayoutItem::StructuredBuffer_UAV(5));
            auto next=device->createBindingLayout(desc);auto shader=factory.CreateShader("app/ProcessingPasses/RtrNativeLightingDeep.hlsl","main",nullptr,nvrhi::ShaderType::Compute);
            if(!shader||!next)throw std::runtime_error("DEEP receiver decomposition shader unavailable");
            nvrhi::ComputePipelineDesc pso;pso.bindingLayouts={next,bindlessLayout};pso.CS=shader;
            auto nextPipeline=CreateLightingPipeline(device,pso,"receiver-decomposition");if(!nextPipeline)throw std::runtime_error("DEEP receiver decomposition pipeline unavailable");deepLayout=next;deepPipeline=nextPipeline;
        }
        const auto* integers=reinterpret_cast<const uint32_t*>(constants.words.data());
        const bool extentChanged=width!=integers[40]||height!=integers[41];
        if(extentChanged||((mask^integers[42])&LegacySignalMask)){
            std::array<nvrhi::TextureHandle,DielectricSignal> next{};
            for(unsigned i=0;i<next.size();i++)if((integers[42]&(1u<<i))!=0){
                auto desc=nvrhi::TextureDesc().setWidth(integers[40]).setHeight(integers[41])
                    .setFormat(i==3?nvrhi::Format::RGBA32_UINT:i==2?nvrhi::Format::RGBA32_FLOAT:nvrhi::Format::RGBA16_FLOAT).setIsUAV(true)
                    .setInitialState(nvrhi::ResourceStates::UnorderedAccess).setKeepInitialState(true);
                desc.debugName="Native lighting requested signal";next[i]=device->createTexture(desc);
                if(!next[i])throw std::runtime_error("Could not allocate native-lighting output");
            }
            nvrhi::TextureHandle evidence;
            if(next[5]){
                auto desc=next[5]->getDesc();desc.format=nvrhi::Format::RGBA32_FLOAT;desc.debugName="Directional shadow evidence";
                evidence=device->createTexture(desc);
                if(!evidence)throw std::runtime_error("Could not allocate directional shadow evidence");
            }
            Retire(device,{}, {direct,indirect,geometry,identity,directionalSpecular,sunPenumbra,sunEvidence});
            direct=next[0];indirect=next[1];geometry=next[2];identity=next[3];directionalSpecular=next[4];sunPenumbra=next[5];sunEvidence=evidence;
        }
        if(extentChanged||((mask^integers[42])&DielectricMask)){
            nvrhi::TextureHandle next;
            if(integers[42]&DielectricMask){
                auto desc=nvrhi::TextureDesc().setWidth(integers[40]).setHeight(integers[41])
                    .setFormat(nvrhi::Format::RGBA32_FLOAT).setIsUAV(true)
                    .setInitialState(nvrhi::ResourceStates::UnorderedAccess).setKeepInitialState(true);
                desc.debugName="Dielectric radiance and camera-ray distance";
                next=device->createTexture(desc);
                if(!next)throw std::runtime_error("Could not allocate dielectric radiance output");
            }
            if(dielectricRadiance)Retire(device,{}, {dielectricRadiance});
            dielectricRadiance=std::move(next);
        }
        if(extentChanged||((mask^integers[42])&RefinementMask)){
            nvrhi::TextureHandle next;
            if(integers[42]&RefinementMask){
                auto desc=dielectricRadiance->getDesc();desc.debugName="Sparse exact-pixel dielectric radiance";
                next=device->createTexture(desc);
                if(!next)throw std::runtime_error("Could not allocate dielectric refinement atlas");
            }
            if(dielectricRefinement)Retire(device,{}, {dielectricRefinement});
            dielectricRefinement=std::move(next);
        }
        if(dielectricRefinement&&refinementCapacity<constants.opticalViewport[2]){
            nvrhi::BufferDesc desc;desc.byteSize=(1+constants.opticalViewport[2])*sizeof(uint32_t);
            desc.canHaveUAVs=true;desc.canHaveRawViews=true;
            desc.initialState=nvrhi::ResourceStates::UnorderedAccess;desc.keepInitialState=true;
            desc.debugName="Dielectric refinement job queue";
            auto next=device->createBuffer(desc);
            if(!next)throw std::runtime_error("Could not allocate dielectric refinement jobs");
            if(dielectricRefinementJobs)Retire(device,{dielectricRefinementJobs});
            dielectricRefinementJobs=std::move(next);refinementCapacity=constants.opticalViewport[2];
        }
        if(extentChanged||mask!=integers[42])retainedSignals=false;
        width=integers[40];height=integers[41];mask=integers[42];
        if(directionalFlux.bins&&(extentChanged||!indirect||!mediumTraversalCount)){
            Retire(device,{}, {directionalFlux.bins});directionalFlux.bins=nullptr;
        }
        if(indirect&&mediumTraversalCount&&(!directionalFlux.triangles.Empty()||constants.words[47]>0||constants.words[55]>0))
            directionalFlux.Prepare(device,factory,layout,bindlessLayout);
        if(dielectricRadiance&&!dielectricPipeline){
            auto desc=*layout->getDesc();
            desc.bindings.push_back(nvrhi::BindingLayoutItem::Texture_UAV(DielectricUav));
            desc.bindings.push_back(nvrhi::BindingLayoutItem::Texture_SRV(DielectricSkySrv));
            auto nextLayout=device->createBindingLayout(desc);
            if(!nextLayout)throw std::runtime_error("Could not prepare dielectric radiance bindings");
            nvrhi::ComputePipelineDesc pso;pso.bindingLayouts={nextLayout,bindlessLayout};
            std::vector<donut::engine::ShaderMacro> macros{{"RTR_DIELECTRIC_PASS","0"}};
            pso.CS=factory.CreateShader("app/ProcessingPasses/RtrNativeDielectric.hlsl","main",&macros,nvrhi::ShaderType::Compute);
            auto nextPipeline=CreateLightingPipeline(device,pso,"dielectric-radiance");
            if(!nextPipeline)throw std::runtime_error("Could not prepare dielectric radiance pipeline");
            dielectricLayout=nextLayout;dielectricPipeline=nextPipeline;
        }
        if(dielectricRadiance&&dielectricProbe.Pending(sequence)&&!dielectricProbe.pipeline){
            auto desc=*dielectricLayout->getDesc();
            desc.bindings.push_back(nvrhi::BindingLayoutItem::RawBuffer_UAV(RtrDielectricProbe::UavSlot));
            auto nextLayout=device->createBindingLayout(desc);
            if(!nextLayout)throw std::runtime_error("Could not prepare dielectric probe bindings");
            nvrhi::ComputePipelineDesc pso;pso.bindingLayouts={nextLayout,bindlessLayout};
            std::vector<donut::engine::ShaderMacro> macros{{"RTR_DIELECTRIC_PASS","1"}};
            pso.CS=factory.CreateShader("app/ProcessingPasses/RtrNativeDielectric.hlsl","main",&macros,nvrhi::ShaderType::Compute);
            auto nextPipeline=CreateLightingPipeline(device,pso,"dielectric-probe");
            if(!nextPipeline)throw std::runtime_error("Could not prepare dielectric probe pipeline");
            dielectricProbe.layout=nextLayout;dielectricProbe.pipeline=nextPipeline;
        }
        if(dielectricRefinement&&!dielectricRefinePipeline){
            nvrhi::BindingLayoutDesc classify;classify.visibility=nvrhi::ShaderType::Compute;
            classify.bindings={nvrhi::BindingLayoutItem::ConstantBuffer(0),nvrhi::BindingLayoutItem::Texture_SRV(CoarseDielectricSrv),
                nvrhi::BindingLayoutItem::Texture_UAV(RefinementUav),nvrhi::BindingLayoutItem::RawBuffer_UAV(RefinementJobsUav)};
            auto classifyLayout=device->createBindingLayout(classify);
            auto refine=*dielectricLayout->getDesc();
            refine.bindings.erase(std::remove_if(refine.bindings.begin(),refine.bindings.end(),[](const auto& item){
                return item.slot==DielectricUav&&item.type==nvrhi::ResourceType::Texture_UAV;
            }),refine.bindings.end());
            refine.bindings.push_back(nvrhi::BindingLayoutItem::Texture_UAV(RefinementUav));
            refine.bindings.push_back(nvrhi::BindingLayoutItem::RawBuffer_SRV(RefinementJobsSrv));
            auto refineLayout=device->createBindingLayout(refine);
            if(!classifyLayout||!refineLayout)throw std::runtime_error("Could not prepare dielectric refinement bindings");
            auto create=[&](const char* pass,const char* name,bool trace){
                nvrhi::ComputePipelineDesc pso;
                if(trace)pso.bindingLayouts={refineLayout,bindlessLayout};
                else pso.bindingLayouts={classifyLayout};
                std::vector<donut::engine::ShaderMacro> macros{{"RTR_DIELECTRIC_PASS",pass}};
                pso.CS=factory.CreateShader("app/ProcessingPasses/RtrNativeDielectric.hlsl","main",&macros,nvrhi::ShaderType::Compute);
                auto pipeline=CreateLightingPipeline(device,pso,name);
                if(!pipeline)throw std::runtime_error(std::string("Could not prepare ")+name);
                return pipeline;
            };
            auto classifyPipeline=create("2","dielectric-classify",false);
            auto refinePipeline=create("3","dielectric-refine",true);
            dielectricClassifyLayout=classifyLayout;dielectricRefineLayout=refineLayout;
            dielectricClassifyPipeline=classifyPipeline;dielectricRefinePipeline=refinePipeline;
        }
        if(sunPenumbra&&!penumbraPipeline){
            auto desc=*layout->getDesc();
            desc.bindings.pop_back(); // u13 is written by direct lighting and read as t14 here.
            desc.bindings.push_back(nvrhi::BindingLayoutItem::Texture_SRV(14));
            desc.bindings.push_back(nvrhi::BindingLayoutItem::Texture_UAV(14));
            auto nextLayout=device->createBindingLayout(desc);
            auto shader=factory.CreateShader("app/ProcessingPasses/RtrSunPenumbra.hlsl","main",nullptr,nvrhi::ShaderType::Compute);
            if(!shader||!nextLayout)throw std::runtime_error("Directional shadow penumbra shader unavailable");
            nvrhi::ComputePipelineDesc pso;pso.bindingLayouts={nextLayout,bindlessLayout};pso.CS=shader;
            auto nextPipeline=CreateLightingPipeline(device,pso,"sun-penumbra");
            if(!nextPipeline)throw std::runtime_error("Could not prepare directional shadow penumbra");
            penumbraLayout=nextLayout;penumbraPipeline=nextPipeline;
        }
        PrepareCompact(device,factory);
        if(!RunMainLighting())return;
        if(UseReceiverSignalsPipeline()){
            if(!receiverSignalsPipeline){
                nvrhi::ComputePipelineDesc pso;pso.bindingLayouts={layout,bindlessLayout};
                pso.CS=factory.CreateShader("app/ProcessingPasses/RtrNativeReceiverSignals.hlsl","main",nullptr,nvrhi::ShaderType::Compute);
                receiverSignalsPipeline=CreateLightingPipeline(device,pso,"receiver-signals");
                if(!receiverSignalsPipeline)throw std::runtime_error("Receiver signals pipeline unavailable");
            }
            return;
        }
        const bool maximum=sources&&sources->VisibleMax();
        const bool channelMaximum=sources&&sources->ChannelExecutor();
        const unsigned groupVariant=!sources?0:sources->SingleGroup()?1:sources->PairGroups()?6:sources->SmallGroups()?4:0;
        const unsigned instrumentedCapacity=groupVariant==1?1:groupVariant==6?2:groupVariant==4?4:32;
        for(unsigned analytic=0;analytic<2;++analytic){
            auto& target=instrumentedCapacity==1?(analytic?singleGroupPipeline:sparseOnlySingleGroupPipeline):
                instrumentedCapacity==2?(analytic?pairGroupPipeline:sparseOnlyPairGroupPipeline):
                instrumentedCapacity==4?(analytic?smallGroupPipeline:sparseOnlySmallGroupPipeline):(analytic?pipeline:sparseOnlyPipeline);
            if(workStatisticsEnabled&&!reconstruction.requested&&!maximum&&!channelMaximum&&!target){
                RtrDiagnostics::Scope prepare(RtrDiagnostics::Pipeline,1);
                std::vector<donut::engine::ShaderMacro> macros{{"RTR_LIGHT_GROUP_CAPACITY",std::to_string(instrumentedCapacity)},
                    {"RTR_ANALYTIC_LIGHTS",analytic?"1":"0"},{"RTR_WORK_STATISTICS","1"},{"RTR_MAX_COMPOSITION","0"}};
                nvrhi::ComputePipelineDesc pso;pso.bindingLayouts={layout,bindlessLayout};
                pso.CS=factory.CreateShader("app/ProcessingPasses/RtrNativeLighting.hlsl","main",&macros,nvrhi::ShaderType::Compute);
                target=CreateLightingPipeline(device,pso,"counted-sum",instrumentedCapacity*2+analytic);if(!target)throw std::runtime_error("Diagnostic lighting pipeline unavailable");
            }
        }
        const bool directOnly=UseDirectPipeline(maximum);
        const char* directShader="app/ProcessingPasses/RtrNativeDirectLighting.hlsl";
        auto& productionPipelines=directOnly?directPipelines:uninstrumentedPipelines;
        if(!reconstruction.requested&&!workStatisticsEnabled&&!maximum&&!channelMaximum)
        for(unsigned i:{groupVariant,groupVariant+(groupVariant<4?2u:1u)})if(!productionPipelines[i]){
            RtrDiagnostics::Scope prepare(RtrDiagnostics::Pipeline,1);
            std::vector<donut::engine::ShaderMacro> macros{{"RTR_LIGHT_GROUP_CAPACITY",i>=6?"2":i>=4?"4":(i&1)?"1":"32"},
                {"RTR_ANALYTIC_LIGHTS",(i>=4?(i&1)!=0:(i&2)!=0)?"0":"1"},{"RTR_WORK_STATISTICS","0"},{"RTR_MAX_COMPOSITION","0"}};
            nvrhi::ComputePipelineDesc pso;pso.bindingLayouts={layout,bindlessLayout};
            pso.CS=factory.CreateShader(directOnly?directShader:"app/ProcessingPasses/RtrNativeLighting.hlsl","main",&macros,nvrhi::ShaderType::Compute);
            productionPipelines[i]=CreateLightingPipeline(device,pso,directOnly?"direct-sum":"sum",i);
            if(!productionPipelines[i])throw std::runtime_error("Production lighting pipeline unavailable");
        }
        const bool allDynamic=sources&&sources->dynamicCount==sources->records.size();
        const unsigned maxOffset=allDynamic?8:0;
        const unsigned capacity=groupVariant==1?1:groupVariant==6?2:groupVariant==4?4:32;
        if(!sourceHistoryLayout){
            // Host augmentation owns the remaining space-zero registers.
            nvrhi::BindingLayoutDesc desc;desc.visibility=nvrhi::ShaderType::Compute;desc.registerSpace=3;
            desc.bindings={nvrhi::BindingLayoutItem::Texture_SRV(0),nvrhi::BindingLayoutItem::Texture_UAV(0)};
            sourceHistoryLayout=device->createBindingLayout(desc);
            if(!sourceHistoryLayout)throw std::runtime_error("Could not create sparse winner seed layout");
        }
        const char* disabled=std::getenv("RTR_RTXPT_DISABLE_SPARSE_HISTORY");
        sourceHistoryActive=maximum&&capacity<=4&&(mask&1)!=0&&uint64_t(width)*height*capacity*8<=64*1024*1024&&(!disabled||std::string(disabled)!="1");
        if(!sourceHistory[0]||(sourceHistoryActive&&(sourceHistory[0]->getDesc().width!=width||sourceHistory[0]->getDesc().height!=height||sourceHistory[0]->getDesc().arraySize<capacity))){
            auto desc=nvrhi::TextureDesc().setWidth(sourceHistoryActive?width:1).setHeight(sourceHistoryActive?height:1)
                .setArraySize(sourceHistoryActive?capacity:1).setDimension(nvrhi::TextureDimension::Texture2DArray)
                .setFormat(nvrhi::Format::R32_UINT).setIsUAV(true).setInitialState(nvrhi::ResourceStates::ShaderResource).setKeepInitialState(true);
            desc.debugName="Exact sparse light winner seeds";std::array<nvrhi::TextureHandle,2> next;
            for(auto& texture:next){texture=device->createTexture(desc);if(!texture)throw std::runtime_error("Could not allocate sparse winner seeds");}
            if(sourceHistory[0])Retire(device,{}, {sourceHistory[0],sourceHistory[1]});
            sourceHistory=std::move(next);sourceHistoryRead=0;sourceHistoryClear=true;
        }
        if(!reconstruction.requested&&maximum&&!workStatisticsEnabled)for(unsigned i:{groupVariant,groupVariant+(groupVariant<4?2u:1u)})if(!maxPipelines[maxOffset+i]){
            std::vector<donut::engine::ShaderMacro> macros{{"RTR_LIGHT_GROUP_CAPACITY",i>=6?"2":i>=4?"4":(i&1)?"1":"32"},{"RTR_ANALYTIC_LIGHTS",(i>=4?(i&1)!=0:(i&2)!=0)?"0":"1"},{"RTR_WORK_STATISTICS","0"},{"RTR_MAX_COMPOSITION","1"}};
            nvrhi::ComputePipelineDesc pso;pso.bindingLayouts={layout,bindlessLayout,sourceHistoryLayout};
            pso.CS=factory.CreateShader(allDynamic?"app/ProcessingPasses/RtrNativeDynamicLighting.hlsl":"app/ProcessingPasses/RtrNativeLighting.hlsl","main",&macros,nvrhi::ShaderType::Compute);maxPipelines[maxOffset+i]=CreateLightingPipeline(device,pso,"maximum",maxOffset+i);
            if(!maxPipelines[maxOffset+i])throw std::runtime_error("Max-composition native lighting pipeline unavailable");
        }
        // Diagnostic observation must not force a different composition
        // executor. Count actual work in the exact maximum-only path, with no
        // previous-frame winner seed or extra history binding.
        if(!reconstruction.requested&&maximum&&workStatisticsEnabled)for(unsigned i:{groupVariant,groupVariant+(groupVariant<4?2u:1u)})if(!countedMaxPipelines[i]){
            std::vector<donut::engine::ShaderMacro> macros{{"RTR_LIGHT_GROUP_CAPACITY",i>=6?"2":i>=4?"4":(i&1)?"1":"32"},
                {"RTR_ANALYTIC_LIGHTS",(i>=4?(i&1)!=0:(i&2)!=0)?"0":"1"},{"RTR_WORK_STATISTICS","1"},{"RTR_MAX_COMPOSITION","1"}};
            nvrhi::ComputePipelineDesc pso;pso.bindingLayouts={layout,bindlessLayout};
            pso.CS=factory.CreateShader("app/ProcessingPasses/RtrNativeLighting.hlsl","main",&macros,nvrhi::ShaderType::Compute);
            countedMaxPipelines[i]=CreateLightingPipeline(device,pso,"counted-maximum",i);
            if(!countedMaxPipelines[i])throw std::runtime_error("Counted maximum-composition lighting pipeline unavailable");
        }
        // CHANNEL_MAX has independent RGB winners and needs neither scalar
        // luminance/ID arrays nor a dynamically indexed per-ray stack.
        const unsigned instrumented=workStatisticsEnabled?1:0;
        if(!reconstruction.requested&&channelMaximum)
            for(unsigned i:{groupVariant,groupVariant+(groupVariant<4?2u:1u)}){
                const bool directChannel=directOnly&&!instrumented;
                auto& target=directChannel?directChannelPipelines[i]:channelMaxPipelines[instrumented*8+i];
                if(target)continue;
                std::vector<donut::engine::ShaderMacro> macros{{"RTR_LIGHT_GROUP_CAPACITY",i>=6?"2":i>=4?"4":(i&1)?"1":"32"},
                    {"RTR_ANALYTIC_LIGHTS",(i>=4?(i&1)!=0:(i&2)!=0)?"0":"1"},{"RTR_WORK_STATISTICS",instrumented?"1":"0"},{"RTR_MAX_COMPOSITION","2"}};
                nvrhi::ComputePipelineDesc pso;pso.bindingLayouts={layout,bindlessLayout};
                pso.CS=factory.CreateShader(directChannel?directShader:"app/ProcessingPasses/RtrNativeLighting.hlsl","main",&macros,nvrhi::ShaderType::Compute);
                target=CreateLightingPipeline(device,pso,directChannel?"direct-channel-maximum":"channel-maximum",instrumented*8+i);
                if(!target)throw std::runtime_error("Channel-maximum lighting pipeline unavailable");
            }
        reconstruction.Prepare(device,factory,layout,bindlessLayout,sourceHistoryLayout,width,height,groupVariant,maximum,allDynamic,channelMaximum);
    }
    float maximumGi()const{return constants.words[70];}
    std::array<nvrhi::TextureHandle,DielectricSignal+2> Outputs()const{
        return {compactEncoding?compactDirect:direct,compactEncoding?compactIndirect:(reconstruction.requested&&reconstruction.valid?reconstruction.result:indirect),geometry,identity,directionalSpecular,sunPenumbra,dielectricRadiance,dielectricRefinement};
    }
    void PrepareCompact(nvrhi::IDevice* device,donut::engine::ShaderFactory& factory){
        if(!compactEncoding||!(mask&3)){
            if(compactDirect||compactIndirect){Retire(device,{}, {compactDirect,compactIndirect});compactDirect=nullptr;compactIndirect=nullptr;}return;
        }
        if(!compactPipeline){
            nvrhi::BindingLayoutDesc layoutDesc;layoutDesc.visibility=nvrhi::ShaderType::Compute;
            layoutDesc.bindings={nvrhi::BindingLayoutItem::ConstantBuffer(0),nvrhi::BindingLayoutItem::Texture_SRV(0),nvrhi::BindingLayoutItem::Texture_SRV(1),
                nvrhi::BindingLayoutItem::Texture_UAV(0),nvrhi::BindingLayoutItem::Texture_UAV(1)};
            auto nextLayout=device->createBindingLayout(layoutDesc);
            auto shader=factory.CreateShader("app/ProcessingPasses/RtrNativeLightingEncode.hlsl","main",nullptr,nvrhi::ShaderType::Compute);
            if(!shader)throw std::runtime_error("Compact native-lighting encoder is unavailable");
            nvrhi::ComputePipelineDesc desc;desc.bindingLayouts={nextLayout};desc.CS=shader;
            auto nextPipeline=device->createComputePipeline(desc);if(!nextPipeline)throw std::runtime_error("Could not prepare compact lighting encoder");
            nvrhi::BufferDesc cb;cb.byteSize=16;cb.isConstantBuffer=true;cb.initialState=nvrhi::ResourceStates::ConstantBuffer;cb.keepInitialState=true;
            auto nextConstants=device->createBuffer(cb);if(!nextConstants)throw std::runtime_error("Could not allocate compact lighting parameters");
            auto td=nvrhi::TextureDesc().setWidth(1).setHeight(1).setFormat(nvrhi::Format::RGBA8_UNORM).setIsUAV(true)
                .setInitialState(nvrhi::ResourceStates::UnorderedAccess).setKeepInitialState(true);
            auto nextDummy=device->createTexture(td);if(!nextDummy)throw std::runtime_error("Could not allocate compact lighting dummy");
            compactLayout=nextLayout;compactConstants=nextConstants;compactDummy=nextDummy;compactPipeline=nextPipeline;
        }
        auto nextDirect=compactDirect,nextIndirect=compactIndirect;
        auto prepare=[&](unsigned bit,nvrhi::TextureHandle& output){
            if(!(mask&bit)){output=nullptr;return;}
            if(output&&output->getDesc().width==width&&output->getDesc().height==height)return;
            auto td=nvrhi::TextureDesc().setWidth(width).setHeight(height).setFormat(nvrhi::Format::RGBA8_UNORM).setIsUAV(true)
                .setInitialState(nvrhi::ResourceStates::UnorderedAccess).setKeepInitialState(true);
            td.debugName="Compact native lighting UNORM8 signal";output=device->createTexture(td);
            if(!output)throw std::runtime_error("Could not allocate requested compact lighting output");
        };
        prepare(1,nextDirect);prepare(2,nextIndirect);
        if(nextDirect!=compactDirect||nextIndirect!=compactIndirect){Retire(device,{}, {compactDirect,compactIndirect});compactDirect=nextDirect;compactIndirect=nextIndirect;}
    }
    void EncodeOutputs(nvrhi::IDevice* device,nvrhi::ICommandList* command){
        if(!compactEncoding||!(mask&3))return;
        struct EncodeParameters {uint32_t width,height;float maximumGi;uint32_t mask;};
        const EncodeParameters parameters{width,height,maximumGi(),mask|(compactResponseEncoding?0x10000u:0u)};
        const auto scope=RtrGpuProfile::Begin(command,"NativeLightingEncode",-1);
        command->writeBuffer(compactConstants,&parameters,sizeof(parameters));
        nvrhi::BindingSetDesc desc;desc.bindings={nvrhi::BindingSetItem::ConstantBuffer(0,compactConstants),
            nvrhi::BindingSetItem::Texture_SRV(0,direct?direct:dummyColor),nvrhi::BindingSetItem::Texture_SRV(1,reconstruction.requested&&reconstruction.valid?reconstruction.result:(indirect?indirect:dummyColor)),
            nvrhi::BindingSetItem::Texture_UAV(0,compactDirect?compactDirect:compactDummy),nvrhi::BindingSetItem::Texture_UAV(1,compactIndirect?compactIndirect:compactDummy)};
        auto bindings=encodeBindings.Get(device,desc,compactLayout);
        nvrhi::ComputeState state;state.pipeline=compactPipeline;state.bindings={bindings};command->setComputeState(state);
        command->dispatch((width+7)/8,(height+7)/8);RtrGpuProfile::End(command,scope);
    }
    void TrackInstanceRole(const std::string& name,const InstanceAttributes& value){
        if(value.rolesVisibility.x!=23||value.surfaceVisibility.x!=1)specialInstances.insert(name);else specialInstances.erase(name);
        specialRoleCount=specialInstances.size();
    }
    void PublishInstanceAttributes(const std::string& name,const InstanceAttributes& value){
        const uint32_t roles=uint32_t(value.rolesVisibility.x)&AllTraversalRoles;
        const uint32_t previousRoles=TraversalRoles(name);
        if(roles!=previousRoles){
            if((roles^previousRoles)&MediumTraversalRole){
                if(roles&MediumTraversalRole)++mediumTraversalCount;else --mediumTraversalCount;
            }
            if(roles==DefaultTraversalRoles)traversalRoles.erase(name);
            else traversalRoles.insert_or_assign(name,roles);
            traversalMaskChanges.insert(name);
        }
        instanceAttributes.insert_or_assign(name,value);
        dirtyInstances.insert(name);TrackInstanceRole(name,value);
    }
    uint32_t TraversalRoles(const std::string& name)const{
        const auto found=traversalRoles.find(name);
        return found==traversalRoles.end()?DefaultTraversalRoles:found->second;
    }
    void ClearTraversalMasks(){
        for(const auto& [name,roles]:traversalRoles)traversalMaskChanges.insert(name);
        traversalRoles.clear();mediumTraversalCount=0;
    }
    void RetireInstance(const std::string& name){
        if(auto role=traversalRoles.find(name);role!=traversalRoles.end()){
            if(role->second&MediumTraversalRole)--mediumTraversalCount;
            traversalRoles.erase(role);
        }
        traversalMaskChanges.erase(name);
        instanceAttributes.erase(name);instanceSlots.erase(name);dirtyInstances.erase(name);specialInstances.erase(name);specialRoleCount=specialInstances.size();metadataDirty=true;
    }
    void RetireGeometry(const std::string& name){
        dirtySurfaces.erase(name);
        if(auto found=geometrySlots.find(name);found!=geometrySlots.end()){
            ReleaseGeometryRanges(found->second);geometrySlots.erase(found);
        }
        geometryIndices.erase(name);meshBaselines.erase(name);meshColors.erase(name);meshReceivers.erase(name);alphaMeshes.erase(name);dirtyBaselines.erase(name);dirtyColors.erase(name);metadataDirty=true;
    }
    void RenameGeometry(const std::string& before,const std::string& after){
        if(before==after)return;
        if(geometrySlots.contains(after)||geometryIndices.contains(after))throw std::logic_error("Geometry metadata rename target is live");
        auto rename=[&](auto& map){auto item=map.extract(before);if(!item.empty()){item.key()=after;map.insert(std::move(item));}};
        rename(geometrySlots);rename(geometryIndices);rename(meshBaselines);rename(meshColors);rename(meshReceivers);
        auto renameSet=[&](auto& values){if(values.erase(before))values.insert(after);};
        renameSet(alphaMeshes);renameSet(dirtyBaselines);renameSet(dirtyColors);renameSet(dirtySurfaces);
        metadataDirty=true;
    }
    bool CollectGeometryMetadata(const std::shared_ptr<donut::engine::MeshInfo>& mesh,uint32_t group,GeometryMetadata& entry,bool nativeRowChanged=false){
        if(entry.collected==metadataScan)return false;
        entry.collected=metadataScan;
        const auto& geometry=mesh->geometries[group];const auto& name=entry.name;
        RefreshMetadataPolicy(entry,geometry->material,mesh,group);++metadataWork[2];
        if(!entry.mapping)if(auto found=geometryIndices.find(name);found!=geometryIndices.end())entry.mapping=&found->second;
        const auto* mapping=entry.mapping;const auto* previous=mapping?mapping->attributes:nullptr;
        const float opacity=MetadataOpacity(geometry->material);
        if(!previous||previous->maskedOpacity!=opacity)TrackMaskedScalar(name,opacity);
        const bool changed=nativeRowChanged||!mapping||mapping->index!=uint32_t(geometry->globalGeometryIndex)
            ||!previous||previous->triangles*3!=geometry->numIndices
            ||previous->texturePolicies!=entry.texturePolicies||previous->maskedOpacity!=opacity;
        if(changed)metadataGeometryChanges.push_back({mesh,group,&entry});return changed;
    }
    void AppendMaterialMetadataDelta(){
        for(const auto& [name,flags]:materialMetadataChanges){
            auto found=materialGeometryUsers.find(name);if(found==materialGeometryUsers.end())continue;
            auto& users=found->second.users;
            for(auto user=users.begin();user!=users.end();){
                auto mesh=user->second.owner.lock();
                auto layout=mesh?metadataLayouts.find(mesh.get()):metadataLayouts.end();
                if(!mesh||layout==metadataLayouts.end()||layout->second.owner.owner_before(mesh)
                    ||mesh.owner_before(layout->second.owner)||user->second.group>=mesh->geometries.size()
                    ||user->second.group>=layout->second.groups.size()){
                    user=users.erase(user);continue;
                }
                const auto group=user->second.group;auto& cached=layout->second.groups[group];
                const auto& material=mesh->geometries[group]->material;
                if(cached.name!=user->first||cached.materialName!=name||!material||material->name!=name){
                    user=users.erase(user);continue;
                }
                CollectGeometryMetadata(mesh,group,cached);
                ++user;
            }
            if(users.empty())materialGeometryUsers.erase(found);
        }
    }
    void CollectMaterialMetadataDelta(){
        sceneDelta.Clear();metadataGeometryChanges.clear();++metadataScan;
        AppendMaterialMetadataDelta();
    }
    // The native table publication already resolved owner/index changes.
    // Consume that exact journal; never rediscover them by scanning the graph.
    void CollectNativeRowDelta(const RtrSceneRowChanges& rows) {
        sceneDelta.Clear();metadataGeometryChanges.clear();
        ++metadataScan;
        for(const auto& instance:rows.instances) {
            const auto& name=instance->GetNode()->GetName();
            const auto slot=instance->GetInstanceIndex();
            const auto previous=instanceSlots.find(name);
            if(previous==instanceSlots.end()||previous->second!=uint32_t(slot))
                sceneDelta.instances.push_back({instance,previous==instanceSlots.end()?nullptr:&previous->second});
        }
        for(const auto& mesh:rows.meshes) {
            auto& cached=MetadataLayout(mesh);
            for(size_t group=0;group<mesh->geometries.size();++group) {
                auto& entry=cached.groups[group];
                const auto& geometry=*mesh->geometries[group];
                const auto* mapping=entry.mapping;
                // A native row publication needs its matching metadata row.
                // Stable material inputs can reuse their packed attribute arena.
                const bool materialStable=materialMetadataChanges.empty()
                    ||!materialMetadataChanges.contains(entry.materialName);
                if(materialStable&&entry.policiesInitialized&&mapping&&mapping->attributes
                    &&mapping->attributes->triangles*3==geometry.numIndices
                    &&!entry.material.owner_before(geometry.material)&&!geometry.material.owner_before(entry.material)){
                    // This native row changed owner or payload. A geometry can
                    // leave the live graph while retaining its cached attributes,
                    // then return to its old dense index after another geometry
                    // overwrote that slot. Its cached index alone cannot prove
                    // the GPU row still contains this identity's metadata.
                    metadataGeometryChanges.push_back({mesh,uint32_t(group),&entry,true});
                    continue;
                }
                CollectGeometryMetadata(mesh,uint32_t(group),entry,true);
            }
        }
        AppendMaterialMetadataDelta();
    }
    void UploadChangedAttributes(nvrhi::IDevice* device,nvrhi::ICommandList* command,const std::shared_ptr<donut::engine::Scene>& scene){
        const auto graph=scene->GetSceneGraph();auto& changes=sceneDelta;
        RtrDiagnostics::Scope indexScope(RtrDiagnostics::ResourceIndex,changes.instances.size()+metadataGeometryChanges.size());
        EnsureAttributeBuffer(device,command,instances,std::max<size_t>(1,graph->GetMeshInstances().size())*sizeof(InstanceAttributes),sizeof(InstanceAttributes),"Native receiver roles");
        EnsureAttributeBuffer(device,command,geometryBaselines,std::max<size_t>(1,graph->GetGeometryCount())*sizeof(GeometryAttributes),sizeof(GeometryAttributes),"Native receiver geometry mapping");
        instanceUploadScratch.resize(graph->GetMeshInstances().size());instanceUploadIndices.clear();
        instanceUploadMarks.resize(instanceUploadScratch.size());
        if(++instanceUploadEpoch==0){std::fill(instanceUploadMarks.begin(),instanceUploadMarks.end(),0);++instanceUploadEpoch;}
        auto stageInstance=[&](const std::string& name,uint32_t slot,bool updateRole=true){
            if(slot>=instanceUploadScratch.size())throw std::runtime_error("Invalid changed instance index");
            if(instanceUploadMarks[slot]==instanceUploadEpoch)return;
            instanceUploadMarks[slot]=instanceUploadEpoch;
            auto attribute=instanceAttributes.find(name);InstanceAttributes value=attribute==instanceAttributes.end()?InstanceAttributes{}:attribute->second;
            instanceUploadScratch[slot]=value;instanceUploadIndices.push_back(slot);
            if(updateRole)TrackInstanceRole(name,value);
            ++attributeWork[2];attributeWork[3]+=sizeof(value);
        };
        geometryUploadScratch.resize(graph->GetGeometryCount());geometryUploadIndices.clear();
        for(const auto& change:changes.instances){
            const auto& instance=change.instance;
            const auto& name=instance->GetNode()->GetName();const auto slot=instance->GetInstanceIndex();
            if(slot<0||size_t(slot)>=graph->GetMeshInstances().size())throw std::runtime_error("Invalid changed instance index");
            const bool newIdentity=!change.previousSlot;
            if(change.previousSlot)*change.previousSlot=uint32_t(slot);
            else instanceSlots.emplace(name,uint32_t(slot));
            // A dense-index move does not alter the identity's authored role.
            stageInstance(name,uint32_t(slot),newIdentity||dirtyInstances.contains(name));++attributeTableVisits;
        }
        std::unordered_set<std::string> upload=dirtyBaselines;upload.insert(dirtyColors.begin(),dirtyColors.end());
        for(const auto& [mesh,group,cached,indexOnly]:metadataGeometryChanges){
            auto& geometry=mesh->geometries[group];auto& metadata=*cached;const auto& key=metadata.name;
            const auto index=uint32_t(geometry->globalGeometryIndex);++attributeTableVisits;
            if(index>=graph->GetGeometryCount())throw std::runtime_error("Invalid changed geometry index");
            // Collection already resolved this stable map node. Dense native
            // index movement changes its index, not its attribute allocation.
            auto* mapping=metadata.mapping;auto* attributes=mapping?mapping->attributes:nullptr;
            if(!attributes||attributes->triangles*3!=geometry->numIndices){
                if(attributes)ReleaseGeometryRanges(*attributes);
                attributes=&geometrySlots.insert_or_assign(key,GeometryAttributes{AllocateAttributeRange(geometry->numIndices),geometry->numIndices/3}).first->second;upload.insert(key);
                SetSurfaceMapping(*attributes,rtr::upstream::MeshSurfaces(*mesh));dirtySurfaces.erase(key);
            }
            if(!mapping)mapping=&geometryIndices.try_emplace(key,GeometryIndex{}).first->second;
            *mapping=GeometryIndex{index,geometryIndexEpoch,attributes};metadata.mapping=mapping;
            if(!indexOnly){
                attributes->texturePolicies=metadata.texturePolicies;
                attributes->maskedOpacity=MetadataOpacity(geometry->material);
            }
            geometryUploadScratch[index]=*attributes;geometryUploadIndices.push_back(index);attributeWork[3]+=sizeof(GeometryAttributes);
        }
        for(const auto& name:dirtyInstances){auto found=instanceSlots.find(name);if(found==instanceSlots.end())continue;
            stageInstance(name,found->second);
        }
        UploadGeometryRows(command);
        UploadAttributeRuns(command,instances,instanceUploadScratch,instanceUploadIndices);
        specialRoleCount=specialInstances.size();
        EnsureAttributeBuffer(device,command,baselineCoordinates,attributeRanges.end*sizeof(VertexAttributes),sizeof(VertexAttributes),"Native receiver attributes arena");
        UploadVertexRuns(command,upload);
        UploadPublications(device,command);previousSceneInstances=scene->GetInstanceBuffer();previousSceneGeometry=scene->GetGeometryBuffer();
        previousInstanceCount=graph->GetMeshInstances().size();previousGeometryCount=graph->GetGeometryCount();
        metadataDirty=false;materialMetadataChanges.clear();metadataGeometryChanges.clear();changes.Clear();dirtyInstances.clear();dirtyBaselines.clear();dirtyColors.clear();++metadataUploads;
    }
    void UploadAttributes(nvrhi::IDevice* device,nvrhi::ICommandList* command,const std::shared_ptr<donut::engine::Scene>& scene,const RtrSceneRowChanges& rows){
        const auto graph=scene->GetSceneGraph();
        const size_t instanceCount=graph->GetMeshInstances().size(),geometryCount=graph->GetGeometryCount();
        if(previousInstanceCount!=instanceCount||previousGeometryCount!=geometryCount)metadataDirty=true;
        if(rows.prepared&&(rows.snapshot||!rows.instances.empty()||!rows.meshes.empty()))metadataDirty=true;
        if(metadataDirty&&!rows.prepared)throw std::runtime_error("Native receiver metadata changed without a prepared scene-row journal");
        if(metadataDirty&&metadataInitialized&&!rows.snapshot){CollectNativeRowDelta(rows);UploadChangedAttributes(device,command,scene);return;}
        if(!metadataDirty&&!materialMetadataChanges.empty()){
            CollectMaterialMetadataDelta();UploadChangedAttributes(device,command,scene);return;
        }
        if(!metadataDirty){
            bool changed=!dirtyInstances.empty()||!dirtyBaselines.empty()||!dirtyColors.empty()||lookupDirty||applicationDirty||!applicationDirtyRanges.empty();
            instanceUploadScratch.resize(instanceCount);instanceUploadIndices.clear();
            for(const auto& name:dirtyInstances){auto found=instanceSlots.find(name);if(found!=instanceSlots.end()){
                const auto& value=instanceAttributes.at(name);const auto slot=found->second;
                if(slot>=instanceUploadScratch.size())throw std::runtime_error("Invalid retained instance index");
                instanceUploadScratch[slot]=value;instanceUploadIndices.push_back(slot);
                TrackInstanceRole(name,value);++attributeWork[2];attributeWork[3]+=sizeof(value);}}
            UploadAttributeRuns(command,instances,instanceUploadScratch,instanceUploadIndices);
            auto changedGeometry=dirtyBaselines;changedGeometry.insert(dirtyColors.begin(),dirtyColors.end());
            UploadVertexRuns(command,changedGeometry);
            if(!dirtySurfaces.empty()){UploadGeometryRows(command);changed=true;}
            UploadPublications(device,command);dirtyInstances.clear();dirtyBaselines.clear();dirtyColors.clear();if(changed)++metadataUploads;return;
        }
        RtrDiagnostics::Scope indexScope(RtrDiagnostics::ResourceIndex);
        ++metadataScan;
        // Donut can replace its dense tables without replacing any of the mesh
        // owners. Keep the adapter's group names and material dependency index.
        // Drop absent owners before collecting rows, so a retired name cannot
        // erase a new owner's material registration later in this snapshot.
        for(const auto& mesh:rows.meshes){
            auto cached=metadataLayouts.find(mesh.get());
            if(cached!=metadataLayouts.end()&&!cached->second.owner.owner_before(mesh)
                &&!mesh.owner_before(cached->second.owner))cached->second.seenInSnapshot=true;
        }
        for(auto cached=metadataLayouts.begin();cached!=metadataLayouts.end();){
            if(!cached->second.seenInSnapshot){ForgetMetadataLayout(cached->second);cached=metadataLayouts.erase(cached);}
            else{cached->second.seenInSnapshot=false;++cached;}
        }
        EnsureAttributeBuffer(device,command,instances,std::max<size_t>(1,instanceCount)*sizeof(InstanceAttributes),sizeof(InstanceAttributes),"Native receiver roles");
        instanceUploadScratch.resize(instanceCount);instanceUploadIndices.clear();
        instanceNamesScratch.assign(instanceCount,nullptr);
        for(const auto& instance:rows.instances){
            const auto& name=instance->GetNode()->GetName();auto index=instance->GetInstanceIndex();
            if(index>=instanceNamesScratch.size()||instanceNamesScratch[index])throw std::runtime_error("Invalid dense instance index");
            instanceNamesScratch[index]=&name;
            auto old=instanceSlots.find(name);
            const bool changed=!metadataInitialized||rows.snapshot||old==instanceSlots.end()||old->second!=index||dirtyInstances.count(name);
            instanceSlots.insert_or_assign(name,index);
            if(changed){
                auto found=instanceAttributes.find(name);InstanceAttributes value=found==instanceAttributes.end()?InstanceAttributes{}:found->second;
                if(index>=instanceUploadScratch.size())throw std::runtime_error("Invalid dense instance index");
                instanceUploadScratch[index]=value;instanceUploadIndices.push_back(index);++attributeWork[2];attributeWork[3]+=sizeof(value);
            }
        }
        for(auto entry=instanceSlots.begin();entry!=instanceSlots.end();){
            const auto index=entry->second;
            if(index>=instanceNamesScratch.size()||!instanceNamesScratch[index]||entry->first!=*instanceNamesScratch[index])entry=instanceSlots.erase(entry);else ++entry;
        }
        UploadAttributeRuns(command,instances,instanceUploadScratch,instanceUploadIndices);
        specialRoleCount=0;specialInstances.clear();
        for(auto entry=instanceAttributes.begin();entry!=instanceAttributes.end();)if(!instanceSlots.count(entry->first))entry=instanceAttributes.erase(entry);
            else{TrackInstanceRole(entry->first,entry->second);++entry;}
        // Reuse names and map nodes. A dense-index migration must still inspect
        // the complete table, but it need not allocate a temporary hash set for it.
        if(++geometryIndexEpoch==0){for(auto& [name,index]:geometryIndices)index.epoch=0;++geometryIndexEpoch;}
        geometryEntriesScratch.resize(geometryCount);size_t entryCount=0;
        for(const auto& mesh:rows.meshes){
            auto& layout=MetadataLayout(mesh);
            for(size_t j=0;j<mesh->geometries.size();j++){
                if(entryCount>=geometryEntriesScratch.size())throw std::runtime_error("Invalid dense geometry count");
                const auto& g=mesh->geometries[j];auto& entry=geometryEntriesScratch[entryCount++];
                auto& metadata=layout.groups[j];entry.name=metadata.name;
                entry.index=uint32_t(g->globalGeometryIndex);entry.corners=g->numIndices;
                entry.surfaces=rtr::upstream::MeshSurfaces(*mesh);
                RefreshMetadataPolicy(metadata,g->material,mesh,uint32_t(j));entry.texturePolicies=metadata.texturePolicies;
                // Donut normalizes masked MaterialConstants.opacity to one. Its
                // original Material object still retains the consumer's float scalar.
                // Keep that input in adapter metadata for this independent pass only.
                entry.maskedOpacity=MetadataOpacity(g->material);TrackMaskedScalar(entry.name,entry.maskedOpacity);
                auto old=geometryIndices.find(entry.name);
                entry.indexChanged=old==geometryIndices.end()||old->second.index!=entry.index;
                if(old==geometryIndices.end())old=geometryIndices.emplace(entry.name,GeometryIndex{entry.index,geometryIndexEpoch}).first;
                else{old->second.index=entry.index;old->second.epoch=geometryIndexEpoch;}
                entry.slot=&old->second;metadata.mapping=entry.slot;++attributeTableVisits;
            }
        }
        if(entryCount!=geometryCount)throw std::runtime_error("Invalid dense geometry count");
        // Release only retired identities. Same-size replacements keep their stable range.
        for(auto i=geometryIndices.begin();i!=geometryIndices.end();)if(i->second.epoch!=geometryIndexEpoch){
            if(auto* attributes=i->second.attributes)ReleaseGeometryRanges(*attributes);
            meshBaselines.erase(i->first);meshColors.erase(i->first);meshReceivers.erase(i->first);alphaMeshes.erase(i->first);geometrySlots.erase(i->first);
            i=geometryIndices.erase(i);
        }else ++i;
        EnsureAttributeBuffer(device,command,geometryBaselines,std::max<size_t>(1,geometryCount)*sizeof(GeometryAttributes),sizeof(GeometryAttributes),"Native receiver geometry mapping");
        geometryUploadScratch.resize(geometryCount);geometryUploadIndices.clear();
        std::unordered_set<std::string> upload=dirtyBaselines;upload.insert(dirtyColors.begin(),dirtyColors.end());
        for(const auto& entry:geometryEntriesScratch){auto*& attributes=entry.slot->attributes;
            bool mappingChanged=!attributes||attributes->triangles*3!=entry.corners;
            if(mappingChanged){
                if(attributes)ReleaseGeometryRanges(*attributes);
                auto [found,inserted]=geometrySlots.insert_or_assign(entry.name,GeometryAttributes{AllocateAttributeRange(entry.corners),entry.corners/3});
                attributes=&found->second;upload.insert(entry.name);
            }
            // Full native table publication and resource resets invalidate dense
            // row ownership, even for the same logical name and triangle count.
            // Rebind the current surface namespace and republish snapshot rows.
            const bool surfacesChanged=SetSurfaceMapping(*attributes,entry.surfaces);
            dirtySurfaces.erase(entry.name);
            const bool policiesChanged=attributes->texturePolicies!=entry.texturePolicies;
            const bool opacityChanged=attributes->maskedOpacity!=entry.maskedOpacity;
            attributes->texturePolicies=entry.texturePolicies;
            attributes->maskedOpacity=entry.maskedOpacity;
            if(!metadataInitialized||rows.snapshot||mappingChanged||surfacesChanged||entry.indexChanged||policiesChanged||opacityChanged){auto value=*attributes;
                if(entry.index>=geometryUploadScratch.size())throw std::runtime_error("Invalid dense geometry index");
                geometryUploadScratch[entry.index]=value;geometryUploadIndices.push_back(entry.index);attributeWork[3]+=sizeof(value);}
        }
        UploadGeometryRows(command);
        EnsureAttributeBuffer(device,command,baselineCoordinates,attributeRanges.end*sizeof(VertexAttributes),sizeof(VertexAttributes),"Native receiver attributes arena");
        UploadVertexRuns(command,upload);
        ++attributeWork[0];
        UploadPublications(device,command);
        previousSceneInstances=scene->GetInstanceBuffer();previousSceneGeometry=scene->GetGeometryBuffer();previousInstanceCount=instanceCount;previousGeometryCount=geometryCount;
        metadataDirty=false;materialMetadataChanges.clear();metadataInitialized=true;dirtyInstances.clear();dirtyBaselines.clear();dirtyColors.clear();++metadataUploads;
    }
    void UploadPublications(nvrhi::IDevice* device,nvrhi::ICommandList* command){
        if(lookupDirty){command->writeBuffer(lookup,baselineLookup.data(),sizeof(baselineLookup));lookupDirty=false;}
        if(applicationDirty){
            size_t bytes=std::max<size_t>(4,applicationData.size());
            if(application->getDesc().byteSize<bytes){size_t capacity=4;while(capacity<bytes)capacity*=2;
                auto next=Buffer(device,capacity,0,"Native lighting application data",true);Retire(device,{application});application=next;}
            if(applicationData.empty()){uint32_t zero=0;command->writeBuffer(application,&zero,4);applicationUploadedBytes+=4;}
            else{command->writeBuffer(application,applicationData.data(),applicationData.size());applicationUploadedBytes+=applicationData.size();}
            ++applicationUploadCalls;applicationDirty=false;applicationDirtyRanges.clear();
        }else if(!applicationDirtyRanges.empty()){
            for(const auto& range:applicationDirtyRanges){
                command->writeBuffer(application,applicationData.data()+range.offset,range.size,range.offset);
                applicationUploadedBytes+=range.size;++applicationUploadCalls;
            }
            applicationDirtyRanges.clear();
        }
    }
    void SetOpticalTriangleEmitters(std::vector<float> records,std::vector<int64_t> volumes){
        directionalFlux.triangles.Set(records,volumes);
        retainedSignals=false;
    }
#if defined(_MSC_VER)
    // Keep this command recorder out of the large SDK host Render function.
    __declspec(noinline)
#endif
    void SetDielectricSky(std::vector<float> sky){
        // Previously submitted bindings keep the old image alive until completion.
        if(dielectricSky){dielectricDispatchBindings.value=nullptr;dielectricSky=nullptr;}
        if(sky.size()==4)std::vector<float>().swap(pendingDielectricSky);
        else pendingDielectricSky=std::move(sky);
    }
    void UploadDielectricSky(nvrhi::IDevice* device,nvrhi::ICommandList* command){
        const uint32_t width=uint32_t(pendingDielectricSky[4]),height=uint32_t(pendingDielectricSky[5]);
        auto desc=nvrhi::TextureDesc().setWidth(width).setHeight(height).setFormat(nvrhi::Format::RGBA32_FLOAT)
            .setInitialState(nvrhi::ResourceStates::ShaderResource).setKeepInitialState(true);
        desc.debugName="Dielectric directional environment";
        auto texture=device->createTexture(desc);
        if(!texture)throw std::runtime_error("Could not allocate dielectric environment texture");
        const size_t count=size_t(width)*height;
        auto rgba=std::make_unique_for_overwrite<std::array<float,4>[]>(count);
        const float* rgb=pendingDielectricSky.data()+6;
        for(size_t i=0;i<count;++i,rgb+=3)rgba[i]={rgb[0],rgb[1],rgb[2],1.f};
        command->writeTexture(texture,0,0,rgba.get(),size_t(width)*sizeof(rgba[0]));
        dielectricSky=std::move(texture);
        std::vector<float>().swap(pendingDielectricSky);
    }
    void Dispatch(nvrhi::IDevice* device,nvrhi::ICommandList* command,const std::shared_ptr<donut::engine::Scene>& scene,
            const RtrSceneRowChanges& rows,nvrhi::rt::IAccelStruct* tlas,RtrSparseLighting& sources,nvrhi::ISampler* sampler,nvrhi::IBuffer* analytic,
            uint32_t lightCount,uint32_t lightRoot,uint32_t treeBase,nvrhi::ITexture* fallbackSky){
        const auto uploadScope=RtrGpuProfile::Begin(command,"NativeLightingUpload",-1);
        if(surfaceUpdates)surfaceUpdates(surfaceRows,surfaceDirty);
        EnsureAttributeBuffer(device,command,surfaceMaterials,std::max<size_t>(1,surfaceRows.size())*sizeof(rtr::upstream::SurfaceMaterialRow),sizeof(rtr::upstream::SurfaceMaterialRow),"Finite-signal surface materials");
        for(size_t slot=surfaceRows.size();slot<surfaceCoverage.size();++slot)surfaceCandidateCount-=surfaceCoverage[slot];
        surfaceCoverage.resize(surfaceRows.size());
        for(uint32_t slot:surfaceDirty){
            const auto& row=surfaceRows.at(slot);
            const bool candidate=row.indexOfRefraction>0&&(row.material.domain!=0||row.maskedOpacity!=1.f);
            surfaceCandidateCount-=surfaceCoverage.at(slot);surfaceCandidateCount+=candidate;surfaceCoverage[slot]=candidate;
        }
        if(!surfaceDirty.empty()){
            RtrDiagnostics::Scope rowsScope(RtrDiagnostics::SceneMaterialRows,surfaceDirty.size(),surfaceDirty.size(),1,0,0,0,
                RtrDiagnostics::Material|RtrDiagnostics::Attributes,surfaceDirty.size()*sizeof(rtr::upstream::SurfaceMaterialRow));
            UploadAttributeRuns(command,surfaceMaterials,surfaceRows,surfaceDirty);++metadataUploads;
            attributeWork[3]+=surfaceDirty.size()*sizeof(rtr::upstream::SurfaceMaterialRow);
            surfaceDirty.clear();
        }
        UploadAttributes(device,command,scene,rows);UploadSurfaceRuns(device,command);sources.Upload(command);
        // Resolve names after resident instance rows are final, never persist scene GPU slots across frames.
        if(indirect&&directionalFlux.triangles.ResolveVolumes([&](const std::string& name){
            const auto found=instanceSlots.find(name);
            if(found==instanceSlots.end()||!(TraversalRoles(name)&MediumTraversalRole))
                throw std::invalid_argument("Optical triangle emitter medium is not resident in the traced scene: "+name);
            return found->second;
        }))retainedSignals=false;
        if(mask&DielectricMask){
            if(mediumTraversalCount&&!pendingDielectricSky.empty())UploadDielectricSky(device,command);
            auto& slots=constants.cameraSlots;
            slots.fill(cameraVolumes?UINT32_MAX:0u);
            if(cameraVolumes)for(size_t i=0;i<cameraVolumes->size();++i){
                const auto found=instanceSlots.find((*cameraVolumes)[i]);
                if(found==instanceSlots.end())throw std::invalid_argument("Camera medium is not resident in the traced scene");
                slots[i]=found->second+1u;
            }
        }
        const bool runMain=RunMainLighting();
        if(runMain&&sourceHistoryClear){for(auto& texture:sourceHistory)command->clearTextureUInt(texture,nvrhi::AllSubresources,0);sourceHistoryClear=false;}
        const bool seeded=runMain&&sourceHistoryActive&&!workStatisticsEnabled&&!(diagnosticDecomposition&&RtrDiagnostics::level.load()==2);
        auto* words=reinterpret_cast<uint32_t*>(constants.words.data());words[72]=uint32_t(applicationData.size());words[73]=uint32_t(publication);words[74]=uint32_t(publication>>32);
        const bool mappedSky=dielectricRadiance&&dielectricSky;
        words[75]=(workStatisticsEnabled?1:0)|((specialRoleCount||!alphaMeshes.empty())?2:0)|(instanceCandidates?4:0)|(seeded?8:0)|((maskedScalarCandidates||surfaceCandidateCount)?32:0)
            |(geometryGeneration!=lastGeometryGeneration?16:0)|(roleMaskTraversal?64:0)|(mappedSky?DielectricSkyFlag:0);
        words[76]=uint32_t(timeNanos);words[77]=uint32_t(uint64_t(timeNanos)>>32);words[78]=uint32_t(deltaNanos);words[79]=uint32_t(uint64_t(deltaNanos)>>32);
        words[39]=lightCount;words[51]=lightRoot;words[59]=treeBase;
        const char* disableReuse=std::getenv("RTR_RTXPT_DISABLE_SIGNAL_REUSE");
        const bool retain=retainSignalsAllowed&&!workStatisticsEnabled&&!diagnosticDecomposition
            &&(!disableReuse||std::string(disableReuse)!="1");
        const bool probeFrame=dielectricRadiance&&dielectricProbe.Select(sequence,sceneRevision,width,height,constants.words.data());
        const bool runFiniteFlux=indirect&&mediumTraversalCount&&directionalFlux.Prepared()&&!directionalFlux.triangles.Empty();
        // RR rewrites its result every evaluation. In this case the finite
        // pass must composite again, without entering the RR history.
        const bool reuse=!probeFrame&&!(runFiniteFlux&&reconstruction.requested)&&retain&&retainedSignals
            &&retainedReconstruction==reconstruction.requested&&retainedInputGeneration==inputGeneration
            &&retainedAnalyticGeneration==analyticGeneration
            &&retainedSourceGeneration==sources.work[1]&&retainedMetadata==metadataUploads
            &&std::memcmp(constants.words.data(),retainedConstants.words.data(),72*sizeof(float))==0
            &&std::memcmp(constants.words.data()+80,retainedConstants.words.data()+80,16*sizeof(float))==0
            &&constants.cameraSlots==retainedConstants.cameraSlots
            &&constants.opticalViewport==retainedConstants.opticalViewport
            &&(words[75]&~16u)==(reinterpret_cast<const uint32_t*>(retainedConstants.words.data())[75]&~16u);
        if(reuse){
            RtrGpuProfile::End(command,uploadScope);
            const auto scope=RtrGpuProfile::Begin(command,"NativeLightingReuse",-1);
            if(reconstruction.requested){
                // A repeated camera and unchanged scene have zero screen motion.
                // Do not reuse the nonzero motion from the last changed frame.
                nvrhi::BindingSetDesc unused;
                reconstruction.Bind(command,unused,constants.words.data(),sequence);
                command->clearTextureFloat(reconstruction.guides[3],nvrhi::AllSubresources,nvrhi::Color(0));
            }
            RtrGpuProfile::End(command,scope);
            if(reconstruction.requested)reconstruction.Evaluate(device,command,indirect,constants.words.data(),sequence);
            EncodeOutputs(device,command);++executedFrames;return;
        }
        retainedSignals=false;
        command->writeBuffer(constantBuffer,&constants,sizeof(constants));
        RtrGpuProfile::End(command,uploadScope);
        nvrhi::BindingSetDesc bindings;bindings.bindings={nvrhi::BindingSetItem::ConstantBuffer(0,constantBuffer),
            nvrhi::BindingSetItem::RayTracingAccelStruct(0,tlas),nvrhi::BindingSetItem::StructuredBuffer_SRV(1,scene->GetInstanceBuffer()),
            nvrhi::BindingSetItem::StructuredBuffer_SRV(2,scene->GetGeometryBuffer()),nvrhi::BindingSetItem::StructuredBuffer_SRV(3,scene->GetMaterialBuffer()),
            nvrhi::BindingSetItem::StructuredBuffer_SRV(4,sources.sourceBuffer),nvrhi::BindingSetItem::StructuredBuffer_SRV(5,sources.treeBuffer),
            nvrhi::BindingSetItem::StructuredBuffer_SRV(6,instances),nvrhi::BindingSetItem::StructuredBuffer_SRV(7,baselineCoordinates),
            nvrhi::BindingSetItem::StructuredBuffer_SRV(8,geometryBaselines),nvrhi::BindingSetItem::RawBuffer_SRV(9,application),
            nvrhi::BindingSetItem::StructuredBuffer_SRV(10,lookup),nvrhi::BindingSetItem::StructuredBuffer_SRV(11,analytic),
            nvrhi::BindingSetItem::StructuredBuffer_SRV(12,surfaceMaterials),nvrhi::BindingSetItem::Sampler(0,sampler),
            nvrhi::BindingSetItem::StructuredBuffer_SRV(13,surfaceIndices),
            nvrhi::BindingSetItem::Texture_UAV(0,direct?direct:dummyColor),nvrhi::BindingSetItem::Texture_UAV(1,indirect?indirect:dummyColor),
            nvrhi::BindingSetItem::Texture_UAV(2,geometry?geometry:dummyColor),nvrhi::BindingSetItem::Texture_UAV(3,identity?identity:dummyIdentity),nvrhi::BindingSetItem::RawBuffer_UAV(4,work),
            nvrhi::BindingSetItem::Texture_UAV(12,directionalSpecular?directionalSpecular:dummyColor)};
        for(uint32_t i=0;i<8;++i){if(!textureSamplers[i])throw std::runtime_error("Adapter texture sampler is not configured");bindings.bindings.push_back(nvrhi::BindingSetItem::Sampler(i+1,textureSamplers[i]));}
        nvrhi::BindingSetDesc penumbraBindings;
        if(sunPenumbra){
            penumbraBindings=bindings;
            penumbraBindings.bindings.push_back(nvrhi::BindingSetItem::Texture_SRV(14,sunEvidence));
            penumbraBindings.bindings.push_back(nvrhi::BindingSetItem::Texture_UAV(14,sunPenumbra));
        }
        bindings.bindings.push_back(nvrhi::BindingSetItem::Texture_UAV(13,sunEvidence?sunEvidence:dummyColor));
        const bool runDirectionalFlux=indirect&&mediumTraversalCount&&directionalFlux.Prepared()&&(constants.words[47]>0||constants.words[55]>0);
        nvrhi::BindingSetDesc fluxBindings;
        if(runDirectionalFlux||runFiniteFlux)fluxBindings=bindings;
        nvrhi::IBindingSet* dielectricBinding=nullptr;
        nvrhi::IBindingSet* classifyBinding=nullptr;
        nvrhi::IBindingSet* refineBinding=nullptr;
        nvrhi::BindingSetHandle probeBinding;
        if(dielectricRadiance){
            if(dielectricRefinement){
                nvrhi::BindingSetDesc classify;
                classify.bindings={nvrhi::BindingSetItem::ConstantBuffer(0,constantBuffer),
                    nvrhi::BindingSetItem::Texture_SRV(CoarseDielectricSrv,dielectricRadiance),
                    nvrhi::BindingSetItem::Texture_UAV(RefinementUav,dielectricRefinement),
                    nvrhi::BindingSetItem::RawBuffer_UAV(RefinementJobsUav,dielectricRefinementJobs)};
                classifyBinding=dielectricClassifyBindings.Get(device,classify,dielectricClassifyLayout);
                auto refine=bindings;
                refine.bindings.push_back(nvrhi::BindingSetItem::Texture_SRV(DielectricSkySrv,mappedSky?dielectricSky.Get():fallbackSky));
                refine.bindings.push_back(nvrhi::BindingSetItem::Texture_UAV(RefinementUav,dielectricRefinement));
                refine.bindings.push_back(nvrhi::BindingSetItem::RawBuffer_SRV(RefinementJobsSrv,dielectricRefinementJobs));
                refineBinding=dielectricRefineBindings.Get(device,refine,dielectricRefineLayout);
            }
            bindings.bindings.push_back(nvrhi::BindingSetItem::Texture_UAV(DielectricUav,dielectricRadiance));
            bindings.bindings.push_back(nvrhi::BindingSetItem::Texture_SRV(DielectricSkySrv,mappedSky?dielectricSky.Get():fallbackSky));
            if(probeFrame){
                auto* trace=dielectricProbe.Allocate(device);
                bindings.bindings.push_back(nvrhi::BindingSetItem::RawBuffer_UAV(RtrDielectricProbe::UavSlot,trace));
                probeBinding=device->createBindingSet(bindings,dielectricProbe.layout);
                if(!probeBinding)throw std::runtime_error("Could not allocate dielectric probe bindings");
                dielectricBinding=probeBinding.Get();
                bindings.bindings.pop_back();
            }else dielectricBinding=dielectricDispatchBindings.Get(device,bindings,dielectricLayout);
            bindings.bindings.pop_back();
            bindings.bindings.pop_back();
        }
        auto dispatchDielectric=[&]{
            if(probeFrame)dielectricProbe.Begin(command);
            const auto scope=RtrGpuProfile::Begin(command,"DielectricRadiance",-1);
            nvrhi::ComputeState state;state.pipeline=probeFrame?dielectricProbe.pipeline:dielectricPipeline;
            state.bindings={dielectricBinding,scene->GetDescriptorTable()};
            command->setComputeState(state);command->dispatch((width+7)/8,(height+7)/8);
            RtrGpuProfile::End(command,scope);
            if(probeFrame)dielectricProbe.End(command);
            if(refineBinding){
                const auto refineScope=RtrGpuProfile::Begin(command,"DielectricRefinement",-1);
                command->clearBufferUInt(dielectricRefinementJobs,0);
                const uint32_t headers=(width*height+3)/4;
                const uint32_t strideX=(constants.opticalViewport[0]+width-1)/width;
                const uint32_t strideY=(constants.opticalViewport[1]+height-1)/height;
                nvrhi::ComputeState classify;classify.pipeline=dielectricClassifyPipeline;classify.bindings={classifyBinding};
                command->setComputeState(classify);
                command->dispatch(std::min((headers+63)/64,1024u),(headers+65535)/65536);
                state.pipeline=dielectricRefinePipeline;state.bindings={refineBinding,scene->GetDescriptorTable()};
                command->setComputeState(state);
                command->dispatch((constants.opticalViewport[2]*strideX*strideY+63)/64);
                RtrGpuProfile::End(command,refineScope);
            }
        };
        auto rememberSignals=[&]{
            if(retain){retainedConstants=constants;retainedReconstruction=reconstruction.requested;retainedInputGeneration=inputGeneration;
                retainedAnalyticGeneration=analyticGeneration;
                retainedSourceGeneration=sources.work[1];retainedMetadata=metadataUploads;retainedSignals=true;}
            lastGeometryGeneration=geometryGeneration;
        };
        if(!runMain){
            dispatchDielectric();rememberSignals();++executedFrames;return;
        }
        const bool deep=diagnosticDecomposition&&RtrDiagnostics::level.load()==2;
        if(reconstruction.requested)reconstruction.Bind(command,bindings,constants.words.data(),sequence);
        if(deep){
            // HLSL structured layout: 32 scalar words in RtrReceiver plus valid.
            const size_t bytes=size_t(width)*height*132;
            if(!deepReceivers||deepReceivers->getDesc().byteSize<bytes){
                nvrhi::BufferDesc desc;desc.byteSize=bytes;desc.structStride=132;desc.canHaveUAVs=true;
                desc.initialState=nvrhi::ResourceStates::UnorderedAccess;desc.keepInitialState=true;desc.debugName="DEEP receiver storage";
                auto next=device->createBuffer(desc);if(!next)throw std::runtime_error("Could not allocate DEEP receiver storage");
                Retire(device,{deepReceivers});deepReceivers=next;
            }
            bindings.bindings.push_back(nvrhi::BindingSetItem::StructuredBuffer_UAV(5,deepReceivers));
        }else if(deepReceivers){Retire(device,{deepReceivers});deepReceivers=nullptr;}
        auto binding=dispatchBindings.Get(device,bindings,reconstruction.requested?reconstruction.layout:deep?deepLayout:layout);
        if(workStatisticsEnabled)command->clearBufferUInt(work,0);
        nvrhi::ComputeState state;state.pipeline=pipeline;state.bindings={binding,scene->GetDescriptorTable()};
        const auto dispatchScope=RtrGpuProfile::Begin(command,deep?"DiagnosticDecomposedLighting":"NativeLightingDispatch",-1);
        if(deep){
            state.pipeline=deepPipeline;const uint32_t flags=words[75];
            for(uint32_t phase=1;phase<=3;phase++){
                if((phase==2&&!(mask&1))||(phase==3&&!(mask&2)))continue;
                // Each phase keeps a distinct GPU address. Reusing the same CB
                // within this command list allowed in-flight phases to observe
                // later phase flags on the D3D12 runtime.
                auto& parameters=deepConstants[phase-1];
                if(!parameters){nvrhi::BufferDesc desc;desc.byteSize=sizeof(Constants);desc.isConstantBuffer=true;
                    desc.initialState=nvrhi::ResourceStates::ConstantBuffer;desc.keepInitialState=true;desc.debugName="DEEP phase parameters";
                    parameters=device->createBuffer(desc);if(!parameters)throw std::runtime_error("Could not allocate DEEP phase parameters");}
                words[75]=flags|(phase<<8);command->writeBuffer(parameters,&constants,sizeof(constants));
                bindings.bindings[0]=nvrhi::BindingSetItem::ConstantBuffer(0,parameters);
                auto phaseBinding=phaseBindings[phase-1].Get(device,bindings,deepLayout);
                if(!phaseBinding)throw std::runtime_error("Could not bind DEEP phase parameters");
                state.bindings={phaseBinding,scene->GetDescriptorTable()};
                const char* name=phase==1?"DiagnosticReceiverTrace":phase==2?"DiagnosticDirectIncludingSourcesAndShadows":"DiagnosticGiIncludingSourcesAndShadows";
                const auto scope=RtrGpuProfile::Begin(command,name,-1);command->setComputeState(state);command->dispatch((width+7)/8,(height+7)/8);RtrGpuProfile::End(command,scope);
            }
            words[75]=flags;
        }else if(UseReceiverSignalsPipeline()){
            state.pipeline=receiverSignalsPipeline;command->setComputeState(state);command->dispatch((width+7)/8,(height+7)/8);
        }else{if(reconstruction.requested)state.pipeline=lightCount<=1
                ?(sources.SingleGroup()?reconstruction.sparseOnlySingleGroupPipeline:sources.PairGroups()?reconstruction.sparseOnlyPairGroupPipeline:sources.SmallGroups()?reconstruction.sparseOnlySmallGroupPipeline:reconstruction.sparseOnlyPipeline)
                :(sources.SingleGroup()?reconstruction.singleGroupPipeline:sources.PairGroups()?reconstruction.pairGroupPipeline:sources.SmallGroups()?reconstruction.smallGroupPipeline:reconstruction.pipeline);
            else state.pipeline=lightCount<=1
                ?(sources.SingleGroup()?sparseOnlySingleGroupPipeline:sources.PairGroups()?sparseOnlyPairGroupPipeline:sources.SmallGroups()?sparseOnlySmallGroupPipeline:sparseOnlyPipeline)
                :(sources.SingleGroup()?singleGroupPipeline:sources.PairGroups()?pairGroupPipeline:sources.SmallGroups()?smallGroupPipeline:pipeline);
            if(workStatisticsEnabled&&sources.VisibleMax()){
                const unsigned variant=sources.SingleGroup()?1+(lightCount<=1?2:0):sources.PairGroups()?6+(lightCount<=1?1:0):sources.SmallGroups()?4+(lightCount<=1?1:0):(lightCount<=1?2:0);
                state.pipeline=reconstruction.requested?reconstruction.countedMaxPipelines[variant]:countedMaxPipelines[variant];
            }
            const bool directOnly=!workStatisticsEnabled&&UseDirectPipeline(sources.VisibleMax());
            if(!workStatisticsEnabled){
                const unsigned variant=sources.SingleGroup()?1+(lightCount<=1?2:0):sources.PairGroups()?6+(lightCount<=1?1:0):sources.SmallGroups()?4+(lightCount<=1?1:0):(lightCount<=1?2:0);
                const unsigned maxVariant=variant+(sources.dynamicCount==sources.records.size()?8:0);
                state.pipeline=reconstruction.requested
                    ?(sources.VisibleMax()?reconstruction.maxPipelines[maxVariant]:reconstruction.uninstrumentedPipelines[variant])
                    :(sources.VisibleMax()?maxPipelines[maxVariant]:(directOnly?directPipelines:uninstrumentedPipelines)[variant]);
                if(sources.VisibleMax()){
                    nvrhi::BindingSetDesc history;
                    history.bindings={nvrhi::BindingSetItem::Texture_SRV(0,sourceHistory[sourceHistoryRead]),
                        nvrhi::BindingSetItem::Texture_UAV(0,sourceHistory[1-sourceHistoryRead])};
                    state.bindings.push_back(sourceHistoryBindings[sourceHistoryRead].Get(device,history,sourceHistoryLayout));
                }
            }
            if(sources.ChannelExecutor()){
                const unsigned variant=sources.SingleGroup()?1+(lightCount<=1?2:0):sources.PairGroups()?6+(lightCount<=1?1:0):sources.SmallGroups()?4+(lightCount<=1?1:0):(lightCount<=1?2:0);
                state.pipeline=directOnly?directChannelPipelines[variant]
                    :(reconstruction.requested?reconstruction.channelMaxPipelines:channelMaxPipelines)[(workStatisticsEnabled?8:0)+variant];
            }
            command->setComputeState(state);command->dispatch((width+7)/8,(height+7)/8);}
        RtrGpuProfile::End(command,dispatchScope);
        if(runDirectionalFlux){
            const auto& live=scene->GetSceneGraph()->GetMeshInstances();
            auto bounds=dm::box3::empty();
            for(const auto& [name,roles]:traversalRoles)if(roles&MediumTraversalRole){
                const auto slot=instanceSlots.find(name);
                if(slot==instanceSlots.end()||slot->second>=live.size())continue;
                const auto& instance=live[slot->second];
                const auto local=instance->GetMesh()->objectSpaceBounds;
                if(!RtrDirectionalFlux::ValidBounds(local))continue;
                float pose[12];rtr::upstream::ManagedScene::CopyPublishedCurrentPose(instance,pose);
                for(int i=0;i<dm::box3::numCorners;++i){
                    const auto v=local.getCorner(i);
                    bounds|=dm::float3(v.x*pose[0]+v.y*pose[1]+v.z*pose[2]+pose[3],
                        v.x*pose[4]+v.y*pose[5]+v.z*pose[6]+pose[7],v.x*pose[8]+v.y*pose[9]+v.z*pose[10]+pose[11]);
                }
            }
            directionalFlux.Dispatch(device,command,scene->GetDescriptorTable(),fluxBindings,constants.words.data(),bounds,width,height);
        }
        if(sunPenumbra){
            const auto scope=RtrGpuProfile::Begin(command,"DirectionalEdgePenumbra",-1);
            auto edgeBindings=penumbraDispatchBindings.Get(device,penumbraBindings,penumbraLayout);
            nvrhi::ComputeState edge;edge.pipeline=penumbraPipeline;edge.bindings={edgeBindings,scene->GetDescriptorTable()};
            command->setComputeState(edge);command->dispatch((width+7)/8,(height+7)/8);
            RtrGpuProfile::End(command,scope);
        }
        if(dielectricBinding)dispatchDielectric();
        if(seeded)sourceHistoryRead=1-sourceHistoryRead;
        if(reconstruction.requested)reconstruction.Evaluate(device,command,indirect,constants.words.data(),sequence);
        if(runFiniteFlux)directionalFlux.Dispatch(device,command,scene->GetDescriptorTable(),fluxBindings,constants.words.data(),
            dm::box3::empty(),width,height,reconstruction.requested?reconstruction.result.Get():indirect.Get());
        rememberSignals();
        EncodeOutputs(device,command);++executedFrames;
    }
};
