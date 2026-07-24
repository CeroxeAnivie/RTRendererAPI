package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import top.ceroxe.mcvulkanrt.renderer.RtMaterialTelemetrySink;
import top.ceroxe.mcvulkanrt.renderer.DynamicMeshAsset;
import top.ceroxe.mcvulkanrt.renderer.DynamicMeshAssetIdAllocator;
import top.ceroxe.mcvulkanrt.renderer.DynamicMeshInstance;
import top.ceroxe.mcvulkanrt.renderer.DynamicRenderScene;
import top.ceroxe.mcvulkanrt.renderer.scene.FaceDirection;
import top.ceroxe.mcvulkanrt.renderer.rt.material.RtTextureCatalog;
import top.ceroxe.mcvulkanrt.renderer.rt.material.RtBlendMode;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionVoxelSnapshot;
import top.ceroxe.mcvulkanrt.renderer.scene.PackedVoxelLighting;
import top.ceroxe.mcvulkanrt.renderer.rt.material.RtSceneMaterialTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Renderer-owned dynamic triangle payload for the RT scene.
 *
 * <p>This is intentionally not a host object wrapper. It is the first
 * MC-free geometry contract that lets entity/drop-like facts participate in the
 * same BLAS/TLAS/material-table path as terrain. The generated cube impostor is
 * a temporary model source; the ownership and lifetime contract is the important
 * piece that later real sourceEngine model extraction will reuse.</p>
 */
public final class RtDynamicTriangleMesh {
    private static final int COMPONENTS_PER_VERTEX = 3;
    private static final int VERTICES_PER_FACE = 4;
    private static final int INDICES_PER_FACE = 6;
    private static final int INTS_PER_FACE_RECORD = 12;
    private static final int TEXTURE_INFO_TEXTURE_ID_MASK = 0x3FFF_FFFF;
    private static final int TEXTURE_INFO_ALPHA_CUTOUT_BIT = 0x4000_0000;
    private static final int TEXTURE_INFO_TINT_BIT = 0x8000_0000;
    /* Dynamic faces reserve terrain-unused material flag bits 3-4 for sourceEngine foil mode. */
    private static final int DYNAMIC_FOIL_MODE_SHIFT = 3;
    private static final int DYNAMIC_FOIL_MODE_MASK = 0b11 << DYNAMIC_FOIL_MODE_SHIFT;
    private static final int DYNAMIC_FACE_MARKER = 0x0200_0000;
    private static final int DYNAMIC_FACE_MARKER_MASK = 0x0F00_0000;
    private static final int DYNAMIC_BLEND_MODE_SHIFT = 28;
    private static final int DYNAMIC_BLEND_MODE_MASK = 0xF000_0000;
    private static final int DYNAMIC_OUTLINE_ENABLED_BIT = 1 << 1;
    private static final int DYNAMIC_OUTLINE_ONLY_BIT = 1 << 2;
    private static final int DYNAMIC_DECAL_ENABLED_BIT = 1 << 7;
    private static final int DECAL_UV_BITS = 10;
    private static final int DECAL_UV_MASK = (1 << DECAL_UV_BITS) - 1;
    private static final DynamicMeshAsset LEGACY_MODEL_CUBE = createLegacyModelCube();

    private final long assetId;
    private final long revision;
    private final float[] vertexPositions;
    private final int[] indices;
    private final RtSceneMaterialTable.SectionMaterial material;
    private final int primitiveCount;

    private RtDynamicTriangleMesh(
            long assetId,
            long revision,
            float[] vertexPositions,
            int[] indices,
            RtSceneMaterialTable.SectionMaterial material,
            int primitiveCount,
            RtMaterialTelemetrySink materialTelemetry
    ) {
        if (assetId < 0L) {
            throw new IllegalArgumentException("dynamic mesh asset id must not be negative");
        }
        if (revision <= 0L) {
            throw new IllegalArgumentException("dynamic mesh revision must be positive");
        }
        this.assetId = assetId;
        this.revision = revision;
        this.vertexPositions = Objects.requireNonNull(vertexPositions, "vertexPositions").clone();
        this.indices = Objects.requireNonNull(indices, "indices").clone();
        this.material = Objects.requireNonNull(material, "material");
        if (this.vertexPositions.length == 0 || this.vertexPositions.length % COMPONENTS_PER_VERTEX != 0) {
            throw new IllegalArgumentException("dynamic vertex positions must contain XYZ triples");
        }
        if (this.indices.length == 0 || this.indices.length % 3 != 0) {
            throw new IllegalArgumentException("dynamic indices must contain triangle triples");
        }
        int materialPrimitiveCount = Math.multiplyExact(
                material.faceCount(), material.primitivesPerMaterialRecord()
        );
        if (materialPrimitiveCount != this.indices.length / 3) {
            throw new IllegalArgumentException(
                    "dynamic material records do not cover the indexed primitives: records="
                            + material.faceCount()
                            + ", primitivesPerRecord=" + material.primitivesPerMaterialRecord()
                            + ", indexedPrimitives=" + this.indices.length / 3
            );
        }
        int vertexCount = this.vertexPositions.length / COMPONENTS_PER_VERTEX;
        for (int index : this.indices) {
            if (index < 0 || index >= vertexCount) {
                throw new IllegalArgumentException("dynamic triangle index outside vertex buffer: " + index);
            }
        }
        if (primitiveCount <= 0) {
            throw new IllegalArgumentException("dynamic mesh primitive count must be positive");
        }
        this.primitiveCount = primitiveCount;
        Objects.requireNonNull(materialTelemetry, "materialTelemetry").dynamicMeshBuilt(
                assetId == 0L ? "sceneBatch" : "asset",
                assetId,
                revision,
                primitiveCount,
                vertexCount,
                this.indices.length / 3,
                material.faceCount(),
                (long) this.vertexPositions.length * Float.BYTES
                        + (long) this.indices.length * Integer.BYTES
                        + (long) material.faceCount() * INTS_PER_FACE_RECORD * Integer.BYTES
        );
    }

