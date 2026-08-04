package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Immutable affine transform applied to mesh texture coordinates at instance shading time.
 *
 * <p>The transform is intentionally instance-owned: animation such as scrolling, rotating, or
 * atlas selection must update a small instance record instead of manufacturing a new
 * {@link MeshAsset} generation and rebuilding its acceleration structure.</p>
 */
public final class UvTransform {
    private static final UvTransform IDENTITY = new UvTransform(1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F);

    private final float m00;
    private final float m01;
    private final float m02;
    private final float m10;
    private final float m11;
    private final float m12;

    private UvTransform(float m00, float m01, float m02, float m10, float m11, float m12) {
        requireFinite(m00, "m00");
        requireFinite(m01, "m01");
        requireFinite(m02, "m02");
        requireFinite(m10, "m10");
        requireFinite(m11, "m11");
        requireFinite(m12, "m12");
        this.m00 = m00;
        this.m01 = m01;
        this.m02 = m02;
        this.m10 = m10;
        this.m11 = m11;
        this.m12 = m12;
    }

    /**
     * Returns the shared identity transform.
     * @return identity UV transform
     */
    public static UvTransform identity() {
        return IDENTITY;
    }

    /**
     * Creates a row-major two-by-three affine transform.
     *
     * @param m00 first row, first column
     * @param m01 first row, second column
     * @param m02 first row translation
     * @param m10 second row, first column
     * @param m11 second row, second column
     * @param m12 second row translation
     * @return transform mapping {@code (u, v)} to
     *         {@code (m00*u + m01*v + m02, m10*u + m11*v + m12)}
     */
    public static UvTransform of(
            float m00, float m01, float m02,
            float m10, float m11, float m12
    ) {
        if (m00 == 1.0F && m01 == 0.0F && m02 == 0.0F
                && m10 == 0.0F && m11 == 1.0F && m12 == 0.0F) {
            return IDENTITY;
        }
        return new UvTransform(m00, m01, m02, m10, m11, m12);
    }

    /**
     * Creates an axis-aligned scale followed by an offset.
     * @param scaleU finite U scale
     * @param scaleV finite V scale
     * @param offsetU finite U offset
     * @param offsetV finite V offset
     * @return affine UV transform
     */
    public static UvTransform scaleAndOffset(float scaleU, float scaleV, float offsetU, float offsetV) {
        return of(scaleU, 0.0F, offsetU, 0.0F, scaleV, offsetV);
    }

    /**
     * Creates a rotation about an arbitrary UV pivot.
     * @param radians finite rotation angle
     * @param pivotU finite U pivot
     * @param pivotV finite V pivot
     * @return affine UV transform
     */
    public static UvTransform rotation(float radians, float pivotU, float pivotV) {
        requireFinite(radians, "radians");
        requireFinite(pivotU, "pivotU");
        requireFinite(pivotV, "pivotV");
        float cosine = (float) Math.cos(radians);
        float sine = (float) Math.sin(radians);
        return of(
                cosine, -sine, pivotU - cosine * pivotU + sine * pivotV,
                sine, cosine, pivotV - sine * pivotU - cosine * pivotV
        );
    }

    /**
     * Returns one row-major matrix element by index in {@code [0, 5]}.
     * @param index matrix index
     * @return selected matrix element
     */
    public float element(int index) {
        return switch (index) {
            case 0 -> m00;
            case 1 -> m01;
            case 2 -> m02;
            case 3 -> m10;
            case 4 -> m11;
            case 5 -> m12;
            default -> throw new IndexOutOfBoundsException(index);
        };
    }

    /**
     * Returns the transformed U coordinate.
     * @param u finite source U
     * @param v finite source V
     * @return transformed U
     */
    public float transformU(float u, float v) {
        requireFinite(u, "u");
        requireFinite(v, "v");
        return m00 * u + m01 * v + m02;
    }

    /**
     * Returns the transformed V coordinate.
     * @param u finite source U
     * @param v finite source V
     * @return transformed V
     */
    public float transformV(float u, float v) {
        requireFinite(u, "u");
        requireFinite(v, "v");
        return m10 * u + m11 * v + m12;
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof UvTransform transform)) return false;
        for (int index = 0; index < 6; index++) {
            if (Float.compare(element(index), transform.element(index)) != 0) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        return Objects.hash(m00, m01, m02, m10, m11, m12);
    }

    @Override
    public String toString() {
        return "UvTransform[" + m00 + ", " + m01 + ", " + m02 + "; "
                + m10 + ", " + m11 + ", " + m12 + ']';
    }
}
