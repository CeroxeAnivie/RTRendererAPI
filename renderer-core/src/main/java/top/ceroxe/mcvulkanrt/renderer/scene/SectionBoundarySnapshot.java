package top.ceroxe.mcvulkanrt.renderer.scene;

import java.util.Arrays;

/**
 * Renderer-owned samples outside one 16x16x16 section.
 *
 * <p>host freezes a complete 3x3x3 {@code RenderSectionRegion} before it
 * compiles the center section. Keeping only the center payload discards that
 * coherent generation and makes every later neighbor admission rebuild all
 * 4096 center voxels. This snapshot retains only the coordinates the RT mesher
 * can actually read: one layer for topology/fluid decisions and two layers for
 * sourceEngine AO lighting. It therefore preserves sourceEngine's source-generation
 * boundary without retaining 26 complete section copies.</p>
 */
public final class SectionBoundarySnapshot {
    private static final int CENTER_MIN = 0;
    private static final int CENTER_MAX = SectionVoxelSnapshot.SECTION_SIZE - 1;
    private static final int GEOMETRY_MIN = -1;
    private static final int GEOMETRY_MAX = SectionVoxelSnapshot.SECTION_SIZE;
    private static final int LIGHT_MIN = -2;
    private static final int LIGHT_MAX = SectionVoxelSnapshot.SECTION_SIZE + 1;
    private static final int[] GEOMETRY_INDICES = createShellIndices(GEOMETRY_MIN, GEOMETRY_MAX);
    private static final int[] LIGHT_INDICES = createLightIndices();
    private static final int GEOMETRY_SAMPLE_COUNT = shellSampleCount(GEOMETRY_INDICES);
    private static final int LIGHT_SAMPLE_COUNT = shellSampleCount(LIGHT_INDICES);

    private final VoxelPaletteStorage.IntPage voxelTypeIds;
    private final VoxelPaletteStorage.IntPage mediumStateIds;
    private final VoxelPaletteStorage.IntPage mediumTypeIds;
    private final VoxelPaletteStorage.BytePage mediumAmounts;
    private final VoxelPaletteStorage.BytePage geometryMaterialFlags;
    private final VoxelPaletteStorage.IntPage packedMapColors;
    private final VoxelPaletteStorage.BytePage lightEmissions;
    private final VoxelPaletteStorage.BytePage lightMaterialFlags;
    private final VoxelPaletteStorage.BytePage shadeBrightnesses;

    /**
     * Starts a one-shot packed boundary publication. The builder is the
     * production ingress path; public array construction retains defensive-copy
     * semantics for codecs and tests.
     */
    public static Builder builder() {
        return new Builder();
    }

    public SectionBoundarySnapshot(
            int[] voxelTypeIds,
            int[] mediumStateIds,
            int[] mediumTypeIds,
            byte[] mediumAmounts,
            byte[] geometryMaterialFlags,
            int[] packedMapColors,
            byte[] lightEmissions,
            byte[] lightMaterialFlags,
            byte[] shadeBrightnesses
    ) {
        this(
                VoxelPaletteStorage.copyIntPage(voxelTypeIds, GEOMETRY_SAMPLE_COUNT, "voxelTypeIds"),
                VoxelPaletteStorage.copyIntPage(mediumStateIds, GEOMETRY_SAMPLE_COUNT, "mediumStateIds"),
                VoxelPaletteStorage.copyIntPage(mediumTypeIds, GEOMETRY_SAMPLE_COUNT, "mediumTypeIds"),
                VoxelPaletteStorage.copyBytePage(mediumAmounts, GEOMETRY_SAMPLE_COUNT, "mediumAmounts"),
                VoxelPaletteStorage.copyBytePage(
                        geometryMaterialFlags, GEOMETRY_SAMPLE_COUNT, "geometryMaterialFlags"
                ),
                VoxelPaletteStorage.copyIntPage(packedMapColors, LIGHT_SAMPLE_COUNT, "packedMapColors"),
                VoxelPaletteStorage.copyBytePage(lightEmissions, LIGHT_SAMPLE_COUNT, "lightEmissions"),
                VoxelPaletteStorage.copyBytePage(lightMaterialFlags, LIGHT_SAMPLE_COUNT, "lightMaterialFlags"),
                VoxelPaletteStorage.copyBytePage(shadeBrightnesses, LIGHT_SAMPLE_COUNT, "shadeBrightnesses")
        );
    }

