package top.ceroxe.rt.renderer.backend.vulkan;

import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.util.Objects;
import java.util.function.LongFunction;
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
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaHeterogeneousMultipleScatteringAbi;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaHeterogeneousMultipleScatteringSource;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaBoundaryScatteringAbi;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaBoundaryScatteringSource;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaBoundaryMediumAbi;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaMediumDensityAbi;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaMultipleScatteringAbi;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaMultipleScatteringSource;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaMediumRuntime;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaSharedRayFrame;
import top.ceroxe.rt.renderer.rt.acceleration.RtTlasBuildPlan;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.device.VulkanQueueHostSync;
import top.ceroxe.rt.renderer.rt.pipeline.GpuSceneRayTracingPipeline;

/** Bounded blocking continuation; only four aggregate counters ever cross back to the host. */
final class CudaMultipleScatteringManager implements AutoCloseable {
    static final LongAdder diagnosticSecondaryPaths=new LongAdder(),diagnosticQueryPaths=new LongAdder(),diagnosticCollisions=new LongAdder();
    private final ExecutorService nativeThread=Executors.newSingleThreadExecutor(task->{
        Thread thread=new Thread(task,"RTRenderer-native-multiple-scattering");thread.setDaemon(true);return thread;
    });
    private final VulkanDeviceRuntime device;
    private final CudaMediumRuntime cuda;
    private final CudaMediumRuntime.BatchKernel initialize,reset,step,reduce;
    private CudaSharedRayFrame storage;
    private HeterogeneousKernels heterogeneous;
    private BoundaryScatteringKernels boundaryScattering;
    private CudaMediumDensityAbi.Scene uploadedDensity;
    private boolean closed;
    CudaMultipleScatteringManager(VulkanDeviceRuntime device) {
        this.device=Objects.requireNonNull(device,"device");
        NativeState initialized;
        try {initialized=onNative(()->{
            var opened=CudaMediumRuntime.open(Integer.getInteger("top.ceroxe.rt.medium.cuda.deviceOrdinal",0));
            try {String source=CudaMultipleScatteringSource.source();return new NativeState(opened,
                    opened.compileBatchKernel(source,"camera_multiple_init"),opened.compileBatchKernel(source,"camera_multiple_reset"),
                    opened.compileBatchKernel(source,"camera_multiple_step"),opened.compileBatchKernel(source,"camera_multiple_reduce"));}
            catch(RuntimeException|Error failure){try{opened.close();}catch(RuntimeException closeFailure){failure.addSuppressed(closeFailure);}throw failure;}
        });}catch(RuntimeException|Error failure){nativeThread.shutdown();throw failure;}
        cuda=initialized.cuda();initialize=initialized.initialize();reset=initialized.reset();step=initialized.step();reduce=initialized.reduce();
    }
    static long requiredBytes(int width,int height,int samples,int steps) {
        try{return Math.addExact(CudaMultipleScatteringAbi.plan(width,height,samples,steps,residentLimit()).bytes(),CudaMultipleScatteringAbi.ALLOCATION_ALLOWANCE);}
        catch(ArithmeticException|IllegalArgumentException invalid){throw unsupported(invalid.getMessage());}
    }
    static CudaHeterogeneousMultipleScatteringAbi.FrameScene scene(RenderFrameRequest frame,int width,int height,
            int lightUpperBound,LongFunction<TextureAsset> textures,CudaMediumDensityAbi.Cache cache) {
        try{return CudaHeterogeneousMultipleScatteringAbi.scene(frame,width,height,lightUpperBound,textures,cache,residentLimit());}
        catch(ArithmeticException|IllegalArgumentException invalid){throw unsupported(invalid.getMessage());}
    }
    static CudaBoundaryScatteringAbi.FrameScene boundaryScatteringScene(RenderFrameRequest frame,int width,int height,
            int lightUpperBound,MediumSceneIndex.NativeScene nativeScene,LongFunction<TextureAsset> textures,
            CudaMediumDensityAbi.Cache cache) {
        try {
            var boundaries=nativeScene.boundaries().stream().map(entry->new CudaBoundaryMediumAbi.Boundary(
                    entry.slot(),entry.boundary(),entry.objectToWorld())).toList();
            return CudaBoundaryScatteringAbi.scene(frame,width,height,lightUpperBound,boundaries,nativeScene.containing(),
                    textures,cache,residentLimit(),nativeScene.packetCapacity(frame.mediumTransport().maxEvents()));
        } catch(ArithmeticException|IllegalArgumentException invalid){throw unsupported(invalid.getMessage());}
    }
    static long requiredBytes(CudaBoundaryScatteringAbi.FrameScene scene) {
        return Math.addExact(scene.plan().bytes(),CudaBoundaryScatteringAbi.ALLOCATION_ALLOWANCE);
    }
    synchronized long requiredGrowthBytes(CudaBoundaryScatteringAbi.FrameScene scene) {
        checkOpen();return onNative(()->storage!=null&&storage.byteSize()>=scene.plan().bytes()?0:requiredBytes(scene));
    }
    synchronized CudaCameraMediumManager.Prepared prepareBoundaryScattering(CudaBoundaryScatteringAbi.FrameScene scene,
            GpuSceneRayTracingPipeline queryPipeline,int slot,RtTlasBuildPlan buildPlan) {
        checkOpen();return onNative(()->prepareBoundaryScatteringOnNative(scene,queryPipeline,slot,buildPlan));
    }
    static long requiredBytes(CudaHeterogeneousMultipleScatteringAbi.FrameScene scene) {
        return Math.addExact(scene.plan().bytes(),CudaMultipleScatteringAbi.ALLOCATION_ALLOWANCE);
    }
    synchronized long requiredGrowthBytes(CudaHeterogeneousMultipleScatteringAbi.FrameScene scene) {
        checkOpen();return onNative(()->storage!=null&&storage.byteSize()>=scene.plan().bytes()
                &&(scene.plan().primaryOffset()<scene.plan().emissionOffset()||storage.byteSize()==scene.plan().bytes())?0:requiredBytes(scene));
    }
    synchronized CudaCameraMediumManager.Prepared prepare(CudaHeterogeneousMultipleScatteringAbi.FrameScene scene,
            GpuSceneRayTracingPipeline primaryProducer,GpuSceneRayTracingPipeline multipleProducer,
            int slot,RtTlasBuildPlan buildPlan) {
        checkOpen();return onNative(()->prepareHeterogeneous(scene,primaryProducer,multipleProducer,slot,buildPlan));
    }
    synchronized CudaPbrMediumManager.RadianceContribution prepareRadianceContribution(
            CudaHeterogeneousMultipleScatteringAbi.FrameScene scene,GpuSceneRayTracingPipeline primaryProducer,
            GpuSceneRayTracingPipeline multipleProducer,int slot,RtTlasBuildPlan buildPlan) {
        checkOpen();return onNative(()->{
            prepareHeterogeneous(scene,primaryProducer,multipleProducer,slot,buildPlan);
            return new CudaPbrMediumManager.RadianceContribution(storage.buffer(),scene.plan().emissionOffset(),
                    Math.multiplyExact((long)scene.plan().pixels()*scene.plan().samples(),16),true);
        });
    }
    synchronized long requiredGrowthBytes(CudaMultipleScatteringAbi.Plan plan) {
        checkOpen();return onNative(()->storage!=null&&storage.byteSize()>=plan.bytes()
                &&(plan.primaryOffset()<plan.emissionOffset()||storage.byteSize()==plan.bytes())?0:
                Math.addExact(plan.bytes(),CudaMultipleScatteringAbi.ALLOCATION_ALLOWANCE));
    }
    synchronized CudaPbrMediumManager.RadianceContribution prepareRadianceContribution(RenderFrameRequest frame,
            CudaMultipleScatteringAbi.Plan plan,GpuSceneRayTracingPipeline primaryProducer,
            GpuSceneRayTracingPipeline directLightProducer,GpuSceneRayTracingPipeline multipleProducer,int slot,
            RtTlasBuildPlan buildPlan) {
        checkOpen();return onNative(()->{
            prepareOnNative(frame,plan,primaryProducer,directLightProducer,multipleProducer,slot,buildPlan);
            return new CudaPbrMediumManager.RadianceContribution(storage.buffer(),plan.emissionOffset(),
                    Math.multiplyExact((long)plan.pixels()*plan.samples(),16),true);
        });
    }
    synchronized long requiredGrowthBytes(int width,int height,int samples,int steps) {
        checkOpen();long bytes=requiredBytes(width,height,samples,steps);
        return onNative(()->storage!=null&&storage.byteSize()>=bytes-CudaMultipleScatteringAbi.ALLOCATION_ALLOWANCE?0:bytes);
    }
    synchronized CudaCameraMediumManager.Prepared prepare(RenderFrameRequest frame,int width,int height,
            GpuSceneRayTracingPipeline primaryProducer,GpuSceneRayTracingPipeline directLightProducer,
            GpuSceneRayTracingPipeline multipleProducer,int slot,RtTlasBuildPlan buildPlan) {
        checkOpen();return onNative(()->prepareOnNative(frame,width,height,primaryProducer,directLightProducer,multipleProducer,slot,buildPlan));
    }
    private CudaCameraMediumManager.Prepared prepareOnNative(RenderFrameRequest frame,int width,int height,
            GpuSceneRayTracingPipeline primaryProducer,GpuSceneRayTracingPipeline directLightProducer,
            GpuSceneRayTracingPipeline multipleProducer,int slot,RtTlasBuildPlan buildPlan) {
        CudaMultipleScatteringAbi.Plan plan;
        try {plan=CudaMultipleScatteringAbi.plan(width,height,frame.antiAliasing().samplesPerPixel(),
                frame.mediumTransport().scatteringSteps(),Math.min(cuda.maxResidentBytes(),CudaMultipleScatteringAbi.RESIDENT_CAP));}
        catch(ArithmeticException|IllegalArgumentException invalid){throw unsupported(invalid.getMessage());}
        return prepareOnNative(frame,plan,primaryProducer,directLightProducer,multipleProducer,slot,buildPlan);
    }
    private CudaCameraMediumManager.Prepared prepareOnNative(RenderFrameRequest frame,CudaMultipleScatteringAbi.Plan plan,
            GpuSceneRayTracingPipeline primaryProducer,GpuSceneRayTracingPipeline directLightProducer,
            GpuSceneRayTracingPipeline multipleProducer,int slot,RtTlasBuildPlan buildPlan) {
        uploadedDensity=null;
        ensureCapacity(plan.bytes(),plan.primaryOffset()>plan.emissionOffset());long address=storage.deviceAddress();
        try(Arena arena=Arena.ofConfined()) {
            var counters=arena.allocate(CudaMultipleScatteringAbi.STATISTICS_BYTES,16);
            for(int first=0;first<plan.pixels();) {
                int tileFirst=first,pixels=Math.min(plan.tilePixels(),plan.pixels()-first),paths=pixels*plan.samples(),secondary=plan.secondaryPaths(pixels);
                boolean initial=first==0;int[] statistics={secondary,0,0,0};
                byte[] header=CudaMultipleScatteringAbi.header(plan,frame,tileFirst,pixels);
                java.util.function.Consumer<CudaMediumRuntime.Slice> collect=shared->{
                    cuda.download(shared.slice(CudaMultipleScatteringAbi.STATISTICS_OFFSET,CudaMultipleScatteringAbi.STATISTICS_BYTES),counters);
                    for(int j=0;j<4;j++)statistics[j]=counters.get(ValueLayout.JAVA_INT,j*4L);
                    diagnosticCollisions.add(statistics[3]);
                    if(statistics[0]==0&&statistics[1]==0){
                        cuda.launchBlocking(reduce,shared,paths,1);
                        cuda.download(shared.slice(CudaMultipleScatteringAbi.STATISTICS_OFFSET,CudaMultipleScatteringAbi.STATISTICS_BYTES),counters);
                        statistics[1]=counters.get(ValueLayout.JAVA_INT,4);
                    }
                };
                java.util.function.Consumer<org.lwjgl.vulkan.VkCommandBuffer> nextQuery=command->{
                    if(statistics[0]>0&&statistics[1]==0)try(MemoryStack stack=MemoryStack.stackPush()){
                        long offsets=Integer.toUnsignedLong(plan.stateOffset())|Integer.toUnsignedLong(plan.primaryOffset())<<32;
                        multipleProducer.recordPrimaryQueueTrace(command,stack,slot,address,tileFirst,pixels,offsets,16);
                        diagnosticQueryPaths.add(statistics[0]);
                    }
                };
                storage.produceThenExchangeBlocking(command->{
                    try(MemoryStack stack=MemoryStack.stackPush()) {
                        VK10.vkCmdUpdateBuffer(command,storage.buffer(),0,stack.malloc(header.length).put(header).flip());
                        if(initial&&buildPlan!=null)buildPlan.record(command,stack);
                        if(plan.primaryOffset()>plan.emissionOffset())
                            primaryProducer.recordPrimaryQueueTrace(command,stack,slot,address+plan.primaryOffset(),tileFirst,pixels);
                        else if(initial)
                            primaryProducer.recordPrimaryQueueTrace(command,stack,slot,address+plan.primaryOffset(),0,plan.pixels());
                        dependency(command,stack);
                        long sourceOffsets=Integer.toUnsignedLong(plan.sourceOffset())|Integer.toUnsignedLong(plan.primaryOffset())<<32;
                        directLightProducer.recordPrimaryQueueTrace(command,stack,slot,address,tileFirst,pixels,sourceOffsets);
                        dependency(command,stack);
                        long stateOffsets=Integer.toUnsignedLong(plan.stateOffset())|Integer.toUnsignedLong(plan.primaryOffset())<<32;
                        multipleProducer.recordPrimaryQueueTrace(command,stack,slot,address,tileFirst,pixels,stateOffsets,0);
                    }
                },shared->{cuda.launchBlocking(initialize,shared,paths,1);collect.accept(shared);},nextQuery);
                diagnosticSecondaryPaths.add(secondary);
                int rounds=0;
                while(statistics[0]>0&&statistics[1]==0) {
                    if(++rounds>frame.mediumTransport().maxEvents()+frame.mediumTransport().scatteringBounces()+1)
                        throw new RendererException("native multiple scattering exceeded the public event/bounce budget");
                    storage.exchangeBlocking(shared->{
                        cuda.launchBlocking(reset,shared,1,1);cuda.launchBlocking(step,shared,secondary,1);collect.accept(shared);
                    },nextQuery);
                }
                if(statistics[1]!=0)throw new RendererException("native multiple scattering rejected "+statistics[1]+" inconsistent states; final output was not published");
                first+=pixels;
            }
        }
        return new CudaCameraMediumManager.Prepared(address+plan.primaryOffset(),address+plan.transmittanceOffset(),address+plan.emissionOffset(),plan.pixels(),plan.samples());
    }
    private HeterogeneousKernels heterogeneousKernels() {
        if(heterogeneous!=null)return heterogeneous;
        String source=CudaHeterogeneousMultipleScatteringSource.source();
        heterogeneous=new HeterogeneousKernels(cuda.compileBatchKernel(source,"heterogeneous_multiple_prepare"),
                cuda.compileBatchKernel(source,"heterogeneous_multiple_connections"),cuda.compileBatchKernel(source,"heterogeneous_multiple_start"),
                cuda.compileBatchKernel(source,"heterogeneous_multiple_reset"),cuda.compileBatchKernel(source,"heterogeneous_multiple_step"),
                cuda.compileBatchKernel(source,"heterogeneous_multiple_reduce"));
        return heterogeneous;
    }
    private BoundaryScatteringKernels boundaryScatteringKernels() {
        if(boundaryScattering!=null)return boundaryScattering;
        String source=CudaBoundaryScatteringSource.source();
        boundaryScattering=new BoundaryScatteringKernels(cuda.compileBatchKernel(source,"boundary_scatter_reset"),
                cuda.compileBatchKernel(source,"boundary_scatter_prepare"),cuda.compileBatchKernel(source,"boundary_scatter_connections"),
                cuda.compileBatchKernel(source,"boundary_scatter_continue"),cuda.compileBatchKernel(source,"boundary_scatter_step"),
                cuda.compileBatchKernel(source,"boundary_scatter_reduce"));
        return boundaryScattering;
    }
    private CudaCameraMediumManager.Prepared prepareBoundaryScatteringOnNative(CudaBoundaryScatteringAbi.FrameScene scene,
            GpuSceneRayTracingPipeline queryPipeline,int slot,RtTlasBuildPlan buildPlan) {
        var plan=scene.plan();ensureCapacity(plan.bytes());var kernels=boundaryScatteringKernels();long address=storage.deviceAddress();
        boolean uploadDensity=uploadedDensity!=scene.density();
        if(uploadDensity)uploadedDensity=null;
        try(Arena arena=Arena.ofConfined()) {
            var counter=arena.allocate(CudaBoundaryScatteringAbi.STATISTICS_BYTES,16);
            for(int first=0;first<plan.pixels();) {
                int tileFirst=first,pixels=Math.min(plan.tilePixels(),plan.pixels()-first),paths=pixels*plan.samples(),states=paths;
                int[] stats={states,0,0,0};boolean initial=first==0,full=initial&&uploadDensity;
                java.util.function.BiConsumer<org.lwjgl.vulkan.VkCommandBuffer,CudaBoundaryScatteringAbi.FrameScene> camera=(command,unused)->{
                    try(MemoryStack stack=MemoryStack.stackPush()) {
                        byte[] metadata=full?scene.metadata(tileFirst,pixels,CudaBoundaryScatteringAbi.QUERY_CAMERA,0)
                                :scene.metadataPrefix(tileFirst,pixels,CudaBoundaryScatteringAbi.QUERY_CAMERA,0);
                        uploadBoundaryScattering(command,stack,metadata,full);
                        if(initial&&buildPlan!=null)buildPlan.record(command,stack);
                        queryPipeline.recordNativeBoundaryScattering(command,stack,slot,address,0,paths);
                    }
                };
                java.util.function.BiConsumer<org.lwjgl.vulkan.VkCommandBuffer,Integer> lights=(command,chunk)->{
                    try(MemoryStack stack=MemoryStack.stackPush()) {
                        uploadBoundaryScattering(command,stack,scene.metadataPrefix(tileFirst,pixels,CudaBoundaryScatteringAbi.QUERY_LIGHTS,chunk),false);
                        dependency(command,stack);queryPipeline.recordNativeBoundaryScattering(command,stack,slot,address,0,states*plan.connections());
                        diagnosticQueryPaths.add(states*plan.connections());
                    }
                };
                storage.produceThenExchangeBlocking(command->camera.accept(command,scene),shared->{
                    cuda.launchBlocking(kernels.prepare(),shared,paths,1);
                    cuda.download(shared.slice(CudaBoundaryScatteringAbi.STATISTICS_OFFSET,CudaBoundaryScatteringAbi.STATISTICS_BYTES),counter);
                    for(int j=0;j<4;j++)stats[j]=counter.get(ValueLayout.JAVA_INT,j*4L);
                },command->{if(stats[0]>0&&stats[1]==0)lights.accept(command,0);});
                diagnosticSecondaryPaths.add(states);
                if(stats[1]!=0)throw new RendererException("native boundary scattering rejected "+stats[1]+" invalid camera paths; HDR was not published");
                if(stats[0]==0) {
                    storage.exchangeBlocking(shared->{cuda.launchBlocking(kernels.reduce(),shared,states,1);
                        cuda.download(shared.slice(CudaBoundaryScatteringAbi.STATISTICS_OFFSET,CudaBoundaryScatteringAbi.STATISTICS_BYTES),counter);
                        stats[1]=counter.get(ValueLayout.JAVA_INT,4);},command->{});
                    if(stats[1]!=0)throw new RendererException("native boundary scattering rejected "+stats[1]+" invalid terminal paths; HDR was not published");
                    first+=pixels;
                    continue;
                }
                int rounds=0;
                while(true) {
                    // Each reservoir/exhaustive light chunk is evaluated before HG continuation.
                    for(int chunk=0;chunk<scene.chunks();chunk++) {
                        int nextChunk=chunk+1;boolean last=nextChunk==scene.chunks();
                        storage.exchangeBlocking(shared->{cuda.launchBlocking(kernels.connections(),shared,states,1);
                            if(last)cuda.launchBlocking(kernels.continuePath(),shared,states,1);},command->{
                            if(!last)lights.accept(command,nextChunk);
                            else if(scene.frame().mediumTransport().scatteringBounces()>0)try(MemoryStack stack=MemoryStack.stackPush()) {
                                uploadBoundaryScattering(command,stack,scene.metadataPrefix(tileFirst,pixels,CudaBoundaryScatteringAbi.QUERY_GEOMETRY,0),false);
                                dependency(command,stack);queryPipeline.recordNativeBoundaryScattering(command,stack,slot,address,0,states);
                                diagnosticQueryPaths.add(states);
                            }});
                    }
                    storage.exchangeBlocking(shared->{
                        cuda.launchBlocking(kernels.reset(),shared,1,1);
                        cuda.launchBlocking(kernels.step(),shared,states,1);
                        cuda.download(shared.slice(CudaBoundaryScatteringAbi.STATISTICS_OFFSET,CudaBoundaryScatteringAbi.STATISTICS_BYTES),counter);
                        for(int j=0;j<4;j++)stats[j]=counter.get(ValueLayout.JAVA_INT,j*4L);
                        if(stats[1]==0&&stats[0]==0){cuda.launchBlocking(kernels.reduce(),shared,states,1);
                            cuda.download(shared.slice(CudaBoundaryScatteringAbi.STATISTICS_OFFSET,CudaBoundaryScatteringAbi.STATISTICS_BYTES),counter);
                            stats[1]=counter.get(ValueLayout.JAVA_INT,4);}
                    },command->{if(stats[0]>0&&stats[1]==0)lights.accept(command,0);});
                    if(stats[1]!=0)throw new RendererException("native boundary scattering rejected "+stats[1]+" invalid paths; HDR was not published");
                    if(stats[0]==0)break;
                    if(++rounds>scene.frame().mediumTransport().maxEvents()+scene.frame().mediumTransport().scatteringBounces()+2)
                        throw new RendererException("native boundary scattering exceeded event/bounce budget");
                }
                first+=pixels;
            }
            uploadedDensity=scene.density();
        }
        return new CudaCameraMediumManager.Prepared(address+plan.primaryOffset(),address+plan.transmittanceOffset(),
                address+plan.emissionOffset(),plan.pixels(),plan.samples());
    }
    private void uploadBoundaryScattering(org.lwjgl.vulkan.VkCommandBuffer command,MemoryStack stack,byte[] data,boolean full) {
        var writable=VkBufferMemoryBarrier.calloc(1,stack).sType$Default()
                .srcAccessMask(VK10.VK_ACCESS_MEMORY_READ_BIT|VK10.VK_ACCESS_MEMORY_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .buffer(storage.buffer()).offset(0).size(storage.byteSize());
        VK10.vkCmdPipelineBarrier(command,VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,0,null,writable,null);
        int mode=java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.nativeOrder()).getInt(212);
        boolean preserveStatistics=!full&&mode!=CudaBoundaryScatteringAbi.QUERY_CAMERA;
        for(int offset=0;offset<data.length;){
            if(preserveStatistics&&offset>=CudaBoundaryScatteringAbi.STATISTICS_OFFSET
                    &&offset<CudaBoundaryScatteringAbi.STATISTICS_OFFSET+CudaBoundaryScatteringAbi.STATISTICS_BYTES) {
                offset=CudaBoundaryScatteringAbi.STATISTICS_OFFSET+CudaBoundaryScatteringAbi.STATISTICS_BYTES;
                continue;
            }
            int bytes=Math.min(65_536,data.length-offset);
            if(preserveStatistics&&offset<CudaBoundaryScatteringAbi.STATISTICS_OFFSET
                    &&offset+bytes>CudaBoundaryScatteringAbi.STATISTICS_OFFSET)
                bytes=CudaBoundaryScatteringAbi.STATISTICS_OFFSET-offset;
            var part=stack.malloc(bytes).put(data,offset,bytes).flip();
            VK10.vkCmdUpdateBuffer(command,storage.buffer(),offset,part);offset+=bytes;
        }
        dependency(command,stack);
    }
    private CudaCameraMediumManager.Prepared prepareHeterogeneous(CudaHeterogeneousMultipleScatteringAbi.FrameScene scene,
            GpuSceneRayTracingPipeline primaryProducer,GpuSceneRayTracingPipeline multipleProducer,int slot,RtTlasBuildPlan buildPlan) {
        var plan=scene.plan();ensureCapacity(plan.bytes(),plan.primaryOffset()>plan.emissionOffset());var kernels=heterogeneousKernels();long address=storage.deviceAddress();
        boolean changed=uploadedDensity!=scene.density();if(changed)uploadedDensity=null;
        try(Arena arena=Arena.ofConfined()) {
            var counters=arena.allocate(CudaMultipleScatteringAbi.STATISTICS_BYTES,16);
            for(int first=0;first<plan.pixels();) {
                int tileFirst=first,pixels=Math.min(plan.tilePixels(),plan.pixels()-first),paths=pixels*plan.samples(),secondary=plan.secondaryPaths(pixels);
                boolean initial=first==0,full=initial&&changed;int[] statistics={secondary,0,0,0};
                java.util.function.Consumer<CudaMediumRuntime.Slice> collect=shared->{
                    cuda.download(shared.slice(CudaMultipleScatteringAbi.STATISTICS_OFFSET,CudaMultipleScatteringAbi.STATISTICS_BYTES),counters);
                    for(int j=0;j<4;j++)statistics[j]=counters.get(ValueLayout.JAVA_INT,j*4L);
                    diagnosticCollisions.add(statistics[3]);
                    if(statistics[0]==0&&statistics[1]==0){cuda.launchBlocking(kernels.reduce(),shared,paths,1);
                        cuda.download(shared.slice(CudaMultipleScatteringAbi.STATISTICS_OFFSET,CudaMultipleScatteringAbi.STATISTICS_BYTES),counters);
                        statistics[1]=counters.get(ValueLayout.JAVA_INT,4);}
                };
                java.util.function.BiConsumer<org.lwjgl.vulkan.VkCommandBuffer,Long> query=(command,mode)->{
                    try(MemoryStack stack=MemoryStack.stackPush()) {
                        multipleProducer.recordPrimaryQueueTrace(command,stack,slot,address,tileFirst,pixels,0,mode);
                        diagnosticQueryPaths.add(secondary);
                    }
                };
                byte[] metadata=full?scene.metadata(tileFirst,pixels,0,0):scene.header(tileFirst,pixels,0,0);
                storage.produceThenExchangeBlocking(command->{try(MemoryStack stack=MemoryStack.stackPush()) {
                    uploadHeterogeneous(command,stack,metadata,full);
                    if(!full)VK10.vkCmdFillBuffer(command,storage.buffer(),CudaMultipleScatteringAbi.STATISTICS_OFFSET,CudaMultipleScatteringAbi.STATISTICS_BYTES,0);
                    if(initial&&buildPlan!=null)buildPlan.record(command,stack);
                    if(plan.primaryOffset()>plan.emissionOffset())
                        primaryProducer.recordPrimaryQueueTrace(command,stack,slot,address+plan.primaryOffset(),tileFirst,pixels);
                    else if(initial)
                        primaryProducer.recordPrimaryQueueTrace(command,stack,slot,address+plan.primaryOffset(),0,plan.pixels());
                    dependency(command,stack);query.accept(command,0L);
                }},shared->cuda.launchBlocking(kernels.prepare(),shared,paths,1),command->query.accept(command,32L));
                diagnosticSecondaryPaths.add(secondary);
                boolean firstOrder=true;int rounds=0;
                do {
                    boolean starting=firstOrder;int phase=starting?0:1;
                    for(int chunk=0;chunk<scene.chunks();chunk++) {
                        int nextChunk=chunk+1;boolean last=nextChunk==scene.chunks();
                        storage.exchangeBlocking(shared->{
                            cuda.launchBlocking(kernels.connections(),shared,secondary,1);
                            if(last){cuda.launchBlocking(kernels.reset(),shared,1,1);
                                cuda.launchBlocking(starting?kernels.start():kernels.step(),shared,secondary,1);collect.accept(shared);}
                        },command->{try(MemoryStack stack=MemoryStack.stackPush()) {
                            if(!last){uploadHeterogeneousContinuation(command,stack,nextChunk*plan.connections(),phase);
                                query.accept(command,starting?32L:16L);}
                            else if(statistics[0]>0&&statistics[1]==0){
                                uploadHeterogeneousContinuation(command,stack,0,1);query.accept(command,16L);}
                        }});
                    }
                    firstOrder=false;
                    if(++rounds>scene.frame().mediumTransport().maxEvents()+scene.frame().mediumTransport().scatteringBounces()+2)
                        throw new RendererException("native heterogeneous multiple scattering exceeded public event/bounce budget");
                }while(statistics[0]>0&&statistics[1]==0);
                if(statistics[1]!=0)throw new RendererException("native heterogeneous multiple scattering rejected "+statistics[1]+" inconsistent states; HDR was not published");
                first+=pixels;
            }
            uploadedDensity=scene.density();
        }
        return new CudaCameraMediumManager.Prepared(address+plan.primaryOffset(),address+plan.transmittanceOffset(),
                address+plan.emissionOffset(),plan.pixels(),plan.samples());
    }
    private void uploadHeterogeneous(org.lwjgl.vulkan.VkCommandBuffer command,MemoryStack stack,byte[] metadata,boolean full) {
        transferDependency(command,stack);
        if(full)try(Arena upload=Arena.ofConfined()) {
            var data=upload.allocate(metadata.length,16).asByteBuffer();data.put(metadata);
            for(int at=0;at<metadata.length;){int bytes=Math.min(65536,metadata.length-at);var part=data.duplicate();part.position(at).limit(at+bytes);
                VK10.vkCmdUpdateBuffer(command,storage.buffer(),at,part.slice());at+=bytes;}
        }else {
            VK10.vkCmdUpdateBuffer(command,storage.buffer(),0,stack.malloc(112).put(metadata,0,112).flip());
            VK10.vkCmdUpdateBuffer(command,storage.buffer(),128,stack.malloc(CudaHeterogeneousMultipleScatteringAbi.HEADER_BYTES-128)
                    .put(metadata,128,CudaHeterogeneousMultipleScatteringAbi.HEADER_BYTES-128).flip());
        }
        dependency(command,stack);
    }
    private void uploadHeterogeneousContinuation(org.lwjgl.vulkan.VkCommandBuffer command,MemoryStack stack,int firstConnection,int phase) {
        transferDependency(command,stack);
        VK10.vkCmdUpdateBuffer(command,storage.buffer(),184,stack.malloc(8).putInt(firstConnection).putInt(phase).flip());
        dependency(command,stack);
    }
    private void transferDependency(org.lwjgl.vulkan.VkCommandBuffer command,MemoryStack stack) {
        var writable=VkBufferMemoryBarrier.calloc(1,stack).sType$Default()
                .srcAccessMask(VK10.VK_ACCESS_MEMORY_READ_BIT|VK10.VK_ACCESS_MEMORY_WRITE_BIT).dstAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .buffer(storage.buffer()).offset(0).size(storage.byteSize());
        VK10.vkCmdPipelineBarrier(command,VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,0,null,writable,null);
    }
    private void dependency(org.lwjgl.vulkan.VkCommandBuffer command,MemoryStack stack){
        var barrier=VkBufferMemoryBarrier.calloc(1,stack).sType$Default().srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT|VK10.VK_ACCESS_SHADER_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT|VK10.VK_ACCESS_SHADER_WRITE_BIT)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .buffer(storage.buffer()).offset(0).size(storage.byteSize());
        VK10.vkCmdPipelineBarrier(command,VK10.VK_PIPELINE_STAGE_TRANSFER_BIT|KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,0,null,barrier,null);
    }
    private void ensureCapacity(long bytes){ensureCapacity(bytes,false);}
    private void ensureCapacity(long bytes,boolean exactBudget){
        if(storage!=null&&storage.byteSize()>=bytes&&(!exactBudget||storage.byteSize()==bytes))return;
        if(bytes+CudaMultipleScatteringAbi.ALLOCATION_ALLOWANCE>Math.min(cuda.maxResidentBytes(),CudaMultipleScatteringAbi.RESIDENT_CAP))throw unsupported("complete multiple-scattering frame exceeds the CUDA resident cap");
        if(storage!=null){drain();storage.close();storage=null;}uploadedDensity=null;
        storage=CudaSharedRayFrame.storage(device,cuda,bytes,cuda.maxResidentBytes()-cuda.residentBytes());}
    private void drain(){int result=VulkanQueueHostSync.waitIdle(device.frameQueue());if(result!=VK10.VK_SUCCESS)throw new IllegalStateException("drain multiple-scattering consumers: VkResult="+result);}
    private static long residentLimit(){return Math.min(CudaMultipleScatteringAbi.RESIDENT_CAP,Long.getLong("top.ceroxe.rt.medium.cuda.maxResidentBytes",CudaMultipleScatteringAbi.RESIDENT_CAP));}
    private void checkOpen(){if(closed)throw new IllegalStateException("multiple-scattering manager is closed");}
    private <T>T onNative(Callable<T> operation){var submitted=nativeThread.submit(operation);boolean interrupted=false;
        try{for(;;)try{return submitted.get();}catch(InterruptedException ignored){interrupted=true;}catch(ExecutionException failure){
            if(failure.getCause() instanceof RuntimeException runtime)throw runtime;if(failure.getCause() instanceof Error error)throw error;
            throw new IllegalStateException("native multiple-scattering operation failed",failure.getCause());}}
        finally{if(interrupted)Thread.currentThread().interrupt();}}
    private static FrameValidationException unsupported(String reason){return new FrameValidationException(FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,reason);}
    @Override public synchronized void close(){if(closed)return;try{onNative(()->{Throwable failure=null;
        try{drain();}catch(RuntimeException|Error exception){failure=exception;}
        if(storage!=null)try{storage.close();storage=null;}catch(RuntimeException|Error exception){if(failure==null)failure=exception;else failure.addSuppressed(exception);}
        try{cuda.close();}catch(RuntimeException|Error exception){if(failure==null)failure=exception;else failure.addSuppressed(exception);}
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;return null;});}
        finally{closed=true;nativeThread.shutdown();}}
    private record NativeState(CudaMediumRuntime cuda,CudaMediumRuntime.BatchKernel initialize,CudaMediumRuntime.BatchKernel reset,
            CudaMediumRuntime.BatchKernel step,CudaMediumRuntime.BatchKernel reduce) { }
    private record HeterogeneousKernels(CudaMediumRuntime.BatchKernel prepare,CudaMediumRuntime.BatchKernel connections,
            CudaMediumRuntime.BatchKernel start,CudaMediumRuntime.BatchKernel reset,CudaMediumRuntime.BatchKernel step,CudaMediumRuntime.BatchKernel reduce) { }
    private record BoundaryScatteringKernels(CudaMediumRuntime.BatchKernel reset,CudaMediumRuntime.BatchKernel prepare,
            CudaMediumRuntime.BatchKernel connections,CudaMediumRuntime.BatchKernel continuePath,
            CudaMediumRuntime.BatchKernel step,CudaMediumRuntime.BatchKernel reduce) { }
}
