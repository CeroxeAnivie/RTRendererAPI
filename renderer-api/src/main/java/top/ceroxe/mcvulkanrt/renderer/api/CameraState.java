package top.ceroxe.mcvulkanrt.renderer.api;

/** Immutable world-space camera basis and primary-ray projection. */
public record CameraState(
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
    private static final float UNIT_TOLERANCE = 2.0E-3F;
    private static final float ORTHOGONAL_TOLERANCE = 3.0E-3F;

    public CameraState {
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(z, "z");
        requireUnit(forwardX, forwardY, forwardZ, "forward");
        requireUnit(rightX, rightY, rightZ, "right");
        requireUnit(upX, upY, upZ, "up");
        requireOrthogonal(forwardX, forwardY, forwardZ, rightX, rightY, rightZ, "forward/right");
        requireOrthogonal(forwardX, forwardY, forwardZ, upX, upY, upZ, "forward/up");
        requireOrthogonal(rightX, rightY, rightZ, upX, upY, upZ, "right/up");
        if (!Float.isFinite(tanHalfFovX) || tanHalfFovX <= 0.0F
                || !Float.isFinite(tanHalfFovY) || tanHalfFovY <= 0.0F) {
            throw new IllegalArgumentException("camera FOV tangents must be finite and positive");
        }
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

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
