package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.vulkan.VkDevice;

import java.util.Objects;

/**
 * Internal capability carried through the public lease wrapper for the official presenter.
 *
 * <p>Expert consumers continue to see only the stable external-memory API. The provider-owned
 * presenter can additionally prove that the source image belongs to its exact logical device and
 * submit an ordered copy without exporting/importing either memory or a completion semaphore.</p>
 */
interface VulkanManagedFrameLease {
    NativeFrame managedNativeFrame();

    void releaseAfterManagedQueueSubmission();

    /** Publishes presenter-owned proof that all consumer access is complete or never began. */
    void releaseAfterPresenterAccessComplete();

    /** Borrowed native identity whose lifetime is bounded by the enclosing lease. */
    record NativeFrame(
            VkDevice device,
            long image,
            int queueFamilyIndex,
            boolean externallyOwned,
            long readyTimelineSemaphore,
            long readyTimelineValue
    ) {
        public NativeFrame {
            Objects.requireNonNull(device, "device");
            if (image == 0L) throw new IllegalArgumentException("image must not be null");
            if (queueFamilyIndex < 0) {
                throw new IllegalArgumentException("queueFamilyIndex must not be negative");
            }
            if ((readyTimelineSemaphore == 0L) != (readyTimelineValue == 0L)
                    || readyTimelineValue < 0L) {
                throw new IllegalArgumentException("managed ready timeline is inconsistent");
            }
        }
    }
}
