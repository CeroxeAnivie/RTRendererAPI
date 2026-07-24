package top.ceroxe.mcvulkanrt.renderer.rt.device;

import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import top.ceroxe.mcvulkanrt.renderer.rt.device.interop.VulkanWin32ExternalMemoryProbe;
import top.ceroxe.mcvulkanrt.renderer.rt.device.interop.VulkanWin32ExternalSemaphoreProbe;
import top.ceroxe.mcvulkanrt.renderer.rt.pipeline.RtRayTracingPipeline;
import top.ceroxe.mcvulkanrt.renderer.rt.runtime.RtCore;

import java.util.Objects;

/** Owns Vulkan external-resource probe lifetimes and converts native failures into stable diagnostics. */
final class RtExternalInteropProbeOrchestrator {
    private RtExternalInteropProbeOrchestrator() {
    }

    static RtCore.ExternalMemoryInteropProbe probeMemory(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            boolean gpuInteropCandidate,
            String supportReason,
            boolean vulkanProbeSuccessful,
            String vulkanProbeReason,
            boolean dedicatedOnly
    ) {
        Objects.requireNonNull(physicalDevice, "physicalDevice");
        Objects.requireNonNull(device, "device");
        if (!gpuInteropCandidate) {
            return RtCore.ExternalMemoryInteropProbe.skipped(supportReason);
        }
        if (!vulkanProbeSuccessful) {
            return RtCore.ExternalMemoryInteropProbe.skipped(
                    "vulkanExternalMemoryProbeNotReady:" + vulkanProbeReason
            );
        }
        VulkanWin32ExternalMemoryProbe.ExportedImage exportedImage = null;
        try {
            exportedImage = VulkanWin32ExternalMemoryProbe.exportImage(
                    physicalDevice,
                    device,
                    RtRayTracingPipeline.bootstrapOutputFormat(),
                    dedicatedOnly
            );
            return RtCore.ExternalMemoryInteropProbe.success(
                    exportedImage.width(),
                    exportedImage.height(),
                    exportedImage.vulkanFormat(),
                    exportedImage.allocationSize(),
                    exportedImage.memoryTypeIndex(),
                    dedicatedOnly
            );
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            return RtCore.ExternalMemoryInteropProbe.failed(
                    failureReason(ex),
                    VulkanWin32ExternalMemoryProbe.PROBE_WIDTH,
                    VulkanWin32ExternalMemoryProbe.PROBE_HEIGHT,
                    RtRayTracingPipeline.bootstrapOutputFormat(),
                    exportedImage == null ? 0L : exportedImage.allocationSize(),
                    exportedImage == null ? -1 : exportedImage.memoryTypeIndex(),
                    dedicatedOnly,
                    exportedImage != null && exportedImage.handleClosed()
            );
        } finally {
            if (exportedImage != null) {
                exportedImage.close();
            }
        }
    }

    static RtCore.ExternalSemaphoreInteropProbe probeSemaphore(
            VkDevice device,
            boolean gpuInteropCandidate,
            String supportReason,
            boolean vulkanProbeSuccessful,
            String vulkanProbeReason
    ) {
        Objects.requireNonNull(device, "device");
        if (!gpuInteropCandidate) {
            return RtCore.ExternalSemaphoreInteropProbe.skipped(supportReason);
        }
        if (!vulkanProbeSuccessful) {
            return RtCore.ExternalSemaphoreInteropProbe.skipped(
                    "vulkanExternalSemaphoreProbeNotReady:" + vulkanProbeReason
            );
        }
        VulkanWin32ExternalSemaphoreProbe.ExportedSemaphore exportedSemaphore = null;
        try {
            exportedSemaphore = VulkanWin32ExternalSemaphoreProbe.exportSemaphore(device);
            return RtCore.ExternalSemaphoreInteropProbe.success(exportedSemaphore.handleType());
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            return RtCore.ExternalSemaphoreInteropProbe.failed(
                    failureReason(ex),
                    0,
                    false,
                    exportedSemaphore != null && exportedSemaphore.handleClosed()
            );
        } finally {
            if (exportedSemaphore != null) {
                exportedSemaphore.close();
            }
        }
    }

    private static String failureReason(Throwable failure) {
        return failure.getClass().getSimpleName() + ": " + Objects.toString(failure.getMessage(), "");
    }
}
