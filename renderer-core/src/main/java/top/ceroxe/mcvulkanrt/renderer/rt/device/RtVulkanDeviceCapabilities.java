package top.ceroxe.mcvulkanrt.renderer.rt.device;

import org.lwjgl.system.MemoryStack;
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
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceBufferDeviceAddressFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelineFeaturesKHR;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Owns Vulkan RT capability interpretation and logical-device extension policy.
 *
 * <p>This class deliberately has no device-context dependency. It converts transient
 * Vulkan query structures into stable values, so instance/device lifetime ownership
 * remains in {@link VulkanRtDeviceContext} while capability policy can evolve and be
 * reviewed independently from frame submission.</p>
 */
final class RtVulkanDeviceCapabilities {
    private RtVulkanDeviceCapabilities() {
    }

    static List<String> requiredDeviceExtensions(Set<String> extensions, int apiVersion) {
        List<String> enabled = new ArrayList<>();
        requireExtension(enabled, extensions, KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME);
        requireExtension(enabled, extensions, KHRRayTracingPipeline.VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME);
        requireExtension(enabled, extensions, KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME);
        requireExtension(enabled, extensions, KHRPipelineLibrary.VK_KHR_PIPELINE_LIBRARY_EXTENSION_NAME);

        if (extensions.contains(KHRBufferDeviceAddress.VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME)) {
            enabled.add(KHRBufferDeviceAddress.VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME);
        } else if (apiVersion < VK12.VK_API_VERSION_1_2) {
            throw new IllegalStateException("missing required extension: "
                    + KHRBufferDeviceAddress.VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME);
        }

        if (apiVersion < VK12.VK_API_VERSION_1_2) {
            requireExtension(enabled, extensions, KHRSpirv14.VK_KHR_SPIRV_1_4_EXTENSION_NAME);
            requireExtension(enabled, extensions, KHRShaderFloatControls.VK_KHR_SHADER_FLOAT_CONTROLS_EXTENSION_NAME);
        }
        addOptionalExternalInteropExtensions(enabled, extensions, apiVersion);
        return enabled;
    }

    static boolean hardwareRtReady(int apiVersion, Set<String> extensions, FeatureFlags features) {
        boolean accelerationStructure = extensions.contains(
                KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME);
        boolean rayTracingPipeline = extensions.contains(
                KHRRayTracingPipeline.VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME);
        boolean deferredHostOperations = extensions.contains(
                KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME);
        boolean pipelineLibrary = extensions.contains(KHRPipelineLibrary.VK_KHR_PIPELINE_LIBRARY_EXTENSION_NAME);
        boolean api12OrNewer = apiVersion >= VK12.VK_API_VERSION_1_2;
        boolean bufferDeviceAddress = api12OrNewer
                || extensions.contains(KHRBufferDeviceAddress.VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME);
        boolean spirv14 = api12OrNewer || extensions.contains(KHRSpirv14.VK_KHR_SPIRV_1_4_EXTENSION_NAME);
        boolean shaderFloatControls = api12OrNewer
                || extensions.contains(KHRShaderFloatControls.VK_KHR_SHADER_FLOAT_CONTROLS_EXTENSION_NAME);

        return accelerationStructure
                && features.accelerationStructure()
                && rayTracingPipeline
                && features.rayTracingPipeline()
                && deferredHostOperations
                && pipelineLibrary
                && bufferDeviceAddress
                && features.bufferDeviceAddress()
                && features.shaderInt64()
                && spirv14
                && shaderFloatControls;
    }

    static FeatureFlags queryRtFeatures(MemoryStack stack, VkPhysicalDevice device, int apiVersion) {
        if (apiVersion < VK11.VK_API_VERSION_1_1) {
            return FeatureFlags.UNAVAILABLE;
        }

        VkPhysicalDeviceAccelerationStructureFeaturesKHR accelerationStructure =
                VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack).sType$Default();
        VkPhysicalDeviceRayTracingPipelineFeaturesKHR rayTracingPipeline =
                VkPhysicalDeviceRayTracingPipelineFeaturesKHR.calloc(stack).sType$Default();
        VkPhysicalDeviceBufferDeviceAddressFeatures bufferDeviceAddress =
                VkPhysicalDeviceBufferDeviceAddressFeatures.calloc(stack).sType$Default();
        VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();

