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
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.OpticalFrameTimings;
import top.ceroxe.rt.renderer.rt.acceleration.RtTlasBuildPlan;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.device.VulkanQueueHostSync;
import top.ceroxe.rt.renderer.rt.pipeline.GpuSceneRayTracingPipeline;

/** One Vulkan packet production and one native resolve per tile, independently of light count. */
final class CudaPbrMediumManager implements AutoCloseable {
    record RadianceContribution(long buffer,long offset,long bytes,boolean includesCameraEmission) {
        RadianceContribution {
            if(buffer==0||offset<0||bytes<16||(offset&15)!=0||(bytes&15)!=0)
                throw new IllegalArgumentException("invalid GPU medium-radiance contribution extent");
            Math.addExact(offset,bytes);
        }
    }
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
        return scene(frame,width,height,lightUpperBound,scene,textures,cache,false);
    }
    static FrameScene scene(RenderFrameRequest frame,int width,int height,int lightUpperBound,MediumSceneIndex.NativeScene scene,
            LongFunction<TextureAsset> textures,CudaMediumDensityAbi.Cache cache,boolean scatteringContribution) {
        try {
            var boundaries=scene.boundaries().stream().map(e->new CudaBoundaryMediumAbi.Boundary(e.slot(),e.boundary(),e.objectToWorld())).toList();
            int capacity=scene.packetCapacity(frame.mediumTransport().maxEvents()),samples=frame.antiAliasing().samplesPerPixel();
            if(scatteringContribution) {
                CudaPbrMediumProfile.requireScatteringContributionSupported(frame,boundaries.size());
                var density=CudaMediumDensityAbi.Scene.empty();
                if(!frame.environment().cameraMedium().densityField().isUniform()) {
                    long densityLimit=CudaPbrMediumAbi.scatteringDensityBudget(frame,width,height,lightUpperBound,capacity,residentLimit());
                    density=cache==null?CudaMediumDensityAbi.prepare(frame.environment().cameraMedium(),boundaries,textures,densityLimit)
                            :cache.prepare(frame.environment().cameraMedium(),boundaries,textures,densityLimit);
                }
                var combined=CudaPbrMediumAbi.planWithScattering(frame,width,height,lightUpperBound,capacity,density,residentLimit());
                return new FrameScene(combined.pbr(),frame,boundaries,scene.containing(),density,combined);
            }
            long limit=residentLimit()-CudaPbrMediumAbi.ALLOCATION_ALLOWANCE;
            long densityLimit=limit-CudaPbrMediumAbi.minimumBytes(width,height,samples,boundaries.size(),lightUpperBound,capacity);
            var density=cache==null?CudaMediumDensityAbi.prepare(frame.environment().cameraMedium(),boundaries,textures,densityLimit)
                    :cache.prepare(frame.environment().cameraMedium(),boundaries,textures,densityLimit);
            var plan=CudaPbrMediumAbi.plan(width,height,samples,boundaries.size(),lightUpperBound,capacity,density,limit);
            return new FrameScene(plan,frame,boundaries,scene.containing(),density);
        }catch(IllegalArgumentException|ArithmeticException invalid){throw unsupported("native PBR medium frame cannot be represented: "+invalid.getMessage());}
    }
    static long requiredBytes(FrameScene scene){return Math.addExact(scene.plan.bytes()+CudaPbrMediumAbi.ALLOCATION_ALLOWANCE,scene.plan.primaryBytes());}
    static void requireCombinedResidentBudget(FrameScene scene,int width,int height,int samples,int steps) {
        long pbrBytes=Math.addExact(scene.plan.bytes(),CudaPbrMediumAbi.ALLOCATION_ALLOWANCE);
        if(scene.transportPlan==null)throw unsupported("combined PBR scattering plan is missing");
        if(scene.plan.pixels()!=Math.multiplyExact(width,height)||scene.plan.samples()!=samples
                ||scene.scatteringSteps!=steps)
            throw unsupported("combined PBR scattering frame does not match its coordinated plan");
        long scatteringBytes=scene.transportPlan.scatteringBytes();
        if(Math.addExact(pbrBytes,scatteringBytes)>residentLimit())
            throw unsupported("combined PBR and scattering buffers exceed the CUDA resident cap");
    }
    synchronized long requiredGrowthBytes(FrameScene scene) {
        checkOpen();return onNative(()->{
            boolean reusable=storage!=null&&storage.byteSize()>=scene.plan.bytes()
                    &&(scene.transportPlan==null||storage.byteSize()==scene.plan.bytes());
            long growth=reusable?0:scene.plan.bytes()+CudaPbrMediumAbi.ALLOCATION_ALLOWANCE;
            if(primary==null||primary.sizeBytes()<scene.plan.primaryBytes())growth=Math.addExact(growth,scene.plan.primaryBytes());
            return growth;
        });
    }
    synchronized CudaCameraMediumManager.Prepared prepare(FrameScene scene,GpuSceneRayTracingPipeline primaryProducer,
            GpuSceneRayTracingPipeline lightProducer,int slot,RtTlasBuildPlan buildPlan,
            CausticPhotonPlan photonPlan,GpuSceneRayTracingPipeline photonPipeline,RtGpuBuffer photonBuffer,
            RadianceContribution additionalRadiance) {
        checkOpen();long started=OpticalFrameTimings.ENABLED?System.nanoTime():0L;
        try{return onNative(()->prepareOnNative(scene,primaryProducer,lightProducer,slot,buildPlan,
                photonPlan,photonPipeline,photonBuffer,additionalRadiance));}
        finally{if(OpticalFrameTimings.ENABLED)OpticalFrameTimings.pbrMediumPrepare.add(System.nanoTime()-started);}
    }
    private CudaCameraMediumManager.Prepared prepareOnNative(FrameScene scene,GpuSceneRayTracingPipeline primaryProducer,
            GpuSceneRayTracingPipeline lightProducer,int slot,RtTlasBuildPlan buildPlan,
            CausticPhotonPlan photonPlan,GpuSceneRayTracingPipeline photonPipeline,RtGpuBuffer photonBuffer,
            RadianceContribution additionalRadiance) {
        if((scene.transportPlan!=null)!=(additionalRadiance!=null))
            throw new IllegalArgumentException("PBR scattering requires the coordinated integrator contribution");
        if(additionalRadiance!=null&&additionalRadiance.includesCameraEmission()
                !=(scene.transportPlan.kind()!=CudaPbrMediumAbi.ScatteringKind.DIRECT_UNIFORM))
            throw new IllegalArgumentException("PBR scattering volume-emission ownership does not match its integrator");
        var plan=scene.plan;ensureCapacity(plan,scene.transportPlan!=null);long address=storage.deviceAddress();boolean changed=!scene.assets.equals(uploadedAssets);
        if(additionalRadiance!=null&&additionalRadiance.bytes()!=Math.multiplyExact((long)plan.paths(),16))
            throw new IllegalArgumentException("PBR/scattering complete-frame radiance extent mismatch");
        try(Arena arena=Arena.ofConfined()) {
            int uploadBytes=changed?plan.sceneBytes():CudaPbrMediumAbi.HEADER_BYTES;
            var metadata=arena.allocate(uploadBytes,16);metadata.asByteBuffer().put(scene.metadata,0,changed?scene.metadata.length:CudaPbrMediumAbi.HEADER_BYTES);
            if(changed)scene.assets.density.write(metadata.asByteBuffer().order(ByteOrder.nativeOrder()),scene.metadata.length);
            var counters=arena.allocate(CudaPbrMediumAbi.STATISTICS_BYTES,16);
            for(int first=0;first<plan.pixels();) {
                int tileFirst=first,pixels=Math.min(plan.tilePixels(),plan.pixels()-first),paths=pixels*plan.samples();boolean initial=first==0;
                CudaPbrMediumAbi.updateTile(metadata.asByteBuffer().order(ByteOrder.nativeOrder()),plan,first,pixels,
                        additionalRadiance==null?0L:Math.addExact(plan.emissionOffset(),Math.multiplyExact((long)first*plan.samples(),16)),
                        additionalRadiance!=null&&additionalRadiance.includesCameraEmission());
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
                    if(additionalRadiance!=null) {
                        // Packet tracing borrows the tile's emission lanes for terminal
                        // radiance. Restore the contribution only after all ray producers
                        // have finished, including on every tile after the first one.
                        long frameOffset=Math.multiplyExact((long)tileFirst*plan.samples(),16);
                        long tileBytes=Math.multiplyExact((long)paths,16);
                        var dependencies=VkBufferMemoryBarrier.calloc(2,stack);
                        dependencies.get(0).sType$Default().srcAccessMask(VK10.VK_ACCESS_MEMORY_WRITE_BIT)
                                .dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT).srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).buffer(additionalRadiance.buffer())
                                .offset(additionalRadiance.offset()+frameOffset).size(tileBytes);
                        dependencies.get(1).sType$Default().srcAccessMask(VK10.VK_ACCESS_MEMORY_READ_BIT|VK10.VK_ACCESS_MEMORY_WRITE_BIT)
                                .dstAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT).srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).buffer(storage.buffer())
                                .offset(plan.emissionOffset()+frameOffset).size(tileBytes);
                        VK10.vkCmdPipelineBarrier(command,VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                                0,null,dependencies,null);
                        var copy=org.lwjgl.vulkan.VkBufferCopy.calloc(1,stack).srcOffset(additionalRadiance.offset()+frameOffset)
                                .dstOffset(plan.emissionOffset()+frameOffset).size(tileBytes);
                        VK10.vkCmdCopyBuffer(command,additionalRadiance.buffer(),storage.buffer(),copy);
                        barrier(command,stack);
                    }
                }},shared->{
                    cuda.launchBlocking(resolve,shared,paths,1,"pbrMedium.resolve");
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
    private void ensureCapacity(CudaPbrMediumAbi.Plan plan,boolean exactBudget) {
        if(storage==null||storage.byteSize()<plan.bytes()||(exactBudget&&storage.byteSize()!=plan.bytes())) {
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
        private final CudaPbrMediumAbi.TransportPlan transportPlan;private final int scatteringSteps;
        FrameScene(CudaPbrMediumAbi.Plan plan,RenderFrameRequest frame,List<CudaBoundaryMediumAbi.Boundary> boundaries,int[] containing,CudaMediumDensityAbi.Scene density) {
            this(plan,frame,boundaries,containing,density,null);
        }
        FrameScene(CudaPbrMediumAbi.Plan plan,RenderFrameRequest frame,List<CudaBoundaryMediumAbi.Boundary> boundaries,int[] containing,CudaMediumDensityAbi.Scene density,
                CudaPbrMediumAbi.TransportPlan transportPlan) {
            this.plan=plan;this.assets=new Assets(List.copyOf(boundaries),density,plan.sceneBytes());
            this.transportPlan=transportPlan;this.scatteringSteps=frame.mediumTransport().scatteringSteps();
            this.metadata=CudaPbrMediumAbi.scenePrefix(plan,frame,boundaries,containing,density);
        }
        CudaPbrMediumAbi.Plan plan(){return plan;}
        top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaCameraScatteringAbi.Plan scatteringPlan(){return transportPlan==null?null:transportPlan.direct();}
        CudaPbrMediumAbi.TransportPlan transportPlan(){return transportPlan;}
    }
}
