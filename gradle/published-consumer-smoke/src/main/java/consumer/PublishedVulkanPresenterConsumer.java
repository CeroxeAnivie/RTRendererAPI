package consumer;

import java.util.Objects;
import java.util.Optional;

import top.ceroxe.rt.renderer.api.RayTracingRenderer;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFrameInterop;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFramePresenter;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFramePresenterConfig;

/**
 * Compile-checked simple GPU presentation path for the staged Maven publication.
 *
 * <p>This consumer deliberately contains no raw Vulkan calls: the provider-bound presenter owns
 * external-memory import, synchronization, swapchain presentation, and lease retirement.</p>
 */
public final class PublishedVulkanPresenterConsumer {
    private PublishedVulkanPresenterConsumer() {
    }

    /**
     * Opens an uncapped, bounded-latency presenter on the renderer's physical GPU.
     *
     * @param renderer open renderer with managed presenter support
     * @param width positive initial framebuffer width
     * @param height positive initial framebuffer height
     * @return caller-owned thread-affine presenter
     */
    public static VulkanFramePresenter openUncapped(
            RayTracingRenderer renderer,
            int width,
            int height
    ) {
        return VulkanFramePresenter.open(
                Objects.requireNonNull(renderer, "renderer"),
                VulkanFramePresenterConfig.builder()
                        .title("RTRendererAPI published consumer")
                        .initialExtent(width, height)
                        .presentMode(VulkanFramePresenterConfig.PresentMode.UNCAPPED)
                        .maximumFramesQueuedAhead(2)
                        .build()
        );
    }

    /**
     * Attempts one submission without converting ordinary producer backpressure into an exception.
     * The caller may advance the request sequence only when this method returns {@code true}.
     *
     * @param renderer open renderer
     * @param request immutable request retained unchanged on deferral
     * @return {@code true} only when the backend admitted the request
     */
    public static boolean trySubmit(
            RayTracingRenderer renderer,
            RenderFrameRequest request
    ) {
        RayTracingRenderer.FrameSubmissionAttempt attempt = Objects.requireNonNull(
                renderer, "renderer"
        ).trySubmit(Objects.requireNonNull(request, "request"));
        return attempt instanceof RayTracingRenderer.FrameSubmitted;
    }

    /**
     * Presents at most one ready frame and preserves the presenter's exhaustive outcome.
     *
     * @param renderer renderer that produced the frame
     * @param presenter renderer-bound presenter on its owner thread
     * @return empty when no frame is ready, otherwise exact presentation evidence
     */
    public static Optional<VulkanFramePresenter.PresentationResult> presentLatest(
            RayTracingRenderer renderer,
            VulkanFramePresenter presenter
    ) {
        RayTracingRenderer checkedRenderer = Objects.requireNonNull(renderer, "renderer");
        VulkanFramePresenter checkedPresenter = Objects.requireNonNull(presenter, "presenter");
        VulkanFrameInterop interop = checkedRenderer.extension(VulkanFrameInterop.class)
                .orElseThrow(() -> new IllegalStateException("Vulkan frame interop is unavailable"));
        VulkanFrameInterop.FramePollResult result = interop.pollLatestFrame();
        if (result == VulkanFrameInterop.FrameNotReady.INSTANCE) return Optional.empty();
        VulkanFrameInterop.FrameAvailable available = (VulkanFrameInterop.FrameAvailable) result;
        return Optional.of(checkedPresenter.presentAndRelease(available.lease()));
    }
}
