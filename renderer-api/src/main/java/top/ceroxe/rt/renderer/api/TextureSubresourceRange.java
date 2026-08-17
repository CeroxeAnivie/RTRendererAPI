package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Immutable contiguous mip and array-layer range of one texture aspect.
 *
 * <p>Resource-relative bounds are deliberately validated by {@link TextureResource}; keeping
 * this value resource-independent makes it reusable in copies, barriers, and view declarations.
 * Counts are non-zero because an empty texture view cannot have executable sampling semantics.</p>
 *
 * @param aspect non-null texture aspect
 * @param baseMipLevel first addressed mip level
 * @param mipLevelCount number of addressed mip levels
 * @param baseArrayLayer first addressed array layer
 * @param arrayLayerCount number of addressed array layers
 */
public record TextureSubresourceRange(
        TextureAspect aspect,
        int baseMipLevel,
        int mipLevelCount,
        int baseArrayLayer,
        int arrayLayerCount
) {
    /**
     * Validates range coordinates without allowing integer overflow in their exclusive ends.
     */
    public TextureSubresourceRange {
        Objects.requireNonNull(aspect, "aspect");
        if (baseMipLevel < 0 || mipLevelCount <= 0 || baseArrayLayer < 0 || arrayLayerCount <= 0) {
            throw new IllegalArgumentException("texture subresource range must use non-negative bases and positive counts");
        }
        try {
            Math.addExact(baseMipLevel, mipLevelCount);
            Math.addExact(baseArrayLayer, arrayLayerCount);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("texture subresource range end overflows int", overflow);
        }
    }

    /**
     * Returns the exclusive addressed mip level.
     *
     * @return non-negative exclusive mip end
     */
    public int mipEndExclusive() {
        return Math.addExact(baseMipLevel, mipLevelCount);
    }

    /**
     * Returns the exclusive addressed array layer.
     *
     * @return non-negative exclusive array-layer end
     */
    public int arrayLayerEndExclusive() {
        return Math.addExact(baseArrayLayer, arrayLayerCount);
    }
}
