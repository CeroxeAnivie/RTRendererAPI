package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.LongBuffer;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import top.ceroxe.rt.renderer.api.interop.vulkan.D3D12SharedFrame;
import top.ceroxe.rt.renderer.rt.device.VulkanFailures;

/** Dedicated Win32 D3D12 resource and timeline-fence import; never owns the supplied NT handles. */
final class VulkanD3D12ImportedFrame implements AutoCloseable {
    private static final int TRANSFER_USAGE=VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    private final VkDevice device;
    private final D3D12SharedFrame allocation;
    private long image,memory,semaphore;
    private long lastReady;
    private int uses;

    private VulkanD3D12ImportedFrame(VkDevice device,D3D12SharedFrame allocation,long image,long memory,long semaphore) {
        this.device=device;this.allocation=allocation;this.image=image;this.memory=memory;this.semaphore=semaphore;
    }

    static VulkanD3D12ImportedFrame open(VkPhysicalDevice physical,VkDevice device,D3D12SharedFrame frame) {
        if(device.getCapabilities().vkImportSemaphoreWin32HandleKHR==0
                ||device.getCapabilities().vkGetMemoryWin32HandlePropertiesKHR==0)
            throw new UnsupportedOperationException("D3D12 sharing requires Win32 external memory and semaphore extensions");
        long image=0,memory=0,semaphore=0;
        try(MemoryStack stack=MemoryStack.stackPush()) {
            requireAdapter(stack,physical,frame.adapterLuid());
            requireImageSupport(stack,physical);
            var external=VkExternalMemoryImageCreateInfo.calloc(stack).sType$Default()
                .handleTypes(VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D12_RESOURCE_BIT);
            var imageInfo=VkImageCreateInfo.calloc(stack).sType$Default().pNext(external.address())
                .imageType(VK10.VK_IMAGE_TYPE_2D).format(VK10.VK_FORMAT_R8G8B8A8_UNORM)
                .mipLevels(1).arrayLayers(1).samples(VK10.VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK10.VK_IMAGE_TILING_OPTIMAL).usage(TRANSFER_USAGE)
                .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.extent().set(frame.width(),frame.height(),1);
            LongBuffer handle=stack.longs(0);
            check(VK10.vkCreateImage(device,imageInfo,null,handle),"vkCreateImage.d3d12Import");
            image=handle.get(0);
            var requirements=VkMemoryRequirements.calloc(stack);
            VK10.vkGetImageMemoryRequirements(device,image,requirements);
            // Vulkan VkMemoryAllocateInfo explicitly ignores allocationSize for D3D12_RESOURCE
            // imports and queries the OS allocation itself. Its image requirement can differ
            // from GetResourceAllocationInfo; comparing the two rejects valid shared textures.
            var properties=VkMemoryWin32HandlePropertiesKHR.calloc(stack).sType$Default();
            check(KHRExternalMemoryWin32.vkGetMemoryWin32HandlePropertiesKHR(device,
                VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D12_RESOURCE_BIT,frame.memoryHandle(),properties),
                "vkGetMemoryWin32HandlePropertiesKHR.d3d12");
            int bits=properties.memoryTypeBits()&requirements.memoryTypeBits();
            if(bits==0)throw new UnsupportedOperationException("D3D12 texture has no compatible Vulkan memory type");
            var dedicated=VkMemoryDedicatedAllocateInfo.calloc(stack).sType$Default().image(image);
            var imported=VkImportMemoryWin32HandleInfoKHR.calloc(stack).sType$Default().pNext(dedicated.address())
                .handleType(VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D12_RESOURCE_BIT).handle(frame.memoryHandle());
            var allocate=VkMemoryAllocateInfo.calloc(stack).sType$Default().pNext(imported.address())
                .allocationSize(frame.allocationSize()).memoryTypeIndex(Integer.numberOfTrailingZeros(bits));
            check(VK10.vkAllocateMemory(device,allocate,null,handle),"vkAllocateMemory.d3d12Import");
            memory=handle.get(0);
            check(VK10.vkBindImageMemory(device,image,memory,0),"vkBindImageMemory.d3d12Import");
            var timeline=VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default()
                .semaphoreType(VK12.VK_SEMAPHORE_TYPE_TIMELINE).initialValue(0);
            check(VK10.vkCreateSemaphore(device,VkSemaphoreCreateInfo.calloc(stack).sType$Default()
                .pNext(timeline.address()),null,handle),"vkCreateSemaphore.d3d12Fence");
            semaphore=handle.get(0);
            var fence=VkImportSemaphoreWin32HandleInfoKHR.calloc(stack).sType$Default().semaphore(semaphore)
                .handleType(VK11.VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_D3D12_FENCE_BIT).handle(frame.fenceHandle());
            // LWJGL 3.4.3 incorrectly requires both mutually exclusive handle/name selectors.
            // Match the already established binary importer: invoke the checked native ABI.
            check(JNI.callPPI(device.address(),fence.address(),device.getCapabilities().vkImportSemaphoreWin32HandleKHR),
                "vkImportSemaphoreWin32HandleKHR.d3d12Fence");
            return new VulkanD3D12ImportedFrame(device,frame,image,memory,semaphore);
        } catch(RuntimeException|Error failure) {
            if(semaphore!=0)VK10.vkDestroySemaphore(device,semaphore,null);
            if(image!=0)VK10.vkDestroyImage(device,image,null);
            if(memory!=0)VK10.vkFreeMemory(device,memory,null);
            throw failure;
        }
    }

