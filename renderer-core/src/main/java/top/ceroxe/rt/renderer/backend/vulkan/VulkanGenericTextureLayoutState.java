package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.vulkan.VK10;
import top.ceroxe.rt.renderer.api.TextureAspect;
import top.ceroxe.rt.renderer.api.TextureSubresourceRange;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Sparse, exact Vulkan layout state for one generic image generation. */
final class VulkanGenericTextureLayoutState {
    private final Map<Subresource, Integer> layouts = new HashMap<>();

    int layout(TextureAspect aspect, int mipLevel, int arrayLayer) {
        return layouts.getOrDefault(new Subresource(aspect, mipLevel, arrayLayer), VK10.VK_IMAGE_LAYOUT_UNDEFINED);
    }

    void set(TextureSubresourceRange range, int layout) {
        Objects.requireNonNull(range, "range");
        for (int mip = range.baseMipLevel(); mip < range.mipEndExclusive(); mip++) {
            for (int layer = range.baseArrayLayer(); layer < range.arrayLayerEndExclusive(); layer++) {
                Subresource key = new Subresource(range.aspect(), mip, layer);
                if (layout == VK10.VK_IMAGE_LAYOUT_UNDEFINED) layouts.remove(key);
                else layouts.put(key, layout);
            }
        }
    }

    private record Subresource(TextureAspect aspect, int mipLevel, int arrayLayer) {
        private Subresource {
            Objects.requireNonNull(aspect, "aspect");
            if (mipLevel < 0 || arrayLayer < 0) throw new IllegalArgumentException("subresource coordinates must be non-negative");
        }
    }
}
