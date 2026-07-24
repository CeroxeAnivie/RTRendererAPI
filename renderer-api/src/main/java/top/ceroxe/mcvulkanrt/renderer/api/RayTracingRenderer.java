package top.ceroxe.mcvulkanrt.renderer.api;

/**
 * Complete host boundary for one independently owned renderer instance.
 *
 * <p>Scene and frame submission are single-writer operations. Observation may
 * occur from another thread, but {@link #close()} must be serialized with all
 * submissions. Implementations may process work asynchronously; accepted input
 * ownership never implies immediate GPU completion.</p>
 */
public interface RayTracingRenderer extends AutoCloseable {
    Status status();

    /** Atomically publishes one ordered set of persistent scene mutations. */
    SceneUpdateResult apply(SceneTransaction transaction);

    /** Schedules one frame from an accepted scene revision satisfying the request minimum. */
    FrameSubmissionResult submit(RenderFrameRequest request);

    /**
     * Acquires the newest completed external GPU image, or {@code null} when no
     * newer frame is available. The returned lease must always be closed.
     */
    GpuFrameLease acquireLatestFrame();

    /** Typed, immutable diagnostics; implementations must not expose internal owners. */
    RendererDiagnostics diagnostics();

    /**
     * Stops accepting work and releases native resources after every acquired GPU frame lease is
     * closed. Teardown may therefore be deferred when a consumer still owes GPU completion.
     */
    @Override
    void close();

    enum Status {
        READY,
        FAILED,
        CLOSED
    }

    /** Logical scene publication result; GPU work coalescing is intentionally not observable. */
    record SceneUpdateResult(long acceptedSceneRevision) {
        public SceneUpdateResult {
            if (acceptedSceneRevision < 0L) {
                throw new IllegalArgumentException("acceptedSceneRevision must not be negative");
            }
        }
    }

    /** Evidence that a frame entered the backend dispatch lane against this exact scene revision. */
    record FrameSubmissionResult(long frameSequence, long scheduledSceneRevision) {
        public FrameSubmissionResult {
            if (frameSequence < 0L || scheduledSceneRevision < 0L) {
                throw new IllegalArgumentException("frame submission revisions must not be negative");
            }
        }
    }
}
