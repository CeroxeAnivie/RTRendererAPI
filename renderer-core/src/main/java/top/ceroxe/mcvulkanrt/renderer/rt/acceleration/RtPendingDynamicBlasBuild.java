package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import top.ceroxe.mcvulkanrt.renderer.RendererFrameCausality;

import java.util.Objects;

/** Owns one submitted legacy dynamic BLAS build until the cache commits or discards it. */
final class RtPendingDynamicBlasBuild implements AutoCloseable {
    private final RtAccelerationStructure.DynamicBlasBuildSubmission submission;
    private final RtDynamicTriangleMesh mesh;
    private final RendererFrameCausality causality;

    RtPendingDynamicBlasBuild(
            RtAccelerationStructure.DynamicBlasBuildSubmission submission,
            RtDynamicTriangleMesh mesh,
            RendererFrameCausality causality
    ) {
        this.submission = Objects.requireNonNull(submission, "submission");
        this.mesh = Objects.requireNonNull(mesh, "mesh");
        this.causality = Objects.requireNonNull(causality, "causality");
    }

    RtAccelerationStructure.DynamicBlasBuildSubmission submission() { return submission; }
    RtDynamicTriangleMesh mesh() { return mesh; }
    RendererFrameCausality causality() { return causality; }

    @Override
    public void close() {
        submission.close();
    }
}
