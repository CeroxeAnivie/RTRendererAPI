package top.ceroxe.mcvulkanrt.renderer;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Immutable renderer-owned triangle asset shared by dynamic RT instances.
 *
 * <p>The asset is deliberately object-local and contains no host model,
 * sprite, render-state, or GPU handle. This mirrors UE GPUScene/RT geometry
 * ownership: immutable geometry has a longer lifetime than frame-local
 * instances. Face materials are intentionally absent: texture, tint, blend,
 * emission, and animated UV state belong to {@link DynamicMeshInstance} so a
 * material pass can never trigger a BLAS rebuild.</p>
 */
public final class DynamicMeshAsset {
    private static final int POSITION_COMPONENTS = 3;
    private static final int INDICES_PER_FACE = 6;

    private final long id;
    private final long revision;
    private final float[] vertexPositions;
    private final int[] indices;
    private final List<Face> faces;
    private final int hashCode;

    public DynamicMeshAsset(
            long id,
            long revision,
            float[] vertexPositions,
            int[] indices,
            List<Face> faces
    ) {
        if (id <= 0L) {
            throw new IllegalArgumentException("dynamic mesh asset id must be positive");
        }
        if (revision <= 0L) {
            throw new IllegalArgumentException("dynamic mesh asset revision must be positive");
        }
        this.id = id;
        this.revision = revision;
        this.vertexPositions = Arrays.copyOf(
                Objects.requireNonNull(vertexPositions, "vertexPositions"),
                vertexPositions.length
        );
        this.indices = Arrays.copyOf(Objects.requireNonNull(indices, "indices"), indices.length);
        this.faces = List.copyOf(Objects.requireNonNull(faces, "faces"));
        validateGeometry();
        this.hashCode = computeHashCode();
    }

    public long id() {
        return id;
    }

    public long revision() {
        return revision;
    }

    public float[] vertexPositions() {
        return Arrays.copyOf(vertexPositions, vertexPositions.length);
    }

    public int[] indices() {
        return Arrays.copyOf(indices, indices.length);
    }

    public List<Face> faces() {
        return faces;
    }

    public int vertexCount() {
        return vertexPositions.length / POSITION_COMPONENTS;
    }

    public int faceCount() {
        return faces.size();
    }

    public int triangleCount() {
        return indices.length / 3;
    }

    public long estimatedBytes() {
        return (long) vertexPositions.length * Float.BYTES
                + (long) indices.length * Integer.BYTES
                + (long) faces.size() * Face.ESTIMATED_BYTES;
    }

    private void validateGeometry() {
        if (vertexPositions.length == 0 || vertexPositions.length % POSITION_COMPONENTS != 0) {
            throw new IllegalArgumentException("dynamic mesh positions must contain XYZ triples");
        }
        for (float position : vertexPositions) {
            if (!Float.isFinite(position)) {
                throw new IllegalArgumentException("dynamic mesh positions must be finite");
            }
        }
        if (indices.length == 0 || indices.length % 3 != 0) {
            throw new IllegalArgumentException("dynamic mesh indices must contain triangle triples");
        }
        if (faces.isEmpty() || Math.multiplyExact(faces.size(), INDICES_PER_FACE) != indices.length) {
            throw new IllegalArgumentException("dynamic mesh faces must map one-to-one to indexed quads");
        }
        int vertexCount = vertexCount();
        for (int index : indices) {
            if (index < 0 || index >= vertexCount) {
                throw new IllegalArgumentException("dynamic mesh index outside vertex range: " + index);
            }
        }
    }

    private int computeHashCode() {
        int result = Long.hashCode(id);
        result = 31 * result + Long.hashCode(revision);
        result = 31 * result + Arrays.hashCode(vertexPositions);
        result = 31 * result + Arrays.hashCode(indices);
        return 31 * result + faces.hashCode();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DynamicMeshAsset that
                && id == that.id
                && revision == that.revision
                && Arrays.equals(vertexPositions, that.vertexPositions)
                && Arrays.equals(indices, that.indices)
                && faces.equals(that.faces);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    public record Face(int directionOrdinal, boolean shade) {
        private static final int ESTIMATED_BYTES = 2 * Integer.BYTES;

        public Face {
            if (directionOrdinal < 0 || directionOrdinal > 5) {
                throw new IllegalArgumentException("dynamic mesh face direction outside enum range");
            }
        }
    }
}
