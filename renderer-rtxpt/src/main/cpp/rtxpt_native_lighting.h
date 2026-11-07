#pragma once
#include <array>
#include <unordered_map>
#include <unordered_set>
#include <cstdlib>
#include "rtxpt_native_application_ranges.h"
#include "rtxpt_attribute_ranges.h"
#include "rtxpt_attribute_capacity.h"
#include "rtxpt_allocation_failure.h"
#include "rtxpt_host_response_lookup.h"
#include "rtxpt_gpu_profile.h"
#include "rtxpt_native_rr.h"
#include "rtxpt_resource_reclaimer.h"

// Independent execution resources. The caller supplies the existing scene/AS,
// descriptor table and command list; this object never owns a second RT scene.
class RtrNativeLightingPass {
public:
    static constexpr uint32_t AbiVersion=1;
    struct alignas(16) Constants {std::array<float,80> words{};};
    struct InstanceAttributes {float4 rolesVisibility={23,1,0,0};float4 baseline={1,1,0,0};};
    struct GeometryAttributes {uint32_t offset=0,triangles=0;};
    struct VertexAttributes {float2 baseline={0,0};uint32_t color=0xffffffff,reserved=0;};
    static_assert(sizeof(VertexAttributes)==16);
    static_assert(sizeof(Constants)==320&&sizeof(InstanceAttributes)==32&&sizeof(GeometryAttributes)==8);
    Constants constants;
    bool active=false,metadataDirty=true;
    bool metadataInitialized=false;
    std::unordered_set<std::string> specialInstances;
    bool instanceCandidates=false;
    bool diagnosticDecomposition=false;
    RtrNativeRR reconstruction;
    nvrhi::BindingLayoutHandle deepLayout;
    nvrhi::ComputePipelineHandle deepPipeline;
    nvrhi::BufferHandle deepReceivers;
    std::array<nvrhi::BufferHandle,3> deepConstants;
    RtrCachedBindings dispatchBindings,encodeBindings;
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
    nvrhi::TextureHandle direct,indirect,geometry,identity,dummyColor,dummyIdentity;
    bool compactEncoding=false;
    bool compactResponseEncoding=false;
    nvrhi::TextureHandle compactDirect,compactIndirect,compactDummy;
    nvrhi::BufferHandle compactConstants;
    nvrhi::BindingLayoutHandle compactLayout;
    nvrhi::ComputePipelineHandle compactPipeline;
    nvrhi::BufferHandle constantBuffer,instances,baselineCoordinates,geometryBaselines,lookup,application,work;
    nvrhi::BindingLayoutHandle layout;
    nvrhi::ComputePipelineHandle pipeline;
    nvrhi::ComputePipelineHandle defaultPipeline;
    nvrhi::ComputePipelineHandle singleGroupPipeline;
    nvrhi::ComputePipelineHandle sparseOnlyPipeline,sparseOnlySingleGroupPipeline,smallGroupPipeline,sparseOnlySmallGroupPipeline;
    nvrhi::ComputePipelineHandle pairGroupPipeline,sparseOnlyPairGroupPipeline;
    std::array<nvrhi::ComputePipelineHandle,8> uninstrumentedPipelines;
    std::array<nvrhi::ComputePipelineHandle,16> maxPipelines;
    std::array<nvrhi::ComputePipelineHandle,8> countedMaxPipelines;
    std::array<nvrhi::ComputePipelineHandle,16> channelMaxPipelines;
    std::string extensionIdentity;
    uint32_t extensionSignals=15,extensionMinimumBytes=0;
    std::unordered_map<std::string,InstanceAttributes> instanceAttributes;
    std::unordered_map<std::string,std::vector<float2>> meshBaselines;
    std::unordered_map<std::string,std::vector<uint32_t>> meshColors;
    std::unordered_set<std::string> alphaMeshes;
    std::unordered_map<std::string,uint32_t> instanceSlots;
    std::unordered_map<std::string,GeometryAttributes> geometrySlots;
    struct GeometryIndex {uint32_t index;uint64_t epoch;GeometryAttributes* attributes=nullptr;};
    std::unordered_map<std::string,GeometryIndex> geometryIndices;
    uint64_t geometryIndexEpoch=0;
    struct GeometryEntry {std::string name;uint32_t index=0,corners=0;bool indexChanged=false;GeometryIndex* slot=nullptr;};
    std::vector<GeometryEntry> geometryEntriesScratch;
    std::unordered_set<std::string> dirtyInstances,dirtyBaselines,dirtyColors;
    std::vector<InstanceAttributes> instanceUploadScratch;
    std::vector<GeometryAttributes> geometryUploadScratch;
    std::vector<uint32_t> instanceUploadIndices,geometryUploadIndices;
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
    uint64_t attributeMigrationBytes=0,attributeTableVisits=0;
    void FreeAttributeRangeAt(uint32_t offset,uint32_t count){attributeRanges.Release(offset,count);}
    uint32_t AllocateAttributeRange(uint32_t count){return attributeRanges.Allocate(count);}
    void EnsureAttributeBuffer(nvrhi::IDevice* device,nvrhi::ICommandList* command,nvrhi::BufferHandle& buffer,size_t bytes,size_t stride,const char* name){
        bytes=std::max(bytes,stride);if(buffer&&buffer->getDesc().byteSize>=bytes)return;
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
    std::vector<VertexAttributes> PackVertices(const std::string& name,size_t count){
        std::vector<VertexAttributes> result(count);auto baseline=meshBaselines.find(name);auto colors=meshColors.find(name);
        if(baseline!=meshBaselines.end()&&baseline->second.size()!=count)throw std::invalid_argument("Baseline update changes geometry layout");
        if(colors!=meshColors.end()&&colors->second.size()!=count)throw std::invalid_argument("Vertex color update changes geometry layout");
        for(size_t i=0;i<count;i++){if(baseline!=meshBaselines.end())result[i].baseline=baseline->second[i];if(colors!=meshColors.end())result[i].color=colors->second[i];}
        attributeWork[1]+=count;return result;
    }
    std::vector<uint8_t> applicationData;
    std::vector<RtrNativeApplicationRange> applicationDirtyRanges;
    uint64_t applicationUploadedBytes=0,applicationUploadCalls=0,applicationPatches=0;
    bool lookupDirty=true,applicationDirty=true;
    void PatchApplication(uint64_t generation,size_t offset,const std::vector<uint8_t>& bytes){
        // Public Java publications require strictly increasing generations. Their
        // bounded uploader may emit several disjoint slices of that same generation.
        if(generation==0||generation<publication)throw std::invalid_argument("Application patch generation cannot precede the resident publication");
        if(applicationData.size()<extensionMinimumBytes)throw std::invalid_argument("Application data is smaller than the active extension contract");
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
    struct Retired {std::vector<nvrhi::BufferHandle> buffers;std::vector<nvrhi::TextureHandle> textures;std::vector<nvrhi::ComputePipelineHandle> pipelines;nvrhi::EventQueryHandle done,computeDone;uint64_t bytes=0;};
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
        auto buffer=device->createBuffer(desc);if(!buffer)throw std::runtime_error(RtrAllocationFailure(device,name,desc.byteSize));return buffer;
    }
    void Retire(nvrhi::IDevice* device,std::vector<nvrhi::BufferHandle> buffers,std::vector<nvrhi::TextureHandle> textures={}){
        PollRetired(device);if(retired.size()>=64)throw std::runtime_error("Lighting retirement capacity exhausted");
        Retired old;old.buffers=std::move(buffers);old.textures=std::move(textures);
        for(auto& b:old.buffers)if(b)old.bytes+=b->getDesc().byteSize;
        old.done=device->createEventQuery();old.computeDone=device->createEventQuery();
        if(!old.done||!old.computeDone)throw std::runtime_error("Lighting completion query allocation failed");
        device->setEventQuery(old.done,nvrhi::CommandQueue::Graphics);device->setEventQuery(old.computeDone,nvrhi::CommandQueue::Compute);retired.push_back(std::move(old));
    }
    void Prepare(nvrhi::IDevice* device,donut::engine::ShaderFactory& factory,nvrhi::IBindingLayout* bindlessLayout,const RtrSparseLighting* sources=nullptr){
        if(!pipeline){
            RtrDiagnostics::Scope initialize(RtrDiagnostics::LightingInitialize);
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
            std::vector<donut::engine::ShaderMacro> defaultMacros{{"RTR_LIGHT_GROUP_CAPACITY","32"},{"RTR_ANALYTIC_LIGHTS","1"},{"RTR_WORK_STATISTICS","1"},{"RTR_MAX_COMPOSITION","0"}};
            auto shader=factory.CreateShader("app/ProcessingPasses/RtrNativeLighting.hlsl","main",&defaultMacros,nvrhi::ShaderType::Compute);
            if(!shader)throw std::runtime_error("Native-lighting shader ABI v1 is unavailable");
            nvrhi::ComputePipelineDesc pso;pso.bindingLayouts={preparedLayout,bindlessLayout};pso.CS=shader;
            auto preparedPipeline=device->createComputePipeline(pso);
            if(!preparedPipeline)throw std::runtime_error("Could not prepare native-lighting pipeline");
            nvrhi::BufferDesc cb;cb.byteSize=sizeof(Constants);cb.isConstantBuffer=true;cb.debugName="Native lighting parameters";
            cb.initialState=nvrhi::ResourceStates::ConstantBuffer;cb.keepInitialState=true;constantBuffer=device->createBuffer(cb);
            application=Buffer(device,4,0,"Native lighting application data",true);
            lookup=Buffer(device,sizeof(baselineLookup),sizeof(float2),"Native lighting baseline lookup");
            nvrhi::BufferDesc counter;counter.byteSize=48;counter.canHaveRawViews=counter.canHaveUAVs=true;
            counter.debugName="Native lighting work counters";counter.initialState=nvrhi::ResourceStates::UnorderedAccess;counter.keepInitialState=true;work=device->createBuffer(counter);
            auto td=nvrhi::TextureDesc().setWidth(1).setHeight(1).setFormat(nvrhi::Format::RGBA16_FLOAT).setIsUAV(true)
                .setInitialState(nvrhi::ResourceStates::UnorderedAccess).setKeepInitialState(true);
            dummyColor=device->createTexture(td);td.format=nvrhi::Format::RGBA32_UINT;dummyIdentity=device->createTexture(td);
            layout=preparedLayout;pipeline=defaultPipeline=preparedPipeline;
        }
        if(diagnosticDecomposition&&RtrDiagnostics::level.load()==2&&!deepPipeline){
            auto desc=*layout->getDesc();desc.bindings.push_back(nvrhi::BindingLayoutItem::StructuredBuffer_UAV(5));
            auto next=device->createBindingLayout(desc);auto shader=factory.CreateShader("app/ProcessingPasses/RtrNativeLightingDeep.hlsl","main",nullptr,nvrhi::ShaderType::Compute);
            if(!shader||!next)throw std::runtime_error("DEEP receiver decomposition shader unavailable");
            nvrhi::ComputePipelineDesc pso;pso.bindingLayouts={next,bindlessLayout};pso.CS=shader;
            auto nextPipeline=device->createComputePipeline(pso);if(!nextPipeline)throw std::runtime_error("DEEP receiver decomposition pipeline unavailable");deepLayout=next;deepPipeline=nextPipeline;
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
        const unsigned groupVariant=!sources?0:sources->SingleGroup()?1:sources->PairGroups()?6:sources->SmallGroups()?4:0;
        const unsigned instrumentedCapacity=groupVariant==1?1:groupVariant==6?2:groupVariant==4?4:32;
        for(unsigned analytic=0;analytic<2;++analytic){
            auto& target=instrumentedCapacity==1?(analytic?singleGroupPipeline:sparseOnlySingleGroupPipeline):
                instrumentedCapacity==2?(analytic?pairGroupPipeline:sparseOnlyPairGroupPipeline):
                instrumentedCapacity==4?(analytic?smallGroupPipeline:sparseOnlySmallGroupPipeline):(analytic?pipeline:sparseOnlyPipeline);
            if(RtrDiagnostics::level.load()==2&&!target){
                RtrDiagnostics::Scope prepare(RtrDiagnostics::Pipeline,1);
                std::vector<donut::engine::ShaderMacro> macros{{"RTR_LIGHT_GROUP_CAPACITY",std::to_string(instrumentedCapacity)},
                    {"RTR_ANALYTIC_LIGHTS",analytic?"1":"0"},{"RTR_WORK_STATISTICS","1"},{"RTR_MAX_COMPOSITION","0"}};
                nvrhi::ComputePipelineDesc pso;pso.bindingLayouts={layout,bindlessLayout};
                pso.CS=factory.CreateShader("app/ProcessingPasses/RtrNativeLighting.hlsl","main",&macros,nvrhi::ShaderType::Compute);
                target=device->createComputePipeline(pso);if(!target)throw std::runtime_error("Diagnostic lighting pipeline unavailable");
            }
        }
        for(unsigned i:{groupVariant,groupVariant+(groupVariant<4?2u:1u)})if(!uninstrumentedPipelines[i]){
            RtrDiagnostics::Scope prepare(RtrDiagnostics::Pipeline,1);
            std::vector<donut::engine::ShaderMacro> macros{{"RTR_LIGHT_GROUP_CAPACITY",i>=6?"2":i>=4?"4":(i&1)?"1":"32"},
                {"RTR_ANALYTIC_LIGHTS",(i>=4?(i&1)!=0:(i&2)!=0)?"0":"1"},{"RTR_WORK_STATISTICS","0"},{"RTR_MAX_COMPOSITION","0"}};
            nvrhi::ComputePipelineDesc pso;pso.bindingLayouts={layout,bindlessLayout};
            pso.CS=factory.CreateShader("app/ProcessingPasses/RtrNativeLighting.hlsl","main",&macros,nvrhi::ShaderType::Compute);
            uninstrumentedPipelines[i]=device->createComputePipeline(pso);
            if(!uninstrumentedPipelines[i])throw std::runtime_error("Production lighting pipeline unavailable");
        }
        const bool maximum=sources&&sources->VisibleMax();
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
        if(!reconstruction.requested&&maximum)for(unsigned i:{groupVariant,groupVariant+(groupVariant<4?2u:1u)})if(!maxPipelines[maxOffset+i]){
            std::vector<donut::engine::ShaderMacro> macros{{"RTR_LIGHT_GROUP_CAPACITY",i>=6?"2":i>=4?"4":(i&1)?"1":"32"},{"RTR_ANALYTIC_LIGHTS",(i>=4?(i&1)!=0:(i&2)!=0)?"0":"1"},{"RTR_WORK_STATISTICS","0"},{"RTR_MAX_COMPOSITION","1"}};
            nvrhi::ComputePipelineDesc pso;pso.bindingLayouts={layout,bindlessLayout,sourceHistoryLayout};
            pso.CS=factory.CreateShader(allDynamic?"app/ProcessingPasses/RtrNativeDynamicLighting.hlsl":"app/ProcessingPasses/RtrNativeLighting.hlsl","main",&macros,nvrhi::ShaderType::Compute);maxPipelines[maxOffset+i]=device->createComputePipeline(pso);
            if(!maxPipelines[maxOffset+i])throw std::runtime_error("Max-composition native lighting pipeline unavailable");
        }
        // Diagnostic observation must not force a different composition
        // executor. Count actual work in the exact maximum-only path, with no
        // previous-frame winner seed or extra history binding.
        if(!reconstruction.requested&&maximum&&RtrDiagnostics::level.load()==2)for(unsigned i:{groupVariant,groupVariant+(groupVariant<4?2u:1u)})if(!countedMaxPipelines[i]){
            std::vector<donut::engine::ShaderMacro> macros{{"RTR_LIGHT_GROUP_CAPACITY",i>=6?"2":i>=4?"4":(i&1)?"1":"32"},
                {"RTR_ANALYTIC_LIGHTS",(i>=4?(i&1)!=0:(i&2)!=0)?"0":"1"},{"RTR_WORK_STATISTICS","1"},{"RTR_MAX_COMPOSITION","1"}};
            nvrhi::ComputePipelineDesc pso;pso.bindingLayouts={layout,bindlessLayout};
            pso.CS=factory.CreateShader("app/ProcessingPasses/RtrNativeLighting.hlsl","main",&macros,nvrhi::ShaderType::Compute);
            countedMaxPipelines[i]=device->createComputePipeline(pso);
            if(!countedMaxPipelines[i])throw std::runtime_error("Counted maximum-composition lighting pipeline unavailable");
        }
        // CHANNEL_MAX has independent RGB winners and needs neither scalar
        // luminance/ID arrays nor a dynamically indexed per-ray stack.
        if(!reconstruction.requested&&sources&&sources->ChannelExecutor())for(unsigned instrumented=0;instrumented<2;++instrumented)
            for(unsigned i:{groupVariant,groupVariant+(groupVariant<4?2u:1u)})if(!channelMaxPipelines[instrumented*8+i]){
                std::vector<donut::engine::ShaderMacro> macros{{"RTR_LIGHT_GROUP_CAPACITY",i>=6?"2":i>=4?"4":(i&1)?"1":"32"},
                    {"RTR_ANALYTIC_LIGHTS",(i>=4?(i&1)!=0:(i&2)!=0)?"0":"1"},{"RTR_WORK_STATISTICS",instrumented?"1":"0"},{"RTR_MAX_COMPOSITION","2"}};
                nvrhi::ComputePipelineDesc pso;pso.bindingLayouts={layout,bindlessLayout};
                pso.CS=factory.CreateShader("app/ProcessingPasses/RtrNativeLighting.hlsl","main",&macros,nvrhi::ShaderType::Compute);
                channelMaxPipelines[instrumented*8+i]=device->createComputePipeline(pso);
                if(!channelMaxPipelines[instrumented*8+i])throw std::runtime_error("Channel-maximum lighting pipeline unavailable");
            }
        reconstruction.Prepare(device,factory,layout,bindlessLayout,sourceHistoryLayout,width,height,groupVariant,maximum,allDynamic,sources&&sources->ChannelExecutor());
    }
    float maximumGi()const{return constants.words[70];}
    std::array<nvrhi::TextureHandle,4> Outputs()const{
        return {compactEncoding?compactDirect:direct,compactEncoding?compactIndirect:(reconstruction.requested&&reconstruction.valid?reconstruction.result:indirect),geometry,identity};
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
    void Activate(nvrhi::IDevice* device,nvrhi::IBindingLayout* bindless,const RtrNativeExtension& extension){
        if(reconstruction.requested)throw std::logic_error("Disable GI RR before activating a raw extension evaluator");
        if(diagnosticDecomposition)throw std::logic_error("Disable diagnostic decomposition before activating an extension evaluator");
        if(!layout)throw std::logic_error("Prepare the lighting layout before activation");
        std::string nextIdentity=extension.identity;
        nvrhi::ShaderDesc shaderDesc(nvrhi::ShaderType::Compute);shaderDesc.entryName="main";shaderDesc.debugName="Public native-lighting extension";
        auto shader=device->createShader(shaderDesc,extension.code.data(),extension.code.size());if(!shader)throw std::runtime_error("Could not load extension DXIL");
        nvrhi::ComputePipelineDesc desc;desc.bindingLayouts={layout,bindless};desc.CS=shader;
        auto next=device->createComputePipeline(desc);if(!next)throw std::runtime_error("Could not prepare extension pipeline");
        RetirePipeline(device);pipeline=next;extensionIdentity=std::move(nextIdentity);extensionSignals=extension.signals;extensionMinimumBytes=extension.minimumBytes;
    }
    void ResetExtension(nvrhi::IDevice* device){if(!extensionIdentity.empty()){RetirePipeline(device);pipeline=defaultPipeline;extensionIdentity.clear();extensionSignals=15;extensionMinimumBytes=0;}}
    void RetirePipeline(nvrhi::IDevice* device){
        PollRetired(device);if(retired.size()>=64)throw std::runtime_error("Lighting retirement capacity exhausted");
        Retired old;old.pipelines.push_back(pipeline);
        old.done=device->createEventQuery();old.computeDone=device->createEventQuery();
        if(!old.done||!old.computeDone)throw std::runtime_error("Could not allocate lighting pipeline retirement queries");
        device->setEventQuery(old.done,nvrhi::CommandQueue::Graphics);
        device->setEventQuery(old.computeDone,nvrhi::CommandQueue::Compute);retired.push_back(std::move(old));
    }
    void TrackInstanceRole(const std::string& name,const InstanceAttributes& value){
        if(value.rolesVisibility.x!=23)specialInstances.insert(name);else specialInstances.erase(name);
        specialRoleCount=specialInstances.size();
    }
    void UploadChangedAttributes(nvrhi::IDevice* device,nvrhi::ICommandList* command,const std::shared_ptr<donut::engine::Scene>& scene){
        const auto graph=scene->GetSceneGraph();auto& changes=graph->HostDelta;
        RtrDiagnostics::Scope indexScope(RtrDiagnostics::ResourceIndex,changes.instances.size()+changes.meshes.size());
        EnsureAttributeBuffer(device,command,instances,std::max<size_t>(1,graph->GetMeshInstances().size())*sizeof(InstanceAttributes),sizeof(InstanceAttributes),"Native receiver roles");
        EnsureAttributeBuffer(device,command,geometryBaselines,std::max<size_t>(1,graph->GetGeometryCount())*sizeof(GeometryAttributes),sizeof(GeometryAttributes),"Native receiver geometry mapping");
        instanceUploadScratch.resize(graph->GetMeshInstances().size());instanceUploadIndices.clear();
        geometryUploadScratch.resize(graph->GetGeometryCount());geometryUploadIndices.clear();
        std::unordered_set<std::string> replacementInstances;
        std::unordered_map<std::string,size_t> replacementMeshes;
        for(auto& [identity,instance]:changes.instances)replacementInstances.insert(instance->GetNode()->GetName());
        for(auto& [identity,mesh]:changes.meshes)replacementMeshes.insert_or_assign(mesh->name,mesh->geometries.size());
        for(const auto& name:changes.removedInstances){instanceSlots.erase(name);specialInstances.erase(name);
            if(!replacementInstances.count(name)){instanceAttributes.erase(name);dirtyInstances.erase(name);}}
        for(auto& [name,count]:changes.removedMeshes)for(size_t group=0;group<count;++group){
            if(auto replacement=replacementMeshes.find(name);replacement!=replacementMeshes.end()&&group<replacement->second)continue;
            const auto key=name+":"+std::to_string(group);auto found=geometrySlots.find(key);
            if(found!=geometrySlots.end()){FreeAttributeRangeAt(found->second.offset,found->second.triangles*3);geometrySlots.erase(found);}
            geometryIndices.erase(key);meshBaselines.erase(key);meshColors.erase(key);alphaMeshes.erase(key);dirtyBaselines.erase(key);dirtyColors.erase(key);
        }
        for(auto& [identity,instance]:changes.instances){
            const auto& name=instance->GetNode()->GetName();const auto slot=instance->GetInstanceIndex();
            if(slot<0||size_t(slot)>=graph->GetMeshInstances().size())throw std::runtime_error("Invalid changed instance index");
            instanceSlots.insert_or_assign(name,uint32_t(slot));dirtyInstances.insert(name);++attributeTableVisits;
        }
        std::unordered_set<std::string> upload=dirtyBaselines;upload.insert(dirtyColors.begin(),dirtyColors.end());
        for(auto& [identity,mesh]:changes.meshes)for(size_t group=0;group<mesh->geometries.size();++group){
            auto& geometry=mesh->geometries[group];const auto key=mesh->name+":"+std::to_string(group);
            const auto index=uint32_t(geometry->globalGeometryIndex);++attributeTableVisits;
            if(index>=graph->GetGeometryCount())throw std::runtime_error("Invalid changed geometry index");
            auto found=geometrySlots.find(key);
            if(found==geometrySlots.end()||found->second.triangles*3!=geometry->numIndices){
                if(found!=geometrySlots.end())FreeAttributeRangeAt(found->second.offset,found->second.triangles*3);
                found=geometrySlots.insert_or_assign(key,GeometryAttributes{AllocateAttributeRange(geometry->numIndices),geometry->numIndices/3}).first;upload.insert(key);
            }
            auto [mapping,inserted]=geometryIndices.insert_or_assign(key,GeometryIndex{index,geometryIndexEpoch,&found->second});
            geometryUploadScratch[index]=found->second;geometryUploadIndices.push_back(index);attributeWork[3]+=sizeof(GeometryAttributes);
        }
        for(const auto& name:dirtyInstances){auto found=instanceSlots.find(name);if(found==instanceSlots.end())continue;
            auto attribute=instanceAttributes.find(name);InstanceAttributes value=attribute==instanceAttributes.end()?InstanceAttributes{}:attribute->second;
            instanceUploadScratch[found->second]=value;instanceUploadIndices.push_back(found->second);TrackInstanceRole(name,value);++attributeWork[2];attributeWork[3]+=sizeof(value);
        }
        UploadAttributeRuns(command,geometryBaselines,geometryUploadScratch,geometryUploadIndices);
        UploadAttributeRuns(command,instances,instanceUploadScratch,instanceUploadIndices);
        specialRoleCount=specialInstances.size();
        EnsureAttributeBuffer(device,command,baselineCoordinates,attributeRanges.end*sizeof(VertexAttributes),sizeof(VertexAttributes),"Native receiver attributes arena");
        for(const auto& name:upload){auto found=geometrySlots.find(name);if(found==geometrySlots.end())continue;
            auto values=PackVertices(name,size_t(found->second.triangles)*3);
            if(!values.empty())command->writeBuffer(baselineCoordinates,values.data(),values.size()*sizeof(VertexAttributes),size_t(found->second.offset)*sizeof(VertexAttributes));
            attributeWork[3]+=values.size()*sizeof(VertexAttributes);++attributeWork[4];
        }
        UploadPublications(device,command);previousSceneInstances=scene->GetInstanceBuffer();previousSceneGeometry=scene->GetGeometryBuffer();
        previousInstanceCount=graph->GetMeshInstances().size();previousGeometryCount=graph->GetGeometryCount();
        metadataDirty=false;changes.Clear();dirtyInstances.clear();dirtyBaselines.clear();dirtyColors.clear();++metadataUploads;
    }
    void UploadAttributes(nvrhi::IDevice* device,nvrhi::ICommandList* command,const std::shared_ptr<donut::engine::Scene>& scene){
        const auto graph=scene->GetSceneGraph();
        const size_t instanceCount=graph->GetMeshInstances().size(),geometryCount=graph->GetGeometryCount();
        if(previousInstanceCount!=instanceCount||previousGeometryCount!=geometryCount)metadataDirty=true;
        if(metadataDirty&&metadataInitialized){UploadChangedAttributes(device,command,scene);return;}
        if(!metadataDirty){
            bool changed=!dirtyInstances.empty()||!dirtyBaselines.empty()||!dirtyColors.empty()||lookupDirty||applicationDirty||!applicationDirtyRanges.empty();
            for(const auto& name:dirtyInstances){auto found=instanceSlots.find(name);if(found!=instanceSlots.end()){
                const auto& value=instanceAttributes.at(name);command->writeBuffer(instances,&value,sizeof(value),found->second*sizeof(value));TrackInstanceRole(name,value);++attributeWork[2];attributeWork[3]+=sizeof(value);}}
            auto changedGeometry=dirtyBaselines;changedGeometry.insert(dirtyColors.begin(),dirtyColors.end());
            for(const auto& name:changedGeometry){auto found=geometrySlots.find(name);if(found!=geometrySlots.end()){
                auto values=PackVertices(name,size_t(found->second.triangles)*3);
                command->writeBuffer(baselineCoordinates,values.data(),values.size()*sizeof(VertexAttributes),size_t(found->second.offset)*sizeof(VertexAttributes));
                attributeWork[3]+=values.size()*sizeof(VertexAttributes);++attributeWork[4];}}
            UploadPublications(device,command);dirtyInstances.clear();dirtyBaselines.clear();dirtyColors.clear();if(changed)++metadataUploads;return;
        }
        RtrDiagnostics::Scope indexScope(RtrDiagnostics::ResourceIndex);
        EnsureAttributeBuffer(device,command,instances,std::max<size_t>(1,instanceCount)*sizeof(InstanceAttributes),sizeof(InstanceAttributes),"Native receiver roles");
        instanceUploadScratch.resize(instanceCount);instanceUploadIndices.clear();
        instanceNamesScratch.assign(instanceCount,nullptr);
        for(const auto& instance:graph->GetMeshInstances()){
            const auto& name=instance->GetNode()->GetName();auto index=instance->GetInstanceIndex();
            if(index>=instanceNamesScratch.size()||instanceNamesScratch[index])throw std::runtime_error("Invalid dense instance index");
            instanceNamesScratch[index]=&name;
            auto old=instanceSlots.find(name);
            const bool changed=old==instanceSlots.end()||old->second!=index||dirtyInstances.count(name);
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
        for(const auto& mesh:graph->GetMeshes())for(size_t j=0;j<mesh->geometries.size();j++){
            if(entryCount>=geometryEntriesScratch.size())throw std::runtime_error("Invalid dense geometry count");
            const auto& g=mesh->geometries[j];auto& entry=geometryEntriesScratch[entryCount++];
            entry.name=mesh->name;entry.name+=":";entry.name+=std::to_string(j);
            entry.index=uint32_t(g->globalGeometryIndex);entry.corners=g->numIndices;
            auto old=geometryIndices.find(entry.name);
            entry.indexChanged=old==geometryIndices.end()||old->second.index!=entry.index;
            if(old==geometryIndices.end())old=geometryIndices.emplace(entry.name,GeometryIndex{entry.index,geometryIndexEpoch}).first;
            else{old->second.index=entry.index;old->second.epoch=geometryIndexEpoch;}
            entry.slot=&old->second;++attributeTableVisits;
        }
        if(entryCount!=geometryCount)throw std::runtime_error("Invalid dense geometry count");
        // Release only retired identities. Same-size replacements keep their stable range.
        for(auto i=geometryIndices.begin();i!=geometryIndices.end();)if(i->second.epoch!=geometryIndexEpoch){
            if(auto* attributes=i->second.attributes)FreeAttributeRangeAt(attributes->offset,attributes->triangles*3);
            meshBaselines.erase(i->first);meshColors.erase(i->first);alphaMeshes.erase(i->first);geometrySlots.erase(i->first);
            i=geometryIndices.erase(i);
        }else ++i;
        EnsureAttributeBuffer(device,command,geometryBaselines,std::max<size_t>(1,geometryCount)*sizeof(GeometryAttributes),sizeof(GeometryAttributes),"Native receiver geometry mapping");
        geometryUploadScratch.resize(geometryCount);geometryUploadIndices.clear();
        std::unordered_set<std::string> upload=dirtyBaselines;upload.insert(dirtyColors.begin(),dirtyColors.end());
        for(const auto& entry:geometryEntriesScratch){auto*& attributes=entry.slot->attributes;
            bool mappingChanged=!attributes||attributes->triangles*3!=entry.corners;
            if(mappingChanged){
                if(attributes)FreeAttributeRangeAt(attributes->offset,attributes->triangles*3);
                auto [found,inserted]=geometrySlots.insert_or_assign(entry.name,GeometryAttributes{AllocateAttributeRange(entry.corners),entry.corners/3});
                attributes=&found->second;upload.insert(entry.name);
            }
            if(mappingChanged||entry.indexChanged){auto value=*attributes;
                if(entry.index>=geometryUploadScratch.size())throw std::runtime_error("Invalid dense geometry index");
                geometryUploadScratch[entry.index]=value;geometryUploadIndices.push_back(entry.index);attributeWork[3]+=sizeof(value);}
        }
        UploadAttributeRuns(command,geometryBaselines,geometryUploadScratch,geometryUploadIndices);
        EnsureAttributeBuffer(device,command,baselineCoordinates,attributeRanges.end*sizeof(VertexAttributes),sizeof(VertexAttributes),"Native receiver attributes arena");
        for(const auto& name:upload){auto found=geometrySlots.find(name);if(found==geometrySlots.end())continue;
            auto values=PackVertices(name,size_t(found->second.triangles)*3);
            if(!values.empty())command->writeBuffer(baselineCoordinates,values.data(),values.size()*sizeof(VertexAttributes),size_t(found->second.offset)*sizeof(VertexAttributes));
            attributeWork[3]+=values.size()*sizeof(VertexAttributes);++attributeWork[4];
        }
        ++attributeWork[0];
        UploadPublications(device,command);
        previousSceneInstances=scene->GetInstanceBuffer();previousSceneGeometry=scene->GetGeometryBuffer();previousInstanceCount=instanceCount;previousGeometryCount=geometryCount;
        metadataDirty=false;metadataInitialized=true;graph->HostDelta.Clear();dirtyInstances.clear();dirtyBaselines.clear();dirtyColors.clear();++metadataUploads;
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
#if defined(_MSC_VER)
    // Keep this command recorder out of the large SDK host Render function.
    __declspec(noinline)
#endif
    void Dispatch(nvrhi::IDevice* device,nvrhi::ICommandList* command,const std::shared_ptr<donut::engine::Scene>& scene,
            nvrhi::rt::IAccelStruct* tlas,RtrSparseLighting& sources,nvrhi::ISampler* sampler,nvrhi::IBuffer* analytic,uint32_t lightCount,uint32_t lightRoot,uint32_t treeBase){
        const auto uploadScope=RtrGpuProfile::Begin(command,"NativeLightingUpload",-1);
        UploadAttributes(device,command,scene);sources.Upload(command);
        if(sourceHistoryClear){for(auto& texture:sourceHistory)command->clearTextureUInt(texture,nvrhi::AllSubresources,0);sourceHistoryClear=false;}
        const bool seeded=sourceHistoryActive&&!workStatisticsEnabled&&extensionIdentity.empty()&&!(diagnosticDecomposition&&RtrDiagnostics::level.load()==2);
        auto* words=reinterpret_cast<uint32_t*>(constants.words.data());words[72]=uint32_t(applicationData.size());words[73]=uint32_t(publication);words[74]=uint32_t(publication>>32);
        words[75]=(workStatisticsEnabled?1:0)|((specialRoleCount||!alphaMeshes.empty())?2:0)|(instanceCandidates?4:0)|(seeded?8:0)
            |(geometryGeneration!=lastGeometryGeneration?16:0);
        words[76]=uint32_t(timeNanos);words[77]=uint32_t(uint64_t(timeNanos)>>32);words[78]=uint32_t(deltaNanos);words[79]=uint32_t(uint64_t(deltaNanos)>>32);
        words[39]=lightCount;words[51]=lightRoot;words[59]=treeBase;
        const char* disableReuse=std::getenv("RTR_RTXPT_DISABLE_SIGNAL_REUSE");
        const bool retain=retainSignalsAllowed&&extensionIdentity.empty()&&!workStatisticsEnabled&&!diagnosticDecomposition
            &&(!disableReuse||std::string(disableReuse)!="1");
        const bool reuse=retain&&retainedSignals&&retainedReconstruction==reconstruction.requested&&retainedInputGeneration==inputGeneration
            &&retainedAnalyticGeneration==analyticGeneration
            &&retainedSourceGeneration==sources.work[1]&&retainedMetadata==metadataUploads
            &&std::memcmp(constants.words.data(),retainedConstants.words.data(),72*sizeof(float))==0
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
            nvrhi::BindingSetItem::StructuredBuffer_SRV(10,lookup),nvrhi::BindingSetItem::StructuredBuffer_SRV(11,analytic),nvrhi::BindingSetItem::Sampler(0,sampler),
            nvrhi::BindingSetItem::Texture_UAV(0,direct?direct:dummyColor),nvrhi::BindingSetItem::Texture_UAV(1,indirect?indirect:dummyColor),
            nvrhi::BindingSetItem::Texture_UAV(2,geometry?geometry:dummyColor),nvrhi::BindingSetItem::Texture_UAV(3,identity?identity:dummyIdentity),nvrhi::BindingSetItem::RawBuffer_UAV(4,work)};
        const bool deep=diagnosticDecomposition&&RtrDiagnostics::level.load()==2;
        if(reconstruction.requested)reconstruction.Bind(command,bindings,constants.words.data(),sequence);
        if(deep){
            if(!extensionIdentity.empty())throw std::runtime_error("DEEP decomposition cannot replace an extension evaluator");
            // HLSL structured layout: 29 scalar words in RtrReceiver plus valid.
            const size_t bytes=size_t(width)*height*120;
            if(!deepReceivers||deepReceivers->getDesc().byteSize<bytes){
                nvrhi::BufferDesc desc;desc.byteSize=bytes;desc.structStride=120;desc.canHaveUAVs=true;
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
        }else{if(reconstruction.requested)state.pipeline=lightCount<=1
                ?(sources.SingleGroup()?reconstruction.sparseOnlySingleGroupPipeline:sources.PairGroups()?reconstruction.sparseOnlyPairGroupPipeline:sources.SmallGroups()?reconstruction.sparseOnlySmallGroupPipeline:reconstruction.sparseOnlyPipeline)
                :(sources.SingleGroup()?reconstruction.singleGroupPipeline:sources.PairGroups()?reconstruction.pairGroupPipeline:sources.SmallGroups()?reconstruction.smallGroupPipeline:reconstruction.pipeline);
            else if(extensionIdentity.empty())state.pipeline=lightCount<=1
                ?(sources.SingleGroup()?sparseOnlySingleGroupPipeline:sources.PairGroups()?sparseOnlyPairGroupPipeline:sources.SmallGroups()?sparseOnlySmallGroupPipeline:sparseOnlyPipeline)
                :(sources.SingleGroup()?singleGroupPipeline:sources.PairGroups()?pairGroupPipeline:sources.SmallGroups()?smallGroupPipeline:pipeline);
            if(workStatisticsEnabled&&extensionIdentity.empty()&&sources.VisibleMax()){
                const unsigned variant=sources.SingleGroup()?1+(lightCount<=1?2:0):sources.PairGroups()?6+(lightCount<=1?1:0):sources.SmallGroups()?4+(lightCount<=1?1:0):(lightCount<=1?2:0);
                state.pipeline=reconstruction.requested?reconstruction.countedMaxPipelines[variant]:countedMaxPipelines[variant];
            }
            if(!workStatisticsEnabled&&extensionIdentity.empty()){
                const unsigned variant=sources.SingleGroup()?1+(lightCount<=1?2:0):sources.PairGroups()?6+(lightCount<=1?1:0):sources.SmallGroups()?4+(lightCount<=1?1:0):(lightCount<=1?2:0);
                const unsigned maxVariant=variant+(sources.dynamicCount==sources.records.size()?8:0);
                state.pipeline=reconstruction.requested
                    ?(sources.VisibleMax()?reconstruction.maxPipelines[maxVariant]:reconstruction.uninstrumentedPipelines[variant])
                    :(sources.VisibleMax()?maxPipelines[maxVariant]:uninstrumentedPipelines[variant]);
                if(sources.VisibleMax()){
                    nvrhi::BindingSetDesc history;
                    history.bindings={nvrhi::BindingSetItem::Texture_SRV(0,sourceHistory[sourceHistoryRead]),
                        nvrhi::BindingSetItem::Texture_UAV(0,sourceHistory[1-sourceHistoryRead])};
                    state.bindings.push_back(sourceHistoryBindings[sourceHistoryRead].Get(device,history,sourceHistoryLayout));
                }
            }
            if(extensionIdentity.empty()&&sources.ChannelExecutor()){
                const unsigned variant=sources.SingleGroup()?1+(lightCount<=1?2:0):sources.PairGroups()?6+(lightCount<=1?1:0):sources.SmallGroups()?4+(lightCount<=1?1:0):(lightCount<=1?2:0);
                state.pipeline=(reconstruction.requested?reconstruction.channelMaxPipelines:channelMaxPipelines)[(workStatisticsEnabled?8:0)+variant];
            }
            command->setComputeState(state);command->dispatch((width+7)/8,(height+7)/8);}
        RtrGpuProfile::End(command,dispatchScope);
        if(retain){retainedConstants=constants;retainedReconstruction=reconstruction.requested;retainedInputGeneration=inputGeneration;
            retainedAnalyticGeneration=analyticGeneration;
            retainedSourceGeneration=sources.work[1];retainedMetadata=metadataUploads;retainedSignals=true;}
        if(seeded)sourceHistoryRead=1-sourceHistoryRead;
        lastGeometryGeneration=geometryGeneration;
        if(reconstruction.requested)reconstruction.Evaluate(device,command,indirect,constants.words.data(),sequence);
        EncodeOutputs(device,command);++executedFrames;
    }
};
