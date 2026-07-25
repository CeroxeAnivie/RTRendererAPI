package top.ceroxe.rt.renderer;

import top.ceroxe.rt.renderer.diagnostics.RtTakeoverTimeline;

/**
 * Low-overhead smoke ledger for host's host level renderer.render call chain.
 */
public final class FrameStageTiming {
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

    /**
     * 开始记录一个外层宿主帧。
     *
     * @param hostFrameSequence 外层帧序列号
     */
    public static void beginFrame(long hostFrameSequence) {
        long now = System.nanoTime();
        frameStartNanos = now;
        stageStartNanos = now;
        outerFrameSequence = hostFrameSequence;
        java.util.Arrays.fill(FRAME_NANOS, 0L);
        currentStage = Stage.OBS_LEVEL_START;
    }

    /**
     * 结束当前阶段并开始下一阶段。
     *
     * @param nextStage 下一计时阶段
     */
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

    /**
     * 完成当前帧并发布可供诊断读取的阶段快照。
     */
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
     *
     * @param hostFrameSequence 要读取的外层帧序列号
     * @return 序列匹配时返回阶段微秒摘要，否则返回 {@code unavailable}
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

    /**
     * 被帧级低开销计时器追踪的阶段。
     */
    public enum Stage {
        /**
         * 场景观察开始。
         */
        OBS_LEVEL_START,
        /**
         * 天空观察。
         */
        OBS_SKY,
        /**
         * 环境观察。
         */
        OBS_ENVIRONMENT,
        /**
         * 相机帧状态观察。
         */
        OBS_FRAME_STATE,
        /**
         * 可见区段观察。
         */
        OBS_VISIBLE_SECTIONS,
        /**
         * 场景原点重定位。
         */
        REPOSITION,
        /**
         * 特性提交。
         */
        SUBMIT_FEATURES,
        /**
         * 实体提交。
         */
        SUBMIT_ENTITIES,
        /**
         * 独立场景对象提交。
         */
        SUBMIT_BLOCK_ENTITIES,
        /**
         * 破坏动画提交。
         */
        SUBMIT_DESTROY_ANIMATION,
        /**
         * 粒子提交。
         */
        SUBMIT_PARTICLES,
        /**
         * 轮廓与辅助图元提交。
         */
        SUBMIT_OUTLINE_GIZMOS,
        /**
         * 光追提交后处理。
         */
        POST_SUBMIT_RT,
        /**
         * 动态事实收集结束。
         */
        DYNAMIC_COLLECTION_END,
        /**
         * 光追地形准备。
         */
        PREPARE_RT_TERRAIN,
        /**
         * 光追替换尝试。
         */
        TRY_RT_REPLACEMENT,
        /**
         * 渲染特性准备。
         */
        PREPARE_FEATURES,
        /**
         * 帧图资源分配。
         */
        FRAME_GRAPH_ALLOC,
        /**
         * 区段渲染准备。
         */
        CHUNK_RENDER_PREPARE,
        /**
         * 帧图 pass 配置。
         */
        FRAME_GRAPH_PASS_SETUP,
        /**
         * 帧图执行。
         */
        FRAME_GRAPH_EXECUTE,
        /**
         * 帧图清理。
         */
        FRAME_GRAPH_CLEANUP,
        /**
         * 区段编译。
         */
        COMPILE_SECTIONS,
        /**
         * 地形上传。
         */
        TERRAIN_UPLOAD,
        /**
         * 遮挡处理。
         */
        OCCLUSION,
        /**
         * 帧尾阶段。
         */
        TAIL
    }
}
