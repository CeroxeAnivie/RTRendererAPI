package top.ceroxe.rt.renderer.orchestration.telemetry;

/**
 * Bounds frame-end timing logs without losing first and slow-frame evidence.
 */
public final class FrameEndTimingLogGate {
    private static final long INITIAL_LOGS = 8L;
    private final long frameInterval, slowFrameInterval;
    private long slowSamples, lastFrame, lastSlow;

    /**
     * 创建帧结束日志节流门。
     *
     * @param frameInterval     常规周期日志间隔
     * @param slowFrameInterval 慢帧日志间隔
     */
    public FrameEndTimingLogGate(long frameInterval, long slowFrameInterval) {
        if (frameInterval <= 0 || slowFrameInterval <= 0)
            throw new IllegalArgumentException("intervals must be positive");
        this.frameInterval = frameInterval;
        this.slowFrameInterval = slowFrameInterval;
    }

    private static String reason(boolean initial, boolean periodic, boolean slow) {
        StringBuilder b = new StringBuilder();
        add(b, initial, "initial");
        add(b, periodic, "periodic");
        add(b, slow, "slowFrame");
        return b.toString();
    }

    private static void add(StringBuilder b, boolean on, String v) {
        if (on) {
            if (!b.isEmpty()) b.append('+');
            b.append(v);
        }
    }

    /**
     * 评估一个已完成帧是否应产生日志。
     *
     * @param frameEnds 单调帧结束计数
     * @param slowFrame 当前帧是否为慢帧
     * @return 不可变日志决策
     */
    public synchronized Decision evaluate(long frameEnds, boolean slowFrame) {
        if (frameEnds <= 0) throw new IllegalArgumentException("frameEnds must be positive");
        if (slowFrame) slowSamples++;
        boolean initial = frameEnds <= INITIAL_LOGS, periodic = frameEnds % frameInterval == 0, slow = slowFrame && slowSamples > lastSlow && (slowSamples == 1 || slowSamples % slowFrameInterval == 0);
        if ((frameEnds == lastFrame && !slow) || (!initial && !periodic && !slow))
            return new Decision(false, "skip", slowSamples);
        lastFrame = frameEnds;
        if (slow) lastSlow = slowSamples;
        return new Decision(true, reason(initial, periodic, slow), slowSamples);
    }

    /**
     * 一次帧结束日志评估结果。
     *
     * @param shouldLog        是否记录日志
     * @param reason           稳定原因编码
     * @param slowFrameSamples 已观察到的慢帧样本数
     */
    public record Decision(boolean shouldLog, String reason, long slowFrameSamples) {
        /**
         * 规范化空原因文本。
         */
        public Decision {
            reason = reason == null ? "" : reason;
        }
    }
}
