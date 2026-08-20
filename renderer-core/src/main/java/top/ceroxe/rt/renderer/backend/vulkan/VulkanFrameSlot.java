package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.system.MemoryStack;
import top.ceroxe.rt.renderer.RtStallTelemetrySink;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease;
import top.ceroxe.rt.renderer.rt.device.RtCommandContext;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.rt.renderer.rt.device.RtGpuImage;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.device.interop.VulkanImportedSemaphore;
import top.ceroxe.rt.renderer.rt.pipeline.VulkanFrameExtents;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/**
 * Bounded ownership unit for one output image, frame constants, descriptor set, and producer fence.
 *
 * <p>The slot becomes writable only after both the Vulkan producer and the external consumer have
 * completed. Extent replacement is therefore local to a free slot and never invalidates a handle
 * or descriptor still visible outside the renderer.</p>
 */
final class VulkanFrameSlot implements AutoCloseable {
    private static final AtomicLong EXTERNAL_RESOURCE_IDS = new AtomicLong(1L);
    private final int index;
    private final VulkanDeviceRuntime device;
    private final VulkanFrameOutput frameOutput;
    private final boolean dedicatedAllocationRequired;
    private final boolean externalSemaphoreCompletionEnabled;
    private final boolean cpuFrameReadbackEnabled;
    private final boolean temporalEnabled;
    private boolean denoisingEnabled;
    private boolean reconstructionEnabled;
    private boolean frameGenerationEnabled;
    private final RtStallTelemetrySink stalls;
    private final RtGpuBuffer frameUniforms;

    private RtGpuImage outputImage;
    private RtGpuImage reconstructionOutputImage;
    private RtGpuImage traceImage;
    private long outputResourceId = -1L;
    private RtGpuImage motionImage;
    private VulkanDenoisingFrameResources denoisingResources;
    private VulkanFrameReconstructionResources reconstructionResources;
    private VulkanFrameGenerationResources frameGenerationResources;
    private RtGpuBuffer cpuReadback;
    private RtCommandContext.AsyncSubmission producerSubmission;
    private RtCommandContext.AsyncSubmission consumerSubmission;
    private VulkanImportedSemaphore consumerSemaphore;
    private boolean frameUniformsClosed;
    private State state = State.FREE;
    private int imageLayout = VK10.VK_IMAGE_LAYOUT_UNDEFINED;
    private boolean traceLayoutInitialized;
    private boolean reconstructionOutputLayoutInitialized;
    private boolean motionLayoutInitialized;
    private boolean externallyOwned;
    private long frameSequence = -1L;
    private long sceneRevision = -1L;
    private long descriptorEpoch = -1L;
    private long readyTimelineSemaphore;
    private long readyTimelineValue;
    private long observedProducerFrameSequence = -1L;
    private long observedProducerDescriptorEpoch = -1L;
    private long pendingCompletionEvidenceSequence = -1L;
    /*
     * Composition uses the same bounded slot ownership machinery as an RT producer, but it
     * never opens a VulkanFeatureSubmissionTransaction.  Keep that distinction explicit so a
     * composition fence cannot be misreported to an optional-feature ledger as a feature frame.
     */
    private boolean currentSubmissionTracksFeatureEvidence;
    private boolean pendingCompletionTracksFeatureEvidence;
    private boolean currentSubmissionUsesSer;
    private boolean pendingCompletionUsesSer;
    private LongConsumer producerCompletionObserver;
    private SubmissionReservation activeSubmissionReservation;
    private boolean closed;

    VulkanFrameSlot(
            int index,
            VulkanDeviceRuntime device,
            VulkanFrameOutput frameOutput,
            boolean dedicatedAllocationRequired,
            boolean externalSemaphoreCompletionEnabled,
            boolean cpuFrameReadbackEnabled,
            RtStallTelemetrySink stalls,
            boolean temporalEnabled
    ) {
        this(
                index,
                device,
                frameOutput,
                dedicatedAllocationRequired,
                externalSemaphoreCompletionEnabled,
                cpuFrameReadbackEnabled,
                stalls,
                temporalEnabled,
                false,
                false,
                false
        );
    }

    VulkanFrameSlot(
            int index,
            VulkanDeviceRuntime device,
            VulkanFrameOutput frameOutput,
            boolean dedicatedAllocationRequired,
            boolean externalSemaphoreCompletionEnabled,
            boolean cpuFrameReadbackEnabled,
            RtStallTelemetrySink stalls,
            boolean temporalEnabled,
            boolean denoisingEnabled
    ) {
        this(
                index, device, frameOutput, dedicatedAllocationRequired, externalSemaphoreCompletionEnabled,
                cpuFrameReadbackEnabled, stalls, temporalEnabled, denoisingEnabled, false, false
        );
    }

