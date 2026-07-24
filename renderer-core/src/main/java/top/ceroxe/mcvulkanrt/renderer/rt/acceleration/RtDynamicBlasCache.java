package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import org.lwjgl.vulkan.VkDevice;
import top.ceroxe.mcvulkanrt.renderer.DynamicMeshInstance;
import top.ceroxe.mcvulkanrt.renderer.DynamicModelIdentity;
import top.ceroxe.mcvulkanrt.renderer.DynamicRenderScene;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameCausality;
import top.ceroxe.mcvulkanrt.renderer.RendererRtDiagnostics;
import top.ceroxe.mcvulkanrt.renderer.DynamicMeshAsset;
import top.ceroxe.mcvulkanrt.renderer.rt.device.RtCommandContext;
import top.ceroxe.mcvulkanrt.renderer.rt.material.RtSceneMaterialTable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * BLAS cache for renderer-owned dynamic geometry.
 *
 * <p>The UE-style rule mirrored here is that dynamic scene facts are gathered
 * for a frame, converted into renderer-owned geometry, and then added to the RT
 * scene as real instances. This cache deliberately tracks revision lifetimes so
 * a completed TLAS or pending frame can keep referencing the old dynamic BLAS
 * until the world TLAS cache says it is no longer protected.</p>
 */
public final class RtDynamicBlasCache implements AutoCloseable {
    /*
     * Dynamic TLAS instances use a stable local material namespace. Terrain
     * slots grow while a bounded successor streams; baking that moving count
     * into VkAccelerationStructureInstanceKHR made the already-bound dynamic
     * TLAS point at stale section records until a later dynamic rebuild.
     */
    public static final int DYNAMIC_MATERIAL_INDEX_BIT = 1 << 23;
    public static final int DYNAMIC_MATERIAL_LOCAL_INDEX_MASK = DYNAMIC_MATERIAL_INDEX_BIT - 1;
    private static final int MAX_CACHED_ASSET_BLASES = 1024;
    private static final int MAX_PENDING_ASSET_BUILDS = 16;
    private static final int WORLD_VISIBILITY_MASK = 0x7F;
    private static final int OVERLAY_VISIBILITY_MASK = 0x80;

    private final VkDevice device;
    private final long allocator;
    private final RtCommandContext commandContext;
    private final int scratchAlignmentBytes;
    private final long placeholderBlasDeviceAddress;
    private final RtAccelerationStructure.TlasInstance inactiveInstance;
    private final RtDynamicBlasRetirementQueue retirementQueue = new RtDynamicBlasRetirementQueue();
    private final Long2ObjectOpenHashMap<AssetBlasEntry> assetBlases = new Long2ObjectOpenHashMap<>();
    private final AssetBuildQueue queuedAssetBuilds = new AssetBuildQueue();
    private RtAccelerationStructure dynamicBlas;
    private RtSceneMaterialTable.SectionMaterial dynamicMaterial;
    private RtPendingDynamicBlasBuild pendingBuild;
    private final Long2ObjectLinkedOpenHashMap<RtPendingDynamicAssetBlasBuild> pendingAssetBuilds =
            new Long2ObjectLinkedOpenHashMap<>();
    /*
     * This is the CPU analogue of UE GPUScene's PersistentPrimitiveIndex table.
     * host submits fresh value objects every render traversal, but a model
     * cube's encoded id is stable for its owner's lifetime.  Retaining the
     * renderer-owned order means transform-only frames neither sort nor clone
     * the whole dynamic scene before recording the Vulkan TLAS UPDATE input.
     */
    private PersistentSlots<DynamicRenderScene.DynamicModelInstance> activeMeshInstances =
            new PersistentSlots<>();
    private RtDynamicTransformSlots activeMeshTransforms = new RtDynamicTransformSlots();
    /*
     * The collector's physical slots and the legacy full-list bootstrap are
     * different identity domains. This flag records which domain currently
     * owns the native table; crossing into the collector domain is an explicit
     * snapshot rebase, never an opportunistic allocateAt collision recovery.
     */
    private boolean collectorOwnsPhysicalModelSlots;
    private final LongOpenHashSet observedMeshInstanceIds = new LongOpenHashSet();
    private final Long2ObjectOpenHashMap<DynamicMeshAsset> observedAssets = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet activeAssetIds = new LongOpenHashSet();
    private final Long2IntOpenHashMap activeAssetReferenceCounts = new Long2IntOpenHashMap();
    private DynamicRenderScene activeScene = DynamicRenderScene.empty();
    /*
     * UE-style cached RT material bindings. Transforms belong to TLAS instance
     * records; rebuilding face material records for a camera/entity transform
     * update is both semantically wrong and a major GC source.
     */
    private DynamicMaterialSnapshot cachedMaterialSnapshot = DynamicMaterialSnapshot.empty();
    private List<RtAccelerationStructure.TlasInstance> cachedTlasInstances = List.of();
    private final ArrayList<RtAccelerationStructure.TlasInstance> cachedTlasInstanceSlots = new ArrayList<>();
    private long cachedTlasInstanceRevision = -1L;
    private int[] cachedTlasInstanceDirtySlots = new int[0];
    private final BitSet dirtyMeshTlasSlots = new BitSet();
    private final ArrayList<RtSceneMaterialTable.SectionMaterial> cachedMeshMaterials = new ArrayList<>();
    private final BitSet dirtyMeshMaterialSlots = new BitSet();
    private boolean legacyMaterialDirty;
    private long cachedMaterialTopologyRevision = -1L;
    private long cachedMaterialGeometryRevision = -1L;
    private long cachedMaterialRevision = -1L;
    private int latestInactiveAssetSlots;
    private int latestReplacementAssetSlots;
    /*
     * These revisions intentionally map to distinct GPU resource lifetimes.
     *
     * <p>{@code revision} is the only dynamic revision consumed by the world
     * TLAS. It advances for topology, object-to-world transform, or BLAS-address
     * changes. {@code materialRevision} is consumed exclusively by the material
     * table upload path. host light, tint, UV animation and face material
     * changes therefore cannot schedule a TLAS refit.</p>
     */
    private long revision;
    private long topologyRevision;
    /** TLAS instance-descriptor revision; advances for transform or visibility-mask changes. */
    private long transformRevision;
    private long geometryRevision;
    private long materialRevision;
    private long latestObservedSceneRevision;
    private RendererFrameCausality latestDynamicCausality = RendererFrameCausality.untraced(0L);
    private long latestObservedLegacySceneRevision;
    private long activeLegacyMeshRevision;
    private long submittedBuilds;
    private long completedBuilds;
    private long buildPollsNotReady;
    private long closeWaits;
    private long discardedCompletedBuilds;
    private long clears;
    private long primitiveCount;
    private long faceCount;
    private long triangleCount;
    private int visibleInstanceCount;
    private long legacyPrimitiveCount;
    private long legacyFaceCount;
    private long legacyTriangleCount;
    private long totalTrianglesBuilt;
    private long dynamicBlasBytes;
    private long lastBuildLatencyMillis;
    private long maxBuildLatencyMillis;
    /* Smoke-only counters: classifications are recorded before GPU ownership changes. */
    private long smokeWindowStartNanos;
    private long smokeSceneEvents;
    private long smokeUnchangedScenes;
    private long smokeTransformOnlyScenes;
    private long smokeMaterialOnlyScenes;
    private long smokeTransformAndMaterialScenes;
    private long smokeTransformChangedInstances;
    private long smokeTransformTranslationChangedInstances;
    private long smokeTransformLinearChangedInstances;
    private long smokeTransformUniformTranslationScenes;
    private long smokeTransformMixedTranslationScenes;
    private long smokeTopologyChanges;
    private long smokeTopologySameOrder;
    private long smokeTopologyReordered;
    private long smokeTopologySizeChanged;
    private long smokeTopologyIdentityReplaced;
    private long smokeTopologyAdded;
    private long smokeTopologyRemoved;
    private final EnumMap<DynamicRenderScene.PrimitiveKind, Long> smokeTopologyAddedByKind =
            new EnumMap<>(DynamicRenderScene.PrimitiveKind.class);
    private final EnumMap<DynamicRenderScene.PrimitiveKind, Long> smokeTopologyRemovedByKind =
            new EnumMap<>(DynamicRenderScene.PrimitiveKind.class);
    private long smokeAssetBuildRequests;
    private long smokeAssetReplacements;
    private long smokeBuildCompletions;
    private long smokePersistentSlotReuses;
    private long smokePersistentSlotAdds;
    private long smokePersistentSlotRemovals;
    private long smokeTransformDirtySlots;
    private long smokeMaterialDirtySlots;
    private boolean lastAcceptedSceneTopologyChanged;
    private final RendererRtDiagnostics diagnostics;
    private boolean closed;

    public RtDynamicBlasCache(
            VkDevice device,
            long allocator,
            RtCommandContext commandContext,
            int scratchAlignmentBytes,
            long placeholderBlasDeviceAddress
    ) {
        this(device, allocator, commandContext, scratchAlignmentBytes,
                placeholderBlasDeviceAddress, RendererRtDiagnostics.noop());
    }

    public RtDynamicBlasCache(
            VkDevice device,
            long allocator,
            RtCommandContext commandContext,
            int scratchAlignmentBytes,
            long placeholderBlasDeviceAddress,
            RendererRtDiagnostics diagnostics
    ) {
        this.device = Objects.requireNonNull(device, "device");
        if (allocator == 0L) {
            throw new IllegalArgumentException("allocator must not be null");
        }
        this.allocator = allocator;
        this.commandContext = Objects.requireNonNull(commandContext, "commandContext");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        if (scratchAlignmentBytes <= 0 || placeholderBlasDeviceAddress == 0L) {
            throw new IllegalArgumentException("scratchAlignmentBytes must be positive");
        }
        this.scratchAlignmentBytes = scratchAlignmentBytes;
        this.placeholderBlasDeviceAddress = placeholderBlasDeviceAddress;
        this.inactiveInstance = RtAccelerationStructure.TlasInstance.inactive(placeholderBlasDeviceAddress);
    }

    public synchronized boolean acceptDynamicScene(DynamicRenderScene scene) {
        return acceptDynamicScene(scene, RendererFrameCausality.untraced(0L));
    }

    public synchronized boolean acceptDynamicScene(
            DynamicRenderScene scene,
            RendererFrameCausality causality
    ) {
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(causality, "causality");
        if (closed) {
            throw new IllegalStateException("RT dynamic BLAS cache is already closed");
        }
        if (!scene.hasSceneUpdate()) {
            return false;
        }
        latestObservedSceneRevision = Math.max(latestObservedSceneRevision, scene.revision());
        latestDynamicCausality = causality;
        activeScene = scene;

        DynamicInstanceChange change = updateMeshInstances(scene, causality);
        recordSmokeSceneClassification(change);

        RtDynamicTriangleMesh mesh = null;
        latestObservedLegacySceneRevision = Math.max(latestObservedLegacySceneRevision, scene.revision());
        if (mesh == null) {
            boolean legacyGeometryChanged = clearLegacyDynamicGeometry(scene.revision());
            submitNextAssetBuild();
            return change.tlasChanged() || legacyGeometryChanged;
        }
        long pendingLegacyMeshRevision = pendingBuild == null ? -1L : pendingBuild.mesh().revision();
        if (!shouldSubmitLegacyBuild(
                mesh.revision(),
                activeLegacyMeshRevision,
                pendingLegacyMeshRevision
        )) {
            submitNextAssetBuild();
            return change.tlasChanged();
        }
        replacePendingBuild(null);
        RtAccelerationStructure.DynamicBlasBuildSubmission submission =
                RtAccelerationStructure.submitDynamicBlasAsync(
                        device,
                        allocator,
                        commandContext,
                        scratchAlignmentBytes,
                        mesh
                );
        pendingBuild = new RtPendingDynamicBlasBuild(submission, mesh, causality);
        submittedBuilds++;
        submitNextAssetBuild();
        return true;
    }

