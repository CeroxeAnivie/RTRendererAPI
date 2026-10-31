package top.ceroxe.rt.renderer.backend.vulkan;

import java.lang.foreign.Arena;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.OpticalWallTimeline;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
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
import top.ceroxe.rt.renderer.api.TextureAsset;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaBoundaryMediumAbi;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaBoundaryMediumSource;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaMediumRuntime;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaMediumDensityAbi;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaSharedRayFrame;
import top.ceroxe.rt.renderer.rt.acceleration.RtTlasBuildPlan;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.rt.renderer.rt.device.VulkanQueueHostSync;
import top.ceroxe.rt.renderer.rt.pipeline.GpuSceneBoundaryTraceCommands;
import top.ceroxe.rt.renderer.rt.pipeline.GpuSceneRayTracingPipeline;

/** Real scene continuation. One exported allocation avoids cross-buffer nested exchanges. */
final class CudaBoundaryMediumManager implements AutoCloseable {
    static final LongAdder diagnosticBoundaryCrossings=new LongAdder();
    static final LongAdder diagnosticQueryPaths=new LongAdder();
    static final LongAdder diagnosticAssetUploads=new LongAdder();
    static final LongAdder diagnosticUploadedBytes=new LongAdder();
    private static final long ALLOCATION_ALLOWANCE=65_536;
    private static final long MAX_RESIDENT_BYTES=Long.getLong(
            "top.ceroxe.rt.medium.cuda.maxResidentBytes",512L*1024*1024
    );
    private final ExecutorService nativeThread=Executors.newSingleThreadExecutor(task->{
        Thread thread=new Thread(task,"RTRenderer-native-boundaries"); thread.setDaemon(true); return thread;
    });
    private final VulkanDeviceRuntime device;
    private final boolean packet=Boolean.parseBoolean(System.getProperty("top.ceroxe.rt.medium.cuda.boundaryPacket","true"));
    private final CudaMediumRuntime cuda;
    private final CudaMediumRuntime.BatchKernel reset,step;
    private CudaSharedRayFrame storage;
    private RtGpuBuffer packetPrimary;
    private CudaMediumRuntime.HostMemory deferredStatistics;
    private FrameAssets uploadedAssets;
    private boolean closed;

    CudaBoundaryMediumManager(VulkanDeviceRuntime device) {
        this.device=Objects.requireNonNull(device,"device");
        NativeState initialized;
        try {
            initialized=onNative(()->{
                CudaMediumRuntime opened=CudaMediumRuntime.open(Integer.getInteger("top.ceroxe.rt.medium.cuda.deviceOrdinal",0));
                try {
                    String source=CudaBoundaryMediumSource.source();
                    return new NativeState(opened,opened.compileBatchKernel(source,"native_boundary_reset"),
                            opened.compileBatchKernel(source,packet?"native_boundary_packet_resolve":"native_boundary_step"));
                } catch(RuntimeException|Error failure) {
                    try {opened.close();} catch(RuntimeException closeFailure) {failure.addSuppressed(closeFailure);} throw failure;
                }
            });
        } catch(RuntimeException|Error failure) {nativeThread.shutdown();throw failure;}
        cuda=initialized.cuda(); reset=initialized.reset(); step=initialized.step();
    }

    static long requiredBytes(int width,int height,int samples,int boundaries) {
        try {
            var density=CudaMediumDensityAbi.Scene.empty();
            var plan=packetProperty()?CudaBoundaryMediumAbi.planPacket(width,height,samples,boundaries,density,configuredStorageLimit())
                    :CudaBoundaryMediumAbi.plan(width,height,samples,boundaries,density,configuredStorageLimit());
            return Math.addExact(requiredSharedBytes(plan),plan.packet()?packetPrimaryBytes(plan):0);
        }
        catch(ArithmeticException|IllegalArgumentException invalid) {throw unsupported("native continuation extent exceeds its bounded ABI");}
    }

    private static boolean terminalRadiance() {
        return Boolean.parseBoolean(System.getProperty("top.ceroxe.rt.medium.cuda.boundaryTerminalRadiance","true"));
    }

    private static boolean packetProperty() {
        return Boolean.parseBoolean(System.getProperty("top.ceroxe.rt.medium.cuda.boundaryPacket","true"));
    }

