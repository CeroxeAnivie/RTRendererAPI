package top.ceroxe.rt.renderer.scene;

import java.util.Objects;

/**
 * CPU-side implementation of the embedding application's voxel-lighting semantics
 * for the renderer-owned section mesh path.
 *
 * <p>The RT shader cannot reproduce source smooth lighting if the material
 * table only carries one light value per face. The embedding application computes four vertex
 * light coordinates and four AO shade values before the vertex reaches the GPU;
 * this class bakes that same per-vertex contract into an engine-neutral packed
 * word so closest-hit can interpolate it exactly like a rasterized quad.</p>
 */
public final class PackedVoxelLighting {
    /**
     * Number of vertices in one quad lighting payload.
     */
    public static final int VERTICES_PER_QUAD = 4;
    /**
     * Maximum representable smooth-light coordinate.
     */
    public static final int SMOOTH_LIGHT_MAX = 240;

    private static final int SMOOTH_BLOCK_MASK = 0x0000_00FF;
    private static final int SMOOTH_SKY_SHIFT = 8;
    private static final int SHADE_SHIFT = 16;

    private static final int[] REMAP_DOWN = {0, 1, 2, 3};
    private static final int[] REMAP_UP = {2, 3, 0, 1};
    private static final int[] REMAP_NORTH = {3, 0, 1, 2};
    private static final int[] REMAP_SOUTH = {0, 1, 2, 3};
    private static final int[] REMAP_WEST = {3, 0, 1, 2};
    private static final int[] REMAP_EAST = {1, 2, 3, 0};

    private PackedVoxelLighting() {
    }

    /**
     * Packs smooth block light, sky light and shade into one vertex word.
     *
     * @param smoothBlock smooth block-light coordinate
     * @param smoothSky   smooth sky-light coordinate
     * @param shade       normalized ambient shade
     * @return packed vertex-lighting word
     */
    public static int packVertex(int smoothBlock, int smoothSky, float shade) {
        int block = clampSmoothLight(smoothBlock);
        int sky = clampSmoothLight(smoothSky);
        int shadeByte = quantizeShade(shade);
        return block | (sky << SMOOTH_SKY_SHIFT) | (shadeByte << SHADE_SHIFT);
    }

    /**
     * Packs flat lighting using the cardinal shade of a face.
     *
     * @param packedMapColorAndLight source material color and compact light word
     * @param direction              face direction
     * @return packed vertex-lighting word
     */
    public static int packFlatVertex(int packedMapColorAndLight, FaceDirection direction) {
        return packFlatVertex(packedMapColorAndLight, cardinalShade(direction));
    }

    /**
     * Packs flat lighting using an explicit shade factor.
     *
     * @param packedMapColorAndLight source material color and compact light word
     * @param shade                  normalized shade factor
     * @return packed vertex-lighting word
     */
    public static int packFlatVertex(int packedMapColorAndLight, float shade) {
        int packedLight = SectionVoxelSnapshot.packedLight(packedMapColorAndLight);
        int block = (packedLight & 0x0F) * 16;
        int sky = ((packedLight >>> 4) & 0x0F) * 16;
        return packVertex(block, sky, shade);
    }

    /**
     * Extracts the smooth block-light coordinate.
     *
     * @param packedVertex packed vertex-lighting word
     * @return smooth block-light coordinate
     */
    public static int smoothBlock(int packedVertex) {
        return packedVertex & SMOOTH_BLOCK_MASK;
    }

    /**
     * Extracts the smooth sky-light coordinate.
     *
     * @param packedVertex packed vertex-lighting word
     * @return smooth sky-light coordinate
     */
    public static int smoothSky(int packedVertex) {
        return (packedVertex >>> SMOOTH_SKY_SHIFT) & 0xFF;
    }

    /**
     * Extracts the quantized ambient shade.
     *
     * @param packedVertex packed vertex-lighting word
     * @return unsigned shade byte
     */
    public static int shadeByte(int packedVertex) {
        return (packedVertex >>> SHADE_SHIFT) & 0xFF;
    }

