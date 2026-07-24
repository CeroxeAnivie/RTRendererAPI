package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

import org.lwjgl.vulkan.VkDevice;
import top.ceroxe.mcvulkanrt.renderer.rt.device.interop.VulkanWin32ExternalSemaphoreProbe;
import top.ceroxe.mcvulkanrt.renderer.rt.device.interop.Win32HandleSupport;
import top.ceroxe.mcvulkanrt.renderer.rt.device.RtGpuImage;
import top.ceroxe.mcvulkanrt.renderer.rt.runtime.RtCore;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * Owns every host-side lifetime that can keep an exported RT frame alive.
 *
 * <p>A completed frame, an exported frame and a presented frame are different
 * retention states. Keeping the slot ring, publication ledger and exported
 * semaphore under one owner makes the release boundary explicit: a retired
 * ring is destroyed only after this object proves that no publication state
 * retains any of its slots.</p>
 */
final class RtSharedFrameLifecycle implements AutoCloseable {
    private final VkDevice device;
    private final boolean externalFrameSemaphoreEnabled;
    private final int minimumSlotCount;
    private final RtFrameSlotRing slotRing;
    private final RtSharedFramePublicationLedger<RtPipelineFrameSlot> publications =
            new RtSharedFramePublicationLedger<>();

    private VulkanWin32ExternalSemaphoreProbe.ExportedSemaphore latestSignalSemaphore;
    private long outputResourceReallocations;
    private long outputResourceCleanupFailures;
    private String lastOutputResourceCleanupFailure = "";
    private long presentationAcknowledgements;
    private long presentationAcknowledgementRejects;
    private long retiredSlotRingsClosed;
    private long signalSemaphores;
    private long signalSemaphoresSkippedFenceSatisfied;
    private long signalHandlesExported;
    private long signalHandlesMissing;

    RtSharedFrameLifecycle(
            VkDevice device,
            RtPipelineFrameSlot[] initialSlots,
            int minimumSlotCount,
            boolean externalFrameSemaphoreEnabled
    ) {
        this.device = Objects.requireNonNull(device, "device");
        if (minimumSlotCount <= 0) {
            throw new IllegalArgumentException("minimumSlotCount must be positive");
        }
        this.minimumSlotCount = minimumSlotCount;
        this.externalFrameSemaphoreEnabled = externalFrameSemaphoreEnabled;
        this.slotRing = new RtFrameSlotRing(initialSlots, minimumSlotCount);
    }

    int slotCount() {
        return slotRing.slotCount();
    }

    RtPipelineFrameSlot acquireWritableSlot() {
        return slotRing.acquireWritable();
    }

    boolean hasWritableSlot() {
        return slotRing.hasWritableSlot();
    }

    boolean canStageDescriptorGeneration() {
        return slotRing.canStageDescriptorGeneration();
    }

    boolean matches(RtFrameOutputConfig.Extent outputExtent, RtFrameOutputConfig.Extent traceExtent) {
        return slotRing.matches(outputExtent, traceExtent);
    }

    RtPipelineFrameSlot[] currentSlots() {
        return slotRing.currentSlots();
    }

    void replaceSlots(RtPipelineFrameSlot[] nextSlots) {
        RtPipelineFrameSlot[] previousSlots = slotRing.replace(nextSlots, minimumSlotCount);
        publications.clearLatestCompletion();
        closeLatestSignalSemaphoreForResize();
        outputResourceReallocations++;
        slotRing.retireOrClose(previousSlots, publications::retainsForPublication, this::closeSlotsForResize);
    }

