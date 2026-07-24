package top.ceroxe.mcvulkanrt.renderer.scene;

import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.Arrays;
import java.util.Collection;

/**
 * Immutable bounded-depth radix page table with structural sharing between generations.
 *
 * <p>Four 8-way levels retain fixed-cost lookup while a one-page successor copies only the
 * nodes on that page's path. Empty subtrees collapse to one shared singleton.</p>
 */
final class PackedSectionIndex {
    private static final int PAGE_INDEX_BITS = 12;
    private static final int RADIX_BITS = 3;
    private static final int RADIX_SIZE = 1 << RADIX_BITS;
    private static final int RADIX_MASK = RADIX_SIZE - 1;
    private static final int RADIX_LEVELS = PAGE_INDEX_BITS / RADIX_BITS;
    private static final int PAGE_COUNT = 1 << PAGE_INDEX_BITS;
    private static final int PAGE_MASK = PAGE_COUNT - 1;

    private final MembershipDirectory root;

    private PackedSectionIndex(MembershipDirectory root) {
        this.root = root;
    }

    static PackedSectionIndex empty() {
        return new PackedSectionIndex(MembershipDirectory.EMPTY);
    }

    static PackedSectionIndex base(LongOpenHashSet keys) {
        int[] counts = new int[PAGE_COUNT];
        LongIterator iterator = keys.iterator();
        while (iterator.hasNext()) {
            counts[pageIndex(iterator.nextLong())]++;
        }
        MembershipDirectory root = MembershipDirectory.mutableRoot();
        for (int page = 0; page < counts.length; page++) {
            if (counts[page] == 0) {
                continue;
            }
            root.installMutable(page, new long[counts[page]]);
            counts[page] = 0;
        }
        iterator = keys.iterator();
        while (iterator.hasNext()) {
            long packed = iterator.nextLong();
            int page = pageIndex(packed);
            long[] values = root.page(page);
            values[counts[page]++] = packed;
        }
        for (int page = 0; page < counts.length; page++) {
            if (counts[page] != 0) {
                Arrays.sort(root.page(page));
            }
        }
        return new PackedSectionIndex(root);
    }

    static PackedSectionIndex baseFromKeys(Collection<SectionKey> keys) {
        LongOpenHashSet packed = new LongOpenHashSet(keys.size());
        for (SectionKey key : keys) {
            packed.add(PackedSectionMembership.packSection(key));
        }
        return base(packed);
    }

    static PackedSectionIndex delta(
            PackedSectionMembership previous,
            SectionKey[] nextKeys
    ) {
        LongOpenHashSet entered = null;
        for (SectionKey key : nextKeys) {
            long packed = PackedSectionMembership.packSection(key);
            if (!previous.containsPacked(packed)) {
                if (entered == null) {
                    entered = new LongOpenHashSet();
                }
                entered.add(packed);
            }
        }
        LongOpenHashSet exited = null;
        int nextIndex = 0;
        for (SectionKey key : previous.canonicalKeysStorage()) {
            while (nextIndex < nextKeys.length
                    && PackedSectionMembership.CANONICAL_ORDER.compare(nextKeys[nextIndex], key) < 0) {
                nextIndex++;
            }
            if (nextIndex >= nextKeys.length || !nextKeys[nextIndex].equals(key)) {
                if (exited == null) {
                    exited = new LongOpenHashSet();
                }
                exited.add(PackedSectionMembership.packSection(key));
            }
        }
            return delta(
                    previous.membershipIndexStorage(),
                    entered,
                    exited
            );
    }

    static PackedSectionIndex delta(
            PackedSectionIndex previous,
            LongOpenHashSet entered,
            LongOpenHashSet exited
    ) {
        if ((entered == null || entered.isEmpty()) && (exited == null || exited.isEmpty())) {
            return previous;
        }
        Long2ObjectLinkedOpenHashMap<LongOpenHashSet> enteredByPage = groupByPage(entered);
        Long2ObjectLinkedOpenHashMap<LongOpenHashSet> exitedByPage = groupByPage(exited);
        LongOpenHashSet affectedPages = new LongOpenHashSet(
                enteredByPage.size() + exitedByPage.size()
        );
        affectedPages.addAll(enteredByPage.keySet());
        affectedPages.addAll(exitedByPage.keySet());

        MembershipDirectory root = previous.root;
        LongIterator pages = affectedPages.iterator();
        while (pages.hasNext()) {
            int page = (int) pages.nextLong();
            long[] nextPage = applyPageDelta(
                    previous.page(page),
                    enteredByPage.get(page),
                    exitedByPage.get(page)
            );
            root = root.withPage(page, nextPage);
        }
        return new PackedSectionIndex(root);
    }

