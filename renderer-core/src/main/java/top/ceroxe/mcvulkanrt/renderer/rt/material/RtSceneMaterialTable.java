package top.ceroxe.mcvulkanrt.renderer.rt.material;

import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import top.ceroxe.mcvulkanrt.renderer.RtMaterialTelemetrySink;
import top.ceroxe.mcvulkanrt.renderer.RtStallTelemetrySink;
import top.ceroxe.mcvulkanrt.renderer.rt.acceleration.RtFarFieldProxyMeshBuilder;
import top.ceroxe.mcvulkanrt.renderer.rt.acceleration.RtSectionSourcePublication;
import top.ceroxe.mcvulkanrt.renderer.rt.device.RtCommandContext;
import top.ceroxe.mcvulkanrt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.mcvulkanrt.renderer.rt.device.RtGpuWorkLabels;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionTriangleMesh;

import java.util.ArrayList;
import java.util.Arrays;
import java.nio.IntBuffer;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * GPU storage for the material facts a closest-hit shader needs after triangle
 * traversal has already resolved the section instance and primitive id.
 *
 * <p>The table deliberately stores compact, renderer-owned ids instead of
 * host objects. That keeps the Vulkan backend independent from client
 * object lifetimes and gives the later texture/atlas path a stable place to
 * replace this first non-textured material model.</p>
 */
/**
 * Owns the immutable material generations and their GPU-visible storage.
 *
 * <p>Scene construction may produce a new {@link Snapshot}; this store alone
 * translates that fact into dirty-range uploads and retires superseded native
 * buffers only after the descriptor generation that referenced them has
 * completed.  Neither scene extraction nor acceleration scheduling owns these
 * buffers.</p>
 */
public final class RtSceneMaterialTable implements AutoCloseable {
    static final int INTS_PER_SECTION_RECORD = 4;
    static final int INTS_PER_FACE_RECORD = 12;
    private static final int MAX_UPDATE_BUFFER_BYTES = 65_536;
    private static final long MAX_OVERALLOCATED_BUFFER_BYTES = 256L * 1024L * 1024L;
    private static final int BOOTSTRAP_SENTINEL_DIRECTION = 255;
    private static final int BOOTSTRAP_SENTINEL_BLOCK_STATE_ID = 0;
    private static final int BOOTSTRAP_SENTINEL_FLUID_AMOUNT = 0;
    private static final int BOOTSTRAP_SENTINEL_LIGHT_EMISSION = 0;
    private static final int BOOTSTRAP_SENTINEL_FLAGS = 0;
    private static final int TEXTURE_INFO_TEXTURE_ID_MASK = 0x3FFF_FFFF;
    private static final int TEXTURE_INFO_ALPHA_CUTOUT_BIT = 0x4000_0000;
    private static final int TEXTURE_INFO_TINT_BIT = 0x8000_0000;

    private final VkDevice device;
    private final long allocator;
    private final RtMaterialTelemetrySink materialTelemetry;
    private final RtStallTelemetrySink stallTelemetry;
    private final RtMaterialBufferPool bufferPool;
    private RtGpuBuffer sectionRecordBuffer;
    private RtGpuBuffer faceRecordBuffer;
    private RtGpuBuffer textureRecordBuffer;
    private RtGpuBuffer texturePixelBuffer;
    private SnapshotSignature uploadedSnapshot = SnapshotSignature.empty();
    private Snapshot uploadedMaterialSnapshot = Snapshot.empty();
    private final RtMaterialUploadStatistics uploadStatistics = new RtMaterialUploadStatistics();
    private boolean closed;

    public RtSceneMaterialTable(VkDevice device, long allocator) {
        this(device, allocator, RtMaterialTelemetrySink.NOOP, RtStallTelemetrySink.NOOP);
    }

    public RtSceneMaterialTable(
            VkDevice device,
            long allocator,
            RtMaterialTelemetrySink materialTelemetry
    ) {
        this(device, allocator, materialTelemetry, RtStallTelemetrySink.NOOP);
    }

    public RtSceneMaterialTable(
            VkDevice device,
            long allocator,
            RtMaterialTelemetrySink materialTelemetry,
            RtStallTelemetrySink stallTelemetry
    ) {
        this.device = Objects.requireNonNull(device, "device");
        if (allocator == 0L) {
            throw new IllegalArgumentException("allocator must not be null");
        }
        this.allocator = allocator;
        this.materialTelemetry = Objects.requireNonNull(materialTelemetry, "materialTelemetry");
        this.stallTelemetry = Objects.requireNonNull(stallTelemetry, "stallTelemetry");
        this.bufferPool = new RtMaterialBufferPool(materialTelemetry);
    }

    public synchronized boolean upload(RtCommandContext commandContext, Snapshot snapshot) {
        PendingUpload pendingUpload = submitUploadAsync(commandContext, snapshot);
        if (pendingUpload == null) {
            return false;
        }
        try {
            pendingUpload.waitUntilComplete();
            ActivatedUpload activatedUpload = activateCompletedUpload(pendingUpload);
            activatedUpload.commit();
            return activatedUpload.materialBuffersChanged();
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            closeSuppressing(ex, pendingUpload);
            throw ex;
        }
    }

    public synchronized PendingUpload submitUploadAsync(RtCommandContext commandContext, Snapshot snapshot) {
        return submitUploadAsync(commandContext, snapshot, true);
    }

    public synchronized PendingUpload submitUploadAsync(
            RtCommandContext commandContext,
            Snapshot snapshot,
            boolean allowInPlaceUpdate
    ) {
        Objects.requireNonNull(commandContext, "commandContext");
        Objects.requireNonNull(snapshot, "snapshot");
        if (closed) {
            throw new IllegalStateException("RT scene material table is already closed");
        }
        if (snapshot.sectionCount() <= 0 || snapshot.faceCount() <= 0) {
            throw new IllegalArgumentException("uploaded RT material table must not be empty");
        }
        SnapshotSignature snapshotSignature = snapshot.signature();
        if (snapshotSignature.equals(uploadedSnapshot)) {
            materialTelemetry.materialUploadDeduplicated(
                    snapshot.revision(),
                    snapshot.textureSnapshot().revision(),
                    snapshot.sectionCount(),
                    snapshot.faceCount()
            );
            return null;
        }
        long prepareStartNanos = System.nanoTime();

        long requiredSectionBytes = checkedMultiply(
                checkedMultiply(snapshot.sectionCount(), INTS_PER_SECTION_RECORD),
                Integer.BYTES
        );
        long requiredFaceBytes = checkedMultiply(
                checkedMultiply(snapshot.faceCount(), INTS_PER_FACE_RECORD),
                Integer.BYTES
        );
        Snapshot previousSnapshot = uploadedMaterialSnapshot;
        RtMaterialBufferPool.Buffers previousBuffers = currentMaterialBuffers();
        boolean canCopyPreviousBuffers = previousBuffers != null && uploadedSnapshot.sectionCount() > 0;
        RtTextureCatalog.Snapshot textureSnapshot = snapshot.textureSnapshot();
        IntBuffer textureRecords = textureSnapshot.textureRecordBuffer();
        long requiredTextureRecordBytes = checkedMultiply(textureRecords.remaining(), Integer.BYTES);
        long requiredTexturePixelBytes = checkedMultiply(textureSnapshot.texturePixelCount(), Integer.BYTES);
        RtMaterialBufferPool.Buffers nextBuffers = null;
        RtMaterialBufferPool.Buffers targetBuffers = null;
        RtGpuBuffer sectionStagingBuffer = null;
        RtGpuBuffer faceStagingBuffer = null;
        RtGpuBuffer textureRecordStagingBuffer = null;
        RtGpuBuffer texturePixelStagingBuffer = null;
        RtCommandContext.AsyncSubmission submission = null;
        int allocatedBufferCount = 0;
        RtMaterialDirtyUploadPlan sectionUploadPlan = null;
        RtMaterialDirtyUploadPlan faceUploadPlan = null;
        RtMaterialDirtyUploadPlan textureRecordUploadPlan = null;
        RtMaterialDirtyUploadPlan texturePixelUploadPlan = null;
        try {
            boolean canUpdateInPlace = allowInPlaceUpdate
                    && previousBuffers != null
                    && previousBuffers.canStore(
                            requiredSectionBytes,
                            requiredFaceBytes,
                            requiredTextureRecordBytes,
                            requiredTexturePixelBytes
                    );
            if (canUpdateInPlace) {
                targetBuffers = previousBuffers;
            } else {
                int reusablePoolSize = bufferPool.reusableCount();
                nextBuffers = bufferPool.acquire(
                        requiredSectionBytes,
                        requiredFaceBytes,
                        requiredTextureRecordBytes,
                        requiredTexturePixelBytes
                );
                if (nextBuffers == null) {
                    nextBuffers = createMaterialBuffers(
                            allocationBytes(requiredSectionBytes),
                            allocationBytes(requiredFaceBytes),
                            allocationBytes(requiredTextureRecordBytes),
                            allocationBytes(requiredTexturePixelBytes)
                    );
                    allocatedBufferCount = 4;
                }
                targetBuffers = nextBuffers;
                recordMaterialBufferDecision(
                        allowInPlaceUpdate,
                        previousBuffers,
                        requiredSectionBytes,
                        requiredFaceBytes,
                        requiredTextureRecordBytes,
                        requiredTexturePixelBytes,
                        reusablePoolSize,
                        allocatedBufferCount == 0,
                        allocatedBufferCount != 0
                );
            }
            if (canUpdateInPlace) {
                recordMaterialBufferDecision(
                        allowInPlaceUpdate,
                        previousBuffers,
                        requiredSectionBytes,
                        requiredFaceBytes,
                        requiredTextureRecordBytes,
                        requiredTexturePixelBytes,
                        bufferPool.reusableCount(),
                        false,
                        false
                );
            }
            boolean materialBuffersChanged = !canUpdateInPlace;
            boolean copyPreviousBuffers = materialBuffersChanged && canCopyPreviousBuffers;
            boolean forceFullUpload = materialBuffersChanged && !copyPreviousBuffers;
            sectionUploadPlan = snapshot.sectionUploadPlan(previousSnapshot, forceFullUpload);
            faceUploadPlan = snapshot.faceUploadPlan(previousSnapshot, forceFullUpload);
            if (!forceFullUpload
                    && textureSnapshot.revision() == previousSnapshot.textureSnapshot().revision()) {
                textureRecordUploadPlan = RtMaterialDirtyUploadPlan.empty();
                texturePixelUploadPlan = RtMaterialDirtyUploadPlan.empty();
            } else {
                textureRecordUploadPlan = RtTextureUploadPlanner.planRecords(
                        textureRecords,
                        previousSnapshot.textureSnapshot().textureRecordBuffer(),
                        RtTextureCatalog.INTS_PER_TEXTURE_RECORD,
                        forceFullUpload
                );
                texturePixelUploadPlan = RtTextureUploadPlanner.planPixels(
                        textureSnapshot,
                        previousSnapshot.textureSnapshot(),
                        forceFullUpload
                );
            }

            sectionStagingBuffer = createStagingUploadBuffer(sectionUploadPlan);
            faceStagingBuffer = createStagingUploadBuffer(faceUploadPlan);
            textureRecordStagingBuffer = createStagingUploadBuffer(textureRecordUploadPlan);
            texturePixelStagingBuffer = createStagingUploadBuffer(texturePixelUploadPlan);

            RtMaterialBufferPool.Buffers sourceBuffers = previousBuffers;
            RtGpuBuffer sourceSectionBuffer = sectionStagingBuffer;
            RtGpuBuffer sourceFaceBuffer = faceStagingBuffer;
            RtGpuBuffer sourceTextureRecordBuffer = textureRecordStagingBuffer;
            RtGpuBuffer sourceTexturePixelBuffer = texturePixelStagingBuffer;
            RtMaterialBufferPool.Buffers uploadTargetBuffers = targetBuffers;
            List<RtMaterialDirtyUploadPlan.CopyRange> sectionCopyRanges = sectionUploadPlan.copyRanges();
            List<RtMaterialDirtyUploadPlan.CopyRange> faceCopyRanges = faceUploadPlan.copyRanges();
            List<RtMaterialDirtyUploadPlan.CopyRange> textureRecordCopyRanges = textureRecordUploadPlan.copyRanges();
            List<RtMaterialDirtyUploadPlan.CopyRange> texturePixelCopyRanges = texturePixelUploadPlan.copyRanges();
            long previousSectionBytes = requiredSectionBytes(previousSnapshot.sectionCount());
            long previousFaceBytes = requiredFaceBytes(previousSnapshot.faceCount());
            long previousTextureRecordBytes = checkedMultiply(
                    previousSnapshot.textureSnapshot().textureRecordBuffer().remaining(),
                    Integer.BYTES
            );
            long previousTexturePixelBytes = checkedMultiply(
                    previousSnapshot.textureSnapshot().texturePixelCount(), Integer.BYTES
            );
            long uploadStartNanos = System.nanoTime();
            submission = commandContext.submitTimedOneTimeAsync(RtGpuWorkLabels.MATERIAL_UPLOAD, (commandBuffer, stack) -> {
                if (copyPreviousBuffers) {
                    RtMaterialUploadCommandRecorder.recordBufferCopy(
                            commandBuffer,
                            sourceBuffers.sectionRecordBuffer().buffer(),
                            uploadTargetBuffers.sectionRecordBuffer().buffer(),
                            0L,
                            0L,
                            Math.min(previousSectionBytes, requiredSectionBytes),
                            stack
                    );
                    RtMaterialUploadCommandRecorder.recordBufferCopy(
                            commandBuffer,
                            sourceBuffers.faceRecordBuffer().buffer(),
                            uploadTargetBuffers.faceRecordBuffer().buffer(),
                            0L,
                            0L,
                            Math.min(previousFaceBytes, requiredFaceBytes),
                            stack
                    );
                    RtMaterialUploadCommandRecorder.recordBufferCopy(
                            commandBuffer,
                            sourceBuffers.textureRecordBuffer().buffer(),
                            uploadTargetBuffers.textureRecordBuffer().buffer(),
                            0L,
                            0L,
                            Math.min(previousTextureRecordBytes, requiredTextureRecordBytes),
                            stack
                    );
                    RtMaterialUploadCommandRecorder.recordBufferCopy(
                            commandBuffer,
                            sourceBuffers.texturePixelBuffer().buffer(),
                            uploadTargetBuffers.texturePixelBuffer().buffer(),
                            0L,
                            0L,
                            Math.min(previousTexturePixelBytes, requiredTexturePixelBytes),
                            stack
                    );
                }
                RtMaterialUploadCommandRecorder.recordBufferCopies(
                        commandBuffer,
                        sourceSectionBuffer,
                        uploadTargetBuffers.sectionRecordBuffer().buffer(),
                        sectionCopyRanges
                );
                RtMaterialUploadCommandRecorder.recordBufferCopies(
                        commandBuffer,
                        sourceFaceBuffer,
                        uploadTargetBuffers.faceRecordBuffer().buffer(),
                        faceCopyRanges
                );
                RtMaterialUploadCommandRecorder.recordBufferCopies(
                        commandBuffer,
                        sourceTextureRecordBuffer,
                        uploadTargetBuffers.textureRecordBuffer().buffer(),
                        textureRecordCopyRanges
                );
                RtMaterialUploadCommandRecorder.recordBufferCopies(
                        commandBuffer,
                        sourceTexturePixelBuffer,
                        uploadTargetBuffers.texturePixelBuffer().buffer(),
                        texturePixelCopyRanges
                );
                RtMaterialUploadCommandRecorder.recordShaderReadBarrier(commandBuffer, stack);
            });

            PendingUpload pendingUpload = new PendingUpload(
                    submission,
                    targetBuffers,
                    sectionStagingBuffer,
                    faceStagingBuffer,
                    textureRecordStagingBuffer,
                    texturePixelStagingBuffer,
                    snapshot,
                    uploadStartNanos,
                    uploadStartNanos - prepareStartNanos,
                    System.nanoTime() - uploadStartNanos,
                    allocatedBufferCount,
                    materialBuffersChanged,
                    forceFullUpload,
                    sectionUploadPlan.recordCount(),
                    faceUploadPlan.recordCount(),
                    textureRecordUploadPlan.recordCount(),
                    texturePixelUploadPlan.recordCount(),
                    sectionUploadPlan.byteCount()
                            + faceUploadPlan.byteCount()
                            + textureRecordUploadPlan.byteCount()
                            + texturePixelUploadPlan.byteCount(),
                    copyPreviousBuffers
            );
            submission = null;
            nextBuffers = null;
            targetBuffers = null;
            sectionStagingBuffer = null;
            faceStagingBuffer = null;
            textureRecordStagingBuffer = null;
            texturePixelStagingBuffer = null;
            uploadStatistics.submitted();
            materialTelemetry.materialUploadSubmitted(
                    snapshot.revision(),
                    textureSnapshot.revision(),
                    snapshot.sectionCount(),
                    snapshot.faceCount(),
                    textureSnapshot.textureCount(),
                    textureSnapshot.texturePixelCount(),
                    materialBuffersChanged,
                    forceFullUpload,
                    copyPreviousBuffers,
                    allocatedBufferCount,
                    sectionUploadPlan.recordCount(),
                    faceUploadPlan.recordCount(),
                    textureRecordUploadPlan.recordCount(),
                    texturePixelUploadPlan.recordCount(),
                    sectionUploadPlan.byteCount()
                            + faceUploadPlan.byteCount()
                            + textureRecordUploadPlan.byteCount()
                            + texturePixelUploadPlan.byteCount(),
                    requiredSectionBytes,
                    requiredFaceBytes,
                    requiredTextureRecordBytes,
                    requiredTexturePixelBytes,
                    pendingUpload.cpuPrepareNanos() / 1_000_000.0,
                    pendingUpload.recordAndSubmitNanos() / 1_000_000.0
            );
            return pendingUpload;
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            materialTelemetry.materialFailure(
                    "submitUploadAsync",
                    snapshot.revision(),
                    snapshot.textureSnapshot().revision(),
                    ex.getClass().getSimpleName()
            );
            if (submission != null) {
                closeSuppressing(ex, submission);
            }
            closeSuppressing(ex, texturePixelStagingBuffer);
            closeSuppressing(ex, textureRecordStagingBuffer);
            closeSuppressing(ex, faceStagingBuffer);
            closeSuppressing(ex, sectionStagingBuffer);
            closeSuppressing(ex, nextBuffers);
            throw ex;
        }
    }

