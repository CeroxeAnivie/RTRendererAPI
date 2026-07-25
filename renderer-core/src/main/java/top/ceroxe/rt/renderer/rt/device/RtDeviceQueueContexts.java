package top.ceroxe.rt.renderer.rt.device;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;
import top.ceroxe.rt.renderer.RtStallTelemetrySink;

import java.util.Objects;

/**
 * Owns RT queue topology and the command-pool contexts bound to that topology.
 *
 * <p>The frame lane may share a physical queue with build work, but its command pool remains
 * separate from build and section-BLAS pools. Shared queues use one submission lock so Vulkan
 * ordering remains explicit while independent queues retain parallel submission capability.</p>
 */
final class RtDeviceQueueContexts implements AutoCloseable {
    private final VkQueue frameQueue;
    private final VkQueue buildQueue;
    private final RtCommandContext frameCommands;
    private final RtCommandContext buildCommands;
    private final RtCommandContext sectionBlasCommands;
    private final VulkanQueueTimeline buildTimeline;
    private final int queueFamilyIndex;
    private final int requestedQueueCount;

    private RtDeviceQueueContexts(
            VkQueue frameQueue,
            VkQueue buildQueue,
            RtCommandContext frameCommands,
            RtCommandContext buildCommands,
            RtCommandContext sectionBlasCommands,
            VulkanQueueTimeline buildTimeline,
            int queueFamilyIndex,
            int requestedQueueCount
    ) {
        this.frameQueue = Objects.requireNonNull(frameQueue, "frameQueue");
        this.buildQueue = Objects.requireNonNull(buildQueue, "buildQueue");
        this.frameCommands = Objects.requireNonNull(frameCommands, "frameCommands");
        this.buildCommands = Objects.requireNonNull(buildCommands, "buildCommands");
        this.sectionBlasCommands = Objects.requireNonNull(sectionBlasCommands, "sectionBlasCommands");
        this.buildTimeline = buildTimeline;
        if (queueFamilyIndex < 0 || requestedQueueCount <= 0) {
            throw new IllegalArgumentException("queue topology values are invalid");
        }
        this.queueFamilyIndex = queueFamilyIndex;
        this.requestedQueueCount = requestedQueueCount;
    }

    static RtDeviceQueueContexts create(
            MemoryStack stack,
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            int queueFamilyIndex,
            int requestedQueueCount,
            RtStallTelemetrySink stallTelemetry
    ) {
        return create(
                stack, physicalDevice, device, queueFamilyIndex, requestedQueueCount,
                stallTelemetry, true
        );
    }

