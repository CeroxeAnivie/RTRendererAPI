package top.ceroxe.rt.renderer.api.interop.vulkan;

import top.ceroxe.rt.renderer.api.Renderer;

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
            Renderer renderer,
            VulkanFramePresenterConfig configuration
    ) {
        Renderer checkedRenderer = Objects.requireNonNull(renderer, "renderer");
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
    PerformanceSnapshot performanceSnapshot();

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
     * An empty string disables the overlay.</p>
     *
     * @param text non-null text; line feeds request additional rows
     */
    void setOverlayText(String text);

    /**
     * Presents the next renderer-owned frame using the provider's GPU-timeline fast path.
     *
     * <p>This is the recommended simple mode: it can enqueue presentation before the producer
     * fence is observed by the CPU, while {@link VulkanFrameInterop#pollLatestFrame()} retains its
     * completed-frame contract for expert external-memory consumers.</p>
     *
     * @return presentation evidence, or empty when no submitted frame is available
     */
    Optional<PresentationResult> presentLatestFrame();

    /**
     * Presents the next ordinary Vulkan scene with the same GPU overlay operations as the
     * external-scene path. Up to seven completed full-size RGBA8_UNORM storage-readable layers
     * are applied after the scene's display transform and before the text HUD. ALPHA_OVER uses
     * premultiplied alpha. Unsupported composition is rejected rather than dropping layers.
     *
     * @param overlays exact generic resource mutations in final composition order
     * @return presentation evidence, or empty when no submitted scene frame is available
     */
    default Optional<PresentationResult> presentLatestFrame(
            java.util.List<top.ceroxe.rt.renderer.api.FrameCompositionPlan.Layer> overlays) {
        if (Objects.requireNonNull(overlays,"overlays").isEmpty()) return presentLatestFrame();
        throw new UnsupportedOperationException("This presenter cannot compose managed scenes with Vulkan layers");
    }

    /**
     * Imports, copies, presents, GPU-signals producer completion, and closes one active lease.
     * Ownership transfers even when this method throws.
     *
     * @param lease active lease transferred exclusively to this call
     * @return immutable evidence of the consumed frame and presentation outcome
     */
    PresentationResult presentAndRelease(GpuFrameLease lease);

    /**
     * GPU-imports a borrowed D3D12 texture/fence and presents through the existing Vulkan display
     * and HUD passes. The source is upstream tone-mapped RGBA8; no extra tone mapping is applied.
     * All producer waits and consumer completion signals occur on GPU queues. Minimized and
     * recreation outcomes still consume the ready signal and publish completion so no slot stalls.
     * Frame generation is not provided by this external-source path.
     * @param frame per-slot shared image and synchronization values, retained by its producer
     * @return outcome of this complete source frame
     */
    default PresentationResult presentD3D12SharedFrame(D3D12SharedFrame frame) {
        throw new UnsupportedOperationException("This presenter cannot import D3D12 shared frames");
    }

    /**
     * Composes the external scene with up to seven exact GPU textures produced by this Vulkan
     * renderer's ordinary/expert command lane. Uses its existing composition pipeline; sources
     * must be completed RGBA8_UNORM storage-readable full-size textures. ALPHA_OVER consumes
     * premultiplied alpha. Pins remain held until the consuming Vulkan submission completes.
     * Empty overlays use the direct-copy path. All layers are applied in listed order after the
     * external base image; no CPU scene readback is involved.
     * @param frame borrowed external complete-scene frame
     * @param overlays exact generic resource mutations and existing composition operations
     * @return final presentation outcome
     */
    default PresentationResult presentD3D12SharedFrame(D3D12SharedFrame frame,
            java.util.List<top.ceroxe.rt.renderer.api.FrameCompositionPlan.Layer> overlays) {
        if(java.util.Objects.requireNonNull(overlays,"overlays").isEmpty())return presentD3D12SharedFrame(frame);
        throw new UnsupportedOperationException("This presenter cannot compose external scenes with Vulkan layers");
    }

    /**
     * GPU-composes the same external scene and Vulkan layers, then writes the result back to the
     * borrowed shared image for final presentation by its D3D12 owner. Does not acquire or present
     * a Vulkan swapchain, draw its text HUD, read pixels on CPU or count a final presented frame.
     * The producer must GPU-wait completion before consuming the modified image. Its SDR format,
     * per-slot fence and borrowed-handle lifetime match {@link #presentD3D12SharedFrame}.
     *
     * <p>Up to seven exact completed RGBA8_UNORM overlay mutations use the existing composition
     * operations. Empty layers only hand the slot back through the fence, with no image/shader
     * work. Call {@link #retireD3D12SharedFrames()} before destroying production resources.</p>
     * @param frame borrowed complete-scene image to modify and release in GENERAL/EXTERNAL
     * @param overlays completed Vulkan texture mutations in composition order
     */
    default void composeD3D12SharedFrame(D3D12SharedFrame frame,
            java.util.List<top.ceroxe.rt.renderer.api.FrameCompositionPlan.Layer> overlays) {
        throw new UnsupportedOperationException("This presenter cannot compose back into D3D12 shared frames");
    }

    /**
     * Requests one diagnostic PNG of the next visible final frame, including composition and HUD.
     * Readback is performed only for this explicit request, before handing that image to WSI;
     * the visible-frame present waits for its capture to complete before returning. The request
     * stays pending while minimized. This is never part of production per-frame delivery.
     * @param png destination path for the one-shot final RGBA/BGRA-to-PNG capture
     */
    default void captureNextPresentedFrame(java.nio.file.Path png) {
        throw new UnsupportedOperationException("This presenter does not expose final-frame diagnostic capture");
    }

    /**
     * Drains previously submitted external-source work and releases cached D3D12 imports while
     * preserving the Vulkan window. Call before a source resize, backend switch or handle close.
     * This is a lifecycle boundary, not a per-frame operation. Borrowed handles are never closed.
     */
    default void retireD3D12SharedFrames() {
        throw new UnsupportedOperationException("This presenter cannot import D3D12 shared frames");
    }

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