    public static RtDynamicTriangleMesh fromScene(DynamicRenderScene scene) {
        return fromScene(scene, RtMaterialTelemetrySink.NOOP);
    }

    static RtDynamicTriangleMesh fromScene(
            DynamicRenderScene scene,
            RtMaterialTelemetrySink materialTelemetry
    ) {
        Objects.requireNonNull(scene, "scene");
        if (scene.revision() <= 0L) {
            throw new IllegalArgumentException("dynamic scene geometry requires a positive revision");
        }
        int tlasPrimitiveCount = 0;
        for (DynamicRenderScene.DynamicPrimitive primitive : scene.primitives()) {
            if (primitive.usesTlasGeometry() && primitive.meshInstance() == null) {
                tlasPrimitiveCount++;
            }
        }
        if (tlasPrimitiveCount == 0) {
            return null;
        }

        MeshBuilder builder = new MeshBuilder(
                scene.revision(),
                tlasPrimitiveCount,
                materialTelemetry
        );
        for (DynamicRenderScene.DynamicPrimitive primitive : scene.primitives()) {
            if (primitive.usesTlasGeometry() && primitive.meshInstance() == null) {
                builder.addPrimitive(primitive);
            }
        }
        return builder.build();
    }

    static RtDynamicTriangleMesh fromAsset(DynamicMeshAsset asset) {
        return fromAsset(asset, RtMaterialTelemetrySink.NOOP);
    }

    static RtDynamicTriangleMesh fromAsset(
            DynamicMeshAsset asset,
            RtMaterialTelemetrySink materialTelemetry
    ) {
        Objects.requireNonNull(asset, "asset");
        DynamicMeshInstance instance = new DynamicMeshInstance(
                asset,
                DynamicMeshInstance.AffineTransform.identity(),
                defaultFaceMaterials(asset.faceCount())
        );
        return new RtDynamicTriangleMesh(
                asset.id(),
                asset.revision(),
                asset.vertexPositions(),
                asset.indices(),
                materialFor(asset, instance.faceMaterials(), 0x00F000F0, asset.id(), materialTelemetry),
                1,
                materialTelemetry
        );
    }

    /**
     * Creates a triangle-native payload without imposing the legacy two-triangle quad layout.
     *
     * <p>Each 12-int record belongs to exactly one indexed triangle. This factory is the native
     * admission boundary used by the generic renderer API; keeping it separate from
     * {@link DynamicMeshAsset} prevents host-oriented face direction and quad conventions from
     * contaminating the standalone mesh contract.</p>
     */
    static RtDynamicTriangleMesh fromTriangleRecords(
            long assetId,
            long revision,
            float[] vertexPositions,
            int[] triangleIndices,
            int[] triangleMaterialRecords,
            RtMaterialTelemetrySink materialTelemetry
    ) {
        RtSceneMaterialTable.SectionMaterial material = new RtSceneMaterialTable.SectionMaterial(
                triangleMaterialRecords,
                0,
                1
        );
        return new RtDynamicTriangleMesh(
                assetId,
                revision,
                vertexPositions,
                triangleIndices,
                material,
                1,
                materialTelemetry
        );
    }

