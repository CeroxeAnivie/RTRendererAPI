package top.ceroxe.rt.renderer.backend.vulkan;

import java.lang.foreign.Arena;
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
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaSharedRayFrame;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.OpticalFrameTimings;
import top.ceroxe.rt.renderer.rt.acceleration.RtTlasBuildPlan;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.device.VulkanQueueHostSync;
import top.ceroxe.rt.renderer.rt.pipeline.GpuSceneRayTracingPipeline;

/** Blocking dielectric continuation. Original primary samples and all declared branches survive. */
final class CudaOpticalMediumManager implements AutoCloseable {
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
    private CudaSharedRayFrame storage;
    private RtGpuBuffer primary;
    private FrameAssets uploadedAssets;
    private boolean closed;
    CudaOpticalMediumManager(VulkanDeviceRuntime device) {
        this.device=Objects.requireNonNull(device,"device");
        NativeState initialized;
        try{initialized=onNative(()->{var opened=CudaMediumRuntime.open(Integer.getInteger("top.ceroxe.rt.medium.cuda.deviceOrdinal",0));
            try{String source=CudaOpticalMediumSource.source();
                var variants=new CudaMediumRuntime.BatchKernel[4];
                for(int layout=0;layout<variants.length;layout++)variants[layout]=opened.compileBatchKernel(source,"native_optical_step_"+layout);
                return new NativeState(opened,opened.compileBatchKernel(source,"native_optical_reset"),variants);}
            catch(RuntimeException|Error failure){try{opened.close();}catch(RuntimeException cleanup){failure.addSuppressed(cleanup);}throw failure;}
        });}catch(RuntimeException|Error failure){nativeThread.shutdown();throw failure;}
        cuda=initialized.cuda();reset=initialized.reset();steps=initialized.steps();
    }
    static FrameScene scene(RenderFrameRequest frame,int width,int height,MediumSceneIndex.NativeScene scene) {
        return scene(frame,width,height,scene,id->null,new CudaMediumDensityAbi.Cache());
    }
    static FrameScene scene(RenderFrameRequest frame,int width,int height,MediumSceneIndex.NativeScene scene,
            java.util.function.LongFunction<top.ceroxe.rt.renderer.api.TextureAsset> textures,CudaMediumDensityAbi.Cache densityCache) {
        try{var boundaries=scene.boundaries().stream().map(entry->new CudaBoundaryMediumAbi.Boundary(
                entry.slot(),entry.boundary(),entry.objectToWorld())).toList();
            int samples=frame.antiAliasing().samplesPerPixel();long limit=residentLimit();
            var base=CudaOpticalMediumAbi.plan(width,height,samples,boundaries.size(),limit);
            long minimum=base.sceneBytes()+(long)base.paths()*((base.externalPrimary()?0:CudaOpticalMediumAbi.HIT_BYTES)+2L*CudaOpticalMediumAbi.COLOR_BYTES)
                    +(long)samples*(base.stateBytes()+base.hitBytes()+CudaOpticalMediumAbi.COLOR_BYTES
                        +(base.externalPrimary()&&!base.directPrimary()?CudaOpticalMediumAbi.HIT_BYTES:0))
                    +CudaOpticalMediumAbi.ALLOCATION_ALLOWANCE;
            var density=densityCache.prepare(frame.environment().cameraMedium(),boundaries,textures,limit-minimum);
            var plan=CudaOpticalMediumAbi.plan(width,height,samples,boundaries.size(),density,limit,
                    base.externalPrimary(),base.directPrimary(),base.compactHits(),base.compactStates());
            byte[] metadata=CudaOpticalMediumAbi.scenePrefix(plan,frame,boundaries,scene.containing(),density);
            return new FrameScene(frame,plan,metadata,boundaries,density);
        }catch(ArithmeticException|IllegalArgumentException invalid){throw unsupported(invalid.getMessage());}
    }
    static long requiredBytes(FrameScene scene){return Math.addExact(scene.plan().bytes()+CudaOpticalMediumAbi.ALLOCATION_ALLOWANCE,
            scene.plan().externalPrimary()?scene.plan().primaryBytes():0);}
    synchronized long requiredGrowthBytes(FrameScene scene){checkOpen();return onNative(()->{
        var plan=scene.plan();long growth=storage!=null&&storage.byteSize()>=plan.bytes()?0:plan.bytes()+CudaOpticalMediumAbi.ALLOCATION_ALLOWANCE;
        if(plan.externalPrimary()&&(primary==null||primary.sizeBytes()<plan.primaryBytes()))growth=Math.addExact(growth,plan.primaryBytes());
        return growth;
    });}
    synchronized CudaCameraMediumManager.Prepared prepare(FrameScene scene,GpuSceneRayTracingPipeline primaryProducer,
            GpuSceneRayTracingPipeline opticalTracer,int slot,RtTlasBuildPlan buildPlan){
        checkOpen();Objects.requireNonNull(opticalTracer,"opticalTracer");
        long started=OpticalFrameTimings.ENABLED?System.nanoTime():0L;
        try{return onNative(()->{
            try{return prepareOnNative(scene,opticalTracer,slot,buildPlan);}
            catch(RuntimeException|Error failure){uploadedAssets=null;throw failure;}
        });}finally{if(OpticalFrameTimings.ENABLED)OpticalFrameTimings.prepare.add(System.nanoTime()-started);}
    }
    private CudaCameraMediumManager.Prepared prepareOnNative(FrameScene scene,GpuSceneRayTracingPipeline tracer,int slot,RtTlasBuildPlan buildPlan){
        var frame=scene.frame();var plan=scene.plan();ensureCapacity(plan);long address=storage.deviceAddress();
        var step=steps[(plan.compactHits()?1:0)|(plan.compactStates()?2:0)];
        long primaryAddress=plan.externalPrimary()?primary.deviceAddress():0;
        int opticalSamples=frame.mediumTransport().opticalSamples();
        int branches=frame.mediumTransport().firstInterfaceSampling()==top.ceroxe.rt.renderer.api.MediumTransport.FirstInterfaceSampling.DETERMINISTIC_SPLIT?2:1;
        boolean uploadAssets=!scene.assets.equals(uploadedAssets);
        try(Arena arena=Arena.ofConfined()){
            int firstUploadBytes=uploadAssets?plan.sceneBytes():CudaOpticalMediumAbi.HEADER_BYTES;
            var metadata=arena.allocate(firstUploadBytes,16);
            metadata.asByteBuffer().put(scene.metadata,0,uploadAssets?scene.metadata.length:CudaOpticalMediumAbi.HEADER_BYTES);
            if(uploadAssets)scene.assets.density.write(metadata.asByteBuffer().order(ByteOrder.nativeOrder()),scene.metadata.length);
            var counters=arena.allocate(CudaOpticalMediumAbi.STATISTICS_BYTES,16);
            var phaseCycles=OpticalFrameTimings.KERNEL_PHASES?arena.allocate(64,16):null;boolean buildPending=buildPlan!=null;
            for(int first=0;first<plan.pixels();){
                int tileFirst=first,pixels=Math.min(plan.tilePixels(),plan.pixels()-first),paths=pixels*plan.samples();
                for(int opticalSample=0;opticalSample<opticalSamples;opticalSample++)for(int branch=0;branch<branches;branch++){
                    CudaOpticalMediumAbi.updateTile(metadata.asByteBuffer().order(ByteOrder.nativeOrder()),plan,first,pixels,opticalSample,branch);
                    boolean recordBuild=buildPending,uploadScene=first==0&&opticalSample==0&&branch==0;
                    int[] statistics={paths,0,0,0};
                    java.util.function.Consumer<CudaMediumRuntime.Slice> resolve=input->{
                        long resolveStarted=OpticalFrameTimings.ENABLED?System.nanoTime():0L;
                        int queries=statistics[0];cuda.launchBlocking(reset,input,1,CudaOpticalMediumAbi.HEADER_BYTES);
                        cuda.launchBlocking(step,input,paths,1);
                        cuda.download(input.slice(CudaOpticalMediumAbi.STATISTICS_OFFSET,CudaOpticalMediumAbi.STATISTICS_BYTES),counters);
                        for(int j=0;j<4;j++)statistics[j]=counters.get(ValueLayout.JAVA_INT,j*4L);
                        if(phaseCycles!=null){
                            cuda.download(input.slice(192,64),phaseCycles);
                            for(int j=0;j<8;j++)OpticalFrameTimings.kernelPhases[j].add(phaseCycles.get(ValueLayout.JAVA_LONG,j*8L));
                        }
                        diagnosticQueries.add(queries);diagnosticInterfaces.add(statistics[2]);
                        if(OpticalFrameTimings.ENABLED)OpticalFrameTimings.resolve.add(System.nanoTime()-resolveStarted);
                    };
                    java.util.function.Consumer<org.lwjgl.vulkan.VkCommandBuffer> continuation=command->{
                        if(statistics[1]==0&&statistics[0]>0)try(MemoryStack stack=MemoryStack.stackPush()){
                            tracer.recordNativeOpticalTrace(command,stack,slot,address,primaryAddress,tileFirst,pixels,false);
                        }
                    };
                    storage.produceThenExchangeBlocking(command->{try(MemoryStack stack=MemoryStack.stackPush()){
                        if(recordBuild)buildPlan.record(command,stack);
                        if(uploadScene&&plan.externalPrimary()) {
                            var primaryBarrier=VkBufferMemoryBarrier.calloc(1,stack).sType$Default()
                                    .srcAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT|VK10.VK_ACCESS_SHADER_WRITE_BIT)
                                    .dstAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                                    .buffer(primary.buffer()).offset(0).size(primary.sizeBytes());
                            VK10.vkCmdPipelineBarrier(command,KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,0,null,primaryBarrier,null);
                        }
                        int bytes=uploadScene?firstUploadBytes:CudaOpticalMediumAbi.HEADER_BYTES;
                        uploadMetadata(command,stack,metadata.asByteBuffer(),bytes);
                        tracer.recordNativeOpticalTrace(command,stack,slot,address,primaryAddress,tileFirst,pixels,true);
                    }},resolve,continuation);
                    buildPending=false;int rounds=1;
                    while(statistics[1]==0&&statistics[0]>0){
                        if(rounds++>=frame.mediumTransport().maxEvents()+2*(frame.mediumTransport().scatteringBounces()+1))
                            throw new RendererException("native optical continuation exceeded the public event and scattering budgets");
                        storage.exchangeBlocking(resolve,continuation);
                    }
                    if(statistics[1]!=0)throw new RendererException("native optical continuation rejected "+statistics[1]+" paths; incomplete radiance was not published");
                }first+=pixels;
            }
        }
        if(uploadAssets){uploadedAssets=scene.assets;diagnosticAssetUploads.increment();}
        return new CudaCameraMediumManager.Prepared(plan.externalPrimary()?primaryAddress:address+plan.primaryOffset(),address+plan.transmittanceOffset(),
                address+plan.emissionOffset(),plan.pixels(),plan.samples(),plan.externalPrimary());
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
        if(storage==null||storage.byteSize()<plan.bytes()) {
            if(plan.bytes()+CudaOpticalMediumAbi.ALLOCATION_ALLOWANCE>Math.min(cuda.maxResidentBytes(),CudaOpticalMediumAbi.DEFAULT_RESIDENT_BYTES))
                throw unsupported("complete native optical frame exceeds the CUDA resident cap");
            uploadedAssets=null;
            if(storage!=null){drain();storage.close();storage=null;}
            storage=CudaSharedRayFrame.storage(device,cuda,plan.bytes(),cuda.maxResidentBytes()-cuda.residentBytes());
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
    private static long residentLimit(){return Math.min(CudaOpticalMediumAbi.DEFAULT_RESIDENT_BYTES,
            Long.getLong("top.ceroxe.rt.medium.cuda.maxResidentBytes",CudaOpticalMediumAbi.DEFAULT_RESIDENT_BYTES));}
    private <T>T onNative(Callable<T> operation){var submitted=nativeThread.submit(operation);boolean interrupted=false;
        try{for(;;)try{return submitted.get();}catch(InterruptedException ignored){interrupted=true;}
            catch(ExecutionException failure){if(failure.getCause() instanceof RuntimeException runtime)throw runtime;
                if(failure.getCause() instanceof Error error)throw error;throw new IllegalStateException("native optical operation failed",failure.getCause());}
        }finally{if(interrupted)Thread.currentThread().interrupt();}}
    private static FrameValidationException unsupported(String message){return new FrameValidationException(
            FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,"native optical candidate: "+message);}
    @Override public synchronized void close(){if(closed)return;try{onNative(()->{Throwable failure=null;
        try{drain();}catch(RuntimeException|Error exception){failure=exception;}
        if(storage!=null)try{storage.close();storage=null;}catch(RuntimeException|Error exception){if(failure==null)failure=exception;else failure.addSuppressed(exception);}
        if(primary!=null)try{primary.close();primary=null;}catch(RuntimeException|Error exception){if(failure==null)failure=exception;else failure.addSuppressed(exception);}
        try{cuda.close();}catch(RuntimeException|Error exception){if(failure==null)failure=exception;else failure.addSuppressed(exception);}
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;return null;
    });}finally{uploadedAssets=null;closed=true;nativeThread.shutdown();}}
    private record NativeState(CudaMediumRuntime cuda,CudaMediumRuntime.BatchKernel reset,CudaMediumRuntime.BatchKernel[] steps){}
    private record FrameAssets(java.util.List<CudaBoundaryMediumAbi.Boundary> boundaries,CudaMediumDensityAbi.Scene density,int sceneBytes){}
    static final class FrameScene {
        private final RenderFrameRequest frame;private final CudaOpticalMediumAbi.Plan plan;private final byte[] metadata;private final FrameAssets assets;
        FrameScene(RenderFrameRequest frame,CudaOpticalMediumAbi.Plan plan,byte[] metadata,
                java.util.List<CudaBoundaryMediumAbi.Boundary> boundaries,CudaMediumDensityAbi.Scene density){
            this.frame=frame;this.plan=plan;this.metadata=metadata.clone();
            this.assets=new FrameAssets(java.util.List.copyOf(boundaries),density,plan.sceneBytes());
        }
        RenderFrameRequest frame(){return frame;}CudaOpticalMediumAbi.Plan plan(){return plan;}
        boolean sameAssets(FrameScene other){return other!=null&&assets.equals(other.assets);}
    }
}
