package top.ceroxe.rt.renderer.rt.device;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaBudget;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

import java.util.ArrayList;

/**
 * Captures VMA heap budgets without retaining native query storage.
 */
final class VulkanMemoryBudget {
    private VulkanMemoryBudget() {
    }

    static VulkanMemoryBudgetSnapshot capture(
            MemoryStack stack,
            VkPhysicalDevice physicalDevice,
            long allocator,
            boolean driverBudgetAvailable
    ) {
        VkPhysicalDeviceMemoryProperties memory = VkPhysicalDeviceMemoryProperties.calloc(stack);
        VK10.vkGetPhysicalDeviceMemoryProperties(physicalDevice, memory);
        VmaBudget.Buffer budgets = VmaBudget.calloc(VK10.VK_MAX_MEMORY_HEAPS, stack);
        Vma.vmaGetHeapBudgets(allocator, budgets);

        ArrayList<VulkanMemoryBudgetSnapshot.HeapBudget> heaps = new ArrayList<>(memory.memoryHeapCount());
        long deviceUsage = 0L;
        long deviceBudget = 0L;
        for (int index = 0; index < memory.memoryHeapCount(); index++) {
            boolean deviceLocal = (memory.memoryHeaps(index).flags() & VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0;
            long usage = nonNegative(budgets.get(index).usage());
            long budget = nonNegative(budgets.get(index).budget());
            heaps.add(new VulkanMemoryBudgetSnapshot.HeapBudget(index, deviceLocal, usage, budget));
            if (deviceLocal) {
                deviceUsage = saturatedAdd(deviceUsage, usage);
                deviceBudget = saturatedAdd(deviceBudget, budget);
            }
        }
        return new VulkanMemoryBudgetSnapshot(
                driverBudgetAvailable,
                deviceUsage,
                deviceBudget,
                Math.max(0L, deviceBudget - deviceUsage),
                heaps
        );
    }

    private static long nonNegative(long value) {
        return value < 0L ? Long.MAX_VALUE : value;
    }

    private static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }
}
