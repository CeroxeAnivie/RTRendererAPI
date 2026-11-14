package top.ceroxe.rt.renderer.rt.acceleration;

import top.ceroxe.rt.renderer.DynamicRenderScene;
import top.ceroxe.rt.renderer.RendererViewState;
import top.ceroxe.rt.renderer.SectionCausalitySnapshot;
import top.ceroxe.rt.renderer.SectionRevisionSnapshot;
import top.ceroxe.rt.renderer.rt.material.RtSceneMaterialTable;
import top.ceroxe.rt.renderer.scene.PackedSectionMembership;
import top.ceroxe.rt.renderer.scene.SectionKey;

import java.util.List;
import java.util.Objects;

/**
 * Immutable renderer-owned snapshot used to submit one terrain world TLAS.
 *
 * <p>This is deliberately distinct from the later pending transaction: this
 * type proves the source scene is internally consistent before any Vulkan work
 * is allocated, while the transaction owns the asynchronous native lifetime.</p>
 */
final class RtWorldTlasBuildInput {
    private final long revision;
    private final long sectionRevision;
    private final long sectionResourceRevision;
    private final long sectionMaterialRevision;
    private final RendererViewState viewState;
    private final long dynamicRevision;
    private final long dynamicTopologyRevision;
    private final long dynamicGeometryRevision;
    private final long dynamicMaterialRevision;
    private final DynamicRenderScene dynamicScene;
    private final int instanceTopologyHash;
    private final PackedSectionMembership sectionMembership;
    private final SectionRevisionSnapshot sectionContentRevisions;
    private final SectionCausalitySnapshot sectionCausalities;
    private final List<RtAccelerationStructure.TlasInstance> instances;
    private final int activeInstanceCount;
    private final int sectionInstanceCount;
    private final int dynamicInstanceCount;
    private final int terrainMaterialCount;
    private final RtSceneMaterialTable.Snapshot materialSnapshot;
    private final long coverageContractionAuthorizationGeneration;
    private final int pendingSectionBuilds;
    private final long pendingTriangles;
    private final long cachedTriangles;

    RtWorldTlasBuildInput(
            long revision,
            long sectionRevision,
            long sectionResourceRevision,
            long sectionMaterialRevision,
            RendererViewState viewState,
            long dynamicRevision,
            long dynamicTopologyRevision,
            long dynamicGeometryRevision,
            long dynamicMaterialRevision,
            DynamicRenderScene dynamicScene,
            int instanceTopologyHash,
            PackedSectionMembership sectionMembership,
            SectionRevisionSnapshot sectionContentRevisions,
            SectionCausalitySnapshot sectionCausalities,
            List<RtAccelerationStructure.TlasInstance> instances,
            int activeInstanceCount,
            int sectionInstanceCount,
            int dynamicInstanceCount,
            int terrainMaterialCount,
            RtSceneMaterialTable.Snapshot materialSnapshot,
            long coverageContractionAuthorizationGeneration,
            int pendingSectionBuilds,
            long pendingTriangles,
            long cachedTriangles
    ) {
        this.instances = instances instanceof RtImmutableTlasInstances ? instances : List.copyOf(instances);
        this.sectionMembership = Objects.requireNonNull(sectionMembership, "sectionMembership");
        if (!sectionMembership.canonicalOrder()) {
            throw new IllegalArgumentException("scene TLAS section membership must be canonical");
        }
        this.sectionContentRevisions = Objects.requireNonNull(sectionContentRevisions, "sectionContentRevisions");
        this.sectionCausalities = Objects.requireNonNull(sectionCausalities, "sectionCausalities");
        if (sectionContentRevisions.size() != sectionMembership.size()) {
            throw new IllegalArgumentException("scene TLAS section revision count must match section keys");
        }
        if (sectionContentRevisions.membership() != sectionMembership) {
            throw new IllegalArgumentException("scene TLAS section revisions must retain the exact section membership publication");
        }
        if (sectionCausalities.membership() != sectionMembership) {
            throw new IllegalArgumentException("scene TLAS section causalities must retain the exact section membership publication");
        }
        this.materialSnapshot = Objects.requireNonNull(materialSnapshot, "materialSnapshot");
        this.dynamicScene = Objects.requireNonNull(dynamicScene, "dynamicScene");
        this.viewState = Objects.requireNonNull(viewState, "viewState");
        if (revision < 0L || sectionRevision < 0L || sectionResourceRevision < 0L || sectionMaterialRevision < 0L
                || dynamicRevision < 0L || dynamicTopologyRevision < 0L || dynamicGeometryRevision < 0L
                || dynamicMaterialRevision < 0L) {
            throw new IllegalArgumentException("scene TLAS input revisions must not be negative");
        }
        if (coverageContractionAuthorizationGeneration < 0L) {
            throw new IllegalArgumentException("coverage contraction authorization generation must not be negative");
        }
        if (!this.instances.isEmpty() && materialSnapshot.sectionCount() == 0) {
            throw new IllegalArgumentException("scene TLAS input instances require material slots");
        }
        if (activeInstanceCount < 0 || sectionInstanceCount < 0 || dynamicInstanceCount < 0
                || (long) sectionInstanceCount + dynamicInstanceCount != activeInstanceCount) {
            throw new IllegalArgumentException("scene TLAS active instance count must equal section plus dynamic instances");
        }
        if (terrainMaterialCount < sectionInstanceCount
                || terrainMaterialCount > RtDynamicBlasCache.DYNAMIC_MATERIAL_INDEX_BIT
                || terrainMaterialCount > materialSnapshot.sectionCount()) {
            throw new IllegalArgumentException("scene TLAS terrain material count does not cover terrain instances");
        }
        if (activeInstanceCount > this.instances.size()) {
            throw new IllegalArgumentException("scene TLAS active instances must fit in physical capacity");
        }
        for (RtAccelerationStructure.TlasInstance instance : this.instances) {
            if (instance.visibilityMask() != 0 && instance.customIndex() >= materialSnapshot.sectionCount()) {
                throw new IllegalArgumentException("scene TLAS instance custom index exceeds material slots");
            }
        }
        if (pendingSectionBuilds < 0 || pendingTriangles < 0L || cachedTriangles < 0L) {
            throw new IllegalArgumentException("scene TLAS input pending counts must not be negative");
        }
        this.revision = revision;
        this.sectionRevision = sectionRevision;
        this.sectionResourceRevision = sectionResourceRevision;
        this.sectionMaterialRevision = sectionMaterialRevision;
        this.dynamicRevision = dynamicRevision;
        this.dynamicTopologyRevision = dynamicTopologyRevision;
        this.dynamicGeometryRevision = dynamicGeometryRevision;
        this.dynamicMaterialRevision = dynamicMaterialRevision;
        this.instanceTopologyHash = instanceTopologyHash;
        this.activeInstanceCount = activeInstanceCount;
        this.sectionInstanceCount = sectionInstanceCount;
        this.dynamicInstanceCount = dynamicInstanceCount;
        this.terrainMaterialCount = terrainMaterialCount;
        this.coverageContractionAuthorizationGeneration = coverageContractionAuthorizationGeneration;
        this.pendingSectionBuilds = pendingSectionBuilds;
        this.pendingTriangles = pendingTriangles;
        this.cachedTriangles = cachedTriangles;
    }