    VulkanFrameSlot(
            int index,
            VulkanDeviceRuntime device,
            VulkanFrameOutput frameOutput,
            boolean dedicatedAllocationRequired,
            boolean externalSemaphoreCompletionEnabled,
            boolean cpuFrameReadbackEnabled,
            RtStallTelemetrySink stalls,
            boolean temporalEnabled,
            boolean denoisingEnabled,
            boolean reconstructionEnabled,
            boolean frameGenerationEnabled
    ) {
        if (index < 0) throw new IllegalArgumentException("frame slot index must not be negative");
        this.index = index;
        this.device = Objects.requireNonNull(device, "device");
        this.frameOutput = Objects.requireNonNull(frameOutput, "frameOutput");
        this.dedicatedAllocationRequired = dedicatedAllocationRequired;
        this.externalSemaphoreCompletionEnabled = externalSemaphoreCompletionEnabled;
        this.cpuFrameReadbackEnabled = cpuFrameReadbackEnabled;
        this.temporalEnabled = temporalEnabled;
        this.denoisingEnabled = denoisingEnabled;
        this.reconstructionEnabled = reconstructionEnabled;
        this.frameGenerationEnabled = frameGenerationEnabled;
        this.stalls = Objects.requireNonNull(stalls, "stalls");
        this.frameUniforms = RtGpuBuffer.createHostVisibleUploadBuffer(
                device.device(),
                device.allocator(),
                VulkanFrameUniformPacker.BYTE_COUNT,
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                this.stalls
        );
    }

    private static RuntimeException accumulate(RuntimeException failure, RuntimeException next) {
        if (failure == null) {
            return next;
        }
        failure.addSuppressed(next);
        return failure;
    }

