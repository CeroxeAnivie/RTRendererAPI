package top.ceroxe.rt.renderer.rt.acceleration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkAccelerationStructureInstanceKHR;
import top.ceroxe.rt.renderer.rt.acceleration.RtAccelerationStructure.TlasInstance;

public final class RtTlasInstanceEncoderSelfTest {
   private RtTlasInstanceEncoderSelfTest() {
   }

   public static void main(String[] args) {
      testDirtySlotValidation();
      testCollectionFreezeBoundary();
      testVulkanAbiEncoding();
      System.out.println("RtTlasInstanceEncoderSelfTest passed");
   }

   private static void testDirtySlotValidation() {
      RtTlasInstanceEncoder.validateDirtySlots(new int[]{0, 2, 4}, 5);
      expectIllegalArgument(() -> RtTlasInstanceEncoder.validateDirtySlots(new int[]{1, 1}, 2));
      expectIllegalArgument(() -> RtTlasInstanceEncoder.validateDirtySlots(new int[]{2}, 2));
      expectIllegalArgument(() -> RtTlasInstanceEncoder.validateDirtySlots(new int[0], 0));
      expectNullPointer(() -> RtTlasInstanceEncoder.validateDirtySlots((int[])null, 1));
   }

   private static void testCollectionFreezeBoundary() {
      RtAccelerationStructure.TlasInstance instance = TlasInstance.identity(1L);
      ArrayList<RtAccelerationStructure.TlasInstance> mutable = new ArrayList<>();
      mutable.add(instance);
      List<RtAccelerationStructure.TlasInstance> frozen = RtTlasInstanceEncoder.freeze(mutable);
      mutable.clear();
      require(frozen.size() == 1 && frozen.getFirst() == instance, "external TLAS collection was retained instead of copied");
      expectNullPointer(() -> RtTlasInstanceEncoder.freeze(null));
   }

   private static void testVulkanAbiEncoding() {
      RtAccelerationStructure.TlasInstance instance = new RtAccelerationStructure.TlasInstance(4660L, 1.0F, 2.0F, 3.0F, 4.0F, 5.0F, 6.0F, 7.0F, 8.0F, 9.0F, 10.0F, 11.0F, 12.0F, 43981, 127);
      MemoryStack stack = MemoryStack.stackPush();

      try {
         VkAccelerationStructureInstanceKHR target = VkAccelerationStructureInstanceKHR.calloc(stack);
         RtTlasInstanceEncoder.write(target, instance);

         for(int index = 0; index < 12; ++index) {
            require(target.transform().matrix(index) == (float)index + 1.0F, "transform lane was encoded out of order: " + index);
         }

         require(target.instanceCustomIndex() == 43981, "custom index was not encoded");
         require(target.mask() == 127, "visibility mask was not encoded");
         require(target.instanceShaderBindingTableRecordOffset() == 0, "unexpected shader binding table record offset");
         require(target.flags() == 1, "instance flags were not encoded");
         require(target.accelerationStructureReference() == 4660L, "BLAS device address was not encoded");
      } catch (Throwable value5) {
         if (stack != null) {
            try {
               stack.close();
            } catch (Throwable value4) {
               value5.addSuppressed(value4);
            }
         }

         throw value5;
      }

      if (stack != null) {
         stack.close();
      }

   }

   private static void expectIllegalArgument(Runnable action) {
      try {
         action.run();
         throw new AssertionError("expected IllegalArgumentException");
      } catch (IllegalArgumentException value2) {
      }
   }

   private static void expectNullPointer(Runnable action) {
      try {
         action.run();
         throw new AssertionError("expected NullPointerException");
      } catch (NullPointerException value2) {
      }
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}
