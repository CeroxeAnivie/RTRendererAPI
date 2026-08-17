package top.ceroxe.rt.renderer.api.interop;

import java.util.Objects;
import top.ceroxe.rt.renderer.api.FrameOutputFormat;
import top.ceroxe.rt.renderer.api.RenderResourceId;
import top.ceroxe.rt.renderer.api.ResourceVersion;

/**
 * Backend-neutral meaning of one completed two-dimensional output image.
 *
 * <p>Native image creation flags, layouts, queue ownership, and import structures are deliberately
 * absent. An adapter for the negotiated handle type owns those backend-specific obligations.</p>
 *
 * @param resourceId stable renderer resource identity
 * @param resourceVersion exact storage generation backing the lease
 * @param frameSequence non-negative renderer frame sequence
 * @param width positive visible width in pixels
 * @param height positive visible height in pixels
 * @param format public color encoding
 * @param origin location of logical pixel (0,0)
 * @param alphaMode interpretation of alpha channels
 */
public record PortableFrameDescriptor(
        RenderResourceId resourceId,
        ResourceVersion resourceVersion,
        long frameSequence,
        int width,
        int height,
        FrameOutputFormat format,
        ImageOrigin origin,
        AlphaMode alphaMode
) {
    public PortableFrameDescriptor {
        resourceId = Objects.requireNonNull(resourceId, "resourceId");
        resourceVersion = Objects.requireNonNull(resourceVersion, "resourceVersion");
        if (frameSequence < 0L) throw new IllegalArgumentException("frameSequence must not be negative");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("frame extent must be positive");
        format = Objects.requireNonNull(format, "format");
        origin = Objects.requireNonNull(origin, "origin");
        alphaMode = Objects.requireNonNull(alphaMode, "alphaMode");
    }

    /** Logical location of pixel coordinate (0,0). */
    public enum ImageOrigin {
        TOP_LEFT,
        BOTTOM_LEFT
    }

    /** Meaning of the output alpha channel. */
    public enum AlphaMode {
        OPAQUE,
        STRAIGHT,
        PREMULTIPLIED
    }
}
