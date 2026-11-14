package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.ResourceMutationKey;
import top.ceroxe.rt.renderer.api.TextureFormat;

/** Exact resident generic source resolved for one composition submission. */
record VulkanGenericCompositionSource(
        ResourceMutationKey mutation,
        TextureFormat format,
        int width,
        int height,
        long image,
        long view,
        int layout
) {
    VulkanGenericCompositionSource {
        if (mutation == null || format == null) throw new NullPointerException("composition source identity");
        if (width <= 0 || height <= 0 || image == 0L || view == 0L || layout < 0) {
            throw new IllegalArgumentException("composition source native image metadata is invalid");
        }
    }
}
