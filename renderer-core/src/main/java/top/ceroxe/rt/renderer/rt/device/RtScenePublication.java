package top.ceroxe.rt.renderer.rt.device;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import jdk.jfr.*;
import top.ceroxe.rt.renderer.*;
import top.ceroxe.rt.renderer.rt.acceleration.RtAccelerationStructure;
import top.ceroxe.rt.renderer.rt.acceleration.RtDynamicBlasCache;
import top.ceroxe.rt.renderer.rt.acceleration.RtDynamicTlasCache;
import top.ceroxe.rt.renderer.rt.acceleration.RtWorldTlasCache;
import top.ceroxe.rt.renderer.rt.material.RtSceneMaterialTable;
import top.ceroxe.rt.renderer.rt.runtime.RtCore;
import top.ceroxe.rt.renderer.scene.PackedSectionMembership;
import top.ceroxe.rt.renderer.scene.SectionKey;

import java.util.Objects;

/**
 * Immutable descriptor-visible RT scene generation.
 *
 * <p>The acceleration caches own native TLAS resources, the material table owns
 * GPU material buffers, and the pipeline owns descriptor generations. This
 * value owns none of those resources. It is the single publication authority
 * which proves that handles, coverage and revisions became visible together.
 * A candidate build or upload must never appear here before every owning
 * component has committed its side of the transaction.</p>
 */
final class RtScenePublication {
    private final long generation;
    private final long descriptorGeneration;
    private final RtAccelerationStructure worldTlas;
    private final long worldTlasRevision;
    private final RtAccelerationStructure dynamicTlas;
    private final long dynamicTlasRevision;
    private final PackedSectionMembership worldSectionKeys;
    private final SectionRevisionSnapshot worldSectionContentRevisions;
    private final SectionCausalitySnapshot worldSectionCausalities;
    private final RendererViewState worldViewState;
    private final RtSceneMaterialTable.Snapshot materialSnapshot;
    private final long sectionMaterialRevision;
    private final long dynamicMaterialRevision;
    private final DynamicRenderScene dynamicScene;
    private final int terrainMaterialCount;
    private final Reason reason;
    private final RendererFrameCausality causality;
    private final RtCausalitySink causalitySink;

    private RtScenePublication(
            long generation,
            long descriptorGeneration,
            RtAccelerationStructure worldTlas,
            long worldTlasRevision,
            RtAccelerationStructure dynamicTlas,
            long dynamicTlasRevision,
            PackedSectionMembership worldSectionKeys,
            SectionRevisionSnapshot worldSectionContentRevisions,
            SectionCausalitySnapshot worldSectionCausalities,
            RendererViewState worldViewState,
            RtSceneMaterialTable.Snapshot materialSnapshot,
            long sectionMaterialRevision,
            long dynamicMaterialRevision,
            DynamicRenderScene dynamicScene,
            int terrainMaterialCount,
            Reason reason,
            RendererFrameCausality causality,
            RtCausalitySink causalitySink
    ) {
        this(
                generation,
                descriptorGeneration,
                worldTlas,
                worldTlasRevision,
                dynamicTlas,
                dynamicTlasRevision,
                worldSectionKeys,
                worldSectionContentRevisions,
                worldSectionCausalities,
                worldViewState,
                materialSnapshot,
                sectionMaterialRevision,
                dynamicMaterialRevision,
                dynamicScene,
                terrainMaterialCount,
                reason,
                causality,
                causalitySink,
                true
        );
    }