    /**
     * Replaces only the shade component of a packed vertex word.
     *
     * @param packedVertex packed vertex-lighting word
     * @param shade        normalized replacement shade
     * @return packed word with the replacement shade
     */
    public static int withShade(int packedVertex, float shade) {
        return (packedVertex & 0x0000_FFFF)
                | (quantizeShade(shade) << SHADE_SHIFT);
    }

    /**
     * Matches the source color scaling contract exactly. The source narrows the scaled
     * channel with a Java float-to-int conversion, which truncates toward zero;
     * rounding here changes a downward face from 127 to 128 and therefore makes
     * the RT vertex payload observably different before any shader runs.
     */
    private static int quantizeShade(float shade) {
        return clampByte((int) (clamp01(shade) * 255.0F));
    }

    /**
     * Removes a previously applied cardinal face shade without changing light coordinates.
     *
     * @param packedVertex packed vertex-lighting word
     * @param direction    face direction whose shade was applied
     * @return packed word with cardinal shading removed
     */
    public static int removeCardinalShade(int packedVertex, FaceDirection direction) {
        float divisor = cardinalShade(direction);
        if (divisor <= 0.0F) {
            return packedVertex;
        }
        int cardinalByte = quantizeShade(divisor);
        if (cardinalByte <= 0) {
            return packedVertex;
        }
        /*
         * The packed shade has already undergone the source contract's truncation.
         * Dividing its normalized byte by the original float turns DOWN's
         * exact 127/127 into 254/255.  Undo the same quantized factor that was
         * actually applied so an unshaded quad returns exactly to 1.0.
         */
        return withShade(packedVertex, shadeByte(packedVertex) / (float) cardinalByte);
    }

    /**
     * Returns the conventional diffuse shade scale for a cardinal face.
     *
     * @param direction face direction
     * @return normalized shade scale
     */
    public static float cardinalShade(FaceDirection direction) {
        return switch (Objects.requireNonNull(direction, "direction")) {
            case NEGATIVE_Y -> 0.50F;
            case POSITIVE_Y -> 1.00F;
            case NEGATIVE_X, POSITIVE_X -> 0.60F;
            case NEGATIVE_Z, POSITIVE_Z -> 0.80F;
        };
    }

