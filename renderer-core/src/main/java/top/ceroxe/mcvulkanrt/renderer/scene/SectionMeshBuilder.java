package top.ceroxe.mcvulkanrt.renderer.scene;

import top.ceroxe.mcvulkanrt.renderer.rt.material.RtTextureCatalog;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntFunction;

public final class SectionMeshBuilder {
    private static final int COMPONENTS_PER_VERTEX = 3;
    private static final int VERTICES_PER_FACE = 4;
    private static final int INDICES_PER_FACE = 6;
    /** Source-renderer pre-quantization anti-z-fighting offset. */
    private static final float FLUID_SURFACE_EPSILON = 0.001F;
    private static final FaceDirection[] DIRECTIONS = FaceDirection.values();
    private static final ThreadLocal<EmissionStaging> EMISSION_STAGING =
            ThreadLocal.withInitial(() -> new EmissionStaging(1));

    private final PositionedModelResolver modelResolver;
    private final PositionedFaceTextureResolver faceTextureResolver;
    private final FluidTextureResolver fluidTextureResolver;

    public SectionMeshBuilder() {
        this(
                (PositionedModelResolver) RtTextureCatalog::resolveModelQuads,
                (PositionedFaceTextureResolver) RtTextureCatalog::resolveFaceTexture,
                RtTextureCatalog::resolveFluidTexture
        );
    }

    SectionMeshBuilder(
            IntFunction<RtTextureCatalog.ModelQuads> modelResolver,
            FaceTextureResolver faceTextureResolver
    ) {
        this(modelResolver, faceTextureResolver, (voxelTypeId, direction, ignoredFluidAmount, ignoredFlowingSurface, ignoredOverlaySide) ->
                faceTextureResolver.resolve(voxelTypeId, direction));
    }

    SectionMeshBuilder(
            IntFunction<RtTextureCatalog.ModelQuads> modelResolver,
            FaceTextureResolver faceTextureResolver,
            FluidTextureResolver fluidTextureResolver
    ) {
        this(
                (voxelTypeId, ignoredX, ignoredY, ignoredZ) ->
                        Objects.requireNonNull(modelResolver, "modelResolver").apply(voxelTypeId),
                (voxelTypeId, direction, ignoredX, ignoredY, ignoredZ) ->
                        Objects.requireNonNull(faceTextureResolver, "faceTextureResolver")
                                .resolve(voxelTypeId, direction),
                fluidTextureResolver
        );
    }

    private SectionMeshBuilder(
            PositionedModelResolver modelResolver,
            PositionedFaceTextureResolver faceTextureResolver,
            FluidTextureResolver fluidTextureResolver
    ) {
        this.modelResolver = Objects.requireNonNull(modelResolver, "modelResolver");
        this.faceTextureResolver = Objects.requireNonNull(faceTextureResolver, "faceTextureResolver");
        this.fluidTextureResolver = Objects.requireNonNull(fluidTextureResolver, "fluidTextureResolver");
    }

    public SectionTriangleMesh build(SectionGeometrySnapshot geometry) {
        return build(SectionFaceStaging.fromSnapshot(geometry));
    }

