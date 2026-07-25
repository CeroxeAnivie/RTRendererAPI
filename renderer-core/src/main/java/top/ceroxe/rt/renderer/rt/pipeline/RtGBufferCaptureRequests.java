package top.ceroxe.rt.renderer.rt.pipeline;

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
    /**
     * System property that enables diagnostic G-buffer capture requests.
     */
    public static final String ENABLED_PROPERTY = "top.ceroxe.rt.oracleGBuffer.enabled";
    private static final AtomicReference<String> PENDING_REASON = new AtomicReference<>();

    private RtGBufferCaptureRequests() {
    }

    /**
     * Publishes a request if capture is enabled and no request is already pending.
     *
     * @param reason non-null diagnostic reason retained for the next dispatch
     * @return {@code true} only when this call occupied the mailbox
     */
    public static boolean request(String reason) {
        Objects.requireNonNull(reason, "reason");
        if (!Boolean.getBoolean(ENABLED_PROPERTY)) {
            return false;
        }
        return PENDING_REASON.compareAndSet(null, reason);
    }

    /**
     * Returns and consumes the pending reason, so one request produces one readback.
     *
     * @return the pending reason, or {@code null} when the mailbox is empty
     */
    public static String claim() {
        return PENDING_REASON.getAndSet(null);
    }

    /**
     * Restores a request only when command recording/submission failed before GPU ownership.
     *
     * @param reason reason to restore; {@code null} leaves the mailbox unchanged
     */
    public static void restore(String reason) {
        if (reason != null) {
            PENDING_REASON.compareAndSet(null, reason);
        }
    }
}