    static RtDeviceQueueContexts create(
            MemoryStack stack,
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            int queueFamilyIndex,
            int requestedQueueCount,
            RtStallTelemetrySink stallTelemetry,
            boolean gpuTimingsEnabled
    ) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(physicalDevice, "physicalDevice");
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(stallTelemetry, "stallTelemetry");
        if (queueFamilyIndex < 0 || requestedQueueCount <= 0) {
            throw new IllegalArgumentException("queue topology values are invalid");
        }
        VkQueue frameQueue = getDeviceQueue(stack, device, queueFamilyIndex, 0);
        VkQueue buildQueue = requestedQueueCount > 1
                ? getDeviceQueue(stack, device, queueFamilyIndex, 1)
                : frameQueue;
        RtCommandContext.QueueSubmitLock frameLock = RtCommandContext.queueSubmitLock(frameQueue);
        RtCommandContext.QueueSubmitLock buildLock = buildQueue.address() == frameQueue.address()
                ? frameLock
                : RtCommandContext.queueSubmitLock(buildQueue);
        RtCommandContext frameCommands = null;
        RtCommandContext buildCommands = null;
        RtCommandContext sectionBlasCommands = null;
        VulkanQueueTimeline buildTimeline = null;
        try {
            if (requestedQueueCount > 1) buildTimeline = VulkanQueueTimeline.create(stack, device);
            frameCommands = RtCommandContext.create(
                    physicalDevice, device, frameQueue, frameLock, queueFamilyIndex,
                    stallTelemetry, gpuTimingsEnabled, buildTimeline, RtCommandContext.TimelineRole.CONSUMER);
            buildCommands = RtCommandContext.create(
                    physicalDevice, device, buildQueue, buildLock, queueFamilyIndex,
                    stallTelemetry, gpuTimingsEnabled, buildTimeline, RtCommandContext.TimelineRole.PRODUCER);
            sectionBlasCommands = RtCommandContext.create(
                    physicalDevice,
                    device,
                    buildQueue,
                    buildLock,
                    queueFamilyIndex,
                    stallTelemetry,
                    gpuTimingsEnabled,
                    buildTimeline,
                    RtCommandContext.TimelineRole.PRODUCER
            );
            RtDeviceQueueContexts contexts = new RtDeviceQueueContexts(
                    frameQueue,
                    buildQueue,
                    frameCommands,
                    buildCommands,
                    sectionBlasCommands,
                    buildTimeline,
                    queueFamilyIndex,
                    requestedQueueCount
            );
            frameCommands = null;
            buildCommands = null;
            sectionBlasCommands = null;
            buildTimeline = null;
            return contexts;
        } finally {
            closeSuppressing(sectionBlasCommands);
            closeSuppressing(buildCommands);
            closeSuppressing(frameCommands);
            closeSuppressing(buildTimeline);
        }
    }

    static int requestedQueueCount(int availableQueues, boolean timelineSemaphoreSupported) {
        if (availableQueues <= 0) {
            throw new IllegalArgumentException("availableQueues must be positive");
        }
        return timelineSemaphoreSupported && availableQueues >= 2 ? 2 : 1;
    }

    private static void closeSuppressing(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // The original construction failure is more informative than a rollback close failure.
        }
    }

    private static RuntimeException closeCollecting(RuntimeException failure, AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception closeFailure) {
            RuntimeException wrapped = closeFailure instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException("failed to close RT command context", closeFailure);
            if (failure == null) {
                return wrapped;
            }
            failure.addSuppressed(wrapped);
        }
        return failure;
    }

    private static VkQueue getDeviceQueue(
            MemoryStack stack,
            VkDevice device,
            int queueFamilyIndex,
            int queueIndex
    ) {
        PointerBuffer queueHandle = stack.mallocPointer(1);
        VK10.vkGetDeviceQueue(device, queueFamilyIndex, queueIndex, queueHandle);
        long address = queueHandle.get(0);
        if (address == 0L) {
            throw new IllegalStateException("vkGetDeviceQueue returned null for queueIndex=" + queueIndex);
        }
        return new VkQueue(address, device);
    }

    VkQueue frameQueue() {
        return frameQueue;
    }

    VkQueue buildQueue() {
        return buildQueue;
    }

    RtCommandContext frameCommands() {
        return frameCommands;
    }

    RtCommandContext buildCommands() {
        return buildCommands;
    }

    RtCommandContext sectionBlasCommands() {
        return sectionBlasCommands;
    }

    int queueFamilyIndex() {
        return queueFamilyIndex;
    }

    int requestedQueueCount() {
        return requestedQueueCount;
    }

    boolean queuesSeparated() {
        return frameQueue.address() != buildQueue.address();
    }

    boolean timelineEnabled() {
        return buildTimeline != null;
    }

    long completedBuildTimelineValue() {
        return buildTimeline == null ? 0L : buildTimeline.completedValue();
    }

    void waitForIdle() {
        frameCommands.waitQueueIdle("vkQueueWaitIdle.frameCloseBarrier");
        if (queuesSeparated()) {
            buildCommands.waitQueueIdle("vkQueueWaitIdle.buildCloseBarrier");
        }
    }

    /**
     * Releases command contexts and the optional build timeline.
     *
     * @throws RuntimeException if any owned queue resource fails to close
     */
    @Override
    public void close() {
        RuntimeException failure = null;
        failure = closeCollecting(failure, sectionBlasCommands);
        failure = closeCollecting(failure, buildCommands);
        failure = closeCollecting(failure, frameCommands);
        failure = closeCollecting(failure, buildTimeline);
        if (failure != null) {
            throw failure;
        }
    }
}