    SectionTriangleMesh build(SectionFaceStaging staging) {
        Objects.requireNonNull(staging, "staging");

        EmissionStaging emissions = collectQuadEmissions(staging);
        int faceCount = emissions.count();
        short[] positions = new short[faceCount * VERTICES_PER_FACE * COMPONENTS_PER_VERTEX];
        int[] indices = new int[faceCount * INDICES_PER_FACE];
        int[] faceVoxelStateIds = new int[faceCount];
        byte[] faceFluidAmounts = new byte[faceCount];
        byte[] faceDirections = new byte[faceCount];
        int[] faceMapColors = new int[faceCount];
        int[] faceVertexLighting0 = new int[faceCount];
        int[] faceVertexLighting1 = new int[faceCount];
        int[] faceVertexLighting2 = new int[faceCount];
        int[] faceVertexLighting3 = new int[faceCount];
        byte[] faceLightEmissions = new byte[faceCount];
        byte[] faceMaterialFlags = new byte[faceCount];
        int[] faceTextureIds = new int[faceCount];
        int[] faceUv0 = new int[faceCount];
        int[] faceUv1 = new int[faceCount];
        int[] faceUv2 = new int[faceCount];
        int[] faceUv3 = new int[faceCount];
        byte[] faceTintFlags = new byte[faceCount];
        byte[] faceAlphaCutoutFlags = new byte[faceCount];
        byte[] faceRenderLayers = new byte[faceCount];
        short[] faceSourceBlockIndices = new short[faceCount];
        int[] faceTintIndices = new int[faceCount];
        int[] faceFluidTypeIds = new int[faceCount];
        short[] faceFluidFlowX = new short[faceCount];
        short[] faceFluidFlowZ = new short[faceCount];
        byte[] faceFluidOverlayFlags = new byte[faceCount];
        short[] faceFluidHeight0 = new short[faceCount];
        short[] faceFluidHeight1 = new short[faceCount];
        short[] faceFluidHeight2 = new short[faceCount];
        short[] faceFluidHeight3 = new short[faceCount];

        for (int faceIndex = 0; faceIndex < emissions.count(); faceIndex++) {
            int sourceFaceIndex = emissions.sourceFaceIndex(faceIndex);
            FaceDirection direction = emissions.direction(faceIndex);
            RtTextureCatalog.FaceTexture texture = emissions.texture(faceIndex);
            int vertexBase = faceIndex * VERTICES_PER_FACE;
            int positionBase = vertexBase * COMPONENTS_PER_VERTEX;
            writeEmissionVertices(
                    staging,
                    sourceFaceIndex,
                    emissions.modelQuad(faceIndex),
                    emissions.fluidFaceMask(staging, sourceFaceIndex),
                    positions,
                    positionBase
            );

            int indexBase = faceIndex * INDICES_PER_FACE;
            indices[indexBase] = vertexBase;
            indices[indexBase + 1] = vertexBase + 1;
            indices[indexBase + 2] = vertexBase + 2;
            indices[indexBase + 3] = vertexBase;
            indices[indexBase + 4] = vertexBase + 2;
            indices[indexBase + 5] = vertexBase + 3;

            faceVoxelStateIds[faceIndex] = staging.voxelTypeId(sourceFaceIndex);
            faceFluidAmounts[faceIndex] = (byte) staging.mediumAmount(sourceFaceIndex);
            faceDirections[faceIndex] = (byte) direction.ordinal();
            int tintColor = tintColorForTexture(staging, sourceFaceIndex, texture);
            boolean applyTint = texture.tinted() && tintColor != SectionVoxelSnapshot.NO_BLOCK_TINT_LAYER_COLOR;
            faceMapColors[faceIndex] = applyTint
                    ? SectionVoxelSnapshot.replaceMapColorRgb(staging.mapColor(sourceFaceIndex), tintColor)
                    : staging.mapColor(sourceFaceIndex);
            faceVertexLighting0[faceIndex] = faceVertexLighting(staging.vertexLight(sourceFaceIndex, 0), direction, texture);
            faceVertexLighting1[faceIndex] = faceVertexLighting(staging.vertexLight(sourceFaceIndex, 1), direction, texture);
            faceVertexLighting2[faceIndex] = faceVertexLighting(staging.vertexLight(sourceFaceIndex, 2), direction, texture);
            faceVertexLighting3[faceIndex] = faceVertexLighting(staging.vertexLight(sourceFaceIndex, 3), direction, texture);
            faceLightEmissions[faceIndex] = (byte) staging.lightEmission(sourceFaceIndex);
            faceMaterialFlags[faceIndex] = (byte) staging.materialFlags(sourceFaceIndex);
            faceTextureIds[faceIndex] = texture.textureId();
            RtTextureCatalog.FaceTexture uvTexture = fluidUvTexture(
                    staging, sourceFaceIndex, texture, emissions.fluidFaceMask(staging, sourceFaceIndex));
            faceUv0[faceIndex] = uvTexture.uv0();
            faceUv1[faceIndex] = uvTexture.uv1();
            faceUv2[faceIndex] = uvTexture.uv2();
            faceUv3[faceIndex] = uvTexture.uv3();
            faceTintFlags[faceIndex] = (byte) (applyTint ? 1 : 0);
            faceAlphaCutoutFlags[faceIndex] = (byte) (texture.alphaCutout() ? 1 : 0);
            faceRenderLayers[faceIndex] = (byte) texture.renderLayer();
            faceSourceBlockIndices[faceIndex] = (short) SectionVoxelSnapshot.localBlockIndex(
                    staging.x(sourceFaceIndex), staging.y(sourceFaceIndex), staging.z(sourceFaceIndex)
            );
            faceTintIndices[faceIndex] = texture.tinted()
                    ? texture.tintIndex()
                    : SectionTriangleMesh.NO_TINT_INDEX;
            boolean fluid = (staging.materialFlags(sourceFaceIndex) & SectionVoxelSnapshot.FLAG_LIQUID) != 0
                    && staging.mediumAmount(sourceFaceIndex) > 0;
            faceFluidTypeIds[faceIndex] = fluid
                    ? staging.mediumTypeId(sourceFaceIndex)
                    : SectionTriangleMesh.UNKNOWN_FLUID_TYPE_ID;
            faceFluidFlowX[faceIndex] = fluid
                    ? (short) staging.fluidFlowX(sourceFaceIndex)
                    : SectionTriangleMesh.UNKNOWN_FLUID_SEMANTIC;
            faceFluidFlowZ[faceIndex] = fluid
                    ? (short) staging.fluidFlowZ(sourceFaceIndex)
                    : SectionTriangleMesh.UNKNOWN_FLUID_SEMANTIC;
            faceFluidOverlayFlags[faceIndex] = fluid
                    ? (byte) (staging.fluidOverlay(sourceFaceIndex) ? 1 : 0)
                    : SectionTriangleMesh.UNKNOWN_FLUID_OVERLAY;
            faceFluidHeight0[faceIndex] = fluid
                    ? (short) staging.fluidHeight(sourceFaceIndex, 0)
                    : SectionTriangleMesh.UNKNOWN_FLUID_SEMANTIC;
            faceFluidHeight1[faceIndex] = fluid
                    ? (short) staging.fluidHeight(sourceFaceIndex, 1)
                    : SectionTriangleMesh.UNKNOWN_FLUID_SEMANTIC;
            faceFluidHeight2[faceIndex] = fluid
                    ? (short) staging.fluidHeight(sourceFaceIndex, 2)
                    : SectionTriangleMesh.UNKNOWN_FLUID_SEMANTIC;
            faceFluidHeight3[faceIndex] = fluid
                    ? (short) staging.fluidHeight(sourceFaceIndex, 3)
                    : SectionTriangleMesh.UNKNOWN_FLUID_SEMANTIC;
        }

        SectionTriangleMesh mesh = SectionTriangleMesh.fromOwnedArrays(
                staging.key(),
                positions,
                indices,
                faceVoxelStateIds,
                faceFluidAmounts,
                faceDirections,
                faceMapColors,
                faceVertexLighting0,
                faceVertexLighting1,
                faceVertexLighting2,
                faceVertexLighting3,
                faceLightEmissions,
                faceMaterialFlags,
                faceTextureIds,
                faceUv0,
                faceUv1,
                faceUv2,
                faceUv3,
                faceTintFlags,
                faceAlphaCutoutFlags,
                faceRenderLayers,
                faceSourceBlockIndices,
                faceTintIndices,
                faceFluidTypeIds,
                faceFluidFlowX,
                faceFluidFlowZ,
                faceFluidOverlayFlags,
                faceFluidHeight0,
                faceFluidHeight1,
                faceFluidHeight2,
                faceFluidHeight3
        );
        mesh.publishWorkerMaterial();
        return mesh;
    }

