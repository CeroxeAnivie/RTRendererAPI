package top.ceroxe.mcvulkanrt.renderer.backend.vulkan;

/**
 * Native transfer boundary for persistent GPUScene buffers.
 *
 * <p>The scene transaction coordinator depends on this narrow protocol rather than Vulkan handles.
 * That keeps CPU publication rules independently testable while the production implementation can
 * retain strict ownership of command buffers, fences, staging allocations, and retired generations.</p>
 */
interface VulkanGpuSceneTransferQueue extends AutoCloseable {
    TransferTicket submit(VulkanGpuSceneUploadPlanner.Plan uploadPlan);

    boolean pollAndActivate(TransferTicket transfer, long retireAfterEpoch);

    void waitAndActivate(TransferTicket transfer, long retireAfterEpoch);

    void releaseRetiredThrough(long completedDescriptorEpoch);

    BufferBinding buffer(VulkanGpuSceneUploadPlanner.Target target);

    TransferState state();

    @Override
    void close();

    interface TransferTicket {
        long revision();

        boolean activated();
    }

    record BufferBinding(long buffer, long deviceAddress, long capacityBytes) {
        public BufferBinding {
            if (buffer == 0L || deviceAddress == 0L || capacityBytes <= 0L) {
                throw new IllegalArgumentException("GPUScene buffer binding is invalid");
            }
        }
    }

    record TransferState(
            long activeRevision,
            int activeBuffers,
            long activeBytes,
            boolean pending,
            int retiredBuffers,
            long retiredBytes
    ) {
        public TransferState {
            if (activeRevision < -1L || activeBuffers < 0 || activeBytes < 0L
                    || retiredBuffers < 0 || retiredBytes < 0L) {
                throw new IllegalArgumentException("GPUScene transfer state contains a negative counter");
            }
        }
    }
}
