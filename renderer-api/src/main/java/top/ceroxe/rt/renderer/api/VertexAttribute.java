package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Immutable mapping from one vertex-buffer field to one shader input location.
 *
 * @param shaderLocation non-negative shader interface location
 * @param bufferBinding non-negative vertex-buffer binding
 * @param byteOffset non-negative byte offset within one element
 * @param format exact storage interpretation
 */
public record VertexAttribute(
        int shaderLocation,
        int bufferBinding,
        int byteOffset,
        VertexFormat format
) {
    /** Validates a portable vertex-attribute declaration. */
    public VertexAttribute {
        if (shaderLocation < 0) throw new IllegalArgumentException("shader location must be non-negative");
        if (bufferBinding < 0) throw new IllegalArgumentException("buffer binding must be non-negative");
        if (byteOffset < 0) throw new IllegalArgumentException("vertex attribute offset must be non-negative");
        format = Objects.requireNonNull(format, "format");
        if ((long) byteOffset + format.byteSize() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("vertex attribute byte range overflows the API limit");
        }
    }

    /** @return exclusive byte end within one buffer element */
    public int byteEndExclusive() { return byteOffset + format.byteSize(); }
}
