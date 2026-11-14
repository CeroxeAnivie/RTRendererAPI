package top.ceroxe.rt.renderer.rt.acceleration;

import top.ceroxe.rt.renderer.RendererViewState;
import top.ceroxe.rt.renderer.scene.SectionKey;

import java.util.Objects;
import java.util.Set;

/**
 * Pure policy for logical-view admission and cached active-view refresh scope.
 */
final class RtSectionActiveViewPolicy {
    private RtSectionActiveViewPolicy() {
    }

    static RendererViewState physicalAdmissionView(
            RendererViewState logicalView,
            Set<SectionKey> retainedPresentationSections
    ) {
        Objects.requireNonNull(logicalView, "logicalView");
        Objects.requireNonNull(retainedPresentationSections, "retainedPresentationSections");
        /* Submitted presentation generations retain resources, not successor active slots. */
        return logicalView;
    }

    static Refresh refresh(
            long cachedGeometryRevision,
            long geometryRevision,
            long cachedMaterialRevision,
            long materialRevision,
            boolean admissionInputsChanged
    ) {
        if (cachedGeometryRevision != geometryRevision || admissionInputsChanged) {
            return Refresh.TOPOLOGY;
        }
        return cachedMaterialRevision == materialRevision ? Refresh.HIT : Refresh.MATERIAL_ONLY;
    }

    enum Refresh {
        HIT,
        MATERIAL_ONLY,
        TOPOLOGY
    }
}
