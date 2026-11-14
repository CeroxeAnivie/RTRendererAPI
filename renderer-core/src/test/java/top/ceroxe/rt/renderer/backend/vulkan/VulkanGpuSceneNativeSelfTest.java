package top.ceroxe.rt.renderer.backend.vulkan;

import java.io.PrintStream;
import java.util.Arrays;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryBarrier;
import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.api.SceneTransaction;
import top.ceroxe.rt.renderer.api.TextureAsset;
import top.ceroxe.rt.renderer.api.TextureAsset.AddressMode;
import top.ceroxe.rt.renderer.api.TextureAsset.ColorSpace;
import top.ceroxe.rt.renderer.api.TextureAsset.Filter;
import top.ceroxe.rt.renderer.backend.vulkan.VulkanGpuSceneUploadPlanner.Target;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

public final class VulkanGpuSceneNativeSelfTest {
   private static final byte[] EXPECTED_PIXEL = new byte[]{1, 2, 3, 4};
   private static final long COMPLETION_TIMEOUT_NANOS = 10000000000L;

   private VulkanGpuSceneNativeSelfTest() {
   }

   public static void main(String[] arguments) throws Exception {
      VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
      require(capability.hardwareRayTracingReady(), "native GPUScene gate requires hardware RT: " + capability.summary());
      VulkanDeviceRuntime device = VulkanDeviceRuntime.open(capability);

      try {
         VulkanGpuScene scene = new VulkanGpuScene(new VulkanGpuSceneBuffers(device.device(), device.allocator(), device.buildCommands()));

         try {
            VulkanSceneResidency residency = new VulkanSceneResidency();
            VulkanSceneResidency.PreparedUpdate prepared = residency.prepare(initialScene());
            VulkanGpuScene.Admission admission = scene.submit(prepared.changeSet(), 0L);
            residency.commit(prepared);
            VulkanGpuScene.Snapshot active = awaitActive(scene, admission.acceptedRevision());

            for(VulkanGpuSceneUploadPlanner.Target target : Target.values()) {
               VulkanGpuSceneTransferQueue.BufferBinding binding = scene.requireBuffer(target, 0L);
               require(binding.capacityBytes() >= 4096L, "descriptor target did not retain bootstrap capacity: " + String.valueOf(target));
            }

            VulkanGpuSceneTransferQueue.BufferBinding texturePixels = scene.requireBuffer(Target.TEXTURE_PIXELS, 0L);
            byte[] uploaded = readDeviceBytes(device, texturePixels.buffer(), EXPECTED_PIXEL.length);
            boolean condition10000 = Arrays.equals(uploaded, EXPECTED_PIXEL);
            String details10001 = Arrays.toString(uploaded);
            require(condition10000, "device-local texture upload differs: " + details10001);
            require(active.transfers().activeBuffers() == Target.values().length, "not every GPUScene descriptor target became active");
            PrintStream output19 = System.out;
            details10001 = capability.preferredDevice().name();
            output19.println("VulkanGpuSceneNativeSelfTest passed: device=" + details10001 + ", activeRevision=" + active.activeRevision() + ", buffers=" + active.transfers().activeBuffers() + ", bytes=" + active.transfers().activeBytes());
         } catch (Throwable value15) {
            try {
               scene.close();
            } catch (Throwable value14) {
               value15.addSuppressed(value14);
            }

            throw value15;
         }

         scene.close();
      } catch (Throwable value16) {
         if (device != null) {
            try {
               device.close();
            } catch (Throwable value13) {
               value16.addSuppressed(value13);
            }
         }

         throw value16;
      }

      if (device != null) {
         device.close();
      }

   }

   private static VulkanGpuScene.Snapshot awaitActive(VulkanGpuScene scene, long revision) throws InterruptedException {
      long deadline = System.nanoTime() + 10000000000L;

      VulkanGpuScene.Snapshot snapshot;
      do {
         snapshot = scene.poll(0L);
         if (snapshot.activeRevision() >= revision) {
            return snapshot;
         }

         Thread.sleep(1L);
      } while(System.nanoTime() < deadline);

      throw new AssertionError("GPUScene transfer did not complete: " + String.valueOf(snapshot));
   }

   private static byte[] readDeviceBytes(VulkanDeviceRuntime device, long sourceBuffer, int byteCount) {
      RtGpuBuffer readback = RtGpuBuffer.createHostVisibleBuffer(device.device(), device.allocator(), (long)byteCount, 2);

      byte[] byteValues5;
      try {
         device.buildCommands().submitOneTime((commandBuffer, stack) -> {
            VkMemoryBarrier.Buffer sourceReady = VkMemoryBarrier.calloc(1, stack);
            ((VkMemoryBarrier)sourceReady.get(0)).sType$Default().srcAccessMask(4128).dstAccessMask(2048);
            VK10.vkCmdPipelineBarrier(commandBuffer, 65536, 4096, 0, sourceReady, (VkBufferMemoryBarrier.Buffer)null, (VkImageMemoryBarrier.Buffer)null);
            VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1, stack);
            ((VkBufferCopy)copy.get(0)).srcOffset(0L).dstOffset(0L).size((long)byteCount);
            VK10.vkCmdCopyBuffer(commandBuffer, sourceBuffer, readback.buffer(), copy);
            VkMemoryBarrier.Buffer hostReady = VkMemoryBarrier.calloc(1, stack);
            ((VkMemoryBarrier)hostReady.get(0)).sType$Default().srcAccessMask(4096).dstAccessMask(8192);
            VK10.vkCmdPipelineBarrier(commandBuffer, 4096, 16384, 0, hostReady, (VkBufferMemoryBarrier.Buffer)null, (VkImageMemoryBarrier.Buffer)null);
         });
         byteValues5 = readback.readBytes((long)byteCount);
      } catch (Throwable value8) {
         if (readback != null) {
            try {
               readback.close();
            } catch (Throwable value7) {
               value8.addSuppressed(value7);
            }
         }

         throw value8;
      }

      if (readback != null) {
         readback.close();
      }

      return byteValues5;
   }

   private static SceneTransaction initialScene() {
      TextureAsset texture = TextureAsset.builder(10L, 1, 1).colorSpace(ColorSpace.SRGB).addressModes(AddressMode.REPEAT, AddressMode.REPEAT).filter(Filter.NEAREST).pixelsRgba8(EXPECTED_PIXEL).build();
      return SceneTransaction.builder(0L).resetScene().upsert(texture).build();
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}
