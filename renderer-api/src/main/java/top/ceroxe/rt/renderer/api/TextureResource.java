package top.ceroxe.rt.renderer.api;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable descriptor of one versioned generic texture resource.
 *
 * <p>This describes declared storage only. It neither uploads texels nor grants backend residency;
 * explicit command transactions retain those distinct lifecycle facts.</p>
 */
public final class TextureResource implements RenderResource {
    private final RenderResourceId id;
    private final ResourceVersion version;
    private final TextureDimension dimension;
    private final int width;
    private final int height;
    private final int depth;
    private final int mipLevelCount;
    private final int arrayLayerCount;
    private final int sampleCount;
    private final TextureFormat format;
    private final Set<TextureUsage> usage;

    /**
     * Creates a versioned texture descriptor.
     *
     * @param id stable resource identity
     * @param version published content generation
     * @param dimension physical storage dimension
     * @param width positive level-zero width
     * @param height positive level-zero height; one for one-dimensional textures
     * @param depth positive level-zero depth; one unless {@code dimension} is three-dimensional
     * @param mipLevelCount positive mip count
     * @param arrayLayerCount positive array-layer count; one for three-dimensional textures
     * @param sampleCount positive sample count; multisample textures have exactly one mip
     * @param format non-null texel format
     * @param usage non-empty declared access roles
     */
    public TextureResource(
            RenderResourceId id,
            ResourceVersion version,
            TextureDimension dimension,
            int width,
            int height,
            int depth,
            int mipLevelCount,
            int arrayLayerCount,
            int sampleCount,
            TextureFormat format,
            Set<TextureUsage> usage
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.version = Objects.requireNonNull(version, "version");
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        if (width <= 0 || height <= 0 || depth <= 0 || mipLevelCount <= 0 || arrayLayerCount <= 0
                || sampleCount <= 0) {
            throw new IllegalArgumentException("texture extents, mip count, layer count, and sample count must be positive");
        }
        if (dimension == TextureDimension.TEXTURE_1D && (height != 1 || depth != 1)) {
            throw new IllegalArgumentException("one-dimensional textures must have height and depth of one");
        }
        if (dimension == TextureDimension.TEXTURE_2D && depth != 1) {
            throw new IllegalArgumentException("two-dimensional textures must have depth of one");
        }
        if (dimension == TextureDimension.TEXTURE_3D && arrayLayerCount != 1) {
            throw new IllegalArgumentException("three-dimensional textures must have exactly one array layer");
        }
        if (sampleCount > 1 && (dimension != TextureDimension.TEXTURE_2D || mipLevelCount != 1)) {
            throw new IllegalArgumentException("multisample textures must be two-dimensional with exactly one mip level");
        }
        if (Integer.bitCount(sampleCount) != 1) {
            throw new IllegalArgumentException("texture sample count must be a power of two");
        }
        int maximumMipLevels = maximumMipLevelCount(width, height, depth);
        if (mipLevelCount > maximumMipLevels) {
            throw new IllegalArgumentException("texture mip count exceeds its extent-limited maximum of " + maximumMipLevels);
        }
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.mipLevelCount = mipLevelCount;
        this.arrayLayerCount = arrayLayerCount;
        this.sampleCount = sampleCount;
        this.format = Objects.requireNonNull(format, "format");
        Objects.requireNonNull(usage, "usage");
        if (usage.isEmpty()) {
            throw new IllegalArgumentException("texture usage must not be empty");
        }
        EnumSet<TextureUsage> checkedUsage = EnumSet.noneOf(TextureUsage.class);
        for (TextureUsage role : usage) {
            checkedUsage.add(Objects.requireNonNull(role, "texture usage element"));
        }
        boolean depthStencil = format.aspects().contains(TextureAspect.DEPTH)
                || format.aspects().contains(TextureAspect.STENCIL);
        if (depthStencil && (checkedUsage.contains(TextureUsage.COLOR_ATTACHMENT)
                || checkedUsage.contains(TextureUsage.STORAGE_READ)
                || checkedUsage.contains(TextureUsage.STORAGE_READ_WRITE))) {
            throw new IllegalArgumentException("depth-stencil formats cannot declare color or storage-image usage");
        }
        if (!depthStencil && checkedUsage.contains(TextureUsage.DEPTH_STENCIL_ATTACHMENT)) {
            throw new IllegalArgumentException("color formats cannot declare depth-stencil attachment usage");
        }
        if (format == TextureFormat.RGBA8_SRGB && (checkedUsage.contains(TextureUsage.STORAGE_READ)
                || checkedUsage.contains(TextureUsage.STORAGE_READ_WRITE))) {
            throw new IllegalArgumentException("sRGB formats cannot declare storage-image usage");
        }
        this.usage = Collections.unmodifiableSet(checkedUsage);
    }

    /** @return stable resource identity */
    public RenderResourceId id() { return id; }

    /** @return published content version */
    public ResourceVersion version() { return version; }

    /** @return physical storage dimension */
    public TextureDimension dimension() { return dimension; }

    /** @return positive level-zero width */
    public int width() { return width; }

    /** @return positive level-zero height */
    public int height() { return height; }

    /** @return positive level-zero depth */
    public int depth() { return depth; }

    /** @return positive mip count */
    public int mipLevelCount() { return mipLevelCount; }

    /** @return positive array-layer count */
    public int arrayLayerCount() { return arrayLayerCount; }

    /** @return positive sample count */
    public int sampleCount() { return sampleCount; }

    /** @return non-null immutable texel format */
    public TextureFormat format() { return format; }

    /** @return non-empty immutable usage set */
    public Set<TextureUsage> usage() { return usage; }

    /**
     * Validates a texture-relative subresource range.
     *
     * @param range non-null range to validate
     * @return the same validated range
     */
    public TextureSubresourceRange requireContained(TextureSubresourceRange range) {
        TextureSubresourceRange checked = Objects.requireNonNull(range, "range");
        if (!format.supports(checked.aspect())) {
            throw new IllegalArgumentException("texture format does not expose requested aspect");
        }
        if (checked.mipEndExclusive() > mipLevelCount || checked.arrayLayerEndExclusive() > arrayLayerCount) {
            throw new IllegalArgumentException("texture subresource range exceeds resource extent");
        }
        return checked;
    }

    /**
     * Computes the maximum legal mip count for a positive extent.
     *
     * @param width positive width
     * @param height positive height
     * @param depth positive depth
     * @return number of levels through one texel in every active dimension
     */
    public static int maximumMipLevelCount(int width, int height, int depth) {
        if (width <= 0 || height <= 0 || depth <= 0) {
            throw new IllegalArgumentException("texture extent must be positive");
        }
        int largest = Math.max(width, Math.max(height, depth));
        return Integer.SIZE - Integer.numberOfLeadingZeros(largest);
    }
}
