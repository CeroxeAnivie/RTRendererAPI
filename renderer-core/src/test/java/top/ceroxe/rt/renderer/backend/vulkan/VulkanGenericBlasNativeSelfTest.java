package top.ceroxe.rt.renderer.backend.vulkan;

import java.io.PrintStream;
import java.util.List;
import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.api.AffineTransform;
import top.ceroxe.rt.renderer.api.MaterialAsset;
import top.ceroxe.rt.renderer.api.MeshAsset;
import top.ceroxe.rt.renderer.api.SceneTransaction;
import top.ceroxe.rt.renderer.api.MaterialAsset.BlendMode;
import top.ceroxe.rt.renderer.api.MaterialAsset.ShadingModel;
import top.ceroxe.rt.renderer.backend.vulkan.VulkanGpuSceneUploadPlanner.Target;
import top.ceroxe.rt.renderer.rt.acceleration.RtAccelerationStructure;
import top.ceroxe.rt.renderer.rt.acceleration.RtDeviceTlasBuilder;
import top.ceroxe.rt.renderer.rt.acceleration.RtDeviceTriangleBlasBuilder;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

public final class VulkanGenericBlasNativeSelfTest {
   private static final long COMPLETION_TIMEOUT_NANOS = 10000000000L;

   private VulkanGenericBlasNativeSelfTest() {
   }

   public static void main(String[] arguments) throws Exception {
      VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
      require(capability.hardwareRayTracingReady(), "generic BLAS gate requires hardware RT: " + capability.summary());
      VulkanDeviceRuntime device = VulkanDeviceRuntime.open(capability);

      try {
         VulkanGpuScene scene = new VulkanGpuScene(new VulkanGpuSceneBuffers(device.device(), device.allocator(), device.buildCommands()));

         try {
            VulkanSceneResidency residency = new VulkanSceneResidency();
            VulkanSceneResidency.PreparedUpdate prepared = residency.prepare(initialScene());
            VulkanGpuScene.Admission admission = scene.submit(prepared.changeSet(), 0L);
            residency.commit(prepared);
            awaitActive(scene, admission.acceptedRevision());
            VulkanGpuSceneTransferQueue.BufferBinding positions = scene.requireBuffer(Target.POSITIONS, 0L);
            VulkanGpuSceneTransferQueue.BufferBinding indices = scene.requireBuffer(Target.INDICES, 0L);
            RtDeviceTriangleBlasBuilder.Geometry geometry = new RtDeviceTriangleBlasBuilder.Geometry(positions.deviceAddress(), indices.deviceAddress(), 3, 1, true);
            RtDeviceTriangleBlasBuilder.PendingBuild pending = RtDeviceTriangleBlasBuilder.submit(device.device(), device.allocator(), device.buildCommands(), device.accelerationStructureScratchAlignment(), List.of(geometry));

            try {
               RtAccelerationStructure blas = awaitBlas(pending);

               try {
                  require(blas.handle() != 0L && blas.deviceAddress() != 0L, "completed generic BLAS has invalid Vulkan handles");
                  require(pending.geometryCount() == 1 && pending.primitiveCount() == 1L, "generic BLAS submission statistics diverged");
                  require(pending.completedStorageBytes() > 0L && pending.completedStorageBytes() <= pending.sourceStorageBytes(), "BLAS compaction increased or lost its storage allocation");
                  RtDeviceTlasBuilder.CompletedBuild completedTlas = buildTlas(device, blas);
                  RtAccelerationStructure tlas = completedTlas.accelerationStructure();

                  try {
                     require(tlas.handle() != 0L && tlas.deviceAddress() != 0L && completedTlas.instanceCount() == 3, "completed generic TLAS has invalid native state");
                     PrintStream output10000 = System.out;
                     String details10001 = capability.preferredDevice().name();
                     output10000.println("VulkanGenericBlasNativeSelfTest passed: device=" + details10001 + ", blas=0x" + Long.toHexString(blas.handle()) + ", blasAddress=0x" + Long.toHexString(blas.deviceAddress()) + ", compacted=" + pending.compacted() + ", storageBytes=" + pending.sourceStorageBytes() + "->" + pending.completedStorageBytes() + ", tlas=0x" + Long.toHexString(tlas.handle()) + ", tlasAddress=0x" + Long.toHexString(tlas.deviceAddress()));
                  } catch (Throwable value21) {
                     if (tlas != null) {
                        try {
                           tlas.close();
                        } catch (Throwable value20) {
                           value21.addSuppressed(value20);
                        }
                     }

                     throw value21;
                  }

                  if (tlas != null) {
                     tlas.close();
                  }
               } catch (Throwable value22) {
                  if (blas != null) {
                     try {
                        blas.close();
                     } catch (Throwable value19) {
                        value22.addSuppressed(value19);
                     }
                  }

                  throw value22;
               }

               if (blas != null) {
                  blas.close();
               }
            } catch (Throwable value23) {
               if (pending != null) {
                  try {
                     pending.close();
                  } catch (Throwable value18) {
                     value23.addSuppressed(value18);
                  }
               }

               throw value23;
            }

            if (pending != null) {
               pending.close();
            }
         } catch (Throwable value24) {
            try {
               scene.close();
            } catch (Throwable value17) {
               value24.addSuppressed(value17);
            }

            throw value24;
         }

         scene.close();
      } catch (Throwable value25) {
         if (device != null) {
            try {
               device.close();
            } catch (Throwable value16) {
               value25.addSuppressed(value16);
            }
         }

         throw value25;
      }

      if (device != null) {
         device.close();
      }

   }

