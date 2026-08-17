package top.ceroxe.rt.renderer.backend.vulkan;

import java.util.HashSet;
import java.util.List;
import top.ceroxe.rt.renderer.api.RendererGpuDevice;
import top.ceroxe.rt.renderer.api.RendererConfig;
import top.ceroxe.rt.renderer.api.RendererBootstrap;
import top.ceroxe.rt.renderer.api.RendererPreset;
import top.ceroxe.rt.renderer.api.HardwareCapabilities;
import top.ceroxe.rt.renderer.spi.RendererBackendProvider.Compatibility;

public final class VulkanGpuDeviceCatalogNativeSelfTest {
   private VulkanGpuDeviceCatalogNativeSelfTest() {
   }

   public static void main(String[] args) {
      List<RendererGpuDevice> devices = RendererBootstrap.availableGpuDevices();
      if (devices.isEmpty()) {
         throw new AssertionError("no hardware RT GPU was exposed by the public catalog");
      } else {
         HashSet<String> identities = new HashSet<>();

         for(RendererGpuDevice device : devices) {
            String details10001 = device.backendId();
            if (!identities.add(details10001 + "/" + device.stableId())) {
               throw new AssertionError("duplicate public GPU identity: " + device.stableId());
            }

            if (!device.hardwareCapabilities().supports(HardwareCapabilities.Feature.HARDWARE_RAY_TRACING)) {
               throw new AssertionError("catalog exposed a non-RT device: " + String.valueOf(device));
            }

            if (device.stableId().length() != 32) {
               throw new AssertionError("Vulkan device identity is not a 128-bit UUID: " + device.stableId());
            }

            RendererConfig selected = RendererPreset.CPU_READBACK.configuration().copyBuilder().gpuDevice(device).build();
            if ((new VulkanRendererBackendProvider()).probe(selected).compatibility() != Compatibility.COMPATIBLE) {
               throw new AssertionError("fresh catalog device could not be selected: " + String.valueOf(device));
            }
         }

         System.out.println("VulkanGpuDeviceCatalogNativeSelfTest passed: " + String.valueOf(devices));
      }
   }
}
