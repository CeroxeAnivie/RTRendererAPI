// Include after session(), HostPathTracer and fail() are defined in rtxpt_jni.cpp.
// Explicit diagnostic: exercises the production encoder over 100001 float32
// inputs, with readback/wait confined to this test entry point.
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
