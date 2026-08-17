package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.vulkan.VK10;
import top.ceroxe.rt.renderer.api.RenderResourceAccess;
import top.ceroxe.rt.renderer.api.TextureAspect;
import top.ceroxe.rt.renderer.api.TextureFormat;

import java.util.Set;

/** Vulkan-only mapping for the portable texture contract; it never infers a presentation ownership transfer. */
final class VulkanGenericTextureMappings {
    private VulkanGenericTextureMappings() { }

    static int aspectMask(TextureAspect aspect) {
        return switch (aspect) {
            case COLOR -> VK10.VK_IMAGE_ASPECT_COLOR_BIT;
            case DEPTH -> VK10.VK_IMAGE_ASPECT_DEPTH_BIT;
            case STENCIL -> VK10.VK_IMAGE_ASPECT_STENCIL_BIT;
        };
    }

    static int fullAspectMask(TextureFormat format) {
        int result = 0;
        for (TextureAspect aspect : format.aspects()) result |= aspectMask(aspect);
        return result;
    }

    static int layoutFor(Set<RenderResourceAccess> accesses, TextureFormat format) {
        if (accesses.contains(RenderResourceAccess.PRESENT_READ)) {
            throw new UnsupportedOperationException("generic command path does not own presentation layout transitions");
        }
        if (accesses.contains(RenderResourceAccess.COPY_WRITE)) return VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        if (accesses.contains(RenderResourceAccess.COPY_READ)) return VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        if (accesses.contains(RenderResourceAccess.COLOR_ATTACHMENT_WRITE)
                || accesses.contains(RenderResourceAccess.COLOR_ATTACHMENT_READ)) {
            return VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
        }
        if (accesses.contains(RenderResourceAccess.DEPTH_STENCIL_WRITE)
                || accesses.contains(RenderResourceAccess.DEPTH_STENCIL_READ)) {
            if (!format.aspects().contains(TextureAspect.DEPTH)) {
                throw new IllegalArgumentException("depth/stencil access requires a depth texture format");
            }
            return VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
        }
        if (accesses.contains(RenderResourceAccess.SHADER_WRITE)) return VK10.VK_IMAGE_LAYOUT_GENERAL;
        if (accesses.contains(RenderResourceAccess.SHADER_READ)) return VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        return VK10.VK_IMAGE_LAYOUT_GENERAL;
    }
}
