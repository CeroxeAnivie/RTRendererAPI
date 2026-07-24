package top.ceroxe.mcvulkanrt.renderer.scene;

import it.unimi.dsi.fastutil.longs.LongArrays;
import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One immutable section-membership publication shared across renderer ownership boundaries.
 *
 * <p>The ordered {@link SectionKey} list remains the public identity and iteration contract, while
 * membership and chunk-column lookup use packed primitive coordinates. A real coverage change
 * therefore allocates one dense publication instead of independently rebuilding {@code SetN},
 * {@code HashSet}, {@code ChunkKey}, and per-column map graphs in visibility, extraction,
 * foreground, and native-admission consumers.</p>
 */
public final class PackedSectionMembership extends AbstractSet<SectionKey> {
    static final long[] EMPTY_LONGS = new long[0];
    static final SectionKey[] EMPTY_SECTION_KEYS = new SectionKey[0];
    static final Comparator<SectionKey> CANONICAL_ORDER = Comparator
            .comparingInt(SectionKey::x)
            .thenComparingInt(SectionKey::y)
            .thenComparingInt(SectionKey::z);
    private static final ThreadLocal<Builder> CANONICAL_BUILDER =
            ThreadLocal.withInitial(() -> new Builder(16));
    private static final PackedSectionMembership EMPTY = new PackedSectionMembership(
            new SectionKey[0], PackedSectionIndex.empty(), true, null
    );

    private final PackedSectionCanonicalOrder keyArray;
    private final List<SectionKey> orderedKeys;
    private final PackedSectionIndex membershipIndex;
    private final boolean canonicalOrder;
    private final PackedSectionColumnIndex columnIndex;
    private final PackedSectionTransition transition;

    private PackedSectionMembership(
            SectionKey[] orderedKeys,
            PackedSectionIndex membershipIndex,
            boolean canonicalOrder,
            PackedSectionMembership reusableColumns
    ) {
        this(PackedSectionCanonicalOrder.base(orderedKeys), membershipIndex, canonicalOrder, reusableColumns, null, null, null);
    }

    private PackedSectionMembership(
            PackedSectionCanonicalOrder orderedKeys,
            PackedSectionIndex membershipIndex,
            boolean canonicalOrder,
            PackedSectionMembership reusableColumns,
            LongOpenHashSet affectedColumns
    ) {
        this(orderedKeys, membershipIndex, canonicalOrder, reusableColumns, affectedColumns, null, null);
    }

    private PackedSectionMembership(
            PackedSectionCanonicalOrder orderedKeys,
            PackedSectionIndex membershipIndex,
            boolean canonicalOrder,
            PackedSectionMembership reusableColumns,
            LongOpenHashSet affectedColumns,
            long[] enteredSections,
            long[] exitedSections
    ) {
        this.keyArray = orderedKeys;
        this.orderedKeys = orderedKeys;
        orderedKeys.owner = this;
        this.membershipIndex = membershipIndex;
        this.canonicalOrder = canonicalOrder;
        this.columnIndex = PackedSectionColumnIndex.build(
                orderedKeys, canonicalOrder, reusableColumns, membershipIndex, affectedColumns
        );
        this.transition = PackedSectionTransition.of(
                reusableColumns, enteredSections, exitedSections
        );
    }

    public static PackedSectionMembership empty() {
        return EMPTY;
    }

    /** Freezes a duplicate-free collection while preserving its iteration order. */
    public static PackedSectionMembership copyOf(Collection<SectionKey> source) {
        return copyOf(source, null);
    }

    /**
     * Freezes a duplicate-free collection and reuses unchanged vertical column payloads from the
     * preceding publication when its chunk index has already been materialized.
     */
    public static PackedSectionMembership copyOf(
            Collection<SectionKey> source,
            PackedSectionMembership reusableColumns
    ) {
        Objects.requireNonNull(source, "source");
        if (source instanceof PackedSectionMembership packed) {
            return packed;
        }
        if (source instanceof PackedSectionCanonicalOrder canonicalKeys && canonicalKeys.owner != null) {
            return canonicalKeys.owner;
        }
        return freeze(source, false, false, reusableColumns);
    }

