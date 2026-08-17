package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Writes an explicitly pitched immutable payload into one texture region. */
public record WriteTextureCommand(
        ResourceSlice.TextureSlice destination,
        TextureOrigin origin,
        TextureExtent extent,
        TextureDataLayout layout,
        ResourceData data
) implements RenderCommand {
    public WriteTextureCommand {
        Objects.requireNonNull(destination, "destination");
        origin = Objects.requireNonNull(origin, "origin");
        extent = Objects.requireNonNull(extent, "extent");
        layout = Objects.requireNonNull(layout, "layout");
        data = Objects.requireNonNull(data, "data");
        if (!destination.resource().usage().contains(TextureUsage.COPY_DESTINATION)) {
            throw new IllegalArgumentException("write destination does not declare COPY_DESTINATION usage");
        }
        TextureRegionValidation.requireContained(destination.resource(), destination.range(), origin, extent);
        long required = TextureRegionValidation.requiredBytes(destination.resource().format(), extent, layout);
        if (required > data.byteSize()) throw new IllegalArgumentException("texture write payload is too small");
    }
}
