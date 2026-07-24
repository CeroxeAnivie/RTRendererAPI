package top.ceroxe.mcvulkanrt.renderer;

/** Material-lifecycle telemetry boundary used by RT material owners. */
public interface RtMaterialTelemetrySink {
    RtMaterialTelemetrySink NOOP = new RtMaterialTelemetrySink() {
    };

    default boolean dynamicMaterialDiagnosticsEnabled() {
        return false;
    }

    default void materialSnapshotChanged(String stage, long materialRevision, int sectionCount, int faceCount,
            int fallbackColorFaces, int fluidFaces, int emissiveFaces, int textureRecords, int texturePixels,
            long textureRevision, int instanceLayoutHash, int sectionRecordHash, int faceRecordHash) {
    }

    default void dynamicMeshBuilt(String meshKind, long assetId, long revision, int primitiveCount,
            int vertexCount, int triangleCount, int materialFaces, long estimatedBytes) {
    }

    default void dynamicMeshMaterialPacked(String meshKind, long assetId, long revision, long primitiveId,
            int primitiveCount, int faceCount, int texturedFaces, int tintedFaces, int alphaCutoutFaces,
            int translucentFaces, int emissiveFaces, int foilFaces, int minimumTextureId, int maximumTextureId,
            int textureSignature, int materialSignature) {
    }

    default void materialOnlyDecision(String decision, long boundMaterialRevision, long candidateMaterialRevision,
            long boundTextureRevision, long candidateTextureRevision) {
    }

    default void materialBindDeferred(String bindKind, String reason, long materialRevision, long textureRevision) {
    }

    default void materialUploadAwaiting(String bindKind, long materialRevision, long textureRevision) {
    }

    default void materialFailure(String stage, long materialRevision, long textureRevision, String failureType) {
    }

    default void materialUploadDeduplicated(long materialRevision, long textureRevision, int sectionCount,
            int faceCount) {
    }

    default void materialUploadSubmitted(long materialRevision, long textureRevision, int sectionCount,
            int faceCount, int textureRecords, int texturePixels, boolean materialBuffersChanged,
            boolean fullUpload, boolean copiedPreviousBuffers, int allocatedBufferCount, long dirtySectionRecords,
            long dirtyFaceRecords, long dirtyTextureRecords, long dirtyTexturePixels, long stagedBytes,
            long requiredSectionBytes, long requiredFaceBytes, long requiredTextureRecordBytes,
            long requiredTexturePixelBytes, double cpuPrepareMillis, double recordAndSubmitMillis) {
    }

    default void materialUploadCompleted(long materialRevision, long textureRevision, long elapsedMillis,
            boolean materialBuffersChanged, boolean fullUpload, long stagedBytes, double cpuPrepareMillis,
            double recordAndSubmitMillis, double queueLockWaitMillis, double vkQueueSubmitMillis,
            double fenceResidencyUpperBoundMillis, double lastNotReadyToObservationMillis, long notReadyPolls) {
    }

    default void materialUploadActivated(long materialRevision, long textureRevision,
            boolean materialBuffersChanged, boolean fullUpload, long stagedBytes) {
    }

    default void materialUploadCommitted(long materialRevision, long textureRevision,
            long retiredDescriptorGeneration, boolean materialBuffersChanged, boolean fullUpload, long stagedBytes) {
    }

    default void materialUploadRolledBack(long materialRevision, long textureRevision,
            boolean materialBuffersChanged, String reason) {
    }

    default void materialBuffersRetired(long descriptorGeneration, int pendingRetirements) {
    }

    default void materialBuffersReleased(long completedDescriptorGeneration, int releasedBatches,
            int reusableBatches, int pendingRetirements) {
    }

    default void materialBufferOverflowClosed(long sectionBytes, long faceBytes, long textureRecordBytes,
            long texturePixelBytes) {
    }

    default void materialBufferDecision(boolean allowInPlaceUpdate, boolean currentBuffersPresent,
            long requiredSectionBytes, long requiredFaceBytes, long requiredTextureRecordBytes,
            long requiredTexturePixelBytes, long currentSectionBytes, long currentFaceBytes,
            long currentTextureRecordBytes, long currentTexturePixelBytes, int reusablePoolSize,
            boolean reusedBatch, boolean allocatedBatch) {
    }

    default void descriptorGenerationBound(String bindKind, long previousGeneration, long nextGeneration,
            long topLevelAccelerationStructure, boolean materialBuffersChanged, long sectionBufferBytes,
            long faceBufferBytes, long textureRecordBufferBytes, long texturePixelBufferBytes) {
    }

    default void descriptorSetWritten(long descriptorGeneration, int descriptorIndex, long descriptorSet,
            long topLevelAccelerationStructure, long dynamicTopLevelAccelerationStructure, long sectionBuffer,
            long sectionBufferBytes, long faceBuffer, long faceBufferBytes, long textureRecordBuffer,
            long textureRecordBufferBytes, long texturePixelBuffer, long texturePixelBufferBytes) {
    }

    default void textureRegistered(int textureId, long catalogRevision, int catalogTextureCount,
            String catalogName, int width, int height, int pixelCount, boolean transparent,
            int animationFrameCount) {
    }

    default void textureCatalogSnapshot(long catalogRevision, int textureCount, int texturePixels,
            int estimatedBytes) {
    }

    default void textureAnimationAdvanced(long animationTicks, long catalogRevision, int animatedTextures,
            int changedTextures, int changedPixels) {
    }

    default void materialSlotUpdated(int slot, boolean newSlot, boolean changed, String rangeDecision,
            int requiredFaces, int previousFirstFace, int previousCapacity, int currentFirstFace,
            int currentCapacity, int activeSlots, int slotCount, int freeSlots, int faceCapacity,
            int freeRanges) {
    }

    default void materialSlotAllocated(int slot, boolean reusedSlot, boolean restoredRetiredCapacity,
            int requiredFaces, int firstFace, int capacity, int activeSlots, int slotCount, int freeSlots,
            int faceCapacity, int freeRanges) {
    }

    default void materialSlotReleased(Integer slot, int firstFace, int capacity, int activeSlots,
            int slotCount, int freeSlots, int faceCapacity, int freeRanges) {
    }

    default void materialSlotsCleared(int activeSlots, int slotCount, int faceCapacity, int freeRanges) {
    }

    default void materialDirtySlotsConsumed(int dirtySlots, int activeSlots, int slotCount) {
    }

    default void materialTrailingSlotsTrimmed(int trimmedSlots, int retainedPreferredCapacity,
            int activeSlots, int slotCount, int faceCapacity, int freeRanges) {
    }
}
