package top.ceroxe.rt.renderer.rt.acceleration;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;

public final class RtAccelerationStructureBuildSupportSelfTest {
   private RtAccelerationStructureBuildSupportSelfTest() {
   }

   public static void main(String[] args) {
      testAlignment();
      testSizeArithmetic();
      testDirectBufferBoundary();
      testTriangleValidation();
      testVulkanBuildSizeValidation();
      System.out.println("RtAccelerationStructureBuildSupportSelfTest passed");
   }

   private static void testAlignment() {
      require(RtAccelerationStructureBuildSupport.alignUp(0L, 256) == 0L, "zero address alignment changed the value");
      require(RtAccelerationStructureBuildSupport.alignUp(256L, 256) == 256L, "already aligned address changed the value");
      require(RtAccelerationStructureBuildSupport.alignUp(257L, 256) == 512L, "unaligned address was rounded incorrectly");
      expectIllegalArgument(() -> RtAccelerationStructureBuildSupport.alignUp(-1L, 256));
      expectIllegalArgument(() -> RtAccelerationStructureBuildSupport.alignUp(1L, 0));
      expectIllegalArgument(() -> RtAccelerationStructureBuildSupport.alignUp(9223372036854775807L, 2));
   }

   private static void testSizeArithmetic() {
      require(RtAccelerationStructureBuildSupport.checkedAdd(7L, 5L) == 12L, "checked addition returned the wrong size");
      require(RtAccelerationStructureBuildSupport.checkedMultiply(7L, 5L) == 35L, "checked multiplication returned the wrong size");
      require(RtAccelerationStructureBuildSupport.checkedMultiply(0L, 9223372036854775807L) == 0L, "zero-sized multiplication returned a non-zero size");
      expectIllegalArgument(() -> RtAccelerationStructureBuildSupport.checkedAdd(9223372036854775807L, 1L));
      expectIllegalArgument(() -> RtAccelerationStructureBuildSupport.checkedMultiply(9223372036854775807L, 2L));
      expectIllegalArgument(() -> RtAccelerationStructureBuildSupport.checkedAdd(-1L, 1L));
      expectIllegalArgument(() -> RtAccelerationStructureBuildSupport.checkedMultiply(-1L, -1L));
   }

   private static void testDirectBufferBoundary() {
      require(RtAccelerationStructureBuildSupport.checkedByteBufferSize(1L, "upload") == 1, "minimum direct-buffer size was rejected");
      require(RtAccelerationStructureBuildSupport.checkedByteBufferSize(2147483647L, "upload") == 2147483647, "maximum direct-buffer size was rejected");
      expectIllegalArgument(() -> RtAccelerationStructureBuildSupport.checkedByteBufferSize(0L, "upload"));
      expectIllegalArgument(() -> RtAccelerationStructureBuildSupport.checkedByteBufferSize(2147483648L, "upload"));
      expectNullPointer(() -> RtAccelerationStructureBuildSupport.checkedByteBufferSize(1L, (String)null));
   }

   private static void testTriangleValidation() {
      require(RtAccelerationStructureBuildSupport.validateAndCountTriangles((int[])null, 3, "triangle") == 1, "single non-indexed triangle count was wrong");
      require(RtAccelerationStructureBuildSupport.validateAndCountTriangles(new int[]{0, 1, 2, 2, 3, 0}, 4, "quad") == 2, "indexed triangle count was wrong");
      expectIllegalArgument(() -> RtAccelerationStructureBuildSupport.validateAndCountTriangles((int[])null, 4, "mesh"));
      expectIllegalArgument(() -> RtAccelerationStructureBuildSupport.validateAndCountTriangles(new int[]{0, 1}, 3, "mesh"));
      expectIllegalArgument(() -> RtAccelerationStructureBuildSupport.validateAndCountTriangles(new int[]{0, 1, 3}, 3, "mesh"));
      expectIllegalArgument(() -> RtAccelerationStructureBuildSupport.validateAndCountTriangles(new int[]{0, -1, 2}, 3, "mesh"));
      expectIllegalArgument(() -> RtAccelerationStructureBuildSupport.validateAndCountTriangles(new int[]{0, 1, 2}, 0, "mesh"));
      expectNullPointer(() -> RtAccelerationStructureBuildSupport.validateAndCountTriangles(new int[]{0, 1, 2}, 3, (String)null));
   }

   private static void testVulkanBuildSizeValidation() {
      MemoryStack stack = MemoryStack.stackPush();

      try {
         VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
         setBuildSizes(sizes, 1024L, 512L);
         RtAccelerationStructureBuildSupport.validateBuildSizes(sizes);
         setBuildSizes(sizes, 0L, 512L);
         expectIllegalState(() -> RtAccelerationStructureBuildSupport.validateBuildSizes(sizes));
         setBuildSizes(sizes, 1024L, 0L);
         expectIllegalState(() -> RtAccelerationStructureBuildSupport.validateBuildSizes(sizes));
      } catch (Throwable value4) {
         if (stack != null) {
            try {
               stack.close();
            } catch (Throwable value3) {
               value4.addSuppressed(value3);
            }
         }

         throw value4;
      }

      if (stack != null) {
         stack.close();
      }

      expectNullPointer(() -> RtAccelerationStructureBuildSupport.validateBuildSizes((VkAccelerationStructureBuildSizesInfoKHR)null));
   }

   private static void setBuildSizes(VkAccelerationStructureBuildSizesInfoKHR sizes, long accelerationStructureBytes, long buildScratchBytes) {
      MemoryUtil.memPutLong(sizes.address() + (long)VkAccelerationStructureBuildSizesInfoKHR.ACCELERATIONSTRUCTURESIZE, accelerationStructureBytes);
      MemoryUtil.memPutLong(sizes.address() + (long)VkAccelerationStructureBuildSizesInfoKHR.BUILDSCRATCHSIZE, buildScratchBytes);
   }

   private static void expectIllegalArgument(Runnable action) {
      expectException(IllegalArgumentException.class, action);
   }

   private static void expectIllegalState(Runnable action) {
      expectException(IllegalStateException.class, action);
   }

   private static void expectNullPointer(Runnable action) {
      expectException(NullPointerException.class, action);
   }

   private static void expectException(Class<? extends RuntimeException> expectedType, Runnable action) {
      try {
         action.run();
         throw new AssertionError("expected " + expectedType.getSimpleName());
      } catch (RuntimeException failure) {
         if (!expectedType.isInstance(failure)) {
            throw new AssertionError("unexpected exception type", failure);
         }
      }
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}
