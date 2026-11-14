package top.ceroxe.rt.renderer.nvidia;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import top.ceroxe.rt.renderer.api.FrameGenerationOptions;
import top.ceroxe.rt.renderer.feature.VulkanSwapchainInterceptor;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Objects;
import java.util.Optional;

/** Streamline WSI proxies required by the SDK's Vulkan manual-hooking contract. */
final class NvidiaStreamlineSwapchainInterceptor implements VulkanSwapchainInterceptor {
    private final NvidiaStreamlinePresentCircuitBreaker presentCircuitBreaker;
    private volatile boolean swapchainRebuildRequired;
    private volatile boolean proxyActive = true;

    NvidiaStreamlineSwapchainInterceptor(FrameGenerationOptions options) {
        FrameGenerationOptions checked = Objects.requireNonNull(options, "options");
        int configuredLimit = checked.multiplier().presentedFramesPerNativeFrame() - 1;
        int requestedGeneratedFrames = !checked.preference().requested()
                ? 0
                : checked.mode() == FrameGenerationOptions.Mode.ADAPTIVE
                ? -configuredLimit
                : configuredLimit;
        presentCircuitBreaker = new NvidiaStreamlinePresentCircuitBreaker(requestedGeneratedFrames);
    }

    @Override
    public boolean swapchainRebuildRequired() {
        return swapchainRebuildRequired;
    }

    @Override
    public boolean proxyActive() {
        return proxyActive;
    }

    @Override
    public void prepareOneWayFallback() {
        if (!swapchainRebuildRequired || !proxyActive) return;
        NvidiaStreamlineFrameGenerationRuntime.disableFrameGeneration();
        proxyActive = false;
    }

    @Override
    public void acknowledgeSwapchainRebuild() {
        swapchainRebuildRequired = false;
    }

    @Override
    public int createSwapchain(VkDevice device, VkSwapchainCreateInfoKHR createInfo, LongBuffer swapchain) {
        return NvidiaStreamlineFrameGenerationRuntime.createSwapchain(
                device.address(), createInfo.address(), MemoryUtil.memAddress(swapchain)
        );
    }

    @Override
    public void destroySwapchain(VkDevice device, long swapchain) {
        NvidiaStreamlineFrameGenerationRuntime.destroySwapchain(device.address(), swapchain);
    }

    @Override
    public int getSwapchainImages(VkDevice device, long swapchain, IntBuffer count, LongBuffer images) {
        return NvidiaStreamlineFrameGenerationRuntime.getSwapchainImages(
                device.address(), swapchain, MemoryUtil.memAddress(count),
                images == null ? 0L : MemoryUtil.memAddress(images)
        );
    }

    @Override
    public int acquireNextImage(
            VkDevice device,
            long swapchain,
            long timeout,
            long semaphore,
            long fence,
            IntBuffer imageIndex
    ) {
        return NvidiaStreamlineFrameGenerationRuntime.acquireNextImage(
                device.address(), swapchain, timeout, semaphore, fence, MemoryUtil.memAddress(imageIndex)
        );
    }

    @Override
    public int queuePresent(VkQueue queue, VkPresentInfoKHR presentInfo, long frameSequence) {
        if (frameSequence < 0L) throw new IllegalArgumentException("frameSequence must not be negative");
        int generatedFrames = presentCircuitBreaker.generatedFramesForPresent();
        try {
            int result = NvidiaStreamlineFrameGenerationRuntime.queuePresent(
                    queue.address(), presentInfo.address(), generatedFrames, frameSequence
            );
            if (presentCircuitBreaker.observeResult(result, generatedFrames, frameSequence)) {
                swapchainRebuildRequired = true;
            }
            return result;
        } catch (RuntimeException | LinkageError failure) {
            if (presentCircuitBreaker.observeFailure(generatedFrames, frameSequence, failure)) {
                swapchainRebuildRequired = true;
            }
            throw failure;
        }
    }

    @Override
    public void retireFrame(long frameSequence) {
        if (frameSequence < 0L) throw new IllegalArgumentException("frameSequence must not be negative");
        // A successful one-way fallback destroys the proxy only after releasing every tag. Once
        // that transaction completes there is no Streamline executor left to notify.
        if (proxyActive) NvidiaStreamlineFrameGenerationRuntime.retireFrame(frameSequence);
    }

    boolean frameGenerationPresented() {
        return frameGenerationEnabled() && frameGenerationStats().generatedFramesActuallyPresented() > 0L;
    }

    void disableGeneration() {
        if (presentCircuitBreaker.disable()) swapchainRebuildRequired = true;
    }

    boolean frameGenerationEnabled() {
        return presentCircuitBreaker.enabled();
    }

    int requestedGeneratedFrames() {
        return presentCircuitBreaker.requestedGeneratedFrames();
    }

    synchronized void reconfigure(FrameGenerationOptions options) {
        FrameGenerationOptions checked = Objects.requireNonNull(options, "options");
        int limit = checked.preference().requested()
                ? checked.multiplier().presentedFramesPerNativeFrame() - 1 : 0;
        int requested = checked.preference().requested()
                && checked.mode() == FrameGenerationOptions.Mode.ADAPTIVE ? -limit : limit;
        if (requested != 0 && !proxyActive) {
            throw new IllegalStateException("frame-generation proxy requires swapchain rebuild");
        }
        if (requested == 0 && presentCircuitBreaker.generatedFramesForPresent() != 0) {
            // The frame ring is drained before this call, so releasing every outstanding tag is
            // both sufficient and necessary before those images can return to ordinary reuse.
            NvidiaStreamlineFrameGenerationRuntime.disableFrameGeneration();
        }
        presentCircuitBreaker.reconfigure(requested);
    }

    Optional<NvidiaStreamlinePresentCircuitBreaker.FailureSnapshot> presentationFailure() {
        return presentCircuitBreaker.failureSnapshot();
    }

    NvidiaStreamlineFrameGenerationRuntime.Stats frameGenerationStats() {
        return NvidiaStreamlineFrameGenerationRuntime.stats();
    }
}