    public synchronized void processFrameBudget() {
        if (closed) {
            throw new IllegalStateException("RT dynamic BLAS cache is already closed");
        }
        processLegacyBuild();
        processAssetBuild();
        submitNextAssetBuild();
    }

    private DynamicInstanceChange updateMeshInstances(
            DynamicRenderScene scene,
            RendererFrameCausality causality
    ) {
        if (scene.modelFrameDelta().isAuthoritative() && !containsLegacyModelInstances(scene)) {
            return updateMeshInstancesFromDelta(scene, causality);
        }
        collectorOwnsPhysicalModelSlots = false;
        observedAssets.clear();
        observedMeshInstanceIds.clear();
        RtDynamicSlotUpdateSummary updates = new RtDynamicSlotUpdateSummary();
        for (DynamicRenderScene.DynamicPrimitive primitive : scene.primitives()) {
            DynamicRenderScene.DynamicPrimitive instancedPrimitive =
                    RtDynamicTriangleMesh.promoteLegacyModelPrimitive(primitive);
            if (instancedPrimitive.meshInstance() == null) {
                continue;
            }
            DynamicMeshInstance meshInstance = instancedPrimitive.meshInstance();
            acceptObservedMeshInstance(new DynamicRenderScene.DynamicModelInstance(
                    instancedPrimitive.id(), instancedPrimitive.kind(), meshInstance.asset(), meshInstance.transform(),
                    meshInstance.faceMaterials(),
                    instancedPrimitive.packedLight(),
                    instancedPrimitive.debugName()
            ), observedAssets, updates, scene.revision(), causality);
        }
        for (DynamicRenderScene.DynamicModelInstance instancedPrimitive : scene.modelInstances()) {
            acceptObservedMeshInstance(instancedPrimitive, observedAssets, updates, scene.revision(), causality);
        }

        removeUnobservedPersistentMeshInstances(updates);
        rebuildActiveAssetReferenceCounts();
        boolean topologyChanged = updates.topologyChanged();
        boolean tlasInstanceChanged = updates.tlasInstanceDirtySlots() > 0;
        /*
         * Membership owns the custom-index material namespace. Even when every
         * surviving instance kept identical face data, add/remove/replace
         * changes which records exist and therefore requires a new material
         * generation alongside the TLAS topology generation.
         */
        boolean materialChanged = topologyChanged || updates.materialDirtySlots() > 0;
        queuedAssetBuilds.retainAssetIds(observedAssets.keySet());
        trimInactiveAssetCache(observedAssets.keySet());
        if (advancesTlasRevision(topologyChanged, tlasInstanceChanged)) {
            revision++;
        }
        if (topologyChanged) {
            topologyRevision++;
            smokeTopologyChanges++;
        }
        if (tlasInstanceChanged) {
            transformRevision++;
            if (updates.transformDirtySlots() > 0) {
                recordSmokeTransformDelta(updates.transformDelta());
            }
        }
        if (advancesMaterialRevision(materialChanged)) {
            materialRevision++;
        }
        recordSmokePersistentSlotUpdates(updates);
        lastAcceptedSceneTopologyChanged = topologyChanged;
        if (updates.removedSlots() > 0 && activeMeshInstances.activeCount() == 0) {
            clears++;
        }
        recomputeDynamicCounts();
        return new DynamicInstanceChange(topologyChanged, tlasInstanceChanged, materialChanged);
    }

    private static boolean containsLegacyModelInstances(DynamicRenderScene scene) {
        for (DynamicRenderScene.DynamicPrimitive primitive : scene.primitives()) {
            if (primitive.usesTlasGeometry() && primitive.meshInstance() != null) {
                return true;
            }
        }
        return false;
    }

    private DynamicInstanceChange updateMeshInstancesFromDelta(
            DynamicRenderScene scene,
            RendererFrameCausality causality
    ) {
        DynamicRenderScene.DynamicModelFrameDelta delta = scene.modelFrameDelta();
        observedAssets.clear();
        RtDynamicSlotUpdateSummary updates = new RtDynamicSlotUpdateSummary();
        boolean rebase = !collectorOwnsPhysicalModelSlots || movesExistingIdentityToAnotherSlot(delta);
        rebase |= delta.physicalSlotCount() < activeMeshInstances.capacity();
        if (rebase) {
            int previousCapacity = activeMeshInstances.capacity();
            int previousActiveCount = activeMeshInstances.activeCount();
            PersistentSlots<DynamicRenderScene.DynamicModelInstance> stagedInstances =
                    rebaseAuthoritativeModelSlots(activeMeshInstances, delta);
            RtDynamicTransformSlots stagedTransforms = rebaseAuthoritativeTransformSlots(delta);
            activeMeshInstances = stagedInstances;
            activeMeshTransforms = stagedTransforms;
            collectorOwnsPhysicalModelSlots = true;
            activeAssetReferenceCounts.clear();
            rebuildActiveAssetReferenceCounts();
            int dirtyCapacity = Math.max(previousCapacity, activeMeshInstances.capacity());
            dirtyMeshMaterialSlots.set(0, dirtyCapacity);
            dirtyMeshTlasSlots.set(0, dirtyCapacity);
            updates.rebased(previousActiveCount, activeMeshInstances.activeCount());
            for (DynamicRenderScene.DynamicModelInstance instance : activeMeshInstances) {
                if (instance != null) {
                    observeDeltaAsset(scene, instance, causality);
                }
            }
        } else {
            if (delta.physicalSlotCount() > activeMeshInstances.capacity()) {
                activeMeshInstances.ensureCapacity(delta.physicalSlotCount());
                activeMeshTransforms.resize(delta.physicalSlotCount());
            }
            applyAuthoritativeDeltaUpdates(scene, delta, updates, causality);
        }

        boolean topologyChanged = updates.topologyChanged();
        boolean tlasInstanceChanged = updates.tlasInstanceDirtySlots() > 0 || topologyChanged;
        boolean materialChanged = topologyChanged || updates.materialDirtySlots() > 0;
        queuedAssetBuilds.retainAssetIds(activeAssetReferenceCounts.keySet());
        trimInactiveAssetCache(activeAssetReferenceCounts.keySet());
        if (advancesTlasRevision(topologyChanged, tlasInstanceChanged)) {
            revision++;
        }
        if (topologyChanged) {
            topologyRevision++;
            smokeTopologyChanges++;
        }
        if (tlasInstanceChanged) {
            transformRevision++;
            if (updates.transformDirtySlots() > 0) {
                recordSmokeTransformDelta(updates.transformDelta());
            }
        }
        if (advancesMaterialRevision(materialChanged)) {
            materialRevision++;
        }
        recordSmokePersistentSlotUpdates(updates);
        lastAcceptedSceneTopologyChanged = topologyChanged;
        if (updates.removedSlots() > 0 && activeMeshInstances.activeCount() == 0) {
            clears++;
        }
        recomputeDynamicCounts();
        return new DynamicInstanceChange(topologyChanged, tlasInstanceChanged, materialChanged);
    }

    private void applyAuthoritativeDeltaUpdates(
            DynamicRenderScene scene,
            DynamicRenderScene.DynamicModelFrameDelta delta,
            RtDynamicSlotUpdateSummary updates,
            RendererFrameCausality causality
    ) {
        for (int update = 0; update < delta.updateCount(); update++) {
            int slot = delta.slotAt(update);
            int mask = delta.dirtyMaskAt(update);
            boolean added = (mask & DynamicRenderScene.DynamicModelFrameDelta.ADDED) != 0;
            boolean removed = (mask & DynamicRenderScene.DynamicModelFrameDelta.REMOVED) != 0;
            DynamicRenderScene.DynamicModelInstance previous =
                    slot < activeMeshInstances.capacity() ? activeMeshInstances.get(slot) : null;
            if (removed && previous != null) {
                decrementAssetReference(previous.asset().id());
                activeMeshInstances.removeAt(slot, previous.id());
                activeMeshTransforms.remove(slot);
                dirtyMeshMaterialSlots.set(slot);
                dirtyMeshTlasSlots.set(slot);
                updates.removedSlot();
                previous = null;
            }
            if (removed && !added) {
                continue;
            }

            DynamicRenderScene.DynamicModelInstance publication = delta.publicationAt(update);
            DynamicRenderScene.DynamicModelInstance next = publication;
            if (next == null) {
                if (previous == null) {
                    throw new IllegalStateException("transform-only delta targeted an inactive model slot: " + slot);
                }
                updates.reusedSlot(false, false, false, activeMeshTransforms, slot, delta, update);
                activeMeshTransforms.set(slot, delta, update);
                dirtyMeshTlasSlots.set(slot);
                observeDeltaAsset(scene, previous, causality);
                continue;
            }

            if (added) {
                int existingSlot = activeMeshInstances.slotFor(next.id());
                if (previous != null) {
                    if (previous.id() != next.id() || existingSlot != slot) {
                        throw new IllegalStateException(
                                "authoritative dynamic slot is occupied by another identity without removal: " + slot
                        );
                    }
                    applyDeltaToPersistentSlot(slot, previous, next, mask, delta, update, updates);
                } else {
                    if (existingSlot >= 0) {
                        throw new IllegalStateException("authoritative dynamic identity moved from slot "
                                + existingSlot + " to " + slot + " without a complete snapshot rebase");
                    }
                    activeMeshInstances.allocateAt(slot, next.id());
                    activeMeshInstances.set(slot, next);
                    if ((mask & DynamicRenderScene.DynamicModelFrameDelta.TRANSFORM) != 0) {
                        activeMeshTransforms.set(slot, delta, update);
                    } else {
                        activeMeshTransforms.set(slot, next);
                    }
                    incrementAssetReference(next.asset().id());
                    dirtyMeshMaterialSlots.set(slot);
                    dirtyMeshTlasSlots.set(slot);
                    updates.addedSlot();
                }
            } else {
                if (previous == null || previous.id() != next.id()) {
                    throw new IllegalStateException("dynamic model delta lost physical slot identity: " + slot);
                }
                applyDeltaToPersistentSlot(slot, previous, next, mask, delta, update, updates);
            }
            observeDeltaAsset(scene, next, causality);
        }
    }

