package top.ceroxe.rt.renderer.feature;

import org.lwjgl.vulkan.VK10;

/** Focused contract gate for reconstruction input semantics crossing into native code. */
public final class VulkanFrameReconstructionResourceContractSelfTest {
    private VulkanFrameReconstructionResourceContractSelfTest() {
    }

    public static void main(String[] arguments) {
        acceptsExactDistinctResources();
        rejectsAlias();
        rejectsMismatchedRenderExtent();
        rejectsNonGlobalExposure();
        rejectsMissingMemory();
        rejectsMissingView();
        rejectsMissingSampledUsage();
    }

    private static void acceptsExactDistinctResources() {
        new VulkanFrameReconstructionResourceContract(
                image(1L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 1280, 720),
                image(2L, VK10.VK_FORMAT_R32_SFLOAT, 1280, 720),
                image(3L, VK10.VK_FORMAT_R16G16_SFLOAT, 1280, 720),
                image(4L, VK10.VK_FORMAT_R32_SFLOAT, 1, 1)
        );
    }

    private static void rejectsAlias() {
        expectIllegalArgument(() -> new VulkanFrameReconstructionResourceContract(
                image(1L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 1280, 720),
                image(2L, VK10.VK_FORMAT_R32_SFLOAT, 1280, 720),
                image(1L, VK10.VK_FORMAT_R16G16_SFLOAT, 1280, 720),
                image(4L, VK10.VK_FORMAT_R32_SFLOAT, 1, 1)
        ));
    }

    private static void rejectsMismatchedRenderExtent() {
        expectIllegalArgument(() -> new VulkanFrameReconstructionResourceContract(
                image(1L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 1280, 720),
                image(2L, VK10.VK_FORMAT_R32_SFLOAT, 1920, 720),
                image(3L, VK10.VK_FORMAT_R16G16_SFLOAT, 1280, 720),
                image(4L, VK10.VK_FORMAT_R32_SFLOAT, 1, 1)
        ));
    }

    private static void rejectsNonGlobalExposure() {
        expectIllegalArgument(() -> new VulkanFrameReconstructionResourceContract(
                image(1L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 1280, 720),
                image(2L, VK10.VK_FORMAT_R32_SFLOAT, 1280, 720),
                image(3L, VK10.VK_FORMAT_R16G16_SFLOAT, 1280, 720),
                image(4L, VK10.VK_FORMAT_R32_SFLOAT, 1280, 720)
        ));
    }

    private static void rejectsMissingView() {
        expectIllegalArgument(() -> new VulkanFrameReconstructionResourceContract(
                new VulkanFrameReconstructionResourceContract.Image(
                        1L, 100L, 0L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 1280, 720, VK10.VK_IMAGE_USAGE_STORAGE_BIT
                ),
                image(2L, VK10.VK_FORMAT_R32_SFLOAT, 1280, 720),
                image(3L, VK10.VK_FORMAT_R16G16_SFLOAT, 1280, 720),
                image(4L, VK10.VK_FORMAT_R32_SFLOAT, 1, 1)
        ));
    }

    private static void rejectsMissingMemory() {
        expectIllegalArgument(() -> new VulkanFrameReconstructionResourceContract(
                new VulkanFrameReconstructionResourceContract.Image(
                        1L, 0L, 10_001L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 1280, 720,
                        VK10.VK_IMAGE_USAGE_STORAGE_BIT
                ),
                image(2L, VK10.VK_FORMAT_R32_SFLOAT, 1280, 720),
                image(3L, VK10.VK_FORMAT_R16G16_SFLOAT, 1280, 720),
                image(4L, VK10.VK_FORMAT_R32_SFLOAT, 1, 1)
        ));
    }

    private static void rejectsMissingSampledUsage() {
        expectIllegalArgument(() -> new VulkanFrameReconstructionResourceContract(
                new VulkanFrameReconstructionResourceContract.Image(
                        1L, 5_001L, 10_001L, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 1280, 720,
                        VK10.VK_IMAGE_USAGE_STORAGE_BIT
                ),
                image(2L, VK10.VK_FORMAT_R32_SFLOAT, 1280, 720),
                image(3L, VK10.VK_FORMAT_R16G16_SFLOAT, 1280, 720),
                image(4L, VK10.VK_FORMAT_R32_SFLOAT, 1, 1)
        ));
    }

    private static VulkanFrameReconstructionResourceContract.Image image(
            long handle, int format, int width, int height
    ) {
        return new VulkanFrameReconstructionResourceContract.Image(
                handle,
                handle + 5_000L,
                handle + 10_000L,
                format,
                width,
                height,
                VK10.VK_IMAGE_USAGE_STORAGE_BIT | VK10.VK_IMAGE_USAGE_SAMPLED_BIT
        );
    }

    private static void expectIllegalArgument(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // A malformed native resource contract must fail before feature evaluation can observe it.
        }
    }
}
