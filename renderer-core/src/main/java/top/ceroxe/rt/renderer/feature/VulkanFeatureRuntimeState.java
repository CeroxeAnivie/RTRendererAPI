package top.ceroxe.rt.renderer.feature;

import java.util.Objects;

/**
 * Small owner for one optional feature's frame-boundary state.
 *
 * <p>State changes are synchronized so capability publication and the next-frame resource
 * decision observe one atomic transition. A failed preferred feature never remains advertised as
 * active, while a required feature can still fail at its caller's strict boundary.</p>
 */
public final class VulkanFeatureRuntimeState {
    /** State visible to capability publication and frame-slot reconfiguration. */
    public enum Status {
        /** Device/provider can be used but has not executed yet. */ AVAILABLE,
        /** Preferred provider failed; verified fallback work has not yet been accepted. */ RECOVERING,
        /** A successful frame has executed. */ ACTIVE,
        /** Preferred path failed and a fallback owns the next frame. */ FALLBACK,
        /** No implementation can execute in this session. */ UNAVAILABLE,
        /** Session resources are closed. */ CLOSED
    }

    /** Immutable observation of one transition.
     * @param status published lifecycle status
     * @param implementation selected implementation identifier
     * @param reason diagnostic transition reason
     */
    public record Snapshot(Status status, String implementation, String reason) {
        /** Normalizes diagnostic strings while preserving an immutable snapshot. */
        public Snapshot {
            Objects.requireNonNull(status, "status");
            implementation = implementation == null ? "none" : implementation;
            reason = reason == null ? "" : reason;
        }
    }

    private Snapshot snapshot;

    /** Creates a state owner before the first frame is admitted.
     * @param initial initial non-terminal status
     * @param implementation initial implementation identifier
     * @param reason initial diagnostic reason
     */
    public VulkanFeatureRuntimeState(Status initial, String implementation, String reason) {
        if (initial == Status.CLOSED) throw new IllegalArgumentException("initial state cannot be CLOSED");
        snapshot = new Snapshot(initial, implementation, reason);
    }

    /** Returns the latest atomically published observation.
     * @return immutable state snapshot
     */
    public synchronized Snapshot snapshot() { return snapshot; }

    /** Returns whether the feature has completed a successful execution transition.
     * @return whether the current state is ACTIVE
     */
    public synchronized boolean active() { return snapshot.status() == Status.ACTIVE; }

    /**
     * Publishes a reserved but not-yet-executed implementation after an explicit re-enable.
     *
     * @param implementation reserved implementation identity
     * @param reason transition explanation
     */
    public synchronized void available(String implementation, String reason) {
        transition(Status.AVAILABLE, implementation, reason);
    }

    /** Publishes successful runtime activation.
     * @param implementation implementation that executed
     * @param reason evidence for activation
     */
    public synchronized void active(String implementation, String reason) {
        transition(Status.ACTIVE, implementation, reason);
    }

    /** Publishes a preferred-feature fallback for the next frame boundary.
     * @param implementation fallback implementation
     * @param reason structured failure reason
     */
    public synchronized void fallback(String implementation, String reason) {
        transition(Status.FALLBACK, implementation, reason);
    }

    /**
     * Publishes recovery intent without claiming that the fallback has executed.
     * @param implementation fallback implementation being prepared
     * @param reason original failure and pending recovery boundary
     */
    public synchronized void recovering(String implementation, String reason) {
        transition(Status.RECOVERING, implementation, reason);
    }

    /** Publishes permanent unavailability for this session.
     * @param reason structured availability reason
     */
    public synchronized void unavailable(String reason) {
        transition(Status.UNAVAILABLE, "none", reason);
    }

    /** Publishes the terminal session state. */
    public synchronized void close() {
        transition(Status.CLOSED, "none", "feature session closed");
    }

    private void transition(Status next, String implementation, String reason) {
        snapshot = new Snapshot(next, implementation, reason);
    }
}
