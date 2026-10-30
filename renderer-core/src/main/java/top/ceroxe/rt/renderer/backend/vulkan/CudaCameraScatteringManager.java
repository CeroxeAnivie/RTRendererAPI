package top.ceroxe.rt.renderer.backend.vulkan;

import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import top.ceroxe.rt.renderer.api.FrameValidationException;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.RendererException;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaCameraScatteringAbi;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaCameraScatteringSource;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaMediumRuntime;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaSharedRayFrame;
import top.ceroxe.rt.renderer.rt.acceleration.RtTlasBuildPlan;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.device.VulkanQueueHostSync;
import top.ceroxe.rt.renderer.rt.pipeline.GpuSceneRayTracingPipeline;

/** Real primary hits, retained Vulkan lights/visibility and native segment integration. */
final class CudaCameraScatteringManager implements AutoCloseable {
    record RadianceContribution(long buffer,long offset,long bytes) { }
    private final ExecutorService nativeThread=Executors.newSingleThreadExecutor(task->{
        Thread thread=new Thread(task,"RTRenderer-native-scattering");thread.setDaemon(true);return thread;
    });
    private final VulkanDeviceRuntime device;
    private final CudaMediumRuntime cuda;
    private final CudaMediumRuntime.BatchKernel resolve;
    private CudaSharedRayFrame storage;
    private boolean closed;

    CudaCameraScatteringManager(VulkanDeviceRuntime device) {
        this.device=Objects.requireNonNull(device,"device");
        NativeState initialized;
        try {initialized=onNative(()->{
            var opened=CudaMediumRuntime.open(Integer.getInteger("top.ceroxe.rt.medium.cuda.deviceOrdinal",0));
            try {return new NativeState(opened,opened.compileBatchKernel(CudaCameraScatteringSource.source(),"camera_scattering_resolve"));}
            catch(RuntimeException|Error failure) {try {opened.close();}catch(RuntimeException closeFailure){failure.addSuppressed(closeFailure);}throw failure;}
        });}catch(RuntimeException|Error failure){nativeThread.shutdown();throw failure;}
        cuda=initialized.cuda();resolve=initialized.resolve();
    }

