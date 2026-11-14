package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.vulkan.VK10;
import top.ceroxe.rt.renderer.feature.VulkanFrameReconstructionResourceContract;
import top.ceroxe.rt.renderer.rt.device.RtGpuImage;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.pipeline.VulkanFrameExtents;

import java.util.Objects;

/**
 * Owns the complete renderer-side input set consumed by a reconstruction implementation.
 *
 * <p>This owner is intentionally separate from a submission slot. A slot tracks when a bounded
 * submission becomes reusable; this type tracks image formats, layouts, replacement, and teardown
 * for one reconstruction input set. Replacement is transactional so a failed resize never leaves
 * a descriptor generation pointing at a partially-created image set.</p>
 */
final class VulkanFrameReconstructionResources implements AutoCloseable {
    private static final int INPUT_COLOR_FORMAT = VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
    private static final int DEPTH_FORMAT = VK10.VK_FORMAT_R32_SFLOAT;
    private static final int MOTION_FORMAT = VK10.VK_FORMAT_R16G16_SFLOAT;
    private static final int EXPOSURE_FORMAT = VK10.VK_FORMAT_R32_SFLOAT;

    private final VulkanDeviceRuntime device;
    private RtGpuImage inputColor;
    private RtGpuImage depth;
    private RtGpuImage motion;
    private RtGpuImage exposure;
    private boolean layoutsInitialized;
    private boolean closed;

    VulkanFrameReconstructionResources(VulkanDeviceRuntime device) {
        this.device = Objects.requireNonNull(device, "device");
    }

    synchronized void ensureExtent(VulkanFrameExtents extents) {
        requireOpen();
        VulkanFrameExtents checked = Objects.requireNonNull(extents, "extents");
        if (matchesExtent(checked)) return;

        RtGpuImage replacementColor = null;
        RtGpuImage replacementDepth = null;
        RtGpuImage replacementMotion = null;
        RtGpuImage replacementExposure = null;
        try {
            replacementColor = image(checked.renderWidth(), checked.renderHeight(), INPUT_COLOR_FORMAT);
            replacementDepth = image(checked.renderWidth(), checked.renderHeight(), DEPTH_FORMAT);
            replacementMotion = image(checked.renderWidth(), checked.renderHeight(), MOTION_FORMAT);
            // Exposure is frame-global, not a per-pixel image. A one-texel image preserves the
            // Vulkan image-tag contract without wasting a render-resolution allocation.
            replacementExposure = image(1, 1, EXPOSURE_FORMAT);
        } catch (RuntimeException | Error failure) {
            closeSuppressing(replacementExposure, failure);
            closeSuppressing(replacementMotion, failure);
            closeSuppressing(replacementDepth, failure);
            closeSuppressing(replacementColor, failure);
            throw failure;
        }

        RtGpuImage previousColor = inputColor;
        RtGpuImage previousDepth = depth;
        RtGpuImage previousMotion = motion;
        RtGpuImage previousExposure = exposure;
        inputColor = replacementColor;
        depth = replacementDepth;
        motion = replacementMotion;
        exposure = replacementExposure;
        layoutsInitialized = false;

        RuntimeException failure = null;
        failure = closeCollecting(previousExposure, failure);
        failure = closeCollecting(previousMotion, failure);
        failure = closeCollecting(previousDepth, failure);
        failure = closeCollecting(previousColor, failure);
        if (failure != null) throw failure;
    }

    synchronized boolean matchesExtent(VulkanFrameExtents extents) {
        requireOpen();
        VulkanFrameExtents checked = Objects.requireNonNull(extents, "extents");
        return inputColor != null
                && inputColor.width() == checked.renderWidth() && inputColor.height() == checked.renderHeight()
                && depth != null && depth.width() == checked.renderWidth() && depth.height() == checked.renderHeight()
                && motion != null && motion.width() == checked.renderWidth() && motion.height() == checked.renderHeight()
                && exposure != null && exposure.width() == 1 && exposure.height() == 1;
    }

    synchronized long requiredGrowthBytes(VulkanFrameExtents extents) {
        requireOpen();
        if (matchesExtent(extents)) return 0L;
        return requiredBytes(extents);
    }

