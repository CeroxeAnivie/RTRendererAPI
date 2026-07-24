package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

import java.util.ArrayList;
import java.util.List;

/** Pure contract gate for all-or-nothing descriptor generation publication. */
public final class GpuSceneDescriptorResourcesSelfTest {
    private GpuSceneDescriptorResourcesSelfTest() {
    }

    public static void main(String[] arguments) {
        List<GpuSceneDescriptorResources.StorageBinding> complete = completeBindings();
        GpuSceneDescriptorResources resources = new GpuSceneDescriptorResources(
                1L, 2L, GpuSceneDescriptorResources.BufferRange.whole(3L, 192L), complete
        );
        require(resources.topLevelAccelerationStructure() == 1L
                        && resources.outputImageView() == 2L,
                "descriptor publication changed native handles");
        require(resources.sceneBuffer(GpuSceneShaderBindings.LIGHT_RECORDS).buffer()
                        == 100L + GpuSceneShaderBindings.LIGHT_RECORDS,
                "descriptor publication changed canonical binding identity");

        expect(IllegalArgumentException.class, () -> new GpuSceneDescriptorResources(
                0L, 2L, GpuSceneDescriptorResources.BufferRange.whole(3L, 192L), complete
        ));
        expect(IllegalArgumentException.class, () -> new GpuSceneDescriptorResources(
                1L, 2L, GpuSceneDescriptorResources.BufferRange.whole(3L, 192L),
                complete.subList(0, complete.size() - 1)
        ));
        ArrayList<GpuSceneDescriptorResources.StorageBinding> duplicate = new ArrayList<>(complete);
        duplicate.set(duplicate.size() - 1, complete.get(0));
        expect(IllegalArgumentException.class, () -> new GpuSceneDescriptorResources(
                1L, 2L, GpuSceneDescriptorResources.BufferRange.whole(3L, 192L), duplicate
        ));
        expect(IllegalArgumentException.class,
                () -> new GpuSceneDescriptorResources.BufferRange(4L, 4L, 16L, 19L));
        expect(IllegalArgumentException.class,
                () -> new GpuSceneDescriptorResources.BufferRange(4L, 0L, 20L, 16L));
        System.out.println("GpuSceneDescriptorResourcesSelfTest passed");
    }

    private static List<GpuSceneDescriptorResources.StorageBinding> completeBindings() {
        ArrayList<GpuSceneDescriptorResources.StorageBinding> bindings = new ArrayList<>();
        for (int binding = GpuSceneShaderBindings.TEXTURE_RECORDS;
             binding <= GpuSceneShaderBindings.LIGHT_RECORDS;
             binding++) {
            bindings.add(new GpuSceneDescriptorResources.StorageBinding(
                    binding, GpuSceneDescriptorResources.BufferRange.whole(100L + binding, 64L)
            ));
        }
        return List.copyOf(bindings);
    }

    private static <T extends Throwable> void expect(Class<T> type, ThrowingRunnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("expected " + type.getName() + " but caught " + failure, failure);
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
