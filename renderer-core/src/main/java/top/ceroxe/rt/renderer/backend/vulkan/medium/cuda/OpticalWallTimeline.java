package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * Explicitly enabled wall intervals. An awaited executor task carries its exact parent interval
 * across threads; its caller does not record until Future.get proves that task has returned.
 * GPU durations are not inserted: host waits can enclose the same GPU work. Event storage is
 * allocated before the window; only an enabled executor handoff needs one task wrapper.
 */
public final class OpticalWallTimeline {
    public static final boolean ENABLED = Boolean.getBoolean("top.ceroxe.rt.medium.opticalWallTimeline");
    public enum Stage {
        WINDOW, REQUEST_BUILD, TRY_SUBMIT, FRAME_POLL_AND_LEASE, DIAGNOSTICS, PARK,
        HOST_ADMISSION, SESSION_ADMISSION, SESSION_PUMP, FEATURE_EXTENT_NEGOTIATION,
        FRAME_PREPARATION, ROUTE_AND_PIPELINES, FEATURE_INPUT_REUSE, RESOURCE_BUDGET_AND_ENSURE,
        FRAME_PRIMITIVES, TEMPORAL_UNIFORMS_DESCRIPTORS, CAMERA_SCATTERING_PREPARE,
        MEDIUM_RESOLVE_PREPARE, FINAL_FRAME_RECORD_AND_SUBMIT, SLOT_PUBLICATION,
        SHARED_EXCHANGE, VULKAN_RELEASE_RECORD, VULKAN_RELEASE_SUBMIT, VULKAN_CONSUMER_RECORD,
        VULKAN_CONSUMER_SUBMIT, VULKAN_CONSUMER_FENCE_WAIT, GPU_QUERY_RESOLVE,
        CUDA_EXCHANGE, CUDA_SEMAPHORE_WAIT_ENQUEUE, CUDA_NATIVE_CALLBACK,
        CUDA_SEMAPHORE_SIGNAL_ENQUEUE, CUDA_COMPLETION_CALLBACK, CUDA_STREAM_SYNCHRONIZE,
        CUDA_KERNEL_ENQUEUE, CUDA_UPLOAD, CUDA_CONTEXT_SYNCHRONIZE, CUDA_DOWNLOAD,
        CUDA_DOWNLOAD_ASYNC_ENQUEUE, CUDA_CONTEXT_PUSH, CUDA_CONTEXT_POP, CUDA_KERNEL_EVENT_RESOLVE,
        NATIVE_TASK_AWAIT, NATIVE_TASK_EXECUTION,
        PRIMARY_MEDIUM_NATIVE, BOUNDARY_MEDIUM_NATIVE, DENSITY_SCATTERING_NATIVE,
        MULTIPLE_SCATTERING_NATIVE, OPTICAL_MEDIUM_NATIVE
    }

    private static volatile Recording active;
    private OpticalWallTimeline() { }

    public static int enter(Stage stage) {
        if (!ENABLED) return -1;
        Recording recording = active;
        return recording != null && recording.writer == Thread.currentThread()
                ? recording.enter(stage.ordinal(), System.nanoTime()) : -1;
    }

    public static void leave(int token) {
        if (!ENABLED || token == -1) return;
        Recording recording = active;
        if (recording == null || recording.writer != Thread.currentThread())
            throw new IllegalStateException("wall interval left outside its owner window");
        recording.leave(token, System.nanoTime());
    }

    public static int next(int token, Stage stage) {
        leave(token);
        return enter(stage);
    }