    private RtScenePublication(
            long generation,
            long descriptorGeneration,
            RtAccelerationStructure worldTlas,
            long worldTlasRevision,
            RtAccelerationStructure dynamicTlas,
            long dynamicTlasRevision,
            PackedSectionMembership worldSectionKeys,
            SectionRevisionSnapshot worldSectionContentRevisions,
            SectionCausalitySnapshot worldSectionCausalities,
            RendererViewState worldViewState,
            RtSceneMaterialTable.Snapshot materialSnapshot,
            long sectionMaterialRevision,
            long dynamicMaterialRevision,
            DynamicRenderScene dynamicScene,
            int terrainMaterialCount,
            Reason reason,
            RendererFrameCausality causality,
            RtCausalitySink causalitySink,
            boolean validateWorldCoverage
    ) {
        if (generation < 0L || descriptorGeneration <= 0L) {
            throw new IllegalArgumentException("scene publication generations are invalid");
        }
        this.generation = generation;
        this.descriptorGeneration = descriptorGeneration;
        this.worldTlas = worldTlas;
        this.worldTlasRevision = worldTlasRevision;
        this.dynamicTlas = Objects.requireNonNull(dynamicTlas, "dynamicTlas");
        this.dynamicTlasRevision = dynamicTlasRevision;
        this.worldSectionKeys = Objects.requireNonNull(worldSectionKeys, "worldSectionKeys");
        this.worldSectionContentRevisions = Objects.requireNonNull(
                worldSectionContentRevisions,
                "worldSectionContentRevisions"
        );
        this.worldSectionCausalities = Objects.requireNonNull(worldSectionCausalities, "worldSectionCausalities");
        if (worldSectionCausalities.membership() != worldSectionKeys) {
            throw new IllegalArgumentException("publication section causalities must retain exact section membership");
        }
        this.worldViewState = Objects.requireNonNull(worldViewState, "worldViewState");
        this.materialSnapshot = Objects.requireNonNull(materialSnapshot, "materialSnapshot");
        this.sectionMaterialRevision = sectionMaterialRevision;
        this.dynamicMaterialRevision = dynamicMaterialRevision;
        this.dynamicScene = Objects.requireNonNull(dynamicScene, "dynamicScene");
        this.terrainMaterialCount = terrainMaterialCount;
        this.reason = Objects.requireNonNull(reason, "reason");
        this.causality = Objects.requireNonNull(causality, "causality");
        this.causalitySink = Objects.requireNonNull(causalitySink, "causalitySink");
        validate(validateWorldCoverage);
        recordPublicationEvent(validateWorldCoverage);
    }

    static RtScenePublication bootstrap(
            RtAccelerationStructure bootstrapTlas,
            long descriptorGeneration,
            RtCausalitySink causalitySink
    ) {
        return new RtScenePublication(
                0L,
                descriptorGeneration,
                null,
                -1L,
                Objects.requireNonNull(bootstrapTlas, "bootstrapTlas"),
                -1L,
                PackedSectionMembership.empty(),
                SectionRevisionSnapshot.empty(),
                SectionCausalitySnapshot.empty(),
                RendererViewState.allResident(),
                RtSceneMaterialTable.Snapshot.empty(),
                -1L,
                -1L,
                DynamicRenderScene.empty(),
                0,
                Reason.BOOTSTRAP,
                RendererFrameCausality.untraced(0L),
                causalitySink
        );
    }

    static boolean worldViewPromotionAllowed(
            RendererViewState currentViewState,
            RendererViewState candidateViewState
    ) {
        Objects.requireNonNull(currentViewState, "currentViewState");
        Objects.requireNonNull(candidateViewState, "candidateViewState");
        return candidateViewState.authoritative()
                && candidateViewState.revision() > currentViewState.revision();
    }

    RtScenePublication publishWorld(
            RtWorldTlasCache.WorldTlasUpdate update,
            long nextDescriptorGeneration,
            Reason publicationReason,
            RendererFrameCausality causality
    ) {
        return publishWorld(
                update,
                update.materialSnapshot(),
                update.dynamicMaterialRevision(),
                nextDescriptorGeneration,
                publicationReason,
                causality
        );
    }

    RtScenePublication publishWorld(
            RtWorldTlasCache.WorldTlasUpdate update,
            RtSceneMaterialTable.Snapshot nextMaterialSnapshot,
            long nextDynamicMaterialRevision,
            long nextDescriptorGeneration,
            Reason publicationReason,
            RendererFrameCausality causality
    ) {
        Objects.requireNonNull(update, "update");
        return new RtScenePublication(
                nextGeneration(),
                nextDescriptorGeneration,
                update.topLevelAccelerationStructure(),
                update.revision(),
                dynamicTlas,
                dynamicTlasRevision,
                update.sectionKeys(),
                update.sectionContentRevisions(),
                update.sectionCausalities(),
                update.viewState(),
                Objects.requireNonNull(nextMaterialSnapshot, "nextMaterialSnapshot"),
                update.sectionMaterialRevision(),
                nextDynamicMaterialRevision,
                dynamicScene,
                update.terrainMaterialCount(),
                publicationReason,
                causality,
                causalitySink
        );
    }

