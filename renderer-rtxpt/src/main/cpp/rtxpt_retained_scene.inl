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
std::unordered_map<donut::engine::LoadedTexture*,size_t> residentTextureUsers;
uint64_t nativePreparationMaterialVisits=0,nativePreparationMeshVisits=0,nativePreparationIndexBuilds=0;
static auto MaterialTextures(const ResidentMaterial& material) {
    return std::array{material->baseOrDiffuseTexture,material->metalRoughOrSpecularTexture,
        material->normalTexture,material->emissiveTexture,material->occlusionTexture,material->transmissionTexture};
}
void IndexRetainedResources() {
    residentMeshes.clear();residentMaterials.clear();residentMaterialMeshes.clear();residentTextureUsers.clear();
    if(!m_scene)return;
    auto graph=m_scene->GetSceneGraph();
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
void ApplyResources(const std::filesystem::path& manifest,
                    std::unordered_map<std::string,donut::engine::SceneGraphNode*>& nodes) {
    if(!managedLighting||!m_scene)throw std::logic_error("Resource updates require a managed retained scene");
    Json::Value json;std::ifstream input(manifest);
    if(!input||!(input>>json))throw std::runtime_error("Could not read retained delta");
    auto stageBegin=std::chrono::steady_clock::now();
    auto imported=[&]{RtrDiagnostics::Scope diagnostic(RtrDiagnostics::Import);return m_scene->HostImport(std::filesystem::u8path(json["model"].asString()));}();
    importerNs+=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-stageBegin).count();
    stageBegin=std::chrono::steady_clock::now();{RtrDiagnostics::Scope diagnostic(RtrDiagnostics::Textures);m_scene->HostFinalizeTextures(*GetCommonPasses());}
    textureFinalizeNs+=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-stageBegin).count();
    auto graph=m_scene->GetSceneGraph();
    auto& meshes=residentMeshes;auto& materials=residentMaterials;
    std::unordered_map<std::string,ResidentMesh> affectedMeshes;
    std::vector<ResidentMaterial> changedMaterials;
    std::vector<std::shared_ptr<donut::engine::LoadedTexture>> oldTextures;
    std::vector<std::pair<nvrhi::TextureHandle,nvrhi::TextureHandle>> textureCopies;
    std::unordered_map<donut::engine::LoadedTexture*,nvrhi::TextureHandle> copiedTextures;
    auto gather=[&](auto&& self,const std::shared_ptr<donut::engine::SceneGraphNode>& node)->void {
        if(auto instance=std::dynamic_pointer_cast<donut::engine::MeshInstance>(node->GetLeaf())) {
            auto mesh=instance->GetMesh();meshes[mesh->name]=mesh;affectedMeshes[mesh->name]=mesh;
        }
        for(uint32_t i=0;i<node->GetNumChildren();i++)self(self,node->GetChild(i)->shared_from_this());
    };
    gather(gather,imported.rootNode);
    // Validate references and transforms before changing the published graph.
    for(auto& record:json["nodes"]) {
        if(!meshes.count(record["mesh"].asString())||record["transform"].size()!=12)throw std::runtime_error("Invalid retained mesh reference");
        for(auto& v:record["transform"])if(!std::isfinite(v.asDouble()))throw std::runtime_error("Non-finite retained transform");
    }
    const auto waitBegin=std::chrono::steady_clock::now();
    // Queue draining is the conservative generation-retirement policy. Outputs
    // shared with external consumers own separate textures and are not replaced.
    GetDevice()->waitForIdle();
    ResourceTelemetry(); // Retire completed timer queries even when nobody polls diagnostics.
    resourceWaitNs+=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-waitBegin).count();
    const auto recordBegin=std::chrono::steady_clock::now();
    std::unordered_map<std::string,bool> invalidated;
    std::unordered_map<std::string,bool> coverageChanged;
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
        for(size_t slot=0;slot<from.size();slot++)if(from[slot]&&to[slot]&&from[slot]!=to[slot]
            &&from[slot]->texture&&to[slot]->texture
            &&std::filesystem::path(from[slot]->path).filename()==std::filesystem::path(to[slot]->path).filename()
            &&from[slot]->loadOptions==to[slot]->loadOptions) {
            auto& a=from[slot]->texture->getDesc();auto& b=to[slot]->texture->getDesc();
            if(a.width==b.width&&a.height==b.height&&a.format==b.format&&a.mipLevels==b.mipLevels&&a.arraySize==b.arraySize) {
                // Keep the resident allocation. The new cache entry retains its own
                // descriptor, and all queues were drained before this in-place copy.
                auto [entry,inserted]=copiedTextures.emplace(from[slot].get(),to[slot]->texture);
                if(inserted){
                    ++reusedTextureAllocations;
                    textureCopies.emplace_back(to[slot]->texture,from[slot]->texture);
                    from[slot]->texture=to[slot]->texture;
                    from[slot]->bindlessDescriptor=m_DescriptorTable->CreateDescriptorHandle(nvrhi::BindingSetItem::Texture_SRV(0,from[slot]->texture));
                }
            }
        }
        auto domain=target->domain;auto constants=target->materialConstants;int id=target->materialID;
        static_cast<donut::engine::Material&>(*target)=*source;target->materialConstants=constants;target->materialID=id;
        GetMaterialsBaker()->HostImportMaterial(*target);pt=PTMaterial::SafeCast(target);
        changedMaterials.push_back(target);
        for(auto& texture:MaterialTextures(target))if(texture)++residentTextureUsers[texture.get()];
        bool after=pt&&(pt->EnableAlphaTesting||pt->ExcludeFromNEE);
        invalidated[source->name]=before!=after||domain!=target->domain||(before&&(alphaTextureChanged||alphaValuesChanged));
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
            mesh->accelStruct=nullptr;
            if(auto* extended=dynamic_cast<MeshInfoEx*>(mesh.get())) {
                extended->AccelStructOMM=nullptr;extended->OpacityMicroMaps.clear();extended->InvalidateOmmCpuInputIdentity();
            }
        }
    }
    }
    bool topology=false;
    for(auto& record:json["removeNodes"]) {
        auto found=nodes.find(record.asString());if(found!=nodes.end()){graph->Detach(found->second->shared_from_this());nodes.erase(found);topology=true;}
    }
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
        RtrSetInstanceTransform(node.get(),p);
        nodes.insert_or_assign(name,node.get());
    }
    m_scene->HostReleaseModels();
    // Compatible RGB-only edits retain the OMM inputs and pending jobs as well as the AS.
    bool ommChanged=topology;for(auto& [name,mesh]:affectedMeshes)ommChanged|=!mesh->accelStruct;
    for(auto& entry:invalidated)ommChanged|=entry.second;
    if(m_ommBaker&&ommChanged)m_ommBaker->SceneUnloading();
    auto command=GetDevice()->createCommandList();command->open();
    nvrhi::TimerQueryHandle gpuQuery;
    if(RtrDiagnostics::level.load()!=0&&resourceGpuQueries.size()<8){gpuQuery=GetDevice()->createTimerQuery();GetDevice()->resetTimerQuery(gpuQuery);command->beginTimerQuery(gpuQuery);}
    for(auto& copy:textureCopies)for(uint32_t mip=0;mip<copy.first->getDesc().mipLevels;mip++)
        command->copyTexture(copy.first,nvrhi::TextureSlice().setMipLevel(mip),copy.second,nvrhi::TextureSlice().setMipLevel(mip));
    if(topology)m_scene->Refresh(command,GetFrameIndex());
    else m_scene->HostRefreshMaterials(command,changedMaterials);
    stageBegin=std::chrono::steady_clock::now();bool materialShadersChanged=[&]{RtrDiagnostics::Scope diagnostic(RtrDiagnostics::Materials);
        return topology?GetMaterialsBaker()->HostSyncMaterials(m_scene):GetMaterialsBaker()->HostSyncChangedMaterials(changedMaterials);}();
    materialSyncNs+=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-stageBegin).count();
    if(topology)ApplyDefaultTransmissionDenoisingGuides(graph.get(),true);
    else ApplyDefaultTransmissionDenoisingGuides(changedMaterials,true);
    uint64_t pending=0,retained=0;
    std::vector<ResidentMesh> pendingMeshes;
    for(auto& [name,mesh]:affectedMeshes) {
        if(!mesh->accelStruct)++pending;
        if(!mesh->accelStruct)pendingMeshes.push_back(mesh);
    }
    retained=graph->GetMeshes().size()-pending;
    stageBegin=std::chrono::steady_clock::now();
    if(pending){CreateBlases(command,true,&pendingMeshes);resourceBlases+=pending;}
    resourceRetainedBlases+=retained;
    if(topology||pending){CreateTlas(command);m_tlasInstanceDataDirty=true;++resourceTlas;}
    accelerationNs+=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-stageBegin).count();
    bool hitGroupsChanged=topology||pending||materialShadersChanged;
    for(auto& entry:invalidated)hitGroupsChanged|=entry.second;
    // Compute-only lighting has no ray-tracing pipeline baker. Retained geometry
    // updates still rebuild/refit the shared AS without creating a beauty PSO.
    if(hitGroupsChanged)if(auto baker=GetRTPipelineBaker())baker->HostInvalidateScene();
    if(gpuQuery)command->endTimerQuery(gpuQuery);command->close();GetDevice()->executeCommandList(command);
    if(gpuQuery)resourceGpuQueries.push_back(gpuQuery);
    // Material-only edits keep geometry, BLAS, TLAS and their buffer identities.
    GetBindingCache().Clear();InvalidateBindingSet();
    stageBegin=std::chrono::steady_clock::now();
    if(topology){
        for(auto& [name,material]:residentMaterials)for(auto& texture:MaterialTextures(material))if(texture)oldTextures.push_back(texture);
        IndexRetainedResources();
    }
    std::unordered_set<donut::engine::LoadedTexture*> retired;
    for(auto& texture:oldTextures)if(!residentTextureUsers.count(texture.get())&&retired.insert(texture.get()).second)m_scene->HostUnloadTexture(texture);
    nativeRetirementNs+=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-stageBegin).count();
    if(UsesBakedLighting())if(auto baker=GetLightsBaker())baker->SceneReloaded();
    if(auto cache=GetSharc())cache->NotifySceneReloaded();
    g_sampleUIData.ResetAccumulation=true;g_sampleUIData.ResetRealtimeCaches=true;preSrHistoryReset=true;
    ++cacheGeneration;lightingSceneDirty=true;
    resourceRecordingNs+=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-recordBegin).count();
}
