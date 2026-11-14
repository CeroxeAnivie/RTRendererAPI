package top.ceroxe.rt.renderer.rt.device;

import top.ceroxe.rt.renderer.rt.device.interop.VulkanWin32ExternalSemaphoreProbe;
import top.ceroxe.rt.renderer.RtStallTelemetrySink;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkTimelineSemaphoreSubmitInfo;

import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * RT backend 专用 command / fence pool。
 *
 * <p>RT frame dispatch、material upload 和 BLAS/TLAS build 都会频繁提交短命令。
 * 成熟 Vulkan 后端的核心纪律不是每次提交都分配/销毁 command buffer 和 fence，
 * 而是让已完成的 GPU work 回到可 reset 的池里复用。这里把这条纪律收敛到一个边界：
 * 调用方只表达“录制一次提交”，资源生命周期由本类统一管理。</p>
 */
public final class RtCommandContext implements AutoCloseable {
    private static final String[] GPU_WORK_CHECKPOINTS = {"start", "end"};
    private static final long SLOW_HOST_STAGE_NANOS = 2_000_000L;
    private static final long DEFAULT_CLOSE_TIMEOUT_MILLIS = 30_000L;
    private static final String CLOSE_TIMEOUT_MILLIS_PROPERTY =
            "top.ceroxe.rt.vulkan.commandContext.closeTimeoutMillis";
    private final VkDevice device;
    private final VkQueue queue;
    private final QueueSubmitLock queueSubmitLock;
    private final Object commandPoolLock = new Object();
    private final long commandPool;
    private final RtGpuTimestampPool gpuTimestamps;
    private final RtStallTelemetrySink stallTelemetry;
    private final VulkanQueueTimeline queueTimeline;
    private final TimelineRole timelineRole;
    private final Deque<PooledCommandBuffer> freeCommandBuffers = new ArrayDeque<>();
    private final Deque<PooledFence> freeFences = new ArrayDeque<>();
    private final List<PooledCommandBuffer> commandBuffers = new ArrayList<>();
    private final List<PooledFence> fences = new ArrayList<>();
    private long syncSubmissions;
    private long asyncSubmissions;
    private long asyncCompletions;
    private long asyncPollsNotReady;
    private long commandBufferAllocations;
    private long commandBufferReuses;
    private long commandBufferReturns;
    private long commandBufferResets;
    private long fenceAllocations;
    private long fenceReuses;
    private long fenceReturns;
    private long fenceResets;
    private long lastSyncSubmissionNanos;
    private long maxSyncSubmissionNanos;
    private long totalSyncSubmissionNanos;
    private long lastAsyncSubmitNanos;
    private long maxAsyncSubmitNanos;
    private long totalAsyncSubmitNanos;
    private long lastAsyncLatencyNanos;
    private long maxAsyncLatencyNanos;
    private long totalAsyncLatencyNanos;
    private long lastCommandPoolWaitNanos;
    private long maxCommandPoolWaitNanos;
    private long totalCommandPoolWaitNanos;
    private long lastCommandRecordNanos;
    private long maxCommandRecordNanos;
    private long totalCommandRecordNanos;
    private long lastQueueLockWaitNanos;
    private long maxQueueLockWaitNanos;
    private long totalQueueLockWaitNanos;
    private long lastVkQueueSubmitNanos;
    private long maxVkQueueSubmitNanos;
    private long totalVkQueueSubmitNanos;
    private int activeAsyncRecordings;
    private volatile boolean closing;
    private volatile boolean closed;

    private RtCommandContext(
            VkDevice device,
            VkQueue queue,
            QueueSubmitLock queueSubmitLock,
            long commandPool,
            RtGpuTimestampPool gpuTimestamps,
            RtStallTelemetrySink stallTelemetry,
            VulkanQueueTimeline queueTimeline,
            TimelineRole timelineRole
    ) {
        this.device = Objects.requireNonNull(device, "device");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.queueSubmitLock = Objects.requireNonNull(queueSubmitLock, "queueSubmitLock");
        queueSubmitLock.requireQueue(queue);
        this.commandPool = commandPool;
        this.gpuTimestamps = gpuTimestamps;
        this.stallTelemetry = Objects.requireNonNull(stallTelemetry, "stallTelemetry");
        this.queueTimeline = queueTimeline;
        this.timelineRole = Objects.requireNonNull(timelineRole, "timelineRole");
        if ((queueTimeline == null) != (timelineRole == TimelineRole.NONE)) {
            throw new IllegalArgumentException("timeline role and timeline ownership must agree");
        }
    }

    static RtCommandContext create(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkQueue queue,
            int queueFamilyIndex
    ) {
        return create(
                physicalDevice,
                device,
                queue,
                queueSubmitLock(queue),
                queueFamilyIndex,
                RtStallTelemetrySink.NOOP
        );
    }

    static RtCommandContext create(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkQueue queue,
            QueueSubmitLock queueSubmitLock,
            int queueFamilyIndex
    ) {
        return create(
                physicalDevice,
                device,
                queue,
                queueSubmitLock,
                queueFamilyIndex,
                RtStallTelemetrySink.NOOP
        );
    }

    static RtCommandContext create(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkQueue queue,
            QueueSubmitLock queueSubmitLock,
            int queueFamilyIndex,
            RtStallTelemetrySink stallTelemetry
    ) {
        return create(
                physicalDevice, device, queue, queueSubmitLock, queueFamilyIndex, stallTelemetry, true
        );
    }

    static RtCommandContext create(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkQueue queue,
            QueueSubmitLock queueSubmitLock,
            int queueFamilyIndex,
            RtStallTelemetrySink stallTelemetry,
            boolean gpuTimingsEnabled
    ) {
        return create(
                physicalDevice, device, queue, queueSubmitLock, queueFamilyIndex,
                stallTelemetry, gpuTimingsEnabled, null, TimelineRole.NONE
        );
    }

    static RtCommandContext create(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkQueue queue,
            QueueSubmitLock queueSubmitLock,
            int queueFamilyIndex,
            RtStallTelemetrySink stallTelemetry,
            boolean gpuTimingsEnabled,
            VulkanQueueTimeline queueTimeline,
            TimelineRole timelineRole
    ) {
        Objects.requireNonNull(physicalDevice, "physicalDevice");
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(queue, "queue");
        Objects.requireNonNull(queueSubmitLock, "queueSubmitLock").requireQueue(queue);
        if (queueFamilyIndex < 0) {
            throw new IllegalArgumentException("queueFamilyIndex must not be negative");
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo createInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(queueFamilyIndex);

            LongBuffer poolHandle = stack.longs(0L);
            checkVk(VK10.vkCreateCommandPool(device, createInfo, null, poolHandle), "vkCreateCommandPool");
            long commandPool = poolHandle.get(0);
            RtGpuTimestampPool gpuTimestamps = null;
            boolean transferred = false;
            try {
                try {
                    if (gpuTimingsEnabled) {
                        gpuTimestamps = RtGpuTimestampPool.create(stack, physicalDevice, device, queueFamilyIndex);
                    }
                } catch (RuntimeException | LinkageError ex) {
                    /*
                     * GPU timing is evidence, never a renderer availability dependency. A driver
                     * may expose timestamp bits yet reject query-pool allocation under pressure;
                     * keep the command lane operational and make the degraded mode explicit.
                     */
                    top.ceroxe.rt.renderer.RendererLog.warn(
                            "Vulkan GPU timestamp instrumentation unavailable; continuing without it",
                            ex
                    );
                }
                RtCommandContext context = new RtCommandContext(
                        device,
                        queue,
                        queueSubmitLock,
                        commandPool,
                        gpuTimestamps,
                        stallTelemetry,
                        queueTimeline,
                        timelineRole
                );
                transferred = true;
                return context;
            } finally {
                if (!transferred) {
                    try {
                        if (gpuTimestamps != null) {
                            gpuTimestamps.close();
                        }
                    } finally {
                        VK10.vkDestroyCommandPool(device, commandPool, null);
                    }
                }
            }
        }
    }

    static QueueSubmitLock queueSubmitLock(VkQueue queue) {
        return new QueueSubmitLock(Objects.requireNonNull(queue, "queue").address());
    }

    private static long closeTimeoutNanos() {
        long millis = Long.getLong(CLOSE_TIMEOUT_MILLIS_PROPERTY, DEFAULT_CLOSE_TIMEOUT_MILLIS);
        if (millis <= 0L) millis = DEFAULT_CLOSE_TIMEOUT_MILLIS;
        return millis >= Long.MAX_VALUE / 1_000_000L ? Long.MAX_VALUE : millis * 1_000_000L;
    }