        features2.pNext(accelerationStructure.address());
        accelerationStructure.pNext(rayTracingPipeline.address());
        rayTracingPipeline.pNext(bufferDeviceAddress.address());

        VK11.vkGetPhysicalDeviceFeatures2(device, features2);
        return new FeatureFlags(
                accelerationStructure.accelerationStructure(),
                rayTracingPipeline.rayTracingPipeline(),
                bufferDeviceAddress.bufferDeviceAddress(),
                features2.features().shaderInt64()
        );
    }

    static Set<String> enumerateDeviceExtensions(MemoryStack stack, VkPhysicalDevice device) {
        IntBuffer count = stack.ints(0);
        int countResult = VK10.vkEnumerateDeviceExtensionProperties(device, (String) null, count, null);
        if (countResult != VK10.VK_SUCCESS || count.get(0) == 0) {
            return Set.of();
        }

        VkExtensionProperties.Buffer properties = VkExtensionProperties.calloc(count.get(0));
        try {
            int enumerateResult = VK10.vkEnumerateDeviceExtensionProperties(device, (String) null, count, properties);
            if (enumerateResult != VK10.VK_SUCCESS) {
                return Set.of();
            }

            Set<String> extensions = new HashSet<>(properties.remaining());
            for (int index = 0; index < properties.limit(); index++) {
                extensions.add(properties.get(index).extensionNameString());
            }
            return extensions;
        } finally {
            properties.free();
        }
    }

    static StablePhysicalDeviceProperties copyProperties(VkPhysicalDeviceProperties properties) {
        return new StablePhysicalDeviceProperties(
                properties.deviceNameString(),
                properties.vendorID(),
                properties.deviceID(),
                properties.deviceType(),
                properties.apiVersion()
        );
    }

    private static void requireExtension(List<String> enabled, Set<String> extensions, String extension) {
        if (!extensions.contains(extension)) {
            throw new IllegalStateException("missing required extension: " + extension);
        }
        enabled.add(extension);
    }

    private static void addOptionalExternalInteropExtensions(
            List<String> enabled,
            Set<String> extensions,
            int apiVersion
    ) {
        boolean externalMemoryCore = apiVersion >= VK11.VK_API_VERSION_1_1
                || extensions.contains(KHRExternalMemory.VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME);
        boolean externalSemaphoreCore = apiVersion >= VK11.VK_API_VERSION_1_1
                || extensions.contains(KHRExternalSemaphore.VK_KHR_EXTERNAL_SEMAPHORE_EXTENSION_NAME);
        if (apiVersion < VK11.VK_API_VERSION_1_1) {
            addOptionalExtension(enabled, extensions, KHRExternalMemory.VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME);
            addOptionalExtension(enabled, extensions, KHRExternalSemaphore.VK_KHR_EXTERNAL_SEMAPHORE_EXTENSION_NAME);
        }
        if (externalMemoryCore) {
            addOptionalExtension(enabled, extensions, KHRExternalMemoryWin32.VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME);
        }
        if (externalSemaphoreCore) {
            addOptionalExtension(
                    enabled,
                    extensions,
                    KHRExternalSemaphoreWin32.VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME
            );
        }
    }

    private static void addOptionalExtension(List<String> enabled, Set<String> extensions, String extension) {
        if (extensions.contains(extension) && !enabled.contains(extension)) {
            enabled.add(extension);
        }
    }

    record StablePhysicalDeviceProperties(
            String deviceNameString,
            int vendorID,
            int deviceID,
            int deviceType,
            int apiVersion
    ) {
    }

    record FeatureFlags(
            boolean accelerationStructure,
            boolean rayTracingPipeline,
            boolean bufferDeviceAddress,
            boolean shaderInt64
    ) {
        private static final FeatureFlags UNAVAILABLE = new FeatureFlags(false, false, false, false);
    }
}
