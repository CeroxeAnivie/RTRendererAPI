package top.ceroxe.rt.renderer.api;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Immutable tightly packed RGBA8 texture generation.
 *
 * <p>{@link #builder(long, int, int)} makes sampling and pixel ownership explicit. Byte-array
 * inputs are defensively copied; advanced asset pipelines can use {@link #wrapImmutableDirect}
 * or the builder's immutable-direct methods to retain off-heap pixels without copying them.</p>
 */
public final class TextureAsset {
    private final long id;
    private final int width;
    private final int height;
    private final ColorSpace colorSpace;
    private final AddressMode addressU;
    private final AddressMode addressV;
    private final Filter filter;
    private final int mipLevelCount;
    private final ByteBuffer rgba8;

    private TextureAsset(
            long id,
            int width,
            int height,
            ColorSpace colorSpace,
            AddressMode addressU,
            AddressMode addressV,
            Filter filter,
            int mipLevelCount,
            ByteBuffer rgba8,
            PixelOwnership ownership
    ) {
        MaterialAsset.requireId(id, "id");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("texture extent must be positive");
        int maximumMipLevels = maximumMipLevelCount(width, height);
        if (mipLevelCount <= 0 || mipLevelCount > maximumMipLevels) {
            throw new IllegalArgumentException("mip level count must be in [1, " + maximumMipLevels + "]");
        }
        ByteBuffer pixels = Objects.requireNonNull(rgba8, "rgba8");
        PixelOwnership checkedOwnership = Objects.requireNonNull(ownership, "ownership");
        if (checkedOwnership == PixelOwnership.IMMUTABLE_DIRECT
                && (!pixels.isDirect() || !pixels.isReadOnly())) {
            throw new IllegalArgumentException("rgba8 must be a read-only direct buffer");
        }
        long requiredBytes = requiredByteCount(width, height, mipLevelCount);
        if (pixels.remaining() != requiredBytes) {
            throw new IllegalArgumentException(
                    "RGBA8 byte count must be " + requiredBytes + " but was " + pixels.remaining()
            );
        }
        this.id = id;
        this.width = width;
        this.height = height;
        this.colorSpace = Objects.requireNonNull(colorSpace, "colorSpace");
        this.addressU = Objects.requireNonNull(addressU, "addressU");
        this.addressV = Objects.requireNonNull(addressV, "addressV");
        this.filter = Objects.requireNonNull(filter, "filter");
        this.mipLevelCount = mipLevelCount;
        this.rgba8 = pixels.slice().asReadOnlyBuffer();
    }

    /**
     * Starts a texture generation with conventional color-sampling defaults.
     *
     * <p>The builder starts in sRGB, repeat/repeat, and linear filtering mode. A pixel source is
     * mandatory and must be selected explicitly before {@link Builder#build()}.</p>
     *
     * @param id     stable non-negative texture identifier
     * @param width  positive level-zero width
     * @param height positive level-zero height
     * @return single-thread-confined semantic builder
     */
    public static Builder builder(long id, int width, int height) {
        return new Builder(id, width, height);
    }

    /**
     * Creates a conventional color texture with beginner-safe sampling defaults.
     *
     * <p>The pixels are copied. The texture uses the sRGB transfer function, repeats in both
     * directions, and linearly filters within the only mip level.</p>
     *
     * @param id     stable non-negative texture identifier
     * @param width  positive width in texels
     * @param height positive height in texels
     * @param rgba8  tightly packed RGBA8 bytes
     * @return defensively copied color texture
     */
    public static TextureAsset color(long id, int width, int height, byte[] rgba8) {
        return builder(id, width, height).pixelsRgba8(rgba8).build();
    }

    /**
     * Creates a conventional color texture from a complete, tightly packed mip chain.
     *
     * @param id            stable non-negative texture identifier
     * @param width         positive level-zero width
     * @param height        positive level-zero height
     * @param mipLevelCount mip count in the valid chain range
     * @param rgba8         tightly packed RGBA8 levels in ascending order
     * @return defensively copied sRGB repeat/repeat linear-filtered texture
     */
    public static TextureAsset colorMipChain(
            long id,
            int width,
            int height,
            int mipLevelCount,
            byte[] rgba8
    ) {
        return builder(id, width, height).mipChainRgba8(mipLevelCount, rgba8).build();
    }

    /**
     * Creates a single-mip texture without copying immutable off-heap pixels.
     *
     * @param id         stable non-negative texture identifier
     * @param width      positive level-zero width
     * @param height     positive level-zero height
     * @param colorSpace texel color space
     * @param addressU   horizontal address mode
     * @param addressV   vertical address mode
     * @param filter     within-level filtering mode
     * @param rgba8      read-only direct buffer containing tightly packed level-zero RGBA8 bytes
     * @return texture retaining the captured immutable direct range without copying it
     * @throws IllegalArgumentException if the buffer is not direct and read-only or its size is invalid
     * @throws NullPointerException     if a reference is {@code null}
     */
    public static TextureAsset wrapImmutableDirect(
            long id,
            int width,
            int height,
            ColorSpace colorSpace,
            AddressMode addressU,
            AddressMode addressV,
            Filter filter,
            ByteBuffer rgba8
    ) {
        return builder(id, width, height)
                .colorSpace(colorSpace)
                .addressModes(addressU, addressV)
                .filter(filter)
                .immutableDirectPixelsRgba8(rgba8)
                .build();
    }

    /**
     * Creates a mipmapped texture without copying immutable off-heap pixels.
     *
     * <p>The asset captures the input's current {@code position..limit} range as a zero-based view,
     * so later cursor changes cannot affect it, and retains the backing buffer. Java cannot prove
     * that no writable alias exists: the caller must permanently relinquish all writable aliases
     * and keep any external allocator or arena alive for at least as long as the asset.</p>
     *
     * @param id            stable non-negative texture identifier
     * @param width         positive level-zero width
     * @param height        positive level-zero height
     * @param colorSpace    texel color space
     * @param addressU      horizontal address mode
     * @param addressV      vertical address mode
     * @param filter        within-level filtering mode
     * @param mipLevelCount mip count in the valid chain range
     * @param rgba8         read-only direct buffer containing tightly packed RGBA8 mip bytes
     * @return texture retaining the captured immutable direct range without copying it
     * @throws IllegalArgumentException if the buffer is not direct and read-only or its size is invalid
     * @throws NullPointerException     if a reference is {@code null}
     */
    public static TextureAsset wrapImmutableDirect(
            long id,
            int width,
            int height,
            ColorSpace colorSpace,
            AddressMode addressU,
            AddressMode addressV,
            Filter filter,
            int mipLevelCount,
            ByteBuffer rgba8
    ) {
        return builder(id, width, height)
                .colorSpace(colorSpace)
                .addressModes(addressU, addressV)
                .filter(filter)
                .immutableDirectMipChainRgba8(mipLevelCount, rgba8)
                .build();
    }

    /**
     * Computes the complete mip-chain length for an extent.
     *
     * @param width  positive level-zero width
     * @param height positive level-zero height
     * @return number of levels down to {@code 1x1}
     */
    public static int maximumMipLevelCount(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("texture extent must be positive");
        int count = 1;
        while (width > 1 || height > 1) {
            width = Math.max(1, width >>> 1);
            height = Math.max(1, height >>> 1);
            count++;
        }
        return count;
    }

    /**
     * Computes the tightly packed RGBA8 byte count for a mip prefix.
     *
     * @param width         positive level-zero width
     * @param height        positive level-zero height
     * @param mipLevelCount requested valid mip count
     * @return required byte count
     */
    public static long requiredByteCount(int width, int height, int mipLevelCount) {
        if (width <= 0 || height <= 0 || mipLevelCount <= 0
                || mipLevelCount > maximumMipLevelCount(width, height)) {
            throw new IllegalArgumentException("invalid texture mip extent");
        }
        long bytes = 0L;
        for (int level = 0; level < mipLevelCount; level++) {
            int mipWidth = Math.max(1, width >>> level);
            int mipHeight = Math.max(1, height >>> level);
            bytes = Math.addExact(bytes, Math.multiplyExact(
                    Math.multiplyExact((long) mipWidth, mipHeight), 4L));
        }
        return bytes;
    }

    private static int mipExtent(int extent, int level, int levelCount, String name) {
        if (level < 0 || level >= levelCount) {
            throw new IllegalArgumentException(name + " must be in [0, " + (levelCount - 1) + "]");
        }
        return Math.max(1, extent >>> level);
    }

    /**
     * Returns the texture identifier.
     *
     * @return stable non-negative identifier
     */
    public long id() {
        return id;
    }

    /**
     * Returns the level-zero width.
     *
     * @return positive width in texels
     */
    public int width() {
        return width;
    }

    /**
     * Returns the level-zero height.
     *
     * @return positive height in texels
     */
    public int height() {
        return height;
    }

    /**
     * Returns the texel color space.
     *
     * @return color space
     */
    public ColorSpace colorSpace() {
        return colorSpace;
    }

    /**
     * Returns the horizontal address mode.
     *
     * @return horizontal address mode
     */
    public AddressMode addressU() {
        return addressU;
    }

    /**
     * Returns the vertical address mode.
     *
     * @return vertical address mode
     */
    public AddressMode addressV() {
        return addressV;
    }

    /**
     * Returns the within-level filtering mode.
     *
     * @return filtering mode
     */
    public Filter filter() {
        return filter;
    }

    /**
     * Returns the mip count.
     *
     * @return positive mip-level count
     */
    public int mipLevelCount() {
        return mipLevelCount;
    }

    /**
     * Returns a mip width.
     *
     * @param level zero-based mip level
     * @return positive width in texels
     */
    public int mipWidth(int level) {
        return mipExtent(width, level, mipLevelCount, "level");
    }

    /**
     * Returns a mip height.
     *
     * @param level zero-based mip level
     * @return positive height in texels
     */
    public int mipHeight(int level) {
        return mipExtent(height, level, mipLevelCount, "level");
    }

    /**
     * Returns the packed byte offset of a mip level.
     *
     * @param level zero-based mip level
     * @return byte offset from the start of {@link #rgba8()}
     */
    public int mipByteOffset(int level) {
        if (level < 0 || level >= mipLevelCount) {
            throw new IllegalArgumentException("level must be in [0, " + (mipLevelCount - 1) + "]");
        }
        long offset = 0L;
        for (int current = 0; current < level; current++) {
            offset = Math.addExact(offset, Math.multiplyExact(
                    Math.multiplyExact((long) mipWidth(current), mipHeight(current)), 4L));
        }
        return Math.toIntExact(offset);
    }

    /**
     * Returns texture bytes.
     *
     * @return independent read-only packed RGBA8 view positioned at zero
     */
    public ByteBuffer rgba8() {
        return rgba8.duplicate();
    }

    private enum PixelOwnership {
        COPIED,
        IMMUTABLE_DIRECT
    }

    /**
     * Texel transfer function.
     */
    public enum ColorSpace {
        /**
         * Linear-light channels.
         */
        LINEAR,
        /**
         * Standard RGB transfer function.
         */
        SRGB
    }

    /**
     * Texture-coordinate addressing policy.
     */
    public enum AddressMode {
        /**
         * Repeat outside the unit interval.
         */
        REPEAT,
        /**
         * Clamp to the nearest edge texel.
         */
        CLAMP_TO_EDGE
    }

    /**
     * Within-level sampling filter.
     */
    public enum Filter {
        /**
         * Select the nearest texel.
         */
        NEAREST,
        /**
         * Linearly interpolate adjacent texels.
         */
        LINEAR
    }

    /**
     * Single-thread-confined semantic builder for one immutable texture generation.
     */
    public static final class Builder {
        private final long id;
        private final int width;
        private final int height;
        private ColorSpace colorSpace = ColorSpace.SRGB;
        private AddressMode addressU = AddressMode.REPEAT;
        private AddressMode addressV = AddressMode.REPEAT;
        private Filter filter = Filter.LINEAR;
        private int mipLevelCount = 1;
        private ByteBuffer rgba8;
        private PixelOwnership ownership;

        private Builder(long id, int width, int height) {
            MaterialAsset.requireId(id, "id");
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("texture extent must be positive");
            }
            this.id = id;
            this.width = width;
            this.height = height;
        }

        /**
         * Selects the texel transfer function.
         *
         * @param colorSpace non-null color space
         * @return this builder
         */
        public Builder colorSpace(ColorSpace colorSpace) {
            this.colorSpace = Objects.requireNonNull(colorSpace, "colorSpace");
            return this;
        }

        /**
         * Selects one address mode for both texture-coordinate axes.
         *
         * @param addressMode non-null address mode
         * @return this builder
         */
        public Builder addressMode(AddressMode addressMode) {
            AddressMode checked = Objects.requireNonNull(addressMode, "addressMode");
            addressU = checked;
            addressV = checked;
            return this;
        }

        /**
         * Selects independent horizontal and vertical address modes.
         *
         * @param addressU non-null horizontal address mode
         * @param addressV non-null vertical address mode
         * @return this builder
         */
        public Builder addressModes(AddressMode addressU, AddressMode addressV) {
            this.addressU = Objects.requireNonNull(addressU, "addressU");
            this.addressV = Objects.requireNonNull(addressV, "addressV");
            return this;
        }

        /**
         * Selects within-level filtering.
         *
         * @param filter non-null filtering mode
         * @return this builder
         */
        public Builder filter(Filter filter) {
            this.filter = Objects.requireNonNull(filter, "filter");
            return this;
        }

        /**
         * Selects a defensively copied, single-mip RGBA8 payload.
         *
         * @param rgba8 tightly packed level-zero pixels
         * @return this builder
         */
        public Builder pixelsRgba8(byte[] rgba8) {
            return copiedMipChainRgba8(1, rgba8);
        }

        /**
         * Selects a defensively copied, tightly packed RGBA8 mip chain.
         *
         * @param mipLevelCount mip count in the valid chain range
         * @param rgba8         levels in ascending order without padding
         * @return this builder
         */
        public Builder mipChainRgba8(int mipLevelCount, byte[] rgba8) {
            return copiedMipChainRgba8(mipLevelCount, rgba8);
        }

        private Builder copiedMipChainRgba8(int mipLevelCount, byte[] rgba8) {
            byte[] checked = Objects.requireNonNull(rgba8, "rgba8");
            validatePixelSelection(mipLevelCount, checked.length);
            byte[] copy = checked.clone();
            selectPixels(
                    mipLevelCount,
                    ByteBuffer.wrap(copy).asReadOnlyBuffer(),
                    PixelOwnership.COPIED
            );
            return this;
        }

        /**
         * Selects a single-mip immutable direct payload without copying it.
         *
         * <p>The caller must permanently relinquish every writable alias and keep the external
         * allocator or arena alive for at least as long as the built asset.</p>
         *
         * @param rgba8 read-only direct level-zero pixels
         * @return this builder
         */
        public Builder immutableDirectPixelsRgba8(ByteBuffer rgba8) {
            return immutableDirectMipChainRgba8(1, rgba8);
        }

        /**
         * Selects an immutable direct mip chain without copying it.
         *
         * <p>The current {@code position..limit} range is captured immediately. The caller must
         * permanently relinquish every writable alias and retain the external allocation for the
         * asset's complete lifetime.</p>
         *
         * @param mipLevelCount mip count in the valid chain range
         * @param rgba8         read-only direct levels in ascending order without padding
         * @return this builder
         */
        public Builder immutableDirectMipChainRgba8(int mipLevelCount, ByteBuffer rgba8) {
            ByteBuffer checked = Objects.requireNonNull(rgba8, "rgba8");
            if (!checked.isDirect() || !checked.isReadOnly()) {
                throw new IllegalArgumentException("rgba8 must be a read-only direct buffer");
            }
            selectPixels(mipLevelCount, checked.slice().asReadOnlyBuffer(), PixelOwnership.IMMUTABLE_DIRECT);
            return this;
        }

        private void selectPixels(int mipLevelCount, ByteBuffer rgba8, PixelOwnership ownership) {
            validatePixelSelection(mipLevelCount, rgba8.remaining());
            this.mipLevelCount = mipLevelCount;
            this.rgba8 = rgba8;
            this.ownership = ownership;
        }

        private void validatePixelSelection(int mipLevelCount, long actualBytes) {
            int maximumMipLevels = maximumMipLevelCount(width, height);
            if (mipLevelCount <= 0 || mipLevelCount > maximumMipLevels) {
                throw new IllegalArgumentException(
                        "mip level count must be in [1, " + maximumMipLevels + "]"
                );
            }
            long requiredBytes = requiredByteCount(width, height, mipLevelCount);
            if (actualBytes != requiredBytes) {
                throw new IllegalArgumentException(
                        "RGBA8 byte count must be " + requiredBytes + " but was " + actualBytes
                );
            }
        }

        /**
         * Builds the validated immutable texture generation.
         *
         * @return immutable texture asset
         * @throws IllegalStateException if no pixel source was selected
         */
        public TextureAsset build() {
            if (rgba8 == null || ownership == null) {
                throw new IllegalStateException("texture pixels must be selected before build");
            }
            return new TextureAsset(
                    id, width, height, colorSpace, addressU, addressV, filter,
                    mipLevelCount, rgba8, ownership
            );
        }
    }
}
