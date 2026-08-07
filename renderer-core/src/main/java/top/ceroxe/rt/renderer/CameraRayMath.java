package top.ceroxe.rt.renderer;

import top.ceroxe.rt.renderer.api.ExactProjectionState;

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

    /**
     * Uses the public exact clip mapping without reducing it to a guessed FOV. This mirrors the
     * GPUScene raygen contract and gives host diagnostics a deterministic CPU reference.
     *
     * @param projection immutable exact projection mapping
     * @param pixelX top-left-origin pixel x coordinate
     * @param pixelY top-left-origin pixel y coordinate
     * @return normalized world-space primary ray
     */
    public static ExactProjectionState.Ray exactScreenRay(
            ExactProjectionState projection,
            double pixelX,
            double pixelY
    ) {
        if (projection == null) throw new IllegalArgumentException("projection must not be null");
        return projection.rayForPixel(pixelX, pixelY);
    }

    /**
     * 从投影矩阵与输出尺寸解析射线半视场切值。
     *
     * @param frameState 有效的相机帧状态
     * @param outputWidth 输出宽度，必须为正数
     * @param outputHeight 输出高度，必须为正数
     * @return 已校验的水平与垂直射线缩放
     */
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

    /**
     * 生成指定标准化屏幕坐标对应的世界空间单位射线。
     *
     * @param frameState 有效的相机帧状态
     * @param ndcX 标准化设备坐标 X
     * @param ndcY 标准化设备坐标 Y
     * @param rayScale 当前输出使用的射线缩放
     * @return 归一化后的世界空间射线方向
     */
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

    /**
     * 光线生成阶段使用的水平与垂直半视场切值。
     *
     * @param horizontalTan 水平半视场的正有限切值
     * @param verticalTan 垂直半视场的正有限切值
     * @param horizontalFromProjection 水平值是否直接来自投影矩阵
     * @param verticalFromProjection 垂直值是否直接来自投影矩阵
     */
    public record RayScale(
            float horizontalTan,
            float verticalTan,
            boolean horizontalFromProjection,
            boolean verticalFromProjection
    ) {
        /**
         * 校验两个缩放值均为有限正数。
         */
        public RayScale {
            requirePositiveFinite(horizontalTan, "horizontalTan");
            requirePositiveFinite(verticalTan, "verticalTan");
        }

        /**
         * 将射线缩放及其解析来源格式化为诊断字段。
         *
         * @return 包含缩放值及其来源的单行诊断片段
         */
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

    /**
     * 世界空间归一化射线方向。
     *
     * @param x X 分量
     * @param y Y 分量
     * @param z Z 分量
     */
    public record RayDirection(float x, float y, float z) {
        /**
         * 校验所有分量有限且向量已归一化。
         */
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

        /**
         * 将单位方向向量格式化为诊断字段。
         *
         * @return 适合单行诊断日志的向量文本
         */
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