    public static final class Builder {
        private final VoxelPaletteStorage.IntPageBuilder voxelTypeIds =
                new VoxelPaletteStorage.IntPageBuilder(GEOMETRY_SAMPLE_COUNT);
        private final VoxelPaletteStorage.IntPageBuilder mediumStateIds =
                new VoxelPaletteStorage.IntPageBuilder(GEOMETRY_SAMPLE_COUNT);
        private final VoxelPaletteStorage.IntPageBuilder mediumTypeIds =
                new VoxelPaletteStorage.IntPageBuilder(GEOMETRY_SAMPLE_COUNT);
        private final VoxelPaletteStorage.BytePageBuilder mediumAmounts =
                new VoxelPaletteStorage.BytePageBuilder(GEOMETRY_SAMPLE_COUNT);
        private final VoxelPaletteStorage.BytePageBuilder geometryMaterialFlags =
                new VoxelPaletteStorage.BytePageBuilder(GEOMETRY_SAMPLE_COUNT);
        private final VoxelPaletteStorage.IntPageBuilder packedMapColors =
                new VoxelPaletteStorage.IntPageBuilder(LIGHT_SAMPLE_COUNT);
        private final VoxelPaletteStorage.BytePageBuilder lightEmissions =
                new VoxelPaletteStorage.BytePageBuilder(LIGHT_SAMPLE_COUNT);
        private final VoxelPaletteStorage.BytePageBuilder lightMaterialFlags =
                new VoxelPaletteStorage.BytePageBuilder(LIGHT_SAMPLE_COUNT);
        private final VoxelPaletteStorage.BytePageBuilder shadeBrightnesses =
                new VoxelPaletteStorage.BytePageBuilder(LIGHT_SAMPLE_COUNT);
        private boolean built;

        private Builder() {
        }

        public void setVoxelStateId(int index, int value) {
            voxelTypeIds.set(index, value);
        }

        public void setFluidVoxelStateId(int index, int value) {
            mediumStateIds.set(index, value);
        }

        public void setFluidTypeId(int index, int value) {
            mediumTypeIds.set(index, value);
        }

        public void setFluidAmount(int index, byte value) {
            mediumAmounts.set(index, value);
        }

        public void setGeometryMaterialFlags(int index, byte value) {
            geometryMaterialFlags.set(index, value);
        }

        public void setPackedMapColor(int index, int value) {
            packedMapColors.set(index, value);
        }

        public void setLightEmission(int index, byte value) {
            lightEmissions.set(index, value);
        }

        public void setLightMaterialFlags(int index, byte value) {
            lightMaterialFlags.set(index, value);
        }

        public void setShadeBrightness(int index, byte value) {
            shadeBrightnesses.set(index, value);
        }

        public SectionBoundarySnapshot build() {
            if (built) {
                throw new IllegalStateException("boundary builder already published");
            }
            built = true;
            return new SectionBoundarySnapshot(
                    voxelTypeIds.build(),
                    mediumStateIds.build(),
                    mediumTypeIds.build(),
                    mediumAmounts.build(),
                    geometryMaterialFlags.build(),
                    packedMapColors.build(),
                    lightEmissions.build(),
                    lightMaterialFlags.build(),
                    shadeBrightnesses.build()
            );
        }

        /**
         * Rewinds worker-local boundary staging. Published pages are immutable and detached
         * from this scratch, so repeated resets cannot mutate an earlier snapshot.
         */
        public void reset() {
            voxelTypeIds.reset();
            mediumStateIds.reset();
            mediumTypeIds.reset();
            mediumAmounts.reset();
            geometryMaterialFlags.reset();
            packedMapColors.reset();
            lightEmissions.reset();
            lightMaterialFlags.reset();
            shadeBrightnesses.reset();
            built = false;
        }
    }

    private SectionBoundarySnapshot(
            VoxelPaletteStorage.IntPage voxelTypeIds,
            VoxelPaletteStorage.IntPage mediumStateIds,
            VoxelPaletteStorage.IntPage mediumTypeIds,
            VoxelPaletteStorage.BytePage mediumAmounts,
            VoxelPaletteStorage.BytePage geometryMaterialFlags,
            VoxelPaletteStorage.IntPage packedMapColors,
            VoxelPaletteStorage.BytePage lightEmissions,
            VoxelPaletteStorage.BytePage lightMaterialFlags,
            VoxelPaletteStorage.BytePage shadeBrightnesses
    ) {
        this.voxelTypeIds = voxelTypeIds;
        this.mediumStateIds = mediumStateIds;
        this.mediumTypeIds = mediumTypeIds;
        this.mediumAmounts = mediumAmounts;
        this.geometryMaterialFlags = geometryMaterialFlags;
        this.packedMapColors = packedMapColors;
        this.lightEmissions = lightEmissions;
        this.lightMaterialFlags = lightMaterialFlags;
        this.shadeBrightnesses = shadeBrightnesses;
    }

