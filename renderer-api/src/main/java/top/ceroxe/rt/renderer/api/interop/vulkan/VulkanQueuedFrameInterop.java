package top.ceroxe.rt.renderer.api.interop.vulkan;

import java.util.Optional;

/** Opt-in delivery of submitted images whose producer completion is enforced on the GPU. */
public interface VulkanQueuedFrameInterop {
    /**
     * Enables exported binary ready signals before the first frame is submitted.
     * Repeated calls before submission are harmless. Unsupported devices fail explicitly.
     */
    void enableBinarySemaphoreDelivery();

    /**
     * Claims exactly {@code frameSequence} after its successful native submission is published.
     * This method does not wait for the producer fence and never substitutes a newer frame.
     * The consumer must wait the lease's binary acquire signal before touching the image,
     * then publish completion after that wait and all image access. A binary signal is consumed
     * exactly once; closing an unimported, unused lease lets the provider drain it safely.
     * The ordinary {@link VulkanFrameInterop#pollLatestFrame()} keeps its completed-frame semantics.
     *
     * @param frameSequence non-negative exact sequence requested
     * @return exclusive active lease, or empty if not submitted, already claimed or retired
     */
    Optional<GpuFrameLease> tryAcquireSubmittedFrame(long frameSequence);
}