    /** Freezes first-occurrence membership in canonical renderer spatial order. */
    public static PackedSectionMembership canonicalDistinct(Collection<SectionKey> source) {
        return canonicalDistinct(source, null);
    }

    public static PackedSectionMembership canonicalDistinct(
            Collection<SectionKey> source,
            PackedSectionMembership reusableColumns
    ) {
        Objects.requireNonNull(source, "source");
        if (source instanceof PackedSectionMembership packed && packed.canonicalOrder) {
            return packed;
        }
        if (source instanceof PackedSectionCanonicalOrder canonicalKeys
                && canonicalKeys.owner != null
                && canonicalKeys.owner.canonicalOrder) {
            return canonicalKeys.owner;
        }
        if (reusableColumns == null) {
            return freeze(source, true, true, null);
        }
        Builder builder = CANONICAL_BUILDER.get().reset(source.size());
        for (SectionKey key : source) {
            SectionKey nonNullKey = Objects.requireNonNull(key, "section membership key");
            builder.addPacked(packSection(nonNullKey));
        }
        return builder.buildCanonical(reusableColumns);
    }

    private static PackedSectionMembership freeze(
            Collection<SectionKey> source,
            boolean discardDuplicates,
            boolean canonical,
            PackedSectionMembership reusableColumns
    ) {
        if (source.isEmpty()) {
            return EMPTY;
        }
        SectionKey[] copied = new SectionKey[source.size()];
        int copiedSize = 0;
        LongOpenHashSet packed = new LongOpenHashSet(source.size());
        for (SectionKey key : source) {
            SectionKey nonNullKey = Objects.requireNonNull(key, "section membership key");
            if (!packed.add(packSection(nonNullKey))) {
                if (discardDuplicates) {
                    continue;
                }
                throw new IllegalArgumentException("section membership must not contain duplicates: " + nonNullKey);
            }
            copied[copiedSize++] = nonNullKey;
        }
        if (copiedSize == 0) {
            return EMPTY;
        }
        if (copiedSize != copied.length) {
            copied = Arrays.copyOf(copied, copiedSize);
        }
        if (reusableColumns != null && copiedSize == reusableColumns.size()) {
            boolean unchanged = canonical && reusableColumns.canonicalOrder;
            if (unchanged) {
                for (SectionKey key : copied) {
                    if (!reusableColumns.containsPacked(packSection(key))) {
                        unchanged = false;
                        break;
                    }
                }
            } else if (!canonical && !reusableColumns.canonicalOrder) {
                unchanged = true;
                for (int index = 0; index < copied.length; index++) {
                    if (!copied[index].equals(reusableColumns.keyArray.get(index))) {
                        unchanged = false;
                        break;
                    }
                }
            }
            if (unchanged) {
                return reusableColumns;
            }
        }
        if (canonical) {
            Arrays.sort(copied, CANONICAL_ORDER);
            if (reusableColumns != null && reusableColumns.canonicalOrder) {
                int previousIndex = 0;
                for (int index = 0; index < copied.length; index++) {
                    SectionKey key = copied[index];
                    while (previousIndex < reusableColumns.keyArray.size()
                            && CANONICAL_ORDER.compare(
                                    reusableColumns.keyArray.get(previousIndex), key
                            ) < 0) {
                        previousIndex++;
                    }
                    if (previousIndex < reusableColumns.keyArray.size()
                            && reusableColumns.keyArray.get(previousIndex).equals(key)) {
                        copied[index] = reusableColumns.keyArray.get(previousIndex);
                    }
                }
            }
        }
        PackedSectionIndex index = reusableColumns != null && canonical
                ? PackedSectionIndex.delta(reusableColumns, copied)
                : PackedSectionIndex.base(packed);
        return new PackedSectionMembership(copied, index, canonical, reusableColumns);
    }

    /** Starts a primitive section-node publication with no intermediate SectionKey collection. */
    public static Builder builder(int expectedSections) {
        return new Builder(expectedSections);
    }