    private static long nanosToMillis(long nanos) {
        return nanos / 1_000_000L;
    }

    private static long saturatingAdd(long left, long right) {
        return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static void checkVk(int result, String stage) {
        VulkanFailures.check(result, stage);
    }

    private static void closeGpuTimestampCapture(RtGpuTimestampPool.Capture capture) {
        try {
            capture.close();
        } catch (RuntimeException ex) {
            top.ceroxe.rt.renderer.RendererLog.warn(
                    "Unable to release optional Vulkan GPU timestamp capture",
                    ex
            );
        }
    }

    private static String vkResultName(int result) {
        return switch (result) {
            case VK10.VK_SUCCESS -> "VK_SUCCESS";
            case VK10.VK_NOT_READY -> "VK_NOT_READY";
            case VK10.VK_ERROR_OUT_OF_HOST_MEMORY -> "VK_ERROR_OUT_OF_HOST_MEMORY";
            case VK10.VK_ERROR_OUT_OF_DEVICE_MEMORY -> "VK_ERROR_OUT_OF_DEVICE_MEMORY";
            case VK10.VK_ERROR_DEVICE_LOST -> "VK_ERROR_DEVICE_LOST";
            default -> Integer.toString(result);
        };
    }

    /**
     * 返回由该上下文串行化提交的原生队列数量。
     *
     * @return 固定为 {@code 1}，因为一个上下文只封装一个 Vulkan 队列
     */
    public int orderedQueueCount() {
        return 1;
    }

    /**
     * 返回接收命令录制与提交停顿数据的遥测接收器。
     *
     * @return 与该上下文生命周期一致的遥测接收器
     */
    public RtStallTelemetrySink stallTelemetry() {
        return stallTelemetry;
    }

    /**
     * 录制并同步提交一次性命令，在返回前等待所属队列空闲。
     *
     * @param recorder 在临时命令缓冲区中写入命令的回调
     */
    public synchronized void submitOneTime(CommandRecorder recorder) {
        Objects.requireNonNull(recorder, "recorder");
        requireAcceptingWork();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            PooledCommandBuffer pooledCommandBuffer;
            synchronized (commandPoolLock) {
                pooledCommandBuffer = acquireCommandBuffer(stack);
            }
            VkCommandBuffer commandBuffer = pooledCommandBuffer.commandBuffer();
            boolean submitted = false;
            try {
                synchronized (commandPoolLock) {
                    VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                            .sType$Default()
                            .flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
                    checkVk(VK10.vkBeginCommandBuffer(commandBuffer, beginInfo), "vkBeginCommandBuffer");

                    recorder.record(commandBuffer, stack);

                    checkVk(VK10.vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer");
                }

                PointerBuffer commandBuffers = stack.pointers(commandBuffer.address());
                VkSubmitInfo.Buffer submitInfo = VkSubmitInfo.calloc(1, stack)
                        .sType$Default()
                        .pCommandBuffers(commandBuffers);
                long submitStart = System.nanoTime();
                long timelineSignalValue;
                synchronized (queueSubmitLock.monitor()) {
                    timelineSignalValue = configureSubmissionTimeline(stack, submitInfo, 0L, 0L);
                    checkVk(VK10.vkQueueSubmit(queue, submitInfo, VK10.VK_NULL_HANDLE), "vkQueueSubmit");
                    markTimelineSubmitted(timelineSignalValue);
                    submitted = true;
                    checkVk(VK10.vkQueueWaitIdle(queue), "vkQueueWaitIdle");
                    markTimelineCompleted(timelineSignalValue);
                }
                recordSyncSubmission(System.nanoTime() - submitStart);
            } finally {
                if (!submitted) {
                    waitQueueIdleUnchecked();
                }
                releaseCommandBuffer(pooledCommandBuffer);
            }
        }
    }

    /**
     * 录制并异步提交一次性命令。
     *
     * @param recorder 在临时命令缓冲区中写入命令的回调
     * @return 用于轮询完成状态并回收提交资源的句柄
     */
    public AsyncSubmission submitOneTimeAsync(CommandRecorder recorder) {
        return submitOneTimeAsync(recorder, 0L, 0L, null);
    }

    /**
     * 录制并异步提交一次性命令，并在该提交完成时发出外部信号量信号。
     * 调用方必须使信号量保持有效，直至返回的提交句柄报告完成。
     *
     * @param recorder        在临时命令缓冲区中写入命令的回调
     * @param signalSemaphore 随队列提交发出信号的外部信号量；可为 {@code null}
     * @return 用于轮询完成状态并回收提交资源的句柄
     */
    public AsyncSubmission submitOneTimeAsync(
            CommandRecorder recorder,
            VulkanWin32ExternalSemaphoreProbe.ExportedSemaphore signalSemaphore
    ) {
        return submitOneTimeAsync(
                recorder,
                0L,
                signalSemaphore == null ? 0L : signalSemaphore.semaphore(),
                null
        );
    }

    /**
     * Records and asynchronously submits work bracketed by ordinary same-device binary
     * semaphores. This is the managed swapchain path: acquire is waited before transfer commands,
     * render-done is signalled for presentation, and the command lane preserves its existing
     * queue timeline and host-synchronization rules.
     *
     * @param waitSemaphore non-null binary semaphore that makes the command buffer executable
     * @param signalSemaphore non-null binary semaphore signalled after command-buffer completion
     * @param recorder callback that records commands into the temporary primary command buffer
     * @return fence-backed asynchronous submission whose close waits and releases owned resources
     */
    public AsyncSubmission submitBinarySynchronizedOneTimeAsync(
            long waitSemaphore,
            long signalSemaphore,
            CommandRecorder recorder
    ) {
        if (waitSemaphore == 0L || signalSemaphore == 0L) {
            throw new IllegalArgumentException("binary wait and signal semaphores must not be null");
        }
        return submitOneTimeAsync(recorder, waitSemaphore, signalSemaphore, null);
    }

    /**
     * Timed variant of {@link #submitBinarySynchronizedOneTimeAsync(long, long, CommandRecorder)}.
     * Query exhaustion degrades to ordinary synchronized submission without delaying the frame.
     *
     * @param timingLabel non-blank GPU timestamp aggregation label
     * @param waitSemaphore non-null binary semaphore that makes the command buffer executable
     * @param signalSemaphore non-null binary semaphore signalled after command-buffer completion
     * @param recorder callback that records the measured commands
     * @return fence-backed asynchronous submission carrying timing when a query was available
     */
    public AsyncSubmission submitTimedBinarySynchronizedOneTimeAsync(
            String timingLabel,
            long waitSemaphore,
            long signalSemaphore,
            CommandRecorder recorder
    ) {
        Objects.requireNonNull(recorder, "recorder");
        if (timingLabel == null || timingLabel.isBlank()) {
            throw new IllegalArgumentException("GPU timing label must not be blank");
        }
        if (waitSemaphore == 0L || signalSemaphore == 0L) {
            throw new IllegalArgumentException("binary wait and signal semaphores must not be null");
        }
        RtGpuTimestampPool.Capture capture = acquireGpuTimestampCapture(
                timingLabel, GPU_WORK_CHECKPOINTS
        );
        boolean transferred = false;
        try {
            CommandRecorder timedRecorder = capture == null
                    ? recorder
                    : (commandBuffer, stack) -> {
                capture.begin(commandBuffer, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
                recorder.record(commandBuffer, stack);
                capture.write(commandBuffer, VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
            };
            AsyncSubmission submission = submitOneTimeAsync(
                    timedRecorder, waitSemaphore, signalSemaphore, capture
            );
            transferred = true;
            return submission;
        } finally {
            if (!transferred && capture != null) capture.close();
        }
    }

    /**
     * Submits one labelled GPU workload whose timestamp lifetime follows the submission fence.
     * Query exhaustion degrades to an ordinary async submission and never delays GPU work.
     *
     * @param timingLabel 标识时间戳结果的非空白名称
     * @param recorder    在临时命令缓冲区中写入命令的回调
     * @return 同时持有提交资源和可选时间戳捕获的异步提交句柄
     */
    public AsyncSubmission submitTimedOneTimeAsync(String timingLabel, CommandRecorder recorder) {
        Objects.requireNonNull(recorder, "recorder");
        if (timingLabel == null || timingLabel.isBlank()) {
            throw new IllegalArgumentException("GPU timing label must not be blank");
        }
        RtGpuTimestampPool.Capture capture = acquireGpuTimestampCapture(timingLabel, GPU_WORK_CHECKPOINTS);
        boolean transferred = false;
        try {
            CommandRecorder timedRecorder = capture == null
                    ? recorder
                    : (commandBuffer, stack) -> {
                capture.begin(commandBuffer, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
                recorder.record(commandBuffer, stack);
                capture.write(commandBuffer, VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
            };
            AsyncSubmission submission = submitOneTimeAsync(timedRecorder, 0L, 0L, capture);
            transferred = true;
            return submission;
        } finally {
            if (!transferred && capture != null) {
                capture.close();
            }
        }
    }

    /**
     * Submits one timed workload and signals a caller-owned timeline semaphore on completion.
     * The signal value must be reserved monotonically by the semaphore owner.
     *
     * @param timingLabel non-blank GPU timing label
     * @param timelineSemaphore non-null same-device timeline semaphore
     * @param timelineValue positive value signalled by this submission
     * @param recorder command recorder invoked before submission
     * @return asynchronous submission retaining command and timestamp resources
     */
    public AsyncSubmission submitTimedOneTimeAsync(
            String timingLabel,
            long timelineSemaphore,
            long timelineValue,
            CommandRecorder recorder
    ) {
        Objects.requireNonNull(recorder, "recorder");
        if (timingLabel == null || timingLabel.isBlank()) {
            throw new IllegalArgumentException("GPU timing label must not be blank");
        }
        if (timelineSemaphore == 0L || timelineValue <= 0L) {
            throw new IllegalArgumentException("timeline signal handle and value must be positive");
        }
        RtGpuTimestampPool.Capture capture = acquireGpuTimestampCapture(
                timingLabel, GPU_WORK_CHECKPOINTS
        );
        boolean transferred = false;
        try {
            CommandRecorder timedRecorder = capture == null
                    ? recorder
                    : (commandBuffer, stack) -> {
                capture.begin(commandBuffer, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
                recorder.record(commandBuffer, stack);
                capture.write(commandBuffer, VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
            };
            AsyncSubmission submission = submitOneTimeAsync(
                    timedRecorder,
                    0L,
                    0L,
                    timelineSemaphore,
                    timelineValue,
                    capture
            );
            transferred = true;
            return submission;
        } finally {
            if (!transferred && capture != null) capture.close();
        }
    }

    /**
     * Submits timed work after a caller-owned timeline wait and optionally signals another
     * caller-owned timeline semaphore. The wait creates the device-memory dependency that a host
     * timeline wait alone cannot provide.
     *
     * @param timingLabel non-blank diagnostic label for GPU timestamp aggregation
     * @param timelineWaitSemaphore non-zero semaphore waited by the submission
     * @param timelineWaitValue positive timeline value to wait
     * @param timelineSignalSemaphore optional semaphore signaled by the submission
     * @param timelineSignalValue signal value, or zero with a zero signal semaphore
     * @param recorder borrowed command-buffer recorder
     * @return owned asynchronous submission and timing result
     */
    public AsyncSubmission submitTimedTimelineSynchronizedOneTimeAsync(
            String timingLabel,
            long timelineWaitSemaphore,
            long timelineWaitValue,
            long timelineSignalSemaphore,
            long timelineSignalValue,
            CommandRecorder recorder
    ) {
        Objects.requireNonNull(recorder, "recorder");
        if (timingLabel == null || timingLabel.isBlank()) {
            throw new IllegalArgumentException("GPU timing label must not be blank");
        }
        if (timelineWaitSemaphore == 0L || timelineWaitValue <= 0L) {
            throw new IllegalArgumentException("timeline wait handle and value must be positive");
        }
        if ((timelineSignalSemaphore == 0L) != (timelineSignalValue == 0L)
                || timelineSignalValue < 0L) {
            throw new IllegalArgumentException("timeline signal handle and value are inconsistent");
        }
        RtGpuTimestampPool.Capture capture = acquireGpuTimestampCapture(timingLabel, GPU_WORK_CHECKPOINTS);
        boolean transferred = false;
        try {
            CommandRecorder timedRecorder = capture == null
                    ? recorder
                    : (commandBuffer, stack) -> {
                capture.begin(commandBuffer, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
                recorder.record(commandBuffer, stack);
                capture.write(commandBuffer, VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
            };
            AsyncSubmission submission = submitOneTimeAsync(
                    timedRecorder,
                    0L,
                    0L,
                    timelineWaitSemaphore,
                    timelineWaitValue,
                    timelineSignalSemaphore,
                    timelineSignalValue,
                    capture
            );
            transferred = true;
            return submission;
        } finally {
            if (!transferred && capture != null) capture.close();
        }
    }

    private AsyncSubmission submitOneTimeAsync(
            CommandRecorder recorder,
            long waitSemaphore,
            long signalSemaphore,
            RtGpuTimestampPool.Capture gpuTimestamps
    ) {
        return submitOneTimeAsync(
                recorder, waitSemaphore, signalSemaphore, 0L, 0L, gpuTimestamps
        );
    }

    private AsyncSubmission submitOneTimeAsync(
            CommandRecorder recorder,
            long waitSemaphore,
            long signalSemaphore,
            long timelineSignalSemaphore,
            long requestedTimelineSignalValue,
            RtGpuTimestampPool.Capture gpuTimestamps
    ) {
        return submitOneTimeAsync(
                recorder, waitSemaphore, signalSemaphore,
                0L, 0L, timelineSignalSemaphore, requestedTimelineSignalValue, gpuTimestamps
        );
    }

    private AsyncSubmission submitOneTimeAsync(
            CommandRecorder recorder,
            long waitSemaphore,
            long signalSemaphore,
            long timelineWaitSemaphore,
            long requestedTimelineWaitValue,
            long timelineSignalSemaphore,
            long requestedTimelineSignalValue,
            RtGpuTimestampPool.Capture gpuTimestamps
    ) {
        Objects.requireNonNull(recorder, "recorder");
        RecordedCommandBuffer recording = recordOneTime(recorder);
        try {
            return submitRecordedAsync(
                    List.of(recording),
                    waitSemaphore,
                    signalSemaphore,
                    timelineWaitSemaphore,
                    requestedTimelineWaitValue,
                    timelineSignalSemaphore,
                    requestedTimelineSignalValue,
                    gpuTimestamps
            );
        } catch (RuntimeException | Error ex) {
            recording.close();
            throw ex;
        }
    }

    /**
     * 录制一次性命令，但不立即提交。
     *
     * @param recorder 在临时命令缓冲区中写入命令的回调
     * @return 可提交一次或显式关闭的已录制命令缓冲区
     */
    public RecordedCommandBuffer recordOneTime(CommandRecorder recorder) {
        return recordOneTime(recorder, null);
    }

    /**
     * Records a labelled GPU workload whose capture remains owned by the recorded buffer.
     *
     * @param timingLabel 标识时间戳结果的非空白名称
     * @param recorder    在临时命令缓冲区中写入命令的回调
     * @return 持有命令缓冲区和可选时间戳捕获的已录制句柄
     */
    public RecordedCommandBuffer recordTimedOneTime(String timingLabel, CommandRecorder recorder) {
        Objects.requireNonNull(recorder, "recorder");
        if (timingLabel == null || timingLabel.isBlank()) {
            throw new IllegalArgumentException("GPU timing label must not be blank");
        }
        RtGpuTimestampPool.Capture capture = acquireGpuTimestampCapture(timingLabel, GPU_WORK_CHECKPOINTS);
        boolean transferred = false;
        try {
            CommandRecorder timedRecorder = capture == null
                    ? recorder
                    : (commandBuffer, stack) -> {
                capture.begin(commandBuffer, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
                recorder.record(commandBuffer, stack);
                capture.write(commandBuffer, VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
            };
            RecordedCommandBuffer recording = recordOneTime(timedRecorder, capture);
            transferred = true;
            return recording;
        } finally {
            if (!transferred && capture != null) {
                capture.close();
            }
        }
    }

    private RecordedCommandBuffer recordOneTime(
            CommandRecorder recorder,
            RtGpuTimestampPool.Capture gpuTimestamps
    ) {
        Objects.requireNonNull(recorder, "recorder");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PooledCommandBuffer pooledCommandBuffer;
            long acquireWaitStartNanos = System.nanoTime();
            long acquireWaitNanos;
            synchronized (this) {
                requireAcceptingWork();
                synchronized (commandPoolLock) {
                    acquireWaitNanos = System.nanoTime() - acquireWaitStartNanos;
                    pooledCommandBuffer = acquireCommandBuffer(stack);
                }
                activeAsyncRecordings++;
            }
            VkCommandBuffer commandBuffer = pooledCommandBuffer.commandBuffer();
            boolean recorded = false;
            long recordStartNanos = System.nanoTime();
            try {
                synchronized (commandPoolLock) {
                    VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                            .sType$Default()
                            .flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
                    checkVk(VK10.vkBeginCommandBuffer(commandBuffer, beginInfo), "vkBeginCommandBuffer.async");

                    recorder.record(commandBuffer, stack);

                    checkVk(VK10.vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer.async");
                }
                long recordNanos = System.nanoTime() - recordStartNanos;
                recordCommandHostTiming(acquireWaitNanos, recordNanos, "recordOneTime");
                recorded = true;
                return new RecordedCommandBuffer(
                        pooledCommandBuffer,
                        acquireWaitNanos,
                        recordNanos,
                        gpuTimestamps
                );
            } finally {
                if (!recorded) {
                    synchronized (this) {
                        releaseCommandBuffer(pooledCommandBuffer);
                    }
                }
                synchronized (this) {
                    activeAsyncRecordings--;
                    notifyAll();
                }
            }
        }
    }

    /**
     * 将一批已录制命令缓冲区作为单次队列提交异步执行。
     * 成功调用会消费列表中的每个句柄；这些句柄此后不能再次提交。
     *
     * @param recordings 非空的已录制命令缓冲区列表
     * @return 用于轮询完成状态并回收整批资源的句柄
     */
    public AsyncSubmission submitRecordedAsync(List<RecordedCommandBuffer> recordings) {
        return submitRecordedAsync(recordings, null);
    }

    /**
     * 将一批已录制命令缓冲区异步提交，并在完成时发出外部信号量信号。
     * 成功调用会消费每个录制句柄；调用方必须使信号量保持有效，直至提交完成。
     *
     * @param recordings      非空的已录制命令缓冲区列表
     * @param signalSemaphore 随队列提交发出信号的外部信号量；可为 {@code null}
     * @return 用于轮询完成状态并回收整批资源的句柄
     */
    public AsyncSubmission submitRecordedAsync(
            List<RecordedCommandBuffer> recordings,
            VulkanWin32ExternalSemaphoreProbe.ExportedSemaphore signalSemaphore
    ) {
        return submitRecordedAsync(
                recordings,
                0L,
                signalSemaphore == null ? 0L : signalSemaphore.semaphore(),
                null
        );
    }

    /**
     * Enqueues a fence-backed execution barrier that waits for an imported binary semaphore.
     * A recorded TOP-to-BOTTOM barrier gives the wait a concrete execution scope; an empty submit
     * or empty command buffer permits drivers to retire the fence without executing a useful stage.
     * The caller must retain the semaphore until the returned submission completes.
     *
     * @param waitSemaphore 要等待的非空 Vulkan 二进制信号量句柄
     * @return 表示信号量等待与执行屏障完成状态的异步提交句柄
     */
    public AsyncSubmission submitSemaphoreWaitAsync(long waitSemaphore) {
        if (waitSemaphore == 0L) throw new IllegalArgumentException("waitSemaphore must not be null");
        RecordedCommandBuffer recording = recordOneTime((commandBuffer, stack) -> {
            VkMemoryBarrier.Buffer executionBarrier = VkMemoryBarrier.calloc(1, stack)
                    .sType$Default()
                    .srcAccessMask(0)
                    .dstAccessMask(0);
            VK10.vkCmdPipelineBarrier(
                    commandBuffer,
                    VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                    0,
                    executionBarrier,
                    null,
                    null
            );
        });
        try {
            return submitRecordedAsync(List.of(recording), waitSemaphore, 0L, null);
        } catch (RuntimeException | Error failure) {
            recording.close();
            throw failure;
        }
    }

    private AsyncSubmission submitRecordedAsync(
            List<RecordedCommandBuffer> recordings,
            long waitSemaphore,
            long signalSemaphore,
            RtGpuTimestampPool.Capture gpuTimestamps
    ) {
        return submitRecordedAsync(
                recordings, waitSemaphore, signalSemaphore, 0L, 0L, gpuTimestamps
        );
    }

    private AsyncSubmission submitRecordedAsync(
            List<RecordedCommandBuffer> recordings,
            long waitSemaphore,
            long signalSemaphore,
            long timelineSignalSemaphore,
            long requestedTimelineSignalValue,
            RtGpuTimestampPool.Capture gpuTimestamps
    ) {
        return submitRecordedAsync(
                recordings, waitSemaphore, signalSemaphore,
                0L, 0L, timelineSignalSemaphore, requestedTimelineSignalValue, gpuTimestamps
        );
    }

    private AsyncSubmission submitRecordedAsync(
            List<RecordedCommandBuffer> recordings,
            long waitSemaphore,
            long signalSemaphore,
            long timelineWaitSemaphore,
            long requestedTimelineWaitValue,
            long timelineSignalSemaphore,
            long requestedTimelineSignalValue,
            RtGpuTimestampPool.Capture gpuTimestamps
    ) {
        Objects.requireNonNull(recordings, "recordings");
        if (recordings.isEmpty()) {
            throw new IllegalArgumentException("recorded command buffer batch must not be empty");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            List<PooledCommandBuffer> commandBuffers = new ArrayList<>(recordings.size());
            List<RtGpuTimestampPool.Capture> timestampCaptures = new ArrayList<>(recordings.size() + 1);
            if (gpuTimestamps != null) {
                timestampCaptures.add(gpuTimestamps);
            }
            PooledFence pooledFence = null;
            boolean submitted = false;
            try {
                synchronized (this) {
                    requireAcceptingWork();
                    for (RecordedCommandBuffer recording : recordings) {
                        TakenRecording taken = recording.takeForSubmission();
                        commandBuffers.add(taken.commandBuffer());
                        if (taken.gpuTimestamps() != null) {
                            timestampCaptures.add(taken.gpuTimestamps());
                        }
                    }
                    pooledFence = acquireFence(stack);
                }
                long totalAcquireWaitNanos = 0L;
                long totalRecordNanos = 0L;
                for (RecordedCommandBuffer recording : recordings) {
                    totalAcquireWaitNanos = saturatingAdd(totalAcquireWaitNanos, recording.acquireWaitNanos());
                    totalRecordNanos = saturatingAdd(totalRecordNanos, recording.recordNanos());
                }
                AsyncSubmission submission = new AsyncSubmission(
                        List.copyOf(commandBuffers),
                        pooledFence,
                        totalAcquireWaitNanos,
                        totalRecordNanos,
                        List.copyOf(timestampCaptures)
                );
                PointerBuffer handles = stack.mallocPointer(commandBuffers.size());
                for (PooledCommandBuffer commandBuffer : commandBuffers) {
                    handles.put(commandBuffer.commandBuffer().address());
                }
                handles.flip();
                VkSubmitInfo.Buffer submitInfo = VkSubmitInfo.calloc(1, stack)
                        .sType$Default()
                        .pCommandBuffers(handles);
                long submitStart = System.nanoTime();
                long queueLockAcquiredNanos;
                long submitCompleteNanos;
                long timelineSignalValue;
                synchronized (queueSubmitLock.monitor()) {
                    queueLockAcquiredNanos = System.nanoTime();
                    timelineSignalValue = configureSubmissionTimeline(
                            stack,
                            submitInfo,
                            waitSemaphore,
                            signalSemaphore,
                            timelineWaitSemaphore,
                            requestedTimelineWaitValue,
                            timelineSignalSemaphore,
                            requestedTimelineSignalValue
                    );
                    checkVk(VK10.vkQueueSubmit(queue, submitInfo, pooledFence.fence()), "vkQueueSubmit.recordedAsync");
                    markTimelineSubmitted(timelineSignalValue);
                    submitCompleteNanos = System.nanoTime();
                }
                submitted = true;
                submission.markSubmitted(
                        submitStart, queueLockAcquiredNanos, submitCompleteNanos, timelineSignalValue);
                synchronized (this) {
                    recordAsyncSubmit(
                            submitCompleteNanos - submitStart,
                            queueLockAcquiredNanos - submitStart,
                            submitCompleteNanos - queueLockAcquiredNanos
                    );
                }
                logSlowHostStage(
                        "queueSubmit",
                        queueLockAcquiredNanos - submitStart,
                        submitCompleteNanos - queueLockAcquiredNanos
                );
                return submission;
            } finally {
                if (!submitted) {
                    synchronized (this) {
                        if (pooledFence != null) {
                            releaseFence(pooledFence);
                        }
                        for (PooledCommandBuffer commandBuffer : commandBuffers) {
                            releaseCommandBuffer(commandBuffer);
                        }
                        for (RtGpuTimestampPool.Capture capture : timestampCaptures) {
                            closeGpuTimestampCapture(capture);
                        }
                    }
                }
            }
        }
    }

    void waitQueueIdle(String operation) {
        Objects.requireNonNull(operation, "operation");
        synchronized (queueSubmitLock.monitor()) {
            checkVk(VK10.vkQueueWaitIdle(queue), operation);
            if (timelineRole == TimelineRole.PRODUCER) {
                long submittedValue = queueTimeline.lastSubmittedValue();
                if (submittedValue > 0L) queueTimeline.markCompleted(submittedValue);
            }
        }
    }

    private long configureSubmissionTimeline(
            MemoryStack stack,
            VkSubmitInfo.Buffer submitInfo,
            long binaryWaitSemaphore,
            long binarySignalSemaphore
    ) {
        return configureSubmissionTimeline(
                stack, submitInfo, binaryWaitSemaphore, binarySignalSemaphore,
                0L, 0L, 0L, 0L
        );
    }

    private long configureSubmissionTimeline(
            MemoryStack stack,
            VkSubmitInfo.Buffer submitInfo,
            long binaryWaitSemaphore,
            long binarySignalSemaphore,
            long externalTimelineWaitSemaphore,
            long externalTimelineWaitValue,
            long externalTimelineSignalSemaphore,
            long externalTimelineSignalValue
    ) {
        if ((externalTimelineWaitSemaphore == 0L) != (externalTimelineWaitValue == 0L)
                || externalTimelineWaitValue < 0L
                || (externalTimelineSignalSemaphore == 0L) != (externalTimelineSignalValue == 0L)
                || externalTimelineSignalValue < 0L) {
            throw new IllegalArgumentException("external timeline dependency is inconsistent");
        }
        long timelineWaitValue = timelineRole == TimelineRole.CONSUMER
                ? queueTimeline.completedValue()
                : 0L;
        long timelineSignalValue = timelineRole == TimelineRole.PRODUCER
                ? queueTimeline.reserveSignalValue()
                : 0L;
        int waitCount = (binaryWaitSemaphore == 0L ? 0 : 1)
                + (externalTimelineWaitValue == 0L ? 0 : 1)
                + (timelineWaitValue == 0L ? 0 : 1);
        int signalCount = (binarySignalSemaphore == 0L ? 0 : 1)
                + (externalTimelineSignalValue == 0L ? 0 : 1)
                + (timelineSignalValue == 0L ? 0 : 1);
        if (waitCount > 0) {
            LongBuffer semaphores = stack.mallocLong(waitCount);
            java.nio.IntBuffer stages = stack.mallocInt(waitCount);
            if (binaryWaitSemaphore != 0L) {
                semaphores.put(binaryWaitSemaphore);
                stages.put(VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
            }
            if (externalTimelineWaitValue != 0L) {
                semaphores.put(externalTimelineWaitSemaphore);
                stages.put(VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
            }
            if (timelineWaitValue != 0L) {
                semaphores.put(queueTimeline.semaphore());
                stages.put(VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
            }
            submitInfo
                    .waitSemaphoreCount(waitCount)
                    .pWaitSemaphores(semaphores.flip())
                    .pWaitDstStageMask(stages.flip());
        }
        if (signalCount > 0) {
            LongBuffer semaphores = stack.mallocLong(signalCount);
            if (binarySignalSemaphore != 0L) semaphores.put(binarySignalSemaphore);
            if (externalTimelineSignalValue != 0L) {
                semaphores.put(externalTimelineSignalSemaphore);
            }
            if (timelineSignalValue != 0L) semaphores.put(queueTimeline.semaphore());
            submitInfo.pSignalSemaphores(semaphores.flip());
        }
        if (externalTimelineWaitValue != 0L
                || timelineWaitValue != 0L
                || externalTimelineSignalValue != 0L
                || timelineSignalValue != 0L) {
            LongBuffer waitValues = stack.callocLong(waitCount);
            LongBuffer signalValues = stack.callocLong(signalCount);
            int waitValueIndex = binaryWaitSemaphore == 0L ? 0 : 1;
            if (externalTimelineWaitValue != 0L) {
                waitValues.put(waitValueIndex++, externalTimelineWaitValue);
            }
            if (timelineWaitValue != 0L) waitValues.put(waitValueIndex, timelineWaitValue);
            int signalValueIndex = binarySignalSemaphore == 0L ? 0 : 1;
            if (externalTimelineSignalValue != 0L) {
                signalValues.put(signalValueIndex++, externalTimelineSignalValue);
            }
            if (timelineSignalValue != 0L) signalValues.put(signalCount - 1, timelineSignalValue);
            VkTimelineSemaphoreSubmitInfo timelineInfo = VkTimelineSemaphoreSubmitInfo.calloc(stack)
                    .sType$Default()
                    .pWaitSemaphoreValues(waitValues)
                    .pSignalSemaphoreValues(signalValues);
            submitInfo.pNext(timelineInfo.address());
        }
        if (submitInfo.waitSemaphoreCount() != waitCount
                || submitInfo.signalSemaphoreCount() != signalCount) {
            throw new IllegalStateException(
                    "Vulkan submit semaphore counts diverged: expected wait=" + waitCount
                            + ", actual wait=" + submitInfo.waitSemaphoreCount()
                            + ", expected signal=" + signalCount
                            + ", actual signal=" + submitInfo.signalSemaphoreCount()
            );
        }
        if (binaryWaitSemaphore != 0L) {
            LongBuffer configuredWaits = submitInfo.pWaitSemaphores();
            if (configuredWaits == null || configuredWaits.remaining() != waitCount
                    || configuredWaits.get(configuredWaits.position()) != binaryWaitSemaphore) {
                throw new IllegalStateException("binary acquire semaphore was not retained by VkSubmitInfo");
            }
        }
        return timelineSignalValue;
    }

    private void markTimelineSubmitted(long value) {
        if (value != 0L) queueTimeline.markSubmitted(value);
    }

    private void markTimelineCompleted(long value) {
        if (value != 0L) queueTimeline.markCompleted(value);
    }

    /**
     * Acquires diagnostic timestamp storage without waiting for an in-flight slot.
     *
     * @param label       标识时间戳序列的名称
     * @param checkpoints 按写入顺序命名各时间戳检查点的数组
     * @return 已获取的捕获对象；未启用时间戳或池中没有空闲槽位时返回 {@code null}
     */
    public RtGpuTimestampPool.Capture acquireGpuTimestampCapture(String label, String[] checkpoints) {
        RtGpuTimestampPool timestamps = gpuTimestamps;
        return timestamps == null ? null : timestamps.acquire(label, checkpoints);
    }

    /**
     * 返回所有 GPU 时间戳捕获的当前诊断快照。
     *
     * @return 时间戳池快照；未启用时间戳时返回禁用状态快照
     */
    public RtGpuTimestampPool.Snapshot gpuTimestampSnapshot() {
        RtGpuTimestampPool timestamps = gpuTimestamps;
        return timestamps == null ? RtGpuTimestampPool.Snapshot.disabled() : timestamps.snapshot();
    }

    /**
     * 返回指定标签的 GPU 阶段时间戳快照。
     *
     * @param label 要查询的阶段标签
     * @return 对应阶段快照；未启用时间戳时返回该标签的禁用状态快照
     */
    public RtGpuTimestampPool.StageSnapshot gpuStageTimestampSnapshot(String label) {
        RtGpuTimestampPool timestamps = gpuTimestamps;
        return timestamps == null
                ? RtGpuTimestampPool.StageSnapshot.disabled(label)
                : timestamps.stageSnapshot(label);
    }

    /**
     * 生成命令池、提交延迟及 GPU 时间戳状态的单行诊断摘要。
     *
     * @param name 作为摘要前缀的上下文名称
     * @return 可直接写入日志的诊断文本
     */
    public String summary(String name) {
        return name
                + "{commandPool=0x" + Long.toHexString(commandPool)
                + ", submissions=" + syncSubmissions
                + ", syncSubmissions=" + syncSubmissions
                + ", asyncSubmissions=" + asyncSubmissions
                + ", asyncCompletions=" + asyncCompletions
                + ", asyncPollsNotReady=" + asyncPollsNotReady
                + ", commandBufferPoolSize=" + commandBuffers.size()
                + ", freeCommandBuffers=" + freeCommandBuffers.size()
                + ", commandBufferAllocations=" + commandBufferAllocations
                + ", commandBufferReuses=" + commandBufferReuses
                + ", commandBufferReturns=" + commandBufferReturns
                + ", commandBufferResets=" + commandBufferResets
                + ", fencePoolSize=" + fences.size()
                + ", freeFences=" + freeFences.size()
                + ", fenceAllocations=" + fenceAllocations
                + ", fenceReuses=" + fenceReuses
                + ", fenceReturns=" + fenceReturns
                + ", fenceResets=" + fenceResets
                + ", lastSubmissionMillis=" + nanosToMillis(lastSyncSubmissionNanos)
                + ", maxSubmissionMillis=" + nanosToMillis(maxSyncSubmissionNanos)
                + ", totalSubmissionMillis=" + nanosToMillis(totalSyncSubmissionNanos)
                + ", lastSyncSubmissionMillis=" + nanosToMillis(lastSyncSubmissionNanos)
                + ", maxSyncSubmissionMillis=" + nanosToMillis(maxSyncSubmissionNanos)
                + ", totalSyncSubmissionMillis=" + nanosToMillis(totalSyncSubmissionNanos)
                + ", lastAsyncSubmitMillis=" + nanosToMillis(lastAsyncSubmitNanos)
                + ", maxAsyncSubmitMillis=" + nanosToMillis(maxAsyncSubmitNanos)
                + ", totalAsyncSubmitMillis=" + nanosToMillis(totalAsyncSubmitNanos)
                + ", lastAsyncLatencyMillis=" + nanosToMillis(lastAsyncLatencyNanos)
                + ", maxAsyncLatencyMillis=" + nanosToMillis(maxAsyncLatencyNanos)
                + ", totalAsyncLatencyMillis=" + nanosToMillis(totalAsyncLatencyNanos)
                + ", lastCommandPoolWaitMillis=" + nanosToMillis(lastCommandPoolWaitNanos)
                + ", maxCommandPoolWaitMillis=" + nanosToMillis(maxCommandPoolWaitNanos)
                + ", totalCommandPoolWaitMillis=" + nanosToMillis(totalCommandPoolWaitNanos)
                + ", lastCommandRecordMillis=" + nanosToMillis(lastCommandRecordNanos)
                + ", maxCommandRecordMillis=" + nanosToMillis(maxCommandRecordNanos)
                + ", totalCommandRecordMillis=" + nanosToMillis(totalCommandRecordNanos)
                + ", lastQueueLockWaitMillis=" + nanosToMillis(lastQueueLockWaitNanos)
                + ", maxQueueLockWaitMillis=" + nanosToMillis(maxQueueLockWaitNanos)
                + ", totalQueueLockWaitMillis=" + nanosToMillis(totalQueueLockWaitNanos)
                + ", lastVkQueueSubmitMillis=" + nanosToMillis(lastVkQueueSubmitNanos)
                + ", maxVkQueueSubmitMillis=" + nanosToMillis(maxVkQueueSubmitNanos)
                + ", totalVkQueueSubmitMillis=" + nanosToMillis(totalVkQueueSubmitNanos)
                + ", " + (gpuTimestamps == null ? "gpuTimestamps{enabled=false}" : gpuTimestamps.summary())
                + "}";
    }

    /**
     * 停止接收新工作，等待正在录制和已提交的队列工作结束，然后销毁池化资源。
     */
    @Override
    public synchronized void close() {
        if (closed) return;
        if (closing) throw new IllegalStateException("RT command context close is already in progress");
        closing = true;
        try {
            long timeoutNanos = closeTimeoutNanos();
            long deadline = saturatingAdd(System.nanoTime(), timeoutNanos);
            while (activeAsyncRecordings > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    throw new IllegalStateException(
                            "timed out waiting for " + activeAsyncRecordings
                                    + " active Vulkan command recordings to finish"
                    );
                }
                try {
                    long waitMillis = Math.max(1L, Math.min(remaining / 1_000_000L, Integer.MAX_VALUE));
                    wait(waitMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while closing RT command context", interrupted);
                }
            }
            waitQueueIdle("vkQueueWaitIdle.commandContextClose");
            closed = true;
            for (PooledFence fence : fences) fence.destroy(device);
            freeFences.clear();
            fences.clear();
            freeCommandBuffers.clear();
            commandBuffers.clear();
            synchronized (commandPoolLock) {
                VK10.vkDestroyCommandPool(device, commandPool, null);
            }
            if (gpuTimestamps != null) gpuTimestamps.close();
        } finally {
            if (!closed) closing = false;
        }
    }

    private void requireAcceptingWork() {
        if (closed || closing) {
            throw new IllegalStateException(
                    closed ? "RT command context is already closed" : "RT command context is closing"
            );
        }
    }

    private void waitQueueIdleUnchecked() {
        synchronized (queueSubmitLock.monitor()) {
            VK10.vkQueueWaitIdle(queue);
        }
    }

    private PooledCommandBuffer acquireCommandBuffer(MemoryStack stack) {
        PooledCommandBuffer commandBuffer = freeCommandBuffers.pollFirst();
        if (commandBuffer != null) {
            commandBuffer.acquire();
            commandBufferReuses++;
            return commandBuffer;
        }

        VkCommandBufferAllocateInfo allocateInfo = VkCommandBufferAllocateInfo.calloc(stack)
                .sType$Default()
                .commandPool(commandPool)
                .level(VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(1);

        PointerBuffer commandBufferHandle = stack.mallocPointer(1);
        checkVk(VK10.vkAllocateCommandBuffers(device, allocateInfo, commandBufferHandle), "vkAllocateCommandBuffers");
        PooledCommandBuffer allocated = new PooledCommandBuffer(new VkCommandBuffer(commandBufferHandle.get(0), device));
        allocated.acquire();
        commandBuffers.add(allocated);
        commandBufferAllocations++;
        return allocated;
    }

    private PooledFence acquireFence(MemoryStack stack) {
        PooledFence fence = freeFences.pollFirst();
        if (fence != null) {
            fence.acquire();
            fenceReuses++;
            return fence;
        }

        VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack).sType$Default();
        LongBuffer fenceHandle = stack.longs(0L);
        checkVk(VK10.vkCreateFence(device, fenceInfo, null, fenceHandle), "vkCreateFence.async");
        PooledFence allocated = new PooledFence(fenceHandle.get(0));
        allocated.acquire();
        fences.add(allocated);
        fenceAllocations++;
        return allocated;
    }

    private void releaseCommandBuffer(PooledCommandBuffer commandBuffer) {
        Objects.requireNonNull(commandBuffer, "commandBuffer");
        if (closed || commandBuffer.released()) {
            return;
        }
        long waitStartNanos = System.nanoTime();
        long waitNanos;
        long resetStartNanos;
        synchronized (commandPoolLock) {
            waitNanos = System.nanoTime() - waitStartNanos;
            resetStartNanos = System.nanoTime();
            checkVk(VK10.vkResetCommandBuffer(commandBuffer.commandBuffer(), 0), "vkResetCommandBuffer.reuse");
        }
        long resetNanos = System.nanoTime() - resetStartNanos;
        recordCommandHostTiming(waitNanos, resetNanos, "releaseCommandBuffer");
        commandBuffer.release();
        freeCommandBuffers.addLast(commandBuffer);
        commandBufferReturns++;
        commandBufferResets++;
    }

    private void releaseFence(PooledFence fence) {
        Objects.requireNonNull(fence, "fence");
        if (closed || fence.released()) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            checkVk(VK10.vkResetFences(device, stack.longs(fence.fence())), "vkResetFences.reuse");
        }
        fence.release();
        freeFences.addLast(fence);
        fenceReturns++;
        fenceResets++;
    }

    private void recordSyncSubmission(long elapsedNanos) {
        syncSubmissions++;
        lastSyncSubmissionNanos = elapsedNanos;
        totalSyncSubmissionNanos += elapsedNanos;
        maxSyncSubmissionNanos = Math.max(maxSyncSubmissionNanos, elapsedNanos);
    }

    private void recordAsyncSubmit(long elapsedNanos, long queueLockWaitNanos, long vkQueueSubmitNanos) {
        asyncSubmissions++;
        lastAsyncSubmitNanos = elapsedNanos;
        totalAsyncSubmitNanos += elapsedNanos;
        maxAsyncSubmitNanos = Math.max(maxAsyncSubmitNanos, elapsedNanos);
        lastQueueLockWaitNanos = queueLockWaitNanos;
        totalQueueLockWaitNanos += queueLockWaitNanos;
        maxQueueLockWaitNanos = Math.max(maxQueueLockWaitNanos, queueLockWaitNanos);
        lastVkQueueSubmitNanos = vkQueueSubmitNanos;
        totalVkQueueSubmitNanos += vkQueueSubmitNanos;
        maxVkQueueSubmitNanos = Math.max(maxVkQueueSubmitNanos, vkQueueSubmitNanos);
    }

    private void recordCommandHostTiming(long commandPoolWaitNanos, long commandRecordNanos, String stage) {
        synchronized (this) {
            lastCommandPoolWaitNanos = commandPoolWaitNanos;
            totalCommandPoolWaitNanos += commandPoolWaitNanos;
            maxCommandPoolWaitNanos = Math.max(maxCommandPoolWaitNanos, commandPoolWaitNanos);
            lastCommandRecordNanos = commandRecordNanos;
            totalCommandRecordNanos += commandRecordNanos;
            maxCommandRecordNanos = Math.max(maxCommandRecordNanos, commandRecordNanos);
        }
        logSlowHostStage(stage, commandPoolWaitNanos, commandRecordNanos);
    }

    private void logSlowHostStage(String stage, long waitNanos, long workNanos) {
        if (waitNanos < SLOW_HOST_STAGE_NANOS && workNanos < SLOW_HOST_STAGE_NANOS) {
            return;
        }
        stallTelemetry.commandHostStall(
                Thread.currentThread().getName(),
                commandPool,
                stage,
                waitNanos,
                workNanos
        );
    }

    private void recordAsyncCompletionLatency(long elapsedNanos) {
        lastAsyncLatencyNanos = elapsedNanos;
        totalAsyncLatencyNanos += elapsedNanos;
        maxAsyncLatencyNanos = Math.max(maxAsyncLatencyNanos, elapsedNanos);
    }

    private void releaseCompletedAsyncSubmission(
            PooledCommandBuffer commandBuffer,
            PooledFence fence,
            long completionNanos,
            long submitStartNanos
    ) {
        if (closed) {
            return;
        }
        recordAsyncCompletionLatency(completionNanos - submitStartNanos);
        asyncCompletions++;
        releaseCommandBuffer(commandBuffer);
        releaseFence(fence);
    }

    enum TimelineRole {NONE, PRODUCER, CONSUMER}

    /**
     * 将调用方命令写入一次性 Vulkan 命令缓冲区的回调。
     * 实现不得保留传入的栈或从该栈分配的对象，因为它们仅在回调期间有效。
     */
    @FunctionalInterface
    public interface CommandRecorder {
        /**
         * 向当前正在录制的命令缓冲区写入命令。
         *
         * @param commandBuffer 处于录制状态的一次性 Vulkan 命令缓冲区
         * @param stack         仅在本次调用期间有效的原生临时内存栈
         */
        void record(VkCommandBuffer commandBuffer, MemoryStack stack);
    }

    static final class QueueSubmitLock {
        private final long queueAddress;
        private final Object monitor;

        private QueueSubmitLock(long queueAddress) {
            if (queueAddress == 0L) {
                throw new IllegalArgumentException("queueAddress must not be null");
            }
            this.queueAddress = queueAddress;
            // This token never owns a private lock. Every token for the same
            // native queue resolves the process-wide host/RT monitor.
            this.monitor = VulkanQueueHostSync.monitor(queueAddress);
        }

        private Object monitor() {
            return monitor;
        }

        private void requireQueue(VkQueue queue) {
            if (Objects.requireNonNull(queue, "queue").address() != queueAddress) {
                throw new IllegalArgumentException("queueSubmitLock belongs to a different VkQueue");
            }
        }
    }

    private static final class PooledCommandBuffer {
        private final VkCommandBuffer commandBuffer;
        private boolean released = true;

        private PooledCommandBuffer(VkCommandBuffer commandBuffer) {
            this.commandBuffer = Objects.requireNonNull(commandBuffer, "commandBuffer");
        }

        private VkCommandBuffer commandBuffer() {
            return commandBuffer;
        }

        private void acquire() {
            if (!released) {
                throw new IllegalStateException("RT command buffer is already leased");
            }
            released = false;
        }

        private void release() {
            if (released) {
                throw new IllegalStateException("RT command buffer was already returned");
            }
            released = true;
        }

        private boolean released() {
            return released;
        }
    }

    private static final class PooledFence {
        private long fence;
        private boolean released = true;

        private PooledFence(long fence) {
            if (fence == 0L) {
                throw new IllegalArgumentException("fence must not be null");
            }
            this.fence = fence;
        }

        private long fence() {
            return fence;
        }

        private void acquire() {
            if (!released) {
                throw new IllegalStateException("RT fence is already leased");
            }
            released = false;
        }

        private void release() {
            if (released) {
                throw new IllegalStateException("RT fence was already returned");
            }
            released = true;
        }

        private boolean released() {
            return released;
        }

        private void destroy(VkDevice device) {
            if (fence == 0L) {
                return;
            }
            VK10.vkDestroyFence(device, fence, null);
            fence = 0L;
            released = true;
        }
    }

    private record TakenRecording(
            PooledCommandBuffer commandBuffer,
            RtGpuTimestampPool.Capture gpuTimestamps
    ) {
        private TakenRecording {
            Objects.requireNonNull(commandBuffer, "commandBuffer");
        }
    }

    /**
     * 描述一次异步提交在宿主队列与 GPU 上的时间证据。
     *
     * @param queueLockWaitNanos             等待获得队列宿主同步锁的纳秒数
     * @param vkQueueSubmitNanos             原生队列提交调用消耗的纳秒数
     * @param fenceResidencyUpperBoundNanos  从提交调用结束到观察时刻的 fence 驻留时间上界
     * @param lastNotReadyToObservationNanos 从最后一次未完成轮询到观察时刻的纳秒数；没有此类轮询时为零
     * @param notReadyPolls                  返回未完成状态的 fence 轮询次数
     * @param completionObserved             是否已经观察到 fence 完成
     * @param gpuWorkNanos                   已解析的 GPU 命令执行纳秒数；没有可用证据时为 {@code -1}
     */
    public record Timing(
            long queueLockWaitNanos,
            long vkQueueSubmitNanos,
            long fenceResidencyUpperBoundNanos,
            long lastNotReadyToObservationNanos,
            long notReadyPolls,
            boolean completionObserved,
            long gpuWorkNanos
    ) {
        /**
         * 创建并校验异步提交时间快照。
         *
         * @param queueLockWaitNanos             等待获得队列宿主同步锁的纳秒数
         * @param vkQueueSubmitNanos             原生队列提交调用消耗的纳秒数
         * @param fenceResidencyUpperBoundNanos  fence 驻留时间上界，单位为纳秒
         * @param lastNotReadyToObservationNanos 最后一次未完成轮询至观察时刻的纳秒数
         * @param notReadyPolls                  返回未完成状态的轮询次数
         * @param completionObserved             是否已经观察到完成
         * @param gpuWorkNanos                   GPU 命令执行纳秒数，或表示未知的 {@code -1}
         */
        public Timing {
            if (gpuWorkNanos < -1L) {
                throw new IllegalArgumentException("gpuWorkNanos must be -1 or non-negative");
            }
        }
    }

    /**
     * 尚未提交的一次性 Vulkan 命令缓冲区所有权句柄。
     * 句柄只能被一次提交消费；不再提交时必须关闭，以便命令缓冲区返回池中。
     */
    public final class RecordedCommandBuffer implements AutoCloseable {
        private final long acquireWaitNanos;
        private final long recordNanos;
        private PooledCommandBuffer commandBuffer;
        private RtGpuTimestampPool.Capture gpuTimestamps;

        private RecordedCommandBuffer(
                PooledCommandBuffer commandBuffer,
                long acquireWaitNanos,
                long recordNanos,
                RtGpuTimestampPool.Capture gpuTimestamps
        ) {
            this.commandBuffer = Objects.requireNonNull(commandBuffer, "commandBuffer");
            if (acquireWaitNanos < 0L || recordNanos < 0L) {
                throw new IllegalArgumentException("recorded command timings must not be negative");
            }
            this.acquireWaitNanos = acquireWaitNanos;
            this.recordNanos = recordNanos;
            this.gpuTimestamps = gpuTimestamps;
        }

        private long acquireWaitNanos() {
            return acquireWaitNanos;
        }

        private long recordNanos() {
            return recordNanos;
        }

        private synchronized TakenRecording takeForSubmission() {
            if (commandBuffer == null) {
                throw new IllegalStateException("recorded command buffer has already been submitted or released");
            }
            TakenRecording result = new TakenRecording(commandBuffer, gpuTimestamps);
            commandBuffer = null;
            gpuTimestamps = null;
            return result;
        }

        /**
         * 释放尚未提交的命令缓冲区及其可选时间戳捕获。
         * 已被提交消费或已关闭的句柄可重复关闭。
         */
        @Override
        public synchronized void close() {
            if (commandBuffer == null) {
                return;
            }
            PooledCommandBuffer result = commandBuffer;
            RtGpuTimestampPool.Capture timestamps = gpuTimestamps;
            commandBuffer = null;
            gpuTimestamps = null;
            synchronized (RtCommandContext.this) {
                releaseCommandBuffer(result);
            }
            if (timestamps != null) {
                closeGpuTimestampCapture(timestamps);
            }
        }
    }

    /**
     * 持有异步 Vulkan 提交完成状态及其池化资源的句柄。
     * 调用方应轮询完成状态或关闭句柄，确保 fence、命令缓冲区和时间戳捕获被回收。
     */
    public final class AsyncSubmission implements AutoCloseable {
        private final List<PooledCommandBuffer> commandBuffers;
        private final PooledFence fence;
        private final long commandPoolAcquireWaitNanos;
        private final long commandRecordNanos;
        private final List<RtGpuTimestampPool.Capture> gpuTimestampCaptures;
        private long submitStartNanos;
        private long queueLockAcquiredNanos;
        private long submitCompleteNanos;
        private long lastNotReadyPollNanos;
        private long completionObservedNanos;
        /* Separates GPU command execution from queue residency before the fence signalled. */
        private long resolvedGpuWorkNanos = -1L;
        private long timelineSignalValue;
        private long notReadyPolls;
        private boolean released;

        private AsyncSubmission(
                List<PooledCommandBuffer> commandBuffers,
                PooledFence fence,
                long commandPoolAcquireWaitNanos,
                long commandRecordNanos,
                List<RtGpuTimestampPool.Capture> gpuTimestampCaptures
        ) {
            this.commandBuffers = Objects.requireNonNull(commandBuffers, "commandBuffers");
            this.fence = Objects.requireNonNull(fence, "fence");
            if (commandPoolAcquireWaitNanos < 0L || commandRecordNanos < 0L) {
                throw new IllegalArgumentException("async submission record timings must not be negative");
            }
            this.commandPoolAcquireWaitNanos = commandPoolAcquireWaitNanos;
            this.commandRecordNanos = commandRecordNanos;
            this.gpuTimestampCaptures = Objects.requireNonNull(gpuTimestampCaptures, "gpuTimestampCaptures");
        }

        private void markSubmitted(
                long submitStartNanos,
                long queueLockAcquiredNanos,
                long submitCompleteNanos,
                long timelineSignalValue
        ) {
            this.submitStartNanos = submitStartNanos;
            this.queueLockAcquiredNanos = queueLockAcquiredNanos;
            this.submitCompleteNanos = submitCompleteNanos;
            this.timelineSignalValue = timelineSignalValue;
        }

        /**
         * 返回本次提交包含的所有命令缓冲区等待命令池锁的累计时间。
         *
         * @return 等待时间，单位为纳秒
         */
        public long commandPoolAcquireWaitNanos() {
            return commandPoolAcquireWaitNanos;
        }

        /**
         * 返回本次提交包含的所有命令缓冲区的累计录制时间。
         *
         * @return 录制时间，单位为纳秒
         */
        public long commandRecordNanos() {
            return commandRecordNanos;
        }

        /**
         * 返回从开始提交到获得队列宿主同步锁的时间。
         *
         * @return 队列锁等待时间，单位为纳秒
         */
        public long queueLockWaitNanos() {
            return queueLockAcquiredNanos - submitStartNanos;
        }

        /**
         * 返回在持有队列锁期间执行原生队列提交调用的时间。
         *
         * @return 原生提交调用时间，单位为纳秒
         */
        public long vkQueueSubmitNanos() {
            return submitCompleteNanos - queueLockAcquiredNanos;
        }

        /**
         * 非阻塞检查 fence，并在首次观察到完成时回收本次提交的资源。
         *
         * @return 已完成或已因所属上下文关闭而释放时为 {@code true}；仍在执行时为 {@code false}
         */
        public synchronized boolean pollComplete() {
            if (released) {
                return true;
            }
            if (closed) {
                released = true;
                abandonGpuTimestamps();
                return true;
            }
            int status = VK10.vkGetFenceStatus(device, fence.fence());
            if (status == VK10.VK_NOT_READY) {
                lastNotReadyPollNanos = System.nanoTime();
                notReadyPolls++;
                synchronized (RtCommandContext.this) {
                    asyncPollsNotReady++;
                }
                return false;
            }
            synchronized (RtCommandContext.this) {
                if (closed) {
                    released = true;
                    abandonGpuTimestamps();
                    return true;
                }
                checkVk(status, "vkGetFenceStatus.async");
                releaseCompleted(System.nanoTime());
                return true;
            }
        }

        /**
         * 等待异步提交完成并回收其资源。
         * 若句柄已经完成、释放或所属上下文已经关闭，则该操作不产生额外效果。
         */
        @Override
        public synchronized void close() {
            if (released) {
                return;
            }
            if (closed) {
                released = true;
                abandonGpuTimestamps();
                return;
            }
            int waitResult = VK10.vkWaitForFences(device, fence.fence(), true, closeTimeoutNanos());
            if (waitResult == VK10.VK_TIMEOUT) {
                throw new IllegalStateException("timed out waiting for asynchronous Vulkan submission completion");
            }
            checkVk(waitResult, "vkWaitForFences.asyncClose");
            synchronized (RtCommandContext.this) {
                if (closed) {
                    released = true;
                    abandonGpuTimestamps();
                    return;
                }
                releaseCompleted(System.nanoTime());
            }
        }

        private void releaseCompleted(long completionNanos) {
            if (released) {
                return;
            }
            released = true;
            completionObservedNanos = completionNanos;
            recordAsyncCompletionLatency(completionNanos - submitStartNanos);
            asyncCompletions++;
            markTimelineCompleted(timelineSignalValue);
            try {
                resolveGpuTimestamps();
            } finally {
                for (PooledCommandBuffer commandBuffer : commandBuffers) {
                    releaseCommandBuffer(commandBuffer);
                }
                releaseFence(fence);
            }
        }

        private void resolveGpuTimestamps() {
            long resolvedNanos = 0L;
            boolean resolvedAny = false;
            for (RtGpuTimestampPool.Capture capture : gpuTimestampCaptures) {
                try {
                    if (capture.resolve()) {
                        resolvedNanos = saturatingAdd(resolvedNanos, capture.totalNanos());
                        resolvedAny = true;
                    }
                } catch (RuntimeException | LinkageError ex) {
                    top.ceroxe.rt.renderer.RendererLog.warn(
                            "Unable to resolve optional Vulkan GPU timestamp evidence",
                            ex
                    );
                } finally {
                    closeGpuTimestampCapture(capture);
                }
            }
            if (resolvedAny) {
                resolvedGpuWorkNanos = resolvedNanos;
            }
        }

        private void abandonGpuTimestamps() {
            for (RtGpuTimestampPool.Capture capture : gpuTimestampCaptures) {
                closeGpuTimestampCapture(capture);
            }
        }

        /**
         * 返回当前可观测到的宿主提交与 GPU 驻留时间证据。
         * 在完成前，fence 驻留上界使用当前时刻计算，因此后续查询可能得到更大的值。
         *
         * @return 本次异步提交的不可变时间快照
         */
        public synchronized Timing timing() {
            long observedNanos = completionObservedNanos == 0L ? System.nanoTime() : completionObservedNanos;
            return new Timing(
                    queueLockAcquiredNanos - submitStartNanos,
                    submitCompleteNanos - queueLockAcquiredNanos,
                    Math.max(0L, observedNanos - submitCompleteNanos),
                    lastNotReadyPollNanos == 0L ? 0L : Math.max(0L, observedNanos - lastNotReadyPollNanos),
                    notReadyPolls,
                    completionObservedNanos != 0L,
                    resolvedGpuWorkNanos
            );
        }
    }
}
