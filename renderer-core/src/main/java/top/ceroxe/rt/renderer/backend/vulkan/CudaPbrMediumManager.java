package top.ceroxe.rt.renderer.backend.vulkan;

import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
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
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaBoundaryMediumAbi;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaMediumDensityAbi;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaMediumRuntime;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaPbrMediumAbi;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaPbrMediumSource;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaSharedRayFrame;
import top.ceroxe.rt.renderer.rt.acceleration.RtTlasBuildPlan;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.device.VulkanQueueHostSync;
import top.ceroxe.rt.renderer.rt.pipeline.GpuSceneRayTracingPipeline;

/** One Vulkan packet production and one native resolve per tile, independently of light count. */
final class CudaPbrMediumManager implements AutoCloseable {
    private final ExecutorService nativeThread=Executors.newSingleThreadExecutor(task->{
        Thread thread=new Thread(task,"RTRenderer-native-pbr-medium");thread.setDaemon(true);return thread;
    });
    private final VulkanDeviceRuntime device;
    private final CudaMediumRuntime cuda;
    private final CudaMediumRuntime.BatchKernel resolve;
    private CudaSharedRayFrame storage;
    private RtGpuBuffer primary;
    private Assets uploadedAssets;
    private boolean closed;

    CudaPbrMediumManager(VulkanDeviceRuntime device) {
        this.device=Objects.requireNonNull(device,"device");NativeState initialized;
        try{initialized=onNative(()->{
            var opened=CudaMediumRuntime.open(Integer.getInteger("top.ceroxe.rt.medium.cuda.deviceOrdinal",0));
            try{return new NativeState(opened,opened.compileBatchKernel(CudaPbrMediumSource.source(),"native_pbr_medium_resolve"));}
            catch(RuntimeException|Error failure){try{opened.close();}catch(RuntimeException cleanup){failure.addSuppressed(cleanup);}throw failure;}
        });}catch(RuntimeException|Error failure){nativeThread.shutdown();throw failure;}
        cuda=initialized.cuda;resolve=initialized.resolve;
    }

