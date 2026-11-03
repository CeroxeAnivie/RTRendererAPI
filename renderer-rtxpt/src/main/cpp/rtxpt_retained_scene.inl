// Included inside HostPathTracer. Imports use the resident scene's factory,
// descriptor allocator and texture cache, never SetCurrentScene/SceneLoaded.
uint64_t resourceBlases=0,resourceRetainedBlases=0,resourceTlas=0,resourceWaitNs=0,resourceRecordingNs=0;
uint64_t importerNs=0,textureFinalizeNs=0,materialSyncNs=0,accelerationNs=0,nativeRetirementNs=0;
uint64_t reusedTextureAllocations=0,resourceGpuSamples=0,resourceGpuIntervalNs=0;
std::vector<nvrhi::TimerQueryHandle> resourceGpuQueries;
size_t RepresentedLights() const { return sceneLights.size(); }
std::array<jlong,8> LightSamplingTelemetry() {
    std::array<jlong,8> values={sceneLightingActive()?(sceneLights.size()<=size_t(pathQuality.values[5]+1)?1:2):0,
        jlong(sceneLights.size()),jlong(sceneLightTree.size()),lightStatsEnabled?1:0,0,0,0,0};
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
void CaptureLinear(const std::filesystem::path& path) {
    if(!m_renderTargets||!m_renderTargets->OutputColor)throw std::runtime_error("No linear render output");
    auto source=m_renderTargets->OutputColor;auto desc=source->getDesc();
    if(desc.format!=nvrhi::Format::RGBA16_FLOAT)throw std::runtime_error("Unsupported linear output format");
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
            uint16_t h=row[x*4+c];int exponent=(h>>10)&31;int mantissa=h&1023;
            float f=exponent==31?(mantissa?NAN:INFINITY):std::ldexp(float(exponent?mantissa+1024:mantissa),exponent?exponent-25:-24);
            if(h&0x8000)f=-f;output.write(reinterpret_cast<const char*>(&f),4);
        }
    }
    GetDevice()->unmapStagingTexture(staging);
    if(!output)throw std::runtime_error("Could not write linear output");
}
std::array<jlong,11> ResourceTelemetry() {
    for(auto query=resourceGpuQueries.begin();query!=resourceGpuQueries.end();){
        if(GetDevice()->pollTimerQuery(*query)){
            resourceGpuIntervalNs+=uint64_t(GetDevice()->getTimerQueryTime(*query)*1e9);++resourceGpuSamples;
            query=resourceGpuQueries.erase(query);
        }else ++query;
    }
    return {jlong(resourceBlases),jlong(resourceRetainedBlases),jlong(resourceTlas),jlong(resourceWaitNs),jlong(resourceRecordingNs),jlong(lightIndexBuilds),jlong(lightIndexBuildNs),jlong(sceneLights.size()),jlong(reusedTextureAllocations),jlong(resourceGpuSamples),jlong(resourceGpuIntervalNs)};
}
void ApplyResources(const std::filesystem::path& manifest) {
    if(!managedLighting||!m_scene)throw std::logic_error("Resource updates require a managed retained scene");
    Json::Value json;std::ifstream input(manifest);
    if(!input||!(input>>json))throw std::runtime_error("Could not read retained delta");
    auto stageBegin=std::chrono::steady_clock::now();
    auto imported=[&]{RtrDiagnostics::Scope diagnostic(RtrDiagnostics::Import);return m_scene->HostImport(std::filesystem::u8path(json["model"].asString()));}();
    importerNs+=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-stageBegin).count();
    stageBegin=std::chrono::steady_clock::now();{RtrDiagnostics::Scope diagnostic(RtrDiagnostics::Textures);m_scene->HostFinalizeTextures(*GetCommonPasses());}
    textureFinalizeNs+=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-stageBegin).count();
    auto graph=m_scene->GetSceneGraph();
    std::unordered_map<std::string,std::shared_ptr<donut::engine::MeshInfo>> meshes;
    std::unordered_map<std::string,std::shared_ptr<donut::engine::Material>> materials;
    std::unordered_map<std::string,std::shared_ptr<donut::engine::SceneGraphNode>> nodes;
    std::unordered_map<std::string,nvrhi::rt::IAccelStruct*> originalBlases;
    std::vector<std::shared_ptr<donut::engine::LoadedTexture>> oldTextures;
    std::vector<std::pair<nvrhi::TextureHandle,nvrhi::TextureHandle>> textureCopies;
    std::unordered_map<donut::engine::LoadedTexture*,nvrhi::TextureHandle> copiedTextures;
    auto textures=[](auto& material) {return std::array{material->baseOrDiffuseTexture,material->metalRoughOrSpecularTexture,material->normalTexture,material->emissiveTexture};};
    for(auto& mesh:graph->GetMeshes()){meshes[mesh->name]=mesh;originalBlases[mesh->name]=mesh->accelStruct.Get();}
    for(auto& material:graph->GetMaterials()) {
        materials[material->name]=material;
        for(auto& texture:textures(material))if(texture)oldTextures.push_back(texture);
    }
    for(auto& instance:graph->GetMeshInstances())nodes[instance->GetNode()->GetName()]=instance->GetNode()->shared_from_this();
    auto gather=[&](auto&& self,const std::shared_ptr<donut::engine::SceneGraphNode>& node)->void {
        if(auto instance=std::dynamic_pointer_cast<donut::engine::MeshInstance>(node->GetLeaf()))meshes[instance->GetMesh()->name]=instance->GetMesh();
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
        auto found=materials.find(source->name);
        if(found==materials.end()){materials[source->name]=source;GetMaterialsBaker()->HostImportMaterial(*source);continue;}
        auto target=found->second;auto* pt=PTMaterial::SafeCast(target);
        bool before=pt&&(pt->EnableAlphaTesting||pt->ExcludeFromNEE);
        bool alphaTextureChanged=coverageChanged[source->name];
        bool alphaValuesChanged=target->alphaCutoff!=source->alphaCutoff || target->opacity!=source->opacity;
        auto from=textures(source),to=textures(target);
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
        bool after=pt&&(pt->EnableAlphaTesting||pt->ExcludeFromNEE);
        invalidated[source->name]=before!=after||domain!=target->domain||(before&&(alphaTextureChanged||alphaValuesChanged));
    }
    for(auto& [name,mesh]:meshes)for(auto& geometry:mesh->geometries) {
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
    bool topology=false;
    for(auto& record:json["removeNodes"]) {
        auto found=nodes.find(record.asString());if(found!=nodes.end()){graph->Detach(found->second);nodes.erase(found);topology=true;}
    }
    for(auto& record:json["nodes"]) {
        std::string name=record["name"].asString();auto mesh=meshes.at(record["mesh"].asString());
        auto found=nodes.find(name);std::shared_ptr<donut::engine::SceneGraphNode> node;
        if(found!=nodes.end()) {
            auto instance=std::dynamic_pointer_cast<donut::engine::MeshInstance>(found->second->GetLeaf());
            if(instance&&instance->GetMesh()==mesh)node=found->second;
            else{graph->Detach(found->second);topology=true;}
        }
        if(!node){node=graph->AttachLeafNode(graph->GetRootNode(),std::make_shared<MeshInstanceEx>(mesh));node->SetName(name);topology=true;}
        double p[12];for(int i=0;i<12;i++)p[i]=record["transform"][i].asDouble();
        daffine3 transform(double3(p[0],p[4],p[8]),double3(p[1],p[5],p[9]),double3(p[2],p[6],p[10]),double3(p[3],p[7],p[11]));
        double3 translation,scaling;dquat rotation;decomposeAffine(transform,&translation,&rotation,&scaling);
        node->SetTransform(&translation,&rotation,&scaling);
    }
    m_scene->HostReleaseModels();
    // Compatible RGB-only edits retain the OMM inputs and pending jobs as well as the AS.
    bool ommChanged=topology;for(auto& [name,mesh]:meshes)ommChanged|=!mesh->accelStruct;
    for(auto& entry:invalidated)ommChanged|=entry.second;
    if(m_ommBaker&&ommChanged)m_ommBaker->SceneUnloading();
    auto command=GetDevice()->createCommandList();command->open();
    nvrhi::TimerQueryHandle gpuQuery;
    if(RtrDiagnostics::level.load()!=0&&resourceGpuQueries.size()<8){gpuQuery=GetDevice()->createTimerQuery();GetDevice()->resetTimerQuery(gpuQuery);command->beginTimerQuery(gpuQuery);}
    for(auto& copy:textureCopies)for(uint32_t mip=0;mip<copy.first->getDesc().mipLevels;mip++)
        command->copyTexture(copy.first,nvrhi::TextureSlice().setMipLevel(mip),copy.second,nvrhi::TextureSlice().setMipLevel(mip));
    m_scene->Refresh(command,GetFrameIndex());
    stageBegin=std::chrono::steady_clock::now();bool materialShadersChanged=[&]{RtrDiagnostics::Scope diagnostic(RtrDiagnostics::Materials);return GetMaterialsBaker()->HostSyncMaterials(m_scene);}();
    materialSyncNs+=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-stageBegin).count();
    ApplyDefaultTransmissionDenoisingGuides(graph.get(),true);
    uint64_t pending=0,retained=0;
    for(auto& mesh:graph->GetMeshes()) {
        if(!mesh->accelStruct)++pending;
        else if(originalBlases[mesh->name]==mesh->accelStruct.Get())++retained;
    }
    stageBegin=std::chrono::steady_clock::now();
    if(pending){CreateBlases(command,true);resourceBlases+=pending;}
    resourceRetainedBlases+=retained;
    if(topology||pending){CreateTlas(command);m_tlasInstanceDataDirty=true;++resourceTlas;}
    accelerationNs+=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-stageBegin).count();
    bool hitGroupsChanged=topology||pending||materialShadersChanged;
    for(auto& entry:invalidated)hitGroupsChanged|=entry.second;
    if(hitGroupsChanged)GetRTPipelineBaker()->HostInvalidateScene();
    if(gpuQuery)command->endTimerQuery(gpuQuery);command->close();GetDevice()->executeCommandList(command);
    if(gpuQuery)resourceGpuQueries.push_back(gpuQuery);
    // Material-only edits keep geometry, BLAS, TLAS and their buffer identities.
    GetBindingCache().Clear();InvalidateBindingSet();
    stageBegin=std::chrono::steady_clock::now();
    std::unordered_map<donut::engine::LoadedTexture*,bool> liveTextures;
    for(auto& material:graph->GetMaterials())for(auto& texture:textures(material))if(texture)liveTextures[texture.get()]=true;
    for(auto& texture:oldTextures)if(!liveTextures.count(texture.get()))m_scene->HostUnloadTexture(texture);
    nativeRetirementNs+=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-stageBegin).count();
    if(UsesBakedLighting())if(auto baker=GetLightsBaker())baker->SceneReloaded();
    if(auto cache=GetSharc())cache->NotifySceneReloaded();
    g_sampleUIData.ResetAccumulation=true;g_sampleUIData.ResetRealtimeCaches=true;preSrHistoryReset=true;
    ++cacheGeneration;lightingSceneDirty=true;
    resourceRecordingNs+=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-recordBegin).count();
}