    private boolean movesExistingIdentityToAnotherSlot(DynamicRenderScene.DynamicModelFrameDelta delta) {
        for (int update = 0; update < delta.updateCount(); update++) {
            if ((delta.dirtyMaskAt(update) & DynamicRenderScene.DynamicModelFrameDelta.ADDED) == 0) {
                continue;
            }
            DynamicRenderScene.DynamicModelInstance publication = delta.publicationAt(update);
            int existingSlot = activeMeshInstances.slotFor(publication.id());
            if (existingSlot >= 0 && existingSlot != delta.slotAt(update)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the collector-owned table off to the side and publishes it only
     * after the complete snapshot passes validation. The old full-list table
     * therefore remains intact if a truncated or malformed handoff arrives.
     */
    static PersistentSlots<DynamicRenderScene.DynamicModelInstance> rebaseAuthoritativeModelSlots(
            PersistentSlots<DynamicRenderScene.DynamicModelInstance> previous,
            DynamicRenderScene.DynamicModelFrameDelta delta
    ) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(delta, "delta");
        DynamicRenderScene.DynamicModelSlotSnapshot snapshot = delta.membershipSnapshot();
        PersistentSlots<DynamicRenderScene.DynamicModelInstance> staged = new PersistentSlots<>();
        staged.ensureCapacity(snapshot.physicalSlotCount());
        for (int index = 0; index < snapshot.activeSlotCount(); index++) {
            int slot = snapshot.slotAt(index);
            DynamicRenderScene.DynamicModelInstance instance = snapshot.instanceAt(index);
            staged.allocateAt(slot, instance.id());
            staged.set(slot, instance);
        }
        if (staged.activeCount() != snapshot.activeSlotCount()
                || staged.capacity() != snapshot.physicalSlotCount()) {
            throw new IllegalStateException("authoritative dynamic model snapshot produced an invalid native table");
        }
        return staged;
    }

    static RtDynamicTransformSlots rebaseAuthoritativeTransformSlots(
            DynamicRenderScene.DynamicModelFrameDelta delta
    ) {
        return RtDynamicTransformSlots.fromAuthoritative(Objects.requireNonNull(delta, "delta"));
    }

    private void observeDeltaAsset(
            DynamicRenderScene scene,
            DynamicRenderScene.DynamicModelInstance instance,
            RendererFrameCausality causality
    ) {
        observeAsset(observedAssets, instance.asset());
        AssetBlasEntry cached = assetBlases.get(instance.asset().id());
        if (cached == null || !cached.asset().equals(instance.asset())) {
            smokeAssetBuildRequests++;
            queueAssetBuild(instance.asset(), cached, causality);
        } else {
            cached.lastSeenSceneRevision = scene.revision();
        }
    }

    private void applyDeltaToPersistentSlot(
            int slot,
            DynamicRenderScene.DynamicModelInstance previous,
            DynamicRenderScene.DynamicModelInstance next,
            int mask,
            DynamicRenderScene.DynamicModelFrameDelta delta,
            int update,
            RtDynamicSlotUpdateSummary updates
    ) {
        boolean topologyChanged = (mask & DynamicRenderScene.DynamicModelFrameDelta.TOPOLOGY) != 0;
        boolean transformChanged = (mask & DynamicRenderScene.DynamicModelFrameDelta.TRANSFORM) != 0;
        boolean materialChanged = (mask & (DynamicRenderScene.DynamicModelFrameDelta.MATERIAL
                | DynamicRenderScene.DynamicModelFrameDelta.LIGHT)) != 0;
        boolean renderLaneChanged = previous.renderLane() != next.renderLane();
        if (topologyChanged && previous.asset().id() != next.asset().id()) {
            decrementAssetReference(previous.asset().id());
            incrementAssetReference(next.asset().id());
        }
        if (!topologyChanged && transformChanged) {
            updates.reusedSlot(
                    true, renderLaneChanged, materialChanged,
                    activeMeshTransforms, slot, delta, update
            );
            activeMeshTransforms.set(slot, delta, update);
        } else if (transformChanged) {
            activeMeshTransforms.set(slot, delta, update);
        } else if (!topologyChanged) {
            updates.reusedSlot(false, renderLaneChanged, materialChanged, next.transform(), next.transform());
        }
        activeMeshInstances.set(slot, next);
        if (materialChanged || topologyChanged) {
            dirtyMeshMaterialSlots.set(slot);
        }
        if (transformChanged || renderLaneChanged || topologyChanged) {
            dirtyMeshTlasSlots.set(slot);
        }
        if (topologyChanged) {
            updates.replacedSlot();
            return;
        }
    }

    private void incrementAssetReference(long assetId) {
        activeAssetReferenceCounts.addTo(assetId, 1);
    }

    private void decrementAssetReference(long assetId) {
        int previous = activeAssetReferenceCounts.get(assetId);
        if (previous <= 0) {
            throw new IllegalStateException("dynamic asset reference table underflow: " + assetId);
        }
        if (previous == 1) {
            activeAssetReferenceCounts.remove(assetId);
        } else {
            activeAssetReferenceCounts.put(assetId, previous - 1);
        }
    }

    private void rebuildActiveAssetReferenceCounts() {
        activeAssetReferenceCounts.clear();
        for (DynamicRenderScene.DynamicModelInstance instance : activeMeshInstances) {
            if (instance != null) {
                incrementAssetReference(instance.asset().id());
            }
        }
    }

    /**
     * Both modern model submissions and promoted legacy MODEL primitives own
     * the same persistent asset/instance lifecycle. Keeping BLAS admission in
     * this shared entry prevents one capture representation from publishing a
     * TLAS identity without ever scheduling its geometry.
     */
    private void acceptObservedMeshInstance(
            DynamicRenderScene.DynamicModelInstance instance,
            Long2ObjectMap<DynamicMeshAsset> observedAssets,
            RtDynamicSlotUpdateSummary updates,
            long sceneRevision,
            RendererFrameCausality causality
    ) {
        DynamicMeshAsset asset = instance.asset();
        observeAsset(observedAssets, asset);
        acceptPersistentMeshInstance(instance, updates);
        AssetBlasEntry cached = assetBlases.get(asset.id());
        if (cached == null || !cached.asset().equals(asset)) {
            smokeAssetBuildRequests++;
            queueAssetBuild(asset, cached, causality);
        } else {
            cached.lastSeenSceneRevision = sceneRevision;
        }
    }

    private static void observeAsset(Long2ObjectMap<DynamicMeshAsset> observedAssets, DynamicMeshAsset asset) {
        DynamicMeshAsset previous = observedAssets.putIfAbsent(asset.id(), asset);
        if (previous != null && !previous.equals(asset)) {
            throw new IllegalArgumentException("dynamic scene reused asset id with different geometry: " + asset.id());
        }
    }

    /** Updates a persistent slot in place; fresh capture objects never determine TLAS ordering. */
    private void acceptPersistentMeshInstance(
            DynamicRenderScene.DynamicModelInstance instance,
            RtDynamicSlotUpdateSummary updates
    ) {
        if (!observedMeshInstanceIds.add(instance.id())) {
            throw new IllegalArgumentException("dynamic scene submitted duplicate persistent instance id: "
                    + instance.id());
        }
        int slot = activeMeshInstances.slotFor(instance.id());
        if (slot < 0) {
            int allocatedSlot = activeMeshInstances.allocate(instance.id());
            activeMeshInstances.set(allocatedSlot, instance);
            activeMeshTransforms.resize(activeMeshInstances.capacity());
            activeMeshTransforms.set(allocatedSlot, instance);
            dirtyMeshMaterialSlots.set(allocatedSlot);
            dirtyMeshTlasSlots.set(allocatedSlot);
            updates.addedSlot();
            return;
        }
        DynamicRenderScene.DynamicModelInstance previous = activeMeshInstances.get(slot);
        if (previous.asset().id() != instance.asset().id()) {
            activeMeshInstances.set(slot, instance);
            activeMeshTransforms.set(slot, instance);
            dirtyMeshMaterialSlots.set(slot);
            dirtyMeshTlasSlots.set(slot);
            updates.replacedSlot();
            return;
        }
        if (!previous.asset().equals(instance.asset())) {
            requireNewerAssetVersion(instance.asset(), previous.asset(), "active instance");
            activeMeshInstances.set(slot, instance);
            activeMeshTransforms.set(slot, instance);
            dirtyMeshMaterialSlots.set(slot);
            dirtyMeshTlasSlots.set(slot);
            updates.replacedSlot();
            return;
        }
        boolean transformChanged = !activeMeshTransforms.matches(slot, instance);
        boolean renderLaneChanged = previous.renderLane() != instance.renderLane();
        boolean materialChanged = previous.packedLight() != instance.packedLight()
                || !previous.faceMaterials().equals(instance.faceMaterials());
        if (transformChanged || renderLaneChanged || materialChanged || previous.kind() != instance.kind()
                || !previous.debugName().equals(instance.debugName())) {
            activeMeshInstances.set(slot, instance);
        }
        if (materialChanged) {
            dirtyMeshMaterialSlots.set(slot);
        }
        if (transformChanged || renderLaneChanged) {
            dirtyMeshTlasSlots.set(slot);
        }
        updates.reusedSlot(
                transformChanged,
                renderLaneChanged,
                materialChanged,
                activeMeshTransforms,
                slot,
                instance
        );
        if (transformChanged) {
            activeMeshTransforms.set(slot, instance);
        }
    }

    /** Removes absent owners without moving a surviving GPU-visible slot. */
    private void removeUnobservedPersistentMeshInstances(RtDynamicSlotUpdateSummary updates) {
        for (int slot = 0; slot < activeMeshInstances.capacity(); slot++) {
            DynamicRenderScene.DynamicModelInstance instance = activeMeshInstances.get(slot);
            if (instance == null) {
                continue;
            }
            if (observedMeshInstanceIds.contains(instance.id())) {
                continue;
            }
            activeMeshInstances.remove(instance.id());
            activeMeshTransforms.remove(slot);
            dirtyMeshMaterialSlots.set(slot);
            dirtyMeshTlasSlots.set(slot);
            updates.removedSlot();
        }
        activeMeshInstances.trimTrailingVacancies();
        activeMeshTransforms.resize(activeMeshInstances.capacity());
    }

    private void queueAssetBuild(
            DynamicMeshAsset asset,
            AssetBlasEntry cached,
            RendererFrameCausality causality
    ) {
        Objects.requireNonNull(causality, "causality");
        RtPendingDynamicAssetBlasBuild pending = pendingAssetBuilds.get(asset.id());
        DynamicMeshAsset pendingAsset = pending == null ? null : pending.asset();
        DynamicMeshAsset cachedAsset = cached == null ? null : cached.asset();
        queuedAssetBuilds.offer(asset, causality, pendingAsset, cachedAsset);
    }

    private void submitNextAssetBuild() {
        while (pendingAssetBuilds.size() < MAX_PENDING_ASSET_BUILDS && !queuedAssetBuilds.isEmpty()) {
            QueuedAssetBuild queued = queuedAssetBuilds.pollEntry();
            DynamicMeshAsset asset = queued.asset();
            AssetBlasEntry cached = assetBlases.get(asset.id());
            if (cached != null && cached.asset().equals(asset)) {
                continue;
            }
            if (pendingAssetBuilds.containsKey(asset.id())) {
                queuedAssetBuilds.offer(asset, queued.causality(), pendingAssetBuilds.get(asset.id()).asset(),
                        cached == null ? null : cached.asset());
                continue;
            }
            if (cached != null) {
                requireNewerAssetVersion(asset, cached.asset(), "cached BLAS");
            }
            RtDynamicTriangleMesh mesh = RtDynamicTriangleMesh.fromAsset(asset, diagnostics.materials());
            RtAccelerationStructure.DynamicBlasBuildSubmission submission =
                    RtAccelerationStructure.submitDynamicBlasAsync(
                            device,
                            allocator,
                            commandContext,
                            scratchAlignmentBytes,
                            mesh
                    );
            pendingAssetBuilds.put(
                    asset.id(),
                    new RtPendingDynamicAssetBlasBuild(asset, submission, queued.causality())
            );
            submittedBuilds++;
        }
    }

    private void processLegacyBuild() {
        RtPendingDynamicBlasBuild pending = pendingBuild;
        if (pending == null) {
            return;
        }
        RtAccelerationStructure.CompletedDynamicBlasBuild completed = pending.submission().completeIfReady();
        if (completed == null) {
            buildPollsNotReady++;
            return;
        }
        pendingBuild = null;
        recordCompletedBuild(completed);
        if (completed.mesh().revision() < latestObservedLegacySceneRevision) {
            discardedCompletedBuilds++;
            completed.accelerationStructure().close();
            return;
        }

        RtAccelerationStructure previous = dynamicBlas;
        if (previous != null) {
            retireDynamicBlas(revision + 1L, previous);
        }
        dynamicBlas = completed.accelerationStructure();
        dynamicMaterial = completed.mesh().material();
        legacyMaterialDirty = true;
        activeLegacyMeshRevision = completed.mesh().revision();
        legacyPrimitiveCount = completed.mesh().primitiveCount();
        legacyFaceCount = completed.mesh().faceCount();
        legacyTriangleCount = completed.mesh().triangleCount();
        totalTrianglesBuilt += completed.mesh().triangleCount();
        revision++;
        geometryRevision++;
        materialRevision++;
        recomputeDynamicCounts();
        recomputeDynamicBlasBytes();
    }

    private void processAssetBuild() {
        boolean residencyChanged = false;
        ObjectIterator<Long2ObjectMap.Entry<RtPendingDynamicAssetBlasBuild>> iterator =
                pendingAssetBuilds.long2ObjectEntrySet().iterator();
        while (iterator.hasNext()) {
            RtPendingDynamicAssetBlasBuild pending = iterator.next().getValue();
            RtAccelerationStructure.CompletedDynamicBlasBuild completed = pending.submission().completeIfReady();
            if (completed == null) {
                buildPollsNotReady++;
                continue;
            }
            iterator.remove();
            recordCompletedBuild(completed);
            AssetBlasEntry previous = assetBlases.get(pending.asset().id());
            DynamicMeshAsset queuedReplacement = queuedAssetBuilds.asset(pending.asset().id());
            boolean superseded;
            try {
                superseded = assetBuildWasSuperseded(pending.asset(), previous, queuedReplacement);
            } catch (RuntimeException ex) {
                try {
                    completed.accelerationStructure().close();
                } catch (RuntimeException closeFailure) {
                    ex.addSuppressed(closeFailure);
                }
                throw ex;
            }
            if (superseded) {
                discardedCompletedBuilds++;
                completed.accelerationStructure().close();
                continue;
            }
            if (previous != null) {
                smokeAssetReplacements++;
                retireDynamicBlas(revision + 1L, previous.blas());
            }
            assetBlases.put(
                    pending.asset().id(),
                    new AssetBlasEntry(
                            pending.asset(),
                            completed.accelerationStructure(),
                            latestObservedSceneRevision,
                            pending.causality()
                    )
            );
            totalTrianglesBuilt += completed.mesh().triangleCount();
            revision++;
            geometryRevision++;
            materialRevision++;
            markMaterialSlotsDirtyForAsset(pending.asset().id());
            markTlasSlotsDirtyForAsset(pending.asset().id());
            residencyChanged = true;
        }
        trimInactiveAssetCache(activeAssetIds());
        if (residencyChanged) {
            recomputeDynamicCounts();
        }
    }

    private void recordCompletedBuild(RtAccelerationStructure.CompletedDynamicBlasBuild completed) {
        completedBuilds++;
        smokeBuildCompletions++;
        long elapsedMillis = completed.elapsedNanos() / 1_000_000L;
        lastBuildLatencyMillis = elapsedMillis;
        maxBuildLatencyMillis = Math.max(maxBuildLatencyMillis, elapsedMillis);
    }

    private void recomputeDynamicCounts() {
        long nextPrimitiveCount = legacyPrimitiveCount + activeMeshInstances.activeCount();
        long nextFaceCount = legacyFaceCount;
        long nextTriangleCount = legacyTriangleCount;
        int nextInactiveAssetSlots = 0;
        int nextReplacementAssetSlots = 0;
        for (DynamicRenderScene.DynamicModelInstance primitive : activeMeshInstances) {
            if (primitive == null) {
                continue;
            }
            DynamicMeshAsset asset = primitive.asset();
            nextFaceCount += asset.faceCount();
            nextTriangleCount += asset.triangleCount();
            AssetBlasEntry entry = assetBlases.get(asset.id());
            if (entry == null || !residentAssetUsableDuringReplacement(asset, entry.asset())) {
                nextInactiveAssetSlots++;
            } else if (!entry.asset().equals(asset)) {
                nextReplacementAssetSlots++;
            }
        }
        primitiveCount = nextPrimitiveCount;
        faceCount = nextFaceCount;
        triangleCount = nextTriangleCount;
        visibleInstanceCount = (dynamicBlas == null ? 0 : 1)
                + activeMeshInstances.activeCount() - nextInactiveAssetSlots;
        latestInactiveAssetSlots = nextInactiveAssetSlots;
        latestReplacementAssetSlots = nextReplacementAssetSlots;
    }

    private void recomputeDynamicBlasBytes() {
        long total = dynamicBlas == null ? 0L : dynamicBlas.storageBytes();
        for (AssetBlasEntry entry : assetBlases.values()) {
            total += entry.blas().storageBytes();
        }
        dynamicBlasBytes = total;
    }

    private void trimInactiveAssetCache(LongSet activeAssetIds) {
        while (assetBlases.size() > MAX_CACHED_ASSET_BLASES) {
            AssetBlasEntry oldest = null;
            for (AssetBlasEntry candidate : assetBlases.values()) {
                if (activeAssetIds.contains(candidate.asset().id())) {
                    continue;
                }
                if (oldest == null
                        || candidate.lastSeenSceneRevision < oldest.lastSeenSceneRevision
                        || (candidate.lastSeenSceneRevision == oldest.lastSeenSceneRevision
                        && candidate.asset().id() < oldest.asset().id())) {
                    oldest = candidate;
                }
            }
            if (oldest == null) {
                break;
            }
            assetBlases.remove(oldest.asset().id());
            retireDynamicBlas(revision + 1L, oldest.blas());
        }
        recomputeDynamicBlasBytes();
    }

    public synchronized RtDynamicInstanceSnapshot snapshotInstanceState() {
        if (closed) {
            throw new IllegalStateException("RT dynamic BLAS cache is already closed");
        }
        boolean hasPersistentSlotTable = dynamicBlas != null || activeMeshInstances.capacity() > 0;
        if (!hasPersistentSlotTable) {
            return RtDynamicInstanceSnapshot.empty(
                    revision,
                    topologyRevision,
                    transformRevision,
                    geometryRevision,
                    materialRevision,
                    latestObservedSceneRevision,
                    latestDynamicCausality,
                    activeScene
            );
        }
        DynamicMaterialSnapshot materialSnapshot = materialSnapshotForReadyAssets();
        TlasInstancePublication tlasPublication = tlasInstanceTableSnapshot();
        List<RtAccelerationStructure.TlasInstance> instances = tlasPublication.instances();
        List<RtSceneMaterialTable.SectionMaterial> materials = materialSnapshot.materials();
        int[] materialDirtySlots = materialSnapshot.dirtySlots();
        return new RtDynamicInstanceSnapshot(
                revision,
                topologyRevision,
                transformRevision,
                geometryRevision,
                materialRevision,
                latestObservedSceneRevision,
                latestDynamicCausality,
                activeScene,
                instances,
                tlasPublication.dirtySlots(),
                materials,
                materialDirtySlots,
                visibleInstanceCount,
                primitiveCount,
                faceCount,
                triangleCount
        );
    }

    /**
     * Publishes the stable physical slot table once per TLAS revision.
     * Material-only and observer-only scene revisions deliberately reuse these
     * immutable records because neither changes BLAS address, transform,
     * custom index, visibility mask, or physical capacity.
     */
    private TlasInstancePublication tlasInstanceTableSnapshot() {
        if (cachedTlasInstanceRevision == revision) {
            return new TlasInstancePublication(cachedTlasInstances, cachedTlasInstanceDirtySlots);
        }
        int requiredSlots = Math.addExact(1, activeMeshInstances.capacity());
        boolean tableResized = cachedTlasInstances.size() != requiredSlots;
        while (cachedTlasInstanceSlots.size() < requiredSlots) {
            cachedTlasInstanceSlots.add(inactiveInstance);
        }
        while (cachedTlasInstanceSlots.size() > requiredSlots) {
            cachedTlasInstanceSlots.remove(cachedTlasInstanceSlots.size() - 1);
        }
        RtAccelerationStructure.TlasInstance legacyInstance = dynamicBlas != null && dynamicMaterial != null
                ? new RtAccelerationStructure.TlasInstance(
                        dynamicBlas.deviceAddress(),
                        DynamicMeshInstance.AffineTransform.identity(),
                        dynamicMaterialCustomIndex(0),
                        WORLD_VISIBILITY_MASK
                )
                : inactiveInstance;
        boolean legacySlotDirty = tableResized
                || cachedTlasInstances.isEmpty()
                || !Objects.equals(cachedTlasInstances.get(0), legacyInstance);
        cachedTlasInstanceSlots.set(0, legacyInstance);
        BitSet dirtyTableSlots = new BitSet(requiredSlots);
        if (tableResized || cachedTlasInstanceRevision < 0L) {
            dirtyTableSlots.set(0, requiredSlots);
        } else if (legacySlotDirty) {
            dirtyTableSlots.set(0);
        }
        for (int slot = dirtyMeshTlasSlots.nextSetBit(0);
             slot >= 0 && slot < activeMeshInstances.capacity();
             slot = dirtyMeshTlasSlots.nextSetBit(slot + 1)) {
            cachedTlasInstanceSlots.set(Math.addExact(1, slot), tlasInstanceForSlot(slot));
            dirtyTableSlots.set(Math.addExact(1, slot));
        }
        cachedTlasInstances = RtPersistentTlasInstanceTable.update(
                cachedTlasInstances,
                cachedTlasInstanceSlots,
                dirtyTableSlots
        );
        cachedTlasInstanceDirtySlots = bitSetToSortedArray(dirtyTableSlots);
        dirtyMeshTlasSlots.clear();
        cachedTlasInstanceRevision = revision;
        return new TlasInstancePublication(cachedTlasInstances, cachedTlasInstanceDirtySlots);
    }

    private static int[] bitSetToSortedArray(BitSet bits) {
        int[] slots = new int[bits.cardinality()];
        int cursor = 0;
        for (int slot = bits.nextSetBit(0); slot >= 0; slot = bits.nextSetBit(slot + 1)) {
            slots[cursor++] = slot;
        }
        return slots;
    }

    private record TlasInstancePublication(
            List<RtAccelerationStructure.TlasInstance> instances,
            int[] dirtySlots
    ) {
        private TlasInstancePublication {
            instances = Objects.requireNonNull(instances, "instances");
            dirtySlots = java.util.Arrays.copyOf(
                    Objects.requireNonNull(dirtySlots, "dirtySlots"),
                    dirtySlots.length
            );
        }
    }

    private RtAccelerationStructure.TlasInstance tlasInstanceForSlot(int slot) {
        DynamicRenderScene.DynamicModelInstance primitive = activeMeshInstances.get(slot);
        int instanceCustomIndex = dynamicMaterialCustomIndex(Math.addExact(1, slot));
        if (primitive == null) {
            return inactiveInstance;
        }
        DynamicMeshAsset asset = primitive.asset();
        AssetBlasEntry entry = assetBlases.get(asset.id());
        if (entry == null || !residentAssetUsableDuringReplacement(asset, entry.asset())) {
            RtDynamicInstanceFlightRecorder.record(
                    entry == null ? "missingAsset" : "residentAssetRejected",
                    revision,
                    topologyRevision,
                    transformRevision,
                    geometryRevision,
                    materialRevision,
                    latestObservedSceneRevision,
                    slot,
                    instanceCustomIndex,
                    visibilityMask(primitive),
                    0L,
                    primitive,
                    activeMeshTransforms,
                    entry == null ? null : entry.asset(),
                    true
            );
            return inactiveInstance;
        }
        boolean assetRevisionMismatch = !entry.asset().equals(asset);
        RtDynamicInstanceFlightRecorder.record(
                assetRevisionMismatch ? "assetRevisionMismatch" : "ready",
                revision,
                topologyRevision,
                transformRevision,
                geometryRevision,
                materialRevision,
                latestObservedSceneRevision,
                slot,
                instanceCustomIndex,
                visibilityMask(primitive),
                entry.blas().deviceAddress(),
                primitive,
                activeMeshTransforms,
                entry.asset(),
                assetRevisionMismatch
        );
        return activeMeshTransforms.tlasInstance(
                slot, entry.blas().deviceAddress(), instanceCustomIndex, visibilityMask(primitive)
        );
    }

    public static int dynamicMaterialCustomIndex(int localMaterialSlot) {
        if (localMaterialSlot < 0 || localMaterialSlot > DYNAMIC_MATERIAL_LOCAL_INDEX_MASK) {
            throw new IllegalArgumentException("dynamic local material slot exceeds the stable 23-bit namespace");
        }
        return DYNAMIC_MATERIAL_INDEX_BIT | localMaterialSlot;
    }

    private static int visibilityMask(DynamicRenderScene.DynamicModelInstance primitive) {
        return switch (primitive.renderLane()) {
            case WORLD -> WORLD_VISIBILITY_MASK;
            case ALWAYS_ON_TOP -> OVERLAY_VISIBILITY_MASK;
        };
    }

    private DynamicMaterialSnapshot materialSnapshotForReadyAssets() {
        if (cachedMaterialTopologyRevision == topologyRevision
                && cachedMaterialGeometryRevision == geometryRevision
                && cachedMaterialRevision == materialRevision) {
            return cachedMaterialSnapshot;
        }
        if (dynamicBlas == null && activeMeshInstances.capacity() == 0) {
            cachedMeshMaterials.clear();
            dirtyMeshMaterialSlots.clear();
            legacyMaterialDirty = false;
            cachedMaterialSnapshot = DynamicMaterialSnapshot.empty();
            cachedMaterialTopologyRevision = topologyRevision;
            cachedMaterialGeometryRevision = geometryRevision;
            cachedMaterialRevision = materialRevision;
            return cachedMaterialSnapshot;
        }
        synchronizeCachedMaterialCapacity();
        for (int slot = dirtyMeshMaterialSlots.nextSetBit(0);
             slot >= 0 && slot < activeMeshInstances.capacity();
             slot = dirtyMeshMaterialSlots.nextSetBit(slot + 1)) {
            DynamicRenderScene.DynamicModelInstance primitive = activeMeshInstances.get(slot);
            RtSceneMaterialTable.SectionMaterial previousMaterial = cachedMeshMaterials.get(slot);
            RtSceneMaterialTable.SectionMaterial material =
                    RtSceneMaterialTable.tombstoneSectionMaterial(previousMaterial.faceCount());
            if (primitive != null) {
                DynamicMeshAsset asset = primitive.asset();
                AssetBlasEntry entry = assetBlases.get(asset.id());
                if (entry != null && residentAssetUsableDuringReplacement(asset, entry.asset())) {
                    material = entry.asset().equals(asset)
                            ? RtDynamicTriangleMesh.materialFor(primitive, diagnostics.materials())
                            : previousMaterial;
                }
            }
            cachedMeshMaterials.set(slot, material);
        }
        int[] dirtyMaterialSlots = materialDirtySlotsForSnapshot();
        dirtyMeshMaterialSlots.clear();
        legacyMaterialDirty = false;
        RtSceneMaterialTable.SectionMaterial[] materials = new RtSceneMaterialTable.SectionMaterial[
                Math.addExact(1, activeMeshInstances.capacity())
        ];
        materials[0] = dynamicBlas != null && dynamicMaterial != null
                ? dynamicMaterial
                : RtSceneMaterialTable.tombstoneSectionMaterial();
        for (int slot = 0; slot < cachedMeshMaterials.size(); slot++) {
            materials[slot + 1] = cachedMeshMaterials.get(slot);
        }
        cachedMaterialSnapshot = new DynamicMaterialSnapshot(List.of(materials), dirtyMaterialSlots);
        cachedMaterialTopologyRevision = topologyRevision;
        cachedMaterialGeometryRevision = geometryRevision;
        cachedMaterialRevision = materialRevision;
        return cachedMaterialSnapshot;
    }

    private void synchronizeCachedMaterialCapacity() {
        int capacity = activeMeshInstances.capacity();
        while (cachedMeshMaterials.size() < capacity) {
            cachedMeshMaterials.add(RtSceneMaterialTable.tombstoneSectionMaterial());
            dirtyMeshMaterialSlots.set(cachedMeshMaterials.size() - 1);
        }
        while (cachedMeshMaterials.size() > capacity) {
            cachedMeshMaterials.remove(cachedMeshMaterials.size() - 1);
        }
        int dirtyLength = dirtyMeshMaterialSlots.length();
        if (dirtyLength > capacity) {
            dirtyMeshMaterialSlots.clear(capacity, dirtyLength);
        }
    }

    private void markMaterialSlotsDirtyForAsset(long assetId) {
        for (int slot = 0; slot < activeMeshInstances.capacity(); slot++) {
            DynamicRenderScene.DynamicModelInstance instance = activeMeshInstances.get(slot);
            if (instance != null && instance.asset().id() == assetId) {
                dirtyMeshMaterialSlots.set(slot);
            }
        }
    }

    private void markTlasSlotsDirtyForAsset(long assetId) {
        for (int slot = 0; slot < activeMeshInstances.capacity(); slot++) {
            DynamicRenderScene.DynamicModelInstance instance = activeMeshInstances.get(slot);
            if (instance != null && instance.asset().id() == assetId) {
                dirtyMeshTlasSlots.set(slot);
            }
        }
    }

    private int[] materialDirtySlotsForSnapshot() {
        int[] dirtySlots = new int[dirtyMeshMaterialSlots.cardinality() + (legacyMaterialDirty ? 1 : 0)];
        int cursor = 0;
        if (legacyMaterialDirty) {
            dirtySlots[cursor++] = 0;
        }
        for (int slot = dirtyMeshMaterialSlots.nextSetBit(0);
             slot >= 0;
             slot = dirtyMeshMaterialSlots.nextSetBit(slot + 1)) {
            dirtySlots[cursor++] = Math.addExact(slot, 1);
        }
        return dirtySlots;
    }

    public synchronized RtDynamicInstanceStats snapshotInstanceStats() {
        if (closed) {
            throw new IllegalStateException("RT dynamic BLAS cache is already closed");
        }
        return new RtDynamicInstanceStats(
                revision,
                topologyRevision,
                transformRevision,
                geometryRevision,
                materialRevision,
                latestObservedSceneRevision,
                latestDynamicCausality,
                visibleInstanceCount,
                primitiveCount,
                faceCount,
                triangleCount
        );
    }

    public synchronized long revision() {
        return revision;
    }

    public synchronized long geometryRevision() {
        return geometryRevision;
    }

    public synchronized long transformRevision() {
        return transformRevision;
    }

    public synchronized long materialRevision() {
        return materialRevision;
    }

    public synchronized long topologyRevision() {
        return topologyRevision;
    }

    public synchronized RtDynamicResidencyState snapshotResidencyState() {
        return residencyState(latestInactiveAssetSlots, latestReplacementAssetSlots);
    }

    /**
     * Returns the complete dynamic asset admission projection without exposing mutable cache
     * entries. This is intentionally a diagnostic query: production scheduling continues to use
     * the owner maps directly, while JFR/debug tooling can join queued, pending and active
     * generations by asset id and admission causality.
     */
    public synchronized List<RtDynamicAssetDebugState> snapshotAssetDebugState() {
        List<RtDynamicAssetDebugState> states = new ArrayList<>(
                queuedAssetBuilds.size() + pendingAssetBuilds.size() + assetBlases.size()
        );
        for (QueuedAssetBuild queued : queuedAssetBuilds.entries()) {
            states.add(new RtDynamicAssetDebugState(
                    queued.asset().id(),
                    queued.asset().revision(),
                    RtDynamicAssetDebugState.Phase.QUEUED,
                    latestObservedSceneRevision,
                    queued.causality()
            ));
        }
        for (RtPendingDynamicAssetBlasBuild pending : pendingAssetBuilds.values()) {
            states.add(new RtDynamicAssetDebugState(
                    pending.asset().id(),
                    pending.asset().revision(),
                    RtDynamicAssetDebugState.Phase.PENDING_BLAS,
                    latestObservedSceneRevision,
                    pending.causality()
            ));
        }
        for (AssetBlasEntry active : assetBlases.values()) {
            states.add(new RtDynamicAssetDebugState(
                    active.asset().id(),
                    active.asset().revision(),
                    RtDynamicAssetDebugState.Phase.ACTIVE_BLAS,
                    active.lastSeenSceneRevision,
                    active.causality()
            ));
        }
        return List.copyOf(states);
    }

    /**
     * Joins the persistent primitive table to its asset admission owner for one
     * sourceEngine entity.  This is computed only on an explicit diagnostic query;
     * the hot path retains no parallel entity registry.
     */
    public synchronized RtDynamicEntityDebugState snapshotEntityDebugState(long entityId) {
        if (entityId < 0L || entityId > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException("entity id must be an unsigned 32-bit value");
        }
        LongOpenHashSet assets = new LongOpenHashSet();
        int primitives = 0;
        long newestAssetRevision = -1L;
        for (DynamicRenderScene.DynamicModelInstance instance : activeMeshInstances) {
            if (instance == null || DynamicModelIdentity.entityIdFromPrimitiveId(instance.id()) != entityId) {
                continue;
            }
            primitives++;
            assets.add(instance.asset().id());
            newestAssetRevision = Math.max(newestAssetRevision, instance.asset().revision());
        }
        if (primitives == 0) {
            return RtDynamicEntityDebugState.absent(entityId);
        }
        int queued = 0;
        int pending = 0;
        int resident = 0;
        LongIterator iterator = assets.iterator();
        while (iterator.hasNext()) {
            long assetId = iterator.nextLong();
            if (queuedAssetBuilds.asset(assetId) != null) {
                queued++;
            }
            if (pendingAssetBuilds.containsKey(assetId)) {
                pending++;
            }
            if (assetBlases.containsKey(assetId)) {
                resident++;
            }
        }
        return new RtDynamicEntityDebugState(
                entityId,
                latestObservedSceneRevision,
                primitives,
                assets.size(),
                queued,
                pending,
                resident,
                newestAssetRevision,
                latestDynamicCausality
        );
    }

    private RtDynamicResidencyState residencyState(
            int inactiveAssetSlots,
            int replacementAssetSlots
    ) {
        return new RtDynamicResidencyState(
                pendingAssetBuilds.size() + (pendingBuild == null ? 0 : 1),
                queuedAssetBuilds.size(),
                inactiveAssetSlots,
                replacementAssetSlots
        );
    }

    public synchronized boolean activeSceneHasTlasGeometryContent() {
        return activeScene.hasTlasGeometryContent();
    }

    public synchronized long latestObservedSceneRevision() {
        return latestObservedSceneRevision;
    }

    public synchronized RendererFrameCausality latestCausality() {
        return latestDynamicCausality;
    }

    public synchronized void releaseRetiredBlasesThrough(long protectedRevision) {
        if (closed) {
            throw new IllegalStateException("RT dynamic BLAS cache is already closed");
        }
        closeRetiredBlasesThrough(protectedRevision);
    }

    public synchronized String summary(String name) {
        return name
                + "{hasDynamicBlas=" + (dynamicBlas != null)
                + ", revision=" + revision
                + ", topologyRevision=" + topologyRevision
                + ", transformRevision=" + transformRevision
                + ", geometryRevision=" + geometryRevision
                + ", materialRevision=" + materialRevision
                + ", latestSceneRevision=" + latestObservedSceneRevision
                + ", latestLegacySceneRevision=" + latestObservedLegacySceneRevision
                + ", activeLegacyMeshRevision=" + activeLegacyMeshRevision
                + ", pendingBuild=" + (pendingBuild != null)
                + ", pendingAssetBuild=" + !pendingAssetBuilds.isEmpty()
                + ", pendingAssetBuilds=" + pendingAssetBuilds.size()
                + ", queuedAssetBuilds=" + queuedAssetBuilds.size()
                + ", cachedAssetBlases=" + assetBlases.size()
                + ", activeMeshInstances=" + activeMeshInstances.activeCount()
                + ", physicalMeshSlots=" + activeMeshInstances.capacity()
                + ", submittedBuilds=" + submittedBuilds
                + ", completedBuilds=" + completedBuilds
                + ", buildPollsNotReady=" + buildPollsNotReady
                + ", closeWaits=" + closeWaits
                + ", discardedCompletedBuilds=" + discardedCompletedBuilds
                + ", clears=" + clears
                + ", primitiveCount=" + primitiveCount
                + ", faceCount=" + faceCount
                + ", triangleCount=" + triangleCount
                + ", totalTrianglesBuilt=" + totalTrianglesBuilt
                + ", dynamicBlasBytes=" + dynamicBlasBytes
                + ", retiredDynamicBlases=" + retirementQueue.size()
                + ", retiredDynamicBlasBytes=" + retirementQueue.retainedBytes()
                + ", peakRetiredDynamicBlasBytes=" + retirementQueue.peakRetainedBytes()
                + ", lastBuildLatencyMillis=" + lastBuildLatencyMillis
                + ", maxBuildLatencyMillis=" + maxBuildLatencyMillis
                + "}";
    }

    private void recordSmokeSceneClassification(DynamicInstanceChange change) {
        if (!diagnostics.builds().enabled()) {
            return;
        }
        smokeSceneEvents++;
        if (!change.anyChanged()) {
            smokeUnchangedScenes++;
        } else if (!change.topologyChanged() && change.tlasInstanceChanged() && !change.materialChanged()) {
            smokeTransformOnlyScenes++;
        } else if (!change.topologyChanged() && !change.tlasInstanceChanged() && change.materialChanged()) {
            smokeMaterialOnlyScenes++;
        } else if (!change.topologyChanged() && change.tlasInstanceChanged()) {
            smokeTransformAndMaterialScenes++;
        }
        emitSmokeAggregateIfDue();
    }

    private void emitSmokeAggregateIfDue() {
        long now = System.nanoTime();
        if (smokeWindowStartNanos == 0L) {
            smokeWindowStartNanos = now;
            return;
        }
        if (now - smokeWindowStartNanos < 1_000_000_000L) {
            return;
        }
        diagnostics.builds().aggregate(
                "dynamicBlas",
                "windowMs=" + (now - smokeWindowStartNanos) / 1_000_000L
                        + ", sceneEvents=" + smokeSceneEvents
                        + ", unchanged=" + smokeUnchangedScenes
                        + ", transformOnly=" + smokeTransformOnlyScenes
                        + ", materialOnly=" + smokeMaterialOnlyScenes
                        + ", transformAndMaterial=" + smokeTransformAndMaterialScenes
                        + ", transformDelta={instances=" + smokeTransformChangedInstances
                        + ", translation=" + smokeTransformTranslationChangedInstances
                        + ", linear=" + smokeTransformLinearChangedInstances
                        + ", uniformTranslationScenes=" + smokeTransformUniformTranslationScenes
                        + ", mixedTranslationScenes=" + smokeTransformMixedTranslationScenes + "}"
                        + ", topologyChanges=" + smokeTopologyChanges
                        + ", topologyDelta={sameOrder=" + smokeTopologySameOrder
                        + ", reordered=" + smokeTopologyReordered
                        + ", sizeChanged=" + smokeTopologySizeChanged
                        + ", identityReplaced=" + smokeTopologyIdentityReplaced
                        + ", added=" + smokeTopologyAdded
                        + ", removed=" + smokeTopologyRemoved + "}"
                        + ", assetBuildRequests=" + smokeAssetBuildRequests
                        + ", assetReplacements=" + smokeAssetReplacements
                        + ", buildCompletions=" + smokeBuildCompletions
                        + ", persistentSlots={reuse=" + smokePersistentSlotReuses
                        + ", add=" + smokePersistentSlotAdds
                        + ", remove=" + smokePersistentSlotRemovals
                        + ", transformDirty=" + smokeTransformDirtySlots
                        + ", materialDirty=" + smokeMaterialDirtySlots + "}"
                        + ", activeInstances=" + activeMeshInstances.activeCount()
                        + ", physicalSlots=" + activeMeshInstances.capacity()
                        + ", activeKinds=" + describeKinds(activeMeshInstances)
                        + ", topologyAddedKinds=" + describeKindCounts(smokeTopologyAddedByKind)
                        + ", topologyRemovedKinds=" + describeKindCounts(smokeTopologyRemovedByKind)
                        + ", cachedAssets=" + assetBlases.size()
                        + ", pendingAssets=" + pendingAssetBuilds.size()
                        + ", revision=" + revision
                        + ", topologyRevision=" + topologyRevision
                        + ", transformRevision=" + transformRevision
                        + ", geometryRevision=" + geometryRevision
                        + ", materialRevision=" + materialRevision
        );
        smokeWindowStartNanos = now;
        smokeSceneEvents = 0L;
        smokeUnchangedScenes = 0L;
        smokeTransformOnlyScenes = 0L;
        smokeMaterialOnlyScenes = 0L;
        smokeTransformAndMaterialScenes = 0L;
        smokeTransformChangedInstances = 0L;
        smokeTransformTranslationChangedInstances = 0L;
        smokeTransformLinearChangedInstances = 0L;
        smokeTransformUniformTranslationScenes = 0L;
        smokeTransformMixedTranslationScenes = 0L;
        smokeTopologyChanges = 0L;
        smokeTopologySameOrder = 0L;
        smokeTopologyReordered = 0L;
        smokeTopologySizeChanged = 0L;
        smokeTopologyIdentityReplaced = 0L;
        smokeTopologyAdded = 0L;
        smokeTopologyRemoved = 0L;
        smokeTopologyAddedByKind.clear();
        smokeTopologyRemovedByKind.clear();
        smokeAssetBuildRequests = 0L;
        smokeAssetReplacements = 0L;
        smokeBuildCompletions = 0L;
        smokePersistentSlotReuses = 0L;
        smokePersistentSlotAdds = 0L;
        smokePersistentSlotRemovals = 0L;
        smokeTransformDirtySlots = 0L;
        smokeMaterialDirtySlots = 0L;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        failure = closePendingBuildCollecting(failure);
        failure = closePendingAssetBuildCollecting(failure);
        failure = closeCollecting(failure, dynamicBlas);
        dynamicBlas = null;
        dynamicMaterial = null;
        for (AssetBlasEntry entry : assetBlases.values()) {
            failure = closeCollecting(failure, entry.blas());
        }
        assetBlases.clear();
        queuedAssetBuilds.clear();
        activeMeshInstances.clear();
        activeMeshTransforms.clear();
        activeAssetReferenceCounts.clear();
        cachedMeshMaterials.clear();
        cachedTlasInstances = List.of();
        cachedTlasInstanceSlots.clear();
        cachedTlasInstanceRevision = -1L;
        cachedTlasInstanceDirtySlots = new int[0];
        dirtyMeshMaterialSlots.clear();
        dirtyMeshTlasSlots.clear();
        observedMeshInstanceIds.clear();
        activeScene = DynamicRenderScene.empty();
        failure = closeAllRetiredBlasesCollecting(failure);
        if (failure != null) {
            throw failure;
        }
    }

    private static boolean sameInstanceTopology(
            List<DynamicRenderScene.DynamicModelInstance> previous,
            List<DynamicRenderScene.DynamicModelInstance> current
    ) {
        if (previous.size() != current.size()) {
            return false;
        }
        for (int index = 0; index < previous.size(); index++) {
            DynamicRenderScene.DynamicModelInstance previousPrimitive = previous.get(index);
            DynamicRenderScene.DynamicModelInstance currentPrimitive = current.get(index);
            if (previousPrimitive.id() != currentPrimitive.id()
                    || previousPrimitive.asset().id() != currentPrimitive.asset().id()) {
                return false;
            }
        }
        return true;
    }

    /**
     * The transform is the only mutable per-instance payload consumed by
     * VkAccelerationStructureInstanceKHR. Material fields deliberately do not
     * participate here: they are addressed by the material-table upload lane.
     */
    private static boolean sameInstanceTransforms(
            List<DynamicRenderScene.DynamicModelInstance> previous,
            List<DynamicRenderScene.DynamicModelInstance> current
    ) {
        if (!sameInstanceTopology(previous, current)) {
            return false;
        }
        for (int index = 0; index < previous.size(); index++) {
            if (!previous.get(index).transform().equals(current.get(index).transform())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Separates a shared camera-space delta from genuine model animation. The
     * result is only accumulated while smoke causality logging is enabled.
     */
    private static RtDynamicTransformDelta classifyTransformDelta(
            List<DynamicRenderScene.DynamicModelInstance> previous,
            List<DynamicRenderScene.DynamicModelInstance> current,
            boolean topologyChanged
    ) {
        if (topologyChanged) {
            return RtDynamicTransformDelta.EMPTY;
        }
        int changed = 0;
        int translationChanged = 0;
        int linearChanged = 0;
        boolean haveReferenceTranslation = false;
        boolean uniformTranslation = true;
        float referenceX = 0.0F;
        float referenceY = 0.0F;
        float referenceZ = 0.0F;
        for (int index = 0; index < previous.size(); index++) {
            DynamicMeshInstance.AffineTransform before = previous.get(index).transform();
            DynamicMeshInstance.AffineTransform after = current.get(index).transform();
            if (before.equals(after)) {
                continue;
            }
            changed++;
            float deltaX = after.translateX() - before.translateX();
            float deltaY = after.translateY() - before.translateY();
            float deltaZ = after.translateZ() - before.translateZ();
            boolean translated = deltaX != 0.0F || deltaY != 0.0F || deltaZ != 0.0F;
            if (translated) {
                translationChanged++;
                if (!haveReferenceTranslation) {
                    referenceX = deltaX;
                    referenceY = deltaY;
                    referenceZ = deltaZ;
                    haveReferenceTranslation = true;
                } else if (Float.compare(referenceX, deltaX) != 0
                        || Float.compare(referenceY, deltaY) != 0
                        || Float.compare(referenceZ, deltaZ) != 0) {
                    uniformTranslation = false;
                }
            }
            if (before.m00() != after.m00() || before.m01() != after.m01() || before.m02() != after.m02()
                    || before.m10() != after.m10() || before.m11() != after.m11() || before.m12() != after.m12()
                    || before.m20() != after.m20() || before.m21() != after.m21() || before.m22() != after.m22()) {
                linearChanged++;
            }
        }
        return new RtDynamicTransformDelta(changed, translationChanged, linearChanged,
                translationChanged > 0 && uniformTranslation);
    }

    private void recordSmokeTransformDelta(RtDynamicTransformDelta delta) {
        if (!diagnostics.builds().enabled()) {
            return;
        }
        smokeTransformChangedInstances += delta.changedInstances();
        smokeTransformTranslationChangedInstances += delta.translationChangedInstances();
        smokeTransformLinearChangedInstances += delta.linearChangedInstances();
        if (delta.uniformTranslation()) {
            smokeTransformUniformTranslationScenes++;
        } else if (delta.translationChangedInstances() > 0) {
            smokeTransformMixedTranslationScenes++;
        }
    }

    private void recordSmokePersistentSlotUpdates(RtDynamicSlotUpdateSummary updates) {
        if (!diagnostics.builds().enabled()) {
            return;
        }
        smokePersistentSlotReuses += updates.reusedSlots();
        smokePersistentSlotAdds += updates.addedSlots();
        smokePersistentSlotRemovals += updates.removedSlots();
        smokeTransformDirtySlots += updates.transformDirtySlots();
        smokeMaterialDirtySlots += updates.materialDirtySlots();
    }

    /**
     * This matches the material-table source exactly: complete face state plus
     * host packed light. Primitive metadata that is not read by
     * {@link RtDynamicTriangleMesh#materialFor(DynamicRenderScene.DynamicPrimitive)}
     * cannot create a GPU material upload.
     */
    private static boolean sameInstanceMaterials(
            List<DynamicRenderScene.DynamicModelInstance> previous,
            List<DynamicRenderScene.DynamicModelInstance> current
    ) {
        if (!sameInstanceTopology(previous, current)) {
            return false;
        }
        for (int index = 0; index < previous.size(); index++) {
            DynamicRenderScene.DynamicModelInstance left = previous.get(index);
            DynamicRenderScene.DynamicModelInstance right = current.get(index);
            if (left.packedLight() != right.packedLight()
                    || !left.faceMaterials().equals(right.faceMaterials())) {
                return false;
            }
        }
        return true;
    }

    private void recordSmokeTopologyDelta(
            List<DynamicRenderScene.DynamicModelInstance> previous,
            List<DynamicRenderScene.DynamicModelInstance> current
    ) {
        if (!diagnostics.builds().enabled()) {
            return;
        }
        if (sameInstanceTopology(previous, current)) {
            smokeTopologySameOrder++;
            return;
        }
        Set<InstanceIdentity> previousIdentities = instanceIdentities(previous);
        Set<InstanceIdentity> currentIdentities = instanceIdentities(current);
        if (previousIdentities.equals(currentIdentities)) {
            smokeTopologyReordered++;
            return;
        }
        if (previous.size() != current.size()) {
            smokeTopologySizeChanged++;
        } else {
            smokeTopologyIdentityReplaced++;
        }
        Map<InstanceIdentity, DynamicRenderScene.PrimitiveKind> previousKinds = instanceKinds(previous);
        Map<InstanceIdentity, DynamicRenderScene.PrimitiveKind> currentKinds = instanceKinds(current);
        smokeTopologyAdded += recordKindDifference(currentKinds, previousKinds, smokeTopologyAddedByKind);
        smokeTopologyRemoved += recordKindDifference(previousKinds, currentKinds, smokeTopologyRemovedByKind);
    }

    private static Set<InstanceIdentity> instanceIdentities(
            List<DynamicRenderScene.DynamicModelInstance> primitives
    ) {
        Set<InstanceIdentity> identities = new HashSet<>(primitives.size());
        for (DynamicRenderScene.DynamicModelInstance primitive : primitives) {
            identities.add(new InstanceIdentity(primitive.id(), primitive.asset().id()));
        }
        return identities;
    }

    private static Map<InstanceIdentity, DynamicRenderScene.PrimitiveKind> instanceKinds(
            List<DynamicRenderScene.DynamicModelInstance> primitives
    ) {
        Map<InstanceIdentity, DynamicRenderScene.PrimitiveKind> result = new HashMap<>(primitives.size());
        for (DynamicRenderScene.DynamicModelInstance primitive : primitives) {
            result.put(new InstanceIdentity(primitive.id(), primitive.asset().id()), primitive.kind());
        }
        return result;
    }

    private static int recordKindDifference(
            Map<InstanceIdentity, DynamicRenderScene.PrimitiveKind> left,
            Map<InstanceIdentity, DynamicRenderScene.PrimitiveKind> right,
            EnumMap<DynamicRenderScene.PrimitiveKind, Long> counts
    ) {
        int total = 0;
        for (Map.Entry<InstanceIdentity, DynamicRenderScene.PrimitiveKind> entry : left.entrySet()) {
            if (!right.containsKey(entry.getKey())) {
                counts.merge(entry.getValue(), 1L, Long::sum);
                total++;
            }
        }
        return total;
    }

    private static String describeKinds(Iterable<DynamicRenderScene.DynamicModelInstance> primitives) {
        EnumMap<DynamicRenderScene.PrimitiveKind, Long> counts =
                new EnumMap<>(DynamicRenderScene.PrimitiveKind.class);
        for (DynamicRenderScene.DynamicModelInstance primitive : primitives) {
            if (primitive == null) {
                continue;
            }
            counts.merge(primitive.kind(), 1L, Long::sum);
        }
        return describeKindCounts(counts);
    }

    private static String describeKindCounts(EnumMap<DynamicRenderScene.PrimitiveKind, Long> counts) {
        StringBuilder result = new StringBuilder("{");
        for (DynamicRenderScene.PrimitiveKind kind : DynamicRenderScene.PrimitiveKind.values()) {
            if (result.length() > 1) {
                result.append(',');
            }
            result.append(kind.name()).append('=').append(counts.getOrDefault(kind, 0L));
        }
        return result.append('}').toString();
    }

    private static int differenceCount(Set<?> left, Set<?> right) {
        int count = 0;
        for (Object value : left) {
            if (!right.contains(value)) {
                count++;
            }
        }
        return count;
    }

    private record InstanceIdentity(long primitiveId, long assetId) {
    }

    private record DynamicInstanceChange(
            boolean topologyChanged,
            boolean tlasInstanceChanged,
            boolean materialChanged
    ) {
        boolean tlasChanged() {
            return topologyChanged || tlasInstanceChanged;
        }

        boolean anyChanged() {
            return tlasChanged() || materialChanged;
        }
    }

    private boolean clearLegacyDynamicGeometry(long sceneRevision) {
        boolean hadPendingBuild = pendingBuild != null;
        replacePendingBuild(null);
        if (dynamicBlas != null) {
            retireDynamicBlas(revision + 1L, dynamicBlas);
            dynamicBlas = null;
            dynamicMaterial = null;
            legacyMaterialDirty = true;
            legacyPrimitiveCount = 0L;
            legacyFaceCount = 0L;
            legacyTriangleCount = 0L;
            activeLegacyMeshRevision = sceneRevision;
            revision++;
            geometryRevision++;
            materialRevision++;
            clears++;
            recomputeDynamicCounts();
            recomputeDynamicBlasBytes();
            return true;
        } else {
            activeLegacyMeshRevision = Math.max(activeLegacyMeshRevision, sceneRevision);
            return hadPendingBuild;
        }
    }

    private LongSet activeAssetIds() {
        activeAssetIds.clear();
        activeAssetIds.addAll(activeAssetReferenceCounts.keySet());
        return activeAssetIds;
    }

    static boolean shouldSubmitLegacyBuild(
            long candidateRevision,
            long activeRevision,
            long pendingRevision
    ) {
        if (candidateRevision <= 0L) {
            throw new IllegalArgumentException("candidate legacy mesh revision must be positive");
        }
        if (activeRevision < 0L || pendingRevision < -1L) {
            throw new IllegalArgumentException("legacy mesh revisions are outside their valid range");
        }
        return candidateRevision > Math.max(activeRevision, pendingRevision);
    }

    /** Package-visible contract used by CPU-only tests to lock the ownership split. */
    static boolean advancesTlasRevision(boolean topologyChanged, boolean instanceDescriptorChanged) {
        return topologyChanged || instanceDescriptorChanged;
    }

    /** Material payload is tracked independently and must never imply a TLAS update. */
    static boolean advancesMaterialRevision(boolean materialChanged) {
        return materialChanged;
    }

    /**
     * Keeps the last completed BLAS resident while a newer revision of the
     * same logical asset is building. A different asset ID is a topology
     * replacement and must never borrow unrelated geometry.
     */
    static boolean residentAssetUsableDuringReplacement(
            DynamicMeshAsset desiredAsset,
            DynamicMeshAsset residentAsset
    ) {
        Objects.requireNonNull(desiredAsset, "desiredAsset");
        Objects.requireNonNull(residentAsset, "residentAsset");
        if (desiredAsset.id() != residentAsset.id()) {
            return false;
        }
        if (!desiredAsset.equals(residentAsset)) {
            requireNewerAssetVersion(desiredAsset, residentAsset, "resident BLAS");
        }
        return true;
    }

    private static boolean assetBuildWasSuperseded(
            DynamicMeshAsset completedAsset,
            AssetBlasEntry cached,
            DynamicMeshAsset queuedReplacement
    ) {
        if (cached != null && isSameOrNewerAssetVersion(cached.asset(), completedAsset)) {
            return true;
        }
        return queuedReplacement != null && isNewerAssetVersion(queuedReplacement, completedAsset);
    }

    private static boolean isSameOrNewerAssetVersion(
            DynamicMeshAsset candidate,
            DynamicMeshAsset reference
    ) {
        requireSameAssetId(candidate, reference);
        if (candidate.revision() == reference.revision() && !candidate.equals(reference)) {
            throw new IllegalStateException("dynamic mesh asset id " + candidate.id()
                    + " has divergent geometry at revision " + candidate.revision());
        }
        return candidate.revision() >= reference.revision();
    }

    private static boolean isNewerAssetVersion(
            DynamicMeshAsset candidate,
            DynamicMeshAsset reference
    ) {
        requireSameAssetId(candidate, reference);
        if (candidate.revision() == reference.revision() && !candidate.equals(reference)) {
            throw new IllegalStateException("dynamic mesh asset id " + candidate.id()
                    + " has divergent geometry at revision " + candidate.revision());
        }
        return candidate.revision() > reference.revision();
    }

    private static void requireNewerAssetVersion(
            DynamicMeshAsset candidate,
            DynamicMeshAsset known,
            String knownOwner
    ) {
        requireSameAssetId(candidate, known);
        if (candidate.equals(known)) {
            return;
        }
        if (candidate.revision() <= known.revision()) {
            throw new IllegalArgumentException("dynamic mesh asset id " + candidate.id()
                    + " must advance beyond " + knownOwner + " revision " + known.revision()
                    + ", candidate=" + candidate.revision());
        }
    }

    private static void requireSameAssetId(DynamicMeshAsset left, DynamicMeshAsset right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        if (left.id() != right.id()) {
            throw new IllegalArgumentException("dynamic mesh asset version comparison requires matching ids");
        }
    }

    private void replacePendingBuild(RtPendingDynamicBlasBuild replacement) {
        RtPendingDynamicBlasBuild previous = pendingBuild;
        pendingBuild = replacement;
        if (previous == null) {
            return;
        }
        closeWaits++;
        previous.close();
    }

    private void retireDynamicBlas(long safeAfterRevision, RtAccelerationStructure blas) {
        retirementQueue.retire(safeAfterRevision, blas);
    }

    private void closeRetiredBlasesThrough(long protectedRevision) {
        retirementQueue.releaseThrough(protectedRevision);
    }

    private RuntimeException closePendingBuildCollecting(RuntimeException failure) {
        RtPendingDynamicBlasBuild pending = pendingBuild;
        pendingBuild = null;
        if (pending == null) {
            return failure;
        }
        closeWaits++;
        try {
            pending.close();
            return failure;
        } catch (RuntimeException ex) {
            if (failure == null) {
                return ex;
            }
            failure.addSuppressed(ex);
            return failure;
        }
    }

    private RuntimeException closePendingAssetBuildCollecting(RuntimeException failure) {
        for (RtPendingDynamicAssetBlasBuild pending : pendingAssetBuilds.values()) {
            closeWaits++;
            try {
                pending.close();
            } catch (RuntimeException ex) {
                if (failure == null) {
                    failure = ex;
                } else {
                    failure.addSuppressed(ex);
                }
            }
        }
        pendingAssetBuilds.clear();
        return failure;
    }

    private RuntimeException closeAllRetiredBlasesCollecting(RuntimeException failure) {
        return retirementQueue.closeAllCollecting(failure);
    }

    private static RuntimeException closeCollecting(RuntimeException failure, AutoCloseable closeable) {
        if (closeable == null) {
            return failure;
        }
        try {
            closeable.close();
            return failure;
        } catch (Exception ex) {
            RuntimeException wrapped = ex instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException("failed to close dynamic BLAS resource", ex);
            if (failure == null) {
                return wrapped;
            }
            failure.addSuppressed(wrapped);
            return failure;
        }
    }

    /** Immutable material lane paired with the ordered ready-asset instance lane. */
    private record DynamicMaterialSnapshot(
            List<RtSceneMaterialTable.SectionMaterial> materials,
            int[] dirtySlots
    ) {
        private static final DynamicMaterialSnapshot EMPTY = new DynamicMaterialSnapshot(List.of(), new int[0]);

        private DynamicMaterialSnapshot {
            materials = List.copyOf(materials);
            dirtySlots = java.util.Arrays.copyOf(dirtySlots, dirtySlots.length);
        }

        @Override
        public int[] dirtySlots() {
            return java.util.Arrays.copyOf(dirtySlots, dirtySlots.length);
        }

        private static DynamicMaterialSnapshot empty() {
            return EMPTY;
        }
    }

    static final class AssetBuildQueue {
        private final Long2ObjectLinkedOpenHashMap<QueuedAssetBuild> assets = new Long2ObjectLinkedOpenHashMap<>();

        void offer(
                DynamicMeshAsset candidate,
                RendererFrameCausality causality,
                DynamicMeshAsset pending,
                DynamicMeshAsset cached
        ) {
            Objects.requireNonNull(candidate, "candidate");
            Objects.requireNonNull(causality, "causality");
            QueuedAssetBuild queuedEntry = assets.get(candidate.id());
            DynamicMeshAsset queued = queuedEntry == null ? null : queuedEntry.asset();
            DynamicMeshAsset newestKnown = newestVersionForId(candidate.id(), queued, pending, cached);
            if (newestKnown != null) {
                if (candidate.equals(newestKnown)) {
                    return;
                }
                requireNewerAssetVersion(candidate, newestKnown, "known asset");
            }
            assets.put(candidate.id(), new QueuedAssetBuild(candidate, causality));
        }

        void offer(
                DynamicMeshAsset candidate,
                DynamicMeshAsset pending,
                DynamicMeshAsset cached
        ) {
            offer(candidate, RendererFrameCausality.untraced(0L), pending, cached);
        }

        DynamicMeshAsset poll() {
            return pollEntry().asset();
        }

        QueuedAssetBuild pollEntry() {
            if (assets.isEmpty()) {
                throw new IllegalStateException("dynamic mesh asset build queue is empty");
            }
            return assets.removeFirst();
        }

        DynamicMeshAsset asset(long id) {
            QueuedAssetBuild queued = assets.get(id);
            return queued == null ? null : queued.asset();
        }

        RendererFrameCausality causality(long id) {
            QueuedAssetBuild queued = assets.get(id);
            return queued == null ? null : queued.causality();
        }

        void retainAssetIds(LongSet activeAssetIds) {
            Objects.requireNonNull(activeAssetIds, "activeAssetIds");
            LongIterator iterator = assets.keySet().iterator();
            while (iterator.hasNext()) {
                if (!activeAssetIds.contains(iterator.nextLong())) {
                    iterator.remove();
                }
            }
        }

        boolean isEmpty() {
            return assets.isEmpty();
        }

        int size() {
            return assets.size();
        }

        List<QueuedAssetBuild> entries() {
            return List.copyOf(assets.values());
        }

        void clear() {
            assets.clear();
        }

        private static DynamicMeshAsset newestVersionForId(
                long id,
                DynamicMeshAsset first,
                DynamicMeshAsset second,
                DynamicMeshAsset third
        ) {
            DynamicMeshAsset newest = null;
            newest = newerVersionForId(id, newest, first);
            newest = newerVersionForId(id, newest, second);
            return newerVersionForId(id, newest, third);
        }

        private static DynamicMeshAsset newerVersionForId(
                long id,
                DynamicMeshAsset current,
                DynamicMeshAsset candidate
        ) {
            if (candidate == null || candidate.id() != id) {
                return current;
            }
            if (current == null) {
                return candidate;
            }
            if (candidate.revision() == current.revision()) {
                if (!candidate.equals(current)) {
                    throw new IllegalStateException("dynamic mesh asset id " + id
                            + " has divergent geometry at revision " + candidate.revision());
                }
                return current;
            }
            return candidate.revision() > current.revision() ? candidate : current;
        }
    }

    private record QueuedAssetBuild(
            DynamicMeshAsset asset,
            RendererFrameCausality causality
    ) {
        private QueuedAssetBuild {
            asset = Objects.requireNonNull(asset, "asset");
            causality = Objects.requireNonNull(causality, "causality");
        }
    }

    /**
     * CPU-side ownership table for dynamic TLAS custom-index slots.
     *
     * <p>Slots are physical GPUScene-style addresses, not frame traversal
     * order. Removal creates an inactive hole; a later owner may reuse that
     * hole, but no live owner is moved. Only trailing vacancies are trimmed,
     * which preserves every surviving custom index and bounds long-lived
     * empty-table growth.</p>
     */
    static final class PersistentSlots<T> implements Iterable<T> {
        private final ArrayList<T> slots = new ArrayList<>();
        private final BitSet allocatedSlots = new BitSet();
        private final Long2IntOpenHashMap slotsById = new Long2IntOpenHashMap();
        private final IntArrayList reusableSlots = new IntArrayList();

        PersistentSlots() {
            slotsById.defaultReturnValue(-1);
        }

        int slotFor(long id) {
            return slotsById.get(id);
        }

        int allocate(long id) {
            if (slotsById.containsKey(id)) {
                throw new IllegalStateException("persistent dynamic slot already exists for id: " + id);
            }
            int slot;
            if (!reusableSlots.isEmpty()) {
                int reused = reusableSlots.removeInt(reusableSlots.size() - 1);
                if (reused < 0 || reused >= slots.size() || slots.get(reused) != null) {
                    throw new IllegalStateException("persistent dynamic free-slot table is corrupt: " + reused);
                }
                slot = reused;
            } else {
                slot = slots.size();
                slots.add(null);
            }
            slotsById.put(id, slot);
            allocatedSlots.set(slot);
            return slot;
        }

        void ensureCapacity(int capacity) {
            if (capacity < 0) {
                throw new IllegalArgumentException("persistent dynamic slot capacity must not be negative");
            }
            while (slots.size() < capacity) {
                int vacant = slots.size();
                slots.add(null);
                reusableSlots.add(vacant);
            }
        }

        void allocateAt(int slot, long id) {
            if (slot < 0) {
                throw new IllegalArgumentException("invalid exact persistent dynamic slot allocation");
            }
            int existingSlot = slotsById.get(id);
            if (existingSlot >= 0) {
                if (existingSlot == slot && allocatedSlots.get(slot) && slots.get(slot) != null) {
                    return;
                }
                throw new IllegalStateException("persistent dynamic identity requires slot rebase: id="
                        + id + ", current=" + existingSlot + ", requested=" + slot);
            }
            while (slots.size() <= slot) {
                int vacant = slots.size();
                slots.add(null);
                reusableSlots.add(vacant);
            }
            if (slots.get(slot) != null || allocatedSlots.get(slot) || !removeReusableSlot(slot)) {
                throw new IllegalStateException("exact persistent dynamic slot is not vacant: " + slot);
            }
            slotsById.put(id, slot);
            allocatedSlots.set(slot);
        }

        void set(int slot, T value) {
            Objects.requireNonNull(value, "value");
            if (slot < 0 || slot >= slots.size() || !allocatedSlots.get(slot)) {
                throw new IllegalArgumentException("persistent dynamic slot is not allocated: " + slot);
            }
            slots.set(slot, value);
        }

        T get(int slot) {
            if (slot < 0 || slot >= slots.size()) {
                throw new IndexOutOfBoundsException("persistent dynamic slot outside capacity: " + slot);
            }
            return slots.get(slot);
        }

        void remove(long id) {
            int slot = slotsById.remove(id);
            if (slot < 0) {
                throw new IllegalStateException("persistent dynamic slot does not exist for id: " + id);
            }
            if (slots.get(slot) == null) {
                throw new IllegalStateException("persistent dynamic slot is already vacant: " + slot);
            }
            slots.set(slot, null);
            allocatedSlots.clear(slot);
            reusableSlots.add(slot);
        }

        void removeAt(int slot, long id) {
            if (slot < 0 || slot >= slots.size() || slotsById.get(id) != slot) {
                throw new IllegalStateException("persistent dynamic slot identity mismatch: " + slot);
            }
            remove(id);
        }

        void trimTrailingVacancies() {
            while (!slots.isEmpty() && slots.get(slots.size() - 1) == null) {
                int slot = slots.size() - 1;
                slots.remove(slot);
                if (!removeReusableSlot(slot)) {
                    throw new IllegalStateException("persistent dynamic free-slot table missed trailing vacancy: " + slot);
                }
            }
        }

        int activeCount() {
            return slotsById.size();
        }

        int capacity() {
            return slots.size();
        }

        void clear() {
            slots.clear();
            allocatedSlots.clear();
            slotsById.clear();
            reusableSlots.clear();
        }

        private boolean removeReusableSlot(int slot) {
            for (int index = reusableSlots.size() - 1; index >= 0; index--) {
                if (reusableSlots.getInt(index) == slot) {
                    reusableSlots.removeInt(index);
                    return true;
                }
            }
            return false;
        }

        @Override
        public Iterator<T> iterator() {
            return slots.iterator();
        }
    }

    private static final class AssetBlasEntry {
        private final DynamicMeshAsset asset;
        private final RtAccelerationStructure blas;
        private long lastSeenSceneRevision;
        private final RendererFrameCausality causality;

        private AssetBlasEntry(
                DynamicMeshAsset asset,
                RtAccelerationStructure blas,
                long lastSeenSceneRevision,
                RendererFrameCausality causality
        ) {
            this.asset = Objects.requireNonNull(asset, "asset");
            this.blas = Objects.requireNonNull(blas, "blas");
            if (lastSeenSceneRevision < 0L) {
                throw new IllegalArgumentException("asset last-seen revision must not be negative");
            }
            this.lastSeenSceneRevision = lastSeenSceneRevision;
            this.causality = Objects.requireNonNull(causality, "causality");
        }

        private DynamicMeshAsset asset() {
            return asset;
        }

        private RtAccelerationStructure blas() {
            return blas;
        }

        private RendererFrameCausality causality() {
            return causality;
        }
    }

}
