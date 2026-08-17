package top.ceroxe.rt.renderer.api.interop;

import java.util.Objects;

/** Exhaustive result of opening one external-frame consumer session. */
public sealed interface ExternalFrameNegotiation
        permits ExternalFrameNegotiation.Accepted, ExternalFrameNegotiation.Rejected {
    /** Negotiation succeeded and transfers ownership of a provider-created session. */
    record Accepted(ExternalFrameConsumerSession session) implements ExternalFrameNegotiation {
        public Accepted {
            session = Objects.requireNonNull(session, "session");
        }
    }

    /** Negotiation failed without creating a session or exporting native resources. */
    record Rejected(Reason reason, String detail) implements ExternalFrameNegotiation {
        public Rejected {
            reason = Objects.requireNonNull(reason, "reason");
            detail = Objects.requireNonNull(detail, "detail");
            if (detail.isBlank()) throw new IllegalArgumentException("detail must not be blank");
        }
    }

    /** Typed fail-closed reason for negotiation rejection. */
    enum Reason {
        NO_COMMON_TRANSPORT,
        PROVIDER_UNAVAILABLE,
        RESOURCE_LIMIT,
        POLICY_REJECTED
    }
}
