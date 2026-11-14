package top.ceroxe.rt.renderer.scene;

import java.util.*;

/**
 * Immutable adapters over canonical and column-owned storage; never copies the publication set.
 */
final class PackedSectionViews {
    private PackedSectionViews() {
    }

    static Set<SectionKey> sectionColumn(SectionKey[] keys) {
        return new SectionColumnSet(keys);
    }

    static Set<ChunkKey> chunkColumns(PackedSectionColumnIndex columns) {
        return new ChunkColumnSet(columns);
    }

    static Map<ChunkKey, Set<SectionKey>> sectionsByChunk(
            PackedSectionColumnIndex columns,
            Set<ChunkKey> chunkColumns
    ) {
        return new ChunkColumnMap(columns, chunkColumns);
    }

    private static final class SectionColumnSet extends AbstractSet<SectionKey> {
        private final SectionKey[] keys;

        private SectionColumnSet(SectionKey[] keys) {
            this.keys = Objects.requireNonNull(keys, "keys");
        }

        @Override
        public Iterator<SectionKey> iterator() {
            return new Iterator<>() {
                private int index;

                @Override
                public boolean hasNext() {
                    return index < keys.length;
                }

                @Override
                public SectionKey next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    return keys[index++];
                }
            };
        }

        @Override
        public int size() {
            return keys.length;
        }

        @Override
        public boolean contains(Object candidate) {
            for (SectionKey key : keys) {
                if (key.equals(candidate)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class ChunkColumnSet extends AbstractSet<ChunkKey> {
        private final PackedSectionColumnIndex columns;

        private ChunkColumnSet(PackedSectionColumnIndex columns) {
            this.columns = Objects.requireNonNull(columns, "columns");
        }

        @Override
        public Iterator<ChunkKey> iterator() {
            return new Iterator<>() {
                private int index;

                @Override
                public boolean hasNext() {
                    return index < columns.columnCount();
                }

                @Override
                public ChunkKey next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    return PackedSectionMembership.unpackChunk(columns.packedColumnAt(index++));
                }
            };
        }

        @Override
        public int size() {
            return columns.columnCount();
        }

        @Override
        public boolean contains(Object candidate) {
            return candidate instanceof ChunkKey key
                    && columns.containsColumn(PackedSectionMembership.packChunk(key.x(), key.z()));
        }
    }

    private static final class ChunkColumnMap extends AbstractMap<ChunkKey, Set<SectionKey>> {
        private final PackedSectionColumnIndex columns;
        private final Set<ChunkKey> chunkColumns;

        private ChunkColumnMap(PackedSectionColumnIndex columns, Set<ChunkKey> chunkColumns) {
            this.columns = Objects.requireNonNull(columns, "columns");
            this.chunkColumns = Objects.requireNonNull(chunkColumns, "chunkColumns");
        }

        @Override
        public Set<SectionKey> get(Object key) {
            if (!(key instanceof ChunkKey chunkKey)) {
                return null;
            }
            return columns.column(PackedSectionMembership.packChunk(chunkKey.x(), chunkKey.z()));
        }

        @Override
        public boolean containsKey(Object key) {
            return key instanceof ChunkKey chunkKey
                    && columns.containsColumn(PackedSectionMembership.packChunk(chunkKey.x(), chunkKey.z()));
        }

        @Override
        public int size() {
            return columns.columnCount();
        }

        @Override
        public Set<ChunkKey> keySet() {
            return chunkColumns;
        }

        @Override
        public Set<Entry<ChunkKey, Set<SectionKey>>> entrySet() {
            return new AbstractSet<>() {
                @Override
                public Iterator<Entry<ChunkKey, Set<SectionKey>>> iterator() {
                    Iterator<ChunkKey> keys = chunkColumns.iterator();
                    return new Iterator<>() {
                        @Override
                        public boolean hasNext() {
                            return keys.hasNext();
                        }

                        @Override
                        public Entry<ChunkKey, Set<SectionKey>> next() {
                            ChunkKey key = keys.next();
                            return Map.entry(key, Objects.requireNonNull(ChunkColumnMap.this.get(key)));
                        }
                    };
                }

                @Override
                public int size() {
                    return columns.columnCount();
                }
            };
        }
    }
}
