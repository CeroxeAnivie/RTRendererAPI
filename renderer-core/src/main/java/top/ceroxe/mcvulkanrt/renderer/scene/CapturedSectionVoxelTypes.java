package top.ceroxe.mcvulkanrt.renderer.scene;

/**
 * Immutable block-state publication for one section and the exact boundary
 * samples consumed by the RT mesher.
 *
 * <p>The host compiler freezes a complete 3x3x3 region because its
 * general-purpose compile task may inspect any neighbor. RT extraction has a
 * narrower, explicit contract: the center section plus the topology/AO shell
 * described by {@link SectionBoundarySnapshot}. Publishing those two packed
 * pages keeps the worker generation immutable without retaining 27 copied
 * paletted containers.</p>
 */
public final class CapturedSectionVoxelTypes {
    private static final int SOURCE_PAGE_AXIS = 3;
    private static final int SOURCE_PAGE_COUNT = SOURCE_PAGE_AXIS * SOURCE_PAGE_AXIS * SOURCE_PAGE_AXIS;

    private final VoxelPaletteStorage.IntPage centerStateIds;
    private final VoxelPaletteStorage.IntPage boundaryStateIds;
    private final SourcePage[] sourcePages;

    private CapturedSectionVoxelTypes(
            VoxelPaletteStorage.IntPage centerStateIds,
            VoxelPaletteStorage.IntPage boundaryStateIds
    ) {
        this.centerStateIds = centerStateIds;
        this.boundaryStateIds = boundaryStateIds;
        sourcePages = null;
    }

