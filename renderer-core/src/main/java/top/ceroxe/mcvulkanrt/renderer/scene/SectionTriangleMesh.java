package top.ceroxe.mcvulkanrt.renderer.scene;

import top.ceroxe.mcvulkanrt.renderer.rt.material.RtSceneMaterialTable;
import top.ceroxe.mcvulkanrt.renderer.rt.material.RtTextureCatalog;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * section-local triangle/index buffer 的 CPU staging 表示。
 *
 * <p>坐标使用 section-local fixed-point 网格。单位立方体面仍然精确落在 [0, 16]，
 * baked model quad 的 1/16 方块坐标也能无损进入 BLAS；这样不会为了草、植物、
 * 可可豆等非整块模型把几何偷偷截断回整数格。</p>
 */
public record SectionTriangleMesh(
        SectionKey key,
        short[] vertexPositions,
        int[] indices,
        int[] faceVoxelStateIds,
        byte[] faceFluidAmounts,
        byte[] faceDirections,
        int[] faceMapColors,
        int[] faceVertexLighting0,
        int[] faceVertexLighting1,
        int[] faceVertexLighting2,
        int[] faceVertexLighting3,
        byte[] faceLightEmissions,
        byte[] faceMaterialFlags,
        int[] faceTextureIds,
        int[] faceUv0,
        int[] faceUv1,
        int[] faceUv2,
        int[] faceUv3,
        byte[] faceTintFlags,
        byte[] faceAlphaCutoutFlags,
        byte[] faceRenderLayers,
        short[] faceSourceBlockIndices,
        int[] faceTintIndices,
        int[] faceFluidTypeIds,
        short[] faceFluidFlowX,
        short[] faceFluidFlowZ,
        byte[] faceFluidOverlayFlags,
        short[] faceFluidHeight0,
        short[] faceFluidHeight1,
        short[] faceFluidHeight2,
        short[] faceFluidHeight3,
        MaterialPublicationSlot materialPublicationSlot
) {
    private static final int COMPONENTS_PER_VERTEX = 3;
    private static final int VERTICES_PER_FACE = 4;
    private static final int INDICES_PER_FACE = 6;
    public static final int POSITION_SCALE = 1024;
    public static final short UNKNOWN_SOURCE_BLOCK_INDEX = -1;
    public static final int UNKNOWN_TINT_INDEX = Integer.MIN_VALUE;
    public static final int NO_TINT_INDEX = -1;
    public static final int UNKNOWN_FLUID_TYPE_ID = SectionFace.UNKNOWN_FLUID_TYPE_ID;
    public static final short UNKNOWN_FLUID_SEMANTIC = Short.MIN_VALUE;
    public static final byte UNKNOWN_FLUID_OVERLAY = -1;

    /*
     * A production mesh builder allocates every component array for one mesh and
     * immediately publishes that mesh. Transferring those fresh arrays avoids a
     * second full-size copy while keeping every public construction and accessor
     * defensive. The scope is thread-confined and removed in finally, so a failed
     * construction cannot affect a later public constructor on the same worker.
     */
    private static final ThreadLocal<OwnedArrayTransfer> OWNED_ARRAY_TRANSFER = new ThreadLocal<>();

    public SectionTriangleMesh {
        key = Objects.requireNonNull(key, "key");
        materialPublicationSlot = Objects.requireNonNull(materialPublicationSlot, "materialPublicationSlot");
        if (!materialPublicationSlot.claimOwner()) {
            throw new IllegalArgumentException("materialPublicationSlot already belongs to another mesh generation");
        }
        vertexPositions = copyShortArray(vertexPositions, "vertexPositions");
        indices = copyIntArray(indices, "indices");
        faceVoxelStateIds = copyIntArray(faceVoxelStateIds, "faceVoxelStateIds");
        faceFluidAmounts = copyByteArray(faceFluidAmounts, "faceFluidAmounts", faceVoxelStateIds.length);
        faceDirections = copyByteArray(faceDirections, "faceDirections", faceVoxelStateIds.length);
        faceMapColors = copyIntArray(faceMapColors, "faceMapColors", faceVoxelStateIds.length);
        faceVertexLighting0 = copyIntArray(faceVertexLighting0, "faceVertexLighting0", faceVoxelStateIds.length);
        faceVertexLighting1 = copyIntArray(faceVertexLighting1, "faceVertexLighting1", faceVoxelStateIds.length);
        faceVertexLighting2 = copyIntArray(faceVertexLighting2, "faceVertexLighting2", faceVoxelStateIds.length);
        faceVertexLighting3 = copyIntArray(faceVertexLighting3, "faceVertexLighting3", faceVoxelStateIds.length);
        faceLightEmissions = copyByteArray(faceLightEmissions, "faceLightEmissions", faceVoxelStateIds.length);
        faceMaterialFlags = copyByteArray(faceMaterialFlags, "faceMaterialFlags", faceVoxelStateIds.length);
        faceTextureIds = copyIntArray(faceTextureIds, "faceTextureIds", faceVoxelStateIds.length);
        faceUv0 = copyIntArray(faceUv0, "faceUv0", faceVoxelStateIds.length);
        faceUv1 = copyIntArray(faceUv1, "faceUv1", faceVoxelStateIds.length);
        faceUv2 = copyIntArray(faceUv2, "faceUv2", faceVoxelStateIds.length);
        faceUv3 = copyIntArray(faceUv3, "faceUv3", faceVoxelStateIds.length);
        faceTintFlags = copyByteArray(faceTintFlags, "faceTintFlags", faceVoxelStateIds.length);
        faceAlphaCutoutFlags = copyByteArray(faceAlphaCutoutFlags, "faceAlphaCutoutFlags", faceVoxelStateIds.length);
        faceRenderLayers = copyByteArray(faceRenderLayers, "faceRenderLayers", faceVoxelStateIds.length);
        faceSourceBlockIndices = copyShortArray(
                faceSourceBlockIndices, "faceSourceBlockIndices", faceVoxelStateIds.length
        );
        faceTintIndices = copyIntArray(faceTintIndices, "faceTintIndices", faceVoxelStateIds.length);
        faceFluidTypeIds = copyIntArray(faceFluidTypeIds, "faceFluidTypeIds", faceVoxelStateIds.length);
        faceFluidFlowX = copyShortArray(faceFluidFlowX, "faceFluidFlowX", faceVoxelStateIds.length);
        faceFluidFlowZ = copyShortArray(faceFluidFlowZ, "faceFluidFlowZ", faceVoxelStateIds.length);
        faceFluidOverlayFlags = copyByteArray(
                faceFluidOverlayFlags, "faceFluidOverlayFlags", faceVoxelStateIds.length
        );
        faceFluidHeight0 = copyShortArray(faceFluidHeight0, "faceFluidHeight0", faceVoxelStateIds.length);
        faceFluidHeight1 = copyShortArray(faceFluidHeight1, "faceFluidHeight1", faceVoxelStateIds.length);
        faceFluidHeight2 = copyShortArray(faceFluidHeight2, "faceFluidHeight2", faceVoxelStateIds.length);
        faceFluidHeight3 = copyShortArray(faceFluidHeight3, "faceFluidHeight3", faceVoxelStateIds.length);
        validateRenderLayers(faceRenderLayers, faceAlphaCutoutFlags);
        validateSourceBlockIndices(faceSourceBlockIndices);
        validateSemanticFields(
                faceTintIndices, faceFluidTypeIds, faceFluidFlowX, faceFluidFlowZ,
                faceFluidOverlayFlags, faceFluidHeight0, faceFluidHeight1, faceFluidHeight2, faceFluidHeight3
        );

        if (vertexPositions.length % COMPONENTS_PER_VERTEX != 0) {
            throw new IllegalArgumentException("vertexPositions length must be divisible by " + COMPONENTS_PER_VERTEX);
        }
        if (indices.length % 3 != 0) {
            throw new IllegalArgumentException("indices length must be divisible by 3");
        }
        int vertexCount = vertexPositions.length / COMPONENTS_PER_VERTEX;
        int faceCount = faceVoxelStateIds.length;
        if (vertexCount != faceCount * VERTICES_PER_FACE) {
            throw new IllegalArgumentException("vertex count must equal faceCount * " + VERTICES_PER_FACE);
        }
        if (indices.length != faceCount * INDICES_PER_FACE) {
            throw new IllegalArgumentException("index count must equal faceCount * " + INDICES_PER_FACE);
        }
        validateIndices(indices, vertexCount);
        validateTextureIds(faceTextureIds);
    }

    /**
     * Public construction creates one publication owner for this exact immutable mesh generation.
     * Retry queues may retain and resubmit the mesh, but they must consume this slot instead of
     * deriving the same twelve-int face records again.
     */
    public SectionTriangleMesh(
            SectionKey key,
            short[] vertexPositions,
            int[] indices,
            int[] faceVoxelStateIds,
            byte[] faceFluidAmounts,
            byte[] faceDirections,
            int[] faceMapColors,
            int[] faceVertexLighting0,
            int[] faceVertexLighting1,
            int[] faceVertexLighting2,
            int[] faceVertexLighting3,
            byte[] faceLightEmissions,
            byte[] faceMaterialFlags,
            int[] faceTextureIds,
            int[] faceUv0,
            int[] faceUv1,
            int[] faceUv2,
            int[] faceUv3,
            byte[] faceTintFlags,
            byte[] faceAlphaCutoutFlags,
            byte[] faceRenderLayers,
            short[] faceSourceBlockIndices,
            int[] faceTintIndices,
            int[] faceFluidTypeIds,
            short[] faceFluidFlowX,
            short[] faceFluidFlowZ,
            byte[] faceFluidOverlayFlags,
            short[] faceFluidHeight0,
            short[] faceFluidHeight1,
            short[] faceFluidHeight2,
            short[] faceFluidHeight3
    ) {
        this(
                key, vertexPositions, indices, faceVoxelStateIds, faceFluidAmounts, faceDirections,
                faceMapColors, faceVertexLighting0, faceVertexLighting1, faceVertexLighting2,
                faceVertexLighting3, faceLightEmissions, faceMaterialFlags, faceTextureIds,
                faceUv0, faceUv1, faceUv2, faceUv3, faceTintFlags, faceAlphaCutoutFlags,
                faceRenderLayers, faceSourceBlockIndices, faceTintIndices, faceFluidTypeIds,
                faceFluidFlowX, faceFluidFlowZ, faceFluidOverlayFlags, faceFluidHeight0,
                faceFluidHeight1, faceFluidHeight2, faceFluidHeight3, new MaterialPublicationSlot()
        );
    }

    public RtSceneMaterialTable.SectionMaterial packedMaterialPublication() {
        return materialPublicationSlot.publication();
    }

    /**
     * Publishes material records already proven to describe this mesh. The slot is first-writer
     * wins so concurrent retry/admission paths cannot replace one immutable generation with a
     * different payload. Callers must prove the candidate with fromMesh or matchesMesh first.
     */
    public RtSceneMaterialTable.SectionMaterial publishPackedMaterial(
            RtSceneMaterialTable.SectionMaterial candidate
    ) {
        return materialPublicationSlot.publish(Objects.requireNonNull(candidate, "candidate"));
    }

    /**
     * Completes the worker-owned mesh generation by publishing its compact material payload
     * before the mesh escapes to admission queues.  The publication factory owns record packing
     * so geometry producers cannot drift from shader layout or opaque/cutout primitive ordering.
     */
    void publishWorkerMaterial() {
        if (faceCount() > 0) {
            RtSceneMaterialTable.SectionMaterial.publishCompactFromWorkerMesh(this);
        }
    }

    /** One-shot publication cell; its identity is part of the immutable mesh generation. */
    public static final class MaterialPublicationSlot {
        private volatile RtSceneMaterialTable.SectionMaterial publication;
        private boolean owned;

        private MaterialPublicationSlot() {
        }

        private RtSceneMaterialTable.SectionMaterial publication() {
            return publication;
        }

        private synchronized boolean claimOwner() {
            if (owned) {
                return false;
            }
            owned = true;
            return true;
        }

        private synchronized RtSceneMaterialTable.SectionMaterial publish(
                RtSceneMaterialTable.SectionMaterial candidate
        ) {
            if (publication == null) {
                publication = candidate;
            }
            return publication;
        }
    }

    static SectionTriangleMesh fromOwnedArrays(
            SectionKey key,
            short[] vertexPositions,
            int[] indices,
            int[] faceVoxelStateIds,
            byte[] faceFluidAmounts,
            byte[] faceDirections,
            int[] faceMapColors,
            int[] faceVertexLighting0,
            int[] faceVertexLighting1,
            int[] faceVertexLighting2,
            int[] faceVertexLighting3,
            byte[] faceLightEmissions,
            byte[] faceMaterialFlags,
            int[] faceTextureIds,
            int[] faceUv0,
            int[] faceUv1,
            int[] faceUv2,
            int[] faceUv3,
            byte[] faceTintFlags,
            byte[] faceAlphaCutoutFlags,
            byte[] faceRenderLayers,
            short[] faceSourceBlockIndices,
            int[] faceTintIndices,
            int[] faceFluidTypeIds,
            short[] faceFluidFlowX,
            short[] faceFluidFlowZ,
            byte[] faceFluidOverlayFlags,
            short[] faceFluidHeight0,
            short[] faceFluidHeight1,
            short[] faceFluidHeight2,
            short[] faceFluidHeight3
    ) {
        if (OWNED_ARRAY_TRANSFER.get() != null) {
            throw new IllegalStateException("nested owned SectionTriangleMesh construction is not supported");
        }
        OWNED_ARRAY_TRANSFER.set(new OwnedArrayTransfer(
                vertexPositions, indices, faceVoxelStateIds, faceFluidAmounts, faceDirections,
                faceMapColors, faceVertexLighting0, faceVertexLighting1, faceVertexLighting2,
                faceVertexLighting3, faceLightEmissions, faceMaterialFlags, faceTextureIds,
                faceUv0, faceUv1, faceUv2, faceUv3, faceTintFlags, faceAlphaCutoutFlags,
                faceRenderLayers, faceSourceBlockIndices, faceTintIndices, faceFluidTypeIds,
                faceFluidFlowX, faceFluidFlowZ, faceFluidOverlayFlags, faceFluidHeight0,
                faceFluidHeight1, faceFluidHeight2, faceFluidHeight3
        ));
        try {
            return new SectionTriangleMesh(
                    key, vertexPositions, indices, faceVoxelStateIds, faceFluidAmounts, faceDirections,
                    faceMapColors, faceVertexLighting0, faceVertexLighting1, faceVertexLighting2,
                    faceVertexLighting3, faceLightEmissions, faceMaterialFlags, faceTextureIds,
                    faceUv0, faceUv1, faceUv2, faceUv3, faceTintFlags, faceAlphaCutoutFlags,
                    faceRenderLayers, faceSourceBlockIndices, faceTintIndices, faceFluidTypeIds,
                    faceFluidFlowX, faceFluidFlowZ, faceFluidOverlayFlags, faceFluidHeight0,
                    faceFluidHeight1, faceFluidHeight2, faceFluidHeight3
            );
        } finally {
            OWNED_ARRAY_TRANSFER.remove();
        }
    }

    /**
     * Compatibility constructor for pre-oracle mesh producers.
     *
     * <p>Those producers never observed the new semantic facts, so this path
     * marks them unavailable instead of inventing values that could create a
     * false sourceEngine/RT match.</p>
     */
    public SectionTriangleMesh(
            SectionKey key,
            short[] vertexPositions,
            int[] indices,
            int[] faceVoxelStateIds,
            byte[] faceFluidAmounts,
            byte[] faceDirections,
            int[] faceMapColors,
            int[] faceVertexLighting0,
            int[] faceVertexLighting1,
            int[] faceVertexLighting2,
            int[] faceVertexLighting3,
            byte[] faceLightEmissions,
            byte[] faceMaterialFlags,
            int[] faceTextureIds,
            int[] faceUv0,
            int[] faceUv1,
            int[] faceUv2,
            int[] faceUv3,
            byte[] faceTintFlags,
            byte[] faceAlphaCutoutFlags,
            byte[] faceRenderLayers,
            short[] faceSourceBlockIndices
    ) {
        this(
                key, vertexPositions, indices, faceVoxelStateIds, faceFluidAmounts, faceDirections,
                faceMapColors, faceVertexLighting0, faceVertexLighting1, faceVertexLighting2,
                faceVertexLighting3, faceLightEmissions, faceMaterialFlags, faceTextureIds,
                faceUv0, faceUv1, faceUv2, faceUv3, faceTintFlags, faceAlphaCutoutFlags,
                faceRenderLayers, faceSourceBlockIndices,
                unknownIntValues(faceVoxelStateIds.length, UNKNOWN_TINT_INDEX),
                unknownIntValues(faceVoxelStateIds.length, UNKNOWN_FLUID_TYPE_ID),
                unknownShortValues(faceVoxelStateIds.length),
                unknownShortValues(faceVoxelStateIds.length),
                unknownOverlayValues(faceVoxelStateIds.length),
                unknownShortValues(faceVoxelStateIds.length),
                unknownShortValues(faceVoxelStateIds.length),
                unknownShortValues(faceVoxelStateIds.length),
                unknownShortValues(faceVoxelStateIds.length)
        );
    }

    public SectionTriangleMesh(
            SectionKey key,
            short[] vertexPositions,
            int[] indices,
            int[] faceVoxelStateIds,
            byte[] faceFluidAmounts,
            byte[] faceDirections
    ) {
        this(
                key,
                vertexPositions,
                indices,
                faceVoxelStateIds,
                faceFluidAmounts,
                faceDirections,
                new int[faceVoxelStateIds.length],
                defaultVertexLighting(faceVoxelStateIds.length),
                defaultVertexLighting(faceVoxelStateIds.length),
                defaultVertexLighting(faceVoxelStateIds.length),
                defaultVertexLighting(faceVoxelStateIds.length),
                new byte[faceVoxelStateIds.length],
                defaultMaterialFlags(faceVoxelStateIds, faceFluidAmounts),
                defaultTextureIds(faceVoxelStateIds.length),
                defaultUv0(faceVoxelStateIds.length),
                defaultUv1(faceVoxelStateIds.length),
                defaultUv2(faceVoxelStateIds.length),
                defaultUv3(faceVoxelStateIds.length),
                new byte[faceVoxelStateIds.length],
                new byte[faceVoxelStateIds.length],
                defaultRenderLayers(defaultMaterialFlags(faceVoxelStateIds, faceFluidAmounts), new byte[faceVoxelStateIds.length]),
                unknownSourceBlockIndices(faceVoxelStateIds.length)
        );
    }

    public SectionTriangleMesh(
            SectionKey key,
            short[] vertexPositions,
            int[] indices,
            int[] faceVoxelStateIds,
            byte[] faceFluidAmounts,
            byte[] faceDirections,
            int[] faceMapColors,
            byte[] faceLightEmissions,
            byte[] faceMaterialFlags
    ) {
        this(
                key,
                vertexPositions,
                indices,
                faceVoxelStateIds,
                faceFluidAmounts,
                faceDirections,
                faceMapColors,
                defaultVertexLighting(faceMapColors, faceDirections, 0),
                defaultVertexLighting(faceMapColors, faceDirections, 1),
                defaultVertexLighting(faceMapColors, faceDirections, 2),
                defaultVertexLighting(faceMapColors, faceDirections, 3),
                faceLightEmissions,
                faceMaterialFlags,
                defaultTextureIds(faceVoxelStateIds.length),
                defaultUv0(faceVoxelStateIds.length),
                defaultUv1(faceVoxelStateIds.length),
                defaultUv2(faceVoxelStateIds.length),
                defaultUv3(faceVoxelStateIds.length),
                new byte[faceVoxelStateIds.length],
                new byte[faceVoxelStateIds.length],
                defaultRenderLayers(faceMaterialFlags, new byte[faceVoxelStateIds.length]),
                unknownSourceBlockIndices(faceVoxelStateIds.length)
        );
    }

    public SectionTriangleMesh(
            SectionKey key,
            short[] vertexPositions,
            int[] indices,
            int[] faceVoxelStateIds,
            byte[] faceFluidAmounts,
            byte[] faceDirections,
            int[] faceMapColors,
            byte[] faceLightEmissions,
            byte[] faceMaterialFlags,
            int[] faceTextureIds,
            int[] faceUv0,
            int[] faceUv1,
            int[] faceUv2,
            int[] faceUv3,
            byte[] faceTintFlags,
            byte[] faceAlphaCutoutFlags
    ) {
        this(
                key,
                vertexPositions,
                indices,
                faceVoxelStateIds,
                faceFluidAmounts,
                faceDirections,
                faceMapColors,
                defaultVertexLighting(faceMapColors, faceDirections, 0),
                defaultVertexLighting(faceMapColors, faceDirections, 1),
                defaultVertexLighting(faceMapColors, faceDirections, 2),
                defaultVertexLighting(faceMapColors, faceDirections, 3),
                faceLightEmissions,
                faceMaterialFlags,
                faceTextureIds,
                faceUv0,
                faceUv1,
                faceUv2,
                faceUv3,
                faceTintFlags,
                faceAlphaCutoutFlags,
                defaultRenderLayers(faceMaterialFlags, faceAlphaCutoutFlags),
                unknownSourceBlockIndices(faceVoxelStateIds.length)
        );
    }

    public SectionTriangleMesh(
            SectionKey key,
            short[] vertexPositions,
            int[] indices,
            int[] faceVoxelStateIds,
            byte[] faceFluidAmounts,
            byte[] faceDirections,
            int[] faceMapColors,
            int[] faceVertexLighting0,
            int[] faceVertexLighting1,
            int[] faceVertexLighting2,
            int[] faceVertexLighting3,
            byte[] faceLightEmissions,
            byte[] faceMaterialFlags,
            int[] faceTextureIds,
            int[] faceUv0,
            int[] faceUv1,
            int[] faceUv2,
            int[] faceUv3,
            byte[] faceTintFlags,
            byte[] faceAlphaCutoutFlags
    ) {
        this(
                key,
                vertexPositions,
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
                defaultRenderLayers(faceMaterialFlags, faceAlphaCutoutFlags),
                unknownSourceBlockIndices(faceVoxelStateIds.length)
        );
    }

    public SectionTriangleMesh(
            SectionKey key,
            short[] vertexPositions,
            int[] indices,
            int[] faceVoxelStateIds,
            byte[] faceFluidAmounts,
            byte[] faceDirections,
            int[] faceMapColors,
            int[] faceVertexLighting0,
            int[] faceVertexLighting1,
            int[] faceVertexLighting2,
            int[] faceVertexLighting3,
            byte[] faceLightEmissions,
            byte[] faceMaterialFlags,
            int[] faceTextureIds,
            int[] faceUv0,
            int[] faceUv1,
            int[] faceUv2,
            int[] faceUv3,
            byte[] faceTintFlags,
            byte[] faceAlphaCutoutFlags,
            byte[] faceRenderLayers
    ) {
        this(
                key, vertexPositions, indices, faceVoxelStateIds, faceFluidAmounts, faceDirections,
                faceMapColors, faceVertexLighting0, faceVertexLighting1, faceVertexLighting2,
                faceVertexLighting3, faceLightEmissions, faceMaterialFlags, faceTextureIds,
                faceUv0, faceUv1, faceUv2, faceUv3, faceTintFlags, faceAlphaCutoutFlags,
                faceRenderLayers, unknownSourceBlockIndices(faceVoxelStateIds.length)
        );
    }

    public int vertexCount() {
        return vertexPositions.length / COMPONENTS_PER_VERTEX;
    }

    public int faceCount() {
        return faceVoxelStateIds.length;
    }

    /**
     * Reads one immutable fixed-point vertex component without cloning the complete mesh array.
     * Compact proxy builders are sparse consumers; forcing them through the defensive array
     * accessor allocated one full {@code short[]} for every streamed section.
     */
    public short vertexPositionComponent(int vertexIndex, int component) {
        Objects.checkIndex(vertexIndex, vertexCount());
        Objects.checkIndex(component, COMPONENTS_PER_VERTEX);
        return vertexPositions[vertexIndex * COMPONENTS_PER_VERTEX + component];
    }

    public int triangleCount() {
        return indices.length / 3;
    }

    public int indexCount() {
        return indices.length;
    }

    public long estimatedBytes() {
        return Short.BYTES * vertexPositions.length
                + Integer.BYTES * indices.length
                + Integer.BYTES * faceVoxelStateIds.length
                + faceFluidAmounts.length
                + faceDirections.length
                + Integer.BYTES * faceMapColors.length
                + Integer.BYTES * faceVertexLighting0.length
                + Integer.BYTES * faceVertexLighting1.length
                + Integer.BYTES * faceVertexLighting2.length
                + Integer.BYTES * faceVertexLighting3.length
                + faceLightEmissions.length
                + faceMaterialFlags.length
                + Integer.BYTES * faceTextureIds.length
                + Integer.BYTES * faceUv0.length
                + Integer.BYTES * faceUv1.length
                + Integer.BYTES * faceUv2.length
                + Integer.BYTES * faceUv3.length
                + faceTintFlags.length
                + faceAlphaCutoutFlags.length
                + faceRenderLayers.length
                + Short.BYTES * faceSourceBlockIndices.length
                + Integer.BYTES * faceTintIndices.length
                + Integer.BYTES * faceFluidTypeIds.length
                + Short.BYTES * faceFluidFlowX.length
                + Short.BYTES * faceFluidFlowZ.length
                + faceFluidOverlayFlags.length
                + Short.BYTES * (faceFluidHeight0.length + faceFluidHeight1.length
                + faceFluidHeight2.length + faceFluidHeight3.length);
    }

    public boolean hasAlphaCutoutFaces() {
        for (byte flag : faceAlphaCutoutFlags) {
            if (flag != 0) {
                return true;
            }
        }
        return false;
    }

    public int alphaCutoutFaceCount() {
        int count = 0;
        for (byte flag : faceAlphaCutoutFlags) {
            if (flag != 0) {
                count++;
            }
        }
        return count;
    }

    public boolean hasSameBaseGeometry(SectionTriangleMesh other) {
        Objects.requireNonNull(other, "other");
        return faceCount() == other.faceCount()
                && triangleCount() == other.triangleCount()
                && vertexCount() == other.vertexCount()
                && Arrays.equals(vertexPositions, other.vertexPositions)
                && Arrays.equals(indices, other.indices)
                && Arrays.equals(faceAlphaCutoutFlags, other.faceAlphaCutoutFlags);
    }

    public boolean hasSameProxyGeometry(SectionTriangleMesh other) {
        Objects.requireNonNull(other, "other");
        return hasSameBaseGeometry(other)
                && Arrays.equals(faceDirections, other.faceDirections);
    }

    public int faceVoxelStateId(int faceIndex) {
        validateFaceIndex(faceIndex);
        return faceVoxelStateIds[faceIndex];
    }

    public int faceFluidAmount(int faceIndex) {
        validateFaceIndex(faceIndex);
        return Byte.toUnsignedInt(faceFluidAmounts[faceIndex]);
    }

    public int faceDirectionOrdinal(int faceIndex) {
        validateFaceIndex(faceIndex);
        return Byte.toUnsignedInt(faceDirections[faceIndex]);
    }

    public int faceMapColor(int faceIndex) {
        validateFaceIndex(faceIndex);
        return faceMapColors[faceIndex];
    }

    public int faceVertexLighting(int faceIndex, int vertexIndex) {
        validateFaceIndex(faceIndex);
        return switch (vertexIndex) {
            case 0 -> faceVertexLighting0[faceIndex];
            case 1 -> faceVertexLighting1[faceIndex];
            case 2 -> faceVertexLighting2[faceIndex];
            case 3 -> faceVertexLighting3[faceIndex];
            default -> throw new IllegalArgumentException("vertexIndex outside quad range: " + vertexIndex);
        };
    }

    public int faceLightEmission(int faceIndex) {
        validateFaceIndex(faceIndex);
        return Byte.toUnsignedInt(faceLightEmissions[faceIndex]);
    }

    public int faceMaterialFlagBits(int faceIndex) {
        validateFaceIndex(faceIndex);
        return Byte.toUnsignedInt(faceMaterialFlags[faceIndex]);
    }

    public int faceTextureId(int faceIndex) {
        validateFaceIndex(faceIndex);
        return faceTextureIds[faceIndex];
    }

    public int faceUv(int faceIndex, int vertexIndex) {
        validateFaceIndex(faceIndex);
        return switch (vertexIndex) {
            case 0 -> faceUv0[faceIndex];
            case 1 -> faceUv1[faceIndex];
            case 2 -> faceUv2[faceIndex];
            case 3 -> faceUv3[faceIndex];
            default -> throw new IllegalArgumentException("vertexIndex outside quad range: " + vertexIndex);
        };
    }

    public boolean faceTinted(int faceIndex) {
        validateFaceIndex(faceIndex);
        return faceTintFlags[faceIndex] != 0;
    }

    public boolean faceAlphaCutout(int faceIndex) {
        validateFaceIndex(faceIndex);
        return faceAlphaCutoutFlags[faceIndex] != 0;
    }

    public int faceRenderLayer(int faceIndex) {
        validateFaceIndex(faceIndex);
        return Byte.toUnsignedInt(faceRenderLayers[faceIndex]);
    }

    public int faceSourceBlockIndex(int faceIndex) {
        validateFaceIndex(faceIndex);
        return faceSourceBlockIndices[faceIndex];
    }

    public int faceTintIndex(int faceIndex) {
        validateFaceIndex(faceIndex);
        return faceTintIndices[faceIndex];
    }

    public int faceFluidTypeId(int faceIndex) {
        validateFaceIndex(faceIndex);
        return faceFluidTypeIds[faceIndex];
    }

    public int faceFluidFlowX(int faceIndex) {
        validateFaceIndex(faceIndex);
        return semanticShort(faceFluidFlowX[faceIndex]);
    }

    public int faceFluidFlowZ(int faceIndex) {
        validateFaceIndex(faceIndex);
        return semanticShort(faceFluidFlowZ[faceIndex]);
    }

    public int faceFluidOverlay(int faceIndex) {
        validateFaceIndex(faceIndex);
        return faceFluidOverlayFlags[faceIndex];
    }

    public int faceFluidHeight(int faceIndex, int vertexIndex) {
        validateFaceIndex(faceIndex);
        short value = switch (vertexIndex) {
            case 0 -> faceFluidHeight0[faceIndex];
            case 1 -> faceFluidHeight1[faceIndex];
            case 2 -> faceFluidHeight2[faceIndex];
            case 3 -> faceFluidHeight3[faceIndex];
            default -> throw new IllegalArgumentException("vertexIndex outside quad range: " + vertexIndex);
        };
        return semanticShort(value);
    }

    public List<SectionTriangleMesh> splitByAlphaCutout() {
        int alphaFaces = 0;
        for (byte flag : faceAlphaCutoutFlags) {
            if (flag != 0) {
                alphaFaces++;
            }
        }
        if (alphaFaces == 0 || alphaFaces == faceCount()) {
            return List.of(this);
        }

        List<SectionTriangleMesh> parts = new ArrayList<>(2);
        parts.add(copyFaces(false, faceCount() - alphaFaces));
        parts.add(copyFaces(true, alphaFaces));
        return List.copyOf(parts);
    }

    /**
     * Streams the geometry partitions needed by a Vulkan BLAS without cloning
     * face material, lighting, texture or UV payload. Those records remain
     * owned by this mesh's material-table path; duplicating them only to split
     * opaque from any-hit geometry was the dominant allocation source during
     * real 32-chunk streaming.
     */
    public void forEachTriangleGeometryByAlphaCutout(TriangleGeometryConsumer consumer) {
        Objects.requireNonNull(consumer, "consumer");
        int alphaFaces = 0;
        for (byte flag : faceAlphaCutoutFlags) {
            if (flag != 0) {
                alphaFaces++;
            }
        }
        if (alphaFaces == 0 || alphaFaces == faceCount()) {
            consumer.accept(vertexPositionsAsFloats(), indices, alphaFaces == 0);
            return;
        }
        emitTriangleGeometryPartition(consumer, false, faceCount() - alphaFaces);
        emitTriangleGeometryPartition(consumer, true, alphaFaces);
    }

    /**
     * Writes a bounded run of BLAS faces directly into native upload buffers.
     * The returned source-face cursor lets the caller continue with another
     * Vulkan update chunk without allocating a mesh-sized float/index copy.
     * Target indices are rebased to the complete selected partition, not the
     * transient upload chunk.
     */
    public int writeBlasGeometryFaces(
            boolean filterByAlphaCutout,
            boolean alphaCutout,
            int sourceFaceStart,
            int maxSelectedFaces,
            int targetFaceBase,
            FloatBuffer vertexTarget,
            IntBuffer indexTarget
    ) {
        Objects.requireNonNull(vertexTarget, "vertexTarget");
        Objects.requireNonNull(indexTarget, "indexTarget");
        if (sourceFaceStart < 0 || sourceFaceStart > faceCount()) {
            throw new IndexOutOfBoundsException("sourceFaceStart outside mesh: " + sourceFaceStart);
        }
        if (maxSelectedFaces <= 0 || targetFaceBase < 0) {
            throw new IllegalArgumentException("BLAS face limits and target base must be valid");
        }
        if ((long) vertexTarget.remaining()
                < (long) maxSelectedFaces * VERTICES_PER_FACE * COMPONENTS_PER_VERTEX
                || (long) indexTarget.remaining() < (long) maxSelectedFaces * INDICES_PER_FACE) {
            throw new IllegalArgumentException("BLAS upload buffers are smaller than the requested face run");
        }

        int sourceFace = sourceFaceStart;
        int writtenFaces = 0;
        while (sourceFace < faceCount() && writtenFaces < maxSelectedFaces) {
            int selectedSourceFace = sourceFace++;
            if (filterByAlphaCutout
                    && (faceAlphaCutoutFlags[selectedSourceFace] != 0) != alphaCutout) {
                continue;
            }
            int sourceVertexOffset = selectedSourceFace * VERTICES_PER_FACE * COMPONENTS_PER_VERTEX;
            for (int component = 0; component < VERTICES_PER_FACE * COMPONENTS_PER_VERTEX; component++) {
                vertexTarget.put(vertexPositions[sourceVertexOffset + component] / (float) POSITION_SCALE);
            }
            int sourceVertexBase = selectedSourceFace * VERTICES_PER_FACE;
            int targetVertexBase = Math.multiplyExact(
                    Math.addExact(targetFaceBase, writtenFaces),
                    VERTICES_PER_FACE
            );
            int sourceIndexOffset = selectedSourceFace * INDICES_PER_FACE;
            for (int index = 0; index < INDICES_PER_FACE; index++) {
                int localVertex = indices[sourceIndexOffset + index] - sourceVertexBase;
                if (localVertex < 0 || localVertex >= VERTICES_PER_FACE) {
                    throw new IllegalStateException("section face indices must reference their own four vertices");
                }
                indexTarget.put(targetVertexBase + localVertex);
            }
            writtenFaces++;
        }
        return sourceFace;
    }

    private void emitTriangleGeometryPartition(
            TriangleGeometryConsumer consumer,
            boolean alphaCutout,
            int copiedFaceCount
    ) {
        float[] copiedVertices = new float[copiedFaceCount * VERTICES_PER_FACE * COMPONENTS_PER_VERTEX];
        int[] copiedIndices = new int[copiedFaceCount * INDICES_PER_FACE];
        int targetFace = 0;
        for (int sourceFace = 0; sourceFace < faceCount(); sourceFace++) {
            if ((faceAlphaCutoutFlags[sourceFace] != 0) != alphaCutout) {
                continue;
            }
            int sourceVertexOffset = sourceFace * VERTICES_PER_FACE * COMPONENTS_PER_VERTEX;
            int targetVertexOffset = targetFace * VERTICES_PER_FACE * COMPONENTS_PER_VERTEX;
            for (int component = 0; component < VERTICES_PER_FACE * COMPONENTS_PER_VERTEX; component++) {
                copiedVertices[targetVertexOffset + component] =
                        vertexPositions[sourceVertexOffset + component] / (float) POSITION_SCALE;
            }
            int sourceVertexBase = sourceFace * VERTICES_PER_FACE;
            int targetVertexBase = targetFace * VERTICES_PER_FACE;
            int sourceIndexOffset = sourceFace * INDICES_PER_FACE;
            int targetIndexOffset = targetFace * INDICES_PER_FACE;
            for (int index = 0; index < INDICES_PER_FACE; index++) {
                int localVertex = indices[sourceIndexOffset + index] - sourceVertexBase;
                if (localVertex < 0 || localVertex >= VERTICES_PER_FACE) {
                    throw new IllegalStateException("section face indices must reference their own four vertices");
                }
                copiedIndices[targetIndexOffset + index] = targetVertexBase + localVertex;
            }
            targetFace++;
        }
        if (targetFace != copiedFaceCount) {
            throw new IllegalStateException("copied alpha-cutout face count mismatch");
        }
        consumer.accept(copiedVertices, copiedIndices, !alphaCutout);
    }

    @FunctionalInterface
    public interface TriangleGeometryConsumer {
        void accept(float[] vertexPositions, int[] indices, boolean opaque);
    }

    private SectionTriangleMesh copyFaces(boolean alphaCutout, int copiedFaceCount) {
        if (copiedFaceCount <= 0) {
            throw new IllegalArgumentException("copiedFaceCount must be positive");
        }

        short[] copiedVertices = new short[copiedFaceCount * VERTICES_PER_FACE * COMPONENTS_PER_VERTEX];
        int[] copiedIndices = new int[copiedFaceCount * INDICES_PER_FACE];
        int[] copiedVoxelStateIds = new int[copiedFaceCount];
        byte[] copiedFluidAmounts = new byte[copiedFaceCount];
        byte[] copiedDirections = new byte[copiedFaceCount];
        int[] copiedMapColors = new int[copiedFaceCount];
        int[] copiedVertexLighting0 = new int[copiedFaceCount];
        int[] copiedVertexLighting1 = new int[copiedFaceCount];
        int[] copiedVertexLighting2 = new int[copiedFaceCount];
        int[] copiedVertexLighting3 = new int[copiedFaceCount];
        byte[] copiedLightEmissions = new byte[copiedFaceCount];
        byte[] copiedMaterialFlags = new byte[copiedFaceCount];
        int[] copiedTextureIds = new int[copiedFaceCount];
        int[] copiedUv0 = new int[copiedFaceCount];
        int[] copiedUv1 = new int[copiedFaceCount];
        int[] copiedUv2 = new int[copiedFaceCount];
        int[] copiedUv3 = new int[copiedFaceCount];
        byte[] copiedTintFlags = new byte[copiedFaceCount];
        byte[] copiedAlphaCutoutFlags = new byte[copiedFaceCount];
        byte[] copiedRenderLayers = new byte[copiedFaceCount];
        short[] copiedSourceBlockIndices = new short[copiedFaceCount];
        int[] copiedTintIndices = new int[copiedFaceCount];
        int[] copiedFluidTypeIds = new int[copiedFaceCount];
        short[] copiedFluidFlowX = new short[copiedFaceCount];
        short[] copiedFluidFlowZ = new short[copiedFaceCount];
        byte[] copiedFluidOverlayFlags = new byte[copiedFaceCount];
        short[] copiedFluidHeight0 = new short[copiedFaceCount];
        short[] copiedFluidHeight1 = new short[copiedFaceCount];
        short[] copiedFluidHeight2 = new short[copiedFaceCount];
        short[] copiedFluidHeight3 = new short[copiedFaceCount];

        int targetFace = 0;
        for (int sourceFace = 0; sourceFace < faceCount(); sourceFace++) {
            boolean sourceAlphaCutout = faceAlphaCutoutFlags[sourceFace] != 0;
            if (sourceAlphaCutout != alphaCutout) {
                continue;
            }

            int sourceVertexOffset = sourceFace * VERTICES_PER_FACE * COMPONENTS_PER_VERTEX;
            int targetVertexOffset = targetFace * VERTICES_PER_FACE * COMPONENTS_PER_VERTEX;
            System.arraycopy(
                    vertexPositions,
                    sourceVertexOffset,
                    copiedVertices,
                    targetVertexOffset,
                    VERTICES_PER_FACE * COMPONENTS_PER_VERTEX
            );

            int sourceVertexBase = sourceFace * VERTICES_PER_FACE;
            int targetVertexBase = targetFace * VERTICES_PER_FACE;
            int sourceIndexOffset = sourceFace * INDICES_PER_FACE;
            int targetIndexOffset = targetFace * INDICES_PER_FACE;
            for (int index = 0; index < INDICES_PER_FACE; index++) {
                int sourceVertex = indices[sourceIndexOffset + index];
                int localVertex = sourceVertex - sourceVertexBase;
                if (localVertex < 0 || localVertex >= VERTICES_PER_FACE) {
                    throw new IllegalStateException("section face indices must reference their own four vertices");
                }
                copiedIndices[targetIndexOffset + index] = targetVertexBase + localVertex;
            }

            copiedVoxelStateIds[targetFace] = faceVoxelStateIds[sourceFace];
            copiedFluidAmounts[targetFace] = faceFluidAmounts[sourceFace];
            copiedDirections[targetFace] = faceDirections[sourceFace];
            copiedMapColors[targetFace] = faceMapColors[sourceFace];
            copiedVertexLighting0[targetFace] = faceVertexLighting0[sourceFace];
            copiedVertexLighting1[targetFace] = faceVertexLighting1[sourceFace];
            copiedVertexLighting2[targetFace] = faceVertexLighting2[sourceFace];
            copiedVertexLighting3[targetFace] = faceVertexLighting3[sourceFace];
            copiedLightEmissions[targetFace] = faceLightEmissions[sourceFace];
            copiedMaterialFlags[targetFace] = faceMaterialFlags[sourceFace];
            copiedTextureIds[targetFace] = faceTextureIds[sourceFace];
            copiedUv0[targetFace] = faceUv0[sourceFace];
            copiedUv1[targetFace] = faceUv1[sourceFace];
            copiedUv2[targetFace] = faceUv2[sourceFace];
            copiedUv3[targetFace] = faceUv3[sourceFace];
            copiedTintFlags[targetFace] = faceTintFlags[sourceFace];
            copiedAlphaCutoutFlags[targetFace] = faceAlphaCutoutFlags[sourceFace];
            copiedRenderLayers[targetFace] = faceRenderLayers[sourceFace];
            copiedSourceBlockIndices[targetFace] = faceSourceBlockIndices[sourceFace];
            copiedTintIndices[targetFace] = faceTintIndices[sourceFace];
            copiedFluidTypeIds[targetFace] = faceFluidTypeIds[sourceFace];
            copiedFluidFlowX[targetFace] = faceFluidFlowX[sourceFace];
            copiedFluidFlowZ[targetFace] = faceFluidFlowZ[sourceFace];
            copiedFluidOverlayFlags[targetFace] = faceFluidOverlayFlags[sourceFace];
            copiedFluidHeight0[targetFace] = faceFluidHeight0[sourceFace];
            copiedFluidHeight1[targetFace] = faceFluidHeight1[sourceFace];
            copiedFluidHeight2[targetFace] = faceFluidHeight2[sourceFace];
            copiedFluidHeight3[targetFace] = faceFluidHeight3[sourceFace];
            targetFace++;
        }

        if (targetFace != copiedFaceCount) {
            throw new IllegalStateException("copied alpha-cutout face count mismatch");
        }
        return new SectionTriangleMesh(
                key,
                copiedVertices,
                copiedIndices,
                copiedVoxelStateIds,
                copiedFluidAmounts,
                copiedDirections,
                copiedMapColors,
                copiedVertexLighting0,
                copiedVertexLighting1,
                copiedVertexLighting2,
                copiedVertexLighting3,
                copiedLightEmissions,
                copiedMaterialFlags,
                copiedTextureIds,
                copiedUv0,
                copiedUv1,
                copiedUv2,
                copiedUv3,
                copiedTintFlags,
                copiedAlphaCutoutFlags,
                copiedRenderLayers,
                copiedSourceBlockIndices,
                copiedTintIndices,
                copiedFluidTypeIds,
                copiedFluidFlowX,
                copiedFluidFlowZ,
                copiedFluidOverlayFlags,
                copiedFluidHeight0,
                copiedFluidHeight1,
                copiedFluidHeight2,
                copiedFluidHeight3
        );
    }

    public float[] vertexPositionsAsFloats() {
        float[] positions = new float[vertexPositions.length];
        for (int index = 0; index < vertexPositions.length; index++) {
            positions[index] = vertexPositions[index] / (float) POSITION_SCALE;
        }
        return positions;
    }

    @Override
    public short[] vertexPositions() {
        return Arrays.copyOf(vertexPositions, vertexPositions.length);
    }

    @Override
    public int[] indices() {
        return Arrays.copyOf(indices, indices.length);
    }

    @Override
    public int[] faceVoxelStateIds() {
        return Arrays.copyOf(faceVoxelStateIds, faceVoxelStateIds.length);
    }

    @Override
    public byte[] faceFluidAmounts() {
        return Arrays.copyOf(faceFluidAmounts, faceFluidAmounts.length);
    }

    @Override
    public byte[] faceDirections() {
        return Arrays.copyOf(faceDirections, faceDirections.length);
    }

    @Override
    public int[] faceMapColors() {
        return Arrays.copyOf(faceMapColors, faceMapColors.length);
    }

    @Override
    public int[] faceVertexLighting0() {
        return Arrays.copyOf(faceVertexLighting0, faceVertexLighting0.length);
    }

    @Override
    public int[] faceVertexLighting1() {
        return Arrays.copyOf(faceVertexLighting1, faceVertexLighting1.length);
    }

    @Override
    public int[] faceVertexLighting2() {
        return Arrays.copyOf(faceVertexLighting2, faceVertexLighting2.length);
    }

    @Override
    public int[] faceVertexLighting3() {
        return Arrays.copyOf(faceVertexLighting3, faceVertexLighting3.length);
    }

    @Override
    public byte[] faceLightEmissions() {
        return Arrays.copyOf(faceLightEmissions, faceLightEmissions.length);
    }

    @Override
    public byte[] faceMaterialFlags() {
        return Arrays.copyOf(faceMaterialFlags, faceMaterialFlags.length);
    }

    @Override
    public int[] faceTextureIds() {
        return Arrays.copyOf(faceTextureIds, faceTextureIds.length);
    }

    @Override
    public int[] faceUv0() {
        return Arrays.copyOf(faceUv0, faceUv0.length);
    }

    @Override
    public int[] faceUv1() {
        return Arrays.copyOf(faceUv1, faceUv1.length);
    }

    @Override
    public int[] faceUv2() {
        return Arrays.copyOf(faceUv2, faceUv2.length);
    }

    @Override
    public int[] faceUv3() {
        return Arrays.copyOf(faceUv3, faceUv3.length);
    }

    @Override
    public byte[] faceTintFlags() {
        return Arrays.copyOf(faceTintFlags, faceTintFlags.length);
    }

    @Override
    public byte[] faceAlphaCutoutFlags() {
        return Arrays.copyOf(faceAlphaCutoutFlags, faceAlphaCutoutFlags.length);
    }

    @Override
    public byte[] faceRenderLayers() {
        return Arrays.copyOf(faceRenderLayers, faceRenderLayers.length);
    }

    @Override
    public short[] faceSourceBlockIndices() {
        return Arrays.copyOf(faceSourceBlockIndices, faceSourceBlockIndices.length);
    }

    @Override
    public int[] faceTintIndices() {
        return Arrays.copyOf(faceTintIndices, faceTintIndices.length);
    }

    @Override
    public int[] faceFluidTypeIds() {
        return Arrays.copyOf(faceFluidTypeIds, faceFluidTypeIds.length);
    }

    @Override
    public short[] faceFluidFlowX() {
        return Arrays.copyOf(faceFluidFlowX, faceFluidFlowX.length);
    }

    @Override
    public short[] faceFluidFlowZ() {
        return Arrays.copyOf(faceFluidFlowZ, faceFluidFlowZ.length);
    }

    @Override
    public byte[] faceFluidOverlayFlags() {
        return Arrays.copyOf(faceFluidOverlayFlags, faceFluidOverlayFlags.length);
    }

    @Override
    public short[] faceFluidHeight0() {
        return Arrays.copyOf(faceFluidHeight0, faceFluidHeight0.length);
    }

    @Override
    public short[] faceFluidHeight1() {
        return Arrays.copyOf(faceFluidHeight1, faceFluidHeight1.length);
    }

    @Override
    public short[] faceFluidHeight2() {
        return Arrays.copyOf(faceFluidHeight2, faceFluidHeight2.length);
    }

    @Override
    public short[] faceFluidHeight3() {
        return Arrays.copyOf(faceFluidHeight3, faceFluidHeight3.length);
    }

    private static short[] unknownSourceBlockIndices(int faceCount) {
        short[] indices = new short[faceCount];
        Arrays.fill(indices, UNKNOWN_SOURCE_BLOCK_INDEX);
        return indices;
    }

    private static void validateSourceBlockIndices(short[] indices) {
        for (short index : indices) {
            if (index < UNKNOWN_SOURCE_BLOCK_INDEX || index >= SectionVoxelSnapshot.BLOCKS_PER_SECTION) {
                throw new IllegalArgumentException("source block index outside section: " + index);
            }
        }
    }

    private static void validateSemanticFields(
            int[] tintIndices,
            int[] mediumTypeIds,
            short[] flowX,
            short[] flowZ,
            byte[] overlays,
            short[] height0,
            short[] height1,
            short[] height2,
            short[] height3
    ) {
        for (int index = 0; index < tintIndices.length; index++) {
            if (tintIndices[index] < NO_TINT_INDEX && tintIndices[index] != UNKNOWN_TINT_INDEX) {
                throw new IllegalArgumentException("face tint index must be non-negative, NO_TINT, or UNKNOWN: "
                        + tintIndices[index]);
            }
            if (mediumTypeIds[index] < UNKNOWN_FLUID_TYPE_ID) {
                throw new IllegalArgumentException("face fluid type must be non-negative or UNKNOWN: " + mediumTypeIds[index]);
            }
            validateFlow(flowX[index], "faceFluidFlowX");
            validateFlow(flowZ[index], "faceFluidFlowZ");
            if (overlays[index] < UNKNOWN_FLUID_OVERLAY || overlays[index] > 1) {
                throw new IllegalArgumentException("face fluid overlay must be UNKNOWN, false, or true");
            }
            validateHeight(height0[index]);
            validateHeight(height1[index]);
            validateHeight(height2[index]);
            validateHeight(height3[index]);
        }
    }

    private static void validateFlow(short value, String name) {
        if (value != UNKNOWN_FLUID_SEMANTIC && (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE)) {
            throw new IllegalArgumentException(name + " must be a signed byte or UNKNOWN: " + value);
        }
    }

    private static void validateHeight(short value) {
        if (value != UNKNOWN_FLUID_SEMANTIC && (value < 0 || value > SectionFace.FLUID_HEIGHT_SCALE)) {
            throw new IllegalArgumentException("face fluid height must be in [0, "
                    + SectionFace.FLUID_HEIGHT_SCALE + "] or UNKNOWN: " + value);
        }
    }

    private static int semanticShort(short value) {
        return value == UNKNOWN_FLUID_SEMANTIC ? Integer.MIN_VALUE : value;
    }

    private static byte[] defaultRenderLayers(byte[] materialFlags, byte[] alphaCutoutFlags) {
        if (materialFlags.length != alphaCutoutFlags.length) {
            throw new IllegalArgumentException("material and alpha-cutout arrays must have equal length");
        }
        byte[] layers = new byte[materialFlags.length];
        for (int index = 0; index < layers.length; index++) {
            if ((Byte.toUnsignedInt(materialFlags[index]) & SectionVoxelSnapshot.FLAG_LIQUID) != 0) {
                layers[index] = (byte) RtTextureCatalog.RENDER_LAYER_TRANSLUCENT;
            } else if (alphaCutoutFlags[index] != 0) {
                layers[index] = (byte) RtTextureCatalog.RENDER_LAYER_CUTOUT;
            } else {
                layers[index] = (byte) RtTextureCatalog.RENDER_LAYER_SOLID;
            }
        }
        return layers;
    }

    private static void validateRenderLayers(byte[] layers, byte[] alphaCutoutFlags) {
        for (int index = 0; index < layers.length; index++) {
            int layer = Byte.toUnsignedInt(layers[index]);
            if (layer < RtTextureCatalog.RENDER_LAYER_SOLID
                    || layer > RtTextureCatalog.RENDER_LAYER_TRANSLUCENT) {
                throw new IllegalArgumentException("face render layer outside SOLID/CUTOUT/TRANSLUCENT: " + layer);
            }
            if (alphaCutoutFlags[index] != 0 && layer != RtTextureCatalog.RENDER_LAYER_CUTOUT) {
                throw new IllegalArgumentException("alpha-cutout face must use CUTOUT render layer");
            }
        }
    }

    private static short[] copyShortArray(short[] source, String name) {
        Objects.requireNonNull(source, name);
        return transferOrCopy(source);
    }

    private static short[] copyShortArray(short[] source, String name, int expectedLength) {
        Objects.requireNonNull(source, name);
        if (source.length != expectedLength) {
            throw new IllegalArgumentException(name + " length must be " + expectedLength + ", got " + source.length);
        }
        return transferOrCopy(source);
    }

    private static int[] copyIntArray(int[] source, String name) {
        Objects.requireNonNull(source, name);
        return transferOrCopy(source);
    }

    private static int[] copyIntArray(int[] source, String name, int expectedLength) {
        Objects.requireNonNull(source, name);
        if (source.length != expectedLength) {
            throw new IllegalArgumentException(name + " length must be " + expectedLength + ", got " + source.length);
        }
        return transferOrCopy(source);
    }

    private static byte[] copyByteArray(byte[] source, String name, int expectedLength) {
        Objects.requireNonNull(source, name);
        if (source.length != expectedLength) {
            throw new IllegalArgumentException(name + " length must be " + expectedLength + ", got " + source.length);
        }
        return transferOrCopy(source);
    }

    private static short[] transferOrCopy(short[] source) {
        OwnedArrayTransfer transfer = OWNED_ARRAY_TRANSFER.get();
        return transfer != null && transfer.take(source) ? source : Arrays.copyOf(source, source.length);
    }

    private static int[] transferOrCopy(int[] source) {
        OwnedArrayTransfer transfer = OWNED_ARRAY_TRANSFER.get();
        return transfer != null && transfer.take(source) ? source : Arrays.copyOf(source, source.length);
    }

    private static byte[] transferOrCopy(byte[] source) {
        OwnedArrayTransfer transfer = OWNED_ARRAY_TRANSFER.get();
        return transfer != null && transfer.take(source) ? source : Arrays.copyOf(source, source.length);
    }

    private static final class OwnedArrayTransfer {
        private final IdentityHashMap<Object, Boolean> pending = new IdentityHashMap<>();

        private OwnedArrayTransfer(Object... arrays) {
            for (Object array : arrays) {
                if (pending.put(array, Boolean.TRUE) != null) {
                    throw new IllegalArgumentException("owned mesh arrays must not share backing storage");
                }
            }
        }

        private boolean take(Object array) {
            return pending.remove(array) != null;
        }
    }

    private static void validateIndices(int[] indices, int vertexCount) {
        for (int index : indices) {
            if (index < 0 || index >= vertexCount) {
                throw new IllegalArgumentException("triangle index outside vertex buffer: " + index);
            }
        }
    }

    private static void validateTextureIds(int[] textureIds) {
        for (int textureId : textureIds) {
            if (textureId < 0) {
                throw new IllegalArgumentException("face texture id must not be negative: " + textureId);
            }
        }
    }

    private void validateFaceIndex(int faceIndex) {
        if (faceIndex < 0 || faceIndex >= faceCount()) {
            throw new IllegalArgumentException("faceIndex outside mesh range: " + faceIndex);
        }
    }

    private static byte[] defaultMaterialFlags(int[] voxelTypeIds, byte[] mediumAmounts) {
        Objects.requireNonNull(voxelTypeIds, "voxelTypeIds");
        Objects.requireNonNull(mediumAmounts, "mediumAmounts");
        if (voxelTypeIds.length != mediumAmounts.length) {
            throw new IllegalArgumentException("legacy face material arrays must have matching lengths");
        }
        byte[] flags = new byte[voxelTypeIds.length];
        for (int index = 0; index < flags.length; index++) {
            if (voxelTypeIds[index] != 0 || Byte.toUnsignedInt(mediumAmounts[index]) > 0) {
                flags[index] = SectionVoxelSnapshot.FLAG_RENDER_SHAPE_VISIBLE;
            } else {
                flags[index] = SectionVoxelSnapshot.FLAG_AIR;
            }
        }
        return flags;
    }

    private static int[] defaultTextureIds(int faceCount) {
        return new int[faceCount];
    }

    private static int[] unknownIntValues(int faceCount, int unknownValue) {
        int[] values = new int[faceCount];
        Arrays.fill(values, unknownValue);
        return values;
    }

    private static short[] unknownShortValues(int faceCount) {
        short[] values = new short[faceCount];
        Arrays.fill(values, UNKNOWN_FLUID_SEMANTIC);
        return values;
    }

    private static byte[] unknownOverlayValues(int faceCount) {
        byte[] values = new byte[faceCount];
        Arrays.fill(values, UNKNOWN_FLUID_OVERLAY);
        return values;
    }

    private static int[] defaultVertexLighting(int faceCount) {
        int[] values = new int[faceCount];
        Arrays.fill(values, PackedVoxelLighting.packVertex(
                PackedVoxelLighting.SMOOTH_LIGHT_MAX,
                PackedVoxelLighting.SMOOTH_LIGHT_MAX,
                1.0F
        ));
        return values;
    }

    private static int[] defaultVertexLighting(int[] faceMapColors, byte[] faceDirections, int vertexIndex) {
        Objects.requireNonNull(faceMapColors, "faceMapColors");
        Objects.requireNonNull(faceDirections, "faceDirections");
        if (faceMapColors.length != faceDirections.length) {
            throw new IllegalArgumentException("legacy vertex lighting defaults require matching face arrays");
        }
        if (vertexIndex < 0 || vertexIndex >= VERTICES_PER_FACE) {
            throw new IllegalArgumentException("vertexIndex outside quad range: " + vertexIndex);
        }
        int[] values = new int[faceMapColors.length];
        for (int face = 0; face < faceMapColors.length; face++) {
            int directionOrdinal = Byte.toUnsignedInt(faceDirections[face]);
            FaceDirection direction = directionOrdinal >= 0 && directionOrdinal < FaceDirection.values().length
                    ? FaceDirection.values()[directionOrdinal]
                    : FaceDirection.POSITIVE_Y;
            values[face] = PackedVoxelLighting.packFlatVertex(faceMapColors[face], direction);
        }
        return values;
    }

    private static int[] defaultUv0(int faceCount) {
        int[] values = new int[faceCount];
        Arrays.fill(values, packUv16(0.0F, 0.0F));
        return values;
    }

    private static int[] defaultUv1(int faceCount) {
        int[] values = new int[faceCount];
        Arrays.fill(values, packUv16(1.0F, 0.0F));
        return values;
    }

    private static int[] defaultUv2(int faceCount) {
        int[] values = new int[faceCount];
        Arrays.fill(values, packUv16(1.0F, 1.0F));
        return values;
    }

    private static int[] defaultUv3(int faceCount) {
        int[] values = new int[faceCount];
        Arrays.fill(values, packUv16(0.0F, 1.0F));
        return values;
    }

    /** Keeps the geometry staging type independent from the texture catalog's static runtime. */
    private static int packUv16(float u, float v) {
        int packedU = Math.round(Math.max(0.0F, Math.min(1.0F, u)) * 65535.0F) & 0xFFFF;
        int packedV = Math.round(Math.max(0.0F, Math.min(1.0F, v)) * 65535.0F) & 0xFFFF;
        return packedU | packedV << 16;
    }
}