    static long requiredBytes(int width,int height,int samples,int steps) {
        try {return Math.addExact(CudaCameraScatteringAbi.plan(width,height,samples,steps,residentLimit()).bytes(),
                CudaCameraScatteringAbi.ALLOCATION_ALLOWANCE);}
        catch(ArithmeticException|IllegalArgumentException invalid){throw unsupported(invalid.getMessage());}
    }
    synchronized long requiredGrowthBytes(int width,int height,int samples,int steps) {
        checkOpen();long bytes=requiredBytes(width,height,samples,steps);
        return onNative(()->storage!=null&&storage.byteSize()>=bytes-CudaCameraScatteringAbi.ALLOCATION_ALLOWANCE?0:bytes);
    }
    static long requiredBytes(CudaCameraScatteringAbi.Plan plan) {
        return Math.addExact(plan.bytes(),CudaCameraScatteringAbi.ALLOCATION_ALLOWANCE);
    }
    synchronized long requiredGrowthBytes(CudaCameraScatteringAbi.Plan plan) {
        checkOpen();return onNative(()->storage!=null&&storage.byteSize()>=plan.bytes()?0:requiredBytes(plan));
    }
    synchronized CudaCameraMediumManager.Prepared prepare(RenderFrameRequest frame,int width,int height,
            GpuSceneRayTracingPipeline primaryProducer,GpuSceneRayTracingPipeline lightProducer,int slot,RtTlasBuildPlan buildPlan) {
        checkOpen();return onNative(()->prepareOnNative(frame,width,height,primaryProducer,lightProducer,slot,buildPlan,true));
    }
    synchronized RadianceContribution prepareRadianceContribution(RenderFrameRequest frame,
            CudaCameraScatteringAbi.Plan plan,GpuSceneRayTracingPipeline lightProducer,int slot,RtTlasBuildPlan buildPlan) {
        if(!plan.compactContribution())throw new IllegalArgumentException("compact contribution plan required");
        checkOpen();return onNative(()->{
            prepareOnNative(frame,plan,null,lightProducer,slot,buildPlan,false);
            return new RadianceContribution(storage.buffer(),plan.emissionOffset(),Math.multiplyExact((long)plan.paths(),16));
        });
    }
    private CudaCameraMediumManager.Prepared prepareOnNative(RenderFrameRequest frame,int width,int height,
            GpuSceneRayTracingPipeline primaryProducer,GpuSceneRayTracingPipeline lightProducer,int slot,RtTlasBuildPlan buildPlan,
            boolean includeMediumEmission) {
        CudaCameraScatteringAbi.Plan plan;
        try {plan=CudaCameraScatteringAbi.plan(width,height,frame.antiAliasing().samplesPerPixel(),
                frame.mediumTransport().scatteringSteps(),Math.min(cuda.maxResidentBytes(),CudaCameraScatteringAbi.DEFAULT_RESIDENT_BYTES));}
        catch(ArithmeticException|IllegalArgumentException invalid){throw unsupported(invalid.getMessage());}
        return prepareOnNative(frame,plan,primaryProducer,lightProducer,slot,buildPlan,includeMediumEmission);
    }
    private CudaCameraMediumManager.Prepared prepareOnNative(RenderFrameRequest frame,CudaCameraScatteringAbi.Plan plan,
            GpuSceneRayTracingPipeline primaryProducer,GpuSceneRayTracingPipeline lightProducer,int slot,RtTlasBuildPlan buildPlan,
            boolean includeMediumEmission) {
        ensureCapacity(plan.bytes(),plan.compactContribution());
        long address=storage.deviceAddress();
        try(Arena arena=Arena.ofConfined()) {
            var failed=arena.allocate(4,4);
            for(int first=0;first<plan.pixels();) {
                int tileFirst=first,pixels=Math.min(plan.tilePixels(),plan.pixels()-first);
                boolean initial=first==0,last=first+pixels==plan.pixels();
                byte[] header=CudaCameraScatteringAbi.header(plan,frame,first,pixels,includeMediumEmission);
                storage.produceThenExchangeBlocking(command->{
                    try(MemoryStack stack=MemoryStack.stackPush()) {
                        // Leave the failure counter intact across tiles, resetting it once per frame.
                        VK10.vkCmdUpdateBuffer(command,storage.buffer(),0,stack.malloc(112).put(header,0,112).flip());
                        if(initial) {
                            VK10.vkCmdFillBuffer(command,storage.buffer(),CudaCameraScatteringAbi.FAILED_OFFSET,4,0);
                            if(buildPlan!=null)buildPlan.record(command,stack);
                            if(!plan.compactContribution())
                                primaryProducer.recordPrimaryQueueTrace(command,stack,slot,address+plan.primaryOffset(),0,plan.pixels());
                        }
                        var barrier=VkBufferMemoryBarrier.calloc(1,stack).sType$Default()
                                .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT|VK10.VK_ACCESS_SHADER_WRITE_BIT)
                                .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT|VK10.VK_ACCESS_SHADER_WRITE_BIT)
                                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                                .buffer(storage.buffer()).offset(0).size(storage.byteSize());
                        VK10.vkCmdPipelineBarrier(command,VK10.VK_PIPELINE_STAGE_TRANSFER_BIT|KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,0,null,barrier,null);
                        long offsets=Integer.toUnsignedLong(plan.sourceOffset())|Integer.toUnsignedLong(plan.primaryOffset())<<32;
                        lightProducer.recordPrimaryQueueTrace(command,stack,slot,address,tileFirst,pixels,offsets);
                    }
                },shared->{
                    cuda.launchBlocking(resolve,shared,pixels*plan.samples(),1);
                    if(last)cuda.download(shared.slice(CudaCameraScatteringAbi.FAILED_OFFSET,4),failed);
                },ignored->{});
                first+=pixels;
            }
            int invalidPaths=failed.get(ValueLayout.JAVA_INT,0);
            if(invalidPaths!=0)throw new RendererException("native camera scattering produced invalid transport for "+invalidPaths+" paths; final output was not published");
        }
        return new CudaCameraMediumManager.Prepared(address+plan.primaryOffset(),address+plan.transmittanceOffset(),
                address+plan.emissionOffset(),plan.pixels(),plan.samples());
    }
    private void ensureCapacity(long bytes,boolean exactBudget) {
        if(storage!=null&&storage.byteSize()>=bytes&&(!exactBudget||storage.byteSize()==bytes))return;
        if(bytes+CudaCameraScatteringAbi.ALLOCATION_ALLOWANCE>Math.min(cuda.maxResidentBytes(),CudaCameraScatteringAbi.DEFAULT_RESIDENT_BYTES))
            throw unsupported("complete direct-scattering frame exceeds the CUDA resident cap");
        if(storage!=null){drain();storage.close();storage=null;}
        storage=CudaSharedRayFrame.storage(device,cuda,bytes,cuda.maxResidentBytes()-cuda.residentBytes());
    }
    private void drain(){int result=VulkanQueueHostSync.waitIdle(device.frameQueue());
        if(result!=VK10.VK_SUCCESS)throw new IllegalStateException("drain native scattering consumers: VkResult="+result);}
    private void checkOpen(){if(closed)throw new IllegalStateException("native scattering manager is closed");}
    private static long residentLimit(){return Math.min(CudaCameraScatteringAbi.DEFAULT_RESIDENT_BYTES,
            Long.getLong("top.ceroxe.rt.medium.cuda.maxResidentBytes",CudaCameraScatteringAbi.DEFAULT_RESIDENT_BYTES));}
    private <T>T onNative(Callable<T> operation) {
        var submitted=nativeThread.submit(operation);boolean interrupted=false;
        try {for(;;)try{return submitted.get();}catch(InterruptedException ignored){interrupted=true;}
            catch(ExecutionException failure){if(failure.getCause() instanceof RuntimeException runtime)throw runtime;
                if(failure.getCause() instanceof Error error)throw error;throw new IllegalStateException("native scattering operation failed",failure.getCause());}
        }finally{if(interrupted)Thread.currentThread().interrupt();}
    }
    private static FrameValidationException unsupported(String reason){return new FrameValidationException(
            FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,reason);}
    @Override public synchronized void close() {
        if(closed)return;
        try{onNative(()->{Throwable failure=null;
            try{drain();}catch(RuntimeException|Error exception){failure=exception;}
            if(storage!=null)try{storage.close();storage=null;}catch(RuntimeException|Error exception){if(failure==null)failure=exception;else failure.addSuppressed(exception);}
            try{cuda.close();}catch(RuntimeException|Error exception){if(failure==null)failure=exception;else failure.addSuppressed(exception);}
            if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;return null;
        });}finally{closed=true;nativeThread.shutdown();}
    }
    private record NativeState(CudaMediumRuntime cuda,CudaMediumRuntime.BatchKernel resolve) { }
}
