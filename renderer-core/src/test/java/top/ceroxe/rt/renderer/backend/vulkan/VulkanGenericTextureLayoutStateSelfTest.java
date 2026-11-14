package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.vulkan.VK10;
import top.ceroxe.rt.renderer.api.TextureAspect;
import top.ceroxe.rt.renderer.api.TextureSubresourceRange;

/** Validates that generic image layout state stays exact to addressed subresources. */
public final class VulkanGenericTextureLayoutStateSelfTest {
    private VulkanGenericTextureLayoutStateSelfTest() { }

    public static void main(String[] args) {
        VulkanGenericTextureLayoutState layouts = new VulkanGenericTextureLayoutState();
        require(layouts.layout(TextureAspect.COLOR, 0, 0) == VK10.VK_IMAGE_LAYOUT_UNDEFINED,
                "unmentioned subresources must start undefined");
        layouts.set(new TextureSubresourceRange(TextureAspect.COLOR, 1, 1, 2, 2),
                VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        require(layouts.layout(TextureAspect.COLOR, 1, 2) == VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                "first addressed array layer lost its layout");
        require(layouts.layout(TextureAspect.COLOR, 1, 3) == VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                "last addressed array layer lost its layout");
        require(layouts.layout(TextureAspect.COLOR, 0, 2) == VK10.VK_IMAGE_LAYOUT_UNDEFINED,
                "a neighboring mip inherited a layout it was never assigned");
        require(layouts.layout(TextureAspect.COLOR, 1, 1) == VK10.VK_IMAGE_LAYOUT_UNDEFINED,
                "a neighboring layer inherited a layout it was never assigned");
        layouts.set(new TextureSubresourceRange(TextureAspect.COLOR, 1, 1, 2, 1), VK10.VK_IMAGE_LAYOUT_UNDEFINED);
        require(layouts.layout(TextureAspect.COLOR, 1, 2) == VK10.VK_IMAGE_LAYOUT_UNDEFINED,
                "returning a subresource to undefined did not clear its sparse state");
        require(layouts.layout(TextureAspect.COLOR, 1, 3) == VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                "clearing one layer changed another layer's layout");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
