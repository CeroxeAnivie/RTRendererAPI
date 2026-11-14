package top.ceroxe.rt.renderer.backend.vulkan;

import java.util.Objects;

/**
 * Converts native frame payloads into the public CPU-frame representation.
 *
 * <p>Keeping pixel semantics independent from command submission prevents the managed readback
 * path from depending on the deliberately synchronous visual-acceptance utility.</p>
 */
final class VulkanFramePixelCodec {
    private VulkanFramePixelCodec() {
    }

    static byte[] convertLinearHdrRgba16fToSdrRgba8(byte[] rgba16f) {
        byte[] checked = Objects.requireNonNull(rgba16f, "rgba16f");
        if ((checked.length & 7) != 0) {
            throw new IllegalArgumentException("RGBA16F payload must contain complete eight-byte pixels");
        }
        byte[] rgba8 = new byte[checked.length / 2];
        for (int source = 0, destination = 0; source < checked.length; source += 8, destination += 4) {
            rgba8[destination] = encodeToneMapped(halfToFloat(readHalf(checked, source)));
            rgba8[destination + 1] = encodeToneMapped(halfToFloat(readHalf(checked, source + 2)));
            rgba8[destination + 2] = encodeToneMapped(halfToFloat(readHalf(checked, source + 4)));
            rgba8[destination + 3] = encodeUnit(halfToFloat(readHalf(checked, source + 6)));
        }
        return rgba8;
    }

    static void requireFiniteLinearHdrRgba16f(byte[] rgba16f) {
        byte[] checked = Objects.requireNonNull(rgba16f, "rgba16f");
        if ((checked.length & 7) != 0) {
            throw new IllegalArgumentException("RGBA16F payload must contain complete eight-byte pixels");
        }
        for (int offset = 0; offset < checked.length; offset += Short.BYTES) {
            int bits = readHalf(checked, offset) & 0xffff;
            if ((bits & 0x7c00) == 0x7c00) {
                int component = offset / Short.BYTES;
                throw new IllegalStateException(
                        "linear HDR readback contains NaN/Inf at pixel=" + (component / 4)
                                + ", channel=" + (component % 4)
                                + ", halfBits=0x" + Integer.toHexString(bits)
                );
            }
        }
    }

    private static short readHalf(byte[] bytes, int offset) {
        return (short) ((bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8));
    }

    private static float halfToFloat(short half) {
        int bits = half & 0xffff;
        int sign = (bits & 0x8000) << 16;
        int exponent = (bits >>> 10) & 0x1f;
        int mantissa = bits & 0x3ff;
        if (exponent == 0) {
            if (mantissa == 0) return Float.intBitsToFloat(sign);
            int normalizedExponent = -14;
            while ((mantissa & 0x400) == 0) {
                mantissa <<= 1;
                normalizedExponent--;
            }
            mantissa &= 0x3ff;
            return Float.intBitsToFloat(sign | ((normalizedExponent + 127) << 23) | (mantissa << 13));
        }
        if (exponent == 0x1f) {
            return Float.intBitsToFloat(sign | 0x7f800000 | (mantissa << 13));
        }
        return Float.intBitsToFloat(sign | ((exponent - 15 + 127) << 23) | (mantissa << 13));
    }

    private static byte encodeToneMapped(float value) {
        if (Float.isNaN(value) || value <= 0.0F) return 0;
        if (value == Float.POSITIVE_INFINITY) return (byte) 0xff;
        double numerator = value * (2.51 * value + 0.03);
        double denominator = value * (2.43 * value + 0.59) + 0.14;
        double mapped = Math.max(0.0, Math.min(1.0, numerator / denominator));
        return encodeUnit(Math.pow(mapped, 1.0 / 2.2));
    }

    private static byte encodeUnit(double value) {
        if (!Double.isFinite(value)) return value > 0.0 ? (byte) 0xff : 0;
        return (byte) Math.round(Math.max(0.0, Math.min(1.0, value)) * 255.0);
    }
}
