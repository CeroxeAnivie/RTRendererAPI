package top.ceroxe.rt.renderer.rt.device;

import java.util.List;
import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;

public final class VulkanRtDeviceCapabilitySelfTest {
   private static final int VK_SUCCESS = 0;
   private static final int VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU = 1;
   private static final int VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU = 2;
   private static final int VK_API_VERSION_1_2 = makeApiVersion(1, 2, 0);
   private static final int NVIDIA_VENDOR_ID = 4318;
   private static final int AMD_VENDOR_ID = 4098;
   private static final int INTEL_VENDOR_ID = 32902;

   private VulkanRtDeviceCapabilitySelfTest() {
   }

   public static void main(String[] args) {
      prefersLargerDiscreteRtDeviceWithoutVendorBias();
      acceptsIntegratedHardwareRtDevice();
      honorsExplicitStableDeviceIdentity();
      System.out.println("VulkanRtDeviceCapabilitySelfTest passed");
   }

   private static void prefersLargerDiscreteRtDeviceWithoutVendorBias() {
      VulkanRtCapabilityProbe.Result result = capability(List.of(device("AMD RT Device", 4098, 10, 2, true), device("NVIDIA RT Device", 4318, 20, 2, true)));
      require(result.hardwareRayTracingReady(), "discrete RT capability should be production ready");
      require(result.preferredDevice() != null, "a ready discrete device should be selected by default");
   }

   private static void acceptsIntegratedHardwareRtDevice() {
      VulkanRtCapabilityProbe.Result result = capability(List.of(device("Integrated RT Device", 32902, 30, 1, true)));
      require(result.hardwareRayTracingReady(), "hardware RT support, not packaging, must define admission");
      require(result.preferredDevice() != null, "an RT-capable integrated GPU must remain selectable");
   }

   private static void honorsExplicitStableDeviceIdentity() {
      VulkanRtCapabilityProbe.DeviceReport first = device("First", 4098, 10, 2, true);
      VulkanRtCapabilityProbe.DeviceReport second = device("Second", 4318, 20, 2, true);
      VulkanRtCapabilityProbe.Result selected = capability(List.of(first, second)).select(first.stableId());
      require(selected.preferredDevice() == first, "explicit stable identity was replaced by backend policy");
      expect(IllegalArgumentException.class, () -> selected.select("missing"));
   }

   private static VulkanRtCapabilityProbe.Result capability(List<VulkanRtCapabilityProbe.DeviceReport> devices) {
      return new VulkanRtCapabilityProbe.Result(VK_API_VERSION_1_2, true, false, "ok", 0, "", devices);
   }

   private static VulkanRtCapabilityProbe.DeviceReport device(String name, int vendorId, int deviceId, int deviceType, boolean ready) {
      return new VulkanRtCapabilityProbe.DeviceReport(name, vendorId, deviceId, deviceType, VK_API_VERSION_1_2, ready, ready, ready, ready, ready, ready, ready, ready, ready, ready);
   }

   private static int makeApiVersion(int major, int minor, int patch) {
      return major << 22 | minor << 12 | patch;
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }

   private static <T extends Throwable> void expect(Class<T> type, Runnable action) {
      try {
         action.run();
      } catch (Throwable failure) {
         if (type.isInstance(failure)) {
            return;
         }

         throw new AssertionError("expected " + type.getName() + " but caught " + String.valueOf(failure), failure);
      }

      throw new AssertionError("expected " + type.getName() + " but no exception was thrown");
   }
}
