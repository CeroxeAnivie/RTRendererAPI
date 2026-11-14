package top.ceroxe.rt.renderer;

import top.ceroxe.rt.renderer.rt.material.RtBlendMode;

import java.util.List;
import java.util.Objects;

/**
 * Frame-local transform and complete face-material state for shared RT geometry.
 */
public final class DynamicMeshInstance {
    private final DynamicMeshAsset asset;
    private final AffineTransform transform;
    private final List<FaceMaterial> faceMaterials;
    private final int hashCode;

    /**
     * 创建共享网格资产的一个完整帧实例。
     *
     * @param asset         实例引用的不可变几何资产
     * @param transform     对象到世界空间变换
     * @param faceMaterials 与资产逻辑面一一对应的材料状态
     */
    public DynamicMeshInstance(
            DynamicMeshAsset asset,
            AffineTransform transform,
            List<FaceMaterial> faceMaterials
    ) {
        this.asset = Objects.requireNonNull(asset, "asset");
        this.transform = Objects.requireNonNull(transform, "transform");
        this.faceMaterials = List.copyOf(Objects.requireNonNull(faceMaterials, "faceMaterials"));
        if (this.faceMaterials.size() != asset.faceCount()) {
            throw new IllegalArgumentException("dynamic mesh material count must match asset face count");
        }
        this.hashCode = computeHashCode();
    }

    /**
     * 返回实例引用的不可变网格资产。
     *
     * @return 共享网格资产
     */
    public DynamicMeshAsset asset() {
        return asset;
    }

    /**
     * 返回对象到世界空间变换。
     *
     * @return 不可变仿射变换
     */
    public AffineTransform transform() {
        return transform;
    }

    /**
     * 返回与资产逻辑面一一对应的不可变材料列表。
     *
     * @return 不可变面材料列表
     */
    public List<FaceMaterial> faceMaterials() {
        return faceMaterials;
    }

    /**
     * 返回指定逻辑面的材料状态。
     *
     * @param faceIndex 资产逻辑面索引
     * @return 对应的不可变材料状态
     */
    public FaceMaterial faceMaterial(int faceIndex) {
        return faceMaterials.get(faceIndex);
    }

