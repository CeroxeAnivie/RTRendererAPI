package top.ceroxe.rt.renderer.scene;

import java.util.*;

/**
 * One immutable publication of renderer-owned terrain lifetime.
 *
 * <p>Chunk membership and live ray geometry deliberately share one revision.
 * Consumers may use the deltas for an in-order fast path, but the full sets are
 * always authoritative so a skipped bridge publication can be reconciled
 * without replaying stale intermediate state.</p>
 *
 * @param revision           单调递增的地形驻留修订号
 * @param residentChunks     当前驻留的全部区块
 * @param geometrySections   当前拥有可追踪几何的全部区段
 * @param geometryMembership {@code geometrySections} 的规范化紧凑索引
 * @param enteredChunks      本次发布中新进入驻留集的区块
 * @param retiredChunks      本次发布中离开驻留集的区块
 * @param enteredSections    本次发布中新进入几何集的区段
 * @param retiredSections    本次发布中离开几何集的区段
 */
public record TerrainResidencySnapshot(
        long revision,
        Set<ChunkKey> residentChunks,
        Set<SectionKey> geometrySections,
        PackedSectionMembership geometryMembership,
        Set<ChunkKey> enteredChunks,
        Set<ChunkKey> retiredChunks,
        Set<SectionKey> enteredSections,
        Set<SectionKey> retiredSections
) {
    /**
     * 从普通几何集合创建驻留快照，并生成规范化紧凑索引。
     *
     * @param revision         单调递增的修订号
     * @param residentChunks   当前驻留的全部区块
     * @param geometrySections 当前拥有几何的全部区段
     * @param enteredChunks    新进入驻留集的区块
     * @param retiredChunks    离开驻留集的区块
     * @param enteredSections  新进入几何集的区段
     * @param retiredSections  离开几何集的区段
     */
    public TerrainResidencySnapshot(
            long revision,
            Set<ChunkKey> residentChunks,
            Set<SectionKey> geometrySections,
            Set<ChunkKey> enteredChunks,
            Set<ChunkKey> retiredChunks,
            Set<SectionKey> enteredSections,
            Set<SectionKey> retiredSections
    ) {
        this(
                revision,
                residentChunks,
                geometrySections,
                PackedSectionMembership.canonicalDistinct(geometrySections),
                enteredChunks,
                retiredChunks,
                enteredSections,
                retiredSections
        );
    }

    /**
     * Freezes authoritative sets and validates full-state/delta consistency.
     */
    public TerrainResidencySnapshot {
        if (revision < 0L) {
            throw new IllegalArgumentException("terrain residency revision must not be negative");
        }
        residentChunks = residentChunks instanceof ChunkMembershipSet
                ? residentChunks
                : immutableNonNullSet(residentChunks, "residentChunks");
        geometrySections = geometrySections instanceof ChunkGeometrySet
                || geometrySections instanceof PackedSectionMembership
                ? geometrySections
                : immutableNonNullSet(geometrySections, "geometrySections");
        geometryMembership = Objects.requireNonNull(geometryMembership, "geometryMembership");
        if (!geometryMembership.canonicalOrder()
                || geometryMembership.size() != geometrySections.size()
                || (!(geometrySections instanceof ChunkGeometrySet)
                && !geometryMembership.equals(geometrySections))) {
            throw new IllegalArgumentException(
                    "packed geometry membership must canonically represent the complete geometry authority"
            );
        }
        enteredChunks = immutableNonNullSet(enteredChunks, "enteredChunks");
        retiredChunks = immutableNonNullSet(retiredChunks, "retiredChunks");
        enteredSections = immutableNonNullSet(enteredSections, "enteredSections");
        retiredSections = immutableNonNullSet(retiredSections, "retiredSections");

        requireDisjoint(enteredChunks, retiredChunks, "chunk deltas");
        requireDisjoint(enteredSections, retiredSections, "section deltas");
        if (!residentChunks.containsAll(enteredChunks)) {
            throw new IllegalArgumentException("entered chunks must belong to the published resident set");
        }
        if (!geometryMembership.containsAll(enteredSections)) {
            throw new IllegalArgumentException("entered sections must belong to the published geometry set");
        }
        if (!disjoint(residentChunks, retiredChunks)) {
            throw new IllegalArgumentException("retired chunks must not remain resident");
        }
        if (!disjoint(geometryMembership, retiredSections)) {
            throw new IllegalArgumentException("retired sections must not remain live geometry");
        }
        if (!(geometrySections instanceof ChunkGeometrySet)) {
            for (SectionKey key : geometrySections) {
                if (!residentChunks.contains(key.chunkKey())) {
                    throw new IllegalArgumentException("geometry section is outside resident terrain: " + key);
                }
            }
        }
    }

    /**
     * Freezes geometry by chunk, reusing each already-immutable per-chunk set.
     * A 32-chunk view owns tens of thousands of sections but only a few
     * thousand columns; copying one hash entry per section on every streaming
     * publication was the dominant ACTIVE allocation and GC source.
     *
     * @param previous           上一份已发布快照，用于结构共享和增量校验
     * @param revision           新快照的修订号
     * @param residentChunks     新快照的权威区块驻留集
     * @param sectionsByChunk    按区块组织的权威几何区段
     * @param geometryMembership 新快照的规范化几何索引
     * @param enteredChunks      新进入驻留集的区块
     * @param retiredChunks      离开驻留集的区块
     * @param enteredSections    新进入几何集的区段
     * @param retiredSections    离开几何集的区段
     * @return 已校验且可安全发布的不可变快照
     */
    public static TerrainResidencySnapshot fromChunkGeometry(
            TerrainResidencySnapshot previous,
            long revision,
            Set<ChunkKey> residentChunks,
            Map<ChunkKey, Set<SectionKey>> sectionsByChunk,
            PackedSectionMembership geometryMembership,
            Set<ChunkKey> enteredChunks,
            Set<ChunkKey> retiredChunks,
            Set<SectionKey> enteredSections,
            Set<SectionKey> retiredSections
    ) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(geometryMembership, "geometryMembership");
        Set<ChunkKey> immutableResidentChunks;
        if (enteredChunks.isEmpty() && retiredChunks.isEmpty()) {
            if (residentChunks.size() != previous.residentChunks().size()) {
                throw new IllegalArgumentException("unchanged chunk delta changed resident membership size");
            }
            /*
             * The coordinator's net chunk delta is the membership event. A
             * geometry-only publication must share the already-immutable chunk
             * authority instead of rebuilding a 32-distance SetN generation.
             */
            immutableResidentChunks = previous.residentChunks();
        } else {
            immutableResidentChunks = ChunkMembershipSet.copyOf(
                    previous.residentChunks(),
                    residentChunks,
                    enteredChunks,
                    retiredChunks
            );
        }
        boolean geometryUnchanged = enteredSections.isEmpty() && retiredSections.isEmpty();
        Set<SectionKey> geometrySections;
        PackedSectionMembership publishedGeometryMembership;
        if (geometryUnchanged) {
            if (!geometryMembership.equals(previous.geometryMembership())) {
                throw new IllegalArgumentException("unchanged geometry delta changed packed membership");
            }
            geometrySections = previous.geometrySections();
            publishedGeometryMembership = previous.geometryMembership();
        } else {
            geometrySections = ChunkGeometrySet.copyOf(
                    previous.geometrySections(),
                    immutableResidentChunks,
                    sectionsByChunk,
                    enteredChunks,
                    retiredChunks,
                    enteredSections,
                    retiredSections
            );
            publishedGeometryMembership = geometryMembership;
        }
        return new TerrainResidencySnapshot(
                revision,
                immutableResidentChunks,
                geometrySections,
                publishedGeometryMembership,
                enteredChunks,
                retiredChunks,
                enteredSections,
                retiredSections
        );
    }

    /**
     * 校验并冻结由普通集合表达的外部几何权威状态。
     *
     * @param previous        上一份已发布快照
     * @param revision        新快照的修订号
     * @param residentChunks  新快照的权威区块驻留集
     * @param sectionsByChunk 按区块组织的权威几何区段
     * @param enteredChunks   新进入驻留集的区块
     * @param retiredChunks   离开驻留集的区块
     * @param enteredSections 新进入几何集的区段
     * @param retiredSections 离开几何集的区段
     * @return 已校验且可安全发布的不可变快照
     */
    public static TerrainResidencySnapshot fromChunkGeometry(
            TerrainResidencySnapshot previous,
            long revision,
            Set<ChunkKey> residentChunks,
            Map<ChunkKey, Set<SectionKey>> sectionsByChunk,
            Set<ChunkKey> enteredChunks,
            Set<ChunkKey> retiredChunks,
            Set<SectionKey> enteredSections,
            Set<SectionKey> retiredSections
    ) {
        Set<SectionKey> authoritativeGeometry = new java.util.LinkedHashSet<>();
        for (ChunkKey residentChunk : residentChunks) {
            authoritativeGeometry.addAll(sectionsByChunk.getOrDefault(residentChunk, Set.of()));
        }
        return fromChunkGeometry(
                previous,
                revision,
                residentChunks,
                sectionsByChunk,
                PackedSectionMembership.canonicalDistinct(
                        authoritativeGeometry,
                        previous.geometryMembership()
                ),
                enteredChunks,
                retiredChunks,
                enteredSections,
                retiredSections
        );
    }

    /**
     * 返回修订号为零且不包含任何驻留内容的初始快照。
     *
     * @return 空驻留快照
     */
    public static TerrainResidencySnapshot empty() {
        return new TerrainResidencySnapshot(
                0L,
                ChunkMembershipSet.empty(),
                Set.of(),
                PackedSectionMembership.empty(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of()
        );
    }

    private static <T> Set<T> immutableNonNullSet(Set<T> values, String name) {
        Objects.requireNonNull(values, name);
        for (T value : values) {
            /*
             * This validation is on the residency publication hot path. Build
             * the diagnostic string only for the exceptional value; eagerly
             * concatenating it once per healthy section dominated ACTIVE JFR
             * allocation without strengthening the invariant.
             */
            if (value == null) {
                throw new NullPointerException(name + " element");
            }
        }
        return Set.copyOf(values);
    }

    private static <T> void requireDisjoint(Set<T> left, Set<T> right, String name) {
        if (!disjoint(left, right)) {
            throw new IllegalArgumentException(name + " must be disjoint");
        }
    }

    private static <T> boolean disjoint(Set<T> left, Set<T> right) {
        Set<T> smaller = left.size() <= right.size() ? left : right;
        Set<T> larger = smaller == left ? right : left;
        for (T value : smaller) {
            if (larger.contains(value)) {
                return false;
            }
        }
        return true;
    }

    private static long packedChunkCoordinate(ChunkKey chunk) {
        return packedChunkCoordinate(chunk.x(), chunk.z());
    }

    private static long packedChunkCoordinate(int chunkX, int chunkZ) {
        return ((long) chunkX << 32)
                | ((chunkZ ^ Integer.MIN_VALUE) & 0xFFFF_FFFFL);
    }

    private static ChunkKey unpackedChunkCoordinate(long coordinate) {
        return new ChunkKey(
                (int) (coordinate >> 32),
                (int) coordinate ^ Integer.MIN_VALUE
        );
    }

    /**
     * 判断指定区块是否属于当前权威驻留集。
     *
     * @param chunkX 区块 X 坐标
     * @param chunkZ 区块 Z 坐标
     * @return 区块当前驻留时返回 {@code true}
     */
    public boolean containsResidentChunk(int chunkX, int chunkZ) {
        long coordinate = packedChunkCoordinate(chunkX, chunkZ);
        if (residentChunks instanceof ChunkMembershipSet membership) {
            return membership.containsCoordinate(coordinate);
        }
        for (ChunkKey chunk : residentChunks) {
            if (chunk.x() == chunkX && chunk.z() == chunkZ) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断本次发布是否包含区块驻留变化。
     *
     * @return 存在进入或退出的区块时返回 {@code true}
     */
    public boolean hasChunkMembershipDelta() {
        return !enteredChunks.isEmpty() || !retiredChunks.isEmpty();
    }

    /**
     * 判断本次发布是否包含几何区段变化。
     *
     * @return 存在进入或退出的几何区段时返回 {@code true}
     */
    public boolean hasGeometryDelta() {
        return !enteredSections.isEmpty() || !retiredSections.isEmpty();
    }

    /**
     * Immutable primitive chunk authority updated from the coordinator's net
     * delta. A streaming window changes a few columns at a time; rebuilding a
     * hash-based {@code SetN} for thousands of unchanged columns made
     * publication cost proportional to view distance instead of the change.
     */
    private static final class ChunkMembershipSet extends AbstractSet<ChunkKey> {
        private static final ChunkMembershipSet EMPTY = new ChunkMembershipSet(new long[0]);
        private final long[] coordinates;

        private ChunkMembershipSet(long[] coordinates) {
            this.coordinates = coordinates;
        }

        private static ChunkMembershipSet empty() {
            return EMPTY;
        }

        private static ChunkMembershipSet copyOf(
                Set<ChunkKey> previous,
                Set<ChunkKey> authoritative,
                Set<ChunkKey> entered,
                Set<ChunkKey> retired
        ) {
            Objects.requireNonNull(previous, "previous");
            Objects.requireNonNull(authoritative, "authoritative");
            Objects.requireNonNull(entered, "entered");
            Objects.requireNonNull(retired, "retired");
            if (!(previous instanceof ChunkMembershipSet membership)) {
                return rebuild(authoritative);
            }
            if (entered.isEmpty() && retired.isEmpty()) {
                if (membership.size() != authoritative.size()) {
                    throw new IllegalArgumentException("unchanged chunk delta changed resident membership size");
                }
                return membership;
            }

            long[] enteredCoordinates = sortedCoordinates(entered, "enteredChunks");
            long[] retiredCoordinates = sortedCoordinates(retired, "retiredChunks");
            for (ChunkKey chunk : entered) {
                if (!authoritative.contains(chunk)) {
                    throw new IllegalArgumentException("entered chunk is absent from resident authority: " + chunk);
                }
            }
            for (ChunkKey chunk : retired) {
                if (authoritative.contains(chunk)) {
                    throw new IllegalArgumentException("retired chunk remains in resident authority: " + chunk);
                }
            }
            for (long coordinate : enteredCoordinates) {
                if (Arrays.binarySearch(membership.coordinates, coordinate) >= 0) {
                    throw new IllegalArgumentException("entered chunk was already resident");
                }
            }
            for (long coordinate : retiredCoordinates) {
                if (Arrays.binarySearch(membership.coordinates, coordinate) < 0) {
                    throw new IllegalArgumentException("retired chunk was not resident");
                }
            }

            int expectedSize = Math.subtractExact(
                    Math.addExact(membership.coordinates.length, enteredCoordinates.length),
                    retiredCoordinates.length
            );
            if (expectedSize != authoritative.size()) {
                throw new IllegalArgumentException("chunk delta does not reconcile resident authority size");
            }
            long[] result = new long[expectedSize];
            int previousIndex = 0;
            int enteredIndex = 0;
            int retiredIndex = 0;
            int resultIndex = 0;
            while (previousIndex < membership.coordinates.length || enteredIndex < enteredCoordinates.length) {
                while (previousIndex < membership.coordinates.length
                        && retiredIndex < retiredCoordinates.length
                        && membership.coordinates[previousIndex] == retiredCoordinates[retiredIndex]) {
                    previousIndex++;
                    retiredIndex++;
                }
                boolean hasPrevious = previousIndex < membership.coordinates.length;
                boolean hasEntered = enteredIndex < enteredCoordinates.length;
                if (!hasPrevious && !hasEntered) {
                    break;
                }
                if (!hasPrevious) {
                    result[resultIndex++] = enteredCoordinates[enteredIndex++];
                    continue;
                }
                if (!hasEntered) {
                    result[resultIndex++] = membership.coordinates[previousIndex++];
                    continue;
                }
                long previousCoordinate = membership.coordinates[previousIndex];
                long enteredCoordinate = enteredCoordinates[enteredIndex];
                if (enteredCoordinate < previousCoordinate) {
                    result[resultIndex++] = enteredCoordinate;
                    enteredIndex++;
                } else if (previousCoordinate < enteredCoordinate) {
                    result[resultIndex++] = previousCoordinate;
                    previousIndex++;
                } else {
                    throw new IllegalArgumentException("chunk delta produced duplicate resident coordinate");
                }
            }
            if (resultIndex != expectedSize || retiredIndex != retiredCoordinates.length) {
                throw new IllegalArgumentException("chunk delta did not produce the expected resident authority");
            }
            return result.length == 0 ? EMPTY : new ChunkMembershipSet(result);
        }

        private static ChunkMembershipSet rebuild(Set<ChunkKey> chunks) {
            long[] coordinates = sortedCoordinates(chunks, "residentChunks");
            return coordinates.length == 0 ? EMPTY : new ChunkMembershipSet(coordinates);
        }

        private static long[] sortedCoordinates(Set<ChunkKey> chunks, String name) {
            long[] coordinates = new long[chunks.size()];
            int index = 0;
            for (ChunkKey chunk : chunks) {
                if (chunk == null) {
                    throw new NullPointerException(name + " element");
                }
                coordinates[index++] = packedChunkCoordinate(chunk);
            }
            Arrays.sort(coordinates);
            for (int duplicateIndex = 1; duplicateIndex < coordinates.length; duplicateIndex++) {
                if (coordinates[duplicateIndex - 1] == coordinates[duplicateIndex]) {
                    throw new IllegalArgumentException(name + " contains duplicate chunk coordinates");
                }
            }
            return coordinates;
        }

        @Override
        public Iterator<ChunkKey> iterator() {
            return new Iterator<>() {
                private int index;

                @Override
                public boolean hasNext() {
                    return index < coordinates.length;
                }

                @Override
                public ChunkKey next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    return unpackedChunkCoordinate(coordinates[index++]);
                }
            };
        }

        @Override
        public int size() {
            return coordinates.length;
        }

        @Override
        public boolean contains(Object candidate) {
            return candidate instanceof ChunkKey chunk
                    && Arrays.binarySearch(coordinates, packedChunkCoordinate(chunk)) >= 0;
        }

        private boolean containsCoordinate(long coordinate) {
            return Arrays.binarySearch(coordinates, coordinate) >= 0;
        }
    }

    /**
     * Immutable set facade backed by structurally shared chunk buckets.
     *
     * <p>Chunk-window movement and block edits normally touch only a handful of columns. A flat
     * {@code Set[]} made every publication copy one reference for every resident chunk anyway.
     * Fixed hash buckets make the immutable publication cost proportional to changed columns: the
     * small bucket table and only affected buckets are copied, while all other per-chunk identities
     * remain shared with the predecessor generation.</p>
     */
    private static final class ChunkGeometrySet extends AbstractSet<SectionKey> {
        private static final int BUCKET_COUNT = 256;
        private static final int BUCKET_MASK = BUCKET_COUNT - 1;
        private static final GeometryBucket EMPTY_BUCKET = new GeometryBucket(new long[0], newSectionArray(0));

        private final GeometryBucket[] buckets;
        private final int size;

        private ChunkGeometrySet(GeometryBucket[] buckets, int size) {
            this.buckets = buckets;
            this.size = size;
        }

        private static ChunkGeometrySet copyOf(
                Set<SectionKey> previousGeometry,
                Set<ChunkKey> residentChunks,
                Map<ChunkKey, Set<SectionKey>> source,
                Set<ChunkKey> enteredChunks,
                Set<ChunkKey> retiredChunks,
                Set<SectionKey> enteredSections,
                Set<SectionKey> retiredSections
        ) {
            Objects.requireNonNull(previousGeometry, "previousGeometry");
            Objects.requireNonNull(source, "sectionsByChunk");
            LinkedHashSet<ChunkKey> changedChunks = new LinkedHashSet<>();
            if (previousGeometry instanceof ChunkGeometrySet previous) {
                changedChunks.addAll(enteredChunks);
                changedChunks.addAll(retiredChunks);
                enteredSections.forEach(section -> changedChunks.add(section.chunkKey()));
                retiredSections.forEach(section -> changedChunks.add(section.chunkKey()));
                if (residentChunks instanceof ChunkMembershipSet membership) {
                    return updateFromMembership(previous, membership, source, changedChunks);
                }
            }
            if (residentChunks instanceof ChunkMembershipSet membership) {
                return rebuildFromMembership(membership, source);
            }
            return rebuild(residentChunks, source);
        }

        private static ChunkGeometrySet rebuildFromMembership(
                ChunkMembershipSet membership,
                Map<ChunkKey, Set<SectionKey>> source
        ) {
            GeometryBucket[] buckets = emptyBuckets();
            long sectionCount = 0L;
            for (long coordinate : membership.coordinates) {
                Set<SectionKey> sections = snapshotChunkSections(unpackedChunkCoordinate(coordinate), source);
                int bucketIndex = bucketIndex(coordinate);
                buckets[bucketIndex] = buckets[bucketIndex].with(coordinate, sections);
                sectionCount += sections.size();
            }
            return new ChunkGeometrySet(buckets, checkedSectionCount(sectionCount));
        }

        private static ChunkGeometrySet updateFromMembership(
                ChunkGeometrySet previous,
                ChunkMembershipSet membership,
                Map<ChunkKey, Set<SectionKey>> source,
                Set<ChunkKey> changedChunks
        ) {
            long[] changedCoordinates = ChunkMembershipSet.sortedCoordinates(changedChunks, "changedChunks");
            if (changedCoordinates.length == 0) {
                return previous;
            }
            GeometryBucket[] updatedBuckets = previous.buckets.clone();
            long sectionCount = previous.size;
            for (long coordinate : changedCoordinates) {
                int bucketIndex = bucketIndex(coordinate);
                GeometryBucket bucket = updatedBuckets[bucketIndex];
                Set<SectionKey> oldSections = bucket.sections(coordinate);
                Set<SectionKey> newSections = membership.containsCoordinate(coordinate)
                        ? snapshotChunkSections(unpackedChunkCoordinate(coordinate), source)
                        : null;
                if (oldSections == null && newSections == null) {
                    throw new IllegalArgumentException("changed chunk is absent from both terrain generations");
                }
                sectionCount -= oldSections == null ? 0 : oldSections.size();
                sectionCount += newSections == null ? 0 : newSections.size();
                updatedBuckets[bucketIndex] = bucket.with(coordinate, newSections);
            }
            return new ChunkGeometrySet(updatedBuckets, checkedSectionCount(sectionCount));
        }

        private static ChunkGeometrySet rebuild(
                Set<ChunkKey> residentChunks,
                Map<ChunkKey, Set<SectionKey>> source
        ) {
            GeometryBucket[] buckets = emptyBuckets();
            long sectionCount = 0L;
            for (ChunkKey chunk : residentChunks) {
                long coordinate = packedChunkCoordinate(chunk);
                Set<SectionKey> sections = snapshotChunkSections(chunk, source);
                int bucketIndex = bucketIndex(coordinate);
                buckets[bucketIndex] = buckets[bucketIndex].with(coordinate, sections);
                sectionCount += sections.size();
            }
            return new ChunkGeometrySet(buckets, checkedSectionCount(sectionCount));
        }

        private static GeometryBucket[] emptyBuckets() {
            GeometryBucket[] buckets = new GeometryBucket[BUCKET_COUNT];
            Arrays.fill(buckets, EMPTY_BUCKET);
            return buckets;
        }

        private static int bucketIndex(long coordinate) {
            long mixed = coordinate;
            mixed ^= mixed >>> 33;
            mixed *= 0xff51afd7ed558ccdL;
            mixed ^= mixed >>> 33;
            return (int) mixed & BUCKET_MASK;
        }

        @SuppressWarnings("unchecked")
        private static Set<SectionKey>[] newSectionArray(int size) {
            return (Set<SectionKey>[]) new Set<?>[size];
        }

        private static int checkedSectionCount(long sectionCount) {
            if (sectionCount < 0L || sectionCount > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("terrain geometry section count exceeds integer capacity");
            }
            return (int) sectionCount;
        }

        private static Set<SectionKey> snapshotChunkSections(
                ChunkKey chunk,
                Map<ChunkKey, Set<SectionKey>> source
        ) {
            Set<SectionKey> sections = immutableNonNullSet(
                    source.getOrDefault(chunk, Set.of()),
                    "sectionsByChunk[" + chunk + ']'
            );
            for (SectionKey section : sections) {
                if (!chunk.equals(section.chunkKey())) {
                    throw new IllegalArgumentException(
                            "geometry section belongs to a different chunk: " + section
                    );
                }
            }
            return sections;
        }

        @Override
        public Iterator<SectionKey> iterator() {
            return new Iterator<>() {
                private int bucketIndex;
                private int chunkIndex;
                private Iterator<SectionKey> sections = Collections.emptyIterator();

                @Override
                public boolean hasNext() {
                    while (!sections.hasNext() && bucketIndex < buckets.length) {
                        GeometryBucket bucket = buckets[bucketIndex];
                        if (chunkIndex < bucket.sections.length) {
                            sections = bucket.sections[chunkIndex++].iterator();
                        } else {
                            bucketIndex++;
                            chunkIndex = 0;
                        }
                    }
                    return sections.hasNext();
                }

                @Override
                public SectionKey next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    return sections.next();
                }
            };
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public boolean contains(Object candidate) {
            if (!(candidate instanceof SectionKey section)) {
                return false;
            }
            long coordinate = packedChunkCoordinate(section.chunkKey());
            Set<SectionKey> sections = buckets[bucketIndex(coordinate)].sections(coordinate);
            return sections != null && sections.contains(section);
        }

        private static final class GeometryBucket {
            private final long[] coordinates;
            private final Set<SectionKey>[] sections;

            private GeometryBucket(long[] coordinates, Set<SectionKey>[] sections) {
                this.coordinates = coordinates;
                this.sections = sections;
            }

            private Set<SectionKey> sections(long coordinate) {
                int index = Arrays.binarySearch(coordinates, coordinate);
                return index < 0 ? null : sections[index];
            }

            private GeometryBucket with(long coordinate, Set<SectionKey> replacement) {
                int index = Arrays.binarySearch(coordinates, coordinate);
                if (index >= 0) {
                    if (replacement == null) {
                        long[] nextCoordinates = new long[coordinates.length - 1];
                        Set<SectionKey>[] nextSections = newSectionArray(sections.length - 1);
                        System.arraycopy(coordinates, 0, nextCoordinates, 0, index);
                        System.arraycopy(coordinates, index + 1, nextCoordinates, index, coordinates.length - index - 1);
                        System.arraycopy(sections, 0, nextSections, 0, index);
                        System.arraycopy(sections, index + 1, nextSections, index, sections.length - index - 1);
                        return nextCoordinates.length == 0
                                ? EMPTY_BUCKET
                                : new GeometryBucket(nextCoordinates, nextSections);
                    }
                    Set<SectionKey>[] nextSections = sections.clone();
                    nextSections[index] = replacement;
                    return new GeometryBucket(coordinates, nextSections);
                }
                if (replacement == null) {
                    return this;
                }
                int insertion = -index - 1;
                long[] nextCoordinates = new long[coordinates.length + 1];
                Set<SectionKey>[] nextSections = newSectionArray(sections.length + 1);
                System.arraycopy(coordinates, 0, nextCoordinates, 0, insertion);
                System.arraycopy(coordinates, insertion, nextCoordinates, insertion + 1, coordinates.length - insertion);
                System.arraycopy(sections, 0, nextSections, 0, insertion);
                System.arraycopy(sections, insertion, nextSections, insertion + 1, sections.length - insertion);
                nextCoordinates[insertion] = coordinate;
                nextSections[insertion] = replacement;
                return new GeometryBucket(nextCoordinates, nextSections);
            }
        }
    }
}
