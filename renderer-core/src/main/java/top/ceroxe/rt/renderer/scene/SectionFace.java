package top.ceroxe.rt.renderer.scene;

import java.util.Objects;

/**
 * 一个 section-local 单位体素面的保守几何记录。
 *
 * <p>这里先保留 unit face，而不是急着做 greedy meshing。原因是 RT backend 后续需要
 * 先验证 block material、透明规则、邻接剔除和 BLAS rebuild 粒度；过早合并会让错误
 * 来源更难定位。</p>
 *
 * @param x                    section-local voxel x coordinate
 * @param y                    section-local voxel y coordinate
 * @param z                    section-local voxel z coordinate
 * @param direction            outward face direction
 * @param voxelTypeId          stable voxel material identifier
 * @param mediumAmount         unsigned medium amount
 * @param mapColor             packed map color
 * @param blockTintLayer0Color first packed tint-layer color
 * @param blockTintLayer1Color second packed tint-layer color
 * @param blockTintLayer2Color third packed tint-layer color
 * @param blockTintLayer3Color fourth packed tint-layer color
 * @param lightEmission        unsigned light emission
 * @param materialFlags        unsigned material flags
 * @param faceVisible          whether the face is visible
 * @param vertexLighting0      packed first-corner lighting
 * @param vertexLighting1      packed second-corner lighting
 * @param vertexLighting2      packed third-corner lighting
 * @param vertexLighting3      packed fourth-corner lighting
 * @param fluidHeight0         first-corner fluid height
 * @param fluidHeight1         second-corner fluid height
 * @param fluidHeight2         third-corner fluid height
 * @param fluidHeight3         fourth-corner fluid height
 * @param fluidFlowX           encoded fluid x-flow
 * @param fluidFlowZ           encoded fluid z-flow
 * @param fluidOverlay         whether overlay rendering is required
 * @param mediumTypeId         stable medium type identifier
 */