    private CapturedSectionVoxelTypes(SourcePage[] sourcePages) {
        centerStateIds = null;
        boundaryStateIds = null;
        this.sourcePages = sourcePages;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Assembles one target publication from the 3x3x3 immutable source pages
     * surrounding it. The caller's array is detached; page payloads are safe to
     * share across target sections and worker generations.
     */
    public static CapturedSectionVoxelTypes fromSourcePages(SourcePage[] sourcePages) {
        if (sourcePages == null || sourcePages.length != SOURCE_PAGE_COUNT) {
            throw new IllegalArgumentException("sourcePages length must be " + SOURCE_PAGE_COUNT);
        }
        SourcePage[] detached = sourcePages.clone();
        for (int index = 0; index < detached.length; index++) {
            if (detached[index] == null) {
                throw new IllegalArgumentException("sourcePages[" + index + "] is null");
            }
        }
        return new CapturedSectionVoxelTypes(detached);
    }

    public int voxelTypeIdAt(int x, int y, int z) {
        if (sourcePages != null) {
            int sourceX = Math.floorDiv(x, SectionVoxelSnapshot.SECTION_SIZE);
            int sourceY = Math.floorDiv(y, SectionVoxelSnapshot.SECTION_SIZE);
            int sourceZ = Math.floorDiv(z, SectionVoxelSnapshot.SECTION_SIZE);
            if (sourceX < -1 || sourceX > 1
                    || sourceY < -1 || sourceY > 1
                    || sourceZ < -1 || sourceZ > 1) {
                throw new IndexOutOfBoundsException("coordinate outside captured source pages: ("
                        + x + ", " + y + ", " + z + ')');
            }
            int sourceIndex = ((sourceZ + 1) * SOURCE_PAGE_AXIS + sourceY + 1) * SOURCE_PAGE_AXIS
                    + sourceX + 1;
            return sourcePages[sourceIndex].voxelTypeIdAt(
                    Math.floorMod(x, SectionVoxelSnapshot.SECTION_SIZE),
                    Math.floorMod(y, SectionVoxelSnapshot.SECTION_SIZE),
                    Math.floorMod(z, SectionVoxelSnapshot.SECTION_SIZE)
            );
        }
        if (insideCenter(x, y, z)) {
            return centerStateIds.get(SectionVoxelSnapshot.localBlockIndex(x, y, z));
        }
        return boundaryStateIds.get(SectionBoundarySnapshot.lightSampleIndex(x, y, z));
    }

    public long estimatedRetainedBytes() {
        if (sourcePages != null) {
            /* Payload pages are cache-owned and shared; this publication owns only references. */
            return Long.BYTES * (long) sourcePages.length;
        }
        return Integer.BYTES * (long) (centerStateIds.retainedElements() + boundaryStateIds.retainedElements());
    }

    int sharedPageCount(CapturedSectionVoxelTypes other) {
        if (other == null || sourcePages != null || other.sourcePages != null) {
            return 0;
        }
        return (centerStateIds == other.centerStateIds ? 1 : 0)
                + (boundaryStateIds == other.boundaryStateIds ? 1 : 0);
    }

    private static boolean insideCenter(int x, int y, int z) {
        return x >= 0 && x < SectionVoxelSnapshot.SECTION_SIZE
                && y >= 0 && y < SectionVoxelSnapshot.SECTION_SIZE
                && z >= 0 && z < SectionVoxelSnapshot.SECTION_SIZE;
    }

    /** Immutable palette-packed state IDs for one complete source section. */
    public static final class SourcePage {
        private final VoxelPaletteStorage.IntPage stateIds;

        private SourcePage(VoxelPaletteStorage.IntPage stateIds) {
            this.stateIds = stateIds;
        }

        public static SourcePage uniform(int voxelTypeId) {
            return new SourcePage(VoxelPaletteStorage.copyIntPage(
                    new int[]{voxelTypeId}, SectionVoxelSnapshot.BLOCKS_PER_SECTION, "stateIds"
            ));
        }

        public static SourcePageBuilder builder() {
            return new SourcePageBuilder();
        }

        public int voxelTypeIdAt(int x, int y, int z) {
            return stateIds.get(SectionVoxelSnapshot.localBlockIndex(x, y, z));
        }

        public long estimatedRetainedBytes() {
            return Integer.BYTES * (long) stateIds.retainedElements();
        }
    }

    /** Reusable render-thread staging for one cache-miss source generation. */
    public static final class SourcePageBuilder {
        private final VoxelPaletteStorage.IntPageBuilder states =
                new VoxelPaletteStorage.IntPageBuilder(SectionVoxelSnapshot.BLOCKS_PER_SECTION);
        private boolean built;

        private SourcePageBuilder() {
        }

        public void setVoxelStateId(int x, int y, int z, int voxelTypeId) {
            if (built) {
                throw new IllegalStateException("source-page builder already published");
            }
            states.set(SectionVoxelSnapshot.localBlockIndex(x, y, z), voxelTypeId);
        }

        public SourcePage build() {
            if (built) {
                throw new IllegalStateException("source-page builder already published");
            }
            built = true;
            return new SourcePage(states.build());
        }

        public void reset() {
            states.reset();
            built = false;
        }
    }

    public static final class Builder {
        private final VoxelPaletteStorage.IntPageBuilder center =
                new VoxelPaletteStorage.IntPageBuilder(SectionVoxelSnapshot.BLOCKS_PER_SECTION);
        private final VoxelPaletteStorage.IntPageBuilder boundary =
                new VoxelPaletteStorage.IntPageBuilder(SectionBoundarySnapshot.lightSampleCount());
        private boolean built;

        private Builder() {
        }

        public void setVoxelStateId(int x, int y, int z, int voxelTypeId) {
            if (built) {
                throw new IllegalStateException("captured block-state builder already published");
            }
            if (insideCenter(x, y, z)) {
                center.set(SectionVoxelSnapshot.localBlockIndex(x, y, z), voxelTypeId);
                return;
            }
            boundary.set(SectionBoundarySnapshot.lightSampleIndex(x, y, z), voxelTypeId);
        }

        public CapturedSectionVoxelTypes build() {
            if (built) {
                throw new IllegalStateException("captured block-state builder already published");
            }
            built = true;
            return new CapturedSectionVoxelTypes(center.build(), boundary.build());
        }

        /** Rewinds render-thread staging after its immutable publication has detached. */
        public void reset() {
            center.reset();
            boundary.reset();
            built = false;
        }
    }
}
