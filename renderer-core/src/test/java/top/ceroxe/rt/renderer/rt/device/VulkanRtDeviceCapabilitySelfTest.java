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
      failedProbeNeverSelectsReadyDevice();
      rejectsMalformedDeviceReports();
      acceptsImportOnlyExternalMemoryEvidence();
      System.out.println("VulkanRtDeviceCapabilitySelfTest passed");
   }

   private static void failedProbeNeverSelectsReadyDevice() {
      VulkanRtCapabilityProbe.DeviceReport ready = device("Ready but untrusted", AMD_VENDOR_ID, 40,
              VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU, true);
      VulkanRtCapabilityProbe.Result failed = new VulkanRtCapabilityProbe.Result(
              VK_API_VERSION_1_2, true, true, "device-query", -3, "query failed", List.of(ready)
      );
      require(!failed.hardwareRayTracingReady() && failed.preferredDevice() == null,
              "failed probe leaked a ready device into admission");
      expect(IllegalStateException.class, () -> failed.select(ready.stableId()));
   }

   private static void rejectsMalformedDeviceReports() {
      expect(IllegalArgumentException.class, () -> device(" bad", INTEL_VENDOR_ID, 50,
              VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU, true));
      VulkanRtCapabilityProbe.DeviceReport duplicate = device("Duplicate", NVIDIA_VENDOR_ID, 60,
              VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU, true);
      expect(IllegalArgumentException.class, () -> capability(List.of(duplicate, duplicate)));
   }

   private static void acceptsImportOnlyExternalMemoryEvidence() {
      VulkanRtCapabilityProbe.DeviceReport report = new VulkanRtCapabilityProbe.DeviceReport(
              "Import-only frame device", NVIDIA_VENDOR_ID, 70, VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU,
              VK_API_VERSION_1_2,
              true, true, true, true, true, true, true,
              true, false,
              false, true, true,
              false, false, false,
              false, false, 0L, 1,
              true, true, true, true,
              1, 1, 1, 1, 1, 1L, 1
      );
      require(report.externalMemory() && report.sdrRgba8Import() && !report.sdrRgba8Output(),
              "import-only external-memory evidence was rejected or rewritten");
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
