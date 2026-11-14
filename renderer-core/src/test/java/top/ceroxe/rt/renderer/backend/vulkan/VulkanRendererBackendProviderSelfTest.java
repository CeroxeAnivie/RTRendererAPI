package top.ceroxe.rt.renderer.backend.vulkan;

import java.util.ServiceLoader;
import org.lwjgl.vulkan.VK13;
import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.api.FrameOutputFormat;
import top.ceroxe.rt.renderer.api.HardwareCapabilities;
import top.ceroxe.rt.renderer.api.RendererGpuDevice;
import top.ceroxe.rt.renderer.spi.RendererBackendProvider;

public final class VulkanRendererBackendProviderSelfTest {
   private VulkanRendererBackendProviderSelfTest() {
   }

   public static void main(String[] arguments) {
      RendererBackendProvider provider = (RendererBackendProvider)ServiceLoader.load(RendererBackendProvider.class).stream().map(ServiceLoader.Provider::get).filter((candidate) -> candidate.descriptor().id().equals("vulkan-rt")).findFirst().orElseThrow(() -> new AssertionError("vulkan-rt backend provider was not discovered"));
      RendererBackendProvider.Descriptor descriptor = provider.descriptor();
      if (descriptor.apiMajor() == RendererBackendProvider.API_MAJOR
              && descriptor.apiMinor() <= RendererBackendProvider.API_MINOR
              && descriptor.priority() > 0) {
         verifyPublicFrameCapabilityGate();
         verifyAuthoritativeSelection();
         System.out.println("VulkanRendererBackendProviderSelfTest passed: " + String.valueOf(descriptor));
      } else {
         throw new AssertionError("Vulkan backend descriptor is invalid: " + String.valueOf(descriptor));
      }
   }

   private static void verifyAuthoritativeSelection() {
      RendererGpuDevice authoritative = VulkanRendererBackendProvider.toPublicDevice(
              device(true, true, true, true)
      );
      VulkanRendererBackendProvider.validateSelectedSnapshot(
              authoritative, authoritative.toBuilder().build()
      );
      RendererGpuDevice forged = authoritative.toBuilder().name("forged capability snapshot").build();
      try {
         VulkanRendererBackendProvider.validateSelectedSnapshot(authoritative, forged);
         throw new AssertionError("non-authoritative GPU snapshot was accepted");
      } catch (IllegalArgumentException expected) {
         if (!expected.getMessage().contains("stale")) {
            throw new AssertionError("snapshot rejection lost its stable reason", expected);
         }
      }
   }

   private static void verifyPublicFrameCapabilityGate() {
      if (!VulkanRendererBackendProvider.publicFrameApiReady(device(true, true))) {
         throw new AssertionError("fully capable external-frame device was rejected");
      } else if (VulkanRendererBackendProvider.publicFrameApiReady(device(true, false))) {
         throw new AssertionError("device without external frame memory was advertised as available");
      } else if (VulkanRendererBackendProvider.publicFrameApiReady(device(false, true))) {
         throw new AssertionError("external memory concealed missing hardware RT support");
      } else if (VulkanRendererBackendProvider.publicFrameApiReady(device(true, true, true, false), FrameOutputFormat.LINEAR_HDR_RGBA16F)) {
         throw new AssertionError("device without RGBA16F Win32 export was advertised for HDR");
      } else if (!VulkanRendererBackendProvider.toPublicDevice(device(true, true, true))
              .hardwareCapabilities().supports(HardwareCapabilities.Feature.GPU_TIMESTAMPS)) {
         throw new AssertionError("selected queue timestamp support was not published");
      } else if (VulkanRendererBackendProvider.toPublicDevice(device(true, true, true, true))
              .hardwareCapabilities().frameInterop(
                      FrameOutputFormat.LINEAR_HDR_RGBA16F,
                      HardwareCapabilities.ExternalHandleType.OPAQUE_WIN32
              ).memoryExport().state() != HardwareCapabilities.SupportState.SUPPORTED) {
         throw new AssertionError("supported HDR native output was not published");
      } else if (VulkanRendererBackendProvider.toPublicDevice(device(true, true, false))
              .hardwareCapabilities().supports(HardwareCapabilities.Feature.GPU_TIMESTAMPS)) {
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
      return new VulkanRtCapabilityProbe.DeviceReport(
              "provider-contract-device", "provider contract GPU", 4318, 1, 2,
              VK13.VK_API_VERSION_1_3,
              hardwareRt, hardwareRt, hardwareRt, hardwareRt, hardwareRt, hardwareRt, hardwareRt,
              externalMemory, true,
              externalMemory, externalMemory, false,
              hdrOutput, hdrOutput, false,
              true, gpuTimestamps, 8_589_934_592L, 16_384,
              hardwareRt, hardwareRt, hardwareRt, hardwareRt,
              2, 32, 32, 64, 4096, 1_073_741_824L, 256
      );
   }
}
