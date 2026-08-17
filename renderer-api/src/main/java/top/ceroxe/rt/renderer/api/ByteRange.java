package top.ceroxe.rt.renderer.api;

/**
 * Immutable half-open byte range within a buffer resource.
 *
 * <p>Empty ranges are valid for explicit no-op copies and barriers. Bindings and draw commands
 * impose their own non-empty range requirements rather than overloading this value type with a
 * context-specific rule.</p>
 *
 * @param offsetBytes non-negative first byte offset
 * @param lengthBytes non-negative byte length
 */
public record ByteRange(long offsetBytes, long lengthBytes) {
    /**
     * Validates a range and its exclusive end without allowing signed overflow.
     */
    public ByteRange {
        if (offsetBytes < 0L || lengthBytes < 0L) {
            throw new IllegalArgumentException("byte range offset and length must not be negative");
        }
        try {
            Math.addExact(offsetBytes, lengthBytes);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("byte range end overflows long", overflow);
        }
    }

    /**
     * Returns the exclusive end offset.
     *
     * @return non-negative exclusive end offset
     */
    public long endExclusive() {
        return Math.addExact(offsetBytes, lengthBytes);
    }

    /**
     * Returns whether this range is completely contained by a buffer of the supplied size.
     *
     * @param byteSize non-negative buffer size
     * @return {@code true} when this range is contained
     */
    public boolean fitsWithin(long byteSize) {
        if (byteSize < 0L) {
            throw new IllegalArgumentException("buffer size must not be negative");
        }
        return endExclusive() <= byteSize;
    }
}
