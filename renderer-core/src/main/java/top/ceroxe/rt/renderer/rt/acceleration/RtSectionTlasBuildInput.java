package top.ceroxe.rt.renderer.rt.acceleration;

import top.ceroxe.rt.renderer.RendererFrameCausality;
import top.ceroxe.rt.renderer.RendererViewState;
import top.ceroxe.rt.renderer.SectionCausalitySnapshot;
import top.ceroxe.rt.renderer.SectionRevisionSnapshot;
import top.ceroxe.rt.renderer.rt.material.RtSceneMaterialTable;
import top.ceroxe.rt.renderer.scene.PackedSectionMembership;
import top.ceroxe.rt.renderer.scene.SectionKey;

import java.util.List;
import java.util.Objects;

/**
 * Immutable section-owned input publication consumed by the world TLAS builder.
 *
 * @param revision                input publication revision
 * @param resourceRevision        resident BLAS resource revision
 * @param materialRevision        material snapshot revision
 * @param viewState               immutable renderer view state
 * @param sectionMembership       packed visible-section membership
 * @param sectionContentRevisions immutable section content revisions
 * @param sectionCausalities      immutable section causality identities
 * @param instances               immutable TLAS instances
 * @param materialSnapshot        immutable material table snapshot
 * @param pendingSectionBuilds    pending section build count
 * @param pendingTriangles        pending triangle count
 * @param cachedTriangles         cached triangle count
 * @param baseInstances           exact-section instance count
 * @param farFieldInstances       far-field proxy instance count
 * @param uncoveredSections       visible sections lacking coverage
 */
