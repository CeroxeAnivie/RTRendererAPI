package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One-slot handoff from a semantic Oracle failure to the next RT diagnostic frame.
 *
 * <p>This is intentionally a mailbox, not a queue. Repeated failures before the
 * next dispatch carry the same root-cause class and must not grow an unbounded
 * readback backlog on the render thread.</p>
 */
public final class RtGBufferCaptureRequests {
    public static final String ENABLED_PROPERTY = "mcvulkanrt.oracleGBuffer.enabled";
    private static final AtomicReference<String> PENDING_REASON = new AtomicReference<>();

    private RtGBufferCaptureRequests() {
    }

    public static boolean request(String reason) {
        Objects.requireNonNull(reason, "reason");
        if (!Boolean.getBoolean(ENABLED_PROPERTY)) {
            return false;
        }
        return PENDING_REASON.compareAndSet(null, reason);
    }

    /** Returns and consumes the pending reason, so one request produces one readback. */
    public static String claim() {
        return PENDING_REASON.getAndSet(null);
    }

    /** Restores a request only when command recording/submission failed before GPU ownership. */
    public static void restore(String reason) {
        if (reason != null) {
            PENDING_REASON.compareAndSet(null, reason);
        }
    }
}
