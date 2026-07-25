package top.ceroxe.rt.renderer.api;

/**
 * Immutable world-space camera basis and primary-ray projection.
 *
 * <p>Instances are thread-safe values. Construction performs validation only and does not block.
 * The three basis vectors must be normalized, mutually orthogonal, and use the renderer's
 * right-handed convention where {@code right = forward x up}.</p>
 *
 */
public final class CameraState {
    private static final float UNIT_TOLERANCE = 2.0E-3F;
    private static final float ORTHOGONAL_TOLERANCE = 3.0E-3F;

    private final double x;
    private final double y;
    private final double z;
    private final float forwardX;
    private final float forwardY;
    private final float forwardZ;
    private final float rightX;
    private final float rightY;
    private final float rightZ;
    private final float upX;
    private final float upY;
    private final float upZ;
    private final float tanHalfFovX;
    private final float tanHalfFovY;

    /**
     * Validates and creates a camera state.
     *
     * @param x           world-space camera origin x coordinate
     * @param y           world-space camera origin y coordinate
     * @param z           world-space camera origin z coordinate
     * @param forwardX    normalized forward-vector x component
     * @param forwardY    normalized forward-vector y component
     * @param forwardZ    normalized forward-vector z component
     * @param rightX      normalized right-vector x component
     * @param rightY      normalized right-vector y component
     * @param rightZ      normalized right-vector z component
     * @param upX         normalized up-vector x component
     * @param upY         normalized up-vector y component
     * @param upZ         normalized up-vector z component
     * @param tanHalfFovX positive tangent of half the horizontal field of view
     * @param tanHalfFovY positive tangent of half the vertical field of view
     * @throws IllegalArgumentException if any component is non-finite or the basis is invalid
     */
    private CameraState(
            double x,
            double y,
            double z,
            float forwardX,
            float forwardY,
            float forwardZ,
            float rightX,
            float rightY,
            float rightZ,
            float upX,
            float upY,
            float upZ,
            float tanHalfFovX,
            float tanHalfFovY
    ) {
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(z, "z");
        requireUnit(forwardX, forwardY, forwardZ, "forward");
        requireUnit(rightX, rightY, rightZ, "right");
        requireUnit(upX, upY, upZ, "up");
        requireOrthogonal(forwardX, forwardY, forwardZ, rightX, rightY, rightZ, "forward/right");
        requireOrthogonal(forwardX, forwardY, forwardZ, upX, upY, upZ, "forward/up");
        requireOrthogonal(rightX, rightY, rightZ, upX, upY, upZ, "right/up");
        requireHandedness(
                forwardX, forwardY, forwardZ,
                rightX, rightY, rightZ,
                upX, upY, upZ
        );
        if (!Float.isFinite(tanHalfFovX) || tanHalfFovX <= 0.0F
                || !Float.isFinite(tanHalfFovY) || tanHalfFovY <= 0.0F) {
            throw new IllegalArgumentException("camera FOV tangents must be finite and positive");
        }
        this.x = x;
        this.y = y;
        this.z = z;
        this.forwardX = forwardX;
        this.forwardY = forwardY;
        this.forwardZ = forwardZ;
        this.rightX = rightX;
        this.rightY = rightY;
        this.rightZ = rightZ;
        this.upX = upX;
        this.upY = upY;
        this.upZ = upZ;
        this.tanHalfFovX = tanHalfFovX;
        this.tanHalfFovY = tanHalfFovY;
    }

    /**
     * Starts a camera builder aimed from a world position at a target.
     *
     * <p>Defaults are world up {@code (0, 1, 0)}, a 60-degree vertical field of view, and a 16:9
     * aspect ratio. The builder derives and normalizes the complete orthogonal basis.</p>
     *
     * @param x       world-space camera x coordinate
     * @param y       world-space camera y coordinate
     * @param z       world-space camera z coordinate
     * @param targetX world-space target x coordinate
     * @param targetY world-space target y coordinate
     * @param targetZ world-space target z coordinate
     * @return single-thread-confined camera builder
     */
    public static Builder lookAt(
            double x, double y, double z,
            double targetX, double targetY, double targetZ
    ) {
        return new Builder(x, y, z, targetX, targetY, targetZ);
    }

