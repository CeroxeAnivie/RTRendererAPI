package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import top.ceroxe.rt.renderer.api.TextureSubresourceRange;
import top.ceroxe.rt.renderer.api.TextureView;
import top.ceroxe.rt.renderer.api.TextureViewDimension;
import top.ceroxe.rt.renderer.rt.device.VulkanFailures;

import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Owns cached native views for exactly one versioned generic texture image. */
final class VulkanGenericTextureViews implements AutoCloseable {
    private final VkDevice device;
    private final VulkanGenericTextureImage image;
    private final Map<ViewKey, Long> handles = new HashMap<>();
    private boolean closed;

    VulkanGenericTextureViews(VkDevice device, VulkanGenericTextureImage image) {
        this.device = Objects.requireNonNull(device, "device");
        this.image = Objects.requireNonNull(image, "image");
    }

    long require(TextureView view) {
        requireOpen();
        Objects.requireNonNull(view, "view");
        ViewKey key = ViewKey.of(view);
        Long existing = handles.get(key);
        if (existing != null) return existing;
        long created = create(key);
        handles.put(key, created);
        return created;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (long handle : handles.values()) VK10.vkDestroyImageView(device, handle, null);
        handles.clear();
    }

    private long create(ViewKey key) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack)
                    .sType$Default()
                    .image(image.image())
                    .viewType(viewType(key.dimension()))
                    .format(VulkanGenericTextureImage.format(key.format()));
            info.components().r(VK10.VK_COMPONENT_SWIZZLE_IDENTITY).g(VK10.VK_COMPONENT_SWIZZLE_IDENTITY)
                    .b(VK10.VK_COMPONENT_SWIZZLE_IDENTITY).a(VK10.VK_COMPONENT_SWIZZLE_IDENTITY);
            TextureSubresourceRange range = key.range();
            info.subresourceRange().aspectMask(VulkanGenericTextureMappings.aspectMask(range.aspect()))
                    .baseMipLevel(range.baseMipLevel()).levelCount(range.mipLevelCount())
                    .baseArrayLayer(range.baseArrayLayer()).layerCount(range.arrayLayerCount());
            LongBuffer output = stack.longs(VK10.VK_NULL_HANDLE);
            VulkanFailures.check(VK10.vkCreateImageView(device, info, null, output), "vkCreateImageView.genericTexture");
            return output.get(0);
        }
    }

    private static int viewType(TextureViewDimension value) {
        return switch (value) {
            case TEXTURE_1D -> VK10.VK_IMAGE_VIEW_TYPE_1D;
            case TEXTURE_1D_ARRAY -> VK10.VK_IMAGE_VIEW_TYPE_1D_ARRAY;
            case TEXTURE_2D -> VK10.VK_IMAGE_VIEW_TYPE_2D;
            case TEXTURE_2D_ARRAY -> VK10.VK_IMAGE_VIEW_TYPE_2D_ARRAY;
            case TEXTURE_2D_MULTISAMPLED -> VK10.VK_IMAGE_VIEW_TYPE_2D;
            case TEXTURE_2D_MULTISAMPLED_ARRAY -> VK10.VK_IMAGE_VIEW_TYPE_2D_ARRAY;
            case TEXTURE_3D -> VK10.VK_IMAGE_VIEW_TYPE_3D;
            case CUBE -> VK10.VK_IMAGE_VIEW_TYPE_CUBE;
            case CUBE_ARRAY -> VK10.VK_IMAGE_VIEW_TYPE_CUBE_ARRAY;
        };
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("generic texture view owner is closed");
    }

    private record ViewKey(
            top.ceroxe.rt.renderer.api.TextureFormat format,
            TextureViewDimension dimension,
            TextureSubresourceRange range
    ) {
        private static ViewKey of(TextureView view) {
            return new ViewKey(view.texture().format(), view.dimension(), view.range());
        }

        private ViewKey {
            Objects.requireNonNull(format, "format");
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(range, "range");
        }
    }
}
