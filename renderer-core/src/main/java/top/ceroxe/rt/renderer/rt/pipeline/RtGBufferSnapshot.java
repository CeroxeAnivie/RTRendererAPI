package top.ceroxe.rt.renderer.rt.pipeline;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Immutable diagnostic attachments for one RT frame.
 *
 * <p>This value is intentionally separate from {@link RtFrameSnapshot}: color
 * presentation can stay on the zero-readback path while an explicit diagnostic
 * request captures the semantic buffers needed to localize a visual mismatch.
 * `normalOct16` packs two signed-normalized 16-bit octahedral components and
 * every RGBA field uses the same little-endian RGBA8 convention as frame output.</p>
 *
 * @param frameStateSequence source frame-state sequence
 * @param width              attachment width
 * @param height             attachment height
 * @param depth              immutable linear-depth values
 * @param normalOct16        immutable octahedral packed normals
 * @param albedoRgba8        immutable packed albedo values
 * @param materialIds        immutable material slot identifiers
 * @param emissiveRgba8      immutable packed emissive values
 * @param cameraMediumIds    immutable camera-medium identifiers
 */
public record RtGBufferSnapshot(
        long frameStateSequence,
        int width,
        int height,
        float[] depth,
        int[] normalOct16,
        int[] albedoRgba8,
        int[] materialIds,
        int[] emissiveRgba8,
        byte[] cameraMediumIds
) {
    /**
     * Validates extent and attachment invariants and defensively copies every attachment.
     */
    public RtGBufferSnapshot {
        if (frameStateSequence < 0L) {
            throw new IllegalArgumentException("frameStateSequence must not be negative");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("G-buffer dimensions must be positive");
        }
        int pixels = pixelCount(width, height);
        depth = copy(depth, pixels, "depth");
        normalOct16 = copy(normalOct16, pixels, "normalOct16");
        albedoRgba8 = copy(albedoRgba8, pixels, "albedoRgba8");
        materialIds = copy(materialIds, pixels, "materialIds");
        emissiveRgba8 = copy(emissiveRgba8, pixels, "emissiveRgba8");
        cameraMediumIds = copy(cameraMediumIds, pixels, "cameraMediumIds");
        for (float value : depth) {
            if (Float.isNaN(value) || value < 0.0F) {
                throw new IllegalArgumentException("depth must be non-negative or positive infinity");
            }
        }
    }

    static int pixelCount(int width, int height) {
        long pixels = (long) width * height;
        if (pixels > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("G-buffer is too large for Java arrays");
        }
        return (int) pixels;
    }

    /**
     * Decodes the fixed two-uvec4-per-pixel diagnostic SSBO written by the RT raygen shader.
     */
    static RtGBufferSnapshot capture(byte[] bytes, int width, int height, long frameStateSequence) {
        Objects.requireNonNull(bytes, "bytes");
        int pixels = pixelCount(width, height);
        int expectedBytes = Math.multiplyExact(pixels, 8 * Integer.BYTES);
        if (bytes.length != expectedBytes) {
            throw new IllegalArgumentException("diagnostic G-buffer byte count mismatch");
        }
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
        float[] depth = new float[pixels];
        int[] normals = new int[pixels];
        int[] albedo = new int[pixels];
        int[] materials = new int[pixels];
        int[] emissive = new int[pixels];
        byte[] media = new byte[pixels];
        for (int pixel = 0; pixel < pixels; pixel++) {
            depth[pixel] = Float.intBitsToFloat(data.getInt());
            normals[pixel] = data.getInt();
            albedo[pixel] = data.getInt();
            materials[pixel] = data.getInt();
            emissive[pixel] = data.getInt();
            media[pixel] = (byte) data.getInt();
            data.getInt();
            data.getInt();
        }
        return new RtGBufferSnapshot(frameStateSequence, width, height, depth, normals, albedo, materials, emissive, media);
    }

    private static float[] copy(float[] values, int count, String name) {
        Objects.requireNonNull(values, name);
        if (values.length != count) throw new IllegalArgumentException(name + " length mismatch");
        return values.clone();
    }

    private static int[] copy(int[] values, int count, String name) {
        Objects.requireNonNull(values, name);
        if (values.length != count) throw new IllegalArgumentException(name + " length mismatch");
        return values.clone();
    }

    private static byte[] copy(byte[] values, int count, String name) {
        Objects.requireNonNull(values, name);
        if (values.length != count) throw new IllegalArgumentException(name + " length mismatch");
        return values.clone();
    }

    /**
     * Returns linear depth.
     *
     * @return a defensive copy
     */
    @Override
    public float[] depth() {
        return depth.clone();
    }

    /**
     * Returns packed normals.
     *
     * @return a defensive copy
     */
    @Override
    public int[] normalOct16() {
        return normalOct16.clone();
    }

    /**
     * Returns packed albedo.
     *
     * @return a defensive copy
     */
    @Override
    public int[] albedoRgba8() {
        return albedoRgba8.clone();
    }

    /**
     * Returns material identifiers.
     *
     * @return a defensive copy
     */
    @Override
    public int[] materialIds() {
        return materialIds.clone();
    }

    /**
     * Returns packed emissive values.
     *
     * @return a defensive copy
     */
    @Override
    public int[] emissiveRgba8() {
        return emissiveRgba8.clone();
    }

    /**
     * Returns camera-medium identifiers.
     *
     * @return a defensive copy
     */
    @Override
    public byte[] cameraMediumIds() {
        return cameraMediumIds.clone();
    }
}
