package top.ceroxe.rt.renderer.nvidia;

import top.ceroxe.rt.renderer.api.CameraState;
import top.ceroxe.rt.renderer.api.DepthProjectionState;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.feature.VulkanTemporalFrameInput;

/** Pins NRD's column-major, column-vector, non-jittered temporal camera contract. */
public final class NrdFrameConstantsSelfTest {
    private NrdFrameConstantsSelfTest() {
    }

    public static void main(String[] arguments) {
        DepthProjectionState projection = DepthProjectionState.vulkanPerspective(0.25F, 4096.0F);
        CameraState origin = camera(0.0D, 0.0D, 0.0D);
        RenderFrameRequest firstRequest = request(9L, origin, projection);
        NrdFrameConstants first = NrdFrameConstants.from(new VulkanTemporalFrameInput(
                firstRequest, origin, projection, 9L, false,
                0.125F, -0.25F, -0.375F, 0.25F
        ), 1280, 720, true);

        require(first.reset(), "invalid initial history must clear NRD accumulation");
        require(matrixClose(first.viewToClipMatrix(), first.viewToClipMatrixPrev()),
                "a reset frame must not fabricate a previous projection");
        require(matrixClose(first.worldToViewMatrix(), first.worldToViewMatrixPrev()),
                "a reset frame must not fabricate previous camera motion");
        require(close(first.previousJitterX(), first.currentJitterX())
                        && close(first.previousJitterY(), first.currentJitterY()),
                "a reset frame must not expose stale previous jitter");
        require(close(first.motionVectorScaleX(), 1.0F / 1280.0F)
                        && close(first.motionVectorScaleY(), 1.0F / 720.0F),
                "pixel motion must be normalized exactly once by NRD");

        CameraState previous = camera(1.0E12D, 0.0D, 0.0D);
        CameraState current = camera(1.0E12D + 1.0D, 0.0D, 0.0D);
        DepthProjectionState currentProjection = DepthProjectionState.vulkanPerspective(0.5F, 2048.0F);
        NrdFrameConstants moved = NrdFrameConstants.from(new VulkanTemporalFrameInput(
                request(10L, current, currentProjection), previous, projection, 9L, true,
                -0.25F, 0.125F, 0.125F, -0.25F
        ), 1280, 720, false);

        require(!moved.reset(), "continuous history must remain available to NRD");
        require(close(moved.previousJitterX(), 0.125F) && close(moved.previousJitterY(), -0.25F),
                "NRD jitter must remain in pixel units, not normalized UV units");
        require(!matrixClose(moved.viewToClipMatrix(), moved.viewToClipMatrixPrev()),
                "current and previous projection changes must remain explicit");
        float[] currentView = transformColumnMajor(
                moved.worldToViewMatrix(), new float[]{0.0F, 0.0F, -10.0F, 1.0F}
        );
        float[] previousView = transformColumnMajor(
                moved.worldToViewMatrixPrev(), new float[]{0.0F, 0.0F, -10.0F, 1.0F}
        );
        require(close(currentView[0], 0.0F) && close(previousView[0], 1.0F),
                "camera-relative matrices must preserve one-unit motion at distant world origins");
        float[] clip = transformColumnMajor(moved.viewToClipMatrix(), currentView);
        require(close(clip[3], 10.0F) && clip[2] > 0.0F,
                "NRD column-major projection must preserve Vulkan forward-Z perspective");

        float[] mutableCopy = moved.worldToViewMatrixPrev();
        mutableCopy[12] = 999.0F;
        require(close(moved.worldToViewMatrixPrev()[12], 1.0F),
                "JNI matrix accessors must not expose mutable constant storage");
        System.out.println("NrdFrameConstantsSelfTest passed");
    }

    private static RenderFrameRequest request(
            long sequence,
            CameraState camera,
            DepthProjectionState projection
    ) {
        return RenderFrameRequest.builder(sequence, 1920, 1080, camera)
                .depthProjection(projection)
                .build();
    }

    private static CameraState camera(double x, double y, double z) {
        return CameraState.explicitBasis(x, y, z)
                .forward(0.0F, 0.0F, -1.0F)
                .right(1.0F, 0.0F, 0.0F)
                .up(0.0F, 1.0F, 0.0F)
                .projectionTangents(1.0F, 0.75F)
                .build();
    }

    private static float[] transformColumnMajor(float[] matrix, float[] vector) {
        float[] result = new float[4];
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                result[row] += matrix[column * 4 + row] * vector[column];
            }
        }
        return result;
    }

    private static boolean matrixClose(float[] left, float[] right) {
        for (int index = 0; index < left.length; index++) {
            if (!close(left[index], right[index])) return false;
        }
        return true;
    }

    private static boolean close(float actual, float expected) {
        return Math.abs(actual - expected) < 1.0E-4F;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
