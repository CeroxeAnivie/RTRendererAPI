package top.ceroxe.rt.renderer.scene;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Immutable palette-packed storage for renderer-owned voxel attributes.
 *
 * <p>host freezes section state before asynchronous compilation. The RT
 * scene must retain the same immutable generation, but retaining one dense
 * 4096-element array for every material attribute scales source residency with
 * attribute count rather than information content. This storage keeps uniform
 * arrays as one value and palette-packs non-uniform arrays only when the packed
 * representation is smaller. Lookups remain O(1), so worker meshing does not
 * trade heap pressure for an RLE scan on every voxel field access.</p>
 */
final class VoxelPaletteStorage {
    private static final int MAX_LOGICAL_LENGTH = SectionVoxelSnapshot.BLOCKS_PER_SECTION;
    private static final int INT_MAGIC = 0x5650_4931;
    private static final int INT_HEADER_WORDS = 4;
    private static final byte BYTE_MAGIC_0 = (byte) 0xD7;
    private static final byte BYTE_MAGIC_1 = 0x51;
    private static final int BYTE_HEADER_BYTES = 7;
    private static final int HASH_CAPACITY = 1 << 13;
    private static final int HASH_MASK = HASH_CAPACITY - 1;
    private static final ThreadLocal<IntPaletteScratch> INT_SCRATCH =
            ThreadLocal.withInitial(IntPaletteScratch::new);
    private static final ImmutablePageArena PAGE_ARENA = new ImmutablePageArena(1 << 11);

    private VoxelPaletteStorage() {
    }

    static IntPage copyIntPage(int[] source, int logicalLength, String name) {
        int[] storage = freezeInts(source, logicalLength, name);
        return PAGE_ARENA.publishInt(new IntPage(logicalLength, hashInts(source, logicalLength), storage));
    }

    static BytePage copyBytePage(byte[] source, int logicalLength, String name) {
        byte[] storage = freezeBytes(source, logicalLength, name);
        return PAGE_ARENA.publishByte(new BytePage(logicalLength, hashBytes(source, logicalLength), storage));
    }

    static int[] freezeInts(int[] source, int logicalLength, String name) {
        Objects.requireNonNull(source, name);
        validateLogicalLength(logicalLength);
        if (source.length == 1) {
            return Arrays.copyOf(source, 1);
        }
        if (source.length == logicalLength) {
            return compressInts(source, logicalLength);
        }
        if (!isPackedInts(source, logicalLength)) {
            throw new IllegalArgumentException(name + " length must be 1 or " + logicalLength
                    + ", got " + source.length);
        }
        return Arrays.copyOf(source, source.length);
    }

    static byte[] freezeBytes(byte[] source, int logicalLength, String name) {
        Objects.requireNonNull(source, name);
        validateLogicalLength(logicalLength);
        if (source.length == 1) {
            return Arrays.copyOf(source, 1);
        }
        if (source.length == logicalLength) {
            return compressBytes(source, logicalLength);
        }
        if (!isPackedBytes(source, logicalLength)) {
            throw new IllegalArgumentException(name + " length must be 1 or " + logicalLength
                    + ", got " + source.length);
        }
        return Arrays.copyOf(source, source.length);
    }

    static int intAt(int[] storage, int logicalLength, int index) {
        checkIndex(index, logicalLength);
        if (storage.length == 1) {
            return storage[0];
        }
        if (storage.length == logicalLength) {
            return storage[index];
        }
        requirePackedInts(storage, logicalLength);
        int paletteSize = storage[2];
        int bits = storage[3];
        int bitOffset = index * bits;
        int wordIndex = INT_HEADER_WORDS + paletteSize + (bitOffset >>> 5);
        int shift = bitOffset & 31;
        long packed = Integer.toUnsignedLong(storage[wordIndex]) >>> shift;
        if (shift + bits > Integer.SIZE) {
            packed |= Integer.toUnsignedLong(storage[wordIndex + 1]) << (Integer.SIZE - shift);
        }
        int paletteIndex = (int) (packed & bitMask(bits));
        if (paletteIndex >= paletteSize) {
            throw new IllegalStateException("packed voxel palette index is corrupt: " + paletteIndex);
        }
        return storage[INT_HEADER_WORDS + paletteIndex];
    }

