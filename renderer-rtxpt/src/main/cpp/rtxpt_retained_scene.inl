static std::chrono::steady_clock::time_point ResourceClock(){return RtrDiagnostics::level.load()!=0?std::chrono::steady_clock::now():std::chrono::steady_clock::time_point{};}
// Included inside HostPathTracer. Imports use the resident scene's factory,
// descriptor allocator and texture cache, never SetCurrentScene/SceneLoaded.
uint64_t resourceBlases=0,resourceRetainedBlases=0,resourceTlas=0,resourceWaitNs=0,resourceRecordingNs=0;
uint64_t resourceRefits=0,actualNativeTlasBuilds=0;
uint64_t textureGpuCopiedBytes=0;
uint64_t importerNs=0,textureFinalizeNs=0,materialSyncNs=0,accelerationNs=0,nativeRetirementNs=0;
uint64_t reusedTextureAllocations=0,resourceGpuSamples=0,resourceGpuIntervalNs=0;
std::vector<nvrhi::TimerQueryHandle> resourceGpuQueries;
size_t RepresentedLights() const { return sceneLights.size(); }
std::array<jlong,8> LightSamplingTelemetry() {
    std::array<jlong,8> values={sceneLightingActive()?(sceneLights.size()<=size_t(pathQuality.values[5]+1)?1:2):0,
        jlong(sceneLights.size()),jlong(lightTree.ActiveNodes()),lightStatsEnabled?1:0,0,0,0,0};
    if(lightStatsBuffer){
        nvrhi::BufferDesc desc;desc.byteSize=16;desc.cpuAccess=nvrhi::CpuAccessMode::Read;desc.debugName="RtrLightWorkReadback";
        auto readback=GetDevice()->createBuffer(desc);auto commands=GetDevice()->createCommandList();
        commands->open();commands->copyBuffer(readback,0,lightStatsBuffer,0,16);commands->close();GetDevice()->executeCommandList(commands);
        auto* data=static_cast<const uint32_t*>(GetDevice()->mapBuffer(readback,nvrhi::CpuAccessMode::Read));
        if(!data)throw std::runtime_error("Light work readback failed");
        for(int i=0;i<4;i++)values[4+i]=data[i];GetDevice()->unmapBuffer(readback);
    }
    return values;
}
void CaptureLinear(const std::filesystem::path& path,int signal=0,int hostSignal=-1) {
    auto source=nativeLighting.active?(signal==0?nativeLighting.direct:signal==1?nativeLighting.indirect:nativeLighting.geometry):(m_renderTargets?m_renderTargets->OutputColor:nvrhi::TextureHandle{});
    if(nativeLighting.active){auto& rr=nativeLighting.reconstruction;
        if(signal==1&&rr.requested&&rr.valid)source=rr.result;
        if(signal>=3&&signal<=8)source=rr.guides[signal-3];
        if(signal==9)source=nativeLighting.indirect;
        if(signal==10)source=rr.reducedRaw;
        if(signal==11)source=rr.reducedGuides[0];
        if(signal==12)source=rr.reducedGuides[2];
        if(signal==13)source=rr.reducedGuides[3];
        if(signal==14)source=rr.reducedGuides[4];
        if(signal==15)source=nativeLighting.indirect;
        if(signal==16)source=rr.result;
        if(signal==17)source=rr.reducedGuides[1];
        if(signal==18)source=rr.reducedGuides[5];
    }
    if(hostSignal>=0){if(hostSignal>=8)throw std::invalid_argument("Unknown host signal");source=hostSurface.composition==0?hostAugmentation.outputs[hostSignal]:hostOutputs.outputs[hostSignal];}
    if(!source)throw std::runtime_error("No linear render output");
    auto desc=source->getDesc();
    const unsigned channels=desc.format==nvrhi::Format::RG16_FLOAT?2:desc.format==nvrhi::Format::R32_FLOAT?1:4;
    const bool floating32=desc.format==nvrhi::Format::RGBA32_FLOAT||desc.format==nvrhi::Format::R32_FLOAT;
    if(desc.format!=nvrhi::Format::RGBA16_FLOAT&&desc.format!=nvrhi::Format::RGBA32_FLOAT&&desc.format!=nvrhi::Format::RG16_FLOAT&&desc.format!=nvrhi::Format::R32_FLOAT)throw std::runtime_error("Unsupported linear output format");
    auto staging=GetDevice()->createStagingTexture(desc,nvrhi::CpuAccessMode::Read);
    auto commands=GetDevice()->createCommandList();commands->open();
    commands->copyTexture(staging,nvrhi::TextureSlice(),source,nvrhi::TextureSlice());
    commands->setTextureState(source,nvrhi::AllSubresources,nvrhi::ResourceStates::UnorderedAccess);
    commands->close();GetDevice()->executeCommandList(commands);
    size_t pitch=0;auto* bytes=static_cast<const uint8_t*>(GetDevice()->mapStagingTexture(staging,nvrhi::TextureSlice(),nvrhi::CpuAccessMode::Read,&pitch));
    if(!bytes)throw std::runtime_error("Linear output readback failed");
    std::ofstream output(path,std::ios::binary);output<<"PF\n"<<desc.width<<" "<<desc.height<<"\n-1.0\n";
    for(int y=int(desc.height)-1;y>=0;y--){auto* row=reinterpret_cast<const uint16_t*>(bytes+size_t(y)*pitch);
        for(uint32_t x=0;x<desc.width;x++)for(int c=0;c<3;c++){
            unsigned component=signal==15||signal==16?3:std::min<unsigned>(unsigned(c),channels-1);
            if(floating32){auto f=reinterpret_cast<const float*>(bytes+size_t(y)*pitch)[x*channels+component];output.write(reinterpret_cast<const char*>(&f),4);continue;}
            uint16_t h=row[x*channels+component];int exponent=(h>>10)&31;int mantissa=h&1023;
            float f=exponent==31?(mantissa?NAN:INFINITY):std::ldexp(float(exponent?mantissa+1024:mantissa),exponent?exponent-25:-24);
            if(h&0x8000)f=-f;output.write(reinterpret_cast<const char*>(&f),4);
        }
    }
    GetDevice()->unmapStagingTexture(staging);
    if(!output)throw std::runtime_error("Could not write linear output");
}
std::array<jlong,14> ResourceTelemetry() {
    for(auto query=resourceGpuQueries.begin();query!=resourceGpuQueries.end();){
        if(GetDevice()->pollTimerQuery(*query)){
            resourceGpuIntervalNs+=uint64_t(GetDevice()->getTimerQueryTime(*query)*1e9);++resourceGpuSamples;
            query=resourceGpuQueries.erase(query);
        }else ++query;
    }
    return {jlong(resourceBlases),jlong(resourceRetainedBlases),jlong(resourceTlas),jlong(resourceWaitNs),jlong(resourceRecordingNs),jlong(lightIndexBuilds),jlong(lightIndexBuildNs),jlong(sceneLights.size()),jlong(reusedTextureAllocations),jlong(resourceGpuSamples),jlong(resourceGpuIntervalNs),
        jlong(nativePreparationMaterialVisits),jlong(nativePreparationMeshVisits),jlong(nativePreparationIndexBuilds)};
}
using ResidentMesh=std::shared_ptr<donut::engine::MeshInfo>;
using ResidentMaterial=std::shared_ptr<donut::engine::Material>;
std::unordered_map<std::string,ResidentMesh> residentMeshes;
std::unordered_map<std::string,ResidentMaterial> residentMaterials;
std::unordered_map<donut::engine::Material*,std::vector<ResidentMesh>> residentMaterialMeshes;
std::unordered_map<donut::engine::MeshInfo*,std::vector<donut::engine::SceneGraphNode*>> residentMeshNodes;
std::unordered_map<std::string,donut::engine::SceneGraphNode*> residentInstanceNodes;
std::unordered_set<std::string> candidateDirtyMeshes;
bool candidateLayoutDirty=true;
std::unordered_map<donut::engine::LoadedTexture*,size_t> residentTextureUsers;
uint64_t nativePreparationMaterialVisits=0,nativePreparationMeshVisits=0,nativePreparationIndexBuilds=0;
std::vector<donut::engine::MeshInfo*> retainedIndexMeshes;
std::vector<donut::engine::Material*> retainedIndexMaterials,retainedIndexMeshMaterials;
std::vector<donut::engine::SceneGraphNode*> retainedIndexNodes;
std::vector<donut::engine::SceneGraphNode*> candidateChangedNodes;
static auto MaterialTextures(const ResidentMaterial& material) {
    return std::array{material->baseOrDiffuseTexture,material->metalRoughOrSpecularTexture,
        material->normalTexture,material->emissiveTexture,material->occlusionTexture,material->transmissionTexture};
}
void IndexRetainedResources() {
    RtrDiagnostics::Scope diagnostic(RtrDiagnostics::IndexRebuild);
    resourceArena.Prepare(GetDevice());uploadArena.Prepare(GetDevice());
    resourceRetirement.reserve(16);resourceCommandPool.reserve(8);resourceQueryPool.reserve(16);
    spareMeshBuffers.reserve(64);spareMeshBlases.reserve(64);spareResourceTlas.reserve(16);resourceGpuQueries.reserve(8);
    candidateLayoutDirty=true;
    if(!m_scene){
        residentMeshes.clear();residentMaterials.clear();residentMaterialMeshes.clear();residentTextureUsers.clear();residentMeshNodes.clear();residentInstanceNodes.clear();return;
    }
    auto graph=m_scene->GetSceneGraph();
    // Topology still reassigns dense scene indices. Reconcile every live edge,
    // but retain map nodes and reverse-list capacity for unchanged resources.
    // Clearing these maps used to allocate thousands of nodes for a tiny inflow.
    retainedIndexNodes.assign(graph->GetMeshInstances().size(),nullptr);
    retainedIndexMeshes.assign(graph->GetMeshes().size(),nullptr);
    retainedIndexMaterials.assign(graph->GetMaterials().size(),nullptr);
    for(auto& [mesh,users]:residentMeshNodes)users.clear();
    for(auto& [material,users]:residentMaterialMeshes)users.clear();
    for(auto& [texture,users]:residentTextureUsers)users=0;
    for(auto& instance:graph->GetMeshInstances()){
        auto* node=instance->GetNode();const auto index=size_t(instance->GetInstanceIndex());
        if(index>=retainedIndexNodes.size()||retainedIndexNodes[index])throw std::runtime_error("Invalid dense instance index");
        retainedIndexNodes[index]=node;
        residentMeshNodes[instance->GetMesh().get()].push_back(node);residentInstanceNodes.insert_or_assign(node->GetName(),node);
    }
    for(auto& mesh:graph->GetMeshes()) {
        const auto index=size_t(mesh->globalMeshIndex);
        if(index>=retainedIndexMeshes.size()||retainedIndexMeshes[index])throw std::runtime_error("Invalid dense mesh index");
        retainedIndexMeshes[index]=mesh.get();residentMeshes.insert_or_assign(mesh->name,mesh);
        retainedIndexMeshMaterials.clear();
        for(auto& geometry:mesh->geometries)retainedIndexMeshMaterials.push_back(geometry->material.get());
        std::sort(retainedIndexMeshMaterials.begin(),retainedIndexMeshMaterials.end(),std::less<>{});
        auto last=std::unique(retainedIndexMeshMaterials.begin(),retainedIndexMeshMaterials.end());
        for(auto material=retainedIndexMeshMaterials.begin();material!=last;++material)residentMaterialMeshes[*material].push_back(mesh);
    }
    for(auto& material:graph->GetMaterials()) {
        const auto index=size_t(material->materialID);
        if(index>=retainedIndexMaterials.size()||retainedIndexMaterials[index])throw std::runtime_error("Invalid dense material index");
        retainedIndexMaterials[index]=material.get();residentMaterials.insert_or_assign(material->name,material);
        for(auto& texture:MaterialTextures(material))if(texture)++residentTextureUsers[texture.get()];
    }
    for(auto i=residentInstanceNodes.begin();i!=residentInstanceNodes.end();){
        const auto instance=std::static_pointer_cast<donut::engine::MeshInstance>(i->second->GetLeaf());
        const auto index=size_t(instance->GetInstanceIndex());
        if(index>=retainedIndexNodes.size()||retainedIndexNodes[index]!=i->second||i->first!=i->second->GetName())i=residentInstanceNodes.erase(i);else ++i;
    }
    for(auto i=residentMeshes.begin();i!=residentMeshes.end();){
        const auto index=size_t(i->second->globalMeshIndex);
        if(index>=retainedIndexMeshes.size()||retainedIndexMeshes[index]!=i->second.get()||i->first!=i->second->name)i=residentMeshes.erase(i);else ++i;
    }
    for(auto i=residentMaterials.begin();i!=residentMaterials.end();){
        const auto index=size_t(i->second->materialID);
        if(index>=retainedIndexMaterials.size()||retainedIndexMaterials[index]!=i->second.get()||i->first!=i->second->name)i=residentMaterials.erase(i);else ++i;
    }
    for(auto i=residentMeshNodes.begin();i!=residentMeshNodes.end();){if(i->second.empty())i=residentMeshNodes.erase(i);else ++i;}
    for(auto i=residentMaterialMeshes.begin();i!=residentMaterialMeshes.end();){if(i->second.empty())i=residentMaterialMeshes.erase(i);else ++i;}
    for(auto i=residentTextureUsers.begin();i!=residentTextureUsers.end();){if(!i->second)i=residentTextureUsers.erase(i);else ++i;}
    if(RtrDiagnostics::level.load()!=0)++nativePreparationIndexBuilds;
}
void PatchRetainedResourceIndex(
        const std::vector<std::shared_ptr<donut::engine::SceneGraphNode>>& detached,
        const std::vector<donut::engine::SceneGraphNode*>& attached,
        const std::unordered_map<donut::engine::MeshInfo*,ResidentMesh>& oldMeshes,
        const std::vector<ResidentMesh>& newMeshes) {
    RtrDiagnostics::Scope diagnostic(RtrDiagnostics::IndexRebuild);
    candidateLayoutDirty=true;
    auto active=[this](const ResidentMesh& mesh){return m_scene->GetSceneGraph()->GetMeshes().Contains(mesh);};
    auto removeUser=[](auto& users,const auto& value){
        auto found=std::find(users.begin(),users.end(),value);
        if(found!=users.end()){
            if(found+1!=users.end())*found=std::move(users.back());
            users.pop_back();
        }
    };
    for(const auto& node:detached){
        auto instance=std::dynamic_pointer_cast<donut::engine::MeshInstance>(node->GetLeaf());
        if(!instance)continue;
        auto users=residentMeshNodes.find(instance->GetMesh().get());
        if(users!=residentMeshNodes.end()){
            removeUser(users->second,node.get());
            if(users->second.empty())residentMeshNodes.erase(users);
        }
        auto entry=residentInstanceNodes.find(node->GetName());
        if(entry!=residentInstanceNodes.end()&&entry->second==node.get())residentInstanceNodes.erase(entry);
    }
    for(auto* node:attached){
        auto instance=std::static_pointer_cast<donut::engine::MeshInstance>(node->GetLeaf());
        auto& users=residentMeshNodes[instance->GetMesh().get()];
        if(std::find(users.begin(),users.end(),node)==users.end())users.push_back(node);
        residentInstanceNodes.insert_or_assign(node->GetName(),node);
    }
    for(const auto& [identity,mesh]:oldMeshes)if(!active(mesh)){
        std::unordered_set<donut::engine::Material*> materials;
        for(const auto& geometry:mesh->geometries)if(materials.insert(geometry->material.get()).second){
            auto users=residentMaterialMeshes.find(geometry->material.get());
            if(users!=residentMaterialMeshes.end()){
                removeUser(users->second,mesh);
                if(users->second.empty())residentMaterialMeshes.erase(users);
            }
        }
        auto entry=residentMeshes.find(mesh->name);
        if(entry!=residentMeshes.end()&&entry->second.get()==identity)residentMeshes.erase(entry);
    }
    for(const auto& mesh:newMeshes){
        if(!active(mesh)){
            auto entry=residentMeshes.find(mesh->name);
            if(entry!=residentMeshes.end()&&entry->second==mesh)residentMeshes.erase(entry);
            continue;
        }
        residentMeshes.insert_or_assign(mesh->name,mesh);
        std::unordered_set<donut::engine::Material*> materials;
        for(const auto& geometry:mesh->geometries)if(materials.insert(geometry->material.get()).second){
            auto& users=residentMaterialMeshes[geometry->material.get()];
            if(std::find(users.begin(),users.end(),mesh)==users.end())users.push_back(mesh);
        }
    }
    // Material identities can be retired by a last-mesh removal. Reconcile this
    // inventory and its texture users, without revisiting every mesh/instance.
    // Dense mesh membership was already validated once during retirement.
    auto graph=m_scene->GetSceneGraph();
    retainedIndexMaterials.assign(graph->GetMaterials().size(),nullptr);
    for(auto& [texture,users]:residentTextureUsers)users=0;
    for(const auto& material:graph->GetMaterials()){
        const size_t index=size_t(material->materialID);
        if(index>=retainedIndexMaterials.size()||retainedIndexMaterials[index])throw std::runtime_error("Invalid dense material index");
        retainedIndexMaterials[index]=material.get();residentMaterials.insert_or_assign(material->name,material);
        for(const auto& texture:MaterialTextures(material))if(texture)++residentTextureUsers[texture.get()];
    }
    for(auto entry=residentMaterials.begin();entry!=residentMaterials.end();){
        const size_t index=size_t(entry->second->materialID);
        if(index>=retainedIndexMaterials.size()||retainedIndexMaterials[index]!=entry->second.get()||entry->first!=entry->second->name)
            entry=residentMaterials.erase(entry);
        else ++entry;
    }
    for(auto entry=residentTextureUsers.begin();entry!=residentTextureUsers.end();){
        if(!entry->second)entry=residentTextureUsers.erase(entry);else ++entry;
    }
    if(RtrDiagnostics::level.load()!=0)++nativePreparationIndexBuilds;
}
uint64_t resourceRevision=0;
std::array<uint64_t,19> dynamicWork{}; // validation records/hits/failures, geometry visits, batches, staging bytes, drains, retirements, pending, peak, packet ns, index ns, migration bytes, texture uploads, geometry uploads, clear calls
struct BlasAllocation {uint64_t bytes=0;RtrResourceArena::Storage storage;};
using BlasAllocationIndex=std::unordered_map<nvrhi::rt::IAccelStruct*,BlasAllocation>;
using BufferAllocationIndex=std::unordered_map<nvrhi::IBuffer*,RtrResourceArena::Storage>;
RtrResourceArena resourceArena;
RtrUploadArena uploadArena;
bool uploadPreparationPending=false;
bool memoryPreparationPending=false;
uint64_t memoryPreparationRevision=UINT64_MAX,memoryPreparationSequence=UINT64_MAX;
// Diagnostic counters freeze at OFF. Arena ownership/capacity is operational.
std::array<uint64_t,8> arenaWork{}; // recycle attempts, buffer hits, BLAS hits, payload, capacity, evictions, preparations, pool rejects
struct ResourceTlasStorage {
    nvrhi::rt::AccelStructHandle acceleration;
    nvrhi::BufferHandle subInstances;
    uint64_t accelerationBytes=0;
    std::array<RtrResourceArena::Storage,2> storage;
};
struct RetiredResources {
    RtrUploadArena::Storage upload;
    std::vector<BlasAllocationIndex::node_type> allocationEntries;
    std::vector<BufferAllocationIndex::node_type> bufferAllocationEntries;
    size_t recycleCursor=0;
    bool recycled=false,tlasRecycled=false;
    // NVRHI owns raw upload/scratch chunks on the public command-list object.
    // The queue's internal list tracker alone does not retain those pools.
    std::vector<nvrhi::CommandListHandle> commands;
    bool recycleCommands=true;
    std::vector<ResidentMesh> meshes;
    std::vector<std::shared_ptr<donut::engine::SceneGraphNode>> detachedNodes;
    std::vector<std::shared_ptr<donut::engine::LoadedTexture>> textures;
    std::vector<nvrhi::BufferHandle> buffers;
    std::vector<nvrhi::rt::AccelStructHandle> acceleration;
    std::vector<nvrhi::rt::OpacityMicromapHandle> opacityMaps;
    ResourceTlasStorage tlas;
    std::array<RtrResourceArena::Storage,3> tlasTableStorage;
    nvrhi::EventQueryHandle done,computeDone;
    uint64_t referencedBytes=0,createdNanos=0;
};
std::vector<RetiredResources> resourceRetirement;
std::vector<nvrhi::CommandListHandle> resourceCommandPool;
std::vector<std::pair<nvrhi::EventQueryHandle,nvrhi::EventQueryHandle>> resourceQueryPool;
struct SpareMeshBuffers {
    nvrhi::BufferHandle vertex,index;
    std::shared_ptr<donut::engine::DescriptorHandle> vertexDescriptor,indexDescriptor;
    uint64_t bytes;
    RtrResourceArena::Storage vertexStorage,indexStorage;
};
struct SpareMeshBlas { nvrhi::rt::AccelStructHandle acceleration; uint64_t bytes;RtrResourceArena::Storage storage; };
std::vector<SpareMeshBuffers> spareMeshBuffers;
std::vector<SpareMeshBlas> spareMeshBlases;
uint64_t spareMeshBufferBytes=0,spareMeshBlasBytes=0;
BlasAllocationIndex resourceBlasAllocationBytes;
BufferAllocationIndex resourceBufferAllocations;
std::unordered_map<std::string,nvrhi::rt::AccelStructHandle> preparedMeshBlases;
std::unordered_map<std::string,uint64_t> resourceChangeReasons;
std::vector<ResourceTlasStorage> spareResourceTlas;
ResourceTlasStorage activeResourceTlas,preparedResourceTlas;
std::array<nvrhi::BufferHandle,3> preparedTlasTables;
std::array<RtrResourceArena::Storage,3> activeTlasTableStorage,preparedTlasTableStorage;
bool preparedTlasAliases=false;
uint64_t spareResourceTlasBytes=0,resourceTlasAllocationBytes=0;
nvrhi::rt::IAccelStruct* resourceTlasAllocationIdentity=nullptr;
static bool FitsResourceTlas(const ResourceTlasStorage& value,size_t instances,uint64_t subBytes){
    return value.acceleration&&value.acceleration->getDesc().topLevelMaxInstances>=std::max<size_t>(1,instances)
        &&value.subInstances&&value.subInstances->getDesc().byteSize>=subBytes;
}
// This is the same identity-preserving update rule used by CommitResources.
// Preflight must not reserve a replacement TLAS for an in-place mesh or pose edit.
static bool CompatibleMemoryMesh(const ResidentMesh& a,const ResidentMesh& b){
    if(a->geometries.size()!=b->geometries.size())return false;
    for(size_t g=0;g<a->geometries.size();++g){const auto& x=a->geometries[g];const auto& y=b->geometries[g];
        if(x->numIndices!=y->numIndices||x->numVertices!=y->numVertices||x->material->name!=y->material->name)return false;
    }
    return true;
}
// Called before any graph or GPU mutation. Heap pressure can defer the complete
// request here; it must never fail halfway through publishing a scene revision.
void PrepareResourceTlas(size_t instances,size_t subInstances,size_t meshes){
    if(instances>=(1u<<19)||subInstances>=(1u<<24))throw std::runtime_error("Retained TLAS instance capacity exceeded");
    const auto subBytes=sizeof(SubInstanceData)*std::max<size_t>(1,subInstances);
    ResourceTlasStorage next;size_t pooled=SIZE_MAX;
    if(!FitsResourceTlas(preparedResourceTlas,instances,subBytes)){
        uint64_t best=UINT64_MAX;
        for(size_t i=0;i<spareResourceTlas.size();++i)if(FitsResourceTlas(spareResourceTlas[i],instances,subBytes)
                &&spareResourceTlas[i].accelerationBytes<best){pooled=i;best=spareResourceTlas[i].accelerationBytes;}
        if(pooled!=SIZE_MAX)next=spareResourceTlas[pooled];
    }
    std::vector<nvrhi::MemoryRequirements> requirements;
    std::array<size_t,5> allocations;allocations.fill(SIZE_MAX);
    if(!FitsResourceTlas(preparedResourceTlas,instances,subBytes)&&!next.acceleration){
        nvrhi::rt::AccelStructDesc desc;desc.isTopLevel=true;desc.isVirtual=true;
        desc.topLevelMaxInstances=RtrCapacity::Elements(instances,256,1u<<19);
        desc.buildFlags=nvrhi::rt::AccelStructBuildFlags::PreferFastTrace;
        RtrDiagnostics::Scope allocation(RtrDiagnostics::TlasAllocate,1,0,1,0,0,RtrDiagnostics::revision.load(),RtrDiagnostics::Topology|RtrDiagnostics::CapacityGrowth);
        next.acceleration=GetDevice()->createAccelStruct(desc);
        if(!next.acceleration)throw std::runtime_error("Virtual retained TLAS preparation failed");
        allocations[0]=requirements.size();requirements.push_back(GetDevice()->getAccelStructMemoryRequirements(next.acceleration));
        next.accelerationBytes=requirements.back().size;allocation.event[21]=next.accelerationBytes;
        nvrhi::BufferDesc buffer;buffer.byteSize=sizeof(SubInstanceData)*RtrCapacity::Elements(subInstances,256,1u<<24);
        buffer.structStride=sizeof(SubInstanceData);buffer.canHaveUAVs=true;buffer.isVirtual=true;
        buffer.debugName="Retained sub-instances";buffer.initialState=nvrhi::ResourceStates::Common;buffer.keepInitialState=true;
        next.subInstances=GetDevice()->createBuffer(buffer);
        if(!next.subInstances)throw std::runtime_error("Virtual retained sub-instance preparation failed");
        allocations[1]=requirements.size();requirements.push_back(GetDevice()->getBufferMemoryRequirements(next.subInstances));
    }
    std::array<nvrhi::BufferHandle,3> tables;
    const std::array<nvrhi::BufferHandle,3> current{m_tlasInstanceBuffer,m_tlasInstanceDataBuffer,m_tlasMeshBoundsBuffer};
    const std::array<size_t,3> counts{instances,instances,meshes};
    const std::array<size_t,3> strides{sizeof(nvrhi::rt::InstanceDesc),sizeof(TLASInstanceData),sizeof(TLASObjectBounds)};
    const std::array<const char*,3> names{"TLASInstanceDescs","TLASInstanceData","TLASMeshBounds"};
    for(size_t i=0;i<3;++i){const auto bytes=strides[i]*std::max<size_t>(1,counts[i]);
        if(current[i]&&preparedTlasTables[i]&&current[i]->getDesc().byteSize>=preparedTlasTables[i]->getDesc().byteSize){
            preparedTlasTables[i]=nullptr;preparedTlasTableStorage[i].reset();
        }
        if((current[i]&&current[i]->getDesc().byteSize>=bytes)||(preparedTlasTables[i]&&preparedTlasTables[i]->getDesc().byteSize>=bytes))continue;
        nvrhi::BufferDesc desc;desc.byteSize=strides[i]*RtrCapacity::Elements(counts[i]);desc.structStride=uint32_t(strides[i]);
        desc.isVirtual=true;desc.debugName=names[i];desc.keepInitialState=true;
        desc.canHaveUAVs=i==0;desc.isAccelStructBuildInput=i==0;
        desc.initialState=i==0?nvrhi::ResourceStates::UnorderedAccess:nvrhi::ResourceStates::Common;
        RtrDiagnostics::Scope allocation(RtrDiagnostics::BufferAllocate,1,0,1,0,0,RtrDiagnostics::revision.load(),RtrDiagnostics::CapacityGrowth,desc.byteSize);
        tables[i]=GetDevice()->createBuffer(desc);if(!tables[i])throw std::runtime_error("Virtual TLAS table preparation failed");
        allocations[i+2]=requirements.size();requirements.push_back(GetDevice()->getBufferMemoryRequirements(tables[i]));
    }
    std::vector<RtrResourceArena::Storage> storage;
    if(!requirements.empty()&&!resourceArena.Reserve(requirements,storage)){memoryPreparationPending=true;throw RtrResourcePending();}
    if(allocations[0]!=SIZE_MAX){next.storage={storage[allocations[0]],storage[allocations[1]]};
        RtrDiagnostics::Scope bind(RtrDiagnostics::PlacedBind,2);
        if(!GetDevice()->bindAccelStructMemory(next.acceleration,next.storage[0]->Heap(),next.storage[0]->Offset())
                ||!GetDevice()->bindBufferMemory(next.subInstances,next.storage[1]->Heap(),next.storage[1]->Offset()))throw std::runtime_error("Placed retained TLAS binding failed");
        preparedTlasAliases=true;
    }
    for(size_t i=0;i<3;++i)if(tables[i]){auto lease=storage[allocations[i+2]];
        RtrDiagnostics::Scope bind(RtrDiagnostics::PlacedBind,1);
        if(!GetDevice()->bindBufferMemory(tables[i],lease->Heap(),lease->Offset()))throw std::runtime_error("Placed TLAS table binding failed");
        preparedTlasAliases=true;
        preparedTlasTables[i]=std::move(tables[i]);preparedTlasTableStorage[i]=std::move(lease);
    }
    if(next.acceleration){preparedResourceTlas=std::move(next);
        if(pooled!=SIZE_MAX){spareResourceTlasBytes-=spareResourceTlas[pooled].accelerationBytes+spareResourceTlas[pooled].subInstances->getDesc().byteSize;
            spareResourceTlas.erase(spareResourceTlas.begin()+pooled);}
    }
}
void CreateResourceTlas(RetiredResources& retired){
    const auto& instances=m_scene->GetSceneGraph()->GetMeshInstances();
    const size_t instanceCount=instances.size();
    uint64_t subInstanceCount=0;
    for(const auto& instance:instances)subInstanceCount+=instance->GetMesh()->geometries.size();
    if(instanceCount>=(1u<<19)||subInstanceCount>=(1u<<24))throw std::runtime_error("Retained TLAS instance capacity exceeded");
    const uint64_t subInstanceBytes=sizeof(SubInstanceData)*std::max<uint64_t>(1,subInstanceCount);
    if(m_topLevelAS&&resourceTlasAllocationBytes&&m_topLevelAS.Get()==resourceTlasAllocationIdentity){
        retired.tlas=activeResourceTlas;
        retired.referencedBytes+=resourceTlasAllocationBytes;
    }
    ResourceTlasStorage next;
    if(FitsResourceTlas(preparedResourceTlas,instanceCount,subInstanceBytes)){next=std::move(preparedResourceTlas);preparedResourceTlas={};}
    for(auto& slot:spareResourceTlas)if(!next.acceleration&&slot.acceleration
            &&slot.acceleration->getDesc().topLevelMaxInstances>=std::max<size_t>(1,instanceCount)
            &&slot.subInstances&&slot.subInstances->getDesc().byteSize>=subInstanceBytes){
        spareResourceTlasBytes-=slot.accelerationBytes+slot.subInstances->getDesc().byteSize;
        next=std::move(slot);slot={};break;
    }
    if(!next.acceleration){
        nvrhi::rt::AccelStructDesc desc;desc.isTopLevel=true;desc.topLevelMaxInstances=RtrCapacity::Elements(instanceCount,256,1u<<19);
        desc.buildFlags=nvrhi::rt::AccelStructBuildFlags::PreferFastTrace;
        {
            RtrDiagnostics::Scope allocation(RtrDiagnostics::TlasAllocate,1,0,1,0,0,RtrDiagnostics::revision.load(),RtrDiagnostics::Topology|RtrDiagnostics::CapacityGrowth);
            next.acceleration=GetDevice()->createAccelStruct(desc);
            if(!next.acceleration)throw std::runtime_error("Retained TLAS allocation failed");
            next.accelerationBytes=GetDevice()->getAccelStructMemoryRequirements(next.acceleration).size;
            allocation.event[21]=next.accelerationBytes;
        }
        nvrhi::BufferDesc buffer;buffer.byteSize=sizeof(SubInstanceData)*RtrCapacity::Elements(subInstanceCount,256,1u<<24);buffer.debugName="Retained sub-instances";
        buffer.structStride=sizeof(SubInstanceData);buffer.canHaveUAVs=true;
        buffer.initialState=nvrhi::ResourceStates::Common;buffer.keepInitialState=true;
        {
            RtrDiagnostics::Scope allocation(RtrDiagnostics::BufferAllocate,1,0,1,0,0,RtrDiagnostics::revision.load(),RtrDiagnostics::Topology|RtrDiagnostics::CapacityGrowth,subInstanceBytes);
            next.subInstances=GetDevice()->createBuffer(buffer);
            if(!next.subInstances)throw std::runtime_error("Retained sub-instance allocation failed");
        }
    }else{
        RtrDiagnostics::Scope hit(RtrDiagnostics::PoolAcquire,2,0,1,0,0,RtrDiagnostics::revision.load(),RtrDiagnostics::Topology|RtrDiagnostics::PoolHit,
            next.accelerationBytes+next.subInstances->getDesc().byteSize);
    }
    activeResourceTlas=next;
    m_topLevelAS=std::move(next.acceleration);m_subInstanceBuffer=std::move(next.subInstances);
    std::array<nvrhi::BufferHandle*,3> tables{std::addressof(m_tlasInstanceBuffer),std::addressof(m_tlasInstanceDataBuffer),std::addressof(m_tlasMeshBoundsBuffer)};
    for(size_t i=0;i<3;++i)if(preparedTlasTables[i]){
        if(*tables[i])retired.buffers.push_back(*tables[i]);retired.tlasTableStorage[i]=std::move(activeTlasTableStorage[i]);
        *tables[i]=std::move(preparedTlasTables[i]);activeTlasTableStorage[i]=std::move(preparedTlasTableStorage[i]);
        m_tlasInstanceDataDirty=true;
    }
    resourceTlasAllocationBytes=next.accelerationBytes;resourceTlasAllocationIdentity=m_topLevelAS.Get();
    m_subInstanceCount=uint32_t(subInstanceCount);
    m_subInstanceData.assign(m_subInstanceCount,SubInstanceData::Default());
}
static bool CompatibleBlas(const nvrhi::rt::AccelStructDesc& a,const nvrhi::rt::AccelStructDesc& b){
    if(a.isTopLevel||b.isTopLevel||a.buildFlags!=b.buildFlags||a.bottomLevelGeometries.size()!=b.bottomLevelGeometries.size())return false;
    for(size_t g=0;g<a.bottomLevelGeometries.size();g++){
        const auto& x=a.bottomLevelGeometries[g];const auto& y=b.bottomLevelGeometries[g];
        if(x.geometryType!=nvrhi::rt::GeometryType::Triangles||y.geometryType!=x.geometryType||x.flags!=y.flags||x.useTransform||y.useTransform)return false;
        const auto& u=x.geometryData.triangles;const auto& v=y.geometryData.triangles;
        if(u.indexFormat!=v.indexFormat||u.vertexFormat!=v.vertexFormat||u.indexCount!=v.indexCount||u.vertexCount!=v.vertexCount||u.vertexStride!=v.vertexStride
            ||u.opacityMicromap||v.opacityMicromap||u.numOmmUsageCounts||v.numOmmUsageCounts)return false;
    }
    return true;
}
void RecycleMesh(const ResidentMesh& mesh,RetiredResources& retired){
    if(RtrDiagnostics::level.load())++arenaWork[0];
    RtrDiagnostics::Scope pool(RtrDiagnostics::PoolAcquire,1,0,1,0,RtrDiagnostics::Identity(mesh->name));
    auto b=mesh->buffers;
    uint64_t bytes=0;
    // A transaction page may still contain live meshes. Never put its buffers
    // into the independent-object cache; BLAS ownership remains independent.
    if(b&&b->vertexBuffer&&b->indexBuffer&&b->vertexBuffer->getDesc().debugName=="Retained mutable vertices"){
    bytes=b->vertexBuffer->getDesc().byteSize+b->indexBuffer->getDesc().byteSize;
    // Cache only reusable vertex/index ownership. Keeping the whole BufferGroup
    // made a later pool erase destroy its instance descriptor on admission.
    while(!spareMeshBuffers.empty()&&(spareMeshBuffers.size()>=64||spareMeshBufferBytes+bytes>64*1024*1024)){
        auto& old=spareMeshBuffers.front();auto oldBytes=old.bytes;
        RtrDiagnostics::Scope evict(RtrDiagnostics::PoolEvict,1,0,1,0,0,0,0,oldBytes);
        if(!nativeLighting.reclaimer->Enqueue(old,oldBytes))break;
        if(RtrDiagnostics::level.load())++arenaWork[5];
        spareMeshBuffers.erase(spareMeshBuffers.begin());spareMeshBufferBytes-=oldBytes;
    }
    if(spareMeshBuffers.size()<64&&spareMeshBufferBytes+bytes<=64*1024*1024){
        auto storage=[&](nvrhi::IBuffer* buffer){for(auto& entry:retired.bufferAllocationEntries)if(!entry.empty()&&entry.key()==buffer)return entry.mapped();return RtrResourceArena::Storage{};};
        spareMeshBuffers.push_back({b->vertexBuffer,b->indexBuffer,b->vertexBufferDescriptor,b->indexBufferDescriptor,bytes,storage(b->vertexBuffer),storage(b->indexBuffer)});spareMeshBufferBytes+=bytes;
    }else if(RtrDiagnostics::level.load())++arenaWork[7];
    }
    auto as=mesh->accelStruct;
    if(as&&!as->isCompacted()&&as->getDesc().debugName=="Retained mutable BLAS"){
        auto allocation=std::find_if(retired.allocationEntries.begin(),retired.allocationEntries.end(),[&](const auto& entry){return !entry.empty()&&entry.key()==as.Get();});
        if(allocation!=retired.allocationEntries.end()){
            bytes=allocation->mapped().bytes;
            while(!spareMeshBlases.empty()&&(spareMeshBlases.size()>=64||spareMeshBlasBytes+bytes>64*1024*1024)){
                auto& old=spareMeshBlases.front();auto oldBytes=old.bytes;
                RtrDiagnostics::Scope evict(RtrDiagnostics::PoolEvict,1,0,1,0,0,0,0,oldBytes);
                if(!nativeLighting.reclaimer->Enqueue(old,oldBytes))break;
                if(RtrDiagnostics::level.load())++arenaWork[5];
                spareMeshBlases.erase(spareMeshBlases.begin());spareMeshBlasBytes-=oldBytes;
            }
            if(spareMeshBlases.size()<64&&spareMeshBlasBytes+bytes<=64*1024*1024){spareMeshBlases.push_back({as,bytes,allocation->mapped().storage});spareMeshBlasBytes+=bytes;}
            else if(RtrDiagnostics::level.load())++arenaWork[7];
        }
    }
}
nvrhi::CommandListHandle ResourceCommand(){
    RtrDiagnostics::Scope acquire(RtrDiagnostics::CommandAcquire);
    while(!resourceCommandPool.empty()){
        auto command=std::move(resourceCommandPool.back());resourceCommandPool.pop_back();
        if(command)return command;
    }
    if(RtrDiagnostics::level.load()!=0)++dynamicWork[18];
    auto command=GetDevice()->createCommandList(nvrhi::CommandListParameters().setScratchChunkSize(4*1024*1024).setScratchMaxMemory(64*1024*1024));
    if(!command)throw std::runtime_error("Resource command-list allocation failed");
    return command;
}
void PollResourceRetirement(){
    RtrDiagnostics::Scope diagnostic(RtrDiagnostics::ResourceRetirement);
    // The generation ring is bounded to 16. Independently recycle every fence-
    // ready TLAS, even when an older generation still has hundreds of meshes or
    // the background destruction queue is full. Queue order permits stopping at
    // the first incomplete generation; no in-flight resource can enter the pool.
    for(auto& retired:resourceRetirement){
        if(retired.tlasRecycled&&!retired.upload)continue;
        bool completed;{RtrDiagnostics::Scope poll(RtrDiagnostics::CompletionPoll,2);completed=GetDevice()->pollEventQuery(retired.done)&&GetDevice()->pollEventQuery(retired.computeDone);}
        if(!completed)break;
        if(retired.tlas.acceleration&&retired.tlas.subInstances){
                const auto bytes=retired.tlas.accelerationBytes+retired.tlas.subInstances->getDesc().byteSize;
                if(spareResourceTlasBytes+bytes<=64*1024*1024){
                    auto slot=std::find_if(spareResourceTlas.begin(),spareResourceTlas.end(),[](const auto& value){return !value.acceleration;});
                    if(slot!=spareResourceTlas.end()){*slot=std::move(retired.tlas);spareResourceTlasBytes+=bytes;}
                    else if(spareResourceTlas.size()<16){spareResourceTlas.push_back(std::move(retired.tlas));spareResourceTlasBytes+=bytes;}
                }
        }
        retired.tlasRecycled=true;retired.upload.reset();
    }
    size_t budget=2;
    for(auto i=resourceRetirement.begin();i!=resourceRetirement.end()&&budget&&nativeLighting.reclaimer->Available();){
        bool completed;{RtrDiagnostics::Scope poll(RtrDiagnostics::CompletionPoll,2);completed=GetDevice()->pollEventQuery(i->done)&&GetDevice()->pollEventQuery(i->computeDone);}
        if(!completed)break;
        if(!i->recycled){
            size_t recycleBudget=8;
            while(i->recycleCursor<i->meshes.size()&&recycleBudget--){RecycleMesh(i->meshes[i->recycleCursor],*i);++i->recycleCursor;}
            // Preserve the cursor and this completed generation for the next
            // observation, including meshes after the first bounded batch.
            if(i->recycleCursor<i->meshes.size())break;
            i->recycled=true;
        }
        if(i->recycleCommands){
            for(auto& command:i->commands)if(command&&resourceCommandPool.size()<8)resourceCommandPool.push_back(std::move(command));
            // RecycleMesh can fill the release queue after Available() succeeded.
            // A rejected Enqueue leaves this generation for a later poll; its
            // moved command handles must never be inserted into the pool again.
            i->recycleCommands=false;
        }
        const bool cacheQueries=resourceQueryPool.size()<16;auto done=cacheQueries?i->done:nvrhi::EventQueryHandle{};auto computeDone=cacheQueries?i->computeDone:nvrhi::EventQueryHandle{};
        {RtrDiagnostics::Scope enqueue(RtrDiagnostics::ReclaimEnqueue,1,i->referencedBytes);
            if(!nativeLighting.reclaimer->Enqueue(*i,i->referencedBytes))break;}
        if(cacheQueries)resourceQueryPool.emplace_back(std::move(done),std::move(computeDone));
        i=resourceRetirement.erase(i);--budget;if(RtrDiagnostics::level.load()!=0)++dynamicWork[7];
    }
    if(RtrDiagnostics::level.load()!=0)dynamicWork[8]=resourceRetirement.size();
}
void RetireResources(RetiredResources&& value){
    value.allocationEntries.reserve(value.meshes.size());
    value.bufferAllocationEntries.reserve(value.meshes.size()*2);
    if(!resourceQueryPool.empty()){
        value.done=std::move(resourceQueryPool.back().first);value.computeDone=std::move(resourceQueryPool.back().second);resourceQueryPool.pop_back();
    }else{value.done=GetDevice()->createEventQuery();value.computeDone=GetDevice()->createEventQuery();}
    value.createdNanos=RtrDiagnostics::level.load()!=0?RtrDiagnostics::Now():0;
    std::unordered_set<nvrhi::IBuffer*> referencedBuffers;
    for(auto& mesh:value.meshes)if(auto& b=mesh->buffers;b){
        for(auto* buffer:{b->vertexBuffer.Get(),b->indexBuffer.Get()})if(auto allocation=resourceBufferAllocations.find(buffer);allocation!=resourceBufferAllocations.end())
            value.bufferAllocationEntries.push_back(resourceBufferAllocations.extract(allocation));
        for(auto* buffer:{b->vertexBuffer.Get(),b->indexBuffer.Get()})
            if(buffer&&referencedBuffers.insert(buffer).second)value.referencedBytes+=buffer->getDesc().byteSize;
        auto as=resourceBlasAllocationBytes.find(mesh->accelStruct.Get());if(as!=resourceBlasAllocationBytes.end()){
            value.referencedBytes+=as->second.bytes;value.allocationEntries.push_back(resourceBlasAllocationBytes.extract(as));
        }
    }
    for(auto& buffer:value.buffers)if(buffer&&referencedBuffers.insert(buffer.Get()).second)value.referencedBytes+=buffer->getDesc().byteSize;
    if(!value.done||!value.computeDone)throw std::runtime_error("Resource retirement event allocation failed");
    GetDevice()->setEventQuery(value.done,nvrhi::CommandQueue::Graphics);
    GetDevice()->setEventQuery(value.computeDone,nvrhi::CommandQueue::Compute);resourceRetirement.push_back(std::move(value));
    if(RtrDiagnostics::level.load()!=0){dynamicWork[8]=resourceRetirement.size();dynamicWork[9]=std::max<uint64_t>(dynamicWork[9],resourceRetirement.size());}
}
uint32_t ValidateGeometry(const std::string& key){
    const bool diagnostic=RtrDiagnostics::level.load()!=0;if(diagnostic)++dynamicWork[0];
    auto separator=key.rfind(':');if(separator==std::string::npos)throw std::invalid_argument("Invalid geometry identity");
    auto mesh=residentMeshes.find(key.substr(0,separator));
    size_t consumed=0;auto index=std::stoul(key.substr(separator+1),&consumed);
    if(mesh==residentMeshes.end()||consumed!=key.size()-separator-1||index>=mesh->second->geometries.size()){
        if(diagnostic)++dynamicWork[2];throw std::invalid_argument("Unknown native-lighting geometry: "+key);
    }
    if(diagnostic){++dynamicWork[1];++dynamicWork[3];}return mesh->second->geometries[index]->numIndices;
}
void ApplyResources(const std::filesystem::path& manifest,
                    std::unordered_map<std::string,donut::engine::SceneGraphNode*>& nodes) {
    if(!managedLighting||!m_scene)throw std::logic_error("Resource updates require a managed retained scene");
    Json::Value json;std::ifstream input(manifest);
    if(!input||!(input>>json))throw std::runtime_error("Could not read retained delta");
    auto stageBegin=ResourceClock();
    auto imported=[&]{RtrDiagnostics::Scope diagnostic(RtrDiagnostics::Import);return m_scene->HostImport(std::filesystem::u8path(json["model"].asString()));}();
    importerNs+=std::chrono::duration_cast<std::chrono::nanoseconds>(ResourceClock()-stageBegin).count();
    stageBegin=ResourceClock();{RtrDiagnostics::Scope diagnostic(RtrDiagnostics::Textures);m_scene->HostFinalizeTextures(*GetCommonPasses());}
    textureFinalizeNs+=std::chrono::duration_cast<std::chrono::nanoseconds>(ResourceClock()-stageBegin).count();
    CommitResources(imported,json,nodes,false);
}
void CommitResources(const donut::engine::SceneImportResult& imported,const Json::Value& json,
        std::unordered_map<std::string,donut::engine::SceneGraphNode*>& nodes,bool memory,nvrhi::CommandListHandle staged={}) {
    auto stageBegin=ResourceClock();
    auto graph=m_scene->GetSceneGraph();
    std::optional<RtrDiagnostics::Scope> commitPart;commitPart.emplace(RtrDiagnostics::ResourceIndex);
    auto& meshes=residentMeshes;auto& materials=residentMaterials;
    RetiredResources retiredGeneration;
    std::vector<ResidentMesh> localMeshes;
    std::unordered_map<donut::engine::MeshInfo*,nvrhi::rt::AccelStructHandle> refitSources;
    std::unordered_map<std::string,ResidentMesh> affectedMeshes;
    std::vector<ResidentMesh> newIndexMeshes;
    std::vector<ResidentMaterial> changedMaterials;
    std::vector<std::shared_ptr<donut::engine::LoadedTexture>> oldTextures;
    struct FailedCommitGuard {
        nvrhi::IDevice* device;nvrhi::CommandListHandle command;bool armed=true;int exceptions=std::uncaught_exceptions();
        ~FailedCommitGuard(){if(armed&&std::uncaught_exceptions()>exceptions){
            donut::engine::HostSceneTables::Invalidate();
            try{device->waitForIdle();}catch(...){} }
        }
    } failureGuard{GetDevice()};
    auto gather=[&](auto&& self,const std::shared_ptr<donut::engine::SceneGraphNode>& node)->void {
        if(auto instance=std::dynamic_pointer_cast<donut::engine::MeshInstance>(node->GetLeaf())) {
            auto mesh=instance->GetMesh();auto old=meshes.find(mesh->name);
            RtrDiagnostics::Scope plan(RtrDiagnostics::ResourceIndex,1,mesh->totalIndices/3,1,0,
                RtrDiagnostics::Identity(mesh->name),RtrDiagnostics::revision.load(),resourceChangeReasons.count(mesh->name)?resourceChangeReasons.at(mesh->name):(old==meshes.end()?RtrDiagnostics::Inflow:RtrDiagnostics::Topology));
            bool compatible=memory&&old!=meshes.end()&&CompatibleMemoryMesh(old->second,mesh);
            if(compatible){
                auto target=std::static_pointer_cast<MeshInfoEx>(old->second);
                auto next=std::static_pointer_cast<MeshInfoEx>(mesh);
                if(m_ommBaker)m_ommBaker->HostInvalidateMesh(*target);
                RtrProtocolTrace::Record(6,0,0,target->HostRefitFrames,target->accelStruct?uint32_t(target->accelStruct->getDesc().buildFlags):0,
                    RtrDiagnostics::frame.load(),resourceRevision,0,0,0,
                    (target->HostIndices?1:0)|(next->HostIndices?2:0)|((target->HostIndices&&next->HostIndices&&*target->HostIndices==*next->HostIndices)?4:0));
                if(target->HostIndices&&next->HostIndices&&*target->HostIndices==*next->HostIndices&&target->HostRefitFrames<16&&target->accelStruct
                    &&(target->accelStruct->getDesc().buildFlags&nvrhi::rt::AccelStructBuildFlags::AllowUpdate)!=0)
                    refitSources.emplace(target.get(),target->accelStruct);
                retiredGeneration.meshes.push_back(std::make_shared<MeshInfoEx>(*target));
                int meshIndex=target->globalMeshIndex;
                for(size_t g=0;g<mesh->geometries.size();g++)mesh->geometries[g]->globalGeometryIndex=target->geometries[g]->globalGeometryIndex;
                static_cast<donut::engine::MeshInfo&>(*target)=*mesh;target->globalMeshIndex=meshIndex;
                target->HostIndices=next->HostIndices;target->HostDynamicCoverage|=next->HostDynamicCoverage;
                target->HostCoverageCandidates|=next->HostCoverageCandidates;
                target->AccelStructOMM=nullptr;target->OpacityMicroMaps.clear();target->InvalidateOmmCpuInputIdentity();
                mesh=target;localMeshes.push_back(mesh);
            }else{
                if(old!=meshes.end())retiredGeneration.meshes.push_back(old->second);
                newIndexMeshes.push_back(mesh);
            }
            meshes[mesh->name]=mesh;affectedMeshes[mesh->name]=mesh;
        }
        for(uint32_t i=0;i<node->GetNumChildren();i++)self(self,node->GetChild(i)->shared_from_this());
    };
    gather(gather,imported.rootNode);
    // Validate references and transforms before changing the published graph.
    for(auto& record:json["nodes"]) {
        if(!meshes.count(record["mesh"].asString())||record["transform"].size()!=12)throw std::runtime_error("Invalid retained mesh reference");
        for(auto& v:record["transform"])if(!std::isfinite(v.asDouble()))throw std::runtime_error("Non-finite retained transform");
    }

    // Both producer queues finish before retired scene generations are released.
    // External output leases own separate textures, independent of scene storage.
    if(RtrDiagnostics::level.load()!=0)ResourceTelemetry();
    // No CPU fence/device wait occurs in a dynamic resource commit.
    const auto recordBegin=ResourceClock();
    std::unordered_map<std::string,bool> invalidated;
    std::unordered_map<std::string,bool> coverageChanged;
    std::unordered_set<donut::engine::MeshInfo*> domainChangedMeshes;
    for(auto& name:json["coverageChangedMaterials"])coverageChanged[name.asString()]=true;
    for(auto& source:imported.materials) {
        if(RtrDiagnostics::level.load()!=0)++nativePreparationMaterialVisits;
        auto found=materials.find(source->name);
        if(found==materials.end()){
            materials[source->name]=source;GetMaterialsBaker()->HostImportMaterial(*source);changedMaterials.push_back(source);continue;
        }
        auto target=found->second;auto* pt=PTMaterial::SafeCast(target);
        bool before=pt&&pt->EnableAlphaTesting;
        bool alphaTextureChanged=coverageChanged[source->name];
        bool alphaValuesChanged=target->alphaCutoff!=source->alphaCutoff || target->opacity!=source->opacity;
        auto from=MaterialTextures(source),to=MaterialTextures(target);
        for(auto& texture:to)if(texture){oldTextures.push_back(texture);auto user=residentTextureUsers.find(texture.get());
            if(user!=residentTextureUsers.end()&&--user->second==0)residentTextureUsers.erase(user);}
        auto domain=target->domain;auto constants=target->materialConstants;int id=target->materialID;
        static_cast<donut::engine::Material&>(*target)=*source;target->materialConstants=constants;target->materialID=id;
        GetMaterialsBaker()->HostImportMaterial(*target);pt=PTMaterial::SafeCast(target);
        changedMaterials.push_back(target);
        for(auto& texture:MaterialTextures(target))if(texture)++residentTextureUsers[texture.get()];
        bool after=pt&&pt->EnableAlphaTesting;
        // Ordinary non-opaque geometry already yields candidates to current alpha
        // testing. Pixel/cutoff edits invalidate baked coverage, not that geometry.
        // Native queries resolve current coverage in the candidate shader. NEE
        // eligibility is shading metadata, not a native geometry build flag.
        invalidated[source->name]=!nativeLighting.active&&(before!=after||domain!=target->domain);
        if(alphaTextureChanged||alphaValuesChanged||domain!=target->domain)for(auto& mesh:residentMaterialMeshes[target.get()]){
            auto* extended=static_cast<MeshInfoEx*>(mesh.get());extended->HostDynamicCoverage=true;extended->HostCoverageCandidates=true;
            if(m_ommBaker)m_ommBaker->HostInvalidateMesh(*extended);
            candidateDirtyMeshes.insert(mesh->name);
            if(extended->AccelStructOMM){retiredGeneration.acceleration.push_back(extended->AccelStructOMM);
                retiredGeneration.opacityMaps.insert(retiredGeneration.opacityMaps.end(),extended->OpacityMicroMaps.begin(),extended->OpacityMicroMaps.end());
                extended->AccelStructOMM=nullptr;extended->OpacityMicroMaps.clear();m_tlasInstanceDataDirty=true;nativeTlasDirty=true;}
            extended->InvalidateOmmCpuInputIdentity();
        }
        if(domain!=target->domain)for(auto& mesh:residentMaterialMeshes[target.get()])domainChangedMeshes.insert(mesh.get());
        // A mixed geometry/coverage transaction already selected the replacement
        // mesh. An old reverse edge must not overwrite it with the retired mesh.
        if(invalidated[source->name])for(auto& mesh:residentMaterialMeshes[target.get()])affectedMeshes.try_emplace(mesh->name,mesh);
    }
    for(auto& [name,mesh]:affectedMeshes){
    if(RtrDiagnostics::level.load()!=0)++nativePreparationMeshVisits;
    for(auto& geometry:mesh->geometries) {
        auto found=materials.find(geometry->material->name);
        if(found==materials.end())throw std::runtime_error("Missing retained material");
        geometry->material=found->second;
        if(invalidated[found->first]) {
            if(mesh->accelStruct)retiredGeneration.acceleration.push_back(mesh->accelStruct);
            mesh->accelStruct=nullptr;
            if(auto* extended=dynamic_cast<MeshInfoEx*>(mesh.get())) {
                if(extended->AccelStructOMM)retiredGeneration.acceleration.push_back(extended->AccelStructOMM);
                retiredGeneration.opacityMaps.insert(retiredGeneration.opacityMaps.end(),extended->OpacityMicroMaps.begin(),extended->OpacityMicroMaps.end());
                extended->AccelStructOMM=nullptr;extended->OpacityMicroMaps.clear();extended->InvalidateOmmCpuInputIdentity();
            }
        }

    }
    }
    bool topology=false,transformsChanged=false;
    std::vector<donut::engine::SceneGraphNode*> attachedIndexNodes;
    std::unordered_map<donut::engine::MeshInfo*,ResidentMesh> detachedMeshes;
    auto detach=[&](donut::engine::SceneGraphNode* node){
        auto retained=node->shared_from_this();
        if(auto instance=std::dynamic_pointer_cast<donut::engine::MeshInstance>(retained->GetLeaf()))
            detachedMeshes.try_emplace(instance->GetMesh().get(),instance->GetMesh());
        // A previous submitted TLAS still contains these BLAS addresses and
        // bindless indices. Keep both the instance and its mesh until both
        // producer queues reach this transaction's retirement point.
        retiredGeneration.detachedNodes.push_back(retained);graph->Detach(retained);
    };
    for(auto& record:json["removeNodes"]) {
        auto found=nodes.find(record.asString());if(found!=nodes.end()){detach(found->second);nodes.erase(found);topology=true;}
    }
    std::vector<donut::engine::SceneGraphNode*> changedLeafNodes;
    for(auto* mesh:domainChangedMeshes){auto users=residentMeshNodes.find(mesh);if(users!=residentMeshNodes.end())changedLeafNodes.insert(changedLeafNodes.end(),users->second.begin(),users->second.end());}
    for(auto& record:json["nodes"]) {
        std::string name=record["name"].asString();auto mesh=meshes.at(record["mesh"].asString());
        auto found=nodes.find(name);std::shared_ptr<donut::engine::SceneGraphNode> node;
        if(found!=nodes.end()) {
            auto instance=std::dynamic_pointer_cast<donut::engine::MeshInstance>(found->second->GetLeaf());
            if(instance&&instance->GetMesh()==mesh)node=found->second->shared_from_this();
            else{detach(found->second);topology=true;}
        }
        if(!node){node=graph->AttachLeafNode(graph->GetRootNode(),std::make_shared<MeshInstanceEx>(mesh));node->SetName(name);attachedIndexNodes.push_back(node.get());topology=true;}
        double p[12];for(int i=0;i<12;i++)p[i]=record["transform"][i].asDouble();
        auto transform=node->GetLocalToParentTransform();bool same=true;
        for(int row=0;row<3;row++)for(int column=0;column<4;column++)same&=std::abs((column==3?transform.m_translation[row]:transform.m_linear[column][row])-p[row*4+column])<1e-12;
        if(!same){RtrSetInstanceTransform(node.get(),p);transformsChanged=true;}
        if(affectedMeshes.count(mesh->name))changedLeafNodes.push_back(node.get());
        nodes.insert_or_assign(name,node.get());
    }
    if(!changedLeafNodes.empty()&&(topology||!graph->HostRefreshLeafNodes(changedLeafNodes)))for(auto* node:changedLeafNodes)node->InvalidateContent();
    m_scene->HostReleaseModels();
    // Compatible RGB-only edits retain the OMM inputs and pending jobs as well as the AS.
    // Queue tasks re-check each mesh's coverage eligibility before publishing.
    // Dynamic edits must not cancel unrelated OMM builds.
    commitPart.reset();commitPart.emplace(RtrDiagnostics::Preparation);
    auto command=staged?staged:ResourceCommand();if(!staged)command->open();
    failureGuard.command=command;
    nvrhi::TimerQueryHandle gpuQuery;
    if(RtrDiagnostics::level.load()!=0&&resourceGpuQueries.size()<8){gpuQuery=GetDevice()->createTimerQuery();GetDevice()->resetTimerQuery(gpuQuery);command->beginTimerQuery(gpuQuery);}
    retiredGeneration.buffers={m_scene->GetInstanceBuffer(),m_scene->GetGeometryBuffer(),m_scene->GetMaterialBuffer(),m_subInstanceBuffer};
    if(m_topLevelAS)retiredGeneration.acceleration.push_back(m_topLevelAS);
    if(topology){
        if(memory)m_scene->HostRefreshTopology(command,GetFrameIndex(),localMeshes,changedMaterials);
        else m_scene->Refresh(command,GetFrameIndex());
    }
    else{
        if(!localMeshes.empty())m_scene->HostRefreshMeshes(command,GetFrameIndex(),localMeshes);
        m_scene->HostRefreshMaterials(command,changedMaterials);
    }
    if(!detachedMeshes.empty()||(topology&&memory)){
        // The tracker's authoritative shared ownership answers membership;
        // do not reconstruct a second dense inventory after graph refresh.
        for(auto& [identity,mesh]:detachedMeshes){
            const bool active=graph->GetMeshes().Contains(mesh);
            if(!active&&std::none_of(retiredGeneration.meshes.begin(),retiredGeneration.meshes.end(),[&](const auto& old){return old.get()==identity;}))
                retiredGeneration.meshes.push_back(mesh);
        }
    }
    // Memory imports bypass the SDK scene loader. Its OMM debug table must
    // still follow the published geometry count before the beauty pass uses it.
    if(topology&&m_ommBaker){
        if(nativeLighting.active)nativeOmmDebugStale=true;
        else{m_ommBaker->SceneLoaded(*m_scene);nativeOmmDebugStale=false;}
    }
    commitPart.reset();
    stageBegin=ResourceClock();bool materialShadersChanged=[&]{RtrDiagnostics::Scope diagnostic(RtrDiagnostics::Materials);
        return topology?GetMaterialsBaker()->HostSyncMaterials(m_scene):GetMaterialsBaker()->HostSyncChangedMaterials(changedMaterials);}();
    materialSyncNs+=std::chrono::duration_cast<std::chrono::nanoseconds>(ResourceClock()-stageBegin).count();
    if(topology)ApplyDefaultTransmissionDenoisingGuides(graph.get(),true);
    else ApplyDefaultTransmissionDenoisingGuides(changedMaterials,true);
    uint64_t pending=0,retained=0;
    std::vector<ResidentMesh> pendingMeshes;
    for(auto& [name,mesh]:affectedMeshes) {
        if(!mesh->accelStruct)++pending;
        if(!mesh->accelStruct)pendingMeshes.push_back(mesh);
    }
    retained=graph->GetMeshes().size()-pending;
    stageBegin=ResourceClock();
    commitPart.emplace(RtrDiagnostics::Acceleration);
    if(pending){
        if(!m_blasPool)m_blasPool=std::make_unique<bvh::BlasPool>(GetDevice());
        if(!m_blasCompactor)m_blasCompactor=std::make_unique<bvh::BlasCompactor>(GetDevice(),*m_blasPool);
        if(m_ommBaker)m_ommBaker->SetBlasCompactor(m_blasCompactor.get());
        if(memory){
            // The compactor synchronously reads GPU sizes. Dynamic generations use
            // ordinary AS allocations and stay entirely on the graphics queue.
            struct BlasJob {
                ResidentMesh mesh;nvrhi::rt::AccelStructDesc desc;
                nvrhi::rt::AccelStructHandle next,source;
                uint64_t allocationBytes,identity,reason;
            };
            std::vector<BlasJob> jobs;jobs.reserve(pendingMeshes.size());
            for(auto& mesh:pendingMeshes){
                bvh::Config cfg={.excludeTransmissive=m_ui.AS.ExcludeTransmissive};
                auto desc=bvh::GetMeshBlasDesc(cfg,*mesh,nullptr,false);
                if(nativeLighting.active)for(auto& geometry:desc.bottomLevelGeometries){
                    geometry.flags=nvrhi::rt::GeometryFlags::Opaque;geometry.useTransform=false;
                }
                desc.buildFlags=nvrhi::rt::AccelStructBuildFlags::PreferFastTrace|nvrhi::rt::AccelStructBuildFlags::AllowUpdate;
                desc.debugName="Retained mutable BLAS";
                nvrhi::rt::AccelStructHandle next;uint64_t allocationBytes=0;
                const auto identity=RtrDiagnostics::Identity(mesh->name);
                uint64_t reason=resourceChangeReasons.count(mesh->name)?resourceChangeReasons.at(mesh->name):RtrDiagnostics::BuildFlags;
                if(auto prepared=preparedMeshBlases.find(mesh->name);prepared!=preparedMeshBlases.end()){
                    next=prepared->second;allocationBytes=resourceBlasAllocationBytes.at(next.Get()).bytes;preparedMeshBlases.erase(prepared);
                }
                for(auto i=spareMeshBlases.begin();!next&&i!=spareMeshBlases.end();++i)if(CompatibleBlas(i->acceleration->getDesc(),desc)){
                    next=i->acceleration;allocationBytes=i->bytes;resourceBlasAllocationBytes.insert_or_assign(next.Get(),BlasAllocation{allocationBytes,i->storage});spareMeshBlasBytes-=i->bytes;spareMeshBlases.erase(i);reason|=RtrDiagnostics::PoolHit;break;
                }
                if(!next){
                    RtrDiagnostics::Scope allocation(RtrDiagnostics::BlasAllocate,1,mesh->totalIndices/3,1,0,identity,RtrDiagnostics::revision.load(),reason|RtrDiagnostics::CapacityGrowth);
                    next=GetDevice()->createAccelStruct(desc);if(!next)throw std::runtime_error("Dynamic BLAS allocation failed");
                    allocationBytes=GetDevice()->getAccelStructMemoryRequirements(next).size;
                }
                if(!resourceBlasAllocationBytes.count(next.Get()))resourceBlasAllocationBytes.emplace(next.Get(),BlasAllocation{allocationBytes,{}});
                auto source=refitSources.find(mesh.get());
                RtrProtocolTrace::Record(7,0,0,0,0,RtrDiagnostics::frame.load(),resourceRevision,desc.bottomLevelGeometries.size(),
                    source!=refitSources.end()?source->second->getDesc().bottomLevelGeometries.size():0,source!=refitSources.end()&&CompatibleBlas(source->second->getDesc(),desc),source!=refitSources.end());
                const bool update=source!=refitSources.end()&&CompatibleBlas(source->second->getDesc(),desc);
                jobs.push_back({mesh,std::move(desc),next,update?source->second:nvrhi::rt::AccelStructHandle{},allocationBytes,identity,reason});
            }
            // Prepare dependencies before the first independent build. A state
            // transition to AS-write synchronizes the build stage, so doing this
            // inside each build loop unnecessarily orders unrelated BLAS work.
            // Automatic barriers and scratch reuse barriers remain enabled.
            for(auto& job:jobs){
                for(auto& geometry:job.desc.bottomLevelGeometries){auto& triangle=geometry.geometryData.triangles;
                    command->setBufferState(triangle.indexBuffer,nvrhi::ResourceStates::AccelStructBuildInput);
                    command->setBufferState(triangle.vertexBuffer,nvrhi::ResourceStates::AccelStructBuildInput);
                }
                command->setAccelStructState(job.next,nvrhi::ResourceStates::AccelStructWrite);
                if(job.source)command->setAccelStructState(job.source,nvrhi::ResourceStates::AccelStructBuildBlas);
            }
            command->commitBarriers();
            bool cloned=false;
            for(auto& job:jobs)if(job.source){
                RtrDiagnostics::Scope clone(RtrDiagnostics::BlasClone,1,0,1,0,job.identity,RtrDiagnostics::revision.load(),job.reason,job.allocationBytes);
                command->copyRaytracingAccelerationStructure(job.next,job.source);cloned=true;
            }
            if(cloned){
                // All clones complete before any in-place refit reads its target.
                // Independent targets need one shared phase dependency.
                auto* list=static_cast<ID3D12GraphicsCommandList*>(command->getNativeObject(nvrhi::ObjectTypes::D3D12_GraphicsCommandList));
                D3D12_RESOURCE_BARRIER dependency{};dependency.Type=D3D12_RESOURCE_BARRIER_TYPE_UAV;
                list->ResourceBarrier(1,&dependency);
            }
            for(auto& job:jobs){
                auto& mesh=job.mesh;auto& desc=job.desc;auto& next=job.next;
                const auto identity=job.identity,reason=job.reason,allocationBytes=job.allocationBytes;
                if(job.source){
                    {RtrDiagnostics::Scope refit(RtrDiagnostics::BlasRefit,mesh->totalIndices/3,0,1,0,identity,RtrDiagnostics::revision.load(),reason,allocationBytes);
                        auto gpu=RtrGpuProfile::Begin(command,"ResourceBlasRefit",-1,identity,RtrDiagnostics::revision.load(),reason);
                        command->buildBottomLevelAccelStruct(next,desc.bottomLevelGeometries.data(),desc.bottomLevelGeometries.size(),desc.buildFlags|nvrhi::rt::AccelStructBuildFlags::PerformUpdate);RtrGpuProfile::End(command,gpu);}
                    ++resourceRefits;++static_cast<MeshInfoEx*>(mesh.get())->HostRefitFrames;
                }else{RtrDiagnostics::Scope build(RtrDiagnostics::BlasBuild,mesh->totalIndices/3,0,1,0,identity,RtrDiagnostics::revision.load(),reason|(refitSources.count(mesh.get())?RtrDiagnostics::BuildFlags:0),allocationBytes);
                    auto gpu=RtrGpuProfile::Begin(command,"ResourceBlasBuild",-1,identity,RtrDiagnostics::revision.load(),reason);
                    nvrhi::utils::BuildBottomLevelAccelStruct(command,next,desc);RtrGpuProfile::End(command,gpu);++resourceBlases;static_cast<MeshInfoEx*>(mesh.get())->HostRefitFrames=0;}
                mesh->accelStruct=next;
            }
            command->commitBarriers();
            auto* list=static_cast<ID3D12GraphicsCommandList*>(command->getNativeObject(nvrhi::ObjectTypes::D3D12_GraphicsCommandList));
            D3D12_RESOURCE_BARRIER barrier{};barrier.Type=D3D12_RESOURCE_BARRIER_TYPE_UAV;barrier.UAV.pResource=nullptr;
            list->ResourceBarrier(1,&barrier);
        }else CreateBlases(command,true,&pendingMeshes);
        if(!memory)resourceBlases+=pending;
    }
    m_ui.AccelerationStructRebuildRequested=false;
    resourceRetainedBlases+=retained;
    if(topology)CreateResourceTlas(retiredGeneration);
    if(topology||pending){
        nativeTlasDirty=true;
        bool patched=memory&&!topology&&!m_tlasInstanceDataDirty&&m_ui.AS.GPUTLASBuild&&m_tlasMeshBoundsBuffer&&m_tlasInstanceDataBuffer
            &&m_tlasInstanceCount==graph->GetMeshInstances().size();
        if(m_blasPool&&m_tlasInstanceDataBlasGeneration!=m_blasPool->GetGeneration())patched=false;
        if(m_ommBaker&&(m_tlasInstanceDataOmmBuildsLeft!=m_ommBaker->UIData().BuildsLeftInQueue||m_tlasInstanceDataOmmGeneration!=m_ommBaker->GetMemoryGeneration()))patched=false;
        if(patched){
            RtrDiagnostics::Scope upload(RtrDiagnostics::Upload,affectedMeshes.size());
            struct Bounds {float3 min;uint32_t pad0;float3 max;uint32_t pad1;};
            struct Instance {uint64_t blas,omm;uint32_t mesh,flags;};
            static_assert(sizeof(Bounds)==32&&sizeof(Instance)==24);
            for(auto& [name,mesh]:affectedMeshes){
                Bounds bounds{mesh->objectSpaceBounds.m_mins,0,mesh->objectSpaceBounds.m_maxs,0};
                command->writeBuffer(m_tlasMeshBoundsBuffer,&bounds,sizeof(bounds),size_t(mesh->globalMeshIndex)*sizeof(bounds));
                auto* ex=static_cast<MeshInfoEx*>(mesh.get());
                Instance data{mesh->accelStruct?mesh->accelStruct->getDeviceAddress():0,ex->AccelStructOMM?ex->AccelStructOMM->getDeviceAddress():0,
                    uint32_t(mesh->globalMeshIndex),mesh->skinPrototype.use_count()?1u:0u};
                auto users=residentMeshNodes.find(mesh.get());if(users!=residentMeshNodes.end())for(auto* node:users->second){
                    auto instance=std::static_pointer_cast<donut::engine::MeshInstance>(node->GetLeaf());
                    auto* extension=dynamic_cast<MeshInstanceExtension*>(instance.get());
                    data.flags=(mesh->skinPrototype?1u:0u)|((extension?extension->HostRayFlags:0u)<<8);
                    command->writeBuffer(m_tlasInstanceDataBuffer,&data,sizeof(data),size_t(instance->GetInstanceIndex())*sizeof(data));
                    upload.event[9]+=sizeof(data);
                }
                upload.event[9]+=sizeof(bounds);
            }
        }
        m_tlasInstanceDataDirty=!patched;++resourceTlas;
    }
    accelerationNs+=std::chrono::duration_cast<std::chrono::nanoseconds>(ResourceClock()-stageBegin).count();
    commitPart.reset();commitPart.emplace(RtrDiagnostics::SharedPublish);
    bool hitGroupsChanged=topology||pending||materialShadersChanged;
    for(auto& entry:invalidated)hitGroupsChanged|=entry.second;
    // Compute-only lighting has no ray-tracing pipeline baker. Retained geometry
    // updates still rebuild/refit the shared AS without creating a beauty PSO.
    if(hitGroupsChanged)if(auto baker=GetRTPipelineBaker())baker->HostInvalidateScene();
    if(gpuQuery)command->endTimerQuery(gpuQuery);
    {RtrDiagnostics::Scope submit(RtrDiagnostics::CommandSubmit);command->close();GetDevice()->executeCommandList(command);}
    retiredGeneration.commands.push_back(command);
    commitPart.reset();commitPart.emplace(RtrDiagnostics::ResourceRetirement);
    if(gpuQuery)resourceGpuQueries.push_back(gpuQuery);
    // Material-only edits keep geometry, BLAS, TLAS and their buffer identities.
    // Local updates keep the scene-table and TLAS bindings. Bindless entries and
    // table contents change, so recreating all beauty bindings would be wasted.
    if(topology){GetBindingCache().Clear();InvalidateBindingSet();}
    stageBegin=ResourceClock();
    if(topology){
        for(auto& [name,material]:residentMaterials)for(auto& texture:MaterialTextures(material))if(texture)oldTextures.push_back(texture);
        if(memory)PatchRetainedResourceIndex(retiredGeneration.detachedNodes,attachedIndexNodes,detachedMeshes,newIndexMeshes);
        else IndexRetainedResources();
    }
    retiredGeneration.textures=oldTextures;
    std::unordered_set<donut::engine::LoadedTexture*> retired;
    for(auto& texture:oldTextures)if(!residentTextureUsers.count(texture.get())&&retired.insert(texture.get()).second)m_scene->HostUnloadTexture(texture);
    nativeRetirementNs+=std::chrono::duration_cast<std::chrono::nanoseconds>(ResourceClock()-stageBegin).count();
    RetireResources(std::move(retiredGeneration));
    failureGuard.armed=false;
    if(UsesBakedLighting())if(auto baker=GetLightsBaker())baker->SceneReloaded();
    if(auto cache=GetSharc())cache->NotifySceneReloaded();
    g_sampleUIData.ResetAccumulation=true;g_sampleUIData.ResetRealtimeCaches=true;if(!nativeLighting.active)preSrHistoryReset=true;
    if(topology)nativeLighting.metadataDirty=true;
    ++cacheGeneration;lightingSceneDirty=true;
    if(topology||transformsChanged||!localMeshes.empty())++nativeMotionGeneration;
    resourceRecordingNs+=std::chrono::duration_cast<std::chrono::nanoseconds>(ResourceClock()-recordBegin).count();
}