    RtScenePublication publishDynamic(
            RtDynamicTlasCache.Update update,
            RtSceneMaterialTable.Snapshot nextMaterialSnapshot,
            long nextDynamicMaterialRevision,
            long nextDescriptorGeneration,
            Reason publicationReason,
            RendererFrameCausality causality
    ) {
        Objects.requireNonNull(update, "update");
        return new RtScenePublication(
                nextGeneration(),
                nextDescriptorGeneration,
                worldTlas,
                worldTlasRevision,
                update.topLevelAccelerationStructure(),
                update.revision(),
                worldSectionKeys,
                worldSectionContentRevisions,
                worldSectionCausalities,
                worldViewState,
                Objects.requireNonNull(nextMaterialSnapshot, "nextMaterialSnapshot"),
                sectionMaterialRevision,
                nextDynamicMaterialRevision,
                update.dynamicScene(),
                terrainMaterialCount,
                publicationReason,
                causality,
                causalitySink,
                false
        );
    }

    RtScenePublication publishMaterial(
            RtSceneMaterialTable.Snapshot nextMaterialSnapshot,
            long nextSectionMaterialRevision,
            long nextDynamicMaterialRevision,
            long nextDescriptorGeneration,
            Reason publicationReason,
            RendererFrameCausality causality
    ) {
        return new RtScenePublication(
                nextGeneration(),
                nextDescriptorGeneration,
                worldTlas,
                worldTlasRevision,
                dynamicTlas,
                dynamicTlasRevision,
                worldSectionKeys,
                worldSectionContentRevisions,
                worldSectionCausalities,
                worldViewState,
                Objects.requireNonNull(nextMaterialSnapshot, "nextMaterialSnapshot"),
                nextSectionMaterialRevision,
                nextDynamicMaterialRevision,
                dynamicScene,
                terrainMaterialCount,
                publicationReason,
                causality,
                causalitySink,
                false
        );
    }

    /**
     * Advances only the logical world-view generation after the BLAS owner has
     * proved that this publication's existing TLAS already covers it.
     *
     * <p>No descriptor or native resource changes here. The publication keeps
     * the exact TLAS, material, membership and revision identities while making
     * the completed transaction visible to frame dispatch and presentation.</p>
     */
    RtScenePublication promoteWorldView(
            RendererViewState nextWorldViewState,
            RendererFrameCausality causality
    ) {
        RendererViewState promotedViewState = Objects.requireNonNull(
                nextWorldViewState,
                "nextWorldViewState"
        );
        if (!worldViewPromotionAllowed(worldViewState, promotedViewState)) {
            throw new IllegalArgumentException("world view promotion must advance an authoritative generation");
        }
        return new RtScenePublication(
                nextGeneration(),
                descriptorGeneration,
                worldTlas,
                worldTlasRevision,
                dynamicTlas,
                dynamicTlasRevision,
                worldSectionKeys,
                worldSectionContentRevisions,
                worldSectionCausalities,
                promotedViewState,
                materialSnapshot,
                sectionMaterialRevision,
                dynamicMaterialRevision,
                dynamicScene,
                terrainMaterialCount,
                Reason.WORLD_VIEW_METADATA_ONLY,
                causality,
                causalitySink,
                false
        );
    }

    RtAccelerationStructure worldTlas() {
        return worldTlas;
    }

    long worldTlasRevision() {
        return worldTlasRevision;
    }

    RtAccelerationStructure dynamicTlas() {
        return dynamicTlas;
    }

    long dynamicTlasRevision() {
        return dynamicTlasRevision;
    }

    PackedSectionMembership worldSectionKeys() {
        return worldSectionKeys;
    }

