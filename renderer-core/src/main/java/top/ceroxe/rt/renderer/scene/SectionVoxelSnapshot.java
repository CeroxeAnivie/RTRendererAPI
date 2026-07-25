package top.ceroxe.rt.renderer.scene;

import java.util.Arrays;
import java.util.Objects;

/**
 * 一个 16x16x16 chunk section 的 renderer-owned 体素材料快照。
 *
 * <p>快照只保存 primitive material facts。它不是最终 RT 几何格式，但已经是
 * Vulkan BLAS 构建前必须拥有的稳定输入：不引用 host 对象，不会被世界线程继续修改。</p>
 *
 * @param key                   区段坐标
 * @param voxelTypeIds          每个体素的渲染器内部类型标识
 * @param mediumStateIds        每个体素位置的介质状态标识
 * @param mediumTypeIds         每个体素位置的介质类型标识
 * @param mediumAmounts         每个体素位置的介质填充量
 * @param fluidFlowX            每个体素位置的流体 X 方向流速
 * @param fluidFlowZ            每个体素位置的流体 Z 方向流速
 * @param mapColors             每个体素位置打包后的地图颜色与光照
 * @param blockTintLayer0Colors 第一染色层颜色
 * @param blockTintLayer1Colors 第二染色层颜色
 * @param blockTintLayer2Colors 第三染色层颜色
 * @param blockTintLayer3Colors 第四染色层颜色
 * @param fluidMapColors        每个体素位置的流体地图颜色
 * @param lightEmissions        每个体素位置的自发光强度
 * @param materialFlags         每个体素位置的材料标志位
 * @param shadeBrightnesses     每个体素位置的明暗系数
 * @param hasOnlyAir            区段是否只包含空气
 * @param hasFluid              区段是否包含流体
 * @param capturedBoundary      可选的、与中心数据同时捕获的边界采样
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
     * Record components expose the logical source section fields, while their
     * private arrays may use VoxelPaletteStorage. Public accessors always
     * expand to 4096 elements, preserving the original immutable API contract.
     */
    /**
     * Edge length, in voxels, of a section snapshot.
     */
    public static final int SECTION_SIZE = 16;
    /**
     * Number of voxels in one section snapshot.
     */
    public static final int BLOCKS_PER_SECTION = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;
    /**
     * Packed map-color sentinel used when no color was captured.
     */
    public static final int NO_MAP_COLOR = 0;
    /**
     * Material bit indicating that a render-shape contributes visible geometry.
     */
    public static final int FLAG_RENDER_SHAPE_VISIBLE = 1;
    /**
     * Material bit indicating an air voxel.
     */
    public static final int FLAG_AIR = 1 << 1;
    /**
     * Material bit indicating liquid content.
     */
    public static final int FLAG_LIQUID = 1 << 2;
    /**
     * Material bit indicating that the voxel occludes adjacent faces.
     */
    public static final int FLAG_OCCLUDES_NEIGHBORS = 1 << 3;
    /**
     * Material bit indicating that neighbor-occlusion information was captured.
     */
    public static final int FLAG_OCCLUSION_KNOWN = 1 << 4;
    /**
     * Material bit indicating that light information was captured.
     */
    public static final int FLAG_LIGHT_KNOWN = 1 << 5;
    /**
     * Material bit indicating that ambient occlusion treats the voxel as translucent.
     */
    public static final int FLAG_AO_TRANSLUCENT = 1 << 6;
    /**
     * Material bit indicating that the voxel behaves as a solid fluid-height boundary.
     */
    public static final int FLAG_FLUID_HEIGHT_SOLID = 1 << 7;
    /**
     * Mask selecting the 24-bit RGB portion of a packed map-color value.
     */
    public static final int MAP_COLOR_RGB_MASK = 0x00FF_FFFF;
    /**
     * Bit offset of the packed sky/block-light byte.
     */
    public static final int PACKED_LIGHT_SHIFT = 24;
    /**
     * Mask selecting the packed sky/block-light byte.
     */
    public static final int PACKED_LIGHT_MASK = 0xFF00_0000;
    /**
     * Tint-layer sentinel indicating that a layer supplies no color.
     */
    public static final int NO_BLOCK_TINT_LAYER_COLOR = -1;
    /**
     * Number of independent block-tint layers retained by a snapshot.
     */
    public static final int CAPTURED_BLOCK_TINT_LAYER_COUNT = 4;

    /**
     * Freezes all voxel arrays and validates their section-wide logical sizes.
     */
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

    /**
     * Creates a snapshot without a captured boundary halo.
     *
     * @param key                   section identity
     * @param voxelTypeIds          voxel type identifiers
     * @param mediumStateIds        medium state identifiers
     * @param mediumTypeIds         medium type identifiers
     * @param mediumAmounts         unsigned medium fill amounts
     * @param fluidFlowX            signed fluid x-flow values
     * @param fluidFlowZ            signed fluid z-flow values
     * @param mapColors             packed map colors and light
     * @param blockTintLayer0Colors first block-tint layer
     * @param blockTintLayer1Colors second block-tint layer
     * @param blockTintLayer2Colors third block-tint layer
     * @param blockTintLayer3Colors fourth block-tint layer
     * @param fluidMapColors        packed fluid map colors
     * @param lightEmissions        unsigned emission strengths
     * @param materialFlags         material classification bits
     * @param shadeBrightnesses     unsigned shade-brightness values
     * @param hasOnlyAir            whether the section contains only air
     * @param hasFluid              whether the section contains fluid
     */
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

    /**
     * Creates a compatibility snapshot whose flow and block-tint layers are unavailable.
     *
     * @param key               section identity
     * @param voxelTypeIds      voxel type identifiers
     * @param mediumStateIds    medium state identifiers
     * @param mediumAmounts     unsigned medium fill amounts
     * @param mapColors         packed map colors and light
     * @param fluidMapColors    packed fluid map colors
     * @param lightEmissions    unsigned emission strengths
     * @param materialFlags     material classification bits
     * @param shadeBrightnesses unsigned shade-brightness values
     * @param hasOnlyAir        whether the section contains only air
     * @param hasFluid          whether the section contains fluid
     */
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

    /**
     * Creates a compatibility snapshot with explicit medium types and default flow and tint layers.
     *
     * @param key               section identity
     * @param voxelTypeIds      voxel type identifiers
     * @param mediumStateIds    medium state identifiers
     * @param mediumTypeIds     medium type identifiers
     * @param mediumAmounts     unsigned medium fill amounts
     * @param mapColors         packed map colors and light
     * @param fluidMapColors    packed fluid map colors
     * @param lightEmissions    unsigned emission strengths
     * @param materialFlags     material classification bits
     * @param shadeBrightnesses unsigned shade-brightness values
     * @param hasOnlyAir        whether the section contains only air
     * @param hasFluid          whether the section contains fluid
     */
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

    /**
     * Creates a legacy snapshot and derives medium types, fluid colors, and shade brightness.
     *
     * @param key            section identity
     * @param voxelTypeIds   voxel type identifiers
     * @param mediumStateIds medium state identifiers
     * @param mediumAmounts  unsigned medium fill amounts
     * @param mapColors      packed map colors and light
     * @param lightEmissions unsigned emission strengths
     * @param materialFlags  material classification bits
     * @param hasOnlyAir     whether the section contains only air
     * @param hasFluid       whether the section contains fluid
     */
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

    /**
     * Creates a legacy snapshot and derives all medium semantic fields from voxel identifiers.
     *
     * @param key            section identity
     * @param voxelTypeIds   voxel type identifiers
     * @param mediumAmounts  unsigned medium fill amounts
     * @param mapColors      packed map colors and light
     * @param lightEmissions unsigned emission strengths
     * @param materialFlags  material classification bits
     * @param hasOnlyAir     whether the section contains only air
     * @param hasFluid       whether the section contains fluid
     */
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

    /**
     * Creates the minimal legacy snapshot, deriving all unavailable visual semantics.
     *
     * @param key           section identity
     * @param voxelTypeIds  voxel type identifiers
     * @param mediumAmounts unsigned medium fill amounts
     * @param hasOnlyAir    whether the section contains only air
     * @param hasFluid      whether the section contains fluid
     */
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

    /**
     * Converts section-local coordinates to the canonical linear voxel index.
     *
     * @param x local x coordinate in {@code [0, 15]}
     * @param y local y coordinate in {@code [0, 15]}
     * @param z local z coordinate in {@code [0, 15]}
     * @return canonical linear index in {@code [0, 4095]}
     * @throws IndexOutOfBoundsException if any coordinate is outside the section
     */
    public static int localBlockIndex(int x, int y, int z) {
        if ((x | y | z) < 0 || x >= SECTION_SIZE || y >= SECTION_SIZE || z >= SECTION_SIZE) {
            throw new IndexOutOfBoundsException("local block coordinate outside 16x16x16 section: ("
                    + x + ", " + y + ", " + z + ")");
        }
        return (y * SECTION_SIZE + z) * SECTION_SIZE + x;
    }

    /**
     * Packs 24-bit map color and clamped four-bit sky/block light values.
     *
     * @param mapColor   RGB map color; high bits are ignored
     * @param skyLight   sky-light level, clamped to {@code [0, 15]}
     * @param blockLight block-light level, clamped to {@code [0, 15]}
     * @return packed color and light value
     */
    public static int packMapColorAndLight(int mapColor, int skyLight, int blockLight) {
        return (mapColor & MAP_COLOR_RGB_MASK)
                | (clampLightNibble(skyLight) << 28)
                | (clampLightNibble(blockLight) << PACKED_LIGHT_SHIFT);
    }

    /**
     * Extracts the RGB portion of a packed color/light value.
     *
     * @param packedMapColorAndLight packed color/light value
     * @return its 24-bit RGB portion
     */
    public static int mapColorRgb(int packedMapColorAndLight) {
        return packedMapColorAndLight & MAP_COLOR_RGB_MASK;
    }

    /**
     * Extracts the combined sky/block-light byte.
     *
     * @param packedMapColorAndLight packed color/light value
     * @return its unsigned packed light byte
     */
    public static int packedLight(int packedMapColorAndLight) {
        return (packedMapColorAndLight >>> PACKED_LIGHT_SHIFT) & 0xFF;
    }

    /**
     * Replaces only the RGB portion of a packed color/light value.
     *
     * @param packedMapColorAndLight original packed value
     * @param mapColorRgb            replacement 24-bit RGB value
     * @return value retaining the original light byte and using the replacement RGB bits
     */
    public static int replaceMapColorRgb(int packedMapColorAndLight, int mapColorRgb) {
        return (packedMapColorAndLight & PACKED_LIGHT_MASK)
                | (mapColorRgb & MAP_COLOR_RGB_MASK);
    }

    /**
     * Combines independently packed RGB and light fields.
     *
     * @param mapColorRgb 24-bit RGB value
     * @param packedLight unsigned sky/block-light byte
     * @return packed color and light value
     */
    public static int packMapColorWithPackedLight(int mapColorRgb, int packedLight) {
        return (mapColorRgb & MAP_COLOR_RGB_MASK)
                | ((packedLight & 0xFF) << PACKED_LIGHT_SHIFT);
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

    /**
     * Creates a default unavailable block-tint layer.
     *
     * @return a full section tint layer initialized to {@link #NO_BLOCK_TINT_LAYER_COLOR}
     */
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

    /**
     * Counts the primitive-array payload retained by this immutable snapshot.
     * JVM object and array headers are intentionally excluded because their size
     * depends on the selected VM layout; the returned value is exact for voxel data.
     *
     * @return exact primitive payload size in bytes, including a captured boundary if present
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

    /**
     * Reads the voxel type at section-local coordinates.
     *
     * @param x local x coordinate
     * @param y local y coordinate
     * @param z local z coordinate
     * @return voxel type identifier at the coordinate
     * @throws IndexOutOfBoundsException if a coordinate is outside the section
     */
    public int voxelTypeIdAt(int x, int y, int z) {
        return voxelTypeIdAtLinearIndex(localBlockIndex(x, y, z));
    }

    /**
     * Reads the unsigned medium amount at section-local coordinates.
     *
     * @param x local x coordinate
     * @param y local y coordinate
     * @param z local z coordinate
     * @return unsigned medium amount at the coordinate
     * @throws IndexOutOfBoundsException if a coordinate is outside the section
     */
    public int mediumAmountAt(int x, int y, int z) {
        return mediumAmountAtLinearIndex(localBlockIndex(x, y, z));
    }

    /**
     * Reads the medium state at section-local coordinates.
     *
     * @param x local x coordinate
     * @param y local y coordinate
     * @param z local z coordinate
     * @return medium state identifier at the coordinate
     * @throws IndexOutOfBoundsException if a coordinate is outside the section
     */
    public int mediumStateIdAt(int x, int y, int z) {
        return mediumStateIdAtLinearIndex(localBlockIndex(x, y, z));
    }

    /**
     * Reads the medium type at section-local coordinates.
     *
     * @param x local x coordinate
     * @param y local y coordinate
     * @param z local z coordinate
     * @return medium type identifier at the coordinate
     * @throws IndexOutOfBoundsException if a coordinate is outside the section
     */
    public int mediumTypeIdAt(int x, int y, int z) {
        return mediumTypeIdAtLinearIndex(localBlockIndex(x, y, z));
    }

    /**
     * Reads the signed fluid x-flow at section-local coordinates.
     *
     * @param x local x coordinate
     * @param y local y coordinate
     * @param z local z coordinate
     * @return signed x-flow value at the coordinate
     * @throws IndexOutOfBoundsException if a coordinate is outside the section
     */
    public int fluidFlowXAt(int x, int y, int z) {
        return fluidFlowXAtLinearIndex(localBlockIndex(x, y, z));
    }

    /**
     * Reads the signed fluid z-flow at section-local coordinates.
     *
     * @param x local x coordinate
     * @param y local y coordinate
     * @param z local z coordinate
     * @return signed z-flow value at the coordinate
     * @throws IndexOutOfBoundsException if a coordinate is outside the section
     */
    public int fluidFlowZAt(int x, int y, int z) {
        return fluidFlowZAtLinearIndex(localBlockIndex(x, y, z));
    }

    /**
     * Reads packed map color and light at section-local coordinates.
     *
     * @param x local x coordinate
     * @param y local y coordinate
     * @param z local z coordinate
     * @return packed map color and light at the coordinate
     * @throws IndexOutOfBoundsException if a coordinate is outside the section
     */
    public int mapColorAt(int x, int y, int z) {
        return mapColorAtLinearIndex(localBlockIndex(x, y, z));
    }

    /**
     * Reads packed fluid map color at section-local coordinates.
     *
     * @param x local x coordinate
     * @param y local y coordinate
     * @param z local z coordinate
     * @return packed fluid map color at the coordinate
     * @throws IndexOutOfBoundsException if a coordinate is outside the section
     */
    public int fluidMapColorAt(int x, int y, int z) {
        return fluidMapColorAtLinearIndex(localBlockIndex(x, y, z));
    }

    /**
     * Reads one captured block-tint layer at section-local coordinates.
     *
     * @param x         local x coordinate
     * @param y         local y coordinate
     * @param z         local z coordinate
     * @param tintLayer tint layer in {@code [0, 3]}
     * @return tint RGB value, or {@link #NO_BLOCK_TINT_LAYER_COLOR} for an unavailable layer
     * @throws IndexOutOfBoundsException if a coordinate is outside the section
     */
    public int blockTintLayerColorAt(int x, int y, int z, int tintLayer) {
        return blockTintLayerColorAtLinearIndex(localBlockIndex(x, y, z), tintLayer);
    }

    /**
     * Reads light emission at section-local coordinates.
     *
     * @param x local x coordinate
     * @param y local y coordinate
     * @param z local z coordinate
     * @return unsigned light-emission strength at the coordinate
     * @throws IndexOutOfBoundsException if a coordinate is outside the section
     */
    public int lightEmissionAt(int x, int y, int z) {
        return lightEmissionAtLinearIndex(localBlockIndex(x, y, z));
    }

    /**
     * Reads material classification flags at section-local coordinates.
     *
     * @param x local x coordinate
     * @param y local y coordinate
     * @param z local z coordinate
     * @return unsigned material flag bits at the coordinate
     * @throws IndexOutOfBoundsException if a coordinate is outside the section
     */
    public int materialFlagsAt(int x, int y, int z) {
        return materialFlagsAtLinearIndex(localBlockIndex(x, y, z));
    }

    /**
     * Reads shade brightness at section-local coordinates.
     *
     * @param x local x coordinate
     * @param y local y coordinate
     * @param z local z coordinate
     * @return unsigned shade-brightness value at the coordinate
     * @throws IndexOutOfBoundsException if a coordinate is outside the section
     */
    public int shadeBrightnessAt(int x, int y, int z) {
        return shadeBrightnessAtLinearIndex(localBlockIndex(x, y, z));
    }

    /**
     * Reads the voxel type at a canonical linear index.
     *
     * @param index canonical linear voxel index
     * @return voxel type identifier at {@code index}
     */
    public int voxelTypeIdAtLinearIndex(int index) {
        checkLinearIndex(index);
        return intValueAt(voxelTypeIds, index);
    }

    /**
     * Reads the unsigned medium amount at a canonical linear index.
     *
     * @param index canonical linear voxel index
     * @return unsigned medium amount at {@code index}
     */
    public int mediumAmountAtLinearIndex(int index) {
        checkLinearIndex(index);
        return Byte.toUnsignedInt(byteValueAt(mediumAmounts, index));
    }

    /**
     * Reads the medium state at a canonical linear index.
     *
     * @param index canonical linear voxel index
     * @return medium state identifier at {@code index}
     */
    public int mediumStateIdAtLinearIndex(int index) {
        checkLinearIndex(index);
        return intValueAt(mediumStateIds, index);
    }

    /**
     * Reads the medium type at a canonical linear index.
     *
     * @param index canonical linear voxel index
     * @return medium type identifier at {@code index}
     */
    public int mediumTypeIdAtLinearIndex(int index) {
        checkLinearIndex(index);
        return intValueAt(mediumTypeIds, index);
    }

    /**
     * Reads signed fluid x-flow at a canonical linear index.
     *
     * @param index canonical linear voxel index
     * @return signed x-flow at {@code index}
     */
    public int fluidFlowXAtLinearIndex(int index) {
        checkLinearIndex(index);
        return byteValueAt(fluidFlowX, index);
    }

    /**
     * Reads signed fluid z-flow at a canonical linear index.
     *
     * @param index canonical linear voxel index
     * @return signed z-flow at {@code index}
     */
    public int fluidFlowZAtLinearIndex(int index) {
        checkLinearIndex(index);
        return byteValueAt(fluidFlowZ, index);
    }

    /**
     * Reads packed map color and light at a canonical linear index.
     *
     * @param index canonical linear voxel index
     * @return packed map color and light at {@code index}
     */
    public int mapColorAtLinearIndex(int index) {
        checkLinearIndex(index);
        return intValueAt(mapColors, index);
    }

    /**
     * Reads packed fluid map color at a canonical linear index.
     *
     * @param index canonical linear voxel index
     * @return packed fluid map color at {@code index}
     */
    public int fluidMapColorAtLinearIndex(int index) {
        checkLinearIndex(index);
        return intValueAt(fluidMapColors, index);
    }

    /**
     * Reads one captured block-tint layer at a canonical linear index.
     *
     * @param index     canonical linear voxel index
     * @param tintLayer tint layer in {@code [0, 3]}
     * @return tint RGB value, or {@link #NO_BLOCK_TINT_LAYER_COLOR} for an unavailable layer
     */
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

    /**
     * Reads unsigned light emission at a canonical linear index.
     *
     * @param index canonical linear voxel index
     * @return unsigned light emission at {@code index}
     */
    public int lightEmissionAtLinearIndex(int index) {
        checkLinearIndex(index);
        return Byte.toUnsignedInt(byteValueAt(lightEmissions, index));
    }

    /**
     * Reads material classification flags at a canonical linear index.
     *
     * @param index canonical linear voxel index
     * @return unsigned material flag bits at {@code index}
     */
    public int materialFlagsAtLinearIndex(int index) {
        checkLinearIndex(index);
        return Byte.toUnsignedInt(byteValueAt(materialFlags, index));
    }

    /**
     * Reads unsigned shade brightness at a canonical linear index.
     *
     * @param index canonical linear voxel index
     * @return unsigned shade brightness at {@code index}
     */
    public int shadeBrightnessAtLinearIndex(int index) {
        checkLinearIndex(index);
        return Byte.toUnsignedInt(byteValueAt(shadeBrightnesses, index));
    }

    /**
     * Tests whether a voxel contributes visible render geometry.
     *
     * @param index canonical linear voxel index
     * @return whether visible render geometry is present
     */
    public boolean visibleRenderShapeAtLinearIndex(int index) {
        return (materialFlagsAtLinearIndex(index) & FLAG_RENDER_SHAPE_VISIBLE) != 0;
    }

    /**
     * Tests exact center and boundary voxel equality.
     *
     * @param other candidate snapshot
     * @return whether center and boundary voxel content are equal
     */
    public boolean hasSameVoxelContent(SectionVoxelSnapshot other) {
        return hasSameCenterVoxelContent(other) && sameCapturedBoundary(other);
    }

    /**
     * Tests exact center payload equality, deliberately excluding the compiled-region halo.
     *
     * @param other candidate snapshot
     * @return whether the center payloads are equal
     */
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

    /**
     * Tests equality of every field that can affect generated geometry.
     *
     * @param other candidate snapshot
     * @return whether all geometry-affecting content is equal
     */
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

    /**
     * Returns an immutable copy with a replacement captured boundary.
     *
     * @param boundary replacement captured boundary, or {@code null} to remove it
     * @return this snapshot when unchanged, otherwise an immutable snapshot with the replacement
     */
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

    /**
     * Estimates retained primitive payload memory.
     *
     * @return estimated bytes retained by primitive payloads and the optional boundary
     */
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

    /**
     * Returns a full-size defensive copy of voxel type identifiers.
     *
     * @return expanded voxel type identifiers
     */
    @Override
    public int[] voxelTypeIds() {
        return expandedIntArray(voxelTypeIds);
    }

    /**
     * Returns a full-size defensive copy of medium state identifiers.
     *
     * @return expanded medium state identifiers
     */
    @Override
    public int[] mediumStateIds() {
        return expandedIntArray(mediumStateIds);
    }

    /**
     * Returns a full-size defensive copy of medium type identifiers.
     *
     * @return expanded medium type identifiers
     */
    @Override
    public int[] mediumTypeIds() {
        return expandedIntArray(mediumTypeIds);
    }

    /**
     * Returns a full-size defensive copy of medium amounts.
     *
     * @return expanded medium amounts
     */
    @Override
    public byte[] mediumAmounts() {
        return expandedByteArray(mediumAmounts);
    }

    /**
     * Returns a full-size defensive copy of fluid x-flow.
     *
     * @return expanded fluid x-flow
     */
    @Override
    public byte[] fluidFlowX() {
        return expandedByteArray(fluidFlowX);
    }

    /**
     * Returns a full-size defensive copy of fluid z-flow.
     *
     * @return expanded fluid z-flow
     */
    @Override
    public byte[] fluidFlowZ() {
        return expandedByteArray(fluidFlowZ);
    }

    /**
     * Returns a full-size defensive copy of packed map colors.
     *
     * @return expanded packed map colors
     */
    @Override
    public int[] mapColors() {
        return expandedIntArray(mapColors);
    }

    /**
     * Returns a full-size defensive copy of block-tint layer zero.
     *
     * @return expanded first tint layer
     */
    @Override
    public int[] blockTintLayer0Colors() {
        return expandedIntArray(blockTintLayer0Colors);
    }

    /**
     * Returns a full-size defensive copy of block-tint layer one.
     *
     * @return expanded second tint layer
     */
    @Override
    public int[] blockTintLayer1Colors() {
        return expandedIntArray(blockTintLayer1Colors);
    }

    /**
     * Returns a full-size defensive copy of block-tint layer two.
     *
     * @return expanded third tint layer
     */
    @Override
    public int[] blockTintLayer2Colors() {
        return expandedIntArray(blockTintLayer2Colors);
    }

    /**
     * Returns a full-size defensive copy of block-tint layer three.
     *
     * @return expanded fourth tint layer
     */
    @Override
    public int[] blockTintLayer3Colors() {
        return expandedIntArray(blockTintLayer3Colors);
    }

    /**
     * Returns a full-size defensive copy of fluid map colors.
     *
     * @return expanded fluid map colors
     */
    @Override
    public int[] fluidMapColors() {
        return expandedIntArray(fluidMapColors);
    }

    /**
     * Returns a full-size defensive copy of light emissions.
     *
     * @return expanded light emissions
     */
    @Override
    public byte[] lightEmissions() {
        return expandedByteArray(lightEmissions);
    }

    /**
     * Returns a full-size defensive copy of material flags.
     *
     * @return expanded material flags
     */
    @Override
    public byte[] materialFlags() {
        return expandedByteArray(materialFlags);
    }

    /**
     * Returns a full-size defensive copy of shade brightness values.
     *
     * @return expanded shade brightness values
     */
    @Override
    public byte[] shadeBrightnesses() {
        return expandedByteArray(shadeBrightnesses);
    }
}