    private EmissionStaging collectQuadEmissions(SectionFaceStaging staging) {
        SectionKey sectionKey = staging.key();
        SectionModelFacts modelFacts = staging.modelFacts();
        EmissionStaging emissions = EMISSION_STAGING.get();
        emissions.reset(staging.faceCount());
        Map<Long, RtTextureCatalog.FaceTexture> fluidTextureCache = emissions.fluidTextureCache();
        Map<Integer, RtTextureCatalog.ModelQuads> modelCache = modelFacts.complete()
                ? Map.of()
                : emissions.modelCache();
        Map<Integer, ModelEmission> modelBlocks = emissions.modelBlocks();
        int previousBlockIndex = -1;
        RtTextureCatalog.ModelQuads previousModel = null;

        for (int sourceFaceIndex = 0; sourceFaceIndex < staging.faceCount(); sourceFaceIndex++) {
            int stagedFaceIndex = sourceFaceIndex;
            if ((staging.materialFlags(sourceFaceIndex) & SectionVoxelSnapshot.FLAG_LIQUID) != 0
                    && staging.mediumAmount(sourceFaceIndex) > 0) {
                emissions.markFluidFace(staging, sourceFaceIndex);
                boolean flowingSurface = isFlowingFluidSurface(staging, sourceFaceIndex);
                RtTextureCatalog.FaceTexture texture = fluidTextureCache.computeIfAbsent(
                        textureCacheKey(staging.voxelTypeId(sourceFaceIndex), staging.direction(sourceFaceIndex),
                                staging.mediumAmount(sourceFaceIndex), flowingSurface, staging.fluidOverlay(sourceFaceIndex)),
                        ignored -> fluidTextureResolver.resolve(
                                staging.voxelTypeId(stagedFaceIndex),
                                staging.direction(stagedFaceIndex),
                                staging.mediumAmount(stagedFaceIndex),
                                flowingSurface,
                                staging.fluidOverlay(stagedFaceIndex)
                        )
                );
                emissions.appendGenerated(sourceFaceIndex, staging.direction(sourceFaceIndex), liquidTexture(staging, sourceFaceIndex, texture));
                continue;
            }

            int blockIndex = SectionVoxelSnapshot.localBlockIndex(
                    staging.x(sourceFaceIndex), staging.y(sourceFaceIndex), staging.z(sourceFaceIndex));
            int worldX = (sectionKey.x() << 4) + staging.x(sourceFaceIndex);
            int worldY = (sectionKey.y() << 4) + staging.y(sourceFaceIndex);
            int worldZ = (sectionKey.z() << 4) + staging.z(sourceFaceIndex);
            RtTextureCatalog.ModelQuads model;
            if (blockIndex == previousBlockIndex) {
                model = previousModel;
            } else {
                model = modelFacts.complete()
                        ? Objects.requireNonNull(
                                modelFacts.modelAt(blockIndex),
                                "complete section model facts must cover every block face source"
                        )
                        : modelCache.computeIfAbsent(
                                blockIndex,
                                ignored -> modelResolver.resolve(staging.voxelTypeId(stagedFaceIndex), worldX, worldY, worldZ)
                        );
                previousBlockIndex = blockIndex;
                previousModel = model;
            }
            if (model.usesBakedGeometry()) {
                modelBlocks
                        .computeIfAbsent(blockIndex, ignored -> new ModelEmission(model))
                        .addFace(sourceFaceIndex, staging);
                continue;
            }

            RtTextureCatalog.FaceTexture texture = generatedFaceTexture(
                    model,
                    staging,
                    sourceFaceIndex,
                    worldX,
                    worldY,
                    worldZ
            );
            emissions.appendGenerated(sourceFaceIndex, staging.direction(sourceFaceIndex), texture);
        }
        for (ModelEmission modelBlock : modelBlocks.values()) {
            for (RtTextureCatalog.ModelQuad quad : modelBlock.model().quads()) {
                int sourceFaceIndex = modelBlock.sourceFaceFor(quad, staging);
                if (sourceFaceIndex >= 0) {
                    emissions.appendModel(sourceFaceIndex, quad);
                }
            }
        }
        return emissions;
    }

