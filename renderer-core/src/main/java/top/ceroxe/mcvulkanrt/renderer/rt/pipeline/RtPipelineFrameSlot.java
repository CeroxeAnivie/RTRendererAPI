package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

import java.util.Objects;
import top.ceroxe.mcvulkanrt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.mcvulkanrt.renderer.rt.device.RtGpuImage;

/** Owns one pipeline frame slot's native resources and lifecycle state. */
final class RtPipelineFrameSlot implements AutoCloseable {
    private final int index;
    private final int expectedDescriptorSetCount;
    private final int dynamicSceneBufferBytes;
    private final long[] descriptorSets;
    private final long[] descriptorGenerations;
    private final RtGpuImage outputImage;
    private final RtGpuImage traceImage;
    private final RtGpuBuffer readbackBuffer;
    private final RtGpuBuffer diagnosticGBuffer;
    private final RtGpuBuffer diagnosticGBufferReadback;
    private final RtGpuBuffer frameUniformBuffer;
    private final RtGpuBuffer dynamicSceneBuffer;
    final byte[] dynamicSceneStaging;
    /* Updated only after command submission succeeded. */
    private final byte[] committedDynamicSceneBytes;
    private boolean dynamicSceneInitialized;
    private boolean dynamicSceneUploadRecorded;
    private int imageLayout;
    private int traceImageLayout;
    private long dynamicSceneRevision = Long.MIN_VALUE;
    private long dynamicSceneFrameSequence = Long.MIN_VALUE;
    private RtFrameSlotStateMachine.State state = RtFrameSlotStateMachine.State.WRITABLE;
    private int inFlightDescriptorIndex = -1;
    private long inFlightDescriptorGeneration = -1L;
    private long completedFrameStateSequence = -1L;
    private boolean closed;

    RtPipelineFrameSlot(
            int index,
            long[] descriptorSets,
            RtGpuImage outputImage,
            RtGpuImage traceImage,
            RtGpuBuffer readbackBuffer,
            RtGpuBuffer diagnosticGBuffer,
            RtGpuBuffer diagnosticGBufferReadback,
            RtGpuBuffer frameUniformBuffer,
            RtGpuBuffer dynamicSceneBuffer,
            int imageLayout,
            int traceImageLayout,
            long descriptorGeneration,
            int expectedDescriptorSetCount,
            int dynamicSceneBufferBytes
    ) {
        if (index < 0) {
            throw new IllegalArgumentException("frame slot index must not be negative");
        }
        if (descriptorGeneration <= 0L) {
            throw new IllegalArgumentException("frame slot descriptorGeneration must be positive");
        }
        if (expectedDescriptorSetCount <= 0 || dynamicSceneBufferBytes <= 0) {
            throw new IllegalArgumentException("frame slot resource dimensions must be positive");
        }
        this.expectedDescriptorSetCount = expectedDescriptorSetCount;
        this.dynamicSceneBufferBytes = dynamicSceneBufferBytes;
        this.dynamicSceneStaging = new byte[dynamicSceneBufferBytes];
        this.committedDynamicSceneBytes = new byte[dynamicSceneBufferBytes];
        this.index = index;
        Objects.requireNonNull(descriptorSets, "descriptorSets");
        if (descriptorSets.length != expectedDescriptorSetCount) {
            throw new IllegalArgumentException("frame slot must own exactly two descriptor generations");
        }
        this.descriptorSets = descriptorSets.clone();
        this.descriptorGenerations = new long[descriptorSets.length];
        for (int descriptorIndex = 0; descriptorIndex < descriptorSets.length; descriptorIndex++) {
            if (descriptorSets[descriptorIndex] == 0L) {
                throw new IllegalArgumentException("frame slot descriptorSet must not be null");
            }
            descriptorGenerations[descriptorIndex] = descriptorGeneration;
        }
        this.outputImage = Objects.requireNonNull(outputImage, "outputImage");
        this.traceImage = Objects.requireNonNull(traceImage, "traceImage");
        this.readbackBuffer = readbackBuffer;
        if ((diagnosticGBuffer == null) != (diagnosticGBufferReadback == null)) {
            throw new IllegalArgumentException("diagnostic G-buffer and readback must be allocated as a pair");
        }
        this.diagnosticGBuffer = diagnosticGBuffer;
        this.diagnosticGBufferReadback = diagnosticGBufferReadback;
        this.frameUniformBuffer = Objects.requireNonNull(frameUniformBuffer, "frameUniformBuffer");
        this.dynamicSceneBuffer = Objects.requireNonNull(dynamicSceneBuffer, "dynamicSceneBuffer");
        this.imageLayout = imageLayout;
        this.traceImageLayout = traceImageLayout;
    }

