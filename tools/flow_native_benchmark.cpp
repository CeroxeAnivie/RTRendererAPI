// Native, standalone Flow SDK comparison host. No Java or RTRenderer runtime.
// All sparse allocation, volume stepping, shadowing and compositing stay upstream.
// Fixture adaptation is deliberately NOT a full physical-media implementation.
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <stdexcept>
#include <string>
#include <vector>
#include "NvFlowLoader.h"
#include "NvFlowMath.h"

static const unsigned width=1920, height=1080;
static unsigned errorCount=0;
static void logPrint(NvFlowLogLevel level,const char* fmt,...) {
    if(level==eNvFlowLogLevel_error)++errorCount;
    va_list args;va_start(args,fmt);vfprintf(stderr,fmt,args);va_end(args);fputc('\n',stderr);
}
static void loadError(const char* str,void*) {fprintf(stderr,"Flow loader: %s\n",str);++errorCount;}
static void require(bool ok,const char* message) {if(!ok)throw std::runtime_error(message);}
template<class T>static T read(std::ifstream& f) {T v{};f.read(reinterpret_cast<char*>(&v),sizeof(v));require(bool(f),"Truncated fixture");return v;}
struct Volume {
    float transform[16];float density[64];float color[3];float emission[3];
};
struct Host {
    NvFlowLoader loader{};NvFlowContextInterface ctx{};
    NvFlowDeviceManager* manager=nullptr;NvFlowDevice* device=nullptr;NvFlowDeviceQueue* queue=nullptr;
    NvFlowContext* context=nullptr;NvFlowGrid* grid=nullptr;
    NvFlowTexture* background=nullptr;NvFlowTexture* depth=nullptr;
    NvFlowFloat4x4 view{},projection{};
    std::vector<Volume> source;
    std::vector<NvFlowGridSimulateLayerParams> sim;
    std::vector<NvFlowGridOffscreenLayerParams> off;
    std::vector<NvFlowGridRenderLayerParams> ren;
    std::vector<NvFlowGridEmitterTextureParams> emit;
    std::vector<NvFlowGridSimulateLayerParams*> simPtr;
    std::vector<NvFlowGridOffscreenLayerParams*> offPtr;
    std::vector<NvFlowGridRenderLayerParams*> renPtr;
    std::vector<NvFlowGridEmitterTextureParams*> emitPtr;
    std::vector<NvFlowFloat4> colors;
    float colorX[2]={0.f,1.f};
    NvFlowDatabaseTypeSnapshot types[4]{};
    NvFlowGridParamsDescSnapshot snapshot{};NvFlowGridParamsDesc params{};
    unsigned blocks=0;uint64_t frame=0;double time=0;
    NvFlowTextureTransient* output=nullptr;
    void flush() {
        NvFlowUint64 completed=0;
        require(loader.deviceInterface.flush(queue,&completed,nullptr,nullptr)==0,"Flow device reset");
        loader.deviceInterface.waitForFrame(queue,completed);
        require(errorCount==0,"Flow reported errors");
    }
    NvFlowTexture* texture(NvFlowFormat format,const void* data,unsigned bytesPerPixel) {
        NvFlowTextureDesc td{};td.textureType=eNvFlowTextureType_2d;
        td.usageFlags=eNvFlowTextureUsage_texture|eNvFlowTextureUsage_textureCopyDst|eNvFlowTextureUsage_textureCopySrc;
        td.format=format;td.width=width;td.height=height;td.depth=1;td.mipLevels=1;
        auto tex=ctx.createTexture(context,&td);require(tex!=nullptr,"Texture allocation failed");
        NvFlowBufferDesc bd{};bd.usageFlags=eNvFlowBufferUsage_bufferCopySrc;
        bd.sizeInBytes=uint64_t(width)*height*bytesPerPixel;
        auto buf=ctx.createBuffer(context,eNvFlowMemoryType_upload,&bd);
        auto dst=ctx.mapBuffer(context,buf);require(dst!=nullptr,"Upload mapping failed");
        memcpy(dst,data,size_t(bd.sizeInBytes));ctx.unmapBuffer(context,buf);
        NvFlowPassCopyBufferToTextureParams cp{};cp.bufferRowPitch=width*bytesPerPixel;cp.bufferDepthPitch=height*cp.bufferRowPitch;
        cp.textureExtent={width,height,1};cp.src=ctx.registerBufferAsTransient(context,buf);
        cp.dst=ctx.registerTextureAsTransient(context,tex);cp.debugLabel="FixtureUpload";
        ctx.addPassCopyBufferToTexture(context,&cp);flush();ctx.destroyBuffer(context,buf);return tex;
    }
    void init(const char* fixture,float cellSize,unsigned deviceIndex,bool externalUsage=false) {
        NvFlowLoaderInit(&loader,loadError,nullptr);
        require(loader.module_nvflow&&loader.module_nvflowext,"Flow DLL loading failed");
        manager=loader.deviceInterface.createDeviceManager(NV_FLOW_FALSE,nullptr,2);
        NvFlowDeviceDesc dd{};dd.deviceIndex=deviceIndex;dd.enableExternalUsage=externalUsage?NV_FLOW_TRUE:NV_FLOW_FALSE;dd.logPrint=logPrint;
        NvFlowPhysicalDeviceDesc physical{};
        require(loader.deviceInterface.enumerateDevices(manager,deviceIndex,&physical),"Selected GPU is unavailable");
        fprintf(stderr,"Flow Vulkan deviceIndex=%u uuid=",deviceIndex);
        for(auto c:physical.deviceUUID)fprintf(stderr,"%02x",c);
        fputc('\n',stderr);
        device=loader.deviceInterface.createDevice(manager,&dd);require(device!=nullptr,"Vulkan device creation failed");
        queue=loader.deviceInterface.getDeviceQueue(device);context=loader.deviceInterface.getContext(queue);
        NvFlowContextInterface_duplicate(&ctx,loader.deviceInterface.getContextInterface(queue));
        std::ifstream f(fixture,std::ios::binary);require(bool(f),"Cannot open fixture");
        require(read<uint32_t>(f)==0x314f4c46,"Fixture magic mismatch");
        unsigned n=read<uint32_t>(f);require(n<128,"Invalid volume count");
        source.resize(n);for(auto& v:source)v=read<Volume>(f);
        sim.resize(n);off.resize(n);ren.resize(n);emit.resize(n);colors.resize(n*2);
        simPtr.resize(n);offPtr.resize(n);renPtr.resize(n);emitPtr.resize(n);
        for(unsigned i=0;i<n;++i) {
            auto& s=sim[i];s=NvFlowGridSimulateLayerParams_default;s.luid=i+1;s.layer=int(i);
            s.densityCellSize=cellSize;s.forceDisableCoreSimulation=NV_FLOW_TRUE;
            s.stepsPerSecond=60;s.maxStepsPerSimulate=1;s.enableSmallBlocks=NV_FLOW_TRUE;
            s.nanoVdbExport.enabled=NV_FLOW_FALSE;
            auto& e=emit[i];e=NvFlowEmitterTextureParams_default;e.luid=i+1000;e.layer=int(i);
            memcpy(&e.localToWorld,source[i].transform,sizeof(e.localToWorld));
            e.halfSize={.5f,.5f,.5f};e.velocity={0,0,0};e.temperature=.5f;e.fuel=0;e.smoke=0;
            e.coupleRateVelocity=0;e.coupleRateTemperature=1e6f;e.coupleRateFuel=0;e.coupleRateSmoke=1e6f;
            e.textureWidth=4;e.textureHeight=4;e.textureDepth=4;
            e.textureSmokes=source[i].density;e.textureSmokeCount=64;e.textureSmokeVersion=1;
            auto& o=off[i];o=NvFlowGridOffscreenLayerParams_default;o.luid=i+2000;o.layer=int(i);
            o.shadow.attenuation=1.f;o.shadow.lightDirection={.2f,-.4f,-.8944272f};
            o.shadow.minIntensity=.001f;o.shadow.numSteps=16;
            o.colormap.xPoints=colorX;o.colormap.xPointCount=2;o.colormap.rgbaPoints=&colors[i*2];o.colormap.rgbaPointCount=2;
            o.colormap.colorScalePoints=nullptr;o.colormap.colorScalePointCount=0;o.colormap.colorScale=1;
            for(unsigned j=0;j<2;++j)colors[i*2+j]={source[i].color[0]+source[i].emission[0],source[i].color[1]+source[i].emission[1],source[i].color[2]+source[i].emission[2],1.f};
            auto& r=ren[i];r=NvFlowGridRenderLayerParams_default;r.luid=i+3000;r.layer=int(i);
            r.rayMarch.attenuation=1.f;r.rayMarch.stepSizeScale=.75f;
            simPtr[i]=&s;offPtr[i]=&o;renPtr[i]=&r;emitPtr[i]=&e;
        }
        types[0]={1,&NvFlowGridSimulateLayerParams_NvFlowReflectDataType,(NvFlowUint8**)simPtr.data(),n};
        types[1]={1,&NvFlowGridEmitterTextureParams_NvFlowReflectDataType,(NvFlowUint8**)emitPtr.data(),n};
        types[2]={1,&NvFlowGridOffscreenLayerParams_NvFlowReflectDataType,(NvFlowUint8**)offPtr.data(),n};
        types[3]={1,&NvFlowGridRenderLayerParams_NvFlowReflectDataType,(NvFlowUint8**)renPtr.data(),n};
        snapshot.snapshot={1,types,4};snapshot.deltaTime=1.f/60.f;params.snapshots=&snapshot;params.snapshotCount=1;
        NvFlowGridDesc gd=NvFlowGridDesc_default;gd.maxLocations=4096;
        grid=loader.gridInterface.createGrid(&ctx,context,loader.opList_orig,loader.extOpList_orig,&gd);
        require(grid!=nullptr,"Grid creation failed");
        view=NvFlowMath::matrixTranslation(0,0,-7);
        projection=NvFlowMath::matrixPerspectiveFovRH(30.f*3.14159265358979323846f/180.f,float(width)/height,.001f,100.f);
        std::vector<uint16_t> bg(size_t(width)*height*4);
        // Linear half-float environment background. No surface-rendering cost is claimed.
        for(size_t p=0;p<size_t(width)*height;++p){bg[p*4]=0x2fae;bg[p*4+1]=0x311f;bg[p*4+2]=0x330a;bg[p*4+3]=0x3c00;}
        std::vector<float> z(size_t(width)*height,1.f);
        background=texture(eNvFlowFormat_r16g16b16a16_float,bg.data(),8);
        depth=texture(eNvFlowFormat_r32_float,z.data(),4);
        for(unsigned i=0;i<8;++i) {
            time+=1./60.;snapshot.absoluteSimTime=time;snapshot.deltaTime=1.f/60.f;
            loader.gridInterface.simulate(context,grid,&params,NV_FLOW_FALSE);
            loader.gridInterface.offscreen(context,grid,&params);flush();
        }
        blocks=loader.gridInterface.getActiveBlockCount(grid);require(n==0||blocks>0,"No allocated volume blocks");
        fprintf(stderr,"Flow fixture: volumes=%u blocks=%u cellSize=%.7f\n",n,blocks,cellSize);
    }
    void render(bool refresh) {
        if(refresh) {
            time+=1./60.;snapshot.absoluteSimTime=time;snapshot.deltaTime=1.f/60.f;
            loader.gridInterface.simulate(context,grid,&params,NV_FLOW_FALSE);
            loader.gridInterface.offscreen(context,grid,&params);
        }
        auto bg=ctx.registerTextureAsTransient(context,background);
        auto z=ctx.registerTextureAsTransient(context,depth);
        loader.gridInterface.render(context,grid,&params,&view,&projection,&projection,width,height,width,height,1.f,z,eNvFlowFormat_r16g16b16a16_float,bg,&output);
        require(output!=nullptr,"Flow produced no output texture");++frame;
    }
    size_t capture(const std::string& path) {
        render(false);
        const unsigned pitch=width*8;
        NvFlowBufferDesc bd{};bd.usageFlags=eNvFlowBufferUsage_bufferCopyDst;bd.sizeInBytes=uint64_t(pitch)*height;
        auto rb=ctx.createBuffer(context,eNvFlowMemoryType_readback,&bd);
        NvFlowPassCopyTextureToBufferParams cp{};cp.bufferRowPitch=pitch;cp.bufferDepthPitch=pitch*height;
        cp.textureExtent={width,height,1};cp.src=output;cp.dst=ctx.registerBufferAsTransient(context,rb);cp.debugLabel="EvidenceCapture";
        ctx.addPassCopyTextureToBuffer(context,&cp);flush();
        auto pixels=static_cast<uint16_t*>(ctx.mapBuffer(context,rb));require(pixels!=nullptr,"Readback failed");
        size_t changed=0;
        for(size_t p=0;p<size_t(width)*height;++p)if(pixels[p*4]!=0x2fae||pixels[p*4+1]!=0x311f||pixels[p*4+2]!=0x330a)++changed;
        std::ofstream f(path,std::ios::binary);f.write(reinterpret_cast<char*>(pixels),std::streamsize(bd.sizeInBytes));
        ctx.unmapBuffer(context,rb);ctx.destroyBuffer(context,rb);
        require(source.empty()||changed>1000,"No visible volume contribution; rejecting empty-frame result");return changed;
    }
    ~Host() {
        if(grid)loader.gridInterface.destroyGrid(context,grid);
        if(background)ctx.destroyTexture(context,background);if(depth)ctx.destroyTexture(context,depth);
        if(queue)flush();
        if(device)loader.deviceInterface.destroyDevice(manager,device);
        if(manager)loader.deviceInterface.destroyDeviceManager(manager);
        if(loader.module_nvflow)NvFlowLoaderDestroy(&loader);
    }
};
#ifndef FLOW_LIBRARY
int main(int argc,char** argv) {
    try {
        require(argc>=7,"Usage: flow_native_benchmark fixture.bin output.json cellSize refresh seconds verifiedVulkanDeviceIndex");
        float cellSize=std::stof(argv[3]);bool refresh=argc>4&&std::atoi(argv[4])!=0;
        double seconds=std::stod(argv[5]);unsigned deviceIndex=std::stoul(argv[6]);Host h;h.init(argv[1],cellSize,deviceIndex);
        for(unsigned i=0;i<32;++i){h.render(refresh);h.flush();}
        size_t visible=h.capture(std::string(argv[2])+".rgba16f");
        std::vector<double> rounds;
        std::ofstream out(argv[2]);out<<"{\n  \"renderer\":\"NVIDIA Flow Vulkan\",\n  \"width\":1920,\"height\":1080,\n";
        out<<"  \"metric\":\"completed volume-render frames with per-frame GPU fence\",\n";
        out<<"  \"vulkanDeviceIndex\":"<<deviceIndex<<",\n";
        out<<"  \"fullBenchmarkSupported\":false,\"samplesPerPixel\":null,\"denoising\":false,\"temporalReuse\":false,\"frameGeneration\":false,\n";
        out<<"  \"cellSize\":"<<cellSize<<",\"stepSizeScale\":0.75,\"shadowSteps\":16,\"refreshDensityAndShadowsEachFrame\":"<<(refresh?"true":"false")<<",\n";
        out<<"  \"volumes\":"<<h.source.size()<<",\"activeBlocks\":"<<h.blocks<<",\"visibleChangedPixels\":"<<visible<<",\n  \"rounds\":[\n";
        double totalMs=0;uint64_t totalFrames=0;
        for(unsigned r=0;r<3;++r) {
            uint64_t frames=0;auto start=std::chrono::steady_clock::now();double ms=0;
            do {h.render(refresh);h.flush();++frames;ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count();}while(ms<seconds*1000);
            if(r)out<<",\n";out<<"    {\"frames\":"<<frames<<",\"wallMs\":"<<ms<<",\"fps\":"<<frames*1000./ms<<"}";
            fprintf(stderr,"Flow round %u: %llu frames / %.3f ms = %.3f FPS\n",r+1,(unsigned long long)frames,ms,frames*1000./ms);
            totalMs+=ms;totalFrames+=frames;
        }
        out<<"\n  ],\n  \"fps\":"<<totalFrames*1000./totalMs<<",\"meanMs\":"<<totalMs/totalFrames<<",\"errors\":"<<errorCount<<"\n}\n";
        printf("FLOW_RESULT fps=%.3f ms=%.6f blocks=%u visible=%zu\n",totalFrames*1000./totalMs,totalMs/totalFrames,h.blocks,visible);
        return 0;
    } catch(const std::exception& e) {fprintf(stderr,"Flow benchmark failed: %s\n",e.what());return 1;}
}
#endif
