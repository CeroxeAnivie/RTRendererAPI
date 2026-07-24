package top.ceroxe.mcvulkanrt.renderer.api;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Objects;

/** Immutable indexed triangle mesh with generic vertex attributes and per-triangle materials. */
public final class MeshAsset {
    private final long id;
    private final float[] positions;
    private final float[] normals;
    private final float[] tangents;
    private final float[] textureCoordinates;
    private final float[] lightmapCoordinates;
    private final int[] vertexColorsRgba8;
    private final int[] triangleIndices;
    private final long[] triangleMaterialIds;

    public MeshAsset(
            long id,
            float[] positions,
            float[] normals,
            float[] tangents,
            float[] textureCoordinates,
            int[] vertexColorsRgba8,
            int[] triangleIndices,
            long[] triangleMaterialIds
    ) {
        this(
                id, positions, normals, tangents, textureCoordinates, new float[0],
                vertexColorsRgba8, triangleIndices, triangleMaterialIds
        );
    }

    public MeshAsset(
            long id,
            float[] positions,
            float[] normals,
            float[] tangents,
            float[] textureCoordinates,
            float[] lightmapCoordinates,
            int[] vertexColorsRgba8,
            int[] triangleIndices,
            long[] triangleMaterialIds
    ) {
        MaterialAsset.requireId(id, "id");
        this.positions = cloneRequired(positions, "positions");
        this.normals = cloneRequired(normals, "normals");
        this.tangents = cloneRequired(tangents, "tangents");
        this.textureCoordinates = cloneRequired(textureCoordinates, "textureCoordinates");
        this.lightmapCoordinates = cloneRequired(lightmapCoordinates, "lightmapCoordinates");
        this.vertexColorsRgba8 = cloneRequired(vertexColorsRgba8, "vertexColorsRgba8");
        this.triangleIndices = cloneRequired(triangleIndices, "triangleIndices");
        this.triangleMaterialIds = cloneRequired(triangleMaterialIds, "triangleMaterialIds");
        this.id = id;

        if (this.positions.length == 0 || this.positions.length % 3 != 0) {
            throw new IllegalArgumentException("positions must contain one or more xyz vertices");
        }
        int vertexCount = this.positions.length / 3;
        if (this.normals.length != 0 && this.normals.length != this.positions.length) {
            throw new IllegalArgumentException("normals must be empty or contain one xyz value per vertex");
        }
        if (this.tangents.length != 0 && this.tangents.length != vertexCount * 4) {
            throw new IllegalArgumentException("tangents must be empty or contain one xyzw value per vertex");
        }
        if (this.textureCoordinates.length != 0 && this.textureCoordinates.length != vertexCount * 2) {
            throw new IllegalArgumentException("texture coordinates must be empty or contain one uv value per vertex");
        }
        if (this.lightmapCoordinates.length != 0 && this.lightmapCoordinates.length != vertexCount * 2) {
            throw new IllegalArgumentException("lightmap coordinates must be empty or contain one uv value per vertex");
        }
        if (this.vertexColorsRgba8.length != 0 && this.vertexColorsRgba8.length != vertexCount) {
            throw new IllegalArgumentException("vertex colors must be empty or contain one value per vertex");
        }
        if (this.triangleIndices.length == 0 || this.triangleIndices.length % 3 != 0) {
            throw new IllegalArgumentException("triangle indices must contain one or more triangles");
        }
        if (this.triangleMaterialIds.length != this.triangleIndices.length / 3) {
            throw new IllegalArgumentException("triangle material count must match triangle count");
        }
        requireFinite(this.positions, "positions");
        requireFinite(this.normals, "normals");
        requireFinite(this.tangents, "tangents");
        requireFinite(this.textureCoordinates, "textureCoordinates");
        requireFinite(this.lightmapCoordinates, "lightmapCoordinates");
        for (int index : this.triangleIndices) {
            if (index < 0 || index >= vertexCount) {
                throw new IllegalArgumentException("triangle index is outside the vertex array: " + index);
            }
        }
        for (long materialId : this.triangleMaterialIds) {
            MaterialAsset.requireId(materialId, "triangle material id");
        }
    }

    public long id() { return id; }
    public int vertexCount() { return positions.length / 3; }
    public int triangleCount() { return triangleIndices.length / 3; }
    public FloatBuffer positions() { return FloatBuffer.wrap(positions).asReadOnlyBuffer(); }
    public FloatBuffer normals() { return FloatBuffer.wrap(normals).asReadOnlyBuffer(); }
    public FloatBuffer tangents() { return FloatBuffer.wrap(tangents).asReadOnlyBuffer(); }
    public FloatBuffer textureCoordinates() { return FloatBuffer.wrap(textureCoordinates).asReadOnlyBuffer(); }
    public FloatBuffer lightmapCoordinates() { return FloatBuffer.wrap(lightmapCoordinates).asReadOnlyBuffer(); }
    public IntBuffer vertexColorsRgba8() { return IntBuffer.wrap(vertexColorsRgba8).asReadOnlyBuffer(); }
    public IntBuffer triangleIndices() { return IntBuffer.wrap(triangleIndices).asReadOnlyBuffer(); }
    public LongBuffer triangleMaterialIds() { return LongBuffer.wrap(triangleMaterialIds).asReadOnlyBuffer(); }

    private static float[] cloneRequired(float[] values, String name) {
        return Objects.requireNonNull(values, name).clone();
    }

    private static int[] cloneRequired(int[] values, String name) {
        return Objects.requireNonNull(values, name).clone();
    }

    private static long[] cloneRequired(long[] values, String name) {
        return Objects.requireNonNull(values, name).clone();
    }

    private static void requireFinite(float[] values, String name) {
        for (float value : values) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException(name + " must contain only finite values");
            }
        }
    }
}
