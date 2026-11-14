#pragma once

namespace rtr::upstream {
// Transaction inputs are packed straight into their final native layout.
// Mutable direct buffers preserve each mesh's ranges across compatible edits.
// All writes execute after prior readers on the same graphics queue; the host
// disallows reuse after a complete-renderer asynchronous prefix. No cache scan,
// speculative reuse, queue polling, temporary scene, or intermediate channels.
class DirectGeometry final {
    using Mesh=donut::engine::MeshInfo;
    using Buffers=donut::engine::BufferGroup;
    using Attribute=donut::engine::VertexAttribute;
    std::vector<uint8_t> m_vertices;
    std::vector<uint32_t> m_indices;
    std::unordered_set<nvrhi::IBuffer*> m_written;
    struct Page {
        nvrhi::BufferHandle buffer;
        std::shared_ptr<donut::engine::DescriptorHandle> descriptor;
        uint64_t used=0;
    };
    Page m_vertexPage,m_indexPage;
    struct Pages {
        std::vector<Page> slots;
        size_t next=0;
    };
    std::array<Pages,4> m_pages;
    bool m_pagesPrepared=false;
    struct Slice {nvrhi::BufferHandle buffer;std::shared_ptr<donut::engine::DescriptorHandle> descriptor;uint64_t offset;};
    static constexpr uint64_t PageBytes(bool indices){return indices?256*1024:1024*1024;}
    static Page CreatePage(nvrhi::IDevice* device,
        const std::shared_ptr<donut::engine::DescriptorTableManager>& descriptors,uint64_t bytes,bool indices,bool prepare){
        Page next;
        nvrhi::BufferDesc desc;
        desc.byteSize=nvrhi::align<uint64_t>(std::max(bytes,PageBytes(indices)),256);
        desc.debugName=indices?"API direct index page":"API direct vertex page";
        desc.isVertexBuffer=!indices;desc.isIndexBuffer=indices;
        desc.format=indices?nvrhi::Format::R32_UINT:nvrhi::Format::UNKNOWN;
        desc.canHaveRawViews=true;desc.canHaveTypedViews=true;desc.isAccelStructBuildInput=true;
        desc.initialState=nvrhi::ResourceStates::ShaderResource|nvrhi::ResourceStates::AccelStructBuildInput;
        desc.keepInitialState=true;
        {RtrDiagnostics::Scope allocation(prepare?RtrDiagnostics::ResourcePrebuild:RtrDiagnostics::BufferAllocate,
                1,0,1,0,0,0,0,desc.byteSize);
            next.buffer=device->createBuffer(desc);}
        if(next.buffer&&descriptors){RtrDiagnostics::Scope allocation(RtrDiagnostics::DescriptorAllocate,1);
            next.descriptor=std::make_shared<donut::engine::DescriptorHandle>(descriptors->CreateDescriptorHandle(
                nvrhi::BindingSetItem::RawBuffer_SRV(0,next.buffer)));}
        return next;
    }
    static Page Take(Pages& pages){
        for(size_t probe=0;probe<pages.slots.size();++probe){
            const size_t i=(pages.next+probe)%pages.slots.size();
            const auto& slot=pages.slots[i];
            // Retirement keeps the BufferGroup (and its descriptor) alive
            // across both queues. Native command lists also hold buffer refs.
            const bool descriptorFree=!slot.descriptor||slot.descriptor.use_count()==1;
            if(descriptorFree&&slot.buffer->GetRefCount()==(slot.descriptor?2ul:1ul)){
                pages.next=(i+1)%pages.slots.size();return slot;
            }
        }
        return {};
    }
    Slice Reserve(nvrhi::IDevice* device,
        const std::shared_ptr<donut::engine::DescriptorTableManager>& descriptors,uint64_t bytes,bool indices){
        auto& page=indices?m_indexPage:m_vertexPage;
        const uint64_t stride=nvrhi::align<uint64_t>(bytes,16);
        if(!page.buffer||stride>page.buffer->getDesc().byteSize-page.used){
            const auto minimum=PageBytes(indices);
            const size_t type=indices?1:0;
            Page next;
            if(stride<=minimum)next=Take(m_pages[type]);
            if(!next.buffer&&stride<=4*minimum)next=Take(m_pages[type+2]);
            {RtrDiagnostics::Scope acquire(RtrDiagnostics::PoolAcquire,1,0,2,0,0,0,0,stride);
                acquire.event[11]|=next.buffer?RtrDiagnostics::CacheHit:RtrDiagnostics::CacheMiss;}
            if(!next.buffer)next=CreatePage(device,descriptors,stride,indices,false);
            if(!next.buffer)throw std::runtime_error("Direct geometry page allocation failed");
            page=std::move(next);
        }
        Slice slice{page.buffer,page.descriptor,page.used};page.used+=stride;return slice;
    }
    static bool Same(const AuthoredMeshInputs::FloatView& a,const AuthoredMeshInputs::FloatView& b){
        return a.size()==b.size()&&(a.empty()||std::memcmp(a.data(),b.data(),a.size()*sizeof(float))==0);
    }
    static bool SameIndices(const AuthoredMeshInputs& a,const AuthoredMeshInputs& b){
        if(a.indexCount!=b.indexCount||a.indices.size()!=b.indices.size())return false;
        for(size_t i=0;i<a.indices.size();++i)if(a.indices[i].count!=b.indices[i].count
            ||std::memcmp(a.indices[i].data,b.indices[i].data,size_t(a.indices[i].count)*4)!=0)return false;
        return true;
    }
    static size_t Stride(Attribute attribute){
        switch(attribute){case Attribute::Position:return 12;case Attribute::TexCoord1:return 8;default:return 4;}
    }
    static void Pack(uint8_t* target,const AuthoredMeshInputs& input,Attribute attribute){
        const size_t vertices=input.positions.size()/3;
        if(attribute==Attribute::Position){std::memcpy(target,input.positions.data(),vertices*12);return;}
        if(attribute==Attribute::TexCoord1){std::memcpy(target,input.uv.data(),vertices*8);return;}
        for(size_t v=0;v<vertices;++v){
            uint32_t packed=attribute==Attribute::Normal
                ?donut::math::vectorToSnorm8(donut::math::float3(input.normals[v*3],input.normals[v*3+1],input.normals[v*3+2]))
                :donut::math::vectorToSnorm8(donut::math::float4(input.tangents[v*4],input.tangents[v*4+1],input.tangents[v*4+2],input.tangents[v*4+3]));
            std::memcpy(target+v*4,&packed,4);
        }
    }
    static void PackIndices(uint32_t* target,const AuthoredMeshInputs& input){
        size_t offset=0;
        for(const auto& part:input.indices){std::memcpy(target+offset,part.data,size_t(part.count)*4);offset+=part.count;}
    }
    void Update(nvrhi::ICommandList* commands,AuthoredMeshInfo& mesh,RtrDiagnostics::Scope& total){
        const auto& input=*mesh.pendingInputs;
        const std::array<Attribute,4> channels{Attribute::Position,Attribute::Normal,Attribute::Tangent,Attribute::TexCoord1};
        RtrDiagnostics::Scope upload(RtrDiagnostics::BufferUpload,0,0,1);
        uint64_t bytes=0;bool vertexWritten=false;
        for(size_t channel=0;channel<channels.size();++channel){
            if(!(mesh.directDirtyChannels&(1u<<channel)))continue;
            const auto& range=mesh.buffers->getVertexBufferRange(channels[channel]);if(!range.byteSize)continue;
            const auto stride=Stride(channels[channel]),size=size_t(mesh.totalVertices)*stride;
            m_vertices.resize(size);Pack(m_vertices.data(),input,channels[channel]);
            commands->writeBuffer(mesh.buffers->vertexBuffer,m_vertices.data(),size,range.byteOffset+size_t(mesh.vertexOffset)*stride);
            bytes+=size;vertexWritten=true;
        }
        bool indexWritten=(mesh.directDirtyChannels&16)!=0;
        if(indexWritten){
            m_indices.resize(input.indexCount);PackIndices(m_indices.data(),input);
            commands->writeBuffer(mesh.buffers->indexBuffer,m_indices.data(),m_indices.size()*4,size_t(mesh.indexOffset)*4);
            bytes+=m_indices.size()*4;
        }
        const auto objects=uint64_t(vertexWritten)+uint64_t(indexWritten);
        if(objects){upload.event[8]=objects;upload.event[21]=bytes;
            upload.event[11]|=RtrDiagnostics::CacheHit;
            if(vertexWritten&&m_written.insert(mesh.buffers->vertexBuffer.Get()).second)++total.event[8];
            if(indexWritten&&m_written.insert(mesh.buffers->indexBuffer.Get()).second)++total.event[8];
            total.event[21]+=bytes;}
        mesh.pendingInputs.reset();mesh.directDirtyChannels=0;
    }
    void Create(nvrhi::IDevice* device,nvrhi::ICommandList* commands,
        const std::shared_ptr<donut::engine::DescriptorTableManager>& descriptors,
        const std::vector<std::shared_ptr<Mesh>>& batch,uint32_t layout,size_t vertices,size_t indices,
        RtrDiagnostics::Scope& total){
        auto shared=batch.front()->buffers;
        uint64_t vertexBytes=0;
        const auto append=[&](Attribute attribute,size_t stride){auto& range=shared->getVertexBufferRange(attribute);
            range.byteOffset=vertexBytes;range.byteSize=nvrhi::align<uint64_t>(vertices*stride,16);vertexBytes+=range.byteSize;};
        append(Attribute::Position,12);append(Attribute::Normal,4);
        if(layout&2)append(Attribute::Tangent,4);if(layout&4)append(Attribute::TexCoord1,8);
        m_vertices.assign(size_t(vertexBytes),0);m_indices.resize(indices);
        size_t vertexOffset=0,indexOffset=0;
        for(const auto& base:batch){auto& mesh=static_cast<AuthoredMeshInfo&>(*base);const auto& input=*mesh.pendingInputs;
            for(auto attribute:{Attribute::Position,Attribute::Normal,Attribute::Tangent,Attribute::TexCoord1}){
                const auto& range=shared->getVertexBufferRange(attribute);if(!range.byteSize)continue;
                Pack(m_vertices.data()+range.byteOffset+vertexOffset*Stride(attribute),input,attribute);
            }
            PackIndices(m_indices.data()+indexOffset,input);
            mesh.vertexOffset=uint32_t(vertexOffset);mesh.indexOffset=uint32_t(indexOffset);
            mesh.buffers=shared;vertexOffset+=mesh.totalVertices;indexOffset+=mesh.totalIndices;
        }
        const auto vertex=Reserve(device,descriptors,vertexBytes,false),index=Reserve(device,descriptors,indices*4,true);
        shared->vertexBuffer=vertex.buffer;shared->indexBuffer=index.buffer;
        shared->vertexBufferDescriptor=vertex.descriptor;shared->indexBufferDescriptor=index.descriptor;
        for(auto attribute:{Attribute::Position,Attribute::Normal,Attribute::Tangent,Attribute::TexCoord1}){
            auto& range=shared->getVertexBufferRange(attribute);if(range.byteSize)range.byteOffset+=vertex.offset;}
        for(const auto& base:batch)base->indexOffset+=uint32_t(index.offset/4);
        const uint64_t bytes=vertexBytes+indices*4;
        {RtrDiagnostics::Scope upload(RtrDiagnostics::BufferUpload,2,0,1,0,0,0,0,bytes);
            commands->writeBuffer(shared->vertexBuffer,m_vertices.data(),m_vertices.size(),vertex.offset);
            commands->writeBuffer(shared->indexBuffer,m_indices.data(),m_indices.size()*4,index.offset);}
        total.event[21]+=bytes;
        if(m_written.insert(shared->vertexBuffer.Get()).second)++total.event[8];
        if(m_written.insert(shared->indexBuffer.Get()).second)++total.event[8];
        for(const auto& base:batch){auto& mesh=static_cast<AuthoredMeshInfo&>(*base);
            mesh.directAttributeSizes=mesh.pendingInputs->AttributeSizes();mesh.pendingInputs.reset();
            mesh.mutableDirectStorage=true;mesh.directDirtyChannels=0;}
    }
public:
    void PreparePages(nvrhi::IDevice* device,const std::shared_ptr<donut::engine::DescriptorTableManager>& descriptors){
        if(m_pagesPrepared)return;
        m_pagesPrepared=true;
        constexpr std::array<size_t,4> capacity{224,224,8,12};
        for(size_t type=0;type<m_pages.size();++type){
            auto& slots=m_pages[type].slots;slots.reserve(capacity[type]);
            const bool indices=(type&1)!=0;
            for(size_t i=0;i<capacity[type];++i){
                auto page=CreatePage(device,descriptors,PageBytes(indices)*(type>=2?4:1),indices,true);
                if(!page.buffer)break;
                slots.push_back(std::move(page));
            }
        }
    }
    void SetOrderedWrites(bool ordered){
        // Separate queues transition whole resources, even for disjoint ranges.
        // After an asynchronous reader, append only to fresh resources; older
        // BufferGroups and native submissions continue owning their old pages.
        if(!ordered){m_vertexPage={};m_indexPage={};}
    }
    static uint32_t ChangedChannels(const AuthoredMeshInputs& before,const AuthoredMeshInputs& after){
        return (!Same(before.positions,after.positions)?1u:0u)|(!Same(before.normals,after.normals)?2u:0u)
            |(!Same(before.tangents,after.tangents)?4u:0u)|(!Same(before.uv,after.uv)?8u:0u)
            |(!SameIndices(before,after)?16u:0u);
    }
    void Upload(nvrhi::IDevice* device,nvrhi::ICommandList* commands,
        const std::shared_ptr<donut::engine::DescriptorTableManager>& descriptors,
        const std::vector<std::shared_ptr<Mesh>>& meshes){
        RtrDiagnostics::Scope preparation(RtrDiagnostics::SceneMeshPreparation,meshes.size());
        RtrDiagnostics::Scope upload(RtrDiagnostics::MeshBufferUpload);
        m_written.clear();
        std::array<std::vector<std::shared_ptr<Mesh>>,8> layouts;
        std::unordered_set<Mesh*> selected;
        for(const auto& base:meshes){
            auto& mesh=static_cast<AuthoredMeshInfo&>(*base);
            if(!mesh.pendingInputs||!selected.insert(base.get()).second)continue;
            if(mesh.mutableDirectStorage){Update(commands,mesh,upload);continue;}
            if(mesh.buffers->vertexBuffer||mesh.buffers->indexBuffer)throw std::logic_error("New direct geometry already owns native storage");
            layouts.at(mesh.pendingInputs->Layout()).push_back(base);
        }
        std::vector<std::shared_ptr<Mesh>> batch;batch.reserve(32);
        for(uint32_t layout=0;layout<layouts.size();++layout){size_t vertices=0,indices=0,bytes=0;
            const auto flush=[&](){if(batch.empty())return;Create(device,commands,descriptors,batch,layout,vertices,indices,upload);batch.clear();vertices=indices=bytes=0;};
            for(const auto& mesh:layouts[layout]){
                const auto sizes=PendingMeshInputs(*mesh)->AttributeSizes();const size_t size=sizes[0]+sizes[1]+sizes[2]+sizes[3]+size_t(mesh->totalIndices)*4;
                if(!batch.empty()&&(batch.size()==32||bytes+size>1024*1024))flush();
                batch.push_back(mesh);vertices+=mesh->totalVertices;indices+=mesh->totalIndices;bytes+=size;
                if(size>1024*1024)flush();
            }
            flush();
        }
        // Bindless descriptors do not provide automatic per-buffer transitions.
        // Publish read state before this frame's BLAS and shader consumers.
        for(auto* buffer:m_written)commands->setBufferState(buffer,
            nvrhi::ResourceStates::ShaderResource|nvrhi::ResourceStates::AccelStructBuildInput);
        commands->commitBarriers();
    }
};
}