    private RtTextureCatalog.FaceTexture generatedFaceTexture(
            RtTextureCatalog.ModelQuads model,
            SectionFaceStaging staging,
            int sourceFaceIndex,
            int worldX,
            int worldY,
            int worldZ
    ) {
        for (RtTextureCatalog.ModelQuad quad : model.quads()) {
            if (quad.directionalCull() && quad.direction() == staging.direction(sourceFaceIndex)) {
                return quad.texture();
            }
        }
        for (RtTextureCatalog.ModelQuad quad : model.quads()) {
            if (!quad.directionalCull() && quad.direction() == staging.direction(sourceFaceIndex)) {
                return quad.texture();
            }
        }
        return faceTextureResolver.resolve(
                staging.voxelTypeId(sourceFaceIndex),
                staging.direction(sourceFaceIndex),
                worldX,
                worldY,
                worldZ
        );
    }

    private static long textureCacheKey(int voxelTypeId, FaceDirection direction, int mediumAmount) {
        return ((long) voxelTypeId << 32) ^ ((long) (mediumAmount & 0xFF) << 8) ^ direction.ordinal();
    }

    private static long textureCacheKey(
            int voxelTypeId,
            FaceDirection direction,
            int mediumAmount,
            boolean flowingSurface,
            boolean overlaySide
    ) {
        return textureCacheKey(voxelTypeId, direction, mediumAmount)
                ^ (flowingSurface ? 0x8000_0000_0000_0000L : 0L)
                ^ (overlaySide ? 0x4000_0000_0000_0000L : 0L);
    }

    private static boolean isFlowingFluidSurface(SectionFaceStaging staging, int sourceFaceIndex) {
        FaceDirection direction = staging.direction(sourceFaceIndex);
        return direction != FaceDirection.NEGATIVE_Y
                && (direction != FaceDirection.POSITIVE_Y
                || staging.fluidFlowX(sourceFaceIndex) != 0
                || staging.fluidFlowZ(sourceFaceIndex) != 0);
    }

    private static RtTextureCatalog.FaceTexture liquidTexture(
            SectionFaceStaging staging,
            int sourceFaceIndex,
            RtTextureCatalog.FaceTexture texture
    ) {
        /*
         * host's medium surface renderer is not the normal cube-model path: water-like
         * fluids commonly get their color from world tint/map-color data while the
         * atlas sprite supplies animated surface detail. If the block model lookup
         * falls back to an untinted particle sprite, the RT path shows the raw gray
         * sprite and can look like gravel. Keep emissive fluids such as lava on
         * their authored texture color, but tint non-emissive fluid surfaces with
         * the captured host material color.
         */
        int mapRgb = SectionVoxelSnapshot.mapColorRgb(staging.mapColor(sourceFaceIndex));
        if (texture.tinted() || mapRgb == SectionVoxelSnapshot.NO_MAP_COLOR || staging.lightEmission(sourceFaceIndex) > 0) {
            return texture;
        }
        return new RtTextureCatalog.FaceTexture(
                texture.textureId(),
                texture.uv0(),
                texture.uv1(),
                texture.uv2(),
                texture.uv3(),
                true,
                0,
                texture.alphaCutout(),
                texture.shade(),
                texture.renderLayer(),
                texture.coordinateMapping()
        );
    }

    private static int tintColorForTexture(
            SectionFaceStaging staging,
            int sourceFaceIndex,
            RtTextureCatalog.FaceTexture texture
    ) {
        if (!texture.tinted()) {
            return SectionVoxelSnapshot.NO_BLOCK_TINT_LAYER_COLOR;
        }
        if ((staging.materialFlags(sourceFaceIndex) & SectionVoxelSnapshot.FLAG_LIQUID) != 0
                && staging.mediumAmount(sourceFaceIndex) > 0) {
            int fluidTint = SectionVoxelSnapshot.mapColorRgb(staging.mapColor(sourceFaceIndex));
            return fluidTint == SectionVoxelSnapshot.NO_MAP_COLOR
                    ? SectionVoxelSnapshot.NO_BLOCK_TINT_LAYER_COLOR
                    : fluidTint;
        }
        return switch (texture.tintIndex()) {
            case 0 -> staging.tint(sourceFaceIndex, 0);
            case 1 -> staging.tint(sourceFaceIndex, 1);
            case 2 -> staging.tint(sourceFaceIndex, 2);
            case 3 -> staging.tint(sourceFaceIndex, 3);
            default -> SectionVoxelSnapshot.NO_BLOCK_TINT_LAYER_COLOR;
        };
    }

    private static RtTextureCatalog.FaceTexture fluidUvTexture(
            SectionFaceStaging staging,
            int sourceFaceIndex,
            RtTextureCatalog.FaceTexture texture,
            int fluidFaceMask
    ) {
        if ((staging.materialFlags(sourceFaceIndex) & SectionVoxelSnapshot.FLAG_LIQUID) == 0
                || staging.mediumAmount(sourceFaceIndex) <= 0) {
            return texture;
        }
        if (staging.direction(sourceFaceIndex) == FaceDirection.POSITIVE_Y) {
            return fluidTopUvTexture(staging, sourceFaceIndex, texture);
        }
        if (staging.direction(sourceFaceIndex) == FaceDirection.NEGATIVE_Y) {
            return fluidBottomUvTexture(texture);
        }
        return fluidSideUvTexture(staging, sourceFaceIndex, texture, fluidFaceMask);
    }