    /**
     * Evaluates smooth per-vertex lighting for one full-cube face.
     *
     * @param direction             face direction
     * @param x                     source voxel X coordinate
     * @param y                     source voxel Y coordinate
     * @param z                     source voxel Z coordinate
     * @param sampler               neighboring voxel-light sampler
     * @param applyDirectionalShade whether to multiply by cardinal face shading
     * @return four packed vertex-lighting words in render order
     */
    public static int[] fullCubeFace(
            FaceDirection direction,
            int x,
            int y,
            int z,
            LightSampler sampler,
            boolean applyDirectionalShade
    ) {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(sampler, "sampler");

        LightSample source = sampler.sample(x, y, z);
        int baseX = x + direction.stepX();
        int baseY = y + direction.stepY();
        int baseZ = z + direction.stepZ();
        LightSample center = sampler.sampleOrDefault(baseX, baseY, baseZ, source);

        FaceDirection[] corners = corners(direction);
        LightSample side0 = sampler.sampleOrDefault(
                baseX + corners[0].stepX(),
                baseY + corners[0].stepY(),
                baseZ + corners[0].stepZ(),
                source
        );
        LightSample side1 = sampler.sampleOrDefault(
                baseX + corners[1].stepX(),
                baseY + corners[1].stepY(),
                baseZ + corners[1].stepZ(),
                source
        );
        LightSample side2 = sampler.sampleOrDefault(
                baseX + corners[2].stepX(),
                baseY + corners[2].stepY(),
                baseZ + corners[2].stepZ(),
                source
        );
        LightSample side3 = sampler.sampleOrDefault(
                baseX + corners[3].stepX(),
                baseY + corners[3].stepY(),
                baseZ + corners[3].stepZ(),
                source
        );

        boolean translucent0 = sampler.sampleOrDefault(
                baseX + corners[0].stepX() + direction.stepX(),
                baseY + corners[0].stepY() + direction.stepY(),
                baseZ + corners[0].stepZ() + direction.stepZ(),
                LightSample.TRANSLUCENT_AIR
        ).aoTranslucent();
        boolean translucent1 = sampler.sampleOrDefault(
                baseX + corners[1].stepX() + direction.stepX(),
                baseY + corners[1].stepY() + direction.stepY(),
                baseZ + corners[1].stepZ() + direction.stepZ(),
                LightSample.TRANSLUCENT_AIR
        ).aoTranslucent();
        boolean translucent2 = sampler.sampleOrDefault(
                baseX + corners[2].stepX() + direction.stepX(),
                baseY + corners[2].stepY() + direction.stepY(),
                baseZ + corners[2].stepZ() + direction.stepZ(),
                LightSample.TRANSLUCENT_AIR
        ).aoTranslucent();
        boolean translucent3 = sampler.sampleOrDefault(
                baseX + corners[3].stepX() + direction.stepX(),
                baseY + corners[3].stepY() + direction.stepY(),
                baseZ + corners[3].stepZ() + direction.stepZ(),
                LightSample.TRANSLUCENT_AIR
        ).aoTranslucent();

        LightSample corner02 = (!translucent2 && !translucent0)
                ? side0
                : sampler.sampleOrDefault(
                baseX + corners[0].stepX() + corners[2].stepX(),
                baseY + corners[0].stepY() + corners[2].stepY(),
                baseZ + corners[0].stepZ() + corners[2].stepZ(),
                source
        );
        LightSample corner03 = (!translucent3 && !translucent0)
                ? side0
                : sampler.sampleOrDefault(
                baseX + corners[0].stepX() + corners[3].stepX(),
                baseY + corners[0].stepY() + corners[3].stepY(),
                baseZ + corners[0].stepZ() + corners[3].stepZ(),
                source
        );
        LightSample corner12 = (!translucent2 && !translucent1)
                ? side0
                : sampler.sampleOrDefault(
                baseX + corners[1].stepX() + corners[2].stepX(),
                baseY + corners[1].stepY() + corners[2].stepY(),
                baseZ + corners[1].stepZ() + corners[2].stepZ(),
                source
        );
        LightSample corner13 = (!translucent3 && !translucent1)
                ? side0
                : sampler.sampleOrDefault(
                baseX + corners[1].stepX() + corners[3].stepX(),
                baseY + corners[1].stepY() + corners[3].stepY(),
                baseZ + corners[1].stepZ() + corners[3].stepZ(),
                source
        );

        float shadeScale = applyDirectionalShade ? cardinalShade(direction) : 1.0F;
        int[] out = new int[VERTICES_PER_QUAD];
        int[] remap = remap(direction);
        out[remap[0]] = packVertexFromSmooth(
                smoothBlend(side3.lightCoords(), side0.lightCoords(), corner03.lightCoords(), center.lightCoords()),
                averageShade(side3, side0, corner03, center) * shadeScale
        );
        out[remap[1]] = packVertexFromSmooth(
                smoothBlend(side2.lightCoords(), side0.lightCoords(), corner02.lightCoords(), center.lightCoords()),
                averageShade(side2, side0, corner02, center) * shadeScale
        );
        out[remap[2]] = packVertexFromSmooth(
                smoothBlend(side2.lightCoords(), side1.lightCoords(), corner12.lightCoords(), center.lightCoords()),
                averageShade(side2, side1, corner12, center) * shadeScale
        );
        out[remap[3]] = packVertexFromSmooth(
                smoothBlend(side3.lightCoords(), side1.lightCoords(), corner13.lightCoords(), center.lightCoords()),
                averageShade(side3, side1, corner13, center) * shadeScale
        );
        return out;
    }

