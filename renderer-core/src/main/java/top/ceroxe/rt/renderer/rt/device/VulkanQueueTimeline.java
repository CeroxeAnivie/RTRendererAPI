package top.ceroxe.rt.renderer.rt.device;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns the timeline semaphore that transfers completed build writes to the frame queue.
 *
 * <p>Only fence-observed values become consumable. Consequently a frame never waits for a
 * speculative or unpublished build, while its timeline wait still establishes the Vulkan memory
 * dependency missing from a host-only fence observation.</p>
 */
final class VulkanQueueTimeline implements AutoCloseable {
    private final VkDevice device;
    private final Watermark watermark = new Watermark();
    private long semaphore;

    private VulkanQueueTimeline(VkDevice device, long semaphore) {
        this.device = Objects.requireNonNull(device, "device");
        if (semaphore == 0L) throw new IllegalArgumentException("timeline semaphore must not be null");
        this.semaphore = semaphore;
    }

    static VulkanQueueTimeline create(MemoryStack stack, VkDevice device) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(device, "device");
        VkSemaphoreTypeCreateInfo type = VkSemaphoreTypeCreateInfo.calloc(stack)
                .sType$Default()
                .semaphoreType(VK12.VK_SEMAPHORE_TYPE_TIMELINE)
                .initialValue(0L);
        VkSemaphoreCreateInfo info = VkSemaphoreCreateInfo.calloc(stack)
                .sType$Default()
                .pNext(type.address());
        LongBuffer handle = stack.longs(0L);
        VulkanFailures.check(VK10.vkCreateSemaphore(device, info, null, handle), "vkCreateSemaphore.queueTimeline");
        return new VulkanQueueTimeline(device, handle.get(0));
    }

    long semaphore() {
        return semaphore;
    }

    long reserveSignalValue() {
        return watermark.reserveSignalValue();
    }

    void markSubmitted(long value) {
        watermark.markSubmitted(value);
    }

    void markCompleted(long value) {
        watermark.markCompleted(value);
    }

    long completedValue() {
        return watermark.completedValue();
    }

    long lastSubmittedValue() {
        return watermark.lastSubmittedValue();
    }

    /**
     * Destroys the owned timeline semaphore; repeated calls are harmless.
     */
    @Override
    public void close() {
        long handle = semaphore;
        semaphore = 0L;
        if (handle != 0L) VK10.vkDestroySemaphore(device, handle, null);
    }

    /**
     * Thread-safe monotonic state separated from native ownership for deterministic tests.
     */
    static final class Watermark {
        private final AtomicLong next = new AtomicLong();
        private final AtomicLong submitted = new AtomicLong();
        private final AtomicLong completed = new AtomicLong();

        long reserveSignalValue() {
            return next.updateAndGet(current -> {
                if (current == Long.MAX_VALUE) throw new IllegalStateException("queue timeline value exhausted");
                return current + 1L;
            });
        }

        void markSubmitted(long value) {
            requireReserved(value);
            submitted.accumulateAndGet(value, Math::max);
        }

        void markCompleted(long value) {
            if (value <= 0L || value > submitted.get()) {
                throw new IllegalArgumentException("completed timeline value was not submitted: " + value);
            }
            completed.accumulateAndGet(value, Math::max);
        }

        long completedValue() {
            return completed.get();
        }

        long lastSubmittedValue() {
            return submitted.get();
        }

        private void requireReserved(long value) {
            if (value <= 0L || value > next.get()) {
                throw new IllegalArgumentException("timeline value was not reserved: " + value);
            }
        }
    }
}