    int index() {
        return index;
    }

    int descriptorSetCount() {
        return descriptorSets.length;
    }

    long descriptorSet(int descriptorIndex) {
        requireDescriptorIndex(descriptorIndex);
        return descriptorSets[descriptorIndex];
    }

    RtGpuImage outputImage() {
        return outputImage;
    }

    RtGpuImage traceImage() {
        return traceImage;
    }

    RtGpuBuffer readbackBuffer() {
        if (readbackBuffer == null) {
            throw new IllegalStateException("frame readback buffer was not allocated for this GPU-only slot");
        }
        return readbackBuffer;
    }

    boolean hasDiagnosticGBuffer() {
        return diagnosticGBuffer != null;
    }

    RtGpuBuffer diagnosticGBuffer() {
        if (diagnosticGBuffer == null) {
            throw new IllegalStateException("diagnostic G-buffer was not allocated for this frame slot");
        }
        return diagnosticGBuffer;
    }

    RtGpuBuffer diagnosticGBufferReadback() {
        if (diagnosticGBufferReadback == null) {
            throw new IllegalStateException("diagnostic G-buffer readback was not allocated for this frame slot");
        }
        return diagnosticGBufferReadback;
    }

    RtGpuBuffer dynamicSceneBuffer() {
        return dynamicSceneBuffer;
    }

    byte[] committedDynamicSceneBytes() {
        return committedDynamicSceneBytes;
    }

    boolean dynamicSceneInitialized() {
        return dynamicSceneInitialized;
    }

    void dynamicSceneUploadRecorded(boolean recorded) {
        dynamicSceneUploadRecorded = recorded;
    }

    boolean dynamicSceneUploadRecorded() {
        return dynamicSceneUploadRecorded;
    }

    void commitDynamicSceneUpload() {
        System.arraycopy(dynamicSceneStaging, 0, committedDynamicSceneBytes, 0, dynamicSceneBufferBytes);
        dynamicSceneInitialized = true;
    }

    RtGpuBuffer frameUniformBuffer() {
        return frameUniformBuffer;
    }

    boolean hasDynamicSceneRevision(long revision, long frameSequence) {
        if (revision < 0L) {
            throw new IllegalArgumentException("dynamic scene revision must not be negative");
        }
        return dynamicSceneRevision == revision && dynamicSceneFrameSequence == frameSequence;
    }

    void dynamicSceneRevision(long revision, long frameSequence) {
        if (revision < 0L) {
            throw new IllegalArgumentException("dynamic scene revision must not be negative");
        }
        dynamicSceneRevision = revision;
        dynamicSceneFrameSequence = frameSequence;
    }

    long dynamicSceneRevision() {
        return dynamicSceneRevision == Long.MIN_VALUE ? -1L : dynamicSceneRevision;
    }

    int imageLayout() {
        return imageLayout;
    }

    void imageLayout(int imageLayout) {
        this.imageLayout = imageLayout;
    }

    int traceImageLayout() {
        return traceImageLayout;
    }

    void traceImageLayout(int traceImageLayout) {
        this.traceImageLayout = traceImageLayout;
    }

    long descriptorGeneration(int descriptorIndex) {
        requireDescriptorIndex(descriptorIndex);
        return descriptorGenerations[descriptorIndex];
    }

    void descriptorGeneration(int descriptorIndex, long descriptorGeneration) {
        requireDescriptorIndex(descriptorIndex);
        if (descriptorGeneration <= 0L) {
            throw new IllegalArgumentException("descriptorGeneration must be positive");
        }
        if (descriptorIndex == inFlightDescriptorIndex) {
            throw new IllegalStateException("cannot mutate an in-flight descriptor generation");
        }
        descriptorGenerations[descriptorIndex] = descriptorGeneration;
    }

    boolean hasStageableDescriptorSet() {
        return stageableDescriptorIndexOrMissing() >= 0;
    }

    int stageableDescriptorIndex() {
        int descriptorIndex = stageableDescriptorIndexOrMissing();
        if (descriptorIndex < 0) {
            throw new IllegalStateException("frame slot has no stageable descriptor generation");
        }
        return descriptorIndex;
    }

