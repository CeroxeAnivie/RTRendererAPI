package top.ceroxe.mcvulkanrt.renderer;

/**
 * Shared camera-ray math used by both Java diagnostics and the Vulkan raygen
 * uniform packer.
 *
 * <p>The host level renderer boundary exposes several matrices with similar-looking
 * element names. Keeping the ray scale derivation in one place prevents the RT
 * path and the diagnostics from drifting when host changes which matrix is
 * passed through a render hook.</p>
 */
public final class CameraRayMath {
    private static final float EPSILON = 1.0e-6F;
    private static final float DEFAULT_VERTICAL_FOV_DEGREES = 70.0F;
    private static final float MIN_VERTICAL_TAN = tangentDegrees(15.0F);
    private static final float MAX_VERTICAL_TAN = tangentDegrees(65.0F);

    private CameraRayMath() {
    }

    public static RayScale rayScale(RendererFrameState frameState, int outputWidth, int outputHeight) {
        if (frameState == null || !frameState.valid()) {
            throw new IllegalArgumentException("frameState must be valid");
        }
        if (outputWidth <= 0 || outputHeight <= 0) {
            throw new IllegalArgumentException("output dimensions must be positive");
        }

        float aspect = outputWidth / (float) outputHeight;
        if (!Float.isFinite(aspect) || aspect <= EPSILON) {
            throw new IllegalArgumentException("output aspect ratio must be positive and finite");
        }

        float defaultVertical = tangentDegrees(DEFAULT_VERTICAL_FOV_DEGREES * 0.5F);
        float vertical = projectionTanOrNaN(frameState.projection11(), MIN_VERTICAL_TAN, MAX_VERTICAL_TAN);
        boolean verticalFromProjection = Float.isFinite(vertical);
        if (!verticalFromProjection) {
            vertical = defaultVertical;
        }

        float horizontal = Float.NaN;
        boolean horizontalFromProjection = false;
        if (verticalFromProjection) {
            horizontal = projectionTanOrNaN(
                    frameState.projection00(),
                    MIN_VERTICAL_TAN * Math.min(aspect, 1.0F),
                    MAX_VERTICAL_TAN * Math.max(aspect, 1.0F)
            );
            horizontalFromProjection = Float.isFinite(horizontal);
        }
        if (!horizontalFromProjection) {
            horizontal = vertical * aspect;
        }

        return new RayScale(horizontal, vertical, horizontalFromProjection, verticalFromProjection);
    }

    public static RayDirection screenRay(RendererFrameState frameState, float ndcX, float ndcY, RayScale rayScale) {
        if (frameState == null || !frameState.valid()) {
            throw new IllegalArgumentException("frameState must be valid");
        }
        if (!Float.isFinite(ndcX) || !Float.isFinite(ndcY)) {
            throw new IllegalArgumentException("screen coordinates must be finite");
        }
        if (rayScale == null) {
            throw new IllegalArgumentException("rayScale must not be null");
        }

        float x = frameState.cameraForwardX()
                + frameState.cameraRightX() * ndcX * rayScale.horizontalTan()
                + frameState.cameraUpX() * ndcY * rayScale.verticalTan();
        float y = frameState.cameraForwardY()
                + frameState.cameraRightY() * ndcX * rayScale.horizontalTan()
                + frameState.cameraUpY() * ndcY * rayScale.verticalTan();
        float z = frameState.cameraForwardZ()
                + frameState.cameraRightZ() * ndcX * rayScale.horizontalTan()
                + frameState.cameraUpZ() * ndcY * rayScale.verticalTan();
        return RayDirection.normalized(x, y, z);
    }

    private static float projectionTanOrNaN(float projectionTerm, float minInclusive, float maxInclusive) {
        if (!Float.isFinite(projectionTerm) || Math.abs(projectionTerm) <= EPSILON) {
            return Float.NaN;
        }
        float tangent = 1.0F / Math.abs(projectionTerm);
        if (!Float.isFinite(tangent) || tangent < minInclusive || tangent > maxInclusive) {
            return Float.NaN;
        }
        return tangent;
    }

    private static float tangentDegrees(float degrees) {
        return (float) Math.tan(Math.toRadians(degrees));
    }

    public record RayScale(
            float horizontalTan,
            float verticalTan,
            boolean horizontalFromProjection,
            boolean verticalFromProjection
    ) {
        public RayScale {
            requirePositiveFinite(horizontalTan, "horizontalTan");
            requirePositiveFinite(verticalTan, "verticalTan");
        }

        public String asLogFragment() {
            return "rayScale{tanHalfFov=(" + horizontalTan + ", " + verticalTan + ")"
                    + ", source=(horizontal=" + source(horizontalFromProjection)
                    + ", vertical=" + source(verticalFromProjection) + ")}";
        }

        private static String source(boolean fromProjection) {
            return fromProjection ? "projection" : "fallback";
        }

        private static void requirePositiveFinite(float value, String name) {
            if (!Float.isFinite(value) || value <= 0.0F) {
                throw new IllegalArgumentException(name + " must be positive and finite");
            }
        }
    }

    public record RayDirection(float x, float y, float z) {
        public RayDirection {
            requireFinite(x, "x");
            requireFinite(y, "y");
            requireFinite(z, "z");
            float lengthSquared = x * x + y * y + z * z;
            if (Math.abs(lengthSquared - 1.0F) > 1.0e-3F) {
                throw new IllegalArgumentException("ray direction must be normalized");
            }
        }

        private static RayDirection normalized(float x, float y, float z) {
            float lengthSquared = x * x + y * y + z * z;
            if (!Float.isFinite(lengthSquared) || lengthSquared < EPSILON * EPSILON) {
                throw new IllegalArgumentException("ray direction must have non-zero finite length");
            }
            float inverseLength = FastInverseSqrt.inverseSqrt(lengthSquared);
            return new RayDirection(x * inverseLength, y * inverseLength, z * inverseLength);
        }

        public String asLogFragment() {
            return "(" + x + ", " + y + ", " + z + ")";
        }

        private static void requireFinite(float value, String name) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException(name + " must be finite");
            }
        }
    }
}