    /** Filters one publication without rebuilding identities retained by the result. */
    public PackedSectionMembership intersect(Set<SectionKey> retainedSections) {
        Objects.requireNonNull(retainedSections, "retainedSections");
        if (isEmpty() || retainedSections.isEmpty()) {
            return EMPTY;
        }
        SectionKey[] retained = null;
        int retainedSize = 0;
        for (int index = 0; index < keyArray.size(); index++) {
            SectionKey key = keyArray.get(index);
            if (retainedSections.contains(key)) {
                if (retained != null) {
                    retained[retainedSize++] = key;
                }
            } else if (retained == null) {
                retained = new SectionKey[keyArray.size() - 1];
                for (int copiedIndex = 0; copiedIndex < index; copiedIndex++) {
                    retained[copiedIndex] = keyArray.get(copiedIndex);
                }
                retainedSize = index;
            }
        }
        if (retained == null) {
            return this;
        }
        if (retainedSize == 0) {
            return EMPTY;
        }
        if (retainedSize != retained.length) {
            retained = Arrays.copyOf(retained, retainedSize);
        }
        LongOpenHashSet packed = new LongOpenHashSet(retainedSize);
        for (SectionKey key : retained) {
            packed.add(packSection(key));
        }
        return new PackedSectionMembership(
                retained,
                canonicalOrder
                        ? PackedSectionIndex.delta(this, retained)
                        : PackedSectionIndex.base(packed),
                canonicalOrder,
                this
        );
    }

    public List<SectionKey> orderedKeys() {
        return orderedKeys;
    }

    PackedSectionCanonicalOrder canonicalKeysStorage() {
        return keyArray;
    }

    PackedSectionIndex membershipIndexStorage() {
        return membershipIndex;
    }

    PackedSectionColumnIndex columnIndexStorage() {
        return columnIndex;
    }

    PackedSectionTransition transitionStorage() {
        return transition;
    }

    /** Returns the canonical position owned by this publication without a generic-list fallback. */
    public int canonicalIndex(SectionKey key) {
        Objects.requireNonNull(key, "key");
        if (!canonicalOrder) {
            throw new IllegalStateException("canonical index requires canonical membership");
        }
        return keyArray.indexOfCanonical(key);
    }

    public boolean canonicalOrder() {
        return canonicalOrder;
    }

    /** Immutable primitive-backed view of the chunk columns represented by this publication. */
    public Set<ChunkKey> chunkColumns() {
        return columnIndex.chunkColumns;
    }

    /** Immutable map view used by extraction workers to select a column's vertical section ticket. */
    public Map<ChunkKey, Set<SectionKey>> sectionsByChunk() {
        return columnIndex.sectionsByChunk;
    }

    public Set<SectionKey> sectionsInChunk(ChunkKey chunkKey) {
        Objects.requireNonNull(chunkKey, "chunkKey");
        return sectionsInChunk(chunkKey.x(), chunkKey.z());
    }

    /** Fixed-cost primitive column lookup for camera-ring probes that do not own a ChunkKey. */
    public Set<SectionKey> sectionsInChunk(int chunkX, int chunkZ) {
        Set<SectionKey> sections = columnIndex.column(packChunk(chunkX, chunkZ));
        return sections == null ? Set.of() : sections;
    }

    public boolean containsChunk(ChunkKey chunkKey) {
        Objects.requireNonNull(chunkKey, "chunkKey");
        return containsChunk(chunkKey.x(), chunkKey.z());
    }

    /** Fixed-cost primitive counterpart to {@link #containsChunk(ChunkKey)}. */
    public boolean containsChunk(int chunkX, int chunkZ) {
        return columnIndex.containsColumn(packChunk(chunkX, chunkZ));
    }

    @Override
    public Iterator<SectionKey> iterator() {
        return orderedKeys.iterator();
    }

    @Override
    public int size() {
        return orderedKeys.size();
    }

    @Override
    public boolean contains(Object candidate) {
        return candidate instanceof SectionKey key && membershipIndex.contains(packSection(key));
    }

    @Override
    public boolean containsAll(Collection<?> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        if (candidates == this) {
            return true;
        }
        if (candidates.size() > size()) {
            return false;
        }
        for (Object candidate : candidates) {
            if (!contains(candidate)) {
                return false;
            }
        }
        return true;
    }

    public boolean containsPacked(long packedSection) {
        return membershipIndex.contains(packedSection);
    }

