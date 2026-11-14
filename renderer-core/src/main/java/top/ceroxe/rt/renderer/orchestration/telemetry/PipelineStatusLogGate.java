package top.ceroxe.rt.renderer.orchestration.telemetry;

/**
 * Bounds pipeline status logs while forcing new failures through immediately.
 */
public final class PipelineStatusLogGate {
    private static final long INITIAL_LOGS = 8L;
    private final long frameInterval, slowInterval;
    private long lastFrame, lastSlow, lastFailures;

    /**
     * Creates a stateful log gate.
     *
     * @param frameInterval regular frame logging interval
     * @param slowInterval  slow-frame milestone interval
     */
    public PipelineStatusLogGate(long frameInterval, long slowInterval) {
        if (frameInterval <= 0 || slowInterval <= 0) throw new IllegalArgumentException("intervals must be positive");
        this.frameInterval = frameInterval;
        this.slowInterval = slowInterval;
    }

    private static String reason(boolean a, boolean b, boolean c, boolean d) {
        StringBuilder s = new StringBuilder();
        add(s, a, "initial");
        add(s, b, "periodic");
        add(s, c, "slowFrame");
        add(s, d, "presentationFailure");
        return s.toString();
    }

    private static void add(StringBuilder s, boolean on, String v) {
        if (on) {
            if (!s.isEmpty()) s.append('+');
            s.append(v);
        }
    }

    /**
     * Evaluates monotonic pipeline counters against the logging policy.
     *
     * @param frames   total completed frames
     * @param slow     total slow frames
     * @param failures total presentation failures
     * @return immutable logging decision
     */
    public synchronized Decision evaluate(long frames, long slow, long failures) {
        if (frames <= 0 || slow < 0 || failures < 0) throw new IllegalArgumentException("invalid counters");
        boolean slowMilestone = slow > lastSlow && (slow <= INITIAL_LOGS || slow % slowInterval == 0), failure = failures > lastFailures;
        if ((frames == lastFrame && !slowMilestone && !failure) || (frames > INITIAL_LOGS && frames % frameInterval != 0 && !slowMilestone && !failure))
            return new Decision(false, false, "");
        lastFrame = frames;
        if (slowMilestone) lastSlow = slow;
        if (failure) lastFailures = failures;
        return new Decision(true, failure, reason(frames <= INITIAL_LOGS, frames % frameInterval == 0, slowMilestone, failure));
    }

    /**
     * Immutable result of one gate evaluation.
     *
     * @param shouldLog whether a message should be emitted
     * @param warn      whether the message represents a warning
     * @param reason    stable reason token or token combination
     */
    public record Decision(boolean shouldLog, boolean warn, String reason) {
        /**
         * Normalizes a null reason to the empty string.
         */
        public Decision {
            reason = reason == null ? "" : reason;
        }
    }
}
