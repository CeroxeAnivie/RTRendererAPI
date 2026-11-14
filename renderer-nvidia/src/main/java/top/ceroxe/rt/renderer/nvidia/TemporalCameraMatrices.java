package top.ceroxe.rt.renderer.nvidia;

import top.ceroxe.rt.renderer.api.CameraState;
import top.ceroxe.rt.renderer.api.DepthProjectionState;
import top.ceroxe.rt.renderer.feature.VulkanTemporalFrameInput;

import java.util.Objects;

/**
 * Camera-relative, non-jittered matrices shared by NVIDIA temporal integrations.
 *
 * <p>The canonical arrays are row-major matrices for row-vector multiplication. The same bytes
 * are the column-major storage of the transposed column-vector matrix required by NRD. Keeping
 * that transpose equivalence in one class prevents Streamline and NRD from silently developing
 * two camera models.</p>
 */
final class TemporalCameraMatrices {
    private final float[] currentViewToClip;
    private final float[] previousViewToClip;
    private final float[] currentClipToView;
    private final float[] currentClipToPreviousClip;
    private final float[] previousClipToCurrentClip;
    private final float[] currentWorldToView;
    private final float[] previousWorldToView;

    private TemporalCameraMatrices(
            float[] currentViewToClip,
            float[] previousViewToClip,
            float[] currentClipToView,
            float[] currentClipToPreviousClip,
            float[] previousClipToCurrentClip,
            float[] currentWorldToView,
            float[] previousWorldToView
    ) {
        this.currentViewToClip = currentViewToClip;
        this.previousViewToClip = previousViewToClip;
        this.currentClipToView = currentClipToView;
        this.currentClipToPreviousClip = currentClipToPreviousClip;
        this.previousClipToCurrentClip = previousClipToCurrentClip;
        this.currentWorldToView = currentWorldToView;
        this.previousWorldToView = previousWorldToView;
    }

    static TemporalCameraMatrices from(VulkanTemporalFrameInput input) {
        VulkanTemporalFrameInput checked = Objects.requireNonNull(input, "input");
        CameraState currentCamera = checked.request().camera();
        CameraState previousCamera = checked.historyValid() ? checked.previousCamera() : currentCamera;
        DepthProjectionState currentProjection = checked.request().depthProjection();
        DepthProjectionState previousProjection = checked.historyValid()
                ? checked.previousDepthProjection()
                : currentProjection;

        float[] currentViewToClip = projection(currentCamera, currentProjection);
        float[] previousViewToClip = projection(previousCamera, previousProjection);
        float[] currentClipToView = inverse(currentViewToClip);
        float[] currentViewToWorld = viewToCenteredWorld(currentCamera, 0.0D, 0.0D, 0.0D);
        float[] previousViewToWorld = viewToCenteredWorld(
                previousCamera,
                previousCamera.x() - currentCamera.x(),
                previousCamera.y() - currentCamera.y(),
                previousCamera.z() - currentCamera.z()
        );
        float[] currentClipToPreviousClip = checked.historyValid()
                ? multiply(
                        multiply(currentClipToView, multiply(currentViewToWorld, inverse(previousViewToWorld))),
                        previousViewToClip
                )
                : identity();
        return new TemporalCameraMatrices(
                currentViewToClip,
                previousViewToClip,
                currentClipToView,
                currentClipToPreviousClip,
                inverse(currentClipToPreviousClip),
                inverse(currentViewToWorld),
                inverse(previousViewToWorld)
        );
    }

    float[] currentViewToClip() { return currentViewToClip.clone(); }
    float[] previousViewToClip() { return previousViewToClip.clone(); }
    float[] currentClipToView() { return currentClipToView.clone(); }
    float[] currentClipToPreviousClip() { return currentClipToPreviousClip.clone(); }
    float[] previousClipToCurrentClip() { return previousClipToCurrentClip.clone(); }
    float[] currentWorldToView() { return currentWorldToView.clone(); }
    float[] previousWorldToView() { return previousWorldToView.clone(); }

    private static float[] projection(CameraState camera, DepthProjectionState projection) {
        if (!projection.known()) throw new IllegalArgumentException("temporal matrices require exact projection");
        float near = projection.nearPlane();
        float far = projection.farPlane();
        float range = far - near;
        return new float[]{
                1.0F / camera.tanHalfFovX(), 0, 0, 0,
                0, 1.0F / camera.tanHalfFovY(), 0, 0,
                0, 0, -far / range, -1,
                0, 0, -far * near / range, 0
        };
    }

    private static float[] viewToCenteredWorld(
            CameraState camera,
            double relativeX,
            double relativeY,
            double relativeZ
    ) {
        return new float[]{
                camera.rightX(), camera.rightY(), camera.rightZ(), 0,
                camera.upX(), camera.upY(), camera.upZ(), 0,
                -camera.forwardX(), -camera.forwardY(), -camera.forwardZ(), 0,
                checkedFloat(relativeX, "relative camera x"),
                checkedFloat(relativeY, "relative camera y"),
                checkedFloat(relativeZ, "relative camera z"),
                1
        };
    }

    private static float checkedFloat(double value, String name) {
        float converted = (float) value;
        if (!Float.isFinite(converted)) {
            throw new IllegalArgumentException(name + " exceeds temporal matrix range");
        }
        return converted;
    }

    private static float[] identity() {
        return new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    private static float[] multiply(float[] left, float[] right) {
        float[] result = new float[16];
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                for (int pivot = 0; pivot < 4; pivot++) {
                    result[row * 4 + column] += left[row * 4 + pivot] * right[pivot * 4 + column];
                }
            }
        }
        return result;
    }

    private static float[] inverse(float[] source) {
        double[][] augmented = new double[4][8];
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                augmented[row][column] = source[row * 4 + column];
                augmented[row][column + 4] = row == column ? 1.0D : 0.0D;
            }
        }
        for (int pivot = 0; pivot < 4; pivot++) {
            int best = pivot;
            for (int row = pivot + 1; row < 4; row++) {
                if (Math.abs(augmented[row][pivot]) > Math.abs(augmented[best][pivot])) best = row;
            }
            if (Math.abs(augmented[best][pivot]) <= 1.0E-12D) {
                throw new IllegalArgumentException("temporal camera matrix is singular");
            }
            double[] swap = augmented[pivot];
            augmented[pivot] = augmented[best];
            augmented[best] = swap;
            double divisor = augmented[pivot][pivot];
            for (int column = 0; column < 8; column++) augmented[pivot][column] /= divisor;
            for (int row = 0; row < 4; row++) {
                if (row == pivot) continue;
                double factor = augmented[row][pivot];
                for (int column = 0; column < 8; column++) {
                    augmented[row][column] -= factor * augmented[pivot][column];
                }
            }
        }
        float[] result = new float[16];
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                result[row * 4 + column] = (float) augmented[row][column + 4];
            }
        }
        return result;
    }
}
