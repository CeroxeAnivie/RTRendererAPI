package top.ceroxe.rt.renderer.scene;

import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.*;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable two-level COW page table and views for primitive chunk-column lookup.
 */
final class PackedSectionColumnIndex {
    private static final int DIRECTORY_BITS = 6;
    private static final int PAGE_BITS = 6;
    private static final int DIRECTORY_SIZE = 1 << DIRECTORY_BITS;
    private static final int PAGE_COUNT = 1 << (DIRECTORY_BITS + PAGE_BITS);
    private static final int PAGE_MASK = PAGE_COUNT - 1;
    private static final ThreadLocal<ColumnGroupingScratch> GROUPING_SCRATCH =
            ThreadLocal.withInitial(ColumnGroupingScratch::new);
    final Set<ChunkKey> chunkColumns;
    final Map<ChunkKey, Set<SectionKey>> sectionsByChunk;
    private final ColumnPage[][] directories;
    private final long[] packedColumns;

    private PackedSectionColumnIndex(ColumnPage[][] directories, long[] packedColumns) {
        this.directories = directories;
        this.packedColumns = packedColumns;
        this.chunkColumns = PackedSectionViews.chunkColumns(this);
        this.sectionsByChunk = PackedSectionViews.sectionsByChunk(this, chunkColumns);
    }

    static PackedSectionColumnIndex build(
            PackedSectionCanonicalOrder orderedKeys,
            boolean canonicalOrder,
            PackedSectionMembership reusableMembership,
            PackedSectionIndex currentMembership,
            LongOpenHashSet knownAffectedColumns
    ) {
        if (orderedKeys.isEmpty()) {
            return empty();
        }
        if (reusableMembership != null && reusableMembership.canonicalOrder() == canonicalOrder) {
            LongOpenHashSet affectedColumns = knownAffectedColumns;
            if (affectedColumns == null) {
                affectedColumns = new LongOpenHashSet();
                for (SectionKey key : reusableMembership.canonicalKeysStorage()) {
                    if (!currentMembership.contains(PackedSectionMembership.packSection(key))) {
                        affectedColumns.add(PackedSectionMembership.packChunk(key.x(), key.z()));
                    }
                }
                for (SectionKey key : orderedKeys) {
                    if (!reusableMembership.containsPacked(PackedSectionMembership.packSection(key))) {
                        affectedColumns.add(PackedSectionMembership.packChunk(key.x(), key.z()));
                    }
                }
            }
            if (affectedColumns.isEmpty()) {
                return reusableMembership.columnIndexStorage();
            }
            return delta(orderedKeys, reusableMembership.columnIndexStorage(), affectedColumns);
        }
        return base(orderedKeys);
    }

    private static PackedSectionColumnIndex base(PackedSectionCanonicalOrder orderedKeys) {
        Long2IntOpenHashMap columnIndices = new Long2IntOpenHashMap(Math.max(2, orderedKeys.size() / 8));
        columnIndices.defaultReturnValue(-1);
        long[] packedColumns = new long[Math.max(1, orderedKeys.size() / 8)];
        int[] columnSizes = new int[packedColumns.length];
        int columnCount = 0;
        for (SectionKey key : orderedKeys) {
            long packedChunk = PackedSectionMembership.packChunk(key.x(), key.z());
            int columnIndex = columnIndices.get(packedChunk);
            if (columnIndex < 0) {
                if (columnCount == packedColumns.length) {
                    int nextCapacity = Math.max(columnCount + 1, packedColumns.length * 2);
                    packedColumns = Arrays.copyOf(packedColumns, nextCapacity);
                    columnSizes = Arrays.copyOf(columnSizes, nextCapacity);
                }
                columnIndex = columnCount++;
                packedColumns[columnIndex] = packedChunk;
                columnIndices.put(packedChunk, columnIndex);
            }
            columnSizes[columnIndex]++;
        }
        SectionKey[][] columnKeys = new SectionKey[columnCount][];
        for (int index = 0; index < columnCount; index++) {
            columnKeys[index] = new SectionKey[columnSizes[index]];
            columnSizes[index] = 0;
        }
        for (SectionKey key : orderedKeys) {
            int columnIndex = columnIndices.get(PackedSectionMembership.packChunk(key.x(), key.z()));
            columnKeys[columnIndex][columnSizes[columnIndex]++] = key;
        }
        Long2ObjectLinkedOpenHashMap<Set<SectionKey>> frozen =
                new Long2ObjectLinkedOpenHashMap<>(columnCount);
        for (int index = 0; index < columnCount; index++) {
            frozen.put(packedColumns[index], PackedSectionViews.sectionColumn(columnKeys[index]));
        }
        long[] canonicalColumns = Arrays.copyOf(packedColumns, columnCount);
        LongArrays.quickSort(canonicalColumns, PackedSectionMembership::comparePackedChunks);
        return base(frozen, canonicalColumns);
    }

