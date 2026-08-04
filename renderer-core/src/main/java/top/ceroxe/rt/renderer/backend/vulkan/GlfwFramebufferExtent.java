package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/** Keeps GLFW window coordinates and Vulkan framebuffer pixels separate on scaled desktops. */
final class GlfwFramebufferExtent {
    private static final int MAX_CORRECTION_ATTEMPTS = 3;
    private static final int DPI_ROUNDING_TOLERANCE = 1;

    private GlfwFramebufferExtent() {
    }

    static Extent initialWindowExtent(long monitor, int framebufferWidth, int framebufferHeight) {
        requirePositive(framebufferWidth, "framebufferWidth");
        requirePositive(framebufferHeight, "framebufferHeight");
        if (monitor == 0L) return new Extent(framebufferWidth, framebufferHeight);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer xScale = stack.floats(1.0F);
            FloatBuffer yScale = stack.floats(1.0F);
            GLFW.glfwGetMonitorContentScale(monitor, xScale, yScale);
            return new Extent(
                    scaleDown(framebufferWidth, xScale.get(0)),
                    scaleDown(framebufferHeight, yScale.get(0))
            );
        }
    }

    static void normalizeWindowedFramebuffer(long window, int targetWidth, int targetHeight) {
        if (window == 0L) throw new IllegalArgumentException("window must not be null");
        requirePositive(targetWidth, "targetWidth");
        requirePositive(targetHeight, "targetHeight");

        for (int attempt = 0; attempt < MAX_CORRECTION_ATTEMPTS; attempt++) {
            Measurement current = measure(window);
            if (current.matches(targetWidth, targetHeight)) return;
            Extent corrected = correctedWindowExtent(current, targetWidth, targetHeight);
            if (corrected.width() == current.windowWidth()
                    && corrected.height() == current.windowHeight()) {
                return;
            }
            GLFW.glfwSetWindowSize(window, corrected.width(), corrected.height());
            GLFW.glfwPollEvents();
        }
    }

    private static Measurement measure(long window) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer windowWidth = stack.ints(0);
            IntBuffer windowHeight = stack.ints(0);
            IntBuffer framebufferWidth = stack.ints(0);
            IntBuffer framebufferHeight = stack.ints(0);
            GLFW.glfwGetWindowSize(window, windowWidth, windowHeight);
            GLFW.glfwGetFramebufferSize(window, framebufferWidth, framebufferHeight);
            return new Measurement(
                    requirePositive(windowWidth.get(0), "GLFW window width"),
                    requirePositive(windowHeight.get(0), "GLFW window height"),
                    requirePositive(framebufferWidth.get(0), "GLFW framebuffer width"),
                    requirePositive(framebufferHeight.get(0), "GLFW framebuffer height")
            );
        }
    }

    private static Extent correctedWindowExtent(
            Measurement current,
            int targetWidth,
            int targetHeight
    ) {
        return new Extent(
                scaleByRatio(targetWidth, current.windowWidth(), current.framebufferWidth()),
                scaleByRatio(targetHeight, current.windowHeight(), current.framebufferHeight())
        );
    }

    private static int scaleDown(int value, float scale) {
        if (!Float.isFinite(scale) || scale <= 0.0F) return value;
        return Math.max(1, Math.round(value / scale));
    }

    private static int scaleByRatio(int target, int window, int framebuffer) {
        long numerator = Math.multiplyExact((long) target, window);
        return Math.max(1, Math.toIntExact((numerator + framebuffer / 2L) / framebuffer));
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) throw new IllegalStateException(name + " must be positive, got " + value);
        return value;
    }

    record Extent(int width, int height) {
        Extent {
            requirePositive(width, "width");
            requirePositive(height, "height");
        }
    }

    private record Measurement(
            int windowWidth,
            int windowHeight,
            int framebufferWidth,
            int framebufferHeight
    ) {
        boolean matches(int targetWidth, int targetHeight) {
            return Math.abs(framebufferWidth - targetWidth) <= DPI_ROUNDING_TOLERANCE
                    && Math.abs(framebufferHeight - targetHeight) <= DPI_ROUNDING_TOLERANCE;
        }
    }
}
