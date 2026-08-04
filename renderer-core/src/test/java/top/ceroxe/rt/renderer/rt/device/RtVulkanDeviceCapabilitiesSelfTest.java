package top.ceroxe.rt.renderer.rt.device;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VK12;

public final class RtVulkanDeviceCapabilitiesSelfTest {
   private RtVulkanDeviceCapabilitiesSelfTest() {
   }

   public static void main(String[] args) {
      acceptsCoreBufferDeviceAddressOnVulkan12();
      removesProviderBufferDeviceAddressAliasesOnVulkan12();
      requiresPre12CompatibilityExtensions();
      rejectsMissingRequiredRayTracingExtension();
      rejectsMissingShaderInt64Feature();
      enablesOptionalWin32InteropExtensionsOnce();
      validatesSelectedQueueFamilyCapabilities();
      System.out.println("RtVulkanDeviceCapabilitiesSelfTest passed");
   }

   private static void acceptsCoreBufferDeviceAddressOnVulkan12() {
      Set<String> available = baseRtExtensions();
      List<String> enabled = RtVulkanDeviceCapabilities.requiredDeviceExtensions(available, VK12.VK_API_VERSION_1_2);
      require(!enabled.contains("VK_KHR_buffer_device_address"), "Vulkan 1.2 must accept core buffer-device-address support without enabling the KHR alias");
      require(RtVulkanDeviceCapabilities.hardwareRtReady(VK12.VK_API_VERSION_1_2, available, fullyEnabledFeatures()), "Vulkan 1.2 core BDA/SPIR-V 1.4/float-controls support should satisfy RT policy");
   }

   private static void removesProviderBufferDeviceAddressAliasesOnVulkan12() {
      Set<String> available = baseRtExtensions();
      available.add("VK_KHR_buffer_device_address");
      available.add("VK_EXT_buffer_device_address");
      List<String> enabled = RtVulkanDeviceCapabilities.requiredDeviceExtensions(
              available,
              VK12.VK_API_VERSION_1_2,
              Set.of("VK_KHR_buffer_device_address", "VK_EXT_buffer_device_address"),
              Set.of()
      );
      require(!enabled.contains("VK_KHR_buffer_device_address"),
              "Vulkan 1.2 must discard a provider-reported KHR BDA alias");
      require(!enabled.contains("VK_EXT_buffer_device_address"),
              "Vulkan 1.2 must discard a provider-reported EXT BDA alias");
   }

   private static void requiresPre12CompatibilityExtensions() {
      Set<String> missingCompatibilityExtensions = baseRtExtensions();
      require(expectFailure(() -> RtVulkanDeviceCapabilities.requiredDeviceExtensions(missingCompatibilityExtensions, VK11.VK_API_VERSION_1_1)) instanceof IllegalStateException, "Vulkan 1.1 must reject devices that omit the KHR buffer-device-address extension");
      Set<String> available = baseRtExtensions();
      available.add("VK_KHR_buffer_device_address");
      available.add("VK_KHR_spirv_1_4");
      available.add("VK_KHR_shader_float_controls");
      List<String> enabled = RtVulkanDeviceCapabilities.requiredDeviceExtensions(available, VK11.VK_API_VERSION_1_1);
      require(enabled.containsAll(Set.of("VK_KHR_buffer_device_address", "VK_KHR_spirv_1_4", "VK_KHR_shader_float_controls")), "Vulkan 1.1 must explicitly enable BDA, SPIR-V 1.4, and shader float controls");
      require(RtVulkanDeviceCapabilities.hardwareRtReady(VK11.VK_API_VERSION_1_1, available, fullyEnabledFeatures()), "Vulkan 1.1 should become RT ready when every compatibility extension and feature is present");
   }

   private static void rejectsMissingRequiredRayTracingExtension() {
      Set<String> available = baseRtExtensions();
      available.remove("VK_KHR_ray_tracing_pipeline");
      RuntimeException failure = expectFailure(() -> RtVulkanDeviceCapabilities.requiredDeviceExtensions(available, VK12.VK_API_VERSION_1_2));
      require(failure instanceof IllegalStateException, "missing required RT extensions must fail closed");
      require(failure.getMessage().contains("VK_KHR_ray_tracing_pipeline"), "the failure must name the missing extension for actionable diagnostics");
   }

   private static void rejectsMissingShaderInt64Feature() {
      require(!RtVulkanDeviceCapabilities.hardwareRtReady(VK12.VK_API_VERSION_1_2, baseRtExtensions(), new RtVulkanDeviceCapabilities.FeatureFlags(true, true, true, false)), "GPUScene 64-bit arena offsets require shaderInt64 and must fail closed when unavailable");
   }

   private static void enablesOptionalWin32InteropExtensionsOnce() {
      Set<String> available = baseRtExtensions();
      available.add("VK_KHR_external_memory");
      available.add("VK_KHR_external_semaphore");
      available.add("VK_KHR_external_memory_win32");
      available.add("VK_KHR_external_semaphore_win32");
      available.add("VK_KHR_buffer_device_address");
      available.add("VK_KHR_spirv_1_4");
      available.add("VK_KHR_shader_float_controls");
      List<String> enabled = RtVulkanDeviceCapabilities.requiredDeviceExtensions(available, VK11.VK_API_VERSION_1_1);
      require(enabled.stream().filter("VK_KHR_external_memory_win32"::equals).count() == 1L, "Win32 external-memory extension must be enabled exactly once");
      require(enabled.stream().filter("VK_KHR_external_semaphore_win32"::equals).count() == 1L, "Win32 external-semaphore extension must be enabled exactly once");
      require(enabled.size() == (new HashSet<>(enabled)).size(), "enabled extension names must be duplicate-free");
   }

   private static Set<String> baseRtExtensions() {
      return new HashSet<>(Set.of("VK_KHR_acceleration_structure", "VK_KHR_ray_tracing_pipeline", "VK_KHR_deferred_host_operations", "VK_KHR_pipeline_library"));
   }

   private static void validatesSelectedQueueFamilyCapabilities() {
      VulkanQueueFamilyCapabilities withoutTimestamps = new VulkanQueueFamilyCapabilities(0, 1, 0);
      VulkanQueueFamilyCapabilities withTimestamps = new VulkanQueueFamilyCapabilities(2, 3, 64);
      require(!withoutTimestamps.gpuTimestamps(), "zero timestampValidBits must disable timestamp capability");
      require(withTimestamps.gpuTimestamps(), "positive timestampValidBits must enable timestamp capability");
      expectFailure(() -> new VulkanQueueFamilyCapabilities(-1, 1, 64));
      expectFailure(() -> new VulkanQueueFamilyCapabilities(0, 0, 64));
      expectFailure(() -> new VulkanQueueFamilyCapabilities(0, 1, 65));
   }

   private static RtVulkanDeviceCapabilities.FeatureFlags fullyEnabledFeatures() {
      return new RtVulkanDeviceCapabilities.FeatureFlags(true, true, true, true);
   }

   private static RuntimeException expectFailure(Runnable runnable) {
      try {
         runnable.run();
      } catch (RuntimeException exception) {
         return exception;
      }

      throw new AssertionError("expected failure did not occur");
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}