    void closeAbandonedSlots(RtPipelineFrameSlot[] slots, Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        if (slots == null) {
            return;
        }
        try {
            RtFrameSlotRing.closeSlots(slots);
        } catch (RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    VulkanWin32ExternalSemaphoreProbe.ExportedSemaphore createSubmissionSignal(RtGpuImage outputImage) {
        Objects.requireNonNull(outputImage, "outputImage");
        if (!outputImage.exportableWin32Memory()) {
            return null;
        }
        if (!externalFrameSemaphoreEnabled) {
            signalSemaphoresSkippedFenceSatisfied++;
            return null;
        }
        VulkanWin32ExternalSemaphoreProbe.ExportedSemaphore signalSemaphore =
                VulkanWin32ExternalSemaphoreProbe.exportSemaphore(device);
        signalSemaphores++;
        return signalSemaphore;
    }

    void publishCompletion(RtPipelineFrameSlot completedSlot, RtCore.SharedFrameState completedState) {
        RtSharedFramePublicationLedger.Completion<RtPipelineFrameSlot> completion =
                publications.complete(completedSlot, completedState);
        RtPipelineFrameSlot previousLatest = completion.previousLatest();
        if (previousLatest != null && !publications.retainsForPublication(previousLatest)) {
            previousLatest.releaseCompleted();
        }
    }

    void acceptCompletedSignal(VulkanWin32ExternalSemaphoreProbe.ExportedSemaphore signalSemaphore) {
        VulkanWin32ExternalSemaphoreProbe.ExportedSemaphore previous = latestSignalSemaphore;
        latestSignalSemaphore = signalSemaphore;
        if (previous != null) {
            previous.close();
        }
    }

    long latestSequence(boolean closed, Predicate<RtGpuImage> pendingWritesImage) {
        RtPipelineFrameSlot latest = publications.latestCompletedSlot();
        RtGpuImage image = latest == null ? null : latest.outputImage();
        return sharedFrameAvailable(
                closed,
                image != null && pendingWritesImage.test(image),
                publications.latestCompletedState().frameStateSequence(),
                image != null
        ) ? publications.latestCompletedState().frameStateSequence() : -1L;
    }

    RtCore.SharedFrameState latestState(boolean closed, Predicate<RtGpuImage> pendingWritesImage) {
        RtPipelineFrameSlot latest = publications.latestCompletedSlot();
        RtGpuImage image = latest == null ? null : latest.outputImage();
        if (!sharedFrameAvailable(
                closed,
                image != null && pendingWritesImage.test(image),
                publications.latestCompletedState().frameStateSequence(),
                image != null
        )) {
            return publications.presentedState().available()
                    ? publications.presentedState()
                    : RtCore.SharedFrameState.unavailable();
        }
        return publications.latestCompletedState();
    }

    RtCore.SharedFrameImage exportLatest(boolean closed, Predicate<RtGpuImage> pendingWritesImage) {
        RtPipelineFrameSlot slot = publications.latestCompletedSlot();
        RtGpuImage image = slot == null ? null : slot.outputImage();
        RtCore.SharedFrameState state = publications.latestCompletedState();
        if (!sharedFrameAvailable(
                closed,
                image != null && pendingWritesImage.test(image),
                state.frameStateSequence(),
                image != null && image.exportableWin32Memory()
        )) {
            return null;
        }

        return export(slot, state, true);
    }

    /**
     * Exports the exact already-presented front when a new incomplete scene
     * generation temporarily fails the visual gate. The ledger retains that
     * slot precisely for this purpose; requiring it to still be the latest
     * completion turns a valid hold into a fail-closed clear.
     */
    RtCore.SharedFrameImage exportRequired(
            long requiredFrameStateSequence,
            boolean closed,
            Predicate<RtGpuImage> pendingWritesImage
    ) {
        if (requiredFrameStateSequence < 0L) {
            throw new IllegalArgumentException("requiredFrameStateSequence must not be negative");
        }
        RtCore.SharedFrameState latest = publications.latestCompletedState();
        if (latest.available() && latest.frameStateSequence() == requiredFrameStateSequence) {
            return exportLatest(closed, pendingWritesImage);
        }
        RtPipelineFrameSlot presented = publications.presentedSlot();
        RtCore.SharedFrameState presentedState = publications.presentedState();
        RtGpuImage image = presented == null ? null : presented.outputImage();
        if (!presentedState.available()
                || presentedState.frameStateSequence() != requiredFrameStateSequence
                || presented == null
                || presented.completedFrameStateSequence() != requiredFrameStateSequence
                || !sharedFrameAvailable(
                        closed,
                        image != null && pendingWritesImage.test(image),
                        requiredFrameStateSequence,
                        image != null && image.exportableWin32Memory()
                )) {
            return null;
        }
        return export(presented, presentedState, false);
    }

    private RtCore.SharedFrameImage export(
            RtPipelineFrameSlot slot,
            RtCore.SharedFrameState state,
            boolean latestCompletion
    ) {
        RtGpuImage image = slot.outputImage();

        reserveForExport(slot);
        /* A presented slot already completed before its acknowledgement. */
        long syncHandleType = latestCompletion && latestSignalSemaphore != null
                ? latestSignalSemaphore.handleType()
                : 0L;
        long syncHandle = latestCompletion ? detachLatestSignalHandle() : 0L;
        int resolvedSyncHandleType = sharedFrameSyncHandleType(syncHandle, Math.toIntExact(syncHandleType));
        try {
            return new RtCore.SharedFrameImage(
                    state.frameStateSequence(),
                    state.causality(),
                    image.width(),
                    image.height(),
                    image.format(),
                    slot.imageLayout(),
                    image.image(),
                    image.memory(),
                    image.allocationSize(),
                    image.memoryTypeIndex(),
                    image.dedicatedAllocation(),
                    image.sharedWin32MemoryHandle(),
                    syncHandle,
                    resolvedSyncHandleType
            );
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            if (syncHandle != 0L) {
                Win32HandleSupport.close(syncHandle);
            }
            throw failure;
        }
    }

    boolean acknowledgePresented(long frameStateSequence, long vulkanImage) {
        RtPipelineFrameSlot acknowledged = slotRing.findCompleted(frameStateSequence, vulkanImage);
        if (acknowledged == null) {
            presentationAcknowledgementRejects++;
            return false;
        }
        RtSharedFramePublicationLedger.Acknowledgement<RtPipelineFrameSlot> acknowledgement =
                publications.acknowledge(acknowledged, frameStateSequence);
        if (acknowledgement.alreadyPresented()) {
            presentationAcknowledgements++;
            return true;
        }

        RtPipelineFrameSlot previousPresented = acknowledgement.previousPresented();
        acknowledged.markPresented(frameStateSequence);
        if (previousPresented != null) {
            previousPresented.releasePresentation(previousPresented == publications.latestCompletedSlot());
        }
        retiredSlotRingsClosed += slotRing.releaseUnretained(
                publications::retainsForPublication,
                this::closeSlotsForResize
        );
        presentationAcknowledgements++;
        return true;
    }

    RtCore.SharedFrameState completedState() {
        return publications.latestCompletedState();
    }

    RtCore.SharedFrameState presentedState() {
        return publications.presentedState();
    }

    RtPipelineFrameSlot presentedSlot() {
        return publications.presentedSlot();
    }

    void resetPublications() {
        publications.reset();
    }

    String slotRingSummary() {
        return slotRing.summary();
    }

    String slotRingResourceSummary() {
        return slotRing.resourceSummary();
    }

    int nextSlotIndex() {
        return slotRing.nextSlotIndex();
    }

    String outputResourceSummary() {
        return "outputResourceReallocations=" + outputResourceReallocations
                + ", outputResourceCleanupFailures=" + outputResourceCleanupFailures
                + ", lastOutputResourceCleanupFailure=" + lastOutputResourceCleanupFailure;
    }

    String publicationSummary() {
        RtPipelineFrameSlot latest = publications.latestCompletedSlot();
        RtPipelineFrameSlot presented = publications.presentedSlot();
        return "latestCompletedFrameSlot=" + (latest == null ? -1 : latest.index())
                + ", presentedFrameSlot=" + (presented == null ? -1 : presented.index())
                + ", presentedFrameSequence=" + (presented == null
                ? -1L : presented.completedFrameStateSequence())
                + ", presentedSharedFrameStateSequence=" + publications.presentedState().frameStateSequence()
                + ", sharedFramePresentationAcknowledgements=" + presentationAcknowledgements
                + ", sharedFramePresentationAcknowledgementRejects=" + presentationAcknowledgementRejects
                + ", retiredFrameSlotRings=" + slotRing.retiredRingCount()
                + ", retiredFrameSlotRingsClosed=" + retiredSlotRingsClosed
                + ", sharedFrameSignalSemaphores=" + signalSemaphores
                + ", sharedFrameSignalSemaphoresSkippedFenceSatisfied="
                + signalSemaphoresSkippedFenceSatisfied
                + ", sharedFrameSignalHandlesExported=" + signalHandlesExported
                + ", sharedFrameSignalHandlesMissing=" + signalHandlesMissing
                + ", latestSharedFrameImage=" + (latest == null
                ? "none" : "0x" + Long.toHexString(latest.outputImage().image()));
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        try {
            closeLatestSignalSemaphore();
        } catch (RuntimeException closeFailure) {
            failure = closeFailure;
        }
        try {
            slotRing.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) {
                failure = closeFailure;
            } else {
                failure.addSuppressed(closeFailure);
            }
        }
        publications.reset();
        if (failure != null) {
            throw failure;
        }
    }

    static boolean sharedFrameAvailable(
            boolean closed,
            boolean pendingWritesSharedImage,
            long latestCompletedFrameStateSequence,
            boolean sharedImageExportable
    ) {
        return !closed
                && !pendingWritesSharedImage
                && latestCompletedFrameStateSequence >= 0L
                && sharedImageExportable;
    }

    static int sharedFrameSyncHandleType(long syncHandle, int exportedSemaphoreHandleType) {
        if (syncHandle == 0L) {
            return 0;
        }
        if (exportedSemaphoreHandleType == 0) {
            throw new IllegalArgumentException(
                    "exported semaphore handle type must be set when sync handle is present"
            );
        }
        return exportedSemaphoreHandleType;
    }

    private void reserveForExport(RtPipelineFrameSlot slot) {
        RtSharedFramePublicationLedger.ExportReservation<RtPipelineFrameSlot> reservation =
                publications.reserveExport(slot);
        RtPipelineFrameSlot previousExport = reservation.previousExport();
        if (previousExport != null
                && previousExport != slot
                && !publications.retainsForPublication(previousExport)
                && previousExport != publications.latestCompletedSlot()) {
            previousExport.releaseCompleted();
        }
    }

    private long detachLatestSignalHandle() {
        if (latestSignalSemaphore == null) {
            signalHandlesMissing++;
            return 0L;
        }
        long handle = latestSignalSemaphore.detachWin32Handle();
        if (handle == 0L) {
            signalHandlesMissing++;
        } else {
            signalHandlesExported++;
        }
        return handle;
    }

    private void closeLatestSignalSemaphore() {
        if (latestSignalSemaphore != null) {
            latestSignalSemaphore.close();
            latestSignalSemaphore = null;
        }
    }

    /** A committed ring replacement cannot be rolled back if external-handle cleanup fails. */
    private void closeLatestSignalSemaphoreForResize() {
        try {
            closeLatestSignalSemaphore();
        } catch (RuntimeException failure) {
            outputResourceCleanupFailures++;
            lastOutputResourceCleanupFailure = "sharedFrameSignalSemaphore: "
                    + failure.getClass().getSimpleName() + ": " + failure.getMessage();
        }
    }

    private void closeSlotsForResize(RtPipelineFrameSlot[] slots) {
        for (RtPipelineFrameSlot slot : slots) {
            try {
                slot.close();
            } catch (RuntimeException failure) {
                outputResourceCleanupFailures++;
                lastOutputResourceCleanupFailure = "rtFrameSlot[" + slot.index() + "]: "
                        + failure.getClass().getSimpleName() + ": " + failure.getMessage();
            }
        }
    }
}
