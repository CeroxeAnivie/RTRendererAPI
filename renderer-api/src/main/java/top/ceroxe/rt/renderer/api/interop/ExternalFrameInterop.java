package top.ceroxe.rt.renderer.api.interop;

import java.util.Objects;

/**
 * Project-independent expert extension for negotiated zero-copy frame consumption.
 *
 * <p>Support is not inferred from the presence of this interface. A consumer must inspect the
 * current {@link #offer()} and receive an {@link ExternalFrameNegotiation.Accepted} result before
 * any native handle is exported. The returned provider-created session is the proof of the exact
 * contract; storing capabilities or constructing matching value objects is not activation.</p>
 */
public interface ExternalFrameInterop {
    /**
     * Returns the immutable current producer offer.
     *
     * <p>Implementations must be thread-safe. A later offer may differ after device loss or output
     * reconfiguration; an already accepted session retains its own exact contract.</p>
     *
     * @return immutable current producer offer
     */
    ExternalFrameOffer offer();

    /**
     * Opens one consumer session after exact capability negotiation.
     *
     * @param capabilities immutable consumer capabilities and preference order
     * @return accepted owned session or typed rejection
     */
    ExternalFrameNegotiation negotiate(ExternalFrameConsumerCapabilities capabilities);

    /** Utility validation shared by implementations before negotiation. */
    static ExternalFrameTransport requireCommonTransport(
            ExternalFrameOffer offer,
            ExternalFrameConsumerCapabilities capabilities
    ) {
        Objects.requireNonNull(offer, "offer");
        Objects.requireNonNull(capabilities, "capabilities");
        return capabilities.selectFrom(offer).orElseThrow(
                () -> new IllegalArgumentException("producer and consumer have no exact common transport")
        );
    }
}
