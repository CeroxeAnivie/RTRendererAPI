// Include after session(), HostPathTracer and fail() are defined in rtxpt_jni.cpp.
// Explicit diagnostic: exercises the production encoder over 100001 float32
// inputs, with readback/wait confined to this test entry point.
extern "C" JNIEXPORT jdoubleArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingGridProbe(JNIEnv* e,jclass,jlong handle){
    try{
        auto& s=session(handle);auto& rr=static_cast<HostPathTracer*>(s.app->HostRenderPass())->nativeLighting.reconstruction;
        if(!rr.upsamplePipeline)throw std::runtime_error("Run a scaled GI frame before the grid diagnostic");
        auto* device=s.app->HostDeviceManager()->GetDevice();constexpr uint32_t w=16,h=8;
        auto texture=[&](uint32_t x,uint32_t y,nvrhi::Format format){return device->createTexture(nvrhi::TextureDesc().setWidth(x).setHeight(y).setFormat(format)
            .setIsUAV(true).setInitialState(nvrhi::ResourceStates::UnorderedAccess).setKeepInitialState(true));};
        auto raw=texture(w,h,nvrhi::Format::RGBA32_FLOAT),albedo=texture(w,h,nvrhi::Format::RGBA32_FLOAT),normal=texture(w,h,nvrhi::Format::RGBA32_FLOAT);
        auto surfaces=texture(w,h,nvrhi::Format::RGBA32_UINT),reconstructed=texture(w/2,h/2,nvrhi::Format::RGBA32_FLOAT),output=texture(w,h,nvrhi::Format::RGBA32_FLOAT);
        if(!raw||!albedo||!normal||!surfaces||!reconstructed||!output)throw std::runtime_error("Grid diagnostic allocation failed");
        std::array<float4,w*h> rv,av,nv;std::array<std::array<uint32_t,4>,w*h> sv;
        std::array<float4,w*h/4> low;
        for(uint32_t i=0;i<w*h;i++){rv[i]=float4(.125f,.25f,.5f,1);av[i]=float4(.5f,.5f,.5f,1);nv[i]=float4(0,0,1,.5f);
            sv[i]={i%w<8?1u:2u,0,0x3f800000,1};}
        for(uint32_t i=0;i<w*h/4;i++)low[i]=i%(w/2)<4?float4(2,0,0,1):float4(0,2,0,1);
        rv[0].w=0;rv[1].w=.5f;
        nv[2*w+2]=float4(0,0,-1,.5f);sv[2*w+4][2]=0x40400000;sv[2*w+6][1]=77;av[2*w+10]=float4(0,0,0,1);
        auto staging=device->createStagingTexture(output->getDesc(),nvrhi::CpuAccessMode::Read);auto command=device->createCommandList();command->open();
        command->writeTexture(raw,0,0,rv.data(),w*sizeof(float4));command->writeTexture(albedo,0,0,av.data(),w*sizeof(float4));
        command->writeTexture(normal,0,0,nv.data(),w*sizeof(float4));command->writeTexture(surfaces,0,0,sv.data(),w*16);
        command->writeTexture(reconstructed,0,0,low.data(),w/2*sizeof(float4));
        nvrhi::BindingSetDesc desc;desc.bindings={nvrhi::BindingSetItem::Texture_SRV(0,reconstructed),nvrhi::BindingSetItem::Texture_SRV(1,raw),
            nvrhi::BindingSetItem::Texture_SRV(2,albedo),nvrhi::BindingSetItem::Texture_SRV(3,normal),nvrhi::BindingSetItem::Texture_SRV(4,surfaces),nvrhi::BindingSetItem::Texture_UAV(0,output)};
        auto binding=device->createBindingSet(desc,rr.upsampleLayout);nvrhi::ComputeState state;state.pipeline=rr.upsamplePipeline;state.bindings={binding};
        command->setComputeState(state);command->dispatch((w+7)/8,(h+7)/8);command->copyTexture(staging,nvrhi::TextureSlice(),output,nvrhi::TextureSlice());
        command->close();device->executeCommandList(command);device->waitForIdle(); // Explicit diagnostic only.
        size_t pitch=0;auto* bytes=static_cast<const uint8_t*>(device->mapStagingTexture(staging,nvrhi::TextureSlice(),nvrhi::CpuAccessMode::Read,&pitch));
        if(!bytes)throw std::runtime_error("Grid diagnostic readback failed");std::array<double,5> result{w*h,0,0,4,0};
        for(uint32_t y=0;y<h;y++)for(uint32_t x=0;x<w;x++){
            const auto* value=reinterpret_cast<const float4*>(bytes+y*pitch)+x;
            bool fallback=y==2&&(x==2||x==4||x==6||x==10);
            float4 expected=rv[y*w+x].w==0?float4(0,0,0,0):fallback?rv[y*w+x]:(x<8?float4(2,0,0,rv[y*w+x].w):float4(0,2,0,rv[y*w+x].w));
            if(value->x!=expected.x||value->y!=expected.y||value->z!=expected.z)++result[1];if(value->w!=expected.w)++result[2];
            if(!std::isfinite(value->x)||!std::isfinite(value->y)||!std::isfinite(value->z)||!std::isfinite(value->w))++result[4];
        }
        device->unmapStagingTexture(staging);auto array=e->NewDoubleArray(jsize(result.size()));if(array)e->SetDoubleArrayRegion(array,0,jsize(result.size()),result.data());return array;
    }catch(const std::exception& failure){fail(e,failure);return nullptr;}
}
extern "C" JNIEXPORT jdoubleArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingCompactProbe(
    JNIEnv* e,jclass,jlong handle){
    try{
        auto& s=session(handle);auto& host=*static_cast<HostPathTracer*>(s.app->HostRenderPass());
        auto* device=s.app->HostDeviceManager()->GetDevice();
        constexpr uint32_t width=4096,height=25,count=100001;
        RtrNativeLightingPass probe;probe.width=width;probe.height=height;probe.mask=3;probe.compactEncoding=true;probe.constants.words[70]=1;
        auto desc=nvrhi::TextureDesc().setWidth(width).setHeight(height).setFormat(nvrhi::Format::RGBA32_FLOAT)
            .setInitialState(nvrhi::ResourceStates::ShaderResource).setKeepInitialState(true);
        probe.direct=device->createTexture(desc);probe.indirect=device->createTexture(desc);
        if(!probe.direct||!probe.indirect)throw std::runtime_error("Could not allocate encoder diagnostic inputs");
        probe.PrepareCompact(device,*host.GetShaderFactory());
        auto directRead=device->createStagingTexture(probe.compactDirect->getDesc(),nvrhi::CpuAccessMode::Read);
        auto indirectRead=device->createStagingTexture(probe.compactIndirect->getDesc(),nvrhi::CpuAccessMode::Read);
        if(!directRead||!indirectRead)throw std::runtime_error("Could not allocate encoder diagnostic readback");
        std::vector<float4> direct(width*height),indirect(width*height);
        for(uint32_t i=0;i<width*height;i++){const float value=float((std::min)(i,count-1))/float(count-1);
            direct[i]=float4(value*15.65f,1,0,1);indirect[i]=float4(value,0,1,1);}
        auto command=device->createCommandList();command->open();
        command->writeTexture(probe.direct,0,0,direct.data(),width*sizeof(float4));command->writeTexture(probe.indirect,0,0,indirect.data(),width*sizeof(float4));
        probe.EncodeOutputs(device,command);command->copyTexture(directRead,nvrhi::TextureSlice(),probe.compactDirect,nvrhi::TextureSlice());
        command->copyTexture(indirectRead,nvrhi::TextureSlice(),probe.compactIndirect,nvrhi::TextureSlice());command->close();device->executeCommandList(command);
        device->waitForIdle(); // Diagnostic-only; ordinary encoding never waits or reads back.
        std::array<double,6> result{};size_t directPitch=0,indirectPitch=0;
        auto* gain=static_cast<const uint8_t*>(device->mapStagingTexture(directRead,nvrhi::TextureSlice(),nvrhi::CpuAccessMode::Read,&directPitch));
        if(!gain)throw std::runtime_error("Could not map diagnostic gain output");
        auto* gi=static_cast<const uint8_t*>(device->mapStagingTexture(indirectRead,nvrhi::TextureSlice(),nvrhi::CpuAccessMode::Read,&indirectPitch));
        if(!gi){device->unmapStagingTexture(directRead);throw std::runtime_error("Could not map diagnostic GI output");}
        for(uint32_t i=0;i<count;i++){
            const auto offset=(i%width)*4;const auto* g=gain+(i/width)*directPitch+offset;const auto* b=gi+(i/width)*indirectPitch+offset;
            const double value=double(i)/double(count-1),original=value*15.65;
            const double decoded=g[0]<=128?double(g[0])/128:std::exp2((double(g[0])-128)/32);
            const double error=std::abs(decoded-original);
            if(original<=1)result[0]=(std::max)(result[0],error);else result[1]=(std::max)(result[1],error/original);
            const double normalized=double(b[0])/255;result[2]=(std::max)(result[2],std::abs(normalized*normalized-value));
            if(g[1]!=128||g[2]!=0||g[3]!=255||b[1]!=0||b[2]!=255||b[3]!=255)++result[3];
        }
        device->unmapStagingTexture(directRead);device->unmapStagingTexture(indirectRead);
        result[4]=count;result[5]=8; // requested pair is 2 * RGBA8, eight bytes/pixel.
        auto output=e->NewDoubleArray(jsize(result.size()));if(output)e->SetDoubleArrayRegion(output,0,jsize(result.size()),result.data());return output;
    }catch(const std::exception& failure){fail(e,failure);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingEncoding(
    JNIEnv* e,jclass,jlong handle,jint encoding){
    try{
        if(encoding<0||encoding>1)throw std::invalid_argument("Unknown native lighting output encoding");
        auto& pass=static_cast<HostPathTracer*>(session(handle).app->HostRenderPass())->nativeLighting;
        if(encoding==1&&pass.constants.words[69]>15.65f)throw std::invalid_argument("Compact gain exceeds its representable range");
        pass.compactEncoding=encoding==1;
    }catch(const std::exception& failure){fail(e,failure);}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingActivateValidatedExtension(
    JNIEnv* e,jclass,jlong handle,jstring manifest,jstring expected,jint signals,jint applicationBytes){
    try{
        if(!manifest||!expected||signals<1||signals>15||applicationBytes<0||applicationBytes>16*1024*1024||applicationBytes%4)
            throw std::invalid_argument("Invalid extension activation contract");
        auto& host=*static_cast<HostPathTracer*>(session(handle).app->HostRenderPass());
        // The accepted next publication can still be queued in Java. PSO creation
        // depends on its declared capacity/layout, not on uploading those bytes now.
        auto extension=RtrNativeExtension::Load(std::filesystem::u8path(utf8(e,manifest)),uint32_t(signals),size_t(applicationBytes));
        if(extension.identity!=utf8(e,expected))throw std::invalid_argument("Extension artifact changed after validation");
        host.ActivateLightingExtension(extension);
    }catch(const std::exception& failure){fail(e,failure);}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingApplicationPatch(
    JNIEnv* e,jclass,jlong handle,jlong generation,jint offset,jbyteArray data){
    try{
        if(generation<=0||offset<0||!data)throw std::invalid_argument("Invalid application patch identity or data");
        const auto count=e->GetArrayLength(data);
        if(offset%4||count<1||count%4||count>16*1024*1024)throw std::invalid_argument("Application patch requires a nonempty 4-byte aligned payload");
        std::vector<uint8_t> bytes(size_t(count),uint8_t(0));e->GetByteArrayRegion(data,0,count,reinterpret_cast<jbyte*>(bytes.data()));if(e->ExceptionCheck())return;
        auto& pass=static_cast<HostPathTracer*>(session(handle).app->HostRenderPass())->nativeLighting;
        pass.PatchApplication(uint64_t(generation),size_t(offset),bytes);
    }catch(const std::exception& failure){fail(e,failure);}
}
extern "C" JNIEXPORT jlongArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingApplicationTelemetry(
    JNIEnv* e,jclass,jlong handle){
    try{
        const auto& pass=static_cast<HostPathTracer*>(session(handle).app->HostRenderPass())->nativeLighting;
        const std::array<jlong,4> words={jlong(pass.applicationData.size()),jlong(pass.applicationUploadedBytes),jlong(pass.applicationUploadCalls),jlong(pass.applicationPatches)};
        auto result=e->NewLongArray(4);if(result)e->SetLongArrayRegion(result,0,4,words.data());return result;
    }catch(const std::exception& failure){fail(e,failure);return nullptr;}
}
