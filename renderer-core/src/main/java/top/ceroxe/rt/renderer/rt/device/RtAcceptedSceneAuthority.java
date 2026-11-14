package top.ceroxe.rt.renderer.rt.device;

import top.ceroxe.rt.renderer.RendererFrameCausality;

import java.util.Objects;

/**
 * Retains the latest accepted host authority independently for view, terrain, and dynamic lanes.
 *
 * <p>Asynchronous world builds often finish on a later frame than the update that caused them.
 * Selecting causality from this owner preserves the originating lane instead of incorrectly
 * attributing completed GPU work to whichever frame happened to poll it.</p>
 */
final class RtAcceptedSceneAuthority {
    private RendererFrameCausality terrainCausality = RendererFrameCausality.untraced(0L);
    private RendererFrameCausality dynamicCausality = RendererFrameCausality.untraced(0L);
    private long viewRevision = -1L;

    void acceptViewRevision(long acceptedViewRevision) {
        viewRevision = acceptedViewRevision;
    }

    void acceptTerrain(RendererFrameCausality causality) {
        terrainCausality = Objects.requireNonNull(causality, "causality");
    }

    void acceptDynamic(RendererFrameCausality causality) {
        dynamicCausality = Objects.requireNonNull(causality, "causality");
    }

    long viewRevision() {
        return viewRevision;
    }

    RendererFrameCausality terrainCausality() {
        return terrainCausality;
    }

    RendererFrameCausality dynamicCausality() {
        return dynamicCausality;
    }

    RendererFrameCausality worldBuildCausality(
            boolean terrainWork,
            boolean dynamicWork,
            RendererFrameCausality currentCausality
    ) {
        if (terrainWork) {
            return terrainCausality;
        }
        if (dynamicWork) {
            return dynamicCausality;
        }
        return Objects.requireNonNull(currentCausality, "currentCausality");
    }
}