    private static void closeFeatureResource(AutoCloseable resource, String name) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception failure) {
            throw new IllegalStateException("failed to release " + name + " during feature fallback", failure);
        }
    }

    private static long nextExternalResourceId() {
        long identity = EXTERNAL_RESOURCE_IDS.getAndIncrement();
        if (identity <= 0L) {
            throw new IllegalStateException("external frame resource identity space exhausted");
        }
        return identity;
    }

    int index() {
        return index;
    }

    synchronized boolean denoisingEnabled() {
        requireOpen();
        return denoisingEnabled;
    }

    synchronized boolean reconstructionEnabled() {
        requireOpen();
        return reconstructionEnabled;
    }

    synchronized boolean frameGenerationEnabled() {
        requireOpen();
        return frameGenerationEnabled;
    }

    synchronized boolean writable() {
        requireOpen();
        return state == State.FREE && pendingCompletionEvidenceSequence < 0L;
    }

    /**
     * Applies a capability transition only while this slot is free. Disabled feature resources
     * are released before the next prepare, so a fallback frame cannot reuse their descriptors or
     * temporal images.
     */
    synchronized void reconfigureFeatures(
            boolean denoising,
            boolean reconstruction,
            boolean frameGeneration
    ) {
        requireState(State.FREE, "reconfigure frame slot features");
        if (denoisingEnabled && !denoising) {
            closeFeatureResource(denoisingResources, "denoising resources");
            denoisingResources = null;
            closeFeatureResource(traceImage, "denoising trace image");
            traceImage = null;
            traceLayoutInitialized = false;
        }
        if (reconstructionEnabled && !reconstruction) {
            closeFeatureResource(reconstructionResources, "reconstruction resources");
            reconstructionResources = null;
            closeFeatureResource(reconstructionOutputImage, "reconstruction output image");
            reconstructionOutputImage = null;
            reconstructionOutputLayoutInitialized = false;
        }
        if (frameGenerationEnabled && !frameGeneration) {
            closeFeatureResource(frameGenerationResources, "frame-generation resources");
            frameGenerationResources = null;
        }
        denoisingEnabled = denoising;
        reconstructionEnabled = reconstruction;
        frameGenerationEnabled = frameGeneration;
    }

    synchronized boolean producerPending() {
        requireOpen();
        /*
         * Managed presentation may lease a SUBMITTED slot before its producer fence signals.
         * State alone therefore cannot prove that ray traversal stopped reading scene buffers.
         * The submission handle is the authoritative producer-lifetime token and is cleared only
         * after its fence has completed (either by polling or by lease release).
         */
        return producerSubmission != null;
    }

    synchronized void prepare(int width, int height, byte[] uniformBytes) {
        prepare(VulkanFrameExtents.identity(width, height), uniformBytes);
    }

    synchronized void prepare(VulkanFrameExtents extents, byte[] uniformBytes) {
        ensureResources(extents);
        writeUniforms(uniformBytes);
    }

    /**
     * Allocates the images that temporal preparation must reference. This is intentionally
     * separate from uniform upload: the temporal coordinator derives those uniforms while
     * borrowing this slot's motion image, so a single prepare call would create a circular
     * first-frame dependency.
     */
    synchronized void ensureResources(VulkanFrameExtents extents) {
        requireState(State.FREE, "prepare frame slot");
        VulkanFrameExtents frameExtents = Objects.requireNonNull(extents, "extents");
        int outputWidth = frameExtents.outputWidth();
        int outputHeight = frameExtents.outputHeight();
        int renderWidth = frameExtents.renderWidth();
        int renderHeight = frameExtents.renderHeight();
        boolean resourcesMatch = outputImage != null
                && outputImage.width() == outputWidth
                && outputImage.height() == outputHeight
                && (!denoisingEnabled || traceImage != null
                && traceImage.width() == renderWidth && traceImage.height() == renderHeight)
                && (!temporalEnabled || motionImage != null)
                && (!denoisingEnabled || denoisingResources != null
                && denoisingResources.matchesExtent(renderWidth, renderHeight))
                && (!reconstructionEnabled || reconstructionResources != null
                && reconstructionResources.matchesExtent(frameExtents))
                && (!requiresPrivateReconstructionOutput() || reconstructionOutputImage != null
                && reconstructionOutputImage.width() == outputWidth
                && reconstructionOutputImage.height() == outputHeight)
                && (!(frameGenerationEnabled && !reconstructionEnabled) || frameGenerationResources != null
                && frameGenerationResources.matchesExtent(frameExtents))
                && (!cpuFrameReadbackEnabled || cpuReadback != null);
        if (resourcesMatch) return;

        RtGpuImage replacementOutput = null;
        RtGpuImage replacementReconstructionOutput = null;
        RtGpuImage replacementTrace = null;
        RtGpuImage replacementMotion = null;
        RtGpuBuffer replacementReadback = null;
        VulkanDenoisingFrameResources replacementDenoising = null;
        VulkanFrameReconstructionResources replacementReconstruction = null;
        VulkanFrameGenerationResources replacementFrameGeneration = null;
        try {
            replacementOutput = frameGenerationEnabled
                    ? RtGpuImage.createExportableStorageSampledImage(
                    device.physicalDevice(),
                    device.device(),
                    outputWidth,
                    outputHeight,
                    frameOutput.vkFormat(),
                    dedicatedAllocationRequired
            )
                    : RtGpuImage.createExportableStorageImage(
                    device.physicalDevice(),
                    device.device(),
                    outputWidth,
                    outputHeight,
                    frameOutput.vkFormat(),
                    dedicatedAllocationRequired
            );
            replacementReconstructionOutput = requiresPrivateReconstructionOutput()
                    ? RtGpuImage.createStorageSampledImage(
                    device.device(), device.allocator(), outputWidth, outputHeight,
                    VK10.VK_FORMAT_R16G16B16A16_SFLOAT
            )
                    : null;
            replacementTrace = denoisingEnabled
                    ? RtGpuImage.createStorageImage(
                    device.device(), device.allocator(), renderWidth, renderHeight,
                    VK10.VK_FORMAT_R16G16B16A16_SFLOAT
            )
                    : null;
            replacementMotion = temporalEnabled
                    ? RtGpuImage.createStorageImage(
                    device.device(), device.allocator(), renderWidth, renderHeight,
                    VulkanTemporalImageSupport.MOTION_FORMAT
            )
                    : null;
            replacementReadback = cpuFrameReadbackEnabled
                    ? RtGpuBuffer.createHostVisibleBuffer(
                    device.device(),
                    device.allocator(),
                    frameOutput.byteCount(outputWidth, outputHeight),
                    VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    stalls
            )
                    : null;
            replacementDenoising = denoisingEnabled
                    ? VulkanDenoisingFrameResources.create(device, renderWidth, renderHeight)
                    : null;
            if (reconstructionEnabled) {
                replacementReconstruction = new VulkanFrameReconstructionResources(device);
                replacementReconstruction.ensureExtent(frameExtents);
            }
            if (frameGenerationEnabled && !reconstructionEnabled) {
                replacementFrameGeneration = new VulkanFrameGenerationResources(device);
                replacementFrameGeneration.ensureExtent(frameExtents);
            }
        } catch (RuntimeException | Error failure) {
            closeAfterFailedReplacement(replacementDenoising, failure);
            closeAfterFailedReplacement(replacementReconstruction, failure);
            closeAfterFailedReplacement(replacementFrameGeneration, failure);
            closeAfterFailedReplacement(replacementReadback, failure);
            closeAfterFailedReplacement(replacementMotion, failure);
            closeAfterFailedReplacement(replacementTrace, failure);
            closeAfterFailedReplacement(replacementReconstructionOutput, failure);
            closeAfterFailedReplacement(replacementOutput, failure);
            throw failure;
        }

        RtGpuImage previousOutput = outputImage;
        RtGpuImage previousReconstructionOutput = reconstructionOutputImage;
        RtGpuImage previousTrace = traceImage;
        RtGpuImage previousMotion = motionImage;
        RtGpuBuffer previousReadback = cpuReadback;
        VulkanDenoisingFrameResources previousDenoising = denoisingResources;
        VulkanFrameReconstructionResources previousReconstruction = reconstructionResources;
        VulkanFrameGenerationResources previousFrameGeneration = frameGenerationResources;
        outputImage = replacementOutput;
        reconstructionOutputImage = replacementReconstructionOutput;
        traceImage = replacementTrace;
        outputResourceId = nextExternalResourceId();
        motionImage = replacementMotion;
        cpuReadback = replacementReadback;
        denoisingResources = replacementDenoising;
        reconstructionResources = replacementReconstruction;
        frameGenerationResources = replacementFrameGeneration;
        imageLayout = VK10.VK_IMAGE_LAYOUT_UNDEFINED;
        traceLayoutInitialized = false;
        reconstructionOutputLayoutInitialized = false;
        externallyOwned = false;
        motionLayoutInitialized = false;

        RuntimeException failure = null;
        failure = closeReplaced(previousReadback, failure);
        failure = closeReplaced(previousDenoising, failure);
        failure = closeReplaced(previousReconstruction, failure);
        failure = closeReplaced(previousFrameGeneration, failure);
        failure = closeReplaced(previousMotion, failure);
        failure = closeReplaced(previousTrace, failure);
        failure = closeReplaced(previousReconstructionOutput, failure);
        failure = closeReplaced(previousOutput, failure);
        if (failure != null) throw failure;
    }

    synchronized void writeUniforms(byte[] uniformBytes) {
        requireState(State.FREE, "write frame uniforms");
        byte[] checkedUniforms = Objects.requireNonNull(uniformBytes, "uniformBytes");
        if (checkedUniforms.length != VulkanFrameUniformPacker.BYTE_COUNT) {
            throw new IllegalArgumentException("frame uniform payload has the wrong ABI size");
        }
        frameUniforms.writeBytes(checkedUniforms);
    }

    private static void closeAfterFailedReplacement(AutoCloseable resource, Throwable failure) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static RuntimeException closeReplaced(AutoCloseable resource, RuntimeException failure) {
        if (resource == null) return failure;
        try {
            resource.close();
            return failure;
        } catch (RuntimeException closeFailure) {
            return accumulate(failure, closeFailure);
        } catch (Exception closeFailure) {
            return accumulate(failure, new IllegalStateException("native frame resource close failed", closeFailure));
        }
    }

    synchronized long requiredImageGrowthBytes(int width, int height) {
        return requiredImageGrowthBytes(VulkanFrameExtents.identity(width, height));
    }

    synchronized long requiredImageGrowthBytes(VulkanFrameExtents extents) {
        requireState(State.FREE, "estimate frame slot growth");
        VulkanFrameExtents frameExtents = Objects.requireNonNull(extents, "extents");
        int outputWidth = frameExtents.outputWidth();
        int outputHeight = frameExtents.outputHeight();
        int renderWidth = frameExtents.renderWidth();
        int renderHeight = frameExtents.renderHeight();
        if (outputImage != null && outputImage.width() == outputWidth && outputImage.height() == outputHeight
                && (!denoisingEnabled || traceImage != null
                && traceImage.width() == renderWidth && traceImage.height() == renderHeight)
                && (!temporalEnabled || motionImage != null)
                && (!denoisingEnabled || denoisingResources != null
                && denoisingResources.matchesExtent(renderWidth, renderHeight))
                && (!reconstructionEnabled || reconstructionResources != null
                && reconstructionResources.matchesExtent(frameExtents))
                && (!requiresPrivateReconstructionOutput() || reconstructionOutputImage != null
                && reconstructionOutputImage.width() == outputWidth
                && reconstructionOutputImage.height() == outputHeight)
                && (!(frameGenerationEnabled && !reconstructionEnabled) || frameGenerationResources != null
                && frameGenerationResources.matchesExtent(frameExtents))) {
            return 0L;
        }
        long bytes = frameOutput.byteCount(outputWidth, outputHeight);
        if (denoisingEnabled) {
            bytes = Math.addExact(bytes, Math.multiplyExact((long) renderWidth * renderHeight, 8L));
        }
        if (temporalEnabled) {
            bytes = Math.addExact(bytes, Math.multiplyExact((long) renderWidth * renderHeight, 4L));
        }
        if (denoisingEnabled) {
            bytes = Math.addExact(bytes, VulkanDenoisingFrameResources.requiredBytes(renderWidth, renderHeight));
        }
        if (reconstructionEnabled) {
            bytes = Math.addExact(bytes, VulkanFrameReconstructionResources.requiredBytes(frameExtents));
            if (requiresPrivateReconstructionOutput()) {
                bytes = Math.addExact(bytes, Math.multiplyExact((long) outputWidth * outputHeight, 8L));
            }
        }
        if (frameGenerationEnabled && !reconstructionEnabled) {
            bytes = Math.addExact(bytes, VulkanFrameGenerationResources.requiredBytes(frameExtents));
        }
        return bytes;
    }

    synchronized long trimIdleOutputImage() {
        requireState(State.FREE, "trim idle frame output");
        RtGpuImage idleOutput = outputImage;
        RtGpuImage idleReconstructionOutput = reconstructionOutputImage;
        RtGpuImage idleTrace = traceImage;
        RtGpuImage idleMotion = motionImage;
        RtGpuBuffer idleReadback = cpuReadback;
        VulkanDenoisingFrameResources idleDenoising = denoisingResources;
        VulkanFrameReconstructionResources idleReconstruction = reconstructionResources;
        VulkanFrameGenerationResources idleFrameGeneration = frameGenerationResources;
        if (idleOutput == null && idleReconstructionOutput == null
                && idleTrace == null && idleMotion == null && idleReadback == null
                && idleDenoising == null && idleReconstruction == null && idleFrameGeneration == null) {
            return 0L;
        }
        long releasedBytes = idleOutput == null ? 0L : idleOutput.allocationSize();
        if (idleReconstructionOutput != null) {
            releasedBytes = Math.addExact(releasedBytes, idleReconstructionOutput.allocationSize());
        }
        if (idleTrace != null) releasedBytes = Math.addExact(releasedBytes, idleTrace.allocationSize());
        if (idleMotion != null) {
            releasedBytes = Math.addExact(releasedBytes, idleMotion.allocationSize());
        }
        if (idleDenoising != null) {
            releasedBytes = Math.addExact(releasedBytes, idleDenoising.allocationSizeBytes());
        }
        if (idleReconstruction != null) {
            releasedBytes = Math.addExact(releasedBytes, idleReconstruction.allocationSizeBytes());
        }
        if (idleFrameGeneration != null) {
            releasedBytes = Math.addExact(releasedBytes, idleFrameGeneration.allocationSizeBytes());
        }
        /*
         * Detach the whole resource set before native teardown. This preserves the slot invariant
         * even when one close fails and lets every independent allocation attempt cleanup.
         */
        outputImage = null;
        reconstructionOutputImage = null;
        traceImage = null;
        outputResourceId = -1L;
        motionImage = null;
        cpuReadback = null;
        denoisingResources = null;
        reconstructionResources = null;
        frameGenerationResources = null;
        imageLayout = VK10.VK_IMAGE_LAYOUT_UNDEFINED;
        traceLayoutInitialized = false;
        reconstructionOutputLayoutInitialized = false;
        externallyOwned = false;
        motionLayoutInitialized = false;

        RuntimeException failure = null;
        failure = closeReplaced(idleReadback, failure);
        failure = closeReplaced(idleDenoising, failure);
        failure = closeReplaced(idleReconstruction, failure);
        failure = closeReplaced(idleFrameGeneration, failure);
        failure = closeReplaced(idleMotion, failure);
        failure = closeReplaced(idleTrace, failure);
        failure = closeReplaced(idleReconstructionOutput, failure);
        failure = closeReplaced(idleOutput, failure);
        if (failure != null) throw failure;
        return releasedBytes;
    }

    synchronized RtGpuImage outputImage() {
        requireOpen();
        if (outputImage == null) throw new IllegalStateException("frame slot has no output image");
        return outputImage;
    }

    /** Returns the HDR target written by reconstruction before public-format conversion. */
    synchronized RtGpuImage reconstructionOutputImage() {
        requireOpen();
        if (!reconstructionEnabled) {
            throw new IllegalStateException("frame slot has no active reconstruction output");
        }
        return requiresPrivateReconstructionOutput()
                ? Objects.requireNonNull(reconstructionOutputImage, "private reconstruction output")
                : outputImage();
    }

    synchronized boolean reconstructionOutputLayoutInitialized() {
        requireOpen();
        return !requiresPrivateReconstructionOutput() || reconstructionOutputLayoutInitialized;
    }

    private boolean requiresPrivateReconstructionOutput() {
        return reconstructionEnabled && !frameOutput.linearHdr();
    }

    /** Returns the linear ray-tracing target when NRD owns final output composition. */
    synchronized RtGpuImage traceImage() {
        requireOpen();
        if (!denoisingEnabled || traceImage == null) {
            throw new IllegalStateException("frame slot has no denoising trace image");
        }
        return traceImage;
    }

    synchronized RtGpuBuffer frameUniforms() {
        requireOpen();
        return frameUniforms;
    }

    synchronized RtGpuBuffer cpuReadback() {
        requireOpen();
        if (!cpuFrameReadbackEnabled || cpuReadback == null) {
            throw new IllegalStateException("frame slot has no managed CPU readback buffer");
        }
        return cpuReadback;
    }

    synchronized RtGpuBuffer cpuReadbackOrNull() {
        requireOpen();
        return cpuReadback;
    }

    synchronized byte[] captureCpuRgba8() {
        requireState(State.COMPLETED, "capture managed CPU frame");
        byte[] nativePixels = cpuReadback().readBytes(frameOutput.byteCount(
                outputImage.width(), outputImage.height()
        ));
        return frameOutput.linearHdr()
                ? VulkanFramePixelCodec.convertLinearHdrRgba16fToSdrRgba8(nativePixels)
                : nativePixels;
    }

    synchronized RtGpuImage motionImage() {
        requireOpen();
        if (!temporalEnabled || motionImage == null) {
            throw new IllegalStateException("frame slot has no temporal motion image");
        }
        return motionImage;
    }

    synchronized VulkanDenoisingFrameResources denoisingResources() {
        requireOpen();
        if (!denoisingEnabled || denoisingResources == null) {
            throw new IllegalStateException("frame slot has no active denoising resources");
        }
        return denoisingResources;
    }

    synchronized VulkanFrameReconstructionResources reconstructionResources() {
        requireOpen();
        if (!reconstructionEnabled || reconstructionResources == null) {
            throw new IllegalStateException("frame slot has no active reconstruction resources");
        }
        return reconstructionResources;
    }

    synchronized VulkanFrameGenerationResources frameGenerationResources() {
        requireOpen();
        if (!frameGenerationEnabled || reconstructionEnabled || frameGenerationResources == null) {
            throw new IllegalStateException("frame slot has no active frame-generation resources");
        }
        return frameGenerationResources;
    }

    synchronized boolean motionLayoutInitialized() {
        requireOpen();
        return motionLayoutInitialized;
    }

    synchronized int imageLayout() {
        requireOpen();
        return imageLayout;
    }

    synchronized boolean traceLayoutInitialized() {
        requireOpen();
        return traceLayoutInitialized;
    }

    synchronized boolean externallyOwned() {
        requireOpen();
        return externallyOwned;
    }

    synchronized void submitted(
            RtCommandContext.AsyncSubmission submission,
            long frameSequence,
            long sceneRevision,
            long descriptorEpoch,
            boolean externallyOwnedAfterSubmission,
            VulkanDeviceRuntime.ManagedPresentationSignal readySignal,
            boolean usesShaderExecutionReordering
    ) {
        requireState(State.FREE, "publish frame submission");
        if (pendingCompletionEvidenceSequence >= 0L) {
            throw new IllegalStateException("previous frame completion evidence is still pending");
        }
        if (frameSequence < 0L || sceneRevision < 0L || descriptorEpoch < 0L) {
            throw new IllegalArgumentException("frame submission counters must not be negative");
        }
        producerSubmission = Objects.requireNonNull(submission, "submission");
        this.frameSequence = frameSequence;
        this.sceneRevision = sceneRevision;
        this.descriptorEpoch = descriptorEpoch;
        VulkanDeviceRuntime.ManagedPresentationSignal checkedSignal =
                Objects.requireNonNull(readySignal, "readySignal");
        readyTimelineSemaphore = checkedSignal.semaphore();
        readyTimelineValue = checkedSignal.value();
        imageLayout = VK10.VK_IMAGE_LAYOUT_GENERAL;
        if (denoisingEnabled) traceLayoutInitialized = true;
        if (requiresPrivateReconstructionOutput()) reconstructionOutputLayoutInitialized = true;
        if (temporalEnabled) motionLayoutInitialized = true;
        externallyOwned = externallyOwnedAfterSubmission;
        currentSubmissionTracksFeatureEvidence = true;
        currentSubmissionUsesSer = usesShaderExecutionReordering;
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

    /**
     * Reserves this slot before a composition command is submitted to Vulkan.
     *
     * <p>All validation that may reject publication happens here, before queue submission. The
     * returned reservation can then adopt the submitted fence without another fallible state
     * transition. Closing an unpublished reservation restores the slot to {@link State#FREE}.</p>
     */
    synchronized SubmissionReservation reserveSubmission(
            long frameSequence,
            long sceneRevision,
            long descriptorEpoch,
            boolean externallyOwnedAfterSubmission,
            VulkanDeviceRuntime.ManagedPresentationSignal readySignal,
            boolean usesShaderExecutionReordering
    ) {
        requireState(State.FREE, "reserve frame submission");
        if (pendingCompletionEvidenceSequence >= 0L) {
            throw new IllegalStateException("previous frame completion evidence is still pending");
        }
        if (frameSequence < 0L || sceneRevision < 0L || descriptorEpoch < 0L) {
            throw new IllegalArgumentException("frame submission counters must not be negative");
        }
        VulkanDeviceRuntime.ManagedPresentationSignal checkedSignal =
                Objects.requireNonNull(readySignal, "readySignal");
        SubmissionReservation reservation = new SubmissionReservation(
                this,
                frameSequence,
                sceneRevision,
                descriptorEpoch,
                externallyOwnedAfterSubmission,
                checkedSignal,
                usesShaderExecutionReordering
        );
        activeSubmissionReservation = reservation;
        state = State.RESERVED;
        return reservation;
    }

    private synchronized void publishReservedSubmission(
            SubmissionReservation reservation,
            RtCommandContext.AsyncSubmission submission
    ) {
        requireOpen();
        SubmissionReservation checked = Objects.requireNonNull(reservation, "reservation");
        RtCommandContext.AsyncSubmission checkedSubmission = Objects.requireNonNull(submission, "submission");
        if (state != State.RESERVED || activeSubmissionReservation != checked) {
            throw new IllegalStateException("frame submission reservation is no longer active");
        }

        /*
         * From this assignment onward the slot is the sole owner of the already-submitted fence.
         * Keep this publication block free of callbacks and allocation so no recoverable Java
         * failure can strand an executing command outside the frame ring.
         */
        producerSubmission = checkedSubmission;
        frameSequence = checked.frameSequence;
        sceneRevision = checked.sceneRevision;
        descriptorEpoch = checked.descriptorEpoch;
        readyTimelineSemaphore = checked.readySignal.semaphore();
        readyTimelineValue = checked.readySignal.value();
        imageLayout = VK10.VK_IMAGE_LAYOUT_GENERAL;
        if (denoisingEnabled) traceLayoutInitialized = true;
        if (requiresPrivateReconstructionOutput()) reconstructionOutputLayoutInitialized = true;
        if (temporalEnabled) motionLayoutInitialized = true;
        externallyOwned = checked.externallyOwnedAfterSubmission;
        currentSubmissionTracksFeatureEvidence = false;
        currentSubmissionUsesSer = checked.usesShaderExecutionReordering;
        activeSubmissionReservation = null;
        checked.published = true;
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

    private synchronized void cancelSubmissionReservation(SubmissionReservation reservation) {
        if (reservation.published) return;
        if (state != State.RESERVED || activeSubmissionReservation != reservation) {
            throw new IllegalStateException("frame submission reservation is no longer active");
        }
        activeSubmissionReservation = null;
        state = State.FREE;
    }

    synchronized boolean pollProducer() {
        requireOpen();
        publishPendingCompletionEvidence();
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
        RtCommandContext.Timing timing = finishProducerCompletion();
        state = State.COMPLETED;
        recordProducerCompleted(timing);
        publishPendingCompletionEvidence();
        return true;
    }

    private RtCommandContext.Timing finishProducerCompletion() {
        RtCommandContext.Timing timing = producerSubmission.timing();
        producerSubmission = null;
        observedProducerFrameSequence = Math.max(observedProducerFrameSequence, frameSequence);
        observedProducerDescriptorEpoch = Math.max(observedProducerDescriptorEpoch, descriptorEpoch);
        if (producerCompletionObserver != null) producerCompletionObserver.accept(frameSequence);
        pendingCompletionEvidenceSequence = frameSequence;
        pendingCompletionTracksFeatureEvidence = currentSubmissionTracksFeatureEvidence;
        currentSubmissionTracksFeatureEvidence = false;
        pendingCompletionUsesSer = currentSubmissionUsesSer;
        currentSubmissionUsesSer = false;
        return timing;
    }

    private void publishPendingCompletionEvidence() {
        if (pendingCompletionEvidenceSequence < 0L) return;
        long completedSequence = pendingCompletionEvidenceSequence;
        if (pendingCompletionUsesSer) {
            device.markShaderExecutionReorderingExecuted(completedSequence);
            // The provider callback below may fail and be retried. SER completion belongs to the
            // device ledger, so consume this flag immediately after its own successful commit.
            pendingCompletionUsesSer = false;
        }
        if (pendingCompletionTracksFeatureEvidence) {
            device.featureSession().observeFrameCompletion(completedSequence);
        }
        pendingCompletionTracksFeatureEvidence = false;
        pendingCompletionEvidenceSequence = -1L;
    }

    private void recordProducerCompleted(RtCommandContext.Timing timing) {
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

    synchronized long observedProducerFrameSequence() {
        return observedProducerFrameSequence;
    }

    synchronized void setProducerCompletionObserver(LongConsumer observer) {
        if (producerCompletionObserver != null) {
            throw new IllegalStateException("producer completion observer is already configured");
        }
        producerCompletionObserver = Objects.requireNonNull(observer, "observer");
    }

    synchronized long observedProducerDescriptorEpoch() {
        return observedProducerDescriptorEpoch;
    }

    synchronized boolean managedPresentable() {
        requireOpen();
        return state == State.COMPLETED
                || state == State.SUBMITTED && readyTimelineSemaphore != 0L;
    }

    synchronized void discardCompleted() {
        requireState(State.COMPLETED, "discard completed frame");
        resetIdentity();
        state = State.FREE;
    }

    synchronized GpuFrameLease acquire() {
        requireState(State.COMPLETED, "acquire completed frame");
        return acquirePreparedFrame();
    }

    synchronized GpuFrameLease acquireManaged() {
        requireOpen();
        if (!managedPresentable()) {
            throw new IllegalStateException("cannot acquire managed frame while frame slot is " + state);
        }
        return acquirePreparedFrame();
    }

    private GpuFrameLease acquirePreparedFrame() {
        VulkanExportedMemoryHandle memoryHandle = new VulkanExportedMemoryHandle(
                this::exportSharedMemoryHandle
        );
        boolean transferred = false;
        try {
            GpuFrameLease.FrameDescriptor descriptor = GpuFrameLease.FrameDescriptor.builder()
                    .resourceId(outputResourceId)
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
                    .memoryTypeIndex(outputImage.memoryTypeIndex())
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
                    completion -> consumerCompleted(leasedSequence, completion),
                    new VulkanManagedFrameLease.NativeFrame(
                            device.device(),
                            outputImage.image(),
                            device.queueFamilyIndex(),
                            externallyOwned,
                            readyTimelineSemaphore,
                            readyTimelineValue
                    )
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

    /**
     * Converts a managed frame into the public expert ownership contract only when an expert
     * consumer actually asks for a native handle. The normal presenter path never calls this
     * method and therefore pays no external queue-family transition.
     */
    private synchronized long exportSharedMemoryHandle() {
        requireState(State.LEASED, "export shared frame memory");
        if (!externallyOwned) {
            RtCommandContext.AsyncSubmission release = device.frameCommands().submitOneTimeAsync(
                    this::recordExternalOwnershipRelease
            );
            release.close();
            externallyOwned = true;
        }
        return outputImage.exportSharedWin32MemoryHandle();
    }

    private void recordExternalOwnershipRelease(
            VkCommandBuffer commandBuffer,
            MemoryStack stack
    ) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                .sType$Default()
                .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                .dstAccessMask(0)
                .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .srcQueueFamilyIndex(device.queueFamilyIndex())
                .dstQueueFamilyIndex(VK11.VK_QUEUE_FAMILY_EXTERNAL)
                .image(outputImage.image());
        barrier.subresourceRange()
                .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1);
        VK10.vkCmdPipelineBarrier(
                commandBuffer,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                0,
                null,
                null,
                barrier
        );
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
        if (producerSubmission != null) {
            producerSubmission.close();
            recordProducerCompleted(finishProducerCompletion());
        }
        // Publish before releasing the lease identity. If a provider violates the retry-safe
        // callback contract, the public lease remains ACTIVE and its next release retries the
        // same completion evidence instead of observing an already-free slot.
        publishPendingCompletionEvidence();
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
        readyTimelineSemaphore = 0L;
        readyTimelineValue = 0L;
        currentSubmissionTracksFeatureEvidence = false;
        pendingCompletionTracksFeatureEvidence = false;
        currentSubmissionUsesSer = false;
        pendingCompletionUsesSer = false;
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

    /**
     * Prevalidated ownership token connecting one free frame slot to one future queue submission.
     * The token deliberately owns no native object until {@link #publish(RtCommandContext.AsyncSubmission)}
     * succeeds, so recorder or queue-submit failures can cancel it without waiting for the device.
     */
    final class SubmissionReservation implements AutoCloseable {
        private final VulkanFrameSlot owner;
        private final long frameSequence;
        private final long sceneRevision;
        private final long descriptorEpoch;
        private final boolean externallyOwnedAfterSubmission;
        private final VulkanDeviceRuntime.ManagedPresentationSignal readySignal;
        private final boolean usesShaderExecutionReordering;
        private boolean published;
        private boolean closed;

        private SubmissionReservation(
                VulkanFrameSlot owner,
                long frameSequence,
                long sceneRevision,
                long descriptorEpoch,
                boolean externallyOwnedAfterSubmission,
                VulkanDeviceRuntime.ManagedPresentationSignal readySignal,
                boolean usesShaderExecutionReordering
        ) {
            this.owner = owner;
            this.frameSequence = frameSequence;
            this.sceneRevision = sceneRevision;
            this.descriptorEpoch = descriptorEpoch;
            this.externallyOwnedAfterSubmission = externallyOwnedAfterSubmission;
            this.readySignal = readySignal;
            this.usesShaderExecutionReordering = usesShaderExecutionReordering;
        }

        void publish(RtCommandContext.AsyncSubmission submission) {
            if (closed) throw new IllegalStateException("frame submission reservation is closed");
            owner.publishReservedSubmission(this, submission);
        }

        boolean published() {
            return published;
        }

        @Override
        public void close() {
            if (closed) return;
            if (!published) owner.cancelSubmissionReservation(this);
            closed = true;
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        publishPendingCompletionEvidence();
        if (state == State.RESERVED) {
            throw new IllegalStateException("cannot close a frame slot with an active submission reservation");
        }
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
            if (reconstructionOutputImage != null) {
                try {
                    reconstructionOutputImage.close();
                    reconstructionOutputImage = null;
                } catch (RuntimeException ex) {
                    failure = accumulate(failure, ex);
                }
            }
            if (outputImage != null) {
                try {
                    outputImage.close();
                    outputImage = null;
                    outputResourceId = -1L;
                } catch (RuntimeException ex) {
                    failure = accumulate(failure, ex);
                }
            }
            if (traceImage != null) {
                try {
                    traceImage.close();
                    traceImage = null;
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
            if (cpuReadback != null) {
                try {
                    cpuReadback.close();
                    cpuReadback = null;
                } catch (RuntimeException ex) {
                    failure = accumulate(failure, ex);
                }
            }
            if (denoisingResources != null) {
                try {
                    denoisingResources.close();
                    denoisingResources = null;
                } catch (RuntimeException ex) {
                    failure = accumulate(failure, ex);
                }
            }
            if (reconstructionResources != null) {
                try {
                    reconstructionResources.close();
                    reconstructionResources = null;
                } catch (RuntimeException ex) {
                    failure = accumulate(failure, ex);
                }
            }
            if (frameGenerationResources != null) {
                try {
                    frameGenerationResources.close();
                    frameGenerationResources = null;
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
                && reconstructionOutputImage == null
                && traceImage == null
                && motionImage == null
                && cpuReadback == null
                && denoisingResources == null
                && reconstructionResources == null
                && frameGenerationResources == null
                && frameUniformsClosed;
        if (failure != null) throw failure;
    }

    private enum State {
        FREE,
        RESERVED,
        SUBMITTED,
        COMPLETED,
        LEASED,
        CONSUMER_PENDING
    }
}
