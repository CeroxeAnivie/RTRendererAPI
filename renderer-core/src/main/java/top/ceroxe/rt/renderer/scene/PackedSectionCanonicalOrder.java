package top.ceroxe.rt.renderer.scene;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.*;

/**
 * Persistent canonical x-slices; a camera delta replaces only affected spatial pages.
 */
final class PackedSectionCanonicalOrder extends AbstractList<SectionKey> implements RandomAccess {
    private final int[] sectionXs;
    private final SectionKey[][] slices;
    private final int[] offsets;
    private final int size;
    PackedSectionMembership owner;

    private PackedSectionCanonicalOrder(int[] sectionXs, SectionKey[][] slices) {
        this.sectionXs = sectionXs;
        this.slices = slices;
        this.offsets = new int[slices.length + 1];
        int total = 0;
        for (int index = 0; index < slices.length; index++) {
            offsets[index] = total;
            total += slices[index].length;
        }
        offsets[slices.length] = total;
        size = total;
    }

    static PackedSectionCanonicalOrder base(SectionKey[] keys) {
        if (keys.length == 0) {
            return new PackedSectionCanonicalOrder(new int[0], new SectionKey[0][]);
        }
        int sliceCount = 1;
        for (int index = 1; index < keys.length; index++) {
            if (keys[index].x() != keys[index - 1].x()) {
                sliceCount++;
            }
        }
        int[] xs = new int[sliceCount];
        SectionKey[][] slices = new SectionKey[sliceCount][];
        int start = 0;
        int slice = 0;
        while (start < keys.length) {
            int end = start + 1;
            while (end < keys.length && keys[end].x() == keys[start].x()) {
                end++;
            }
            xs[slice] = keys[start].x();
            slices[slice] = Arrays.copyOfRange(keys, start, end);
            slice++;
            start = end;
        }
        return new PackedSectionCanonicalOrder(xs, slices);
    }

    static PackedSectionCanonicalOrder delta(
            PackedSectionCanonicalOrder previous,
            long[] enteredPacked,
            LongOpenHashSet exited
    ) {
        LongOpenHashSet affectedXs = new LongOpenHashSet();
        for (long packed : enteredPacked) {
            affectedXs.add(SectionKey.unpackX(packed));
        }
        if (exited != null) {
            LongIterator iterator = exited.iterator();
            while (iterator.hasNext()) {
                affectedXs.add(SectionKey.unpackX(iterator.nextLong()));
            }
        }
        int[] enteredXs = new int[enteredPacked.length];
        int enteredXCount = 0;
        int lastX = Integer.MIN_VALUE;
        for (long packed : enteredPacked) {
            int x = SectionKey.unpackX(packed);
            if (enteredXCount == 0 || x != lastX) {
                enteredXs[enteredXCount++] = x;
                lastX = x;
            }
        }
        int[] candidateXs = new int[previous.sectionXs.length + enteredXCount];
        int previousX = 0;
        int enteredX = 0;
        int candidateCount = 0;
        while (previousX < previous.sectionXs.length || enteredX < enteredXCount) {
            if (previousX >= previous.sectionXs.length) {
                candidateXs[candidateCount++] = enteredXs[enteredX++];
            } else if (enteredX >= enteredXCount) {
                candidateXs[candidateCount++] = previous.sectionXs[previousX++];
            } else if (previous.sectionXs[previousX] < enteredXs[enteredX]) {
                candidateXs[candidateCount++] = previous.sectionXs[previousX++];
            } else if (previous.sectionXs[previousX] > enteredXs[enteredX]) {
                candidateXs[candidateCount++] = enteredXs[enteredX++];
            } else {
                candidateXs[candidateCount++] = previous.sectionXs[previousX++];
                enteredX++;
            }
        }
        int[] nextXs = new int[candidateCount];
        SectionKey[][] nextSlices = new SectionKey[candidateCount][];
        int nextCount = 0;
        int enteredStart = 0;
        for (int candidateIndex = 0; candidateIndex < candidateCount; candidateIndex++) {
            int x = candidateXs[candidateIndex];
            while (enteredStart < enteredPacked.length && SectionKey.unpackX(enteredPacked[enteredStart]) < x) {
                enteredStart++;
            }
            int enteredEnd = enteredStart;
            while (enteredEnd < enteredPacked.length && SectionKey.unpackX(enteredPacked[enteredEnd]) == x) {
                enteredEnd++;
            }
            int previousSlice = Arrays.binarySearch(previous.sectionXs, x);
            SectionKey[] oldSlice = previousSlice < 0
                    ? PackedSectionMembership.EMPTY_SECTION_KEYS
                    : previous.slices[previousSlice];
            SectionKey[] nextSlice;
            if (!affectedXs.contains(x)) {
                nextSlice = oldSlice;
            } else {
                int retained = 0;
                for (SectionKey key : oldSlice) {
                    if (exited == null || !exited.contains(PackedSectionMembership.packSection(key))) {
                        retained++;
                    }
                }
                nextSlice = new SectionKey[retained + enteredEnd - enteredStart];
                int oldIndex = 0;
                int newIndex = enteredStart;
                int output = 0;
                while (oldIndex < oldSlice.length || newIndex < enteredEnd) {
                    while (oldIndex < oldSlice.length
                            && exited != null
                            && exited.contains(PackedSectionMembership.packSection(oldSlice[oldIndex]))) {
                        oldIndex++;
                    }
                    if (oldIndex >= oldSlice.length && newIndex >= enteredEnd) {
                        break;
                    }
                    if (oldIndex >= oldSlice.length) {
                        nextSlice[output++] = PackedSectionMembership.keyFromPacked(enteredPacked[newIndex++]);
                    } else if (newIndex >= enteredEnd) {
                        nextSlice[output++] = oldSlice[oldIndex++];
                    } else if (PackedSectionMembership.compareSectionToPacked(
                            oldSlice[oldIndex], enteredPacked[newIndex]
                    ) < 0) {
                        nextSlice[output++] = oldSlice[oldIndex++];
                    } else {
                        nextSlice[output++] = PackedSectionMembership.keyFromPacked(enteredPacked[newIndex++]);
                    }
                }
            }
            if (nextSlice.length > 0) {
                nextXs[nextCount] = x;
                nextSlices[nextCount] = nextSlice;
                nextCount++;
            }
            enteredStart = enteredEnd;
        }
        return new PackedSectionCanonicalOrder(
                Arrays.copyOf(nextXs, nextCount),
                Arrays.copyOf(nextSlices, nextCount)
        );
    }

