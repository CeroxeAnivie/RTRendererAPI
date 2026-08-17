package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Clears one exact depth or stencil subresource range outside a render pass. */
public record ClearDepthStencilCommand(
        ResourceSlice.TextureSlice destination,
        float depth,
        int stencil
) implements RenderCommand {
    /** Validates finite depth, the unsigned eight-bit stencil domain, and depth/stencil usage. */
    public ClearDepthStencilCommand {
        destination = Objects.requireNonNull(destination, "destination");
        if (!Float.isFinite(depth) || depth < 0.0f || depth > 1.0f) {
            throw new IllegalArgumentException("clear depth must be finite and within [0, 1]");
        }
        if (stencil < 0 || stencil > 0xff) {
            throw new IllegalArgumentException("clear stencil must be within unsigned eight-bit range");
        }
        if (destination.range().aspect() == TextureAspect.COLOR) {
            throw new IllegalArgumentException("depth/stencil clear requires DEPTH or STENCIL texture aspect");
        }
        if (!destination.resource().usage().contains(TextureUsage.DEPTH_STENCIL_ATTACHMENT)
                && !destination.resource().usage().contains(TextureUsage.COPY_DESTINATION)) {
            throw new IllegalArgumentException("depth/stencil clear destination requires DEPTH_STENCIL_ATTACHMENT or COPY_DESTINATION usage");
        }
    }
}