    /**
     * Allocation-free production variant of {@link #fullCubeFace}.
     *
     * <p>The object-based API remains the readable reference contract. Section
     * workers use this packed form because one 32-chunk bootstrap evaluates
     * millions of neighboring light samples; allocating a record for every
     * sample made lighting the second-largest JFR allocation site.</p>
     */
    static void fullCubeFacePacked(
            FaceDirection direction,
            int x,
            int y,
            int z,
            PackedLightSampler sampler,
            boolean applyDirectionalShade,
            int[] out
    ) {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(sampler, "sampler");
        if (out == null || out.length < VERTICES_PER_QUAD) {
            throw new IllegalArgumentException("packed lighting output must contain four entries");
        }

        long source = sampler.sample(x, y, z);
        if (!packedSampleAvailable(source)) {
            throw new IllegalStateException("source light sample is unavailable");
        }
        int baseX = x + direction.stepX();
        int baseY = y + direction.stepY();
        int baseZ = z + direction.stepZ();
        long center = packedSampleOrDefault(sampler.sample(baseX, baseY, baseZ), source);

        FaceDirection corner0 = corner(direction, 0);
        FaceDirection corner1 = corner(direction, 1);
        FaceDirection corner2 = corner(direction, 2);
        FaceDirection corner3 = corner(direction, 3);
        long side0 = packedSampleOrDefault(sampler.sample(
                baseX + corner0.stepX(), baseY + corner0.stepY(), baseZ + corner0.stepZ()), source);
        long side1 = packedSampleOrDefault(sampler.sample(
                baseX + corner1.stepX(), baseY + corner1.stepY(), baseZ + corner1.stepZ()), source);
        long side2 = packedSampleOrDefault(sampler.sample(
                baseX + corner2.stepX(), baseY + corner2.stepY(), baseZ + corner2.stepZ()), source);
        long side3 = packedSampleOrDefault(sampler.sample(
                baseX + corner3.stepX(), baseY + corner3.stepY(), baseZ + corner3.stepZ()), source);

        long translucentAir = packLightSample(0, 0, 255, SectionVoxelSnapshot.FLAG_AIR
                | SectionVoxelSnapshot.FLAG_AO_TRANSLUCENT);
        boolean translucent0 = packedAoTranslucent(packedSampleOrDefault(sampler.sample(
                baseX + corner0.stepX() + direction.stepX(),
                baseY + corner0.stepY() + direction.stepY(),
                baseZ + corner0.stepZ() + direction.stepZ()), translucentAir));
        boolean translucent1 = packedAoTranslucent(packedSampleOrDefault(sampler.sample(
                baseX + corner1.stepX() + direction.stepX(),
                baseY + corner1.stepY() + direction.stepY(),
                baseZ + corner1.stepZ() + direction.stepZ()), translucentAir));
        boolean translucent2 = packedAoTranslucent(packedSampleOrDefault(sampler.sample(
                baseX + corner2.stepX() + direction.stepX(),
                baseY + corner2.stepY() + direction.stepY(),
                baseZ + corner2.stepZ() + direction.stepZ()), translucentAir));
        boolean translucent3 = packedAoTranslucent(packedSampleOrDefault(sampler.sample(
                baseX + corner3.stepX() + direction.stepX(),
                baseY + corner3.stepY() + direction.stepY(),
                baseZ + corner3.stepZ() + direction.stepZ()), translucentAir));

        long corner02 = (!translucent2 && !translucent0) ? side0 : packedSampleOrDefault(sampler.sample(
                baseX + corner0.stepX() + corner2.stepX(),
                baseY + corner0.stepY() + corner2.stepY(),
                baseZ + corner0.stepZ() + corner2.stepZ()), source);
        long corner03 = (!translucent3 && !translucent0) ? side0 : packedSampleOrDefault(sampler.sample(
                baseX + corner0.stepX() + corner3.stepX(),
                baseY + corner0.stepY() + corner3.stepY(),
                baseZ + corner0.stepZ() + corner3.stepZ()), source);
        long corner12 = (!translucent2 && !translucent1) ? side0 : packedSampleOrDefault(sampler.sample(
                baseX + corner1.stepX() + corner2.stepX(),
                baseY + corner1.stepY() + corner2.stepY(),
                baseZ + corner1.stepZ() + corner2.stepZ()), source);
        long corner13 = (!translucent3 && !translucent1) ? side0 : packedSampleOrDefault(sampler.sample(
                baseX + corner1.stepX() + corner3.stepX(),
                baseY + corner1.stepY() + corner3.stepY(),
                baseZ + corner1.stepZ() + corner3.stepZ()), source);

        float shadeScale = applyDirectionalShade ? cardinalShade(direction) : 1.0F;
        int[] remap = remap(direction);
        out[remap[0]] = packVertexFromSmooth(
                smoothBlend(packedLightCoords(side3), packedLightCoords(side0), packedLightCoords(corner03), packedLightCoords(center)),
                packedAverageShade(side3, side0, corner03, center) * shadeScale);
        out[remap[1]] = packVertexFromSmooth(
                smoothBlend(packedLightCoords(side2), packedLightCoords(side0), packedLightCoords(corner02), packedLightCoords(center)),
                packedAverageShade(side2, side0, corner02, center) * shadeScale);
        out[remap[2]] = packVertexFromSmooth(
                smoothBlend(packedLightCoords(side2), packedLightCoords(side1), packedLightCoords(corner12), packedLightCoords(center)),
                packedAverageShade(side2, side1, corner12, center) * shadeScale);
        out[remap[3]] = packVertexFromSmooth(
                smoothBlend(packedLightCoords(side3), packedLightCoords(side1), packedLightCoords(corner13), packedLightCoords(center)),
                packedAverageShade(side3, side1, corner13, center) * shadeScale);
    }