    private static PackedSectionColumnIndex delta(
            PackedSectionCanonicalOrder orderedKeys,
            PackedSectionColumnIndex previous,
            LongOpenHashSet affectedColumns
    ) {
        int affectedCount = affectedColumns.size();
        long[] affected = new long[affectedCount];
        LongIterator affectedIterator = affectedColumns.iterator();
        for (int index = 0; index < affectedCount; index++) {
            affected[index] = affectedIterator.nextLong();
        }
        Long2ObjectLinkedOpenHashMap<Set<SectionKey>> changed =
                new Long2ObjectLinkedOpenHashMap<>(affectedCount);
        for (long packedChunk : affected) {
            int x = (int) (packedChunk >> 32);
            int z = (int) packedChunk;
            SectionKey[] keys = orderedKeys.columnKeys(x, z);
            changed.put(packedChunk, keys.length == 0 ? null : PackedSectionViews.sectionColumn(keys));
        }
        return delta(previous, changed);
    }

    private static PackedSectionColumnIndex base(
            Long2ObjectLinkedOpenHashMap<Set<SectionKey>> columns,
            long[] packedColumns
    ) {
        ColumnPage[][] directories = new ColumnPage[DIRECTORY_SIZE][];
        PageChanges[] byPage = groupColumnsByPage(columns);
        for (int page = 0; page < byPage.length; page++) {
            PageChanges pageChanges = byPage[page];
            if (pageChanges == null) {
                continue;
            }
            int directoryIndex = page >>> PAGE_BITS;
            if (directories[directoryIndex] == null) {
                directories[directoryIndex] = new ColumnPage[DIRECTORY_SIZE];
            }
            directories[directoryIndex][page & (DIRECTORY_SIZE - 1)] = ColumnPage.from(pageChanges);
        }
        return new PackedSectionColumnIndex(directories, packedColumns);
    }