    static byte byteAt(byte[] storage, int logicalLength, int index) {
        checkIndex(index, logicalLength);
        if (storage.length == 1) {
            return storage[0];
        }
        if (storage.length == logicalLength) {
            return storage[index];
        }
        requirePackedBytes(storage, logicalLength);
        int paletteSize = unsignedShort(storage, 4);
        int bits = Byte.toUnsignedInt(storage[6]);
        int bitOffset = index * bits;
        int byteIndex = BYTE_HEADER_BYTES + paletteSize + (bitOffset >>> 3);
        int shift = bitOffset & 7;
        int packed = Byte.toUnsignedInt(storage[byteIndex]) >>> shift;
        if (shift + bits > Byte.SIZE) {
            packed |= Byte.toUnsignedInt(storage[byteIndex + 1]) << (Byte.SIZE - shift);
        }
        int paletteIndex = packed & (int) bitMask(bits);
        if (paletteIndex >= paletteSize) {
            throw new IllegalStateException("packed byte palette index is corrupt: " + paletteIndex);
        }
        return storage[BYTE_HEADER_BYTES + paletteIndex];
    }

    static int[] expandInts(int[] storage, int logicalLength) {
        int[] expanded = new int[logicalLength];
        if (storage.length == 1) {
            Arrays.fill(expanded, storage[0]);
            return expanded;
        }
        if (storage.length == logicalLength) {
            return Arrays.copyOf(storage, storage.length);
        }
        for (int index = 0; index < logicalLength; index++) {
            expanded[index] = intAt(storage, logicalLength, index);
        }
        return expanded;
    }

    static byte[] expandBytes(byte[] storage, int logicalLength) {
        byte[] expanded = new byte[logicalLength];
        if (storage.length == 1) {
            Arrays.fill(expanded, storage[0]);
            return expanded;
        }
        if (storage.length == logicalLength) {
            return Arrays.copyOf(storage, storage.length);
        }
        for (int index = 0; index < logicalLength; index++) {
            expanded[index] = byteAt(storage, logicalLength, index);
        }
        return expanded;
    }

    static boolean logicalIntsEqual(int[] left, int[] right, int logicalLength) {
        if (left == right) {
            return true;
        }
        if (left.length == 1 && right.length == 1) {
            return left[0] == right[0];
        }
        for (int index = 0; index < logicalLength; index++) {
            if (intAt(left, logicalLength, index) != intAt(right, logicalLength, index)) {
                return false;
            }
        }
        return true;
    }

    static boolean logicalBytesEqual(byte[] left, byte[] right, int logicalLength) {
        if (left == right) {
            return true;
        }
        if (left.length == 1 && right.length == 1) {
            return left[0] == right[0];
        }
        for (int index = 0; index < logicalLength; index++) {
            if (byteAt(left, logicalLength, index) != byteAt(right, logicalLength, index)) {
                return false;
            }
        }
        return true;
    }

    private static int hashInts(int[] storage, int logicalLength) {
        int hash = 0x811c9dc5;
        for (int index = 0; index < logicalLength; index++) {
            hash = (hash ^ intAt(storage, logicalLength, index)) * 0x01000193;
        }
        return hash;
    }

    private static int hashInts(IntPageBuilder builder) {
        int hash = 0x811c9dc5;
        for (int index = 0; index < builder.logicalLength; index++) {
            hash = (hash ^ builder.valueAt(index)) * 0x01000193;
        }
        return hash;
    }

    private static int hashBytes(byte[] storage, int logicalLength) {
        int hash = 0x811c9dc5;
        for (int index = 0; index < logicalLength; index++) {
            hash = (hash ^ Byte.toUnsignedInt(byteAt(storage, logicalLength, index))) * 0x01000193;
        }
        return hash;
    }

    private static int hashBytes(BytePageBuilder builder) {
        int hash = 0x811c9dc5;
        for (int index = 0; index < builder.logicalLength; index++) {
            hash = (hash ^ builder.valueAt(index)) * 0x01000193;
        }
        return hash;
    }

