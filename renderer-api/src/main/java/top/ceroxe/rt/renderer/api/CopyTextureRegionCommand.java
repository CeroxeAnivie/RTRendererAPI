package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Copies one exact texel region between compatible texture generations. */
public record CopyTextureRegionCommand(
        ResourceSlice.TextureSlice source,
        ResourceSlice.TextureSlice destination,
        TextureOrigin sourceOrigin,
        TextureOrigin destinationOrigin,
        TextureExtent extent
) implements RenderCommand {
    public CopyTextureRegionCommand {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        sourceOrigin = Objects.requireNonNull(sourceOrigin, "sourceOrigin");
        destinationOrigin = Objects.requireNonNull(destinationOrigin, "destinationOrigin");
        extent = Objects.requireNonNull(extent, "extent");
        TextureResource sourceTexture = source.resource();
        TextureResource destinationTexture = destination.resource();
        if (!sourceTexture.usage().contains(TextureUsage.COPY_SOURCE)
                || !destinationTexture.usage().contains(TextureUsage.COPY_DESTINATION)) {
            throw new IllegalArgumentException("texture copy regions require COPY_SOURCE and COPY_DESTINATION usage");
        }
        if (sourceTexture.format() != destinationTexture.format()
                || sourceTexture.sampleCount() != destinationTexture.sampleCount()
                || source.range().aspect() != destination.range().aspect()) {
            throw new IllegalArgumentException("texture copy regions require matching format, samples, and aspect");
        }
        TextureRegionValidation.requireContained(sourceTexture, source.range(), sourceOrigin, extent);
        TextureRegionValidation.requireContained(destinationTexture, destination.range(), destinationOrigin, extent);
        if (sameGeneration(source, destination) && rangesOverlap(source.range(), destination.range())
                && overlaps(sourceOrigin, destinationOrigin, extent)) {
            throw new IllegalArgumentException("overlapping texture copies within one generation are undefined");
        }
    }

    private static boolean sameGeneration(ResourceSlice.TextureSlice first, ResourceSlice.TextureSlice second) {
        return first.resource().id().equals(second.resource().id())
                && first.resource().version().equals(second.resource().version());
    }

    private static boolean overlaps(TextureOrigin source, TextureOrigin destination, TextureExtent extent) {
        return (long) source.x() < (long) destination.x() + extent.width()
                && (long) destination.x() < (long) source.x() + extent.width()
                && (long) source.y() < (long) destination.y() + extent.height()
                && (long) destination.y() < (long) source.y() + extent.height()
                && (long) source.z() < (long) destination.z() + extent.depth()
                && (long) destination.z() < (long) source.z() + extent.depth();
    }

    private static boolean rangesOverlap(TextureSubresourceRange first, TextureSubresourceRange second) {
        return first.aspect() == second.aspect()
                && first.baseMipLevel() < second.mipEndExclusive()
                && second.baseMipLevel() < first.mipEndExclusive()
                && first.baseArrayLayer() < second.arrayLayerEndExclusive()
                && second.baseArrayLayer() < first.arrayLayerEndExclusive();
    }
}
