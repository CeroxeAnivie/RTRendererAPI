package top.ceroxe.rt.renderer.scene;

import top.ceroxe.rt.renderer.rt.material.RtTextureCatalog;

import java.util.Map;
import java.util.Objects;
import java.util.function.IntPredicate;

/**
 * 从 material facts 生成 section face proxy。
 *
 * <p>边界剔除必须和 section 内部剔除走同一条 material 判定路径。否则每个 16x16x16
 * section 都会把与相邻 section 接触的面误当成外表面，直接放大 face/material/BLAS/TLAS
 * 工作量，并把本该不可见的贴图面暴露到 RT 画面里。</p>
 */
public final class SectionMesher {
    private static final FaceDirection[] FACE_DIRECTIONS = FaceDirection.values();
    private static final int BLOCKS_PER_SECTION = SectionVoxelSnapshot.BLOCKS_PER_SECTION;
    private static final int SECTION_SIZE = SectionVoxelSnapshot.SECTION_SIZE;
    private static final float MAX_FLUID_HEIGHT = 0.8888889F;
    private static final ThreadLocal<BuildScratch> BUILD_SCRATCH =
            ThreadLocal.withInitial(BuildScratch::new);
    private final VoxelMaterialClassifier classifier;
    private final PositionedModelResolver modelResolver;
    private final PositionedGeometryResolver geometryResolver;
    private final BlockSemantics blockSemantics;

    /**
     * Creates a mesher with the production material classifier.
     */
    public SectionMesher() {
        this(VoxelMaterialClassifier.DEFAULT_CONSERVATIVE);
    }

    /**
     * Creates a mesher with an explicit material classifier.
     *
     * @param classifier material classifier
     */
    public SectionMesher(VoxelMaterialClassifier classifier) {
        this(
                classifier,
                RtTextureCatalog::resolveModelQuads,
                null,
                BlockSemantics.NONE
        );
    }

    SectionMesher(VoxelMaterialClassifier classifier, IntPredicate geometryResolver) {
        this(classifier, geometryResolver, BlockSemantics.NONE);
    }

    SectionMesher(
            VoxelMaterialClassifier classifier,
            IntPredicate geometryResolver,
            BlockSemantics blockSemantics
    ) {
        this(
                classifier,
                null,
                (voxelTypeId, ignoredX, ignoredY, ignoredZ) ->
                        Objects.requireNonNull(geometryResolver, "geometryResolver").test(voxelTypeId),
                blockSemantics
        );
    }

    private SectionMesher(
            VoxelMaterialClassifier classifier,
            PositionedModelResolver modelResolver,
            PositionedGeometryResolver geometryResolver,
            BlockSemantics blockSemantics
    ) {
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        if ((modelResolver == null) == (geometryResolver == null)) {
            throw new IllegalArgumentException("exactly one model resolver must be supplied");
        }
        this.modelResolver = modelResolver;
        this.geometryResolver = geometryResolver;
        this.blockSemantics = Objects.requireNonNull(blockSemantics, "blockSemantics");
    }

    private static boolean isPureFluidBlock(
            int voxelTypeId,
            int mediumStateId,
            int mediumAmount,
            int materialFlags
    ) {
        return mediumAmount > 0
                && mediumStateId != 0
                && voxelTypeId == mediumStateId
                && (materialFlags & SectionVoxelSnapshot.FLAG_LIQUID) != 0;
    }

    private static float fluidOcclusionHeight(FaceDirection direction, int[] faceHeights) {
        if (faceHeights == null || faceHeights.length != 4) {
            return MAX_FLUID_HEIGHT;
        }
        return switch (direction) {
            case NEGATIVE_Y -> MAX_FLUID_HEIGHT;
            case POSITIVE_Y -> packedFluidHeight(Math.min(
                    Math.min(faceHeights[0], faceHeights[1]),
                    Math.min(faceHeights[2], faceHeights[3])
            ));
            case NEGATIVE_X, POSITIVE_X, NEGATIVE_Z, POSITIVE_Z ->
                    packedFluidHeight(Math.max(faceHeights[2], faceHeights[3]));
        };
    }

    private static float packedFluidHeight(int packedHeight) {
        return Math.max(0.0F, Math.min(1.0F, packedHeight / (float) SectionFace.FLUID_HEIGHT_SCALE));
    }

    private static boolean isSameFluidVolume(
            int currentFluidVoxelStateId,
            int currentFluidTypeId,
            int currentFluidAmount,
            int neighborFluidVoxelStateId,
            int neighborFluidTypeId,
            int neighborFluidAmount
    ) {
        return currentFluidAmount > 0
                && neighborFluidAmount > 0
                && ((currentFluidTypeId > 0 && currentFluidTypeId == neighborFluidTypeId)
                || (currentFluidTypeId == 0
                && currentFluidVoxelStateId != 0
                && currentFluidVoxelStateId == neighborFluidVoxelStateId));
    }

    private static void fluidCornerHeights(
            int[] mediumAmounts,
            int[] fluidVoxelStates,
            int[] mediumTypes,
            int[] blockStates,
            int[] materialFlags,
            SectionNeighborhood neighborhood,
            int mediumStateId,
            int mediumTypeId,
            int x,
            int y,
            int z,
            int[] out
    ) {
        if (out == null || out.length < 4) {
            throw new IllegalArgumentException("fluid corner height output must contain four entries");
        }
        out[0] = fluidCornerHeight(mediumAmounts, fluidVoxelStates, mediumTypes, blockStates, materialFlags, neighborhood, mediumStateId, mediumTypeId, x, y, z, -1, -1);
        out[1] = fluidCornerHeight(mediumAmounts, fluidVoxelStates, mediumTypes, blockStates, materialFlags, neighborhood, mediumStateId, mediumTypeId, x, y, z, 1, -1);
        out[2] = fluidCornerHeight(mediumAmounts, fluidVoxelStates, mediumTypes, blockStates, materialFlags, neighborhood, mediumStateId, mediumTypeId, x, y, z, 1, 1);
        out[3] = fluidCornerHeight(mediumAmounts, fluidVoxelStates, mediumTypes, blockStates, materialFlags, neighborhood, mediumStateId, mediumTypeId, x, y, z, -1, 1);
    }

    private static void fluidFaceHeights(FaceDirection direction, int[] cornerHeights, int[] out) {
        if (cornerHeights == null || cornerHeights.length != 4) {
            throw new IllegalArgumentException("fluid corner height table must contain four entries");
        }
        if (out == null || out.length < 4) {
            throw new IllegalArgumentException("fluid face height output must contain four entries");
        }
        int x0z0 = cornerHeights[0];
        int x1z0 = cornerHeights[1];
        int x1z1 = cornerHeights[2];
        int x0z1 = cornerHeights[3];
        switch (direction) {
            case NEGATIVE_X -> set4(out, 0, 0, x0z1, x0z0);
            case POSITIVE_X -> set4(out, 0, 0, x1z0, x1z1);
            case NEGATIVE_Y -> set4(out, 0, 0, 0, 0);
            case POSITIVE_Y -> set4(out, x0z1, x1z1, x1z0, x0z0);
            case NEGATIVE_Z -> set4(out, 0, 0, x0z0, x1z0);
            case POSITIVE_Z -> set4(out, 0, 0, x1z1, x0z1);
        }
    }

