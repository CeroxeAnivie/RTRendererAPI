package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Copies complete matching subresources between exact texture generations. */
public record CopyTextureCommand(
        ResourceSlice.TextureSlice source,
        ResourceSlice.TextureSlice destination
) implements RenderCommand {
    /** Validates usage and portable full-subresource copy compatibility. */
    public CopyTextureCommand {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        TextureResource sourceTexture = source.resource();
        TextureResource destinationTexture = destination.resource();
        if (!sourceTexture.usage().contains(TextureUsage.COPY_SOURCE)) {
            throw new IllegalArgumentException("source texture does not declare COPY_SOURCE usage");
        }
        if (!destinationTexture.usage().contains(TextureUsage.COPY_DESTINATION)) {
            throw new IllegalArgumentException("destination texture does not declare COPY_DESTINATION usage");
        }
        if (sourceTexture.format() != destinationTexture.format()
                || sourceTexture.sampleCount() != destinationTexture.sampleCount()
                || source.range().aspect() != destination.range().aspect()
                || source.range().mipLevelCount() != destination.range().mipLevelCount()
                || source.range().arrayLayerCount() != destination.range().arrayLayerCount()) {
            throw new IllegalArgumentException("texture copy slices have incompatible formats or subresource counts");
        }
        for (int offset = 0; offset < source.range().mipLevelCount(); offset++) {
            int sourceMip = source.range().baseMipLevel() + offset;
            int destinationMip = destination.range().baseMipLevel() + offset;
            if (mipExtent(sourceTexture.width(), sourceMip) != mipExtent(destinationTexture.width(), destinationMip)
                    || mipExtent(sourceTexture.height(), sourceMip) != mipExtent(destinationTexture.height(), destinationMip)
                    || mipExtent(sourceTexture.depth(), sourceMip) != mipExtent(destinationTexture.depth(), destinationMip)) {
                throw new IllegalArgumentException("texture copy slices address different mip extents");
            }
        }
        boolean sameGeneration = sourceTexture.id().equals(destinationTexture.id())
                && sourceTexture.version().equals(destinationTexture.version());
        if (sameGeneration && overlaps(source.range(), destination.range())) {
            throw new IllegalArgumentException("overlapping copies within one texture generation are undefined");
        }
    }

    private static boolean overlaps(TextureSubresourceRange first, TextureSubresourceRange second) {
        return first.aspect() == second.aspect()
                && first.baseMipLevel() < second.mipEndExclusive()
                && second.baseMipLevel() < first.mipEndExclusive()
                && first.baseArrayLayer() < second.arrayLayerEndExclusive()
                && second.baseArrayLayer() < first.arrayLayerEndExclusive();
    }

    private static int mipExtent(int baseExtent, int mipLevel) {
        return Math.max(1, baseExtent >> Math.min(mipLevel, Integer.SIZE - 1));
    }
}
