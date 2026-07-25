package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.vulkan.VK10;
import top.ceroxe.rt.renderer.RtStallTelemetrySink;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease;
import top.ceroxe.rt.renderer.rt.device.RtCommandContext;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.rt.renderer.rt.device.RtGpuImage;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.device.interop.VulkanImportedSemaphore;

import java.util.Objects;

/**
 * Bounded ownership unit for one output image, frame constants, descriptor set, and producer fence.
 *
 * <p>The slot becomes writable only after both the Vulkan producer and the external consumer have
 * completed. Extent replacement is therefore local to a free slot and never invalidates a handle
 * or descriptor still visible outside the renderer.</p>
 */
final class VulkanFrameSlot implements AutoCloseable {
    private final int index;
    private final VulkanDeviceRuntime device;
    private final VulkanFrameOutput frameOutput;
    private final boolean dedicatedAllocationRequired;
    private final boolean externalSemaphoreCompletionEnabled;
    private final boolean temporalEnabled;
    private final RtGpuBuffer frameUniforms;

    private RtGpuImage outputImage;
    private RtGpuImage motionImage;
    private RtCommandContext.AsyncSubmission producerSubmission;
    private RtCommandContext.AsyncSubmission consumerSubmission;
    private VulkanImportedSemaphore consumerSemaphore;
    private boolean frameUniformsClosed;
    private State state = State.FREE;
    private int imageLayout = VK10.VK_IMAGE_LAYOUT_UNDEFINED;
    private boolean motionLayoutInitialized;
    private boolean externallyOwned;
    private long frameSequence = -1L;
    private long sceneRevision = -1L;
    private long descriptorEpoch = -1L;
    private boolean closed;

    VulkanFrameSlot(
            int index,
            VulkanDeviceRuntime device,
            VulkanFrameOutput frameOutput,
            boolean dedicatedAllocationRequired,
            boolean externalSemaphoreCompletionEnabled,
            RtStallTelemetrySink stalls,
            boolean temporalEnabled
    ) {
        if (index < 0) throw new IllegalArgumentException("frame slot index must not be negative");
        this.index = index;
        this.device = Objects.requireNonNull(device, "device");
        this.frameOutput = Objects.requireNonNull(frameOutput, "frameOutput");
        this.dedicatedAllocationRequired = dedicatedAllocationRequired;
        this.externalSemaphoreCompletionEnabled = externalSemaphoreCompletionEnabled;
        this.temporalEnabled = temporalEnabled;
        this.frameUniforms = RtGpuBuffer.createHostVisibleUploadBuffer(
                device.device(),
                device.allocator(),
                VulkanFrameUniformPacker.BYTE_COUNT,
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                Objects.requireNonNull(stalls, "stalls")
        );
    }

    private static RuntimeException accumulate(RuntimeException failure, RuntimeException next) {
        if (failure == null) {
            return next;
        }
        failure.addSuppressed(next);
        return failure;
    }

    int index() {
        return index;
    }

    synchronized boolean writable() {
        requireOpen();
        return state == State.FREE;
    }

    synchronized boolean producerPending() {
        requireOpen();
        return state == State.SUBMITTED;
    }

    synchronized void prepare(int width, int height, byte[] uniformBytes) {
        requireState(State.FREE, "prepare frame slot");
        byte[] checkedUniforms = Objects.requireNonNull(uniformBytes, "uniformBytes");
        if (checkedUniforms.length != VulkanFrameUniformPacker.BYTE_COUNT) {
            throw new IllegalArgumentException("frame uniform payload has the wrong ABI size");
        }
        if (outputImage == null || outputImage.width() != width || outputImage.height() != height) {
            if (outputImage != null) outputImage.close();
            if (motionImage != null) motionImage.close();
            outputImage = RtGpuImage.createExportableStorageImage(
                    device.physicalDevice(),
                    device.device(),
                    width,
                    height,
                    frameOutput.vkFormat(),
                    dedicatedAllocationRequired
            );
            imageLayout = VK10.VK_IMAGE_LAYOUT_UNDEFINED;
            externallyOwned = false;
            motionImage = temporalEnabled
                    ? RtGpuImage.createStorageImage(
                    device.device(), device.allocator(), width, height,
                    VulkanTemporalImageSupport.MOTION_FORMAT
            )
                    : null;
            motionLayoutInitialized = false;
        }
        frameUniforms.writeBytes(checkedUniforms);
    }

