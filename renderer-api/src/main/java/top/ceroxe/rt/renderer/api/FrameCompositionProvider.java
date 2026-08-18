package top.ceroxe.rt.renderer.api;

import java.util.Optional;

/**
 * Optional provider-owned bridge from completed generic texture mutations to external frame slots.
 *
 * <p>Implementations must be stable for the renderer lifetime. Returning an extension is a
 * capability claim: providers without a real frame-ring submission path must return empty from
 * {@link Renderer#extension(Class)} rather than exposing a placeholder.</p>
 */
public interface FrameCompositionProvider {
    /**
     * Returns the provider's portable layer limit. Requests above this limit must be rejected
     * without partially submitting work; the limit is not part of the API request shape.
     */
    default int maxLayers() {
        return Integer.MAX_VALUE;
    }

    /**
     * Records an ordered composition into a provider-owned external frame slot.
     *
     * @param request exact source mutations and requested output metadata
     * @return submission or rejection evidence; submission is not visibility
     */
    FrameCompositionEvidence compose(FrameCompositionRequest request);

    /**
     * Returns the latest authoritative evidence for one provider-owned composition sequence.
     *
     * <p>The provider may report submission and GPU completion without a consumer. It must only
     * report a consumer milestone after the negotiated consumer has published completion for the
     * exact same frame sequence. An empty result means that the sequence is unknown or has already
     * been retired from the bounded evidence ledger.</p>
     *
     * @param frameSequence exact composition sequence to inspect
     * @return immutable evidence, or empty when no such composition is retained
     */
    default Optional<FrameCompositionEvidence> compositionEvidence(long frameSequence) {
        if (frameSequence < 0L) throw new IllegalArgumentException("frameSequence must not be negative");
        return Optional.empty();
    }
}
