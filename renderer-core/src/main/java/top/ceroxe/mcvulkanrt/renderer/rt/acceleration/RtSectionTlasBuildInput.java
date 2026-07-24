package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import top.ceroxe.mcvulkanrt.renderer.RendererFrameCausality;
import top.ceroxe.mcvulkanrt.renderer.RendererViewState;
import top.ceroxe.mcvulkanrt.renderer.SectionCausalitySnapshot;
import top.ceroxe.mcvulkanrt.renderer.SectionRevisionSnapshot;
import top.ceroxe.mcvulkanrt.renderer.rt.material.RtSceneMaterialTable;
import top.ceroxe.mcvulkanrt.renderer.scene.PackedSectionMembership;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;

import java.util.List;
import java.util.Objects;

/** Immutable section-owned input publication consumed by the world TLAS builder. */
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

    public List<SectionKey> sectionKeys() {
        return sectionMembership.orderedKeys();
    }

    public boolean hasPendingSectionBuilds() {
        return pendingSectionBuilds > 0;
    }

    private static SectionRevisionSnapshot zeroRevisions(List<SectionKey> keys) {
        return SectionRevisionSnapshot.constant(keys, 0L);
    }
}
