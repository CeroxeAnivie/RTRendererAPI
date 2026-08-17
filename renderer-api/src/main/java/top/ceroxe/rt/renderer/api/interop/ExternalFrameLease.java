package top.ceroxe.rt.renderer.api.interop;

import java.util.Optional;

/**
 * Exclusive, thread-safe consumer lease for one completed external frame.
 *
 * <p>The producer retains image-allocation ownership. The consumer owns exported native handles
 * according to their individual ownership rules. The image slot becomes reusable only after
 * {@link #release(ExternalFrameCompletionEvidence)} publishes evidence matching this frame and
 * the producer observes that evidence. Closing an imported active lease must fail closed rather
 * than guessing that presentation or queue submission implies completion.</p>
 */
public interface ExternalFrameLease extends AutoCloseable {
    /** @return portable image meaning */
    PortableFrameDescriptor descriptor();

    /** @return exact contract selected when the consumer session was opened */
    ExternalFrameTransport transport();

    /** @return owned external-memory handle, initially exported */
    OwnedExternalHandle<ExternalMemoryHandleType> memoryHandle();

    /** @return exact externally shared allocation region */
    ExternalMemoryRegion memoryRegion();

    /**
     * Returns producer-completion signaling.
     *
     * @return empty exactly when the selected producer contract is CPU-observed
     */
    Optional<ExternalSynchronizationSignal> acquireSignal();

    /**
     * Publishes consumer completion exactly once.
     *
     * @param evidence evidence for this frame using an advertised consumer-completion contract
     * @throws IllegalArgumentException when frame sequence or completion contract does not match
     * @throws IllegalStateException when the lease is no longer active
     */
    void release(ExternalFrameCompletionEvidence evidence);

    /** @return authoritative lifecycle state */
    LeaseState state();

    /** @return current provider-authored evidence snapshot */
    ExternalFrameConsumptionEvidence evidence();

    /**
     * Closes exporter-owned handles.
     *
     * <p>An imported active lease cannot be abandoned safely and must reject close. An unimported
     * active lease may be abandoned because no consumer GPU work can reference its image.</p>
     */
    @Override
    void close();

    /** Mutually exclusive consumer-side lease ownership state. */
    enum LeaseState {
        ACTIVE,
        RELEASED,
        CLOSED
    }
}