public record SectionFace(
        int x,
        int y,
        int z,
        FaceDirection direction,
        int voxelTypeId,
        int mediumAmount,
        int mapColor,
        int blockTintLayer0Color,
        int blockTintLayer1Color,
        int blockTintLayer2Color,
        int blockTintLayer3Color,
        int lightEmission,
        int materialFlags,
        boolean faceVisible,
        int vertexLighting0,
        int vertexLighting1,
        int vertexLighting2,
        int vertexLighting3,
        int fluidHeight0,
        int fluidHeight1,
        int fluidHeight2,
        int fluidHeight3,
        int fluidFlowX,
        int fluidFlowZ,
        boolean fluidOverlay,
        int mediumTypeId
) {
    /**
     * Fluid corner averages must survive until final mesh quantization.  An
     * 8-bit height loses enough precision to move a source renderer's surface offset by
     * multiple position/UV units.  The full positive signed-short range keeps
     * the existing payload layout while matching 16-bit UV precision.
     */
    public static final int FLUID_HEIGHT_SCALE = Short.MAX_VALUE;
    /**
     * Sentinel indicating an unavailable fluid type.
     */
    public static final int UNKNOWN_FLUID_TYPE_ID = -1;

    /**
     * Validates coordinates, direction, and encoded fluid semantics.
     */
    public SectionFace {
        SectionVoxelSnapshot.localBlockIndex(x, y, z);
        direction = Objects.requireNonNull(direction, "direction");
        if (mediumAmount < 0 || mediumAmount > 255) {
            throw new IllegalArgumentException("mediumAmount must be unsigned byte compatible: " + mediumAmount);
        }
        if (lightEmission < 0 || lightEmission > 255) {
            throw new IllegalArgumentException("lightEmission must be unsigned byte compatible: " + lightEmission);
        }
        if (materialFlags < 0 || materialFlags > 255) {
            throw new IllegalArgumentException("materialFlags must be unsigned byte compatible: " + materialFlags);
        }
        blockTintLayer0Color = validateBlockTintLayerColor(blockTintLayer0Color, "blockTintLayer0Color");
        blockTintLayer1Color = validateBlockTintLayerColor(blockTintLayer1Color, "blockTintLayer1Color");
        blockTintLayer2Color = validateBlockTintLayerColor(blockTintLayer2Color, "blockTintLayer2Color");
        blockTintLayer3Color = validateBlockTintLayerColor(blockTintLayer3Color, "blockTintLayer3Color");
        fluidHeight0 = validateFluidHeight(fluidHeight0);
        fluidHeight1 = validateFluidHeight(fluidHeight1);
        fluidHeight2 = validateFluidHeight(fluidHeight2);
        fluidHeight3 = validateFluidHeight(fluidHeight3);
        fluidFlowX = validateFluidFlow(fluidFlowX, "fluidFlowX");
        fluidFlowZ = validateFluidFlow(fluidFlowZ, "fluidFlowZ");
        if (mediumTypeId < UNKNOWN_FLUID_TYPE_ID) {
            throw new IllegalArgumentException("mediumTypeId must be non-negative or UNKNOWN: " + mediumTypeId);
        }
    }

    /**
     * Creates a legacy face with unavailable extended material semantics.
     *
     * @param x                    section-local voxel x coordinate
     * @param y                    section-local voxel y coordinate
     * @param z                    section-local voxel z coordinate
     * @param direction            outward face direction
     * @param voxelTypeId          stable voxel material identifier
     * @param mediumAmount         unsigned medium amount
     * @param mapColor             packed map color
     * @param blockTintLayer0Color first packed tint-layer color
     * @param blockTintLayer1Color second packed tint-layer color
     * @param blockTintLayer2Color third packed tint-layer color
     * @param blockTintLayer3Color fourth packed tint-layer color
     * @param lightEmission        unsigned light emission
     * @param materialFlags        unsigned material flags
     * @param faceVisible          whether the face is visible
     * @param vertexLighting0      packed first-corner lighting
     * @param vertexLighting1      packed second-corner lighting
     * @param vertexLighting2      packed third-corner lighting
     * @param vertexLighting3      packed fourth-corner lighting
     * @param fluidHeight0         first-corner fluid height
     * @param fluidHeight1         second-corner fluid height
     * @param fluidHeight2         third-corner fluid height
     * @param fluidHeight3         fourth-corner fluid height
     * @param fluidFlowX           signed fluid x-flow
     * @param fluidFlowZ           signed fluid z-flow
     * @param fluidOverlay         whether fluid overlay rendering is required
     */
    public SectionFace(
            int x,
            int y,
            int z,
            FaceDirection direction,
            int voxelTypeId,
            int mediumAmount,
            int mapColor,
            int blockTintLayer0Color,
            int blockTintLayer1Color,
            int blockTintLayer2Color,
            int blockTintLayer3Color,
            int lightEmission,
            int materialFlags,
            boolean faceVisible,
            int vertexLighting0,
            int vertexLighting1,
            int vertexLighting2,
            int vertexLighting3,
            int fluidHeight0,
            int fluidHeight1,
            int fluidHeight2,
            int fluidHeight3,
            int fluidFlowX,
            int fluidFlowZ,
            boolean fluidOverlay
    ) {
        this(
                x, y, z, direction, voxelTypeId, mediumAmount, mapColor,
                blockTintLayer0Color, blockTintLayer1Color, blockTintLayer2Color, blockTintLayer3Color,
                lightEmission, materialFlags, faceVisible,
                vertexLighting0, vertexLighting1, vertexLighting2, vertexLighting3,
                fluidHeight0, fluidHeight1, fluidHeight2, fluidHeight3,
                fluidFlowX, fluidFlowZ, fluidOverlay, UNKNOWN_FLUID_TYPE_ID
        );
    }

    /**
     * Creates a compatibility face with captured color and material flags.
     *
     * @param x               section-local voxel x coordinate
     * @param y               section-local voxel y coordinate
     * @param z               section-local voxel z coordinate
     * @param direction       outward face direction
     * @param voxelTypeId     stable voxel material identifier
     * @param mediumAmount    unsigned medium amount
     * @param mapColor        packed map color
     * @param lightEmission   unsigned light emission
     * @param materialFlags   unsigned material flags
     * @param faceVisible     whether the face is visible
     * @param vertexLighting0 packed first-corner lighting
     * @param vertexLighting1 packed second-corner lighting
     * @param vertexLighting2 packed third-corner lighting
     * @param vertexLighting3 packed fourth-corner lighting
     */
    public SectionFace(
            int x,
            int y,
            int z,
            FaceDirection direction,
            int voxelTypeId,
            int mediumAmount,
            int mapColor,
            int lightEmission,
            int materialFlags,
            boolean faceVisible,
            int vertexLighting0,
            int vertexLighting1,
            int vertexLighting2,
            int vertexLighting3
    ) {
        this(
                x,
                y,
                z,
                direction,
                voxelTypeId,
                mediumAmount,
                mapColor,
                SectionVoxelSnapshot.NO_BLOCK_TINT_LAYER_COLOR,
                SectionVoxelSnapshot.NO_BLOCK_TINT_LAYER_COLOR,
                SectionVoxelSnapshot.NO_BLOCK_TINT_LAYER_COLOR,
                SectionVoxelSnapshot.NO_BLOCK_TINT_LAYER_COLOR,
                lightEmission,
                materialFlags,
                faceVisible,
                vertexLighting0,
                vertexLighting1,
                vertexLighting2,
                vertexLighting3,
                FLUID_HEIGHT_SCALE,
                FLUID_HEIGHT_SCALE,
                FLUID_HEIGHT_SCALE,
                FLUID_HEIGHT_SCALE,
                0,
                0,
                false
        );
    }

    /**
     * Creates a textured compatibility face with captured tint layers and vertex lighting.
     *
     * @param x                    section-local voxel x coordinate
     * @param y                    section-local voxel y coordinate
     * @param z                    section-local voxel z coordinate
     * @param direction            outward face direction
     * @param voxelTypeId          stable voxel material identifier
     * @param mediumAmount         unsigned medium amount
     * @param mapColor             packed map color
     * @param blockTintLayer0Color first packed tint-layer color
     * @param blockTintLayer1Color second packed tint-layer color
     * @param blockTintLayer2Color third packed tint-layer color
     * @param blockTintLayer3Color fourth packed tint-layer color
     * @param lightEmission        unsigned light emission
     * @param materialFlags        unsigned material flags
     * @param faceVisible          whether the face is visible
     * @param vertexLighting0      packed first-corner lighting
     * @param vertexLighting1      packed second-corner lighting
     * @param vertexLighting2      packed third-corner lighting
     * @param vertexLighting3      packed fourth-corner lighting
     */
    public SectionFace(
            int x,
            int y,
            int z,
            FaceDirection direction,
            int voxelTypeId,
            int mediumAmount,
            int mapColor,
            int blockTintLayer0Color,
            int blockTintLayer1Color,
            int blockTintLayer2Color,
            int blockTintLayer3Color,
            int lightEmission,
            int materialFlags,
            boolean faceVisible,
            int vertexLighting0,
            int vertexLighting1,
            int vertexLighting2,
            int vertexLighting3
    ) {
        this(
                x,
                y,
                z,
                direction,
                voxelTypeId,
                mediumAmount,
                mapColor,
                blockTintLayer0Color,
                blockTintLayer1Color,
                blockTintLayer2Color,
                blockTintLayer3Color,
                lightEmission,
                materialFlags,
                faceVisible,
                vertexLighting0,
                vertexLighting1,
                vertexLighting2,
                vertexLighting3,
                FLUID_HEIGHT_SCALE,
                FLUID_HEIGHT_SCALE,
                FLUID_HEIGHT_SCALE,
                FLUID_HEIGHT_SCALE,
                0,
                0,
                false
        );
    }

    /**
     * Creates a textured face with explicit per-corner lighting.
     *
     * @param x               section-local voxel x coordinate
     * @param y               section-local voxel y coordinate
     * @param z               section-local voxel z coordinate
     * @param direction       outward face direction
     * @param voxelTypeId     stable voxel material identifier
     * @param mediumAmount    unsigned medium amount
     * @param mapColor        packed map color
     * @param lightEmission   unsigned light emission
     * @param materialFlags   unsigned material flags
     * @param vertexLighting0 packed first-corner lighting
     * @param vertexLighting1 packed second-corner lighting
     * @param vertexLighting2 packed third-corner lighting
     * @param vertexLighting3 packed fourth-corner lighting
     */
    public SectionFace(
            int x,
            int y,
            int z,
            FaceDirection direction,
            int voxelTypeId,
            int mediumAmount,
            int mapColor,
            int lightEmission,
            int materialFlags,
            int vertexLighting0,
            int vertexLighting1,
            int vertexLighting2,
            int vertexLighting3
    ) {
        this(
                x,
                y,
                z,
                direction,
                voxelTypeId,
                mediumAmount,
                mapColor,
                SectionVoxelSnapshot.NO_BLOCK_TINT_LAYER_COLOR,
                SectionVoxelSnapshot.NO_BLOCK_TINT_LAYER_COLOR,
                SectionVoxelSnapshot.NO_BLOCK_TINT_LAYER_COLOR,
                SectionVoxelSnapshot.NO_BLOCK_TINT_LAYER_COLOR,
                lightEmission,
                materialFlags,
                true,
                vertexLighting0,
                vertexLighting1,
                vertexLighting2,
                vertexLighting3,
                FLUID_HEIGHT_SCALE,
                FLUID_HEIGHT_SCALE,
                FLUID_HEIGHT_SCALE,
                FLUID_HEIGHT_SCALE,
                0,
                0,
                false
        );
    }

    /**
     * Creates a face with explicit tint layers and visible render classification.
     *
     * @param x                    section-local voxel x coordinate
     * @param y                    section-local voxel y coordinate
     * @param z                    section-local voxel z coordinate
     * @param direction            outward face direction
     * @param voxelTypeId          stable voxel material identifier
     * @param mediumAmount         unsigned medium amount
     * @param mapColor             packed map color
     * @param blockTintLayer0Color first packed tint-layer color
     * @param blockTintLayer1Color second packed tint-layer color
     * @param blockTintLayer2Color third packed tint-layer color
     * @param blockTintLayer3Color fourth packed tint-layer color
     * @param lightEmission        unsigned light emission
     * @param materialFlags        unsigned material flags
     * @param vertexLighting0      packed first-corner lighting
     * @param vertexLighting1      packed second-corner lighting
     * @param vertexLighting2      packed third-corner lighting
     * @param vertexLighting3      packed fourth-corner lighting
     */
    public SectionFace(
            int x,
            int y,
            int z,
            FaceDirection direction,
            int voxelTypeId,
            int mediumAmount,
            int mapColor,
            int blockTintLayer0Color,
            int blockTintLayer1Color,
            int blockTintLayer2Color,
            int blockTintLayer3Color,
            int lightEmission,
            int materialFlags,
            int vertexLighting0,
            int vertexLighting1,
            int vertexLighting2,
            int vertexLighting3
    ) {
        this(
                x,
                y,
                z,
                direction,
                voxelTypeId,
                mediumAmount,
                mapColor,
                blockTintLayer0Color,
                blockTintLayer1Color,
                blockTintLayer2Color,
                blockTintLayer3Color,
                lightEmission,
                materialFlags,
                true,
                vertexLighting0,
                vertexLighting1,
                vertexLighting2,
                vertexLighting3
        );
    }

    /**
     * Creates a visible face with flat lighting derived from captured color.
     *
     * @param x             section-local voxel x coordinate
     * @param y             section-local voxel y coordinate
     * @param z             section-local voxel z coordinate
     * @param direction     outward face direction
     * @param voxelTypeId   stable voxel material identifier
     * @param mediumAmount  unsigned medium amount
     * @param mapColor      packed map color
     * @param lightEmission unsigned light emission
     * @param materialFlags unsigned material flags
     */
    public SectionFace(
            int x,
            int y,
            int z,
            FaceDirection direction,
            int voxelTypeId,
            int mediumAmount,
            int mapColor,
            int lightEmission,
            int materialFlags
    ) {
        this(
                x,
                y,
                z,
                direction,
                voxelTypeId,
                mediumAmount,
                mapColor,
                SectionVoxelSnapshot.NO_BLOCK_TINT_LAYER_COLOR,
                SectionVoxelSnapshot.NO_BLOCK_TINT_LAYER_COLOR,
                SectionVoxelSnapshot.NO_BLOCK_TINT_LAYER_COLOR,
                SectionVoxelSnapshot.NO_BLOCK_TINT_LAYER_COLOR,
                lightEmission,
                materialFlags,
                true,
                PackedVoxelLighting.packFlatVertex(mapColor, direction),
                PackedVoxelLighting.packFlatVertex(mapColor, direction),
                PackedVoxelLighting.packFlatVertex(mapColor, direction),
                PackedVoxelLighting.packFlatVertex(mapColor, direction),
                FLUID_HEIGHT_SCALE,
                FLUID_HEIGHT_SCALE,
                FLUID_HEIGHT_SCALE,
                FLUID_HEIGHT_SCALE,
                0,
                0,
                false
        );
    }

    /**
     * Creates a minimally classified face with unavailable color semantics.
     *
     * @param x            section-local voxel x coordinate
     * @param y            section-local voxel y coordinate
     * @param z            section-local voxel z coordinate
     * @param direction    outward face direction
     * @param voxelTypeId  stable voxel material identifier
     * @param mediumAmount unsigned medium amount
     */
    public SectionFace(
            int x,
            int y,
            int z,
            FaceDirection direction,
            int voxelTypeId,
            int mediumAmount
    ) {
        this(
                x,
                y,
                z,
                direction,
                voxelTypeId,
                mediumAmount,
                SectionVoxelSnapshot.NO_MAP_COLOR,
                0,
                voxelTypeId != 0 || mediumAmount > 0
                        ? SectionVoxelSnapshot.FLAG_RENDER_SHAPE_VISIBLE
                        : SectionVoxelSnapshot.FLAG_AIR
        );
    }

    private static int validateFluidHeight(int height) {
        if (height < 0 || height > FLUID_HEIGHT_SCALE) {
            throw new IllegalArgumentException("fluid height must be in [0, "
                    + FLUID_HEIGHT_SCALE + "]: " + height);
        }
        return height;
    }

    private static int validateFluidFlow(int flow, String name) {
        if (flow < Byte.MIN_VALUE || flow > Byte.MAX_VALUE) {
            throw new IllegalArgumentException(name + " must be signed byte compatible: " + flow);
        }
        return flow;
    }

    private static int validateBlockTintLayerColor(int color, String name) {
        if (color != SectionVoxelSnapshot.NO_BLOCK_TINT_LAYER_COLOR) {
            return color & SectionVoxelSnapshot.MAP_COLOR_RGB_MASK;
        }
        return color;
    }

    /**
     * Returns an immutable copy with replacement fluid corner heights.
     *
     * @param height0 first-corner fluid height
     * @param height1 second-corner fluid height
     * @param height2 third-corner fluid height
     * @param height3 fourth-corner fluid height
     * @return face copy with replacement corner heights
     */
    public SectionFace withFluidHeights(int height0, int height1, int height2, int height3) {
        return new SectionFace(
                x,
                y,
                z,
                direction,
                voxelTypeId,
                mediumAmount,
                mapColor,
                blockTintLayer0Color,
                blockTintLayer1Color,
                blockTintLayer2Color,
                blockTintLayer3Color,
                lightEmission,
                materialFlags,
                faceVisible,
                vertexLighting0,
                vertexLighting1,
                vertexLighting2,
                vertexLighting3,
                height0,
                height1,
                height2,
                height3,
                fluidFlowX,
                fluidFlowZ,
                fluidOverlay,
                mediumTypeId
        );
    }

    /**
     * Returns an immutable copy with replacement signed fluid flow.
     *
     * @param flowX signed fluid x-flow
     * @param flowZ signed fluid z-flow
     * @return face copy with replacement fluid flow
     */
    public SectionFace withFluidFlow(int flowX, int flowZ) {
        return new SectionFace(
                x,
                y,
                z,
                direction,
                voxelTypeId,
                mediumAmount,
                mapColor,
                blockTintLayer0Color,
                blockTintLayer1Color,
                blockTintLayer2Color,
                blockTintLayer3Color,
                lightEmission,
                materialFlags,
                faceVisible,
                vertexLighting0,
                vertexLighting1,
                vertexLighting2,
                vertexLighting3,
                fluidHeight0,
                fluidHeight1,
                fluidHeight2,
                fluidHeight3,
                flowX,
                flowZ,
                fluidOverlay,
                mediumTypeId
        );
    }

    /**
     * Returns an immutable copy with replacement overlay state.
     *
     * @param overlay whether fluid overlay rendering is required
     * @return face copy with replacement overlay state
     */
    public SectionFace withFluidOverlay(boolean overlay) {
        return new SectionFace(
                x,
                y,
                z,
                direction,
                voxelTypeId,
                mediumAmount,
                mapColor,
                blockTintLayer0Color,
                blockTintLayer1Color,
                blockTintLayer2Color,
                blockTintLayer3Color,
                lightEmission,
                materialFlags,
                faceVisible,
                vertexLighting0,
                vertexLighting1,
                vertexLighting2,
                vertexLighting3,
                fluidHeight0,
                fluidHeight1,
                fluidHeight2,
                fluidHeight3,
                fluidFlowX,
                fluidFlowZ,
                overlay,
                mediumTypeId
        );
    }

    /**
     * Returns an immutable copy with a replacement fluid type.
     *
     * @param typeId stable medium type identifier
     * @return face copy with replacement medium type
     */
    public SectionFace withFluidTypeId(int typeId) {
        return new SectionFace(
                x, y, z, direction, voxelTypeId, mediumAmount, mapColor,
                blockTintLayer0Color, blockTintLayer1Color, blockTintLayer2Color, blockTintLayer3Color,
                lightEmission, materialFlags, faceVisible,
                vertexLighting0, vertexLighting1, vertexLighting2, vertexLighting3,
                fluidHeight0, fluidHeight1, fluidHeight2, fluidHeight3,
                fluidFlowX, fluidFlowZ, fluidOverlay, typeId
        );
    }
}