    private static int[] compressInts(int[] source, int logicalLength) {
        IntPaletteScratch scratch = INT_SCRATCH.get();
        int generation = scratch.nextGeneration();
        int paletteSize = 0;
        for (int value : source) {
            int slot = scratch.findSlot(value, generation);
            if (scratch.stamps[slot] == generation) {
                continue;
            }
            scratch.stamps[slot] = generation;
            scratch.keys[slot] = value;
            scratch.indices[slot] = paletteSize;
            scratch.palette[paletteSize++] = value;
        }
        if (paletteSize == 1) {
            return new int[]{scratch.palette[0]};
        }
        int bits = bitsForPalette(paletteSize);
        int packedWords = Math.toIntExact(((long) logicalLength * bits + 31L) >>> 5);
        int packedLength = INT_HEADER_WORDS + paletteSize + packedWords;
        if (packedLength >= logicalLength) {
            return Arrays.copyOf(source, source.length);
        }
        int[] packed = new int[packedLength];
        packed[0] = INT_MAGIC;
        packed[1] = logicalLength;
        packed[2] = paletteSize;
        packed[3] = bits;
        System.arraycopy(scratch.palette, 0, packed, INT_HEADER_WORDS, paletteSize);
        int payloadOffset = INT_HEADER_WORDS + paletteSize;
        for (int index = 0; index < source.length; index++) {
            int paletteIndex = scratch.indexOf(source[index], generation);
            writePackedInt(packed, payloadOffset, index * bits, bits, paletteIndex);
        }
        return packed;
    }

    private static byte[] compressBytes(byte[] source, int logicalLength) {
        byte first = source[0];
        boolean uniform = true;
        for (int index = 1; index < source.length; index++) {
            if (source[index] != first) {
                uniform = false;
                break;
            }
        }
        if (uniform) {
            return new byte[]{first};
        }
        int[] indicesByValue = new int[256];
        Arrays.fill(indicesByValue, -1);
        byte[] palette = new byte[256];
        int paletteSize = 0;
        for (byte value : source) {
            int unsigned = Byte.toUnsignedInt(value);
            if (indicesByValue[unsigned] >= 0) {
                continue;
            }
            indicesByValue[unsigned] = paletteSize;
            palette[paletteSize++] = value;
        }
        if (paletteSize == 1) {
            return new byte[]{palette[0]};
        }
        int bits = bitsForPalette(paletteSize);
        int payloadBytes = Math.toIntExact(((long) logicalLength * bits + 7L) >>> 3);
        int packedLength = BYTE_HEADER_BYTES + paletteSize + payloadBytes;
        if (packedLength >= logicalLength) {
            return Arrays.copyOf(source, source.length);
        }
        byte[] packed = new byte[packedLength];
        packed[0] = BYTE_MAGIC_0;
        packed[1] = BYTE_MAGIC_1;
        writeUnsignedShort(packed, 2, logicalLength);
        writeUnsignedShort(packed, 4, paletteSize);
        packed[6] = (byte) bits;
        System.arraycopy(palette, 0, packed, BYTE_HEADER_BYTES, paletteSize);
        int payloadOffset = BYTE_HEADER_BYTES + paletteSize;
        for (int index = 0; index < source.length; index++) {
            int paletteIndex = indicesByValue[Byte.toUnsignedInt(source[index])];
            writePackedByte(packed, payloadOffset, index * bits, paletteIndex);
        }
        return packed;
    }

    private static void writePackedInt(int[] target, int payloadOffset, int bitOffset, int bits, int value) {
        int word = payloadOffset + (bitOffset >>> 5);
        int shift = bitOffset & 31;
        target[word] |= value << shift;
        if (shift + bits > Integer.SIZE) {
            target[word + 1] |= value >>> (Integer.SIZE - shift);
        }
    }

    private static void writePackedByte(byte[] target, int payloadOffset, int bitOffset, int value) {
        int index = payloadOffset + (bitOffset >>> 3);
        int shift = bitOffset & 7;
        target[index] = (byte) (target[index] | value << shift);
        if (shift != 0 && (value >>> (Byte.SIZE - shift)) != 0) {
            target[index + 1] = (byte) (target[index + 1] | value >>> (Byte.SIZE - shift));
        }
    }

    private static boolean isPackedInts(int[] storage, int logicalLength) {
        if (storage.length < INT_HEADER_WORDS || storage[0] != INT_MAGIC || storage[1] != logicalLength) {
            return false;
        }
        int paletteSize = storage[2];
        int bits = storage[3];
        if (paletteSize < 2 || paletteSize > logicalLength || bits != bitsForPalette(paletteSize)) {
            return false;
        }
        long expected = (long) INT_HEADER_WORDS + paletteSize + (((long) logicalLength * bits + 31L) >>> 5);
        return expected == storage.length && storage.length < logicalLength;
    }

