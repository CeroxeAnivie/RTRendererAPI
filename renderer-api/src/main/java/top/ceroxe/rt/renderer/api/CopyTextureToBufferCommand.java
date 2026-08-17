package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Copies one texture region into an explicitly pitched buffer range. */
public record CopyTextureToBufferCommand(
        ResourceSlice.TextureSlice source,
        TextureOrigin sourceOrigin,
        TextureExtent extent,
        ResourceSlice.BufferSlice destination,
        TextureDataLayout destinationLayout
) implements RenderCommand {
    /** Performs the overflow-safe portable region and pitch validation at construction. */
    public CopyTextureToBufferCommand {
        source = Objects.requireNonNull(source, "source");
        sourceOrigin = Objects.requireNonNull(sourceOrigin, "sourceOrigin");
        extent = Objects.requireNonNull(extent, "extent");
        destination = Objects.requireNonNull(destination, "destination");
        destinationLayout = Objects.requireNonNull(destinationLayout, "destinationLayout");
        if (!source.resource().usage().contains(TextureUsage.COPY_SOURCE)
                || !destination.resource().usage().contains(BufferUsage.COPY_DESTINATION)) {
            throw new IllegalArgumentException("texture-to-buffer copy requires COPY_SOURCE and COPY_DESTINATION usage");
        }
        TextureRegionValidation.requireContained(source.resource(), source.range(), sourceOrigin, extent);
        long required = TextureRegionValidation.requiredBytes(source.resource().format(), extent, destinationLayout);
        if (required > destination.range().lengthBytes()) {
            throw new IllegalArgumentException("texture-to-buffer destination range is smaller than its pitched region");
        }
    }
}
