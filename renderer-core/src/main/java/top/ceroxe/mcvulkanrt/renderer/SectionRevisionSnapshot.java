package top.ceroxe.mcvulkanrt.renderer;

import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;
import top.ceroxe.mcvulkanrt.renderer.scene.PackedSectionMembership;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Immutable section-content revisions aligned with one canonical section-key publication.
 *
 * <p>BLAS, TLAS, pending-frame and presentation records all retain the same generation. A
 * general-purpose hash map followed by repeated {@code Map.copyOf} calls duplicated thousands
 * of nodes and immutable-table arrays without adding isolation: the source generation was
 * already immutable. This value stores one canonical key publication and copy-on-write primitive
 * revision pages; {@link #copyOf(Map)} preserves identity when a downstream owner freezes it again.</p>
 */
public final class SectionRevisionSnapshot extends AbstractMap<SectionKey, Long> {
    private static final int REVISION_PAGE_SHIFT = 8;
    private static final int REVISION_PAGE_SIZE = 1 << REVISION_PAGE_SHIFT;
    private static final int REVISION_PAGE_MASK = REVISION_PAGE_SIZE - 1;
    private static final long[][] EMPTY_REVISION_PAGES = new long[0][];
    private static final SectionRevisionSnapshot EMPTY =
            new SectionRevisionSnapshot(PackedSectionMembership.empty(), EMPTY_REVISION_PAGES);

    private final PackedSectionMembership membership;
    private final List<SectionKey> sectionKeys;
    private final long[][] revisionPages;
    private final Set<SectionKey> keySet;
    private final Set<Entry<SectionKey, Long>> entrySet = new EntrySet();

    private SectionRevisionSnapshot(PackedSectionMembership membership, long[][] revisionPages) {
        this.membership = Objects.requireNonNull(membership, "membership");
        if (!membership.canonicalOrder()) {
            throw new IllegalArgumentException("section revision membership must be canonical");
        }
        this.sectionKeys = membership.orderedKeys();
        this.keySet = membership;
        this.revisionPages = Objects.requireNonNull(revisionPages, "revisionPages");
        validateRevisionPages(this.sectionKeys.size(), revisionPages);
        if (!RendererViewState.isCanonicalSectionKeyList(this.sectionKeys)) {
            throw new IllegalArgumentException("section revision keys must be canonical and duplicate-free");
        }
    }

    /** Returns the single immutable empty publication. */
    public static SectionRevisionSnapshot empty() {
        return EMPTY;
    }

    /**
     * Publishes one revision for every supplied section key.
     *
     * <p>This is deliberately a construction boundary rather than an exposed mutable map so
     * compatibility/default paths cannot create a second ownership representation for the same
     * section generation.</p>
     */
    public static SectionRevisionSnapshot constant(
            Collection<SectionKey> sectionKeys,
            long revision
    ) {
        Objects.requireNonNull(sectionKeys, "sectionKeys");
        if (sectionKeys.isEmpty()) {
            return EMPTY;
        }
        PackedSectionMembership membership = PackedSectionMembership.canonicalDistinct(sectionKeys);
        long[][] pages = allocateRevisionPages(membership.size());
        for (long[] page : pages) {
            java.util.Arrays.fill(page, revision);
        }
        return new SectionRevisionSnapshot(membership, pages);
    }

    /**
     * Selects revisions for an already-canonical coverage list without constructing hash nodes.
     * The source map may own a larger resident generation; only the supplied coverage is frozen.
     */
    public static SectionRevisionSnapshot select(
            List<SectionKey> canonicalSectionKeys,
            Map<SectionKey, Long> sourceRevisions
    ) {
        Objects.requireNonNull(canonicalSectionKeys, "canonicalSectionKeys");
        Objects.requireNonNull(sourceRevisions, "sourceRevisions");
        if (canonicalSectionKeys.isEmpty()) {
            return EMPTY;
        }
        if (!RendererViewState.isCanonicalSectionKeyList(canonicalSectionKeys)) {
            throw new IllegalArgumentException("selected section revision keys must be canonical");
        }
        if (sourceRevisions instanceof SectionRevisionSnapshot snapshot
                && snapshot.sectionKeys.equals(canonicalSectionKeys)) {
            /*
             * A BLAS publication is subsequently adopted by the foreground epoch
             * for the same coverage. Its primitive array is already immutable, so
             * copying it would only create a second generation with no ownership
             * or isolation benefit.
             */
            return snapshot;
        }
        return select(PackedSectionMembership.canonicalDistinct(canonicalSectionKeys), sourceRevisions);
    }

    /** Selects revisions while retaining the exact canonical membership publication. */
    public static SectionRevisionSnapshot select(
            PackedSectionMembership membership,
            Map<SectionKey, Long> sourceRevisions
    ) {
        return select(membership, sourceRevisions, null);
    }

    /**
     * Selects revisions while reusing unchanged primitive pages from the preceding publication.
     *
     * <p>Content revisions usually advance for one completed section while the active TLAS
     * membership remains stable. A monolithic primitive array therefore copied thousands of
     * unchanged values for every BLAS completion. Page identity makes unchanged publication a
     * zero-allocation operation and bounds a sparse update to the pages that actually changed.</p>
     */
    public static SectionRevisionSnapshot select(
            PackedSectionMembership membership,
            Map<SectionKey, Long> sourceRevisions,
            SectionRevisionSnapshot previous
    ) {
        Objects.requireNonNull(membership, "membership");
        Objects.requireNonNull(sourceRevisions, "sourceRevisions");
        if (!membership.canonicalOrder()) {
            throw new IllegalArgumentException("selected section revision membership must be canonical");
        }
        if (membership.isEmpty()) {
            return EMPTY;
        }
        if (sourceRevisions instanceof SectionRevisionSnapshot snapshot
                && snapshot.membership == membership) {
            return snapshot;
        }
        if (previous != null && previous.membership == membership) {
            long[][] nextPages = previous.revisionPages;
            for (int index = 0; index < membership.size(); index++) {
                SectionKey key = membership.orderedKeys().get(index);
                long revision = requiredRevision(sourceRevisions, key);
                if (previous.revisionAt(index) == revision) {
                    continue;
                }
                if (nextPages == previous.revisionPages) {
                    nextPages = previous.revisionPages.clone();
                }
                int pageIndex = index >>> REVISION_PAGE_SHIFT;
                if (nextPages[pageIndex] == previous.revisionPages[pageIndex]) {
                    nextPages[pageIndex] = previous.revisionPages[pageIndex].clone();
                }
                nextPages[pageIndex][index & REVISION_PAGE_MASK] = revision;
            }
            return nextPages == previous.revisionPages
                    ? previous
                    : new SectionRevisionSnapshot(membership, nextPages);
        }
        long[][] selectedRevisions = allocateRevisionPages(membership.size());
        for (int index = 0; index < membership.size(); index++) {
            SectionKey key = membership.orderedKeys().get(index);
            setRevision(selectedRevisions, index, requiredRevision(sourceRevisions, key));
        }
        return new SectionRevisionSnapshot(membership, selectedRevisions);
    }

    /** Selects a canonical subset while substituting one explicit missing-value sentinel. */
    public static SectionRevisionSnapshot select(
            Collection<SectionKey> sectionKeys,
            Map<SectionKey, Long> sourceRevisions,
            long missingRevision
    ) {
        Objects.requireNonNull(sectionKeys, "sectionKeys");
        Objects.requireNonNull(sourceRevisions, "sourceRevisions");
        if (sectionKeys.isEmpty()) {
            return EMPTY;
        }
        PackedSectionMembership membership = PackedSectionMembership.canonicalDistinct(sectionKeys);
        List<SectionKey> canonicalKeys = membership.orderedKeys();
        if (sourceRevisions instanceof SectionRevisionSnapshot snapshot
                && snapshot.sectionKeys.equals(canonicalKeys)) {
            return snapshot;
        }
        long[][] selectedRevisions = allocateRevisionPages(canonicalKeys.size());
        for (int index = 0; index < canonicalKeys.size(); index++) {
            setRevision(selectedRevisions, index, valueOrDefault(
                    sourceRevisions,
                    canonicalKeys.get(index),
                    missingRevision
            ));
        }
        return new SectionRevisionSnapshot(membership, selectedRevisions);
    }

    /** Freezes an arbitrary revision map once, retaining an existing immutable publication. */
    public static SectionRevisionSnapshot copyOf(Map<SectionKey, Long> revisions) {
        Objects.requireNonNull(revisions, "sectionContentRevisions");
        if (revisions instanceof SectionRevisionSnapshot snapshot) {
            return snapshot;
        }
        if (revisions.isEmpty()) {
            return EMPTY;
        }
        PackedSectionMembership membership = PackedSectionMembership.canonicalDistinct(revisions.keySet());
        List<SectionKey> keys = membership.orderedKeys();
        long[][] values = allocateRevisionPages(keys.size());
        for (int index = 0; index < keys.size(); index++) {
            SectionKey key = keys.get(index);
            Long revision = revisions.get(key);
            if (revision == null) {
                throw new NullPointerException("sectionContentRevisions value for " + key);
            }
            setRevision(values, index, revision);
        }
        if (keys.size() != revisions.size()) {
            throw new IllegalArgumentException("section content revisions contain duplicate coordinates");
        }
        return new SectionRevisionSnapshot(membership, values);
    }

    /** Reads a primitive revision without boxing when the source is this publication type. */
    public static long valueOrDefault(
            Map<SectionKey, Long> revisions,
            SectionKey key,
            long defaultRevision
    ) {
        Objects.requireNonNull(revisions, "revisions");
        Objects.requireNonNull(key, "section key");
        if (revisions instanceof SectionRevisionSnapshot snapshot) {
            int index = snapshot.membership.canonicalIndex(key);
            return index < 0 ? defaultRevision : snapshot.revisionAt(index);
        }
        Long revision = revisions.get(key);
        return revision == null ? defaultRevision : revision;
    }

    /** Returns the primitive revision for a covered key, or the supplied sentinel. */
    public long valueOrDefault(SectionKey key, long defaultRevision) {
        Objects.requireNonNull(key, "section key");
        int index = membership.canonicalIndex(key);
        return index < 0 ? defaultRevision : revisionAt(index);
    }

    /** Returns the canonical, duplicate-free key order used by this publication. */
    public List<SectionKey> sectionKeys() {
        return sectionKeys;
    }

    public PackedSectionMembership membership() {
        return membership;
    }

    @Override
    public int size() {
        return sectionKeys.size();
    }

    @Override
    public boolean containsKey(Object candidate) {
        return candidate instanceof SectionKey key
                && membership.contains(key);
    }

    @Override
    public Long get(Object candidate) {
        if (!(candidate instanceof SectionKey key)) {
            return null;
        }
        int index = membership.canonicalIndex(key);
        return index < 0 ? null : revisionAt(index);
    }

    @Override
    public Set<SectionKey> keySet() {
        return keySet;
    }

    @Override
    public Set<Entry<SectionKey, Long>> entrySet() {
        return entrySet;
    }

    @Override
    public boolean equals(Object candidate) {
        if (candidate == this) {
            return true;
        }
        if (!(candidate instanceof Map<?, ?> other) || other.size() != size()) {
            return false;
        }
        for (int index = 0; index < sectionKeys.size(); index++) {
            Object value = other.get(sectionKeys.get(index));
            if (!(value instanceof Long revision) || revision.longValue() != revisionAt(index)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int hash = 0;
        for (int index = 0; index < sectionKeys.size(); index++) {
            hash += sectionKeys.get(index).hashCode() ^ Long.hashCode(revisionAt(index));
        }
        return hash;
    }

    @Override
    public void forEach(BiConsumer<? super SectionKey, ? super Long> action) {
        Objects.requireNonNull(action, "action");
        for (int index = 0; index < sectionKeys.size(); index++) {
            action.accept(sectionKeys.get(index), revisionAt(index));
        }
    }

    @Override
    public Long put(SectionKey key, Long value) {
        throw immutableMutation();
    }

    @Override
    public Long remove(Object key) {
        throw immutableMutation();
    }

    @Override
    public void putAll(Map<? extends SectionKey, ? extends Long> map) {
        throw immutableMutation();
    }

    @Override
    public void clear() {
        throw immutableMutation();
    }

    @Override
    public void replaceAll(BiFunction<? super SectionKey, ? super Long, ? extends Long> function) {
        throw immutableMutation();
    }

    @Override
    public Long putIfAbsent(SectionKey key, Long value) {
        throw immutableMutation();
    }

    @Override
    public boolean remove(Object key, Object value) {
        throw immutableMutation();
    }

    @Override
    public boolean replace(SectionKey key, Long oldValue, Long newValue) {
        throw immutableMutation();
    }

    @Override
    public Long replace(SectionKey key, Long value) {
        throw immutableMutation();
    }

    @Override
    public Long computeIfAbsent(
            SectionKey key,
            Function<? super SectionKey, ? extends Long> mappingFunction
    ) {
        throw immutableMutation();
    }

    @Override
    public Long computeIfPresent(
            SectionKey key,
            BiFunction<? super SectionKey, ? super Long, ? extends Long> remappingFunction
    ) {
        throw immutableMutation();
    }

    @Override
    public Long compute(
            SectionKey key,
            BiFunction<? super SectionKey, ? super Long, ? extends Long> remappingFunction
    ) {
        throw immutableMutation();
    }

    @Override
    public Long merge(
            SectionKey key,
            Long value,
            BiFunction<? super Long, ? super Long, ? extends Long> remappingFunction
    ) {
        throw immutableMutation();
    }

    private static UnsupportedOperationException immutableMutation() {
        return new UnsupportedOperationException("section revision snapshot is immutable");
    }

    private final class KeySet extends AbstractSet<SectionKey> {
        @Override
        public Iterator<SectionKey> iterator() {
            return sectionKeys.iterator();
        }

        @Override
        public int size() {
            return sectionKeys.size();
        }

        @Override
        public boolean contains(Object candidate) {
            return SectionRevisionSnapshot.this.containsKey(candidate);
        }

        @Override
        public boolean remove(Object candidate) {
            throw immutableMutation();
        }

        @Override
        public void clear() {
            throw immutableMutation();
        }
    }

    private final class EntrySet extends AbstractSet<Entry<SectionKey, Long>> {
        @Override
        public Iterator<Entry<SectionKey, Long>> iterator() {
            return new Iterator<>() {
                private int index;

                @Override
                public boolean hasNext() {
                    return index < sectionKeys.size();
                }

                @Override
                public Entry<SectionKey, Long> next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    int current = index++;
                    return Map.entry(sectionKeys.get(current), revisionAt(current));
                }

                @Override
                public void remove() {
                    throw immutableMutation();
                }
            };
        }

        @Override
        public int size() {
            return sectionKeys.size();
        }

        @Override
        public boolean contains(Object candidate) {
            if (!(candidate instanceof Entry<?, ?> entry) || !(entry.getKey() instanceof SectionKey key)) {
                return false;
            }
            int index = membership.canonicalIndex(key);
            return index >= 0 && Objects.equals(revisionAt(index), entry.getValue());
        }

        @Override
        public boolean remove(Object candidate) {
            throw immutableMutation();
        }

        @Override
        public void clear() {
            throw immutableMutation();
        }
    }

    private long revisionAt(int index) {
        return revisionPages[index >>> REVISION_PAGE_SHIFT][index & REVISION_PAGE_MASK];
    }

    private static long requiredRevision(Map<SectionKey, Long> sourceRevisions, SectionKey key) {
        if (sourceRevisions instanceof SectionRevisionSnapshot snapshot) {
            int index = snapshot.membership.canonicalIndex(key);
            if (index >= 0) {
                return snapshot.revisionAt(index);
            }
        } else {
            Long revision = sourceRevisions.get(key);
            if (revision != null) {
                return revision;
            }
        }
        throw new IllegalStateException("active RT section is missing its content revision: " + key);
    }

    private static long[][] allocateRevisionPages(int size) {
        if (size == 0) {
            return EMPTY_REVISION_PAGES;
        }
        int pageCount = (size + REVISION_PAGE_MASK) >>> REVISION_PAGE_SHIFT;
        long[][] pages = new long[pageCount][];
        for (int page = 0; page < pageCount; page++) {
            int remaining = size - (page << REVISION_PAGE_SHIFT);
            pages[page] = new long[Math.min(REVISION_PAGE_SIZE, remaining)];
        }
        return pages;
    }

    private static void setRevision(long[][] pages, int index, long revision) {
        pages[index >>> REVISION_PAGE_SHIFT][index & REVISION_PAGE_MASK] = revision;
    }

    private static void validateRevisionPages(int size, long[][] pages) {
        int expectedPages = (size + REVISION_PAGE_MASK) >>> REVISION_PAGE_SHIFT;
        if (pages.length != expectedPages) {
            throw new IllegalArgumentException("section key and revision page counts must match");
        }
        for (int page = 0; page < pages.length; page++) {
            long[] values = Objects.requireNonNull(pages[page], "revision page");
            int remaining = size - (page << REVISION_PAGE_SHIFT);
            int expectedLength = Math.min(REVISION_PAGE_SIZE, remaining);
            if (values.length != expectedLength) {
                throw new IllegalArgumentException("section key and revision page sizes must match");
            }
        }
    }
}
