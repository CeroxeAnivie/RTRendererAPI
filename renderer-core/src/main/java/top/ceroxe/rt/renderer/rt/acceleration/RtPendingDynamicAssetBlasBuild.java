package top.ceroxe.rt.renderer.rt.acceleration;

import top.ceroxe.rt.renderer.DynamicMeshAsset;
import top.ceroxe.rt.renderer.RendererFrameCausality;

import java.util.Objects;

/**
 * Owns one submitted reusable dynamic-asset BLAS build until resident publication.
 */
final class RtPendingDynamicAssetBlasBuild implements AutoCloseable {
    private final DynamicMeshAsset asset;
    private final RtAccelerationStructure.DynamicBlasBuildSubmission submission;
    private final RendererFrameCausality causality;

    RtPendingDynamicAssetBlasBuild(
            DynamicMeshAsset asset,
            RtAccelerationStructure.DynamicBlasBuildSubmission submission,
            RendererFrameCausality causality
    ) {
        this.asset = Objects.requireNonNull(asset, "asset");
        this.submission = Objects.requireNonNull(submission, "submission");
        this.causality = Objects.requireNonNull(causality, "causality");
    }

    DynamicMeshAsset asset() {
        return asset;
    }

    RtAccelerationStructure.DynamicBlasBuildSubmission submission() {
        return submission;
    }

    RendererFrameCausality causality() {
        return causality;
    }

    @Override
    public void close() {
        submission.close();
    }
}
