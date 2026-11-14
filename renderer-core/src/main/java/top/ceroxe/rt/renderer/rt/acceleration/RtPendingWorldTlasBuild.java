package top.ceroxe.rt.renderer.rt.acceleration;

import top.ceroxe.rt.renderer.DynamicRenderScene;
import top.ceroxe.rt.renderer.RendererFrameCausality;
import top.ceroxe.rt.renderer.RendererViewState;
import top.ceroxe.rt.renderer.SectionCausalitySnapshot;
import top.ceroxe.rt.renderer.SectionRevisionSnapshot;
import top.ceroxe.rt.renderer.rt.material.RtSceneMaterialTable;
import top.ceroxe.rt.renderer.scene.PackedSectionMembership;

import java.util.Objects;

/**
 * Owns one submitted world-TLAS build until it either completes or is closed.
 *
 * <p>The input is immutable and fully validated before native work is
 * submitted. The owner deliberately contains no mutable cache state, so a
 * caller must explicitly commit the result or dispose it as stale.</p>
 */
final class RtPendingWorldTlasBuild implements AutoCloseable {
    private final RtAccelerationStructure.WorldTlasBuildSubmission submission;
    private final RtWorldTlasBuildInput input;
    private final RendererFrameCausality causality;
    private final boolean urgent;

    RtPendingWorldTlasBuild(
            RtAccelerationStructure.WorldTlasBuildSubmission submission,
            RtWorldTlasBuildInput input,
            RendererFrameCausality causality,
            boolean urgent
    ) {
        this.submission = Objects.requireNonNull(submission, "submission");
        this.input = Objects.requireNonNull(input, "input");
        this.causality = Objects.requireNonNull(causality, "causality");
        this.urgent = urgent;
    }

    RtAccelerationStructure.CompletedWorldTlasBuild completeIfReady() {
        return submission.completeIfReady();
    }

    long revision() {
        return input.revision();
    }

    long sectionRevision() {
        return input.sectionRevision();
    }

    long sectionResourceRevision() {
        return input.sectionResourceRevision();
    }

    long sectionMaterialRevision() {
        return input.sectionMaterialRevision();
    }

    RendererViewState viewState() {
        return input.viewState();
    }

    long dynamicRevision() {
        return input.dynamicRevision();
    }

    long dynamicTopologyRevision() {
        return input.dynamicTopologyRevision();
    }

    long dynamicGeometryRevision() {
        return input.dynamicGeometryRevision();
    }

    long dynamicMaterialRevision() {
        return input.dynamicMaterialRevision();
    }

    DynamicRenderScene dynamicScene() {
        return input.dynamicScene();
    }

    int instanceTopologyHash() {
        return input.instanceTopologyHash();
    }

    PackedSectionMembership sectionMembership() {
        return input.sectionMembership();
    }

    SectionRevisionSnapshot sectionContentRevisions() {
        return input.sectionContentRevisions();
    }

    SectionCausalitySnapshot sectionCausalities() {
        return input.sectionCausalities();
    }

    int instanceCount() {
        return input.activeInstanceCount();
    }

    int tlasInstanceCapacity() {
        return input.instances().size();
    }

    int sectionInstanceCount() {
        return input.sectionInstanceCount();
    }

    int dynamicInstanceCount() {
        return input.dynamicInstanceCount();
    }

    int terrainMaterialCount() {
        return input.terrainMaterialCount();
    }

    RtSceneMaterialTable.Snapshot materialSnapshot() {
        return input.materialSnapshot();
    }

    RendererFrameCausality causality() {
        return causality;
    }

    long coverageContractionAuthorizationGeneration() {
        return input.coverageContractionAuthorizationGeneration();
    }

    boolean submittedWithPendingBacklog() {
        return input.hasPendingSectionBuilds();
    }

    boolean urgent() {
        return urgent;
    }

    @Override
    public void close() {
        submission.close();
    }
}
