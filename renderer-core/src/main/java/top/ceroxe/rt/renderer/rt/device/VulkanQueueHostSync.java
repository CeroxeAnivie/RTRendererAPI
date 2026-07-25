package top.ceroxe.rt.renderer.rt.device;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.lwjgl.vulkan.*;

import java.lang.ref.WeakReference;
import java.util.Objects;

/**
 * Owns Vulkan's process-wide host-synchronization contract for native queues.
 *
 * <p>{@code VkQueue} commands require external synchronization even when the
 * command buffers and fences are otherwise independent. Frame dispatch and
 * acceleration-structure work may submit from different threads. Both paths must resolve the same queue handle to
 * the same monitor. Keeping that identity here prevents each subsystem from
 * inventing a private lock that cannot synchronize with the other owner.</p>
 */
public final class VulkanQueueHostSync {
    private static final Long2ObjectOpenHashMap<WeakReference<Object>> MONITORS = new Long2ObjectOpenHashMap<>();

    private VulkanQueueHostSync() {
    }

    /**
     * Returns the process-wide host-synchronization monitor for a native queue handle.
     *
     * @param queue live Vulkan queue
     * @return stable monitor shared by all wrappers for the queue address
     */
    public static Object monitor(VkQueue queue) {
        return monitor(Objects.requireNonNull(queue, "queue").address());
    }

    static Object monitor(long queueAddress) {
        if (queueAddress == 0L) {
            throw new IllegalArgumentException("queueAddress must not be null");
        }
        synchronized (MONITORS) {
            WeakReference<Object> reference = MONITORS.get(queueAddress);
            Object monitor = reference == null ? null : reference.get();
            if (monitor == null) {
                monitor = new Object();
                MONITORS.put(queueAddress, new WeakReference<>(monitor));
                if (MONITORS.size() > 256) {
                    MONITORS.long2ObjectEntrySet().removeIf(entry -> entry.getValue().get() == null);
                }
            }
            return monitor;
        }
    }

    /**
     * Submits synchronization2 work while holding the queue's host monitor.
     *
     * @param queue   destination queue
     * @param submits submit descriptors valid for the duration of the call
     * @param fence   optional fence handle, or {@link VK10#VK_NULL_HANDLE}
     * @return Vulkan result returned by {@code vkQueueSubmit2KHR}
     */
    public static int submit2(VkQueue queue, VkSubmitInfo2.Buffer submits, long fence) {
        synchronized (monitor(queue)) {
            return KHRSynchronization2.vkQueueSubmit2KHR(queue, submits, fence);
        }
    }

    /**
     * Submits legacy {@link VkSubmitInfo} work while holding the queue's process-wide host lock.
     * This is shared by the RT command lanes and the managed swapchain presenter.
     *
     * @param queue destination queue
     * @param submits submit descriptors valid for the duration of the native call
     * @param fence optional completion fence, or {@link VK10#VK_NULL_HANDLE}
     * @return Vulkan result returned by {@link VK10#vkQueueSubmit}
     */
    public static int submit(VkQueue queue, VkSubmitInfo.Buffer submits, long fence) {
        synchronized (monitor(queue)) {
            return VK10.vkQueueSubmit(queue, submits, fence);
        }
    }

    /**
     * Waits for a queue to become idle while excluding concurrent queue commands.
     *
     * @param queue queue to drain
     * @return Vulkan result returned by {@code vkQueueWaitIdle}
     */
    public static int waitIdle(VkQueue queue) {
        synchronized (monitor(queue)) {
            return VK10.vkQueueWaitIdle(queue);
        }
    }

    /**
     * Presents while holding the same monitor used by submissions to this queue.
     *
     * @param queue       presentation queue
     * @param presentInfo presentation descriptor valid for the duration of the call
     * @return Vulkan result returned by {@code vkQueuePresentKHR}
     */
    public static int present(VkQueue queue, VkPresentInfoKHR presentInfo) {
        synchronized (monitor(queue)) {
            return KHRSwapchain.vkQueuePresentKHR(queue, presentInfo);
        }
    }

    /**
     * Presents while separately measuring monitor contention and the native WSI call.
     *
     * @param queue presentation queue
     * @param presentInfo presentation descriptor valid for the duration of the native call
     * @return immutable native result and host timing sample
     */
    public static TimedPresent presentTimed(VkQueue queue, VkPresentInfoKHR presentInfo) {
        Object queueMonitor = monitor(queue);
        long waitStart = System.nanoTime();
        synchronized (queueMonitor) {
            long acquired = System.nanoTime();
            int result = KHRSwapchain.vkQueuePresentKHR(queue, presentInfo);
            long completed = System.nanoTime();
            return new TimedPresent(result, acquired - waitStart, completed - acquired);
        }
    }

    /**
     * Immutable timing for one host-synchronized present call.
     *
     * @param result Vulkan result returned by {@code vkQueuePresentKHR}
     * @param queueLockWaitNanos non-negative time spent waiting for the queue host monitor
     * @param nativeCallNanos non-negative time spent inside {@code vkQueuePresentKHR}
     */
    public record TimedPresent(int result, long queueLockWaitNanos, long nativeCallNanos) {
        /** Validates that both monotonic timing components are non-negative. */
        public TimedPresent {
            if (queueLockWaitNanos < 0L || nativeCallNanos < 0L) {
                throw new IllegalArgumentException("present timings must not be negative");
            }
        }
    }
}
