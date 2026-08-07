package top.ceroxe.rt.renderer.api;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable, host-independent exact camera-to-world and clip-from-view mapping.
 *
 * <p>Both matrices are supplied in the explicitly selected layout and are copied into a
 * canonical row-major representation. View space is right-handed with {@code -Z} forward,
 * {@code +X} right, and {@code +Y} up. Pixel coordinates use a top-left origin and pixel centers
 * at {@code (x + 0.5, y + 0.5)}. No renderer or host-specific fields are present in this contract.</p>
 */
public final class ExactProjectionState {
    private static final double RIGID_TOLERANCE = 2.0E-6D;
    private static final double DETERMINANT_EPSILON = 1.0E-12D;

    /** Matrix element ordering supplied by the host. */
    public enum MatrixLayout {
        /** Elements are supplied as row * 4 + column. */
        ROW_MAJOR,
        /** Elements are supplied as column * 4 + row. */
        COLUMN_MAJOR
    }

    /** Coordinate convention used by the camera-to-world matrix. */
    public enum CoordinateSystem {
        /** Right-handed view coordinates with -Z forward, +X right, and +Y up. */
        RIGHT_HANDED_NEGATIVE_Z_FORWARD
    }

    /** NDC depth interval and orientation used by the clip-from-view matrix. */
    public enum DepthConvention {
        /** Forward depth in [0, 1]. */
        ZERO_TO_ONE(false, 0.0D, 1.0D),
        /** Forward depth in [-1, 1]. */
        NEGATIVE_ONE_TO_ONE(false, -1.0D, 1.0D),
        /** Reversed depth in [0, 1]. */
        REVERSED_ZERO_TO_ONE(true, 1.0D, 0.0D),
        /** Reversed depth in [-1, 1]. */
        REVERSED_NEGATIVE_ONE_TO_ONE(true, 1.0D, -1.0D);

        private final boolean reversed;
        private final double nearNdc;
        private final double farNdc;

        DepthConvention(boolean reversed, double nearNdc, double farNdc) {
            this.reversed = reversed;
            this.nearNdc = nearNdc;
            this.farNdc = farNdc;
        }

        /** Returns whether near and far depth are reversed.
         * @return {@code true} for reversed depth
         */
        public boolean reversed() {
            return reversed;
        }

        double nearNdc() {
            return nearNdc;
        }

        double farNdc() {
            return farNdc;
        }
    }

    /** Convention for the optional per-frame projection jitter. */
    public enum JitterConvention {
        /** No jitter; both jitter values must be zero. */
        NONE,
        /** Jitter values are pixel offsets applied before viewport-to-NDC conversion. */
        PIXEL_CENTER_OFFSET,
        /** Jitter values are direct NDC offsets. */
        NDC_OFFSET
    }

    /** Immutable world-space ray returned by {@link #rayForPixel(double, double)}.
     * @param originX world-space origin x
     * @param originY world-space origin y
     * @param originZ world-space origin z
     * @param directionX normalized direction x
     * @param directionY normalized direction y
     * @param directionZ normalized direction z
     */
    public record Ray(
            double originX,
            double originY,
            double originZ,
            double directionX,
            double directionY,
            double directionZ
    ) {
        /** Creates a finite immutable world-space ray. */
        public Ray {
            if (!Double.isFinite(originX) || !Double.isFinite(originY) || !Double.isFinite(originZ)
                    || !Double.isFinite(directionX) || !Double.isFinite(directionY)
                    || !Double.isFinite(directionZ)) {
                throw new IllegalArgumentException("exact projection ray must be finite");
            }
        }
    }

    private final int viewportWidth;
    private final int viewportHeight;
    private final MatrixLayout matrixLayout;
    private final CoordinateSystem coordinateSystem;
    private final DepthConvention depthConvention;
    private final JitterConvention jitterConvention;
    private final double jitterX;
    private final double jitterY;
    private final double[] cameraToWorld;
    private final double[] clipFromView;
    private final double[] inverseClipFromView;

