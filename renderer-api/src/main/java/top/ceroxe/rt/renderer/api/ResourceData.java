package top.ceroxe.rt.renderer.api;

import java.nio.ByteBuffer;
import java.util.Objects;

/** Immutable defensive copy of caller-owned resource bytes with explicit byte order. */
public final class ResourceData {
    private final ByteBuffer bytes;

    /** Copies remaining bytes and retains their declared byte order. */
    public ResourceData(ByteBuffer bytes) {
        ByteBuffer source = Objects.requireNonNull(bytes, "bytes").slice().order(bytes.order());
        ByteBuffer copy = ByteBuffer.allocateDirect(source.remaining()).order(source.order());
        copy.put(source).flip();
        this.bytes = copy.asReadOnlyBuffer().order(copy.order());
    }

    /** @return independent read-only view positioned at zero */
    public ByteBuffer bytes() { return bytes.duplicate().order(bytes.order()); }

    /** @return exact byte count */
    public int byteSize() { return bytes.remaining(); }

    /** @return byte order retained from the source buffer */
    public java.nio.ByteOrder byteOrder() { return bytes.order(); }
}
