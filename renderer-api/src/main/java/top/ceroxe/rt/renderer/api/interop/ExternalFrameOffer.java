package top.ceroxe.rt.renderer.api.interop;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Immutable producer offer ordered from most to least preferred exact transport. */
public final class ExternalFrameOffer {
    private final List<ExternalFrameTransport> transports;

    public ExternalFrameOffer(List<ExternalFrameTransport> transports) {
        Objects.requireNonNull(transports, "transports");
        ArrayList<ExternalFrameTransport> copy = new ArrayList<>(transports.size());
        HashSet<ExternalFrameTransport> unique = new HashSet<>();
        for (ExternalFrameTransport transport : transports) {
            ExternalFrameTransport value = Objects.requireNonNull(transport, "transports contains null");
            if (!unique.add(value)) throw new IllegalArgumentException("duplicate offered transport: " + value);
            copy.add(value);
        }
        if (copy.isEmpty()) throw new IllegalArgumentException("at least one transport must be offered");
        this.transports = List.copyOf(copy);
    }

    /** @return immutable producer-preference order */
    public List<ExternalFrameTransport> transports() {
        return transports;
    }
}