    static DynamicRenderScene.DynamicPrimitive promoteLegacyModelPrimitive(
            DynamicRenderScene.DynamicPrimitive primitive
    ) {
        Objects.requireNonNull(primitive, "primitive");
        if (!primitive.usesTlasGeometry() || primitive.meshInstance() != null) {
            return primitive;
        }
        float sideLength = Math.max(primitive.radius(), 0.05F);
        DynamicMeshInstance.AffineTransform transform = new DynamicMeshInstance.AffineTransform(
                sideLength, 0.0F, 0.0F, finiteFloat(primitive.x(), "primitive.x"),
                0.0F, sideLength, 0.0F, finiteFloat(primitive.y(), "primitive.y"),
                0.0F, 0.0F, sideLength, finiteFloat(primitive.z(), "primitive.z")
        );
        int rgb24 = primitiveMapColor(primitive);
        int tintRgba8 = ((rgb24 >>> 16) & 0xFF)
                | (rgb24 & 0x00FF00)
                | ((rgb24 & 0xFF) << 16)
                | 0xFF00_0000;
        DynamicMeshInstance.FaceMaterial faceMaterial = new DynamicMeshInstance.FaceMaterial(
                Math.max(0, primitive.textureKey()),
                RtTextureCatalog.packUv16(0.0F, 0.0F),
                RtTextureCatalog.packUv16(1.0F, 0.0F),
                RtTextureCatalog.packUv16(1.0F, 1.0F),
                RtTextureCatalog.packUv16(0.0F, 1.0F),
                tintRgba8,
                true,
                false,
                RtBlendMode.OPAQUE,
                0,
                0,
                0,
                false,
                false,
                DynamicMeshInstance.FaceMaterial.NO_OVERLAY_COORDS
        );
        DynamicMeshInstance meshInstance = new DynamicMeshInstance(
                LEGACY_MODEL_CUBE,
                transform,
                java.util.Collections.nCopies(LEGACY_MODEL_CUBE.faceCount(), faceMaterial)
        );
        return new DynamicRenderScene.DynamicPrimitive(
                primitive.id(),
                primitive.kind(),
                primitive.geometryKind(),
                primitive.x(),
                primitive.y(),
                primitive.z(),
                primitive.yaw(),
                primitive.pitch(),
                primitive.roll(),
                primitive.radiusX(),
                primitive.radiusY(),
                primitive.radiusZ(),
                primitive.materialKey(),
                primitive.textureKey(),
                primitive.packedLight(),
                primitive.castsShadow(),
                primitive.debugName(),
                meshInstance
        );
    }

    private static DynamicMeshAsset createLegacyModelCube() {
        float negative = -0.5F;
        float positive = 0.5F;
        float[] vertices = {
                negative, negative, negative, negative, negative, positive,
                negative, positive, positive, negative, positive, negative,
                positive, negative, positive, positive, negative, negative,
                positive, positive, negative, positive, positive, positive,
                negative, negative, negative, positive, negative, negative,
                positive, negative, positive, negative, negative, positive,
                negative, positive, positive, positive, positive, positive,
                positive, positive, negative, negative, positive, negative,
                positive, negative, negative, negative, negative, negative,
                negative, positive, negative, positive, positive, negative,
                negative, negative, positive, positive, negative, positive,
                positive, positive, positive, negative, positive, positive
        };
        int[] indices = new int[6 * INDICES_PER_FACE];
        for (int faceIndex = 0; faceIndex < 6; faceIndex++) {
            int vertexBase = faceIndex * VERTICES_PER_FACE;
            int indexBase = faceIndex * INDICES_PER_FACE;
            indices[indexBase] = vertexBase;
            indices[indexBase + 1] = vertexBase + 1;
            indices[indexBase + 2] = vertexBase + 2;
            indices[indexBase + 3] = vertexBase;
            indices[indexBase + 4] = vertexBase + 2;
            indices[indexBase + 5] = vertexBase + 3;
        }
        return new DynamicMeshAsset(
                DynamicMeshAssetIdAllocator.next(DynamicMeshAssetIdAllocator.Domain.PROCEDURAL_MODEL),
                1L,
                vertices,
                indices,
                List.of(
                        new DynamicMeshAsset.Face(FaceDirection.NEGATIVE_X.ordinal(), true),
                        new DynamicMeshAsset.Face(FaceDirection.POSITIVE_X.ordinal(), true),
                        new DynamicMeshAsset.Face(FaceDirection.NEGATIVE_Y.ordinal(), true),
                        new DynamicMeshAsset.Face(FaceDirection.POSITIVE_Y.ordinal(), true),
                        new DynamicMeshAsset.Face(FaceDirection.NEGATIVE_Z.ordinal(), true),
                        new DynamicMeshAsset.Face(FaceDirection.POSITIVE_Z.ordinal(), true)
                )
        );
    }

    static RtSceneMaterialTable.SectionMaterial materialFor(DynamicRenderScene.DynamicPrimitive primitive) {
        return materialFor(primitive, RtMaterialTelemetrySink.NOOP);
    }

    static RtSceneMaterialTable.SectionMaterial materialFor(
            DynamicRenderScene.DynamicPrimitive primitive,
            RtMaterialTelemetrySink materialTelemetry
    ) {
        Objects.requireNonNull(primitive, "primitive");
        DynamicMeshInstance instance = Objects.requireNonNull(
                primitive.meshInstance(),
                "primitive.meshInstance"
        );
        return materialFor(instance.asset(), instance.faceMaterials(), primitive.packedLight(), primitive.id(),
                materialTelemetry);
    }

    static RtSceneMaterialTable.SectionMaterial materialFor(DynamicRenderScene.DynamicModelInstance instance) {
        return materialFor(instance, RtMaterialTelemetrySink.NOOP);
    }