    long revision() {
        return revision;
    }

    long sectionRevision() {
        return sectionRevision;
    }

    long sectionResourceRevision() {
        return sectionResourceRevision;
    }

    long sectionMaterialRevision() {
        return sectionMaterialRevision;
    }

    RendererViewState viewState() {
        return viewState;
    }

    long dynamicRevision() {
        return dynamicRevision;
    }

    long dynamicTopologyRevision() {
        return dynamicTopologyRevision;
    }

    long dynamicGeometryRevision() {
        return dynamicGeometryRevision;
    }

    long dynamicMaterialRevision() {
        return dynamicMaterialRevision;
    }

    DynamicRenderScene dynamicScene() {
        return dynamicScene;
    }

    int instanceTopologyHash() {
        return instanceTopologyHash;
    }

    PackedSectionMembership sectionMembership() {
        return sectionMembership;
    }

    List<SectionKey> sectionKeys() {
        return sectionMembership.orderedKeys();
    }

    SectionRevisionSnapshot sectionContentRevisions() {
        return sectionContentRevisions;
    }

    SectionCausalitySnapshot sectionCausalities() {
        return sectionCausalities;
    }

    List<RtAccelerationStructure.TlasInstance> instances() {
        return instances;
    }

    int activeInstanceCount() {
        return activeInstanceCount;
    }

    int sectionInstanceCount() {
        return sectionInstanceCount;
    }

    int dynamicInstanceCount() {
        return dynamicInstanceCount;
    }

    int terrainMaterialCount() {
        return terrainMaterialCount;
    }

    RtSceneMaterialTable.Snapshot materialSnapshot() {
        return materialSnapshot;
    }

    long coverageContractionAuthorizationGeneration() {
        return coverageContractionAuthorizationGeneration;
    }

    int pendingSectionBuilds() {
        return pendingSectionBuilds;
    }

    long pendingTriangles() {
        return pendingTriangles;
    }

    long cachedTriangles() {
        return cachedTriangles;
    }

    boolean hasPendingSectionBuilds() {
        return pendingSectionBuilds > 0;
    }
}