    static long packLightSample(int compactLight, int lightEmission, int shadeByte, int materialFlags) {
        return 1L << 32
                | (compactLight & 0xFFL)
                | ((long) clampByte(Math.min(15, Math.max(0, lightEmission))) << 8)
                | ((long) clampByte(shadeByte) << 16)
                | ((long) (materialFlags & 0xFF) << 24);
    }

    private static long packedSampleOrDefault(long sample, long fallback) {
        return packedSampleAvailable(sample) ? sample : fallback;
    }

    private static boolean packedSampleAvailable(long sample) {
        return (sample & (1L << 32)) != 0L;
    }

    private static int packedLightCoords(long sample) {
        return lightCoordsFromCompactLight((int) sample & 0xFF, (int) (sample >>> 8) & 0xFF);
    }

    private static float packedAverageShade(long a, long b, long c, long d) {
        /*
         * Keep the packed hot path bit-identical to LightSample.shade() and
         * averageShade(). Reassociating this as an integer sum followed by one
         * division is mathematically equivalent, but can round one ULP higher;
         * the source contract's final float-to-int truncation then exposes a one-byte seam.
         */
        return (((a >>> 16) & 0xFFL) / 255.0F
                + ((b >>> 16) & 0xFFL) / 255.0F
                + ((c >>> 16) & 0xFFL) / 255.0F
                + ((d >>> 16) & 0xFFL) / 255.0F) * 0.25F;
    }

    private static boolean packedAoTranslucent(long sample) {
        return (((int) (sample >>> 24)) & SectionVoxelSnapshot.FLAG_AO_TRANSLUCENT) != 0;
    }

