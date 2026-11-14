package top.ceroxe.rt.renderer.rt.pipeline;

import java.util.ArrayList;
import java.util.List;
import top.ceroxe.rt.renderer.rt.pipeline.GpuSceneDescriptorResources.BufferRange;

public final class GpuSceneDescriptorResourcesSelfTest {
   private GpuSceneDescriptorResourcesSelfTest() {
   }

   public static void main(String[] arguments) {
      List<GpuSceneDescriptorResources.StorageBinding> complete = completeBindings();
      GpuSceneDescriptorResources resources = resources(1L, 2L, 3L, 4L, 5L, 6L, 7L, complete);
      require(resources.topLevelAccelerationStructure() == 1L && resources.outputImageView() == 2L, "descriptor publication changed native handles");
      require(resources.sceneBuffer(16).buffer() == 116L, "descriptor publication changed canonical binding identity");
      require(resources.denoisingImages().viewZ() == 9L, "denoising descriptor publication changed canonical view identity");
      expect(IllegalArgumentException.class, () -> new GpuSceneDescriptorResources(0L, 2L, 3L, 4L, 5L, 6L, 7L, views(), reconstructionViews(), BufferRange.whole(8L, 192L), complete));
      expect(IllegalArgumentException.class, () -> new GpuSceneDescriptorResources(1L, 2L, 3L, 4L, 5L, 6L, 7L, views(), reconstructionViews(), BufferRange.whole(8L, 192L), complete.subList(0, complete.size() - 1)));
      ArrayList<GpuSceneDescriptorResources.StorageBinding> duplicate = new ArrayList<>(complete);
      duplicate.set(duplicate.size() - 1, (GpuSceneDescriptorResources.StorageBinding)complete.get(0));
      expect(IllegalArgumentException.class, () -> new GpuSceneDescriptorResources(1L, 2L, 3L, 4L, 5L, 6L, 7L, views(), reconstructionViews(), BufferRange.whole(8L, 192L), duplicate));
      expect(IllegalArgumentException.class, () -> resources(1L, 2L, 3L, 3L, 5L, 6L, 7L, complete));
      expect(IllegalArgumentException.class, () -> resources(1L, 2L, 3L, 4L, 5L, 5L, 7L, complete));
      expect(IllegalArgumentException.class, () -> new GpuSceneDescriptorResources.DenoisingImageViews(8L, 0L, 10L, 11L, 12L, 13L, 14L, 15L, 16L));
      expect(IllegalArgumentException.class, () -> new GpuSceneDescriptorResources.BufferRange(4L, 4L, 16L, 19L));
      expect(IllegalArgumentException.class, () -> new GpuSceneDescriptorResources.BufferRange(4L, 0L, 20L, 16L));
      System.out.println("GpuSceneDescriptorResourcesSelfTest passed");
   }

   private static GpuSceneDescriptorResources resources(long tlas, long output, long colorInput, long colorOutput, long geometryInput, long geometryOutput, long motionOutput, List<GpuSceneDescriptorResources.StorageBinding> bindings) {
      return new GpuSceneDescriptorResources(tlas, output, colorInput, colorOutput, geometryInput, geometryOutput, motionOutput, views(), reconstructionViews(), BufferRange.whole(8L, 192L), bindings);
   }

   private static GpuSceneDescriptorResources.DenoisingImageViews views() {
      return new GpuSceneDescriptorResources.DenoisingImageViews(8L, 9L, 10L, 11L, 12L, 13L, 14L, 15L, 16L);
   }

   private static GpuSceneDescriptorResources.ReconstructionImageViews reconstructionViews() {
      return new GpuSceneDescriptorResources.ReconstructionImageViews(15L, 16L, 17L);
   }

   private static List<GpuSceneDescriptorResources.StorageBinding> completeBindings() {
      ArrayList<GpuSceneDescriptorResources.StorageBinding> bindings = new ArrayList<>();

      for(int binding = 3; binding <= 16; ++binding) {
         bindings.add(new GpuSceneDescriptorResources.StorageBinding(binding, BufferRange.whole(100L + (long)binding, 64L)));
      }

      return List.copyOf(bindings);
   }

   private static <T extends Throwable> void expect(Class<T> type, ThrowingRunnable action) {
      try {
         action.run();
      } catch (Throwable failure) {
         if (type.isInstance(failure)) {
            return;
         }

         throw new AssertionError("expected " + type.getName() + " but caught " + String.valueOf(failure), failure);
      }

      throw new AssertionError("expected " + type.getName() + " but no exception was thrown");
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }

   @FunctionalInterface
   private interface ThrowingRunnable {
      void run() throws Throwable;
   }
}
