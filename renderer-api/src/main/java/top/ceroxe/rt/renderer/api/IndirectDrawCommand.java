package top.ceroxe.rt.renderer.api;

import java.util.Objects;
import java.util.Optional;

/**
 * Standard-layout indirect direct or indexed draw batch.
 *
 * <p>Without a count slice, {@code maximumDrawCount} is the exact draw count. With a count slice,
 * the backend reads one unsigned 32-bit count and clamps it to {@code maximumDrawCount}. Direct
 * argument records contain four 32-bit words; indexed records contain five. Arguments begin at
 * the supplied slice offset and consecutive records use {@code strideBytes}.</p>
 *
 * @param kind exact direct or indexed argument layout
 * @param arguments exact indirect argument-buffer slice
 * @param maximumDrawCount non-negative exact or clamped maximum draw count
 * @param strideBytes four-byte-aligned record stride, no smaller than the selected record
 * @param count optional four-byte-aligned indirect count-buffer slice
 */
public record IndirectDrawCommand(
        Kind kind,
        ResourceSlice.BufferSlice arguments,
        int maximumDrawCount,
        int strideBytes,
        Optional<ResourceSlice.BufferSlice> count
) implements RenderCommand {
    /** Standard portable indirect record layouts. */
    public enum Kind {
        DIRECT(16, false),
        INDEXED(20, true);

        private final int recordByteSize;
        private final boolean indexed;

        Kind(int recordByteSize, boolean indexed) {
            this.recordByteSize = recordByteSize;
            this.indexed = indexed;
        }

        public int recordByteSize() { return recordByteSize; }

        public boolean indexed() { return indexed; }
    }

    /** Validates usage, alignment, stride, maximum count, and every potentially read byte. */
    public IndirectDrawCommand {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(arguments, "arguments");
        count = Objects.requireNonNull(count, "count");
        if (maximumDrawCount < 0) throw new IllegalArgumentException("maximum indirect draw count must not be negative");
        if (strideBytes < kind.recordByteSize() || (strideBytes & 3) != 0) {
            throw new IllegalArgumentException("indirect stride must contain one record and be four-byte aligned");
        }
        requireIndirectUsage(arguments, "argument");
        if ((arguments.range().offsetBytes() & 3L) != 0L) {
            throw new IllegalArgumentException("indirect argument offset must be four-byte aligned");
        }
        long requiredBytes = requiredArgumentBytes(maximumDrawCount, strideBytes, kind.recordByteSize());
        if (requiredBytes > arguments.range().lengthBytes()) {
            throw new IllegalArgumentException("indirect argument slice is smaller than its maximum draw range");
        }
        if (count.isPresent()) {
            ResourceSlice.BufferSlice countSlice = count.orElseThrow();
            requireIndirectUsage(countSlice, "count");
            if ((countSlice.range().offsetBytes() & 3L) != 0L || countSlice.range().lengthBytes() < Integer.BYTES) {
                throw new IllegalArgumentException("indirect count slice must expose one aligned 32-bit value");
            }
            if (sameGeneration(arguments, countSlice) && overlaps(arguments.range(), countSlice.range())) {
                throw new IllegalArgumentException("indirect count and argument ranges must not overlap");
            }
        }
    }

    /** Creates a fixed-count indirect command. */
    public static IndirectDrawCommand fixed(
            Kind kind,
            ResourceSlice.BufferSlice arguments,
            int drawCount,
            int strideBytes
    ) {
        return new IndirectDrawCommand(kind, arguments, drawCount, strideBytes, Optional.empty());
    }

    /** Creates a count-buffer-clamped indirect command. */
    public static IndirectDrawCommand counted(
            Kind kind,
            ResourceSlice.BufferSlice arguments,
            int maximumDrawCount,
            int strideBytes,
            ResourceSlice.BufferSlice count
    ) {
        return new IndirectDrawCommand(kind, arguments, maximumDrawCount, strideBytes, Optional.of(
                Objects.requireNonNull(count, "count")
        ));
    }

    private static long requiredArgumentBytes(int count, int stride, int recordSize) {
        if (count == 0) return 0L;
        try {
            return Math.addExact(Math.multiplyExact((long) count - 1L, (long) stride), recordSize);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("indirect argument range overflows long", overflow);
        }
    }

    private static void requireIndirectUsage(ResourceSlice.BufferSlice slice, String role) {
        if (!slice.resource().usage().contains(BufferUsage.INDIRECT)) {
            throw new IllegalArgumentException("indirect " + role + " buffer does not declare INDIRECT usage");
        }
    }

    private static boolean sameGeneration(ResourceSlice.BufferSlice first, ResourceSlice.BufferSlice second) {
        return first.resource().id().equals(second.resource().id())
                && first.resource().version().equals(second.resource().version());
    }

    private static boolean overlaps(ByteRange first, ByteRange second) {
        return first.offsetBytes() < second.endExclusive() && second.offsetBytes() < first.endExclusive();
    }
}
