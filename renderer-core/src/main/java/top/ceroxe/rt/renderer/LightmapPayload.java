package top.ceroxe.rt.renderer;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable renderer input for a two-axis, quantized lighting lookup table.
 *
 * <p>The embedding application owns how its frame lighting is evaluated. The
 * core owns only the GPU ABI: a stable 16 by 16 RGBA8 table sampled by packed
 * material light coordinates. This mirrors a GPUScene upload contract: source
 * policy is resolved before publication, and the render backend receives a
 * self-contained byte-equivalent payload with a revision identity.</p>
 */
public final class LightmapPayload {
    /**
     * Number of samples on each lookup-table axis.
     */
    public static final int AXIS_SIZE = 16;
    /**
     * Total number of RGBA8 entries in the square lookup table.
     */
    public static final int ENTRY_COUNT = AXIS_SIZE * AXIS_SIZE;
    /**
     * Number of four-entry records required by the GPU storage ABI.
     */
    public static final int PACKED_UVEC4_RECORDS = ENTRY_COUNT / 4;
    private static final LightmapPayload UNKNOWN = new LightmapPayload(0L, fullIntensityEntries(), true);
    private static final int FULL_INTENSITY_RGBA8 = 0xffff_ffff;
    private final long revision;
    private final int[] packedRgba8;

    /**
     * Creates a payload and defensively copies its packed RGBA8 entries.
     *
     * @param revision    nonnegative source revision; zero denotes an unknown revision
     * @param packedRgba8 exactly {@link #ENTRY_COUNT} row-major RGBA8 entries
     */
    public LightmapPayload(long revision, int[] packedRgba8) {
        this(revision, packedRgba8, false);
    }

    private LightmapPayload(long revision, int[] packedRgba8, boolean trustedOwnership) {
        if (revision < 0L) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        Objects.requireNonNull(packedRgba8, "packedRgba8");
        if (packedRgba8.length != ENTRY_COUNT) {
            throw new IllegalArgumentException("lightmap payload must contain " + ENTRY_COUNT + " RGBA8 entries");
        }
        this.revision = revision;
        this.packedRgba8 = trustedOwnership ? packedRgba8 : Arrays.copyOf(packedRgba8, packedRgba8.length);
    }

    /**
     * Returns the shared full-intensity payload used when no source lightmap is known.
     *
     * @return immutable unknown payload
     */
    public static LightmapPayload unknown() {
        return UNKNOWN;
    }

    /**
     * Converts two clamped coordinates to the row-major entry index.
     *
     * @param firstCoordinate  first lookup-table coordinate
     * @param secondCoordinate second lookup-table coordinate
     * @return index in the range {@code [0, ENTRY_COUNT)}
     */
    public static int index(int firstCoordinate, int secondCoordinate) {
        return clampCoordinate(firstCoordinate) * AXIS_SIZE + clampCoordinate(secondCoordinate);
    }

    private static int clampCoordinate(int value) {
        return Math.max(0, Math.min(AXIS_SIZE - 1, value));
    }

    private static int[] fullIntensityEntries() {
        int[] entries = new int[ENTRY_COUNT];
        Arrays.fill(entries, FULL_INTENSITY_RGBA8);
        return entries;
    }

    /**
     * Returns the source revision.
     *
     * @return nonnegative revision
     */
    public long revision() {
        return revision;
    }

    /**
     * Tests whether this payload has a positive source revision.
     *
     * @return {@code true} when the source revision is known
     */
    public boolean known() {
        return revision > 0L;
    }

    /**
     * Returns one packed RGBA8 entry after clamping both coordinates to the valid axis range.
     *
     * @param firstCoordinate  first lookup-table coordinate
     * @param secondCoordinate second lookup-table coordinate
     * @return packed RGBA8 entry
     */
    public int packedRgba8(int firstCoordinate, int secondCoordinate) {
        return packedRgba8[index(firstCoordinate, secondCoordinate)];
    }

    /**
     * Returns a defensive copy of all packed RGBA8 entries.
     *
     * @return row-major packed entries
     */
    public int[] packedRgba8() {
        return Arrays.copyOf(packedRgba8, packedRgba8.length);
    }

    /**
     * Streams the payload as consecutive four-integer records without exposing internal storage.
     *
     * @param sink destination invoked once per GPU record
     */
    public void writePackedUvec4Records(IntQuadSink sink) {
        Objects.requireNonNull(sink, "sink");
        for (int offset = 0; offset < packedRgba8.length; offset += 4) {
            sink.put(
                    packedRgba8[offset],
                    packedRgba8[offset + 1],
                    packedRgba8[offset + 2],
                    packedRgba8[offset + 3]
            );
        }
    }

    /**
     * Formats the payload identity for diagnostic logs.
     *
     * @return stable single-line log fragment
     */
    public String asLogFragment() {
        return "lightmapPayload{revision=" + revision + ", known=" + known() + ", entries=" + ENTRY_COUNT + "}";
    }

    @Override
    public boolean equals(Object other) {
        return other == this || (other instanceof LightmapPayload payload
                && revision == payload.revision
                && Arrays.equals(packedRgba8, payload.packedRgba8));
    }

    @Override
    public int hashCode() {
        return 31 * Long.hashCode(revision) + Arrays.hashCode(packedRgba8);
    }

    /**
     * Receives one four-integer record in GPU upload order.
     */
    @FunctionalInterface
    public interface IntQuadSink {
        /**
         * Accepts one packed record.
         *
         * @param x first component
         * @param y second component
         * @param z third component
         * @param w fourth component
         */
        void put(int x, int y, int z, int w);
    }
}
