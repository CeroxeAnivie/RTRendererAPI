package top.ceroxe.rt.renderer.rt.device;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.*;

/**
 * Fixed-capacity, fence-retired Vulkan timestamp storage for one command context.
 *
 * <p>A capture owns one preallocated query range from command recording until the
 * submission fence is observed. Acquisition never waits: an exhausted pool drops
 * diagnostic evidence rather than stalling renderer work. Captures and their
 * primitive result arrays are pooled as well, so enabling GPU timing does not add
 * a per-frame Java allocation stream.</p>
 */
public final class RtGpuTimestampPool implements AutoCloseable {
    private static final int QUERIES_PER_CAPTURE = 8;
    private static final int DEFAULT_CAPTURE_CAPACITY = 64;
    private static final String CAPACITY_PROPERTY = "top.ceroxe.rt.rt.gpuTimestamps.captureCapacity";

    private final VkDevice device;
    private final long queryPool;
    private final float timestampPeriodNanos;
    private final int timestampValidBits;
    private final Deque<Capture> freeCaptures = new ArrayDeque<>();
    private final List<Capture> captures = new ArrayList<>();
    private final Map<String, StageStatistics> stageStatistics = new LinkedHashMap<>();
    private long acquiredCaptures;
    private long completedCaptures;
    private long droppedCaptures;
    private long failedCaptures;
    private long lastTotalNanos;
    private long maxTotalNanos;
    private long totalNanos;
    private long lastFirstSegmentNanos;
    private long lastSecondSegmentNanos;
    private long totalFirstSegmentNanos;
    private long totalSecondSegmentNanos;
    private String lastLabel = "none";
    private volatile boolean closed;

    private RtGpuTimestampPool(
            VkDevice device,
            long queryPool,
            float timestampPeriodNanos,
            int timestampValidBits,
            int captureCapacity
    ) {
        this.device = Objects.requireNonNull(device, "device");
        if (queryPool == 0L || !Float.isFinite(timestampPeriodNanos) || timestampPeriodNanos <= 0.0F
                || timestampValidBits <= 0 || timestampValidBits > Long.SIZE || captureCapacity <= 0) {
            throw new IllegalArgumentException("invalid GPU timestamp pool configuration");
        }
        this.queryPool = queryPool;
        this.timestampPeriodNanos = timestampPeriodNanos;
        this.timestampValidBits = timestampValidBits;
        for (int slot = 0; slot < captureCapacity; slot++) {
            Capture capture = new Capture(this, slot * QUERIES_PER_CAPTURE);
            captures.add(capture);
            freeCaptures.addLast(capture);
        }
    }

    static RtGpuTimestampPool create(
            MemoryStack stack,
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            int queueFamilyIndex
    ) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(physicalDevice, "physicalDevice");
        Objects.requireNonNull(device, "device");
        if (queueFamilyIndex < 0) {
            throw new IllegalArgumentException("queueFamilyIndex must not be negative");
        }

        VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
        VK10.vkGetPhysicalDeviceProperties(physicalDevice, properties);
        float timestampPeriod = properties.limits().timestampPeriod();
        int timestampValidBits = timestampValidBits(stack, physicalDevice, queueFamilyIndex);
        if (!Float.isFinite(timestampPeriod) || timestampPeriod <= 0.0F || timestampValidBits <= 0) {
            return null;
        }