    static long requiredBytes(VulkanFrameExtents extents) {
        VulkanFrameExtents checked = Objects.requireNonNull(extents, "extents");
        long pixels = Math.multiplyExact((long) checked.renderWidth(), checked.renderHeight());
        // R16G16B16A16 input color (8) + R32 depth (4) + R16G16 motion (4), plus R32 exposure.
        return Math.addExact(Math.multiplyExact(pixels, 16L), Integer.BYTES);
    }

    synchronized RtGpuImage inputColor() { return requireImage(inputColor, "input color"); }

    synchronized RtGpuImage depth() { return requireImage(depth, "depth"); }

    synchronized RtGpuImage motion() { return requireImage(motion, "motion"); }

    synchronized RtGpuImage exposure() { return requireImage(exposure, "exposure"); }

    /** Publishes the typed native boundary only after all four independently-owned images exist. */
    synchronized VulkanFrameReconstructionResourceContract contract() {
        return new VulkanFrameReconstructionResourceContract(
                image(inputColor()), image(depth()), image(motion()), image(exposure())
        );
    }

    synchronized top.ceroxe.rt.renderer.rt.pipeline.GpuSceneDescriptorResources.ReconstructionImageViews descriptorViews() {
        return new top.ceroxe.rt.renderer.rt.pipeline.GpuSceneDescriptorResources.ReconstructionImageViews(
                depth().imageView(), motion().imageView(), exposure().imageView()
        );
    }

    synchronized boolean layoutsInitialized() {
        requireOpen();
        return layoutsInitialized;
    }

    synchronized void markLayoutsInitialized() {
        requireOpen();
        requireImage(inputColor, "input color");
        layoutsInitialized = true;
    }

    synchronized long allocationSizeBytes() {
        return Math.addExact(
                Math.addExact(inputColor().allocationSize(), depth().allocationSize()),
                Math.addExact(motion().allocationSize(), exposure().allocationSize())
        );
    }

    private RtGpuImage image(int width, int height, int format) {
        // Reconstruction inputs are authored through storage descriptors by the ray-generation
        // pass, then sampled by Streamline. Both usages must exist at VkImage creation time;
        // publishing only storage usage lets tagging succeed while DLSS reads undefined data.
        return RtGpuImage.createStorageSampledImage(device.device(), device.allocator(), width, height, format);
    }

    private static VulkanFrameReconstructionResourceContract.Image image(RtGpuImage image) {
        return new VulkanFrameReconstructionResourceContract.Image(
                image.image(), image.memory(), image.imageView(), image.format(), image.width(), image.height(), image.usageFlags()
        );
    }

    private RtGpuImage requireImage(RtGpuImage image, String name) {
        requireOpen();
        if (image == null) throw new IllegalStateException("reconstruction resources have no " + name + " image");
        return image;
    }

    private static void closeSuppressing(AutoCloseable resource, Throwable failure) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static RuntimeException closeCollecting(AutoCloseable resource, RuntimeException failure) {
        if (resource == null) return failure;
        try {
            resource.close();
            return failure;
        } catch (RuntimeException closeFailure) {
            if (failure == null) return closeFailure;
            failure.addSuppressed(closeFailure);
            return failure;
        } catch (Exception closeFailure) {
            RuntimeException wrapped = new IllegalStateException("reconstruction resource close failed", closeFailure);
            if (failure == null) return wrapped;
            failure.addSuppressed(wrapped);
            return failure;
        }
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("reconstruction resources are closed");
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        RtGpuImage closingExposure = exposure;
        RtGpuImage closingMotion = motion;
        RtGpuImage closingDepth = depth;
        RtGpuImage closingColor = inputColor;
        exposure = null;
        motion = null;
        depth = null;
        inputColor = null;
        layoutsInitialized = false;
        closed = true;
        RuntimeException failure = null;
        failure = closeCollecting(closingExposure, failure);
        failure = closeCollecting(closingMotion, failure);
        failure = closeCollecting(closingDepth, failure);
        failure = closeCollecting(closingColor, failure);
        if (failure != null) throw failure;
    }
}
