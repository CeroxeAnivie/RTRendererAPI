package top.ceroxe.rt.renderer;

/** Material-lifecycle telemetry boundary used by RT material owners. */
public interface RtMaterialTelemetrySink {
    /** Shared sink that discards every event. */
    RtMaterialTelemetrySink NOOP = new RtMaterialTelemetrySink() {
    };

    /**
     * Returns whether high-volume dynamic material diagnostics are requested.
     * @return enablement state
     */
    default boolean dynamicMaterialDiagnosticsEnabled() {
        return false;
    }

    /**
     * Records an immutable material snapshot publication.
     * @param stage lifecycle stage
     * @param materialRevision material revision
     * @param sectionCount section-record count
     * @param faceCount face-record count
     * @param fallbackColorFaces fallback-color face count
     * @param fluidFaces fluid face count
     * @param emissiveFaces emissive face count
     * @param textureRecords texture-record count
     * @param texturePixels texture-pixel count
     * @param textureRevision texture revision
     * @param instanceLayoutHash instance-layout hash
     * @param sectionRecordHash section-record hash
     * @param faceRecordHash face-record hash
     */
    default void materialSnapshotChanged(String stage, long materialRevision, int sectionCount, int faceCount,
            int fallbackColorFaces, int fluidFaces, int emissiveFaces, int textureRecords, int texturePixels,
            long textureRevision, int instanceLayoutHash, int sectionRecordHash, int faceRecordHash) {
    }

    /**
     * Records completed dynamic mesh construction statistics.
     * @param meshKind mesh category
     * @param assetId stable asset identifier
     * @param revision asset revision
     * @param primitiveCount primitive count
     * @param vertexCount vertex count
     * @param triangleCount triangle count
     * @param materialFaces material-face count
     * @param estimatedBytes estimated retained bytes
     */
    default void dynamicMeshBuilt(String meshKind, long assetId, long revision, int primitiveCount,
            int vertexCount, int triangleCount, int materialFaces, long estimatedBytes) {
    }

    /**
     * Records packed dynamic-mesh material statistics.
     * @param meshKind mesh category
     * @param assetId stable asset identifier
     * @param revision asset revision
     * @param primitiveId primitive identifier
     * @param primitiveCount primitive count
     * @param faceCount face count
     * @param texturedFaces textured face count
     * @param tintedFaces tinted face count
     * @param alphaCutoutFaces alpha-cutout face count
     * @param translucentFaces translucent face count
     * @param emissiveFaces emissive face count
     * @param foilFaces foil face count
     * @param minimumTextureId minimum referenced texture identifier
     * @param maximumTextureId maximum referenced texture identifier
     * @param textureSignature texture signature
     * @param materialSignature material signature
     */
    default void dynamicMeshMaterialPacked(String meshKind, long assetId, long revision, long primitiveId,
            int primitiveCount, int faceCount, int texturedFaces, int tintedFaces, int alphaCutoutFaces,
            int translucentFaces, int emissiveFaces, int foilFaces, int minimumTextureId, int maximumTextureId,
            int textureSignature, int materialSignature) {
    }

    /**
     * Records a material-only binding decision.
     * @param decision stable decision token
     * @param boundMaterialRevision bound material revision
     * @param candidateMaterialRevision candidate material revision
     * @param boundTextureRevision bound texture revision
     * @param candidateTextureRevision candidate texture revision
     */
    default void materialOnlyDecision(String decision, long boundMaterialRevision, long candidateMaterialRevision,
            long boundTextureRevision, long candidateTextureRevision) {
    }

    /**
     * Records a deferred material binding.
     * @param bindKind binding category
     * @param reason deferral reason
     * @param materialRevision material revision
     * @param textureRevision texture revision
     */
    default void materialBindDeferred(String bindKind, String reason, long materialRevision, long textureRevision) {
    }

    /**
     * Records a binding waiting for its material upload.
     * @param bindKind binding category
     * @param materialRevision material revision
     * @param textureRevision texture revision
     */
    default void materialUploadAwaiting(String bindKind, long materialRevision, long textureRevision) {
    }

    /**
     * Records a material lifecycle failure.
     * @param stage failure stage
     * @param materialRevision material revision
     * @param textureRevision texture revision
     * @param failureType failure type
     */
    default void materialFailure(String stage, long materialRevision, long textureRevision, String failureType) {
    }