    private static RtTextureCatalog.FaceTexture fluidBottomUvTexture(RtTextureCatalog.FaceTexture texture) {
        return new RtTextureCatalog.FaceTexture(
                texture.textureId(),
                texture.coordinateMapping().packLocalUv(0.0F, 0.0F),
                texture.coordinateMapping().packLocalUv(1.0F, 0.0F),
                texture.coordinateMapping().packLocalUv(1.0F, 1.0F),
                texture.coordinateMapping().packLocalUv(0.0F, 1.0F),
                texture.tinted(),
                texture.tintIndex(),
                texture.alphaCutout(),
                texture.shade(),
                texture.renderLayer(),
                texture.coordinateMapping()
        );
    }

    private static RtTextureCatalog.FaceTexture fluidTopUvTexture(
            SectionFaceStaging staging,
            int sourceFaceIndex,
            RtTextureCatalog.FaceTexture texture
    ) {
        if (staging.fluidFlowX(sourceFaceIndex) == 0 && staging.fluidFlowZ(sourceFaceIndex) == 0) {
            return new RtTextureCatalog.FaceTexture(
                    texture.textureId(),
                    texture.coordinateMapping().packLocalUv(0.0F, 1.0F),
                    texture.coordinateMapping().packLocalUv(1.0F, 1.0F),
                    texture.coordinateMapping().packLocalUv(1.0F, 0.0F),
                    texture.coordinateMapping().packLocalUv(0.0F, 0.0F),
                    texture.tinted(),
                    texture.tintIndex(),
                    texture.alphaCutout(),
                    texture.shade(),
                    texture.renderLayer(),
                    texture.coordinateMapping()
            );
        }
        /*
         * sourceEngine medium surface renderer rotates the top flowing sprite from the fluid
         * vector instead of treating every liquid top as a still full-quad UV.
         * The RT mesh stores the resolved UVs per emitted face, so carrying the
         * same orientation here keeps the shader data-oriented and avoids adding
         * a frame-varying branch to closest-hit.
         */
        float flowX = staging.fluidFlowX(sourceFaceIndex) / (float) Byte.MAX_VALUE;
        float flowZ = staging.fluidFlowZ(sourceFaceIndex) / (float) Byte.MAX_VALUE;
        float lengthSquared = flowX * flowX + flowZ * flowZ;
        if (!Float.isFinite(lengthSquared) || lengthSquared <= 1.0e-8F) {
            return texture;
        }
        float angle = (float) Math.atan2(flowZ, flowX) - ((float) Math.PI / 2.0F);
        float sin = (float) Math.sin(angle) * 0.25F;
        float cos = (float) Math.cos(angle) * 0.25F;
        int sourceEngineU00 = texture.coordinateMapping().packLocalUv(0.5F - cos - sin, 0.5F - cos + sin);
        int sourceEngineU01 = texture.coordinateMapping().packLocalUv(0.5F - cos + sin, 0.5F + cos + sin);
        int sourceEngineU10 = texture.coordinateMapping().packLocalUv(0.5F + cos + sin, 0.5F + cos - sin);
        int sourceEngineU11 = texture.coordinateMapping().packLocalUv(0.5F + cos - sin, 0.5F - cos - sin);
        return new RtTextureCatalog.FaceTexture(
                texture.textureId(),
                sourceEngineU01,
                sourceEngineU10,
                sourceEngineU11,
                sourceEngineU00,
                texture.tinted(),
                texture.tintIndex(),
                texture.alphaCutout(),
                texture.shade(),
                texture.renderLayer(),
                texture.coordinateMapping()
            );
        }

    private static RtTextureCatalog.FaceTexture fluidSideUvTexture(
            SectionFaceStaging staging,
            int sourceFaceIndex,
            RtTextureCatalog.FaceTexture texture,
            int fluidFaceMask
    ) {
        float heightOffset = hasFace(fluidFaceMask, FaceDirection.POSITIVE_Y)
                ? FLUID_SURFACE_EPSILON : 0.0F;
        float h2 = fluidHeight(staging.fluidHeight(sourceFaceIndex, 2))
                - heightOffset;
        float h3 = fluidHeight(staging.fluidHeight(sourceFaceIndex, 3))
                - heightOffset;
        return new RtTextureCatalog.FaceTexture(
                texture.textureId(),
                texture.coordinateMapping().packLocalUv(0.5F, 0.5F),
                texture.coordinateMapping().packLocalUv(0.0F, 0.5F),
                texture.coordinateMapping().packLocalUv(0.0F, (1.0F - h2) * 0.5F),
                texture.coordinateMapping().packLocalUv(0.5F, (1.0F - h3) * 0.5F),
                texture.tinted(),
                texture.tintIndex(),
                texture.alphaCutout(),
                texture.shade(),
                texture.renderLayer(),
                texture.coordinateMapping()
        );
    }

    @FunctionalInterface
    interface FaceTextureResolver {
        RtTextureCatalog.FaceTexture resolve(int voxelTypeId, FaceDirection direction);
    }

    @FunctionalInterface
    private interface PositionedModelResolver {
        RtTextureCatalog.ModelQuads resolve(int voxelTypeId, int worldX, int worldY, int worldZ);
    }

