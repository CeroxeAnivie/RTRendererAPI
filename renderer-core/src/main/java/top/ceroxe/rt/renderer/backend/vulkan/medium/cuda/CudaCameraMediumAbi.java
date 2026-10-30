package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;

/** Internal camera batch constants. Integer lanes retain their exact uint bit representation. */
public final class CudaCameraMediumAbi {
    public static final int CONSTANT_BYTES = 96;
    public static final int OUTPUT_BYTES = 16;
    private CudaCameraMediumAbi() { }

    /** Output is a contiguous tile; its first element corresponds to firstPixel, not image pixel zero. */
    public static long outputBytes(int count) {
        if (count < 1) throw new IllegalArgumentException("camera batch count must be positive");
        return Math.multiplyExact((long) count, OUTPUT_BYTES);
    }

    public static byte[] constants(RenderFrameRequest frame, int firstPixel, int count,
                                   float jitterX, float jitterY, float rayEpsilon) {
        java.util.Objects.requireNonNull(frame, "frame");
        if (firstPixel < 0 || count < 1 || (long) firstPixel + count > (long) frame.width() * frame.height())
            throw new IllegalArgumentException("camera tile exceeds image extent");
        if (!Float.isFinite(jitterX) || !Float.isFinite(jitterY))
            throw new IllegalArgumentException("camera jitter must be finite");
        if (!Float.isFinite(rayEpsilon) || rayEpsilon < 0 || rayEpsilon >= frame.mediumTransport().maxDistance())
            throw new IllegalArgumentException("camera epsilon must be finite and below the transport range");
        var camera = frame.camera();
        if (camera.hasExactProjection()) throw new IllegalArgumentException("camera batch requires basis/FOV projection");
        var medium = frame.environment().cameraMedium();
        if (!medium.densityField().isUniform() || !medium.scattering().isZero() || !medium.emission().isZero())
            throw new IllegalArgumentException("camera batch requires homogeneous absorption");
        ByteBuffer data = ByteBuffer.allocate(CONSTANT_BYTES).order(ByteOrder.nativeOrder());
        data.putFloat(0, finiteFloat(camera.x())).putFloat(4, finiteFloat(camera.y())).putFloat(8, finiteFloat(camera.z()));
        data.putFloat(12, frame.mediumTransport().maxDistance());
        data.putFloat(16, camera.forwardX()).putFloat(20, camera.forwardY()).putFloat(24, camera.forwardZ());
        data.putInt(28, frame.width());
        data.putFloat(32, camera.rightX()).putFloat(36, camera.rightY()).putFloat(40, camera.rightZ());
        data.putInt(44, frame.height());
        data.putFloat(48, camera.upX()).putFloat(52, camera.upY()).putFloat(56, camera.upZ());
        data.putInt(60, firstPixel);
        data.putFloat(64, camera.tanHalfFovX()).putFloat(68, camera.tanHalfFovY());
        data.putFloat(72, jitterX).putFloat(76, jitterY);
        var absorption = medium.absorption().multiply(medium.density());
        data.putFloat(80, absorption.red()).putFloat(84, absorption.green()).putFloat(88, absorption.blue());
        data.putFloat(92, rayEpsilon);
        return data.array();
    }

    private static float finiteFloat(double value) {
        float result = (float) value;
        if (!Float.isFinite(result)) throw new IllegalArgumentException("camera origin exceeds float ray ABI");
        return result;
    }
}