    public static int geometrySampleCount() {
        return GEOMETRY_SAMPLE_COUNT;
    }

    public static int lightSampleCount() {
        return LIGHT_SAMPLE_COUNT;
    }

    public static boolean containsGeometryCoordinate(int x, int y, int z) {
        return shellIndexOrMissing(x, y, z, GEOMETRY_MIN, GEOMETRY_MAX, GEOMETRY_INDICES) >= 0;
    }

    public static boolean containsLightCoordinate(int x, int y, int z) {
        return shellIndexOrMissing(x, y, z, LIGHT_MIN, LIGHT_MAX, LIGHT_INDICES) >= 0;
    }

    public static int geometrySampleIndex(int x, int y, int z) {
        return requiredShellIndex(x, y, z, GEOMETRY_MIN, GEOMETRY_MAX, GEOMETRY_INDICES, "geometry");
    }

    public static int lightSampleIndex(int x, int y, int z) {
        return requiredShellIndex(x, y, z, LIGHT_MIN, LIGHT_MAX, LIGHT_INDICES, "light");
    }

    public int voxelTypeIdAt(int x, int y, int z) {
        return voxelTypeIds.get(geometrySampleIndex(x, y, z));
    }

    public int mediumStateIdAt(int x, int y, int z) {
        return mediumStateIds.get(geometrySampleIndex(x, y, z));
    }

    public int mediumTypeIdAt(int x, int y, int z) {
        return mediumTypeIds.get(geometrySampleIndex(x, y, z));
    }

    public int mediumAmountAt(int x, int y, int z) {
        return Byte.toUnsignedInt(mediumAmounts.get(geometrySampleIndex(x, y, z)));
    }

    public int geometryMaterialFlagsAt(int x, int y, int z) {
        return Byte.toUnsignedInt(geometryMaterialFlags.get(geometrySampleIndex(x, y, z)));
    }

    public int packedMapColorAt(int x, int y, int z) {
        return packedMapColors.get(lightSampleIndex(x, y, z));
    }

    public int lightEmissionAt(int x, int y, int z) {
        return Byte.toUnsignedInt(lightEmissions.get(lightSampleIndex(x, y, z)));
    }

    public int lightMaterialFlagsAt(int x, int y, int z) {
        return Byte.toUnsignedInt(lightMaterialFlags.get(lightSampleIndex(x, y, z)));
    }

    public int shadeBrightnessAt(int x, int y, int z) {
        return Byte.toUnsignedInt(shadeBrightnesses.get(lightSampleIndex(x, y, z)));
    }

    public boolean hasSameContent(SectionBoundarySnapshot other) {
        return other != null
                && voxelTypeIds.hasSameContent(other.voxelTypeIds)
                && mediumStateIds.hasSameContent(other.mediumStateIds)
                && mediumTypeIds.hasSameContent(other.mediumTypeIds)
                && mediumAmounts.hasSameContent(other.mediumAmounts)
                && geometryMaterialFlags.hasSameContent(other.geometryMaterialFlags)
                && packedMapColors.hasSameContent(other.packedMapColors)
                && lightEmissions.hasSameContent(other.lightEmissions)
                && lightMaterialFlags.hasSameContent(other.lightMaterialFlags)
                && shadeBrightnesses.hasSameContent(other.shadeBrightnesses);
    }

    public long estimatedRetainedBytes() {
        return Integer.BYTES * (long) (
                voxelTypeIds.retainedElements() + mediumStateIds.retainedElements()
                        + mediumTypeIds.retainedElements() + packedMapColors.retainedElements()
        ) + mediumAmounts.retainedElements()
                + geometryMaterialFlags.retainedElements()
                + lightEmissions.retainedElements()
                + lightMaterialFlags.retainedElements()
                + shadeBrightnesses.retainedElements();
    }

    int sharedPageCount(SectionBoundarySnapshot other) {
        if (other == null) {
            return 0;
        }
        int shared = voxelTypeIds == other.voxelTypeIds ? 1 : 0;
        shared += mediumStateIds == other.mediumStateIds ? 1 : 0;
        shared += mediumTypeIds == other.mediumTypeIds ? 1 : 0;
        shared += mediumAmounts == other.mediumAmounts ? 1 : 0;
        shared += geometryMaterialFlags == other.geometryMaterialFlags ? 1 : 0;
        shared += packedMapColors == other.packedMapColors ? 1 : 0;
        shared += lightEmissions == other.lightEmissions ? 1 : 0;
        shared += lightMaterialFlags == other.lightMaterialFlags ? 1 : 0;
        shared += shadeBrightnesses == other.shadeBrightnesses ? 1 : 0;
        return shared;
    }