    static RtSceneMaterialTable.SectionMaterial materialFor(
            DynamicRenderScene.DynamicModelInstance instance,
            RtMaterialTelemetrySink materialTelemetry
    ) {
        Objects.requireNonNull(instance, "instance");
        return materialFor(
                instance.asset(),
                instance.faceMaterials(),
                instance.packedLight(),
                instance.id(),
                materialTelemetry
        );
    }

    long assetId() {
        return assetId;
    }

    long revision() {
        return revision;
    }

    public float[] vertexPositions() {
        return vertexPositions.clone();
    }

    public int[] indices() {
        return indices.clone();
    }

    RtSceneMaterialTable.SectionMaterial material() {
        return material;
    }

    public int primitiveCount() {
        return primitiveCount;
    }

    public int faceCount() {
        return material.faceCount();
    }

    public int primitivesPerMaterialRecord() {
        return material.primitivesPerMaterialRecord();
    }

    /** Defensive diagnostic view; callers cannot mutate the mesh-owned material payload. */
    public int[] faceRecords() {
        return material.faceRecords();
    }

    public int triangleCount() {
        return indices.length / 3;
    }

    long estimatedBytes() {
        return (long) vertexPositions.length * Float.BYTES
                + (long) indices.length * Integer.BYTES
                + (long) material.faceCount() * INTS_PER_FACE_RECORD * Integer.BYTES;
    }

    private static final class MeshBuilder {
        private final long revision;
        private final int primitiveCount;
        private final List<Float> vertices;
        private final List<Integer> indices;
        private final List<Integer> faceRecords;
        private final RtMaterialTelemetrySink materialTelemetry;
        private final boolean diagnosticsEnabled;
        private int texturedFaces;
        private int minimumTextureId = Integer.MAX_VALUE;
        private int maximumTextureId = -1;
        private int textureSignature = 1;
        private int materialSignature = 1;

        private MeshBuilder(long revision, int primitiveCount, RtMaterialTelemetrySink materialTelemetry) {
            this.revision = revision;
            this.primitiveCount = primitiveCount;
            this.materialTelemetry = Objects.requireNonNull(materialTelemetry, "materialTelemetry");
            this.diagnosticsEnabled = materialTelemetry.dynamicMaterialDiagnosticsEnabled();
            this.vertices = new ArrayList<>(primitiveCount * 6 * VERTICES_PER_FACE * COMPONENTS_PER_VERTEX);
            this.indices = new ArrayList<>(primitiveCount * 6 * INDICES_PER_FACE);
            this.faceRecords = new ArrayList<>(primitiveCount * 6 * INTS_PER_FACE_RECORD);
        }

        private void addPrimitive(DynamicRenderScene.DynamicPrimitive primitive) {
            Objects.requireNonNull(primitive, "primitive");
            float halfExtent = Math.max(primitive.radius() * 0.5F, 0.025F);
            float centerX = finiteFloat(primitive.x(), "primitive.x");
            float centerY = finiteFloat(primitive.y(), "primitive.y");
            float centerZ = finiteFloat(primitive.z(), "primitive.z");
            int rgb24 = primitiveMapColor(primitive);
            int packedMapColorAndLight = SectionVoxelSnapshot.packMapColorAndLight(
                    rgb24,
                    hostSkyLight(primitive.packedLight()),
                    hostBlockLight(primitive.packedLight())
            );
            int voxelTypeId = primitiveVoxelStateId(primitive);
            int textureId = Math.max(0, primitive.textureKey());
            int materialFlags = SectionVoxelSnapshot.FLAG_RENDER_SHAPE_VISIBLE
                    | SectionVoxelSnapshot.FLAG_LIGHT_KNOWN;
            int primitiveFaces = FaceDirection.values().length;
            if (diagnosticsEnabled) {
                if (textureId > 0) {
                    texturedFaces += primitiveFaces;
                }
                minimumTextureId = Math.min(minimumTextureId, textureId);
                maximumTextureId = Math.max(maximumTextureId, textureId);
                textureSignature = 31 * textureSignature + textureId;
                materialSignature = 31 * materialSignature + voxelTypeId;
                materialSignature = 31 * materialSignature + packedMapColorAndLight;
                materialSignature = 31 * materialSignature + textureId;
                materialSignature = 31 * materialSignature + materialFlags;
            }

            addFace(
                    FaceDirection.NEGATIVE_X,
                    voxelTypeId,
                    packedMapColorAndLight,
                    textureId,
                    materialFlags,
                    centerX - halfExtent, centerY - halfExtent, centerZ - halfExtent,
                    centerX - halfExtent, centerY - halfExtent, centerZ + halfExtent,
                    centerX - halfExtent, centerY + halfExtent, centerZ + halfExtent,
                    centerX - halfExtent, centerY + halfExtent, centerZ - halfExtent
            );
            addFace(
                    FaceDirection.POSITIVE_X,
                    voxelTypeId,
                    packedMapColorAndLight,
                    textureId,
                    materialFlags,
                    centerX + halfExtent, centerY - halfExtent, centerZ + halfExtent,
                    centerX + halfExtent, centerY - halfExtent, centerZ - halfExtent,
                    centerX + halfExtent, centerY + halfExtent, centerZ - halfExtent,
                    centerX + halfExtent, centerY + halfExtent, centerZ + halfExtent
            );
            addFace(
                    FaceDirection.NEGATIVE_Y,
                    voxelTypeId,
                    packedMapColorAndLight,
                    textureId,
                    materialFlags,
                    centerX - halfExtent, centerY - halfExtent, centerZ - halfExtent,
                    centerX + halfExtent, centerY - halfExtent, centerZ - halfExtent,
                    centerX + halfExtent, centerY - halfExtent, centerZ + halfExtent,
                    centerX - halfExtent, centerY - halfExtent, centerZ + halfExtent
            );
            addFace(
                    FaceDirection.POSITIVE_Y,
                    voxelTypeId,
                    packedMapColorAndLight,
                    textureId,
                    materialFlags,
                    centerX - halfExtent, centerY + halfExtent, centerZ + halfExtent,
                    centerX + halfExtent, centerY + halfExtent, centerZ + halfExtent,
                    centerX + halfExtent, centerY + halfExtent, centerZ - halfExtent,
                    centerX - halfExtent, centerY + halfExtent, centerZ - halfExtent
            );
            addFace(
                    FaceDirection.NEGATIVE_Z,
                    voxelTypeId,
                    packedMapColorAndLight,
                    textureId,
                    materialFlags,
                    centerX + halfExtent, centerY - halfExtent, centerZ - halfExtent,
                    centerX - halfExtent, centerY - halfExtent, centerZ - halfExtent,
                    centerX - halfExtent, centerY + halfExtent, centerZ - halfExtent,
                    centerX + halfExtent, centerY + halfExtent, centerZ - halfExtent
            );
            addFace(
                    FaceDirection.POSITIVE_Z,
                    voxelTypeId,
                    packedMapColorAndLight,
                    textureId,
                    materialFlags,
                    centerX - halfExtent, centerY - halfExtent, centerZ + halfExtent,
                    centerX + halfExtent, centerY - halfExtent, centerZ + halfExtent,
                    centerX + halfExtent, centerY + halfExtent, centerZ + halfExtent,
                    centerX - halfExtent, centerY + halfExtent, centerZ + halfExtent
            );
        }