    private static PackedSectionColumnIndex delta(
            PackedSectionColumnIndex previous,
            Long2ObjectLinkedOpenHashMap<Set<SectionKey>> changes
    ) {
        int enteredCount = 0;
        int retiredCount = 0;
        for (var entry : changes.long2ObjectEntrySet()) {
            Set<SectionKey> oldColumn = previous.column(entry.getLongKey());
            if (oldColumn == null && entry.getValue() != null) {
                enteredCount++;
            } else if (oldColumn != null && entry.getValue() == null) {
                retiredCount++;
            }
        }
        long[] entered = new long[enteredCount];
        int enteredIndex = 0;
        for (var entry : changes.long2ObjectEntrySet()) {
            if (previous.column(entry.getLongKey()) == null && entry.getValue() != null) {
                entered[enteredIndex++] = entry.getLongKey();
            }
        }
        LongArrays.quickSort(entered, PackedSectionMembership::comparePackedChunks);
        long[] packedColumns = new long[previous.packedColumns.length + enteredCount - retiredCount];
        int previousIndex = 0;
        enteredIndex = 0;
        int outputIndex = 0;
        while (previousIndex < previous.packedColumns.length || enteredIndex < entered.length) {
            while (previousIndex < previous.packedColumns.length
                    && changes.containsKey(previous.packedColumns[previousIndex])
                    && changes.get(previous.packedColumns[previousIndex]) == null) {
                previousIndex++;
            }
            if (previousIndex >= previous.packedColumns.length && enteredIndex >= entered.length) {
                break;
            }
            if (previousIndex >= previous.packedColumns.length) {
                packedColumns[outputIndex++] = entered[enteredIndex++];
            } else if (enteredIndex >= entered.length) {
                packedColumns[outputIndex++] = previous.packedColumns[previousIndex++];
            } else if (PackedSectionMembership.comparePackedChunks(
                    previous.packedColumns[previousIndex], entered[enteredIndex]
            ) < 0) {
                packedColumns[outputIndex++] = previous.packedColumns[previousIndex++];
            } else {
                packedColumns[outputIndex++] = entered[enteredIndex++];
            }
        }
        ColumnPage[][] directories = previous.directories.clone();
        boolean[] copiedDirectories = new boolean[DIRECTORY_SIZE];
        PageChanges[] byPage = groupColumnsByPage(changes);
        for (int page = 0; page < byPage.length; page++) {
            PageChanges pageChanges = byPage[page];
            if (pageChanges == null) {
                continue;
            }
            int directoryIndex = page >>> PAGE_BITS;
            if (!copiedDirectories[directoryIndex]) {
                ColumnPage[] previousDirectory = directories[directoryIndex];
                directories[directoryIndex] = previousDirectory == null
                        ? new ColumnPage[DIRECTORY_SIZE]
                        : previousDirectory.clone();
                copiedDirectories[directoryIndex] = true;
            }
            int localPage = page & (DIRECTORY_SIZE - 1);
            directories[directoryIndex][localPage] = ColumnPage.apply(previous.page(page), pageChanges);
        }
        return new PackedSectionColumnIndex(directories, packedColumns);
    }

    private static PackedSectionColumnIndex empty() {
        return base(new Long2ObjectLinkedOpenHashMap<>(), new long[0]);
    }

    private static PageChanges[] groupColumnsByPage(
            Long2ObjectLinkedOpenHashMap<Set<SectionKey>> columns
    ) {
        ColumnGroupingScratch scratch = GROUPING_SCRATCH.get().reset();
        for (var entry : columns.long2ObjectEntrySet()) {
            scratch.count(columnPageIndex(entry.getLongKey()));
        }
        scratch.allocateTouchedPages();
        for (var entry : columns.long2ObjectEntrySet()) {
            scratch.grouped[columnPageIndex(entry.getLongKey())].add(entry.getLongKey(), entry.getValue());
        }
        for (int index = 0; index < scratch.touchedCount; index++) {
            scratch.grouped[scratch.touchedPages[index]].sort();
        }
        return scratch.grouped;
    }

    private static int columnPageIndex(long packedChunk) {
        return (int) HashCommon.mix(packedChunk) & PAGE_MASK;
    }

    Set<SectionKey> column(long packedChunk) {
        return page(columnPageIndex(packedChunk)).get(packedChunk);
    }