    /**
     * Publishes one canonical membership addition with structural sharing.
     *
     * <p>Lifecycle owners already know the exact key whose state changed. Re-scanning and
     * re-hashing their complete slot table to publish that fact turns gradual warmup into
     * quadratic allocation. This operation copies only affected spatial, membership and column
     * pages while retaining every unrelated page by identity.</p>
     */
    public PackedSectionMembership withAdded(SectionKey sectionKey) {
        Objects.requireNonNull(sectionKey, "sectionKey");
        long packed = packSection(sectionKey);
        if (containsPacked(packed)) {
            return this;
        }
        LongOpenHashSet entered = new LongOpenHashSet(1);
        entered.add(packed);
        long[] enteredPacked = new long[]{packed};
        PackedSectionCanonicalOrder nextKeys = PackedSectionCanonicalOrder.delta(keyArray, enteredPacked, null);
        LongOpenHashSet affectedColumns = new LongOpenHashSet(1);
        affectedColumns.add(packChunk(sectionKey.x(), sectionKey.z()));
        return new PackedSectionMembership(
                nextKeys,
                PackedSectionIndex.delta(membershipIndex, entered, null),
                true,
                this,
                affectedColumns,
                enteredPacked,
                EMPTY_LONGS
        );
    }

    /** Publishes one canonical membership removal with the same COW ownership contract. */
    public PackedSectionMembership without(SectionKey sectionKey) {
        Objects.requireNonNull(sectionKey, "sectionKey");
        long packed = packSection(sectionKey);
        if (!containsPacked(packed)) {
            return this;
        }
        LongOpenHashSet exited = new LongOpenHashSet(1);
        exited.add(packed);
        PackedSectionCanonicalOrder nextKeys = PackedSectionCanonicalOrder.delta(keyArray, EMPTY_LONGS, exited);
        if (nextKeys.isEmpty()) {
            return EMPTY;
        }
        LongOpenHashSet affectedColumns = new LongOpenHashSet(1);
        affectedColumns.add(packChunk(sectionKey.x(), sectionKey.z()));
        return new PackedSectionMembership(
                nextKeys,
                PackedSectionIndex.delta(membershipIndex, null, exited),
                true,
                this,
                affectedColumns,
                EMPTY_LONGS,
                new long[]{packed}
        );
    }

    /**
     * Publishes one exact lifecycle delta without reconstructing the complete membership.
     *
     * <p>The caller already owns authoritative entered/retired facts. Re-enumerating every
     * retained section to rediscover that delta defeats persistent-scene ownership and creates
     * humongous hash-table allocations as the camera window grows. This path validates the
     * producer contract, then updates only affected canonical, lookup, and chunk-column pages.</p>
     */
    public PackedSectionMembership withDelta(
            Collection<SectionKey> enteredSections,
            Collection<SectionKey> retiredSections
    ) {
        Objects.requireNonNull(enteredSections, "enteredSections");
        Objects.requireNonNull(retiredSections, "retiredSections");
        if (enteredSections.isEmpty() && retiredSections.isEmpty()) {
            return this;
        }

        LongOpenHashSet entered = enteredSections.isEmpty()
                ? null
                : new LongOpenHashSet(enteredSections.size());
        long[] enteredPacked = new long[enteredSections.size()];
        LongOpenHashSet affectedColumns = new LongOpenHashSet(
                enteredSections.size() + retiredSections.size()
        );
        int enteredIndex = 0;
        for (SectionKey key : enteredSections) {
            Objects.requireNonNull(key, "entered section");
            long packed = packSection(key);
            if (containsPacked(packed) || !entered.add(packed)) {
                throw new IllegalArgumentException("entered section is already present or duplicated: " + key);
            }
            enteredPacked[enteredIndex++] = packed;
            affectedColumns.add(packChunk(key.x(), key.z()));
        }

        LongOpenHashSet exited = retiredSections.isEmpty()
                ? null
                : new LongOpenHashSet(retiredSections.size());
        for (SectionKey key : retiredSections) {
            Objects.requireNonNull(key, "retired section");
            long packed = packSection(key);
            if (!containsPacked(packed) || !exited.add(packed)) {
                throw new IllegalArgumentException("retired section is absent or duplicated: " + key);
            }
            if (entered != null && entered.contains(packed)) {
                throw new IllegalArgumentException("section cannot enter and retire in one publication: " + key);
            }
            affectedColumns.add(packChunk(key.x(), key.z()));
        }

        LongArrays.quickSort(enteredPacked, PackedSectionMembership::comparePackedSections);
        PackedSectionCanonicalOrder nextKeys = PackedSectionCanonicalOrder.delta(keyArray, enteredPacked, exited);
        int expectedSize = Math.addExact(size(), enteredPacked.length) - retiredSections.size();
        if (nextKeys.size() != expectedSize) {
            throw new IllegalStateException("persistent section delta produced inconsistent membership");
        }
        if (nextKeys.isEmpty()) {
            return EMPTY;
        }
        return new PackedSectionMembership(
                nextKeys,
                PackedSectionIndex.delta(membershipIndex, entered, exited),
                true,
                this,
                affectedColumns,
                enteredPacked,
                sortedPacked(exited)
        );
    }

