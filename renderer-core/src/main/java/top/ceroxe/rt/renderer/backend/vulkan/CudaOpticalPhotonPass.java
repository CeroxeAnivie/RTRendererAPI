package top.ceroxe.rt.renderer.backend.vulkan;

import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import top.ceroxe.rt.renderer.api.RendererException;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.*;
import top.ceroxe.rt.renderer.rt.acceleration.RtTlasBuildPlan;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.pipeline.GpuSceneRayTracingPipeline;
import top.ceroxe.rt.renderer.rt.diagnostics.OpticalDetailedProfile;

/** Bounded photon wavefront using the same native density and medium stack as camera paths. */
final class CudaOpticalPhotonPass implements AutoCloseable {
    private final VulkanDeviceRuntime device;
    private final CudaMediumRuntime cuda;
    private final CudaMediumRuntime.BatchKernel clear,reset,step;
    private CudaSharedRayFrame storage;

    CudaOpticalPhotonPass(VulkanDeviceRuntime device,CudaMediumRuntime cuda) {
        this.device=device;this.cuda=cuda;
        String source=CudaOpticalCausticSource.source();
        clear=cuda.compileBatchKernel(source,"native_optical_photon_clear");
        reset=cuda.compileBatchKernel(source,"native_optical_photon_reset");
        step=cuda.compileBatchKernel(source,"native_optical_photon_step");
    }

    static long bytes(CudaOpticalMediumManager.FrameScene scene) {
        var plan=scene.nativePhotons();
        return plan==null?0:Math.addExact(offset(scene),plan.bytes()+CudaOpticalMediumAbi.ALLOCATION_ALLOWANCE);
    }
    long growth(CudaOpticalMediumManager.FrameScene scene) {
        return storage!=null&&storage.byteSize()>=bytes(scene)-CudaOpticalMediumAbi.ALLOCATION_ALLOWANCE?0:bytes(scene);
    }
    private static int offset(CudaOpticalMediumManager.FrameScene scene) {
        return Math.toIntExact((scene.plan().sceneBytes()+15L)&~15L);
    }

