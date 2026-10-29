package top.ceroxe.rt.renderer.rt.pipeline;

import java.util.Objects;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaBoundaryMediumAbi;

/** Wire-compatible dispatch for gpuscene_native_medium_trace.rgen and its retained-scene hit stages. */
public final class GpuSceneBoundaryTraceCommands {
    private GpuSceneBoundaryTraceCommands() { }
    public static void record(VkCommandBuffer command, MemoryStack stack,
            GpuSceneRayTracingPipeline pipeline, int slot, long sharedAddress,
            CudaBoundaryMediumAbi.Plan plan, int firstPixel, int pixelCount) {
        record(command,stack,pipeline,slot,sharedAddress,plan,firstPixel,pixelCount,false);
    }

    public static void recordPacket(VkCommandBuffer command, MemoryStack stack,
            GpuSceneRayTracingPipeline pipeline, int slot, long sharedAddress, long primaryAddress,
            CudaBoundaryMediumAbi.Plan plan, int firstPixel, int pixelCount) {
        Objects.requireNonNull(plan,"plan");
        if (primaryAddress==0 || (primaryAddress&15)!=0) throw new IllegalArgumentException("invalid packet primary address");
        long offsets=Integer.toUnsignedLong(plan.stateOffset())
                | Integer.toUnsignedLong(plan.tilePrimaryScratchOffset(firstPixel))<<32;
        pipeline.recordBoundaryPacketTrace(command,stack,slot,sharedAddress,primaryAddress,firstPixel,pixelCount,offsets);
    }
    public static void record(VkCommandBuffer command, MemoryStack stack,
            GpuSceneRayTracingPipeline pipeline, int slot, long sharedAddress,
            CudaBoundaryMediumAbi.Plan plan, int firstPixel, int pixelCount, boolean initialize) {
        Objects.requireNonNull(plan,"plan"); Objects.requireNonNull(pipeline,"pipeline");
        if (sharedAddress==0 || (sharedAddress&15)!=0 || pixelCount<1 || pixelCount>plan.tilePixels()
                || firstPixel<0 || (long)firstPixel+pixelCount>plan.pixels())
            throw new IllegalArgumentException("invalid native boundary trace dispatch");
        long offsets = Integer.toUnsignedLong(plan.stateOffset() | (initialize?0x80000000:0))
                | Integer.toUnsignedLong(plan.tilePrimaryOffset(firstPixel))<<32;
        pipeline.recordPrimaryQueueTrace(command,stack,slot,sharedAddress,firstPixel,pixelCount,offsets);
    }
}
