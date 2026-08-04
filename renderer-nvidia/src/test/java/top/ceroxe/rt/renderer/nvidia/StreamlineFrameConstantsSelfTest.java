package top.ceroxe.rt.renderer.nvidia;

import top.ceroxe.rt.renderer.api.CameraState;
import top.ceroxe.rt.renderer.api.DepthProjectionState;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.feature.VulkanTemporalFrameInput;

/** Pins the forward-Z and temporal matrix convention consumed by Streamline. */
public final class StreamlineFrameConstantsSelfTest {
    private StreamlineFrameConstantsSelfTest() {
    }

    public static void main(String[] arguments) {
        CameraState camera = camera(0.0D, 0.0D, 0.0D);
        RenderFrameRequest request = RenderFrameRequest.builder(9L, 1920, 1080, camera)
                .depthProjection(DepthProjectionState.vulkanPerspective(0.25F, 4096.0F))
                .build();
        StreamlineFrameConstants first = StreamlineFrameConstants.from(new VulkanTemporalFrameInput(
                request, camera, request.depthProjection(), 9L, false, 0.125F, -0.25F, 0.0F, 0.0F
        ), 1280, 720);
        require(first.reset(), "first frame must reset Streamline history");
        require(close(first.motionScaleX(), 1.0F / 1280.0F) && close(first.motionScaleY(), 1.0F / 720.0F),
                "motion vectors must be normalized in render pixels");
        require(close(first.currentJitterX(), 0.125F) && close(first.currentJitterY(), -0.25F),
                "pixel-space jitter must remain separate from Streamline projection matrices");
        require(close(first.cameraViewToClip()[10], -4096.0F / 4095.75F)
                        && close(first.cameraViewToClip()[11], -1.0F)
                        && close(first.cameraViewToClip()[14], -(4096.0F * 0.25F) / 4095.75F),
                "row-major Vulkan forward-Z projection changed");
        require(identity(multiply(first.cameraViewToClip(), first.clipToCameraView())),
                "view-to-clip and clip-to-view must be inverse projection matrices");
        require(identity(first.clipToPrevClip()), "first frame must not fabricate temporal camera motion");

        CameraState moved = camera(1.0D, 0.0D, 0.0D);
        StreamlineFrameConstants next = StreamlineFrameConstants.from(new VulkanTemporalFrameInput(
                RenderFrameRequest.builder(10L, 1920, 1080, moved)
                        .depthProjection(request.depthProjection())
                        .build(), camera, request.depthProjection(),
                9L, true, 0.0F, 0.0F, 0.125F, -0.25F
        ), 1280, 720);
        require(!next.reset(), "connected temporal frame must preserve history");
        require(identity(multiply(next.clipToPrevClip(), next.prevClipToClip())),
                "current/previous clip transforms must be inverse pairs");
        require(matrixClose(first.cameraViewToClip(), next.cameraViewToClip()),
                "view-to-clip projection must not contain camera translation");
        requireTransformsWorldPoint(
                next.clipToPrevClip(),
                moved, request.depthProjection(),
                camera, request.depthProjection(),
                new float[]{0.0F, 0.0F, -10.0F, 1.0F},
                "camera translation"
        );

        CameraState rotated = rotatedCamera(4.0D, 2.0D, -3.0D);
        DepthProjectionState changedProjection = DepthProjectionState.vulkanPerspective(0.5F, 2048.0F);
        StreamlineFrameConstants rotatedFrame = StreamlineFrameConstants.from(new VulkanTemporalFrameInput(
                RenderFrameRequest.builder(11L, 1920, 1080, rotated)
                        .depthProjection(changedProjection)
                        .build(), moved, request.depthProjection(),
                10L, true, -0.25F, 0.125F, 0.0F, 0.0F
        ), 1280, 720);
        requireTransformsWorldPoint(
                rotatedFrame.clipToPrevClip(),
                rotated, changedProjection,
                moved, request.depthProjection(),
                new float[]{-12.0F, 2.0F, -12.0F, 1.0F},
                "camera rotation and projection change"
        );

        CameraState distantPrevious = camera(1.0E12D, 0.0D, 0.0D);
        CameraState distantCurrent = camera(1.0E12D + 1.0D, 0.0D, 0.0D);
        StreamlineFrameConstants distantFrame = StreamlineFrameConstants.from(new VulkanTemporalFrameInput(
                RenderFrameRequest.builder(12L, 1920, 1080, distantCurrent)
                        .depthProjection(request.depthProjection())
                        .build(), distantPrevious, request.depthProjection(),
                11L, true, 0.0F, 0.0F, 0.0F, 0.0F
        ), 1280, 720);
        require(!identity(distantFrame.clipToPrevClip()),
                "camera-centered temporal matrices must preserve motion at distant world origins");
        System.out.println("StreamlineFrameConstantsSelfTest passed");
    }