    private static long configuredStorageLimit() {
        return Math.min(MAX_RESIDENT_BYTES,Long.parseLong(System.getProperty(
                "top.ceroxe.rt.medium.cuda.maxResidentBytes",Long.toString(MAX_RESIDENT_BYTES))))-ALLOCATION_ALLOWANCE;
    }

    static FrameScene scene(RenderFrameRequest frame,int width,int height,MediumSceneIndex.NativeScene scene,
            java.util.function.LongFunction<TextureAsset> textures) {
        return scene(frame,width,height,scene,textures,null);
    }

    static FrameScene scene(RenderFrameRequest frame,int width,int height,MediumSceneIndex.NativeScene scene,
            java.util.function.LongFunction<TextureAsset> textures,CudaMediumDensityAbi.Cache densityCache) {
        try {
            var boundaries=scene.boundaries().stream().map(entry->new CudaBoundaryMediumAbi.Boundary(
                    entry.slot(),entry.boundary(),entry.objectToWorld())).toList();
            int samples=frame.antiAliasing().samplesPerPixel();
            long storageLimit=configuredStorageLimit();
            int packetCapacity=Boolean.parseBoolean(System.getProperty(
                    "top.ceroxe.rt.medium.cuda.boundaryCompactPacket","true"))
                    ?scene.packetCapacity(frame.mediumTransport().maxEvents()):32;
            long minimum=packetProperty()?CudaBoundaryMediumAbi.minimumPacketBytes(width,height,samples,boundaries.size(),packetCapacity)
                    :CudaBoundaryMediumAbi.minimumBytes(width,height,samples,boundaries.size());
            long densityLimit=storageLimit-minimum;
            var density=densityCache==null
                    ?CudaMediumDensityAbi.prepare(frame.environment().cameraMedium(),boundaries,textures,densityLimit)
                    :densityCache.prepare(frame.environment().cameraMedium(),boundaries,textures,densityLimit);
            var plan=packetProperty()
                    ?CudaBoundaryMediumAbi.planPacket(width,height,samples,boundaries.size(),density,storageLimit,packetCapacity)
                    :CudaBoundaryMediumAbi.plan(width,height,samples,boundaries.size(),density,storageLimit);
            // All unsupported data and upload bounds fail before replacing live GPU storage.
            return new FrameScene(frame,plan,boundaries,scene.containing(),density);
        } catch(ArithmeticException|IllegalArgumentException invalid) {
            throw unsupported("native boundary frame cannot be represented: "+invalid.getMessage());
        }
    }

    static long requiredBytes(FrameScene scene) {
        return Math.addExact(requiredSharedBytes(scene.plan()),scene.plan().packet()?packetPrimaryBytes(scene.plan()):0);
    }
    private static long requiredSharedBytes(CudaBoundaryMediumAbi.Plan plan) {return Math.addExact(plan.bytes(),ALLOCATION_ALLOWANCE);}
    private static long packetPrimaryBytes(CudaBoundaryMediumAbi.Plan plan) {
        return Math.multiplyExact((long)plan.paths(),CudaBoundaryMediumAbi.PRIMARY_BYTES);
    }

    synchronized long requiredGrowthBytes(FrameScene scene) {
        checkOpen();
        return onNative(()->{
            long growth=storage!=null&&storage.byteSize()>=scene.plan().bytes()?0:requiredSharedBytes(scene.plan());
            if(scene.plan().packet()&&(packetPrimary==null||packetPrimary.sizeBytes()<packetPrimaryBytes(scene.plan())))
                growth=Math.addExact(growth,packetPrimaryBytes(scene.plan()));
            return growth;
        });
    }

    synchronized long requiredGrowthBytes(int width,int height,int samples,int boundaries) {
        checkOpen(); long required=requiredBytes(width,height,samples,boundaries);
        return onNative(()->storage!=null && storage.byteSize()>=required-ALLOCATION_ALLOWANCE?0:required);
    }

    synchronized CudaCameraMediumManager.Prepared prepare(RenderFrameRequest frame,int width,int height,
            MediumSceneIndex.NativeScene scene,GpuSceneRayTracingPipeline producer,int slot,RtTlasBuildPlan buildPlan) {
        return prepare(scene(frame,width,height,scene,ignored->null),producer,slot,buildPlan);
    }