        int captureCapacity = captureCapacity();
        VkQueryPoolCreateInfo createInfo = VkQueryPoolCreateInfo.calloc(stack)
                .sType$Default()
                .queryType(VK10.VK_QUERY_TYPE_TIMESTAMP)
                .queryCount(Math.multiplyExact(captureCapacity, QUERIES_PER_CAPTURE));
        LongBuffer handle = stack.longs(0L);
        checkVk(VK10.vkCreateQueryPool(device, createInfo, null, handle), "vkCreateQueryPool.timestamp");
        long queryPool = handle.get(0);
        boolean transferred = false;
        try {
            RtGpuTimestampPool result = new RtGpuTimestampPool(
                    device,
                    queryPool,
                    timestampPeriod,
                    Math.min(timestampValidBits, Long.SIZE),
                    captureCapacity
            );
            transferred = true;
            return result;
        } finally {
            if (!transferred) {
                VK10.vkDestroyQueryPool(device, queryPool, null);
            }
        }
    }

    private static int timestampValidBits(
            MemoryStack stack,
            VkPhysicalDevice physicalDevice,
            int queueFamilyIndex
    ) {
        IntBuffer count = stack.ints(0);
        VK10.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, null);
        if (queueFamilyIndex >= count.get(0)) {
            throw new IllegalArgumentException("queue family index exceeds physical-device queue families");
        }
        VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.calloc(count.get(0), stack);
        VK10.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, families);
        return families.get(queueFamilyIndex).timestampValidBits();
    }

    private static int captureCapacity() {
        String configured = System.getProperty(CAPACITY_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return DEFAULT_CAPTURE_CAPACITY;
        }
        try {
            int parsed = Integer.parseInt(configured.trim());
            return parsed >= 8 && parsed <= 4_096 ? parsed : DEFAULT_CAPTURE_CAPACITY;
        } catch (NumberFormatException ignored) {
            return DEFAULT_CAPTURE_CAPACITY;
        }
    }

    private static String requireLabel(String label) {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("GPU timestamp label must not be blank");
        }
        return label;
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static void checkVk(int result, String operation) {
        VulkanFailures.check(result, operation);
    }

    /**
     * Leases a preallocated query range without waiting for an in-flight range.
     *
     * @param label       stable nonblank aggregation label
     * @param checkpoints ordered unique checkpoint names, with between two and the supported maximum
     * @return leased capture that the caller must close, or {@code null} under bounded-capacity pressure
     */
    public synchronized Capture acquire(String label, String[] checkpoints) {
        requireLabel(label);
        Objects.requireNonNull(checkpoints, "checkpoints");
        if (checkpoints.length < 2 || checkpoints.length > QUERIES_PER_CAPTURE) {
            throw new IllegalArgumentException("GPU timestamp capture requires 2.." + QUERIES_PER_CAPTURE + " checkpoints");
        }
        for (String checkpoint : checkpoints) {
            requireLabel(checkpoint);
        }
        if (closed) {
            return null;
        }
        StageStatistics statistics = stageStatistics.computeIfAbsent(label, StageStatistics::new);
        Capture capture = freeCaptures.pollFirst();
        if (capture == null) {
            droppedCaptures++;
            statistics.recordDropped();
            return null;
        }
        capture.acquire(label, checkpoints);
        acquiredCaptures++;
        statistics.recordAcquired();
        return capture;
    }

    /**
     * Builds a bounded aggregate timing and capacity summary.
     *
     * @return single-line lifetime and per-stage diagnostic summary
     */
    public synchronized String summary() {
        StringBuilder result = new StringBuilder(256)
                .append("gpuTimestamps{enabled=true")
                .append(", queryPool=0x").append(Long.toHexString(queryPool))
                .append(", timestampPeriodNanos=").append(timestampPeriodNanos)
                .append(", timestampValidBits=").append(timestampValidBits)
                .append(", capacity=").append(captures.size())
                .append(", free=").append(freeCaptures.size())
                .append(", acquired=").append(acquiredCaptures)
                .append(", completed=").append(completedCaptures)
                .append(", dropped=").append(droppedCaptures)
                .append(", failed=").append(failedCaptures)
                .append(", lastLabel=").append(lastLabel)
                .append(", lastTotalMicros=").append(lastTotalNanos / 1_000L)
                .append(", maxTotalMicros=").append(maxTotalNanos / 1_000L)
                .append(", avgTotalMicros=")
                .append(completedCaptures == 0L ? 0L : totalNanos / completedCaptures / 1_000L);
        result.append(", stages=[");
        boolean first = true;
        for (StageStatistics statistics : stageStatistics.values()) {
            if (!first) {
                result.append(',');
            }
            statistics.appendTo(result);
            first = false;
        }
        return result.append("]}").toString();
    }

    /**
     * Captures the pool's lifetime counters and durations.
     *
     * @return immutable timing snapshot
     */
    public synchronized Snapshot snapshot() {
        return new Snapshot(
                true,
                acquiredCaptures,
                completedCaptures,
                droppedCaptures,
                failedCaptures,
                lastFirstSegmentNanos,
                lastSecondSegmentNanos,
                lastTotalNanos,
                completedCaptures == 0L ? 0L : totalFirstSegmentNanos / completedCaptures,
                completedCaptures == 0L ? 0L : totalSecondSegmentNanos / completedCaptures,
                completedCaptures == 0L ? 0L : totalNanos / completedCaptures,
                maxTotalNanos
        );
    }

    /**
     * Captures counters and durations for one aggregation label.
     *
     * @param label nonblank aggregation label
     * @return immutable per-label timing snapshot, or an enabled empty snapshot if unseen
     */
    public synchronized StageSnapshot stageSnapshot(String label) {
        requireLabel(label);
        StageStatistics statistics = stageStatistics.get(label);
        return statistics == null ? StageSnapshot.empty(label) : statistics.snapshot();
    }

    private synchronized void recordResolved(Capture capture, long totalCaptureNanos) {
        if (capture.owner != this || totalCaptureNanos < 0L) {
            throw new IllegalArgumentException("resolved timestamp capture does not belong to this pool");
        }
        completedCaptures++;
        lastLabel = capture.label;
        lastTotalNanos = totalCaptureNanos;
        lastFirstSegmentNanos = capture.durationNanos[0];
        lastSecondSegmentNanos = capture.checkpointCount > 2 ? capture.durationNanos[1] : 0L;
        maxTotalNanos = Math.max(maxTotalNanos, totalCaptureNanos);
        totalNanos = saturatingAdd(totalNanos, totalCaptureNanos);
        totalFirstSegmentNanos = saturatingAdd(totalFirstSegmentNanos, lastFirstSegmentNanos);
        totalSecondSegmentNanos = saturatingAdd(totalSecondSegmentNanos, lastSecondSegmentNanos);
        stageStatistics.get(capture.label).recordCompleted(totalCaptureNanos);
    }

    private synchronized void recordFailure(Capture capture) {
        if (capture.owner != this) {
            throw new IllegalArgumentException("failed timestamp capture does not belong to this pool");
        }
        failedCaptures++;
        StageStatistics statistics = stageStatistics.get(capture.label);
        if (statistics != null) {
            statistics.recordFailed();
        }
    }

    private synchronized void release(Capture capture) {
        if (capture.owner != this || capture.available) {
            throw new IllegalStateException("GPU timestamp capture ownership is invalid");
        }
        capture.release();
        if (!closed) {
            freeCaptures.addLast(capture);
        }
    }

    /**
     * Destroys the owned Vulkan query pool.
     *
     * @throws IllegalStateException if any capture remains leased when the pool is destroyed
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        int leasedCaptures = captures.size() - freeCaptures.size();
        closed = true;
        freeCaptures.clear();
        VK10.vkDestroyQueryPool(device, queryPool, null);
        if (leasedCaptures != 0) {
            throw new IllegalStateException("destroyed GPU timestamp pool with captures still leased"
                    + ", capacity=" + captures.size() + ", leased=" + leasedCaptures);
        }
    }

    /**
     * Immutable lifetime GPU timestamp counters and durations.
     *
     * @param enabled                   whether GPU timestamp collection was enabled
     * @param acquiredCaptures          number of captures leased from the pool
     * @param completedCaptures         number of captures resolved and aggregated successfully
     * @param droppedCaptures           number of acquisition attempts dropped because capacity was exhausted
     * @param failedCaptures            number of captures that could not be resolved
     * @param lastFirstSegmentNanos     most recent first-segment duration in nanoseconds
     * @param lastSecondSegmentNanos    most recent second-segment duration in nanoseconds
     * @param lastTotalNanos            most recent total capture duration in nanoseconds
     * @param averageFirstSegmentNanos  average first-segment duration in nanoseconds
     * @param averageSecondSegmentNanos average second-segment duration in nanoseconds
     * @param averageTotalNanos         average total capture duration in nanoseconds
     * @param maxTotalNanos             maximum total capture duration in nanoseconds
     */
    public record Snapshot(
            boolean enabled,
            long acquiredCaptures,
            long completedCaptures,
            long droppedCaptures,
            long failedCaptures,
            long lastFirstSegmentNanos,
            long lastSecondSegmentNanos,
            long lastTotalNanos,
            long averageFirstSegmentNanos,
            long averageSecondSegmentNanos,
            long averageTotalNanos,
            long maxTotalNanos
    ) {
        private static final Snapshot DISABLED = new Snapshot(
                false, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L
        );

        /**
         * Validates all counters and durations as non-negative.
         */
        public Snapshot {
            if (acquiredCaptures < 0L || completedCaptures < 0L || droppedCaptures < 0L
                    || failedCaptures < 0L || lastFirstSegmentNanos < 0L || lastSecondSegmentNanos < 0L
                    || lastTotalNanos < 0L || averageFirstSegmentNanos < 0L
                    || averageSecondSegmentNanos < 0L || averageTotalNanos < 0L || maxTotalNanos < 0L) {
                throw new IllegalArgumentException("GPU timestamp snapshot values must not be negative");
            }
        }

        /**
         * Returns the canonical snapshot for a disabled timestamp pool.
         *
         * @return shared immutable disabled snapshot
         */
        public static Snapshot disabled() {
            return DISABLED;
        }
    }

    /**
     * Immutable counters and durations for one work label.
     *
     * @param label             nonblank aggregation label
     * @param enabled           whether GPU timestamp collection was enabled
     * @param acquiredCaptures  number of captures acquired for this label
     * @param completedCaptures number of captures resolved for this label
     * @param droppedCaptures   number of capacity-limited acquisitions for this label
     * @param failedCaptures    number of failed resolutions for this label
     * @param lastNanos         most recent total duration in nanoseconds
     * @param averageNanos      average total duration in nanoseconds
     * @param maxNanos          maximum total duration in nanoseconds
     */
    public record StageSnapshot(
            String label,
            boolean enabled,
            long acquiredCaptures,
            long completedCaptures,
            long droppedCaptures,
            long failedCaptures,
            long lastNanos,
            long averageNanos,
            long maxNanos
    ) {
        /**
         * Validates the label and all counters and durations.
         */
        public StageSnapshot {
            requireLabel(label);
            if (acquiredCaptures < 0L || completedCaptures < 0L || droppedCaptures < 0L
                    || failedCaptures < 0L || lastNanos < 0L || averageNanos < 0L || maxNanos < 0L) {
                throw new IllegalArgumentException("GPU timestamp stage values must not be negative");
            }
        }

        /**
         * Creates an empty snapshot for a label while timing is disabled.
         *
         * @param label nonblank work label
         * @return disabled empty snapshot
         */
        public static StageSnapshot disabled(String label) {
            return new StageSnapshot(label, false, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        }

        private static StageSnapshot empty(String label) {
            return new StageSnapshot(label, true, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        }
    }

    /**
     * Per-label aggregation is created once, then updated without allocating on completion.
     */
    private static final class StageStatistics {
        private final String label;
        private long completed;
        private long lastNanos;
        private long maxNanos;
        private long totalNanos;
        private long acquired;
        private long dropped;
        private long failed;

        private StageStatistics(String label) {
            this.label = label;
        }

        private void recordAcquired() {
            acquired++;
        }

        private void recordDropped() {
            dropped++;
        }

        private void recordFailed() {
            failed++;
        }

        private void recordCompleted(long captureNanos) {
            completed++;
            lastNanos = captureNanos;
            maxNanos = Math.max(maxNanos, captureNanos);
            totalNanos = saturatingAdd(totalNanos, captureNanos);
        }

        private StageSnapshot snapshot() {
            return new StageSnapshot(
                    label,
                    true,
                    acquired,
                    completed,
                    dropped,
                    failed,
                    lastNanos,
                    completed == 0L ? 0L : totalNanos / completed,
                    maxNanos
            );
        }

        private void appendTo(StringBuilder target) {
            target.append("stage{label=").append(label)
                    .append(", acquired=").append(acquired)
                    .append(", completed=").append(completed)
                    .append(", dropped=").append(dropped)
                    .append(", failed=").append(failed)
                    .append(", lastMicros=").append(lastNanos / 1_000L)
                    .append(", averageMicros=").append(completed == 0L ? 0L : totalNanos / completed / 1_000L)
                    .append(", maxMicros=").append(maxNanos / 1_000L)
                    .append('}');
        }
    }

    /**
     * Pooled capture view. It must be closed after resolving or abandoning the submission.
     */
    public static final class Capture implements AutoCloseable {
        private final RtGpuTimestampPool owner;
        private final int firstQuery;
        private final long[] ticks = new long[QUERIES_PER_CAPTURE];
        private final long[] durationNanos = new long[QUERIES_PER_CAPTURE - 1];
        private final String[] checkpointNames = new String[QUERIES_PER_CAPTURE];
        private String label;
        private int checkpointCount;
        private int writtenCheckpoints;
        private boolean resolved;
        private boolean available = true;

        private Capture(RtGpuTimestampPool owner, int firstQuery) {
            this.owner = owner;
            this.firstQuery = firstQuery;
        }

        private static long timestampDelta(long start, long end, int validBits) {
            long delta = end - start;
            if (validBits == Long.SIZE) {
                return delta >= 0L ? delta : 0L;
            }
            long mask = (1L << validBits) - 1L;
            return delta & mask;
        }

        private static long ticksToNanos(long ticks, float periodNanos) {
            double nanos = (double) ticks * periodNanos;
            if (!Double.isFinite(nanos) || nanos >= Long.MAX_VALUE) {
                return Long.MAX_VALUE;
            }
            return Math.max(0L, Math.round(nanos));
        }

        private void acquire(String label, String[] checkpoints) {
            if (!available) {
                throw new IllegalStateException("GPU timestamp capture is already leased");
            }
            this.label = label;
            this.checkpointCount = checkpoints.length;
            System.arraycopy(checkpoints, 0, checkpointNames, 0, checkpoints.length);
            this.writtenCheckpoints = 0;
            this.resolved = false;
            this.available = false;
        }

        /**
         * Resets the query range and writes the first timestamp.
         *
         * @param commandBuffer recording command buffer that owns the timestamp commands
         * @param pipelineStage Vulkan pipeline-stage mask for the first checkpoint
         */
        public void begin(VkCommandBuffer commandBuffer, int pipelineStage) {
            Objects.requireNonNull(commandBuffer, "commandBuffer");
            requireLeased();
            if (writtenCheckpoints != 0) {
                throw new IllegalStateException("GPU timestamp capture has already begun");
            }
            VK10.vkCmdResetQueryPool(commandBuffer, owner.queryPool, firstQuery, checkpointCount);
            write(commandBuffer, pipelineStage);
        }

        /**
         * Writes the next checkpoint timestamp.
         *
         * @param commandBuffer recording command buffer that owns the timestamp command
         * @param pipelineStage Vulkan pipeline-stage mask for this checkpoint
         */
        public void write(VkCommandBuffer commandBuffer, int pipelineStage) {
            Objects.requireNonNull(commandBuffer, "commandBuffer");
            requireLeased();
            if (writtenCheckpoints >= checkpointCount) {
                throw new IllegalStateException("GPU timestamp capture has no unwritten checkpoint");
            }
            VK10.vkCmdWriteTimestamp(commandBuffer, pipelineStage, owner.queryPool, firstQuery + writtenCheckpoints);
            writtenCheckpoints++;
        }

        /**
         * Resolves after the owning submission fence has signalled; no GPU wait flag is used.
         *
         * @return {@code true} if every query was available and aggregated
         */
        public boolean resolve() {
            requireLeased();
            if (resolved) {
                return true;
            }
            if (writtenCheckpoints != checkpointCount) {
                owner.recordFailure(this);
                return false;
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer results = stack.mallocLong(checkpointCount);
                int status = VK10.vkGetQueryPoolResults(
                        owner.device,
                        owner.queryPool,
                        firstQuery,
                        checkpointCount,
                        results,
                        Long.BYTES,
                        VK10.VK_QUERY_RESULT_64_BIT
                );
                if (status != VK10.VK_SUCCESS) {
                    owner.recordFailure(this);
                    return false;
                }
                for (int index = 0; index < checkpointCount; index++) {
                    ticks[index] = results.get(index);
                }
                long total = 0L;
                for (int index = 0; index < checkpointCount - 1; index++) {
                    long deltaTicks = timestampDelta(ticks[index], ticks[index + 1], owner.timestampValidBits);
                    long nanos = ticksToNanos(deltaTicks, owner.timestampPeriodNanos);
                    durationNanos[index] = nanos;
                    total = saturatingAdd(total, nanos);
                }
                resolved = true;
                owner.recordResolved(this, total);
                return true;
            }
        }

        /**
         * Returns the aggregation label after successful resolution.
         *
         * @return nonblank aggregation label
         */
        public String label() {
            requireResolved();
            return label;
        }

        /**
         * Returns the number of resolved durations between checkpoints.
         *
         * @return segment count
         */
        public int segmentCount() {
            requireResolved();
            return checkpointCount - 1;
        }

        /**
         * Returns the derived name of a checkpoint pair.
         *
         * @param index zero-based segment index
         * @return derived checkpoint-pair name
         */
        public String segmentName(int index) {
            requireResolved();
            requireSegment(index);
            return checkpointNames[index] + "To" + checkpointNames[index + 1];
        }

        /**
         * Returns one resolved segment duration.
         *
         * @param index zero-based segment index
         * @return duration in nanoseconds
         */
        public long segmentNanos(int index) {
            requireResolved();
            requireSegment(index);
            return durationNanos[index];
        }

        /**
         * Returns the saturated sum of all resolved segment durations.
         *
         * @return total duration in nanoseconds
         */
        public long totalNanos() {
            requireResolved();
            long total = 0L;
            for (int index = 0; index < checkpointCount - 1; index++) {
                total = saturatingAdd(total, durationNanos[index]);
            }
            return total;
        }

        /**
         * Returns this leased query range to its owning pool without waiting.
         */
        @Override
        public void close() {
            if (available) {
                return;
            }
            owner.release(this);
        }

        private void release() {
            label = null;
            for (int index = 0; index < checkpointCount; index++) {
                checkpointNames[index] = null;
            }
            checkpointCount = 0;
            writtenCheckpoints = 0;
            resolved = false;
            available = true;
        }

        private void requireLeased() {
            if (available) {
                throw new IllegalStateException("GPU timestamp capture is not leased");
            }
        }

        private void requireResolved() {
            requireLeased();
            if (!resolved) {
                throw new IllegalStateException("GPU timestamp capture has not resolved");
            }
        }

        private void requireSegment(int index) {
            if (index < 0 || index >= checkpointCount - 1) {
                throw new IndexOutOfBoundsException("GPU timestamp segment index=" + index);
            }
        }
    }
}
