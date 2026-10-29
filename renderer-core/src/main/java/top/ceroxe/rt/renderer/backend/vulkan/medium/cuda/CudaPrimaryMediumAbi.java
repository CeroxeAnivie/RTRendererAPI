package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;

/** Native attenuation of a primary hit produced by the ordinary Vulkan GPUScene pipeline. */
public final class CudaPrimaryMediumAbi {
    public static final int PRIMARY_BYTES = 208;
    public static final int CONSTANT_BYTES = 16;
    public static final int OUTPUT_BYTES = 16;
    private CudaPrimaryMediumAbi() { }

    public static long primaryBytes(int count) { return bytes(count, PRIMARY_BYTES); }
    public static long outputBytes(int count) { return bytes(count, OUTPUT_BYTES); }

    /** Constants contain density-scaled sigmaA RGB followed by the medium integration distance. */
    public static byte[] constants(RenderFrameRequest frame) {
        var medium = Objects.requireNonNull(frame, "frame").environment().cameraMedium();
        if (!medium.densityField().isUniform() || !medium.scattering().isZero() || !medium.emission().isZero()
                || medium.indexOfRefraction() != 1)
            throw new IllegalArgumentException("primary batch requires homogeneous absorption with IOR one");
        var absorption = medium.absorption().multiply(medium.density());
        return ByteBuffer.allocate(CONSTANT_BYTES).order(ByteOrder.nativeOrder())
                .putFloat(absorption.red()).putFloat(absorption.green()).putFloat(absorption.blue())
                .putFloat(frame.mediumTransport().maxDistance()).array();
    }

    private static long bytes(int count, int stride) {
        if (count < 1) throw new IllegalArgumentException("primary batch count must be positive");
        return Math.multiplyExact((long) count, stride);
    }
}