    @FunctionalInterface
    private interface PositionedFaceTextureResolver {
        RtTextureCatalog.FaceTexture resolve(
                int voxelTypeId,
                FaceDirection direction,
                int worldX,
                int worldY,
                int worldZ
        );
    }

    @FunctionalInterface
    interface FluidTextureResolver {
        RtTextureCatalog.FaceTexture resolve(
                int voxelTypeId,
                FaceDirection direction,
                int mediumAmount,
                boolean flowingSurface,
                boolean overlaySide
        );
    }

    private static int faceVertexLighting(
            int packedVertexLighting,
            FaceDirection direction,
            RtTextureCatalog.FaceTexture texture
    ) {
        return texture.shade()
                ? packedVertexLighting
                : PackedVoxelLighting.removeCardinalShade(packedVertexLighting, direction);
    }

    private static void writeEmissionVertices(
            SectionFaceStaging staging,
            int sourceFaceIndex,
            RtTextureCatalog.ModelQuad modelQuad,
            int fluidFaceMask,
            short[] positions,
            int offset
    ) {
        if (modelQuad == null) {
            if ((staging.materialFlags(sourceFaceIndex) & SectionVoxelSnapshot.FLAG_LIQUID) != 0
                    && staging.mediumAmount(sourceFaceIndex) > 0) {
                writeFluidFaceVertices(staging, sourceFaceIndex, fluidFaceMask, positions, offset);
                return;
            }
            writeFaceVertices(staging, sourceFaceIndex, positions, offset);
            return;
        }

        float[] localPositions = modelQuad.positions();
        for (int vertex = 0; vertex < VERTICES_PER_FACE; vertex++) {
            int localOffset = vertex * COMPONENTS_PER_VERTEX;
            writeVertex(
                    positions,
                    offset + localOffset,
                    staging.x(sourceFaceIndex) + localPositions[localOffset],
                    staging.y(sourceFaceIndex) + localPositions[localOffset + 1],
                    staging.z(sourceFaceIndex) + localPositions[localOffset + 2]
            );
        }
    }

    private static void writeFaceVertices(
            SectionFaceStaging staging,
            int sourceFaceIndex,
            short[] positions,
            int offset
    ) {
        int x0 = staging.x(sourceFaceIndex);
        int y0 = staging.y(sourceFaceIndex);
        int z0 = staging.z(sourceFaceIndex);
        int x1 = x0 + 1;
        int y1 = y0 + 1;
        int z1 = z0 + 1;

        switch (staging.direction(sourceFaceIndex)) {
            case NEGATIVE_X -> {
                writeVertex(positions, offset, x0, y0, z0);
                writeVertex(positions, offset + 3, x0, y0, z1);
                writeVertex(positions, offset + 6, x0, y1, z1);
                writeVertex(positions, offset + 9, x0, y1, z0);
            }
            case POSITIVE_X -> {
                writeVertex(positions, offset, x1, y0, z1);
                writeVertex(positions, offset + 3, x1, y0, z0);
                writeVertex(positions, offset + 6, x1, y1, z0);
                writeVertex(positions, offset + 9, x1, y1, z1);
            }
            case NEGATIVE_Y -> {
                writeVertex(positions, offset, x0, y0, z0);
                writeVertex(positions, offset + 3, x1, y0, z0);
                writeVertex(positions, offset + 6, x1, y0, z1);
                writeVertex(positions, offset + 9, x0, y0, z1);
            }
            case POSITIVE_Y -> {
                writeVertex(positions, offset, x0, y1, z1);
                writeVertex(positions, offset + 3, x1, y1, z1);
                writeVertex(positions, offset + 6, x1, y1, z0);
                writeVertex(positions, offset + 9, x0, y1, z0);
            }
            case NEGATIVE_Z -> {
                writeVertex(positions, offset, x1, y0, z0);
                writeVertex(positions, offset + 3, x0, y0, z0);
                writeVertex(positions, offset + 6, x0, y1, z0);
                writeVertex(positions, offset + 9, x1, y1, z0);
            }
            case POSITIVE_Z -> {
                writeVertex(positions, offset, x0, y0, z1);
                writeVertex(positions, offset + 3, x1, y0, z1);
                writeVertex(positions, offset + 6, x1, y1, z1);
                writeVertex(positions, offset + 9, x0, y1, z1);
            }
        }
    }

