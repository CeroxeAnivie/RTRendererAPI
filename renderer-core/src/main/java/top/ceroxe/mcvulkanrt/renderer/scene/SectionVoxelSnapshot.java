package top.ceroxe.mcvulkanrt.renderer.scene;

import java.util.Arrays;
import java.util.Objects;

/**
 * 一个 16x16x16 chunk section 的 renderer-owned 体素材料快照。
 *
 * <p>快照只保存 primitive material facts。它不是最终 RT 几何格式，但已经是
 * Vulkan BLAS 构建前必须拥有的稳定输入：不引用 host 对象，不会被世界线程继续修改。</p>
 */
public record SectionVoxelSnapshot(
        SectionKey key,
        int[] voxelTypeIds,
        int[] mediumStateIds,
        int[] mediumTypeIds,
        byte[] mediumAmounts,
        byte[] fluidFlowX,
        byte[] fluidFlowZ,
        int[] mapColors,
        int[] blockTintLayer0Colors,
        int[] blockTintLayer1Colors,
        int[] blockTintLayer2Colors,
        int[] blockTintLayer3Colors,
        int[] fluidMapColors,
        byte[] lightEmissions,
        byte[] materialFlags,
        byte[] shadeBrightnesses,
        boolean hasOnlyAir,
        boolean hasFluid,
        SectionBoundarySnapshot capturedBoundary
) {
    /*
     * Record components expose the logical sourceEngine section fields, while their
     * private arrays may use VoxelPaletteStorage. Public accessors always
     * expand to 4096 elements, preserving the original immutable API contract.
     */
    public static final int SECTION_SIZE = 16;
    public static final int BLOCKS_PER_SECTION = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;
    public static final int NO_MAP_COLOR = 0;
    public static final int FLAG_RENDER_SHAPE_VISIBLE = 1;
    public static final int FLAG_AIR = 1 << 1;
    public static final int FLAG_LIQUID = 1 << 2;
    public static final int FLAG_OCCLUDES_NEIGHBORS = 1 << 3;
    public static final int FLAG_OCCLUSION_KNOWN = 1 << 4;
    public static final int FLAG_LIGHT_KNOWN = 1 << 5;
    public static final int FLAG_AO_TRANSLUCENT = 1 << 6;
    public static final int FLAG_FLUID_HEIGHT_SOLID = 1 << 7;
    public static final int MAP_COLOR_RGB_MASK = 0x00FF_FFFF;
    public static final int PACKED_LIGHT_SHIFT = 24;
    public static final int PACKED_LIGHT_MASK = 0xFF00_0000;
    public static final int NO_BLOCK_TINT_LAYER_COLOR = -1;
    public static final int CAPTURED_BLOCK_TINT_LAYER_COUNT = 4;

    public SectionVoxelSnapshot {
        key = Objects.requireNonNull(key, "key");
        voxelTypeIds = copyIntArray(voxelTypeIds, "voxelTypeIds");
        mediumStateIds = copyIntArray(mediumStateIds, "mediumStateIds");
        mediumTypeIds = copyIntArray(mediumTypeIds, "mediumTypeIds");
        mediumAmounts = copyByteArray(mediumAmounts, "mediumAmounts");
        fluidFlowX = copyByteArray(fluidFlowX, "fluidFlowX");
        fluidFlowZ = copyByteArray(fluidFlowZ, "fluidFlowZ");
        mapColors = copyIntArray(mapColors, "mapColors");
        blockTintLayer0Colors = copyBlockTintLayerArray(blockTintLayer0Colors, "blockTintLayer0Colors");
        blockTintLayer1Colors = copyBlockTintLayerArray(blockTintLayer1Colors, "blockTintLayer1Colors");
        blockTintLayer2Colors = copyBlockTintLayerArray(blockTintLayer2Colors, "blockTintLayer2Colors");
        blockTintLayer3Colors = copyBlockTintLayerArray(blockTintLayer3Colors, "blockTintLayer3Colors");
        fluidMapColors = copyIntArray(fluidMapColors, "fluidMapColors");
        lightEmissions = copyByteArray(lightEmissions, "lightEmissions");
        materialFlags = copyByteArray(materialFlags, "materialFlags");
        shadeBrightnesses = copyByteArray(shadeBrightnesses, "shadeBrightnesses");
    }

    public SectionVoxelSnapshot(
            SectionKey key,
            int[] voxelTypeIds,
            int[] mediumStateIds,
            int[] mediumTypeIds,
            byte[] mediumAmounts,
            byte[] fluidFlowX,
            byte[] fluidFlowZ,
            int[] mapColors,
            int[] blockTintLayer0Colors,
            int[] blockTintLayer1Colors,
            int[] blockTintLayer2Colors,
            int[] blockTintLayer3Colors,
            int[] fluidMapColors,
            byte[] lightEmissions,
            byte[] materialFlags,
            byte[] shadeBrightnesses,
            boolean hasOnlyAir,
            boolean hasFluid
    ) {
        this(
                key,
                voxelTypeIds,
                mediumStateIds,
                mediumTypeIds,
                mediumAmounts,
                fluidFlowX,
                fluidFlowZ,
                mapColors,
                blockTintLayer0Colors,
                blockTintLayer1Colors,
                blockTintLayer2Colors,
                blockTintLayer3Colors,
                fluidMapColors,
                lightEmissions,
                materialFlags,
                shadeBrightnesses,
                hasOnlyAir,
                hasFluid,
                null
        );
    }

    public SectionVoxelSnapshot(
            SectionKey key,
            int[] voxelTypeIds,
            int[] mediumStateIds,
            byte[] mediumAmounts,
            int[] mapColors,
            int[] fluidMapColors,
            byte[] lightEmissions,
            byte[] materialFlags,
            byte[] shadeBrightnesses,
            boolean hasOnlyAir,
            boolean hasFluid
    ) {
        this(
                key,
                voxelTypeIds,
                mediumStateIds,
                defaultFluidTypeIds(mediumStateIds, mediumAmounts),
                mediumAmounts,
                defaultFluidFlow(),
                defaultFluidFlow(),
                mapColors,
                defaultBlockTintLayerColors(),
                defaultBlockTintLayerColors(),
                defaultBlockTintLayerColors(),
                defaultBlockTintLayerColors(),
                fluidMapColors,
                lightEmissions,
                materialFlags,
                shadeBrightnesses,
                hasOnlyAir,
                hasFluid
        );
    }

    public SectionVoxelSnapshot(
            SectionKey key,
            int[] voxelTypeIds,
            int[] mediumStateIds,
            int[] mediumTypeIds,
            byte[] mediumAmounts,
            int[] mapColors,
            int[] fluidMapColors,
            byte[] lightEmissions,
            byte[] materialFlags,
            byte[] shadeBrightnesses,
            boolean hasOnlyAir,
            boolean hasFluid
    ) {
        this(
                key,
                voxelTypeIds,
                mediumStateIds,
                mediumTypeIds,
                mediumAmounts,
                defaultFluidFlow(),
                defaultFluidFlow(),
                mapColors,
                defaultBlockTintLayerColors(),
                defaultBlockTintLayerColors(),
                defaultBlockTintLayerColors(),
                defaultBlockTintLayerColors(),
                fluidMapColors,
                lightEmissions,
                materialFlags,
                shadeBrightnesses,
                hasOnlyAir,
                hasFluid
        );
    }

    public SectionVoxelSnapshot(
            SectionKey key,
            int[] voxelTypeIds,
            int[] mediumStateIds,
            byte[] mediumAmounts,
            int[] mapColors,
            byte[] lightEmissions,
            byte[] materialFlags,
            boolean hasOnlyAir,
            boolean hasFluid
    ) {
        this(
                key,
                voxelTypeIds,
                mediumStateIds,
                defaultFluidTypeIds(mediumStateIds, mediumAmounts),
                mediumAmounts,
                defaultFluidFlow(),
                defaultFluidFlow(),
                mapColors,
                defaultBlockTintLayerColors(),
                defaultBlockTintLayerColors(),
                defaultBlockTintLayerColors(),
                defaultBlockTintLayerColors(),
                defaultFluidMapColors(mapColors, mediumAmounts),
                lightEmissions,
                materialFlags,
                defaultShadeBrightnesses(voxelTypeIds, mediumAmounts),
                hasOnlyAir,
                hasFluid
        );
    }

    public SectionVoxelSnapshot(
            SectionKey key,
            int[] voxelTypeIds,
            byte[] mediumAmounts,
            int[] mapColors,
            byte[] lightEmissions,
            byte[] materialFlags,
            boolean hasOnlyAir,
            boolean hasFluid
    ) {
        this(
                key,
                voxelTypeIds,
                defaultFluidVoxelStateIds(voxelTypeIds, mediumAmounts),
                defaultFluidTypeIds(defaultFluidVoxelStateIds(voxelTypeIds, mediumAmounts), mediumAmounts),
                mediumAmounts,
                defaultFluidFlow(),
                defaultFluidFlow(),
                mapColors,
                defaultBlockTintLayerColors(),
                defaultBlockTintLayerColors(),
                defaultBlockTintLayerColors(),
                defaultBlockTintLayerColors(),
                defaultFluidMapColors(mapColors, mediumAmounts),
                lightEmissions,
                materialFlags,
                defaultShadeBrightnesses(voxelTypeIds, mediumAmounts),
                hasOnlyAir,
                hasFluid
        );
    }

    public SectionVoxelSnapshot(
            SectionKey key,
            int[] voxelTypeIds,
            byte[] mediumAmounts,
            boolean hasOnlyAir,
            boolean hasFluid
    ) {
        this(
                key,
                voxelTypeIds,
                defaultFluidVoxelStateIds(voxelTypeIds, mediumAmounts),
                defaultFluidTypeIds(defaultFluidVoxelStateIds(voxelTypeIds, mediumAmounts), mediumAmounts),
                mediumAmounts,
                defaultFluidFlow(),
                defaultFluidFlow(),
                defaultMapColors(),
                defaultBlockTintLayerColors(),
                defaultBlockTintLayerColors(),
                defaultBlockTintLayerColors(),
                defaultBlockTintLayerColors(),
                defaultMapColors(),
                defaultLightEmissions(),
                defaultMaterialFlags(voxelTypeIds, mediumAmounts),
                defaultShadeBrightnesses(voxelTypeIds, mediumAmounts),
                hasOnlyAir,
                hasFluid
        );
    }

    public static int localBlockIndex(int x, int y, int z) {
        if ((x | y | z) < 0 || x >= SECTION_SIZE || y >= SECTION_SIZE || z >= SECTION_SIZE) {
            throw new IndexOutOfBoundsException("local block coordinate outside 16x16x16 section: ("
                    + x + ", " + y + ", " + z + ")");
        }
        return (y * SECTION_SIZE + z) * SECTION_SIZE + x;
    }

    /**
     * Counts the primitive-array payload retained by this immutable snapshot.
     * JVM object and array headers are intentionally excluded because their size
     * depends on the selected VM layout; the returned value is exact for voxel data.
     */
    public long primitivePayloadBytes() {
        long integerElements = (long) voxelTypeIds.length
                + mediumStateIds.length
                + mediumTypeIds.length
                + mapColors.length
                + blockTintLayer0Colors.length
                + blockTintLayer1Colors.length
                + blockTintLayer2Colors.length
                + blockTintLayer3Colors.length
                + fluidMapColors.length;
        long byteElements = (long) mediumAmounts.length
                + fluidFlowX.length
                + fluidFlowZ.length
                + lightEmissions.length
                + materialFlags.length
                + shadeBrightnesses.length;
        return integerElements * Integer.BYTES
                + byteElements
                + (capturedBoundary == null ? 0L : capturedBoundary.estimatedRetainedBytes());
    }

    public static int packMapColorAndLight(int mapColor, int skyLight, int blockLight) {
        return (mapColor & MAP_COLOR_RGB_MASK)
                | (clampLightNibble(skyLight) << 28)
                | (clampLightNibble(blockLight) << PACKED_LIGHT_SHIFT);
    }

    public static int mapColorRgb(int packedMapColorAndLight) {
        return packedMapColorAndLight & MAP_COLOR_RGB_MASK;
    }

    public static int packedLight(int packedMapColorAndLight) {
        return (packedMapColorAndLight >>> PACKED_LIGHT_SHIFT) & 0xFF;
    }

    public static int replaceMapColorRgb(int packedMapColorAndLight, int mapColorRgb) {
        return (packedMapColorAndLight & PACKED_LIGHT_MASK)
                | (mapColorRgb & MAP_COLOR_RGB_MASK);
    }

    public static int packMapColorWithPackedLight(int mapColorRgb, int packedLight) {
        return (mapColorRgb & MAP_COLOR_RGB_MASK)
                | ((packedLight & 0xFF) << PACKED_LIGHT_SHIFT);
    }

    public int voxelTypeIdAt(int x, int y, int z) {
        return voxelTypeIdAtLinearIndex(localBlockIndex(x, y, z));
    }

    public int mediumAmountAt(int x, int y, int z) {
        return mediumAmountAtLinearIndex(localBlockIndex(x, y, z));
    }

    public int mediumStateIdAt(int x, int y, int z) {
        return mediumStateIdAtLinearIndex(localBlockIndex(x, y, z));
    }

    public int mediumTypeIdAt(int x, int y, int z) {
        return mediumTypeIdAtLinearIndex(localBlockIndex(x, y, z));
    }

    public int fluidFlowXAt(int x, int y, int z) {
        return fluidFlowXAtLinearIndex(localBlockIndex(x, y, z));
    }

    public int fluidFlowZAt(int x, int y, int z) {
        return fluidFlowZAtLinearIndex(localBlockIndex(x, y, z));
    }

    public int mapColorAt(int x, int y, int z) {
        return mapColorAtLinearIndex(localBlockIndex(x, y, z));
    }

    public int fluidMapColorAt(int x, int y, int z) {
        return fluidMapColorAtLinearIndex(localBlockIndex(x, y, z));
    }

    public int blockTintLayerColorAt(int x, int y, int z, int tintLayer) {
        return blockTintLayerColorAtLinearIndex(localBlockIndex(x, y, z), tintLayer);
    }

    public int lightEmissionAt(int x, int y, int z) {
        return lightEmissionAtLinearIndex(localBlockIndex(x, y, z));
    }

    public int materialFlagsAt(int x, int y, int z) {
        return materialFlagsAtLinearIndex(localBlockIndex(x, y, z));
    }

    public int shadeBrightnessAt(int x, int y, int z) {
        return shadeBrightnessAtLinearIndex(localBlockIndex(x, y, z));
    }

    public int voxelTypeIdAtLinearIndex(int index) {
        checkLinearIndex(index);
        return intValueAt(voxelTypeIds, index);
    }

    public int mediumAmountAtLinearIndex(int index) {
        checkLinearIndex(index);
        return Byte.toUnsignedInt(byteValueAt(mediumAmounts, index));
    }

    public int mediumStateIdAtLinearIndex(int index) {
        checkLinearIndex(index);
        return intValueAt(mediumStateIds, index);
    }

    public int mediumTypeIdAtLinearIndex(int index) {
        checkLinearIndex(index);
        return intValueAt(mediumTypeIds, index);
    }

    public int fluidFlowXAtLinearIndex(int index) {
        checkLinearIndex(index);
        return byteValueAt(fluidFlowX, index);
    }

    public int fluidFlowZAtLinearIndex(int index) {
        checkLinearIndex(index);
        return byteValueAt(fluidFlowZ, index);
    }

    public int mapColorAtLinearIndex(int index) {
        checkLinearIndex(index);
        return intValueAt(mapColors, index);
    }

    public int fluidMapColorAtLinearIndex(int index) {
        checkLinearIndex(index);
        return intValueAt(fluidMapColors, index);
    }

    public int blockTintLayerColorAtLinearIndex(int index, int tintLayer) {
        checkLinearIndex(index);
        return switch (tintLayer) {
            case 0 -> intValueAt(blockTintLayer0Colors, index);
            case 1 -> intValueAt(blockTintLayer1Colors, index);
            case 2 -> intValueAt(blockTintLayer2Colors, index);
            case 3 -> intValueAt(blockTintLayer3Colors, index);
            default -> NO_BLOCK_TINT_LAYER_COLOR;
        };
    }

    public int lightEmissionAtLinearIndex(int index) {
        checkLinearIndex(index);
        return Byte.toUnsignedInt(byteValueAt(lightEmissions, index));
    }

    public int materialFlagsAtLinearIndex(int index) {
        checkLinearIndex(index);
        return Byte.toUnsignedInt(byteValueAt(materialFlags, index));
    }

    public int shadeBrightnessAtLinearIndex(int index) {
        checkLinearIndex(index);
        return Byte.toUnsignedInt(byteValueAt(shadeBrightnesses, index));
    }

    public boolean visibleRenderShapeAtLinearIndex(int index) {
        return (materialFlagsAtLinearIndex(index) & FLAG_RENDER_SHAPE_VISIBLE) != 0;
    }

    public boolean hasSameVoxelContent(SectionVoxelSnapshot other) {
        return hasSameCenterVoxelContent(other) && sameCapturedBoundary(other);
    }

    /** Exact center payload equality, deliberately excluding the compiled-region halo. */
    public boolean hasSameCenterVoxelContent(SectionVoxelSnapshot other) {
        if (other == null) {
            return false;
        }
        return hasOnlyAir == other.hasOnlyAir
                && hasFluid == other.hasFluid
                && logicalIntArrayEquals(voxelTypeIds, other.voxelTypeIds)
                && logicalIntArrayEquals(mediumStateIds, other.mediumStateIds)
                && logicalIntArrayEquals(mediumTypeIds, other.mediumTypeIds)
                && logicalByteArrayEquals(mediumAmounts, other.mediumAmounts)
                && logicalByteArrayEquals(fluidFlowX, other.fluidFlowX)
                && logicalByteArrayEquals(fluidFlowZ, other.fluidFlowZ)
                && logicalIntArrayEquals(mapColors, other.mapColors)
                && logicalIntArrayEquals(blockTintLayer0Colors, other.blockTintLayer0Colors)
                && logicalIntArrayEquals(blockTintLayer1Colors, other.blockTintLayer1Colors)
                && logicalIntArrayEquals(blockTintLayer2Colors, other.blockTintLayer2Colors)
                && logicalIntArrayEquals(blockTintLayer3Colors, other.blockTintLayer3Colors)
                && logicalIntArrayEquals(fluidMapColors, other.fluidMapColors)
                && logicalByteArrayEquals(lightEmissions, other.lightEmissions)
                && logicalByteArrayEquals(materialFlags, other.materialFlags)
                && logicalByteArrayEquals(shadeBrightnesses, other.shadeBrightnesses);
    }

    public boolean hasSameGeometryContent(SectionVoxelSnapshot other) {
        return hasSameCenterGeometryContent(other) && sameCapturedBoundary(other);
    }

    private boolean hasSameCenterGeometryContent(SectionVoxelSnapshot other) {
        if (other == null) {
            return false;
        }
        return hasOnlyAir == other.hasOnlyAir
                && hasFluid == other.hasFluid
                && logicalIntArrayEquals(voxelTypeIds, other.voxelTypeIds)
                && logicalIntArrayEquals(mediumStateIds, other.mediumStateIds)
                && logicalIntArrayEquals(mediumTypeIds, other.mediumTypeIds)
                && logicalByteArrayEquals(mediumAmounts, other.mediumAmounts)
                && logicalByteArrayEquals(fluidFlowX, other.fluidFlowX)
                && logicalByteArrayEquals(fluidFlowZ, other.fluidFlowZ)
                && logicalByteArrayEquals(materialFlags, other.materialFlags);
    }

    public SectionVoxelSnapshot withCapturedBoundary(SectionBoundarySnapshot boundary) {
        if (capturedBoundary == boundary) {
            return this;
        }
        return new SectionVoxelSnapshot(
                key,
                voxelTypeIds,
                mediumStateIds,
                mediumTypeIds,
                mediumAmounts,
                fluidFlowX,
                fluidFlowZ,
                mapColors,
                blockTintLayer0Colors,
                blockTintLayer1Colors,
                blockTintLayer2Colors,
                blockTintLayer3Colors,
                fluidMapColors,
                lightEmissions,
                materialFlags,
                shadeBrightnesses,
                hasOnlyAir,
                hasFluid,
                boundary
        );
    }

    private boolean sameCapturedBoundary(SectionVoxelSnapshot other) {
        if (other == null) {
            return false;
        }
        if (capturedBoundary == null || other.capturedBoundary == null) {
            return capturedBoundary == other.capturedBoundary;
        }
        return capturedBoundary.hasSameContent(other.capturedBoundary);
    }

    public long estimatedRetainedBytes() {
        return Integer.BYTES * (long) (
                voxelTypeIds.length
                        + mediumStateIds.length
                        + mediumTypeIds.length
                        + mapColors.length
                        + blockTintLayer0Colors.length
                        + blockTintLayer1Colors.length
                        + blockTintLayer2Colors.length
                        + blockTintLayer3Colors.length
                        + fluidMapColors.length
        ) + mediumAmounts.length
                + fluidFlowX.length
                + fluidFlowZ.length
                + lightEmissions.length
                + materialFlags.length
                + shadeBrightnesses.length
                + (capturedBoundary == null ? 0L : capturedBoundary.estimatedRetainedBytes());
    }

    @Override
    public int[] voxelTypeIds() {
        return expandedIntArray(voxelTypeIds);
    }

    @Override
    public int[] mediumStateIds() {
        return expandedIntArray(mediumStateIds);
    }

    @Override
    public int[] mediumTypeIds() {
        return expandedIntArray(mediumTypeIds);
    }

    @Override
    public byte[] mediumAmounts() {
        return expandedByteArray(mediumAmounts);
    }

    @Override
    public byte[] fluidFlowX() {
        return expandedByteArray(fluidFlowX);
    }

    @Override
    public byte[] fluidFlowZ() {
        return expandedByteArray(fluidFlowZ);
    }

    @Override
    public int[] mapColors() {
        return expandedIntArray(mapColors);
    }

    @Override
    public int[] blockTintLayer0Colors() {
        return expandedIntArray(blockTintLayer0Colors);
    }

    @Override
    public int[] blockTintLayer1Colors() {
        return expandedIntArray(blockTintLayer1Colors);
    }

    @Override
    public int[] blockTintLayer2Colors() {
        return expandedIntArray(blockTintLayer2Colors);
    }

    @Override
    public int[] blockTintLayer3Colors() {
        return expandedIntArray(blockTintLayer3Colors);
    }

    @Override
    public int[] fluidMapColors() {
        return expandedIntArray(fluidMapColors);
    }

    @Override
    public byte[] lightEmissions() {
        return expandedByteArray(lightEmissions);
    }

    @Override
    public byte[] materialFlags() {
        return expandedByteArray(materialFlags);
    }

    @Override
    public byte[] shadeBrightnesses() {
        return expandedByteArray(shadeBrightnesses);
    }

    private static int[] copyIntArray(int[] source, String name) {
        return VoxelPaletteStorage.freezeInts(source, BLOCKS_PER_SECTION, name);
    }

    private static int[] copyBlockTintLayerArray(int[] source, String name) {
        Objects.requireNonNull(source, name);
        if (source.length != 1 && source.length != BLOCKS_PER_SECTION) {
            return VoxelPaletteStorage.freezeInts(source, BLOCKS_PER_SECTION, name);
        }
        int[] copy = Arrays.copyOf(source, source.length);
        for (int index = 0; index < copy.length; index++) {
            int color = copy[index];
            if (color != NO_BLOCK_TINT_LAYER_COLOR) {
                copy[index] = color & MAP_COLOR_RGB_MASK;
            }
        }
        return VoxelPaletteStorage.freezeInts(copy, BLOCKS_PER_SECTION, name);
    }

    private static byte[] copyByteArray(byte[] source, String name) {
        return VoxelPaletteStorage.freezeBytes(source, BLOCKS_PER_SECTION, name);
    }

    private static int intValueAt(int[] values, int index) {
        return VoxelPaletteStorage.intAt(values, BLOCKS_PER_SECTION, index);
    }

    private static byte byteValueAt(byte[] values, int index) {
        return VoxelPaletteStorage.byteAt(values, BLOCKS_PER_SECTION, index);
    }

    private static int[] expandedIntArray(int[] values) {
        return VoxelPaletteStorage.expandInts(values, BLOCKS_PER_SECTION);
    }

    private static byte[] expandedByteArray(byte[] values) {
        return VoxelPaletteStorage.expandBytes(values, BLOCKS_PER_SECTION);
    }

    private static boolean logicalIntArrayEquals(int[] left, int[] right) {
        return VoxelPaletteStorage.logicalIntsEqual(left, right, BLOCKS_PER_SECTION);
    }

    private static boolean logicalByteArrayEquals(byte[] left, byte[] right) {
        return VoxelPaletteStorage.logicalBytesEqual(left, right, BLOCKS_PER_SECTION);
    }

    private static int[] defaultMapColors() {
        return new int[BLOCKS_PER_SECTION];
    }

    public static int[] defaultBlockTintLayerColors() {
        int[] colors = new int[BLOCKS_PER_SECTION];
        Arrays.fill(colors, NO_BLOCK_TINT_LAYER_COLOR);
        return colors;
    }

    private static byte[] defaultFluidFlow() {
        return new byte[BLOCKS_PER_SECTION];
    }

    private static int[] defaultFluidMapColors(int[] mapColors, byte[] mediumAmounts) {
        Objects.requireNonNull(mapColors, "mapColors");
        Objects.requireNonNull(mediumAmounts, "mediumAmounts");
        if (mapColors.length != BLOCKS_PER_SECTION || mediumAmounts.length != BLOCKS_PER_SECTION) {
            throw new IllegalArgumentException("legacy fluid map color defaults require full section arrays");
        }
        int[] fluidMapColors = new int[BLOCKS_PER_SECTION];
        for (int index = 0; index < BLOCKS_PER_SECTION; index++) {
            if (Byte.toUnsignedInt(mediumAmounts[index]) > 0) {
                fluidMapColors[index] = mapColors[index];
            }
        }
        return fluidMapColors;
    }

    private static byte[] defaultLightEmissions() {
        return new byte[BLOCKS_PER_SECTION];
    }

    private static byte[] defaultShadeBrightnesses(int[] voxelTypeIds, byte[] mediumAmounts) {
        Objects.requireNonNull(voxelTypeIds, "voxelTypeIds");
        Objects.requireNonNull(mediumAmounts, "mediumAmounts");
        if (voxelTypeIds.length != BLOCKS_PER_SECTION || mediumAmounts.length != BLOCKS_PER_SECTION) {
            throw new IllegalArgumentException("legacy shade defaults require full section arrays");
        }
        byte[] shadeBrightnesses = new byte[BLOCKS_PER_SECTION];
        Arrays.fill(shadeBrightnesses, (byte) 255);
        return shadeBrightnesses;
    }

    private static int[] defaultFluidVoxelStateIds(int[] voxelTypeIds, byte[] mediumAmounts) {
        Objects.requireNonNull(voxelTypeIds, "voxelTypeIds");
        Objects.requireNonNull(mediumAmounts, "mediumAmounts");
        if (voxelTypeIds.length != BLOCKS_PER_SECTION || mediumAmounts.length != BLOCKS_PER_SECTION) {
            throw new IllegalArgumentException("legacy fluid defaults require full section arrays");
        }
        int[] mediumStateIds = new int[BLOCKS_PER_SECTION];
        for (int index = 0; index < BLOCKS_PER_SECTION; index++) {
            if (Byte.toUnsignedInt(mediumAmounts[index]) > 0) {
                mediumStateIds[index] = voxelTypeIds[index];
            }
        }
        return mediumStateIds;
    }

    private static int[] defaultFluidTypeIds(int[] mediumStateIds, byte[] mediumAmounts) {
        Objects.requireNonNull(mediumStateIds, "mediumStateIds");
        Objects.requireNonNull(mediumAmounts, "mediumAmounts");
        if (mediumStateIds.length != BLOCKS_PER_SECTION || mediumAmounts.length != BLOCKS_PER_SECTION) {
            throw new IllegalArgumentException("legacy fluid type defaults require full section arrays");
        }
        int[] mediumTypeIds = new int[BLOCKS_PER_SECTION];
        for (int index = 0; index < BLOCKS_PER_SECTION; index++) {
            if (Byte.toUnsignedInt(mediumAmounts[index]) > 0) {
                mediumTypeIds[index] = mediumStateIds[index];
            }
        }
        return mediumTypeIds;
    }

    private static byte[] defaultMaterialFlags(int[] voxelTypeIds, byte[] mediumAmounts) {
        Objects.requireNonNull(voxelTypeIds, "voxelTypeIds");
        Objects.requireNonNull(mediumAmounts, "mediumAmounts");
        if (voxelTypeIds.length != BLOCKS_PER_SECTION || mediumAmounts.length != BLOCKS_PER_SECTION) {
            throw new IllegalArgumentException("legacy material defaults require full section arrays");
        }
        byte[] flags = new byte[BLOCKS_PER_SECTION];
        for (int index = 0; index < BLOCKS_PER_SECTION; index++) {
            int mediumAmount = Byte.toUnsignedInt(mediumAmounts[index]);
            if (voxelTypeIds[index] != 0) {
                int materialFlags = FLAG_RENDER_SHAPE_VISIBLE | FLAG_OCCLUDES_NEIGHBORS | FLAG_FLUID_HEIGHT_SOLID;
                if (mediumAmount > 0) {
                    materialFlags |= FLAG_LIQUID;
                }
                if ((materialFlags & FLAG_OCCLUDES_NEIGHBORS) == 0 || (materialFlags & FLAG_LIQUID) != 0) {
                    materialFlags |= FLAG_AO_TRANSLUCENT;
                }
                flags[index] = (byte) materialFlags;
            } else if (mediumAmount > 0) {
                flags[index] = (byte) (FLAG_LIQUID | FLAG_AO_TRANSLUCENT);
            } else {
                flags[index] = (byte) (FLAG_AIR | FLAG_AO_TRANSLUCENT);
            }
        }
        return flags;
    }

    private static int clampLightNibble(int value) {
        return Math.max(0, Math.min(15, value));
    }

    private static void checkLinearIndex(int index) {
        if (index < 0 || index >= BLOCKS_PER_SECTION) {
            throw new IndexOutOfBoundsException("linear block index outside section: " + index);
        }
    }
}