    /**
     * Proves an already-validated subset relation across one immutable generation transition.
     *
     * <p>This is the renderer equivalent of GPUScene generation ownership: the previous relation
     * remains true unless the subset enters a key absent from the new superset, or the superset
     * retires a key still present in the new subset. The proof therefore touches only publication
     * deltas. Collections assembled outside this publication chain cannot use this fast path and
     * must still receive a complete validation at their ownership boundary.</p>
     */
    public boolean provesSubsetTransition(
            PackedSectionMembership superset,
            PackedSectionMembership previousSubset,
            PackedSectionMembership previousSuperset
    ) {
        Objects.requireNonNull(superset, "superset");
        Objects.requireNonNull(previousSubset, "previousSubset");
        Objects.requireNonNull(previousSuperset, "previousSuperset");
        if (this == superset) {
            return true;
        }
        if (this != previousSubset && transition.predecessor() != previousSubset) {
            return false;
        }
        if (superset != previousSuperset && superset.transition.predecessor() != previousSuperset) {
            return false;
        }
        if (this != previousSubset) {
            for (long entered : transition.enteredSections()) {
                if (!superset.containsPacked(entered)) {
                    return false;
                }
            }
        }
        if (superset != previousSuperset) {
            for (long exited : superset.transition.exitedSections()) {
                if (containsPacked(exited)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Advances an exact intersection count across one immutable publication transition.
     *
     * <p>Both owners already publish entered/exited facts. Re-enumerating either complete set to
     * rediscover their overlap makes gradual BLAS admission quadratic in the foreground size.
     * Applying the two deltas in producer order preserves the exact count while touching only
     * changed section identities. A negative return means that either input did not descend
     * directly from the supplied publication, so an external caller must perform full validation.</p>
     */
    public int transitionIntersectionCount(
            PackedSectionMembership other,
            PackedSectionMembership previousThis,
            PackedSectionMembership previousOther,
            int previousIntersectionCount
    ) {
        Objects.requireNonNull(other, "other");
        Objects.requireNonNull(previousThis, "previousThis");
        Objects.requireNonNull(previousOther, "previousOther");
        if (previousIntersectionCount < 0
                || previousIntersectionCount > Math.min(previousThis.size(), previousOther.size())) {
            throw new IllegalArgumentException("previous intersection count is outside publication bounds");
        }
        if ((this != previousThis && transition.predecessor() != previousThis)
                || (other != previousOther && other.transition.predecessor() != previousOther)) {
            return -1;
        }

        int nextCount = previousIntersectionCount;
        if (this != previousThis) {
            for (long exited : transition.exitedSections()) {
                if (previousOther.containsPacked(exited)) {
                    nextCount--;
                }
            }
            for (long entered : transition.enteredSections()) {
                if (previousOther.containsPacked(entered)) {
                    nextCount++;
                }
            }
        }
        if (other != previousOther) {
            for (long exited : other.transition.exitedSections()) {
                if (containsPacked(exited)) {
                    nextCount--;
                }
            }
            for (long entered : other.transition.enteredSections()) {
                if (containsPacked(entered)) {
                    nextCount++;
                }
            }
        }
        if (nextCount < 0 || nextCount > Math.min(size(), other.size())) {
            throw new IllegalStateException("publication deltas produced an invalid intersection count");
        }
        return nextCount;
    }

    /** Returns a canonical index without traversing the publication as a generic list. */
    public static int canonicalIndex(List<SectionKey> keys, SectionKey key) {
        Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(key, "key");
        return keys instanceof PackedSectionCanonicalOrder canonicalKeys
                ? canonicalKeys.indexOfCanonical(key)
                : Integer.MIN_VALUE;
    }

    /**
     * Primitive visibility builder. Section identities are materialized only after a real
     * membership delta, and retained coordinates reuse the preceding canonical identities.
     */
    public static final class Builder {
        private long[] packedSections;
        private final LongOpenHashSet membership;
        private int size;
        private boolean built;

        private Builder(int expectedSections) {
            if (expectedSections < 0) {
                throw new IllegalArgumentException("expected section count must not be negative");
            }
            packedSections = new long[expandedCapacity(0, expectedSections)];
            membership = new LongOpenHashSet(expectedSections);
        }

        public Builder reset(int expectedSections) {
            if (expectedSections < 0) {
                throw new IllegalArgumentException("expected section count must not be negative");
            }
            membership.clear();
            size = 0;
            built = false;
            if (expectedSections > packedSections.length) {
                packedSections = new long[expandedCapacity(packedSections.length, expectedSections)];
            }
            return this;
        }

        public boolean addPacked(long packedSection) {
            ensureMutable();
            if (!membership.add(packedSection)) {
                return false;
            }
            if (size == packedSections.length) {
                packedSections = Arrays.copyOf(
                        packedSections,
                        expandedCapacity(packedSections.length, Math.addExact(size, 1))
                );
            }
            packedSections[size++] = packedSection;
            return true;
        }

        private static int expandedCapacity(int currentCapacity, int requiredCapacity) {
            if (requiredCapacity <= currentCapacity) {
                return currentCapacity;
            }
            /*
             * Builders are persistent publication scratch, so an exact-size
             * resize converts gradual scene convergence into one new long[]
             * for every higher watermark. Power-of-two growth amortizes those
             * transitions while keeping the retained scratch bounded by less
             * than twice the observed stage membership.
             */
            int minimumCapacity = Math.max(16, requiredCapacity);
            int expanded = HashCommon.nextPowerOfTwo(minimumCapacity);
            if (expanded <= 0) {
                throw new IllegalArgumentException("section membership capacity is too large: " + requiredCapacity);
            }
            return expanded;
        }

        public boolean add(SectionKey sectionKey) {
            Objects.requireNonNull(sectionKey, "sectionKey");
            return addPacked(packSection(sectionKey));
        }

        public int size() {
            return size;
        }

        public PackedSectionMembership buildCanonical(PackedSectionMembership previous) {
            ensureMutable();
            built = true;
            if (size == 0) {
                return EMPTY;
            }
            if (previous != null && previous.canonicalOrder && size == previous.size()) {
                boolean unchanged = true;
                for (int index = 0; index < size; index++) {
                    if (!previous.containsPacked(packedSections[index])) {
                        unchanged = false;
                        break;
                    }
                }
                if (unchanged) {
                    return previous;
                }
            }

            if (previous == null || !previous.canonicalOrder) {
                long[] canonicalPacked = size == packedSections.length
                        ? packedSections
                        : Arrays.copyOf(packedSections, size);
                LongArrays.quickSort(canonicalPacked, PackedSectionMembership::comparePackedSections);
                SectionKey[] canonicalKeys = new SectionKey[size];
                for (int index = 0; index < size; index++) {
                    long packed = canonicalPacked[index];
                    canonicalKeys[index] = new SectionKey(
                            SectionKey.unpackX(packed), SectionKey.unpackY(packed), SectionKey.unpackZ(packed)
                    );
                }
                return new PackedSectionMembership(
                        canonicalKeys,
                        PackedSectionIndex.baseFromKeys(PackedSectionCanonicalOrder.base(canonicalKeys)),
                        true,
                        null
                );
            }

            int enteredCount = 0;
            for (int index = 0; index < size; index++) {
                if (!previous.containsPacked(packedSections[index])) {
                    enteredCount++;
                }
            }
            long[] enteredPacked = new long[enteredCount];
            LongOpenHashSet entered = enteredCount == 0 ? null : new LongOpenHashSet(enteredCount);
            int enteredIndex = 0;
            for (int index = 0; index < size; index++) {
                long packed = packedSections[index];
                if (!previous.containsPacked(packed)) {
                    enteredPacked[enteredIndex++] = packed;
                    entered.add(packed);
                }
            }
            LongArrays.quickSort(enteredPacked, PackedSectionMembership::comparePackedSections);

            int exitedCount = previous.size() + enteredCount - size;
            LongOpenHashSet exited = exitedCount == 0 ? null : new LongOpenHashSet(exitedCount);
            if (exited != null) {
                for (SectionKey key : previous.keyArray) {
                    long packed = packSection(key);
                    if (!membership.contains(packed)) {
                        exited.add(packed);
                    }
                }
            }

            PackedSectionCanonicalOrder canonicalKeys =
                    PackedSectionCanonicalOrder.delta(previous.keyArray, enteredPacked, exited);
            if (canonicalKeys.size() != size) {
                throw new IllegalStateException("persistent section merge produced inconsistent membership");
            }
            LongOpenHashSet affectedColumns = new LongOpenHashSet(
                    (entered == null ? 0 : entered.size()) + (exited == null ? 0 : exited.size())
            );
            if (entered != null) {
                LongIterator iterator = entered.iterator();
                while (iterator.hasNext()) {
                    long packed = iterator.nextLong();
                    affectedColumns.add(packChunk(SectionKey.unpackX(packed), SectionKey.unpackZ(packed)));
                }
            }
            if (exited != null) {
                LongIterator iterator = exited.iterator();
                while (iterator.hasNext()) {
                    long packed = iterator.nextLong();
                    affectedColumns.add(packChunk(SectionKey.unpackX(packed), SectionKey.unpackZ(packed)));
                }
            }
            return new PackedSectionMembership(
                    canonicalKeys,
                    PackedSectionIndex.delta(previous.membershipIndex, entered, exited),
                    true,
                    previous,
                    affectedColumns,
                    enteredPacked,
                    sortedPacked(exited)
            );
        }

        private void ensureMutable() {
            if (built) {
                throw new IllegalStateException("packed section builder already published");
            }
        }
    }

    static int comparePackedSections(long left, long right) {
        int comparison = Integer.compare(SectionKey.unpackX(left), SectionKey.unpackX(right));
        if (comparison != 0) {
            return comparison;
        }
        comparison = Integer.compare(SectionKey.unpackY(left), SectionKey.unpackY(right));
        return comparison != 0
                ? comparison
                : Integer.compare(SectionKey.unpackZ(left), SectionKey.unpackZ(right));
    }

    static int compareSectionToPacked(SectionKey left, long right) {
        int comparison = Integer.compare(left.x(), SectionKey.unpackX(right));
        if (comparison != 0) {
            return comparison;
        }
        comparison = Integer.compare(left.y(), SectionKey.unpackY(right));
        return comparison != 0 ? comparison : Integer.compare(left.z(), SectionKey.unpackZ(right));
    }

    static SectionKey keyFromPacked(long packed) {
        return SectionKey.fromPacked(packed);
    }

    static long[] sortedPacked(LongOpenHashSet keys) {
        if (keys == null || keys.isEmpty()) {
            return EMPTY_LONGS;
        }
        long[] packed = keys.toLongArray();
        LongArrays.quickSort(packed, PackedSectionMembership::comparePackedSections);
        return packed;
    }

    static long packSection(SectionKey key) {
        return key.packed();
    }

    static long packChunk(int x, int z) {
        return ((long) x << 32) | Integer.toUnsignedLong(z);
    }

    static int comparePackedChunks(long left, long right) {
        int comparison = Integer.compare((int) (left >> 32), (int) (right >> 32));
        return comparison != 0 ? comparison : Integer.compare((int) left, (int) right);
    }

    static ChunkKey unpackChunk(long packed) {
        return new ChunkKey((int) (packed >> 32), (int) packed);
    }

}