    private static boolean isPackedBytes(byte[] storage, int logicalLength) {
        if (storage.length < BYTE_HEADER_BYTES || storage[0] != BYTE_MAGIC_0 || storage[1] != BYTE_MAGIC_1
                || unsignedShort(storage, 2) != logicalLength) {
            return false;
        }
        int paletteSize = unsignedShort(storage, 4);
        int bits = Byte.toUnsignedInt(storage[6]);
        if (paletteSize < 2 || paletteSize > 256 || bits != bitsForPalette(paletteSize)) {
            return false;
        }
        long expected = (long) BYTE_HEADER_BYTES + paletteSize + (((long) logicalLength * bits + 7L) >>> 3);
        return expected == storage.length && storage.length < logicalLength;
    }

    private static void requirePackedInts(int[] storage, int logicalLength) {
        if (storage.length < INT_HEADER_WORDS || storage[0] != INT_MAGIC || storage[1] != logicalLength) {
            throw new IllegalStateException("invalid packed integer voxel storage");
        }
    }

    private static void requirePackedBytes(byte[] storage, int logicalLength) {
        if (storage.length < BYTE_HEADER_BYTES || storage[0] != BYTE_MAGIC_0 || storage[1] != BYTE_MAGIC_1
                || unsignedShort(storage, 2) != logicalLength) {
            throw new IllegalStateException("invalid packed byte voxel storage");
        }
    }

    private static int bitsForPalette(int paletteSize) {
        return Math.max(1, Integer.SIZE - Integer.numberOfLeadingZeros(paletteSize - 1));
    }

    private static long bitMask(int bits) {
        return (1L << bits) - 1L;
    }

