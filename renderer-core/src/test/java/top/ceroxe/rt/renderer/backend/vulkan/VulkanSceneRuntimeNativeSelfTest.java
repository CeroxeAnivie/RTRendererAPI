package top.ceroxe.rt.renderer.backend.vulkan;

import java.io.PrintStream;
import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.api.AffineTransform;
import top.ceroxe.rt.renderer.api.MaterialAsset;
import top.ceroxe.rt.renderer.api.MeshAsset;
import top.ceroxe.rt.renderer.api.SceneInstance;
import top.ceroxe.rt.renderer.api.SceneTransaction;
import top.ceroxe.rt.renderer.api.MaterialAsset.BlendMode;
import top.ceroxe.rt.renderer.api.MaterialAsset.ShadingModel;
import top.ceroxe.rt.renderer.api.SceneInstance.Mobility;

public final class VulkanSceneRuntimeNativeSelfTest {
   private static final long TIMEOUT_NANOS = 10000000000L;

   private VulkanSceneRuntimeNativeSelfTest() {
   }

   public static void main(String[] arguments) throws Exception {
      VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
      require(capability.hardwareRayTracingReady(), "scene runtime gate requires hardware RT: " + capability.summary());
      VulkanSceneRuntime runtime = VulkanSceneRuntime.open(capability, RendererRtDiagnostics.noop());

      try {
         VulkanSceneResidency residency = new VulkanSceneResidency();
         VulkanSceneResidency.PreparedUpdate initial = residency.prepare(initialScene());
         VulkanSceneRuntime.Admission admitted = runtime.apply(initial.changeSet(), 0L);
         residency.commit(initial);
         require(admitted.revision() == 0L && admitted.uploadBytes() > 0L, "scene runtime did not admit real GPU work");
         VulkanSceneResidency.PreparedUpdate premature = residency.prepare(moveInstance(1L));
         boolean busy = false;
         VulkanSceneRuntime.Admission moved = null;

         try {
            moved = runtime.apply(premature.changeSet(), 1L);
         } catch (VulkanSceneRuntime.BusyException value12) {
            busy = true;
         }

         if (busy) {
            VulkanSceneRuntime.Snapshot active = awaitActive(runtime, 0L);
            require(active.gpuScene().activeRevision() == 0L && active.acceleration().activeRevision() == 0L, "scene runtime exposed mismatched GPUScene and AS generations");
            moved = runtime.apply(premature.changeSet(), 1L);
         }

         require(moved != null, "successor scene generation was neither accepted nor backpressured");
         residency.commit(premature);
         VulkanSceneRuntime.Snapshot successor = awaitActive(runtime, 1L);
         require(moved.revision() == 1L && successor.activeRevision() == 1L && successor.acceleration().retiredGenerations() == 1, "successor scene generation did not converge or retire its predecessor");
         VulkanSceneRuntime.Snapshot retired = runtime.poll(1L);
         require(retired.acceleration().retiredGenerations() == 0, "real descriptor completion epoch did not release retired acceleration");
         PrintStream output10000 = System.out;
         String details10001 = capability.preferredDevice().name();
         output10000.println("VulkanSceneRuntimeNativeSelfTest passed: device=" + details10001 + ", accepted=" + retired.acceptedRevision() + ", active=" + retired.activeRevision());
      } catch (Throwable value13) {
         if (runtime != null) {
            try {
               runtime.close();
            } catch (Throwable value11) {
               value13.addSuppressed(value11);
            }
         }

         throw value13;
      }

      if (runtime != null) {
         runtime.close();
      }

   }

   private static VulkanSceneRuntime.Snapshot awaitActive(VulkanSceneRuntime runtime, long revision) throws Exception {
      long deadline = System.nanoTime() + 10000000000L;

      VulkanSceneRuntime.Snapshot snapshot;
      do {
         snapshot = runtime.snapshot();
         if (snapshot.activeRevision() == revision) {
            return snapshot;
         }

         runtime.pollCompletion();
         Thread.sleep(1L);
      } while(System.nanoTime() < deadline);

      throw new AssertionError("scene runtime did not converge: " + String.valueOf(snapshot));
   }

   private static SceneTransaction initialScene() {
      return SceneTransaction.builder(0L).resetScene().upsert(material()).upsert(mesh()).upsert(instance(AffineTransform.identity())).build();
   }

   private static SceneTransaction moveInstance(long revision) {
      return SceneTransaction.builder(revision).upsert(instance(new AffineTransform(new float[]{1.0F, 0.0F, 0.0F, 3.0F, 0.0F, 1.0F, 0.0F, 2.0F, 0.0F, 0.0F, 1.0F, -1.0F}))).build();
   }

   private static MaterialAsset material() {
      return MaterialAsset.builder(20L).blendMode(BlendMode.OPAQUE).baseColorRgba8(-8339392).emissive(-16777216, 1.0F).alphaCutoff(0.0F).roughness(0.55F).metallic(0.0F).transmission(0.0F).indexOfRefraction(1.5F).doubleSided(true).shadingModel(ShadingModel.PHYSICALLY_BASED).build();
   }

   private static MeshAsset mesh() {
      return MeshAsset.builder(30L, new float[]{-1.0F, -1.0F, 0.0F, 1.0F, -1.0F, 0.0F, 0.0F, 1.0F, 0.0F}, new int[]{0, 1, 2}, new long[]{20L}).normals(new float[]{0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F}).build();
   }

   private static SceneInstance instance(AffineTransform transform) {
      return SceneInstance.builder(40L, 30L).transform(transform).mobility(Mobility.DYNAMIC).build();
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}
