package top.ceroxe.rt.renderer.backend.vulkan;

import java.io.PrintStream;
import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.api.AffineTransform;
import top.ceroxe.rt.renderer.api.MaterialAsset;
import top.ceroxe.rt.renderer.api.MeshAsset;
import top.ceroxe.rt.renderer.api.SceneInstance;
import top.ceroxe.rt.renderer.api.SceneTransaction;
import top.ceroxe.rt.renderer.api.MaterialAsset.BlendMode;
import top.ceroxe.rt.renderer.api.MaterialAsset.ShadingModel;
import top.ceroxe.rt.renderer.api.SceneInstance.Mobility;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

public final class VulkanSceneAccelerationNativeSelfTest {
   private static final long TIMEOUT_NANOS = 10000000000L;

   private VulkanSceneAccelerationNativeSelfTest() {
   }

   public static void main(String[] arguments) throws Exception {
      VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
      require(capability.hardwareRayTracingReady(), "scene acceleration gate requires hardware RT: " + capability.summary());
      VulkanDeviceRuntime device = VulkanDeviceRuntime.open(capability);

      try {
         VulkanGpuScene gpuScene = new VulkanGpuScene(new VulkanGpuSceneBuffers(device.device(), device.allocator(), device.buildCommands()));

         try {
            VulkanSceneAcceleration acceleration = new VulkanSceneAcceleration(device, gpuScene);

            try {
               VulkanSceneResidency residency = new VulkanSceneResidency();
               VulkanSceneResidency.PreparedUpdate initial = residency.prepare(initialScene());
               activateGpuScene(gpuScene, initial.changeSet(), 0L);
               VulkanSceneAcceleration.Admission initialAdmission = acceleration.submit(initial.changeSet(), 0L);
               residency.commit(initial);
               VulkanSceneAcceleration.Snapshot first = awaitAcceleration(acceleration, 0L, 0L);
               require(initialAdmission.meshBuilds() == 1 && first.activeMeshes() == 1 && first.activeInstances() == 1 && first.tlasReady(), "initial acceleration generation did not publish one mesh and instance");
               long firstTlas = acceleration.requireActiveTlas(0L).handle();
               VulkanSceneResidency.PreparedUpdate moved = residency.prepare(instanceMove(1L));
               activateGpuScene(gpuScene, moved.changeSet(), 1L);
               VulkanSceneAcceleration.Admission movedAdmission = acceleration.submit(moved.changeSet(), 1L);
               residency.commit(moved);
               VulkanSceneAcceleration.Snapshot second = awaitAcceleration(acceleration, 1L, 0L);
               long secondTlas = acceleration.requireActiveTlas(1L).handle();
               require(movedAdmission.meshBuilds() == 0, "instance-only generation rebuilt an unchanged BLAS");
               require(second.activeMeshes() == 1 && second.activeInstances() == 1 && second.retiredGenerations() == 1 && secondTlas != firstTlas, "instance-only generation did not replace and retire its TLAS");
               VulkanSceneAcceleration.Snapshot released = acceleration.poll(1L);
               require(released.retiredGenerations() == 0, "completed descriptor epoch did not release the retired TLAS generation");
               VulkanSceneResidency.PreparedUpdate faded = residency.prepare(instanceFade(2L));
               activateGpuScene(gpuScene, faded.changeSet(), 2L);
               VulkanSceneAcceleration.Admission fadedAdmission = acceleration.submit(faded.changeSet(), 2L);
               residency.commit(faded);
               VulkanSceneAcceleration.Snapshot third = awaitAcceleration(acceleration, 2L, 1L);
               long thirdTlas = acceleration.requireActiveTlas(2L).handle();
               require(fadedAdmission.active() && fadedAdmission.meshBuilds() == 0, "appearance-only generation did not activate synchronously");
               require(thirdTlas == secondTlas && third.retiredGenerations() == 0, "appearance-only instance update rebuilt or retired an unchanged TLAS");
               PrintStream output10000 = System.out;
               String details10001 = capability.preferredDevice().name();
               output10000.println("VulkanSceneAccelerationNativeSelfTest passed: device=" + details10001 + ", activeRevision=" + third.activeRevision() + ", meshes=" + third.activeMeshes() + ", instances=" + third.activeInstances() + ", appearanceOnlyTlasReused=" + (thirdTlas == secondTlas));
            } catch (Throwable value25) {
               try {
                  acceleration.close();
               } catch (Throwable value24) {
                  value25.addSuppressed(value24);
               }

               throw value25;
            }

            acceleration.close();
         } catch (Throwable value26) {
            try {
               gpuScene.close();
            } catch (Throwable value23) {
               value26.addSuppressed(value23);
            }

            throw value26;
         }

         gpuScene.close();
      } catch (Throwable value27) {
         if (device != null) {
            try {
               device.close();
            } catch (Throwable value22) {
               value27.addSuppressed(value22);
            }
         }

         throw value27;
      }

      if (device != null) {
         device.close();
      }

   }

   private static void activateGpuScene(VulkanGpuScene gpuScene, VulkanSceneResidency.SceneChangeSet changes, long retireEpoch) throws Exception {
      gpuScene.submit(changes, retireEpoch);
      long deadline = System.nanoTime() + 10000000000L;

      while(gpuScene.poll(Math.max(0L, retireEpoch - 1L)).activeRevision() != changes.revision()) {
         Thread.sleep(1L);
         if (System.nanoTime() >= deadline) {
            throw new AssertionError("GPUScene generation did not activate: " + changes.revision());
         }
      }

   }

   private static VulkanSceneAcceleration.Snapshot awaitAcceleration(VulkanSceneAcceleration acceleration, long revision, long completedEpoch) throws Exception {
      long deadline = System.nanoTime() + 10000000000L;

      VulkanSceneAcceleration.Snapshot state;
      do {
         state = acceleration.poll(completedEpoch);
         if (state.activeRevision() == revision) {
            return state;
         }

         Thread.sleep(1L);
      } while(System.nanoTime() < deadline);

      throw new AssertionError("acceleration generation did not activate: " + String.valueOf(state));
   }

   private static SceneTransaction initialScene() {
      return SceneTransaction.builder(0L).resetScene().upsert(material()).upsert(mesh()).upsert(instance(AffineTransform.identity())).build();
   }

   private static SceneTransaction instanceMove(long revision) {
      AffineTransform moved = new AffineTransform(new float[]{1.0F, 0.0F, 0.0F, 4.0F, 0.0F, 1.0F, 0.0F, 2.0F, 0.0F, 0.0F, 1.0F, -3.0F});
      return SceneTransaction.builder(revision).upsert(instance(moved)).build();
   }

   private static SceneTransaction instanceFade(long revision) {
      return SceneTransaction.builder(revision).upsert(SceneInstance.builder(40L, 30L).transform(new AffineTransform(new float[]{1.0F, 0.0F, 0.0F, 4.0F, 0.0F, 1.0F, 0.0F, 2.0F, 0.0F, 0.0F, 1.0F, -3.0F})).mobility(Mobility.DYNAMIC).surfaceVisibility(0.5F).build()).build();
   }

   private static MaterialAsset material() {
      return MaterialAsset.builder(20L).blendMode(BlendMode.OPAQUE).baseColorRgba8(-4161472).emissive(-16777216, 1.0F).alphaCutoff(0.0F).roughness(0.65F).metallic(0.0F).transmission(0.0F).indexOfRefraction(1.5F).doubleSided(true).shadingModel(ShadingModel.PHYSICALLY_BASED).build();
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
