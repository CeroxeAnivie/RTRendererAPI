package top.ceroxe.mcvulkanrt.renderer.scene;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 面向 renderer backend 的 section 材料编码。
 *
 * <p>第一版使用 palette + RLE：palette 去重 voxel state / fluid 材料，RLE 保留
 * linear voxel order 中的连续材料 run。它还不是最终 meshlet 格式，但已经把原始
 * 4096 voxel 数组压成后端更容易批处理和 profile 的稳定结构。</p>
 */
public record SectionEncodedSnapshot(
        SectionKey key,
        int[] blockStatePalette,
        int[] fluidVoxelStatePalette,
        int[] mediumTypePalette,
        byte[] mediumAmountPalette,
        byte[] fluidFlowXPalette,
        byte[] fluidFlowZPalette,
        int[] mapColorPalette,
        int[] blockTintLayer0Palette,
        int[] blockTintLayer1Palette,
        int[] blockTintLayer2Palette,
        int[] blockTintLayer3Palette,
        int[] fluidMapColorPalette,
        byte[] lightEmissionPalette,
        byte[] materialFlagPalette,
        byte[] shadeBrightnessPalette,
        int[] runPaletteIndices,
        int[] runLengths,
        boolean hasOnlyAir,
        boolean hasFluid
) {
    public SectionEncodedSnapshot {
        key = Objects.requireNonNull(key, "key");
        blockStatePalette = copyIntArray(blockStatePalette, "blockStatePalette");
        fluidVoxelStatePalette = copyIntArray(fluidVoxelStatePalette, "fluidVoxelStatePalette", blockStatePalette.length);
        mediumTypePalette = copyIntArray(mediumTypePalette, "mediumTypePalette", blockStatePalette.length);
        mediumAmountPalette = copyByteArray(mediumAmountPalette, "mediumAmountPalette", blockStatePalette.length);
        fluidFlowXPalette = copyByteArray(fluidFlowXPalette, "fluidFlowXPalette", blockStatePalette.length);
        fluidFlowZPalette = copyByteArray(fluidFlowZPalette, "fluidFlowZPalette", blockStatePalette.length);
        mapColorPalette = copyIntArray(mapColorPalette, "mapColorPalette", blockStatePalette.length);
        blockTintLayer0Palette = copyBlockTintLayerArray(blockTintLayer0Palette, "blockTintLayer0Palette", blockStatePalette.length);
        blockTintLayer1Palette = copyBlockTintLayerArray(blockTintLayer1Palette, "blockTintLayer1Palette", blockStatePalette.length);
        blockTintLayer2Palette = copyBlockTintLayerArray(blockTintLayer2Palette, "blockTintLayer2Palette", blockStatePalette.length);
        blockTintLayer3Palette = copyBlockTintLayerArray(blockTintLayer3Palette, "blockTintLayer3Palette", blockStatePalette.length);
        fluidMapColorPalette = copyIntArray(fluidMapColorPalette, "fluidMapColorPalette", blockStatePalette.length);
        lightEmissionPalette = copyByteArray(lightEmissionPalette, "lightEmissionPalette", blockStatePalette.length);
        materialFlagPalette = copyByteArray(materialFlagPalette, "materialFlagPalette", blockStatePalette.length);
        shadeBrightnessPalette = copyByteArray(shadeBrightnessPalette, "shadeBrightnessPalette", blockStatePalette.length);
        runPaletteIndices = copyIntArray(runPaletteIndices, "runPaletteIndices");
        runLengths = copyIntArray(runLengths, "runLengths");
        if (runPaletteIndices.length != runLengths.length) {
            throw new IllegalArgumentException("runPaletteIndices and runLengths must have the same length");
        }
        validateRuns(runPaletteIndices, runLengths, blockStatePalette.length);
    }

    public SectionEncodedSnapshot(
            SectionKey key,
            int[] blockStatePalette,
            byte[] mediumAmountPalette,
            int[] runPaletteIndices,
            int[] runLengths,
            boolean hasOnlyAir,
            boolean hasFluid
    ) {
        this(
                key,
                blockStatePalette,
                defaultFluidVoxelStatePalette(blockStatePalette, mediumAmountPalette),
                defaultFluidTypePalette(defaultFluidVoxelStatePalette(blockStatePalette, mediumAmountPalette), mediumAmountPalette),
                mediumAmountPalette,
                new byte[blockStatePalette.length],
                new byte[blockStatePalette.length],
                new int[blockStatePalette.length],
                defaultBlockTintLayerPalette(blockStatePalette.length),
                defaultBlockTintLayerPalette(blockStatePalette.length),
                defaultBlockTintLayerPalette(blockStatePalette.length),
                defaultBlockTintLayerPalette(blockStatePalette.length),
                new int[blockStatePalette.length],
                new byte[blockStatePalette.length],
                defaultMaterialFlags(blockStatePalette, mediumAmountPalette),
                defaultShadeBrightnessPalette(blockStatePalette.length),
                runPaletteIndices,
                runLengths,
                hasOnlyAir,
                hasFluid
        );
    }

    public static SectionEncodedSnapshot encode(SectionVoxelSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");

        Map<MaterialKey, Integer> palette = new LinkedHashMap<>();
        int[] tempRunPaletteIndices = new int[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        int[] tempRunLengths = new int[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        int runCount = 0;
        int activePaletteIndex = -1;

        for (int index = 0; index < SectionVoxelSnapshot.BLOCKS_PER_SECTION; index++) {
            MaterialKey material = new MaterialKey(
                    snapshot.voxelTypeIdAtLinearIndex(index),
                    snapshot.mediumStateIdAtLinearIndex(index),
                    snapshot.mediumTypeIdAtLinearIndex(index),
                    snapshot.mediumAmountAtLinearIndex(index),
                    snapshot.fluidFlowXAtLinearIndex(index),
                    snapshot.fluidFlowZAtLinearIndex(index),
                    snapshot.mapColorAtLinearIndex(index),
                    snapshot.blockTintLayerColorAtLinearIndex(index, 0),
                    snapshot.blockTintLayerColorAtLinearIndex(index, 1),
                    snapshot.blockTintLayerColorAtLinearIndex(index, 2),
                    snapshot.blockTintLayerColorAtLinearIndex(index, 3),
                    snapshot.fluidMapColorAtLinearIndex(index),
                    snapshot.lightEmissionAtLinearIndex(index),
                    snapshot.materialFlagsAtLinearIndex(index),
                    snapshot.shadeBrightnessAtLinearIndex(index)
            );
            Integer paletteIndex = palette.get(material);
            if (paletteIndex == null) {
                paletteIndex = palette.size();
                palette.put(material, paletteIndex);
            }

            if (paletteIndex == activePaletteIndex) {
                tempRunLengths[runCount - 1]++;
            } else {
                tempRunPaletteIndices[runCount] = paletteIndex;
                tempRunLengths[runCount] = 1;
                activePaletteIndex = paletteIndex;
                runCount++;
            }
        }

        int[] blockStatePalette = new int[palette.size()];
        int[] fluidVoxelStatePalette = new int[palette.size()];
        int[] mediumTypePalette = new int[palette.size()];
        byte[] mediumAmountPalette = new byte[palette.size()];
        byte[] fluidFlowXPalette = new byte[palette.size()];
        byte[] fluidFlowZPalette = new byte[palette.size()];
        int[] mapColorPalette = new int[palette.size()];
        int[] blockTintLayer0Palette = new int[palette.size()];
        int[] blockTintLayer1Palette = new int[palette.size()];
        int[] blockTintLayer2Palette = new int[palette.size()];
        int[] blockTintLayer3Palette = new int[palette.size()];
        int[] fluidMapColorPalette = new int[palette.size()];
        byte[] lightEmissionPalette = new byte[palette.size()];
        byte[] materialFlagPalette = new byte[palette.size()];
        byte[] shadeBrightnessPalette = new byte[palette.size()];
        for (Map.Entry<MaterialKey, Integer> entry : palette.entrySet()) {
            int paletteIndex = entry.getValue();
            blockStatePalette[paletteIndex] = entry.getKey().voxelTypeId();
            fluidVoxelStatePalette[paletteIndex] = entry.getKey().mediumStateId();
            mediumTypePalette[paletteIndex] = entry.getKey().mediumTypeId();
            mediumAmountPalette[paletteIndex] = (byte) entry.getKey().mediumAmount();
            fluidFlowXPalette[paletteIndex] = (byte) entry.getKey().fluidFlowX();
            fluidFlowZPalette[paletteIndex] = (byte) entry.getKey().fluidFlowZ();
            mapColorPalette[paletteIndex] = entry.getKey().mapColor();
            blockTintLayer0Palette[paletteIndex] = entry.getKey().blockTintLayer0Color();
            blockTintLayer1Palette[paletteIndex] = entry.getKey().blockTintLayer1Color();
            blockTintLayer2Palette[paletteIndex] = entry.getKey().blockTintLayer2Color();
            blockTintLayer3Palette[paletteIndex] = entry.getKey().blockTintLayer3Color();
            fluidMapColorPalette[paletteIndex] = entry.getKey().fluidMapColor();
            lightEmissionPalette[paletteIndex] = (byte) entry.getKey().lightEmission();
            materialFlagPalette[paletteIndex] = (byte) entry.getKey().materialFlags();
            shadeBrightnessPalette[paletteIndex] = (byte) entry.getKey().shadeBrightness();
        }

        return new SectionEncodedSnapshot(
                snapshot.key(),
                blockStatePalette,
                fluidVoxelStatePalette,
                mediumTypePalette,
                mediumAmountPalette,
                fluidFlowXPalette,
                fluidFlowZPalette,
                mapColorPalette,
                blockTintLayer0Palette,
                blockTintLayer1Palette,
                blockTintLayer2Palette,
                blockTintLayer3Palette,
                fluidMapColorPalette,
                lightEmissionPalette,
                materialFlagPalette,
                shadeBrightnessPalette,
                Arrays.copyOf(tempRunPaletteIndices, runCount),
                Arrays.copyOf(tempRunLengths, runCount),
                snapshot.hasOnlyAir(),
                snapshot.hasFluid()
        );
    }

    public int paletteSize() {
        return blockStatePalette.length;
    }

    public int runCount() {
        return runLengths.length;
    }

    public int voxelTypeIdAt(int x, int y, int z) {
        int paletteIndex = paletteIndexAtLinearIndex(SectionVoxelSnapshot.localBlockIndex(x, y, z));
        return blockStatePalette[paletteIndex];
    }

    public int mediumStateIdAt(int x, int y, int z) {
        int paletteIndex = paletteIndexAtLinearIndex(SectionVoxelSnapshot.localBlockIndex(x, y, z));
        return fluidVoxelStatePalette[paletteIndex];
    }

    public int mediumTypeIdAt(int x, int y, int z) {
        int paletteIndex = paletteIndexAtLinearIndex(SectionVoxelSnapshot.localBlockIndex(x, y, z));
        return mediumTypePalette[paletteIndex];
    }

    public int mediumAmountAt(int x, int y, int z) {
        int paletteIndex = paletteIndexAtLinearIndex(SectionVoxelSnapshot.localBlockIndex(x, y, z));
        return Byte.toUnsignedInt(mediumAmountPalette[paletteIndex]);
    }

    public int fluidFlowXAt(int x, int y, int z) {
        int paletteIndex = paletteIndexAtLinearIndex(SectionVoxelSnapshot.localBlockIndex(x, y, z));
        return fluidFlowXPalette[paletteIndex];
    }

    public int fluidFlowZAt(int x, int y, int z) {
        int paletteIndex = paletteIndexAtLinearIndex(SectionVoxelSnapshot.localBlockIndex(x, y, z));
        return fluidFlowZPalette[paletteIndex];
    }

    public int mapColorAt(int x, int y, int z) {
        int paletteIndex = paletteIndexAtLinearIndex(SectionVoxelSnapshot.localBlockIndex(x, y, z));
        return mapColorPalette[paletteIndex];
    }

    public int fluidMapColorAt(int x, int y, int z) {
        int paletteIndex = paletteIndexAtLinearIndex(SectionVoxelSnapshot.localBlockIndex(x, y, z));
        return fluidMapColorPalette[paletteIndex];
    }

    public int blockTintLayerColorAt(int x, int y, int z, int tintLayer) {
        int paletteIndex = paletteIndexAtLinearIndex(SectionVoxelSnapshot.localBlockIndex(x, y, z));
        return switch (tintLayer) {
            case 0 -> blockTintLayer0Palette[paletteIndex];
            case 1 -> blockTintLayer1Palette[paletteIndex];
            case 2 -> blockTintLayer2Palette[paletteIndex];
            case 3 -> blockTintLayer3Palette[paletteIndex];
            default -> SectionVoxelSnapshot.NO_BLOCK_TINT_LAYER_COLOR;
        };
    }

    public int lightEmissionAt(int x, int y, int z) {
        int paletteIndex = paletteIndexAtLinearIndex(SectionVoxelSnapshot.localBlockIndex(x, y, z));
        return Byte.toUnsignedInt(lightEmissionPalette[paletteIndex]);
    }

    public int materialFlagsAt(int x, int y, int z) {
        int paletteIndex = paletteIndexAtLinearIndex(SectionVoxelSnapshot.localBlockIndex(x, y, z));
        return Byte.toUnsignedInt(materialFlagPalette[paletteIndex]);
    }

    public int shadeBrightnessAt(int x, int y, int z) {
        int paletteIndex = paletteIndexAtLinearIndex(SectionVoxelSnapshot.localBlockIndex(x, y, z));
        return Byte.toUnsignedInt(shadeBrightnessPalette[paletteIndex]);
    }

    public int estimatedBytes() {
        return Integer.BYTES * blockStatePalette.length
                + Integer.BYTES * fluidVoxelStatePalette.length
                + Integer.BYTES * mediumTypePalette.length
                + mediumAmountPalette.length
                + fluidFlowXPalette.length
                + fluidFlowZPalette.length
                + Integer.BYTES * mapColorPalette.length
                + Integer.BYTES * blockTintLayer0Palette.length
                + Integer.BYTES * blockTintLayer1Palette.length
                + Integer.BYTES * blockTintLayer2Palette.length
                + Integer.BYTES * blockTintLayer3Palette.length
                + Integer.BYTES * fluidMapColorPalette.length
                + lightEmissionPalette.length
                + materialFlagPalette.length
                + shadeBrightnessPalette.length
                + Integer.BYTES * runPaletteIndices.length
                + Integer.BYTES * runLengths.length;
    }

    @Override
    public int[] blockStatePalette() {
        return Arrays.copyOf(blockStatePalette, blockStatePalette.length);
    }

    @Override
    public int[] fluidVoxelStatePalette() {
        return Arrays.copyOf(fluidVoxelStatePalette, fluidVoxelStatePalette.length);
    }

    @Override
    public int[] mediumTypePalette() {
        return Arrays.copyOf(mediumTypePalette, mediumTypePalette.length);
    }

    @Override
    public byte[] mediumAmountPalette() {
        return Arrays.copyOf(mediumAmountPalette, mediumAmountPalette.length);
    }

    @Override
    public byte[] fluidFlowXPalette() {
        return Arrays.copyOf(fluidFlowXPalette, fluidFlowXPalette.length);
    }

    @Override
    public byte[] fluidFlowZPalette() {
        return Arrays.copyOf(fluidFlowZPalette, fluidFlowZPalette.length);
    }

    @Override
    public int[] mapColorPalette() {
        return Arrays.copyOf(mapColorPalette, mapColorPalette.length);
    }

    @Override
    public int[] blockTintLayer0Palette() {
        return Arrays.copyOf(blockTintLayer0Palette, blockTintLayer0Palette.length);
    }

    @Override
    public int[] blockTintLayer1Palette() {
        return Arrays.copyOf(blockTintLayer1Palette, blockTintLayer1Palette.length);
    }

    @Override
    public int[] blockTintLayer2Palette() {
        return Arrays.copyOf(blockTintLayer2Palette, blockTintLayer2Palette.length);
    }

    @Override
    public int[] blockTintLayer3Palette() {
        return Arrays.copyOf(blockTintLayer3Palette, blockTintLayer3Palette.length);
    }

    @Override
    public int[] fluidMapColorPalette() {
        return Arrays.copyOf(fluidMapColorPalette, fluidMapColorPalette.length);
    }

    @Override
    public byte[] lightEmissionPalette() {
        return Arrays.copyOf(lightEmissionPalette, lightEmissionPalette.length);
    }

    @Override
    public byte[] materialFlagPalette() {
        return Arrays.copyOf(materialFlagPalette, materialFlagPalette.length);
    }

    @Override
    public byte[] shadeBrightnessPalette() {
        return Arrays.copyOf(shadeBrightnessPalette, shadeBrightnessPalette.length);
    }

    @Override
    public int[] runPaletteIndices() {
        return Arrays.copyOf(runPaletteIndices, runPaletteIndices.length);
    }

    @Override
    public int[] runLengths() {
        return Arrays.copyOf(runLengths, runLengths.length);
    }

    private int paletteIndexAtLinearIndex(int targetIndex) {
        int cursor = 0;
        for (int run = 0; run < runLengths.length; run++) {
            int nextCursor = cursor + runLengths[run];
            if (targetIndex < nextCursor) {
                return runPaletteIndices[run];
            }
            cursor = nextCursor;
        }
        throw new IndexOutOfBoundsException("linear index outside encoded section: " + targetIndex);
    }

    private static void validateRuns(int[] runPaletteIndices, int[] runLengths, int paletteSize) {
        int totalLength = 0;
        for (int run = 0; run < runLengths.length; run++) {
            if (runLengths[run] <= 0) {
                throw new IllegalArgumentException("run length must be positive at run " + run);
            }
            if (runPaletteIndices[run] < 0 || runPaletteIndices[run] >= paletteSize) {
                throw new IllegalArgumentException("run palette index outside palette at run " + run);
            }
            totalLength += runLengths[run];
        }
        if (totalLength != SectionVoxelSnapshot.BLOCKS_PER_SECTION) {
            throw new IllegalArgumentException("encoded run length total must be "
                    + SectionVoxelSnapshot.BLOCKS_PER_SECTION + ", got " + totalLength);
        }
    }

    private static int[] copyIntArray(int[] source, String name) {
        Objects.requireNonNull(source, name);
        return Arrays.copyOf(source, source.length);
    }

    private static int[] copyIntArray(int[] source, String name, int expectedLength) {
        Objects.requireNonNull(source, name);
        if (source.length != expectedLength) {
            throw new IllegalArgumentException(name + " length must match blockStatePalette length");
        }
        return Arrays.copyOf(source, source.length);
    }

    private static int[] copyBlockTintLayerArray(int[] source, String name, int expectedLength) {
        int[] copy = copyIntArray(source, name, expectedLength);
        for (int index = 0; index < copy.length; index++) {
            int color = copy[index];
            if (color != SectionVoxelSnapshot.NO_BLOCK_TINT_LAYER_COLOR) {
                copy[index] = color & SectionVoxelSnapshot.MAP_COLOR_RGB_MASK;
            }
        }
        return copy;
    }

    private static byte[] copyByteArray(byte[] source, String name, int expectedLength) {
        Objects.requireNonNull(source, name);
        if (source.length != expectedLength) {
            throw new IllegalArgumentException(name + " length must match blockStatePalette length");
        }
        return Arrays.copyOf(source, source.length);
    }

    private static byte[] defaultMaterialFlags(int[] blockStatePalette, byte[] mediumAmountPalette) {
        Objects.requireNonNull(blockStatePalette, "blockStatePalette");
        Objects.requireNonNull(mediumAmountPalette, "mediumAmountPalette");
        if (blockStatePalette.length != mediumAmountPalette.length) {
            throw new IllegalArgumentException("legacy palette arrays must have matching lengths");
        }
        byte[] flags = new byte[blockStatePalette.length];
        for (int index = 0; index < flags.length; index++) {
            if (blockStatePalette[index] != 0 || Byte.toUnsignedInt(mediumAmountPalette[index]) > 0) {
                flags[index] = SectionVoxelSnapshot.FLAG_RENDER_SHAPE_VISIBLE;
            } else {
                flags[index] = SectionVoxelSnapshot.FLAG_AIR;
            }
        }
        return flags;
    }

    private static byte[] defaultShadeBrightnessPalette(int paletteSize) {
        byte[] shadeBrightnessPalette = new byte[paletteSize];
        Arrays.fill(shadeBrightnessPalette, (byte) 255);
        return shadeBrightnessPalette;
    }

    private static int[] defaultBlockTintLayerPalette(int paletteSize) {
        int[] colors = new int[paletteSize];
        Arrays.fill(colors, SectionVoxelSnapshot.NO_BLOCK_TINT_LAYER_COLOR);
        return colors;
    }

    private static int[] defaultFluidVoxelStatePalette(int[] blockStatePalette, byte[] mediumAmountPalette) {
        Objects.requireNonNull(blockStatePalette, "blockStatePalette");
        Objects.requireNonNull(mediumAmountPalette, "mediumAmountPalette");
        if (blockStatePalette.length != mediumAmountPalette.length) {
            throw new IllegalArgumentException("legacy palette arrays must have matching lengths");
        }
        int[] fluidVoxelStatePalette = new int[blockStatePalette.length];
        for (int index = 0; index < fluidVoxelStatePalette.length; index++) {
            if (Byte.toUnsignedInt(mediumAmountPalette[index]) > 0) {
                fluidVoxelStatePalette[index] = blockStatePalette[index];
            }
        }
        return fluidVoxelStatePalette;
    }

    private static int[] defaultFluidTypePalette(int[] fluidVoxelStatePalette, byte[] mediumAmountPalette) {
        Objects.requireNonNull(fluidVoxelStatePalette, "fluidVoxelStatePalette");
        Objects.requireNonNull(mediumAmountPalette, "mediumAmountPalette");
        if (fluidVoxelStatePalette.length != mediumAmountPalette.length) {
            throw new IllegalArgumentException("legacy fluid type palette arrays must have matching lengths");
        }
        int[] mediumTypePalette = new int[fluidVoxelStatePalette.length];
        for (int index = 0; index < mediumTypePalette.length; index++) {
            if (Byte.toUnsignedInt(mediumAmountPalette[index]) > 0) {
                mediumTypePalette[index] = fluidVoxelStatePalette[index];
            }
        }
        return mediumTypePalette;
    }

    private record MaterialKey(
            int voxelTypeId,
            int mediumStateId,
            int mediumTypeId,
            int mediumAmount,
            int fluidFlowX,
            int fluidFlowZ,
            int mapColor,
            int blockTintLayer0Color,
            int blockTintLayer1Color,
            int blockTintLayer2Color,
            int blockTintLayer3Color,
            int fluidMapColor,
            int lightEmission,
            int materialFlags,
            int shadeBrightness
    ) {
        private MaterialKey {
            if (mediumTypeId < 0) {
                throw new IllegalArgumentException("mediumTypeId must not be negative: " + mediumTypeId);
            }
            if (mediumAmount < 0 || mediumAmount > 255) {
                throw new IllegalArgumentException("mediumAmount must be unsigned byte compatible: " + mediumAmount);
            }
            if (fluidFlowX < Byte.MIN_VALUE || fluidFlowX > Byte.MAX_VALUE) {
                throw new IllegalArgumentException("fluidFlowX must be signed byte compatible: " + fluidFlowX);
            }
            if (fluidFlowZ < Byte.MIN_VALUE || fluidFlowZ > Byte.MAX_VALUE) {
                throw new IllegalArgumentException("fluidFlowZ must be signed byte compatible: " + fluidFlowZ);
            }
            validateBlockTintLayerColor(blockTintLayer0Color, "blockTintLayer0Color");
            validateBlockTintLayerColor(blockTintLayer1Color, "blockTintLayer1Color");
            validateBlockTintLayerColor(blockTintLayer2Color, "blockTintLayer2Color");
            validateBlockTintLayerColor(blockTintLayer3Color, "blockTintLayer3Color");
            if (lightEmission < 0 || lightEmission > 255) {
                throw new IllegalArgumentException("lightEmission must be unsigned byte compatible: " + lightEmission);
            }
            if (materialFlags < 0 || materialFlags > 255) {
                throw new IllegalArgumentException("materialFlags must be unsigned byte compatible: " + materialFlags);
            }
            if (shadeBrightness < 0 || shadeBrightness > 255) {
                throw new IllegalArgumentException("shadeBrightness must be unsigned byte compatible: " + shadeBrightness);
            }
        }

        private static void validateBlockTintLayerColor(int color, String name) {
            if (color != SectionVoxelSnapshot.NO_BLOCK_TINT_LAYER_COLOR
                    && (color & ~SectionVoxelSnapshot.MAP_COLOR_RGB_MASK) != 0) {
                throw new IllegalArgumentException(name + " must be RGB24 or NO_BLOCK_TINT_LAYER_COLOR: " + color);
            }
        }
    }
}
