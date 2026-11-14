package top.ceroxe.rt.renderer.rt.pipeline;

import top.ceroxe.rt.renderer.rt.runtime.RtCore;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable CPU-visible output of the native RT backend.
 *
 * <p>The presentation layer consumes this value object instead of peeking into
 * Vulkan pipeline internals. That keeps the native backend free to replace
 * readback with shared images later while renderer-side code still has one
 * stable, testable contract. Production snapshots retain the exact scene-publication proof used
 * by their GPU submission; compatibility factories may expose an unavailable proof for isolated
 * CPU tests.</p>
 *
 * @param frameStateSequence            source frame-state sequence
 * @param dynamicSceneRevision          source dynamic-scene revision
 * @param boundTlasDynamicSceneRevision dynamic revision bound into the dispatched TLAS
 * @param publicationState              immutable scene-publication proof
 * @param width                         output width
 * @param height                        output height
 * @param origin                        packed origin diagnostic sample
 * @param center                        packed center diagnostic sample
 * @param last                          packed final diagnostic sample
 * @param checksum                      frame pixel checksum
 * @param backgroundPixels              background pixel count
 * @param foregroundPixels              foreground pixel count
 * @param uniqueSampledColors           sampled unique-color count
 * @param rgba8                         immutable packed frame bytes
 */