    /**
     * Records an upload elided because an equivalent generation already exists.
     * @param materialRevision material revision
     * @param textureRevision texture revision
     * @param sectionCount section count
     * @param faceCount face count
     */
    default void materialUploadDeduplicated(long materialRevision, long textureRevision, int sectionCount,
            int faceCount) {
    }

    /**
     * Records material upload preparation and submission statistics.
     * @param materialRevision material revision
     * @param textureRevision texture revision
     * @param sectionCount section count
     * @param faceCount face count
     * @param textureRecords texture-record count
     * @param texturePixels texture-pixel count
     * @param materialBuffersChanged whether material buffers changed
     * @param fullUpload whether this is a full upload
     * @param copiedPreviousBuffers whether previous buffers were copied
     * @param allocatedBufferCount allocated buffer count
     * @param dirtySectionRecords dirty section-record count
     * @param dirtyFaceRecords dirty face-record count
     * @param dirtyTextureRecords dirty texture-record count
     * @param dirtyTexturePixels dirty texture-pixel count
     * @param stagedBytes staged byte count
     * @param requiredSectionBytes required section bytes
     * @param requiredFaceBytes required face bytes
     * @param requiredTextureRecordBytes required texture-record bytes
     * @param requiredTexturePixelBytes required texture-pixel bytes
     * @param cpuPrepareMillis CPU preparation milliseconds
     * @param recordAndSubmitMillis recording and submission milliseconds
     */
    default void materialUploadSubmitted(long materialRevision, long textureRevision, int sectionCount,
            int faceCount, int textureRecords, int texturePixels, boolean materialBuffersChanged,
            boolean fullUpload, boolean copiedPreviousBuffers, int allocatedBufferCount, long dirtySectionRecords,
            long dirtyFaceRecords, long dirtyTextureRecords, long dirtyTexturePixels, long stagedBytes,
            long requiredSectionBytes, long requiredFaceBytes, long requiredTextureRecordBytes,
            long requiredTexturePixelBytes, double cpuPrepareMillis, double recordAndSubmitMillis) {
    }

    /**
     * Records completion and queue/fence timing for a material upload.
     * @param materialRevision material revision
     * @param textureRevision texture revision
     * @param elapsedMillis total elapsed milliseconds
     * @param materialBuffersChanged whether material buffers changed
     * @param fullUpload whether this was a full upload
     * @param stagedBytes staged bytes
     * @param cpuPrepareMillis CPU preparation milliseconds
     * @param recordAndSubmitMillis recording and submission milliseconds
     * @param queueLockWaitMillis queue-lock wait milliseconds
     * @param vkQueueSubmitMillis Vulkan queue submission milliseconds
     * @param fenceResidencyUpperBoundMillis fence-residency upper bound in milliseconds
     * @param lastNotReadyToObservationMillis last-not-ready observation interval
     * @param notReadyPolls not-ready poll count
     */
    default void materialUploadCompleted(long materialRevision, long textureRevision, long elapsedMillis,
            boolean materialBuffersChanged, boolean fullUpload, long stagedBytes, double cpuPrepareMillis,
            double recordAndSubmitMillis, double queueLockWaitMillis, double vkQueueSubmitMillis,
            double fenceResidencyUpperBoundMillis, double lastNotReadyToObservationMillis, long notReadyPolls) {
    }

    /** Records activation of a completed upload.
     * @param materialRevision material revision
     * @param textureRevision texture revision
     * @param materialBuffersChanged whether buffers changed
     * @param fullUpload whether this was full
     * @param stagedBytes staged bytes */
    default void materialUploadActivated(long materialRevision, long textureRevision,
            boolean materialBuffersChanged, boolean fullUpload, long stagedBytes) {
    }

    /** Records commitment of an activated upload.
     * @param materialRevision material revision
     * @param textureRevision texture revision
     * @param retiredDescriptorGeneration retired descriptor generation
     * @param materialBuffersChanged whether buffers changed
     * @param fullUpload whether this was full
     * @param stagedBytes staged bytes */
    default void materialUploadCommitted(long materialRevision, long textureRevision,
            long retiredDescriptorGeneration, boolean materialBuffersChanged, boolean fullUpload, long stagedBytes) {
    }

    /** Records rollback of an activated upload.
     * @param materialRevision material revision
     * @param textureRevision texture revision
     * @param materialBuffersChanged whether buffers changed
     * @param reason rollback reason */
    default void materialUploadRolledBack(long materialRevision, long textureRevision,
            boolean materialBuffersChanged, String reason) {
    }

