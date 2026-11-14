package top.ceroxe.rt.renderer.feature;

import top.ceroxe.rt.renderer.rt.acceleration.RtBottomLevelBuild;
import top.ceroxe.rt.renderer.rt.acceleration.RtDeviceTriangleBlasBuilder;
import top.ceroxe.rt.renderer.rt.device.RtCommandContext;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Vendor-owned acceleration-structure allocation, compaction, and lifetime boundary. */
public interface VulkanAccelerationStructureMemoryOptimizer {
    /**
     * Submits one triangle BLAS into the supplied ordered build lane.
     *
     * <p>Implementations own all destination, scratch, query, and compaction storage. They must
     * retain that ownership until the returned build completes or closes.</p>
     *
     * @param commands ordered build queue used to record and submit native work
     * @param geometries non-empty immutable geometry descriptions retained by the caller
     * @return asynchronous build owner that transfers exactly one completed BLAS
     */
    RtBottomLevelBuild submitTriangleBlas(
            RtCommandContext commands,
            List<RtDeviceTriangleBlasBuilder.Geometry> geometries
    );

    /**
     * Begins a one-way recovery after this optimizer failed to submit or complete a BLAS.
     *
     * <p>An empty result is a strict contract: the failure must terminate the renderer operation.
     * A present result permanently removes this optimizer from future build selection and gives
     * core permission to rebuild the complete affected generation. The recovery is published as
     * a fallback only after {@link FailureRecovery#commitCoreSubmission()} confirms that every
     * replacement BLAS was accepted by the core Vulkan queue.</p>
     *
     * @param failure original optimizer failure
     * @return recovery token for a preferred feature, or empty for a required feature
     */
    default Optional<FailureRecovery> beginFailureRecovery(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        return Optional.empty();
    }

    /** Commit token separating recovery intent from accepted core-GPU work evidence. */
    interface FailureRecovery {
        /** Publishes the fallback after all replacement BLAS submissions were accepted. */
        void commitCoreSubmission();
    }
}
