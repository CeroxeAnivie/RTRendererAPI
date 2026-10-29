package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;

/** Homogeneous camera transport with API-compatible analytic emission, using production primary hits. */
public final class CudaPrimaryEmissionAbi {
    public static final int PRIMARY_BYTES = CudaPrimaryMediumAbi.PRIMARY_BYTES;
    public static final int CONSTANT_BYTES = 32;
    public static final int CHANNEL_BYTES = 16;
    public static final int OUTPUT_BYTES = CHANNEL_BYTES * 2;

    private CudaPrimaryEmissionAbi() { }

    public static long primaryBytes(int count) { return bytes(count, PRIMARY_BYTES); }
    public static long outputBytes(int count) { return bytes(count, OUTPUT_BYTES); }
    public static long emissionOffset(int count) { return bytes(count, CHANNEL_BYTES); }

    /** Output contains count transmittance float4 values, followed by count emitted-radiance float4 values. */
    public static byte[] constants(RenderFrameRequest frame) {
        var checked = Objects.requireNonNull(frame, "frame");
        var medium = checked.environment().cameraMedium();
        if (!medium.densityField().isUniform() || !medium.scattering().isZero()
                || medium.indexOfRefraction() != 1)
            throw new IllegalArgumentException("primary emission batch requires homogeneous non-scattering transport with IOR one");
        var absorption = medium.absorption().multiply(medium.density());
        var emission = medium.emission().multiply(medium.density());
        return ByteBuffer.allocate(CONSTANT_BYTES).order(ByteOrder.nativeOrder())
                .putFloat(absorption.red()).putFloat(absorption.green()).putFloat(absorption.blue())
                .putFloat(checked.mediumTransport().maxDistance())
                .putFloat(emission.red()).putFloat(emission.green()).putFloat(emission.blue()).putInt(0).array();
    }

    private static long bytes(int count, int stride) {
        if (count < 1) throw new IllegalArgumentException("primary emission count must be positive");
        return Math.multiplyExact((long) count, stride);
    }
}