        private void addFace(
                FaceDirection direction,
                int voxelTypeId,
                int packedMapColorAndLight,
                int textureId,
                int materialFlags,
                float x0,
                float y0,
                float z0,
                float x1,
                float y1,
                float z1,
                float x2,
                float y2,
                float z2,
                float x3,
                float y3,
                float z3
        ) {
            int vertexBase = vertices.size() / COMPONENTS_PER_VERTEX;
            addVertex(x0, y0, z0);
            addVertex(x1, y1, z1);
            addVertex(x2, y2, z2);
            addVertex(x3, y3, z3);
            indices.add(vertexBase);
            indices.add(vertexBase + 1);
            indices.add(vertexBase + 2);
            indices.add(vertexBase);
            indices.add(vertexBase + 2);
            indices.add(vertexBase + 3);

            faceRecords.add(voxelTypeId);
            /*
             * Dynamic MODEL faces are already sampled against host light
             * coordinates at the render boundary. Sky light is not emission:
             * storing it as lightEmission made TLAS-promoted models glow and
             * skipped the closest-hit path that applies sourceEngine lightmaps.
             */
            faceRecords.add(packFaceMetadata(0, direction.ordinal(), 0, materialFlags));
            faceRecords.add(packedMapColorAndLight);
            faceRecords.add(packTextureInfo(textureId, false));
            faceRecords.add(RtTextureCatalog.packUv16(0.0F, 0.0F));
            faceRecords.add(RtTextureCatalog.packUv16(1.0F, 0.0F));
            faceRecords.add(RtTextureCatalog.packUv16(1.0F, 1.0F));
            faceRecords.add(RtTextureCatalog.packUv16(0.0F, 1.0F));
            int vertexLighting = PackedVoxelLighting.packFlatVertex(packedMapColorAndLight, direction);
            faceRecords.add(vertexLighting);
            faceRecords.add(vertexLighting);
            faceRecords.add(vertexLighting);
            faceRecords.add(vertexLighting);
        }

        private void addVertex(float x, float y, float z) {
            vertices.add(x);
            vertices.add(y);
            vertices.add(z);
        }

