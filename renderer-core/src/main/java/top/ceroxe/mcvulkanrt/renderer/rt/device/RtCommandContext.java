package top.ceroxe.mcvulkanrt.renderer.rt.device;

import top.ceroxe.mcvulkanrt.renderer.rt.device.interop.VulkanWin32ExternalSemaphoreProbe;
import top.ceroxe.mcvulkanrt.renderer.RtStallTelemetrySink;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSubmitInfo;

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
 * UE5 VulkanRHI 的核心纪律不是每次提交都分配/销毁 command buffer 和 fence，
 * 而是让已完成的 GPU work 回到可 reset 的池里复用。这里把这条纪律收敛到一个边界：
 * 调用方只表达“录制一次提交”，资源生命周期由本类统一管理。</p>
 */
public final class RtCommandContext implements AutoCloseable {
    private static final String[] GPU_WORK_CHECKPOINTS = {"start", "end"};
    private static final long SLOW_HOST_STAGE_NANOS = 2_000_000L;
    private final VkDevice device;
    private final VkQueue queue;
    private final QueueSubmitLock queueSubmitLock;
    private final Object commandPoolLock = new Object();
    private final long commandPool;
    private final RtGpuTimestampPool gpuTimestamps;
    private final RtStallTelemetrySink stallTelemetry;
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
    private volatile boolean closed;

    public int orderedQueueCount() {
        return 1;
    }

    public RtStallTelemetrySink stallTelemetry() {
        return stallTelemetry;
    }