    int stageableDescriptorIndexOrMissing() {
        return RtFrameDispatchPolicy.stageableDescriptorIndex(
                descriptorGenerations,
                inFlightDescriptorIndex
        );
    }

    int descriptorIndexForGeneration(long descriptorGeneration) {
        if (descriptorGeneration <= 0L) {
            throw new IllegalArgumentException("descriptorGeneration must be positive");
        }
        for (int descriptorIndex = 0; descriptorIndex < descriptorGenerations.length; descriptorIndex++) {
            if (descriptorIndex != inFlightDescriptorIndex
                    && descriptorGenerations[descriptorIndex] == descriptorGeneration) {
                return descriptorIndex;
            }
        }
        return -1;
    }

    void requireDescriptorIndex(int descriptorIndex) {
        if (descriptorIndex < 0 || descriptorIndex >= descriptorSets.length) {
            throw new IllegalArgumentException("descriptor index is outside the frame slot generation bank");
        }
    }

    boolean writable() {
        return state == RtFrameSlotStateMachine.State.WRITABLE;
    }

    void beginWrite(int descriptorIndex, long descriptorGeneration) {
        requireDescriptorIndex(descriptorIndex);
        if (descriptorGeneration <= 0L) {
            throw new IllegalArgumentException("descriptorGeneration must be positive");
        }
        if (descriptorGenerations[descriptorIndex] != descriptorGeneration) {
            throw new IllegalStateException("frame dispatch descriptor generation is stale");
        }
        state = RtFrameSlotStateMachine.transition(state, RtFrameSlotStateMachine.Event.BEGIN_WRITE);
        inFlightDescriptorIndex = descriptorIndex;
        inFlightDescriptorGeneration = descriptorGeneration;
        completedFrameStateSequence = -1L;
    }

    void abortWrite() {
        state = RtFrameSlotStateMachine.transition(state, RtFrameSlotStateMachine.Event.ABORT_WRITE);
        inFlightDescriptorIndex = -1;
        inFlightDescriptorGeneration = -1L;
        completedFrameStateSequence = -1L;
    }

    void completeWrite(long frameStateSequence) {
        if (frameStateSequence < 0L) {
            throw new IllegalArgumentException("frameStateSequence must not be negative");
        }
        state = RtFrameSlotStateMachine.transition(state, RtFrameSlotStateMachine.Event.COMPLETE_WRITE);
        inFlightDescriptorIndex = -1;
        inFlightDescriptorGeneration = -1L;
        completedFrameStateSequence = frameStateSequence;
    }

    void markPresented(long frameStateSequence) {
        if (completedFrameStateSequence != frameStateSequence) {
            throw new IllegalStateException(
                    "presented frame sequence does not match completed slot: completed="
                            + completedFrameStateSequence + ", presented=" + frameStateSequence
            );
        }
        state = RtFrameSlotStateMachine.transition(state, RtFrameSlotStateMachine.Event.PRESENT);
    }

    void releaseCompleted() {
        state = RtFrameSlotStateMachine.transition(state, RtFrameSlotStateMachine.Event.SUPERSEDE_COMPLETED);
        completedFrameStateSequence = -1L;
    }

    void releasePresentation(boolean remainsLatestCompleted) {
        state = RtFrameSlotStateMachine.transition(
                state,
                remainsLatestCompleted
                        ? RtFrameSlotStateMachine.Event.RELEASE_PRESENTED_TO_COMPLETED
                        : RtFrameSlotStateMachine.Event.RELEASE_PRESENTED_TO_WRITABLE
        );
        if (!remainsLatestCompleted) {
            completedFrameStateSequence = -1L;
        }
    }

    boolean matchesCompletedFrame(long frameStateSequence, long vulkanImage) {
        return (state == RtFrameSlotStateMachine.State.COMPLETED || state == RtFrameSlotStateMachine.State.PRESENTED)
                && completedFrameStateSequence == frameStateSequence
                && outputImage.image() == vulkanImage;
    }

    long completedFrameStateSequence() {
        return completedFrameStateSequence;
    }

    /** Exact allocator-visible bytes owned by this slot; Java staging arrays are intentionally excluded. */
    long nativeResourceBytes() {
        long bytes = Math.addExact(outputImage.allocationSize(), traceImage.allocationSize());
        bytes = addBufferBytes(bytes, readbackBuffer);
        bytes = addBufferBytes(bytes, diagnosticGBuffer);
        bytes = addBufferBytes(bytes, diagnosticGBufferReadback);
        bytes = addBufferBytes(bytes, frameUniformBuffer);
        return addBufferBytes(bytes, dynamicSceneBuffer);
    }

