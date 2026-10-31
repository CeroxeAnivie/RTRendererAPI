package top.ceroxe.rt.renderer.rt.device;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.rt.diagnostics.NativeMemoryDiagnostics;

/** Isolate the host commit of identical device-local allocations; no shaders or frames. */
public final class GpuBufferCommitNativeSelfTest {
    public static void main(String[] arguments) {
        String mode = arguments[0];
        long bytes = Long.parseLong(arguments[1]);
        if (!mode.equals("vma") && !mode.equals("dedicated") && !mode.equals("exported"))
            throw new IllegalArgumentException("vma, dedicated, or exported required");
        try (var runtime = VulkanDeviceRuntime.open(VulkanRtCapabilityProbe.capture());
             var stack = MemoryStack.stackPush()) {
            NativeMemoryDiagnostics.checkpoint("isolation.device", 0, 0);
            if (mode.equals("vma")) {
                try (var scope = NativeMemoryDiagnostics.begin("isolation.buffer." + mode, bytes)) {
                    var buffer = RtGpuBuffer.createDeviceAddressBuffer(runtime.device(), runtime.allocator(), bytes,
                            VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
                    NativeMemoryDiagnostics.checkpoint("isolation.allocated." + mode, buffer.buffer(), bytes);
                    scope.close();
                    buffer.close();
                }
            } else {
                boolean exported = mode.equals("exported");
                long buffer = 0, memory = 0;
                try {
                    var external = VkExternalMemoryBufferCreateInfo.calloc(stack).sType$Default()
                            .handleTypes(VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT);
                    var info = VkBufferCreateInfo.calloc(stack).sType$Default().size(bytes)
                            .usage(VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT)
                            .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
                    if (exported) info.pNext(external.address());
                    var output = stack.mallocLong(1);
                    VulkanFailures.check(VK10.vkCreateBuffer(runtime.device(), info, null, output), "isolation buffer");
                    buffer = output.get(0);
                    var requirements = VkMemoryRequirements.malloc(stack);
                    VK10.vkGetBufferMemoryRequirements(runtime.device(), buffer, requirements);
                    var properties = VkPhysicalDeviceMemoryProperties.malloc(stack);
                    VK10.vkGetPhysicalDeviceMemoryProperties(runtime.physicalDevice(), properties);
                    int type = -1;
                    for (int i = 0; i < properties.memoryTypeCount(); i++)
                        if ((requirements.memoryTypeBits() & (1 << i)) != 0
                                && (properties.memoryTypes(i).propertyFlags() & VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0) {
                            type = i; break;
                        }
                    if (type < 0) throw new IllegalStateException("device-local memory required");
                    var dedicated = VkMemoryDedicatedAllocateInfo.calloc(stack).sType$Default().buffer(buffer);
                    var exportInfo = VkExportMemoryAllocateInfo.calloc(stack).sType$Default()
                            .handleTypes(VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT).pNext(dedicated.address());
                    var flags = VkMemoryAllocateFlagsInfo.calloc(stack).sType$Default()
                            .flags(VK12.VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT)
                            .pNext(exported ? exportInfo.address() : dedicated.address());
                    try (var scope = NativeMemoryDiagnostics.begin("isolation.buffer." + mode, requirements.size())) {
                        VulkanFailures.check(VK10.vkAllocateMemory(runtime.device(), VkMemoryAllocateInfo.calloc(stack)
                                .sType$Default().pNext(flags.address()).allocationSize(requirements.size()).memoryTypeIndex(type),
                                null, output), "isolation memory");
                        memory = output.get(0);
                        VulkanFailures.check(VK10.vkBindBufferMemory(runtime.device(), buffer, memory, 0), "isolation bind");
                        NativeMemoryDiagnostics.checkpoint("isolation.allocated." + mode, type, requirements.size());
                    }
                } finally {
                    if (buffer != 0) VK10.vkDestroyBuffer(runtime.device(), buffer, null);
                    if (memory != 0) VK10.vkFreeMemory(runtime.device(), memory, null);
                }
            }
            NativeMemoryDiagnostics.checkpoint("isolation.released." + mode, 0, 0);
        }
        System.out.println("GpuBufferCommitNativeSelfTest passed " + mode);
    }
}
