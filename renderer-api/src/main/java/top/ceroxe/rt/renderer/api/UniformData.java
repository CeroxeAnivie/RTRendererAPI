package top.ceroxe.rt.renderer.api;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Immutable raw bytes with an explicit byte alignment for uniform or push-constant data.
 *
 * <p>No Java object serialization, host structure packing, or byte-order conversion is implied.</p>
 */
public final class UniformData {
    private final ByteBuffer bytes;
    private final int alignment;

    /**
     * Copies a complete byte range.
     *
     * @param bytes non-null remaining bytes
     * @param alignment positive power-of-two byte alignment
     */
    public UniformData(ByteBuffer bytes, int alignment) {
        if (alignment <= 0 || Integer.bitCount(alignment) != 1) {
            throw new IllegalArgumentException("uniform-data alignment must be a positive power of two");
        }
        ByteBuffer source = Objects.requireNonNull(bytes, "bytes").slice();
        ByteBuffer copy = ByteBuffer.allocateDirect(source.remaining()).order(bytes.order());
        copy.put(source).flip();
        this.bytes = copy.asReadOnlyBuffer().order(bytes.order());
        this.alignment = alignment;
    }

    /** @return independent read-only byte view positioned at zero */
    public ByteBuffer bytes() { return bytes.duplicate().order(bytes.order()); }

    /** @return exact byte length */
    public int byteSize() { return bytes.remaining(); }

    /** @return positive power-of-two byte alignment */
    public int alignment() { return alignment; }
}