    private static FaceDirection corner(FaceDirection direction, int index) {
        return switch (direction) {
            case NEGATIVE_Y -> switch (index) {
                case 0 -> FaceDirection.NEGATIVE_X;
                case 1 -> FaceDirection.POSITIVE_X;
                case 2 -> FaceDirection.NEGATIVE_Z;
                default -> FaceDirection.POSITIVE_Z;
            };
            case POSITIVE_Y -> switch (index) {
                case 0 -> FaceDirection.POSITIVE_X;
                case 1 -> FaceDirection.NEGATIVE_X;
                case 2 -> FaceDirection.NEGATIVE_Z;
                default -> FaceDirection.POSITIVE_Z;
            };
            case NEGATIVE_Z -> switch (index) {
                case 0 -> FaceDirection.POSITIVE_Y;
                case 1 -> FaceDirection.NEGATIVE_Y;
                case 2 -> FaceDirection.POSITIVE_X;
                default -> FaceDirection.NEGATIVE_X;
            };
            case POSITIVE_Z -> switch (index) {
                case 0 -> FaceDirection.NEGATIVE_X;
                case 1 -> FaceDirection.POSITIVE_X;
                case 2 -> FaceDirection.NEGATIVE_Y;
                default -> FaceDirection.POSITIVE_Y;
            };
            case NEGATIVE_X -> switch (index) {
                case 0 -> FaceDirection.POSITIVE_Y;
                case 1 -> FaceDirection.NEGATIVE_Y;
                case 2 -> FaceDirection.NEGATIVE_Z;
                default -> FaceDirection.POSITIVE_Z;
            };
            case POSITIVE_X -> switch (index) {
                case 0 -> FaceDirection.NEGATIVE_Y;
                case 1 -> FaceDirection.POSITIVE_Y;
                case 2 -> FaceDirection.NEGATIVE_Z;
                default -> FaceDirection.POSITIVE_Z;
            };
        };
    }

    static int lightCoordsFromCompactLight(int compactLight, int lightEmission) {
        int sky = (compactLight >>> 4) & 0x0F;
        int block = Math.max(compactLight & 0x0F, Math.max(0, Math.min(15, lightEmission)));
        return (block << 4) | (sky << 20);
    }

    static int smoothBlend(int neighbor1, int neighbor2, int neighbor3, int center) {
        if (lightCoordsSky(center) > 2 || lightCoordsBlock(center) > 2) {
            neighbor1 = repairSmoothNeighbor(neighbor1, center);
            neighbor2 = repairSmoothNeighbor(neighbor2, center);
            neighbor3 = repairSmoothNeighbor(neighbor3, center);
        }
        return ((neighbor1 + neighbor2 + neighbor3 + center) >>> 2) & 0x00FF_00FF;
    }

    private static int repairSmoothNeighbor(int neighbor, int center) {
        if (neighbor == 0) {
            return center;
        }
        if (lightCoordsSky(neighbor) == 0) {
            return neighbor | (center & 0x00FF_0000);
        }
        return neighbor;
    }

    private static int packVertexFromSmooth(int smoothLight, float shade) {
        return packVertex(smoothLight & 0xFF, (smoothLight >>> 16) & 0xFF, shade);
    }

    private static int lightCoordsBlock(int packed) {
        return (packed >>> 4) & 0x0F;
    }

    private static int lightCoordsSky(int packed) {
        return (packed >>> 20) & 0x0F;
    }

    private static float averageShade(LightSample a, LightSample b, LightSample c, LightSample d) {
        return (a.shade() + b.shade() + c.shade() + d.shade()) * 0.25F;
    }

    private static FaceDirection[] corners(FaceDirection direction) {
        return switch (direction) {
            case NEGATIVE_Y -> new FaceDirection[]{
                    FaceDirection.NEGATIVE_X,
                    FaceDirection.POSITIVE_X,
                    FaceDirection.NEGATIVE_Z,
                    FaceDirection.POSITIVE_Z
            };
            case POSITIVE_Y -> new FaceDirection[]{
                    FaceDirection.POSITIVE_X,
                    FaceDirection.NEGATIVE_X,
                    FaceDirection.NEGATIVE_Z,
                    FaceDirection.POSITIVE_Z
            };
            case NEGATIVE_Z -> new FaceDirection[]{
                    FaceDirection.POSITIVE_Y,
                    FaceDirection.NEGATIVE_Y,
                    FaceDirection.POSITIVE_X,
                    FaceDirection.NEGATIVE_X
            };
            case POSITIVE_Z -> new FaceDirection[]{
                    FaceDirection.NEGATIVE_X,
                    FaceDirection.POSITIVE_X,
                    FaceDirection.NEGATIVE_Y,
                    FaceDirection.POSITIVE_Y
            };
            case NEGATIVE_X -> new FaceDirection[]{
                    FaceDirection.POSITIVE_Y,
                    FaceDirection.NEGATIVE_Y,
                    FaceDirection.NEGATIVE_Z,
                    FaceDirection.POSITIVE_Z
            };
            case POSITIVE_X -> new FaceDirection[]{
                    FaceDirection.NEGATIVE_Y,
                    FaceDirection.POSITIVE_Y,
                    FaceDirection.NEGATIVE_Z,
                    FaceDirection.POSITIVE_Z
            };
        };
    }

