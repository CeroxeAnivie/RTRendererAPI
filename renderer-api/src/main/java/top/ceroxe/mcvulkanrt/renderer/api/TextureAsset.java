package top.ceroxe.mcvulkanrt.renderer.api;

import java.nio.ByteBuffer;
import java.util.Objects;

/** Immutable tightly packed RGBA8 texture generation. */
public final class TextureAsset {
    private final long id;
    private final int width;
    private final int height;
    private final ColorSpace colorSpace;
    private final AddressMode addressU;
    private final AddressMode addressV;
    private final Filter filter;
    private final int mipLevelCount;
    private final byte[] rgba8;

    public TextureAsset(
            long id,
            int width,
            int height,
            ColorSpace colorSpace,
            AddressMode addressU,
            AddressMode addressV,
            Filter filter,
            byte[] rgba8
    ) {
        this(id, width, height, colorSpace, addressU, addressV, filter, 1, rgba8);
    }

    /**
     * Creates a texture whose bytes contain tightly packed RGBA8 mip levels in ascending order.
     * Level {@code 0} starts at byte zero; every following level starts immediately after the
     * previous level's pixels. This layout keeps one stable GPU allocation per texture while
     * allowing the shader to derive offsets without a second indirection table.
     */
    public TextureAsset(
            long id,
            int width,
            int height,
            ColorSpace colorSpace,
            AddressMode addressU,
            AddressMode addressV,
            Filter filter,
            int mipLevelCount,
            byte[] rgba8
    ) {
        MaterialAsset.requireId(id, "id");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("texture extent must be positive");
        }
        if (mipLevelCount <= 0 || mipLevelCount > maximumMipLevelCount(width, height)) {
            throw new IllegalArgumentException("mip level count must be in [1, "
                    + maximumMipLevelCount(width, height) + "]");
        }
        long requiredBytes = requiredByteCount(width, height, mipLevelCount);
        if (requiredBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("texture exceeds Java array address space");
        }
        Objects.requireNonNull(rgba8, "rgba8");
        if (rgba8.length != (int) requiredBytes) {
            throw new IllegalArgumentException("RGBA8 byte count does not match texture extent");
        }
        this.id = id;
        this.width = width;
        this.height = height;
        this.colorSpace = Objects.requireNonNull(colorSpace, "colorSpace");
        this.addressU = Objects.requireNonNull(addressU, "addressU");
        this.addressV = Objects.requireNonNull(addressV, "addressV");
        this.filter = Objects.requireNonNull(filter, "filter");
        this.mipLevelCount = mipLevelCount;
        this.rgba8 = rgba8.clone();
    }

    public long id() { return id; }
    public int width() { return width; }
    public int height() { return height; }
    public ColorSpace colorSpace() { return colorSpace; }
    public AddressMode addressU() { return addressU; }
    public AddressMode addressV() { return addressV; }
    public Filter filter() { return filter; }
    public int mipLevelCount() { return mipLevelCount; }
    public int mipWidth(int level) { return mipExtent(width, level, mipLevelCount, "level"); }
    public int mipHeight(int level) { return mipExtent(height, level, mipLevelCount, "level"); }
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
    public ByteBuffer rgba8() { return ByteBuffer.wrap(rgba8).asReadOnlyBuffer(); }

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

    public enum ColorSpace { LINEAR, SRGB }
    public enum AddressMode { REPEAT, CLAMP_TO_EDGE }
    public enum Filter { NEAREST, LINEAR }
}