    private static void set4(int[] out, int a, int b, int c, int d) {
        out[0] = a;
        out[1] = b;
        out[2] = c;
        out[3] = d;
    }

    private static int fluidCornerHeight(
            int[] mediumAmounts,
            int[] fluidVoxelStates,
            int[] mediumTypes,
            int[] blockStates,
            int[] materialFlags,
            SectionNeighborhood neighborhood,
            int mediumStateId,
            int mediumTypeId,
            int x,
            int y,
            int z,
            int xSign,
            int zSign
    ) {
        float centerHeight = fluidSampleHeight(mediumAmounts, fluidVoxelStates, mediumTypes, blockStates, materialFlags, neighborhood, mediumStateId, mediumTypeId, x, y, z);
        float xSideHeight = fluidSampleHeight(
                mediumAmounts,
                fluidVoxelStates,
                mediumTypes,
                blockStates,
                materialFlags,
                neighborhood,
                mediumStateId,
                mediumTypeId,
                x + xSign,
                y,
                z
        );
        float zSideHeight = fluidSampleHeight(
                mediumAmounts,
                fluidVoxelStates,
                mediumTypes,
                blockStates,
                materialFlags,
                neighborhood,
                mediumStateId,
                mediumTypeId,
                x,
                y,
                z + zSign
        );
        if (xSideHeight >= 1.0F || zSideHeight >= 1.0F) {
            return SectionFace.FLUID_HEIGHT_SCALE;
        }

        float[] weightedHeight = new float[2];
        if (xSideHeight > 0.0F || zSideHeight > 0.0F) {
            float diagonalHeight = fluidSampleHeight(
                    mediumAmounts,
                    fluidVoxelStates,
                    mediumTypes,
                    blockStates,
                    materialFlags,
                    neighborhood,
                    mediumStateId,
                    mediumTypeId,
                    x + xSign,
                    y,
                    z + zSign
            );
            if (diagonalHeight >= 1.0F) {
                return SectionFace.FLUID_HEIGHT_SCALE;
            }
            addWeightedFluidHeight(weightedHeight, diagonalHeight);
        }
        addWeightedFluidHeight(weightedHeight, centerHeight);
        addWeightedFluidHeight(weightedHeight, xSideHeight);
        addWeightedFluidHeight(weightedHeight, zSideHeight);
        if (weightedHeight[1] <= 0.0F) {
            return 0;
        }
        return Math.round((weightedHeight[0] / weightedHeight[1]) * SectionFace.FLUID_HEIGHT_SCALE);
    }

    private static void addWeightedFluidHeight(float[] weightedHeight, float height) {
        if (height < 0.0F) {
            return;
        }
        if (height >= 0.8F) {
            weightedHeight[0] += height * 10.0F;
            weightedHeight[1] += 10.0F;
            return;
        }
        weightedHeight[0] += height;
        weightedHeight[1] += 1.0F;
    }

    private static float fluidSampleHeight(
            int[] mediumAmounts,
            int[] fluidVoxelStates,
            int[] mediumTypes,
            int[] blockStates,
            int[] materialFlags,
            SectionNeighborhood neighborhood,
            int mediumStateId,
            int mediumTypeId,
            int x,
            int y,
            int z
    ) {
        if (sameFluidAmountAt(mediumAmounts, fluidVoxelStates, mediumTypes, neighborhood, mediumStateId, mediumTypeId, x, y + 1, z) > 0) {
            return 1.0F;
        }
        int amount = sameFluidAmountAt(mediumAmounts, fluidVoxelStates, mediumTypes, neighborhood, mediumStateId, mediumTypeId, x, y, z);
        if (amount > 0) {
            return Math.max(0.0F, Math.min(1.0F, amount / 9.0F));
        }
        int sampleFlags = materialFlagsAt(blockStates, materialFlags, neighborhood, x, y, z);
        if (sampleFlags < 0) {
            return -1.0F;
        }
        return isSolidForFluidHeight(sampleFlags) ? -1.0F : 0.0F;
    }

    private static int sameFluidAmountAt(
            int[] mediumAmounts,
            int[] fluidVoxelStates,
            int[] mediumTypes,
            SectionNeighborhood neighborhood,
            int mediumStateId,
            int mediumTypeId,
            int x,
            int y,
            int z
    ) {
        if (insideSection(x, y, z)) {
            int index = linearIndex(x, y, z);
            return sameFluidType(fluidVoxelStates[index], mediumTypes[index], mediumStateId, mediumTypeId)
                    ? mediumAmounts[index]
                    : 0;
        }
        if (!neighborhood.hasGeometrySampleAtLocalCoordinate(x, y, z)) {
            return 0;
        }
        return sameFluidType(
                neighborhood.mediumStateIdAtLocalCoordinate(x, y, z),
                neighborhood.mediumTypeIdAtLocalCoordinate(x, y, z),
                mediumStateId,
                mediumTypeId
        )
                ? neighborhood.mediumAmountAtLocalCoordinate(x, y, z)
                : 0;
    }

    private static int materialFlagsAt(
            int[] blockStates,
            int[] materialFlags,
            SectionNeighborhood neighborhood,
            int x,
            int y,
            int z
    ) {
        if (insideSection(x, y, z)) {
            int index = linearIndex(x, y, z);
            if (blockStates[index] == 0
                    || (materialFlags[index] & SectionVoxelSnapshot.FLAG_AIR) != 0) {
                return SectionVoxelSnapshot.FLAG_AIR | SectionVoxelSnapshot.FLAG_OCCLUSION_KNOWN;
            }
            return materialFlags[index];
        }
        if (!neighborhood.hasGeometrySampleAtLocalCoordinate(x, y, z)) {
            return -1;
        }
        return neighborhood.geometryMaterialFlagsAtLocalCoordinate(x, y, z);
    }

    private static boolean isSolidForFluidHeight(int materialFlags) {
        if ((materialFlags & SectionVoxelSnapshot.FLAG_AIR) != 0) {
            return false;
        }
        return (materialFlags & SectionVoxelSnapshot.FLAG_FLUID_HEIGHT_SOLID) != 0;
    }

    private static boolean sameFluidType(
            int sampleFluidVoxelStateId,
            int sampleFluidTypeId,
            int targetFluidVoxelStateId,
            int targetFluidTypeId
    ) {
        if (targetFluidTypeId > 0) {
            return sampleFluidTypeId == targetFluidTypeId;
        }
        return targetFluidVoxelStateId != 0 && sampleFluidVoxelStateId == targetFluidVoxelStateId;
    }

