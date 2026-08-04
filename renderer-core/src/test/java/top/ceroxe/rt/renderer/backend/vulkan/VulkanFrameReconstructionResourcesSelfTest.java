package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.rt.pipeline.VulkanFrameExtents;

/** Pure-JVM contract gate for reconstruction input ownership dimensions and accounting. */
public final class VulkanFrameReconstructionResourcesSelfTest {
    private VulkanFrameReconstructionResourcesSelfTest() {
    }

    public static void main(String[] arguments) {
        countsRenderExtentInputsNotOutputExtent();
        rejectsOverflowInsteadOfUnderbudgetingNativeAllocations();
    }

    private static void countsRenderExtentInputsNotOutputExtent() {
        VulkanFrameExtents extents = new VulkanFrameExtents(1280, 720, 1920, 1080);
        long expected = 1280L * 720L * 16L + Integer.BYTES;
        require(VulkanFrameReconstructionResources.requiredBytes(extents) == expected);
    }

    private static void rejectsOverflowInsteadOfUnderbudgetingNativeAllocations() {
        try {
            VulkanFrameReconstructionResources.requiredBytes(
                    new VulkanFrameExtents(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE)
            );
            throw new AssertionError("expected arithmetic overflow");
        } catch (ArithmeticException expected) {
            // Native allocation budget computations must fail rather than wrap to a small positive value.
        }
    }

    private static void require(boolean condition) {
        if (!condition) throw new AssertionError("contract expectation failed");
    }
}
