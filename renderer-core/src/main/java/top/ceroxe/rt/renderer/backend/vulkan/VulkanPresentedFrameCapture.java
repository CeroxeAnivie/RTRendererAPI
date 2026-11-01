package top.ceroxe.rt.renderer.backend.vulkan;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

/** One explicit final-frame readback. No instance exists during ordinary frame delivery. */
final class VulkanPresentedFrameCapture implements AutoCloseable {
    private final RtGpuBuffer buffer;
    private final int width,height,format;
    private final Path path;
    private boolean submitted;

    VulkanPresentedFrameCapture(VulkanDeviceRuntime runtime,int width,int height,int format,Path path) {
        this.width=width;this.height=height;this.format=format;this.path=path;
        buffer=RtGpuBuffer.createHostVisibleBuffer(runtime.device(),runtime.allocator(),(long)width*height*4,
            VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
    }
    void record(VkCommandBuffer commands,MemoryStack stack,long image) {
        var copy=VkBufferImageCopy.calloc(1,stack);
        copy.imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
        copy.imageExtent().set(width,height,1);
        VK10.vkCmdCopyImageToBuffer(commands,image,VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,buffer.buffer(),copy);
        var visible=VkBufferMemoryBarrier.calloc(1,stack).sType$Default()
            .buffer(buffer.buffer()).offset(0).size(VK10.VK_WHOLE_SIZE)
            .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK10.VK_ACCESS_HOST_READ_BIT)
            .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED);
        VK10.vkCmdPipelineBarrier(commands,VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,VK10.VK_PIPELINE_STAGE_HOST_BIT,
            0,null,visible,null);
    }
    void submitted() { submitted=true; }
    void finish() {
        if(!submitted)return;
        byte[] bytes=buffer.readBytes();
        boolean bgra=format==VK10.VK_FORMAT_B8G8R8A8_UNORM;
        int[] pixels=new int[width*height];
        for(int i=0,p=0;i<pixels.length;i++,p+=4) {
            int r=bytes[p+(bgra?2:0)]&255,g=bytes[p+1]&255,b=bytes[p+(bgra?0:2)]&255,a=bytes[p+3]&255;
            pixels[i]=(a<<24)|(r<<16)|(g<<8)|b;
        }
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0,0,width,height,pixels,0,width);
        try {
            Files.createDirectories(path.getParent());
            if(!ImageIO.write(image,"png",path.toFile()))throw new IOException("No PNG writer available");
        } catch(IOException failure) { throw new IllegalStateException("Final-frame diagnostic capture failed: "+path,failure); }
    }
    @Override public void close() { buffer.close(); }
}
