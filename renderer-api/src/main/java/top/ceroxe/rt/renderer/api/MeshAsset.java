package top.ceroxe.rt.renderer.api;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable indexed triangle mesh with generic vertex attributes and per-triangle materials.
 *
 * <p>The semantic {@link Builder} is the safe default: it defensively copies caller data when
 * building the asset. Advanced integrations that already own immutable off-heap assets can use
 * {@link #wrapImmutableDirect} to avoid that copy under an explicit ownership contract.</p>
 */
public final class MeshAsset {
    private final long id;
    private final FloatBuffer positions;
    private final FloatBuffer normals;
    private final FloatBuffer tangents;
    private final FloatBuffer textureCoordinates;
    private final FloatBuffer lightmapCoordinates;
    private final IntBuffer vertexColorsRgba8;
    private final IntBuffer triangleIndices;
    private final LongBuffer triangleMaterialIds;

    private MeshAsset(
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
        this(
                id,
                copied(positions, "positions"),
                copied(normals, "normals"),
                copied(tangents, "tangents"),
                copied(textureCoordinates, "textureCoordinates"),
                copied(lightmapCoordinates, "lightmapCoordinates"),
                copied(vertexColorsRgba8, "vertexColorsRgba8"),
                copied(triangleIndices, "triangleIndices"),
                copied(triangleMaterialIds, "triangleMaterialIds"),
                false
        );
    }

    private MeshAsset(
            long id,
            FloatBuffer positions,
            FloatBuffer normals,
            FloatBuffer tangents,
            FloatBuffer textureCoordinates,
            FloatBuffer lightmapCoordinates,
            IntBuffer vertexColorsRgba8,
            IntBuffer triangleIndices,
            LongBuffer triangleMaterialIds,
            boolean requireImmutableDirect
    ) {
        MaterialAsset.requireId(id, "id");
        this.id = id;
        this.positions = captured(positions, "positions", requireImmutableDirect);
        this.normals = captured(normals, "normals", requireImmutableDirect);
        this.tangents = captured(tangents, "tangents", requireImmutableDirect);
        this.textureCoordinates = captured(textureCoordinates, "textureCoordinates", requireImmutableDirect);
        this.lightmapCoordinates = captured(lightmapCoordinates, "lightmapCoordinates", requireImmutableDirect);
        this.vertexColorsRgba8 = captured(vertexColorsRgba8, "vertexColorsRgba8", requireImmutableDirect);
        this.triangleIndices = captured(triangleIndices, "triangleIndices", requireImmutableDirect);
        this.triangleMaterialIds = captured(triangleMaterialIds, "triangleMaterialIds", requireImmutableDirect);
        validate();
    }

    /**
     * Starts a safely copied mesh builder with the complete required topology.
     *
     * <p>Positions, indices, and per-triangle material identifiers are mandatory because no
     * meaningful mesh generation exists without them. Optional vertex attributes are selected by
     * name, preventing adjacent arrays with similar element types from being silently swapped.</p>
     *
     * @param id                  stable non-negative mesh identifier
     * @param positions           required xyz position array
     * @param triangleIndices     triangle vertex indices in groups of three
     * @param triangleMaterialIds one material identifier per triangle
     * @return new single-thread-confined mesh builder
     * @throws IllegalArgumentException if {@code id} is negative
     * @throws NullPointerException     if a required array is {@code null}
     */
    public static Builder builder(
            long id,
            float[] positions,
            int[] triangleIndices,
            long[] triangleMaterialIds
    ) {
        return new Builder(id, positions, triangleIndices, triangleMaterialIds);
    }

    /**
     * Creates a basic mesh with one material and safe defaults for optional attributes.
     *
     * <p>This is the recommended entry point for callers that only have triangle positions and
     * indices. Normals, tangents, texture coordinates, lightmaps, and vertex colors remain absent;
     * the renderer applies its documented fallback behavior.</p>
     *
     * @param id              stable non-negative mesh identifier
     * @param positions       xyz positions
     * @param triangleIndices triangle indices in groups of three
     * @param materialId      material used by every triangle
     * @return defensively copied mesh
     */
    public static MeshAsset triangles(long id, float[] positions, int[] triangleIndices, long materialId) {
        Objects.requireNonNull(triangleIndices, "triangleIndices");
        if (triangleIndices.length == 0 || triangleIndices.length % 3 != 0) {
            throw new IllegalArgumentException("triangle indices must contain one or more triangles");
        }
        MaterialAsset.requireId(materialId, "materialId");
        long[] materialIds = new long[triangleIndices.length / 3];
        Arrays.fill(materialIds, materialId);
        return builder(id, positions, triangleIndices, materialIds).build();
    }

    /**
     * Creates a mesh with lightmap coordinates without copying immutable off-heap storage.
     *
     * <p>Every non-empty input must be a read-only direct buffer. The asset captures precisely the
     * current {@code position..limit} range as a zero-based view and retains its backing memory.
     * Empty optional buffers need only be read-only. The caller must permanently relinquish every
     * writable alias and keep the external allocator alive for at least as long as the asset.</p>
     *
     * @param id                  stable non-negative mesh identifier
     * @param positions           required xyz positions
     * @param normals             empty or one xyz normal per vertex
     * @param tangents            empty or one xyzw tangent per vertex
     * @param textureCoordinates  empty or one uv pair per vertex
     * @param lightmapCoordinates empty or one lightmap uv pair per vertex
     * @param vertexColorsRgba8   empty or one packed RGBA8 value per vertex; channels remain raw
     *                            numeric authored data and are not implicitly sRGB-decoded
     * @param triangleIndices     triangle vertex indices in groups of three
     * @param triangleMaterialIds one material identifier per triangle
     * @return mesh retaining immutable direct storage without copying it
     * @throws IllegalArgumentException if a non-empty buffer is not direct and read-only, or if
     *                                  shapes, indices, identifiers, or values are invalid
     * @throws NullPointerException     if any buffer is {@code null}
     */
    public static MeshAsset wrapImmutableDirect(
            long id,
            FloatBuffer positions,
            FloatBuffer normals,
            FloatBuffer tangents,
            FloatBuffer textureCoordinates,
            FloatBuffer lightmapCoordinates,
            IntBuffer vertexColorsRgba8,
            IntBuffer triangleIndices,
            LongBuffer triangleMaterialIds
    ) {
        return new MeshAsset(
                id, positions, normals, tangents, textureCoordinates, lightmapCoordinates,
                vertexColorsRgba8, triangleIndices, triangleMaterialIds, true
        );
    }

    private static void requireOptionalSize(int actual, int expected, String message) {
        if (actual != 0 && actual != expected) throw new IllegalArgumentException(message);
    }

    private static FloatBuffer copied(float[] values, String name) {
        return FloatBuffer.wrap(Objects.requireNonNull(values, name).clone()).asReadOnlyBuffer();
    }

    private static IntBuffer copied(int[] values, String name) {
        return IntBuffer.wrap(Objects.requireNonNull(values, name).clone()).asReadOnlyBuffer();
    }

    private static LongBuffer copied(long[] values, String name) {
        return LongBuffer.wrap(Objects.requireNonNull(values, name).clone()).asReadOnlyBuffer();
    }

    private static float[] copiedArray(FloatBuffer values) {
        FloatBuffer source = values.duplicate();
        float[] copy = new float[source.remaining()];
        source.get(copy);
        return copy;
    }

    private static int[] copiedArray(IntBuffer values) {
        IntBuffer source = values.duplicate();
        int[] copy = new int[source.remaining()];
        source.get(copy);
        return copy;
    }

    private static long[] copiedArray(LongBuffer values) {
        LongBuffer source = values.duplicate();
        long[] copy = new long[source.remaining()];
        source.get(copy);
        return copy;
    }

    private static FloatBuffer captured(FloatBuffer values, String name, boolean requireImmutableDirect) {
        Objects.requireNonNull(values, name);
        requireStorageContract(values.hasRemaining(), values.isDirect(), values.isReadOnly(), name,
                requireImmutableDirect);
        return values.slice().asReadOnlyBuffer();
    }

    private static IntBuffer captured(IntBuffer values, String name, boolean requireImmutableDirect) {
        Objects.requireNonNull(values, name);
        requireStorageContract(values.hasRemaining(), values.isDirect(), values.isReadOnly(), name,
                requireImmutableDirect);
        return values.slice().asReadOnlyBuffer();
    }

    private static LongBuffer captured(LongBuffer values, String name, boolean requireImmutableDirect) {
        Objects.requireNonNull(values, name);
        requireStorageContract(values.hasRemaining(), values.isDirect(), values.isReadOnly(), name,
                requireImmutableDirect);
        return values.slice().asReadOnlyBuffer();
    }

    private static void requireStorageContract(
            boolean nonEmpty, boolean direct, boolean readOnly, String name, boolean required
    ) {
        if (required && (!readOnly || (nonEmpty && !direct))) {
            throw new IllegalArgumentException(name + " must be read-only and direct when non-empty");
        }
    }

    private static void requireFinite(FloatBuffer values, String name) {
        FloatBuffer source = values.duplicate();
        while (source.hasRemaining()) {
            if (!Float.isFinite(source.get())) {
                throw new IllegalArgumentException(name + " must contain only finite values");
            }
        }
    }

    private static FloatBuffer emptyFloats() {
        return ByteBuffer.allocateDirect(0).asFloatBuffer().asReadOnlyBuffer();
    }

    /**
     * Starts an independent, safely copied builder initialized from this complete mesh generation.
     *
     * <p>The returned builder never aliases this asset, including when this asset retains direct
     * storage supplied through {@link #wrapImmutableDirect}.</p>
     *
     * @return new builder containing every current mesh attribute
     */
    public Builder toBuilder() {
        return builder(
                id,
                copiedArray(positions),
                copiedArray(triangleIndices),
                copiedArray(triangleMaterialIds)
        )
                .normals(copiedArray(normals))
                .tangents(copiedArray(tangents))
                .textureCoordinates(copiedArray(textureCoordinates))
                .lightmapCoordinates(copiedArray(lightmapCoordinates))
                .vertexColorsRgba8(copiedArray(vertexColorsRgba8));
    }

    private void validate() {
        int positionCount = positions.remaining();
        if (positionCount == 0 || positionCount % 3 != 0) {
            throw new IllegalArgumentException("positions must contain one or more xyz vertices");
        }
        int vertexCount = positionCount / 3;
        requireOptionalSize(normals.remaining(), positionCount,
                "normals must be empty or contain one xyz value per vertex");
        requireOptionalSize(tangents.remaining(), Math.multiplyExact(vertexCount, 4),
                "tangents must be empty or contain one xyzw tangent per vertex");
        requireOptionalSize(textureCoordinates.remaining(), Math.multiplyExact(vertexCount, 2),
                "texture coordinates must be empty or contain one uv value per vertex");
        requireOptionalSize(lightmapCoordinates.remaining(), Math.multiplyExact(vertexCount, 2),
                "lightmap coordinates must be empty or contain one uv value per vertex");
        requireOptionalSize(vertexColorsRgba8.remaining(), vertexCount,
                "vertex colors must be empty or contain one value per vertex");
        if (triangleIndices.remaining() == 0 || triangleIndices.remaining() % 3 != 0) {
            throw new IllegalArgumentException("triangle indices must contain one or more triangles");
        }
        if (triangleMaterialIds.remaining() != triangleIndices.remaining() / 3) {
            throw new IllegalArgumentException("triangle material count must match triangle count");
        }
        requireFinite(positions, "positions");
        requireFinite(normals, "normals");
        requireFinite(tangents, "tangents");
        requireFinite(textureCoordinates, "textureCoordinates");
        requireFinite(lightmapCoordinates, "lightmapCoordinates");

        IntBuffer indices = triangleIndices.duplicate();
        while (indices.hasRemaining()) {
            int index = indices.get();
            if (index < 0 || index >= vertexCount) {
                throw new IllegalArgumentException("triangle index is outside the vertex array: " + index);
            }
        }
        LongBuffer materialIds = triangleMaterialIds.duplicate();
        while (materialIds.hasRemaining()) MaterialAsset.requireId(materialIds.get(), "triangle material id");
    }

    /**
     * Returns the stable mesh identifier.
     *
     * @return non-negative mesh identifier
     */
    public long id() {
        return id;
    }

    /**
     * Returns the vertex count.
     *
     * @return number of vertices
     */
    public int vertexCount() {
        return positions.remaining() / 3;
    }

    /**
     * Returns the triangle count.
     *
     * @return number of indexed triangles
     */
    public int triangleCount() {
        return triangleIndices.remaining() / 3;
    }

    /**
     * Returns mesh positions.
     *
     * @return independent read-only xyz position view positioned at zero
     */
    public FloatBuffer positions() {
        return positions.duplicate();
    }

    /**
     * Returns mesh normals.
     *
     * @return independent read-only xyz normal view positioned at zero, possibly empty
     */
    public FloatBuffer normals() {
        return normals.duplicate();
    }

    /**
     * Returns mesh tangents.
     *
     * @return independent read-only xyzw tangent view positioned at zero, possibly empty
     */
    public FloatBuffer tangents() {
        return tangents.duplicate();
    }

    /**
     * Returns texture coordinates.
     *
     * @return independent read-only texture-coordinate view positioned at zero, possibly empty
     */
    public FloatBuffer textureCoordinates() {
        return textureCoordinates.duplicate();
    }

    /**
     * Returns lightmap coordinates.
     *
     * @return independent read-only lightmap-coordinate view positioned at zero, possibly empty
     */
    public FloatBuffer lightmapCoordinates() {
        return lightmapCoordinates.duplicate();
    }

    /**
     * Returns raw authored vertex colors. RGBA8 channels are normalized numerically by the
     * renderer; this field deliberately has no implicit sRGB transfer-function semantics.
     *
     * @return independent read-only packed vertex-color view positioned at zero, possibly empty
     */
    public IntBuffer vertexColorsRgba8() {
        return vertexColorsRgba8.duplicate();
    }

    /**
     * Returns triangle indices.
     *
     * @return independent read-only triangle-index view positioned at zero
     */
    public IntBuffer triangleIndices() {
        return triangleIndices.duplicate();
    }

    /**
     * Returns triangle materials.
     *
     * @return independent read-only per-triangle material-id view positioned at zero
     */
    public LongBuffer triangleMaterialIds() {
        return triangleMaterialIds.duplicate();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof MeshAsset mesh)) return false;
        return id == mesh.id
                && positions.equals(mesh.positions)
                && normals.equals(mesh.normals)
                && tangents.equals(mesh.tangents)
                && textureCoordinates.equals(mesh.textureCoordinates)
                && lightmapCoordinates.equals(mesh.lightmapCoordinates)
                && vertexColorsRgba8.equals(mesh.vertexColorsRgba8)
                && triangleIndices.equals(mesh.triangleIndices)
                && triangleMaterialIds.equals(mesh.triangleMaterialIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                id, positions, normals, tangents, textureCoordinates, lightmapCoordinates,
                vertexColorsRgba8, triangleIndices, triangleMaterialIds
        );
    }

    @Override
    public String toString() {
        return "MeshAsset["
                + "id=" + id
                + ", vertexCount=" + vertexCount()
                + ", triangleCount=" + triangleCount()
                + ", normals=" + normals.hasRemaining()
                + ", tangents=" + tangents.hasRemaining()
                + ", textureCoordinates=" + textureCoordinates.hasRemaining()
                + ", lightmapCoordinates=" + lightmapCoordinates.hasRemaining()
                + ", vertexColorsRgba8=" + vertexColorsRgba8.hasRemaining()
                + ']';
    }

    /**
     * Single-thread-confined builder for an immutable, safely copied mesh generation.
     */
    public static final class Builder {
        private final long id;
        private final float[] positions;
        private final int[] triangleIndices;
        private final long[] triangleMaterialIds;
        private float[] normals = new float[0];
        private float[] tangents = new float[0];
        private float[] textureCoordinates = new float[0];
        private float[] lightmapCoordinates = new float[0];
        private int[] vertexColorsRgba8 = new int[0];

        private Builder(
                long id,
                float[] positions,
                int[] triangleIndices,
                long[] triangleMaterialIds
        ) {
            MaterialAsset.requireId(id, "id");
            this.id = id;
            this.positions = Objects.requireNonNull(positions, "positions").clone();
            this.triangleIndices = Objects.requireNonNull(triangleIndices, "triangleIndices").clone();
            this.triangleMaterialIds = Objects.requireNonNull(
                    triangleMaterialIds, "triangleMaterialIds"
            ).clone();
        }

        /**
         * Selects per-vertex xyz normals.
         *
         * @param values empty or one xyz normal per vertex
         * @return this builder
         * @throws NullPointerException if {@code values} is {@code null}
         */
        public Builder normals(float[] values) {
            normals = Objects.requireNonNull(values, "normals").clone();
            return this;
        }

        /**
         * Selects per-vertex xyzw tangents.
         *
         * @param values empty or one xyzw tangent per vertex
         * @return this builder
         * @throws NullPointerException if {@code values} is {@code null}
         */
        public Builder tangents(float[] values) {
            tangents = Objects.requireNonNull(values, "tangents").clone();
            return this;
        }

        /**
         * Selects per-vertex texture coordinates.
         *
         * @param values empty or one uv pair per vertex
         * @return this builder
         * @throws NullPointerException if {@code values} is {@code null}
         */
        public Builder textureCoordinates(float[] values) {
            textureCoordinates = Objects.requireNonNull(values, "textureCoordinates").clone();
            return this;
        }

        /**
         * Selects per-vertex lightmap coordinates.
         *
         * @param values empty or one lightmap uv pair per vertex
         * @return this builder
         * @throws NullPointerException if {@code values} is {@code null}
         */
        public Builder lightmapCoordinates(float[] values) {
            lightmapCoordinates = Objects.requireNonNull(values, "lightmapCoordinates").clone();
            return this;
        }

        /**
         * Selects packed per-vertex colors.
         *
         * @param values empty or one packed RGBA8 value per vertex
         * @return this builder
         * @throws NullPointerException if {@code values} is {@code null}
         */
        public Builder vertexColorsRgba8(int[] values) {
            vertexColorsRgba8 = Objects.requireNonNull(values, "vertexColorsRgba8").clone();
            return this;
        }

        /**
         * Validates and returns an immutable mesh generation.
         *
         * <p>Every array is copied again so a reusable builder cannot retain a writable alias to
         * storage owned by a previously built asset.</p>
         *
         * @return immutable validated mesh
         * @throws IllegalArgumentException if shapes, indices, identifiers, or values are invalid
         */
        public MeshAsset build() {
            return new MeshAsset(
                    id, positions, normals, tangents, textureCoordinates, lightmapCoordinates,
                    vertexColorsRgba8, triangleIndices, triangleMaterialIds
            );
        }
    }
}