    private static void writeUnsignedShort(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 8);
        target[offset + 1] = (byte) value;
    }

    private static int unsignedShort(byte[] source, int offset) {
        return Byte.toUnsignedInt(source[offset]) << 8 | Byte.toUnsignedInt(source[offset + 1]);
    }

    private static void validateLogicalLength(int logicalLength) {
        if (logicalLength <= 1 || logicalLength > MAX_LOGICAL_LENGTH) {
            throw new IllegalArgumentException("voxel storage logical length outside supported range: "
                    + logicalLength);
        }
    }

    private static void checkIndex(int index, int logicalLength) {
        if (index < 0 || index >= logicalLength) {
            throw new IndexOutOfBoundsException("voxel storage index outside logical length: " + index);
        }
    }

    private static void requireMutableIndex(int index, boolean built, int logicalLength) {
        if (built) {
            throw new IllegalStateException("voxel page builder is already published");
        }
        checkIndex(index, logicalLength);
    }

    private static int mix(int value) {
        value ^= value >>> 16;
        value *= 0x7feb352d;
        value ^= value >>> 15;
        value *= 0x846ca68b;
        return value ^ value >>> 16;
    }

    /**
     * Strongly owned immutable integer publication. The backing array never crosses this
     * ownership boundary, which makes arena sharing safe even when a producer resets or fails.
     */
    static final class IntPage {
        private final int logicalLength;
        private final int contentHash;
        private final int[] storage;

        private IntPage(int logicalLength, int contentHash, int[] storage) {
            this.logicalLength = logicalLength;
            this.contentHash = contentHash;
            this.storage = storage;
        }

        int get(int index) {
            return intAt(storage, logicalLength, index);
        }

        int retainedElements() {
            return storage.length;
        }

        boolean hasSameContent(IntPage other) {
            return this == other || other != null
                    && logicalLength == other.logicalLength
                    && contentHash == other.contentHash
                    && logicalIntsEqual(storage, other.storage, logicalLength);
        }
    }

    /**
     * Immutable byte counterpart to {@link IntPage}.
     */
    static final class BytePage {
        private final int logicalLength;
        private final int contentHash;
        private final byte[] storage;

        private BytePage(int logicalLength, int contentHash, byte[] storage) {
            this.logicalLength = logicalLength;
            this.contentHash = contentHash;
            this.storage = storage;
        }

        byte get(int index) {
            return byteAt(storage, logicalLength, index);
        }

        int retainedElements() {
            return storage.length;
        }

        boolean hasSameContent(BytePage other) {
            return this == other || other != null
                    && logicalLength == other.logicalLength
                    && contentHash == other.contentHash
                    && logicalBytesEqual(storage, other.storage, logicalLength);
        }
    }

    /**
     * Builds one immutable integer page without first allocating a dense
     * {@code int[logicalLength]}. Boundary capture is dominated by default
     * values (air/no-fluid/no-light); allocating every dense lane before
     * discovering that it palette-packs to one value made source ingestion a
     * GC workload. This builder keeps palette indices in 16 bits and emits the
     * final packed representation directly.
     */
    static final class IntPageBuilder {
        private final int logicalLength;
        private int[] palette = new int[8];
        private short[] paletteIndices;
        private int paletteSize = 1;
        private boolean hasNonZeroValue;
        private boolean built;

        IntPageBuilder(int logicalLength) {
            validateLogicalLength(logicalLength);
            this.logicalLength = logicalLength;
        }

        void set(int index, int value) {
            requireMutableIndex(index, built, logicalLength);
            if (!hasNonZeroValue && value == 0) {
                return;
            }
            if (paletteIndices == null) {
                paletteIndices = new short[logicalLength];
            }
            hasNonZeroValue |= value != 0;
            int paletteIndex = paletteIndex(value);
            paletteIndices[index] = (short) paletteIndex;
        }

        IntPage build() {
            if (built) {
                throw new IllegalStateException("integer page builder already published");
            }
            built = true;
            int contentHash = hashInts(this);
            IntPage reusable = PAGE_ARENA.findInt(this, contentHash);
            if (reusable != null) {
                return reusable;
            }
            if (!hasNonZeroValue) {
                return PAGE_ARENA.publishInt(new IntPage(logicalLength, contentHash, new int[]{0}));
            }
            int bits = bitsForPalette(paletteSize);
            int packedWords = Math.toIntExact(((long) logicalLength * bits + 31L) >>> 5);
            int packedLength = INT_HEADER_WORDS + paletteSize + packedWords;
            if (packedLength >= logicalLength) {
                int[] dense = new int[logicalLength];
                for (int index = 0; index < logicalLength; index++) {
                    dense[index] = valueAt(index);
                }
                return PAGE_ARENA.publishInt(new IntPage(logicalLength, contentHash, dense));
            }
            int[] packed = new int[packedLength];
            packed[0] = INT_MAGIC;
            packed[1] = logicalLength;
            packed[2] = paletteSize;
            packed[3] = bits;
            System.arraycopy(palette, 0, packed, INT_HEADER_WORDS, paletteSize);
            int payloadOffset = INT_HEADER_WORDS + paletteSize;
            for (int index = 0; index < logicalLength; index++) {
                writePackedInt(
                        packed,
                        payloadOffset,
                        index * bits,
                        bits,
                        Short.toUnsignedInt(paletteIndices[index])
                );
            }
            return PAGE_ARENA.publishInt(new IntPage(logicalLength, contentHash, packed));
        }

        /**
         * Rewinds producer-owned staging without retaining any mutable publication storage.
         * Immutable pages remain arena-owned and may be reused by a later equal generation.
         */
        void reset() {
            if (paletteIndices != null) {
                Arrays.fill(paletteIndices, (short) 0);
            }
            palette[0] = 0;
            paletteSize = 1;
            hasNonZeroValue = false;
            built = false;
        }

        private int paletteIndex(int value) {
            for (int index = 0; index < paletteSize; index++) {
                if (palette[index] == value) {
                    return index;
                }
            }
            if (paletteSize == Short.MAX_VALUE + 1) {
                throw new IllegalStateException("integer page palette exceeds unsigned-short index range");
            }
            if (paletteSize == palette.length) {
                /*
                 * Index zero represents unwritten/default voxels while capture is in progress.
                 * A fully written page containing logicalLength distinct non-zero values can
                 * therefore need one extra transient palette entry before it publishes dense.
                 */
                palette = Arrays.copyOf(palette, Math.min(logicalLength + 1, palette.length << 1));
            }
            palette[paletteSize] = value;
            return paletteSize++;
        }

        private int valueAt(int index) {
            return paletteIndices == null ? 0 : palette[Short.toUnsignedInt(paletteIndices[index])];
        }
    }

    /**
     * Byte lanes remain dense once they become non-uniform, but the backing
     * array is allocated lazily. Uniform zero lanes therefore publish as a
     * singleton without ever allocating the full boundary page.
     */
    static final class BytePageBuilder {
        private final int logicalLength;
        private byte[] values;
        private boolean built;

        BytePageBuilder(int logicalLength) {
            validateLogicalLength(logicalLength);
            this.logicalLength = logicalLength;
        }

        void set(int index, byte value) {
            requireMutableIndex(index, built, logicalLength);
            if (values == null && value == 0) {
                return;
            }
            if (values == null) {
                values = new byte[logicalLength];
            }
            values[index] = value;
        }

        BytePage build() {
            if (built) {
                throw new IllegalStateException("byte page builder already published");
            }
            built = true;
            int contentHash = hashBytes(this);
            BytePage reusable = PAGE_ARENA.findByte(this, contentHash);
            if (reusable != null) {
                return reusable;
            }
            byte[] storage = values == null ? new byte[]{0} : compressBytes(values, logicalLength);
            return PAGE_ARENA.publishByte(new BytePage(logicalLength, contentHash, storage));
        }

        void reset() {
            if (values != null) {
                Arrays.fill(values, (byte) 0);
            }
            built = false;
        }

        private int valueAt(int index) {
            return values == null ? 0 : Byte.toUnsignedInt(values[index]);
        }
    }

    private static final class IntPaletteScratch {
        private final int[] keys = new int[HASH_CAPACITY];
        private final int[] indices = new int[HASH_CAPACITY];
        private final int[] stamps = new int[HASH_CAPACITY];
        private final int[] palette = new int[MAX_LOGICAL_LENGTH];
        private int generation;

        private int nextGeneration() {
            generation++;
            if (generation == 0) {
                Arrays.fill(stamps, 0);
                generation = 1;
            }
            return generation;
        }

        private int findSlot(int value, int activeGeneration) {
            int slot = mix(value) & HASH_MASK;
            while (stamps[slot] == activeGeneration && keys[slot] != value) {
                slot = (slot + 1) & HASH_MASK;
            }
            return slot;
        }

        private int indexOf(int value, int activeGeneration) {
            int slot = findSlot(value, activeGeneration);
            if (stamps[slot] != activeGeneration) {
                throw new IllegalStateException("voxel palette lost a value during packing");
            }
            return indices[slot];
        }
    }

    /**
     * Bounded scatter-style publication cache. Slots retain immutable pages rather than
     * mutable builders, so cache hits share identity across capture threads while collisions
     * merely evict old reuse opportunities. Full logical comparison makes hashes an indexing
     * aid, never a correctness assumption.
     */
    private static final class ImmutablePageArena {
        private final AtomicReferenceArray<IntPage> ints;
        private final AtomicReferenceArray<BytePage> bytes;
        private final int mask;

        private ImmutablePageArena(int capacity) {
            if (Integer.bitCount(capacity) != 1) {
                throw new IllegalArgumentException("page arena capacity must be a power of two");
            }
            ints = new AtomicReferenceArray<>(capacity);
            bytes = new AtomicReferenceArray<>(capacity);
            mask = capacity - 1;
        }

        private IntPage findInt(IntPageBuilder builder, int contentHash) {
            IntPage candidate = ints.get(slot(builder.logicalLength, contentHash));
            if (candidate == null || candidate.logicalLength != builder.logicalLength
                    || candidate.contentHash != contentHash) {
                return null;
            }
            for (int index = 0; index < builder.logicalLength; index++) {
                if (candidate.get(index) != builder.valueAt(index)) {
                    return null;
                }
            }
            return candidate;
        }

        private BytePage findByte(BytePageBuilder builder, int contentHash) {
            BytePage candidate = bytes.get(slot(builder.logicalLength, contentHash));
            if (candidate == null || candidate.logicalLength != builder.logicalLength
                    || candidate.contentHash != contentHash) {
                return null;
            }
            for (int index = 0; index < builder.logicalLength; index++) {
                if (Byte.toUnsignedInt(candidate.get(index)) != builder.valueAt(index)) {
                    return null;
                }
            }
            return candidate;
        }

        private IntPage publishInt(IntPage page) {
            int slot = slot(page.logicalLength, page.contentHash);
            for (; ; ) {
                IntPage current = ints.get(slot);
                if (current != null && current.hasSameContent(page)) {
                    return current;
                }
                if (ints.compareAndSet(slot, current, page)) {
                    return page;
                }
            }
        }

        private BytePage publishByte(BytePage page) {
            int slot = slot(page.logicalLength, page.contentHash);
            for (; ; ) {
                BytePage current = bytes.get(slot);
                if (current != null && current.hasSameContent(page)) {
                    return current;
                }
                if (bytes.compareAndSet(slot, current, page)) {
                    return page;
                }
            }
        }

        private int slot(int logicalLength, int contentHash) {
            return mix(contentHash ^ logicalLength * 0x9e3779b9) & mask;
        }
    }
}
