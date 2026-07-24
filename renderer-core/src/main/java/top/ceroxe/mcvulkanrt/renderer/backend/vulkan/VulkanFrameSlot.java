package top.ceroxe.mcvulkanrt.renderer.backend.vulkan;

import org.lwjgl.vulkan.VK10;
import top.ceroxe.mcvulkanrt.renderer.RtStallTelemetrySink;
import top.ceroxe.mcvulkanrt.renderer.api.GpuFrameLease;
import top.ceroxe.mcvulkanrt.renderer.rt.device.RtCommandContext;
import top.ceroxe.mcvulkanrt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.mcvulkanrt.renderer.rt.device.RtGpuImage;
import top.ceroxe.mcvulkanrt.renderer.rt.device.VulkanDeviceRuntime;

import java.util.Objects;

/**
 * Bounded ownership unit for one output image, frame constants, descriptor set, and producer fence.
 *
 * <p>The slot becomes writable only after both the Vulkan producer and the external consumer have
 * completed. Extent replacement is therefore local to a free slot and never invalidates a handle
 * or descriptor still visible outside the renderer.</p>
 */
final class VulkanFrameSlot implements AutoCloseable {
    private static final int OUTPUT_FORMAT = VK10.VK_FORMAT_R8G8B8A8_UNORM;

    private final int index;
    private final VulkanDeviceRuntime device;
    private final boolean dedicatedAllocationRequired;
    private final RtGpuBuffer frameUniforms;

    private RtGpuImage outputImage;
    private RtCommandContext.AsyncSubmission producerSubmission;
    private State state = State.FREE;
    private int imageLayout = VK10.VK_IMAGE_LAYOUT_UNDEFINED;
    private long frameSequence = -1L;
    private long sceneRevision = -1L;
    private long descriptorEpoch = -1L;
    private boolean closed;

    VulkanFrameSlot(
            int index,
            VulkanDeviceRuntime device,
            boolean dedicatedAllocationRequired,
            RtStallTelemetrySink stalls
    ) {
        if (index < 0) throw new IllegalArgumentException("frame slot index must not be negative");
        this.index = index;
        this.device = Objects.requireNonNull(device, "device");
        this.dedicatedAllocationRequired = dedicatedAllocationRequired;
        this.frameUniforms = RtGpuBuffer.createHostVisibleUploadBuffer(
                device.device(),
                device.allocator(),
                VulkanFrameUniformPacker.BYTE_COUNT,
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                Objects.requireNonNull(stalls, "stalls")
        );
    }

    int index() {
        return index;
    }

    synchronized boolean writable() {
        requireOpen();
        return state == State.FREE;
    }

    synchronized void prepare(int width, int height, byte[] uniformBytes) {
        requireState(State.FREE, "prepare frame slot");
        byte[] checkedUniforms = Objects.requireNonNull(uniformBytes, "uniformBytes");
        if (checkedUniforms.length != VulkanFrameUniformPacker.BYTE_COUNT) {
            throw new IllegalArgumentException("frame uniform payload has the wrong ABI size");
        }
        if (outputImage == null || outputImage.width() != width || outputImage.height() != height) {
            if (outputImage != null) outputImage.close();
            outputImage = RtGpuImage.createExportableStorageImage(
                    device.physicalDevice(),
                    device.device(),
                    width,
                    height,
                    OUTPUT_FORMAT,
                    dedicatedAllocationRequired
            );
            imageLayout = VK10.VK_IMAGE_LAYOUT_UNDEFINED;
        }
        frameUniforms.writeBytes(checkedUniforms);
    }

    synchronized RtGpuImage outputImage() {
        requireOpen();
        if (outputImage == null) throw new IllegalStateException("frame slot has no output image");
        return outputImage;
    }

    synchronized RtGpuBuffer frameUniforms() {
        requireOpen();
        return frameUniforms;
    }

    synchronized int imageLayout() {
        requireOpen();
        return imageLayout;
    }

    synchronized void submitted(
            RtCommandContext.AsyncSubmission submission,
            long frameSequence,
            long sceneRevision,
            long descriptorEpoch
    ) {
        requireState(State.FREE, "publish frame submission");
        if (frameSequence < 0L || sceneRevision < 0L || descriptorEpoch < 0L) {
            throw new IllegalArgumentException("frame submission counters must not be negative");
        }
        producerSubmission = Objects.requireNonNull(submission, "submission");
        this.frameSequence = frameSequence;
        this.sceneRevision = sceneRevision;
        this.descriptorEpoch = descriptorEpoch;
        imageLayout = VK10.VK_IMAGE_LAYOUT_GENERAL;
        state = State.SUBMITTED;
        VulkanFrameFlightRecorder.record(
                VulkanFrameFlightRecorder.PHASE_ADMITTED,
                index,
                frameSequence,
                sceneRevision,
                descriptorEpoch,
                outputImage.width(),
                outputImage.height(),
                0L,
                0L
        );
    }

