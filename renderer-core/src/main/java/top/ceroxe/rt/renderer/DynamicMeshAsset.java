package top.ceroxe.rt.renderer;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Immutable renderer-owned triangle asset shared by dynamic RT instances.
 *
 * <p>The asset is deliberately object-local and contains no host model,
 * sprite, render-state, or GPU handle. This mirrors persistent GPU-scene/RT geometry
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

    /**
     * 创建并完整校验一个不可变动态网格资产。
     *
     * @param id              稳定且为正的资产标识
     * @param revision        非负资产修订号
     * @param vertexPositions 按 XYZ 三元组排列的顶点位置
     * @param indices         按三角形排列的顶点索引
     * @param faces           与每六个索引对应的逻辑面元数据
     */
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

    /**
     * 返回网格资产的稳定标识。
     *
     * @return 稳定资产标识
     */
    public long id() {
        return id;
    }

    /**
     * 返回网格资产的内容修订号。
     *
     * @return 资产修订号
     */
    public long revision() {
        return revision;
    }

    /**
     * 复制并返回顶点位置负载。
     *
     * @return 与内部存储分离的顶点位置副本
     */
    public float[] vertexPositions() {
        return Arrays.copyOf(vertexPositions, vertexPositions.length);
    }

    /**
     * 复制并返回三角形索引负载。
     *
     * @return 与内部存储分离的三角形索引副本
     */
    public int[] indices() {
        return Arrays.copyOf(indices, indices.length);
    }

    /**
     * 返回不可变逻辑面元数据。
     *
     * @return 不可变逻辑面列表
     */
    public List<Face> faces() {
        return faces;
    }

    /**
     * 返回网格顶点数量。
     *
     * @return 顶点数量
     */
    public int vertexCount() {
        return vertexPositions.length / POSITION_COMPONENTS;
    }

    /**
     * 返回网格逻辑面数量。
     *
     * @return 逻辑面数量
     */
    public int faceCount() {
        return faces.size();
    }

    /**
     * 返回网格三角形数量。
     *
     * @return 三角形数量
     */
    public int triangleCount() {
        return indices.length / 3;
    }

    /**
     * 估算网格稳定负载占用。
     *
     * @return 不包含 JVM 对象头的稳定负载估算字节数
     */
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

    /**
     * 动态网格中一个由两个三角形组成的逻辑面。
     *
     * @param directionOrdinal 面方向序号，取值范围为 0..5
     * @param shade            是否应用方向明暗处理
     */
    public record Face(int directionOrdinal, boolean shade) {
        private static final int ESTIMATED_BYTES = 2 * Integer.BYTES;

        /**
         * 校验面方向序号属于受支持范围。
         */
        public Face {
            if (directionOrdinal < 0 || directionOrdinal > 5) {
                throw new IllegalArgumentException("dynamic mesh face direction outside enum range");
            }
        }
    }
}
