package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Copies explicitly pitched bytes from a buffer range into one texture region. */
public record CopyBufferToTextureCommand(
        ResourceSlice.BufferSlice source,
        ResourceSlice.TextureSlice destination,
        TextureOrigin destinationOrigin,
        TextureExtent extent,
        TextureDataLayout sourceLayout
) implements RenderCommand {
    /** Performs the overflow-safe portable region and pitch validation at construction. */
    public CopyBufferToTextureCommand {
        source = Objects.requireNonNull(source, "source");
        destination = Objects.requireNonNull(destination, "destination");
        destinationOrigin = Objects.requireNonNull(destinationOrigin, "destinationOrigin");
        extent = Objects.requireNonNull(extent, "extent");
        sourceLayout = Objects.requireNonNull(sourceLayout, "sourceLayout");
        if (!source.resource().usage().contains(BufferUsage.COPY_SOURCE)
                || !destination.resource().usage().contains(TextureUsage.COPY_DESTINATION)) {
            throw new IllegalArgumentException("buffer-to-texture copy requires COPY_SOURCE and COPY_DESTINATION usage");
        }
        TextureRegionValidation.requireContained(destination.resource(), destination.range(), destinationOrigin, extent);
        long required = TextureRegionValidation.requiredBytes(destination.resource().format(), extent, sourceLayout);
        if (required > source.range().lengthBytes()) {
            throw new IllegalArgumentException("buffer-to-texture source range is smaller than its pitched region");
        }
    }
}
