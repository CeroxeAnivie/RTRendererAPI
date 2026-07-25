package top.ceroxe.rt.renderer.rt.device;

public final class RtDynamicSceneDispatchPolicySelfTest {
   private RtDynamicSceneDispatchPolicySelfTest() {
   }

   public static void main(String[] args) {
      rejectsInPlaceDescriptorMutation();
      preservesCommittedFrontUntilDynamicInstancesExist();
      keepsAnalyticSsboUpdatesFrameLocal();
      protectsTheFirstInteractiveFront();
      System.out.println("RtDynamicSceneDispatchPolicySelfTest passed");
   }

   private static void rejectsInPlaceDescriptorMutation() {
      require(!RtDynamicSceneDispatchPolicy.descriptorGenerationCanDispatch(true, false, true, true), "in-place world material uploads must block the old descriptor generation");
      require(RtDynamicSceneDispatchPolicy.descriptorGenerationCanDispatch(true, true, true, true), "copy-on-write uploads must preserve the currently bound descriptor generation");
      require(!RtDynamicSceneDispatchPolicy.descriptorGenerationCanDispatch(false, true, true, true), "no descriptor generation may dispatch without a bound world scene");
   }

   private static void preservesCommittedFrontUntilDynamicInstancesExist() {
      require("rtDynamicBuildPending".equals(RtDynamicSceneDispatchPolicy.committedFrontBlockReason(true, true, 0)), "dynamic geometry must wait for a matching dynamic instance in the world TLAS");
      require("ready".equals(RtDynamicSceneDispatchPolicy.committedFrontBlockReason(true, true, 1)), "a world TLAS with a dynamic instance may dispatch the committed front");
   }

   private static void keepsAnalyticSsboUpdatesFrameLocal() {
      require(RtDynamicSceneDispatchPolicy.shouldDispatchCurrentSsboScene(true, false, false), "analytic-only scenes should not wait for descriptor publication");
      require(!RtDynamicSceneDispatchPolicy.shouldDispatchCurrentSsboScene(true, true, false), "a published dynamic TLAS generation must remain the sole dispatch source");
   }

   private static void protectsTheFirstInteractiveFront() {
      require(RtDynamicSceneDispatchPolicy.shouldBlockForInteractiveWorldSceneBind(false, true), "an interactive transaction without any committed front must block dispatch");
      require(!RtDynamicSceneDispatchPolicy.shouldBlockForInteractiveWorldSceneBind(true, true), "a valid committed front remains renderable while its interactive successor converges");
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}
