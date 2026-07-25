package top.ceroxe.rt.renderer;

/**
 * Immutable render-thread view state captured at the host level renderer boundary.
 *
 * <p>RT backend code must not retain integration-layer camera objects. They are mutable,
 * owned by the caller, and their lifetime is tied to the render call.
 * This value object copies only the small set of primitive facts the native path
 * needs to make a frame-output decision.</p>
 *
 * @param sequence            non-negative frame sequence
 * @param valid               whether the frame state is available for rendering
 * @param targetWidth         output width in pixels
 * @param targetHeight        output height in pixels
 * @param cameraX             world-space camera x coordinate
 * @param cameraY             world-space camera y coordinate
 * @param cameraZ             world-space camera z coordinate
 * @param cameraPitch         camera pitch in degrees
 * @param cameraYaw           camera yaw in degrees
 * @param cameraForwardX      normalized forward-vector x component
 * @param cameraForwardY      normalized forward-vector y component
 * @param cameraForwardZ      normalized forward-vector z component
 * @param cameraRightX        normalized right-vector x component
 * @param cameraRightY        normalized right-vector y component
 * @param cameraRightZ        normalized right-vector z component
 * @param cameraUpX           normalized up-vector x component
 * @param cameraUpY           normalized up-vector y component
 * @param cameraUpZ           normalized up-vector z component
 * @param projection00        projection matrix element at row zero, column zero
 * @param projection11        projection matrix element at row one, column one
 * @param projection22        projection matrix element at row two, column two
 * @param projection23        projection matrix element at row two, column three
 * @param projection32        projection matrix element at row three, column two
 * @param projection33        projection matrix element at row three, column three
 * @param cameraFluidMedium   immutable camera-medium state
 * @param frameEnvironment    immutable per-frame environment constants
 * @param renderBlockOutline  whether selection outlines are enabled
 * @param renderBlockEntities whether attached scene objects are enabled
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

    /**
     * Creates a frame state using an unknown frame environment.
     *
     * <p>This compatibility constructor preserves the explicitly supplied camera medium while
     * defaulting environment data that was not available to earlier callers.</p>
     *
     * @param sequence            non-negative frame sequence
     * @param valid               whether the frame state is available for rendering
     * @param targetWidth         output width in pixels
     * @param targetHeight        output height in pixels
     * @param cameraX             world-space camera x coordinate
     * @param cameraY             world-space camera y coordinate
     * @param cameraZ             world-space camera z coordinate
     * @param cameraPitch         camera pitch in degrees
     * @param cameraYaw           camera yaw in degrees
     * @param cameraForwardX      normalized forward-vector x component
     * @param cameraForwardY      normalized forward-vector y component
     * @param cameraForwardZ      normalized forward-vector z component
     * @param cameraRightX        normalized right-vector x component
     * @param cameraRightY        normalized right-vector y component
     * @param cameraRightZ        normalized right-vector z component
     * @param cameraUpX           normalized up-vector x component
     * @param cameraUpY           normalized up-vector y component
     * @param cameraUpZ           normalized up-vector z component
     * @param projection00        projection matrix element at row zero, column zero
     * @param projection11        projection matrix element at row one, column one
     * @param projection22        projection matrix element at row two, column two
     * @param projection23        projection matrix element at row two, column three
     * @param projection32        projection matrix element at row three, column two
     * @param projection33        projection matrix element at row three, column three
     * @param cameraFluidMedium   immutable camera-medium state; {@code null} selects air
     * @param renderBlockOutline  whether selection outlines are enabled
     * @param renderBlockEntities whether attached scene objects are enabled
     * @throws IllegalArgumentException if the sequence is negative or an available frame has
     *                                  invalid dimensions, non-finite camera values, or an invalid camera basis
     */
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

    /**
     * Creates a frame state using the default air medium and an unknown frame environment.
     *
     * <p>This compatibility constructor is intended for callers that do not provide
     * camera-medium or environment metadata.</p>
     *
     * @param sequence            non-negative frame sequence
     * @param valid               whether the frame state is available for rendering
     * @param targetWidth         output width in pixels
     * @param targetHeight        output height in pixels
     * @param cameraX             world-space camera x coordinate
     * @param cameraY             world-space camera y coordinate
     * @param cameraZ             world-space camera z coordinate
     * @param cameraPitch         camera pitch in degrees
     * @param cameraYaw           camera yaw in degrees
     * @param cameraForwardX      normalized forward-vector x component
     * @param cameraForwardY      normalized forward-vector y component
     * @param cameraForwardZ      normalized forward-vector z component
     * @param cameraRightX        normalized right-vector x component
     * @param cameraRightY        normalized right-vector y component
     * @param cameraRightZ        normalized right-vector z component
     * @param cameraUpX           normalized up-vector x component
     * @param cameraUpY           normalized up-vector y component
     * @param cameraUpZ           normalized up-vector z component
     * @param projection00        projection matrix element at row zero, column zero
     * @param projection11        projection matrix element at row one, column one
     * @param projection22        projection matrix element at row two, column two
     * @param projection23        projection matrix element at row two, column three
     * @param projection32        projection matrix element at row three, column two
     * @param projection33        projection matrix element at row three, column three
     * @param renderBlockOutline  whether selection outlines are enabled
     * @param renderBlockEntities whether attached scene objects are enabled
     * @throws IllegalArgumentException if the sequence is negative or an available frame has
     *                                  invalid dimensions, non-finite camera values, or an invalid camera basis
     */
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

    /**
     * Validates and normalizes a frame-state instance before publication.
     *
     * <p>Unavailable states discard target dimensions and camera-medium information because
     * downstream renderers must not mistake stale values for usable frame data. Null medium
     * and environment values are replaced with their neutral representations.</p>
     *
     * @param sequence            non-negative frame sequence
     * @param valid               whether the frame state is available for rendering
     * @param targetWidth         output width in pixels
     * @param targetHeight        output height in pixels
     * @param cameraX             world-space camera x coordinate
     * @param cameraY             world-space camera y coordinate
     * @param cameraZ             world-space camera z coordinate
     * @param cameraPitch         camera pitch in degrees
     * @param cameraYaw           camera yaw in degrees
     * @param cameraForwardX      normalized forward-vector x component
     * @param cameraForwardY      normalized forward-vector y component
     * @param cameraForwardZ      normalized forward-vector z component
     * @param cameraRightX        normalized right-vector x component
     * @param cameraRightY        normalized right-vector y component
     * @param cameraRightZ        normalized right-vector z component
     * @param cameraUpX           normalized up-vector x component
     * @param cameraUpY           normalized up-vector y component
     * @param cameraUpZ           normalized up-vector z component
     * @param projection00        projection matrix element at row zero, column zero
     * @param projection11        projection matrix element at row one, column one
     * @param projection22        projection matrix element at row two, column two
     * @param projection23        projection matrix element at row two, column three
     * @param projection32        projection matrix element at row three, column two
     * @param projection33        projection matrix element at row three, column three
     * @param cameraFluidMedium   immutable camera-medium state; {@code null} selects air
     * @param frameEnvironment    immutable per-frame environment constants; {@code null} selects unknown values
     * @param renderBlockOutline  whether selection outlines are enabled
     * @param renderBlockEntities whether attached scene objects are enabled
     * @throws IllegalArgumentException if the sequence is negative or an available frame has
     *                                  invalid dimensions, non-finite camera values, or an invalid camera basis
     */
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

    /**
     * Returns the shared unavailable state at sequence zero.
     *
     * @return the shared unavailable frame state
     */
    public static RendererFrameState unavailable() {
        return UNAVAILABLE;
    }

    /**
     * Creates an unavailable state associated with a specific frame sequence.
     *
     * @param sequence non-negative frame sequence
     * @return an unavailable frame state for the supplied sequence
     * @throws IllegalArgumentException if {@code sequence} is negative
     */
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

    /**
     * Formats the frame state as a compact diagnostic fragment.
     *
     * @return a stable log fragment describing availability and relevant frame values
     */
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
}
