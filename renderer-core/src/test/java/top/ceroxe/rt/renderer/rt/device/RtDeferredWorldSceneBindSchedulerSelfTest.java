package top.ceroxe.rt.renderer.rt.device;

import top.ceroxe.rt.renderer.rt.device.RtDeferredWorldSceneBindScheduler.Action;

public final class RtDeferredWorldSceneBindSchedulerSelfTest {
   private RtDeferredWorldSceneBindSchedulerSelfTest() {
   }

   public static void main(String[] args) {
      transformRefitOnlyWaitsForAnotherDescriptorTransaction();
      fullWorldUpdateRequiresWritableDescriptors();
      fullWorldUpdateRetainsOnlyWhenConvergencePolicyRequestsIt();
      System.out.println("RtDeferredWorldSceneBindSchedulerSelfTest passed");
   }

   private static void transformRefitOnlyWaitsForAnotherDescriptorTransaction() {
      require(select(true, false, false, true) == Action.BIND_TRANSFORM_ONLY, "material-compatible transform refits must not wait for material descriptor availability");
      require(select(true, true, true, false) == Action.DESCRIPTOR_DEFERRED, "transform refits must not overlap another descriptor transaction");
   }

   private static void fullWorldUpdateRequiresWritableDescriptors() {
      require(select(false, false, false, false) == Action.DESCRIPTOR_DEFERRED, "full world binds require writable descriptors");
      require(select(false, true, true, false) == Action.DESCRIPTOR_DEFERRED, "full world binds must not overlap another descriptor transaction");
   }

   private static void fullWorldUpdateRetainsOnlyWhenConvergencePolicyRequestsIt() {
      require(select(false, true, false, true) == Action.RETAIN_DEFERRED, "cadence or backlog pressure must retain the deferred transaction");
      require(select(false, true, false, false) == Action.SUBMIT_MATERIAL_UPLOAD, "a writable non-deferred full world bind must submit its material upload");
   }

   private static RtDeferredWorldSceneBindScheduler.Action select(boolean transformOnly, boolean descriptorsCanBeUpdated, boolean descriptorTransactionPresent, boolean deferMaterialUpload) {
      return RtDeferredWorldSceneBindScheduler.selectAction(transformOnly, descriptorsCanBeUpdated, descriptorTransactionPresent, deferMaterialUpload);
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}