   private static void awaitActive(VulkanGpuScene scene, long revision) throws InterruptedException {
      long deadline = System.nanoTime() + 10000000000L;

      while(scene.poll(0L).activeRevision() < revision) {
         Thread.sleep(1L);
         if (System.nanoTime() >= deadline) {
            throw new AssertionError("GPUScene geometry transfer did not complete before BLAS build");
         }
      }

   }

   private static RtAccelerationStructure awaitBlas(RtDeviceTriangleBlasBuilder.PendingBuild pending) throws InterruptedException {
      long deadline = System.nanoTime() + 10000000000L;

      do {
         RtAccelerationStructure completed = pending.completeIfReady();
         if (completed != null) {
            return completed;
         }

         Thread.sleep(1L);
      } while(System.nanoTime() < deadline);

      throw new AssertionError("generic device-address BLAS did not complete");
   }

   private static RtDeviceTlasBuilder.CompletedBuild buildTlas(VulkanDeviceRuntime device, RtAccelerationStructure blas) throws InterruptedException {
      List<RtDeviceTlasBuilder.Instance> instances = List.of(instance(blas, -2.0F), instance(blas, 0.0F), instance(blas, 2.0F));
      RtDeviceTlasBuilder.PendingBuild pending = RtDeviceTlasBuilder.submit(device.device(), device.allocator(), device.buildCommands(), device.accelerationStructureScratchAlignment(), instances);

      RtDeviceTlasBuilder.CompletedBuild completedBuild7;
      try {
         long deadline = System.nanoTime() + 10000000000L;

         while(true) {
            RtDeviceTlasBuilder.CompletedBuild completed = pending.completeIfReady();
            if (completed != null) {
               completedBuild7 = completed;
               break;
            }

            Thread.sleep(1L);
            if (System.nanoTime() >= deadline) {
               throw new AssertionError("generic affine-instance TLAS did not complete");
            }
         }
      } catch (Throwable value9) {
         if (pending != null) {
            try {
               pending.close();
            } catch (Throwable value8) {
               value9.addSuppressed(value8);
            }
         }

         throw value9;
      }

      if (pending != null) {
         pending.close();
      }

      return completedBuild7;
   }

   private static RtDeviceTlasBuilder.Instance instance(RtAccelerationStructure blas, float x) {
      return new RtDeviceTlasBuilder.Instance(blas.deviceAddress(), new AffineTransform(new float[]{1.0F, 0.0F, 0.0F, x, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F}), (int)(x + 2.0F), 255);
   }

   private static SceneTransaction initialScene() {
      MaterialAsset material = MaterialAsset.builder(20L).blendMode(BlendMode.OPAQUE).baseColorRgba8(-4161472).emissive(-16777216, 1.0F).alphaCutoff(0.0F).roughness(0.65F).metallic(0.0F).transmission(0.0F).indexOfRefraction(1.5F).doubleSided(true).shadingModel(ShadingModel.PHYSICALLY_BASED).build();
      MeshAsset mesh = MeshAsset.builder(30L, new float[]{-1.0F, -1.0F, 0.0F, 1.0F, -1.0F, 0.0F, 0.0F, 1.0F, 0.0F}, new int[]{0, 1, 2}, new long[]{20L}).normals(new float[]{0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F}).build();
      return SceneTransaction.builder(0L).resetScene().upsert(material).upsert(mesh).build();
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}