    private static int[] remap(FaceDirection direction) {
        return switch (direction) {
            case NEGATIVE_Y -> REMAP_DOWN;
            case POSITIVE_Y -> REMAP_UP;
            case NEGATIVE_Z -> REMAP_NORTH;
            case POSITIVE_Z -> REMAP_SOUTH;
            case NEGATIVE_X -> REMAP_WEST;
            case POSITIVE_X -> REMAP_EAST;
        };
    }

    private static int clampSmoothLight(int value) {
        return Math.max(0, Math.min(SMOOTH_LIGHT_MAX, value));
    }

    static int clampByte(int value) {
        return Math.max(0, Math.min(255, value));
    }

    static float clamp01(float value) {
        if (!Float.isFinite(value)) {
            return 0.0F;
        }
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    /**
     * Samples immutable voxel-light inputs by integer coordinate.
     */
    @FunctionalInterface
    public interface LightSampler {
        /**
         * Samples one coordinate.
         *
         * @param x voxel X coordinate
         * @param y voxel Y coordinate
         * @param z voxel Z coordinate
         * @return light sample, or {@code null} outside known coverage
         */
        LightSample sample(int x, int y, int z);

        /**
         * Samples one coordinate and substitutes a caller-provided fallback when unavailable.
         *
         * @param x        voxel X coordinate
         * @param y        voxel Y coordinate
         * @param z        voxel Z coordinate
         * @param fallback value returned when the coordinate is unavailable
         * @return sampled or fallback value
         */
        default LightSample sampleOrDefault(int x, int y, int z, LightSample fallback) {
            LightSample sample = sample(x, y, z);
            return sample == null ? fallback : sample;
        }
    }

    @FunctionalInterface
    interface PackedLightSampler {
        /**
         * Returns zero when the requested sample is outside known coverage.
         */
        long sample(int x, int y, int z);
    }

    /**
     * Immutable source voxel-light sample.
     *
     * @param compactLight  packed block and sky light nibbles
     * @param lightEmission material emission level
     * @param shadeByte     unsigned ambient shade byte
     * @param materialFlags packed material classification flags
     */
    public record LightSample(int compactLight, int lightEmission, int shadeByte, int materialFlags) {
        /**
         * Shared transparent-air fallback for samples outside known coverage.
         */
        public static final LightSample TRANSLUCENT_AIR = new LightSample(0, 0, 255, SectionVoxelSnapshot.FLAG_AIR
                | SectionVoxelSnapshot.FLAG_AO_TRANSLUCENT);

        /**
         * Clamps every packed component to its ABI range.
         *
         * @param compactLight  packed block and sky light nibbles
         * @param lightEmission material emission level
         * @param shadeByte     unsigned ambient shade byte
         * @param materialFlags packed material classification flags
         */
        public LightSample {
            compactLight &= 0xFF;
            lightEmission = Math.max(0, Math.min(15, lightEmission));
            shadeByte = clampByte(shadeByte);
            materialFlags &= 0xFF;
        }

        /**
         * Expands compact light into interpolatable GPU light coordinates.
         *
         * @return packed expanded light coordinates
         */
        public int lightCoords() {
            return lightCoordsFromCompactLight(compactLight, lightEmission);
        }

        /**
         * Returns the normalized ambient shade.
         *
         * @return shade in the range {@code [0, 1]}
         */
        public float shade() {
            return shadeByte / 255.0F;
        }

        /**
         * Tests whether this sample permits ambient-occlusion light propagation.
         *
         * @return {@code true} when marked AO-translucent
         */
        public boolean aoTranslucent() {
            return (materialFlags & SectionVoxelSnapshot.FLAG_AO_TRANSLUCENT) != 0;
        }
    }
}