        private RtDynamicTriangleMesh build() {
            float[] packedVertices = new float[vertices.size()];
            for (int index = 0; index < vertices.size(); index++) {
                packedVertices[index] = vertices.get(index);
            }
            int[] packedIndices = new int[indices.size()];
            for (int index = 0; index < indices.size(); index++) {
                packedIndices[index] = indices.get(index);
            }
            int[] packedFaceRecords = new int[faceRecords.size()];
            for (int index = 0; index < faceRecords.size(); index++) {
                packedFaceRecords[index] = faceRecords.get(index);
            }
            RtSceneMaterialTable.SectionMaterial packedMaterial =
                    new RtSceneMaterialTable.SectionMaterial(packedFaceRecords);
            if (diagnosticsEnabled) {
                materialTelemetry.dynamicMeshMaterialPacked(
                        "sceneBatch",
                        0L,
                        revision,
                        0L,
                        primitiveCount,
                        packedMaterial.faceCount(),
                        texturedFaces,
                        0,
                        0,
                        0,
                        0,
                        0,
                        minimumTextureId == Integer.MAX_VALUE ? -1 : minimumTextureId,
                        maximumTextureId,
                        textureSignature,
                        materialSignature
                );
            }
            return new RtDynamicTriangleMesh(
                    0L,
                    revision,
                    packedVertices,
                    packedIndices,
                    packedMaterial,
                    primitiveCount,
                    materialTelemetry
            );
        }
    }

    private static int primitiveVoxelStateId(DynamicRenderScene.DynamicPrimitive primitive) {
        long idFold = primitive.id() ^ (primitive.id() >>> 32);
        return 0x0100_0000
                | ((primitive.kind().ordinal() & 0xFF) << 16)
                | ((primitive.geometryKind().ordinal() & 0xFF) << 8)
                | ((int) idFold & 0xFF);
    }

    private static int primitiveMapColor(DynamicRenderScene.DynamicPrimitive primitive) {
        int materialKey = primitive.materialKey();
        if ((materialKey & 0xFF00_0000) != 0) {
            int red = materialKey & 0xFF;
            int green = (materialKey >>> 8) & 0xFF;
            int blue = (materialKey >>> 16) & 0xFF;
            return (red << 16) | (green << 8) | blue;
        }
        int value = (int) (primitive.id() * 0x9E37_79B9L)
                ^ primitive.kind().ordinal() * 0x632B_E59B
                ^ primitive.geometryKind().ordinal() * 0x8515_7AF5
                ^ primitive.textureKey() * 0x58F3_8DED
                ^ materialKey;
        int red = 96 + (value & 0x7F);
        int green = 96 + ((value >>> 8) & 0x7F);
        int blue = 96 + ((value >>> 16) & 0x7F);
        return (red << 16) | (green << 8) | blue;
    }

