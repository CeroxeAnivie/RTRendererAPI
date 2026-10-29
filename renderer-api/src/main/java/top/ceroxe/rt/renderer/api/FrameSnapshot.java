package top.ceroxe.rt.renderer.api;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Immutable CPU-readable snapshot of one completed renderer frame.
 *
 * <p>Pixels are tightly packed in row-major order from the top-left corner. Each pixel occupies
 * the byte count specified by its explicit format. {@link #pixels()} returns an independent
 * read-only cursor over renderer-owned storage, so the value can be safely retained or shared
 * without a lease, native handle, Vulkan object, or explicit synchronization.</p>
 */
public final class FrameSnapshot implements FrameView {
    private final long frameSequence;
    private final long renderedSceneRevision;
    private final RenderResourceId outputResource;
    private final int width;
    private final int height;
    private final ByteBuffer pixels;
    private final TextureFormat format;

    private FrameSnapshot(Builder builder) {
        if (builder.frameSequence < 0L || builder.renderedSceneRevision < 0L) {
            throw new IllegalArgumentException("frame sequence and scene revision must not be negative");
        }
        if (builder.width <= 0 || builder.height <= 0) {
            throw new IllegalArgumentException("frame extent must be positive");
        }
        format = Objects.requireNonNull(builder.format, "format");
        long expectedBytes = Math.multiplyExact(Math.multiplyExact((long) builder.width, builder.height), format.bytesPerTexel());
        byte[] copy = Objects.requireNonNull(builder.pixels, "pixels").clone();
        if (copy.length != expectedBytes) {
            throw new IllegalArgumentException(
                    "pixel byte count must be " + expectedBytes + " but was " + copy.length
            );
        }
        frameSequence = builder.frameSequence;
        renderedSceneRevision = builder.renderedSceneRevision;
        outputResource = builder.outputResource;
        width = builder.width;
        height = builder.height;
        this.pixels = ByteBuffer.wrap(copy).asReadOnlyBuffer();
    }

    /**
     * Starts an empty semantic builder for one CPU frame snapshot.
     *
     * @return new single-thread-confined builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Makes the one explicit ownership copy needed to retain a scoped frame. */
    public static FrameSnapshot copyOf(FrameView view) {
        Objects.requireNonNull(view, "view");
        if (view.origin() != FrameOrigin.TOP_LEFT || view.rowBytes() != Math.multiplyExact(view.width(), view.format().bytesPerTexel())) {
            throw new IllegalArgumentException("snapshot requires tightly packed top-left pixels");
        }
        long expected = Math.multiplyExact(Math.multiplyExact((long) view.width(), view.height()), view.format().bytesPerTexel());
        if (view.width() <= 0 || view.height() <= 0 || view.frameSequence() < 0 || view.renderedSceneRevision() < 0
                || expected != view.byteCount() || view.pixels().remaining() != expected) {
            throw new IllegalArgumentException("invalid frame metadata or pixel extent");
        }
        ByteBuffer copy = SnapshotPixelPool.SHARED.copy(view.pixels(), view.byteCount());
        Builder builder = builder().frameSequence(view.frameSequence()).renderedSceneRevision(view.renderedSceneRevision())
                .extent(view.width(), view.height()).format(view.format());
        view.outputResource().ifPresent(builder::outputResource);
        return new FrameSnapshot(builder, copy);
    }

    private FrameSnapshot(Builder builder, ByteBuffer ownedPixels) {
        frameSequence = builder.frameSequence; renderedSceneRevision = builder.renderedSceneRevision;
        outputResource = builder.outputResource; width = builder.width; height = builder.height;
        format = builder.format; pixels = ownedPixels.asReadOnlyBuffer();
    }

    @Override public TextureFormat format() { return format; }
    @Override public FrameOrigin origin() { return FrameOrigin.TOP_LEFT; }
    @Override public int rowBytes() { return Math.multiplyExact(width, format.bytesPerTexel()); }
    @Override public ByteBuffer pixels() { return pixels.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN); }

    /**
     * Starts an independent builder initialized from this snapshot.
     *
     * @return builder containing copied pixel data and all metadata
     */
    public Builder toBuilder() {
        byte[] copy = new byte[byteCount()];
        pixels().get(copy);
        Builder builder = new Builder()
                .format(format)
                .frameSequence(frameSequence)
                .renderedSceneRevision(renderedSceneRevision)
                .extent(width, height)
                .pixels(copy);
        if (outputResource != null) builder.outputResource(outputResource);
        return builder;
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
     * Returns the exact generic output resource that produced this frame, when applicable.
     * Retained-scene frames have no generic resource identity and return empty.
     */
    public java.util.Optional<RenderResourceId> outputResource() {
        return java.util.Optional.ofNullable(outputResource);
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
     * @return exactly {@code width * height * format.bytesPerTexel()}
     */
    public int byteCount() {
        return pixels.remaining();
    }

    /**
     * Copies the complete native-format payload to {@code destination}, advancing its position.
     *
     * @param destination buffer with at least {@link #byteCount()} remaining bytes
     * @throws IllegalArgumentException if the destination has insufficient remaining space
     * @throws NullPointerException     if {@code destination} is {@code null}
     */
    public void copyPixelsTo(ByteBuffer destination) {
        Objects.requireNonNull(destination, "destination");
        if (destination.remaining() < byteCount()) {
            throw new IllegalArgumentException(
                    "destination requires " + byteCount() + " remaining bytes but has " + destination.remaining()
            );
        }
        destination.put(pixels());
    }

    /**
     * Single-thread-confined builder that names metadata explicitly and defensively copies the
     * pixel payload when {@link #build()} publishes the immutable frame.
     */
    public static final class Builder {
        private TextureFormat format = TextureFormat.RGBA8_UNORM;
        public Builder format(TextureFormat value) { format = Objects.requireNonNull(value, "format"); return this; }
        private long frameSequence = -1L;
        private long renderedSceneRevision = -1L;
        private RenderResourceId outputResource;
        private int width;
        private int height;
        private byte[] pixels;

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

        /** Selects the exact generic output resource for this snapshot. */
        public Builder outputResource(RenderResourceId value) {
            outputResource = Objects.requireNonNull(value, "outputResource");
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
         * Selects tightly packed top-left-origin native-format bytes.
         *
         * <p>The immutable frame copies this payload during {@link #build()}; the caller may reuse
         * or clear its array after that method returns.</p>
         *
         * @param value non-null native-format payload
         * @return this builder
         */
        public Builder pixels(byte[] value) {
            pixels = Objects.requireNonNull(value, "pixels");
            return this;
        }

        /**
         * Validates and creates a frame with an independent pixel copy.
         *
         * @return immutable CPU frame snapshot
         */
        public FrameSnapshot build() {
            return new FrameSnapshot(this);
        }
    }
}
