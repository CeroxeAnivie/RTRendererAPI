package top.ceroxe.rt.renderer.backend.vulkan;

import java.lang.foreign.Arena;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.OpticalWallTimeline;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import top.ceroxe.rt.renderer.api.FrameValidationException;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.RendererException;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaBoundaryMediumAbi;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaMediumRuntime;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaMediumDensityAbi;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaOpticalMediumAbi;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaOpticalMediumSource;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaOpticalCausticAbi;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaSharedRayFrame;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.OpticalFrameTimings;
import top.ceroxe.rt.renderer.rt.diagnostics.OpticalDetailedProfile;
import top.ceroxe.rt.renderer.rt.acceleration.RtTlasBuildPlan;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.device.VulkanMemoryBudgetSnapshot;
import top.ceroxe.rt.renderer.rt.device.VulkanQueueHostSync;
import top.ceroxe.rt.renderer.rt.pipeline.GpuSceneRayTracingPipeline;

/** Blocking dielectric continuation. Original primary samples and all declared branches survive. */
final class CudaOpticalMediumManager implements AutoCloseable {
    private static final boolean RETAIN_EXTERNAL_OWNERSHIP=Boolean.parseBoolean(System.getProperty(
            "top.ceroxe.rt.medium.cuda.opticalRetainedOwnership","true"));
    static final LongAdder diagnosticQueries=new LongAdder(),diagnosticInterfaces=new LongAdder();
    static final LongAdder diagnosticAssetUploads=new LongAdder(),diagnosticUploadedBytes=new LongAdder();
    static final LongAdder diagnosticStorageRecreations=new LongAdder();
    private final ExecutorService nativeThread=Executors.newSingleThreadExecutor(task->{
        var thread=new Thread(task,"RTRenderer-native-optical");thread.setDaemon(true);return thread;
    });
    private final VulkanDeviceRuntime device;
    private final CudaMediumRuntime cuda;
    private final CudaMediumRuntime.BatchKernel reset;
    private final CudaMediumRuntime.BatchKernel[] steps;
    private final CudaMediumRuntime.HostMemory firstStatistics,secondStatistics;
    private CudaMediumRuntime.HostMemory firstShaderSamples,secondShaderSamples;
    private long shaderSampleOffset;
    private boolean shaderSampleFrame;
    private CudaSharedRayFrame storage;
    private RtGpuBuffer primary;
    private CudaOpticalPhotonPass photonPass;
    private FrameAssets uploadedAssets;
    private boolean closed;
    CudaOpticalMediumManager(VulkanDeviceRuntime device) {
        this.device=Objects.requireNonNull(device,"device");
        NativeState initialized;
        try{initialized=onNative(()->{var opened=CudaMediumRuntime.open(Integer.getInteger("top.ceroxe.rt.medium.cuda.deviceOrdinal",0),configuredResidentLimit());
            try{String source=CudaOpticalMediumSource.source();
                var variants=new CudaMediumRuntime.BatchKernel[9];
                for(int layout=0;layout<variants.length;layout++)variants[layout]=opened.compileBatchKernel(source,"native_optical_step_"+layout);
                return new NativeState(opened,opened.compileBatchKernel(source,"native_optical_reset"),variants,
                        opened.allocateHost(CudaOpticalMediumAbi.STATISTICS_BYTES),opened.allocateHost(CudaOpticalMediumAbi.STATISTICS_BYTES));}
            catch(RuntimeException|Error failure){try{opened.close();}catch(RuntimeException cleanup){failure.addSuppressed(cleanup);}throw failure;}
        });}catch(RuntimeException|Error failure){nativeThread.shutdown();throw failure;}
        cuda=initialized.cuda();reset=initialized.reset();steps=initialized.steps();
        firstStatistics=initialized.firstStatistics();secondStatistics=initialized.secondStatistics();
    }
    static FrameScene scene(RenderFrameRequest frame,int width,int height,MediumSceneIndex.NativeScene scene) {
        return scene(frame,width,height,scene,id->null,new CudaMediumDensityAbi.Cache());
    }
    static FrameScene scene(RenderFrameRequest frame,int width,int height,MediumSceneIndex.NativeScene scene,
            java.util.function.LongFunction<top.ceroxe.rt.renderer.api.TextureAsset> textures,CudaMediumDensityAbi.Cache densityCache) {
        return scene(frame,width,height,scene,textures,densityCache,false);
    }
    static FrameScene scene(RenderFrameRequest frame,int width,int height,MediumSceneIndex.NativeScene scene,
            java.util.function.LongFunction<top.ceroxe.rt.renderer.api.TextureAsset> textures,CudaMediumDensityAbi.Cache densityCache,
            boolean opaqueRadianceProof) {
        return scene(frame,width,height,scene,textures,densityCache,opaqueRadianceProof,0);
    }
    static FrameScene scene(RenderFrameRequest frame,int width,int height,MediumSceneIndex.NativeScene scene,
            java.util.function.LongFunction<top.ceroxe.rt.renderer.api.TextureAsset> textures,CudaMediumDensityAbi.Cache densityCache,
            boolean opaqueRadianceProof,int lightSlotUpperBound) {
        return scene(frame,width,height,scene,textures,densityCache,opaqueRadianceProof,lightSlotUpperBound,false);
    }
    static FrameScene scene(RenderFrameRequest frame,int width,int height,MediumSceneIndex.NativeScene scene,
            java.util.function.LongFunction<top.ceroxe.rt.renderer.api.TextureAsset> textures,CudaMediumDensityAbi.Cache densityCache,
            boolean opaqueRadianceProof,int lightSlotUpperBound,boolean surfaceContinuations) {
        return scene(frame,width,height,scene,textures,densityCache,opaqueRadianceProof,lightSlotUpperBound,surfaceContinuations,residentLimit());
    }
    static FrameScene scene(RenderFrameRequest frame,int width,int height,MediumSceneIndex.NativeScene scene,
            java.util.function.LongFunction<top.ceroxe.rt.renderer.api.TextureAsset> textures,CudaMediumDensityAbi.Cache densityCache,
            boolean opaqueRadianceProof,int lightSlotUpperBound,boolean surfaceContinuations,long residentBytes) {
        try{var boundaries=scene.boundaries().stream().map(entry->new CudaBoundaryMediumAbi.Boundary(
                entry.slot(),entry.boundary(),entry.objectToWorld())).toList();
            boolean connections=surfaceContinuations||CudaOpticalMediumAbi.requiresConnections(frame,boundaries);
            int samples=frame.antiAliasing().samplesPerPixel();long limit=residentBytes-(OpticalDetailedProfile.SHADERS?1048576:0);
            var base=CudaOpticalMediumAbi.plan(width,height,samples,boundaries.size(),limit);
            long minimum=base.sceneBytes()+(long)base.paths()*((base.externalPrimary()?0:CudaOpticalMediumAbi.HIT_BYTES)+2L*CudaOpticalMediumAbi.COLOR_BYTES)
                    +(long)samples*(base.stateBytes()+base.hitBytes()+CudaOpticalMediumAbi.COLOR_BYTES
                        +(base.externalPrimary()&&!base.directPrimary()?CudaOpticalMediumAbi.HIT_BYTES:0)
                        +(connections?CudaOpticalMediumAbi.CONNECTION_BYTES:0)+(CudaOpticalMediumAbi.useActiveQueues(boundaries.size(),connections)?8:0))
                    +CudaOpticalMediumAbi.ALLOCATION_ALLOWANCE;
            var photons=nativePhotonPlan(frame,boundaries,limit);
            long photonBytes=photons==null?0:photons.bytes()+base.sceneBytes()+CudaOpticalMediumAbi.ALLOCATION_ALLOWANCE;
            var density=densityCache.prepare(frame.environment().cameraMedium(),boundaries,textures,
                    (limit-minimum-photonBytes)/(photons==null?1:2));
            if(photons!=null)photonBytes+=density.bytes();
            var plan=CudaOpticalMediumAbi.plan(width,height,samples,boundaries.size(),density,limit-photonBytes,
                    base.externalPrimary(),base.directPrimary(),base.compactHits(),base.compactStates(),base.transposedHits(),base.transposedStates(),base.transposedPrimary(),connections);
            byte[] metadata=CudaOpticalMediumAbi.scenePrefix(plan,frame,boundaries,scene.containing(),density);
            if(boundaries.isEmpty() && opaqueRadianceProof && Boolean.parseBoolean(System.getProperty("top.ceroxe.rt.medium.cuda.opticalOpaqueRadiance","false"))) {
                var header=java.nio.ByteBuffer.wrap(metadata).order(ByteOrder.nativeOrder());
                header.putInt(188,header.getInt(188)|64);
            }
            return new FrameScene(frame,plan,metadata,boundaries,density,
                    CudaOpticalMediumAbi.maximumRounds(frame,lightSlotUpperBound,connections),photons);
        }catch(ArithmeticException|IllegalArgumentException invalid){throw unsupported(invalid.getMessage());}
    }
    private static CudaOpticalCausticAbi.Plan nativePhotonPlan(RenderFrameRequest frame,
            java.util.List<CudaBoundaryMediumAbi.Boundary> boundaries,long residentBytes) {
        if(!frame.caustics().enabled()||(boundaries.isEmpty()&&frame.environment().cameraMedium().densityField().isUniform()))return null;
        boolean scattering=!frame.environment().cameraMedium().scattering().isZero()
                ||boundaries.stream().anyMatch(b->!b.boundary().medium().scattering().isZero());
        int deposits=scattering?Math.min(frame.caustics().maxEvents(),frame.mediumTransport().scatteringBounces()+2):1;
        return CudaOpticalCausticAbi.planForMap(frame.caustics().photons(),deposits,residentBytes);
    }
    static long requiredBytes(FrameScene scene){return Math.addExact(scene.plan().bytes()+OpticalDetailedProfile.shaderBytes(scene.plan().tilePaths())+CudaOpticalMediumAbi.ALLOCATION_ALLOWANCE+CudaOpticalPhotonPass.bytes(scene),
            scene.plan().externalPrimary()?scene.plan().primaryBytes():0);}
    synchronized long requiredGrowthBytes(FrameScene scene){checkOpen();return onNative(()->{
        var plan=scene.plan();long growth=storage!=null&&storage.byteSize()>=plan.bytes()+OpticalDetailedProfile.shaderBytes(plan.tilePaths())?0:plan.bytes()+OpticalDetailedProfile.shaderBytes(plan.tilePaths())+CudaOpticalMediumAbi.ALLOCATION_ALLOWANCE;
        if(plan.externalPrimary()&&(primary==null||primary.sizeBytes()<plan.primaryBytes()))growth=Math.addExact(growth,plan.primaryBytes());
        if(scene.nativePhotons()!=null)growth=Math.addExact(growth,photonPass==null?CudaOpticalPhotonPass.bytes(scene):photonPass.growth(scene));
        return growth;
    });}
    synchronized CudaCameraMediumManager.Prepared prepare(FrameScene scene,GpuSceneRayTracingPipeline primaryProducer,
            GpuSceneRayTracingPipeline opticalTracer,int slot,RtTlasBuildPlan buildPlan,
            CausticPhotonPlan photonPlan,GpuSceneRayTracingPipeline photonPipeline,RtGpuBuffer photonBuffer){
        checkOpen();Objects.requireNonNull(opticalTracer,"opticalTracer");
        long started=OpticalFrameTimings.ENABLED?System.nanoTime():0L;
        try{return onNative(()->{
            try{return prepareOnNative(scene,opticalTracer,slot,buildPlan,photonPlan,photonPipeline,photonBuffer);}
            catch(RuntimeException|Error failure){uploadedAssets=null;
                if(OpticalDetailedProfile.ENABLED||OpticalFrameTimings.ENABLED)try{drain();OpticalDetailedProfile.discardDeferred();}
                    catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}
                throw failure;}
        });}finally{if(OpticalFrameTimings.ENABLED)OpticalFrameTimings.prepare.add(System.nanoTime()-started);}
    }
    private CudaCameraMediumManager.Prepared prepareOnNative(FrameScene scene,GpuSceneRayTracingPipeline tracer,int slot,
            RtTlasBuildPlan buildPlan,CausticPhotonPlan photonPlan,GpuSceneRayTracingPipeline photonPipeline,RtGpuBuffer photonBuffer){
        var frame=scene.frame();var plan=scene.plan();
        shaderSampleFrame=OpticalDetailedProfile.opticalProfileFrame(frame.sequence());
        boolean nativePhotons=scene.nativePhotons()!=null&&photonPlan!=null&&photonPlan.photons()>0;
        if(!nativePhotons&&photonPass!=null){photonPass.close();photonPass=null;}
        if(nativePhotons&&storage!=null&&storage.byteSize()>plan.bytes()+OpticalDetailedProfile.shaderBytes(plan.tilePaths())){
            drain();storage.close();storage=null;uploadedAssets=null;
        }
        ensureCapacity(plan);long address=storage.deviceAddress();
        if(nativePhotons) {
            if(photonPass==null)photonPass=new CudaOpticalPhotonPass(device,cuda);
            photonPass.prepare(scene,photonPlan,tracer,slot,photonBuffer,buildPlan);
        }
        var step=steps[plan.transposedStates()?6+(plan.transposedHits()?2:plan.compactHits()?1:0):plan.transposedHits()?4+(plan.compactStates()?1:0):((plan.compactHits()?1:0)|(plan.compactStates()?2:0))];
        long primaryAddress=plan.externalPrimary()?primary.deviceAddress():0;
        int opticalSamples=frame.mediumTransport().opticalSamples();
        int branches=frame.mediumTransport().firstInterfaceSampling()==top.ceroxe.rt.renderer.api.MediumTransport.FirstInterfaceSampling.DETERMINISTIC_SPLIT?2:1;
        boolean queued=Boolean.parseBoolean(System.getProperty("top.ceroxe.rt.medium.cuda.opticalQueuedRounds","true"))
                &&device.timelineSemaphoreEnabled()&&!OpticalFrameTimings.KERNEL_PHASES;
        boolean uploadAssets=!scene.assets.equals(uploadedAssets);
        try(Arena arena=Arena.ofConfined()){
            int firstUploadBytes=uploadAssets?plan.sceneBytes():CudaOpticalMediumAbi.HEADER_BYTES;
            var metadata=arena.allocate(firstUploadBytes,16);
            metadata.asByteBuffer().put(scene.metadata,0,uploadAssets?scene.metadata.length:CudaOpticalMediumAbi.HEADER_BYTES);
            if(!device.traceRaysIndirectEnabled())metadata.set(ValueLayout.JAVA_INT,188,metadata.get(ValueLayout.JAVA_INT,188)&~65536);
            if(uploadAssets)scene.assets.density.write(metadata.asByteBuffer().order(ByteOrder.nativeOrder()),scene.metadata.length);
            if(shaderSampleFrame){
                if(OpticalFrameTimings.KERNEL_PHASES)throw new IllegalStateException("shader and CUDA clock sampling must run separately");
                metadata.set(ValueLayout.JAVA_LONG,192,address+plan.bytes());
            }
            var shaderHost=shaderSampleFrame&&!queued?arena.allocate(OpticalDetailedProfile.shaderBytes(plan.tilePaths()),16):null;
            var counters=arena.allocate(CudaOpticalMediumAbi.STATISTICS_BYTES,16);
            var phaseCycles=OpticalFrameTimings.KERNEL_PHASES?arena.allocate(64,16):null;boolean buildPending=buildPlan!=null&&!nativePhotons;
            boolean photonsPending=photonPlan!=null&&photonPlan.photons()>0&&!nativePhotons;
            for(int first=0;first<plan.pixels();){
                int tileFirst=first,pixels=Math.min(plan.tilePixels(),plan.pixels()-first),paths=pixels*plan.samples();
                for(int opticalSample=0;opticalSample<opticalSamples;opticalSample++)for(int branch=0;branch<branches;branch++){
                    CudaOpticalMediumAbi.updateTile(metadata.asByteBuffer().order(ByteOrder.nativeOrder()),plan,first,pixels,opticalSample,branch);
                    boolean recordBuild=buildPending,recordPhotons=photonsPending,uploadScene=first==0&&opticalSample==0&&branch==0;
                    int[] statistics={paths,0,0,0};
                    int sampleIndex=opticalSample,branchIndex=branch;int[] roundIndex=OpticalFrameTimings.KERNEL_PHASES||OpticalDetailedProfile.ENABLED?new int[]{0}:null;
                    java.util.function.Consumer<CudaMediumRuntime.Slice> resolve=input->{
                        long resolveStarted=OpticalFrameTimings.ENABLED?System.nanoTime():0L;
                        int queries=statistics[0],dispatched=plan.activeQueues()?Math.max(1,queries):paths;
                        cuda.launchBlocking(reset,input,1,CudaOpticalMediumAbi.HEADER_BYTES);
                        cuda.launchBlocking(step,input,dispatched,1);
                        cuda.download(input.slice(CudaOpticalMediumAbi.STATISTICS_OFFSET,CudaOpticalMediumAbi.STATISTICS_BYTES),counters);
                        for(int j=0;j<4;j++)statistics[j]=counters.get(ValueLayout.JAVA_INT,j*4L);
                        if(phaseCycles!=null){
                            cuda.download(input.slice(192,64),phaseCycles);
                            long[] phases=new long[8];
                            for(int j=0;j<8;j++){phases[j]=phaseCycles.get(ValueLayout.JAVA_LONG,j*8L);OpticalFrameTimings.kernelPhases[j].add(phases[j]);}
                            OpticalFrameTimings.recordRound(sampleIndex,branchIndex,roundIndex[0],queries,statistics,phases);
                        }
                        if(OpticalDetailedProfile.ENABLED){
                            OpticalDetailedProfile.add("cuda/dispatchedPaths",dispatched);OpticalDetailedProfile.add("cuda/activeInputPaths",queries);
                            OpticalDetailedProfile.add("cuda/continuedPaths",statistics[0]);OpticalDetailedProfile.add("cuda/errors",statistics[1]);
                            OpticalDetailedProfile.add("cuda/interfaces",statistics[2]);OpticalDetailedProfile.add("cuda/completedPaths",statistics[3]);
                        }
                        if(shaderHost!=null){long diagnosticStarted=OpticalDetailedProfile.start();
                            cuda.download(input.slice(plan.bytes(),OpticalDetailedProfile.shaderBytes(paths)),shaderHost.asSlice(0,OpticalDetailedProfile.shaderBytes(paths)));
                            OpticalDetailedProfile.shaderSamples(shaderHost,paths);
                            OpticalDetailedProfile.elapsed("diagnosticReadbackAndAggregationNanos",diagnosticStarted);
                        }
                        diagnosticQueries.add(queries);diagnosticInterfaces.add(statistics[2]);
                        if(OpticalFrameTimings.ENABLED)OpticalFrameTimings.resolve.add(System.nanoTime()-resolveStarted);
                    };
                    java.util.function.Consumer<org.lwjgl.vulkan.VkCommandBuffer> continuation=command->{
                        if(statistics[1]==0&&statistics[0]>0)try(MemoryStack stack=MemoryStack.stackPush()){
                            recordOpticalTrace(tracer,command,stack,slot,address,primaryAddress,tileFirst,pixels,false,plan,statistics[0]);
                        }
                    };
                    if(OpticalDetailedProfile.ENABLED)OpticalDetailedProfile.scope(tileFirst,sampleIndex,branchIndex,0);
                    java.util.function.Consumer<org.lwjgl.vulkan.VkCommandBuffer> producer=command->{try(MemoryStack stack=MemoryStack.stackPush()){
                        var detail=OpticalDetailedProfile.ENABLED?device.frameCommands().acquireGpuTimestampCapture("opticalProducerDetailed",
                                new String[]{"start","build","primaryDependency","metadata","trace"}):null;
                        OpticalDetailedProfile.defer("producerGpu",detail);
                        if(detail!=null)detail.begin(command,VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
                        if(recordBuild)buildPlan.record(command,stack);
                        if(recordPhotons) {
                            if(photonPipeline==null||photonBuffer==null)
                                throw new IllegalArgumentException("native optical caustics require a photon pipeline and map buffer");
                            var photons=OpticalFrameTimings.ENABLED?device.frameCommands().acquireGpuTimestampCapture("opticalVulkanPhotons",new String[]{"start","end"}):null;
                            OpticalDetailedProfile.defer("opticalPhoton.vulkanBuild",photons);
                            if(photons!=null)photons.begin(command,VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
                            photonPlan.record(command,stack,photonBuffer,photonPipeline,slot);
                            if(photons!=null)photons.write(command,VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
                        }
                        if(detail!=null)detail.write(command,VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
                        if(uploadScene&&plan.externalPrimary()) {
                            var primaryBarrier=VkBufferMemoryBarrier.calloc(1,stack).sType$Default()
                                    .srcAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT|VK10.VK_ACCESS_SHADER_WRITE_BIT)
                                    .dstAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                                    .buffer(primary.buffer()).offset(0).size(primary.sizeBytes());
                            VK10.vkCmdPipelineBarrier(command,KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,0,null,primaryBarrier,null);
                        }
                        if(detail!=null)detail.write(command,VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
                        int bytes=uploadScene?firstUploadBytes:CudaOpticalMediumAbi.HEADER_BYTES;
                        uploadMetadata(command,stack,metadata.asByteBuffer(),bytes);
                        if(detail!=null)detail.write(command,VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
                        recordOpticalTrace(tracer,command,stack,slot,address,primaryAddress,tileFirst,pixels,true,plan,paths);
                        if(detail!=null)detail.write(command,VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
                    }};
                    java.util.function.Consumer<org.lwjgl.vulkan.VkCommandBuffer> queuedContinuation=command->{try(MemoryStack stack=MemoryStack.stackPush()){
                        recordOpticalTrace(tracer,command,stack,slot,address,primaryAddress,tileFirst,pixels,false,plan,statistics[0]);
                    }};
                    if(queued)exchangePair(producer,queuedContinuation,step,paths,statistics,plan.activeQueues());
                    else storage.produceThenExchangeBlocking(producer,resolve,continuation);
                    if(roundIndex!=null)roundIndex[0]++;
                    buildPending=false;photonsPending=false;int rounds=queued?2:1;
                    while(statistics[1]==0&&statistics[0]>0){
                        int limit=scene.maximumRounds;
                        if(rounds>=limit)
                            throw new RendererException("native optical continuation exceeded the public event and scattering budgets");
                        if(OpticalDetailedProfile.ENABLED)OpticalDetailedProfile.scope(tileFirst,sampleIndex,branchIndex,roundIndex[0]);
                        if(queued&&rounds+2<=limit){
                            exchangePair(queuedContinuation,queuedContinuation,step,paths,statistics,plan.activeQueues());rounds+=2;
                        }else{if(queued)storage.produceThenExchangeBlocking(queuedContinuation,resolve,command->{});
                            else storage.exchangeBlocking(resolve,continuation);rounds++;}
                        if(roundIndex!=null)roundIndex[0]++;
                    }
                    if(statistics[1]!=0)throw rejectedFrame(plan,paths,statistics[1],frame.sequence());
                }first+=pixels;
            }
        }
        // Intermediate pairs leave the completed allocation external-owned. Acquire only
        // once before the final Vulkan consumer can see this fully validated frame.
        storage.acquireForVulkanBlocking();
        if(uploadAssets){uploadedAssets=scene.assets;diagnosticAssetUploads.increment();}
        // Publish this proof only after every declared path/branch completed without rejection.
        // The consumer retains its geometry-dependent feature and outline fallback.
        return new CudaCameraMediumManager.Prepared(plan.externalPrimary()?primaryAddress:address+plan.primaryOffset(),(address+plan.transmittanceOffset())|(plan.transposedPrimary()?8L:0L)
                |(CudaOpticalMediumAbi.resolvedPublication()?CudaOpticalMediumAbi.RESOLVED_RADIANCE_FLAG:0L),
                address+plan.emissionOffset(),plan.pixels(),plan.samples(),plan.externalPrimary());
    }
    private RendererException rejectedFrame(CudaOpticalMediumAbi.Plan plan,int paths,int rejected,long sequence) {
        String reason="native optical continuation rejected "+rejected+" paths; frameSequence="+sequence;
        try {return new RendererException(reason+"; "+rejectionDetails(plan,paths)+"; incomplete radiance was not published");}
        catch(RuntimeException|Error diagnosticFailure) {
            var failure=new RendererException(reason+"; diagnostic readback failed; incomplete radiance was not published");
            failure.addSuppressed(diagnosticFailure);return failure;
        }
    }
    /** Failure-only: scan bounded blocks and fetch at most four complete records. */
    private String rejectionDetails(CudaOpticalMediumAbi.Plan plan,int paths) {
        try(Arena arena=Arena.ofConfined()) {
            int chunkPaths=Math.min(paths,4096),scanStride=plan.transposedStates()?4:plan.stateBytes();
            var scan=arena.allocate(Math.multiplyExact((long)chunkPaths,scanStride),16);
            var states=arena.allocate(plan.stateBytes(),16);
            var hits=arena.allocate(plan.hitBytes(),16);
            var connection=plan.connectionTransport()?arena.allocate(CudaOpticalMediumAbi.CONNECTION_BYTES,16):null;
            var histogram=new java.util.TreeMap<Integer,Integer>();var examples=new java.util.ArrayList<String>();
            int[] samplePaths=new int[4],sampleErrors=new int[4],sampleCount={0};
            storage.exchangeBlocking(input->{
                for(int first=0;first<paths;first+=chunkPaths) {
                    int count=Math.min(chunkPaths,paths-first);
                    long offset=plan.stateOffset()+(plan.transposedStates()?(9L*paths+first)*4:first*(long)plan.stateBytes());
                    cuda.download(input.slice(offset,(long)count*scanStride),scan.asSlice(0,(long)count*scanStride));
                    for(int j=0;j<count;j++) {
                        int error=scan.get(ValueLayout.JAVA_INT,(long)j*scanStride+(plan.transposedStates()?0:36));
                        if(error==0)continue;histogram.merge(error,1,Integer::sum);
                        if(sampleCount[0]<4) {int index=sampleCount[0]++;samplePaths[index]=first+j;sampleErrors[index]=error;}
                    }
                }
                for(int example=0;example<sampleCount[0];example++) {
                    int i=samplePaths[example],error=sampleErrors[example];
                    downloadDiagnosticRecord(input,plan.stateOffset(),paths,i,plan.stateBytes(),4,plan.transposedStates(),states);
                    downloadDiagnosticRecord(input,plan.hitOffset(),paths,i,plan.hitBytes(),16,plan.transposedHits(),hits);
                    if(connection!=null)cuda.download(input.slice(plan.connectionOffset()+(long)i*CudaOpticalMediumAbi.CONNECTION_BYTES,
                            CudaOpticalMediumAbi.CONNECTION_BYTES),connection);
                    int hitWord=plan.compactHits()?16:44;
                    long hitAt=hitWord*4L;
                    String sample="path="+i+",error="+error+",hitBoundary="+hits.get(ValueLayout.JAVA_INT,hitAt);
                    long stateAt=0;
                    long hitStart=0;
                    sample+=",rayOrigin=["+states.get(ValueLayout.JAVA_FLOAT,stateAt)+","
                            +states.get(ValueLayout.JAVA_FLOAT,stateAt+4)+","
                            +states.get(ValueLayout.JAVA_FLOAT,stateAt+8)+"]"
                            +",hitDistance="+hits.get(ValueLayout.JAVA_FLOAT,hitStart+12);
                    java.util.function.IntToLongFunction stateWord=word->word*4L;
                    java.util.function.IntToLongFunction hitField=word->word*4L;
                    sample+=",rayDirection=["+states.get(ValueLayout.JAVA_FLOAT,stateWord.applyAsLong(4))+","
                            +states.get(ValueLayout.JAVA_FLOAT,stateWord.applyAsLong(5))+","
                            +states.get(ValueLayout.JAVA_FLOAT,stateWord.applyAsLong(6))+"]"
                            +",hitPoint=["+hits.get(ValueLayout.JAVA_FLOAT,hitField.applyAsLong(0))+","
                            +hits.get(ValueLayout.JAVA_FLOAT,hitField.applyAsLong(1))+","
                            +hits.get(ValueLayout.JAVA_FLOAT,hitField.applyAsLong(2))+"]"
                            +",hitNormal=["+hits.get(ValueLayout.JAVA_FLOAT,hitField.applyAsLong(plan.compactHits()?4:12))+","
                            +hits.get(ValueLayout.JAVA_FLOAT,hitField.applyAsLong(plan.compactHits()?5:13))+","
                            +hits.get(ValueLayout.JAVA_FLOAT,hitField.applyAsLong(plan.compactHits()?6:14))+"]"
                            +",hitSide="+hits.get(ValueLayout.JAVA_INT,hitField.applyAsLong(plan.compactHits()?15:29))
                            +",originOffset="+states.get(ValueLayout.JAVA_FLOAT,stateWord.applyAsLong(3))
                            +",surfaceEvents="+states.get(ValueLayout.JAVA_INT,stateWord.applyAsLong(20))
                            +",scatterEvents="+states.get(ValueLayout.JAVA_INT,stateWord.applyAsLong(23));
                    if(!plan.compactStates()) {long base=0;
                        int size=states.get(ValueLayout.JAVA_INT,base+244);var stack=new java.util.ArrayList<Integer>();
                        for(int k=0;k<Math.max(0,Math.min(size,32));k++)stack.add(states.get(ValueLayout.JAVA_INT,base+116+k*4L));
                        sample+=",mainVolume="+states.get(ValueLayout.JAVA_INT,base+112)+",mainStackSize="+size+",mainStack="+stack;
                    }
                    if(connection!=null){long base=0;
                        sample+=",connectionOrdinal="+connection.get(ValueLayout.JAVA_INT,base+80)
                                +",connectionEvents="+connection.get(ValueLayout.JAVA_INT,base+88)
                                +",connectionVolume="+connection.get(ValueLayout.JAVA_INT,base+96)
                                +",connectionStackSize="+connection.get(ValueLayout.JAVA_INT,base+228)
                                +",connectionOrigin=["+connection.get(ValueLayout.JAVA_FLOAT,base)+","
                                +connection.get(ValueLayout.JAVA_FLOAT,base+4)+","+connection.get(ValueLayout.JAVA_FLOAT,base+8)+"]"
                                +",connectionDirection=["+connection.get(ValueLayout.JAVA_FLOAT,base+16)+","
                                +connection.get(ValueLayout.JAVA_FLOAT,base+20)+","+connection.get(ValueLayout.JAVA_FLOAT,base+24)+"]";}
                    examples.add(sample);
                }
            },command->{});
            return "errorCodes="+histogram+",examples="+examples;
        }
    }
    private void downloadDiagnosticRecord(CudaMediumRuntime.Slice input,long base,int paths,int item,
            int bytes,int laneBytes,boolean transposed,java.lang.foreign.MemorySegment target) {
        if(!transposed) {cuda.download(input.slice(base+(long)item*bytes,bytes),target);return;}
        for(int lane=0;lane<bytes/laneBytes;lane++)
            cuda.download(input.slice(base+((long)lane*paths+item)*laneBytes,laneBytes),
                    target.asSlice((long)lane*laneBytes,laneBytes));
    }
    private void exchangePair(java.util.function.Consumer<org.lwjgl.vulkan.VkCommandBuffer> producer,
            java.util.function.Consumer<org.lwjgl.vulkan.VkCommandBuffer> continuation,
            CudaMediumRuntime.BatchKernel step,int paths,int[] statistics,boolean activeQueues) {
        // One input can publish at most one successor. The completed previous
        // pair therefore bounds both steps without another host readback.
        int queries=statistics[0],dispatched=activeQueues?Math.max(1,queries):paths;
        storage.produceThenExchangePairBlocking(producer,input->{
            cuda.launchBlocking(reset,input,1,CudaOpticalMediumAbi.HEADER_BYTES);
            cuda.launchBlocking(step,input,dispatched,1);
            cuda.downloadAsync(input.slice(CudaOpticalMediumAbi.STATISTICS_OFFSET,CudaOpticalMediumAbi.STATISTICS_BYTES),firstStatistics);
            if(shaderSampleFrame)cuda.downloadAsync(input.slice(shaderSampleOffset,firstShaderSamples.byteSize()),firstShaderSamples);
        },continuation,input->{
            cuda.launchBlocking(reset,input,1,CudaOpticalMediumAbi.HEADER_BYTES);
            cuda.launchBlocking(step,input,dispatched,1);
            cuda.downloadAsync(input.slice(CudaOpticalMediumAbi.STATISTICS_OFFSET,CudaOpticalMediumAbi.STATISTICS_BYTES),secondStatistics);
            if(shaderSampleFrame)cuda.downloadAsync(input.slice(shaderSampleOffset,secondShaderSamples.byteSize()),secondShaderSamples);
        },RETAIN_EXTERNAL_OWNERSHIP);
        if(shaderSampleFrame){
            long diagnosticStarted=OpticalDetailedProfile.start();
            OpticalDetailedProfile.shaderSamples(firstShaderSamples.segment(),paths);
            OpticalDetailedProfile.shaderSamples(secondShaderSamples.segment(),paths);
            OpticalDetailedProfile.elapsed("diagnosticReadbackAndAggregationNanos",diagnosticStarted);
        }
        if(OpticalDetailedProfile.ENABLED) {
            recordPairStep("cuda/pairStep0",queries,firstStatistics);
            recordPairStep("cuda/pairStep1",firstStatistics.getInt(0),secondStatistics);
            OpticalDetailedProfile.add("cuda/dispatchedPathSlots",Math.multiplyExact((long)dispatched,2L));
            OpticalDetailedProfile.add("vulkan/previousCapacityPathSlots",Math.multiplyExact((long)dispatched,2L));
            OpticalDetailedProfile.add("vulkan/queuedPathSlots",(long)queries+firstStatistics.getInt(0));
            if(device.traceRaysIndirectEnabled()&&Boolean.parseBoolean(System.getProperty(CudaOpticalMediumAbi.SPLIT_TASK_QUEUES_PROPERTY,"true")))
                OpticalDetailedProfile.add("vulkan/eliminatedInactivePathSlots",Math.multiplyExact((long)dispatched,2L)-queries-firstStatistics.getInt(0));
        }
        diagnosticQueries.add((long)queries+firstStatistics.getInt(0));
        diagnosticInterfaces.add((long)firstStatistics.getInt(8)+secondStatistics.getInt(8));
        statistics[0]=secondStatistics.getInt(0);
        for(int j=1;j<4;j++)statistics[j]=Math.addExact(firstStatistics.getInt(j*4L),secondStatistics.getInt(j*4L));
    }
    private static void recordPairStep(String scope,int activeInput,CudaMediumRuntime.HostMemory statistics){
        OpticalDetailedProfile.add(scope+"/activeInputPaths",activeInput);
        OpticalDetailedProfile.add(scope+"/activeOutputPaths",statistics.getInt(0));
        OpticalDetailedProfile.add(scope+"/errors",statistics.getInt(4));
        OpticalDetailedProfile.add(scope+"/interfaces",statistics.getInt(8));
        OpticalDetailedProfile.add(scope+"/completedPaths",statistics.getInt(12));
    }
    private void recordOpticalTrace(GpuSceneRayTracingPipeline tracer,org.lwjgl.vulkan.VkCommandBuffer command,MemoryStack stack,
            int slot,long address,long primaryAddress,int first,int pixels,boolean initialize,CudaOpticalMediumAbi.Plan plan,int activeBound){
        if(shaderSampleFrame){
            VK10.vkCmdFillBuffer(command,storage.buffer(),plan.bytes(),OpticalDetailedProfile.shaderBytes(pixels*plan.samples()),0);
            var dependency=VkBufferMemoryBarrier.calloc(1,stack).sType$Default().srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT|VK10.VK_ACCESS_SHADER_WRITE_BIT)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .buffer(storage.buffer()).offset(plan.bytes()).size(OpticalDetailedProfile.shaderBytes(pixels*plan.samples()));
            VK10.vkCmdPipelineBarrier(command,VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,0,null,dependency,null);
        }
        var timing=OpticalFrameTimings.ENABLED?device.frameCommands().acquireGpuTimestampCapture("opticalCameraTrace",new String[]{"start","end"}):null;
        OpticalDetailedProfile.defer(initialize?"optical.cameraInitialTrace":"optical.cameraContinuationTrace",timing);
        if(timing!=null)timing.begin(command,KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR);
        int dispatchedPixels=initialize||!plan.activeQueues()?pixels:Math.min(pixels,Math.max(1,Math.ceilDiv(activeBound,plan.samples())));
        if(!initialize&&plan.activeQueues()&&device.traceRaysIndirectEnabled()
                &&Boolean.parseBoolean(System.getProperty(CudaOpticalMediumAbi.SPLIT_TASK_QUEUES_PROPERTY,"true")))
            tracer.recordNativeOpticalTaskQueues(command,stack,slot,address,primaryAddress,first,pixels*plan.samples(),address+plan.queueControlOffset());
        else tracer.recordNativeOpticalTrace(command,stack,slot,address,primaryAddress,first,dispatchedPixels,initialize);
        if(timing!=null)timing.write(command,KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR);
    }
    private void uploadMetadata(org.lwjgl.vulkan.VkCommandBuffer command,MemoryStack stack,java.nio.ByteBuffer source,int bytes){
        for(int offset=0;offset<bytes;){int length=Math.min(65536,bytes-offset);
            var chunk=source.duplicate();chunk.position(offset).limit(offset+length);
            VK10.vkCmdUpdateBuffer(command,storage.buffer(),offset,chunk.slice());offset+=length;}
        var barrier=VkBufferMemoryBarrier.calloc(1,stack).sType$Default().srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT|VK10.VK_ACCESS_SHADER_WRITE_BIT)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .buffer(storage.buffer()).offset(0).size(bytes);
        VK10.vkCmdPipelineBarrier(command,VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,0,null,barrier,null);
        diagnosticUploadedBytes.add(bytes);
    }
    private void ensureCapacity(CudaOpticalMediumAbi.Plan plan){
        shaderSampleOffset=plan.bytes();
        int shaderBytes=OpticalDetailedProfile.shaderBytes(plan.tilePaths());
        if(shaderBytes>0&&(firstShaderSamples==null||firstShaderSamples.byteSize()!=shaderBytes)) {
            if(firstShaderSamples!=null)firstShaderSamples.close();
            if(secondShaderSamples!=null)secondShaderSamples.close();
            firstShaderSamples=cuda.allocateHost(shaderBytes);secondShaderSamples=cuda.allocateHost(shaderBytes);
        }
        long sharedBytes=plan.bytes()+OpticalDetailedProfile.shaderBytes(plan.tilePaths());
        if(storage==null||storage.byteSize()<sharedBytes) {
            if(sharedBytes+CudaOpticalMediumAbi.ALLOCATION_ALLOWANCE>cuda.maxResidentBytes())
                throw unsupported("complete native optical frame exceeds the CUDA resident cap");
            uploadedAssets=null;
            if(storage!=null){drain();storage.close();storage=null;}
            storage=CudaSharedRayFrame.storage(device,cuda,sharedBytes,cuda.maxResidentBytes()-cuda.residentBytes());
            diagnosticStorageRecreations.increment();
        }
        if(plan.externalPrimary()) {
            if(primary==null||primary.sizeBytes()<plan.primaryBytes()) {
                if(primary!=null){drain();primary.close();primary=null;}
                primary=RtGpuBuffer.createDeviceAddressBuffer(device.device(),device.allocator(),plan.primaryBytes(),VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            }
        }else if(primary!=null){drain();primary.close();primary=null;}
    }
    private void drain(){int result=VulkanQueueHostSync.waitIdle(device.frameQueue());if(result!=VK10.VK_SUCCESS)
        throw new IllegalStateException("drain native optical consumers: VkResult="+result);}
    private void checkOpen(){if(closed)throw new IllegalStateException("native optical manager is closed");}
    private static long configuredResidentLimit(){return Math.min(CudaOpticalMediumAbi.MAXIMUM_RESIDENT_BYTES,
            Long.getLong("top.ceroxe.rt.medium.cuda.maxResidentBytes",CudaOpticalMediumAbi.MAXIMUM_RESIDENT_BYTES));}
    private static long residentLimit(){return Math.min(configuredResidentLimit(),
            Long.getLong("top.ceroxe.rt.medium.cuda.maxResidentBytes",CudaOpticalMediumAbi.DEFAULT_RESIDENT_BYTES));}
    /** Bound scratch by the device budget instead of serializing every GPU behind a 512 MiB ceiling.
     * Actual growth still goes through VulkanMemoryBudgetPolicy, including primary/photon buffers.
     * Use the stable heap budget, not free bytes after our own allocation, to avoid per-frame retile churn.
     */
    static long residentLimit(VulkanMemoryBudgetSnapshot budget){
        long available=budget!=null&&budget.driverBudgetAvailable()&&budget.deviceLocalBudgetBytes()>0
                ?budget.deviceLocalBudgetBytes()/4:CudaOpticalMediumAbi.DEFAULT_RESIDENT_BYTES;
        return Math.min(configuredResidentLimit(),available);
    }
    private <T>T onNative(Callable<T> operation){
        int wallTask=OpticalWallTimeline.enter(OpticalWallTimeline.Stage.OPTICAL_MEDIUM_NATIVE);
        try {var submitted=nativeThread.submit(OpticalWallTimeline.awaited(operation));boolean interrupted=false;
        try{for(;;)try{return submitted.get();}catch(InterruptedException ignored){interrupted=true;}
            catch(ExecutionException failure){if(failure.getCause() instanceof RuntimeException runtime)throw runtime;
                if(failure.getCause() instanceof Error error)throw error;throw new IllegalStateException("native optical operation failed",failure.getCause());}
        }finally{if(interrupted)Thread.currentThread().interrupt();}
        }finally{OpticalWallTimeline.leave(wallTask);}
    }
    private static FrameValidationException unsupported(String message){return new FrameValidationException(
            FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,"native optical candidate: "+message);}
    @Override public synchronized void close(){if(closed)return;try{onNative(()->{Throwable failure=null;
        try{drain();}catch(RuntimeException|Error exception){failure=exception;}
        if(storage!=null)try{storage.close();storage=null;}catch(RuntimeException|Error exception){if(failure==null)failure=exception;else failure.addSuppressed(exception);}
        if(primary!=null)try{primary.close();primary=null;}catch(RuntimeException|Error exception){if(failure==null)failure=exception;else failure.addSuppressed(exception);}
        if(photonPass!=null)try{photonPass.close();photonPass=null;}catch(RuntimeException|Error exception){if(failure==null)failure=exception;else failure.addSuppressed(exception);}
        try{cuda.close();}catch(RuntimeException|Error exception){if(failure==null)failure=exception;else failure.addSuppressed(exception);}
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;return null;
    });}finally{uploadedAssets=null;closed=true;nativeThread.shutdown();}}
    private record NativeState(CudaMediumRuntime cuda,CudaMediumRuntime.BatchKernel reset,CudaMediumRuntime.BatchKernel[] steps,
            CudaMediumRuntime.HostMemory firstStatistics,CudaMediumRuntime.HostMemory secondStatistics){}
    private record FrameAssets(java.util.List<CudaBoundaryMediumAbi.Boundary> boundaries,CudaMediumDensityAbi.Scene density,int sceneBytes){}
    static final class FrameScene {
        private final RenderFrameRequest frame;private final CudaOpticalMediumAbi.Plan plan;private final byte[] metadata;private final FrameAssets assets;private final int maximumRounds;
        private final CudaOpticalCausticAbi.Plan nativePhotons;
        FrameScene(RenderFrameRequest frame,CudaOpticalMediumAbi.Plan plan,byte[] metadata,
                java.util.List<CudaBoundaryMediumAbi.Boundary> boundaries,CudaMediumDensityAbi.Scene density){
            this(frame,plan,metadata,boundaries,density,CudaOpticalMediumAbi.maximumRounds(frame,0,plan.connectionTransport()));
        }
        FrameScene(RenderFrameRequest frame,CudaOpticalMediumAbi.Plan plan,byte[] metadata,
                java.util.List<CudaBoundaryMediumAbi.Boundary> boundaries,CudaMediumDensityAbi.Scene density,int maximumRounds){
            this(frame,plan,metadata,boundaries,density,maximumRounds,nativePhotonPlan(frame,boundaries,residentLimit()));
        }
        private FrameScene(RenderFrameRequest frame,CudaOpticalMediumAbi.Plan plan,byte[] metadata,
                java.util.List<CudaBoundaryMediumAbi.Boundary> boundaries,CudaMediumDensityAbi.Scene density,int maximumRounds,
                CudaOpticalCausticAbi.Plan nativePhotons){
            this.frame=frame;this.plan=plan;this.metadata=metadata.clone();
            this.assets=new FrameAssets(java.util.List.copyOf(boundaries),density,plan.sceneBytes());
            this.maximumRounds=maximumRounds;
            this.nativePhotons=nativePhotons;
        }
        RenderFrameRequest frame(){return frame;}CudaOpticalMediumAbi.Plan plan(){return plan;}
        CudaOpticalCausticAbi.Plan nativePhotons(){return nativePhotons;}
        void writeScene(java.nio.ByteBuffer target){target.put(metadata);assets.density.write(target,metadata.length);}
        boolean sameAssets(FrameScene other){return other!=null&&assets.equals(other.assets);}
    }
}
