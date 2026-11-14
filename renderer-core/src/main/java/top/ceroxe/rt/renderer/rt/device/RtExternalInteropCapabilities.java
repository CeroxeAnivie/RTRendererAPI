package top.ceroxe.rt.renderer.rt.device;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import top.ceroxe.rt.renderer.rt.device.interop.VulkanWin32ExternalMemoryProbe;
import top.ceroxe.rt.renderer.rt.device.interop.VulkanWin32ExternalSemaphoreProbe;
import top.ceroxe.rt.renderer.rt.pipeline.RtRayTracingPipeline;

import java.util.List;
import java.util.Objects;

/**
 * Immutable external-memory/semaphore capability and probe evidence for one Vulkan device.
 *
 * <p>Capability discovery is separate from presentation policy. The device context consumes this
 * value when it decides whether shared-frame export is available, while the probe facts remain
 * inspectable even when export is deliberately disabled by a higher-level policy.</p>
 */
final class RtExternalInteropCapabilities {
    private final Support support;
    private final VulkanWin32ExternalMemoryProbe.Result memoryProbe;
    private final VulkanWin32ExternalSemaphoreProbe.Result semaphoreProbe;

    private RtExternalInteropCapabilities(
            Support support,
            VulkanWin32ExternalMemoryProbe.Result memoryProbe,
            VulkanWin32ExternalSemaphoreProbe.Result semaphoreProbe
    ) {
        this.support = Objects.requireNonNull(support, "support");
        this.memoryProbe = Objects.requireNonNull(memoryProbe, "memoryProbe");
        this.semaphoreProbe = Objects.requireNonNull(semaphoreProbe, "semaphoreProbe");
    }

    static RtExternalInteropCapabilities create(
            MemoryStack stack,
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            int apiVersion,
            List<String> enabledExtensions
    ) {
        return create(
                stack, physicalDevice, device, apiVersion, enabledExtensions,
                RtRayTracingPipeline.bootstrapOutputFormat()
        );
    }

    static RtExternalInteropCapabilities create(
            MemoryStack stack,
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            int apiVersion,
            List<String> enabledExtensions,
            int outputFormat
    ) {
        Support support = querySupport(
                Objects.requireNonNull(stack, "stack"),
                Objects.requireNonNull(physicalDevice, "physicalDevice"),
                apiVersion,
                List.copyOf(Objects.requireNonNull(enabledExtensions, "enabledExtensions")),
                outputFormat
        );
        VulkanWin32ExternalMemoryProbe.Result memoryProbe = support.memoryExportReady()
                ? VulkanWin32ExternalMemoryProbe.run(
                physicalDevice,
                Objects.requireNonNull(device, "device"),
                outputFormat,
                support.dedicatedOnly()
        )
                : VulkanWin32ExternalMemoryProbe.Result.skipped(support.reason());
        VulkanWin32ExternalSemaphoreProbe.Result semaphoreProbe = support.semaphoreExportCandidate()
                ? VulkanWin32ExternalSemaphoreProbe.run(physicalDevice, device)
                : VulkanWin32ExternalSemaphoreProbe.Result.skipped(support.reason());
        return new RtExternalInteropCapabilities(support, memoryProbe, semaphoreProbe);
    }