    private static void sourceEngineVertexLighting(
            SectionLightSampler sampler,
            FaceDirection direction,
            int x,
            int y,
            int z,
            boolean applyDirectionalShade,
            int[] out
    ) {
        PackedVoxelLighting.fullCubeFacePacked(direction, x, y, z, sampler, applyDirectionalShade, out);
    }

    private static int mapColorWithExteriorLight(
            int sourcePackedMapColor,
            int[] sectionPackedMapColors,
            SectionNeighborhood neighborhood,
            FaceDirection direction,
            int x,
            int y,
            int z
    ) {
        int exteriorPackedLight = exteriorPackedLight(
                sourcePackedMapColor,
                sectionPackedMapColors,
                neighborhood,
                direction,
                x,
                y,
                z
        );
        return (sourcePackedMapColor & SectionVoxelSnapshot.MAP_COLOR_RGB_MASK)
                | (exteriorPackedLight << SectionVoxelSnapshot.PACKED_LIGHT_SHIFT);
    }

    private static int fluidFaceMapColorWithLight(
            int sourcePackedMapColor,
            int[] sectionPackedMapColors,
            SectionNeighborhood neighborhood,
            int lightEmission,
            int x,
            int y,
            int z
    ) {
        int fluidPackedLight = fluidPackedLight(sourcePackedMapColor, sectionPackedMapColors, neighborhood, x, y, z);
        int emittedBlockLight = Math.max(0, Math.min(15, lightEmission));
        fluidPackedLight = (fluidPackedLight & 0xF0)
                | Math.max(fluidPackedLight & 0x0F, emittedBlockLight);
        return (sourcePackedMapColor & SectionVoxelSnapshot.MAP_COLOR_RGB_MASK)
                | (fluidPackedLight << SectionVoxelSnapshot.PACKED_LIGHT_SHIFT);
    }

    private static int fluidPackedLight(
            int sourcePackedMapColor,
            int[] sectionPackedMapColors,
            SectionNeighborhood neighborhood,
            int x,
            int y,
            int z
    ) {
        /*
         * sourceEngine medium surface renderer does not run block-model AO for liquid quads.
         * It samples LightCoordsUtil.max(pos, pos.above()) once and writes the
         * same packed light to every fluid vertex. Keeping that separate from
         * the block AO path prevents water/lava from inheriting stale exposed
         * block-corner shadows during digging or flowing updates.
         */
        int selfPackedLight = samplePackedLight(sectionPackedMapColors, neighborhood, x, y, z);
        int abovePackedLight = samplePackedLight(sectionPackedMapColors, neighborhood, x, y + 1, z);
        int fallback = SectionVoxelSnapshot.packedLight(sourcePackedMapColor);
        if (selfPackedLight < 0) {
            selfPackedLight = fallback;
        }
        if (abovePackedLight < 0) {
            abovePackedLight = fallback;
        }
        return maxPackedLight(selfPackedLight, abovePackedLight);
    }

    private static int exteriorPackedLight(
            int sourcePackedMapColor,
            int[] sectionPackedMapColors,
            SectionNeighborhood neighborhood,
            FaceDirection direction,
            int x,
            int y,
            int z
    ) {
        int neighborX = x + direction.stepX();
        int neighborY = y + direction.stepY();
        int neighborZ = z + direction.stepZ();
        int directPackedLight = samplePackedLight(sectionPackedMapColors, neighborhood, neighborX, neighborY, neighborZ);
        if (directPackedLight < 0) {
            /*
             * Missing neighbor sections are still a streaming boundary, not a
             * license to invent full daylight. Preserve the source packed light
             * until SceneDatabase can provide the real adjacent air/terrain
             * section, while fixing the common in-section case that made exposed
             * solid faces sample their own black interior light.
             */
            return SectionVoxelSnapshot.packedLight(sourcePackedMapColor);
        }

        /*
         * host's source model renderer does not let one stale exterior voxel
         * decide a whole face: flat lighting samples the face-adjacent position,
         * while AO also consults the four side/corner positions around that face.
         * Block mutations can momentarily expose an air voxel whose section
         * snapshot still carries 0/0 light; taking the channel-wise maximum over
         * the same exterior plane keeps newly dug faces readable without
         * fabricating light across unloaded section boundaries.
         */
        int bestPackedLight = directPackedLight;
        for (FaceDirection sideDirection : FACE_DIRECTIONS) {
            if (!isPerpendicular(direction, sideDirection)) {
                continue;
            }
            int sidePackedLight = samplePackedLight(
                    sectionPackedMapColors,
                    neighborhood,
                    neighborX + sideDirection.stepX(),
                    neighborY + sideDirection.stepY(),
                    neighborZ + sideDirection.stepZ()
            );
            if (sidePackedLight >= 0) {
                bestPackedLight = maxPackedLight(bestPackedLight, sidePackedLight);
            }
        }
        return bestPackedLight;
    }

    private static int samplePackedLight(
            int[] sectionPackedMapColors,
            SectionNeighborhood neighborhood,
            int x,
            int y,
            int z
    ) {
        if (insideSection(x, y, z)) {
            return SectionVoxelSnapshot.packedLight(sectionPackedMapColors[linearIndex(x, y, z)]);
        }

        if (!neighborhood.hasLightSampleAtLocalCoordinate(x, y, z)) {
            return -1;
        }
        return SectionVoxelSnapshot.packedLight(neighborhood.packedMapColorAtLocalCoordinate(x, y, z));
    }

    private static boolean isPerpendicular(FaceDirection faceDirection, FaceDirection candidate) {
        int dot = faceDirection.stepX() * candidate.stepX()
                + faceDirection.stepY() * candidate.stepY()
                + faceDirection.stepZ() * candidate.stepZ();
        return dot == 0;
    }

    private static int maxPackedLight(int first, int second) {
        int sky = Math.max((first >>> 4) & 0x0F, (second >>> 4) & 0x0F);
        int block = Math.max(first & 0x0F, second & 0x0F);
        return (sky << 4) | block;
    }

    private static void repairVertexLightingFloorInPlace(
            int[] vertexLighting,
            int faceMapColor,
            int lightEmission
    ) {
        int packedLight = SectionVoxelSnapshot.packedLight(faceMapColor);
        int blockFloor = Math.max(
                packedLight & 0x0F,
                Math.max(0, Math.min(15, lightEmission))
        ) * 16;
        int skyFloor = ((packedLight >>> 4) & 0x0F) * 16;
        if ((blockFloor | skyFloor) == 0) {
            return;
        }
        for (int index = 0; index < vertexLighting.length; index++) {
            int packedVertex = vertexLighting[index];
            vertexLighting[index] = PackedVoxelLighting.packVertex(
                    Math.max(PackedVoxelLighting.smoothBlock(packedVertex), blockFloor),
                    Math.max(PackedVoxelLighting.smoothSky(packedVertex), skyFloor),
                    PackedVoxelLighting.shadeByte(packedVertex) / 255.0F
            );
        }
    }