    /**
     * Only for an operation whose caller immediately awaits its Future before leaving its scope.
     * The identity is explicitly carried across submit/get rather than guessed from thread timing.
     * Like Perfetto flow IDs, the captured parent connects two distinct thread tracks; raw events
     * retain their actual thread ID. Unlike an asynchronous flow, this awaited task is also proven
     * to be contained in its caller interval, so its duration can partition that blocking interval.
     */
    public static <T> Callable<T> awaited(Callable<T> operation) {
        if (!ENABLED) return operation;
        Recording recording = active;
        Thread caller = Thread.currentThread();
        if (recording == null || recording.writer != caller) return operation;
        int parent = recording.stack[recording.depth - 1];
        return () -> {
            if (recording.writer != caller || recording.stack[recording.depth - 1] != parent)
                throw new IllegalStateException("awaited trace task escaped its captured parent");
            recording.writer = Thread.currentThread();
            int execution = enter(Stage.NATIVE_TASK_EXECUTION);
            try { return operation.call(); }
            finally {
                leave(execution);
                recording.writer = caller;
            }
        };
    }

    public static Recording allocate(int capacity) {
        if (!ENABLED) throw new IllegalStateException("wall timeline is not enabled");
        return new Recording(capacity);
    }

    /** The fixed arrays, not a per-event object, form the raw nested interval tree. */
    public static final class Recording {
        private final Thread owner = Thread.currentThread();
        private volatile Thread writer = owner;
        private final long[] starts, ends;
        private final long[] threadIds;
        private final int[] kinds, parents;
        private final int[] stack = new int[128];
        private int size, depth, ignoredDepth;
        private long dropped;
        private boolean finished;

        private Recording(int capacity) {
            if (capacity < 2) throw new IllegalArgumentException("at least two wall event slots required");
            starts = new long[capacity]; ends = new long[capacity]; threadIds = new long[capacity];
            kinds = new int[capacity]; parents = new int[capacity];
        }
        public long droppedEvents(){return dropped;}
        /** Includes capacity losses, so warmup can size later windows without recording allocations. */
        public long attemptedEvents(){return (long)size + dropped;}

        public long begin() {
            if (owner != Thread.currentThread() || size != 0 || active != null)
                throw new IllegalStateException("wall windows cannot overlap or restart");
            active = this;
            long started = System.nanoTime();
            enter(Stage.WINDOW.ordinal(), started);
            return started;
        }

        public long finish() {
            if (owner != Thread.currentThread() || writer != owner || active != this || depth != 1 || ignoredDepth != 0)
                throw new IllegalStateException("wall interval tree is unbalanced");
            long ended = System.nanoTime();
            leave(0, ended);
            active = null;
            finished = true;
            return ended - starts[0];
        }

        private int enter(int kind, long now) {
            if (ignoredDepth != 0 || size == starts.length || depth == stack.length) {
                ignoredDepth++; dropped++;
                return -2;
            }
            int token = size++;
            starts[token] = now; kinds[token] = kind;
            threadIds[token] = Thread.currentThread().threadId();
            parents[token] = depth == 0 ? -1 : stack[depth - 1];
            stack[depth++] = token;
            return token;
        }

        private void leave(int token, long now) {
            if (token == -2) { ignoredDepth--; return; }
            if (depth == 0 || stack[depth - 1] != token || ignoredDepth != 0 || now < starts[token])
                throw new IllegalStateException("wall interval nesting or clock is invalid");
            ends[token] = now;
            depth--;
        }

