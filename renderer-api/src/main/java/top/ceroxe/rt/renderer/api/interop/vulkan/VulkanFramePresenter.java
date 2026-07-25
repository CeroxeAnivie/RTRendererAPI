package top.ceroxe.rt.renderer.api.interop.vulkan;

import top.ceroxe.rt.renderer.api.RayTracingRenderer;

import java.util.Objects;

/**
 * Official Windows Vulkan swapchain consumer for {@link VulkanFrameInterop} leases.
 *
 * <p>The presenter is thread-affine: opening, event polling, presentation, and closing must occur
 * on the same thread. {@link #presentAndRelease} always consumes the supplied lease and leaves it
 * closed, including on swapchain recreation or failure. This makes the easy path safe without
 * hiding native synchronization from callers that use {@link VulkanFrameInterop} directly.</p>
 */
public interface VulkanFramePresenter extends AutoCloseable {
    /**
     * Opens the renderer provider's presenter on the renderer's exact physical GPU.
     *
     * @param renderer      open renderer whose expert presenter factory is used
     * @param configuration immutable native window and swapchain policy
     * @return newly owned thread-affine presenter
     * @throws UnsupportedOperationException if the provider has no managed Vulkan presenter
     */
    static VulkanFramePresenter open(
            RayTracingRenderer renderer,
            VulkanFramePresenterConfig configuration
    ) {
        RayTracingRenderer checkedRenderer = Objects.requireNonNull(renderer, "renderer");
        VulkanFramePresenterFactory factory = checkedRenderer
                .extension(VulkanFramePresenterFactory.class)
                .orElseThrow(() -> new UnsupportedOperationException(
                        "renderer does not provide the managed Vulkan frame presenter"));
        return Objects.requireNonNull(
                factory.openPresenter(Objects.requireNonNull(configuration, "configuration")),
                "presenter factory result"
        );
    }

    /** Pumps native window events without blocking. */
    void pollEvents();

    /**
     * Returns an atomic snapshot of close and framebuffer state.
     *
     * @return current native window state
     */
    WindowState windowState();

    /**
     * Returns the Vulkan present mode actually selected after applying platform fallbacks.
     *
     * @return active swapchain present mode
     */
    SwapchainPresentMode activePresentMode();

    /**
     * Replaces the native window title on the presenter thread.
     *
     * @param title non-blank native window title
     */
    void setTitle(String title);

    /**
     * Imports, copies, presents, GPU-signals producer completion, and closes one active lease.
     *
     * @param lease active lease transferred exclusively to this call
     * @return immutable evidence of the consumed frame and presentation outcome
     */
    PresentationResult presentAndRelease(GpuFrameLease lease);

    /** Releases swapchain, imported images, device, surface, window, and GLFW ownership. */
    @Override
    void close();

    /**
     * Atomic native window state. Zero extent means the window is minimized.
     *
     * @param closeRequested   whether the platform close action was requested
     * @param framebufferWidth non-negative framebuffer width, or zero while minimized
     * @param framebufferHeight non-negative framebuffer height, or zero while minimized
     */
    record WindowState(boolean closeRequested, int framebufferWidth, int framebufferHeight) {
        /** Validates a window state snapshot. */
        public WindowState {
            if (framebufferWidth < 0 || framebufferHeight < 0) {
                throw new IllegalArgumentException("framebuffer dimensions must not be negative");
            }
            if ((framebufferWidth == 0) != (framebufferHeight == 0)) {
                throw new IllegalArgumentException("a minimized framebuffer must have zero width and height");
            }
        }

        /**
         * Reports whether native presentation is currently possible.
         *
         * @return {@code true} when the framebuffer has a positive extent
         */
        public boolean drawable() {
            return framebufferWidth > 0;
        }
    }

    /**
     * Outcome for one lease that the presenter has consumed and closed.
     *
     * @param frameSequence non-negative consumed renderer frame sequence
     * @param sourceWidth   positive imported image width
     * @param sourceHeight  positive imported image height
     * @param outcome       exhaustive presentation outcome
     */
    record PresentationResult(
            long frameSequence,
            int sourceWidth,
            int sourceHeight,
            Outcome outcome
    ) {
        /** Validates immutable presentation evidence. */
        public PresentationResult {
            if (frameSequence < 0L || sourceWidth <= 0 || sourceHeight <= 0) {
                throw new IllegalArgumentException("presentation source identity must be positive");
            }
            outcome = Objects.requireNonNull(outcome, "outcome");
        }
    }

    /** Exhaustive result of consuming one frame lease. */
    enum Outcome {
        /** Frame reached the platform presentation queue. */
        PRESENTED,
        /** Window was minimized, so the lease was safely released without presentation. */
        SKIPPED_MINIMIZED,
        /** Swapchain changed and the frame was safely retired while recreation completed. */
        RETIRED_FOR_RECREATE
    }

    /** Native swapchain present mode selected by the platform-facing implementation. */
    enum SwapchainPresentMode {
        /** Vertically synchronized FIFO queue. */
        FIFO,
        /** Latest-frame mailbox queue. */
        MAILBOX,
        /** Unsynchronized immediate presentation. */
        IMMEDIATE
    }
}