    public static MaterialBufferUploadPlan materialBufferUploadPlan(
            Snapshot previousSnapshot,
            Snapshot snapshot,
            long currentSectionBufferBytes,
            long currentFaceBufferBytes,
            long currentTextureRecordBufferBytes,
            long currentTexturePixelBufferBytes
    ) {
        return materialBufferUploadPlan(
                previousSnapshot,
                snapshot,
                currentSectionBufferBytes,
                currentFaceBufferBytes,
                currentTextureRecordBufferBytes,
                currentTexturePixelBufferBytes,
                true
        );
    }

    public static MaterialBufferUploadPlan materialBufferUploadPlan(
            Snapshot previousSnapshot,
            Snapshot snapshot,
            long currentSectionBufferBytes,
            long currentFaceBufferBytes,
            long currentTextureRecordBufferBytes,
            long currentTexturePixelBufferBytes,
            boolean allowInPlaceUpdate
    ) {
        Objects.requireNonNull(previousSnapshot, "previousSnapshot");
        Objects.requireNonNull(snapshot, "snapshot");
        if (currentSectionBufferBytes < 0L
                || currentFaceBufferBytes < 0L
                || currentTextureRecordBufferBytes < 0L
                || currentTexturePixelBufferBytes < 0L) {
            throw new IllegalArgumentException("current material buffer byte counts must not be negative");
        }
        long requiredSectionBytes = requiredSectionBytes(snapshot.sectionCount());
        long requiredFaceBytes = requiredFaceBytes(snapshot.faceCount());
        long requiredTextureRecordBytes = checkedMultiply(
                snapshot.textureSnapshot().textureRecordBuffer().remaining(),
                Integer.BYTES
        );
        long requiredTexturePixelBytes = checkedMultiply(
                snapshot.textureSnapshot().texturePixelCount(),
                Integer.BYTES
        );
        boolean hasPreviousUpload = previousSnapshot.sectionCount() > 0;
        boolean inPlace = allowInPlaceUpdate
                && hasPreviousUpload
                && currentSectionBufferBytes >= requiredSectionBytes
                && currentFaceBufferBytes >= requiredFaceBytes
                && currentTextureRecordBufferBytes >= requiredTextureRecordBytes
                && currentTexturePixelBufferBytes >= requiredTexturePixelBytes;
        long copiedPreviousBytes = 0L;
        if (!inPlace && hasPreviousUpload) {
            copiedPreviousBytes = checkedAdd(copiedPreviousBytes, Math.min(
                    requiredSectionBytes(previousSnapshot.sectionCount()),
                    requiredSectionBytes
            ));
            copiedPreviousBytes = checkedAdd(copiedPreviousBytes, Math.min(
                    requiredFaceBytes(previousSnapshot.faceCount()),
                    requiredFaceBytes
            ));
            copiedPreviousBytes = checkedAdd(copiedPreviousBytes, Math.min(
                    checkedMultiply(previousSnapshot.textureSnapshot().textureRecordBuffer().remaining(), Integer.BYTES),
                    requiredTextureRecordBytes
            ));
            copiedPreviousBytes = checkedAdd(copiedPreviousBytes, Math.min(
                    checkedMultiply(previousSnapshot.textureSnapshot().texturePixelCount(), Integer.BYTES),
                    requiredTexturePixelBytes
            ));
        }
        return new MaterialBufferUploadPlan(
                inPlace,
                !inPlace,
                !inPlace && !hasPreviousUpload,
                copiedPreviousBytes,
                requiredSectionBytes,
                requiredFaceBytes,
                requiredTextureRecordBytes,
                requiredTexturePixelBytes
        );
    }

    public synchronized ActivatedUpload activateUploadIfReady(PendingUpload pendingUpload) {
        Objects.requireNonNull(pendingUpload, "pendingUpload");
        if (closed) {
            throw new IllegalStateException("RT scene material table is already closed");
        }
        if (!pendingUpload.completeIfReady()) {
            uploadStatistics.polledNotReady();
            return null;
        }
        return activateCompletedUpload(pendingUpload);
    }

    public synchronized long sectionRecordBuffer() {
        requireUploaded();
        return sectionRecordBuffer.buffer();
    }

    public synchronized long sectionRecordBufferBytes() {
        requireUploaded();
        return sectionRecordBuffer.sizeBytes();
    }

    public synchronized long faceRecordBuffer() {
        requireUploaded();
        return faceRecordBuffer.buffer();
    }

    public synchronized long faceRecordBufferBytes() {
        requireUploaded();
        return faceRecordBuffer.sizeBytes();
    }

    public synchronized long textureRecordBuffer() {
        requireUploaded();
        return textureRecordBuffer.buffer();
    }

    public synchronized long textureRecordBufferBytes() {
        requireUploaded();
        return textureRecordBuffer.sizeBytes();
    }

    public synchronized long texturePixelBuffer() {
        requireUploaded();
        return texturePixelBuffer.buffer();
    }

    public synchronized long texturePixelBufferBytes() {
        requireUploaded();
        return texturePixelBuffer.sizeBytes();
    }

    public synchronized void releaseRetiredMaterialBuffers() {
        releaseRetiredMaterialBuffersThrough(Long.MAX_VALUE);
    }

    public synchronized void releaseRetiredMaterialBuffersThrough(long completedDescriptorGeneration) {
        if (completedDescriptorGeneration < 0L) {
            throw new IllegalArgumentException("completedDescriptorGeneration must not be negative");
        }
        RuntimeException failure = bufferPool.releaseThrough(null, completedDescriptorGeneration);
        if (failure != null) {
            throw failure;
        }
    }

    public synchronized String summary(String name) {
        return uploadStatistics.summary(
                name,
                bufferPool.summary(),
                sectionRecordBuffer == null ? 0L : sectionRecordBuffer.sizeBytes(),
                faceRecordBuffer == null ? 0L : faceRecordBuffer.sizeBytes(),
                textureRecordBuffer == null ? 0L : textureRecordBuffer.sizeBytes(),
                texturePixelBuffer == null ? 0L : texturePixelBuffer.sizeBytes()
        );
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        failure = closeCollecting(failure, texturePixelBuffer);
        failure = closeCollecting(failure, textureRecordBuffer);
        failure = closeCollecting(failure, faceRecordBuffer);
        failure = closeCollecting(failure, sectionRecordBuffer);
        failure = bufferPool.closeCollecting(failure);
        texturePixelBuffer = null;
        textureRecordBuffer = null;
        faceRecordBuffer = null;
        sectionRecordBuffer = null;
        if (failure != null) {
            throw failure;
        }
    }

    private RtGpuBuffer createStorageUploadBuffer(long requiredBytes) {
        return RtGpuBuffer.createDeviceAddressBuffer(
                device,
                allocator,
                requiredBytes,
                materialStorageBufferUsageFlags(),
                stallTelemetry
        );
    }

    /**
     * Material generations are persistent shader storage, upload targets, and
     * resize-copy sources. Declaring all three roles at creation time is part
     * of the resource contract: a later generation may preserve the unchanged
     * prefix with vkCmdCopyBuffer while dirty ranges arrive from staging.
     */
    public static int materialStorageBufferUsageFlags() {
        return VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT
                | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                | VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    }

    private RtMaterialBufferPool.Buffers createMaterialBuffers(
            long sectionBytes,
            long faceBytes,
            long textureRecordBytes,
            long texturePixelBytes
    ) {
        RtGpuBuffer nextSectionBuffer = null;
        RtGpuBuffer nextFaceBuffer = null;
        RtGpuBuffer nextTextureRecordBuffer = null;
        RtGpuBuffer nextTexturePixelBuffer = null;
        try {
            nextSectionBuffer = createStorageUploadBuffer(sectionBytes);
            nextFaceBuffer = createStorageUploadBuffer(faceBytes);
            nextTextureRecordBuffer = createStorageUploadBuffer(textureRecordBytes);
            nextTexturePixelBuffer = createStorageUploadBuffer(texturePixelBytes);
            RtMaterialBufferPool.Buffers buffers = new RtMaterialBufferPool.Buffers(
                    nextSectionBuffer,
                    nextFaceBuffer,
                    nextTextureRecordBuffer,
                    nextTexturePixelBuffer
            );
            nextSectionBuffer = null;
            nextFaceBuffer = null;
            nextTextureRecordBuffer = null;
            nextTexturePixelBuffer = null;
            return buffers;
        } finally {
            RuntimeException failure = null;
            failure = closeCollecting(failure, nextTexturePixelBuffer);
            failure = closeCollecting(failure, nextTextureRecordBuffer);
            failure = closeCollecting(failure, nextFaceBuffer);
            failure = closeCollecting(failure, nextSectionBuffer);
            if (failure != null) {
                throw failure;
            }
        }
    }

    private void recordMaterialBufferDecision(
            boolean allowInPlaceUpdate,
            RtMaterialBufferPool.Buffers currentBuffers,
            long requiredSectionBytes,
            long requiredFaceBytes,
            long requiredTextureRecordBytes,
            long requiredTexturePixelBytes,
            int reusablePoolSize,
            boolean reusedBatch,
            boolean allocatedBatch
    ) {
        materialTelemetry.materialBufferDecision(
                allowInPlaceUpdate,
                currentBuffers != null,
                requiredSectionBytes,
                requiredFaceBytes,
                requiredTextureRecordBytes,
                requiredTexturePixelBytes,
                currentBuffers == null ? 0L : currentBuffers.sectionRecordBuffer().sizeBytes(),
                currentBuffers == null ? 0L : currentBuffers.faceRecordBuffer().sizeBytes(),
                currentBuffers == null ? 0L : currentBuffers.textureRecordBuffer().sizeBytes(),
                currentBuffers == null ? 0L : currentBuffers.texturePixelBuffer().sizeBytes(),
                reusablePoolSize,
                reusedBatch,
                allocatedBatch
        );
    }

    private RtGpuBuffer createStagingUploadBuffer(long requiredBytes) {
        return RtGpuBuffer.createHostVisibleUploadBuffer(
                device,
                allocator,
                requiredBytes,
                VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                stallTelemetry
        );
    }

    private RtGpuBuffer createStagingUploadBuffer(RtMaterialDirtyUploadPlan uploadPlan) {
        Objects.requireNonNull(uploadPlan, "uploadPlan");
        if (uploadPlan.isEmpty()) {
            return null;
        }
        RtGpuBuffer stagingBuffer = createStagingUploadBuffer(uploadPlan.byteCount());
        try {
            stagingBuffer.writeIntWriters(uploadPlan.chunks());
            return stagingBuffer;
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            closeSuppressing(ex, stagingBuffer);
            throw ex;
        }
    }

    private ActivatedUpload activateCompletedUpload(PendingUpload pendingUpload) {
        if (!pendingUpload.completed()) {
            throw new IllegalStateException("cannot activate an incomplete RT material upload");
        }
        pendingUpload.releaseStagingBuffers();
        RtMaterialBufferPool.Buffers previousBuffers = currentMaterialBuffers();
        SnapshotSignature previousSnapshot = uploadedSnapshot;
        Snapshot previousMaterialSnapshot = uploadedMaterialSnapshot;
        ActivatedUpload activatedUpload = new ActivatedUpload(
                pendingUpload.snapshot().signature(),
                pendingUpload.snapshot(),
                previousSnapshot,
                previousMaterialSnapshot,
                previousBuffers,
                pendingUpload.materialBuffers(),
                pendingUpload.allocatedBufferCount(),
                pendingUpload.materialBuffersChanged(),
                pendingUpload.fullUpload(),
                pendingUpload.dirtySectionRecords(),
                pendingUpload.dirtyFaceRecords(),
                pendingUpload.dirtyTextureRecords(),
                pendingUpload.dirtyTexturePixels(),
                pendingUpload.stagedBytes(),
                pendingUpload.copiedPreviousBuffers()
        );
        /*
         * Buffer-pointer replacement is the material activation commit point. Build the rollback
         * owner and emit fallible diagnostics first; after the assignments below this method must
         * either return that exact owner or leave the old generation untouched.
         */
        materialTelemetry.materialUploadActivated(
                pendingUpload.snapshot().revision(),
                pendingUpload.snapshot().textureSnapshot().revision(),
                pendingUpload.materialBuffersChanged(),
                pendingUpload.fullUpload(),
                pendingUpload.stagedBytes()
        );
        sectionRecordBuffer = pendingUpload.materialBuffers().sectionRecordBuffer();
        faceRecordBuffer = pendingUpload.materialBuffers().faceRecordBuffer();
        textureRecordBuffer = pendingUpload.materialBuffers().textureRecordBuffer();
        texturePixelBuffer = pendingUpload.materialBuffers().texturePixelBuffer();
        pendingUpload.markActivated();
        return activatedUpload;
    }

    private void commitActivatedUpload(
            SnapshotSignature snapshot,
            Snapshot materialSnapshot,
            RtMaterialBufferPool.Buffers previousBuffers,
            long retiredDescriptorGeneration,
            int allocatedBufferCount,
            boolean materialBuffersChanged,
            boolean fullUpload,
            long dirtySectionRecords,
            long dirtyFaceRecords,
            long dirtyTextureRecords,
            long dirtyTexturePixels,
            long stagedBytes,
            boolean copiedPreviousBuffers
    ) {
        uploadedSnapshot = snapshot;
        uploadedMaterialSnapshot = materialSnapshot;
        uploadStatistics.committed(
                snapshot,
                allocatedBufferCount,
                materialBuffersChanged,
                fullUpload,
                dirtySectionRecords,
                dirtyFaceRecords,
                dirtyTextureRecords,
                dirtyTexturePixels,
                stagedBytes,
                copiedPreviousBuffers
        );
        if (materialBuffersChanged) {
            bufferPool.retire(previousBuffers, retiredDescriptorGeneration);
        }
        materialTelemetry.materialUploadCommitted(
                materialSnapshot.revision(),
                materialSnapshot.textureSnapshot().revision(),
                retiredDescriptorGeneration,
                materialBuffersChanged,
                fullUpload,
                stagedBytes
        );
    }