    private static FaceDirection opposite(FaceDirection direction) {
        return switch (direction) {
            case NEGATIVE_X -> FaceDirection.POSITIVE_X;
            case POSITIVE_X -> FaceDirection.NEGATIVE_X;
            case NEGATIVE_Y -> FaceDirection.POSITIVE_Y;
            case POSITIVE_Y -> FaceDirection.NEGATIVE_Y;
            case NEGATIVE_Z -> FaceDirection.POSITIVE_Z;
            case POSITIVE_Z -> FaceDirection.NEGATIVE_Z;
        };
    }

    private static boolean insideSection(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < SECTION_SIZE && y < SECTION_SIZE && z < SECTION_SIZE;
    }

    private static int linearIndex(int x, int y, int z) {
        return (y * SECTION_SIZE + z) * SECTION_SIZE + x;
    }

    /**
     * Builds geometry without neighboring samples.
     *
     * @param section encoded section snapshot
     * @return immutable staged geometry
     */
    public SectionGeometrySnapshot build(SectionEncodedSnapshot section) {
        Objects.requireNonNull(section, "section");
        return build(section, Map.of());
    }

    /**
     * Builds geometry with cardinal neighboring snapshots.
     *
     * @param section          encoded center-section snapshot
     * @param neighborSections cardinal neighbor snapshots
     * @return immutable staged geometry
     */
    public SectionGeometrySnapshot build(
            SectionEncodedSnapshot section,
            Map<FaceDirection, SectionVoxelSnapshot> neighborSections
    ) {
        Objects.requireNonNull(section, "section");
        return build(section, SectionNeighborhood.fromFaceNeighbors(section.key(), neighborSections));
    }

    /**
     * Builds geometry with an arbitrary immutable neighborhood.
     *
     * @param section      encoded center-section snapshot
     * @param neighborhood immutable neighboring samples
     * @return immutable staged geometry
     */
    public SectionGeometrySnapshot build(
            SectionEncodedSnapshot section,
            SectionNeighborhood neighborhood
    ) {
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(neighborhood, "neighborhood");
        return stageFromAccessors(
                section.key(),
                section::voxelTypeIdAt,
                section::mediumStateIdAt,
                section::mediumTypeIdAt,
                section::mediumAmountAt,
                section::fluidFlowXAt,
                section::fluidFlowZAt,
                section::mapColorAt,
                section::blockTintLayerColorAt,
                section::fluidMapColorAt,
                section::lightEmissionAt,
                section::materialFlagsAt,
                section::shadeBrightnessAt,
                neighborhood
        ).toSnapshot(section.paletteSize(), section.runCount());
    }

    /**
     * Builds geometry with explicit model facts.
     *
     * @param section           decoded section snapshot
     * @param sourcePaletteSize source palette size
     * @param sourceRunCount    source run count
     * @return immutable staged geometry
     */
    public SectionGeometrySnapshot build(
            SectionVoxelSnapshot section,
            int sourcePaletteSize,
            int sourceRunCount
    ) {
        return build(section, sourcePaletteSize, sourceRunCount, Map.of());
    }

    /**
     * Builds geometry with an explicit neighborhood and model facts.
     *
     * @param section           decoded center-section snapshot
     * @param sourcePaletteSize source palette size
     * @param sourceRunCount    source run count
     * @param neighborSections  cardinal neighbor snapshots
     * @return immutable staged geometry
     */
    public SectionGeometrySnapshot build(
            SectionVoxelSnapshot section,
            int sourcePaletteSize,
            int sourceRunCount,
            Map<FaceDirection, SectionVoxelSnapshot> neighborSections
    ) {
        Objects.requireNonNull(section, "section");
        return build(
                section,
                sourcePaletteSize,
                sourceRunCount,
                SectionNeighborhood.fromFaceNeighbors(section.key(), neighborSections)
        );
    }

    /**
     * Builds geometry using explicit block semantics and model facts.
     *
     * @param section           decoded center-section snapshot
     * @param sourcePaletteSize source palette size
     * @param sourceRunCount    source run count
     * @param neighborhood      immutable neighboring samples
     * @return immutable staged geometry
     */
    public SectionGeometrySnapshot build(
            SectionVoxelSnapshot section,
            int sourcePaletteSize,
            int sourceRunCount,
            SectionNeighborhood neighborhood
    ) {
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(neighborhood, "neighborhood");
        return stageFromAccessors(
                section.key(),
                section::voxelTypeIdAt,
                section::mediumStateIdAt,
                section::mediumTypeIdAt,
                section::mediumAmountAt,
                section::fluidFlowXAt,
                section::fluidFlowZAt,
                section::mapColorAt,
                section::blockTintLayerColorAt,
                section::fluidMapColorAt,
                section::lightEmissionAt,
                section::materialFlagsAt,
                section::shadeBrightnessAt,
                neighborhood
        ).toSnapshot(sourcePaletteSize, sourceRunCount);
    }

    /**
     * Produces the worker-private face representation consumed immediately by {@link SectionMeshBuilder}.
     * Snapshot materialization deliberately stays outside this path so production does not allocate one
     * immutable object per extracted face.
     */
    SectionFaceStaging stage(
            SectionVoxelSnapshot section,
            SectionNeighborhood neighborhood
    ) {
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(neighborhood, "neighborhood");
        return stageFromAccessors(
                section.key(),
                section::voxelTypeIdAt,
                section::mediumStateIdAt,
                section::mediumTypeIdAt,
                section::mediumAmountAt,
                section::fluidFlowXAt,
                section::fluidFlowZAt,
                section::mapColorAt,
                section::blockTintLayerColorAt,
                section::fluidMapColorAt,
                section::lightEmissionAt,
                section::materialFlagsAt,
                section::shadeBrightnessAt,
                neighborhood
        );
    }

