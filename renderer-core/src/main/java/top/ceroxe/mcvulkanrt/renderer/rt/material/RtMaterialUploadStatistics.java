package top.ceroxe.mcvulkanrt.renderer.rt.material;

import java.util.Objects;

/** Single mutable owner for material upload counters, peaks, and async latency evidence. */
final class RtMaterialUploadStatistics {
    private long uploads;
    private long asyncSubmissions;
    private long asyncCompletions;
    private long asyncPollsNotReady;
    private long asyncCloseWaits;
    private long reallocations;
    private long fullUploads;
    private long incrementalUploads;
    private long inPlaceUploads;
    private long copiedPreviousBufferBatches;
    private long dirtySectionRecords;
    private long dirtyFaceRecords;
    private long dirtyTextureRecords;
    private long dirtyTexturePixels;
    private long stagedBytes;
    private long uploadedSections;
    private long uploadedFaces;
    private long uploadedFallbackColorFaces;
    private long uploadedTextureRecords;
    private long uploadedTexturePixels;
    private long uploadedFluidFaces;
    private long uploadedEmissiveFaces;
    private long peakSections;
    private long peakFaces;
    private long peakFallbackColorFaces;
    private long peakTextureRecords;
    private long peakTexturePixels;
    private long lastAsyncLatencyMillis;
    private long maxAsyncLatencyMillis;
    private long totalAsyncLatencyMillis;

    void submitted() {
        asyncSubmissions = saturatingIncrement(asyncSubmissions);
    }

    void polledNotReady() {
        asyncPollsNotReady = saturatingIncrement(asyncPollsNotReady);
    }

    void closeWaited() {
        asyncCloseWaits = saturatingIncrement(asyncCloseWaits);
    }

    void completed(long elapsedMillis) {
        if (elapsedMillis < 0L) {
            throw new IllegalArgumentException("elapsedMillis must not be negative");
        }
        asyncCompletions = saturatingIncrement(asyncCompletions);
        lastAsyncLatencyMillis = elapsedMillis;
        maxAsyncLatencyMillis = Math.max(maxAsyncLatencyMillis, elapsedMillis);
        totalAsyncLatencyMillis = saturatingAdd(totalAsyncLatencyMillis, elapsedMillis);
    }

    void committed(
            RtSceneMaterialTable.SnapshotSignature signature,
            int allocatedBufferCount,
            boolean materialBuffersChanged,
            boolean fullUpload,
            long dirtySectionRecordCount,
            long dirtyFaceRecordCount,
            long dirtyTextureRecordCount,
            long dirtyTexturePixelCount,
            long stagedByteCount,
            boolean copiedPreviousBuffers
    ) {
        Objects.requireNonNull(signature, "signature");
        if (allocatedBufferCount < 0
                || dirtySectionRecordCount < 0L
                || dirtyFaceRecordCount < 0L
                || dirtyTextureRecordCount < 0L
                || dirtyTexturePixelCount < 0L
                || stagedByteCount < 0L) {
            throw new IllegalArgumentException("material upload counters must not be negative");
        }
        uploads = saturatingIncrement(uploads);
        reallocations = saturatingAdd(reallocations, allocatedBufferCount);
        if (fullUpload) {
            fullUploads = saturatingIncrement(fullUploads);
        } else {
            incrementalUploads = saturatingIncrement(incrementalUploads);
        }
        if (!materialBuffersChanged) {
            inPlaceUploads = saturatingIncrement(inPlaceUploads);
        }
        if (copiedPreviousBuffers) {
            copiedPreviousBufferBatches = saturatingIncrement(copiedPreviousBufferBatches);
        }
        dirtySectionRecords = saturatingAdd(dirtySectionRecords, dirtySectionRecordCount);
        dirtyFaceRecords = saturatingAdd(dirtyFaceRecords, dirtyFaceRecordCount);
        dirtyTextureRecords = saturatingAdd(dirtyTextureRecords, dirtyTextureRecordCount);
        dirtyTexturePixels = saturatingAdd(dirtyTexturePixels, dirtyTexturePixelCount);
        stagedBytes = saturatingAdd(stagedBytes, stagedByteCount);
        uploadedSections = signature.sectionCount();
        uploadedFaces = signature.faceCount();
        uploadedFallbackColorFaces = signature.fallbackColorFaceCount();
        uploadedTextureRecords = signature.textureRecords();
        uploadedTexturePixels = signature.texturePixels();
        uploadedFluidFaces = signature.fluidFaceCount();
        uploadedEmissiveFaces = signature.emissiveFaceCount();
        peakSections = Math.max(peakSections, uploadedSections);
        peakFaces = Math.max(peakFaces, uploadedFaces);
        peakFallbackColorFaces = Math.max(peakFallbackColorFaces, uploadedFallbackColorFaces);
        peakTextureRecords = Math.max(peakTextureRecords, uploadedTextureRecords);
        peakTexturePixels = Math.max(peakTexturePixels, uploadedTexturePixels);
    }