    private void rollbackActivatedUpload(
            SnapshotSignature previousSnapshot,
            Snapshot previousMaterialSnapshot,
            RtMaterialBufferPool.Buffers previousBuffers,
            RtMaterialBufferPool.Buffers activatedBuffers,
            boolean materialBuffersChanged
    ) {
        if (sectionRecordBuffer == activatedBuffers.sectionRecordBuffer()) {
            sectionRecordBuffer = previousBuffers == null ? null : previousBuffers.sectionRecordBuffer();
        }
        if (faceRecordBuffer == activatedBuffers.faceRecordBuffer()) {
            faceRecordBuffer = previousBuffers == null ? null : previousBuffers.faceRecordBuffer();
        }
        if (textureRecordBuffer == activatedBuffers.textureRecordBuffer()) {
            textureRecordBuffer = previousBuffers == null ? null : previousBuffers.textureRecordBuffer();
        }
        if (texturePixelBuffer == activatedBuffers.texturePixelBuffer()) {
            texturePixelBuffer = previousBuffers == null ? null : previousBuffers.texturePixelBuffer();
        }
        uploadedSnapshot = previousSnapshot;
        uploadedMaterialSnapshot = previousMaterialSnapshot;
        if (materialBuffersChanged) {
            bufferPool.recycle(activatedBuffers);
        }
    }

    private static long allocationBytes(long requiredBytes) {
        if (requiredBytes <= 0L) {
            throw new IllegalArgumentException("requiredBytes must be positive");
        }
        long doubled = checkedMultiply(requiredBytes, 2L);
        long padded = Math.max(requiredBytes, Math.min(doubled, MAX_OVERALLOCATED_BUFFER_BYTES));
        return alignUp(padded, MAX_UPDATE_BUFFER_BYTES);
    }

    private void requireUploaded() {
        if (sectionRecordBuffer == null
                || faceRecordBuffer == null
                || textureRecordBuffer == null
                || texturePixelBuffer == null) {
            throw new IllegalStateException("RT scene material table has not been uploaded");
        }
    }

    public static Snapshot bootstrapSnapshot() {
        return Snapshot.fromSectionMaterials(List.of(tombstoneSectionMaterial()), 0L);
    }

    public static SectionMaterial tombstoneSectionMaterial() {
        return TombstoneMaterialHolder.SINGLE_FACE;
    }

    private static final class TombstoneMaterialHolder {
        private static final SectionMaterial SINGLE_FACE = new SectionMaterial(new int[]{
                BOOTSTRAP_SENTINEL_BLOCK_STATE_ID,
                packFaceMetadata(
                        BOOTSTRAP_SENTINEL_FLUID_AMOUNT,
                        BOOTSTRAP_SENTINEL_DIRECTION,
                        BOOTSTRAP_SENTINEL_LIGHT_EMISSION,
                        BOOTSTRAP_SENTINEL_FLAGS
                ),
                0,
                packTextureInfo(0, false, false),
                RtTextureCatalog.packUv16(0.0F, 0.0F),
                RtTextureCatalog.packUv16(1.0F, 0.0F),
                RtTextureCatalog.packUv16(1.0F, 1.0F),
                RtTextureCatalog.packUv16(0.0F, 1.0F),
                0x00FF_F0F0,
                0x00FF_F0F0,
                0x00FF_F0F0,
                0x00FF_F0F0
        });
    }

    public static SectionMaterial tombstoneSectionMaterial(int faceCount) {
        if (faceCount <= 0) {
            throw new IllegalArgumentException("tombstone face count must be positive");
        }
        SectionMaterial singleFace = tombstoneSectionMaterial();
        if (faceCount == 1) {
            return singleFace;
        }
        return new SectionMaterial(singleFace.faceRecords, 0, faceCount, SectionMaterial.RepeatedRecord.INSTANCE);
    }

    public static int bootstrapHitRgba8() {
        return rgba8(32, 128, 255, 255);
    }

    public static int missRgba8() {
        return rgba8(92, 148, 224, 255);
    }

    private static int rgba8(int red, int green, int blue, int alpha) {
        return red | (green << 8) | (blue << 16) | (alpha << 24);
    }

    private RtMaterialBufferPool.Buffers currentMaterialBuffers() {
        if (sectionRecordBuffer == null
                && faceRecordBuffer == null
                && textureRecordBuffer == null
                && texturePixelBuffer == null) {
            return null;
        }
        return new RtMaterialBufferPool.Buffers(
                sectionRecordBuffer, faceRecordBuffer, textureRecordBuffer, texturePixelBuffer
        );
    }

    private static void close(RtGpuBuffer buffer) {
        if (buffer != null) {
            buffer.close();
        }
    }

    private static RuntimeException closeCollecting(RuntimeException failure, RtGpuBuffer buffer) {
        if (buffer == null) {
            return failure;
        }
        try {
            buffer.close();
            return failure;
        } catch (RuntimeException ex) {
            if (failure == null) {
                return ex;
            }
            failure.addSuppressed(ex);
            return failure;
        }
    }

