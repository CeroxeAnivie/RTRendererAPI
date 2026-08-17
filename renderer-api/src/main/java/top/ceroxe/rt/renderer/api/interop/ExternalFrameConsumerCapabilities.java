package top.ceroxe.rt.renderer.api.interop;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable consumer support ordered from most to least preferred exact transport. */
public final class ExternalFrameConsumerCapabilities {
    private final List<ExternalFrameTransport> acceptedTransports;

    public ExternalFrameConsumerCapabilities(List<ExternalFrameTransport> acceptedTransports) {
        Objects.requireNonNull(acceptedTransports, "acceptedTransports");
        ArrayList<ExternalFrameTransport> copy = new ArrayList<>(acceptedTransports.size());
        HashSet<ExternalFrameTransport> unique = new HashSet<>();
        for (ExternalFrameTransport transport : acceptedTransports) {
            ExternalFrameTransport value = Objects.requireNonNull(transport, "acceptedTransports contains null");
            if (!unique.add(value)) throw new IllegalArgumentException("duplicate accepted transport: " + value);
            copy.add(value);
        }
        if (copy.isEmpty()) throw new IllegalArgumentException("at least one accepted transport is required");
        this.acceptedTransports = List.copyOf(copy);
    }

    /** @return immutable consumer-preference order */
    public List<ExternalFrameTransport> acceptedTransports() {
        return acceptedTransports;
    }

    /**
     * Finds the first exact match in consumer preference order.
     *
     * <p>No partial matching is permitted: format, memory type, producer synchronization, and the
     * image-import profile, and the complete synchronization contract must agree.</p>
     *
     * @param offer producer offer
     * @return exact selected transport or empty when no exact contract is shared
     */
    public Optional<ExternalFrameTransport> selectFrom(ExternalFrameOffer offer) {
        Objects.requireNonNull(offer, "offer");
        for (ExternalFrameTransport accepted : acceptedTransports) {
            if (offer.transports().contains(accepted)) return Optional.of(accepted);
        }
        return Optional.empty();
    }
}
