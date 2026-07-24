package top.ceroxe.mcvulkanrt.renderer;

/**
 * Immutable render-thread view state captured at the host level renderer boundary.
 *
 * <p>RT backend code must not retain host camera objects. They are mutable,
 * owned by the sourceEngine renderer, and their lifetime is tied to the render call.
 * This value object copies only the small set of primitive facts the native path
 * needs to make a frame-output decision.</p>
 */
public record RendererFrameState(
        long sequence,
        boolean valid,
        int targetWidth,
        int targetHeight,
        double cameraX,
        double cameraY,
        double cameraZ,
        float cameraPitch,
        float cameraYaw,
        float cameraForwardX,
        float cameraForwardY,
        float cameraForwardZ,
        float cameraRightX,
        float cameraRightY,
        float cameraRightZ,
        float cameraUpX,
        float cameraUpY,
        float cameraUpZ,
        float projection00,
        float projection11,
        float projection22,
        float projection23,
        float projection32,
        float projection33,
        CameraMedium cameraFluidMedium,
        RendererFrameEnvironment frameEnvironment,
        boolean renderBlockOutline,
        boolean renderBlockEntities
) {
    private static final RendererFrameState UNAVAILABLE = new RendererFrameState(
            0L,
            false,
            0,
            0,
            0.0D,
            0.0D,
            0.0D,
            0.0F,
            0.0F,
            0.0F,
            0.0F,
            -1.0F,
            1.0F,
            0.0F,
            0.0F,
            0.0F,
            1.0F,
            0.0F,
            0.0F,
            0.0F,
            0.0F,
            0.0F,
            0.0F,
            0.0F,
            CameraMedium.air(),
            RendererFrameEnvironment.unknown(),
            false,
            false
    );

    public RendererFrameState(
            long sequence,
            boolean valid,
            int targetWidth,
            int targetHeight,
            double cameraX,
            double cameraY,
            double cameraZ,
            float cameraPitch,
            float cameraYaw,
            float cameraForwardX,
            float cameraForwardY,
            float cameraForwardZ,
            float cameraRightX,
            float cameraRightY,
            float cameraRightZ,
            float cameraUpX,
            float cameraUpY,
            float cameraUpZ,
            float projection00,
            float projection11,
            float projection22,
            float projection23,
            float projection32,
            float projection33,
            CameraMedium cameraFluidMedium,
            boolean renderBlockOutline,
            boolean renderBlockEntities
    ) {
        this(
                sequence,
                valid,
                targetWidth,
                targetHeight,
                cameraX,
                cameraY,
                cameraZ,
                cameraPitch,
                cameraYaw,
                cameraForwardX,
                cameraForwardY,
                cameraForwardZ,
                cameraRightX,
                cameraRightY,
                cameraRightZ,
                cameraUpX,
                cameraUpY,
                cameraUpZ,
                projection00,
                projection11,
                projection22,
                projection23,
                projection32,
                projection33,
                cameraFluidMedium,
                RendererFrameEnvironment.unknown(),
                renderBlockOutline,
                renderBlockEntities
        );
    }

    public RendererFrameState(
            long sequence,
            boolean valid,
            int targetWidth,
            int targetHeight,
            double cameraX,
            double cameraY,
            double cameraZ,
            float cameraPitch,
            float cameraYaw,
            float cameraForwardX,
            float cameraForwardY,
            float cameraForwardZ,
            float cameraRightX,
            float cameraRightY,
            float cameraRightZ,
            float cameraUpX,
            float cameraUpY,
            float cameraUpZ,
            float projection00,
            float projection11,
            float projection22,
            float projection23,
            float projection32,
            float projection33,
            boolean renderBlockOutline,
            boolean renderBlockEntities
    ) {
        this(
                sequence,
                valid,
                targetWidth,
                targetHeight,
                cameraX,
                cameraY,
                cameraZ,
                cameraPitch,
                cameraYaw,
                cameraForwardX,
                cameraForwardY,
                cameraForwardZ,
                cameraRightX,
                cameraRightY,
                cameraRightZ,
                cameraUpX,
                cameraUpY,
                cameraUpZ,
                projection00,
                projection11,
                projection22,
                projection23,
                projection32,
                projection33,
                CameraMedium.air(),
                RendererFrameEnvironment.unknown(),
                renderBlockOutline,
                renderBlockEntities
        );
    }

    public RendererFrameState {
        cameraFluidMedium = cameraFluidMedium == null ? CameraMedium.air() : cameraFluidMedium;
        frameEnvironment = frameEnvironment == null ? RendererFrameEnvironment.unknown() : frameEnvironment;
        if (sequence < 0L) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
        if (valid) {
            requirePositive(targetWidth, "targetWidth");
            requirePositive(targetHeight, "targetHeight");
            requireFinite(cameraX, "cameraX");
            requireFinite(cameraY, "cameraY");
            requireFinite(cameraZ, "cameraZ");
            requireFinite(cameraPitch, "cameraPitch");
            requireFinite(cameraYaw, "cameraYaw");
            requireUnitVector(cameraForwardX, cameraForwardY, cameraForwardZ, "cameraForward");
            requireUnitVector(cameraRightX, cameraRightY, cameraRightZ, "cameraRight");
            requireUnitVector(cameraUpX, cameraUpY, cameraUpZ, "cameraUp");
            requireOrthogonal(cameraForwardX, cameraForwardY, cameraForwardZ, cameraRightX, cameraRightY, cameraRightZ,
                    "cameraForward", "cameraRight");
            requireOrthogonal(cameraForwardX, cameraForwardY, cameraForwardZ, cameraUpX, cameraUpY, cameraUpZ,
                    "cameraForward", "cameraUp");
            requireOrthogonal(cameraRightX, cameraRightY, cameraRightZ, cameraUpX, cameraUpY, cameraUpZ,
                    "cameraRight", "cameraUp");
            requireScreenBasisHandedness(
                    cameraForwardX,
                    cameraForwardY,
                    cameraForwardZ,
                    cameraRightX,
                    cameraRightY,
                    cameraRightZ,
                    cameraUpX,
                    cameraUpY,
                    cameraUpZ
            );
        } else {
            targetWidth = 0;
            targetHeight = 0;
            cameraFluidMedium = CameraMedium.air();
        }
    }

    public static RendererFrameState unavailable() {
        return UNAVAILABLE;
    }

    public static RendererFrameState unavailable(long sequence) {
        return new RendererFrameState(
                sequence,
                false,
                0,
                0,
                0.0D,
                0.0D,
                0.0D,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                -1.0F,
                1.0F,
                0.0F,
                0.0F,
                0.0F,
                1.0F,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
            0.0F,
            0.0F,
            CameraMedium.air(),
            RendererFrameEnvironment.unknown(),
            false,
            false
        );
    }

    public String asLogFragment() {
        if (!valid) {
            return "frameState=unavailable, frameStateSeq=" + sequence;
        }
        return "frameStateSeq=" + sequence
                + ", frameTarget=" + targetWidth + "x" + targetHeight
                + ", camera=(" + cameraX + ", " + cameraY + ", " + cameraZ + ")"
                + ", cameraPitch=" + cameraPitch
                + ", cameraYaw=" + cameraYaw
                + ", cameraForward=(" + cameraForwardX + ", " + cameraForwardY + ", " + cameraForwardZ + ")"
                + ", cameraRight=(" + cameraRightX + ", " + cameraRightY + ", " + cameraRightZ + ")"
                + ", cameraUp=(" + cameraUpX + ", " + cameraUpY + ", " + cameraUpZ + ")"
                + ", " + cameraFluidMedium.asLogFragment()
                + ", renderBlockOutline=" + renderBlockOutline
                + ", renderBlockEntities=" + renderBlockEntities;
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void requireUnitVector(float x, float y, float z, String name) {
        requireFinite(x, name + ".x");
        requireFinite(y, name + ".y");
        requireFinite(z, name + ".z");
        float lengthSquared = x * x + y * y + z * z;
        if (Math.abs(lengthSquared - 1.0F) > 1.0e-3F) {
            throw new IllegalArgumentException(name + " must be normalized");
        }
    }

    private static void requireOrthogonal(
            float leftX,
            float leftY,
            float leftZ,
            float rightX,
            float rightY,
            float rightZ,
            String leftName,
            String rightName
    ) {
        float dot = leftX * rightX + leftY * rightY + leftZ * rightZ;
        if (Math.abs(dot) > 1.0e-3F) {
            throw new IllegalArgumentException(leftName + " must be orthogonal to " + rightName);
        }
    }

    private static void requireScreenBasisHandedness(
            float forwardX,
            float forwardY,
            float forwardZ,
            float rightX,
            float rightY,
            float rightZ,
            float upX,
            float upY,
            float upZ
    ) {
        /*
         * The raygen shader expands NDC as forward + right*x + up*y. For the
         * host camera convention used here, up x right must point forward;
         * otherwise every mathematically unit/orthogonal basis still renders a
         * mirrored RT image.
         */
        float crossX = upY * rightZ - upZ * rightY;
        float crossY = upZ * rightX - upX * rightZ;
        float crossZ = upX * rightY - upY * rightX;
        float alignment = crossX * forwardX + crossY * forwardY + crossZ * forwardZ;
        if (alignment < 1.0F - 1.0e-3F) {
            throw new IllegalArgumentException("camera basis must preserve host screen handedness");
        }
    }
}
