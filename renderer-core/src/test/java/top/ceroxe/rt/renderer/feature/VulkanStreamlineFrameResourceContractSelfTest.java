package top.ceroxe.rt.renderer.feature;

import org.lwjgl.vulkan.VK10;

/** Verifies that no Streamline tag set can omit or alias its output image. */
public final class VulkanStreamlineFrameResourceContractSelfTest {
    private VulkanStreamlineFrameResourceContractSelfTest() {
    }

    public static void main(String[] arguments) {
        VulkanFrameReconstructionResourceContract.Image input = image(1L, 1280, 720,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT);
        VulkanFrameReconstructionResourceContract.Image output = image(2L, 1920, 1080,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT);
        VulkanFrameReconstructionResourceContract.Image depth = image(3L, 1280, 720,
                VK10.VK_FORMAT_R32_SFLOAT);
        VulkanFrameReconstructionResourceContract.Image motion = image(4L, 1280, 720,
                VK10.VK_FORMAT_R16G16_SFLOAT);
        VulkanFrameReconstructionResourceContract.Image exposure = image(5L, 1, 1,
                VK10.VK_FORMAT_R32_SFLOAT);
        VulkanStreamlineFrameResourceContract contract = new VulkanStreamlineFrameResourceContract(
                input, output, depth, motion, exposure
        );
        require(contract.outputColor().width() == 1920 && contract.outputColor().height() == 1080,
                "Streamline output extent changed");
        expect(IllegalArgumentException.class, () -> new VulkanStreamlineFrameResourceContract(
                input, input, depth, motion, exposure
        ));
        expect(IllegalArgumentException.class, () -> new VulkanStreamlineFrameResourceContract(
                input, image(6L, 1920, 1080, VK10.VK_FORMAT_R32_SFLOAT), depth, motion, exposure
        ));
        expect(IllegalArgumentException.class, () -> new VulkanStreamlineFrameResourceContract(
                storageImage(7L, 1280, 720, VK10.VK_FORMAT_R16G16B16A16_SFLOAT),
                output, depth, motion, exposure
        ));
        System.out.println("VulkanStreamlineFrameResourceContractSelfTest passed");
    }

    private static VulkanFrameReconstructionResourceContract.Image image(
            long handle, int width, int height, int format
    ) {
        return new VulkanFrameReconstructionResourceContract.Image(
                handle, handle + 10L, handle + 20L, format, width, height,
                VK10.VK_IMAGE_USAGE_STORAGE_BIT | VK10.VK_IMAGE_USAGE_SAMPLED_BIT
        );
    }

    private static VulkanFrameReconstructionResourceContract.Image storageImage(
            long handle, int width, int height, int format
    ) {
        return new VulkanFrameReconstructionResourceContract.Image(
                handle, handle + 10L, handle + 20L, format, width, height, VK10.VK_IMAGE_USAGE_STORAGE_BIT
        );
    }

    private static <T extends Throwable> void expect(Class<T> type, ThrowingRunnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("expected " + type.getName(), failure);
        }
        throw new AssertionError("expected " + type.getName() + " but no exception was thrown");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Throwable;
    }
}