public record RtSectionTlasBuildInput(
        long revision,
        long resourceRevision,
        long materialRevision,
        RendererViewState viewState,
        PackedSectionMembership sectionMembership,
        SectionRevisionSnapshot sectionContentRevisions,
        SectionCausalitySnapshot sectionCausalities,
        List<RtAccelerationStructure.TlasInstance> instances,
        RtSceneMaterialTable.Snapshot materialSnapshot,
        int pendingSectionBuilds,
        long pendingTriangles,
        long cachedTriangles,
        int baseInstances,
        int farFieldInstances,
        int uncoveredSections
) {
    /**
     * Creates a legacy all-resident input while deriving material and zero-causality metadata.
     *
     * @param revision             publication revision
     * @param resourceRevision     BLAS resource revision
     * @param sectionKeys          ordered membership
     * @param instances            TLAS instances
     * @param materialSnapshot     materials
     * @param pendingSectionBuilds pending builds
     * @param pendingTriangles     pending triangles
     * @param cachedTriangles      cached triangles
     * @param baseInstances        exact instance count
     * @param farFieldInstances    proxy instance count
     * @param uncoveredSections    uncovered count
     */
    public RtSectionTlasBuildInput(
            long revision,
            long resourceRevision,
            List<SectionKey> sectionKeys,
            List<RtAccelerationStructure.TlasInstance> instances,
            RtSceneMaterialTable.Snapshot materialSnapshot,
            int pendingSectionBuilds,
            long pendingTriangles,
            long cachedTriangles,
            int baseInstances,
            int farFieldInstances,
            int uncoveredSections
    ) {
        this(
                revision,
                resourceRevision,
                materialSnapshot.revision(),
                RendererViewState.allResident(),
                PackedSectionMembership.canonicalDistinct(sectionKeys),
                zeroRevisions(sectionKeys),
                SectionCausalitySnapshot.constant(
                        SectionRevisionSnapshot.constant(sectionKeys, 0L),
                        RendererFrameCausality.untraced(0L)
                ),
                instances,
                materialSnapshot,
                pendingSectionBuilds,
                pendingTriangles,
                cachedTriangles,
                baseInstances,
                farFieldInstances,
                uncoveredSections
        );
    }

    /**
     * Creates a legacy exact-section-only input with no far-field coverage metadata.
     *
     * @param revision             publication revision
     * @param resourceRevision     BLAS resource revision
     * @param sectionKeys          ordered membership
     * @param instances            TLAS instances
     * @param materialSnapshot     materials
     * @param pendingSectionBuilds pending builds
     * @param pendingTriangles     pending triangles
     * @param cachedTriangles      cached triangles
     */
    public RtSectionTlasBuildInput(
            long revision,
            long resourceRevision,
            List<SectionKey> sectionKeys,
            List<RtAccelerationStructure.TlasInstance> instances,
            RtSceneMaterialTable.Snapshot materialSnapshot,
            int pendingSectionBuilds,
            long pendingTriangles,
            long cachedTriangles
    ) {
        this(
                revision,
                resourceRevision,
                materialSnapshot.revision(),
                RendererViewState.allResident(),
                PackedSectionMembership.canonicalDistinct(sectionKeys),
                zeroRevisions(sectionKeys),
                SectionCausalitySnapshot.constant(
                        SectionRevisionSnapshot.constant(sectionKeys, 0L),
                        RendererFrameCausality.untraced(0L)
                ),
                instances,
                materialSnapshot,
                pendingSectionBuilds,
                pendingTriangles,
                cachedTriangles,
                Objects.requireNonNull(instances, "instances").size(),
                0,
                0
        );
    }

    /**
     * Freezes the publication and validates exact membership, material, and instance cardinality.
     */
    public RtSectionTlasBuildInput {
        viewState = Objects.requireNonNull(viewState, "viewState");
        sectionMembership = Objects.requireNonNull(sectionMembership, "sectionMembership");
        if (!sectionMembership.canonicalOrder()) {
            throw new IllegalArgumentException("section coverage membership must be canonical");
        }
        sectionContentRevisions = Objects.requireNonNull(sectionContentRevisions, "sectionContentRevisions");
        sectionCausalities = Objects.requireNonNull(sectionCausalities, "sectionCausalities");
        instances = List.copyOf(instances);
        materialSnapshot = Objects.requireNonNull(materialSnapshot, "materialSnapshot");
        if (revision < 0L || resourceRevision < 0L || materialRevision < 0L) {
            throw new IllegalArgumentException("scene and resource revisions must not be negative");
        }
        if (sectionContentRevisions.size() != sectionMembership.size()) {
            throw new IllegalArgumentException("section content revision count must match section keys");
        }
        if (sectionCausalities.membership() != sectionContentRevisions.membership()
                && !sectionCausalities.membership().equals(sectionContentRevisions.membership())) {
            throw new IllegalArgumentException("section causalities must exactly cover section revisions");
        }
        sectionCausalities = sectionCausalities.membership() == sectionContentRevisions.membership()
                ? sectionCausalities
                : sectionCausalities.rebase(sectionContentRevisions);
        if (sectionContentRevisions.membership() != sectionMembership
                && !sectionContentRevisions.membership().equals(sectionMembership)) {
            throw new IllegalArgumentException("section content revisions must exactly cover section keys");
        }
        sectionMembership = sectionContentRevisions.membership();
        if (!instances.isEmpty() && materialSnapshot.sectionCount() == 0) {
            throw new IllegalArgumentException("TLAS instances require at least one material slot");
        }
        for (RtAccelerationStructure.TlasInstance instance : instances) {
            if (instance.customIndex() >= materialSnapshot.sectionCount()) {
                throw new IllegalArgumentException("TLAS instance custom index exceeds material slot count");
            }
        }
        if (pendingSectionBuilds < 0) {
            throw new IllegalArgumentException("pendingSectionBuilds must not be negative");
        }
        if (pendingTriangles < 0L) {
            throw new IllegalArgumentException("pendingTriangles must not be negative");
        }
        if (cachedTriangles < 0L) {
            throw new IllegalArgumentException("cachedTriangles must not be negative");
        }
        if (baseInstances < 0 || farFieldInstances < 0 || uncoveredSections < 0) {
            throw new IllegalArgumentException("Base/FarField coverage counts must not be negative");
        }
        if ((long) baseInstances + farFieldInstances != instances.size()) {
            throw new IllegalArgumentException("Base/FarField instance counts must cover the TLAS instances");
        }
    }

    private static SectionRevisionSnapshot zeroRevisions(List<SectionKey> keys) {
        return SectionRevisionSnapshot.constant(keys, 0L);
    }

    /**
     * Returns the canonical ordered section membership.
     *
     * @return immutable ordered section keys
     */
    public List<SectionKey> sectionKeys() {
        return sectionMembership.orderedKeys();
    }

    /**
     * Reports whether the input was published before all section builds completed.
     *
     * @return pending status
     */
    public boolean hasPendingSectionBuilds() {
        return pendingSectionBuilds > 0;
    }
}
