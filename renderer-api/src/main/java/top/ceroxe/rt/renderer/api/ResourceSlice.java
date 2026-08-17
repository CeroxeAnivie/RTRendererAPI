package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Exact, version-preserving subrange of a generic storage resource.
 *
 * <p>The slice carries its complete immutable descriptor so validation cannot accidentally bind a
 * range to a newer resource generation with the same stable identity.</p>
 */
public sealed interface ResourceSlice permits ResourceSlice.BufferSlice, ResourceSlice.TextureSlice {
    /** @return exact immutable source resource descriptor */
    RenderResource resource();

    /** Versioned byte range of a buffer resource. */
    record BufferSlice(BufferResource resource, ByteRange range) implements ResourceSlice {
        /** Validates that the range is fully contained by the exact buffer generation. */
        public BufferSlice {
            Objects.requireNonNull(resource, "resource");
            resource.requireContained(Objects.requireNonNull(range, "range"));
        }
    }

    /** Versioned mip/layer/aspect range of a texture resource. */
    record TextureSlice(TextureResource resource, TextureSubresourceRange range) implements ResourceSlice {
        /** Validates that the range is fully contained by the exact texture generation. */
        public TextureSlice {
            Objects.requireNonNull(resource, "resource");
            resource.requireContained(Objects.requireNonNull(range, "range"));
        }
    }
}