    synchronized boolean pollProducer() {
        requireOpen();
        if (state != State.SUBMITTED) return state == State.COMPLETED || state == State.LEASED;
        if (!producerSubmission.pollComplete()) return false;
        RtCommandContext.Timing timing = producerSubmission.timing();
        producerSubmission = null;
        state = State.COMPLETED;
        VulkanFrameFlightRecorder.record(
                VulkanFrameFlightRecorder.PHASE_PRODUCER_COMPLETED,
                index,
                frameSequence,
                sceneRevision,
                descriptorEpoch,
                outputImage.width(),
                outputImage.height(),
                timing.fenceResidencyUpperBoundNanos(),
                timing.notReadyPolls()
        );
        return true;
    }

    synchronized boolean completed() {
        requireOpen();
        return state == State.COMPLETED;
    }

    synchronized long frameSequence() {
        return frameSequence;
    }

    synchronized long descriptorEpoch() {
        return descriptorEpoch;
    }

    synchronized void discardCompleted() {
        requireState(State.COMPLETED, "discard completed frame");
        resetIdentity();
        state = State.FREE;
    }

    synchronized GpuFrameLease acquire() {
        requireState(State.COMPLETED, "acquire completed frame");
        long handle = outputImage.exportSharedWin32MemoryHandle();
        VulkanExportedMemoryHandle memoryHandle = new VulkanExportedMemoryHandle(handle);
        boolean transferred = false;
        try {
            GpuFrameLease.FrameDescriptor descriptor = new GpuFrameLease.FrameDescriptor(
                    frameSequence,
                    sceneRevision,
                    outputImage.width(),
                    outputImage.height(),
                    outputImage.format(),
                    VK10.VK_IMAGE_TYPE_2D,
                    VK10.VK_IMAGE_TILING_OPTIMAL,
                    outputImage.usageFlags(),
                    0,
                    VK10.VK_IMAGE_LAYOUT_GENERAL,
                    1,
                    1,
                    VK10.VK_SAMPLE_COUNT_1_BIT,
                    VK10.VK_SHARING_MODE_EXCLUSIVE,
                    device.queueFamilyIndex(),
                    outputImage.allocationSize(),
                    0L,
                    outputImage.dedicatedAllocation()
            );
            long leasedSequence = frameSequence;
            VulkanGpuFrameLease lease = new VulkanGpuFrameLease(
                    descriptor,
                    memoryHandle,
                    () -> consumerCompleted(leasedSequence)
            );
            state = State.LEASED;
            VulkanFrameFlightRecorder.record(
                    VulkanFrameFlightRecorder.PHASE_LEASE_ACQUIRED,
                    index,
                    frameSequence,
                    sceneRevision,
                    descriptorEpoch,
                    outputImage.width(),
                    outputImage.height(),
                    0L,
                    0L
            );
            transferred = true;
            return lease;
        } finally {
            if (!transferred) memoryHandle.close();
        }
    }

    private synchronized void consumerCompleted(long leasedSequence) {
        requireState(State.LEASED, "release frame consumer");
        if (frameSequence != leasedSequence) {
            throw new IllegalStateException("frame slot identity changed while externally leased");
        }
        VulkanFrameFlightRecorder.record(
                VulkanFrameFlightRecorder.PHASE_CONSUMER_COMPLETED,
                index,
                frameSequence,
                sceneRevision,
                descriptorEpoch,
                outputImage.width(),
                outputImage.height(),
                0L,
                0L
        );
        resetIdentity();
        state = State.FREE;
    }

    private void resetIdentity() {
        frameSequence = -1L;
        sceneRevision = -1L;
        descriptorEpoch = -1L;
    }

    private void requireState(State required, String operation) {
        requireOpen();
        if (state != required) {
            throw new IllegalStateException("cannot " + operation + " while frame slot is " + state);
        }
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("frame slot is closed");
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        if (state == State.LEASED) {
            throw new IllegalStateException("cannot close a frame slot retained by an external consumer");
        }
        closed = true;
        RuntimeException failure = null;
        if (producerSubmission != null) {
            try { producerSubmission.close(); } catch (RuntimeException ex) { failure = ex; }
            producerSubmission = null;
        }
        if (outputImage != null) {
            try { outputImage.close(); } catch (RuntimeException ex) {
                if (failure == null) failure = ex; else failure.addSuppressed(ex);
            }
            outputImage = null;
        }
        try { frameUniforms.close(); } catch (RuntimeException ex) {
            if (failure == null) failure = ex; else failure.addSuppressed(ex);
        }
        if (failure != null) throw failure;
    }

    private enum State {
        FREE,
        SUBMITTED,
        COMPLETED,
        LEASED
    }
}
