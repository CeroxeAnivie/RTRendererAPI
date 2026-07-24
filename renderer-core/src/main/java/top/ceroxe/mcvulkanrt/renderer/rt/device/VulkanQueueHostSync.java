package top.ceroxe.mcvulkanrt.renderer.rt.device;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSubmitInfo2;

import java.util.Objects;

/**
 * Owns Vulkan's host-synchronization contract for borrowed host queues.
 *
 * <p>{@code VkQueue} commands require external synchronization even when the
 * command buffers and fences are otherwise independent. host submits its
 * frame timeline on the render thread while section BLAS work may submit from
 * an RT worker. Both paths therefore have to resolve the same queue handle to
 * the same monitor. Keeping that identity here prevents each subsystem from
 * inventing a private lock that cannot synchronize with the other owner.</p>
 */
public final class VulkanQueueHostSync {
    private static final Long2ObjectOpenHashMap<Object> MONITORS = new Long2ObjectOpenHashMap<>();

    private VulkanQueueHostSync() {
    }

    public static Object monitor(VkQueue queue) {
        return monitor(Objects.requireNonNull(queue, "queue").address());
    }

    static Object monitor(long queueAddress) {
        if (queueAddress == 0L) {
            throw new IllegalArgumentException("queueAddress must not be null");
        }
        synchronized (MONITORS) {
            return MONITORS.computeIfAbsent(queueAddress, ignored -> new Object());
        }
    }

    public static int submit2(VkQueue queue, VkSubmitInfo2.Buffer submits, long fence) {
        synchronized (monitor(queue)) {
            return KHRSynchronization2.vkQueueSubmit2KHR(queue, submits, fence);
        }
    }

    public static int waitIdle(VkQueue queue) {
        synchronized (monitor(queue)) {
            return VK10.vkQueueWaitIdle(queue);
        }
    }

    public static int present(VkQueue queue, VkPresentInfoKHR presentInfo) {
        synchronized (monitor(queue)) {
            return KHRSwapchain.vkQueuePresentKHR(queue, presentInfo);
        }
    }
}