    private static int[] createShellIndices(int minimum, int maximum) {
        int dimension = maximum - minimum + 1;
        int[] indices = new int[dimension * dimension * dimension];
        Arrays.fill(indices, -1);
        int next = 0;
        for (int y = minimum; y <= maximum; y++) {
            for (int z = minimum; z <= maximum; z++) {
                for (int x = minimum; x <= maximum; x++) {
                    if (insideCenter(x, y, z)) {
                        continue;
                    }
                    indices[fullIndex(x, y, z, minimum, dimension)] = next++;
                }
            }
        }
        return indices;
    }

    private static int[] createLightIndices() {
        int dimension = LIGHT_MAX - LIGHT_MIN + 1;
        int[] indices = new int[dimension * dimension * dimension];
        Arrays.fill(indices, -1);
        int next = 0;
        for (int y = LIGHT_MIN; y <= LIGHT_MAX; y++) {
            for (int z = LIGHT_MIN; z <= LIGHT_MAX; z++) {
                for (int x = LIGHT_MIN; x <= LIGHT_MAX; x++) {
                    if (!isFirstShellCoordinate(x, y, z) && !isSecondAoCoordinate(x, y, z)) {
                        continue;
                    }
                    indices[fullIndex(x, y, z, LIGHT_MIN, dimension)] = next++;
                }
            }
        }
        return indices;
    }

    private static boolean isFirstShellCoordinate(int x, int y, int z) {
        return x >= GEOMETRY_MIN && x <= GEOMETRY_MAX
                && y >= GEOMETRY_MIN && y <= GEOMETRY_MAX
                && z >= GEOMETRY_MIN && z <= GEOMETRY_MAX
                && !insideCenter(x, y, z);
    }

    /**
     * AO's second-normal probe reaches exactly one +/-2 axis and at most one
     * tangent halo axis. Coordinates with two second-normal axes or two tangent
     * halo axes are never sampled by {@code PackedVoxelLighting}.
     */
    private static boolean isSecondAoCoordinate(int x, int y, int z) {
        int secondNormalAxes = secondNormalAxis(x) + secondNormalAxis(y) + secondNormalAxis(z);
        if (secondNormalAxes != 1) {
            return false;
        }
        if (!insideFirstExpandedAxis(x) || !insideFirstExpandedAxis(y) || !insideFirstExpandedAxis(z)) {
            return false;
        }
        int tangentHaloAxes = firstHaloAxis(x) + firstHaloAxis(y) + firstHaloAxis(z);
        return tangentHaloAxes <= 1;
    }

    private static int secondNormalAxis(int coordinate) {
        return coordinate == LIGHT_MIN || coordinate == LIGHT_MAX ? 1 : 0;
    }

    private static int firstHaloAxis(int coordinate) {
        return coordinate == GEOMETRY_MIN || coordinate == GEOMETRY_MAX ? 1 : 0;
    }

    private static boolean insideFirstExpandedAxis(int coordinate) {
        return secondNormalAxis(coordinate) != 0
                || coordinate >= GEOMETRY_MIN && coordinate <= GEOMETRY_MAX;
    }

    private static int shellSampleCount(int[] indices) {
        int maximum = -1;
        for (int index : indices) {
            maximum = Math.max(maximum, index);
        }
        return maximum + 1;
    }

    private static int requiredShellIndex(
            int x,
            int y,
            int z,
            int minimum,
            int maximum,
            int[] indices,
            String domain
    ) {
        int index = shellIndexOrMissing(x, y, z, minimum, maximum, indices);
        if (index < 0) {
            throw new IndexOutOfBoundsException(domain + " boundary coordinate unavailable: ("
                    + x + ", " + y + ", " + z + ')');
        }
        return index;
    }

    private static int shellIndexOrMissing(
            int x,
            int y,
            int z,
            int minimum,
            int maximum,
            int[] indices
    ) {
        if (x < minimum || x > maximum || y < minimum || y > maximum || z < minimum || z > maximum) {
            return -1;
        }
        int dimension = maximum - minimum + 1;
        return indices[fullIndex(x, y, z, minimum, dimension)];
    }

    private static int fullIndex(int x, int y, int z, int minimum, int dimension) {
        return ((y - minimum) * dimension + (z - minimum)) * dimension + (x - minimum);
    }

    private static boolean insideCenter(int x, int y, int z) {
        return x >= CENTER_MIN && x <= CENTER_MAX
                && y >= CENTER_MIN && y <= CENTER_MAX
                && z >= CENTER_MIN && z <= CENTER_MAX;
    }

}
