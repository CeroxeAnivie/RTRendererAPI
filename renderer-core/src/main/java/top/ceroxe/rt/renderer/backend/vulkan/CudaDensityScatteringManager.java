package top.ceroxe.rt.renderer.backend.vulkan;

import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongFunction;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import top.ceroxe.rt.renderer.api.FrameValidationException;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.RendererException;
import top.ceroxe.rt.renderer.api.TextureAsset;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaDensityScatteringAbi;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaDensityScatteringSource;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaMediumDensityAbi;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaMediumRuntime;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaSharedRayFrame;
import top.ceroxe.rt.renderer.rt.acceleration.RtTlasBuildPlan;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.device.VulkanQueueHostSync;
import top.ceroxe.rt.renderer.rt.pipeline.GpuSceneRayTracingPipeline;

/** Blocking GPU-resident density samples, real Vulkan light connections and native density shadows. */
final class CudaDensityScatteringManager implements AutoCloseable {
    private final ExecutorService nativeThread=Executors.newSingleThreadExecutor(task->{
        Thread thread=new Thread(task,"RTRenderer-native-density-scattering");thread.setDaemon(true);return thread;
    });
    private final VulkanDeviceRuntime device;
    private final CudaMediumRuntime cuda;
    private final CudaMediumRuntime.BatchKernel prepare,resolve;
    private CudaSharedRayFrame storage;
    private CudaMediumDensityAbi.Scene uploadedDensity;
    private boolean closed;
    CudaDensityScatteringManager(VulkanDeviceRuntime device) {
        this.device=Objects.requireNonNull(device,"device");NativeState state;
        try {state=onNative(()->{
            var opened=CudaMediumRuntime.open(Integer.getInteger("top.ceroxe.rt.medium.cuda.deviceOrdinal",0));
            try {String source=CudaDensityScatteringSource.source();return new NativeState(opened,
                    opened.compileBatchKernel(source,"density_scattering_prepare"),opened.compileBatchKernel(source,"density_scattering_resolve"));}
            catch(RuntimeException|Error failure){try{opened.close();}catch(RuntimeException closeFailure){failure.addSuppressed(closeFailure);}throw failure;}
        });}catch(RuntimeException|Error failure){nativeThread.shutdown();throw failure;}
        cuda=state.cuda;prepare=state.prepare;resolve=state.resolve;
    }
    static CudaDensityScatteringAbi.FrameScene scene(RenderFrameRequest frame,int width,int height,int lightUpperBound,
            LongFunction<TextureAsset> textures,CudaMediumDensityAbi.Cache cache) {
        try {return CudaDensityScatteringAbi.scene(frame,width,height,lightUpperBound,textures,cache,residentLimit());}
        catch(IllegalArgumentException|ArithmeticException invalid){throw unsupported(invalid.getMessage());}
    }
    static long requiredBytes(CudaDensityScatteringAbi.FrameScene scene) {return Math.addExact(scene.plan().bytes(),CudaDensityScatteringAbi.ALLOCATION_ALLOWANCE);}
    synchronized long requiredGrowthBytes(CudaDensityScatteringAbi.FrameScene scene) {
        checkOpen();return onNative(()->storage!=null&&storage.byteSize()>=scene.plan().bytes()?0:requiredBytes(scene));
    }
    synchronized CudaCameraMediumManager.Prepared prepare(CudaDensityScatteringAbi.FrameScene scene,
            GpuSceneRayTracingPipeline primaryProducer,GpuSceneRayTracingPipeline lightProducer,int slot,RtTlasBuildPlan buildPlan) {
        checkOpen();return onNative(()->prepareOnNative(scene,primaryProducer,lightProducer,slot,buildPlan));
    }
    private CudaCameraMediumManager.Prepared prepareOnNative(CudaDensityScatteringAbi.FrameScene scene,
            GpuSceneRayTracingPipeline primaryProducer,GpuSceneRayTracingPipeline lightProducer,int slot,RtTlasBuildPlan buildPlan) {
        var plan=scene.plan();ensureCapacity(plan.bytes());long address=storage.deviceAddress();
        boolean densityChanged=uploadedDensity!=scene.density();if(densityChanged)uploadedDensity=null;
        try(Arena arena=Arena.ofConfined()) {
            var failed=arena.allocate(4,4);
            for(int first=0;first<plan.pixels();) {
                int tileFirst=first,pixels=Math.min(plan.tilePixels(),plan.pixels()-first),paths=pixels*plan.samples();boolean initial=first==0;
                boolean full=initial&&densityChanged;
                byte[] metadata=full?scene.metadata(first,pixels,0,0):scene.header(first,pixels,0,0);
                storage.produceThenExchangeBlocking(command->{try(MemoryStack stack=MemoryStack.stackPush()) {
                    upload(command,stack,metadata,full);
                    // A full metadata upload already initializes the counter. Avoid an overlapping
                    // transfer write; small cached headers deliberately preserve this counter.
                    if(initial){if(!full)VK10.vkCmdFillBuffer(command,storage.buffer(),CudaDensityScatteringAbi.FAILED_OFFSET,4,0);
                        if(buildPlan!=null)buildPlan.record(command,stack);
                        primaryProducer.recordPrimaryQueueTrace(command,stack,slot,address+plan.primaryOffset(),0,plan.pixels());}
                    barrier(command,stack);
                    lightProducer.recordPrimaryQueueTrace(command,stack,slot,address,tileFirst,pixels);
                }},shared->cuda.launchBlocking(prepare,shared,paths,1),command->{try(MemoryStack stack=MemoryStack.stackPush()) {
                    upload(command,stack,scene.header(tileFirst,pixels,1,0),false);
                    lightProducer.recordPrimaryQueueTrace(command,stack,slot,address,tileFirst,pixels);
                }});
                for(int chunk=0;chunk<scene.chunks();chunk++) {
                    int next=chunk+1;boolean last=next==scene.chunks();boolean finalTile=first+pixels==plan.pixels();
                    storage.exchangeBlocking(shared->{cuda.launchBlocking(resolve,shared,paths,1);
                        if(last&&finalTile)cuda.download(shared.slice(CudaDensityScatteringAbi.FAILED_OFFSET,4),failed);
                    },command->{if(!last)try(MemoryStack stack=MemoryStack.stackPush()) {
                        upload(command,stack,scene.header(tileFirst,pixels,1,next),false);
                        lightProducer.recordPrimaryQueueTrace(command,stack,slot,address,tileFirst,pixels);
                    }});
                }
                first+=pixels;
            }
            // The only device-to-host result is one aggregate failure counter, folded into the last round.
            if(failed.get(ValueLayout.JAVA_INT,0)!=0)throw new RendererException("native density scattering produced invalid state; final output was not published");
            uploadedDensity=scene.density();
        }
        return new CudaCameraMediumManager.Prepared(address+plan.primaryOffset(),address+plan.transmittanceOffset(),
                address+plan.emissionOffset(),plan.pixels(),plan.samples());
    }
    private void upload(org.lwjgl.vulkan.VkCommandBuffer command,MemoryStack stack,byte[] metadata,boolean full) {
        // CUDA ownership acquisition makes native writes visible, but repeated Vulkan metadata
        // transfers also need an explicit dependency from the previous Vulkan accesses.
        var writable=VkBufferMemoryBarrier.calloc(1,stack).sType$Default()
                .srcAccessMask(VK10.VK_ACCESS_MEMORY_READ_BIT|VK10.VK_ACCESS_MEMORY_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .buffer(storage.buffer()).offset(0).size(storage.byteSize());
        VK10.vkCmdPipelineBarrier(command,VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                0,null,writable,null);
        if(full) {try(Arena upload=Arena.ofConfined()) {
            ByteBuffer source=upload.allocate(metadata.length,16).asByteBuffer();source.put(metadata);
            for(int offset=0;offset<metadata.length;) {int bytes=Math.min(65536,metadata.length-offset);
                ByteBuffer part=source.duplicate();part.position(offset).limit(offset+bytes);VK10.vkCmdUpdateBuffer(command,storage.buffer(),offset,part.slice());offset+=bytes;}
        }}
        else {VK10.vkCmdUpdateBuffer(command,storage.buffer(),0,stack.malloc(112).put(metadata,0,112).flip());
            VK10.vkCmdUpdateBuffer(command,storage.buffer(),116,stack.malloc(28).put(metadata,116,28).flip());}
        barrier(command,stack);
    }
    private void barrier(org.lwjgl.vulkan.VkCommandBuffer command,MemoryStack stack) {
        var barrier=VkBufferMemoryBarrier.calloc(1,stack).sType$Default()
                .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT|VK10.VK_ACCESS_SHADER_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT|VK10.VK_ACCESS_SHADER_WRITE_BIT)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .buffer(storage.buffer()).offset(0).size(storage.byteSize());
        VK10.vkCmdPipelineBarrier(command,VK10.VK_PIPELINE_STAGE_TRANSFER_BIT|KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,0,null,barrier,null);
    }
    private void ensureCapacity(long bytes) {
        if(storage!=null&&storage.byteSize()>=bytes)return;
        if(bytes+CudaDensityScatteringAbi.ALLOCATION_ALLOWANCE>Math.min(cuda.maxResidentBytes(),CudaDensityScatteringAbi.DEFAULT_RESIDENT_BYTES))
            throw unsupported("complete density-scattering frame exceeds the session CUDA cap");
        if(storage!=null){drain();storage.close();storage=null;}uploadedDensity=null;
        storage=CudaSharedRayFrame.storage(device,cuda,bytes,cuda.maxResidentBytes()-cuda.residentBytes());
    }
    private static long residentLimit(){return Math.min(CudaDensityScatteringAbi.DEFAULT_RESIDENT_BYTES,
            Long.getLong("top.ceroxe.rt.medium.cuda.maxResidentBytes",CudaDensityScatteringAbi.DEFAULT_RESIDENT_BYTES));}
    private void drain(){int result=VulkanQueueHostSync.waitIdle(device.frameQueue());if(result!=VK10.VK_SUCCESS)
        throw new IllegalStateException("drain native density scattering consumers: VkResult="+result);}
    private void checkOpen(){if(closed)throw new IllegalStateException("density-scattering manager is closed");}
    private static FrameValidationException unsupported(String reason){return new FrameValidationException(
            FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,reason);}
    private <T>T onNative(Callable<T> task) {var result=nativeThread.submit(task);boolean interrupted=false;
        try{for(;;)try{return result.get();}catch(InterruptedException ignored){interrupted=true;}catch(ExecutionException failure){
            if(failure.getCause() instanceof RuntimeException runtime)throw runtime;if(failure.getCause() instanceof Error error)throw error;
            throw new IllegalStateException("native density-scattering operation failed",failure.getCause());}}
        finally{if(interrupted)Thread.currentThread().interrupt();}}
    @Override public synchronized void close(){if(closed)return;try{onNative(()->{Throwable failure=null;
        try{drain();}catch(RuntimeException|Error exception){failure=exception;}
        if(storage!=null)try{storage.close();storage=null;}catch(RuntimeException|Error exception){if(failure==null)failure=exception;else failure.addSuppressed(exception);}
        try{cuda.close();}catch(RuntimeException|Error exception){if(failure==null)failure=exception;else failure.addSuppressed(exception);}
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;return null;
    });}finally{closed=true;nativeThread.shutdown();}}
    private record NativeState(CudaMediumRuntime cuda,CudaMediumRuntime.BatchKernel prepare,CudaMediumRuntime.BatchKernel resolve) { }
}
