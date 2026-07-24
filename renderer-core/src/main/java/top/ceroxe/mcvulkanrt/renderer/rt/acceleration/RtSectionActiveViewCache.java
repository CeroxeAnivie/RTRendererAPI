package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import top.ceroxe.mcvulkanrt.renderer.RendererViewState;

import java.util.Objects;

/** Owns the immutable active-view publication and every scalar key which validates it. */
final class RtSectionActiveViewCache {
    private RtSectionActiveViewAssembler.Snapshot snapshot = RtSectionActiveViewAssembler.Snapshot.empty();
    private long geometryRevision = -1L;
    private long materialRevision = -1L;
    private long residentMembershipRevision = -1L;
    private long activeMembershipRevision = -1L;
    private long sourceGeometryRevision = -1L;
    private long sourceMaterialRevision = -1L;
    private boolean admissionRequiresView;
    private RendererViewState admissionView = RendererViewState.allResident();

    Refresh refresh(
            long currentGeometryRevision,
            long currentMaterialRevision,
            long currentResidentMembershipRevision,
            long currentActiveMembershipRevision,
            long currentSourceGeometryRevision,
            long currentSourceMaterialRevision,
            boolean currentAdmissionRequiresView,
            RendererViewState currentView
    ) {
        boolean admissionChanged = admissionInputsChanged(
                currentResidentMembershipRevision,
                currentActiveMembershipRevision,
                currentSourceGeometryRevision,
                currentAdmissionRequiresView,
                currentView
        );
        if (geometryRevision != currentGeometryRevision || admissionChanged) {
            return Refresh.TOPOLOGY;
        }
        return materialRevision == currentMaterialRevision
                && sourceMaterialRevision == currentSourceMaterialRevision
                ? Refresh.HIT
                : Refresh.MATERIAL_ONLY;
    }

    boolean geometryChanged(
            long currentGeometryRevision,
            long currentResidentMembershipRevision,
            long currentActiveMembershipRevision,
            long currentSourceGeometryRevision
    ) {
        return geometryRevision != currentGeometryRevision
                || topologyInputsChanged(
                        residentMembershipRevision,
                        currentResidentMembershipRevision,
                        activeMembershipRevision,
                        currentActiveMembershipRevision,
                        sourceGeometryRevision,
                        currentSourceGeometryRevision
                );
    }

    boolean materialChanged(long currentMaterialRevision, long currentSourceMaterialRevision) {
        return materialRevision != currentMaterialRevision
                || sourceMaterialRevision != currentSourceMaterialRevision;
    }

    boolean admissionInputsChanged(
            long currentResidentMembershipRevision,
            long currentActiveMembershipRevision,
            long currentSourceGeometryRevision,
            boolean currentAdmissionRequiresView,
            RendererViewState currentView
    ) {
        Objects.requireNonNull(currentView, "currentView");
        return topologyInputsChanged(
                residentMembershipRevision,
                currentResidentMembershipRevision,
                activeMembershipRevision,
                currentActiveMembershipRevision,
                sourceGeometryRevision,
                currentSourceGeometryRevision
        )
                || (currentAdmissionRequiresView || admissionRequiresView)
                && !sameAdmissionInputs(admissionView, currentView);
    }

    void publishMaterial(long currentMaterialRevision, long currentSourceMaterialRevision) {
        materialRevision = currentMaterialRevision;
        sourceMaterialRevision = currentSourceMaterialRevision;
    }

    void publishTopology(
            RtSectionActiveViewAssembler.Snapshot nextSnapshot,
            long currentGeometryRevision,
            long currentMaterialRevision,
            long currentResidentMembershipRevision,
            long currentActiveMembershipRevision,
            long currentSourceGeometryRevision,
            long currentSourceMaterialRevision,
            boolean currentAdmissionRequiresView,
            RendererViewState currentView
    ) {
        snapshot = Objects.requireNonNull(nextSnapshot, "nextSnapshot");
        admissionView = Objects.requireNonNull(currentView, "currentView");
        geometryRevision = currentGeometryRevision;
        materialRevision = currentMaterialRevision;
        residentMembershipRevision = currentResidentMembershipRevision;
        activeMembershipRevision = currentActiveMembershipRevision;
        sourceGeometryRevision = currentSourceGeometryRevision;
        sourceMaterialRevision = currentSourceMaterialRevision;
        admissionRequiresView = currentAdmissionRequiresView;
    }

    RtSectionActiveViewAssembler.Snapshot snapshot() {
        return snapshot;
    }

    void clear() {
        snapshot = RtSectionActiveViewAssembler.Snapshot.empty();
        geometryRevision = materialRevision = residentMembershipRevision = -1L;
        activeMembershipRevision = sourceGeometryRevision = sourceMaterialRevision = -1L;
        admissionRequiresView = false;
        admissionView = RendererViewState.allResident();
    }

    boolean available() {
        return geometryRevision >= 0L && materialRevision >= 0L;
    }

    long geometryRevision() {
        return geometryRevision;
    }

    long materialRevision() {
        return materialRevision;
    }

    boolean admissionRequiresView() {
        return admissionRequiresView;
    }

    static boolean topologyInputsChanged(
            long cachedResidentRevision,
            long residentRevision,
            long cachedActiveRevision,
            long activeRevision,
            long cachedSourceGeometryRevision,
            long sourceGeometryRevision
    ) {
        return cachedResidentRevision != residentRevision
                || cachedActiveRevision != activeRevision
                || cachedSourceGeometryRevision != sourceGeometryRevision;
    }

    static boolean sameAdmissionInputs(RendererViewState first, RendererViewState second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        return first.authoritative() == second.authoritative()
                && first.cameraValid() == second.cameraValid()
                && first.cameraSectionX() == second.cameraSectionX()
                && first.cameraSectionY() == second.cameraSectionY()
                && first.cameraSectionZ() == second.cameraSectionZ()
                && first.visibleSectionKeys().equals(second.visibleSectionKeys());
    }

    enum Refresh {
        HIT,
        MATERIAL_ONLY,
        TOPOLOGY
    }
}
