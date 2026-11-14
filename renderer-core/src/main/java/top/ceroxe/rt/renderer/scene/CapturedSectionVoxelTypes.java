package top.ceroxe.rt.renderer.scene;

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

    /**
     * 创建中心区段与边界采样的暂存构建器。
     *
     * @return 用于构建中心区段及其精确边界采样的可复用暂存器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Assembles one target publication from the 3x3x3 immutable source pages
     * surrounding it. The caller's array is detached; page payloads are safe to
     * share across target sections and worker generations.
     *
     * @param sourcePages 按 3x3x3 规范顺序排列的不可变源区段页
     * @return 与调用方数组分离的捕获发布对象
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

    private static boolean insideCenter(int x, int y, int z) {
        return x >= 0 && x < SectionVoxelSnapshot.SECTION_SIZE
                && y >= 0 && y < SectionVoxelSnapshot.SECTION_SIZE
                && z >= 0 && z < SectionVoxelSnapshot.SECTION_SIZE;
    }

    /**
     * 返回中心区段或已捕获边界坐标处的体素类型。
     *
     * @param x 相对中心区段的局部 X 坐标
     * @param y 相对中心区段的局部 Y 坐标
     * @param z 相对中心区段的局部 Z 坐标
     * @return 渲染器内部体素类型标识
     */
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

    /**
     * 估算此发布对象独占的稳定负载。
     *
     * @return 此发布对象独占负载的估算字节数；共享源页负载不重复计入
     */
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

    /**
     * Immutable palette-packed state IDs for one complete source section.
     */
    public static final class SourcePage {
        private final VoxelPaletteStorage.IntPage stateIds;

        private SourcePage(VoxelPaletteStorage.IntPage stateIds) {
            this.stateIds = stateIds;
        }

        /**
         * 创建所有体素均具有相同类型的不可变源页。
         *
         * @param voxelTypeId 填充整个源页的体素类型标识
         * @return 调色板压缩后的不可变源页
         */
        public static SourcePage uniform(int voxelTypeId) {
            return new SourcePage(VoxelPaletteStorage.copyIntPage(
                    new int[]{voxelTypeId}, SectionVoxelSnapshot.BLOCKS_PER_SECTION, "stateIds"
            ));
        }

        /**
         * 创建一个新的源页暂存构建器。
         *
         * @return 新的可复用源页暂存器
         */
        public static SourcePageBuilder builder() {
            return new SourcePageBuilder();
        }

        /**
         * 返回区段内指定坐标的体素类型。
         *
         * @param x 区段内 X 坐标
         * @param y 区段内 Y 坐标
         * @param z 区段内 Z 坐标
         * @return 指定位置的体素类型标识
         */
        public int voxelTypeIdAt(int x, int y, int z) {
            return stateIds.get(SectionVoxelSnapshot.localBlockIndex(x, y, z));
        }

        /**
         * 估算源页调色板负载占用。
         *
         * @return 调色板压缩负载的估算字节数
         */
        public long estimatedRetainedBytes() {
            return Integer.BYTES * (long) stateIds.retainedElements();
        }
    }

    /**
     * Reusable render-thread staging for one cache-miss source generation.
     */
    public static final class SourcePageBuilder {
        private final VoxelPaletteStorage.IntPageBuilder states =
                new VoxelPaletteStorage.IntPageBuilder(SectionVoxelSnapshot.BLOCKS_PER_SECTION);
        private boolean built;

        private SourcePageBuilder() {
        }

        /**
         * 写入一个区段内体素类型。
         *
         * @param x           区段内 X 坐标
         * @param y           区段内 Y 坐标
         * @param z           区段内 Z 坐标
         * @param voxelTypeId 渲染器内部体素类型标识
         */
        public void setVoxelStateId(int x, int y, int z, int voxelTypeId) {
            if (built) {
                throw new IllegalStateException("source-page builder already published");
            }
            states.set(SectionVoxelSnapshot.localBlockIndex(x, y, z), voxelTypeId);
        }

        /**
         * 冻结当前暂存内容；再次写入前必须先调用 {@link #reset()}。
         *
         * @return 不可变源页
         */
        public SourcePage build() {
            if (built) {
                throw new IllegalStateException("source-page builder already published");
            }
            built = true;
            return new SourcePage(states.build());
        }

        /**
         * 清空暂存器并允许下一轮源页捕获。
         */
        public void reset() {
            states.reset();
            built = false;
        }
    }

    /**
     * 中心区段和其精确边界采样的可复用构建器。
     */
    public static final class Builder {
        private final VoxelPaletteStorage.IntPageBuilder center =
                new VoxelPaletteStorage.IntPageBuilder(SectionVoxelSnapshot.BLOCKS_PER_SECTION);
        private final VoxelPaletteStorage.IntPageBuilder boundary =
                new VoxelPaletteStorage.IntPageBuilder(SectionBoundarySnapshot.lightSampleCount());
        private boolean built;

        private Builder() {
        }

        /**
         * 写入中心区段或受支持边界坐标的体素类型。
         *
         * @param x           相对中心区段的局部 X 坐标
         * @param y           相对中心区段的局部 Y 坐标
         * @param z           相对中心区段的局部 Z 坐标
         * @param voxelTypeId 渲染器内部体素类型标识
         */
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

        /**
         * 冻结中心页与边界页；再次写入前必须先调用 {@link #reset()}。
         *
         * @return 不可变捕获发布对象
         */
        public CapturedSectionVoxelTypes build() {
            if (built) {
                throw new IllegalStateException("captured block-state builder already published");
            }
            built = true;
            return new CapturedSectionVoxelTypes(center.build(), boundary.build());
        }

        /**
         * Rewinds render-thread staging after its immutable publication has detached.
         */
        public void reset() {
            center.reset();
            boundary.reset();
            built = false;
        }
    }
}
