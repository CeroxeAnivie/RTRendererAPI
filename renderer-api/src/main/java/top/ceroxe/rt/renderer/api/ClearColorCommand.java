package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Clears one exact color texture subresource range outside a render pass. */
public record ClearColorCommand(ResourceSlice.TextureSlice destination, ClearColorValue value) implements RenderCommand {
    /** Validates color aspect and attachment/storage write capability. */
    public ClearColorCommand {
        destination = Objects.requireNonNull(destination, "destination");
        value = Objects.requireNonNull(value, "value");
        if (destination.range().aspect() != TextureAspect.COLOR) {
            throw new IllegalArgumentException("color clear requires a COLOR texture aspect");
        }
        if (!destination.resource().usage().contains(TextureUsage.COPY_DESTINATION)
                && !destination.resource().usage().contains(TextureUsage.COLOR_ATTACHMENT)) {
            throw new IllegalArgumentException("color clear destination requires COPY_DESTINATION or COLOR_ATTACHMENT usage");
        }
    }
}