    /**
     * Starts an expert builder for a fully explicit orthonormal camera basis.
     *
     * <p>This entry point is intended for hosts that already own validated view matrices. The
     * final {@link ExplicitBasisBuilder#build()} call still enforces normalization, orthogonality,
     * handedness, and finite projection values.</p>
     *
     * @param x world-space camera x coordinate
     * @param y world-space camera y coordinate
     * @param z world-space camera z coordinate
     * @return single-thread-confined explicit-basis builder
     */
    public static ExplicitBasisBuilder explicitBasis(double x, double y, double z) {
        return new ExplicitBasisBuilder(x, y, z);
    }

    private static void requireUnit(float x, float y, float z, String name) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
            throw new IllegalArgumentException(name + " vector must be finite");
        }
        float lengthSquared = x * x + y * y + z * z;
        if (Math.abs(lengthSquared - 1.0F) > UNIT_TOLERANCE) {
            throw new IllegalArgumentException(name + " vector must be normalized");
        }
    }

    private static void requireOrthogonal(
            float ax, float ay, float az,
            float bx, float by, float bz,
            String name
    ) {
        if (Math.abs(ax * bx + ay * by + az * bz) > ORTHOGONAL_TOLERANCE) {
            throw new IllegalArgumentException(name + " camera vectors must be orthogonal");
        }
    }

    private static void requireHandedness(
            float forwardX, float forwardY, float forwardZ,
            float rightX, float rightY, float rightZ,
            float upX, float upY, float upZ
    ) {
        float crossX = forwardY * upZ - forwardZ * upY;
        float crossY = forwardZ * upX - forwardX * upZ;
        float crossZ = forwardX * upY - forwardY * upX;
        float alignment = crossX * rightX + crossY * rightY + crossZ * rightZ;
        if (!Float.isFinite(alignment) || alignment < 1.0F - ORTHOGONAL_TOLERANCE) {
            throw new IllegalArgumentException(
                    "camera basis must satisfy the right-handed convention right = forward x up"
            );
        }
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    /**
     * Returns the world-space camera origin x coordinate.
     *
     * @return world-space camera origin x coordinate
     */
    public double x() {
        return x;
    }

    /**
     * Returns the world-space camera origin y coordinate.
     *
     * @return world-space camera origin y coordinate
     */
    public double y() {
        return y;
    }

    /**
     * Returns the world-space camera origin z coordinate.
     *
     * @return world-space camera origin z coordinate
     */
    public double z() {
        return z;
    }

    /**
     * Returns the normalized forward-vector x component.
     *
     * @return normalized forward-vector x component
     */
    public float forwardX() {
        return forwardX;
    }

    /**
     * Returns the normalized forward-vector y component.
     *
     * @return normalized forward-vector y component
     */
    public float forwardY() {
        return forwardY;
    }

    /**
     * Returns the normalized forward-vector z component.
     *
     * @return normalized forward-vector z component
     */
    public float forwardZ() {
        return forwardZ;
    }

    /**
     * Returns the normalized right-vector x component.
     *
     * @return normalized right-vector x component
     */
    public float rightX() {
        return rightX;
    }

    /**
     * Returns the normalized right-vector y component.
     *
     * @return normalized right-vector y component
     */
    public float rightY() {
        return rightY;
    }

    /**
     * Returns the normalized right-vector z component.
     *
     * @return normalized right-vector z component
     */
    public float rightZ() {
        return rightZ;
    }

    /**
     * Returns the normalized up-vector x component.
     *
     * @return normalized up-vector x component
     */
    public float upX() {
        return upX;
    }

    /**
     * Returns the normalized up-vector y component.
     *
     * @return normalized up-vector y component
     */
    public float upY() {
        return upY;
    }

    /**
     * Returns the normalized up-vector z component.
     *
     * @return normalized up-vector z component
     */
    public float upZ() {
        return upZ;
    }

    /**
     * Returns the positive tangent of half the horizontal field of view.
     *
     * @return positive tangent of half the horizontal field of view
     */
    public float tanHalfFovX() {
        return tanHalfFovX;
    }

    /**
     * Returns the positive tangent of half the vertical field of view.
     *
     * @return positive tangent of half the vertical field of view
     */
    public float tanHalfFovY() {
        return tanHalfFovY;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CameraState camera)) return false;
        return Double.compare(x, camera.x) == 0
                && Double.compare(y, camera.y) == 0
                && Double.compare(z, camera.z) == 0
                && Float.compare(forwardX, camera.forwardX) == 0
                && Float.compare(forwardY, camera.forwardY) == 0
                && Float.compare(forwardZ, camera.forwardZ) == 0
                && Float.compare(rightX, camera.rightX) == 0
                && Float.compare(rightY, camera.rightY) == 0
                && Float.compare(rightZ, camera.rightZ) == 0
                && Float.compare(upX, camera.upX) == 0
                && Float.compare(upY, camera.upY) == 0
                && Float.compare(upZ, camera.upZ) == 0
                && Float.compare(tanHalfFovX, camera.tanHalfFovX) == 0
                && Float.compare(tanHalfFovY, camera.tanHalfFovY) == 0;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(
                x, y, z,
                forwardX, forwardY, forwardZ,
                rightX, rightY, rightZ,
                upX, upY, upZ,
                tanHalfFovX, tanHalfFovY
        );
    }

    @Override
    public String toString() {
        return "CameraState["
                + "x=" + x
                + ", y=" + y
                + ", z=" + z
                + ", forwardX=" + forwardX
                + ", forwardY=" + forwardY
                + ", forwardZ=" + forwardZ
                + ", rightX=" + rightX
                + ", rightY=" + rightY
                + ", rightZ=" + rightZ
                + ", upX=" + upX
                + ", upY=" + upY
                + ", upZ=" + upZ
                + ", tanHalfFovX=" + tanHalfFovX
                + ", tanHalfFovY=" + tanHalfFovY
                + ']';
    }

    /**
     * Single-thread-confined semantic builder for a perspective camera.
     */
    public static final class Builder {
        private final double x;
        private final double y;
        private final double z;
        private final double targetX;
        private final double targetY;
        private final double targetZ;
        private double worldUpX;
        private double worldUpY = 1.0D;
        private double worldUpZ;
        private double verticalFieldOfViewDegrees = 60.0D;
        private double aspectRatio = 16.0D / 9.0D;

        private Builder(
                double x, double y, double z,
                double targetX, double targetY, double targetZ
        ) {
            requireFinite(x, "x");
            requireFinite(y, "y");
            requireFinite(z, "z");
            requireFinite(targetX, "targetX");
            requireFinite(targetY, "targetY");
            requireFinite(targetZ, "targetZ");
            this.x = x;
            this.y = y;
            this.z = z;
            this.targetX = targetX;
            this.targetY = targetY;
            this.targetZ = targetZ;
        }

        private static double[] normalized(double x, double y, double z, String zeroMessage) {
            double length = Math.hypot(Math.hypot(x, y), z);
            if (!Double.isFinite(length) || length <= 1.0E-12D) {
                throw new IllegalArgumentException(zeroMessage);
            }
            return new double[]{x / length, y / length, z / length};
        }

        /**
         * Selects the approximate world-up direction used to resolve camera roll.
         *
         * @param upX world-up x component
         * @param upY world-up y component
         * @param upZ world-up z component
         * @return this builder
         */
        public Builder worldUp(double upX, double upY, double upZ) {
            requireFinite(upX, "upX");
            requireFinite(upY, "upY");
            requireFinite(upZ, "upZ");
            worldUpX = upX;
            worldUpY = upY;
            worldUpZ = upZ;
            return this;
        }

        /**
         * Selects the vertical perspective field of view.
         *
         * @param degrees finite angle strictly between 0 and 180 degrees
         * @return this builder
         */
        public Builder verticalFieldOfViewDegrees(double degrees) {
            if (!Double.isFinite(degrees) || degrees <= 0.0D || degrees >= 180.0D) {
                throw new IllegalArgumentException("vertical field of view must be in (0, 180) degrees");
            }
            verticalFieldOfViewDegrees = degrees;
            return this;
        }

        /**
         * Selects the viewport width-to-height ratio.
         *
         * @param widthOverHeight finite positive aspect ratio
         * @return this builder
         */
        public Builder aspectRatio(double widthOverHeight) {
            if (!Double.isFinite(widthOverHeight) || widthOverHeight <= 0.0D) {
                throw new IllegalArgumentException("camera aspect ratio must be finite and positive");
            }
            aspectRatio = widthOverHeight;
            return this;
        }

        /**
         * Derives a normalized right-handed basis and validated projection.
         *
         * @return immutable camera state
         * @throws IllegalArgumentException if position equals target, world up is zero or parallel
         *                                  to the view direction, or projection values exceed the float API
         */
        public CameraState build() {
            double[] forward = normalized(
                    targetX - x, targetY - y, targetZ - z,
                    "camera position and target must be distinct"
            );
            double[] worldUp = normalized(
                    worldUpX, worldUpY, worldUpZ,
                    "world-up vector must be non-zero"
            );
            double[] right = normalized(
                    forward[1] * worldUp[2] - forward[2] * worldUp[1],
                    forward[2] * worldUp[0] - forward[0] * worldUp[2],
                    forward[0] * worldUp[1] - forward[1] * worldUp[0],
                    "world-up vector must not be parallel to the view direction"
            );
            double[] up = {
                    right[1] * forward[2] - right[2] * forward[1],
                    right[2] * forward[0] - right[0] * forward[2],
                    right[0] * forward[1] - right[1] * forward[0]
            };
            double tanHalfFovY = Math.tan(Math.toRadians(verticalFieldOfViewDegrees) * 0.5D);
            double tanHalfFovX = tanHalfFovY * aspectRatio;
            if (!Double.isFinite(tanHalfFovX) || !Double.isFinite(tanHalfFovY)
                    || tanHalfFovX > Float.MAX_VALUE || tanHalfFovY > Float.MAX_VALUE) {
                throw new IllegalArgumentException("camera projection exceeds the supported finite float range");
            }
            return new CameraState(
                    x, y, z,
                    (float) forward[0], (float) forward[1], (float) forward[2],
                    (float) right[0], (float) right[1], (float) right[2],
                    (float) up[0], (float) up[1], (float) up[2],
                    (float) tanHalfFovX, (float) tanHalfFovY
            );
        }
    }

    /**
     * Single-thread-confined builder for an explicitly supplied perspective camera basis.
     */
    public static final class ExplicitBasisBuilder {
        private final double x;
        private final double y;
        private final double z;
        private float forwardX;
        private float forwardY;
        private float forwardZ;
        private float rightX;
        private float rightY;
        private float rightZ;
        private float upX;
        private float upY;
        private float upZ;
        private float tanHalfFovX;
        private float tanHalfFovY;

        private ExplicitBasisBuilder(double x, double y, double z) {
            requireFinite(x, "x");
            requireFinite(y, "y");
            requireFinite(z, "z");
            this.x = x;
            this.y = y;
            this.z = z;
        }

        /**
         * Selects the normalized forward direction.
         *
         * @param x forward x component
         * @param y forward y component
         * @param z forward z component
         * @return this builder
         */
        public ExplicitBasisBuilder forward(float x, float y, float z) {
            forwardX = x;
            forwardY = y;
            forwardZ = z;
            return this;
        }

        /**
         * Selects the normalized right direction.
         *
         * @param x right x component
         * @param y right y component
         * @param z right z component
         * @return this builder
         */
        public ExplicitBasisBuilder right(float x, float y, float z) {
            rightX = x;
            rightY = y;
            rightZ = z;
            return this;
        }

        /**
         * Selects the normalized up direction.
         *
         * @param x up x component
         * @param y up y component
         * @param z up z component
         * @return this builder
         */
        public ExplicitBasisBuilder up(float x, float y, float z) {
            upX = x;
            upY = y;
            upZ = z;
            return this;
        }

        /**
         * Selects the horizontal and vertical perspective projection tangents.
         *
         * @param horizontal positive tangent of half the horizontal field of view
         * @param vertical   positive tangent of half the vertical field of view
         * @return this builder
         */
        public ExplicitBasisBuilder projectionTangents(float horizontal, float vertical) {
            tanHalfFovX = horizontal;
            tanHalfFovY = vertical;
            return this;
        }

        /**
         * Validates and creates the immutable explicit camera state.
         *
         * @return immutable camera state
         */
        public CameraState build() {
            return new CameraState(
                    x, y, z,
                    forwardX, forwardY, forwardZ,
                    rightX, rightY, rightZ,
                    upX, upY, upZ,
                    tanHalfFovX, tanHalfFovY
            );
        }
    }
}
