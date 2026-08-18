package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.vulkan.VK10;
import top.ceroxe.rt.renderer.api.TextureAspect;
import top.ceroxe.rt.renderer.api.TextureSubresourceRange;

/**
 * Proves that composition image-layout planning is isolated until the submission commits.
 * This test intentionally uses the persistent layout state directly; no Vulkan device is needed
 * to verify the transaction boundary itself.
 */
public final class VulkanGenericTextureLayoutUpdatesSelfTest {
    private VulkanGenericTextureLayoutUpdatesSelfTest() { }

    public static void main(String[] args) {
        VulkanGenericTextureLayoutState persistent = new VulkanGenericTextureLayoutState();
        VulkanGenericTextureLayoutUpdates overlay = new VulkanGenericTextureLayoutUpdates();
        TextureSubresourceRange color = new TextureSubresourceRange(TextureAspect.COLOR, 0, 1, 0, 1);

        require(persistent.layout(TextureAspect.COLOR, 0, 0) == VK10.VK_IMAGE_LAYOUT_UNDEFINED,
                "persistent layout must start undefined");
        overlay.set(persistent, color, VK10.VK_IMAGE_LAYOUT_GENERAL);
        require(overlay.layout(persistent, TextureAspect.COLOR, 0, 0) == VK10.VK_IMAGE_LAYOUT_GENERAL,
                "overlay must expose the staged layout to command recording");
        require(persistent.layout(TextureAspect.COLOR, 0, 0) == VK10.VK_IMAGE_LAYOUT_UNDEFINED,
                "staging must not mutate persistent layout before submit success");

        overlay.commit();
        require(persistent.layout(TextureAspect.COLOR, 0, 0) == VK10.VK_IMAGE_LAYOUT_GENERAL,
                "successful submit commit must publish the staged layout");
        expect(IllegalStateException.class,
                () -> overlay.set(persistent, color, VK10.VK_IMAGE_LAYOUT_UNDEFINED));
        expect(IllegalStateException.class,
                () -> overlay.layout(persistent, TextureAspect.COLOR, 0, 0));
    }

    private static void expect(Class<? extends Throwable> expected, Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (expected.isInstance(failure)) return;
            throw new AssertionError("unexpected failure type: " + failure, failure);
        }
        throw new AssertionError("expected " + expected.getSimpleName());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