void PrepareNativeCandidateFlags(nvrhi::ICommandList* command){
    candidateChangedNodes.clear();
    if(candidateLayoutDirty){
        candidateChangedNodes.reserve(residentInstanceNodes.size());
        for(auto& [name,node]:residentInstanceNodes)candidateChangedNodes.push_back(node);
    }
    else {
        std::unordered_set<donut::engine::SceneGraphNode*> changed;
        for(auto& name:nativeLighting.dirtyInstances){auto i=residentInstanceNodes.find(name);if(i!=residentInstanceNodes.end())changed.insert(i->second);}
        for(auto& key:nativeLighting.dirtyColors)candidateDirtyMeshes.insert(key.substr(0,key.rfind(':')));
        for(auto& name:candidateDirtyMeshes){auto mesh=residentMeshes.find(name);if(mesh==residentMeshes.end())continue;
            auto users=residentMeshNodes.find(mesh->second.get());if(users!=residentMeshNodes.end())changed.insert(users->second.begin(),users->second.end());}
        candidateChangedNodes.assign(changed.begin(),changed.end());
    }
    for(auto* node:candidateChangedNodes){
        auto instance=std::static_pointer_cast<donut::engine::MeshInstance>(node->GetLeaf());
        auto* extension=dynamic_cast<MeshInstanceExtension*>(instance.get());if(!extension)continue;
        auto* mesh=static_cast<MeshInfoEx*>(instance->GetMesh().get());
        auto attributes=nativeLighting.instanceAttributes.find(node->GetName());
        bool custom=attributes!=nativeLighting.instanceAttributes.end()&&attributes->second.rolesVisibility.x!=23;
        bool coverage=mesh->HostCoverageCandidates;
        for(size_t g=0;g<mesh->geometries.size();++g){
            coverage|=mesh->geometries[g]->material->domain!=donut::engine::MaterialDomain::Opaque;
            if(nativeLighting.alphaMeshes.count(mesh->name+":"+std::to_string(g))){coverage=true;mesh->HostDynamicCoverage=true;}
        }
        custom|=coverage;
        if(coverage||(attributes!=nativeLighting.instanceAttributes.end()&&attributes->second.rolesVisibility.w!=0)){
            auto& value=nativeLighting.instanceAttributes[node->GetName()];
            if(value.rolesVisibility.w!=float(coverage)){value.rolesVisibility.w=float(coverage);nativeLighting.dirtyInstances.insert(node->GetName());}
        }
        const uint32_t flags=custom?uint32_t(nvrhi::rt::InstanceFlags::ForceNonOpaque):0u;
        if(extension->HostRayFlags==flags)continue;extension->HostRayFlags=flags;nativeTlasDirty=true;
        if(!candidateLayoutDirty&&!m_tlasInstanceDataDirty&&m_tlasInstanceDataBuffer){
            const uint32_t data=(mesh->skinPrototype?1u:0u)|(flags<<8);
            command->writeBuffer(m_tlasInstanceDataBuffer,&data,sizeof(data),size_t(instance->GetInstanceIndex())*24+20);
        }else m_tlasInstanceDataDirty=true;
    }
    candidateLayoutDirty=false;candidateDirtyMeshes.clear();nativeLighting.instanceCandidates=true;
}
