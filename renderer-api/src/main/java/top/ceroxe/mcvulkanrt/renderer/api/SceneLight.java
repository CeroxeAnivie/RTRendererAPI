package top.ceroxe.mcvulkanrt.renderer.api;

/** Persistent analytic light independent of source-engine light objects. */
public record SceneLight(
        long id,
        Type type,
        double x,
        double y,
        double z,
        float directionX,
        float directionY,
        float directionZ,
        float red,
        float green,
        float blue,
        float intensity,
        float range,
        float innerConeCosine,
        float outerConeCosine,
        boolean castsShadow
) {
    public SceneLight {
        MaterialAsset.requireId(id, "id");
        type = java.util.Objects.requireNonNull(type, "type");
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(z, "z");
        requireNonNegative(red, "red");
        requireNonNegative(green, "green");
        requireNonNegative(blue, "blue");
        requireNonNegative(intensity, "intensity");
        requireNonNegative(range, "range");
        requireFinite(directionX, "directionX");
        requireFinite(directionY, "directionY");
        requireFinite(directionZ, "directionZ");
        requireFinite(innerConeCosine, "innerConeCosine");
        requireFinite(outerConeCosine, "outerConeCosine");
        if (type != Type.POINT) {
            requireDirection(directionX, directionY, directionZ);
        } else if (directionX != 0.0F || directionY != 0.0F || directionZ != 0.0F) {
            throw new IllegalArgumentException("point light direction must use the canonical zero vector");
        }
        if (type == Type.SPOT) {
            if (!Float.isFinite(innerConeCosine) || !Float.isFinite(outerConeCosine)
                    || innerConeCosine < -1.0F || innerConeCosine > 1.0F
                    || outerConeCosine < -1.0F || outerConeCosine > 1.0F
                    || innerConeCosine < outerConeCosine) {
                throw new IllegalArgumentException("spot cone cosines must satisfy inner >= outer in [-1, 1]");
            }
        }
        if (type != Type.DIRECTIONAL && range <= 0.0F) {
            throw new IllegalArgumentException("local lights require positive range");
        }
        if (type == Type.DIRECTIONAL && range != 0.0F) {
            throw new IllegalArgumentException("directional light range must use canonical value 0");
        }
        if (type != Type.SPOT && (innerConeCosine != 0.0F || outerConeCosine != 0.0F)) {
            throw new IllegalArgumentException("non-spot cone values must use canonical value 0");
        }
    }

    public enum Type {
        DIRECTIONAL,
        POINT,
        SPOT
    }

    private static void requireDirection(float x, float y, float z) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
            throw new IllegalArgumentException("light direction must be finite");
        }
        float lengthSquared = x * x + y * y + z * z;
        if (Math.abs(lengthSquared - 1.0F) > 2.0E-3F) {
            throw new IllegalArgumentException("light direction must be normalized");
        }
    }

    private static void requireNonNegative(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0F) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
