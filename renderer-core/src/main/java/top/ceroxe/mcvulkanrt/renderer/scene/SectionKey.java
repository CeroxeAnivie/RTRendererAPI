package top.ceroxe.mcvulkanrt.renderer.scene;

/**
 * Renderer-owned section identity and packed representation.
 *
 * <p>The bit layout intentionally matches host's section-node wire/storage
 * representation (22 bits X, 20 bits Y, 22 bits Z).  Compatibility belongs at
 * the numeric contract, not at a dependency on {@code SectionPos}; the renderer
 * can therefore consume already-packed observations without loading host
 * classes.  Packing rejects out-of-range coordinates instead of silently
 * aliasing two renderer sections onto the same native ownership key.</p>
 */
public record SectionKey(int x, int y, int z) {
    private static final int HORIZONTAL_BITS = 22;
    private static final int VERTICAL_BITS = 20;
    private static final int Z_SHIFT = VERTICAL_BITS;
    private static final int X_SHIFT = VERTICAL_BITS + HORIZONTAL_BITS;
    private static final long HORIZONTAL_MASK = (1L << HORIZONTAL_BITS) - 1L;
    private static final long VERTICAL_MASK = (1L << VERTICAL_BITS) - 1L;
    public static final int MIN_HORIZONTAL_COORDINATE = -(1 << (HORIZONTAL_BITS - 1));
    public static final int MAX_HORIZONTAL_COORDINATE = (1 << (HORIZONTAL_BITS - 1)) - 1;
    public static final int MIN_VERTICAL_COORDINATE = -(1 << (VERTICAL_BITS - 1));
    public static final int MAX_VERTICAL_COORDINATE = (1 << (VERTICAL_BITS - 1)) - 1;

    public ChunkKey chunkKey() {
        return new ChunkKey(x, z);
    }

    public long packed() {
        return pack(x, y, z);
    }

    public static long pack(int x, int y, int z) {
        requireRange(x, MIN_HORIZONTAL_COORDINATE, MAX_HORIZONTAL_COORDINATE, "x");
        requireRange(y, MIN_VERTICAL_COORDINATE, MAX_VERTICAL_COORDINATE, "y");
        requireRange(z, MIN_HORIZONTAL_COORDINATE, MAX_HORIZONTAL_COORDINATE, "z");
        return ((long) x & HORIZONTAL_MASK) << X_SHIFT
                | ((long) z & HORIZONTAL_MASK) << Z_SHIFT
                | ((long) y & VERTICAL_MASK);
    }

    public static SectionKey fromPacked(long packed) {
        return new SectionKey(unpackX(packed), unpackY(packed), unpackZ(packed));
    }

    public static int unpackX(long packed) {
        return (int) (packed << 0 >> X_SHIFT);
    }

    public static int unpackY(long packed) {
        return (int) (packed << (Long.SIZE - VERTICAL_BITS) >> (Long.SIZE - VERTICAL_BITS));
    }

    public static int unpackZ(long packed) {
        return (int) (packed << HORIZONTAL_BITS >> X_SHIFT);
    }

    private static void requireRange(int value, int minimum, int maximum, String component) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    "section " + component + " coordinate is outside packed range: " + value
            );
        }
    }
}