    static FrameScene scene(RenderFrameRequest frame,int width,int height,int lightUpperBound,MediumSceneIndex.NativeScene scene,
            LongFunction<TextureAsset> textures,CudaMediumDensityAbi.Cache cache) {
        try {
            var boundaries=scene.boundaries().stream().map(e->new CudaBoundaryMediumAbi.Boundary(e.slot(),e.boundary(),e.objectToWorld())).toList();
            int capacity=scene.packetCapacity(frame.mediumTransport().maxEvents()),samples=frame.antiAliasing().samplesPerPixel();
            long limit=residentLimit()-CudaPbrMediumAbi.ALLOCATION_ALLOWANCE;
            long densityLimit=limit-CudaPbrMediumAbi.minimumBytes(width,height,samples,boundaries.size(),lightUpperBound,capacity);
            var density=cache==null?CudaMediumDensityAbi.prepare(frame.environment().cameraMedium(),boundaries,textures,densityLimit)
                    :cache.prepare(frame.environment().cameraMedium(),boundaries,textures,densityLimit);
            var plan=CudaPbrMediumAbi.plan(width,height,samples,boundaries.size(),lightUpperBound,capacity,density,limit);
            return new FrameScene(plan,frame,boundaries,scene.containing(),density);
        }catch(IllegalArgumentException|ArithmeticException invalid){throw unsupported("native PBR medium frame cannot be represented: "+invalid.getMessage());}
    }
    static long requiredBytes(FrameScene scene){return Math.addExact(scene.plan.bytes()+CudaPbrMediumAbi.ALLOCATION_ALLOWANCE,scene.plan.primaryBytes());}
    synchronized long requiredGrowthBytes(FrameScene scene) {
        checkOpen();return onNative(()->{
            long growth=storage!=null&&storage.byteSize()>=scene.plan.bytes()?0:scene.plan.bytes()+CudaPbrMediumAbi.ALLOCATION_ALLOWANCE;
            if(primary==null||primary.sizeBytes()<scene.plan.primaryBytes())growth=Math.addExact(growth,scene.plan.primaryBytes());
            return growth;
        });
    }
    synchronized CudaCameraMediumManager.Prepared prepare(FrameScene scene,GpuSceneRayTracingPipeline primaryProducer,
            GpuSceneRayTracingPipeline lightProducer,int slot,RtTlasBuildPlan buildPlan,
            CausticPhotonPlan photonPlan,GpuSceneRayTracingPipeline photonPipeline,RtGpuBuffer photonBuffer) {
        checkOpen();return onNative(()->prepareOnNative(scene,primaryProducer,lightProducer,slot,buildPlan,
                photonPlan,photonPipeline,photonBuffer));
    }
    private CudaCameraMediumManager.Prepared prepareOnNative(FrameScene scene,GpuSceneRayTracingPipeline primaryProducer,
            GpuSceneRayTracingPipeline lightProducer,int slot,RtTlasBuildPlan buildPlan,
            CausticPhotonPlan photonPlan,GpuSceneRayTracingPipeline photonPipeline,RtGpuBuffer photonBuffer) {
        var plan=scene.plan;ensureCapacity(plan);long address=storage.deviceAddress();boolean changed=!scene.assets.equals(uploadedAssets);
        try(Arena arena=Arena.ofConfined()) {
            int uploadBytes=changed?plan.sceneBytes():CudaPbrMediumAbi.HEADER_BYTES;
            var metadata=arena.allocate(uploadBytes,16);metadata.asByteBuffer().put(scene.metadata,0,changed?scene.metadata.length:CudaPbrMediumAbi.HEADER_BYTES);
            if(changed)scene.assets.density.write(metadata.asByteBuffer().order(ByteOrder.nativeOrder()),scene.metadata.length);
            var counters=arena.allocate(CudaPbrMediumAbi.STATISTICS_BYTES,16);
            for(int first=0;first<plan.pixels();) {
                int tileFirst=first,pixels=Math.min(plan.tilePixels(),plan.pixels()-first),paths=pixels*plan.samples();boolean initial=first==0;
                CudaPbrMediumAbi.updateTile(metadata.asByteBuffer().order(ByteOrder.nativeOrder()),plan,first,pixels);
                int bytes=initial?uploadBytes:CudaPbrMediumAbi.HEADER_BYTES;
                storage.produceThenExchangeBlocking(command->{try(MemoryStack stack=MemoryStack.stackPush()) {
                    if(initial&&buildPlan!=null)buildPlan.record(command,stack);
                    upload(command,stack,metadata.asByteBuffer(),bytes);
                    if (initial && photonPlan != null && photonPlan.photons() > 0) {
                        if (photonPipeline == null || photonBuffer == null)
                            throw new IllegalArgumentException("native PBR caustics require a photon pipeline and map buffer");
                        photonPlan.record(command, stack, photonBuffer, photonPipeline, slot);
                    }
                    primaryProducer.recordBoundaryPacketTrace(command,stack,slot,address,primary.deviceAddress(),tileFirst,pixels,plan.stateOffset());
                    barrier(command,stack);
                    lightProducer.recordBoundaryPacketTrace(command,stack,slot,address,primary.deviceAddress(),tileFirst,
                            Math.multiplyExact(pixels,plan.connections()),plan.stateOffset());
                }},shared->{
                    cuda.launchBlocking(resolve,shared,paths,1);
                    cuda.download(shared.slice(CudaPbrMediumAbi.STATISTICS_OFFSET,CudaPbrMediumAbi.STATISTICS_BYTES),counters);
                },command->{});
                if(counters.get(ValueLayout.JAVA_INT,0)!=0||counters.get(ValueLayout.JAVA_INT,4)!=0||counters.get(ValueLayout.JAVA_INT,12)!=paths)
                    throw new RendererException("native PBR medium produced invalid packet states; final output was not published");
                first+=pixels;
            }
        }
        uploadedAssets=scene.assets;
        // This private flag preserves primary geometry while replacing only the already-integrated radiance split.
        return new CudaCameraMediumManager.Prepared(primary.deviceAddress(),(address+plan.transmittanceOffset())|CudaPbrMediumAbi.NATIVE_LIGHTING_FLAG,
                address+plan.emissionOffset(),plan.pixels(),plan.samples(),true);
    }
    private void upload(org.lwjgl.vulkan.VkCommandBuffer command,MemoryStack stack,ByteBuffer source,int bytes) {
        var writable=VkBufferMemoryBarrier.calloc(1,stack).sType$Default().srcAccessMask(VK10.VK_ACCESS_MEMORY_READ_BIT|VK10.VK_ACCESS_MEMORY_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT).srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).buffer(storage.buffer()).offset(0).size(storage.byteSize());
        VK10.vkCmdPipelineBarrier(command,VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,0,null,writable,null);
        for(int offset=0;offset<bytes;) {
            int count=Math.min(65536,bytes-offset);ByteBuffer part=source.duplicate();part.position(offset).limit(offset+count);
            VK10.vkCmdUpdateBuffer(command,storage.buffer(),offset,part.slice());offset+=count;
        }
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
        if(primary!=null) {
            var primaryBarrier=VkBufferMemoryBarrier.calloc(1,stack).sType$Default().srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT).srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).buffer(primary.buffer()).offset(0).size(primary.sizeBytes());
            VK10.vkCmdPipelineBarrier(command,KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,0,null,primaryBarrier,null);
        }
    }
    private void ensureCapacity(CudaPbrMediumAbi.Plan plan) {
        if(storage==null||storage.byteSize()<plan.bytes()) {
            if(plan.bytes()+CudaPbrMediumAbi.ALLOCATION_ALLOWANCE>cuda.maxResidentBytes())throw unsupported("native PBR complete-frame storage exceeds CUDA residency");
            if(storage!=null){drain();storage.close();storage=null;}uploadedAssets=null;
            storage=CudaSharedRayFrame.storage(device,cuda,plan.bytes(),cuda.maxResidentBytes()-cuda.residentBytes());
        }
        if(primary==null||primary.sizeBytes()<plan.primaryBytes()) {
            if(primary!=null){drain();primary.close();}
            primary=RtGpuBuffer.createDeviceAddressBuffer(device.device(),device.allocator(),plan.primaryBytes(),VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        }
    }
    private static long residentLimit(){return Math.min(CudaPbrMediumAbi.DEFAULT_RESIDENT_BYTES,
            Long.getLong("top.ceroxe.rt.medium.cuda.maxResidentBytes",CudaPbrMediumAbi.DEFAULT_RESIDENT_BYTES));}
    private void drain(){int result=VulkanQueueHostSync.waitIdle(device.frameQueue());if(result!=VK10.VK_SUCCESS)throw new IllegalStateException("drain native PBR consumers: VkResult="+result);}
    private void checkOpen(){if(closed)throw new IllegalStateException("native PBR manager is closed");}
    private static FrameValidationException unsupported(String message){return new FrameValidationException(FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,message);}
    private <T>T onNative(Callable<T> operation) {
        var result=nativeThread.submit(operation);boolean interrupted=false;
        try{for(;;)try{return result.get();}catch(InterruptedException ignored){interrupted=true;}catch(ExecutionException failure){
            if(failure.getCause() instanceof RuntimeException runtime)throw runtime;if(failure.getCause() instanceof Error error)throw error;
            throw new IllegalStateException("native PBR operation failed",failure.getCause());}}
        finally{if(interrupted)Thread.currentThread().interrupt();}
    }
    @Override public synchronized void close(){if(closed)return;try{onNative(()->{Throwable failure=null;
        try{drain();}catch(RuntimeException|Error exception){failure=exception;}
        if(storage!=null)try{storage.close();storage=null;}catch(RuntimeException|Error exception){if(failure==null)failure=exception;else failure.addSuppressed(exception);}
        if(primary!=null)try{primary.close();primary=null;}catch(RuntimeException|Error exception){if(failure==null)failure=exception;else failure.addSuppressed(exception);}
        try{cuda.close();}catch(RuntimeException|Error exception){if(failure==null)failure=exception;else failure.addSuppressed(exception);}
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;return null;
    });}finally{closed=true;nativeThread.shutdown();}}
    private record NativeState(CudaMediumRuntime cuda,CudaMediumRuntime.BatchKernel resolve) { }
    private record Assets(List<CudaBoundaryMediumAbi.Boundary> boundaries,CudaMediumDensityAbi.Scene density,int sceneBytes) { }
    static final class FrameScene {
        private final CudaPbrMediumAbi.Plan plan;private final byte[] metadata;private final Assets assets;
        FrameScene(CudaPbrMediumAbi.Plan plan,RenderFrameRequest frame,List<CudaBoundaryMediumAbi.Boundary> boundaries,int[] containing,CudaMediumDensityAbi.Scene density) {
            this.plan=plan;this.assets=new Assets(List.copyOf(boundaries),density,plan.sceneBytes());
            this.metadata=CudaPbrMediumAbi.scenePrefix(plan,frame,boundaries,containing,density);
        }
        CudaPbrMediumAbi.Plan plan(){return plan;}
    }
}
