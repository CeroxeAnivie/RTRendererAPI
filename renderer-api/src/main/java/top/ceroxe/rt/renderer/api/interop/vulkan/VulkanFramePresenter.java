package top.ceroxe.rt.renderer.api.interop.vulkan;

import top.ceroxe.rt.renderer.api.RayTracingRenderer;

import java.util.Objects;
import java.util.Optional;

/**
 * Official Windows Vulkan swapchain consumer for {@link VulkanFrameInterop} leases.
 *
 * <p>The presenter is thread-affine: opening, event polling, presentation, and closing must occur
 * on the same thread. {@link #presentAndRelease} transfers exclusive ownership to the presenter,
 * which closes the lease before returning whenever cleanup succeeds. If vendor tag release or a
 * host retirement callback fails, the presenter retains the lease and retries it before accepting
 * another frame or completing {@link #close()}; the caller must never resume ownership after the
 * transfer. This makes the easy path safe without hiding native synchronization from callers that
 * use {@link VulkanFrameInterop} directly.</p>
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
     * Returns accumulated native presentation timings without resetting them.
     *
     * @return immutable point-in-time timing counters
     */
    default PerformanceSnapshot performanceSnapshot() {
        return new PerformanceSnapshot(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
    }

    /**
     * Replaces the native window title on the presenter thread.
     *
     * @param title non-blank native window title
     */
    void setTitle(String title);

    /**
     * Replaces the optional text drawn over the top-left of subsequently presented frames.
     *
     * <p>The managed presenter implements this as a tiny transfer-only overlay, so enabling a
     * diagnostic HUD does not enable CPU frame readback or add a graphics-pipeline dependency.
     * An empty string disables the overlay. Providers that do not support an overlay may retain
     * this binary-compatible default implementation.</p>
     *
     * @param text non-null text; line feeds request additional rows
     */
    default void setOverlayText(String text) {
        Objects.requireNonNull(text, "text");
    }

    /**
     * Presents the next renderer-owned frame using the provider's GPU-timeline fast path.
     *
     * <p>This is the recommended simple mode: it can enqueue presentation before the producer
     * fence is observed by the CPU, while {@link VulkanFrameInterop#pollLatestFrame()} retains its
     * completed-frame contract for expert external-memory consumers.</p>
     *
     * @return presentation evidence, or empty when no submitted frame is available
     */
    default Optional<PresentationResult> presentLatestFrame() {
        throw new UnsupportedOperationException("presenter does not own a managed frame source");
    }

    /**
     * Imports, copies, presents, GPU-signals producer completion, and closes one active lease.
     * Ownership transfers even when this method throws.
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

    /**
     * Point-in-time evidence separating platform waits from managed GPU copy execution.
     * Unknown GPU copy timing is represented by zero samples, never by a fabricated duration.
     *
     * @param acquireSamples number of measured swapchain acquire calls
     * @param acquireNanos accumulated host duration of measured acquire calls
     * @param presentSamples number of measured swapchain present calls
     * @param presentNanos accumulated queue-lock and native present duration
     * @param presentQueueLockNanos accumulated host wait for exclusive queue access
     * @param presentNativeCallNanos accumulated duration inside the native present call
     * @param managedCopyGpuSamples number of completed managed-copy GPU timing samples
     * @param managedCopyGpuNanos accumulated managed-copy GPU execution duration
     */
    record PerformanceSnapshot(
            long acquireSamples,
            long acquireNanos,
            long presentSamples,
            long presentNanos,
            long presentQueueLockNanos,
            long presentNativeCallNanos,
            long managedCopyGpuSamples,
            long managedCopyGpuNanos
    ) {
        /** Validates that all monotonic timing counters are non-negative. */
        public PerformanceSnapshot {
            if (acquireSamples < 0L || acquireNanos < 0L
                    || presentSamples < 0L || presentNanos < 0L
                    || presentQueueLockNanos < 0L || presentNativeCallNanos < 0L
                    || managedCopyGpuSamples < 0L || managedCopyGpuNanos < 0L) {
                throw new IllegalArgumentException("presentation timing counters must not be negative");
            }
        }

        /**
         * Computes the mean host-side swapchain acquire duration.
         *
         * @return average duration in milliseconds, or NaN without samples
         */
        public double averageAcquireMillis() {
            return acquireSamples == 0L ? Double.NaN : acquireNanos / 1_000_000.0 / acquireSamples;
        }

        /**
         * Computes the mean complete host-side present duration.
         *
         * @return average queue-lock plus native duration in milliseconds, or NaN without samples
         */
        public double averagePresentMillis() {
            return presentSamples == 0L ? Double.NaN : presentNanos / 1_000_000.0 / presentSamples;
        }

        /**
         * Computes the mean wait for exclusive host access to the presentation queue.
         *
         * @return average queue-lock wait in milliseconds, or NaN without present samples
         */
        public double averagePresentQueueLockMillis() {
            return presentSamples == 0L
                    ? Double.NaN
                    : presentQueueLockNanos / 1_000_000.0 / presentSamples;
        }

        /**
         * Computes the mean duration spent inside the native present call.
         *
         * @return average native duration in milliseconds, or NaN without samples
         */
        public double averagePresentNativeCallMillis() {
            return presentSamples == 0L
                    ? Double.NaN
                    : presentNativeCallNanos / 1_000_000.0 / presentSamples;
        }

        /**
         * Computes the mean GPU execution duration of the managed presentation copy.
         *
         * @return average GPU duration in milliseconds, or NaN without samples
         */
        public double averageManagedCopyGpuMillis() {
            return managedCopyGpuSamples == 0L
                    ? Double.NaN
                    : managedCopyGpuNanos / 1_000_000.0 / managedCopyGpuSamples;
        }
    }
}
