package top.ceroxe.rt.renderer.api;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * Explicit bounded-in-flight policy used when deciding whether a resource generation may retire.
 *
 * <p>This is a snapshot of the sequencing facts used by a retirement decision, not a claim that
 * an object is already retired. An unknown consumer sequence is represented by an empty optional
 * and always fails closed. A known consumer may lag by no more than {@code maxFramesInFlight}
 * producer sequences; a larger lag is an invalid policy snapshot rather than permission to guess.</p>
 *
 * @param maxFramesInFlight positive bound on producer work outstanding
 * @param currentProducerSequence current non-negative producer sequence
 * @param lastConsumerSequence last sequence for which consumer completion was observed, if known
 */
public record ResourceRetirementPolicy(
        int maxFramesInFlight,
        long currentProducerSequence,
        OptionalLong lastConsumerSequence
) {
    public ResourceRetirementPolicy {
        if (maxFramesInFlight <= 0 || maxFramesInFlight > 1_024) {
            throw new IllegalArgumentException("maxFramesInFlight must be in [1, 1024]");
        }
        if (currentProducerSequence < 0L) {
            throw new IllegalArgumentException("currentProducerSequence must not be negative");
        }
        lastConsumerSequence = Objects.requireNonNull(lastConsumerSequence, "lastConsumerSequence");
        if (lastConsumerSequence.isPresent()) {
            long consumer = lastConsumerSequence.getAsLong();
            if (consumer < 0L || consumer > currentProducerSequence) {
                throw new IllegalArgumentException("lastConsumerSequence must be within producer progress");
            }
            long lag = currentProducerSequence - consumer;
            if (lag > maxFramesInFlight) {
                throw new IllegalArgumentException("consumer lag exceeds maxFramesInFlight bound");
            }
        }
    }

    /** @return whether a consumer completion sequence is available for a safe decision */
    public boolean consumerProgressKnown() {
        return lastConsumerSequence.isPresent();
    }

    /**
     * Reports whether the last producer use of a generation is safe to retire.
     *
     * @param lastUseProducerSequence exact producer sequence that last referenced the generation
     * @return true only when consumer completion for that sequence was observed
     */
    public boolean mayRetire(long lastUseProducerSequence) {
        if (lastUseProducerSequence < 0L || lastUseProducerSequence > currentProducerSequence
                || lastConsumerSequence.isEmpty()) {
            return false;
        }
        return lastConsumerSequence.getAsLong() >= lastUseProducerSequence;
    }

    /**
     * Requires a safe retirement decision rather than converting uncertainty into permission.
     *
     * @param lastUseProducerSequence exact last-use sequence
     * @throws IllegalStateException when consumer progress is unknown or insufficient
     */
    public void requireRetirable(long lastUseProducerSequence) {
        if (!mayRetire(lastUseProducerSequence)) {
            throw new IllegalStateException("resource generation is not proven safe to retire");
        }
    }
}
