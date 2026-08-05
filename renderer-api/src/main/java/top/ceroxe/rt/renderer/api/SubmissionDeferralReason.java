package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Stable classification for a recoverable submission-capacity refusal.
 *
 * <p>The enum is deliberately independent from provider diagnostics. Applications may aggregate
 * and retry by this value without parsing backend text; {@link #UNSPECIFIED} preserves the
 * behavior of providers compiled against API versions before {@code 0.5.1}.</p>
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
    PROVIDER_CAPACITY,
    /** Legacy or third-party provider supplied only an unstructured diagnostic message. */
    UNSPECIFIED;

    private static final String PREFIX = "[RTR-DEFER:";
    private static final String SUFFIX = "] ";

    static String encode(SubmissionDeferralReason reason, String detail) {
        SubmissionDeferralReason checkedReason = Objects.requireNonNull(reason, "reason");
        String checkedDetail = requireDetail(detail);
        if (checkedReason == UNSPECIFIED) return checkedDetail;
        return PREFIX + checkedReason.name() + SUFFIX + checkedDetail;
    }

    static SubmissionDeferralReason decode(String diagnostic) {
        String checked = requireDetail(diagnostic);
        if (!checked.startsWith(PREFIX)) return UNSPECIFIED;
        int end = checked.indexOf(SUFFIX, PREFIX.length());
        if (end < 0) return UNSPECIFIED;
        try {
            return valueOf(checked.substring(PREFIX.length(), end));
        } catch (IllegalArgumentException unknownCode) {
            return UNSPECIFIED;
        }
    }

    static String detail(String diagnostic) {
        String checked = requireDetail(diagnostic);
        SubmissionDeferralReason decoded = decode(checked);
        if (decoded == UNSPECIFIED) return checked;
        int end = checked.indexOf(SUFFIX, PREFIX.length());
        return checked.substring(end + SUFFIX.length());
    }

    private static String requireDetail(String detail) {
        String checked = Objects.requireNonNull(detail, "detail");
        if (checked.isBlank()) throw new IllegalArgumentException("detail must not be blank");
        return checked;
    }
}
