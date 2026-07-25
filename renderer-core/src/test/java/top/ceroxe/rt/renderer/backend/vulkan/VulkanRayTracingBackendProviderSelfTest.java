package top.ceroxe.rt.renderer.backend.vulkan;

import java.util.ServiceLoader;
import org.lwjgl.vulkan.VK13;
import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.api.FrameOutputFormat;
import top.ceroxe.rt.renderer.api.RayTracingGpuDevice.Capability;
import top.ceroxe.rt.renderer.spi.RayTracingBackendProvider;

public final class VulkanRayTracingBackendProviderSelfTest {
   private VulkanRayTracingBackendProviderSelfTest() {
   }

   public static void main(String[] arguments) {
      RayTracingBackendProvider provider = (RayTracingBackendProvider)ServiceLoader.load(RayTracingBackendProvider.class).stream().map(ServiceLoader.Provider::get).filter((candidate) -> candidate.descriptor().id().equals("vulkan-rt")).findFirst().orElseThrow(() -> new AssertionError("vulkan-rt backend provider was not discovered"));
      RayTracingBackendProvider.Descriptor descriptor = provider.descriptor();
      if (descriptor.apiMajor() == 1 && descriptor.priority() > 0) {
         verifyPublicFrameCapabilityGate();
         System.out.println("VulkanRayTracingBackendProviderSelfTest passed: " + String.valueOf(descriptor));
      } else {
         throw new AssertionError("Vulkan backend descriptor is invalid: " + String.valueOf(descriptor));
      }
   }

   private static void verifyPublicFrameCapabilityGate() {
      if (!VulkanRayTracingBackendProvider.publicFrameApiReady(device(true, true))) {
         throw new AssertionError("fully capable external-frame device was rejected");
      } else if (VulkanRayTracingBackendProvider.publicFrameApiReady(device(true, false))) {
         throw new AssertionError("device without external frame memory was advertised as available");
      } else if (VulkanRayTracingBackendProvider.publicFrameApiReady(device(false, true))) {
         throw new AssertionError("external memory concealed missing hardware RT support");
      } else if (VulkanRayTracingBackendProvider.publicFrameApiReady(device(true, true, true, false), FrameOutputFormat.LINEAR_HDR_RGBA16F)) {
         throw new AssertionError("device without RGBA16F Win32 export was advertised for HDR");
      } else if (!VulkanRayTracingBackendProvider.toPublicDevice(device(true, true, true)).capabilities().contains(Capability.GPU_TIMESTAMPS)) {
         throw new AssertionError("selected queue timestamp support was not published");
      } else if (!VulkanRayTracingBackendProvider.toPublicDevice(device(true, true, true, true)).capabilities().contains(Capability.NATIVE_LINEAR_HDR_RGBA16F)) {
         throw new AssertionError("supported HDR native output was not published");
      } else if (VulkanRayTracingBackendProvider.toPublicDevice(device(true, true, false)).capabilities().contains(Capability.GPU_TIMESTAMPS)) {
         throw new AssertionError("unsupported selected queue timestamps were published");
      }
   }

   private static VulkanRtCapabilityProbe.DeviceReport device(boolean hardwareRt, boolean externalMemory) {
      return device(hardwareRt, externalMemory, true);
   }

   private static VulkanRtCapabilityProbe.DeviceReport device(boolean hardwareRt, boolean externalMemory, boolean gpuTimestamps) {
      return device(hardwareRt, externalMemory, gpuTimestamps, externalMemory);
   }

   private static VulkanRtCapabilityProbe.DeviceReport device(boolean hardwareRt, boolean externalMemory, boolean gpuTimestamps, boolean hdrOutput) {
      return new VulkanRtCapabilityProbe.DeviceReport("provider-contract-device", "provider contract GPU", 4318, 1, 2, VK13.VK_API_VERSION_1_3, hardwareRt, hardwareRt, hardwareRt, hardwareRt, hardwareRt, hardwareRt, hardwareRt, externalMemory, true, externalMemory, hdrOutput, true, gpuTimestamps, 8589934592L, hardwareRt, hardwareRt, hardwareRt, hardwareRt, 2, 32, 32, 64, 4096, 1073741824L, 256);
   }
}
