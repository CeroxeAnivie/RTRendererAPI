package top.ceroxe.rt.renderer.rt.acceleration;

public final class RtAccelerationStructureCommandRecorderSelfTest {
   private RtAccelerationStructureCommandRecorderSelfTest() {
   }

   public static void main(String[] args) {
      testMaximumUpdateElements();
      testPayloadAlignment();
      testDestinationOffsetAlignment();
      System.out.println("RtAccelerationStructureCommandRecorderSelfTest passed");
   }

   private static void testMaximumUpdateElements() {
      require(RtAccelerationStructureCommandRecorder.maximumElementsPerUpdate(4) == 16384, "float update capacity violated Vulkan's 64 KiB limit");
      require(RtAccelerationStructureCommandRecorder.maximumElementsPerUpdate(64) == 1024, "TLAS instance update capacity was calculated incorrectly");
      require(RtAccelerationStructureCommandRecorder.maximumElementsPerUpdate(48) == 1365, "section-face update capacity was calculated incorrectly");
      require(RtAccelerationStructureCommandRecorder.maximumElementsPerUpdate(65536) == 1, "maximum legal update element was rejected");
      expectIllegalArgument(() -> RtAccelerationStructureCommandRecorder.maximumElementsPerUpdate(0));
      expectIllegalArgument(() -> RtAccelerationStructureCommandRecorder.maximumElementsPerUpdate(3));
      expectIllegalArgument(() -> RtAccelerationStructureCommandRecorder.maximumElementsPerUpdate(65540));
   }

   private static void testPayloadAlignment() {
      RtAccelerationStructureCommandRecorder.validatePayloadLayout(0, 64);
      RtAccelerationStructureCommandRecorder.validatePayloadLayout(128, 64);
      expectIllegalArgument(() -> RtAccelerationStructureCommandRecorder.validatePayloadLayout(-1, 64));
      expectIllegalArgument(() -> RtAccelerationStructureCommandRecorder.validatePayloadLayout(63, 64));
      expectIllegalArgument(() -> RtAccelerationStructureCommandRecorder.validatePayloadLayout(64, 6));
   }

   private static void testDestinationOffsetAlignment() {
      RtAccelerationStructureCommandRecorder.validateDestinationOffset(0L);
      RtAccelerationStructureCommandRecorder.validateDestinationOffset(65536L);
      expectIllegalArgument(() -> RtAccelerationStructureCommandRecorder.validateDestinationOffset(-4L));
      expectIllegalArgument(() -> RtAccelerationStructureCommandRecorder.validateDestinationOffset(2L));
   }

   private static void expectIllegalArgument(Runnable action) {
      try {
         action.run();
         throw new AssertionError("expected IllegalArgumentException");
      } catch (IllegalArgumentException value2) {
      }
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}
