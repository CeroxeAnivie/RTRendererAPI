package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import static top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.OpticalWallTimeline.Stage.*;

/** Checks independent interval coverage, owner isolation and fixed-capacity loss handling. */
public final class OpticalWallTimelineSelfTest {
    private OpticalWallTimelineSelfTest() { }
    public static void main(String[] args) throws Exception {
        System.setProperty("top.ceroxe.rt.medium.opticalWallTimeline", "true");
        var recording = OpticalWallTimeline.allocate(32_768);
        recording.begin();
        for (int i = 0; i < 1000; i++) {
            int outer = OpticalWallTimeline.enter(TRY_SUBMIT);
            int inner = OpticalWallTimeline.enter(CUDA_STREAM_SYNCHRONIZE);
            OpticalWallTimeline.leave(inner);
            inner = OpticalWallTimeline.enter(CUDA_UPLOAD);
            OpticalWallTimeline.leave(inner);
            OpticalWallTimeline.leave(outer);
        }
        int[] otherToken = {Integer.MIN_VALUE};
        Thread other = new Thread(() -> {
            int ignored = OpticalWallTimeline.enter(REQUEST_BUILD);
            otherToken[0] = ignored;
            OpticalWallTimeline.leave(ignored);
        });
        other.start(); other.join();
        require(otherToken[0] == -1, "other-thread calls must not enter the owner window");
        long wall = recording.finish();
        String json = recording.json(1000);
        require(wall > 0 && json.contains("\"events\":3001"), "all nested intervals recorded");
        require(json.contains("\"coveragePercent\":100.0,\"uncoveredMs\":0.0"), "exact wall coverage");
        require(json.contains("\"droppedEvents\":0"), "normal window loses no intervals");
        require(json.contains("\"selfP95Ms\":") && json.contains("\"rawIntervals\":["), "distribution and raw boundaries retained");

        var bounded = OpticalWallTimeline.allocate(2);
        bounded.begin();
        int kept = OpticalWallTimeline.enter(TRY_SUBMIT);
        int lostOuter = OpticalWallTimeline.enter(CUDA_EXCHANGE);
        int lostInner = OpticalWallTimeline.enter(CUDA_UPLOAD);
        OpticalWallTimeline.leave(lostInner);
        OpticalWallTimeline.leave(lostOuter);
        OpticalWallTimeline.leave(kept);
        bounded.finish();
        json = bounded.json(1);
        require(json.contains("\"droppedEvents\":2"), "capacity exhaustion reports all lost events");
        require(bounded.attemptedEvents()==4, "warmup sizing counts retained and lost nested events");
        require(json.contains("\"uncoveredMs\":0.0"), "lost detail remains covered by the measured parent");

        var ordered = OpticalWallTimeline.allocate(8);
        ordered.begin();
        int parent = OpticalWallTimeline.enter(TRY_SUBMIT);
        int child = OpticalWallTimeline.enter(CUDA_EXCHANGE);
        try {
            OpticalWallTimeline.leave(parent);
            throw new AssertionError("out-of-order interval close must fail");
        } catch (IllegalStateException expected) {
            // A rejected close must leave the stack usable so the real owner can finish it.
        }
        OpticalWallTimeline.leave(child);
        OpticalWallTimeline.leave(parent);
        ordered.finish();
        ordered.json(1);

        var awaited = OpticalWallTimeline.allocate(64);
        awaited.begin();
        int awaiting = OpticalWallTimeline.enter(NATIVE_TASK_AWAIT);
        long workerId;
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            workerId = executor.submit(OpticalWallTimeline.awaited(() -> {
                int kernel = OpticalWallTimeline.enter(CUDA_KERNEL_ENQUEUE);
                try { return Thread.currentThread().threadId(); }
                finally { OpticalWallTimeline.leave(kernel); }
            })).get();
        }
        OpticalWallTimeline.leave(awaiting);
        awaited.finish();
        json = awaited.json(1);
        require(workerId != Thread.currentThread().threadId(), "test must cross a real executor boundary");
        require(json.contains("WINDOW/NATIVE_TASK_AWAIT/NATIVE_TASK_EXECUTION/CUDA_KERNEL_ENQUEUE"),
                "awaited executor work retains its explicitly captured parent");
        require(json.contains("\"events\":4") && json.contains("\"droppedEvents\":0"), "cross-thread intervals fully retained");
        System.out.println("OpticalWallTimelineSelfTest passed: exact partition, raw distribution, owner isolation, bounded loss, nesting and awaited executor propagation");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