    private ExactProjectionState(Builder builder) {
        viewportWidth = builder.viewportWidth;
        viewportHeight = builder.viewportHeight;
        matrixLayout = Objects.requireNonNull(builder.matrixLayout, "matrixLayout");
        coordinateSystem = Objects.requireNonNull(builder.coordinateSystem, "coordinateSystem");
        depthConvention = Objects.requireNonNull(builder.depthConvention, "depthConvention");
        jitterConvention = Objects.requireNonNull(builder.jitterConvention, "jitterConvention");
        jitterX = builder.jitterX;
        jitterY = builder.jitterY;
        cameraToWorld = canonical(builder.cameraToWorld, matrixLayout, "cameraToWorld");
        clipFromView = canonical(builder.clipFromView, matrixLayout, "clipFromView");
        validateRigid(cameraToWorld);
        inverseClipFromView = invert(clipFromView);
        validateJitter(jitterConvention, jitterX, jitterY);
    }

    /** Starts a builder for a positive output viewport.
     * @param viewportWidth positive viewport width
     * @param viewportHeight positive viewport height
     * @return exact projection builder
     */
    public static Builder builder(int viewportWidth, int viewportHeight) {
        return new Builder(viewportWidth, viewportHeight);
    }

    /** Returns the positive viewport width in pixels.
     * @return viewport width
     */
    public int viewportWidth() { return viewportWidth; }
    /** Returns the positive viewport height in pixels.
     * @return viewport height
     */
    public int viewportHeight() { return viewportHeight; }
    /** Returns the explicitly selected input matrix layout.
     * @return matrix layout
     */
    public MatrixLayout matrixLayout() { return matrixLayout; }
    /** Returns the explicit view coordinate convention.
     * @return coordinate convention
     */
    public CoordinateSystem coordinateSystem() { return coordinateSystem; }
    /** Returns the explicit clip-depth convention.
     * @return depth convention
     */
    public DepthConvention depthConvention() { return depthConvention; }
    /** Returns the explicit jitter convention.
     * @return jitter convention
     */
    public JitterConvention jitterConvention() { return jitterConvention; }
    /** Returns the horizontal jitter value in the selected convention.
     * @return horizontal jitter
     */
    public double jitterX() { return jitterX; }
    /** Returns the vertical jitter value in the selected convention.
     * @return vertical jitter
     */
    public double jitterY() { return jitterY; }
    /** Returns horizontal jitter after validating it fits the GPU float ABI.
     * @return finite float jitter
     */
    public float jitterXAsFloat() { return finiteFloat(jitterX, "jitterX"); }
    /** Returns vertical jitter after validating it fits the GPU float ABI.
     * @return finite float jitter
     */
    public float jitterYAsFloat() { return finiteFloat(jitterY, "jitterY"); }

    /** Returns a defensive copy in canonical row-major order.
     * @return camera-to-world matrix
     */
    public double[] cameraToWorld() { return cameraToWorld.clone(); }

    /** Returns a defensive copy in canonical row-major order.
     * @return clip-from-view matrix
     */
    public double[] clipFromView() { return clipFromView.clone(); }

    /** Returns a defensive copy in canonical row-major order.
     * @return inverse clip-from-view matrix
     */
    public double[] inverseClipFromView() { return inverseClipFromView.clone(); }

    /** Returns the camera world-space x coordinate.
     * @return world-space x coordinate
     */
    public double cameraX() { return cameraToWorld[3]; }
    /** Returns the camera world-space y coordinate.
     * @return world-space y coordinate
     */
    public double cameraY() { return cameraToWorld[7]; }
    /** Returns the camera world-space z coordinate.
     * @return world-space z coordinate
     */
    public double cameraZ() { return cameraToWorld[11]; }