    private static void closeSuppressing(Throwable failure, AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ex) {
            failure.addSuppressed(ex);
        }
    }

    public final class PendingUpload implements AutoCloseable {
        private final RtCommandContext.AsyncSubmission submission;
        private final RtMaterialBufferPool.Buffers materialBuffers;
        private RtGpuBuffer sectionStagingBuffer;
        private RtGpuBuffer faceStagingBuffer;
        private RtGpuBuffer textureRecordStagingBuffer;
        private RtGpuBuffer texturePixelStagingBuffer;
        private final Snapshot snapshot;
        private final long uploadStartNanos;
        private final long cpuPrepareNanos;
        private final long recordAndSubmitNanos;
        private final int allocatedBufferCount;
        private final boolean materialBuffersChanged;
        private final boolean fullUpload;
        private final long dirtySectionRecords;
        private final long dirtyFaceRecords;
        private final long dirtyTextureRecords;
        private final long dirtyTexturePixels;
        private final long stagedBytes;
        private final boolean copiedPreviousBuffers;
        private boolean completed;
        private boolean activated;
        private boolean closed;

        private PendingUpload(
                RtCommandContext.AsyncSubmission submission,
                RtMaterialBufferPool.Buffers materialBuffers,
                RtGpuBuffer sectionStagingBuffer,
                RtGpuBuffer faceStagingBuffer,
                RtGpuBuffer textureRecordStagingBuffer,
                RtGpuBuffer texturePixelStagingBuffer,
                Snapshot snapshot,
                long uploadStartNanos,
                long cpuPrepareNanos,
                long recordAndSubmitNanos,
                int allocatedBufferCount,
                boolean materialBuffersChanged,
                boolean fullUpload,
                long dirtySectionRecords,
                long dirtyFaceRecords,
                long dirtyTextureRecords,
                long dirtyTexturePixels,
                long stagedBytes,
                boolean copiedPreviousBuffers
        ) {
            this.submission = Objects.requireNonNull(submission, "submission");
            this.materialBuffers = Objects.requireNonNull(materialBuffers, "materialBuffers");
            this.sectionStagingBuffer = sectionStagingBuffer;
            this.faceStagingBuffer = faceStagingBuffer;
            this.textureRecordStagingBuffer = textureRecordStagingBuffer;
            this.texturePixelStagingBuffer = texturePixelStagingBuffer;
            this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
            this.uploadStartNanos = uploadStartNanos;
            if (cpuPrepareNanos < 0L || recordAndSubmitNanos < 0L) {
                throw new IllegalArgumentException("material upload stage durations must not be negative");
            }
            this.cpuPrepareNanos = cpuPrepareNanos;
            this.recordAndSubmitNanos = recordAndSubmitNanos;
            if (allocatedBufferCount < 0) {
                throw new IllegalArgumentException("allocatedBufferCount must not be negative");
            }
            if (dirtySectionRecords < 0L
                    || dirtyFaceRecords < 0L
                    || dirtyTextureRecords < 0L
                    || dirtyTexturePixels < 0L
                    || stagedBytes < 0L) {
                throw new IllegalArgumentException("upload dirty counts must not be negative");
            }
            this.allocatedBufferCount = allocatedBufferCount;
            this.materialBuffersChanged = materialBuffersChanged;
            this.fullUpload = fullUpload;
            this.dirtySectionRecords = dirtySectionRecords;
            this.dirtyFaceRecords = dirtyFaceRecords;
            this.dirtyTextureRecords = dirtyTextureRecords;
            this.dirtyTexturePixels = dirtyTexturePixels;
            this.stagedBytes = stagedBytes;
            this.copiedPreviousBuffers = copiedPreviousBuffers;
        }

        private boolean completeIfReady() {
            if (completed) {
                return true;
            }
            if (!submission.pollComplete()) {
                return false;
            }
            recordCompletedUpload();
            return true;
        }

        private void waitUntilComplete() {
            if (completed) {
                return;
            }
            uploadStatistics.closeWaited();
            submission.close();
            recordCompletedUpload();
        }

        private void recordCompletedUpload() {
            if (completed) {
                return;
            }
            completed = true;
            long elapsedMillis = (System.nanoTime() - uploadStartNanos) / 1_000_000L;
            uploadStatistics.completed(elapsedMillis);
            RtCommandContext.Timing commandTiming = submission.timing();
            materialTelemetry.materialUploadCompleted(
                    snapshot.revision(),
                    snapshot.textureSnapshot().revision(),
                    elapsedMillis,
                    materialBuffersChanged,
                    fullUpload,
                    stagedBytes,
                    cpuPrepareNanos / 1_000_000.0,
                    recordAndSubmitNanos / 1_000_000.0,
                    commandTiming.queueLockWaitNanos() / 1_000_000.0,
                    commandTiming.vkQueueSubmitNanos() / 1_000_000.0,
                    commandTiming.fenceResidencyUpperBoundNanos() / 1_000_000.0,
                    commandTiming.lastNotReadyToObservationNanos() / 1_000_000.0,
                    commandTiming.notReadyPolls()
            );
        }

        private boolean completed() {
            return completed;
        }

        private RtMaterialBufferPool.Buffers materialBuffers() {
            return materialBuffers;
        }

        private Snapshot snapshot() {
            return snapshot;
        }

        private long cpuPrepareNanos() {
            return cpuPrepareNanos;
        }

        private long recordAndSubmitNanos() {
            return recordAndSubmitNanos;
        }

        private int allocatedBufferCount() {
            return allocatedBufferCount;
        }

        public boolean materialBuffersChanged() {
            return materialBuffersChanged;
        }

        private boolean fullUpload() {
            return fullUpload;
        }

        private long dirtySectionRecords() {
            return dirtySectionRecords;
        }

        private long dirtyFaceRecords() {
            return dirtyFaceRecords;
        }

        private long dirtyTextureRecords() {
            return dirtyTextureRecords;
        }

        private long dirtyTexturePixels() {
            return dirtyTexturePixels;
        }

        private long stagedBytes() {
            return stagedBytes;
        }

        private boolean copiedPreviousBuffers() {
            return copiedPreviousBuffers;
        }

        private void releaseStagingBuffers() {
            RuntimeException failure = null;
            failure = closeCollecting(failure, texturePixelStagingBuffer);
            failure = closeCollecting(failure, textureRecordStagingBuffer);
            failure = closeCollecting(failure, faceStagingBuffer);
            failure = closeCollecting(failure, sectionStagingBuffer);
            texturePixelStagingBuffer = null;
            textureRecordStagingBuffer = null;
            faceStagingBuffer = null;
            sectionStagingBuffer = null;
            if (failure != null) {
                throw failure;
            }
        }

        private void markActivated() {
            activated = true;
            closed = true;
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            RuntimeException failure = null;
            try {
                if (!completed) {
                    uploadStatistics.closeWaited();
                    submission.close();
                    recordCompletedUpload();
                }
            } catch (RuntimeException ex) {
                failure = ex;
            }
            failure = closeCollecting(failure, texturePixelStagingBuffer);
            failure = closeCollecting(failure, textureRecordStagingBuffer);
            failure = closeCollecting(failure, faceStagingBuffer);
            failure = closeCollecting(failure, sectionStagingBuffer);
            if (!activated && materialBuffersChanged) {
                failure = bufferPool.recycleCollecting(failure, materialBuffers);
            }
            if (!activated) {
                materialTelemetry.materialUploadRolledBack(
                        snapshot.revision(),
                        snapshot.textureSnapshot().revision(),
                        materialBuffersChanged,
                        "pendingUploadClosedBeforeActivation"
                );
            }
            texturePixelStagingBuffer = null;
            textureRecordStagingBuffer = null;
            faceStagingBuffer = null;
            sectionStagingBuffer = null;
            if (failure != null) {
                throw failure;
            }
        }
    }

    public final class ActivatedUpload {
        private final SnapshotSignature snapshot;
        private final Snapshot materialSnapshot;
        private final SnapshotSignature previousSnapshot;
        private final Snapshot previousMaterialSnapshot;
        private final RtMaterialBufferPool.Buffers previousBuffers;
        private final RtMaterialBufferPool.Buffers activatedBuffers;
        private final int allocatedBufferCount;
        private final boolean materialBuffersChanged;
        private final boolean fullUpload;
        private final long dirtySectionRecords;
        private final long dirtyFaceRecords;
        private final long dirtyTextureRecords;
        private final long dirtyTexturePixels;
        private final long stagedBytes;
        private final boolean copiedPreviousBuffers;
        private boolean resolved;

        private ActivatedUpload(
                SnapshotSignature snapshot,
                Snapshot materialSnapshot,
                SnapshotSignature previousSnapshot,
                Snapshot previousMaterialSnapshot,
                RtMaterialBufferPool.Buffers previousBuffers,
                RtMaterialBufferPool.Buffers activatedBuffers,
                int allocatedBufferCount,
                boolean materialBuffersChanged,
                boolean fullUpload,
                long dirtySectionRecords,
                long dirtyFaceRecords,
                long dirtyTextureRecords,
                long dirtyTexturePixels,
                long stagedBytes,
                boolean copiedPreviousBuffers
        ) {
            this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
            this.materialSnapshot = Objects.requireNonNull(materialSnapshot, "materialSnapshot");
            this.previousSnapshot = Objects.requireNonNull(previousSnapshot, "previousSnapshot");
            this.previousMaterialSnapshot = Objects.requireNonNull(previousMaterialSnapshot, "previousMaterialSnapshot");
            this.previousBuffers = previousBuffers;
            this.activatedBuffers = Objects.requireNonNull(activatedBuffers, "activatedBuffers");
            if (allocatedBufferCount < 0) {
                throw new IllegalArgumentException("allocatedBufferCount must not be negative");
            }
            if (dirtySectionRecords < 0L
                    || dirtyFaceRecords < 0L
                    || dirtyTextureRecords < 0L
                    || dirtyTexturePixels < 0L
                    || stagedBytes < 0L) {
                throw new IllegalArgumentException("upload dirty counts must not be negative");
            }
            this.allocatedBufferCount = allocatedBufferCount;
            this.materialBuffersChanged = materialBuffersChanged;
            this.fullUpload = fullUpload;
            this.dirtySectionRecords = dirtySectionRecords;
            this.dirtyFaceRecords = dirtyFaceRecords;
            this.dirtyTextureRecords = dirtyTextureRecords;
            this.dirtyTexturePixels = dirtyTexturePixels;
            this.stagedBytes = stagedBytes;
            this.copiedPreviousBuffers = copiedPreviousBuffers;
        }

        public boolean materialBuffersChanged() {
            return materialBuffersChanged;
        }

        public synchronized void commit() {
            commit(0L);
        }

        public synchronized void commit(long retiredDescriptorGeneration) {
            if (resolved) {
                return;
            }
            if (retiredDescriptorGeneration < 0L) {
                throw new IllegalArgumentException("retiredDescriptorGeneration must not be negative");
            }
            commitActivatedUpload(
                    snapshot,
                    materialSnapshot,
                    previousBuffers,
                    retiredDescriptorGeneration,
                    allocatedBufferCount,
                    materialBuffersChanged,
                    fullUpload,
                    dirtySectionRecords,
                    dirtyFaceRecords,
                    dirtyTextureRecords,
                    dirtyTexturePixels,
                    stagedBytes,
                    copiedPreviousBuffers
            );
            resolved = true;
        }

        public synchronized void rollback() {
            if (resolved) {
                return;
            }
            resolved = true;
            materialTelemetry.materialUploadRolledBack(
                    materialSnapshot.revision(),
                    materialSnapshot.textureSnapshot().revision(),
                    materialBuffersChanged,
                    "activatedUploadOwnerRollback"
            );
            rollbackActivatedUpload(
                    previousSnapshot,
                    previousMaterialSnapshot,
                    previousBuffers,
                    activatedBuffers,
                    materialBuffersChanged
            );
        }
    }

    private static int packFaceMetadata(int mediumAmount, int faceDirection, int lightEmission, int materialFlags) {
        if (mediumAmount < 0 || mediumAmount > 255) {
            throw new IllegalArgumentException("mediumAmount must be unsigned byte compatible: " + mediumAmount);
        }
        if (faceDirection < 0 || faceDirection > 255) {
            throw new IllegalArgumentException("faceDirection must be unsigned byte compatible: " + faceDirection);
        }
        if (lightEmission < 0 || lightEmission > 255) {
            throw new IllegalArgumentException("lightEmission must be unsigned byte compatible: " + lightEmission);
        }
        if (materialFlags < 0 || materialFlags > 255) {
            throw new IllegalArgumentException("materialFlags must be unsigned byte compatible: " + materialFlags);
        }
        return mediumAmount | (faceDirection << 8) | (lightEmission << 16) | (materialFlags << 24);
    }

    private static int packTextureInfo(int textureId, boolean tinted, boolean alphaCutout) {
        if (textureId < 0) {
            throw new IllegalArgumentException("textureId must not be negative: " + textureId);
        }
        if ((textureId & ~TEXTURE_INFO_TEXTURE_ID_MASK) != 0) {
            throw new IllegalArgumentException("textureId is too large to reserve material bits: " + textureId);
        }
        int flags = 0;
        if (alphaCutout) {
            flags |= TEXTURE_INFO_ALPHA_CUTOUT_BIT;
        }
        if (tinted) {
            flags |= TEXTURE_INFO_TINT_BIT;
        }
        return textureId | flags;
    }

    static long checkedMultiply(long left, long right) {
        long result = left * right;
        if (left != 0L && result / left != right) {
            throw new IllegalArgumentException("size overflow");
        }
        return result;
    }

    private static long checkedAdd(long left, long right) {
        long result = left + right;
        if (((left ^ result) & (right ^ result)) < 0L) {
            throw new IllegalArgumentException("size overflow");
        }
        return result;
    }

    private static long requiredSectionBytes(int sectionCount) {
        return checkedMultiply(checkedMultiply(sectionCount, INTS_PER_SECTION_RECORD), Integer.BYTES);
    }

    private static long requiredFaceBytes(int faceCount) {
        return checkedMultiply(checkedMultiply(faceCount, INTS_PER_FACE_RECORD), Integer.BYTES);
    }

    private static long alignUp(long value, long alignment) {
        if (alignment <= 0L) {
            throw new IllegalArgumentException("alignment must be positive");
        }
        long remainder = value % alignment;
        if (remainder == 0L) {
            return value;
        }
        long delta = alignment - remainder;
        long result = value + delta;
        if (result < value) {
            throw new IllegalArgumentException("size overflow");
        }
        return result;
    }

    public static final class SectionMaterial implements RtGpuBuffer.IntBufferWriter {
        private final int[] faceRecords;
        private final SectionTriangleMesh sourceMesh;
        private final int[] sourceFaceOrder;
        private final boolean repeatedFaceRecord;
        private final int secondGeometryFaceOffset;
        private final int primitivesPerMaterialRecord;
        private final int faceCount;
        private final int fallbackColorFaceCount;
        private final int fluidFaceCount;
        private final int emissiveFaceCount;
        private final int hashCode;

        public SectionMaterial(int[] faceRecords) {
            this(faceRecords, 0, 2);
        }

        public SectionMaterial(int[] faceRecords, int secondGeometryFaceOffset) {
            this(faceRecords, secondGeometryFaceOffset, 2);
        }

        /**
         * Creates a material publication with an explicit primitive-to-record mapping.
         *
         * <p>Triangle-native geometry uses one material record per primitive, while the
         * established quad lane uses one record for its two triangles. Keeping this mapping in
         * the section record makes shader indexing a property of the submitted geometry instead
         * of an implicit host-format assumption.</p>
         */
        public SectionMaterial(
                int[] faceRecords,
                int secondGeometryFaceOffset,
                int primitivesPerMaterialRecord
        ) {
            this(
                    copyRecords(faceRecords, "faceRecords", INTS_PER_FACE_RECORD),
                    secondGeometryFaceOffset,
                    primitivesPerMaterialRecord,
                    true
            );
        }

        /**
         * Takes ownership only of a newly allocated private record array. This
         * avoids copying every mesh material twice while preserving defensive
         * copying at all public/external-array entry points.
         */
        private SectionMaterial(int[] records, int secondGeometryFaceOffset, boolean trustedOwnedRecords) {
            this(records, secondGeometryFaceOffset, 2, trustedOwnedRecords);
        }

        private SectionMaterial(
                int[] records,
                int secondGeometryFaceOffset,
                int primitivesPerMaterialRecord,
                boolean trustedOwnedRecords
        ) {
            validateRecords(records, "faceRecords", INTS_PER_FACE_RECORD);
            if (records.length == 0) {
                throw new IllegalArgumentException("section material must contain at least one face record");
            }
            int faceCount = records.length / INTS_PER_FACE_RECORD;
            if (secondGeometryFaceOffset < 0 || secondGeometryFaceOffset > faceCount) {
                throw new IllegalArgumentException("secondGeometryFaceOffset outside section face range");
            }
            validatePrimitivesPerMaterialRecord(primitivesPerMaterialRecord);
            this.faceRecords = records;
            this.sourceMesh = null;
            this.sourceFaceOrder = null;
            this.repeatedFaceRecord = false;
            this.secondGeometryFaceOffset = secondGeometryFaceOffset;
            this.primitivesPerMaterialRecord = primitivesPerMaterialRecord;
            this.faceCount = faceCount;
            this.fallbackColorFaceCount = countFallbackColorFaces(records);
            this.fluidFaceCount = countFluidFaces(records);
            this.emissiveFaceCount = countEmissiveFaces(records);
            this.hashCode = 31 * (31 * Arrays.hashCode(records) + secondGeometryFaceOffset)
                    + primitivesPerMaterialRecord;
        }

        /** Retains one immutable tombstone record while exposing the original logical face count. */
        private SectionMaterial(
                int[] repeatedRecord,
                int secondGeometryFaceOffset,
                int repeatedFaceCount,
                RepeatedRecord ignored
        ) {
            validateRecords(repeatedRecord, "repeatedRecord", INTS_PER_FACE_RECORD);
            if (repeatedRecord.length != INTS_PER_FACE_RECORD || repeatedFaceCount <= 0) {
                throw new IllegalArgumentException("repeated material requires one face record and a positive count");
            }
            if (secondGeometryFaceOffset < 0 || secondGeometryFaceOffset > repeatedFaceCount) {
                throw new IllegalArgumentException("secondGeometryFaceOffset outside repeated face range");
            }
            this.faceRecords = repeatedRecord;
            this.sourceMesh = null;
            this.sourceFaceOrder = null;
            this.repeatedFaceRecord = true;
            this.secondGeometryFaceOffset = secondGeometryFaceOffset;
            this.primitivesPerMaterialRecord = 2;
            this.faceCount = repeatedFaceCount;
            this.fallbackColorFaceCount = Math.multiplyExact(
                    countFallbackColorFaces(repeatedRecord), repeatedFaceCount
            );
            this.fluidFaceCount = Math.multiplyExact(countFluidFaces(repeatedRecord), repeatedFaceCount);
            this.emissiveFaceCount = Math.multiplyExact(countEmissiveFaces(repeatedRecord), repeatedFaceCount);
            int repeatedHash = 1;
            for (int face = 0; face < repeatedFaceCount; face++) {
                for (int value : repeatedRecord) {
                    repeatedHash = 31 * repeatedHash + value;
                }
            }
            this.hashCode = 31 * (31 * repeatedHash + secondGeometryFaceOffset)
                    + primitivesPerMaterialRecord;
        }

        private enum RepeatedRecord {
            INSTANCE
        }

        private SectionMaterial(SectionTriangleMesh mesh) {
            this.sourceMesh = Objects.requireNonNull(mesh, "mesh");
            this.faceRecords = null;
            this.repeatedFaceRecord = false;
            this.primitivesPerMaterialRecord = 2;
            this.faceCount = mesh.faceCount();
            if (faceCount <= 0) {
                throw new IllegalArgumentException("section material must contain at least one face record");
            }
            int alphaCutoutFaces = mesh.alphaCutoutFaceCount();
            int opaqueFaces = faceCount - alphaCutoutFaces;
            this.secondGeometryFaceOffset = alphaCutoutFaces > 0 && opaqueFaces > 0 ? opaqueFaces : 0;
            this.sourceFaceOrder = packedSourceFaceOrder(mesh, opaqueFaces, alphaCutoutFaces);
            int fallbackFaces = 0;
            int fluidFaces = 0;
            int emissiveFaces = 0;
            for (int sourceFace = 0; sourceFace < faceCount; sourceFace++) {
                packedMeshFaceMetadata(mesh, sourceFace);
                if (mesh.faceTextureId(sourceFace) == 0) {
                    fallbackFaces++;
                }
                if (mesh.faceFluidAmount(sourceFace) > 0) {
                    fluidFaces++;
                }
                if (mesh.faceLightEmission(sourceFace) > 0) {
                    emissiveFaces++;
                }
            }
            this.fallbackColorFaceCount = fallbackFaces;
            this.fluidFaceCount = fluidFaces;
            this.emissiveFaceCount = emissiveFaces;
            this.hashCode = meshRecordHashUnpublished(mesh, secondGeometryFaceOffset);
        }

        public static SectionMaterial fromMesh(SectionTriangleMesh mesh) {
            Objects.requireNonNull(mesh, "mesh");
            SectionMaterial published = mesh.packedMaterialPublication();
            if (published != null) {
                return published;
            }
            synchronized (mesh.materialPublicationSlot()) {
                published = mesh.packedMaterialPublication();
                if (published != null) {
                    return published;
                }
                return mesh.publishPackedMaterial(new SectionMaterial(mesh));
            }
        }

        /**
         * Seals a production mesh generation with the exact compact payload consumed by stable
         * material slots.  This runs on the mesh worker while all face lanes are cache-local, so
         * admission, BLAS and snapshots share one immutable identity without first retaining a
         * mesh-backed publication or repacking twelve integers per face later on the render path.
         */
        public static SectionMaterial publishCompactFromWorkerMesh(SectionTriangleMesh mesh) {
            Objects.requireNonNull(mesh, "mesh");
            SectionMaterial published = mesh.packedMaterialPublication();
            if (published != null) {
                return requireCompactWorkerPublication(published);
            }
            synchronized (mesh.materialPublicationSlot()) {
                published = mesh.packedMaterialPublication();
                if (published != null) {
                    return requireCompactWorkerPublication(published);
                }
                int faceCount = mesh.faceCount();
                if (faceCount <= 0) {
                    throw new IllegalArgumentException("section material must contain at least one face record");
                }
                int alphaCutoutFaces = mesh.alphaCutoutFaceCount();
                int opaqueFaces = faceCount - alphaCutoutFaces;
                int secondGeometryFaceOffset = alphaCutoutFaces > 0 && opaqueFaces > 0 ? opaqueFaces : 0;
                int[] faceRecords = new int[checkedIntMultiply(faceCount, INTS_PER_FACE_RECORD)];
                int opaqueCursor = 0;
                int alphaCursor = opaqueFaces;
                for (int sourceFace = 0; sourceFace < faceCount; sourceFace++) {
                    int targetFace = mesh.faceAlphaCutout(sourceFace) ? alphaCursor++ : opaqueCursor++;
                    copyMeshFaceRecord(mesh, sourceFace, faceRecords, targetFace);
                }
                return mesh.publishPackedMaterial(
                        new SectionMaterial(faceRecords, secondGeometryFaceOffset, true)
                );
            }
        }

        private static SectionMaterial requireCompactWorkerPublication(SectionMaterial publication) {
            if (publication.meshBackedPublication()) {
                throw new IllegalStateException(
                        "worker material publication must be sealed before the mesh escapes"
                );
            }
            return publication;
        }

        /**
         * Returns the exact {@link #faceRecordHash()} a mesh would produce without first
         * materializing its twelve-int-per-face record array.  Admission uses it as a cheap
         * cache key and still calls {@link #matchesMesh(SectionTriangleMesh)} before reuse, so
         * a hash collision can only miss an optimization, never alias two materials.
         */
        public static int meshRecordHash(SectionTriangleMesh mesh) {
            Objects.requireNonNull(mesh, "mesh");
            SectionMaterial published = mesh.packedMaterialPublication();
            if (published != null) {
                return published.faceRecordHash();
            }
            int alphaCutoutFaces = mesh.alphaCutoutFaceCount();
            int opaqueFaces = mesh.faceCount() - alphaCutoutFaces;
            int secondGeometryFaceOffset = alphaCutoutFaces > 0 && opaqueFaces > 0 ? opaqueFaces : 0;
            return meshRecordHashUnpublished(mesh, secondGeometryFaceOffset);
        }

        private static int meshRecordHashUnpublished(
                SectionTriangleMesh mesh,
                int secondGeometryFaceOffset
        ) {
            int recordsHash = 1;
            for (int sourceFace = 0; sourceFace < mesh.faceCount(); sourceFace++) {
                if (!mesh.faceAlphaCutout(sourceFace)) {
                    recordsHash = appendMeshFaceRecordHash(recordsHash, mesh, sourceFace);
                }
            }
            for (int sourceFace = 0; sourceFace < mesh.faceCount(); sourceFace++) {
                if (mesh.faceAlphaCutout(sourceFace)) {
                    recordsHash = appendMeshFaceRecordHash(recordsHash, mesh, sourceFace);
                }
            }
            return 31 * (31 * recordsHash + secondGeometryFaceOffset) + 2;
        }

        /**
         * Compares a mesh against the exact packed material generation without
         * allocating a candidate record array.
         *
         * <p>Section geometry can be republished while lighting, tint, texture
         * and alpha partitioning remain unchanged. Material admission uses this
         * predicate before {@link #fromMesh(SectionTriangleMesh)} so an
         * unchanged generation reuses its persistent payload instead of
         * allocating and immediately discarding twelve integers per face.</p>
         */
        public boolean matchesMesh(SectionTriangleMesh mesh) {
            Objects.requireNonNull(mesh, "mesh");
            if (mesh.faceCount() != faceCount) {
                return false;
            }
            int alphaCutoutFaces = mesh.alphaCutoutFaceCount();
            int opaqueFaces = mesh.faceCount() - alphaCutoutFaces;
            int expectedSecondGeometryFaceOffset = alphaCutoutFaces > 0 && opaqueFaces > 0
                    ? opaqueFaces
                    : 0;
            if (secondGeometryFaceOffset != expectedSecondGeometryFaceOffset) {
                return false;
            }
            int opaqueCursor = 0;
            int alphaCursor = opaqueFaces;
            for (int sourceFace = 0; sourceFace < mesh.faceCount(); sourceFace++) {
                int targetFace = mesh.faceAlphaCutout(sourceFace) ? alphaCursor++ : opaqueCursor++;
                if (!matchesMeshFaceRecord(mesh, sourceFace, targetFace)) {
                    return false;
                }
            }
            return true;
        }

        public static SectionMaterial fromFarFieldProxy(
                RtFarFieldProxyMeshBuilder.ProxyMesh proxyMesh,
                Map<SectionKey, RtSectionSourcePublication> sourcePublications
        ) {
            Objects.requireNonNull(proxyMesh, "proxyMesh");
            Objects.requireNonNull(sourcePublications, "sourcePublications");
            int[] faceRecords = new int[checkedIntMultiply(proxyMesh.faceCount(), INTS_PER_FACE_RECORD)];
            for (int targetFace = 0; targetFace < proxyMesh.sourceFaces().size(); targetFace++) {
                RtFarFieldProxyMeshBuilder.SourceFaceReference sourceFace = proxyMesh.sourceFaces().get(targetFace);
                RtSectionSourcePublication publication = sourcePublications.get(sourceFace.sectionKey());
                if (publication == null) {
                    throw new IllegalStateException("missing proxy material source publication "
                            + sourceFace.sectionKey());
                }
                publication.requireFarFieldSource().copySourceFaceRecordTo(
                        sourceFace.faceIndex(), faceRecords, targetFace
                );
            }
            return new SectionMaterial(faceRecords, proxyMesh.secondGeometryFaceOffset(), true);
        }

        private static void copyMeshFaceRecords(
                SectionTriangleMesh mesh,
                int[] faceRecords,
                int firstFace
        ) {
            for (int face = 0; face < mesh.faceCount(); face++) {
                copyMeshFaceRecord(mesh, face, faceRecords, checkedIntAdd(firstFace, face));
            }
        }

        /**
         * Copies one authoritative source-face record into an independently owned compact payload.
         * FarField source publication uses this at the mesh lifetime boundary so later proxy builds
         * never retain or dereference the complete section mesh.
         */
        public static void copyMeshFaceRecord(
                SectionTriangleMesh mesh,
                int sourceFace,
                int[] faceRecords,
                int targetFace
        ) {
            Objects.requireNonNull(mesh, "mesh");
            Objects.requireNonNull(faceRecords, "faceRecords");
            if (sourceFace < 0 || sourceFace >= mesh.faceCount()) {
                throw new IllegalArgumentException("source face lies outside the section mesh");
            }
            if (targetFace < 0 || checkedIntMultiply(checkedIntAdd(targetFace, 1), INTS_PER_FACE_RECORD) > faceRecords.length) {
                throw new IllegalArgumentException("target face lies outside the material record buffer");
            }
            int recordOffset = checkedIntMultiply(targetFace, INTS_PER_FACE_RECORD);
            faceRecords[recordOffset] = mesh.faceVoxelStateId(sourceFace);
            faceRecords[recordOffset + 1] = packedMeshFaceMetadata(mesh, sourceFace);
            faceRecords[recordOffset + 2] = mesh.faceMapColor(sourceFace);
            faceRecords[recordOffset + 3] = packedMeshFaceTextureInfo(mesh, sourceFace);
            faceRecords[recordOffset + 4] = mesh.faceUv(sourceFace, 0);
            faceRecords[recordOffset + 5] = mesh.faceUv(sourceFace, 1);
            faceRecords[recordOffset + 6] = mesh.faceUv(sourceFace, 2);
            faceRecords[recordOffset + 7] = mesh.faceUv(sourceFace, 3);
            faceRecords[recordOffset + 8] = mesh.faceVertexLighting(sourceFace, 0);
            faceRecords[recordOffset + 9] = mesh.faceVertexLighting(sourceFace, 1);
            faceRecords[recordOffset + 10] = mesh.faceVertexLighting(sourceFace, 2);
            faceRecords[recordOffset + 11] = mesh.faceVertexLighting(sourceFace, 3);
        }

        public static int intsPerFaceRecord() {
            return INTS_PER_FACE_RECORD;
        }

        private static int appendMeshFaceRecordHash(int hash, SectionTriangleMesh mesh, int sourceFace) {
            hash = 31 * hash + mesh.faceVoxelStateId(sourceFace);
            hash = 31 * hash + packedMeshFaceMetadata(mesh, sourceFace);
            hash = 31 * hash + mesh.faceMapColor(sourceFace);
            hash = 31 * hash + packedMeshFaceTextureInfo(mesh, sourceFace);
            hash = 31 * hash + mesh.faceUv(sourceFace, 0);
            hash = 31 * hash + mesh.faceUv(sourceFace, 1);
            hash = 31 * hash + mesh.faceUv(sourceFace, 2);
            hash = 31 * hash + mesh.faceUv(sourceFace, 3);
            hash = 31 * hash + mesh.faceVertexLighting(sourceFace, 0);
            hash = 31 * hash + mesh.faceVertexLighting(sourceFace, 1);
            hash = 31 * hash + mesh.faceVertexLighting(sourceFace, 2);
            return 31 * hash + mesh.faceVertexLighting(sourceFace, 3);
        }

        private boolean matchesMeshFaceRecord(SectionTriangleMesh mesh, int sourceFace, int targetFace) {
            return recordInt(targetFace, 0) == mesh.faceVoxelStateId(sourceFace)
                    && recordInt(targetFace, 1) == packedMeshFaceMetadata(mesh, sourceFace)
                    && recordInt(targetFace, 2) == mesh.faceMapColor(sourceFace)
                    && recordInt(targetFace, 3) == packedMeshFaceTextureInfo(mesh, sourceFace)
                    && recordInt(targetFace, 4) == mesh.faceUv(sourceFace, 0)
                    && recordInt(targetFace, 5) == mesh.faceUv(sourceFace, 1)
                    && recordInt(targetFace, 6) == mesh.faceUv(sourceFace, 2)
                    && recordInt(targetFace, 7) == mesh.faceUv(sourceFace, 3)
                    && recordInt(targetFace, 8) == mesh.faceVertexLighting(sourceFace, 0)
                    && recordInt(targetFace, 9) == mesh.faceVertexLighting(sourceFace, 1)
                    && recordInt(targetFace, 10) == mesh.faceVertexLighting(sourceFace, 2)
                    && recordInt(targetFace, 11) == mesh.faceVertexLighting(sourceFace, 3);
        }

        private static int packedMeshFaceMetadata(SectionTriangleMesh mesh, int sourceFace) {
            int direction = mesh.faceDirectionOrdinal(sourceFace);
            if (direction > 5) {
                throw new IllegalArgumentException("section face direction outside enum ordinal range: " + direction);
            }
            return packFaceMetadata(
                    mesh.faceFluidAmount(sourceFace),
                    direction,
                    mesh.faceLightEmission(sourceFace),
                    mesh.faceMaterialFlagBits(sourceFace)
            );
        }

        private static int packedMeshFaceTextureInfo(SectionTriangleMesh mesh, int sourceFace) {
            return packTextureInfo(
                    mesh.faceTextureId(sourceFace),
                    mesh.faceTinted(sourceFace),
                    mesh.faceAlphaCutout(sourceFace)
            );
        }

        public int faceCount() {
            return faceCount;
        }

        public int secondGeometryFaceOffset() {
            return secondGeometryFaceOffset;
        }

        public int primitivesPerMaterialRecord() {
            return primitivesPerMaterialRecord;
        }

        int fallbackColorFaceCount() {
            return fallbackColorFaceCount;
        }

        int fluidFaceCount() {
            return fluidFaceCount;
        }

        int emissiveFaceCount() {
            return emissiveFaceCount;
        }

        public int estimatedBytes() {
            return checkedIntMultiply(checkedIntMultiply(faceCount, INTS_PER_FACE_RECORD), Integer.BYTES);
        }

        public int faceRecordHash() {
            return hashCode;
        }

        public boolean meshBackedPublication() {
            return sourceMesh != null;
        }

        /**
         * Crosses the source-mesh -> persistent-material lifetime boundary.
         *
         * <p>Lazy mesh-backed publications are ideal while BLAS work is queued because the queue
         * already owns that mesh. A stable material slot must never retain the complete geometry
         * object graph after native BLAS upload, however. Materialize exactly one compact packed
         * record array at that boundary; snapshots and slot tables then share this immutable value.</p>
         */
        public SectionMaterial detachedPublication() {
            if (faceRecords != null) {
                return this;
            }
            int[] detachedRecords = new int[intCount()];
            writeTo(IntBuffer.wrap(detachedRecords));
            return new SectionMaterial(
                    detachedRecords,
                    secondGeometryFaceOffset,
                    primitivesPerMaterialRecord,
                    true
            );
        }

        public int[] faceRecords() {
            int[] materialized = new int[intCount()];
            writeTo(IntBuffer.wrap(materialized));
            return materialized;
        }

        @Override
        public int intCount() {
            return checkedIntMultiply(faceCount, INTS_PER_FACE_RECORD);
        }

        @Override
        public void writeTo(IntBuffer target) {
            writeFaceRangeTo(0, faceCount, target);
        }

        RtGpuBuffer.IntBufferWriter faceRange(int firstFace, int rangeFaceCount) {
            if (firstFace < 0 || rangeFaceCount <= 0 || firstFace + rangeFaceCount > faceCount) {
                throw new IllegalArgumentException("material face range is outside publication");
            }
            return new MaterialFaceRange(this, firstFace, rangeFaceCount);
        }

        private void writeFaceRangeTo(int firstFace, int rangeFaceCount, IntBuffer target) {
            Objects.requireNonNull(target, "target");
            int requiredInts = checkedIntMultiply(rangeFaceCount, INTS_PER_FACE_RECORD);
            if (target.remaining() < requiredInts) {
                throw new IllegalArgumentException("target lacks space for material face range");
            }
            for (int face = firstFace; face < firstFace + rangeFaceCount; face++) {
                for (int component = 0; component < INTS_PER_FACE_RECORD; component++) {
                    target.put(recordInt(face, component));
                }
            }
        }

        private void copyRecordsTo(int[] target, int firstInt) {
            Objects.requireNonNull(target, "target");
            if (firstInt < 0 || firstInt + intCount() > target.length) {
                throw new IllegalArgumentException("target material array is too small");
            }
            IntBuffer view = IntBuffer.wrap(target);
            view.position(firstInt);
            writeTo(view);
        }

        private boolean faceRecordEquals(int face, SectionMaterial other) {
            if (faceRecords != null && other.faceRecords != null
                    && !repeatedFaceRecord && !other.repeatedFaceRecord) {
                int offset = checkedIntMultiply(face, INTS_PER_FACE_RECORD);
                return Arrays.equals(
                        faceRecords,
                        offset,
                        offset + INTS_PER_FACE_RECORD,
                        other.faceRecords,
                        offset,
                        offset + INTS_PER_FACE_RECORD
                );
            }
            for (int component = 0; component < INTS_PER_FACE_RECORD; component++) {
                if (recordInt(face, component) != other.recordInt(face, component)) {
                    return false;
                }
            }
            return true;
        }

        private int recordInt(int targetFace, int component) {
            if (targetFace < 0 || targetFace >= faceCount || component < 0 || component >= INTS_PER_FACE_RECORD) {
                throw new IllegalArgumentException("material record coordinate is outside publication");
            }
            if (faceRecords != null) {
                return faceRecords[(repeatedFaceRecord ? 0 : targetFace * INTS_PER_FACE_RECORD) + component];
            }
            int sourceFace = sourceFaceOrder == null ? targetFace : sourceFaceOrder[targetFace];
            return switch (component) {
                case 0 -> sourceMesh.faceVoxelStateId(sourceFace);
                case 1 -> packedMeshFaceMetadata(sourceMesh, sourceFace);
                case 2 -> sourceMesh.faceMapColor(sourceFace);
                case 3 -> packedMeshFaceTextureInfo(sourceMesh, sourceFace);
                case 4, 5, 6, 7 -> sourceMesh.faceUv(sourceFace, component - 4);
                case 8, 9, 10, 11 -> sourceMesh.faceVertexLighting(sourceFace, component - 8);
                default -> throw new AssertionError("unreachable material component");
            };
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            return other instanceof SectionMaterial that
                    && hashCode == that.hashCode
                    && secondGeometryFaceOffset == that.secondGeometryFaceOffset
                    && primitivesPerMaterialRecord == that.primitivesPerMaterialRecord
                    && faceCount == that.faceCount
                    && recordsEqual(that);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        private boolean recordsEqual(SectionMaterial other) {
            for (int face = 0; face < faceCount; face++) {
                if (!faceRecordEquals(face, other)) {
                    return false;
                }
            }
            return true;
        }

        private static int[] packedSourceFaceOrder(
                SectionTriangleMesh mesh,
                int opaqueFaces,
                int alphaCutoutFaces
        ) {
            if (opaqueFaces == 0 || alphaCutoutFaces == 0) {
                return null;
            }
            int[] order = new int[mesh.faceCount()];
            int opaqueCursor = 0;
            int alphaCursor = opaqueFaces;
            for (int sourceFace = 0; sourceFace < mesh.faceCount(); sourceFace++) {
                order[mesh.faceAlphaCutout(sourceFace) ? alphaCursor++ : opaqueCursor++] = sourceFace;
            }
            return order;
        }

        private record MaterialFaceRange(
                SectionMaterial material,
                int firstFace,
                int faceCount
        ) implements RtGpuBuffer.IntBufferWriter {
            @Override
            public int intCount() {
                return checkedIntMultiply(faceCount, INTS_PER_FACE_RECORD);
            }

            @Override
            public void writeTo(IntBuffer target) {
                material.writeFaceRangeTo(firstFace, faceCount, target);
            }
        }

        private static int countFallbackColorFaces(int[] faceRecords) {
            int count = 0;
            for (int offset = 0; offset < faceRecords.length; offset += INTS_PER_FACE_RECORD) {
                if ((faceRecords[offset + 3] & TEXTURE_INFO_TEXTURE_ID_MASK) == 0) {
                    count++;
                }
            }
            return count;
        }

        private static int countFluidFaces(int[] faceRecords) {
            int count = 0;
            for (int offset = 0; offset < faceRecords.length; offset += INTS_PER_FACE_RECORD) {
                if ((faceRecords[offset + 1] & 0xFF) > 0) {
                    count++;
                }
            }
            return count;
        }

        private static int countEmissiveFaces(int[] faceRecords) {
            int count = 0;
            for (int offset = 0; offset < faceRecords.length; offset += INTS_PER_FACE_RECORD) {
                if (((faceRecords[offset + 1] >>> 16) & 0xFF) > 0) {
                    count++;
                }
            }
            return count;
        }
    }

    public static final class Snapshot {
        private static final int[] EMPTY_INT_ARRAY = new int[0];
        private final int[] sectionRecords;
        private final int[] faceRecords;
        private final List<SectionMaterial> sectionMaterials;
        private final int[] sectionFirstFaces;
        private final RtTextureCatalog.Snapshot textureSnapshot;
        private final long revision;
        private final int sectionCount;
        private final int faceCount;
        private final int instanceLayoutHash;
        private final SnapshotSignature signature;
        /*
         * A successor carries only the identity token of its immediate predecessor.
         * Holding the predecessor Snapshot here would retain every historical world
         * material payload while an asynchronous upload is active.  The token keeps
         * the dirty-range proof without turning the frame stream into a retention
         * chain.
         */
        private final Object snapshotIdentity = new Object();
        private final Object incrementalBaseIdentity;
        private final int[] dirtySectionSlots;
        /* Packed triples: section slot, first dirty face within the section, face count. */
        private final int[] dirtyFaceRuns;

        private Snapshot(
                int[] sectionRecords,
                int[] faceRecords,
                RtTextureCatalog.Snapshot textureSnapshot,
                long revision
        ) {
            this.sectionRecords = validateRecords(sectionRecords, "sectionRecords", INTS_PER_SECTION_RECORD);
            this.faceRecords = validateRecords(faceRecords, "faceRecords", INTS_PER_FACE_RECORD);
            this.sectionMaterials = List.of();
            this.sectionFirstFaces = sectionFirstFacesFromRecords(this.sectionRecords);
            this.textureSnapshot = Objects.requireNonNull(textureSnapshot, "textureSnapshot");
            if (revision < 0L) {
                throw new IllegalArgumentException("revision must not be negative");
            }
            validateSectionRanges(this.sectionRecords, this.faceRecords.length / INTS_PER_FACE_RECORD);
            this.revision = revision;
            this.sectionCount = this.sectionRecords.length / INTS_PER_SECTION_RECORD;
            this.faceCount = this.faceRecords.length / INTS_PER_FACE_RECORD;
            this.instanceLayoutHash = Arrays.hashCode(this.sectionRecords);
            this.signature = signatureFromFaceRecords(
                    this.sectionRecords,
                    this.faceRecords,
                    this.sectionCount,
                    this.faceCount,
                    this.textureSnapshot
            );
            this.incrementalBaseIdentity = null;
            this.dirtySectionSlots = EMPTY_INT_ARRAY;
            this.dirtyFaceRuns = EMPTY_INT_ARRAY;
        }

        Snapshot(
                List<SectionMaterial> sectionMaterials,
                int[] sectionFirstFaces,
                int faceCapacity,
                RtTextureCatalog.Snapshot textureSnapshot,
                long revision,
                int instanceLayoutHash
        ) {
            Objects.requireNonNull(sectionMaterials, "sectionMaterials");
            Objects.requireNonNull(sectionFirstFaces, "sectionFirstFaces");
            this.sectionRecords = null;
            this.faceRecords = null;
            this.sectionMaterials = List.copyOf(sectionMaterials);
            this.sectionFirstFaces = Arrays.copyOf(sectionFirstFaces, sectionFirstFaces.length);
            this.textureSnapshot = Objects.requireNonNull(textureSnapshot, "textureSnapshot");
            if (revision < 0L) {
                throw new IllegalArgumentException("revision must not be negative");
            }
            if (this.sectionMaterials.isEmpty()) {
                throw new IllegalArgumentException("streaming material snapshot must contain at least one section");
            }
            if (this.sectionFirstFaces.length != this.sectionMaterials.size()) {
                throw new IllegalArgumentException("section first-face table must match section material count");
            }
            if (faceCapacity <= 0) {
                throw new IllegalArgumentException("faceCapacity must be positive");
            }
            int totalFaces = 0;
            int fallbackColorFaces = 0;
            int fluidFaces = 0;
            int emissiveFaces = 0;
            for (int index = 0; index < this.sectionMaterials.size(); index++) {
                int firstFace = this.sectionFirstFaces[index];
                SectionMaterial checked = Objects.requireNonNull(this.sectionMaterials.get(index), "section material");
                if (firstFace < 0 || firstFace > faceCapacity || firstFace + checked.faceCount() > faceCapacity) {
                    throw new IllegalArgumentException("section material face range exceeds sparse face table");
                }
                totalFaces = Math.max(totalFaces, firstFace + checked.faceCount());
                fallbackColorFaces = checkedIntAdd(fallbackColorFaces, checked.fallbackColorFaceCount());
                fluidFaces = checkedIntAdd(fluidFaces, checked.fluidFaceCount());
                emissiveFaces = checkedIntAdd(emissiveFaces, checked.emissiveFaceCount());
            }
            if (totalFaces > faceCapacity) {
                throw new IllegalArgumentException("faceCapacity is smaller than material ranges");
            }
            this.revision = revision;
            this.sectionCount = this.sectionMaterials.size();
            this.faceCount = faceCapacity;
            this.instanceLayoutHash = instanceLayoutHash;
            /*
             * This signature describes only bytes uploaded to the material buffers.
             * TLAS instance order/identity is tracked separately by instanceLayoutHash;
             * folding it into upload dedupe makes harmless TLAS churn look like a
             * 256 MiB material-buffer change during chunk streaming.
             */
            this.signature = new SnapshotSignature(
                    this.sectionCount,
                    this.faceCount,
                    fallbackColorFaces,
                    fluidFaces,
                    emissiveFaces,
                    this.textureSnapshot.textureCount(),
                    this.textureSnapshot.texturePixelCount(),
                    this.textureSnapshot.revision(),
                    sectionRecordHash(this.sectionMaterials, this.sectionFirstFaces),
                    faceRecordHash(this.sectionMaterials, this.sectionFirstFaces)
            );
            this.incrementalBaseIdentity = null;
            this.dirtySectionSlots = EMPTY_INT_ARRAY;
            this.dirtyFaceRuns = EMPTY_INT_ARRAY;
        }

        Snapshot(
                Snapshot previousSnapshot,
                List<SectionMaterial> sectionMaterials,
                int[] sectionFirstFaces,
                int faceCapacity,
                RtTextureCatalog.Snapshot textureSnapshot,
                long revision,
                int instanceLayoutHash,
                int[] dirtySectionSlots,
                int fallbackColorFaces,
                int fluidFaces,
                int emissiveFaces,
                int sectionRecordHash,
                int faceRecordHash
        ) {
            this.sectionRecords = null;
            this.faceRecords = null;
            /*
             * The unchanged incremental path already owns an immutable list.
             * Re-copying it turns a texture-only or no-op slot publication into
             * an O(world sections) allocation. A changed path is represented by
             * a private mutable ArrayList and still receives a defensive freeze.
             */
            this.sectionMaterials = sectionMaterials == previousSnapshot.sectionMaterials
                    ? previousSnapshot.sectionMaterials
                    : List.copyOf(sectionMaterials);
            this.sectionFirstFaces = sectionFirstFaces;
            this.textureSnapshot = Objects.requireNonNull(textureSnapshot, "textureSnapshot");
            if (revision < 0L) {
                throw new IllegalArgumentException("revision must not be negative");
            }
            if (faceCapacity <= 0) {
                throw new IllegalArgumentException("faceCapacity must be positive");
            }
            this.revision = revision;
            this.sectionCount = this.sectionMaterials.size();
            this.faceCount = faceCapacity;
            this.instanceLayoutHash = instanceLayoutHash;
            this.signature = new SnapshotSignature(
                    this.sectionCount,
                    this.faceCount,
                    fallbackColorFaces,
                    fluidFaces,
                    emissiveFaces,
                    this.textureSnapshot.textureCount(),
                    this.textureSnapshot.texturePixelCount(),
                    this.textureSnapshot.revision(),
                    sectionRecordHash,
                    faceRecordHash
            );
            this.incrementalBaseIdentity = Objects.requireNonNull(previousSnapshot, "previousSnapshot").snapshotIdentity;
            this.dirtySectionSlots = Arrays.copyOf(dirtySectionSlots, dirtySectionSlots.length);
            this.dirtyFaceRuns = buildDirtyFaceRuns(previousSnapshot, this.dirtySectionSlots);
        }

        private Snapshot(
                Snapshot previousSnapshot,
                RtTextureCatalog.Snapshot textureSnapshot,
                long revision,
                int instanceLayoutHash
        ) {
            previousSnapshot = Objects.requireNonNull(previousSnapshot, "previousSnapshot");
            this.sectionRecords = previousSnapshot.sectionRecords;
            this.faceRecords = previousSnapshot.faceRecords;
            this.sectionMaterials = previousSnapshot.sectionMaterials;
            this.sectionFirstFaces = previousSnapshot.sectionFirstFaces;
            this.textureSnapshot = Objects.requireNonNull(textureSnapshot, "textureSnapshot");
            if (revision < 0L) {
                throw new IllegalArgumentException("revision must not be negative");
            }
            this.revision = revision;
            this.sectionCount = previousSnapshot.sectionCount;
            this.faceCount = previousSnapshot.faceCount;
            this.instanceLayoutHash = instanceLayoutHash;
            this.signature = new SnapshotSignature(
                    this.sectionCount,
                    this.faceCount,
                    previousSnapshot.signature.fallbackColorFaceCount(),
                    previousSnapshot.signature.fluidFaceCount(),
                    previousSnapshot.signature.emissiveFaceCount(),
                    this.textureSnapshot.textureCount(),
                    this.textureSnapshot.texturePixelCount(),
                    this.textureSnapshot.revision(),
                    previousSnapshot.signature.sectionRecordHash(),
                    previousSnapshot.signature.faceRecordHash()
            );
            this.incrementalBaseIdentity = previousSnapshot.snapshotIdentity;
            this.dirtySectionSlots = EMPTY_INT_ARRAY;
            this.dirtyFaceRuns = EMPTY_INT_ARRAY;
        }

        /**
         * Freezes upload provenance while the material owner still knows which successor slots
         * changed.  Mesh-backed publications name an immutable source generation, so a different
         * publication is uploaded as one section run instead of re-decoding twelve derived values
         * per face on the render thread.  Array-backed external publications retain exact sparse
         * runs because their packed records are already directly addressable.
         */
        private int[] buildDirtyFaceRuns(Snapshot previousSnapshot, int[] dirtySlots) {
            if (dirtySlots.length == 0) {
                return EMPTY_INT_ARRAY;
            }
            DirtyFaceRunBuilder runs = new DirtyFaceRunBuilder();
            for (int slot : dirtySlots) {
                if (slot < 0) {
                    throw new IllegalArgumentException("dirty material slot must not be negative");
                }
                if (slot >= sectionCount) {
                    continue;
                }
                SectionMaterial material = sectionMaterials.get(slot);
                if (slot >= previousSnapshot.sectionCount) {
                    runs.add(slot, 0, material.faceCount());
                    continue;
                }
                SectionMaterial previousMaterial = previousSnapshot.sectionMaterial(slot);
                int firstFace = sectionFirstFaces[slot];
                int previousFirstFace = previousSnapshot.firstFace(slot);
                if (firstFace != previousFirstFace || material.faceCount() != previousMaterial.faceCount()) {
                    runs.add(slot, 0, material.faceCount());
                    continue;
                }
                if (material == previousMaterial) {
                    continue;
                }
                if (material.meshBackedPublication() || previousMaterial.meshBackedPublication()) {
                    runs.add(slot, 0, material.faceCount());
                    continue;
                }

                int dirtyRunStart = -1;
                for (int face = 0; face < material.faceCount(); face++) {
                    if (!material.faceRecordEquals(face, previousMaterial)) {
                        if (dirtyRunStart < 0) {
                            dirtyRunStart = face;
                        }
                    } else if (dirtyRunStart >= 0) {
                        runs.add(slot, dirtyRunStart, face - dirtyRunStart);
                        dirtyRunStart = -1;
                    }
                }
                if (dirtyRunStart >= 0) {
                    runs.add(slot, dirtyRunStart, material.faceCount() - dirtyRunStart);
                }
            }
            return runs.build();
        }

        private static final class DirtyFaceRunBuilder {
            private int[] runs;
            private int runCount;

            private void add(int sectionSlot, int firstFace, int faceCount) {
                if (sectionSlot < 0 || firstFace < 0 || faceCount <= 0) {
                    throw new IllegalArgumentException("dirty face run must be positive and in a valid section slot");
                }
                int requiredInts = checkedIntMultiply(checkedIntAdd(runCount, 1), 3);
                if (runs == null) {
                    runs = new int[Math.max(12, requiredInts)];
                } else if (requiredInts > runs.length) {
                    runs = Arrays.copyOf(runs, Math.max(requiredInts, checkedIntMultiply(runs.length, 2)));
                }
                int offset = checkedIntMultiply(runCount, 3);
                runs[offset] = sectionSlot;
                runs[offset + 1] = firstFace;
                runs[offset + 2] = faceCount;
                runCount++;
            }

            private int[] build() {
                return runCount == 0
                        ? EMPTY_INT_ARRAY
                        : Arrays.copyOf(runs, checkedIntMultiply(runCount, 3));
            }
        }

        public static Snapshot empty() {
            return new Snapshot(new int[0], new int[0], RtTextureCatalog.snapshot(), 0L);
        }

        public static Snapshot fromSectionMaterials(List<SectionMaterial> sectionMaterials) {
            return fromSectionMaterials(sectionMaterials, 0L);
        }

        public static Snapshot fromSectionMaterials(List<SectionMaterial> sectionMaterials, long revision) {
            return fromSectionMaterials(
                    sectionMaterials,
                    revision,
                    implicitInstanceLayoutHash(sectionMaterials)
            );
        }

        public static Snapshot fromSectionMaterials(
                List<SectionMaterial> sectionMaterials,
                long revision,
                int instanceLayoutHash
        ) {
            Objects.requireNonNull(sectionMaterials, "sectionMaterials");
            if (revision < 0L) {
                throw new IllegalArgumentException("revision must not be negative");
            }
            if (sectionMaterials.isEmpty()) {
                return empty();
            }

            List<SectionMaterial> materials = new ArrayList<>(sectionMaterials.size());
            for (SectionMaterial material : sectionMaterials) {
                SectionMaterial checked = Objects.requireNonNull(material, "section material");
                materials.add(checked);
            }
            int[] firstFaces = sequentialFirstFaces(materials);
            return new Snapshot(
                    materials,
                    firstFaces,
                    faceCapacity(materials, firstFaces),
                    RtTextureCatalog.snapshot(),
                    revision,
                    instanceLayoutHash
            );
        }

        public static Snapshot fromSectionMaterialsIncremental(
                Snapshot previousSnapshot,
                List<SectionMaterial> sectionMaterials,
                long revision,
                int instanceLayoutHash
        ) {
            Objects.requireNonNull(previousSnapshot, "previousSnapshot");
            Objects.requireNonNull(sectionMaterials, "sectionMaterials");
            if (revision < 0L) {
                throw new IllegalArgumentException("revision must not be negative");
            }
            if (sectionMaterials.isEmpty()) {
                return empty();
            }
            if (previousSnapshot.sectionRecords != null
                    || previousSnapshot.sectionCount != sectionMaterials.size()) {
                return fromSectionMaterials(sectionMaterials, revision, instanceLayoutHash);
            }

            int faceCapacity = 0;
            /*
             * Stable RT material frames are the common case.  UE GPUScene
             * batches only actual dirty primitive indices; allocating a full
             * section-count scratch array before discovering zero changes
             * turned every material snapshot into avoidable GC pressure.
             */
            int[] dirtySlots = null;
            int dirtyCount = 0;
            for (int slot = 0; slot < sectionMaterials.size(); slot++) {
                SectionMaterial nextMaterial = Objects.requireNonNull(sectionMaterials.get(slot), "section material");
                if (faceCapacity != previousSnapshot.sectionFirstFaces[slot]) {
                    return fromSectionMaterials(sectionMaterials, revision, instanceLayoutHash);
                }
                faceCapacity = checkedIntAdd(faceCapacity, nextMaterial.faceCount());
                if (!nextMaterial.equals(previousSnapshot.sectionMaterials.get(slot))) {
                    if (dirtySlots == null) {
                        dirtySlots = new int[Math.min(sectionMaterials.size(), 16)];
                    } else if (dirtyCount == dirtySlots.length) {
                        dirtySlots = Arrays.copyOf(dirtySlots, Math.min(
                                sectionMaterials.size(),
                                Math.max(dirtySlots.length + 1, dirtySlots.length * 2)
                        ));
                    }
                    dirtySlots[dirtyCount++] = slot;
                }
            }
            if (faceCapacity != previousSnapshot.faceCount) {
                return fromSectionMaterials(sectionMaterials, revision, instanceLayoutHash);
            }
            dirtySlots = dirtyCount == 0 ? EMPTY_INT_ARRAY : Arrays.copyOf(dirtySlots, dirtyCount);

            List<SectionMaterial> nextMaterials = previousSnapshot.sectionMaterials;
            int fallbackColorFaces = previousSnapshot.signature.fallbackColorFaceCount();
            int fluidFaces = previousSnapshot.signature.fluidFaceCount();
            int emissiveFaces = previousSnapshot.signature.emissiveFaceCount();
            int sectionRecordHash = previousSnapshot.signature.sectionRecordHash();
            int faceRecordHash = previousSnapshot.signature.faceRecordHash();
            for (int slot : dirtySlots) {
                SectionMaterial previousMaterial = previousSnapshot.sectionMaterials.get(slot);
                SectionMaterial nextMaterial = sectionMaterials.get(slot);
                if (nextMaterials == previousSnapshot.sectionMaterials) {
                    nextMaterials = new ArrayList<>(previousSnapshot.sectionMaterials);
                }
                nextMaterials.set(slot, nextMaterial);
                fallbackColorFaces = checkedIntAdd(
                        fallbackColorFaces - previousMaterial.fallbackColorFaceCount(),
                        nextMaterial.fallbackColorFaceCount()
                );
                fluidFaces = checkedIntAdd(
                        fluidFaces - previousMaterial.fluidFaceCount(),
                        nextMaterial.fluidFaceCount()
                );
                emissiveFaces = checkedIntAdd(
                        emissiveFaces - previousMaterial.emissiveFaceCount(),
                        nextMaterial.emissiveFaceCount()
                );
                int sectionExponent = checkedIntMultiply(
                        previousSnapshot.sectionCount - slot - 1,
                        INTS_PER_SECTION_RECORD
                );
                sectionRecordHash += (sectionRecordBlockHash(previousSnapshot.sectionFirstFaces[slot], nextMaterial)
                        - sectionRecordBlockHash(previousSnapshot.sectionFirstFaces[slot], previousMaterial))
                        * powerOf31(sectionExponent);
                int faceExponent = checkedIntMultiply(previousSnapshot.sectionCount - slot - 1, 2);
                faceRecordHash += (faceRecordBlockHash(previousSnapshot.sectionFirstFaces[slot], nextMaterial)
                        - faceRecordBlockHash(previousSnapshot.sectionFirstFaces[slot], previousMaterial))
                        * powerOf31(faceExponent);
            }

            return new Snapshot(
                    previousSnapshot,
                    nextMaterials,
                    previousSnapshot.sectionFirstFaces,
                    faceCapacity,
                    RtTextureCatalog.snapshot(),
                    revision,
                    instanceLayoutHash,
                    dirtySlots,
                    fallbackColorFaces,
                    fluidFaces,
                    emissiveFaces,
                    sectionRecordHash,
                    faceRecordHash
            );
        }

        public static Snapshot fromSectionMaterialsIncremental(
                Snapshot previousSnapshot,
                List<SectionMaterial> sectionMaterials,
                int[] dirtySlotHints,
                long revision,
                int instanceLayoutHash
        ) {
            Objects.requireNonNull(previousSnapshot, "previousSnapshot");
            Objects.requireNonNull(sectionMaterials, "sectionMaterials");
            Objects.requireNonNull(dirtySlotHints, "dirtySlotHints");
            if (revision < 0L) {
                throw new IllegalArgumentException("revision must not be negative");
            }
            if (sectionMaterials.isEmpty()) {
                return empty();
            }
            if (previousSnapshot.sectionRecords != null
                    || previousSnapshot.sectionCount != sectionMaterials.size()) {
                return fromSectionMaterials(sectionMaterials, revision, instanceLayoutHash);
            }

            int previousHint = -1;
            for (int slot : dirtySlotHints) {
                if (slot <= previousHint || slot >= sectionMaterials.size()) {
                    throw new IllegalArgumentException("material dirty slot hints must be sorted, unique, and in range");
                }
                previousHint = slot;
                SectionMaterial previousMaterial = previousSnapshot.sectionMaterials.get(slot);
                SectionMaterial nextMaterial = Objects.requireNonNull(sectionMaterials.get(slot), "section material");
                if (previousMaterial.faceCount() != nextMaterial.faceCount()) {
                    return fromSectionMaterials(sectionMaterials, revision, instanceLayoutHash);
                }
            }

            List<SectionMaterial> nextMaterials = previousSnapshot.sectionMaterials;
            int fallbackColorFaces = previousSnapshot.signature.fallbackColorFaceCount();
            int fluidFaces = previousSnapshot.signature.fluidFaceCount();
            int emissiveFaces = previousSnapshot.signature.emissiveFaceCount();
            int sectionRecordHash = previousSnapshot.signature.sectionRecordHash();
            int faceRecordHash = previousSnapshot.signature.faceRecordHash();
            int[] actualDirtySlots = new int[dirtySlotHints.length];
            int actualDirtyCount = 0;
            for (int slot : dirtySlotHints) {
                SectionMaterial previousMaterial = previousSnapshot.sectionMaterials.get(slot);
                SectionMaterial nextMaterial = sectionMaterials.get(slot);
                if (previousMaterial.equals(nextMaterial)) {
                    continue;
                }
                actualDirtySlots[actualDirtyCount++] = slot;
                if (nextMaterials == previousSnapshot.sectionMaterials) {
                    nextMaterials = new ArrayList<>(previousSnapshot.sectionMaterials);
                }
                nextMaterials.set(slot, nextMaterial);
                fallbackColorFaces = checkedIntAdd(
                        fallbackColorFaces - previousMaterial.fallbackColorFaceCount(),
                        nextMaterial.fallbackColorFaceCount()
                );
                fluidFaces = checkedIntAdd(
                        fluidFaces - previousMaterial.fluidFaceCount(),
                        nextMaterial.fluidFaceCount()
                );
                emissiveFaces = checkedIntAdd(
                        emissiveFaces - previousMaterial.emissiveFaceCount(),
                        nextMaterial.emissiveFaceCount()
                );
                int sectionExponent = checkedIntMultiply(
                        previousSnapshot.sectionCount - slot - 1,
                        INTS_PER_SECTION_RECORD
                );
                int firstFace = previousSnapshot.sectionFirstFaces[slot];
                sectionRecordHash += (sectionRecordBlockHash(firstFace, nextMaterial)
                        - sectionRecordBlockHash(firstFace, previousMaterial)) * powerOf31(sectionExponent);
                int faceExponent = checkedIntMultiply(previousSnapshot.sectionCount - slot - 1, 2);
                faceRecordHash += (faceRecordBlockHash(firstFace, nextMaterial)
                        - faceRecordBlockHash(firstFace, previousMaterial)) * powerOf31(faceExponent);
            }
            actualDirtySlots = actualDirtyCount == actualDirtySlots.length
                    ? actualDirtySlots
                    : Arrays.copyOf(actualDirtySlots, actualDirtyCount);
            return new Snapshot(
                    previousSnapshot,
                    nextMaterials,
                    previousSnapshot.sectionFirstFaces,
                    previousSnapshot.faceCount,
                    RtTextureCatalog.snapshot(),
                    revision,
                    instanceLayoutHash,
                    actualDirtySlots,
                    fallbackColorFaces,
                    fluidFaces,
                    emissiveFaces,
                    sectionRecordHash,
                    faceRecordHash
            );
        }

        public static Snapshot fromMaterialSlots(
                List<SectionMaterial> sectionMaterials,
                int[] sectionFirstFaces,
                int faceCapacity,
                long revision,
                int instanceLayoutHash
        ) {
            Objects.requireNonNull(sectionMaterials, "sectionMaterials");
            Objects.requireNonNull(sectionFirstFaces, "sectionFirstFaces");
            if (revision < 0L) {
                throw new IllegalArgumentException("revision must not be negative");
            }
            if (sectionMaterials.isEmpty()) {
                return empty();
            }
            List<SectionMaterial> materials = new ArrayList<>(sectionMaterials.size());
            for (SectionMaterial material : sectionMaterials) {
                materials.add(Objects.requireNonNull(material, "section material"));
            }
            return new Snapshot(
                    materials,
                    sectionFirstFaces,
                    faceCapacity,
                    RtTextureCatalog.snapshot(),
                    revision,
                    instanceLayoutHash
            );
        }

        static Snapshot fromMaterialSlotsIncremental(
                Snapshot previousSnapshot,
                MaterialSlotAllocator<?> materialSlots,
                long revision,
                int instanceLayoutHash
        ) {
            Objects.requireNonNull(previousSnapshot, "previousSnapshot");
            Objects.requireNonNull(materialSlots, "materialSlots");
            List<SectionMaterial> sourceMaterials = materialSlots.sectionMaterials();
            int faceCapacity = materialSlots.faceCapacity();
            int[] dirtySlots = materialSlots.consumeDirtySlots();

            if (sourceMaterials.isEmpty()) {
                return empty();
            }

            if (previousSnapshot.sectionRecords != null
                    || previousSnapshot.sectionCount != sourceMaterials.size()
                    || previousSnapshot.faceCount != faceCapacity) {
                return fromMaterialSlots(
                        sourceMaterials,
                        materialSlots.firstFacesArray(),
                        faceCapacity,
                        revision,
                        instanceLayoutHash
                );
            }

            List<SectionMaterial> nextMaterials = previousSnapshot.sectionMaterials;
            int[] nextFirstFaces = previousSnapshot.sectionFirstFaces;
            int fallbackColorFaces = previousSnapshot.signature.fallbackColorFaceCount();
            int fluidFaces = previousSnapshot.signature.fluidFaceCount();
            int emissiveFaces = previousSnapshot.signature.emissiveFaceCount();
            int sectionRecordHash = previousSnapshot.signature.sectionRecordHash();
            int faceRecordHash = previousSnapshot.signature.faceRecordHash();

            for (int slot : dirtySlots) {
                if (slot < 0 || slot >= sourceMaterials.size()) {
                    return fromMaterialSlots(
                            sourceMaterials,
                            materialSlots.firstFacesArray(),
                            faceCapacity,
                            revision,
                            instanceLayoutHash
                    );
                }
                SectionMaterial previousMaterial = previousSnapshot.sectionMaterials.get(slot);
                SectionMaterial nextMaterial = Objects.requireNonNull(sourceMaterials.get(slot), "section material");
                int previousFirstFace = previousSnapshot.sectionFirstFaces[slot];
                int nextFirstFace = materialSlots.firstFace(slot);
                if (nextFirstFace < 0 || nextFirstFace > faceCapacity
                        || nextFirstFace + nextMaterial.faceCount() > faceCapacity) {
                    throw new IllegalStateException("dirty material slot face range exceeds sparse face table: " + slot);
                }
                if (previousMaterial.equals(nextMaterial) && previousFirstFace == nextFirstFace) {
                    continue;
                }
                if (nextMaterials == previousSnapshot.sectionMaterials) {
                    nextMaterials = new ArrayList<>(previousSnapshot.sectionMaterials);
                }
                nextMaterials.set(slot, nextMaterial);
                if (nextFirstFaces == previousSnapshot.sectionFirstFaces) {
                    nextFirstFaces = Arrays.copyOf(previousSnapshot.sectionFirstFaces, previousSnapshot.sectionFirstFaces.length);
                }
                nextFirstFaces[slot] = nextFirstFace;

                fallbackColorFaces = checkedIntAdd(
                        fallbackColorFaces - previousMaterial.fallbackColorFaceCount(),
                        nextMaterial.fallbackColorFaceCount()
                );
                fluidFaces = checkedIntAdd(
                        fluidFaces - previousMaterial.fluidFaceCount(),
                        nextMaterial.fluidFaceCount()
                );
                emissiveFaces = checkedIntAdd(
                        emissiveFaces - previousMaterial.emissiveFaceCount(),
                        nextMaterial.emissiveFaceCount()
                );
                int sectionExponent = checkedIntMultiply(
                        previousSnapshot.sectionCount - slot - 1,
                        INTS_PER_SECTION_RECORD
                );
                sectionRecordHash += (sectionRecordBlockHash(nextFirstFace, nextMaterial)
                        - sectionRecordBlockHash(previousFirstFace, previousMaterial))
                        * powerOf31(sectionExponent);
                int faceExponent = checkedIntMultiply(previousSnapshot.sectionCount - slot - 1, 2);
                faceRecordHash += (faceRecordBlockHash(nextFirstFace, nextMaterial)
                        - faceRecordBlockHash(previousFirstFace, previousMaterial))
                        * powerOf31(faceExponent);
            }

            return new Snapshot(
                    previousSnapshot,
                    nextMaterials,
                    nextFirstFaces,
                    faceCapacity,
                    RtTextureCatalog.snapshot(),
                    revision,
                    instanceLayoutHash,
                    dirtySlots,
                    fallbackColorFaces,
                    fluidFaces,
                    emissiveFaces,
                    sectionRecordHash,
                    faceRecordHash
            );
        }

        public static Snapshot composeIncremental(
                Snapshot previousComposite,
                Snapshot previousBase,
                Snapshot nextBase,
                Snapshot previousAppended,
                Snapshot nextAppended,
                long revision,
                int instanceLayoutHash
        ) {
            return RtMaterialSnapshotComposer.composeIncremental(
                    previousComposite,
                    previousBase,
                    nextBase,
                    previousAppended,
                    nextAppended,
                    revision,
                    instanceLayoutHash
            );
        }

        public static Snapshot compose(
                Snapshot base,
                List<SectionMaterial> appendedMaterials,
                long revision,
                int instanceLayoutHash
        ) {
            return RtMaterialSnapshotComposer.compose(base, appendedMaterials, revision, instanceLayoutHash);
        }

        public static Snapshot compose(
                Snapshot base,
                Snapshot appended,
                long revision,
                int instanceLayoutHash
        ) {
            return RtMaterialSnapshotComposer.compose(base, appended, revision, instanceLayoutHash);
        }

        /**
         * Retains an immutable material-prefix generation without exposing face-range ownership.
         * Split TLAS lanes use this to preserve the descriptor-visible terrain namespace while
         * replacing only the independently updated dynamic suffix.
         */
        public Snapshot prefix(int prefixSections, long revision, int instanceLayoutHash) {
            return RtMaterialSnapshotComposer.prefix(this, prefixSections, revision, instanceLayoutHash);
        }

        /**
         * Retains the independently published material suffix and rebases its face offsets to zero.
         * Split terrain/dynamic descriptor transactions use this to combine a newer terrain prefix
         * with the exact dynamic generation already visible, without retaining or re-packing the
         * superseded terrain records.
         */
        public Snapshot suffix(int prefixSections, long revision, int instanceLayoutHash) {
            return RtMaterialSnapshotComposer.suffix(this, prefixSections, revision, instanceLayoutHash);
        }

        public int sectionCount() {
            return sectionCount;
        }

        public int faceCount() {
            return faceCount;
        }

        public RtTextureCatalog.Snapshot textureSnapshot() {
            return textureSnapshot;
        }

        public Snapshot withTextureSnapshot(RtTextureCatalog.Snapshot replacementTextureSnapshot) {
            Objects.requireNonNull(replacementTextureSnapshot, "replacementTextureSnapshot");
            if (textureSnapshot.equals(replacementTextureSnapshot)) {
                return this;
            }
            return new Snapshot(this, replacementTextureSnapshot, revision, instanceLayoutHash);
        }

        public Snapshot withCurrentTextureSnapshot() {
            return withTextureSnapshot(RtTextureCatalog.snapshot());
        }

        public long revision() {
            return revision;
        }

        public int fallbackColorFaceCount() {
            return signature.fallbackColorFaceCount();
        }

        public int fluidFaceCount() {
            return signature.fluidFaceCount();
        }

        public int emissiveFaceCount() {
            return signature.emissiveFaceCount();
        }

        public SnapshotSignature signature() {
            return signature;
        }

        public int instanceLayoutHash() {
            return instanceLayoutHash;
        }

        public MaterialUploadDiff uploadDiffFrom(Snapshot previousSnapshot, boolean forceFullUpload) {
            return RtMaterialSnapshotUploadPlanner.diff(this, previousSnapshot, forceFullUpload);
        }

        public List<SectionMaterial> sectionMaterials() {
            if (sectionRecords == null) {
                return sectionMaterials;
            }

            List<SectionMaterial> materials = new ArrayList<>(sectionCount);
            for (int section = 0; section < sectionCount; section++) {
                int sectionOffset = section * INTS_PER_SECTION_RECORD;
                int firstFace = sectionRecords[sectionOffset];
                int faceCount = sectionRecords[sectionOffset + 1];
                int firstInt = checkedIntMultiply(firstFace, INTS_PER_FACE_RECORD);
                int intCount = checkedIntMultiply(faceCount, INTS_PER_FACE_RECORD);
                int[] sectionFaceRecords = new int[intCount];
                System.arraycopy(faceRecords, firstInt, sectionFaceRecords, 0, intCount);
                materials.add(new SectionMaterial(
                        sectionFaceRecords,
                        sectionRecords[sectionOffset + 2],
                        sectionRecords[sectionOffset + 3]
                ));
            }
            return List.copyOf(materials);
        }

        public int[] sectionRecords() {
            if (sectionRecords != null) {
                return Arrays.copyOf(sectionRecords, sectionRecords.length);
            }
            return buildSectionRecords();
        }

        public int[] faceRecords() {
            if (faceRecords != null) {
                return Arrays.copyOf(faceRecords, faceRecords.length);
            }
            int[] materializedFaceRecords = new int[checkedIntMultiply(faceCount, INTS_PER_FACE_RECORD)];
            for (int section = 0; section < sectionMaterials.size(); section++) {
                SectionMaterial material = sectionMaterials.get(section);
                material.copyRecordsTo(
                        materializedFaceRecords,
                        sectionFirstFaces[section] * INTS_PER_FACE_RECORD
                );
            }
            return materializedFaceRecords;
        }

        public int[] sectionFirstFaces() {
            return Arrays.copyOf(sectionFirstFaces, sectionFirstFaces.length);
        }

        List<SectionMaterial> sectionMaterialsUnsafe() {
            return sectionMaterials;
        }

        int[] sectionFirstFacesUnsafe() {
            return sectionFirstFaces;
        }

        void writeSectionRecordsTo(RtGpuBuffer target) {
            Objects.requireNonNull(target, "target").writeInts(
                    sectionRecords == null ? buildSectionRecords() : sectionRecords
            );
        }

        void writeFaceRecordsTo(RtGpuBuffer target) {
            Objects.requireNonNull(target, "target");
            target.writeInts(faceRecords());
        }

        private int[] buildSectionRecords() {
            int[] materializedSectionRecords = new int[checkedIntMultiply(sectionCount, INTS_PER_SECTION_RECORD)];
            for (int section = 0; section < sectionMaterials.size(); section++) {
                SectionMaterial material = sectionMaterials.get(section);
                int sectionOffset = section * INTS_PER_SECTION_RECORD;
                materializedSectionRecords[sectionOffset] = sectionFirstFaces[section];
                materializedSectionRecords[sectionOffset + 1] = material.faceCount();
                materializedSectionRecords[sectionOffset + 2] = material.secondGeometryFaceOffset();
                materializedSectionRecords[sectionOffset + 3] = material.primitivesPerMaterialRecord();
            }
            return materializedSectionRecords;
        }

        RtMaterialDirtyUploadPlan sectionUploadPlan(Snapshot previousSnapshot, boolean forceFullUpload) {
            return RtMaterialSnapshotUploadPlanner.planSections(this, previousSnapshot, forceFullUpload);
        }

        RtMaterialDirtyUploadPlan faceUploadPlan(Snapshot previousSnapshot, boolean forceFullUpload) {
            return RtMaterialSnapshotUploadPlanner.planFaces(this, previousSnapshot, forceFullUpload);
        }

        int[] sectionRecord(int section) {
            SectionMaterial material = sectionMaterial(section);
            return new int[]{
                    firstFace(section),
                    material.faceCount(),
                    material.secondGeometryFaceOffset(),
                    material.primitivesPerMaterialRecord()
            };
        }

        boolean sectionRecordEquals(Snapshot previousSnapshot, int section) {
            SectionMaterial material = sectionMaterial(section);
            SectionMaterial previousMaterial = previousSnapshot.sectionMaterial(section);
            return firstFace(section) == previousSnapshot.firstFace(section)
                    && material.faceCount() == previousMaterial.faceCount()
                    && material.secondGeometryFaceOffset() == previousMaterial.secondGeometryFaceOffset()
                    && material.primitivesPerMaterialRecord()
                    == previousMaterial.primitivesPerMaterialRecord();
        }

        SectionMaterial sectionMaterial(int section) {
            if (section < 0 || section >= sectionCount) {
                throw new IllegalArgumentException("section index outside material snapshot: " + section);
            }
            if (sectionRecords == null) {
                return sectionMaterials.get(section);
            }

            int sectionOffset = section * INTS_PER_SECTION_RECORD;
            int firstFace = sectionRecords[sectionOffset];
            int sectionFaceCount = sectionRecords[sectionOffset + 1];
            int firstInt = checkedIntMultiply(firstFace, INTS_PER_FACE_RECORD);
            int intCount = checkedIntMultiply(sectionFaceCount, INTS_PER_FACE_RECORD);
            int[] sectionFaceRecords = new int[intCount];
            System.arraycopy(faceRecords, firstInt, sectionFaceRecords, 0, intCount);
            return new SectionMaterial(
                    sectionFaceRecords,
                    sectionRecords[sectionOffset + 2],
                    sectionRecords[sectionOffset + 3]
            );
        }

        int firstFace(int section) {
            if (section < 0 || section >= sectionCount) {
                throw new IllegalArgumentException("section index outside material snapshot: " + section);
            }
            return sectionFirstFaces[section];
        }

        private static SnapshotSignature signatureFromFaceRecords(
                int[] sectionRecords,
                int[] faceRecords,
                int sectionCount,
                int faceCount,
                RtTextureCatalog.Snapshot textureSnapshot
        ) {
            int fallbackColorFaces = 0;
            int fluidFaces = 0;
            int emissiveFaces = 0;
            for (int offset = 0; offset < faceRecords.length; offset += INTS_PER_FACE_RECORD) {
                if ((faceRecords[offset + 3] & TEXTURE_INFO_TEXTURE_ID_MASK) == 0) {
                    fallbackColorFaces++;
                }
                if ((faceRecords[offset + 1] & 0xFF) > 0) {
                    fluidFaces++;
                }
                if (((faceRecords[offset + 1] >>> 16) & 0xFF) > 0) {
                    emissiveFaces++;
                }
            }
            return new SnapshotSignature(
                    sectionCount,
                    faceCount,
                    fallbackColorFaces,
                    fluidFaces,
                    emissiveFaces,
                    textureSnapshot.textureCount(),
                    textureSnapshot.texturePixelCount(),
                    textureSnapshot.revision(),
                    Arrays.hashCode(sectionRecords),
                    Arrays.hashCode(faceRecords)
            );
        }

        private static int implicitInstanceLayoutHash(List<SectionMaterial> sectionMaterials) {
            int result = 1;
            for (int index = 0; index < sectionMaterials.size(); index++) {
                SectionMaterial material = Objects.requireNonNull(sectionMaterials.get(index), "section material");
                result = 31 * result + index;
                result = 31 * result + material.faceCount();
                result = 31 * result + material.secondGeometryFaceOffset();
                result = 31 * result + material.primitivesPerMaterialRecord();
                result = 31 * result + material.faceRecordHash();
            }
            return result;
        }

        private static int sectionRecordHash(List<SectionMaterial> sectionMaterials, int[] sectionFirstFaces) {
            int result = 1;
            for (int index = 0; index < sectionMaterials.size(); index++) {
                SectionMaterial material = sectionMaterials.get(index);
                result = 31 * result + sectionFirstFaces[index];
                result = 31 * result + material.faceCount();
                result = 31 * result + material.secondGeometryFaceOffset();
                result = 31 * result + material.primitivesPerMaterialRecord();
            }
            return result;
        }

        private static int faceRecordHash(List<SectionMaterial> sectionMaterials, int[] sectionFirstFaces) {
            int result = 1;
            for (int index = 0; index < sectionMaterials.size(); index++) {
                SectionMaterial material = sectionMaterials.get(index);
                result = 31 * result + sectionFirstFaces[index];
                result = 31 * result + material.faceRecordHash();
            }
            return result;
        }

        private static int sectionRecordBlockHash(int firstFace, SectionMaterial material) {
            int result = firstFace;
            result = 31 * result + material.faceCount();
            result = 31 * result + material.secondGeometryFaceOffset();
            return 31 * result + material.primitivesPerMaterialRecord();
        }

        private static int faceRecordBlockHash(int firstFace, SectionMaterial material) {
            return 31 * firstFace + material.faceRecordHash();
        }

        private static int powerOf31(int exponent) {
            if (exponent < 0) {
                throw new IllegalArgumentException("power exponent must not be negative");
            }
            int result = 1;
            int factor = 31;
            int remaining = exponent;
            while (remaining > 0) {
                if ((remaining & 1) != 0) {
                    result *= factor;
                }
                factor *= factor;
                remaining >>>= 1;
            }
            return result;
        }

        private static int[] sequentialFirstFaces(List<SectionMaterial> sectionMaterials) {
            int[] firstFaces = new int[sectionMaterials.size()];
            int faceCursor = 0;
            for (int index = 0; index < sectionMaterials.size(); index++) {
                firstFaces[index] = faceCursor;
                faceCursor = checkedIntAdd(faceCursor, sectionMaterials.get(index).faceCount());
            }
            return firstFaces;
        }

        boolean isIncrementalSuccessorOf(Snapshot previousSnapshot) {
            return incrementalBaseIdentity == previousSnapshot.snapshotIdentity;
        }

        int[] dirtySectionSlotsUnsafe() {
            return dirtySectionSlots;
        }

        int[] dirtyFaceRunsUnsafe() {
            return dirtyFaceRuns;
        }

        private static int faceCapacity(List<SectionMaterial> sectionMaterials, int[] sectionFirstFaces) {
            if (sectionMaterials.size() != sectionFirstFaces.length) {
                throw new IllegalArgumentException("section face layout must match material count");
            }
            int capacity = 0;
            for (int index = 0; index < sectionMaterials.size(); index++) {
                int firstFace = sectionFirstFaces[index];
                if (firstFace < 0) {
                    throw new IllegalArgumentException("section first face must not be negative");
                }
                capacity = Math.max(capacity, checkedIntAdd(firstFace, sectionMaterials.get(index).faceCount()));
            }
            return capacity;
        }

        private static int[] sectionFirstFacesFromRecords(int[] sectionRecords) {
            int sectionCount = sectionRecords.length / INTS_PER_SECTION_RECORD;
            int[] firstFaces = new int[sectionCount];
            for (int section = 0; section < sectionCount; section++) {
                firstFaces[section] = sectionRecords[section * INTS_PER_SECTION_RECORD];
            }
            return firstFaces;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Snapshot that
                    && Arrays.equals(sectionRecords(), that.sectionRecords())
                    && Arrays.equals(faceRecords(), that.faceRecords())
                    && textureSnapshot.equals(that.textureSnapshot);
        }

        @Override
        public int hashCode() {
            int result = Arrays.hashCode(sectionRecords());
            result = 31 * result + Arrays.hashCode(faceRecords());
            result = 31 * result + textureSnapshot.hashCode();
            return result;
        }
    }

    public record SnapshotSignature(
            int sectionCount,
            int faceCount,
            int fallbackColorFaceCount,
            int fluidFaceCount,
            int emissiveFaceCount,
            int textureRecords,
            int texturePixels,
            long textureRevision,
            int sectionRecordHash,
            int faceRecordHash
    ) {
        public SnapshotSignature {
            if (sectionCount < 0 || faceCount < 0 || fallbackColorFaceCount < 0 || fluidFaceCount < 0
                    || emissiveFaceCount < 0 || textureRecords < 0 || texturePixels < 0) {
                throw new IllegalArgumentException("snapshot signature counts must not be negative");
            }
            if (fallbackColorFaceCount > faceCount || fluidFaceCount > faceCount || emissiveFaceCount > faceCount) {
                throw new IllegalArgumentException("snapshot signature face counts must not exceed total faces");
            }
            if (textureRevision < 0L) {
                throw new IllegalArgumentException("textureRevision must not be negative");
            }
        }

        static SnapshotSignature empty() {
            return new SnapshotSignature(0, 0, 0, 0, 0, 0, 0, 0L, 1, 1);
        }
    }

    public record MaterialUploadDiff(
            long dirtySectionRecords,
            long dirtyFaceRecords,
            long dirtyTextureRecords,
            long dirtyTexturePixels,
            long dirtySectionBytes,
            long dirtyFaceBytes,
            long dirtyTextureRecordBytes,
            long dirtyTexturePixelBytes,
            long stagedBytes,
            boolean fullUpload
    ) {
        public MaterialUploadDiff {
            if (dirtySectionRecords < 0L
                    || dirtyFaceRecords < 0L
                    || dirtyTextureRecords < 0L
                    || dirtyTexturePixels < 0L
                    || dirtySectionBytes < 0L
                    || dirtyFaceBytes < 0L
                    || dirtyTextureRecordBytes < 0L
                    || dirtyTexturePixelBytes < 0L
                    || stagedBytes < 0L) {
                throw new IllegalArgumentException("material upload diff counts must not be negative");
            }
        }
    }

    public record MaterialBufferUploadPlan(
            boolean inPlace,
            boolean materialBuffersChanged,
            boolean fullUpload,
            long copiedPreviousBytes,
            long requiredSectionBytes,
            long requiredFaceBytes,
            long requiredTextureRecordBytes,
            long requiredTexturePixelBytes
    ) {
        public MaterialBufferUploadPlan {
            if (copiedPreviousBytes < 0L
                    || requiredSectionBytes < 0L
                    || requiredFaceBytes < 0L
                    || requiredTextureRecordBytes < 0L
                    || requiredTexturePixelBytes < 0L) {
                throw new IllegalArgumentException("material upload plan byte counts must not be negative");
            }
            if (inPlace == materialBuffersChanged) {
                throw new IllegalArgumentException("in-place material upload must not change descriptor buffers");
            }
        }
    }

    private static int[] copyRecords(int[] source, String name, int intsPerRecord) {
        validateRecords(source, name, intsPerRecord);
        return Arrays.copyOf(source, source.length);
    }

    private static int[] validateRecords(int[] source, String name, int intsPerRecord) {
        Objects.requireNonNull(source, name);
        if (intsPerRecord <= 0) {
            throw new IllegalArgumentException("intsPerRecord must be positive");
        }
        if (source.length % intsPerRecord != 0) {
            throw new IllegalArgumentException(name + " length must be divisible by " + intsPerRecord);
        }
        return source;
    }

    private static void validateSectionRanges(int[] sectionRecords, int faceCount) {
        for (int offset = 0; offset < sectionRecords.length; offset += INTS_PER_SECTION_RECORD) {
            int faceOffset = sectionRecords[offset];
            int sectionFaceCount = sectionRecords[offset + 1];
            int secondGeometryFaceOffset = sectionRecords[offset + 2];
            int primitivesPerMaterialRecord = sectionRecords[offset + 3];
            if (faceOffset < 0 || sectionFaceCount <= 0) {
                throw new IllegalArgumentException("section material range must be non-empty");
            }
            if (secondGeometryFaceOffset < 0 || secondGeometryFaceOffset > sectionFaceCount) {
                throw new IllegalArgumentException("section second geometry offset exceeds face range");
            }
            validatePrimitivesPerMaterialRecord(primitivesPerMaterialRecord);
            if (faceOffset > faceCount || faceOffset + sectionFaceCount > faceCount) {
                throw new IllegalArgumentException("section material range exceeds face table");
            }
        }
    }

    private static void validatePrimitivesPerMaterialRecord(int primitivesPerMaterialRecord) {
        if (primitivesPerMaterialRecord != 1 && primitivesPerMaterialRecord != 2) {
            throw new IllegalArgumentException("primitivesPerMaterialRecord must be 1 or 2");
        }
    }

    static int checkedIntAdd(int left, int right) {
        int result = left + right;
        if (((left ^ result) & (right ^ result)) < 0) {
            throw new IllegalArgumentException("size overflow");
        }
        return result;
    }

    static int checkedIntMultiply(int left, int right) {
        int result = left * right;
        if (left != 0 && result / left != right) {
            throw new IllegalArgumentException("size overflow");
        }
        return result;
    }
}