        public String json(int frames) {
            if (!finished || frames < 1) throw new IllegalStateException("finish the wall window first");
            long[] self = new long[size];
            long[] lastChildEnd = new long[size];
            for (int i = 0; i < size; i++) self[i] = ends[i] - starts[i];
            for (int i = 1; i < size; i++) {
                int parent = parents[i];
                if (starts[i] < starts[parent] || ends[i] > ends[parent])
                    throw new IllegalStateException("child wall interval escapes parent");
                if (starts[i] < lastChildEnd[parent])
                    throw new IllegalStateException("sibling wall intervals overlap");
                lastChildEnd[parent] = ends[i];
                self[parent] -= ends[i] - starts[i];
            }
            String[] paths = new String[size];
            var groups = new java.util.LinkedHashMap<String, Samples>();
            long sum = 0;
            for (int i = 0; i < size; i++) {
                if (self[i] < 0) throw new IllegalStateException("overlapping sibling wall intervals");
                sum = Math.addExact(sum, self[i]);
                String name = Stage.values()[kinds[i]].name();
                String path = parents[i] < 0 ? name : paths[parents[i]] + "/" + name;
                Samples samples=groups.computeIfAbsent(path, Samples::new);
                paths[i]=samples.path;
                samples.add(ends[i] - starts[i], self[i]);
            }
            long wall = ends[0] - starts[0];
            if (sum != wall) throw new IllegalStateException("wall intervals do not cover the window");
            var ranked = new ArrayList<>(groups.values());
            ranked.sort(Comparator.comparingLong((Samples s) -> s.totalSelf).reversed());
            StringBuilder json = new StringBuilder(8192);
            json.append("{\n\"clock\":\"System.nanoTime; nested host intervals with explicit awaited executor parent propagation\",")
                    .append("\n\"interpretation\":\"self intervals partition this wall window; host waits overlap GPU work; self is measured boundary time, not CPU compute or GPU idle\",")
                    .append("\n\"frames\":").append(frames).append(",\"events\":").append(size)
                    .append(",\"droppedEvents\":").append(dropped)
                    .append(",\"wallMs\":").append(number(wall / 1e6))
                    .append(",\"exclusiveSumMs\":").append(number(sum / 1e6))
                    .append(",\"coveragePercent\":100.0,\"uncoveredMs\":0.0,\n\"rankedHostBoundaries\":[");
            for (int i = 0; i < ranked.size(); i++) {
                Samples samples = ranked.get(i);
                if (i != 0) json.append(',');
                long[] sorted = samples.sorted();
                json.append("\n{\"path\":\"").append(samples.path).append("\",\"count\":").append(samples.count)
                        .append(",\"inclusiveMs\":").append(number(samples.totalInclusive / 1e6))
                        .append(",\"selfMs\":").append(number(samples.totalSelf / 1e6))
                        .append(",\"selfMsPerFrame\":").append(number(samples.totalSelf / (double) frames / 1e6))
                        .append(",\"selfMsPerSecond\":").append(number(samples.totalSelf * 1000.0 / wall))
                        .append(",\"selfPercentWall\":").append(number(samples.totalSelf * 100.0 / wall))
                        .append(",\"selfMeanMs\":").append(number(samples.totalSelf / (double) samples.count / 1e6))
                        .append(",\"selfP50Ms\":").append(number(sorted[(sorted.length - 1) / 2] / 1e6))
                        .append(",\"selfP95Ms\":").append(number(sorted[(int) Math.ceil(sorted.length * .95) - 1] / 1e6))
                        .append(",\"selfMaxMs\":").append(number(sorted[sorted.length - 1] / 1e6)).append('}');
            }
            json.append("\n],\n\"rawIntervalColumns\":[\"stageOrdinal\",\"parentIndex\",\"startOffsetNanos\",\"durationNanos\",\"javaThreadId\"],\n\"stageNames\":[");
            Stage[] stages = Stage.values();
            for (int i = 0; i < stages.length; i++) {
                if (i != 0) json.append(',');
                json.append('"').append(stages[i].name()).append('"');
            }
            json.append("],\n\"rawIntervals\":[");
            for (int i = 0; i < size; i++) {
                if (i != 0) json.append(',');
                json.append('[').append(kinds[i]).append(',').append(parents[i]).append(',')
                        .append(starts[i] - starts[0]).append(',').append(ends[i] - starts[i]).append(',')
                        .append(threadIds[i]).append(']');
            }
            return json.append("]\n}\n").toString();
        }
    }

    private static String number(double value) { return String.format(Locale.ROOT, "%.9f", value); }
    private static final class Samples {
        final String path;
        long totalInclusive, totalSelf;
        long[] values = new long[16];
        int count;
        Samples(String path) { this.path = path; }
        void add(long inclusive, long self) {
            totalInclusive += inclusive; totalSelf += self;
            if (count == values.length) values = Arrays.copyOf(values, count * 2);
            values[count++] = self;
        }
        long[] sorted() { long[] sorted = Arrays.copyOf(values, count); Arrays.sort(sorted); return sorted; }
    }
}