    void prepare(CudaOpticalMediumManager.FrameScene scene,CausticPhotonPlan output,
            GpuSceneRayTracingPipeline tracer,int slot,RtGpuBuffer map,RtTlasBuildPlan build) {
        var plan=scene.nativePhotons();int offset=offset(scene);
        if(plan.paths()!=output.photons()||plan.depositsPerPath()!=output.depositsPerPath()
                ||plan.hashCapacity()!=output.hashCapacity())
            throw new IllegalArgumentException("native and Vulkan photon map layouts differ");
        long bytes=offset+plan.bytes();
        if(storage==null||storage.byteSize()<bytes) {
            if(storage!=null){storage.close();storage=null;}
            storage=CudaSharedRayFrame.storage(device,cuda,bytes,cuda.maxResidentBytes()-cuda.residentBytes());
        }
        long address=storage.deviceAddress(),photonAddress=address+offset;
        try(Arena arena=Arena.ofConfined()) {
            var prefix=arena.allocate(offset+CudaOpticalCausticAbi.HEADER_BYTES,16);
            scene.writeScene(prefix.asByteBuffer().order(ByteOrder.nativeOrder()));
            prefix.asSlice(offset,CudaOpticalCausticAbi.HEADER_BYTES).asByteBuffer().put(CudaOpticalCausticAbi.mapHeader(
                    plan,scene.frame().caustics(),scene.frame().mediumTransport().scatteringBounces()+1,
                    (int)scene.frame().sequence()));
            var pointer=arena.allocate(ValueLayout.JAVA_LONG);
            var statistics=arena.allocate(8,8);
            int[] counts={0,plan.paths()};
            for(int round=0;round<scene.frame().caustics().maxEvents()&&counts[1]>0;round++) {
                boolean initialize=round==0;
                storage.produceThenExchangeBlocking(command->{try(MemoryStack stack=MemoryStack.stackPush()) {
                    if(initialize) {
                        if(build!=null)build.record(command,stack);
                        var initialization=OpticalFrameTimings.ENABLED?device.frameCommands().acquireGpuTimestampCapture("opticalPhotonInitialize",new String[]{"start","end"}):null;
                        OpticalDetailedProfile.defer("opticalPhoton.mapInitialize",initialization);
                        if(initialization!=null)initialization.begin(command,VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
                        output.initializeMap(command,stack,map);
                        upload(command,stack,prefix.asByteBuffer());
                        if(initialization!=null)initialization.write(command,VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
                    }
                    var trace=OpticalFrameTimings.ENABLED?device.frameCommands().acquireGpuTimestampCapture("opticalPhotonTrace",new String[]{"start","end"}):null;
                    OpticalDetailedProfile.defer("opticalPhoton.trace",trace);
                    if(trace!=null)trace.begin(command,KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR);
                    tracer.recordNativeOpticalPhotons(command,stack,slot,address,photonAddress,plan.paths(),initialize);
                    if(trace!=null)trace.write(command,KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR);
                }},input->{
                    var photons=input.slice(offset,plan.bytes());
                    if(initialize) {
                        // The Vulkan BDA and CUDA pointer are distinct address spaces.
                        pointer.set(ValueLayout.JAVA_LONG,0,input.deviceAddress());
                        cuda.upload(photons.slice(96,8),pointer);
                        cuda.launchBlocking(clear,photons,plan.hashCapacity(),1);
                    }
                    cuda.launchBlocking(reset,photons,1,CudaOpticalCausticAbi.HEADER_BYTES);
                    cuda.launchBlocking(step,photons,plan.paths(),1);
                    cuda.download(photons.slice(80,8),statistics);
                    counts[0]=statistics.get(ValueLayout.JAVA_INT,0);
                    counts[1]=statistics.get(ValueLayout.JAVA_INT,4);
                },command->{
                    if(counts[0]==0&&counts[1]==0)try(MemoryStack stack=MemoryStack.stackPush()) {
                        var copy=OpticalFrameTimings.ENABLED?device.frameCommands().acquireGpuTimestampCapture("opticalPhotonCopy",new String[]{"start","end"}):null;
                        OpticalDetailedProfile.defer("opticalPhoton.mapCopy",copy);
                        if(copy!=null)copy.begin(command,VK10.VK_PIPELINE_STAGE_TRANSFER_BIT);
                        var copies=VkBufferCopy.calloc(2,stack);
                        copies.get(0).srcOffset(offset+(long)plan.headsOffset()).dstOffset(output.headsWord()*4L)
                                .size(plan.hashCapacity()*4L);
                        copies.get(1).srcOffset(offset+(long)plan.photonOffset()).dstOffset(output.recordsWord()*4L)
                                .size(plan.capacity()*(long)CudaOpticalCausticAbi.PHOTON_BYTES);
                        VK10.vkCmdCopyBuffer(command,storage.buffer(),map.buffer(),copies);
                        if(copy!=null)copy.write(command,VK10.VK_PIPELINE_STAGE_TRANSFER_BIT);
                        var barrier=VkMemoryBarrier.calloc(1,stack).sType$Default().srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                                .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT);
                        VK10.vkCmdPipelineBarrier(command,VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,0,barrier,null,null);
                    }
                });
                if(counts[0]!=0)throw new RendererException("native photon transport rejected "+counts[0]+" paths");
            }
            if(counts[1]!=0)throw new RendererException("native photon paths exceeded the declared event budget");
        }
        CausticPhotonPlan.diagnosticPhotonDispatches.increment();
    }
    private void upload(VkCommandBuffer command,MemoryStack stack,java.nio.ByteBuffer data) {
        for(int offset=0;offset<data.capacity();offset+=65536) {
            var chunk=data.duplicate();chunk.position(offset).limit(Math.min(data.capacity(),offset+65536));
            VK10.vkCmdUpdateBuffer(command,storage.buffer(),offset,chunk.slice());
        }
        var barrier=VkMemoryBarrier.calloc(1,stack).sType$Default().srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT|VK10.VK_ACCESS_SHADER_WRITE_BIT);
        VK10.vkCmdPipelineBarrier(command,VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,0,barrier,null,null);
    }
    @Override public void close(){
        RuntimeException failure=null;
        for(AutoCloseable resource:new AutoCloseable[]{storage,step,reset,clear})if(resource!=null)try{resource.close();}
            catch(Exception error){if(failure==null)failure=new IllegalStateException("close native photon pass",error);else failure.addSuppressed(error);}
        storage=null;
        if(failure!=null)throw failure;
    }
}