    /** Returns one canonical row-major clip-from-view matrix element.
     * @param row matrix row
     * @param column matrix column
     * @return matrix element
     */
    public double matrix(int row, int column) {
        if (row < 0 || row >= 4 || column < 0 || column >= 4) {
            throw new IndexOutOfBoundsException("matrix index must be in [0, 4)");
        }
        return clipFromView[row * 4 + column];
    }

    /**
     * Maps a top-left-origin pixel center to a normalized world-space primary ray.
     *
     * @param pixelX pixel coordinate, finite
     * @param pixelY pixel coordinate, finite
     * @return immutable world-space ray
     */
    public Ray rayForPixel(double pixelX, double pixelY) {
        if (!Double.isFinite(pixelX) || !Double.isFinite(pixelY)) {
            throw new IllegalArgumentException("pixel coordinates must be finite");
        }
        double sampleX = pixelX + 0.5D;
        double sampleY = pixelY + 0.5D;
        double ndcX = sampleX / viewportWidth * 2.0D - 1.0D;
        double ndcY = 1.0D - sampleY / viewportHeight * 2.0D;
        if (jitterConvention == JitterConvention.PIXEL_CENTER_OFFSET) {
            ndcX += jitterX / viewportWidth * 2.0D;
            ndcY -= jitterY / viewportHeight * 2.0D;
        } else if (jitterConvention == JitterConvention.NDC_OFFSET) {
            ndcX += jitterX;
            ndcY += jitterY;
        }
        double[] near = unproject(ndcX, ndcY, depthConvention.nearNdc());
        double[] far = unproject(ndcX, ndcY, depthConvention.farNdc());
        double[] worldNear = transformPoint(cameraToWorld, near);
        double[] worldFar = transformPoint(cameraToWorld, far);
        double dx = worldFar[0] - worldNear[0];
        double dy = worldFar[1] - worldNear[1];
        double dz = worldFar[2] - worldNear[2];
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!Double.isFinite(length) || length <= DETERMINANT_EPSILON) {
            throw new IllegalArgumentException("exact projection produced a degenerate ray");
        }
        return new Ray(cameraX(), cameraY(), cameraZ(), dx / length, dy / length, dz / length);
    }

    private double[] unproject(double ndcX, double ndcY, double ndcZ) {
        double[] clip = {ndcX, ndcY, ndcZ, 1.0D};
        double[] view = multiply(inverseClipFromView, clip);
        double w = view[3];
        if (!Double.isFinite(w) || Math.abs(w) <= DETERMINANT_EPSILON) {
            throw new IllegalArgumentException("exact projection inverse produced an invalid homogeneous coordinate");
        }
        return new double[]{view[0] / w, view[1] / w, view[2] / w};
    }

    private static double[] canonical(double[] source, MatrixLayout layout, String name) {
        Objects.requireNonNull(source, name);
        if (source.length != 16) throw new IllegalArgumentException(name + " must contain 16 elements");
        double[] result = new double[16];
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                int sourceIndex = layout == MatrixLayout.ROW_MAJOR ? row * 4 + column : column * 4 + row;
                double value = source[sourceIndex];
                if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
                result[row * 4 + column] = value;
            }
        }
        return result;
    }

    private static void validateRigid(double[] matrix) {
        if (Math.abs(matrix[12]) > RIGID_TOLERANCE || Math.abs(matrix[13]) > RIGID_TOLERANCE
                || Math.abs(matrix[14]) > RIGID_TOLERANCE || Math.abs(matrix[15] - 1.0D) > RIGID_TOLERANCE) {
            throw new IllegalArgumentException("cameraToWorld must be an affine rigid transform");
        }
        double[] c0 = {matrix[0], matrix[4], matrix[8]};
        double[] c1 = {matrix[1], matrix[5], matrix[9]};
        double[] c2 = {matrix[2], matrix[6], matrix[10]};
        if (!unit(c0) || !unit(c1) || !unit(c2)
                || Math.abs(dot(c0, c1)) > RIGID_TOLERANCE
                || Math.abs(dot(c0, c2)) > RIGID_TOLERANCE
                || Math.abs(dot(c1, c2)) > RIGID_TOLERANCE
                || Math.abs(det3(c0, c1, c2) - 1.0D) > 5.0E-5D) {
            throw new IllegalArgumentException("cameraToWorld rotation must be orthonormal with determinant +1");
        }
    }

    private static void validateJitter(JitterConvention convention, double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) throw new IllegalArgumentException("jitter must be finite");
        if (convention == JitterConvention.NONE && (x != 0.0D || y != 0.0D)) {
            throw new IllegalArgumentException("NONE jitter convention requires zero jitter");
        }
    }

    private static float finiteFloat(double value, String name) {
        if (!Double.isFinite(value) || value < -Float.MAX_VALUE || value > Float.MAX_VALUE) {
            throw new IllegalArgumentException(name + " is outside the shader float ABI");
        }
        return (float) value;
    }

    private static boolean unit(double[] v) { return Math.abs(dot(v, v) - 1.0D) <= RIGID_TOLERANCE; }
    private static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    private static double det3(double[] c0, double[] c1, double[] c2) {
        return c0[0] * (c1[1] * c2[2] - c1[2] * c2[1])
                - c1[0] * (c0[1] * c2[2] - c0[2] * c2[1])
                + c2[0] * (c0[1] * c1[2] - c0[2] * c1[1]);
    }

    private static double[] transformPoint(double[] matrix, double[] point) {
        return new double[]{
                matrix[0] * point[0] + matrix[1] * point[1] + matrix[2] * point[2] + matrix[3],
                matrix[4] * point[0] + matrix[5] * point[1] + matrix[6] * point[2] + matrix[7],
                matrix[8] * point[0] + matrix[9] * point[1] + matrix[10] * point[2] + matrix[11]
        };
    }

    private static double[] multiply(double[] matrix, double[] vector) {
        return new double[]{
                matrix[0] * vector[0] + matrix[1] * vector[1] + matrix[2] * vector[2] + matrix[3] * vector[3],
                matrix[4] * vector[0] + matrix[5] * vector[1] + matrix[6] * vector[2] + matrix[7] * vector[3],
                matrix[8] * vector[0] + matrix[9] * vector[1] + matrix[10] * vector[2] + matrix[11] * vector[3],
                matrix[12] * vector[0] + matrix[13] * vector[1] + matrix[14] * vector[2] + matrix[15] * vector[3]
        };
    }

    private static double[] invert(double[] source) {
        double[][] augmented = new double[4][8];
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) augmented[row][column] = source[row * 4 + column];
            augmented[row][row + 4] = 1.0D;
        }
        for (int pivot = 0; pivot < 4; pivot++) {
            int best = pivot;
            for (int row = pivot + 1; row < 4; row++) {
                if (Math.abs(augmented[row][pivot]) > Math.abs(augmented[best][pivot])) best = row;
            }
            double scale = Math.abs(augmented[best][pivot]);
            if (!Double.isFinite(scale) || scale <= DETERMINANT_EPSILON) throw new IllegalArgumentException("clipFromView must be invertible");
            double[] swap = augmented[pivot]; augmented[pivot] = augmented[best]; augmented[best] = swap;
            double divisor = augmented[pivot][pivot];
            for (int column = 0; column < 8; column++) augmented[pivot][column] /= divisor;
            for (int row = 0; row < 4; row++) {
                if (row == pivot) continue;
                double factor = augmented[row][pivot];
                for (int column = 0; column < 8; column++) augmented[row][column] -= factor * augmented[pivot][column];
            }
        }
        double[] inverse = new double[16];
        for (int row = 0; row < 4; row++) for (int column = 0; column < 4; column++) {
            inverse[row * 4 + column] = augmented[row][column + 4];
            if (!Double.isFinite(inverse[row * 4 + column])) throw new IllegalArgumentException("clipFromView inverse must be finite");
        }
        return inverse;
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ExactProjectionState that)) return false;
        return viewportWidth == that.viewportWidth && viewportHeight == that.viewportHeight
                && matrixLayout == that.matrixLayout && coordinateSystem == that.coordinateSystem
                && depthConvention == that.depthConvention && jitterConvention == that.jitterConvention
                && Double.compare(jitterX, that.jitterX) == 0 && Double.compare(jitterY, that.jitterY) == 0
                && Arrays.equals(cameraToWorld, that.cameraToWorld) && Arrays.equals(clipFromView, that.clipFromView);
    }

    @Override public int hashCode() { return Objects.hash(viewportWidth, viewportHeight, matrixLayout, coordinateSystem, depthConvention, jitterConvention, jitterX, jitterY, Arrays.hashCode(cameraToWorld), Arrays.hashCode(clipFromView)); }

    @Override public String toString() { return "ExactProjectionState[" + viewportWidth + "x" + viewportHeight + ", " + depthConvention + ", " + jitterConvention + "]"; }

    /** Single-thread-confined builder for an immutable exact mapping. */
    public static final class Builder {
        private final int viewportWidth;
        private final int viewportHeight;
        private MatrixLayout matrixLayout;
        private CoordinateSystem coordinateSystem;
        private DepthConvention depthConvention;
        private JitterConvention jitterConvention = JitterConvention.NONE;
        private double jitterX;
        private double jitterY;
        private double[] cameraToWorld;
        private double[] clipFromView;

        private Builder(int viewportWidth, int viewportHeight) {
            if (viewportWidth <= 0 || viewportHeight <= 0) throw new IllegalArgumentException("viewport must be positive");
            this.viewportWidth = viewportWidth;
            this.viewportHeight = viewportHeight;
        }

        /** Selects the layout used by both supplied matrices.
         * @param value matrix layout
         * @return this builder
         */
        public Builder matrixLayout(MatrixLayout value) { matrixLayout = Objects.requireNonNull(value, "matrixLayout"); return this; }
        /** Selects the explicit view coordinate system.
         * @param value coordinate system
         * @return this builder
         */
        public Builder coordinateSystem(CoordinateSystem value) { coordinateSystem = Objects.requireNonNull(value, "coordinateSystem"); return this; }
        /** Selects the NDC depth interval and orientation.
         * @param value depth convention
         * @return this builder
         */
        public Builder depthConvention(DepthConvention value) { depthConvention = Objects.requireNonNull(value, "depthConvention"); return this; }
        /** Selects jitter convention and values.
         * @param convention jitter convention
         * @param x horizontal jitter
         * @param y vertical jitter
         * @return this builder
         */
        public Builder jitter(JitterConvention convention, double x, double y) { jitterConvention = Objects.requireNonNull(convention, "jitterConvention"); jitterX = x; jitterY = y; return this; }
        /** Supplies a 16-element camera-to-world rigid matrix.
         * @param values matrix values
         * @return this builder
         */
        public Builder cameraToWorld(double[] values) { cameraToWorld = Objects.requireNonNull(values, "cameraToWorld").clone(); return this; }
        /** Supplies a 16-element finite, invertible clip-from-view matrix.
         * @param values matrix values
         * @return this builder
         */
        public Builder clipFromView(double[] values) { clipFromView = Objects.requireNonNull(values, "clipFromView").clone(); return this; }

        /** Validates all values and returns an immutable exact mapping.
         * @return validated immutable mapping
         */
        public ExactProjectionState build() {
            if (matrixLayout == null || coordinateSystem == null || depthConvention == null) throw new IllegalArgumentException("matrix layout, coordinate system, and depth convention are required");
            if (cameraToWorld == null || clipFromView == null) throw new IllegalArgumentException("both projection matrices are required");
            return new ExactProjectionState(this);
        }
    }
}
