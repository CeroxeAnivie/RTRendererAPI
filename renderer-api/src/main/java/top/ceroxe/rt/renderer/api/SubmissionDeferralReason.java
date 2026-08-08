package top.ceroxe.rt.renderer.api;

/**
 * Stable classification for a recoverable submission-capacity refusal.
 *
 * <p>The enum is deliberately independent from provider diagnostics. Applications aggregate and
 * retry by this value without parsing backend text.</p>
 */
public enum SubmissionDeferralReason {
    /** Every bounded renderer frame slot is retained or in flight. */
    FRAME_RING_FULL,
    /** Persistent scene state is still referenced by previously submitted GPU work. */
    SCENE_UPDATE_BACKLOG,
    /** Frame-local resources cannot be resized or replaced until prior GPU work retires. */
    FRAME_RESOURCES_BUSY,
    /** CPU readback ownership is preventing reuse of a bounded frame slot. */
    READBACK_BACKLOG,
    /** The managed presentation queue reached its configured producer lead. */
    PRESENTATION_BACKLOG,
    /** A negotiated feature changed its plan and the same frame must be recorded again. */
    FEATURE_RECONFIGURATION,
    /** A bounded memory budget temporarily refused additional resident resources. */
    RESOURCE_PRESSURE,
    /** Device recreation is waiting for externally owned resources to retire. */
    DEVICE_RECOVERING,
    /** Provider-defined recoverable capacity that has no more specific public classification. */
    PROVIDER_CAPACITY
}
