package top.ceroxe.mcvulkanrt.renderer.rt.device;

/**
 * Bounds visual work submitted while immutable terrain resources converge.
 *
 * <p>A committed world generation remains safe to read while its successor is
 * built, but that lifetime guarantee is not permission to enqueue a full ray
 * dispatch for every host extraction frame. Terrain BLAS, material, TLAS,
 * dynamic TLAS and ray dispatches share one ordered Vulkan queue. During large
 * streaming bursts, unbounded display work therefore increases the fence
 * latency of the very resources needed to advance the visible front.</p>
 *
 * <p>This policy preserves two independent liveness guarantees: a newly bound
 * terrain generation is displayed immediately, and camera/dynamic state cannot
 * remain visually stale beyond one bounded interval. Once convergence ends,
 * the normal stable-frame path owns unrestricted frame production.</p>
 */
final class RtResourceConvergenceCadence {
    private RtResourceConvergenceCadence() {
    }

    static boolean shouldDispatchCommittedFront(
            long publishedWorldRevision,
            long lastDisplayedWorldRevision,
            long nowNanos,
            long nextVisualDeadlineNanos
    ) {
        if (publishedWorldRevision < 0L || lastDisplayedWorldRevision < -1L) {
            throw new IllegalArgumentException("committed terrain revisions are invalid");
        }
        return publishedWorldRevision > lastDisplayedWorldRevision
                || deadlineReached(nowNanos, nextVisualDeadlineNanos);
    }

    static long nextVisualDeadline(long nowNanos, long intervalNanos) {
        if (intervalNanos <= 0L) {
            throw new IllegalArgumentException("visual convergence interval must be positive");
        }
        /* nanoTime is allowed to wrap; subtraction-based comparison remains valid. */
        return nowNanos + intervalNanos;
    }

    static boolean deadlineReached(long nowNanos, long deadlineNanos) {
        return nowNanos - deadlineNanos >= 0L;
    }
}