    private SectionFaceStaging stageFromAccessors(
            SectionKey key,
            VoxelIntAccessor blockStates,
            VoxelIntAccessor fluidVoxelStates,
            VoxelIntAccessor mediumTypes,
            VoxelIntAccessor mediumAmounts,
            VoxelIntAccessor fluidFlowXs,
            VoxelIntAccessor fluidFlowZs,
            VoxelIntAccessor mapColors,
            VoxelTintLayerAccessor blockTintLayerColors,
            VoxelIntAccessor fluidMapColors,
            VoxelIntAccessor lightEmissions,
            VoxelIntAccessor materialFlags,
            VoxelIntAccessor shadeBrightnesses,
            SectionNeighborhood neighborhood
    ) {
        boolean phaseRecording = SectionMesherFlightRecorder.enabled();
        long totalStartNanos = phaseRecording ? System.nanoTime() : 0L;
        long totalStartCpuNanos = phaseRecording ? SectionMesherFlightRecorder.currentThreadCpuNanos() : 0L;
        /*
         * One worker builds one section at a time, yet the old path allocated
         * sixteen 4096-element classification arrays for every section. At a
         * 32-chunk radius this generated enough short-lived arrays to force
         * repeated old collections before the first RT front converged. Keep
         * the arena thread-confined and overwrite every slot below; immutable
         * SectionFace/SectionGeometrySnapshot ownership remains unchanged.
         */
        BuildScratch scratch = BUILD_SCRATCH.get();
        boolean[] blockRenderable = scratch.blockRenderable;
        boolean[] fluidRenderable = scratch.fluidRenderable;
        int[] blockStateValues = scratch.blockStateValues;
        int[] fluidVoxelStateValues = scratch.fluidVoxelStateValues;
        int[] mediumTypeValues = scratch.mediumTypeValues;
        int[] fluidValues = scratch.fluidValues;
        int[] fluidFlowXValues = scratch.fluidFlowXValues;
        int[] fluidFlowZValues = scratch.fluidFlowZValues;
        int[] mapColorValues = scratch.mapColorValues;
        int[] blockTintLayer0Values = scratch.blockTintLayer0Values;
        int[] blockTintLayer1Values = scratch.blockTintLayer1Values;
        int[] blockTintLayer2Values = scratch.blockTintLayer2Values;
        int[] blockTintLayer3Values = scratch.blockTintLayer3Values;
        int[] fluidMapColorValues = scratch.fluidMapColorValues;
        int[] lightEmissionValues = scratch.lightEmissionValues;
        int[] materialFlagValues = scratch.materialFlagValues;
        int[] shadeBrightnessValues = scratch.shadeBrightnessValues;
        scratch.lightSampler.configure(
                mapColorValues,
                lightEmissionValues,
                materialFlagValues,
                shadeBrightnessValues,
                neighborhood
        );
        int renderableFaceSources = 0;
        int blockFaceSources = 0;
        int fluidFaceSources = 0;

        /*
         * Classification is the hot section-build path. Cache the per-block material
         * decision once so the six neighbor probes do not repeatedly chase palette/RLE
         * runs or re-run conservative material rules for the same voxel.
         */
        for (int y = 0; y < SECTION_SIZE; y++) {
            for (int z = 0; z < SECTION_SIZE; z++) {
                for (int x = 0; x < SECTION_SIZE; x++) {
                    int index = linearIndex(x, y, z);
                    int voxelTypeId = blockStates.at(x, y, z);
                    int mediumStateId = fluidVoxelStates.at(x, y, z);
                    int mediumTypeId = mediumTypes.at(x, y, z);
                    int mediumAmount = mediumAmounts.at(x, y, z);
                    int flags = materialFlags.at(x, y, z);
                    blockStateValues[index] = voxelTypeId;
                    fluidVoxelStateValues[index] = mediumStateId;
                    mediumTypeValues[index] = mediumTypeId;
                    fluidValues[index] = mediumAmount;
                    fluidFlowXValues[index] = fluidFlowXs.at(x, y, z);
                    fluidFlowZValues[index] = fluidFlowZs.at(x, y, z);
                    mapColorValues[index] = mapColors.at(x, y, z);
                    blockTintLayer0Values[index] = blockTintLayerColors.at(x, y, z, 0);
                    blockTintLayer1Values[index] = blockTintLayerColors.at(x, y, z, 1);
                    blockTintLayer2Values[index] = blockTintLayerColors.at(x, y, z, 2);
                    blockTintLayer3Values[index] = blockTintLayerColors.at(x, y, z, 3);
                    lightEmissionValues[index] = lightEmissions.at(x, y, z);
                    materialFlagValues[index] = flags;
                    fluidMapColorValues[index] = fluidMapColors.at(x, y, z);
                    shadeBrightnessValues[index] = shadeBrightnesses.at(x, y, z);
                    boolean pureFluidBlock = isPureFluidBlock(voxelTypeId, mediumStateId, mediumAmount, flags);
                    boolean voxelBlockRenderable = !pureFluidBlock
                            && classifier.isRenderable(voxelTypeId, 0, flags)
                            && (flags & SectionVoxelSnapshot.FLAG_RENDER_SHAPE_VISIBLE) != 0;
                    boolean voxelFluidRenderable = mediumAmount > 0 && mediumStateId != 0;
                    blockRenderable[index] = voxelBlockRenderable;
                    fluidRenderable[index] = voxelFluidRenderable;
                    if (voxelBlockRenderable) {
                        renderableFaceSources++;
                        blockFaceSources++;
                    }
                    if (voxelFluidRenderable) {
                        renderableFaceSources++;
                        fluidFaceSources++;
                    }
                }
            }
        }
        long classificationNanos = phaseRecording ? System.nanoTime() - totalStartNanos : 0L;
        long classificationCpuNanos = phaseRecording
                ? SectionMesherFlightRecorder.currentThreadCpuNanos() - totalStartCpuNanos
                : 0L;
        long surfaceEmissionStartNanos = phaseRecording ? System.nanoTime() : 0L;
        long surfaceEmissionStartCpuNanos = phaseRecording
                ? SectionMesherFlightRecorder.currentThreadCpuNanos()
                : 0L;

        SectionFaceStaging faces = scratch.faceStaging;
        faces.reset(key, Math.min(renderableFaceSources * FACE_DIRECTIONS.length, 4096));
        int modelFactCount = 0;
        for (int y = 0; y < SECTION_SIZE; y++) {
            for (int z = 0; z < SECTION_SIZE; z++) {
                for (int x = 0; x < SECTION_SIZE; x++) {
                    int index = linearIndex(x, y, z);
                    int voxelTypeId = blockStateValues[index];
                    int mediumAmount = fluidValues[index];
                    int flags = materialFlagValues[index];
                    if (blockRenderable[index]) {
                        int blockFaceStart = faces.faceCount();
                        RtTextureCatalog.ModelQuads resolvedModel = modelResolver == null
                                ? null
                                : Objects.requireNonNull(
                                modelResolver.resolve(
                                        voxelTypeId,
                                        (key.x() << 4) + x,
                                        (key.y() << 4) + y,
                                        (key.z() << 4) + z
                                ),
                                "resolved model"
                        );
                        boolean geometryBlock = resolvedModel == null
                                ? isGeometryBlock(voxelTypeId, key, x, y, z)
                                : resolvedModel.usesBakedGeometry();
                        for (FaceDirection direction : FACE_DIRECTIONS) {
                            boolean faceVisible = shouldRenderBlockFace(
                                    voxelTypeId,
                                    flags,
                                    blockStateValues,
                                    materialFlagValues,
                                    neighborhood,
                                    direction,
                                    x,
                                    y,
                                    z
                            );
                            if (faceVisible || geometryBlock) {
                                int faceMapColor = mapColorWithExteriorLight(
                                        mapColorValues[index],
                                        mapColorValues,
                                        neighborhood,
                                        direction,
                                        x,
                                        y,
                                        z
                                );
                                int[] vertexLighting = scratch.vertexLighting;
                                sourceEngineVertexLighting(
                                        scratch.lightSampler,
                                        direction,
                                        x,
                                        y,
                                        z,
                                        true,
                                        vertexLighting
                                );
                                repairVertexLightingFloorInPlace(
                                        vertexLighting, faceMapColor, lightEmissionValues[index]);
                                faces.append(
                                        x,
                                        y,
                                        z,
                                        direction,
                                        voxelTypeId,
                                        0,
                                        faceMapColor,
                                        blockTintLayer0Values[index],
                                        blockTintLayer1Values[index],
                                        blockTintLayer2Values[index],
                                        blockTintLayer3Values[index],
                                        lightEmissionValues[index],
                                        flags & ~SectionVoxelSnapshot.FLAG_LIQUID,
                                        faceVisible,
                                        vertexLighting[0],
                                        vertexLighting[1],
                                        vertexLighting[2],
                                        vertexLighting[3],
                                        SectionFace.FLUID_HEIGHT_SCALE,
                                        SectionFace.FLUID_HEIGHT_SCALE,
                                        SectionFace.FLUID_HEIGHT_SCALE,
                                        SectionFace.FLUID_HEIGHT_SCALE,
                                        0,
                                        0,
                                        false,
                                        SectionFace.UNKNOWN_FLUID_TYPE_ID
                                );
                            }
                        }
                        if (resolvedModel != null && faces.faceCount() > blockFaceStart) {
                            scratch.modelBlockIndices[modelFactCount] = index;
                            scratch.models[modelFactCount] = resolvedModel;
                            modelFactCount++;
                        }
                    }

                    if (fluidRenderable[index]) {
                        int[] fluidCornerHeights = scratch.fluidCornerHeights;
                        fluidCornerHeights(
                                fluidValues,
                                fluidVoxelStateValues,
                                mediumTypeValues,
                                blockStateValues,
                                materialFlagValues,
                                neighborhood,
                                fluidVoxelStateValues[index],
                                mediumTypeValues[index],
                                x,
                                y,
                                z,
                                fluidCornerHeights
                        );
                        int mediumStateId = fluidVoxelStateValues[index];
                        int fluidFlags = SectionVoxelSnapshot.FLAG_RENDER_SHAPE_VISIBLE
                                | SectionVoxelSnapshot.FLAG_LIQUID
                                | SectionVoxelSnapshot.FLAG_OCCLUSION_KNOWN
                                | SectionVoxelSnapshot.FLAG_LIGHT_KNOWN;
                        for (FaceDirection direction : FACE_DIRECTIONS) {
                            int[] fluidHeights = scratch.fluidFaceHeights;
                            fluidFaceHeights(direction, fluidCornerHeights, fluidHeights);
                            if (shouldRenderFluidFace(
                                    blockStateValues[index],
                                    mediumStateId,
                                    mediumTypeValues[index],
                                    mediumAmount,
                                    fluidHeights,
                                    blockStateValues,
                                    fluidVoxelStateValues,
                                    mediumTypeValues,
                                    fluidValues,
                                    materialFlagValues,
                                    neighborhood,
                                    direction,
                                    x,
                                    y,
                                    z
                            )) {
                                int sourceFluidMapColor = fluidMapColorValues[index] != SectionVoxelSnapshot.NO_MAP_COLOR
                                        ? fluidMapColorValues[index]
                                        : mapColorValues[index];
                                int faceMapColor = fluidFaceMapColorWithLight(
                                        sourceFluidMapColor,
                                        mapColorValues,
                                        neighborhood,
                                        lightEmissionValues[index],
                                        x,
                                        y,
                                        z
                                );
                                int packedVertexLighting = PackedVoxelLighting.packFlatVertex(faceMapColor, direction);
                                faces.append(
                                        x,
                                        y,
                                        z,
                                        direction,
                                        mediumStateId,
                                        mediumAmount,
                                        faceMapColor,
                                        SectionVoxelSnapshot.NO_BLOCK_TINT_LAYER_COLOR,
                                        SectionVoxelSnapshot.NO_BLOCK_TINT_LAYER_COLOR,
                                        SectionVoxelSnapshot.NO_BLOCK_TINT_LAYER_COLOR,
                                        SectionVoxelSnapshot.NO_BLOCK_TINT_LAYER_COLOR,
                                        lightEmissionValues[index],
                                        fluidFlags,
                                        true,
                                        packedVertexLighting,
                                        packedVertexLighting,
                                        packedVertexLighting,
                                        packedVertexLighting,
                                        fluidHeights[0],
                                        fluidHeights[1],
                                        fluidHeights[2],
                                        fluidHeights[3],
                                        fluidFlowXValues[index],
                                        fluidFlowZValues[index],
                                        shouldUseFluidSideOverlay(
                                                blockStateValues,
                                                neighborhood,
                                                direction,
                                                x,
                                                y,
                                                z
                                        ),
                                        mediumTypeValues[index]
                                );
                            }
                        }
                    }
                }
            }
        }

        SectionModelFacts modelFacts = modelResolver == null
                ? SectionModelFacts.unavailable()
                : SectionModelFacts.complete(
                scratch.modelBlockIndices,
                scratch.models,
                modelFactCount
        );
        faces.setModelFacts(modelFacts);
        if (phaseRecording) {
            SectionMesherFlightRecorder.record(
                    key,
                    faces.faceCount(),
                    blockFaceSources,
                    fluidFaceSources,
                    classificationNanos,
                    classificationCpuNanos,
                    System.nanoTime() - surfaceEmissionStartNanos,
                    SectionMesherFlightRecorder.currentThreadCpuNanos() - surfaceEmissionStartCpuNanos,
                    System.nanoTime() - totalStartNanos
            );
        }
        return faces;
    }