    private static void writeFluidFaceVertices(
            SectionFaceStaging staging,
            int sourceFaceIndex,
            int fluidFaceMask,
            short[] positions,
            int offset
    ) {
        int x0 = staging.x(sourceFaceIndex);
        int y0 = staging.y(sourceFaceIndex);
        int z0 = staging.z(sourceFaceIndex);
        int x1 = x0 + 1;
        int z1 = z0 + 1;
        float bottomOffset = hasFace(fluidFaceMask, FaceDirection.NEGATIVE_Y)
                ? FLUID_SURFACE_EPSILON : 0.0F;
        float topOffset = hasFace(fluidFaceMask, FaceDirection.POSITIVE_Y)
                ? FLUID_SURFACE_EPSILON : 0.0F;
        float yv0 = y0 + fluidHeight(staging.fluidHeight(sourceFaceIndex, 0)) - topOffset;
        float yv1 = y0 + fluidHeight(staging.fluidHeight(sourceFaceIndex, 1)) - topOffset;
        float yv2 = y0 + fluidHeight(staging.fluidHeight(sourceFaceIndex, 2)) - topOffset;
        float yv3 = y0 + fluidHeight(staging.fluidHeight(sourceFaceIndex, 3)) - topOffset;

        switch (staging.direction(sourceFaceIndex)) {
            case NEGATIVE_X -> {
                float x = x0 + FLUID_SURFACE_EPSILON;
                writeVertex(positions, offset, x, y0 + bottomOffset, z0);
                writeVertex(positions, offset + 3, x, y0 + bottomOffset, z1);
                writeVertex(positions, offset + 6, x, yv2, z1);
                writeVertex(positions, offset + 9, x, yv3, z0);
            }
            case POSITIVE_X -> {
                float x = x1 - FLUID_SURFACE_EPSILON;
                writeVertex(positions, offset, x, y0 + bottomOffset, z1);
                writeVertex(positions, offset + 3, x, y0 + bottomOffset, z0);
                writeVertex(positions, offset + 6, x, yv2, z0);
                writeVertex(positions, offset + 9, x, yv3, z1);
            }
            case NEGATIVE_Y -> {
                float y = y0 + bottomOffset;
                writeVertex(positions, offset, x0, y, z0);
                writeVertex(positions, offset + 3, x1, y, z0);
                writeVertex(positions, offset + 6, x1, y, z1);
                writeVertex(positions, offset + 9, x0, y, z1);
            }
            case POSITIVE_Y -> {
                writeVertex(positions, offset, x0, yv0, z1);
                writeVertex(positions, offset + 3, x1, yv1, z1);
                writeVertex(positions, offset + 6, x1, yv2, z0);
                writeVertex(positions, offset + 9, x0, yv3, z0);
            }
            case NEGATIVE_Z -> {
                float z = z0 + FLUID_SURFACE_EPSILON;
                writeVertex(positions, offset, x1, y0 + bottomOffset, z);
                writeVertex(positions, offset + 3, x0, y0 + bottomOffset, z);
                writeVertex(positions, offset + 6, x0, yv2, z);
                writeVertex(positions, offset + 9, x1, yv3, z);
            }
            case POSITIVE_Z -> {
                float z = z1 - FLUID_SURFACE_EPSILON;
                writeVertex(positions, offset, x0, y0 + bottomOffset, z);
                writeVertex(positions, offset + 3, x1, y0 + bottomOffset, z);
                writeVertex(positions, offset + 6, x1, yv2, z);
                writeVertex(positions, offset + 9, x0, yv3, z);
            }
        }
    }

    private static boolean hasFace(int faceMask, FaceDirection direction) {
        return (faceMask & (1 << direction.ordinal())) != 0;
    }

    private static float fluidHeight(int packedHeight) {
        return Math.max(0.0F, Math.min(1.0F, packedHeight / (float) SectionFace.FLUID_HEIGHT_SCALE));
    }

    private static void writeVertex(short[] positions, int offset, int x, int y, int z) {
        writeVertex(positions, offset, (float) x, (float) y, (float) z);
    }

    private static void writeVertex(short[] positions, int offset, float x, float y, float z) {
        positions[offset] = packPosition(x);
        positions[offset + 1] = packPosition(y);
        positions[offset + 2] = packPosition(z);
    }

