package top.ceroxe.mcvulkanrt.renderer.diagnostics;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-local monotonic clock shared by every RT takeover layer.
 *
 * <p>Wall-clock log prefixes are too coarse for queue causality and can move when
 * the system clock is adjusted. All takeover diagnostics use this origin so a
 * smoke parser can subtract host, bridge, native, and presentation events
 * without correlating independent clocks.</p>
 */
public final class RtTakeoverTimeline {
    private static final long ORIGIN_NANOS = System.nanoTime();
    private static final boolean EDGE_LOGGING_ENABLED =
            Boolean.getBoolean("mcvulkanrt.takeoverFlightRecorder.enabled");
    private static final boolean VERBOSE_IO_ENABLED =
            Boolean.getBoolean("mcvulkanrt.takeoverFlightRecorder.verboseIo");
    private static final Set<String> OBSERVED_EDGE_KEYS = ConcurrentHashMap.newKeySet();
    private static final AtomicLong NEXT_EVENT_SEQUENCE = new AtomicLong();

    public static final int KIND_EDGE = 1;
    public static final int KIND_SMOKE_AGGREGATE = 2;
    public static final int KIND_COMMAND_HOST_STALL = 3;

    private RtTakeoverTimeline() {
    }

    public static long elapsedMillis() {
        return Math.max(0L, (System.nanoTime() - ORIGIN_NANOS) / 1_000_000L);
    }

    public static void resetEdges() {
        if (EDGE_LOGGING_ENABLED) {
            OBSERVED_EDGE_KEYS.clear();
        }
    }

    public static void logEdge(String edge, String details) {
        if (!EDGE_LOGGING_ENABLED) {
            return;
        }
        if (!VERBOSE_IO_ENABLED && !OBSERVED_EDGE_KEYS.add(edge)) {
            return;
        }
        recordEdge(edge, details);
    }

    public static void logEdgeOnce(String key, String edge, String details) {
        if (EDGE_LOGGING_ENABLED && OBSERVED_EDGE_KEYS.add(key)) {
            logEdge(edge, details);
        }
    }

    /**
     * Writes a deliberately rate-limited smoke aggregate without participating
     * in edge de-duplication.  Callers must keep the hot path allocation-free
     * and invoke this only at their own fixed reporting boundary.
     */
    public static void logSmokeAggregate(String aggregate, String details) {
        if (!EDGE_LOGGING_ENABLED) {
            return;
        }
        recordAggregate(aggregate, details);
    }

    /**
     * Records a rare host-side wait as raw timing facts.
     *
     * <p>Host stalls are specifically valuable when normal smoke diagnostics
     * are disabled, so JFR's event-enable setting, rather than the smoke
     * switch, governs whether this method publishes an event.</p>
     */
    public static void logCommandHostStall(
            String thread,
            long commandPool,
            String stage,
            long waitNanos,
            long workNanos
    ) {
        RtTakeoverTimelineEvent event = new RtTakeoverTimelineEvent();
        if (!event.isEnabled()) {
            return;
        }
        populate(event, KIND_COMMAND_HOST_STALL, stage, thread);
        event.commandPool = commandPool;
        event.waitNanos = Math.max(0L, waitNanos);
        event.workNanos = Math.max(0L, workNanos);
        event.commit();
    }

    private static void recordEdge(String edge, String details) {
        RtTakeoverTimelineEvent event = new RtTakeoverTimelineEvent();
        if (!event.isEnabled()) {
            return;
        }
        populate(event, KIND_EDGE, edge, details);
        event.commit();
    }

    private static void recordAggregate(String aggregate, String details) {
        RtTakeoverTimelineEvent event = new RtTakeoverTimelineEvent();
        if (!event.isEnabled()) {
            return;
        }
        populate(event, KIND_SMOKE_AGGREGATE, aggregate, details);
        event.commit();
    }

    /**
     * Converts the legacy string-only call surface into a deterministic
     * primitive contract. The token code identifies the stable producer stage;
     * the fingerprint distinguishes different payloads of that stage without
     * putting a formatted message, object identity, or transient allocation in
     * the recording. Terminal failure dumps remain responsible for expanding
     * arbitrary text and stack traces.
     */
    private static void populate(
            RtTakeoverTimelineEvent event,
            int kind,
            String subject,
            String details
    ) {
        event.sceneSessionId = RtSceneCausalityRecorder.sessionId();
        event.eventSequence = NEXT_EVENT_SEQUENCE.incrementAndGet();
        event.elapsedMillis = elapsedMillis();
        event.threadId = Thread.currentThread().threadId();
        event.kind = kind;
        event.subjectCode = stableTokenCode(subject);
        event.detailsFingerprint = stableTokenCode(details);
    }

    /**
     * A fixed FNV-1a implementation makes legacy call-site labels queryable
     * as primitive values without depending on JVM-specific String hash salts.
     */
    public static long stableTokenCode(String token) {
        if (token == null || token.isBlank()) {
            return 0L;
        }
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < token.length(); index++) {
            hash ^= token.charAt(index);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    public static boolean verboseIoEnabled() {
        return VERBOSE_IO_ENABLED;
    }

    /**
     * Exposes the smoke switch to bounded aggregate diagnostics.  Producers
     * still use their allocation-free flight recorders; this is deliberately
     * not a general runtime logging flag.
     */
    public static boolean edgeLoggingEnabled() {
        return EDGE_LOGGING_ENABLED;
    }

    @Name("top.ceroxe.mcvulkanrt.RtTakeoverTimeline")
    @Label("MCVulkanRT RT Takeover Timeline")
    @Category({"MCVulkanRT", "Renderer", "Causality"})
    @StackTrace(false)
    static final class RtTakeoverTimelineEvent extends Event {
        long sceneSessionId;
        long eventSequence;
        long elapsedMillis;
        long threadId;
        int kind;
        long subjectCode;
        long detailsFingerprint;
        long commandPool;
        long waitNanos;
        long workNanos;
    }
}
