package top.ceroxe.rt.renderer.rt.device;

import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import top.ceroxe.rt.renderer.rt.runtime.RtCore;

import java.util.Objects;

/**
 * Immutable capability and diagnostic boundary for Vulkan shared-frame interop.
 */
final class VulkanRtExternalFrameInterop {
    private final VkPhysicalDevice physicalDevice;
    private final VkDevice device;
    private final RtExternalInteropCapabilities capabilities;

    VulkanRtExternalFrameInterop(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            RtExternalInteropCapabilities capabilities
    ) {
        this.physicalDevice = Objects.requireNonNull(physicalDevice, "physicalDevice");
        this.device = Objects.requireNonNull(device, "device");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    }

    static boolean sharedPresentationReady(
            boolean gpuInteropCandidate,
            boolean externalMemoryProbeSuccessful,
            boolean externalSemaphoreProbeSuccessful
    ) {
        return gpuInteropCandidate
                && externalMemoryProbeSuccessful
                && externalSemaphoreProbeSuccessful;
    }

    boolean sharedPresentationReady() {
        return sharedPresentationReady(
                capabilities.gpuInteropCandidate(),
                capabilities.memoryProbe().successful(),
                capabilities.semaphoreProbe().successful()
        );
    }

    boolean dedicatedAllocationRequired() {
        return capabilities.dedicatedOnly();
    }

    RtCore.ExternalMemoryInteropProbe probeExternalMemoryInterop() {
        return RtExternalInteropProbeOrchestrator.probeMemory(
                physicalDevice,
                device,
                capabilities.gpuInteropCandidate(),
                capabilities.reason(),
                capabilities.memoryProbe().successful(),
                capabilities.memoryProbe().reason(),
                capabilities.dedicatedOnly()
        );
    }

    RtCore.ExternalSemaphoreInteropProbe probeExternalSemaphoreInterop() {
        return RtExternalInteropProbeOrchestrator.probeSemaphore(
                device,
                capabilities.gpuInteropCandidate(),
                capabilities.reason(),
                capabilities.semaphoreProbe().successful(),
                capabilities.semaphoreProbe().reason()
        );
    }

    String summary() {
        return capabilities.summary();
    }
}