    private static CameraState camera(double x, double y, double z) {
        return CameraState.explicitBasis(x, y, z)
                .forward(0.0F, 0.0F, -1.0F)
                .right(1.0F, 0.0F, 0.0F)
                .up(0.0F, 1.0F, 0.0F)
                .projectionTangents(1.0F, 0.75F)
                .build();
    }

    private static CameraState rotatedCamera(double x, double y, double z) {
        return CameraState.explicitBasis(x, y, z)
                .forward(-1.0F, 0.0F, 0.0F)
                .right(0.0F, 0.0F, -1.0F)
                .up(0.0F, 1.0F, 0.0F)
                .projectionTangents(0.8F, 0.6F)
                .build();
    }

    private static void requireTransformsWorldPoint(
            float[] clipToPreviousClip,
            CameraState currentCamera,
            DepthProjectionState currentProjection,
            CameraState previousCamera,
            DepthProjectionState previousProjection,
            float[] worldPoint,
            String scenario
    ) {
        float[] currentClip = project(worldPoint, currentCamera, currentProjection);
        float[] expectedPreviousClip = project(worldPoint, previousCamera, previousProjection);
        float[] actualPreviousClip = transformRow(currentClip, clipToPreviousClip);
        require(vectorClose(actualPreviousClip, expectedPreviousClip),
                scenario + " clip-to-previous-clip transform is inconsistent with direct projection");
    }

    private static float[] project(float[] point, CameraState camera, DepthProjectionState projection) {
        float dx = point[0] - (float) camera.x();
        float dy = point[1] - (float) camera.y();
        float dz = point[2] - (float) camera.z();
        float viewX = dx * camera.rightX() + dy * camera.rightY() + dz * camera.rightZ();
        float viewY = dx * camera.upX() + dy * camera.upY() + dz * camera.upZ();
        float viewZ = -(dx * camera.forwardX() + dy * camera.forwardY() + dz * camera.forwardZ());
        float near = projection.nearPlane();
        float far = projection.farPlane();
        float range = far - near;
        return new float[]{
                viewX / camera.tanHalfFovX(),
                viewY / camera.tanHalfFovY(),
                (-far / range) * viewZ - (far * near / range),
                -viewZ
        };
    }

    private static float[] transformRow(float[] vector, float[] matrix) {
        float[] result = new float[4];
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) result[column] += vector[row] * matrix[row * 4 + column];
        }
        return result;
    }

    private static float[] multiply(float[] left, float[] right) {
        float[] result = new float[16];
        for (int row = 0; row < 4; row++) for (int column = 0; column < 4; column++)
            for (int pivot = 0; pivot < 4; pivot++) result[row * 4 + column] += left[row * 4 + pivot] * right[pivot * 4 + column];
        return result;
    }

    private static boolean identity(float[] matrix) {
        for (int row = 0; row < 4; row++) for (int column = 0; column < 4; column++)
            if (!close(matrix[row * 4 + column], row == column ? 1.0F : 0.0F)) return false;
        return true;
    }

    private static boolean matrixClose(float[] left, float[] right) {
        for (int index = 0; index < left.length; index++) if (!close(left[index], right[index])) return false;
        return true;
    }

    private static boolean vectorClose(float[] left, float[] right) {
        for (int index = 0; index < left.length; index++) {
            float tolerance = 1.0E-3F * Math.max(1.0F, Math.max(Math.abs(left[index]), Math.abs(right[index])));
            if (Math.abs(left[index] - right[index]) > tolerance) return false;
        }
        return true;
    }

    private static boolean close(float actual, float expected) { return Math.abs(actual - expected) < 1.0E-4F; }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