    synchronized CudaCameraMediumManager.Prepared prepare(FrameScene scene,
            GpuSceneRayTracingPipeline producer,int slot,RtTlasBuildPlan buildPlan) {
        checkOpen();
        return onNative(()->prepareOnNative(scene,producer,slot,buildPlan));
    }

    boolean canFuseConsumer(FrameScene scene) {
        return packet&&scene.plan().tilePixels()==scene.plan().pixels()
                &&Boolean.parseBoolean(System.getProperty("top.ceroxe.rt.medium.cuda.boundaryFusedConsumer","true"));
    }

    synchronized top.ceroxe.rt.renderer.rt.device.RtCommandContext.AsyncSubmission submitBlocking(FrameScene scene,
            GpuSceneRayTracingPipeline producer,int slot,RtTlasBuildPlan buildPlan,CudaCameraMediumManager.FinalConsumer consumer) {
        checkOpen();Objects.requireNonNull(consumer,"consumer");
        if(!canFuseConsumer(scene))throw new IllegalArgumentException("fused boundary consumer requires one complete packet tile");
        return onNative(()->{
            var submitted=new top.ceroxe.rt.renderer.rt.device.RtCommandContext.AsyncSubmission[1];
            try {
                prepareOnNative(scene,producer,slot,buildPlan,(prepared,semaphore,acquire)->{
                    submitted[0]=consumer.submit(prepared,semaphore,acquire);
                    submitted[0].close();
                    return submitted[0];
                });
                return submitted[0];
            } catch(RuntimeException|Error failure) {
                if(submitted[0]!=null)try{submitted[0].close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}
                throw failure;
            }
        });
    }

    private CudaCameraMediumManager.Prepared prepareOnNative(FrameScene scene,
            GpuSceneRayTracingPipeline producer,int slot,RtTlasBuildPlan buildPlan) {
        return prepareOnNative(scene,producer,slot,buildPlan,null);
    }