    /** Records retirement of material buffers.
     * @param descriptorGeneration retired descriptor generation
     * @param pendingRetirements pending retirement count */
    default void materialBuffersRetired(long descriptorGeneration, int pendingRetirements) {
    }

    /** Records release and reuse of retired batches.
     * @param completedDescriptorGeneration completed generation
     * @param releasedBatches released batch count
     * @param reusableBatches reusable batch count
     * @param pendingRetirements pending count */
    default void materialBuffersReleased(long completedDescriptorGeneration, int releasedBatches,
            int reusableBatches, int pendingRetirements) {
    }

    /** Records overflow buffer closure.
     * @param sectionBytes section bytes
     * @param faceBytes face bytes
     * @param textureRecordBytes texture-record bytes
     * @param texturePixelBytes texture-pixel bytes */
    default void materialBufferOverflowClosed(long sectionBytes, long faceBytes, long textureRecordBytes,
            long texturePixelBytes) {
    }

    /** Records buffer reuse/allocation admission inputs and outcome.
     * @param allowInPlaceUpdate whether in-place update is allowed
     * @param currentBuffersPresent whether current buffers exist
     * @param requiredSectionBytes required section bytes
     * @param requiredFaceBytes required face bytes
     * @param requiredTextureRecordBytes required texture-record bytes
     * @param requiredTexturePixelBytes required texture-pixel bytes
     * @param currentSectionBytes current section bytes
     * @param currentFaceBytes current face bytes
     * @param currentTextureRecordBytes current texture-record bytes
     * @param currentTexturePixelBytes current texture-pixel bytes
     * @param reusablePoolSize reusable pool size
     * @param reusedBatch whether a batch was reused
     * @param allocatedBatch whether a batch was allocated */
    default void materialBufferDecision(boolean allowInPlaceUpdate, boolean currentBuffersPresent,
            long requiredSectionBytes, long requiredFaceBytes, long requiredTextureRecordBytes,
            long requiredTexturePixelBytes, long currentSectionBytes, long currentFaceBytes,
            long currentTextureRecordBytes, long currentTexturePixelBytes, int reusablePoolSize,
            boolean reusedBatch, boolean allocatedBatch) {
    }

    /** Records a descriptor-generation binding transition.
     * @param bindKind binding category
     * @param previousGeneration previous generation
     * @param nextGeneration next generation
     * @param topLevelAccelerationStructure TLAS handle
     * @param materialBuffersChanged whether buffers changed
     * @param sectionBufferBytes section-buffer bytes
     * @param faceBufferBytes face-buffer bytes
     * @param textureRecordBufferBytes texture-record bytes
     * @param texturePixelBufferBytes texture-pixel bytes */
    default void descriptorGenerationBound(String bindKind, long previousGeneration, long nextGeneration,
            long topLevelAccelerationStructure, boolean materialBuffersChanged, long sectionBufferBytes,
            long faceBufferBytes, long textureRecordBufferBytes, long texturePixelBufferBytes) {
    }

    /** Records native handles and byte ranges written to one descriptor set.
     * @param descriptorGeneration descriptor generation
     * @param descriptorIndex descriptor index
     * @param descriptorSet descriptor-set handle
     * @param topLevelAccelerationStructure world TLAS handle
     * @param dynamicTopLevelAccelerationStructure dynamic TLAS handle
     * @param sectionBuffer section-buffer handle
     * @param sectionBufferBytes section-buffer bytes
     * @param faceBuffer face-buffer handle
     * @param faceBufferBytes face-buffer bytes
     * @param textureRecordBuffer texture-record buffer handle
     * @param textureRecordBufferBytes texture-record buffer bytes
     * @param texturePixelBuffer texture-pixel buffer handle
     * @param texturePixelBufferBytes texture-pixel buffer bytes */
    default void descriptorSetWritten(long descriptorGeneration, int descriptorIndex, long descriptorSet,
            long topLevelAccelerationStructure, long dynamicTopLevelAccelerationStructure, long sectionBuffer,
            long sectionBufferBytes, long faceBuffer, long faceBufferBytes, long textureRecordBuffer,
            long textureRecordBufferBytes, long texturePixelBuffer, long texturePixelBufferBytes) {
    }