public record RtFrameSnapshot(
        long frameStateSequence,
        long dynamicSceneRevision,
        long boundTlasDynamicSceneRevision,
        RtCore.ScenePublicationState publicationState,
        int width,
        int height,
        int origin,
        int center,
        int last,
        long checksum,
        int backgroundPixels,
        int foregroundPixels,
        int uniqueSampledColors,
        byte[] rgba8
) {
    /**
     * Validates causality, extent, publication proof, and defensively captures the pixel array.
     */
    public RtFrameSnapshot {
        if (frameStateSequence < 0L) {
            throw new IllegalArgumentException("frameStateSequence must not be negative");
        }
        if (dynamicSceneRevision < -1L) {
            throw new IllegalArgumentException("dynamicSceneRevision must be -1 or greater");
        }
        if (boundTlasDynamicSceneRevision < -1L) {
            throw new IllegalArgumentException("boundTlasDynamicSceneRevision must be -1 or greater");
        }
        publicationState = Objects.requireNonNull(publicationState, "publicationState");
        if (publicationState.available()
                && (publicationState.descriptorGeneration() <= 0L
                || publicationState.worldTlasRevision() < 0L
                || publicationState.materialRevision() < 0L
                || publicationState.dynamicSceneRevision() != boundTlasDynamicSceneRevision)) {
            throw new IllegalArgumentException("RT frame publication proof does not match bound scene state");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("RT frame dimensions must be positive");
        }
        long expectedBytes = (long) width * height * Integer.BYTES;
        if (expectedBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("RT frame is too large for a Java byte array");
        }
        rgba8 = rgba8 == null ? null : rgba8.clone();
        if (rgba8 == null || rgba8.length != (int) expectedBytes) {
            throw new IllegalArgumentException(
                    "RT frame byte size mismatch: expected " + expectedBytes
                            + ", actual " + (rgba8 == null ? "null" : rgba8.length)
            );
        }
    }

    /**
     * Captures a compatibility snapshot without dynamic-scene or background classification data.
     *
     * @param rgba8              packed little-endian RGBA8 pixels
     * @param width              frame width
     * @param height             frame height
     * @param frameStateSequence source frame-state sequence
     * @return immutable analyzed snapshot
     */
    public static RtFrameSnapshot capture(byte[] rgba8, int width, int height, long frameStateSequence) {
        return capture(rgba8, width, height, frameStateSequence, -1L, -1L, Integer.MIN_VALUE);
    }

    /**
     * Captures a compatibility snapshot and classifies one packed color as background.
     *
     * @param rgba8              packed little-endian RGBA8 pixels
     * @param width              frame width
     * @param height             frame height
     * @param frameStateSequence source frame-state sequence
     * @param backgroundPixel    packed background color
     * @return immutable analyzed snapshot
     */
    public static RtFrameSnapshot capture(
            byte[] rgba8,
            int width,
            int height,
            long frameStateSequence,
            int backgroundPixel
    ) {
        return capture(rgba8, width, height, frameStateSequence, -1L, -1L, backgroundPixel);
    }

    /**
     * Captures scene revision evidence without a production publication proof.
     *
     * @param rgba8                         packed little-endian RGBA8 pixels
     * @param width                         frame width
     * @param height                        frame height
     * @param frameStateSequence            source frame-state sequence
     * @param dynamicSceneRevision          accepted dynamic-scene revision, or {@code -1}
     * @param boundTlasDynamicSceneRevision dynamic revision bound into the TLAS, or {@code -1}
     * @param backgroundPixel               packed background color
     * @return immutable analyzed snapshot
     */
    public static RtFrameSnapshot capture(
            byte[] rgba8,
            int width,
            int height,
            long frameStateSequence,
            long dynamicSceneRevision,
            long boundTlasDynamicSceneRevision,
            int backgroundPixel
    ) {
        return capture(
                rgba8,
                width,
                height,
                frameStateSequence,
                dynamicSceneRevision,
                boundTlasDynamicSceneRevision,
                RtCore.ScenePublicationState.unavailable(),
                backgroundPixel
        );
    }

    /**
     * Captures pixels together with the exact scene publication proof used for GPU dispatch.
     *
     * @param rgba8                         packed little-endian RGBA8 pixels
     * @param width                         frame width
     * @param height                        frame height
     * @param frameStateSequence            source frame-state sequence
     * @param dynamicSceneRevision          accepted dynamic-scene revision, or {@code -1}
     * @param boundTlasDynamicSceneRevision dynamic revision bound into the TLAS, or {@code -1}
     * @param publicationState              immutable dispatch publication proof
     * @param backgroundPixel               packed background color
     * @return immutable analyzed snapshot
     */
    public static RtFrameSnapshot capture(
            byte[] rgba8,
            int width,
            int height,
            long frameStateSequence,
            long dynamicSceneRevision,
            long boundTlasDynamicSceneRevision,
            RtCore.ScenePublicationState publicationState,
            int backgroundPixel
    ) {
        if (rgba8 == null) {
            throw new IllegalArgumentException("rgba8 must not be null");
        }
        long expectedBytes = (long) width * height * Integer.BYTES;
        if (width <= 0 || height <= 0 || expectedBytes > Integer.MAX_VALUE || rgba8.length != (int) expectedBytes) {
            throw new IllegalStateException(
                    "RT frame byte size mismatch: expected " + expectedBytes + ", actual " + rgba8.length
            );
        }
        int origin = pixel(rgba8, width, 0, 0);
        int center = pixel(rgba8, width, width / 2, height / 2);
        int last = pixel(rgba8, width, width - 1, height - 1);
        PixelSummary summary = summarizePixels(rgba8, backgroundPixel);
        return new RtFrameSnapshot(
                frameStateSequence,
                dynamicSceneRevision,
                boundTlasDynamicSceneRevision,
                publicationState,
                width,
                height,
                origin,
                center,
                last,
                checksum(rgba8),
                summary.backgroundPixels(),
                summary.foregroundPixels(),
                summary.uniqueSampledColors(),
                rgba8
        );
    }

    /**
     * Decodes one little-endian packed RGBA8 pixel.
     *
     * @param bytes packed frame bytes
     * @param width frame width used for row addressing
     * @param x     zero-based column
     * @param y     zero-based row
     * @return packed RGBA8 integer
     */
    public static int pixel(byte[] bytes, int width, int x, int y) {
        int byteOffset = (y * width + x) * Integer.BYTES;
        return Byte.toUnsignedInt(bytes[byteOffset])
                | (Byte.toUnsignedInt(bytes[byteOffset + 1]) << 8)
                | (Byte.toUnsignedInt(bytes[byteOffset + 2]) << 16)
                | (Byte.toUnsignedInt(bytes[byteOffset + 3]) << 24);
    }

    /**
     * Computes the stable FNV-1a checksum used by frame diagnostics.
     *
     * @param bytes bytes to hash
     * @return unsigned checksum bits stored in a Java {@code long}
     */
    public static long checksum(byte[] bytes) {
        long result = 0xcbf29ce484222325L;
        for (byte value : bytes) {
            result ^= Byte.toUnsignedInt(value);
            result *= 0x100000001b3L;
        }
        return result;
    }

    private static PixelSummary summarizePixels(byte[] bytes, int backgroundPixel) {
        int backgroundPixels = 0;
        int foregroundPixels = 0;
        int[] sampledColors = new int[32];
        int uniqueSampledColors = 0;
        for (int offset = 0; offset < bytes.length; offset += Integer.BYTES) {
            int pixel = Byte.toUnsignedInt(bytes[offset])
                    | (Byte.toUnsignedInt(bytes[offset + 1]) << 8)
                    | (Byte.toUnsignedInt(bytes[offset + 2]) << 16)
                    | (Byte.toUnsignedInt(bytes[offset + 3]) << 24);
            if (pixel == backgroundPixel) {
                backgroundPixels++;
            } else {
                foregroundPixels++;
            }
            if ((offset / Integer.BYTES) % 257 == 0) {
                boolean known = false;
                for (int index = 0; index < uniqueSampledColors; index++) {
                    if (sampledColors[index] == pixel) {
                        known = true;
                        break;
                    }
                }
                if (!known && uniqueSampledColors < sampledColors.length) {
                    sampledColors[uniqueSampledColors++] = pixel;
                }
            }
        }
        return new PixelSummary(backgroundPixels, foregroundPixels, uniqueSampledColors);
    }

    /**
     * Formats a packed value.
     *
     * @param value packed 32-bit value
     * @return fixed-width lowercase hexadecimal text
     */
    public static String hex(int value) {
        return String.format(Locale.ROOT, "0x%08x", value);
    }

    /**
     * Formats a packed value.
     *
     * @param value packed 64-bit value
     * @return fixed-width lowercase hexadecimal text
     */
    public static String hex(long value) {
        return String.format(Locale.ROOT, "0x%016x", value);
    }

    /**
     * Returns a defensive copy of the complete packed frame.
     *
     * @return copied RGBA8 bytes
     */
    public byte[] copyRgba8() {
        return rgba8.clone();
    }

    /**
     * Returns a defensive copy from the record accessor.
     *
     * @return copied RGBA8 bytes
     */
    @Override
    public byte[] rgba8() {
        return copyRgba8();
    }

    /**
     * Appends all frame bytes to a caller-owned destination.
     *
     * @param destination buffer whose position advances by the frame byte count
     */
    public void copyRgba8To(ByteBuffer destination) {
        Objects.requireNonNull(destination, "destination");
        if (destination.remaining() < rgba8.length) {
            throw new IllegalArgumentException(
                    "destination buffer has too few remaining bytes: required="
                            + rgba8.length
                            + ", remaining="
                            + destination.remaining()
            );
        }
        destination.put(rgba8);
    }

    /**
     * Appends frame rows, optionally reversing their vertical order.
     *
     * @param destination    buffer whose position advances by the frame byte count
     * @param flipVertically whether the bottom source row is copied first
     */
    public void copyRgba8RowsTo(ByteBuffer destination, boolean flipVertically) {
        Objects.requireNonNull(destination, "destination");
        if (!flipVertically) {
            copyRgba8To(destination);
            return;
        }
        if (destination.remaining() < rgba8.length) {
            throw new IllegalArgumentException(
                    "destination buffer has too few remaining bytes: required="
                            + rgba8.length
                            + ", remaining="
                            + destination.remaining()
            );
        }

        int rowBytes = width * Integer.BYTES;
        for (int sourceY = height - 1; sourceY >= 0; sourceY--) {
            destination.put(rgba8, sourceY * rowBytes, rowBytes);
        }
    }

    /**
     * Returns bounded metadata that excludes the pixel payload.
     *
     * @return diagnostic log fragment
     */
    public String asLogFragment() {
        return "{seq=" + frameStateSequence
                + ", dynamicScene=" + dynamicSceneRevision
                + ", boundTlasDynamicScene=" + boundTlasDynamicSceneRevision
                + ", publication=" + publicationState.publicationGeneration()
                + ", descriptor=" + publicationState.descriptorGeneration()
                + ", extent=" + width + "x" + height
                + ", origin=" + hex(origin)
                + ", center=" + hex(center)
                + ", last=" + hex(last)
                + ", foregroundPixels=" + foregroundPixels
                + ", backgroundPixels=" + backgroundPixels
                + ", uniqueSampledColors=" + uniqueSampledColors
                + ", checksum=" + hex(checksum)
                + "}";
    }

    /**
     * Compares scalar diagnostics and captured pixel bytes.
     *
     * @param other object to compare
     * @return {@code true} when all snapshot fields and pixels are equal
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof RtFrameSnapshot that
                && frameStateSequence == that.frameStateSequence
                && dynamicSceneRevision == that.dynamicSceneRevision
                && boundTlasDynamicSceneRevision == that.boundTlasDynamicSceneRevision
                && publicationState.equals(that.publicationState)
                && width == that.width
                && height == that.height
                && origin == that.origin
                && center == that.center
                && last == that.last
                && checksum == that.checksum
                && backgroundPixels == that.backgroundPixels
                && foregroundPixels == that.foregroundPixels
                && uniqueSampledColors == that.uniqueSampledColors
                && Arrays.equals(rgba8, that.rgba8);
    }

    /**
     * Computes a hash over scalar diagnostics and captured pixel bytes.
     *
     * @return content hash consistent with {@link #equals(Object)}
     */
    @Override
    public int hashCode() {
        int result = Long.hashCode(frameStateSequence);
        result = 31 * result + Long.hashCode(dynamicSceneRevision);
        result = 31 * result + Long.hashCode(boundTlasDynamicSceneRevision);
        result = 31 * result + publicationState.hashCode();
        result = 31 * result + width;
        result = 31 * result + height;
        result = 31 * result + origin;
        result = 31 * result + center;
        result = 31 * result + last;
        result = 31 * result + Long.hashCode(checksum);
        result = 31 * result + backgroundPixels;
        result = 31 * result + foregroundPixels;
        result = 31 * result + uniqueSampledColors;
        result = 31 * result + Arrays.hashCode(rgba8);
        return result;
    }

    private record PixelSummary(int backgroundPixels, int foregroundPixels, int uniqueSampledColors) {
    }
}