    private CudaCameraMediumManager.Prepared prepareOnNative(FrameScene scene,
            GpuSceneRayTracingPipeline producer,int slot,RtTlasBuildPlan buildPlan,CudaCameraMediumManager.FinalConsumer finalConsumer) {
        var plan=scene.plan(); var frame=scene.frame();
        ensureCapacity(plan.bytes());
        if(packet) ensurePacketPrimary(plan);
        long address=storage.deviceAddress();
        if(finalConsumer!=null&&deferredStatistics==null)deferredStatistics=cuda.allocateHost(CudaBoundaryMediumAbi.STATISTICS_BYTES);
        long primaryAddress=packet?packetPrimary.deviceAddress():address+plan.primaryOffset();
        var prepared=new CudaCameraMediumManager.Prepared(primaryAddress,
                (address+plan.transmittanceOffset())|(packet&&terminalRadiance()?2L:0L),
                address+plan.emissionOffset(),plan.pixels(),plan.samples(),packet);
        boolean buildPending=buildPlan!=null;
        boolean uploadAssets=!scene.assets.equals(uploadedAssets);
        try(Arena arena=Arena.ofConfined()) {
            int firstUploadBytes=uploadAssets?plan.sceneBytes():CudaBoundaryMediumAbi.HEADER_BYTES;
            MemorySegment hostMetadata=arena.allocate(firstUploadBytes,16);
            hostMetadata.asByteBuffer().put(scene.metadata,0,uploadAssets?scene.metadata.length:CudaBoundaryMediumAbi.HEADER_BYTES);
            if(uploadAssets)scene.assets.density().write(hostMetadata.asByteBuffer().order(ByteOrder.nativeOrder()),scene.metadata.length);
            MemorySegment counters=arena.allocate(CudaBoundaryMediumAbi.STATISTICS_BYTES,16);
        for(int first=0;first<plan.pixels();) {
            int tileFirst=first,pixels=Math.min(plan.tilePixels(),plan.pixels()-first),paths=pixels*plan.samples();
            CudaBoundaryMediumAbi.updateTile(hostMetadata.asByteBuffer().order(ByteOrder.nativeOrder()),plan,first,pixels);
            if(packet&&terminalRadiance())hostMetadata.asByteBuffer().order(ByteOrder.nativeOrder())
                    .putInt(CudaBoundaryMediumAbi.PACKET_LAYOUT_OFFSET,plan.packetCapacity()|CudaBoundaryMediumAbi.PACKET_TERMINAL_RADIANCE);
            int uploadBytes=first==0?firstUploadBytes:CudaBoundaryMediumAbi.HEADER_BYTES;
            int[] statistics={paths,0,0,0};
            String[] failureDetail={""};
            boolean recordBuild=buildPending;
                java.util.function.Consumer<CudaMediumRuntime.Slice> resolve=input->{
                    int queries=statistics[0];
                    if(!packet)cuda.launchBlocking(reset,input,1,CudaBoundaryMediumAbi.HEADER_BYTES);
                    cuda.launchBlocking(step,input,paths,packet?plan.packetPathBytes():CudaBoundaryMediumAbi.PATH_BYTES);
                    var statisticsSlice=input.slice(CudaBoundaryMediumAbi.STATISTICS_OFFSET,CudaBoundaryMediumAbi.STATISTICS_BYTES);
                    if(finalConsumer!=null)cuda.downloadAsync(statisticsSlice,deferredStatistics);
                    else {
                        cuda.download(statisticsSlice,counters);
                        for(int j=0;j<4;j++) statistics[j]=counters.get(ValueLayout.JAVA_INT,j*4L);
                        diagnosticQueryPaths.add(queries); diagnosticBoundaryCrossings.add(statistics[2]);
                        if(packet&&statistics[1]!=0)failureDetail[0]=packetFailureDetail(input,plan,tileFirst,paths,frame.width());
                    }
                };
                java.util.function.Consumer<org.lwjgl.vulkan.VkCommandBuffer> continuation=command->{
                    if(!packet&&statistics[1]==0 && statistics[0]>0) try(MemoryStack stack=MemoryStack.stackPush()) {
                        GpuSceneBoundaryTraceCommands.record(command,stack,producer,slot,address,plan,tileFirst,pixels,false);
                    }
                };
                // First real primary trace initializes GPU ray state; CUDA then consumes that exact hit.
                java.util.function.Consumer<org.lwjgl.vulkan.VkCommandBuffer> produce=command->{
                    try(MemoryStack stack=MemoryStack.stackPush()) {
                        if(recordBuild) buildPlan.record(command,stack);
                        uploadMetadata(command,stack,hostMetadata.asByteBuffer(),uploadBytes);
                        if(packet) GpuSceneBoundaryTraceCommands.recordPacket(command,stack,producer,slot,address,
                                packetPrimary.deviceAddress(),plan,tileFirst,pixels);
                        else GpuSceneBoundaryTraceCommands.record(command,stack,producer,slot,address,plan,tileFirst,pixels,true);
                    }
                };
                if(finalConsumer==null)storage.produceThenExchangeBlocking(produce,resolve,continuation);
                else {
                    storage.produceThenConsumeBlocking(produce,resolve,
                            (semaphore,acquire)->finalConsumer.submit(prepared,semaphore,acquire));
                    // The final fence covers CUDA kernel + pinned copy + external signal + Vulkan consumption.
                    // Validate before the submitter commits/publishes this frame slot.
                    for(int j=0;j<4;j++)statistics[j]=deferredStatistics.getInt(j*4L);
                    diagnosticQueryPaths.add(paths);diagnosticBoundaryCrossings.add(statistics[2]);
                    if(statistics[0]!=0||statistics[3]!=paths)
                        throw new RendererException("incomplete native boundary packet; final medium output was not published");
                }
                int rounds=1;
                while(statistics[1]==0 && statistics[0]>0) {
                    if(rounds++>frame.mediumTransport().maxEvents())
                        throw new RendererException("native boundary continuation did not obey the frame event budget");
                    storage.exchangeBlocking(resolve,continuation);
                }
                if(statistics[1]!=0) throw new RendererException("native boundary continuation rejected "+statistics[1]
                        +" inconsistent path states; final medium output was not published"+failureDetail[0]);
            buildPending=false; first+=pixels;
        }
        }
        uploadedAssets=scene.assets;
        if(uploadAssets)diagnosticAssetUploads.increment();
        long primary=packet?packetPrimary.deviceAddress():address+plan.primaryOffset();
        long trans=packet?address+plan.transmittanceOffset():address+plan.transmittanceOffset();
        // The low aligned bit selects the private terminal-radiance ABI; the shader masks it before dereference.
        return new CudaCameraMediumManager.Prepared(primary,trans|(packet&&terminalRadiance()?2L:0L),
                address+plan.emissionOffset(),plan.pixels(),plan.samples(),packet);
    }