    private static void requireAdapter(MemoryStack stack,VkPhysicalDevice physical,long expected) {
        var ids=VkPhysicalDeviceIDProperties.calloc(stack).sType$Default();
        VK11.vkGetPhysicalDeviceProperties2(physical,VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(ids.address()));
        long actual=0;
        for(int i=0;i<VK11.VK_LUID_SIZE;i++)actual|=(ids.deviceLUID(i)&0xffL)<<(i*8);
        if(!ids.deviceLUIDValid()||actual!=expected)
            throw new UnsupportedOperationException("D3D12 producer and Vulkan presenter must use the same adapter LUID");
    }

    private static void requireImageSupport(MemoryStack stack,VkPhysicalDevice physical) {
        var external=VkPhysicalDeviceExternalImageFormatInfo.calloc(stack).sType$Default()
            .handleType(VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D12_RESOURCE_BIT);
        var query=VkPhysicalDeviceImageFormatInfo2.calloc(stack).sType$Default().pNext(external.address())
            .format(VK10.VK_FORMAT_R8G8B8A8_UNORM).type(VK10.VK_IMAGE_TYPE_2D)
            .tiling(VK10.VK_IMAGE_TILING_OPTIMAL).usage(TRANSFER_USAGE);
        var support=VkExternalImageFormatProperties.calloc(stack).sType$Default();
        int result=VK11.vkGetPhysicalDeviceImageFormatProperties2(physical,query,
            VkImageFormatProperties2.calloc(stack).sType$Default().pNext(support.address()));
        if(result==VK10.VK_ERROR_FORMAT_NOT_SUPPORTED)
            throw new UnsupportedOperationException("RGBA8 D3D12_RESOURCE import is not supported by this Vulkan GPU");
        check(result,"vkGetPhysicalDeviceImageFormatProperties2.d3d12");
        if((support.externalMemoryProperties().externalMemoryFeatures()&VK11.VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT)==0)
            throw new UnsupportedOperationException("RGBA8 D3D12_RESOURCE import is not supported by this Vulkan GPU");
        var kind=VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default().semaphoreType(VK12.VK_SEMAPHORE_TYPE_TIMELINE);
        var semQuery=VkPhysicalDeviceExternalSemaphoreInfo.calloc(stack).sType$Default().pNext(kind.address())
            .handleType(VK11.VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_D3D12_FENCE_BIT);
        var semSupport=VkExternalSemaphoreProperties.calloc(stack).sType$Default();
        VK11.vkGetPhysicalDeviceExternalSemaphoreProperties(physical,semQuery,semSupport);
        if((semSupport.externalSemaphoreFeatures()&VK11.VK_EXTERNAL_SEMAPHORE_FEATURE_IMPORTABLE_BIT)==0)
            throw new UnsupportedOperationException("D3D12_FENCE timeline import is not supported by this Vulkan GPU");
    }

    void requireNext(D3D12SharedFrame frame) {
        if(frame.resourceId()!=allocation.resourceId()||frame.adapterLuid()!=allocation.adapterLuid()
                ||frame.width()!=allocation.width()||frame.height()!=allocation.height()
                ||frame.allocationSize()!=allocation.allocationSize()||frame.memoryHandle()!=allocation.memoryHandle()
                ||frame.fenceHandle()!=allocation.fenceHandle())
            throw new IllegalArgumentException("D3D12 resourceId was reused for another image/fence allocation");
        if(frame.readyValue()<=lastReady)throw new IllegalArgumentException("D3D12 slot ready value must strictly advance");
    }

    long image() { return image; }
    long semaphore() { return semaphore; }
    void submitted(long ready) { lastReady=ready;uses++; }
    void retired() { if(--uses<0)throw new IllegalStateException("D3D12 import GPU use count underflow"); }
    boolean inUse() { return uses!=0; }
    @Override public void close() {
        if(uses!=0)throw new IllegalStateException("D3D12 image is still used by Vulkan GPU work");
        if(semaphore!=0)VK10.vkDestroySemaphore(device,semaphore,null);
        semaphore=0;
        if(image!=0)VK10.vkDestroyImage(device,image,null);
        image=0;
        if(memory!=0)VK10.vkFreeMemory(device,memory,null);
        memory=0;
    }
    private static void check(int result,String label) { VulkanFailures.check(result,label); }
}
