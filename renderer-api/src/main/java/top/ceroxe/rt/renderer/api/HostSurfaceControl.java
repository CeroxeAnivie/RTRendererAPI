package top.ceroxe.rt.renderer.api;

import java.util.Optional;

/** Owner-thread controls for GPU-only host primary-surface input. */
public interface HostSurfaceControl extends HostSurfaceAugmentationControl {
    HostSurfacePlan hostSurfacePlan();
    /** Prepare all new resources first; retain at most one old in-flight generation. */
    void hostSurfacePlan(HostSurfacePlan plan);
    /** Finite correction parameters and explicit common host/source color domain. */
    HostSurfaceAugmentation hostSurfaceAugmentation();
    void hostSurfaceAugmentation(HostSurfaceAugmentation parameters);
    /**
     * Nonblocking acquisition, empty when all current-generation slots are owned.
     * Retains the request's camera/projection/clock facts. Submit the same immutable request instance.
     */
    Optional<HostSurfaceInputLease> acquireHostSurfaceInput(RenderFrameRequest request,long resourceGeneration);
    /** Nonblocking acquisition of the latest atomic linear surface output; lease owns every plane. */
    Optional<HostSurfaceFrameLease> acquireHostSurfaceFrame();
    /** Discard a published input before its read begins, with GPU ready/done synchronization. */
    void discardHostSurfaceInput(long generation,long sequence);
    record Status(long currentGeneration,int residentGenerations,int hostReservations,int publishedInputs,
            int pendingGpuOperations,long residentTextureBytes,long publications,long consumedInputs,long abandonedInputs) { }
    Status hostSurfaceStatus();
    /** Opt-in asynchronous primary-work counters. OFF stops new counter work and readback copies. */
    void hostSurfaceWorkStatisticsEnabled(boolean enabled);
    /** Most recent completed sample; availability never implies that the current frame has completed. */
    record WorkStatistics(boolean available,long sequence,long sceneRevision,long suppliedPrimary,long fallbackPrimary,
            long invalidOrBackground,long reusedFill,long fallbackFill,long invalidFill) { }
    WorkStatistics hostSurfaceWorkStatistics();
}