    private static RtSceneMaterialTable.SectionMaterial materialFor(
            DynamicMeshAsset asset,
            List<DynamicMeshInstance.FaceMaterial> faceMaterials,
            int packedLight,
            long primitiveId,
            RtMaterialTelemetrySink materialTelemetry
    ) {
        Objects.requireNonNull(asset, "asset");
        List<DynamicMeshInstance.FaceMaterial> materials = Objects.requireNonNull(faceMaterials, "faceMaterials");
        if (materials.size() != asset.faceCount()) {
            throw new IllegalArgumentException("dynamic mesh material count must match asset face count");
        }
        Objects.requireNonNull(materialTelemetry, "materialTelemetry");
        boolean diagnosticsEnabled = materialTelemetry.dynamicMaterialDiagnosticsEnabled();
        int[] faceRecords = new int[Math.multiplyExact(asset.faceCount(), INTS_PER_FACE_RECORD)];
        int texturedFaces = 0;
        int tintedFaces = 0;
        int alphaCutoutFaces = 0;
        int translucentFaces = 0;
        int emissiveFaces = 0;
        int foilFaces = 0;
        int minimumTextureId = Integer.MAX_VALUE;
        int maximumTextureId = -1;
        int textureSignature = 1;
        int materialSignature = 1;
        for (int faceIndex = 0; faceIndex < asset.faceCount(); faceIndex++) {
            DynamicMeshAsset.Face face = asset.faces().get(faceIndex);
            DynamicMeshInstance.FaceMaterial material = materials.get(faceIndex);
            int recordOffset = faceIndex * INTS_PER_FACE_RECORD;
            FaceDirection direction = FaceDirection.values()[face.directionOrdinal()];
            boolean tinted = material.tinted();
            if (diagnosticsEnabled) {
                if (material.textureId() > 0) {
                    texturedFaces++;
                }
                if (tinted) {
                    tintedFaces++;
                }
                if (material.alphaCutout()) {
                    alphaCutoutFaces++;
                }
                if (material.translucent()) {
                    translucentFaces++;
                }
                if (material.lightEmission() > 0) {
                    emissiveFaces++;
                }
                if (material.foilMode() > 0) {
                    foilFaces++;
                }
                minimumTextureId = Math.min(minimumTextureId, material.textureId());
                maximumTextureId = Math.max(maximumTextureId, material.textureId());
                textureSignature = 31 * textureSignature + material.textureId();
                materialSignature = 31 * materialSignature + material.hashCode();
                materialSignature = 31 * materialSignature + face.hashCode();
            }
            int tintRgba8 = tinted ? material.tintRgba8() : 0xFFFF_FFFF;
            int rgb24 = rgbaToRgb24(tintRgba8);
            int packedMapColorAndLight = SectionVoxelSnapshot.packMapColorAndLight(
                    rgb24,
                    hostSkyLight(packedLight),
                    hostBlockLight(packedLight)
            );
            int materialFlags = SectionVoxelSnapshot.FLAG_RENDER_SHAPE_VISIBLE
                    | SectionVoxelSnapshot.FLAG_LIGHT_KNOWN;
            if (material.translucent()) {
                materialFlags |= SectionVoxelSnapshot.FLAG_AO_TRANSLUCENT;
            }
            if (((material.outlineRgba8() >>> 24) & 0xFF) != 0) {
                materialFlags |= DYNAMIC_OUTLINE_ENABLED_BIT;
                if (material.outlineOnly()) {
                    materialFlags |= DYNAMIC_OUTLINE_ONLY_BIT;
                }
            }
            materialFlags = encodeDynamicFoilMode(materialFlags, material.foilMode());
            if (material.decal().present()) {
                materialFlags |= DYNAMIC_DECAL_ENABLED_BIT;
            }
            faceRecords[recordOffset] = packDynamicFaceIdentity(material.outlineRgba8(), material.blendMode());
            faceRecords[recordOffset + 1] = packFaceMetadata(
                    0,
                    direction.ordinal(),
                    material.lightEmission(),
                    materialFlags
            );
            faceRecords[recordOffset + 2] = packedMapColorAndLight;
            faceRecords[recordOffset + 3] = packTextureInfo(
                    material.textureId(),
                    tinted,
                    material.alphaCutout()
            );
            faceRecords[recordOffset + 4] = material.uv0();
            faceRecords[recordOffset + 5] = material.uv1();
            faceRecords[recordOffset + 6] = material.uv2();
            faceRecords[recordOffset + 7] = material.uv3();
            FaceDirection lightingDirection = face.shade() ? direction : FaceDirection.POSITIVE_Y;
            int vertexLighting = packDynamicOverlay(
                    PackedVoxelLighting.packFlatVertex(packedMapColorAndLight, lightingDirection),
                    material.overlayCoords()
            );
            faceRecords[recordOffset + 8] = vertexLighting;
            int rawVertexLighting = vertexLighting & 0x00FF_FFFF;
            faceRecords[recordOffset + 9] = packDynamicDecalWord(
                    material.decal(), 0, tintRgba8 & 0xFF00_0000
            );
            faceRecords[recordOffset + 10] = packDynamicDecalWord(
                    material.decal(), 1, material.outlineRgba8() & 0xFF00_0000
            );
            faceRecords[recordOffset + 11] = packDynamicDecalWord(
                    material.decal(),
                    2,
                    ((material.decal().textureId() >>> 12) & 0xF) << 24
                            | material.decal().fractionalBits() << 28
            );
        }
        RtSceneMaterialTable.SectionMaterial packedMaterial = new RtSceneMaterialTable.SectionMaterial(faceRecords);
        if (diagnosticsEnabled) {
            materialTelemetry.dynamicMeshMaterialPacked(
                    "assetInstance",
                    asset.id(),
                    asset.revision(),
                    primitiveId,
                    1,
                    packedMaterial.faceCount(),
                    texturedFaces,
                    tintedFaces,
                    alphaCutoutFaces,
                    translucentFaces,
                    emissiveFaces,
                    foilFaces,
                    minimumTextureId == Integer.MAX_VALUE ? -1 : minimumTextureId,
                    maximumTextureId,
                    textureSignature,
                    materialSignature
            );
        }
        return packedMaterial;
    }

    private static int encodeDynamicFoilMode(int materialFlags, int foilMode) {
        if (foilMode < 0 || foilMode > 2) {
            throw new IllegalArgumentException("dynamic foil mode must be in [0, 2]: " + foilMode);
        }
        if ((materialFlags & DYNAMIC_FOIL_MODE_MASK) != 0) {
            throw new IllegalArgumentException("dynamic material flags already contain a foil mode");
        }
        return materialFlags | (foilMode << DYNAMIC_FOIL_MODE_SHIFT);
    }

    static int packDynamicOverlay(int packedVertexLighting, int overlayCoords) {
        if ((packedVertexLighting & 0xFF00_0000) != 0) {
            throw new IllegalArgumentException("dynamic vertex lighting high byte is already occupied");
        }
        DynamicMeshInstance.FaceMaterial.requireValidOverlayCoords(overlayCoords);
        int overlayByte = (overlayCoords & 0x0F) | (((overlayCoords >>> 16) & 0x0F) << 4);
        return packedVertexLighting | (overlayByte << 24);
    }

    static int packDynamicTintAlpha(int packedVertexLighting, int tintRgba8) {
        if ((packedVertexLighting & 0xFF00_0000) != 0) {
            throw new IllegalArgumentException("dynamic vertex lighting high byte is already occupied");
        }
        return packedVertexLighting | (tintRgba8 & 0xFF00_0000);
    }

    static int packDynamicOutlineAlpha(int packedVertexLighting, int outlineRgba8) {
        if ((packedVertexLighting & 0xFF00_0000) != 0) {
            throw new IllegalArgumentException("dynamic vertex lighting high byte is already occupied");
        }
        return packedVertexLighting | (outlineRgba8 & 0xFF00_0000);
    }

