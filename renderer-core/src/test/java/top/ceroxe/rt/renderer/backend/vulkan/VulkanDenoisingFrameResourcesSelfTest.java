package top.ceroxe.rt.renderer.backend.vulkan;

/** Pure-Java budget contract for renderer-owned NRD signal images. */
public final class VulkanDenoisingFrameResourcesSelfTest {
    private VulkanDenoisingFrameResourcesSelfTest() {
    }

    public static void main(String[] args) {
        require(
                VulkanDenoisingFrameResources.requiredBytes(1920, 1080) == 141_004_800L,
                "NRD signal, 2.5D motion, and material-factor images must account for 68 bytes per pixel"
        );
        require(
                VulkanDenoisingFrameResources.requiredBytes(1, 1)
                        == VulkanDenoisingFrameResources.BYTES_PER_PIXEL,
                "one-pixel NRD resource allocation estimate is incorrect"
        );
        expectIllegalArgument(() -> VulkanDenoisingFrameResources.requiredBytes(0, 1));
        expectIllegalArgument(() -> VulkanDenoisingFrameResources.requiredBytes(1, 0));
        expectIllegalArgument(() -> VulkanDenoisingFrameResources.requiredBytes(-1, 1));
    }

    private static void expectIllegalArgument(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("expected IllegalArgumentException");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