    synchronized long requiredImageGrowthBytes(int width, int height) {
        requireState(State.FREE, "estimate frame slot growth");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("frame extent must be positive");
        if (outputImage != null && outputImage.width() == width && outputImage.height() == height
                && (!temporalEnabled || motionImage != null)) return 0L;
        long bytes = frameOutput.byteCount(width, height);
        if (temporalEnabled) {
            bytes = Math.addExact(bytes, Math.multiplyExact((long) width * height, 4L));
        }
        return bytes;
    }

    synchronized long trimIdleOutputImage() {
        requireState(State.FREE, "trim idle frame output");
        RtGpuImage idle = outputImage;
        if (idle == null) {
            return 0L;
        }
        long releasedBytes = idle.allocationSize();
        /*
         * Detach first so even an exceptional native close cannot leave a closed image reachable
         * by a later descriptor update. The owning session treats such a close failure as fatal.
         */
        outputImage = null;
        imageLayout = VK10.VK_IMAGE_LAYOUT_UNDEFINED;
        externallyOwned = false;
        idle.close();
        if (motionImage != null) {
            releasedBytes = Math.addExact(releasedBytes, motionImage.allocationSize());
            motionImage.close();
            motionImage = null;
            motionLayoutInitialized = false;
        }
        return releasedBytes;
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

    synchronized RtGpuImage motionImage() {
        requireOpen();
        if (!temporalEnabled || motionImage == null) {
            throw new IllegalStateException("frame slot has no temporal motion image");
        }
        return motionImage;
    }

    synchronized boolean motionLayoutInitialized() {
        requireOpen();
        return motionLayoutInitialized;
    }

    synchronized int imageLayout() {
        requireOpen();
        return imageLayout;
    }

    synchronized boolean externallyOwned() {
        requireOpen();
        return externallyOwned;
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
        if (temporalEnabled) motionLayoutInitialized = true;
        externallyOwned = true;
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
        if (state == State.CONSUMER_PENDING) {
            if (!consumerSubmission.pollComplete()) return false;
            consumerSubmission = null;
            consumerSemaphore.close();
            consumerSemaphore = null;
            recordConsumerCompleted();
            resetIdentity();
            state = State.FREE;
            return false;
        }
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

    /**
     * Returns the scene revision captured when the current frame identity was submitted.
     */
    synchronized long renderedSceneRevision() {
        return sceneRevision;
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
            GpuFrameLease.FrameDescriptor descriptor = GpuFrameLease.FrameDescriptor.builder()
                    .frameSequence(frameSequence)
                    .renderedSceneRevision(sceneRevision)
                    .extent(outputImage.width(), outputImage.height())
                    .format(new GpuFrameLease.VulkanFormat(outputImage.format()))
                    .imageType(new GpuFrameLease.VulkanImageType(VK10.VK_IMAGE_TYPE_2D))
                    .imageTiling(new GpuFrameLease.VulkanImageTiling(VK10.VK_IMAGE_TILING_OPTIMAL))
                    .imageUsage(new GpuFrameLease.VulkanImageUsage(outputImage.usageFlags()))
                    .imageCreateFlags(new GpuFrameLease.VulkanImageCreateFlags(0))
                    .imageLayout(new GpuFrameLease.VulkanImageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL))
                    .mipLevels(1)
                    .arrayLayers(1)
                    .sampleCount(new GpuFrameLease.VulkanSampleCount(VK10.VK_SAMPLE_COUNT_1_BIT))
                    .sharingMode(new GpuFrameLease.VulkanSharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE))
                    .producerQueueFamily(new GpuFrameLease.VulkanQueueFamily(device.queueFamilyIndex()))
                    .allocationSize(outputImage.allocationSize())
                    .allocationOffset(0L)
                    .dedicatedAllocation(outputImage.dedicatedAllocation())
                    .build();
            long leasedSequence = frameSequence;
            VulkanGpuFrameLease lease = new VulkanGpuFrameLease(
                    descriptor,
                    memoryHandle,
                    externalSemaphoreCompletionEnabled
                            ? GpuFrameLease.ConsumerCompletionCapabilities.cpuAndBinarySemaphore()
                            : GpuFrameLease.ConsumerCompletionCapabilities.cpuOnly(),
                    completion -> consumerCompleted(leasedSequence, completion)
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

    private synchronized void consumerCompleted(
            long leasedSequence,
            GpuFrameLease.ConsumerCompletion completion
    ) {
        requireState(State.LEASED, "release frame consumer");
        if (frameSequence != leasedSequence) {
            throw new IllegalStateException("frame slot identity changed while externally leased");
        }
        if (completion instanceof GpuFrameLease.ExternalSemaphoreSignal signal) {
            if (!externalSemaphoreCompletionEnabled) {
                throw new UnsupportedOperationException("external semaphore completion is unavailable");
            }
            VulkanImportedSemaphore imported = VulkanImportedSemaphore.importBinary(device.device(), signal);
            boolean transferred = false;
            try {
                RtCommandContext.AsyncSubmission wait =
                        device.frameCommands().submitSemaphoreWaitAsync(imported.semaphore());
                consumerSemaphore = imported;
                consumerSubmission = wait;
                state = State.CONSUMER_PENDING;
                transferred = true;
                return;
            } finally {
                if (!transferred) imported.close();
            }
        }
        recordConsumerCompleted();
        resetIdentity();
        state = State.FREE;
    }

    private void recordConsumerCompleted() {
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
        RuntimeException failure = null;
        if (consumerSubmission != null) {
            try {
                consumerSubmission.close();
                consumerSubmission = null;
            } catch (RuntimeException ex) {
                failure = ex;
            }
        }
        /* The imported semaphore may still be referenced by a timed-out wait submission. */
        if (consumerSubmission == null && consumerSemaphore != null) {
            try {
                consumerSemaphore.close();
                consumerSemaphore = null;
            } catch (RuntimeException ex) {
                failure = accumulate(failure, ex);
            }
        }
        if (producerSubmission != null) {
            try {
                producerSubmission.close();
                producerSubmission = null;
            } catch (RuntimeException ex) {
                failure = accumulate(failure, ex);
            }
        }

        /*
         * Neither GPU submission may outlive the image or uniform buffers it
         * references. A timeout keeps these resources owned by the slot so a
         * later close can retry after the fence or external semaphore advances.
         */
        if (producerSubmission == null && consumerSubmission == null) {
            if (outputImage != null) {
                try {
                    outputImage.close();
                    outputImage = null;
                } catch (RuntimeException ex) {
                    failure = accumulate(failure, ex);
                }
            }
            if (motionImage != null) {
                try {
                    motionImage.close();
                    motionImage = null;
                } catch (RuntimeException ex) {
                    failure = accumulate(failure, ex);
                }
            }
            if (!frameUniformsClosed) {
                try {
                    frameUniforms.close();
                    frameUniformsClosed = true;
                } catch (RuntimeException ex) {
                    failure = accumulate(failure, ex);
                }
            }
        }
        closed = consumerSubmission == null
                && consumerSemaphore == null
                && producerSubmission == null
                && outputImage == null
                && motionImage == null
                && frameUniformsClosed;
        if (failure != null) throw failure;
    }

    private enum State {
        FREE,
        SUBMITTED,
        COMPLETED,
        LEASED,
        CONSUMER_PENDING
    }
}