    private static short packPosition(float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("mesh vertex position must be finite");
        }
        int fixed = Math.round(value * SectionTriangleMesh.POSITION_SCALE);
        if (fixed < Short.MIN_VALUE || fixed > Short.MAX_VALUE) {
            throw new IllegalArgumentException("mesh vertex position exceeds fixed-point range: " + value);
        }
        return (short) fixed;
    }

    private static final class ModelEmission {
        private final RtTextureCatalog.ModelQuads model;
        private final int[] facesByDirection = new int[FaceDirection.values().length];
        private int firstFaceIndex = -1;

        private ModelEmission(RtTextureCatalog.ModelQuads model) {
            this.model = Objects.requireNonNull(model, "model");
            java.util.Arrays.fill(facesByDirection, -1);
        }

        private RtTextureCatalog.ModelQuads model() {
            return model;
        }

        private void addFace(int sourceFaceIndex, SectionFaceStaging staging) {
            if (sourceFaceIndex < 0 || sourceFaceIndex >= staging.faceCount()) {
                throw new IllegalArgumentException("source face index outside staging range");
            }
            if (firstFaceIndex < 0) {
                firstFaceIndex = sourceFaceIndex;
            }
            facesByDirection[staging.direction(sourceFaceIndex).ordinal()] = sourceFaceIndex;
        }

        private int sourceFaceFor(RtTextureCatalog.ModelQuad quad, SectionFaceStaging staging) {
            Objects.requireNonNull(quad, "quad");
            int directionalFaceIndex = facesByDirection[quad.direction().ordinal()];
            if (quad.directionalCull()) {
                return directionalFaceIndex >= 0 && staging.faceVisible(directionalFaceIndex)
                        ? directionalFaceIndex
                        : -1;
            }
            return directionalFaceIndex >= 0 ? directionalFaceIndex : firstFaceIndex;
        }
    }

    /**
     * Builder-private output order. It keeps only scalar source indexes and the catalog objects
     * that already own texture/model data, avoiding one temporary Java object per emitted quad.
     */
    private static final class EmissionStaging {
        private int[] sourceFaceIndices;
        private byte[] directions;
        private RtTextureCatalog.FaceTexture[] textures;
        private RtTextureCatalog.ModelQuad[] modelQuads;
        private final byte[] fluidFaceMasks = new byte[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        private final Map<Long, RtTextureCatalog.FaceTexture> fluidTextureCache = new HashMap<>();
        private final Map<Integer, RtTextureCatalog.ModelQuads> modelCache = new HashMap<>();
        private final Map<Integer, ModelEmission> modelBlocks = new LinkedHashMap<>();
        private int count;

        private EmissionStaging(int expectedEmissions) {
            int capacity = Math.max(1, expectedEmissions);
            sourceFaceIndices = new int[capacity];
            directions = new byte[capacity];
            textures = new RtTextureCatalog.FaceTexture[capacity];
            modelQuads = new RtTextureCatalog.ModelQuad[capacity];
        }

        private int count() {
            return count;
        }

        private void reset(int expectedEmissions) {
            if (expectedEmissions < 0) {
                throw new IllegalArgumentException("expectedEmissions must not be negative");
            }
            ensureCapacity(expectedEmissions);
            /* The arena is retained by a worker thread, not by a mesh publication. */
            java.util.Arrays.fill(textures, 0, count, null);
            java.util.Arrays.fill(modelQuads, 0, count, null);
            fluidTextureCache.clear();
            modelCache.clear();
            modelBlocks.clear();
            java.util.Arrays.fill(fluidFaceMasks, (byte) 0);
            count = 0;
        }

        private Map<Long, RtTextureCatalog.FaceTexture> fluidTextureCache() {
            return fluidTextureCache;
        }

        private Map<Integer, RtTextureCatalog.ModelQuads> modelCache() {
            return modelCache;
        }

        private Map<Integer, ModelEmission> modelBlocks() {
            return modelBlocks;
        }

        private int sourceFaceIndex(int emissionIndex) {
            checkIndex(emissionIndex);
            return sourceFaceIndices[emissionIndex];
        }

        private FaceDirection direction(int emissionIndex) {
            checkIndex(emissionIndex);
            return DIRECTIONS[directions[emissionIndex]];
        }

        private RtTextureCatalog.FaceTexture texture(int emissionIndex) {
            checkIndex(emissionIndex);
            return textures[emissionIndex];
        }

        private RtTextureCatalog.ModelQuad modelQuad(int emissionIndex) {
            checkIndex(emissionIndex);
            return modelQuads[emissionIndex];
        }

        private void markFluidFace(SectionFaceStaging staging, int sourceFaceIndex) {
            int blockIndex = SectionVoxelSnapshot.localBlockIndex(
                    staging.x(sourceFaceIndex), staging.y(sourceFaceIndex), staging.z(sourceFaceIndex));
            fluidFaceMasks[blockIndex] |= (byte) (1 << staging.direction(sourceFaceIndex).ordinal());
        }

        private int fluidFaceMask(SectionFaceStaging staging, int sourceFaceIndex) {
            if ((staging.materialFlags(sourceFaceIndex) & SectionVoxelSnapshot.FLAG_LIQUID) == 0
                    || staging.mediumAmount(sourceFaceIndex) <= 0) {
                return 0;
            }
            int blockIndex = SectionVoxelSnapshot.localBlockIndex(
                    staging.x(sourceFaceIndex), staging.y(sourceFaceIndex), staging.z(sourceFaceIndex));
            return Byte.toUnsignedInt(fluidFaceMasks[blockIndex]);
        }

        private void appendGenerated(int sourceFaceIndex, FaceDirection direction, RtTextureCatalog.FaceTexture texture) {
            append(sourceFaceIndex, direction, texture, null);
        }

        private void appendModel(int sourceFaceIndex, RtTextureCatalog.ModelQuad modelQuad) {
            Objects.requireNonNull(modelQuad, "modelQuad");
            append(sourceFaceIndex, modelQuad.direction(), modelQuad.texture(), modelQuad);
        }

        private void append(
                int sourceFaceIndex,
                FaceDirection direction,
                RtTextureCatalog.FaceTexture texture,
                RtTextureCatalog.ModelQuad modelQuad
        ) {
            if (sourceFaceIndex < 0) {
                throw new IllegalArgumentException("sourceFaceIndex must not be negative");
            }
            ensureCapacity();
            sourceFaceIndices[count] = sourceFaceIndex;
            directions[count] = (byte) Objects.requireNonNull(direction, "direction").ordinal();
            textures[count] = Objects.requireNonNull(texture, "texture");
            modelQuads[count] = modelQuad;
            count++;
        }

        private void ensureCapacity() {
            ensureCapacity(Math.addExact(count, 1));
        }

        private void ensureCapacity(int requiredCapacity) {
            if (requiredCapacity <= sourceFaceIndices.length) {
                return;
            }
            int capacity = sourceFaceIndices.length;
            while (capacity < requiredCapacity) {
                capacity = Math.multiplyExact(capacity, 2);
            }
            sourceFaceIndices = java.util.Arrays.copyOf(sourceFaceIndices, capacity);
            directions = java.util.Arrays.copyOf(directions, capacity);
            textures = java.util.Arrays.copyOf(textures, capacity);
            modelQuads = java.util.Arrays.copyOf(modelQuads, capacity);
        }

        private void checkIndex(int emissionIndex) {
            if (emissionIndex < 0 || emissionIndex >= count) {
                throw new IllegalArgumentException("emission index outside range");
            }
        }
    }
}
