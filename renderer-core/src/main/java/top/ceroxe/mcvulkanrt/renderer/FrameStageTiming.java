package top.ceroxe.mcvulkanrt.renderer;

import top.ceroxe.mcvulkanrt.renderer.diagnostics.RtTakeoverTimeline;

/** Low-overhead smoke ledger for host's host level renderer.render call chain. */
public final class FrameStageTiming {
    public enum Stage {
        OBS_LEVEL_START,
        OBS_SKY,
        OBS_ENVIRONMENT,
        OBS_FRAME_STATE,
        OBS_VISIBLE_SECTIONS,
        REPOSITION,
        SUBMIT_FEATURES,
        SUBMIT_ENTITIES,
        SUBMIT_BLOCK_ENTITIES,
        SUBMIT_DESTROY_ANIMATION,
        SUBMIT_PARTICLES,
        SUBMIT_OUTLINE_GIZMOS,
        POST_SUBMIT_RT,
        DYNAMIC_COLLECTION_END,
        PREPARE_RT_TERRAIN,
        TRY_RT_REPLACEMENT,
        PREPARE_FEATURES,
        FRAME_GRAPH_ALLOC,
        CHUNK_RENDER_PREPARE,
        FRAME_GRAPH_PASS_SETUP,
        FRAME_GRAPH_EXECUTE,
        FRAME_GRAPH_CLEANUP,
        COMPILE_SECTIONS,
        TERRAIN_UPLOAD,
        OCCLUSION,
        TAIL
    }

    private static final long[] TOTAL_NANOS = new long[Stage.values().length];
    private static final long[] LAST_TOTAL_NANOS = new long[Stage.values().length];
    private static final long[] FRAME_NANOS = new long[Stage.values().length];
    /* Reused by the render thread; publishing a slow-frame breakdown must not allocate per frame. */
    private static final long[] LAST_COMPLETED_NANOS = new long[Stage.values().length];
    private static long frameStartNanos;
    private static long stageStartNanos;
    private static long outerFrameSequence;
    private static long lastCompletedOuterFrameSequence;
    private static Stage currentStage;
    private static long frames;
    private static long lastFrames;
    private static long windowStartNanos;

    private FrameStageTiming() {
    }

    public static void beginFrame(long hostFrameSequence) {
        long now = System.nanoTime();
        frameStartNanos = now;
        stageStartNanos = now;
        outerFrameSequence = hostFrameSequence;
        java.util.Arrays.fill(FRAME_NANOS, 0L);
        currentStage = Stage.OBS_LEVEL_START;
    }

    public static void transition(Stage nextStage) {
        long now = System.nanoTime();
        if (currentStage != null) {
            long elapsed = now - stageStartNanos;
            TOTAL_NANOS[currentStage.ordinal()] += elapsed;
            FRAME_NANOS[currentStage.ordinal()] += elapsed;
        }
        currentStage = nextStage;
        stageStartNanos = now;
    }

    public static void endFrame() {
        long now = System.nanoTime();
        if (currentStage != null) {
            long elapsed = now - stageStartNanos;
            TOTAL_NANOS[currentStage.ordinal()] += elapsed;
            FRAME_NANOS[currentStage.ordinal()] += elapsed;
        }
        currentStage = null;
        lastCompletedOuterFrameSequence = outerFrameSequence;
        System.arraycopy(FRAME_NANOS, 0, LAST_COMPLETED_NANOS, 0, FRAME_NANOS.length);
        frames++;
        maybeLog(now);
    }

    /**
     * Formats a breakdown only for the matching outer host frame.
     *
     * <p>This method is called exclusively from the rare JFR low-frame and
     * visible-stall paths. Normal rendering only updates primitive counters and
     * the preallocated completed array above.</p>
     */
    public static String completedFrameAsLogFragment(long hostFrameSequence) {
        if (hostFrameSequence == 0L || lastCompletedOuterFrameSequence != hostFrameSequence) {
            return "unavailable";
        }
        StringBuilder stages = new StringBuilder(256);
        for (Stage stage : Stage.values()) {
            if (!stages.isEmpty()) {
                stages.append(',');
            }
            stages.append(stage.name().toLowerCase(java.util.Locale.ROOT))
                    .append('=')
                    .append(LAST_COMPLETED_NANOS[stage.ordinal()] / 1_000L);
        }
        return "levelFrame{outerSeq=" + hostFrameSequence + ", stages={" + stages + "}}";
    }

    private static void maybeLog(long now) {
        if (!RtTakeoverTimeline.edgeLoggingEnabled()) {
            return;
        }
        if (windowStartNanos == 0L) {
            windowStartNanos = now;
            remember();
            return;
        }
        if (now - windowStartNanos < 1_000_000_000L) {
            return;
        }
        long accountedNanos = 0L;
        StringBuilder stages = new StringBuilder(192);
        for (Stage stage : Stage.values()) {
            long deltaNanos = Math.max(0L, TOTAL_NANOS[stage.ordinal()] - LAST_TOTAL_NANOS[stage.ordinal()]);
            accountedNanos += deltaNanos;
            if (!stages.isEmpty()) {
                stages.append(", ");
            }
            stages.append(stage.name().toLowerCase(java.util.Locale.ROOT))
                    .append('=')
                    .append(deltaNanos / 1_000L);
        }
        RtTakeoverTimeline.logSmokeAggregate(
                "levelRendererStages",
                "windowMs=" + (now - windowStartNanos) / 1_000_000L
                        + ", frames=" + Math.max(0L, frames - lastFrames)
                        + ", accountedMicros=" + accountedNanos / 1_000L
                        + ", stages={" + stages + "}"
        );
        windowStartNanos = now;
        remember();
    }

    private static void remember() {
        lastFrames = frames;
        System.arraycopy(TOTAL_NANOS, 0, LAST_TOTAL_NANOS, 0, TOTAL_NANOS.length);
    }
}
