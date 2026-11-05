struct MemoryTexture {
    uint32_t width=0,height=0,mips=0,color=0,u=0,v=0,filter=0;
    std::vector<uint8_t> pixels;
    std::shared_ptr<donut::engine::LoadedTexture> loaded;
    uint64_t generation=0;uint32_t patchMip=0,patchX=0,patchY=0,patchWidth=0,patchHeight=0;
    bool patch=false,patchChanged=false,hasAlpha=false;uint64_t alphaPixels=0;
    bool sameLayout(const MemoryTexture& b)const{return width==b.width&&height==b.height&&mips==b.mips&&color==b.color&&u==b.u&&v==b.v&&filter==b.filter;}
};
std::unordered_map<int64_t,MemoryTexture> memoryTextures;
struct SpareMemoryTexture {MemoryTexture value;nvrhi::EventQueryHandle done,computeDone;size_t bytes;};
std::vector<SpareMemoryTexture> spareMemoryTextures;
struct TexturePatchStaging {nvrhi::TextureHandle texture;nvrhi::EventQueryHandle graphics,compute;uint64_t bytes=0;bool recording=false;};
std::vector<TexturePatchStaging> texturePatchStaging;
uint64_t texturePatchStagingBytes=0;
nvrhi::TextureHandle PatchStaging(const nvrhi::TextureDesc& desc){
    const uint64_t bytes=uint64_t(desc.width)*desc.height*4;
    for(auto& slot:texturePatchStaging)if(!slot.recording&&slot.texture->getDesc().width==desc.width&&slot.texture->getDesc().height==desc.height&&slot.texture->getDesc().format==desc.format
            &&(!slot.graphics||(GetDevice()->pollEventQuery(slot.graphics)&&GetDevice()->pollEventQuery(slot.compute)))){
        slot.recording=true;return slot.texture;}
    if(texturePatchStaging.size()>=64||texturePatchStagingBytes+bytes>16*1024*1024)return {};
    auto texture=GetDevice()->createTexture(desc);if(!texture)throw std::runtime_error("Texture patch staging allocation failed");
    texturePatchStaging.push_back({texture,{},{},bytes,true});texturePatchStagingBytes+=bytes;return texture;
}
size_t spareMemoryTextureBytes=0;
std::vector<uint8_t> meshUploadScratch;
std::map<std::pair<uint64_t,bool>,nvrhi::MemoryRequirements> memoryBufferRequirements;
std::map<std::vector<uint64_t>,nvrhi::MemoryRequirements> memoryBlasRequirements;
struct MemoryMeshBufferPage {
    RtrResourceArena::Storage vertexStorage,indexStorage;
    nvrhi::BufferHandle vertex,index;
    std::shared_ptr<donut::engine::DescriptorHandle> vertexDescriptor,indexDescriptor;
    uint64_t vertexBytes=0,indexBytes=0;
    size_t remaining=0;
    std::array<size_t,2> allocation{};
    std::vector<uint8_t> vertexUpload,indexUpload;
};
// The shared_ptr conversion preserves this derived deleter. Every live or
// retired mesh owns its page, even after another mesh from that page is removed.
struct MemoryMeshBufferGroup : donut::engine::BufferGroup {
    std::shared_ptr<MemoryMeshBufferPage> page;
    uint64_t vertexBase=0;
};
static uint64_t MemoryBufferCapacity(uint64_t bytes){return (std::max<uint64_t>(bytes,16)+4095)&~uint64_t(4095);}
static uint64_t LayoutMemoryMesh(const ResidentMesh& mesh){
    using donut::engine::VertexAttribute;
    auto& b=*mesh->buffers;
    const auto base=static_cast<MemoryMeshBufferGroup&>(b).vertexBase;
    uint64_t vertexBytes=base;auto range=[&](VertexAttribute attribute,size_t bytes){
        auto& r=b.getVertexBufferRange(attribute);r.byteOffset=vertexBytes;r.byteSize=(bytes+15)&~size_t(15);vertexBytes+=r.byteSize;
    };
    range(VertexAttribute::Position,b.positionData.size()*sizeof(float3));
    range(VertexAttribute::Normal,b.normalData.size()*4);
    range(VertexAttribute::Tangent,b.tangentData.size()*4);
    range(VertexAttribute::TexCoord1,b.texcoord1Data.size()*sizeof(float2));
    return vertexBytes-base;
}
nvrhi::rt::AccelStructDesc MemoryBlasDesc(const ResidentMesh& mesh){
    // Preflight runs before material baking and must not call the SDK helper,
    // which requires every new MaterialEx to already own a PTMaterial.
    nvrhi::rt::AccelStructDesc desc;desc.isTopLevel=false;
    for(auto& geometry:mesh->geometries){
        nvrhi::rt::GeometryDesc entry;entry.geometryType=nvrhi::rt::GeometryType::Triangles;
        auto& triangles=entry.geometryData.triangles;
        triangles.indexBuffer=mesh->buffers->indexBuffer;
        triangles.indexOffset=(mesh->indexOffset+geometry->indexOffsetInMesh)*sizeof(uint32_t);
        triangles.indexFormat=nvrhi::Format::R32_UINT;triangles.indexCount=geometry->numIndices;
        triangles.vertexBuffer=mesh->buffers->vertexBuffer;
        triangles.vertexOffset=(mesh->vertexOffset+geometry->vertexOffsetInMesh)*sizeof(float3)
            +mesh->buffers->getVertexBufferRange(donut::engine::VertexAttribute::Position).byteOffset;
        triangles.vertexFormat=nvrhi::Format::RGB32_FLOAT;triangles.vertexStride=sizeof(float3);triangles.vertexCount=geometry->numVertices;
        entry.flags=nvrhi::rt::GeometryFlags::Opaque;
        if(!nativeLighting.active){
            auto pt=static_cast<MaterialEx&>(*geometry->material).PTMaterial;
            const auto domain=geometry->material->domain;
            const bool alpha=pt?pt->EnableAlphaTesting:
                domain==donut::engine::MaterialDomain::AlphaTested||domain==donut::engine::MaterialDomain::TransmissiveAlphaTested;
            if(alpha||(pt&&pt->ExcludeFromNEE))entry.flags=nvrhi::rt::GeometryFlags::None;
            if((m_ui.AS.ExcludeTransmissive&&domain==donut::engine::MaterialDomain::Transmissive)||(pt&&pt->SkipRender)){
                constexpr float nan=std::numeric_limits<float>::quiet_NaN();
                entry.setTransform(nvrhi::rt::AffineTransform{nan,nan,nan,nan,nan,nan,nan,nan,nan,nan,nan,nan});
            }
        }
        desc.bottomLevelGeometries.push_back(entry);
    }
    desc.buildFlags=nvrhi::rt::AccelStructBuildFlags::PreferFastTrace|nvrhi::rt::AccelStructBuildFlags::AllowUpdate;
    desc.debugName="Retained mutable BLAS";desc.isVirtual=true;return desc;
}
void PrepareMemoryAllocations(const std::vector<ResidentMesh>& meshes){
    if(meshes.empty())return;
    if(RtrDiagnostics::level.load())++arenaWork[6];
    struct Plan {ResidentMesh mesh;size_t buffers=SIZE_MAX,blas=SIZE_MAX;std::array<size_t,3> allocation{SIZE_MAX,SIZE_MAX,SIZE_MAX};nvrhi::rt::AccelStructHandle acceleration;};
    std::vector<Plan> plans;plans.reserve(meshes.size());
    std::vector<bool> usedBuffers(spareMeshBuffers.size()),usedBlases(spareMeshBlases.size());
    std::vector<nvrhi::MemoryRequirements> requirements;requirements.reserve(meshes.size()*3);
    std::vector<std::shared_ptr<MemoryMeshBufferPage>> pages;
    // Small resources from one immutable transaction share bounded page pairs.
    // An isolated survivor can retain at most this payload, never the whole scene.
    for(size_t first=0;first<meshes.size();){
        uint64_t vertices=0,indices=0;size_t end=first;
        while(end<meshes.size()&&end-first<32){
            const auto v=LayoutMemoryMesh(meshes[end]),i=meshes[end]->buffers->indexData.size()*sizeof(uint32_t);
            if(v+i>1024*1024||vertices+indices+v+i>1024*1024)break;
            vertices+=v;indices+=i;++end;
        }
        if(end-first<2){first=std::max(first+1,end);continue;}
        auto page=std::make_shared<MemoryMeshBufferPage>();page->vertexBytes=vertices;page->indexBytes=indices;page->remaining=end-first;
        nvrhi::BufferDesc desc;desc.byteSize=MemoryBufferCapacity(vertices);desc.debugName="Retained transaction vertices";
        desc.isVirtual=true;desc.isVertexBuffer=true;desc.canHaveTypedViews=true;desc.canHaveRawViews=true;desc.isAccelStructBuildInput=true;
        desc.initialState=nvrhi::ResourceStates::VertexBuffer|nvrhi::ResourceStates::ShaderResource|nvrhi::ResourceStates::AccelStructBuildInput;desc.keepInitialState=true;
        page->vertex=GetDevice()->createBuffer(desc);
        desc.byteSize=MemoryBufferCapacity(indices);desc.debugName="Retained transaction indices";desc.isVertexBuffer=false;desc.isIndexBuffer=true;desc.format=nvrhi::Format::R32_UINT;
        desc.initialState=nvrhi::ResourceStates::IndexBuffer|nvrhi::ResourceStates::ShaderResource|nvrhi::ResourceStates::AccelStructBuildInput;
        page->index=GetDevice()->createBuffer(desc);
        if(!page->vertex||!page->index)throw std::runtime_error("Virtual transaction buffer preparation failed");
        size_t slot=0;for(auto buffer:{page->vertex,page->index}){
            const auto key=std::make_pair(buffer->getDesc().byteSize,slot==1);auto found=memoryBufferRequirements.find(key);
            if(found==memoryBufferRequirements.end()){
                RtrDiagnostics::Scope info(RtrDiagnostics::AllocationInfo);
                if(memoryBufferRequirements.size()>=256)memoryBufferRequirements.erase(memoryBufferRequirements.begin());
                found=memoryBufferRequirements.emplace(key,GetDevice()->getBufferMemoryRequirements(buffer)).first;
            }
            page->allocation[slot++]=requirements.size();requirements.push_back(found->second);
        }
        uint64_t vertexOffset=0,indexOffset=0;
        for(size_t i=first;i<end;++i){auto& mesh=meshes[i];auto& b=static_cast<MemoryMeshBufferGroup&>(*mesh->buffers);
            const auto bytes=LayoutMemoryMesh(mesh);b.page=page;b.vertexBase=vertexOffset;mesh->indexOffset=uint32_t(indexOffset/sizeof(uint32_t));
            b.vertexBuffer=page->vertex;b.indexBuffer=page->index;LayoutMemoryMesh(mesh);
            vertexOffset+=bytes;indexOffset+=b.indexData.size()*sizeof(uint32_t);
        }
        pages.push_back(std::move(page));first=end;
    }
    for(auto& mesh:meshes){
        Plan plan;plan.mesh=mesh;auto& b=*mesh->buffers;
        const auto vertexBytes=LayoutMemoryMesh(mesh),indexBytes=b.indexData.size()*4;
        const bool shared=bool(static_cast<MemoryMeshBufferGroup&>(b).page);
        uint64_t bestBytes=UINT64_MAX;
        for(size_t i=0;!shared&&i<spareMeshBuffers.size();++i){auto& spare=spareMeshBuffers[i];
            if(!usedBuffers[i]&&spare.vertex->getDesc().byteSize>=vertexBytes&&spare.index->getDesc().byteSize>=indexBytes&&spare.bytes<bestBytes){plan.buffers=i;bestBytes=spare.bytes;}}
        if(plan.buffers!=SIZE_MAX){auto& spare=spareMeshBuffers[plan.buffers];usedBuffers[plan.buffers]=true;
            b.vertexBuffer=spare.vertex;b.indexBuffer=spare.index;b.vertexBufferDescriptor=spare.vertexDescriptor;b.indexBufferDescriptor=spare.indexDescriptor;
        }else if(!shared){
            nvrhi::BufferDesc desc;desc.byteSize=MemoryBufferCapacity(vertexBytes);desc.debugName="Retained mutable vertices";
            desc.isVirtual=true;desc.isVertexBuffer=true;desc.canHaveTypedViews=true;desc.canHaveRawViews=true;desc.isAccelStructBuildInput=true;
            desc.initialState=nvrhi::ResourceStates::VertexBuffer|nvrhi::ResourceStates::ShaderResource|nvrhi::ResourceStates::AccelStructBuildInput;desc.keepInitialState=true;
            b.vertexBuffer=GetDevice()->createBuffer(desc);
            desc.byteSize=MemoryBufferCapacity(indexBytes);desc.debugName="Retained mutable indices";desc.isVertexBuffer=false;desc.isIndexBuffer=true;desc.format=nvrhi::Format::R32_UINT;
            desc.initialState=nvrhi::ResourceStates::IndexBuffer|nvrhi::ResourceStates::ShaderResource|nvrhi::ResourceStates::AccelStructBuildInput;
            b.indexBuffer=GetDevice()->createBuffer(desc);
            if(!b.vertexBuffer||!b.indexBuffer)throw std::runtime_error("Virtual mesh buffer preparation failed");
            size_t slot=0;for(auto buffer:{b.vertexBuffer,b.indexBuffer}){
                const auto key=std::make_pair(buffer->getDesc().byteSize,slot==1);auto found=memoryBufferRequirements.find(key);
                if(found==memoryBufferRequirements.end()){
                    RtrDiagnostics::Scope info(RtrDiagnostics::AllocationInfo,1,0,1,0,RtrDiagnostics::Identity(mesh->name));
                    auto value=GetDevice()->getBufferMemoryRequirements(buffer);
                    if(memoryBufferRequirements.size()>=256)memoryBufferRequirements.erase(memoryBufferRequirements.begin());
                    found=memoryBufferRequirements.emplace(key,value).first;
                }
                plan.allocation[slot++]=requirements.size();requirements.push_back(found->second);
            }
        }
        auto desc=MemoryBlasDesc(mesh);
        for(size_t i=0;i<spareMeshBlases.size();++i)if(!usedBlases[i]&&CompatibleBlas(spareMeshBlases[i].acceleration->getDesc(),desc)){
            plan.blas=i;usedBlases[i]=true;plan.acceleration=spareMeshBlases[i].acceleration;break;
        }
        if(!plan.acceleration){
            {RtrDiagnostics::Scope prebuild(RtrDiagnostics::ResourcePrebuild,1,mesh->totalIndices/3,1,0,RtrDiagnostics::Identity(mesh->name));plan.acceleration=GetDevice()->createAccelStruct(desc);}
            if(!plan.acceleration)throw std::runtime_error("Virtual BLAS preparation failed");
            std::vector<uint64_t> key{uint64_t(desc.buildFlags)};
            for(auto& geometry:desc.bottomLevelGeometries){auto& t=geometry.geometryData.triangles;
                key.insert(key.end(),{uint64_t(geometry.geometryType),uint64_t(geometry.flags),uint64_t(t.indexFormat),uint64_t(t.vertexFormat),t.indexCount,t.vertexCount,t.vertexStride});}
            auto found=memoryBlasRequirements.find(key);
            if(found==memoryBlasRequirements.end()){
                RtrDiagnostics::Scope info(RtrDiagnostics::AllocationInfo,1,0,1,0,RtrDiagnostics::Identity(mesh->name));
                auto value=GetDevice()->getAccelStructMemoryRequirements(plan.acceleration);
                if(memoryBlasRequirements.size()>=256)memoryBlasRequirements.erase(memoryBlasRequirements.begin());
                found=memoryBlasRequirements.emplace(std::move(key),value).first;
            }
            plan.allocation[2]=requirements.size();requirements.push_back(found->second);
        }
        plans.push_back(std::move(plan));
    }
    std::vector<RtrResourceArena::Storage> storage;
    if(!resourceArena.Reserve(requirements,storage)){
        memoryPreparationPending=true;
        // Relinquish unused cache ownership through the bounded release worker.
        // Live/retired readers keep their own leases; admission never destroys them.
        unsigned budget=8;for(size_t i=spareMeshBuffers.size();i--&&budget;)if(!usedBuffers[i]){auto bytes=spareMeshBuffers[i].bytes;
            if(!nativeLighting.reclaimer->Enqueue(spareMeshBuffers[i],bytes))break;
            spareMeshBuffers.erase(spareMeshBuffers.begin()+i);spareMeshBufferBytes-=bytes;--budget;if(RtrDiagnostics::level.load())++arenaWork[5];}
        budget=8;for(size_t i=spareMeshBlases.size();i--&&budget;)if(!usedBlases[i]){auto bytes=spareMeshBlases[i].bytes;
            if(!nativeLighting.reclaimer->Enqueue(spareMeshBlases[i],bytes))break;
            spareMeshBlases.erase(spareMeshBlases.begin()+i);spareMeshBlasBytes-=bytes;--budget;if(RtrDiagnostics::level.load())++arenaWork[5];}
        throw RtrResourcePending();
    }
    memoryPreparationPending=false;
    // No resident graph, pool membership, or GPU command has changed above.
    // Bind all new placed resources before transferring any pool ownership.
    for(auto& page:pages){
        page->vertexStorage=storage[page->allocation[0]];page->indexStorage=storage[page->allocation[1]];
        {RtrDiagnostics::Scope bind(RtrDiagnostics::PlacedBind,2);
            if(!GetDevice()->bindBufferMemory(page->vertex,page->vertexStorage->Heap(),page->vertexStorage->Offset())
                ||!GetDevice()->bindBufferMemory(page->index,page->indexStorage->Heap(),page->indexStorage->Offset()))throw std::runtime_error("Placed transaction buffer binding failed");}
        RtrDiagnostics::Scope descriptors(RtrDiagnostics::DescriptorAllocate,2);
        page->vertexDescriptor=std::make_shared<donut::engine::DescriptorHandle>(m_DescriptorTable->CreateDescriptorHandle(nvrhi::BindingSetItem::RawBuffer_SRV(0,page->vertex)));
        page->indexDescriptor=std::make_shared<donut::engine::DescriptorHandle>(m_DescriptorTable->CreateDescriptorHandle(nvrhi::BindingSetItem::RawBuffer_SRV(0,page->index)));
        if(RtrDiagnostics::level.load())arenaWork[4]+=page->vertex->getDesc().byteSize+page->index->getDesc().byteSize;
    }
    for(auto& plan:plans){auto& b=*plan.mesh->buffers;
        auto page=static_cast<MemoryMeshBufferGroup&>(b).page;
        if(RtrDiagnostics::level.load()){
            arenaWork[1]+=plan.buffers!=SIZE_MAX;arenaWork[2]+=plan.blas!=SIZE_MAX;
            arenaWork[3]+=LayoutMemoryMesh(plan.mesh)+b.indexData.size()*4;
            if(!page)arenaWork[4]+=b.vertexBuffer->getDesc().byteSize+b.indexBuffer->getDesc().byteSize;
        }
        if(page){b.vertexBufferDescriptor=page->vertexDescriptor;b.indexBufferDescriptor=page->indexDescriptor;}
        else if(plan.buffers==SIZE_MAX){
            RtrDiagnostics::Scope bind(RtrDiagnostics::PlacedBind,2,0,1,0,RtrDiagnostics::Identity(plan.mesh->name));
            auto& vertex=storage[plan.allocation[0]];auto& index=storage[plan.allocation[1]];
            if(!GetDevice()->bindBufferMemory(b.vertexBuffer,vertex->Heap(),vertex->Offset())||!GetDevice()->bindBufferMemory(b.indexBuffer,index->Heap(),index->Offset()))throw std::runtime_error("Placed mesh buffer binding failed");
            RtrDiagnostics::Scope descriptors(RtrDiagnostics::DescriptorAllocate,2,0,1,0,RtrDiagnostics::Identity(plan.mesh->name));
            b.vertexBufferDescriptor=std::make_shared<donut::engine::DescriptorHandle>(m_DescriptorTable->CreateDescriptorHandle(nvrhi::BindingSetItem::RawBuffer_SRV(0,b.vertexBuffer)));
            b.indexBufferDescriptor=std::make_shared<donut::engine::DescriptorHandle>(m_DescriptorTable->CreateDescriptorHandle(nvrhi::BindingSetItem::RawBuffer_SRV(0,b.indexBuffer)));
        }
        if(plan.blas==SIZE_MAX){auto& lease=storage[plan.allocation[2]];
            RtrDiagnostics::Scope bind(RtrDiagnostics::PlacedBind,1,0,1,0,RtrDiagnostics::Identity(plan.mesh->name));
            if(!GetDevice()->bindAccelStructMemory(plan.acceleration,lease->Heap(),lease->Offset()))throw std::runtime_error("Placed BLAS binding failed");}
    }
    for(auto& plan:plans){auto& b=*plan.mesh->buffers;
        if(!static_cast<MemoryMeshBufferGroup&>(b).page){
            auto vertex=plan.buffers==SIZE_MAX?storage[plan.allocation[0]]:spareMeshBuffers[plan.buffers].vertexStorage;
            auto index=plan.buffers==SIZE_MAX?storage[plan.allocation[1]]:spareMeshBuffers[plan.buffers].indexStorage;
            resourceBufferAllocations.insert_or_assign(b.vertexBuffer.Get(),vertex);resourceBufferAllocations.insert_or_assign(b.indexBuffer.Get(),index);
        }
        auto lease=plan.blas==SIZE_MAX?storage[plan.allocation[2]]:spareMeshBlases[plan.blas].storage;
        auto bytes=plan.blas==SIZE_MAX?lease->Capacity():spareMeshBlases[plan.blas].bytes;
        resourceBlasAllocationBytes.insert_or_assign(plan.acceleration.Get(),BlasAllocation{bytes,lease});preparedMeshBlases.insert_or_assign(plan.mesh->name,plan.acceleration);
    }
    for(size_t i=usedBuffers.size();i--;)if(usedBuffers[i]){spareMeshBufferBytes-=spareMeshBuffers[i].bytes;spareMeshBuffers.erase(spareMeshBuffers.begin()+i);}
    for(size_t i=usedBlases.size();i--;)if(usedBlases[i]){spareMeshBlasBytes-=spareMeshBlases[i].bytes;spareMeshBlases.erase(spareMeshBlases.begin()+i);}
}
void UploadMemoryMesh(const ResidentMesh& mesh,nvrhi::ICommandList* command){
    static_cast<MeshInfoEx*>(mesh.get())->HostIndices=std::make_shared<const std::vector<uint32_t>>(mesh->buffers->indexData);
    static_cast<MeshInfoEx*>(mesh.get())->HostDynamicCoverage=true;
    using donut::engine::VertexAttribute;auto& b=*mesh->buffers;
    const auto vertexBytes=LayoutMemoryMesh(mesh),indexBytes=b.indexData.size()*4;
    // Owner-thread staging capacity is reused across meshes and transactions.
    // writeBuffer copies into the command list's own retained upload chunks.
    RtrDiagnostics::Scope packScope(RtrDiagnostics::PayloadPack,b.positionData.size(),0,1,0,RtrDiagnostics::Identity(mesh->name),RtrDiagnostics::revision.load(),0,vertexBytes);
    std::vector<uint8_t> oversized;
    auto& packed=vertexBytes<=64*1024*1024?meshUploadScratch:oversized;packed.resize(vertexBytes);
    auto& memory=static_cast<MemoryMeshBufferGroup&>(b);
    auto pack=[&](VertexAttribute attribute,const auto& data){if(!data.empty())std::memcpy(packed.data()+b.getVertexBufferRange(attribute).byteOffset-memory.vertexBase,data.data(),data.size()*sizeof(data[0]));};
    pack(VertexAttribute::Position,b.positionData);pack(VertexAttribute::Normal,b.normalData);pack(VertexAttribute::Tangent,b.tangentData);pack(VertexAttribute::TexCoord1,b.texcoord1Data);
    if(auto& page=memory.page;page){
        if(page->vertexUpload.empty()){page->vertexUpload.resize(page->vertexBytes);page->indexUpload.resize(page->indexBytes);}
        std::memcpy(page->vertexUpload.data()+memory.vertexBase,packed.data(),vertexBytes);
        std::memcpy(page->indexUpload.data()+size_t(mesh->indexOffset)*4,b.indexData.data(),indexBytes);
        if(--page->remaining==0){
            RtrDiagnostics::Scope upload(RtrDiagnostics::BufferUpload,2,0,1,0,0,RtrDiagnostics::revision.load(),0,page->vertexBytes+page->indexBytes);
            command->writeBuffer(page->vertex,page->vertexUpload.data(),page->vertexUpload.size());
            command->writeBuffer(page->index,page->indexUpload.data(),page->indexUpload.size());
            std::vector<uint8_t>().swap(page->vertexUpload);std::vector<uint8_t>().swap(page->indexUpload);
        }
    }else{RtrDiagnostics::Scope upload(RtrDiagnostics::BufferUpload,2,0,1,0,RtrDiagnostics::Identity(mesh->name),RtrDiagnostics::revision.load(),0,vertexBytes+indexBytes);
        command->writeBuffer(b.vertexBuffer,packed.data(),packed.size());command->writeBuffer(b.indexBuffer,b.indexData.data(),indexBytes);}
    std::vector<float3>().swap(b.positionData);std::vector<float2>().swap(b.texcoord1Data);
    std::vector<uint32_t>().swap(b.normalData);std::vector<uint32_t>().swap(b.tangentData);std::vector<uint32_t>().swap(b.indexData);
}
void ApplyMemoryResources(const void* bytes,size_t size,uint64_t revision,
        std::unordered_map<std::string,donut::engine::SceneGraphNode*>& nodes){
    if(!managedLighting||!m_scene||revision<resourceRevision)throw std::invalid_argument("Invalid resource transaction revision");
    if(resourceRetirement.size()>=16)throw std::runtime_error("Resource generation ring is full; retry after GPU completion");
    const auto sequence=RtrDiagnostics::frame.load();
    if(memoryPreparationRevision!=revision||memoryPreparationSequence!=sequence){
        // A cancelled transaction must not prevent a later delete-only request
        // from freeing the capacity that the earlier request was waiting for.
        // These preparations have never been submitted and own no GPU readers.
        preparedResourceTlas={};preparedTlasTables={};preparedTlasTableStorage={};preparedTlasAliases=false;
        memoryPreparationPending=false;memoryPreparationRevision=revision;memoryPreparationSequence=sequence;
    }
    if(memoryPreparationPending&&!resourceArena.PreparationReady())throw RtrResourcePending();
    RtrDiagnostics::Scope stagingScope(RtrDiagnostics::ResourceStaging);
    std::optional<RtrDiagnostics::Scope> part;part.emplace(RtrDiagnostics::Import);
    RtrResourceReader r(bytes,size);if(r.value<uint32_t>()!=0x52445254)throw std::invalid_argument("Unknown resource transport");
    std::unordered_map<int64_t,MemoryTexture> textures;
    auto n=r.count();for(uint32_t i=0;i<n;i++){
        auto id=r.value<int64_t>();MemoryTexture t;t.width=r.value<uint32_t>();t.height=r.value<uint32_t>();t.mips=r.value<uint32_t>();
        t.color=r.value<uint32_t>();t.u=r.value<uint32_t>();t.v=r.value<uint32_t>();t.filter=r.value<uint32_t>();t.generation=r.value<uint64_t>();
        const auto transport=r.value<uint32_t>();if(transport>1||!t.generation)throw std::invalid_argument("Invalid texture generation transport");
        if(transport==0)t.pixels=r.bytes();else{
            const auto parent=r.value<uint64_t>();t.patch=true;t.patchMip=r.value<uint32_t>();t.patchX=r.value<uint32_t>();t.patchY=r.value<uint32_t>();
            t.patchWidth=r.value<uint32_t>();t.patchHeight=r.value<uint32_t>();const auto pitch=r.value<uint32_t>();const auto pixels=r.bytes();
            auto old=memoryTextures.find(id);
            if(old==memoryTextures.end()||old->second.generation!=parent||!old->second.sameLayout(t)||t.patchMip>=t.mips||!t.patchWidth||!t.patchHeight)throw std::invalid_argument("Stale or invalid texture patch");
            const uint32_t w=std::max(1u,t.width>>t.patchMip),h=std::max(1u,t.height>>t.patchMip);
            if(uint64_t(t.patchX)+t.patchWidth>w||uint64_t(t.patchY)+t.patchHeight>h||uint64_t(pitch)<uint64_t(t.patchWidth)*4
                ||uint64_t(pitch)*(t.patchHeight-1)+uint64_t(t.patchWidth)*4!=pixels.size())throw std::invalid_argument("Invalid texture patch range");
            {RtrDiagnostics::Scope copy(RtrDiagnostics::PayloadPack,1,0,1,0,uint64_t(id),t.generation,RtrDiagnostics::Texture|RtrDiagnostics::IndexMigration,old->second.pixels.size());t.pixels=old->second.pixels;}t.alphaPixels=old->second.alphaPixels;size_t offset=0;for(uint32_t mip=0;mip<t.patchMip;++mip)offset+=size_t(std::max(1u,t.width>>mip))*std::max(1u,t.height>>mip)*4;
            for(uint32_t row=0;row<t.patchHeight;++row){auto* target=t.pixels.data()+offset+(size_t(t.patchY+row)*w+t.patchX)*4;
                t.patchChanged|=std::memcmp(target,pixels.data()+size_t(row)*pitch,size_t(t.patchWidth)*4)!=0;
                for(uint32_t x=0;x<t.patchWidth;++x){if(target[x*4+3]!=255)--t.alphaPixels;if(pixels[size_t(row)*pitch+x*4+3]!=255)++t.alphaPixels;}
                std::memcpy(target,pixels.data()+size_t(row)*pitch,size_t(t.patchWidth)*4);}
        }
        uint64_t required=0;uint32_t w=t.width,h=t.height;
        if(id<0||!w||!h||w>16384||h>16384||!t.mips||t.mips>15||t.color>1||t.u>1||t.v>1||t.filter>1)throw std::invalid_argument("Invalid texture metadata");
        for(uint32_t mip=0;mip<t.mips;mip++){required+=uint64_t(w)*h*4;w=std::max(1u,w/2);h=std::max(1u,h/2);}
        if(required!=t.pixels.size())throw std::invalid_argument("Invalid texture data");
        if(!t.patch)for(size_t a=3;a<t.pixels.size();a+=4)t.alphaPixels+=t.pixels[a]!=255;
        t.hasAlpha=t.alphaPixels!=0;
        if(!textures.emplace(id,std::move(t)).second)throw std::invalid_argument("Duplicate texture identity");
    }
    donut::engine::SceneImportResult imported;imported.rootNode=std::make_shared<donut::engine::SceneGraphNode>();
    struct MaterialInput{std::shared_ptr<MaterialEx> material;std::array<int64_t,4> textures;float ior;bool boundary=false;float3 absorption={};int priority=0;};
    std::vector<MaterialInput> materialInputs;std::unordered_map<std::string,ResidentMaterial> materials;
    std::unordered_set<std::string> coverageMaterials;
    n=r.count();for(uint32_t i=0;i<n;i++){
        MaterialInput input;auto m=std::make_shared<MaterialEx>();input.material=m;m->name=r.text();auto rgba=r.value<uint32_t>();
        m->baseOrDiffuseColor=float3(float(rgba&255),float((rgba>>8)&255),float((rgba>>16)&255))/255.f;
        m->opacity=float(rgba>>24)/255.f*r.value<float>();m->roughness=r.value<float>();m->metalness=r.value<float>();
        m->transmissionFactor=r.value<float>();m->alphaCutoff=r.value<float>();auto blend=r.value<uint32_t>();m->doubleSided=r.value<uint32_t>()!=0;
        auto emissive=r.value<uint32_t>();m->emissiveColor=float3(float(emissive&255),float((emissive>>8)&255),float((emissive>>16)&255))/255.f;
        m->emissiveIntensity=r.value<float>();input.ior=r.value<float>();
        if(blend>2||!std::isfinite(input.ior)||input.ior<1||!std::isfinite(m->roughness)||!std::isfinite(m->opacity))throw std::invalid_argument("Invalid material input");
        m->domain=donut::engine::MaterialDomain(blend+(m->transmissionFactor>0?3:0));
        for(auto& id:input.textures){id=r.value<int64_t>();if(id>=0&&!textures.count(id)&&!memoryTextures.count(id))throw std::invalid_argument("Unknown texture reference");}
        bool candidate=blend!=0||m->opacity<1;
        if(auto id=input.textures[0];id>=0){auto next=textures.find(id);candidate|=next!=textures.end()?next->second.hasAlpha:memoryTextures.at(id).hasAlpha;}
        if(candidate)coverageMaterials.insert(m->name);
        input.boundary=r.value<uint32_t>()!=0;if(input.boundary){input.absorption.x=r.value<float>();input.absorption.y=r.value<float>();input.absorption.z=r.value<float>();input.priority=r.value<int32_t>();}
        if(!materials.emplace(m->name,m).second)throw std::invalid_argument("Duplicate material identity");
        imported.materials.push_back(m);materialInputs.push_back(std::move(input));
    }
    std::vector<ResidentMesh> newMeshes;n=r.count();for(uint32_t i=0;i<n;i++){
        if((i&63)==63&&RtrHostResources::eventPump)RtrHostResources::eventPump();
        auto mesh=std::make_shared<MeshInfoEx>();mesh->name=r.text();auto p=r.floats(),normal=r.floats(),tangent=r.floats(),uv=r.floats();
        if(p.empty()||p.size()%3||normal.size()!=p.size()||(!tangent.empty()&&tangent.size()!=p.size()/3*4)||(!uv.empty()&&uv.size()!=p.size()/3*2))throw std::invalid_argument("Invalid mesh attributes");
        const size_t vertices=p.size()/3;mesh->buffers=std::make_shared<MemoryMeshBufferGroup>();auto& b=*mesh->buffers;
        b.positionData.resize(vertices);std::memcpy(b.positionData.data(),p.data(),p.size()*4);
        b.normalData.resize(vertices);b.texcoord1Data.resize(vertices);
        for(size_t v=0;v<vertices;v++){
            b.normalData[v]=donut::math::vectorToSnorm8(float3(normal[v*3],normal[v*3+1],normal[v*3+2]));
            if(!uv.empty())b.texcoord1Data[v]=float2(uv[v*2],uv[v*2+1]);
            mesh->objectSpaceBounds|=b.positionData[v];
        }
        auto groups=r.count();if(!groups)throw std::invalid_argument("Mesh requires geometry groups");
        for(uint32_t g=0;g<groups;g++){
            auto name=r.text();auto m=materials.find(name);auto old=residentMaterials.find(name);
            if(m==materials.end()&&old==residentMaterials.end())throw std::invalid_argument("Unknown geometry material");
            auto geometry=std::make_shared<MeshGeometryEx>();geometry->material=m!=materials.end()?m->second:old->second;
            geometry->indexOffsetInMesh=uint32_t(b.indexData.size());geometry->numIndices=r.count();geometry->numVertices=uint32_t(vertices);geometry->objectSpaceBounds=mesh->objectSpaceBounds;
            if(geometry->numIndices%3)throw std::invalid_argument("Invalid triangle index count");
            for(uint32_t k=0;k<geometry->numIndices;k++){auto index=r.value<uint32_t>();if(index>=vertices)throw std::invalid_argument("Triangle index exceeds vertices");b.indexData.push_back(index);}
            mesh->geometries.push_back(geometry);
        }
        bool needsTangent=false;
        for(auto& geometry:mesh->geometries){
            auto input=std::find_if(materialInputs.begin(),materialInputs.end(),[&](const auto& value){return value.material->name==geometry->material->name;});
            needsTangent|=input!=materialInputs.end()?input->textures[1]>=0:bool(geometry->material->normalTexture);
        }
        if(needsTangent)b.tangentData.resize(vertices);
        if(needsTangent&&!tangent.empty())for(size_t v=0;v<vertices;v++)b.tangentData[v]=donut::math::vectorToSnorm8(float4(tangent[v*4],tangent[v*4+1],tangent[v*4+2],tangent[v*4+3]));
        if(needsTangent&&tangent.empty()){
            std::vector<float3> tangents(vertices),bitangents(vertices);
            for(size_t k=0;k<b.indexData.size();k+=3){auto a=b.indexData[k],c=b.indexData[k+1],d=b.indexData[k+2];
                auto e1=b.positionData[c]-b.positionData[a],e2=b.positionData[d]-b.positionData[a];auto u1=b.texcoord1Data[c]-b.texcoord1Data[a],u2=b.texcoord1Data[d]-b.texcoord1Data[a];
                float determinant=u1.x*u2.y-u1.y*u2.x;
                if(std::abs(determinant)>1e-20f){auto t=(e1*u2.y-e2*u1.y)/determinant,bt=(e2*u1.x-e1*u2.x)/determinant;
                    float tl=length(t),bl=length(bt);if(tl>0&&bl>0)for(auto v:{a,c,d}){tangents[v]+=t/tl;bitangents[v]+=bt/bl;}}}
            for(size_t v=0;v<vertices;v++){float3 normalValue(normal[v*3],normal[v*3+1],normal[v*3+2]);auto t=tangents[v]-normalValue*dot(normalValue,tangents[v]);float l=length(t);
                if(l>0)t/=l;else{auto axis=std::abs(normalValue.x)<.9f?float3(1,0,0):float3(0,1,0);auto fallback=cross(normalValue,axis);float magnitude=length(fallback);t=magnitude>0?fallback/magnitude:float3(1,0,0);}
                float sign=dot(cross(normalValue,t),bitangents[v])<0?-1.f:1.f;b.tangentData[v]=donut::math::vectorToSnorm8(float4(t,sign));}
        }
        mesh->totalVertices=uint32_t(vertices);mesh->totalIndices=uint32_t(b.indexData.size());newMeshes.push_back(mesh);
    }
    Json::Value json;json["removeNodes"]=Json::Value(Json::arrayValue);json["nodes"]=Json::Value(Json::arrayValue);json["coverageChangedMaterials"]=Json::Value(Json::arrayValue);
    n=r.count();for(uint32_t i=0;i<n;i++)json["removeNodes"].append(r.text());
    std::unordered_set<std::string> meshNames;for(auto& mesh:newMeshes)if(!meshNames.insert(mesh->name).second)throw std::invalid_argument("Duplicate mesh identity");
    n=r.count();for(uint32_t i=0;i<n;i++){Json::Value node;node["name"]=r.text();auto mesh=r.text();
        if(!meshNames.count(mesh)&&!residentMeshes.count(mesh))throw std::invalid_argument("Unknown instance mesh");node["mesh"]=mesh;
        auto values=r.floats();if(values.size()!=12)throw std::invalid_argument("Transform requires twelve floats");for(auto v:values)node["transform"].append(v);json["nodes"].append(node);}
    n=r.count();for(uint32_t i=0;i<n;i++)json["coverageChangedMaterials"].append(r.text());
    std::vector<int64_t> removedTextures;n=r.count();for(uint32_t i=0;i<n;i++){auto id=r.value<int64_t>();if(textures.count(id))throw std::invalid_argument("Texture upsert/remove conflict");removedTextures.push_back(id);}r.finish();
    resourceChangeReasons.clear();uint64_t plannedBytes=0,plannedTriangles=0;
    for(auto& mesh:newMeshes){
        auto old=residentMeshes.find(mesh->name);uint64_t reason=RtrDiagnostics::Inflow;
        if(old!=residentMeshes.end()){
            auto* prior=static_cast<MeshInfoEx*>(old->second.get());
            bool same=prior->geometries.size()==mesh->geometries.size()&&prior->HostIndices&&*prior->HostIndices==mesh->buffers->indexData;
            if(same)for(size_t g=0;g<mesh->geometries.size();++g){auto a=prior->geometries[g],b=mesh->geometries[g];
                same&=a->numVertices==b->numVertices&&a->numIndices==b->numIndices&&a->material->name==b->material->name;}
            reason=same?RtrDiagnostics::Deformation:RtrDiagnostics::Topology;
            if(same&&prior->HostRefitFrames>=16)reason|=RtrDiagnostics::RefitRefresh;
        }
        resourceChangeReasons.emplace(mesh->name,reason);
        uint64_t payload=uint64_t(mesh->totalVertices)*24+mesh->buffers->tangentData.size()*4+uint64_t(mesh->totalIndices)*4;
        plannedBytes+=payload;plannedTriangles+=mesh->totalIndices/3;
        RtrDiagnostics::Scope entry(RtrDiagnostics::ResourcePlan,1,mesh->totalIndices/3,1,0,RtrDiagnostics::Identity(mesh->name),revision,reason,payload);
    }
    for(auto& [id,t]:textures){plannedBytes+=t.patch?uint64_t(t.patchWidth)*t.patchHeight*4:t.pixels.size();
        RtrDiagnostics::Scope entry(RtrDiagnostics::ResourcePlan,1,0,1,0,uint64_t(id),t.generation,RtrDiagnostics::Texture,t.patch?uint64_t(t.patchWidth)*t.patchHeight*4:t.pixels.size());}
    for(auto& node:json["nodes"])if(!resourceChangeReasons.count(node["mesh"].asString())){
        RtrDiagnostics::Scope entry(RtrDiagnostics::ResourcePlan,1,0,1,0,RtrDiagnostics::Identity(node["name"].asString()),revision,RtrDiagnostics::Pose);}
    for(auto& node:json["removeNodes"]){RtrDiagnostics::Scope entry(RtrDiagnostics::ResourcePlan,1,0,1,0,RtrDiagnostics::Identity(node.asString()),revision,RtrDiagnostics::Removal);}
    for(auto& m:imported.materials){RtrDiagnostics::Scope entry(RtrDiagnostics::ResourcePlan,1,0,1,0,RtrDiagnostics::Identity(m->name),revision,RtrDiagnostics::Material|(coverageMaterials.count(m->name)?RtrDiagnostics::Coverage:0));}
    {RtrDiagnostics::Scope budget(RtrDiagnostics::ResourcePlan,newMeshes.size(),plannedTriangles,1,0,0,revision,0,plannedBytes);}
    // All references and byte ranges are validated before staging or graph mutation.
    if(!json["removeNodes"].empty()||!json["nodes"].empty()){
        auto graph=m_scene->GetSceneGraph();size_t instanceCapacity=graph->GetMeshInstances().size();
        size_t subCapacity=graph->GetGeometryInstancesCount();
        bool topology=false;
        std::unordered_map<donut::engine::MeshInfo*,int64_t> meshUserChanges;
        std::unordered_map<std::string,ResidentMesh> plannedMeshes;
        for(auto& mesh:newMeshes){auto old=residentMeshes.find(mesh->name);
            plannedMeshes.emplace(mesh->name,old!=residentMeshes.end()&&CompatibleMemoryMesh(old->second,mesh)?old->second:mesh);
        }
        std::unordered_set<donut::engine::SceneGraphNode*> removedNodes;
        auto subtract=[&](auto&& self,donut::engine::SceneGraphNode* node)->void{
            if(!removedNodes.insert(node).second)return;
            topology=true;
            if(auto instance=std::dynamic_pointer_cast<donut::engine::MeshInstance>(node->GetLeaf())){
                --instanceCapacity;subCapacity-=instance->GetMesh()->geometries.size();
                --meshUserChanges[instance->GetMesh().get()];
            }
            for(uint32_t i=0;i<node->GetNumChildren();++i)self(self,node->GetChild(i));
        };
        for(auto& name:json["removeNodes"]){auto found=nodes.find(name.asString());if(found!=nodes.end())subtract(subtract,found->second);}
        std::unordered_set<std::string> plannedNodes;
        for(auto& node:json["nodes"]){
            const auto identity=node["name"].asString();if(!plannedNodes.insert(identity).second)throw std::invalid_argument("Duplicate instance identity");
            auto previous=nodes.find(identity);
            const auto name=node["mesh"].asString();
            auto prepared=plannedMeshes.find(name);auto mesh=prepared!=plannedMeshes.end()?prepared->second:residentMeshes.at(name);
            std::shared_ptr<donut::engine::MeshInstance> prior;
            if(previous!=nodes.end()&&!removedNodes.count(previous->second))prior=std::dynamic_pointer_cast<donut::engine::MeshInstance>(previous->second->GetLeaf());
            if(prior){subCapacity-=prior->GetMesh()->geometries.size();--meshUserChanges[prior->GetMesh().get()];}
            else ++instanceCapacity;
            topology|=!prior||prior->GetMesh()!=mesh;
            ++meshUserChanges[mesh.get()];subCapacity+=mesh->geometries.size();
        }
        if(topology){
            size_t meshCapacity=graph->GetMeshes().size();
            for(const auto& [mesh,change]:meshUserChanges){auto users=residentMeshNodes.find(mesh);
                const int64_t before=users==residentMeshNodes.end()?0:int64_t(users->second.size()),after=before+change;
                if(after<0)throw std::runtime_error("Invalid retained mesh ownership plan");
                if(before==0&&after>0)++meshCapacity;else if(before>0&&after==0)--meshCapacity;
            }
            PrepareResourceTlas(instanceCapacity,subCapacity,meshCapacity);
        }
    }
    PrepareMemoryAllocations(newMeshes);
    if(RtrDiagnostics::level.load())for(auto& mesh:newMeshes)dynamicWork[14]+=uint64_t(mesh->totalVertices)*24+mesh->buffers->tangentData.size()*4+uint64_t(mesh->totalIndices)*4;
    part.reset();part.emplace(RtrDiagnostics::Upload);
    auto command=ResourceCommand();command->open();
    if(!newMeshes.empty()||preparedTlasAliases){
        // Every lease is obtained after all readers of its previous owner have
        // retired. Activate placed-resource aliases before their first use.
        command->commitBarriers();
        auto* list=static_cast<ID3D12GraphicsCommandList*>(command->getNativeObject(nvrhi::ObjectTypes::D3D12_GraphicsCommandList));
        if(GetDevice()->queryFeatureSupport(nvrhi::Feature::EnhancedBarriers)){
            // Enhanced buffers have no legacy alias activation state. Order
            // memory reuse with their native global barrier model instead.
            Microsoft::WRL::ComPtr<ID3D12GraphicsCommandList7> enhanced;
            if(FAILED(list->QueryInterface(IID_PPV_ARGS(&enhanced))))throw std::runtime_error("Enhanced resource alias barrier unavailable");
            D3D12_GLOBAL_BARRIER alias{};alias.SyncBefore=D3D12_BARRIER_SYNC_ALL;alias.SyncAfter=D3D12_BARRIER_SYNC_ALL;
            alias.AccessBefore=D3D12_BARRIER_ACCESS_COMMON;alias.AccessAfter=D3D12_BARRIER_ACCESS_COMMON;
            D3D12_BARRIER_GROUP group{};group.Type=D3D12_BARRIER_TYPE_GLOBAL;group.NumBarriers=1;group.pGlobalBarriers=&alias;
            enhanced->Barrier(1,&group);
        }else{
            D3D12_RESOURCE_BARRIER alias{};alias.Type=D3D12_RESOURCE_BARRIER_TYPE_ALIASING;list->ResourceBarrier(1,&alias);
        }
        preparedTlasAliases=false;
    }
    size_t uploadedMeshes=0;for(auto& mesh:newMeshes){
        auto* extended=static_cast<MeshInfoEx*>(mesh.get());
        for(auto& geometry:mesh->geometries)extended->HostCoverageCandidates|=coverageMaterials.count(geometry->material->name)!=0||geometry->material->domain!=donut::engine::MaterialDomain::Opaque||geometry->material->opacity<1;
        UploadMemoryMesh(mesh,command);if((++uploadedMeshes&63)==0&&RtrHostResources::eventPump)RtrHostResources::eventPump();}
    for(auto& [id,t]:textures){auto old=memoryTextures.find(id);bool compatible=old!=memoryTextures.end()&&old->second.sameLayout(t);
        if(compatible&&((t.patch&&!t.patchChanged)||(!t.patch&&old->second.pixels==t.pixels))){t.loaded=old->second.loaded;continue;}
        std::shared_ptr<donut::engine::LoadedTexture> loaded;
        for(auto i=spareMemoryTextures.begin();i!=spareMemoryTextures.end();++i)if(t.sameLayout(i->value)&&GetDevice()->pollEventQuery(i->done)&&GetDevice()->pollEventQuery(i->computeDone)){
            loaded=i->value.loaded;spareMemoryTextureBytes-=i->bytes;spareMemoryTextures.erase(i);++reusedTextureAllocations;break;
        }
        auto desc=nvrhi::TextureDesc().setWidth(t.width).setHeight(t.height).setMipLevels(t.mips).setFormat(t.color?nvrhi::Format::SRGBA8_UNORM:nvrhi::Format::RGBA8_UNORM)
            .setInitialState(nvrhi::ResourceStates::ShaderResource).setKeepInitialState(true);desc.debugName="Retained memory texture generation";
        if(!loaded){
            loaded=std::make_shared<donut::engine::LoadedTexture>();loaded->isMemorySource=true;loaded->path="memory/texture-"+std::to_string(id);loaded->originalBitsPerPixel=32;
            loaded->loadOptions.sRGBMode=t.color?donut::engine::SRGBMode::ForceSRGB:donut::engine::SRGBMode::ForceLinear;
            {RtrDiagnostics::Scope allocation(RtrDiagnostics::TextureAllocate,1,0,1,0,uint64_t(id),t.generation,RtrDiagnostics::Texture|RtrDiagnostics::CapacityGrowth,t.pixels.size());loaded->texture=GetDevice()->createTexture(desc);}if(!loaded->texture)throw std::runtime_error("Texture allocation failed");
            {RtrDiagnostics::Scope descriptor(RtrDiagnostics::DescriptorAllocate,1,0,1,0,uint64_t(id),t.generation,RtrDiagnostics::Texture);loaded->bindlessDescriptor=m_DescriptorTable->CreateDescriptorHandle(nvrhi::BindingSetItem::Texture_SRV(0,loaded->texture));}
        }
        RtrDiagnostics::Scope upload(RtrDiagnostics::TextureUpload,t.mips,0,1,0,uint64_t(id),t.generation,RtrDiagnostics::Texture,t.patch?uint64_t(t.patchWidth)*t.patchHeight*4:t.pixels.size());
        uint32_t w=t.width,h=t.height;size_t offset=0;
        for(uint32_t mip=0;mip<t.mips;mip++){
            size_t bytesInMip=size_t(w)*h*4;
            bool equal=compatible&&(t.patch?mip!=t.patchMip:std::memcmp(t.pixels.data()+offset,old->second.pixels.data()+offset,bytesInMip)==0);
            if(equal){command->copyTexture(loaded->texture,nvrhi::TextureSlice().setMipLevel(mip),old->second.loaded->texture,nvrhi::TextureSlice().setMipLevel(mip));textureGpuCopiedBytes+=bytesInMip;}
            else if(compatible&&bytesInMip>4096){
                uint32_t x0=t.patch?t.patchX:w,y0=t.patch?t.patchY:h,x1=t.patch?t.patchX+t.patchWidth:0,y1=t.patch?t.patchY+t.patchHeight:0;
                if(!t.patch)for(uint32_t y=0;y<h;y++)for(uint32_t x=0;x<w;x++){
                    size_t pixel=offset+(size_t(y)*w+x)*4;
                    if(std::memcmp(t.pixels.data()+pixel,old->second.pixels.data()+pixel,4)!=0){x0=std::min(x0,x);y0=std::min(y0,y);x1=std::max(x1,x+1);y1=std::max(y1,y+1);}}
                command->copyTexture(loaded->texture,nvrhi::TextureSlice().setMipLevel(mip),old->second.loaded->texture,nvrhi::TextureSlice().setMipLevel(mip));
                textureGpuCopiedBytes+=bytesInMip;
                auto patchDesc=desc;patchDesc.width=x1-x0;patchDesc.height=y1-y0;patchDesc.mipLevels=1;patchDesc.debugName="Texture rectangle staging";
                auto patch=PatchStaging(patchDesc);
                if(!patch){command->writeTexture(loaded->texture,0,mip,t.pixels.data()+offset,w*4);
                    if(RtrDiagnostics::level.load()!=0){dynamicWork[5]+=bytesInMip;++dynamicWork[13];}
                    offset+=bytesInMip;w=std::max(1u,w/2);h=std::max(1u,h/2);continue;}
                command->writeTexture(patch,0,0,t.pixels.data()+offset+(size_t(y0)*w+x0)*4,w*4);
                auto target=nvrhi::TextureSlice().setMipLevel(mip);target.x=x0;target.y=y0;target.width=x1-x0;target.height=y1-y0;
                command->copyTexture(loaded->texture,target,patch,nvrhi::TextureSlice());
                textureGpuCopiedBytes+=uint64_t(x1-x0)*(y1-y0)*4;
                if(RtrDiagnostics::level.load()!=0){dynamicWork[5]+=uint64_t(x1-x0)*(y1-y0)*4;++dynamicWork[13];}
            }else{command->writeTexture(loaded->texture,0,mip,t.pixels.data()+offset,w*4);if(RtrDiagnostics::level.load()!=0){dynamicWork[5]+=bytesInMip;++dynamicWork[13];}}
            offset+=bytesInMip;w=std::max(1u,w/2);h=std::max(1u,h/2);
        }
        t.loaded=loaded;
    }
    part.reset();
    auto texture=[&](int64_t id)->std::shared_ptr<donut::engine::LoadedTexture>{if(id<0)return {};auto t=textures.find(id);return t!=textures.end()?t->second.loaded:memoryTextures.at(id).loaded;};
    for(auto& input:materialInputs){auto& m=*input.material;m.baseOrDiffuseTexture=texture(input.textures[0]);m.normalTexture=texture(input.textures[1]);m.metalRoughOrSpecularTexture=texture(input.textures[2]);m.emissiveTexture=texture(input.textures[3]);
        uint32_t flags=0;for(size_t slot=0;slot<4;slot++)if(input.textures[slot]>=0){auto id=input.textures[slot];auto found=textures.find(id);auto& t=found!=textures.end()?found->second:memoryTextures.at(id);
            uint32_t policy=t.u|(t.v<<1)|((t.filter==0?1u:0u)<<2);flags|=policy<<(12+slot*3);}
        m.hostTextureSampling=flags;
    }
    auto graph=m_scene->GetSceneGraph();for(auto& mesh:newMeshes)graph->AttachLeafNode(imported.rootNode,std::make_shared<MeshInstanceEx>(mesh));
    CommitResources(imported,json,nodes,true,command);
    for(auto& slot:texturePatchStaging)if(slot.recording){slot.graphics=resourceRetirement.back().done;slot.compute=resourceRetirement.back().computeDone;slot.recording=false;}
    resourceRetirement.back().recycleCommands=size<=4*1024*1024;
    for(auto& input:materialInputs){auto target=residentMaterials.at(input.material->name);auto* pt=PTMaterial::SafeCast(target);pt->IoR=input.ior;if(input.boundary){pt->VolumeAttenuationColor=float3(std::exp(-input.absorption.x),std::exp(-input.absorption.y),std::exp(-input.absorption.z));pt->VolumeAttenuationDistance=1;pt->NestedPriority=input.priority;}pt->GPUDataDirty=true;}
    for(auto& [id,t]:textures){
        auto old=memoryTextures.find(id);
        if(old!=memoryTextures.end()&&old->second.loaded!=t.loaded&&!residentTextureUsers.count(old->second.loaded.get())
                &&spareMemoryTextures.size()<128&&spareMemoryTextureBytes+old->second.pixels.size()<=32*1024*1024){
            auto& a=old->second;MemoryTexture metadata{a.width,a.height,a.mips,a.color,a.u,a.v,a.filter,{},a.loaded};
            spareMemoryTextureBytes+=a.pixels.size();
            spareMemoryTextures.push_back({std::move(metadata),resourceRetirement.back().done,resourceRetirement.back().computeDone,a.pixels.size()});
        }
        memoryTextures.insert_or_assign(id,std::move(t));
    }
    for(auto id:removedTextures)memoryTextures.erase(id);
    resourceRevision=revision;
}