    private boolean isGeometryBlock(
            int voxelTypeId,
            SectionKey sectionKey,
            int localX,
            int localY,
            int localZ
    ) {
        if (voxelTypeId == 0) {
            return false;
        }
        return geometryResolver.test(
                voxelTypeId,
                (sectionKey.x() << 4) + localX,
                (sectionKey.y() << 4) + localY,
                (sectionKey.z() << 4) + localZ
        );
    }

    private boolean shouldRenderBlockFace(
            int currentVoxelStateId,
            int currentMaterialFlags,
            int[] blockStateValues,
            int[] materialFlagValues,
            SectionNeighborhood neighborhood,
            FaceDirection direction,
            int x,
            int y,
            int z
    ) {
        int neighborX = x + direction.stepX();
        int neighborY = y + direction.stepY();
        int neighborZ = z + direction.stepZ();
        if (insideSection(neighborX, neighborY, neighborZ)) {
            int neighborIndex = linearIndex(neighborX, neighborY, neighborZ);
            return shouldRenderFaceAgainstNeighbor(
                    currentVoxelStateId,
                    currentMaterialFlags,
                    blockStateValues[neighborIndex],
                    materialFlagValues[neighborIndex],
                    direction
            );
        }

        if (!neighborhood.hasGeometrySampleAtLocalCoordinate(neighborX, neighborY, neighborZ)) {
            return true;
        }
        return shouldRenderFaceAgainstNeighbor(
                currentVoxelStateId,
                currentMaterialFlags,
                neighborhood.voxelTypeIdAtLocalCoordinate(neighborX, neighborY, neighborZ),
                neighborhood.geometryMaterialFlagsAtLocalCoordinate(neighborX, neighborY, neighborZ),
                direction
        );
    }

