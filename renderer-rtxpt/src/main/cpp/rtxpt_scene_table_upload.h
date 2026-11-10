#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <stdexcept>
#include <vector>
#include <nvrhi/nvrhi.h>

namespace rtr::upstream::scene_upload {
// Keep this using-directive inside the adapter's own namespace. Every method
// forwards the public interface; no native command-list implementation is used.
using namespace nvrhi;

class ForwardingCommandList : public RefCounter<ICommandList> {
protected:
    CommandListHandle m_target;
    ICommandList& Target() const {
        if(!m_target)throw std::logic_error("Scene upload command list has no recording target");
        return *m_target;
    }
public:
    Object getNativeObject(ObjectType objectType) override {return Target().getNativeObject(objectType);}
    bool queryMemoryRequirements(MemoryRequirements& outRequirements) override {return Target().queryMemoryRequirements(outRequirements);}
    void open() override {Target().open();}
    void close() override {Target().close();}
    void clearState() override {Target().clearState();}
    void clearTextureFloat(ITexture* t,TextureSubresourceSet subresources,const Color& clearColor) override {Target().clearTextureFloat(t,subresources,clearColor);}
    void clearDepthStencilTexture(ITexture* t,TextureSubresourceSet subresources,bool clearDepth,float depth,bool clearStencil,uint8_t stencil) override {Target().clearDepthStencilTexture(t,subresources,clearDepth,depth,clearStencil,stencil);}
    void clearTextureUInt(ITexture* t,TextureSubresourceSet subresources,uint32_t clearColor) override {Target().clearTextureUInt(t,subresources,clearColor);}
    void copyTexture(ITexture* dest,const TextureSlice& destSlice,ITexture* src,const TextureSlice& srcSlice) override {Target().copyTexture(dest,destSlice,src,srcSlice);}
    void copyTexture(IStagingTexture* dest,const TextureSlice& destSlice,ITexture* src,const TextureSlice& srcSlice) override {Target().copyTexture(dest,destSlice,src,srcSlice);}
    void copyTexture(ITexture* dest,const TextureSlice& destSlice,IStagingTexture* src,const TextureSlice& srcSlice) override {Target().copyTexture(dest,destSlice,src,srcSlice);}
    void writeTexture(ITexture* dest,uint32_t arraySlice,uint32_t mipLevel,const void* data,size_t rowPitch,size_t depthPitch=0) override {Target().writeTexture(dest,arraySlice,mipLevel,data,rowPitch,depthPitch);}
    void resolveTexture(ITexture* dest,const TextureSubresourceSet& dstSubresources,ITexture* src,const TextureSubresourceSet& srcSubresources) override {Target().resolveTexture(dest,dstSubresources,src,srcSubresources);}
    void writeBuffer(IBuffer* b,const void* data,size_t dataSize,uint64_t destOffsetBytes=0) override {Target().writeBuffer(b,data,dataSize,destOffsetBytes);}
    void clearBufferUInt(IBuffer* b,uint32_t clearValue) override {Target().clearBufferUInt(b,clearValue);}
    void copyBuffer(IBuffer* dest,uint64_t destOffsetBytes,IBuffer* src,uint64_t srcOffsetBytes,uint64_t dataSizeBytes) override {Target().copyBuffer(dest,destOffsetBytes,src,srcOffsetBytes,dataSizeBytes);}
    void clearSamplerFeedbackTexture(ISamplerFeedbackTexture* texture) override {Target().clearSamplerFeedbackTexture(texture);}
    void decodeSamplerFeedbackTexture(IBuffer* buffer,ISamplerFeedbackTexture* texture,nvrhi::Format format) override {Target().decodeSamplerFeedbackTexture(buffer,texture,format);}
    void setSamplerFeedbackTextureState(ISamplerFeedbackTexture* texture,ResourceStates stateBits) override {Target().setSamplerFeedbackTextureState(texture,stateBits);}
    void setPushConstants(const void* data,size_t byteSize) override {Target().setPushConstants(data,byteSize);}
    void setGraphicsState(const GraphicsState& state) override {Target().setGraphicsState(state);}
    void draw(const DrawArguments& args) override {Target().draw(args);}
    void drawIndexed(const DrawArguments& args) override {Target().drawIndexed(args);}
    void drawIndirect(uint32_t offsetBytes,uint32_t drawCount=1) override {Target().drawIndirect(offsetBytes,drawCount);}
    void drawIndexedIndirect(uint32_t offsetBytes,uint32_t drawCount=1) override {Target().drawIndexedIndirect(offsetBytes,drawCount);}
    void drawIndexedIndirectCount(uint32_t paramOffsetBytes,uint32_t countOffsetBytes,uint32_t maxDrawCount) override {Target().drawIndexedIndirectCount(paramOffsetBytes,countOffsetBytes,maxDrawCount);}
    void setComputeState(const ComputeState& state) override {Target().setComputeState(state);}
    void dispatch(uint32_t groupsX,uint32_t groupsY=1,uint32_t groupsZ=1) override {Target().dispatch(groupsX,groupsY,groupsZ);}
    void dispatchIndirect(uint32_t offsetBytes) override {Target().dispatchIndirect(offsetBytes);}
    void setMeshletState(const MeshletState& state) override {Target().setMeshletState(state);}
    void dispatchMesh(uint32_t groupsX,uint32_t groupsY=1,uint32_t groupsZ=1) override {Target().dispatchMesh(groupsX,groupsY,groupsZ);}
    void dispatchMeshIndirect(uint32_t offsetBytes,uint32_t maxDrawCount) override {Target().dispatchMeshIndirect(offsetBytes,maxDrawCount);}
    void dispatchMeshIndirectCount(uint32_t paramOffsetBytes,uint32_t countOffsetBytes,uint32_t maxDrawCount) override {Target().dispatchMeshIndirectCount(paramOffsetBytes,countOffsetBytes,maxDrawCount);}
    void setRayTracingState(const rt::State& state) override {Target().setRayTracingState(state);}
    void dispatchRays(const rt::DispatchRaysArguments& args) override {Target().dispatchRays(args);}
    void buildOpacityMicromap(rt::IOpacityMicromap* omm,const rt::OpacityMicromapDesc& desc) override {Target().buildOpacityMicromap(omm,desc);}
    void buildBottomLevelAccelStruct(rt::IAccelStruct* as,const rt::GeometryDesc* geometries,size_t numGeometries,rt::AccelStructBuildFlags buildFlags=rt::AccelStructBuildFlags::None) override {Target().buildBottomLevelAccelStruct(as,geometries,numGeometries,buildFlags);}
    void compactBottomLevelAccelStructs() override {Target().compactBottomLevelAccelStructs();}
    void copyRaytracingAccelerationStructure(rt::IAccelStruct* destination,rt::IAccelStruct* source) override {Target().copyRaytracingAccelerationStructure(destination,source);}
    void buildTopLevelAccelStruct(rt::IAccelStruct* as,const rt::InstanceDesc* instances,size_t numInstances,rt::AccelStructBuildFlags buildFlags=rt::AccelStructBuildFlags::None) override {Target().buildTopLevelAccelStruct(as,instances,numInstances,buildFlags);}
    void executeMultiIndirectClusterOperation(const rt::cluster::OperationDesc& desc) override {Target().executeMultiIndirectClusterOperation(desc);}
    void buildTopLevelAccelStructFromBuffer(rt::IAccelStruct* as,nvrhi::IBuffer* instanceBuffer,uint64_t instanceBufferOffset,size_t numInstances,rt::AccelStructBuildFlags buildFlags=rt::AccelStructBuildFlags::None) override {Target().buildTopLevelAccelStructFromBuffer(as,instanceBuffer,instanceBufferOffset,numInstances,buildFlags);}
    void convertCoopVecMatrices(coopvec::ConvertMatrixLayoutDesc const* convertDescs,size_t numDescs) override {Target().convertCoopVecMatrices(convertDescs,numDescs);}
    void beginTimerQuery(ITimerQuery* query) override {Target().beginTimerQuery(query);}
    void endTimerQuery(ITimerQuery* query) override {Target().endTimerQuery(query);}
    void beginMarker(const char* name) override {Target().beginMarker(name);}
    void endMarker() override {Target().endMarker();}
    void setEnableAutomaticBarriers(bool enable) override {Target().setEnableAutomaticBarriers(enable);}
    void setResourceStatesForBindingSet(IBindingSet* bindingSet) override {Target().setResourceStatesForBindingSet(bindingSet);}
    void setEnableUavBarriersForTexture(ITexture* texture,bool enableBarriers) override {Target().setEnableUavBarriersForTexture(texture,enableBarriers);}
    void setEnableUavBarriersForBuffer(IBuffer* buffer,bool enableBarriers) override {Target().setEnableUavBarriersForBuffer(buffer,enableBarriers);}
    void beginTrackingTextureState(ITexture* texture,TextureSubresourceSet subresources,ResourceStates stateBits) override {Target().beginTrackingTextureState(texture,subresources,stateBits);}
    void beginTrackingBufferState(IBuffer* buffer,ResourceStates stateBits) override {Target().beginTrackingBufferState(buffer,stateBits);}
    void setTextureState(ITexture* texture,TextureSubresourceSet subresources,ResourceStates stateBits) override {Target().setTextureState(texture,subresources,stateBits);}
    void setBufferState(IBuffer* buffer,ResourceStates stateBits) override {Target().setBufferState(buffer,stateBits);}
    void setAccelStructState(rt::IAccelStruct* as,ResourceStates stateBits) override {Target().setAccelStructState(as,stateBits);}
    void setPermanentTextureState(ITexture* texture,ResourceStates stateBits) override {Target().setPermanentTextureState(texture,stateBits);}
    void setPermanentBufferState(IBuffer* buffer,ResourceStates stateBits) override {Target().setPermanentBufferState(buffer,stateBits);}
    void commitBarriers() override {Target().commitBarriers();}
    ResourceStates getTextureSubresourceState(ITexture* texture,ArraySlice arraySlice,MipLevel mipLevel) override {return Target().getTextureSubresourceState(texture,arraySlice,mipLevel);}
    ResourceStates getBufferState(IBuffer* buffer) override {return Target().getBufferState(buffer);}
    IDevice* getDevice() override {return Target().getDevice();}
    const CommandListParameters& getDesc() override {return Target().getDesc();}
};

// Scene::WriteInstanceBuffer is the only supported boundary exposing its
// already-packed instance array. Slice that synchronous write, not Scene's
// private Resources or a second implementation of UpdateInstance's packing.
class SceneTableUploadCommands final : public ForwardingCommandList {
public:
    struct Statistics {uint64_t rows=0,writes=0,bytes=0;bool full=false;};
    template<class Writer> Statistics Record(ICommandList* commands,IBuffer* buffer,size_t stride,
            std::vector<uint32_t>& dirty,bool initialized,Writer&& writer){
        if(m_target||!commands||!buffer||!stride)
            throw std::logic_error("Invalid or nested scene-table recording");
        std::sort(dirty.begin(),dirty.end());dirty.erase(std::unique(dirty.begin(),dirty.end()),dirty.end());
        m_spans.clear();
        for(size_t first=0;first<dirty.size();){
            size_t end=first+1;
            while(end<dirty.size()&&uint64_t(dirty[end])==uint64_t(dirty[end-1])+1)++end;
            if(uint64_t(dirty[first])>std::numeric_limits<uint64_t>::max()/stride
                ||uint64_t(end-first)>std::numeric_limits<uint64_t>::max()/stride){initialized=false;m_spans.clear();break;}
            m_spans.push_back({uint64_t(dirty[first])*stride,uint64_t(end-first)*stride});first=end;
        }
        m_buffer=buffer;m_stride=stride;m_initialized=initialized;m_written=false;m_statistics={};m_target=commands;
        struct Reset {SceneTableUploadCommands& owner;~Reset(){owner.m_target=nullptr;owner.m_buffer=nullptr;}} reset{*this};
        writer(this);
        if(!m_written)throw std::logic_error("Native scene-table writer omitted its instance buffer");
        return m_statistics;
    }
    void writeBuffer(IBuffer* buffer,const void* data,size_t dataSize,uint64_t offset=0) override {
        if(buffer!=m_buffer){Target().writeBuffer(buffer,data,dataSize,offset);return;}
        if(m_written)throw std::logic_error("Native scene-table writer repeated its instance buffer");
        m_written=true;
        const auto& description=buffer->getDesc();
        if(offset>description.byteSize||dataSize>description.byteSize-offset||(dataSize&&!data))
            throw std::out_of_range("Native scene-table upload exceeds its buffer");
        // First publication and unexpectedly shaped writes retain the original
        // full-write behavior. Bound fragmented traffic without guessing CPU
        // capacity; the supplied native dataSize is the sole row-array bound.
        bool full=!m_initialized||offset!=0||dataSize%m_stride||m_spans.size()>64||description.isVolatile;
        uint64_t selectedBytes=0;
        for(const auto& span:m_spans){
            if(span.offset>dataSize||span.bytes>dataSize-span.offset)full=true;
            if(span.bytes>std::numeric_limits<uint64_t>::max()-selectedBytes)full=true;
            else selectedBytes+=span.bytes;
        }
        full|=selectedBytes>=dataSize;
        if(full){
            Target().writeBuffer(buffer,data,dataSize,offset);
            m_statistics={dataSize/m_stride,1,dataSize,true};return;
        }
        const auto* bytes=static_cast<const uint8_t*>(data);
        for(const auto& span:m_spans){
            Target().writeBuffer(buffer,bytes+span.offset,size_t(span.bytes),span.offset);
            m_statistics.rows+=span.bytes/m_stride;++m_statistics.writes;m_statistics.bytes+=span.bytes;
        }
    }
private:
    struct Span {uint64_t offset,bytes;};
    std::vector<Span> m_spans;
    IBuffer* m_buffer=nullptr;
    size_t m_stride=0;
    bool m_initialized=false,m_written=false;
    Statistics m_statistics;
};
}