    private static Support querySupport(
            MemoryStack stack,
            VkPhysicalDevice physicalDevice,
            int apiVersion,
            List<String> enabledExtensions,
            int outputFormat
    ) {
        boolean externalMemoryCore = apiVersion >= VK11.VK_API_VERSION_1_1
                || enabledExtensions.contains(KHRExternalMemory.VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME);
        boolean externalSemaphoreCore = apiVersion >= VK11.VK_API_VERSION_1_1
                || enabledExtensions.contains(KHRExternalSemaphore.VK_KHR_EXTERNAL_SEMAPHORE_EXTENSION_NAME);
        boolean win32MemoryEnabled = enabledExtensions.contains(
                KHRExternalMemoryWin32.VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME
        );
        boolean win32SemaphoreEnabled = enabledExtensions.contains(
                KHRExternalSemaphoreWin32.VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME
        );
        if (!externalMemoryCore || !win32MemoryEnabled) {
            return Support.unavailable(
                    externalMemoryCore,
                    externalSemaphoreCore,
                    win32MemoryEnabled,
                    win32SemaphoreEnabled,
                    "externalMemoryWin32ExtensionUnavailable"
            );
        }

        int handleType = VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
        VkPhysicalDeviceExternalImageFormatInfo externalInfo = VkPhysicalDeviceExternalImageFormatInfo.calloc(stack)
                .sType$Default().handleType(handleType);
        VkPhysicalDeviceImageFormatInfo2 imageFormatInfo = VkPhysicalDeviceImageFormatInfo2.calloc(stack)
                .sType$Default().pNext(externalInfo).format(outputFormat)
                .type(VK10.VK_IMAGE_TYPE_2D).tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
                .usage(VK10.VK_IMAGE_USAGE_STORAGE_BIT
                        | VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                        | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                .flags(0);
        VkExternalImageFormatProperties externalProperties = VkExternalImageFormatProperties.calloc(stack).sType$Default();
        VkImageFormatProperties2 imageFormatProperties = VkImageFormatProperties2.calloc(stack)
                .sType$Default().pNext(externalProperties);
        int result = VK11.vkGetPhysicalDeviceImageFormatProperties2(physicalDevice, imageFormatInfo, imageFormatProperties);
        if (result != VK10.VK_SUCCESS) {
            return Support.unavailable(
                    externalMemoryCore,
                    externalSemaphoreCore,
                    win32MemoryEnabled,
                    win32SemaphoreEnabled,
                    "externalImageFormatQueryFailed:" + vkResultName(result)
            );
        }

        VkExternalMemoryProperties memoryProperties = externalProperties.externalMemoryProperties();
        int memoryFeatures = memoryProperties.externalMemoryFeatures();
        int compatibleHandleTypes = memoryProperties.compatibleHandleTypes();
        boolean compatibleOpaqueWin32 = (compatibleHandleTypes & handleType) != 0;
        boolean exportable = (memoryFeatures & VK11.VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT) != 0;
        boolean importable = (memoryFeatures & VK11.VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT) != 0;
        boolean dedicatedOnly = (memoryFeatures & VK11.VK_EXTERNAL_MEMORY_FEATURE_DEDICATED_ONLY_BIT) != 0;
        boolean memoryExportReady = compatibleOpaqueWin32 && exportable;
        return new Support(
                externalMemoryCore,
                externalSemaphoreCore,
                win32MemoryEnabled,
                win32SemaphoreEnabled,
                true,
                compatibleOpaqueWin32,
                exportable,
                importable,
                dedicatedOnly,
                memoryFeatures,
                compatibleHandleTypes,
                memoryExportReady && externalSemaphoreCore && win32SemaphoreEnabled,
                memoryExportReady ? "ready" : "opaqueWin32StorageImageNotExportable"
        );
    }

    private static String vkResultName(int result) {
        return switch (result) {
            case VK10.VK_SUCCESS -> "VK_SUCCESS";
            case VK10.VK_ERROR_OUT_OF_HOST_MEMORY -> "VK_ERROR_OUT_OF_HOST_MEMORY";
            case VK10.VK_ERROR_OUT_OF_DEVICE_MEMORY -> "VK_ERROR_OUT_OF_DEVICE_MEMORY";
            case VK10.VK_ERROR_INITIALIZATION_FAILED -> "VK_ERROR_INITIALIZATION_FAILED";
            default -> Integer.toString(result);
        };
    }

    boolean gpuInteropCandidate() {
        return support.gpuInteropCandidate();
    }

    boolean dedicatedOnly() {
        return support.dedicatedOnly();
    }

    String reason() {
        return support.reason();
    }

    VulkanWin32ExternalMemoryProbe.Result memoryProbe() {
        return memoryProbe;
    }

    VulkanWin32ExternalSemaphoreProbe.Result semaphoreProbe() {
        return semaphoreProbe;
    }

    boolean sharedPresentationReady() {
        return support.gpuInteropCandidate() && memoryProbe.successful() && semaphoreProbe.successful();
    }

    String summary() {
        return support.summary("externalInterop")
                + ", " + memoryProbe.summary("externalMemoryProbe")
                + ", " + semaphoreProbe.summary("externalSemaphoreProbe");
    }

    private record Support(
            boolean externalMemoryCore,
            boolean externalSemaphoreCore,
            boolean win32MemoryEnabled,
            boolean win32SemaphoreEnabled,
            boolean outputImageFormatQueried,
            boolean compatibleOpaqueWin32,
            boolean exportable,
            boolean importable,
            boolean dedicatedOnly,
            int externalMemoryFeatures,
            int compatibleHandleTypes,
            boolean gpuInteropCandidate,
            String reason
    ) {
        private Support {
            reason = reason == null ? "" : reason;
        }

        static Support unavailable(
                boolean externalMemoryCore,
                boolean externalSemaphoreCore,
                boolean win32MemoryEnabled,
                boolean win32SemaphoreEnabled,
                String reason
        ) {
            return new Support(
                    externalMemoryCore, externalSemaphoreCore, win32MemoryEnabled, win32SemaphoreEnabled,
                    false, false, false, false, false, 0, 0, false, reason
            );
        }

        boolean memoryExportReady() {
            return compatibleOpaqueWin32 && exportable;
        }

        boolean semaphoreExportCandidate() {
            return memoryExportReady() && externalSemaphoreCore && win32SemaphoreEnabled;
        }

        String summary(String name) {
            return name
                    + "{externalMemoryCore=" + externalMemoryCore
                    + ", externalSemaphoreCore=" + externalSemaphoreCore
                    + ", win32MemoryEnabled=" + win32MemoryEnabled
                    + ", win32SemaphoreEnabled=" + win32SemaphoreEnabled
                    + ", outputImageFormatQueried=" + outputImageFormatQueried
                    + ", compatibleOpaqueWin32=" + compatibleOpaqueWin32
                    + ", exportable=" + exportable
                    + ", importable=" + importable
                    + ", dedicatedOnly=" + dedicatedOnly
                    + ", externalMemoryFeatures=0x" + Integer.toHexString(externalMemoryFeatures)
                    + ", compatibleHandleTypes=0x" + Integer.toHexString(compatibleHandleTypes)
                    + ", gpuInteropCandidate=" + gpuInteropCandidate
                    + ", reason=" + reason
                    + "}";
        }
    }
}