    private int computeHashCode() {
        int result = asset.hashCode();
        result = 31 * result + transform.hashCode();
        return 31 * result + faceMaterials.hashCode();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DynamicMeshInstance that
                && asset.equals(that.asset)
                && transform.equals(that.transform)
                && faceMaterials.equals(that.faceMaterials);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    /**
     * 一个动态逻辑面的完整着色状态。
     *
     * @param textureId     基础纹理标识
     * @param uv0           第一顶点打包 UV
     * @param uv1           第二顶点打包 UV
     * @param uv2           第三顶点打包 UV
     * @param uv3           第四顶点打包 UV
     * @param tintRgba8     打包 RGBA8 染色
     * @param tinted        是否应用染色
     * @param alphaCutout   是否执行透明度裁剪
     * @param blendMode     混合模式
     * @param lightEmission 0..15 自发光强度
     * @param foilMode      箔片效果模式
     * @param outlineRgba8  打包 RGBA8 轮廓色
     * @param outlineOnly   是否只渲染轮廓
     * @param alwaysOnTop   是否使用始终置顶通道
     * @param overlayCoords 打包覆盖层坐标
     * @param decal         可选的同表面贴花
     */
    public record FaceMaterial(
            int textureId,
            int uv0,
            int uv1,
            int uv2,
            int uv3,
            int tintRgba8,
            boolean tinted,
            boolean alphaCutout,
            RtBlendMode blendMode,
            int lightEmission,
            int foilMode,
            int outlineRgba8,
            boolean outlineOnly,
            boolean alwaysOnTop,
            int overlayCoords,
            SurfaceDecal decal
    ) {
        /**
         * 表示未提供覆盖层坐标的规范打包值。
         */
        public static final int NO_OVERLAY_COORDS = 10 << 16;

        /**
         * 创建没有同表面贴花的面材料。
         *
         * @param textureId     基础纹理标识
         * @param uv0           第一顶点打包 UV
         * @param uv1           第二顶点打包 UV
         * @param uv2           第三顶点打包 UV
         * @param uv3           第四顶点打包 UV
         * @param tintRgba8     打包 RGBA8 染色
         * @param tinted        是否应用染色
         * @param alphaCutout   是否执行透明度裁剪
         * @param blendMode     混合模式
         * @param lightEmission 0..15 自发光强度
         * @param foilMode      箔片效果模式
         * @param outlineRgba8  打包 RGBA8 轮廓色
         * @param outlineOnly   是否只渲染轮廓
         * @param alwaysOnTop   是否使用始终置顶通道
         * @param overlayCoords 打包覆盖层坐标
         */
        public FaceMaterial(
                int textureId,
                int uv0,
                int uv1,
                int uv2,
                int uv3,
                int tintRgba8,
                boolean tinted,
                boolean alphaCutout,
                RtBlendMode blendMode,
                int lightEmission,
                int foilMode,
                int outlineRgba8,
                boolean outlineOnly,
                boolean alwaysOnTop,
                int overlayCoords
        ) {
            this(
                    textureId, uv0, uv1, uv2, uv3, tintRgba8, tinted, alphaCutout, blendMode,
                    lightEmission, foilMode, outlineRgba8, outlineOnly, alwaysOnTop, overlayCoords,
                    SurfaceDecal.NONE
            );
        }

        /**
         * 校验并规范化完整面材料状态。
         */
        public FaceMaterial {
            if (textureId < 0) {
                throw new IllegalArgumentException("dynamic mesh texture id must not be negative");
            }
            if (lightEmission < 0 || lightEmission > 15) {
                throw new IllegalArgumentException("dynamic mesh light emission must be in [0, 15]");
            }
            if (foilMode < 0 || foilMode > 2) {
                throw new IllegalArgumentException("dynamic mesh foil mode must be in [0, 2]");
            }
            blendMode = Objects.requireNonNull(blendMode, "blendMode");
            if (outlineOnly && ((outlineRgba8 >>> 24) & 0xFF) == 0) {
                throw new IllegalArgumentException("outline-only material requires a visible outline color");
            }
            requireValidOverlayCoords(overlayCoords);
            decal = Objects.requireNonNull(decal, "decal");
        }

        /**
         * 校验两个覆盖层坐标均处于 0..15。
         *
         * @param overlayCoords 待校验的打包坐标
         * @return 原始有效坐标
         */
        public static int requireValidOverlayCoords(int overlayCoords) {
            if ((overlayCoords & 0xFFF0_FFF0) != 0) {
                throw new IllegalArgumentException("dynamic mesh overlay coordinates must both be in [0, 15]");
            }
            return overlayCoords;
        }

        /**
         * 判断此面是否需要透明混合。
         *
         * @return 混合模式属于透明通道时返回 {@code true}
         */
        public boolean translucent() {
            return blendMode.translucent();
        }

        /**
         * 返回仅替换同表面贴花的材料副本。
         *
         * @param nextDecal 新贴花状态
         * @return 贴花相同时返回当前对象，否则返回新材料
         */
        public FaceMaterial withDecal(SurfaceDecal nextDecal) {
            Objects.requireNonNull(nextDecal, "nextDecal");
            return decal.equals(nextDecal)
                    ? this
                    : new FaceMaterial(
                    textureId, uv0, uv1, uv2, uv3, tintRgba8, tinted, alphaCutout, blendMode,
                    lightEmission, foilMode, outlineRgba8, outlineOnly, alwaysOnTop, overlayCoords,
                    nextDecal
            );
        }
    }

    /**
     * Optional same-surface material layer evaluated by the RT closest-hit shader.
     *
     * @param textureId      unsigned 16-bit texture id, or zero when the layer is absent
     * @param u0             first vertex u coordinate
     * @param v0             first vertex v coordinate
     * @param u1             second vertex u coordinate
     * @param v1             second vertex v coordinate
     * @param u2             third vertex u coordinate
     * @param v2             third vertex v coordinate
     * @param u3             fourth vertex u coordinate
     * @param v3             fourth vertex v coordinate
     * @param fractionalBits fixed-point precision in {@code [0, 15]}
     */
    public record SurfaceDecal(
            int textureId,
            float u0,
            float v0,
            float u1,
            float v1,
            float u2,
            float v2,
            float u3,
            float v3,
            int fractionalBits
    ) {
        /**
         * 不存在贴花时使用的规范状态。
         */
        public static final SurfaceDecal NONE = new SurfaceDecal(
                0, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0
        );

        /**
         * 校验贴花纹理、UV 与定点精度契约。
         */
        public SurfaceDecal {
            if (textureId < 0 || textureId >= 1 << 16) {
                throw new IllegalArgumentException("surface decal texture id must fit 16 bits");
            }
            float[] coordinates = {u0, v0, u1, v1, u2, v2, u3, v3};
            for (float coordinate : coordinates) {
                if (!Float.isFinite(coordinate)) {
                    throw new IllegalArgumentException("surface decal UV coordinates must be finite");
                }
            }
            if (fractionalBits < 0 || fractionalBits > 15) {
                throw new IllegalArgumentException("surface decal fractional bits must be in [0, 15]");
            }
            if (textureId == 0 && (u0 != 0.0F || v0 != 0.0F || u1 != 0.0F || v1 != 0.0F
                    || u2 != 0.0F || v2 != 0.0F || u3 != 0.0F || v3 != 0.0F || fractionalBits != 0)) {
                throw new IllegalArgumentException("absent surface decal must not carry UV coordinates");
            }
        }

        /**
         * 将可重复 UV 归一化到 RT 定点表示范围。
         *
         * @param textureId 16 位贴花纹理标识
         * @param u0        第一顶点 U
         * @param v0        第一顶点 V
         * @param u1        第二顶点 U
         * @param v1        第二顶点 V
         * @param u2        第三顶点 U
         * @param v2        第三顶点 V
         * @param u3        第四顶点 U
         * @param v3        第四顶点 V
         * @return 已选择最大可用定点精度的贴花
         */
        public static SurfaceDecal repeating(
                int textureId,
                float u0,
                float v0,
                float u1,
                float v1,
                float u2,
                float v2,
                float u3,
                float v3
        ) {
            float uOffset = (float) Math.floor((Math.min(Math.min(u0, u1), Math.min(u2, u3))
                    + Math.max(Math.max(u0, u1), Math.max(u2, u3))) * 0.5F);
            float vOffset = (float) Math.floor((Math.min(Math.min(v0, v1), Math.min(v2, v3))
                    + Math.max(Math.max(v0, v1), Math.max(v2, v3))) * 0.5F);
            float[] normalized = {
                    u0 - uOffset, v0 - vOffset,
                    u1 - uOffset, v1 - vOffset,
                    u2 - uOffset, v2 - vOffset,
                    u3 - uOffset, v3 - vOffset
            };
            float maximumMagnitude = 0.0F;
            for (float coordinate : normalized) {
                maximumMagnitude = Math.max(maximumMagnitude, Math.abs(coordinate));
            }
            int fractionalBits = 15;
            while (fractionalBits > 0 && maximumMagnitude * (1 << fractionalBits) > 511.0F) {
                fractionalBits--;
            }
            if (maximumMagnitude > 511.0F) {
                throw new IllegalArgumentException("surface decal UV span exceeds the RT fixed-point range");
            }
            return new SurfaceDecal(
                    textureId,
                    normalized[0], normalized[1], normalized[2], normalized[3],
                    normalized[4], normalized[5], normalized[6], normalized[7],
                    fractionalBits
            );
        }

        /**
         * 返回指定顶点的贴花 U 坐标。
         *
         * @param vertexIndex 0..3 顶点索引
         * @return 对应顶点的 U 坐标
         */
        public float u(int vertexIndex) {
            return switch (vertexIndex) {
                case 0 -> u0;
                case 1 -> u1;
                case 2 -> u2;
                case 3 -> u3;
                default -> throw new IndexOutOfBoundsException(vertexIndex);
            };
        }

        /**
         * 返回指定顶点的贴花 V 坐标。
         *
         * @param vertexIndex 0..3 顶点索引
         * @return 对应顶点的 V 坐标
         */
        public float v(int vertexIndex) {
            return switch (vertexIndex) {
                case 0 -> v0;
                case 1 -> v1;
                case 2 -> v2;
                case 3 -> v3;
                default -> throw new IndexOutOfBoundsException(vertexIndex);
            };
        }

        /**
         * 判断当前状态是否包含实际贴花。
         *
         * @return 纹理标识非零时返回 {@code true}
         */
        public boolean present() {
            return textureId != 0;
        }
    }

    /**
     * Vulkan-compatible row-major 3x4 object-to-world transform.
     *
     * @param m00 row zero, column zero
     * @param m01 row zero, column one
     * @param m02 row zero, column two
     * @param m03 row zero translation
     * @param m10 row one, column zero
     * @param m11 row one, column one
     * @param m12 row one, column two
     * @param m13 row one translation
     * @param m20 row two, column zero
     * @param m21 row two, column one
     * @param m22 row two, column two
     * @param m23 row two translation
     */
    public record AffineTransform(
            float m00, float m01, float m02, float m03,
            float m10, float m11, float m12, float m13,
            float m20, float m21, float m22, float m23
    ) {
        private static final AffineTransform IDENTITY = new AffineTransform(
                1.0F, 0.0F, 0.0F, 0.0F,
                0.0F, 1.0F, 0.0F, 0.0F,
                0.0F, 0.0F, 1.0F, 0.0F
        );

        /**
         * 校验所有矩阵分量有限且线性部分可逆。
         */
        public AffineTransform {
            if (!Float.isFinite(m00) || !Float.isFinite(m01) || !Float.isFinite(m02) || !Float.isFinite(m03)
                    || !Float.isFinite(m10) || !Float.isFinite(m11) || !Float.isFinite(m12) || !Float.isFinite(m13)
                    || !Float.isFinite(m20) || !Float.isFinite(m21) || !Float.isFinite(m22) || !Float.isFinite(m23)) {
                throw new IllegalArgumentException("dynamic mesh instance transform must be finite");
            }
            float determinant = m00 * (m11 * m22 - m12 * m21)
                    - m01 * (m10 * m22 - m12 * m20)
                    + m02 * (m10 * m21 - m11 * m20);
            if (!Float.isFinite(determinant) || Math.abs(determinant) < 1.0E-8F) {
                throw new IllegalArgumentException("dynamic mesh instance transform must be invertible");
            }
        }

        /**
         * 返回共享的单位仿射变换。
         *
         * @return 单位仿射变换
         */
        public static AffineTransform identity() {
            // The value is deeply immutable and appears in every inactive or
            // untranslated physical TLAS slot. Reusing it prevents stable
            // capacity padding from manufacturing transforms every frame.
            return IDENTITY;
        }

        /**
         * 返回 X 平移分量。
         *
         * @return X 平移
         */
        public float translateX() {
            return m03;
        }

        /**
         * 返回 Y 平移分量。
         *
         * @return Y 平移
         */
        public float translateY() {
            return m13;
        }

        /**
         * 返回 Z 平移分量。
         *
         * @return Z 平移
         */
        public float translateZ() {
            return m23;
        }

        /**
         * 按 Vulkan 3x4 行主序返回矩阵分量。
         *
         * @param index 0..11 分量索引
         * @return 对应矩阵分量
         */
        public float value(int index) {
            return switch (index) {
                case 0 -> m00;
                case 1 -> m01;
                case 2 -> m02;
                case 3 -> m03;
                case 4 -> m10;
                case 5 -> m11;
                case 6 -> m12;
                case 7 -> m13;
                case 8 -> m20;
                case 9 -> m21;
                case 10 -> m22;
                case 11 -> m23;
                default -> throw new IndexOutOfBoundsException(index);
            };
        }
    }
}