    String summary() {
        return "slot" + index
                + "{descriptorSets=" + descriptorSetSummary()
                + ", outputImage=0x" + Long.toHexString(outputImage.image())
                + ", outputLayout=" + imageLayoutName(imageLayout)
                + ", traceImage=0x" + Long.toHexString(traceImage.image())
                + ", traceLayout=" + imageLayoutName(traceImageLayout)
                + ", descriptorGenerations=" + java.util.Arrays.toString(descriptorGenerations)
                + ", inFlightDescriptorIndex=" + inFlightDescriptorIndex
                + ", inFlightDescriptorGeneration=" + inFlightDescriptorGeneration
                + ", outputExtent=" + outputImage.width() + "x" + outputImage.height()
                + ", traceExtent=" + traceImage.width() + "x" + traceImage.height()
                + ", nativeResourceBytes=" + nativeResourceBytes()
                + ", readbackBuffer=" + (readbackBuffer == null ? "disabled" : "0x" + Long.toHexString(readbackBuffer.buffer()))
                + ", diagnosticGBuffer=" + (diagnosticGBuffer == null ? "disabled" : "0x" + Long.toHexString(diagnosticGBuffer.buffer()))
                + ", frameUniformBuffer=0x" + Long.toHexString(frameUniformBuffer.buffer())
                + ", dynamicSceneBuffer=0x" + Long.toHexString(dynamicSceneBuffer.buffer())
                + ", dynamicSceneRevision=" + (dynamicSceneRevision == Long.MIN_VALUE ? "none" : dynamicSceneRevision)
                + ", state=" + state
                + ", completedFrameStateSequence=" + completedFrameStateSequence
                + ", exportable=" + outputImage.exportableWin32Memory()
                + "}";
    }

    private static long addBufferBytes(long current, RtGpuBuffer buffer) {
        return buffer == null ? current : Math.addExact(current, buffer.sizeBytes());
    }

    String descriptorSetSummary() {
        StringBuilder summary = new StringBuilder("[");
        for (int descriptorIndex = 0; descriptorIndex < descriptorSets.length; descriptorIndex++) {
            if (descriptorIndex > 0) {
                summary.append(',');
            }
            summary.append("0x").append(Long.toHexString(descriptorSets[descriptorIndex]));
        }
        return summary.append(']').toString();
    }

    private static String imageLayoutName(int layout) {
        return switch (layout) {
            case org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED -> "UNDEFINED";
            case org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL -> "GENERAL";
            case org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> "TRANSFER_SRC_OPTIMAL";
            default -> Integer.toString(layout);
        };
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException firstFailure = null;
        try {
            dynamicSceneBuffer.close();
        } catch (RuntimeException ex) {
            firstFailure = ex;
        }
        try {
            frameUniformBuffer.close();
        } catch (RuntimeException ex) {
            if (firstFailure == null) {
                firstFailure = ex;
            } else {
                firstFailure.addSuppressed(ex);
            }
        }
        if (readbackBuffer != null) {
            try {
                readbackBuffer.close();
            } catch (RuntimeException ex) {
                if (firstFailure == null) {
                    firstFailure = ex;
                } else {
                    firstFailure.addSuppressed(ex);
                }
            }
        }
        if (diagnosticGBufferReadback != null) {
            try {
                diagnosticGBufferReadback.close();
            } catch (RuntimeException ex) {
                if (firstFailure == null) {
                    firstFailure = ex;
                } else {
                    firstFailure.addSuppressed(ex);
                }
            }
        }
        if (diagnosticGBuffer != null) {
            try {
                diagnosticGBuffer.close();
            } catch (RuntimeException ex) {
                if (firstFailure == null) {
                    firstFailure = ex;
                } else {
                    firstFailure.addSuppressed(ex);
                }
            }
        }
        try {
            outputImage.close();
        } catch (RuntimeException ex) {
            if (firstFailure == null) {
                firstFailure = ex;
            } else {
                firstFailure.addSuppressed(ex);
            }
        }
        try {
            traceImage.close();
        } catch (RuntimeException ex) {
            if (firstFailure == null) {
                firstFailure = ex;
            } else {
                firstFailure.addSuppressed(ex);
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }
}