    /** Read only after a rejected packet, while CUDA still owns the external allocation. */
    private String packetFailureDetail(CudaMediumRuntime.Slice input,CudaBoundaryMediumAbi.Plan plan,int first,int paths,int width) {
        try(Arena arena=Arena.ofConfined()) {
            MemorySegment output=arena.allocate((long)paths*16,16);
            cuda.download(input.slice(plan.tileTransmittanceOffset(first),output.byteSize()),output);
            for(int i=0;i<paths;i++) {
                float marker=output.get(ValueLayout.JAVA_FLOAT,(long)i*16+12);
                if(marker>=0)continue;
                int code=(int)-marker;
                MemorySegment packetData=arena.allocate(plan.packetPathBytes(),16);
                cuda.download(input.slice(plan.stateOffset()+(long)i*plan.packetPathBytes(),packetData.byteSize()),packetData);
                ByteBuffer data=packetData.asByteBuffer().order(ByteOrder.nativeOrder());
                int pixel=first+i/plan.samples();
                StringBuilder detail=new StringBuilder("; packet pixel=(").append(pixel%width).append(',')
                        .append(pixel/width).append(") sample=").append(i%plan.samples())
                        .append(" status=").append(code/256).append(" event=").append(code%256).append(" events=[");
                int count=Math.min(data.getInt(12),plan.packetCapacity());
                for(int j=0;j<count;j++) {
                    if(j>0)detail.append(',');
                    int event=data.getInt(20+j*8);
                    detail.append(data.getFloat(16+j*8)).append(':').append(event<0?"enter":"exit")
                            .append(':').append(event&0x7fffffff);
                }
                return detail.append(']').toString();
            }
            return "; no failure marker found";
        }
    }

    private void ensurePacketPrimary(CudaBoundaryMediumAbi.Plan plan) {
        long bytes=Math.multiplyExact((long)plan.pixels()*plan.samples(),CudaBoundaryMediumAbi.PRIMARY_BYTES);
        if(packetPrimary!=null&&packetPrimary.sizeBytes()>=bytes)return;
        if(packetPrimary!=null){drain();packetPrimary.close();}
        packetPrimary=RtGpuBuffer.createDeviceAddressBuffer(device.device(),device.allocator(),bytes,
                org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
    }

    private void uploadMetadata(org.lwjgl.vulkan.VkCommandBuffer command,MemoryStack stack,ByteBuffer data,int bytes) {
        for(int offset=0;offset<bytes;) {
            int size=Math.min(65_536,bytes-offset);
            ByteBuffer chunk=data.duplicate(); chunk.position(offset).limit(offset+size);
            VK10.vkCmdUpdateBuffer(command,storage.buffer(),offset,chunk.slice()); offset+=size;
        }
        var barrier=VkBufferMemoryBarrier.calloc(1,stack).sType$Default()
                .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT|VK10.VK_ACCESS_SHADER_WRITE_BIT)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .buffer(storage.buffer()).offset(0).size(bytes);
        VK10.vkCmdPipelineBarrier(command,VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,0,null,barrier,null);
        diagnosticUploadedBytes.add(bytes);
    }

