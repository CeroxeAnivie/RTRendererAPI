package top.ceroxe.rt.renderer.rt.pipeline;

/** Verifies the internal render/public output extent ownership boundary. */
public final class VulkanFrameExtentsSelfTest {
    private VulkanFrameExtentsSelfTest() {
    }

    public static void main(String[] arguments) {
        VulkanFrameExtents identity = VulkanFrameExtents.identity(1920, 1080);
        require(identity.isIdentity() && identity.renderPixelCount() == 2_073_600L
                        && identity.outputPixelCount() == 2_073_600L,
                "identity extent lost its single-resolution contract");
        VulkanFrameExtents reconstructed = new VulkanFrameExtents(1280, 720, 1920, 1080);
        require(!reconstructed.isIdentity() && reconstructed.renderPixelCount() == 921_600L
                        && reconstructed.outputPixelCount() == 2_073_600L,
                "reconstruction extent did not retain independent render and output sizes");
        expect(IllegalArgumentException.class, () -> new VulkanFrameExtents(1921, 1080, 1920, 1080));
        expect(IllegalArgumentException.class, () -> new VulkanFrameExtents(0, 720, 1920, 1080));
        System.out.println("VulkanFrameExtentsSelfTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static <T extends Throwable> void expect(Class<T> type, ThrowingRunnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("expected " + type.getName(), failure);
        }
        throw new AssertionError("expected " + type.getName());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }
}