    String summary(
            String name,
            String bufferPoolSummary,
            long sectionBufferBytes,
            long faceBufferBytes,
            long textureRecordBufferBytes,
            long texturePixelBufferBytes
    ) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(bufferPoolSummary, "bufferPoolSummary");
        if (sectionBufferBytes < 0L || faceBufferBytes < 0L
                || textureRecordBufferBytes < 0L || texturePixelBufferBytes < 0L) {
            throw new IllegalArgumentException("material buffer byte counts must not be negative");
        }
        return name
                + "{sections=" + uploadedSections
                + ", faces=" + uploadedFaces
                + ", uploads=" + uploads
                + ", asyncUploadSubmissions=" + asyncSubmissions
                + ", asyncUploadCompletions=" + asyncCompletions
                + ", asyncUploadPollsNotReady=" + asyncPollsNotReady
                + ", asyncUploadCloseWaits=" + asyncCloseWaits
                + ", reallocations=" + reallocations
                + ", fullMaterialUploads=" + fullUploads
                + ", incrementalMaterialUploads=" + incrementalUploads
                + ", inPlaceMaterialUploads=" + inPlaceUploads
                + ", copiedPreviousMaterialBufferBatches=" + copiedPreviousBufferBatches
                + ", dirtySectionRecordUploads=" + dirtySectionRecords
                + ", dirtyFaceRecordUploads=" + dirtyFaceRecords
                + ", dirtyTextureRecordUploads=" + dirtyTextureRecords
                + ", dirtyTexturePixelUploads=" + dirtyTexturePixels
                + ", stagedMaterialUploadBytes=" + stagedBytes
                + ", peakSections=" + peakSections
                + ", peakFaces=" + peakFaces
                + ", fallbackColorFaces=" + uploadedFallbackColorFaces
                + ", textureRecords=" + uploadedTextureRecords
                + ", texturePixels=" + uploadedTexturePixels
                + ", fluidFaces=" + uploadedFluidFaces
                + ", emissiveFaces=" + uploadedEmissiveFaces
                + ", " + bufferPoolSummary
                + ", peakFallbackColorFaces=" + peakFallbackColorFaces
                + ", peakTextureRecords=" + peakTextureRecords
                + ", peakTexturePixels=" + peakTexturePixels
                + ", lastAsyncUploadLatencyMillis=" + lastAsyncLatencyMillis
                + ", maxAsyncUploadLatencyMillis=" + maxAsyncLatencyMillis
                + ", totalAsyncUploadLatencyMillis=" + totalAsyncLatencyMillis
                + ", sectionBufferBytes=" + sectionBufferBytes
                + ", faceBufferBytes=" + faceBufferBytes
                + ", textureRecordBufferBytes=" + textureRecordBufferBytes
                + ", texturePixelBufferBytes=" + texturePixelBufferBytes
                + "}";
    }

    /** Diagnostics saturate instead of turning a successfully committed GPU transaction into a failure. */
    private static long saturatingIncrement(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    private static long saturatingAdd(long left, long right) {
        if (right < 0L) {
            throw new IllegalArgumentException("diagnostic increments must not be negative");
        }
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
