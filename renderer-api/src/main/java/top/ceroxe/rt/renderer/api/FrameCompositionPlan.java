package top.ceroxe.rt.renderer.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable ordered composition request for completed generic resource mutations.
 *
 * <p>The plan names content snapshots rather than scene categories. A backend may only accept it
 * after it can consume every layer in order and publish {@link FramePresentationEvidence}; merely
 * creating this value never transfers target ownership or proves a visible frame.</p>
 */
public final class FrameCompositionPlan {
    private final ResourceMutationKey target;
    private final List<Layer> layers;

    /** Creates a non-empty ordered composition into one exact writable target mutation. */
    public FrameCompositionPlan(ResourceMutationKey target, List<? extends Layer> layers) {
        this.target = Objects.requireNonNull(target, "target");
        Objects.requireNonNull(layers, "layers");
        ArrayList<Layer> copied = new ArrayList<>(layers.size());
        for (Layer layer : layers) copied.add(Objects.requireNonNull(layer, "layer"));
        if (copied.isEmpty()) throw new IllegalArgumentException("frame composition requires at least one layer");
        if (copied.stream().anyMatch(layer -> layer.source().equals(target))) {
            throw new IllegalArgumentException("frame composition target cannot be read as one of its own source layers");
        }
        this.layers = List.copyOf(copied);
    }

    /** @return exact target content mutation whose ownership remains backend-defined until evidence exists */
    public ResourceMutationKey target() { return target; }

    /** @return source layers in strict back-to-front command order */
    public List<Layer> layers() { return layers; }

    /** One project-neutral source snapshot and its requested blend operation. */
    public record Layer(ResourceMutationKey source, Operation operation) {
        /** Rejects absent content identity or blend operation. */
        public Layer {
            source = Objects.requireNonNull(source, "source");
            operation = Objects.requireNonNull(operation, "operation");
        }
    }

    /** Portable composition operations; unsupported backends must reject rather than approximate. */
    public enum Operation {
        REPLACE,
        ALPHA_OVER,
        ADDITIVE
    }
}