    boolean containsColumn(long packedChunk) {
        int low = 0;
        int high = packedColumns.length - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int comparison = PackedSectionMembership.comparePackedChunks(packedColumns[middle], packedChunk);
            if (comparison < 0) {
                low = middle + 1;
            } else if (comparison > 0) {
                high = middle - 1;
            } else {
                return true;
            }
        }
        return false;
    }

    int columnCount() {
        return packedColumns.length;
    }

    long packedColumnAt(int index) {
        return packedColumns[index];
    }

    private ColumnPage page(int pageIndex) {
        ColumnPage[] directory = directories[pageIndex >>> PAGE_BITS];
        if (directory == null) {
            return ColumnPage.EMPTY;
        }
        ColumnPage page = directory[pageIndex & (DIRECTORY_SIZE - 1)];
        return page == null ? ColumnPage.EMPTY : page;
    }

    private static final class ColumnGroupingScratch {
        private final int[] pageCounts = new int[PAGE_COUNT];
        private final int[] touchedPages = new int[PAGE_COUNT];
        private final PageChanges[] grouped = new PageChanges[PAGE_COUNT];
        private int touchedCount;

        private ColumnGroupingScratch reset() {
            for (int index = 0; index < touchedCount; index++) {
                int page = touchedPages[index];
                pageCounts[page] = 0;
                grouped[page] = null;
            }
            touchedCount = 0;
            return this;
        }

        private void count(int page) {
            if (pageCounts[page]++ == 0) {
                touchedPages[touchedCount++] = page;
            }
        }

        private void allocateTouchedPages() {
            for (int index = 0; index < touchedCount; index++) {
                int page = touchedPages[index];
                grouped[page] = new PageChanges(pageCounts[page]);
            }
        }
    }

    /**
     * Dense, short-lived page delta used only while publishing one immutable column index.
     */
    private static final class PageChanges {
        private final long[] packedColumns;
        private final Object[] sectionColumns;
        private int size;

        private PageChanges(int capacity) {
            this.packedColumns = new long[capacity];
            this.sectionColumns = new Object[capacity];
        }

        private void add(long packedColumn, Set<SectionKey> sections) {
            packedColumns[size] = packedColumn;
            sectionColumns[size] = sections;
            size++;
        }

        private void sort() {
            for (int index = 1; index < size; index++) {
                long key = packedColumns[index];
                Object value = sectionColumns[index];
                int insertion = index;
                while (insertion > 0 && packedColumns[insertion - 1] > key) {
                    packedColumns[insertion] = packedColumns[insertion - 1];
                    sectionColumns[insertion] = sectionColumns[insertion - 1];
                    insertion--;
                }
                packedColumns[insertion] = key;
                sectionColumns[insertion] = value;
            }
        }
    }

    private static final class ColumnPage {
        private static final ColumnPage EMPTY =
                new ColumnPage(PackedSectionMembership.EMPTY_LONGS, new Object[0]);

        private final long[] packedColumns;
        private final Object[] sectionColumns;

        private ColumnPage(long[] packedColumns, Object[] sectionColumns) {
            this.packedColumns = packedColumns;
            this.sectionColumns = sectionColumns;
        }

        private static ColumnPage from(PageChanges changes) {
            if (changes.size == 0) {
                return null;
            }
            for (int index = 0; index < changes.size; index++) {
                Objects.requireNonNull(changes.sectionColumns[index]);
            }
            return new ColumnPage(changes.packedColumns, changes.sectionColumns);
        }

        private static ColumnPage apply(ColumnPage previous, PageChanges changes) {
            long[] nextKeys = new long[previous.packedColumns.length + changes.size];
            Object[] nextValues = new Object[nextKeys.length];
            int previousIndex = 0;
            int changeIndex = 0;
            int nextIndex = 0;
            while (previousIndex < previous.packedColumns.length || changeIndex < changes.size) {
                if (changeIndex >= changes.size
                        || previousIndex < previous.packedColumns.length
                        && previous.packedColumns[previousIndex] < changes.packedColumns[changeIndex]) {
                    nextKeys[nextIndex] = previous.packedColumns[previousIndex];
                    nextValues[nextIndex++] = previous.sectionColumns[previousIndex++];
                } else if (previousIndex >= previous.packedColumns.length
                        || changes.packedColumns[changeIndex] < previous.packedColumns[previousIndex]) {
                    Object changedValue = changes.sectionColumns[changeIndex];
                    if (changedValue != null) {
                        nextKeys[nextIndex] = changes.packedColumns[changeIndex];
                        nextValues[nextIndex++] = changedValue;
                    }
                    changeIndex++;
                } else {
                    Object changedValue = changes.sectionColumns[changeIndex++];
                    previousIndex++;
                    if (changedValue != null) {
                        nextKeys[nextIndex] = changes.packedColumns[changeIndex - 1];
                        nextValues[nextIndex++] = changedValue;
                    }
                }
            }
            if (nextIndex == 0) {
                return null;
            }
            return new ColumnPage(
                    nextIndex == nextKeys.length ? nextKeys : Arrays.copyOf(nextKeys, nextIndex),
                    nextIndex == nextValues.length ? nextValues : Arrays.copyOf(nextValues, nextIndex)
            );
        }

        private Set<SectionKey> get(long packedChunk) {
            int index = Arrays.binarySearch(packedColumns, packedChunk);
            if (index < 0) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Set<SectionKey> column = (Set<SectionKey>) sectionColumns[index];
            return column;
        }
    }

}
