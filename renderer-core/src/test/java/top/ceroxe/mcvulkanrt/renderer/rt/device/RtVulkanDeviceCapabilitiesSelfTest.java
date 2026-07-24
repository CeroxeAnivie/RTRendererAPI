package top.ceroxe.mcvulkanrt.renderer.rt.device;

import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.KHRBufferDeviceAddress;
import org.lwjgl.vulkan.KHRDeferredHostOperations;
import org.lwjgl.vulkan.KHRExternalMemory;
import org.lwjgl.vulkan.KHRExternalMemoryWin32;
import org.lwjgl.vulkan.KHRExternalSemaphore;
import org.lwjgl.vulkan.KHRExternalSemaphoreWin32;
import org.lwjgl.vulkan.KHRPipelineLibrary;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.KHRShaderFloatControls;
import org.lwjgl.vulkan.KHRSpirv14;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VK12;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Exercises Vulkan RT extension policy without creating a Vulkan instance or device. */
public final class RtVulkanDeviceCapabilitiesSelfTest {
    private RtVulkanDeviceCapabilitiesSelfTest() {
    }

    public static void main(String[] args) {
        acceptsCoreBufferDeviceAddressOnVulkan12();
        requiresPre12CompatibilityExtensions();
        rejectsMissingRequiredRayTracingExtension();
        rejectsMissingShaderInt64Feature();
        enablesOptionalWin32InteropExtensionsOnce();
        System.out.println("RtVulkanDeviceCapabilitiesSelfTest passed");
    }

    private static void acceptsCoreBufferDeviceAddressOnVulkan12() {
        Set<String> available = baseRtExtensions();
        List<String> enabled = RtVulkanDeviceCapabilities.requiredDeviceExtensions(
                available,
                VK12.VK_API_VERSION_1_2
        );

        require(
                !enabled.contains(KHRBufferDeviceAddress.VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME),
                "Vulkan 1.2 must accept core buffer-device-address support without enabling the KHR alias"
        );
        require(
                RtVulkanDeviceCapabilities.hardwareRtReady(
                        VK12.VK_API_VERSION_1_2,
                        available,
                        fullyEnabledFeatures()
                ),
                "Vulkan 1.2 core BDA/SPIR-V 1.4/float-controls support should satisfy RT policy"
        );
    }

    private static void requiresPre12CompatibilityExtensions() {
        Set<String> missingCompatibilityExtensions = baseRtExtensions();
        require(
                expectFailure(() -> RtVulkanDeviceCapabilities.requiredDeviceExtensions(
                        missingCompatibilityExtensions,
                        VK11.VK_API_VERSION_1_1
                )) instanceof IllegalStateException,
                "Vulkan 1.1 must reject devices that omit the KHR buffer-device-address extension"
        );

        Set<String> available = baseRtExtensions();
        available.add(KHRBufferDeviceAddress.VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME);
        available.add(KHRSpirv14.VK_KHR_SPIRV_1_4_EXTENSION_NAME);
        available.add(KHRShaderFloatControls.VK_KHR_SHADER_FLOAT_CONTROLS_EXTENSION_NAME);
        List<String> enabled = RtVulkanDeviceCapabilities.requiredDeviceExtensions(
                available,
                VK11.VK_API_VERSION_1_1
        );

        require(
                enabled.containsAll(Set.of(
                        KHRBufferDeviceAddress.VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME,
                        KHRSpirv14.VK_KHR_SPIRV_1_4_EXTENSION_NAME,
                        KHRShaderFloatControls.VK_KHR_SHADER_FLOAT_CONTROLS_EXTENSION_NAME
                )),
                "Vulkan 1.1 must explicitly enable BDA, SPIR-V 1.4, and shader float controls"
        );
        require(
                RtVulkanDeviceCapabilities.hardwareRtReady(
                        VK11.VK_API_VERSION_1_1,
                        available,
                        fullyEnabledFeatures()
                ),
                "Vulkan 1.1 should become RT ready when every compatibility extension and feature is present"
        );
    }

    private static void rejectsMissingRequiredRayTracingExtension() {
        Set<String> available = baseRtExtensions();
        available.remove(KHRRayTracingPipeline.VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME);

        RuntimeException failure = expectFailure(() -> RtVulkanDeviceCapabilities.requiredDeviceExtensions(
                available,
                VK12.VK_API_VERSION_1_2
        ));
        require(failure instanceof IllegalStateException, "missing required RT extensions must fail closed");
        require(
                failure.getMessage().contains(KHRRayTracingPipeline.VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME),
                "the failure must name the missing extension for actionable diagnostics"
        );
    }

    private static void rejectsMissingShaderInt64Feature() {
        require(
                !RtVulkanDeviceCapabilities.hardwareRtReady(
                        VK12.VK_API_VERSION_1_2,
                        baseRtExtensions(),
                        new RtVulkanDeviceCapabilities.FeatureFlags(true, true, true, false)
                ),
                "GPUScene 64-bit arena offsets require shaderInt64 and must fail closed when unavailable"
        );
    }

    private static void enablesOptionalWin32InteropExtensionsOnce() {
        Set<String> available = baseRtExtensions();
        available.add(KHRExternalMemory.VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME);
        available.add(KHRExternalSemaphore.VK_KHR_EXTERNAL_SEMAPHORE_EXTENSION_NAME);
        available.add(KHRExternalMemoryWin32.VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME);
        available.add(KHRExternalSemaphoreWin32.VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME);
        available.add(KHRBufferDeviceAddress.VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME);
        available.add(KHRSpirv14.VK_KHR_SPIRV_1_4_EXTENSION_NAME);
        available.add(KHRShaderFloatControls.VK_KHR_SHADER_FLOAT_CONTROLS_EXTENSION_NAME);

        List<String> enabled = RtVulkanDeviceCapabilities.requiredDeviceExtensions(
                available,
                VK11.VK_API_VERSION_1_1
        );
        require(
                enabled.stream().filter(KHRExternalMemoryWin32.VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME::equals)
                        .count() == 1L,
                "Win32 external-memory extension must be enabled exactly once"
        );
        require(
                enabled.stream().filter(KHRExternalSemaphoreWin32.VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME::equals)
                        .count() == 1L,
                "Win32 external-semaphore extension must be enabled exactly once"
        );
        require(enabled.size() == new HashSet<>(enabled).size(), "enabled extension names must be duplicate-free");
    }

    private static Set<String> baseRtExtensions() {
        return new HashSet<>(Set.of(
                KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME,
                KHRRayTracingPipeline.VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME,
                KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME,
                KHRPipelineLibrary.VK_KHR_PIPELINE_LIBRARY_EXTENSION_NAME
        ));
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