    boolean contains(long packed) {
        return Arrays.binarySearch(page(pageIndex(packed)), packed) >= 0;
    }

    private long[] page(int pageIndex) {
        return root.page(pageIndex);
    }

    private static int pageIndex(long packed) {
        return (int) HashCommon.mix(packed) & PAGE_MASK;
    }

    private static Long2ObjectLinkedOpenHashMap<LongOpenHashSet> groupByPage(LongOpenHashSet keys) {
        Long2ObjectLinkedOpenHashMap<LongOpenHashSet> grouped = new Long2ObjectLinkedOpenHashMap<>();
        if (keys == null) {
            return grouped;
        }
        LongIterator iterator = keys.iterator();
        while (iterator.hasNext()) {
            long packed = iterator.nextLong();
            int page = pageIndex(packed);
            grouped.computeIfAbsent(page, ignored -> new LongOpenHashSet()).add(packed);
        }
        return grouped;
    }

    private static long[] applyPageDelta(
            long[] previous,
            LongOpenHashSet entered,
            LongOpenHashSet exited
    ) {
        int retained = 0;
        for (long packed : previous) {
            if (exited == null || !exited.contains(packed)) {
                retained++;
            }
        }
        int additions = 0;
        if (entered != null) {
            LongIterator iterator = entered.iterator();
            while (iterator.hasNext()) {
                if (Arrays.binarySearch(previous, iterator.nextLong()) < 0) {
                    additions++;
                }
            }
        }
        if (retained + additions == 0) {
            return null;
        }
        long[] next = new long[retained + additions];
        int index = 0;
        for (long packed : previous) {
            if (exited == null || !exited.contains(packed)) {
                next[index++] = packed;
            }
        }
        if (entered != null) {
            LongIterator iterator = entered.iterator();
            while (iterator.hasNext()) {
                long packed = iterator.nextLong();
                if (Arrays.binarySearch(previous, packed) < 0) {
                    next[index++] = packed;
                }
            }
        }
        Arrays.sort(next);
        return next;
    }

    /** One persistent radix node; leaves contain sorted packed-section pages. */
    private static final class MembershipDirectory {
        private static final MembershipDirectory EMPTY =
                new MembershipDirectory(new Object[RADIX_SIZE]);

        private final Object[] children;

        private MembershipDirectory(Object[] children) {
            this.children = children;
        }

        private static MembershipDirectory mutableRoot() {
            return new MembershipDirectory(new Object[RADIX_SIZE]);
        }

        private long[] page(int pageIndex) {
            MembershipDirectory directory = this;
            for (int level = RADIX_LEVELS - 1; level > 0; level--) {
                Object child = directory.children[radixSlot(pageIndex, level)];
                if (child == null) {
                    return PackedSectionMembership.EMPTY_LONGS;
                }
                directory = (MembershipDirectory) child;
            }
            long[] page = (long[]) directory.children[radixSlot(pageIndex, 0)];
            return page == null ? PackedSectionMembership.EMPTY_LONGS : page;
        }

        /** Installs pages only while constructing an unpublished base generation. */
        private void installMutable(int pageIndex, long[] page) {
            MembershipDirectory directory = this;
            for (int level = RADIX_LEVELS - 1; level > 0; level--) {
                int slot = radixSlot(pageIndex, level);
                MembershipDirectory child = (MembershipDirectory) directory.children[slot];
                if (child == null) {
                    child = mutableRoot();
                    directory.children[slot] = child;
                }
                directory = child;
            }
            directory.children[radixSlot(pageIndex, 0)] = page;
        }

        private MembershipDirectory withPage(int pageIndex, long[] page) {
            return withPage(pageIndex, RADIX_LEVELS - 1, page);
        }

        private MembershipDirectory withPage(int pageIndex, int level, long[] page) {
            int slot = radixSlot(pageIndex, level);
            Object previousChild = children[slot];
            Object nextChild;
            if (level == 0) {
                nextChild = page;
            } else {
                MembershipDirectory previousDirectory = previousChild == null
                        ? EMPTY
                        : (MembershipDirectory) previousChild;
                MembershipDirectory nextDirectory = previousDirectory.withPage(pageIndex, level - 1, page);
                nextChild = nextDirectory == EMPTY ? null : nextDirectory;
            }
            if (previousChild == nextChild) {
                return this;
            }
            Object[] nextChildren = children.clone();
            nextChildren[slot] = nextChild;
            for (Object child : nextChildren) {
                if (child != null) {
                    return new MembershipDirectory(nextChildren);
                }
            }
            return EMPTY;
        }

        private static int radixSlot(int pageIndex, int level) {
            return (pageIndex >>> (level * RADIX_BITS)) & RADIX_MASK;
        }
    }
}