    static int packDynamicDecalWord(
            DynamicMeshInstance.SurfaceDecal decal,
            int vertexIndex,
            int highByte
    ) {
        Objects.requireNonNull(decal, "decal");
        if (vertexIndex < 0 || vertexIndex > 2 || (highByte & 0x00FF_FFFF) != 0) {
            throw new IllegalArgumentException("dynamic decal word inputs are invalid");
        }
        int packedU = quantizeDecalCoordinate(decal.u(vertexIndex), decal.fractionalBits());
        int packedV = quantizeDecalCoordinate(decal.v(vertexIndex), decal.fractionalBits());
        int textureNibble = decal.textureId() >>> (vertexIndex * 4) & 0xF;
        return highByte
                | packedU
                | packedV << DECAL_UV_BITS
                | textureNibble << 20;
    }

    private static int quantizeDecalCoordinate(float coordinate, int fractionalBits) {
        int quantized = Math.round(coordinate * (1 << fractionalBits));
        if (quantized < -512 || quantized > 511) {
            throw new IllegalArgumentException("dynamic decal UV coordinate exceeds encoded range");
        }
        return quantized & DECAL_UV_MASK;
    }

    static int packDynamicFaceIdentity(int outlineRgba8, RtBlendMode blendMode) {
        Objects.requireNonNull(blendMode, "blendMode");
        int blendCode = blendMode.faceCode();
        if ((blendCode & ~0xF) != 0) {
            throw new IllegalArgumentException("dynamic blend mode must fit the face marker nibble: " + blendCode);
        }
        return DYNAMIC_FACE_MARKER
                | (blendCode << DYNAMIC_BLEND_MODE_SHIFT)
                | (outlineRgba8 & 0x00FF_FFFF);
    }

    static RtBlendMode unpackDynamicBlendMode(int packedFaceIdentity) {
        if ((packedFaceIdentity & DYNAMIC_FACE_MARKER_MASK) != DYNAMIC_FACE_MARKER) {
            throw new IllegalArgumentException("face identity is not a dynamic face marker");
        }
        return RtBlendMode.fromFaceCode(
                (packedFaceIdentity & DYNAMIC_BLEND_MODE_MASK) >>> DYNAMIC_BLEND_MODE_SHIFT
        );
    }

    private static List<DynamicMeshInstance.FaceMaterial> defaultFaceMaterials(int faceCount) {
        DynamicMeshInstance.FaceMaterial material = new DynamicMeshInstance.FaceMaterial(
                0,
                RtTextureCatalog.packUv16(0.0F, 0.0F),
                RtTextureCatalog.packUv16(1.0F, 0.0F),
                RtTextureCatalog.packUv16(1.0F, 1.0F),
                RtTextureCatalog.packUv16(0.0F, 1.0F),
                0xFFFF_FFFF,
                false,
                false,
                RtBlendMode.OPAQUE,
                0,
                0,
                0,
                false,
                false,
                DynamicMeshInstance.FaceMaterial.NO_OVERLAY_COORDS
        );
        return java.util.Collections.nCopies(faceCount, material);
    }

    private static int packFaceMetadata(int mediumAmount, int faceDirection, int lightEmission, int materialFlags) {
        return (mediumAmount & 0xFF)
                | ((faceDirection & 0xFF) << 8)
                | ((lightEmission & 0xFF) << 16)
                | ((materialFlags & 0xFF) << 24);
    }

    private static int packTextureInfo(int textureId, boolean tinted) {
        return packTextureInfo(textureId, tinted, false);
    }

    private static int packTextureInfo(int textureId, boolean tinted, boolean alphaCutout) {
        if (textureId < 0 || (textureId & ~TEXTURE_INFO_TEXTURE_ID_MASK) != 0) {
            throw new IllegalArgumentException("invalid dynamic texture id: " + textureId);
        }
        int flags = 0;
        if (alphaCutout) {
            flags |= TEXTURE_INFO_ALPHA_CUTOUT_BIT;
        }
        if (tinted) {
            flags |= TEXTURE_INFO_TINT_BIT;
        }
        return textureId | flags;
    }

    private static int rgbaToRgb24(int rgba8) {
        int red = rgba8 & 0xFF;
        int green = (rgba8 >>> 8) & 0xFF;
        int blue = (rgba8 >>> 16) & 0xFF;
        return (red << 16) | (green << 8) | blue;
    }

    private static int hostBlockLight(int packedLightCoords) {
        return (packedLightCoords >>> 4) & 0x0F;
    }

    private static int hostSkyLight(int packedLightCoords) {
        return (packedLightCoords >>> 20) & 0x0F;
    }

    private static float finiteFloat(double value, String name) {
        if (!Double.isFinite(value) || value < -Float.MAX_VALUE || value > Float.MAX_VALUE) {
            throw new IllegalArgumentException(name + " must be finite float compatible");
        }
        return (float) value;
    }
}