    SectionRevisionSnapshot worldSectionContentRevisions() {
        return worldSectionContentRevisions;
    }

    SectionCausalitySnapshot worldSectionCausalities() {
        return worldSectionCausalities;
    }

    RendererViewState worldViewState() {
        return worldViewState;
    }

    long worldViewRevision() {
        return worldViewState.revision();
    }

    RtSceneMaterialTable.Snapshot materialSnapshot() {
        return materialSnapshot;
    }

    long sectionMaterialRevision() {
        return sectionMaterialRevision;
    }

    long dynamicMaterialRevision() {
        return dynamicMaterialRevision;
    }

    DynamicRenderScene dynamicScene() {
        return dynamicScene;
    }

    int terrainMaterialCount() {
        return terrainMaterialCount;
    }

    long descriptorGeneration() {
        return descriptorGeneration;
    }

    long generation() {
        return generation;
    }

    RendererFrameCausality causality() {
        return causality;
    }

    /**
     * Resource compatibility fence for an asynchronous descriptor transaction. Logical view-only
     * promotions may advance {@link #generation} while retaining these exact identities; any
     * change below means the candidate was built against a different descriptor resource base.
     */
    boolean hasSameDescriptorResourceBase(RtScenePublication other) {
        Objects.requireNonNull(other, "other");
        return descriptorGeneration == other.descriptorGeneration
                && worldTlas == other.worldTlas
                && worldTlasRevision == other.worldTlasRevision
                && dynamicTlas == other.dynamicTlas
                && dynamicTlasRevision == other.dynamicTlasRevision
                && materialSnapshot == other.materialSnapshot
                && sectionMaterialRevision == other.sectionMaterialRevision
                && dynamicMaterialRevision == other.dynamicMaterialRevision
                && terrainMaterialCount == other.terrainMaterialCount;
    }

    RendererFrameCausality worldSectionCausality(SectionKey key) {
        return worldSectionCausalities.causality(Objects.requireNonNull(key, "key"));
    }

    RtCore.ScenePublicationState snapshot() {
        return new RtCore.ScenePublicationState(
                generation,
                descriptorGeneration,
                worldTlasRevision,
                dynamicTlasRevision,
                materialSnapshot.revision(),
                sectionMaterialRevision,
                dynamicMaterialRevision,
                worldViewRevision(),
                worldSectionKeys.size(),
                dynamicScene.revision(),
                reason.token
        );
    }

    private long nextGeneration() {
        return Math.addExact(generation, 1L);
    }

    private void validate(boolean validateWorldCoverage) {
        /*
         * World coverage is an invariant of publishWorld(). Dynamic TLAS and
         * material-only publications deliberately carry forward the already
         * validated immutable world sets; re-running keySet.equals() there was
         * sampled in JFR as ACTIVE main-thread SetN/MapN probing with no new
         * correctness signal.
         */
        if (validateWorldCoverage && worldSectionContentRevisions.membership() != worldSectionKeys) {
            throw new IllegalArgumentException(
                    "published section revisions must retain the exact section membership publication"
            );
        }
        if (worldTlas == null) {
            if (worldTlasRevision != -1L || !worldSectionKeys.isEmpty()
                    || !worldViewState.equals(RendererViewState.allResident())
                    || sectionMaterialRevision != -1L || materialSnapshot.sectionCount() != 0
                    || terrainMaterialCount != 0) {
                throw new IllegalArgumentException("bootstrap publication must not claim a world scene");
            }
        } else if (worldTlasRevision < 0L || sectionMaterialRevision < 0L || materialSnapshot.sectionCount() <= 0) {
            throw new IllegalArgumentException("published world scene requires TLAS and material generations");
        }
        if (terrainMaterialCount < 0
                || terrainMaterialCount > RtDynamicBlasCache.DYNAMIC_MATERIAL_INDEX_BIT
                || terrainMaterialCount > materialSnapshot.sectionCount()) {
            throw new IllegalArgumentException("published terrain material count exceeds material slots");
        }
        if (dynamicTlasRevision < -1L || dynamicMaterialRevision < -1L) {
            throw new IllegalArgumentException("published dynamic generations must be -1 or greater");
        }
    }

