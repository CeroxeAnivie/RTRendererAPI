package top.ceroxe.rt.renderer.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Targetless composition request for a provider-owned external frame.
 *
 * <p>The caller identifies exact completed source mutations. The provider owns destination slot
 * selection and must publish its exact frame sequence in {@link FrameCompositionEvidence}; a
 * generic resource identity is never guessed or reused as an external frame identity.</p>
 */
public final class FrameCompositionRequest {
    private final List<FrameCompositionPlan.Layer> layers;
    private final int width;
    private final int height;
    private final FrameOutputFormat format;
    private final long frameSequence;
    private final long sceneRevision;
    private final AlphaEncoding alphaEncoding;

    /** Creates an immutable ordered request for one provider-owned output frame. */
    public FrameCompositionRequest(
            List<? extends FrameCompositionPlan.Layer> layers,
            int width,
            int height,
            FrameOutputFormat format,
            long frameSequence,
            long sceneRevision
    ) {
        this(layers, width, height, format, frameSequence, sceneRevision, AlphaEncoding.PREMULTIPLIED);
    }

    /** Creates a request with an explicit alpha representation for blend operations. */
    public FrameCompositionRequest(
            List<? extends FrameCompositionPlan.Layer> layers,
            int width,
            int height,
            FrameOutputFormat format,
            long frameSequence,
            long sceneRevision,
            AlphaEncoding alphaEncoding
    ) {
        Objects.requireNonNull(layers, "layers");
        ArrayList<FrameCompositionPlan.Layer> copy = new ArrayList<>(layers.size());
        for (FrameCompositionPlan.Layer layer : layers) {
            copy.add(Objects.requireNonNull(layer, "layer"));
        }
        if (copy.isEmpty()) throw new IllegalArgumentException("composition requires at least one layer");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("composition extent must be positive");
        if (frameSequence < 0L || sceneRevision < 0L) {
            throw new IllegalArgumentException("composition sequence and scene revision must not be negative");
        }
        this.layers = List.copyOf(copy);
        this.width = width;
        this.height = height;
        this.format = Objects.requireNonNull(format, "format");
        this.frameSequence = frameSequence;
        this.sceneRevision = sceneRevision;
        this.alphaEncoding = Objects.requireNonNull(alphaEncoding, "alphaEncoding");
        if (copy.get(0).operation() != FrameCompositionPlan.Operation.REPLACE) {
            throw new IllegalArgumentException("the first composition layer must use REPLACE");
        }
    }

    /** @return ordered back-to-front source mutations */
    public List<FrameCompositionPlan.Layer> layers() { return layers; }

    /** @return requested output width */
    public int width() { return width; }

    /** @return requested output height */
    public int height() { return height; }

    /** @return exact output encoding requested from the provider */
    public FrameOutputFormat format() { return format; }

    /** @return caller-owned monotonic output frame sequence */
    public long frameSequence() { return frameSequence; }

    /** @return scene revision associated with the output */
    public long sceneRevision() { return sceneRevision; }

    /** @return alpha representation consumed by ALPHA_OVER layers */
    public AlphaEncoding alphaEncoding() { return alphaEncoding; }

    /** Portable alpha representation; the Vulkan provider currently executes premultiplied alpha. */
    public enum AlphaEncoding {
        PREMULTIPLIED
    }
}