    private RtCommandContext(
            VkDevice device,
            VkQueue queue,
            QueueSubmitLock queueSubmitLock,
            long commandPool,
            RtGpuTimestampPool gpuTimestamps,
            RtStallTelemetrySink stallTelemetry
    ) {
        this.device = Objects.requireNonNull(device, "device");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.queueSubmitLock = Objects.requireNonNull(queueSubmitLock, "queueSubmitLock");
        queueSubmitLock.requireQueue(queue);
        this.commandPool = commandPool;
        this.gpuTimestamps = gpuTimestamps;
        this.stallTelemetry = Objects.requireNonNull(stallTelemetry, "stallTelemetry");
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
                    top.ceroxe.mcvulkanrt.renderer.RendererLog.warn(
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
                        stallTelemetry
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

    public synchronized void submitOneTime(CommandRecorder recorder) {
        Objects.requireNonNull(recorder, "recorder");
        if (closed) {
            throw new IllegalStateException("RT command context is already closed");
        }

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
                synchronized (queueSubmitLock.monitor()) {
                    checkVk(VK10.vkQueueSubmit(queue, submitInfo, VK10.VK_NULL_HANDLE), "vkQueueSubmit");
                    submitted = true;
                    checkVk(VK10.vkQueueWaitIdle(queue), "vkQueueWaitIdle");
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

    public AsyncSubmission submitOneTimeAsync(CommandRecorder recorder) {
        return submitOneTimeAsync(recorder, null);
    }

    public AsyncSubmission submitOneTimeAsync(
            CommandRecorder recorder,
            VulkanWin32ExternalSemaphoreProbe.ExportedSemaphore signalSemaphore
    ) {
        return submitOneTimeAsync(recorder, signalSemaphore, null);
    }

    /**
     * Submits one labelled GPU workload whose timestamp lifetime follows the submission fence.
     * Query exhaustion degrades to an ordinary async submission and never delays GPU work.
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
            AsyncSubmission submission = submitOneTimeAsync(timedRecorder, null, capture);
            transferred = true;
            return submission;
        } finally {
            if (!transferred && capture != null) {
                capture.close();
            }
        }
    }

    private AsyncSubmission submitOneTimeAsync(
            CommandRecorder recorder,
            VulkanWin32ExternalSemaphoreProbe.ExportedSemaphore signalSemaphore,
            RtGpuTimestampPool.Capture gpuTimestamps
    ) {
        Objects.requireNonNull(recorder, "recorder");
        RecordedCommandBuffer recording = recordOneTime(recorder);
        try {
            return submitRecordedAsync(List.of(recording), signalSemaphore, gpuTimestamps);
        } catch (RuntimeException | Error ex) {
            recording.close();
            throw ex;
        }
    }

    public RecordedCommandBuffer recordOneTime(CommandRecorder recorder) {
        return recordOneTime(recorder, null);
    }

    /** Records a labelled GPU workload whose capture remains owned by the recorded buffer. */
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
                if (closed) {
                    throw new IllegalStateException("RT command context is already closed");
                }
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

    public AsyncSubmission submitRecordedAsync(List<RecordedCommandBuffer> recordings) {
        return submitRecordedAsync(recordings, null);
    }

    public AsyncSubmission submitRecordedAsync(
            List<RecordedCommandBuffer> recordings,
            VulkanWin32ExternalSemaphoreProbe.ExportedSemaphore signalSemaphore
    ) {
        return submitRecordedAsync(recordings, signalSemaphore, null);
    }

    private AsyncSubmission submitRecordedAsync(
            List<RecordedCommandBuffer> recordings,
            VulkanWin32ExternalSemaphoreProbe.ExportedSemaphore signalSemaphore,
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
                    if (closed) {
                        throw new IllegalStateException("RT command context is already closed");
                    }
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
                if (signalSemaphore != null) {
                    submitInfo.pSignalSemaphores(stack.longs(signalSemaphore.semaphore()));
                }
                long submitStart = System.nanoTime();
                long queueLockAcquiredNanos;
                long submitCompleteNanos;
                synchronized (queueSubmitLock.monitor()) {
                    queueLockAcquiredNanos = System.nanoTime();
                    checkVk(VK10.vkQueueSubmit(queue, submitInfo, pooledFence.fence()), "vkQueueSubmit.recordedAsync");
                    submitCompleteNanos = System.nanoTime();
                }
                submitted = true;
                submission.markSubmitted(submitStart, queueLockAcquiredNanos, submitCompleteNanos);
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
        }
    }

    /** Acquires diagnostic timestamp storage without waiting for an in-flight slot. */
    public RtGpuTimestampPool.Capture acquireGpuTimestampCapture(String label, String[] checkpoints) {
        RtGpuTimestampPool timestamps = gpuTimestamps;
        return timestamps == null ? null : timestamps.acquire(label, checkpoints);
    }

    public RtGpuTimestampPool.Snapshot gpuTimestampSnapshot() {
        RtGpuTimestampPool timestamps = gpuTimestamps;
        return timestamps == null ? RtGpuTimestampPool.Snapshot.disabled() : timestamps.snapshot();
    }

    public RtGpuTimestampPool.StageSnapshot gpuStageTimestampSnapshot(String label) {
        RtGpuTimestampPool timestamps = gpuTimestamps;
        return timestamps == null
                ? RtGpuTimestampPool.StageSnapshot.disabled(label)
                : timestamps.stageSnapshot(label);
    }

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

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        boolean interrupted = false;
        while (activeAsyncRecordings > 0) {
            try {
                wait();
            } catch (InterruptedException ex) {
                interrupted = true;
            }
        }
        waitQueueIdle("vkQueueWaitIdle.commandContextClose");
        for (PooledFence fence : fences) {
            fence.destroy(device);
        }
        freeFences.clear();
        fences.clear();
        freeCommandBuffers.clear();
        commandBuffers.clear();
        synchronized (commandPoolLock) {
            VK10.vkDestroyCommandPool(device, commandPool, null);
        }
        if (gpuTimestamps != null) {
            gpuTimestamps.close();
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
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

    private static long nanosToMillis(long nanos) {
        return nanos / 1_000_000L;
    }

    private static long saturatingAdd(long left, long right) {
        return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static void checkVk(int result, String stage) {
        if (result != VK10.VK_SUCCESS) {
            throw new IllegalStateException(stage + " failed: " + vkResultName(result));
        }
    }

    private static void closeGpuTimestampCapture(RtGpuTimestampPool.Capture capture) {
        try {
            capture.close();
        } catch (RuntimeException ex) {
            top.ceroxe.mcvulkanrt.renderer.RendererLog.warn(
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

    @FunctionalInterface
    public interface CommandRecorder {
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

    public final class RecordedCommandBuffer implements AutoCloseable {
        private PooledCommandBuffer commandBuffer;
        private RtGpuTimestampPool.Capture gpuTimestamps;
        private final long acquireWaitNanos;
        private final long recordNanos;

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

    private record TakenRecording(
            PooledCommandBuffer commandBuffer,
            RtGpuTimestampPool.Capture gpuTimestamps
    ) {
        private TakenRecording {
            Objects.requireNonNull(commandBuffer, "commandBuffer");
        }
    }

    public final class AsyncSubmission implements AutoCloseable {
        private final List<PooledCommandBuffer> commandBuffers;
        private final PooledFence fence;
        private long submitStartNanos;
        private long queueLockAcquiredNanos;
        private long submitCompleteNanos;
        private final long commandPoolAcquireWaitNanos;
        private final long commandRecordNanos;
        private final List<RtGpuTimestampPool.Capture> gpuTimestampCaptures;
        private long lastNotReadyPollNanos;
        private long completionObservedNanos;
        /* Separates GPU command execution from queue residency before the fence signalled. */
        private long resolvedGpuWorkNanos = -1L;
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
            if (this.commandBuffers.isEmpty()) {
                throw new IllegalArgumentException("async submission must own command buffers");
            }
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
                long submitCompleteNanos
        ) {
            this.submitStartNanos = submitStartNanos;
            this.queueLockAcquiredNanos = queueLockAcquiredNanos;
            this.submitCompleteNanos = submitCompleteNanos;
        }

        public long commandPoolAcquireWaitNanos() {
            return commandPoolAcquireWaitNanos;
        }

        public long commandRecordNanos() {
            return commandRecordNanos;
        }

        public long queueLockWaitNanos() {
            return queueLockAcquiredNanos - submitStartNanos;
        }

        public long vkQueueSubmitNanos() {
            return submitCompleteNanos - queueLockAcquiredNanos;
        }

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
            checkVk(VK10.vkWaitForFences(device, fence.fence(), true, -1L), "vkWaitForFences.asyncClose");
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
                    top.ceroxe.mcvulkanrt.renderer.RendererLog.warn(
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

    public record Timing(
            long queueLockWaitNanos,
            long vkQueueSubmitNanos,
            long fenceResidencyUpperBoundNanos,
            long lastNotReadyToObservationNanos,
            long notReadyPolls,
            boolean completionObserved,
            long gpuWorkNanos
        ) {
        public Timing {
            if (gpuWorkNanos < -1L) {
                throw new IllegalArgumentException("gpuWorkNanos must be -1 or non-negative");
            }
        }
    }
}
