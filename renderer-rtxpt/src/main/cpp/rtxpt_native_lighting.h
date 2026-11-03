#pragma once
#include <array>
#include <unordered_map>
#include <unordered_set>
#include "rtxpt_native_application_ranges.h"
#include "rtxpt_gpu_profile.h"

// Independent execution resources. The caller supplies the existing scene/AS,
// descriptor table and command list; this object never owns a second RT scene.
class RtrNativeLightingPass {
public:
    static constexpr uint32_t AbiVersion=1;
    struct alignas(16) Constants {std::array<float,80> words{};};
    struct InstanceAttributes {float4 rolesVisibility={23,1,0,0};float4 baseline={1,1,0,0};};
    struct GeometryAttributes {uint32_t offset=0,triangles=0;};
    static_assert(sizeof(Constants)==320&&sizeof(InstanceAttributes)==32&&sizeof(GeometryAttributes)==8);
    Constants constants;
    bool active=false,metadataDirty=true;
    uint32_t width=0,height=0,mask=0;
    uint64_t executedFrames=0,beautyFrames=0,publication=0,metadataUploads=0;
    int64_t timeNanos=0,deltaNanos=0;
    bool workStatisticsEnabled=false;
    size_t specialRoleCount=0;
    uint64_t sequence=0,sceneRevision=0;
    struct WorkReadback {nvrhi::BufferHandle staging;nvrhi::EventQueryHandle done;uint64_t sequence=0,revision=0;bool pending=false;};
    std::array<WorkReadback,3> workReadbacks;
    std::array<uint64_t,9> latestWork{};
    void PollWork(nvrhi::IDevice* device){
        for(auto& slot:workReadbacks)if(slot.pending&&device->pollEventQuery(slot.done)){
            auto* words=static_cast<const uint32_t*>(device->mapBuffer(slot.staging,nvrhi::CpuAccessMode::Read));
            if(words){if(!latestWork[0]||slot.sequence>=latestWork[1]){latestWork={1,slot.sequence,slot.revision,words[0],words[1],words[2],words[3],words[4],words[5]};}device->unmapBuffer(slot.staging);}
            slot.pending=false;
        }
    }
    void RecordWork(nvrhi::IDevice* device){
        PollWork(device);if(!workStatisticsEnabled)return;
        for(auto& slot:workReadbacks)if(!slot.pending){
            if(!slot.staging){nvrhi::BufferDesc desc;desc.byteSize=24;desc.cpuAccess=nvrhi::CpuAccessMode::Read;desc.initialState=nvrhi::ResourceStates::CopyDest;desc.debugName="Optional native lighting statistics";
                slot.staging=device->createBuffer(desc);slot.done=device->createEventQuery();}
            auto commands=device->createCommandList();commands->open();commands->copyBuffer(slot.staging,0,work,0,24);commands->close();device->executeCommandList(commands);
            device->setEventQuery(slot.done,nvrhi::CommandQueue::Graphics);slot.sequence=sequence;slot.revision=sceneRevision;slot.pending=true;break;
        }
    }
    nvrhi::TextureHandle direct,indirect,geometry,identity,dummyColor,dummyIdentity;
    bool compactEncoding=false;
    nvrhi::TextureHandle compactDirect,compactIndirect,compactDummy;
    nvrhi::BufferHandle compactConstants;
    nvrhi::BindingLayoutHandle compactLayout;
    nvrhi::ComputePipelineHandle compactPipeline;
    nvrhi::BufferHandle constantBuffer,instances,baselineCoordinates,geometryBaselines,lookup,application,work;
    nvrhi::BindingLayoutHandle layout;
    nvrhi::ComputePipelineHandle pipeline;
    nvrhi::ComputePipelineHandle defaultPipeline;
    std::string extensionIdentity;
    uint32_t extensionSignals=15,extensionMinimumBytes=0;
    std::unordered_map<std::string,InstanceAttributes> instanceAttributes;
    std::unordered_map<std::string,std::vector<float2>> meshBaselines;
    std::unordered_map<std::string,uint32_t> instanceSlots;
    std::unordered_map<std::string,GeometryAttributes> geometrySlots;
    std::unordered_set<std::string> dirtyInstances,dirtyBaselines;
    std::vector<uint8_t> applicationData;
    std::vector<RtrNativeApplicationRange> applicationDirtyRanges;
    uint64_t applicationUploadedBytes=0,applicationUploadCalls=0,applicationPatches=0;
    bool lookupDirty=true,applicationDirty=true;
    void PatchApplication(uint64_t generation,size_t offset,const std::vector<uint8_t>& bytes){
        if(generation==0||generation<=publication)throw std::invalid_argument("Application patch generations must strictly increase");
        if(applicationData.size()<extensionMinimumBytes)throw std::invalid_argument("Application data is smaller than the active extension contract");
        auto next=RtrMergeApplicationRange(applicationDirtyRanges,offset,bytes.size(),applicationData.size());
        // All validation/allocation precedes mutation, including the dirty-range metadata.
        std::copy(bytes.begin(),bytes.end(),applicationData.begin()+offset);
        applicationDirtyRanges=std::move(next);publication=generation;++applicationPatches;
    }
    std::array<float2,256> baselineLookup=[] {std::array<float2,256> values{};for(int y=0;y<16;y++)for(int x=0;x<16;x++)values[y*16+x]=float2(x/15.f,y/15.f);return values;}();
    nvrhi::IBuffer* previousSceneInstances=nullptr;
    nvrhi::IBuffer* previousSceneGeometry=nullptr;
    size_t previousInstanceCount=0,previousGeometryCount=0;
    struct Retired {std::vector<nvrhi::BufferHandle> buffers;std::vector<nvrhi::TextureHandle> textures;std::vector<nvrhi::ComputePipelineHandle> pipelines;nvrhi::EventQueryHandle done;};
    std::vector<Retired> retired;
    void Configure(const std::vector<float>& parameters){
        if(parameters.size()!=80)throw std::invalid_argument("Native-lighting ABI v1 requires 80 parameter words");
        for(auto v:parameters)if(!std::isfinite(v))throw std::invalid_argument("Non-finite native-lighting parameter");
        auto integer=[&](size_t i,uint32_t minimum,uint32_t maximum){float value=parameters[i];
            if(value<float(minimum)||value>float(maximum)||float(uint32_t(value))!=value)throw std::invalid_argument("Invalid native-lighting integer parameter");return uint32_t(value);};
        uint32_t w=integer(40,1,16384),h=integer(41,1,16384),outputs=integer(42,1,15),samples=integer(43,0,8);
        if(parameters[56]<=0||parameters[57]<0||parameters[58]<=0||parameters[58]>.1f)
            throw std::invalid_argument("Invalid native-lighting GI budget or offset");
        if(parameters[47]<0||parameters[47]>1||parameters[55]<0||parameters[55]>1)
            throw std::invalid_argument("Style directional weights must be in [0,1]");
        Constants prepared;std::copy(parameters.begin(),parameters.end(),prepared.words.begin());
        auto* integers=reinterpret_cast<uint32_t*>(prepared.words.data());integers[40]=w;integers[41]=h;integers[42]=outputs;integers[43]=samples;
        // Counter enable is not a float identity payload. The caller updates it
        // from the actual diagnostic setting, separately from required state.
        integers[72]=0;integers[73]=uint32_t(publication);integers[74]=uint32_t(publication>>32);integers[75]=0;
        for(size_t i=76;i<80;i++)integers[i]=0;
        constants=prepared;active=true;
    }
    static nvrhi::BufferHandle Buffer(nvrhi::IDevice* device,size_t bytes,size_t stride,const char* name,bool raw=false){
        nvrhi::BufferDesc desc;desc.byteSize=std::max<size_t>(bytes,stride?stride:4);desc.structStride=uint32_t(stride);
        desc.debugName=name;desc.initialState=nvrhi::ResourceStates::ShaderResource;desc.keepInitialState=true;desc.canHaveRawViews=raw;
        auto buffer=device->createBuffer(desc);if(!buffer)throw std::runtime_error(std::string("Could not allocate ")+name);return buffer;
    }
    void Retire(nvrhi::IDevice* device,std::vector<nvrhi::BufferHandle> buffers,std::vector<nvrhi::TextureHandle> textures={}){
        Retired old;old.buffers=std::move(buffers);old.textures=std::move(textures);old.done=device->createEventQuery();
        device->setEventQuery(old.done,nvrhi::CommandQueue::Graphics);retired.push_back(std::move(old));
    }
    void Prepare(nvrhi::IDevice* device,donut::engine::ShaderFactory& factory,nvrhi::IBindingLayout* bindlessLayout){
        for(auto i=retired.begin();i!=retired.end();)if(device->pollEventQuery(i->done))i=retired.erase(i);else ++i;
        if(!pipeline){
            nvrhi::BindingLayoutDesc desc;desc.visibility=nvrhi::ShaderType::Compute;
            desc.bindings={nvrhi::BindingLayoutItem::ConstantBuffer(0),nvrhi::BindingLayoutItem::RayTracingAccelStruct(0),
                nvrhi::BindingLayoutItem::StructuredBuffer_SRV(1),nvrhi::BindingLayoutItem::StructuredBuffer_SRV(2),
                nvrhi::BindingLayoutItem::StructuredBuffer_SRV(3),nvrhi::BindingLayoutItem::StructuredBuffer_SRV(4),
                nvrhi::BindingLayoutItem::StructuredBuffer_SRV(5),nvrhi::BindingLayoutItem::StructuredBuffer_SRV(6),
                nvrhi::BindingLayoutItem::StructuredBuffer_SRV(7),nvrhi::BindingLayoutItem::StructuredBuffer_SRV(8),
                nvrhi::BindingLayoutItem::RawBuffer_SRV(9),nvrhi::BindingLayoutItem::StructuredBuffer_SRV(10),nvrhi::BindingLayoutItem::StructuredBuffer_SRV(11),
                nvrhi::BindingLayoutItem::Sampler(0),nvrhi::BindingLayoutItem::Texture_UAV(0),nvrhi::BindingLayoutItem::Texture_UAV(1),
                nvrhi::BindingLayoutItem::Texture_UAV(2),nvrhi::BindingLayoutItem::Texture_UAV(3),nvrhi::BindingLayoutItem::RawBuffer_UAV(4)};
            auto preparedLayout=device->createBindingLayout(desc);
            auto shader=factory.CreateShader("app/ProcessingPasses/RtrNativeLighting.hlsl","main",nullptr,nvrhi::ShaderType::Compute);
            if(!shader)throw std::runtime_error("Native-lighting shader ABI v1 is unavailable");
            nvrhi::ComputePipelineDesc pso;pso.bindingLayouts={preparedLayout,bindlessLayout};pso.CS=shader;
            auto preparedPipeline=device->createComputePipeline(pso);
            if(!preparedPipeline)throw std::runtime_error("Could not prepare native-lighting pipeline");
            nvrhi::BufferDesc cb;cb.byteSize=sizeof(Constants);cb.isConstantBuffer=true;cb.debugName="Native lighting parameters";
            cb.initialState=nvrhi::ResourceStates::ConstantBuffer;cb.keepInitialState=true;constantBuffer=device->createBuffer(cb);
            application=Buffer(device,4,0,"Native lighting application data",true);
            lookup=Buffer(device,sizeof(baselineLookup),sizeof(float2),"Native lighting baseline lookup");
            nvrhi::BufferDesc counter;counter.byteSize=24;counter.canHaveRawViews=counter.canHaveUAVs=true;
            counter.debugName="Native lighting work counters";counter.initialState=nvrhi::ResourceStates::UnorderedAccess;counter.keepInitialState=true;work=device->createBuffer(counter);
            auto td=nvrhi::TextureDesc().setWidth(1).setHeight(1).setFormat(nvrhi::Format::RGBA16_FLOAT).setIsUAV(true)
                .setInitialState(nvrhi::ResourceStates::UnorderedAccess).setKeepInitialState(true);
            dummyColor=device->createTexture(td);td.format=nvrhi::Format::RGBA32_UINT;dummyIdentity=device->createTexture(td);
            layout=preparedLayout;pipeline=defaultPipeline=preparedPipeline;
        }
        const auto* integers=reinterpret_cast<const uint32_t*>(constants.words.data());
        if(width!=integers[40]||height!=integers[41]||mask!=integers[42]){
            std::array<nvrhi::TextureHandle,4> next{};
            for(unsigned i=0;i<4;i++)if((integers[42]&(1u<<i))!=0){
                auto desc=nvrhi::TextureDesc().setWidth(integers[40]).setHeight(integers[41])
                    .setFormat(i==3?nvrhi::Format::RGBA32_UINT:i==2?nvrhi::Format::RGBA32_FLOAT:nvrhi::Format::RGBA16_FLOAT).setIsUAV(true)
                    .setInitialState(nvrhi::ResourceStates::UnorderedAccess).setKeepInitialState(true);
                desc.debugName="Native lighting requested signal";next[i]=device->createTexture(desc);
                if(!next[i])throw std::runtime_error("Could not allocate native-lighting output");
            }
            Retire(device,{}, {direct,indirect,geometry,identity});direct=next[0];indirect=next[1];geometry=next[2];identity=next[3];
            width=integers[40];height=integers[41];mask=integers[42];
        }
        PrepareCompact(device,factory);
    }
    float maximumGi()const{return constants.words[70];}
    std::array<nvrhi::TextureHandle,4> Outputs()const{
        return {compactEncoding?compactDirect:direct,compactEncoding?compactIndirect:indirect,geometry,identity};
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
        const EncodeParameters parameters{width,height,maximumGi(),mask};
        const auto scope=RtrGpuProfile::Begin(command,"NativeLightingEncode",-1);
        command->writeBuffer(compactConstants,&parameters,sizeof(parameters));
        nvrhi::BindingSetDesc desc;desc.bindings={nvrhi::BindingSetItem::ConstantBuffer(0,compactConstants),
            nvrhi::BindingSetItem::Texture_SRV(0,direct?direct:dummyColor),nvrhi::BindingSetItem::Texture_SRV(1,indirect?indirect:dummyColor),
            nvrhi::BindingSetItem::Texture_UAV(0,compactDirect?compactDirect:compactDummy),nvrhi::BindingSetItem::Texture_UAV(1,compactIndirect?compactIndirect:compactDummy)};
        auto bindings=device->createBindingSet(desc,compactLayout);if(!bindings)throw std::runtime_error("Could not bind compact lighting encoder");
        nvrhi::ComputeState state;state.pipeline=compactPipeline;state.bindings={bindings};command->setComputeState(state);
        command->dispatch((width+7)/8,(height+7)/8);RtrGpuProfile::End(command,scope);
    }
    void Activate(nvrhi::IDevice* device,nvrhi::IBindingLayout* bindless,const RtrNativeExtension& extension){
        if(!layout)throw std::logic_error("Prepare the lighting layout before activation");
        std::string nextIdentity=extension.identity;
        nvrhi::ShaderDesc shaderDesc(nvrhi::ShaderType::Compute);shaderDesc.entryName="main";shaderDesc.debugName="Public native-lighting extension";
        auto shader=device->createShader(shaderDesc,extension.code.data(),extension.code.size());if(!shader)throw std::runtime_error("Could not load extension DXIL");
        nvrhi::ComputePipelineDesc desc;desc.bindingLayouts={layout,bindless};desc.CS=shader;
        auto next=device->createComputePipeline(desc);if(!next)throw std::runtime_error("Could not prepare extension pipeline");
        RetirePipeline(device);pipeline=next;extensionIdentity=std::move(nextIdentity);extensionSignals=extension.signals;extensionMinimumBytes=extension.minimumBytes;
    }
    void ResetExtension(nvrhi::IDevice* device){if(!extensionIdentity.empty()){RetirePipeline(device);pipeline=defaultPipeline;extensionIdentity.clear();extensionSignals=15;extensionMinimumBytes=0;}}
    void RetirePipeline(nvrhi::IDevice* device){Retired old;old.pipelines.push_back(pipeline);old.done=device->createEventQuery();
        if(!old.done)throw std::runtime_error("Could not allocate lighting pipeline retirement query");
        device->setEventQuery(old.done,nvrhi::CommandQueue::Graphics);retired.push_back(std::move(old));}
    void UploadAttributes(nvrhi::IDevice* device,nvrhi::ICommandList* command,const std::shared_ptr<donut::engine::Scene>& scene){
        const auto graph=scene->GetSceneGraph();
        const size_t instanceCount=graph->GetMeshInstances().size(),geometryCount=graph->GetGeometryCount();
        if(previousSceneInstances!=scene->GetInstanceBuffer()||previousSceneGeometry!=scene->GetGeometryBuffer()
            ||previousInstanceCount!=instanceCount||previousGeometryCount!=geometryCount)metadataDirty=true;
        if(!metadataDirty){
            bool changed=!dirtyInstances.empty()||!dirtyBaselines.empty()||lookupDirty||applicationDirty||!applicationDirtyRanges.empty();
            for(const auto& name:dirtyInstances){auto found=instanceSlots.find(name);if(found!=instanceSlots.end()){
                const auto& value=instanceAttributes.at(name);command->writeBuffer(instances,&value,sizeof(value),found->second*sizeof(value));}}
            for(const auto& name:dirtyBaselines){auto found=geometrySlots.find(name);if(found!=geometrySlots.end()){
                const auto& values=meshBaselines.at(name);if(values.size()!=size_t(found->second.triangles)*3)throw std::invalid_argument("Baseline update changes geometry layout");
                command->writeBuffer(baselineCoordinates,values.data(),values.size()*sizeof(float2),size_t(found->second.offset)*sizeof(float2));}}
            UploadPublications(device,command);dirtyInstances.clear();dirtyBaselines.clear();if(changed)++metadataUploads;return;
        }
        instanceSlots.clear();geometrySlots.clear();
        std::vector<InstanceAttributes> instanceData(std::max<size_t>(1,instanceCount));
        for(const auto& instance:graph->GetMeshInstances()){
            instanceSlots.emplace(instance->GetNode()->GetName(),instance->GetInstanceIndex());
            auto found=instanceAttributes.find(instance->GetNode()->GetName());
            if(found!=instanceAttributes.end())instanceData.at(instance->GetInstanceIndex())=found->second;
        }
        specialRoleCount=0;
        for(auto entry=instanceAttributes.begin();entry!=instanceAttributes.end();)if(!instanceSlots.count(entry->first))entry=instanceAttributes.erase(entry);
            else{specialRoleCount+=entry->second.rolesVisibility.x!=23;++entry;}
        std::vector<GeometryAttributes> geometryData(std::max<size_t>(1,geometryCount));std::vector<float2> coordinates;
        for(const auto& mesh:graph->GetMeshes())for(size_t j=0;j<mesh->geometries.size();j++){
            const auto& geometry=mesh->geometries[j];auto found=meshBaselines.find(mesh->name+":"+std::to_string(j));
            if(found==meshBaselines.end())continue;
            if(found->second.size()!=geometry->numIndices)throw std::runtime_error("Baseline triangle layout does not match resident geometry");
            auto& entry=geometryData.at(geometry->globalGeometryIndex);entry.offset=uint32_t(coordinates.size());entry.triangles=geometry->numIndices/3;
            geometrySlots.emplace(mesh->name+":"+std::to_string(j),entry);
            coordinates.insert(coordinates.end(),found->second.begin(),found->second.end());
        }
        auto nextInstances=Buffer(device,instanceData.size()*sizeof(InstanceAttributes),sizeof(InstanceAttributes),"Native receiver roles");
        auto nextGeometry=Buffer(device,geometryData.size()*sizeof(GeometryAttributes),sizeof(GeometryAttributes),"Native receiver geometry mapping");
        auto nextCoordinates=Buffer(device,coordinates.size()*sizeof(float2),sizeof(float2),"Native receiver baseline attributes");
        command->writeBuffer(nextInstances,instanceData.data(),instanceData.size()*sizeof(InstanceAttributes));
        command->writeBuffer(nextGeometry,geometryData.data(),geometryData.size()*sizeof(GeometryAttributes));
        if(!coordinates.empty())command->writeBuffer(nextCoordinates,coordinates.data(),coordinates.size()*sizeof(float2));
        UploadPublications(device,command);
        Retire(device,{instances,geometryBaselines,baselineCoordinates});instances=nextInstances;geometryBaselines=nextGeometry;baselineCoordinates=nextCoordinates;
        previousSceneInstances=scene->GetInstanceBuffer();previousSceneGeometry=scene->GetGeometryBuffer();previousInstanceCount=instanceCount;previousGeometryCount=geometryCount;
        metadataDirty=false;dirtyInstances.clear();dirtyBaselines.clear();++metadataUploads;
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
    void Dispatch(nvrhi::IDevice* device,nvrhi::ICommandList* command,const std::shared_ptr<donut::engine::Scene>& scene,
            nvrhi::rt::IAccelStruct* tlas,RtrSparseLighting& sources,nvrhi::ISampler* sampler,nvrhi::IBuffer* analytic,uint32_t lightCount,uint32_t lightRoot,uint32_t treeBase){
        const auto uploadScope=RtrGpuProfile::Begin(command,"NativeLightingUpload",-1);
        UploadAttributes(device,command,scene);sources.Upload(command);
        auto* words=reinterpret_cast<uint32_t*>(constants.words.data());words[72]=uint32_t(applicationData.size());words[73]=uint32_t(publication);words[74]=uint32_t(publication>>32);
        words[75]=(workStatisticsEnabled?1:0)|(specialRoleCount?2:0);
        words[76]=uint32_t(timeNanos);words[77]=uint32_t(uint64_t(timeNanos)>>32);words[78]=uint32_t(deltaNanos);words[79]=uint32_t(uint64_t(deltaNanos)>>32);
        words[39]=lightCount;words[51]=lightRoot;words[59]=treeBase;
        command->writeBuffer(constantBuffer,&constants,sizeof(constants));
        RtrGpuProfile::End(command,uploadScope);
        nvrhi::BindingSetDesc bindings;bindings.bindings={nvrhi::BindingSetItem::ConstantBuffer(0,constantBuffer),
            nvrhi::BindingSetItem::RayTracingAccelStruct(0,tlas),nvrhi::BindingSetItem::StructuredBuffer_SRV(1,scene->GetInstanceBuffer()),
            nvrhi::BindingSetItem::StructuredBuffer_SRV(2,scene->GetGeometryBuffer()),nvrhi::BindingSetItem::StructuredBuffer_SRV(3,scene->GetMaterialBuffer()),
            nvrhi::BindingSetItem::StructuredBuffer_SRV(4,sources.sourceBuffer),nvrhi::BindingSetItem::StructuredBuffer_SRV(5,sources.treeBuffer),
            nvrhi::BindingSetItem::StructuredBuffer_SRV(6,instances),nvrhi::BindingSetItem::StructuredBuffer_SRV(7,baselineCoordinates),
            nvrhi::BindingSetItem::StructuredBuffer_SRV(8,geometryBaselines),nvrhi::BindingSetItem::RawBuffer_SRV(9,application),
            nvrhi::BindingSetItem::StructuredBuffer_SRV(10,lookup),nvrhi::BindingSetItem::StructuredBuffer_SRV(11,analytic),nvrhi::BindingSetItem::Sampler(0,sampler),
            nvrhi::BindingSetItem::Texture_UAV(0,direct?direct:dummyColor),nvrhi::BindingSetItem::Texture_UAV(1,indirect?indirect:dummyColor),
            nvrhi::BindingSetItem::Texture_UAV(2,geometry?geometry:dummyColor),nvrhi::BindingSetItem::Texture_UAV(3,identity?identity:dummyIdentity),nvrhi::BindingSetItem::RawBuffer_UAV(4,work)};
        auto binding=device->createBindingSet(bindings,layout);if(!binding)throw std::runtime_error("Could not bind native-lighting resources");
        if(workStatisticsEnabled)command->clearBufferUInt(work,0);nvrhi::ComputeState state;state.pipeline=pipeline;state.bindings={binding,scene->GetDescriptorTable()};
        const auto dispatchScope=RtrGpuProfile::Begin(command,"NativeLightingDispatch",-1);
        command->setComputeState(state);command->dispatch((width+7)/8,(height+7)/8);
        RtrGpuProfile::End(command,dispatchScope);EncodeOutputs(device,command);++executedFrames;
    }
};
