package top.ceroxe.mcvulkanrt.renderer;

import top.ceroxe.mcvulkanrt.renderer.rt.material.RtBlendMode;

import java.util.List;
import java.util.Objects;

/** Frame-local transform and complete face-material state for shared RT geometry. */
public final class DynamicMeshInstance {
    private final DynamicMeshAsset asset;
    private final AffineTransform transform;
    private final List<FaceMaterial> faceMaterials;
    private final int hashCode;

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

    public DynamicMeshAsset asset() {
        return asset;
    }

    public AffineTransform transform() {
        return transform;
    }

    public List<FaceMaterial> faceMaterials() {
        return faceMaterials;
    }

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
        public static final int NO_OVERLAY_COORDS = 10 << 16;

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

        public boolean translucent() {
            return blendMode.translucent();
        }

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

        public static int requireValidOverlayCoords(int overlayCoords) {
            if ((overlayCoords & 0xFFF0_FFF0) != 0) {
                throw new IllegalArgumentException("dynamic mesh overlay coordinates must both be in [0, 15]");
            }
            return overlayCoords;
        }
    }

    /** Optional same-surface material layer evaluated by the RT closest-hit shader. */
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
        public static final SurfaceDecal NONE = new SurfaceDecal(
                0, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0
        );

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

        public float u(int vertexIndex) {
            return switch (vertexIndex) {
                case 0 -> u0;
                case 1 -> u1;
                case 2 -> u2;
                case 3 -> u3;
                default -> throw new IndexOutOfBoundsException(vertexIndex);
            };
        }

        public float v(int vertexIndex) {
            return switch (vertexIndex) {
                case 0 -> v0;
                case 1 -> v1;
                case 2 -> v2;
                case 3 -> v3;
                default -> throw new IndexOutOfBoundsException(vertexIndex);
            };
        }

        public boolean present() {
            return textureId != 0;
        }
    }

    /** Vulkan-compatible row-major 3x4 object-to-world transform. */
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

        public static AffineTransform identity() {
            // The value is deeply immutable and appears in every inactive or
            // untranslated physical TLAS slot. Reusing it prevents stable
            // capacity padding from manufacturing transforms every frame.
            return IDENTITY;
        }

        public float translateX() {
            return m03;
        }

        public float translateY() {
            return m13;
        }

        public float translateZ() {
            return m23;
        }

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
