package top.ceroxe.rt.renderer.rt.pipeline;

public final class RtShaderBindingTableSelfTest {
   private RtShaderBindingTableSelfTest() {
   }

   public static void main(String[] args) {
      computesShaderBindingTableLayoutWithBaseAlignment();
      packsTwoDistinctCallableRecordsAfterVisibilityGroups();
      System.out.println("RtShaderBindingTableSelfTest passed");
   }

   private static void packsTwoDistinctCallableRecordsAfterVisibilityGroups() {
      var properties = new RtRayTracingPipelineProperties(24, 32, 64, 4096, 2);
      byte[] handles = new byte[7 * 24];
      for (int group = 0; group < 7; group++)
         java.util.Arrays.fill(handles, group * 24, (group + 1) * 24, (byte)(group + 1));
      var table = properties.packShaderGroupHandles(handles, 1, 2, 2, 2);
      var region = table.layout().callable();
      require(region.offsetBytes() == 192 && region.sizeBytes() == 64,
              "both callable records must follow raygen, two miss and two hit groups");
      byte[] bytes = table.bytes();
      require(bytes.length == 256, "two callable records must fit the complete aligned table");
      for (int i = 0; i < 24; i++) {
         require(bytes[192 + i] == 6, "callable index zero must select material");
         require(bytes[224 + i] == 7, "callable index one must select gather");
      }
      for (int i = 24; i < 32; i++)
         require(bytes[192 + i] == 0 && bytes[224 + i] == 0, "callable stride padding must remain zero");
   }

   private static void computesShaderBindingTableLayoutWithBaseAlignment() {
      RtRayTracingPipelineProperties properties = new RtRayTracingPipelineProperties(32, 32, 64, 4096, 2);
      RtRayTracingPipelineProperties.ShaderBindingTableLayout layout = properties.shaderBindingTableLayout(1, 2, 3, 0);
      require(layout.strideBytes() == 32, "SBT stride should align shader group handle size");
      require(layout.raygen().offsetBytes() == 0, "raygen region should start at the SBT base");
      require(layout.raygen().sizeBytes() == 32, "raygen region size mismatch");
      require(layout.miss().offsetBytes() == 64, "miss region must respect base alignment");
      require(layout.miss().sizeBytes() == 64, "miss region size mismatch");
      require(layout.hit().offsetBytes() == 128, "hit region must respect base alignment");
      require(layout.hit().sizeBytes() == 96, "hit region size mismatch");
      require(layout.callable().offsetBytes() == 256, "empty callable region should still expose an aligned offset");
      require(layout.callable().sizeBytes() == 0, "empty callable region should be zero sized");
      require(layout.totalBytes() == 256, "SBT total size should be aligned to base alignment");
      require(layout.baseAlignmentBytes() == 64, "SBT layout should retain base alignment for runtime address padding");
      byte[] handles = new byte[192];

      for(int group = 0; group < 6; ++group) {
         handles[group * 32] = (byte)(group + 1);
      }

      RtRayTracingPipelineProperties.ShaderBindingTableData table = properties.packShaderGroupHandles(handles, 1, 2, 3, 0);
      byte[] bytes = table.bytes();
      require(bytes.length == 256, "packed SBT byte length mismatch");
      require(bytes[0] == 1, "raygen handle should be copied to raygen region");
      require(bytes[32] == 0, "raygen padding should stay zeroed");
      require(bytes[64] == 2, "first miss handle should be copied to miss region");
      require(bytes[96] == 3, "second miss handle should be copied to miss region");
      require(bytes[128] == 4, "first hit handle should be copied to hit region");
      require(bytes[160] == 5, "second hit handle should be copied to hit region");
      require(bytes[192] == 6, "third hit handle should be copied to hit region");
      bytes[0] = 99;
      require(table.bytes()[0] == 1, "SBT data accessor must not expose internal byte array");
      RuntimeException failure = expectFailure(() -> properties.shaderBindingTableLayout(0, 1, 1, 0));
      require(failure instanceof IllegalArgumentException, "SBT layout should reject missing raygen group");
      RuntimeException lengthFailure = expectFailure(() -> properties.packShaderGroupHandles(new byte[31], 1, 0, 0, 0));
      require(lengthFailure instanceof IllegalArgumentException, "SBT packer should reject wrong handle byte count");
   }

   private static RuntimeException expectFailure(Runnable runnable) {
      try {
         runnable.run();
      } catch (RuntimeException exception) {
         return exception;
      }

      throw new AssertionError("expected failure did not occur");
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}