    private boolean shouldRenderFaceAgainstNeighbor(
            int currentVoxelStateId,
            int currentMaterialFlags,
            int neighborVoxelStateId,
            int neighborMaterialFlags,
            FaceDirection direction
    ) {
        if ((neighborMaterialFlags & SectionVoxelSnapshot.FLAG_AIR) != 0) {
            return true;
        }

        if (((currentMaterialFlags | neighborMaterialFlags) & SectionVoxelSnapshot.FLAG_OCCLUSION_KNOWN) != 0) {
            Boolean sourceEngineDecision = blockSemantics.shouldRenderFace(
                    currentVoxelStateId,
                    neighborVoxelStateId,
                    direction
            );
            if (sourceEngineDecision != null) {
                return sourceEngineDecision;
            }
        }

        if ((neighborMaterialFlags & SectionVoxelSnapshot.FLAG_OCCLUDES_NEIGHBORS) == 0) {
            return true;
        }
        return false;
    }

    private boolean shouldRenderFluidFace(
            int currentVoxelStateId,
            int currentFluidVoxelStateId,
            int currentFluidTypeId,
            int currentFluidAmount,
            int[] currentFaceFluidHeights,
            int[] blockStateValues,
            int[] fluidVoxelStateValues,
            int[] mediumTypeValues,
            int[] fluidValues,
            int[] materialFlagValues,
            SectionNeighborhood neighborhood,
            FaceDirection direction,
            int x,
            int y,
            int z
    ) {
        int neighborX = x + direction.stepX();
        int neighborY = y + direction.stepY();
        int neighborZ = z + direction.stepZ();
        if (insideSection(neighborX, neighborY, neighborZ)) {
            int neighborIndex = linearIndex(neighborX, neighborY, neighborZ);
            return shouldRenderFluidFaceAgainstNeighbor(
                    currentVoxelStateId,
                    currentFluidVoxelStateId,
                    currentFluidTypeId,
                    currentFluidAmount,
                    currentFaceFluidHeights,
                    blockStateValues[neighborIndex],
                    fluidVoxelStateValues[neighborIndex],
                    mediumTypeValues[neighborIndex],
                    fluidValues[neighborIndex],
                    materialFlagValues[neighborIndex],
                    direction
            );
        }

        if (!neighborhood.hasGeometrySampleAtLocalCoordinate(neighborX, neighborY, neighborZ)) {
            return true;
        }
        return shouldRenderFluidFaceAgainstNeighbor(
                currentVoxelStateId,
                currentFluidVoxelStateId,
                currentFluidTypeId,
                currentFluidAmount,
                currentFaceFluidHeights,
                neighborhood.voxelTypeIdAtLocalCoordinate(neighborX, neighborY, neighborZ),
                neighborhood.mediumStateIdAtLocalCoordinate(neighborX, neighborY, neighborZ),
                neighborhood.mediumTypeIdAtLocalCoordinate(neighborX, neighborY, neighborZ),
                neighborhood.mediumAmountAtLocalCoordinate(neighborX, neighborY, neighborZ),
                neighborhood.geometryMaterialFlagsAtLocalCoordinate(neighborX, neighborY, neighborZ),
                direction
        );
    }

    private boolean shouldRenderFluidFaceAgainstNeighbor(
            int currentVoxelStateId,
            int currentFluidVoxelStateId,
            int currentFluidTypeId,
            int currentFluidAmount,
            int[] currentFaceFluidHeights,
            int neighborVoxelStateId,
            int neighborFluidVoxelStateId,
            int neighborFluidTypeId,
            int neighborFluidAmount,
            int neighborMaterialFlags,
            FaceDirection direction
    ) {
        if (isSameFluidVolume(
                currentFluidVoxelStateId,
                currentFluidTypeId,
                currentFluidAmount,
                neighborFluidVoxelStateId,
                neighborFluidTypeId,
                neighborFluidAmount
        )) {
            return false;
        }
        if (direction != FaceDirection.POSITIVE_Y) {
            Boolean selfOccluded = sourceEngineFluidFaceOccludedBySelf(
                    currentVoxelStateId,
                    direction
            );
            if (Boolean.TRUE.equals(selfOccluded)) {
                return false;
            }
        }
        Boolean neighborOccluded = sourceEngineFluidFaceOccludedByNeighbor(
                neighborVoxelStateId,
                direction,
                fluidOcclusionHeight(direction, currentFaceFluidHeights)
        );
        if (neighborOccluded != null) {
            return !neighborOccluded;
        }
        if ((neighborMaterialFlags & SectionVoxelSnapshot.FLAG_AIR) != 0) {
            return true;
        }
        if ((neighborMaterialFlags & SectionVoxelSnapshot.FLAG_OCCLUDES_NEIGHBORS) != 0) {
            return false;
        }
        return neighborVoxelStateId == 0 || (neighborMaterialFlags & SectionVoxelSnapshot.FLAG_RENDER_SHAPE_VISIBLE) == 0;
    }

    private Boolean sourceEngineFluidFaceOccludedBySelf(
            int voxelTypeId,
            FaceDirection direction
    ) {
        return blockSemantics.fluidFaceOccluded(voxelTypeId, opposite(direction), 1.0F);
    }

    private Boolean sourceEngineFluidFaceOccludedByNeighbor(
            int voxelTypeId,
            FaceDirection direction,
            float height
    ) {
        return blockSemantics.fluidFaceOccluded(voxelTypeId, direction, height);
    }

