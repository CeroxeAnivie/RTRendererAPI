package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import top.ceroxe.rt.renderer.rt.device.RtGpuImage;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.diagnostics.NativeMemoryDiagnostics;
import top.ceroxe.rt.renderer.rt.pipeline.VulkanFrameCompositionPipeline;

/** Reuses the existing premultiplied-alpha composition shader; no new lighting or UI shader. */
final class VulkanD3D12Composition implements AutoCloseable {
    private final VulkanDeviceRuntime runtime;
    private final VulkanFrameCompositionPipeline pipeline;
    private final RtGpuImage[] bases,outputs;

    VulkanD3D12Composition(VulkanDeviceRuntime runtime,int slots) {
        this.runtime=runtime;
        pipeline=VulkanFrameCompositionPipeline.open(runtime,slots,false);
        bases=new RtGpuImage[slots];outputs=new RtGpuImage[slots];
    }

    void prepare(int slot,int width,int height) {
        if(bases[slot]!=null&&bases[slot].width()==width&&bases[slot].height()==height)return;
        if(bases[slot]!=null)bases[slot].close();
        if(outputs[slot]!=null)outputs[slot].close();
        bases[slot]=null;outputs[slot]=null;
        try {
            bases[slot]=RtGpuImage.createStorageImage(runtime.device(),runtime.allocator(),width,height,VK10.VK_FORMAT_R8G8B8A8_UNORM);
            outputs[slot]=RtGpuImage.createStorageImage(runtime.device(),runtime.allocator(),width,height,VK10.VK_FORMAT_R8G8B8A8_UNORM);
            NativeMemoryDiagnostics.checkpoint("vulkan.d3d12-composition.allocate",slot,bases[slot].allocationSize()+outputs[slot].allocationSize());
        } catch(RuntimeException|Error failure) {
            if(bases[slot]!=null)bases[slot].close();bases[slot]=null;
            if(outputs[slot]!=null)outputs[slot].close();outputs[slot]=null;
            throw failure;
        }
    }

    /** External source has already been acquired in TRANSFER_SRC_OPTIMAL by the presenter. */
    long record(VkCommandBuffer commands,MemoryStack stack,int slot,long external,int width,int height,
            VulkanD3D12CompositionInputs inputs) {
        var base=bases[slot];var output=outputs[slot];
        barrier(commands,stack,base.image(),VK10.VK_IMAGE_LAYOUT_UNDEFINED,VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            0,VK10.VK_ACCESS_TRANSFER_WRITE_BIT,VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK10.VK_PIPELINE_STAGE_TRANSFER_BIT);
        var copy=VkImageCopy.calloc(1,stack);
        copy.srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
        copy.dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
        copy.extent().set(width,height,1);
        VK10.vkCmdCopyImage(commands,external,VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,base.image(),
            VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,copy);
        barrier(commands,stack,base.image(),VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VK10.VK_IMAGE_LAYOUT_GENERAL,
            VK10.VK_ACCESS_TRANSFER_WRITE_BIT,VK10.VK_ACCESS_SHADER_READ_BIT,VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        long[] views=new long[inputs.sources.length+1];int[] operations=new int[views.length];
        views[inputs.externalLayer]=base.imageView();operations[inputs.externalLayer]=inputs.externalLayer==0?0:1;
        for(int i=0;i<inputs.sources.length;i++) {
            int index=i<inputs.externalLayer?i:i+1;
            var source=inputs.sources[i];views[index]=source.view();operations[index]=inputs.operations[i];
            barrier(commands,stack,source.image(),inputs.layouts[i],VK10.VK_IMAGE_LAYOUT_GENERAL,
                VK10.VK_ACCESS_MEMORY_WRITE_BIT,VK10.VK_ACCESS_SHADER_READ_BIT,
                VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        }
        if(inputs.linearSrgb)operations[0]|=8; // opt-in sRGB decoding and linear-light premultiplied blend
        barrier(commands,stack,output.image(),VK10.VK_IMAGE_LAYOUT_UNDEFINED,VK10.VK_IMAGE_LAYOUT_GENERAL,
            0,VK10.VK_ACCESS_SHADER_WRITE_BIT,VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        pipeline.record(commands,stack,slot,width,height,views,output.imageView(),operations);
        barrier(commands,stack,output.image(),VK10.VK_IMAGE_LAYOUT_GENERAL,VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            VK10.VK_ACCESS_SHADER_WRITE_BIT,VK10.VK_ACCESS_TRANSFER_READ_BIT,
            VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK10.VK_PIPELINE_STAGE_TRANSFER_BIT);
        return output.image();
    }

    private static void barrier(VkCommandBuffer commands,MemoryStack stack,long image,int oldLayout,int newLayout,
            int srcAccess,int dstAccess,int srcStage,int dstStage) {
        var barrier=VkImageMemoryBarrier.calloc(1,stack).sType$Default().image(image).oldLayout(oldLayout).newLayout(newLayout)
            .srcAccessMask(srcAccess).dstAccessMask(dstAccess).srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
            .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED);
        barrier.subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        VK10.vkCmdPipelineBarrier(commands,srcStage,dstStage,0,null,null,barrier);
    }
    @Override public void close() {
        long released=0;
        for(var image:bases)if(image!=null){released+=image.allocationSize();image.close();}
        for(var image:outputs)if(image!=null){released+=image.allocationSize();image.close();}
        NativeMemoryDiagnostics.checkpoint("vulkan.d3d12-composition.release",0,released);
        pipeline.close();
    }
}