    private void ensureCapacity(long bytes) {
        if(storage!=null && storage.byteSize()>=bytes) return;
        long residentBefore=cuda.residentBytes(),residentLimit=cuda.maxResidentBytes();
        if(Math.addExact(bytes,ALLOCATION_ALLOWANCE)>residentLimit)
            throw unsupported("the complete native boundary frame exceeds the CUDA resident budget; paths are never truncated");
        uploadedAssets=null;
        if(storage!=null) {drain();storage.close();storage=null;}
        long residentAfterRetire=cuda.residentBytes();
        if(Boolean.getBoolean("top.ceroxe.rt.medium.cuda.boundaryDiagnostics"))
            System.out.println("NativeBoundaryStorage requestBytes="+bytes+" alignmentAllowance="+ALLOCATION_ALLOWANCE
                    +" residentLimit="+residentLimit+" residentBefore="+residentBefore
                    +" residentAfterRetire="+residentAfterRetire+" availableBytes="+(residentLimit-residentAfterRetire));
        storage=CudaSharedRayFrame.storage(device,cuda,bytes,residentLimit-residentAfterRetire);
        if(Boolean.getBoolean("top.ceroxe.rt.medium.cuda.boundaryDiagnostics"))
            System.out.println("NativeBoundaryStorage allocationBytes="+storage.allocationBytes()
                    +" residentAfter="+cuda.residentBytes()+" remainingBytes="+(residentLimit-cuda.residentBytes()));
    }

    private void drain() {
        int result=VulkanQueueHostSync.waitIdle(device.frameQueue());
        if(result!=VK10.VK_SUCCESS) throw new IllegalStateException("drain native boundary consumers: VkResult="+result);
    }
    private void checkOpen() {if(closed) throw new IllegalStateException("native boundary manager is closed");}
    private <T>T onNative(Callable<T> operation) {
        int wallTask=OpticalWallTimeline.enter(OpticalWallTimeline.Stage.BOUNDARY_MEDIUM_NATIVE);
        try {
        var submitted=nativeThread.submit(OpticalWallTimeline.awaited(operation)); boolean interrupted=false;
        try {for(;;) try {return submitted.get();} catch(InterruptedException ignored) {interrupted=true;}
            catch(ExecutionException failure) {if(failure.getCause() instanceof RuntimeException runtime) throw runtime;
                if(failure.getCause() instanceof Error error) throw error;
                throw new IllegalStateException("native boundary operation failed",failure.getCause());}
        } finally {if(interrupted) Thread.currentThread().interrupt();}

        }finally{OpticalWallTimeline.leave(wallTask);}
    }
    private static FrameValidationException unsupported(String message) {
        return new FrameValidationException(FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,message);
    }
    @Override public synchronized void close() {
        if(closed) return;
        try {onNative(()->{
            Throwable failure=null;
            try {drain();} catch(RuntimeException|Error exception) {failure=exception;}
            if(storage!=null) try {storage.close();storage=null;} catch(RuntimeException|Error exception) {
                if(failure==null) failure=exception;else failure.addSuppressed(exception);}
            if(packetPrimary!=null) try {packetPrimary.close();packetPrimary=null;} catch(RuntimeException|Error exception) {
                if(failure==null) failure=exception;else failure.addSuppressed(exception);
            }
            try {cuda.close();} catch(RuntimeException|Error exception) {
                if(failure==null) failure=exception;else failure.addSuppressed(exception);}
            if(failure instanceof RuntimeException runtime) throw runtime;
            if(failure instanceof Error error) throw error;
            return null;
        });} finally {closed=true;nativeThread.shutdown();}
    }
    private record NativeState(CudaMediumRuntime cuda,CudaMediumRuntime.BatchKernel reset,CudaMediumRuntime.BatchKernel step) { }
    private record FrameAssets(java.util.List<CudaBoundaryMediumAbi.Boundary> boundaries,
            CudaMediumDensityAbi.Scene density,int sceneBytes) { }

    static final class FrameScene {
        private final RenderFrameRequest frame;
        private final CudaBoundaryMediumAbi.Plan plan;
        private final byte[] metadata;
        private final FrameAssets assets;
        FrameScene(RenderFrameRequest frame,CudaBoundaryMediumAbi.Plan plan,
                java.util.List<CudaBoundaryMediumAbi.Boundary> boundaries,int[] containing,CudaMediumDensityAbi.Scene density) {
            this.frame=Objects.requireNonNull(frame,"frame");this.plan=Objects.requireNonNull(plan,"plan");
            assets=new FrameAssets(java.util.List.copyOf(boundaries),Objects.requireNonNull(density,"density"),plan.sceneBytes());
            metadata=CudaBoundaryMediumAbi.scenePrefix(plan,frame,boundaries,containing,0,Math.min(plan.tilePixels(),plan.pixels()),density);
        }
        RenderFrameRequest frame() {return frame;}
        CudaBoundaryMediumAbi.Plan plan() {return plan;}
    }
}
