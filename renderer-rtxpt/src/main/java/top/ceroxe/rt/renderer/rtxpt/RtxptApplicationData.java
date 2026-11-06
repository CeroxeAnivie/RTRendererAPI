package top.ceroxe.rt.renderer.rtxpt;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Objects;

/**
 * Owner-thread application storage and bounded pending uploads. No caller buffer or patch is retained.
 * The writer must not reenter this object while an upload is in progress.
 */
final class RtxptApplicationData {
    private static final int MAXIMUM_BYTES = 16 * 1024 * 1024;
    private static final int WORD_BYTES = Integer.BYTES;
    private static final int MAXIMUM_PATCH_SPANS = 256;

    private byte[] shadow;
    private BitSet dirtyWords;
    private boolean fullPending;

    /** Replaces all storage only after validating and copying the complete input range. */
    void publish(ByteBuffer data) {
        Objects.requireNonNull(data, "data");
        int bytes = data.remaining();
        if ((bytes & (WORD_BYTES - 1)) != 0 || bytes > MAXIMUM_BYTES) {
            throw new IllegalArgumentException("Application data requires 4-byte alignment and at most 16 MiB");
        }
        byte[] next = new byte[bytes];
        BitSet nextDirty = new BitSet(bytes / WORD_BYTES);
        data.duplicate().get(next);
        shadow = next;
        dirtyWords = nextDirty;
        fullPending = true;
    }

    /** Applies an aligned range immediately; overlapping pending writes retain their final bytes. */
    void patch(int offset, ByteBuffer data) {
        Objects.requireNonNull(data, "data");
        if (shadow == null) throw new IllegalStateException("Publish application data before patching it");
        int bytes = data.remaining();
        if (offset < 0 || (offset & (WORD_BYTES - 1)) != 0 || bytes == 0
                || (bytes & (WORD_BYTES - 1)) != 0 || bytes > shadow.length
                || offset > shadow.length - bytes) {
            throw new IllegalArgumentException("Application patch must be a nonempty aligned range within publication capacity");
        }
        data.duplicate().get(shadow, offset, bytes);
        if (!fullPending) dirtyWords.set(offset / WORD_BYTES, (offset + bytes) / WORD_BYTES);
    }

    /**
     * Borrows the authoritative array only while its full upload is pending. The renderer owner
     * may pass it to synchronous JNI, but must neither mutate it nor retain the borrow afterwards.
     */
    byte[] fullPublication() {
        return fullPending ? shadow : null;
    }

    @FunctionalInterface
    interface PatchWriter {
        void write(long generation, int offset, byte[] data);
    }

    /**
     * Uploads exact contiguous dirty words, or one full range when fragmentation exceeds 256 spans.
     * A failed range and every later range stay pending. Ordinary small patches never expand.
     */
    void flushPatches(long generation, PatchWriter writer) {
        if (shadow == null || fullPending || dirtyWords.isEmpty()) return;
        if (generation <= 0) throw new IllegalArgumentException("Application generation must be positive");
        Objects.requireNonNull(writer, "writer");
        int spans = 0;
        for (int first = dirtyWords.nextSetBit(0); first >= 0; ) {
            if (++spans > MAXIMUM_PATCH_SPANS) {
                writer.write(generation, 0, shadow.clone());
                dirtyWords.clear();
                return;
            }
            first = dirtyWords.nextSetBit(dirtyWords.nextClearBit(first));
        }
        for (int first = dirtyWords.nextSetBit(0); first >= 0; ) {
            int end = dirtyWords.nextClearBit(first);
            int offset = first * WORD_BYTES;
            byte[] values = Arrays.copyOfRange(shadow, offset, end * WORD_BYTES);
            writer.write(generation, offset, values);
            dirtyWords.clear(first, end);
            first = dirtyWords.nextSetBit(end);
        }
    }

    /** Acknowledges successful synchronous upload of the borrowed full publication. */
    void publicationApplied() {
        fullPending = false;
        if (dirtyWords != null) dirtyWords.clear();
    }

    int capacity() {
        return shadow == null ? 0 : shadow.length;
    }

    /** Payload capacity only: authoritative bytes plus BitSet backing words, excluding JVM headers. */
    long retainedBytes() {
        return capacity() + (dirtyWords == null ? 0L : (long) dirtyWords.size() / Byte.SIZE);
    }

    void clear() {
        shadow = null;
        dirtyWords = null;
        fullPending = false;
    }
}
