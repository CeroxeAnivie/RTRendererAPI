package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.rt.device.RtGpuImage;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

import java.util.Objects;

/**
 * Session-owned ping-pong radiance/geometry history with transactional layout state.
 */
final class VulkanTemporalHistory implements AutoCloseable {
    private static final int HISTORY_IMAGES = 2;
    private static final int HISTORY_BYTES_PER_PIXEL = 4 * 8;

    private final VulkanDeviceRuntime device;
    private final boolean enabled;
    private final RtGpuImage[] color = new RtGpuImage[HISTORY_IMAGES];
    private final RtGpuImage[] geometry = new RtGpuImage[HISTORY_IMAGES];
    private final boolean[] layoutInitialized = new boolean[HISTORY_IMAGES];

    private RtGpuImage disabledMotionSentinel;
    private boolean disabledMotionLayoutInitialized;
    private int width;
    private int height;
    private int nextOutputIndex;
    private long allocationVersion;
    private boolean closed;

    VulkanTemporalHistory(VulkanDeviceRuntime device, boolean enabled) {
        this.device = Objects.requireNonNull(device, "device");
        this.enabled = enabled;
        VulkanTemporalImageSupport.requireSupported(device.physicalDevice());
    }

    private static RuntimeException close(RuntimeException failure, RtGpuImage image) {
        if (image == null) return failure;
        try {
            image.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) return closeFailure;
            failure.addSuppressed(closeFailure);
        }
        return failure;
    }

    private static void closeSuppressing(Throwable failure, RtGpuImage image) {
        if (image == null) return;
        try {
            image.close();
        } catch (RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    boolean enabled() {
        return enabled;
    }

    boolean extentMatches(int width, int height) {
        requireOpen();
        int requiredWidth = enabled ? width : 1;
        int requiredHeight = enabled ? height : 1;
        return color[0] != null && this.width == requiredWidth && this.height == requiredHeight;
    }

    long requiredGrowthBytes(int width, int height) {
        requireOpen();
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("history extent must be positive");
        int requiredWidth = enabled ? width : 1;
        int requiredHeight = enabled ? height : 1;
        if (extentMatches(requiredWidth, requiredHeight)) return 0L;
        long requested = Math.multiplyExact(
                Math.multiplyExact((long) requiredWidth, requiredHeight),
                HISTORY_BYTES_PER_PIXEL
        );
        if (!enabled) requested = Math.addExact(requested, 4L);
        return Math.max(0L, requested - allocationSizeBytes());
    }

    void ensureExtent(int width, int height) {
        requireOpen();
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("history extent must be positive");
        int requiredWidth = enabled ? width : 1;
        int requiredHeight = enabled ? height : 1;
        if (color[0] != null && this.width == requiredWidth && this.height == requiredHeight) return;

        closeImages();
        this.width = requiredWidth;
        this.height = requiredHeight;
        nextOutputIndex = 0;
        java.util.Arrays.fill(layoutInitialized, false);
        disabledMotionLayoutInitialized = false;
        allocationVersion = Math.incrementExact(allocationVersion);
        try {
            for (int index = 0; index < HISTORY_IMAGES; index++) {
                color[index] = createHistoryImage();
                geometry[index] = createHistoryImage();
            }
            if (!enabled) {
                disabledMotionSentinel = RtGpuImage.createStorageImage(
                        device.device(), device.allocator(), 1, 1,
                        VulkanTemporalImageSupport.MOTION_FORMAT
                );
            }
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            closeImagesSuppressing(failure);
            throw failure;
        }
    }

    PreparedFrame prepareFrame(RtGpuImage motionImage, boolean motionLayoutInitialized) {
        requireOpen();
        if (color[0] == null) throw new IllegalStateException("temporal history extent is not allocated");
        RtGpuImage motion = enabled
                ? Objects.requireNonNull(motionImage, "motionImage")
                : Objects.requireNonNull(disabledMotionSentinel, "disabledMotionSentinel");
        int outputIndex = nextOutputIndex;
        int inputIndex = 1 - outputIndex;
        return new PreparedFrame(
                allocationVersion,
                outputIndex,
                color[inputIndex],
                color[outputIndex],
                geometry[inputIndex],
                geometry[outputIndex],
                motion,
                layoutInitialized[inputIndex],
                layoutInitialized[outputIndex],
                enabled ? motionLayoutInitialized : disabledMotionLayoutInitialized
        );
    }

    void commit(PreparedFrame prepared) {
        PreparedFrame checked = Objects.requireNonNull(prepared, "prepared");
        if (checked.allocationVersion() != allocationVersion
                || checked.outputIndex() != nextOutputIndex) {
            throw new IllegalStateException("temporal GPU frame was prepared against stale history");
        }
        layoutInitialized[0] = true;
        layoutInitialized[1] = true;
        if (!enabled) disabledMotionLayoutInitialized = true;
        nextOutputIndex = 1 - nextOutputIndex;
    }

    long allocationSizeBytes() {
        long total = 0L;
        for (RtGpuImage image : color) if (image != null) total = Math.addExact(total, image.allocationSize());
        for (RtGpuImage image : geometry) if (image != null) total = Math.addExact(total, image.allocationSize());
        if (disabledMotionSentinel != null) {
            total = Math.addExact(total, disabledMotionSentinel.allocationSize());
        }
        return total;
    }

    private RtGpuImage createHistoryImage() {
        return RtGpuImage.createStorageImage(
                device.device(), device.allocator(), width, height,
                VulkanTemporalImageSupport.HISTORY_FORMAT
        );
    }

    private void closeImages() {
        RuntimeException failure = null;
        for (int index = HISTORY_IMAGES - 1; index >= 0; index--) {
            failure = close(failure, geometry[index]);
            geometry[index] = null;
            failure = close(failure, color[index]);
            color[index] = null;
        }
        failure = close(failure, disabledMotionSentinel);
        disabledMotionSentinel = null;
        disabledMotionLayoutInitialized = false;
        if (failure != null) throw failure;
    }

    private void closeImagesSuppressing(Throwable failure) {
        for (int index = HISTORY_IMAGES - 1; index >= 0; index--) {
            closeSuppressing(failure, geometry[index]);
            geometry[index] = null;
            closeSuppressing(failure, color[index]);
            color[index] = null;
        }
        closeSuppressing(failure, disabledMotionSentinel);
        disabledMotionSentinel = null;
        disabledMotionLayoutInitialized = false;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("temporal history is closed");
    }

    @Override
    public void close() {
        if (closed) return;
        closeImages();
        closed = true;
    }

    record PreparedFrame(
            long allocationVersion,
            int outputIndex,
            RtGpuImage colorInput,
            RtGpuImage colorOutput,
            RtGpuImage geometryInput,
            RtGpuImage geometryOutput,
            RtGpuImage motionOutput,
            boolean inputLayoutInitialized,
            boolean outputLayoutInitialized,
            boolean motionLayoutInitialized
    ) {
        PreparedFrame {
            if (allocationVersion <= 0L || outputIndex < 0 || outputIndex >= HISTORY_IMAGES) {
                throw new IllegalArgumentException("temporal frame identity is invalid");
            }
            colorInput = Objects.requireNonNull(colorInput, "colorInput");
            colorOutput = Objects.requireNonNull(colorOutput, "colorOutput");
            geometryInput = Objects.requireNonNull(geometryInput, "geometryInput");
            geometryOutput = Objects.requireNonNull(geometryOutput, "geometryOutput");
            motionOutput = Objects.requireNonNull(motionOutput, "motionOutput");
            if (colorInput == colorOutput || geometryInput == geometryOutput) {
                throw new IllegalArgumentException("temporal input and output images must not alias");
            }
        }
    }
}
