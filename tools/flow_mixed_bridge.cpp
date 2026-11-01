// Measurement-only GPU bridge. Surface shading is Blender EEVEE; media is stock Flow.
// OpenGL imports Flow's Vulkan allocations; no per-frame CPU image transfers.
#define FLOW_LIBRARY
#include "flow_native_benchmark.cpp"

struct MixedHost : Host {
    NvFlowBuffer* inputs[2]{};
    NvFlowInteropHandle handles[2]{};
    void createInputs() {
        for(unsigned i=0;i<2;++i) {
            NvFlowBufferDesc bd{};bd.usageFlags=eNvFlowBufferUsage_bufferCopySrc|eNvFlowBufferUsage_bufferCopyDst;
            bd.sizeInBytes=uint64_t(width)*height*(i==0?8:4);
            inputs[i]=ctx.createBuffer(context,eNvFlowMemoryType_device,&bd);
            require(inputs[i]!=nullptr,"Shared input allocation failed");
            ctx.getBufferExternalHandle(context,inputs[i],&handles[i]);
            require(handles[i].type==eNvFlowInteropHandleType_opaqueWin32,"Vulkan allocation is not exportable to OpenGL");
        }
    }
    void fullFrame(bool refresh) {
        for(unsigned i=0;i<2;++i) {
            NvFlowPassCopyBufferToTextureParams cp{};cp.bufferRowPitch=width*(i==0?8:4);cp.bufferDepthPitch=height*cp.bufferRowPitch;
            cp.textureExtent={width,height,1};cp.src=ctx.registerBufferAsTransient(context,inputs[i]);
            cp.dst=ctx.registerTextureAsTransient(context,i==0?background:depth);cp.debugLabel="NativeSurfaceGPUHandoff";
            ctx.addPassCopyBufferToTexture(context,&cp);
        }
        render(refresh);flush();
    }
    ~MixedHost() {
        for(unsigned i=0;i<2;++i)if(inputs[i]) {
            ctx.closeBufferExternalHandle(context,inputs[i],&handles[i]);
            ctx.destroyBuffer(context,inputs[i]);
        }
    }
};
static std::string lastError;
extern "C" __declspec(dllexport) const char* flowMixedError(){return lastError.c_str();}
extern "C" __declspec(dllexport) void* flowMixedCreate(const char* fixture,float cell,unsigned deviceIndex,const uint8_t* uuid) {
    try {auto h=new MixedHost();h->init(fixture,cell,deviceIndex,true,uuid);h->createInputs();return h;}
    catch(const std::exception& e){lastError=e.what();return nullptr;}
}
extern "C" __declspec(dllexport) uint64_t flowMixedHandle(void* ptr,unsigned i){return static_cast<MixedHost*>(ptr)->handles[i].value;}
extern "C" __declspec(dllexport) uint64_t flowMixedAllocationSize(void* ptr,unsigned i){return static_cast<MixedHost*>(ptr)->handles[i].resourceSizeInBytes;}
extern "C" __declspec(dllexport) int flowMixedFrame(void* ptr,int refresh) {
    try {static_cast<MixedHost*>(ptr)->fullFrame(refresh!=0);return 0;}
    catch(const std::exception& e){lastError=e.what();return -1;}
}
extern "C" __declspec(dllexport) int flowMixedCapture(void* ptr,const char* path) {
    try {static_cast<MixedHost*>(ptr)->capture(path);return 0;}
    catch(const std::exception& e){lastError=e.what();return -1;}
}
extern "C" __declspec(dllexport) int flowMixedCaptureFarDepth(void* ptr,const char* path) {
    try {
        auto h=static_cast<MixedHost*>(ptr);std::vector<float> z(size_t(width)*height,1.f);
        auto farDepth=h->texture(eNvFlowFormat_r32_float,z.data(),4);auto saved=h->depth;h->depth=farDepth;
        h->capture(path);h->depth=saved;h->ctx.destroyTexture(h->context,farDepth);return 0;
    } catch(const std::exception& e){lastError=e.what();return -1;}
}
extern "C" __declspec(dllexport) void flowMixedDestroy(void* ptr){delete static_cast<MixedHost*>(ptr);}
