static std::chrono::steady_clock::time_point ResourceClock(){return RtrDiagnostics::level.load()!=0?std::chrono::steady_clock::now():std::chrono::steady_clock::time_point{};}
// Included inside HostPathTracer. Imports use the resident scene's factory,
// descriptor allocator and texture cache, never SetCurrentScene/SceneLoaded.
uint64_t resourceBlases=0,resourceRetainedBlases=0,resourceTlas=0,resourceWaitNs=0,resourceRecordingNs=0;
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
            unsigned component=std::min<unsigned>(unsigned(c),channels-1);
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
std::unordered_map<donut::engine::LoadedTexture*,size_t> residentTextureUsers;
uint64_t nativePreparationMaterialVisits=0,nativePreparationMeshVisits=0,nativePreparationIndexBuilds=0;
static auto MaterialTextures(const ResidentMaterial& material) {
    return std::array{material->baseOrDiffuseTexture,material->metalRoughOrSpecularTexture,
        material->normalTexture,material->emissiveTexture,material->occlusionTexture,material->transmissionTexture};
}
void IndexRetainedResources() {
    residentMeshes.clear();residentMaterials.clear();residentMaterialMeshes.clear();residentTextureUsers.clear();residentMeshNodes.clear();
    if(!m_scene)return;
    auto graph=m_scene->GetSceneGraph();
    for(auto& instance:graph->GetMeshInstances())residentMeshNodes[instance->GetMesh().get()].push_back(instance->GetNode());
    for(auto& mesh:graph->GetMeshes()) {
        residentMeshes.emplace(mesh->name,mesh);std::unordered_set<donut::engine::Material*> used;
        for(auto& geometry:mesh->geometries)if(used.insert(geometry->material.get()).second)
            residentMaterialMeshes[geometry->material.get()].push_back(mesh);
    }
    for(auto& material:graph->GetMaterials()) {
        residentMaterials.emplace(material->name,material);
        for(auto& texture:MaterialTextures(material))if(texture)++residentTextureUsers[texture.get()];
    }
    if(RtrDiagnostics::level.load()!=0)++nativePreparationIndexBuilds;
}
uint64_t resourceRevision=0;
std::array<uint64_t,19> dynamicWork{}; // validation records/hits/failures, geometry visits, batches, staging bytes, drains, retirements, pending, peak, packet ns, index ns, migration bytes, texture uploads, geometry uploads, clear calls
struct RetiredResources {
    // NVRHI owns raw upload/scratch chunks on the public command-list object.
    // The queue's internal list tracker alone does not retain those pools.
    std::vector<nvrhi::CommandListHandle> commands;
    bool recycleCommands=true;
    std::vector<ResidentMesh> meshes;
    std::vector<std::shared_ptr<donut::engine::LoadedTexture>> textures;
    std::vector<nvrhi::BufferHandle> buffers;
    std::vector<nvrhi::rt::AccelStructHandle> acceleration;
    std::vector<nvrhi::rt::OpacityMicromapHandle> opacityMaps;
    nvrhi::EventQueryHandle done,computeDone;
};
std::vector<RetiredResources> resourceRetirement;
std::vector<nvrhi::CommandListHandle> resourceCommandPool;
struct SpareMeshBuffers { std::shared_ptr<donut::engine::BufferGroup> buffers; uint64_t bytes; };
struct SpareMeshBlas { nvrhi::rt::AccelStructHandle acceleration; uint64_t bytes; };
std::vector<SpareMeshBuffers> spareMeshBuffers;
std::vector<SpareMeshBlas> spareMeshBlases;
uint64_t spareMeshBufferBytes=0,spareMeshBlasBytes=0;
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
void RecycleMesh(const ResidentMesh& mesh){
    auto b=mesh->buffers;
    if(!b||!b->vertexBuffer||!b->indexBuffer||b->vertexBuffer->getDesc().debugName!="Retained mutable vertices")return;
    b->instanceBuffer=nullptr;b->instnaceBufferDescriptor.reset();
    uint64_t bytes=b->vertexBuffer->getDesc().byteSize+b->indexBuffer->getDesc().byteSize;
    if(spareMeshBuffers.size()<64&&spareMeshBufferBytes+bytes<=64*1024*1024){spareMeshBuffers.push_back({b,bytes});spareMeshBufferBytes+=bytes;}
    auto as=mesh->accelStruct;
    if(as&&!as->isCompacted()&&as->getDesc().debugName=="Retained mutable BLAS"){
        bytes=GetDevice()->getAccelStructMemoryRequirements(as).size;
        if(spareMeshBlases.size()<64&&spareMeshBlasBytes+bytes<=64*1024*1024){spareMeshBlases.push_back({as,bytes});spareMeshBlasBytes+=bytes;}
    }
}
nvrhi::CommandListHandle ResourceCommand(){
    if(!resourceCommandPool.empty()){auto command=resourceCommandPool.back();resourceCommandPool.pop_back();return command;}
    if(RtrDiagnostics::level.load()!=0)++dynamicWork[18];
    return GetDevice()->createCommandList(nvrhi::CommandListParameters().setScratchChunkSize(4*1024*1024).setScratchMaxMemory(64*1024*1024));
}
void PollResourceRetirement(){
    RtrDiagnostics::Scope diagnostic(RtrDiagnostics::ResourceRetirement);
    for(auto i=resourceRetirement.begin();i!=resourceRetirement.end();)if(GetDevice()->pollEventQuery(i->done)&&GetDevice()->pollEventQuery(i->computeDone)){
        for(auto& mesh:i->meshes)RecycleMesh(mesh);
        if(i->recycleCommands)for(auto& command:i->commands)if(resourceCommandPool.size()<8)resourceCommandPool.push_back(std::move(command));
        i=resourceRetirement.erase(i);if(RtrDiagnostics::level.load()!=0)++dynamicWork[7];
    }else ++i;
    if(RtrDiagnostics::level.load()!=0)dynamicWork[8]=resourceRetirement.size();
}
void RetireResources(RetiredResources&& value){
    value.done=GetDevice()->createEventQuery();value.computeDone=GetDevice()->createEventQuery();
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
    std::unordered_map<std::string,ResidentMesh> affectedMeshes;
    std::vector<ResidentMaterial> changedMaterials;
    std::vector<std::shared_ptr<donut::engine::LoadedTexture>> oldTextures;
    struct FailedCommitGuard {
        nvrhi::IDevice* device;nvrhi::CommandListHandle command;bool armed=true;int exceptions=std::uncaught_exceptions();
        ~FailedCommitGuard(){if(armed&&std::uncaught_exceptions()>exceptions)try{device->waitForIdle();}catch(...){} }
    } failureGuard{GetDevice()};
    auto gather=[&](auto&& self,const std::shared_ptr<donut::engine::SceneGraphNode>& node)->void {
        if(auto instance=std::dynamic_pointer_cast<donut::engine::MeshInstance>(node->GetLeaf())) {
            auto mesh=instance->GetMesh();auto old=meshes.find(mesh->name);
            bool compatible=memory&&old!=meshes.end()&&old->second->geometries.size()==mesh->geometries.size();
            if(compatible)for(size_t g=0;g<mesh->geometries.size();g++){
                auto a=old->second->geometries[g],b=mesh->geometries[g];
                compatible&=a->numIndices==b->numIndices&&a->numVertices==b->numVertices&&a->material->name==b->material->name;
            }
            if(compatible){
                auto target=std::static_pointer_cast<MeshInfoEx>(old->second);
                retiredGeneration.meshes.push_back(std::make_shared<MeshInfoEx>(*target));
                int meshIndex=target->globalMeshIndex;
                for(size_t g=0;g<mesh->geometries.size();g++)mesh->geometries[g]->globalGeometryIndex=target->geometries[g]->globalGeometryIndex;
                static_cast<donut::engine::MeshInfo&>(*target)=*mesh;target->globalMeshIndex=meshIndex;
                target->AccelStructOMM=nullptr;target->OpacityMicroMaps.clear();target->InvalidateOmmCpuInputIdentity();
                mesh=target;localMeshes.push_back(mesh);
            }else if(old!=meshes.end())retiredGeneration.meshes.push_back(old->second);
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
    PollResourceRetirement();
    ResourceTelemetry(); // Retire completed timer queries even when nobody polls diagnostics.
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
        bool before=pt&&(pt->EnableAlphaTesting||pt->ExcludeFromNEE);
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
        bool after=pt&&(pt->EnableAlphaTesting||pt->ExcludeFromNEE);
        invalidated[source->name]=before!=after||domain!=target->domain||(before&&(alphaTextureChanged||alphaValuesChanged));
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
    bool topology=false;
    for(auto& record:json["removeNodes"]) {
        auto found=nodes.find(record.asString());if(found!=nodes.end()){graph->Detach(found->second->shared_from_this());nodes.erase(found);topology=true;}
    }
    std::vector<donut::engine::SceneGraphNode*> changedLeafNodes;
    for(auto* mesh:domainChangedMeshes){auto users=residentMeshNodes.find(mesh);if(users!=residentMeshNodes.end())changedLeafNodes.insert(changedLeafNodes.end(),users->second.begin(),users->second.end());}
    for(auto& record:json["nodes"]) {
        std::string name=record["name"].asString();auto mesh=meshes.at(record["mesh"].asString());
        auto found=nodes.find(name);std::shared_ptr<donut::engine::SceneGraphNode> node;
        if(found!=nodes.end()) {
            auto instance=std::dynamic_pointer_cast<donut::engine::MeshInstance>(found->second->GetLeaf());
            if(instance&&instance->GetMesh()==mesh)node=found->second->shared_from_this();
            else{graph->Detach(found->second->shared_from_this());topology=true;}
        }
        if(!node){node=graph->AttachLeafNode(graph->GetRootNode(),std::make_shared<MeshInstanceEx>(mesh));node->SetName(name);topology=true;}
        double p[12];for(int i=0;i<12;i++)p[i]=record["transform"][i].asDouble();
        auto transform=node->GetLocalToParentTransform();bool same=true;
        for(int row=0;row<3;row++)for(int column=0;column<4;column++)same&=std::abs((column==3?transform.m_translation[row]:transform.m_linear[column][row])-p[row*4+column])<1e-12;
        if(!same)RtrSetInstanceTransform(node.get(),p);
        if(affectedMeshes.count(mesh->name))changedLeafNodes.push_back(node.get());
        nodes.insert_or_assign(name,node.get());
    }
    if(!changedLeafNodes.empty()&&(topology||!graph->HostRefreshLeafNodes(changedLeafNodes)))for(auto* node:changedLeafNodes)node->InvalidateContent();
    m_scene->HostReleaseModels();
    // Compatible RGB-only edits retain the OMM inputs and pending jobs as well as the AS.
    bool ommChanged=topology;for(auto& [name,mesh]:affectedMeshes)ommChanged|=!mesh->accelStruct;
    for(auto& entry:invalidated)ommChanged|=entry.second;
    if(m_ommBaker&&ommChanged)m_ommBaker->SceneUnloading();
    commitPart.reset();commitPart.emplace(RtrDiagnostics::Preparation);
    auto command=staged?staged:ResourceCommand();if(!staged)command->open();
    failureGuard.command=command;
    nvrhi::TimerQueryHandle gpuQuery;
    if(RtrDiagnostics::level.load()!=0&&resourceGpuQueries.size()<8){gpuQuery=GetDevice()->createTimerQuery();GetDevice()->resetTimerQuery(gpuQuery);command->beginTimerQuery(gpuQuery);}
    retiredGeneration.buffers={m_scene->GetInstanceBuffer(),m_scene->GetGeometryBuffer(),m_scene->GetMaterialBuffer(),m_subInstanceBuffer};
    if(m_topLevelAS)retiredGeneration.acceleration.push_back(m_topLevelAS);
    if(topology)m_scene->Refresh(command,GetFrameIndex());
    else{
        if(!localMeshes.empty())m_scene->HostRefreshMeshes(command,GetFrameIndex(),localMeshes);
        m_scene->HostRefreshMaterials(command,changedMaterials);
    }
    // Memory imports bypass the SDK scene loader. Its OMM debug table must
    // still follow the published geometry count before the beauty pass uses it.
    if(topology&&m_ommBaker)m_ommBaker->SceneLoaded(*m_scene);
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
            for(auto& mesh:pendingMeshes){
                bvh::Config cfg={.excludeTransmissive=m_ui.AS.ExcludeTransmissive};
                auto desc=bvh::GetMeshBlasDesc(cfg,*mesh,nullptr,false);
                desc.buildFlags=nvrhi::rt::AccelStructBuildFlags::PreferFastTrace;
                desc.debugName="Retained mutable BLAS";
                nvrhi::rt::AccelStructHandle next;
                for(auto i=spareMeshBlases.begin();i!=spareMeshBlases.end();++i)if(CompatibleBlas(i->acceleration->getDesc(),desc)){
                    next=i->acceleration;spareMeshBlasBytes-=i->bytes;spareMeshBlases.erase(i);break;
                }
                if(!next)next=GetDevice()->createAccelStruct(desc);if(!next)throw std::runtime_error("Dynamic BLAS allocation failed");
                nvrhi::utils::BuildBottomLevelAccelStruct(command,next,desc);mesh->accelStruct=next;
            }
            command->commitBarriers();
            auto* list=static_cast<ID3D12GraphicsCommandList*>(command->getNativeObject(nvrhi::ObjectTypes::D3D12_GraphicsCommandList));
            D3D12_RESOURCE_BARRIER barrier{};barrier.Type=D3D12_RESOURCE_BARRIER_TYPE_UAV;barrier.UAV.pResource=nullptr;
            list->ResourceBarrier(1,&barrier);
        }else CreateBlases(command,true,&pendingMeshes);
        resourceBlases+=pending;
    }
    m_ui.AccelerationStructRebuildRequested=false;
    resourceRetainedBlases+=retained;
    if(topology)CreateTlas(command);
    if(topology||pending){
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
    if(gpuQuery)command->endTimerQuery(gpuQuery);command->close();GetDevice()->executeCommandList(command);
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
        IndexRetainedResources();
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
    resourceRecordingNs+=std::chrono::duration_cast<std::chrono::nanoseconds>(ResourceClock()-recordBegin).count();
}