    @Override
    public SectionKey get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException(index);
        }
        int slice = Arrays.binarySearch(offsets, index);
        if (slice < 0) {
            slice = -slice - 2;
        } else if (slice == slices.length) {
            slice--;
        }
        return slices[slice][index - offsets[slice]];
    }

    @Override
    public int size() {
        return size;
    }

    int indexOfCanonical(SectionKey key) {
        int slice = Arrays.binarySearch(sectionXs, key.x());
        if (slice < 0) {
            return -1;
        }
        SectionKey[] candidates = slices[slice];
        int low = 0;
        int high = candidates.length - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            SectionKey candidate = candidates[middle];
            int comparison = Integer.compare(candidate.y(), key.y());
            if (comparison == 0) {
                comparison = Integer.compare(candidate.z(), key.z());
            }
            if (comparison < 0) {
                low = middle + 1;
            } else if (comparison > 0) {
                high = middle - 1;
            } else {
                return offsets[slice] + middle;
            }
        }
        return -1;
    }

    @Override
    public Iterator<SectionKey> iterator() {
        return new Iterator<>() {
            private int slice;
            private int index;

            @Override
            public boolean hasNext() {
                return slice < slices.length;
            }

            @Override
            public SectionKey next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                SectionKey key = slices[slice][index++];
                if (index == slices[slice].length) {
                    slice++;
                    index = 0;
                }
                return key;
            }
        };
    }

    SectionKey[] columnKeys(int x, int z) {
        int slice = Arrays.binarySearch(sectionXs, x);
        if (slice < 0) {
            return PackedSectionMembership.EMPTY_SECTION_KEYS;
        }
        SectionKey[] source = slices[slice];
        int count = 0;
        for (SectionKey key : source) {
            if (key.z() == z) {
                count++;
            }
        }
        if (count == 0) {
            return PackedSectionMembership.EMPTY_SECTION_KEYS;
        }
        SectionKey[] result = new SectionKey[count];
        int index = 0;
        for (SectionKey key : source) {
            if (key.z() == z) {
                result[index++] = key;
            }
        }
        return result;
    }
}
