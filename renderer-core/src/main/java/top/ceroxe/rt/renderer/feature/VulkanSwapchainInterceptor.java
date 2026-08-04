package top.ceroxe.rt.renderer.feature;

import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

/**
 * Optional vendor-owned Vulkan WSI proxy used by manual-hooking integrations.
 *
 * <p>The official presenter remains the sole swapchain owner. This boundary only replaces the
 * five WSI entry points that a feature SDK must observe; it neither creates a second presentation
 * thread nor changes ownership of the application's queues.</p>
 */
public interface VulkanSwapchainInterceptor {
    /**
     * Reports a one-way vendor failure transition that must rebuild the proxy swapchain before
     * presentation continues. The presenter acknowledges it only after recreation succeeds.
     *
     * @return {@code true} when the current proxy swapchain must be rebuilt
     */
    default boolean swapchainRebuildRequired() {
        return false;
    }

    /**
     * Returns whether newly created swapchains must still use the vendor proxy path.
     *
     * @return {@code true} while the vendor proxy remains selected
     */
    default boolean proxyActive() {
        return true;
    }

    /**
     * Performs a requested one-way fallback after the old proxy swapchain has been destroyed and
     * before its native replacement is created.
     */
    default void prepareOneWayFallback() {
    }

    /** Confirms that the requested proxy rebuild completed successfully. */
    default void acknowledgeSwapchainRebuild() {
    }

    /**
     * Creates a swapchain through the vendor's manual-hooking proxy.
     * @param device borrowed logical device
     * @param createInfo borrowed swapchain creation descriptor
     * @param swapchain output storage for the created handle
     * @return Vulkan result code
     */
    int createSwapchain(VkDevice device, VkSwapchainCreateInfoKHR createInfo, LongBuffer swapchain);

    /**
     * Destroys a proxy-created swapchain.
     * @param device borrowed logical device
     * @param swapchain non-zero swapchain handle
     */
    void destroySwapchain(VkDevice device, long swapchain);

    /**
     * Queries proxy-owned swapchain images.
     * @param device borrowed logical device
     * @param swapchain non-zero swapchain handle
     * @param count in/out image count
     * @param images optional output image-handle storage
     * @return Vulkan result code
     */
    int getSwapchainImages(VkDevice device, long swapchain, IntBuffer count, LongBuffer images);

    /**
     * Acquires the next proxy-owned swapchain image.
     * @param device borrowed logical device
     * @param swapchain non-zero swapchain handle
     * @param timeout Vulkan acquisition timeout in nanoseconds
     * @param semaphore optional signal semaphore
     * @param fence optional signal fence
     * @param imageIndex output storage for the acquired image index
     * @return Vulkan result code
     */
    int acquireNextImage(
            VkDevice device,
            long swapchain,
            long timeout,
            long semaphore,
            long fence,
            IntBuffer imageIndex
    );

    /**
     * Presents one application-rendered frame through the vendor proxy.
     *
     * <p>The sequence is the renderer frame whose image is referenced by {@code presentInfo}. It
     * lets frame-generation integrations associate present-time bookkeeping and latency markers
     * with the same frame token used while tagging depth, motion, and HUD-less color.</p>
     *
     * @param queue borrowed presentation queue under external host synchronization
     * @param presentInfo borrowed Vulkan presentation descriptor
     * @param frameSequence non-negative renderer frame identity
     * @return Vulkan result code returned by the vendor proxy
     */
    int queuePresent(VkQueue queue, VkPresentInfoKHR presentInfo, long frameSequence);

    /**
     * Releases every vendor frame-token reference up to and including one retired renderer frame.
     *
     * <p>The presenter invokes this boundary before returning a non-presented lease to the frame
     * ring. Implementations must be idempotent because a proxy present may already have retired
     * the same token before a later diagnostic operation failed.</p>
     *
     * @param frameSequence non-negative renderer frame identity
     */
    default void retireFrame(long frameSequence) {
        if (frameSequence < 0L) throw new IllegalArgumentException("frameSequence must not be negative");
    }
}
