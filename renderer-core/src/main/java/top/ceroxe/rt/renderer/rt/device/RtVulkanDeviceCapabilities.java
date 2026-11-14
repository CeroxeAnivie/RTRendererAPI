package top.ceroxe.rt.renderer.rt.device;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
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
        return requiredDeviceExtensions(extensions, apiVersion, Set.of(), Set.of());
    }

    /**
     * Builds the logical-device extension list after optional feature negotiation.
     *
     * <p>Feature requirements are merged here, immediately before device creation, so a provider
     * cannot claim an extension in a later frame after the Vulkan contract is already fixed.</p>
     */
    static List<String> requiredDeviceExtensions(
            Set<String> extensions,
            int apiVersion,
            Set<String> featureRequiredExtensions,
            Set<String> featurePreferredExtensions
    ) {
        List<String> enabled = new ArrayList<>();
        requireExtension(enabled, extensions, KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME);
        requireExtension(enabled, extensions, KHRRayTracingPipeline.VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME);
        requireExtension(enabled, extensions, KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME);
        requireExtension(enabled, extensions, KHRPipelineLibrary.VK_KHR_PIPELINE_LIBRARY_EXTENSION_NAME);

        if (apiVersion < VK12.VK_API_VERSION_1_2) {
            requireExtension(
                    enabled, extensions,
                    KHRBufferDeviceAddress.VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME
            );
        }

        if (apiVersion < VK12.VK_API_VERSION_1_2) {
            requireExtension(enabled, extensions, KHRSpirv14.VK_KHR_SPIRV_1_4_EXTENSION_NAME);
            requireExtension(enabled, extensions, KHRShaderFloatControls.VK_KHR_SHADER_FLOAT_CONTROLS_EXTENSION_NAME);
            addOptionalExtension(enabled, extensions, KHRTimelineSemaphore.VK_KHR_TIMELINE_SEMAPHORE_EXTENSION_NAME);
        }
        addOptionalExternalInteropExtensions(enabled, extensions, apiVersion);
        // The managed presenter shares this logical device with the RT pipeline.  Enabling the
        // swapchain extension here is what makes the zero-IPC viewport path possible; devices
        // without it remain usable for headless/expert interop and simply take the fallback path.
        addOptionalExtension(enabled, extensions, KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME);
        addOptionalExtension(
                enabled, extensions, EXTFullScreenExclusive.VK_EXT_FULL_SCREEN_EXCLUSIVE_EXTENSION_NAME
        );
        addOptionalExtension(enabled, extensions, EXTMemoryBudget.VK_EXT_MEMORY_BUDGET_EXTENSION_NAME);
        for (String extension : Objects.requireNonNull(featureRequiredExtensions, "featureRequiredExtensions")) {
            if (isSupersededBufferDeviceAddressAlias(extension, apiVersion)) continue;
            requireExtension(enabled, extensions, extension);
        }
        for (String extension : Objects.requireNonNull(featurePreferredExtensions, "featurePreferredExtensions")) {
            if (isSupersededBufferDeviceAddressAlias(extension, apiVersion)) continue;
            addOptionalExtension(enabled, extensions, extension);
        }
        return enabled;
    }

    /**
     * Keeps provider-reported compatibility aliases from creating an invalid logical device.
     *
     * <p>The RT core always selects the KHR spelling before Vulkan 1.2 and the promoted core
     * feature on Vulkan 1.2+. Streamline can report both historical spellings, but Vulkan forbids
     * enabling the KHR and EXT buffer-device-address extensions together.</p>
     */
    private static boolean isSupersededBufferDeviceAddressAlias(String extension, int apiVersion) {
        if (EXTBufferDeviceAddress.VK_EXT_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME.equals(extension)) return true;
        return apiVersion >= VK12.VK_API_VERSION_1_2
                && KHRBufferDeviceAddress.VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME.equals(extension);
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

    static boolean timelineSemaphoreSupported(
            MemoryStack stack,
            VkPhysicalDevice device,
            int apiVersion,
            Set<String> extensions
    ) {
        if (apiVersion < VK12.VK_API_VERSION_1_2
                && !extensions.contains(KHRTimelineSemaphore.VK_KHR_TIMELINE_SEMAPHORE_EXTENSION_NAME)) {
            return false;
        }
        VkPhysicalDeviceTimelineSemaphoreFeatures timeline =
                VkPhysicalDeviceTimelineSemaphoreFeatures.calloc(stack).sType$Default();
        VkPhysicalDeviceFeatures2 features = VkPhysicalDeviceFeatures2.calloc(stack)
                .sType$Default()
                .pNext(timeline.address());
        VK11.vkGetPhysicalDeviceFeatures2(device, features);
        return timeline.timelineSemaphore();
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
                properties.apiVersion(),
                properties.limits().maxImageDimension2D(),
                properties.limits().maxBoundDescriptorSets(),
                properties.limits().maxSamplerAnisotropy()
        );
    }

    static String stableDeviceId(MemoryStack stack, VkPhysicalDevice device, int apiVersion) {
        if (apiVersion < VK11.VK_API_VERSION_1_1) {
            throw new IllegalStateException("hardware RT device identity requires Vulkan 1.1");
        }
        VkPhysicalDeviceIDProperties identity = VkPhysicalDeviceIDProperties.calloc(stack).sType$Default();
        org.lwjgl.vulkan.VkPhysicalDeviceProperties2 properties =
                org.lwjgl.vulkan.VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(identity);
        VK11.vkGetPhysicalDeviceProperties2(device, properties);
        java.nio.ByteBuffer uuid = identity.deviceUUID();
        StringBuilder result = new StringBuilder(uuid.remaining() * 2);
        for (int index = uuid.position(); index < uuid.limit(); index++) {
            result.append(Character.forDigit((uuid.get(index) >>> 4) & 0x0f, 16));
            result.append(Character.forDigit(uuid.get(index) & 0x0f, 16));
        }
        return result.toString();
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
            int apiVersion,
            int maxImageDimension2D,
            int maxBoundDescriptorSets,
            float maxSamplerAnisotropy
    ) {
        StablePhysicalDeviceProperties {
            if (maxImageDimension2D <= 0 || maxBoundDescriptorSets <= 0
                    || !Float.isFinite(maxSamplerAnisotropy) || maxSamplerAnisotropy < 1.0F) {
                throw new IllegalArgumentException("physical-device limits must be finite and positive");
            }
        }
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
