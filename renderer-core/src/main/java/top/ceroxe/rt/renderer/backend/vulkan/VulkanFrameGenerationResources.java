package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.vulkan.VK10;
import top.ceroxe.rt.renderer.feature.VulkanFrameGenerationResourceContract;
import top.ceroxe.rt.renderer.feature.VulkanFrameReconstructionResourceContract;
import top.ceroxe.rt.renderer.rt.device.RtGpuImage;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.pipeline.VulkanFrameExtents;
import top.ceroxe.rt.renderer.rt.pipeline.GpuSceneDescriptorResources;

import java.util.Objects;

/** Owns the dedicated depth/motion signal pair used when DLSS-G runs without reconstruction. */
final class VulkanFrameGenerationResources implements AutoCloseable {
    private final VulkanDeviceRuntime device;
    private RtGpuImage depth;
    private RtGpuImage motion;
    private RtGpuImage exposure;
    private boolean layoutsInitialized;
    private boolean closed;

    VulkanFrameGenerationResources(VulkanDeviceRuntime device) {
        this.device = Objects.requireNonNull(device, "device");
    }

    synchronized void ensureExtent(VulkanFrameExtents extents) {
        requireOpen();
        VulkanFrameExtents checked = Objects.requireNonNull(extents, "extents");
        if (matchesExtent(checked)) return;
        RtGpuImage replacementDepth = null;
        RtGpuImage replacementMotion = null;
        RtGpuImage replacementExposure = null;
        try {
            replacementDepth = create(checked.renderWidth(), checked.renderHeight(), VK10.VK_FORMAT_R32_SFLOAT);
            replacementMotion = create(checked.renderWidth(), checked.renderHeight(), VK10.VK_FORMAT_R16G16_SFLOAT);
            replacementExposure = create(1, 1, VK10.VK_FORMAT_R32_SFLOAT);
        } catch (RuntimeException | Error failure) {
            closeSuppressing(replacementExposure, failure);
            closeSuppressing(replacementMotion, failure);
            closeSuppressing(replacementDepth, failure);
            throw failure;
        }
        RtGpuImage previousDepth = depth;
        RtGpuImage previousMotion = motion;
        RtGpuImage previousExposure = exposure;
        depth = replacementDepth;
        motion = replacementMotion;
        exposure = replacementExposure;
        layoutsInitialized = false;
        RuntimeException failure = null;
        failure = closeCollecting(previousExposure, failure);
        failure = closeCollecting(previousMotion, failure);
        failure = closeCollecting(previousDepth, failure);
        if (failure != null) throw failure;
    }

    synchronized boolean matchesExtent(VulkanFrameExtents extents) {
        requireOpen();
        VulkanFrameExtents checked = Objects.requireNonNull(extents, "extents");
        return depth != null && depth.width() == checked.renderWidth() && depth.height() == checked.renderHeight()
                && motion != null && motion.width() == checked.renderWidth()
                && motion.height() == checked.renderHeight()
                && exposure != null && exposure.width() == 1 && exposure.height() == 1;
    }

    static long requiredBytes(VulkanFrameExtents extents) {
        VulkanFrameExtents checked = Objects.requireNonNull(extents, "extents");
        return Math.addExact(Math.multiplyExact(
                Math.multiplyExact((long) checked.renderWidth(), checked.renderHeight()),
                8L
        ), Integer.BYTES);
    }

    synchronized VulkanFrameGenerationResourceContract contract() {
        return new VulkanFrameGenerationResourceContract(image(depth()), image(motion()), image(exposure()));
    }

    synchronized GpuSceneDescriptorResources.ReconstructionImageViews descriptorViews() {
        return new GpuSceneDescriptorResources.ReconstructionImageViews(
                depth().imageView(), motion().imageView(), exposure().imageView()
        );
    }

    synchronized RtGpuImage depth() { return requireImage(depth, "depth"); }

    synchronized RtGpuImage motion() { return requireImage(motion, "motion"); }

    synchronized RtGpuImage exposure() { return requireImage(exposure, "exposure"); }

    synchronized boolean layoutsInitialized() {
        requireOpen();
        return layoutsInitialized;
    }

    synchronized void markLayoutsInitialized() {
        depth();
        motion();
        exposure();
        layoutsInitialized = true;
    }

    synchronized long allocationSizeBytes() {
        return Math.addExact(
                Math.addExact(depth().allocationSize(), motion().allocationSize()),
                exposure().allocationSize()
        );
    }

    private RtGpuImage create(int width, int height, int format) {
        return RtGpuImage.createStorageSampledImage(device.device(), device.allocator(), width, height, format);
    }

    private static VulkanFrameReconstructionResourceContract.Image image(RtGpuImage source) {
        return new VulkanFrameReconstructionResourceContract.Image(
                source.image(), source.memory(), source.imageView(), source.format(),
                source.width(), source.height(), source.usageFlags()
        );
    }

    private RtGpuImage requireImage(RtGpuImage image, String name) {
        requireOpen();
        if (image == null) throw new IllegalStateException("frame generation has no " + name + " image");
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
        } catch (RuntimeException closeFailure) {
            if (failure == null) return closeFailure;
            failure.addSuppressed(closeFailure);
        } catch (Exception closeFailure) {
            RuntimeException wrapped = new IllegalStateException("frame generation resource close failed", closeFailure);
            if (failure == null) return wrapped;
            failure.addSuppressed(wrapped);
        }
        return failure;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("frame generation resources are closed");
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        RtGpuImage closingMotion = motion;
        RtGpuImage closingDepth = depth;
        RtGpuImage closingExposure = exposure;
        motion = null;
        depth = null;
        exposure = null;
        layoutsInitialized = false;
        closed = true;
        RuntimeException failure = null;
        failure = closeCollecting(closingExposure, failure);
        failure = closeCollecting(closingMotion, failure);
        failure = closeCollecting(closingDepth, failure);
        if (failure != null) throw failure;
    }
}