    /** Records registration of a texture catalog entry.
     * @param textureId texture identifier
     * @param catalogRevision catalog revision
     * @param catalogTextureCount catalog texture count
     * @param catalogName canonical texture name
     * @param width width in pixels
     * @param height height in pixels
     * @param pixelCount pixel count
     * @param transparent whether the texture has transparency
     * @param animationFrameCount animation frame count */
    default void textureRegistered(int textureId, long catalogRevision, int catalogTextureCount,
            String catalogName, int width, int height, int pixelCount, boolean transparent,
            int animationFrameCount) {
    }

    /** Records immutable texture-catalog snapshot size.
     * @param catalogRevision catalog revision
     * @param textureCount texture count
     * @param texturePixels texture pixel count
     * @param estimatedBytes estimated byte size */
    default void textureCatalogSnapshot(long catalogRevision, int textureCount, int texturePixels,
            int estimatedBytes) {
    }

    /** Records one texture-animation advancement pass.
     * @param animationTicks animation ticks
     * @param catalogRevision catalog revision
     * @param animatedTextures animated texture count
     * @param changedTextures changed texture count
     * @param changedPixels changed pixel count */
    default void textureAnimationAdvanced(long animationTicks, long catalogRevision, int animatedTextures,
            int changedTextures, int changedPixels) {
    }

    /** Records a stable material-slot update and face-range decision.
     * @param slot stable slot
     * @param newSlot whether newly allocated
     * @param changed whether content changed
     * @param rangeDecision range decision
     * @param requiredFaces required faces
     * @param previousFirstFace previous first face
     * @param previousCapacity previous capacity
     * @param currentFirstFace current first face
     * @param currentCapacity current capacity
     * @param activeSlots active slots
     * @param slotCount physical slots
     * @param freeSlots free slots
     * @param faceCapacity face capacity
     * @param freeRanges free ranges */
    default void materialSlotUpdated(int slot, boolean newSlot, boolean changed, String rangeDecision,
            int requiredFaces, int previousFirstFace, int previousCapacity, int currentFirstFace,
            int currentCapacity, int activeSlots, int slotCount, int freeSlots, int faceCapacity,
            int freeRanges) {
    }

    /** Records allocation of a stable material slot.
     * @param slot stable slot
     * @param reusedSlot whether reused
     * @param restoredRetiredCapacity whether retired capacity was restored
     * @param requiredFaces required faces
     * @param firstFace first face
     * @param capacity range capacity
     * @param activeSlots active slots
     * @param slotCount physical slots
     * @param freeSlots free slots
     * @param faceCapacity face capacity
     * @param freeRanges free ranges */
    default void materialSlotAllocated(int slot, boolean reusedSlot, boolean restoredRetiredCapacity,
            int requiredFaces, int firstFace, int capacity, int activeSlots, int slotCount, int freeSlots,
            int faceCapacity, int freeRanges) {
    }

    /** Records release of a stable material slot.
     * @param slot released slot, or {@code null}
     * @param firstFace first face
     * @param capacity range capacity
     * @param activeSlots active slots
     * @param slotCount physical slots
     * @param freeSlots free slots
     * @param faceCapacity face capacity
     * @param freeRanges free ranges */
    default void materialSlotReleased(Integer slot, int firstFace, int capacity, int activeSlots,
            int slotCount, int freeSlots, int faceCapacity, int freeRanges) {
    }

    /** Records clearing all stable material slots.
     * @param activeSlots active slots before clearing
     * @param slotCount physical slots before clearing
     * @param faceCapacity face capacity before clearing
     * @param freeRanges free ranges before clearing */
    default void materialSlotsCleared(int activeSlots, int slotCount, int faceCapacity, int freeRanges) {
    }

    /** Records consumption of dirty-slot state by snapshot publication.
     * @param dirtySlots consumed dirty slots
     * @param activeSlots active slots
     * @param slotCount physical slots */
    default void materialDirtySlotsConsumed(int dirtySlots, int activeSlots, int slotCount) {
    }

    /** Records trimming unused trailing slots and face capacity.
     * @param trimmedSlots trimmed slots
     * @param retainedPreferredCapacity retained preferred capacity
     * @param activeSlots active slots
     * @param slotCount physical slots
     * @param faceCapacity face capacity
     * @param freeRanges free ranges */
    default void materialTrailingSlotsTrimmed(int trimmedSlots, int retainedPreferredCapacity,
            int activeSlots, int slotCount, int faceCapacity, int freeRanges) {
    }
}
