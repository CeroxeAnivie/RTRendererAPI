package top.ceroxe.rt.renderer.api.interop.vulkan;

import java.util.Objects;

/**
 * Immutable window and swapchain policy for the managed Vulkan frame presenter.
 *
 * <p>The default uses FIFO presentation because it is universally supported and bounds GPU
 * production to the display refresh cycle. Applications that deliberately benchmark throughput
 * must opt into {@link PresentMode#UNCAPPED} explicitly.</p>
 */
public final class VulkanFramePresenterConfig {
    private final String title;
    private final int initialWidth;
    private final int initialHeight;
    private final boolean resizable;
    private final WindowMode windowMode;
    private final PresentMode presentMode;
    private final int maximumFramesQueuedAhead;

    private VulkanFramePresenterConfig(Builder builder) {
        title = requireTitle(builder.title);
        initialWidth = requireDimension(builder.initialWidth, "initialWidth");
        initialHeight = requireDimension(builder.initialHeight, "initialHeight");
        resizable = builder.resizable;
        windowMode = Objects.requireNonNull(builder.windowMode, "windowMode");
        presentMode = Objects.requireNonNull(builder.presentMode, "presentMode");
        maximumFramesQueuedAhead = requireQueuedFrames(builder.maximumFramesQueuedAhead);
    }

    /**
     * Returns a new builder with safe interactive defaults.
     *
     * @return new single-thread-confined builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the non-blank native window title.
     *
     * @return native window title
     */
    public String title() {
        return title;
    }

    /**
     * Returns the positive initial framebuffer width.
     *
     * @return initial width in pixels
     */
    public int initialWidth() {
        return initialWidth;
    }

    /**
     * Returns the positive initial framebuffer height.
     *
     * @return initial height in pixels
     */
    public int initialHeight() {
        return initialHeight;
    }

    /**
     * Reports whether the native window may be resized.
     *
     * @return resizable window policy
     */
    public boolean resizable() {
        return resizable;
    }

    /**
     * Returns whether the presenter creates a normal window or a native monitor window.
     *
     * @return native window attachment policy
     */
    public WindowMode windowMode() {
        return windowMode;
    }

    /**
     * Returns the requested swapchain pacing policy.
     *
     * @return presentation mode preference
     */
    public PresentMode presentMode() {
        return presentMode;
    }

    /**
     * Returns the maximum accepted producer frames not yet acquired for presentation.
     *
     * <p>This bounds latency and prevents an uncapped renderer queue from starving the swapchain
     * queue on the same physical GPU. It does not impose a time-based FPS limit.</p>
     *
     * @return bounded producer lead in {@code [1, 16]}
     */
    public int maximumFramesQueuedAhead() {
        return maximumFramesQueuedAhead;
    }

    /** Presentation pacing policy with explicit fallback semantics. */
    public enum PresentMode {
        /** Required Vulkan FIFO mode; bounds production to display refresh. */
        VSYNC,
        /** Prefers mailbox for low latency and falls back to FIFO. */
        LOW_LATENCY,
        /** Prefers immediate, then mailbox, then FIFO; intended only for explicit benchmarks. */
        UNCAPPED
    }

    /** Native window attachment policy. */
    public enum WindowMode {
        /** Ordinary composited desktop window. */
        WINDOWED,
        /** Native full-screen window on the primary monitor at its current video mode. */
        PRIMARY_MONITOR_FULLSCREEN
    }

    /** Single-thread-confined semantic builder. */
    public static final class Builder {
        private String title = "RTRendererAPI Vulkan Presenter";
        private int initialWidth = 1280;
        private int initialHeight = 720;
        private boolean resizable = true;
        private WindowMode windowMode = WindowMode.WINDOWED;
        private PresentMode presentMode = PresentMode.VSYNC;
        private int maximumFramesQueuedAhead = 2;

        private Builder() {
        }

        /**
         * Selects a non-blank native window title.
         *
         * @param value non-blank title
         * @return this builder
         */
        public Builder title(String value) {
            title = requireTitle(value);
            return this;
        }

        /**
         * Selects positive initial framebuffer dimensions.
         *
         * @param width  positive initial width in pixels
         * @param height positive initial height in pixels
         * @return this builder
         */
        public Builder initialExtent(int width, int height) {
            initialWidth = requireDimension(width, "width");
            initialHeight = requireDimension(height, "height");
            return this;
        }

        /**
         * Selects whether the native window may be resized.
         *
         * @param value resizable window policy
         * @return this builder
         */
        public Builder resizable(boolean value) {
            resizable = value;
            return this;
        }

        /**
         * Selects the native window attachment policy.
         *
         * @param value non-null window attachment policy
         * @return this builder
         */
        public Builder windowMode(WindowMode value) {
            windowMode = Objects.requireNonNull(value, "windowMode");
            return this;
        }

        /**
         * Selects an explicit swapchain pacing policy.
         *
         * @param value non-null presentation mode preference
         * @return this builder
         */
        public Builder presentMode(PresentMode value) {
            presentMode = Objects.requireNonNull(value, "presentMode");
            return this;
        }

        /**
         * Bounds producer work accepted ahead of the presenter without imposing an FPS cap.
         *
         * <p>Two permits one render and one presentation operation to overlap while preventing
         * disposable future frames from monopolizing the GPU. Larger values trade latency and
         * visible throughput for deeper benchmark-oriented buffering.</p>
         *
         * @param value queued-frame bound in {@code [1, 16]}
         * @return this builder
         */
        public Builder maximumFramesQueuedAhead(int value) {
            maximumFramesQueuedAhead = requireQueuedFrames(value);
            return this;
        }

        /**
         * Validates and returns an independent immutable configuration.
         *
         * @return validated presenter configuration
         */
        public VulkanFramePresenterConfig build() {
            return new VulkanFramePresenterConfig(this);
        }
    }

    private static String requireTitle(String value) {
        String checked = Objects.requireNonNull(value, "title");
        if (checked.isBlank()) throw new IllegalArgumentException("title must not be blank");
        return checked;
    }

    private static int requireDimension(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static int requireQueuedFrames(int value) {
        if (value < 1 || value > 16) {
            throw new IllegalArgumentException("maximumFramesQueuedAhead must be in [1, 16]");
        }
        return value;
    }
}
