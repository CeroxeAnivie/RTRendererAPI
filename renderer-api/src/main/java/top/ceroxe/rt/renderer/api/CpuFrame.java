package top.ceroxe.rt.renderer.api;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Immutable CPU-readable snapshot of one completed renderer frame.
 *
 * <p>Pixels are tightly packed in row-major order from the top-left corner. Each pixel occupies
 * four bytes in red, green, blue, alpha order. {@link #pixelsRgba8()} returns an independent
 * read-only cursor over renderer-owned storage, so the value can be safely retained or shared
 * without a lease, native handle, Vulkan object, or explicit synchronization.</p>
 */
public final class CpuFrame {
    private final long frameSequence;
    private final long renderedSceneRevision;
    private final int width;
    private final int height;
    private final ByteBuffer pixelsRgba8;

    private CpuFrame(Builder builder) {
        if (builder.frameSequence < 0L || builder.renderedSceneRevision < 0L) {
            throw new IllegalArgumentException("frame sequence and scene revision must not be negative");
        }
        if (builder.width <= 0 || builder.height <= 0) {
            throw new IllegalArgumentException("frame extent must be positive");
        }
        long expectedBytes = Math.multiplyExact(Math.multiplyExact((long) builder.width, builder.height), 4L);
        byte[] copy = Objects.requireNonNull(builder.pixelsRgba8, "pixelsRgba8").clone();
        if (copy.length != expectedBytes) {
            throw new IllegalArgumentException(
                    "RGBA8 byte count must be " + expectedBytes + " but was " + copy.length
            );
        }
        frameSequence = builder.frameSequence;
        renderedSceneRevision = builder.renderedSceneRevision;
        width = builder.width;
        height = builder.height;
        this.pixelsRgba8 = ByteBuffer.wrap(copy).asReadOnlyBuffer();
    }

    /**
     * Starts an empty semantic builder for one CPU frame snapshot.
     *
     * @return new single-thread-confined builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Starts an independent builder initialized from this snapshot.
     *
     * @return builder containing copied pixel data and all metadata
     */
    public Builder toBuilder() {
        byte[] copy = new byte[byteCount()];
        pixelsRgba8().get(copy);
        return new Builder()
                .frameSequence(frameSequence)
                .renderedSceneRevision(renderedSceneRevision)
                .extent(width, height)
                .pixelsRgba8(copy);
    }

    /**
     * Returns the exact rendered frame sequence.
     *
     * @return non-negative frame sequence
     */
    public long frameSequence() {
        return frameSequence;
    }

    /**
     * Returns the exact scene revision used for rendering.
     *
     * @return non-negative scene revision
     */
    public long renderedSceneRevision() {
        return renderedSceneRevision;
    }

    /**
     * Returns the frame width.
     *
     * @return positive width in pixels
     */
    public int width() {
        return width;
    }

    /**
     * Returns the frame height.
     *
     * @return positive height in pixels
     */
    public int height() {
        return height;
    }

    /**
     * Returns the packed byte count.
     *
     * @return exactly {@code width * height * 4}
     */
    public int byteCount() {
        return pixelsRgba8.remaining();
    }

    /**
     * Returns an independent read-only view of the complete top-left-origin RGBA8 payload.
     *
     * @return read-only buffer positioned at zero
     */
    public ByteBuffer pixelsRgba8() {
        return pixelsRgba8.duplicate();
    }

    /**
     * Copies the complete RGBA8 payload to {@code destination}, advancing its position.
     *
     * @param destination buffer with at least {@link #byteCount()} remaining bytes
     * @throws IllegalArgumentException if the destination has insufficient remaining space
     * @throws NullPointerException     if {@code destination} is {@code null}
     */
    public void copyPixelsRgba8To(ByteBuffer destination) {
        Objects.requireNonNull(destination, "destination");
        if (destination.remaining() < byteCount()) {
            throw new IllegalArgumentException(
                    "destination requires " + byteCount() + " remaining bytes but has " + destination.remaining()
            );
        }
        destination.put(pixelsRgba8());
    }

    /**
     * Single-thread-confined builder that names metadata explicitly and defensively copies the
     * pixel payload when {@link #build()} publishes the immutable frame.
     */
    public static final class Builder {
        private long frameSequence = -1L;
        private long renderedSceneRevision = -1L;
        private int width;
        private int height;
        private byte[] pixelsRgba8;

        private Builder() {
        }

        /**
         * Selects the exact rendered frame sequence.
         *
         * @param value non-negative sequence
         * @return this builder
         */
        public Builder frameSequence(long value) {
            frameSequence = value;
            return this;
        }

        /**
         * Selects the exact scene revision used for rendering.
         *
         * @param value non-negative revision
         * @return this builder
         */
        public Builder renderedSceneRevision(long value) {
            renderedSceneRevision = value;
            return this;
        }

        /**
         * Selects the positive image extent as one inseparable property.
         *
         * @param widthPixels  positive width in pixels
         * @param heightPixels positive height in pixels
         * @return this builder
         */
        public Builder extent(int widthPixels, int heightPixels) {
            width = widthPixels;
            height = heightPixels;
            return this;
        }

        /**
         * Selects tightly packed top-left-origin RGBA8 bytes.
         *
         * <p>The immutable frame copies this payload during {@link #build()}; the caller may reuse
         * or clear its array after that method returns.</p>
         *
         * @param value non-null RGBA8 payload
         * @return this builder
         */
        public Builder pixelsRgba8(byte[] value) {
            pixelsRgba8 = Objects.requireNonNull(value, "pixelsRgba8");
            return this;
        }

        /**
         * Validates and creates a frame with an independent pixel copy.
         *
         * @return immutable CPU frame snapshot
         */
        public CpuFrame build() {
            return new CpuFrame(this);
        }
    }
}
