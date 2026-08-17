package top.ceroxe.rt.renderer.api.interop;

import java.util.Objects;

/**
 * Provider-created session proving that one exact external-frame contract was negotiated.
 * Implementations must make polling, transport observation, and close thread-safe.
 */
public interface ExternalFrameConsumerSession extends AutoCloseable {
    /** @return exact negotiated transport */
    ExternalFrameTransport transport();

    /**
     * Polls for a completed frame without nullable control flow.
     *
     * @return an exclusive active lease or the shared not-ready value
     * @throws IllegalStateException when this session is closed
     */
    PollResult pollLatestFrame();

    /** Stops new lease acquisition; already acquired leases retain their obligations. */
    @Override
    void close();

    /** Exhaustive result of one non-blocking poll. */
    sealed interface PollResult permits FrameAvailable, FrameNotReady {
    }

    /** Transfers exclusive ownership of one active lease. */
    record FrameAvailable(ExternalFrameLease lease) implements PollResult {
        public FrameAvailable {
            lease = Objects.requireNonNull(lease, "lease");
            if (lease.state() != ExternalFrameLease.LeaseState.ACTIVE) {
                throw new IllegalArgumentException("available lease must be active");
            }
        }
    }

    /** Shared allocation-free result when no newer completed frame is available. */
    enum FrameNotReady implements PollResult {
        INSTANCE
    }
}