    private boolean shouldUseFluidSideOverlay(
            int[] blockStateValues,
            SectionNeighborhood neighborhood,
            FaceDirection direction,
            int x,
            int y,
            int z
    ) {
        if (direction == FaceDirection.POSITIVE_Y || direction == FaceDirection.NEGATIVE_Y) {
            return false;
        }
        int neighborX = x + direction.stepX();
        int neighborY = y + direction.stepY();
        int neighborZ = z + direction.stepZ();
        int neighborVoxelStateId;
        if (insideSection(neighborX, neighborY, neighborZ)) {
            neighborVoxelStateId = blockStateValues[linearIndex(neighborX, neighborY, neighborZ)];
        } else {
            if (!neighborhood.hasGeometrySampleAtLocalCoordinate(neighborX, neighborY, neighborZ)) {
                return false;
            }
            neighborVoxelStateId = neighborhood.voxelTypeIdAtLocalCoordinate(neighborX, neighborY, neighborZ);
        }
        return blockSemantics.useFluidSideOverlay(neighborVoxelStateId);
    }

    @FunctionalInterface
    private interface PositionedModelResolver {
        RtTextureCatalog.ModelQuads resolve(int voxelTypeId, int worldX, int worldY, int worldZ);
    }

    @FunctionalInterface
    private interface PositionedGeometryResolver {
        boolean test(int voxelTypeId, int worldX, int worldY, int worldZ);
    }

    @FunctionalInterface
    private interface VoxelIntAccessor {
        int at(int x, int y, int z);
    }

    @FunctionalInterface
    private interface VoxelTintLayerAccessor {
        int at(int x, int y, int z, int tintLayer);
    }

    /**
     * Supplies renderer-independent block semantics required by meshing.
     */
    public interface BlockSemantics {
        /**
         * Block semantics that defer every decision to conservative renderer defaults.
         */
        BlockSemantics NONE = new BlockSemantics() {
        };

        /**
         * Optionally overrides whether one voxel face should be emitted.
         *
         * @param currentVoxelStateId  current voxel-state identifier
         * @param neighborVoxelStateId neighboring voxel-state identifier
         * @param direction            outward face direction
         * @return explicit decision, or {@code null} to use renderer defaults
         */
        default Boolean shouldRenderFace(
                int currentVoxelStateId,
                int neighborVoxelStateId,
                FaceDirection direction
        ) {
            return null;
        }

        /**
         * Optionally overrides whether a neighboring voxel occludes a fluid face.
         *
         * @param voxelTypeId neighboring voxel type identifier
         * @param direction   outward fluid-face direction
         * @param fluidHeight normalized fluid height
         * @return explicit decision, or {@code null} to use renderer defaults
         */
        default Boolean fluidFaceOccluded(int voxelTypeId, FaceDirection direction, float fluidHeight) {
            return null;
        }

        /**
         * Selects the fluid side-overlay material for one neighboring voxel state.
         *
         * @param neighborVoxelStateId neighboring voxel-state identifier
         * @return {@code true} when the side overlay is required
         */
        default boolean useFluidSideOverlay(int neighborVoxelStateId) {
            return false;
        }
    }

    /**
     * Thread-confined mutable staging; no reference escapes a build call.
     */
    private static final class BuildScratch {
        private final boolean[] blockRenderable = new boolean[BLOCKS_PER_SECTION];
        private final boolean[] fluidRenderable = new boolean[BLOCKS_PER_SECTION];
        private final int[] blockStateValues = new int[BLOCKS_PER_SECTION];
        private final int[] fluidVoxelStateValues = new int[BLOCKS_PER_SECTION];
        private final int[] mediumTypeValues = new int[BLOCKS_PER_SECTION];
        private final int[] fluidValues = new int[BLOCKS_PER_SECTION];
        private final int[] fluidFlowXValues = new int[BLOCKS_PER_SECTION];
        private final int[] fluidFlowZValues = new int[BLOCKS_PER_SECTION];
        private final int[] mapColorValues = new int[BLOCKS_PER_SECTION];
        private final int[] blockTintLayer0Values = new int[BLOCKS_PER_SECTION];
        private final int[] blockTintLayer1Values = new int[BLOCKS_PER_SECTION];
        private final int[] blockTintLayer2Values = new int[BLOCKS_PER_SECTION];
        private final int[] blockTintLayer3Values = new int[BLOCKS_PER_SECTION];
        private final int[] fluidMapColorValues = new int[BLOCKS_PER_SECTION];
        private final int[] lightEmissionValues = new int[BLOCKS_PER_SECTION];
        private final int[] materialFlagValues = new int[BLOCKS_PER_SECTION];
        private final int[] shadeBrightnessValues = new int[BLOCKS_PER_SECTION];
        private final int[] modelBlockIndices = new int[BLOCKS_PER_SECTION];
        private final RtTextureCatalog.ModelQuads[] models =
                new RtTextureCatalog.ModelQuads[BLOCKS_PER_SECTION];
        private final SectionFaceStaging faceStaging = new SectionFaceStaging();
        private final SectionLightSampler lightSampler = new SectionLightSampler();
        private final int[] vertexLighting = new int[PackedVoxelLighting.VERTICES_PER_QUAD];
        private final int[] fluidCornerHeights = new int[4];
        private final int[] fluidFaceHeights = new int[4];
    }

    private static final class SectionLightSampler implements PackedVoxelLighting.PackedLightSampler {
        private int[] packedMapColors;
        private int[] lightEmissions;
        private int[] materialFlags;
        private int[] shadeBrightnesses;
        private SectionNeighborhood neighborhood;

        private void configure(
                int[] packedMapColors,
                int[] lightEmissions,
                int[] materialFlags,
                int[] shadeBrightnesses,
                SectionNeighborhood neighborhood
        ) {
            this.packedMapColors = Objects.requireNonNull(packedMapColors, "packedMapColors");
            this.lightEmissions = Objects.requireNonNull(lightEmissions, "lightEmissions");
            this.materialFlags = Objects.requireNonNull(materialFlags, "materialFlags");
            this.shadeBrightnesses = Objects.requireNonNull(shadeBrightnesses, "shadeBrightnesses");
            this.neighborhood = Objects.requireNonNull(neighborhood, "neighborhood");
        }

        @Override
        public long sample(int x, int y, int z) {
            if (insideSection(x, y, z)) {
                int index = linearIndex(x, y, z);
                return PackedVoxelLighting.packLightSample(
                        SectionVoxelSnapshot.packedLight(packedMapColors[index]),
                        lightEmissions[index],
                        shadeBrightnesses[index],
                        materialFlags[index]
                );
            }
            if (!neighborhood.hasLightSampleAtLocalCoordinate(x, y, z)) {
                return 0L;
            }
            return PackedVoxelLighting.packLightSample(
                    SectionVoxelSnapshot.packedLight(neighborhood.packedMapColorAtLocalCoordinate(x, y, z)),
                    neighborhood.lightEmissionAtLocalCoordinate(x, y, z),
                    neighborhood.shadeBrightnessAtLocalCoordinate(x, y, z),
                    neighborhood.lightMaterialFlagsAtLocalCoordinate(x, y, z)
            );
        }
    }
}