    private void recordPublicationEvent(boolean worldGenerationChanged) {
        if (generation == 0L) {
            return;
        }
        if (causalitySink.generationEventsEnabled()) {
            if (worldGenerationChanged) {
                for (SectionKey key : worldSectionKeys) {
                    RendererFrameCausality sectionCausality = worldSectionCausalities.causality(key);
                    causalitySink.sectionGeneration(
                            sectionCausality == null ? causality : sectionCausality,
                            key,
                            worldSectionContentRevisions.get(key),
                            -1L,
                            sectionMaterialRevision,
                            -1L,
                            -1L,
                            worldTlasRevision,
                            generation,
                            0
                    );
                }
            }
            if (reason == Reason.DYNAMIC_TLAS || reason == Reason.DYNAMIC_TLAS_WITH_MATERIAL) {
                LongOpenHashSet entityIds = new LongOpenHashSet();
                for (DynamicRenderScene.DynamicModelInstance instance : dynamicScene.modelInstances()) {
                    long entityId = DynamicModelIdentity.entityIdFromPrimitiveId(instance.id());
                    if (entityId >= 0L && entityIds.add(entityId)) {
                        causalitySink.entityGeneration(
                                causality,
                                entityId,
                                instance.asset().revision(),
                                dynamicScene.revision(),
                                dynamicTlasRevision,
                                dynamicTlasRevision,
                                generation,
                                0
                        );
                    }
                }
            }
        }
        causalitySink.publication(
                causality,
                generation,
                descriptorGeneration,
                worldTlasRevision,
                dynamicTlasRevision,
                dynamicScene.revision(),
                reason.code,
                worldSectionContentRevisions
        );
        ScenePublicationEvent event = new ScenePublicationEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.frameTransactionTraceId = causality.traceId();
        event.frameSequence = causality.frameSequence();
        event.submissionSource = causality.source().ordinal();
        event.publicationGeneration = generation;
        event.descriptorGeneration = descriptorGeneration;
        event.worldTlasRevision = worldTlasRevision;
        event.dynamicTlasRevision = dynamicTlasRevision;
        event.materialRevision = materialSnapshot.revision();
        event.sectionMaterialRevision = sectionMaterialRevision;
        event.dynamicMaterialRevision = dynamicMaterialRevision;
        event.viewRevision = worldViewRevision();
        event.sectionCount = worldSectionKeys.size();
        event.dynamicSceneRevision = dynamicScene.revision();
        event.reason = reason.code;
        event.commit();
    }

    enum Reason {
        BOOTSTRAP(0, "bootstrap"),
        DYNAMIC_TLAS(1, "dynamicTlasBound"),
        DYNAMIC_TLAS_WITH_MATERIAL(2, "dynamicTlasAtomicBound"),
        WORLD_TLAS(3, "worldTlasBound"),
        WORLD_TRANSFORM_REFIT(4, "dynamicTransformRefit"),
        MATERIAL_ONLY(5, "materialOnlyBound"),
        MATERIAL_METADATA_ONLY(6, "materialGenerationAdvancedWithoutUpload"),
        MATERIAL_UPLOAD_DEDUPLICATED(7, "materialUploadDeduplicated"),
        WORLD_VIEW_METADATA_ONLY(8, "worldViewGenerationAdvancedWithoutTlasRebuild");

        private final int code;
        private final String token;

        Reason(int code, String token) {
            this.code = code;
            this.token = token;
        }
    }

    @Name("top.ceroxe.rt.ScenePublication")
    @Label("RT Scene Publication")
    @Category({"RTRenderer", "Causality"})
    @StackTrace(false)
    static final class ScenePublicationEvent extends Event {
        long frameTransactionTraceId;
        long frameSequence;
        int submissionSource;
        long publicationGeneration;
        long descriptorGeneration;
        long worldTlasRevision;
        long dynamicTlasRevision;
        long materialRevision;
        long sectionMaterialRevision;
        long dynamicMaterialRevision;
        long viewRevision;
        int sectionCount;
        long dynamicSceneRevision;
        int reason;
    }
}
