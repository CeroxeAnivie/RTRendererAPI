package top.ceroxe.rt.renderer.feature;

import org.lwjgl.vulkan.VK10;

/** Focused contract gate for image semantics that cannot be recovered inside JNI. */
public final class VulkanDenoisingResourceContractSelfTest {
    private VulkanDenoisingResourceContractSelfTest() {
    }

    public static void main(String[] arguments) {
        acceptsExactDistinctResources();
        rejectsAlias();
        rejectsWrongFormat();
        rejectsMismatchedExtent();
    }

    private static void acceptsExactDistinctResources() {
        new VulkanDenoisingResourceContract(
                image(1L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 64, 32),
                image(2L, VK10.VK_FORMAT_R32_SFLOAT, 64, 32),
                image(3L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 64, 32),
                image(4L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 64, 32),
                image(5L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 64, 32),
                image(6L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 64, 32),
                image(7L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 64, 32),
                image(8L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 64, 32),
                image(9L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 64, 32)
        );
    }

    private static void rejectsAlias() {
        expectIllegalArgument(() -> new VulkanDenoisingResourceContract(
                image(1L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 64, 32),
                image(2L, VK10.VK_FORMAT_R32_SFLOAT, 64, 32),
                image(3L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 64, 32),
                image(4L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 64, 32),
                image(5L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 64, 32),
                image(4L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 64, 32),
                image(7L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 64, 32),
                image(8L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 64, 32),
                image(9L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 64, 32)
        ));
    }

    private static void rejectsWrongFormat() {
        expectIllegalArgument(() -> new VulkanDenoisingResourceContract(
                image(1L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 64, 32),
                image(2L, VK10.VK_FORMAT_R16_SFLOAT, 64, 32),
                image(3L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 64, 32),
                image(4L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 64, 32),
                image(5L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 64, 32),
                image(6L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 64, 32),
                image(7L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 64, 32),
                image(8L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 64, 32),
                image(9L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 64, 32)
        ));
    }

    private static void rejectsMismatchedExtent() {
        expectIllegalArgument(() -> new VulkanDenoisingResourceContract(
                image(1L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 64, 32),
                image(2L, VK10.VK_FORMAT_R32_SFLOAT, 64, 32),
                image(3L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 64, 32),
                image(4L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 64, 32),
                image(5L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 64, 32),
                image(6L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 32, 32),
                image(7L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 64, 32),
                image(8L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 64, 32),
                image(9L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 64, 32)
        ));
    }

    private static VulkanDenoisingResourceContract.Image image(long handle, int format, int width, int height) {
        return new VulkanDenoisingResourceContract.Image(handle, format, width, height);
    }

    private static void expectIllegalArgument(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected: a bad contract must fail before a JNI call is possible.
        }
    }
}
